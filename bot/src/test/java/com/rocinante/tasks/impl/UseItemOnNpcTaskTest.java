package com.rocinante.tasks.impl;

import com.rocinante.core.GameStateService;
import com.rocinante.input.InventoryClickHelper;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.input.SafeClickExecutor;
import com.rocinante.navigation.EntityFinder;
import com.rocinante.navigation.NavigationService;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.TaskTestHelper;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.WorldView;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for UseItemOnNpcTask.
 * Tests the two-step interaction sequence with NPC movement tracking.
 */
public class UseItemOnNpcTaskTest {

    // Widget IDs for dialogue detection (no RuneLite constants)
    private static final int WIDGET_NPC_DIALOGUE = 231;
    private static final int WIDGET_PLAYER_DIALOGUE = 217;

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
    private SafeClickExecutor safeClickExecutor;

    @Mock
    private InventoryState inventoryState;

    @Mock
    private Canvas canvas;

    @Mock
    private NavigationService navigationService;

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
                .animationId(-1)
                .isMoving(false)
                .isInteracting(false)
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
        when(taskContext.getInventoryClickHelper()).thenReturn(inventoryClickHelper);
        when(taskContext.getSafeClickExecutor()).thenReturn(safeClickExecutor);
        when(taskContext.isLoggedIn()).thenReturn(true);
        when(taskContext.getPlayerState()).thenReturn(playerState);
        when(taskContext.getInventoryState()).thenReturn(inventoryState);
        when(taskContext.getNavigationService()).thenReturn(navigationService);

        // GameStateService
        when(gameStateService.isLoggedIn()).thenReturn(true);
        when(gameStateService.getPlayerState()).thenReturn(playerState);

        // Client mocks
        when(client.getNpcs()).thenReturn(npcList);
        when(client.getCanvas()).thenReturn(canvas);
        when(canvas.getSize()).thenReturn(new Dimension(800, 600));
        when(client.getPlane()).thenReturn(0);
        
        // Mock WorldView for LocalPoint.fromWorld to work
        WorldView mockWorldView = mock(WorldView.class);
        when(mockWorldView.getPlane()).thenReturn(0);
        when(client.findWorldViewFromWorldPoint(any(WorldPoint.class))).thenReturn(mockWorldView);

        // InventoryClickHelper default
        when(inventoryClickHelper.executeClick(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // SafeClickExecutor default - handle both nullable and non-nullable third arg
        when(safeClickExecutor.clickNpc(any(NPC.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(safeClickExecutor.clickNpc(any(NPC.class), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // MouseController default
        when(mouseController.moveToCanvas(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Randomization defaults
        when(randomization.chance(anyDouble())).thenReturn(false);
        when(randomization.uniformRandomInt(anyInt(), anyInt())).thenReturn(200);

        // Empty inventory by default
        when(inventoryState.hasItem(anyInt())).thenReturn(false);
        when(inventoryState.getSlotOf(anyInt())).thenReturn(-1);
        when(inventoryState.getNonEmptyItems()).thenReturn(Collections.emptyList());

        // No dialogue by default
        when(client.getWidget(anyInt(), anyInt())).thenReturn(null);

        // NavigationService: by default return empty (no reachable NPC)
        // Individual tests can use setupNavigationForNpc() to make NPCs reachable
        when(navigationService.findNearestReachableNpc(any(), any(), anySet(), anyInt()))
                .thenReturn(java.util.Optional.empty());
    }

    /**
     * Helper to make NavigationService return a specific NPC as reachable.
     */
    private void setupNavigationForNpc(NPC npc, int pathCost) {
        EntityFinder.NpcSearchResult result = new EntityFinder.NpcSearchResult(npc, Collections.emptyList(), null, pathCost);
        when(navigationService.findNearestReachableNpc(any(), any(), anySet(), anyInt()))
                .thenReturn(java.util.Optional.of(result));
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Test
    public void testConstructor_SingleItemSingleNpc() {
        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);

        assertEquals(1, task.getItemIds().size());
        assertEquals(ItemID.BONES, (int) task.getItemIds().get(0));
        assertEquals(NpcID.WOMAN_3299, task.getNpcId());
        assertTrue(task.getAlternateNpcIds().isEmpty());
    }

    @Test
    public void testConstructor_MultipleItemsSingleNpc() {
        List<Integer> items = Arrays.asList(ItemID.BONES, 528, 530); // Different bone types
        UseItemOnNpcTask task = new UseItemOnNpcTask(items, NpcID.WOMAN_3299);

        assertEquals(3, task.getItemIds().size());
        assertEquals(NpcID.WOMAN_3299, task.getNpcId());
    }

    @Test
    public void testConstructor_MultipleItemsMultipleNpcs() {
        List<Integer> items = Arrays.asList(ItemID.BONES, 528);
        List<Integer> npcs = Arrays.asList(NpcID.WOMAN_3299, 3300, 3301);
        UseItemOnNpcTask task = new UseItemOnNpcTask(items, npcs);

        assertEquals(2, task.getItemIds().size());
        assertEquals(NpcID.WOMAN_3299, task.getNpcId());
        assertEquals(2, task.getAlternateNpcIds().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_EmptyNpcList_Throws() {
        new UseItemOnNpcTask(Arrays.asList(ItemID.BONES), Collections.emptyList());
    }

    // ========================================================================
    // Builder Method Tests
    // ========================================================================

    @Test
    public void testBuilderMethods() {
        UseItemOnNpcTask task = new UseItemOnNpcTask(1234, 4567)
                .withSearchRadius(20)
                .withNpcName("Quest NPC")
                .withDialogueExpected(true)
                .withTrackMovement(true)
                .withDescription("Give quest item to NPC");

        assertEquals(20, task.getSearchRadius());
        assertEquals("Quest NPC", task.getNpcName());
        assertTrue(task.isDialogueExpected());
        assertTrue(task.isTrackMovement());
        assertEquals("Give quest item to NPC", task.getDescription());
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_NotLoggedIn_ReturnsFalse() {
        when(taskContext.isLoggedIn()).thenReturn(false);

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_ItemNotInInventory_ReturnsFalse() {
        when(inventoryState.hasItem(ItemID.BONES)).thenReturn(false);

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_ItemInInventory_ReturnsTrue() {
        when(inventoryState.hasItem(ItemID.BONES)).thenReturn(true);

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_MultipleItems_FirstMatchReturnsTrue() {
        List<Integer> items = Arrays.asList(ItemID.BONES, 528, 530);
        
        // Has second item
        when(inventoryState.hasItem(ItemID.BONES)).thenReturn(false);
        when(inventoryState.hasItem(528)).thenReturn(true);

        UseItemOnNpcTask task = new UseItemOnNpcTask(items, NpcID.WOMAN_3299);

        assertTrue(task.canExecute(taskContext));
    }

    // ========================================================================
    // Phase: CLICK_ITEM Tests
    // ========================================================================

    @Test
    public void testClickItem_UsesInventoryClickHelper() {
        setupInventoryWithItem(ItemID.BONES, 5);

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);
        task.canExecute(taskContext);
        task.execute(taskContext);

        verify(inventoryClickHelper).executeClick(eq(5), contains("Use item"));
    }

    @Test
    public void testClickItem_NoHelper_Fails() {
        when(taskContext.getInventoryClickHelper()).thenReturn(null);
        setupInventoryWithItem(ItemID.BONES, 5);

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);
        task.canExecute(taskContext);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testClickItem_ItemConsumedDuringInteraction_Fails() {
        when(inventoryState.hasItem(ItemID.BONES)).thenReturn(true);
        when(inventoryState.getSlotOf(ItemID.BONES)).thenReturn(-1); // Item disappeared

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);
        task.canExecute(taskContext);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Phase: FIND_NPC Tests
    // ========================================================================

    @Test
    public void testFindNpc_NpcFound_TransitionsToMove() {
        setupInventoryWithItem(ItemID.BONES, 5);
        addNpcToScene(NpcID.WOMAN_3299, "Ectofuntus", new WorldPoint(3202, 3200, 0));

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);
        task.canExecute(taskContext);

        // Execute CLICK_ITEM
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Execute FIND_NPC
        task.execute(taskContext);

        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindNpc_NpcNotFound_Fails() {
        setupInventoryWithItem(ItemID.BONES, 5);
        // No NPC in scene

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);
        task.canExecute(taskContext);

        // Execute CLICK_ITEM
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Execute FIND_NPC
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindNpc_AlternateIdFound() {
        setupInventoryWithItem(ItemID.BONES, 5);
        
        List<Integer> npcs = Arrays.asList(NpcID.WOMAN_3299, 3300);
        addNpcToScene(3300, "Altar", new WorldPoint(3202, 3200, 0)); // Alternate ID

        UseItemOnNpcTask task = new UseItemOnNpcTask(Arrays.asList(ItemID.BONES), npcs);
        task.canExecute(taskContext);

        // Execute CLICK_ITEM
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Execute FIND_NPC
        task.execute(taskContext);

        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindNpc_WithNameFilter_MatchesCorrectNpc() {
        setupInventoryWithItem(ItemID.BONES, 5);
        
        // Two NPCs with same ID but different names
        addNpcToScene(NpcID.WOMAN_3299, "Wrong NPC", new WorldPoint(3201, 3200, 0));
        addNpcToScene(NpcID.WOMAN_3299, "Ectofuntus", new WorldPoint(3205, 3200, 0));

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299)
                .withNpcName("Ectofuntus");
        task.canExecute(taskContext);

        // Execute through phases
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);

        // Should find the farther NPC with matching name
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindNpc_DeadNpcSkipped() {
        setupInventoryWithItem(ItemID.BONES, 5);
        
        NPC deadNpc = TaskTestHelper.mockNpc(NpcID.WOMAN_3299, "Ectofuntus", new WorldPoint(3201, 3200, 0));
        when(deadNpc.isDead()).thenReturn(true);
        npcList.add(deadNpc);
        
        // Add a living one farther away
        addNpcToScene(NpcID.WOMAN_3299, "Ectofuntus", new WorldPoint(3205, 3200, 0));

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);
        task.canExecute(taskContext);

        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);

        assertNotEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // NPC Movement Tracking Tests
    // ========================================================================

    @Test
    public void testNpcMovement_SmallMovement_ContinuesInteraction() {
        setupInventoryWithItem(ItemID.BONES, 5);
        NPC npc = addNpcToScene(NpcID.WOMAN_3299, "Ectofuntus", new WorldPoint(3202, 3200, 0));

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299)
                .withTrackMovement(true);
        task.canExecute(taskContext);

        // Execute to FIND_NPC
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);

        // NPC moves 2 tiles
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3204, 3200, 0));

        // Continue execution
        for (int i = 0; i < 5; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(20); } catch (InterruptedException e) {}
        }

        // Should not fail due to movement
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testNpcMovement_DuringClickSequence_RetargetsMouse() {
        setupInventoryWithItem(ItemID.BONES, 5);
        NPC npc = addNpcToScene(NpcID.WOMAN_3299, "Ectofuntus", new WorldPoint(3202, 3200, 0));

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299)
                .withTrackMovement(true);
        task.canExecute(taskContext);

        // Execute through phases
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(20); } catch (InterruptedException e) {}
        }

        // Should have tracked the NPC
        verify(mouseController, atLeastOnce()).moveToCanvas(anyInt(), anyInt());
    }

    @Test
    public void testNpcMovement_TrackingDisabled_IgnoresMovement() {
        setupInventoryWithItem(ItemID.BONES, 5);
        NPC npc = addNpcToScene(NpcID.WOMAN_3299, "Ectofuntus", new WorldPoint(3202, 3200, 0));

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299)
                .withTrackMovement(false);
        task.canExecute(taskContext);

        // Execute to FIND_NPC
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);

        // NPC moves significantly
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3210, 3200, 0));

        // Should not re-track
        for (int i = 0; i < 5; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(20); } catch (InterruptedException e) {}
        }
    }

    // ========================================================================
    // NPC Death/Despawn Tests
    // ========================================================================

    @Test
    public void testNpcDeath_DuringInteraction_TriggersRetarget() {
        setupInventoryWithItem(ItemID.BONES, 5);
        NPC npc = addNpcToScene(NpcID.WOMAN_3299, "Ectofuntus", new WorldPoint(3202, 3200, 0));

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);
        task.canExecute(taskContext);

        // Execute through phases
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);

        // NPC dies, add another
        when(npc.isDead()).thenReturn(true);
        addNpcToScene(NpcID.WOMAN_3299, "Ectofuntus", new WorldPoint(3205, 3205, 0));

        // Continue - should attempt retarget
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(20); } catch (InterruptedException e) {}
        }
    }

    @Test
    public void testNpcDeath_MaxRetargetsExceeded_Fails() {
        setupInventoryWithItem(ItemID.BONES, 5);
        NPC npc = addNpcToScene(NpcID.WOMAN_3299, "Ectofuntus", new WorldPoint(3202, 3200, 0));

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);
        task.canExecute(taskContext);

        // Execute through phases
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);

        // NPC dies, no replacement
        when(npc.isDead()).thenReturn(true);
        npcList.clear();

        // Execute until failure
        for (int i = 0; i < 30; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(20); } catch (InterruptedException e) {}
        }

        assertEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Success Detection Tests
    // ========================================================================

    @Test
    public void testSuccess_DialogueOpened() {
        setupInventoryWithItem(1234, 5);
        addNpcToScene(4567, "Quest NPC", new WorldPoint(3202, 3200, 0));

        // Mock dialogue appearing - set BEFORE execution
        Widget dialogueWidget = mock(Widget.class);
        when(dialogueWidget.isHidden()).thenReturn(false);
        when(client.getWidget(WIDGET_NPC_DIALOGUE, 0)).thenReturn(dialogueWidget);

        UseItemOnNpcTask task = new UseItemOnNpcTask(1234, 4567)
                .withDialogueExpected(true);
        task.canExecute(taskContext);

        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 25);

        assertEquals(TaskState.COMPLETED, result);
    }

    @Test
    public void testSuccess_Animation() {
        setupInventoryWithItem(ItemID.BONES, 5);
        addNpcToScene(NpcID.WOMAN_3299, "Ectofuntus", new WorldPoint(3202, 3200, 0));

        // Player starts animation - set BEFORE execution
        PlayerState animatingState = TaskTestHelper.playerStateWithAnimation(896, playerPos);
        when(taskContext.getPlayerState()).thenReturn(animatingState);

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);
        task.canExecute(taskContext);

        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 25);

        assertEquals(TaskState.COMPLETED, result);
    }

    @Test
    public void testSuccess_PositionChange() {
        setupInventoryWithItem(ItemID.BONES, 5);
        addNpcToScene(NpcID.WOMAN_3299, "Ectofuntus", new WorldPoint(3202, 3200, 0));

        // Use a counter to change position mid-execution
        final AtomicInteger execCount = new AtomicInteger(0);
        final WorldPoint originalPos = playerPos;
        final WorldPoint movedPos = new WorldPoint(3201, 3200, 0);
        
        when(taskContext.getPlayerState()).thenAnswer(inv -> {
            if (execCount.incrementAndGet() > 5) {
                return PlayerState.builder()
                        .worldPosition(movedPos)
                        .animationId(-1)
                        .build();
            }
            return TaskTestHelper.idlePlayerState(originalPos);
        });

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);
        task.canExecute(taskContext);

        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 25);

        assertEquals(TaskState.COMPLETED, result);
    }

    @Test
    public void testTimeout_NoSuccessIndicators_Fails() {
        setupInventoryWithItem(ItemID.BONES, 5);
        addNpcToScene(NpcID.WOMAN_3299, "Ectofuntus", new WorldPoint(3202, 3200, 0));

        // Keep player idle with same position throughout
        PlayerState idleState = TaskTestHelper.idlePlayerState(playerPos);
        when(taskContext.getPlayerState()).thenReturn(idleState);

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);
        task.canExecute(taskContext);

        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 30);

        assertEquals(TaskState.FAILED, result);
        assertTrue(task.getFailureReason().contains("timeout"));
    }

    // ========================================================================
    // Reset Tests
    // ========================================================================

    @Test
    public void testReset_ClearsState() {
        setupInventoryWithItem(ItemID.BONES, 5);
        addNpcToScene(NpcID.WOMAN_3299, "Ectofuntus", new WorldPoint(3202, 3200, 0));

        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);
        task.canExecute(taskContext);
        task.execute(taskContext);

        task.resetForRetry();

        assertEquals(TaskState.PENDING, task.getState());
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_SingleItem() {
        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299);
        String desc = task.getDescription();

        assertTrue(desc.contains(String.valueOf(ItemID.BONES)));
        assertTrue(desc.contains(String.valueOf(NpcID.WOMAN_3299)));
    }

    @Test
    public void testGetDescription_Custom() {
        UseItemOnNpcTask task = new UseItemOnNpcTask(ItemID.BONES, NpcID.WOMAN_3299)
                .withDescription("Offer bones at altar");

        assertEquals("Offer bones at altar", task.getDescription());
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void setupInventoryWithItem(int itemId, int slot) {
        when(inventoryState.hasItem(itemId)).thenReturn(true);
        when(inventoryState.getSlotOf(itemId)).thenReturn(slot);
        
        Item item = mock(Item.class);
        when(item.getId()).thenReturn(itemId);
        when(item.getQuantity()).thenReturn(1);
        when(inventoryState.getNonEmptyItems()).thenReturn(Collections.singletonList(item));
    }

    private NPC addNpcToScene(int npcId, String name, WorldPoint pos) {
        NPC npc = TaskTestHelper.mockNpc(npcId, name, pos);
        npcList.add(npc);
        // Make this NPC reachable via NavigationService
        setupNavigationForNpc(npc, playerPos.distanceTo(pos) + 1);
        return npc;
    }
}

