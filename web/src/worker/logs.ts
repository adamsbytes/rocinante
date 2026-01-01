/**
 * Log manager for container logs.
 * Maintains in-memory buffer per bot and broadcasts to subscribers.
 */

import type { Socket } from 'bun';
import { getContainerLogStream, parseDockerLogBuffer } from './docker';
import { serializeMessage, type LogMessage } from '../shared/worker-protocol';

const MAX_LOG_LINES = 2000;
const MAX_LINE_LENGTH = 4096;

// ANSI escape sequences (colors, cursor movement, etc.)
const ANSI_ESCAPE_REGEX = /\x1b\[[0-9;]*[a-zA-Z]/g;

// Control characters and Unicode directional overrides that could spoof content
// Includes: C0 controls (except \n\r\t), C1 controls, RTL/LTR overrides, isolates
const CONTROL_CHARS_REGEX = /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f\u200e\u200f\u202a-\u202e\u2066-\u2069]/g;

/**
 * Sanitize log content to prevent terminal escape injection and UI issues.
 * - Strips ANSI escape sequences
 * - Strips control characters and RTL overrides
 * - Limits line length
 */
function sanitizeLogContent(content: string): string {
  return content
    .replace(ANSI_ESCAPE_REGEX, '')
    .replace(CONTROL_CHARS_REGEX, '')
    .split('\n')
    .map(line => line.length > MAX_LINE_LENGTH ? line.slice(0, MAX_LINE_LENGTH) + '...[truncated]' : line)
    .join('\n');
}

// In-memory log storage per bot (survives container restarts)
const botLogs = new Map<string, string[]>();

// Active log subscriptions: botId -> Set of sockets
const subscriptions = new Map<string, Set<Socket<unknown>>>();

// Active log stream watchers: botId -> cleanup function
const activeStreams = new Map<string, () => void>();

/**
 * Append log lines to in-memory buffer.
 */
export function appendBotLogs(botId: string, lines: string[]): void {
  const existing = botLogs.get(botId) || [];
  const combined = [...existing, ...lines].slice(-MAX_LOG_LINES);
  botLogs.set(botId, combined);
}

/**
 * Get stored logs for a bot.
 */
export function getBotLogs(botId: string): string[] {
  return botLogs.get(botId) || [];
}

/**
 * Clear logs for a bot.
 */
export function clearBotLogs(botId: string): void {
  botLogs.delete(botId);
}

/**
 * Subscribe a socket to log updates for a bot.
 */
export function subscribeLogs(botId: string, socket: Socket<unknown>): void {
  let subs = subscriptions.get(botId);
  if (!subs) {
    subs = new Set();
    subscriptions.set(botId, subs);
  }
  subs.add(socket);

  // Send existing logs immediately
  const existingLogs = getBotLogs(botId);
  for (const line of existingLogs) {
    sendLogLine(socket, botId, line);
  }

  // Start watching container logs if not already
  if (!activeStreams.has(botId)) {
    startLogStream(botId);
  }
}

/**
 * Unsubscribe a socket from log updates for a bot.
 */
export function unsubscribeLogs(botId: string, socket: Socket<unknown>): void {
  const subs = subscriptions.get(botId);
  if (subs) {
    subs.delete(socket);
    if (subs.size === 0) {
      subscriptions.delete(botId);
      // Stop watching if no more subscribers
      stopLogStream(botId);
    }
  }
}

/**
 * Unsubscribe a socket from all bots (called on disconnect).
 */
export function unsubscribeAll(socket: Socket<unknown>): void {
  for (const [botId, subs] of subscriptions) {
    subs.delete(socket);
    if (subs.size === 0) {
      subscriptions.delete(botId);
      stopLogStream(botId);
    }
  }
}

/**
 * Send a log line to a single socket.
 */
function sendLogLine(socket: Socket<unknown>, botId: string, line: string): void {
  const message: LogMessage = {
    type: 'log',
    botId,
    line,
  };
  try {
    socket.write(serializeMessage(message));
  } catch {
    // Socket may be closed
  }
}

/**
 * Broadcast a log line to all subscribers for a bot.
 */
export function broadcastLogLine(botId: string, line: string): void {
  const subs = subscriptions.get(botId);
  if (!subs || subs.size === 0) return;

  const message: LogMessage = {
    type: 'log',
    botId,
    line,
  };
  const serialized = serializeMessage(message);

  for (const socket of subs) {
    try {
      socket.write(serialized);
    } catch {
      // Socket may be closed, will be cleaned up on disconnect
    }
  }
}

/**
 * Start watching container logs and broadcasting to subscribers.
 */
async function startLogStream(botId: string): Promise<void> {
  if (activeStreams.has(botId)) return;

  try {
    const stream = await getContainerLogStream(botId);
    if (!stream) {
      // No container running, add a message
      appendBotLogs(botId, ['[Container not running]\n']);
      broadcastLogLine(botId, '[Container not running]\n');
      return;
    }

    let stopped = false;

    const cleanup = () => {
      stopped = true;
      stream.removeAllListeners();
      activeStreams.delete(botId);
    };

    activeStreams.set(botId, cleanup);

    // Add separator for new stream
    appendBotLogs(botId, ['\n--- Log stream connected ---\n']);
    broadcastLogLine(botId, '\n--- Log stream connected ---\n');

    stream.on('data', (chunk: Buffer) => {
      if (stopped) return;

      // Docker log stream has 8-byte header per frame
      const rawContent = chunk.slice(8).toString('utf-8');
      
      // Sanitize to prevent escape sequence injection and UI issues
      const content = sanitizeLogContent(rawContent);
      
      // Store in memory
      appendBotLogs(botId, [content]);
      
      // Broadcast to subscribers
      broadcastLogLine(botId, content);
    });

    stream.on('end', () => {
      if (stopped) return;
      cleanup();
      
      const endMessage = '\n--- Log stream ended ---\n';
      appendBotLogs(botId, [endMessage]);
      broadcastLogLine(botId, endMessage);
    });

    stream.on('error', (err: Error) => {
      if (stopped) return;
      cleanup();
      
      // Sanitize error message in case it contains malicious content
      const safeMessage = sanitizeLogContent(err.message);
      const errorMessage = `\n--- Log stream error: ${safeMessage} ---\n`;
      appendBotLogs(botId, [errorMessage]);
      broadcastLogLine(botId, errorMessage);
    });
  } catch (err) {
    console.error(`Failed to start log stream for ${botId}:`, err);
    const errorMessage = `[Failed to connect to container logs]\n`;
    appendBotLogs(botId, [errorMessage]);
    broadcastLogLine(botId, errorMessage);
  }
}

/**
 * Stop watching container logs for a bot.
 */
function stopLogStream(botId: string): void {
  const cleanup = activeStreams.get(botId);
  if (cleanup) {
    cleanup();
  }
}

/**
 * Restart log stream for a bot (called after container restart).
 */
export async function restartLogStream(botId: string): Promise<void> {
  stopLogStream(botId);
  
  // Only restart if there are subscribers
  if (subscriptions.has(botId) && subscriptions.get(botId)!.size > 0) {
    await startLogStream(botId);
  }
}

/**
 * Get subscriber count for a bot.
 */
export function getSubscriberCount(botId: string): number {
  return subscriptions.get(botId)?.size || 0;
}

/**
 * Add a log separator (used when starting a new container).
 */
export function addLogSeparator(botId: string, message: string): void {
  const separator = `\n--- ${message} ---\n`;
  appendBotLogs(botId, [separator]);
  broadcastLogLine(botId, separator);
}
