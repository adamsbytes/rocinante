import Docker from 'dockerode';
import type { BotConfig, BotStatus } from '../shared/types';

const docker = new Docker({ socketPath: '/var/run/docker.sock' });

const BOT_IMAGE = process.env.BOT_IMAGE || 'rocinante-bot:latest';
const CONTAINER_PREFIX = 'rocinante_';

// In-memory log storage per bot (survives container restarts)
const botLogs = new Map<string, string[]>();
const MAX_LOG_LINES = 2000;

function appendBotLogs(botId: string, lines: string[]) {
  const existing = botLogs.get(botId) || [];
  const combined = [...existing, ...lines].slice(-MAX_LOG_LINES);
  botLogs.set(botId, combined);
}

function getBotLogs(botId: string): string[] {
  return botLogs.get(botId) || [];
}

function parseMemory(mem: string): number {
  const match = mem.match(/^(\d+(?:\.\d+)?)\s*([KMGT]?)B?$/i);
  if (!match) return 2 * 1024 * 1024 * 1024; // default 2GB

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

function parseCpu(cpu: string): number {
  // Docker uses NanoCpus (1 CPU = 1e9)
  const value = parseFloat(cpu);
  return Math.floor(value * 1e9);
}

async function captureContainerLogs(botId: string, container: Docker.Container): Promise<void> {
  try {
    const logBuffer = await container.logs({
      follow: false,
      stdout: true,
      stderr: true,
    });

    // Docker log stream has 8-byte header per frame, strip them
    const lines: string[] = [];
    let offset = 0;
    const buf = Buffer.from(logBuffer as unknown as ArrayBuffer);

    while (offset < buf.length) {
      if (offset + 8 > buf.length) break;
      const size = buf.readUInt32BE(offset + 4);
      if (offset + 8 + size > buf.length) break;
      const line = buf.slice(offset + 8, offset + 8 + size).toString('utf-8');
      lines.push(line);
      offset += 8 + size;
    }

    // Store in memory
    appendBotLogs(botId, lines);
  } catch (err) {
    console.error(`Failed to capture logs for bot ${botId}:`, err);
  }
}

export async function getContainerStatus(botId: string): Promise<BotStatus> {
  const containerName = `${CONTAINER_PREFIX}${botId}`;

  try {
    const containers = await docker.listContainers({ all: true });
    const container = containers.find((c) =>
      c.Names.some((n) => n === `/${containerName}`)
    );

    if (!container) {
      return {
        id: botId,
        containerId: null,
        state: 'stopped',
      };
    }

    const state = container.State.toLowerCase();
    let mappedState: BotStatus['state'] = 'stopped';

    if (state === 'running') {
      mappedState = 'running';
    } else if (state === 'created' || state === 'restarting') {
      mappedState = 'starting';
    } else if (state === 'paused' || state === 'exited' || state === 'dead') {
      mappedState = 'stopped';
    }

    return {
      id: botId,
      containerId: container.Id,
      state: mappedState,
    };
  } catch (err) {
    console.error(`Error getting status for ${botId}:`, err);
    return {
      id: botId,
      containerId: null,
      state: 'error',
      error: err instanceof Error ? err.message : 'Unknown error',
    };
  }
}

export async function startBot(bot: BotConfig): Promise<void> {
  const containerName = `${CONTAINER_PREFIX}${bot.id}`;

  // Always remove any existing container to ensure fresh state
  // Bot config is separate from container lifecycle
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

    // Capture logs before removing container
    await captureContainerLogs(bot.id, container);
    await container.remove();
  }

  // Add separator for new run
  appendBotLogs(bot.id, ['\n--- New container starting ---\n']);

  // Build environment variables for Jagex Launcher authentication
  const env: string[] = [
    'DISPLAY=:99',
    `ACCOUNT_EMAIL=${bot.username}`,
    `ACCOUNT_PASSWORD=${bot.password}`,
  ];

  // Add TOTP secret if provided (for 2FA)
  if (bot.totpSecret) {
    env.push(`TOTP_SECRET=${bot.totpSecret}`);
  }

  // Add character name if provided (for new accounts)
  if (bot.characterName) {
    env.push(`CHARACTER_NAME=${bot.characterName}`);
  }

  // Add preferred world if provided (defaults to 301 F2P in entrypoint)
  if (bot.preferredWorld) {
    env.push(`PREFERRED_WORLD=${bot.preferredWorld}`);
  }

  if (bot.proxy) {
    env.push(`PROXY_HOST=${bot.proxy.host}`);
    env.push(`PROXY_PORT=${bot.proxy.port}`);
    if (bot.proxy.user) env.push(`PROXY_USER=${bot.proxy.user}`);
    if (bot.proxy.pass) env.push(`PROXY_PASS=${bot.proxy.pass}`);
  }

  if (bot.ironman.enabled) {
    env.push(`IRONMAN_MODE=true`);
    if (bot.ironman.type) env.push(`IRONMAN_TYPE=${bot.ironman.type}`);
    if (bot.ironman.hcimSafetyLevel) {
      env.push(`HCIM_SAFETY_LEVEL=${bot.ironman.hcimSafetyLevel}`);
    }
  } else {
    env.push('IRONMAN_MODE=false');
  }

  // Pass through Claude API key if available
  if (process.env.CLAUDE_API_KEY) {
    env.push(`CLAUDE_API_KEY=${process.env.CLAUDE_API_KEY}`);
  }

  // Create and start container
  const container = await docker.createContainer({
    Image: BOT_IMAGE,
    name: containerName,
    Env: env,
    HostConfig: {
      PortBindings: {
        '5900/tcp': [{ HostPort: String(bot.vncPort) }],
      },
      Memory: parseMemory(bot.resources.memoryLimit),
      NanoCpus: parseCpu(bot.resources.cpuLimit),
      // Chromium needs more shared memory than Docker's 64MB default
      ShmSize: 256 * 1024 * 1024, // 256MB
      Binds: [
        // Mount volumes for persistent data
        `rocinante_profiles_${bot.id}:/home/runelite/.runelite/rocinante/profiles`,
        `rocinante_logs_${bot.id}:/home/runelite/.runelite/logs`,
        `rocinante_settings_${bot.id}:/home/runelite/.runelite/settings`,
        // Bolt launcher stores login session here
        `rocinante_bolt_${bot.id}:/home/runelite/.local/share/bolt-launcher`,
      ],
      // Auto-restart on crash, but respect explicit stop commands
      RestartPolicy: {
        Name: 'unless-stopped',
      },
    },
    ExposedPorts: {
      '5900/tcp': {},
    },
  });

  await container.start();
}

export async function stopBot(botId: string): Promise<void> {
  const containerName = `${CONTAINER_PREFIX}${botId}`;

  const containers = await docker.listContainers({ all: true });
  const existing = containers.find((c) =>
    c.Names.some((n) => n === `/${containerName}`)
  );

  if (!existing) {
    return; // Container doesn't exist
  }

  const container = docker.getContainer(existing.Id);
  const info = await container.inspect();

  if (info.State.Running) {
    await container.stop({ t: 10 }); // 10 second timeout
  }

  // Save logs before removing container
  await captureContainerLogs(botId, container);

  // Always remove container on stop - bot config is separate from container
  await container.remove();
}

export async function restartBot(bot: BotConfig): Promise<void> {
  // Stop and remove existing container, then start fresh
  await stopBot(bot.id);
  await startBot(bot);
}

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

  // Save logs before removing container
  await captureContainerLogs(botId, container);
  await container.remove();
}

export async function getContainerLogs(botId: string): Promise<ReadableStream<string>> {
  const containerName = `${CONTAINER_PREFIX}${botId}`;

  const containers = await docker.listContainers({ all: true });
  const existing = containers.find((c) =>
    c.Names.some((n) => n === `/${containerName}`)
  );

  // Always return in-memory logs first, then stream new ones if container is running
  const existingLogs = getBotLogs(botId);

  // No running container - return in-memory logs only
  if (!existing) {
    return new ReadableStream({
      start(controller) {
        if (existingLogs.length === 0) {
          controller.enqueue('[No logs available]\n');
        } else {
          for (const line of existingLogs) {
            controller.enqueue(line);
          }
        }
        controller.close();
      },
    });
  }

  const container = docker.getContainer(existing.Id);
  const logStream = await container.logs({
    follow: true,
    stdout: true,
    stderr: true,
    tail: 100,
  });

  // Convert Node stream to Web ReadableStream
  return new ReadableStream({
    start(controller) {
      // First send existing in-memory logs
      for (const line of existingLogs) {
        controller.enqueue(line);
      }

      logStream.on('data', (chunk: Buffer) => {
        // Docker log stream has 8-byte header per frame
        // Skip header and send actual log content
        const content = chunk.slice(8).toString('utf-8');
        controller.enqueue(content);
        // Also store in memory
        appendBotLogs(botId, [content]);
      });

      logStream.on('end', () => {
        try {
          controller.close();
        } catch {
          // Controller already closed
        }
      });

      logStream.on('error', (err: Error) => {
        try {
          controller.error(err);
        } catch {
          // Controller already closed
        }
      });
    },
  });
}

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

export async function buildBotImage(): Promise<void> {
  const botPath = process.env.BOT_PATH || '/app/bot';
  
  console.log(`Building bot image from ${botPath}...`);
  
  const stream = await docker.buildImage(
    { context: botPath, src: ['.'] },
    { t: BOT_IMAGE }
  );

  // Wait for build to complete
  await new Promise<void>((resolve, reject) => {
    docker.modem.followProgress(
      stream,
      (err: Error | null, result: any[]) => {
        if (err) {
          reject(err);
        } else {
          console.log('Bot image built successfully');
          resolve();
        }
      },
      (event: any) => {
        if (event.stream) {
          process.stdout.write(event.stream);
        }
      }
    );
  });
}

