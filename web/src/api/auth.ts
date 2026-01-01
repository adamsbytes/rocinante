import { betterAuth } from 'better-auth';
import { magicLink } from 'better-auth/plugins';
import type { BetterAuthPlugin } from 'better-auth';
import { createAuthMiddleware } from 'better-auth/api';
import { Database } from 'bun:sqlite';
import { join } from 'path';
import { mkdirSync, existsSync } from 'fs';
import {
  generateDeviceFingerprint,
  validateDeviceFingerprint,
  serializeFingerprint,
  deserializeFingerprint,
  decodeClientFingerprint,
} from './device-fingerprint';

const DATA_DIR = process.env.DATA_DIR || './data';
const AUTH_DB_PATH = join(DATA_DIR, 'auth.db');
const AUTH_SECRET = process.env.AUTH_SECRET!;

const isDev = process.env.NODE_ENV !== 'production';

// Ensure data directory exists
if (!existsSync(DATA_DIR)) {
  mkdirSync(DATA_DIR, { recursive: true });
}

// Initialize SQLite database for auth using Bun's native driver
const db = new Database(AUTH_DB_PATH, { create: true });

// =============================================================================
// Device Fingerprint Storage (in-memory with TTL)
// Tokens only last 15 minutes, so simple Map is sufficient
// =============================================================================

interface StoredFingerprint {
  data: string;
  email: string;
  expiresAt: number;
}

const fingerprintStore = new Map<string, StoredFingerprint>();

// Cleanup expired entries every 5 minutes
setInterval(() => {
  const now = Date.now();
  for (const [token, entry] of fingerprintStore.entries()) {
    if (entry.expiresAt < now) {
      fingerprintStore.delete(token);
    }
  }
}, 5 * 60 * 1000);

function storeFingerprint(token: string, fingerprint: string, email: string): void {
  fingerprintStore.set(token, {
    data: fingerprint,
    email,
    expiresAt: Date.now() + 15 * 60 * 1000, // 15 minutes
  });
}

function getStoredFingerprint(token: string): StoredFingerprint | undefined {
  const entry = fingerprintStore.get(token);
  if (entry && entry.expiresAt > Date.now()) {
    return entry;
  }
  fingerprintStore.delete(token);
  return undefined;
}

function deleteStoredFingerprint(token: string): void {
  fingerprintStore.delete(token);
}

// =============================================================================
// Plugin: Extract client fingerprint from magic link request header
// =============================================================================

const extractClientFingerprint = (): BetterAuthPlugin => {
  return {
    id: 'extract-client-fingerprint',
    hooks: {
      before: [
        {
          matcher: (context) => context.path === '/sign-in/magic-link' && context.method === 'POST',
          handler: createAuthMiddleware(async (ctx) => {
            if (!ctx.request) {
              return { context: ctx };
            }

            // Extract client fingerprint from custom header
            const clientFingerprintHeader = ctx.request.headers.get('x-device-fingerprint');
            
            if (clientFingerprintHeader) {
              const clientData = decodeClientFingerprint(clientFingerprintHeader);
              if (clientData) {
                // Store decoded fingerprint in request for sendMagicLink to access
                (ctx.request as any).clientFingerprint = clientData;
                console.log('Client fingerprint extracted from magic link request');
              } else {
                console.warn('Failed to decode client fingerprint header');
              }
            } else {
              console.warn('No client fingerprint in magic link request headers');
            }

            return { context: ctx };
          }),
        },
      ],
    },
  };
};

// =============================================================================
// Plugin: Validate device fingerprint when magic link is verified
// =============================================================================

const magicLinkDeviceValidation = (): BetterAuthPlugin => {
  return {
    id: 'magic-link-device-validation',
    hooks: {
      before: [
        {
          matcher: (context) => context.path === '/magic-link/verify',
          handler: createAuthMiddleware(async (ctx) => {
            if (!ctx.request) {
              return { context: ctx };
            }

            const url = new URL(ctx.request.url);
            const token = url.searchParams.get('token');

            if (!token) {
              return { context: ctx };
            }

            // Get stored fingerprint
            const stored = getStoredFingerprint(token);
            if (!stored) {
              console.warn(`No device fingerprint found for magic link token: ${token.substring(0, 10)}...`);
              throw new Error('Invalid or expired magic link. Please request a new one.');
            }

            // Extract current client fingerprint from header
            const clientFingerprintHeader = ctx.request.headers.get('x-device-fingerprint');
            const clientData = decodeClientFingerprint(clientFingerprintHeader);

            // Generate current fingerprint
            const currentFingerprint = generateDeviceFingerprint(
              ctx.request,
              AUTH_SECRET,
              clientData
            );

            // Validate against stored fingerprint
            const storedFingerprint = deserializeFingerprint(stored.data);
            const validation = validateDeviceFingerprint(
              storedFingerprint,
              currentFingerprint,
              AUTH_SECRET
            );

            if (!validation.valid) {
              console.error(`SECURITY: Device mismatch for ${stored.email} - ${validation.reason}`);
              console.error(`Match score: ${validation.matchScore.toFixed(2)}`);
              
              // Clean up the fingerprint
              deleteStoredFingerprint(token);

              // Block the verification
              throw new Error('Device verification failed. Please request a new magic link from the same device.');
            }

            console.log(`Device validation passed for ${stored.email} (score: ${validation.matchScore.toFixed(2)})`);
            
            // Clean up after successful validation
            deleteStoredFingerprint(token);

            return { context: ctx };
          }),
        },
      ],
    },
  };
};

// =============================================================================
// Auth Configuration
// =============================================================================

export const auth = betterAuth({
  // bun:sqlite is API-compatible with better-sqlite3, just different types
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  database: db as any,
  
  // Base URL for generating magic link URLs
  baseURL: process.env.BASE_URL || 'http://localhost:3000',
  
  // Trust the Vite dev server origin
  trustedOrigins: [
    process.env.CLIENT_URL || 'http://localhost:5173',
    process.env.BASE_URL || 'http://localhost:3000',
  ],
  
  plugins: [
    // Extract client fingerprint from magic link request body
    extractClientFingerprint(),
    
    // Validate device fingerprint when magic link is verified
    magicLinkDeviceValidation(),
    
    magicLink({
      sendMagicLink: async ({ email, url, token }, ctx) => {
        // Modify URL to point to our verification interceptor page
        const originalUrl = new URL(url);
        const clientUrl = process.env.CLIENT_URL || 'http://localhost:5173';
        const verifyUrl = new URL('/auth/verify', clientUrl);
        verifyUrl.searchParams.set('token', token);
        verifyUrl.searchParams.set(
          'callbackURL',
          originalUrl.searchParams.get('callbackURL') || '/'
        );

        // Capture device fingerprint from ctx.request
        const request = ctx?.request;
        if (request) {
          const clientData = (request as any).clientFingerprint || null;
          
          if (!clientData) {
            console.warn(`Magic link for ${email} missing client fingerprint - device validation will be skipped`);
          }

          const fingerprint = generateDeviceFingerprint(request, AUTH_SECRET, clientData);
          storeFingerprint(token, serializeFingerprint(fingerprint), email);
          
          console.log(`Device fingerprint captured for ${email}:`, {
            ip: fingerprint.ip,
            viewport: fingerprint.viewportCategory,
            dpr: fingerprint.devicePixelRatio,
            hardware: fingerprint.hardwareConcurrency,
            timezone: fingerprint.timezone,
          });
        } else {
          console.warn(`Magic link for ${email} has no request context - device validation disabled`);
        }

        if (isDev) {
          // Dev: Log to terminal - user clicks from there
          console.log(`\n🔗 Magic link for ${email}:\n${verifyUrl.toString()}\n`);
          return;
        }
        
        // Prod: Send actual email
        // TODO: Implement email sending (e.g., Resend, SendGrid, etc.)
        console.error('Email sending not configured for production');
        throw new Error('Email sending not configured');
      },
      
      // Token expires in 15 minutes
      expiresIn: 60 * 15,
      // Do not auto-create accounts via magic links
      disableSignUp: true,
    }),
  ],
  
  session: {
    // Session expires in 7 days
    expiresIn: 60 * 60 * 24 * 7,
    // Refresh session if accessed within 1 day of expiry
    updateAge: 60 * 60 * 24,
  },
  
  advanced: {
    // Secure cookies in production (HTTPS)
    useSecureCookies: !isDev,
    // Default cookie attributes for security
    defaultCookieAttributes: {
      httpOnly: true,
      secure: !isDev,
      sameSite: 'lax',
    },
  },
});

// Type for session result
export type Session = Awaited<ReturnType<typeof auth.api.getSession>>;

/**
 * Get session from request headers.
 * Returns null if not authenticated.
 */
export async function getSession(req: Request) {
  return auth.api.getSession({ headers: req.headers });
}

/**
 * Require authenticated session, returns user ID.
 * Throws if not authenticated.
 */
export async function requireAuth(req: Request): Promise<string> {
  const session = await getSession(req);
  if (!session?.user?.id) {
    throw new Error('Unauthorized');
  }
  return session.user.id;
}
