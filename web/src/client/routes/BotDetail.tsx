import { type Component, Show, Suspense, createSignal, createEffect, onCleanup, createMemo } from 'solid-js';
import { Link, useParams } from '@tanstack/solid-router';
import { useBotQuery, useStartBotMutation, useStopBotMutation, useRestartBotMutation, useDeleteBotMutation } from '../lib/api';
import { StatusBadge } from '../components/StatusBadge';
import { VncViewer, type VncStatus } from '../components/VncViewer';
import { LogsViewer } from '../components/LogsViewer';
import { createBotStatusStore } from '../lib/statusStore';
import { createPersistedSignal } from '../lib/persistedSignal';
import { CurrentTask } from '../components/CurrentTask';
import { SessionStatsPanel, InlineSessionStats } from '../components/SessionStatsPanel';
import { AccountStatsGrid } from '../components/AccountStatsGrid';
import { AccountStatsHiscores } from '../components/AccountStatsHiscores';
import { ManualTaskPanel } from '../components/ManualTaskPanel';
import { ScreenshotsGallery } from '../components/ScreenshotsGallery';

type StatsView = 'grid' | 'table';

export const BotDetail: Component = () => {
  const params = useParams({ from: '/bots/$id' });
  const botQuery = useBotQuery(() => params().id);
  const startMutation = useStartBotMutation();
  const stopMutation = useStopBotMutation();
  const restartMutation = useRestartBotMutation();
  const deleteMutation = useDeleteBotMutation();
  const [showDeleteConfirm, setShowDeleteConfirm] = createSignal(false);
  const [showLogs, setShowLogs] = createSignal(false);
  const [vncStatus, setVncStatus] = createSignal<VncStatus>('connecting');
  const [vncError, setVncError] = createSignal<string | null>(null);
  const [statsView, setStatsView] = createPersistedSignal<StatsView>('stats-view', 'grid');

  const handleVncStatusChange = (status: VncStatus, error?: string | null) => {
    setVncStatus(status);
    setVncError(error ?? null);
  };

  const isRunning = () => botQuery.data?.status.state === 'running';
  const isStarting = () => botQuery.data?.status.state === 'starting' || startMutation.isPending;
  const isStopping = () => botQuery.data?.status.state === 'stopping' || stopMutation.isPending;
  const isError = () => botQuery.data?.status.state === 'error';
  const isRestarting = () => restartMutation.isPending;

  // Status store - created once on mount, like VncViewer
  // Using refs to avoid effect-based lifecycle which causes disconnects on isRunning() flickers
  let statusStoreRef: ReturnType<typeof createBotStatusStore> | null = null;
  let statusStoreBotId: string | null = null;
  const [statusStore, setStatusStore] = createSignal<ReturnType<typeof createBotStatusStore> | null>(null);

  // Create status store for current bot - only recreate if botId changes
  createEffect(() => {
    const botId = params().id;
    
    // Only act if botId changed
    if (statusStoreBotId === botId) {
      return;
    }

    // Disconnect old store if exists
    if (statusStoreRef) {
      statusStoreRef.disconnect();
      statusStoreRef = null;
      setStatusStore(null);
    }

    // Create new store for this bot (regardless of running state - store handles reconnection)
    statusStoreRef = createBotStatusStore({ botId });
    statusStoreBotId = botId;
    setStatusStore(statusStoreRef);
  });

  // Cleanup status store on unmount
  onCleanup(() => {
    statusStoreRef?.disconnect();
  });

  // Derived status data
  const runtimeStatus = () => statusStore()?.status() || null;

  const handleDelete = async () => {
    try {
      await deleteMutation.mutateAsync(params().id);
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
              {/* Header - responsive: stacks on mobile */}
              <div class="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4 mb-6">
                <div class="min-w-0">
                  <div class="flex items-center gap-3 mb-2 flex-wrap">
                    <h2 class="text-xl sm:text-2xl font-bold truncate">
                      {bot().characterName}
                    </h2>
                    <StatusBadge state={bot().status.state} />
                  </div>
                  <div class="flex items-center gap-4 flex-wrap">
                    <p class="text-[var(--text-secondary)] text-sm sm:text-base truncate">{bot().username}</p>
                    {/* Inline session stats when running */}
                    <Show when={isRunning() && runtimeStatus()?.session}>
                      <InlineSessionStats session={runtimeStatus()!.session} />
                    </Show>
                  </div>
                </div>
                <div class="flex flex-wrap gap-2">
                  {isRunning() ? (
                    /* Running state: restart + stop buttons */
                    <>
                      <button
                        onClick={() => restartMutation.mutate(params().id)}
                        disabled={isRestarting() || isStopping()}
                        class="px-3 sm:px-4 py-2 bg-amber-600 hover:bg-amber-700 disabled:opacity-50 rounded-lg font-medium transition-colors text-sm sm:text-base"
                      >
                        {isRestarting() ? 'Restarting...' : 'Restart'}
                      </button>
                      <button
                        onClick={() => stopMutation.mutate(params().id)}
                        disabled={isStopping()}
                        class="px-3 sm:px-4 py-2 bg-red-600 hover:bg-red-700 disabled:opacity-50 rounded-lg font-medium transition-colors text-sm sm:text-base"
                      >
                        {isStopping() ? 'Stopping...' : 'Stop'}
                      </button>
                    </>
                  ) : isStarting() || isError() ? (
                    /* Starting or Error state: start/retry + stop buttons */
                    <>
                      <button
                        onClick={() => startMutation.mutate(params().id)}
                        disabled={isStarting()}
                        class="px-3 sm:px-4 py-2 bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50 rounded-lg font-medium transition-colors text-sm sm:text-base"
                      >
                        {isStarting() ? 'Starting...' : 'Retry'}
                      </button>
                      <button
                        onClick={() => stopMutation.mutate(params().id)}
                        disabled={isStopping()}
                        class="px-3 sm:px-4 py-2 bg-red-600 hover:bg-red-700 disabled:opacity-50 rounded-lg font-medium transition-colors text-sm sm:text-base"
                        title="Force stop / cleanup container"
                      >
                        {isStopping() ? 'Stopping...' : 'Stop'}
                      </button>
                    </>
                  ) : (
                    /* Stopped state: start button only */
                    <button
                      onClick={() => startMutation.mutate(params().id)}
                      disabled={isStarting()}
                      class="px-3 sm:px-4 py-2 bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50 rounded-lg font-medium transition-colors text-sm sm:text-base"
                    >
                      Start
                    </button>
                  )}
                  <button
                    onClick={() => setShowLogs(true)}
                    class="px-3 sm:px-4 py-2 bg-[var(--bg-tertiary)] hover:bg-zinc-700 rounded-lg font-medium transition-colors text-sm sm:text-base"
                  >
                    Logs
                  </button>
                  <Link
                    to="/bots/$id/edit"
                    params={{ id: params().id }}
                    class="px-3 sm:px-4 py-2 bg-[var(--bg-tertiary)] hover:bg-zinc-700 rounded-lg font-medium transition-colors text-sm sm:text-base"
                  >
                    Edit
                  </Link>
                </div>
              </div>

              {/* Logs Modal */}
              <Show when={showLogs()}>
                <LogsViewer botId={params().id} onClose={() => setShowLogs(false)} />
              </Show>

              {/* Screenshots gallery */}
              <ScreenshotsGallery botId={params().id} characterName={bot().characterName} />

              {/* VNC Viewer - always show, with offline placeholder when not running */}
              <div class="mb-6">
                <div class="flex items-center gap-3 mb-3">
                  <h3 class="text-lg font-semibold">Live View</h3>
                  {/* Only show status when there's an issue - connected state is implicit */}
                  <Show when={isRunning() && vncStatus() !== 'connected'}>
                    <span class="flex items-center gap-1.5 text-xs">
                      <span
                        class={`w-2 h-2 rounded-full ${
                          vncStatus() === 'connecting'
                            ? 'bg-amber-500 animate-pulse'
                            : 'bg-red-500'
                        }`}
                      />
                      <span class={
                        vncStatus() === 'connecting'
                          ? 'text-amber-400'
                          : 'text-red-400'
                      }>
                        {vncStatus() === 'connecting' && 'Connecting...'}
                        {vncStatus() === 'disconnected' && 'Reconnecting...'}
                        {vncStatus() === 'error' && (vncError() || 'Error')}
                      </span>
                    </span>
                  </Show>
                </div>
                <Show
                  when={isRunning()}
                  fallback={
                    <VncOfflinePlaceholder state={bot().status.state} />
                  }
                >
                  <VncViewer 
                    botId={params().id} 
                    shouldConnect={isRunning}
                    onStatusChange={handleVncStatusChange} 
                  />
                </Show>
              </div>

              {/* Real-time Status Section - always visible, disabled when offline */}
              <div class="mb-6 space-y-4">
                {/* Current Task */}
                <CurrentTask 
                  task={runtimeStatus()?.task || null}
                  queue={runtimeStatus()?.queue || null}
                  disabled={!isRunning()}
                />

                {/* Session Stats */}
                <SessionStatsPanel 
                  session={runtimeStatus()?.session || null} 
                  disabled={!isRunning()}
                />

                {/* Manual Task Panel */}
                <ManualTaskPanel 
                  statusStore={statusStore() || undefined}
                  playerSkills={runtimeStatus()?.player?.skills}
                  questsData={runtimeStatus()?.quests || null}
                  disabled={!isRunning()}
                />

                {/* Account Stats with view toggle */}
                <div class={!isRunning() ? 'opacity-50' : ''}>
                  <div class="flex items-center justify-between mb-3">
                    <h3 class="text-lg font-semibold flex items-center gap-2">
                      <span>Account Stats</span>
                      <Show when={isRunning() && runtimeStatus()?.gameState}>
                        <span class="text-xs px-2 py-0.5 rounded bg-gray-700 text-gray-300">
                          {runtimeStatus()!.gameState}
                        </span>
                      </Show>
                      <Show when={!isRunning()}>
                        <span class="text-xs px-2 py-0.5 rounded bg-gray-700 text-gray-500">
                          Offline
                        </span>
                      </Show>
                    </h3>
                    <div class="flex gap-1 bg-gray-800 rounded-lg p-1">
                      <button
                        onClick={() => setStatsView('grid')}
                        disabled={!isRunning()}
                        class={`px-3 py-1.5 rounded text-sm font-medium transition-colors ${
                          statsView() === 'grid'
                            ? 'bg-amber-600 text-white'
                            : 'text-gray-400 hover:text-white'
                        } disabled:cursor-not-allowed`}
                      >
                        Grid
                      </button>
                      <button
                        onClick={() => setStatsView('table')}
                        disabled={!isRunning()}
                        class={`px-3 py-1.5 rounded text-sm font-medium transition-colors ${
                          statsView() === 'table'
                            ? 'bg-amber-600 text-white'
                            : 'text-gray-400 hover:text-white'
                        } disabled:cursor-not-allowed`}
                      >
                        Table
                      </button>
                    </div>
                  </div>
                  <Show
                    when={statsView() === 'grid'}
                    fallback={
                      <AccountStatsHiscores 
                        player={runtimeStatus()?.player || null}
                        session={runtimeStatus()?.session || null}
                      />
                    }
                  >
                    <AccountStatsGrid player={runtimeStatus()?.player || null} />
                  </Show>
                </div>
              </div>

              {/* Bot Details */}
              <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
                {/* Configuration */}
                <div class="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl p-5">
                  <h3 class="text-lg font-semibold mb-4">Configuration</h3>
                  <dl class="space-y-3 text-sm">
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


/**
 * VNC Offline placeholder - shows when bot is not running.
 */
const VncOfflinePlaceholder: Component<{ state: string }> = (props) => {
  const stateText = () => {
    switch (props.state) {
      case 'stopped':
        return 'Bot is offline';
      case 'starting':
        return 'Bot is starting...';
      case 'stopping':
        return 'Bot is stopping...';
      case 'error':
        return 'Bot has an error';
      default:
        return 'Bot is offline';
    }
  };

  const stateIcon = () => {
    switch (props.state) {
      case 'starting':
        return '🚀';
      case 'stopping':
        return '⏳';
      case 'error':
        return '⚠️';
      default:
        return '💤';
    }
  };

  return (
    <div class="relative bg-black rounded-lg overflow-hidden aspect-video flex items-center justify-center">
      <div class="absolute inset-0 bg-gradient-to-br from-gray-900 via-gray-800 to-gray-900" />
      <div class="relative text-center">
        <div class="text-4xl mb-3">{stateIcon()}</div>
        <p class="text-gray-400 text-lg">{stateText()}</p>
        <p class="text-gray-500 text-sm mt-2">
          Start the bot to see the live view
        </p>
      </div>
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
