import { Component, Show, createMemo } from 'solid-js';
import type { SkillName } from '../../shared/types';
import { formatXp, getLevel, XP_TABLE } from '../../shared/types';

/**
 * OSRS skill icon backgrounds - matches the in-game stats tab colors.
 */
const SKILL_COLORS: Record<SkillName, string> = {
  Attack: '#9B1C1C',
  Hitpoints: '#7E1F1F',
  Mining: '#5C5C5C',
  Strength: '#2F855A',
  Agility: '#1E3A5F',
  Smithing: '#4A5568',
  Defence: '#2B6CB0',
  Herblore: '#276749',
  Fishing: '#2C5282',
  Ranged: '#48BB78',
  Thieving: '#6B46C1',
  Cooking: '#9B2C2C',
  Prayer: '#D69E2E',
  Crafting: '#8B5CF6',
  Firemaking: '#DD6B20',
  Magic: '#805AD5',
  Fletching: '#38A169',
  Woodcutting: '#68532F',
  Runecraft: '#B7791F',
  Slayer: '#1A202C',
  Farming: '#48BB78',
  Construction: '#8B6914',
  Hunter: '#744210',
};

/**
 * Skill icon URLs from OSRS Wiki (small icons).
 */
const getSkillIconUrl = (skill: SkillName): string => {
  return `https://oldschool.runescape.wiki/images/${skill}_icon.png`;
};

interface SkillIconProps {
  skill: SkillName;
  level?: number;
  xp?: number;
  boosted?: number;
  size?: 'sm' | 'md' | 'lg';
  showLevel?: boolean;
  showXp?: boolean;
  showBoosted?: boolean;
  onClick?: () => void;
  class?: string;
}

/**
 * Skill icon component with OSRS styling.
 * Displays the skill icon with optional level badge and XP tooltip.
 */
export const SkillIcon: Component<SkillIconProps> = (props) => {
  const sizeClasses = {
    sm: 'w-8 h-8',
    md: 'w-12 h-12',
    lg: 'w-16 h-16',
  };

  const levelSizeClasses = {
    sm: 'text-[10px]',
    md: 'text-xs',
    lg: 'text-sm',
  };

  const size = () => props.size || 'md';
  const bgColor = () => SKILL_COLORS[props.skill];

  // Calculate progress to next level
  const progressToNext = createMemo(() => {
    if (!props.xp) return 0;
    const currentLevel = getLevel(props.xp);
    if (currentLevel >= 99) return 100;
    
    const currentLevelXp = XP_TABLE[currentLevel];
    const nextLevelXp = XP_TABLE[currentLevel + 1];
    const progress = (props.xp - currentLevelXp) / (nextLevelXp - currentLevelXp);
    return Math.min(100, Math.max(0, progress * 100));
  });

  // Format XP for tooltip
  const formattedXp = createMemo(() => {
    if (!props.xp) return '';
    return formatXp(props.xp);
  });

  // Determine if boosted (above base level)
  const isBoosted = createMemo(() => {
    if (!props.boosted || !props.level) return false;
    return props.boosted > props.level;
  });

  // Determine if drained (below base level)
  const isDrained = createMemo(() => {
    if (!props.boosted || !props.level) return false;
    return props.boosted < props.level;
  });

  return (
    <div
      class={`relative flex items-center justify-center rounded-sm cursor-pointer transition-transform hover:scale-105 ${sizeClasses[size()]} ${props.class || ''}`}
      style={{ 'background-color': bgColor() }}
      onClick={props.onClick}
      title={props.showXp && props.xp ? `${props.skill}: ${formattedXp()} XP` : props.skill}
    >
      {/* Skill Icon */}
      <img
        src={getSkillIconUrl(props.skill)}
        alt={props.skill}
        class="w-[70%] h-[70%] object-contain drop-shadow-sm"
        loading="lazy"
      />

      {/* Level Badge */}
      <Show when={props.showLevel && props.level !== undefined}>
        <div
          class={`absolute -bottom-0.5 -right-0.5 min-w-[1.25rem] h-5 flex items-center justify-center rounded-sm px-0.5 font-bold shadow-md ${levelSizeClasses[size()]}`}
          classList={{
            'bg-yellow-500 text-black': !isBoosted() && !isDrained(),
            'bg-green-500 text-white': isBoosted(),
            'bg-red-500 text-white': isDrained(),
          }}
        >
          {props.showBoosted && props.boosted !== undefined ? props.boosted : props.level}
        </div>
      </Show>

      {/* Progress bar (for when showing XP progress) */}
      <Show when={props.showXp && props.xp !== undefined && props.level !== undefined && props.level < 99}>
        <div class="absolute bottom-0 left-0 right-0 h-0.5 bg-black/50 rounded-b-sm overflow-hidden">
          <div
            class="h-full bg-yellow-400 transition-all duration-300"
            style={{ width: `${progressToNext()}%` }}
          />
        </div>
      </Show>
    </div>
  );
};

interface SkillBadgeProps {
  skill: SkillName;
  level: number;
  xp: number;
  xpGained?: number;
  class?: string;
}

/**
 * Skill badge showing skill name, level, and XP in RuneScape style.
 * Format: "Attack: 99 (13,034,431 XP)"
 */
export const SkillBadge: Component<SkillBadgeProps> = (props) => {
  const formattedXp = createMemo(() => formatXp(props.xp));
  const formattedGained = createMemo(() => props.xpGained ? formatXp(props.xpGained) : null);

  return (
    <div class={`flex items-center gap-2 ${props.class || ''}`}>
      <SkillIcon skill={props.skill} size="sm" />
      <div class="flex flex-col">
        <span class="text-sm font-semibold text-amber-200">
          {props.skill}: <span class="text-white">{props.level}</span>
        </span>
        <span class="text-xs text-gray-400">
          {formattedXp()} XP
          <Show when={formattedGained()}>
            <span class="text-green-400"> (+{formattedGained()})</span>
          </Show>
        </span>
      </div>
    </div>
  );
};

interface XpGainBadgeProps {
  skill: SkillName;
  xpGained: number;
  xpPerHour?: number;
  class?: string;
}

/**
 * Badge showing XP gained for a skill with optional rate.
 */
export const XpGainBadge: Component<XpGainBadgeProps> = (props) => {
  const formattedGained = createMemo(() => formatXp(props.xpGained));
  const formattedRate = createMemo(() => {
    if (!props.xpPerHour) return null;
    return formatXp(Math.round(props.xpPerHour));
  });

  return (
    <div class={`flex items-center gap-2 bg-gray-800/50 rounded px-2 py-1 ${props.class || ''}`}>
      <SkillIcon skill={props.skill} size="sm" />
      <div class="flex flex-col">
        <span class="text-sm text-green-400 font-semibold">+{formattedGained()}</span>
        <Show when={formattedRate()}>
          <span class="text-xs text-gray-400">{formattedRate()}/hr</span>
        </Show>
      </div>
    </div>
  );
};

export default SkillIcon;

