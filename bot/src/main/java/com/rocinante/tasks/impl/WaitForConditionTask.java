package com.rocinante.tasks.impl;

import com.rocinante.state.StateCondition;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.timing.DelayProfile;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import com.rocinante.util.Randomization;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Task that blocks until a StateCondition evaluates true.
 *
 * Per REQUIREMENTS.md Section 5.4.4:
 * <ul>
 *   <li>Block until condition is true</li>
 *   <li>Configurable timeout (default 30 seconds)</li>
 *   <li>Poll interval: 1 game tick</li>
 *   <li>Idle mouse behavior while waiting</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Wait for player to be idle
 * WaitForConditionTask waitIdle = new WaitForConditionTask(Conditions.playerIsIdle());
 *
 * // Wait for inventory to be full with 60 second timeout
 * WaitForConditionTask waitFull = new WaitForConditionTask(
 *     Conditions.inventoryFull()
 * ).withTimeout(Duration.ofSeconds(60));
 *
 * // Wait for bank to open with idle mouse behavior
 * WaitForConditionTask waitBank = new WaitForConditionTask(
 *     Conditions.bankOpen()
 * ).withIdleMouseBehavior(true);
 *
 * // Wait with early exit condition
 * WaitForConditionTask waitWithExit = new WaitForConditionTask(
 *     Conditions.playerIsIdle()
 * ).withEarlyExitCondition(Conditions.healthBelow(0.3));
 * }</pre>
 */
@Slf4j
public class WaitForConditionTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Default timeout for waiting (30 seconds per REQUIREMENTS.md).
     */
    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Minimum ticks between idle mouse movements.
     */
    private static final int IDLE_MOUSE_MIN_INTERVAL = 5;

    /**
     * Maximum ticks between idle mouse movements.
     */
    private static final int IDLE_MOUSE_MAX_INTERVAL = 15;

    /**
     * Maximum pixels for idle mouse drift.
     */
    private static final int IDLE_MOUSE_MAX_DRIFT = 50;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The condition to wait for.
     */
    @Getter
    private final StateCondition condition;

    /**
     * Whether to perform idle mouse behavior while waiting.
     */
    @Getter
    @Setter
    private boolean idleMouseBehavior = true;

    /**
     * Optional early exit condition (fails the task if this becomes true).
     */
    @Getter
    @Setter
    private StateCondition earlyExitCondition;

    /**
     * Optional callback when condition becomes true.
     */
    @Getter
    @Setter
    private Runnable onConditionMet;

    /**
     * Custom description.
     */
    @Getter
    @Setter
    private String description;

    /**
     * Whether to log each poll attempt (can be noisy).
     */
    @Getter
    @Setter
    private boolean verboseLogging = false;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Number of ticks spent waiting.
     */
    private int waitTicks = 0;

    /**
     * Ticks until next idle mouse movement.
     */
    private int idleMouseCountdown = 0;

    /**
     * Whether an idle mouse movement is in progress.
     */
    private boolean idleMousePending = false;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a wait task for a condition.
     *
     * @param condition the condition to wait for
     */
    public WaitForConditionTask(StateCondition condition) {
        this.condition = condition;
        this.timeout = DEFAULT_WAIT_TIMEOUT;
        resetIdleMouseCountdown();
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a wait task for player to be idle.
     *
     * @return wait task
     */
    public static WaitForConditionTask untilIdle() {
        return new WaitForConditionTask(ctx -> ctx.getPlayerState().isIdle())
                .withDescription("Wait until idle");
    }

    /**
     * Create a wait task for animation to complete.
     *
     * @return wait task
     */
    public static WaitForConditionTask untilAnimationComplete() {
        return new WaitForConditionTask(ctx -> !ctx.getPlayerState().isAnimating())
                .withDescription("Wait for animation complete");
    }

    /**
     * Create a wait task for a specific number of game ticks.
     *
     * @param ticks number of ticks to wait
     * @return wait task
     */
    public static WaitForConditionTask forTicks(int ticks) {
        return new WaitForConditionTask(new TickCountCondition(ticks))
                .withDescription("Wait " + ticks + " ticks");
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set whether to perform idle mouse behavior (builder-style).
     *
     * @param idle true to enable idle mouse behavior
     * @return this task for chaining
     */
    public WaitForConditionTask withIdleMouseBehavior(boolean idle) {
        this.idleMouseBehavior = idle;
        return this;
    }

    /**
     * Set an early exit condition (builder-style).
     * Task fails if this condition becomes true while waiting.
     *
     * @param condition the early exit condition
     * @return this task for chaining
     */
    public WaitForConditionTask withEarlyExitCondition(StateCondition condition) {
        this.earlyExitCondition = condition;
        return this;
    }

    /**
     * Set a callback for when condition is met (builder-style).
     *
     * @param callback the callback
     * @return this task for chaining
     */
    public WaitForConditionTask withOnConditionMet(Runnable callback) {
        this.onConditionMet = callback;
        return this;
    }

    /**
     * Set timeout duration (builder-style).
     *
     * @param timeout the timeout
     * @return this task for chaining
     */
    public WaitForConditionTask withTimeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public WaitForConditionTask withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * Enable verbose logging (builder-style).
     *
     * @param verbose true to enable
     * @return this task for chaining
     */
    public WaitForConditionTask withVerboseLogging(boolean verbose) {
        this.verboseLogging = verbose;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        // Can always execute - just waits
        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        waitTicks++;

        // Check early exit condition
        if (earlyExitCondition != null && earlyExitCondition.test(ctx)) {
            log.info("Wait task early exit condition triggered after {} ticks", waitTicks);
            fail("Early exit condition triggered");
            return;
        }

        // Check main condition
        boolean conditionMet;
        try {
            conditionMet = condition.test(ctx);
        } catch (Exception e) {
            log.error("Error evaluating wait condition", e);
            fail("Condition evaluation error: " + e.getMessage());
            return;
        }

        if (conditionMet) {
            log.info("Wait condition satisfied after {} ticks", waitTicks);
            if (onConditionMet != null) {
                try {
                    onConditionMet.run();
                } catch (Exception e) {
                    log.warn("Error in onConditionMet callback", e);
                }
            }
            complete();
            return;
        }

        // Condition not yet met - perform idle behavior
        if (idleMouseBehavior && !idleMousePending) {
            performIdleMouseBehavior(ctx);
        }

        if (verboseLogging) {
            log.trace("Waiting for condition (tick {})", waitTicks);
        }
    }

    // ========================================================================
    // Idle Mouse Behavior
    // ========================================================================

    /**
     * Perform humanized idle mouse movement while waiting.
     */
    private void performIdleMouseBehavior(TaskContext ctx) {
        idleMouseCountdown--;

        if (idleMouseCountdown > 0) {
            return;
        }

        // Get randomization for humanized behavior
        Randomization rand = ctx.getRandomization();

        // Reset countdown
        resetIdleMouseCountdown(rand);

        // Decide if we should move the mouse (not every time)
        if (!rand.chance(0.6)) {
            return;
        }

        // Get current mouse position
        java.awt.Point mousePos = ctx.getMouseController().getCurrentPosition();
        if (mousePos == null) {
            return;
        }

        // Calculate random drift
        int driftX = rand.uniformRandomInt(-IDLE_MOUSE_MAX_DRIFT, IDLE_MOUSE_MAX_DRIFT);
        int driftY = rand.uniformRandomInt(-IDLE_MOUSE_MAX_DRIFT, IDLE_MOUSE_MAX_DRIFT);
        
        // Apply smaller movements more often
        if (rand.chance(0.7)) {
            driftX = driftX / 3;
            driftY = driftY / 3;
        }

        int newX = mousePos.x + driftX;
        int newY = mousePos.y + driftY;

        // Clamp to reasonable screen bounds
        newX = Math.max(0, Math.min(newX, 1920));
        newY = Math.max(0, Math.min(newY, 1080));

        log.trace("Idle mouse drift: ({}, {}) -> ({}, {})", mousePos.x, mousePos.y, newX, newY);

        // Perform idle movement (these are screen coordinates from getCurrentPosition)
        idleMousePending = true;
        final int targetX = newX;
        final int targetY = newY;

        ctx.getMouseController().moveToScreen(targetX, targetY)
                .thenRun(() -> idleMousePending = false)
                .exceptionally(e -> {
                    idleMousePending = false;
                    return null;
                });
    }

    /**
     * Reset the idle mouse countdown using default randomization.
     * Called from constructor before TaskContext is available.
     */
    private void resetIdleMouseCountdown() {
        resetIdleMouseCountdown(null);
    }

    /**
     * Reset the idle mouse countdown.
     * 
     * @param rand the randomization instance (may be null)
     */
    private void resetIdleMouseCountdown(Randomization rand) {
        if (rand != null) {
            idleMouseCountdown = rand.uniformRandomInt(IDLE_MOUSE_MIN_INTERVAL, IDLE_MOUSE_MAX_INTERVAL);
        } else {
            // Fallback when no randomization available (e.g., constructor call)
            idleMouseCountdown = IDLE_MOUSE_MIN_INTERVAL
                    + new java.util.Random().nextInt(IDLE_MOUSE_MAX_INTERVAL - IDLE_MOUSE_MIN_INTERVAL);
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
        return "WaitForCondition";
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Get the number of ticks waited so far.
     *
     * @return wait ticks
     */
    public int getWaitTicks() {
        return waitTicks;
    }

    /**
     * Get wait progress as a ratio of timeout.
     *
     * @return progress (0.0 to 1.0)
     */
    public double getProgress() {
        if (timeout == null || timeout.isZero()) {
            return 0.0;
        }
        // Approximate: 1 tick = 600ms
        double elapsedMs = waitTicks * 600.0;
        return Math.min(1.0, elapsedMs / timeout.toMillis());
    }

    // ========================================================================
    // Special Condition: Tick Count
    // ========================================================================

    /**
     * A condition that becomes true after a specific number of ticks.
     */
    private static class TickCountCondition implements StateCondition {
        private final int targetTicks;
        private int currentTicks = 0;

        TickCountCondition(int targetTicks) {
            this.targetTicks = targetTicks;
        }

        @Override
        public boolean test(TaskContext ctx) {
            currentTicks++;
            return currentTicks >= targetTicks;
        }

        @Override
        public String describe() {
            return "TickCount(" + targetTicks + ")";
        }
    }

}

