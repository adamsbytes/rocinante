import { type Component, onMount, onCleanup, createSignal, createEffect, Show, untrack } from 'solid-js';
// Vendored noVNC ESM source (see lib/novnc/LICENSE.txt)
import RFB from '../lib/novnc/rfb.js';

export type VncStatus = 'connecting' | 'connected' | 'disconnected' | 'error';

interface VncViewerProps {
  botId: string;
  class?: string;
  /** Signal accessor indicating whether reconnection should be attempted (e.g., bot is running) */
  shouldConnect?: () => boolean;
  onStatusChange?: (status: VncStatus, error?: string | null) => void;
}

// Reconnection constants
const INITIAL_RECONNECT_DELAY = 1000; // 1 second
const MAX_RECONNECT_DELAY = 30000; // 30 seconds

// Metrics for debugging/logging
interface VncMetrics {
  connectTime: number | null;
  framesReceived: number;
  lastFrameTime: number | null;
  encoding: string | null;
  reconnectAttempts: number;
}

export const VncViewer: Component<VncViewerProps> = (props) => {
  let containerRef: HTMLDivElement | undefined;
  let rfb: RFB | null = null;
  let reconnectTimeoutId: ReturnType<typeof setTimeout> | null = null;
  let initialConnectDone = false;
  
  const [status, setStatus] = createSignal<VncStatus>('disconnected');
  const [error, setError] = createSignal<string | null>(null);
  const [reconnectAttempt, setReconnectAttempt] = createSignal(0);
  const [isReconnecting, setIsReconnecting] = createSignal(false);
  const [desktopName, setDesktopName] = createSignal<string>('');
  
  // Metrics for performance tracking
  const [metrics, setMetrics] = createSignal<VncMetrics>({
    connectTime: null,
    framesReceived: 0,
    lastFrameTime: null,
    encoding: null,
    reconnectAttempts: 0,
  });

  /** Calculate reconnection delay with exponential backoff */
  const getReconnectDelay = (attempt: number): number => {
    return Math.min(INITIAL_RECONNECT_DELAY * Math.pow(2, attempt), MAX_RECONNECT_DELAY);
  };

  /** Schedule a reconnection attempt with exponential backoff */
  const scheduleReconnect = () => {
    // Only reconnect if shouldConnect returns true (or if prop not provided, always reconnect)
    const shouldReconnect = props.shouldConnect?.() ?? true;
    if (!shouldReconnect) {
      console.log('VNC: Not reconnecting - shouldConnect is false');
      setIsReconnecting(false);
      return;
    }

    // Clear any existing reconnect timeout
    if (reconnectTimeoutId) {
      clearTimeout(reconnectTimeoutId);
      reconnectTimeoutId = null;
    }

    const attempt = reconnectAttempt();
    const delay = getReconnectDelay(attempt);
    
    console.log(`VNC: Scheduling reconnect attempt ${attempt + 1} in ${delay}ms`);
    
    setIsReconnecting(true);
    setStatus('connecting');
    setMetrics(m => ({ ...m, reconnectAttempts: m.reconnectAttempts + 1 }));
    
    reconnectTimeoutId = setTimeout(() => {
      reconnectTimeoutId = null;
      setReconnectAttempt(a => a + 1);
      connect();
    }, delay);
  };

  /** Establish RFB connection via noVNC */
  const connect = () => {
    // Disconnect any existing connection
    if (rfb) {
      rfb.disconnect();
      rfb = null;
    }

    if (!containerRef) {
      console.error('VNC: Container ref not available');
      return;
    }

    // Build WebSocket URL for VNC proxy
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/api/vnc/${props.botId}`;

    try {
      setStatus('connecting');
      setError(null);
      
      const connectStart = performance.now();

      // Create RFB instance with noVNC
      // noVNC handles: protocol negotiation, encodings (Tight/ZRLE/CopyRect), display rendering
      rfb = new RFB(containerRef, wsUrl, {
        shared: true, // Allow shared sessions
        credentials: {}, // No password (x11vnc uses -nopw)
        wsProtocols: [], // Default WebSocket protocols
      });

      // Configure RFB options
      rfb.viewOnly = true;       // VIEW ONLY - no mouse/keyboard input
      rfb.scaleViewport = true;  // Scale to fit container
      rfb.resizeSession = false; // Don't resize remote desktop
      rfb.clipViewport = false;  // Don't clip
      rfb.showDotCursor = false; // Cursor is baked into framebuffer by x11vnc
      rfb.background = '#000000'; // Black background
      
      // Quality settings - prefer quality over latency for viewing
      // noVNC will negotiate best available encoding (Tight > ZRLE > CopyRect > Raw)
      rfb.qualityLevel = 6; // 0-9, higher = better quality
      rfb.compressionLevel = 2; // 0-9, higher = more compression

      // Event handlers
      rfb.addEventListener('connect', () => {
        const connectTime = performance.now() - connectStart;
        console.log(`VNC: Connected in ${connectTime.toFixed(0)}ms`);
        
        // Override noVNC's cursor:none so local cursor is visible while watching
        const canvas = containerRef?.querySelector('canvas');
        if (canvas) {
          canvas.style.setProperty('cursor', 'default', 'important');
        }
        
        setStatus('connected');
        setError(null);
        setReconnectAttempt(0);
        setIsReconnecting(false);
        setMetrics(m => ({
          ...m,
          connectTime,
          framesReceived: 0,
          lastFrameTime: null,
        }));
      });

      rfb.addEventListener('disconnect', (e: CustomEvent<{ clean: boolean }>) => {
        const clean = e.detail?.clean ?? false;
        console.log(`VNC: Disconnected (clean: ${clean})`);
        
        setStatus('disconnected');
        
        // Schedule reconnection if appropriate
        scheduleReconnect();
      });

      rfb.addEventListener('securityfailure', (e: CustomEvent<{ status: number; reason?: string }>) => {
        const reason = e.detail?.reason || 'Security failure';
        console.error('VNC: Security failure:', reason);
        setStatus('error');
        setError(reason);
      });

      rfb.addEventListener('desktopname', (e: CustomEvent<{ name: string }>) => {
        setDesktopName(e.detail?.name || '');
        console.log('VNC: Desktop name:', e.detail?.name);
      });

      // Capability updates (shows which encodings/features are available)
      rfb.addEventListener('capabilities', (e: CustomEvent) => {
        console.log('VNC: Capabilities:', e.detail?.capabilities);
      });

    } catch (err) {
      console.error('VNC: Failed to create RFB connection:', err);
      setStatus('error');
      setError(err instanceof Error ? err.message : 'Failed to connect');
      // Schedule reconnection even on creation failure
      scheduleReconnect();
    }
  };

  onMount(() => {
    // Wait for container to be rendered with proper dimensions before connecting
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        initialConnectDone = true;
        // Only connect if shouldConnect is true
        if (props.shouldConnect?.() ?? true) {
          connect();
        }
      });
    });
  });

  onCleanup(() => {
    // Clear reconnection timeout
    if (reconnectTimeoutId) {
      clearTimeout(reconnectTimeoutId);
      reconnectTimeoutId = null;
    }
    
    // Disconnect RFB
    if (rfb) {
      rfb.disconnect();
      rfb = null;
    }
  });

  // React to shouldConnect changes - only trigger on shouldConnect, not on status changes
  // Skip initial mount - onMount handles that after container is sized
  createEffect(() => {
    const shouldConnectNow = props.shouldConnect?.() ?? true;
    
    // Use untrack to read status without adding it as a dependency
    // This effect should ONLY re-run when shouldConnect changes
    untrack(() => {
      if (!shouldConnectNow) {
        // Stop reconnecting
        if (reconnectTimeoutId) {
          console.log('VNC: Cancelling reconnection - shouldConnect became false');
          clearTimeout(reconnectTimeoutId);
          reconnectTimeoutId = null;
        }
        setIsReconnecting(false);
        
        // Disconnect if connected
        if (rfb) {
          console.log('VNC: Disconnecting - shouldConnect became false');
          rfb.disconnect();
          rfb = null;
        }
        setStatus('disconnected');
      } else if (initialConnectDone) {
        // shouldConnect became true AFTER initial mount - reconnect
        const currentStatus = status();
        if (!rfb && currentStatus !== 'connecting' && !reconnectTimeoutId) {
          console.log('VNC: shouldConnect became true - connecting');
          setReconnectAttempt(0);
          connect();
        }
      }
      // If !initialConnectDone and shouldConnectNow, onMount will handle it
    });
  });

  // Notify parent of status changes
  createEffect(() => {
    props.onStatusChange?.(status(), error());
  });

  return (
    <div 
      class={`relative bg-black rounded-lg overflow-hidden ${props.class ?? ''}`}
      style={{ width: '100%', 'aspect-ratio': '16 / 9' }}
    >
      {/* noVNC renders into this container - it creates its own screen/canvas */}
      {/* Container must fill parent for noVNC to get proper dimensions */}
      <div
        ref={containerRef}
        class="absolute inset-0 vnc-container"
      />
      {/* Override noVNC's inline cursor:none so we can see local cursor while watching */}
      <style>{`.vnc-container canvas { cursor: default !important; }`}</style>
      <Show when={status() !== 'connected'}>
        {/* Use reduced opacity (bg-black/60) to keep last frame visible during reconnection */}
        <div class={`absolute inset-0 flex items-center justify-center ${isReconnecting() ? 'bg-black/60' : 'bg-black/80'}`}>
          <div class="text-center">
            <Show when={status() === 'connecting'}>
              <div class="w-8 h-8 border-2 border-[var(--accent)] border-t-transparent rounded-full animate-spin mx-auto mb-2" />
              <Show 
                when={isReconnecting()}
                fallback={<p class="text-[var(--text-secondary)]">Connecting to VNC...</p>}
              >
                <p class="text-amber-400">
                  Reconnecting{reconnectAttempt() > 1 ? ` (attempt ${reconnectAttempt()})` : '...'}
                </p>
              </Show>
            </Show>
            <Show when={status() === 'disconnected' && !isReconnecting()}>
              <p class="text-[var(--text-secondary)]">VNC Disconnected</p>
            </Show>
            <Show when={status() === 'error' && !isReconnecting()}>
              <p class="text-red-400">{error() || 'Connection failed'}</p>
            </Show>
          </div>
        </div>
      </Show>
    </div>
  );
};
