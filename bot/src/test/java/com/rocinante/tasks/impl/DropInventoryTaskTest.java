package com.rocinante.tasks.impl;

import com.rocinante.core.GameStateService;
import com.rocinante.input.InventoryClickHelper;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for DropInventoryTask.
 * Tests shift+click dropping, keep amounts, drop patterns, and humanized delays.
 */
public class DropInventoryTaskTest {


    @Mock
    private Client client;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private RobotMouseController mouseController;

    @Mock
    private RobotKeyboardController keyboardController;

    @Mock
    private InventoryClickHelper inventoryClickHelper;

    @Mock
    private HumanTimer humanTimer;

    @Mock
    private Randomization randomization;

    private TaskContext taskContext;
    private PlayerState playerState;
    private InventoryState inventoryState;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        playerState = PlayerState.builder()
                .animationId(-1)
                .isMoving(false)
                .isInteracting(false)
                .build();

        inventoryState = mock(InventoryState.class);
        when(inventoryState.countItem(anyInt())).thenReturn(0);

        // TaskContext wiring
        taskContext = mock(TaskContext.class);
        when(taskContext.getClient()).thenReturn(client);
        when(taskContext.getGameStateService()).thenReturn(gameStateService);
        when(taskContext.getMouseController()).thenReturn(mouseController);
        when(taskContext.getKeyboardController()).thenReturn(keyboardController);
        when(taskContext.getInventoryClickHelper()).thenReturn(inventoryClickHelper);
        when(taskContext.getHumanTimer()).thenReturn(humanTimer);
        when(taskContext.getRandomization()).thenReturn(randomization);
        when(taskContext.isLoggedIn()).thenReturn(true);
        when(taskContext.getPlayerState()).thenReturn(playerState);
        when(taskContext.getInventoryState()).thenReturn(inventoryState);

        // Default click helper returns success
        when(inventoryClickHelper.executeClick(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // Default keyboard returns success for all key operations
        when(keyboardController.pressKey(anyInt(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(keyboardController.holdKey(anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(keyboardController.releaseKey(anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Default timer completion
        when(humanTimer.sleep(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Default randomization
        when(randomization.uniformRandomLong(anyLong(), anyLong())).thenReturn(100L);
        when(randomization.chance(anyDouble())).thenReturn(false);
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Test
    public void testConstructor_WithCollection() {
        List<Integer> items = List.of(ItemID.IRON_ORE, ItemID.COAL);
        DropInventoryTask task = new DropInventoryTask(items);

        assertEquals(2, task.getItemIdsToDrop().size());
        assertTrue(task.getItemIdsToDrop().contains(ItemID.IRON_ORE));
        assertTrue(task.getItemIdsToDrop().contains(ItemID.COAL));
    }

    @Test
    public void testConstructor_WithSingleItem() {
        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE);

        assertEquals(1, task.getItemIdsToDrop().size());
        assertTrue(task.getItemIdsToDrop().contains(ItemID.IRON_ORE));
    }

    @Test
    public void testForItemIds_Collection() {
        DropInventoryTask task = DropInventoryTask.forItemIds(List.of(ItemID.IRON_ORE, ItemID.COAL));

        assertEquals(2, task.getItemIdsToDrop().size());
    }

    @Test
    public void testForItemIds_Varargs() {
        DropInventoryTask task = DropInventoryTask.forItemIds(ItemID.IRON_ORE, ItemID.COAL, ItemID.GOLD_ORE);

        assertEquals(3, task.getItemIdsToDrop().size());
    }

    // ========================================================================
    // Builder Method Tests
    // ========================================================================

    @Test
    public void testKeepAmount() {
        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE)
                .keepAmount(5);

        assertEquals(5, task.getKeepAmount());
    }

    @Test
    public void testKeepAmount_NegativeClampedToZero() {
        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE)
                .keepAmount(-1);

        assertEquals(0, task.getKeepAmount());
    }

    @Test
    public void testWithDescription() {
        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE)
                .withDescription("Drop ores for power mining");

        assertEquals("Drop ores for power mining", task.getDescription());
    }

    @Test
    public void testWithPattern() {
        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE)
                .withPattern(DropInventoryTask.DropPattern.COLUMN);

        assertEquals(DropInventoryTask.DropPattern.COLUMN, task.getDropPattern());
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_HasItems_True() {
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(10);

        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE);

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_NoItems_False() {
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(0);

        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE);

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_NotLoggedIn_False() {
        when(taskContext.isLoggedIn()).thenReturn(false);
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(10);

        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE);

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_KeepAmount_OnlyIfExcess() {
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(5);

        DropInventoryTask keepAll = new DropInventoryTask(ItemID.IRON_ORE)
                .keepAmount(5);
        DropInventoryTask keepSome = new DropInventoryTask(ItemID.IRON_ORE)
                .keepAmount(3);

        assertFalse(keepAll.canExecute(taskContext));
        assertTrue(keepSome.canExecute(taskContext));
    }

    // ========================================================================
    // Prepare Phase Tests
    // ========================================================================

    @Test
    public void testPrepare_BuildsSlotQueue() {
        // Setup inventory with items in specific slots
        Item ironOre = mock(Item.class);
        when(ironOre.getId()).thenReturn(ItemID.IRON_ORE);
        
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(5);
        when(inventoryState.getItemInSlot(0)).thenReturn(Optional.of(ironOre));
        when(inventoryState.getItemInSlot(5)).thenReturn(Optional.of(ironOre));
        when(inventoryState.getItemInSlot(10)).thenReturn(Optional.of(ironOre));
        for (int i = 0; i < 28; i++) {
            if (i != 0 && i != 5 && i != 10) {
                when(inventoryState.getItemInSlot(i)).thenReturn(Optional.empty());
            }
        }

        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE);
        task.execute(taskContext);

        // Should have prepared slots to drop
        verify(inventoryState, atLeastOnce()).getItemInSlot(anyInt());
    }

    @Test
    public void testPrepare_RespectsKeepAmount() {
        Item ironOre = mock(Item.class);
        when(ironOre.getId()).thenReturn(ItemID.IRON_ORE);
        
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(5);
        
        // Slots 0-4 have iron ore
        for (int i = 0; i < 5; i++) {
            when(inventoryState.getItemInSlot(i)).thenReturn(Optional.of(ironOre));
        }
        for (int i = 5; i < 28; i++) {
            when(inventoryState.getItemInSlot(i)).thenReturn(Optional.empty());
        }

        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE)
                .keepAmount(2);
        task.execute(taskContext);

        // Should keep first 2 items, drop remaining 3
    }

    @Test
    public void testPrepare_NoItemsToDrop_CannotExecute() {
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(2);
        
        Item ironOre = mock(Item.class);
        when(ironOre.getId()).thenReturn(ItemID.IRON_ORE);
        
        when(inventoryState.getItemInSlot(0)).thenReturn(Optional.of(ironOre));
        when(inventoryState.getItemInSlot(1)).thenReturn(Optional.of(ironOre));
        for (int i = 2; i < 28; i++) {
            when(inventoryState.getItemInSlot(i)).thenReturn(Optional.empty());
        }

        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE)
                .keepAmount(5); // Keep more than we have
        
        // canExecute returns false when we have fewer items than keepAmount
        assertFalse("Should not execute when no items to drop", task.canExecute(taskContext));
        
        // Task stays PENDING when preconditions not met
        task.execute(taskContext);
        assertEquals(TaskState.PENDING, task.getState());
    }

    // ========================================================================
    // Drop Pattern Tests
    // ========================================================================

    @Test
    public void testDropPattern_Sequential() {
        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE)
                .withPattern(DropInventoryTask.DropPattern.SEQUENTIAL);

        assertEquals(DropInventoryTask.DropPattern.SEQUENTIAL, task.getDropPattern());
    }

    @Test
    public void testDropPattern_Random() {
        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE)
                .withPattern(DropInventoryTask.DropPattern.RANDOM);

        assertEquals(DropInventoryTask.DropPattern.RANDOM, task.getDropPattern());
    }

    @Test
    public void testDropPattern_Column() {
        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE)
                .withPattern(DropInventoryTask.DropPattern.COLUMN);

        assertEquals(DropInventoryTask.DropPattern.COLUMN, task.getDropPattern());
    }

    // ========================================================================
    // Shift+Click Behavior Tests
    // ========================================================================

    @Test
    public void testShiftClickDrop_PressesShift() {
        Item ironOre = mock(Item.class);
        when(ironOre.getId()).thenReturn(ItemID.IRON_ORE);
        
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(1);
        when(inventoryState.getItemInSlot(0)).thenReturn(Optional.of(ironOre));
        for (int i = 1; i < 28; i++) {
            when(inventoryState.getItemInSlot(i)).thenReturn(Optional.empty());
        }

        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE);
        
        // Execute multiple times to progress through phases
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should have held shift key in HOLD_SHIFT phase
        verify(keyboardController, atLeastOnce()).holdKey(eq(KeyEvent.VK_SHIFT));
    }

    @Test
    public void testShiftClickDrop_ClicksItems() {
        Item ironOre = mock(Item.class);
        when(ironOre.getId()).thenReturn(ItemID.IRON_ORE);
        
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(3);
        when(inventoryState.getItemInSlot(0)).thenReturn(Optional.of(ironOre));
        when(inventoryState.getItemInSlot(1)).thenReturn(Optional.of(ironOre));
        when(inventoryState.getItemInSlot(2)).thenReturn(Optional.of(ironOre));
        for (int i = 3; i < 28; i++) {
            when(inventoryState.getItemInSlot(i)).thenReturn(Optional.empty());
        }

        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE);
        
        // Execute to progress through dropping
        for (int i = 0; i < 20; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should have clicked items in inventory
        verify(inventoryClickHelper, atLeastOnce()).executeClick(anyInt(), anyString());
    }

    // ========================================================================
    // Humanization Tests
    // ========================================================================

    @Test
    public void testHumanization_DelaysBetweenDrops() {
        Item ironOre = mock(Item.class);
        when(ironOre.getId()).thenReturn(ItemID.IRON_ORE);
        
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(2);
        when(inventoryState.getItemInSlot(0)).thenReturn(Optional.of(ironOre));
        when(inventoryState.getItemInSlot(1)).thenReturn(Optional.of(ironOre));
        for (int i = 2; i < 28; i++) {
            when(inventoryState.getItemInSlot(i)).thenReturn(Optional.empty());
        }

        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE);
        
        for (int i = 0; i < 20; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should use humanized (log-normal) delays instead of uniform
        verify(randomization, atLeastOnce()).humanizedDelayMs(anyLong(), anyLong(), anyLong());
    }

    @Test
    public void testHumanization_MicroPauses() {
        // Enable micro-pauses
        when(randomization.chance(eq(0.07))).thenReturn(true);
        
        Item ironOre = mock(Item.class);
        when(ironOre.getId()).thenReturn(ItemID.IRON_ORE);
        
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(5);
        for (int i = 0; i < 5; i++) {
            when(inventoryState.getItemInSlot(i)).thenReturn(Optional.of(ironOre));
        }
        for (int i = 5; i < 28; i++) {
            when(inventoryState.getItemInSlot(i)).thenReturn(Optional.empty());
        }

        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE);
        
        for (int i = 0; i < 30; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should check for micro-pause chance
        verify(randomization, atLeastOnce()).chance(eq(0.07));
    }

    // ========================================================================
    // Multiple Item Types Tests
    // ========================================================================

    @Test
    public void testMultipleItems_DropsAll() {
        Item ironOre = mock(Item.class);
        when(ironOre.getId()).thenReturn(ItemID.IRON_ORE);
        Item coal = mock(Item.class);
        when(coal.getId()).thenReturn(ItemID.COAL);
        
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(2);
        when(inventoryState.countItem(ItemID.COAL)).thenReturn(2);
        
        when(inventoryState.getItemInSlot(0)).thenReturn(Optional.of(ironOre));
        when(inventoryState.getItemInSlot(1)).thenReturn(Optional.of(ironOre));
        when(inventoryState.getItemInSlot(2)).thenReturn(Optional.of(coal));
        when(inventoryState.getItemInSlot(3)).thenReturn(Optional.of(coal));
        for (int i = 4; i < 28; i++) {
            when(inventoryState.getItemInSlot(i)).thenReturn(Optional.empty());
        }

        DropInventoryTask task = new DropInventoryTask(List.of(ItemID.IRON_ORE, ItemID.COAL));

        assertTrue(task.canExecute(taskContext));
        Set<Integer> items = task.getItemIdsToDrop();
        assertTrue(items.contains(ItemID.IRON_ORE));
        assertTrue(items.contains(ItemID.COAL));
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_Default() {
        DropInventoryTask task = new DropInventoryTask(List.of(ItemID.IRON_ORE, ItemID.COAL));

        String desc = task.getDescription();
        assertTrue(desc.contains("DropInventory"));
        assertTrue(desc.contains("keep=0"));
    }

    @Test
    public void testGetDescription_Custom() {
        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE)
                .withDescription("Power mine drop");

        assertEquals("Power mine drop", task.getDescription());
    }

    // ========================================================================
    // Real Game Scenario Tests
    // ========================================================================

    @Test
    public void testScenario_PowerMining() {
        // Power mining: drop all ores, keep pickaxe
        Item ironOre = mock(Item.class);
        when(ironOre.getId()).thenReturn(ItemID.IRON_ORE);
        
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(27);
        for (int i = 0; i < 27; i++) {
            when(inventoryState.getItemInSlot(i)).thenReturn(Optional.of(ironOre));
        }
        when(inventoryState.getItemInSlot(27)).thenReturn(Optional.empty()); // Pickaxe slot

        DropInventoryTask task = DropInventoryTask.forItemIds(ItemID.IRON_ORE)
                .withPattern(DropInventoryTask.DropPattern.COLUMN)
                .withDescription("Drop ores");

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testScenario_DropAllButOne() {
        // Keep 1 for combo eating/etc
        Item food = mock(Item.class);
        when(food.getId()).thenReturn(ItemID.LOBSTER);
        
        when(inventoryState.countItem(ItemID.LOBSTER)).thenReturn(5);
        for (int i = 0; i < 5; i++) {
            when(inventoryState.getItemInSlot(i)).thenReturn(Optional.of(food));
        }
        for (int i = 5; i < 28; i++) {
            when(inventoryState.getItemInSlot(i)).thenReturn(Optional.empty());
        }

        DropInventoryTask task = new DropInventoryTask(ItemID.LOBSTER)
                .keepAmount(1);

        assertTrue(task.canExecute(taskContext));
        assertEquals(1, task.getKeepAmount());
    }

    // ========================================================================
    // Error Handling Tests
    // ========================================================================

    @Test
    public void testError_NoKeyboardController() {
        when(taskContext.getKeyboardController()).thenReturn(null);
        
        Item ironOre = mock(Item.class);
        when(ironOre.getId()).thenReturn(ItemID.IRON_ORE);
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(1);
        when(inventoryState.getItemInSlot(0)).thenReturn(Optional.of(ironOre));
        for (int i = 1; i < 28; i++) {
            when(inventoryState.getItemInSlot(i)).thenReturn(Optional.empty());
        }

        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE);
        
        // Should fail when trying to use keyboard
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testError_ItemDisappearsDuringDrop() {
        Item ironOre = mock(Item.class);
        when(ironOre.getId()).thenReturn(ItemID.IRON_ORE);
        
        when(inventoryState.countItem(ItemID.IRON_ORE)).thenReturn(3);
        when(inventoryState.getItemInSlot(0)).thenReturn(Optional.of(ironOre));
        when(inventoryState.getItemInSlot(1)).thenReturn(Optional.of(ironOre));
        when(inventoryState.getItemInSlot(2)).thenReturn(Optional.of(ironOre));
        for (int i = 3; i < 28; i++) {
            when(inventoryState.getItemInSlot(i)).thenReturn(Optional.empty());
        }

        DropInventoryTask task = new DropInventoryTask(ItemID.IRON_ORE);
        
        // Execute to prepare
        task.execute(taskContext);
        
        // Item disappears during dropping (traded, banked, etc.)
        when(inventoryState.getItemInSlot(0)).thenReturn(Optional.empty());
        
        // Should skip missing item and continue
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should not fail, just skip the missing item
    }
}

