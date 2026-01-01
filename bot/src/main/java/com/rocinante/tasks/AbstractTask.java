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

    /**
     * Number of ticks without progress before considering task stuck.
     * 100 ticks ≈ 60 seconds at 0.6s/tick.
     */
    public static final int INACTIVITY_TIMEOUT_TICKS = 100;

    /**
     * Maximum absolute timeout as a safety net (30 minutes).
     * This catches truly stuck tasks that somehow keep reporting false progress.
     */
    public static final Duration MAX_ABSOLUTE_TIMEOUT = Duration.ofMinutes(30);

    @Getter
    protected volatile TaskState state = TaskState.PENDING;

    @Getter
    @Setter
    protected TaskPriority priority = TaskPriority.NORMAL;

    @Getter
    @Setter
    protected int inactivityTimeoutTicks = INACTIVITY_TIMEOUT_TICKS;

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
     * Tick number when progress was last recorded.
     * Progress resets the inactivity timeout.
     */
    @Getter
    protected int lastProgressTick = 0;

    // Snapshot of game state for automatic progress detection
    private int lastInventoryHash = 0;
    private int lastPlayerX = -1;
    private int lastPlayerY = -1;
    private int lastAnimation = -1;

    /**
     * Flag indicating if the task was aborted externally.
     */
    protected volatile boolean aborted = false;

    /**
     * Optional failure reason for debugging.
     */
    @Getter
    protected String failureReason;

    /**
     * Progress indicator (0.0 to 1.0).
     * Set to -1.0 if progress is not applicable or not tracked.
     * Tasks can update this value as they execute to show progress in the UI.
     */
    @Getter
    @Setter
    protected double progress = -1.0;

    /**
     * Timestamp when the task started executing (for public access).
     * Returns null if the task hasn't started yet.
     */
    public Instant getStartTime() {
        return startTime;
    }

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
     * Resets phase wait ticks counter and records progress.
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
            // Phase transitions count as progress
            recordProgress();
        }
    }

    /**
     * Record that the task made progress.
     * This resets the inactivity timeout counter.
     * 
     * <p>Call this whenever the task does something meaningful:
     * <ul>
     *   <li>Successfully clicks something</li>
     *   <li>Detects expected state change (inventory, position, animation)</li>
     *   <li>Transitions to a new phase</li>
     *   <li>Completes a sub-task</li>
     * </ul>
     */
    protected void recordProgress() {
        this.lastProgressTick = this.executionTicks;
    }

    /**
     * Record progress with a description for debugging.
     * 
     * @param description what progress was made
     */
    protected void recordProgress(String description) {
        this.lastProgressTick = this.executionTicks;
        log.trace("Task '{}' progress: {} (tick {})", getDescription(), description, executionTicks);
    }

    /**
     * Automatically detect meaningful game state changes and record progress.
     * Called each tick before executeImpl().
     * 
     * <p>Detects:
     * <ul>
     *   <li>Inventory changes (items gained/lost)</li>
     *   <li>Position changes (player moved)</li>
     *   <li>Animation changes (started new action)</li>
     * </ul>
     */
    private void detectAutomaticProgress(TaskContext ctx) {
        boolean progressDetected = false;
        
        // Check inventory changes
        var inv = ctx.getInventoryState();
        if (inv != null) {
            int currentHash = inv.hashCode();
            if (lastInventoryHash != 0 && currentHash != lastInventoryHash) {
                progressDetected = true;
                log.trace("Auto-progress: inventory changed");
            }
            lastInventoryHash = currentHash;
        }
        
        // Check position changes
        var player = ctx.getPlayerState();
        if (player != null && player.getWorldPosition() != null) {
            int x = player.getWorldPosition().getX();
            int y = player.getWorldPosition().getY();
            if (lastPlayerX >= 0 && (x != lastPlayerX || y != lastPlayerY)) {
                progressDetected = true;
                log.trace("Auto-progress: position changed");
            }
            lastPlayerX = x;
            lastPlayerY = y;
        }
        
        // Check animation changes (new animation started)
        if (player != null) {
            int anim = player.getAnimationId();
            if (lastAnimation != -1 && anim != lastAnimation && anim != -1) {
                // Only count starting a new animation, not stopping
                progressDetected = true;
                log.trace("Auto-progress: animation started ({})", anim);
            }
            lastAnimation = anim;
        }
        
        if (progressDetected) {
            recordProgress();
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
        log.debug("Task completing: {} [phase={}, ticks={}, elapsed={}ms]",
                getDescription(), currentPhaseName, executionTicks,
                startTime != null ? getExecutionDuration().toMillis() : 0);
        transitionTo(TaskState.COMPLETED);
    }

    /**
     * Mark the task as failed with a reason.
     *
     * @param reason the failure reason
     */
    protected void fail(String reason) {
        this.failureReason = reason;
        log.debug("Task failing: {} - reason='{}' [phase={}, ticks={}, elapsed={}ms]",
                getDescription(), reason, currentPhaseName, executionTicks,
                startTime != null ? getExecutionDuration().toMillis() : 0);
        transitionTo(TaskState.FAILED);
    }

    /**
     * Abort the task externally.
     */
    public void abort() {
        this.aborted = true;
        fail("Task aborted externally");
    }

    /**
     * Reset this task for retry.
     * Clears base task state and calls resetImpl() for subclass-specific state.
     */
    public final void resetForRetry() {
        this.state = TaskState.PENDING;
        this.startTime = null;
        this.executionTicks = 0;
        this.lastProgressTick = 0;
        this.lastInventoryHash = 0;
        this.lastPlayerX = -1;
        this.lastPlayerY = -1;
        this.lastAnimation = -1;
        this.aborted = false;
        this.failureReason = null;
        resetImpl();
    }

    /**
     * Override in subclasses to reset task-specific state on retry.
     * Called by resetForRetry() after base state is cleared.
     */
    protected void resetImpl() {
        // Default: no additional state to reset
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
            // Log task start with configuration details
            log.debug("Starting task: {} [inactivityTimeout={}ticks, priority={}, maxRetries={}]",
                    getDescription(), inactivityTimeoutTicks, priority, maxRetries);
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
            fail("Task timed out: no progress for " + getTicksSinceProgress() + " ticks");
            return;
        }

        // Execute the actual task logic
        try {
            executionTicks++;
            
            // Detect meaningful game state changes as automatic progress
            detectAutomaticProgress(ctx);
            
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
     * <p>Uses progress-based timeout: times out if no progress for
     * {@link #inactivityTimeoutTicks} ticks (default 100 ≈ 60 seconds).
     * 
     * <p>Also has a safety-net absolute timeout of 30 minutes to catch
     * tasks that somehow keep reporting false progress.
     *
     * @return true if timed out due to inactivity or absolute limit
     */
    public boolean isTimedOut() {
        if (startTime == null) {
            return false;
        }
        
        // Primary: inactivity-based timeout
        int ticksSinceProgress = executionTicks - lastProgressTick;
        if (ticksSinceProgress > inactivityTimeoutTicks) {
            log.debug("Task '{}' timed out: no progress for {} ticks (limit: {})",
                    getDescription(), ticksSinceProgress, inactivityTimeoutTicks);
            return true;
        }
        
        // Safety net: absolute timeout (catches infinite false-progress loops)
        if (getExecutionDuration().compareTo(MAX_ABSOLUTE_TIMEOUT) > 0) {
            log.warn("Task '{}' hit absolute timeout of {} minutes",
                    getDescription(), MAX_ABSOLUTE_TIMEOUT.toMinutes());
            return true;
        }
        
        return false;
    }
    
    /**
     * Get ticks since last progress was recorded.
     * 
     * @return ticks since last progress
     */
    public int getTicksSinceProgress() {
        return executionTicks - lastProgressTick;
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
     * Get the remaining ticks before inactivity timeout.
     *
     * @return remaining ticks, or 0 if already timed out
     */
    public int getRemainingInactivityTicks() {
        int ticksSinceProgress = executionTicks - lastProgressTick;
        int remaining = inactivityTimeoutTicks - ticksSinceProgress;
        return Math.max(0, remaining);
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
     * Set the inactivity timeout in ticks (builder-style).
     * Default is 100 ticks (≈60 seconds).
     *
     * @param ticks the inactivity timeout in ticks
     * @return this task for chaining
     */
    public AbstractTask withInactivityTimeout(int ticks) {
        this.inactivityTimeoutTicks = ticks;
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
    // Progress Tracking
    // ========================================================================

    /**
     * Report progress as a fraction (0.0 to 1.0).
     * 
     * @param current the current count
     * @param total the total count
     */
    protected void reportProgress(int current, int total) {
        if (total <= 0) {
            this.progress = -1.0;
        } else {
            this.progress = Math.min(1.0, Math.max(0.0, (double) current / total));
        }
    }

    /**
     * Report progress as a fraction (0.0 to 1.0).
     * 
     * @param current the current count
     * @param total the total count
     */
    protected void reportProgress(long current, long total) {
        if (total <= 0) {
            this.progress = -1.0;
        } else {
            this.progress = Math.min(1.0, Math.max(0.0, (double) current / total));
        }
    }

    /**
     * Report progress directly as a percentage value.
     * 
     * @param progressValue the progress (0.0 to 1.0)
     */
    protected void reportProgress(double progressValue) {
        this.progress = Math.min(1.0, Math.max(-1.0, progressValue));
    }

    /**
     * Clear progress tracking (set to -1.0, meaning "not tracked").
     */
    protected void clearProgress() {
        this.progress = -1.0;
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

