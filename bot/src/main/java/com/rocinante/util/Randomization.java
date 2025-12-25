package com.rocinante.util;

import javax.inject.Singleton;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Statistical distribution utilities for humanized input simulation.
 * Provides Gaussian, Poisson, uniform, and exponential distributions
 * as specified in REQUIREMENTS.md Section 3 and 4.
 */
@Singleton
public class Randomization {

    private final Random random;

    public Randomization() {
        this.random = ThreadLocalRandom.current();
    }

    /**
     * Constructor with seeded random for deterministic testing.
     *
     * @param seed the random seed
     */
    public Randomization(long seed) {
        this.random = new Random(seed);
    }

    // ========================================================================
    // Gaussian (Normal) Distribution
    // ========================================================================

    /**
     * Generate a random value from a Gaussian (normal) distribution.
     *
     * @param mean   the mean (μ) of the distribution
     * @param stdDev the standard deviation (σ) of the distribution
     * @return a random value from N(mean, stdDev²)
     */
    public double gaussianRandom(double mean, double stdDev) {
        return mean + random.nextGaussian() * stdDev;
    }

    /**
     * Generate a bounded random value from a Gaussian distribution.
     * Values are clamped to [min, max] range.
     *
     * @param mean   the mean (μ) of the distribution
     * @param stdDev the standard deviation (σ) of the distribution
     * @param min    the minimum allowed value
     * @param max    the maximum allowed value
     * @return a random value from N(mean, stdDev²) clamped to [min, max]
     */
    public double gaussianRandom(double mean, double stdDev, double min, double max) {
        double value = gaussianRandom(mean, stdDev);
        return clamp(value, min, max);
    }

    /**
     * Generate a bounded integer from a Gaussian distribution.
     *
     * @param mean   the mean (μ) of the distribution
     * @param stdDev the standard deviation (σ) of the distribution
     * @param min    the minimum allowed value
     * @param max    the maximum allowed value
     * @return a random integer from N(mean, stdDev²) clamped to [min, max]
     */
    public int gaussianRandomInt(double mean, double stdDev, int min, int max) {
        return (int) Math.round(gaussianRandom(mean, stdDev, min, max));
    }

    /**
     * Generate a bounded long from a Gaussian distribution.
     * Used for timing delays.
     *
     * @param mean   the mean (μ) of the distribution in milliseconds
     * @param stdDev the standard deviation (σ) in milliseconds
     * @param min    the minimum allowed value in milliseconds
     * @param max    the maximum allowed value in milliseconds
     * @return a random long from N(mean, stdDev²) clamped to [min, max]
     */
    public long gaussianRandomLong(double mean, double stdDev, long min, long max) {
        return Math.round(gaussianRandom(mean, stdDev, min, max));
    }

    // ========================================================================
    // Poisson Distribution
    // ========================================================================

    /**
     * Generate a random value from a Poisson distribution using the inverse transform method.
     * Used for reaction times as specified in REQUIREMENTS.md Section 4.1.
     *
     * @param lambda the rate parameter (λ) - expected value
     * @return a random value from Poisson(λ)
     */
    public int poissonRandom(double lambda) {
        // Knuth algorithm for Poisson distribution
        double L = Math.exp(-lambda);
        int k = 0;
        double p = 1.0;

        do {
            k++;
            p *= random.nextDouble();
        } while (p > L);

        return k - 1;
    }

    /**
     * Generate a bounded random value from a Poisson distribution.
     * Used for REACTION delay profile: λ=250ms, min=150ms, max=600ms.
     *
     * @param lambda the rate parameter (λ)
     * @param min    the minimum allowed value
     * @param max    the maximum allowed value
     * @return a random value from Poisson(λ) clamped to [min, max]
     */
    public int poissonRandom(double lambda, int min, int max) {
        int value = poissonRandom(lambda);
        return (int) clamp(value, min, max);
    }

    /**
     * Generate a bounded long from a Poisson distribution.
     * Used for timing delays.
     *
     * @param lambda the rate parameter (λ) in milliseconds
     * @param min    the minimum allowed value in milliseconds
     * @param max    the maximum allowed value in milliseconds
     * @return a random long from Poisson(λ) clamped to [min, max]
     */
    public long poissonRandomLong(double lambda, long min, long max) {
        int value = poissonRandom(lambda);
        return (long) clamp(value, min, max);
    }

    // ========================================================================
    // Uniform Distribution
    // ========================================================================

    /**
     * Generate a random double uniformly distributed in [min, max).
     *
     * @param min the minimum value (inclusive)
     * @param max the maximum value (exclusive)
     * @return a random double in [min, max)
     */
    public double uniformRandom(double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    /**
     * Generate a random integer uniformly distributed in [min, max].
     *
     * @param min the minimum value (inclusive)
     * @param max the maximum value (inclusive)
     * @return a random integer in [min, max]
     */
    public int uniformRandomInt(int min, int max) {
        if (min >= max) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    /**
     * Generate a random long uniformly distributed in [min, max].
     *
     * @param min the minimum value (inclusive)
     * @param max the maximum value (inclusive)
     * @return a random long in [min, max]
     */
    public long uniformRandomLong(long min, long max) {
        if (min >= max) {
            return min;
        }
        return min + (long) (random.nextDouble() * (max - min + 1));
    }

    // ========================================================================
    // Exponential Distribution
    // ========================================================================

    /**
     * Generate a random value from an exponential distribution.
     * Used for break durations and rare events as specified in REQUIREMENTS.md Section 4.1.
     *
     * @param lambda the rate parameter (λ = 1/mean)
     * @return a random value from Exponential(λ)
     */
    public double exponentialRandom(double lambda) {
        return -Math.log(1 - random.nextDouble()) / lambda;
    }

    /**
     * Generate a bounded random value from an exponential distribution.
     *
     * @param lambda the rate parameter (λ = 1/mean)
     * @param min    the minimum allowed value
     * @param max    the maximum allowed value
     * @return a random value from Exponential(λ) clamped to [min, max]
     */
    public double exponentialRandom(double lambda, double min, double max) {
        double value = exponentialRandom(lambda);
        return clamp(value, min, max);
    }

    /**
     * Generate a bounded long from an exponential distribution.
     * Used for break duration timing.
     *
     * @param lambda the rate parameter (λ = 1/mean)
     * @param min    the minimum allowed value in milliseconds
     * @param max    the maximum allowed value in milliseconds
     * @return a random long from Exponential(λ) clamped to [min, max]
     */
    public long exponentialRandomLong(double lambda, long min, long max) {
        return Math.round(exponentialRandom(lambda, min, max));
    }

    // ========================================================================
    // Weighted Selection
    // ========================================================================

    /**
     * Select an index based on weighted probabilities.
     * Weights do not need to sum to 1.0; they are normalized internally.
     *
     * @param weights array of weights for each index
     * @return the selected index
     */
    public int weightedChoice(double[] weights) {
        if (weights == null || weights.length == 0) {
            throw new IllegalArgumentException("Weights array cannot be null or empty");
        }

        double totalWeight = 0;
        for (double w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("Weights cannot be negative");
            }
            totalWeight += w;
        }

        if (totalWeight <= 0) {
            // All weights are zero, return random index
            return random.nextInt(weights.length);
        }

        double randomValue = random.nextDouble() * totalWeight;
        double cumulative = 0;

        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (randomValue < cumulative) {
                return i;
            }
        }

        // Fallback to last index (shouldn't happen with proper floating point)
        return weights.length - 1;
    }

    /**
     * Select an index based on weighted probabilities from a list.
     *
     * @param weights list of weights for each index
     * @return the selected index
     */
    public int weightedChoice(List<Double> weights) {
        return weightedChoice(weights.stream().mapToDouble(Double::doubleValue).toArray());
    }

    /**
     * Select an item from a list based on weighted probabilities.
     *
     * @param items   the list of items to choose from
     * @param weights the weights corresponding to each item
     * @param <T>     the type of items
     * @return the selected item
     */
    public <T> T weightedChoiceItem(List<T> items, double[] weights) {
        if (items.size() != weights.length) {
            throw new IllegalArgumentException("Items and weights must have the same length");
        }
        int index = weightedChoice(weights);
        return items.get(index);
    }

    // ========================================================================
    // Probability Checks
    // ========================================================================

    /**
     * Check if an event with given probability should occur.
     *
     * @param probability the probability (0.0 to 1.0)
     * @return true if the event should occur
     */
    public boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    /**
     * Check if an event with given percentage should occur.
     *
     * @param percent the percentage (0 to 100)
     * @return true if the event should occur
     */
    public boolean chancePercent(double percent) {
        return chance(percent / 100.0);
    }

    // ========================================================================
    // 2D Gaussian Distribution (for click positions)
    // ========================================================================

    /**
     * Generate a 2D point from a bivariate Gaussian distribution.
     * Used for click position variance as specified in REQUIREMENTS.md Section 3.1.2.
     *
     * @param centerX the mean X coordinate
     * @param centerY the mean Y coordinate
     * @param sigmaX  the standard deviation in X direction
     * @param sigmaY  the standard deviation in Y direction
     * @return an array [x, y] with the generated point
     */
    public double[] gaussian2D(double centerX, double centerY, double sigmaX, double sigmaY) {
        double x = gaussianRandom(centerX, sigmaX);
        double y = gaussianRandom(centerY, sigmaY);
        return new double[]{x, y};
    }

    /**
     * Generate a bounded 2D point from a bivariate Gaussian distribution.
     *
     * @param centerX the mean X coordinate
     * @param centerY the mean Y coordinate
     * @param sigmaX  the standard deviation in X direction
     * @param sigmaY  the standard deviation in Y direction
     * @param minX    minimum X value
     * @param maxX    maximum X value
     * @param minY    minimum Y value
     * @param maxY    maximum Y value
     * @return an array [x, y] with the generated point clamped to bounds
     */
    public double[] gaussian2D(double centerX, double centerY, double sigmaX, double sigmaY,
                               double minX, double maxX, double minY, double maxY) {
        double x = gaussianRandom(centerX, sigmaX, minX, maxX);
        double y = gaussianRandom(centerY, sigmaY, minY, maxY);
        return new double[]{x, y};
    }

    /**
     * Generate a click position within a hitbox using 2D Gaussian distribution.
     * As per spec: center at 45-55% of dimensions, σ = 15% of dimension.
     *
     * @param hitboxX      the hitbox X coordinate
     * @param hitboxY      the hitbox Y coordinate
     * @param hitboxWidth  the hitbox width
     * @param hitboxHeight the hitbox height
     * @return an array [x, y] with the click position
     */
    public int[] generateClickPosition(int hitboxX, int hitboxY, int hitboxWidth, int hitboxHeight) {
        // Center offset: 45-55% of dimensions (never geometric center)
        double centerOffsetX = uniformRandom(0.45, 0.55);
        double centerOffsetY = uniformRandom(0.45, 0.55);

        double centerX = hitboxX + hitboxWidth * centerOffsetX;
        double centerY = hitboxY + hitboxHeight * centerOffsetY;

        // Standard deviation: 15% of dimension
        double sigmaX = hitboxWidth * 0.15;
        double sigmaY = hitboxHeight * 0.15;

        // Generate position with bounds
        double[] point = gaussian2D(centerX, centerY, sigmaX, sigmaY,
                hitboxX, hitboxX + hitboxWidth,
                hitboxY, hitboxY + hitboxHeight);

        return new int[]{(int) Math.round(point[0]), (int) Math.round(point[1])};
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Clamp a value to a specified range.
     *
     * @param value the value to clamp
     * @param min   the minimum allowed value
     * @param max   the maximum allowed value
     * @return the clamped value
     */
    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Clamp an integer value to a specified range.
     *
     * @param value the value to clamp
     * @param min   the minimum allowed value
     * @param max   the maximum allowed value
     * @return the clamped value
     */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Clamp a long value to a specified range.
     *
     * @param value the value to clamp
     * @param min   the minimum allowed value
     * @param max   the maximum allowed value
     * @return the clamped value
     */
    public static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Linear interpolation between two values.
     *
     * @param a the start value
     * @param b the end value
     * @param t the interpolation factor (0.0 to 1.0)
     * @return the interpolated value
     */
    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * Get the underlying Random instance for advanced operations.
     *
     * @return the Random instance
     */
    public Random getRandom() {
        return random;
    }
}

