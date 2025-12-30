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

export const botFormSchema = z
  .object({
    username: z
      .string()
      .trim()
      .email({ message: 'Valid Jagex account email is required' }),
    password: z
      .string()
      .min(1, { message: 'Password is required' }),
    totpSecret: z
      .string()
      .trim()
      .min(1, { message: '2FA TOTP secret is required' }),
    characterName: z
      .string()
      .trim()
      .min(1, { message: 'Character name is required' })
      .max(12, { message: 'Character name must be 12 characters or fewer' }),
    lampSkill: z.enum(lampSkillOptions, { message: 'Lamp skill is required' }),
    preferredWorld: z
      .coerce.number()
      .int({ message: 'Preferred world must be a whole number' })
      .min(301, { message: 'Preferred world must be between 301 and 638' })
      .max(638, { message: 'Preferred world must be between 301 and 638' })
      .optional(),
    proxy: z.union([proxySchema, z.null()]).transform((value) => value ?? null),
    ironman: z
      .object({
        enabled: z.boolean(),
        type: ironmanTypeSchema,
        hcimSafetyLevel: hcimSafetySchema,
      })
      .superRefine((value, ctx) => {
        if (value.enabled && !value.type) {
          ctx.addIssue({
            code: 'custom',
            path: ['type'],
            message: 'Select an Ironman type when enabled',
          });
        }
        if (!value.enabled && (value.type || value.hcimSafetyLevel)) {
          ctx.addIssue({
            code: 'custom',
            path: ['type'],
            message: 'Disable Ironman values when not enabled',
          });
        }
        if (value.type !== 'HARDCORE_IRONMAN' && value.hcimSafetyLevel) {
          ctx.addIssue({
            code: 'custom',
            path: ['hcimSafetyLevel'],
            message: 'HCIM safety level only applies to Hardcore Ironman',
          });
        }
        if (value.type === 'HARDCORE_IRONMAN' && !value.hcimSafetyLevel) {
          ctx.addIssue({
            code: 'custom',
            path: ['hcimSafetyLevel'],
            message: 'Select a safety level for Hardcore Ironman',
          });
        }
      })
      .transform((value) => ({
        enabled: value.enabled,
        type: value.enabled ? value.type : null,
        hcimSafetyLevel: value.type === 'HARDCORE_IRONMAN' ? value.hcimSafetyLevel : null,
      })),
    resources: z.object({
      cpuLimit: z.enum(cpuOptions),
      memoryLimit: z.enum(memoryOptions),
    }),
  })
  .transform((value) => ({
    ...value,
    preferredWorld: value.preferredWorld ?? undefined,
    resources: {
      cpuLimit: value.resources.cpuLimit || '1.0',
      memoryLimit: value.resources.memoryLimit || '2G',
    },
  }));

export type BotFormInput = z.input<typeof botFormSchema>;
export type BotFormData = z.infer<typeof botFormSchema>;

