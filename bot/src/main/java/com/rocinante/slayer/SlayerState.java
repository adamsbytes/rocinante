package com.rocinante.slayer;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.NPC;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of slayer-specific state at a point in time.
 *
 * Per REQUIREMENTS.md Section 6.2.7 and 11.1, captures:
 * <ul>
 *   <li>Current task name and location (from RuneLite SlayerPluginService)</li>
 *   <li>Kill counts (initial, remaining)</li>
 *   <li>Slayer points and task streak</li>
 *   <li>Current slayer master</li>
 *   <li>Unlocked rewards</li>
 *   <li>Blocked/extended tasks</li>
 *   <li>Target NPCs (from RuneLite SlayerPlugin)</li>
 * </ul>
 *
 * Data is combined from:
 * <ul>
 *   <li>RuneLite SlayerPluginService (injected) - task name, location, counts, targets</li>
 *   <li>Client varbits - points, streak, master ID, unlocks, blocks</li>
 * </ul>
 *
 * Uses TICK_CACHED policy for real-time slayer tracking.
 */
@Value
@Builder
public class SlayerState {

    /**
     * Empty slayer state for when no task is active.
     */
    public static final SlayerState EMPTY = SlayerState.builder()
            .taskName(null)
            .taskLocation(null)
            .remainingKills(0)
            .initialKills(0)
            .slayerPoints(0)
            .taskStreak(0)
            .wildernessStreak(0)
            .currentMaster(null)
            .unlocks(Collections.emptySet())
            .blockedTasks(Collections.emptySet())
            .extendedTasks(Collections.emptySet())
            .targetNpcs(Collections.emptyList())
            .slayerLevel(1)
            .build();

    // ========================================================================
    // Task Information (from SlayerPluginService)
    // ========================================================================

    /**
     * Name of the current slayer task (e.g., "Black demons", "Abyssal demons").
     * Null if no active task.
     */
    @Nullable
    String taskName;

    /**
     * Location restriction for the current task (Konar only).
     * Null for non-Konar tasks or if no task active.
     */
    @Nullable
    String taskLocation;

    /**
     * Number of kills remaining for the current task.
     * 0 if no task or task complete.
     */
    int remainingKills;

    /**
     * Initial number of kills assigned for the current task.
     * Used to calculate progress percentage.
     */
    int initialKills;

    // ========================================================================
    // Points and Streaks (from Varbits)
    // ========================================================================

    /**
     * Current slayer reward points.
     * From varbit SLAYER_POINTS (4068).
     */
    int slayerPoints;

    /**
     * Current task completion streak (standard masters).
     * From varbit SLAYER_TASKS_COMPLETED (4069).
     */
    int taskStreak;

    /**
     * Current wilderness task streak (Krystilia only).
     * From varbit SLAYER_WILDERNESS_TASKS_COMPLETED (5617).
     */
    int wildernessStreak;

    // ========================================================================
    // Master Information
    // ========================================================================

    /**
     * The slayer master who assigned the current task.
     * From varbit SLAYER_MASTER (4067).
     * Null if no task or master unknown.
     */
    @Nullable
    SlayerMaster currentMaster;

    // ========================================================================
    // Unlocks and Preferences
    // ========================================================================

    /**
     * Set of unlocked slayer rewards.
     * Populated from various SLAYER_UNLOCK_* varbits.
     */
    @Builder.Default
    Set<SlayerUnlock> unlocks = Collections.emptySet();

    /**
     * Set of blocked task names (creature names).
     * From SLAYER_REWARDS_BLOCKED varplayers.
     */
    @Builder.Default
    Set<String> blockedTasks = Collections.emptySet();

    /**
     * Set of extended task names (longer assignments).
     * Derived from SLAYER_LONGER_* varbits.
     */
    @Builder.Default
    Set<String> extendedTasks = Collections.emptySet();

    // ========================================================================
    // Target NPCs (from SlayerPluginService)
    // ========================================================================

    /**
     * List of NPCs that match the current slayer task.
     * Populated by RuneLite's SlayerPlugin target detection.
     */
    @Builder.Default
    List<NPC> targetNpcs = Collections.emptyList();

    // ========================================================================
    // Player Stats
    // ========================================================================

    /**
     * Player's current slayer level.
     * Used for task eligibility checks.
     */
    int slayerLevel;

    // ========================================================================
    // Task State Queries
    // ========================================================================

    /**
     * Check if the player has an active slayer task.
     *
     * @return true if a task is assigned
     */
    public boolean hasTask() {
        return taskName != null && !taskName.isEmpty() && remainingKills > 0;
    }

    /**
     * Check if the current task is complete.
     *
     * @return true if task was started but now has 0 remaining kills
     */
    public boolean isTaskComplete() {
        return taskName != null && !taskName.isEmpty() && remainingKills == 0 && initialKills > 0;
    }

    /**
     * Check if this is a Konar (location-restricted) task.
     *
     * @return true if task has a location restriction
     */
    public boolean isLocationRestricted() {
        return taskLocation != null && !taskLocation.isEmpty();
    }

    /**
     * Check if this is a wilderness task (from Krystilia).
     *
     * @return true if assigned by Krystilia
     */
    public boolean isWildernessTask() {
        return currentMaster == SlayerMaster.KRYSTILIA;
    }

    // ========================================================================
    // Progress Calculations
    // ========================================================================

    /**
     * Get the number of kills completed for the current task.
     *
     * @return kills completed, or 0 if no task
     */
    public int getKillsCompleted() {
        if (!hasTask() && !isTaskComplete()) {
            return 0;
        }
        return Math.max(0, initialKills - remainingKills);
    }

    /**
     * Get the task completion percentage (0-100).
     *
     * @return percentage complete, or 0 if no task
     */
    public int getCompletionPercentage() {
        if (initialKills <= 0) {
            return 0;
        }
        return Math.min(100, (getKillsCompleted() * 100) / initialKills);
    }

    /**
     * Check if the task is almost complete (80%+).
     *
     * @return true if near completion
     */
    public boolean isNearCompletion() {
        return getCompletionPercentage() >= 80;
    }

    // ========================================================================
    // Point Queries
    // ========================================================================

    /**
     * Check if the player can afford a point cost.
     *
     * @param cost the point cost
     * @return true if enough points
     */
    public boolean canAfford(int cost) {
        return slayerPoints >= cost;
    }

    /**
     * Check if the player can skip a task (35 points).
     *
     * @return true if can afford skip
     */
    public boolean canSkipTask() {
        return slayerPoints >= 30;
    }

    /**
     * Check if the player can block a task (100 points).
     *
     * @return true if can afford block
     */
    public boolean canBlockTask() {
        return slayerPoints >= 100;
    }

    /**
     * Get the relevant streak for the current task type.
     *
     * @return wilderness streak for Krystilia tasks, regular streak otherwise
     */
    public int getRelevantStreak() {
        return isWildernessTask() ? wildernessStreak : taskStreak;
    }

    // ========================================================================
    // Unlock Queries
    // ========================================================================

    /**
     * Check if a specific unlock is active.
     *
     * @param unlock the unlock to check
     * @return true if unlocked
     */
    public boolean hasUnlock(SlayerUnlock unlock) {
        return unlocks.contains(unlock);
    }

    /**
     * Check if slayer helm can be crafted.
     *
     * @return true if unlocked
     */
    public boolean hasSlayerHelm() {
        return hasUnlock(SlayerUnlock.SLAYER_HELM);
    }

    /**
     * Check if superiors can spawn.
     *
     * @return true if unlocked
     */
    public boolean hasSuperiorUnlock() {
        return hasUnlock(SlayerUnlock.SUPERIOR_CREATURES);
    }

    /**
     * Check if boss tasks are available.
     *
     * @return true if unlocked
     */
    public boolean hasBossTasks() {
        return hasUnlock(SlayerUnlock.BOSS_TASKS);
    }

    // ========================================================================
    // Block/Extend Queries
    // ========================================================================

    /**
     * Check if a task is blocked.
     *
     * @param creatureName the creature name
     * @return true if blocked
     */
    public boolean isTaskBlocked(String creatureName) {
        if (creatureName == null || blockedTasks.isEmpty()) {
            return false;
        }
        return blockedTasks.stream()
                .anyMatch(blocked -> blocked.equalsIgnoreCase(creatureName));
    }

    /**
     * Check if the current task is extended.
     *
     * @return true if current task has an extension unlock
     */
    public boolean isCurrentTaskExtended() {
        if (taskName == null || extendedTasks.isEmpty()) {
            return false;
        }
        return extendedTasks.stream()
                .anyMatch(extended -> extended.equalsIgnoreCase(taskName));
    }

    /**
     * Get the number of blocked task slots used.
     *
     * @return count of blocked tasks
     */
    public int getBlockedSlotCount() {
        return blockedTasks.size();
    }

    /**
     * Check if the player can block more tasks.
     * Base slots: 3-6 depending on quest points.
     *
     * @param maxSlots maximum available block slots
     * @return true if more blocks available
     */
    public boolean canBlockMore(int maxSlots) {
        return blockedTasks.size() < maxSlots;
    }

    // ========================================================================
    // Target NPC Queries
    // ========================================================================

    /**
     * Check if there are any valid targets nearby.
     *
     * @return true if target NPCs are present
     */
    public boolean hasTargetsNearby() {
        return !targetNpcs.isEmpty();
    }

    /**
     * Get the count of target NPCs.
     *
     * @return number of valid targets
     */
    public int getTargetCount() {
        return targetNpcs.size();
    }

    /**
     * Check if a specific NPC is a valid slayer target.
     *
     * @param npc the NPC to check
     * @return true if NPC matches current task
     */
    public boolean isTarget(NPC npc) {
        return targetNpcs.contains(npc);
    }

    // ========================================================================
    // Summary
    // ========================================================================

    /**
     * Get a summary string for logging.
     *
     * @return summary of slayer state
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder("SlayerState[");

        if (hasTask()) {
            sb.append("task=").append(taskName);
            if (taskLocation != null) {
                sb.append(" (").append(taskLocation).append(")");
            }
            sb.append(", kills=").append(remainingKills).append("/").append(initialKills);
            sb.append(" (").append(getCompletionPercentage()).append("%)");
        } else if (isTaskComplete()) {
            sb.append("task=").append(taskName).append(" COMPLETE");
        } else {
            sb.append("no task");
        }

        sb.append(", points=").append(slayerPoints);
        sb.append(", streak=").append(taskStreak);

        if (currentMaster != null) {
            sb.append(", master=").append(currentMaster.getDisplayName());
        }

        if (!targetNpcs.isEmpty()) {
            sb.append(", targets=").append(targetNpcs.size());
        }

        sb.append("]");
        return sb.toString();
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

