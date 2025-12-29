import { Component, Show, For, createMemo, createSignal } from 'solid-js';
import type { PlayerInfo, SkillData, SkillName } from '../../shared/types';
import { SKILLS, formatXp, XP_TABLE, getLevel } from '../../shared/types';
import { SkillIcon } from './SkillIcon';

/**
 * Skill layout for the RS stats tab - 3 columns x 8 rows.
 * This mirrors the in-game layout.
 */
const SKILL_GRID: (SkillName | null)[][] = [
  ['Attack', 'Hitpoints', 'Mining'],
  ['Strength', 'Agility', 'Smithing'],
  ['Defence', 'Herblore', 'Fishing'],
  ['Ranged', 'Thieving', 'Cooking'],
  ['Prayer', 'Crafting', 'Firemaking'],
  ['Magic', 'Fletching', 'Woodcutting'],
  ['Runecraft', 'Slayer', 'Farming'],
  ['Construction', 'Hunter', null], // Last cell is empty or total
];

interface AccountStatsGridProps {
  player: PlayerInfo | null;
  class?: string;
}

/**
 * RS-style stats tab with 3x8 skill grid.
 * Displays skills with icons and levels in the familiar in-game layout.
 */
export const AccountStatsGrid: Component<AccountStatsGridProps> = (props) => {
  const [selectedSkill, setSelectedSkill] = createSignal<SkillName | null>(null);

  // Get skill data
  const getSkillData = (skill: SkillName): SkillData | null => {
    if (!props.player?.skills) return null;
    return props.player.skills[skill] || null;
  };

  // Calculate total level
  const totalLevel = createMemo(() => {
    if (!props.player?.skills) return 0;
    return Object.values(props.player.skills).reduce((sum, skill) => sum + (skill?.level || 0), 0);
  });

  // Calculate total XP
  const totalXp = createMemo(() => {
    if (!props.player?.skills) return 0;
    return Object.values(props.player.skills).reduce((sum, skill) => sum + (skill?.xp || 0), 0);
  });

  // Selected skill details
  const selectedSkillData = createMemo(() => {
    const skill = selectedSkill();
    if (!skill) return null;
    const data = getSkillData(skill);
    if (!data) return null;

    const level = data.level;
    const currentXp = data.xp;
    const nextLevelXp = level < 99 ? XP_TABLE[level + 1] : XP_TABLE[99];
    const currentLevelXp = XP_TABLE[level];
    const xpToNext = level < 99 ? nextLevelXp - currentXp : 0;
    const progress = level < 99 
      ? ((currentXp - currentLevelXp) / (nextLevelXp - currentLevelXp)) * 100 
      : 100;

    return {
      skill,
      level,
      boosted: data.boosted,
      xp: currentXp,
      xpToNext,
      progress,
      nextLevelXp,
    };
  });

  return (
    <div class={`bg-gray-800/60 rounded-lg border border-gray-700 p-4 ${props.class || ''}`}>
      {/* Header */}
      <div class="flex items-center mb-4">
        <h3 class="font-semibold text-gray-100 flex items-center gap-2">
          <svg class="w-5 h-5 text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" 
              d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
          </svg>
          Skills
        </h3>
      </div>

      {/* Player info */}
      <Show when={props.player}>
        <div class="mb-4 pb-3 border-b border-gray-700 flex items-center justify-between">
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 rounded-full bg-gray-700 flex items-center justify-center">
              <span class="text-lg">⚔️</span>
            </div>
            <div>
              <div class="font-semibold text-gray-100">{props.player!.name || 'Unknown'}</div>
              <div class="text-sm text-gray-400">Combat Level {props.player!.combatLevel}</div>
            </div>
          </div>
          <div class="text-right flex items-center gap-2">
            <div>
              <div class="text-sm text-gray-400">Quest Points</div>
              <div class="text-lg font-semibold text-amber-300">{props.player!.questPoints}</div>
            </div>
            <img 
              src="https://oldschool.runescape.wiki/images/Quest_point_icon.png" 
              alt="Quest Points"
              class="w-8 h-8 object-contain"
              loading="lazy"
            />
          </div>
        </div>
      </Show>

      {/* Skill grid - RS stats tab layout */}
      <div class="grid grid-cols-3 gap-1.5 mb-4">
        <For each={SKILL_GRID}>
          {(row) => (
            <For each={row}>
              {(skill) => (
                <Show
                  when={skill}
                  fallback={
                    // Total level cell
                    <div class="bg-gray-700/50 rounded flex flex-col items-center justify-center p-2">
                      <span class="text-xs text-gray-400">Total</span>
                      <span class="text-lg font-bold text-amber-300">{totalLevel()}</span>
                    </div>
                  }
                >
                  {(s) => {
                    const skillData = () => getSkillData(s());
                    const isSelected = () => selectedSkill() === s();
                    
                    return (
                      <div
                        class={`relative flex items-center gap-1.5 p-1.5 rounded cursor-pointer transition-all ${
                          isSelected() 
                            ? 'bg-amber-600/30 ring-1 ring-amber-400' 
                            : 'bg-gray-700/50 hover:bg-gray-700'
                        }`}
                        onClick={() => setSelectedSkill(isSelected() ? null : s())}
                      >
                        <SkillIcon
                          skill={s()}
                          level={skillData()?.level}
                          boosted={skillData()?.boosted}
                          xp={skillData()?.xp}
                          size="sm"
                          showLevel={false}
                        />
                        <div class="flex-1 min-w-0">
                          <div class="text-xs text-gray-400 truncate">{s()}</div>
                          <div class="font-semibold text-white text-sm">{skillData()?.level || 1}</div>
                        </div>
                      </div>
                    );
                  }}
                </Show>
              )}
            </For>
          )}
        </For>
      </div>

      {/* Selected skill details */}
      <Show when={selectedSkillData()}>
        {(data) => (
          <div class="bg-gray-700/30 rounded p-3 mt-3">
            <div class="flex items-center gap-3 mb-3">
              <SkillIcon
                skill={data().skill}
                level={data().level}
                boosted={data().boosted}
                xp={data().xp}
                size="lg"
                showLevel
                showBoosted
              />
              <div>
                <div class="font-semibold text-lg text-white">{data().skill}</div>
                <div class="text-sm text-gray-300">
                  Level {data().level}
                  <Show when={data().boosted !== data().level}>
                    <span class={data().boosted > data().level ? 'text-green-400' : 'text-red-400'}>
                      {' '}({data().boosted > data().level ? '+' : ''}{data().boosted - data().level})
                    </span>
                  </Show>
                </div>
              </div>
            </div>

            <div class="space-y-2">
              <div class="flex justify-between text-sm">
                <span class="text-gray-400">XP:</span>
                <span class="text-white font-mono">{formatXp(data().xp)}</span>
              </div>
              <Show when={data().level < 99}>
                <div class="flex justify-between text-sm">
                  <span class="text-gray-400">XP to next level:</span>
                  <span class="text-amber-300 font-mono">{formatXp(data().xpToNext)}</span>
                </div>
                <div>
                  <div class="flex justify-between text-xs text-gray-400 mb-1">
                    <span>Progress to {data().level + 1}</span>
                    <span>{Math.round(data().progress)}%</span>
                  </div>
                  <div class="h-2 bg-gray-600 rounded-full overflow-hidden">
                    <div
                      class="h-full bg-gradient-to-r from-amber-500 to-yellow-400 transition-all duration-300"
                      style={{ width: `${data().progress}%` }}
                    />
                  </div>
                </div>
              </Show>
              <Show when={data().level >= 99}>
                <div class="text-center text-amber-400 text-sm font-semibold mt-2">
                  ✨ Mastered ✨
                </div>
              </Show>
            </div>
          </div>
        )}
      </Show>

      {/* No data message */}
      <Show when={!props.player}>
        <div class="text-center py-8 text-gray-500">
          <div class="text-4xl mb-2">📊</div>
          <p>No player data available</p>
        </div>
      </Show>
    </div>
  );
};

export default AccountStatsGrid;

