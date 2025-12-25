import {
  createRootRouteWithContext,
  createRoute,
  Outlet,
  Link,
} from '@tanstack/solid-router';
import { Show } from 'solid-js';
import type { QueryClient } from '@tanstack/solid-query';
import { Dashboard } from './routes/Dashboard';
import { BotDetail } from './routes/BotDetail';
import { BotNew } from './routes/BotNew';
import { BotEdit } from './routes/BotEdit';
import { LogsViewer } from './components/LogsViewer';
import { getViewingLogsForBot, closeLogs } from './lib/logsStore';

interface RouterContext {
  queryClient: QueryClient;
}

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
});

function RootLayout() {
  const viewingBotId = getViewingLogsForBot();

  return (
    <>
      <div class="min-h-screen flex">
        {/* Sidebar */}
        <aside class="w-64 bg-[var(--bg-secondary)] border-r border-[var(--border)] p-4 flex flex-col">
          <h1 class="text-xl font-bold mb-8 text-[var(--accent)]">Rocinante</h1>
          <nav class="flex flex-col gap-2">
            <Link
              to="/"
              class="px-4 py-2 rounded-lg hover:bg-[var(--bg-tertiary)] transition-colors"
              activeProps={{ class: 'bg-[var(--bg-tertiary)] text-[var(--accent)]' }}
            >
              Dashboard
            </Link>
            <Link
              to="/bots/new"
              class="px-4 py-2 rounded-lg hover:bg-[var(--bg-tertiary)] transition-colors"
              activeProps={{ class: 'bg-[var(--bg-tertiary)] text-[var(--accent)]' }}
            >
              + Add Bot
            </Link>
          </nav>
        </aside>

        {/* Main content */}
        <main class="flex-1 p-6 overflow-auto">
          <Outlet />
        </main>
      </div>

      {/* Global Logs Viewer - survives component re-renders */}
      <Show when={viewingBotId()}>
        <LogsViewer botId={viewingBotId()!} onClose={closeLogs} />
      </Show>
    </>
  );
}

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: Dashboard,
});

const botNewRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/bots/new',
  component: BotNew,
});

const botDetailRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/bots/$id',
  component: BotDetail,
});

const botEditRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/bots/$id/edit',
  component: BotEdit,
});

export const routeTree = rootRoute.addChildren([
  indexRoute,
  botNewRoute,
  botDetailRoute,
  botEditRoute,
]);

