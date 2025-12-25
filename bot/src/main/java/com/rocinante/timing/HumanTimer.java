package com.rocinante.timing;

import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

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
 * - Fatigue multiplier integration (for FatigueModel in Phase 5)
 * - Async sleep methods for non-blocking delays
 *
 * All delays are multiplied by the fatigue multiplier before being returned.
 * FatigueModel (Phase 5) will update the multiplier based on session fatigue.
 */
@Slf4j
@Singleton
public class HumanTimer {

    private final Randomization randomization;
    private final ScheduledExecutorService executor;

    /**
     * Fatigue multiplier applied to all delays.
     * Default: 1.0 (no fatigue effect)
     * Updated by FatigueModel based on fatigue level.
     * Per REQUIREMENTS 4.2.2: delay multiplier = 1.0 + (fatigueLevel * 0.5)
     */
    @Getter
    @Setter
    private volatile double fatigueMultiplier = 1.0;

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
                profile.getMin(),
                profile.getMax()
        );
    }

    /**
     * Calculate a delay value from the specified distribution.
     */
    private long calculateFromDistribution(DistributionType type, double mean, double stdDev,
                                           Long min, Long max) {
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
     * Apply fatigue multiplier to a base delay.
     */
    private long applyFatigue(long baseDelay) {
        if (fatigueMultiplier <= 0) {
            log.warn("Invalid fatigue multiplier: {}, using 1.0", fatigueMultiplier);
            return baseDelay;
        }
        return Math.round(baseDelay * fatigueMultiplier);
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
     */
    public void resetFatigue() {
        this.fatigueMultiplier = 1.0;
        log.debug("Fatigue multiplier reset to 1.0");
    }
}

