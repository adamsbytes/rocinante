import { Database } from 'bun:sqlite';
import { join } from 'path';
import { mkdirSync, existsSync } from 'fs';
import type { BotConfig, ProxyConfig } from '../shared/types';
import { encrypt, decrypt } from './crypto';

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

// Initialize secrets table (shared secrets for all bots)
db.exec(`
  CREATE TABLE IF NOT EXISTS secrets (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    created_at TEXT DEFAULT (datetime('now'))
  );
`);

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

// Decrypt proxy password if present
function decryptProxy(proxy: ProxyConfig, aad: string): ProxyConfig {
  if (proxy.pass) {
    return { ...proxy, pass: decrypt(proxy.pass, aad) };
  }
  return proxy;
}

// Convert database row to BotConfig (decrypts sensitive fields)
function fromRow(row: BotRow): BotConfig {
  const aad = row.id;
  return {
    id: row.id,
    ownerId: row.owner_id,
    username: row.username,
    password: decrypt(row.password, aad),
    totpSecret: decrypt(row.totp_secret, aad),
    characterName: row.character_name,
    preferredWorld: row.preferred_world ?? undefined,
    lampSkill: row.lamp_skill as BotConfig['lampSkill'],
    proxy: row.proxy ? decryptProxy(JSON.parse(row.proxy), aad) : null,
    ironman: JSON.parse(row.ironman),
    resources: JSON.parse(row.resources),
    environment: JSON.parse(row.environment),
    vncPassword: decrypt(row.vnc_password, aad),
    fingerprintSeed: decrypt(row.fingerprint_seed, aad),
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

// Encrypt proxy password if present
function encryptProxy(proxy: ProxyConfig | null, aad: string): string | null {
  if (!proxy) return null;
  const encrypted = proxy.pass
    ? { ...proxy, pass: encrypt(proxy.pass, aad) }
    : proxy;
  return JSON.stringify(encrypted);
}

export function createBot(bot: BotConfig): BotConfig {
  const aad = bot.id;
  statements.insert.run({
    $id: bot.id,
    $owner_id: bot.ownerId,
    $username: bot.username,
    $password: encrypt(bot.password, aad),
    $totp_secret: encrypt(bot.totpSecret, aad),
    $character_name: bot.characterName,
    $preferred_world: bot.preferredWorld ?? null,
    $lamp_skill: bot.lampSkill,
    $proxy: encryptProxy(bot.proxy, aad),
    $ironman: JSON.stringify(bot.ironman),
    $resources: JSON.stringify(bot.resources),
    $environment: JSON.stringify(bot.environment),
    $vnc_password: encrypt(bot.vncPassword, aad),
    $fingerprint_seed: encrypt(bot.fingerprintSeed, aad),
  });
  return bot;
}

export function updateBot(id: string, updates: Partial<BotConfig>): BotConfig | null {
  const existing = getBot(id);
  if (!existing) return null;

  // Merge updates with existing
  const updated: BotConfig = { ...existing, ...updates, id };
  const aad = id;

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
    params.$password = encrypt(updates.password, aad);
  }
  if (updates.totpSecret !== undefined) {
    setClauses.push('totp_secret = $totp_secret');
    params.$totp_secret = encrypt(updates.totpSecret, aad);
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
    params.$proxy = encryptProxy(updates.proxy, aad);
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
    params.$vnc_password = encrypt(updates.vncPassword, aad);
  }
  if (updates.fingerprintSeed !== undefined) {
    setClauses.push('fingerprint_seed = $fingerprint_seed');
    params.$fingerprint_seed = encrypt(updates.fingerprintSeed, aad);
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

// =============================================================================
// Shared Secrets
// =============================================================================

const secretStatements = {
  get: db.query<{ value: string }, [string]>('SELECT value FROM secrets WHERE key = ?'),
  set: db.query<void, { $key: string; $value: string }>(
    'INSERT OR REPLACE INTO secrets (key, value) VALUES ($key, $value)'
  ),
};

/**
 * Get or generate the wiki cache signing secret.
 * Creates a new 64-char hex secret if one doesn't exist.
 */
export function getWikiCacheSecret(): string {
  const row = secretStatements.get.get('wiki_cache_secret');
  if (row) {
    return row.value;
  }

  // Generate new secret
  const secret = Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString('hex');
  secretStatements.set.run({ $key: 'wiki_cache_secret', $value: secret });
  console.log('Generated new wiki cache signing secret');
  return secret;
}
