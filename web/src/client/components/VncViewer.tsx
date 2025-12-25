import { type Component, onMount, onCleanup, createSignal } from 'solid-js';

interface VncViewerProps {
  host: string;
  port: number;
  class?: string;
}

export const VncViewer: Component<VncViewerProps> = (props) => {
  let canvasRef: HTMLCanvasElement | undefined;
  const [status, setStatus] = createSignal<'connecting' | 'connected' | 'disconnected' | 'error'>('connecting');
  const [error, setError] = createSignal<string | null>(null);

  // Simple VNC implementation using WebSocket
  // For production, consider using @nickcis/react-vnc or novnc
  onMount(() => {
    const wsUrl = `ws://${props.host}:${props.port}`;
    let ws: WebSocket | null = null;

    const connect = () => {
      setStatus('connecting');
      setError(null);

      try {
        ws = new WebSocket(wsUrl);
        ws.binaryType = 'arraybuffer';

        ws.onopen = () => {
          setStatus('connected');
        };

        ws.onclose = () => {
          setStatus('disconnected');
        };

        ws.onerror = () => {
          setStatus('error');
          setError('Failed to connect to VNC server');
        };

        ws.onmessage = (event) => {
          // Handle VNC protocol messages
          // This is a simplified implementation
          // Real VNC requires RFB protocol handling
          if (canvasRef && event.data instanceof ArrayBuffer) {
            // Placeholder for actual VNC frame rendering
            const ctx = canvasRef.getContext('2d');
            if (ctx) {
              ctx.fillStyle = '#1a1a24';
              ctx.fillRect(0, 0, canvasRef.width, canvasRef.height);
              ctx.fillStyle = '#3b82f6';
              ctx.font = '14px Inter, sans-serif';
              ctx.textAlign = 'center';
              ctx.fillText('VNC Stream Active', canvasRef.width / 2, canvasRef.height / 2);
            }
          }
        };
      } catch (err) {
        setStatus('error');
        setError(err instanceof Error ? err.message : 'Connection failed');
      }
    };

    // Attempt connection
    connect();

    onCleanup(() => {
      if (ws) {
        ws.close();
      }
    });
  });

  return (
    <div class={`relative bg-[var(--bg-tertiary)] rounded-lg overflow-hidden ${props.class ?? ''}`}>
      <canvas
        ref={canvasRef}
        width={1024}
        height={768}
        class="w-full h-auto"
      />
      <div class="absolute top-2 right-2 flex items-center gap-2">
        <span
          class={`w-2 h-2 rounded-full ${
            status() === 'connected'
              ? 'bg-emerald-500'
              : status() === 'connecting'
              ? 'bg-amber-500 animate-pulse'
              : 'bg-red-500'
          }`}
        />
        <span class="text-xs text-[var(--text-secondary)]">
          {status() === 'connected' && 'Connected'}
          {status() === 'connecting' && 'Connecting...'}
          {status() === 'disconnected' && 'Disconnected'}
          {status() === 'error' && (error() || 'Error')}
        </span>
      </div>
      {status() !== 'connected' && (
        <div class="absolute inset-0 flex items-center justify-center bg-[var(--bg-tertiary)]/90">
          <div class="text-center">
            {status() === 'connecting' && (
              <>
                <div class="w-8 h-8 border-2 border-[var(--accent)] border-t-transparent rounded-full animate-spin mx-auto mb-2" />
                <p class="text-[var(--text-secondary)]">Connecting to VNC...</p>
              </>
            )}
            {status() === 'disconnected' && (
              <p class="text-[var(--text-secondary)]">VNC Disconnected</p>
            )}
            {status() === 'error' && (
              <p class="text-red-400">{error() || 'Connection failed'}</p>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

