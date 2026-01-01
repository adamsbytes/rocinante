import { useQuery, useMutation, useQueryClient } from '@tanstack/solid-query';
import type { BotConfigDTO, BotWithStatusDTO, ApiResponse, ScreenshotEntry } from '../../shared/types';
import type { BotFormData } from '../../shared/botSchema';

const API_BASE = '/api';

async function fetchApi<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
  });
  const data: ApiResponse<T> = await res.json();
  if (!data.success) {
    throw new Error(data.error || 'Unknown error');
  }
  return data.data as T;
}

// Query keys
export const botKeys = {
  all: ['bots'] as const,
  detail: (id: string) => ['bots', id] as const,
  screenshots: (id: string, category: string | null) => ['bots', id, 'screenshots', category ?? 'all'] as const,
};

// Queries
export function useBotsQuery() {
  return useQuery(() => ({
    queryKey: botKeys.all,
    queryFn: () => fetchApi<BotWithStatusDTO[]>('/bots'),
  }));
}

export function useBotQuery(id: () => string) {
  return useQuery(() => ({
    queryKey: botKeys.detail(id()),
    queryFn: () => fetchApi<BotWithStatusDTO>(`/bots/${id()}`),
    enabled: !!id(),
  }));
}

export function useScreenshotsQuery(id: () => string, category: () => string | null) {
  return useQuery(() => ({
    queryKey: botKeys.screenshots(id(), category()),
    queryFn: () => {
      const search = new URLSearchParams();
      const cat = category();
      if (cat && cat !== 'all') {
        search.set('category', cat);
      }
      const query = search.toString();
      return fetchApi<ScreenshotEntry[]>(`/bots/${id()}/screenshots${query ? `?${query}` : ''}`);
    },
    enabled: !!id(),
    // Keep fresh without manual interaction
    refetchInterval: 5000,
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
  }));
}

// Mutations
export function useCreateBotMutation() {
  const queryClient = useQueryClient();
  return useMutation(() => ({
    mutationFn: (bot: BotFormData) =>
      fetchApi<BotConfigDTO>('/bots', {
        method: 'POST',
        body: JSON.stringify(bot),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: botKeys.all });
    },
  }));
}

export function useUpdateBotMutation() {
  const queryClient = useQueryClient();
  return useMutation(() => ({
    mutationFn: ({ id, ...bot }: BotFormData & { id: string }) =>
      fetchApi<BotConfigDTO>(`/bots/${id}`, {
        method: 'PUT',
        body: JSON.stringify(bot),
      }),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: botKeys.all });
      queryClient.invalidateQueries({ queryKey: botKeys.detail(variables.id) });
    },
  }));
}

export function useDeleteBotMutation() {
  const queryClient = useQueryClient();
  return useMutation(() => ({
    mutationFn: (id: string) =>
      fetchApi<void>(`/bots/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: botKeys.all });
    },
  }));
}

export function useStartBotMutation() {
  const queryClient = useQueryClient();
  return useMutation(() => ({
    mutationFn: (id: string) =>
      fetchApi<void>(`/bots/${id}/start`, { method: 'POST' }),
    onSuccess: (_, id) => {
      queryClient.invalidateQueries({ queryKey: botKeys.all });
      queryClient.invalidateQueries({ queryKey: botKeys.detail(id) });
    },
  }));
}

export function useStopBotMutation() {
  const queryClient = useQueryClient();
  return useMutation(() => ({
    mutationFn: (id: string) =>
      fetchApi<void>(`/bots/${id}/stop`, { method: 'POST' }),
    onSuccess: (_, id) => {
      queryClient.invalidateQueries({ queryKey: botKeys.all });
      queryClient.invalidateQueries({ queryKey: botKeys.detail(id) });
    },
  }));
}

export function useRestartBotMutation() {
  const queryClient = useQueryClient();
  return useMutation(() => ({
    mutationFn: (id: string) =>
      fetchApi<void>(`/bots/${id}/restart`, { method: 'POST' }),
    onSuccess: (_, id) => {
      queryClient.invalidateQueries({ queryKey: botKeys.all });
      queryClient.invalidateQueries({ queryKey: botKeys.detail(id) });
    },
  }));
}

