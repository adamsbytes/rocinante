package com.rocinante.behavior;

import com.rocinante.tasks.CompositeTask;
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
 * Tests for EmergencyHandler handling multiple simultaneous emergencies.
 * Tests Bug #9 fix: priority-based emergency queueing.
 */
public class MultipleEmergenciesTest {

    @Mock
    private TaskContext taskContext;
    
    private EmergencyHandler emergencyHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        emergencyHandler = new EmergencyHandler();
    }

    @Test
    public void testMultipleEmergencies_ReturnsCompositeTask() {
        // Register two emergency conditions that both trigger
        EmergencyCondition emergency1 = new TestEmergency("Emergency1", 50);
        EmergencyCondition emergency2 = new TestEmergency("Emergency2", 75);
        
        emergencyHandler.registerCondition(emergency1);
        emergencyHandler.registerCondition(emergency2);
        
        // Check emergencies
        Optional<Task> result = emergencyHandler.checkEmergencies(taskContext);
        
        assertTrue("Should return emergency task", result.isPresent());
        Task task = result.get();
        assertTrue("Should be CompositeTask for multiple emergencies", task instanceof CompositeTask);
    }

    @Test
    public void testMultipleEmergencies_SortedBySeverity() {
        // Register emergencies with different severities
        TestEmergency lowSeverity = new TestEmergency("Low", 10);
        TestEmergency highSeverity = new TestEmergency("High", 90);
        TestEmergency medSeverity = new TestEmergency("Med", 50);
        
        emergencyHandler.registerCondition(lowSeverity);
        emergencyHandler.registerCondition(highSeverity);
        emergencyHandler.registerCondition(medSeverity);
        
        // Check emergencies
        Optional<Task> result = emergencyHandler.checkEmergencies(taskContext);
        
        assertTrue("Should return task", result.isPresent());
        // High severity should set active emergency
        assertEquals("High severity should be active", "High", emergencyHandler.getActiveEmergencyId());
    }

    @Test
    public void testSingleEmergency_ReturnsDirectly() {
        // Register single emergency
        EmergencyCondition emergency = new TestEmergency("Solo", 50);
        emergencyHandler.registerCondition(emergency);
        
        // Check emergencies
        Optional<Task> result = emergencyHandler.checkEmergencies(taskContext);
        
        assertTrue("Should return task", result.isPresent());
        Task task = result.get();
        assertFalse("Single emergency should not be CompositeTask", task instanceof CompositeTask);
        assertEquals("Task description should match", "Emergency: Solo", task.getDescription());
    }

    @Test
    public void testEmergency_RespectsCooldown() {
        EmergencyCondition emergency = new TestEmergency("Cooldown Test", 50);
        emergencyHandler.registerCondition(emergency);
        
        // Trigger first time
        Optional<Task> first = emergencyHandler.checkEmergencies(taskContext);
        assertTrue("First trigger should return task", first.isPresent());
        
        // Trigger again immediately
        Optional<Task> second = emergencyHandler.checkEmergencies(taskContext);
        assertFalse("Second trigger should be on cooldown", second.isPresent());
    }

    // ========================================================================
    // Helper Classes
    // ========================================================================

    private static class TestEmergency implements EmergencyCondition {
        private final String id;
        private final int severity;

        TestEmergency(String id, int severity) {
            this.id = id;
            this.severity = severity;
        }

        @Override
        public boolean isTriggered(TaskContext ctx) {
            return true; // Always triggered for testing
        }

        @Override
        public Task createResponseTask(TaskContext ctx) {
            return new com.rocinante.tasks.AbstractTask() {
                @Override
                public String getDescription() {
                    return "Emergency: " + id;
                }

                @Override
                public boolean canExecute(TaskContext ctx) {
                    return true;
                }

                @Override
                protected void executeImpl(TaskContext ctx) {
                    complete();
                }
            };
        }

        @Override
        public String getDescription() {
            return id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public int getSeverity() {
            return severity;
        }
    }
}

