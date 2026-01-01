import { createSignal, onMount } from 'solid-js';
import { captureEncodedFingerprint } from '../lib/client-fingerprint';

/**
 * Magic Link Verification Interceptor
 *
 * This page intercepts magic link clicks to capture client-side fingerprint
 * before redirecting to the verification endpoint.
 *
 * Flow:
 * 1. User clicks magic link (redirects here with token)
 * 2. Capture device fingerprint client-side
 * 3. Make GET request to verification endpoint with fingerprint header
 * 4. Follow redirects to callback URL on success
 */
export function VerifyMagicLink() {
  const [error, setError] = createSignal<string | null>(null);
  const [status, setStatus] = createSignal('Verifying your magic link...');

  onMount(async () => {
    // Extract search params directly from URL
    const urlParams = new URLSearchParams(window.location.search);
    const token = urlParams.get('token');
    const callbackURL = urlParams.get('callbackURL') || '/';

    if (!token) {
      setError('Invalid or missing token');
      setTimeout(() => {
        window.location.href = '/?error=invalid_token';
      }, 2000);
      return;
    }

    try {
      setStatus('Capturing device fingerprint...');
      
      // Capture client-side fingerprint
      const fingerprint = captureEncodedFingerprint();

      setStatus('Verifying with server...');
      
      // Call better-auth's magic link verify endpoint with fingerprint header
      const verifyUrl = new URL('/api/auth/magic-link/verify', window.location.origin);
      verifyUrl.searchParams.set('token', token);
      verifyUrl.searchParams.set('callbackURL', callbackURL);

      // Use manual redirect handling to avoid CORS issues with cross-origin redirects
      const response = await fetch(verifyUrl.toString(), {
        method: 'GET',
        headers: {
          'X-Device-Fingerprint': fingerprint,
        },
        credentials: 'include',
        redirect: 'manual',
      });

      // 3xx redirect = success, better-auth is redirecting us to callbackURL
      if (response.type === 'opaqueredirect' || (response.status >= 300 && response.status < 400)) {
        // Success - redirect handled by server, go to callback
        window.location.href = callbackURL;
      } else if (response.ok) {
        // 2xx success without redirect - go to callback
        window.location.href = callbackURL;
      } else {
        // Error occurred - parse response for succinct message + requestId
        const data = await response.json().catch(() => ({ error: 'Unknown error' }));
        const ref = data.requestId ? ` (ref: ${data.requestId})` : '';
        
        if (data.error?.toLowerCase().includes('device')) {
          setError('Device verification failed. Please request a new magic link from the same device.' + ref);
        } else {
          setError((data.error || 'Verification failed') + ref);
        }
        
        setTimeout(() => {
          window.location.href = '/?error=verification_failed';
        }, 3000);
      }
    } catch (err) {
      setError('An unexpected error occurred');
      setTimeout(() => {
        window.location.href = '/?error=verification_failed';
      }, 2000);
    }
  });

  return (
    <div class="min-h-screen flex items-center justify-center bg-[var(--bg-primary)]">
      <div class="text-center space-y-4 p-8">
        {error() ? (
          <>
            <div class="w-16 h-16 mx-auto rounded-full bg-red-500/10 flex items-center justify-center">
              <svg class="w-8 h-8 text-red-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </div>
            <div class="text-red-400 font-semibold text-lg">{error()}</div>
            <p class="text-sm text-[var(--text-secondary)]">Redirecting...</p>
          </>
        ) : (
          <>
            <div class="w-16 h-16 mx-auto">
              <svg class="animate-spin w-full h-full text-[var(--accent)]" viewBox="0 0 24 24">
                <circle 
                  class="opacity-25" 
                  cx="12" 
                  cy="12" 
                  r="10" 
                  stroke="currentColor" 
                  stroke-width="4" 
                  fill="none" 
                />
                <path 
                  class="opacity-75" 
                  fill="currentColor" 
                  d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" 
                />
              </svg>
            </div>
            <p class="text-[var(--text-secondary)] text-lg">{status()}</p>
            <p class="text-xs text-[var(--text-secondary)] opacity-75">
              Securely verifying your device
            </p>
          </>
        )}
      </div>
    </div>
  );
}
