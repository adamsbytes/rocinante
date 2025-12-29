import type { Component } from 'solid-js';

interface StatusBadgeProps {
  state: 'stopped' | 'running' | 'starting' | 'stopping' | 'error';
}

const STATUS_CONFIG = {
  stopped: { dot: 'bg-gray-500', text: 'text-gray-400', label: 'Offline' },
  running: { dot: 'bg-emerald-500', text: 'text-emerald-400', label: 'Running' },
  starting: { dot: 'bg-amber-500 animate-pulse', text: 'text-amber-400', label: 'Starting' },
  stopping: { dot: 'bg-amber-500 animate-pulse', text: 'text-amber-400', label: 'Stopping' },
  error: { dot: 'bg-red-500', text: 'text-red-400', label: 'Error' },
};

export const StatusBadge: Component<StatusBadgeProps> = (props) => {
  const config = () => STATUS_CONFIG[props.state];

  return (
    <span class="flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full bg-gray-800/80">
      <span class={`w-2 h-2 rounded-full ${config().dot}`} />
      <span class={`font-medium ${config().text}`}>{config().label}</span>
    </span>
  );
};

