package com.rocinante.tasks;

import com.rocinante.state.StateCondition;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * A task that branches execution based on a StateCondition.
 *
 * Per REQUIREMENTS.md Section 5.3:
 * Conditional branching executes different subtasks based on StateCondition evaluation.
 *
 * <p>Example usage:
 * <pre>{@code
 * // If-then-else style
 * ConditionalTask eatIfLowHealth = ConditionalTask.ifThen(
 *     Conditions.healthBelow(0.5),
 *     new EatFoodTask()
 * ).orElse(new ContinueCombatTask());
 *
 * // If-then only (no else branch)
 * ConditionalTask checkPoison = ConditionalTask.ifThen(
 *     Conditions.isPoisoned(),
 *     new DrinkAntipoisonTask()
 * );
 *
 * // With builder pattern
 * ConditionalTask gearSwitch = new ConditionalTask()
 *     .when(Conditions.playerInArea(WILDERNESS_AREA))
 *     .thenDo(new EquipPvpGearTask())
 *     .otherwiseDo(new EquipPvmGearTask());
 * }</pre>
 */
@Slf4j
public class ConditionalTask extends AbstractTask {

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The condition to evaluate.
     */
    @Getter
    @Setter
    private StateCondition condition;

    /**
     * Task to execute when condition is true.
     */
    @Getter
    @Setter
    private Task ifTrueTask;

    /**
     * Task to execute when condition is false (optional).
     */
    @Getter
    @Setter
    private Task ifFalseTask;

    /**
     * Custom description for this conditional.
     */
    @Getter
    @Setter
    private String description;

    /**
     * Whether to re-evaluate condition each tick or only once.
     * Default: false (evaluate once at start).
     */
    @Getter
    @Setter
    private boolean dynamicEvaluation = false;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * The selected branch task (determined on first execution).
     */
    private Task selectedTask;

    /**
     * Whether the condition was true when evaluated.
     */
    @Getter
    private boolean conditionResult;

    /**
     * Whether the condition has been evaluated yet.
     */
    private boolean evaluated = false;

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a conditional task with if-then logic.
     *
     * @param condition the condition to evaluate
     * @param ifTrue    task to execute when condition is true
     * @return the conditional task
     */
    public static ConditionalTask ifThen(StateCondition condition, Task ifTrue) {
        ConditionalTask task = new ConditionalTask();
        task.condition = condition;
        task.ifTrueTask = ifTrue;
        return task;
    }

    /**
     * Create a conditional task with full if-then-else logic.
     *
     * @param condition the condition to evaluate
     * @param ifTrue    task to execute when condition is true
     * @param ifFalse   task to execute when condition is false
     * @return the conditional task
     */
    public static ConditionalTask ifThenElse(StateCondition condition, Task ifTrue, Task ifFalse) {
        ConditionalTask task = new ConditionalTask();
        task.condition = condition;
        task.ifTrueTask = ifTrue;
        task.ifFalseTask = ifFalse;
        return task;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set the condition (builder-style).
     *
     * @param condition the condition to evaluate
     * @return this task for chaining
     */
    public ConditionalTask when(StateCondition condition) {
        this.condition = condition;
        return this;
    }

    /**
     * Set the true-branch task (builder-style).
     *
     * @param task task to execute when condition is true
     * @return this task for chaining
     */
    public ConditionalTask thenDo(Task task) {
        this.ifTrueTask = task;
        return this;
    }

    /**
     * Set the false-branch task (builder-style).
     *
     * @param task task to execute when condition is false
     * @return this task for chaining
     */
    public ConditionalTask otherwiseDo(Task task) {
        this.ifFalseTask = task;
        return this;
    }

    /**
     * Set the false-branch task (alias for otherwiseDo).
     *
     * @param task task to execute when condition is false
     * @return this task for chaining
     */
    public ConditionalTask orElse(Task task) {
        return otherwiseDo(task);
    }

    /**
     * Enable dynamic evaluation (re-evaluate each tick).
     *
     * @return this task for chaining
     */
    public ConditionalTask withDynamicEvaluation() {
        this.dynamicEvaluation = true;
        return this;
    }

    /**
     * Set a custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public ConditionalTask withDescription(String description) {
        this.description = description;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (condition == null) {
            log.warn("ConditionalTask has no condition set");
            return false;
        }
        if (ifTrueTask == null && ifFalseTask == null) {
            log.warn("ConditionalTask has no branch tasks");
            return false;
        }
        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        // Evaluate condition (once or dynamically)
        if (!evaluated || dynamicEvaluation) {
            boolean previousResult = conditionResult;
            conditionResult = condition.test(ctx);
            
            // If dynamic evaluation and condition flipped while branch task is running, abort current branch
            if (dynamicEvaluation && evaluated && selectedTask != null && 
                    selectedTask.getState() == TaskState.RUNNING && 
                    previousResult != conditionResult) {
                log.warn("Conditional branch flipped mid-execution ({} -> {}), aborting current branch",
                        previousResult, conditionResult);
                if (selectedTask instanceof AbstractTask) {
                    ((AbstractTask) selectedTask).abort();
                }
                selectedTask = null;
                evaluated = false;  // Re-evaluate next tick
                return;
            }
            
            selectedTask = conditionResult ? ifTrueTask : ifFalseTask;

            if (!evaluated) {
                log.debug("Conditional evaluated: {} -> {}",
                        conditionResult,
                        selectedTask != null ? selectedTask.getDescription() : "no task");
            }
            evaluated = true;
        }

        // Handle case where selected branch is null
        if (selectedTask == null) {
            log.debug("Conditional has no task for result: {}", conditionResult);
            complete();
            return;
        }

        // Execute the selected branch
        if (selectedTask.getState() == TaskState.PENDING) {
            if (!selectedTask.canExecute(ctx)) {
                log.debug("Selected task preconditions not met");
                return;
            }
        }

        selectedTask.execute(ctx);

        // Check branch state
        TaskState branchState = selectedTask.getState();
        if (branchState == TaskState.COMPLETED) {
            selectedTask.onComplete(ctx);
            complete();
        } else if (branchState == TaskState.FAILED) {
            selectedTask.onFail(ctx, null);
            fail("Branch task failed: " + selectedTask.getDescription());
        }
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }

        StringBuilder sb = new StringBuilder("ConditionalTask[");
        if (ifTrueTask != null) {
            sb.append("if: ").append(ifTrueTask.getDescription());
        }
        if (ifFalseTask != null) {
            sb.append(", else: ").append(ifFalseTask.getDescription());
        }
        sb.append("]");
        return sb.toString();
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Get the currently selected branch task.
     *
     * @return the selected task, or null if not yet evaluated
     */
    public Task getSelectedTask() {
        return selectedTask;
    }

    /**
     * Check if the condition has been evaluated.
     *
     * @return true if evaluated
     */
    public boolean isEvaluated() {
        return evaluated;
    }

    /**
     * Reset the evaluation state (useful for re-running).
     */
    public void resetEvaluation() {
        evaluated = false;
        selectedTask = null;
        conditionResult = false;
    }
}

