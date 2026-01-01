/**
 * Shared HMAC token utilities for Unix socket authentication.
 * Used by both the Docker worker and web server.
 *
 * Uses Bun.CryptoHasher for HMAC (native, ~34% faster than Node crypto).
 */

import { timingSafeEqual } from 'crypto';

const DEFAULT_MAX_SKEW_MS = 60_000; // 60 seconds

/**
 * Compute HMAC-SHA256 using Bun's native CryptoHasher.
 */
function hmacSha256(key: string, data: string): string {
  return new Bun.CryptoHasher('sha256', key).update(data).digest('hex');
}

/**
 * Create an HMAC-signed token for socket authentication.
 * Format: `timestamp.clientId.signature`
 *
 * @param clientId - Identifier for the connecting client (e.g., 'webserver')
 * @param secret - Shared secret (from WORKER_SHARED_SECRET env var)
 * @returns Signed token string
 */
export function makeToken(clientId: string, secret: string): string {
  const ts = Date.now();
  const payload = `${clientId}.${ts}`;
  const sig = hmacSha256(secret, payload);
  return `${ts}.${clientId}.${sig}`;
}

/**
 * Verify an HMAC-signed token.
 *
 * @param token - Token to verify (format: `timestamp.clientId.signature`)
 * @param expectedClientId - Expected client identifier
 * @param secret - Shared secret (from WORKER_SHARED_SECRET env var)
 * @param maxSkewMs - Maximum allowed time skew in milliseconds (default: 60s)
 * @returns true if token is valid and not expired
 */
export function verifyToken(
  token: string,
  expectedClientId: string,
  secret: string,
  maxSkewMs: number = DEFAULT_MAX_SKEW_MS
): boolean {
  const parts = token.split('.');
  if (parts.length !== 3) {
    return false;
  }

  const [tsStr, clientId, sig] = parts;

  // Check client ID matches
  if (clientId !== expectedClientId) {
    return false;
  }

  // Check timestamp is valid and within skew window
  const ts = Number(tsStr);
  if (!Number.isFinite(ts)) {
    return false;
  }
  const now = Date.now();
  if (Math.abs(now - ts) > maxSkewMs) {
    return false;
  }

  // Verify signature using timing-safe comparison
  const payload = `${clientId}.${ts}`;
  const expectedSig = hmacSha256(secret, payload);

  // Convert to buffers for timingSafeEqual (no Bun native equivalent)
  const sigBuffer = Buffer.from(sig, 'hex');
  const expectedSigBuffer = Buffer.from(expectedSig, 'hex');

  // Ensure same length (should be, but guard against malformed input)
  if (sigBuffer.length !== expectedSigBuffer.length) {
    return false;
  }

  return timingSafeEqual(sigBuffer, expectedSigBuffer);
}
