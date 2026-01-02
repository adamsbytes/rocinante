import { useQuery, useMutation, useQueryClient } from '@tanstack/solid-query';
import type { BotConfigDTO, BotWithStatusDTO, ApiResponse, ApiErrorCode, ScreenshotEntry } from '../../shared/types';
import type { BotFormData } from '../../shared/botSchema';

const API_BASE = '/api';

/**
 * Custom error class for API errors.
 * Contains requestId for log correlation and code for programmatic handling.
 */
export class ApiError extends Error {
  /** Error code for programmatic handling */
  readonly code: ApiErrorCode;
  /** Request ID for correlating with server logs (for bug reports) */
  readonly requestId: string;
  /** HTTP status code */
  readonly status: number;

  constructor(message: string, code: ApiErrorCode, requestId: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.requestId = requestId;
    this.status = status;
  }

  /** Format for display: "Message (ref: abc123)" */
  toDisplayString(): string {
    return `${this.message} (ref: ${this.requestId})`;
  }
}

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
    throw new ApiError(
      data.error || 'Something went wrong',
      data.code || 'INTERNAL_ERROR',
      data.requestId || 'unknown',
      res.status
    );
  }
  return data.data as T;
}

/** Type guard to check if an error is an ApiError */
export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

/** Get display-friendly error message from any error */
export function getErrorMessage(error: unknown): string {
  if (isApiError(error)) {
    return error.toDisplayString();
  }
  if (error instanceof Error) {
    return error.message;
  }
  return 'An unexpected error occurred';
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
    // Dashboard benefits from periodic refresh to catch container state changes
    refetchInterval: 5_000,
    // But data is considered fresh for a bit to avoid flicker
    staleTime: 3_000,
  }));
}

export function useBotQuery(id: () => string) {
  return useQuery(() => ({
    queryKey: botKeys.detail(id()),
    queryFn: () => fetchApi<BotWithStatusDTO>(`/bots/${id()}`),
    enabled: !!id(),
    // Container status can change (starting → running) so keep reasonably fresh
    // Optimistic updates handle the immediate feedback, this catches actual state
    staleTime: 2_000,
    refetchInterval: 3_000, // Poll every 3s to catch container state changes
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
    // Screenshots don't change often - longer staleTime to prevent flashing
    staleTime: 10_000,
    // Still poll to catch new screenshots, but less aggressively
    refetchInterval: 15_000,
    refetchOnWindowFocus: true,
    // Structural sharing ensures re-renders only when data actually changes
    structuralSharing: true,
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

/**
 * Optimistic update context returned from onMutate for rollback.
 */
type OptimisticContext = {
  previousBot?: BotWithStatusDTO;
  previousBots?: BotWithStatusDTO[];
};

/**
 * Helper to optimistically update bot status in cache.
 * Uses context.client from Solid Query callbacks.
 */
async function optimisticBotStatusUpdate(
  client: ReturnType<typeof useQueryClient>,
  id: string,
  newState: BotWithStatusDTO['status']['state']
): Promise<OptimisticContext> {
  // Cancel any outgoing refetches to prevent overwriting our optimistic update
  await client.cancelQueries({ queryKey: botKeys.detail(id) });
  await client.cancelQueries({ queryKey: botKeys.all });

  // Snapshot previous states for rollback
  const previousBot = client.getQueryData<BotWithStatusDTO>(botKeys.detail(id));
  const previousBots = client.getQueryData<BotWithStatusDTO[]>(botKeys.all);

  // Optimistically update the detail query
  if (previousBot) {
    client.setQueryData<BotWithStatusDTO>(botKeys.detail(id), {
      ...previousBot,
      status: { ...previousBot.status, state: newState },
    });
  }

  // Optimistically update the list query
  if (previousBots) {
    client.setQueryData<BotWithStatusDTO[]>(
      botKeys.all,
      previousBots.map((bot) =>
        bot.id === id ? { ...bot, status: { ...bot.status, state: newState } } : bot
      )
    );
  }

  return { previousBot, previousBots };
}

/**
 * Helper to rollback optimistic update on error.
 */
function rollbackBotStatusUpdate(
  client: ReturnType<typeof useQueryClient>,
  id: string,
  snapshot: OptimisticContext
) {
  if (snapshot.previousBot) {
    client.setQueryData(botKeys.detail(id), snapshot.previousBot);
  }
  if (snapshot.previousBots) {
    client.setQueryData(botKeys.all, snapshot.previousBots);
  }
}

export function useStartBotMutation() {
  const queryClient = useQueryClient();
  return useMutation(() => ({
    mutationFn: (id: string) =>
      fetchApi<{ status: BotWithStatusDTO['status'] }>(`/bots/${id}/start`, { method: 'POST' }),
    onMutate: async (id: string) => {
      return optimisticBotStatusUpdate(queryClient, id, 'starting');
    },
    onError: (_err: Error, id: string, onMutateResult: OptimisticContext | undefined) => {
      if (onMutateResult) {
        rollbackBotStatusUpdate(queryClient, id, onMutateResult);
      }
    },
    onSuccess: (data: { status: BotWithStatusDTO['status'] }, id: string) => {
      // Update with real status from server
      const currentBot = queryClient.getQueryData<BotWithStatusDTO>(botKeys.detail(id));
      if (currentBot && data.status) {
        queryClient.setQueryData<BotWithStatusDTO>(botKeys.detail(id), {
          ...currentBot,
          status: data.status,
        });
      }
    },
    onSettled: (_data: unknown, _err: Error | null, id: string) => {
      // Always refetch to ensure consistency
      queryClient.invalidateQueries({ queryKey: botKeys.all });
      queryClient.invalidateQueries({ queryKey: botKeys.detail(id) });
    },
  }));
}

export function useStopBotMutation() {
  const queryClient = useQueryClient();
  return useMutation(() => ({
    mutationFn: (id: string) =>
      fetchApi<{ status: BotWithStatusDTO['status'] }>(`/bots/${id}/stop`, { method: 'POST' }),
    onMutate: async (id: string) => {
      return optimisticBotStatusUpdate(queryClient, id, 'stopping');
    },
    onError: (_err: Error, id: string, onMutateResult: OptimisticContext | undefined) => {
      if (onMutateResult) {
        rollbackBotStatusUpdate(queryClient, id, onMutateResult);
      }
    },
    onSuccess: (data: { status: BotWithStatusDTO['status'] }, id: string) => {
      const currentBot = queryClient.getQueryData<BotWithStatusDTO>(botKeys.detail(id));
      if (currentBot && data.status) {
        queryClient.setQueryData<BotWithStatusDTO>(botKeys.detail(id), {
          ...currentBot,
          status: data.status,
        });
      }
    },
    onSettled: (_data: unknown, _err: Error | null, id: string) => {
      queryClient.invalidateQueries({ queryKey: botKeys.all });
      queryClient.invalidateQueries({ queryKey: botKeys.detail(id) });
    },
  }));
}

export function useRestartBotMutation() {
  const queryClient = useQueryClient();
  return useMutation(() => ({
    mutationFn: (id: string) =>
      fetchApi<{ status: BotWithStatusDTO['status'] }>(`/bots/${id}/restart`, { method: 'POST' }),
    onMutate: async (id: string) => {
      // Show stopping first, then server will return starting/running
      return optimisticBotStatusUpdate(queryClient, id, 'stopping');
    },
    onError: (_err: Error, id: string, onMutateResult: OptimisticContext | undefined) => {
      if (onMutateResult) {
        rollbackBotStatusUpdate(queryClient, id, onMutateResult);
      }
    },
    onSuccess: (data: { status: BotWithStatusDTO['status'] }, id: string) => {
      const currentBot = queryClient.getQueryData<BotWithStatusDTO>(botKeys.detail(id));
      if (currentBot && data.status) {
        queryClient.setQueryData<BotWithStatusDTO>(botKeys.detail(id), {
          ...currentBot,
          status: data.status,
        });
      }
    },
    onSettled: (_data: unknown, _err: Error | null, id: string) => {
      queryClient.invalidateQueries({ queryKey: botKeys.all });
      queryClient.invalidateQueries({ queryKey: botKeys.detail(id) });
    },
  }));
}

