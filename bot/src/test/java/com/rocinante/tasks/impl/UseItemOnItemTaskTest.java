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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for UseItemOnItemTask.
 * Tests inventory combining interactions like crafting and item transformations.
 */
public class UseItemOnItemTaskTest {


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
    public void testConstructor_SingleSourceSingleTarget() {
        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS);

        assertEquals(1, task.getSourceItemIds().size());
        assertEquals(ItemID.TINDERBOX, (int) task.getSourceItemIds().get(0));
        assertEquals(1, task.getTargetItemIds().size());
        assertEquals(ItemID.LOGS, (int) task.getTargetItemIds().get(0));
    }

    @Test
    public void testConstructor_MultipleSourcesSingleTarget() {
        List<Integer> sources = Arrays.asList(ItemID.TINDERBOX, 9104); // Normal and golden tinderbox
        UseItemOnItemTask task = new UseItemOnItemTask(sources, ItemID.LOGS);

        assertEquals(2, task.getSourceItemIds().size());
        assertEquals(1, task.getTargetItemIds().size());
    }

    @Test
    public void testConstructor_SingleSourceMultipleTargets() {
        List<Integer> targets = Arrays.asList(ItemID.LOGS, ItemID.OAK_LOGS, 1521);
        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, targets);

        assertEquals(1, task.getSourceItemIds().size());
        assertEquals(3, task.getTargetItemIds().size());
    }

    @Test
    public void testConstructor_MultipleSourcesMultipleTargets() {
        List<Integer> sources = Arrays.asList(ItemID.TINDERBOX, 9104);
        List<Integer> targets = Arrays.asList(ItemID.LOGS, ItemID.OAK_LOGS);
        UseItemOnItemTask task = new UseItemOnItemTask(sources, targets);

        assertEquals(2, task.getSourceItemIds().size());
        assertEquals(2, task.getTargetItemIds().size());
    }

    // ========================================================================
    // Builder Method Tests
    // ========================================================================

    @Test
    public void testWithDescription() {
        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.BUCKET_OF_WATER, ItemID.POT_OF_FLOUR)
                .withDescription("Make bread dough");

        assertEquals("Make bread dough", task.getDescription());
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_NotLoggedIn_ReturnsFalse() {
        when(taskContext.isLoggedIn()).thenReturn(false);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS);

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_SourceNotInInventory_ReturnsFalse() {
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(false);
        when(inventoryState.hasItem(ItemID.LOGS)).thenReturn(true);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS);

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_TargetNotInInventory_ReturnsFalse() {
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.LOGS)).thenReturn(false);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS);

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_BothItemsPresent_ReturnsTrue() {
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.LOGS)).thenReturn(true);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS);

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_MultipleItems_FirstMatchWins() {
        List<Integer> sources = Arrays.asList(ItemID.TINDERBOX, 9104);
        List<Integer> targets = Arrays.asList(ItemID.LOGS, ItemID.OAK_LOGS);
        
        // Has golden tinderbox and oak logs
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(false);
        when(inventoryState.hasItem(9104)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.LOGS)).thenReturn(false);
        when(inventoryState.hasItem(ItemID.OAK_LOGS)).thenReturn(true);

        UseItemOnItemTask task = new UseItemOnItemTask(sources, targets);

        assertTrue(task.canExecute(taskContext));
    }

    // ========================================================================
    // Phase: CLICK_SOURCE Tests
    // ========================================================================

    @Test
    public void testClickSource_UsesInventoryClickHelper() {
        setupInventoryWithItems(ItemID.TINDERBOX, 0, ItemID.LOGS, 5);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS);
        task.canExecute(taskContext);
        task.execute(taskContext);

        verify(inventoryClickHelper).executeClick(eq(0), contains("Use source"));
    }

    @Test
    public void testClickSource_NoHelper_Fails() {
        when(taskContext.getInventoryClickHelper()).thenReturn(null);
        setupInventoryWithItems(ItemID.TINDERBOX, 0, ItemID.LOGS, 5);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS);
        task.canExecute(taskContext);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("InventoryClickHelper"));
    }

    @Test
    public void testClickSource_ItemConsumed_Fails() {
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.LOGS)).thenReturn(true);
        when(inventoryState.getSlotOf(ItemID.TINDERBOX)).thenReturn(-1); // Disappeared

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS);
        task.canExecute(taskContext);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testClickSource_ClickFails_FailsTask() {
        setupInventoryWithItems(ItemID.TINDERBOX, 0, ItemID.LOGS, 5);
        when(inventoryClickHelper.executeClick(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(false));

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS);
        task.canExecute(taskContext);
        task.execute(taskContext);

        // Wait for async
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Phase: CLICK_TARGET Tests
    // ========================================================================

    @Test
    public void testClickTarget_UsesInventoryClickHelper() {
        setupInventoryWithItems(ItemID.TINDERBOX, 0, ItemID.LOGS, 5);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS);
        task.canExecute(taskContext);
        
        // Execute CLICK_SOURCE
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Execute CLICK_TARGET
        task.execute(taskContext);

        verify(inventoryClickHelper).executeClick(eq(5), contains("Use on target"));
    }

    @Test
    public void testClickTarget_TargetConsumed_Fails() {
        setupInventoryWithItems(ItemID.BUCKET_OF_WATER, 0, ItemID.POT_OF_FLOUR, 5);
        
        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.BUCKET_OF_WATER, ItemID.POT_OF_FLOUR);
        task.canExecute(taskContext);
        
        // Execute CLICK_SOURCE
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Target disappears
        when(inventoryState.getSlotOf(ItemID.POT_OF_FLOUR)).thenReturn(-1);

        // Execute CLICK_TARGET
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Phase: WAIT_RESPONSE Tests
    // ========================================================================

    @Test
    public void testWaitResponse_Animation_Completes() {
        setupInventoryWithItems(ItemID.NEEDLE, 0, ItemID.LEATHER, 5);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.NEEDLE, ItemID.LEATHER);
        task.canExecute(taskContext);
        
        // Execute through CLICK_SOURCE and CLICK_TARGET
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Player starts crafting animation
        PlayerState craftingState = PlayerState.builder()
                .animationId(1249) // Crafting animation
                .build();
        when(taskContext.getPlayerState()).thenReturn(craftingState);

        task.execute(taskContext);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testWaitResponse_TargetConsumed_Completes() {
        setupInventoryWithItems(ItemID.BUCKET_OF_WATER, 0, ItemID.POT_OF_FLOUR, 5);
        when(inventoryState.countItem(ItemID.POT_OF_FLOUR)).thenReturn(1);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.BUCKET_OF_WATER, ItemID.POT_OF_FLOUR);
        task.canExecute(taskContext);
        
        // Execute through phases
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Pot of flour consumed (transformed to bread dough)
        when(inventoryState.countItem(ItemID.POT_OF_FLOUR)).thenReturn(0);

        task.execute(taskContext);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testWaitResponse_SourceConsumed_Completes() {
        setupInventoryWithItems(ItemID.THREAD, 0, ItemID.NEEDLE, 5);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.THREAD, ItemID.NEEDLE);
        task.canExecute(taskContext);
        
        // Execute through phases
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Thread consumed
        when(inventoryState.hasItem(ItemID.THREAD)).thenReturn(false);

        task.execute(taskContext);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testWaitResponse_Timeout_Fails() {
        setupInventoryWithItems(ItemID.TINDERBOX, 0, ItemID.LOGS, 5);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS);
        task.canExecute(taskContext);
        
        // Execute through phases
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Execute past timeout
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("timeout"));
    }

    // ========================================================================
    // Real Game Scenario Tests
    // ========================================================================

    @Test
    public void testMakeBreadDough_WaterOnFlour() {
        setupInventoryWithItems(ItemID.BUCKET_OF_WATER, 3, ItemID.POT_OF_FLOUR, 15);
        when(inventoryState.countItem(ItemID.POT_OF_FLOUR)).thenReturn(1);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.BUCKET_OF_WATER, ItemID.POT_OF_FLOUR)
                .withDescription("Make bread dough");

        assertTrue(task.canExecute(taskContext));
        
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Verify first click was on water bucket
        verify(inventoryClickHelper).executeClick(eq(3), anyString());
    }

    @Test
    public void testFiremaking_TinderboxOnLogs() {
        setupInventoryWithItems(ItemID.TINDERBOX, 0, ItemID.LOGS, 27);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS)
                .withDescription("Light a fire");

        assertTrue(task.canExecute(taskContext));
        
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        verify(inventoryClickHelper).executeClick(eq(0), anyString());
    }

    @Test
    public void testCrafting_NeedleOnLeather() {
        setupInventoryWithItems(ItemID.NEEDLE, 0, ItemID.LEATHER, 27);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.NEEDLE, ItemID.LEATHER)
                .withDescription("Craft leather");

        assertTrue(task.canExecute(taskContext));
        
        // Execute through both phases
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        verify(inventoryClickHelper, times(2)).executeClick(anyInt(), anyString());
    }

    @Test
    public void testMultipleLogTypes_SelectsFirstAvailable() {
        List<Integer> logTypes = Arrays.asList(ItemID.LOGS, ItemID.OAK_LOGS, 1521);
        
        // Only has oak logs
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.LOGS)).thenReturn(false);
        when(inventoryState.hasItem(ItemID.OAK_LOGS)).thenReturn(true);
        when(inventoryState.getSlotOf(ItemID.TINDERBOX)).thenReturn(0);
        when(inventoryState.getSlotOf(ItemID.OAK_LOGS)).thenReturn(5);

        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, logTypes);

        assertTrue(task.canExecute(taskContext));
        
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);

        // Should have clicked on oak logs slot
        verify(inventoryClickHelper).executeClick(eq(5), contains("target"));
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_SingleItems() {
        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS);
        String desc = task.getDescription();

        assertTrue(desc.contains(String.valueOf(ItemID.TINDERBOX)));
        assertTrue(desc.contains(String.valueOf(ItemID.LOGS)));
    }

    @Test
    public void testGetDescription_MultipleItems() {
        List<Integer> sources = Arrays.asList(ItemID.TINDERBOX, 9104);
        List<Integer> targets = Arrays.asList(ItemID.LOGS, ItemID.OAK_LOGS);
        UseItemOnItemTask task = new UseItemOnItemTask(sources, targets);
        String desc = task.getDescription();

        assertTrue(desc.contains("sources"));
        assertTrue(desc.contains("targets"));
    }

    @Test
    public void testGetDescription_Custom() {
        UseItemOnItemTask task = new UseItemOnItemTask(ItemID.BUCKET_OF_WATER, ItemID.POT_OF_FLOUR)
                .withDescription("Make dough for bread");

        assertEquals("Make dough for bread", task.getDescription());
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void setupInventoryWithItems(int sourceId, int sourceSlot, int targetId, int targetSlot) {
        when(inventoryState.hasItem(sourceId)).thenReturn(true);
        when(inventoryState.hasItem(targetId)).thenReturn(true);
        when(inventoryState.getSlotOf(sourceId)).thenReturn(sourceSlot);
        when(inventoryState.getSlotOf(targetId)).thenReturn(targetSlot);
        when(inventoryState.countItem(sourceId)).thenReturn(1);
        when(inventoryState.countItem(targetId)).thenReturn(1);

        Item sourceItem = mock(Item.class);
        when(sourceItem.getId()).thenReturn(sourceId);
        when(sourceItem.getQuantity()).thenReturn(1);
        
        Item targetItem = mock(Item.class);
        when(targetItem.getId()).thenReturn(targetId);
        when(targetItem.getQuantity()).thenReturn(1);
        
        when(inventoryState.getNonEmptyItems()).thenReturn(Arrays.asList(sourceItem, targetItem));
    }
}

