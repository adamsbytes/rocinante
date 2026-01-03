/**
 * Docker operations for the worker process.
 * This is the only process that has Docker socket access.
 */

import Docker from 'dockerode';
import tar from 'tar-stream';
import { writeFileSync } from 'fs';
import { createHash } from 'crypto';
import type { BotConfig } from '../shared/types';
import type { ContainerStatus } from '../shared/worker-protocol';
import {
  getStatusDir,
  ensureStatusDir,
  resetStatusFile,
  getMachineIdPath,
  getScreenshotsDir,
  ensureScreenshotsDir,
  getSharedCacheDir,
  ensureSharedCacheDir,
  getTrainingSpotCacheDir,
  ensureTrainingSpotCacheDir,
  getBoltDataDir,
  ensureBoltDataDir,
} from '../api/status';
import { getBot, getWikiCacheSecret } from '../api/config';

const docker = new Docker({ socketPath: '/var/run/docker.sock' });

const BOT_IMAGE = process.env.BOT_IMAGE || 'rocinante-bot:latest';

// Label keys for container metadata
const LABEL_CONFIG_HASH = 'rocinante.config-hash';
const LABEL_IMAGE_ID = 'rocinante.image-id';  // Actual image ID (sha256), not tag
const CONTAINER_PREFIX = 'rocinante_';

// =============================================================================
// Utility Functions
// =============================================================================

// Steam Deck identity: all containers use the same hostname
// This matches the default Steam Deck hostname
function generateHostname(_botId: string): string {
  return 'steamdeck';
}

function writeMachineIdFile(botId: string, machineId: string): string {
  const machineIdPath = getMachineIdPath(botId);
  writeFileSync(machineIdPath, machineId + '\n', { mode: 0o644 });
  return machineIdPath;
}

function parseMemory(mem: string): number {
  const match = mem.match(/^(\d+(?:\.\d+)?)\s*([KMGT]?)B?$/i);
  if (!match) return 2 * 1024 * 1024 * 1024;

  const value = parseFloat(match[1]);
  const unit = match[2].toUpperCase();

  const multipliers: Record<string, number> = {
    '': 1,
    'K': 1024,
    'M': 1024 * 1024,
    'G': 1024 * 1024 * 1024,
    'T': 1024 * 1024 * 1024 * 1024,
  };

  return Math.floor(value * (multipliers[unit] || 1));
}

/**
 * Create a tar archive containing config.json in memory.
 * This is used to inject bot config directly into the container without touching the host disk.
 */
async function createConfigTar(bot: BotConfig): Promise<Buffer> {
  const pack = tar.pack();
  const configJson = JSON.stringify(bot, null, 2);

  // Add config.json owned by deck user (uid 1000, gid 998 wheel) so entrypoint can read it
  pack.entry({ name: 'config.json', mode: 0o644, uid: 1000, gid: 998 }, configJson);
  pack.finalize();

  // Convert stream to buffer
  const chunks: Buffer[] = [];
  for await (const chunk of pack) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

function parseCpu(cpu: string): number {
  const value = parseFloat(cpu);
  return Math.floor(value * 1e9);
}

/**
 * Compute a hash of config values that require container recreation if changed.
 * This includes: memory, CPU, and bind mount paths (derived from botId).
 * NOTE: Image ID is tracked separately since it changes independently of bot config.
 * Runtime config (credentials, settings) can be injected via putArchive without recreation.
 */
function computeContainerSpecHash(bot: BotConfig): string {
  const spec = {
    memory: bot.resources.memoryLimit,
    cpu: bot.resources.cpuLimit,
    // Bind paths are derived from botId, so if botId changes we'd have a different container anyway
  };
  return createHash('sha256').update(JSON.stringify(spec)).digest('hex').slice(0, 16);
}

/**
 * Get the actual image ID (sha256 digest) for an image tag.
 * This allows us to detect when an image has been rebuilt with the same tag.
 */
async function getImageId(imageName: string): Promise<string | null> {
  try {
    const image = docker.getImage(imageName);
    const info = await image.inspect();
    return info.Id;  // e.g., "sha256:abc123..."
  } catch {
    return null;
  }
}

// =============================================================================
// Docker Connection & Image Check
// =============================================================================

export async function checkDockerConnection(): Promise<boolean> {
  try {
    await docker.ping();
    return true;
  } catch {
    return false;
  }
}

export async function checkBotImage(): Promise<boolean> {
  try {
    await docker.getImage(BOT_IMAGE).inspect();
    return true;
  } catch {
    return false;
  }
}

// =============================================================================
// Container Status
// =============================================================================

/**
 * Get status of all rocinante containers.
 * Returns a map of botId -> ContainerStatus.
 */
export async function getAllContainerStatuses(): Promise<Record<string, ContainerStatus>> {
  const result: Record<string, ContainerStatus> = {};

  try {
    const containers = await docker.listContainers({ all: true });

    for (const container of containers) {
      // Find rocinante containers by name prefix
      const name = container.Names.find((n) => n.startsWith(`/${CONTAINER_PREFIX}`));
      if (!name) continue;

      // Extract bot ID from container name
      const botId = name.slice(CONTAINER_PREFIX.length + 1); // +1 for leading /

      const state = container.State.toLowerCase();
      let mappedState: ContainerStatus['state'] = 'stopped';

      if (state === 'running') {
        mappedState = 'running';
      } else if (state === 'created' || state === 'restarting') {
        mappedState = 'starting';
      } else if (state === 'paused' || state === 'exited' || state === 'dead') {
        mappedState = 'stopped';
      }

      result[botId] = {
        containerId: container.Id,
        state: mappedState,
      };
    }
  } catch (err) {
    console.error('Error listing containers:', err);
  }

  return result;
}

/**
 * Get status of a single container by bot ID.
 */
export async function getContainerStatus(botId: string): Promise<ContainerStatus> {
  const containerName = `${CONTAINER_PREFIX}${botId}`;

  try {
    const containers = await docker.listContainers({ all: true });
    const container = containers.find((c) =>
      c.Names.some((n) => n === `/${containerName}`)
    );

    if (!container) {
      return { containerId: null, state: 'stopped' };
    }

    const state = container.State.toLowerCase();
    let mappedState: ContainerStatus['state'] = 'stopped';

    if (state === 'running') {
      mappedState = 'running';
    } else if (state === 'created' || state === 'restarting') {
      mappedState = 'starting';
    } else if (state === 'paused' || state === 'exited' || state === 'dead') {
      mappedState = 'stopped';
    }

    return {
      containerId: container.Id,
      state: mappedState,
    };
  } catch (err) {
    console.error(`Error getting status for ${botId}:`, err);
    return { containerId: null, state: 'error' };
  }
}

// =============================================================================
// Docker Operations
// =============================================================================

/**
 * Start a bot container.
 * Reads config from database.
 */
export async function startBot(botId: string): Promise<void> {
  const bot = getBot(botId);
  if (!bot) {
    throw new Error(`Bot ${botId} not found in database`);
  }

  await startBotWithConfig(bot);
}

/**
 * Validate bot config has all required fields.
 */
function validateBotConfig(bot: BotConfig): void {
  if (!bot.environment) {
    throw new Error(`Bot ${bot.id} missing environment config`);
  }
  if (!bot.environment.machineId) {
    throw new Error(`Bot ${bot.id} missing environment.machineId`);
  }
  if (!bot.environment.screenResolution) {
    throw new Error(`Bot ${bot.id} missing environment.screenResolution`);
  }
  if (!bot.environment.displayDpi) {
    throw new Error(`Bot ${bot.id} missing environment.displayDpi`);
  }
  if (!bot.environment.timezone) {
    throw new Error(`Bot ${bot.id} missing environment.timezone`);
  }
  if (bot.environment.displayNumber === undefined) {
    throw new Error(`Bot ${bot.id} missing environment.displayNumber`);
  }
  if (!bot.environment.screenDepth) {
    throw new Error(`Bot ${bot.id} missing environment.screenDepth`);
  }
  if (!bot.environment.gcAlgorithm) {
    throw new Error(`Bot ${bot.id} missing environment.gcAlgorithm`);
  }
  if (!bot.environment.additionalFonts) {
    throw new Error(`Bot ${bot.id} missing environment.additionalFonts`);
  }
  if (!bot.lampSkill) {
    throw new Error(`Bot ${bot.id} missing required lampSkill`);
  }
  if (!bot.totpSecret) {
    throw new Error(`Bot ${bot.id} missing required totpSecret`);
  }
  if (!bot.characterName) {
    throw new Error(`Bot ${bot.id} missing required characterName`);
  }
  if (!bot.preferredWorld) {
    throw new Error(`Bot ${bot.id} missing required preferredWorld`);
  }
  if (!bot.vncPassword) {
    throw new Error(`Bot ${bot.id} missing required vncPassword`);
  }
}

/**
 * Start a bot container with provided config.
 * 
 * Optimized lifecycle:
 * - If container exists and is stopped with matching config hash, just start it
 * - If container exists but config changed (memory/CPU/image), recreate it
 * - If container doesn't exist, create it
 * 
 * Config.json is always re-injected before starting to pick up runtime config changes.
 */
async function startBotWithConfig(bot: BotConfig): Promise<void> {
  const containerName = `${CONTAINER_PREFIX}${bot.id}`;
  
  // Validate config first
  validateBotConfig(bot);

  // Ensure directories exist for bind mounts
  await ensureStatusDir(bot.id);
  await ensureScreenshotsDir(bot.id);
  await ensureSharedCacheDir();
  await ensureTrainingSpotCacheDir();
  await ensureBoltDataDir(bot.id);

  const statusDir = getStatusDir(bot.id);
  const screenshotsDir = getScreenshotsDir(bot.id);
  const sharedCacheDir = getSharedCacheDir();
  const trainingSpotCacheDir = getTrainingSpotCacheDir();
  const boltDataDir = getBoltDataDir(bot.id);
  const machineIdPath = writeMachineIdFile(bot.id, bot.environment.machineId);
  const hostname = generateHostname(bot.id);
  const boltLauncherPath = '/home/deck/.local/share/bolt-launcher/.runelite';
  
  // Compute config hash for this bot's container spec
  const configHash = computeContainerSpecHash(bot);
  
  // Get current image ID (detects rebuilds of the same tag)
  const currentImageId = await getImageId(BOT_IMAGE);
  if (!currentImageId) {
    throw new Error(`Image ${BOT_IMAGE} not found`);
  }

  // Check for existing container
  const containers = await docker.listContainers({ all: true });
  const existing = containers.find((c) =>
    c.Names.some((n) => n === `/${containerName}`)
  );

  let container: Docker.Container;
  let needsCreate = true;

  if (existing) {
    container = docker.getContainer(existing.Id);
    const info = await container.inspect();

    // If running, nothing to do (shouldn't happen in normal flow)
    if (info.State.Running) {
      console.log(`Container ${containerName} is already running`);
      return;
    }

    // Check if container spec AND image ID match
    const existingHash = info.Config.Labels?.[LABEL_CONFIG_HASH];
    const existingImageId = info.Config.Labels?.[LABEL_IMAGE_ID];
    
    if (existingHash === configHash && existingImageId === currentImageId) {
      // Container spec and image match - can reuse
      console.log(`Reusing existing container ${containerName} (config hash and image match)`);
      needsCreate = false;
    } else {
      // Config or image changed - need to recreate
      const reason = existingHash !== configHash 
        ? `config hash ${existingHash?.slice(0, 8)} -> ${configHash.slice(0, 8)}`
        : `image ${existingImageId?.slice(7, 19)} -> ${currentImageId.slice(7, 19)}`;
      console.log(`Recreating container ${containerName} (${reason})`);
      await container.remove();
    }
  }

  if (needsCreate) {
    // Create new container with labels for tracking
    container = await docker.createContainer({
      Image: BOT_IMAGE,
      name: containerName,
      Hostname: hostname,
      Env: [],
      Labels: {
        [LABEL_CONFIG_HASH]: configHash,
        [LABEL_IMAGE_ID]: currentImageId,
      },
      HostConfig: {
        Memory: parseMemory(bot.resources.memoryLimit),
        NanoCpus: parseCpu(bot.resources.cpuLimit),
        // Steam Deck has 4 cores / 8 threads - limit to match availableProcessors()
        CpusetCpus: '0-7',
        ShmSize: 256 * 1024 * 1024,
        Binds: [
          `${boltDataDir}:/home/deck/.local/share/bolt-launcher`,
          `${statusDir}:${boltLauncherPath}/rocinante`,
          `${screenshotsDir}:${boltLauncherPath}/screenshots`,
          `${sharedCacheDir}:${boltLauncherPath}/rocinante/wiki-cache`,
          `${trainingSpotCacheDir}:${boltLauncherPath}/rocinante/training-spot-cache`,
          `${machineIdPath}:/etc/machine-id:ro`,
        ],
        RestartPolicy: {
          Name: 'unless-stopped',
        },
      },
    });
    console.log(`Created new container ${containerName}`);
  }

  // Always inject fresh config.json before starting (picks up any runtime config changes)
  const botWithSecret = { ...bot, wikiCacheSecret: getWikiCacheSecret() };
  const configTar = await createConfigTar(botWithSecret);
  await container!.putArchive(configTar, { path: '/' });

  await container!.start();
  console.log(`Started container ${containerName}`);
}

/**
 * Stop a bot container (but keep it for fast restart).
 */
export async function stopBot(botId: string): Promise<void> {
  const containerName = `${CONTAINER_PREFIX}${botId}`;

  const containers = await docker.listContainers({ all: true });
  const existing = containers.find((c) =>
    c.Names.some((n) => n === `/${containerName}`)
  );

  if (!existing) {
    await resetStatusFile(botId);
    return;
  }

  const container = docker.getContainer(existing.Id);
  const info = await container.inspect();

  if (info.State.Running) {
    await container.stop({ t: 10 });
    console.log(`Stopped container ${containerName}`);
  }

  // Don't remove - keep for fast restart
  await resetStatusFile(botId);
}

/**
 * Restart a bot container.
 * Uses stop+start to ensure fresh config is injected.
 */
export async function restartBot(botId: string): Promise<void> {
  // Stop first (keeps container)
  await stopBot(botId);
  // Start will reuse container if config hash matches, otherwise recreate
  await startBot(botId);
}

/**
 * Remove a bot container (used when deleting a bot).
 */
export async function removeContainer(botId: string): Promise<void> {
  const containerName = `${CONTAINER_PREFIX}${botId}`;

  const containers = await docker.listContainers({ all: true });
  const existing = containers.find((c) =>
    c.Names.some((n) => n === `/${containerName}`)
  );

  if (!existing) {
    return;
  }

  const container = docker.getContainer(existing.Id);
  const info = await container.inspect();

  if (info.State.Running) {
    await container.stop({ t: 10 });
  }

  await container.remove();
}

// =============================================================================
// Log Streaming
// =============================================================================

/**
 * Get a log stream for a container.
 * Returns null if container doesn't exist.
 */
export async function getContainerLogStream(botId: string): Promise<NodeJS.ReadableStream | null> {
  const containerName = `${CONTAINER_PREFIX}${botId}`;

  const containers = await docker.listContainers({ all: true });
  const existing = containers.find((c) =>
    c.Names.some((n) => n === `/${containerName}`)
  );

  if (!existing) {
    return null;
  }

  const container = docker.getContainer(existing.Id);
  const logStream = await container.logs({
    follow: true,
    stdout: true,
    stderr: true,
    tail: 100,
  });

  return logStream;
}

/**
 * Parse Docker log buffer (strips 8-byte header frames).
 */
export function parseDockerLogBuffer(buffer: Buffer): string[] {
  const lines: string[] = [];
  let offset = 0;

  while (offset < buffer.length) {
    if (offset + 8 > buffer.length) break;
    const size = buffer.readUInt32BE(offset + 4);
    if (offset + 8 + size > buffer.length) break;
    const line = buffer.slice(offset + 8, offset + 8 + size).toString('utf-8');
    lines.push(line);
    offset += 8 + size;
  }

  return lines;
}
