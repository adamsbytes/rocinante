import { Database } from 'bun:sqlite';
import { join } from 'path';
import { mkdirSync, existsSync } from 'fs';
import type { BotConfig } from '../shared/types';

const DATA_DIR = process.env.DATA_DIR || './data';
const DB_PATH = join(DATA_DIR, 'bots.db');

// Ensure data directory exists
if (!existsSync(DATA_DIR)) {
  mkdirSync(DATA_DIR, { recursive: true });
}

// Initialize SQLite database
const db = new Database(DB_PATH, { create: true });

// Enable WAL mode for better concurrent access and crash safety
db.exec('PRAGMA journal_mode = WAL');

// Initialize schema
db.exec(`
  CREATE TABLE IF NOT EXISTS bots (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    username TEXT NOT NULL,
    password TEXT NOT NULL,
    totp_secret TEXT NOT NULL,
    character_name TEXT NOT NULL,
    preferred_world INTEGER,
    lamp_skill TEXT NOT NULL,
    proxy TEXT,
    ironman TEXT NOT NULL,
    resources TEXT NOT NULL,
    environment TEXT NOT NULL,
    vnc_password TEXT NOT NULL,
    fingerprint_seed TEXT NOT NULL,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
  );
  CREATE INDEX IF NOT EXISTS idx_bots_owner_id ON bots(owner_id);
`);

// Row type from database (JSON columns are strings)
interface BotRow {
  id: string;
  owner_id: string;
  username: string;
  password: string;
  totp_secret: string;
  character_name: string;
  preferred_world: number | null;
  lamp_skill: string;
  proxy: string | null;
  ironman: string;
  resources: string;
  environment: string;
  vnc_password: string;
  fingerprint_seed: string;
  created_at: string;
  updated_at: string;
}

// Convert database row to BotConfig
function fromRow(row: BotRow): BotConfig {
  return {
    id: row.id,
    ownerId: row.owner_id,
    username: row.username,
    password: row.password,
    totpSecret: row.totp_secret,
    characterName: row.character_name,
    preferredWorld: row.preferred_world ?? undefined,
    lampSkill: row.lamp_skill as BotConfig['lampSkill'],
    proxy: row.proxy ? JSON.parse(row.proxy) : null,
    ironman: JSON.parse(row.ironman),
    resources: JSON.parse(row.resources),
    environment: JSON.parse(row.environment),
    vncPassword: row.vnc_password,
    fingerprintSeed: row.fingerprint_seed,
  };
}

// Prepared statements for better performance
const statements = {
  getAll: db.query<BotRow, []>('SELECT * FROM bots'),
  getById: db.query<BotRow, [string]>('SELECT * FROM bots WHERE id = ?'),
  getByOwner: db.query<BotRow, [string]>('SELECT * FROM bots WHERE owner_id = ?'),
  insert: db.query<void, {
    $id: string;
    $owner_id: string;
    $username: string;
    $password: string;
    $totp_secret: string;
    $character_name: string;
    $preferred_world: number | null;
    $lamp_skill: string;
    $proxy: string | null;
    $ironman: string;
    $resources: string;
    $environment: string;
    $vnc_password: string;
    $fingerprint_seed: string;
  }>(`
    INSERT INTO bots (id, owner_id, username, password, totp_secret, character_name, preferred_world, lamp_skill, proxy, ironman, resources, environment, vnc_password, fingerprint_seed)
    VALUES ($id, $owner_id, $username, $password, $totp_secret, $character_name, $preferred_world, $lamp_skill, $proxy, $ironman, $resources, $environment, $vnc_password, $fingerprint_seed)
  `),
  delete: db.query<void, [string]>('DELETE FROM bots WHERE id = ?'),
};

export function getBots(): BotConfig[] {
  const rows = statements.getAll.all();
  return rows.map(fromRow);
}

export function getBot(id: string): BotConfig | null {
  const row = statements.getById.get(id);
  return row ? fromRow(row) : null;
}

export function getBotsByOwner(ownerId: string): BotConfig[] {
  const rows = statements.getByOwner.all(ownerId);
  return rows.map(fromRow);
}

export function createBot(bot: BotConfig): BotConfig {
  statements.insert.run({
    $id: bot.id,
    $owner_id: bot.ownerId,
    $username: bot.username,
    $password: bot.password,
    $totp_secret: bot.totpSecret,
    $character_name: bot.characterName,
    $preferred_world: bot.preferredWorld ?? null,
    $lamp_skill: bot.lampSkill,
    $proxy: bot.proxy ? JSON.stringify(bot.proxy) : null,
    $ironman: JSON.stringify(bot.ironman),
    $resources: JSON.stringify(bot.resources),
    $environment: JSON.stringify(bot.environment),
    $vnc_password: bot.vncPassword,
    $fingerprint_seed: bot.fingerprintSeed,
  });
  return bot;
}

export function updateBot(id: string, updates: Partial<BotConfig>): BotConfig | null {
  const existing = getBot(id);
  if (!existing) return null;

  // Merge updates with existing
  const updated: BotConfig = { ...existing, ...updates, id };

  // Build dynamic UPDATE statement
  // NOTE: ownerId is intentionally excluded - ownership transfers are not allowed
  const setClauses: string[] = [];
  const params: Record<string, unknown> = { $id: id };

  if (updates.username !== undefined) {
    setClauses.push('username = $username');
    params.$username = updates.username;
  }
  if (updates.password !== undefined) {
    setClauses.push('password = $password');
    params.$password = updates.password;
  }
  if (updates.totpSecret !== undefined) {
    setClauses.push('totp_secret = $totp_secret');
    params.$totp_secret = updates.totpSecret;
  }
  if (updates.characterName !== undefined) {
    setClauses.push('character_name = $character_name');
    params.$character_name = updates.characterName;
  }
  if (updates.preferredWorld !== undefined) {
    setClauses.push('preferred_world = $preferred_world');
    params.$preferred_world = updates.preferredWorld ?? null;
  }
  if (updates.lampSkill !== undefined) {
    setClauses.push('lamp_skill = $lamp_skill');
    params.$lamp_skill = updates.lampSkill;
  }
  if (updates.proxy !== undefined) {
    setClauses.push('proxy = $proxy');
    params.$proxy = updates.proxy ? JSON.stringify(updates.proxy) : null;
  }
  if (updates.ironman !== undefined) {
    setClauses.push('ironman = $ironman');
    params.$ironman = JSON.stringify(updates.ironman);
  }
  if (updates.resources !== undefined) {
    setClauses.push('resources = $resources');
    params.$resources = JSON.stringify(updates.resources);
  }
  if (updates.environment !== undefined) {
    setClauses.push('environment = $environment');
    params.$environment = JSON.stringify(updates.environment);
  }
  if (updates.vncPassword !== undefined) {
    setClauses.push('vnc_password = $vnc_password');
    params.$vnc_password = updates.vncPassword;
  }
  if (updates.fingerprintSeed !== undefined) {
    setClauses.push('fingerprint_seed = $fingerprint_seed');
    params.$fingerprint_seed = updates.fingerprintSeed;
  }

  if (setClauses.length === 0) {
    return existing; // Nothing to update
  }

  // Always update timestamp
  setClauses.push("updated_at = datetime('now')");

  const sql = `UPDATE bots SET ${setClauses.join(', ')} WHERE id = $id`;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  db.query(sql).run(params as any);

  return updated;
}

export function deleteBot(id: string): boolean {
  const result = statements.delete.run(id);
  return result.changes > 0;
}
