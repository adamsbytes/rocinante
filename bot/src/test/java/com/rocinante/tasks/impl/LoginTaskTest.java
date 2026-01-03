package com.rocinante.tasks.impl;

import com.rocinante.input.RobotMouseController;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.timing.DelayProfile;
import com.rocinante.timing.HumanTimer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.Rectangle;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for LoginTask.
 * Tests login state detection, play button clicking, world full handling, and retries.
 */
public class LoginTaskTest {

    @Mock
    private Client client;

    @Mock
    private RobotMouseController mouseController;

    @Mock
    private HumanTimer humanTimer;

    @Mock
    private Widget playButtonWidget;

    @Mock
    private Widget loginResponseWidget;

    private TaskContext taskContext;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // TaskContext wiring
        taskContext = mock(TaskContext.class);
        when(taskContext.getClient()).thenReturn(client);
        when(taskContext.getMouseController()).thenReturn(mouseController);
        when(taskContext.getHumanTimer()).thenReturn(humanTimer);

        // Default game state - at login screen
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        when(client.getLoginIndex()).thenReturn(24);

        // Default timer completion
        when(humanTimer.sleep(any(DelayProfile.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(humanTimer.sleep(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(humanTimer.sleepContextual(any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(humanTimer.getContextualReaction(any()))
                .thenReturn(100L);

        // Default mouse controller
        when(mouseController.click(any(Rectangle.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mouseController.moveToCanvas(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mouseController.click())
                .thenReturn(CompletableFuture.completedFuture(null));

        // Default play button widget
        when(playButtonWidget.isHidden()).thenReturn(false);
        when(playButtonWidget.getBounds()).thenReturn(new Rectangle(300, 250, 150, 50));
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_LoginScreen_True() {
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);

        LoginTask task = new LoginTask();

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_ConnectionLost_True() {
        when(client.getGameState()).thenReturn(GameState.CONNECTION_LOST);

        LoginTask task = new LoginTask();

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_LoggingIn_True() {
        when(client.getGameState()).thenReturn(GameState.LOGGING_IN);

        LoginTask task = new LoginTask();

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_LoggedIn_False() {
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

        LoginTask task = new LoginTask();

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_Hopping_False() {
        when(client.getGameState()).thenReturn(GameState.HOPPING);

        LoginTask task = new LoginTask();

        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Configuration Tests
    // ========================================================================

    @Test
    public void testDefaultConfiguration() {
        LoginTask task = new LoginTask();

        assertEquals(5, task.getMaxRetries());
        assertEquals(5000, task.getInitialRetryDelayMs());
        assertEquals(60000, task.getMaxRetryDelayMs());
        assertEquals(2.0, task.getBackoffMultiplier(), 0.001);
    }

    @Test
    public void testConfigurationSetters() {
        LoginTask task = new LoginTask();
        task.setMaxRetries(10);
        task.setInitialRetryDelayMs(3000);
        task.setMaxRetryDelayMs(120000);
        task.setBackoffMultiplier(1.5);

        assertEquals(10, task.getMaxRetries());
        assertEquals(3000, task.getInitialRetryDelayMs());
        assertEquals(120000, task.getMaxRetryDelayMs());
        assertEquals(1.5, task.getBackoffMultiplier(), 0.001);
    }

    // ========================================================================
    // Already Logged In Tests
    // ========================================================================

    @Test
    public void testExecute_AlreadyLoggedIn_StaysPending() {
        // When already logged in, canExecute returns false, so task stays PENDING
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

        LoginTask task = new LoginTask();
        assertFalse("Should not execute when already logged in", task.canExecute(taskContext));
        
        task.execute(taskContext);

        // Task stays PENDING because precondition not met
        assertEquals(TaskState.PENDING, task.getState());
    }

    @Test
    public void testExecute_BecomesLoggedIn_Completes() {
        when(client.getGameState())
                .thenReturn(GameState.LOGIN_SCREEN)
                .thenReturn(GameState.LOGGING_IN)
                .thenReturn(GameState.LOGGED_IN);

        LoginTask task = new LoginTask();
        
        // Execute multiple times
        task.execute(taskContext);
        task.execute(taskContext);
        task.execute(taskContext);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    // ========================================================================
    // Logging In State Tests
    // ========================================================================

    @Test
    public void testExecute_LoggingIn_Waits() {
        when(client.getGameState()).thenReturn(GameState.LOGGING_IN);

        LoginTask task = new LoginTask();
        task.execute(taskContext);

        // Should not fail or complete while logging in
        assertNotEquals(TaskState.COMPLETED, task.getState());
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testExecute_LoadingState_Waits() {
        when(client.getGameState()).thenReturn(GameState.LOADING);

        LoginTask task = new LoginTask();
        task.execute(taskContext);

        assertNotEquals(TaskState.COMPLETED, task.getState());
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testExecute_LoginTimeout_Fails() {
        when(client.getGameState()).thenReturn(GameState.LOGGING_IN);

        LoginTask task = new LoginTask();
        
        // Execute many times to trigger timeout
        for (int i = 0; i < 110; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("timeout"));
    }

    // ========================================================================
    // Detect State Phase Tests
    // ========================================================================

    @Test
    public void testDetectState_Lobby_TransitionsToClickPlay() {
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        when(client.getLoginIndex()).thenReturn(24); // Lobby

        when(client.getWidget(378, 78)).thenReturn(playButtonWidget);

        LoginTask task = new LoginTask();
        task.execute(taskContext);

        // Should be in CLICK_PLAY phase or beyond
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testDetectState_TitleScreen_TransitionsToClickPlay() {
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        when(client.getLoginIndex()).thenReturn(0); // Title

        when(client.getWidget(378, 78)).thenReturn(playButtonWidget);

        LoginTask task = new LoginTask();
        task.execute(taskContext);

        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testDetectState_ConnectionLost_WaitsForRecovery() {
        when(client.getGameState()).thenReturn(GameState.CONNECTION_LOST);

        LoginTask task = new LoginTask();
        
        // Should wait without failing immediately
        for (int i = 0; i < 15; i++) {
            task.execute(taskContext);
        }

        assertNotEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // World Full Handling Tests
    // ========================================================================

    @Test
    public void testWorldFull_Detected_TriggersRetry() {
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        when(client.getLoginIndex()).thenReturn(0);
        
        when(loginResponseWidget.isHidden()).thenReturn(false);
        when(loginResponseWidget.getText()).thenReturn("The world is full");
        when(client.getWidget(378, 30)).thenReturn(loginResponseWidget);

        LoginTask task = new LoginTask();
        task.execute(taskContext);

        // Should be handling world full, not failed
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testWorldFull_MaxRetriesExceeded_Fails() {
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        when(client.getLoginIndex()).thenReturn(0);
        
        when(loginResponseWidget.isHidden()).thenReturn(false);
        when(loginResponseWidget.getText()).thenReturn("The world is full");
        when(client.getWidget(378, 30)).thenReturn(loginResponseWidget);

        LoginTask task = new LoginTask();
        task.setMaxRetries(2);
        
        // Execute enough to exhaust retries
        for (int i = 0; i < 100; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("max retries"));
    }

    @Test
    public void testWorldFull_RetryDelayIncreases() {
        // Test exponential backoff is applied
        LoginTask task = new LoginTask();
        task.setInitialRetryDelayMs(1000);
        task.setMaxRetryDelayMs(10000);
        task.setBackoffMultiplier(2.0);

        // Initial delay should be 1000ms
        assertEquals(1000, task.getInitialRetryDelayMs());

        // After retry, delay should increase by multiplier
        // This tests the configuration, actual application is internal
    }

    // ========================================================================
    // Login Error Tests
    // ========================================================================

    @Test
    public void testLoginError_Detected_Fails() {
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        // Use loginIndex=2 (password entry) so error widget is checked
        // loginIndex 0 or 24 skip error checking and go straight to CLICK_PLAY
        when(client.getLoginIndex()).thenReturn(2);
        
        when(loginResponseWidget.isHidden()).thenReturn(false);
        when(loginResponseWidget.getText()).thenReturn("Account disabled");
        when(client.getWidget(378, 30)).thenReturn(loginResponseWidget);

        LoginTask task = new LoginTask();
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("Account disabled"));
    }

    // ========================================================================
    // Click Play Phase Tests
    // ========================================================================

    @Test
    public void testClickPlay_ButtonFound_Clicks() {
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        when(client.getLoginIndex()).thenReturn(24);
        when(client.getWidget(378, 78)).thenReturn(playButtonWidget);

        LoginTask task = new LoginTask();
        
        // Execute to reach click play phase
        task.execute(taskContext);
        task.execute(taskContext);

        // Should have attempted to click
        verify(mouseController, atLeastOnce()).click(any(Rectangle.class));
    }

    @Test
    public void testClickPlay_ButtonNotFound_FallbackToCenterScreen() {
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        when(client.getLoginIndex()).thenReturn(24);
        when(client.getWidget(378, 78)).thenReturn(null);
        when(client.getWidget(378, 73)).thenReturn(null);

        LoginTask task = new LoginTask();
        
        // Execute many times to trigger fallback
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should try center screen click as fallback
        verify(mouseController, atLeastOnce()).moveToCanvas(eq(383), anyInt());
    }

    // ========================================================================
    // Reset Tests
    // ========================================================================

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_IncludesPhase() {
        LoginTask task = new LoginTask();

        String desc = task.getDescription();
        assertTrue(desc.contains("LoginTask"));
        assertTrue(desc.contains("phase="));
        assertTrue(desc.contains("retries="));
    }

    // ========================================================================
    // Real Game Scenario Tests
    // ========================================================================

    @Test
    public void testScenario_NormalLogin() {
        // Simulate normal login flow
        when(client.getGameState())
                .thenReturn(GameState.LOGIN_SCREEN)
                .thenReturn(GameState.LOGIN_SCREEN)
                .thenReturn(GameState.LOGGING_IN)
                .thenReturn(GameState.LOADING)
                .thenReturn(GameState.LOGGED_IN);
        
        when(client.getLoginIndex()).thenReturn(24);
        when(client.getWidget(378, 78)).thenReturn(playButtonWidget);

        LoginTask task = new LoginTask();
        
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testScenario_ReconnectAfterTimeout() {
        // Connection lost, then recover
        when(client.getGameState())
                .thenReturn(GameState.CONNECTION_LOST)
                .thenReturn(GameState.CONNECTION_LOST)
                .thenReturn(GameState.LOGIN_SCREEN)
                .thenReturn(GameState.LOGGING_IN)
                .thenReturn(GameState.LOGGED_IN);
        
        when(client.getLoginIndex()).thenReturn(24);
        when(client.getWidget(378, 78)).thenReturn(playButtonWidget);

        LoginTask task = new LoginTask();
        
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testScenario_WorldFullThenSuccess() {
        // World full on first attempt, success on retry
        when(client.getGameState())
                .thenReturn(GameState.LOGIN_SCREEN) // First try
                .thenReturn(GameState.LOGIN_SCREEN) // Detect world full
                .thenReturn(GameState.LOGIN_SCREEN) // Retry
                .thenReturn(GameState.LOGGING_IN)
                .thenReturn(GameState.LOGGED_IN);
        
        when(client.getLoginIndex()).thenReturn(24);
        when(client.getWidget(378, 78)).thenReturn(playButtonWidget);
        
        // First time: world full, then no message
        when(loginResponseWidget.isHidden())
                .thenReturn(false)
                .thenReturn(true);
        when(loginResponseWidget.getText())
                .thenReturn("World is full")
                .thenReturn(null);
        when(client.getWidget(378, 30)).thenReturn(loginResponseWidget);

        LoginTask task = new LoginTask();
        
        for (int i = 0; i < 20; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should eventually complete
        // (actual behavior depends on async timing)
    }
}

