package com.rocinante.behavior;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for TimingSelfMonitor.
 */
public class TimingSelfMonitorTest {

    private TimingSelfMonitor monitor;

    @Before
    public void setUp() {
        monitor = new TimingSelfMonitor();
    }

    @Test
    public void testRecordSamples_AccumulatesProperly() {
        // Record 60 samples
        for (int i = 0; i < 60; i++) {
            monitor.recordReactionTime(200 + i);
        }

        // Should have stats after 50+ samples
        TimingSelfMonitor.TimingStats stats = monitor.getStats(
                TimingSelfMonitor.TimingCategory.REACTION);
        assertNotNull(stats);
        assertEquals(60, stats.getSampleCount());
        
        // Mean should be around 229.5 ((200+259)/2)
        assertTrue(stats.getMean() > 220 && stats.getMean() < 240);
    }

    @Test
    public void testDetectsTooConsistentTiming() {
        // Record 100 samples with very low variance (all exactly the same)
        for (int i = 0; i < 100; i++) {
            monitor.recordReactionTime(200);
        }

        TimingSelfMonitor.TimingStats stats = monitor.getStats(
                TimingSelfMonitor.TimingCategory.REACTION);
        assertNotNull(stats);
        
        // With all same values, CV should be 0 (or very close)
        assertTrue(stats.isTooConsistent());
        assertEquals(0.0, stats.getCoefficientOfVariation(), 0.001);
    }

    @Test
    public void testDetectsNormalTiming() {
        // Record samples with moderate variance (human-like)
        // CV should be around 0.3-0.5 for reaction times
        for (int i = 0; i < 100; i++) {
            // Spread from 150 to 350 gives reasonable variance
            long sample = 150 + (long)(Math.random() * 200);
            monitor.recordReactionTime(sample);
        }

        TimingSelfMonitor.TimingStats stats = monitor.getStats(
                TimingSelfMonitor.TimingCategory.REACTION);
        assertNotNull(stats);
        
        // With uniform 150-350, CV should be around 0.28-0.35 (sqrt(variance)/mean for uniform)
        // Uniform CV = (max-min) / (sqrt(12) * (max+min)/2) ≈ 0.289 for 150-350
        assertTrue(stats.getCoefficientOfVariation() > 0.15);
        assertTrue(stats.getCoefficientOfVariation() < 1.0);
    }

    @Test
    public void testVarianceBoostIncreasesOnConsistentTiming() {
        // Initial boost should be 0
        assertEquals(0.0, monitor.getCurrentVarianceBoost(), 0.001);
        assertEquals(1.0, monitor.getVarianceMultiplier(), 0.001);

        // Record 100 identical samples to trigger consistency warning
        for (int i = 0; i < 100; i++) {
            monitor.recordReactionTime(200);
        }

        // Analysis should have detected consistency and boosted variance
        assertTrue(monitor.getCurrentVarianceBoost() > 0);
        assertTrue(monitor.getVarianceMultiplier() > 1.0);
    }

    @Test
    public void testCircularBuffer_HandlesOverflow() {
        // Record more than buffer size (100 samples)
        for (int i = 0; i < 150; i++) {
            monitor.recordClickDuration(80 + (i % 20));
        }

        // Should still work, keeping last 100 samples
        TimingSelfMonitor.TimingStats stats = monitor.getStats(
                TimingSelfMonitor.TimingCategory.CLICK_DURATION);
        assertNotNull(stats);
        assertEquals(100, stats.getSampleCount());
    }

    @Test
    public void testReset_ClearsAllState() {
        // Record some samples and trigger boost
        for (int i = 0; i < 100; i++) {
            monitor.recordReactionTime(200);
        }
        assertTrue(monitor.getCurrentVarianceBoost() > 0);

        // Reset
        monitor.reset();

        // All state should be cleared
        assertEquals(0.0, monitor.getCurrentVarianceBoost(), 0.001);
        assertNull(monitor.getStats(TimingSelfMonitor.TimingCategory.REACTION));
    }

    @Test
    public void testDisabled_DoesNotRecordSamples() {
        monitor.setEnabled(false);

        // Record samples while disabled
        for (int i = 0; i < 100; i++) {
            monitor.recordReactionTime(200 + i);
        }

        // Should have no stats
        assertNull(monitor.getStats(TimingSelfMonitor.TimingCategory.REACTION));
    }

    @Test
    public void testDifferentCategories_TrackedSeparately() {
        // Record to different categories
        for (int i = 0; i < 60; i++) {
            monitor.recordReactionTime(200 + i);
            monitor.recordClickDuration(80 + (i % 10));
            monitor.recordActionInterval(500 + i * 2);
        }

        // Each category should have its own stats
        TimingSelfMonitor.TimingStats reactionStats = monitor.getStats(
                TimingSelfMonitor.TimingCategory.REACTION);
        TimingSelfMonitor.TimingStats clickStats = monitor.getStats(
                TimingSelfMonitor.TimingCategory.CLICK_DURATION);
        TimingSelfMonitor.TimingStats actionStats = monitor.getStats(
                TimingSelfMonitor.TimingCategory.ACTION_INTERVAL);

        assertNotNull(reactionStats);
        assertNotNull(clickStats);
        assertNotNull(actionStats);

        // Means should be different
        assertTrue(reactionStats.getMean() > 220);
        assertTrue(clickStats.getMean() < 100);
        assertTrue(actionStats.getMean() > 500);
    }

    @Test
    public void testSummary_ContainsAllCategories() {
        // Record some samples
        for (int i = 0; i < 60; i++) {
            monitor.recordReactionTime(200 + i);
        }

        String summary = monitor.getSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("REACTION"));
        assertTrue(summary.contains("Variance Boost"));
    }

    @Test
    public void testTotalAnomaliesTracking() {
        assertEquals(0, monitor.getTotalAnomaliesDetected().get());

        // Trigger anomaly with consistent timing
        for (int i = 0; i < 100; i++) {
            monitor.recordReactionTime(200);
        }

        // Should have detected at least one anomaly
        assertTrue(monitor.getTotalAnomaliesDetected().get() > 0);
    }

    @Test
    public void testIgnoresZeroAndNegativeTimings() {
        monitor.recordReactionTime(0);
        monitor.recordReactionTime(-100);
        
        // Should not have any samples
        assertNull(monitor.getStats(TimingSelfMonitor.TimingCategory.REACTION));
    }

    @Test
    public void testTimingStats_MinMax() {
        // Record samples with known min/max
        monitor.recordReactionTime(100);
        monitor.recordReactionTime(200);
        monitor.recordReactionTime(300);
        // Need 50 samples minimum
        for (int i = 0; i < 50; i++) {
            monitor.recordReactionTime(200);
        }

        TimingSelfMonitor.TimingStats stats = monitor.getStats(
                TimingSelfMonitor.TimingCategory.REACTION);
        assertNotNull(stats);
        assertEquals(100L, stats.getMin());
        assertEquals(300L, stats.getMax());
    }
}
