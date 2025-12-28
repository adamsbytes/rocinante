import { watch, existsSync, readFileSync, writeFileSync, mkdirSync } from 'fs';
import { join, dirname } from 'path';
import type { BotRuntimeStatus, BotCommand, CommandsFile } from '../shared/types';

/**
 * Status file watcher and WebSocket broadcaster.
 * 
 * Watches status.json files in Docker volumes and broadcasts updates
 * to connected WebSocket clients.
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
 * Ensure status directory exists for a bot.
 */
export function ensureStatusDir(botId: string): void {
  const dir = getStatusDir(botId);
  if (!existsSync(dir)) {
    mkdirSync(dir, { recursive: true });
  }
}

// ============================================================================
// Status Reading
// ============================================================================

/**
 * Read current status for a bot.
 * Returns null if status file doesn't exist or can't be read.
 */
export function readBotStatus(botId: string): BotRuntimeStatus | null {
  const filePath = getStatusFilePath(botId);
  
  if (!existsSync(filePath)) {
    return null;
  }
  
  try {
    const content = readFileSync(filePath, 'utf-8');
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
  watcher: ReturnType<typeof watch> | null;
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
      watcher: null,
      callbacks: new Set(),
      lastStatus: null,
      pollInterval: null,
    };
    watchers.set(botId, entry);
    
    // Start watching
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
 */
function startWatcher(botId: string, entry: WatcherEntry): void {
  const statusDir = getStatusDir(botId);
  const statusFile = getStatusFilePath(botId);
  
  // Ensure directory exists
  ensureStatusDir(botId);
  
  // Read initial status
  const initialStatus = readBotStatus(botId);
  if (initialStatus) {
    entry.lastStatus = initialStatus;
  }
  
  // Try to use fs.watch for efficient file watching
  try {
    entry.watcher = watch(statusDir, (eventType, filename) => {
      if (filename === 'status.json') {
        handleStatusChange(botId, entry);
      }
    });
    
    entry.watcher.on('error', (error) => {
      console.error(`Watcher error for bot ${botId}:`, error);
      // Fall back to polling
      startPolling(botId, entry);
    });
  } catch (error) {
    console.warn(`Could not start file watcher for bot ${botId}, using polling:`, error);
    startPolling(botId, entry);
  }
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
function handleStatusChange(botId: string, entry: WatcherEntry): void {
  const status = readBotStatus(botId);
  
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
  if (entry.watcher) {
    entry.watcher.close();
    entry.watcher = null;
  }
  
  if (entry.pollInterval) {
    clearInterval(entry.pollInterval);
    entry.pollInterval = null;
  }
}

// ============================================================================
// Command Writing
// ============================================================================

/**
 * Send a command to a bot.
 * Writes the command to the bot's commands.json file.
 */
export function sendBotCommand(botId: string, command: Omit<BotCommand, 'timestamp'>): void {
  const commandsFile = getCommandsFilePath(botId);
  ensureStatusDir(botId);
  
  // Read existing commands
  let commandsData: CommandsFile = { commands: [] };
  if (existsSync(commandsFile)) {
    try {
      const content = readFileSync(commandsFile, 'utf-8');
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
  
  // Write back
  try {
    writeFileSync(commandsFile, JSON.stringify(commandsData, null, 2));
    console.log(`Command sent to bot ${botId}:`, fullCommand.type);
  } catch (error) {
    console.error(`Error writing command for bot ${botId}:`, error);
    throw error;
  }
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
export function handleStatusWebSocket(ws: WebSocket, botId: string): void {
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
  ws.addEventListener('message', (event) => {
    try {
      const data = typeof event.data === 'string' ? event.data : event.data.toString();
      const message = JSON.parse(data);
      
      if (message.type === 'command' && message.command) {
        sendBotCommand(botId, message.command);
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
  const currentStatus = readBotStatus(botId);
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

