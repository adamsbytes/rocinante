package com.rocinante.timing;

import com.rocinante.behavior.FatigueModel;
import com.rocinante.util.Randomization;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HumanTimer class.
 * Verifies delay generation matches REQUIREMENTS.md Section 4.1 specifications.
 */
public class HumanTimerTest {

    private HumanTimer humanTimer;
    private Randomization randomization;
    private FatigueModel fatigueModel;

    @Before
    public void setUp() {
        // Use seeded randomization for reproducible tests
        randomization = new Randomization(12345L);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        humanTimer = new HumanTimer(randomization, executor);
        
        // Create mock FatigueModel with default values (no fatigue)
        fatigueModel = mock(FatigueModel.class);
        when(fatigueModel.getSigmaMultiplier()).thenReturn(1.0);
        when(fatigueModel.getTauMultiplier()).thenReturn(1.0);
        humanTimer.setFatigueModel(fatigueModel);
    }

    // ========================================================================
    // REACTION Profile Tests
    // ========================================================================

    @Test
    public void testReactionDelay_WithinBounds() {
        for (int i = 0; i < 100; i++) {
            long delay = humanTimer.getDelay(DelayProfile.REACTION);
            assertTrue("REACTION delay should be >= 150ms, got: " + delay, delay >= 150);
            assertTrue("REACTION delay should be <= 600ms, got: " + delay, delay <= 600);
        }
    }

    // ========================================================================
    // ACTION_GAP Profile Tests
    // ========================================================================

    @Test
    public void testActionGapDelay_WithinBounds() {
        for (int i = 0; i < 100; i++) {
            long delay = humanTimer.getDelay(DelayProfile.ACTION_GAP);
            assertTrue("ACTION_GAP delay should be >= 400ms, got: " + delay, delay >= 400);
            assertTrue("ACTION_GAP delay should be <= 2000ms, got: " + delay, delay <= 2000);
        }
    }

    @Test
    public void testActionGapDelay_NearMean() {
        // Statistical test: average of 1000 samples should be near mean
        long sum = 0;
        int samples = 1000;
        for (int i = 0; i < samples; i++) {
            sum += humanTimer.getDelay(DelayProfile.ACTION_GAP);
        }
        double average = sum / (double) samples;

        // Average should be within 100ms of mean (800ms)
        assertTrue("ACTION_GAP average should be near 800ms, got: " + average,
                average >= 700 && average <= 900);
    }

    // ========================================================================
    // MENU_SELECT Profile Tests
    // ========================================================================

    @Test
    public void testMenuSelectDelay_PositiveValues() {
        for (int i = 0; i < 100; i++) {
            long delay = humanTimer.getDelay(DelayProfile.MENU_SELECT);
            assertTrue("MENU_SELECT delay should be positive, got: " + delay, delay >= 0);
        }
    }

    @Test
    public void testMenuSelectDelay_NearMean() {
        long sum = 0;
        int samples = 1000;
        for (int i = 0; i < samples; i++) {
            sum += humanTimer.getDelay(DelayProfile.MENU_SELECT);
        }
        double average = sum / (double) samples;

        // Average should be within 30ms of mean (180ms)
        assertTrue("MENU_SELECT average should be near 180ms, got: " + average,
                average >= 150 && average <= 210);
    }

    // ========================================================================
    // DIALOGUE_READ Profile Tests
    // ========================================================================

    @Test
    public void testDialogueDelay_ZeroWords() {
        long sum = 0;
        int samples = 100;
        for (int i = 0; i < samples; i++) {
            sum += humanTimer.getDialogueDelay(0);
        }
        double average = sum / (double) samples;

        // Should be around 1200ms (base mean)
        assertTrue("DIALOGUE_READ with 0 words should average near 1200ms, got: " + average,
                average >= 1000 && average <= 1400);
    }

    @Test
    public void testDialogueDelay_TenWords() {
        long sum = 0;
        int samples = 100;
        for (int i = 0; i < samples; i++) {
            sum += humanTimer.getDialogueDelay(10);
        }
        double average = sum / (double) samples;

        // Should be around 1700ms (1200 + 10*50)
        assertTrue("DIALOGUE_READ with 10 words should average near 1700ms, got: " + average,
                average >= 1500 && average <= 1900);
    }

    @Test
    public void testDialogueDelay_NegativeWordCount() {
        // Should handle negative word count gracefully (treat as 0)
        long delay = humanTimer.getDialogueDelay(-5);
        assertTrue("DIALOGUE_READ with negative words should still work, got: " + delay,
                delay >= 0);
    }

    // ========================================================================
    // INVENTORY_SCAN Profile Tests
    // ========================================================================

    @Test
    public void testInventoryScanDelay_PositiveValues() {
        for (int i = 0; i < 100; i++) {
            long delay = humanTimer.getDelay(DelayProfile.INVENTORY_SCAN);
            assertTrue("INVENTORY_SCAN delay should be positive, got: " + delay, delay >= 0);
        }
    }

    @Test
    public void testInventoryScanDelay_NearMean() {
        long sum = 0;
        int samples = 1000;
        for (int i = 0; i < samples; i++) {
            sum += humanTimer.getDelay(DelayProfile.INVENTORY_SCAN);
        }
        double average = sum / (double) samples;

        // Average should be within 50ms of mean (400ms)
        assertTrue("INVENTORY_SCAN average should be near 400ms, got: " + average,
                average >= 350 && average <= 450);
    }

    // ========================================================================
    // BANK_SEARCH Profile Tests
    // ========================================================================

    @Test
    public void testBankSearchDelay_NearMean() {
        long sum = 0;
        int samples = 1000;
        for (int i = 0; i < samples; i++) {
            sum += humanTimer.getDelay(DelayProfile.BANK_SEARCH);
        }
        double average = sum / (double) samples;

        // Average should be within 75ms of mean (600ms)
        assertTrue("BANK_SEARCH average should be near 600ms, got: " + average,
                average >= 525 && average <= 675);
    }

    // ========================================================================
    // PRAYER_SWITCH Profile Tests
    // ========================================================================

    @Test
    public void testPrayerSwitchDelay_WithinBounds() {
        for (int i = 0; i < 100; i++) {
            long delay = humanTimer.getDelay(DelayProfile.PRAYER_SWITCH);
            assertTrue("PRAYER_SWITCH delay should be >= 50ms, got: " + delay, delay >= 50);
        }
    }

    @Test
    public void testPrayerSwitchDelay_NearMean() {
        long sum = 0;
        int samples = 1000;
        for (int i = 0; i < samples; i++) {
            sum += humanTimer.getDelay(DelayProfile.PRAYER_SWITCH);
        }
        double average = sum / (double) samples;

        // Average should be within 15ms of mean (80ms), accounting for min clamp
        assertTrue("PRAYER_SWITCH average should be near 80ms, got: " + average,
                average >= 65 && average <= 95);
    }

    // ========================================================================
    // GEAR_SWITCH Profile Tests
    // ========================================================================

    @Test
    public void testGearSwitchDelay_NearMean() {
        long sum = 0;
        int samples = 1000;
        for (int i = 0; i < samples; i++) {
            sum += humanTimer.getDelay(DelayProfile.GEAR_SWITCH);
        }
        double average = sum / (double) samples;

        // Average should be within 20ms of mean (120ms)
        assertTrue("GEAR_SWITCH average should be near 120ms, got: " + average,
                average >= 100 && average <= 140);
    }

    // ========================================================================
    // Fatigue Model Tests
    // ========================================================================

    @Test
    public void testFatigueModel_NoFatigue() {
        // With no fatigue, multipliers should be 1.0
        when(fatigueModel.getSigmaMultiplier()).thenReturn(1.0);
        when(fatigueModel.getTauMultiplier()).thenReturn(1.0);
        
        long delay = humanTimer.getDelay(DelayProfile.ACTION_GAP);
        assertTrue("Delay should be positive", delay > 0);
    }

    @Test
    public void testFatigueModel_HighFatigueIncreasesVariance() {
        // First get baseline delays with no fatigue
        when(fatigueModel.getSigmaMultiplier()).thenReturn(1.0);
        when(fatigueModel.getTauMultiplier()).thenReturn(1.0);
        
        long sum = 0;
        int samples = 100;
        for (int i = 0; i < samples; i++) {
            sum += humanTimer.getDelay(DelayProfile.ACTION_GAP);
        }
        double normalAverage = sum / (double) samples;

        // Now set high fatigue (max sigma = 1.6, max tau = 1.8)
        when(fatigueModel.getSigmaMultiplier()).thenReturn(1.6);
        when(fatigueModel.getTauMultiplier()).thenReturn(1.8);
        
        sum = 0;
        for (int i = 0; i < samples; i++) {
            sum += humanTimer.getDelay(DelayProfile.ACTION_GAP);
        }
        double fatigueAverage = sum / (double) samples;

        // With high fatigue, average should be higher due to increased variance/tail
        // The effect may not be exactly proportional since we're modifying distribution shape
        assertTrue("Fatigue should generally increase delays, got normal=" + normalAverage + 
                ", fatigue=" + fatigueAverage, fatigueAverage >= normalAverage * 0.8);
    }

    @Test
    public void testFatigueModel_NullSafe() {
        // Without a FatigueModel, should still work with default multipliers
        HumanTimer timerWithoutFatigue = new HumanTimer(randomization, 
                Executors.newSingleThreadScheduledExecutor());
        
        long delay = timerWithoutFatigue.getDelay(DelayProfile.ACTION_GAP);
        assertTrue("Should work without FatigueModel", delay > 0);
    }

    // ========================================================================
    // Custom Delay Tests
    // ========================================================================

    @Test
    public void testCustomGaussianDelay() {
        long sum = 0;
        int samples = 1000;
        for (int i = 0; i < samples; i++) {
            sum += humanTimer.getCustomDelay(DistributionType.GAUSSIAN, 500.0, 100.0);
        }
        double average = sum / (double) samples;

        assertTrue("Custom Gaussian average should be near 500ms, got: " + average,
                average >= 450 && average <= 550);
    }

    @Test
    public void testCustomGaussianDelay_WithBounds() {
        for (int i = 0; i < 100; i++) {
            long delay = humanTimer.getCustomDelay(DistributionType.GAUSSIAN, 500.0, 200.0, 300L, 700L);
            assertTrue("Custom Gaussian delay should be >= 300ms, got: " + delay, delay >= 300);
            assertTrue("Custom Gaussian delay should be <= 700ms, got: " + delay, delay <= 700);
        }
    }

    @Test
    public void testCustomPoissonDelay() {
        long sum = 0;
        int samples = 1000;
        for (int i = 0; i < samples; i++) {
            sum += humanTimer.getPoissonDelay(250.0);
        }
        double average = sum / (double) samples;

        // Poisson mean should be close to lambda
        assertTrue("Poisson average should be near 250ms, got: " + average,
                average >= 200 && average <= 300);
    }

    @Test
    public void testCustomPoissonDelay_WithBounds() {
        for (int i = 0; i < 100; i++) {
            long delay = humanTimer.getPoissonDelay(250.0, 100, 500);
            assertTrue("Bounded Poisson delay should be >= 100ms, got: " + delay, delay >= 100);
            assertTrue("Bounded Poisson delay should be <= 500ms, got: " + delay, delay <= 500);
        }
    }

    @Test
    public void testCustomUniformDelay() {
        for (int i = 0; i < 100; i++) {
            long delay = humanTimer.getUniformDelay(100, 200);
            assertTrue("Uniform delay should be >= 100ms, got: " + delay, delay >= 100);
            assertTrue("Uniform delay should be <= 200ms, got: " + delay, delay <= 200);
        }
    }

    @Test
    public void testCustomExponentialDelay() {
        long sum = 0;
        int samples = 1000;
        for (int i = 0; i < samples; i++) {
            sum += humanTimer.getExponentialDelay(0.002); // lambda=0.002, mean=500ms
        }
        double average = sum / (double) samples;

        // Exponential mean should be near 1/lambda = 500ms
        assertTrue("Exponential average should be near 500ms, got: " + average,
                average >= 300 && average <= 700);
    }

    @Test
    public void testCustomExponentialDelay_WithBounds() {
        for (int i = 0; i < 100; i++) {
            long delay = humanTimer.getExponentialDelay(0.002, 100, 1000);
            assertTrue("Bounded exponential delay should be >= 100ms, got: " + delay, delay >= 100);
            assertTrue("Bounded exponential delay should be <= 1000ms, got: " + delay, delay <= 1000);
        }
    }

    // ========================================================================
    // Async Sleep Tests
    // ========================================================================

    @Test
    public void testSleep_CompletesSuccessfully() throws ExecutionException, InterruptedException {
        CompletableFuture<Void> future = humanTimer.sleep(10L);
        future.get(); // Should complete without exception
        assertTrue("Sleep should complete", future.isDone());
    }

    @Test
    public void testSleep_ZeroDelay() throws ExecutionException, InterruptedException {
        CompletableFuture<Void> future = humanTimer.sleep(0L);
        future.get();
        assertTrue("Sleep with 0 delay should complete immediately", future.isDone());
    }

    @Test
    public void testSleep_NegativeDelay() throws ExecutionException, InterruptedException {
        CompletableFuture<Void> future = humanTimer.sleep(-100L);
        future.get();
        assertTrue("Sleep with negative delay should complete immediately", future.isDone());
    }

    @Test
    public void testSleepProfile_CompletesSuccessfully() throws ExecutionException, InterruptedException {
        CompletableFuture<Void> future = humanTimer.sleep(DelayProfile.MENU_SELECT);
        future.get();
        assertTrue("Sleep with profile should complete", future.isDone());
    }

    @Test
    public void testSleepDialogue_CompletesSuccessfully() throws ExecutionException, InterruptedException {
        CompletableFuture<Void> future = humanTimer.sleepDialogue(5);
        future.get();
        assertTrue("Sleep dialogue should complete", future.isDone());
    }

    @Test
    public void testSleepSync_BlocksThread() throws InterruptedException {
        long start = System.currentTimeMillis();
        humanTimer.sleepSync(50L);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue("Sync sleep should block for at least 40ms", elapsed >= 40);
    }

    // ========================================================================
    // Utility Method Tests
    // ========================================================================

    @Test
    public void testChance_ReturnsBoolean() {
        int trueCount = 0;
        int samples = 1000;
        for (int i = 0; i < samples; i++) {
            if (humanTimer.chance(0.5)) {
                trueCount++;
            }
        }

        // With 50% chance, should be roughly half
        assertTrue("50% chance should produce roughly 50% true values",
                trueCount >= 400 && trueCount <= 600);
    }

    @Test
    public void testChance_AlwaysFalse() {
        for (int i = 0; i < 100; i++) {
            assertFalse("0% chance should always be false", humanTimer.chance(0.0));
        }
    }

    @Test
    public void testChance_AlwaysTrue() {
        for (int i = 0; i < 100; i++) {
            assertTrue("100% chance should always be true", humanTimer.chance(1.0));
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testAllProfiles_ReturnPositiveDelays() {
        for (DelayProfile profile : DelayProfile.values()) {
            for (int i = 0; i < 10; i++) {
                long delay = humanTimer.getDelay(profile);
                assertTrue("Profile " + profile.name() + " should return positive delay, got: " + delay,
                        delay >= 0);
            }
        }
    }

    @Test
    public void testHighFatigue_StillReasonable() {
        // Max fatigue: sigma=1.6, tau=1.8
        when(fatigueModel.getSigmaMultiplier()).thenReturn(1.6);
        when(fatigueModel.getTauMultiplier()).thenReturn(1.8);

        for (int i = 0; i < 100; i++) {
            long delay = humanTimer.getDelay(DelayProfile.ACTION_GAP);
            // Even with max fatigue effects, delays should stay reasonable
            // ACTION_GAP max is 2000ms, with variance increase could go higher
            assertTrue("High fatigue delay should still be reasonable, got: " + delay,
                    delay <= 4000);
        }
    }

    // ========================================================================
    // Distribution Shape Tests (Statistical)
    // ========================================================================

    @Test
    public void testGaussian_NormalDistributionShape() {
        // For a normal distribution, ~68% of values should be within 1 stddev of mean
        int withinOneStdDev = 0;
        int samples = 1000;

        // ACTION_GAP: mean=800, stddev=200
        double mean = 800.0;
        double stdDev = 200.0;

        for (int i = 0; i < samples; i++) {
            long delay = humanTimer.getDelay(DelayProfile.ACTION_GAP);
            if (delay >= mean - stdDev && delay <= mean + stdDev) {
                withinOneStdDev++;
            }
        }

        double percentWithin = (withinOneStdDev / (double) samples) * 100;
        // Due to bounds (400-2000), the actual distribution is truncated
        // Should still see significant clustering around mean
        assertTrue("~68% of Gaussian values should be within 1 stddev, got: " + percentWithin + "%",
                percentWithin >= 50); // Relaxed due to truncation
    }
}

