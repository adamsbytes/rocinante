import { randomUUIDv7 } from 'bun';
import type { ServerWebSocket, Socket } from 'bun';
import {
  getBots,
  getBot,
  createBot,
  updateBot,
  deleteBot,
} from './config';
import {
  getContainerStatus,
  startBot,
  stopBot,
  restartBot,
  removeContainer,
  getContainerLogs,
  checkDockerConnection,
  checkBotImage,
  buildBotImage,
} from './docker';
import {
  handleStatusWebSocket,
  readBotStatus,
  sendBotCommand,
  cleanup as cleanupStatus,
  watchBotStatus,
  getVncSocketPath,
} from './status';
import {
  getTrainingMethods,
  getTrainingMethodsBySkill,
  getLocations,
  getLocationsByType,
} from './data';
import { listScreenshots, getScreenshotFile } from './screenshots';
import type { ApiResponse, BotConfig, BotWithStatus, BotRuntimeStatus, LocationInfo, EnvironmentConfig } from '../shared/types';
import { botFormSchema, type BotFormData } from '../shared/botSchema';

const PORT = parseInt(process.env.PORT || '3000');

// WebSocket connection types
type VncWebSocketData = {
  type: 'vnc';
  botId: string;
  tcpSocket: Socket<{ ws: ServerWebSocket<VncWebSocketData> }> | null;
};

type StatusWebSocketData = {
  type: 'status';
  botId: string;
  cleanup: (() => void) | null;
  pingInterval: ReturnType<typeof setInterval> | null;
};

type WebSocketData = VncWebSocketData | StatusWebSocketData;

function json<T>(data: T, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function success<T>(data: T): Response {
  return json<ApiResponse<T>>({ success: true, data });
}

function error(message: string, status = 400): Response {
  return json<ApiResponse<never>>({ success: false, error: message }, status);
}

function formatValidationErrors(issues: Array<{ message: string }>): string {
  return issues.map((issue) => issue.message).join('; ');
}

// =============================================================================
// Environment Fingerprint Generation
// Deterministically generates anti-fingerprint settings from bot ID
// =============================================================================

const COMMON_DPIS = [96, 110, 120, 144];

// Optional fonts pool - installed in Dockerfile but disabled by default
// Entrypoint enables selected fonts per bot
const OPTIONAL_FONTS = [
  'firacode', 'roboto', 'ubuntu', 'inconsolata',
  'lato', 'open-sans', 'cascadia-code', 'hack',
  'jetbrains-mono', 'droid', 'wine', 'cantarell',
  'dejavu-extra', 'noto-mono', 'noto-ui-core',
  'liberation-sans-narrow', 'font-awesome', 'opensymbol',
  'liberation2', 'croscore', 'crosextra-carlito', 'crosextra-caladea',
  'noto-color-emoji', 'freefont-otf', 'urw-base35', 'texgyre',
];

const GC_ALGORITHMS: Array<'G1GC' | 'ParallelGC' | 'ZGC'> = ['G1GC', 'ParallelGC', 'ZGC'];

/** Simple seeded random number generator (matches Java's behavior) */
function seededRandom(seed: number): () => number {
  return () => {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff;
    return seed / 0x7fffffff;
  };
}

/** Generate a deterministic seed from a string (matches Java hashCode) */
function generateSeed(str: string): number {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash) + str.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash);
}

/** Generate environment fingerprint data for a bot */
function generateEnvironmentFingerprint(botId: string): EnvironmentConfig {
  const seed = generateSeed(botId);
  const random = seededRandom(seed);

  // Machine ID: 32 hex characters
  let machineId = '';
  for (let i = 0; i < 32; i++) {
    machineId += Math.floor(random() * 16).toString(16);
  }

  // Display number: 0-9 (X11 display)
  const displayNumber = Math.floor(random() * 10);

  // Screen resolution: sample from common sizes to avoid fingerprinting
  // TODO: Add weighted random selection of resolutions.
  // We are only running one bot right now, so this isn't important
  const screenResolution = '1280x720';

  // Screen depth: 24 or 32 (70% use 24-bit)
  const screenDepth = random() < 0.7 ? 24 : 32;

  // Display DPI: Random from common values
  const displayDpi = COMMON_DPIS[Math.floor(random() * COMMON_DPIS.length)];

  // Timezone: Default, user sets per account to match proxy
  const timezone = 'America/New_York';

  // Additional fonts: 10-20 from expanded pool
  const numFonts = 10 + Math.floor(random() * 11);
  const shuffledFonts = [...OPTIONAL_FONTS].sort(() => random() - 0.5);
  const additionalFonts = shuffledFonts.slice(0, numFonts);

  // GC algorithm: Random from available options
  const gcAlgorithm = GC_ALGORITHMS[Math.floor(random() * GC_ALGORITHMS.length)];

  return {
    machineId,
    displayNumber,
    screenResolution,
    screenDepth,
    displayDpi,
    timezone,
    additionalFonts,
    gcAlgorithm,
  };
}

async function handleRequest(req: Request, server: ReturnType<typeof Bun.serve>): Promise<Response | undefined> {
  const url = new URL(req.url);
  const path = url.pathname;
  const method = req.method;

  // VNC WebSocket upgrade
  const vncMatch = path.match(/^\/api\/vnc\/([^/]+)$/);
  if (vncMatch && req.headers.get('upgrade') === 'websocket') {
    const botId = vncMatch[1];
    const bot = await getBot(botId);
    
    if (!bot) {
      return error('Bot not found', 404);
    }

    // Check if bot is running
    const status = await getContainerStatus(botId);
    if (status.state !== 'running') {
      return error('Bot is not running', 400);
    }

    // Upgrade to WebSocket
    const upgraded = server.upgrade(req, {
      data: {
        type: 'vnc',
        botId,
        tcpSocket: null,
      } as VncWebSocketData,
    });

    if (upgraded) {
      return undefined; // Bun handles the 101 response
    }
    return error('WebSocket upgrade failed', 500);
  }

  // Status WebSocket upgrade
  const statusMatch = path.match(/^\/api\/status\/([^/]+)$/);
  if (statusMatch && req.headers.get('upgrade') === 'websocket') {
    const botId = statusMatch[1];
    const bot = await getBot(botId);
    
    if (!bot) {
      return error('Bot not found', 404);
    }

    // Upgrade to WebSocket
    const upgraded = server.upgrade(req, {
      data: {
        type: 'status',
        botId,
        cleanup: null,
        pingInterval: null,
      } as StatusWebSocketData,
    });

    if (upgraded) {
      return undefined; // Bun handles the 101 response
    }
    return error('WebSocket upgrade failed', 500);
  }

  // REST API for bot runtime status
  const statusApiMatch = path.match(/^\/api\/bots\/([^/]+)\/status$/);
  if (statusApiMatch && method === 'GET') {
    const botId = statusApiMatch[1];
    const bot = await getBot(botId);
    
    if (!bot) {
      return error('Bot not found', 404);
    }

    const runtimeStatus = await readBotStatus(botId);
    return success(runtimeStatus);
  }

  // Screenshots - list
  const screenshotsMatch = path.match(/^\/api\/bots\/([^/]+)\/screenshots$/);
  if (screenshotsMatch && method === 'GET') {
    const botId = screenshotsMatch[1];
    const bot = await getBot(botId);

    if (!bot) {
      return error('Bot not found', 404);
    }

    const category = url.searchParams.get('category');
    const character = url.searchParams.get('character');
    const screenshots = await listScreenshots(botId, { category, character });
    return success(screenshots);
  }

  // Screenshots - view single file
  const screenshotViewMatch = path.match(/^\/api\/bots\/([^/]+)\/screenshots\/view$/);
  if (screenshotViewMatch && method === 'GET') {
    const botId = screenshotViewMatch[1];
    const bot = await getBot(botId);

    if (!bot) {
      return error('Bot not found', 404);
    }

    const relativePath = url.searchParams.get('path');
    if (!relativePath) {
      return error('Missing path', 400);
    }

    try {
      const fileResult = await getScreenshotFile(botId, relativePath);
      if (!fileResult) {
        return error('Screenshot not found', 404);
      }
      const arrayBuffer = await fileResult.file.arrayBuffer();
      return new Response(arrayBuffer, {
        status: 200,
        headers: {
          'Content-Type': fileResult.contentType,
          'Cache-Control': 'public, max-age=60',
        },
      });
    } catch (err) {
      console.error('Failed to load screenshot', err);
      return error('Failed to load screenshot', 500);
    }
  }

  // REST API to send commands to bot
  const commandMatch = path.match(/^\/api\/bots\/([^/]+)\/command$/);
  if (commandMatch && method === 'POST') {
    const botId = commandMatch[1];
    const bot = await getBot(botId);
    
    if (!bot) {
      return error('Bot not found', 404);
    }

    try {
      const body = await req.json();
      await sendBotCommand(botId, body);
      return success({ sent: true });
    } catch (err) {
      return error(err instanceof Error ? err.message : 'Failed to send command');
    }
  }

  // Health check
  if (path === '/api/health' && method === 'GET') {
    const dockerOk = await checkDockerConnection();
    const imageOk = await checkBotImage();
    return success({
      status: 'ok',
      docker: dockerOk,
      botImage: imageOk,
    });
  }

  // List all bots
  if (path === '/api/bots' && method === 'GET') {
    const bots = await getBots();
    const botsWithStatus: BotWithStatus[] = await Promise.all(
      bots.map(async (bot) => ({
        ...bot,
        status: await getContainerStatus(bot.id),
      }))
    );
    return success(botsWithStatus);
  }

  // Create new bot
  if (path === '/api/bots' && method === 'POST') {
    try {
      const body = await req.json();
      const parsed = botFormSchema.safeParse(body);

      if (!parsed.success) {
        return error(`Invalid bot configuration: ${formatValidationErrors(parsed.error.issues)}`, 400);
      }

      const botData: BotFormData = parsed.data;
      const botId = randomUUIDv7();
      
      // Generate environment fingerprint data (deterministic from bot ID)
      const environment = generateEnvironmentFingerprint(botId);

      const newBot: BotConfig = {
        id: botId,
        ...botData,
        // Environment fingerprint (auto-generated, deterministic per bot)
        environment,
      };

      await createBot(newBot);
      return success(newBot);
    } catch (err) {
      return error(err instanceof Error ? err.message : 'Failed to create bot');
    }
  }

  // Single bot routes
  const botMatch = path.match(/^\/api\/bots\/([^/]+)$/);
  if (botMatch) {
    const botId = botMatch[1];

    // Get single bot
    if (method === 'GET') {
      const bot = await getBot(botId);
      if (!bot) {
        return error('Bot not found', 404);
      }
      const botWithStatus: BotWithStatus = {
        ...bot,
        status: await getContainerStatus(bot.id),
      };
      return success(botWithStatus);
    }

    // Update bot
    if (method === 'PUT') {
      try {
        const body = await req.json();
        const parsed = botFormSchema.safeParse(body);

        if (!parsed.success) {
          return error(`Invalid bot configuration: ${formatValidationErrors(parsed.error.issues)}`, 400);
        }

        const updated = await updateBot(botId, parsed.data);
        if (!updated) {
          return error('Bot not found', 404);
        }
        return success(updated);
      } catch (err) {
        return error(err instanceof Error ? err.message : 'Failed to update bot');
      }
    }

    // Delete bot
    if (method === 'DELETE') {
      try {
        // Stop and remove container if running
        await removeContainer(botId);
        const deleted = await deleteBot(botId);
        if (!deleted) {
          return error('Bot not found', 404);
        }
        return success({ deleted: true });
      } catch (err) {
        return error(err instanceof Error ? err.message : 'Failed to delete bot');
      }
    }
  }

  // Bot actions
  const actionMatch = path.match(/^\/api\/bots\/([^/]+)\/(start|stop|restart|logs)$/);
  if (actionMatch && method === 'POST') {
    const botId = actionMatch[1];
    const action = actionMatch[2];

    const bot = await getBot(botId);
    if (!bot) {
      return error('Bot not found', 404);
    }

    try {
      switch (action) {
        case 'start':
          await startBot(bot);
          return success({ started: true });

        case 'stop':
          await stopBot(botId);
          return success({ stopped: true });

        case 'restart':
          await restartBot(bot);
          return success({ restarted: true });

        default:
          return error('Unknown action', 400);
      }
    } catch (err) {
      return error(err instanceof Error ? err.message : `Failed to ${action} bot`);
    }
  }

  // Bot logs (GET for SSE)
  const logsMatch = path.match(/^\/api\/bots\/([^/]+)\/logs$/);
  if (logsMatch && method === 'GET') {
    const botId = logsMatch[1];

    const bot = await getBot(botId);
    if (!bot) {
      return error('Bot not found', 404);
    }

    try {
      const logStream = await getContainerLogs(botId);
      return new Response(logStream, {
        headers: {
          'Content-Type': 'text/event-stream',
          'Cache-Control': 'no-cache',
          'Connection': 'keep-alive',
        },
      });
    } catch (err) {
      return error(err instanceof Error ? err.message : 'Failed to get logs');
    }
  }

  // ============================================================================
  // Data API endpoints (for Manual Task UI)
  // ============================================================================

  // Training methods
  if (path === '/api/data/training-methods' && method === 'GET') {
    try {
      const grouped = url.searchParams.get('grouped') === 'true';
      if (grouped) {
        const data = await getTrainingMethodsBySkill();
        return success(data);
      } else {
        const data = await getTrainingMethods();
        return success(data);
      }
    } catch (err) {
      return error(err instanceof Error ? err.message : 'Failed to load training methods');
    }
  }

  // Navigation locations
  if (path === '/api/data/locations' && method === 'GET') {
    try {
      const type = url.searchParams.get('type') as LocationInfo['type'] | null;
      if (type) {
        const data = await getLocationsByType(type);
        return success(data);
      } else {
        const data = await getLocations();
        return success(data);
      }
    } catch (err) {
      return error(err instanceof Error ? err.message : 'Failed to load locations');
    }
  }

  // Static file serving for production
  if (method === 'GET' && !path.startsWith('/api')) {
    // In production, serve static files from dist/client
    // For dev, Vite handles this via proxy
    try {
      const filePath = path === '/' ? '/index.html' : path;
      const file = Bun.file(`./dist/client${filePath}`);
      if (await file.exists()) {
        return new Response(file);
      }
      // SPA fallback
      const indexFile = Bun.file('./dist/client/index.html');
      if (await indexFile.exists()) {
        return new Response(indexFile);
      }
    } catch {
      // Fall through to 404
    }
  }

  return error('Not found', 404);
}

// Check prerequisites on startup
async function checkPrerequisites(): Promise<void> {
  console.log('Checking Docker connection...');
  const dockerOk = await checkDockerConnection();
  if (!dockerOk) {
    console.error('WARNING: Cannot connect to Docker socket. Make sure /var/run/docker.sock is mounted.');
    return;
  }
  console.log('Docker connection OK');

  console.log('Checking bot image...');
  const imageOk = await checkBotImage();
  if (!imageOk) {
    const botPath = process.env.BOT_PATH;
    if (botPath) {
      console.log(`Bot image not found. Building from ${botPath}...`);
      try {
        await buildBotImage();
      } catch (err) {
        console.error('Failed to build bot image:', err);
        console.error('You can manually build it with: docker build -t rocinante-bot:latest ./bot');
      }
    } else {
      console.warn(`WARNING: Bot image '${process.env.BOT_IMAGE || 'rocinante-bot:latest'}' not found.`);
      console.warn('Set BOT_PATH env var to auto-build, or manually run: docker build -t rocinante-bot:latest ./bot');
    }
  } else {
    console.log('Bot image OK');
  }
}

// Start server
console.log('Starting Rocinante Management Server...');
await checkPrerequisites();

const bunServer = Bun.serve({
  port: PORT,
  fetch: handleRequest,
  websocket: {
    // Idle timeout in seconds - set to 120s, ping every 30s keeps connection alive
    idleTimeout: 120,
    
    // Called when WebSocket connection opens
    async open(ws: ServerWebSocket<WebSocketData>) {
      if (ws.data.type === 'vnc') {
        const vncData = ws.data as VncWebSocketData;
        const { botId } = vncData;
        
        // Get VNC socket path from status directory
        const vncSocketPath = getVncSocketPath(botId);

        try {
          // Connect to VNC server via Unix socket (not TCP)
          const tcpSocket = await Bun.connect({
            unix: vncSocketPath,
            socket: {
              data(socket, data) {
                // Forward VNC server data to WebSocket client
                const wsConn = socket.data.ws;
                if (wsConn.readyState === 1) { // OPEN
                  wsConn.send(data);
                }
              },
              open(socket) {
                console.log(`Unix socket connection to VNC established: ${vncSocketPath}`);
              },
              close(socket) {
                console.log(`Unix socket connection to VNC closed: ${vncSocketPath}`);
                const wsConn = socket.data.ws;
                if (wsConn.readyState === 1) {
                  wsConn.close(1000, 'VNC server disconnected');
                }
              },
              error(socket, err) {
                console.error(`Unix socket error for VNC ${vncSocketPath}:`, err);
                const wsConn = socket.data.ws;
                if (wsConn.readyState === 1) {
                  wsConn.close(1011, 'VNC connection error');
                }
              },
            },
            data: { ws: ws as ServerWebSocket<VncWebSocketData> },
          });

          vncData.tcpSocket = tcpSocket;
        } catch (err: any) {
          // ENOENT = socket doesn't exist yet (container starting)
          // ECONNREFUSED = socket exists but nothing listening
          // EACCES = permission denied
          if (err?.code === 'ENOENT' || err?.code === 'ECONNREFUSED') {
            // Container still starting - close gracefully, client will retry
            ws.close(1000, 'VNC server not ready');
          } else {
            console.error(`Failed to connect to VNC socket ${vncSocketPath}:`, err);
          ws.close(1011, 'Failed to connect to VNC server');
          }
        }
      } else if (ws.data.type === 'status') {
        const statusData = ws.data as StatusWebSocketData;
        const { botId } = statusData;
        console.log(`Status WebSocket opened for bot ${botId}`);

        // Start watching status file and forward updates to WebSocket
        statusData.cleanup = watchBotStatus(botId, (status) => {
          if (ws.readyState === 1) { // OPEN
            try {
              ws.send(JSON.stringify(status));
            } catch (error) {
              console.error(`Error sending status to WebSocket:`, error);
            }
          }
        });

        // Start ping interval to keep connection alive (every 30 seconds)
        statusData.pingInterval = setInterval(() => {
          if (ws.readyState === 1) { // OPEN
            ws.ping();
          }
        }, 30000);

        // Send initial status if available
        readBotStatus(botId).then((initialStatus) => {
        if (initialStatus && ws.readyState === 1) {
          ws.send(JSON.stringify(initialStatus));
        }
        }).catch((err) => console.error(`Error reading initial status:`, err));
      }
    },

    // Called when WebSocket receives a message from client
    message(ws: ServerWebSocket<WebSocketData>, message) {
      if (ws.data.type === 'vnc') {
        // Forward client data to VNC server
        const vncData = ws.data as VncWebSocketData;
        const { tcpSocket } = vncData;
        if (tcpSocket) {
          if (typeof message === 'string') {
            tcpSocket.write(Buffer.from(message));
          } else {
            tcpSocket.write(message);
          }
        }
      } else if (ws.data.type === 'status') {
        // Handle commands from client
        const statusData = ws.data as StatusWebSocketData;
        try {
          const data = typeof message === 'string' ? message : message.toString();
          const parsed = JSON.parse(data);
          
          if (parsed.type === 'command' && parsed.command) {
            sendBotCommand(statusData.botId, parsed.command)
              .catch((err) => console.error(`Error sending command:`, err));
          }
        } catch (error) {
          console.error(`Error handling status WebSocket message:`, error);
        }
      }
    },

    // Called when WebSocket connection closes
    close(ws: ServerWebSocket<WebSocketData>, code, reason) {
      if (ws.data.type === 'vnc') {
        console.log(`VNC WebSocket closed: ${code} ${reason}`);
        const vncData = ws.data as VncWebSocketData;
        if (vncData.tcpSocket) {
          vncData.tcpSocket.end();
        }
      } else if (ws.data.type === 'status') {
        const statusData = ws.data as StatusWebSocketData;
        console.log(`Status WebSocket closed for bot ${statusData.botId}: ${code} ${reason}`);
        if (statusData.pingInterval) {
          clearInterval(statusData.pingInterval);
        }
        if (statusData.cleanup) {
          statusData.cleanup();
        }
      }
    },
  },
});

// Handle graceful shutdown
process.on('SIGINT', () => {
  console.log('Shutting down...');
  cleanupStatus();
  process.exit(0);
});

process.on('SIGTERM', () => {
  console.log('Shutting down...');
  cleanupStatus();
  process.exit(0);
});

console.log(`Server running on http://localhost:${PORT}`);
