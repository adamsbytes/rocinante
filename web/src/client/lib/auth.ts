import { createAuthClient } from 'better-auth/client';
import { magicLinkClient } from 'better-auth/client/plugins';
import { createSignal } from 'solid-js';
import { captureEncodedFingerprint } from './client-fingerprint';

// Create the auth client with magic link support
export const authClient = createAuthClient({
  baseURL: import.meta.env.VITE_API_URL || '',
  plugins: [magicLinkClient()],
});

// Wrap authClient.$fetch to automatically attach device fingerprint header
// This ensures all auth requests include device characteristics for validation
const originalFetch = authClient.$fetch;

(authClient.$fetch as any) = async (url: string, options?: any) => {
  try {
    const fingerprint = captureEncodedFingerprint();

    const headers = new Headers(options?.headers || {});
    headers.set('X-Device-Fingerprint', fingerprint);

    return await originalFetch(url, {
      ...(options || {}),
      headers,
    } as any);
  } catch (error) {
    // Fallback to original if fingerprint capture fails
    console.warn('Failed to capture fingerprint, proceeding without:', error);
    return originalFetch(url, options as any);
  }
};

// Reactive auth state
const [user, setUser] = createSignal<{ id: string; email: string } | null>(null);
const [loading, setLoading] = createSignal(true);

// Initialize: check for existing session
authClient.getSession().then(({ data }) => {
  setUser(data?.user ?? null);
  setLoading(false);
}).catch(() => {
  setUser(null);
  setLoading(false);
});

// Export reactive getters
export { user, loading };

export const isAuthenticated = () => !!user();

/**
 * Request a magic link to be sent to the email.
 * In dev, check server terminal for the link.
 * 
 * SECURITY: Captures device fingerprint and sends it with the request.
 * The server stores this fingerprint and validates it when the magic link is clicked.
 */
export async function requestMagicLink(email: string, callbackURL = '/') {
  // Capture device fingerprint for magic link security
  const fingerprint = captureEncodedFingerprint();
  
  const result = await authClient.signIn.magicLink(
    {
      email,
      callbackURL,
    },
    {
      headers: {
        'X-Device-Fingerprint': fingerprint,
      },
    }
  );
  return result;
}

/**
 * Sign out the current user.
 */
export async function signOut() {
  await authClient.signOut();
  setUser(null);
}

/**
 * Refresh session state from server.
 */
export async function refreshSession() {
  const { data } = await authClient.getSession();
  setUser(data?.user ?? null);
  return data?.user ?? null;
}
