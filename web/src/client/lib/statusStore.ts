import { createSignal, createEffect, onCleanup, createMemo, type Accessor } from 'solid-js';
import type { BotRuntimeStatus, BotCommand } from '../../shared/types';

/**
 * Connection state for status WebSocket.
 */
export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'error';

/**
 * Status store interface.
 */
export interface StatusStore {
  /** Current bot status (null if not connected or no data) */
  status: Accessor<BotRuntimeStatus | null>;
  /** Current connection state */
  connectionState: Accessor<ConnectionState>;
  /** Error message if connection failed */
  error: Accessor<string | null>;
  /** Manually reconnect */
  reconnect: () => void;
  /** Manually disconnect */
  disconnect: () => void;
  /** Send a command to the bot */
  sendCommand: (command: Omit<BotCommand, 'timestamp'>) => void;
  /** Whether the status data is stale (older than 5 seconds) */
  isStale: Accessor<boolean>;
}

/**
 * Configuration for status store.
 */
interface StatusStoreConfig {
  /** Bot ID to connect to */
  botId: string;
  /** Auto-reconnect on disconnect (default: true) */
  autoReconnect?: boolean;
  /** Initial reconnect delay in ms (default: 1000) */
  reconnectDelay?: number;
  /** Maximum reconnect delay in ms (default: 30000) */
  maxReconnectDelay?: number;
  /** Callback when status updates */
  onStatusUpdate?: (status: BotRuntimeStatus) => void;
  /** Callback when connection state changes */
  onConnectionChange?: (state: ConnectionState) => void;
}

/**
 * Create a reactive store for bot status with WebSocket connection.
 * 
 * Usage:
 * ```tsx
 * const { status, connectionState, sendCommand } = createBotStatusStore({ botId: 'abc123' });
 * 
 * return (
 *   <Show when={status()}>
 *     <div>Player: {status()!.player?.name}</div>
 *   </Show>
 * );
 * ```
 */
export function createBotStatusStore(config: StatusStoreConfig): StatusStore {
  const {
    botId,
    autoReconnect = true,
    reconnectDelay = 1000,
    maxReconnectDelay = 30000,
    onStatusUpdate,
    onConnectionChange,
  } = config;

  const [status, setStatus] = createSignal<BotRuntimeStatus | null>(null);
  const [connectionState, setConnectionState] = createSignal<ConnectionState>('disconnected');
  const [error, setError] = createSignal<string | null>(null);

  let ws: WebSocket | null = null;
  let reconnectTimeout: ReturnType<typeof setTimeout> | null = null;
  let currentReconnectDelay = reconnectDelay;
  let manualDisconnect = false;

  // Calculate if status is stale (more than 5 seconds old)
  const isStale = createMemo(() => {
    const currentStatus = status();
    if (!currentStatus) return true;
    return Date.now() - currentStatus.timestamp > 5000;
  });

  // Notify on connection state change
  createEffect(() => {
    const state = connectionState();
    onConnectionChange?.(state);
  });

  /**
   * Connect to the status WebSocket.
   */
  function connect() {
    if (ws) {
      ws.close();
    }

    manualDisconnect = false;
    setConnectionState('connecting');
    setError(null);

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/api/status/${botId}`;

    try {
      ws = new WebSocket(wsUrl);

      ws.onopen = () => {
        console.log(`[StatusStore] Connected to ${botId}`);
        setConnectionState('connected');
        currentReconnectDelay = reconnectDelay; // Reset backoff
      };

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data) as BotRuntimeStatus;
          setStatus(data);
          onStatusUpdate?.(data);
        } catch (err) {
          console.error('[StatusStore] Failed to parse status:', err);
        }
      };

      ws.onerror = (event) => {
        console.error('[StatusStore] WebSocket error:', event);
        setError('Connection error');
        setConnectionState('error');
      };

      ws.onclose = (event) => {
        console.log(`[StatusStore] Disconnected from ${botId}:`, event.code, event.reason);
        ws = null;
        
        if (!manualDisconnect && autoReconnect) {
          setConnectionState('disconnected');
          scheduleReconnect();
        } else {
          setConnectionState('disconnected');
        }
      };
    } catch (err) {
      console.error('[StatusStore] Failed to create WebSocket:', err);
      setError(err instanceof Error ? err.message : 'Failed to connect');
      setConnectionState('error');
      
      if (autoReconnect) {
        scheduleReconnect();
      }
    }
  }

  /**
   * Schedule a reconnection attempt with exponential backoff.
   */
  function scheduleReconnect() {
    if (reconnectTimeout) {
      clearTimeout(reconnectTimeout);
    }

    reconnectTimeout = setTimeout(() => {
      console.log(`[StatusStore] Reconnecting to ${botId}...`);
      connect();
      // Exponential backoff
      currentReconnectDelay = Math.min(currentReconnectDelay * 2, maxReconnectDelay);
    }, currentReconnectDelay);
  }

  /**
   * Manually disconnect from the WebSocket.
   */
  function disconnect() {
    manualDisconnect = true;
    
    if (reconnectTimeout) {
      clearTimeout(reconnectTimeout);
      reconnectTimeout = null;
    }

    if (ws) {
      ws.close();
      ws = null;
    }

    setConnectionState('disconnected');
  }

  /**
   * Manually trigger a reconnection.
   */
  function reconnect() {
    if (reconnectTimeout) {
      clearTimeout(reconnectTimeout);
      reconnectTimeout = null;
    }

    currentReconnectDelay = reconnectDelay; // Reset backoff
    connect();
  }

  /**
   * Send a command to the bot via WebSocket.
   */
  function sendCommand(command: Omit<BotCommand, 'timestamp'>) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({
        type: 'command',
        command: {
          ...command,
          timestamp: Date.now(),
        },
      }));
    } else {
      console.warn('[StatusStore] Cannot send command, not connected');
    }
  }

  // Connect on creation
  connect();

  // Cleanup on disposal
  onCleanup(() => {
    disconnect();
  });

  return {
    status,
    connectionState,
    error,
    reconnect,
    disconnect,
    sendCommand,
    isStale,
  };
}

/**
 * Create a simple status fetcher using REST API (for non-realtime needs).
 */
export async function fetchBotStatus(botId: string): Promise<BotRuntimeStatus | null> {
  try {
    const response = await fetch(`/api/bots/${botId}/status`);
    if (!response.ok) {
      return null;
    }
    const data = await response.json();
    return data.data as BotRuntimeStatus;
  } catch (err) {
    console.error('[StatusStore] Failed to fetch status:', err);
    return null;
  }
}

/**
 * Send a command to a bot via REST API.
 */
export async function sendBotCommand(
  botId: string,
  command: Omit<BotCommand, 'timestamp'>
): Promise<boolean> {
  try {
    const response = await fetch(`/api/bots/${botId}/command`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        ...command,
        timestamp: Date.now(),
      }),
    });
    return response.ok;
  } catch (err) {
    console.error('[StatusStore] Failed to send command:', err);
    return false;
  }
}

