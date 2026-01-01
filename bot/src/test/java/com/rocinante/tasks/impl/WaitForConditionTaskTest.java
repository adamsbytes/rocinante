package com.rocinante.tasks.impl;

import com.rocinante.core.GameStateService;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.state.PlayerState;
import com.rocinante.state.StateCondition;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for WaitForConditionTask.
 * Tests condition evaluation, timeouts, early exit, and idle mouse behavior.
 */
public class WaitForConditionTaskTest {

    @Mock
    private GameStateService gameStateService;

    @Mock
    private RobotMouseController mouseController;

    @Mock
    private RobotKeyboardController keyboardController;

    @Mock
    private HumanTimer humanTimer;

    @Mock
    private Randomization randomization;

    private TaskContext taskContext;
    private PlayerState playerState;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        playerState = PlayerState.builder()
                .animationId(-1)
                .isMoving(false)
                .isInteracting(false)
                .build();

        // Wire up TaskContext
        taskContext = mock(TaskContext.class);
        when(taskContext.getGameStateService()).thenReturn(gameStateService);
        when(taskContext.getMouseController()).thenReturn(mouseController);
        when(taskContext.getKeyboardController()).thenReturn(keyboardController);
        when(taskContext.getHumanTimer()).thenReturn(humanTimer);
        when(taskContext.getRandomization()).thenReturn(randomization);
        when(taskContext.isLoggedIn()).thenReturn(true);
        when(taskContext.getPlayerState()).thenReturn(playerState);

        // Randomization defaults
        when(randomization.chance(anyDouble())).thenReturn(false);
        when(randomization.uniformRandomInt(anyInt(), anyInt())).thenReturn(10);

        // Mouse controller
        when(mouseController.getCurrentPosition()).thenReturn(new Point(400, 300));
        when(mouseController.moveToScreen(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Test
    public void testDefaultConfiguration() {
        StateCondition condition = ctx -> true;
        WaitForConditionTask task = new WaitForConditionTask(condition);

        assertEquals(condition, task.getCondition());
        assertTrue(task.isIdleMouseBehavior());
        assertNull(task.getEarlyExitCondition());
        assertEquals(100, task.getInactivityTimeoutTicks()); // Default 100 ticks
    }

    // ========================================================================
    // Factory Method Tests
    // ========================================================================

    @Test
    public void testUntilIdle_CreatesCorrectCondition() {
        WaitForConditionTask task = WaitForConditionTask.untilIdle();

        // Player is idle - condition should be true
        assertTrue(task.getCondition().test(taskContext));
    }

    @Test
    public void testUntilAnimationComplete_CreatesCorrectCondition() {
        WaitForConditionTask task = WaitForConditionTask.untilAnimationComplete();

        // Player not animating - condition should be true
        assertTrue(task.getCondition().test(taskContext));
    }

    @Test
    public void testForTicks_CreatesCorrectCondition() {
        WaitForConditionTask task = WaitForConditionTask.forTicks(5);

        // Should be false for first 4 ticks
        for (int i = 0; i < 4; i++) {
            assertFalse(task.getCondition().test(taskContext));
        }
        // True on 5th tick
        assertTrue(task.getCondition().test(taskContext));
    }

    // ========================================================================
    // Builder Method Tests
    // ========================================================================

    @Test
    public void testBuilderMethods() {
        StateCondition exitCondition = ctx -> false;
        Runnable callback = () -> {};

        WaitForConditionTask task = new WaitForConditionTask(ctx -> true)
                .withIdleMouseBehavior(false)
                .withEarlyExitCondition(exitCondition)
                .withOnConditionMet(callback)
                .withInactivityTimeout(100)  // 100 ticks ≈ 60 seconds
                .withDescription("Wait for test")
                .withVerboseLogging(true);

        assertFalse(task.isIdleMouseBehavior());
        assertEquals(exitCondition, task.getEarlyExitCondition());
        assertEquals(callback, task.getOnConditionMet());
        assertEquals(100, task.getInactivityTimeoutTicks());
        assertEquals("Wait for test", task.getDescription());
        assertTrue(task.isVerboseLogging());
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_AlwaysTrue() {
        WaitForConditionTask task = new WaitForConditionTask(ctx -> false);

        // Can always execute - waiting is always possible
        assertTrue(task.canExecute(taskContext));
    }

    // ========================================================================
    // Condition Evaluation Tests
    // ========================================================================

    @Test
    public void testCondition_TrueImmediately_Completes() {
        WaitForConditionTask task = new WaitForConditionTask(ctx -> true)
                .withIdleMouseBehavior(false);

        task.execute(taskContext);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testCondition_BecomesTrue_Completes() {
        AtomicBoolean conditionValue = new AtomicBoolean(false);
        WaitForConditionTask task = new WaitForConditionTask(ctx -> conditionValue.get())
                .withIdleMouseBehavior(false);

        // Execute while false
        task.execute(taskContext);
        assertNotEquals(TaskState.COMPLETED, task.getState());

        task.execute(taskContext);
        assertNotEquals(TaskState.COMPLETED, task.getState());

        // Condition becomes true
        conditionValue.set(true);
        task.execute(taskContext);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testCondition_TracksWaitTicks() {
        WaitForConditionTask task = new WaitForConditionTask(ctx -> false)
                .withIdleMouseBehavior(false);

        // Execute multiple times
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(10, task.getWaitTicks());
    }

    @Test
    public void testCondition_ExceptionHandled() {
        WaitForConditionTask task = new WaitForConditionTask(ctx -> {
            throw new RuntimeException("Test exception");
        }).withIdleMouseBehavior(false);

        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("error"));
    }

    // ========================================================================
    // Callback Tests
    // ========================================================================

    @Test
    public void testOnConditionMet_CalledOnSuccess() {
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        
        WaitForConditionTask task = new WaitForConditionTask(ctx -> true)
                .withIdleMouseBehavior(false)
                .withOnConditionMet(() -> callbackInvoked.set(true));

        task.execute(taskContext);

        assertTrue(callbackInvoked.get());
        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testOnConditionMet_ExceptionIgnored() {
        WaitForConditionTask task = new WaitForConditionTask(ctx -> true)
                .withIdleMouseBehavior(false)
                .withOnConditionMet(() -> {
                    throw new RuntimeException("Callback error");
                });

        // Should not crash
        task.execute(taskContext);

        // Still completes successfully
        assertEquals(TaskState.COMPLETED, task.getState());
    }

    // ========================================================================
    // Early Exit Tests
    // ========================================================================

    @Test
    public void testEarlyExit_ConditionTrue_Fails() {
        AtomicBoolean exitConditionValue = new AtomicBoolean(false);
        
        WaitForConditionTask task = new WaitForConditionTask(ctx -> false) // Main never true
                .withIdleMouseBehavior(false)
                .withEarlyExitCondition(ctx -> exitConditionValue.get());

        // Execute while early exit is false
        task.execute(taskContext);
        assertNotEquals(TaskState.FAILED, task.getState());

        // Early exit condition becomes true
        exitConditionValue.set(true);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("Early exit"));
    }

    @Test
    public void testEarlyExit_MainConditionTrueFirst_Completes() {
        AtomicBoolean mainCondition = new AtomicBoolean(false);
        AtomicBoolean earlyExit = new AtomicBoolean(false);
        
        WaitForConditionTask task = new WaitForConditionTask(ctx -> mainCondition.get())
                .withIdleMouseBehavior(false)
                .withEarlyExitCondition(ctx -> earlyExit.get());

        // Main condition becomes true first
        mainCondition.set(true);
        task.execute(taskContext);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testEarlyExit_CheckedBeforeMain() {
        AtomicInteger evaluationOrder = new AtomicInteger(0);
        
        WaitForConditionTask task = new WaitForConditionTask(ctx -> {
            // This would return true
            evaluationOrder.set(2);
            return true;
        }).withIdleMouseBehavior(false)
          .withEarlyExitCondition(ctx -> {
              // This is checked first and returns true
              evaluationOrder.set(1);
              return true;
          });

        task.execute(taskContext);

        // Early exit was checked first
        assertEquals(1, evaluationOrder.get());
        assertEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Idle Mouse Behavior Tests
    // ========================================================================

    @Test
    public void testIdleMouse_Enabled_MovesAfterInterval() {
        // Setup randomization to trigger mouse movement
        when(randomization.chance(0.6)).thenReturn(true);
        when(randomization.uniformRandomInt(anyInt(), anyInt())).thenReturn(1); // Short interval

        WaitForConditionTask task = new WaitForConditionTask(ctx -> false)
                .withIdleMouseBehavior(true);

        // Execute enough times to trigger idle movement
        // Initial countdown from constructor uses random (5-15), so we need more iterations
        for (int i = 0; i < 20; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should have moved mouse at least once
        verify(mouseController, atLeastOnce()).moveToScreen(anyInt(), anyInt());
    }

    @Test
    public void testIdleMouse_Disabled_NoMovement() {
        WaitForConditionTask task = new WaitForConditionTask(ctx -> false)
                .withIdleMouseBehavior(false);

        // Execute several times
        for (int i = 0; i < 20; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should never move mouse
        verify(mouseController, never()).moveToScreen(anyInt(), anyInt());
    }

    @Test
    public void testIdleMouse_ChanceRejected_NoMovement() {
        when(randomization.chance(0.6)).thenReturn(false);
        when(randomization.uniformRandomInt(anyInt(), anyInt())).thenReturn(1); // Short interval

        WaitForConditionTask task = new WaitForConditionTask(ctx -> false)
                .withIdleMouseBehavior(true);

        // Execute several times
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Chance was rejected, so no movement
        verify(mouseController, never()).moveToScreen(anyInt(), anyInt());
    }

    @Test
    public void testIdleMouse_NoMousePosition_NoMovement() {
        when(mouseController.getCurrentPosition()).thenReturn(null);
        when(randomization.chance(0.6)).thenReturn(true);
        when(randomization.uniformRandomInt(anyInt(), anyInt())).thenReturn(1);

        WaitForConditionTask task = new WaitForConditionTask(ctx -> false)
                .withIdleMouseBehavior(true);

        // Execute several times
        for (int i = 0; i < 5; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // No mouse position available, should not move
        verify(mouseController, never()).moveToScreen(anyInt(), anyInt());
    }

    // ========================================================================
    // Progress Tracking Tests
    // ========================================================================

    @Test
    public void testGetProgress_CalculatesCorrectly() {
        WaitForConditionTask task = new WaitForConditionTask(ctx -> false)
                .withIdleMouseBehavior(false)
                .withInactivityTimeout(50);  // 50 ticks

        // Execute 25 ticks = 50% of inactivity timeout
        for (int i = 0; i < 25; i++) {
            task.execute(taskContext);
        }

        double progress = task.getProgress();
        assertTrue(progress > 0.4 && progress < 0.6); // ~50%
    }

    @Test
    public void testGetProgress_ZeroTimeout_ReturnsZero() {
        WaitForConditionTask task = new WaitForConditionTask(ctx -> false)
                .withIdleMouseBehavior(false)
                .withInactivityTimeout(0);

        task.execute(taskContext);

        assertEquals(0.0, task.getProgress(), 0.001);
    }

    // ========================================================================
    // Real Game Scenario Tests
    // ========================================================================

    @Test
    public void testWaitForPlayerIdle_AnimatingThenIdle() {
        AtomicBoolean isIdle = new AtomicBoolean(false);
        
        PlayerState animatingState = PlayerState.builder()
                .animationId(123)
                .build();
        
        PlayerState idleState = PlayerState.builder()
                .animationId(-1)
                .isMoving(false)
                .isInteracting(false)
                .build();

        when(taskContext.getPlayerState()).thenAnswer(inv -> 
                isIdle.get() ? idleState : animatingState);

        WaitForConditionTask task = WaitForConditionTask.untilIdle()
                .withIdleMouseBehavior(false);

        // Execute while animating
        task.execute(taskContext);
        assertNotEquals(TaskState.COMPLETED, task.getState());

        task.execute(taskContext);
        assertNotEquals(TaskState.COMPLETED, task.getState());

        // Player becomes idle
        isIdle.set(true);
        task.execute(taskContext);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testWaitWithHealthEarlyExit() {
        AtomicInteger health = new AtomicInteger(100);
        
        StateCondition bankOpen = ctx -> false; // Bank never opens
        StateCondition lowHealth = ctx -> health.get() < 30;

        WaitForConditionTask task = new WaitForConditionTask(bankOpen)
                .withIdleMouseBehavior(false)
                .withEarlyExitCondition(lowHealth)
                .withDescription("Wait for bank to open");

        // Wait while healthy
        task.execute(taskContext);
        assertNotEquals(TaskState.FAILED, task.getState());

        task.execute(taskContext);
        assertNotEquals(TaskState.FAILED, task.getState());

        // Health drops below threshold
        health.set(20);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("Early exit"));
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_Default() {
        WaitForConditionTask task = new WaitForConditionTask(ctx -> true);

        assertEquals("WaitForCondition", task.getDescription());
    }

    @Test
    public void testGetDescription_Custom() {
        WaitForConditionTask task = new WaitForConditionTask(ctx -> true)
                .withDescription("Waiting for inventory");

        assertEquals("Waiting for inventory", task.getDescription());
    }

    @Test
    public void testGetDescription_FactoryMethods() {
        assertEquals("Wait until idle", WaitForConditionTask.untilIdle().getDescription());
        assertEquals("Wait for animation complete", WaitForConditionTask.untilAnimationComplete().getDescription());
        assertTrue(WaitForConditionTask.forTicks(10).getDescription().contains("10"));
    }
}

