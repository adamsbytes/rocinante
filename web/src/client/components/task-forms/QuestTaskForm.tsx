import { type Component, createSignal, createResource, For, Show, createMemo } from 'solid-js';
import type { QuestTaskSpec, QuestInfo } from '../../../shared/types';

interface QuestTaskFormProps {
  onSubmit: (task: QuestTaskSpec) => void;
  submitting?: boolean;
}

const DIFFICULTIES = [
  { value: '', label: 'All Difficulties' },
  { value: 'NOVICE', label: '⭐ Novice' },
  { value: 'INTERMEDIATE', label: '⭐⭐ Intermediate' },
  { value: 'EXPERIENCED', label: '⭐⭐⭐ Experienced' },
  { value: 'MASTER', label: '⭐⭐⭐⭐ Master' },
  { value: 'GRANDMASTER', label: '⭐⭐⭐⭐⭐ Grandmaster' },
] as const;

/**
 * Fetch quests from API.
 */
async function fetchQuests(): Promise<QuestInfo[]> {
  const response = await fetch('/api/data/quests');
  if (!response.ok) throw new Error('Failed to load quests');
  const data = await response.json();
  return data.data;
}

/**
 * Quest Task Form - create quest tasks.
 * 
 * Features:
 * - Quest dropdown with search/filter
 * - Filter by difficulty and membership
 * - Shows quest points and requirements
 */
export const QuestTaskForm: Component<QuestTaskFormProps> = (props) => {
  const [quests] = createResource(fetchQuests);
  
  const [searchQuery, setSearchQuery] = createSignal('');
  const [difficultyFilter, setDifficultyFilter] = createSignal<string>('');
  const [membersFilter, setMembersFilter] = createSignal<string>('');
  const [selectedQuest, setSelectedQuest] = createSignal<string>('');

  // Filter quests
  const filteredQuests = createMemo(() => {
    if (!quests()) return [];
    
    let result = quests()!;
    
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
    if (!id || !quests()) return null;
    return quests()!.find(q => q.id === id) || null;
  });

  // Get difficulty color
  const getDifficultyColor = (difficulty: QuestInfo['difficulty']) => {
    switch (difficulty) {
      case 'NOVICE': return 'text-green-400';
      case 'INTERMEDIATE': return 'text-blue-400';
      case 'EXPERIENCED': return 'text-yellow-400';
      case 'MASTER': return 'text-orange-400';
      case 'GRANDMASTER': return 'text-red-400';
      default: return 'text-gray-400';
    }
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
      {/* Filters */}
      <div class="grid grid-cols-3 gap-3">
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
      </div>

      {/* Quest List */}
      <div>
        <label class="block text-sm font-medium text-gray-300 mb-1">
          Quest
        </label>
        <Show
          when={!quests.loading}
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
              {(quest) => (
                <option value={quest.id}>
                  {quest.members ? '🔒 ' : ''}{quest.name} ({quest.questPoints} QP)
                </option>
              )}
            </For>
          </select>
        </Show>
        <p class="text-xs text-gray-500 mt-1">
          {filteredQuests().length} quests available
        </p>
      </div>

      {/* Selected Quest Info */}
      <Show when={selectedQuestObj()}>
        {(quest) => (
          <div class="bg-gray-700/50 rounded-lg p-4 space-y-3">
            <div class="flex items-center justify-between">
              <h4 class="font-semibold text-white">{quest().name}</h4>
              <span class={`text-sm font-medium ${getDifficultyColor(quest().difficulty)}`}>
                {quest().difficulty.charAt(0) + quest().difficulty.slice(1).toLowerCase()}
              </span>
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

            <div class="text-xs text-amber-400/80 flex items-center gap-2 pt-2 border-t border-gray-600">
              <span>⚠️</span>
              <span>
                Quest support uses QuestHelper integration. Ensure requirements are met before starting.
              </span>
            </div>
          </div>
        )}
      </Show>

      {/* Info Banner */}
      <div class="bg-blue-900/30 border border-blue-800/50 rounded-lg p-3 text-sm text-blue-300">
        <strong>Note:</strong> Quest automation integrates with the QuestHelper plugin.
        Make sure you meet all skill requirements and have necessary items before starting.
      </div>

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

