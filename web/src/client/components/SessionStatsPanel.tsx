import { Component, Show, For, createMemo, createSignal } from 'solid-js';
import type { SessionInfo, SkillName } from '../../shared/types';
import { formatDuration, formatXp, formatXpShort, SKILLS } from '../../shared/types';
import { XpGainBadge } from './SkillIcon';

interface SessionStatsPanelProps {
  session: SessionInfo | null;
  class?: string;
}

/**
 * Session statistics panel showing runtime, breaks, fatigue, and XP.
 */
export const SessionStatsPanel: Component<SessionStatsPanelProps> = (props) => {
  const [expanded, setExpanded] = createSignal(false);

  // Format runtime
  const formattedRuntime = createMemo(() => {
    if (!props.session) return '00:00:00';
    return formatDuration(props.session.runtimeMs);
  });

  // Format break duration
  const formattedBreakTime = createMemo(() => {
    if (!props.session) return '00:00:00';
    return formatDuration(props.session.breaks.totalDuration);
  });

  // Format fatigue as percentage
  const fatiguePercent = createMemo(() => {
    if (!props.session) return 0;
    return Math.round(props.session.fatigue * 100);
  });

  // Format total XP
  const formattedTotalXp = createMemo(() => {
    if (!props.session) return '0';
    return formatXp(props.session.totalXp);
  });

  // Format XP/hr
  const formattedXpPerHour = createMemo(() => {
    if (!props.session || !props.session.xpPerHour) return '0';
    return formatXpShort(Math.round(props.session.xpPerHour));
  });

  // Get skills with XP gained
  const skillsWithXp = createMemo(() => {
    if (!props.session?.xpGained) return [];
    return Object.entries(props.session.xpGained)
      .filter(([_, xp]) => xp > 0)
      .sort(([_, a], [__, b]) => b - a) as [SkillName, number][];
  });

  // Calculate XP per hour for each skill
  const getSkillXpPerHour = (skillXp: number): number => {
    if (!props.session || props.session.runtimeMs < 1000) return 0;
    const hours = props.session.runtimeMs / 3600000;
    return Math.round(skillXp / hours);
  };

  // Fatigue bar color
  const fatigueColor = createMemo(() => {
    const level = fatiguePercent();
    if (level < 30) return 'bg-green-500';
    if (level < 60) return 'bg-yellow-500';
    if (level < 80) return 'bg-orange-500';
    return 'bg-red-500';
  });

  return (
    <div class={`bg-gray-800/60 rounded-lg border border-gray-700 ${props.class || ''}`}>
      {/* Header with basic stats */}
      <div
        class="px-4 py-3 cursor-pointer hover:bg-gray-700/30 transition-colors rounded-t-lg"
        onClick={() => setExpanded(!expanded())}
      >
        <div class="flex items-center justify-between mb-3">
          <h3 class="font-semibold text-gray-100 flex items-center gap-2">
            <svg class="w-5 h-5 text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" 
                d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            Session Stats
          </h3>
          <svg
            class={`w-5 h-5 text-gray-400 transform transition-transform ${expanded() ? 'rotate-180' : ''}`}
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
          </svg>
        </div>

        {/* Quick stats grid */}
        <div class="grid grid-cols-2 sm:grid-cols-4 gap-3">
          {/* Runtime */}
          <div class="bg-gray-700/50 rounded px-3 py-2">
            <div class="text-xs text-gray-400 mb-0.5">Runtime</div>
            <div class="font-mono text-lg text-white">{formattedRuntime()}</div>
          </div>

          {/* Total XP */}
          <div class="bg-gray-700/50 rounded px-3 py-2">
            <div class="text-xs text-gray-400 mb-0.5">Total XP</div>
            <div class="text-lg text-green-400 font-semibold">{formattedTotalXp()}</div>
          </div>

          {/* XP/hr */}
          <div class="bg-gray-700/50 rounded px-3 py-2">
            <div class="text-xs text-gray-400 mb-0.5">XP/hr</div>
            <div class="text-lg text-cyan-400 font-semibold">{formattedXpPerHour()}</div>
          </div>

          {/* Actions */}
          <div class="bg-gray-700/50 rounded px-3 py-2">
            <div class="text-xs text-gray-400 mb-0.5">Actions</div>
            <div class="text-lg text-white">{props.session?.actions?.toLocaleString() || '0'}</div>
          </div>
        </div>
      </div>

      {/* Expanded details */}
      <Show when={expanded()}>
        <div class="px-4 pb-4 border-t border-gray-700 pt-3 space-y-4">
          {/* Breaks & Fatigue */}
          <div class="grid grid-cols-2 gap-4">
            {/* Breaks */}
            <div class="bg-gray-700/30 rounded px-3 py-2">
              <div class="text-xs text-gray-400 mb-1">Breaks Taken</div>
              <div class="flex items-center justify-between">
                <span class="text-lg font-semibold text-white">{props.session?.breaks.count || 0}</span>
                <span class="text-sm text-gray-400">{formattedBreakTime()}</span>
              </div>
            </div>

            {/* Fatigue */}
            <div class="bg-gray-700/30 rounded px-3 py-2">
              <div class="text-xs text-gray-400 mb-1">Fatigue Level</div>
              <div class="flex items-center gap-2">
                <div class="flex-1 h-2 bg-gray-600 rounded-full overflow-hidden">
                  <div
                    class={`h-full transition-all duration-500 ${fatigueColor()}`}
                    style={{ width: `${fatiguePercent()}%` }}
                  />
                </div>
                <span class="text-sm font-semibold text-white w-10 text-right">{fatiguePercent()}%</span>
              </div>
            </div>
          </div>

          {/* XP Gains by skill */}
          <Show when={skillsWithXp().length > 0}>
            <div>
              <h4 class="text-sm font-medium text-gray-400 mb-2">XP Gained</h4>
              <div class="grid grid-cols-2 sm:grid-cols-3 gap-2">
                <For each={skillsWithXp()}>
                  {([skill, xp]) => (
                    <XpGainBadge
                      skill={skill}
                      xpGained={xp}
                      xpPerHour={getSkillXpPerHour(xp)}
                    />
                  )}
                </For>
              </div>
            </div>
          </Show>

          {/* No XP message */}
          <Show when={skillsWithXp().length === 0}>
            <p class="text-sm text-gray-500 italic text-center py-2">
              No XP gained yet this session
            </p>
          </Show>
        </div>
      </Show>
    </div>
  );
};

/**
 * Compact inline session stats for header/status bar usage.
 */
interface InlineSessionStatsProps {
  session: SessionInfo | null;
  class?: string;
}

export const InlineSessionStats: Component<InlineSessionStatsProps> = (props) => {
  const formattedRuntime = createMemo(() => {
    if (!props.session) return '00:00:00';
    return formatDuration(props.session.runtimeMs);
  });

  const formattedXpPerHour = createMemo(() => {
    if (!props.session || !props.session.xpPerHour) return '0';
    return formatXpShort(Math.round(props.session.xpPerHour));
  });

  return (
    <div class={`flex items-center gap-4 text-sm ${props.class || ''}`}>
      <div class="flex items-center gap-1.5">
        <svg class="w-4 h-4 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" 
            d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
        <span class="font-mono text-gray-200">{formattedRuntime()}</span>
      </div>
      <div class="flex items-center gap-1.5">
        <span class="text-green-400">{formattedXpPerHour()}</span>
        <span class="text-gray-500">xp/hr</span>
      </div>
    </div>
  );
};

export default SessionStatsPanel;

