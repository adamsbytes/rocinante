export interface ProxyConfig {
  host: string;
  port: number;
  user?: string;
  pass?: string;
}

export interface IronmanConfig {
  enabled: boolean;
  type: 'STANDARD_IRONMAN' | 'HARDCORE_IRONMAN' | 'ULTIMATE_IRONMAN' | null;
  hcimSafetyLevel: 'NORMAL' | 'CAUTIOUS' | 'PARANOID' | null;
}

export interface ResourceConfig {
  cpuLimit: string;
  memoryLimit: string;
}

export interface EnvironmentConfig {
  /** Machine ID - 32 hex chars, stable per account */
  machineId: string;
  
  /** X11 display number (0-9) */
  displayNumber: number;
  /** Screen resolution - e.g., "1280x720" */
  screenResolution: string;
  /** Screen color depth - 24 or 32 */
  screenDepth: number;
  /** Display DPI - one of 96, 110, 120, 144 */
  displayDpi: number;
  
  /** Timezone matching proxy location - e.g., "America/New_York" */
  timezone: string;
  
  /** Additional fonts enabled for this profile (10-20 from optional pool) */
  additionalFonts: string[];
  
  /** JVM garbage collector algorithm */
  gcAlgorithm: 'G1GC' | 'ParallelGC' | 'ZGC';
}

export interface BotConfig {
  id: string;
  name: string;
  username: string;  // Jagex account email
  password: string;  // Jagex account password
  totpSecret?: string;  // Optional: Base32 TOTP secret for 2FA
  characterName?: string;  // Optional: Desired character name for new accounts
  preferredWorld?: number;  // Optional: Default world to use (defaults to 301 F2P)
  proxy: ProxyConfig | null;
  ironman: IronmanConfig;
  resources: ResourceConfig;
  
  /** Environment fingerprint settings - auto-generated, deterministic per bot */
  environment: EnvironmentConfig;
}

export interface BotStatus {
  id: string;
  containerId: string | null;
  state: 'stopped' | 'running' | 'starting' | 'stopping' | 'error';
  error?: string;
}

export interface BotWithStatus extends BotConfig {
  status: BotStatus;
}

export interface BotsConfigFile {
  bots: BotConfig[];
}

export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: string;
}

// ============================================================================
// Runtime Status Types (from bot status.json)
// ============================================================================

/**
 * Full runtime status snapshot from the bot.
 * Published every second via WebSocket when bot is running.
 */
export interface BotRuntimeStatus {
  /** Unix timestamp when this snapshot was created */
  timestamp: number;
  /** Current game state (LOGGED_IN, LOGIN_SCREEN, etc.) */
  gameState: string;
  /** Current task information */
  task: TaskInfo | null;
  /** Session statistics */
  session: SessionInfo | null;
  /** Player information */
  player: PlayerInfo | null;
  /** Task queue status */
  queue: QueueInfo | null;
  /** Quest data (updated on level up, quest completion, or manual refresh) */
  quests: QuestsData | null;
}

/**
 * Information about the current task.
 */
export interface TaskInfo {
  /** Human-readable task description */
  description: string;
  /** Current task state (PENDING, RUNNING, COMPLETED, FAILED, etc.) */
  state: string;
  /** Progress percentage (0.0 to 1.0), or -1 if not applicable */
  progress: number;
  /** Subtask descriptions for composite tasks */
  subtasks: string[];
  /** Time elapsed on current task in milliseconds */
  elapsedMs: number;
}

/**
 * Session statistics.
 */
export interface SessionInfo {
  /** Session start timestamp */
  startTime: number | null;
  /** Total runtime in milliseconds */
  runtimeMs: number;
  /** Break statistics */
  breaks: BreakInfo;
  /** Total actions performed */
  actions: number;
  /** Current fatigue level (0.0 to 1.0) */
  fatigue: number;
  /** XP gained per skill (skill name -> xp) */
  xpGained: Record<string, number>;
  /** Total XP gained across all skills */
  totalXp: number;
  /** XP per hour rate */
  xpPerHour: number;
}

/**
 * Break statistics.
 */
export interface BreakInfo {
  count: number;
  totalDuration: number;
}

/**
 * Player information.
 */
export interface PlayerInfo {
  /** Player's display name */
  name: string;
  /** Combat level */
  combatLevel: number;
  /** Total level across all skills */
  totalLevel: number;
  /** Quest points */
  questPoints: number;
  /** Current hitpoints */
  currentHp: number;
  /** Maximum hitpoints */
  maxHp: number;
  /** Current prayer points */
  currentPrayer: number;
  /** Maximum prayer points */
  maxPrayer: number;
  /** Run energy (0-100) */
  runEnergy: number;
  /** Whether player is poisoned */
  poisoned: boolean;
  /** Whether player is venomed */
  venomed: boolean;
  /** Current world position X coordinate */
  x: number;
  /** Current world position Y coordinate */
  y: number;
  /** Current world position plane (0-3) */
  plane: number;
  /** Skills data (skill name -> data) */
  skills: Record<string, SkillData>;
}

/**
 * Individual skill data.
 */
export interface SkillData {
  level: number;
  xp: number;
  boosted: number;
}

/**
 * Task queue information.
 */
export interface QueueInfo {
  /** Number of pending tasks */
  pending: number;
  /** Descriptions of next few tasks */
  descriptions: string[];
}

// ============================================================================
// Command Types (for sending commands to bot)
// ============================================================================

/**
 * Command to send to the bot.
 */
export interface BotCommand {
  type: 'START' | 'STOP' | 'CLEAR_QUEUE' | 'FORCE_BREAK' | 'ABORT_TASK' | 'QUEUE_TASK' | 'REFRESH_QUESTS';
  timestamp: number;
  task?: TaskSpec;
  priority?: 'URGENT' | 'NORMAL' | 'LOW' | 'BEHAVIORAL';
}

/**
 * Commands file structure.
 */
export interface CommandsFile {
  commands: BotCommand[];
}

// ============================================================================
// Task Specification Types (for QUEUE_TASK commands)
// ============================================================================

/**
 * Union type for all task specifications.
 */
export type TaskSpec = SkillTaskSpec | CombatTaskSpec | NavigationTaskSpec | QuestTaskSpec;

/**
 * Skill task specification.
 */
export interface SkillTaskSpec {
  taskType: 'SKILL';
  /** Training method ID from training_methods.json */
  methodId: string;
  /** Target type: LEVEL, XP, or DURATION */
  targetType: 'LEVEL' | 'XP' | 'DURATION';
  /** Target value (level number, XP amount, or duration in minutes) */
  targetValue: number;
  /** Override banking behavior (optional) */
  bankInsteadOfDrop?: boolean;
  /** Enable world hopping when crowded (optional) */
  useWorldHopping?: boolean;
  /** Player count to trigger world hop (optional) */
  worldHopThreshold?: number;
}

/**
 * Combat task specification.
 */
export interface CombatTaskSpec {
  taskType: 'COMBAT';
  /** NPC names to target (optional, use this or targetNpcIds) */
  targetNpcs?: string[];
  /** NPC IDs to target (optional, use this or targetNpcs) */
  targetNpcIds?: number[];
  /** Completion type: KILL_COUNT or DURATION */
  completionType: 'KILL_COUNT' | 'DURATION';
  /** Completion value (kill count or duration in minutes) */
  completionValue: number;
  /** Enable looting (optional, default true) */
  lootEnabled?: boolean;
  /** Minimum loot value (optional, default 1000) */
  lootMinValue?: number;
  /** Weapon style: SLASH, STAB, CRUSH, RANGED, MAGIC, ANY (optional) */
  weaponStyle?: 'SLASH' | 'STAB' | 'CRUSH' | 'RANGED' | 'MAGIC' | 'ANY';
  /** XP goal: ATTACK, STRENGTH, DEFENCE, etc. (optional) */
  xpGoal?: 'ATTACK' | 'STRENGTH' | 'DEFENCE' | 'HITPOINTS' | 'RANGED' | 'MAGIC' | 'ANY';
  /** Enable safe-spotting (optional) */
  useSafeSpot?: boolean;
  /** Safe spot X coordinate (optional) */
  safeSpotX?: number;
  /** Safe spot Y coordinate (optional) */
  safeSpotY?: number;
  /** Safe spot plane (optional, default 0) */
  safeSpotPlane?: number;
  /** Stop when out of food (optional) */
  stopWhenOutOfFood?: boolean;
  /** Enable resupply runs (optional) */
  enableResupply?: boolean;
  /** Enable bone burying while fighting (optional) */
  buryBonesEnabled?: boolean;
  /** Minimum kills before burying bones (optional, default 2) */
  buryBonesMinKills?: number;
  /** Minimum seconds since last kill before burying (optional, default 60) */
  buryBonesMinSeconds?: number;
  /** Maximum bones to bury per kill ratio (optional, default 2) */
  buryBonesMaxRatio?: number;
}

/**
 * Navigation task specification.
 */
export interface NavigationTaskSpec {
  taskType: 'NAVIGATION';
  /** Named location ID from web.json (optional, use this or x,y) */
  locationId?: string;
  /** X coordinate (optional, use this or locationId) */
  x?: number;
  /** Y coordinate (optional, use this or locationId) */
  y?: number;
  /** Plane (optional, default 0) */
  plane?: number;
  /** Random offset radius (optional) */
  randomRadius?: number;
  /** Task description (optional) */
  description?: string;
}

/**
 * Quest task specification.
 */
export interface QuestTaskSpec {
  taskType: 'QUEST';
  /** Quest identifier */
  questId: string;
}

// ============================================================================
// Data API Types (for training methods, locations, quests)
// ============================================================================

/**
 * Training method information for the UI.
 */
export interface TrainingMethodInfo {
  id: string;
  name: string;
  skill: SkillName;
  methodType: 'GATHER' | 'PROCESS' | 'AGILITY' | 'FIREMAKING' | 'MINIGAME';
  minLevel: number;
  maxLevel: number;
  xpPerAction: number;
  /** Multiplier for level-based XP (0 if static) */
  xpMultiplier: number;
  actionsPerHour: number;
  gpPerHour: number;
  ironmanViable: boolean;
  locationId?: string;
}

/**
 * Calculate XP/hr for a training method at a given level.
 * Handles both static and level-based (xpMultiplier) methods.
 */
export function calculateXpPerHour(method: TrainingMethodInfo, level: number): number {
  if (method.xpMultiplier > 0) {
    return level * method.xpMultiplier * method.actionsPerHour;
  }
  return method.xpPerAction * method.actionsPerHour;
}

/**
 * Navigation location information.
 */
export interface LocationInfo {
  id: string;
  name: string;
  x: number;
  y: number;
  plane: number;
  type: 'GENERIC' | 'BANK' | 'SHOP' | 'TRAINING' | 'QUEST' | 'TRANSPORT';
  tags: string[];
}

/**
 * Quest information for the UI (static data).
 */
export interface QuestInfo {
  id: string;
  name: string;
  questPoints: number;
  difficulty: 'NOVICE' | 'INTERMEDIATE' | 'EXPERIENCED' | 'MASTER' | 'GRANDMASTER' | 'MINIQUEST';
  members: boolean;
}

// ============================================================================
// Live Quest Data Types (from bot status.json)
// ============================================================================

/**
 * Quest data included in runtime status.
 * Updated on: initial load, level up, quest completion, manual refresh.
 */
export interface QuestsData {
  /** Unix timestamp when quest data was last refreshed */
  lastUpdated: number;
  /** List of available quests with requirement status */
  available: QuestSummary[];
  /** IDs of completed quests */
  completed: string[];
  /** IDs of in-progress quests */
  inProgress: string[];
  /** Total quest points earned */
  totalQuestPoints: number;
}

/**
 * Summary of a single quest with live requirement checking.
 */
export interface QuestSummary {
  /** Quest ID (e.g., "DESERT_TREASURE") */
  id: string;
  /** Quest display name */
  name: string;
  /** Difficulty level */
  difficulty: string;
  /** Whether members-only */
  members: boolean;
  /** Quest points reward */
  questPoints: number;
  /** Current quest state: NOT_STARTED, IN_PROGRESS, FINISHED */
  state: string;
  /** Whether all requirements are met to start this quest */
  canStart: boolean;
  /** Skill requirements with met/unmet status */
  skillRequirements: SkillRequirementStatus[];
  /** Quest requirements with met/unmet status */
  questRequirements: QuestRequirementStatus[];
  /** Item requirements (from Quest Helper) */
  itemRequirements: ItemRequirementStatus[];
}

/**
 * Skill requirement with current level status.
 */
export interface SkillRequirementStatus {
  skill: string;
  required: number;
  current: number;
  met: boolean;
  /** Whether this can be boosted to meet requirement */
  boostable: boolean;
}

/**
 * Quest requirement status.
 */
export interface QuestRequirementStatus {
  questId: string;
  questName: string;
  met: boolean;
}

/**
 * Item requirement status.
 */
export interface ItemRequirementStatus {
  itemName: string;
  itemId: number;
  quantity: number;
  have: boolean;
}

// ============================================================================
// Skill Constants
// ============================================================================

/**
 * All OSRS skills in display order.
 */
export const SKILLS = [
  'Attack',
  'Hitpoints',
  'Mining',
  'Strength',
  'Agility',
  'Smithing',
  'Defence',
  'Herblore',
  'Fishing',
  'Ranged',
  'Thieving',
  'Cooking',
  'Prayer',
  'Crafting',
  'Firemaking',
  'Magic',
  'Fletching',
  'Woodcutting',
  'Runecraft',
  'Slayer',
  'Farming',
  'Construction',
  'Hunter',
] as const;

export type SkillName = typeof SKILLS[number];

/**
 * Combat skills.
 */
export const COMBAT_SKILLS = ['Attack', 'Strength', 'Defence', 'Ranged', 'Magic', 'Hitpoints', 'Prayer'] as const;

/**
 * XP table for levels 1-99.
 * XP_TABLE[level] = minimum XP for that level
 */
export const XP_TABLE: number[] = [
  0, 0, 83, 174, 276, 388, 512, 650, 801, 969, 1154, 1358, 1584, 1833, 2107,
  2411, 2746, 3115, 3523, 3973, 4470, 5018, 5624, 6291, 7028, 7842, 8740, 9730,
  10824, 12031, 13363, 14833, 16456, 18247, 20224, 22406, 24815, 27473, 30408,
  33648, 37224, 41171, 45529, 50339, 55649, 61512, 67983, 75127, 83014, 91721,
  101333, 111945, 123660, 136594, 150872, 166636, 184040, 203254, 224466, 247886,
  273742, 302288, 333804, 368599, 407015, 449428, 496254, 547953, 605032, 668051,
  737627, 814445, 899257, 992895, 1096278, 1210421, 1336443, 1475581, 1629200,
  1798808, 1986068, 2192818, 2421087, 2673114, 2951373, 3258594, 3597792, 3972294,
  4385776, 4842295, 5346332, 5902831, 6517253, 7195629, 7944614, 8771558, 9684577,
  10692629, 11805606, 13034431,
];

/**
 * Maximum XP (200M).
 */
export const MAX_XP = 200_000_000;

/**
 * Get the level for a given XP amount.
 */
export function getLevel(xp: number): number {
  for (let level = 98; level >= 1; level--) {
    if (xp >= XP_TABLE[level]) {
      return level;
    }
  }
  return 1;
}

/**
 * Get virtual level for a given XP amount (up to 126).
 */
export function getVirtualLevel(xp: number): number {
  if (xp < XP_TABLE[99]) return getLevel(xp);
  
  // Calculate virtual levels beyond 99
  for (let level = 126; level > 99; level--) {
    const xpRequired = Math.floor(XP_TABLE[99] * Math.pow(1.1, level - 99));
    if (xp >= xpRequired) return level;
  }
  return 99;
}

/**
 * Format XP number with commas.
 */
export function formatXp(xp: number): string {
  return xp.toLocaleString();
}

/**
 * Format XP with K/M suffix.
 */
export function formatXpShort(xp: number): string {
  if (xp >= 1_000_000) {
    return `${(xp / 1_000_000).toFixed(1)}M`;
  } else if (xp >= 1_000) {
    return `${(xp / 1_000).toFixed(1)}K`;
  }
  return xp.toString();
}

/**
 * Format duration in HH:MM:SS.
 */
export function formatDuration(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  
  return [
    hours.toString().padStart(2, '0'),
    (minutes % 60).toString().padStart(2, '0'),
    (seconds % 60).toString().padStart(2, '0'),
  ].join(':');
}

