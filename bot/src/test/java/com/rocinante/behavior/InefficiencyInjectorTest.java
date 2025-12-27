package com.rocinante.behavior;

import com.rocinante.util.Randomization;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InefficiencyInjector.
 * Tests probability distributions and inefficiency injection behaviors.
 */
public class InefficiencyInjectorTest {

    private Randomization randomization;
    private InefficiencyInjector injector;

    @Mock
    private FatigueModel fatigueModel;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        randomization = new Randomization(12345L);
        injector = new InefficiencyInjector(randomization);
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
            assertFalse(injector.shouldBacktrack());
        }
    }

    @Test
    public void testShouldBacktrack_ApproximatelyTwoPercent() {
        // 2% base rate, run many trials
        int backtrackCount = 0;
        int trials = 10000;
        
        for (int i = 0; i < trials; i++) {
            // Reset timing between checks
            try { Thread.sleep(35); } catch (InterruptedException e) {}
            if (injector.shouldBacktrack()) {
                backtrackCount++;
            }
        }
        
        // With 2% rate, expect ~200 over 10000 trials, but clustering prevention reduces this
        // Just verify it's reasonable (not 0 and not 50%)
        assertTrue("Should have some backtracking events", backtrackCount > 0);
        assertTrue("Should not backtrack too often", backtrackCount < trials * 0.10);
    }

    @Test
    public void testGetBacktrackDistance_ReturnsOneOrTwo() {
        for (int i = 0; i < 100; i++) {
            int distance = injector.getBacktrackDistance();
            assertTrue("Backtrack distance should be 1 or 2", distance >= 1 && distance <= 2);
        }
    }

    @Test
    public void testShouldHesitate_WhenDisabled_ReturnsFalse() {
        injector.setEnabled(false);
        
        for (int i = 0; i < 100; i++) {
            assertFalse(injector.shouldHesitate());
        }
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
            assertFalse(injector.shouldPerformRedundantAction());
        }
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
            assertFalse(injector.shouldCancelAction());
        }
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
        // Trigger some events (may or may not actually trigger due to probability)
        for (int i = 0; i < 1000; i++) {
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
}

