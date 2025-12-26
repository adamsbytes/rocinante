package com.rocinante.quest.steps;

import com.rocinante.state.StateCondition;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for all quest steps.
 *
 * A QuestStep represents a single action or set of actions within a quest progression.
 * It mirrors Quest Helper's QuestStep/DetailedQuestStep patterns but integrates with
 * our Task system.
 *
 * <p>QuestSteps are declarative - they describe WHAT needs to happen, not HOW.
 * The {@link com.rocinante.quest.QuestExecutor} translates steps into executable Tasks.
 *
 * <p>Subclasses provide specific step types:
 * <ul>
 *   <li>{@link NpcQuestStep} - Talk to an NPC</li>
 *   <li>{@link ObjectQuestStep} - Interact with a game object</li>
 *   <li>{@link WidgetQuestStep} - Interact with a UI widget</li>
 *   <li>{@link WalkQuestStep} - Walk to a location</li>
 *   <li>{@link CombatQuestStep} - Engage in combat</li>
 *   <li>{@link ItemQuestStep} - Use or equip items</li>
 *   <li>{@link ConditionalQuestStep} - Branch based on conditions</li>
 * </ul>
 */
@Getter
@Setter
@Accessors(chain = true)
public abstract class QuestStep {

    /**
     * Step types for categorization.
     */
    public enum StepType {
        NPC,
        OBJECT,
        WIDGET,
        WALK,
        COMBAT,
        ITEM,
        CONDITIONAL,
        COMPOSITE,
        CUSTOM
    }

    /**
     * Human-readable instruction text for this step.
     */
    private String text;

    /**
     * Additional lines of helper text.
     */
    private List<String> additionalText = new ArrayList<>();

    /**
     * Conditions that must be true for this step to execute.
     * If not met, the step is skipped and re-checked next tick.
     */
    private List<StateCondition> conditions = new ArrayList<>();

    /**
     * Condition that indicates this step is complete.
     * If null, the step relies on varbit advancement.
     */
    private StateCondition completionCondition;

    /**
     * Whether this step should be highlighted to the user.
     */
    private boolean highlighted = true;

    /**
     * Whether this step requires the player to be idle.
     */
    private boolean requiresIdle = false;

    /**
     * Custom timeout in milliseconds for this step (0 = default).
     */
    private long timeoutMs = 0;

    /**
     * Create a quest step with instruction text.
     *
     * @param text the instruction text
     */
    protected QuestStep(String text) {
        this.text = text;
    }

    /**
     * Create a quest step with no initial text.
     */
    protected QuestStep() {
        this.text = "";
    }

    /**
     * Get the type of this step.
     *
     * @return the step type
     */
    public abstract StepType getType();

    /**
     * Convert this step into one or more executable Tasks.
     *
     * @param ctx the task context for state access
     * @return list of tasks to execute for this step
     */
    public abstract List<Task> toTasks(TaskContext ctx);

    /**
     * Check if this step's conditions are currently met.
     *
     * @param ctx the task context
     * @return true if all conditions pass
     */
    public boolean conditionsMet(TaskContext ctx) {
        for (StateCondition condition : conditions) {
            if (!condition.test(ctx)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if this step is complete based on completion condition.
     *
     * @param ctx the task context
     * @return true if complete, false if incomplete or no completion condition
     */
    public boolean isComplete(TaskContext ctx) {
        if (completionCondition != null) {
            return completionCondition.test(ctx);
        }
        return false;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Add a condition that must be met before this step executes.
     *
     * @param condition the condition
     * @return this step for chaining
     */
    public QuestStep withCondition(StateCondition condition) {
        this.conditions.add(condition);
        return this;
    }

    /**
     * Add multiple conditions.
     *
     * @param conditions the conditions
     * @return this step for chaining
     */
    public QuestStep withConditions(StateCondition... conditions) {
        for (StateCondition c : conditions) {
            this.conditions.add(c);
        }
        return this;
    }

    /**
     * Set the completion condition for this step.
     *
     * @param condition the completion condition
     * @return this step for chaining
     */
    public QuestStep completesWhen(StateCondition condition) {
        this.completionCondition = condition;
        return this;
    }

    /**
     * Add additional helper text.
     *
     * @param text the additional text
     * @return this step for chaining
     */
    public QuestStep withText(String text) {
        this.additionalText.add(text);
        return this;
    }

    /**
     * Set whether this step should highlight.
     *
     * @param highlighted true to highlight
     * @return this step for chaining
     */
    public QuestStep highlighted(boolean highlighted) {
        this.highlighted = highlighted;
        return this;
    }

    /**
     * Mark this step as requiring the player to be idle first.
     *
     * @return this step for chaining
     */
    public QuestStep requiresIdle() {
        this.requiresIdle = true;
        return this;
    }

    /**
     * Set a custom timeout for this step.
     *
     * @param timeoutMs timeout in milliseconds
     * @return this step for chaining
     */
    public QuestStep withTimeout(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        return this;
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Get a description for logging/debugging.
     *
     * @return step description
     */
    public String getDescription() {
        return String.format("%s: %s", getType(), text);
    }

    @Override
    public String toString() {
        return getDescription();
    }
}

