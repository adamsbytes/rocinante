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
 * Unit tests for PoisonEmergency.
 * Tests poison/venom detection and threshold calculation.
 */
public class PoisonEmergencyTest {

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

    private PoisonEmergency.CureTaskFactory mockFactory;
    private PoisonEmergency emergency;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        when(taskContext.getCombatState()).thenReturn(combatState);
        when(taskContext.getPlayerState()).thenReturn(playerState);

        // Default: normal account
        when(activityTracker.getAccountType()).thenReturn(AccountType.NORMAL);

        // Default: not poisoned/venomed
        when(combatState.isPoisoned()).thenReturn(false);
        when(combatState.isVenomed()).thenReturn(false);

        // Default: 50 max HP
        when(playerState.getMaxHitpoints()).thenReturn(50);
        when(playerState.getCurrentHitpoints()).thenReturn(50);

        mockFactory = ctx -> mockResponseTask;
        emergency = new PoisonEmergency(activityTracker, mockFactory);
    }

    // ========================================================================
    // Not Poisoned Tests
    // ========================================================================

    @Test
    public void testIsTriggered_NotPoisoned_ReturnsFalse() {
        when(combatState.isPoisoned()).thenReturn(false);
        when(combatState.isVenomed()).thenReturn(false);

        assertFalse(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_NotPoisoned_EvenWithLowHealth_ReturnsFalse() {
        when(combatState.isPoisoned()).thenReturn(false);
        when(combatState.isVenomed()).thenReturn(false);
        when(playerState.getCurrentHitpoints()).thenReturn(5);

        assertFalse(emergency.isTriggered(taskContext));
    }

    // ========================================================================
    // Poison Trigger Tests
    // ========================================================================

    @Test
    public void testIsTriggered_Poisoned_HealthAboveThreshold_ReturnsFalse() {
        when(combatState.isPoisoned()).thenReturn(true);
        // 50% HP, threshold is 30%
        when(playerState.getCurrentHitpoints()).thenReturn(25);

        assertFalse(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_Poisoned_HealthAtThreshold_ReturnsTrue() {
        when(combatState.isPoisoned()).thenReturn(true);
        // Exactly 30% HP
        when(playerState.getCurrentHitpoints()).thenReturn(15);

        assertTrue(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_Poisoned_HealthBelowThreshold_ReturnsTrue() {
        when(combatState.isPoisoned()).thenReturn(true);
        // 20% HP
        when(playerState.getCurrentHitpoints()).thenReturn(10);

        assertTrue(emergency.isTriggered(taskContext));
    }

    // ========================================================================
    // Venom Tests
    // ========================================================================

    @Test
    public void testIsTriggered_Venomed_HigherThreshold() {
        // Venom adds +10% to threshold (30% -> 40%)
        when(combatState.isVenomed()).thenReturn(true);
        
        // 35% HP - would pass poison threshold but fail venom threshold
        when(playerState.getCurrentHitpoints()).thenReturn(17);

        assertTrue(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_Venomed_HealthAboveVenomThreshold_ReturnsFalse() {
        when(combatState.isVenomed()).thenReturn(true);
        // 50% HP, venom threshold is 40%
        when(playerState.getCurrentHitpoints()).thenReturn(25);

        assertFalse(emergency.isTriggered(taskContext));
    }

    // ========================================================================
    // HCIM Tests
    // ========================================================================

    @Test
    public void testIsTriggered_HCIM_Poisoned_HigherThreshold() {
        // HCIM threshold is 50% (vs 30% for normal)
        when(activityTracker.getAccountType()).thenReturn(AccountType.HARDCORE_IRONMAN);
        when(combatState.isPoisoned()).thenReturn(true);
        
        // 45% HP - would pass normal threshold but fail HCIM threshold
        when(playerState.getCurrentHitpoints()).thenReturn(22);

        assertTrue(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_HCIM_Venomed_CombinedThreshold() {
        // HCIM (50%) + Venom (+10%) = 60% threshold
        when(activityTracker.getAccountType()).thenReturn(AccountType.HARDCORE_IRONMAN);
        when(combatState.isVenomed()).thenReturn(true);
        
        // 55% HP - should trigger
        when(playerState.getCurrentHitpoints()).thenReturn(27);

        assertTrue(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_HCIM_Poisoned_HealthAboveThreshold_ReturnsFalse() {
        when(activityTracker.getAccountType()).thenReturn(AccountType.HARDCORE_IRONMAN);
        when(combatState.isPoisoned()).thenReturn(true);
        // 60% HP, HCIM threshold is 50%
        when(playerState.getCurrentHitpoints()).thenReturn(30);

        assertFalse(emergency.isTriggered(taskContext));
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testIsTriggered_ZeroMaxHp_ReturnsFalse() {
        when(combatState.isPoisoned()).thenReturn(true);
        when(playerState.getMaxHitpoints()).thenReturn(0);
        when(playerState.getCurrentHitpoints()).thenReturn(0);

        assertFalse(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_BothPoisonedAndVenomed_UsesVenomThreshold() {
        // Both flags true - venom takes precedence (higher threshold)
        when(combatState.isPoisoned()).thenReturn(true);
        when(combatState.isVenomed()).thenReturn(true);
        
        // 35% HP - passes poison threshold (30%) but fails venom threshold (40%)
        when(playerState.getCurrentHitpoints()).thenReturn(17);

        assertTrue(emergency.isTriggered(taskContext));
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
        PoisonEmergency noFactoryEmergency = new PoisonEmergency(activityTracker, null);
        Task result = noFactoryEmergency.createResponseTask(taskContext);
        assertNull(result);
    }

    // ========================================================================
    // Metadata Tests
    // ========================================================================

    @Test
    public void testGetId() {
        assertEquals("POISON_EMERGENCY", emergency.getId());
    }

    @Test
    public void testGetSeverity_High() {
        // Poison is serious but not as urgent as direct low health
        int severity = emergency.getSeverity();
        assertTrue(severity >= 60);
        assertTrue(severity < 90);
    }

    @Test
    public void testGetCooldownMs_ReasonableValue() {
        // Need time for cure to take effect
        long cooldown = emergency.getCooldownMs();
        assertTrue(cooldown >= 5000);
        assertTrue(cooldown <= 30000);
    }

    // ========================================================================
    // Null Activity Tracker Tests
    // ========================================================================

    @Test
    public void testIsTriggered_NullActivityTracker_UsesNormalThreshold() {
        PoisonEmergency noTrackerEmergency = new PoisonEmergency(null, mockFactory);
        when(combatState.isPoisoned()).thenReturn(true);
        
        // 25% HP - below 30% threshold
        when(playerState.getCurrentHitpoints()).thenReturn(12);

        assertTrue(noTrackerEmergency.isTriggered(taskContext));
    }
}
