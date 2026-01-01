import { type Component, createSignal, createMemo } from 'solid-js';
import type { BotConfigDTO, LampSkill } from '../../shared/types';
import { botCreateSchema, botUpdateSchema, type BotFormData, type BotFormInput, type BotUpdateInput } from '../../shared/botSchema';

interface BotFormProps {
  /** Initial data for editing - uses DTO (no password/TOTP values) */
  initialData?: BotConfigDTO;
  onSubmit: (data: BotFormData) => void;
  isLoading?: boolean;
  submitLabel?: string;
}

// Placeholder to show in password fields when editing (no actual data)
const PASSWORD_PLACEHOLDER = '••••••••••••';

export const BotForm: Component<BotFormProps> = (props) => {
  const data = props.initialData || null;
  const isEditMode = !!data;
  const initialCharacterName = data ? data.characterName : '';

  const [characterName, setCharacterName] = createSignal(initialCharacterName);
  const [username, setUsername] = createSignal(data ? data.username : '');
  // In edit mode, password/TOTP start empty (placeholder shown via CSS)
  // User must type to change them; empty = no change
  const [password, setPassword] = createSignal('');
  const [totpSecret, setTotpSecret] = createSignal('');
  const [showPassword, setShowPassword] = createSignal(false);
  const [showTotpSecret, setShowTotpSecret] = createSignal(false);
  
  // Track if user has started typing in sensitive fields
  const passwordTouched = createMemo(() => password().length > 0);
  const totpTouched = createMemo(() => totpSecret().length > 0);
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

  // Proxy - DTO has hasPassword flag instead of actual password
  const proxyData = data && data.proxy ? data.proxy : null;
  const [proxyEnabled, setProxyEnabled] = createSignal(!!proxyData);
  const [proxyHost, setProxyHost] = createSignal(proxyData ? proxyData.host : '');
  const [proxyPort, setProxyPort] = createSignal(proxyData ? proxyData.port : 8080);
  const [proxyUser, setProxyUser] = createSignal(proxyData && proxyData.user ? proxyData.user : '');
  // Proxy password: empty in edit mode, user types to change
  const [proxyPass, setProxyPass] = createSignal('');
  const proxyHasExistingPassword = proxyData?.hasPassword ?? false;
  const proxyPassTouched = createMemo(() => proxyPass().length > 0);

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

    // Build form data - password/TOTP empty means "no change" in edit mode
    const formData: BotFormInput | BotUpdateInput = {
      username: username(),
      password: password(), // Empty string in edit mode = no change
      totpSecret: totpSecret(), // Empty string in edit mode = no change
      characterName: characterName(),
      lampSkill: lampSkill(),
      preferredWorld: Number.isFinite(preferredWorld()) ? preferredWorld() : undefined,
      proxy: proxyEnabled()
        ? {
            host: proxyHost().trim(),
            port: proxyPort(),
            user: proxyUser().trim() || undefined,
            pass: proxyPass() || undefined, // Empty = no change in edit mode
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

    // Use appropriate schema based on mode
    const schema = isEditMode ? botUpdateSchema : botCreateSchema;
    const parsed = schema.safeParse(formData);
    if (!parsed.success) {
      setErrors(parsed.error.issues.map((issue) => issue.message));
      return;
    }

    setErrors([]);
    props.onSubmit(parsed.data as BotFormData);
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
          <label class={labelClass}>Character Name</label>
          <input
            type="text"
            value={characterName()}
            onInput={(e) => setCharacterName(e.currentTarget.value)}
            class={inputClass}
            placeholder="Character name (max 12 chars)"
            maxLength={12}
            required
          />
          <p class="text-xs text-[var(--text-secondary)] mt-1">
            Used everywhere as the display name.
          </p>
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
            <label class={labelClass}>
              Password
              {isEditMode && <span class="text-xs text-[var(--text-secondary)] ml-2">(leave empty to keep current)</span>}
            </label>
            <div class="relative">
              <input
                type={showPassword() && passwordTouched() ? 'text' : 'password'}
                value={password()}
                onInput={(e) => setPassword(e.currentTarget.value)}
                class={`${inputClass} pr-12`}
                placeholder={isEditMode ? PASSWORD_PLACEHOLDER : ''}
                required={!isEditMode}
              />
              <button
                type="button"
                class={`absolute inset-y-0 right-0 px-3 text-sm transition-colors ${
                  passwordTouched() 
                    ? 'text-[var(--text-secondary)] hover:text-white cursor-pointer' 
                    : 'text-[var(--text-secondary)]/30 cursor-not-allowed'
                }`}
                onClick={() => passwordTouched() && setShowPassword((prev) => !prev)}
                disabled={!passwordTouched()}
                title={passwordTouched() ? (showPassword() ? 'Hide password' : 'Show password') : 'Type to enable'}
              >
                {showPassword() && passwordTouched() ? 'Hide' : 'View'}
              </button>
            </div>
          </div>
        </div>
        <div>
          <label class={labelClass}>
            2FA TOTP Secret
            {isEditMode && <span class="text-xs text-[var(--text-secondary)] ml-2">(leave empty to keep current)</span>}
          </label>
          <div class="relative">
            <input
              type={showTotpSecret() && totpTouched() ? 'text' : 'password'}
              value={totpSecret()}
              onInput={(e) => setTotpSecret(e.currentTarget.value)}
              class={`${inputClass} pr-12`}
              placeholder={isEditMode ? PASSWORD_PLACEHOLDER : 'Base32 secret from authenticator setup'}
              required={!isEditMode}
            />
            <button
              type="button"
              class={`absolute inset-y-0 right-0 px-3 text-sm transition-colors ${
                totpTouched() 
                  ? 'text-[var(--text-secondary)] hover:text-white cursor-pointer' 
                  : 'text-[var(--text-secondary)]/30 cursor-not-allowed'
              }`}
              onClick={() => totpTouched() && setShowTotpSecret((prev) => !prev)}
              disabled={!totpTouched()}
              title={totpTouched() ? (showTotpSecret() ? 'Hide secret' : 'Show secret') : 'Type to enable'}
            >
              {showTotpSecret() && totpTouched() ? 'Hide' : 'View'}
            </button>
          </div>
          <p class="text-xs text-[var(--text-secondary)] mt-1">
            Jagex requires 2FA. We only support TOTP (not email codes), so paste the base32 secret shown when enabling the authenticator.
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
                <label class={labelClass}>
                  Password (optional)
                  {isEditMode && proxyHasExistingPassword && !proxyPassTouched() && (
                    <span class="text-xs text-[var(--text-secondary)] ml-2">(set - leave empty to keep)</span>
                  )}
                </label>
                <input
                  type="password"
                  value={proxyPass()}
                  onInput={(e) => setProxyPass(e.currentTarget.value)}
                  class={inputClass}
                  placeholder={isEditMode && proxyHasExistingPassword ? PASSWORD_PLACEHOLDER : ''}
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

