import { type Component, createSignal } from 'solid-js';
import type { BotConfig, LampSkill } from '../../shared/types';
import { botFormSchema, type BotFormData } from '../../shared/botSchema';

interface BotFormProps {
  initialData?: BotConfig;
  onSubmit: (data: BotFormData) => void;
  isLoading?: boolean;
  submitLabel?: string;
}

export const BotForm: Component<BotFormProps> = (props) => {
  const data = props.initialData || null;

  const [name, setName] = createSignal(data ? data.name : '');
  const [username, setUsername] = createSignal(data ? data.username : '');
  const [password, setPassword] = createSignal(data ? data.password : '');
  const [totpSecret, setTotpSecret] = createSignal(data ? data.totpSecret : '');
  const [characterName, setCharacterName] = createSignal(data ? data.characterName : '');
  const lampSkillOptions: readonly LampSkill[] = [
    'ATTACK',
    'STRENGTH',
    'DEFENCE',
    'RANGED',
    'PRAYER',
    'MAGIC',
    'HITPOINTS',
    'AGILITY',
    'HERBLORE',
    'THIEVING',
    'CRAFTING',
    'FLETCHING',
    'SLAYER',
    'HUNTER',
    'MINING',
    'SMITHING',
    'FISHING',
    'COOKING',
    'FIREMAKING',
    'WOODCUTTING',
    'FARMING',
    'RUNECRAFT',
    'CONSTRUCTION',
  ];

  type CpuOption = '0.5' | '1.0' | '1.5' | '2.0';
  const cpuOptions: ReadonlyArray<CpuOption> = ['0.5', '1.0', '1.5', '2.0'];
  type MemoryOption = '1G' | '2G' | '3G' | '4G';
  const memoryOptions: ReadonlyArray<MemoryOption> = ['1G', '2G', '3G', '4G'];

  const [lampSkill, setLampSkill] = createSignal<LampSkill>(data ? data.lampSkill : lampSkillOptions[0]);
  const [preferredWorld, setPreferredWorld] = createSignal(data && data.preferredWorld !== undefined ? data.preferredWorld : 418);

  // Proxy
  const proxyData = data && data.proxy ? data.proxy : null;
  const [proxyEnabled, setProxyEnabled] = createSignal(!!proxyData);
  const [proxyHost, setProxyHost] = createSignal(proxyData ? proxyData.host : '');
  const [proxyPort, setProxyPort] = createSignal(proxyData ? proxyData.port : 8080);
  const [proxyUser, setProxyUser] = createSignal(proxyData && proxyData.user ? proxyData.user : '');
  const [proxyPass, setProxyPass] = createSignal(proxyData && proxyData.pass ? proxyData.pass : '');

  // Ironman - single dropdown for account type
  type AccountTypeOption = 'NORMAL' | 'STANDARD_IRONMAN' | 'HARDCORE_IRONMAN' | 'ULTIMATE_IRONMAN';
  const getInitialAccountType = (): AccountTypeOption => {
    if (!data) return 'NORMAL';
    if (!data.ironman.enabled) return 'NORMAL';
    return data.ironman.type ? data.ironman.type as AccountTypeOption : 'NORMAL';
  };
  const [accountType, setAccountType] = createSignal<AccountTypeOption>(getInitialAccountType());
  const initialHcimSafety = data && data.ironman && data.ironman.hcimSafetyLevel ? data.ironman.hcimSafetyLevel : 'NORMAL';
  const [hcimSafety, setHcimSafety] = createSignal<'NORMAL' | 'CAUTIOUS' | 'PARANOID'>(initialHcimSafety);

  // Resources
  const initialCpuLimit = cpuOptions.find((opt) => data && data.resources.cpuLimit === opt) || '1.0';
  const initialMemoryLimit = memoryOptions.find((opt) => data && data.resources.memoryLimit === opt) || '2G';
  const [cpuLimit, setCpuLimit] = createSignal<CpuOption>(initialCpuLimit);
  const [memoryLimit, setMemoryLimit] = createSignal<MemoryOption>(initialMemoryLimit);
  const [errors, setErrors] = createSignal<string[]>([]);

  const isLampSkill = (value: string): value is LampSkill => lampSkillOptions.some((opt) => opt === value);
  const isCpuOption = (value: string): value is CpuOption => cpuOptions.some((opt) => opt === value);
  const isMemoryOption = (value: string): value is MemoryOption => memoryOptions.some((opt) => opt === value);
  const isHcimSafety = (value: string): value is 'NORMAL' | 'CAUTIOUS' | 'PARANOID' =>
    value === 'NORMAL' || value === 'CAUTIOUS' || value === 'PARANOID';

  const toIronmanType = (value: AccountTypeOption) =>
    value === 'STANDARD_IRONMAN' || value === 'HARDCORE_IRONMAN' || value === 'ULTIMATE_IRONMAN' ? value : null;

  const handleSubmit = (e: Event) => {
    e.preventDefault();

    const formData: BotFormData = {
      name: name(),
      username: username(),
      password: password(),
      totpSecret: totpSecret(),
      characterName: characterName(),
      lampSkill: lampSkill(),
      preferredWorld: Number.isFinite(preferredWorld()) ? preferredWorld() : undefined,
      proxy: proxyEnabled()
        ? {
            host: proxyHost().trim(),
            port: proxyPort(),
            user: proxyUser().trim() || undefined,
            pass: proxyPass() || undefined,
          }
        : null,
      ironman: {
        enabled: accountType() !== 'NORMAL',
        type: toIronmanType(accountType()),
        hcimSafetyLevel: accountType() === 'HARDCORE_IRONMAN' ? hcimSafety() : null,
      },
      resources: {
        cpuLimit: cpuLimit(),
        memoryLimit: memoryLimit(),
      },
    };

    const parsed = botFormSchema.safeParse(formData);
    if (!parsed.success) {
      setErrors(parsed.error.issues.map((issue) => issue.message));
      return;
    }

    setErrors([]);
    props.onSubmit(parsed.data);
  };

  const inputClass = 'w-full px-3 py-2 bg-[var(--bg-primary)] border border-[var(--border)] rounded-lg focus:outline-none focus:border-[var(--accent)]';
  const labelClass = 'block text-sm font-medium text-[var(--text-secondary)] mb-1';

  return (
    <form onSubmit={handleSubmit} class="space-y-6 max-w-2xl">
      {errors().length > 0 && (
        <div class="space-y-2 rounded-lg border border-red-500/50 bg-red-500/10 px-4 py-3 text-red-100">
          <p class="font-semibold">Fix the highlighted issues:</p>
          <ul class="list-disc space-y-1 pl-5 text-sm">
            {errors().map((err) => (
              <li>{err}</li>
            ))}
          </ul>
        </div>
      )}
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
          <label class={labelClass}>2FA TOTP Secret</label>
          <input
            type="password"
            value={totpSecret()}
            onInput={(e) => setTotpSecret(e.currentTarget.value)}
            class={inputClass}
            placeholder="Base32 secret from authenticator setup"
            required
          />
          <p class="text-xs text-[var(--text-secondary)] mt-1">
            Jagex requires 2FA. We only support TOTP (not email codes), so paste the base32 secret shown when enabling the authenticator.
          </p>
        </div>
        <div>
          <label class={labelClass}>Character Name</label>
          <input
            type="text"
            value={characterName()}
            onInput={(e) => setCharacterName(e.currentTarget.value)}
            class={inputClass}
            placeholder="Desired name for new accounts"
            maxLength={12}
            required
          />
          <p class="text-xs text-[var(--text-secondary)] mt-1">
            Required for account creation and tracking. Max 12 characters (OSRS limit).
          </p>
        </div>
        <div>
          <label class={labelClass}>Lamp Skill (Genie lamps)</label>
          <select
            value={lampSkill()}
            onChange={(e) => {
              const value = e.currentTarget.value;
              if (isLampSkill(value)) {
                setLampSkill(value);
              }
            }}
            class={inputClass}
            required
          >
            {lampSkillOptions.map((skill) => (
              <option value={skill}>{skill}</option>
            ))}
          </select>
          <p class="text-xs text-[var(--text-secondary)] mt-1">
            Used when consuming Genie lamps. Required; choose the skill to receive XP.
          </p>
        </div>
        <div>
          <label class={labelClass}>Preferred World</label>
          <input
            type="number"
            value={preferredWorld()}
            onInput={(e) => setPreferredWorld(parseInt(e.currentTarget.value) || 418)}
            class={inputClass}
            placeholder="418"
            min={301}
            max={638}
          />
          <p class="text-xs text-[var(--text-secondary)] mt-1">
            Default world to log into. 418 is a safe F2P world. Supports any world 301-638.
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
              onChange={(e) => {
                const value = e.currentTarget.value;
                if (isHcimSafety(value)) {
                  setHcimSafety(value);
                }
              }}
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
            <select
              value={cpuLimit()}
              onChange={(e) => {
                const value = e.currentTarget.value;
                if (isCpuOption(value)) {
                  setCpuLimit(value);
                }
              }}
              class={inputClass}
            >
              {cpuOptions.map((opt) => (
                <option value={opt}>{opt}</option>
              ))}
            </select>
            <p class="text-xs text-[var(--text-secondary)] mt-1">Allowed: 0.5 - 2.0 CPU</p>
          </div>
          <div>
            <label class={labelClass}>Memory Limit</label>
            <select
              value={memoryLimit()}
              onChange={(e) => {
                const value = e.currentTarget.value;
                if (isMemoryOption(value)) {
                  setMemoryLimit(value);
                }
              }}
              class={inputClass}
            >
              {memoryOptions.map((opt) => (
                <option value={opt}>{opt}</option>
              ))}
            </select>
            <p class="text-xs text-[var(--text-secondary)] mt-1">Allowed: 1G - 4G</p>
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

