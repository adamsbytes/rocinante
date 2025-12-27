package com.rocinante.tasks;

/**
 * Represents the execution state of a task.
 * Tasks transition through states as they execute:
 * PENDING -> RUNNING -> PAUSED (for behavioral interrupts) -> RUNNING -> COMPLETED or FAILED
 *
 * Per REQUIREMENTS.md Section 5.1
 */
public enum TaskState {

    /**
     * Task is queued but not yet started.
     * Initial state for all tasks.
     */
    PENDING,

    /**
     * Task is currently executing.
     * Only one task can be in RUNNING state at a time (per executor).
     */
    RUNNING,

    /**
     * Task is paused for behavioral interruption (break, ritual, etc.).
     * Will be resumed after the behavioral task completes.
     */
    PAUSED,

    /**
     * Task has completed successfully.
     * Terminal state - task will not be executed again.
     */
    COMPLETED,

    /**
     * Task has failed due to error, timeout, or explicit abort.
     * Terminal state - task may be retried based on retry policy.
     */
    FAILED;

    /**
     * Check if this state is a terminal state (COMPLETED or FAILED).
     *
     * @return true if the task has finished execution
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /**
     * Check if this state allows execution to continue.
     *
     * @return true if the task can continue executing
     */
    public boolean canContinue() {
        return this == PENDING || this == RUNNING || this == PAUSED;
    }
}

