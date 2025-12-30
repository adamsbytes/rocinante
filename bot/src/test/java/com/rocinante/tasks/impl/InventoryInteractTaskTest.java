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
import net.runelite.api.AnimationID;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for InventoryInteractTask.
 * Tests eating, drinking, dropping items, item consumption detection, and priority ordering.
 */
public class InventoryInteractTaskTest {


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
    private InventoryClickHelper inventoryClickHelper;

    @Mock
    private InventoryState inventoryState;

    private TaskContext taskContext;
    private PlayerState playerState;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        playerState = PlayerState.builder()
                .worldPosition(null)
                .animationId(-1)
                .isMoving(false)
                .isInteracting(false)
                .build();

        // Wire up TaskContext
        taskContext = mock(TaskContext.class);
        when(taskContext.getClient()).thenReturn(client);
        when(taskContext.getGameStateService()).thenReturn(gameStateService);
        when(taskContext.getMouseController()).thenReturn(mouseController);
        when(taskContext.getKeyboardController()).thenReturn(keyboardController);
        when(taskContext.getHumanTimer()).thenReturn(humanTimer);
        when(taskContext.getRandomization()).thenReturn(randomization);
        when(taskContext.getInventoryClickHelper()).thenReturn(inventoryClickHelper);
        when(taskContext.isLoggedIn()).thenReturn(true);
        when(taskContext.getPlayerState()).thenReturn(playerState);
        when(taskContext.getInventoryState()).thenReturn(inventoryState);

        // GameStateService
        when(gameStateService.isLoggedIn()).thenReturn(true);
        when(gameStateService.getPlayerState()).thenReturn(playerState);

        // InventoryClickHelper default
        when(inventoryClickHelper.executeClick(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // Empty inventory by default
        when(inventoryState.hasItem(anyInt())).thenReturn(false);
        when(inventoryState.getSlotOf(anyInt())).thenReturn(-1);
        when(inventoryState.countItem(anyInt())).thenReturn(0);
        when(inventoryState.getNonEmptyItems()).thenReturn(Collections.emptyList());
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Test
    public void testConstructor_SingleItem() {
        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat");

        assertEquals(1, task.getItemIds().size());
        assertEquals(ItemID.LOBSTER, (int) task.getItemIds().get(0));
        assertEquals("eat", task.getAction());
    }

    @Test
    public void testConstructor_MultipleItems() {
        List<Integer> items = Arrays.asList(ItemID.PRAYER_POTION4, ItemID.PRAYER_POTION3, ItemID.PRAYER_POTION2, ItemID.PRAYER_POTION1);
        InventoryInteractTask task = new InventoryInteractTask(items, "drink");

        assertEquals(4, task.getItemIds().size());
        assertEquals(ItemID.PRAYER_POTION4, (int) task.getItemIds().get(0));
        assertEquals("drink", task.getAction());
    }

    @Test
    public void testConstructor_ActionNormalized() {
        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "EAT");

        assertEquals("eat", task.getAction());
    }

    // ========================================================================
    // Builder Method Tests
    // ========================================================================

    @Test
    public void testWithDescription() {
        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat")
                .withDescription("Eat lobster for healing");

        assertEquals("Eat lobster for healing", task.getDescription());
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_NotLoggedIn_ReturnsFalse() {
        when(taskContext.isLoggedIn()).thenReturn(false);

        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat");

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_ItemNotInInventory_ReturnsFalse() {
        when(inventoryState.hasItem(ItemID.LOBSTER)).thenReturn(false);

        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat");

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_ItemInInventory_ReturnsTrue() {
        when(inventoryState.hasItem(ItemID.LOBSTER)).thenReturn(true);

        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat");

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_MultipleItems_FirstMatchReturnsTrue() {
        List<Integer> potions = Arrays.asList(ItemID.PRAYER_POTION4, ItemID.PRAYER_POTION3, ItemID.PRAYER_POTION2);
        
        // Only has potion 3
        when(inventoryState.hasItem(ItemID.PRAYER_POTION4)).thenReturn(false);
        when(inventoryState.hasItem(ItemID.PRAYER_POTION3)).thenReturn(true);

        InventoryInteractTask task = new InventoryInteractTask(potions, "drink");

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_MultipleItems_NoneFound_ReturnsFalse() {
        List<Integer> potions = Arrays.asList(ItemID.PRAYER_POTION4, ItemID.PRAYER_POTION3);
        
        when(inventoryState.hasItem(ItemID.PRAYER_POTION4)).thenReturn(false);
        when(inventoryState.hasItem(ItemID.PRAYER_POTION3)).thenReturn(false);

        InventoryInteractTask task = new InventoryInteractTask(potions, "drink");

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_PriorityOrdering() {
        List<Integer> potions = Arrays.asList(ItemID.PRAYER_POTION4, ItemID.PRAYER_POTION3, ItemID.PRAYER_POTION2);
        
        // Has both potion 3 and potion 2, should prefer 3 (higher priority)
        when(inventoryState.hasItem(ItemID.PRAYER_POTION4)).thenReturn(false);
        when(inventoryState.hasItem(ItemID.PRAYER_POTION3)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.PRAYER_POTION2)).thenReturn(true);
        when(inventoryState.getSlotOf(ItemID.PRAYER_POTION3)).thenReturn(5);
        when(inventoryState.countItem(ItemID.PRAYER_POTION3)).thenReturn(1);

        InventoryInteractTask task = new InventoryInteractTask(potions, "drink");

        assertTrue(task.canExecute(taskContext));
        
        // Execute and verify it uses potion 3
        task.execute(taskContext);
        verify(inventoryClickHelper).executeClick(eq(5), contains("drink"));
    }

    // ========================================================================
    // Phase: CLICK_ITEM Tests
    // ========================================================================

    @Test
    public void testClickItem_UsesInventoryClickHelper() {
        setupInventoryWithItem(ItemID.LOBSTER, 10);

        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat");
        task.canExecute(taskContext); // Resolve item
        task.execute(taskContext);

        verify(inventoryClickHelper).executeClick(eq(10), contains("eat"));
    }

    @Test
    public void testClickItem_NoHelper_Fails() {
        when(taskContext.getInventoryClickHelper()).thenReturn(null);
        setupInventoryWithItem(ItemID.LOBSTER, 5);

        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat");
        task.canExecute(taskContext);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("InventoryClickHelper"));
    }

    @Test
    public void testClickItem_ItemDisappeared_Fails() {
        when(inventoryState.hasItem(ItemID.LOBSTER)).thenReturn(true);
        when(inventoryState.getSlotOf(ItemID.LOBSTER)).thenReturn(-1); // Item disappeared

        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat");
        task.canExecute(taskContext);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("not found"));
    }

    @Test
    public void testClickItem_ClickFails_FailsTask() {
        setupInventoryWithItem(ItemID.LOBSTER, 5);
        when(inventoryClickHelper.executeClick(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(false));

        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat");
        task.canExecute(taskContext);
        task.execute(taskContext);

        // Wait for async
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Phase: WAIT_RESPONSE Tests
    // ========================================================================

    @Test
    public void testWaitResponse_Animation_Completes() {
        setupInventoryWithItem(ItemID.LOBSTER, 5);

        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat");
        task.canExecute(taskContext);
        task.execute(taskContext);

        // Wait for click async
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Player starts eating animation
        PlayerState eatingState = PlayerState.builder()
                .animationId(AnimationID.CONSUMING)
                .build();
        when(taskContext.getPlayerState()).thenReturn(eatingState);
        when(gameStateService.getPlayerState()).thenReturn(eatingState);

        task.execute(taskContext);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testWaitResponse_ItemConsumed_Completes() {
        setupInventoryWithItem(ItemID.LOBSTER, 5);
        when(inventoryState.countItem(ItemID.LOBSTER)).thenReturn(3); // 3 lobsters initially

        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat");
        task.canExecute(taskContext);
        task.execute(taskContext);

        // Wait for click async
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Lobster was consumed (3 -> 2)
        when(inventoryState.countItem(ItemID.LOBSTER)).thenReturn(2);

        task.execute(taskContext);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testWaitResponse_UseAction_CompletesImmediately() {
        setupInventoryWithItem(ItemID.TINDERBOX, 0);

        InventoryInteractTask task = new InventoryInteractTask(ItemID.TINDERBOX, "use");
        task.canExecute(taskContext);
        task.execute(taskContext);

        // Wait for click async
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // For "use" action, completes even without animation/consumption
        task.execute(taskContext);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testWaitResponse_Timeout_CompletesAnyway() {
        setupInventoryWithItem(ItemID.BONES, 5);
        when(inventoryState.countItem(ItemID.BONES)).thenReturn(5); // Count doesn't change

        InventoryInteractTask task = new InventoryInteractTask(ItemID.BONES, "bury");
        task.canExecute(taskContext);
        task.execute(taskContext);

        // Wait for click async
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Execute multiple times past timeout (5 ticks)
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should complete even after timeout
        assertEquals(TaskState.COMPLETED, task.getState());
    }

    // ========================================================================
    // Eat Action Tests
    // ========================================================================

    @Test
    public void testEatFood_Success() {
        setupInventoryWithItem(ItemID.SHARK, 3);
        when(inventoryState.countItem(ItemID.SHARK)).thenReturn(5);

        InventoryInteractTask task = new InventoryInteractTask(ItemID.SHARK, "eat");
        task.canExecute(taskContext);
        task.execute(taskContext);

        // Verify click was on correct slot
        verify(inventoryClickHelper).executeClick(eq(3), contains("eat"));
    }

    @Test
    public void testEatFood_MultipleTypes_SelectsFirstAvailable() {
        List<Integer> food = Arrays.asList(ItemID.SHARK, ItemID.LOBSTER);
        
        // Shark not available, lobster is
        when(inventoryState.hasItem(ItemID.SHARK)).thenReturn(false);
        when(inventoryState.hasItem(ItemID.LOBSTER)).thenReturn(true);
        when(inventoryState.getSlotOf(ItemID.LOBSTER)).thenReturn(7);
        when(inventoryState.countItem(ItemID.LOBSTER)).thenReturn(2);

        InventoryInteractTask task = new InventoryInteractTask(food, "eat");
        task.canExecute(taskContext);
        task.execute(taskContext);

        verify(inventoryClickHelper).executeClick(eq(7), contains("eat"));
    }

    // ========================================================================
    // Drink Action Tests
    // ========================================================================

    @Test
    public void testDrinkPotion_Success() {
        setupInventoryWithItem(ItemID.PRAYER_POTION4, 12);

        InventoryInteractTask task = new InventoryInteractTask(ItemID.PRAYER_POTION4, "drink");
        task.canExecute(taskContext);
        task.execute(taskContext);

        verify(inventoryClickHelper).executeClick(eq(12), contains("drink"));
    }

    @Test
    public void testDrinkPotion_DoseChain() {
        // Common pattern: drink prayer pot 4 -> 3 -> 2 -> 1
        List<Integer> potions = Arrays.asList(
                ItemID.PRAYER_POTION4, ItemID.PRAYER_POTION3, ItemID.PRAYER_POTION2, ItemID.PRAYER_POTION1
        );
        
        // Has potion 2
        when(inventoryState.hasItem(ItemID.PRAYER_POTION4)).thenReturn(false);
        when(inventoryState.hasItem(ItemID.PRAYER_POTION3)).thenReturn(false);
        when(inventoryState.hasItem(ItemID.PRAYER_POTION2)).thenReturn(true);
        when(inventoryState.getSlotOf(ItemID.PRAYER_POTION2)).thenReturn(8);
        when(inventoryState.countItem(ItemID.PRAYER_POTION2)).thenReturn(1);

        InventoryInteractTask task = new InventoryInteractTask(potions, "drink");
        task.canExecute(taskContext);
        task.execute(taskContext);

        verify(inventoryClickHelper).executeClick(eq(8), contains("drink"));
    }

    // ========================================================================
    // Drop Action Tests
    // ========================================================================

    @Test
    public void testDropItem_Success() {
        setupInventoryWithItem(ItemID.BONES, 20);

        InventoryInteractTask task = new InventoryInteractTask(ItemID.BONES, "drop");
        task.canExecute(taskContext);
        task.execute(taskContext);

        verify(inventoryClickHelper).executeClick(eq(20), contains("drop"));
    }

    // ========================================================================
    // Use Action Tests
    // ========================================================================

    @Test
    public void testUseItem_Success() {
        setupInventoryWithItem(ItemID.TINDERBOX, 1);

        InventoryInteractTask task = new InventoryInteractTask(ItemID.TINDERBOX, "use");
        task.canExecute(taskContext);
        task.execute(taskContext);

        verify(inventoryClickHelper).executeClick(eq(1), contains("use"));
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_SingleItem() {
        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat");
        String desc = task.getDescription();

        assertTrue(desc.contains("eat"));
        assertTrue(desc.contains(String.valueOf(ItemID.LOBSTER)));
    }

    @Test
    public void testGetDescription_MultipleItems() {
        List<Integer> items = Arrays.asList(ItemID.SHARK, ItemID.LOBSTER);
        InventoryInteractTask task = new InventoryInteractTask(items, "eat");
        String desc = task.getDescription();

        assertTrue(desc.contains("eat"));
        assertTrue(desc.contains(String.valueOf(ItemID.SHARK)));
        assertTrue(desc.contains(String.valueOf(ItemID.LOBSTER)));
    }

    @Test
    public void testGetDescription_Custom() {
        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat")
                .withDescription("Heal up with food");

        assertEquals("Heal up with food", task.getDescription());
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testClickDuringAnimation_StillWorks() {
        setupInventoryWithItem(ItemID.LOBSTER, 5);

        // Player already animating when click happens
        PlayerState animatingState = PlayerState.builder()
                .animationId(AnimationID.CONSUMING)
                .build();
        when(taskContext.getPlayerState()).thenReturn(animatingState);
        when(gameStateService.getPlayerState()).thenReturn(animatingState);

        InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat");
        task.canExecute(taskContext);
        task.execute(taskContext);

        // Should still attempt click
        verify(inventoryClickHelper).executeClick(anyInt(), anyString());
    }

    @Test
    public void testSlotPosition_CalculatedCorrectly() {
        // Test that task handles different slot positions correctly
        for (int slot = 0; slot < 28; slot++) {
            reset(inventoryClickHelper);
            when(inventoryClickHelper.executeClick(anyInt(), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(true));

            when(inventoryState.hasItem(ItemID.LOBSTER)).thenReturn(true);
            when(inventoryState.getSlotOf(ItemID.LOBSTER)).thenReturn(slot);
            when(inventoryState.countItem(ItemID.LOBSTER)).thenReturn(1);

            InventoryInteractTask task = new InventoryInteractTask(ItemID.LOBSTER, "eat");
            task.canExecute(taskContext);
            task.execute(taskContext);

            verify(inventoryClickHelper).executeClick(eq(slot), anyString());
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void setupInventoryWithItem(int itemId, int slot) {
        when(inventoryState.hasItem(itemId)).thenReturn(true);
        when(inventoryState.getSlotOf(itemId)).thenReturn(slot);
        when(inventoryState.countItem(itemId)).thenReturn(1);
        
        Item item = mock(Item.class);
        when(item.getId()).thenReturn(itemId);
        when(item.getQuantity()).thenReturn(1);
        when(inventoryState.getNonEmptyItems()).thenReturn(Collections.singletonList(item));
    }
}

