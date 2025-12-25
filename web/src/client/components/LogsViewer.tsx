import { type Component, createSignal, onCleanup, onMount, Show } from 'solid-js';

interface LogsViewerProps {
  botId: string;
  onClose: () => void;
}

export const LogsViewer: Component<LogsViewerProps> = (props) => {
  const [logs, setLogs] = createSignal<string[]>([]);
  const [isConnected, setIsConnected] = createSignal(false);
  const [error, setError] = createSignal<string | null>(null);
  const [autoScroll, setAutoScroll] = createSignal(true);
  let logsContainer: HTMLDivElement | undefined;
  let eventSource: EventSource | null = null;

  const connectToLogs = () => {
    setError(null);
    setIsConnected(false);
    
    // Use fetch with ReadableStream for the logs
    fetch(`/api/bots/${props.botId}/logs`)
      .then(async (response) => {
        if (!response.ok) {
          const data = await response.json();
          throw new Error(data.error || 'Failed to fetch logs');
        }
        
        setIsConnected(true);
        const reader = response.body?.getReader();
        const decoder = new TextDecoder();

        if (!reader) {
          throw new Error('No response body');
        }

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          
          const text = decoder.decode(value, { stream: true });
          // Split by newlines and add each line
          const lines = text.split('\n').filter(line => line.trim());
          if (lines.length > 0) {
            setLogs(prev => {
              const newLogs = [...prev, ...lines];
              // Keep last 1000 lines
              return newLogs.slice(-1000);
            });
            
            // Auto-scroll to bottom
            if (autoScroll() && logsContainer) {
              setTimeout(() => {
                logsContainer!.scrollTop = logsContainer!.scrollHeight;
              }, 10);
            }
          }
        }
      })
      .catch((err) => {
        setError(err instanceof Error ? err.message : 'Failed to connect to logs');
        setIsConnected(false);
      });
  };

  onMount(() => {
    connectToLogs();
  });

  onCleanup(() => {
    if (eventSource) {
      eventSource.close();
    }
  });

  const handleScroll = () => {
    if (!logsContainer) return;
    const { scrollTop, scrollHeight, clientHeight } = logsContainer;
    // If user scrolled up, disable auto-scroll
    const isAtBottom = scrollHeight - scrollTop - clientHeight < 50;
    setAutoScroll(isAtBottom);
  };

  const scrollToBottom = () => {
    if (logsContainer) {
      logsContainer.scrollTop = logsContainer.scrollHeight;
      setAutoScroll(true);
    }
  };

  const clearLogs = () => {
    setLogs([]);
  };

  const copyLogs = () => {
    navigator.clipboard.writeText(logs().join('\n'));
  };

  return (
    <div class="fixed inset-0 bg-black/70 flex items-center justify-center z-50 p-4">
      <div class="bg-[var(--bg-primary)] border border-[var(--border)] rounded-xl w-full max-w-4xl max-h-[80vh] flex flex-col shadow-2xl">
        {/* Header */}
        <div class="flex items-center justify-between p-4 border-b border-[var(--border)]">
          <div class="flex items-center gap-3">
            <h3 class="text-lg font-semibold">Container Logs</h3>
            <Show when={isConnected()}>
              <span class="flex items-center gap-1.5 text-xs text-emerald-400">
                <span class="w-2 h-2 bg-emerald-400 rounded-full animate-pulse" />
                Live
              </span>
            </Show>
            <Show when={error()}>
              <span class="flex items-center gap-1.5 text-xs text-red-400">
                <span class="w-2 h-2 bg-red-400 rounded-full" />
                {error()}
              </span>
            </Show>
          </div>
          <div class="flex items-center gap-2">
            <button
              onClick={copyLogs}
              class="px-3 py-1.5 text-sm bg-[var(--bg-tertiary)] hover:bg-zinc-700 rounded-lg transition-colors"
              title="Copy logs"
            >
              Copy
            </button>
            <button
              onClick={clearLogs}
              class="px-3 py-1.5 text-sm bg-[var(--bg-tertiary)] hover:bg-zinc-700 rounded-lg transition-colors"
              title="Clear logs"
            >
              Clear
            </button>
            <button
              onClick={props.onClose}
              class="p-1.5 hover:bg-[var(--bg-tertiary)] rounded-lg transition-colors"
              title="Close"
            >
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>

        {/* Logs container */}
        <div
          ref={logsContainer}
          onScroll={handleScroll}
          class="flex-1 overflow-auto p-4 font-mono text-xs bg-[var(--bg-secondary)] min-h-[400px]"
        >
          <Show
            when={logs().length > 0}
            fallback={
              <div class="text-[var(--text-secondary)] text-center py-8">
                <Show when={isConnected()} fallback={<p>Connecting to logs...</p>}>
                  <p>Waiting for log output...</p>
                </Show>
              </div>
            }
          >
            {logs().map((line, i) => (
              <div 
                class="py-0.5 hover:bg-white/5 whitespace-pre-wrap break-all"
                classList={{
                  'text-red-400': line.toLowerCase().includes('error') || line.toLowerCase().includes('exception'),
                  'text-yellow-400': line.toLowerCase().includes('warn'),
                  'text-emerald-400': line.toLowerCase().includes('success') || line.toLowerCase().includes('started'),
                  'text-blue-400': line.toLowerCase().includes('info'),
                }}
              >
                <span class="text-[var(--text-secondary)] mr-2 select-none">{String(i + 1).padStart(4, ' ')}</span>
                {line}
              </div>
            ))}
          </Show>
        </div>

        {/* Footer */}
        <div class="flex items-center justify-between p-3 border-t border-[var(--border)] text-xs text-[var(--text-secondary)]">
          <span>{logs().length} lines</span>
          <Show when={!autoScroll()}>
            <button
              onClick={scrollToBottom}
              class="px-3 py-1 bg-[var(--accent)] hover:bg-[var(--accent-hover)] text-white rounded transition-colors"
            >
              Scroll to bottom
            </button>
          </Show>
        </div>
      </div>
    </div>
  );
};

