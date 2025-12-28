package com.rocinante.tasks;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;

/**
 * Abstract base implementation of {@link Task} providing common functionality.
 *
 * <p>Features:
 * <ul>
 *   <li>State management with transition validation</li>
 *   <li>Execution timing tracking for timeout detection</li>
 *   <li>Default implementations for lifecycle hooks</li>
 *   <li>Configurable timeout and priority</li>
 * </ul>
 *
 * <p>Subclasses must implement:
 * <ul>
 *   <li>{@link #canExecute(TaskContext)} - precondition checking</li>
 *   <li>{@link #executeImpl(TaskContext)} - actual task logic</li>
 *   <li>{@link #getDescription()} - human-readable description</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractTask implements Task {

    @Getter
    protected volatile TaskState state = TaskState.PENDING;

    @Getter
    @Setter
    protected TaskPriority priority = TaskPriority.NORMAL;

    @Getter
    @Setter
    protected Duration timeout = Task.DEFAULT_TIMEOUT;

    @Getter
    @Setter
    protected int maxRetries = 3;

    /**
     * Timestamp when the task started executing (transitioned to RUNNING).
     */
    protected Instant startTime;

    /**
     * Number of ticks this task has been executing.
     */
    @Getter
    protected int executionTicks = 0;

    /**
     * Flag indicating if the task was aborted externally.
     */
    protected volatile boolean aborted = false;

    /**
     * Optional failure reason for debugging.
     */
    @Getter
    protected String failureReason;

    // ========================================================================
    // Phase Management Support
    // ========================================================================

    /**
     * Current phase name (for tasks that use phase-based execution).
     * Stored as String to support any enum type.
     */
    @Getter
    protected String currentPhaseName = "INIT";

    /**
     * Ticks spent in the current phase.
     * Useful for phase-specific timeouts and delays.
     */
    @Getter
    protected int phaseWaitTicks = 0;

    /**
     * Maximum ticks to wait in a phase before considering it stuck.
     * Set to -1 for no limit.
     */
    @Getter
    @Setter
    protected int maxPhaseWaitTicks = 100;

    /**
     * Tick when the current phase started.
     */
    protected int phaseStartTick = 0;

    /**
     * Record a phase transition for logging and tracking.
     * Resets phase wait ticks counter.
     *
     * @param newPhase the new phase (use enum.name() or string)
     */
    protected void recordPhaseTransition(String newPhase) {
        if (newPhase == null) {
            return;
        }
        String oldPhase = this.currentPhaseName;
        this.currentPhaseName = newPhase;
        this.phaseWaitTicks = 0;
        this.phaseStartTick = this.executionTicks;
        
        if (!newPhase.equals(oldPhase)) {
            log.debug("Task '{}' phase: {} -> {}", getDescription(), oldPhase, newPhase);
        }
    }

    /**
     * Record a phase transition from an enum value.
     * Convenience method that calls name() on the enum.
     *
     * @param newPhase the new phase enum value
     */
    protected void recordPhaseTransition(Enum<?> newPhase) {
        if (newPhase != null) {
            recordPhaseTransition(newPhase.name());
        }
    }

    /**
     * Increment the phase wait tick counter.
     * Call this each tick to track how long we've been in the current phase.
     */
    protected void incrementPhaseWaitTicks() {
        this.phaseWaitTicks++;
    }

    /**
     * Check if the current phase has been waiting too long.
     * Uses maxPhaseWaitTicks as the threshold.
     *
     * @return true if phase has exceeded max wait ticks
     */
    protected boolean isPhaseTimedOut() {
        if (maxPhaseWaitTicks < 0) {
            return false; // No limit
        }
        return phaseWaitTicks > maxPhaseWaitTicks;
    }

    /**
     * Check if the current phase has been waiting for at least the specified ticks.
     *
     * @param ticks minimum ticks to have waited
     * @return true if waited at least the specified ticks
     */
    protected boolean hasPhaseWaitedTicks(int ticks) {
        return phaseWaitTicks >= ticks;
    }

    /**
     * Get ticks since the current phase started.
     *
     * @return ticks in current phase
     */
    protected int getTicksInCurrentPhase() {
        return executionTicks - phaseStartTick;
    }

    /**
     * Reset phase tracking to initial state.
     * Useful when restarting or re-initializing a task.
     */
    protected void resetPhaseTracking() {
        this.currentPhaseName = "INIT";
        this.phaseWaitTicks = 0;
        this.phaseStartTick = 0;
    }

    // ========================================================================
    // State Management
    // ========================================================================

    /**
     * Transition the task to a new state with validation.
     *
     * @param newState the target state
     * @return true if transition was successful
     */
    protected boolean transitionTo(TaskState newState) {
        TaskState oldState = this.state;

        // Validate transition
        if (!isValidTransition(oldState, newState)) {
            log.warn("Invalid state transition attempted: {} -> {} for task {}",
                    oldState, newState, getDescription());
            return false;
        }

        this.state = newState;
        log.debug("Task '{}' transitioned: {} -> {}", getDescription(), oldState, newState);

        // Track start time when transitioning to RUNNING
        if (newState == TaskState.RUNNING && startTime == null) {
            startTime = Instant.now();
        }

        return true;
    }

    /**
     * Check if a state transition is valid.
     */
    private boolean isValidTransition(TaskState from, TaskState to) {
        switch (from) {
            case PENDING:
                return to == TaskState.RUNNING || to == TaskState.FAILED;
            case RUNNING:
                return to == TaskState.COMPLETED || to == TaskState.FAILED || to == TaskState.PAUSED;
            case PAUSED:
                return to == TaskState.RUNNING || to == TaskState.FAILED;
            case COMPLETED:
            case FAILED:
                // Terminal states - no further transitions
                return false;
            default:
                return false;
        }
    }

    /**
     * Mark the task as completed successfully.
     */
    protected void complete() {
        transitionTo(TaskState.COMPLETED);
    }

    /**
     * Mark the task as failed with a reason.
     *
     * @param reason the failure reason
     */
    protected void fail(String reason) {
        this.failureReason = reason;
        transitionTo(TaskState.FAILED);
    }

    /**
     * Abort the task externally.
     */
    public void abort() {
        this.aborted = true;
        fail("Task aborted externally");
    }

    // ========================================================================
    // Execution
    // ========================================================================

    /**
     * Main execution method called by TaskExecutor.
     * Handles state transitions and delegates to {@link #executeImpl(TaskContext)}.
     */
    @Override
    public final void execute(TaskContext ctx) {
        // Check for abort
        if (aborted) {
            fail("Task was aborted");
            return;
        }

        // Handle state transitions
        if (state == TaskState.PENDING) {
            if (!canExecute(ctx)) {
                log.debug("Task '{}' preconditions not met, skipping execution", getDescription());
                return;
            }
            transitionTo(TaskState.RUNNING);
        }

        // Resume from PAUSED state
        if (state == TaskState.PAUSED) {
            log.debug("Task '{}' resuming from PAUSED state", getDescription());
            transitionTo(TaskState.RUNNING);
        }

        if (state != TaskState.RUNNING) {
            return;
        }

        // Check for timeout
        if (isTimedOut()) {
            fail("Task timed out after " + getExecutionDuration().toSeconds() + " seconds");
            return;
        }

        // Execute the actual task logic
        try {
            executionTicks++;
            executeImpl(ctx);
        } catch (Exception e) {
            log.error("Task '{}' threw exception during execution", getDescription(), e);
            fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Implement the actual task logic.
     * Called once per game tick while the task is RUNNING.
     *
     * @param ctx the task context
     */
    protected abstract void executeImpl(TaskContext ctx);

    // ========================================================================
    // Lifecycle Hooks
    // ========================================================================

    @Override
    public void onComplete(TaskContext ctx) {
        log.info("Task completed: {} (took {} ticks, {}ms)",
                getDescription(),
                executionTicks,
                getExecutionDuration().toMillis());
    }

    @Override
    public void onFail(TaskContext ctx, Exception e) {
        String reason = failureReason != null ? failureReason : (e != null ? e.getMessage() : "Unknown");
        log.warn("Task failed: {} - Reason: {} (after {} ticks)",
                getDescription(), reason, executionTicks);
    }

    // ========================================================================
    // Timing
    // ========================================================================

    /**
     * Check if the task has exceeded its timeout.
     *
     * @return true if timed out
     */
    public boolean isTimedOut() {
        if (startTime == null) {
            return false;
        }
        return getExecutionDuration().compareTo(timeout) > 0;
    }

    /**
     * Get how long the task has been executing.
     *
     * @return duration since start, or ZERO if not started
     */
    public Duration getExecutionDuration() {
        if (startTime == null) {
            return Duration.ZERO;
        }
        return Duration.between(startTime, Instant.now());
    }

    /**
     * Get the remaining time before timeout.
     *
     * @return remaining duration, or ZERO if already timed out
     */
    public Duration getRemainingTime() {
        Duration elapsed = getExecutionDuration();
        Duration remaining = timeout.minus(elapsed);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    // ========================================================================
    // Task Configuration (Builder-style)
    // ========================================================================

    /**
     * Set the task priority (builder-style).
     *
     * @param priority the priority
     * @return this task for chaining
     */
    public AbstractTask withPriority(TaskPriority priority) {
        this.priority = priority;
        return this;
    }

    /**
     * Set the task timeout (builder-style).
     *
     * @param timeout the timeout duration
     * @return this task for chaining
     */
    public AbstractTask withTimeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Set the maximum retries (builder-style).
     *
     * @param maxRetries the max retry count
     * @return this task for chaining
     */
    public AbstractTask withMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    // ========================================================================
    // Utility
    // ========================================================================

    @Override
    public String toString() {
        return String.format("%s[state=%s, priority=%s, ticks=%d]",
                getClass().getSimpleName(), state, priority, executionTicks);
    }
}

