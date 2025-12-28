package com.rocinante.status;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskState;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.GameState;
import net.runelite.api.Skill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of the complete bot status for external reporting.
 * 
 * This is the main data structure serialized to JSON and sent to the web UI.
 * Contains all relevant state: game state, current task, session stats,
 * player info, and task queue status.
 */
@Value
@Builder
public class BotStatus {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    /**
     * Empty status for when bot is not connected.
     */
    public static final BotStatus EMPTY = BotStatus.builder()
            .timestamp(System.currentTimeMillis())
            .gameState("UNKNOWN")
            .task(null)
            .session(null)
            .player(null)
            .queue(null)
            .quests(null)
            .build();

    /**
     * Unix timestamp when this snapshot was created.
     */
    long timestamp;

    /**
     * Current game state (LOGGED_IN, LOGIN_SCREEN, etc.).
     */
    String gameState;

    /**
     * Current task information.
     */
    TaskInfo task;

    /**
     * Session statistics.
     */
    SessionInfo session;

    /**
     * Player information.
     */
    PlayerInfo player;

    /**
     * Task queue status.
     */
    QueueInfo queue;

    /**
     * Quest data from Quest Helper (updated on quest state changes).
     */
    QuestsData quests;

    /**
     * Serialize to JSON string.
     * 
     * @return JSON representation
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Parse from JSON string.
     * 
     * @param json the JSON string
     * @return parsed BotStatus
     */
    public static BotStatus fromJson(String json) {
        return GSON.fromJson(json, BotStatus.class);
    }

    // ========================================================================
    // Nested Classes for JSON Structure
    // ========================================================================

    /**
     * Information about the current task.
     */
    @Value
    @Builder
    public static class TaskInfo {
        /**
         * Human-readable task description.
         */
        String description;

        /**
         * Current task state (PENDING, RUNNING, COMPLETED, FAILED, etc.).
         */
        String state;

        /**
         * Progress percentage (0.0 to 1.0), or -1 if not applicable.
         */
        double progress;

        /**
         * Subtask descriptions for composite tasks.
         */
        List<String> subtasks;

        /**
         * Time elapsed on current task in milliseconds.
         */
        long elapsedMs;

        /**
         * Create TaskInfo from a Task instance.
         */
        public static TaskInfo fromTask(Task task) {
            if (task == null) {
                return null;
            }

            List<String> subtasks = new ArrayList<>();
            // If this is a composite task, we could extract subtask info here
            // For now, just use the main description

            return TaskInfo.builder()
                    .description(task.getDescription())
                    .state(task.getState().name())
                    .progress(-1) // Progress tracking would need to be added to tasks
                    .subtasks(subtasks)
                    .elapsedMs(0) // Would need timing from AbstractTask
                    .build();
        }
    }

    /**
     * Session statistics for JSON serialization.
     */
    @Value
    @Builder
    public static class SessionInfo {
        /**
         * Session start timestamp.
         */
        Long startTime;

        /**
         * Total runtime in milliseconds.
         */
        long runtimeMs;

        /**
         * Break statistics.
         */
        BreakInfo breaks;

        /**
         * Total actions performed.
         */
        long actions;

        /**
         * Current fatigue level (0.0 to 1.0).
         */
        double fatigue;

        /**
         * XP gained per skill.
         */
        Map<String, Integer> xpGained;

        /**
         * Total XP gained.
         */
        long totalXp;

        /**
         * XP per hour rate.
         */
        double xpPerHour;

        /**
         * Create SessionInfo from SessionStats.
         */
        public static SessionInfo fromStats(SessionStats stats) {
            if (stats == null || !stats.hasData()) {
                return null;
            }

            Map<String, Integer> xpBySkillName = new HashMap<>();
            stats.getXpGainedBySkill().forEach((skill, xp) -> {
                if (xp > 0) {
                    xpBySkillName.put(skill.getName(), xp);
                }
            });

            return SessionInfo.builder()
                    .startTime(stats.getSessionStartTime() != null 
                            ? stats.getSessionStartTime().toEpochMilli() : null)
                    .runtimeMs(stats.getRuntimeMs())
                    .breaks(BreakInfo.builder()
                            .count(stats.getBreakCount())
                            .totalDuration(stats.getTotalBreakDurationMs())
                            .build())
                    .actions(stats.getActionCount())
                    .fatigue(stats.getFatigueLevel())
                    .xpGained(xpBySkillName)
                    .totalXp(stats.getTotalXpGained())
                    .xpPerHour(stats.getXpPerHour())
                    .build();
        }
    }

    /**
     * Break statistics.
     */
    @Value
    @Builder
    public static class BreakInfo {
        int count;
        long totalDuration;
    }

    /**
     * Player information for JSON serialization.
     */
    @Value
    @Builder
    public static class PlayerInfo {
        /**
         * Player's display name.
         */
        String name;

        /**
         * Combat level.
         */
        int combatLevel;

        /**
         * Total level across all skills.
         */
        int totalLevel;

        /**
         * Quest points.
         */
        int questPoints;

        /**
         * Current hitpoints.
         */
        int currentHp;

        /**
         * Maximum hitpoints.
         */
        int maxHp;

        /**
         * Current prayer points.
         */
        int currentPrayer;

        /**
         * Maximum prayer points.
         */
        int maxPrayer;

        /**
         * Run energy (0-100).
         */
        int runEnergy;

        /**
         * Whether player is poisoned.
         */
        boolean poisoned;

        /**
         * Whether player is venomed.
         */
        boolean venomed;

        /**
         * Skills data.
         */
        Map<String, SkillData> skills;

        /**
         * Create PlayerInfo from game state.
         * 
         * @param playerState the player state snapshot
         * @param xpTracker the XP tracker for skill data
         * @param playerName the player's name
         * @param questPoints quest points (from varbit)
         * @return player info
         */
        public static PlayerInfo fromState(
                PlayerState playerState,
                XpTracker xpTracker,
                String playerName,
                int questPoints) {
            
            if (playerState == null || !playerState.isValid()) {
                return null;
            }

            Map<String, SkillData> skills = new HashMap<>();
            if (xpTracker != null) {
                for (Skill skill : Skill.values()) {
                    if (skill == Skill.OVERALL) {
                        continue;
                    }
                    int level = xpTracker.getCurrentLevel(skill);
                    int xp = xpTracker.getCurrentXp(skill);
                    if (level >= 0 && xp >= 0) {
                        skills.put(skill.getName(), SkillData.builder()
                                .level(level)
                                .xp(xp)
                                .boosted(xpTracker.getBoostedLevel(skill))
                                .build());
                    }
                }
            }

            // Calculate combat level
            int combatLevel = calculateCombatLevel(skills);
            int totalLevel = skills.values().stream()
                    .mapToInt(SkillData::getLevel)
                    .sum();

            return PlayerInfo.builder()
                    .name(playerName)
                    .combatLevel(combatLevel)
                    .totalLevel(totalLevel)
                    .questPoints(questPoints)
                    .currentHp(playerState.getCurrentHitpoints())
                    .maxHp(playerState.getMaxHitpoints())
                    .currentPrayer(playerState.getCurrentPrayer())
                    .maxPrayer(playerState.getMaxPrayer())
                    .runEnergy(playerState.getRunEnergy())
                    .poisoned(playerState.isPoisoned())
                    .venomed(playerState.isVenomed())
                    .skills(skills)
                    .build();
        }

        private static int calculateCombatLevel(Map<String, SkillData> skills) {
            int attack = getSkillLevel(skills, "Attack");
            int strength = getSkillLevel(skills, "Strength");
            int defence = getSkillLevel(skills, "Defence");
            int hitpoints = getSkillLevel(skills, "Hitpoints");
            int prayer = getSkillLevel(skills, "Prayer");
            int ranged = getSkillLevel(skills, "Ranged");
            int magic = getSkillLevel(skills, "Magic");

            double base = 0.25 * (defence + hitpoints + Math.floor(prayer / 2.0));
            double melee = 0.325 * (attack + strength);
            double range = 0.325 * (Math.floor(ranged / 2.0) + ranged);
            double mage = 0.325 * (Math.floor(magic / 2.0) + magic);

            return (int) Math.floor(base + Math.max(melee, Math.max(range, mage)));
        }

        private static int getSkillLevel(Map<String, SkillData> skills, String name) {
            SkillData data = skills.get(name);
            return data != null ? data.getLevel() : 1;
        }
    }

    /**
     * Individual skill data.
     */
    @Value
    @Builder
    public static class SkillData {
        int level;
        int xp;
        int boosted;
    }

    /**
     * Task queue information.
     */
    @Value
    @Builder
    public static class QueueInfo {
        /**
         * Number of pending tasks.
         */
        int pending;

        /**
         * Descriptions of next few tasks.
         */
        List<String> descriptions;

        /**
         * Create QueueInfo from task list.
         */
        public static QueueInfo fromTasks(List<Task> pendingTasks) {
            if (pendingTasks == null || pendingTasks.isEmpty()) {
                return QueueInfo.builder()
                        .pending(0)
                        .descriptions(List.of())
                        .build();
            }

            List<String> descriptions = new ArrayList<>();
            int limit = Math.min(pendingTasks.size(), 5);
            for (int i = 0; i < limit; i++) {
                descriptions.add(pendingTasks.get(i).getDescription());
            }

            return QueueInfo.builder()
                    .pending(pendingTasks.size())
                    .descriptions(descriptions)
                    .build();
        }
    }

    /**
     * Quest data for the UI.
     */
    @Value
    @Builder
    public static class QuestsData {
        /**
         * Unix timestamp when quest data was last refreshed.
         * Quest data is expensive to compute, so it's cached and only refreshed on:
         * - Initial load
         * - Level up (skill requirements may now be met)
         * - Quest completion
         * - Manual refresh request from UI
         */
        long lastUpdated;

        /**
         * List of available quests with requirement status.
         */
        List<QuestSummary> available;

        /**
         * IDs of completed quests.
         */
        List<String> completed;

        /**
         * IDs of in-progress quests.
         */
        List<String> inProgress;

        /**
         * Total quest points earned.
         */
        int totalQuestPoints;

        /**
         * Create empty quests data.
         */
        public static QuestsData empty() {
            return QuestsData.builder()
                    .lastUpdated(0)
                    .available(List.of())
                    .completed(List.of())
                    .inProgress(List.of())
                    .totalQuestPoints(0)
                    .build();
        }
    }

    /**
     * Summary of a single quest with requirement checking.
     */
    @Value
    @Builder
    public static class QuestSummary {
        /**
         * Quest ID (e.g., "DESERT_TREASURE").
         */
        String id;

        /**
         * Quest display name.
         */
        String name;

        /**
         * Difficulty level.
         */
        String difficulty;

        /**
         * Whether members-only.
         */
        boolean members;

        /**
         * Quest points reward.
         */
        int questPoints;

        /**
         * Current quest state: NOT_STARTED, IN_PROGRESS, FINISHED.
         */
        String state;

        /**
         * Whether all requirements are met to start this quest.
         */
        boolean canStart;

        /**
         * Skill requirements with met/unmet status.
         */
        List<SkillRequirementStatus> skillRequirements;

        /**
         * Quest requirements with met/unmet status.
         */
        List<QuestRequirementStatus> questRequirements;

        /**
         * Item requirements (optional, from Quest Helper).
         */
        List<ItemRequirementStatus> itemRequirements;
    }

    /**
     * Skill requirement status.
     */
    @Value
    @Builder
    public static class SkillRequirementStatus {
        String skill;
        int required;
        int current;
        boolean met;
        boolean boostable;
    }

    /**
     * Quest requirement status.
     */
    @Value
    @Builder
    public static class QuestRequirementStatus {
        String questId;
        String questName;
        boolean met;
    }

    /**
     * Item requirement status.
     */
    @Value
    @Builder
    public static class ItemRequirementStatus {
        String itemName;
        int itemId;
        int quantity;
        boolean have;
    }

    // ========================================================================
    // Builder Helper
    // ========================================================================

    /**
     * Create a BotStatus from current game state.
     * 
     * @param gameState current game state
     * @param currentTask current executing task
     * @param sessionStats session statistics
     * @param playerState player state snapshot
     * @param xpTracker XP tracker
     * @param playerName player's display name
     * @param questPoints quest points
     * @param pendingTasks pending task queue
     * @param questsData cached quest data (can be null if not yet loaded)
     * @return complete bot status snapshot
     */
    public static BotStatus capture(
            GameState gameState,
            Task currentTask,
            SessionStats sessionStats,
            PlayerState playerState,
            XpTracker xpTracker,
            String playerName,
            int questPoints,
            List<Task> pendingTasks,
            QuestsData questsData) {

        return BotStatus.builder()
                .timestamp(System.currentTimeMillis())
                .gameState(gameState != null ? gameState.name() : "UNKNOWN")
                .task(TaskInfo.fromTask(currentTask))
                .session(SessionInfo.fromStats(sessionStats))
                .player(PlayerInfo.fromState(playerState, xpTracker, playerName, questPoints))
                .queue(QueueInfo.fromTasks(pendingTasks))
                .quests(questsData)
                .build();
    }
}

