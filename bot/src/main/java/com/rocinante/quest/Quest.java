package com.rocinante.quest;

import com.rocinante.quest.steps.QuestStep;
import com.rocinante.state.StateCondition;

import java.util.List;
import java.util.Map;

/**
 * Interface defining a quest or quest-like progression.
 *
 * This mirrors Quest Helper's QuestHelper pattern but is adapted for our bot framework.
 * A Quest defines a series of steps that map to varbit values, along with requirements
 * and metadata about the quest.
 *
 * <p>Quests are defined declaratively and executed by {@link QuestExecutor} which
 * monitors the progress varbit and translates steps into executable Tasks.
 *
 * <p>This interface supports:
 * <ul>
 *   <li>Standard RS quests via Quest Helper data extraction</li>
 *   <li>Custom quests like Tutorial Island</li>
 *   <li>Achievement diaries and other progression systems</li>
 * </ul>
 */
public interface Quest {

    /**
     * Get the unique identifier for this quest.
     * For standard quests, this matches the Quest enum name.
     *
     * @return the quest ID
     */
    String getId();

    /**
     * Get the display name of this quest.
     *
     * @return human-readable quest name
     */
    String getName();

    /**
     * Get the varbit/varp ID that tracks quest progress.
     *
     * @return the progress varbit/varp ID
     */
    int getProgressVarbit();

    /**
     * Check if this quest uses a VarPlayer (varp) instead of a Varbit.
     * Most quests use varbits, but some (like Tutorial Island) use varps.
     *
     * @return true if uses VarPlayer, false if uses Varbit (default)
     */
    default boolean usesVarp() {
        return false;
    }

    /**
     * Get the varbit/varp value indicating quest completion.
     *
     * @return the completion value
     */
    int getCompletionValue();

    /**
     * Load all quest steps mapped by their trigger varbit value.
     *
     * <p>The map keys are varbit values that trigger each step. When the progress
     * varbit equals a key, that step becomes active.
     *
     * @return map of varbit values to quest steps
     */
    Map<Integer, QuestStep> loadSteps();

    /**
     * Get the starting requirements for this quest.
     * These are checked before the quest begins.
     *
     * @return list of requirement conditions
     */
    default List<StateCondition> getRequirements() {
        return List.of();
    }

    /**
     * Get items required to complete this quest.
     * This is informational and may not be enforced.
     *
     * @return list of item IDs required
     */
    default List<Integer> getRequiredItems() {
        return List.of();
    }

    /**
     * Get recommended items for this quest.
     *
     * @return list of recommended item IDs
     */
    default List<Integer> getRecommendedItems() {
        return List.of();
    }

    /**
     * Check if this quest is complete.
     *
     * @param currentVarbitValue the current progress varbit value
     * @return true if the quest is complete
     */
    default boolean isComplete(int currentVarbitValue) {
        return currentVarbitValue >= getCompletionValue();
    }

    /**
     * Check if quest is complete with access to the live client.
     *
     * By default this delegates to {@link #isComplete(int)}. Implementations that
     * need richer checks (e.g., Quest Helper state, RuneLite Quest state) should
     * override this to avoid varbit-only shortcuts.
     *
     * @param currentVarbitValue current progress var value (or -1 if unknown)
     * @param client the RuneLite client (may be null in tests)
     * @return true if the quest is complete
     */
    default boolean isComplete(int currentVarbitValue, net.runelite.api.Client client) {
        return isComplete(currentVarbitValue);
    }

    /**
     * Check if this quest has been started.
     *
     * @param currentVarbitValue the current progress varbit value
     * @return true if the quest has been started (varbit > 0)
     */
    default boolean isStarted(int currentVarbitValue) {
        return currentVarbitValue > 0;
    }

    /**
     * Get the quest difficulty rating.
     *
     * @return the difficulty (e.g., "Novice", "Intermediate", "Master")
     */
    default String getDifficulty() {
        return "Unknown";
    }

    /**
     * Get the estimated quest duration in minutes.
     *
     * @return estimated minutes to complete
     */
    default int getEstimatedMinutes() {
        return 30;
    }

    /**
     * Check if this quest has combat requirements.
     *
     * @return true if combat is involved
     */
    default boolean hasCombat() {
        return false;
    }

    /**
     * Get a brief description of this quest.
     *
     * @return quest description
     */
    default String getDescription() {
        return "";
    }
}

