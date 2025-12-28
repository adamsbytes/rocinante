package com.rocinante.behavior;

import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized inefficiency injection system for humanizing bot behavior.
 * 
 * Per REQUIREMENTS.md Section 3.4.4 (Intentional Inefficiency Injection):
 * Perfect efficiency is inhuman. Inject 5-10% suboptimal actions.
 * 
 * Inefficiency Types:
 * - Misclicks: 1-3% miss target, correct after delay (handled in RobotMouseController)
 * - Backtracking: 2% of walks, walk 1-2 tiles past destination, then return
 * - Redundant actions: 3% of bank trips, open/close bank twice before transaction
 * - Hesitation: 5% of actions, hover over target for 500-1500ms before clicking
 * - Action cancellation: 1% of queued actions, cancel and re-queue after 1-3 second pause
 * 
 * This component is consulted by various tasks to determine if inefficiency should be injected.
 */
@Slf4j
@Singleton
public class InefficiencyInjector {

    // === Probability Constants (per REQUIREMENTS.md 3.4.4) ===
    
    /**
     * Base probability of backtracking after walk completion.
     */
    private static final double BASE_BACKTRACK_PROBABILITY = 0.02;
    
    /**
     * Base probability of redundant actions (e.g., opening bank twice).
     */
    private static final double BASE_REDUNDANT_ACTION_PROBABILITY = 0.03;
    
    /**
     * Base probability of hesitation before click.
     */
    private static final double BASE_HESITATION_PROBABILITY = 0.05;
    
    /**
     * Base probability of action cancellation.
     */
    private static final double BASE_ACTION_CANCEL_PROBABILITY = 0.01;
    
    // === Hesitation Delay Constants ===
    
    /**
     * Minimum hesitation delay in milliseconds.
     */
    private static final long MIN_HESITATION_DELAY_MS = 500;
    
    /**
     * Maximum hesitation delay in milliseconds.
     */
    private static final long MAX_HESITATION_DELAY_MS = 1500;
    
    // === Action Cancellation Delay Constants ===
    
    /**
     * Minimum delay before re-queueing cancelled action (ms).
     */
    private static final long MIN_CANCEL_DELAY_MS = 1000;
    
    /**
     * Maximum delay before re-queueing cancelled action (ms).
     */
    private static final long MAX_CANCEL_DELAY_MS = 3000;
    
    // === Backtrack Distance Constants ===
    
    /**
     * Minimum backtrack distance in tiles.
     * Per user specification: 2-10 tiles to simulate "not paying attention."
     */
    private static final int MIN_BACKTRACK_TILES = 2;
    
    /**
     * Maximum backtrack distance in tiles.
     * Per user specification: 2-10 tiles to simulate "not paying attention."
     */
    private static final int MAX_BACKTRACK_TILES = 10;
    
    // === Dependencies ===
    
    private final Randomization randomization;
    
    @Setter
    @Nullable
    private PlayerProfile playerProfile;
    
    @Setter
    @Nullable
    private FatigueModel fatigueModel;
    
    // === State ===
    
    /**
     * Whether inefficiency injection is enabled.
     */
    @Getter
    @Setter
    private boolean enabled = true;
    
    /**
     * Count of injected inefficiencies for metrics.
     */
    private final AtomicLong backtrackCount = new AtomicLong(0);
    private final AtomicLong redundantActionCount = new AtomicLong(0);
    private final AtomicLong hesitationCount = new AtomicLong(0);
    private final AtomicLong actionCancelCount = new AtomicLong(0);
    
    /**
     * Last time each inefficiency type was injected (to prevent clustering).
     */
    private Instant lastBacktrack = Instant.EPOCH;
    private Instant lastRedundantAction = Instant.EPOCH;
    private Instant lastHesitation = Instant.EPOCH;
    private Instant lastActionCancel = Instant.EPOCH;
    
    /**
     * Minimum interval between same-type inefficiencies (prevents clustering).
     */
    private static final Duration MIN_INEFFICIENCY_INTERVAL = Duration.ofSeconds(30);

    @Inject
    public InefficiencyInjector(Randomization randomization) {
        this.randomization = randomization;
        log.info("InefficiencyInjector initialized");
    }

    // ========================================================================
    // Backtracking
    // ========================================================================

    /**
     * Check if backtracking should occur after walk completion.
     * 2% of walks walk 1-2 tiles past destination, then return.
     * 
     * @return true if backtracking should be performed
     */
    public boolean shouldBacktrack() {
        if (!enabled) {
            return false;
        }
        
        // Prevent clustering
        if (Duration.between(lastBacktrack, Instant.now()).compareTo(MIN_INEFFICIENCY_INTERVAL) < 0) {
            return false;
        }
        
        double probability = getAdjustedProbability(BASE_BACKTRACK_PROBABILITY);
        boolean should = randomization.chance(probability);
        
        if (should) {
            lastBacktrack = Instant.now();
            backtrackCount.incrementAndGet();
            log.debug("Backtracking triggered");
        }
        
        return should;
    }

    /**
     * Get the number of tiles to backtrack.
     * 
     * @return number of tiles to walk past destination (1-2)
     */
    public int getBacktrackDistance() {
        return randomization.uniformRandomInt(MIN_BACKTRACK_TILES, MAX_BACKTRACK_TILES);
    }

    // ========================================================================
    // Redundant Actions
    // ========================================================================

    /**
     * Check if a redundant action should be performed.
     * 3% of bank trips open/close bank twice before actual transaction.
     * 
     * @return true if redundant action should be performed
     */
    public boolean shouldPerformRedundantAction() {
        if (!enabled) {
            return false;
        }
        
        // Prevent clustering
        if (Duration.between(lastRedundantAction, Instant.now()).compareTo(MIN_INEFFICIENCY_INTERVAL) < 0) {
            return false;
        }
        
        double probability = getAdjustedProbability(BASE_REDUNDANT_ACTION_PROBABILITY);
        boolean should = randomization.chance(probability);
        
        if (should) {
            lastRedundantAction = Instant.now();
            redundantActionCount.incrementAndGet();
            log.debug("Redundant action triggered");
        }
        
        return should;
    }

    /**
     * Get the number of redundant repetitions (usually 1, for double-action).
     * 
     * @return number of extra times to perform the action
     */
    public int getRedundantRepetitions() {
        // Usually just 1 (double-action), rarely 2 (triple)
        return randomization.chance(0.9) ? 1 : 2;
    }

    // ========================================================================
    // Hesitation
    // ========================================================================

    /**
     * Check if hesitation should occur before a click.
     * 5% of actions hover over target for 500-1500ms before clicking.
     * 
     * @return true if hesitation should be performed
     */
    public boolean shouldHesitate() {
        if (!enabled) {
            return false;
        }
        
        // Prevent clustering - shorter interval for hesitation as it's more common
        Duration hesitationInterval = Duration.ofSeconds(10);
        if (Duration.between(lastHesitation, Instant.now()).compareTo(hesitationInterval) < 0) {
            return false;
        }
        
        double probability = getAdjustedProbability(BASE_HESITATION_PROBABILITY);
        boolean should = randomization.chance(probability);
        
        if (should) {
            lastHesitation = Instant.now();
            hesitationCount.incrementAndGet();
            log.trace("Hesitation triggered");
        }
        
        return should;
    }

    /**
     * Get the duration to hesitate (hover) before clicking.
     * 
     * @return hesitation delay in milliseconds
     */
    public long getHesitationDelay() {
        return randomization.uniformRandomLong(MIN_HESITATION_DELAY_MS, MAX_HESITATION_DELAY_MS);
    }

    /**
     * Get hesitation delay adjusted for fatigue.
     * Fatigued players hesitate longer.
     * 
     * @return hesitation delay in milliseconds, adjusted for fatigue
     */
    public long getAdjustedHesitationDelay() {
        long baseDelay = getHesitationDelay();
        
        if (fatigueModel != null) {
            double fatigueMultiplier = 1.0 + (fatigueModel.getFatigueLevel() * 0.5); // Up to 50% longer when fatigued
            baseDelay = (long) (baseDelay * fatigueMultiplier);
        }
        
        return baseDelay;
    }

    // ========================================================================
    // Action Cancellation
    // ========================================================================

    /**
     * Check if an action should be cancelled and re-queued.
     * 1% of queued actions, cancel and re-queue after 1-3 second pause.
     * 
     * @return true if action should be cancelled
     */
    public boolean shouldCancelAction() {
        if (!enabled) {
            return false;
        }
        
        // Prevent clustering
        if (Duration.between(lastActionCancel, Instant.now()).compareTo(MIN_INEFFICIENCY_INTERVAL) < 0) {
            return false;
        }
        
        double probability = getAdjustedProbability(BASE_ACTION_CANCEL_PROBABILITY);
        boolean should = randomization.chance(probability);
        
        if (should) {
            lastActionCancel = Instant.now();
            actionCancelCount.incrementAndGet();
            log.debug("Action cancellation triggered");
        }
        
        return should;
    }

    /**
     * Get the delay before re-queueing a cancelled action.
     * 
     * @return delay in milliseconds
     */
    public long getCancellationDelay() {
        return randomization.uniformRandomLong(MIN_CANCEL_DELAY_MS, MAX_CANCEL_DELAY_MS);
    }

    // ========================================================================
    // Probability Adjustment
    // ========================================================================

    /**
     * Adjust inefficiency probability based on fatigue and profile.
     * Higher fatigue = more inefficiencies.
     * 
     * @param baseProbability the base probability
     * @return adjusted probability
     */
    private double getAdjustedProbability(double baseProbability) {
        double adjusted = baseProbability;
        
        // Fatigue increases inefficiency (makes sense - tired people make more mistakes)
        if (fatigueModel != null) {
            double fatigueLevel = fatigueModel.getFatigueLevel();
            // Up to 2x more inefficiencies at max fatigue
            adjusted *= (1.0 + fatigueLevel);
        }
        
        // Profile-based adjustment could go here
        // Some players might be naturally more "sloppy"
        
        return Math.min(adjusted, 0.3); // Cap at 30% to prevent excessive inefficiency
    }

    // ========================================================================
    // Combined Checks
    // ========================================================================

    /**
     * Result of inefficiency check with type and parameters.
     */
    public static class InefficiencyResult {
        @Getter
        private final InefficiencyType type;
        @Getter
        private final long delayMs;
        @Getter
        private final int amount;
        
        public InefficiencyResult(InefficiencyType type, long delayMs, int amount) {
            this.type = type;
            this.delayMs = delayMs;
            this.amount = amount;
        }
        
        public static InefficiencyResult none() {
            return new InefficiencyResult(InefficiencyType.NONE, 0, 0);
        }
        
        public boolean isPresent() {
            return type != InefficiencyType.NONE;
        }
    }

    /**
     * Types of inefficiency that can be injected.
     */
    public enum InefficiencyType {
        NONE,
        BACKTRACK,
        REDUNDANT_ACTION,
        HESITATION,
        ACTION_CANCEL
    }

    /**
     * Check if any pre-click inefficiency should be applied.
     * Used before performing a click action.
     * 
     * @return InefficiencyResult with type and parameters
     */
    public InefficiencyResult checkPreClickInefficiency() {
        if (!enabled) {
            return InefficiencyResult.none();
        }
        
        // Check hesitation first (most common)
        if (shouldHesitate()) {
            return new InefficiencyResult(
                InefficiencyType.HESITATION,
                getAdjustedHesitationDelay(),
                0
            );
        }
        
        // Check action cancellation
        if (shouldCancelAction()) {
            return new InefficiencyResult(
                InefficiencyType.ACTION_CANCEL,
                getCancellationDelay(),
                0
            );
        }
        
        return InefficiencyResult.none();
    }

    /**
     * Check if any post-walk inefficiency should be applied.
     * Used after completing a walk action.
     * 
     * @return InefficiencyResult with type and parameters
     */
    public InefficiencyResult checkPostWalkInefficiency() {
        if (!enabled) {
            return InefficiencyResult.none();
        }
        
        if (shouldBacktrack()) {
            return new InefficiencyResult(
                InefficiencyType.BACKTRACK,
                0,
                getBacktrackDistance()
            );
        }
        
        return InefficiencyResult.none();
    }

    /**
     * Check if any bank-related inefficiency should be applied.
     * Used during bank interactions.
     * 
     * @return InefficiencyResult with type and parameters
     */
    public InefficiencyResult checkBankInefficiency() {
        if (!enabled) {
            return InefficiencyResult.none();
        }
        
        if (shouldPerformRedundantAction()) {
            return new InefficiencyResult(
                InefficiencyType.REDUNDANT_ACTION,
                0,
                getRedundantRepetitions()
            );
        }
        
        return InefficiencyResult.none();
    }

    // ========================================================================
    // Metrics
    // ========================================================================

    /**
     * Get count of backtracking events.
     */
    public long getBacktrackCount() {
        return backtrackCount.get();
    }

    /**
     * Get count of redundant action events.
     */
    public long getRedundantActionCount() {
        return redundantActionCount.get();
    }

    /**
     * Get count of hesitation events.
     */
    public long getHesitationCount() {
        return hesitationCount.get();
    }

    /**
     * Get count of action cancellation events.
     */
    public long getActionCancelCount() {
        return actionCancelCount.get();
    }

    /**
     * Get total count of all inefficiency events.
     */
    public long getTotalInefficiencyCount() {
        return backtrackCount.get() + redundantActionCount.get() 
             + hesitationCount.get() + actionCancelCount.get();
    }

    /**
     * Reset all counters. Called on session start.
     */
    public void resetCounters() {
        backtrackCount.set(0);
        redundantActionCount.set(0);
        hesitationCount.set(0);
        actionCancelCount.set(0);
        lastBacktrack = Instant.EPOCH;
        lastRedundantAction = Instant.EPOCH;
        lastHesitation = Instant.EPOCH;
        lastActionCancel = Instant.EPOCH;
    }
}

