import { type Component, For, Show, Suspense } from 'solid-js';
import { Link } from '@tanstack/solid-router';
import { useBotsQuery } from '../lib/api';
import { BotCard } from '../components/BotCard';

export const Dashboard: Component = () => {
  const botsQuery = useBotsQuery();

  return (
    <div>
      <div class="flex items-center justify-between mb-6">
        <h2 class="text-2xl font-bold">Bot Dashboard</h2>
        <Link
          to="/bots/new"
          class="px-4 py-2 bg-[var(--accent)] hover:bg-[var(--accent-hover)] rounded-lg font-medium transition-colors"
        >
          + Add Bot
        </Link>
      </div>

      <Suspense fallback={<LoadingSkeleton />}>
        <Show
          when={!botsQuery.isError}
          fallback={
            <div class="text-center py-12">
              <p class="text-red-400 mb-4">Failed to load bots</p>
              <button
                onClick={() => botsQuery.refetch()}
                class="px-4 py-2 bg-[var(--bg-tertiary)] hover:bg-zinc-700 rounded-lg transition-colors"
              >
                Retry
              </button>
            </div>
          }
        >
          <Show
            when={botsQuery.data && botsQuery.data.length > 0}
            fallback={
              <div class="text-center py-12">
                <p class="text-[var(--text-secondary)] mb-4">No bots configured yet</p>
                <Link
                  to="/bots/new"
                  class="inline-block px-4 py-2 bg-[var(--accent)] hover:bg-[var(--accent-hover)] rounded-lg font-medium transition-colors"
                >
                  Add Your First Bot
                </Link>
              </div>
            }
          >
            <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              <For each={botsQuery.data}>
                {(bot) => <BotCard bot={bot} />}
              </For>
            </div>
          </Show>
        </Show>
      </Suspense>
    </div>
  );
};

const LoadingSkeleton: Component = () => (
  <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
    <For each={[1, 2, 3]}>
      {() => (
        <div class="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl p-5 animate-pulse">
          <div class="h-6 bg-[var(--bg-tertiary)] rounded w-1/2 mb-2" />
          <div class="h-4 bg-[var(--bg-tertiary)] rounded w-1/3 mb-4" />
          <div class="space-y-2 mb-4">
            <div class="h-3 bg-[var(--bg-tertiary)] rounded w-2/3" />
            <div class="h-3 bg-[var(--bg-tertiary)] rounded w-1/2" />
          </div>
          <div class="flex gap-2">
            <div class="h-10 bg-[var(--bg-tertiary)] rounded flex-1" />
            <div class="h-10 bg-[var(--bg-tertiary)] rounded w-16" />
          </div>
        </div>
      )}
    </For>
  </div>
);

