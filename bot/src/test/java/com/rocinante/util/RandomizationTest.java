package com.rocinante.util;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for Randomization utility class.
 * Verifies statistical distributions match REQUIREMENTS.md specifications.
 */
public class RandomizationTest {

    private static final int SAMPLE_SIZE = 1000;
    private static final double TOLERANCE = 0.15; // 15% tolerance for statistical tests

    private Randomization randomization;

    @Before
    public void setUp() {
        // Use seeded randomization for reproducible tests
        randomization = new Randomization(12345L);
    }

    // ========================================================================
    // Gaussian Distribution Tests
    // ========================================================================

    @Test
    public void testGaussianRandom_MeanAndStdDev() {
        double mean = 100.0;
        double stdDev = 15.0;

        double[] samples = new double[SAMPLE_SIZE];
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            samples[i] = randomization.gaussianRandom(mean, stdDev);
        }

        double sampleMean = calculateMean(samples);
        double sampleStdDev = calculateStdDev(samples, sampleMean);

        // Verify mean is within tolerance
        assertEquals("Mean should be close to specified value", mean, sampleMean, mean * TOLERANCE);

        // Verify std dev is within tolerance
        assertEquals("StdDev should be close to specified value", stdDev, sampleStdDev, stdDev * TOLERANCE);
    }

    @Test
    public void testGaussianRandom_Bounded() {
        double mean = 85.0;
        double stdDev = 15.0;
        double min = 60.0;
        double max = 120.0;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double value = randomization.gaussianRandom(mean, stdDev, min, max);
            assertTrue("Value should be >= min", value >= min);
            assertTrue("Value should be <= max", value <= max);
        }
    }

    @Test
    public void testGaussianRandomInt_Bounded() {
        double mean = 100.0;
        double stdDev = 20.0;
        int min = 60;
        int max = 140;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int value = randomization.gaussianRandomInt(mean, stdDev, min, max);
            assertTrue("Value should be >= min", value >= min);
            assertTrue("Value should be <= max", value <= max);
        }
    }

    @Test
    public void testGaussianRandomLong_ClickDuration() {
        // Test click duration spec: μ=85ms, σ=15ms, min=60, max=120
        double mean = 85.0;
        double stdDev = 15.0;
        long min = 60;
        long max = 120;

        long[] samples = new long[SAMPLE_SIZE];
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            samples[i] = randomization.gaussianRandomLong(mean, stdDev, min, max);
            assertTrue("Click duration should be >= 60ms", samples[i] >= min);
            assertTrue("Click duration should be <= 120ms", samples[i] <= max);
        }

        double sampleMean = calculateMean(samples);
        // Mean should be close to 85ms (accounting for truncation at bounds)
        assertTrue("Mean click duration should be reasonable", sampleMean > 70 && sampleMean < 100);
    }

    // ========================================================================
    // Poisson Distribution Tests
    // ========================================================================

    @Test
    public void testPoissonRandom_Lambda() {
        double lambda = 250.0; // Reaction time spec

        int[] samples = new int[SAMPLE_SIZE];
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            samples[i] = randomization.poissonRandom(lambda);
        }

        double sampleMean = calculateMean(samples);

        // For Poisson, mean ≈ lambda
        assertEquals("Poisson mean should be close to lambda", lambda, sampleMean, lambda * TOLERANCE);
    }

    @Test
    public void testPoissonRandom_Bounded() {
        double lambda = 250.0;
        int min = 150;
        int max = 600;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int value = randomization.poissonRandom(lambda, min, max);
            assertTrue("Value should be >= min", value >= min);
            assertTrue("Value should be <= max", value <= max);
        }
    }

    @Test
    public void testPoissonRandomLong_ReactionTime() {
        // Test REACTION profile: λ=250ms, min=150ms, max=600ms
        double lambda = 250.0;
        long min = 150;
        long max = 600;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long value = randomization.poissonRandomLong(lambda, min, max);
            assertTrue("Reaction time should be >= 150ms", value >= min);
            assertTrue("Reaction time should be <= 600ms", value <= max);
        }
    }

    // ========================================================================
    // Uniform Distribution Tests
    // ========================================================================

    @Test
    public void testUniformRandom_Range() {
        double min = 0.45;
        double max = 0.55;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double value = randomization.uniformRandom(min, max);
            assertTrue("Value should be >= min", value >= min);
            assertTrue("Value should be < max", value < max);
        }
    }

    @Test
    public void testUniformRandomInt_Range() {
        int min = 5;
        int max = 20;

        int[] counts = new int[max - min + 1];
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int value = randomization.uniformRandomInt(min, max);
            assertTrue("Value should be >= min", value >= min);
            assertTrue("Value should be <= max", value <= max);
            counts[value - min]++;
        }

        // All values should appear (uniform distribution)
        for (int count : counts) {
            assertTrue("Each value should appear at least once", count > 0);
        }
    }

    @Test
    public void testUniformRandomLong_Range() {
        long min = 50;
        long max = 150;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long value = randomization.uniformRandomLong(min, max);
            assertTrue("Value should be >= min", value >= min);
            assertTrue("Value should be <= max", value <= max);
        }
    }

    // ========================================================================
    // Exponential Distribution Tests
    // ========================================================================

    @Test
    public void testExponentialRandom_Mean() {
        double lambda = 0.01; // mean = 1/lambda = 100

        double[] samples = new double[SAMPLE_SIZE];
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            samples[i] = randomization.exponentialRandom(lambda);
        }

        double sampleMean = calculateMean(samples);
        double expectedMean = 1.0 / lambda;

        // Exponential mean = 1/lambda
        assertEquals("Exponential mean should be 1/lambda", expectedMean, sampleMean, expectedMean * TOLERANCE);
    }

    @Test
    public void testExponentialRandom_Bounded() {
        double lambda = 0.01;
        double min = 10.0;
        double max = 500.0;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double value = randomization.exponentialRandom(lambda, min, max);
            assertTrue("Value should be >= min", value >= min);
            assertTrue("Value should be <= max", value <= max);
        }
    }

    // ========================================================================
    // Weighted Choice Tests
    // ========================================================================

    @Test
    public void testWeightedChoice_Distribution() {
        double[] weights = {0.7, 0.2, 0.1}; // 70%, 20%, 10%
        int[] counts = new int[3];

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int choice = randomization.weightedChoice(weights);
            counts[choice]++;
        }

        // Verify distribution roughly matches weights
        double ratio0 = (double) counts[0] / SAMPLE_SIZE;
        double ratio1 = (double) counts[1] / SAMPLE_SIZE;
        double ratio2 = (double) counts[2] / SAMPLE_SIZE;

        assertEquals("First option should be ~70%", 0.7, ratio0, 0.1);
        assertEquals("Second option should be ~20%", 0.2, ratio1, 0.1);
        assertEquals("Third option should be ~10%", 0.1, ratio2, 0.1);
    }

    @Test
    public void testWeightedChoice_SingleOption() {
        double[] weights = {1.0};
        int choice = randomization.weightedChoice(weights);
        assertEquals("Single option should always return 0", 0, choice);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWeightedChoice_EmptyArray() {
        randomization.weightedChoice(new double[]{});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWeightedChoice_NegativeWeight() {
        randomization.weightedChoice(new double[]{0.5, -0.3, 0.2});
    }

    // ========================================================================
    // Probability Tests
    // ========================================================================

    @Test
    public void testChance_Distribution() {
        double probability = 0.3; // 30%
        int trueCount = 0;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            if (randomization.chance(probability)) {
                trueCount++;
            }
        }

        double ratio = (double) trueCount / SAMPLE_SIZE;
        assertEquals("30% chance should occur ~30% of time", probability, ratio, 0.05);
    }

    @Test
    public void testChancePercent_Distribution() {
        double percent = 15.0; // 15%
        int trueCount = 0;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            if (randomization.chancePercent(percent)) {
                trueCount++;
            }
        }

        double ratio = (double) trueCount / SAMPLE_SIZE;
        assertEquals("15% chance should occur ~15% of time", percent / 100.0, ratio, 0.05);
    }

    // ========================================================================
    // 2D Gaussian Tests (Click Position)
    // ========================================================================

    @Test
    public void testGaussian2D_Distribution() {
        double centerX = 100.0;
        double centerY = 100.0;
        double sigmaX = 15.0;
        double sigmaY = 15.0;

        double sumX = 0, sumY = 0;
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double[] point = randomization.gaussian2D(centerX, centerY, sigmaX, sigmaY);
            sumX += point[0];
            sumY += point[1];
        }

        double meanX = sumX / SAMPLE_SIZE;
        double meanY = sumY / SAMPLE_SIZE;

        assertEquals("Mean X should be close to center", centerX, meanX, centerX * TOLERANCE);
        assertEquals("Mean Y should be close to center", centerY, meanY, centerY * TOLERANCE);
    }

    @Test
    public void testGenerateClickPosition_Bounds() {
        int hitboxX = 100;
        int hitboxY = 100;
        int hitboxWidth = 50;
        int hitboxHeight = 30;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int[] pos = randomization.generateClickPosition(hitboxX, hitboxY, hitboxWidth, hitboxHeight);

            assertTrue("Click X should be within hitbox", pos[0] >= hitboxX && pos[0] <= hitboxX + hitboxWidth);
            assertTrue("Click Y should be within hitbox", pos[1] >= hitboxY && pos[1] <= hitboxY + hitboxHeight);
        }
    }

    @Test
    public void testGenerateClickPosition_NotCenter() {
        int hitboxX = 100;
        int hitboxY = 100;
        int hitboxWidth = 50;
        int hitboxHeight = 30;
        int centerX = hitboxX + hitboxWidth / 2;
        int centerY = hitboxY + hitboxHeight / 2;

        int exactCenterCount = 0;
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int[] pos = randomization.generateClickPosition(hitboxX, hitboxY, hitboxWidth, hitboxHeight);
            if (pos[0] == centerX && pos[1] == centerY) {
                exactCenterCount++;
            }
        }

        // Exact center clicks should be very rare (< 5%)
        double centerRatio = (double) exactCenterCount / SAMPLE_SIZE;
        assertTrue("Exact center clicks should be rare", centerRatio < 0.05);
    }

    // ========================================================================
    // Utility Method Tests
    // ========================================================================

    @Test
    public void testClamp_Double() {
        assertEquals(5.0, Randomization.clamp(3.0, 5.0, 10.0), 0.001);
        assertEquals(7.0, Randomization.clamp(7.0, 5.0, 10.0), 0.001);
        assertEquals(10.0, Randomization.clamp(15.0, 5.0, 10.0), 0.001);
    }

    @Test
    public void testClamp_Int() {
        assertEquals(5, Randomization.clamp(3, 5, 10));
        assertEquals(7, Randomization.clamp(7, 5, 10));
        assertEquals(10, Randomization.clamp(15, 5, 10));
    }

    @Test
    public void testClamp_Long() {
        assertEquals(5L, Randomization.clamp(3L, 5L, 10L));
        assertEquals(7L, Randomization.clamp(7L, 5L, 10L));
        assertEquals(10L, Randomization.clamp(15L, 5L, 10L));
    }

    @Test
    public void testLerp() {
        assertEquals(0.0, Randomization.lerp(0.0, 10.0, 0.0), 0.001);
        assertEquals(5.0, Randomization.lerp(0.0, 10.0, 0.5), 0.001);
        assertEquals(10.0, Randomization.lerp(0.0, 10.0, 1.0), 0.001);
        assertEquals(2.5, Randomization.lerp(0.0, 10.0, 0.25), 0.001);
    }

    // ========================================================================
    // Humanized Timing Tests
    // ========================================================================

    @Test
    public void testHumanizedDelayMs_IsWithinBounds() {
        long min = 5;
        long max = 20;
        long median = 10;
        
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long delay = randomization.humanizedDelayMs(median, min, max);
            assertTrue("Delay should be >= min", delay >= min);
            assertTrue("Delay should be <= max", delay <= max);
        }
    }

    @Test
    public void testHumanizedDelayMs_MedianApproximatelyCorrect() {
        long min = 10;
        long max = 200;
        long median = 50;
        
        long[] samples = new long[SAMPLE_SIZE * 10]; // More samples for better median estimate
        for (int i = 0; i < samples.length; i++) {
            samples[i] = randomization.humanizedDelayMs(median, 0.4, min, max);
        }
        
        Arrays.sort(samples);
        long sampleMedian = samples[samples.length / 2];
        
        // Median should be within 30% of specified (log-normal has asymmetric tails)
        assertTrue("Sample median should be close to specified median",
                Math.abs(sampleMedian - median) < median * 0.3);
    }

    @Test
    public void testHumanizedDelayMs_HasPositiveSkew() {
        // Log-normal distribution has positive skew (mean > median)
        long min = 5;
        long max = 100;
        long median = 20;
        
        long[] samples = new long[SAMPLE_SIZE * 10];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = randomization.humanizedDelayMs(median, 0.5, min, max);
        }
        
        double mean = calculateMean(samples);
        Arrays.sort(samples);
        double sampleMedian = samples[samples.length / 2];
        
        // Mean should be greater than median for positive skew
        assertTrue("Mean should be >= median (positive skew)", mean >= sampleMedian * 0.95);
    }

    @Test
    public void testHumanizedDelayMs_NotUniform() {
        // Verify the distribution is NOT uniform (would fail K-S test)
        // For uniform, values should be evenly distributed in quartiles
        // For log-normal, more values cluster below the median
        long min = 10;
        long max = 100;
        long median = 30;
        
        int belowMedian = 0;
        int aboveMedian = 0;
        int samples = SAMPLE_SIZE * 10;
        
        for (int i = 0; i < samples; i++) {
            long delay = randomization.humanizedDelayMs(median, 0.4, min, max);
            if (delay < median) {
                belowMedian++;
            } else {
                aboveMedian++;
            }
        }
        
        // For log-normal centered on median, roughly 50% should be below
        // The key is it's NOT 50% for min to median vs median to max in terms of range
        double belowRatio = (double) belowMedian / samples;
        assertTrue("Roughly half should be below median", 
                belowRatio > 0.35 && belowRatio < 0.65);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private double calculateMean(double[] samples) {
        double sum = 0;
        for (double s : samples) {
            sum += s;
        }
        return sum / samples.length;
    }

    private double calculateMean(int[] samples) {
        double sum = 0;
        for (int s : samples) {
            sum += s;
        }
        return sum / samples.length;
    }

    private double calculateMean(long[] samples) {
        double sum = 0;
        for (long s : samples) {
            sum += s;
        }
        return sum / samples.length;
    }

    private double calculateStdDev(double[] samples, double mean) {
        double sumSquaredDiff = 0;
        for (double s : samples) {
            sumSquaredDiff += (s - mean) * (s - mean);
        }
        return Math.sqrt(sumSquaredDiff / samples.length);
    }
    
    // ========================================================================
    // Multivariate Normal Distribution Tests
    // ========================================================================
    
    @Test
    public void testMultivariateNormal_BasicGeneration() {
        // Test basic 2D multivariate normal with no correlation
        double[] means = {100.0, 50.0};
        double[] stdDevs = {10.0, 5.0};
        double[][] correlation = {
            {1.0, 0.0},
            {0.0, 1.0}
        };
        
        double[] sumX = {0, 0};
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double[] result = randomization.multivariateNormal(means, stdDevs, correlation);
            assertEquals("Should return 2 values", 2, result.length);
            sumX[0] += result[0];
            sumX[1] += result[1];
        }
        
        double meanX0 = sumX[0] / SAMPLE_SIZE;
        double meanX1 = sumX[1] / SAMPLE_SIZE;
        
        assertEquals("First dimension mean should be ~100", 100.0, meanX0, 15.0);
        assertEquals("Second dimension mean should be ~50", 50.0, meanX1, 7.5);
    }
    
    @Test
    public void testMultivariateNormal_PositiveCorrelation() {
        // Test 2D multivariate normal with positive correlation (r=0.7)
        double[] means = {0, 0};
        double[] stdDevs = {1.0, 1.0};
        double[][] correlation = {
            {1.0, 0.7},
            {0.7, 1.0}
        };
        
        double[] x0 = new double[SAMPLE_SIZE];
        double[] x1 = new double[SAMPLE_SIZE];
        
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double[] result = randomization.multivariateNormal(means, stdDevs, correlation);
            x0[i] = result[0];
            x1[i] = result[1];
        }
        
        double r = pearsonCorrelation(x0, x1);
        
        // Correlation should be close to 0.7 (within sampling error)
        assertTrue("Correlation should be positive and ~0.7, but was " + r, r > 0.5 && r < 0.9);
    }
    
    @Test
    public void testMultivariateNormal_NegativeCorrelation() {
        // Test 2D multivariate normal with negative correlation (r=-0.5)
        double[] means = {0, 0};
        double[] stdDevs = {1.0, 1.0};
        double[][] correlation = {
            {1.0, -0.5},
            {-0.5, 1.0}
        };
        
        double[] x0 = new double[SAMPLE_SIZE];
        double[] x1 = new double[SAMPLE_SIZE];
        
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double[] result = randomization.multivariateNormal(means, stdDevs, correlation);
            x0[i] = result[0];
            x1[i] = result[1];
        }
        
        double r = pearsonCorrelation(x0, x1);
        
        // Correlation should be negative and ~-0.5
        assertTrue("Correlation should be negative and ~-0.5, but was " + r, r < -0.3 && r > -0.7);
    }
    
    @Test
    public void testMultivariateNormalBounded_RespectsLimits() {
        // Test that bounded version clamps to specified limits
        double[] means = {100.0, 50.0};
        double[] stdDevs = {30.0, 20.0}; // Large stdDev to ensure some would exceed bounds
        double[][] correlation = {
            {1.0, 0.0},
            {0.0, 1.0}
        };
        double[] mins = {80.0, 30.0};
        double[] maxs = {120.0, 70.0};
        
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double[] result = randomization.multivariateNormalBounded(
                means, stdDevs, correlation, mins, maxs);
            
            assertTrue("First dimension should be >= min", result[0] >= mins[0]);
            assertTrue("First dimension should be <= max", result[0] <= maxs[0]);
            assertTrue("Second dimension should be >= min", result[1] >= mins[1]);
            assertTrue("Second dimension should be <= max", result[1] <= maxs[1]);
        }
    }
    
    @Test
    public void testMultivariateNormal_HigherDimensions() {
        // Test 4D multivariate normal with mixed correlations
        double[] means = {0, 0, 0, 0};
        double[] stdDevs = {1, 1, 1, 1};
        // Correlation matrix: 0-1 positive, 0-2 negative, others uncorrelated
        double[][] correlation = {
            {1.0,  0.5, -0.4, 0.0},
            {0.5,  1.0,  0.0, 0.0},
            {-0.4, 0.0,  1.0, 0.0},
            {0.0,  0.0,  0.0, 1.0}
        };
        
        double[][] samples = new double[SAMPLE_SIZE][4];
        
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double[] result = randomization.multivariateNormal(means, stdDevs, correlation);
            assertEquals("Should return 4 values", 4, result.length);
            samples[i] = result;
        }
        
        // Extract columns
        double[] col0 = new double[SAMPLE_SIZE];
        double[] col1 = new double[SAMPLE_SIZE];
        double[] col2 = new double[SAMPLE_SIZE];
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            col0[i] = samples[i][0];
            col1[i] = samples[i][1];
            col2[i] = samples[i][2];
        }
        
        // Verify correlations are approximately correct
        double r01 = pearsonCorrelation(col0, col1);
        double r02 = pearsonCorrelation(col0, col2);
        
        assertTrue("Correlation 0-1 should be positive (~0.5): " + r01, r01 > 0.3 && r01 < 0.7);
        assertTrue("Correlation 0-2 should be negative (~-0.4): " + r02, r02 < -0.2 && r02 > -0.6);
    }
    
    /**
     * Calculate Pearson correlation coefficient between two arrays.
     */
    private double pearsonCorrelation(double[] x, double[] y) {
        int n = x.length;
        if (n != y.length || n == 0) return 0;
        
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
            sumY2 += y[i] * y[i];
        }
        
        double numerator = n * sumXY - sumX * sumY;
        double denominator = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        
        if (denominator == 0) return 0;
        return numerator / denominator;
    }
}

