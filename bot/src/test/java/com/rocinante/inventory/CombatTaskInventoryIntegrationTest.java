package com.rocinante.inventory;

import com.rocinante.combat.TargetSelectorConfig;
import com.rocinante.core.GameStateService;
import com.rocinante.state.BankState;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.CombatTaskConfig;
import com.rocinante.timing.HumanTimer;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.inject.Provider;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for CombatTask inventory preparation using IdealInventory system.
 */
public class CombatTaskInventoryIntegrationTest {

    @Mock private Client mockClient;
    @Mock private GameStateService mockGameStateService;
    @Mock private PlayerState mockPlayerState;
    @Mock private InventoryState mockInventory;
    @Mock private EquipmentState mockEquipment;
    @Mock private BankState mockBank;
    @Mock private HumanTimer mockHumanTimer;

    private InventoryPreparation inventoryPreparation;
    private TaskContext taskContext;

    private static final int SHARK_ID = ItemID.SHARK;
    private static final int LOBSTER_ID = ItemID.LOBSTER;
    private static final int RUNE_SCIMITAR_ID = ItemID.RUNE_SCIMITAR;
    private static final int SUPER_COMBAT_POTION_4 = ItemID.SUPER_COMBAT_POTION4;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        inventoryPreparation = new InventoryPreparation();

        // Setup mock player with skill levels
        Map<Skill, Integer> skillLevels = new HashMap<>();
        skillLevels.put(Skill.ATTACK, 50);
        skillLevels.put(Skill.STRENGTH, 50);
        skillLevels.put(Skill.DEFENCE, 50);
        when(mockPlayerState.getBaseSkillLevels()).thenReturn(skillLevels);
        when(mockPlayerState.getWorldPosition()).thenReturn(new WorldPoint(3000, 3000, 0));
        when(mockPlayerState.isValid()).thenReturn(true);

        // Setup mock game state service
        when(mockGameStateService.getPlayerState()).thenReturn(mockPlayerState);
        when(mockGameStateService.getInventoryState()).thenReturn(mockInventory);
        when(mockGameStateService.getEquipmentState()).thenReturn(mockEquipment);
        when(mockGameStateService.getBankState()).thenReturn(mockBank);

        // Default: empty inventory
        when(mockInventory.isEmpty()).thenReturn(true);
        when(mockInventory.hasItem(anyInt())).thenReturn(false);
        when(mockInventory.isFull()).thenReturn(false);

        // Default: nothing equipped
        when(mockEquipment.hasEquipped(anyInt())).thenReturn(false);

        // Default: bank has food and supplies
        when(mockBank.hasItem(SHARK_ID)).thenReturn(true);
        when(mockBank.hasItem(LOBSTER_ID)).thenReturn(true);
        when(mockBank.hasItem(RUNE_SCIMITAR_ID)).thenReturn(true);
        when(mockBank.hasItem(SUPER_COMBAT_POTION_4)).thenReturn(true);
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
                null, // navigationService
                inventoryPreparation,
                null  // questService
        );
    }

    // ========================================================================
    // CombatTaskConfig.getOrCreateIdealInventory Tests
    // ========================================================================

    @Test
    public void testGetOrCreateIdealInventory_FromResupplyItems() {
        CombatTaskConfig config = CombatTaskConfig.builder()
                .targetConfig(TargetSelectorConfig.forNpcIds(100))
                .killCount(10)
                .maxDuration(Duration.ofMinutes(30))
                .enableResupply(true)
                .resupplyItem(SHARK_ID, 20)
                .resupplyItem(SUPER_COMBAT_POTION_4, 2)
                .build();

        IdealInventory ideal = config.getOrCreateIdealInventory();

        assertNotNull(ideal);
        assertTrue(ideal.hasRequiredItems());
        // Should have 2 items: sharks and potions
        assertEquals(2, ideal.getRequiredItems().size());
        // Should fill with first item (sharks)
        assertTrue(ideal.hasFillItem());
        assertEquals(Integer.valueOf(SHARK_ID), ideal.getFillRestWithItemId());
    }

    @Test
    public void testGetOrCreateIdealInventory_ExplicitOverride() {
        IdealInventory customIdeal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_SCIMITAR_ID, EquipPreference.MUST_EQUIP))
                .requiredItem(InventorySlotSpec.forItems(LOBSTER_ID, 15))
                .fillRestWithItemId(LOBSTER_ID)
                .depositInventoryFirst(true)
                .build();

        CombatTaskConfig config = CombatTaskConfig.builder()
                .targetConfig(TargetSelectorConfig.forNpcIds(100))
                .killCount(10)
                .idealInventory(customIdeal)
                .build();

        IdealInventory ideal = config.getOrCreateIdealInventory();

        // Should return custom ideal
        assertSame(customIdeal, ideal);
        assertEquals(2, ideal.getRequiredItems().size());
    }

    @Test
    public void testGetOrCreateIdealInventory_SkipPreparation() {
        CombatTaskConfig config = CombatTaskConfig.builder()
                .targetConfig(TargetSelectorConfig.forNpcIds(100))
                .killCount(10)
                .skipInventoryPreparation(true)
                .resupplyItem(SHARK_ID, 20) // Would normally create ideal
                .build();

        IdealInventory ideal = config.getOrCreateIdealInventory();

        // Should return IdealInventory.none() due to skip flag
        assertFalse(ideal.isDepositInventoryFirst());
        assertFalse(ideal.hasRequiredItems());
    }

    @Test
    public void testGetOrCreateIdealInventory_NoResupplyItems() {
        CombatTaskConfig config = CombatTaskConfig.builder()
                .targetConfig(TargetSelectorConfig.forNpcIds(100))
                .killCount(10)
                .enableResupply(false)
                .build();

        IdealInventory ideal = config.getOrCreateIdealInventory();

        // Should return IdealInventory.none() when no resupply items
        assertFalse(ideal.isDepositInventoryFirst());
        assertFalse(ideal.hasRequiredItems());
    }

    // ========================================================================
    // IdealInventoryFactory Combat Methods
    // ========================================================================

    @Test
    public void testIdealInventoryFactory_ForCombat() {
        IdealInventory ideal = IdealInventoryFactory.forCombat(RUNE_SCIMITAR_ID, SHARK_ID, 10);

        assertNotNull(ideal);
        assertTrue(ideal.hasRequiredItems());
        assertEquals(2, ideal.getRequiredItems().size());

        // First item should be weapon with MUST_EQUIP
        InventorySlotSpec weaponSpec = ideal.getRequiredItems().get(0);
        assertEquals(Integer.valueOf(RUNE_SCIMITAR_ID), weaponSpec.getItemId());
        assertEquals(EquipPreference.MUST_EQUIP, weaponSpec.getEquipPreference());

        // Second item should be food
        InventorySlotSpec foodSpec = ideal.getRequiredItems().get(1);
        assertEquals(Integer.valueOf(SHARK_ID), foodSpec.getItemId());
        assertEquals(10, foodSpec.getQuantity());

        // Should fill rest with food
        assertTrue(ideal.hasFillItem());
        assertEquals(Integer.valueOf(SHARK_ID), ideal.getFillRestWithItemId());
    }

    @Test
    public void testIdealInventoryFactory_ForCombatWithPotions() {
        IdealInventory ideal = IdealInventoryFactory.forCombatWithPotions(
                RUNE_SCIMITAR_ID, SHARK_ID, 15, SUPER_COMBAT_POTION_4, 2);

        assertNotNull(ideal);
        assertTrue(ideal.hasRequiredItems());
        assertEquals(3, ideal.getRequiredItems().size());

        // Should have weapon, potions, and food
        assertTrue(ideal.hasFillItem());
    }

    // ========================================================================
    // InventoryPreparation Integration
    // ========================================================================

    @Test
    public void testInventoryPreparation_ForCombatConfig() {
        CombatTaskConfig config = CombatTaskConfig.builder()
                .targetConfig(TargetSelectorConfig.forNpcIds(100))
                .killCount(10)
                .enableResupply(true)
                .resupplyItem(SHARK_ID, 20)
                .build();

        IdealInventory ideal = config.getOrCreateIdealInventory();

        // Since inventory is empty and items are in bank, should need preparation
        assertFalse(inventoryPreparation.isInventoryReady(ideal, taskContext));
    }

    @Test
    public void testInventoryPreparation_AlreadyReady() {
        // Setup: player already has sharks in inventory
        when(mockInventory.hasItem(SHARK_ID)).thenReturn(true);
        when(mockInventory.countItem(SHARK_ID)).thenReturn(20);

        CombatTaskConfig config = CombatTaskConfig.builder()
                .targetConfig(TargetSelectorConfig.forNpcIds(100))
                .killCount(10)
                .enableResupply(true)
                .resupplyItem(SHARK_ID, 20)
                .idealInventory(IdealInventory.builder()
                        .requiredItem(InventorySlotSpec.forItems(SHARK_ID, 20))
                        .depositInventoryFirst(false)
                        .keepExistingItems(true)
                        .build())
                .build();

        IdealInventory ideal = config.getOrCreateIdealInventory();

        // Should be ready since we have the food
        assertTrue(inventoryPreparation.isInventoryReady(ideal, taskContext));
    }
}

