import { type Component, createSignal, Show } from 'solid-js';
import type { CombatTaskSpec } from '../../../shared/types';

interface CombatTaskFormProps {
  onSubmit: (task: CombatTaskSpec) => void;
  submitting?: boolean;
}

const WEAPON_STYLES = [
  { value: 'ANY', label: 'Any' },
  { value: 'SLASH', label: 'Slash' },
  { value: 'STAB', label: 'Stab' },
  { value: 'CRUSH', label: 'Crush' },
  { value: 'RANGED', label: 'Ranged' },
  { value: 'MAGIC', label: 'Magic' },
] as const;

const XP_GOALS = [
  { value: 'ANY', label: 'Any' },
  { value: 'ATTACK', label: 'Attack' },
  { value: 'STRENGTH', label: 'Strength' },
  { value: 'DEFENCE', label: 'Defence' },
  { value: 'HITPOINTS', label: 'Hitpoints' },
  { value: 'RANGED', label: 'Ranged' },
  { value: 'MAGIC', label: 'Magic' },
] as const;

/**
 * Combat Task Form - create combat tasks.
 * 
 * Features:
 * - Target NPC input (names or IDs)
 * - Completion type (kill count / duration)
 * - Loot settings
 * - Combat style configuration
 * - Optional safe-spotting
 */
export const CombatTaskForm: Component<CombatTaskFormProps> = (props) => {
  const [targetInput, setTargetInput] = createSignal('');
  const [useNpcIds, setUseNpcIds] = createSignal(false);
  const [completionType, setCompletionType] = createSignal<'KILL_COUNT' | 'DURATION'>('KILL_COUNT');
  const [completionValue, setCompletionValue] = createSignal(50);
  const [lootEnabled, setLootEnabled] = createSignal(true);
  const [lootMinValue, setLootMinValue] = createSignal(1000);
  const [weaponStyle, setWeaponStyle] = createSignal<CombatTaskSpec['weaponStyle']>('ANY');
  const [xpGoal, setXpGoal] = createSignal<CombatTaskSpec['xpGoal']>('ANY');
  const [showAdvanced, setShowAdvanced] = createSignal(false);
  const [useSafeSpot, setUseSafeSpot] = createSignal(false);
  const [safeSpotX, setSafeSpotX] = createSignal(0);
  const [safeSpotY, setSafeSpotY] = createSignal(0);
  const [stopWhenOutOfFood, setStopWhenOutOfFood] = createSignal(true);
  const [enableResupply, setEnableResupply] = createSignal(false);

  // Parse targets from input
  const parseTargets = () => {
    const input = targetInput().trim();
    if (!input) return { names: [], ids: [] };

    const parts = input.split(',').map(s => s.trim()).filter(Boolean);
    
    if (useNpcIds()) {
      const ids = parts.map(p => parseInt(p)).filter(n => !isNaN(n));
      return { names: [], ids };
    } else {
      return { names: parts, ids: [] };
    }
  };

  // Handle form submission
  const handleSubmit = (e: Event) => {
    e.preventDefault();
    
    const targets = parseTargets();
    if (targets.names.length === 0 && targets.ids.length === 0) return;

    const task: CombatTaskSpec = {
      taskType: 'COMBAT',
      completionType: completionType(),
      completionValue: completionValue(),
      lootEnabled: lootEnabled(),
    };

    // Add targets
    if (targets.names.length > 0) {
      task.targetNpcs = targets.names;
    }
    if (targets.ids.length > 0) {
      task.targetNpcIds = targets.ids;
    }

    // Add loot settings
    if (lootEnabled()) {
      task.lootMinValue = lootMinValue();
    }

    // Add combat style
    if (weaponStyle() !== 'ANY') {
      task.weaponStyle = weaponStyle();
    }
    if (xpGoal() !== 'ANY') {
      task.xpGoal = xpGoal();
    }

    // Add safe spot
    if (useSafeSpot() && safeSpotX() && safeSpotY()) {
      task.useSafeSpot = true;
      task.safeSpotX = safeSpotX();
      task.safeSpotY = safeSpotY();
    }

    // Add resource management
    task.stopWhenOutOfFood = stopWhenOutOfFood();
    task.enableResupply = enableResupply();

    props.onSubmit(task);
  };

  // Validate form
  const isValid = () => {
    const targets = parseTargets();
    if (targets.names.length === 0 && targets.ids.length === 0) return false;
    if (completionValue() <= 0) return false;
    return true;
  };

  return (
    <form onSubmit={handleSubmit} class="space-y-4">
      {/* Target NPC Input */}
      <div>
        <div class="flex items-center justify-between mb-1">
          <label class="block text-sm font-medium text-gray-300">
            Target NPCs
          </label>
          <div class="flex items-center gap-2 text-xs">
            <button
              type="button"
              onClick={() => setUseNpcIds(false)}
              class={`px-2 py-1 rounded ${!useNpcIds() ? 'bg-amber-600 text-white' : 'bg-gray-700 text-gray-400'}`}
            >
              Names
            </button>
            <button
              type="button"
              onClick={() => setUseNpcIds(true)}
              class={`px-2 py-1 rounded ${useNpcIds() ? 'bg-amber-600 text-white' : 'bg-gray-700 text-gray-400'}`}
            >
              IDs
            </button>
          </div>
        </div>
        <input
          type="text"
          value={targetInput()}
          onInput={(e) => setTargetInput(e.target.value)}
          placeholder={useNpcIds() ? 'e.g., 3021, 3022' : 'e.g., Goblin, Cow, Chicken'}
          class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-amber-500"
        />
        <p class="text-xs text-gray-500 mt-1">
          Separate multiple targets with commas
        </p>
      </div>

      {/* Completion Type */}
      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="block text-sm font-medium text-gray-300 mb-1">
            Complete After
          </label>
          <select
            value={completionType()}
            onChange={(e) => setCompletionType(e.target.value as 'KILL_COUNT' | 'DURATION')}
            class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
          >
            <option value="KILL_COUNT">Kill Count</option>
            <option value="DURATION">Duration</option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-300 mb-1">
            {completionType() === 'KILL_COUNT' ? 'Number of Kills' : 'Duration (minutes)'}
          </label>
          <input
            type="number"
            value={completionValue()}
            onInput={(e) => setCompletionValue(parseInt(e.target.value) || 0)}
            min={1}
            class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
          />
        </div>
      </div>

      {/* Loot Settings */}
      <div class="bg-gray-700/50 rounded-lg p-3 space-y-3">
        <div class="flex items-center justify-between">
          <span class="text-sm font-medium text-gray-300">Loot Settings</span>
          <div class="flex items-center gap-2">
            <input
              type="checkbox"
              id="lootEnabled"
              checked={lootEnabled()}
              onChange={(e) => setLootEnabled(e.target.checked)}
              class="w-4 h-4 rounded bg-gray-700 border-gray-600 text-amber-500 focus:ring-amber-500"
            />
            <label for="lootEnabled" class="text-sm text-gray-300">
              Enable Looting
            </label>
          </div>
        </div>
        <Show when={lootEnabled()}>
          <div class="flex items-center gap-3">
            <label class="text-sm text-gray-400">Min Value:</label>
            <input
              type="number"
              value={lootMinValue()}
              onInput={(e) => setLootMinValue(parseInt(e.target.value) || 0)}
              min={0}
              step={100}
              class="w-32 bg-gray-700 border border-gray-600 rounded-lg px-3 py-1.5 text-white text-sm focus:outline-none focus:ring-2 focus:ring-amber-500"
            />
            <span class="text-sm text-gray-400">gp</span>
          </div>
        </Show>
      </div>

      {/* Combat Style */}
      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="block text-sm font-medium text-gray-300 mb-1">
            Weapon Style
          </label>
          <select
            value={weaponStyle()}
            onChange={(e) => setWeaponStyle(e.target.value as CombatTaskSpec['weaponStyle'])}
            class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
          >
            {WEAPON_STYLES.map(s => (
              <option value={s.value}>{s.label}</option>
            ))}
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-300 mb-1">
            XP Goal
          </label>
          <select
            value={xpGoal()}
            onChange={(e) => setXpGoal(e.target.value as CombatTaskSpec['xpGoal'])}
            class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
          >
            {XP_GOALS.map(g => (
              <option value={g.value}>{g.label}</option>
            ))}
          </select>
        </div>
      </div>

      {/* Advanced Options */}
      <div>
        <button
          type="button"
          onClick={() => setShowAdvanced(!showAdvanced())}
          class="text-sm text-gray-400 hover:text-gray-200 flex items-center gap-1"
        >
          <span class={`transform transition-transform ${showAdvanced() ? 'rotate-90' : ''}`}>▶</span>
          Advanced Options
        </button>
        
        <Show when={showAdvanced()}>
          <div class="mt-3 space-y-3 pl-4 border-l-2 border-gray-600">
            {/* Safe-spotting */}
            <div>
              <div class="flex items-center gap-2 mb-2">
                <input
                  type="checkbox"
                  id="useSafeSpot"
                  checked={useSafeSpot()}
                  onChange={(e) => setUseSafeSpot(e.target.checked)}
                  class="w-4 h-4 rounded bg-gray-700 border-gray-600 text-amber-500 focus:ring-amber-500"
                />
                <label for="useSafeSpot" class="text-sm text-gray-300">
                  Use safe spot
                </label>
              </div>
              <Show when={useSafeSpot()}>
                <div class="flex items-center gap-2 pl-6">
                  <input
                    type="number"
                    value={safeSpotX()}
                    onInput={(e) => setSafeSpotX(parseInt(e.target.value) || 0)}
                    placeholder="X"
                    class="w-24 bg-gray-700 border border-gray-600 rounded-lg px-3 py-1.5 text-white text-sm focus:outline-none focus:ring-2 focus:ring-amber-500"
                  />
                  <input
                    type="number"
                    value={safeSpotY()}
                    onInput={(e) => setSafeSpotY(parseInt(e.target.value) || 0)}
                    placeholder="Y"
                    class="w-24 bg-gray-700 border border-gray-600 rounded-lg px-3 py-1.5 text-white text-sm focus:outline-none focus:ring-2 focus:ring-amber-500"
                  />
                </div>
              </Show>
            </div>

            {/* Resource Management */}
            <div class="space-y-2">
              <div class="flex items-center gap-2">
                <input
                  type="checkbox"
                  id="stopWhenOutOfFood"
                  checked={stopWhenOutOfFood()}
                  onChange={(e) => setStopWhenOutOfFood(e.target.checked)}
                  class="w-4 h-4 rounded bg-gray-700 border-gray-600 text-amber-500 focus:ring-amber-500"
                />
                <label for="stopWhenOutOfFood" class="text-sm text-gray-300">
                  Stop when out of food
                </label>
              </div>
              <div class="flex items-center gap-2">
                <input
                  type="checkbox"
                  id="enableResupply"
                  checked={enableResupply()}
                  onChange={(e) => setEnableResupply(e.target.checked)}
                  class="w-4 h-4 rounded bg-gray-700 border-gray-600 text-amber-500 focus:ring-amber-500"
                />
                <label for="enableResupply" class="text-sm text-gray-300">
                  Enable bank resupply
                </label>
              </div>
            </div>
          </div>
        </Show>
      </div>

      {/* Submit Button */}
      <button
        type="submit"
        disabled={!isValid() || props.submitting}
        class="w-full px-4 py-2.5 bg-amber-600 hover:bg-amber-700 disabled:bg-gray-600 disabled:cursor-not-allowed text-white font-medium rounded-lg transition-colors"
      >
        {props.submitting ? 'Queuing...' : 'Queue Combat Task'}
      </button>
    </form>
  );
};

export default CombatTaskForm;

