import { type Component, createSignal, Show } from 'solid-js';
import { z } from 'zod';
import { requestMagicLink } from '../lib/auth';

export const Login: Component = () => {
  const [email, setEmail] = createSignal('');
  const [status, setStatus] = createSignal<'idle' | 'sending' | 'sent' | 'error'>('idle');
  const [errorMsg, setErrorMsg] = createSignal('');
  const emailSchema = z.string().trim().email().max(256);

  const isEmailValid = () => emailSchema.safeParse(email().trim()).success;

  async function handleSubmit(e: Event) {
    e.preventDefault();
    if (!isEmailValid()) return;

    setStatus('sending');
    setErrorMsg('');

    const { error } = await requestMagicLink(email().trim());

    if (error) {
      // Avoid disclosing whether the account exists; log for debugging only
      console.warn('Magic link request error (suppressed):', error);
    }

    setStatus('sent');
  }

  return (
    <div class="min-h-screen flex items-center justify-center bg-[var(--bg-primary)] p-4">
      <div class="w-full max-w-md">
        {/* Logo/Title */}
        <div class="text-center mb-8">
          <div class="inline-flex items-center justify-center w-16 h-16 rounded-xl bg-[var(--accent)]/10 mb-4">
            <svg class="w-8 h-8 text-[var(--accent)]" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <h1 class="text-3xl font-bold text-[var(--text-primary)]">Rocinante</h1>
          <p class="text-[var(--text-secondary)] mt-2">Sign in to manage your bots</p>
        </div>

        {/* Card */}
        <div class="bg-[var(--bg-secondary)] rounded-xl border border-[var(--border)] p-8">
          <Show when={status() !== 'sent'}>
            <form onSubmit={handleSubmit} class="space-y-6">
              <div>
                <label for="email" class="block text-sm font-medium text-[var(--text-secondary)] mb-2">
                  Email address
                </label>
                <input
                  id="email"
                  type="email"
                  value={email()}
                  onInput={(e) => setEmail(e.currentTarget.value)}
                  placeholder="you@example.com"
                  required
                  autocomplete="email"
                  class="w-full px-4 py-3 bg-[var(--bg-tertiary)] border border-[var(--border)] rounded-lg text-[var(--text-primary)] placeholder-[var(--text-secondary)]/50 focus:outline-none focus:border-[var(--accent)] focus:ring-1 focus:ring-[var(--accent)] transition-colors"
                />
              </div>

              <button
                type="submit"
                disabled={status() === 'sending' || !isEmailValid()}
                class="w-full py-3 px-4 bg-[var(--accent)] hover:bg-[var(--accent-hover)] disabled:opacity-50 disabled:cursor-not-allowed text-white font-medium rounded-lg transition-colors"
              >
                {status() === 'sending' ? (
                  <span class="inline-flex items-center gap-2">
                    <svg class="animate-spin h-4 w-4" viewBox="0 0 24 24">
                      <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" fill="none" />
                      <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                    </svg>
                    Sending...
                  </span>
                ) : (
                  'Send Magic Link'
                )}
              </button>

              <Show when={status() === 'error'}>
                <p class="text-[var(--danger)] text-sm text-center">{errorMsg()}</p>
              </Show>
            </form>
          </Show>

          <Show when={status() === 'sent'}>
            <div class="text-center space-y-4">
              <div class="inline-flex items-center justify-center w-12 h-12 rounded-full bg-[var(--success)]/10 mb-2">
                <svg class="w-6 h-6 text-[var(--success)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                </svg>
              </div>
              
              <h2 class="text-xl font-semibold text-[var(--text-primary)]">Check your email</h2>
              
              <p class="text-[var(--text-secondary)]">
                If your email exists, a magic link was sent to <span class="text-[var(--text-primary)] font-medium">{email()}</span>
              </p>

              <p class="text-[var(--text-secondary)] text-sm">
                Click the link in the email to sign in.
              </p>

              <button
                onClick={() => {
                  setStatus('idle');
                  setEmail('');
                }}
                class="text-sm text-[var(--accent)] hover:text-[var(--accent-hover)] transition-colors"
              >
                Use a different email
              </button>
            </div>
          </Show>
        </div>

        {/* Footer hint */}
        <p class="text-center text-[var(--text-secondary)] text-xs mt-6">
          No password needed. We'll send you a secure link.
        </p>
      </div>
    </div>
  );
};
