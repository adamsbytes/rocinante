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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Models player fatigue accumulation and its effects on gameplay.
 * 
 * Per REQUIREMENTS.md Section 4.2:
 * 
 * Fatigue Accumulation (0.0 = fresh, 1.0 = exhausted):
 * - +0.0005 per action performed
 * - +0.00002 per second of active session time (with quadratic term for extended sessions)
 * - -0.1 per minute of break time
 * - -0.3 at session start (simulating return from AFK)
 * 
 * Fatigue Effects:
 * - Delay multiplier: 1.0 + (fatigue * 0.5) — up to 50% slower when exhausted
 * - Click variance multiplier: 1.0 + (fatigue * 0.4) — less precise when tired
 * - Misclick probability multiplier: 1.0 + (fatigue * 2.0) — up to 3x more misclicks
 * 
 * Realism Features (jagged curves like real players):
 * - Performance crashes: Random sudden fatigue spikes (zoning out, frustration)
 * - Coffee breaks: Mini-recoveries without actual breaks (stretch, sip water, mental reset)
 * - Non-linear accumulation: Extended sessions compound fatigue faster (quadratic term)
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
     * Factor for click variance: variance_mult = 1.0 + (fatigue * this)
     */
    private static final double CLICK_VARIANCE_FACTOR = 0.4;
    
    /**
     * Factor for misclick probability: misclick_mult = 1.0 + (fatigue * this)
     */
    private static final double MISCLICK_FACTOR = 2.0;
    
    /**
     * Factor for Ex-Gaussian σ (variance): sigma_mult = 1.0 + (fatigue * this)
     * Tired people are less consistent - more spread in their reaction times.
     */
    private static final double SIGMA_FACTOR = 0.6;
    
    /**
     * Factor for Ex-Gaussian τ (tail): tau_mult = 1.0 + (fatigue * this)
     * Tired people have more "zoning out" moments - heavier right tail.
     */
    private static final double TAU_FACTOR = 0.8;
    
    // === Realism features: jagged fatigue curves ===
    
    /**
     * Performance crash: probability per second (~1.5% per minute = 0.00025 per second).
     * Simulates: zoning out, frustration spike, phone distraction.
     */
    private static final double CRASH_PROBABILITY_PER_SECOND = 0.00025;
    
    /**
     * Performance crash: minimum fatigue spike.
     */
    private static final double CRASH_MIN_SPIKE = 0.15;
    
    /**
     * Performance crash: maximum fatigue spike.
     */
    private static final double CRASH_MAX_SPIKE = 0.25;
    
    /**
     * Performance crash: minimum fatigue level before crashes can occur.
     */
    private static final double CRASH_MIN_FATIGUE = 0.20;
    
    /**
     * Performance crash: minimum seconds between crashes.
     */
    private static final double CRASH_COOLDOWN_SECONDS = 300; // 5 minutes
    
    /**
     * Coffee break: probability per second (~2% per minute = 0.00033 per second).
     * Simulates: stretch, sip water, quick mental reset.
     */
    private static final double COFFEE_BREAK_PROBABILITY_PER_SECOND = 0.00033;
    
    /**
     * Coffee break: minimum recovery amount.
     */
    private static final double COFFEE_BREAK_MIN_RECOVERY = 0.05;
    
    /**
     * Coffee break: maximum recovery amount.
     */
    private static final double COFFEE_BREAK_MAX_RECOVERY = 0.12;
    
    /**
     * Coffee break: minimum fatigue level before coffee breaks can occur.
     */
    private static final double COFFEE_BREAK_MIN_FATIGUE = 0.30;
    
    /**
     * Coffee break: minimum seconds between coffee breaks.
     */
    private static final double COFFEE_BREAK_COOLDOWN_SECONDS = 600; // 10 minutes
    
    /**
     * Coffee break: increased probability after a crash (human "snap out of it").
     */
    private static final double COFFEE_BREAK_POST_CRASH_MULTIPLIER = 3.0;
    
    /**
     * Coffee break: window after crash where probability is elevated (seconds).
     */
    private static final double COFFEE_BREAK_POST_CRASH_WINDOW = 180; // 3 minutes
    
    /**
     * Non-linear accumulation: quadratic factor for extended sessions.
     * fatigue_rate *= (1 + session_hours² * this)
     * At 2 hours: 1.6x, at 3 hours: 2.35x, at 4 hours: 3.4x
     */
    private static final double SESSION_QUADRATIC_FACTOR = 0.15;

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
     * Timestamp of session start (for quadratic accumulation term).
     */
    private volatile Instant sessionStartTime = Instant.now();
    
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
    
    /**
     * Timestamp of last performance crash (for cooldown tracking).
     */
    private volatile Instant lastCrashTime = null;
    
    /**
     * Timestamp of last coffee break (for cooldown tracking).
     */
    private volatile Instant lastCoffeeBreakTime = null;
    
    /**
     * Count of performance crashes this session (for stats).
     */
    private final AtomicLong crashCount = new AtomicLong(0);
    
    /**
     * Count of coffee breaks this session (for stats).
     */
    private final AtomicLong coffeeBreakCount = new AtomicLong(0);

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
        
        Instant now = Instant.now();
        lastUpdateTime = now;
        sessionStartTime = now;
        sessionActionCount.set(0);
        onBreak.set(false);
        breakStartTime = null;
        
        // Reset crash/coffee break tracking for new session
        lastCrashTime = null;
        lastCoffeeBreakTime = null;
        crashCount.set(0);
        coffeeBreakCount.set(0);
        
        log.info("Session started: fatigue {} -> {} (recovered {})",
                String.format("%.3f", oldFatigue),
                String.format("%.3f", getFatigueLevel()),
                String.format("%.3f", SESSION_START_RECOVERY));
    }

    /**
     * Called at session end. Could persist fatigue state if desired.
     */
    public void onSessionEnd() {
        log.info("Session ended: final fatigue={}, actions={}, duration={}h, crashes={}, coffeeBreaks={}",
                String.format("%.3f", getFatigueLevel()), 
                getSessionActionCount(),
                String.format("%.1f", getSessionDurationHours()),
                crashCount.get(),
                coffeeBreakCount.get());
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
        
        log.debug("Action recorded: fatigue now {} (+{} with {}x intensity)",
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
     * Includes non-linear accumulation, performance crashes, and coffee breaks.
     */
    private void accumulateTimeFatigue() {
        Instant now = Instant.now();
        double secondsElapsed = Duration.between(lastUpdateTime, now).toMillis() / 1000.0;
        lastUpdateTime = now;
        
        if (secondsElapsed <= 0) {
            return;
        }
        
        double currentFatigue = getFatigueLevel();
        
        // Check for random events first (before normal accumulation)
        // This creates the "jagged" patterns real players have
        
        // Performance crash check (sudden fatigue spike)
        if (maybePerformanceCrash(now, secondsElapsed, currentFatigue)) {
            currentFatigue = getFatigueLevel(); // Re-read after crash
        }
        
        // Coffee break check (mini-recovery)
        if (maybeCoffeeBreak(now, secondsElapsed, currentFatigue)) {
            currentFatigue = getFatigueLevel(); // Re-read after recovery
        }
        
        // Calculate base fatigue increase with intensity multiplier
        double intensityMultiplier = getIntensityMultiplier();
        double baseFatigueIncrease = FATIGUE_PER_SECOND * secondsElapsed * intensityMultiplier;
        
        // Apply quadratic term for extended sessions
        // fatigue_rate *= (1 + session_hours² * factor)
        // This models cognitive load compounding over time
        double sessionHours = getSessionDurationHours();
        double quadraticMultiplier = 1.0 + (sessionHours * sessionHours * SESSION_QUADRATIC_FACTOR);
        double fatigueIncrease = baseFatigueIncrease * quadraticMultiplier;
        
        setFatigueLevelInternal(clampFatigue(currentFatigue + fatigueIncrease));
    }
    
    /**
     * Get session duration in hours.
     */
    private double getSessionDurationHours() {
        if (sessionStartTime == null) {
            return 0.0;
        }
        return Duration.between(sessionStartTime, Instant.now()).toMillis() / 3600000.0;
    }
    
    /**
     * Check for and apply a random performance crash.
     * Simulates: zoning out, frustration, phone distraction, sudden tiredness.
     * 
     * @param now current timestamp
     * @param secondsElapsed seconds since last tick
     * @param currentFatigue current fatigue level
     * @return true if a crash occurred
     */
    private boolean maybePerformanceCrash(Instant now, double secondsElapsed, double currentFatigue) {
        // Only crash if fatigue is above threshold
        if (currentFatigue < CRASH_MIN_FATIGUE) {
            return false;
        }
        
        // Check cooldown
        if (lastCrashTime != null) {
            double secondsSinceCrash = Duration.between(lastCrashTime, now).toMillis() / 1000.0;
            if (secondsSinceCrash < CRASH_COOLDOWN_SECONDS) {
                return false;
            }
        }
        
        // Probability scales with time elapsed (more ticks = more chances)
        // Also slightly more likely when already fatigued (tired people crash more)
        double fatigueScaling = 1.0 + (currentFatigue * 0.5); // Up to 1.5x at max fatigue
        double crashProbability = CRASH_PROBABILITY_PER_SECOND * secondsElapsed * fatigueScaling;
        
        if (ThreadLocalRandom.current().nextDouble() < crashProbability) {
            // Crash! Apply sudden fatigue spike
            double spike = CRASH_MIN_SPIKE + ThreadLocalRandom.current().nextDouble() * (CRASH_MAX_SPIKE - CRASH_MIN_SPIKE);
            double newFatigue = clampFatigue(currentFatigue + spike);
            setFatigueLevelInternal(newFatigue);
            
            lastCrashTime = now;
            crashCount.incrementAndGet();
            
            log.info("⚡ Performance crash! Fatigue {} -> {} (+{}) [session crashes: {}]",
                    String.format("%.3f", currentFatigue),
                    String.format("%.3f", newFatigue),
                    String.format("%.3f", spike),
                    crashCount.get());
            return true;
        }
        
        return false;
    }
    
    /**
     * Check for and apply a random coffee break (mini-recovery).
     * Simulates: stretch, sip water, quick mental reset, deep breath.
     * 
     * @param now current timestamp
     * @param secondsElapsed seconds since last tick
     * @param currentFatigue current fatigue level
     * @return true if a coffee break occurred
     */
    private boolean maybeCoffeeBreak(Instant now, double secondsElapsed, double currentFatigue) {
        // Only coffee break if fatigue is above threshold
        if (currentFatigue < COFFEE_BREAK_MIN_FATIGUE) {
            return false;
        }
        
        // Check cooldown
        if (lastCoffeeBreakTime != null) {
            double secondsSinceCoffee = Duration.between(lastCoffeeBreakTime, now).toMillis() / 1000.0;
            if (secondsSinceCoffee < COFFEE_BREAK_COOLDOWN_SECONDS) {
                return false;
            }
        }
        
        // Base probability
        double coffeeProbability = COFFEE_BREAK_PROBABILITY_PER_SECOND * secondsElapsed;
        
        // Elevated probability after a recent crash (human "snap out of it" behavior)
        if (lastCrashTime != null) {
            double secondsSinceCrash = Duration.between(lastCrashTime, now).toMillis() / 1000.0;
            if (secondsSinceCrash < COFFEE_BREAK_POST_CRASH_WINDOW) {
                coffeeProbability *= COFFEE_BREAK_POST_CRASH_MULTIPLIER;
            }
        }
        
        if (ThreadLocalRandom.current().nextDouble() < coffeeProbability) {
            // Coffee break! Apply mini-recovery
            double recovery = COFFEE_BREAK_MIN_RECOVERY + 
                ThreadLocalRandom.current().nextDouble() * (COFFEE_BREAK_MAX_RECOVERY - COFFEE_BREAK_MIN_RECOVERY);
            double newFatigue = clampFatigue(currentFatigue - recovery);
            setFatigueLevelInternal(newFatigue);
            
            lastCoffeeBreakTime = now;
            coffeeBreakCount.incrementAndGet();
            
            log.info("☕ Coffee break! Fatigue {} -> {} (-{}) [session recoveries: {}]",
                    String.format("%.3f", currentFatigue),
                    String.format("%.3f", newFatigue),
                    String.format("%.3f", recovery),
                    coffeeBreakCount.get());
            return true;
        }
        
        return false;
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
     * Get the Ex-Gaussian sigma (σ) multiplier based on current fatigue.
     * Tired people have more variance - less consistent timing.
     * At max fatigue: σ becomes 1.6x larger.
     * 
     * @return sigma multiplier (1.0 - 1.6)
     */
    public double getSigmaMultiplier() {
        return 1.0 + (getFatigueLevel() * SIGMA_FACTOR);
    }
    
    /**
     * Get the Ex-Gaussian tau (τ) multiplier based on current fatigue.
     * Tired people have more "zoning out" - heavier right tail.
     * At max fatigue: τ becomes 1.8x larger.
     * 
     * @return tau multiplier (1.0 - 1.8)
     */
    public double getTauMultiplier() {
        return 1.0 + (getFatigueLevel() * TAU_FACTOR);
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
                "Fatigue[level=%.1f%%, σMult=%.2f, τMult=%.2f, clickVar=%.2f, misclick=%.2f, " +
                "actions=%d, onBreak=%s, sessionHrs=%.1f, crashes=%d, coffeeBreaks=%d]",
                getFatigueLevel() * 100,
                getSigmaMultiplier(),
                getTauMultiplier(),
                getClickVarianceMultiplier(),
                getMisclickMultiplier(),
                getSessionActionCount(),
                isOnBreak(),
                getSessionDurationHours(),
                crashCount.get(),
                coffeeBreakCount.get()
        );
    }
    
    /**
     * Get count of performance crashes this session.
     */
    public long getCrashCount() {
        return crashCount.get();
    }
    
    /**
     * Get count of coffee breaks this session.
     */
    public long getCoffeeBreakCount() {
        return coffeeBreakCount.get();
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

