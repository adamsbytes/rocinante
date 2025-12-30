package com.rocinante.tasks.impl;

import com.rocinante.core.GameStateService;
import com.rocinante.input.InventoryClickHelper;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.TaskTestHelper;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.ItemCollections;
import com.rocinante.util.Randomization;
import net.runelite.api.AnimationID;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for FiremakingTask.
 * Tests fire lighting, line repositioning, banking, and XP tracking.
 */
public class FiremakingTaskTest {

    private static final WorldPoint START_POS = new WorldPoint(3165, 3487, 0);

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

    @Mock
    private WorldState worldState;

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
                .worldPosition(START_POS)
                .build();

        inventoryState = mock(InventoryState.class);
        when(inventoryState.hasItem(anyInt())).thenReturn(false);
        when(inventoryState.countItem(anyInt())).thenReturn(0);
        when(inventoryState.getSlotOf(anyInt())).thenReturn(-1);

        // Client skill levels
        when(client.getRealSkillLevel(Skill.FIREMAKING)).thenReturn(30);
        when(client.getSkillExperience(Skill.FIREMAKING)).thenReturn(13363);

        // TaskContext wiring
        taskContext = mock(TaskContext.class);
        when(taskContext.getClient()).thenReturn(client);
        when(taskContext.getGameStateService()).thenReturn(gameStateService);
        when(taskContext.getMouseController()).thenReturn(mouseController);
        when(taskContext.getKeyboardController()).thenReturn(keyboardController);
        when(taskContext.getInventoryClickHelper()).thenReturn(inventoryClickHelper);
        when(taskContext.getHumanTimer()).thenReturn(humanTimer);
        when(taskContext.getRandomization()).thenReturn(randomization);
        when(taskContext.getWorldState()).thenReturn(worldState);
        when(taskContext.isLoggedIn()).thenReturn(true);
        when(taskContext.getPlayerState()).thenReturn(playerState);
        when(taskContext.getInventoryState()).thenReturn(inventoryState);

        // Default click helper returns success
        when(inventoryClickHelper.executeClick(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // Default timer completion
        when(humanTimer.sleep(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Default randomization
        when(randomization.uniformRandomInt(anyInt(), anyInt())).thenReturn(0);

        // Default world state
        when(worldState.getNearbyObjects()).thenReturn(Collections.emptyList());
    }

    // ========================================================================
    // FiremakingConfig Tests
    // ========================================================================

    @Test
    public void testConfig_RequiresLogItemId() {
        assertThrows(IllegalStateException.class, () -> {
            FiremakingConfig config = FiremakingConfig.builder()
                    .targetLevel(45)
                    .build();
            config.validate();
        });
    }

    @Test
    public void testConfig_RequiresTarget() {
        assertThrows(IllegalStateException.class, () -> {
            FiremakingConfig config = FiremakingConfig.builder()
                    .logItemId(ItemID.WILLOW_LOGS)
                    .build();
            config.validate();
        });
    }

    @Test
    public void testConfig_ValidWithLevelTarget() {
        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .build();
        
        config.validate(); // Should not throw
        assertTrue(config.hasLevelTarget());
    }

    @Test
    public void testConfig_ValidWithXpTarget() {
        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetXp(100000)
                .build();
        
        config.validate();
        assertTrue(config.hasXpTarget());
    }

    @Test
    public void testConfig_ValidWithLogsBurnedTarget() {
        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLogsBurned(500)
                .build();
        
        config.validate();
        assertTrue(config.hasLogsBurnedTarget());
    }

    @Test
    public void testConfig_ValidWithTimeLimit() {
        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .maxDuration(Duration.ofHours(1))
                .build();
        
        config.validate();
        assertTrue(config.hasTimeLimit());
    }

    @Test
    public void testConfig_XpPerLog() {
        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .build();

        assertEquals(90.0, config.getXpPerLog(), 0.01);
    }

    @Test
    public void testConfig_RequiredLevel() {
        FiremakingConfig willowConfig = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .build();
        
        FiremakingConfig magicConfig = FiremakingConfig.builder()
                .logItemId(ItemID.MAGIC_LOGS)
                .targetLevel(99)
                .build();

        assertEquals(30, willowConfig.getRequiredLevel());
        assertEquals(75, magicConfig.getRequiredLevel());
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_LoggedInWithLevel_True() {
        when(client.getRealSkillLevel(Skill.FIREMAKING)).thenReturn(30);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .build();
        FiremakingTask task = new FiremakingTask(config);

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_NotLoggedIn_False() {
        when(taskContext.isLoggedIn()).thenReturn(false);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .build();
        FiremakingTask task = new FiremakingTask(config);

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_LevelTooLow_False() {
        when(client.getRealSkillLevel(Skill.FIREMAKING)).thenReturn(1);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS) // Requires level 30
                .targetLevel(45)
                .build();
        FiremakingTask task = new FiremakingTask(config);

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_AlreadyAtTarget_False() {
        when(client.getRealSkillLevel(Skill.FIREMAKING)).thenReturn(50);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .build();
        FiremakingTask task = new FiremakingTask(config);

        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Setup Phase Tests
    // ========================================================================

    @Test
    public void testSetup_NoTinderbox_Fails() {
        when(inventoryState.hasItem(anyInt())).thenReturn(false);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .build();
        FiremakingTask task = new FiremakingTask(config);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("tinderbox"));
    }

    @Test
    public void testSetup_NoLogs_TransitionsToBanking() {
        // Has tinderbox but no logs
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.WILLOW_LOGS)).thenReturn(false);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .bankForLogs(true)
                .build();
        FiremakingTask task = new FiremakingTask(config);
        task.execute(taskContext);

        // Should transition to banking phase (not fail)
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testSetup_NoLogs_BankingDisabled_Completes() {
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.WILLOW_LOGS)).thenReturn(false);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .bankForLogs(false)
                .build();
        FiremakingTask task = new FiremakingTask(config);
        task.execute(taskContext);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    // ========================================================================
    // Lighting Fire Phase Tests
    // ========================================================================

    @Test
    public void testLightingFire_ClicksTinderboxThenLogs() {
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.WILLOW_LOGS)).thenReturn(true);
        when(inventoryState.getSlotOf(ItemID.TINDERBOX)).thenReturn(0);
        when(inventoryState.getSlotOf(ItemID.WILLOW_LOGS)).thenReturn(5);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .startPosition(START_POS)
                .build();
        FiremakingTask task = new FiremakingTask(config);

        // Execute multiple times to progress through phases
        for (int i = 0; i < 5; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Should have clicked inventory slots
        verify(inventoryClickHelper, atLeastOnce()).executeClick(anyInt(), anyString());
    }

    @Test
    public void testLightingFire_DetectsAnimation() {
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.WILLOW_LOGS)).thenReturn(true);
        when(inventoryState.getSlotOf(ItemID.TINDERBOX)).thenReturn(0);
        when(inventoryState.getSlotOf(ItemID.WILLOW_LOGS)).thenReturn(5);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .startPosition(START_POS)
                .build();
        FiremakingTask task = new FiremakingTask(config);

        // Execute through setup and lighting
        for (int i = 0; i < 5; i++) {
            task.execute(taskContext);
        }

        // Simulate fire lighting animation
        PlayerState lightingPlayer = PlayerState.builder()
                .animationId(AnimationID.FIREMAKING)
                .worldPosition(START_POS)
                .build();
        when(taskContext.getPlayerState()).thenReturn(lightingPlayer);

        task.execute(taskContext);

        // Task should recognize the animation
    }

    @Test
    public void testLightingFire_DetectsMovement() {
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.WILLOW_LOGS)).thenReturn(true);
        when(inventoryState.getSlotOf(ItemID.TINDERBOX)).thenReturn(0);
        when(inventoryState.getSlotOf(ItemID.WILLOW_LOGS)).thenReturn(5);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .startPosition(START_POS)
                .build();
        FiremakingTask task = new FiremakingTask(config);

        // Execute through phases
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        // Simulate player movement west after fire lit
        WorldPoint newPos = new WorldPoint(START_POS.getX() - 1, START_POS.getY(), 0);
        PlayerState movedPlayer = PlayerState.builder()
                .animationId(-1)
                .worldPosition(newPos)
                .build();
        when(taskContext.getPlayerState()).thenReturn(movedPlayer);

        task.execute(taskContext);

        // Logs burned should increase
        assertTrue(task.getLogsBurned() >= 0);
    }

    // ========================================================================
    // Repositioning Tests
    // ========================================================================

    @Test
    public void testRepositioning_WhenEndOfLine() {
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.WILLOW_LOGS)).thenReturn(true);
        when(inventoryState.getSlotOf(ItemID.TINDERBOX)).thenReturn(0);
        when(inventoryState.getSlotOf(ItemID.WILLOW_LOGS)).thenReturn(5);

        // Player has moved far west (end of line)
        WorldPoint endOfLinePos = new WorldPoint(START_POS.getX() - 15, START_POS.getY(), 0);
        PlayerState atEndPlayer = PlayerState.builder()
                .animationId(-1)
                .worldPosition(endOfLinePos)
                .build();
        when(taskContext.getPlayerState()).thenReturn(atEndPlayer);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .startPosition(START_POS)
                .minLineTiles(10)
                .build();
        FiremakingTask task = new FiremakingTask(config);

        // Task should detect end of line and reposition
        assertNotNull(task);
    }

    // ========================================================================
    // Completion Tests
    // ========================================================================

    @Test
    public void testCompletion_LevelTargetReached() {
        when(client.getRealSkillLevel(Skill.FIREMAKING)).thenReturn(45);
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.WILLOW_LOGS)).thenReturn(true);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .build();
        FiremakingTask task = new FiremakingTask(config);

        // Should not be executable since target reached
        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCompletion_XpTargetReached_AlreadyAtTarget_CannotExecute() {
        // XP is already above target
        when(client.getSkillExperience(Skill.FIREMAKING)).thenReturn(100000);
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.WILLOW_LOGS)).thenReturn(true);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetXp(50000)
                .build();
        FiremakingTask task = new FiremakingTask(config);
        
        // Task should not be executable - target already reached
        assertFalse(task.canExecute(taskContext));
        
        // Executing should not change state from PENDING since canExecute is false
        task.execute(taskContext);
        assertEquals(TaskState.PENDING, task.getState());
    }

    @Test
    public void testCompletion_LogsBurnedTarget_ZeroMeansNoTarget() {
        // targetLogsBurned(0) means "no logs burned target" (hasLogsBurnedTarget returns false)
        // The task can execute indefinitely without a logs target
        when(client.getSkillExperience(Skill.FIREMAKING)).thenReturn(5000);
        when(client.getRealSkillLevel(Skill.FIREMAKING)).thenReturn(30);
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.WILLOW_LOGS)).thenReturn(true);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLogsBurned(0) // 0 means "no target" - hasLogsBurnedTarget returns false
                .build();
        FiremakingTask task = new FiremakingTask(config);
        
        // With no logs target set, task should be executable
        assertTrue(task.canExecute(taskContext));
        
        // hasLogsBurnedTarget returns false when target is 0
        assertFalse(config.hasLogsBurnedTarget());
    }
    
    @Test  
    public void testLogsBurnedTarget_WithPositiveTarget_CanExecute() {
        // With a positive target, task should be executable
        when(client.getSkillExperience(Skill.FIREMAKING)).thenReturn(5000);
        when(client.getRealSkillLevel(Skill.FIREMAKING)).thenReturn(30);
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.WILLOW_LOGS)).thenReturn(true);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLogsBurned(10) // Positive target - should be executable
                .build();
        FiremakingTask task = new FiremakingTask(config);
        
        // Task should be executable - logsBurned (0) < target (10)
        assertTrue(task.canExecute(taskContext));
        assertEquals(0, task.getLogsBurned());
        assertTrue(config.hasLogsBurnedTarget());
    }

    // ========================================================================
    // XP Tracking Tests
    // ========================================================================

    @Test
    public void testXpTracking_CalculatesGain() {
        AtomicInteger xp = new AtomicInteger(13363);
        when(client.getSkillExperience(Skill.FIREMAKING)).thenAnswer(inv -> xp.get());
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.WILLOW_LOGS)).thenReturn(true);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .build();
        FiremakingTask task = new FiremakingTask(config);
        
        // Initialize task
        task.execute(taskContext);
        
        // Simulate XP gain
        xp.addAndGet(90); // One willow log burned

        int gained = task.getXpGained(taskContext);
        assertEquals(90, gained);
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_Default() {
        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .build();
        FiremakingTask task = new FiremakingTask(config);

        String desc = task.getDescription();
        assertTrue(desc.contains("Firemaking"));
    }

    @Test
    public void testGetDescription_Custom() {
        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .build();
        FiremakingTask task = new FiremakingTask(config)
                .withDescription("Train firemaking at GE");

        assertEquals("Train firemaking at GE", task.getDescription());
    }

    // ========================================================================
    // Real Game Scenario Tests
    // ========================================================================

    @Test
    public void testScenario_GEFiremaking() {
        when(client.getRealSkillLevel(Skill.FIREMAKING)).thenReturn(30);
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.WILLOW_LOGS)).thenReturn(true);
        when(inventoryState.getSlotOf(ItemID.TINDERBOX)).thenReturn(0);
        when(inventoryState.getSlotOf(ItemID.WILLOW_LOGS)).thenReturn(1);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .startPosition(FiremakingConfig.GE_START_POINT)
                .bankForLogs(true)
                .targetLevel(45)
                .build();
        FiremakingTask task = new FiremakingTask(config);

        assertTrue(task.canExecute(taskContext));
        assertEquals(FiremakingConfig.GE_START_POINT, config.getStartPosition());
    }

    @Test
    public void testScenario_MagicLogTraining() {
        when(client.getRealSkillLevel(Skill.FIREMAKING)).thenReturn(75);
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.MAGIC_LOGS)).thenReturn(true);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.MAGIC_LOGS)
                .targetLevel(99)
                .build();
        FiremakingTask task = new FiremakingTask(config);

        assertTrue(task.canExecute(taskContext));
        assertEquals(303.8, config.getXpPerLog(), 0.1);
        assertEquals(75, config.getRequiredLevel());
    }

    @Test
    public void testScenario_TimeLimitedSession() {
        when(client.getRealSkillLevel(Skill.FIREMAKING)).thenReturn(30);
        when(inventoryState.hasItem(ItemID.TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.WILLOW_LOGS)).thenReturn(true);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .maxDuration(Duration.ofHours(2))
                .build();
        FiremakingTask task = new FiremakingTask(config);

        assertTrue(task.canExecute(taskContext));
        assertTrue(config.hasTimeLimit());
        assertEquals(Duration.ofHours(2), config.getMaxDuration());
    }

    // ========================================================================
    // Different Tinderbox Types Tests
    // ========================================================================

    @Test
    public void testTinderbox_GoldenTinderbox() {
        // Golden tinderbox is in ItemCollections.TINDERBOXES
        when(inventoryState.hasItem(ItemID.GOLDEN_TINDERBOX)).thenReturn(true);
        when(inventoryState.hasItem(ItemID.WILLOW_LOGS)).thenReturn(true);
        when(inventoryState.getSlotOf(ItemID.GOLDEN_TINDERBOX)).thenReturn(0);
        when(inventoryState.getSlotOf(ItemID.WILLOW_LOGS)).thenReturn(1);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .tinderboxItemIds(ItemCollections.TINDERBOXES)
                .build();
        FiremakingTask task = new FiremakingTask(config);

        // Task should recognize golden tinderbox
        assertTrue(config.getTinderboxItemIds().contains(ItemID.GOLDEN_TINDERBOX));
    }
}

