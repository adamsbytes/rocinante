import { render } from 'solid-js/web';
import { QueryClient, QueryClientProvider } from '@tanstack/solid-query';
import { RouterProvider, createRouter } from '@tanstack/solid-router';
import { routeTree } from './router';
import './index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5000,
      refetchInterval: 10000,
    },
  },
});

const router = createRouter({
  routeTree,
  context: { queryClient },
  defaultPreload: 'intent',
});

declare module '@tanstack/solid-router' {
  interface Register {
    router: typeof router;
  }
}

const rootElement = document.getElementById('app');

if (rootElement) {
  render(
    () => (
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    ),
    rootElement,
  );
}

