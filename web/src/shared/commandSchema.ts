import { z } from 'zod';

// =============================================================================
// TaskSpec Schemas
// =============================================================================

/**
 * Skill task specification schema.
 */
const skillTaskSpecSchema = z.object({
  taskType: z.literal('SKILL'),
  methodId: z.string().min(1, { message: 'Training method ID is required' }),
  locationId: z.string().optional(),
  targetType: z.enum(['LEVEL', 'XP', 'DURATION'], { message: 'Target type must be LEVEL, XP, or DURATION' }),
  targetValue: z.number().positive({ message: 'Target value must be positive' }),
  bankInsteadOfDrop: z.boolean().optional(),
  useWorldHopping: z.boolean().optional(),
  worldHopThreshold: z.number().int().positive().optional(),
});

/**
 * Combat task specification schema.
 */
const combatTaskSpecSchema = z.object({
  taskType: z.literal('COMBAT'),
  targetNpcs: z.array(z.string()).optional(),
  targetNpcIds: z.array(z.number().int()).optional(),
  completionType: z.enum(['KILL_COUNT', 'DURATION'], { message: 'Completion type must be KILL_COUNT or DURATION' }),
  completionValue: z.number().positive({ message: 'Completion value must be positive' }),
  lootEnabled: z.boolean().optional(),
  lootMinValue: z.number().int().nonnegative().optional(),
  weaponStyle: z.enum(['SLASH', 'STAB', 'CRUSH', 'RANGED', 'MAGIC', 'ANY']).optional(),
  xpGoal: z.enum(['ATTACK', 'STRENGTH', 'DEFENCE', 'HITPOINTS', 'RANGED', 'MAGIC', 'ANY']).optional(),
  useSafeSpot: z.boolean().optional(),
  safeSpotX: z.number().int().optional(),
  safeSpotY: z.number().int().optional(),
  safeSpotPlane: z.number().int().optional(),
  stopWhenOutOfFood: z.boolean().optional(),
  enableResupply: z.boolean().optional(),
  buryBonesEnabled: z.boolean().optional(),
  buryBonesMinKills: z.number().int().positive().optional(),
  buryBonesMinSeconds: z.number().int().positive().optional(),
  buryBonesMaxRatio: z.number().positive().optional(),
});

/**
 * Navigation task specification schema.
 */
const navigationTaskSpecSchema = z.object({
  taskType: z.literal('NAVIGATION'),
  locationId: z.string().optional(),
  x: z.number().int().optional(),
  y: z.number().int().optional(),
  plane: z.number().int().optional(),
  randomRadius: z.number().int().nonnegative().optional(),
  description: z.string().optional(),
}).superRefine((value, ctx) => {
  // Must provide either locationId OR coordinates
  if (!value.locationId && (value.x === undefined || value.y === undefined)) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: 'Must provide either locationId or x,y coordinates',
      path: ['locationId'],
    });
  }
});

/**
 * Quest task specification schema.
 */
const questTaskSpecSchema = z.object({
  taskType: z.literal('QUEST'),
  questId: z.string().min(1, { message: 'Quest ID is required' }),
});

/**
 * Discriminated union of all task specifications.
 */
const taskSpecSchema = z.discriminatedUnion('taskType', [
  skillTaskSpecSchema,
  combatTaskSpecSchema,
  navigationTaskSpecSchema,
  questTaskSpecSchema,
]);

// =============================================================================
// BotCommand Schema
// =============================================================================

/**
 * Command payload schema (without timestamp - server adds it).
 * This is what the client sends.
 */
export const botCommandPayloadSchema = z.object({
  type: z.enum(['START', 'STOP', 'CLEAR_QUEUE', 'FORCE_BREAK', 'ABORT_TASK', 'QUEUE_TASK', 'REFRESH_QUESTS'], {
    message: 'Invalid command type',
  }),
  task: taskSpecSchema.optional(),
  priority: z.enum(['URGENT', 'NORMAL', 'LOW', 'BEHAVIORAL']).optional(),
}).superRefine((value, ctx) => {
  // QUEUE_TASK requires a task
  if (value.type === 'QUEUE_TASK' && !value.task) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: 'QUEUE_TASK command requires a task specification',
      path: ['task'],
    });
  }
});

/**
 * Full command schema (with timestamp - used internally).
 */
export const botCommandSchema = botCommandPayloadSchema.extend({
  timestamp: z.number().int().positive(),
});

// Export types
export type BotCommandPayload = z.infer<typeof botCommandPayloadSchema>;
export type SkillTaskSpec = z.infer<typeof skillTaskSpecSchema>;
export type CombatTaskSpec = z.infer<typeof combatTaskSpecSchema>;
export type NavigationTaskSpec = z.infer<typeof navigationTaskSpecSchema>;
export type QuestTaskSpec = z.infer<typeof questTaskSpecSchema>;
export type TaskSpec = z.infer<typeof taskSpecSchema>;
