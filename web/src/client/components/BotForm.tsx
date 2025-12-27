import { type Component, createSignal } from 'solid-js';
import type { BotConfig } from '../../shared/types';

interface BotFormProps {
  initialData?: BotConfig;
  onSubmit: (data: Omit<BotConfig, 'id'>) => void;
  isLoading?: boolean;
  submitLabel?: string;
}

export const BotForm: Component<BotFormProps> = (props) => {
  const [name, setName] = createSignal(props.initialData?.name ?? '');
  const [username, setUsername] = createSignal(props.initialData?.username ?? '');
  const [password, setPassword] = createSignal(props.initialData?.password ?? '');
  const [totpSecret, setTotpSecret] = createSignal(props.initialData?.totpSecret ?? '');
  const [characterName, setCharacterName] = createSignal(props.initialData?.characterName ?? '');
  const [preferredWorld, setPreferredWorld] = createSignal(props.initialData?.preferredWorld ?? 301);

  // Proxy
  const [proxyEnabled, setProxyEnabled] = createSignal(!!props.initialData?.proxy);
  const [proxyHost, setProxyHost] = createSignal(props.initialData?.proxy?.host ?? '');
  const [proxyPort, setProxyPort] = createSignal(props.initialData?.proxy?.port ?? 8080);
  const [proxyUser, setProxyUser] = createSignal(props.initialData?.proxy?.user ?? '');
  const [proxyPass, setProxyPass] = createSignal(props.initialData?.proxy?.pass ?? '');

  // Ironman - single dropdown for account type
  const getInitialAccountType = () => {
    if (!props.initialData?.ironman.enabled) return 'NORMAL';
    return props.initialData.ironman.type ?? 'NORMAL';
  };
  const [accountType, setAccountType] = createSignal<'NORMAL' | 'STANDARD_IRONMAN' | 'HARDCORE_IRONMAN' | 'ULTIMATE_IRONMAN'>(getInitialAccountType());
  const [hcimSafety, setHcimSafety] = createSignal(props.initialData?.ironman.hcimSafetyLevel ?? 'NORMAL');

  // Resources
  const [cpuLimit, setCpuLimit] = createSignal(props.initialData?.resources.cpuLimit ?? '1.0');
  const [memoryLimit, setMemoryLimit] = createSignal(props.initialData?.resources.memoryLimit ?? '2G');

  const handleSubmit = (e: Event) => {
    e.preventDefault();
    props.onSubmit({
      name: name(),
      username: username(),
      password: password(),
      totpSecret: totpSecret() || undefined,
      characterName: characterName() || undefined,
      preferredWorld: preferredWorld() || undefined,
      proxy: proxyEnabled()
        ? {
            host: proxyHost(),
            port: proxyPort(),
            user: proxyUser() || undefined,
            pass: proxyPass() || undefined,
          }
        : null,
      ironman: {
        enabled: accountType() !== 'NORMAL',
        type: accountType() !== 'NORMAL' ? accountType() : null,
        hcimSafetyLevel: accountType() === 'HARDCORE_IRONMAN' ? hcimSafety() : null,
      },
      resources: {
        cpuLimit: cpuLimit(),
        memoryLimit: memoryLimit(),
      },
    });
  };

  const inputClass = 'w-full px-3 py-2 bg-[var(--bg-primary)] border border-[var(--border)] rounded-lg focus:outline-none focus:border-[var(--accent)]';
  const labelClass = 'block text-sm font-medium text-[var(--text-secondary)] mb-1';

  return (
    <form onSubmit={handleSubmit} class="space-y-6 max-w-2xl">
      {/* Basic Info */}
      <section class="space-y-4">
        <h3 class="text-lg font-semibold border-b border-[var(--border)] pb-2">Account Details</h3>
        <div>
          <label class={labelClass}>Display Name</label>
          <input
            type="text"
            value={name()}
            onInput={(e) => setName(e.currentTarget.value)}
            class={inputClass}
            placeholder="Main Account"
            required
          />
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class={labelClass}>Jagex Account Email</label>
            <input
              type="email"
              value={username()}
              onInput={(e) => setUsername(e.currentTarget.value)}
              class={inputClass}
              placeholder="email@example.com"
              required
            />
          </div>
          <div>
            <label class={labelClass}>Password</label>
            <input
              type="password"
              value={password()}
              onInput={(e) => setPassword(e.currentTarget.value)}
              class={inputClass}
              required
            />
          </div>
        </div>
        <div>
          <label class={labelClass}>2FA TOTP Secret (optional)</label>
          <input
            type="password"
            value={totpSecret()}
            onInput={(e) => setTotpSecret(e.currentTarget.value)}
            class={inputClass}
            placeholder="Base32 secret from authenticator setup"
          />
          <p class="text-xs text-[var(--text-secondary)] mt-1">
            Required if your Jagex account has authenticator enabled. This is the secret key shown during 2FA setup (not the 6-digit code).
          </p>
        </div>
        <div>
          <label class={labelClass}>Character Name (optional)</label>
          <input
            type="text"
            value={characterName()}
            onInput={(e) => setCharacterName(e.currentTarget.value)}
            class={inputClass}
            placeholder="Desired name for new accounts"
            maxLength={12}
          />
          <p class="text-xs text-[var(--text-secondary)] mt-1">
            Only needed for new accounts that don't have a character yet. Max 12 characters.
          </p>
        </div>
        <div>
          <label class={labelClass}>Preferred World</label>
          <input
            type="number"
            value={preferredWorld()}
            onInput={(e) => setPreferredWorld(parseInt(e.currentTarget.value) || 301)}
            class={inputClass}
            placeholder="301"
            min={301}
            max={580}
          />
          <p class="text-xs text-[var(--text-secondary)] mt-1">
            Default world to log into. 301 is a safe F2P world. Members worlds are 302+.
          </p>
        </div>
      </section>

      {/* Proxy */}
      <section class="space-y-4">
        <div class="flex items-center gap-3 border-b border-[var(--border)] pb-2">
          <h3 class="text-lg font-semibold">Proxy Settings</h3>
          <label class="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={proxyEnabled()}
              onChange={(e) => setProxyEnabled(e.currentTarget.checked)}
              class="rounded"
            />
            Enable
          </label>
        </div>
        {proxyEnabled() && (
          <>
            <div class="grid grid-cols-3 gap-4">
              <div class="col-span-2">
                <label class={labelClass}>Host</label>
                <input
                  type="text"
                  value={proxyHost()}
                  onInput={(e) => setProxyHost(e.currentTarget.value)}
                  class={inputClass}
                  placeholder="proxy.example.com"
                  required={proxyEnabled()}
                />
              </div>
              <div>
                <label class={labelClass}>Port</label>
                <input
                  type="number"
                  value={proxyPort()}
                  onInput={(e) => setProxyPort(parseInt(e.currentTarget.value))}
                  class={inputClass}
                  required={proxyEnabled()}
                />
              </div>
            </div>
            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class={labelClass}>Username (optional)</label>
                <input
                  type="text"
                  value={proxyUser()}
                  onInput={(e) => setProxyUser(e.currentTarget.value)}
                  class={inputClass}
                />
              </div>
              <div>
                <label class={labelClass}>Password (optional)</label>
                <input
                  type="password"
                  value={proxyPass()}
                  onInput={(e) => setProxyPass(e.currentTarget.value)}
                  class={inputClass}
                />
              </div>
            </div>
          </>
        )}
      </section>

      {/* Account Type */}
      <section class="space-y-4">
        <h3 class="text-lg font-semibold border-b border-[var(--border)] pb-2">Account Type</h3>
        <div>
          <label class={labelClass}>Account Mode</label>
          <select
            value={accountType()}
            onChange={(e) => setAccountType(e.currentTarget.value as any)}
            class={inputClass}
          >
            <option value="NORMAL">Normal Account</option>
            <option value="STANDARD_IRONMAN">Standard Ironman</option>
            <option value="HARDCORE_IRONMAN">Hardcore Ironman</option>
            <option value="ULTIMATE_IRONMAN">Ultimate Ironman</option>
          </select>
          <p class="text-xs text-[var(--text-secondary)] mt-1">
            {accountType() === 'NORMAL' && 'Standard account with full trading and Grand Exchange access.'}
            {accountType() === 'STANDARD_IRONMAN' && 'Self-sufficient gameplay - no trading or GE. Can be downgraded to Normal.'}
            {accountType() === 'HARDCORE_IRONMAN' && 'Standard Ironman with permadeath. Extra safety protocols enabled. Can be downgraded to Standard Ironman or Normal.'}
            {accountType() === 'ULTIMATE_IRONMAN' && 'Standard Ironman without banking. Most challenging mode. Can be downgraded to Standard Ironman or Normal.'}
          </p>
        </div>
        {accountType() === 'HARDCORE_IRONMAN' && (
          <div>
            <label class={labelClass}>HCIM Safety Level</label>
            <select
              value={hcimSafety()}
              onChange={(e) => setHcimSafety(e.currentTarget.value as any)}
              class={inputClass}
            >
              <option value="NORMAL">Normal - Standard flee thresholds</option>
              <option value="CAUTIOUS">Cautious - Flee earlier, avoid more risks</option>
              <option value="PARANOID">Paranoid - Maximum safety, flee very early</option>
            </select>
            <p class="text-xs text-[var(--text-secondary)] mt-1">
              Safety level affects flee thresholds and risk tolerance for combat activities.
            </p>
          </div>
        )}
      </section>

      {/* Resources */}
      <section class="space-y-4">
        <h3 class="text-lg font-semibold border-b border-[var(--border)] pb-2">Resource Limits</h3>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class={labelClass}>CPU Limit</label>
            <input
              type="text"
              value={cpuLimit()}
              onInput={(e) => setCpuLimit(e.currentTarget.value)}
              class={inputClass}
              placeholder="1.0"
            />
            <p class="text-xs text-[var(--text-secondary)] mt-1">e.g., 0.5, 1.0, 2.0</p>
          </div>
          <div>
            <label class={labelClass}>Memory Limit</label>
            <input
              type="text"
              value={memoryLimit()}
              onInput={(e) => setMemoryLimit(e.currentTarget.value)}
              class={inputClass}
              placeholder="2G"
            />
            <p class="text-xs text-[var(--text-secondary)] mt-1">e.g., 1G, 2G, 4G</p>
          </div>
        </div>
      </section>

      <button
        type="submit"
        disabled={props.isLoading}
        class="w-full py-3 bg-[var(--accent)] hover:bg-[var(--accent-hover)] disabled:opacity-50 rounded-lg font-medium transition-colors"
      >
        {props.isLoading ? 'Saving...' : (props.submitLabel ?? 'Save Bot')}
      </button>
    </form>
  );
};

