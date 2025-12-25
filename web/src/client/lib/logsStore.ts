import { createSignal } from 'solid-js';

// Global store for which bot's logs are being viewed
// Stored at module level so it survives component re-renders
const [viewingLogsForBot, setViewingLogsForBot] = createSignal<string | null>(null);

export function openLogs(botId: string) {
  setViewingLogsForBot(botId);
}

export function closeLogs() {
  setViewingLogsForBot(null);
}

export function getViewingLogsForBot() {
  return viewingLogsForBot;
}

