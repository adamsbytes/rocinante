import { defineConfig } from 'vitest/config';
import solid from 'vite-plugin-solid';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [solid(), tailwindcss()],
  server: {
    port: 5173,
    watch: {
      // Exclude data directory from file watching - status files update every second
      // which would otherwise trigger continuous HMR updates
      ignored: ['**/data/**'],
    },
    proxy: {
      '/api': {
        target: 'http://localhost:3000',
        changeOrigin: true,
        ws: true,
        // Increase proxy timeout for WebSocket connections
        // Default timeout is very short which causes frequent 1005 disconnects
        timeout: 0, // Disable timeout (infinite)
        proxyTimeout: 0, // Disable proxy timeout
        configure: (proxy, _options) => {
          // Set longer timeout on the proxy itself
          proxy.on('proxyReqWs', (_proxyReq, _req, socket) => {
            // Disable socket timeout for WebSocket connections
            socket.setTimeout(0);
            socket.setKeepAlive(true, 30000);
          });
          proxy.on('open', (proxySocket) => {
            // Disable timeout on the proxied socket as well
            proxySocket.setTimeout(0);
            proxySocket.setKeepAlive(true, 30000);
          });
        },
      },
    },
  },
  build: {
    outDir: 'dist/client',
  },
  test: {
    globals: true,
    environment: 'node',
    coverage: {
      reporter: ['text', 'lcov'],
    },
  },
});

