/**
 * Data API functions for serving bot data to the frontend.
 * Reads data files from the bot's resources directory.
 */

import type { TrainingMethodInfo, LocationInfo, QuestInfo, SkillName } from '../shared/types';

// Path to bot's data directory (relative to workspace root)
const BOT_DATA_PATH = process.env.BOT_DATA_PATH || './bot/src/main/resources/data';

// ============================================================================
// Training Methods
// ============================================================================

interface RawTrainingMethod {
  id: string;
  name: string;
  skill: string;
  methodType: string;
  minLevel: number;
  maxLevel?: number;
  xpPerAction?: number;
  xpMultiplier?: number;
  actionsPerHour: number;
  gpPerHour?: number;
  ironmanViable?: boolean;
  locationId?: string;
}

interface TrainingMethodsFile {
  methods: RawTrainingMethod[];
}

let cachedTrainingMethods: TrainingMethodInfo[] | null = null;
let trainingMethodsCacheTime = 0;
const CACHE_TTL_MS = 60000; // 1 minute cache

/**
 * Load and parse training methods from JSON file.
 */
export async function getTrainingMethods(): Promise<TrainingMethodInfo[]> {
  const now = Date.now();
  
  // Return cached data if still valid
  if (cachedTrainingMethods && (now - trainingMethodsCacheTime) < CACHE_TTL_MS) {
    return cachedTrainingMethods;
  }

  try {
    const file = Bun.file(`${BOT_DATA_PATH}/training_methods.json`);
    const data: TrainingMethodsFile = await file.json();
    
    cachedTrainingMethods = data.methods.map(m => ({
      id: m.id,
      name: m.name,
      skill: formatSkillName(m.skill) as SkillName,
      methodType: m.methodType as TrainingMethodInfo['methodType'],
      minLevel: m.minLevel,
      maxLevel: m.maxLevel ?? -1,
      xpPerAction: m.xpPerAction ?? 0,
      xpMultiplier: m.xpMultiplier ?? 0,
      actionsPerHour: m.actionsPerHour,
      gpPerHour: m.gpPerHour ?? 0,
      ironmanViable: m.ironmanViable ?? true,
      locationId: m.locationId,
    }));
    
    trainingMethodsCacheTime = now;
    return cachedTrainingMethods;
  } catch (error) {
    console.error('Failed to load training methods:', error);
    return cachedTrainingMethods || [];
  }
}

/**
 * Get training methods grouped by skill.
 */
export async function getTrainingMethodsBySkill(): Promise<Record<SkillName, TrainingMethodInfo[]>> {
  const methods = await getTrainingMethods();
  const grouped: Record<string, TrainingMethodInfo[]> = {};
  
  for (const method of methods) {
    if (!grouped[method.skill]) {
      grouped[method.skill] = [];
    }
    grouped[method.skill].push(method);
  }
  
  // Sort each skill's methods by minLevel
  for (const skill of Object.keys(grouped)) {
    grouped[skill].sort((a, b) => a.minLevel - b.minLevel);
  }
  
  return grouped as Record<SkillName, TrainingMethodInfo[]>;
}

// ============================================================================
// Navigation Locations
// ============================================================================

interface RawLocation {
  id: string;
  name: string;
  x: number;
  y: number;
  plane?: number;
  type: string;
  tags?: string[];
  metadata?: Record<string, unknown>;
}

interface WebJsonFile {
  version: string;
  nodes: RawLocation[];
}

let cachedLocations: LocationInfo[] | null = null;
let locationsCacheTime = 0;

/**
 * Load and parse navigation locations from web.json.
 */
export async function getLocations(): Promise<LocationInfo[]> {
  const now = Date.now();
  
  // Return cached data if still valid
  if (cachedLocations && (now - locationsCacheTime) < CACHE_TTL_MS) {
    return cachedLocations;
  }

  try {
    const file = Bun.file(`${BOT_DATA_PATH}/web.json`);
    const data: WebJsonFile = await file.json();
    
    cachedLocations = data.nodes.map(n => ({
      id: n.id,
      name: n.name,
      x: n.x,
      y: n.y,
      plane: n.plane ?? 0,
      type: n.type as LocationInfo['type'],
      tags: n.tags ?? [],
    }));
    
    locationsCacheTime = now;
    return cachedLocations;
  } catch (error) {
    console.error('Failed to load locations:', error);
    return cachedLocations || [];
  }
}

/**
 * Get locations filtered by type.
 */
export async function getLocationsByType(type: LocationInfo['type']): Promise<LocationInfo[]> {
  const locations = await getLocations();
  return locations.filter(l => l.type === type);
}

// ============================================================================
// Quests
// ============================================================================

// Static quest list (from RuneLite Quest enum)
// This is a subset - full list would include all quests
const QUESTS: QuestInfo[] = [
  // Free-to-play quests
  { id: 'COOKS_ASSISTANT', name: "Cook's Assistant", questPoints: 1, difficulty: 'NOVICE', members: false },
  { id: 'THE_RESTLESS_GHOST', name: 'The Restless Ghost', questPoints: 1, difficulty: 'NOVICE', members: false },
  { id: 'ROMEO_AND_JULIET', name: 'Romeo & Juliet', questPoints: 5, difficulty: 'NOVICE', members: false },
  { id: 'SHEEP_SHEARER', name: 'Sheep Shearer', questPoints: 1, difficulty: 'NOVICE', members: false },
  { id: 'SHIELD_OF_ARRAV', name: 'Shield of Arrav', questPoints: 1, difficulty: 'NOVICE', members: false },
  { id: 'ERNEST_THE_CHICKEN', name: 'Ernest the Chicken', questPoints: 4, difficulty: 'NOVICE', members: false },
  { id: 'VAMPIRE_SLAYER', name: 'Vampire Slayer', questPoints: 3, difficulty: 'INTERMEDIATE', members: false },
  { id: 'IMP_CATCHER', name: 'Imp Catcher', questPoints: 1, difficulty: 'NOVICE', members: false },
  { id: 'PRINCE_ALI_RESCUE', name: 'Prince Ali Rescue', questPoints: 3, difficulty: 'INTERMEDIATE', members: false },
  { id: 'DORICS_QUEST', name: "Doric's Quest", questPoints: 1, difficulty: 'NOVICE', members: false },
  { id: 'BLACK_KNIGHTS_FORTRESS', name: "Black Knights' Fortress", questPoints: 3, difficulty: 'INTERMEDIATE', members: false },
  { id: 'WITCHS_POTION', name: "Witch's Potion", questPoints: 1, difficulty: 'NOVICE', members: false },
  { id: 'THE_KNIGHTS_SWORD', name: "The Knight's Sword", questPoints: 1, difficulty: 'INTERMEDIATE', members: false },
  { id: 'GOBLIN_DIPLOMACY', name: 'Goblin Diplomacy', questPoints: 5, difficulty: 'NOVICE', members: false },
  { id: 'PIRATES_TREASURE', name: "Pirate's Treasure", questPoints: 2, difficulty: 'NOVICE', members: false },
  { id: 'RUNE_MYSTERIES', name: 'Rune Mysteries', questPoints: 1, difficulty: 'NOVICE', members: false },
  { id: 'MISTHALIN_MYSTERY', name: 'Misthalin Mystery', questPoints: 1, difficulty: 'NOVICE', members: false },
  { id: 'THE_CORSAIR_CURSE', name: 'The Corsair Curse', questPoints: 2, difficulty: 'NOVICE', members: false },
  { id: 'X_MARKS_THE_SPOT', name: 'X Marks the Spot', questPoints: 1, difficulty: 'NOVICE', members: false },
  { id: 'BELOW_ICE_MOUNTAIN', name: 'Below Ice Mountain', questPoints: 1, difficulty: 'NOVICE', members: false },
  { id: 'DRAGON_SLAYER_I', name: 'Dragon Slayer I', questPoints: 2, difficulty: 'EXPERIENCED', members: false },
  
  // Members quests (selected)
  { id: 'DRUIDIC_RITUAL', name: 'Druidic Ritual', questPoints: 4, difficulty: 'NOVICE', members: true },
  { id: 'LOST_CITY', name: 'Lost City', questPoints: 3, difficulty: 'EXPERIENCED', members: true },
  { id: 'WATERFALL_QUEST', name: 'Waterfall Quest', questPoints: 1, difficulty: 'INTERMEDIATE', members: true },
  { id: 'TREE_GNOME_VILLAGE', name: 'Tree Gnome Village', questPoints: 2, difficulty: 'INTERMEDIATE', members: true },
  { id: 'FIGHT_ARENA', name: 'Fight Arena', questPoints: 2, difficulty: 'EXPERIENCED', members: true },
  { id: 'MONKEY_MADNESS_I', name: 'Monkey Madness I', questPoints: 3, difficulty: 'MASTER', members: true },
  { id: 'RECIPE_FOR_DISASTER', name: 'Recipe for Disaster', questPoints: 10, difficulty: 'GRANDMASTER', members: true },
  { id: 'DESERT_TREASURE', name: 'Desert Treasure', questPoints: 3, difficulty: 'MASTER', members: true },
  { id: 'REGICIDE', name: 'Regicide', questPoints: 3, difficulty: 'MASTER', members: true },
  { id: 'SONG_OF_THE_ELVES', name: 'Song of the Elves', questPoints: 4, difficulty: 'GRANDMASTER', members: true },
  { id: 'DRAGON_SLAYER_II', name: 'Dragon Slayer II', questPoints: 5, difficulty: 'GRANDMASTER', members: true },
];

/**
 * Get all quests.
 */
export function getQuests(): QuestInfo[] {
  return QUESTS;
}

/**
 * Get quests filtered by members status.
 */
export function getQuestsFiltered(membersOnly?: boolean): QuestInfo[] {
  if (membersOnly === undefined) {
    return QUESTS;
  }
  return QUESTS.filter(q => q.members === membersOnly);
}

// ============================================================================
// Utilities
// ============================================================================

/**
 * Format skill name from UPPER_CASE to Title Case.
 */
function formatSkillName(skill: string): string {
  return skill
    .split('_')
    .map(word => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
    .join(' ');
}

/**
 * Clear all caches (for testing).
 */
export function clearCaches(): void {
  cachedTrainingMethods = null;
  cachedLocations = null;
  trainingMethodsCacheTime = 0;
  locationsCacheTime = 0;
}

