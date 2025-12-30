package com.rocinante.inventory;

import com.rocinante.core.GameStateService;
import com.rocinante.progression.MethodLocation;
import com.rocinante.progression.MethodType;
import com.rocinante.progression.TrainingMethod;
import com.rocinante.state.BankState;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.SkillTask;
import com.rocinante.tasks.impl.SkillTaskConfig;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.ItemCollections;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.inject.Provider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for SkillTask inventory preparation using IdealInventory system.
 */
public class SkillTaskInventoryIntegrationTest {

    @Mock private Client mockClient;
    @Mock private GameStateService mockGameStateService;
    @Mock private PlayerState mockPlayerState;
    @Mock private InventoryState mockInventory;
    @Mock private EquipmentState mockEquipment;
    @Mock private BankState mockBank;
    @Mock private HumanTimer mockHumanTimer;

    private InventoryPreparation inventoryPreparation;
    private TaskContext taskContext;

    private static final int BRONZE_AXE_ID = ItemID.BRONZE_AXE;
    private static final int RUNE_AXE_ID = ItemID.RUNE_AXE;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        inventoryPreparation = new InventoryPreparation();

        // Setup mock player with skill levels
        Map<Skill, Integer> skillLevels = new HashMap<>();
        skillLevels.put(Skill.WOODCUTTING, 50);
        skillLevels.put(Skill.ATTACK, 50);
        skillLevels.put(Skill.AGILITY, 50);
        when(mockPlayerState.getBaseSkillLevels()).thenReturn(skillLevels);
        when(mockPlayerState.getWorldPosition()).thenReturn(new WorldPoint(3000, 3000, 0));
        when(mockPlayerState.isValid()).thenReturn(true);
        when(mockPlayerState.isIdle()).thenReturn(true);

        // Setup mock game state service
        when(mockGameStateService.getPlayerState()).thenReturn(mockPlayerState);
        when(mockGameStateService.getInventoryState()).thenReturn(mockInventory);
        when(mockGameStateService.getEquipmentState()).thenReturn(mockEquipment);
        when(mockGameStateService.getBankState()).thenReturn(mockBank);

        // Default: empty inventory
        when(mockInventory.isEmpty()).thenReturn(true);
        when(mockInventory.hasItem(anyInt())).thenReturn(false);
        when(mockInventory.isFull()).thenReturn(false);
        when(mockInventory.getAllItems()).thenReturn(java.util.Collections.emptyList());
        when(mockInventory.getUsedSlots()).thenReturn(0);
        when(mockInventory.getFreeSlots()).thenReturn(28);

        // Default: nothing equipped
        when(mockEquipment.hasEquipped(anyInt())).thenReturn(false);

        // Default: bank has axes
        when(mockBank.hasItem(BRONZE_AXE_ID)).thenReturn(true);
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(true);
        when(mockBank.isUnknown()).thenReturn(false);

        // Create provider
        Provider<GameStateService> provider = () -> mockGameStateService;

        // Create task context with inventory preparation
        taskContext = new TaskContext(
                mockClient,
                provider,
                null, // mouseController
                null, // keyboardController
                mockHumanTimer,
                null, // targetSelector
                null, // combatManager
                null, // gearSwitcher
                null, // foodManager
                null, // inventoryClickHelper
                null, // groundItemClickHelper
                null, // widgetClickHelper
                null, // menuHelper
                null, // safeClickExecutor
                null, // unlockTracker
                null, // agilityCourseRepository
                null, // playerProfile
                null, // puzzleSolverRegistry
                null, // cameraController
                null, // mouseCameraCoupler
                null, // actionSequencer
                null, // inefficiencyInjector
                null, // predictiveHoverManager
                null, // logoutHandler
                null, // breakScheduler
                null, // randomization
                null, // pathFinder
                null, // webWalker
                null, // obstacleHandler
                null, // planeTransitionHandler
                inventoryPreparation,
                null  // questService
        );
    }

    // ========================================================================
    // SkillTaskConfig.getOrCreateIdealInventory Tests
    // ========================================================================

    @Test
    public void testGetOrCreateIdealInventory_FromTrainingMethod() {
        TrainingMethod method = createWoodcuttingMethod();

        SkillTaskConfig config = SkillTaskConfig.builder()
                .skill(Skill.WOODCUTTING)
                .targetLevel(50)
                .method(method)
                .build();

        IdealInventory ideal = config.getOrCreateIdealInventory(mockPlayerState);

        assertNotNull(ideal);
        assertTrue(ideal.hasRequiredItems());
        assertTrue(ideal.isDepositInventoryFirst());
    }

    @Test
    public void testGetOrCreateIdealInventory_ExplicitOverride() {
        TrainingMethod method = createWoodcuttingMethod();

        // Create custom ideal inventory
        IdealInventory customIdeal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(BRONZE_AXE_ID, EquipPreference.MUST_EQUIP))
                .depositInventoryFirst(false)
                .build();

        SkillTaskConfig config = SkillTaskConfig.builder()
                .skill(Skill.WOODCUTTING)
                .targetLevel(50)
                .method(method)
                .idealInventory(customIdeal)
                .build();

        IdealInventory ideal = config.getOrCreateIdealInventory(mockPlayerState);

        // Should return custom ideal, not derived one
        assertSame(customIdeal, ideal);
        assertFalse(ideal.isDepositInventoryFirst());
    }

    @Test
    public void testGetOrCreateIdealInventory_SkipPreparation() {
        TrainingMethod method = createWoodcuttingMethod();

        SkillTaskConfig config = SkillTaskConfig.builder()
                .skill(Skill.WOODCUTTING)
                .targetLevel(50)
                .method(method)
                .skipInventoryPreparation(true)
                .build();

        IdealInventory ideal = config.getOrCreateIdealInventory(mockPlayerState);

        // Should return IdealInventory.none()
        assertNotNull(ideal);
        assertFalse(ideal.isDepositInventoryFirst());
        assertFalse(ideal.isDepositEquipmentFirst());
        assertTrue(ideal.isKeepExistingItems());
    }

    // ========================================================================
    // IdealInventoryFactory Integration Tests
    // ========================================================================

    @Test
    public void testIdealInventoryFactory_ForWoodcutting() {
        IdealInventory ideal = IdealInventoryFactory.forWoodcutting(true);

        assertNotNull(ideal);
        assertTrue(ideal.hasRequiredItems());
        assertEquals(1, ideal.getRequiredItems().size());

        // Should have axe collection
        InventorySlotSpec axeSpec = ideal.getRequiredItems().get(0);
        assertTrue(axeSpec.isCollectionBased());
        assertEquals(Skill.WOODCUTTING, axeSpec.getSkillForLevelCheck());
    }

    @Test
    public void testIdealInventoryFactory_ForMining() {
        IdealInventory ideal = IdealInventoryFactory.forMining(true);

        assertNotNull(ideal);
        assertTrue(ideal.hasRequiredItems());

        // Should have pickaxe collection
        InventorySlotSpec pickSpec = ideal.getRequiredItems().get(0);
        assertTrue(pickSpec.isCollectionBased());
        assertEquals(Skill.MINING, pickSpec.getSkillForLevelCheck());
    }

    @Test
    public void testIdealInventoryFactory_FromTrainingMethod() {
        TrainingMethod method = createWoodcuttingMethod();

        IdealInventory ideal = IdealInventoryFactory.fromTrainingMethod(method, mockPlayerState);

        assertNotNull(ideal);
        assertTrue(ideal.hasRequiredItems());
    }

    // ========================================================================
    // Tool Selection Integration Tests
    // ========================================================================

    @Test
    public void testToolSelection_SelectsBestAvailable() {
        // Bank has both bronze and rune axes
        when(mockBank.hasItem(BRONZE_AXE_ID)).thenReturn(true);
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(true);

        InventorySlotSpec axeSpec = InventorySlotSpec.forToolCollection(
                ItemCollections.AXES,
                ItemCollections.AXE_LEVELS,
                Skill.WOODCUTTING
        );

        var selectedId = inventoryPreparation.getSelectedToolId(axeSpec, taskContext);

        assertTrue(selectedId.isPresent());
        // With 50 WC, should select rune axe
        assertEquals(Integer.valueOf(RUNE_AXE_ID), selectedId.get());
    }

    @Test
    public void testToolSelection_FallsBackToLowerTier() {
        // Only bronze axe available
        when(mockBank.hasItem(BRONZE_AXE_ID)).thenReturn(true);
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(false);

        InventorySlotSpec axeSpec = InventorySlotSpec.forToolCollection(
                ItemCollections.AXES,
                ItemCollections.AXE_LEVELS,
                Skill.WOODCUTTING
        );

        var selectedId = inventoryPreparation.getSelectedToolId(axeSpec, taskContext);

        assertTrue(selectedId.isPresent());
        // Should fall back to bronze
        assertEquals(Integer.valueOf(BRONZE_AXE_ID), selectedId.get());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private TrainingMethod createWoodcuttingMethod() {
        return TrainingMethod.builder()
                .id("oak_trees_powerchop")
                .name("Oak Trees (Power)")
                .skill(Skill.WOODCUTTING)
                .methodType(MethodType.GATHER)
                .minLevel(15)
                .xpPerAction(37.5)
                .requiresInventorySpace(true)
                .dropWhenFull(true)
                .requiredItemIds(ItemCollections.AXES) // Any axe
                .locations(List.of(
                        MethodLocation.builder()
                                .id("varrock_west")
                                .name("Varrock West Bank")
                                .exactPosition(new WorldPoint(3165, 3415, 0))
                                .build()
                ))
                .build();
    }
}

