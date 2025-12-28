import { type Component, createSignal, createResource, For, Show, createMemo, createEffect } from 'solid-js';
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
 * Fetch quests from static API (fallback when no live data).
 */
async function fetchQuests(): Promise<QuestInfo[]> {
  const response = await fetch('/api/data/quests');
  if (!response.ok) throw new Error('Failed to load quests');
  const data = await response.json();
  return data.data;
}

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
  // Fallback to static API when no live data
  const [staticQuests] = createResource(fetchQuests);
  
  const [searchQuery, setSearchQuery] = createSignal('');
  const [difficultyFilter, setDifficultyFilter] = createSignal<string>('');
  const [membersFilter, setMembersFilter] = createSignal<string>('');
  const [canStartFilter, setCanStartFilter] = createSignal<string>('');
  const [selectedQuest, setSelectedQuest] = createSignal<string>('');

  // Determine if we're using live data
  const usingLiveData = createMemo(() => {
    const live = props.liveQuestData;
    return live && live.available && live.available.length > 0;
  });

  // Get quest list (live or static)
  const questList = createMemo(() => {
    if (usingLiveData()) {
      return props.liveQuestData!.available.map(liveQuestToInfo);
    }
    return staticQuests() || [];
  });

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
    if (usingLiveData()) {
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
    if (!usingLiveData()) return null;
    const id = selectedQuest();
    if (!id) return null;
    return props.liveQuestData!.available.find(q => q.id === id) || null;
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
          when={usingLiveData()}
          fallback={
            <span class="text-gray-500">
              📄 Using static quest list ({questList().length} quests)
            </span>
          }
        >
          <span class="text-green-400">
            ✓ Live data from bot ({props.liveQuestData!.available.length} quests)
          </span>
          <div class="flex items-center gap-2">
            <span class="text-gray-500">
              Updated: {formatLastUpdated(props.liveQuestData!.lastUpdated)}
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
        <Show when={usingLiveData()}>
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
          when={!staticQuests.loading || usingLiveData()}
          fallback={<div class="text-gray-400 text-sm">Loading quests...</div>}
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
                const prefix = usingLiveData() 
                  ? (state === 'FINISHED' ? '✓ ' : canStart ? '● ' : '○ ')
                  : (quest.members ? '🔒 ' : '');
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
          {filteredQuests().length} quests available
          {usingLiveData() && ` • ● = ready to start • ○ = missing requirements • ✓ = completed`}
        </p>
      </div>

      {/* Selected Quest Info */}
      <Show when={selectedQuestObj()}>
        {(quest) => (
          <div class="bg-gray-700/50 rounded-lg p-4 space-y-3">
            <div class="flex items-center justify-between">
              <h4 class="font-semibold text-white">{quest().name}</h4>
              <div class="flex items-center gap-2">
                <Show when={usingLiveData() && selectedLiveQuest()}>
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
            <Show when={usingLiveData() && selectedLiveQuest()}>
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

                  {/* Item Requirements */}
                  <Show when={liveQuest().itemRequirements.length > 0}>
                    <div class="border-t border-gray-600 pt-3">
                      <h5 class="text-sm font-medium text-gray-300 mb-2">Item Requirements</h5>
                      <div class="grid grid-cols-2 gap-2 text-sm">
                        <For each={liveQuest().itemRequirements}>
                          {(req) => (
                            <div class={`flex items-center gap-2 ${req.have ? 'text-green-400' : 'text-yellow-400'}`}>
                              <span>{req.have ? '✓' : '○'}</span>
                              <span>{req.quantity > 1 ? `${req.quantity}x ` : ''}{req.itemName}</span>
                            </div>
                          )}
                        </For>
                      </div>
                    </div>
                  </Show>
                </>
              )}
            </Show>

            {/* Warning for static data */}
            <Show when={!usingLiveData()}>
            <div class="text-xs text-amber-400/80 flex items-center gap-2 pt-2 border-t border-gray-600">
              <span>⚠️</span>
              <span>
                  Start the bot to see live requirement status from Quest Helper.
              </span>
            </div>
            </Show>
          </div>
        )}
      </Show>

      {/* Info Banner */}
      <Show when={!usingLiveData()}>
      <div class="bg-blue-900/30 border border-blue-800/50 rounded-lg p-3 text-sm text-blue-300">
        <strong>Note:</strong> Quest automation integrates with the QuestHelper plugin.
          Start the bot to see live quest requirements and status.
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
