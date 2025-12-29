import type { Component } from 'solid-js';
import { useNavigate } from '@tanstack/solid-router';
import { BotForm } from '../components/BotForm';
import { useCreateBotMutation } from '../lib/api';
import type { BotConfig } from '../../shared/types';

export const BotNew: Component = () => {
  const navigate = useNavigate();
  const createMutation = useCreateBotMutation();

  const handleSubmit = async (data: Omit<BotConfig, 'id' | 'vncPort'>) => {
    try {
      await createMutation.mutateAsync(data);
      navigate({ to: '/' });
    } catch (err) {
      // Error is handled by the mutation
    }
  };

  return (
    <div>
      <h2 class="text-2xl font-bold mb-6">Add New Bot</h2>
      <BotForm
        onSubmit={handleSubmit}
        isLoading={createMutation.isPending}
        submitLabel="Create Bot"
      />
      {createMutation.isError && (
        <p class="mt-4 text-red-400">
          Error: {createMutation.error?.message || 'Failed to create bot'}
        </p>
      )}
    </div>
  );
};

