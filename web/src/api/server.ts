import { randomUUIDv7 } from 'bun';
import type { ServerWebSocket, Socket } from 'bun';
import {
  getBots,
  getBot,
  createBot,
  updateBot,
  deleteBot,
  getNextVncPort,
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
import type { ApiResponse, BotConfig, BotWithStatus } from '../shared/types';

const PORT = parseInt(process.env.PORT || '3000');

// Store VNC TCP connections per WebSocket
type VncWebSocketData = {
  botId: string;
  vncPort: number;
  tcpSocket: Socket<{ ws: ServerWebSocket<VncWebSocketData> }> | null;
};

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
        botId,
        vncPort: bot.vncPort,
        tcpSocket: null,
      } as VncWebSocketData,
    });

    if (upgraded) {
      return undefined; // Bun handles the 101 response
    }
    return error('WebSocket upgrade failed', 500);
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
      const nextVncPort = await getNextVncPort();

      const newBot: BotConfig = {
        id: randomUUIDv7(),
        name: body.name,
        username: body.username,
        password: body.password,
        totpSecret: body.totpSecret || undefined,
        characterName: body.characterName || undefined,
        proxy: body.proxy || null,
        ironman: body.ironman || { enabled: false, type: null, hcimSafetyLevel: null },
        resources: body.resources || { cpuLimit: '1.0', memoryLimit: '2G' },
        vncPort: nextVncPort, // Auto-assigned, not configurable
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
        // Prevent updating vncPort (auto-assigned)
        const { vncPort: _, ...updates } = body;
        const updated = await updateBot(botId, updates);
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

Bun.serve({
  port: PORT,
  fetch: handleRequest,
  websocket: {
    // Called when WebSocket connection opens
    async open(ws: ServerWebSocket<VncWebSocketData>) {
      const { vncPort } = ws.data;
      console.log(`VNC WebSocket opened for port ${vncPort}`);

      try {
        // Connect to VNC server via TCP
        const tcpSocket = await Bun.connect({
          hostname: 'localhost',
          port: vncPort,
          socket: {
            data(socket, data) {
              // Forward VNC server data to WebSocket client
              const wsConn = socket.data.ws;
              if (wsConn.readyState === 1) { // OPEN
                wsConn.send(data);
              }
            },
            open(socket) {
              console.log(`TCP connection to VNC port ${vncPort} established`);
            },
            close(socket) {
              console.log(`TCP connection to VNC port ${vncPort} closed`);
              const wsConn = socket.data.ws;
              if (wsConn.readyState === 1) {
                wsConn.close(1000, 'VNC server disconnected');
              }
            },
            error(socket, err) {
              console.error(`TCP error for VNC port ${vncPort}:`, err);
              const wsConn = socket.data.ws;
              if (wsConn.readyState === 1) {
                wsConn.close(1011, 'VNC connection error');
              }
            },
          },
          data: { ws },
        });

        ws.data.tcpSocket = tcpSocket;
      } catch (err) {
        console.error(`Failed to connect to VNC port ${vncPort}:`, err);
        ws.close(1011, 'Failed to connect to VNC server');
      }
    },

    // Called when WebSocket receives a message from client
    message(ws: ServerWebSocket<VncWebSocketData>, message) {
      // Forward client data to VNC server
      const { tcpSocket } = ws.data;
      if (tcpSocket) {
        if (typeof message === 'string') {
          tcpSocket.write(Buffer.from(message));
        } else {
          tcpSocket.write(message);
        }
      }
    },

    // Called when WebSocket connection closes
    close(ws: ServerWebSocket<VncWebSocketData>, code, reason) {
      console.log(`VNC WebSocket closed: ${code} ${reason}`);
      const { tcpSocket } = ws.data;
      if (tcpSocket) {
        tcpSocket.end();
      }
    },

    // Called on WebSocket error
    error(ws: ServerWebSocket<VncWebSocketData>, error) {
      console.error('VNC WebSocket error:', error);
      const { tcpSocket } = ws.data;
      if (tcpSocket) {
        tcpSocket.end();
      }
    },
  },
});

console.log(`Server running on http://localhost:${PORT}`);
