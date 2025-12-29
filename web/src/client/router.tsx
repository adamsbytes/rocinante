import {
  createRootRouteWithContext,
  createRoute,
  Outlet,
  Link,
} from '@tanstack/solid-router';
import { Show } from 'solid-js';
import { createPersistedSignal } from './lib/persistedSignal';
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

// Icons as components for cleaner JSX
const DashboardIcon = () => (
  <svg class="w-5 h-5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zM14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zM14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z" />
  </svg>
);

const AddBotIcon = () => (
  <svg class="w-5 h-5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6v6m0 0v6m0-6h6m-6 0H6" />
  </svg>
);

const CollapseIcon = (props: { collapsed: boolean }) => (
  <svg 
    class="w-5 h-5 transition-transform duration-200" 
    classList={{ 'rotate-180': props.collapsed }}
    fill="none" 
    stroke="currentColor" 
    viewBox="0 0 24 24"
  >
    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 19l-7-7 7-7m8 14l-7-7 7-7" />
  </svg>
);

const LogoIcon = () => (
  <svg class="w-6 h-6 flex-shrink-0" viewBox="0 0 24 24" fill="currentColor">
    <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
  </svg>
);

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
});

function RootLayout() {
  const viewingBotId = getViewingLogsForBot();
  const [collapsed, setCollapsed] = createPersistedSignal('sidebar-collapsed', false);

  return (
    <>
      <div class="min-h-screen flex">
        {/* Sidebar - fixed height, no scroll */}
        <aside 
          class="bg-[var(--bg-secondary)] border-r border-[var(--border)] p-4 flex flex-col h-screen sticky top-0 transition-all duration-200"
          classList={{ 'w-64': !collapsed(), 'w-16': collapsed() }}
        >
          {/* Header with logo */}
          <div class="flex items-center gap-2 mb-8 overflow-hidden" classList={{ 'justify-center': collapsed() }}>
            <span class="text-[var(--accent)]">
              <LogoIcon />
            </span>
            <Show when={!collapsed()}>
              <h1 class="text-xl font-bold text-[var(--accent)] whitespace-nowrap">Rocinante</h1>
            </Show>
          </div>

          {/* Navigation */}
          <nav class="flex flex-col gap-2">
            <Link
              to="/"
              class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-[var(--bg-tertiary)] transition-colors"
              classList={{ 'justify-center': collapsed() }}
              activeProps={{ class: 'bg-[var(--bg-tertiary)] text-[var(--accent)]' }}
              title="Dashboard"
            >
              <DashboardIcon />
              <Show when={!collapsed()}>
                <span class="whitespace-nowrap">Dashboard</span>
              </Show>
            </Link>
            <Link
              to="/bots/new"
              class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-[var(--bg-tertiary)] transition-colors"
              classList={{ 'justify-center': collapsed() }}
              activeProps={{ class: 'bg-[var(--bg-tertiary)] text-[var(--accent)]' }}
              title="Add Bot"
            >
              <AddBotIcon />
              <Show when={!collapsed()}>
                <span class="whitespace-nowrap">Add Bot</span>
              </Show>
            </Link>
          </nav>

          {/* Spacer to push collapse button to bottom */}
          <div class="flex-1" />

          {/* Collapse button at bottom */}
          <button
            onClick={() => setCollapsed(!collapsed())}
            class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-[var(--bg-tertiary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
            classList={{ 'justify-center': collapsed() }}
            title={collapsed() ? 'Expand sidebar' : 'Collapse sidebar'}
          >
            <CollapseIcon collapsed={collapsed()} />
            <Show when={!collapsed()}>
              <span class="whitespace-nowrap">Collapse</span>
            </Show>
          </button>
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

