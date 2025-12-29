package com.rocinante.quest;

import com.rocinante.quest.steps.QuestStep;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Tracks quest progress based on varbit values.
 *
 * QuestProgress monitors the quest's progress varbit and determines which step
 * should currently be active. It handles the mapping from varbit values to steps
 * and detects when progress is made.
 *
 * <p>This class handles the complexity of varbit-to-step mapping where:
 * <ul>
 *   <li>Varbit values may have gaps (e.g., 2, 7, 10, 20)</li>
 *   <li>Some varbit values may not have explicit steps</li>
 *   <li>Progress may skip values (quest shortcuts, etc.)</li>
 * </ul>
 */
@Slf4j
public class QuestProgress {

    @Getter
    private final Quest quest;

    /**
     * Steps keyed by their minimum varbit value, sorted for lookup.
     */
    private final NavigableMap<Integer, QuestStep> stepsByVarbit;

    /**
     * Last known varbit value.
     */
    @Getter
    private int lastVarbitValue = -1;

    /**
     * Current active step.
     */
    @Getter
    private QuestStep currentStep;

    /**
     * Whether the quest has been completed.
     */
    @Getter
    private boolean completed = false;

    /**
     * Create a progress tracker for a quest.
     *
     * @param quest the quest to track
     */
    public QuestProgress(Quest quest) {
        this.quest = quest;

        // Load steps into sorted map for efficient lookup
        Map<Integer, QuestStep> steps = quest.loadSteps();
        this.stepsByVarbit = new TreeMap<>(steps);

        log.debug("QuestProgress initialized for {} with {} steps",
                quest.getName(), stepsByVarbit.size());
    }

    /**
     * Update progress based on current varbit/varp value.
     *
     * @param client the game client for reading progress
     * @return true if progress changed (step or completion)
     */
    public boolean update(Client client) {
        int currentValue;
        if (quest.usesVarp()) {
            // Use VarPlayer (varp) - e.g., Tutorial Island uses varp 281
            currentValue = client.getVarpValue(quest.getProgressVarbit());
        } else {
            // Use Varbit - most quests use this
            currentValue = client.getVarbitValue(quest.getProgressVarbit());
        }
        return updateWithValue(currentValue);
    }

    /**
     * Force a refresh from actual game varbits, ignoring cached state.
     * 
     * This is called after login/reconnect to ensure the progress tracker
     * is synchronized with the actual game state.
     *
     * @param client the game client for reading progress
     */
    public void forceRefresh(Client client) {
        // Reset cached state to force re-evaluation
        int previousValue = lastVarbitValue;
        lastVarbitValue = -1;  // Force update to detect change
        currentStep = null;
        completed = false;
        
        // Now do a normal update
        boolean changed = update(client);
        
        log.info("Force refresh: varbit {} -> {}, changed={}",
                previousValue, lastVarbitValue, changed);
    }

    /**
     * Update progress with a specific varbit value.
     * Useful for testing or manual progression.
     *
     * @param currentValue the current varbit value
     * @return true if progress changed
     */
    public boolean updateWithValue(int currentValue) {
        if (currentValue == lastVarbitValue && currentStep != null) {
            return false; // No change
        }

        int previousValue = lastVarbitValue;
        lastVarbitValue = currentValue;

        // Check for completion
        if (quest.isComplete(currentValue)) {
            if (!completed) {
                completed = true;
                currentStep = null;
                log.info("Quest {} completed! (varbit {} -> {})",
                        quest.getName(), previousValue, currentValue);
                return true;
            }
            return false;
        }

        // Find the appropriate step for this varbit value
        QuestStep newStep = findStepForVarbit(currentValue);

        if (newStep != currentStep) {
            QuestStep oldStep = currentStep;
            currentStep = newStep;

            if (newStep != null) {
                log.info("Quest {} progress: varbit {} -> {}, step: {}",
                        quest.getName(), previousValue, currentValue, newStep.getText());
            } else {
                log.warn("Quest {} has no step defined for varbit value {}",
                        quest.getName(), currentValue);
            }

            return true;
        }

        return previousValue != currentValue;
    }

    /**
     * Find the appropriate step for a varbit value.
     *
     * Uses floor entry to find the step with the highest varbit key
     * that is less than or equal to the current value.
     *
     * @param varbitValue the current varbit value
     * @return the matching step, or null if none
     */
    private QuestStep findStepForVarbit(int varbitValue) {
        // First try exact match
        QuestStep exact = stepsByVarbit.get(varbitValue);
        if (exact != null) {
            return exact;
        }

        // Find the floor entry (highest key <= varbitValue)
        Map.Entry<Integer, QuestStep> floor = stepsByVarbit.floorEntry(varbitValue);
        if (floor != null) {
            return floor.getValue();
        }

        // No step found - might be before the first step
        return null;
    }

    /**
     * Get the step for a specific varbit value (exact match only).
     *
     * @param varbitValue the varbit value
     * @return the step, or null if no exact match
     */
    public QuestStep getStepForVarbitExact(int varbitValue) {
        return stepsByVarbit.get(varbitValue);
    }

    /**
     * Get all steps.
     *
     * @return navigable map of steps by varbit value
     */
    public NavigableMap<Integer, QuestStep> getAllSteps() {
        return new TreeMap<>(stepsByVarbit);
    }

    /**
     * Get the next step after the current one.
     *
     * @return the next step, or null if at end
     */
    public QuestStep getNextStep() {
        if (lastVarbitValue < 0) {
            return stepsByVarbit.isEmpty() ? null : stepsByVarbit.firstEntry().getValue();
        }

        Map.Entry<Integer, QuestStep> higher = stepsByVarbit.higherEntry(lastVarbitValue);
        return higher != null ? higher.getValue() : null;
    }

    /**
     * Get the previous step before the current one.
     *
     * @return the previous step, or null if at start
     */
    public QuestStep getPreviousStep() {
        if (lastVarbitValue < 0) {
            return null;
        }

        Map.Entry<Integer, QuestStep> lower = stepsByVarbit.lowerEntry(lastVarbitValue);
        return lower != null ? lower.getValue() : null;
    }

    /**
     * Calculate progress percentage.
     *
     * @return progress as 0.0 to 1.0
     */
    public double getProgressPercent() {
        if (completed) {
            return 1.0;
        }
        if (lastVarbitValue < 0 || stepsByVarbit.isEmpty()) {
            return 0.0;
        }

        int completionValue = quest.getCompletionValue();
        if (completionValue <= 0) {
            return 0.0;
        }

        return Math.min(1.0, (double) lastVarbitValue / completionValue);
    }

    /**
     * Get the current step index (1-based for display).
     *
     * @return current step number, or 0 if not started
     */
    public int getCurrentStepNumber() {
        if (currentStep == null) {
            return 0;
        }

        int count = 1;
        for (Map.Entry<Integer, QuestStep> entry : stepsByVarbit.entrySet()) {
            if (entry.getValue() == currentStep) {
                return count;
            }
            count++;
        }
        return 0;
    }

    /**
     * Get total number of steps.
     *
     * @return total step count
     */
    public int getTotalSteps() {
        return stepsByVarbit.size();
    }

    /**
     * Reset progress tracking (does not affect actual game progress).
     */
    public void reset() {
        lastVarbitValue = -1;
        currentStep = null;
        completed = false;
    }

    @Override
    public String toString() {
        return String.format("QuestProgress[%s, varbit=%d, step=%d/%d, %.0f%%]",
                quest.getName(),
                lastVarbitValue,
                getCurrentStepNumber(),
                getTotalSteps(),
                getProgressPercent() * 100);
    }
}

