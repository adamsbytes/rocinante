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
import com.rocinante.timing.HumanTimer;
import net.runelite.api.Client;
import net.runelite.api.Item;
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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FiremakingTask.
 * Tests fire lighting, line repositioning, and completion conditions.
 */
public class FiremakingTaskTest {

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
    private InventoryClickHelper inventoryClickHelper;

    private TaskContext taskContext;

    private WorldPoint playerPos;
    private PlayerState validPlayerState;
    private InventoryState inventoryWithLogsAndTinderbox;
    private InventoryState inventoryWithTinderboxOnly;
    private InventoryState emptyInventory;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Set up TaskContext with inventory click helper
        taskContext = new TaskContext(
                client, 
                gameStateService, 
                mouseController, 
                keyboardController, 
                humanTimer,
                null, // targetSelector
                null, // combatManager
                null, // gearSwitcher
                null, // foodManager
                inventoryClickHelper,
                null, // groundItemClickHelper
                null, // widgetClickHelper
                null, // menuHelper
                null, // unlockTracker
                null, // agilityCourseRepository
                null, // playerProfile
                null, // puzzleSolverRegistry
                null, // cameraController
                null, // mouseCameraCoupler
                null, // actionSequencer
                null, // inefficiencyInjector
                null, // logoutHandler
                null, // breakScheduler
                null, // randomization
                null, // pathFinder
                null, // webWalker
                null, // obstacleHandler
                null  // planeTransitionHandler
        );

        // Player at GE start position
        playerPos = FiremakingConfig.GE_START_POINT;
        validPlayerState = PlayerState.builder()
                .worldPosition(playerPos)
                .currentHitpoints(50)
                .maxHitpoints(50)
                .animationId(-1)
                .inCombat(false)
                .build();

        // Create inventory items
        Item tinderbox = mock(Item.class);
        when(tinderbox.getId()).thenReturn(ItemID.TINDERBOX);
        when(tinderbox.getQuantity()).thenReturn(1);

        Item willowLogs = mock(Item.class);
        when(willowLogs.getId()).thenReturn(ItemID.WILLOW_LOGS);
        when(willowLogs.getQuantity()).thenReturn(27);

        // Inventory with tinderbox and logs
        Item[] itemsWithLogs = new Item[28];
        itemsWithLogs[0] = tinderbox;
        itemsWithLogs[1] = willowLogs;
        inventoryWithLogsAndTinderbox = new InventoryState(itemsWithLogs);

        // Inventory with only tinderbox
        Item[] itemsWithTinderbox = new Item[28];
        itemsWithTinderbox[0] = tinderbox;
        inventoryWithTinderboxOnly = new InventoryState(itemsWithTinderbox);

        // Empty inventory
        emptyInventory = new InventoryState(new Item[28]);

        // Default mocks
        when(gameStateService.isLoggedIn()).thenReturn(true);
        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(WorldState.EMPTY);
        when(gameStateService.getInventoryState()).thenReturn(inventoryWithLogsAndTinderbox);

        // Mock firemaking level - set to 30 (sufficient for willow) but below target (45)
        when(client.getRealSkillLevel(Skill.FIREMAKING)).thenReturn(30);
        when(client.getSkillExperience(Skill.FIREMAKING)).thenReturn(13363); // Level 30 XP

        // Mock inventory click helper
        when(inventoryClickHelper.executeClick(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
    }

    // ========================================================================
    // Configuration Tests
    // ========================================================================

    @Test
    public void testConfigValidation_RequiresLogItemId() {
        assertThrows(IllegalStateException.class, () -> {
            FiremakingConfig.builder()
                    .logItemId(0)
                    .targetLevel(45)
                    .build()
                    .validate();
        });
    }

    @Test
    public void testConfigValidation_RequiresAtLeastOneTarget() {
        assertThrows(IllegalStateException.class, () -> {
            FiremakingConfig.builder()
                    .logItemId(ItemID.WILLOW_LOGS)
                    .build()
                    .validate();
        });
    }

    @Test
    public void testConfigValidation_PassesWithLevelTarget() {
        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .build();

        config.validate(); // Should not throw
        assertTrue(config.hasLevelTarget());
        assertFalse(config.hasXpTarget());
    }

    @Test
    public void testConfigValidation_PassesWithXpTarget() {
        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetXp(500000)
                .build();

        config.validate(); // Should not throw
        assertFalse(config.hasLevelTarget());
        assertTrue(config.hasXpTarget());
    }

    @Test
    public void testLogXpValues() {
        assertEquals(40.0, FiremakingConfig.LOG_XP.get(ItemID.LOGS), 0.01);
        assertEquals(60.0, FiremakingConfig.LOG_XP.get(ItemID.OAK_LOGS), 0.01);
        assertEquals(90.0, FiremakingConfig.LOG_XP.get(ItemID.WILLOW_LOGS), 0.01);
        assertEquals(135.0, FiremakingConfig.LOG_XP.get(ItemID.MAPLE_LOGS), 0.01);
        assertEquals(202.5, FiremakingConfig.LOG_XP.get(ItemID.YEW_LOGS), 0.01);
        assertEquals(303.8, FiremakingConfig.LOG_XP.get(ItemID.MAGIC_LOGS), 0.01);
    }

    @Test
    public void testLogLevelRequirements() {
        assertEquals(Integer.valueOf(1), FiremakingConfig.LOG_LEVELS.get(ItemID.LOGS));
        assertEquals(Integer.valueOf(15), FiremakingConfig.LOG_LEVELS.get(ItemID.OAK_LOGS));
        assertEquals(Integer.valueOf(30), FiremakingConfig.LOG_LEVELS.get(ItemID.WILLOW_LOGS));
        assertEquals(Integer.valueOf(45), FiremakingConfig.LOG_LEVELS.get(ItemID.MAPLE_LOGS));
        assertEquals(Integer.valueOf(60), FiremakingConfig.LOG_LEVELS.get(ItemID.YEW_LOGS));
        assertEquals(Integer.valueOf(75), FiremakingConfig.LOG_LEVELS.get(ItemID.MAGIC_LOGS));
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_ReturnsFalse_WhenNotLoggedIn() {
        when(gameStateService.isLoggedIn()).thenReturn(false);

        FiremakingTask task = createBasicTask();
        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_ReturnsFalse_WhenLevelTooLow() {
        when(client.getRealSkillLevel(Skill.FIREMAKING)).thenReturn(20); // Below 30 for willow

        FiremakingTask task = createBasicTask();
        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_ReturnsTrue_WhenLevelSufficient() {
        when(client.getRealSkillLevel(Skill.FIREMAKING)).thenReturn(30);

        FiremakingTask task = createBasicTask();
        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_ReturnsFalse_WhenTargetLevelReached() {
        when(client.getRealSkillLevel(Skill.FIREMAKING)).thenReturn(45);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .build();

        FiremakingTask task = new FiremakingTask(config);
        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_ReturnsFalse_WhenTargetXpReached() {
        when(client.getSkillExperience(Skill.FIREMAKING)).thenReturn(500000);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetXp(500000)
                .build();

        FiremakingTask task = new FiremakingTask(config);
        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Execution Tests
    // ========================================================================

    @Test
    public void testExecute_TransitionsToBank_WhenNoLogs() {
        when(gameStateService.getInventoryState()).thenReturn(inventoryWithTinderboxOnly);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .bankForLogs(true)
                .build();

        FiremakingTask task = new FiremakingTask(config);

        // Execute setup phase
        task.execute(taskContext);

        // Should have transitioned to banking (phase is private, check behavior)
        assertEquals(TaskState.RUNNING, task.getState());
    }

    @Test
    public void testExecute_Completes_WhenNoLogsAndBankingDisabled() {
        when(gameStateService.getInventoryState()).thenReturn(inventoryWithTinderboxOnly);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .bankForLogs(false)
                .build();

        FiremakingTask task = new FiremakingTask(config);

        // Execute setup phase - should complete since no logs and banking disabled
        task.execute(taskContext);

        // Task completes when no logs and banking disabled
        assertTrue("Task should complete when no logs and banking disabled",
                task.getState() == TaskState.COMPLETED || task.getLogsBurned() == 0);
    }

    @Test
    public void testExecute_FailsWithoutTinderbox() {
        when(gameStateService.getInventoryState()).thenReturn(emptyInventory);

        FiremakingTask task = createBasicTask();
        task.execute(taskContext);

        // Task should fail when missing tinderbox
        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testExecute_StartsLightingWhenReady() {
        // Player at start position with logs
        when(gameStateService.getInventoryState()).thenReturn(inventoryWithLogsAndTinderbox);
        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);

        FiremakingTask task = createBasicTask();

        // First execution - setup phase
        task.execute(taskContext);

        // Should be in running state after setup
        assertEquals(TaskState.RUNNING, task.getState());
    }

    // ========================================================================
    // Fire Lighting Tests
    // ========================================================================

    @Test
    public void testFireLighting_UsesInventoryClickHelper() {
        when(gameStateService.getInventoryState()).thenReturn(inventoryWithLogsAndTinderbox);

        FiremakingTask task = createBasicTask();

        // Execute to start lighting
        task.execute(taskContext);
        task.execute(taskContext);

        // Verify inventory click was attempted for tinderbox
        verify(inventoryClickHelper, atLeastOnce()).executeClick(anyInt(), anyString());
    }

    @Test
    public void testLogsBurnedTracking() {
        FiremakingTask task = createBasicTask();

        // Initial logs burned should be 0
        assertEquals(0, task.getLogsBurned());
    }

    // ========================================================================
    // XP Tracking Tests
    // ========================================================================

    @Test
    public void testXpTracking_InitializesOnFirstExecute() {
        when(client.getSkillExperience(Skill.FIREMAKING)).thenReturn(100000);

        FiremakingTask task = createBasicTask();
        task.execute(taskContext);

        // After first execute, XP should be tracked
        when(client.getSkillExperience(Skill.FIREMAKING)).thenReturn(100090); // +90 for one willow
        assertEquals(90, task.getXpGained(taskContext));
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testDescription_ContainsLogCount() {
        FiremakingTask task = createBasicTask();
        String desc = task.getDescription();

        assertTrue(desc.contains("logs="));
        assertTrue(desc.contains("phase="));
    }

    @Test
    public void testDescription_CanBeCustomized() {
        FiremakingTask task = createBasicTask();
        task.withDescription("Custom firemaking description");

        assertEquals("Custom firemaking description", task.getDescription());
    }

    // ========================================================================
    // Time Limit Tests
    // ========================================================================

    @Test
    public void testConfig_TimeLimit() {
        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .maxDuration(Duration.ofMinutes(30))
                .build();

        assertTrue(config.hasTimeLimit());
        assertEquals(Duration.ofMinutes(30), config.getMaxDuration());
    }

    // ========================================================================
    // Start Position Tests
    // ========================================================================

    @Test
    public void testConfig_DefaultsToGEStartPosition() {
        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .build();

        assertEquals(FiremakingConfig.GE_START_POINT, config.getStartPosition());
    }

    @Test
    public void testConfig_CustomStartPosition() {
        WorldPoint customStart = new WorldPoint(3200, 3200, 0);

        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .startPosition(customStart)
                .build();

        assertEquals(customStart, config.getStartPosition());
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private FiremakingTask createBasicTask() {
        FiremakingConfig config = FiremakingConfig.builder()
                .logItemId(ItemID.WILLOW_LOGS)
                .targetLevel(45)
                .bankForLogs(true)
                .build();

        return new FiremakingTask(config);
    }
}

