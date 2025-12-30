package com.rocinante.behavior;

import com.rocinante.util.Randomization;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InefficiencyInjector.
 * Tests probability distributions and inefficiency injection behaviors.
 *
 * Uses a controllable clock to test time-dependent behavior without Thread.sleep().
 */
public class InefficiencyInjectorTest {

    private Randomization randomization;
    private InefficiencyInjector injector;
    private MutableClock testClock;

    @Mock
    private FatigueModel fatigueModel;

    /**
     * A mutable clock for testing time-dependent behavior.
     * Allows advancing time programmatically without actual delays.
     */
    private static class MutableClock extends Clock {
        private Instant currentInstant;
        private final ZoneId zone;

        MutableClock(Instant startInstant, ZoneId zone) {
            this.currentInstant = startInstant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(currentInstant, zone);
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }

        /**
         * Advance the clock by the specified duration.
         */
        void advance(Duration duration) {
            currentInstant = currentInstant.plus(duration);
        }

        /**
         * Advance the clock past the anti-clustering interval (31 seconds).
         * This ensures the next inefficiency check isn't blocked by the time guard.
         */
        void advancePastClusteringInterval() {
            advance(Duration.ofSeconds(31));
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        testClock = new MutableClock(Instant.now(), ZoneId.systemDefault());
        randomization = new Randomization(12345L);
        injector = new InefficiencyInjector(randomization, testClock);
    }

    @Test
    public void testIsEnabled_DefaultTrue() {
        assertTrue(injector.isEnabled());
    }

    @Test
    public void testSetEnabled_ChangesState() {
        injector.setEnabled(false);
        assertFalse(injector.isEnabled());

        injector.setEnabled(true);
        assertTrue(injector.isEnabled());
    }

    @Test
    public void testShouldBacktrack_WhenDisabled_ReturnsFalse() {
        injector.setEnabled(false);

        for (int i = 0; i < 100; i++) {
            testClock.advancePastClusteringInterval();
            assertFalse(injector.shouldBacktrack());
        }
    }

    @Test
    public void testShouldBacktrack_ApproximatelyTwoPercent() {
        // 2% base rate, run many trials with clock advancing past the clustering interval
        int backtrackCount = 0;
        int trials = 1000;

        for (int i = 0; i < trials; i++) {
            // Advance time past clustering interval so each check gets a fair probability test
            testClock.advancePastClusteringInterval();
            if (injector.shouldBacktrack()) {
                backtrackCount++;
            }
        }

        // With 2% rate over 1000 trials, expect ~20 (±statistical variance)
        // Use wide bounds to avoid flaky tests while still catching gross errors
        double rate = (double) backtrackCount / trials;
        assertTrue("Backtrack rate (" + rate + ") should be > 0.5%", rate > 0.005);
        assertTrue("Backtrack rate (" + rate + ") should be < 10%", rate < 0.10);
    }

    @Test
    public void testShouldBacktrack_ClusteringPrevention() {
        // First call should have a chance to succeed
        boolean firstResult = injector.shouldBacktrack();

        // Immediately call again without advancing time - should be blocked by clustering prevention
        boolean secondResult = injector.shouldBacktrack();
        assertFalse("Second call within clustering interval should return false", secondResult);

        // Advance time past interval
        testClock.advancePastClusteringInterval();

        // Now we have a chance again (may or may not succeed based on probability)
        // Just verify it doesn't throw and can potentially succeed
        injector.shouldBacktrack(); // No assertion - just verify no exception
    }

    @Test
    public void testGetBacktrackDistance_ReturnsTwoToTen() {
        for (int i = 0; i < 100; i++) {
            int distance = injector.getBacktrackDistance();
            assertTrue("Backtrack distance should be 2 to 10", distance >= 2 && distance <= 10);
        }
    }

    @Test
    public void testShouldHesitate_WhenDisabled_ReturnsFalse() {
        injector.setEnabled(false);

        for (int i = 0; i < 100; i++) {
            testClock.advance(Duration.ofSeconds(11)); // Past hesitation interval
            assertFalse(injector.shouldHesitate());
        }
    }

    @Test
    public void testShouldHesitate_ApproximatelyFivePercent() {
        // 5% base rate, run many trials
        int hesitationCount = 0;
        int trials = 1000;

        for (int i = 0; i < trials; i++) {
            // Advance time past hesitation clustering interval (10 seconds)
            testClock.advance(Duration.ofSeconds(11));
            if (injector.shouldHesitate()) {
                hesitationCount++;
            }
        }

        // With 5% rate over 1000 trials, expect ~50
        double rate = (double) hesitationCount / trials;
        assertTrue("Hesitation rate (" + rate + ") should be > 1%", rate > 0.01);
        assertTrue("Hesitation rate (" + rate + ") should be < 15%", rate < 0.15);
    }

    @Test
    public void testGetHesitationDelay_WithinBounds() {
        for (int i = 0; i < 100; i++) {
            long delay = injector.getHesitationDelay();
            assertTrue("Hesitation delay should be >= 500ms", delay >= 500);
            assertTrue("Hesitation delay should be <= 1500ms", delay <= 1500);
        }
    }

    @Test
    public void testGetAdjustedHesitationDelay_WithFatigue_IsLonger() {
        // Set up fatigue model with 50% fatigue
        when(fatigueModel.getFatigueLevel()).thenReturn(0.5);
        injector.setFatigueModel(fatigueModel);

        // Collect samples with and without fatigue
        long baseTotalDelay = 0;
        long fatigueTotalDelay = 0;
        int samples = 100;

        for (int i = 0; i < samples; i++) {
            baseTotalDelay += injector.getHesitationDelay();
            fatigueTotalDelay += injector.getAdjustedHesitationDelay();
        }

        // Average adjusted delay should be higher due to fatigue multiplier
        assertTrue("Fatigued hesitation should be longer than base",
                fatigueTotalDelay > baseTotalDelay);
    }

    @Test
    public void testShouldPerformRedundantAction_WhenDisabled_ReturnsFalse() {
        injector.setEnabled(false);

        for (int i = 0; i < 100; i++) {
            testClock.advancePastClusteringInterval();
            assertFalse(injector.shouldPerformRedundantAction());
        }
    }

    @Test
    public void testShouldPerformRedundantAction_ApproximatelyThreePercent() {
        // 3% base rate, run many trials
        int redundantCount = 0;
        int trials = 1000;

        for (int i = 0; i < trials; i++) {
            testClock.advancePastClusteringInterval();
            if (injector.shouldPerformRedundantAction()) {
                redundantCount++;
            }
        }

        // With 3% rate over 1000 trials, expect ~30
        double rate = (double) redundantCount / trials;
        assertTrue("Redundant action rate (" + rate + ") should be > 0.5%", rate > 0.005);
        assertTrue("Redundant action rate (" + rate + ") should be < 12%", rate < 0.12);
    }

    @Test
    public void testGetRedundantRepetitions_ReturnsOneOrTwo() {
        for (int i = 0; i < 100; i++) {
            int reps = injector.getRedundantRepetitions();
            assertTrue("Redundant reps should be 1 or 2", reps >= 1 && reps <= 2);
        }
    }

    @Test
    public void testShouldCancelAction_WhenDisabled_ReturnsFalse() {
        injector.setEnabled(false);

        for (int i = 0; i < 100; i++) {
            testClock.advancePastClusteringInterval();
            assertFalse(injector.shouldCancelAction());
        }
    }

    @Test
    public void testShouldCancelAction_ApproximatelyOnePercent() {
        // 1% base rate, run many trials
        int cancelCount = 0;
        int trials = 2000; // More trials for 1% rate

        for (int i = 0; i < trials; i++) {
            testClock.advancePastClusteringInterval();
            if (injector.shouldCancelAction()) {
                cancelCount++;
            }
        }

        // With 1% rate over 2000 trials, expect ~20
        double rate = (double) cancelCount / trials;
        assertTrue("Cancel action rate (" + rate + ") should be > 0.1%", rate > 0.001);
        assertTrue("Cancel action rate (" + rate + ") should be < 8%", rate < 0.08);
    }

    @Test
    public void testGetCancellationDelay_WithinBounds() {
        for (int i = 0; i < 100; i++) {
            long delay = injector.getCancellationDelay();
            assertTrue("Cancellation delay should be >= 1000ms", delay >= 1000);
            assertTrue("Cancellation delay should be <= 3000ms", delay <= 3000);
        }
    }

    @Test
    public void testCheckPreClickInefficiency_WhenDisabled_ReturnsNone() {
        injector.setEnabled(false);

        InefficiencyInjector.InefficiencyResult result = injector.checkPreClickInefficiency();

        assertFalse(result.isPresent());
        assertEquals(InefficiencyInjector.InefficiencyType.NONE, result.getType());
    }

    @Test
    public void testCheckPostWalkInefficiency_WhenDisabled_ReturnsNone() {
        injector.setEnabled(false);

        InefficiencyInjector.InefficiencyResult result = injector.checkPostWalkInefficiency();

        assertFalse(result.isPresent());
        assertEquals(InefficiencyInjector.InefficiencyType.NONE, result.getType());
    }

    @Test
    public void testCheckBankInefficiency_WhenDisabled_ReturnsNone() {
        injector.setEnabled(false);

        InefficiencyInjector.InefficiencyResult result = injector.checkBankInefficiency();

        assertFalse(result.isPresent());
        assertEquals(InefficiencyInjector.InefficiencyType.NONE, result.getType());
    }

    @Test
    public void testCheckPreClickInefficiency_CanReturnHesitation() {
        // Run enough trials to likely get a hesitation result
        boolean foundHesitation = false;
        for (int i = 0; i < 100; i++) {
            testClock.advancePastClusteringInterval();
            InefficiencyInjector.InefficiencyResult result = injector.checkPreClickInefficiency();
            if (result.getType() == InefficiencyInjector.InefficiencyType.HESITATION) {
                foundHesitation = true;
                assertTrue("Hesitation delay should be positive", result.getDelayMs() > 0);
                break;
            }
        }
        assertTrue("Should eventually get a hesitation result", foundHesitation);
    }

    @Test
    public void testCheckPostWalkInefficiency_CanReturnBacktrack() {
        // Run enough trials to likely get a backtrack result
        boolean foundBacktrack = false;
        for (int i = 0; i < 200; i++) {
            testClock.advancePastClusteringInterval();
            InefficiencyInjector.InefficiencyResult result = injector.checkPostWalkInefficiency();
            if (result.getType() == InefficiencyInjector.InefficiencyType.BACKTRACK) {
                foundBacktrack = true;
                assertTrue("Backtrack amount should be 2-10", 
                        result.getAmount() >= 2 && result.getAmount() <= 10);
                break;
            }
        }
        assertTrue("Should eventually get a backtrack result", foundBacktrack);
    }

    @Test
    public void testCheckBankInefficiency_CanReturnRedundantAction() {
        // Run enough trials to likely get a redundant action result
        boolean foundRedundant = false;
        for (int i = 0; i < 150; i++) {
            testClock.advancePastClusteringInterval();
            InefficiencyInjector.InefficiencyResult result = injector.checkBankInefficiency();
            if (result.getType() == InefficiencyInjector.InefficiencyType.REDUNDANT_ACTION) {
                foundRedundant = true;
                assertTrue("Redundant amount should be 1 or 2", 
                        result.getAmount() >= 1 && result.getAmount() <= 2);
                break;
            }
        }
        assertTrue("Should eventually get a redundant action result", foundRedundant);
    }

    @Test
    public void testInefficiencyResult_None_IsNotPresent() {
        InefficiencyInjector.InefficiencyResult result = InefficiencyInjector.InefficiencyResult.none();

        assertFalse(result.isPresent());
        assertEquals(InefficiencyInjector.InefficiencyType.NONE, result.getType());
        assertEquals(0, result.getDelayMs());
        assertEquals(0, result.getAmount());
    }

    @Test
    public void testInefficiencyResult_WithType_IsPresent() {
        InefficiencyInjector.InefficiencyResult result =
            new InefficiencyInjector.InefficiencyResult(
                InefficiencyInjector.InefficiencyType.HESITATION, 1000, 0);

        assertTrue(result.isPresent());
        assertEquals(InefficiencyInjector.InefficiencyType.HESITATION, result.getType());
        assertEquals(1000, result.getDelayMs());
    }

    @Test
    public void testResetCounters_ClearsAllCounts() {
        // Trigger some events by advancing time and calling methods
        for (int i = 0; i < 100; i++) {
            testClock.advancePastClusteringInterval();
            injector.shouldBacktrack();
            injector.shouldHesitate();
            injector.shouldPerformRedundantAction();
            injector.shouldCancelAction();
        }

        // Reset
        injector.resetCounters();

        // All counts should be 0
        assertEquals(0, injector.getBacktrackCount());
        assertEquals(0, injector.getHesitationCount());
        assertEquals(0, injector.getRedundantActionCount());
        assertEquals(0, injector.getActionCancelCount());
        assertEquals(0, injector.getTotalInefficiencyCount());
    }

    @Test
    public void testCounters_IncrementOnSuccess() {
        // Reset to start fresh
        injector.resetCounters();

        // Trigger enough calls to likely get some successes
        for (int i = 0; i < 200; i++) {
            testClock.advancePastClusteringInterval();
            injector.shouldBacktrack();
            injector.shouldHesitate();
            injector.shouldPerformRedundantAction();
            injector.shouldCancelAction();
        }

        // Total should be sum of individual counts
        long total = injector.getTotalInefficiencyCount();
        long sum = injector.getBacktrackCount() + injector.getHesitationCount()
                 + injector.getRedundantActionCount() + injector.getActionCancelCount();

        assertEquals(sum, total);
        
        // With 200 trials each, we should have gotten at least some hits
        assertTrue("Should have some inefficiency events", total > 0);
    }

    @Test
    public void testGetTotalInefficiencyCount_SumsAllTypes() {
        injector.resetCounters();

        // The total should always equal sum of individual counts
        long total = injector.getTotalInefficiencyCount();
        long sum = injector.getBacktrackCount() + injector.getHesitationCount()
                 + injector.getRedundantActionCount() + injector.getActionCancelCount();

        assertEquals(sum, total);
    }

    @Test
    public void testInefficiencyType_HasAllExpectedValues() {
        InefficiencyInjector.InefficiencyType[] types = InefficiencyInjector.InefficiencyType.values();

        assertEquals(5, types.length);
        assertNotNull(InefficiencyInjector.InefficiencyType.NONE);
        assertNotNull(InefficiencyInjector.InefficiencyType.BACKTRACK);
        assertNotNull(InefficiencyInjector.InefficiencyType.REDUNDANT_ACTION);
        assertNotNull(InefficiencyInjector.InefficiencyType.HESITATION);
        assertNotNull(InefficiencyInjector.InefficiencyType.ACTION_CANCEL);
    }

    @Test
    public void testFatigueAffectsProbability() {
        // Create two injectors with same seed but different fatigue
        MutableClock clock1 = new MutableClock(Instant.now(), ZoneId.systemDefault());
        MutableClock clock2 = new MutableClock(Instant.now(), ZoneId.systemDefault());
        
        Randomization rand1 = new Randomization(99999L);
        Randomization rand2 = new Randomization(99999L);
        
        InefficiencyInjector noFatigueInjector = new InefficiencyInjector(rand1, clock1);
        InefficiencyInjector fatiguedInjector = new InefficiencyInjector(rand2, clock2);
        
        FatigueModel highFatigue = mock(FatigueModel.class);
        when(highFatigue.getFatigueLevel()).thenReturn(1.0); // Max fatigue
        fatiguedInjector.setFatigueModel(highFatigue);
        
        // Run many trials
        int noFatigueHits = 0;
        int fatiguedHits = 0;
        int trials = 500;
        
        for (int i = 0; i < trials; i++) {
            clock1.advancePastClusteringInterval();
            clock2.advancePastClusteringInterval();
            
            if (noFatigueInjector.shouldHesitate()) noFatigueHits++;
            if (fatiguedInjector.shouldHesitate()) fatiguedHits++;
        }
        
        // Fatigued injector should have more hits (probability is doubled at max fatigue)
        // Allow some variance but the trend should be clear with 500 trials
        assertTrue("Fatigued injector should trigger more often (got " + fatiguedHits + 
                   " vs " + noFatigueHits + ")", fatiguedHits >= noFatigueHits);
    }
}
