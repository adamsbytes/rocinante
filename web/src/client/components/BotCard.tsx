import { type Component, createSignal, Show } from 'solid-js';
import { Link } from '@tanstack/solid-router';
import type { BotWithStatus } from '../../shared/types';
import { StatusBadge } from './StatusBadge';
import { LogsViewer } from './LogsViewer';
import { useStartBotMutation, useStopBotMutation } from '../lib/api';

interface BotCardProps {
  bot: BotWithStatus;
}

export const BotCard: Component<BotCardProps> = (props) => {
  const startMutation = useStartBotMutation();
  const stopMutation = useStopBotMutation();
  const [showLogs, setShowLogs] = createSignal(false);

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

  const handleShowLogs = (e: Event) => {
    e.preventDefault();
    e.stopPropagation();
    setShowLogs(true);
  };

  const isRunning = () => props.bot.status.state === 'running';
  const isStarting = () => props.bot.status.state === 'starting' || startMutation.isPending;
  const isStopping = () => props.bot.status.state === 'stopping' || stopMutation.isPending;
  const isError = () => props.bot.status.state === 'error';
  const hasContainer = () => !!props.bot.status.containerId;

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
          /* Running state: full-width stop button */
          <button
            onClick={handleStop}
            disabled={isStopping()}
            class="flex-1 px-3 py-2 bg-red-600 hover:bg-red-700 disabled:opacity-50 rounded-lg text-sm font-medium transition-colors"
          >
            {isStopping() ? 'Stopping...' : 'Stop'}
          </button>
        ) : isStarting() || isError() ? (
          /* Starting or Error state: half-width start + stop buttons */
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
          /* Stopped state: full-width start button */
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
        <Link
          to="/bots/$id/edit"
          params={{ id: props.bot.id }}
          onClick={(e: Event) => e.stopPropagation()}
          class="px-3 py-2 bg-[var(--bg-tertiary)] hover:bg-zinc-700 rounded-lg text-sm font-medium transition-colors"
        >
          Edit
        </Link>
      </div>

      {/* Logs Modal */}
      <Show when={showLogs()}>
        <LogsViewer botId={props.bot.id} onClose={() => setShowLogs(false)} />
      </Show>
    </Link>
  );
};

