import { readFile, writeFile, mkdir } from 'fs/promises';
import { existsSync, mkdirSync } from 'fs';
import { dirname } from 'path';
import type { BotConfig, BotsConfigFile } from '../shared/types';

const CONFIG_PATH = process.env.CONFIG_PATH || './data/bots.json';

const defaultConfig: BotsConfigFile = {
  bots: [],
};

// Ensure config directory exists
function ensureConfigDir(): void {
  const dir = dirname(CONFIG_PATH);
  if (!existsSync(dir)) {
    mkdirSync(dir, { recursive: true });
  }
}

export async function readConfig(): Promise<BotsConfigFile> {
  try {
    ensureConfigDir();
    if (!existsSync(CONFIG_PATH)) {
      await writeConfig(defaultConfig);
      return defaultConfig;
    }
    const content = await readFile(CONFIG_PATH, 'utf-8');
    return JSON.parse(content) as BotsConfigFile;
  } catch (err) {
    console.error('Failed to read config:', err);
    return defaultConfig;
  }
}

export async function writeConfig(config: BotsConfigFile): Promise<void> {
  ensureConfigDir();
  await writeFile(CONFIG_PATH, JSON.stringify(config, null, 2), 'utf-8');
}

export async function getBots(): Promise<BotConfig[]> {
  const config = await readConfig();
  return config.bots;
}

export async function getBot(id: string): Promise<BotConfig | null> {
  const config = await readConfig();
  return config.bots.find((b) => b.id === id) || null;
}

export async function createBot(bot: BotConfig): Promise<BotConfig> {
  const config = await readConfig();
  config.bots.push(bot);
  await writeConfig(config);
  return bot;
}

export async function updateBot(id: string, updates: Partial<BotConfig>): Promise<BotConfig | null> {
  const config = await readConfig();
  const index = config.bots.findIndex((b) => b.id === id);
  if (index === -1) return null;

  config.bots[index] = { ...config.bots[index], ...updates, id };
  await writeConfig(config);
  return config.bots[index];
}

export async function deleteBot(id: string): Promise<boolean> {
  const config = await readConfig();
  const index = config.bots.findIndex((b) => b.id === id);
  if (index === -1) return false;

  config.bots.splice(index, 1);
  await writeConfig(config);
  return true;
}

