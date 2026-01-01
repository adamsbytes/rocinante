import { z } from 'zod';

// Regex to detect dangerous characters: null bytes, newlines, and control chars (ASCII 0-31 except tab)
const DANGEROUS_CHARS = /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/;

/** Validate that string contains no control characters, null bytes, or newlines */
const noControlChars = (fieldName: string) => (value: string) => {
  if (DANGEROUS_CHARS.test(value) || value.includes('\n') || value.includes('\r')) {
    return false;
  }
  return true;
};

const optionalSafeString = z
  .string()
  .trim()
  .max(256, { message: 'Must be 256 characters or fewer' })
  .refine(noControlChars('field'), { message: 'Must not contain control characters or newlines' })
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

// Proxy host: hostname, IPv4, or IPv6 (bracketed)
// Allows: proxy.example.com, 192.168.1.1, [::1], [2001:db8::1]
const PROXY_HOST_PATTERN = /^(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*|\d{1,3}(?:\.\d{1,3}){3}|\[[a-fA-F0-9:]+\])$/;

export const proxySchema = z.object({
  host: z
    .string()
    .trim()
    .min(1, { message: 'Proxy host is required' })
    .max(256, { message: 'Proxy host must be 256 characters or fewer' })
    .refine(noControlChars('Proxy host'), { message: 'Proxy host must not contain control characters' })
    .refine((v) => PROXY_HOST_PATTERN.test(v), { message: 'Proxy host must be a valid hostname or IP address' }),
  port: z
    .coerce.number()
    .int({ message: 'Proxy port must be a whole number' })
    .min(1, { message: 'Proxy port must be at least 1' })
    .max(65535, { message: 'Proxy port must be at most 65535' }),
  user: optionalSafeString,
  pass: optionalSafeString,
});

// Shared schema parts
const usernameSchema = z
  .string()
  .trim()
  .email({ message: 'Valid Jagex account email is required' })
  .max(256, { message: 'Username must be 256 characters or fewer' });
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
  .max(256, { message: 'TOTP secret must be 256 characters or fewer' })
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
    password: z
      .string()
      .min(1, { message: 'Password is required' })
      .max(256, { message: 'Password must be 256 characters or fewer' })
      .refine(noControlChars('Password'), { message: 'Password must not contain control characters or newlines' }),
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
    password: z
      .string()
      .max(256, { message: 'Password must be 256 characters or fewer' })
      .refine((v) => !v || noControlChars('Password')(v), { message: 'Password must not contain control characters or newlines' })
      .optional()
      .transform((v) => (v && v.trim().length > 0 ? v : undefined)),
    totpSecret: z
      .string()
      .max(256, { message: 'TOTP secret must be 256 characters or fewer' })
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

