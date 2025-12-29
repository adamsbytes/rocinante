package com.rocinante.quest.steps;

import com.rocinante.state.StateCondition;
import com.rocinante.tasks.CompositeTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Quest step that branches based on conditions.
 *
 * This mirrors Quest Helper's ConditionalStep pattern, allowing different
 * substeps to be executed based on game state.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Branch based on inventory
 * ConditionalQuestStep conditionalStep = new ConditionalQuestStep("Make bread")
 *     .when(Conditions.hasItem(ItemID.BREAD_DOUGH), 
 *           new ObjectQuestStep(ObjectID.RANGE, "Cook", "Cook the bread"))
 *     .when(Conditions.hasItem(ItemID.POT_OF_FLOUR),
 *           new ItemQuestStep(ItemID.BUCKET_OF_WATER, ItemID.POT_OF_FLOUR, "Make dough"))
 *     .otherwise(new ObjectQuestStep(ObjectID.GRAIN, "Pick", "Get flour first"));
 *
 * // Varbit-based branching
 * ConditionalQuestStep questBranch = new ConditionalQuestStep("Continue quest")
 *     .when(VarbitCondition.equals(QUEST_VAR, 5), talkToNpcStep)
 *     .when(VarbitCondition.equals(QUEST_VAR, 10), killMonsterStep);
 * }</pre>
 */
@Slf4j
@Getter
public class ConditionalQuestStep extends QuestStep {

    /**
     * Represents a condition-step pair.
     */
    public static class Branch {
        private final StateCondition condition;
        private final QuestStep step;

        public Branch(StateCondition condition, QuestStep step) {
            this.condition = condition;
            this.step = step;
        }

        public StateCondition getCondition() {
            return condition;
        }

        public QuestStep getStep() {
            return step;
        }
    }

    /**
     * Ordered list of conditional branches.
     */
    private final List<Branch> branches = new ArrayList<>();

    /**
     * Default step if no conditions match.
     */
    private QuestStep defaultStep;

    /**
     * Create a conditional quest step.
     *
     * @param text instruction text
     */
    public ConditionalQuestStep(String text) {
        super(text);
    }

    @Override
    public StepType getType() {
        return StepType.CONDITIONAL;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        // Find the first matching branch
        for (Branch branch : branches) {
            if (branch.getCondition().test(ctx)) {
                log.debug("Conditional step matched: {}", branch.getStep().getText());
                return branch.getStep().toTasks(ctx);
            }
        }

        // Use default step if no branches match
        if (defaultStep != null) {
            log.debug("Using default conditional step: {}", defaultStep.getText());
            return defaultStep.toTasks(ctx);
        }

        // No matching branch and no default
        log.warn("Conditional step '{}' has no matching branch and no default", getText());
        return new ArrayList<>();
    }

    /**
     * Get the currently active step based on conditions.
     *
     * @param ctx the task context
     * @return the active step, or null if none
     */
    public QuestStep getActiveStep(TaskContext ctx) {
        for (Branch branch : branches) {
            if (branch.getCondition().test(ctx)) {
                return branch.getStep();
            }
        }
        return defaultStep;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Add a conditional branch.
     *
     * @param condition the condition to check
     * @param step      the step to execute if condition is true
     * @return this step for chaining
     */
    public ConditionalQuestStep when(StateCondition condition, QuestStep step) {
        branches.add(new Branch(condition, step));
        return this;
    }

    /**
     * Add multiple steps for the same condition (executed sequentially).
     *
     * @param condition the condition to check
     * @param steps     the steps to execute
     * @return this step for chaining
     */
    public ConditionalQuestStep when(StateCondition condition, QuestStep... steps) {
        if (steps.length == 1) {
            return when(condition, steps[0]);
        }

        // Wrap multiple steps in a composite step
        CompositeQuestStep composite = new CompositeQuestStep("Composite for condition");
        for (QuestStep step : steps) {
            composite.addStep(step);
        }
        branches.add(new Branch(condition, composite));
        return this;
    }

    /**
     * Set the default step to execute if no conditions match.
     *
     * @param step the default step
     * @return this step for chaining
     */
    public ConditionalQuestStep otherwise(QuestStep step) {
        this.defaultStep = step;
        return this;
    }

    // ========================================================================
    // Composite Quest Step (for grouping)
    // ========================================================================

    /**
     * A quest step that contains multiple sub-steps.
     */
    public static class CompositeQuestStep extends QuestStep {
        private final List<QuestStep> subSteps = new ArrayList<>();
        
        /**
         * If true (default), retries resume from the failed child task.
         * If false, retries restart from the beginning of the composite.
         */
        @Getter
        @Setter
        private boolean retryFromFailed = true;

        public CompositeQuestStep(String text) {
            super(text);
        }

        public void addStep(QuestStep step) {
            subSteps.add(step);
        }

        @Override
        public StepType getType() {
            return StepType.COMPOSITE;
        }

        @Override
        public List<Task> toTasks(TaskContext ctx) {
            // Collect all child tasks
            List<Task> childTasks = new ArrayList<>();
            for (QuestStep step : subSteps) {
                childTasks.addAll(step.toTasks(ctx));
            }
            
            // If only one task, return it directly
            if (childTasks.size() == 1) {
                return childTasks;
            }
            
            // Wrap in a CompositeTask with our retry setting
            CompositeTask composite = CompositeTask.sequential(childTasks.toArray(new Task[0]))
                    .withDescription(getText())
                    .withRetryFromFailed(retryFromFailed);
            
            return Collections.singletonList(composite);
        }

        public List<QuestStep> getSubSteps() {
            return subSteps;
        }
    }
}

