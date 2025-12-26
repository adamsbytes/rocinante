package com.rocinante.tasks;

import com.rocinante.state.StateCondition;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A task composed of multiple child tasks.
 *
 * Per REQUIREMENTS.md Section 5.3:
 * <ul>
 *   <li>SEQUENTIAL - Child tasks run in order; failure aborts sequence</li>
 *   <li>PARALLEL - Child tasks run concurrently; configurable failure policy</li>
 *   <li>LOOP - Repeat child sequence N times or until condition</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * CompositeTask bankingSequence = CompositeTask.sequential(
 *     new WalkToTask("varrock_bank"),
 *     new InteractObjectTask(BANK_BOOTH_ID, "Bank"),
 *     new WaitForConditionTask(Conditions.bankOpen())
 * );
 *
 * CompositeTask parallelChecks = CompositeTask.parallel(
 *     new CheckInventoryTask(),
 *     new CheckEquipmentTask()
 * ).withFailurePolicy(FailurePolicy.FAIL_SILENT);
 *
 * CompositeTask fishingLoop = CompositeTask.loop(
 *     new InteractNpcTask(FISHING_SPOT_ID, "Lure"),
 *     new WaitForConditionTask(Conditions.playerIsIdle())
 * ).untilCondition(Conditions.inventoryFull());
 * }</pre>
 */
@Slf4j
public class CompositeTask extends AbstractTask {

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The child tasks to execute.
     */
    @Getter
    private final List<Task> children = new ArrayList<>();

    /**
     * The execution mode.
     */
    @Getter
    @Setter
    private ExecutionMode executionMode = ExecutionMode.SEQUENTIAL;

    /**
     * The failure policy (for parallel execution).
     */
    @Getter
    @Setter
    private FailurePolicy failurePolicy = FailurePolicy.FAIL_FAST;

    /**
     * Maximum loop iterations (for LOOP mode).
     * -1 means infinite (use stopCondition instead).
     */
    @Getter
    @Setter
    private int maxIterations = -1;

    /**
     * Stop condition for LOOP mode.
     * Loop terminates when this condition becomes true.
     */
    @Getter
    @Setter
    private StateCondition stopCondition;

    /**
     * Custom description for this composite.
     */
    @Getter
    @Setter
    private String description;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current child index (for SEQUENTIAL and LOOP modes).
     */
    private int currentIndex = 0;

    /**
     * Current loop iteration (for LOOP mode).
     */
    private int currentIteration = 0;

    /**
     * Tracks which children have completed (for PARALLEL mode).
     */
    private boolean[] childCompleted;

    /**
     * Count of failed children (for REQUIRE_ALL policy).
     */
    private int failedCount = 0;

    /**
     * Count of completed children (for PARALLEL mode).
     */
    private int completedCount = 0;

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a sequential composite task.
     *
     * @param tasks the child tasks in execution order
     * @return the composite task
     */
    public static CompositeTask sequential(Task... tasks) {
        CompositeTask composite = new CompositeTask();
        composite.setExecutionMode(ExecutionMode.SEQUENTIAL);
        Collections.addAll(composite.children, tasks);
        return composite;
    }

    /**
     * Create a parallel composite task with FAIL_FAST policy.
     *
     * @param tasks the child tasks to execute concurrently
     * @return the composite task
     */
    public static CompositeTask parallel(Task... tasks) {
        CompositeTask composite = new CompositeTask();
        composite.setExecutionMode(ExecutionMode.PARALLEL);
        Collections.addAll(composite.children, tasks);
        return composite;
    }

    /**
     * Create a looping composite task.
     *
     * @param tasks the child tasks to repeat
     * @return the composite task
     */
    public static CompositeTask loop(Task... tasks) {
        CompositeTask composite = new CompositeTask();
        composite.setExecutionMode(ExecutionMode.LOOP);
        Collections.addAll(composite.children, tasks);
        return composite;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Add a child task.
     *
     * @param task the task to add
     * @return this composite for chaining
     */
    public CompositeTask addChild(Task task) {
        if (task != null) {
            children.add(task);
        }
        return this;
    }

    /**
     * Add multiple child tasks.
     *
     * @param tasks the tasks to add
     * @return this composite for chaining
     */
    public CompositeTask addChildren(Task... tasks) {
        Collections.addAll(children, tasks);
        return this;
    }

    /**
     * Set the failure policy (builder-style).
     *
     * @param policy the failure policy
     * @return this composite for chaining
     */
    public CompositeTask withFailurePolicy(FailurePolicy policy) {
        this.failurePolicy = policy;
        return this;
    }

    /**
     * Set maximum loop iterations (builder-style).
     *
     * @param iterations max iterations (-1 for infinite)
     * @return this composite for chaining
     */
    public CompositeTask withMaxIterations(int iterations) {
        this.maxIterations = iterations;
        return this;
    }

    /**
     * Set the loop stop condition (builder-style).
     *
     * @param condition condition that terminates the loop
     * @return this composite for chaining
     */
    public CompositeTask untilCondition(StateCondition condition) {
        this.stopCondition = condition;
        return this;
    }

    /**
     * Set a custom description (builder-style).
     *
     * @param description the description
     * @return this composite for chaining
     */
    public CompositeTask withDescription(String description) {
        this.description = description;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (children.isEmpty()) {
            log.warn("CompositeTask has no children");
            return false;
        }
        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        switch (executionMode) {
            case SEQUENTIAL:
                executeSequential(ctx);
                break;
            case PARALLEL:
                executeParallel(ctx);
                break;
            case LOOP:
                executeLoop(ctx);
                break;
        }
    }

    // ========================================================================
    // Sequential Execution
    // ========================================================================

    private void executeSequential(TaskContext ctx) {
        if (currentIndex >= children.size()) {
            complete();
            return;
        }

        Task current = children.get(currentIndex);

        // Execute current child
        if (current.getState() == TaskState.PENDING) {
            if (!current.canExecute(ctx)) {
                log.debug("Sequential child {} preconditions not met", currentIndex);
                return; // Wait for preconditions
            }
        }

        current.execute(ctx);

        // Check child state
        TaskState childState = current.getState();
        if (childState == TaskState.COMPLETED) {
            current.onComplete(ctx);
            currentIndex++;
            log.debug("Sequential child {} completed, advancing to {}",
                    currentIndex - 1, currentIndex);

            // Check if all done
            if (currentIndex >= children.size()) {
                complete();
            }
        } else if (childState == TaskState.FAILED) {
            current.onFail(ctx, null);
            log.warn("Sequential child {} failed: {}", currentIndex, current.getDescription());
            fail("Child task failed: " + current.getDescription());
        }
    }

    // ========================================================================
    // Parallel Execution
    // ========================================================================

    private void executeParallel(TaskContext ctx) {
        // Initialize tracking on first execution
        if (childCompleted == null) {
            childCompleted = new boolean[children.size()];
            failedCount = 0;
            completedCount = 0;
        }

        // Execute all non-completed children
        for (int i = 0; i < children.size(); i++) {
            if (childCompleted[i]) {
                continue;
            }

            Task child = children.get(i);

            // Start child if pending
            if (child.getState() == TaskState.PENDING) {
                if (!child.canExecute(ctx)) {
                    continue; // Skip this tick
                }
            }

            child.execute(ctx);

            // Check child state
            TaskState childState = child.getState();
            if (childState == TaskState.COMPLETED) {
                child.onComplete(ctx);
                childCompleted[i] = true;
                completedCount++;
                log.debug("Parallel child {} completed", i);
            } else if (childState == TaskState.FAILED) {
                child.onFail(ctx, null);
                childCompleted[i] = true;
                failedCount++;
                log.debug("Parallel child {} failed", i);

                // Handle based on failure policy
                if (failurePolicy == FailurePolicy.FAIL_FAST) {
                    fail("Child task failed: " + child.getDescription());
                    return;
                }
            }
        }

        // Check if all children are done
        if (completedCount + failedCount >= children.size()) {
            if (failurePolicy == FailurePolicy.REQUIRE_ALL && failedCount > 0) {
                fail(failedCount + " child tasks failed");
            } else if (completedCount == 0) {
                fail("All child tasks failed");
            } else {
                complete();
            }
        }
    }

    // ========================================================================
    // Loop Execution
    // ========================================================================

    private void executeLoop(TaskContext ctx) {
        // Check stop condition
        if (stopCondition != null && stopCondition.test(ctx)) {
            log.debug("Loop stop condition met after {} iterations", currentIteration);
            complete();
            return;
        }

        // Check max iterations
        if (maxIterations > 0 && currentIteration >= maxIterations) {
            log.debug("Loop reached max iterations: {}", maxIterations);
            complete();
            return;
        }

        // Execute current child in sequence
        if (currentIndex >= children.size()) {
            // Completed one iteration, reset for next
            currentIteration++;
            currentIndex = 0;
            resetChildren();
            log.debug("Loop iteration {} completed, starting iteration {}",
                    currentIteration - 1, currentIteration);

            // Re-check stop condition and max iterations
            if (stopCondition != null && stopCondition.test(ctx)) {
                complete();
                return;
            }
            if (maxIterations > 0 && currentIteration >= maxIterations) {
                complete();
                return;
            }
        }

        // Execute like sequential
        Task current = children.get(currentIndex);

        if (current.getState() == TaskState.PENDING) {
            if (!current.canExecute(ctx)) {
                return;
            }
        }

        current.execute(ctx);

        TaskState childState = current.getState();
        if (childState == TaskState.COMPLETED) {
            current.onComplete(ctx);
            currentIndex++;
        } else if (childState == TaskState.FAILED) {
            current.onFail(ctx, null);
            log.warn("Loop child {} failed on iteration {}", currentIndex, currentIteration);
            fail("Loop child task failed: " + current.getDescription());
        }
    }

    /**
     * Reset all children to PENDING state for loop iteration.
     */
    private void resetChildren() {
        for (Task child : children) {
            if (child instanceof AbstractTask) {
                AbstractTask at = (AbstractTask) child;
                at.state = TaskState.PENDING;
                at.startTime = null;
                at.executionTicks = 0;
                at.aborted = false;
                at.failureReason = null;
            }
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
        return String.format("CompositeTask[%s, %d children]",
                executionMode, children.size());
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Get the current progress as a fraction.
     *
     * @return progress (0.0 to 1.0)
     */
    public double getProgress() {
        if (children.isEmpty()) {
            return 1.0;
        }

        switch (executionMode) {
            case SEQUENTIAL:
                return (double) currentIndex / children.size();
            case PARALLEL:
                return (double) (completedCount + failedCount) / children.size();
            case LOOP:
                if (maxIterations > 0) {
                    double iterProgress = (double) currentIndex / children.size();
                    return (currentIteration + iterProgress) / maxIterations;
                }
                return 0.0; // Unknown for infinite loops
            default:
                return 0.0;
        }
    }

    /**
     * Get the current child being executed.
     *
     * @return the current child task, or null if none
     */
    public Task getCurrentChild() {
        if (executionMode == ExecutionMode.PARALLEL) {
            return null; // Multiple children active
        }
        if (currentIndex < children.size()) {
            return children.get(currentIndex);
        }
        return null;
    }
}

