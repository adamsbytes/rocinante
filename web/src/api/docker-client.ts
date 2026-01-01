/**
 * Docker client for the web server.
 * Communicates with the Docker worker via Unix socket.
 */

import { randomUUIDv7 } from 'bun';
import type { Socket } from 'bun';
import { join } from 'path';
import type { BotStatus } from '../shared/types';
import {
  serializeMessage,
  parseWorkerMessage,
  dockerStatusFileSchema,
  type AuthRequest,
  type CommandRequest,
  type SubscribeRequest,
  type UnsubscribeRequest,
  type CommandResponse,
  type LogMessage,
  type DockerStatusFile,
} from '../shared/worker-protocol';
import { makeToken } from '../shared/socket-auth';

const DATA_DIR = process.env.DATA_DIR || './data';
const SOCKET_PATH = join(DATA_DIR, 'docker.sock');
const STATUS_FILE_PATH = join(DATA_DIR, 'docker-status.json');

// Shared secret for socket authentication (required)
const WORKER_SHARED_SECRET = process.env.WORKER_SHARED_SECRET;

// Client identifier for authentication
const CLIENT_ID = 'webserver';

// Auth timeout (5 seconds)
const AUTH_TIMEOUT_MS = 5000;

// Command timeout (30 seconds - Docker operations can be slow)
const COMMAND_TIMEOUT_MS = 30000;

// Health check threshold (5 seconds)
const HEALTH_THRESHOLD_MS = 5000;

// =============================================================================
// Status File Reading
// =============================================================================

/**
 * Read the Docker status file.
 * Returns null if file doesn't exist or is invalid.
 */
async function readStatusFile(): Promise<DockerStatusFile | null> {
  try {
    const file = Bun.file(STATUS_FILE_PATH);
    if (!(await file.exists())) {
      return null;
    }

    const content = await file.text();
    const parsed = JSON.parse(content);
    const result = dockerStatusFileSchema.safeParse(parsed);
    return result.success ? result.data : null;
  } catch {
    return null;
  }
}

/**
 * Get container status for a single bot.
 */
export async function getContainerStatus(botId: string): Promise<BotStatus> {
  const statusFile = await readStatusFile();

  if (!statusFile) {
    // Worker not running or no status file yet
    return {
      id: botId,
      containerId: null,
      state: 'error',
      error: 'Docker worker not available',
    };
  }

  const containerStatus = statusFile.containers[botId];
  if (!containerStatus) {
    return {
      id: botId,
      containerId: null,
      state: 'stopped',
    };
  }

  return {
    id: botId,
    containerId: containerStatus.containerId,
    state: containerStatus.state,
  };
}

/**
 * Check if Docker connection is healthy.
 * Returns true if worker is running and status file is recent.
 */
export async function checkDockerConnection(): Promise<boolean> {
  const statusFile = await readStatusFile();
  if (!statusFile) return false;

  const age = Date.now() - statusFile.lastUpdated;
  return age < HEALTH_THRESHOLD_MS;
}

/**
 * Check if bot image exists.
 */
export async function checkBotImage(): Promise<boolean> {
  const statusFile = await readStatusFile();
  return statusFile?.botImageExists ?? false;
}

// =============================================================================
// Socket Connection
// =============================================================================

interface PendingCommand {
  resolve: (response: CommandResponse) => void;
  reject: (error: Error) => void;
  timeout: ReturnType<typeof setTimeout>;
}

// Persistent connection to worker
let workerSocket: Socket<SocketData> | null = null;
let connectionPromise: Promise<Socket<SocketData>> | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;

// Pending command responses
const pendingCommands = new Map<string, PendingCommand>();

// Log subscription callbacks
const logCallbacks = new Map<string, (line: string) => void>();

// Auth response callback (set during connection handshake)
let pendingAuthResolve: ((success: boolean, error?: string) => void) | null = null;

interface SocketData {
  buffer: string;
  authenticated: boolean;
}

/**
 * Get or create connection to the worker.
 * Handles authentication handshake on new connections.
 */
async function getConnection(): Promise<Socket<SocketData>> {
  if (workerSocket && workerSocket.data.authenticated) {
    return workerSocket;
  }

  if (connectionPromise) {
    return connectionPromise;
  }

  // Validate shared secret is configured
  if (!WORKER_SHARED_SECRET) {
    throw new Error('WORKER_SHARED_SECRET environment variable is required');
  }

  connectionPromise = new Promise<Socket<SocketData>>((resolve, reject) => {
    let authTimeout: ReturnType<typeof setTimeout> | null = null;

    Bun.connect<SocketData>({
      unix: SOCKET_PATH,
      socket: {
        open(socket) {
          socket.data = { buffer: '', authenticated: false };
          workerSocket = socket;

          // Send authentication request immediately
          const token = makeToken(CLIENT_ID, WORKER_SHARED_SECRET!);
          const authRequest: AuthRequest = {
            type: 'auth',
            token,
            clientId: CLIENT_ID,
          };

          // Set up auth timeout
          authTimeout = setTimeout(() => {
            console.error('[DockerClient] Authentication timed out');
            socket.end();
            workerSocket = null;
            connectionPromise = null;
            reject(new Error('Authentication timed out'));
          }, AUTH_TIMEOUT_MS);

          // Set up auth response handler
          pendingAuthResolve = (success: boolean, error?: string) => {
            if (authTimeout) {
              clearTimeout(authTimeout);
              authTimeout = null;
            }
            pendingAuthResolve = null;

            if (success) {
              socket.data.authenticated = true;
              connectionPromise = null;
              console.log('[DockerClient] Authenticated with worker');
              resolve(socket);
            } else {
              socket.end();
              workerSocket = null;
              connectionPromise = null;
              reject(new Error(error || 'Authentication failed'));
            }
          };

          try {
            socket.write(serializeMessage(authRequest));
          } catch (err) {
            if (authTimeout) {
              clearTimeout(authTimeout);
            }
            pendingAuthResolve = null;
            reject(err instanceof Error ? err : new Error('Failed to send auth'));
          }
        },

        data(socket, data) {
          socket.data.buffer += data.toString();

          // Process complete JSON lines
          let newlineIndex: number;
          while ((newlineIndex = socket.data.buffer.indexOf('\n')) !== -1) {
            const line = socket.data.buffer.slice(0, newlineIndex);
            socket.data.buffer = socket.data.buffer.slice(newlineIndex + 1);

            if (line.trim()) {
              handleWorkerMessage(line);
            }
          }
        },

        close() {
          console.log('[DockerClient] Connection closed');
          workerSocket = null;
          connectionPromise = null;

          // Reject pending auth if any
          if (pendingAuthResolve) {
            pendingAuthResolve(false, 'Connection closed');
            pendingAuthResolve = null;
          }

          // Reject all pending commands
          for (const [id, pending] of pendingCommands) {
            clearTimeout(pending.timeout);
            pending.reject(new Error('Connection closed'));
            pendingCommands.delete(id);
          }

          // Schedule reconnect
          scheduleReconnect();
        },

        error(socket, error) {
          console.error('[DockerClient] Connection error:', error);
          workerSocket = null;
          connectionPromise = null;

          // Reject pending auth if any
          if (pendingAuthResolve) {
            pendingAuthResolve(false, 'Connection error');
            pendingAuthResolve = null;
          }

          reject(error);
        },
      },
    }).catch(reject);
  });

  return connectionPromise;
}

function scheduleReconnect(): void {
  if (reconnectTimer) return;

  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    // Only reconnect if there are active subscriptions
    if (logCallbacks.size > 0) {
      getConnection().catch(() => {
        // Will retry on next operation
      });
    }
  }, 1000);
}

function handleWorkerMessage(line: string): void {
  const message = parseWorkerMessage(line);
  if (!message) {
    console.warn('[DockerClient] Invalid message from worker:', line.slice(0, 100));
    return;
  }

  switch (message.type) {
    case 'auth_response': {
      if (pendingAuthResolve) {
        pendingAuthResolve(message.success, message.error);
      }
      break;
    }

    case 'response': {
      const pending = pendingCommands.get(message.id);
      if (pending) {
        clearTimeout(pending.timeout);
        pendingCommands.delete(message.id);
        pending.resolve(message);
      }
      break;
    }

    case 'log': {
      const callback = logCallbacks.get(message.botId);
      if (callback) {
        callback(message.line);
      }
      break;
    }
  }
}

// =============================================================================
// Command Execution
// =============================================================================

type DockerCommand = 'start' | 'stop' | 'restart' | 'remove';

/**
 * Send a command to the Docker worker and wait for response.
 */
async function sendCommand(command: DockerCommand, botId: string): Promise<void> {
  const socket = await getConnection();
  const id = randomUUIDv7();

  const request: CommandRequest = {
    type: 'command',
    id,
    command,
    botId,
  };

  return new Promise<void>((resolve, reject) => {
    const timeout = setTimeout(() => {
      pendingCommands.delete(id);
      reject(new Error(`Command ${command} timed out`));
    }, COMMAND_TIMEOUT_MS);

    pendingCommands.set(id, {
      resolve: (response: CommandResponse) => {
        if (response.success) {
          resolve();
        } else {
          reject(new Error(response.error || 'Command failed'));
        }
      },
      reject,
      timeout,
    });

    try {
      socket.write(serializeMessage(request));
    } catch (err) {
      clearTimeout(timeout);
      pendingCommands.delete(id);
      reject(err instanceof Error ? err : new Error('Failed to send command'));
    }
  });
}

/**
 * Start a bot container.
 */
export async function startBot(botId: string): Promise<void> {
  await sendCommand('start', botId);
}

/**
 * Stop a bot container.
 */
export async function stopBot(botId: string): Promise<void> {
  await sendCommand('stop', botId);
}

/**
 * Restart a bot container.
 */
export async function restartBot(botId: string): Promise<void> {
  await sendCommand('restart', botId);
}

/**
 * Remove a bot container.
 */
export async function removeContainer(botId: string): Promise<void> {
  await sendCommand('remove', botId);
}

// =============================================================================
// Log Streaming
// =============================================================================

/**
 * Get container logs as a ReadableStream.
 * Compatible with the existing API contract.
 */
export async function getContainerLogs(botId: string): Promise<ReadableStream<string>> {
  const socket = await getConnection();

  return new ReadableStream<string>({
    start(controller) {
      // Subscribe to logs
      const subscribeRequest: SubscribeRequest = {
        type: 'subscribe',
        botId,
      };

      // Set up callback for log lines
      logCallbacks.set(botId, (line: string) => {
        try {
          controller.enqueue(line);
        } catch {
          // Stream may be closed
        }
      });

      try {
        socket.write(serializeMessage(subscribeRequest));
      } catch (err) {
        controller.error(err instanceof Error ? err : new Error('Failed to subscribe'));
      }
    },

    cancel() {
      // Unsubscribe from logs
      logCallbacks.delete(botId);

      if (workerSocket) {
        const unsubscribeRequest: UnsubscribeRequest = {
          type: 'unsubscribe',
          botId,
        };
        try {
          workerSocket.write(serializeMessage(unsubscribeRequest));
        } catch {
          // Socket may be closed
        }
      }
    },
  });
}

// =============================================================================
// Cleanup
// =============================================================================

/**
 * Close the worker connection.
 */
export function closeConnection(): void {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }

  if (workerSocket) {
    workerSocket.end();
    workerSocket = null;
  }

  // Clear all pending commands
  for (const [id, pending] of pendingCommands) {
    clearTimeout(pending.timeout);
    pending.reject(new Error('Connection closed'));
  }
  pendingCommands.clear();

  // Clear log callbacks
  logCallbacks.clear();
}
