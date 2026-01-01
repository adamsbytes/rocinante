/**
 * Shared HMAC token utilities for Unix socket authentication.
 * Used by both the Docker worker and web server.
 */

import { createHmac, timingSafeEqual } from 'crypto';

const DEFAULT_MAX_SKEW_MS = 60_000; // 60 seconds

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
  const sig = createHmac('sha256', secret).update(payload).digest('hex');
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
  const expectedSig = createHmac('sha256', secret).update(payload).digest('hex');

  // Convert to buffers for timingSafeEqual
  const sigBuffer = Buffer.from(sig, 'hex');
  const expectedSigBuffer = Buffer.from(expectedSig, 'hex');

  // Ensure same length (should be, but guard against malformed input)
  if (sigBuffer.length !== expectedSigBuffer.length) {
    return false;
  }

  return timingSafeEqual(sigBuffer, expectedSigBuffer);
}
