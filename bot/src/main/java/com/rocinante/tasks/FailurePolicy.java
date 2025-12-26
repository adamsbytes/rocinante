package com.rocinante.tasks;

/**
 * Failure handling policies for CompositeTask parallel execution.
 *
 * Per REQUIREMENTS.md Section 5.3, configurable failure policy for parallel tasks:
 * <ul>
 *   <li>FAIL_FAST - First failure aborts all remaining tasks</li>
 *   <li>FAIL_SILENT - Failures are logged but execution continues</li>
 *   <li>REQUIRE_ALL - All tasks must succeed for composite to succeed</li>
 * </ul>
 */
public enum FailurePolicy {

    /**
     * Stop execution immediately when any child task fails.
     * The composite task fails, and remaining tasks are not executed.
     * Use for dependent task sequences where later tasks need earlier results.
     */
    FAIL_FAST,

    /**
     * Log failures but continue executing remaining tasks.
     * The composite task succeeds if at least one task succeeds.
     * Use for independent tasks where some failures are acceptable.
     */
    FAIL_SILENT,

    /**
     * All child tasks must complete successfully.
     * Continue executing all tasks even if some fail.
     * The composite task fails if any child failed.
     * Use when all tasks are required but order doesn't matter.
     */
    REQUIRE_ALL
}

