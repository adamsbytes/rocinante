package com.rocinante.tasks;

import com.rocinante.state.StateCondition;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CompositeTask loop execution.
 * Tests stop condition evaluation timing (Bug #5 fix).
 */
public class CompositeTaskLoopTest {

    @Mock
    private TaskContext taskContext;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(taskContext.isLoggedIn()).thenReturn(true);
    }

    // ========================================================================
    // Loop Stop Condition Tests (Bug #5)
    // ========================================================================

    @Test
    public void testLoopStopCondition_CheckedBeforeNextIteration() {
        // Create a counter we can track
        int[] counter = {0};
        
        // Stop condition becomes true after first iteration
        StateCondition stopCondition = ctx -> counter[0] >= 1;
        
        // Child task that increments counter
        Task childTask = new AbstractTask() {
            @Override
            public String getDescription() {
                return "Counter Task";
            }

            @Override
            public boolean canExecute(TaskContext ctx) {
                return true;
            }

            @Override
            protected void executeImpl(TaskContext ctx) {
                counter[0]++;
                complete();
            }
        };
        
        // Create loop with stop condition
        CompositeTask loop = CompositeTask.loop(childTask)
            .untilCondition(stopCondition);
        
        // Execute first iteration
        loop.execute(taskContext);
        assertEquals("Counter should be 1 after first iteration", 1, counter[0]);
        
        // Execute again - should check condition and stop
        loop.execute(taskContext);
        
        assertEquals("Loop should stop after condition met", TaskState.COMPLETED, loop.getState());
        assertEquals("Counter should still be 1 (no extra iteration)", 1, counter[0]);
    }

    @Test
    public void testLoopMaxIterations_CheckedBeforeNextIteration() {
        int[] executionCount = {0};
        
        Task childTask = new AbstractTask() {
            @Override
            public String getDescription() {
                return "Counter";
            }

            @Override
            public boolean canExecute(TaskContext ctx) {
                return true;
            }

            @Override
            protected void executeImpl(TaskContext ctx) {
                executionCount[0]++;
                complete();
            }
        };
        
        // Create loop with max 3 iterations
        CompositeTask loop = CompositeTask.loop(childTask).withMaxIterations(3);
        
        // Execute 3 iterations
        for (int i = 0; i < 10; i++) {
            if (loop.getState().isTerminal()) {
                break;
            }
            loop.execute(taskContext);
        }
        
        assertEquals("Should execute exactly 3 iterations", 3, executionCount[0]);
        assertEquals("Loop should be complete", TaskState.COMPLETED, loop.getState());
    }

    @Test
    public void testLoopStopCondition_PreventsPrematureStart() {
        // Condition is true from the start
        StateCondition stopCondition = ctx -> true;
        
        int[] executionCount = {0};
        Task childTask = new AbstractTask() {
            @Override
            public String getDescription() {
                return "Child";
            }

            @Override
            public boolean canExecute(TaskContext ctx) {
                return true;
            }

            @Override
            protected void executeImpl(TaskContext ctx) {
                executionCount[0]++;
                complete();
            }
        };
        
        CompositeTask loop = CompositeTask.loop(childTask).untilCondition(stopCondition);
        
        // Execute - should check condition before first iteration
        loop.execute(taskContext);
        
        assertEquals("Loop should complete immediately without executing children", 
                TaskState.COMPLETED, loop.getState());
        assertEquals("Child should not have executed", 0, executionCount[0]);
    }
}

