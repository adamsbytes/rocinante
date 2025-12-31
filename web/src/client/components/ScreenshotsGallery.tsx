import { type Component, For, Show, createMemo, createSignal, createEffect, onCleanup } from 'solid-js';
import type { ScreenshotEntry } from '../../shared/types';
import { useScreenshotsQuery } from '../lib/api';

interface ScreenshotsGalleryProps {
  botId: string;
  characterName?: string;
}

type CategoryOption = { id: string; label: string; count: number };

function formatTimestamp(ms: number): string {
  return new Intl.DateTimeFormat(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(ms));
}

export const ScreenshotsGallery: Component<ScreenshotsGalleryProps> = (props) => {
  const [category, setCategory] = createSignal<string | null>('all');
  const [viewerIndex, setViewerIndex] = createSignal<number | null>(null);
  const [isGalleryOpen, setGalleryOpen] = createSignal(false);

  const screenshotsQuery = useScreenshotsQuery(() => props.botId, category);
  const entries = () => screenshotsQuery.data ?? [];

  const categories = createMemo<CategoryOption[]>(() => {
    const counts = new Map<string, number>();
    entries().forEach((entry) => {
      const key = entry.category;
      counts.set(key, (counts.get(key) ?? 0) + 1);
    });
    const options: CategoryOption[] = [{ id: 'all', label: 'All', count: entries().length }];
    for (const [key, count] of counts.entries()) {
      options.push({ id: key, label: key, count });
    }
    return options;
  });

  const filtered = createMemo<ScreenshotEntry[]>(() => {
    const active = category();
    if (!active || active === 'all') return entries();
    return entries().filter((entry) => entry.category === active);
  });

  const hasData = createMemo(() => !screenshotsQuery.isLoading && filtered().length > 0);
  const currentShot = createMemo(() => {
    const idx = viewerIndex();
    if (idx === null) return null;
    return filtered()[idx] ?? null;
  });

  const closeViewer = () => setViewerIndex(null);
  const openViewer = (idx: number) => {
    setGalleryOpen(true);
    setViewerIndex(idx);
  };
  const goNext = () => {
    const list = filtered();
    const idx = viewerIndex();
    if (idx === null || list.length === 0) return;
    setViewerIndex((idx + 1) % list.length);
  };
  const goPrev = () => {
    const list = filtered();
    const idx = viewerIndex();
    if (idx === null || list.length === 0) return;
    setViewerIndex((idx - 1 + list.length) % list.length);
  };

  // Allow closing viewer with Escape
  createEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setGalleryOpen(false);
        closeViewer();
      } else if (e.key === 'ArrowRight' && viewerIndex() !== null) {
        goNext();
      } else if (e.key === 'ArrowLeft' && viewerIndex() !== null) {
        goPrev();
      }
    };
    window.addEventListener('keydown', handler);
    onCleanup(() => window.removeEventListener('keydown', handler));
  });

  const buildImageUrl = (entry: ScreenshotEntry) =>
    `/api/bots/${encodeURIComponent(entry.botId)}/screenshots/view?path=${encodeURIComponent(entry.path)}`;

  // Keep selection valid when filters change
  createEffect(() => {
    const list = filtered();
    if (list.length === 0) {
      closeViewer();
      return;
    }
    const idx = viewerIndex();
    if (idx === null || idx >= list.length) {
      setViewerIndex(0);
    }
  });

  return (
    <div class="mb-8">
      <div class="flex flex-wrap items-center justify-between gap-3 mb-3">
        <button
          type="button"
          class="flex items-center gap-3 group"
          onClick={() => {
            setGalleryOpen(true);
            if (entries().length > 0) openViewer(0);
          }}
        >
          <div class="text-left">
            <h3 class="text-lg font-semibold text-white group-hover:text-emerald-200 transition">
              Screenshots
            </h3>
          </div>
        </button>
        <div class="flex items-center gap-2 flex-wrap justify-end">
          <Show when={screenshotsQuery.isFetching}>
            <div class="text-xs text-gray-400">Refreshing…</div>
          </Show>
          <div class="flex flex-wrap gap-2">
            <For each={categories()}>
              {(cat) => (
                <button
                  class={`px-3 py-1.5 text-sm rounded-full border ${
                    category() === cat.id
                      ? 'border-white/70 bg-white/10 text-white'
                      : 'border-[var(--border)] text-gray-300 hover:border-white/50 hover:bg-white/5'
                  } transition`}
                  onClick={() => setCategory(cat.id)}
                >
                  {cat.label} {cat.count > 0 ? `(${cat.count})` : ''}
                </button>
              )}
            </For>
          </div>
        </div>
      </div>

      <Show
        when={hasData()}
        fallback={
          <div class="border border-dashed border-[var(--border)] rounded-lg p-6 text-center text-gray-400">
            <Show
              when={!screenshotsQuery.isLoading}
              fallback={<div class="text-sm text-gray-400">Loading screenshots…</div>}
            >
              <div class="text-lg mb-1">No screenshots yet</div>
              <div class="text-sm text-gray-500">
                Screenshots will appear here automatically once this bot saves them.
              </div>
            </Show>
          </div>
        }
      >
        <div class="overflow-x-auto pb-2">
          <div class="grid grid-flow-col auto-cols-[220px] gap-3">
            <For each={filtered()}>
              {(shot, idx) => (
                <button
                  class="text-left group"
                  onClick={() => openViewer(idx())}
                  aria-label={`Open screenshot ${shot.title}`}
                >
                  <div class="relative aspect-video rounded-lg overflow-hidden border border-[var(--border)] bg-black/40">
                    <img
                      src={buildImageUrl(shot)}
                      loading="lazy"
                      class="absolute inset-0 h-full w-full object-cover transition duration-200 group-hover:scale-[1.02] group-hover:opacity-95"
                      alt={shot.title}
                    />
                    <div class="absolute inset-0 bg-gradient-to-t from-black/50 via-black/10 to-transparent opacity-0 group-hover:opacity-100 transition" />
                    <div class="absolute bottom-1 left-2 right-2 text-xs text-white drop-shadow-sm">
                      <div class="flex justify-between gap-2">
                        <span class="truncate font-medium">{shot.title}</span>
                        <span class="text-[11px] uppercase tracking-wide opacity-80">{shot.category}</span>
                      </div>
                    </div>
                  </div>
                  <div class="mt-2 text-xs text-gray-400 flex items-center gap-2">
                    <span>{formatTimestamp(shot.capturedAt)}</span>
                    <span class="text-gray-600">•</span>
                    <span class="truncate">{(shot.sizeBytes / 1024).toFixed(0)} KB</span>
                  </div>
                </button>
              )}
            </For>
          </div>
        </div>
      </Show>

      {/* Full gallery overlay */}
      <Show when={isGalleryOpen()}>
        <div
          class="fixed inset-0 bg-black/85 z-50 flex items-center justify-center p-4"
          onClick={(e) => {
            if (e.target === e.currentTarget) {
              setGalleryOpen(false);
              closeViewer();
            }
          }}
        >
          <div
            class="bg-[var(--bg-primary)] border border-[var(--border)] rounded-2xl max-w-7xl w-full h-[90vh] flex flex-col shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div class="flex items-center justify-between p-4 border-b border-[var(--border)] gap-3">
              <div class="min-w-0">
                <div class="text-xl font-semibold text-white">Screenshots Gallery</div>
                <Show when={currentShot()}>
                  {(shot) => (
                    <div class="text-sm text-gray-400 truncate">
                      {shot().category} • {formatTimestamp(shot().capturedAt)} • {shot().title}
                    </div>
                  )}
                </Show>
              </div>
              <div class="flex items-center gap-3">
                <select
                  class="bg-[var(--bg-secondary)] border border-[var(--border)] text-sm rounded-md px-3 py-1.5 text-white focus:outline-none"
                  value={category() ?? 'all'}
                  onInput={(e) => setCategory((e.currentTarget.value || 'all'))}
                >
                  <For each={categories()}>
                    {(cat) => (
                      <option value={cat.id}>
                        {cat.label} {cat.count > 0 ? `(${cat.count})` : ''}
                      </option>
                    )}
                  </For>
                </select>
                <button
                  class="p-2 rounded-md hover:bg-white/10 text-gray-200"
                  onClick={() => {
                    setGalleryOpen(false);
                    closeViewer();
                  }}
                  aria-label="Close"
                >
                  ✕
                </button>
              </div>
            </div>

            <Show
              when={filtered().length > 0}
              fallback={
                <div class="flex-1 flex items-center justify-center text-center text-gray-400 px-6">
                  <div>
                    <div class="text-3xl mb-3">📷</div>
                    <div class="text-lg mb-1">No screenshots yet</div>
                    <div class="text-sm text-gray-500">
                      Captures will show up here automatically once available.
                    </div>
                  </div>
                </div>
              }
            >
              <div class="flex-1 grid grid-cols-[1fr,260px] gap-4 p-4 min-h-0">
                <div class="relative bg-black rounded-xl border border-[var(--border)] flex items-center justify-center overflow-hidden">
                  <Show when={currentShot()}>
                    {(shot) => (
                      <>
                        <img
                          src={buildImageUrl(shot())}
                          alt={shot().title}
                          class="max-h-full max-w-full object-contain"
                        />
                        <button
                          class="absolute left-3 top-1/2 -translate-y-1/2 bg-black/60 hover:bg-black/80 text-white rounded-full px-3 py-2"
                          onClick={goPrev}
                          aria-label="Previous"
                        >
                          ‹
                        </button>
                        <button
                          class="absolute right-3 top-1/2 -translate-y-1/2 bg-black/60 hover:bg-black/80 text-white rounded-full px-3 py-2"
                          onClick={goNext}
                          aria-label="Next"
                        >
                          ›
                        </button>
                        <div class="absolute bottom-3 left-3 right-3 bg-black/50 rounded-md px-3 py-2 text-sm text-white flex justify-between gap-3">
                          <div class="truncate">
                            <div class="font-medium truncate">{shot().title}</div>
                            <div class="text-xs text-gray-200 truncate">
                              {shot().category} • {formatTimestamp(shot().capturedAt)}
                            </div>
                          </div>
                          <button
                            class="text-xs px-3 py-1 rounded-md border border-white/30 hover:bg-white/10"
                            onClick={() => window.open(buildImageUrl(shot()), '_blank')}
                          >
                            Open
                          </button>
                        </div>
                      </>
                    )}
                  </Show>
                </div>

                <div class="h-full overflow-y-auto pr-1">
                  <div class="grid gap-2">
                    <For each={filtered()}>
                      {(shot, idx) => (
                        <button
                          class={`w-full flex items-center gap-3 rounded-lg border px-2 py-2 text-left transition ${
                            viewerIndex() === idx()
                              ? 'border-emerald-400/70 bg-emerald-400/5'
                              : 'border-[var(--border)] hover:border-white/50 hover:bg-white/5'
                          }`}
                          onClick={() => setViewerIndex(idx())}
                        >
                          <div class="relative w-16 h-12 rounded-md overflow-hidden bg-black">
                            <img
                              src={buildImageUrl(shot)}
                              alt={shot.title}
                              class="absolute inset-0 w-full h-full object-cover"
                              loading="lazy"
                            />
                          </div>
                          <div class="min-w-0">
                            <div class="text-sm text-white truncate">{shot.title}</div>
                            <div class="text-[11px] text-gray-400 truncate">
                              {shot.category} • {formatTimestamp(shot.capturedAt)}
                            </div>
                          </div>
                        </button>
                      )}
                    </For>
                  </div>
                </div>
              </div>
            </Show>
          </div>
        </div>
      </Show>
    </div>
  );
};

