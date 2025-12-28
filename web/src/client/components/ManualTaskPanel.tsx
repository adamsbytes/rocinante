import { type Component, createSignal, Show, For, Switch, Match, Suspense } from 'solid-js';
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
  /** Status store for sending commands - optional when disabled */
  statusStore?: StatusStore;
  /** Current player level (for skill method filtering) */
  playerSkills?: Record<string, { level: number }>;
  /** Class name for the container */
  class?: string;
  /** When true, shows a disabled/offline state */
  disabled?: boolean;
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
    if (props.disabled || !props.statusStore) return;
    
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
    if (props.disabled || !props.statusStore) return;
    props.statusStore.sendCommand({ type: 'ABORT_TASK' });
    setFeedback({ type: 'success', message: 'Abort command sent' });
    setTimeout(() => setFeedback(null), 3000);
  };

  /**
   * Clear the task queue.
   */
  const handleClearQueue = () => {
    if (props.disabled || !props.statusStore) return;
    props.statusStore.sendCommand({ type: 'CLEAR_QUEUE' });
    setFeedback({ type: 'success', message: 'Queue cleared' });
    setTimeout(() => setFeedback(null), 3000);
  };

  /**
   * Force a break.
   */
  const handleForceBreak = () => {
    if (props.disabled || !props.statusStore) return;
    props.statusStore.sendCommand({ type: 'FORCE_BREAK' });
    setFeedback({ type: 'success', message: 'Break scheduled' });
    setTimeout(() => setFeedback(null), 3000);
  };

  return (
    <div class={`bg-gray-800/60 rounded-lg border border-gray-700 ${props.disabled ? 'opacity-50' : ''} ${props.class || ''}`}>
      {/* Header with title and control buttons */}
      <div class="flex items-center justify-between px-4 py-3 border-b border-gray-700">
        <h3 class="text-lg font-semibold text-gray-100 flex items-center gap-2">
          <span class="text-xl">📋</span>
          Manual Task Control
          <Show when={props.disabled}>
            <span class="text-xs px-2 py-0.5 rounded bg-gray-700 text-gray-500">Offline</span>
          </Show>
        </h3>
        <div class="flex gap-2">
          <button
            onClick={handleForceBreak}
            disabled={props.disabled}
            class="px-3 py-1.5 text-sm bg-amber-600/20 hover:bg-amber-600/40 text-amber-400 border border-amber-600/50 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-amber-600/20"
            title={props.disabled ? 'Bot is offline' : 'Schedule a break'}
          >
            ☕ Break
          </button>
          <button
            onClick={handleAbortTask}
            disabled={props.disabled}
            class="px-3 py-1.5 text-sm bg-orange-600/20 hover:bg-orange-600/40 text-orange-400 border border-orange-600/50 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-orange-600/20"
            title={props.disabled ? 'Bot is offline' : 'Abort current task'}
          >
            ⏹ Abort
          </button>
          <button
            onClick={handleClearQueue}
            disabled={props.disabled}
            class="px-3 py-1.5 text-sm bg-red-600/20 hover:bg-red-600/40 text-red-400 border border-red-600/50 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-red-600/20"
            title={props.disabled ? 'Bot is offline' : 'Clear task queue'}
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
              onClick={() => !props.disabled && setActiveTab(tab.id)}
              disabled={props.disabled}
              class={`flex-1 px-4 py-3 text-sm font-medium transition-colors disabled:cursor-not-allowed ${
                activeTab() === tab.id
                  ? 'bg-gray-700/50 text-white border-b-2 border-amber-500'
                  : 'text-gray-400 hover:text-gray-200 hover:bg-gray-700/30 disabled:hover:bg-transparent disabled:hover:text-gray-400'
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

      {/* Tab content - Suspense prevents resource loading from bubbling up to page-level Suspense */}
      <div class="p-4 min-h-[400px]">
        <Show
          when={!props.disabled}
          fallback={
            <div class="flex flex-col items-center justify-center h-[350px] text-center">
              <div class="text-4xl mb-3">💤</div>
              <p class="text-gray-400 text-lg">Bot is offline</p>
              <p class="text-gray-500 text-sm mt-2">
                Start the bot to queue tasks
              </p>
            </div>
          }
        >
          <Suspense fallback={<div class="text-gray-400 text-sm py-4">Loading...</div>}>
            <Switch>
              <Match when={activeTab() === 'skills'}>
            <SkillTaskForm
              onSubmit={handleSubmitTask}
              playerSkills={props.playerSkills}
              submitting={submitting()}
            />
              </Match>
              <Match when={activeTab() === 'combat'}>
            <CombatTaskForm
              onSubmit={handleSubmitTask}
              submitting={submitting()}
            />
              </Match>
              <Match when={activeTab() === 'navigation'}>
            <NavigationTaskForm
              onSubmit={handleSubmitTask}
              submitting={submitting()}
            />
              </Match>
              <Match when={activeTab() === 'quests'}>
            <QuestTaskForm
              onSubmit={handleSubmitTask}
              submitting={submitting()}
            />
              </Match>
            </Switch>
          </Suspense>
        </Show>
      </div>
    </div>
  );
};

export default ManualTaskPanel;

