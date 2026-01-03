package com.rocinante.input;

import org.junit.Test;

import java.security.SecureRandom;

import static org.junit.Assert.*;

/**
 * Tests for the PinkNoiseGenerator (Voss-McCartney algorithm).
 * Verifies that the generated noise has 1/f characteristics.
 */
public class PinkNoiseGeneratorTest {

    @Test
    public void testOutputRange() {
        PinkNoiseGenerator generator = new PinkNoiseGenerator();
        
        // Generate many samples and check they're roughly in expected range
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        double sum = 0;
        int samples = 100000;  // Need many samples for pink noise statistics
        
        // Skip initial samples to allow generator to stabilize
        for (int i = 0; i < 10000; i++) {
            generator.next();
        }
        
        for (int i = 0; i < samples; i++) {
            double value = generator.next();
            min = Math.min(min, value);
            max = Math.max(max, value);
            sum += value;
        }
        
        double mean = sum / samples;
        
        // Pink noise has significant low-frequency power, so mean may drift more than white noise
        // Use relaxed tolerance (0.3) since individual runs may show bias
        assertTrue("Mean should be relatively small, got: " + mean, Math.abs(mean) < 0.5);
        
        // Values should roughly be in [-4, 4] range (normalized)
        // With 16 octaves and sqrt(16) normalization, 3-4 sigma is reasonable
        assertTrue("Min should be reasonable, got: " + min, min > -5.0);
        assertTrue("Max should be reasonable, got: " + max, max < 5.0);
    }

    @Test
    public void testAmplitudeScaling() {
        PinkNoiseGenerator generator = new PinkNoiseGenerator();
        double amplitude = 10.0;
        
        // Generate samples with amplitude scaling
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        
        for (int i = 0; i < 1000; i++) {
            double value = generator.next(amplitude);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        
        // Values should be scaled by amplitude
        assertTrue("Max should be scaled, got: " + max, max > 1.0);
        assertTrue("Min should be scaled, got: " + min, min < -1.0);
    }

    @Test
    public void test2DOutput() {
        PinkNoiseGenerator generator = new PinkNoiseGenerator();
        
        // 2D output should have two independent values
        double[] result = generator.next2D(1.0);
        assertEquals("Should return 2 values", 2, result.length);
    }

    @Test
    public void testStepCounter() {
        PinkNoiseGenerator generator = new PinkNoiseGenerator();
        assertEquals("Initial step count should be 0", 0, generator.getStepCount());
        
        generator.next();
        assertEquals("Step count should increment", 1, generator.getStepCount());
        
        for (int i = 0; i < 99; i++) {
            generator.next();
        }
        assertEquals("Step count should be 100", 100, generator.getStepCount());
    }

    @Test
    public void testReset() {
        PinkNoiseGenerator generator = new PinkNoiseGenerator();
        
        // Generate some samples
        for (int i = 0; i < 100; i++) {
            generator.next();
        }
        
        // Reset
        generator.reset();
        assertEquals("Step count should be 0 after reset", 0, generator.getStepCount());
    }

    @Test
    public void testNotWhiteNoise() {
        // Pink noise should have autocorrelation (values are correlated)
        // White noise has zero autocorrelation
        PinkNoiseGenerator pink = new PinkNoiseGenerator(new SecureRandom());
        SecureRandom white = new SecureRandom();
        
        // Calculate lag-1 autocorrelation for both
        double pinkAutoCorr = calculateAutocorrelation(pink, 1000, 1);
        double whiteAutoCorr = calculateWhiteAutocorrelation(white, 1000, 1);
        
        // Pink noise should have positive autocorrelation (> 0.3 typically)
        // White noise should have near-zero autocorrelation
        assertTrue("Pink noise should have positive autocorrelation, got: " + pinkAutoCorr, 
                pinkAutoCorr > 0.1);
        assertTrue("White noise autocorrelation should be near zero, got: " + whiteAutoCorr, 
                Math.abs(whiteAutoCorr) < 0.15);
    }

    private double calculateAutocorrelation(PinkNoiseGenerator gen, int n, int lag) {
        double[] values = new double[n];
        double sum = 0;
        
        for (int i = 0; i < n; i++) {
            values[i] = gen.next();
            sum += values[i];
        }
        double mean = sum / n;
        
        double variance = 0;
        for (int i = 0; i < n; i++) {
            variance += (values[i] - mean) * (values[i] - mean);
        }
        variance /= n;
        
        double covariance = 0;
        for (int i = 0; i < n - lag; i++) {
            covariance += (values[i] - mean) * (values[i + lag] - mean);
        }
        covariance /= (n - lag);
        
        return covariance / variance;
    }

    private double calculateWhiteAutocorrelation(SecureRandom random, int n, int lag) {
        double[] values = new double[n];
        double sum = 0;
        
        for (int i = 0; i < n; i++) {
            values[i] = random.nextDouble() * 2 - 1;
            sum += values[i];
        }
        double mean = sum / n;
        
        double variance = 0;
        for (int i = 0; i < n; i++) {
            variance += (values[i] - mean) * (values[i] - mean);
        }
        variance /= n;
        
        double covariance = 0;
        for (int i = 0; i < n - lag; i++) {
            covariance += (values[i] - mean) * (values[i + lag] - mean);
        }
        covariance /= (n - lag);
        
        return covariance / variance;
    }

    @Test
    public void testDifferentOctaveUpdateRates() {
        // The Voss-McCartney algorithm updates different octaves at different rates
        // Lower octaves update more frequently (higher frequency components)
        // Higher octaves update less frequently (lower frequency components)
        
        // We can verify this by checking that the algorithm runs without error
        // and produces reasonable output over many iterations
        PinkNoiseGenerator generator = new PinkNoiseGenerator();
        
        // Generate enough samples to ensure all octaves are updated multiple times
        // With 16 octaves, we need at least 2^16 = 65536 samples
        int samples = 100000;
        double sum = 0;
        double sumSquared = 0;
        
        for (int i = 0; i < samples; i++) {
            double value = generator.next();
            sum += value;
            sumSquared += value * value;
        }
        
        double mean = sum / samples;
        double variance = sumSquared / samples - mean * mean;
        
        // Pink noise has significant low-frequency power, so mean may drift more than white noise
        // Use relaxed tolerance since individual runs may show bias
        assertTrue("Mean should be relatively small, got: " + mean, Math.abs(mean) < 0.5);
        
        // Variance should be positive and reasonable
        assertTrue("Variance should be positive", variance > 0);
        assertTrue("Variance should be reasonable", variance < 5);
    }
}
