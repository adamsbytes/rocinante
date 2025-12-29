import { type Component, Show, Suspense } from 'solid-js';
import { useNavigate, useParams } from '@tanstack/solid-router';
import { BotForm } from '../components/BotForm';
import { useBotQuery, useUpdateBotMutation } from '../lib/api';
import type { BotConfig } from '../../shared/types';

export const BotEdit: Component = () => {
  const params = useParams({ from: '/bots/$id/edit' });
  const navigate = useNavigate();
  const botQuery = useBotQuery(() => params().id);
  const updateMutation = useUpdateBotMutation();

  const handleSubmit = async (data: Omit<BotConfig, 'id' | 'vncPort'>) => {
    try {
      await updateMutation.mutateAsync({ id: params().id, ...data });
      navigate({ to: '/bots/$id', params: { id: params().id } });
    } catch (err) {
      // Error is handled by the mutation
    }
  };

  return (
    <div>
      <h2 class="text-2xl font-bold mb-6">Edit Bot</h2>
      <Suspense fallback={<LoadingSkeleton />}>
        <Show
          when={botQuery.data}
          fallback={
            <Show when={botQuery.isError}>
              <p class="text-red-400">Failed to load bot configuration</p>
            </Show>
          }
        >
          {(bot) => (
            <BotForm
              initialData={bot()}
              onSubmit={handleSubmit}
              isLoading={updateMutation.isPending}
              submitLabel="Save Changes"
            />
          )}
        </Show>
      </Suspense>
      {updateMutation.isError && (
        <p class="mt-4 text-red-400">
          Error: {updateMutation.error?.message || 'Failed to update bot'}
        </p>
      )}
    </div>
  );
};

const LoadingSkeleton: Component = () => (
  <div class="space-y-6 max-w-2xl animate-pulse">
    <div class="space-y-4">
      <div class="h-6 bg-[var(--bg-tertiary)] rounded w-1/4" />
      <div class="h-10 bg-[var(--bg-tertiary)] rounded" />
      <div class="grid grid-cols-2 gap-4">
        <div class="h-10 bg-[var(--bg-tertiary)] rounded" />
        <div class="h-10 bg-[var(--bg-tertiary)] rounded" />
      </div>
    </div>
  </div>
);

