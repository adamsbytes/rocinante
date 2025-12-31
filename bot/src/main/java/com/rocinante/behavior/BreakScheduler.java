package com.rocinante.behavior;

import com.rocinante.tasks.Task;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/**
 * Schedules breaks based on action count, time, and fatigue level.
 * 
 * Per REQUIREMENTS.md Section 4.3:
 * 
 * Break Types:
 * - Micro-pause: 2-8 seconds, every 30-90 actions (30% chance)
 * - Short break: 30-180 seconds, every 15-40 minutes (60% chance)
 * - Long break: 5-20 minutes, every 60-120 minutes (80% chance)
 * - Session end: After 2-6 hours (mandatory logout)
 * 
 * Break timing uses exponential distribution to avoid predictable patterns.
 * Breaks are only taken when the current activity allows interruption.
 */
@Slf4j
@Singleton
public class BreakScheduler {

    // === Dependencies ===
    
    private final BotActivityTracker activityTracker;
    private final FatigueModel fatigueModel;
    private final PlayerProfile playerProfile;
    private final Randomization randomization;
    
    /**
     * Factory for creating break tasks. Injected to avoid circular dependencies.
     */
    private Function<BreakType, Task> breakTaskFactory;
    
    // === Tracking State ===
    
    /**
     * Actions since last micro-pause.
     */
    @Getter
    private int actionsSinceMicroPause = 0;

    /**
     * Total micro-pauses taken this session.
     */
    @Getter
    private int microPausesTaken = 0;

    /**
     * Total short breaks taken this session.
     */
    @Getter
    private int shortBreaksTaken = 0;

    /**
     * Total long breaks taken this session.
     */
    @Getter
    private int longBreaksTaken = 0;
    
    /**
     * Actions threshold for next micro-pause.
     */
    private int nextMicroPauseThreshold;
    
    /**
     * Time of last short break.
     */
    private Instant lastShortBreak = Instant.now();
    
    /**
     * Time threshold for next short break.
     */
    private Instant nextShortBreakTime;
    
    /**
     * Time of last long break.
     */
    private Instant lastLongBreak = Instant.now();
    
    /**
     * Time threshold for next long break.
     */
    private Instant nextLongBreakTime;
    
    /**
     * Session start time.
     */
    @Getter
    private Instant sessionStartTime = Instant.now();
    
    /**
     * Scheduled session end time.
     */
    private Instant sessionEndTime;
    
    /**
     * Currently pending break (if any).
     */
    @Getter
    private BreakType pendingBreak = null;
    
    /**
     * Whether break injection is enabled.
     */
    @Getter
    private boolean enabled = true;

    @Inject
    public BreakScheduler(BotActivityTracker activityTracker,
                          FatigueModel fatigueModel,
                          PlayerProfile playerProfile,
                          Randomization randomization) {
        this.activityTracker = activityTracker;
        this.fatigueModel = fatigueModel;
        this.playerProfile = playerProfile;
        this.randomization = randomization;
        
        initializeThresholds();
        log.info("BreakScheduler initialized");
    }

    /**
     * Constructor for testing.
     */
    public BreakScheduler(BotActivityTracker activityTracker,
                          FatigueModel fatigueModel,
                          PlayerProfile playerProfile,
                          Randomization randomization,
                          Instant sessionStart) {
        this.activityTracker = activityTracker;
        this.fatigueModel = fatigueModel;
        this.playerProfile = playerProfile;
        this.randomization = randomization;
        this.sessionStartTime = sessionStart;
        initializeThresholds();
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    private void initializeThresholds() {
        scheduleNextMicroPause();
        scheduleNextShortBreak();
        scheduleNextLongBreak();
        // Note: scheduleSessionEnd() is called in onSessionStart() after sessionStartTime is set
    }

    /**
     * Set the break task factory. Required before breaks can be created.
     * 
     * @param factory function that creates tasks for each break type
     */
    public void setBreakTaskFactory(Function<BreakType, Task> factory) {
        this.breakTaskFactory = factory;
    }

    // ========================================================================
    // Session Lifecycle
    // ========================================================================

    /**
     * Called when a new session starts.
     */
    public void onSessionStart() {
        sessionStartTime = Instant.now();
        lastShortBreak = Instant.now();
        lastLongBreak = Instant.now();
        actionsSinceMicroPause = 0;
        pendingBreak = null;
        
        initializeThresholds();
        
        // Schedule session end AFTER sessionStartTime is set (fixes timing bug)
        scheduleSessionEnd();
        
        log.info("Break scheduler session started, session end scheduled at {}",
                sessionEndTime);
    }

    /**
     * Called when a session ends.
     */
    public void onSessionEnd() {
        log.info("Break scheduler session ended after {}",
                Duration.between(sessionStartTime, Instant.now()));
    }

    // ========================================================================
    // Game Tick Handler
    // ========================================================================

    /**
     * Check for breaks each game tick.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        tick();
    }

    /**
     * Manual tick for when not using event subscription.
     */
    public void tick() {
        if (!enabled) {
            return;
        }
        
        // Don't schedule breaks if we're already on break
        if (fatigueModel.isOnBreak()) {
            return;
        }
        
        // Check break conditions in priority order
        checkForBreaks();
    }

    // ========================================================================
    // Action Recording
    // ========================================================================

    /**
     * Record an action being performed. Updates micro-pause tracking.
     */
    public void recordAction() {
        actionsSinceMicroPause++;
        fatigueModel.recordAction();
    }

    // ========================================================================
    // Break Checking
    // ========================================================================

    private void checkForBreaks() {
        // Already have a pending break
        if (pendingBreak != null) {
            return;
        }
        
        Instant now = Instant.now();
        
        // Check in priority order (most important first)
        
        // 1. Session end (mandatory)
        if (shouldEndSession(now)) {
            pendingBreak = BreakType.SESSION_END;
            log.info("Session end triggered after {}", 
                    Duration.between(sessionStartTime, now));
            return;
        }
        
        // 2. Long break (time-based)
        if (shouldTakeLongBreak(now)) {
            pendingBreak = BreakType.LONG_BREAK;
            log.debug("Long break triggered");
            return;
        }
        
        // 3. Short break (time-based or fatigue-based)
        if (shouldTakeShortBreak(now)) {
            pendingBreak = BreakType.SHORT_BREAK;
            log.debug("Short break triggered");
            return;
        }
        
        // 4. Micro-pause (action-based)
        if (shouldTakeMicroPause()) {
            pendingBreak = BreakType.MICRO_PAUSE;
            log.debug("Micro-pause triggered after {} actions", actionsSinceMicroPause);
            return;
        }
    }

    /**
     * Check if session should end.
     */
    public boolean shouldEndSession(Instant now) {
        return sessionEndTime != null && now.isAfter(sessionEndTime);
    }

    /**
     * Check if a long break should be taken.
     */
    public boolean shouldTakeLongBreak(Instant now) {
        if (!canTakeBreak()) {
            return false;
        }
        
        if (nextLongBreakTime == null || now.isBefore(nextLongBreakTime)) {
            return false;
        }
        
        // Probability check
        return randomization.chance(BreakType.LONG_BREAK.getProbability());
    }

    /**
     * Check if a short break should be taken.
     */
    public boolean shouldTakeShortBreak(Instant now) {
        if (!canTakeBreak()) {
            return false;
        }
        
        // Time-based check
        boolean timeTriggered = nextShortBreakTime != null && now.isAfter(nextShortBreakTime);
        
        // Fatigue-based check
        boolean fatigueTriggered = fatigueModel.shouldTakeBreak();
        
        if (!timeTriggered && !fatigueTriggered) {
            return false;
        }
        
        // Probability check (always pass if fatigue triggered)
        if (fatigueTriggered) {
            return true;
        }
        
        return randomization.chance(BreakType.SHORT_BREAK.getProbability());
    }

    /**
     * Check if a micro-pause should be taken.
     */
    public boolean shouldTakeMicroPause() {
        if (!canTakeBreak()) {
            return false;
        }
        
        if (actionsSinceMicroPause < nextMicroPauseThreshold) {
            return false;
        }
        
        return randomization.chance(BreakType.MICRO_PAUSE.getProbability());
    }

    /**
     * Check if breaks are allowed in the current context.
     */
    private boolean canTakeBreak() {
        if (activityTracker == null) {
            return true;
        }
        return activityTracker.canTakeBreak();
    }

    // ========================================================================
    // Break Task Creation
    // ========================================================================

    /**
     * Get the scheduled break as a task, if one is pending.
     * Clears the pending break after returning.
     * 
     * @return Optional containing the break task, or empty if no break pending
     */
    public Optional<Task> getScheduledBreak() {
        if (pendingBreak == null) {
            return Optional.empty();
        }
        
        // Check if we can actually take the break
        if (!canTakeBreak()) {
            // Defer until we can
            return Optional.empty();
        }
        
        BreakType breakType = pendingBreak;
        pendingBreak = null;
        
        // Create the task
        Task breakTask = createBreakTask(breakType);
        if (breakTask == null) {
            log.warn("Break task factory returned null for type: {}", breakType);
            return Optional.empty();
        }
        
        log.debug("Returning break task: {}", breakType);
        return Optional.of(breakTask);
    }

    /**
     * Create a task for the given break type.
     */
    private Task createBreakTask(BreakType breakType) {
        if (breakTaskFactory != null) {
            return breakTaskFactory.apply(breakType);
        }
        
        // No factory set - can't create tasks
        log.warn("No break task factory configured");
        return null;
    }

    // ========================================================================
    // Break Completion
    // ========================================================================

    /**
     * Called when a break is completed.
     * Updates tracking state and schedules next break.
     * 
     * @param breakType the type of break that completed
     * @param actualDuration how long the break actually lasted
     */
    public void onBreakCompleted(BreakType breakType, Duration actualDuration) {
        Instant now = Instant.now();
        
        switch (breakType) {
            case MICRO_PAUSE:
                actionsSinceMicroPause = 0;
                scheduleNextMicroPause();
                break;
                
            case SHORT_BREAK:
                lastShortBreak = now;
                scheduleNextShortBreak();
                fatigueModel.recordBreakTime(actualDuration);
                break;
                
            case LONG_BREAK:
                lastLongBreak = now;
                lastShortBreak = now;  // Reset short break timer too
                scheduleNextLongBreak();
                scheduleNextShortBreak();
                fatigueModel.recordBreakTime(actualDuration);
                break;
                
            case SESSION_END:
                // Session is ending, no scheduling needed
                break;
        }
        
        log.debug("Break completed: {} ({})", breakType, actualDuration);
    }

    // ========================================================================
    // Scheduling
    // ========================================================================

    private void scheduleNextMicroPause() {
        // Use exponential distribution centered on the interval
        int minActions = BreakType.MICRO_PAUSE.getMinInterval();
        int maxActions = BreakType.MICRO_PAUSE.getMaxInterval();
        
        // Exponential-ish distribution via multiple uniform samples
        int base = randomization.uniformRandomInt(minActions, maxActions);
        int variance = randomization.uniformRandomInt(-10, 10);
        nextMicroPauseThreshold = Math.max(minActions, base + variance);
        
        log.debug("Next micro-pause scheduled at {} actions", nextMicroPauseThreshold);
    }

    private void scheduleNextShortBreak() {
        int minSeconds = BreakType.SHORT_BREAK.getMinInterval();
        int maxSeconds = BreakType.SHORT_BREAK.getMaxInterval();
        
        // Exponential distribution
        double lambda = 1.0 / ((minSeconds + maxSeconds) / 2.0);
        int intervalSeconds = (int) randomization.exponentialRandom(lambda, minSeconds, maxSeconds);
        
        nextShortBreakTime = Instant.now().plusSeconds(intervalSeconds);
        
        log.debug("Next short break scheduled in {} seconds", intervalSeconds);
    }

    private void scheduleNextLongBreak() {
        int minSeconds = BreakType.LONG_BREAK.getMinInterval();
        int maxSeconds = BreakType.LONG_BREAK.getMaxInterval();
        
        // Exponential distribution
        double lambda = 1.0 / ((minSeconds + maxSeconds) / 2.0);
        int intervalSeconds = (int) randomization.exponentialRandom(lambda, minSeconds, maxSeconds);
        
        nextLongBreakTime = Instant.now().plusSeconds(intervalSeconds);
        
        log.debug("Next long break scheduled in {} minutes", intervalSeconds / 60);
    }

    private void scheduleSessionEnd() {
        int minSeconds = BreakType.SESSION_END.getMinInterval();
        int maxSeconds = BreakType.SESSION_END.getMaxInterval();
        
        // Session length with some variance
        int sessionLengthSeconds = randomization.uniformRandomInt(minSeconds, maxSeconds);
        
        // Apply account-type modifier (HCIM: shorter sessions)
        if (activityTracker != null && activityTracker.getAccountType().isHardcore()) {
            sessionLengthSeconds = (int) (sessionLengthSeconds * 0.8);  // 20% shorter
        }
        
        sessionEndTime = sessionStartTime.plusSeconds(sessionLengthSeconds);
        
        log.info("Session end scheduled after {} hours", sessionLengthSeconds / 3600.0);
    }

    // ========================================================================
    // Control
    // ========================================================================

    /**
     * Enable or disable break scheduling.
     * 
     * @param enabled true to enable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.debug("Break scheduler {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Cancel any pending break.
     */
    public void cancelPendingBreak() {
        if (pendingBreak != null) {
            log.debug("Cancelled pending break: {}", pendingBreak);
            pendingBreak = null;
        }
    }

    /**
     * Force a specific break type to be scheduled.
     * 
     * @param breakType the break type to schedule
     */
    public void forceBreak(BreakType breakType) {
        pendingBreak = breakType;
        log.debug("Forced break: {}", breakType);
    }

    // ========================================================================
    // Status
    // ========================================================================

    /**
     * Get time until next short break.
     * 
     * @return duration until short break
     */
    public Duration getTimeUntilShortBreak() {
        if (nextShortBreakTime == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(Instant.now(), nextShortBreakTime);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * Get time until next long break.
     * 
     * @return duration until long break
     */
    public Duration getTimeUntilLongBreak() {
        if (nextLongBreakTime == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(Instant.now(), nextLongBreakTime);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * Get time until session end.
     * 
     * @return duration until session end
     */
    public Duration getTimeUntilSessionEnd() {
        if (sessionEndTime == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(Instant.now(), sessionEndTime);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * Get session duration so far.
     * 
     * @return duration since session start
     */
    public Duration getSessionDuration() {
        return Duration.between(sessionStartTime, Instant.now());
    }

    /**
     * Get the number of breaks taken this session.
     *
     * @return count of breaks taken
     */
    public int getBreaksTaken() {
        return microPausesTaken + shortBreaksTaken + longBreaksTaken;
    }

    /**
     * Get total break duration this session.
     *
     * @return total duration of all breaks
     */
    public Duration getTotalBreakDuration() {
        // Estimate based on average break durations
        long microPauseSecs = microPausesTaken * 5; // ~5 sec average
        long shortBreakSecs = shortBreaksTaken * 60; // ~1 min average
        long longBreakSecs = longBreaksTaken * 600; // ~10 min average
        return Duration.ofSeconds(microPauseSecs + shortBreakSecs + longBreakSecs);
    }

    /**
     * Get a summary of scheduler state.
     * 
     * @return summary string
     */
    public String getSummary() {
        return String.format(
                "BreakScheduler[enabled=%s, pending=%s, actions=%d/%d, shortIn=%dm, longIn=%dm, sessionEnd=%dm]",
                enabled,
                pendingBreak,
                actionsSinceMicroPause,
                nextMicroPauseThreshold,
                getTimeUntilShortBreak().toMinutes(),
                getTimeUntilLongBreak().toMinutes(),
                getTimeUntilSessionEnd().toMinutes()
        );
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

