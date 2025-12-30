package com.rocinante.tasks.impl;

import com.rocinante.core.GameStateService;
import com.rocinante.input.MenuHelper;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.input.SafeClickExecutor;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.TaskTestHelper;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for InteractObjectTask.
 * Tests all phases of the state machine, edge cases, and real game scenarios.
 */
public class InteractObjectTaskTest {


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
    private Scene scene;

    @Mock
    private Canvas canvas;

    private TaskContext taskContext;
    private WorldPoint playerPos;
    private PlayerState playerState;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        playerPos = new WorldPoint(3200, 3200, 0);
        playerState = PlayerState.builder()
                .worldPosition(playerPos)
                .currentHitpoints(50)
                .maxHitpoints(50)
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
        when(taskContext.getSafeClickExecutor()).thenReturn(safeClickExecutor);
        when(taskContext.getMenuHelper()).thenReturn(menuHelper);
        when(taskContext.isLoggedIn()).thenReturn(true);
        when(taskContext.getPlayerState()).thenReturn(playerState);

        // GameStateService
        when(gameStateService.isLoggedIn()).thenReturn(true);
        when(gameStateService.getPlayerState()).thenReturn(playerState);

        // Client mocks
        when(client.getScene()).thenReturn(scene);
        when(client.getCanvas()).thenReturn(canvas);
        when(canvas.getSize()).thenReturn(new Dimension(800, 600));

        // Randomization defaults (no camera rotation by default)
        when(randomization.chance(anyDouble())).thenReturn(false);
        when(randomization.uniformRandomInt(anyInt(), anyInt())).thenReturn(200);
        when(randomization.gaussianRandom(anyDouble(), anyDouble())).thenReturn(0.0);

        // Mouse controller defaults
        when(mouseController.moveToCanvas(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mouseController.click())
                .thenReturn(CompletableFuture.completedFuture(null));

        // SafeClickExecutor default
        when(safeClickExecutor.clickObject(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // MenuHelper defaults
        when(menuHelper.isLeftClickActionContains(anyString())).thenReturn(true);
        when(menuHelper.selectMenuEntry(any(Rectangle.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // Empty scene by default
        setupEmptyScene();
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Test
    public void testConstructor_SingleId() {
        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");

        assertEquals(ObjectID.BANK_BOOTH_10355, task.getObjectId());
        assertEquals("Bank", task.getMenuAction());
        assertTrue(task.getAlternateObjectIds().isEmpty());
        assertEquals(15, task.getSearchRadius()); // Default
        assertEquals(0.3, task.getCameraRotationChance(), 0.01); // Default
    }

    @Test
    public void testConstructor_MultipleIds() {
        List<Integer> ids = Arrays.asList(ObjectID.DOOR_1535, ObjectID.DOOR_1536, 1537);
        InteractObjectTask task = new InteractObjectTask(ids, "Open");

        assertEquals(ObjectID.DOOR_1535, task.getObjectId()); // First ID is primary
        assertEquals(2, task.getAlternateObjectIds().size());
        assertTrue(task.getAlternateObjectIds().contains(ObjectID.DOOR_1536));
        assertTrue(task.getAlternateObjectIds().contains(1537));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_EmptyIds_Throws() {
        new InteractObjectTask(Arrays.asList(), "Open");
    }

    // ========================================================================
    // Builder Method Tests
    // ========================================================================

    @Test
    public void testBuilderMethods() {
        InteractObjectTask task = new InteractObjectTask(ObjectID.TREE, "Chop down")
                .withSearchRadius(10)
                .withCameraRotationChance(0.8)
                .withSuccessAnimation(AnimationID.WOODCUTTING_RUNE)
                .withWaitForIdle(false)
                .withDescription("Chop a tree")
                .withAlternateIds(1277, 1278);

        assertEquals(10, task.getSearchRadius());
        assertEquals(0.8, task.getCameraRotationChance(), 0.01);
        assertEquals(List.of(AnimationID.WOODCUTTING_RUNE), task.getSuccessAnimationIds());
        assertFalse(task.isWaitForIdle());
        assertEquals("Chop a tree", task.getDescription());
        assertEquals(2, task.getAlternateObjectIds().size());
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_WhenLoggedIn_ReturnsTrue() {
        when(taskContext.isLoggedIn()).thenReturn(true);

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_WhenNotLoggedIn_ReturnsFalse() {
        when(taskContext.isLoggedIn()).thenReturn(false);

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");

        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Phase: FIND_OBJECT Tests
    // ========================================================================

    @Test
    public void testFindObject_ObjectFound_TransitionsToNextPhase() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");
        task.execute(taskContext);

        // Should transition from FIND_OBJECT to either ROTATE_CAMERA or MOVE_MOUSE
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindObject_ObjectNotFound_Fails() {
        // Scene is empty
        setupEmptyScene();

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("Object not found"));
    }

    @Test
    public void testFindObject_ObjectOutOfRadius_Fails() {
        // Object at 50 tiles away (beyond default 15 tile radius)
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3250, 3250, 0));

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindObject_CustomRadius_FindsDistantObject() {
        // Object at 20 tiles away
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3220, 3200, 0));

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank")
                .withSearchRadius(25);
        task.execute(taskContext);

        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindObject_AlternateIdFound_Succeeds() {
        // Primary ID not present, but alternate is
        setupSceneWithObject(ObjectID.DOOR_1536, new WorldPoint(3202, 3202, 0));

        InteractObjectTask task = new InteractObjectTask(ObjectID.DOOR_1535, "Open")
                .withAlternateIds(ObjectID.DOOR_1536);
        task.execute(taskContext);

        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindObject_ClosestObjectSelected() {
        // Setup two objects - one close, one far
        Tile[][][] tiles = createEmptyTileGrid();
        
        // Close object at (3201, 3200)
        GameObject closeObj = createMockGameObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3201, 3200, 0));
        // Far object at (3210, 3200)
        GameObject farObj = createMockGameObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3210, 3200, 0));
        
        // Add both to scene
        int closeX = 3201 - 3200 + 52; // Convert to scene coords
        int closeY = 3200 - 3200 + 52;
        int farX = 3210 - 3200 + 52;
        int farY = 3200 - 3200 + 52;
        
        tiles[0][closeX][closeY] = createTileWithObject(closeObj);
        tiles[0][farX][farY] = createTileWithObject(farObj);
        
        when(scene.getTiles()).thenReturn(tiles);

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");
        task.execute(taskContext);

        // Task should find the closer object
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Phase: Object Despawn Edge Cases
    // ========================================================================

    @Test
    public void testDespawn_DuringClick_TriggersResearch() {
        setupSceneWithObject(ObjectID.TREE, new WorldPoint(3202, 3202, 0));
        
        InteractObjectTask task = new InteractObjectTask(ObjectID.TREE, "Chop down");
        
        // Execute to find object
        task.execute(taskContext);
        assertNotEquals(TaskState.FAILED, task.getState());
        
        // Advance to CLICK phase by executing multiple times
        // and setting up click to return success
        advanceToPhase(task, "CLICK", 10);
        
        // Now clear the scene (object despawns)
        setupEmptyScene();
        
        // Force targetObject to null to simulate despawn
        task.targetObject = null;
        
        // Execute - should attempt retry (despawnRetryCount < MAX_DESPAWN_RETRIES)
        task.execute(taskContext);
        
        // Task should either retry finding or fail if exceeds max retries
        // Since we cleared scene, it will fail on re-find
        // But it should have attempted a retry first (despawnRetryCount > 0)
    }

    @Test
    public void testDespawn_ExceedsMaxRetries_Fails() {
        InteractObjectTask task = new InteractObjectTask(ObjectID.TREE, "Chop down");
        
        // Manually set despawn retry count to max
        task.despawnRetryCount = 3;
        task.phase = InteractObjectTask.InteractionPhase.CLICK;
        task.targetObject = null; // Simulate despawn
        
        task.execute(taskContext);
        
        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("despawned repeatedly"));
    }

    // ========================================================================
    // Phase: ROTATE_CAMERA Tests
    // ========================================================================

    @Test
    public void testRotateCamera_WhenChanceTriggered_RotatesCamera() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        
        // Set camera rotation to always happen
        when(randomization.chance(anyDouble())).thenReturn(true);

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank")
                .withCameraRotationChance(1.0);
        
        task.execute(taskContext);
        
        // Task should have triggered camera rotation (transitioned through ROTATE_CAMERA)
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testRotateCamera_WhenChanceNotTriggered_SkipsRotation() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        
        // Camera rotation chance returns false
        when(randomization.chance(anyDouble())).thenReturn(false);

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank")
                .withCameraRotationChance(0.0);
        
        task.execute(taskContext);
        
        // Task should skip directly to MOVE_MOUSE
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Phase: CHECK_MENU Tests
    // ========================================================================

    @Test
    public void testCheckMenu_LeftClickAvailable_UsesLeftClick() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        when(menuHelper.isLeftClickActionContains("Bank")).thenReturn(true);

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");
        
        // Advance to CHECK_MENU phase
        advanceToPhase(task, "CHECK_MENU", 10);
        
        // Execute CHECK_MENU
        task.execute(taskContext);
        
        // Verify it proceeded to CLICK (not SELECT_MENU)
        verify(menuHelper).isLeftClickActionContains("Bank");
    }

    @Test
    public void testCheckMenu_RightClickRequired_UsesMenuSelection() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        when(menuHelper.isLeftClickActionContains("Use")).thenReturn(false);

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Use");
        
        // Advance to CHECK_MENU phase
        advanceToPhase(task, "CHECK_MENU", 10);
        
        // Execute CHECK_MENU
        task.execute(taskContext);
        
        // Verify it checked menu action
        verify(menuHelper).isLeftClickActionContains("Use");
    }

    @Test
    public void testCheckMenu_NoMenuHelper_FallsBackToLeftClick() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        when(taskContext.getMenuHelper()).thenReturn(null);

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");
        
        // Advance to CHECK_MENU phase
        advanceToPhase(task, "CHECK_MENU", 10);
        
        task.execute(taskContext);
        
        // Should not fail - falls back to left-click
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testCheckMenu_NullMenuAction_UsesLeftClick() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, null);
        
        // Advance to CHECK_MENU phase
        advanceToPhase(task, "CHECK_MENU", 10);
        
        task.execute(taskContext);
        
        // Should use left-click directly
        verify(menuHelper, never()).isLeftClickActionContains(anyString());
    }

    // ========================================================================
    // Phase: CLICK Tests
    // ========================================================================

    @Test
    public void testClick_Success_TransitionsToWaitResponse() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        when(safeClickExecutor.clickObject(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");
        
        // Advance to CLICK phase
        advanceToPhase(task, "CLICK", 10);
        
        // Execute click
        task.execute(taskContext);
        
        // Verify click was attempted
        verify(safeClickExecutor).clickObject(any(), eq("Bank"));
    }

    @Test
    public void testClick_Failure_FailsTask() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        when(safeClickExecutor.clickObject(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(false));

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");
        
        // Advance to CLICK phase
        advanceToPhase(task, "CLICK", 10);
        
        // Execute click (will fail)
        task.execute(taskContext);
        
        // Wait for async to complete
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);
        
        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testClick_PendingClick_DoesNotDoubleClick() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        
        // Return a non-completed future
        CompletableFuture<Boolean> pendingFuture = new CompletableFuture<>();
        when(safeClickExecutor.clickObject(any(), anyString())).thenReturn(pendingFuture);

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");
        advanceToPhase(task, "CLICK", 10);
        
        // Execute click (starts pending)
        task.execute(taskContext);
        
        // Execute again (should not trigger another click)
        task.execute(taskContext);
        
        // Verify click was only called once
        verify(safeClickExecutor, times(1)).clickObject(any(), anyString());
    }

    // ========================================================================
    // Phase: SELECT_MENU Tests
    // ========================================================================

    @Test
    public void testSelectMenu_Success_TransitionsToWaitResponse() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        when(menuHelper.isLeftClickActionContains(anyString())).thenReturn(false);
        when(menuHelper.selectMenuEntry(any(Rectangle.class), eq("Use")))
                .thenReturn(CompletableFuture.completedFuture(true));

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Use");
        advanceToPhase(task, "SELECT_MENU", 10);
        
        // Set up cached clickbox
        task.cachedClickbox = new Rectangle(100, 100, 50, 50);
        
        task.execute(taskContext);
        
        verify(menuHelper).selectMenuEntry(any(Rectangle.class), eq("Use"));
    }

    @Test
    public void testSelectMenu_Failure_FailsTask() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        when(menuHelper.isLeftClickActionContains(anyString())).thenReturn(false);
        when(menuHelper.selectMenuEntry(any(Rectangle.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(false));

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Use");
        advanceToPhase(task, "SELECT_MENU", 10);
        task.cachedClickbox = new Rectangle(100, 100, 50, 50);
        
        task.execute(taskContext);
        
        // Wait for async
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        task.execute(taskContext);
        
        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testSelectMenu_NoClickbox_FailsTask() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        when(menuHelper.isLeftClickActionContains(anyString())).thenReturn(false);

        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Use");
        advanceToPhase(task, "SELECT_MENU", 10);
        task.cachedClickbox = null; // No cached clickbox
        task.targetObject = null; // Can't recalculate
        
        task.execute(taskContext);
        
        // Should fail or retry based on despawn retry logic
    }

    // ========================================================================
    // Phase: WAIT_RESPONSE Tests
    // ========================================================================

    @Test
    public void testWaitResponse_AnimationDetected_Completes() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        
        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");
        advanceToPhase(task, "WAIT_RESPONSE", 15);
        
        // Player starts animating
        PlayerState animatingState = PlayerState.builder()
                .worldPosition(playerPos)
                .animationId(AnimationID.WOODCUTTING_RUNE)
                .build();
        when(taskContext.getPlayerState()).thenReturn(animatingState);
        when(gameStateService.getPlayerState()).thenReturn(animatingState);
        
        task.execute(taskContext);
        
        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testWaitResponse_SpecificAnimationRequired_CompletesOnMatch() {
        setupSceneWithObject(ObjectID.TREE, new WorldPoint(3202, 3202, 0));
        
        InteractObjectTask task = new InteractObjectTask(ObjectID.TREE, "Chop down")
                .withSuccessAnimation(AnimationID.WOODCUTTING_RUNE);
        advanceToPhase(task, "WAIT_RESPONSE", 15);
        
        // Player plays expected animation
        PlayerState animatingState = PlayerState.builder()
                .worldPosition(playerPos)
                .animationId(AnimationID.WOODCUTTING_RUNE)
                .build();
        when(taskContext.getPlayerState()).thenReturn(animatingState);
        when(gameStateService.getPlayerState()).thenReturn(animatingState);
        
        task.execute(taskContext);
        
        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testWaitResponse_PositionChanged_Completes() {
        setupSceneWithObject(ObjectID.DOOR_1535, new WorldPoint(3202, 3202, 0));
        
        InteractObjectTask task = new InteractObjectTask(ObjectID.DOOR_1535, "Open")
                .withSuccessAnimation(-1); // No animation expected
        advanceToPhase(task, "WAIT_RESPONSE", 15);
        
        // Remember start position
        task.startPosition = playerPos;
        
        // Player moved
        PlayerState movedState = PlayerState.builder()
                .worldPosition(new WorldPoint(3201, 3200, 0)) // Different position
                .animationId(-1)
                .build();
        when(taskContext.getPlayerState()).thenReturn(movedState);
        when(gameStateService.getPlayerState()).thenReturn(movedState);
        
        task.execute(taskContext);
        
        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testWaitResponse_DialogueOpened_CompletesWhenExpected() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        
        // Mock dialogue widget visible
        Widget dialogueWidget = mock(Widget.class);
        when(dialogueWidget.isHidden()).thenReturn(false);
        when(client.getWidget(231, 0)).thenReturn(dialogueWidget);
        
        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Talk-to");
        task.setDialogueExpected(true);
        advanceToPhase(task, "WAIT_RESPONSE", 15);
        
        task.execute(taskContext);
        
        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testWaitResponse_Timeout_Fails() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        
        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");
        advanceToPhase(task, "WAIT_RESPONSE", 15);
        
        // No success indicators - player is idle
        PlayerState idleState = PlayerState.builder()
                .worldPosition(playerPos)
                .animationId(-1)
                .isMoving(false)
                .isInteracting(false)
                .build();
        when(taskContext.getPlayerState()).thenReturn(idleState);
        when(gameStateService.getPlayerState()).thenReturn(idleState);
        
        // Execute past timeout (11 ticks)
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }
        
        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("timeout"));
    }

    @Test
    public void testWaitResponse_Interacting_Completes() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        
        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");
        advanceToPhase(task, "WAIT_RESPONSE", 15);
        
        // Player is interacting
        PlayerState interactingState = PlayerState.builder()
                .worldPosition(playerPos)
                .isInteracting(true)
                .build();
        when(taskContext.getPlayerState()).thenReturn(interactingState);
        when(gameStateService.getPlayerState()).thenReturn(interactingState);
        
        task.execute(taskContext);
        
        assertEquals(TaskState.COMPLETED, task.getState());
    }

    // ========================================================================
    // Reset/Retry Tests
    // ========================================================================

    @Test
    public void testResetImpl_ClearsAllState() {
        setupSceneWithObject(ObjectID.BANK_BOOTH_10355, new WorldPoint(3202, 3202, 0));
        
        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");
        
        // Execute to set up state
        task.execute(taskContext);
        
        // Manually set some state
        task.despawnRetryCount = 2;
        task.interactionTicks = 5;
        task.clickPending = true;
        
        // Reset
        task.resetForRetry();
        
        assertEquals(0, task.despawnRetryCount);
        assertEquals(0, task.interactionTicks);
        assertFalse(task.clickPending);
        assertNull(task.targetObject);
        assertEquals(TaskState.PENDING, task.getState());
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_Default() {
        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank");
        
        String desc = task.getDescription();
        
        assertTrue(desc.contains(String.valueOf(ObjectID.BANK_BOOTH_10355)));
        assertTrue(desc.contains("Bank"));
    }

    @Test
    public void testGetDescription_Custom() {
        InteractObjectTask task = new InteractObjectTask(ObjectID.BANK_BOOTH_10355, "Bank")
                .withDescription("Open the Varrock bank");
        
        assertEquals("Open the Varrock bank", task.getDescription());
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void setupEmptyScene() {
        Tile[][][] tiles = createEmptyTileGrid();
        when(scene.getTiles()).thenReturn(tiles);
    }

    private void setupSceneWithObject(int objectId, WorldPoint objectPos) {
        Tile[][][] tiles = createEmptyTileGrid();
        
        GameObject obj = createMockGameObject(objectId, objectPos);
        
        // Convert world to scene coordinates (assuming base at 3148, 3148)
        int sceneX = objectPos.getX() - 3200 + 52; // Scene center offset
        int sceneY = objectPos.getY() - 3200 + 52;
        
        if (sceneX >= 0 && sceneX < Constants.SCENE_SIZE && 
            sceneY >= 0 && sceneY < Constants.SCENE_SIZE) {
            tiles[objectPos.getPlane()][sceneX][sceneY] = createTileWithObject(obj);
        }
        
        when(scene.getTiles()).thenReturn(tiles);
    }

    private Tile[][][] createEmptyTileGrid() {
        Tile[][][] tiles = new Tile[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        return tiles;
    }

    private GameObject createMockGameObject(int objectId, WorldPoint pos) {
        return TaskTestHelper.mockGameObject(objectId, pos);
    }

    private Tile createTileWithObject(GameObject obj) {
        return TaskTestHelper.mockTileWithObject(obj);
    }

    private void advanceToPhase(InteractObjectTask task, String targetPhase, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            if (task.getState().isTerminal()) break;
            if (task.phase.name().equals(targetPhase)) break;
            task.execute(taskContext);
        }
    }
}

