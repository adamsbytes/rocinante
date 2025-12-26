package com.rocinante.tasks;

import java.time.Duration;

/**
 * Base interface for all executable tasks in the automation framework.
 *
 * Per REQUIREMENTS.md Section 5.1:
 * Tasks represent discrete units of work that can be executed, composed,
 * and sequenced. Each task manages its own state and provides hooks for
 * lifecycle events.
 *
 * <p>Execution Model:
 * <ul>
 *   <li>{@link #execute(TaskContext)} is called once per game tick while RUNNING</li>
 *   <li>Tasks should perform incremental work, not block the game thread</li>
 *   <li>State transitions are managed by the task itself</li>
 * </ul>
 *
 * <p>Timeout Behavior (per Section 5.1):
 * <ul>
 *   <li>Default timeout: 60 seconds for all task types</li>
 *   <li>On timeout: task transitions to FAILED state</li>
 *   <li>Override for special cases (long walks, combat)</li>
 * </ul>
 */
public interface Task {

    /**
     * Default task timeout in seconds.
     */
    Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Get the current execution state of this task.
     *
     * @return the current TaskState
     */
    TaskState getState();

    /**
     * Check if the task's preconditions are met and it can execute.
     * Called before transitioning from PENDING to RUNNING.
     *
     * @param ctx the task context providing game state and services
     * @return true if the task can begin execution
     */
    boolean canExecute(TaskContext ctx);

    /**
     * Perform one tick of work.
     * Called once per game tick while the task is in RUNNING state.
     *
     * <p>Implementation Guidelines:
     * <ul>
     *   <li>Do not block - return quickly to allow game to continue</li>
     *   <li>Update internal state to track progress</li>
     *   <li>Transition to COMPLETED or FAILED when done</li>
     *   <li>Use TaskContext for all game interactions</li>
     * </ul>
     *
     * @param ctx the task context providing game state and services
     */
    void execute(TaskContext ctx);

    /**
     * Called when the task completes successfully.
     * Use for cleanup, logging, or triggering follow-up actions.
     *
     * @param ctx the task context
     */
    void onComplete(TaskContext ctx);

    /**
     * Called when the task fails due to error, timeout, or abort.
     *
     * @param ctx the task context
     * @param e   the exception that caused failure (may be null for timeout/abort)
     */
    void onFail(TaskContext ctx, Exception e);

    /**
     * Get a human-readable description of this task.
     * Used for logging and debugging.
     *
     * @return description string
     */
    String getDescription();

    /**
     * Get the maximum execution time before the task is considered stuck.
     * Default is 60 seconds per REQUIREMENTS.md Section 5.1.
     *
     * @return the timeout duration
     */
    default Duration getTimeout() {
        return DEFAULT_TIMEOUT;
    }

    /**
     * Get the priority of this task.
     * Default is NORMAL.
     *
     * @return the task priority
     */
    default TaskPriority getPriority() {
        return TaskPriority.NORMAL;
    }

    /**
     * Check if this task can be interrupted by higher priority tasks.
     * Default is true for all tasks except URGENT.
     *
     * @return true if the task can be interrupted
     */
    default boolean isInterruptible() {
        return getPriority() != TaskPriority.URGENT;
    }

    /**
     * Get the maximum number of retry attempts for this task.
     * Per REQUIREMENTS.md Section 5.1: max 3 retries with exponential backoff.
     *
     * @return the maximum retry count
     */
    default int getMaxRetries() {
        return 3;
    }
}

