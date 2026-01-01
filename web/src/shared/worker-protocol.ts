/**
 * Shared Zod schemas for Docker worker socket protocol.
 * Used by both the worker (server) and web server (client).
 */

import { z } from 'zod';

// =============================================================================
// Client → Worker Messages
// =============================================================================

/**
 * Command request - execute a Docker operation.
 */
export const commandRequestSchema = z.object({
  type: z.literal('command'),
  /** UUIDv7 request ID for correlation */
  id: z.string().uuid(),
  /** Docker command to execute */
  command: z.enum(['start', 'stop', 'restart', 'remove']),
  /** Bot ID to operate on */
  botId: z.string().uuid(),
});

export type CommandRequest = z.infer<typeof commandRequestSchema>;

/**
 * Subscribe to log streaming for a bot.
 */
export const subscribeRequestSchema = z.object({
  type: z.literal('subscribe'),
  /** Bot ID to subscribe to logs for */
  botId: z.string().uuid(),
});

export type SubscribeRequest = z.infer<typeof subscribeRequestSchema>;

/**
 * Unsubscribe from log streaming for a bot.
 */
export const unsubscribeRequestSchema = z.object({
  type: z.literal('unsubscribe'),
  /** Bot ID to unsubscribe from */
  botId: z.string().uuid(),
});

export type UnsubscribeRequest = z.infer<typeof unsubscribeRequestSchema>;

/**
 * Authentication request - must be first message after connection.
 */
export const authRequestSchema = z.object({
  type: z.literal('auth'),
  /** HMAC-signed token (format: timestamp.clientId.signature) */
  token: z.string().max(256),
  /** Client identifier (must match token) */
  clientId: z.string().max(64),
});

export type AuthRequest = z.infer<typeof authRequestSchema>;

/**
 * Union of all client → worker messages.
 */
export const clientMessageSchema = z.discriminatedUnion('type', [
  authRequestSchema,
  commandRequestSchema,
  subscribeRequestSchema,
  unsubscribeRequestSchema,
]);

export type ClientMessage = z.infer<typeof clientMessageSchema>;

// =============================================================================
// Worker → Client Messages
// =============================================================================

/**
 * Response to a command request.
 */
export const commandResponseSchema = z.object({
  type: z.literal('response'),
  /** Request ID this is responding to */
  id: z.string().uuid(),
  /** Whether the command succeeded */
  success: z.boolean(),
  /** Error message if failed */
  error: z.string().optional(),
});

export type CommandResponse = z.infer<typeof commandResponseSchema>;

/**
 * Log line from a container.
 */
export const logMessageSchema = z.object({
  type: z.literal('log'),
  /** Bot ID the log is from */
  botId: z.string().uuid(),
  /** Log line content */
  line: z.string(),
});

export type LogMessage = z.infer<typeof logMessageSchema>;

/**
 * Authentication response - sent after receiving auth request.
 */
export const authResponseSchema = z.object({
  type: z.literal('auth_response'),
  /** Whether authentication succeeded */
  success: z.boolean(),
  /** Error message if authentication failed */
  error: z.string().optional(),
});

export type AuthResponse = z.infer<typeof authResponseSchema>;

/**
 * Union of all worker → client messages.
 */
export const workerMessageSchema = z.discriminatedUnion('type', [
  authResponseSchema,
  commandResponseSchema,
  logMessageSchema,
]);

export type WorkerMessage = z.infer<typeof workerMessageSchema>;

// =============================================================================
// Status File Schema
// =============================================================================

/**
 * Container status for a single bot.
 */
export const containerStatusSchema = z.object({
  containerId: z.string().nullable(),
  state: z.enum(['stopped', 'running', 'starting', 'stopping', 'error']),
});

export type ContainerStatus = z.infer<typeof containerStatusSchema>;

/**
 * Docker status file written by the worker.
 */
export const dockerStatusFileSchema = z.object({
  /** Unix timestamp (ms) when status was last updated */
  lastUpdated: z.number(),
  /** Whether the bot Docker image exists */
  botImageExists: z.boolean(),
  /** Container status per bot ID */
  containers: z.record(z.string(), containerStatusSchema),
});

export type DockerStatusFile = z.infer<typeof dockerStatusFileSchema>;

// =============================================================================
// Helper Functions
// =============================================================================

/**
 * Parse a JSON line into a client message.
 * Returns null if parsing fails.
 */
export function parseClientMessage(json: string): ClientMessage | null {
  try {
    const parsed = JSON.parse(json);
    const result = clientMessageSchema.safeParse(parsed);
    return result.success ? result.data : null;
  } catch {
    return null;
  }
}

/**
 * Parse a JSON line into a worker message.
 * Returns null if parsing fails.
 */
export function parseWorkerMessage(json: string): WorkerMessage | null {
  try {
    const parsed = JSON.parse(json);
    const result = workerMessageSchema.safeParse(parsed);
    return result.success ? result.data : null;
  } catch {
    return null;
  }
}

/**
 * Serialize a message to JSON line (with newline).
 */
export function serializeMessage(message: ClientMessage | WorkerMessage): string {
  return JSON.stringify(message) + '\n';
}
