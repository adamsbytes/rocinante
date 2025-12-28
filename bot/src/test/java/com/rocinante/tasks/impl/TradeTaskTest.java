package com.rocinante.tasks.impl;

import com.rocinante.core.GameStateService;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskPriority;
import com.rocinante.tasks.TaskState;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TradeTask.
 * Tests trade flow, phase transitions, and scam protection.
 */
public class TradeTaskTest {

    // Widget IDs
    private static final int TRADE_MAIN_GROUP = 335;
    private static final int TRADE_CONFIRM_GROUP = 334;

    @Mock
    private Client client;

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

    @Mock
    private Widget tradeMainWidget;

    @Mock
    private Widget tradeConfirmWidget;

    @Mock
    private Widget acceptButton;

    @Mock
    private Widget statusWidget;

    @Mock
    private Widget otherOfferWidget;

    private TaskContext taskContext;
    private PlayerState loggedInPlayer;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Set up TaskContext
        taskContext = new TaskContext(
                client,
                gameStateService,
                mouseController,
                keyboardController,
                humanTimer,
                null, // targetSelector
                null, // combatManager
                null, // gearSwitcher
                null, // foodManager
                null, // inventoryClickHelper
                null, // groundItemClickHelper
                null, // widgetClickHelper
                null, // menuHelper
                null, // unlockTracker
                null, // agilityCourseRepository
                null, // playerProfile
                null, // puzzleSolverRegistry
                null, // cameraController
                null, // mouseCameraCoupler
                null, // actionSequencer
                null, // inefficiencyInjector
                null, // logoutHandler
                null, // breakScheduler
                randomization,
                null, // pathFinder
                null, // webWalker
                null, // obstacleHandler
                null  // planeTransitionHandler
        );

        // Player logged in
        loggedInPlayer = PlayerState.builder()
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .currentHitpoints(99)
                .maxHitpoints(99)
                .build();

        // Default mock behaviors
        when(gameStateService.isLoggedIn()).thenReturn(true);
        when(gameStateService.getPlayerState()).thenReturn(loggedInPlayer);
        
        // Widget bounds for clicking
        java.awt.Rectangle bounds = new java.awt.Rectangle(100, 100, 80, 20);
        when(acceptButton.getBounds()).thenReturn(bounds);
        when(acceptButton.isHidden()).thenReturn(false);

        // Mouse/keyboard futures
        when(mouseController.moveToCanvas(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mouseController.click())
                .thenReturn(CompletableFuture.completedFuture(null));
        when(keyboardController.type(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(keyboardController.pressEnter())
                .thenReturn(CompletableFuture.completedFuture(null));

        // Human timer sleep
        when(humanTimer.sleep(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Randomization
        when(randomization.uniformRandomLong(anyLong(), anyLong()))
                .thenReturn(1500L);
    }

    // ========================================================================
    // Config Tests
    // ========================================================================

    @Test
    public void testTradeConfigDefaultValues() {
        TradeConfig config = TradeConfig.builder().build();
        
        assertFalse(config.isSendResponse());
        assertEquals("Ty!", config.getResponseMessage());
        assertTrue(config.getOfferingItems().isEmpty());
        assertTrue(config.getExpectedItems().isEmpty());
        assertTrue(config.isVerifySecondScreen());
        assertEquals(100, config.getMaxWaitTicks());
        assertEquals(1000, config.getMinAcceptDelayMs());
        assertEquals(3000, config.getMaxAcceptDelayMs());
    }

    @Test
    public void testTradeConfigForRandomTrade() {
        TradeConfig config = TradeConfig.forRandomTrade();
        
        assertTrue(config.isSendResponse());
        assertEquals("Ty!", config.getResponseMessage());
        assertTrue(config.isPassiveTrade());
        assertFalse(config.hasExpectedItems());
    }

    @Test
    public void testTradeConfigShouldVerifyTrade() {
        // Passive trade - no verification needed
        TradeConfig passive = TradeConfig.builder().build();
        assertFalse(passive.shouldVerifyTrade());

        // Active trade with items - should verify
        TradeConfig active = TradeConfig.builder()
                .offeringItems(Map.of(995, 10000)) // offering coins
                .verifySecondScreen(true)
                .build();
        assertTrue(active.shouldVerifyTrade());

        // Active trade with verification disabled
        TradeConfig noVerify = TradeConfig.builder()
                .offeringItems(Map.of(995, 10000))
                .verifySecondScreen(false)
                .build();
        assertFalse(noVerify.shouldVerifyTrade());
    }

    // ========================================================================
    // Task Creation Tests
    // ========================================================================

    @Test
    public void testTradeTaskCreation() {
        TradeConfig config = TradeConfig.forRandomTrade();
        TradeTask task = new TradeTask(config);
        
        assertEquals(TaskState.PENDING, task.getState());
        assertEquals(TaskPriority.BEHAVIORAL, task.getPriority());
        assertNotNull(task.getConfig());
    }

    @Test
    public void testForRandomTradeFactory() {
        TradeTask task = TradeTask.forRandomTrade();
        
        assertNotNull(task);
        assertTrue(task.getConfig().isSendResponse());
    }

    @Test
    public void testTaskDescription() {
        TradeConfig config = TradeConfig.forRandomTrade();
        TradeTask task = new TradeTask(config);
        
        assertNotNull(task.getDescription());
        assertTrue(task.getDescription().contains("TradeTask"));
    }

    @Test
    public void testCustomDescription() {
        TradeConfig config = TradeConfig.forRandomTrade();
        TradeTask task = new TradeTask(config)
                .withDescription("Accept trade from TestPlayer");
        
        assertEquals("Accept trade from TestPlayer", task.getDescription());
    }

    // ========================================================================
    // Precondition Tests
    // ========================================================================

    @Test
    public void testCanExecuteWhenLoggedIn() {
        when(gameStateService.isLoggedIn()).thenReturn(true);
        
        TradeTask task = TradeTask.forRandomTrade();
        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCannotExecuteWhenLoggedOut() {
        when(gameStateService.isLoggedIn()).thenReturn(false);
        
        TradeTask task = TradeTask.forRandomTrade();
        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Trade Screen Detection Tests
    // ========================================================================

    @Test
    public void testWaitForFirstScreenTimeout() {
        // No trade screens visible
        when(client.getWidget(TRADE_MAIN_GROUP, 0)).thenReturn(null);
        when(client.getWidget(TRADE_CONFIRM_GROUP, 0)).thenReturn(null);

        TradeTask task = TradeTask.forRandomTrade();
        
        // Execute multiple ticks without trade screen
        for (int i = 0; i < 15; i++) {
            task.execute(taskContext);
            if (task.getState() == TaskState.FAILED) break;
        }

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("not opened"));
    }

    @Test
    public void testDetectsFirstScreen() {
        // First screen visible
        when(client.getWidget(TRADE_MAIN_GROUP, 0)).thenReturn(tradeMainWidget);
        when(tradeMainWidget.isHidden()).thenReturn(false);
        
        // Status widget (no accept yet)
        when(client.getWidget(TRADE_MAIN_GROUP, 30)).thenReturn(statusWidget);
        when(statusWidget.getText()).thenReturn("Trading with: TestPlayer");

        TradeTask task = TradeTask.forRandomTrade();
        task.execute(taskContext);
        
        // Should transition to monitoring first screen
        assertEquals(TaskState.RUNNING, task.getState());
    }

    // ========================================================================
    // Offer Tracking Tests
    // ========================================================================

    @Test
    public void testTracksTheirOfferValue() {
        // Set up first screen
        when(client.getWidget(TRADE_MAIN_GROUP, 0)).thenReturn(tradeMainWidget);
        when(tradeMainWidget.isHidden()).thenReturn(false);
        
        // Set up offer widget with items
        when(client.getWidget(TRADE_MAIN_GROUP, 28)).thenReturn(otherOfferWidget);
        
        Widget itemWidget = mock(Widget.class);
        when(itemWidget.getItemId()).thenReturn(995); // Coins
        when(itemWidget.getItemQuantity()).thenReturn(10000);
        when(otherOfferWidget.getDynamicChildren()).thenReturn(new Widget[]{itemWidget});
        
        // Set up item composition for HA value
        ItemComposition itemComp = mock(ItemComposition.class);
        when(itemComp.getHaPrice()).thenReturn(1); // 1gp HA for coins
        when(client.getItemDefinition(995)).thenReturn(itemComp);
        
        // Status - not accepted yet
        when(client.getWidget(TRADE_MAIN_GROUP, 30)).thenReturn(statusWidget);
        when(statusWidget.getText()).thenReturn("");

        TradeTask task = TradeTask.forRandomTrade();
        task.execute(taskContext); // Detect screen
        task.execute(taskContext); // Monitor and track offers

        // Value should be tracked (10000 * 1 = 10000)
        assertEquals(10000, task.getTheirOfferValue());
    }

    // ========================================================================
    // Response Message Tests
    // ========================================================================

    @Test
    public void testSendResponseEnabled() {
        TradeConfig config = TradeConfig.builder()
                .sendResponse(true)
                .responseMessage("Thanks!")
                .build();
        
        TradeTask task = new TradeTask(config);
        assertTrue(task.getConfig().isSendResponse());
        assertEquals("Thanks!", task.getConfig().getResponseMessage());
    }

    @Test
    public void testSendResponseDisabled() {
        TradeConfig config = TradeConfig.builder()
                .sendResponse(false)
                .build();
        
        TradeTask task = new TradeTask(config);
        assertFalse(task.getConfig().isSendResponse());
    }

    // ========================================================================
    // Timeout Tests
    // ========================================================================

    @Test
    public void testMaxWaitTicksConfigurable() {
        TradeConfig config = TradeConfig.builder()
                .maxWaitTicks(50)
                .build();
        
        assertEquals(50, config.getMaxWaitTicks());
    }

    @Test
    public void testAcceptDelayConfigurable() {
        TradeConfig config = TradeConfig.builder()
                .minAcceptDelayMs(500)
                .maxAcceptDelayMs(2000)
                .build();
        
        assertEquals(500, config.getMinAcceptDelayMs());
        assertEquals(2000, config.getMaxAcceptDelayMs());
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testHandlesNullWidgetGracefully() {
        when(client.getWidget(anyInt(), anyInt())).thenReturn(null);

        TradeTask task = TradeTask.forRandomTrade();
        
        // Should not throw, should fail gracefully
        for (int i = 0; i < 15; i++) {
            task.execute(taskContext);
            if (task.getState().isTerminal()) break;
        }

        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testHandlesEmptyOfferContainer() {
        when(client.getWidget(TRADE_MAIN_GROUP, 0)).thenReturn(tradeMainWidget);
        when(tradeMainWidget.isHidden()).thenReturn(false);
        when(client.getWidget(TRADE_MAIN_GROUP, 28)).thenReturn(otherOfferWidget);
        when(otherOfferWidget.getDynamicChildren()).thenReturn(new Widget[0]);
        when(client.getWidget(TRADE_MAIN_GROUP, 30)).thenReturn(statusWidget);
        when(statusWidget.getText()).thenReturn("");

        TradeTask task = TradeTask.forRandomTrade();
        task.execute(taskContext);
        task.execute(taskContext);

        // Should handle empty offer gracefully
        assertEquals(0, task.getTheirOfferValue());
    }
}

