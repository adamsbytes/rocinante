/**
 * Docker Worker - Dedicated process for Docker operations.
 * 
 * This is the only process with Docker socket access.
 * Communicates with the web server via Unix socket.
 */

import { randomUUIDv7 } from 'bun';
import type { Socket, TCPSocketListener, UnixSocketListener } from 'bun';
import { unlink } from 'fs/promises';
import { join } from 'path';
import {
  parseClientMessage,
  serializeMessage,
  type ClientMessage,
  type CommandResponse,
  type AuthResponse,
  type DockerStatusFile,
} from '../shared/worker-protocol';
import { verifyToken } from '../shared/socket-auth';
import {
  startBot,
  stopBot,
  restartBot,
  removeContainer,
  getAllContainerStatuses,
  checkDockerConnection,
  checkBotImage,
} from './docker';
import {
  subscribeLogs,
  unsubscribeLogs,
  unsubscribeAll,
  addLogSeparator,
  restartLogStream,
} from './logs';

const DATA_DIR = process.env.DATA_DIR || './data';
const SOCKET_PATH = join(DATA_DIR, 'docker.sock');
const STATUS_FILE_PATH = join(DATA_DIR, 'docker-status.json');

// Shared secret for socket authentication (required)
const WORKER_SHARED_SECRET = process.env.WORKER_SHARED_SECRET;

// Status update interval (1 second)
const STATUS_UPDATE_INTERVAL_MS = 1000;

// Track connected clients
const clients = new Set<Socket<SocketData>>();

interface SocketData {
  buffer: string;
  /** Whether this connection has been authenticated */
  authenticated: boolean;
}

// =============================================================================
// Socket Server
// =============================================================================

let server: UnixSocketListener<SocketData> | null = null;

async function startSocketServer(): Promise<void> {
  // Remove existing socket file if it exists
  try {
    await unlink(SOCKET_PATH);
  } catch {
    // Ignore if doesn't exist
  }

  server = Bun.listen<SocketData>({
    unix: SOCKET_PATH,
    socket: {
      open(socket) {
        console.log('[Worker] Client connected (awaiting auth)');
        socket.data = { buffer: '', authenticated: false };
        clients.add(socket);
      },

      data(socket, data) {
        // Accumulate data in buffer
        socket.data.buffer += data.toString();

        // Process complete JSON lines
        let newlineIndex: number;
        while ((newlineIndex = socket.data.buffer.indexOf('\n')) !== -1) {
          const line = socket.data.buffer.slice(0, newlineIndex);
          socket.data.buffer = socket.data.buffer.slice(newlineIndex + 1);

          if (line.trim()) {
            handleMessage(socket, line);
          }
        }
      },

      close(socket) {
        console.log('[Worker] Client disconnected');
        clients.delete(socket);
        unsubscribeAll(socket);
      },

      error(socket, error) {
        console.error('[Worker] Socket error:', error);
        clients.delete(socket);
        unsubscribeAll(socket);
      },
    },
  });

  console.log(`[Worker] Socket server listening on ${SOCKET_PATH}`);
}

async function handleMessage(socket: Socket<SocketData>, line: string): Promise<void> {
  const message = parseClientMessage(line);
  
  if (!message) {
    console.warn('[Worker] Invalid message received:', line.slice(0, 100));
    return;
  }

  // Handle authentication first
  if (message.type === 'auth') {
    await handleAuth(socket, message);
    return;
  }

  // Reject all other messages if not authenticated
  if (!socket.data.authenticated) {
    console.warn('[Worker] Rejecting message from unauthenticated client:', message.type);
    return;
  }

  switch (message.type) {
    case 'command':
      await handleCommand(socket, message);
      break;

    case 'subscribe':
      subscribeLogs(message.botId, socket);
      break;

    case 'unsubscribe':
      unsubscribeLogs(message.botId, socket);
      break;
  }
}

async function handleAuth(socket: Socket<SocketData>, message: ClientMessage & { type: 'auth' }): Promise<void> {
  const { token, clientId } = message;

  // Validate shared secret is configured
  if (!WORKER_SHARED_SECRET) {
    console.error('[Worker] WORKER_SHARED_SECRET not configured - rejecting auth');
    sendAuthResponse(socket, false, 'Server misconfigured');
    return;
  }

  // Verify the token
  const valid = verifyToken(token, clientId, WORKER_SHARED_SECRET);
  
  if (valid) {
    socket.data.authenticated = true;
    console.log(`[Worker] Client authenticated: ${clientId}`);
    sendAuthResponse(socket, true);
  } else {
    console.warn(`[Worker] Authentication failed for client: ${clientId}`);
    sendAuthResponse(socket, false, 'Invalid or expired token');
  }
}

function sendAuthResponse(socket: Socket<SocketData>, success: boolean, error?: string): void {
  const response: AuthResponse = {
    type: 'auth_response',
    success,
    error,
  };

  try {
    socket.write(serializeMessage(response));
  } catch {
    // Socket may be closed
  }
}

async function handleCommand(socket: Socket<SocketData>, message: ClientMessage & { type: 'command' }): Promise<void> {
  const { id, command, botId } = message;

  let success = false;
  let error: string | undefined;

  try {
    switch (command) {
      case 'start':
        addLogSeparator(botId, 'Starting container');
        await startBot(botId);
        // Restart log stream for any subscribers
        await restartLogStream(botId);
        success = true;
        break;

      case 'stop':
        addLogSeparator(botId, 'Stopping container');
        await stopBot(botId);
        success = true;
        break;

      case 'restart':
        addLogSeparator(botId, 'Restarting container');
        await restartBot(botId);
        // Restart log stream for any subscribers
        await restartLogStream(botId);
        success = true;
        break;

      case 'remove':
        await removeContainer(botId);
        success = true;
        break;

      default:
        error = `Unknown command: ${command}`;
    }
  } catch (err) {
    error = err instanceof Error ? err.message : 'Unknown error';
    console.error(`[Worker] Command ${command} failed for ${botId}:`, err);
  }

  // Send response
  const response: CommandResponse = {
    type: 'response',
    id,
    success,
    error,
  };

  try {
    socket.write(serializeMessage(response));
  } catch {
    // Socket may be closed
  }
}

// =============================================================================
// Status File Updates
// =============================================================================

let statusUpdateTimer: ReturnType<typeof setInterval> | null = null;

async function updateStatusFile(): Promise<void> {
  try {
    const [containers, botImageExists] = await Promise.all([
      getAllContainerStatuses(),
      checkBotImage(),
    ]);

    const statusFile: DockerStatusFile = {
      lastUpdated: Date.now(),
      botImageExists,
      containers,
    };

    // Atomic write using Bun.write
    await Bun.write(STATUS_FILE_PATH, JSON.stringify(statusFile, null, 2));
  } catch (err) {
    console.error('[Worker] Failed to update status file:', err);
  }
}

function startStatusUpdates(): void {
  // Initial update
  updateStatusFile();

  // Schedule periodic updates
  statusUpdateTimer = setInterval(updateStatusFile, STATUS_UPDATE_INTERVAL_MS);
}

function stopStatusUpdates(): void {
  if (statusUpdateTimer) {
    clearInterval(statusUpdateTimer);
    statusUpdateTimer = null;
  }
}

// =============================================================================
// Startup & Shutdown
// =============================================================================

async function startup(): Promise<void> {
  console.log('[Worker] Starting Docker worker...');

  // Check shared secret is configured
  if (!WORKER_SHARED_SECRET) {
    console.error('[Worker] ERROR: WORKER_SHARED_SECRET environment variable is required');
    console.error('[Worker] Generate one with: openssl rand -hex 32');
    process.exit(1);
  }
  console.log('[Worker] Shared secret configured');

  // Check Docker connection
  const dockerOk = await checkDockerConnection();
  if (!dockerOk) {
    console.error('[Worker] ERROR: Cannot connect to Docker socket');
    console.error('[Worker] Make sure /var/run/docker.sock is accessible');
    process.exit(1);
  }
  console.log('[Worker] Docker connection OK');

  // Check bot image
  const imageOk = await checkBotImage();
  if (!imageOk) {
    console.warn('[Worker] WARNING: Bot image not found');
    console.warn('[Worker] Run: docker build -t rocinante-bot:latest ./bot');
  } else {
    console.log('[Worker] Bot image OK');
  }

  // Start socket server
  await startSocketServer();

  // Start status updates
  startStatusUpdates();

  console.log('[Worker] Docker worker ready');
}

async function shutdown(): Promise<void> {
  console.log('[Worker] Shutting down...');

  // Stop status updates
  stopStatusUpdates();

  // Close socket server
  if (server) {
    server.stop();
    server = null;
  }

  // Remove socket file
  try {
    await unlink(SOCKET_PATH);
  } catch {
    // Ignore
  }

  console.log('[Worker] Shutdown complete');
}

// Handle graceful shutdown
process.on('SIGINT', async () => {
  await shutdown();
  process.exit(0);
});

process.on('SIGTERM', async () => {
  await shutdown();
  process.exit(0);
});

// Start the worker
startup().catch((err) => {
  console.error('[Worker] Startup failed:', err);
  process.exit(1);
});
