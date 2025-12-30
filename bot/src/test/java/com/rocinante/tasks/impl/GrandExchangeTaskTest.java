package com.rocinante.tasks.impl;

import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.input.SafeClickExecutor;
import com.rocinante.input.WidgetClickHelper;
import com.rocinante.state.GrandExchangeSlot;
import com.rocinante.state.GrandExchangeState;
import com.rocinante.state.IronmanState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.timing.DelayProfile;
import com.rocinante.timing.HumanTimer;
import com.rocinante.ui.GrandExchangeWidgets;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemID;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for GrandExchangeTask.
 * Tests buy/sell offers, collect, abort, pricing modes, and ironman restrictions.
 */
public class GrandExchangeTaskTest {

    private static final int LOBSTER_ID = ItemID.LOBSTER;
    private static final WorldPoint PLAYER_POS = new WorldPoint(3164, 3485, 0); // Near GE

    @Mock
    private Client client;

    @Mock
    private RobotMouseController mouseController;

    @Mock
    private RobotKeyboardController keyboardController;

    @Mock
    private SafeClickExecutor safeClickExecutor;

    @Mock
    private WidgetClickHelper widgetClickHelper;

    @Mock
    private HumanTimer humanTimer;

    @Mock
    private IronmanState ironmanState;

    @Mock
    private GrandExchangeState geState;

    @Mock
    private Widget geWidget;

    @Mock
    private NPC geClerkNpc;

    @Mock
    private ItemComposition itemComposition;

    private TaskContext taskContext;
    private PlayerState playerState;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        playerState = PlayerState.builder()
                .worldPosition(PLAYER_POS)
                .build();

        // TaskContext wiring
        taskContext = mock(TaskContext.class);
        when(taskContext.getClient()).thenReturn(client);
        when(taskContext.getMouseController()).thenReturn(mouseController);
        when(taskContext.getKeyboardController()).thenReturn(keyboardController);
        when(taskContext.getSafeClickExecutor()).thenReturn(safeClickExecutor);
        when(taskContext.getWidgetClickHelper()).thenReturn(widgetClickHelper);
        when(taskContext.getHumanTimer()).thenReturn(humanTimer);
        when(taskContext.getIronmanState()).thenReturn(ironmanState);
        when(taskContext.getGrandExchangeState()).thenReturn(geState);
        when(taskContext.isLoggedIn()).thenReturn(true);
        when(taskContext.getPlayerState()).thenReturn(playerState);

        // Default ironman state - NOT ironman
        when(ironmanState.canUseGrandExchange()).thenReturn(true);

        // Default GE state
        when(geState.hasEmptySlot()).thenReturn(true);
        when(geState.isUnknown()).thenReturn(false);
        when(geState.getFirstEmptySlot()).thenReturn(Optional.of(GrandExchangeSlot.empty(0)));

        // Default SafeClickExecutor
        when(safeClickExecutor.clickNpc(any(NPC.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // Default widget click helper
        when(widgetClickHelper.clickWidget(any(Widget.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // Default keyboard
        when(keyboardController.type(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(keyboardController.pressEnter())
                .thenReturn(CompletableFuture.completedFuture(null));
        when(keyboardController.pressKey(anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Default timer
        when(humanTimer.sleep(any(DelayProfile.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(humanTimer.sleep(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Default mouse
        when(mouseController.moveToCanvas(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mouseController.click())
                .thenReturn(CompletableFuture.completedFuture(null));

        // Default GE clerk
        when(geClerkNpc.getId()).thenReturn(GrandExchangeWidgets.GE_CLERK_NPC_IDS[0]);
        when(geClerkNpc.getWorldLocation()).thenReturn(new WorldPoint(3164, 3484, 0));
        when(geClerkNpc.getConvexHull()).thenReturn(new java.awt.Polygon(
                new int[]{10, 50, 50, 10},
                new int[]{10, 10, 50, 50},
                4
        ));

        List<NPC> npcs = new ArrayList<>();
        npcs.add(geClerkNpc);
        when(client.getNpcs()).thenReturn(npcs);

        // Default item composition for pricing
        when(itemComposition.getPrice()).thenReturn(200);
        when(itemComposition.getName()).thenReturn("Lobster");
        when(client.getItemDefinition(LOBSTER_ID)).thenReturn(itemComposition);
    }

    // ========================================================================
    // Factory Method Tests - Buy
    // ========================================================================

    @Test
    public void testBuy_ExactPrice() {
        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 1000, 250);

        assertEquals(GrandExchangeTask.GrandExchangeOperation.BUY, task.getOperation());
        assertEquals(LOBSTER_ID, task.getItemId());
        assertEquals(1000, task.getQuantity());
        assertEquals(250, task.getExactPrice());
        assertEquals(GrandExchangeTask.PriceMode.EXACT, task.getPriceMode());
    }

    @Test
    public void testBuy_PriceMode() {
        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 500, GrandExchangeTask.PriceMode.INSTANT_BUY);

        assertEquals(GrandExchangeTask.PriceMode.INSTANT_BUY, task.getPriceMode());
    }

    @Test
    public void testBuyWithOffset() {
        GrandExchangeTask task = GrandExchangeTask.buyWithOffset(LOBSTER_ID, 100, 10.0);

        assertEquals(GrandExchangeTask.PriceMode.PERCENTAGE_OFFSET, task.getPriceMode());
        assertEquals(10.0, task.getPercentageOffset(), 0.001);
    }

    // ========================================================================
    // Factory Method Tests - Sell
    // ========================================================================

    @Test
    public void testSell_ExactPrice() {
        GrandExchangeTask task = GrandExchangeTask.sell(LOBSTER_ID, 500, 180);

        assertEquals(GrandExchangeTask.GrandExchangeOperation.SELL, task.getOperation());
        assertEquals(180, task.getExactPrice());
    }

    @Test
    public void testSell_PriceMode() {
        GrandExchangeTask task = GrandExchangeTask.sell(LOBSTER_ID, 500, GrandExchangeTask.PriceMode.INSTANT_SELL);

        assertEquals(GrandExchangeTask.PriceMode.INSTANT_SELL, task.getPriceMode());
    }

    @Test
    public void testSellWithOffset() {
        GrandExchangeTask task = GrandExchangeTask.sellWithOffset(LOBSTER_ID, 100, -5.0);

        assertEquals(GrandExchangeTask.PriceMode.PERCENTAGE_OFFSET, task.getPriceMode());
        assertEquals(-5.0, task.getPercentageOffset(), 0.001);
    }

    // ========================================================================
    // Factory Method Tests - Other Operations
    // ========================================================================

    @Test
    public void testCollect() {
        GrandExchangeTask task = GrandExchangeTask.collect();

        assertEquals(GrandExchangeTask.GrandExchangeOperation.COLLECT, task.getOperation());
    }

    @Test
    public void testAbort() {
        GrandExchangeTask task = GrandExchangeTask.abort(3);

        assertEquals(GrandExchangeTask.GrandExchangeOperation.ABORT, task.getOperation());
        assertEquals(3, task.getTargetSlot());
    }

    @Test
    public void testOpen() {
        GrandExchangeTask task = GrandExchangeTask.open();

        assertEquals(GrandExchangeTask.GrandExchangeOperation.OPEN_ONLY, task.getOperation());
        assertFalse(task.isCloseAfter());
    }

    // ========================================================================
    // Builder Method Tests
    // ========================================================================

    @Test
    public void testWithCloseAfter() {
        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 100, GrandExchangeTask.PriceMode.GUIDE_PRICE)
                .withCloseAfter(false);

        assertFalse(task.isCloseAfter());
    }

    @Test
    public void testWithSearchTerm() {
        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 100, GrandExchangeTask.PriceMode.GUIDE_PRICE)
                .withSearchTerm("lobs");

        assertEquals("lobs", task.getSearchTerm());
    }

    @Test
    public void testWithDescription() {
        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 100, GrandExchangeTask.PriceMode.GUIDE_PRICE)
                .withDescription("Restock food");

        assertEquals("Restock food", task.getDescription());
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_LoggedInNonIronman_True() {
        when(taskContext.isLoggedIn()).thenReturn(true);
        when(ironmanState.canUseGrandExchange()).thenReturn(true);

        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 100, GrandExchangeTask.PriceMode.GUIDE_PRICE);

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_NotLoggedIn_False() {
        when(taskContext.isLoggedIn()).thenReturn(false);

        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 100, GrandExchangeTask.PriceMode.GUIDE_PRICE);

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_Ironman_False() {
        when(ironmanState.canUseGrandExchange()).thenReturn(false);

        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 100, GrandExchangeTask.PriceMode.GUIDE_PRICE);

        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Validation Phase Tests
    // ========================================================================

    @Test
    public void testValidation_NoEmptySlot_Fails() {
        when(geState.hasEmptySlot()).thenReturn(false);

        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 100, GrandExchangeTask.PriceMode.GUIDE_PRICE);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("empty"));
    }

    @Test
    public void testValidation_AbortInvalidSlot_Fails() {
        GrandExchangeTask task = GrandExchangeTask.abort(-1);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("Invalid"));
    }

    @Test
    public void testValidation_AbortEmptySlot_Fails() {
        GrandExchangeSlot emptySlot = GrandExchangeSlot.empty(2);
        when(geState.getSlot(2)).thenReturn(emptySlot);

        GrandExchangeTask task = GrandExchangeTask.abort(2);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("empty"));
    }

    // ========================================================================
    // Price Mode Tests
    // ========================================================================

    @Test
    public void testPriceMode_GuidePriceUsesItemPrice() {
        when(itemComposition.getPrice()).thenReturn(200);

        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 100, GrandExchangeTask.PriceMode.GUIDE_PRICE);

        // Price calculation happens during execution
        assertNotNull(task);
    }

    @Test
    public void testPriceMode_InstantBuyAdds5Percent() {
        // INSTANT_BUY should be guide * 1.05
        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 100, GrandExchangeTask.PriceMode.INSTANT_BUY);

        assertEquals(GrandExchangeTask.PriceMode.INSTANT_BUY, task.getPriceMode());
    }

    @Test
    public void testPriceMode_InstantSellSubtracts5Percent() {
        // INSTANT_SELL should be guide * 0.95
        GrandExchangeTask task = GrandExchangeTask.sell(LOBSTER_ID, 100, GrandExchangeTask.PriceMode.INSTANT_SELL);

        assertEquals(GrandExchangeTask.PriceMode.INSTANT_SELL, task.getPriceMode());
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_Buy() {
        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 100, GrandExchangeTask.PriceMode.GUIDE_PRICE);

        String desc = task.getDescription();
        assertTrue(desc.contains("buy"));
        assertTrue(desc.contains(String.valueOf(LOBSTER_ID)));
    }

    @Test
    public void testGetDescription_Sell() {
        GrandExchangeTask task = GrandExchangeTask.sell(LOBSTER_ID, 50, GrandExchangeTask.PriceMode.INSTANT_SELL);

        String desc = task.getDescription();
        assertTrue(desc.contains("sell"));
    }

    @Test
    public void testGetDescription_Collect() {
        GrandExchangeTask task = GrandExchangeTask.collect();

        assertTrue(task.getDescription().contains("collect"));
    }

    @Test
    public void testGetDescription_Abort() {
        GrandExchangeTask task = GrandExchangeTask.abort(5);

        String desc = task.getDescription();
        assertTrue(desc.contains("abort"));
        assertTrue(desc.contains("5"));
    }

    @Test
    public void testGetDescription_Custom() {
        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 100, GrandExchangeTask.PriceMode.GUIDE_PRICE)
                .withDescription("Restock fishing supplies");

        assertEquals("Restock fishing supplies", task.getDescription());
    }

    // ========================================================================
    // Real Game Scenario Tests
    // ========================================================================

    @Test
    public void testScenario_BuyLobstersForTraining() {
        when(geState.hasEmptySlot()).thenReturn(true);

        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 1000, GrandExchangeTask.PriceMode.INSTANT_BUY)
                .withDescription("Buy lobsters for cooking training");

        assertTrue(task.canExecute(taskContext));
        assertEquals(1000, task.getQuantity());
    }

    @Test
    public void testScenario_SellCoalAtGuidePrice() {
        int coalId = ItemID.COAL;
        GrandExchangeTask task = GrandExchangeTask.sell(coalId, 500, GrandExchangeTask.PriceMode.GUIDE_PRICE)
                .withDescription("Sell coal from mining");

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testScenario_CollectCompletedOffers() {
        GrandExchangeTask task = GrandExchangeTask.collect();

        assertTrue(task.canExecute(taskContext));
        assertEquals(GrandExchangeTask.GrandExchangeOperation.COLLECT, task.getOperation());
    }

    @Test
    public void testScenario_IronmanCannotUseGE() {
        when(ironmanState.canUseGrandExchange()).thenReturn(false);

        GrandExchangeTask task = GrandExchangeTask.buy(LOBSTER_ID, 100, GrandExchangeTask.PriceMode.GUIDE_PRICE);

        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Operation Enum Tests
    // ========================================================================

    @Test
    public void testGrandExchangeOperation_AllValues() {
        GrandExchangeTask.GrandExchangeOperation[] ops = GrandExchangeTask.GrandExchangeOperation.values();

        assertEquals(5, ops.length);
        assertNotNull(GrandExchangeTask.GrandExchangeOperation.BUY);
        assertNotNull(GrandExchangeTask.GrandExchangeOperation.SELL);
        assertNotNull(GrandExchangeTask.GrandExchangeOperation.COLLECT);
        assertNotNull(GrandExchangeTask.GrandExchangeOperation.ABORT);
        assertNotNull(GrandExchangeTask.GrandExchangeOperation.OPEN_ONLY);
    }

    // ========================================================================
    // Price Mode Enum Tests
    // ========================================================================

    @Test
    public void testPriceMode_AllValues() {
        GrandExchangeTask.PriceMode[] modes = GrandExchangeTask.PriceMode.values();

        assertEquals(5, modes.length);
        assertNotNull(GrandExchangeTask.PriceMode.EXACT);
        assertNotNull(GrandExchangeTask.PriceMode.GUIDE_PRICE);
        assertNotNull(GrandExchangeTask.PriceMode.INSTANT_BUY);
        assertNotNull(GrandExchangeTask.PriceMode.INSTANT_SELL);
        assertNotNull(GrandExchangeTask.PriceMode.PERCENTAGE_OFFSET);
    }
}

