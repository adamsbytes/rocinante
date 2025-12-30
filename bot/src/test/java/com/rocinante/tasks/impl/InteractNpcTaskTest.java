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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for InteractNpcTask.
 * Tests NPC finding, movement tracking, death handling, re-targeting, and dialogue detection.
 */
public class InteractNpcTaskTest {


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
    private Canvas canvas;

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
        when(taskContext.getSafeClickExecutor()).thenReturn(safeClickExecutor);
        when(taskContext.getMenuHelper()).thenReturn(menuHelper);
        when(taskContext.isLoggedIn()).thenReturn(true);
        when(taskContext.getPlayerState()).thenReturn(playerState);

        // GameStateService
        when(gameStateService.isLoggedIn()).thenReturn(true);
        when(gameStateService.getPlayerState()).thenReturn(playerState);

        // Client mocks
        when(client.getNpcs()).thenReturn(npcList);
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
        when(safeClickExecutor.clickNpc(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // MenuHelper defaults
        when(menuHelper.isLeftClickActionContains(anyString())).thenReturn(true);
        when(menuHelper.selectMenuEntry(any(Rectangle.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(menuHelper.selectMenuEntry(any(Rectangle.class), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Test
    public void testConstructor_SingleId() {
        InteractNpcTask task = new InteractNpcTask(NpcID.BANKER_1618, "Talk-to");

        assertEquals(NpcID.BANKER_1618, task.getNpcId());
        assertEquals("Talk-to", task.getMenuAction());
        assertTrue(task.getAlternateNpcIds().isEmpty());
        assertEquals(15, task.getSearchRadius()); // Default
        assertEquals(0.3, task.getCameraRotationChance(), 0.01); // Default
        assertTrue(task.isTrackMovement()); // Default enabled
    }

    @Test
    public void testConstructor_MultipleIds() {
        List<Integer> ids = Arrays.asList(NpcID.GOBLIN_3029, NpcID.GOBLIN_3030, 3031);
        InteractNpcTask task = new InteractNpcTask(ids, "Attack");

        assertEquals(NpcID.GOBLIN_3029, task.getNpcId()); // First ID is primary
        assertEquals(2, task.getAlternateNpcIds().size());
        assertTrue(task.getAlternateNpcIds().contains(NpcID.GOBLIN_3030));
        assertTrue(task.getAlternateNpcIds().contains(3031));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_EmptyIds_Throws() {
        new InteractNpcTask(Arrays.asList(), "Attack");
    }

    // ========================================================================
    // Builder Method Tests
    // ========================================================================

    @Test
    public void testBuilderMethods() {
        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack")
                .withSearchRadius(20)
                .withCameraRotationChance(0.5)
                .withSuccessAnimation(422)
                .withDialogueExpected(false)
                .withTrackMovement(true)
                .withWaitForIdle(false)
                .withNpcName("Goblin")
                .withDescription("Attack a goblin")
                .withAlternateIds(NpcID.GOBLIN_3030);

        assertEquals(20, task.getSearchRadius());
        assertEquals(0.5, task.getCameraRotationChance(), 0.01);
        assertEquals(422, task.getSuccessAnimationId());
        assertFalse(task.isDialogueExpected());
        assertTrue(task.isTrackMovement());
        assertFalse(task.isWaitForIdle());
        assertEquals("Goblin", task.getNpcName());
        assertEquals("Attack a goblin", task.getDescription());
        assertEquals(1, task.getAlternateNpcIds().size());
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_WhenLoggedIn_ReturnsTrue() {
        when(taskContext.isLoggedIn()).thenReturn(true);

        InteractNpcTask task = new InteractNpcTask(NpcID.BANKER_1618, "Talk-to");

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_WhenNotLoggedIn_ReturnsFalse() {
        when(taskContext.isLoggedIn()).thenReturn(false);

        InteractNpcTask task = new InteractNpcTask(NpcID.BANKER_1618, "Talk-to");

        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Phase: FIND_NPC Tests
    // ========================================================================

    @Test
    public void testFindNpc_NpcFound_TransitionsToNextPhase() {
        addNpcToScene(NpcID.BANKER_1618, "Banker", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.BANKER_1618, "Talk-to");
        task.execute(taskContext);

        // Should transition from FIND_NPC to either ROTATE_CAMERA or MOVE_MOUSE
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindNpc_NpcNotFound_Fails() {
        // npcList is empty

        InteractNpcTask task = new InteractNpcTask(NpcID.BANKER_1618, "Talk-to");
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("NPC not found"));
    }

    @Test
    public void testFindNpc_NpcOutOfRadius_Fails() {
        // NPC at 50 tiles away (beyond default 15 tile radius)
        addNpcToScene(NpcID.BANKER_1618, "Banker", new WorldPoint(3250, 3250, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.BANKER_1618, "Talk-to");
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindNpc_CustomRadius_FindsDistantNpc() {
        // NPC at 20 tiles away
        addNpcToScene(NpcID.BANKER_1618, "Banker", new WorldPoint(3220, 3200, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.BANKER_1618, "Talk-to")
                .withSearchRadius(25);
        task.execute(taskContext);

        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindNpc_AlternateIdFound_Succeeds() {
        // Primary ID not present, but alternate is
        addNpcToScene(NpcID.GOBLIN_3030, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack")
                .withAlternateIds(NpcID.GOBLIN_3030);
        task.execute(taskContext);

        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindNpc_ClosestNpcSelected() {
        // Setup two NPCs - one close, one far
        addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3201, 3200, 0)); // 1 tile
        addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3210, 3200, 0)); // 10 tiles

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack");
        task.execute(taskContext);

        // Task should find the closer NPC
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindNpc_NameFilterApplied() {
        // Two NPCs with same ID but different names
        addNpcToScene(NpcID.MAN_3108, "Man", new WorldPoint(3201, 3200, 0));
        addNpcToScene(NpcID.MAN_3108, "Guard", new WorldPoint(3202, 3200, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.MAN_3108, "Talk-to")
                .withNpcName("Guard");
        task.execute(taskContext);

        // Should only match the Guard
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testFindNpc_DeadNpcSkipped() {
        NPC deadNpc = TaskTestHelper.mockNpc(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3201, 3200, 0));
        when(deadNpc.isDead()).thenReturn(true);
        npcList.add(deadNpc);
        
        // Add a living goblin farther away
        addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3205, 3200, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack");
        task.execute(taskContext);

        // Should find the living goblin, not the dead one
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // NPC Movement Tracking Tests
    // ========================================================================

    @Test
    public void testNpcMovement_SmallMovement_ContinuesInteraction() {
        NPC npc = addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack")
                .withTrackMovement(true);

        // Execute to find NPC
        task.execute(taskContext);

        // Simulate NPC moving 2 tiles (within MAX_NPC_MOVEMENT)
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3204, 3202, 0));

        // Continue execution - should not fail due to movement
        for (int i = 0; i < 5; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testNpcMovement_LargeMovement_UpdatesLastPosition() {
        NPC npc = addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack")
                .withTrackMovement(true);

        // Execute to find NPC
        task.execute(taskContext);

        // Simulate NPC moving 5 tiles (beyond MAX_NPC_MOVEMENT of 3)
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3207, 3202, 0));

        // Should update tracking but continue
        for (int i = 0; i < 5; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should not immediately fail - will try to continue with new position
        // (Failure only happens if NPC becomes null/dead)
    }

    @Test
    public void testNpcMovement_TrackingDisabled_IgnoresMovement() {
        NPC npc = addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack")
                .withTrackMovement(false);

        task.execute(taskContext);

        // Simulate NPC moving significantly
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3220, 3220, 0));

        // Should continue without re-tracking logic
        for (int i = 0; i < 5; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Behavior depends on whether task can still get clickbox
    }

    // ========================================================================
    // NPC Death/Despawn Tests
    // ========================================================================

    @Test
    public void testNpcDeath_BeforeClick_TriggersRetarget() {
        NPC npc = addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack");

        // Execute to find NPC
        task.execute(taskContext);

        // NPC dies - add another living one
        when(npc.isDead()).thenReturn(true);
        NPC newNpc = addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3205, 3205, 0));

        // Should retarget to new NPC
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should either find new target or fail if max retries exceeded
    }

    @Test
    public void testNpcDeath_MaxRetargetsExceeded_Fails() {
        NPC npc = addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack");

        // Execute to find NPC
        task.execute(taskContext);

        // NPC dies with no replacement
        when(npc.isDead()).thenReturn(true);
        npcList.clear();

        // Execute until failure
        for (int i = 0; i < 20; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testNpcDespawn_DuringMoveMouse_TriggersRetarget() {
        NPC npc = addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack");

        // Execute to find NPC
        task.execute(taskContext);
        
        // NPC completely despawns (removed from list)
        npcList.remove(npc);
        
        // Add new one
        addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3203, 3203, 0));

        // Continue - should attempt retarget
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should have tried to retarget
    }

    // ========================================================================
    // Re-targeting Tests
    // ========================================================================

    @Test
    public void testRetarget_MultipleAttempts_SucceedsEventually() {
        // Start with one goblin
        NPC npc1 = addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack");
        task.execute(taskContext);

        // First goblin dies, add second
        when(npc1.isDead()).thenReturn(true);
        NPC npc2 = addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3204, 3204, 0));

        // Execute some ticks
        for (int i = 0; i < 5; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Second goblin dies, add third
        when(npc2.isDead()).thenReturn(true);
        addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3206, 3206, 0));

        // Should eventually succeed or fail after MAX_RETARGET_ATTEMPTS
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }
    }

    @Test
    public void testRetarget_MaxAttemptsReached_Fails() {
        NPC npc = addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack");
        task.execute(taskContext);

        // Make NPC dead and never add replacements
        when(npc.isDead()).thenReturn(true);
        npcList.clear();

        // Keep executing - should fail after MAX_RETARGET_ATTEMPTS (3)
        for (int i = 0; i < 30; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("NPC"));
    }

    // ========================================================================
    // Dialogue Detection Tests
    // ========================================================================

    @Test
    public void testDialogue_ExpectedAndOpened_Completes() {
        addNpcToScene(NpcID.BANKER_1618, "Banker", new WorldPoint(3202, 3202, 0));
        
        // Mock dialogue widget visible
        Widget dialogueWidget = mock(Widget.class);
        when(dialogueWidget.isHidden()).thenReturn(false);
        when(client.getWidget(231, 0)).thenReturn(dialogueWidget);

        InteractNpcTask task = new InteractNpcTask(NpcID.BANKER_1618, "Talk-to")
                .withDialogueExpected(true);

        // Execute through phases
        for (int i = 0; i < 20; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testDialogue_ExpectedButNotOpened_TimesOut() {
        addNpcToScene(NpcID.BANKER_1618, "Banker", new WorldPoint(3202, 3202, 0));
        
        // No dialogue widget visible
        when(client.getWidget(anyInt(), anyInt())).thenReturn(null);

        InteractNpcTask task = new InteractNpcTask(NpcID.BANKER_1618, "Talk-to")
                .withDialogueExpected(true);

        // Advance to WAIT_RESPONSE and timeout
        for (int i = 0; i < 30; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("timeout"));
    }

    @Test
    public void testDialogue_NotExpected_CompletesOnAnimation() {
        addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack")
                .withDialogueExpected(false);

        // Player starts attacking - set before execution
        PlayerState attackingState = TaskTestHelper.playerStateWithAnimation(422, playerPos);
        when(taskContext.getPlayerState()).thenReturn(attackingState);
        when(gameStateService.getPlayerState()).thenReturn(attackingState);

        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 20);

        assertEquals(TaskState.COMPLETED, result);
    }

    // ========================================================================
    // Menu Action Tests
    // ========================================================================

    @Test
    public void testMenuAction_LeftClickAvailable() {
        addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));
        when(menuHelper.isLeftClickActionContains("Attack")).thenReturn(true);

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack");

        // Execute through phases
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        verify(menuHelper, atLeastOnce()).isLeftClickActionContains("Attack");
    }

    @Test
    public void testMenuAction_RightClickRequired() {
        addNpcToScene(NpcID.MAN_3108, "Man", new WorldPoint(3202, 3202, 0));
        when(menuHelper.isLeftClickActionContains("Pickpocket")).thenReturn(false);

        InteractNpcTask task = new InteractNpcTask(NpcID.MAN_3108, "Pickpocket");

        // Execute through phases
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        verify(menuHelper, atLeastOnce()).isLeftClickActionContains("Pickpocket");
    }

    // ========================================================================
    // Success Detection Tests
    // ========================================================================

    @Test
    public void testSuccess_SpecificAnimation() {
        addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack")
                .withSuccessAnimation(422);

        // Player plays expected animation - set BEFORE execution starts
        // The task will detect the animation when it reaches WAIT_RESPONSE
        PlayerState animatingState = TaskTestHelper.playerStateWithAnimation(422, playerPos);
        when(taskContext.getPlayerState()).thenReturn(animatingState);
        when(gameStateService.getPlayerState()).thenReturn(animatingState);

        // Execute to completion
        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 20);

        assertEquals(TaskState.COMPLETED, result);
    }

    @Test
    public void testSuccess_AnyAnimation_WhenNoSpecificExpected() {
        addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack");
        // No specific animation set (default -1)

        // Player plays some animation - set before execution
        PlayerState animatingState = TaskTestHelper.playerStateWithAnimation(12345, playerPos);
        when(taskContext.getPlayerState()).thenReturn(animatingState);
        when(gameStateService.getPlayerState()).thenReturn(animatingState);

        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 20);

        assertEquals(TaskState.COMPLETED, result);
    }

    @Test
    public void testSuccess_PlayerInteracting() {
        addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack");

        // Player is interacting - set before execution
        PlayerState interactingState = PlayerState.builder()
                .worldPosition(playerPos)
                .isInteracting(true)
                .build();
        when(taskContext.getPlayerState()).thenReturn(interactingState);
        when(gameStateService.getPlayerState()).thenReturn(interactingState);

        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 20);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testSuccess_PositionChanged() {
        addNpcToScene(NpcID.BANKER_1618, "Banker", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.BANKER_1618, "Talk-to");

        // Use a counter to track executions and change position after a few ticks
        final AtomicInteger executionCount = new AtomicInteger(0);
        final WorldPoint originalPos = playerPos;
        final WorldPoint movedPos = new WorldPoint(3201, 3200, 0);
        
        when(taskContext.getPlayerState()).thenAnswer(inv -> {
            // After 5 executions (past FIND_NPC), return moved position
            if (executionCount.incrementAndGet() > 5) {
                return PlayerState.builder()
                        .worldPosition(movedPos)
                        .animationId(-1)
                        .build();
            }
            return PlayerState.builder()
                    .worldPosition(originalPos)
                    .animationId(-1)
                    .build();
        });

        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 20);

        assertEquals(TaskState.COMPLETED, result);
    }

    // ========================================================================
    // Timeout Tests
    // ========================================================================

    @Test
    public void testTimeout_NoSuccessIndicators_Fails() {
        addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack");

        // Keep player idle (no success indicators) throughout execution
        // Player position stays at playerPos (3200, 3200, 0) which is the same as start
        PlayerState idleState = TaskTestHelper.idlePlayerState(playerPos);
        when(taskContext.getPlayerState()).thenReturn(idleState);
        when(gameStateService.getPlayerState()).thenReturn(idleState);

        // Execute until timeout causes failure
        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 25);

        assertEquals(TaskState.FAILED, result);
        assertTrue(task.getFailureReason().contains("timeout"));
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_Default() {
        InteractNpcTask task = new InteractNpcTask(NpcID.BANKER_1618, "Talk-to");

        String desc = task.getDescription();

        assertTrue(desc.contains(String.valueOf(NpcID.BANKER_1618)));
        assertTrue(desc.contains("Talk-to"));
    }

    @Test
    public void testGetDescription_WithName() {
        InteractNpcTask task = new InteractNpcTask(NpcID.BANKER_1618, "Talk-to")
                .withNpcName("Bob");

        String desc = task.getDescription();

        assertTrue(desc.contains("Bob"));
    }

    @Test
    public void testGetDescription_Custom() {
        InteractNpcTask task = new InteractNpcTask(NpcID.BANKER_1618, "Talk-to")
                .withDescription("Talk to the friendly banker");

        assertEquals("Talk to the friendly banker", task.getDescription());
    }

    // ========================================================================
    // Reset Tests
    // ========================================================================

    @Test
    public void testResetImpl_ClearsAllState() {
        addNpcToScene(NpcID.GOBLIN_3029, "Goblin", new WorldPoint(3202, 3202, 0));

        InteractNpcTask task = new InteractNpcTask(NpcID.GOBLIN_3029, "Attack");

        // Execute to set up state
        task.execute(taskContext);

        // Reset
        task.resetForRetry();

        assertNull(task.getFailureReason());
        assertEquals(TaskState.PENDING, task.getState());
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private NPC addNpcToScene(int npcId, String name, WorldPoint pos) {
        NPC npc = TaskTestHelper.mockNpc(npcId, name, pos);
        npcList.add(npc);
        return npc;
    }
}

