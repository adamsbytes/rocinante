import { mkdir, unlink, chmod } from 'fs/promises';
import { join } from 'path';
import type { BotRuntimeStatus, BotCommand, CommandsFile } from '../shared/types';

/**
 * Status file watcher and WebSocket broadcaster.
 * 
 * Watches status.json files in Docker volumes and broadcasts updates
 * to connected WebSocket clients.
 * 
 * Uses Bun's native file I/O APIs for better performance.
 */

// Base path for bot status directories
const DATA_DIR = process.env.DATA_DIR || join(process.cwd(), 'data');

/**
 * Get the status directory path for a bot.
 * The bot writes to /home/runelite/.runelite/rocinante/ inside the container,
 * which is mounted to data/status/<botId>/ on the host.
 */
export function getStatusDir(botId: string): string {
  return join(DATA_DIR, 'status', botId);
}

/**
 * Screenshots directory for a bot.
 * Mounted to /home/runelite/.runelite/screenshots inside the container.
 */
export function getScreenshotsDir(botId: string): string {
  return join(getStatusDir(botId), 'screenshots');
}

/**
 * Shared cache directory (shared across all bots).
 * Used for wiki cache to avoid per-account duplication.
 */
export function getSharedCacheDir(): string {
  return join(DATA_DIR, 'status', 'cache');
}

/**
 * Training spot cache directory (shared across all bots).
 * Used for caching ranked training object positions.
 */
export function getTrainingSpotCacheDir(): string {
  return join(DATA_DIR, 'status', 'training-cache');
}

/**
 * Bolt launcher data directory for a bot.
 * Stores login session and launcher state.
 * Mounted to /home/runelite/.local/share/bolt-launcher inside the container.
 */
export function getBoltDataDir(botId: string): string {
  return join(getStatusDir(botId), 'bolt-launcher');
}

/**
 * Get the status file path for a bot.
 */
export function getStatusFilePath(botId: string): string {
  return join(getStatusDir(botId), 'status.json');
}

/**
 * Get the commands file path for a bot.
 */
export function getCommandsFilePath(botId: string): string {
  return join(getStatusDir(botId), 'commands.json');
}

/**
 * Get the VNC socket path for a bot.
 * The container creates vnc.sock in /home/runelite/.local/share/bolt-launcher/.runelite/rocinante/
 * which is bind-mounted to data/status/<botId>/ on the host.
 */
export function getVncSocketPath(botId: string): string {
  return join(getStatusDir(botId), 'vnc.sock');
}

/**
 * Get the machine-id file path for a bot.
 * This file is created by docker.ts and bind-mounted into the container at /etc/machine-id.
 */
export function getMachineIdPath(botId: string): string {
  return join(getStatusDir(botId), 'machine-id');
}

/**
 * Ensure status directory exists for a bot with proper permissions.
 * The directory must be writable by the container's runelite user (UID 1000).
 * We use chmod after mkdir because mkdir's mode is affected by umask.
 * Container creates files as runelite (UID 1000) via bind mount - no chown needed.
 */
export async function ensureStatusDir(botId: string): Promise<void> {
  const dir = getStatusDir(botId);
  await mkdir(dir, { recursive: true });
  await chmod(dir, 0o777);
}

/**
 * Ensure the screenshots directory exists for a bot.
 */
export async function ensureScreenshotsDir(botId: string): Promise<void> {
  const dir = getScreenshotsDir(botId);
  await mkdir(dir, { recursive: true });
  await chmod(dir, 0o777);
}

/**
 * Ensure the shared cache directory exists.
 */
export async function ensureSharedCacheDir(): Promise<void> {
  const dir = getSharedCacheDir();
  await mkdir(dir, { recursive: true });
  await chmod(dir, 0o777);
}

/**
 * Ensure the training spot cache directory exists.
 */
export async function ensureTrainingSpotCacheDir(): Promise<void> {
  const dir = getTrainingSpotCacheDir();
  await mkdir(dir, { recursive: true });
  await chmod(dir, 0o777);
}

/**
 * Ensure the bolt launcher data directory exists for a bot.
 * Container creates files as runelite (UID 1000) via bind mount - no chown needed.
 */
export async function ensureBoltDataDir(botId: string): Promise<void> {
  const dir = getBoltDataDir(botId);
  await mkdir(dir, { recursive: true });
  await chmod(dir, 0o777);
}

// ============================================================================
// Status Reading
// ============================================================================

/**
 * Read current status for a bot.
 * Returns null if status file doesn't exist or can't be read.
 */
export async function readBotStatus(botId: string): Promise<BotRuntimeStatus | null> {
  const filePath = getStatusFilePath(botId);
  const file = Bun.file(filePath);
  
  if (!(await file.exists())) {
    return null;
  }
  
  try {
    const content = await file.text();
    return JSON.parse(content) as BotRuntimeStatus;
  } catch (error) {
    console.error(`Error reading status for bot ${botId}:`, error);
    return null;
  }
}

// ============================================================================
// Status File Watching
// ============================================================================

type StatusCallback = (status: BotRuntimeStatus) => void;

interface WatcherEntry {
  callbacks: Set<StatusCallback>;
  lastStatus: BotRuntimeStatus | null;
  pollInterval: NodeJS.Timeout | null;
}

// Active watchers per bot
const watchers = new Map<string, WatcherEntry>();

/**
 * Start watching status file for a bot.
 * Calls the callback whenever the status changes.
 */
export function watchBotStatus(botId: string, callback: StatusCallback): () => void {
  let entry = watchers.get(botId);
  
  if (!entry) {
    entry = {
      callbacks: new Set(),
      lastStatus: null,
      pollInterval: null,
    };
    watchers.set(botId, entry);
    
    // Start polling for status updates
    startWatcher(botId, entry);
  }
  
  entry.callbacks.add(callback);
  
  // Send current status immediately if available
  if (entry.lastStatus) {
    callback(entry.lastStatus);
  }
  
  // Return cleanup function
  return () => {
    entry!.callbacks.delete(callback);
    
    // Stop watcher if no more callbacks
    if (entry!.callbacks.size === 0) {
      stopWatcher(botId, entry!);
      watchers.delete(botId);
    }
  };
}

/**
 * Start the file watcher for a bot's status file.
 * 
 * NOTE: We always use polling because fs.watch is unreliable with Docker volume mounts.
 * The files are written by the container to a mounted volume, and fs.watch often
 * silently fails to detect changes in this scenario.
 */
async function startWatcher(botId: string, entry: WatcherEntry): Promise<void> {
  // Ensure directory exists
  await ensureStatusDir(botId);
  
  // Read initial status
  const initialStatus = await readBotStatus(botId);
  if (initialStatus) {
    entry.lastStatus = initialStatus;
  }
  
  // Always use polling for Docker volume reliability
  startPolling(botId, entry);
}

/**
 * Start polling as fallback when fs.watch is not available.
 */
function startPolling(botId: string, entry: WatcherEntry): void {
  if (entry.pollInterval) {
    return; // Already polling
  }
  
  // Poll every second
  entry.pollInterval = setInterval(() => {
    handleStatusChange(botId, entry);
  }, 1000);
}

/**
 * Handle status file change.
 */
async function handleStatusChange(botId: string, entry: WatcherEntry): Promise<void> {
  const status = await readBotStatus(botId);
  
  if (!status) {
    return;
  }
  
  // Only notify if status actually changed
  if (entry.lastStatus && entry.lastStatus.timestamp >= status.timestamp) {
    return;
  }
  
  entry.lastStatus = status;
  
  // Notify all callbacks
  for (const callback of entry.callbacks) {
    try {
      callback(status);
    } catch (error) {
      console.error(`Error in status callback for bot ${botId}:`, error);
    }
  }
}

/**
 * Stop the watcher for a bot.
 */
function stopWatcher(botId: string, entry: WatcherEntry): void {
  if (entry.pollInterval) {
    clearInterval(entry.pollInterval);
    entry.pollInterval = null;
  }
}

/**
 * Reset the status file for a bot to initial state.
 * Called when stopping/restarting to prevent stale data on next start.
 * 
 * Note: The container writes status.json as UID 1000, so we may not have
 * permission to overwrite it directly. We delete and recreate instead.
 */
export async function resetStatusFile(botId: string): Promise<void> {
  const filePath = getStatusFilePath(botId);
  
  const initialStatus: BotRuntimeStatus = {
    timestamp: Date.now(),
    gameState: 'LOGIN_SCREEN',
    task: null,
    session: null,
    player: null,
    queue: {
      pending: 0,
      descriptions: [],
    },
    quests: null,
  };
  
  try {
    await ensureStatusDir(botId);
    
    // Delete existing file first (may be owned by container user UID 1000)
    // Directory has 0o777 so we can delete even if we can't overwrite
    try {
      await unlink(filePath);
    } catch (unlinkError) {
      // Ignore if file doesn't exist
      if ((unlinkError as NodeJS.ErrnoException).code !== 'ENOENT') {
        throw unlinkError;
      }
    }
    
    // Write new file (will be owned by current user)
    await Bun.write(filePath, JSON.stringify(initialStatus, null, 2));
    console.log(`Reset status file for bot ${botId}`);
  } catch (error) {
    console.warn(`Failed to reset status file for bot ${botId}:`, error);
  }
  
  // Also update cached status in any active watcher
  const entry = watchers.get(botId);
  if (entry) {
    entry.lastStatus = initialStatus;
    // Notify callbacks of the reset
    for (const callback of entry.callbacks) {
      try {
        callback(initialStatus);
      } catch (err) {
        console.error(`Error in status callback for bot ${botId}:`, err);
      }
    }
  }
}

// ============================================================================
// Command Writing
// ============================================================================

// Per-bot locks to prevent race conditions when writing commands
const commandLocks = new Map<string, Promise<void>>();

/**
 * Send a command to a bot.
 * Writes the command to the bot's commands.json file.
 * Uses per-bot locking to prevent race conditions.
 */
export async function sendBotCommand(botId: string, command: Omit<BotCommand, 'timestamp'>): Promise<void> {
  // Wait for any pending write to complete, then run ours
  const existingLock = commandLocks.get(botId) ?? Promise.resolve();
  
  const writePromise = existingLock.then(async () => {
  const commandsFilePath = getCommandsFilePath(botId);
  await ensureStatusDir(botId);
  
  // Read existing commands
  let commandsData: CommandsFile = { commands: [] };
  const file = Bun.file(commandsFilePath);
  if (await file.exists()) {
    try {
      const content = await file.text();
      commandsData = JSON.parse(content);
    } catch (error) {
      console.warn(`Error reading commands file for bot ${botId}, creating new:`, error);
    }
  }
  
  // Add new command with timestamp
  const fullCommand: BotCommand = {
    ...command,
    timestamp: Date.now(),
  };
  commandsData.commands.push(fullCommand);
  
  // Keep only recent commands (last 100)
  if (commandsData.commands.length > 100) {
    commandsData.commands = commandsData.commands.slice(-100);
  }
  
  // Write back using Bun.write
  try {
    await Bun.write(commandsFilePath, JSON.stringify(commandsData, null, 2));
    console.log(`Command sent to bot ${botId}:`, fullCommand.type);
  } catch (error) {
    console.error(`Error writing command for bot ${botId}:`, error);
    throw error;
  }
  });
  
  // Update the lock for this bot
  commandLocks.set(botId, writePromise.catch(() => {})); // Swallow errors for lock chain
  
  // Wait for our write to complete (will throw if it failed)
  await writePromise;
}

// ============================================================================
// WebSocket Management
// ============================================================================

interface WebSocketClient {
  ws: WebSocket;
  botId: string;
  cleanup: (() => void) | null;
}

// Active WebSocket connections
const wsClients = new Map<WebSocket, WebSocketClient>();

/**
 * Handle a new WebSocket connection for bot status.
 */
export async function handleStatusWebSocket(ws: WebSocket, botId: string): Promise<void> {
  console.log(`WebSocket connected for bot ${botId}`);
  
  const client: WebSocketClient = {
    ws,
    botId,
    cleanup: null,
  };
  wsClients.set(ws, client);
  
  // Start watching status for this bot
  client.cleanup = watchBotStatus(botId, (status) => {
    if (ws.readyState === WebSocket.OPEN) {
      try {
        ws.send(JSON.stringify(status));
      } catch (error) {
        console.error(`Error sending status to WebSocket:`, error);
      }
    }
  });
  
  // Handle incoming messages (commands from client)
  ws.addEventListener('message', async (event) => {
    try {
      const data = typeof event.data === 'string' ? event.data : event.data.toString();
      const message = JSON.parse(data);
      
      if (message.type === 'command' && message.command) {
        await sendBotCommand(botId, message.command);
      }
    } catch (error) {
      console.error(`Error handling WebSocket message:`, error);
    }
  });
  
  // Handle close
  ws.addEventListener('close', () => {
    console.log(`WebSocket disconnected for bot ${botId}`);
    
    if (client.cleanup) {
      client.cleanup();
    }
    wsClients.delete(ws);
  });
  
  // Handle error
  ws.addEventListener('error', (error) => {
    console.error(`WebSocket error for bot ${botId}:`, error);
  });
  
  // Send initial status if available
  const currentStatus = await readBotStatus(botId);
  if (currentStatus && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(currentStatus));
  }
}

/**
 * Get the number of active WebSocket connections.
 */
export function getActiveConnectionCount(): number {
  return wsClients.size;
}

/**
 * Close all WebSocket connections for a bot.
 */
export function closeConnectionsForBot(botId: string): void {
  for (const [ws, client] of wsClients.entries()) {
    if (client.botId === botId) {
      ws.close();
    }
  }
}

/**
 * Clean up all watchers and connections.
 * Call on server shutdown.
 */
export function cleanup(): void {
  // Close all WebSocket connections
  for (const [ws, client] of wsClients.entries()) {
    if (client.cleanup) {
      client.cleanup();
    }
    ws.close();
  }
  wsClients.clear();
  
  // Stop all watchers
  for (const [botId, entry] of watchers.entries()) {
    stopWatcher(botId, entry);
  }
  watchers.clear();
}

