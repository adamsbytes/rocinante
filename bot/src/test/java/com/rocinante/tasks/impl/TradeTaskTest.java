package com.rocinante.tasks.impl;

import com.rocinante.core.GameStateService;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.state.IronmanState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.Rectangle;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for TradeTask.
 * Tests trade flow, scam protection, ironman restrictions, and response messages.
 */
public class TradeTaskTest {

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
    private IronmanState ironmanState;

    @Mock
    private Widget tradeMainWidget;

    @Mock
    private Widget tradeConfirmWidget;

    @Mock
    private Widget acceptButton;

    @Mock
    private Widget statusWidget;

    @Mock
    private Widget theirOfferWidget;

    private TaskContext taskContext;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // TaskContext wiring
        taskContext = mock(TaskContext.class);
        when(taskContext.getClient()).thenReturn(client);
        when(taskContext.getGameStateService()).thenReturn(gameStateService);
        when(taskContext.getMouseController()).thenReturn(mouseController);
        when(taskContext.getKeyboardController()).thenReturn(keyboardController);
        when(taskContext.getHumanTimer()).thenReturn(humanTimer);
        when(taskContext.getRandomization()).thenReturn(randomization);
        when(taskContext.isLoggedIn()).thenReturn(true);

        // Default ironman state - NOT ironman
        when(ironmanState.isIronman()).thenReturn(false);
        when(gameStateService.getIronmanState()).thenReturn(ironmanState);

        // Default mouse
        when(mouseController.moveToCanvas(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mouseController.click())
                .thenReturn(CompletableFuture.completedFuture(null));

        // Default keyboard
        when(keyboardController.type(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(keyboardController.pressEnter())
                .thenReturn(CompletableFuture.completedFuture(null));

        // Default timer
        when(humanTimer.sleep(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Default randomization
        when(randomization.uniformRandomLong(anyLong(), anyLong())).thenReturn(1500L);

        // Default accept button
        when(acceptButton.isHidden()).thenReturn(false);
        when(acceptButton.getBounds()).thenReturn(new Rectangle(300, 400, 100, 30));
    }

    // ========================================================================
    // TradeConfig Tests
    // ========================================================================

    @Test
    public void testTradeConfig_Default() {
        TradeConfig config = TradeConfig.builder().build();

        assertFalse(config.isSendResponse());
        assertEquals("Ty!", config.getResponseMessage());
        assertTrue(config.isPassiveTrade());
        assertFalse(config.hasExpectedItems());
    }

    @Test
    public void testTradeConfig_ForRandomTrade() {
        TradeConfig config = TradeConfig.forRandomTrade();

        assertTrue(config.isSendResponse());
        assertEquals("Ty!", config.getResponseMessage());
        assertTrue(config.isPassiveTrade());
    }

    @Test
    public void testTradeConfig_ForDecline() {
        TradeConfig config = TradeConfig.forDecline();

        assertFalse(config.isSendResponse());
        assertEquals(0, config.getMaxWaitTicks());
    }

    @Test
    public void testTradeConfig_WithOffering() {
        TradeConfig config = TradeConfig.builder()
                .offeringItems(Map.of(995, 10000))
                .expectedItems(Map.of(1333, 1))
                .verifySecondScreen(true)
                .build();

        assertFalse(config.isPassiveTrade());
        assertTrue(config.hasExpectedItems());
        assertTrue(config.shouldVerifyTrade());
    }

    @Test
    public void testTradeConfig_AcceptDelays() {
        TradeConfig config = TradeConfig.builder()
                .minAcceptDelayMs(2000)
                .maxAcceptDelayMs(5000)
                .build();

        assertEquals(2000, config.getMinAcceptDelayMs());
        assertEquals(5000, config.getMaxAcceptDelayMs());
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_LoggedInNonIronman_True() {
        when(taskContext.isLoggedIn()).thenReturn(true);
        when(ironmanState.isIronman()).thenReturn(false);

        TradeTask task = new TradeTask(TradeConfig.forRandomTrade());

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_NotLoggedIn_False() {
        when(taskContext.isLoggedIn()).thenReturn(false);

        TradeTask task = new TradeTask(TradeConfig.forRandomTrade());

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_Ironman_False() {
        when(ironmanState.isIronman()).thenReturn(true);

        TradeTask task = new TradeTask(TradeConfig.forRandomTrade());

        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Factory Method Tests
    // ========================================================================

    @Test
    public void testForRandomTrade() {
        TradeTask task = TradeTask.forRandomTrade();

        assertNotNull(task);
        assertTrue(task.getConfig().isSendResponse());
    }

    // ========================================================================
    // Wait First Screen Phase Tests
    // ========================================================================

    @Test
    public void testWaitFirstScreen_NotVisible_Fails() {
        when(client.getWidget(TRADE_MAIN_GROUP, 0)).thenReturn(null);

        TradeTask task = new TradeTask(TradeConfig.forRandomTrade());
        
        // Execute multiple times to trigger timeout
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("not opened"));
    }

    @Test
    public void testWaitFirstScreen_Visible_AdvancesToMonitor() {
        when(tradeMainWidget.isHidden()).thenReturn(false);
        when(client.getWidget(TRADE_MAIN_GROUP, 0)).thenReturn(tradeMainWidget);
        when(client.getWidget(TRADE_MAIN_GROUP, 28)).thenReturn(theirOfferWidget);
        when(theirOfferWidget.getDynamicChildren()).thenReturn(new Widget[0]);

        TradeTask task = new TradeTask(TradeConfig.forRandomTrade());
        task.execute(taskContext);
        task.execute(taskContext);

        // Should be monitoring the trade
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Trade Value Tracking Tests
    // ========================================================================

    @Test
    public void testTradeValueTracking() {
        when(tradeMainWidget.isHidden()).thenReturn(false);
        when(client.getWidget(TRADE_MAIN_GROUP, 0)).thenReturn(tradeMainWidget);
        when(client.getWidget(TRADE_MAIN_GROUP, 28)).thenReturn(theirOfferWidget);
        
        // Setup item in their offer
        Widget itemWidget = mock(Widget.class);
        when(itemWidget.getItemId()).thenReturn(1333);
        when(itemWidget.getItemQuantity()).thenReturn(1);
        when(theirOfferWidget.getDynamicChildren()).thenReturn(new Widget[]{itemWidget});
        
        ItemComposition itemComp = mock(ItemComposition.class);
        when(itemComp.getHaPrice()).thenReturn(15000);
        when(client.getItemDefinition(1333)).thenReturn(itemComp);

        TradeTask task = new TradeTask(TradeConfig.forRandomTrade());
        task.execute(taskContext);
        task.execute(taskContext);

        assertTrue(task.getTheirOfferValue() > 0);
    }

    // ========================================================================
    // Trade Cancelled Tests
    // ========================================================================

    @Test
    public void testTradeCancelled_ScreenCloses_Fails() {
        // First visible, then closes
        when(tradeMainWidget.isHidden())
                .thenReturn(false)
                .thenReturn(true);
        when(client.getWidget(TRADE_MAIN_GROUP, 0))
                .thenReturn(tradeMainWidget)
                .thenReturn(null);

        TradeTask task = new TradeTask(TradeConfig.forRandomTrade());
        task.execute(taskContext);
        task.execute(taskContext);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("cancelled"));
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_Default() {
        TradeTask task = new TradeTask(TradeConfig.forRandomTrade());

        String desc = task.getDescription();
        assertTrue(desc.contains("TradeTask"));
        assertTrue(desc.contains("phase"));
    }

    @Test
    public void testGetDescription_Custom() {
        TradeTask task = new TradeTask(TradeConfig.forRandomTrade())
                .withDescription("Accept gift from friend");

        assertEquals("Accept gift from friend", task.getDescription());
    }

    // ========================================================================
    // Real Game Scenario Tests
    // ========================================================================

    @Test
    public void testScenario_AcceptRandomGift() {
        // Someone trades you a gift
        TradeConfig config = TradeConfig.forRandomTrade();
        TradeTask task = new TradeTask(config);

        assertTrue(task.canExecute(taskContext));
        assertTrue(config.isPassiveTrade());
        assertTrue(config.isSendResponse());
    }

    @Test
    public void testScenario_ActiveTradeWithScamProtection() {
        TradeConfig config = TradeConfig.builder()
                .offeringItems(Map.of(995, 100000)) // 100k coins
                .expectedItems(Map.of(1333, 1)) // Rune scimitar
                .verifySecondScreen(true)
                .sendResponse(false)
                .build();
        TradeTask task = new TradeTask(config);

        assertFalse(config.isPassiveTrade());
        assertTrue(config.shouldVerifyTrade());
        assertTrue(config.hasExpectedItems());
    }

    @Test
    public void testScenario_IronmanCannotTrade() {
        when(ironmanState.isIronman()).thenReturn(true);

        TradeConfig config = TradeConfig.forRandomTrade();
        TradeTask task = new TradeTask(config);

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testScenario_TradeWithCustomResponse() {
        TradeConfig config = TradeConfig.builder()
                .sendResponse(true)
                .responseMessage("Thanks for the drop!")
                .build();
        TradeTask task = new TradeTask(config);

        assertEquals("Thanks for the drop!", config.getResponseMessage());
    }

    // ========================================================================
    // Scam Protection Tests
    // ========================================================================

    @Test
    public void testScamProtection_EnabledWhenOffering() {
        TradeConfig config = TradeConfig.builder()
                .offeringItems(Map.of(995, 1000))
                .verifySecondScreen(true)
                .build();

        assertTrue(config.shouldVerifyTrade());
    }

    @Test
    public void testScamProtection_DisabledWhenPassive() {
        TradeConfig config = TradeConfig.builder()
                .verifySecondScreen(true)
                .build();

        // Even with verifySecondScreen true, passive trades don't need verification
        assertFalse(config.shouldVerifyTrade());
    }

    @Test
    public void testScamProtection_ExplicitlyDisabled() {
        TradeConfig config = TradeConfig.builder()
                .offeringItems(Map.of(995, 1000))
                .verifySecondScreen(false)
                .build();

        assertFalse(config.shouldVerifyTrade());
    }

    // ========================================================================
    // Timeout Tests
    // ========================================================================

    @Test
    public void testTimeout_MaxWaitTicksExceeded() {
        when(tradeMainWidget.isHidden()).thenReturn(false);
        when(client.getWidget(TRADE_MAIN_GROUP, 0)).thenReturn(tradeMainWidget);
        when(client.getWidget(TRADE_MAIN_GROUP, 28)).thenReturn(theirOfferWidget);
        when(client.getWidget(TRADE_MAIN_GROUP, 30)).thenReturn(statusWidget);
        when(statusWidget.getText()).thenReturn("Waiting...");
        when(theirOfferWidget.getDynamicChildren()).thenReturn(new Widget[0]);
        when(client.getWidget(TRADE_MAIN_GROUP, 13)).thenReturn(acceptButton);

        TradeConfig config = TradeConfig.builder()
                .maxWaitTicks(5)
                .build();
        TradeTask task = new TradeTask(config);

        // Execute beyond max wait
        for (int i = 0; i < 20; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.FAILED, task.getState());
    }
}

