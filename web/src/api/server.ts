import { v4 as uuidv4 } from 'uuid';
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

async function handleRequest(req: Request): Promise<Response> {
  const url = new URL(req.url);
  const path = url.pathname;
  const method = req.method;

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
        id: uuidv4(),
        name: body.name,
        username: body.username,
        password: body.password,
        proxy: body.proxy || null,
        ironman: body.ironman || { enabled: false, type: null, hcimSafetyLevel: null },
        resources: body.resources || { cpuLimit: '1.0', memoryLimit: '2G' },
        vncPort: body.vncPort || nextVncPort,
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
        const updated = await updateBot(botId, body);
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
          await restartBot(botId);
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
});

console.log(`Server running on http://localhost:${PORT}`);

