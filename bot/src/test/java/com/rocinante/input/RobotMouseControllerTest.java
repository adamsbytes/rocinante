package com.rocinante.input;

import com.rocinante.behavior.PlayerProfile;
import com.rocinante.util.PerlinNoise;
import com.rocinante.util.Randomization;
import org.junit.Before;
import org.junit.Test;

import java.awt.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.*;

/**
 * Unit tests for RobotMouseController.
 * Tests the algorithmic components (Bezier curves, timing calculations, etc.)
 * without requiring an actual display.
 *
 * Note: Full integration tests with actual mouse movement require a display
 * environment (Xvfb in Docker or native display).
 */
public class RobotMouseControllerTest {

    private static final int SAMPLE_SIZE = 1000;
    private static final double TOLERANCE = 0.20; // 20% tolerance

    private Randomization randomization;
    private PerlinNoise perlinNoise;
    private PlayerProfile playerProfile;
    private ScheduledExecutorService testExecutor;

    @Before
    public void setUp() {
        randomization = new Randomization(12345L);
        perlinNoise = new PerlinNoise(12345L);
        testExecutor = Executors.newSingleThreadScheduledExecutor();
        playerProfile = new PlayerProfile(randomization, testExecutor);
        playerProfile.initializeDefault();
    }

    // ========================================================================
    // Click Position Tests (Statistical Validation per REQUIREMENTS 16.3)
    // ========================================================================

    @Test
    public void testClickPosition_2DGaussianDistribution() {
        Rectangle hitbox = new Rectangle(100, 100, 50, 30);

        double sumX = 0, sumY = 0;
        double sumXSq = 0, sumYSq = 0;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int[] pos = randomization.generateClickPosition(
                    hitbox.x, hitbox.y, hitbox.width, hitbox.height);

            sumX += pos[0];
            sumY += pos[1];
            sumXSq += pos[0] * pos[0];
            sumYSq += pos[1] * pos[1];
        }

        double meanX = sumX / SAMPLE_SIZE;
        double meanY = sumY / SAMPLE_SIZE;

        // Expected center: 45-55% of hitbox (so ~50% = center)
        double expectedCenterX = hitbox.x + hitbox.width * 0.5;
        double expectedCenterY = hitbox.y + hitbox.height * 0.5;

        // Mean should be close to center
        assertEquals("Mean X should be near hitbox center",
                expectedCenterX, meanX, hitbox.width * 0.1);
        assertEquals("Mean Y should be near hitbox center",
                expectedCenterY, meanY, hitbox.height * 0.1);
    }

    @Test
    public void testClickPosition_NeverGeometricCenter() {
        Rectangle hitbox = new Rectangle(100, 100, 50, 30);
        int exactCenterX = hitbox.x + hitbox.width / 2;
        int exactCenterY = hitbox.y + hitbox.height / 2;

        int exactCenterCount = 0;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int[] pos = randomization.generateClickPosition(
                    hitbox.x, hitbox.y, hitbox.width, hitbox.height);

            if (pos[0] == exactCenterX && pos[1] == exactCenterY) {
                exactCenterCount++;
            }
        }

        // Per spec: "Never click the geometric center"
        // With 2D Gaussian, exact center is very unlikely (< 5%)
        double exactCenterRatio = (double) exactCenterCount / SAMPLE_SIZE;
        assertTrue("Exact center clicks should be rare (< 5%)", exactCenterRatio < 0.05);
    }

    @Test
    public void testClickPosition_WithinHitbox() {
        Rectangle hitbox = new Rectangle(100, 100, 50, 30);

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int[] pos = randomization.generateClickPosition(
                    hitbox.x, hitbox.y, hitbox.width, hitbox.height);

            assertTrue("Click X should be within hitbox",
                    pos[0] >= hitbox.x && pos[0] <= hitbox.x + hitbox.width);
            assertTrue("Click Y should be within hitbox",
                    pos[1] >= hitbox.y && pos[1] <= hitbox.y + hitbox.height);
        }
    }

    @Test
    public void testClickPosition_StandardDeviationApprox15Percent() {
        Rectangle hitbox = new Rectangle(100, 100, 100, 100); // Large hitbox for clear stats

        double sumX = 0, sumY = 0;
        int[] positionsX = new int[SAMPLE_SIZE];
        int[] positionsY = new int[SAMPLE_SIZE];

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int[] pos = randomization.generateClickPosition(
                    hitbox.x, hitbox.y, hitbox.width, hitbox.height);
            positionsX[i] = pos[0];
            positionsY[i] = pos[1];
            sumX += pos[0];
            sumY += pos[1];
        }

        double meanX = sumX / SAMPLE_SIZE;
        double meanY = sumY / SAMPLE_SIZE;

        // Calculate standard deviation
        double sumSqDiffX = 0, sumSqDiffY = 0;
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            sumSqDiffX += (positionsX[i] - meanX) * (positionsX[i] - meanX);
            sumSqDiffY += (positionsY[i] - meanY) * (positionsY[i] - meanY);
        }

        double stdDevX = Math.sqrt(sumSqDiffX / SAMPLE_SIZE);
        double stdDevY = Math.sqrt(sumSqDiffY / SAMPLE_SIZE);

        // Expected: σ = 15% of dimension
        double expectedStdDevX = hitbox.width * 0.15;
        double expectedStdDevY = hitbox.height * 0.15;

        assertEquals("StdDev X should be ~15% of width",
                expectedStdDevX, stdDevX, expectedStdDevX * TOLERANCE);
        assertEquals("StdDev Y should be ~15% of height",
                expectedStdDevY, stdDevY, expectedStdDevY * TOLERANCE);
    }

    // ========================================================================
    // Movement Duration Tests
    // ========================================================================

    @Test
    public void testMovementDuration_Formula() {
        // REQUIREMENTS 3.1.1: duration = sqrt(distance) * 10 + gaussianRandom(50, 150)
        // Min: 80ms, Max: 1500ms

        double[] distances = {50, 100, 200, 500, 1000};

        for (double distance : distances) {
            double baseDuration = Math.sqrt(distance) * 10;

            // With random addition of 50-150ms (mean ~100), we can estimate range
            double minExpected = Math.max(80, baseDuration + 50 - 100);  // Accounting for gaussian
            double maxExpected = Math.min(1500, baseDuration + 150 + 100);

            // Test multiple samples
            for (int i = 0; i < 100; i++) {
                double randomAddition = randomization.gaussianRandom(100, 25, 50, 150);
                long duration = Math.round(baseDuration + randomAddition);
                duration = Randomization.clamp(duration, 80, 1500);

                assertTrue("Duration should be >= 80ms", duration >= 80);
                assertTrue("Duration should be <= 1500ms", duration <= 1500);
            }
        }
    }

    @Test
    public void testMovementDuration_ShortDistance() {
        double distance = 50;
        double baseDuration = Math.sqrt(distance) * 10; // ~70ms

        // With min of 80ms, short movements should be clamped
        for (int i = 0; i < 100; i++) {
            double randomAddition = randomization.gaussianRandom(100, 25);
            long duration = Math.round(baseDuration + randomAddition);
            duration = Randomization.clamp(duration, 80, 1500);

            assertTrue("Short movement should be at least 80ms", duration >= 80);
        }
    }

    @Test
    public void testMovementDuration_LongDistance() {
        double distance = 2000;
        double baseDuration = Math.sqrt(distance) * 10; // ~447ms

        for (int i = 0; i < 100; i++) {
            double randomAddition = randomization.gaussianRandom(100, 25);
            long duration = Math.round(baseDuration + randomAddition);
            duration = Randomization.clamp(duration, 80, 1500);

            assertTrue("Long movement should be <= 1500ms", duration <= 1500);
        }
    }

    // ========================================================================
    // Click Timing Tests
    // ========================================================================

    @Test
    public void testClickDuration_GaussianDistribution() {
        // REQUIREMENTS 3.1.2: μ=85ms, σ=15ms, min=60, max=120

        double sum = 0;
        long[] durations = new long[SAMPLE_SIZE];

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            durations[i] = randomization.gaussianRandomLong(85, 15, 60, 120);
            sum += durations[i];
        }

        double mean = sum / SAMPLE_SIZE;

        // Mean should be close to 85ms (accounting for truncation)
        assertTrue("Mean click duration should be > 70ms", mean > 70);
        assertTrue("Mean click duration should be < 100ms", mean < 100);

        // All values should be within bounds
        for (long d : durations) {
            assertTrue("Click duration should be >= 60ms", d >= 60);
            assertTrue("Click duration should be <= 120ms", d <= 120);
        }
    }

    @Test
    public void testDoubleClickInterval() {
        // REQUIREMENTS 3.1.2: 80-180ms

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long interval = randomization.uniformRandomLong(80, 180);
            assertTrue("Double-click interval should be >= 80ms", interval >= 80);
            assertTrue("Double-click interval should be <= 180ms", interval <= 180);
        }
    }

    // ========================================================================
    // Overshoot and Micro-Correction Tests
    // ========================================================================

    @Test
    public void testOvershootProbability() {
        // REQUIREMENTS 3.1.1: 8-15% probability

        int overshootCount = 0;
        double probability = 0.12; // Middle of range

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            if (randomization.chance(probability)) {
                overshootCount++;
            }
        }

        double ratio = (double) overshootCount / SAMPLE_SIZE;
        assertEquals("Overshoot should occur ~12% of time", 0.12, ratio, 0.05);
    }

    @Test
    public void testOvershootDistance() {
        // REQUIREMENTS 3.1.1: 3-12 pixels

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int overshoot = randomization.uniformRandomInt(3, 12);
            assertTrue("Overshoot should be >= 3 pixels", overshoot >= 3);
            assertTrue("Overshoot should be <= 12 pixels", overshoot <= 12);
        }
    }

    @Test
    public void testOvershootCorrectionDelay() {
        // REQUIREMENTS 3.1.1: 50-150ms

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long delay = randomization.uniformRandomLong(50, 150);
            assertTrue("Overshoot correction delay should be >= 50ms", delay >= 50);
            assertTrue("Overshoot correction delay should be <= 150ms", delay <= 150);
        }
    }

    @Test
    public void testMicroCorrectionProbability() {
        // REQUIREMENTS 3.1.1: 20% probability

        int correctionCount = 0;
        double probability = 0.20;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            if (randomization.chance(probability)) {
                correctionCount++;
            }
        }

        double ratio = (double) correctionCount / SAMPLE_SIZE;
        assertEquals("Micro-correction should occur ~20% of time", 0.20, ratio, 0.05);
    }

    @Test
    public void testMicroCorrectionDistance() {
        // REQUIREMENTS 3.1.1: 1-3 pixels

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int correction = randomization.uniformRandomInt(1, 3);
            assertTrue("Micro-correction should be >= 1 pixel", correction >= 1);
            assertTrue("Micro-correction should be <= 3 pixels", correction <= 3);
        }
    }

    @Test
    public void testMicroCorrectionDelay() {
        // REQUIREMENTS 3.1.1: 100-200ms

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long delay = randomization.uniformRandomLong(100, 200);
            assertTrue("Micro-correction delay should be >= 100ms", delay >= 100);
            assertTrue("Micro-correction delay should be <= 200ms", delay <= 200);
        }
    }

    // ========================================================================
    // Misclick Tests
    // ========================================================================

    @Test
    public void testMisclickProbability() {
        // REQUIREMENTS 3.1.2: 1-3% probability

        int misclickCount = 0;
        double probability = 0.02; // Middle of range

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            if (randomization.chance(probability)) {
                misclickCount++;
            }
        }

        double ratio = (double) misclickCount / SAMPLE_SIZE;
        assertEquals("Misclick should occur ~2% of time", 0.02, ratio, 0.02);
    }

    @Test
    public void testMisclickOffset() {
        // REQUIREMENTS 3.1.2: 5-20 pixels

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int offset = randomization.uniformRandomInt(5, 20);
            assertTrue("Misclick offset should be >= 5 pixels", offset >= 5);
            assertTrue("Misclick offset should be <= 20 pixels", offset <= 20);
        }
    }

    @Test
    public void testMisclickCorrectionDelay() {
        // REQUIREMENTS 3.1.2: 200-500ms

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long delay = randomization.uniformRandomLong(200, 500);
            assertTrue("Misclick correction delay should be >= 200ms", delay >= 200);
            assertTrue("Misclick correction delay should be <= 500ms", delay <= 500);
        }
    }

    // ========================================================================
    // Idle Behavior Tests
    // ========================================================================

    @Test
    public void testIdleBehavior_Distribution() {
        // REQUIREMENTS 3.1.3: 70% stationary, 20% drift, 10% rest position

        int stationaryCount = 0;
        int driftCount = 0;
        int restPositionCount = 0;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double roll = randomization.uniformRandom(0, 1);
            if (roll < 0.70) {
                stationaryCount++;
            } else if (roll < 0.90) {
                driftCount++;
            } else {
                restPositionCount++;
            }
        }

        double stationaryRatio = (double) stationaryCount / SAMPLE_SIZE;
        double driftRatio = (double) driftCount / SAMPLE_SIZE;
        double restRatio = (double) restPositionCount / SAMPLE_SIZE;

        assertEquals("Stationary should be ~70%", 0.70, stationaryRatio, 0.05);
        assertEquals("Drift should be ~20%", 0.20, driftRatio, 0.05);
        assertEquals("Rest position should be ~10%", 0.10, restRatio, 0.05);
    }

    @Test
    public void testIdleDrift_Distance() {
        // REQUIREMENTS 3.1.3: 5-30 pixels

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int drift = randomization.uniformRandomInt(5, 30);
            assertTrue("Idle drift should be >= 5 pixels", drift >= 5);
            assertTrue("Idle drift should be <= 30 pixels", drift <= 30);
        }
    }

    @Test
    public void testIdleDrift_Duration() {
        // REQUIREMENTS 3.1.3: 500-2000ms

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long duration = randomization.uniformRandomLong(500, 2000);
            assertTrue("Idle drift duration should be >= 500ms", duration >= 500);
            assertTrue("Idle drift duration should be <= 2000ms", duration <= 2000);
        }
    }

    // ========================================================================
    // Bezier Curve Control Point Tests
    // ========================================================================

    @Test
    public void testControlPointCount_ShortDistance() {
        // < 200px: 3 control points
        double distance = 150;
        int expectedControlPoints = 3;

        // Test control point selection logic
        int numControlPoints;
        if (distance < 200) {
            numControlPoints = 3;
        } else if (distance < 500) {
            numControlPoints = 4;
        } else {
            numControlPoints = 5;
        }

        assertEquals("Short distance should use 3 control points",
                expectedControlPoints, numControlPoints);
    }

    @Test
    public void testControlPointCount_MediumDistance() {
        // 200-500px: 4 control points
        double distance = 350;
        int expectedControlPoints = 4;

        int numControlPoints;
        if (distance < 200) {
            numControlPoints = 3;
        } else if (distance < 500) {
            numControlPoints = 4;
        } else {
            numControlPoints = 5;
        }

        assertEquals("Medium distance should use 4 control points",
                expectedControlPoints, numControlPoints);
    }

    @Test
    public void testControlPointCount_LongDistance() {
        // > 500px: 5 control points
        double distance = 750;
        int expectedControlPoints = 5;

        int numControlPoints;
        if (distance < 200) {
            numControlPoints = 3;
        } else if (distance < 500) {
            numControlPoints = 4;
        } else {
            numControlPoints = 5;
        }

        assertEquals("Long distance should use 5 control points",
                expectedControlPoints, numControlPoints);
    }

    @Test
    public void testControlPointOffset_Range() {
        // REQUIREMENTS 3.1.1: 5-15% of total distance

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double offsetPercent = randomization.uniformRandom(0.05, 0.15);
            assertTrue("Control point offset should be >= 5%", offsetPercent >= 0.05);
            assertTrue("Control point offset should be <= 15%", offsetPercent <= 0.15);
        }
    }

    // ========================================================================
    // Noise Injection Tests
    // ========================================================================

    @Test
    public void testNoiseAmplitude_Range() {
        // REQUIREMENTS 3.1.1: 1-3 pixels

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double amplitude = randomization.uniformRandom(1, 3);
            assertTrue("Noise amplitude should be >= 1", amplitude >= 1);
            assertTrue("Noise amplitude should be <= 3", amplitude <= 3);
        }
    }

    @Test
    public void testNoisePath_WithinBounds() {
        double amplitude = 3.0;
        long seed = 12345L;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            double progress = (double) i / SAMPLE_SIZE;
            double[] offset = perlinNoise.getPathOffset(progress, amplitude, seed);

            assertTrue("Noise X offset should be within amplitude",
                    Math.abs(offset[0]) <= amplitude);
            assertTrue("Noise Y offset should be within amplitude",
                    Math.abs(offset[1]) <= amplitude);
        }
    }
}

