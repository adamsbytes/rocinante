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
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for UseItemOnObjectTask.
 * Tests the two-step interaction sequence, item/object resolution, and edge cases.
 */
public class UseItemOnObjectTaskTest {


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
    private Scene scene;

    @Mock
    private Canvas canvas;

    @Mock
    private NavigationService navigationService;

    private TaskContext taskContext;
    private WorldPoint playerPos;
    private PlayerState playerState;

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
        when(client.getScene()).thenReturn(scene);
        when(client.getCanvas()).thenReturn(canvas);
        when(canvas.getSize()).thenReturn(new Dimension(800, 600));
        when(client.getPlane()).thenReturn(0);
        
        // Mock WorldView for LocalPoint.fromWorld to work
        WorldView mockWorldView = mock(WorldView.class);
        when(mockWorldView.getPlane()).thenReturn(0);
        when(client.findWorldViewFromWorldPoint(any(WorldPoint.class))).thenReturn(mockWorldView);

        // Empty scene by default
        Tile[][][] tiles = new Tile[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        when(scene.getTiles()).thenReturn(tiles);

        // InventoryClickHelper default
        when(inventoryClickHelper.executeClick(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // SafeClickExecutor default - handle nullable params
        when(safeClickExecutor.clickObject(any(TileObject.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(safeClickExecutor.clickObject(any(TileObject.class), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // Randomization defaults
        when(randomization.chance(anyDouble())).thenReturn(false);
        when(randomization.uniformRandomInt(anyInt(), anyInt())).thenReturn(200);

        // Empty inventory by default
        when(inventoryState.hasItem(anyInt())).thenReturn(false);
        when(inventoryState.getSlotOf(anyInt())).thenReturn(-1);
        when(inventoryState.getNonEmptyItems()).thenReturn(Collections.emptyList());

        // NavigationService: by default return empty (no reachable object)
        // Individual tests can use setupNavigationForObject() to make objects reachable
        when(navigationService.findNearestReachableObject(any(), any(), anySet(), anyInt()))
                .thenReturn(java.util.Optional.empty());
    }

    /**
     * Helper to make NavigationService return a specific object as reachable.
     */
    private void setupNavigationForObject(TileObject obj, WorldPoint objPos, int pathCost) {
        EntityFinder.ObjectSearchResult result = new EntityFinder.ObjectSearchResult(obj, Collections.emptyList(), objPos, pathCost);
        when(navigationService.findNearestReachableObject(any(), any(), anySet(), anyInt()))
                .thenReturn(java.util.Optional.of(result));
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Test
    public void testConstructor_SingleItemSingleObject() {
        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);

        assertEquals(1, task.getItemIds().size());
        assertEquals(ItemID.RAW_SHRIMPS, (int) task.getItemIds().get(0));
        assertEquals(1, task.getObjectIds().size());
        assertEquals(ObjectID.FIRE_26185, (int) task.getObjectIds().get(0));
    }

    @Test
    public void testConstructor_MultipleItemsSingleObject() {
        List<Integer> items = Arrays.asList(ItemID.RAW_SHRIMPS, 321, 323); // Multiple raw fish
        UseItemOnObjectTask task = new UseItemOnObjectTask(items, ObjectID.FIRE_26185);

        assertEquals(3, task.getItemIds().size());
        assertEquals(1, task.getObjectIds().size());
    }

    @Test
    public void testConstructor_SingleItemMultipleObjects() {
        List<Integer> objects = Arrays.asList(ObjectID.FIRE_26185, 26186, 26187); // Multiple fires
        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, objects);

        assertEquals(1, task.getItemIds().size());
        assertEquals(3, task.getObjectIds().size());
    }

    @Test
    public void testConstructor_MultipleItemsMultipleObjects() {
        List<Integer> items = Arrays.asList(ItemID.RAW_SHRIMPS, 321);
        List<Integer> objects = Arrays.asList(ObjectID.FIRE_26185, 26186);
        UseItemOnObjectTask task = new UseItemOnObjectTask(items, objects);

        assertEquals(2, task.getItemIds().size());
        assertEquals(2, task.getObjectIds().size());
    }

    // ========================================================================
    // Builder Method Tests
    // ========================================================================

    @Test
    public void testBuilderMethods() {
        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.TINDERBOX, ItemID.LOGS)
                .withSearchRadius(20)
                .withDescription("Light the logs");

        assertEquals(20, task.getSearchRadius());
        assertEquals("Light the logs", task.getDescription());
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_NotLoggedIn_ReturnsFalse() {
        when(taskContext.isLoggedIn()).thenReturn(false);

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_ItemNotInInventory_ReturnsFalse() {
        when(inventoryState.hasItem(ItemID.RAW_SHRIMPS)).thenReturn(false);

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_ItemInInventory_ReturnsTrue() {
        when(inventoryState.hasItem(ItemID.RAW_SHRIMPS)).thenReturn(true);

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_MultipleItems_FirstMatchReturnsTrue() {
        List<Integer> items = Arrays.asList(ItemID.RAW_SHRIMPS, 321, 323);
        
        // Has second item
        when(inventoryState.hasItem(ItemID.RAW_SHRIMPS)).thenReturn(false);
        when(inventoryState.hasItem(321)).thenReturn(true);

        UseItemOnObjectTask task = new UseItemOnObjectTask(items, ObjectID.FIRE_26185);

        assertTrue(task.canExecute(taskContext));
    }

    // ========================================================================
    // Phase: CLICK_ITEM Tests
    // ========================================================================

    @Test
    public void testClickItem_UsesInventoryClickHelper() {
        setupInventoryWithItem(ItemID.RAW_SHRIMPS, 5);

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);
        task.canExecute(taskContext);
        task.execute(taskContext);

        verify(inventoryClickHelper).executeClick(eq(5), contains("Use item"));
    }

    @Test
    public void testClickItem_NoHelper_Fails() {
        when(taskContext.getInventoryClickHelper()).thenReturn(null);
        setupInventoryWithItem(ItemID.RAW_SHRIMPS, 5);

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);
        task.canExecute(taskContext);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testClickItem_ItemDisappeared_Fails() {
        when(inventoryState.hasItem(ItemID.RAW_SHRIMPS)).thenReturn(true);
        when(inventoryState.getSlotOf(ItemID.RAW_SHRIMPS)).thenReturn(-1); // Item disappeared

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);
        task.canExecute(taskContext);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testClickItem_ClickFails_FailsTask() {
        setupInventoryWithItem(ItemID.RAW_SHRIMPS, 5);
        when(inventoryClickHelper.executeClick(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(false));

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);
        task.canExecute(taskContext);
        task.execute(taskContext);

        // Wait for async
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Phase: FIND_OBJECT Tests
    // ========================================================================

    @Test
    public void testFindObject_ObjectFound_TransitionsToClick() {
        setupInventoryWithItem(ItemID.RAW_SHRIMPS, 5);
        setupObjectInScene(ObjectID.FIRE_26185, new WorldPoint(3202, 3200, 0));

        // Set animation state so task completes after finding object
        PlayerState animatingState = TaskTestHelper.playerStateWithAnimation(896, playerPos);
        when(taskContext.getPlayerState()).thenReturn(animatingState);
        when(gameStateService.getPlayerState()).thenReturn(animatingState);

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);
        task.canExecute(taskContext);

        // Execute to completion - if object found, task progresses
        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 20);

        assertEquals(TaskState.COMPLETED, result);
    }

    @Test
    public void testFindObject_ObjectNotFound_Fails() {
        setupInventoryWithItem(ItemID.RAW_SHRIMPS, 5);
        // No object in scene

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);
        task.canExecute(taskContext);

        // Execute CLICK_ITEM
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Execute FIND_OBJECT
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindObject_MultipleObjects_FindsFirst() {
        setupInventoryWithItem(ItemID.RAW_SHRIMPS, 5);
        
        // Setup two fires at different distances
        Tile[][][] tiles = new Tile[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        
        int sceneX1 = 3201 - 3200 + 52;
        int sceneY1 = 3200 - 3200 + 52;
        tiles[0][sceneX1][sceneY1] = createTileWithObject(ObjectID.FIRE_26185, new WorldPoint(3201, 3200, 0));
        
        int sceneX2 = 3205 - 3200 + 52;
        int sceneY2 = 3200 - 3200 + 52;
        tiles[0][sceneX2][sceneY2] = createTileWithObject(26186, new WorldPoint(3205, 3200, 0));
        
        when(scene.getTiles()).thenReturn(tiles);

        // Set animation state so task completes successfully
        PlayerState animatingState = TaskTestHelper.playerStateWithAnimation(896, playerPos);
        when(taskContext.getPlayerState()).thenReturn(animatingState);
        when(gameStateService.getPlayerState()).thenReturn(animatingState);

        List<Integer> objects = Arrays.asList(ObjectID.FIRE_26185, 26186);
        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, objects);
        task.canExecute(taskContext);

        // Execute to completion - should find the closer fire
        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 20);

        assertEquals(TaskState.COMPLETED, result);
    }

    // ========================================================================
    // Phase: CLICK_OBJECT Tests
    // ========================================================================

    @Test
    public void testClickObject_UsesSafeClickExecutor() {
        setupInventoryWithItem(ItemID.RAW_SHRIMPS, 5);
        setupObjectInScene(ObjectID.FIRE_26185, new WorldPoint(3202, 3200, 0));
        
        // Mock item definition for menu matching
        ItemComposition itemDef = mock(ItemComposition.class);
        when(itemDef.getName()).thenReturn("Raw shrimps");
        when(client.getItemDefinition(ItemID.RAW_SHRIMPS)).thenReturn(itemDef);

        // Set animation state so task completes successfully
        PlayerState animatingState = TaskTestHelper.playerStateWithAnimation(896, playerPos);
        when(taskContext.getPlayerState()).thenReturn(animatingState);
        when(gameStateService.getPlayerState()).thenReturn(animatingState);

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);
        task.canExecute(taskContext);

        TaskTestHelper.advanceToCompletion(task, taskContext, 15);

        verify(safeClickExecutor, atLeastOnce()).clickObject(any(TileObject.class), eq("Use"), eq("Raw shrimps"));
    }

    @Test
    public void testClickObject_ObjectDespawned_Fails() {
        setupInventoryWithItem(ItemID.RAW_SHRIMPS, 5);
        setupObjectInScene(ObjectID.FIRE_26185, new WorldPoint(3202, 3200, 0));

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);
        task.canExecute(taskContext);

        // Execute CLICK_ITEM
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Clear scene (object despawns)
        Tile[][][] emptyTiles = new Tile[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        when(scene.getTiles()).thenReturn(emptyTiles);

        // Also clear NavigationService result (object is no longer reachable)
        when(navigationService.findNearestReachableObject(any(), any(), anySet(), anyInt()))
                .thenReturn(java.util.Optional.empty());

        // Execute FIND_OBJECT
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Phase: WAIT_RESPONSE Tests
    // ========================================================================

    @Test
    public void testWaitResponse_Animation_Completes() {
        setupInventoryWithItem(ItemID.RAW_SHRIMPS, 5);
        setupObjectInScene(ObjectID.FIRE_26185, new WorldPoint(3202, 3200, 0));

        // Player starts cooking animation - set BEFORE execution
        PlayerState cookingState = TaskTestHelper.playerStateWithAnimation(AnimationID.COOKING_RANGE, playerPos);
        when(taskContext.getPlayerState()).thenReturn(cookingState);
        when(gameStateService.getPlayerState()).thenReturn(cookingState);

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);
        task.canExecute(taskContext);

        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 20);

        assertEquals(TaskState.COMPLETED, result);
    }

    @Test
    public void testWaitResponse_PositionChange_Completes() {
        setupInventoryWithItem(ItemID.BUCKET, 5);
        setupObjectInScene(ObjectID.WELL_884, new WorldPoint(3202, 3200, 0));

        // Use counter to change position mid-execution
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

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.BUCKET, ObjectID.WELL_884);
        task.canExecute(taskContext);

        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 20);

        assertEquals(TaskState.COMPLETED, result);
    }

    @Test
    public void testWaitResponse_Timeout_Fails() {
        setupInventoryWithItem(ItemID.RAW_SHRIMPS, 5);
        setupObjectInScene(ObjectID.FIRE_26185, new WorldPoint(3202, 3200, 0));

        // Keep player idle (no success indicators) throughout execution
        PlayerState idleState = TaskTestHelper.idlePlayerState(playerPos);
        when(taskContext.getPlayerState()).thenReturn(idleState);
        when(gameStateService.getPlayerState()).thenReturn(idleState);

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);
        task.canExecute(taskContext);

        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 30);

        assertEquals(TaskState.FAILED, result);
        assertTrue(task.getFailureReason().contains("timeout"));
    }

    // ========================================================================
    // Sequence Tests (Real Game Scenarios)
    // ========================================================================

    @Test
    public void testCookingSequence_RawShrimpOnFire() {
        setupInventoryWithItem(ItemID.RAW_SHRIMPS, 3);
        setupObjectInScene(ObjectID.FIRE_26185, new WorldPoint(3202, 3200, 0));
        
        ItemComposition itemDef = mock(ItemComposition.class);
        when(itemDef.getName()).thenReturn("Raw shrimps");
        when(client.getItemDefinition(ItemID.RAW_SHRIMPS)).thenReturn(itemDef);

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185)
                .withDescription("Cook shrimp");

        assertTrue(task.canExecute(taskContext));
        
        // Execute through click item phase
        task.execute(taskContext);
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Should have clicked inventory
        verify(inventoryClickHelper).executeClick(eq(3), anyString());
    }

    @Test
    public void testFiremakingSequence_TinderboxOnLogs() {
        setupInventoryWithItem(ItemID.TINDERBOX, 0);
        setupObjectInScene(ItemID.LOGS, new WorldPoint(3200, 3201, 0));
        
        ItemComposition itemDef = mock(ItemComposition.class);
        when(itemDef.getName()).thenReturn("Tinderbox");
        when(client.getItemDefinition(ItemID.TINDERBOX)).thenReturn(itemDef);

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.TINDERBOX, ItemID.LOGS);

        assertTrue(task.canExecute(taskContext));
        
        // Execute through phases
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(20); } catch (InterruptedException e) {}
        }

        verify(inventoryClickHelper).executeClick(anyInt(), anyString());
    }

    // ========================================================================
    // Reset Tests
    // ========================================================================

    @Test
    public void testReset_ClearsState() {
        setupInventoryWithItem(ItemID.RAW_SHRIMPS, 5);
        setupObjectInScene(ObjectID.FIRE_26185, new WorldPoint(3202, 3200, 0));

        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);
        task.canExecute(taskContext);
        task.execute(taskContext);

        task.resetForRetry();

        assertEquals(TaskState.PENDING, task.getState());
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_SingleItemObject() {
        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185);
        String desc = task.getDescription();

        assertTrue(desc.contains(String.valueOf(ItemID.RAW_SHRIMPS)));
        assertTrue(desc.contains(String.valueOf(ObjectID.FIRE_26185)));
    }

    @Test
    public void testGetDescription_Custom() {
        UseItemOnObjectTask task = new UseItemOnObjectTask(ItemID.RAW_SHRIMPS, ObjectID.FIRE_26185)
                .withDescription("Cook some food");

        assertEquals("Cook some food", task.getDescription());
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

    private void setupObjectInScene(int objectId, WorldPoint pos) {
        Tile[][][] tiles = new Tile[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        
        int sceneX = pos.getX() - 3200 + 52;
        int sceneY = pos.getY() - 3200 + 52;
        
        if (sceneX >= 0 && sceneX < Constants.SCENE_SIZE &&
            sceneY >= 0 && sceneY < Constants.SCENE_SIZE) {
            tiles[pos.getPlane()][sceneX][sceneY] = createTileWithObject(objectId, pos);
        }
        
        when(scene.getTiles()).thenReturn(tiles);
    }

    private Tile createTileWithObject(int objectId, WorldPoint pos) {
        Tile tile = mock(Tile.class);
        GameObject obj = mock(GameObject.class);
        when(obj.getId()).thenReturn(objectId);
        when(obj.getWorldLocation()).thenReturn(pos);
        
        Shape clickbox = new Rectangle(100, 100, 50, 50);
        when(obj.getClickbox()).thenReturn(clickbox);
        
        when(tile.getGameObjects()).thenReturn(new GameObject[]{obj});
        when(tile.getWallObject()).thenReturn(null);
        when(tile.getDecorativeObject()).thenReturn(null);
        when(tile.getGroundObject()).thenReturn(null);

        // Make this object reachable via NavigationService
        setupNavigationForObject(obj, pos, playerPos.distanceTo(pos) + 1);
        
        return tile;
    }
}

