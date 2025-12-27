package com.rocinante.tasks;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AbstractTask pause/resume functionality.
 * Tests state transitions for PAUSED state.
 */
public class AbstractTaskPauseResumeTest {

    @Mock
    private TaskContext taskContext;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(taskContext.isLoggedIn()).thenReturn(true);
    }

    // ========================================================================
    // Pause/Resume State Transition Tests
    // ========================================================================

    @Test
    public void testPauseTransition_FromRunningToPaused_IsValid() {
        TestTask task = new TestTask();
        
        // Start the task
        task.execute(taskContext);
        assertEquals("Task should be RUNNING", TaskState.RUNNING, task.getState());
        
        // Transition to PAUSED
        boolean success = task.transitionTo(TaskState.PAUSED);
        
        assertTrue("RUNNING -> PAUSED should be valid", success);
        assertEquals("Task should be PAUSED", TaskState.PAUSED, task.getState());
    }

    @Test
    public void testResumeTransition_FromPausedToRunning_IsValid() {
        TestTask task = new TestTask();
        
        // Get to PAUSED state
        task.execute(taskContext);
        task.transitionTo(TaskState.PAUSED);
        
        // Resume to RUNNING
        boolean success = task.transitionTo(TaskState.RUNNING);
        
        assertTrue("PAUSED -> RUNNING should be valid", success);
        assertEquals("Task should be RUNNING", TaskState.RUNNING, task.getState());
    }

    @Test
    public void testPausedTask_CanBeExecuted() {
        TestTask task = new TestTask();
        
        // Get to PAUSED state
        task.execute(taskContext);
        task.transitionTo(TaskState.PAUSED);
        
        // Execute should resume
        task.execute(taskContext);
        
        assertEquals("Task should resume to RUNNING", TaskState.RUNNING, task.getState());
    }

    @Test
    public void testPausedTask_FromFailed_IsInvalid() {
        TestTask task = new TestTask();
        
        // Transition to FAILED
        task.transitionTo(TaskState.FAILED);
        
        // Try to pause
        boolean success = task.transitionTo(TaskState.PAUSED);
        
        assertFalse("FAILED -> PAUSED should be invalid", success);
        assertEquals("Task should remain FAILED", TaskState.FAILED, task.getState());
    }

    @Test
    public void testPausedTask_FromCompleted_IsInvalid() {
        TestTask task = new TestTask();
        
        // Complete the task
        task.execute(taskContext);
        task.complete();
        
        // Try to pause
        boolean success = task.transitionTo(TaskState.PAUSED);
        
        assertFalse("COMPLETED -> PAUSED should be invalid", success);
        assertEquals("Task should remain COMPLETED", TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testPausedTask_CanFail() {
        TestTask task = new TestTask();
        
        // Get to PAUSED state
        task.execute(taskContext);
        task.transitionTo(TaskState.PAUSED);
        
        // Transition to FAILED
        boolean success = task.transitionTo(TaskState.FAILED);
        
        assertTrue("PAUSED -> FAILED should be valid", success);
        assertEquals("Task should be FAILED", TaskState.FAILED, task.getState());
    }

    @Test
    public void testPausedTask_PreservesExecutionTicks() {
        TestTask task = new TestTask();
        
        // Execute for 3 ticks
        task.execute(taskContext);
        task.execute(taskContext);
        task.execute(taskContext);
        int ticksBeforePause = task.getExecutionTicks();
        
        // Pause
        task.transitionTo(TaskState.PAUSED);
        
        // Resume
        task.execute(taskContext);
        
        assertEquals("Execution ticks should be preserved through pause",
                ticksBeforePause, task.getExecutionTicks() - 1);
    }

    // ========================================================================
    // Helper Classes
    // ========================================================================

    /**
     * Simple test task for state transition testing.
     */
    private static class TestTask extends AbstractTask {
        private boolean canExecute = true;

        @Override
        public String getDescription() {
            return "TestTask";
        }

        @Override
        public boolean canExecute(TaskContext ctx) {
            return canExecute;
        }

        @Override
        protected void executeImpl(TaskContext ctx) {
            // Just run indefinitely until manually completed
        }
    }
}

