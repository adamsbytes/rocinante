package com.rocinante.behavior;

import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controls tick jitter to desync action timing from game tick boundaries.
 * 
 * Problem: Bots that execute actions exactly on tick boundaries (0ms, 600ms, 1200ms)
 * are trivially detectable via server-side timing analysis.
 * 
 * Solution: Add human-like perception delay (20-100ms) after each tick before
 * executing actions. This creates the natural latency pattern seen in real players.
 * 
 * Jitter uses Ex-Gaussian distribution (right-skewed like human reaction times):
 * - μ (mu): Base mean delay (~40ms)
 * - σ (sigma): Consistency of perception (~15ms)
 * - τ (tau): Occasional longer delays (~20ms tail)
 * 
 * Activity-based scaling:
 * - CRITICAL/HIGH: Reduced jitter (need responsiveness in combat)
 * - MEDIUM: Standard jitter
 * - LOW/IDLE: Increased jitter (relaxed, less attentive)
 * 
 * Emergency bypass: 0ms jitter for combat emergencies (low HP, under attack).
 */
@Slf4j
@Singleton
public class TickJitterController {

    // ========================================================================
    // Jitter Constants
    // ========================================================================
    
    /**
     * Minimum jitter to avoid 0ms (still detectable).
     */
    private static final long MIN_JITTER_MS = 15;
    
    /**
     * Maximum jitter cap (allows tick skips and attention lapses).
     */
    private static final long MAX_JITTER_MS = 10_000;
    
    /**
     * One game tick in milliseconds.
     */
    private static final long GAME_TICK_MS = 600;
    
    /**
     * Threshold for logging long delays (only log DEBUG if >= this).
     */
    private static final long LOG_THRESHOLD_MS = 1000;
    
    /**
     * Minimum delay for anticipation (player was ready).
     */
    private static final long ANTICIPATION_MIN_MS = 25;
    
    /**
     * Maximum delay for anticipation.
     */
    private static final long ANTICIPATION_MAX_MS = 50;
    
    /**
     * Minimum delay for attention lapse (zone out).
     */
    private static final long ATTENTION_LAPSE_MIN_MS = 1500;
    
    /**
     * Maximum delay for attention lapse.
     */
    private static final long ATTENTION_LAPSE_MAX_MS = 3000;
    
    // ========================================================================
    // Activity Scaling Factors (for Ex-Gaussian jitter)
    // ========================================================================
    
    /**
     * Jitter multiplier for CRITICAL activities (fast reactions needed).
     */
    private static final double CRITICAL_SCALE = 0.4;
    
    /**
     * Jitter multiplier for HIGH activities.
     */
    private static final double HIGH_SCALE = 0.6;
    
    /**
     * Jitter multiplier for MEDIUM activities (standard).
     */
    private static final double MEDIUM_SCALE = 1.0;
    
    /**
     * Jitter multiplier for LOW activities (relaxed).
     */
    private static final double LOW_SCALE = 1.3;
    
    /**
     * Jitter multiplier for IDLE activities (very relaxed).
     */
    private static final double IDLE_SCALE = 1.5;
    
    // ========================================================================
    // Tick Skip Activity Scaling (different from jitter - affects probability)
    // ========================================================================
    
    /**
     * Tick skip multiplier for CRITICAL (almost never skip in boss fights).
     */
    private static final double CRITICAL_SKIP_SCALE = 0.1;
    
    /**
     * Tick skip multiplier for HIGH (rarely skip in combat).
     */
    private static final double HIGH_SKIP_SCALE = 0.3;
    
    /**
     * Tick skip multiplier for MEDIUM (standard).
     */
    private static final double MEDIUM_SKIP_SCALE = 1.0;
    
    /**
     * Tick skip multiplier for LOW (more likely during grinding).
     */
    private static final double LOW_SKIP_SCALE = 1.5;
    
    /**
     * Tick skip multiplier for AFK_COMBAT (NMZ, crabs - zoning out expected).
     */
    private static final double AFK_COMBAT_SKIP_SCALE = 2.0;
    
    /**
     * Tick skip multiplier for IDLE (very relaxed, high skip rate).
     */
    private static final double IDLE_SKIP_SCALE = 2.5;

    // ========================================================================
    // Dependencies (all required - no fallbacks)
    // ========================================================================
    
    private final Randomization randomization;
    private final ScheduledExecutorService executor;
    private final PlayerProfile playerProfile;
    private final PerformanceState performanceState;
    private final FatigueModel fatigueModel;
    private final BotActivityTracker activityTracker;
    
    /**
     * Thread name counter for obfuscation.
     */
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(13);

    // ========================================================================
    // State
    // ========================================================================
    
    /**
     * Whether a jittered execution is currently pending.
     * Prevents double-scheduling within the same tick.
     */
    private final AtomicBoolean jitterPending = new AtomicBoolean(false);
    
    /**
     * The currently scheduled jitter task (if any).
     */
    private volatile ScheduledFuture<?> pendingTask = null;
    
    /**
     * Last calculated jitter value (for debugging/metrics).
     */
    @Getter
    private volatile long lastJitterMs = 0;
    
    /**
     * Whether jitter is enabled. Can be disabled for testing.
     */
    @Getter
    @Setter
    private volatile boolean enabled = true;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public TickJitterController(Randomization randomization,
                                PlayerProfile playerProfile,
                                PerformanceState performanceState,
                                FatigueModel fatigueModel,
                                BotActivityTracker activityTracker) {
        this.randomization = randomization;
        this.playerProfile = playerProfile;
        this.performanceState = java.util.Objects.requireNonNull(performanceState, 
                "PerformanceState is required");
        this.fatigueModel = fatigueModel;
        this.activityTracker = activityTracker;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Thread-" + THREAD_COUNTER.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        log.info("TickJitterController initialized");
    }

    /**
     * Constructor for testing with custom executor.
     */
    public TickJitterController(Randomization randomization,
                                PlayerProfile playerProfile,
                                PerformanceState performanceState,
                                FatigueModel fatigueModel,
                                BotActivityTracker activityTracker,
                                ScheduledExecutorService executor) {
        this.randomization = randomization;
        this.playerProfile = playerProfile;
        this.performanceState = java.util.Objects.requireNonNull(performanceState,
                "PerformanceState is required");
        this.fatigueModel = fatigueModel;
        this.activityTracker = activityTracker;
        this.executor = executor;
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Calculate jitter delay for the given activity type.
     * Incorporates:
     * - Ex-Gaussian base distribution (profile-specific)
     * - Activity-based scaling
     * - Fatigue-based increases
     * - Tick skip probability (occasionally delay 1-2 game ticks)
     * - Attention lapse probability (rare but significant delays)
     * 
     * @param activity the current activity type
     * @return jitter delay in milliseconds
     */
    public long calculateJitter(ActivityType activity) {
        if (!enabled) {
            return 0;
        }
        
        // Get Ex-Gaussian parameters (from profile or defaults)
        double mu = getJitterMu();
        double sigma = getJitterSigma();
        double tau = getJitterTau();
        
        // Apply fatigue-based increases to distribution parameters
        double fatigueLevel = getFatigueLevel();
        double fatiguedMu = mu * (1.0 + fatigueLevel * 0.5);  // Up to 50% slower mean
        double fatiguedSigma = sigma * getFatigueSigmaMultiplier();
        double fatiguedTau = tau * getFatigueTauMultiplier();
        
        // Apply activity-based scaling
        double scale = getActivityScale(activity);
        double scaledMu = fatiguedMu * scale;
        double scaledSigma = fatiguedSigma * scale;
        double scaledTau = fatiguedTau * scale;
        
        // Generate Ex-Gaussian distributed base jitter
        double jitter = randomization.exGaussianRandom(
                scaledMu, scaledSigma, scaledTau,
                MIN_JITTER_MS, MAX_JITTER_MS);
        
        // === Tick Skip Check ===
        // Occasionally skip 1-2 game ticks (600-1200ms additional delay)
        double tickSkipProb = getEffectiveTickSkipProbability(activity);
        if (randomization.chance(tickSkipProb)) {
            // 70% chance of 1 tick, 30% chance of 2 ticks
            int ticksToSkip = randomization.chance(0.70) ? 1 : 2;
            long skipDelay = ticksToSkip * GAME_TICK_MS;
            jitter += skipDelay;
        }
        
        // === Attention Lapse Check ===
        // Rare but significant delays where player zones out
        double lapseProb = getEffectiveAttentionLapseProbability(activity);
        if (randomization.chance(lapseProb)) {
            long lapseDelay = randomization.uniformRandomLong(
                    ATTENTION_LAPSE_MIN_MS, ATTENTION_LAPSE_MAX_MS);
            jitter += lapseDelay;
        }
        
        // Clamp to max (should rarely hit this with new parameters)
        jitter = Math.min(jitter, MAX_JITTER_MS);
        
        lastJitterMs = Math.round(jitter);
        
        // Only log long delays to reduce noise
        if (lastJitterMs >= LOG_THRESHOLD_MS) {
            log.debug("Long jitter: {}ms (activity={}, fatigue={:.2f}, tickSkipProb={:.3f})",
                    lastJitterMs, activity, fatigueLevel, tickSkipProb);
        }
        
        return lastJitterMs;
    }
    
    /**
     * Calculate jitter with anticipation support.
     * When waiting for predictable events (inventory full, ore depleted),
     * player may react faster than normal.
     * 
     * @param activity the current activity type
     * @param predictableEvent true if this is a predictable event
     * @return jitter delay in milliseconds
     */
    public long calculateJitterWithAnticipation(ActivityType activity, boolean predictableEvent) {
        if (!enabled) {
            return 0;
        }
        
        // Check for anticipation (faster-than-normal reaction)
        if (predictableEvent) {
            double anticipationProb = getAnticipationProbability();
            if (randomization.chance(anticipationProb)) {
                // Player was ready - fast reaction
                lastJitterMs = randomization.uniformRandomLong(
                        ANTICIPATION_MIN_MS, ANTICIPATION_MAX_MS);
                log.debug("Anticipation triggered: {}ms", lastJitterMs);
                return lastJitterMs;
            }
        }
        
        // Normal jitter calculation
        return calculateJitter(activity);
    }

    /**
     * Calculate jitter for emergency situations (minimal delay).
     * Used for combat emergencies where immediate response is critical.
     * 
     * @return minimal jitter delay (10-20ms)
     */
    public long calculateEmergencyJitter() {
        if (!enabled) {
            return 0;
        }
        
        // Minimal jitter for emergencies - just enough to not be exactly on tick
        lastJitterMs = randomization.uniformRandomLong(10, 20);
        return lastJitterMs;
    }

    /**
     * Schedule a task to execute after jitter delay.
     * 
     * @param task the task to execute
     * @param activity the current activity type (for jitter scaling)
     * @return true if scheduled, false if jitter already pending
     */
    public boolean scheduleJitteredExecution(Runnable task, ActivityType activity) {
        long jitterMs = calculateJitter(activity);
        return scheduleWithDelay(task, jitterMs, "jittered (activity=" + activity + ")");
    }

    /**
     * Schedule a task with emergency (minimal) jitter.
     * 
     * @param task the task to execute
     * @return true if scheduled, false if jitter already pending
     */
    public boolean scheduleEmergencyExecution(Runnable task) {
        long jitterMs = calculateEmergencyJitter();
        return scheduleWithDelay(task, jitterMs, "emergency");
    }

    /**
     * Internal method to schedule a task with the given delay.
     * Prevents double-scheduling within the same tick.
     */
    private boolean scheduleWithDelay(Runnable task, long delayMs, String context) {
        if (!jitterPending.compareAndSet(false, true)) {
            log.trace("Jitter already pending, skipping {} schedule", context);
            return false;
        }
        
        // Only log long delays to reduce noise
        if (delayMs >= LOG_THRESHOLD_MS) {
            log.debug("Scheduling {} execution: {}ms", context, delayMs);
        }
        
        pendingTask = executor.schedule(() -> {
            try {
                task.run();
            } finally {
                jitterPending.set(false);
                pendingTask = null;
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        
        return true;
    }

    /**
     * Execute a task immediately without jitter.
     * Used for truly time-critical operations (abort handling).
     * 
     * @param task the task to execute
     */
    public void executeImmediate(Runnable task) {
        executor.execute(task);
    }

    /**
     * Check if a jittered execution is currently pending.
     * 
     * @return true if pending
     */
    public boolean isJitterPending() {
        return jitterPending.get();
    }

    /**
     * Cancel any pending jittered execution.
     * Used when task needs to be aborted.
     */
    public void cancelPending() {
        ScheduledFuture<?> task = pendingTask;
        if (task != null && !task.isDone()) {
            task.cancel(false);
            log.debug("Cancelled pending jittered execution");
        }
        jitterPending.set(false);
        pendingTask = null;
    }

    /**
     * Reset jitter state. Called on session end.
     */
    public void reset() {
        cancelPending();
        lastJitterMs = 0;
    }

    /**
     * Shutdown the executor. Called on plugin shutdown.
     */
    public void shutdown() {
        cancelPending();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ========================================================================
    // Internal Methods
    // ========================================================================

    /**
     * Get the activity-based jitter scale factor.
     */
    private double getActivityScale(ActivityType activity) {
        return switch (activity) {
            case CRITICAL -> CRITICAL_SCALE;
            case HIGH -> HIGH_SCALE;
            case MEDIUM -> MEDIUM_SCALE;
            case LOW, AFK_COMBAT -> LOW_SCALE;
            case IDLE -> IDLE_SCALE;
        };
    }

    /**
     * Get jitter μ (mean) from performance state (includes daily and circadian modulation).
     */
    private double getJitterMu() {
        return performanceState.getEffectiveJitterMu();
    }

    /**
     * Get jitter σ (std dev) from performance state (includes daily and circadian modulation).
     */
    private double getJitterSigma() {
        return performanceState.getEffectiveJitterSigma();
    }

    /**
     * Get jitter τ (tail) from performance state (includes daily and circadian modulation).
     */
    private double getJitterTau() {
        return performanceState.getEffectiveJitterTau();
    }

    // ========================================================================
    // Tick Skip / Attention / Anticipation Methods
    // ========================================================================

    /**
     * Get the activity-based tick skip scale factor.
     * Different from jitter scaling - this affects tick skip probability.
     */
    private double getActivitySkipScale(ActivityType activity) {
        return switch (activity) {
            case CRITICAL -> CRITICAL_SKIP_SCALE;
            case HIGH -> HIGH_SKIP_SCALE;
            case MEDIUM -> MEDIUM_SKIP_SCALE;
            case LOW -> LOW_SKIP_SCALE;
            case AFK_COMBAT -> AFK_COMBAT_SKIP_SCALE;
            case IDLE -> IDLE_SKIP_SCALE;
        };
    }

    /**
     * Get the effective tick skip probability, accounting for:
     * - Profile base probability
     * - Activity type scaling
     * - Fatigue level
     * - Task repetitiveness
     * 
     * @param activity the current activity type
     * @return effective probability (0.0-1.0)
     */
    private double getEffectiveTickSkipProbability(ActivityType activity) {
        // Base probability from profile
        double baseProb = getTickSkipBaseProbability();
        
        // Activity scaling
        double activityScale = getActivitySkipScale(activity);
        
        // Fatigue increases tick skip probability
        // At max fatigue, probability is 2.5x base
        double fatigueLevel = getFatigueLevel();
        double fatigueMultiplier = 1.0 + fatigueLevel * 1.5;
        
        // Repetitiveness increases tick skip probability
        // Doing the same task for 100+ ticks doubles the probability
        double repetitivenessMultiplier = getRepetitivenessMultiplier();
        
        // Combine all factors
        double effectiveProb = baseProb * activityScale * fatigueMultiplier * repetitivenessMultiplier;
        
        // Cap at 25% to avoid excessive tick skipping
        return Math.min(0.25, effectiveProb);
    }

    /**
     * Get the effective attention lapse probability, accounting for:
     * - Profile base probability
     * - Activity type (critical = almost never)
     * - Fatigue level (tired = more lapses)
     * - Task repetitiveness
     * 
     * @param activity the current activity type
     * @return effective probability (0.0-1.0)
     */
    private double getEffectiveAttentionLapseProbability(ActivityType activity) {
        // No attention lapses during critical activities
        if (activity == ActivityType.CRITICAL) {
            return 0.0;
        }
        
        // Very rare during high activities
        if (activity == ActivityType.HIGH) {
            return getAttentionLapseProbability() * 0.1;
        }
        
        // Base probability from profile
        double baseProb = getAttentionLapseProbability();
        
        // Fatigue significantly increases lapse probability
        // At max fatigue, probability is 4x base
        double fatigueLevel = getFatigueLevel();
        double fatigueMultiplier = 1.0 + fatigueLevel * 3.0;
        
        // Repetitiveness increases lapse probability
        double repetitivenessMultiplier = getRepetitivenessMultiplier();
        
        // Activity-based boost for AFK/IDLE
        double activityMultiplier = 1.0;
        if (activity == ActivityType.AFK_COMBAT || activity == ActivityType.IDLE) {
            activityMultiplier = 2.0;
        } else if (activity == ActivityType.LOW) {
            activityMultiplier = 1.5;
        }
        
        // Combine all factors
        double effectiveProb = baseProb * fatigueMultiplier * repetitivenessMultiplier * activityMultiplier;
        
        // Cap at 8% to avoid excessive lapses
        return Math.min(0.08, effectiveProb);
    }

    /**
     * Get base tick skip probability from profile.
     */
    private double getTickSkipBaseProbability() {
        return playerProfile.getTickSkipBaseProbability();
    }

    /**
     * Get base attention lapse probability from profile.
     */
    private double getAttentionLapseProbability() {
        return playerProfile.getAttentionLapseProbability();
    }

    /**
     * Get anticipation probability from profile.
     */
    private double getAnticipationProbability() {
        return playerProfile.getAnticipationProbability();
    }

    // ========================================================================
    // Fatigue Integration
    // ========================================================================

    /**
     * Get current fatigue level (0.0-1.0).
     */
    private double getFatigueLevel() {
        return fatigueModel.getFatigueLevel();
    }

    /**
     * Get fatigue-based sigma multiplier for Ex-Gaussian distribution.
     */
    private double getFatigueSigmaMultiplier() {
        return fatigueModel.getSigmaMultiplier();
    }

    /**
     * Get fatigue-based tau multiplier for Ex-Gaussian distribution.
     */
    private double getFatigueTauMultiplier() {
        return fatigueModel.getTauMultiplier();
    }

    /**
     * Get repetitiveness multiplier from activity tracker.
     */
    private double getRepetitivenessMultiplier() {
        return activityTracker.getRepetitivenessMultiplier();
    }
}
