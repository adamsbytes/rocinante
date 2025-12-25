import type { Component } from 'solid-js';

interface StatusBadgeProps {
  state: 'stopped' | 'running' | 'starting' | 'stopping' | 'error';
}

export const StatusBadge: Component<StatusBadgeProps> = (props) => {
  const colors = {
    stopped: 'bg-zinc-600 text-zinc-200',
    running: 'bg-emerald-600 text-emerald-100',
    starting: 'bg-amber-600 text-amber-100',
    stopping: 'bg-amber-600 text-amber-100',
    error: 'bg-red-600 text-red-100',
  };

  return (
    <span class={`px-2 py-1 rounded text-xs font-medium uppercase ${colors[props.state]}`}>
      {props.state}
    </span>
  );
};

