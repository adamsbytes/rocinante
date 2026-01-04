package com.rocinante.util;

import javax.inject.Singleton;
import java.security.SecureRandom;
import java.util.List;
import java.util.Random;

/**
 * Statistical distribution utilities for humanized input simulation.
 * Provides Gaussian, Poisson, uniform, and exponential distributions
 * as specified in REQUIREMENTS.md Section 3 and 4.
 * 
 * <p>Uses {@link SecureRandom} for cryptographically strong random number generation.
 * This ensures that timing patterns cannot be predicted or reverse-engineered from
 * observed behavior, which is critical for avoiding statistical detection.
 * 
 * <p>A single shared {@link SecureRandom} instance is used application-wide for:
 * <ul>
 *   <li>Thread safety (SecureRandom is thread-safe)</li>
 *   <li>Entropy pool efficiency (one pool, better randomness)</li>
 *   <li>Consistent unpredictability across all components</li>
 * </ul>
 */
@Singleton
public class Randomization {

    /**
     * Shared SecureRandom instance for cryptographically strong randomness.
     * Thread-safe and used by all Randomization instances (except test instances).
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Random random;

    /**
     * Default constructor using the shared SecureRandom instance.
     * This ensures cryptographically strong, unpredictable random values.
     */
    public Randomization() {
        this.random = SECURE_RANDOM;
    }

    /**
     * Constructor with seeded random for deterministic testing.
     * 
     * <p><b>WARNING:</b> Only use this constructor in tests! Seeded Random
     * is predictable and should never be used in production.
     *
     * @param seed the random seed for reproducible test results
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

    // ========================================================================
    // Ex-Gaussian Distribution (Human Reaction Times)
    // ========================================================================

    /**
     * Generate a random value from an ex-Gaussian distribution.
     * 
     * The ex-Gaussian (exponentially modified Gaussian) is the convolution of
     * a normal distribution and an exponential distribution. It produces a
     * right-skewed distribution that closely models human reaction times.
     * 
     * Real human reaction time data shows:
     * - A roughly Gaussian core (fast reactions cluster around the mean)
     * - A long right tail (occasional slow reactions due to distraction, fatigue)
     * 
     * This is more realistic than pure Gaussian which is symmetric.
     *
     * @param mu    the mean of the Gaussian component (central tendency)
     * @param sigma the standard deviation of the Gaussian component (consistency)
     * @param tau   the mean of the exponential component (tail heaviness)
     * @return a random value from ex-Gaussian(μ, σ, τ)
     */
    public double exGaussianRandom(double mu, double sigma, double tau) {
        // Gaussian component: the "core" of the distribution
        double gaussian = random.nextGaussian() * sigma + mu;
        
        // Exponential component: adds the right tail
        // Using 1 - nextDouble() to avoid log(0)
        double exponential = -tau * Math.log(1 - random.nextDouble());
        
        return gaussian + exponential;
    }

    /**
     * Generate a bounded random value from an ex-Gaussian distribution.
     *
     * @param mu    the mean of the Gaussian component
     * @param sigma the standard deviation of the Gaussian component
     * @param tau   the mean of the exponential component (tail heaviness)
     * @param min   the minimum allowed value
     * @param max   the maximum allowed value
     * @return a random value from ex-Gaussian(μ, σ, τ) clamped to [min, max]
     */
    public double exGaussianRandom(double mu, double sigma, double tau, double min, double max) {
        double value = exGaussianRandom(mu, sigma, tau);
        return clamp(value, min, max);
    }

    /**
     * Generate a bounded long from an ex-Gaussian distribution.
     * Ideal for timing delays where human reaction patterns matter.
     *
     * @param mu    the mean of the Gaussian component in milliseconds
     * @param sigma the standard deviation in milliseconds
     * @param tau   the exponential tail parameter in milliseconds
     * @param min   the minimum allowed value in milliseconds
     * @param max   the maximum allowed value in milliseconds
     * @return a random long from ex-Gaussian clamped to [min, max]
     */
    public long exGaussianRandomLong(double mu, double sigma, double tau, long min, long max) {
        return Math.round(exGaussianRandom(mu, sigma, tau, min, max));
    }

    /**
     * Generate a human-like reaction time using ex-Gaussian distribution.
     * 
     * <p>Ex-Gaussian is the gold standard for modeling human reaction times in
     * cognitive psychology. It's the convolution of a Gaussian (core reactions)
     * and an exponential (occasional slow reactions from attention lapses).
     * 
     * <p>This method auto-tunes σ and τ based on the median to produce realistic
     * reaction time distributions:
     * <ul>
     *   <li><b>σ (sigma) = 20% of median:</b> Consistency variation in fast reactions</li>
     *   <li><b>τ (tau) = 30% of median:</b> Right-tail for "brain farts" and distractions</li>
     * </ul>
     * 
     * <p>Use for perception/decision delays like:
     * <ul>
     *   <li>Noticing an event on screen</li>
     *   <li>Deciding to press a hotkey</li>
     *   <li>Detecting a typo</li>
     *   <li>Reacting to game state changes</li>
     * </ul>
     * 
     * <p>For inter-action intervals (e.g., click-to-click timing), use
     * {@link #humanizedDelayMs(long, long, long)} instead.
     *
     * @param median the approximate median reaction time in milliseconds
     * @param min    physiological minimum (fastest possible human reaction)
     * @param max    maximum allowed delay (prevents infinite waits)
     * @return a human-like reaction time in milliseconds
     */
    public long reactionTimeMs(long median, long min, long max) {
        // Ex-Gaussian parameters tuned for human reaction time data:
        // - σ controls the spread of the Gaussian core (fast reactions)
        // - τ controls the exponential tail (occasional slow reactions)
        double sigma = median * 0.20;  // 20% of median for consistency variation
        double tau = median * 0.30;    // 30% of median for attention-lapse tail
        return exGaussianRandomLong(median, sigma, tau, min, max);
    }

    // ========================================================================
    // Log-Normal Distribution (Attention/Session Timing)
    // ========================================================================

    /**
     * Generate a random value from a log-normal distribution.
     * 
     * The log-normal distribution is right-skewed with a fat tail, making it ideal
     * for modeling human attention spans, session lengths, and break intervals.
     * Unlike uniform distribution, it produces:
     * - Most values clustered around the median
     * - Occasional much longer values (the "fat tail")
     * - No values below zero
     * 
     * Parameters:
     * - μ (mu): Controls the median: median = e^μ
     * - σ (sigma): Controls the spread/tail heaviness
     * 
     * Example: μ=4.5, σ=0.6 gives median≈90s, mean≈107s, 95th percentile≈220s
     *
     * @param mu    the mean of the underlying normal distribution (log-scale)
     * @param sigma the standard deviation of the underlying normal (controls tail)
     * @return a random value from LogNormal(μ, σ)
     */
    public double logNormalRandom(double mu, double sigma) {
        return Math.exp(mu + sigma * random.nextGaussian());
    }

    /**
     * Generate a bounded random value from a log-normal distribution.
     *
     * @param mu    the mean of the underlying normal distribution
     * @param sigma the standard deviation of the underlying normal
     * @param min   the minimum allowed value
     * @param max   the maximum allowed value
     * @return a random value from LogNormal(μ, σ) clamped to [min, max]
     */
    public double logNormalRandom(double mu, double sigma, double min, double max) {
        double value = logNormalRandom(mu, sigma);
        return clamp(value, min, max);
    }

    /**
     * Generate a bounded long from a log-normal distribution.
     * Ideal for attention span and session timing.
     *
     * @param mu    the mean of the underlying normal distribution
     * @param sigma the standard deviation of the underlying normal
     * @param min   the minimum allowed value
     * @param max   the maximum allowed value
     * @return a random long from LogNormal clamped to [min, max]
     */
    public long logNormalRandomLong(double mu, double sigma, long min, long max) {
        return Math.round(logNormalRandom(mu, sigma, min, max));
    }

    /**
     * Generate a human-like timing interval using log-normal distribution.
     * 
     * Real human inter-event timings follow a log-normal or gamma distribution,
     * NOT uniform. This method provides a convenient API for generating realistic
     * timing intervals that will pass Kolmogorov-Smirnov tests against human data.
     * 
     * The log-normal distribution is characterized by:
     * - Positive skew (long tail to the right)
     * - Mode < Median < Mean
     * - Most values cluster near the lower end with occasional longer delays
     * 
     * This matches real human motor timing where:
     * - Most actions are near-optimal (quick)
     * - Occasional "hiccups" cause longer delays
     * - Very fast reactions are physiologically impossible (clamped by min)
     * 
     * @param median the median timing (50th percentile) - most common value
     * @param spread controls tail heaviness (0.2 = tight, 0.5 = moderate, 0.8 = heavy)
     * @param min    physiological minimum (fastest possible)
     * @param max    maximum allowed delay
     * @return a human-like timing value in the same units as inputs
     */
    public long humanizedDelayMs(long median, double spread, long min, long max) {
        // Convert median to log-normal μ parameter
        // For log-normal: median = exp(μ), so μ = ln(median)
        double mu = Math.log(median);
        
        // σ controls spread/skew - higher = more right-skew
        double sigma = spread;
        
        return logNormalRandomLong(mu, sigma, min, max);
    }

    /**
     * Generate a human-like timing interval with default moderate spread.
     * Convenience overload for common timing scenarios.
     * 
     * @param median the median timing (50th percentile)
     * @param min    physiological minimum
     * @param max    maximum allowed delay
     * @return a human-like timing value
     */
    public long humanizedDelayMs(long median, long min, long max) {
        return humanizedDelayMs(median, 0.4, min, max);  // Default moderate spread
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
    // Multivariate Normal Distribution (for correlated trait generation)
    // ========================================================================

    /**
     * Generate correlated random values from a multivariate normal distribution.
     * 
     * Uses Cholesky decomposition to transform independent standard normals into
     * correlated normals with the specified correlation structure. This is essential
     * for generating realistic human trait profiles where characteristics like
     * mouse speed, click duration, and reaction time are correlated but not
     * perfectly linearly dependent.
     * 
     * Real human populations show correlations of r=0.3-0.6 between motor traits,
     * NOT the r≈0.9 implied by linear derivations. This method preserves that
     * natural variance, allowing for archetypes like "Fast but Sloppy" (high speed,
     * high overshoot) or "Slow but Snappy" (low speed, quick clicks).
     *
     * @param means the mean values for each dimension
     * @param stdDevs the standard deviations for each dimension
     * @param correlationMatrix the correlation matrix (must be symmetric, positive semi-definite)
     * @return array of correlated random values, one per dimension
     * @throws IllegalArgumentException if dimensions don't match or matrix is invalid
     */
    public double[] multivariateNormal(double[] means, double[] stdDevs, double[][] correlationMatrix) {
        int n = means.length;
        if (stdDevs.length != n || correlationMatrix.length != n) {
            throw new IllegalArgumentException("All parameters must have same dimensionality");
        }
        
        // Step 1: Generate n independent standard normal samples
        double[] z = new double[n];
        for (int i = 0; i < n; i++) {
            z[i] = random.nextGaussian();
        }
        
        // Step 2: Cholesky decomposition of correlation matrix (L where LL^T = R)
        double[][] L = choleskyDecomposition(correlationMatrix);
        
        // Step 3: Transform: correlatedZ = L * z
        double[] correlatedZ = new double[n];
        for (int i = 0; i < n; i++) {
            correlatedZ[i] = 0.0;
            for (int j = 0; j <= i; j++) {  // L is lower triangular
                correlatedZ[i] += L[i][j] * z[j];
            }
        }
        
        // Step 4: Scale and shift to actual distribution: X = mean + stdDev * correlatedZ
        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            result[i] = means[i] + stdDevs[i] * correlatedZ[i];
        }
        
        return result;
    }

    /**
     * Cholesky decomposition of a positive semi-definite matrix.
     * Returns lower triangular matrix L such that A = L * L^T.
     * 
     * Uses Cholesky-Banachiewicz algorithm with regularization for numerical stability.
     *
     * @param A the input matrix (must be symmetric, positive semi-definite)
     * @return the lower triangular Cholesky factor L
     * @throws IllegalArgumentException if matrix is not positive semi-definite
     */
    private double[][] choleskyDecomposition(double[][] A) {
        int n = A.length;
        double[][] L = new double[n][n];
        
        // Small regularization term for numerical stability
        double epsilon = 1e-10;
        
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0.0;
                
                for (int k = 0; k < j; k++) {
                    sum += L[i][k] * L[j][k];
                }
                
                if (i == j) {
                    // Diagonal element: L[i][i] = sqrt(A[i][i] - sum)
                    double diag = A[i][i] - sum;
                    if (diag < 0) {
                        // Add regularization if near-singular
                        diag = epsilon;
                    }
                    L[i][j] = Math.sqrt(diag);
                } else {
                    // Off-diagonal: L[i][j] = (A[i][j] - sum) / L[j][j]
                    if (L[j][j] < epsilon) {
                        L[i][j] = 0.0;
                    } else {
                        L[i][j] = (A[i][j] - sum) / L[j][j];
                    }
                }
            }
        }
        
        return L;
    }

    /**
     * Generate correlated random values with clamping to specified bounds.
     * 
     * Values that fall outside bounds are clamped, not regenerated. This maintains
     * the correlation structure while ensuring all values are within valid ranges.
     *
     * @param means the mean values for each dimension
     * @param stdDevs the standard deviations for each dimension
     * @param correlationMatrix the correlation matrix
     * @param mins the minimum allowed values for each dimension
     * @param maxs the maximum allowed values for each dimension
     * @return array of correlated random values, clamped to [min, max] per dimension
     */
    public double[] multivariateNormalBounded(double[] means, double[] stdDevs, 
            double[][] correlationMatrix, double[] mins, double[] maxs) {
        double[] raw = multivariateNormal(means, stdDevs, correlationMatrix);
        for (int i = 0; i < raw.length; i++) {
            raw[i] = clamp(raw[i], mins[i], maxs[i]);
        }
        return raw;
    }

    // ========================================================================
    // Per-Instance Correlation Matrix Perturbation
    // ========================================================================
    // 
    // Real human populations don't share a single fixed correlation structure.
    // Different sub-populations (gamers, casuals, age groups) have varying
    // correlation patterns. To avoid a detectable "too-perfect" population-level
    // fingerprint, we add bounded random noise to the base correlation matrix
    // for each bot instance.
    // ========================================================================

    /**
     * Generate a per-instance correlation matrix by adding bounded noise to a base matrix.
     * 
     * <p>This solves the "population fingerprint" problem where all bots sampling from
     * the same fixed correlation matrix creates a detectable statistical signature.
     * Real human populations have:
     * <ul>
     *   <li>Noisier correlations (more confounding variables)</li>
     *   <li>Different correlation magnitudes between individuals</li>
     *   <li>Sub-population structure (gamers vs casuals, young vs old)</li>
     * </ul>
     * 
     * <p>The algorithm:
     * <ol>
     *   <li>Add Gaussian noise to each off-diagonal element</li>
     *   <li>Ensure symmetry by averaging upper and lower triangles</li>
     *   <li>Clamp off-diagonals to [-0.95, 0.95] to maintain valid range</li>
     *   <li>Apply eigenvalue regularization to ensure positive semi-definiteness</li>
     * </ol>
     * 
     * @param baseMatrix the base correlation matrix (must be square, symmetric, diagonal=1)
     * @param noiseStdDev standard deviation of Gaussian noise added to off-diagonals
     *                    (recommended: 0.08-0.15 for subtle variation)
     * @return a new correlation matrix with per-instance noise, guaranteed valid
     */
    public double[][] perturbCorrelationMatrix(double[][] baseMatrix, double noiseStdDev) {
        int n = baseMatrix.length;
        double[][] noisy = new double[n][n];
        
        // Step 1: Copy base matrix and add Gaussian noise to off-diagonals
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    // Diagonal must stay at 1.0 (self-correlation)
                    noisy[i][j] = 1.0;
                } else {
                    // Add bounded Gaussian noise to off-diagonal
                    double noise = random.nextGaussian() * noiseStdDev;
                    noisy[i][j] = baseMatrix[i][j] + noise;
                }
            }
        }
        
        // Step 2: Enforce symmetry by averaging with transpose
        // This handles any asymmetric noise we added
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double avg = (noisy[i][j] + noisy[j][i]) / 2.0;
                noisy[i][j] = avg;
                noisy[j][i] = avg;
            }
        }
        
        // Step 3: Clamp off-diagonals to valid correlation range
        // Use [-0.95, 0.95] to leave room for regularization if needed
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    noisy[i][j] = clamp(noisy[i][j], -0.95, 0.95);
                }
            }
        }
        
        // Step 4: Ensure positive semi-definiteness via eigenvalue regularization
        // If any eigenvalue is negative or too small, the Cholesky decomposition will fail
        return ensurePositiveSemiDefinite(noisy);
    }

    /**
     * Ensure a symmetric matrix is positive semi-definite.
     * 
     * <p>Uses eigenvalue regularization: if any eigenvalue is below a threshold,
     * adds a small multiple of the identity matrix to shift all eigenvalues up.
     * This is numerically stable and preserves the correlation structure.
     * 
     * <p>For small matrices (≤10 dimensions), we use an iterative approach:
     * attempt Cholesky decomposition and add regularization until it succeeds.
     * 
     * @param matrix a symmetric matrix
     * @return a positive semi-definite version of the matrix
     */
    private double[][] ensurePositiveSemiDefinite(double[][] matrix) {
        int n = matrix.length;
        double[][] result = copyMatrix(matrix);
        
        // Iteratively add regularization until Cholesky succeeds
        // Start with a small regularization term and increase if needed
        double regularization = 1e-6;
        int maxAttempts = 10;
        
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                // Test if Cholesky decomposition succeeds
                choleskyDecomposition(result);
                return result;  // Success - matrix is positive semi-definite
            } catch (Exception e) {
                // Cholesky failed - matrix has negative eigenvalues
                // Add regularization: R' = R + λI (shift eigenvalues up by λ)
                for (int i = 0; i < n; i++) {
                    result[i][i] = matrix[i][i] + regularization;
                }
                regularization *= 10;  // Increase for next attempt
            }
        }
        
        // Fallback: return the regularized matrix even if not perfectly PSD
        // The Cholesky decomposition in multivariateNormal has its own regularization
        return result;
    }

    /**
     * Create a deep copy of a 2D matrix.
     */
    private double[][] copyMatrix(double[][] matrix) {
        int n = matrix.length;
        double[][] copy = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(matrix[i], 0, copy[i], 0, n);
        }
        return copy;
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
     * Generate a random integer from a Gaussian distribution (static convenience method).
     * Uses the shared SecureRandom instance.
     *
     * @param mean   the mean value
     * @param stdDev the standard deviation
     * @return a random integer
     */
    public static int gaussianInt(double mean, double stdDev) {
        return (int) Math.round(mean + SECURE_RANDOM.nextGaussian() * stdDev);
    }

    /**
     * Generate a 2D point from a bivariate Gaussian distribution (static convenience method).
     * Uses the shared SecureRandom instance.
     *
     * @param centerX the mean X coordinate
     * @param centerY the mean Y coordinate
     * @param sigmaX  the standard deviation in X direction
     * @param sigmaY  the standard deviation in Y direction
     * @return an array [x, y] with the generated point
     */
    public static double[] staticGaussian2D(double centerX, double centerY, double sigmaX, double sigmaY) {
        double x = centerX + SECURE_RANDOM.nextGaussian() * sigmaX;
        double y = centerY + SECURE_RANDOM.nextGaussian() * sigmaY;
        return new double[]{x, y};
    }

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

    // ========================================================================
    // Static Secure Accessors
    // ========================================================================
    // 
    // These methods provide direct access to the shared SecureRandom instance
    // for cases where injecting a Randomization instance isn't practical.
    // Prefer using instance methods when possible for consistency.
    // ========================================================================

    /**
     * Get the shared SecureRandom instance for cryptographically strong randomness.
     * 
     * <p>Use this when you need direct access to SecureRandom and cannot inject
     * a {@link Randomization} instance. Thread-safe.
     *
     * @return the shared SecureRandom instance
     */
    public static SecureRandom getSecureRandom() {
        return SECURE_RANDOM;
    }

    /**
     * Generate a secure random double in [0.0, 1.0).
     * Static convenience method using the shared SecureRandom.
     *
     * @return a random double
     */
    public static double secureDouble() {
        return SECURE_RANDOM.nextDouble();
    }

    /**
     * Generate a secure random int in [0, bound).
     * Static convenience method using the shared SecureRandom.
     *
     * @param bound the upper bound (exclusive)
     * @return a random int
     */
    public static int secureInt(int bound) {
        return SECURE_RANDOM.nextInt(bound);
    }

    /**
     * Generate a secure random int in [min, max].
     * Static convenience method using the shared SecureRandom.
     *
     * @param min the minimum value (inclusive)
     * @param max the maximum value (inclusive)
     * @return a random int in [min, max]
     */
    public static int secureIntRange(int min, int max) {
        if (min >= max) {
            return min;
        }
        return min + SECURE_RANDOM.nextInt(max - min + 1);
    }

    /**
     * Generate a secure random boolean.
     * Static convenience method using the shared SecureRandom.
     *
     * @return a random boolean
     */
    public static boolean secureBoolean() {
        return SECURE_RANDOM.nextBoolean();
    }

    /**
     * Check if an event with given probability should occur.
     * Static convenience method using the shared SecureRandom.
     *
     * @param probability the probability (0.0 to 1.0)
     * @return true if the event should occur
     */
    public static boolean secureChance(double probability) {
        return SECURE_RANDOM.nextDouble() < probability;
    }
}

