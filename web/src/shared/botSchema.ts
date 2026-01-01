import { z } from 'zod';

const optionalTrimmedString = z
  .string()
  .trim()
  .optional()
  .transform((value) => (value && value.length > 0 ? value : undefined));

const cpuOptions = ['0.5', '1.0', '1.5', '2.0'] as const;
const memoryOptions = ['1G', '2G', '3G', '4G'] as const;
const lampSkillOptions = [
  'ATTACK',
  'STRENGTH',
  'DEFENCE',
  'RANGED',
  'PRAYER',
  'MAGIC',
  'HITPOINTS',
  'AGILITY',
  'HERBLORE',
  'THIEVING',
  'CRAFTING',
  'FLETCHING',
  'SLAYER',
  'HUNTER',
  'MINING',
  'SMITHING',
  'FISHING',
  'COOKING',
  'FIREMAKING',
  'WOODCUTTING',
  'FARMING',
  'RUNECRAFT',
  'CONSTRUCTION',
] as const;

const ironmanTypeSchema = z
  .enum(['STANDARD_IRONMAN', 'HARDCORE_IRONMAN', 'ULTIMATE_IRONMAN'])
  .nullable();

const hcimSafetySchema = z.enum(['NORMAL', 'CAUTIOUS', 'PARANOID']).nullable();

export const proxySchema = z.object({
  host: z
    .string()
    .trim()
    .min(1, { message: 'Proxy host is required' }),
  port: z
    .coerce.number()
    .int({ message: 'Proxy port must be a whole number' })
    .min(1, { message: 'Proxy port must be a positive number' }),
  user: optionalTrimmedString,
  pass: optionalTrimmedString,
});

// Shared schema parts
const usernameSchema = z.string().trim().email({ message: 'Valid Jagex account email is required' });
const characterNameSchema = z
  .string()
  .trim()
  .min(1, { message: 'Character name is required' })
  .max(12, { message: 'Character name must be 12 characters or fewer' })
  .regex(/^[a-zA-Z0-9 -]+$/, { message: 'Character name can only contain letters, numbers, spaces, and hyphens' });
const lampSkillSchema = z.enum(lampSkillOptions, { message: 'Lamp skill is required' });
const totpSecretSchema = z
  .string()
  .trim()
  .min(1, { message: '2FA TOTP secret is required' })
  .regex(/^[A-Z2-7]+=*$/i, { message: 'TOTP secret must be valid Base32 format (A-Z, 2-7)' });
const preferredWorldSchema = z.coerce.number().int({ message: 'Preferred world must be a whole number' }).min(301, { message: 'Preferred world must be between 301 and 638' }).max(638, { message: 'Preferred world must be between 301 and 638' }).optional();
const proxyFieldSchema = z.union([proxySchema, z.null()]).transform((value) => value ?? null);
const resourcesSchema = z.object({ cpuLimit: z.enum(cpuOptions), memoryLimit: z.enum(memoryOptions) });

const ironmanSchema = z
  .object({
    enabled: z.boolean(),
    type: ironmanTypeSchema,
    hcimSafetyLevel: hcimSafetySchema,
  })
  .superRefine((value, ctx) => {
    if (value.enabled && !value.type) {
      ctx.addIssue({ code: 'custom', path: ['type'], message: 'Select an Ironman type when enabled' });
    }
    if (!value.enabled && (value.type || value.hcimSafetyLevel)) {
      ctx.addIssue({ code: 'custom', path: ['type'], message: 'Disable Ironman values when not enabled' });
    }
    if (value.type !== 'HARDCORE_IRONMAN' && value.hcimSafetyLevel) {
      ctx.addIssue({ code: 'custom', path: ['hcimSafetyLevel'], message: 'HCIM safety level only applies to Hardcore Ironman' });
    }
    if (value.type === 'HARDCORE_IRONMAN' && !value.hcimSafetyLevel) {
      ctx.addIssue({ code: 'custom', path: ['hcimSafetyLevel'], message: 'Select a safety level for Hardcore Ironman' });
    }
  })
  .transform((value) => ({
    enabled: value.enabled,
    type: value.enabled ? value.type : null,
    hcimSafetyLevel: value.type === 'HARDCORE_IRONMAN' ? value.hcimSafetyLevel : null,
  }));

/**
 * Schema for creating a new bot - password and TOTP required.
 */
export const botCreateSchema = z
  .object({
    username: usernameSchema,
    password: z.string().min(1, { message: 'Password is required' }),
    totpSecret: totpSecretSchema,
    characterName: characterNameSchema,
    lampSkill: lampSkillSchema,
    preferredWorld: preferredWorldSchema,
    proxy: proxyFieldSchema,
    ironman: ironmanSchema,
    resources: resourcesSchema,
  })
  .transform((value) => ({
    ...value,
    preferredWorld: value.preferredWorld ?? undefined,
    resources: {
      cpuLimit: value.resources.cpuLimit || '1.0',
      memoryLimit: value.resources.memoryLimit || '2G',
    },
  }));

/**
 * Schema for updating an existing bot - password and TOTP are optional.
 * Empty string = no change (will be undefined after transform).
 */
export const botUpdateSchema = z
  .object({
    username: usernameSchema,
    // Empty string transforms to undefined = no change
    password: z.string().optional().transform((v) => (v && v.trim().length > 0 ? v : undefined)),
    totpSecret: z
      .string()
      .optional()
      .refine((v) => {
        // Allow empty (means no change) or valid Base32 format
        if (!v || v.trim().length === 0) return true;
        return /^[A-Z2-7]+=*$/i.test(v.trim());
      }, { message: 'TOTP secret must be valid Base32 format (A-Z, 2-7)' })
      .transform((v) => (v && v.trim().length > 0 ? v.trim() : undefined)),
    characterName: characterNameSchema,
    lampSkill: lampSkillSchema,
    preferredWorld: preferredWorldSchema,
    proxy: proxyFieldSchema,
    ironman: ironmanSchema,
    resources: resourcesSchema,
  })
  .transform((value) => ({
    ...value,
    preferredWorld: value.preferredWorld ?? undefined,
    resources: {
      cpuLimit: value.resources.cpuLimit || '1.0',
      memoryLimit: value.resources.memoryLimit || '2G',
    },
  }));

/** @deprecated Use botCreateSchema for new bots, botUpdateSchema for edits */
export const botFormSchema = botCreateSchema;

export type BotFormInput = z.input<typeof botCreateSchema>;
export type BotFormData = z.infer<typeof botCreateSchema>;
export type BotUpdateInput = z.input<typeof botUpdateSchema>;
export type BotUpdateData = z.infer<typeof botUpdateSchema>;

