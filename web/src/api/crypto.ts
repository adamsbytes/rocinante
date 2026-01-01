/**
 * Field-level encryption for sensitive bot credentials.
 * Uses AES-256-GCM with per-record AAD binding.
 *
 * Security properties:
 * - Confidentiality: AES-256 encryption
 * - Integrity: GCM authentication tag (128-bit)
 * - Authenticity: AAD binds ciphertext to specific bot ID
 *
 * Uses Web Crypto for random bytes (~36x faster than Node crypto.randomBytes).
 * Uses Node crypto for AES-GCM (Web Crypto is async, adds complexity for minimal gain).
 */

import { createCipheriv, createDecipheriv } from 'crypto';
import { readFileSync, writeFileSync, existsSync, chmodSync } from 'fs';
import { join } from 'path';

/** Generate cryptographically secure random bytes using Web Crypto API */
function randomBytes(length: number): Buffer {
  return Buffer.from(crypto.getRandomValues(new Uint8Array(length)));
}

const DATA_DIR = process.env.DATA_DIR || './data';
const KEY_PATH = join(DATA_DIR, '.master.key');
const ALGORITHM = 'aes-256-gcm';
const IV_LENGTH = 12; // GCM recommended IV length
const AUTH_TAG_LENGTH = 16; // 128-bit auth tag

let masterKey: Buffer | null = null;

/**
 * Get or create the master encryption key.
 * Key is stored in a file with 0600 permissions (owner read/write only).
 */
function getMasterKey(): Buffer {
  if (masterKey) return masterKey;

  if (existsSync(KEY_PATH)) {
    masterKey = readFileSync(KEY_PATH);
    if (masterKey.length !== 32) {
      throw new Error('Invalid master key length - expected 32 bytes');
    }
  } else {
    // Generate new key
    masterKey = randomBytes(32);
    writeFileSync(KEY_PATH, masterKey, { mode: 0o600 });
    chmodSync(KEY_PATH, 0o600); // Ensure permissions even if umask interfered
    console.log('Generated new master encryption key at', KEY_PATH);
  }

  return masterKey;
}

// Initialize key on module load
getMasterKey();

/**
 * Encrypt a plaintext value.
 *
 * @param plaintext - The secret to encrypt
 * @param aad - Additional authenticated data (e.g., bot ID) - binds ciphertext to context
 * @returns Base64-encoded ciphertext: iv || authTag || ciphertext
 */
export function encrypt(plaintext: string, aad: string): string {
  const key = getMasterKey();
  const iv = randomBytes(IV_LENGTH);

  const cipher = createCipheriv(ALGORITHM, key, iv, {
    authTagLength: AUTH_TAG_LENGTH,
  });
  cipher.setAAD(Buffer.from(aad, 'utf8'));

  const encrypted = Buffer.concat([
    cipher.update(plaintext, 'utf8'),
    cipher.final(),
  ]);

  const authTag = cipher.getAuthTag();

  // Format: iv (12) || authTag (16) || ciphertext (variable)
  const result = Buffer.concat([iv, authTag, encrypted]);
  return result.toString('base64');
}

/**
 * Decrypt a ciphertext value.
 *
 * @param ciphertext - Base64-encoded ciphertext from encrypt()
 * @param aad - Additional authenticated data (must match what was used during encryption)
 * @returns Decrypted plaintext
 * @throws Error if decryption fails (wrong key, tampered data, wrong AAD)
 */
export function decrypt(ciphertext: string, aad: string): string {
  const key = getMasterKey();
  const data = Buffer.from(ciphertext, 'base64');

  if (data.length < IV_LENGTH + AUTH_TAG_LENGTH) {
    throw new Error('Invalid ciphertext: too short');
  }

  const iv = data.subarray(0, IV_LENGTH);
  const authTag = data.subarray(IV_LENGTH, IV_LENGTH + AUTH_TAG_LENGTH);
  const encrypted = data.subarray(IV_LENGTH + AUTH_TAG_LENGTH);

  const decipher = createDecipheriv(ALGORITHM, key, iv, {
    authTagLength: AUTH_TAG_LENGTH,
  });
  decipher.setAAD(Buffer.from(aad, 'utf8'));
  decipher.setAuthTag(authTag);

  const decrypted = Buffer.concat([
    decipher.update(encrypted),
    decipher.final(),
  ]);

  return decrypted.toString('utf8');
}
