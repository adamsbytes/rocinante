import { createAuthClient } from 'better-auth/client';
import { magicLinkClient } from 'better-auth/client/plugins';
import { createSignal, createEffect } from 'solid-js';

// Create the auth client with magic link support
export const authClient = createAuthClient({
  baseURL: import.meta.env.VITE_API_URL || '',
  plugins: [magicLinkClient()],
});

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
 */
export async function requestMagicLink(email: string, callbackURL = '/') {
  const result = await authClient.signIn.magicLink({
    email,
    callbackURL,
  });
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
