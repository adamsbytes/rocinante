import { type Component, createSignal, createEffect, createMemo, For, Show, untrack } from 'solid-js';
import type { SkillTaskSpec, TrainingMethodInfo, MethodLocationInfo, SkillName } from '../../../shared/types';
import { SKILLS, calculateXpPerHour, getXpPerHourRange, formatXpShort } from '../../../shared/types';
import { useTrainingMethodsQuery } from '../../lib/api';

interface SkillTaskFormProps {
  onSubmit: (task: SkillTaskSpec) => void;
  playerSkills?: Record<string, { level: number }>;
  submitting?: boolean;
}

// Trainable skills (excluding Hitpoints which is trained via combat)
const TRAINABLE_SKILLS = SKILLS.filter(s => 
  !['Hitpoints', 'Slayer'].includes(s)
) as SkillName[];

/**
 * Format XP/hr range for display.
 */
function formatXpRange(min: number, max: number): string {
  if (min === max) {
    return formatXpShort(min);
  }
  return `${formatXpShort(min)}-${formatXpShort(max)}`;
}

/**
 * Skill Task Form - create skill training tasks.
 * 
 * Features:
 * - Skill selector dropdown
 * - Training method dropdown (filtered by skill and level)
 * - Location selector (when method has multiple locations)
 * - Target type selector (Level / XP / Duration)
 * - XP/hr estimate display
 * - Optional settings (banking, world hopping)
 */
export const SkillTaskForm: Component<SkillTaskFormProps> = (props) => {
  // Use TanStack Query - data is cached globally, survives tab switches
  const methodsQuery = useTrainingMethodsQuery();
  const methods = () => methodsQuery.data;
  
  const [selectedSkill, setSelectedSkill] = createSignal<SkillName | ''>('');
  const [selectedMethod, setSelectedMethod] = createSignal<string>('');
  const [selectedLocation, setSelectedLocation] = createSignal<string>('');
  const [targetType, setTargetType] = createSignal<'LEVEL' | 'XP' | 'DURATION'>('LEVEL');
  const [targetValue, setTargetValue] = createSignal<number>(0);
  const [bankInsteadOfDrop, setBankInsteadOfDrop] = createSignal<boolean | undefined>(undefined);
  const [useWorldHopping, setUseWorldHopping] = createSignal(false);
  const [worldHopThreshold, setWorldHopThreshold] = createSignal(2);
  const [showAdvanced, setShowAdvanced] = createSignal(false);

  // Get current level for selected skill
  const currentLevel = () => {
    const skill = selectedSkill();
    if (!skill || !props.playerSkills) return 1;
    return props.playerSkills[skill]?.level ?? 1;
  };

  // Filter methods by selected skill
  const availableMethods = () => {
    const skill = selectedSkill();
    if (!skill || !methods()) return [];
    return methods()![skill] || [];
  };

  // Get the selected method object
  const selectedMethodObj = () => {
    const methodId = selectedMethod();
    return availableMethods().find(m => m.id === methodId);
  };

  // Get the selected location object
  const selectedLocationObj = (): MethodLocationInfo | undefined => {
    const method = selectedMethodObj();
    const locId = selectedLocation();
    if (!method || !locId) return method?.locations[0];
    return method.locations.find(l => l.id === locId) || method.locations[0];
  };

  // Calculate estimated XP/hr for selected method and location
  const estimatedXpHr = () => {
    const method = selectedMethodObj();
    const location = selectedLocationObj();
    if (!method) return 0;
    return calculateXpPerHour(method, currentLevel(), location);
  };

  // Sorted methods list - stable reference, only changes when skill changes
  const sortedMethods = createMemo(() => {
    return availableMethods().slice().sort((a, b) => a.minLevel - b.minLevel);
  });

  // Pre-computed XP ranges - Map lookup is O(1) during render
  // Recomputes when methods or level change, but doesn't affect sortedMethods stability
  const xpRangeMap = createMemo(() => {
    const level = currentLevel();
    const map = new Map<string, string>();
    for (const method of availableMethods()) {
      const range = getXpPerHourRange(method, level);
      map.set(method.id, formatXpRange(range.min, range.max));
    }
    return map;
  });

  // Helper to check if player meets method requirements
  const meetsRequirements = (method: TrainingMethodInfo) => {
    return method.minLevel <= currentLevel();
  };

  // Reset method when skill changes
  createEffect(() => {
    selectedSkill(); // track
    setSelectedMethod('');
    setSelectedLocation('');
  });

  // Reset location when method changes
  createEffect(() => {
    const method = selectedMethodObj();
    if (method && method.locations.length > 0) {
      setSelectedLocation(method.locations[0].id);
    } else {
      setSelectedLocation('');
    }
  });

  // Track the last skill/type combo we set defaults for
  // This prevents re-setting when playerSkills updates from polling
  let lastDefaultsKey = '';

  // Set default target value when skill or target type changes
  // Does NOT re-run when playerSkills updates (that would overwrite user input)
  createEffect(() => {
    const type = targetType();
    const skill = selectedSkill();
    
    // Create a key for this skill/type combo
    const key = `${skill}:${type}`;
    
    // Only set defaults if this is a NEW combo (user changed skill or type)
    if (key === lastDefaultsKey) return;
    lastDefaultsKey = key;
    
    // Read level without tracking - we just want the current value
    const level = untrack(currentLevel);
    
    if (type === 'LEVEL') {
      // Default to current level + 10, capped at 99
      setTargetValue(Math.min(level + 10, 99));
    } else if (type === 'XP') {
      setTargetValue(50000);
    } else {
      setTargetValue(60); // 60 minutes
    }
  });

  // Handle form submission
  const handleSubmit = (e: Event) => {
    e.preventDefault();
    
    const method = selectedMethod();
    if (!method) return;

    const task: SkillTaskSpec = {
      taskType: 'SKILL',
      methodId: method,
      targetType: targetType(),
      targetValue: targetValue(),
    };

    // Include location if method has multiple locations
    const methodObj = selectedMethodObj();
    if (methodObj && methodObj.locations.length > 1) {
      const locId = selectedLocation();
      if (locId) {
        task.locationId = locId;
      }
    }

    // Add optional fields
    if (bankInsteadOfDrop() !== undefined) {
      task.bankInsteadOfDrop = bankInsteadOfDrop();
    }
    if (useWorldHopping()) {
      task.useWorldHopping = true;
      task.worldHopThreshold = worldHopThreshold();
    }

    props.onSubmit(task);
  };

  // Validate form
  const isValid = () => {
    if (!selectedSkill() || !selectedMethod()) return false;
    if (targetValue() <= 0) return false;
    
    if (targetType() === 'LEVEL') {
      const level = currentLevel();
      const target = targetValue();
      // Target must be greater than current level and at most 99
      if (target <= level || target > 99) return false;
    }
    
    return true;
  };

  // Get validation error message for target field
  const getTargetError = () => {
    if (targetType() !== 'LEVEL') return null;
    
    const level = currentLevel();
    const target = targetValue();
    
    if (target <= level) {
      return `Target must be greater than current level (${level})`;
    }
    if (target > 99) {
      return 'Target cannot exceed 99';
    }
    return null;
  };

  return (
    <form onSubmit={handleSubmit} class="space-y-4">
      {/* Skill Selector */}
      <div>
        <label class="block text-sm font-medium text-gray-300 mb-1">
          Skill
        </label>
        <select
          value={selectedSkill()}
          onChange={(e) => setSelectedSkill(e.target.value as SkillName | '')}
          class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
        >
          <option value="">Select a skill...</option>
          <For each={TRAINABLE_SKILLS}>
            {(skill) => (
              <option value={skill}>
                {skill}{props.playerSkills?.[skill]?.level 
                  ? ` [${props.playerSkills[skill].level}]`
                  : ''}
              </option>
            )}
          </For>
        </select>
      </div>

      {/* Training Method Selector */}
      <Show when={selectedSkill()}>
        <div>
          <label class="block text-sm font-medium text-gray-300 mb-1">
            Training Method
          </label>
          <Show
            when={!methodsQuery.isLoading}
            fallback={<div class="text-gray-400 text-sm">Loading methods...</div>}
          >
            <select
              value={selectedMethod()}
              onChange={(e) => setSelectedMethod(e.target.value)}
              class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
            >
              <option value="">Select a method...</option>
              {/* Show all methods, sorted by level - valid ones first, then locked ones */}
              {/* Methods list is stable; XP ranges pre-computed in xpRangeMap */}
              <For each={sortedMethods()}>
                {(method) => {
                  // Reactive lookups - O(1) from pre-computed map
                  const meetsReqs = () => meetsRequirements(method);
                  const xpRange = () => xpRangeMap().get(method.id) || '';
                  return (
                    <option 
                      value={method.id}
                      disabled={!meetsReqs()}
                      class={!meetsReqs() ? 'text-gray-500' : ''}
                    >
                      {meetsReqs() 
                        ? `${method.name} (Lvl ${method.minLevel}+, ~${xpRange()}/hr)`
                        : `🔒 ${method.name} (Requires Lvl ${method.minLevel})`
                      }
                    </option>
                  );
                }}
              </For>
            </select>
          </Show>
        </div>
      </Show>

      {/* Location Selector - only show if method has multiple locations */}
      <Show when={selectedMethodObj() && selectedMethodObj()!.locations.length > 1}>
        {(_) => {
          const method = selectedMethodObj()!;
          return (
            <div>
              <label class="block text-sm font-medium text-gray-300 mb-1">
                Location
              </label>
              <select
                value={selectedLocation()}
                onChange={(e) => setSelectedLocation(e.target.value)}
                class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
              >
                <For each={method.locations}>
                  {(location) => (
                    <option value={location.id}>
                      {location.name}
                      {' '}(~{formatXpShort(calculateXpPerHour(method, currentLevel(), location))}/hr)
                      {location.membersOnly ? ' [P2P]' : ''}
                    </option>
                  )}
                </For>
              </select>
            </div>
          );
        }}
      </Show>

      {/* Method Info Card */}
      <Show when={selectedMethodObj()}>
        {(method) => {
          const location = selectedLocationObj();
          return (
            <div class="bg-gray-700/50 rounded-lg p-3 space-y-2">
              <div class="flex justify-between text-sm">
                <span class="text-gray-400">Estimated XP/hr:</span>
                <span class="text-emerald-400 font-medium">
                  {formatXpShort(estimatedXpHr())}
                </span>
              </div>
              <Show when={location && location.gpPerHour !== 0}>
                <div class="flex justify-between text-sm">
                  <span class="text-gray-400">GP/hr:</span>
                  <span class={location!.gpPerHour > 0 ? 'text-green-400' : 'text-red-400'}>
                    {location!.gpPerHour > 0 ? '+' : ''}{location!.gpPerHour.toLocaleString()}
                  </span>
                </div>
              </Show>
              <div class="flex justify-between text-sm">
                <span class="text-gray-400">Type:</span>
                <span class="text-gray-200">{method().methodType}</span>
              </div>
              <Show when={method().locations.length === 1 && method().locations[0].name}>
                <div class="flex justify-between text-sm">
                  <span class="text-gray-400">Location:</span>
                  <span class="text-gray-200">{method().locations[0].name}</span>
                </div>
              </Show>
              <Show when={!method().ironmanViable}>
                <div class="text-amber-400 text-xs mt-1">
                  ⚠️ Not ironman viable
                </div>
              </Show>
              <Show when={method().membersOnly || location?.membersOnly}>
                <div class="text-sky-400 text-xs mt-1">
                  🔷 Members only
                </div>
              </Show>
              <Show when={location?.notes}>
                <div class="text-gray-400 text-xs mt-1 italic">
                  {location!.notes}
                </div>
              </Show>
            </div>
          );
        }}
      </Show>

      {/* Target Configuration */}
      <Show when={selectedMethod()}>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="block text-sm font-medium text-gray-300 mb-1">
              Target Type
            </label>
            <select
              value={targetType()}
              onChange={(e) => setTargetType(e.target.value as typeof targetType.arguments[0])}
              class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
            >
              <option value="LEVEL">Level</option>
              <option value="XP">XP Amount</option>
              <option value="DURATION">Duration</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-300 mb-1">
              {targetType() === 'LEVEL' ? 'Target Level' : 
               targetType() === 'XP' ? 'Target XP' : 'Duration (minutes)'}
            </label>
            <input
              type="number"
              value={targetValue()}
              onInput={(e) => setTargetValue(parseInt(e.target.value) || 0)}
              min={targetType() === 'LEVEL' ? currentLevel() + 1 : 1}
              max={targetType() === 'LEVEL' ? 99 : undefined}
              class={`w-full bg-gray-700 border rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 ${
                getTargetError() 
                  ? 'border-red-500 focus:ring-red-500' 
                  : 'border-gray-600 focus:ring-amber-500'
              }`}
            />
            <Show when={getTargetError()}>
              <p class="text-red-400 text-xs mt-1">{getTargetError()}</p>
            </Show>
          </div>
        </div>
      </Show>

      {/* Advanced Options */}
      <Show when={selectedMethod()}>
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
              {/* Banking Override */}
              <div>
                <label class="block text-sm font-medium text-gray-300 mb-1">
                  Inventory Handling
                </label>
                <select
                  value={bankInsteadOfDrop() === undefined ? 'default' : bankInsteadOfDrop() ? 'bank' : 'drop'}
                  onChange={(e) => {
                    const v = e.target.value;
                    setBankInsteadOfDrop(v === 'default' ? undefined : v === 'bank');
                  }}
                  class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
                >
                  <option value="default">Use method default</option>
                  <option value="bank">Bank when full</option>
                  <option value="drop">Drop when full (power train)</option>
                </select>
              </div>

              {/* World Hopping */}
              <div class="flex items-center gap-3">
                <input
                  type="checkbox"
                  id="worldHopping"
                  checked={useWorldHopping()}
                  onChange={(e) => setUseWorldHopping(e.target.checked)}
                  class="w-4 h-4 rounded bg-gray-700 border-gray-600 text-amber-500 focus:ring-amber-500"
                />
                <label for="worldHopping" class="text-sm text-gray-300">
                  World hop when crowded
                </label>
              </div>
              
              <Show when={useWorldHopping()}>
                <div class="pl-7">
                  <label class="block text-sm text-gray-400 mb-1">
                    Hop when players ≥
                  </label>
                  <input
                    type="number"
                    value={worldHopThreshold()}
                    onInput={(e) => setWorldHopThreshold(parseInt(e.target.value) || 2)}
                    min={1}
                    max={10}
                    class="w-24 bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white text-sm focus:outline-none focus:ring-2 focus:ring-amber-500"
                  />
                </div>
              </Show>
            </div>
          </Show>
        </div>
      </Show>

      {/* Submit Button */}
      <button
        type="submit"
        disabled={!isValid() || props.submitting}
        class="w-full px-4 py-2.5 bg-amber-600 hover:bg-amber-700 disabled:bg-gray-600 disabled:cursor-not-allowed text-white font-medium rounded-lg transition-colors"
      >
        {props.submitting ? 'Queuing...' : 'Queue Skill Task'}
      </button>
    </form>
  );
};

export default SkillTaskForm;

