import { randomUUIDv7 } from 'bun';
import type { ServerWebSocket, Socket } from 'bun';
import { join, normalize, resolve, sep } from 'path';
import { z } from 'zod';
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
import { auth, getSession, type Session } from './auth';
import type { ApiResponse, BotConfig, BotWithStatus, BotConfigDTO, BotWithStatusDTO, ProxyConfigDTO, LocationInfo, EnvironmentConfig, BotStatus } from '../shared/types';
import { botCreateSchema, botUpdateSchema, type BotFormData, type BotUpdateData } from '../shared/botSchema';
import { botCommandPayloadSchema } from '../shared/commandSchema';

// =============================================================================
// Bot ID Validation (UUIDv7)
// =============================================================================
const botIdSchema = z.uuidv7({ message: 'Invalid bot ID: must be a valid UUIDv7' });

type BotIdResult = 
  | { ok: true; botId: string; bot: BotConfig }
  | { ok: false; response: Response };

/**
 * Extract and validate bot ID from path, then fetch the bot.
 * Returns the validated botId and bot config, or an error response.
 */
async function withBotId(path: string, pattern: RegExp): Promise<BotIdResult> {
  const match = path.match(pattern);
  if (!match) {
    return { ok: false, response: error('Not found', 404) };
  }
  
  const rawId = match[1];
  const validation = botIdSchema.safeParse(rawId);
  if (!validation.success) {
    return { ok: false, response: error('Invalid bot ID format', 400) };
  }
  
  const botId = validation.data;
  const bot = await getBot(botId);
  if (!bot) {
    return { ok: false, response: error('Bot not found', 404) };
  }
  
  return { ok: true, botId, bot };
}

/**
 * Fetch bot and check ownership against pre-validated session.
 * Session must already be validated before calling this.
 */
function withOwnedBot(session: NonNullable<Session>, path: string, pattern: RegExp): Promise<BotIdResult> {
  return withBotId(path, pattern).then(result => {
    if (!result.ok) return result;
    
    if (result.bot.ownerId !== session.user.id) {
      console.warn(`IDOR blocked: user ${session.user.id} tried to access bot ${result.botId} owned by ${result.bot.ownerId}`);
      return { ok: false, response: error('Forbidden', 403) };
    }
    
    return result;
  });
}

/**
 * Extract and validate bot ID from path (without fetching bot).
 * Use when you only need to validate the ID format.
 */
function extractBotId(path: string, pattern: RegExp): { ok: true; botId: string } | { ok: false; response: Response } {
  const match = path.match(pattern);
  if (!match) {
    return { ok: false, response: error('Not found', 404) };
  }
  
  const rawId = match[1];
  const validation = botIdSchema.safeParse(rawId);
  if (!validation.success) {
    return { ok: false, response: error('Invalid bot ID format', 400) };
  }
  
  return { ok: true, botId: validation.data };
}

// =============================================================================
// Path Security - Prevent directory traversal attacks
// =============================================================================

/**
 * Validates that a requested file path resolves within the allowed base directory.
 * Prevents path traversal attacks (../, absolute paths, etc.)
 * 
 * @param baseDir - Absolute path to the allowed directory
 * @param requestedPath - User-supplied path (from URL, query param, etc.)
 * @returns Validated absolute path within baseDir
 * @throws Error if path attempts to escape baseDir
 */
function validateSecurePath(baseDir: string, requestedPath: string): string {
  // Normalize the requested path and strip leading slashes/backslashes
  const normalized = normalize(requestedPath).replace(/^[/\\]+/, '');
  
  // Resolve both to absolute paths for comparison
  const resolvedBase = resolve(baseDir);
  const resolvedPath = resolve(join(resolvedBase, normalized));
  
  // Ensure the resolved path is within the base directory
  const baseWithSep = resolvedBase.endsWith(sep) ? resolvedBase : `${resolvedBase}${sep}`;
  
  if (resolvedPath !== resolvedBase && !resolvedPath.startsWith(baseWithSep)) {
    throw new Error('Path traversal attempt detected');
  }
  
  return resolvedPath;
}

// =============================================================================
// DTO Sanitization - Strip sensitive fields before sending to client
// =============================================================================

/**
 * Convert ProxyConfig to ProxyConfigDTO (strip password).
 */
function toProxyDTO(proxy: BotConfig['proxy']): ProxyConfigDTO | null {
  if (!proxy) return null;
  return {
    host: proxy.host,
    port: proxy.port,
    user: proxy.user,
    hasPassword: !!proxy.pass,
  };
}

/**
 * Strip sensitive fields from BotConfig before sending to client.
 * Never exposes: password, totpSecret, proxy.pass
 */
function toBotDTO(bot: BotConfig): BotConfigDTO {
  return {
    id: bot.id,
    ownerId: bot.ownerId,
    username: bot.username,
    characterName: bot.characterName,
    preferredWorld: bot.preferredWorld,
    lampSkill: bot.lampSkill,
    proxy: toProxyDTO(bot.proxy),
    ironman: bot.ironman,
    resources: bot.resources,
    environment: bot.environment,
    hasPassword: !!bot.password,
    hasTotpSecret: !!bot.totpSecret,
  };
}

/**
 * Convert BotConfig + BotStatus to BotWithStatusDTO.
 */
function toBotWithStatusDTO(bot: BotConfig, status: BotStatus): BotWithStatusDTO {
  return {
    ...toBotDTO(bot),
    status,
  };
}

const PORT = parseInt(process.env.PORT || '3000');

// WebSocket connection types
type VncWebSocketData = {
  type: 'vnc';
  botId: string;
  tcpSocket: Socket<{ ws: ServerWebSocket<VncWebSocketData> }> | null;
  /** Track if we've ever had a successful VNC connection on this WebSocket */
  hadConnection: boolean;
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

  // ==========================================================================
  // PUBLIC ROUTES (no auth required)
  // ==========================================================================
  
  // Authentication routes (handled by better-auth)
  if (path.startsWith('/api/auth')) {
    return auth.handler(req);
  }

  // Health check (public for monitoring)
  if (path === '/api/health' && method === 'GET') {
    const dockerOk = await checkDockerConnection();
    const imageOk = await checkBotImage();
    return success({
      status: 'ok',
      docker: dockerOk,
      botImage: imageOk,
    });
  }

  // ==========================================================================
  // DEFAULT DENY: All other /api/* routes require authentication
  // ==========================================================================
  let session: Session = null;
  
  if (path.startsWith('/api')) {
    session = await getSession(req);
    if (!session?.user?.id) {
      console.warn(`Auth failed: ${method} ${path} - no valid session`);
      return error('Unauthorized', 401);
    }
  }

  // From here on, session is guaranteed valid for all /api/* routes
  // TypeScript doesn't know this, so we use session! where needed

  // ==========================================================================
  // WEBSOCKET ROUTES (auth already validated above)
  // ==========================================================================

  // VNC WebSocket upgrade
  if (path.match(/^\/api\/vnc\/[^/]+$/) && req.headers.get('upgrade') === 'websocket') {
    const result = await withOwnedBot(session!, path, /^\/api\/vnc\/([^/]+)$/);
    if (!result.ok) return result.response;
    const { botId } = result;

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
        hadConnection: false,
      } as VncWebSocketData,
    });

    if (upgraded) {
      return undefined; // Bun handles the 101 response
    }
    return error('WebSocket upgrade failed', 500);
  }

  // Status WebSocket upgrade
  if (path.match(/^\/api\/status\/[^/]+$/) && req.headers.get('upgrade') === 'websocket') {
    const result = await withOwnedBot(session!, path, /^\/api\/status\/([^/]+)$/);
    if (!result.ok) return result.response;
    const { botId } = result;

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

  // ==========================================================================
  // BOT API ROUTES (auth already validated, need ownership checks)
  // ==========================================================================

  // REST API for bot runtime status
  if (path.match(/^\/api\/bots\/[^/]+\/status$/) && method === 'GET') {
    const result = await withOwnedBot(session!, path, /^\/api\/bots\/([^/]+)\/status$/);
    if (!result.ok) return result.response;
    
    const runtimeStatus = await readBotStatus(result.botId);
    return success(runtimeStatus);
  }

  // Screenshots - list
  if (path.match(/^\/api\/bots\/[^/]+\/screenshots$/) && method === 'GET') {
    const result = await withOwnedBot(session!, path, /^\/api\/bots\/([^/]+)\/screenshots$/);
    if (!result.ok) return result.response;

    const category = url.searchParams.get('category');
    const character = url.searchParams.get('character');
    // Pass bot's timezone so screenshot timestamps are parsed correctly
    const timezone = result.bot.environment?.timezone;
    const screenshots = await listScreenshots(result.botId, { category, character, timezone });
    return success(screenshots);
  }

  // Screenshots - view single file (uses Bun's optimized file streaming)
  if (path.match(/^\/api\/bots\/[^/]+\/screenshots\/view$/) && method === 'GET') {
    const result = await withOwnedBot(session!, path, /^\/api\/bots\/([^/]+)\/screenshots\/view$/);
    if (!result.ok) return result.response;

    const relativePath = url.searchParams.get('path');
    if (!relativePath) {
      return error('Missing path', 400);
    }

    try {
      const fileResult = await getScreenshotFile(result.botId, relativePath);
      if (!fileResult) {
        return error('Screenshot not found', 404);
      }
      // Use Bun's optimized file streaming - passes BunFile directly to Response
      return new Response(fileResult.file, {
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
  if (path.match(/^\/api\/bots\/[^/]+\/command$/) && method === 'POST') {
    const result = await withOwnedBot(session!, path, /^\/api\/bots\/([^/]+)\/command$/);
    if (!result.ok) return result.response;

    try {
      const body = await req.json();
      
      // Validate command payload with Zod
      const validation = botCommandPayloadSchema.safeParse(body);
      if (!validation.success) {
        const issues = validation.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`);
        return error(`Invalid command: ${issues.join(', ')}`, 400);
      }
      
      await sendBotCommand(result.botId, validation.data);
      return success({ sent: true });
    } catch (err) {
      return error(err instanceof Error ? err.message : 'Failed to send command');
    }
  }

  // List all bots (returns DTOs - no sensitive fields, filtered by owner)
  if (path === '/api/bots' && method === 'GET') {
    const allBots = await getBots();
    // Filter to only show bots owned by this user
    const userBots = allBots.filter(bot => bot.ownerId === session!.user.id);
    const botsWithStatus: BotWithStatusDTO[] = await Promise.all(
      userBots.map(async (bot) => toBotWithStatusDTO(bot, await getContainerStatus(bot.id)))
    );
    return success(botsWithStatus);
  }

  // Create new bot (returns DTO - no sensitive fields)
  if (path === '/api/bots' && method === 'POST') {
    try {
      const body = await req.json();
      const parsed = botCreateSchema.safeParse(body);

      if (!parsed.success) {
        return error(`Invalid bot configuration: ${formatValidationErrors(parsed.error.issues)}`, 400);
      }

      const botData: BotFormData = parsed.data;
      const botId = randomUUIDv7();
      
      // Generate environment fingerprint data (deterministic from bot ID)
      const environment = generateEnvironmentFingerprint(botId);

      const newBot: BotConfig = {
        id: botId,
        ownerId: session!.user.id,  // Set owner to authenticated user
        ...botData,
        // Environment fingerprint (auto-generated, deterministic per bot)
        environment,
      };

      await createBot(newBot);
      return success(toBotDTO(newBot));
    } catch (err) {
      return error(err instanceof Error ? err.message : 'Failed to create bot');
    }
  }

  // Single bot routes
  if (path.match(/^\/api\/bots\/[^/]+$/)) {
    // Validate bot ID first for all methods
    const idResult = extractBotId(path, /^\/api\/bots\/([^/]+)$/);
    if (!idResult.ok) return idResult.response;
    const botId = idResult.botId;

    // Get single bot (returns DTO - no sensitive fields)
    if (method === 'GET') {
      const bot = await getBot(botId);
      if (!bot) {
        return error('Bot not found', 404);
      }
      // Check ownership
      if (bot.ownerId !== session!.user.id) {
        console.warn(`IDOR blocked: user ${session!.user.id} tried to access bot ${botId} owned by ${bot.ownerId}`);
        return error('Forbidden', 403);
      }
      return success(toBotWithStatusDTO(bot, await getContainerStatus(bot.id)));
    }

    // Update bot (returns DTO - no sensitive fields)
    // Password/TOTP: empty = no change, non-empty = update
    if (method === 'PUT') {
      try {
        const existingBot = await getBot(botId);
        if (!existingBot) {
          return error('Bot not found', 404);
        }
        // Check ownership
        if (existingBot.ownerId !== session!.user.id) {
          console.warn(`IDOR blocked: user ${session!.user.id} tried to update bot ${botId} owned by ${existingBot.ownerId}`);
          return error('Forbidden', 403);
        }

        const body = await req.json();
        const parsed = botUpdateSchema.safeParse(body);

        if (!parsed.success) {
          return error(`Invalid bot configuration: ${formatValidationErrors(parsed.error.issues)}`, 400);
        }

        // Merge: keep existing password/TOTP if not provided in update
        const updateData: BotFormData = {
          ...parsed.data,
          password: parsed.data.password ?? existingBot.password,
          totpSecret: parsed.data.totpSecret ?? existingBot.totpSecret,
        };

        const updated = await updateBot(botId, updateData);
        if (!updated) {
          return error('Bot not found', 404);
        }
        return success(toBotDTO(updated));
      } catch (err) {
        return error(err instanceof Error ? err.message : 'Failed to update bot');
      }
    }

    // Delete bot
    if (method === 'DELETE') {
      try {
        const existingBot = await getBot(botId);
        if (!existingBot) {
          return error('Bot not found', 404);
        }
        // Check ownership
        if (existingBot.ownerId !== session!.user.id) {
          console.warn(`IDOR blocked: user ${session!.user.id} tried to delete bot ${botId} owned by ${existingBot.ownerId}`);
          return error('Forbidden', 403);
        }

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
  if (path.match(/^\/api\/bots\/[^/]+\/(start|stop|restart)$/) && method === 'POST') {
    const result = await withOwnedBot(session!, path, /^\/api\/bots\/([^/]+)\/(start|stop|restart)$/);
    if (!result.ok) return result.response;
    
    const actionMatch = path.match(/\/(start|stop|restart)$/);
    const action = actionMatch![1];

    try {
      switch (action) {
        case 'start':
          await startBot(result.bot);
          return success({ started: true });

        case 'stop':
          await stopBot(result.botId);
          return success({ stopped: true });

        case 'restart':
          await restartBot(result.bot);
          return success({ restarted: true });

        default:
          return error('Unknown action', 400);
      }
    } catch (err) {
      return error(err instanceof Error ? err.message : `Failed to ${action} bot`);
    }
  }

  // Bot logs (GET for SSE)
  if (path.match(/^\/api\/bots\/[^/]+\/logs$/) && method === 'GET') {
    const result = await withOwnedBot(session!, path, /^\/api\/bots\/([^/]+)\/logs$/);
    if (!result.ok) return result.response;

    try {
      const logStream = await getContainerLogs(result.botId);
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

  // ==========================================================================
  // DATA API ROUTES (auth already validated, no ownership - shared data)
  // ==========================================================================

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
      const baseDir = resolve('./dist/client');
      
      // Validate path to prevent directory traversal attacks
      const securePath = validateSecurePath(baseDir, filePath);
      
      const file = Bun.file(securePath);
      if (await file.exists()) {
        return new Response(file);
      }
      
      // SPA fallback - also validate this path
      const indexPath = validateSecurePath(baseDir, '/index.html');
      const indexFile = Bun.file(indexPath);
      if (await indexFile.exists()) {
        return new Response(indexFile);
      }
    } catch (err) {
      // Path traversal attempts or other errors
      if (err instanceof Error && err.message.includes('traversal')) {
        console.warn(`Path traversal attempt blocked: ${path}`);
        return error('Invalid path', 400);
      }
      // Fall through to 404 for other errors
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
  
  // Security: Limit request body size to prevent DOS attacks
  // 10MB is generous for bot configs, commands, and other API payloads
  maxRequestBodySize: 10 * 1024 * 1024, // 10MB
  
  websocket: {
    // Idle timeout in seconds - set to 120s, ping every 30s keeps connection alive
    idleTimeout: 120,
    
    // Security: Limit WebSocket message size to prevent DOS attacks
    // 10MB allows for VNC full-screen updates (1280x720 can be ~3.5MB uncompressed)
    // Status updates are tiny (<1KB), so this is primarily for VNC
    maxPayloadLength: 10 * 1024 * 1024, // 10MB
    
    // Called when WebSocket connection opens
    async open(ws: ServerWebSocket<WebSocketData>) {
      if (ws.data.type === 'vnc') {
        const vncData = ws.data as VncWebSocketData;
        const { botId } = vncData;
        console.log(`VNC WebSocket opened for bot ${botId}`);
        
        // Get VNC socket path from status directory
        const vncSocketPath = getVncSocketPath(botId);
        
        // Connection settings - keeps WebSocket open while waiting for VNC server
        const MAX_RETRIES = 60;  // ~60 seconds max wait per connection attempt
        const BASE_DELAY_MS = 500;
        const MAX_DELAY_MS = 2000;
        
        // Recursive function to connect to VNC socket with retry
        const tryConnect = async (attempt: number): Promise<void> => {
          // Check if WebSocket was closed while we were waiting
          if (ws.readyState !== 1) {
            console.log(`VNC WebSocket closed, stopping connection attempts for bot ${botId}`);
            return;
          }
          
          try {
            // Connect to VNC server via Unix socket
            const tcpSocket = await Bun.connect({
              unix: vncSocketPath,
              socket: {
                data(socket, data) {
                  // Forward VNC server data to WebSocket client
                  const wsConn = socket.data.ws;
                  if (wsConn.readyState === 1) { // OPEN
                    const bytesSent = wsConn.send(data);
                    if (bytesSent === 0) {
                      console.warn(`VNC frame dropped for bot ${botId}: send buffer full (frame size: ${data.length} bytes)`);
                    }
                  }
                },
                open(socket) {
                  console.log(`Unix socket connection to VNC established: ${vncSocketPath}`);
                  const wsConn = socket.data.ws;
                  const wsData = wsConn.data as VncWebSocketData;
                  
                  // If this is a REconnection after VNC server restart, we need to close
                  // the WebSocket so the client can start a fresh VNC session.
                  // noVNC can't handle receiving a new RFB handshake mid-session.
                  if (wsData.hadConnection) {
                    console.log(`VNC reconnected for bot ${botId} - closing WebSocket for fresh client session`);
                    socket.end(); // Close Unix socket first
                    wsConn.close(1000, 'VNC session restarted - please reconnect');
                    return;
                  }
                  
                  // Mark that we've had a successful connection
                  wsData.hadConnection = true;
                },
                close(socket) {
                  // VNC server disconnected (container restart, etc.)
                  // Keep WebSocket open and try to reconnect
                  console.log(`Unix socket connection to VNC closed: ${vncSocketPath}`);
                  const wsConn = socket.data.ws;
                  const wsData = wsConn.data as VncWebSocketData;
                  wsData.tcpSocket = null;
                  
                  if (wsConn.readyState === 1) {
                    // Don't close WebSocket - start reconnection attempts
                    console.log(`VNC server disconnected for bot ${botId}, waiting for reconnect...`);
                    // Small delay before starting reconnection to allow server to restart
                    setTimeout(() => tryConnect(0), 1000);
                  }
                },
                error(socket, err) {
                  console.error(`Unix socket error for VNC ${vncSocketPath}:`, err);
                  const wsConn = socket.data.ws;
                  const wsData = wsConn.data as VncWebSocketData;
                  wsData.tcpSocket = null;
                  
                  if (wsConn.readyState === 1) {
                    // Don't close WebSocket - try to reconnect
                    console.log(`VNC connection error for bot ${botId}, attempting reconnect...`);
                    setTimeout(() => tryConnect(0), 1000);
                  }
                },
              },
              data: { ws: ws as ServerWebSocket<VncWebSocketData> },
            });
            
            vncData.tcpSocket = tcpSocket;
          } catch (err: any) {
            // ENOENT = socket doesn't exist yet (container starting)
            // ECONNREFUSED = socket exists but nothing listening
            if (err?.code === 'ENOENT' || err?.code === 'ECONNREFUSED') {
              if (attempt < MAX_RETRIES) {
                // Calculate delay with exponential backoff (capped)
                const delay = Math.min(BASE_DELAY_MS * Math.pow(1.2, attempt), MAX_DELAY_MS);
                if (attempt === 0) {
                  console.log(`VNC socket not ready for bot ${botId}, waiting...`);
                }
                await new Promise((resolve) => setTimeout(resolve, delay));
                return tryConnect(attempt + 1);
              } else {
                // After max retries, just keep waiting silently with periodic checks
                console.log(`VNC socket still not ready for bot ${botId}, continuing to wait...`);
                await new Promise((resolve) => setTimeout(resolve, MAX_DELAY_MS));
                return tryConnect(0); // Reset attempt counter and keep trying
              }
            } else {
              // Unexpected error (permission denied, etc.)
              console.error(`Failed to connect to VNC socket ${vncSocketPath}:`, err);
              ws.close(1011, 'Failed to connect to VNC server');
            }
          }
        };
        
        // Start connection attempts (non-blocking)
        tryConnect(0);
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
        // VNC View-only mode: Defense-in-depth filter (x11vnc -viewonly is the primary control)
        // This filter provides early rejection and logging, but isn't foolproof against
        // message smuggling attacks. The VNC server enforces view-only authoritatively.
        // RFB message types (first byte after handshake):
        //   0 = SetPixelFormat (ALLOW - display config)
        //   2 = SetEncodings (ALLOW - compression config)
        //   3 = FramebufferUpdateRequest (ALLOW - needed to receive frames!)
        //   4 = KeyEvent (BLOCK - keyboard input)
        //   5 = PointerEvent (BLOCK - mouse input)
        //   6 = ClientCutText (BLOCK - clipboard)
        // Handshake messages don't follow this format, so we allow short messages
        const vncData = ws.data as VncWebSocketData;
        const { tcpSocket, botId } = vncData;
        
        if (!tcpSocket) return;
        
        const buffer = typeof message === 'string' ? Buffer.from(message) : message;
        
        // Allow handshake messages (they're short and don't have the same format)
        // After handshake, RFB messages have type byte at position 0
        if (buffer.length > 0) {
          const messageType = buffer[0];
          
          // Block input events (4=KeyEvent, 5=PointerEvent, 6=ClientCutText)
          if (messageType === 4 || messageType === 5 || messageType === 6) {
            // Silently drop input events - view-only mode
            return;
          }
        }
        
        // Forward allowed messages (handshake, SetPixelFormat, SetEncodings, FramebufferUpdateRequest)
        tcpSocket.write(buffer);
        return;
      } else if (ws.data.type === 'status') {
        // Handle commands from client
        const statusData = ws.data as StatusWebSocketData;
        try {
          const data = typeof message === 'string' ? message : message.toString();
          const parsed = JSON.parse(data);
          
          if (parsed.type === 'command' && parsed.command) {
            // Validate command payload with same schema as REST endpoint
            const validation = botCommandPayloadSchema.safeParse(parsed.command);
            if (!validation.success) {
              console.warn(`Invalid WebSocket command for bot ${statusData.botId}:`, validation.error.issues);
              return;
            }
            sendBotCommand(statusData.botId, validation.data)
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
        const vncData = ws.data as VncWebSocketData;
        console.log(`VNC WebSocket closed for bot ${vncData.botId}: code=${code} reason=${reason || 'none'}`);
        if (vncData.tcpSocket) {
          vncData.tcpSocket.end();
        }
      } else if (ws.data.type === 'status') {
        const statusData = ws.data as StatusWebSocketData;
        console.log(`Status WebSocket closed for bot ${statusData.botId}: code=${code} reason=${reason || 'none'}`);
        if (statusData.pingInterval) {
          clearInterval(statusData.pingInterval);
        }
        if (statusData.cleanup) {
          statusData.cleanup();
        }
      }
    },
    
    // Called when WebSocket send buffer is full (backpressure)
    drain(ws: ServerWebSocket<WebSocketData>) {
      if (ws.data.type === 'vnc') {
        const vncData = ws.data as VncWebSocketData;
        console.warn(`VNC WebSocket drain event for bot ${vncData.botId} - send buffer was full`);
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
