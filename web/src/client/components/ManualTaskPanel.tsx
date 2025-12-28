import { type Component, createSignal, Show, For } from 'solid-js';
import type { StatusStore } from '../lib/statusStore';
import type { TaskSpec } from '../../shared/types';
import { SkillTaskForm } from './task-forms/SkillTaskForm';
import { CombatTaskForm } from './task-forms/CombatTaskForm';
import { NavigationTaskForm } from './task-forms/NavigationTaskForm';
import { QuestTaskForm } from './task-forms/QuestTaskForm';

type TabId = 'skills' | 'combat' | 'navigation' | 'quests';

interface Tab {
  id: TabId;
  label: string;
  icon: string;
}

const TABS: Tab[] = [
  { id: 'skills', label: 'Skills', icon: '⚒️' },
  { id: 'combat', label: 'Combat', icon: '⚔️' },
  { id: 'navigation', label: 'Navigation', icon: '🧭' },
  { id: 'quests', label: 'Quests', icon: '📜' },
];

interface ManualTaskPanelProps {
  /** Status store for sending commands */
  statusStore: StatusStore;
  /** Current player level (for skill method filtering) */
  playerSkills?: Record<string, { level: number }>;
  /** Class name for the container */
  class?: string;
}

/**
 * Manual Task Panel - allows users to queue tasks manually.
 * 
 * Features:
 * - Tabbed interface for different task types
 * - Task control buttons (abort, clear queue)
 * - Dynamic forms for each task type
 */
export const ManualTaskPanel: Component<ManualTaskPanelProps> = (props) => {
  const [activeTab, setActiveTab] = createSignal<TabId>('skills');
  const [submitting, setSubmitting] = createSignal(false);
  const [feedback, setFeedback] = createSignal<{ type: 'success' | 'error'; message: string } | null>(null);

  /**
   * Handle task submission from any form.
   */
  const handleSubmitTask = async (taskSpec: TaskSpec) => {
    setSubmitting(true);
    setFeedback(null);

    try {
      props.statusStore.sendCommand({
        type: 'QUEUE_TASK',
        task: taskSpec,
      });
      setFeedback({ type: 'success', message: 'Task queued successfully!' });
      
      // Clear feedback after 3 seconds
      setTimeout(() => setFeedback(null), 3000);
    } catch (err) {
      setFeedback({ 
        type: 'error', 
        message: err instanceof Error ? err.message : 'Failed to queue task' 
      });
    } finally {
      setSubmitting(false);
    }
  };

  /**
   * Abort the current task.
   */
  const handleAbortTask = () => {
    props.statusStore.sendCommand({ type: 'ABORT_TASK' });
    setFeedback({ type: 'success', message: 'Abort command sent' });
    setTimeout(() => setFeedback(null), 3000);
  };

  /**
   * Clear the task queue.
   */
  const handleClearQueue = () => {
    props.statusStore.sendCommand({ type: 'CLEAR_QUEUE' });
    setFeedback({ type: 'success', message: 'Queue cleared' });
    setTimeout(() => setFeedback(null), 3000);
  };

  /**
   * Force a break.
   */
  const handleForceBreak = () => {
    props.statusStore.sendCommand({ type: 'FORCE_BREAK' });
    setFeedback({ type: 'success', message: 'Break scheduled' });
    setTimeout(() => setFeedback(null), 3000);
  };

  return (
    <div class={`bg-gray-800/60 rounded-lg border border-gray-700 ${props.class || ''}`}>
      {/* Header with title and control buttons */}
      <div class="flex items-center justify-between px-4 py-3 border-b border-gray-700">
        <h3 class="text-lg font-semibold text-gray-100 flex items-center gap-2">
          <span class="text-xl">📋</span>
          Manual Task Control
        </h3>
        <div class="flex gap-2">
          <button
            onClick={handleForceBreak}
            class="px-3 py-1.5 text-sm bg-amber-600/20 hover:bg-amber-600/40 text-amber-400 border border-amber-600/50 rounded-lg transition-colors"
            title="Schedule a break"
          >
            ☕ Break
          </button>
          <button
            onClick={handleAbortTask}
            class="px-3 py-1.5 text-sm bg-orange-600/20 hover:bg-orange-600/40 text-orange-400 border border-orange-600/50 rounded-lg transition-colors"
            title="Abort current task"
          >
            ⏹ Abort
          </button>
          <button
            onClick={handleClearQueue}
            class="px-3 py-1.5 text-sm bg-red-600/20 hover:bg-red-600/40 text-red-400 border border-red-600/50 rounded-lg transition-colors"
            title="Clear task queue"
          >
            🗑 Clear Queue
          </button>
        </div>
      </div>

      {/* Tab navigation */}
      <div class="flex border-b border-gray-700">
        <For each={TABS}>
          {(tab) => (
            <button
              onClick={() => setActiveTab(tab.id)}
              class={`flex-1 px-4 py-3 text-sm font-medium transition-colors ${
                activeTab() === tab.id
                  ? 'bg-gray-700/50 text-white border-b-2 border-amber-500'
                  : 'text-gray-400 hover:text-gray-200 hover:bg-gray-700/30'
              }`}
            >
              <span class="mr-2">{tab.icon}</span>
              {tab.label}
            </button>
          )}
        </For>
      </div>

      {/* Feedback banner */}
      <Show when={feedback()}>
        {(fb) => (
          <div
            class={`px-4 py-2 text-sm ${
              fb().type === 'success'
                ? 'bg-emerald-900/50 text-emerald-300 border-b border-emerald-800'
                : 'bg-red-900/50 text-red-300 border-b border-red-800'
            }`}
          >
            {fb().message}
          </div>
        )}
      </Show>

      {/* Tab content */}
      <div class="p-4">
        <Show when={activeTab() === 'skills'}>
          <SkillTaskForm
            onSubmit={handleSubmitTask}
            playerSkills={props.playerSkills}
            submitting={submitting()}
          />
        </Show>
        <Show when={activeTab() === 'combat'}>
          <CombatTaskForm
            onSubmit={handleSubmitTask}
            submitting={submitting()}
          />
        </Show>
        <Show when={activeTab() === 'navigation'}>
          <NavigationTaskForm
            onSubmit={handleSubmitTask}
            submitting={submitting()}
          />
        </Show>
        <Show when={activeTab() === 'quests'}>
          <QuestTaskForm
            onSubmit={handleSubmitTask}
            submitting={submitting()}
          />
        </Show>
      </div>
    </div>
  );
};

export default ManualTaskPanel;

