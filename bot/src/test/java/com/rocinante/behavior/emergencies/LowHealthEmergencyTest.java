package com.rocinante.behavior.emergencies;

import com.rocinante.behavior.AccountType;
import com.rocinante.behavior.BotActivityTracker;
import com.rocinante.state.CombatState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LowHealthEmergency.
 * Tests threshold calculation and triggering conditions.
 */
public class LowHealthEmergencyTest {

    @Mock
    private TaskContext taskContext;

    @Mock
    private BotActivityTracker activityTracker;

    @Mock
    private CombatState combatState;

    @Mock
    private PlayerState playerState;

    @Mock
    private Task mockResponseTask;

    private LowHealthEmergency.FleeTaskFactory mockFactory;
    private LowHealthEmergency emergency;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        when(taskContext.getCombatState()).thenReturn(combatState);
        when(taskContext.getPlayerState()).thenReturn(playerState);

        // Default: normal account, not in combat, not in dangerous area
        when(activityTracker.getAccountType()).thenReturn(AccountType.NORMAL);
        when(activityTracker.isInDangerousArea()).thenReturn(false);
        when(combatState.isBeingAttacked()).thenReturn(false);
        when(combatState.hasTarget()).thenReturn(false);

        // Default: 50 max HP
        when(playerState.getMaxHitpoints()).thenReturn(50);

        mockFactory = ctx -> mockResponseTask;
        emergency = new LowHealthEmergency(activityTracker, mockFactory);
    }

    // ========================================================================
    // Basic Trigger Tests
    // ========================================================================

    @Test
    public void testIsTriggered_HealthAboveThreshold_ReturnsFalse() {
        // 50% HP, threshold is 25%
        when(playerState.getCurrentHitpoints()).thenReturn(25);
        when(playerState.getMaxHitpoints()).thenReturn(50);

        assertFalse(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_HealthAtThreshold_ReturnsTrue() {
        // Exactly 25% HP
        when(playerState.getCurrentHitpoints()).thenReturn(12);
        when(playerState.getMaxHitpoints()).thenReturn(50);

        assertTrue(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_HealthBelowThreshold_ReturnsTrue() {
        // 10% HP
        when(playerState.getCurrentHitpoints()).thenReturn(5);
        when(playerState.getMaxHitpoints()).thenReturn(50);

        assertTrue(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_ZeroMaxHp_ReturnsFalse() {
        when(playerState.getMaxHitpoints()).thenReturn(0);
        when(playerState.getCurrentHitpoints()).thenReturn(0);

        assertFalse(emergency.isTriggered(taskContext));
    }

    // ========================================================================
    // Combat Modifier Tests
    // ========================================================================

    @Test
    public void testIsTriggered_InCombat_HigherThreshold() {
        // Being attacked adds +10% to threshold (25% -> 35%)
        when(combatState.isBeingAttacked()).thenReturn(true);
        
        // 30% HP - would pass normal threshold but fail combat threshold
        when(playerState.getCurrentHitpoints()).thenReturn(15);
        when(playerState.getMaxHitpoints()).thenReturn(50);

        assertTrue(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_HasTarget_HigherThreshold() {
        // Having a target adds +10%
        when(combatState.hasTarget()).thenReturn(true);
        
        // 30% HP
        when(playerState.getCurrentHitpoints()).thenReturn(15);
        when(playerState.getMaxHitpoints()).thenReturn(50);

        assertTrue(emergency.isTriggered(taskContext));
    }

    // ========================================================================
    // HCIM Modifier Tests
    // ========================================================================

    @Test
    public void testIsTriggered_HCIM_HigherThreshold() {
        // HCIM adds +25% to threshold (25% -> 50%)
        when(activityTracker.getAccountType()).thenReturn(AccountType.HARDCORE_IRONMAN);
        
        // 45% HP - would pass normal threshold but fail HCIM threshold
        when(playerState.getCurrentHitpoints()).thenReturn(22);
        when(playerState.getMaxHitpoints()).thenReturn(50);

        assertTrue(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_HCIM_InCombat_CombinedModifiers() {
        // HCIM (+25%) + Combat (+10%) = 60% threshold
        when(activityTracker.getAccountType()).thenReturn(AccountType.HARDCORE_IRONMAN);
        when(combatState.isBeingAttacked()).thenReturn(true);
        
        // 55% HP - should trigger
        when(playerState.getCurrentHitpoints()).thenReturn(27);
        when(playerState.getMaxHitpoints()).thenReturn(50);

        assertTrue(emergency.isTriggered(taskContext));
    }

    // ========================================================================
    // Dangerous Area Tests
    // ========================================================================

    @Test
    public void testIsTriggered_DangerousArea_HigherThreshold() {
        // Dangerous area adds +15%
        when(activityTracker.isInDangerousArea()).thenReturn(true);
        
        // 35% HP - would pass normal threshold but fail dangerous area threshold
        when(playerState.getCurrentHitpoints()).thenReturn(17);
        when(playerState.getMaxHitpoints()).thenReturn(50);

        assertTrue(emergency.isTriggered(taskContext));
    }

    // ========================================================================
    // Threshold Cap Tests
    // ========================================================================

    @Test
    public void testIsTriggered_ThresholdCappedAt75() {
        // All modifiers: HCIM (25%) + Combat (10%) + Dangerous (15%) = 75% (capped)
        when(activityTracker.getAccountType()).thenReturn(AccountType.HARDCORE_IRONMAN);
        when(activityTracker.isInDangerousArea()).thenReturn(true);
        when(combatState.isBeingAttacked()).thenReturn(true);
        
        // 80% HP - should NOT trigger even with all modifiers (cap at 75%)
        when(playerState.getCurrentHitpoints()).thenReturn(40);
        when(playerState.getMaxHitpoints()).thenReturn(50);

        assertFalse(emergency.isTriggered(taskContext));
    }

    // ========================================================================
    // Response Task Tests
    // ========================================================================

    @Test
    public void testCreateResponseTask_ReturnsFactoryTask() {
        Task result = emergency.createResponseTask(taskContext);
        assertEquals(mockResponseTask, result);
    }

    @Test
    public void testCreateResponseTask_NullFactory_ReturnsNull() {
        LowHealthEmergency noFactoryEmergency = new LowHealthEmergency(activityTracker, null);
        Task result = noFactoryEmergency.createResponseTask(taskContext);
        assertNull(result);
    }

    // ========================================================================
    // Metadata Tests
    // ========================================================================

    @Test
    public void testGetId() {
        assertEquals("LOW_HEALTH_EMERGENCY", emergency.getId());
    }

    @Test
    public void testGetSeverity_VeryHigh() {
        // Health emergencies are highest priority
        assertTrue(emergency.getSeverity() >= 90);
    }

    @Test
    public void testGetCooldownMs_Short() {
        // Need quick reaction time for health emergencies
        assertTrue(emergency.getCooldownMs() <= 5000);
    }

    // ========================================================================
    // Null Activity Tracker Tests
    // ========================================================================

    @Test
    public void testIsTriggered_NullActivityTracker_UsesBaseThreshold() {
        // With null tracker, should fall back to base threshold only
        LowHealthEmergency noTrackerEmergency = new LowHealthEmergency(null, mockFactory);
        
        // 20% HP - below base 25% threshold
        when(playerState.getCurrentHitpoints()).thenReturn(10);
        when(playerState.getMaxHitpoints()).thenReturn(50);

        assertTrue(noTrackerEmergency.isTriggered(taskContext));
    }
}
