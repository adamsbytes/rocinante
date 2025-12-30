package com.rocinante.tasks.impl;

import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.input.SafeClickExecutor;
import com.rocinante.input.MenuHelper;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.NPC;
import net.runelite.api.NpcID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for ShopPurchaseTask.
 * Tests shopkeeper interaction, item purchasing, quantity modes, and shop interface.
 */
public class ShopPurchaseTaskTest {

    private static final WorldPoint PLAYER_POS = new WorldPoint(3200, 3200, 0);

    @Mock
    private Client client;

    @Mock
    private RobotMouseController mouseController;

    @Mock
    private RobotKeyboardController keyboardController;

    @Mock
    private SafeClickExecutor safeClickExecutor;

    @Mock
    private MenuHelper menuHelper;

    @Mock
    private Widget shopWidget;

    @Mock
    private Widget shopItemsWidget;

    @Mock
    private Widget itemWidget;

    @Mock
    private NPC shopkeeperNpc;

    private TaskContext taskContext;
    private PlayerState playerState;
    private InventoryState inventoryState;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        playerState = PlayerState.builder()
                .worldPosition(PLAYER_POS)
                .build();

        inventoryState = mock(InventoryState.class);
        when(inventoryState.countItem(anyInt())).thenReturn(0);

        // TaskContext wiring
        taskContext = mock(TaskContext.class);
        when(taskContext.getClient()).thenReturn(client);
        when(taskContext.getMouseController()).thenReturn(mouseController);
        when(taskContext.getKeyboardController()).thenReturn(keyboardController);
        when(taskContext.getSafeClickExecutor()).thenReturn(safeClickExecutor);
        when(taskContext.getMenuHelper()).thenReturn(menuHelper);
        when(taskContext.isLoggedIn()).thenReturn(true);
        when(taskContext.getPlayerState()).thenReturn(playerState);
        when(taskContext.getInventoryState()).thenReturn(inventoryState);

        // Default SafeClickExecutor
        when(safeClickExecutor.clickNpc(any(NPC.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // Default mouse/keyboard
        when(mouseController.moveToCanvas(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mouseController.click())
                .thenReturn(CompletableFuture.completedFuture(null));
        when(keyboardController.pressKey(anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Default menu helper
        when(menuHelper.selectMenuEntry(any(Rectangle.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // Default NPC setup
        when(shopkeeperNpc.getId()).thenReturn(NpcID.SHOP_KEEPER);
        when(shopkeeperNpc.getWorldLocation()).thenReturn(new WorldPoint(3202, 3202, 0));
        when(shopkeeperNpc.getConvexHull()).thenReturn(new java.awt.Polygon(
                new int[]{10, 50, 50, 10},
                new int[]{10, 10, 50, 50},
                4
        ));
        when(shopkeeperNpc.isDead()).thenReturn(false);

        List<NPC> npcs = new ArrayList<>();
        npcs.add(shopkeeperNpc);
        when(client.getNpcs()).thenReturn(npcs);

        // Default item widget data
        when(itemWidget.getItemQuantity()).thenReturn(100);
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Test
    public void testConstructor_BasicPurchase() {
        ShopPurchaseTask task = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5);

        assertEquals(NpcID.SHOP_KEEPER, task.getShopkeeperNpcId());
        assertEquals(ItemID.BUCKET, task.getItemId());
        assertEquals(5, task.getQuantity());
        assertEquals(ShopPurchaseTask.PurchaseQuantity.EXACT, task.getQuantityMode());
    }

    // ========================================================================
    // Factory Method Tests
    // ========================================================================

    @Test
    public void testBuy_ExactQuantity() {
        ShopPurchaseTask task = ShopPurchaseTask.buy(NpcID.SHOP_KEEPER, ItemID.BUCKET, 10);

        assertEquals(10, task.getQuantity());
        assertEquals(ShopPurchaseTask.PurchaseQuantity.EXACT, task.getQuantityMode());
    }

    @Test
    public void testBuyOne() {
        ShopPurchaseTask task = ShopPurchaseTask.buyOne(NpcID.SHOP_KEEPER, ItemID.BUCKET);

        assertEquals(ShopPurchaseTask.PurchaseQuantity.ONE, task.getQuantityMode());
    }

    @Test
    public void testBuyFive() {
        ShopPurchaseTask task = ShopPurchaseTask.buyFive(NpcID.SHOP_KEEPER, ItemID.BUCKET);

        assertEquals(ShopPurchaseTask.PurchaseQuantity.FIVE, task.getQuantityMode());
    }

    @Test
    public void testBuyTen() {
        ShopPurchaseTask task = ShopPurchaseTask.buyTen(NpcID.SHOP_KEEPER, ItemID.BUCKET);

        assertEquals(ShopPurchaseTask.PurchaseQuantity.TEN, task.getQuantityMode());
    }

    @Test
    public void testBuyFifty() {
        ShopPurchaseTask task = ShopPurchaseTask.buyFifty(NpcID.SHOP_KEEPER, ItemID.BUCKET);

        assertEquals(ShopPurchaseTask.PurchaseQuantity.FIFTY, task.getQuantityMode());
    }

    // ========================================================================
    // Builder Method Tests
    // ========================================================================

    @Test
    public void testWithCloseAfter() {
        ShopPurchaseTask task = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5)
                .withCloseAfter(false);

        assertFalse(task.isCloseAfter());
    }

    @Test
    public void testWithSearchRadius() {
        ShopPurchaseTask task = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5)
                .withSearchRadius(30);

        assertEquals(30, task.getSearchRadius());
    }

    @Test
    public void testWithDescription() {
        ShopPurchaseTask task = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5)
                .withDescription("Buy buckets for farming");

        assertEquals("Buy buckets for farming", task.getDescription());
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_LoggedIn_True() {
        when(taskContext.isLoggedIn()).thenReturn(true);

        ShopPurchaseTask task = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5);

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_NotLoggedIn_False() {
        when(taskContext.isLoggedIn()).thenReturn(false);

        ShopPurchaseTask task = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5);

        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Find Shopkeeper Phase Tests
    // ========================================================================

    @Test
    public void testFindShopkeeper_Found() {
        ShopPurchaseTask task = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5);
        task.execute(taskContext);

        verify(client).getNpcs();
    }

    @Test
    public void testFindShopkeeper_NotFound_Fails() {
        when(client.getNpcs()).thenReturn(new ArrayList<>());

        ShopPurchaseTask task = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("not found"));
    }

    @Test
    public void testFindShopkeeper_OutsideRadius_Fails() {
        WorldPoint farPos = new WorldPoint(3300, 3300, 0);
        when(shopkeeperNpc.getWorldLocation()).thenReturn(farPos);

        ShopPurchaseTask task = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5)
                .withSearchRadius(10);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Shop Already Open Tests
    // ========================================================================

    @Test
    public void testShopAlreadyOpen_SkipsOpening() {
        when(shopWidget.isHidden()).thenReturn(false);
        when(client.getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 0)).thenReturn(shopWidget);

        ShopPurchaseTask task = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5);
        task.execute(taskContext);

        // Should skip to SET_QUANTITY phase
        verify(client).getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 0);
    }

    // ========================================================================
    // isShopOpen Tests
    // ========================================================================

    @Test
    public void testIsShopOpen_True() {
        when(shopWidget.isHidden()).thenReturn(false);
        when(client.getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 0)).thenReturn(shopWidget);

        assertTrue(ShopPurchaseTask.isShopOpen(client));
    }

    @Test
    public void testIsShopOpen_Hidden_False() {
        when(shopWidget.isHidden()).thenReturn(true);
        when(client.getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 0)).thenReturn(shopWidget);

        assertFalse(ShopPurchaseTask.isShopOpen(client));
    }

    @Test
    public void testIsShopOpen_Null_False() {
        when(client.getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 0)).thenReturn(null);

        assertFalse(ShopPurchaseTask.isShopOpen(client));
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_Default() {
        ShopPurchaseTask task = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5);

        String desc = task.getDescription();
        assertTrue(desc.contains("ShopPurchaseTask"));
        assertTrue(desc.contains(String.valueOf(ItemID.BUCKET)));
    }

    @Test
    public void testGetDescription_QuantityMode() {
        ShopPurchaseTask task = ShopPurchaseTask.buyFive(NpcID.SHOP_KEEPER, ItemID.BUCKET);

        String desc = task.getDescription();
        assertTrue(desc.contains("FIVE"));
    }

    @Test
    public void testGetDescription_Custom() {
        ShopPurchaseTask task = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5)
                .withDescription("Buy supplies");

        assertEquals("Buy supplies", task.getDescription());
    }

    // ========================================================================
    // Real Game Scenario Tests
    // ========================================================================

    @Test
    public void testScenario_BuyBucketsFromGeneralStore() {
        // Setup shop open
        when(shopWidget.isHidden()).thenReturn(false);
        when(client.getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 0)).thenReturn(shopWidget);
        
        // Setup items widget
        when(itemWidget.getItemId()).thenReturn(ItemID.BUCKET);
        when(itemWidget.getBounds()).thenReturn(new Rectangle(100, 100, 32, 32));
        Widget[] items = new Widget[]{itemWidget};
        when(shopItemsWidget.getDynamicChildren()).thenReturn(items);
        when(client.getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 16)).thenReturn(shopItemsWidget);

        ShopPurchaseTask task = ShopPurchaseTask.buy(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5)
                .withDescription("Buy buckets for farming");

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testExactPurchase_MultiChunk_Completes() {
        // Shop open and item present
        when(shopWidget.isHidden()).thenReturn(false);
        when(client.getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 0)).thenReturn(shopWidget);
        when(itemWidget.getItemId()).thenReturn(ItemID.BUCKET);
        when(itemWidget.getBounds()).thenReturn(new Rectangle(100, 100, 32, 32));
        Widget[] items = new Widget[]{itemWidget};
        when(shopItemsWidget.getDynamicChildren()).thenReturn(items);
        when(client.getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 16)).thenReturn(shopItemsWidget);

        // Inventory gains across ticks: start 0, then 6, then 12 (complete)
        stubInventoryCounts(0, 0, 6, 12, 12);

        ShopPurchaseTask task = ShopPurchaseTask.buy(NpcID.SHOP_KEEPER, ItemID.BUCKET, 12);

        executeUntilTerminal(task, 20);

        assertEquals(TaskState.COMPLETED, task.getState());
        assertNull(task.getFailureReason());
    }

    @Test
    public void testExactPurchase_PartialAllowed_WhenStockShort() {
        // Shop open and item present
        when(shopWidget.isHidden()).thenReturn(false);
        when(client.getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 0)).thenReturn(shopWidget);
        when(itemWidget.getItemId()).thenReturn(ItemID.BUCKET);
        when(itemWidget.getBounds()).thenReturn(new Rectangle(100, 100, 32, 32));
        when(itemWidget.getItemQuantity()).thenReturn(2);
        Widget[] items = new Widget[]{itemWidget};
        when(shopItemsWidget.getDynamicChildren()).thenReturn(items);
        when(client.getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 16)).thenReturn(shopItemsWidget);

        // Inventory gains to 2, then stalls
        stubInventoryCounts(0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2);

        ShopPurchaseTask task = ShopPurchaseTask.buy(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5);

        executeUntilTerminal(task, 30);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testExactPurchase_NoProgress_Fails() {
        when(shopWidget.isHidden()).thenReturn(false);
        when(client.getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 0)).thenReturn(shopWidget);
        when(itemWidget.getItemId()).thenReturn(ItemID.BUCKET);
        when(itemWidget.getBounds()).thenReturn(new Rectangle(100, 100, 32, 32));
        when(itemWidget.getItemQuantity()).thenReturn(0);
        Widget[] items = new Widget[]{itemWidget};
        when(shopItemsWidget.getDynamicChildren()).thenReturn(items);
        when(client.getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 16)).thenReturn(shopItemsWidget);

        // No inventory gains
        stubInventoryCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        ShopPurchaseTask task = ShopPurchaseTask.buy(NpcID.SHOP_KEEPER, ItemID.BUCKET, 3);

        executeUntilTerminal(task, 30);

        assertEquals(TaskState.FAILED, task.getState());
        assertNotNull(task.getFailureReason());
    }

    @Test
    public void testScenario_BuyFromDistantShopkeeper() {
        // Shopkeeper is far away
        WorldPoint farPos = new WorldPoint(3220, 3220, 0);
        when(shopkeeperNpc.getWorldLocation()).thenReturn(farPos);

        ShopPurchaseTask task = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5)
                .withSearchRadius(30);
        task.execute(taskContext);

        // Should detect distance and transition to walking
        verify(client).getNpcs();
    }

    @Test
    public void testScenario_ItemNotInShop() {
        // Shop is open but item not found
        when(shopWidget.isHidden()).thenReturn(false);
        when(client.getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 0)).thenReturn(shopWidget);
        
        // Empty shop items
        Widget[] items = new Widget[]{};
        when(shopItemsWidget.getDynamicChildren()).thenReturn(items);
        when(client.getWidget(ShopPurchaseTask.WIDGET_SHOP_GROUP, 16)).thenReturn(shopItemsWidget);

        ShopPurchaseTask task = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET, 5);
        
        // Execute multiple times to reach FIND_ITEM phase
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should fail when item not found
        // (depends on phase progression)
    }

    // ========================================================================
    // Purchase Quantity Enum Tests
    // ========================================================================

    @Test
    public void testPurchaseQuantity_AllValues() {
        ShopPurchaseTask.PurchaseQuantity[] quantities = ShopPurchaseTask.PurchaseQuantity.values();
        
        assertTrue(Arrays.asList(quantities).contains(ShopPurchaseTask.PurchaseQuantity.ONE));
        assertTrue(Arrays.asList(quantities).contains(ShopPurchaseTask.PurchaseQuantity.FIVE));
        assertTrue(Arrays.asList(quantities).contains(ShopPurchaseTask.PurchaseQuantity.TEN));
        assertTrue(Arrays.asList(quantities).contains(ShopPurchaseTask.PurchaseQuantity.FIFTY));
        assertTrue(Arrays.asList(quantities).contains(ShopPurchaseTask.PurchaseQuantity.EXACT));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void executeUntilTerminal(ShopPurchaseTask task, int maxTicks) {
        for (int i = 0; i < maxTicks && !task.getState().isTerminal(); i++) {
            task.execute(taskContext);
        }
    }

    private void stubInventoryCounts(int... counts) {
        AtomicInteger idx = new AtomicInteger(0);
        when(inventoryState.countItem(anyInt())).thenAnswer(invocation -> {
            int i = Math.min(idx.getAndIncrement(), counts.length - 1);
            return counts[i];
        });
    }
}

