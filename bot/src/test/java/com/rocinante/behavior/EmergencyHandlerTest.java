package com.rocinante.behavior;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EmergencyHandler.
 * Tests emergency triggering, cooldowns, and resolution.
 */
public class EmergencyHandlerTest {

    @Mock
    private TaskContext taskContext;
    
    @Mock
    private EmergencyCondition condition1;
    
    @Mock
    private EmergencyCondition condition2;
    
    @Mock
    private Task mockResponseTask;
    
    private EmergencyHandler emergencyHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        when(condition1.getId()).thenReturn("EMERGENCY_1");
        when(condition1.getDescription()).thenReturn("Test Emergency 1");
        when(condition1.getCooldownMs()).thenReturn(3000L);
        when(condition1.createResponseTask(any())).thenReturn(mockResponseTask);
        
        when(condition2.getId()).thenReturn("EMERGENCY_2");
        when(condition2.getDescription()).thenReturn("Test Emergency 2");
        when(condition2.getCooldownMs()).thenReturn(5000L);
        when(condition2.createResponseTask(any())).thenReturn(mockResponseTask);
        
        emergencyHandler = new EmergencyHandler();
    }

    // ========================================================================
    // Registration Tests
    // ========================================================================

    @Test
    public void testRegisterCondition_IncreasesCount() {
        assertEquals(0, emergencyHandler.getConditionCount());
        
        emergencyHandler.registerCondition(condition1);
        
        assertEquals(1, emergencyHandler.getConditionCount());
    }

    @Test
    public void testUnregisterCondition_DecreasesCount() {
        emergencyHandler.registerCondition(condition1);
        emergencyHandler.registerCondition(condition2);
        assertEquals(2, emergencyHandler.getConditionCount());
        
        emergencyHandler.unregisterCondition(condition1);
        
        assertEquals(1, emergencyHandler.getConditionCount());
    }

    @Test
    public void testClearConditions_RemovesAll() {
        emergencyHandler.registerCondition(condition1);
        emergencyHandler.registerCondition(condition2);
        
        emergencyHandler.clearConditions();
        
        assertEquals(0, emergencyHandler.getConditionCount());
    }

    // ========================================================================
    // Emergency Triggering Tests
    // ========================================================================

    @Test
    public void testCheckEmergencies_NoConditions_ReturnsEmpty() {
        Optional<Task> task = emergencyHandler.checkEmergencies(taskContext);
        
        assertFalse(task.isPresent());
    }

    @Test
    public void testCheckEmergencies_ConditionNotTriggered_ReturnsEmpty() {
        when(condition1.isTriggered(any())).thenReturn(false);
        emergencyHandler.registerCondition(condition1);
        
        Optional<Task> task = emergencyHandler.checkEmergencies(taskContext);
        
        assertFalse(task.isPresent());
    }

    @Test
    public void testCheckEmergencies_ConditionTriggered_ReturnsTask() {
        when(condition1.isTriggered(any())).thenReturn(true);
        emergencyHandler.registerCondition(condition1);
        
        Optional<Task> task = emergencyHandler.checkEmergencies(taskContext);
        
        assertTrue(task.isPresent());
        assertEquals(mockResponseTask, task.get());
        assertTrue("Emergency should be active", emergencyHandler.hasActiveEmergency());
        assertEquals("EMERGENCY_1", emergencyHandler.getActiveEmergencyId());
    }

    @Test
    public void testCheckEmergencies_MultipleConditions_ReturnsFirst() {
        when(condition1.isTriggered(any())).thenReturn(true);
        when(condition2.isTriggered(any())).thenReturn(true);
        
        emergencyHandler.registerCondition(condition1);
        emergencyHandler.registerCondition(condition2);
        
        Optional<Task> task = emergencyHandler.checkEmergencies(taskContext);
        
        assertTrue(task.isPresent());
        // First registered condition should trigger
        verify(condition1).createResponseTask(taskContext);
    }

    // ========================================================================
    // Cooldown Tests
    // ========================================================================

    @Test
    public void testCooldown_PreventsImmediateRetrigger() {
        when(condition1.isTriggered(any())).thenReturn(true);
        emergencyHandler.registerCondition(condition1);
        
        // First trigger
        Optional<Task> task1 = emergencyHandler.checkEmergencies(taskContext);
        assertTrue(task1.isPresent());
        
        // Immediate second check should be on cooldown
        Optional<Task> task2 = emergencyHandler.checkEmergencies(taskContext);
        assertFalse("Should be on cooldown", task2.isPresent());
    }

    @Test
    public void testCooldown_AllowsRetriggerAfterResolution() {
        when(condition1.isTriggered(any())).thenReturn(true);
        when(condition1.getCooldownMs()).thenReturn(100L);
        emergencyHandler.registerCondition(condition1);
        
        // First trigger
        emergencyHandler.checkEmergencies(taskContext);
        
        // Success clears both active emergency AND cooldown
        emergencyHandler.emergencySucceeded("EMERGENCY_1");
        
        // Should trigger again immediately (no wait needed)
        Optional<Task> task = emergencyHandler.checkEmergencies(taskContext);
        assertTrue("Should trigger after success", task.isPresent());
    }

    @Test
    public void testClearCooldown_AllowsImmediateRetrigger() {
        when(condition1.isTriggered(any())).thenReturn(true);
        emergencyHandler.registerCondition(condition1);
        
        // First trigger
        emergencyHandler.checkEmergencies(taskContext);
        
        // Success clears active emergency and cooldown
        emergencyHandler.emergencySucceeded("EMERGENCY_1");
        
        // Should trigger again immediately (success cleared cooldown)
        Optional<Task> task = emergencyHandler.checkEmergencies(taskContext);
        assertTrue("Should trigger after success", task.isPresent());
    }

    // ========================================================================
    // Resolution Tests
    // ========================================================================

    @Test
    public void testEmergencySucceeded_ClearsActive() {
        when(condition1.isTriggered(any())).thenReturn(true);
        emergencyHandler.registerCondition(condition1);
        
        emergencyHandler.checkEmergencies(taskContext);
        assertTrue(emergencyHandler.hasActiveEmergency());
        
        emergencyHandler.emergencySucceeded("EMERGENCY_1");
        
        assertFalse(emergencyHandler.hasActiveEmergency());
        assertNull(emergencyHandler.getActiveEmergencyId());
    }

    @Test
    public void testEmergencySucceeded_ClearsCooldown() {
        when(condition1.isTriggered(any())).thenReturn(true);
        emergencyHandler.registerCondition(condition1);
        
        // Trigger emergency
        emergencyHandler.checkEmergencies(taskContext);
        
        // Success clears cooldown
        emergencyHandler.emergencySucceeded("EMERGENCY_1");
        
        // Should be able to trigger immediately (cooldown cleared)
        Optional<Task> task = emergencyHandler.checkEmergencies(taskContext);
        assertTrue("Should retrigger after success clears cooldown", task.isPresent());
    }

    @Test
    public void testEmergencyFailed_ClearsActiveButRetainsCooldown() {
        when(condition1.isTriggered(any())).thenReturn(true);
        when(condition1.getCooldownMs()).thenReturn(60000L); // Long cooldown
        emergencyHandler.registerCondition(condition1);
        
        // Trigger emergency
        emergencyHandler.checkEmergencies(taskContext);
        assertTrue(emergencyHandler.hasActiveEmergency());
        
        // Failure clears active but KEEPS cooldown
        emergencyHandler.emergencyFailed("EMERGENCY_1");
        
        // Active should be cleared
        assertFalse(emergencyHandler.hasActiveEmergency());
        
        // But should NOT retrigger immediately due to retained cooldown
        Optional<Task> task = emergencyHandler.checkEmergencies(taskContext);
        assertFalse("Should NOT retrigger after failure - cooldown retained", task.isPresent());
    }

    @Test
    public void testEmergencySucceeded_WrongId_NoEffect() {
        when(condition1.isTriggered(any())).thenReturn(true);
        emergencyHandler.registerCondition(condition1);
        
        emergencyHandler.checkEmergencies(taskContext);
        assertTrue(emergencyHandler.hasActiveEmergency());
        
        emergencyHandler.emergencySucceeded("WRONG_ID");
        
        assertTrue("Wrong ID should not clear emergency", emergencyHandler.hasActiveEmergency());
    }

    // ========================================================================
    // Suppression Tests
    // ========================================================================

    @Test
    public void testSuppress_PreventsEmergencies() {
        when(condition1.isTriggered(any())).thenReturn(true);
        emergencyHandler.registerCondition(condition1);
        
        emergencyHandler.suppress();
        
        Optional<Task> task = emergencyHandler.checkEmergencies(taskContext);
        
        assertFalse("Suppressed handler should not trigger emergencies", task.isPresent());
        assertTrue(emergencyHandler.isSuppressed());
    }

    @Test
    public void testUnsuppress_AllowsEmergencies() {
        emergencyHandler.suppress();
        emergencyHandler.unsuppress();
        
        assertFalse(emergencyHandler.isSuppressed());
    }

    // ========================================================================
    // Active Emergency Tests
    // ========================================================================

    @Test
    public void testActiveEmergency_PreventsRetriggerSameEmergency() {
        when(condition1.isTriggered(any())).thenReturn(true);
        emergencyHandler.registerCondition(condition1);
        
        // First trigger
        emergencyHandler.checkEmergencies(taskContext);
        assertEquals("EMERGENCY_1", emergencyHandler.getActiveEmergencyId());
        
        // Second check should skip active emergency even without cooldown
        emergencyHandler.clearCooldown("EMERGENCY_1");
        Optional<Task> task = emergencyHandler.checkEmergencies(taskContext);
        
        assertFalse("Active emergency should not retrigger", task.isPresent());
    }

    // ========================================================================
    // Summary Tests
    // ========================================================================

    @Test
    public void testGetSummary_ContainsKeyInfo() {
        emergencyHandler.registerCondition(condition1);
        
        String summary = emergencyHandler.getSummary();
        
        assertTrue(summary.contains("conditions=1"));
        assertTrue(summary.contains("active=null"));
        assertTrue(summary.contains("suppressed=false"));
    }
}

