import { betterAuth } from 'better-auth';
import { magicLink } from 'better-auth/plugins';
import { Database } from 'bun:sqlite';
import { join } from 'path';
import { mkdirSync, existsSync } from 'fs';

const DATA_DIR = process.env.DATA_DIR || './data';
const AUTH_DB_PATH = join(DATA_DIR, 'auth.db');

const isDev = process.env.NODE_ENV !== 'production';

// Ensure data directory exists
if (!existsSync(DATA_DIR)) {
  mkdirSync(DATA_DIR, { recursive: true });
}

// Initialize SQLite database for auth using Bun's native driver
// bun:sqlite API is compatible with better-sqlite3 (which better-auth expects)
const db = new Database(AUTH_DB_PATH, { create: true });

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
    magicLink({
      sendMagicLink: async ({ email, url }) => {
        if (isDev) {
          // Dev: Log to terminal - user clicks from there
          console.log(`\n🔗 Magic link for ${email}:\n${url}\n`);
          return;
        }
        
        // Prod: Send actual email
        // TODO: Implement email sending (e.g., Resend, SendGrid, etc.)
        console.error('Email sending not configured for production');
        throw new Error('Email sending not configured');
      },
      
      // Token expires in 15 minutes
      expiresIn: 60 * 15,
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
