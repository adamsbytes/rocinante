import { type Component, Show, Suspense, createSignal } from 'solid-js';
import { Link, useParams } from '@tanstack/solid-router';
import { useBotQuery, useStartBotMutation, useStopBotMutation, useRestartBotMutation, useDeleteBotMutation } from '../lib/api';
import { StatusBadge } from '../components/StatusBadge';
import { VncViewer } from '../components/VncViewer';
import { LogsViewer } from '../components/LogsViewer';

export const BotDetail: Component = () => {
  const params = useParams();
  const botQuery = useBotQuery(() => params.id);
  const startMutation = useStartBotMutation();
  const stopMutation = useStopBotMutation();
  const restartMutation = useRestartBotMutation();
  const deleteMutation = useDeleteBotMutation();
  const [showDeleteConfirm, setShowDeleteConfirm] = createSignal(false);
  const [showLogs, setShowLogs] = createSignal(false);

  const isRunning = () => botQuery.data?.status.state === 'running';
  const isStarting = () => botQuery.data?.status.state === 'starting' || startMutation.isPending;
  const isStopping = () => botQuery.data?.status.state === 'stopping' || stopMutation.isPending;
  const isError = () => botQuery.data?.status.state === 'error';
  const isRestarting = () => restartMutation.isPending;

  const handleDelete = async () => {
    try {
      await deleteMutation.mutateAsync(params.id);
      window.location.href = '/';
    } catch (err) {
      // Error handled by mutation
    }
  };

  return (
    <div>
      <Suspense fallback={<LoadingSkeleton />}>
        <Show
          when={botQuery.data}
          fallback={
            <Show when={botQuery.isError}>
              <p class="text-red-400">Failed to load bot</p>
            </Show>
          }
        >
          {(bot) => (
            <>
              {/* Header */}
              <div class="flex items-start justify-between mb-6">
                <div>
                  <div class="flex items-center gap-3 mb-2">
                    <h2 class="text-2xl font-bold">{bot().name}</h2>
                    <StatusBadge state={bot().status.state} />
                  </div>
                  <p class="text-[var(--text-secondary)]">{bot().username}</p>
                </div>
                <div class="flex gap-2">
                  {isRunning() ? (
                    /* Running state: restart + stop buttons */
                    <>
                      <button
                        onClick={() => restartMutation.mutate(params.id)}
                        disabled={isRestarting() || isStopping()}
                        class="px-4 py-2 bg-amber-600 hover:bg-amber-700 disabled:opacity-50 rounded-lg font-medium transition-colors"
                      >
                        {isRestarting() ? 'Restarting...' : 'Restart'}
                      </button>
                      <button
                        onClick={() => stopMutation.mutate(params.id)}
                        disabled={isStopping()}
                        class="px-4 py-2 bg-red-600 hover:bg-red-700 disabled:opacity-50 rounded-lg font-medium transition-colors"
                      >
                        {isStopping() ? 'Stopping...' : 'Stop'}
                      </button>
                    </>
                  ) : isStarting() || isError() ? (
                    /* Starting or Error state: start/retry + stop buttons */
                    <>
                      <button
                        onClick={() => startMutation.mutate(params.id)}
                        disabled={isStarting()}
                        class="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50 rounded-lg font-medium transition-colors"
                      >
                        {isStarting() ? 'Starting...' : 'Retry'}
                      </button>
                      <button
                        onClick={() => stopMutation.mutate(params.id)}
                        disabled={isStopping()}
                        class="px-4 py-2 bg-red-600 hover:bg-red-700 disabled:opacity-50 rounded-lg font-medium transition-colors"
                        title="Force stop / cleanup container"
                      >
                        {isStopping() ? 'Stopping...' : 'Stop'}
                      </button>
                    </>
                  ) : (
                    /* Stopped state: start button only */
                    <button
                      onClick={() => startMutation.mutate(params.id)}
                      disabled={isStarting()}
                      class="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50 rounded-lg font-medium transition-colors"
                    >
                      Start
                    </button>
                  )}
                  <button
                    onClick={() => setShowLogs(true)}
                    class="px-4 py-2 bg-[var(--bg-tertiary)] hover:bg-zinc-700 rounded-lg font-medium transition-colors"
                  >
                    Logs
                  </button>
                  <Link
                    to="/bots/$id/edit"
                    params={{ id: params.id }}
                    class="px-4 py-2 bg-[var(--bg-tertiary)] hover:bg-zinc-700 rounded-lg font-medium transition-colors"
                  >
                    Edit
                  </Link>
                </div>
              </div>

              {/* Logs Modal */}
              <Show when={showLogs()}>
                <LogsViewer botId={params.id} onClose={() => setShowLogs(false)} />
              </Show>

              {/* VNC Viewer */}
              <Show when={isRunning()}>
                <div class="mb-6">
                  <h3 class="text-lg font-semibold mb-3">Live View</h3>
                  <VncViewer host="localhost" port={bot().vncPort} />
                </div>
              </Show>

              {/* Bot Details */}
              <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
                {/* Configuration */}
                <div class="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl p-5">
                  <h3 class="text-lg font-semibold mb-4">Configuration</h3>
                  <dl class="space-y-3 text-sm">
                    <div class="flex justify-between">
                      <dt class="text-[var(--text-secondary)]">VNC Port</dt>
                      <dd>{bot().vncPort}</dd>
                    </div>
                    <Show when={bot().proxy}>
                      <div class="flex justify-between">
                        <dt class="text-[var(--text-secondary)]">Proxy</dt>
                        <dd>{bot().proxy!.host}:{bot().proxy!.port}</dd>
                      </div>
                    </Show>
                    <Show when={bot().ironman.enabled}>
                      <div class="flex justify-between">
                        <dt class="text-[var(--text-secondary)]">Ironman Mode</dt>
                        <dd>{bot().ironman.type?.replace('_', ' ')}</dd>
                      </div>
                      <Show when={bot().ironman.hcimSafetyLevel}>
                        <div class="flex justify-between">
                          <dt class="text-[var(--text-secondary)]">HCIM Safety</dt>
                          <dd>{bot().ironman.hcimSafetyLevel}</dd>
                        </div>
                      </Show>
                    </Show>
                    <div class="flex justify-between">
                      <dt class="text-[var(--text-secondary)]">CPU Limit</dt>
                      <dd>{bot().resources.cpuLimit}</dd>
                    </div>
                    <div class="flex justify-between">
                      <dt class="text-[var(--text-secondary)]">Memory Limit</dt>
                      <dd>{bot().resources.memoryLimit}</dd>
                    </div>
                  </dl>
                </div>

                {/* Status */}
                <div class="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl p-5">
                  <h3 class="text-lg font-semibold mb-4">Status</h3>
                  <dl class="space-y-3 text-sm">
                    <div class="flex justify-between">
                      <dt class="text-[var(--text-secondary)]">State</dt>
                      <dd><StatusBadge state={bot().status.state} /></dd>
                    </div>
                    <Show when={bot().status.containerId}>
                      <div class="flex justify-between">
                        <dt class="text-[var(--text-secondary)]">Container ID</dt>
                        <dd class="font-mono text-xs">{bot().status.containerId?.slice(0, 12)}</dd>
                      </div>
                    </Show>
                    <Show when={bot().status.error}>
                      <div>
                        <dt class="text-[var(--text-secondary)] mb-1">Error</dt>
                        <dd class="text-red-400 text-xs">{bot().status.error}</dd>
                      </div>
                    </Show>
                  </dl>
                </div>
              </div>

              {/* Danger Zone */}
              <div class="mt-8 bg-red-950/30 border border-red-900/50 rounded-xl p-5">
                <h3 class="text-lg font-semibold text-red-400 mb-4">Danger Zone</h3>
                <Show
                  when={!showDeleteConfirm()}
                  fallback={
                    <div class="flex items-center gap-4">
                      <p class="text-[var(--text-secondary)]">Are you sure? This cannot be undone.</p>
                      <button
                        onClick={handleDelete}
                        disabled={deleteMutation.isPending}
                        class="px-4 py-2 bg-red-600 hover:bg-red-700 disabled:opacity-50 rounded-lg font-medium transition-colors"
                      >
                        {deleteMutation.isPending ? 'Deleting...' : 'Yes, Delete'}
                      </button>
                      <button
                        onClick={() => setShowDeleteConfirm(false)}
                        class="px-4 py-2 bg-[var(--bg-tertiary)] hover:bg-zinc-700 rounded-lg font-medium transition-colors"
                      >
                        Cancel
                      </button>
                    </div>
                  }
                >
                  <button
                    onClick={() => setShowDeleteConfirm(true)}
                    class="px-4 py-2 bg-red-600/20 hover:bg-red-600/30 text-red-400 border border-red-600/50 rounded-lg font-medium transition-colors"
                  >
                    Delete Bot
                  </button>
                </Show>
              </div>
            </>
          )}
        </Show>
      </Suspense>
    </div>
  );
};

const LoadingSkeleton: Component = () => (
  <div class="animate-pulse">
    <div class="flex justify-between mb-6">
      <div>
        <div class="h-8 bg-[var(--bg-tertiary)] rounded w-48 mb-2" />
        <div class="h-4 bg-[var(--bg-tertiary)] rounded w-32" />
      </div>
      <div class="flex gap-2">
        <div class="h-10 bg-[var(--bg-tertiary)] rounded w-20" />
        <div class="h-10 bg-[var(--bg-tertiary)] rounded w-16" />
      </div>
    </div>
    <div class="h-96 bg-[var(--bg-tertiary)] rounded-xl mb-6" />
    <div class="grid grid-cols-2 gap-6">
      <div class="h-48 bg-[var(--bg-tertiary)] rounded-xl" />
      <div class="h-48 bg-[var(--bg-tertiary)] rounded-xl" />
    </div>
  </div>
);

