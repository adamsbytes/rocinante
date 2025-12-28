package com.rocinante.behavior;

import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Models player fatigue accumulation and its effects on gameplay.
 * 
 * Per REQUIREMENTS.md Section 4.2:
 * 
 * Fatigue Accumulation (0.0 = fresh, 1.0 = exhausted):
 * - +0.0005 per action performed
 * - +0.00002 per second of active session time
 * - -0.1 per minute of break time
 * - -0.3 at session start (simulating return from AFK)
 * 
 * Fatigue Effects:
 * - Delay multiplier: 1.0 + (fatigue * 0.5) — up to 50% slower when exhausted
 * - Click variance multiplier: 1.0 + (fatigue * 0.4) — less precise when tired
 * - Misclick probability multiplier: 1.0 + (fatigue * 2.0) — up to 3x more misclicks
 * 
 * Activity intensity modifies accumulation rates via ActivityType.fatigueMultiplier.
 */
@Slf4j
@Singleton
public class FatigueModel {

    // === Fatigue accumulation rates (per REQUIREMENTS.md 4.2.1) ===
    
    /**
     * Base fatigue increase per action.
     * Modified by ActivityType.fatigueMultiplier.
     */
    private static final double FATIGUE_PER_ACTION = 0.0005;
    
    /**
     * Fatigue increase per second of active gameplay.
     * Modified by ActivityType.fatigueMultiplier.
     */
    private static final double FATIGUE_PER_SECOND = 0.00002;
    
    /**
     * Fatigue decrease per minute of break time.
     */
    private static final double RECOVERY_PER_MINUTE = 0.1;
    
    /**
     * Fatigue decrease at session start.
     */
    private static final double SESSION_START_RECOVERY = 0.3;
    
    /**
     * Maximum fatigue level.
     */
    private static final double MAX_FATIGUE = 1.0;
    
    /**
     * Minimum fatigue level.
     */
    private static final double MIN_FATIGUE = 0.0;
    
    // === Effect multiplier factors (per REQUIREMENTS.md 4.2.2) ===
    
    /**
     * Factor for delay multiplier: delay_mult = 1.0 + (fatigue * this)
     */
    private static final double DELAY_FACTOR = 0.5;
    
    /**
     * Factor for click variance: variance_mult = 1.0 + (fatigue * this)
     */
    private static final double CLICK_VARIANCE_FACTOR = 0.4;
    
    /**
     * Factor for misclick probability: misclick_mult = 1.0 + (fatigue * this)
     */
    private static final double MISCLICK_FACTOR = 2.0;

    // === Dependencies ===
    
    // Using Provider to break circular dependency:
    // GameStateService -> FatigueModel -> BotActivityTracker -> TaskExecutor -> TaskContext -> GameStateService
    private final Provider<BotActivityTracker> activityTrackerProvider;
    private final PlayerProfile playerProfile;
    
    // === State (using Atomic types for thread safety) ===
    
    /**
     * Current fatigue level (0.0 = fresh, 1.0 = exhausted).
     * Stored as bits in AtomicLong for thread-safe double operations.
     */
    private final AtomicLong fatigueLevelBits = new AtomicLong(Double.doubleToLongBits(0.0));
    
    /**
     * Timestamp of last fatigue update (for time-based accumulation).
     */
    private volatile Instant lastUpdateTime = Instant.now();
    
    /**
     * Whether currently on break (affects time accumulation).
     */
    private final AtomicBoolean onBreak = new AtomicBoolean(false);
    
    /**
     * Break start time for recovery calculation.
     */
    private volatile Instant breakStartTime = null;
    
    /**
     * Total actions this session (for debugging/stats).
     */
    private final AtomicLong sessionActionCount = new AtomicLong(0);

    @Inject
    public FatigueModel(Provider<BotActivityTracker> activityTrackerProvider, PlayerProfile playerProfile) {
        this.activityTrackerProvider = activityTrackerProvider;
        this.playerProfile = playerProfile;
        log.info("FatigueModel initialized");
    }

    /**
     * Constructor for testing.
     */
    public FatigueModel(Provider<BotActivityTracker> activityTrackerProvider, PlayerProfile playerProfile, 
                        double initialFatigue) {
        this.activityTrackerProvider = activityTrackerProvider;
        this.playerProfile = playerProfile;
        setFatigueLevelInternal(clampFatigue(initialFatigue));
    }
    
    // ========================================================================
    // Thread-Safe Double Accessors
    // ========================================================================
    
    /**
     * Get current fatigue level (thread-safe).
     * 
     * @return fatigue level (0.0 - 1.0)
     */
    public double getFatigueLevel() {
        return Double.longBitsToDouble(fatigueLevelBits.get());
    }
    
    /**
     * Set fatigue level (thread-safe, internal use).
     */
    private void setFatigueLevelInternal(double value) {
        fatigueLevelBits.set(Double.doubleToLongBits(value));
    }
    
    /**
     * Get session action count (thread-safe).
     * 
     * @return total actions this session
     */
    public long getSessionActionCount() {
        return sessionActionCount.get();
    }

    // ========================================================================
    // Session Lifecycle
    // ========================================================================

    /**
     * Called at session start. Applies recovery for time away.
     */
    public void onSessionStart() {
        // Apply session start recovery
        double oldFatigue = getFatigueLevel();
        setFatigueLevelInternal(clampFatigue(oldFatigue - SESSION_START_RECOVERY));
        
        lastUpdateTime = Instant.now();
        sessionActionCount.set(0);
        onBreak.set(false);
        breakStartTime = null;
        
        log.info("Session started: fatigue {} -> {} (recovered {})",
                String.format("%.3f", oldFatigue),
                String.format("%.3f", getFatigueLevel()),
                String.format("%.3f", SESSION_START_RECOVERY));
    }

    /**
     * Called at session end. Could persist fatigue state if desired.
     */
    public void onSessionEnd() {
        log.info("Session ended: final fatigue={}, actions={}",
                String.format("%.3f", getFatigueLevel()), getSessionActionCount());
    }

    // ========================================================================
    // Fatigue Updates
    // ========================================================================

    /**
     * Game tick handler - accumulates time-based fatigue.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        if (!onBreak.get()) {
            accumulateTimeFatigue();
        }
    }

    /**
     * Manual tick for when not using event subscription.
     */
    public void tick() {
        if (!onBreak.get()) {
            accumulateTimeFatigue();
        }
    }

    /**
     * Record an action being performed. Increases fatigue.
     */
    public void recordAction() {
        if (onBreak.get()) {
            return;
        }
        
        double intensityMultiplier = getIntensityMultiplier();
        double fatigueIncrease = FATIGUE_PER_ACTION * intensityMultiplier;
        
        setFatigueLevelInternal(clampFatigue(getFatigueLevel() + fatigueIncrease));
        sessionActionCount.incrementAndGet();
        
        log.trace("Action recorded: fatigue now {} (+{} with {}x intensity)",
                String.format("%.4f", getFatigueLevel()),
                String.format("%.5f", fatigueIncrease),
                String.format("%.1f", intensityMultiplier));
    }

    /**
     * Record break time for fatigue recovery.
     * 
     * @param breakDuration how long the break lasted
     */
    public void recordBreakTime(Duration breakDuration) {
        double minutes = breakDuration.toMillis() / 60000.0;
        double recovery = RECOVERY_PER_MINUTE * minutes;
        
        double oldFatigue = getFatigueLevel();
        setFatigueLevelInternal(clampFatigue(oldFatigue - recovery));
        
        log.debug("Break recovery: {} minutes -> fatigue {} -> {} (-{})",
                String.format("%.1f", minutes),
                String.format("%.3f", oldFatigue),
                String.format("%.3f", getFatigueLevel()),
                String.format("%.3f", recovery));
    }

    /**
     * Accumulate time-based fatigue since last update.
     */
    private void accumulateTimeFatigue() {
        Instant now = Instant.now();
        double secondsElapsed = Duration.between(lastUpdateTime, now).toMillis() / 1000.0;
        lastUpdateTime = now;
        
        if (secondsElapsed <= 0) {
            return;
        }
        
        double intensityMultiplier = getIntensityMultiplier();
        double fatigueIncrease = FATIGUE_PER_SECOND * secondsElapsed * intensityMultiplier;
        
        setFatigueLevelInternal(clampFatigue(getFatigueLevel() + fatigueIncrease));
    }

    /**
     * Get the current intensity multiplier from activity tracker.
     */
    private double getIntensityMultiplier() {
        if (activityTrackerProvider == null) {
            return 1.0;
        }
        BotActivityTracker tracker = activityTrackerProvider.get();
        return tracker != null ? tracker.getCurrentActivity().getFatigueMultiplier() : 1.0;
    }

    // ========================================================================
    // Break State Management
    // ========================================================================

    /**
     * Signal that a break is starting. Pauses fatigue accumulation.
     */
    public void startBreak() {
        if (onBreak.compareAndSet(false, true)) {
            breakStartTime = Instant.now();
            log.debug("Break started at fatigue {}", String.format("%.3f", getFatigueLevel()));
        }
    }

    /**
     * Signal that a break is ending. Applies recovery and resumes accumulation.
     */
    public void endBreak() {
        if (onBreak.get() && breakStartTime != null) {
            Duration breakDuration = Duration.between(breakStartTime, Instant.now());
            recordBreakTime(breakDuration);
        }
        
        onBreak.set(false);
        breakStartTime = null;
        lastUpdateTime = Instant.now();
        
        log.debug("Break ended at fatigue {}", String.format("%.3f", getFatigueLevel()));
    }

    /**
     * Check if currently on break.
     * 
     * @return true if on break
     */
    public boolean isOnBreak() {
        return onBreak.get();
    }

    // ========================================================================
    // Effect Multipliers
    // ========================================================================

    /**
     * Get the delay multiplier based on current fatigue.
     * Applied to all timing delays.
     * 
     * Per spec: 1.0 + (fatigue * 0.5) — up to 1.5x (50% slower) when exhausted.
     * 
     * @return delay multiplier (1.0 - 1.5)
     */
    public double getDelayMultiplier() {
        return 1.0 + (getFatigueLevel() * DELAY_FACTOR);
    }

    /**
     * Get the click variance multiplier based on current fatigue.
     * Applied to click position variance calculations.
     * 
     * Per spec: 1.0 + (fatigue * 0.4) — up to 1.4x (40% more variance) when exhausted.
     * 
     * @return click variance multiplier (1.0 - 1.4)
     */
    public double getClickVarianceMultiplier() {
        return 1.0 + (getFatigueLevel() * CLICK_VARIANCE_FACTOR);
    }

    /**
     * Get the misclick probability multiplier based on current fatigue.
     * Applied to base misclick rate.
     * 
     * Per spec: 1.0 + (fatigue * 2.0) — up to 3x more misclicks when exhausted.
     * 
     * @return misclick multiplier (1.0 - 3.0)
     */
    public double getMisclickMultiplier() {
        return 1.0 + (getFatigueLevel() * MISCLICK_FACTOR);
    }

    /**
     * Get the effective misclick rate after applying fatigue.
     * 
     * @return effective misclick probability
     */
    public double getEffectiveMisclickRate() {
        if (playerProfile == null) {
            return 0.02 * getMisclickMultiplier();
        }
        return playerProfile.getBaseMisclickRate() * getMisclickMultiplier();
    }

    // ========================================================================
    // Break Threshold
    // ========================================================================

    /**
     * Check if fatigue has reached the break threshold for this profile.
     * Uses the profile's break fatigue threshold, modified by account type.
     * 
     * @return true if a break should be taken
     */
    public boolean shouldTakeBreak() {
        double currentFatigue = getFatigueLevel();
        if (playerProfile == null) {
            return currentFatigue >= 0.80;
        }
        
        double threshold = playerProfile.getBreakFatigueThreshold();
        
        // Apply account type modifier (HCIM breaks earlier)
        BotActivityTracker tracker = activityTrackerProvider != null ? activityTrackerProvider.get() : null;
        if (tracker != null) {
            threshold *= tracker.getAccountType().getBreakThresholdModifier();
        }
        
        return currentFatigue >= threshold;
    }

    /**
     * Get the current break threshold for this profile/account.
     * 
     * @return the fatigue threshold that triggers breaks
     */
    public double getBreakThreshold() {
        double threshold = 0.80;
        
        if (playerProfile != null) {
            threshold = playerProfile.getBreakFatigueThreshold();
        }
        
        BotActivityTracker tracker = activityTrackerProvider != null ? activityTrackerProvider.get() : null;
        if (tracker != null) {
            threshold *= tracker.getAccountType().getBreakThresholdModifier();
        }
        
        return threshold;
    }

    // ========================================================================
    // Manual Control (for testing/debugging)
    // ========================================================================

    /**
     * Manually set fatigue level. Use with caution.
     * 
     * @param level new fatigue level (0.0 - 1.0)
     */
    public void setFatigueLevel(double level) {
        setFatigueLevelInternal(clampFatigue(level));
        log.debug("Fatigue manually set to {}", String.format("%.3f", getFatigueLevel()));
    }

    /**
     * Reset fatigue to zero. Called on long breaks or testing.
     */
    public void reset() {
        setFatigueLevelInternal(MIN_FATIGUE);
        sessionActionCount.set(0);
        lastUpdateTime = Instant.now();
        log.info("Fatigue reset to 0");
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private double clampFatigue(double value) {
        return Randomization.clamp(value, MIN_FATIGUE, MAX_FATIGUE);
    }

    /**
     * Get a summary of current fatigue state.
     * 
     * @return summary string
     */
    public String getSummary() {
        return String.format(
                "Fatigue[level=%.1f%%, delayMult=%.2f, varianceMult=%.2f, misclickMult=%.2f, actions=%d, onBreak=%s]",
                getFatigueLevel() * 100,
                getDelayMultiplier(),
                getClickVarianceMultiplier(),
                getMisclickMultiplier(),
                getSessionActionCount(),
                isOnBreak()
        );
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

