import { type Component, createSignal, onCleanup, onMount, Show, For } from 'solid-js';

interface LogsViewerProps {
  botId: string;
  onClose: () => void;
}

export const LogsViewer: Component<LogsViewerProps> = (props) => {
  const [logs, setLogs] = createSignal<string[]>([]);
  const [isConnected, setIsConnected] = createSignal(false);
  const [error, setError] = createSignal<string | null>(null);
  const [autoScroll, setAutoScroll] = createSignal(true);
  const [copyStatus, setCopyStatus] = createSignal<'idle' | 'copied' | 'error'>('idle');
  let logsContainer: HTMLDivElement | undefined;
  let abortController: AbortController | null = null;

  const connectToLogs = () => {
    setError(null);
    setIsConnected(false);
    
    abortController = new AbortController();
    
    fetch(`/api/bots/${props.botId}/logs`, { signal: abortController.signal })
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
          const lines = text.split('\n').filter(line => line.trim());
          if (lines.length > 0) {
            setLogs(prev => {
              const newLogs = [...prev, ...lines];
              return newLogs.slice(-1000);
            });
            
            if (autoScroll() && logsContainer) {
              setTimeout(() => {
                logsContainer!.scrollTop = logsContainer!.scrollHeight;
              }, 10);
            }
          }
        }
      })
      .catch((err) => {
        if (err.name !== 'AbortError') {
          setError(err instanceof Error ? err.message : 'Failed to connect to logs');
          setIsConnected(false);
        }
      });
  };

  onMount(() => {
    connectToLogs();
  });

  onCleanup(() => {
    if (abortController) {
      abortController.abort();
    }
  });

  const handleScroll = () => {
    if (!logsContainer) return;
    const { scrollTop, scrollHeight, clientHeight } = logsContainer;
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
    const text = logs().join('\n');
    
    // Use the fallback method which is more reliable
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.left = '-9999px';
    textarea.style.top = '0';
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();
    
    try {
      const success = document.execCommand('copy');
      setCopyStatus(success ? 'copied' : 'error');
    } catch (err) {
      console.error('Copy failed:', err);
      setCopyStatus('error');
    }
    
    document.body.removeChild(textarea);
    setTimeout(() => setCopyStatus('idle'), 2000);
  };

  const getLineClass = (line: string): string => {
    const lower = line.toLowerCase();
    if (lower.includes('error') || lower.includes('exception')) return 'text-red-400';
    if (lower.includes('warn')) return 'text-yellow-400';
    if (lower.includes('success') || lower.includes('started')) return 'text-emerald-400';
    if (lower.includes('info')) return 'text-blue-400';
    return '';
  };

  // Close modal when clicking backdrop
  const handleBackdropClick = (e: MouseEvent) => {
    if (e.target === e.currentTarget) {
      props.onClose();
    }
  };

  return (
    <div 
      class="fixed inset-0 bg-black/70 flex items-center justify-center z-50 p-4"
      onClick={handleBackdropClick}
    >
      <div 
        class="bg-[var(--bg-primary)] border border-[var(--border)] rounded-xl w-full max-w-4xl max-h-[80vh] flex flex-col shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
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
              {copyStatus() === 'copied' ? '✓ Copied' : copyStatus() === 'error' ? 'Failed' : 'Copy'}
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
          class="flex-1 overflow-auto bg-[var(--bg-secondary)] min-h-[400px]"
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
            <table class="w-full font-mono text-xs border-collapse">
              <tbody>
                <For each={logs()}>
                  {(line, i) => (
                    <tr class={`hover:bg-white/5 ${getLineClass(line)}`}>
                      <td class="text-[var(--text-secondary)] px-2 py-0.5 text-right w-12 select-none border-r border-[var(--border)] align-top">
                        {i() + 1}
                      </td>
                      <td class="px-3 py-0.5 whitespace-pre-wrap break-all">
                        {line}
                      </td>
                    </tr>
                  )}
                </For>
              </tbody>
            </table>
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

