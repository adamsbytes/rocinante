/**
 * Docker operations for the worker process.
 * This is the only process that has Docker socket access.
 */

import Docker from 'dockerode';
import tar from 'tar-stream';
import { writeFileSync } from 'fs';
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
const CONTAINER_PREFIX = 'rocinante_';

// =============================================================================
// Utility Functions
// =============================================================================

const HOSTNAME_PREFIXES = [
  'desktop', 'pc', 'linux', 'home', 'workstation', 'main',
  'hp', 'dell', 'lenovo', 'asus', 'acer',
  'ubuntu', 'pop', 'fedora', 'arch',
];

function hashCode(str: string): number {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash) + str.charCodeAt(i);
    hash |= 0;
  }
  return hash;
}

function generateHostname(botId: string): string {
  const suffix = botId.replace(/-/g, '').substring(0, 6).toLowerCase();
  const prefix = HOSTNAME_PREFIXES[Math.abs(hashCode(botId)) % HOSTNAME_PREFIXES.length];
  return `${prefix}-${suffix}`;
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

  // Add config.json with read-only permissions for owner
  pack.entry({ name: 'config.json', mode: 0o600 }, configJson);
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
 * Start a bot container with provided config.
 * Config is injected directly from memory via putArchive - never touches host disk.
 */
async function startBotWithConfig(bot: BotConfig): Promise<void> {
  const containerName = `${CONTAINER_PREFIX}${bot.id}`;

  // Always remove any existing container to ensure fresh state
  const containers = await docker.listContainers({ all: true });
  const existing = containers.find((c) =>
    c.Names.some((n) => n === `/${containerName}`)
  );

  if (existing) {
    const container = docker.getContainer(existing.Id);
    const info = await container.inspect();

    if (info.State.Running) {
      await container.stop({ t: 10 });
    }

    await container.remove();
  }

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

  // Validate required config fields (fail loud)
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

  const machineIdPath = writeMachineIdFile(bot.id, bot.environment.machineId);
  const hostname = generateHostname(bot.id);
  const boltLauncherPath = '/home/runelite/.local/share/bolt-launcher/.runelite';

  // No environment variables - all config comes from /home/runelite/config.json
  const container = await docker.createContainer({
    Image: BOT_IMAGE,
    name: containerName,
    Hostname: hostname,
    Env: [],
    HostConfig: {
      Memory: parseMemory(bot.resources.memoryLimit),
      NanoCpus: parseCpu(bot.resources.cpuLimit),
      ShmSize: 256 * 1024 * 1024,
      Binds: [
        `${boltDataDir}:/home/runelite/.local/share/bolt-launcher`,
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

  // Inject wiki cache secret (shared across all bots for cache signing)
  const botWithSecret = { ...bot, wikiCacheSecret: getWikiCacheSecret() };

  // Inject config.json directly into container from memory (never touches host disk)
  const configTar = await createConfigTar(botWithSecret);
  await container.putArchive(configTar, { path: '/home/runelite' });

  await container.start();
}

/**
 * Stop a bot container.
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
  }

  await container.remove();
  await resetStatusFile(botId);
}

/**
 * Restart a bot container.
 */
export async function restartBot(botId: string): Promise<void> {
  await stopBot(botId);
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
