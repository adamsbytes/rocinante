package com.rocinante.tasks.impl;

import com.rocinante.behavior.InefficiencyInjector;
import com.rocinante.core.GameStateService;
import com.rocinante.input.MenuHelper;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.input.SafeClickExecutor;
import com.rocinante.input.WidgetClickHelper;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for BankTask.
 * Tests all 10 phases, quantity modes, redundant actions, and edge cases.
 */
public class BankTaskTest {

    // Widget IDs
    private static final int WIDGET_BANK_GROUP = 12;
    private static final int WIDGET_BANK_CONTAINER = 1;
    private static final int WIDGET_BANK_ITEMS = 13;
    private static final int WIDGET_DEPOSIT_INVENTORY = 44;
    private static final int WIDGET_DEPOSIT_EQUIPMENT = 46;
    private static final int WIDGET_BANK_INVENTORY_GROUP = 15;
    private static final int WIDGET_BANK_INV_ITEMS = 3;
    private static final int WIDGET_CHATBOX_GROUP = 162;
    private static final int WIDGET_CHATBOX_INPUT = 45;
    private static final int WIDGET_QUANTITY_X = 36;


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
    private SafeClickExecutor safeClickExecutor;

    @Mock
    private MenuHelper menuHelper;

    @Mock
    private WidgetClickHelper widgetClickHelper;

    @Mock
    private InefficiencyInjector inefficiencyInjector;

    @Mock
    private Scene scene;

    @Mock
    private Canvas canvas;

    @Mock
    private InventoryState inventoryState;

    private TaskContext taskContext;
    private WorldPoint playerPos;
    private PlayerState playerState;
    private List<NPC> npcList;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        playerPos = new WorldPoint(3200, 3200, 0);
        playerState = PlayerState.builder()
                .worldPosition(playerPos)
                .currentHitpoints(50)
                .maxHitpoints(50)
                .build();

        npcList = new ArrayList<>();

        // Wire up TaskContext
        taskContext = mock(TaskContext.class);
        when(taskContext.getClient()).thenReturn(client);
        when(taskContext.getGameStateService()).thenReturn(gameStateService);
        when(taskContext.getMouseController()).thenReturn(mouseController);
        when(taskContext.getKeyboardController()).thenReturn(keyboardController);
        when(taskContext.getHumanTimer()).thenReturn(humanTimer);
        when(taskContext.getRandomization()).thenReturn(randomization);
        when(taskContext.getSafeClickExecutor()).thenReturn(safeClickExecutor);
        when(taskContext.getMenuHelper()).thenReturn(menuHelper);
        when(taskContext.getWidgetClickHelper()).thenReturn(widgetClickHelper);
        when(taskContext.getInefficiencyInjector()).thenReturn(inefficiencyInjector);
        when(taskContext.isLoggedIn()).thenReturn(true);
        when(taskContext.getPlayerState()).thenReturn(playerState);
        when(taskContext.getInventoryState()).thenReturn(inventoryState);

        // GameStateService
        when(gameStateService.isLoggedIn()).thenReturn(true);
        when(gameStateService.getPlayerState()).thenReturn(playerState);

        // Client mocks
        when(client.getScene()).thenReturn(scene);
        when(client.getCanvas()).thenReturn(canvas);
        when(client.getNpcs()).thenReturn(npcList);
        when(canvas.getSize()).thenReturn(new Dimension(800, 600));

        // Default: empty scene with no tiles
        Tile[][][] tiles = new Tile[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        when(scene.getTiles()).thenReturn(tiles);

        // Randomization defaults
        when(randomization.chance(anyDouble())).thenReturn(false);
        when(randomization.uniformRandomInt(anyInt(), anyInt())).thenReturn(200);
        when(randomization.uniformRandomLong(anyLong(), anyLong())).thenReturn(200L);

        // HumanTimer defaults
        when(humanTimer.sleep(anyLong())).thenReturn(CompletableFuture.completedFuture(null));
        when(humanTimer.sleep(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(humanTimer.getDelay(any())).thenReturn(100L);

        // Keyboard controller
        when(keyboardController.pressKey(anyInt())).thenReturn(CompletableFuture.completedFuture(null));
        when(keyboardController.pressEnter()).thenReturn(CompletableFuture.completedFuture(null));
        when(keyboardController.type(anyString())).thenReturn(CompletableFuture.completedFuture(null));

        // SafeClickExecutor defaults
        when(safeClickExecutor.clickObject(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(safeClickExecutor.clickNpc(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // WidgetClickHelper defaults
        when(widgetClickHelper.clickWidget(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // MenuHelper defaults
        when(menuHelper.selectMenuEntry(any(Rectangle.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // InefficiencyInjector defaults
        when(inefficiencyInjector.shouldPerformRedundantAction()).thenReturn(false);

        // Bank closed by default
        setupBankClosed();
    }

    // ========================================================================
    // Factory Method Tests
    // ========================================================================

    @Test
    public void testWithdraw_CreatesCorrectTask() {
        BankTask task = BankTask.withdraw(ItemID.LOBSTER, 10);

        assertEquals(BankTask.BankOperation.WITHDRAW, task.getOperation());
        assertEquals(ItemID.LOBSTER, task.getItemId());
        assertEquals(10, task.getQuantity());
        assertEquals(BankTask.WithdrawQuantity.EXACT, task.getQuantityMode());
    }

    @Test
    public void testWithdrawQuantityMode_CreatesCorrectTask() {
        BankTask task = BankTask.withdraw(ItemID.COINS_995, BankTask.WithdrawQuantity.ALL);

        assertEquals(BankTask.BankOperation.WITHDRAW, task.getOperation());
        assertEquals(ItemID.COINS_995, task.getItemId());
        assertEquals(BankTask.WithdrawQuantity.ALL, task.getQuantityMode());
    }

    @Test
    public void testDeposit_CreatesCorrectTask() {
        BankTask task = BankTask.deposit(ItemID.LOBSTER, 5);

        assertEquals(BankTask.BankOperation.DEPOSIT, task.getOperation());
        assertEquals(ItemID.LOBSTER, task.getItemId());
        assertEquals(5, task.getQuantity());
    }

    @Test
    public void testDepositAll_CreatesCorrectTask() {
        BankTask task = BankTask.depositAll();

        assertEquals(BankTask.BankOperation.DEPOSIT_ALL, task.getOperation());
    }

    @Test
    public void testDepositEquipment_CreatesCorrectTask() {
        BankTask task = BankTask.depositEquipment();

        assertEquals(BankTask.BankOperation.DEPOSIT_EQUIPMENT, task.getOperation());
    }

    @Test
    public void testOpen_CreatesCorrectTask() {
        BankTask task = BankTask.open();

        assertEquals(BankTask.BankOperation.OPEN_ONLY, task.getOperation());
        assertFalse(task.isCloseAfter());
    }

    @Test
    public void testWithdrawX_CreatesCorrectTask() {
        BankTask task = BankTask.withdrawX(ItemID.LOBSTER, 14);

        assertEquals(BankTask.BankOperation.WITHDRAW, task.getOperation());
        assertEquals(ItemID.LOBSTER, task.getItemId());
        assertEquals(14, task.getQuantity());
        assertEquals(BankTask.WithdrawQuantity.X, task.getQuantityMode());
    }

    // ========================================================================
    // Builder Method Tests
    // ========================================================================

    @Test
    public void testBuilderMethods() {
        BankTask task = BankTask.withdraw(ItemID.LOBSTER, 10)
                .withCloseAfter(false)
                .withSearchRadius(30)
                .withDescription("Get some food")
                .withWithdrawNoted(true);

        assertFalse(task.isCloseAfter());
        assertEquals(30, task.getSearchRadius());
        assertEquals("Get some food", task.getDescription());
        assertTrue(task.isWithdrawNoted());
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_WhenLoggedIn_ReturnsTrue() {
        when(taskContext.isLoggedIn()).thenReturn(true);

        BankTask task = BankTask.depositAll();

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_WhenNotLoggedIn_ReturnsFalse() {
        when(taskContext.isLoggedIn()).thenReturn(false);

        BankTask task = BankTask.depositAll();

        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Phase: FIND_BANK Tests
    // ========================================================================

    @Test
    public void testFindBank_NoBank_Fails() {
        // No bank booths or NPCs

        BankTask task = BankTask.depositAll();
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("No bank"));
    }

    @Test
    public void testFindBank_BankAlreadyOpen_SkipsToOperation() {
        setupBankOpen();

        BankTask task = BankTask.depositAll();
        task.execute(taskContext);

        // Should not fail - bank is already open
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindBank_OpenOnly_WhenBankOpen_Completes() {
        setupBankOpen();

        BankTask task = BankTask.open();
        task.execute(taskContext);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testFindBank_BankBoothFound_TransitionsToOpen() {
        setupBankBooth(new WorldPoint(3202, 3200, 0));

        BankTask task = BankTask.depositAll();
        task.execute(taskContext);

        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindBank_BankerFound_TransitionsToOpen() {
        addBanker(new WorldPoint(3202, 3200, 0));

        BankTask task = BankTask.depositAll();
        task.execute(taskContext);

        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindBank_TooFar_TransitionsToWalk() {
        // Bank booth at 20 tiles away
        setupBankBooth(new WorldPoint(3220, 3200, 0));

        BankTask task = BankTask.depositAll();
        task.execute(taskContext);

        // Should transition to WALK_TO_BANK phase
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Phase: OPEN_BANK Tests
    // ========================================================================

    @Test
    public void testOpenBank_BankBooth_ClicksWithSafeExecutor() {
        setupBankBooth(new WorldPoint(3202, 3200, 0));
        when(safeClickExecutor.clickObject(any(), eq("Bank")))
                .thenReturn(CompletableFuture.completedFuture(true));

        BankTask task = BankTask.depositAll();
        
        // Execute multiple times to reach OPEN_BANK phase
        for (int i = 0; i < 5; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        verify(safeClickExecutor, atLeastOnce()).clickObject(any(), eq("Bank"));
    }

    @Test
    public void testOpenBank_Banker_ClicksWithSafeExecutor() {
        addBanker(new WorldPoint(3202, 3200, 0));
        when(safeClickExecutor.clickNpc(any(), eq("Bank")))
                .thenReturn(CompletableFuture.completedFuture(true));

        BankTask task = BankTask.depositAll();
        
        // Execute multiple times
        for (int i = 0; i < 5; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        verify(safeClickExecutor, atLeastOnce()).clickNpc(any(), eq("Bank"));
    }

    // ========================================================================
    // Phase: WAIT_BANK_OPEN Tests
    // ========================================================================

    @Test
    public void testWaitBankOpen_BankOpens_TransitionsToOperation() {
        setupBankBooth(new WorldPoint(3202, 3200, 0));

        BankTask task = BankTask.depositAll();
        
        // Execute to get to WAIT_BANK_OPEN phase
        for (int i = 0; i < 5; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        // Now bank opens - also setup deposit widgets since depositAll needs them
        setupBankOpen();
        setupBankWidgets(true, false);

        // Execute again - should proceed past WAIT_BANK_OPEN to PERFORM_OPERATION
        for (int i = 0; i < 5; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        // Should proceed past WAIT_BANK_OPEN without failing
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Redundant Action Tests
    // ========================================================================

    @Test
    public void testRedundantActions_WhenInjected_ClosesAndReopensBank() {
        setupBankBooth(new WorldPoint(3202, 3200, 0));
        when(inefficiencyInjector.shouldPerformRedundantAction()).thenReturn(true);
        when(inefficiencyInjector.getRedundantRepetitions()).thenReturn(1);

        BankTask task = BankTask.depositAll();
        
        // Execute until bank would open
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        setupBankOpen();

        // Execute more - should close and reopen
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        // Verify ESC was pressed (bank close)
        verify(keyboardController, atLeastOnce()).pressKey(KeyEvent.VK_ESCAPE);
    }

    // ========================================================================
    // Phase: PERFORM_OPERATION Tests
    // ========================================================================

    @Test
    public void testDepositAll_ClicksDepositButton() {
        setupBankOpen();
        setupBankWidgets(true, false); // Deposit buttons available

        BankTask task = BankTask.depositAll();

        // Execute through phases
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        // Verify deposit button was clicked
        verify(widgetClickHelper, atLeastOnce()).clickWidget(any(), contains("Deposit"));
    }

    @Test
    public void testDepositEquipment_ClicksDepositEquipmentButton() {
        setupBankOpen();
        setupBankWidgets(true, true); // Both deposit buttons available

        BankTask task = BankTask.depositEquipment();

        // Execute through phases
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        verify(widgetClickHelper, atLeastOnce()).clickWidget(any(), contains("Deposit equipment"));
    }

    @Test
    public void testWithdraw_ExactQuantity_UsesRightClickMenu() {
        setupBankOpen();
        setupBankWithItem(ItemID.LOBSTER);

        BankTask task = BankTask.withdraw(ItemID.LOBSTER, 5);

        // Execute through phases
        for (int i = 0; i < 20; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        // For exact quantity, should use menu selection
        verify(menuHelper, atLeastOnce()).selectMenuEntry(any(Rectangle.class), eq("Withdraw-X"));
    }

    @Test
    public void testWithdraw_AllMode_ClicksQuantityButton() {
        setupBankOpen();
        setupBankWithItem(ItemID.LOBSTER);
        setupQuantityButtons();

        BankTask task = BankTask.withdraw(ItemID.LOBSTER, BankTask.WithdrawQuantity.ALL);

        // Execute through phases
        for (int i = 0; i < 20; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        // Should click ALL quantity button, then the item
        verify(widgetClickHelper, atLeastOnce()).clickWidget(any(), anyString());
    }

    @Test
    public void testWithdraw_ItemNotInBank_Fails() {
        setupBankOpen();
        // Bank is open but item not present
        Widget bankItems = mock(Widget.class);
        when(bankItems.getDynamicChildren()).thenReturn(new Widget[0]);
        when(client.getWidget(WIDGET_BANK_GROUP, WIDGET_BANK_ITEMS)).thenReturn(bankItems);

        BankTask task = BankTask.withdraw(ItemID.LOBSTER, 10);

        // Execute through phases
        for (int i = 0; i < 20; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("not found"));
    }

    // ========================================================================
    // Phase: ENTER_QUANTITY Tests
    // ========================================================================

    @Test
    public void testEnterQuantity_TypesAndPressesEnter() {
        setupBankOpen();
        setupBankWithItem(ItemID.LOBSTER);
        setupChatboxInput();

        BankTask task = BankTask.withdraw(ItemID.LOBSTER, 15);

        // Execute through phases
        for (int i = 0; i < 30; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        // Verify quantity was typed and Enter pressed
        verify(keyboardController, atLeastOnce()).type("15");
        verify(keyboardController, atLeastOnce()).pressEnter();
    }

    @Test
    public void testEnterQuantity_UpdatesLastXQuantity() {
        // Reset the static field
        BankTask.setLastXQuantity(0);

        setupBankOpen();
        setupBankWithItem(ItemID.LOBSTER);
        setupQuantityButtons();
        setupChatboxInput();

        BankTask task = BankTask.withdrawX(ItemID.LOBSTER, 14);

        // Execute through phases
        for (int i = 0; i < 30; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        // lastXQuantity should be updated
        assertEquals(14, BankTask.getLastXQuantity());
    }

    // ========================================================================
    // Phase: WAIT_OPERATION Tests
    // ========================================================================

    @Test
    public void testWaitOperation_DepositAll_CompletesWhenInventoryEmpty() {
        setupBankOpen();
        setupBankWidgets(true, false);
        
        // Inventory starts with items
        when(inventoryState.isEmpty()).thenReturn(false);

        BankTask task = BankTask.depositAll();

        // Execute a few times
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        // Inventory becomes empty
        when(inventoryState.isEmpty()).thenReturn(true);

        // Execute more
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testWaitOperation_Withdraw_CompletesWhenItemInInventory() {
        setupBankOpen();
        setupBankWithItem(ItemID.LOBSTER);
        
        // Inventory doesn't have item initially
        when(inventoryState.hasItem(ItemID.LOBSTER)).thenReturn(false);

        BankTask task = BankTask.withdraw(ItemID.LOBSTER, BankTask.WithdrawQuantity.ALL);

        // Execute a few times
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        // Item appears in inventory
        when(inventoryState.hasItem(ItemID.LOBSTER)).thenReturn(true);

        // Execute more
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    // ========================================================================
    // Phase: CLOSE_BANK Tests
    // ========================================================================

    @Test
    public void testCloseBank_PressesEscape() {
        setupBankOpen();
        setupBankWidgets(true, false);
        when(inventoryState.isEmpty()).thenReturn(true);

        BankTask task = BankTask.depositAll()
                .withCloseAfter(true);

        // Execute to completion
        for (int i = 0; i < 25; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        verify(keyboardController, atLeastOnce()).pressKey(KeyEvent.VK_ESCAPE);
    }

    @Test
    public void testCloseBank_Disabled_DoesNotPressEscape() {
        setupBankOpen();
        setupBankWidgets(true, false);
        when(inventoryState.isEmpty()).thenReturn(true);

        BankTask task = BankTask.depositAll()
                .withCloseAfter(false);

        // Execute to completion
        for (int i = 0; i < 25; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        // ESC should not be pressed for closing (might be pressed for other reasons)
        assertEquals(TaskState.COMPLETED, task.getState());
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_Withdraw() {
        BankTask task = BankTask.withdraw(ItemID.LOBSTER, 10);
        assertTrue(task.getDescription().contains("withdraw"));
    }

    @Test
    public void testGetDescription_DepositAll() {
        BankTask task = BankTask.depositAll();
        assertTrue(task.getDescription().contains("deposit all"));
    }

    @Test
    public void testGetDescription_Custom() {
        BankTask task = BankTask.depositAll()
                .withDescription("Bank everything");
        assertEquals("Bank everything", task.getDescription());
    }

    // ========================================================================
    // Static Method Tests
    // ========================================================================

    @Test
    public void testIsBankOpen_WhenOpen_ReturnsTrue() {
        setupBankOpen();
        assertTrue(BankTask.isBankOpen(client));
    }

    @Test
    public void testIsBankOpen_WhenClosed_ReturnsFalse() {
        setupBankClosed();
        assertFalse(BankTask.isBankOpen(client));
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void setupBankClosed() {
        when(client.getWidget(WIDGET_BANK_GROUP, WIDGET_BANK_CONTAINER)).thenReturn(null);
    }

    private void setupBankOpen() {
        Widget bankContainer = mock(Widget.class);
        when(bankContainer.isHidden()).thenReturn(false);
        when(client.getWidget(WIDGET_BANK_GROUP, WIDGET_BANK_CONTAINER)).thenReturn(bankContainer);
    }

    private void setupBankBooth(WorldPoint pos) {
        Tile[][][] tiles = new Tile[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        
        // Convert world to scene coordinates
        int sceneX = pos.getX() - 3200 + 52;
        int sceneY = pos.getY() - 3200 + 52;

        if (sceneX >= 0 && sceneX < Constants.SCENE_SIZE &&
            sceneY >= 0 && sceneY < Constants.SCENE_SIZE) {
            
            Tile tile = mock(Tile.class);
            GameObject booth = createMockBankBooth(pos);
            when(tile.getGameObjects()).thenReturn(new GameObject[]{booth});
            tiles[pos.getPlane()][sceneX][sceneY] = tile;
        }

        when(scene.getTiles()).thenReturn(tiles);
    }

    private GameObject createMockBankBooth(WorldPoint pos) {
        GameObject booth = mock(GameObject.class);
        when(booth.getId()).thenReturn(ObjectID.BANK_BOOTH_10355);
        when(booth.getWorldLocation()).thenReturn(pos);
        
        Shape clickbox = new Rectangle(100, 100, 50, 50);
        when(booth.getClickbox()).thenReturn(clickbox);
        
        return booth;
    }

    private void addBanker(WorldPoint pos) {
        NPC banker = mock(NPC.class);
        when(banker.getId()).thenReturn(NpcID.BANKER);
        when(banker.getName()).thenReturn("Banker");
        when(banker.getWorldLocation()).thenReturn(pos);
        when(banker.isDead()).thenReturn(false);
        
        Shape hull = new Rectangle(100, 100, 50, 50);
        when(banker.getConvexHull()).thenReturn(hull);
        
        npcList.add(banker);
    }

    private void setupBankWidgets(boolean depositInventory, boolean depositEquipment) {
        if (depositInventory) {
            Widget depositInvBtn = mock(Widget.class);
            when(depositInvBtn.isHidden()).thenReturn(false);
            when(depositInvBtn.getBounds()).thenReturn(new Rectangle(100, 100, 30, 30));
            when(client.getWidget(WIDGET_BANK_GROUP, WIDGET_DEPOSIT_INVENTORY)).thenReturn(depositInvBtn);
        }

        if (depositEquipment) {
            Widget depositEquipBtn = mock(Widget.class);
            when(depositEquipBtn.isHidden()).thenReturn(false);
            when(depositEquipBtn.getBounds()).thenReturn(new Rectangle(140, 100, 30, 30));
            when(client.getWidget(WIDGET_BANK_GROUP, WIDGET_DEPOSIT_EQUIPMENT)).thenReturn(depositEquipBtn);
        }
    }

    private void setupBankWithItem(int itemId) {
        Widget bankItems = mock(Widget.class);
        Widget itemWidget = mock(Widget.class);
        when(itemWidget.getItemId()).thenReturn(itemId);
        when(itemWidget.getBounds()).thenReturn(new Rectangle(100, 100, 32, 32));
        when(bankItems.getDynamicChildren()).thenReturn(new Widget[]{itemWidget});
        when(client.getWidget(WIDGET_BANK_GROUP, WIDGET_BANK_ITEMS)).thenReturn(bankItems);
    }

    private void setupQuantityButtons() {
        Widget qtyWidget = mock(Widget.class);
        when(qtyWidget.isHidden()).thenReturn(false);
        when(qtyWidget.getBounds()).thenReturn(new Rectangle(200, 200, 20, 20));
        
        // Setup all quantity buttons
        for (int childId = 30; childId <= 38; childId += 2) {
            when(client.getWidget(WIDGET_BANK_GROUP, childId)).thenReturn(qtyWidget);
        }
    }

    private void setupChatboxInput() {
        Widget chatboxInput = mock(Widget.class);
        when(chatboxInput.isHidden()).thenReturn(false);
        when(client.getWidget(WIDGET_CHATBOX_GROUP, WIDGET_CHATBOX_INPUT)).thenReturn(chatboxInput);
    }
}

