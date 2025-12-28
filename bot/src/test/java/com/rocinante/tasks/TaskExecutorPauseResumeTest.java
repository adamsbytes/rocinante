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
 * Integration tests for TaskExecutor pause/resume functionality.
 * Tests behavioral task interruption and emergency interrupt edge cases.
 */
public class TaskExecutorPauseResumeTest {

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
        
        when(taskContext.isAbortRequested()).thenReturn(false);
        when(taskContext.isLoggedIn()).thenReturn(true);
        when(emergencyHandler.checkEmergencies(any())).thenReturn(Optional.empty());
        
        taskExecutor = new TaskExecutor(taskContext);
        taskExecutor.setBreakScheduler(breakScheduler);
        taskExecutor.setEmergencyHandler(emergencyHandler);
        taskExecutor.start();
    }

    // ========================================================================
    // Behavioral Task Pause/Resume Tests
    // ========================================================================

    @Test
    public void testBehavioralTask_PausesCurrentTask() {
        // Queue a normal task that takes multiple ticks
        CountingTask normalTask = new CountingTask("Normal Task", TaskPriority.NORMAL, 5);
        taskExecutor.queueTask(normalTask);
        
        // Execute one tick to start the task
        taskExecutor.onGameTick(null);
        assertEquals("Normal task should be running", TaskState.RUNNING, normalTask.getState());
        
        // Simulate step completion
        taskExecutor.setCurrentStepComplete(true);
        
        // Queue a behavioral task (needs multiple ticks to still be current after onGameTick)
        CountingTask behavioralTask = new CountingTask("Behavioral Task", TaskPriority.BEHAVIORAL, 3);
        when(breakScheduler.getScheduledBreak()).thenReturn(Optional.of(behavioralTask));
        
        // Execute tick - should pause normal and start behavioral
        taskExecutor.onGameTick(null);
        
        assertEquals("Behavioral task should be running", behavioralTask, taskExecutor.getCurrentTask());
        assertEquals("Normal task should be PAUSED", TaskState.PAUSED, normalTask.getState());
    }

    @Test
    public void testBehavioralCompletion_ResumesOriginalTask() {
        // Queue and start normal task
        CountingTask normalTask = new CountingTask("Normal Task", TaskPriority.NORMAL, 10);
        taskExecutor.queueTask(normalTask);
        taskExecutor.onGameTick(null);
        taskExecutor.setCurrentStepComplete(true);
        
        // Queue behavioral task
        CountingTask behavioralTask = new CountingTask("Behavioral Task", TaskPriority.BEHAVIORAL, 1);
        when(breakScheduler.getScheduledBreak())
            .thenReturn(Optional.of(behavioralTask))
            .thenReturn(Optional.empty());
        
        taskExecutor.onGameTick(null);
        
        // Complete behavioral task
        taskExecutor.onGameTick(null);
        
        // Should resume normal task
        taskExecutor.onGameTick(null);
        assertEquals("Should resume normal task", normalTask, taskExecutor.getCurrentTask());
        assertEquals("Normal task should be RUNNING", TaskState.RUNNING, normalTask.getState());
    }

    // ========================================================================
    // Emergency During Behavioral Task Tests (Edge Case #11)
    // ========================================================================

    @Test
    public void testUrgentEmergency_InterruptsBehavioralTask_PreservesPauseStack() {
        // Queue and start normal task
        CountingTask normalTask = new CountingTask("Normal Task", TaskPriority.NORMAL, 10);
        taskExecutor.queueTask(normalTask);
        taskExecutor.onGameTick(null);
        taskExecutor.setCurrentStepComplete(true);
        
        // Queue behavioral task (pauses normal) - needs multiple ticks
        CountingTask behavioralTask = new CountingTask("Behavioral Task", TaskPriority.BEHAVIORAL, 5);
        when(breakScheduler.getScheduledBreak())
            .thenReturn(Optional.of(behavioralTask))
            .thenReturn(Optional.empty());
        taskExecutor.onGameTick(null);
        assertEquals("Behavioral task should be running", behavioralTask, taskExecutor.getCurrentTask());
        
        // While behavioral task runs, trigger urgent emergency (needs multiple ticks)
        CountingTask emergencyTask = new CountingTask("Emergency Task", TaskPriority.URGENT, 2);
        taskExecutor.queueTask(emergencyTask, TaskPriority.URGENT);
        
        // Execute - should interrupt behavioral
        taskExecutor.onGameTick(null);
        assertEquals("Emergency should be running", emergencyTask, taskExecutor.getCurrentTask());
        
        // Complete emergency (takes 2 ticks)
        taskExecutor.onGameTick(null);
        
        // Should resume normal task (NOT behavioral) after emergency completes
        taskExecutor.onGameTick(null);
        assertEquals("Should resume original normal task", normalTask, taskExecutor.getCurrentTask());
    }

    @Test
    public void testNestedBehavioralTasks_MaintainPauseStack() {
        // Queue normal task
        CountingTask normalTask = new CountingTask("Normal Task", TaskPriority.NORMAL, 10);
        taskExecutor.queueTask(normalTask);
        taskExecutor.onGameTick(null);
        assertEquals("Normal task should be running", normalTask, taskExecutor.getCurrentTask());
        taskExecutor.setCurrentStepComplete(true);
        
        // First behavioral task (pauses normal)
        CountingTask behavioral1 = new CountingTask("Behavioral 1", TaskPriority.BEHAVIORAL, 5);
        when(breakScheduler.getScheduledBreak())
            .thenReturn(Optional.of(behavioral1))
            .thenReturn(Optional.empty());
        taskExecutor.onGameTick(null);
        assertEquals("Behavioral1 should be running", behavioral1, taskExecutor.getCurrentTask());
        
        // Second behavioral task (nested - pauses behavioral1)
        CountingTask behavioral2 = new CountingTask("Behavioral 2", TaskPriority.BEHAVIORAL, 3);
        when(breakScheduler.getScheduledBreak())
            .thenReturn(Optional.of(behavioral2))
            .thenReturn(Optional.empty());
        taskExecutor.setCurrentStepComplete(true);
        taskExecutor.onGameTick(null);
        assertEquals("Behavioral2 should be running", behavioral2, taskExecutor.getCurrentTask());
        
        // Complete behavioral2 (3 ticks total)
        taskExecutor.onGameTick(null);
        taskExecutor.onGameTick(null);
        
        // Should resume behavioral1 after behavioral2 completes
        taskExecutor.onGameTick(null);
        assertEquals("Should resume behavioral1", behavioral1, taskExecutor.getCurrentTask());
        
        // Complete behavioral1 (remaining ticks)
        for (int i = 0; i < 5; i++) {
            taskExecutor.onGameTick(null);
        }
        
        // Should resume normal task after behavioral1 completes
        taskExecutor.onGameTick(null);
        assertEquals("Should resume normal task", normalTask, taskExecutor.getCurrentTask());
    }

    // ========================================================================
    // Helper Classes
    // ========================================================================

    /**
     * Simple task that executes for a fixed number of ticks.
     */
    private static class CountingTask extends AbstractTask {
        private final String description;
        private final int ticksToComplete;
        private int ticksExecuted = 0;

        CountingTask(String description, TaskPriority priority) {
            this(description, priority, 1);
        }

        CountingTask(String description, TaskPriority priority, int ticksToComplete) {
            this.description = description;
            this.priority = priority;
            this.ticksToComplete = ticksToComplete;
            this.timeout = Duration.ofSeconds(10);
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public boolean canExecute(TaskContext ctx) {
            return true;
        }

        @Override
        protected void executeImpl(TaskContext ctx) {
            ticksExecuted++;
            if (ticksExecuted >= ticksToComplete) {
                complete();
            }
        }
    }
}

