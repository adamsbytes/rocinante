package com.rocinante.behavior;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FatigueModel.
 * Tests fatigue accumulation, recovery, and effect multipliers.
 */
public class FatigueModelTest {

    @Mock
    private BotActivityTracker activityTracker;
    
    @Mock
    private PlayerProfile playerProfile;
    
    private FatigueModel fatigueModel;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Setup default mocks
        when(activityTracker.getCurrentActivity()).thenReturn(ActivityType.MEDIUM);
        when(activityTracker.getAccountType()).thenReturn(AccountType.NORMAL);
        when(playerProfile.getBreakFatigueThreshold()).thenReturn(0.80);
        when(playerProfile.getBaseMisclickRate()).thenReturn(0.02);
        
        fatigueModel = new FatigueModel(() -> activityTracker, playerProfile);
    }

    // ========================================================================
    // Initialization Tests
    // ========================================================================

    @Test
    public void testInitialization_StartsAtZero() {
        assertEquals(0.0, fatigueModel.getFatigueLevel(), 0.001);
        assertEquals(0, fatigueModel.getSessionActionCount());
        assertFalse(fatigueModel.isOnBreak());
    }

    @Test
    public void testInitialization_WithCustomLevel() {
        FatigueModel custom = new FatigueModel(() -> activityTracker, playerProfile, 0.5);
        assertEquals(0.5, custom.getFatigueLevel(), 0.001);
    }

    // ========================================================================
    // Session Lifecycle Tests
    // ========================================================================

    @Test
    public void testOnSessionStart_AppliesRecovery() {
        fatigueModel.setFatigueLevel(0.5);
        
        fatigueModel.onSessionStart();
        
        // Should reduce by 0.3 (session start recovery)
        assertEquals(0.2, fatigueModel.getFatigueLevel(), 0.001);
        assertEquals(0, fatigueModel.getSessionActionCount());
        assertFalse(fatigueModel.isOnBreak());
    }

    @Test
    public void testOnSessionStart_ClampsToZero() {
        fatigueModel.setFatigueLevel(0.1);
        
        fatigueModel.onSessionStart();
        
        // 0.1 - 0.3 = -0.2, but should clamp to 0
        assertEquals(0.0, fatigueModel.getFatigueLevel(), 0.001);
    }

    // ========================================================================
    // Action Recording Tests
    // ========================================================================

    @Test
    public void testRecordAction_IncreasesFatigue() {
        double initialFatigue = fatigueModel.getFatigueLevel();
        
        fatigueModel.recordAction();
        
        assertTrue(fatigueModel.getFatigueLevel() > initialFatigue);
        assertEquals(1, fatigueModel.getSessionActionCount());
    }

    @Test
    public void testRecordAction_MultipleActions_AccumulatesFatigue() {
        for (int i = 0; i < 100; i++) {
            fatigueModel.recordAction();
        }
        
        // 100 actions * 0.0005 = 0.05 fatigue
        assertTrue(fatigueModel.getFatigueLevel() >= 0.05);
        assertEquals(100, fatigueModel.getSessionActionCount());
    }

    @Test
    public void testRecordAction_DuringBreak_NoEffect() {
        fatigueModel.startBreak();
        double fatigueBeforeBreak = fatigueModel.getFatigueLevel();
        
        fatigueModel.recordAction();
        
        assertEquals(fatigueBeforeBreak, fatigueModel.getFatigueLevel(), 0.001);
    }

    @Test
    public void testRecordAction_HighIntensityActivity_MoreFatigue() {
        when(activityTracker.getCurrentActivity()).thenReturn(ActivityType.CRITICAL);
        
        fatigueModel.recordAction();
        double criticalFatigue = fatigueModel.getFatigueLevel();
        
        fatigueModel.reset();
        when(activityTracker.getCurrentActivity()).thenReturn(ActivityType.LOW);
        
        fatigueModel.recordAction();
        double lowFatigue = fatigueModel.getFatigueLevel();
        
        assertTrue("Critical activity should accumulate more fatigue", criticalFatigue > lowFatigue);
    }

    // ========================================================================
    // Break Management Tests
    // ========================================================================

    @Test
    public void testStartBreak_SetsFlagAndRecordsTime() {
        assertFalse(fatigueModel.isOnBreak());
        
        fatigueModel.startBreak();
        
        assertTrue(fatigueModel.isOnBreak());
    }

    @Test
    public void testEndBreak_AppliesRecovery() throws InterruptedException {
        fatigueModel.setFatigueLevel(0.5);
        fatigueModel.startBreak();
        
        Thread.sleep(10); // Minimal delay to ensure time passes
        fatigueModel.endBreak();
        
        // Should have recovered some fatigue (even if tiny amount)
        assertTrue(fatigueModel.getFatigueLevel() <= 0.5);
        assertFalse(fatigueModel.isOnBreak());
    }

    @Test
    public void testRecordBreakTime_ReducesFatigue() {
        fatigueModel.setFatigueLevel(0.8);
        
        // 5 minutes of break = 0.5 recovery (5 * 0.1)
        fatigueModel.recordBreakTime(Duration.ofMinutes(5));
        
        assertEquals(0.3, fatigueModel.getFatigueLevel(), 0.001);
    }

    // ========================================================================
    // Effect Multiplier Tests
    // ========================================================================

    @Test
    public void testGetSigmaMultiplier_ZeroFatigue_ReturnsOne() {
        fatigueModel.setFatigueLevel(0.0);
        
        assertEquals(1.0, fatigueModel.getSigmaMultiplier(), 0.001);
    }

    @Test
    public void testGetSigmaMultiplier_MaxFatigue_ReturnsOnePointSix() {
        fatigueModel.setFatigueLevel(1.0);
        
        // 1.0 + (1.0 * 0.6) = 1.6
        assertEquals(1.6, fatigueModel.getSigmaMultiplier(), 0.001);
    }
    
    @Test
    public void testGetTauMultiplier_ZeroFatigue_ReturnsOne() {
        fatigueModel.setFatigueLevel(0.0);
        
        assertEquals(1.0, fatigueModel.getTauMultiplier(), 0.001);
    }

    @Test
    public void testGetTauMultiplier_MaxFatigue_ReturnsOnePointEight() {
        fatigueModel.setFatigueLevel(1.0);
        
        // 1.0 + (1.0 * 0.8) = 1.8
        assertEquals(1.8, fatigueModel.getTauMultiplier(), 0.001);
    }

    @Test
    public void testGetClickVarianceMultiplier_ZeroFatigue_ReturnsOne() {
        fatigueModel.setFatigueLevel(0.0);
        
        assertEquals(1.0, fatigueModel.getClickVarianceMultiplier(), 0.001);
    }

    @Test
    public void testGetClickVarianceMultiplier_MaxFatigue_ReturnsOnePointFour() {
        fatigueModel.setFatigueLevel(1.0);
        
        // 1.0 + (1.0 * 0.4) = 1.4
        assertEquals(1.4, fatigueModel.getClickVarianceMultiplier(), 0.001);
    }

    @Test
    public void testGetMisclickMultiplier_ZeroFatigue_ReturnsOne() {
        fatigueModel.setFatigueLevel(0.0);
        
        assertEquals(1.0, fatigueModel.getMisclickMultiplier(), 0.001);
    }

    @Test
    public void testGetMisclickMultiplier_MaxFatigue_ReturnsThree() {
        fatigueModel.setFatigueLevel(1.0);
        
        // 1.0 + (1.0 * 2.0) = 3.0
        assertEquals(3.0, fatigueModel.getMisclickMultiplier(), 0.001);
    }

    @Test
    public void testGetEffectiveMisclickRate_UsesProfile() {
        fatigueModel.setFatigueLevel(0.5);
        
        double effectiveRate = fatigueModel.getEffectiveMisclickRate();
        
        // Base rate (0.02) * (1.0 + 0.5 * 2.0) = 0.02 * 2.0 = 0.04
        assertEquals(0.04, effectiveRate, 0.001);
    }

    // ========================================================================
    // Break Threshold Tests
    // ========================================================================

    @Test
    public void testShouldTakeBreak_BelowThreshold_ReturnsFalse() {
        fatigueModel.setFatigueLevel(0.5);
        
        assertFalse(fatigueModel.shouldTakeBreak());
    }

    @Test
    public void testShouldTakeBreak_AboveThreshold_ReturnsTrue() {
        fatigueModel.setFatigueLevel(0.85);
        
        assertTrue(fatigueModel.shouldTakeBreak());
    }

    @Test
    public void testShouldTakeBreak_HCIM_LowerThreshold() {
        when(activityTracker.getAccountType()).thenReturn(AccountType.HARDCORE_IRONMAN);
        
        // HCIM modifier is 0.85, so 0.80 * 0.85 = 0.68
        fatigueModel.setFatigueLevel(0.70);
        
        assertTrue("HCIM should break earlier", fatigueModel.shouldTakeBreak());
    }

    // ========================================================================
    // Reset Tests
    // ========================================================================

    @Test
    public void testReset_ClearsFatigueAndActions() {
        fatigueModel.setFatigueLevel(0.7);
        fatigueModel.recordAction();
        fatigueModel.recordAction();
        
        fatigueModel.reset();
        
        assertEquals(0.0, fatigueModel.getFatigueLevel(), 0.001);
        assertEquals(0, fatigueModel.getSessionActionCount());
    }

    // ========================================================================
    // Summary Tests
    // ========================================================================

    @Test
    public void testGetSummary_ContainsKeyInfo() {
        fatigueModel.setFatigueLevel(0.5);
        fatigueModel.recordAction();
        
        String summary = fatigueModel.getSummary();
        
        assertTrue(summary.contains("50")); // Fatigue percentage
        assertTrue(summary.contains("1.2")); // Delay multiplier (1.25)
        assertTrue(summary.contains("actions="));
    }
}

