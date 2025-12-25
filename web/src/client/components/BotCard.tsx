import type { Component } from 'solid-js';
import { Link } from '@tanstack/solid-router';
import type { BotWithStatus } from '../../shared/types';
import { StatusBadge } from './StatusBadge';
import { useStartBotMutation, useStopBotMutation } from '../lib/api';

interface BotCardProps {
  bot: BotWithStatus;
}

export const BotCard: Component<BotCardProps> = (props) => {
  const startMutation = useStartBotMutation();
  const stopMutation = useStopBotMutation();

  const handleStart = (e: Event) => {
    e.preventDefault();
    e.stopPropagation();
    startMutation.mutate(props.bot.id);
  };

  const handleStop = (e: Event) => {
    e.preventDefault();
    e.stopPropagation();
    stopMutation.mutate(props.bot.id);
  };

  const isRunning = () => props.bot.status.state === 'running';
  const isLoading = () =>
    props.bot.status.state === 'starting' ||
    props.bot.status.state === 'stopping' ||
    startMutation.isPending ||
    stopMutation.isPending;

  return (
    <Link
      to="/bots/$id"
      params={{ id: props.bot.id }}
      class="block bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl p-5 hover:border-[var(--accent)] transition-colors"
    >
      <div class="flex items-start justify-between mb-4">
        <div>
          <h3 class="font-semibold text-lg">{props.bot.name}</h3>
          <p class="text-[var(--text-secondary)] text-sm">{props.bot.username}</p>
        </div>
        <StatusBadge state={props.bot.status.state} />
      </div>

      <div class="text-sm text-[var(--text-secondary)] mb-4 space-y-1">
        {props.bot.proxy && (
          <p>Proxy: {props.bot.proxy.host}:{props.bot.proxy.port}</p>
        )}
        {props.bot.ironman.enabled && (
          <p>Mode: {props.bot.ironman.type?.replace('_', ' ')}</p>
        )}
        <p>VNC: :{props.bot.vncPort}</p>
      </div>

      <div class="flex gap-2">
        {isRunning() ? (
          <button
            onClick={handleStop}
            disabled={isLoading()}
            class="flex-1 px-3 py-2 bg-red-600 hover:bg-red-700 disabled:opacity-50 rounded-lg text-sm font-medium transition-colors"
          >
            {isLoading() ? 'Stopping...' : 'Stop'}
          </button>
        ) : (
          <button
            onClick={handleStart}
            disabled={isLoading()}
            class="flex-1 px-3 py-2 bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50 rounded-lg text-sm font-medium transition-colors"
          >
            {isLoading() ? 'Starting...' : 'Start'}
          </button>
        )}
        <Link
          to="/bots/$id/edit"
          params={{ id: props.bot.id }}
          onClick={(e: Event) => e.stopPropagation()}
          class="px-3 py-2 bg-[var(--bg-tertiary)] hover:bg-zinc-700 rounded-lg text-sm font-medium transition-colors"
        >
          Edit
        </Link>
      </div>
    </Link>
  );
};

