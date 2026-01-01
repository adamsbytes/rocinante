import { type Component, createSignal, For, Show, createMemo, untrack } from 'solid-js';
import type { QuestTaskSpec, QuestInfo, QuestsData, QuestSummary } from '../../../shared/types';

interface QuestTaskFormProps {
  onSubmit: (task: QuestTaskSpec) => void;
  submitting?: boolean;
  /** Live quest data from bot runtime status */
  liveQuestData?: QuestsData | null;
  /** Callback to request quest data refresh from bot */
  onRefreshQuests?: () => void;
}

const DIFFICULTIES = [
  { value: '', label: 'All Difficulties' },
  { value: 'NOVICE', label: '⭐ Novice' },
  { value: 'INTERMEDIATE', label: '⭐⭐ Intermediate' },
  { value: 'EXPERIENCED', label: '⭐⭐⭐ Experienced' },
  { value: 'MASTER', label: '⭐⭐⭐⭐ Master' },
  { value: 'GRANDMASTER', label: '⭐⭐⭐⭐⭐ Grandmaster' },
  { value: 'MINIQUEST', label: '📜 Miniquest' },
] as const;

/**
 * Convert live QuestSummary to QuestInfo for unified display.
 */
function liveQuestToInfo(quest: QuestSummary): QuestInfo & { canStart?: boolean; state?: string } {
  return {
    id: quest.id,
    name: quest.name,
    questPoints: quest.questPoints,
    difficulty: quest.difficulty as QuestInfo['difficulty'],
    members: quest.members,
    canStart: quest.canStart,
    state: quest.state,
  };
}

/**
 * Quest Task Form - create quest tasks.
 * 
 * Features:
 * - Uses live quest data from bot when available (with requirements)
 * - Falls back to static API data
 * - Filter by difficulty, membership, and can-start status
 * - Shows detailed requirements when live data available
 * - Refresh button to update live data
 */
export const QuestTaskForm: Component<QuestTaskFormProps> = (props) => {
  const [searchQuery, setSearchQuery] = createSignal('');
  const [difficultyFilter, setDifficultyFilter] = createSignal<string>('');
  const [membersFilter, setMembersFilter] = createSignal<string>('');
  const [canStartFilter, setCanStartFilter] = createSignal<string>('');
  const [selectedQuest, setSelectedQuest] = createSignal<string>('');

  // Live quest data availability - cache to prevent unnecessary re-renders
  // Store a stable reference that only updates when quest count or IDs change
  const [cachedQuests, setCachedQuests] = createSignal<(QuestInfo & { canStart?: boolean; state?: string })[]>([]);
  const [lastQuestHash, setLastQuestHash] = createSignal('');
  
  const liveQuestData = createMemo(() => props.liveQuestData);
  const hasLiveData = createMemo(() => {
    const live = liveQuestData();
    return !!(live && live.available && live.available.length > 0);
  });

  // Update cached quests only when data actually changes
  createMemo(() => {
    const live = liveQuestData();
    if (live && live.available.length > 0) {
      // Create a hash of quest IDs and states to detect actual changes
      const hash = live.available.map(q => `${q.id}:${q.state}:${q.canStart}`).join('|');
      if (hash !== untrack(lastQuestHash)) {
        setLastQuestHash(hash);
        setCachedQuests(live.available.map(liveQuestToInfo));
      }
    } else if (untrack(cachedQuests).length > 0) {
      setCachedQuests([]);
      setLastQuestHash('');
    }
  });

  // Quest list from cached data (stable reference)
  const questList = cachedQuests;

  // Filter quests
  const filteredQuests = createMemo(() => {
    let result = questList();
    
    // Filter by difficulty
    const difficulty = difficultyFilter();
    if (difficulty) {
      result = result.filter(q => q.difficulty === difficulty);
    }
    
    // Filter by membership
    const members = membersFilter();
    if (members === 'f2p') {
      result = result.filter(q => !q.members);
    } else if (members === 'members') {
      result = result.filter(q => q.members);
    }

    // Filter by can-start (only when live data)
    if (hasLiveData()) {
      const canStart = canStartFilter();
      if (canStart === 'ready') {
        result = result.filter(q => (q as any).canStart === true);
      } else if (canStart === 'notready') {
        result = result.filter(q => (q as any).canStart === false);
      }
    }
    
    // Filter by search query
    const query = searchQuery().toLowerCase();
    if (query) {
      result = result.filter(q => 
        q.name.toLowerCase().includes(query) ||
        q.id.toLowerCase().includes(query)
      );
    }
    
    // Sort by name
    return result.sort((a, b) => a.name.localeCompare(b.name));
  });

  // Get selected quest object
  const selectedQuestObj = createMemo(() => {
    const id = selectedQuest();
    if (!id) return null;
    return questList().find(q => q.id === id) || null;
  });

  // Get live quest details (for requirements display)
  const selectedLiveQuest = createMemo(() => {
    if (!hasLiveData()) return null;
    const id = selectedQuest();
    if (!id) return null;
    return liveQuestData()!.available.find(q => q.id === id) || null;
  });

  // Get difficulty color
  const getDifficultyColor = (difficulty: string) => {
    switch (difficulty) {
      case 'NOVICE': return 'text-green-400';
      case 'INTERMEDIATE': return 'text-blue-400';
      case 'EXPERIENCED': return 'text-yellow-400';
      case 'MASTER': return 'text-orange-400';
      case 'GRANDMASTER': return 'text-red-400';
      case 'MINIQUEST': return 'text-purple-400';
      default: return 'text-gray-400';
    }
  };

  // Format last updated time
  const formatLastUpdated = (timestamp: number) => {
    const date = new Date(timestamp);
    return date.toLocaleTimeString();
  };

  // Handle form submission
  const handleSubmit = (e: Event) => {
    e.preventDefault();
    
    const quest = selectedQuestObj();
    if (!quest) return;

    const task: QuestTaskSpec = {
      taskType: 'QUEST',
      questId: quest.id,
    };

    props.onSubmit(task);
  };

  // Validate form
  const isValid = () => {
    return !!selectedQuest();
  };

  return (
    <form onSubmit={handleSubmit} class="space-y-4">
      {/* Data Source Indicator */}
      <div class="flex items-center justify-between text-xs">
        <Show
          when={hasLiveData()}
          fallback={
            <div class="text-gray-500 flex items-center gap-2">
              <span>⏳ Waiting for live quest data from bot...</span>
              <Show when={props.onRefreshQuests}>
                <button
                  type="button"
                  onClick={() => props.onRefreshQuests?.()}
                  class="text-amber-400 hover:text-amber-300 underline"
                >
                  Request refresh
                </button>
              </Show>
            </div>
          }
        >
          <span class="text-green-400">
            ✓ Live data from bot ({questList().length} quests)
          </span>
          <div class="flex items-center gap-2">
            <span class="text-gray-500">
              Updated: {formatLastUpdated(liveQuestData()!.lastUpdated)}
            </span>
            <Show when={props.onRefreshQuests}>
              <button
                type="button"
                onClick={() => props.onRefreshQuests?.()}
                class="text-amber-400 hover:text-amber-300 underline"
              >
                Refresh
              </button>
            </Show>
          </div>
        </Show>
      </div>

      {/* Filters */}
      <div class="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <div>
          <label class="block text-sm font-medium text-gray-300 mb-1">
            Search
          </label>
          <input
            type="text"
            value={searchQuery()}
            onInput={(e) => setSearchQuery(e.target.value)}
            placeholder="Search quests..."
            class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-amber-500"
          />
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-300 mb-1">
            Difficulty
          </label>
          <select
            value={difficultyFilter()}
            onChange={(e) => setDifficultyFilter(e.target.value)}
            class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
          >
            <For each={DIFFICULTIES}>
              {(d) => (
                <option value={d.value}>{d.label}</option>
              )}
            </For>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-300 mb-1">
            Membership
          </label>
          <select
            value={membersFilter()}
            onChange={(e) => setMembersFilter(e.target.value)}
            class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
          >
            <option value="">All</option>
            <option value="f2p">Free-to-Play</option>
            <option value="members">Members</option>
          </select>
        </div>
        <Show when={hasLiveData()}>
          <div>
            <label class="block text-sm font-medium text-gray-300 mb-1">
              Requirements
            </label>
            <select
              value={canStartFilter()}
              onChange={(e) => setCanStartFilter(e.target.value)}
              class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
            >
              <option value="">All</option>
              <option value="ready">✓ Ready to Start</option>
              <option value="notready">✗ Missing Requirements</option>
            </select>
          </div>
        </Show>
      </div>

      {/* Quest List */}
      <div>
        <label class="block text-sm font-medium text-gray-300 mb-1">
          Quest
        </label>
        <Show
          when={hasLiveData()}
          fallback={
            <div class="text-gray-400 text-sm space-y-2">
              <div>Waiting for quest data from the bot...</div>
              <Show when={props.onRefreshQuests}>
                <button
                  type="button"
                  onClick={() => props.onRefreshQuests?.()}
                  class="text-amber-400 hover:text-amber-300 underline"
                >
                  Request refresh
                </button>
              </Show>
            </div>
          }
        >
          <select
            value={selectedQuest()}
            onChange={(e) => setSelectedQuest(e.target.value)}
            class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
            size={10}
          >
            <option value="">Select a quest...</option>
            <For each={filteredQuests()}>
              {(quest) => {
                const canStart = (quest as any).canStart;
                const state = (quest as any).state;
                const prefix = hasLiveData() 
                  ? (state === 'FINISHED' ? '✓ ' : canStart ? '● ' : '○ ')
                  : '';
                return (
                  <option value={quest.id} class={state === 'FINISHED' ? 'text-gray-500' : ''}>
                    {prefix}{quest.name} ({quest.questPoints} QP)
                </option>
                );
              }}
            </For>
          </select>
        </Show>
        <p class="text-xs text-gray-500 mt-1">
          {hasLiveData()
            ? `${filteredQuests().length} quests • ● ready • ○ missing reqs • ✓ done • ◐ obtainable during quest`
            : 'Quest list will appear once the bot publishes live quest data'}
        </p>
      </div>

      {/* Selected Quest Info */}
      <Show when={selectedQuestObj()}>
        {(quest) => (
          <div class="bg-gray-700/50 rounded-lg p-4 space-y-3">
            <div class="flex items-center justify-between">
              <h4 class="font-semibold text-white">{quest().name}</h4>
              <div class="flex items-center gap-2">
                <Show when={hasLiveData() && selectedLiveQuest()}>
                  {(liveQuest) => (
                    <span class={`text-xs px-2 py-1 rounded ${
                      liveQuest().state === 'FINISHED' ? 'bg-green-900/50 text-green-400' :
                      liveQuest().canStart ? 'bg-blue-900/50 text-blue-400' : 'bg-red-900/50 text-red-400'
                    }`}>
                      {liveQuest().state === 'FINISHED' ? 'Completed' :
                       liveQuest().canStart ? 'Ready' : 'Not Ready'}
                    </span>
                  )}
                </Show>
              <span class={`text-sm font-medium ${getDifficultyColor(quest().difficulty)}`}>
                {quest().difficulty.charAt(0) + quest().difficulty.slice(1).toLowerCase()}
              </span>
              </div>
            </div>
            
            <div class="grid grid-cols-2 gap-4 text-sm">
              <div class="flex items-center gap-2">
                <span class="text-gray-400">Quest Points:</span>
                <span class="text-amber-400 font-medium">{quest().questPoints}</span>
              </div>
              <div class="flex items-center gap-2">
                <span class="text-gray-400">Members:</span>
                <span class={quest().members ? 'text-green-400' : 'text-blue-400'}>
                  {quest().members ? 'Yes' : 'Free-to-Play'}
                </span>
              </div>
            </div>

            {/* Live Requirements Display */}
            <Show when={hasLiveData() && selectedLiveQuest()}>
              {(liveQuest) => (
                <>
                  {/* Skill Requirements */}
                  <Show when={liveQuest().skillRequirements.length > 0}>
                    <div class="border-t border-gray-600 pt-3">
                      <h5 class="text-sm font-medium text-gray-300 mb-2">Skill Requirements</h5>
                      <div class="grid grid-cols-2 gap-2 text-sm">
                        <For each={liveQuest().skillRequirements}>
                          {(req) => (
                            <div class={`flex items-center gap-2 ${req.met ? 'text-green-400' : 'text-red-400'}`}>
                              <span>{req.met ? '✓' : '✗'}</span>
                              <span>{req.skill} {req.required}</span>
                              <span class="text-gray-500">({req.current})</span>
                              <Show when={req.boostable && !req.met}>
                                <span class="text-yellow-400 text-xs">(boostable)</span>
                              </Show>
                            </div>
                          )}
                        </For>
                      </div>
                    </div>
                  </Show>

                  {/* Quest Requirements */}
                  <Show when={liveQuest().questRequirements.length > 0}>
                    <div class="border-t border-gray-600 pt-3">
                      <h5 class="text-sm font-medium text-gray-300 mb-2">Quest Requirements</h5>
                      <div class="grid grid-cols-2 gap-2 text-sm">
                        <For each={liveQuest().questRequirements}>
                          {(req) => (
                            <div class={`flex items-center gap-2 ${req.met ? 'text-green-400' : 'text-red-400'}`}>
                              <span>{req.met ? '✓' : '✗'}</span>
                              <span>{req.questName}</span>
                            </div>
                          )}
                        </For>
                      </div>
                    </div>
                  </Show>

                  {/* Item Requirements (required items only) */}
                  <Show when={liveQuest().itemRequirements.filter(r => !r.recommended).length > 0}>
                    <div class="border-t border-gray-600 pt-3">
                      <h5 class="text-sm font-medium text-gray-300 mb-2">Item Requirements</h5>
                      <div class="grid grid-cols-2 gap-2 text-sm">
                        <For each={liveQuest().itemRequirements.filter(r => !r.recommended)}>
                          {(req) => {
                            const isMet = req.met ?? req.have;
                            const isObtainableDuring = req.obtainableDuringQuest;
                            return (
                              <div class={`flex items-center gap-2 ${
                                isMet ? 'text-green-400' : 
                                isObtainableDuring ? 'text-blue-400' : 'text-yellow-400'
                              }`}>
                                <span>{isMet ? '✓' : isObtainableDuring ? '◐' : '○'}</span>
                                <span>{req.quantity > 1 ? `${req.quantity}x ` : ''}{req.itemName}</span>
                                <Show when={isObtainableDuring && !isMet}>
                                  <span class="text-xs text-blue-400/80">(quest)</span>
                                </Show>
                              </div>
                            );
                          }}
                        </For>
                      </div>
                    </div>
                  </Show>

                  {/* Item Recommendations (optional items) */}
                  <Show when={liveQuest().itemRequirements.filter(r => r.recommended).length > 0}>
                    <div class="border-t border-gray-600 pt-3">
                      <h5 class="text-sm font-medium text-gray-300 mb-2">Item Recommendations</h5>
                      <div class="grid grid-cols-2 gap-2 text-sm">
                        <For each={liveQuest().itemRequirements.filter(r => r.recommended)}>
                          {(req) => {
                            const isMet = req.met ?? req.have;
                            const isObtainableDuring = req.obtainableDuringQuest;
                            return (
                              <div class={`flex items-center gap-2 ${
                                isMet ? 'text-green-400' : 'text-gray-400'
                              }`}>
                                <span>{isMet ? '✓' : '○'}</span>
                                <span>{req.quantity > 1 ? `${req.quantity}x ` : ''}{req.itemName}</span>
                                <Show when={isObtainableDuring && !isMet}>
                                  <span class="text-xs text-blue-400/80">(quest)</span>
                                </Show>
                              </div>
                            );
                          }}
                        </For>
                      </div>
                    </div>
                  </Show>
                </>
              )}
            </Show>

      {/* Waiting notice */}
      <Show when={!hasLiveData()}>
        <div class="text-xs text-amber-400/80 flex items-center gap-2 pt-2 border-t border-gray-600">
          <span>⚠️</span>
          <span>
            Waiting for quest data from the bot. Quest Helper will provide requirements once the bot is running.
          </span>
        </div>
      </Show>
          </div>
        )}
      </Show>

      {/* Info Banner */}
      <Show when={!hasLiveData()}>
        <div class="bg-blue-900/30 border border-blue-800/50 rounded-lg p-3 text-sm text-blue-300">
          <strong>Note:</strong> Quest data is streamed from the running bot via QuestHelper. Use the refresh button if data seems stale.
        </div>
      </Show>

      {/* Submit Button */}
      <button
        type="submit"
        disabled={!isValid() || props.submitting}
        class="w-full px-4 py-2.5 bg-amber-600 hover:bg-amber-700 disabled:bg-gray-600 disabled:cursor-not-allowed text-white font-medium rounded-lg transition-colors"
      >
        {props.submitting ? 'Queuing...' : 'Queue Quest Task'}
      </button>
    </form>
  );
};

export default QuestTaskForm;
