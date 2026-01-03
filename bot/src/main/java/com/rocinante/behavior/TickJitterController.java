package com.rocinante.behavior;

import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
    // Default Jitter Parameters (Ex-Gaussian)
    // ========================================================================
    
    /**
     * Default Ex-Gaussian μ (Gaussian mean) - base perception delay.
     */
    private static final double DEFAULT_JITTER_MU = 40.0;
    
    /**
     * Default Ex-Gaussian σ (Gaussian std dev) - consistency.
     */
    private static final double DEFAULT_JITTER_SIGMA = 15.0;
    
    /**
     * Default Ex-Gaussian τ (exponential mean) - occasional longer delays.
     */
    private static final double DEFAULT_JITTER_TAU = 20.0;
    
    /**
     * Minimum jitter to avoid 0ms (still detectable).
     */
    private static final long MIN_JITTER_MS = 15;
    
    /**
     * Maximum jitter to avoid excessive delay.
     */
    private static final long MAX_JITTER_MS = 150;
    
    // ========================================================================
    // Activity Scaling Factors
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
    // Dependencies
    // ========================================================================
    
    private final Randomization randomization;
    private final ScheduledExecutorService executor;
    
    /**
     * PlayerProfile for per-account jitter characteristics.
     * If null, uses default parameters.
     */
    @Setter
    @Nullable
    private PlayerProfile playerProfile;

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
    public TickJitterController(Randomization randomization) {
        this.randomization = randomization;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TickJitter");
            t.setDaemon(true);
            return t;
        });
        log.info("TickJitterController initialized");
    }

    /**
     * Constructor for testing with custom executor.
     */
    public TickJitterController(Randomization randomization, ScheduledExecutorService executor) {
        this.randomization = randomization;
        this.executor = executor;
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Calculate jitter delay for the given activity type.
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
        
        // Apply activity-based scaling
        double scale = getActivityScale(activity);
        double scaledMu = mu * scale;
        double scaledSigma = sigma * scale;
        double scaledTau = tau * scale;
        
        // Generate Ex-Gaussian distributed jitter
        double jitter = randomization.exGaussianRandom(
                scaledMu, scaledSigma, scaledTau,
                MIN_JITTER_MS, MAX_JITTER_MS);
        
        lastJitterMs = Math.round(jitter);
        return lastJitterMs;
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
        
        log.debug("Scheduling {} execution: {}ms", context, delayMs);
        
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
        if (activity == null) {
            return MEDIUM_SCALE;
        }
        
        return switch (activity) {
            case CRITICAL -> CRITICAL_SCALE;
            case HIGH -> HIGH_SCALE;
            case MEDIUM -> MEDIUM_SCALE;
            case LOW, AFK_COMBAT -> LOW_SCALE;
            case IDLE -> IDLE_SCALE;
        };
    }

    /**
     * Get jitter μ (mean) from profile or default.
     */
    private double getJitterMu() {
        if (playerProfile != null) {
            return playerProfile.getJitterMu();
        }
        return DEFAULT_JITTER_MU;
    }

    /**
     * Get jitter σ (std dev) from profile or default.
     */
    private double getJitterSigma() {
        if (playerProfile != null) {
            return playerProfile.getJitterSigma();
        }
        return DEFAULT_JITTER_SIGMA;
    }

    /**
     * Get jitter τ (tail) from profile or default.
     */
    private double getJitterTau() {
        if (playerProfile != null) {
            return playerProfile.getJitterTau();
        }
        return DEFAULT_JITTER_TAU;
    }
}
