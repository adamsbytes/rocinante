package com.rocinante.tasks;

import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.BreakType;
import com.rocinante.behavior.EmergencyHandler;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TaskExecutor.
 * Tests task execution, behavioral break injection, and currentStepComplete flag.
 */
public class TaskExecutorTest {

    @Mock
    private TaskContext taskContext;
    
    @Mock
    private BreakScheduler breakScheduler;
    
    @Mock
    private EmergencyHandler emergencyHandler;
    
    private TaskExecutor taskExecutor;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Setup taskContext defaults
        when(taskContext.isAbortRequested()).thenReturn(false);
        
        // Setup breakScheduler defaults
        when(breakScheduler.getScheduledBreak()).thenReturn(Optional.empty());
        
        // Setup emergencyHandler defaults
        when(emergencyHandler.checkEmergencies(any())).thenReturn(Optional.empty());
        
        taskExecutor = new TaskExecutor(taskContext);
        taskExecutor.setBreakScheduler(breakScheduler);
        taskExecutor.setEmergencyHandler(emergencyHandler);
    }

    // ========================================================================
    // Basic Execution Tests
    // ========================================================================

    @Test
    public void testQueueTask_IncreasesQueueSize() {
        Task mockTask = createMockTask("Test Task");
        
        taskExecutor.queueTask(mockTask);
        
        assertEquals(1, taskExecutor.getQueueSize());
    }

    @Test
    public void testStart_EnablesExecution() {
        taskExecutor.start();
        
        assertTrue(taskExecutor.getEnabled().get());
    }

    @Test
    public void testStop_DisablesExecution() {
        taskExecutor.start();
        taskExecutor.stop();
        
        assertFalse(taskExecutor.getEnabled().get());
    }

    // ========================================================================
    // CurrentStepComplete Tests (Critical Bug Fix #1)
    // ========================================================================

    @Test
    public void testCurrentStepComplete_ResetsAfterTaskCompletion() {
        // Create a task that completes in one tick
        Task quickTask = new AbstractTask() {
            private boolean executed = false;
            
            {
                timeout = Duration.ofSeconds(5);
            }
            
            @Override
            public String getDescription() {
                return "Quick Task";
            }
            
            @Override
            public boolean canExecute(TaskContext context) {
                return true;
            }
            
            @Override
            protected void executeImpl(TaskContext context) {
                if (!executed) {
                    executed = true;
                    complete();
                }
            }
        };
        
        taskExecutor.queueTask(quickTask);
        taskExecutor.start();
        
        // Verify initial state
        assertTrue("Should start with step complete", taskExecutor.isCurrentStepComplete());
        
        // Execute first tick (task starts)
        taskExecutor.onGameTick(null);
        
        // During execution, step should be incomplete
        // After completion, should reset to true
        assertTrue("Should reset to true after task completion", 
                  taskExecutor.isCurrentStepComplete());
    }

    @Test
    public void testCurrentStepComplete_AllowsBreaksAfterCompletion() {
        // Create a completing task
        Task task = new AbstractTask() {
            private boolean executed = false;
            
            {
                timeout = Duration.ofSeconds(5);
            }
            
            @Override
            public String getDescription() {
                return "Task";
            }
            
            @Override
            public boolean canExecute(TaskContext context) {
                return true;
            }
            
            @Override
            protected void executeImpl(TaskContext context) {
                if (!executed) {
                    executed = true;
                    complete();
                }
            }
        };
        
        // Mock a break being scheduled
        Task breakTask = createMockTask("Break Task");
        when(breakScheduler.getScheduledBreak()).thenReturn(Optional.of(breakTask));
        
        taskExecutor.queueTask(task);
        taskExecutor.start();
        
        // Execute task
        taskExecutor.onGameTick(null);
        
        // Break scheduler should have been checked after completion
        verify(breakScheduler, atLeastOnce()).getScheduledBreak();
    }

    @Test
    public void testCurrentStepComplete_BlocksBreaksDuringExecution() {
        // Create a long-running task
        Task longTask = new AbstractTask() {
            private int ticks = 0;
            
            {
                timeout = Duration.ofSeconds(10);
            }
            
            @Override
            public String getDescription() {
                return "Long Task";
            }
            
            @Override
            public boolean canExecute(TaskContext context) {
                return true;
            }
            
            @Override
            protected void executeImpl(TaskContext context) {
                ticks++;
                // Don't complete for several ticks
                if (ticks >= 5) {
                    complete();
                }
            }
        };
        
        taskExecutor.queueTask(longTask);
        taskExecutor.start();
        
        // Set step as incomplete (simulating task execution)
        taskExecutor.setCurrentStepComplete(false);
        
        // Tick once
        taskExecutor.onGameTick(null);
        
        // Break scheduler should NOT be checked when step is incomplete
        verify(breakScheduler, never()).getScheduledBreak();
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private Task createMockTask(String description) {
        return new AbstractTask() {
            {
                timeout = Duration.ofSeconds(5);
            }
            
            @Override
            public String getDescription() {
                return description;
            }
            
            @Override
            public boolean canExecute(TaskContext context) {
                return true;
            }
            
            @Override
            protected void executeImpl(TaskContext context) {
                // Do nothing, just sit there
            }
        };
    }
}

