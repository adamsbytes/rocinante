import {
  createRootRouteWithContext,
  createRoute,
  Outlet,
  Link,
  useLocation,
} from '@tanstack/solid-router';
import { Show, Switch, Match } from 'solid-js';
import { createPersistedSignal } from './lib/persistedSignal';
import type { QueryClient } from '@tanstack/solid-query';
import { Dashboard } from './routes/Dashboard';
import { BotDetail } from './routes/BotDetail';
import { BotNew } from './routes/BotNew';
import { BotEdit } from './routes/BotEdit';
import { Login } from './routes/Login';
import { VerifyMagicLink } from './routes/VerifyMagicLink';
import { LogsViewer } from './components/LogsViewer';
import { getViewingLogsForBot, closeLogs } from './lib/logsStore';
import { user, loading, isAuthenticated, signOut } from './lib/auth';

interface RouterContext {
  queryClient: QueryClient;
}

// Icons as components for cleaner JSX
const DashboardIcon = () => (
  <svg class="w-7 h-7 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zM14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zM14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z" />
  </svg>
);

const AddBotIcon = () => (
  <svg class="w-7 h-7 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6v6m0 0v6m0-6h6m-6 0H6" />
  </svg>
);

const CollapseIcon = (props: { collapsed: boolean }) => (
  <svg 
    class="w-7 h-7 transition-transform duration-200" 
    classList={{ 'rotate-180': props.collapsed }}
    fill="none" 
    stroke="currentColor" 
    viewBox="0 0 24 24"
  >
    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 19l-7-7 7-7m8 14l-7-7 7-7" />
  </svg>
);

const LogoIcon = () => (
  <svg class="w-7 h-7 flex-shrink-0" viewBox="0 0 24 24" fill="currentColor">
    <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
  </svg>
);

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
});

// Loading spinner component
const LoadingSpinner = () => (
  <div class="min-h-screen flex items-center justify-center bg-[var(--bg-primary)]">
    <div class="flex flex-col items-center gap-4">
      <svg class="animate-spin h-8 w-8 text-[var(--accent)]" viewBox="0 0 24 24">
        <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" fill="none" />
        <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
      </svg>
      <span class="text-[var(--text-secondary)]">Loading...</span>
    </div>
  </div>
);

// Sign out icon
const SignOutIcon = () => (
  <svg class="w-7 h-7 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
  </svg>
);

function RootLayout() {
  const viewingBotId = getViewingLogsForBot();
  const [collapsed, setCollapsed] = createPersistedSignal('sidebar-collapsed', false);
  const location = useLocation();

  // Auth verification route bypasses normal auth check
  const isAuthRoute = () => location().pathname.startsWith('/auth/');

  // Shared styles for nav items
  const navItemBase = "flex items-center rounded-lg hover:bg-[var(--bg-tertiary)] transition-colors";

  // Use Switch/Match for reactive conditional rendering
  return (
    <Switch>
      {/* Auth routes (verify magic link) bypass auth check */}
      <Match when={isAuthRoute()}>
        <Outlet />
      </Match>

      {/* Show loading spinner while checking auth */}
      <Match when={loading()}>
        <LoadingSpinner />
      </Match>

      {/* Show login page if not authenticated */}
      <Match when={!isAuthenticated()}>
        <Login />
      </Match>

      {/* Authenticated - show main app */}
      <Match when={isAuthenticated()}>
    <>
      <div class="min-h-screen flex">
        {/* Sidebar - fixed height, no scroll */}
        <aside 
          class="bg-[var(--bg-secondary)] border-r border-[var(--border)] flex flex-col h-screen sticky top-0 transition-all duration-200"
          classList={{ 
            'w-64 p-4': !collapsed(), 
            'w-16 py-4 items-center': collapsed() 
          }}
        >
          {/* Header with logo */}
          <div 
            class="flex items-center flex-shrink-0"
            classList={{ 
              'gap-2 mb-6 px-2 h-12': !collapsed(), 
              'justify-center mb-6 w-12 h-12': collapsed() 
            }}
          >
            <span class="text-[var(--accent)] flex items-center justify-center">
              <LogoIcon />
            </span>
            <Show when={!collapsed()}>
              <h1 class="text-xl font-bold text-[var(--accent)] whitespace-nowrap">Rocinante</h1>
            </Show>
          </div>

          {/* Navigation */}
          <nav 
            class="flex flex-col w-full" 
            classList={{ 
              'gap-2': !collapsed(),
              'gap-4 items-center': collapsed() 
            }}
          >
            <Link
              to="/"
              class={`${navItemBase} ${collapsed() ? 'w-12 h-12 justify-center' : 'gap-3 px-3 w-full h-12'}`}
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
              class={`${navItemBase} ${collapsed() ? 'w-12 h-12 justify-center' : 'gap-3 px-3 w-full h-12'}`}
              activeProps={{ class: 'bg-[var(--bg-tertiary)] text-[var(--accent)]' }}
              title="Add Bot"
            >
              <AddBotIcon />
              <Show when={!collapsed()}>
                <span class="whitespace-nowrap">Add Bot</span>
              </Show>
            </Link>
          </nav>

          {/* Spacer to push buttons to bottom */}
          <div class="flex-1" />

          {/* User info (when expanded) */}
          <Show when={!collapsed() && user()}>
            <div class="px-3 py-2 mb-2 text-xs text-[var(--text-secondary)] truncate">
              {user()?.email}
            </div>
          </Show>

          {/* Sign out button */}
          <div classList={{ 'w-full flex justify-center': collapsed() }}>
            <button
              onClick={() => signOut()}
              class={`${navItemBase} text-[var(--text-secondary)] hover:text-[var(--danger)] ${collapsed() ? 'w-12 h-12 justify-center' : 'gap-3 px-3 w-full h-12'}`}
              title="Sign out"
            >
              <SignOutIcon />
              <Show when={!collapsed()}>
                <span class="whitespace-nowrap">Sign out</span>
              </Show>
            </button>
          </div>

          {/* Collapse button at bottom */}
          <div classList={{ 'w-full flex justify-center mt-2': collapsed(), 'mt-2': !collapsed() }}>
            <button
              onClick={() => setCollapsed(!collapsed())}
              class={`${navItemBase} text-[var(--text-secondary)] hover:text-[var(--text-primary)] ${collapsed() ? 'w-12 h-12 justify-center' : 'gap-3 px-3 w-full h-12'}`}
              title={collapsed() ? 'Expand sidebar' : 'Collapse sidebar'}
            >
              <CollapseIcon collapsed={collapsed()} />
              <Show when={!collapsed()}>
                <span class="whitespace-nowrap">Collapse</span>
              </Show>
            </button>
          </div>
        </aside>

        {/* Main content */}
        <main class="flex-1 p-6 overflow-auto min-w-0">
          <Outlet />
        </main>
      </div>

      {/* Global Logs Viewer - survives component re-renders */}
      <Show when={viewingBotId()}>
        <LogsViewer botId={viewingBotId()!} onClose={closeLogs} />
      </Show>
    </>
      </Match>
    </Switch>
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

// Magic link verification - intercepts magic link clicks to capture fingerprint
// This route bypasses the normal auth check since it IS the auth flow
const verifyMagicLinkRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/auth/verify',
  component: VerifyMagicLink,
});

export const routeTree = rootRoute.addChildren([
  indexRoute,
  botNewRoute,
  botDetailRoute,
  botEditRoute,
  verifyMagicLinkRoute,
]);
