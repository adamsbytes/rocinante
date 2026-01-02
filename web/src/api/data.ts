/**
 * Data API functions for serving bot data to the frontend.
 * Reads data files from the bot's resources directory.
 */

import type { TrainingMethodInfo, MethodLocationInfo, LocationInfo, SkillName } from '../shared/types';

// Path to bot's data directory (symlinked at web/bot-data -> ../bot/src/main/resources/data)
const BOT_DATA_PATH = process.env.BOT_DATA_PATH || './bot-data';

// ============================================================================
// Training Methods
// ============================================================================

interface RawMethodLocation {
  name: string;
  locationId: string;
  actionsPerHour: number;
  gpPerHour?: number;
  bankLocationId?: string;
  notes?: string;
  requirements?: {
    members?: boolean;
    quests?: string[];
  };
}

interface RawTrainingMethod {
  id: string;
  name: string;
  skill: string;
  methodType: string;
  minLevel: number;
  xpPerAction?: number;
  xpMultiplier?: number;
  ironmanViable?: boolean;
  locations?: RawMethodLocation[];
  notes?: string;
  requirements?: {
    members?: boolean;
  };
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
    
    cachedTrainingMethods = data.methods.map(m => {
      // Parse locations array - use actual location ID from JSON (must match bot's TrainingMethod)
      const locations: MethodLocationInfo[] = (m.locations || []).map((loc) => ({
        id: loc.id,
        name: loc.name,
        locationId: loc.locationId,
        actionsPerHour: loc.actionsPerHour,
        gpPerHour: loc.gpPerHour ?? 0,
        bankLocationId: loc.bankLocationId,
        notes: loc.notes,
        membersOnly: loc.requirements?.members,
        questRequirements: loc.requirements?.quests,
      }));
      
      return {
        id: m.id,
        name: m.name,
        skill: formatSkillName(m.skill) as SkillName,
        methodType: m.methodType as TrainingMethodInfo['methodType'],
        minLevel: m.minLevel,
        xpPerAction: m.xpPerAction ?? 0,
        xpMultiplier: m.xpMultiplier ?? 0,
        ironmanViable: m.ironmanViable ?? true,
        locations,
        notes: m.notes,
        membersOnly: m.requirements?.members,
      };
    });
    
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

interface LocationsJsonFile {
  version: string;
  generated: string;
  source: string;
  locations: Array<{
    id: string;
    name: string;
    x: number;
    y: number;
    plane: number;
    type: string;
    tags: string[];
    metadata?: Record<string, string>;
  }>;
}

let cachedLocations: LocationInfo[] | null = null;
let locationsCacheTime = 0;

/**
 * Load and parse navigation locations from locations.json.
 * Data extracted from shortest-path plugin + curated city centers.
 */
export async function getLocations(): Promise<LocationInfo[]> {
  const now = Date.now();
  
  // Return cached data if still valid
  if (cachedLocations && (now - locationsCacheTime) < CACHE_TTL_MS) {
    return cachedLocations;
  }

  try {
    const file = Bun.file(`${BOT_DATA_PATH}/locations.json`);
    if (await file.exists()) {
      const data: LocationsJsonFile = await file.json();
      cachedLocations = data.locations.map(loc => ({
        id: loc.id,
        name: loc.name,
        x: loc.x,
        y: loc.y,
        plane: loc.plane,
        type: loc.type as LocationInfo['type'],
        tags: loc.tags,
      }));
      locationsCacheTime = now;
      return cachedLocations;
    }
    
    console.error('locations.json not found');
    return [];
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

