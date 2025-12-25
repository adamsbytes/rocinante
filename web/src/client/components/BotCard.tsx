import { type Component } from 'solid-js';
import { useNavigate } from '@tanstack/solid-router';
import type { BotWithStatus } from '../../shared/types';
import { StatusBadge } from './StatusBadge';
import { useStartBotMutation, useStopBotMutation } from '../lib/api';
import { openLogs } from '../lib/logsStore';

interface BotCardProps {
  bot: BotWithStatus;
}

export const BotCard: Component<BotCardProps> = (props) => {
  const navigate = useNavigate({ from: '/' });
  const startMutation = useStartBotMutation();
  const stopMutation = useStopBotMutation();

  const handleCardClick = () => {
    navigate({ to: '/bots/$id', params: { id: props.bot.id } });
  };

  const handleStart = (e: MouseEvent) => {
    e.stopPropagation();
    startMutation.mutate(props.bot.id);
  };

  const handleStop = (e: MouseEvent) => {
    e.stopPropagation();
    stopMutation.mutate(props.bot.id);
  };

  const handleShowLogs = (e: MouseEvent) => {
    e.stopPropagation();
    openLogs(props.bot.id);
  };

  const handleEdit = (e: MouseEvent) => {
    e.stopPropagation();
    navigate({ to: '/bots/$id/edit', params: { id: props.bot.id } });
  };

  const isRunning = () => props.bot.status.state === 'running';
  const isStarting = () => props.bot.status.state === 'starting' || startMutation.isPending;
  const isStopping = () => props.bot.status.state === 'stopping' || stopMutation.isPending;
  const isError = () => props.bot.status.state === 'error';

  return (
    <>
      <div
        onClick={handleCardClick}
        class="block bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl p-5 hover:border-[var(--accent)] transition-colors cursor-pointer"
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
        </div>

        <div class="flex gap-2">
          {isRunning() ? (
            <button
              onClick={handleStop}
              disabled={isStopping()}
              class="flex-1 px-3 py-2 bg-red-600 hover:bg-red-700 disabled:opacity-50 rounded-lg text-sm font-medium transition-colors"
            >
              {isStopping() ? 'Stopping...' : 'Stop'}
            </button>
          ) : isStarting() || isError() ? (
            <>
              <button
                onClick={handleStart}
                disabled={isStarting()}
                class="flex-1 px-3 py-2 bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50 rounded-lg text-sm font-medium transition-colors"
              >
                {isStarting() ? 'Starting...' : 'Retry'}
              </button>
              <button
                onClick={handleStop}
                disabled={isStopping()}
                class="px-3 py-2 bg-red-600 hover:bg-red-700 disabled:opacity-50 rounded-lg text-sm font-medium transition-colors"
                title="Force stop / cleanup"
              >
                {isStopping() ? '...' : 'Stop'}
              </button>
            </>
          ) : (
            <button
              onClick={handleStart}
              disabled={isStarting()}
              class="flex-1 px-3 py-2 bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50 rounded-lg text-sm font-medium transition-colors"
            >
              Start
            </button>
          )}
          <button
            onClick={handleShowLogs}
            class="px-3 py-2 bg-[var(--bg-tertiary)] hover:bg-zinc-700 rounded-lg text-sm font-medium transition-colors"
          >
            Logs
          </button>
          <button
            onClick={handleEdit}
            class="px-3 py-2 bg-[var(--bg-tertiary)] hover:bg-zinc-700 rounded-lg text-sm font-medium transition-colors"
          >
            Edit
          </button>
        </div>
      </div>
    </>
  );
};
