/**
 * Device Fingerprinting for Magic Link Security
 *
 * Captures and validates device characteristics to prevent magic link phishing.
 * Uses a fuzzy matching algorithm to account for legitimate variations (IP changes, etc.)
 *
 * SECURITY: Fingerprints are HMAC-signed to prevent tampering.
 *
 * Uses Bun.CryptoHasher for hash/HMAC (native, ~15-34% faster than Node crypto).
 */

import { timingSafeEqual } from 'crypto';
import { getClientIp } from './ip-helpers';

/**
 * Compute SHA-256 hash using Bun's native CryptoHasher.
 */
function sha256(data: string): string {
  return new Bun.CryptoHasher('sha256').update(data).digest('hex');
}

/**
 * Compute HMAC-SHA256 using Bun's native CryptoHasher.
 */
function hmacSha256(key: string, data: string): string {
  return new Bun.CryptoHasher('sha256', key).update(data).digest('hex');
}

export interface DeviceFingerprint {
  // Server-extracted headers
  ip: string | null;
  userAgent: string | null;
  acceptLanguage: string | null;
  acceptEncoding: string | null;
  accept: string | null;
  secChUa: string | null;
  secChUaMobile: string | null;
  secChUaPlatform: string | null;

  // Client-provided data
  viewportCategory: 'mobile' | 'tablet' | 'desktop' | 'unknown';
  devicePixelRatio: number | null;
  screenWidth: number | null;
  screenHeight: number | null;
  connectionType: string | null;
  timezone: string | null;
  timezoneOffset: number | null;
  hardwareConcurrency: number | null;
  deviceMemory: number | null;
  doNotTrack: string | null;
  cookieEnabled: boolean | null;

  // Computed
  hash: string;
  timestamp: number;
  signature: string;
}

interface ClientFingerprintData {
  viewportWidth?: number;
  viewportHeight?: number;
  devicePixelRatio?: number;
  connectionType?: string;
  timezone?: string;
  timezoneOffset?: number;
  hardwareConcurrency?: number;
  deviceMemory?: number;
  doNotTrack?: string;
  cookieEnabled?: boolean;
}

/**
 * Normalize IP address for comparison
 * Keeps first 3 octets for IPv4 (same /24 subnet)
 */
function normalizeIp(ip: string | null): string {
  if (!ip) return 'unknown';

  // Remove port if present
  const cleanIp = ip.split(':')[0] || ip;

  // For IPv4, keep first 3 octets (allow same subnet)
  if (cleanIp.includes('.')) {
    const parts = cleanIp.split('.');
    if (parts.length === 4) {
      return `${parts[0]}.${parts[1]}.${parts[2]}.*`;
    }
  }

  // For IPv6, keep first 4 groups
  if (cleanIp.includes(':')) {
    const parts = cleanIp.split(':');
    if (parts.length >= 4) {
      return `${parts[0]}:${parts[1]}:${parts[2]}:${parts[3]}:*`;
    }
  }

  return cleanIp;
}

/**
 * Normalize user agent - extract browser and OS only
 */
function normalizeUserAgent(ua: string | null): string {
  if (!ua) return 'unknown';

  const browsers = ['Chrome', 'Firefox', 'Safari', 'Edge', 'Opera'];
  const browser = browsers.find((b) => ua.includes(b)) || 'unknown';

  const os = ua.includes('Windows')
    ? 'Windows'
    : ua.includes('Mac')
      ? 'Mac'
      : ua.includes('Linux')
        ? 'Linux'
        : ua.includes('Android')
          ? 'Android'
          : ua.includes('iOS')
            ? 'iOS'
            : 'unknown';

  return `${browser}/${os}`;
}

/**
 * Categorize viewport into device class
 */
function categorizeViewport(
  width: number | null,
  height: number | null
): 'mobile' | 'tablet' | 'desktop' | 'unknown' {
  if (!width || !height) return 'unknown';

  const minDimension = Math.min(width, height);

  if (minDimension < 768) return 'mobile';
  if (minDimension <= 1024) return 'tablet';
  return 'desktop';
}

/**
 * Generate HMAC signature for fingerprint (prevents tampering)
 */
function generateSignature(
  fingerprint: Omit<DeviceFingerprint, 'signature'>,
  secret: string
): string {
  // Pipe-delimited for deterministic ordering
  const data = [
    fingerprint.ip || '',
    fingerprint.userAgent || '',
    fingerprint.acceptLanguage || '',
    fingerprint.acceptEncoding || '',
    fingerprint.accept || '',
    fingerprint.secChUa || '',
    fingerprint.secChUaMobile || '',
    fingerprint.secChUaPlatform || '',
    fingerprint.viewportCategory || '',
    fingerprint.devicePixelRatio?.toString() || '',
    fingerprint.screenWidth?.toString() || '',
    fingerprint.screenHeight?.toString() || '',
    fingerprint.connectionType || '',
    fingerprint.timezone || '',
    fingerprint.timezoneOffset?.toString() || '',
    fingerprint.hardwareConcurrency?.toString() || '',
    fingerprint.deviceMemory?.toString() || '',
    fingerprint.doNotTrack || '',
    fingerprint.cookieEnabled?.toString() || '',
    fingerprint.hash || '',
    fingerprint.timestamp.toString(),
  ].join('|');

  return hmacSha256(secret, data);
}

/**
 * Verify HMAC signature (constant-time comparison)
 */
function verifySignature(fingerprint: DeviceFingerprint, secret: string): boolean {
  const expected = generateSignature(
    {
      ip: fingerprint.ip,
      userAgent: fingerprint.userAgent,
      acceptLanguage: fingerprint.acceptLanguage,
      acceptEncoding: fingerprint.acceptEncoding,
      accept: fingerprint.accept,
      secChUa: fingerprint.secChUa,
      secChUaMobile: fingerprint.secChUaMobile,
      secChUaPlatform: fingerprint.secChUaPlatform,
      viewportCategory: fingerprint.viewportCategory,
      devicePixelRatio: fingerprint.devicePixelRatio,
      screenWidth: fingerprint.screenWidth,
      screenHeight: fingerprint.screenHeight,
      connectionType: fingerprint.connectionType,
      timezone: fingerprint.timezone,
      timezoneOffset: fingerprint.timezoneOffset,
      hardwareConcurrency: fingerprint.hardwareConcurrency,
      deviceMemory: fingerprint.deviceMemory,
      doNotTrack: fingerprint.doNotTrack,
      cookieEnabled: fingerprint.cookieEnabled,
      hash: fingerprint.hash,
      timestamp: fingerprint.timestamp,
    },
    secret
  );

  try {
    return timingSafeEqual(
      Buffer.from(expected, 'hex'),
      Buffer.from(fingerprint.signature, 'hex')
    );
  } catch {
    return false;
  }
}

// Re-export getClientIp for external use
export { getClientIp };

/**
 * Generate device fingerprint from request + client data
 */
export function generateDeviceFingerprint(
  request: Request,
  secret: string,
  clientData?: ClientFingerprintData | null
): DeviceFingerprint {
  const ip = getClientIp(request);
  const userAgent = request.headers.get('user-agent');
  const acceptLanguage = request.headers.get('accept-language');
  const acceptEncoding = request.headers.get('accept-encoding');
  const accept = request.headers.get('accept');

  // Client Hints
  const secChUa = request.headers.get('sec-ch-ua');
  const secChUaMobile = request.headers.get('sec-ch-ua-mobile');
  const secChUaPlatform = request.headers.get('sec-ch-ua-platform');

  // Client-side data
  const viewportCategory = categorizeViewport(
    clientData?.viewportWidth || null,
    clientData?.viewportHeight || null
  );
  const devicePixelRatio = clientData?.devicePixelRatio || null;
  const screenWidth = clientData?.viewportWidth || null;
  const screenHeight = clientData?.viewportHeight || null;
  const connectionType = clientData?.connectionType || null;
  const timezone = clientData?.timezone || null;
  const timezoneOffset = clientData?.timezoneOffset ?? null;
  const hardwareConcurrency = clientData?.hardwareConcurrency || null;
  const deviceMemory = clientData?.deviceMemory || null;
  const doNotTrack = clientData?.doNotTrack || null;
  const cookieEnabled = clientData?.cookieEnabled ?? null;

  // Create normalized hash
  const normalizedIp = normalizeIp(ip);
  const normalizedUa = normalizeUserAgent(userAgent);
  const normalizedLang = acceptLanguage?.split(',')[0]?.trim() || 'unknown';
  const normalizedEncoding = acceptEncoding?.split(',').sort().join(',') || 'unknown';
  const normalizedPlatform = secChUaPlatform?.replace(/"/g, '') || 'unknown';

  const hash = sha256(
    `${normalizedIp}:${normalizedUa}:${normalizedLang}:${normalizedEncoding}:${normalizedPlatform}:` +
      `${viewportCategory}:${devicePixelRatio}:${connectionType}:${timezone}:` +
      `${hardwareConcurrency}:${deviceMemory}:${doNotTrack}:${cookieEnabled}`
  );

  const timestamp = Date.now();

  const unsigned = {
    ip,
    userAgent,
    acceptLanguage,
    acceptEncoding,
    accept,
    secChUa,
    secChUaMobile,
    secChUaPlatform,
    viewportCategory,
    devicePixelRatio,
    screenWidth,
    screenHeight,
    connectionType,
    timezone,
    timezoneOffset,
    hardwareConcurrency,
    deviceMemory,
    doNotTrack,
    cookieEnabled,
    hash,
    timestamp,
  };

  const signature = generateSignature(unsigned, secret);

  return { ...unsigned, signature };
}

/**
 * Calculate match score between two fingerprints (0-1)
 *
 * Scoring priorities:
 * - HIGH (50%): Hardware concurrency, device memory, platform, DPR
 * - MEDIUM (30%): Viewport category, accept-encoding
 * - LOW (20%): Timezone, browser features, language
 * - BONUS: IP match, connection type (not penalized if different)
 */
export function calculateMatchScore(
  stored: DeviceFingerprint,
  current: DeviceFingerprint
): number {
  let score = 0;
  let maxScore = 0;

  // HIGH: Hardware Concurrency (15%)
  maxScore += 15;
  if (stored.hardwareConcurrency && current.hardwareConcurrency) {
    if (stored.hardwareConcurrency === current.hardwareConcurrency) {
      score += 15;
    }
  }

  // HIGH: Device Memory (10%)
  maxScore += 10;
  if (stored.deviceMemory && current.deviceMemory) {
    if (stored.deviceMemory === current.deviceMemory) {
      score += 10;
    }
  }

  // HIGH: Platform/OS (15%)
  maxScore += 15;
  if (stored.secChUaPlatform && current.secChUaPlatform) {
    const storedPlatform = stored.secChUaPlatform.replace(/"/g, '').toLowerCase();
    const currentPlatform = current.secChUaPlatform.replace(/"/g, '').toLowerCase();
    if (storedPlatform === currentPlatform) {
      score += 15;
    }
  } else if (stored.userAgent && current.userAgent) {
    // Fallback to user agent
    const storedOS = normalizeUserAgent(stored.userAgent).split('/')[1];
    const currentOS = normalizeUserAgent(current.userAgent).split('/')[1];
    if (storedOS === currentOS) {
      score += 12;
    }
  }

  // HIGH: Device Pixel Ratio (10%)
  maxScore += 10;
  if (stored.devicePixelRatio && current.devicePixelRatio) {
    if (stored.devicePixelRatio === current.devicePixelRatio) {
      score += 10;
    } else if (Math.abs(stored.devicePixelRatio - current.devicePixelRatio) <= 0.25) {
      score += 7;
    }
  }

  // MEDIUM: Viewport Category (10%)
  maxScore += 10;
  if (stored.viewportCategory !== 'unknown' && current.viewportCategory !== 'unknown') {
    if (stored.viewportCategory === current.viewportCategory) {
      score += 10;
    } else {
      // Tablet-Desktop overlap acceptable
      const acceptable =
        (stored.viewportCategory === 'tablet' && current.viewportCategory === 'desktop') ||
        (stored.viewportCategory === 'desktop' && current.viewportCategory === 'tablet');
      if (acceptable) {
        score += 7;
      }
    }
  }

  // MEDIUM: Accept-Encoding (10%)
  maxScore += 10;
  if (stored.acceptEncoding && current.acceptEncoding) {
    const storedEnc = stored.acceptEncoding.split(',').map((s) => s.trim()).sort().join(',');
    const currentEnc = current.acceptEncoding.split(',').map((s) => s.trim()).sort().join(',');
    if (storedEnc === currentEnc) {
      score += 10;
    } else {
      // Partial match: both support brotli
      const storedHasBr = storedEnc.includes('br');
      const currentHasBr = currentEnc.includes('br');
      if (storedHasBr === currentHasBr) {
        score += 7;
      }
    }
  }

  // LOW: Timezone (10%)
  maxScore += 10;
  if (stored.timezone && current.timezone) {
    if (stored.timezone === current.timezone) {
      score += 10;
    } else if (stored.timezoneOffset === current.timezoneOffset) {
      score += 7;
    }
  }

  // LOW: Browser Security Features (5%)
  maxScore += 5;
  let securityMatches = 0;
  let securityTotal = 0;

  if (stored.doNotTrack !== null && current.doNotTrack !== null) {
    securityTotal++;
    if (stored.doNotTrack === current.doNotTrack) securityMatches++;
  }

  if (stored.cookieEnabled !== null && current.cookieEnabled !== null) {
    securityTotal++;
    if (stored.cookieEnabled === current.cookieEnabled) securityMatches++;
  }

  if (securityTotal > 0) {
    score += (securityMatches / securityTotal) * 5;
  }

  // LOW: Language (5%)
  maxScore += 5;
  if (stored.acceptLanguage && current.acceptLanguage) {
    const storedLang = stored.acceptLanguage.split(',')[0]?.trim();
    const currentLang = current.acceptLanguage.split(',')[0]?.trim();
    if (storedLang === currentLang) {
      score += 5;
    } else if (storedLang?.split('-')[0] === currentLang?.split('-')[0]) {
      score += 3;
    }
  }

  // BONUS: Connection Type (not penalized)
  if (
    stored.connectionType &&
    current.connectionType &&
    stored.connectionType === current.connectionType
  ) {
    score += 2;
    maxScore += 2;
  }

  // BONUS: IP Address (not penalized)
  if (stored.ip && current.ip) {
    const storedNorm = normalizeIp(stored.ip);
    const currentNorm = normalizeIp(current.ip);
    if (storedNorm === currentNorm) {
      score += 3;
      maxScore += 3;
    }
  }

  return maxScore > 0 ? score / maxScore : 0;
}

/**
 * Validate device fingerprint
 *
 * 1. Verify HMAC signature (detect tampering)
 * 2. Check match score (detect different device)
 */
export function validateDeviceFingerprint(
  stored: DeviceFingerprint,
  current: DeviceFingerprint,
  secret: string
): { valid: boolean; matchScore: number; reason?: string } {
  const MATCH_THRESHOLD = 0.7;

  // Verify signatures
  const storedValid = verifySignature(stored, secret);
  const currentValid = verifySignature(current, secret);

  if (!storedValid || !currentValid) {
    console.error('SECURITY: Device fingerprint signature verification failed - TAMPERING DETECTED');
    return {
      valid: false,
      matchScore: 0,
      reason: 'Fingerprint tampering detected',
    };
  }

  // Calculate match score
  const matchScore = calculateMatchScore(stored, current);
  const isValid = matchScore >= MATCH_THRESHOLD;

  if (!isValid) {
    console.warn(`Device validation failed: score=${matchScore.toFixed(2)}, threshold=${MATCH_THRESHOLD}`);
    return {
      valid: false,
      matchScore,
      reason: 'Device characteristics do not match',
    };
  }

  return { valid: true, matchScore };
}

/**
 * Serialize fingerprint for storage
 */
export function serializeFingerprint(fingerprint: DeviceFingerprint): string {
  return JSON.stringify(fingerprint);
}

/**
 * Deserialize fingerprint from storage
 */
export function deserializeFingerprint(data: string): DeviceFingerprint {
  return JSON.parse(data);
}

/**
 * Decode client fingerprint from X-Device-Fingerprint header
 */
export function decodeClientFingerprint(header: string | null): ClientFingerprintData | null {
  if (!header) return null;
  try {
    return JSON.parse(atob(header));
  } catch {
    return null;
  }
}
