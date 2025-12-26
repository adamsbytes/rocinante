package com.rocinante.tasks;

/**
 * Execution modes for CompositeTask.
 *
 * Per REQUIREMENTS.md Section 5.3, CompositeTask supports:
 * <ul>
 *   <li>SEQUENTIAL - Child tasks run in order; failure aborts sequence</li>
 *   <li>PARALLEL - Child tasks run concurrently (simulated via round-robin)</li>
 *   <li>LOOP - Repeat child sequence N times or until condition</li>
 * </ul>
 */
public enum ExecutionMode {

    /**
     * Execute child tasks one after another.
     * Each task must complete before the next starts.
     * If any task fails, the entire composite fails (unless FailurePolicy overrides).
     */
    SEQUENTIAL,

    /**
     * Execute child tasks concurrently (simulated via round-robin tick allocation).
     * All tasks receive execution time each tick.
     * Completion/failure depends on FailurePolicy.
     */
    PARALLEL,

    /**
     * Repeat the child task sequence.
     * Number of iterations or stop condition is configurable.
     */
    LOOP
}

