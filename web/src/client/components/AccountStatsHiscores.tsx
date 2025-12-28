import { Component, Show, For, createMemo, createSignal } from 'solid-js';
import type { PlayerInfo, SkillData, SkillName, SessionInfo } from '../../shared/types';
import { SKILLS, formatXp, formatXpShort, XP_TABLE, getLevel } from '../../shared/types';
import { SkillIcon } from './SkillIcon';

interface AccountStatsHiscoresProps {
  player: PlayerInfo | null;
  session?: SessionInfo | null;
  class?: string;
}

type SortField = 'name' | 'level' | 'xp' | 'gained';
type SortDirection = 'asc' | 'desc';

/**
 * Detailed hiscores-style table view of all skills.
 * Shows level, XP, and optionally XP gained in the session.
 */
export const AccountStatsHiscores: Component<AccountStatsHiscoresProps> = (props) => {
  const [sortField, setSortField] = createSignal<SortField>('name');
  const [sortDirection, setSortDirection] = createSignal<SortDirection>('asc');
  const [showGained, setShowGained] = createSignal(true);

  // Get skill data
  const getSkillData = (skill: SkillName): SkillData | null => {
    if (!props.player?.skills) return null;
    return props.player.skills[skill] || null;
  };

  // Get XP gained for a skill
  const getXpGained = (skill: SkillName): number => {
    if (!props.session?.xpGained) return 0;
    return props.session.xpGained[skill] || 0;
  };

  // Sort skills
  const sortedSkills = createMemo(() => {
    const skills = [...SKILLS];
    const field = sortField();
    const direction = sortDirection();

    return skills.sort((a, b) => {
      let comparison = 0;
      
      switch (field) {
        case 'name':
          comparison = a.localeCompare(b);
          break;
        case 'level': {
          const levelA = getSkillData(a)?.level || 1;
          const levelB = getSkillData(b)?.level || 1;
          comparison = levelA - levelB;
          break;
        }
        case 'xp': {
          const xpA = getSkillData(a)?.xp || 0;
          const xpB = getSkillData(b)?.xp || 0;
          comparison = xpA - xpB;
          break;
        }
        case 'gained': {
          const gainedA = getXpGained(a);
          const gainedB = getXpGained(b);
          comparison = gainedA - gainedB;
          break;
        }
      }

      return direction === 'asc' ? comparison : -comparison;
    });
  });

  // Toggle sort
  const toggleSort = (field: SortField) => {
    if (sortField() === field) {
      setSortDirection(d => d === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortDirection(field === 'name' ? 'asc' : 'desc');
    }
  };

  // Calculate totals
  const totalLevel = createMemo(() => {
    if (!props.player?.skills) return 0;
    return Object.values(props.player.skills).reduce((sum, skill) => sum + (skill?.level || 0), 0);
  });

  const totalXp = createMemo(() => {
    if (!props.player?.skills) return 0;
    return Object.values(props.player.skills).reduce((sum, skill) => sum + (skill?.xp || 0), 0);
  });

  const totalGained = createMemo(() => {
    if (!props.session?.xpGained) return 0;
    return Object.values(props.session.xpGained).reduce((sum, xp) => sum + (xp || 0), 0);
  });

  // Sort indicator
  const SortIndicator: Component<{ field: SortField }> = (p) => (
    <Show when={sortField() === p.field}>
      <span class="ml-1">
        {sortDirection() === 'asc' ? '▲' : '▼'}
      </span>
    </Show>
  );

  return (
    <div class={`bg-gray-800/60 rounded-lg border border-gray-700 ${props.class || ''}`}>
      {/* Header */}
      <div class="flex items-center justify-between px-4 py-3 border-b border-gray-700">
        <h3 class="font-semibold text-gray-100 flex items-center gap-2">
          <svg class="w-5 h-5 text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" 
              d="M4 6h16M4 10h16M4 14h16M4 18h16" />
          </svg>
          Detailed Stats
        </h3>
        <Show when={props.session}>
          <label class="flex items-center gap-2 text-sm cursor-pointer">
            <input
              type="checkbox"
              checked={showGained()}
              onChange={(e) => setShowGained(e.currentTarget.checked)}
              class="rounded border-gray-600 bg-gray-700 text-amber-500 focus:ring-amber-500"
            />
            <span class="text-gray-300">Show XP Gained</span>
          </label>
        </Show>
      </div>

      {/* Table */}
      <div class="overflow-x-auto">
        <table class="w-full">
          <thead>
            <tr class="bg-gray-700/30 text-left text-sm">
              <th
                class="px-4 py-2 cursor-pointer hover:bg-gray-700/50 transition-colors"
                onClick={() => toggleSort('name')}
              >
                <span class="flex items-center">
                  Skill
                  <SortIndicator field="name" />
                </span>
              </th>
              <th
                class="px-4 py-2 cursor-pointer hover:bg-gray-700/50 transition-colors text-right"
                onClick={() => toggleSort('level')}
              >
                <span class="flex items-center justify-end">
                  Level
                  <SortIndicator field="level" />
                </span>
              </th>
              <th
                class="px-4 py-2 cursor-pointer hover:bg-gray-700/50 transition-colors text-right"
                onClick={() => toggleSort('xp')}
              >
                <span class="flex items-center justify-end">
                  XP
                  <SortIndicator field="xp" />
                </span>
              </th>
              <Show when={showGained() && props.session}>
                <th
                  class="px-4 py-2 cursor-pointer hover:bg-gray-700/50 transition-colors text-right"
                  onClick={() => toggleSort('gained')}
                >
                  <span class="flex items-center justify-end">
                    Gained
                    <SortIndicator field="gained" />
                  </span>
                </th>
              </Show>
            </tr>
          </thead>
          <tbody>
            <For each={sortedSkills()}>
              {(skill) => {
                const data = () => getSkillData(skill);
                const gained = () => getXpGained(skill);

                return (
                  <tr class="border-t border-gray-700/50 hover:bg-gray-700/20 transition-colors">
                    <td class="px-4 py-2">
                      <div class="flex items-center gap-2">
                        <SkillIcon skill={skill} size="sm" />
                        <span class="text-gray-200">{skill}</span>
                      </div>
                    </td>
                    <td class="px-4 py-2 text-right">
                      <span class="font-semibold text-white">
                        {data()?.level || 1}
                      </span>
                      <Show when={data()?.boosted && data()?.boosted !== data()?.level}>
                        <span class={`ml-1 text-sm ${(data()?.boosted || 0) > (data()?.level || 0) ? 'text-green-400' : 'text-red-400'}`}>
                          ({data()?.boosted})
                        </span>
                      </Show>
                    </td>
                    <td class="px-4 py-2 text-right font-mono text-sm text-gray-300">
                      {formatXp(data()?.xp || 0)}
                    </td>
                    <Show when={showGained() && props.session}>
                      <td class="px-4 py-2 text-right">
                        <Show when={gained() > 0} fallback={<span class="text-gray-600">—</span>}>
                          <span class="text-green-400 font-semibold">
                            +{formatXpShort(gained())}
                          </span>
                        </Show>
                      </td>
                    </Show>
                  </tr>
                );
              }}
            </For>
            {/* Totals row */}
            <tr class="border-t-2 border-gray-600 bg-gray-700/30 font-semibold">
              <td class="px-4 py-2 text-amber-300">
                Total
              </td>
              <td class="px-4 py-2 text-right text-amber-300">
                {totalLevel()}
              </td>
              <td class="px-4 py-2 text-right font-mono text-amber-300">
                {formatXp(totalXp())}
              </td>
              <Show when={showGained() && props.session}>
                <td class="px-4 py-2 text-right text-green-400">
                  +{formatXpShort(totalGained())}
                </td>
              </Show>
            </tr>
          </tbody>
        </table>
      </div>

      {/* No data message */}
      <Show when={!props.player}>
        <div class="text-center py-8 text-gray-500">
          <div class="text-4xl mb-2">📋</div>
          <p>No player data available</p>
        </div>
      </Show>
    </div>
  );
};

/**
 * Compact skill summary for quick overview.
 * Shows RuneScape-style format: "Attack: 99 (13,034,431 XP)"
 */
interface SkillSummaryProps {
  skill: SkillName;
  level: number;
  xp: number;
  xpGained?: number;
  class?: string;
}

export const SkillSummary: Component<SkillSummaryProps> = (props) => {
  return (
    <div class={`flex items-center gap-2 ${props.class || ''}`}>
      <SkillIcon skill={props.skill} size="sm" />
      <span class="text-amber-200">
        {props.skill}: <span class="text-white font-semibold">{props.level}</span>
      </span>
      <span class="text-gray-400 text-sm">
        ({formatXp(props.xp)} XP)
      </span>
      <Show when={props.xpGained && props.xpGained > 0}>
        <span class="text-green-400 text-sm">
          +{formatXpShort(props.xpGained!)}
        </span>
      </Show>
    </div>
  );
};

export default AccountStatsHiscores;

