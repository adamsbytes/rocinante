import { Component, Show, For, createSignal, createMemo } from 'solid-js';
import type { TaskInfo, QueueInfo } from '../../shared/types';
import { formatDuration } from '../../shared/types';

interface CurrentTaskProps {
  task: TaskInfo | null;
  queue: QueueInfo | null;
  class?: string;
}

/**
 * Map task states to display properties.
 */
const TASK_STATE_DISPLAY: Record<string, { color: string; label: string; icon: string }> = {
  PENDING: { color: 'text-yellow-400', label: 'Pending', icon: '⏳' },
  RUNNING: { color: 'text-green-400', label: 'Running', icon: '▶️' },
  COMPLETED: { color: 'text-blue-400', label: 'Completed', icon: '✓' },
  FAILED: { color: 'text-red-400', label: 'Failed', icon: '✗' },
  ABORTED: { color: 'text-orange-400', label: 'Aborted', icon: '⊘' },
  WAITING: { color: 'text-cyan-400', label: 'Waiting', icon: '⏸' },
};

/**
 * Current task display component with collapsible details.
 */
export const CurrentTask: Component<CurrentTaskProps> = (props) => {
  const [expanded, setExpanded] = createSignal(false);

  const stateDisplay = createMemo(() => {
    if (!props.task) return null;
    return TASK_STATE_DISPLAY[props.task.state] || {
      color: 'text-gray-400',
      label: props.task.state,
      icon: '•',
    };
  });

  const hasProgress = createMemo(() => {
    return props.task && props.task.progress >= 0 && props.task.progress <= 1;
  });

  const progressPercent = createMemo(() => {
    if (!hasProgress()) return 0;
    return Math.round((props.task?.progress || 0) * 100);
  });

  const hasSubtasks = createMemo(() => {
    return props.task && props.task.subtasks && props.task.subtasks.length > 0;
  });

  const hasQueue = createMemo(() => {
    return props.queue && props.queue.pending > 0;
  });

  return (
    <div class={`bg-gray-800/60 rounded-lg border border-gray-700 ${props.class || ''}`}>
      {/* Header */}
      <div
        class="flex items-center justify-between px-4 py-3 cursor-pointer hover:bg-gray-700/30 transition-colors rounded-t-lg"
        onClick={() => setExpanded(!expanded())}
      >
        <div class="flex items-center gap-3">
          {/* Task indicator */}
          <div class="w-8 h-8 rounded-full bg-gray-700 flex items-center justify-center">
            <Show when={props.task} fallback={<span class="text-gray-500">—</span>}>
              <span class="text-lg">{stateDisplay()?.icon}</span>
            </Show>
          </div>

          {/* Task description */}
          <div class="flex flex-col">
            <span class="font-semibold text-gray-100">
              <Show when={props.task} fallback="No Active Task">
                {props.task!.description}
              </Show>
            </span>
            <Show when={props.task}>
              <div class="flex items-center gap-2 text-sm">
                <span class={stateDisplay()?.color}>{stateDisplay()?.label}</span>
                <Show when={props.task!.elapsedMs > 0}>
                  <span class="text-gray-500">•</span>
                  <span class="text-gray-400">{formatDuration(props.task!.elapsedMs)}</span>
                </Show>
              </div>
            </Show>
          </div>
        </div>

        {/* Queue badge and expand arrow */}
        <div class="flex items-center gap-3">
          <Show when={hasQueue()}>
            <div class="px-2 py-1 rounded bg-gray-700 text-xs text-gray-300">
              {props.queue!.pending} queued
            </div>
          </Show>
          <svg
            class={`w-5 h-5 text-gray-400 transform transition-transform ${expanded() ? 'rotate-180' : ''}`}
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
          </svg>
        </div>
      </div>

      {/* Progress bar */}
      <Show when={hasProgress()}>
        <div class="px-4 pb-2">
          <div class="h-1.5 bg-gray-700 rounded-full overflow-hidden">
            <div
              class="h-full bg-gradient-to-r from-green-500 to-emerald-400 transition-all duration-300 ease-out"
              style={{ width: `${progressPercent()}%` }}
            />
          </div>
          <div class="flex justify-between text-xs text-gray-500 mt-1">
            <span>Progress</span>
            <span>{progressPercent()}%</span>
          </div>
        </div>
      </Show>

      {/* Expanded details */}
      <Show when={expanded()}>
        <div class="px-4 pb-4 border-t border-gray-700 mt-2 pt-3 space-y-3">
          {/* Subtasks */}
          <Show when={hasSubtasks()}>
            <div>
              <h4 class="text-sm font-medium text-gray-400 mb-2">Subtasks</h4>
              <ul class="space-y-1.5">
                <For each={props.task!.subtasks}>
                  {(subtask, index) => (
                    <li class="flex items-center gap-2 text-sm text-gray-300">
                      <span class="w-5 h-5 rounded-full bg-gray-700 flex items-center justify-center text-xs text-gray-400">
                        {index() + 1}
                      </span>
                      {subtask}
                    </li>
                  )}
                </For>
              </ul>
            </div>
          </Show>

          {/* Task Queue */}
          <Show when={hasQueue()}>
            <div>
              <h4 class="text-sm font-medium text-gray-400 mb-2">Queue ({props.queue!.pending} tasks)</h4>
              <ul class="space-y-1.5">
                <For each={props.queue!.descriptions}>
                  {(desc, index) => (
                    <li class="flex items-center gap-2 text-sm text-gray-300">
                      <span class="w-5 h-5 rounded-full bg-gray-700/50 flex items-center justify-center text-xs text-gray-500">
                        {index() + 1}
                      </span>
                      {desc}
                    </li>
                  )}
                </For>
                <Show when={props.queue!.pending > props.queue!.descriptions.length}>
                  <li class="text-sm text-gray-500 italic pl-7">
                    +{props.queue!.pending - props.queue!.descriptions.length} more...
                  </li>
                </Show>
              </ul>
            </div>
          </Show>

          {/* No details message */}
          <Show when={!hasSubtasks() && !hasQueue()}>
            <p class="text-sm text-gray-500 italic">No additional details available</p>
          </Show>
        </div>
      </Show>
    </div>
  );
};

export default CurrentTask;

