package com.rocinante.timing;

import com.rocinante.behavior.AttentionModel;
import com.rocinante.behavior.FatigueModel;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.*;

/**
 * Human-like delay generation as specified in REQUIREMENTS.md Section 4.1.
 *
 * Provides delays based on statistical distributions to simulate human reaction times,
 * action gaps, and various gameplay interactions. Supports:
 * - Named delay profiles (REACTION, ACTION_GAP, MENU_SELECT, etc.)
 * - Custom delays with configurable distributions
 * - Fatigue multiplier integration via FatigueModel
 * - Async sleep methods for non-blocking delays
 *
 * All delays are multiplied by the fatigue multiplier before being returned.
 * FatigueModel updates the multiplier based on session fatigue and attention state.
 */
@Slf4j
@Singleton
public class HumanTimer {

    private final Randomization randomization;
    private final ScheduledExecutorService executor;

    /**
     * Legacy fatigue multiplier - deprecated, use FatigueModel instead.
     * Kept for backwards compatibility.
     * Default: 1.0 (no fatigue effect)
     */
    @Getter
    @Setter
    @Deprecated
    private volatile double fatigueMultiplier = 1.0;

    /**
     * FatigueModel for dynamic fatigue effects.
     * When set, fatigue multiplier is obtained from this model.
     */
    @Setter
    @Nullable
    private FatigueModel fatigueModel;

    /**
     * AttentionModel for attention state effects.
     * When set, attention multiplier is obtained from this model.
     */
    @Setter
    @Nullable
    private AttentionModel attentionModel;

    @Inject
    public HumanTimer(Randomization randomization) {
        this.randomization = randomization;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HumanTimer");
            t.setDaemon(true);
            return t;
        });
        log.info("HumanTimer initialized");
    }

    /**
     * Constructor for testing with custom randomization.
     *
     * @param randomization the randomization instance
     * @param executor      the executor service for async operations
     */
    HumanTimer(Randomization randomization, ScheduledExecutorService executor) {
        this.randomization = randomization;
        this.executor = executor;
    }

    /**
     * Constructor for testing with full model injection.
     */
    HumanTimer(Randomization randomization, ScheduledExecutorService executor,
               @Nullable FatigueModel fatigueModel, @Nullable AttentionModel attentionModel) {
        this.randomization = randomization;
        this.executor = executor;
        this.fatigueModel = fatigueModel;
        this.attentionModel = attentionModel;
    }

    // ========================================================================
    // Named Profile Delays
    // ========================================================================

    /**
     * Get a delay in milliseconds for a named delay profile.
     *
     * @param profile the delay profile to use
     * @return the delay in milliseconds, adjusted by fatigue multiplier
     */
    public long getDelay(DelayProfile profile) {
        long baseDelay = calculateBaseDelay(profile, profile.getMean());
        return applyFatigue(baseDelay);
    }

    /**
     * Get a delay for DIALOGUE_READ profile with word count adjustment.
     * Total delay = base (1200ms) + (wordCount * 50ms/word) + Gaussian variance
     *
     * @param wordCount the number of words in the dialogue
     * @return the delay in milliseconds, adjusted by fatigue multiplier
     */
    public long getDialogueDelay(int wordCount) {
        if (wordCount < 0) {
            wordCount = 0;
        }
        double adjustedMean = DelayProfile.DIALOGUE_READ.getAdjustedMeanForDialogue(wordCount);
        long baseDelay = calculateBaseDelay(DelayProfile.DIALOGUE_READ, adjustedMean);
        return applyFatigue(baseDelay);
    }

    // ========================================================================
    // Contextual Reaction Times
    // ========================================================================

    /**
     * Reaction context - determines which reaction profile to use.
     * Different contexts produce different reaction time distributions.
     */
    public enum ReactionContext {
        /**
         * Expected event - you were waiting for this to happen.
         * Examples: dialogue continues, bank opens after clicking, animation ends.
         * Fastest reaction because attention is already focused.
         */
        EXPECTED,
        
        /**
         * Unexpected event - this surprised you.
         * Examples: random event, PKer appears, NPC despawns, error message.
         * Slower because you need to notice and process what happened.
         */
        UNEXPECTED,
        
        /**
         * Complex event - you need to think before acting.
         * Examples: choosing an item, selecting from menu, making a decision.
         * Slowest because cognitive processing is required.
         */
        COMPLEX
    }

    /**
     * Get a contextual reaction delay based on what kind of event occurred.
     * Uses the appropriate REACTION_* profile based on context.
     *
     * @param context the reaction context
     * @return reaction delay in milliseconds, adjusted by fatigue
     */
    public long getContextualReaction(ReactionContext context) {
        DelayProfile profile = switch (context) {
            case EXPECTED -> DelayProfile.REACTION_EXPECTED;
            case UNEXPECTED -> DelayProfile.REACTION_UNEXPECTED;
            case COMPLEX -> DelayProfile.REACTION_COMPLEX;
        };
        return getDelay(profile);
    }

    /**
     * Sleep for a contextual reaction time.
     *
     * @param context the reaction context
     * @return CompletableFuture that completes after the reaction delay
     */
    public CompletableFuture<Void> sleepContextual(ReactionContext context) {
        return sleep(getContextualReaction(context));
    }

    /**
     * Sleep synchronously for a contextual reaction time.
     *
     * @param context the reaction context
     * @throws InterruptedException if the sleep is interrupted
     */
    public void sleepContextualSync(ReactionContext context) throws InterruptedException {
        sleepSync(getContextualReaction(context));
    }

    // ========================================================================
    // Custom Delays
    // ========================================================================

    /**
     * Get a custom delay using the specified distribution type.
     * No bounds applied beyond natural distribution characteristics.
     *
     * @param type   the distribution type
     * @param mean   the mean (or lambda for Poisson/Exponential)
     * @param stdDev the standard deviation (for Gaussian only)
     * @return the delay in milliseconds, adjusted by fatigue multiplier
     */
    public long getCustomDelay(DistributionType type, double mean, double stdDev) {
        long baseDelay = calculateFromDistribution(type, mean, stdDev, null, null);
        return applyFatigue(baseDelay);
    }

    /**
     * Get a custom delay using the specified distribution type with bounds.
     *
     * @param type   the distribution type
     * @param mean   the mean (or lambda for Poisson/Exponential)
     * @param stdDev the standard deviation (for Gaussian only)
     * @param min    minimum bound (inclusive), null for no minimum
     * @param max    maximum bound (inclusive), null for no maximum
     * @return the delay in milliseconds, adjusted by fatigue multiplier
     */
    public long getCustomDelay(DistributionType type, double mean, double stdDev, Long min, Long max) {
        long baseDelay = calculateFromDistribution(type, mean, stdDev, min, max);
        return applyFatigue(baseDelay);
    }

    /**
     * Get a uniform random delay within the specified range.
     * Convenience method for simple bounded randomization.
     *
     * @param min minimum delay in milliseconds (inclusive)
     * @param max maximum delay in milliseconds (inclusive)
     * @return the delay in milliseconds, adjusted by fatigue multiplier
     */
    public long getUniformDelay(long min, long max) {
        long baseDelay = randomization.uniformRandomLong(min, max);
        return applyFatigue(baseDelay);
    }

    /**
     * Get a Gaussian delay with the specified parameters.
     * Convenience method for common use case.
     *
     * @param mean   the mean delay in milliseconds
     * @param stdDev the standard deviation in milliseconds
     * @return the delay in milliseconds, adjusted by fatigue multiplier
     */
    public long getGaussianDelay(double mean, double stdDev) {
        long baseDelay = Math.round(randomization.gaussianRandom(mean, stdDev));
        baseDelay = Math.max(0, baseDelay); // Ensure non-negative
        return applyFatigue(baseDelay);
    }

    /**
     * Get a Gaussian delay with the specified parameters and bounds.
     *
     * @param mean   the mean delay in milliseconds
     * @param stdDev the standard deviation in milliseconds
     * @param min    minimum bound in milliseconds
     * @param max    maximum bound in milliseconds
     * @return the delay in milliseconds, adjusted by fatigue multiplier
     */
    public long getGaussianDelay(double mean, double stdDev, long min, long max) {
        long baseDelay = randomization.gaussianRandomLong(mean, stdDev, min, max);
        return applyFatigue(baseDelay);
    }

    /**
     * Get a Poisson delay with the specified lambda.
     * Used primarily for reaction times.
     *
     * @param lambda the rate parameter (expected value)
     * @return the delay in milliseconds, adjusted by fatigue multiplier
     */
    public long getPoissonDelay(double lambda) {
        long baseDelay = randomization.poissonRandom(lambda);
        return applyFatigue(baseDelay);
    }

    /**
     * Get a Poisson delay with the specified lambda and bounds.
     *
     * @param lambda the rate parameter (expected value)
     * @param min    minimum bound in milliseconds
     * @param max    maximum bound in milliseconds
     * @return the delay in milliseconds, adjusted by fatigue multiplier
     */
    public long getPoissonDelay(double lambda, long min, long max) {
        long baseDelay = randomization.poissonRandomLong(lambda, min, max);
        return applyFatigue(baseDelay);
    }

    /**
     * Get an exponential delay with the specified lambda.
     * Used for break durations and rare events.
     *
     * @param lambda the rate parameter (1/mean)
     * @return the delay in milliseconds, adjusted by fatigue multiplier
     */
    public long getExponentialDelay(double lambda) {
        long baseDelay = Math.round(randomization.exponentialRandom(lambda));
        return applyFatigue(baseDelay);
    }

    /**
     * Get an exponential delay with the specified lambda and bounds.
     *
     * @param lambda the rate parameter (1/mean)
     * @param min    minimum bound in milliseconds
     * @param max    maximum bound in milliseconds
     * @return the delay in milliseconds, adjusted by fatigue multiplier
     */
    public long getExponentialDelay(double lambda, long min, long max) {
        long baseDelay = randomization.exponentialRandomLong(lambda, min, max);
        return applyFatigue(baseDelay);
    }

    // ========================================================================
    // Async Sleep Methods
    // ========================================================================

    /**
     * Sleep for the specified delay profile.
     *
     * @param profile the delay profile to use
     * @return CompletableFuture that completes after the delay
     */
    public CompletableFuture<Void> sleep(DelayProfile profile) {
        long delay = getDelay(profile);
        return sleep(delay);
    }

    /**
     * Sleep for a dialogue reading delay with word count.
     *
     * @param wordCount the number of words in the dialogue
     * @return CompletableFuture that completes after the delay
     */
    public CompletableFuture<Void> sleepDialogue(int wordCount) {
        long delay = getDialogueDelay(wordCount);
        return sleep(delay);
    }

    /**
     * Sleep for the specified duration in milliseconds.
     *
     * @param millis the duration to sleep in milliseconds
     * @return CompletableFuture that completes after the delay
     */
    public CompletableFuture<Void> sleep(long millis) {
        if (millis <= 0) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.schedule(() -> future.complete(null), millis, TimeUnit.MILLISECONDS);
        return future;
    }

    /**
     * Sleep synchronously for the specified delay profile.
     * Blocks the current thread.
     *
     * @param profile the delay profile to use
     * @throws InterruptedException if the sleep is interrupted
     */
    public void sleepSync(DelayProfile profile) throws InterruptedException {
        long delay = getDelay(profile);
        sleepSync(delay);
    }

    /**
     * Sleep synchronously for the specified duration.
     * Blocks the current thread.
     *
     * @param millis the duration to sleep in milliseconds
     * @throws InterruptedException if the sleep is interrupted
     */
    public void sleepSync(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    // ========================================================================
    // Internal Calculation Methods
    // ========================================================================

    /**
     * Calculate the base delay for a profile with a given mean.
     */
    private long calculateBaseDelay(DelayProfile profile, double mean) {
        return calculateFromDistribution(
                profile.getDistributionType(),
                mean,
                profile.getStdDev(),
                profile.getTau(),
                profile.getMin(),
                profile.getMax()
        );
    }

    /**
     * Calculate a delay value from the specified distribution.
     * 
     * @param type   the distribution type
     * @param mean   the mean (μ for Gaussian/Ex-Gaussian, λ for Poisson/Exponential)
     * @param stdDev the standard deviation (for Gaussian/Ex-Gaussian)
     * @param tau    the exponential tail parameter (for Ex-Gaussian only)
     * @param min    minimum bound
     * @param max    maximum bound
     * @return the calculated delay value
     */
    private long calculateFromDistribution(DistributionType type, double mean, double stdDev,
                                           double tau, Long min, Long max) {
        double rawValue;

        switch (type) {
            case GAUSSIAN:
                rawValue = randomization.gaussianRandom(mean, stdDev);
                break;

            case POISSON:
                rawValue = randomization.poissonRandom(mean);
                break;

            case UNIFORM:
                // For uniform, mean is the midpoint; use min/max if provided
                long uniformMin = min != null ? min : Math.round(mean * 0.5);
                long uniformMax = max != null ? max : Math.round(mean * 1.5);
                rawValue = randomization.uniformRandomLong(uniformMin, uniformMax);
                break;

            case EXPONENTIAL:
                // For exponential, mean is used as 1/lambda (so lambda = 1/mean)
                double lambda = mean > 0 ? 1.0 / mean : 1.0;
                rawValue = randomization.exponentialRandom(lambda);
                break;

            case EX_GAUSSIAN:
                // Ex-Gaussian: convolution of Gaussian(μ, σ) and Exponential(τ)
                // Creates right-skewed distribution matching human reaction times
                // Mean of distribution = μ + τ
                rawValue = randomization.exGaussianRandom(mean, stdDev, tau);
                break;

            default:
                rawValue = mean;
        }

        // Ensure non-negative
        rawValue = Math.max(0, rawValue);

        // Apply bounds if specified
        if (min != null) {
            rawValue = Math.max(min, rawValue);
        }
        if (max != null) {
            rawValue = Math.min(max, rawValue);
        }

        return Math.round(rawValue);
    }

    /**
     * Calculate a delay value from the specified distribution (legacy overload without tau).
     * For backward compatibility with getCustomDelay methods.
     */
    private long calculateFromDistribution(DistributionType type, double mean, double stdDev,
                                           Long min, Long max) {
        // Default tau to 0 (no effect for non-Ex-Gaussian distributions)
        return calculateFromDistribution(type, mean, stdDev, 0.0, min, max);
    }

    /**
     * Apply fatigue and attention modifiers to a base delay.
     * 
     * Order of application:
     * 1. Fatigue multiplier (from FatigueModel or legacy field)
     * 2. Attention multiplier (from AttentionModel)
     * 
     * Combined multiplier: baseDelay * fatigueMult * attentionMult
     */
    private long applyModifiers(long baseDelay) {
        double effectiveFatigueMultiplier = getEffectiveFatigueMultiplier();
        double effectiveAttentionMultiplier = getEffectiveAttentionMultiplier();
        
        double combinedMultiplier = effectiveFatigueMultiplier * effectiveAttentionMultiplier;
        
        if (combinedMultiplier <= 0) {
            log.warn("Invalid combined multiplier: {}, using 1.0", combinedMultiplier);
            return baseDelay;
        }
        
        return Math.round(baseDelay * combinedMultiplier);
    }

    /**
     * Get the effective fatigue multiplier.
     * Uses FatigueModel if available, otherwise falls back to legacy field.
     */
    private double getEffectiveFatigueMultiplier() {
        if (fatigueModel != null) {
            return fatigueModel.getDelayMultiplier();
        }
        return fatigueMultiplier;
    }

    /**
     * Get the effective attention multiplier.
     * Uses AttentionModel if available, otherwise returns 1.0.
     */
    private double getEffectiveAttentionMultiplier() {
        if (attentionModel != null) {
            return attentionModel.getDelayMultiplier();
        }
        return 1.0;
    }

    /**
     * Apply fatigue multiplier to a base delay.
     * @deprecated Use applyModifiers instead for full behavioral support
     */
    @Deprecated
    private long applyFatigue(long baseDelay) {
        return applyModifiers(baseDelay);
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Shutdown the timer's executor service.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if a probability event should occur.
     * Convenience method delegating to Randomization.
     *
     * @param probability the probability (0.0 to 1.0)
     * @return true if the event should occur
     */
    public boolean chance(double probability) {
        return randomization.chance(probability);
    }

    /**
     * Reset fatigue multiplier to default (1.0).
     * @deprecated Use FatigueModel.reset() instead for full behavioral support
     */
    @Deprecated
    public void resetFatigue() {
        this.fatigueMultiplier = 1.0;
        log.debug("Fatigue multiplier reset to 1.0");
    }

    /**
     * Check if actions can be performed (not in AFK state).
     * 
     * @return true if actions can be taken
     */
    public boolean canAct() {
        if (attentionModel != null) {
            return attentionModel.canAct();
        }
        return true;
    }

    /**
     * Get the current combined delay multiplier for debugging.
     * 
     * @return combined multiplier (fatigue * attention)
     */
    public double getCombinedMultiplier() {
        return getEffectiveFatigueMultiplier() * getEffectiveAttentionMultiplier();
    }

    /**
     * Get event processing lag based on attention state.
     * When distracted, there's extra delay before responding to game events.
     * 
     * @return lag in milliseconds
     */
    public long getEventProcessingLag() {
        if (attentionModel != null) {
            return attentionModel.getEventProcessingLag();
        }
        return 0;
    }
}

