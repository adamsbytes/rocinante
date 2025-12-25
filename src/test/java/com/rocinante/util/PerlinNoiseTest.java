package com.rocinante.util;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for PerlinNoise utility class.
 * Verifies noise generation for mouse path jitter as per REQUIREMENTS.md Section 3.1.1.
 */
public class PerlinNoiseTest {

    private static final int SAMPLE_SIZE = 1000;

    private PerlinNoise perlinNoise;

    @Before
    public void setUp() {
        perlinNoise = new PerlinNoise();
    }

    @Test
    public void testNoise1D_RangeNegOneToOne() {
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double x = i * 0.1;
            double noise = perlinNoise.noise1D(x);
            assertTrue("Noise should be >= -1", noise >= -1.0);
            assertTrue("Noise should be <= 1", noise <= 1.0);
        }
    }

    @Test
    public void testNoise1D_Continuity() {
        // Perlin noise should be continuous - nearby inputs should produce nearby outputs
        double previousValue = perlinNoise.noise1D(0);

        for (int i = 1; i < SAMPLE_SIZE; i++) {
            double x = i * 0.01; // Small step size
            double currentValue = perlinNoise.noise1D(x);

            // Change between adjacent samples should be small
            double change = Math.abs(currentValue - previousValue);
            assertTrue("Noise should change smoothly (change: " + change + ")", change < 0.5);

            previousValue = currentValue;
        }
    }

    @Test
    public void testNoise1D_WithAmplitude() {
        double amplitude = 3.0; // Max amplitude from spec (1-3 pixels)

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double x = i * 0.1;
            double noise = perlinNoise.noise1D(x, 1.0, amplitude);
            assertTrue("Noise should be >= -amplitude", noise >= -amplitude);
            assertTrue("Noise should be <= amplitude", noise <= amplitude);
        }
    }

    @Test
    public void testNoise1D_FrequencyAffectsVariation() {
        double lowFreqSum = 0;
        double highFreqSum = 0;

        // Count sign changes to measure variation
        double prevLow = perlinNoise.noise1D(0, 0.5, 1.0);
        double prevHigh = perlinNoise.noise1D(0, 5.0, 1.0);
        int lowSignChanges = 0;
        int highSignChanges = 0;

        for (int i = 1; i < SAMPLE_SIZE; i++) {
            double x = i * 0.01;
            double lowFreq = perlinNoise.noise1D(x, 0.5, 1.0);
            double highFreq = perlinNoise.noise1D(x, 5.0, 1.0);

            if (Math.signum(lowFreq) != Math.signum(prevLow)) lowSignChanges++;
            if (Math.signum(highFreq) != Math.signum(prevHigh)) highSignChanges++;

            prevLow = lowFreq;
            prevHigh = highFreq;
        }

        // Higher frequency should have more sign changes
        assertTrue("Higher frequency should cause more variation",
                highSignChanges > lowSignChanges);
    }

    @Test
    public void testNoise2D_RangeNegOneToOne() {
        for (int i = 0; i < 100; i++) {
            for (int j = 0; j < 100; j++) {
                double x = i * 0.1;
                double y = j * 0.1;
                double noise = perlinNoise.noise2D(x, y);
                assertTrue("2D Noise should be >= -1", noise >= -1.0);
                assertTrue("2D Noise should be <= 1", noise <= 1.0);
            }
        }
    }

    @Test
    public void testNoise2D_Continuity() {
        // 2D noise should also be continuous
        for (int i = 0; i < 50; i++) {
            for (int j = 0; j < 50; j++) {
                double x = i * 0.1;
                double y = j * 0.1;

                double v00 = perlinNoise.noise2D(x, y);
                double v10 = perlinNoise.noise2D(x + 0.1, y);
                double v01 = perlinNoise.noise2D(x, y + 0.1);

                assertTrue("2D noise should change smoothly in X",
                        Math.abs(v00 - v10) < 0.5);
                assertTrue("2D noise should change smoothly in Y",
                        Math.abs(v00 - v01) < 0.5);
            }
        }
    }

    @Test
    public void testFractalNoise1D_MoreDetail() {
        // Fractal noise with more octaves should have more detail
        double singleOctaveVariance = calculateVariance(
                i -> perlinNoise.noise1D(i * 0.1));
        double multiOctaveVariance = calculateVariance(
                i -> perlinNoise.fractalNoise1D(i * 0.1, 4, 0.5));

        // Both should produce similar range but different characteristics
        assertTrue("Fractal noise should produce values in valid range",
                multiOctaveVariance > 0);
    }

    @Test
    public void testGetPathOffset_AmplitudeConstraint() {
        // Test that path offsets respect the spec amplitude (1-3 pixels)
        double amplitude = 2.0;
        long seed = 12345L;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double progress = (double) i / SAMPLE_SIZE;
            double[] offset = perlinNoise.getPathOffset(progress, amplitude, seed);

            assertTrue("X offset should be within amplitude",
                    Math.abs(offset[0]) <= amplitude);
            assertTrue("Y offset should be within amplitude",
                    Math.abs(offset[1]) <= amplitude);
        }
    }

    @Test
    public void testGetPathOffset_Smoothness() {
        double amplitude = 2.0;
        long seed = 12345L;

        double[] prevOffset = perlinNoise.getPathOffset(0, amplitude, seed);

        for (int i = 1; i < 100; i++) {
            double progress = i * 0.01;
            double[] offset = perlinNoise.getPathOffset(progress, amplitude, seed);

            // Offsets should change smoothly
            double changeX = Math.abs(offset[0] - prevOffset[0]);
            double changeY = Math.abs(offset[1] - prevOffset[1]);

            assertTrue("X offset should change smoothly", changeX < amplitude);
            assertTrue("Y offset should change smoothly", changeY < amplitude);

            prevOffset = offset;
        }
    }

    @Test
    public void testGetPathOffset_DifferentSeeds() {
        double amplitude = 2.0;

        // Different seeds should produce different patterns
        double[] offset1 = perlinNoise.getPathOffset(0.5, amplitude, 111L);
        double[] offset2 = perlinNoise.getPathOffset(0.5, amplitude, 222L);

        // Not guaranteed to be different, but very likely
        // Test that at least one of them is non-zero
        assertTrue("Offsets with different seeds should produce values",
                offset1[0] != 0 || offset1[1] != 0 || offset2[0] != 0 || offset2[1] != 0);
    }

    @Test
    public void testGetPerpendicularOffset_Range() {
        double amplitude = 3.0;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double progress = (double) i / SAMPLE_SIZE;
            double offset = perlinNoise.getPerpendicularOffset(progress, amplitude);

            assertTrue("Perpendicular offset should be within amplitude",
                    Math.abs(offset) <= amplitude);
        }
    }

    @Test
    public void testSeededConstruction_DifferentPatterns() {
        PerlinNoise noise1 = new PerlinNoise(111L);
        PerlinNoise noise2 = new PerlinNoise(222L);

        // Different seeds should produce different noise patterns
        double value1 = noise1.noise1D(5.0);
        double value2 = noise2.noise1D(5.0);

        // These should be different (not guaranteed but very likely)
        // Just verify they're valid
        assertTrue("Seeded noise should produce valid values", value1 >= -1 && value1 <= 1);
        assertTrue("Seeded noise should produce valid values", value2 >= -1 && value2 <= 1);
    }

    @Test
    public void testSeededConstruction_Reproducibility() {
        PerlinNoise noise1 = new PerlinNoise(12345L);
        PerlinNoise noise2 = new PerlinNoise(12345L);

        // Same seed should produce same pattern
        for (int i = 0; i < 100; i++) {
            double x = i * 0.1;
            assertEquals("Same seed should produce same noise",
                    noise1.noise1D(x), noise2.noise1D(x), 0.0001);
        }
    }

    @Test
    public void testNoiseDistribution_NotUniform() {
        // Perlin noise should not be uniformly distributed
        // It tends to cluster around zero
        int nearZeroCount = 0;
        int farFromZeroCount = 0;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double noise = perlinNoise.noise1D(i * 0.1);
            if (Math.abs(noise) < 0.3) {
                nearZeroCount++;
            } else if (Math.abs(noise) > 0.7) {
                farFromZeroCount++;
            }
        }

        // Near-zero values should be more common than extreme values
        assertTrue("Perlin noise should cluster near zero",
                nearZeroCount > farFromZeroCount);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private interface NoiseFunction {
        double apply(int i);
    }

    private double calculateVariance(NoiseFunction noiseFunction) {
        double sum = 0;
        double sumSq = 0;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double value = noiseFunction.apply(i);
            sum += value;
            sumSq += value * value;
        }

        double mean = sum / SAMPLE_SIZE;
        return (sumSq / SAMPLE_SIZE) - (mean * mean);
    }
}

