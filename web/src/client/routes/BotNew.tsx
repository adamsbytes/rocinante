import type { Component } from 'solid-js';
import { useNavigate } from '@tanstack/solid-router';
import { BotForm } from '../components/BotForm';
import { useCreateBotMutation, getErrorMessage } from '../lib/api';
import type { BotFormData } from '../../shared/botSchema';

export const BotNew: Component = () => {
  const navigate = useNavigate();
  const createMutation = useCreateBotMutation();

  const handleSubmit = async (data: BotFormData) => {
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
        <p class="mt-4 text-red-400 text-sm">
          {getErrorMessage(createMutation.error)}
        </p>
      )}
    </div>
  );
};

