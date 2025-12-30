package com.rocinante.inventory;

import com.rocinante.state.BankState;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.CompositeTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.EquipItemTask;
import com.rocinante.tasks.impl.ResupplyTask;
import com.rocinante.util.ItemCollections;
import net.runelite.api.Item;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InventoryPreparation service.
 */
public class InventoryPreparationTest {

    @Mock private TaskContext mockContext;
    @Mock private PlayerState mockPlayerState;
    @Mock private InventoryState mockInventory;
    @Mock private EquipmentState mockEquipment;
    @Mock private BankState mockBank;
    @Mock private Item mockRuneAxe;
    @Mock private Item mockBronzeAxe;
    @Mock private Item mockShark;

    private InventoryPreparation inventoryPreparation;

    private static final int BRONZE_AXE_ID = ItemID.BRONZE_AXE;
    private static final int IRON_AXE_ID = ItemID.IRON_AXE;
    private static final int RUNE_AXE_ID = ItemID.RUNE_AXE;
    private static final int SHARK_ID = ItemID.SHARK;
    private static final int RANDOM_JUNK_ID = 12345;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        inventoryPreparation = new InventoryPreparation();

        // Setup mock items
        when(mockRuneAxe.getId()).thenReturn(RUNE_AXE_ID);
        when(mockRuneAxe.getQuantity()).thenReturn(1);

        when(mockBronzeAxe.getId()).thenReturn(BRONZE_AXE_ID);
        when(mockBronzeAxe.getQuantity()).thenReturn(1);

        when(mockShark.getId()).thenReturn(SHARK_ID);
        when(mockShark.getQuantity()).thenReturn(1);

        // Setup mock context
        when(mockContext.getPlayerState()).thenReturn(mockPlayerState);
        when(mockContext.getInventoryState()).thenReturn(mockInventory);
        when(mockContext.getEquipmentState()).thenReturn(mockEquipment);
        when(mockContext.getBankState()).thenReturn(mockBank);

        // Setup mock player with skill levels
        Map<Skill, Integer> skillLevels = new HashMap<>();
        skillLevels.put(Skill.WOODCUTTING, 50);
        skillLevels.put(Skill.ATTACK, 50);
        skillLevels.put(Skill.AGILITY, 50);
        when(mockPlayerState.getBaseSkillLevels()).thenReturn(skillLevels);

        // Default: empty inventory
        when(mockInventory.isEmpty()).thenReturn(true);
        when(mockInventory.hasItem(anyInt())).thenReturn(false);
        when(mockInventory.getAllItems()).thenReturn(java.util.Collections.emptyList());
        when(mockInventory.getUsedSlots()).thenReturn(0);
        when(mockInventory.getFreeSlots()).thenReturn(28);

        // Default: nothing equipped
        when(mockEquipment.hasEquipped(anyInt())).thenReturn(false);

        // Default: items in bank
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(true);
        when(mockBank.hasItem(SHARK_ID)).thenReturn(true);
        when(mockBank.isUnknown()).thenReturn(false);
    }

    // ========================================================================
    // isInventoryReady Tests
    // ========================================================================

    @Test
    public void testIsInventoryReady_NullIdeal() {
        assertTrue(inventoryPreparation.isInventoryReady(null, mockContext));
    }

    @Test
    public void testIsInventoryReady_NoneIdeal() {
        IdealInventory none = IdealInventory.none();
        assertTrue(inventoryPreparation.isInventoryReady(none, mockContext));
    }

    @Test
    public void testIsInventoryReady_WhenItemAlreadyInInventory() {
        // Item already in inventory
        when(mockInventory.hasItem(RUNE_AXE_ID)).thenReturn(true);
        when(mockInventory.isEmpty()).thenReturn(false);
        when(mockInventory.getAllItems()).thenReturn(List.of(mockRuneAxe));
        when(mockInventory.getUsedSlots()).thenReturn(1);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID))
                .depositInventoryFirst(false)
                .build();

        assertTrue(inventoryPreparation.isInventoryReady(ideal, mockContext));
    }

    @Test
    public void testIsInventoryReady_WhenItemAlreadyEquipped() {
        // Item already equipped
        when(mockEquipment.hasEquipped(RUNE_AXE_ID)).thenReturn(true);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID, EquipPreference.PREFER_EQUIP))
                .depositInventoryFirst(false)
                .build();

        assertTrue(inventoryPreparation.isInventoryReady(ideal, mockContext));
    }

    @Test
    public void testIsInventoryReady_WhenItemNeedsWithdraw() {
        // Item is in bank, not inventory
        when(mockInventory.hasItem(RUNE_AXE_ID)).thenReturn(false);
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(true);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID))
                .depositInventoryFirst(false)
                .build();

        assertFalse(inventoryPreparation.isInventoryReady(ideal, mockContext));
    }

    @Test
    public void testIsInventoryReady_ReturnsFalseWhenMissingRequiredItem() {
        // Item is not available anywhere
        when(mockInventory.hasItem(RUNE_AXE_ID)).thenReturn(false);
        when(mockEquipment.hasEquipped(RUNE_AXE_ID)).thenReturn(false);
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(false);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID))
                .failOnMissingItems(true)
                .build();

        // Should return false because required item is missing
        assertFalse(inventoryPreparation.isInventoryReady(ideal, mockContext));
    }

    // ========================================================================
    // prepareInventory Tests
    // ========================================================================

    @Test
    public void testPrepareInventory_ReturnsNullForNullIdeal() {
        assertNull(inventoryPreparation.prepareInventory(null, mockContext));
    }

    @Test
    public void testPrepareInventory_ReturnsNullWhenAlreadyReady() {
        // Item already in inventory
        when(mockInventory.hasItem(RUNE_AXE_ID)).thenReturn(true);
        when(mockInventory.isEmpty()).thenReturn(false);
        when(mockInventory.getAllItems()).thenReturn(List.of(mockRuneAxe));
        when(mockInventory.getUsedSlots()).thenReturn(1);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID))
                .depositInventoryFirst(false)
                .keepExistingItems(true)
                .build();

        assertNull(inventoryPreparation.prepareInventory(ideal, mockContext));
    }

    @Test
    public void testPrepareInventory_GeneratesResupplyTask() {
        // Need to withdraw rune axe from bank
        when(mockInventory.hasItem(RUNE_AXE_ID)).thenReturn(false);
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(true);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID))
                .depositInventoryFirst(false)
                .build();

        Task task = inventoryPreparation.prepareInventory(ideal, mockContext);

        assertNotNull(task);
        // Should be a ResupplyTask (or CompositeTask containing one)
        assertTrue(task instanceof ResupplyTask || task instanceof CompositeTask);
    }

    @Test
    public void testPrepareInventory_GeneratesEquipTask() {
        // Have axe in inventory, need to equip it
        when(mockInventory.hasItem(RUNE_AXE_ID)).thenReturn(true);
        when(mockInventory.isEmpty()).thenReturn(false);
        when(mockInventory.getAllItems()).thenReturn(List.of(mockRuneAxe));
        when(mockEquipment.hasEquipped(RUNE_AXE_ID)).thenReturn(false);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID, EquipPreference.PREFER_EQUIP))
                .depositInventoryFirst(false)
                .build();

        Task task = inventoryPreparation.prepareInventory(ideal, mockContext);

        assertNotNull(task);
        // Should include equip task
        assertTrue(task instanceof EquipItemTask || task instanceof CompositeTask);
    }

    @Test
    public void testPrepareInventory_HandlesCollectionBasedSpec() {
        // Test with axe collection - should select best available
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(true);
        when(mockBank.hasItem(BRONZE_AXE_ID)).thenReturn(true);

        InventorySlotSpec axeSpec = InventorySlotSpec.forToolCollection(
                ItemCollections.AXES,
                ItemCollections.AXE_LEVELS,
                Skill.WOODCUTTING,
                EquipPreference.PREFER_EQUIP
        );

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(axeSpec)
                .depositInventoryFirst(true)
                .build();

        Task task = inventoryPreparation.prepareInventory(ideal, mockContext);

        // Should generate task to get the best axe (rune at 50 WC)
        assertNotNull(task);
    }

    @Test
    public void testPrepareInventory_WithDeposit() {
        // Inventory has unwanted items
        Item unwantedItem = mock(Item.class);
        when(unwantedItem.getId()).thenReturn(RANDOM_JUNK_ID);
        when(mockInventory.isEmpty()).thenReturn(false);
        when(mockInventory.getAllItems()).thenReturn(List.of(unwantedItem));
        when(mockInventory.getUsedSlots()).thenReturn(1);

        // Need rune axe
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(true);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID))
                .depositInventoryFirst(true)
                .keepExistingItems(false)
                .build();

        Task task = inventoryPreparation.prepareInventory(ideal, mockContext);

        assertNotNull(task);
    }

    @Test(expected = IllegalStateException.class)
    public void testPrepareInventory_ThrowsWhenMissingRequiredItem() {
        // Item is not available anywhere
        when(mockInventory.hasItem(RUNE_AXE_ID)).thenReturn(false);
        when(mockEquipment.hasEquipped(RUNE_AXE_ID)).thenReturn(false);
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(false);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID))
                .failOnMissingItems(true)
                .build();

        // Should throw IllegalStateException
        inventoryPreparation.prepareInventory(ideal, mockContext);
    }

    @Test
    public void testPrepareInventory_DoesNotThrowWhenFailOnMissingIsFalse() {
        // Item is not available anywhere
        when(mockInventory.hasItem(RUNE_AXE_ID)).thenReturn(false);
        when(mockEquipment.hasEquipped(RUNE_AXE_ID)).thenReturn(false);
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(false);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID))
                .failOnMissingItems(false)
                .build();

        // Should NOT throw - just return null since nothing can be done
        Task task = inventoryPreparation.prepareInventory(ideal, mockContext);
        assertNull(task);
    }

    // ========================================================================
    // analyzeAndPrepare Tests
    // ========================================================================

    @Test
    public void testAnalyzeAndPrepare_ReturnsMissingItems() {
        // Item is not available anywhere
        when(mockInventory.hasItem(RUNE_AXE_ID)).thenReturn(false);
        when(mockEquipment.hasEquipped(RUNE_AXE_ID)).thenReturn(false);
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(false);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID))
                .failOnMissingItems(true)
                .build();

        InventoryPreparation.PreparationResult result = 
                inventoryPreparation.analyzeAndPrepare(ideal, mockContext);

        assertTrue(result.isFailed());
        assertFalse(result.getMissingItems().isEmpty());
        assertNull(result.getTask());
    }

    @Test
    public void testAnalyzeAndPrepare_ReturnsReadyWhenAlreadyPrepared() {
        // Item already equipped
        when(mockEquipment.hasEquipped(RUNE_AXE_ID)).thenReturn(true);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID, EquipPreference.MUST_EQUIP))
                .depositInventoryFirst(false)
                .build();

        InventoryPreparation.PreparationResult result = 
                inventoryPreparation.analyzeAndPrepare(ideal, mockContext);

        assertTrue(result.isReady());
        assertFalse(result.isFailed());
        assertNull(result.getTask());
    }

    // ========================================================================
    // PREFER_INVENTORY Tests
    // ========================================================================

    @Test
    public void testPreferInventory_AcceptsEquippedItemAsAccessible() {
        // Item is equipped, but we prefer it in inventory
        // Since we don't have unequip, we should accept it
        when(mockEquipment.hasEquipped(RUNE_AXE_ID)).thenReturn(true);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID, EquipPreference.PREFER_INVENTORY))
                .depositInventoryFirst(false)
                .build();

        // Should be ready since item is accessible (even if in wrong location)
        assertTrue(inventoryPreparation.isInventoryReady(ideal, mockContext));
    }

    // ========================================================================
    // Fill Calculation Tests
    // ========================================================================

    @Test
    public void testFillCalculation_AccountsForExistingItems() {
        // Already have 5 sharks in inventory
        when(mockInventory.hasItem(SHARK_ID)).thenReturn(true);
        when(mockInventory.countItem(SHARK_ID)).thenReturn(5);
        when(mockInventory.isEmpty()).thenReturn(false);
        when(mockInventory.getUsedSlots()).thenReturn(5);

        // Also need a tool (rune axe) - 1 slot
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(true);
        when(mockBank.hasItem(SHARK_ID)).thenReturn(true);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID, EquipPreference.MUST_EQUIP))
                .fillRestWithItemId(SHARK_ID)
                .depositInventoryFirst(false)
                .keepExistingItems(true)
                .build();

        Task task = inventoryPreparation.prepareInventory(ideal, mockContext);

        // Should generate a task (withdraw tool + fill with more sharks)
        assertNotNull(task);
    }

    @Test
    public void testFillCalculation_DoesNotOverfill() {
        // Already have 27 sharks in inventory
        when(mockInventory.hasItem(SHARK_ID)).thenReturn(true);
        when(mockInventory.countItem(SHARK_ID)).thenReturn(27);
        when(mockInventory.isEmpty()).thenReturn(false);
        when(mockInventory.getUsedSlots()).thenReturn(27);

        // Request fill with sharks but inventory is almost full
        // Need to withdraw a tool (1 slot) and fill rest with sharks
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(true);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID, EquipPreference.MUST_EQUIP))
                .fillRestWithItemId(SHARK_ID)
                .depositInventoryFirst(true) // Will deposit sharks first
                .keepExistingItems(false)
                .build();

        Task task = inventoryPreparation.prepareInventory(ideal, mockContext);

        // Should still generate a task
        assertNotNull(task);
    }

    // ========================================================================
    // getSelectedToolId Tests
    // ========================================================================

    @Test
    public void testGetSelectedToolId_ReturnsHighestAvailable() {
        // Bank has bronze and rune axes
        when(mockBank.hasItem(BRONZE_AXE_ID)).thenReturn(true);
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(true);

        InventorySlotSpec axeSpec = InventorySlotSpec.forToolCollection(
                ItemCollections.AXES,
                ItemCollections.AXE_LEVELS,
                Skill.WOODCUTTING
        );

        var result = inventoryPreparation.getSelectedToolId(axeSpec, mockContext);

        assertTrue(result.isPresent());
        assertEquals(Integer.valueOf(RUNE_AXE_ID), result.get());
    }

    @Test
    public void testGetSelectedToolId_ReturnsEmptyWhenNoneAvailable() {
        // No axes available anywhere
        when(mockInventory.hasItem(anyInt())).thenReturn(false);
        when(mockEquipment.hasEquipped(anyInt())).thenReturn(false);
        when(mockBank.hasItem(anyInt())).thenReturn(false);

        InventorySlotSpec axeSpec = InventorySlotSpec.forToolCollection(
                ItemCollections.AXES,
                ItemCollections.AXE_LEVELS,
                Skill.WOODCUTTING
        );

        var result = inventoryPreparation.getSelectedToolId(axeSpec, mockContext);

        assertFalse(result.isPresent());
    }

    @Test
    public void testGetSelectedToolId_SpecificItem() {
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(true);

        InventorySlotSpec spec = InventorySlotSpec.forItem(RUNE_AXE_ID);

        var result = inventoryPreparation.getSelectedToolId(spec, mockContext);

        assertTrue(result.isPresent());
        assertEquals(Integer.valueOf(RUNE_AXE_ID), result.get());
    }

    @Test
    public void testGetSelectedToolId_SpecificItemNotAvailable() {
        when(mockInventory.hasItem(RUNE_AXE_ID)).thenReturn(false);
        when(mockEquipment.hasEquipped(RUNE_AXE_ID)).thenReturn(false);
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(false);

        InventorySlotSpec spec = InventorySlotSpec.forItem(RUNE_AXE_ID);

        var result = inventoryPreparation.getSelectedToolId(spec, mockContext);

        assertFalse(result.isPresent());
    }

    // ========================================================================
    // Unwanted Items Tests
    // ========================================================================

    @Test
    public void testUnwantedItems_DoesNotFlagItemsToBeEquipped() {
        // Have a rune axe in inventory that will be equipped
        when(mockInventory.hasItem(RUNE_AXE_ID)).thenReturn(true);
        when(mockInventory.isEmpty()).thenReturn(false);
        when(mockInventory.getAllItems()).thenReturn(List.of(mockRuneAxe));
        when(mockInventory.getUsedSlots()).thenReturn(1);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID, EquipPreference.PREFER_EQUIP))
                .depositInventoryFirst(true)
                .keepExistingItems(false)
                .build();

        Task task = inventoryPreparation.prepareInventory(ideal, mockContext);

        // Should generate an equip task, not a deposit task
        assertNotNull(task);
        assertTrue(task instanceof EquipItemTask);
    }

    @Test
    public void testUnwantedItems_FlagsActuallyUnwantedItems() {
        // Have junk item in inventory
        Item junkItem = mock(Item.class);
        when(junkItem.getId()).thenReturn(RANDOM_JUNK_ID);

        when(mockInventory.isEmpty()).thenReturn(false);
        when(mockInventory.getAllItems()).thenReturn(List.of(junkItem));
        when(mockInventory.getUsedSlots()).thenReturn(1);

        // Want rune axe from bank
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(true);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID))
                .depositInventoryFirst(true)
                .keepExistingItems(false)
                .build();

        Task task = inventoryPreparation.prepareInventory(ideal, mockContext);

        // Should generate a task that includes deposit
        assertNotNull(task);
        assertTrue(task instanceof ResupplyTask || task instanceof CompositeTask);
    }

    // ========================================================================
    // Optional Item Tests
    // ========================================================================

    @Test
    public void testOptionalItem_DoesNotCauseFail() {
        // Optional item is not available
        when(mockInventory.hasItem(SHARK_ID)).thenReturn(false);
        when(mockBank.hasItem(SHARK_ID)).thenReturn(false);

        // Required item is available
        when(mockBank.hasItem(RUNE_AXE_ID)).thenReturn(true);

        IdealInventory ideal = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID))
                .requiredItem(InventorySlotSpec.builder()
                        .itemId(SHARK_ID)
                        .optional(true)
                        .build())
                .failOnMissingItems(true)
                .build();

        // Should NOT throw - optional item missing is OK
        Task task = inventoryPreparation.prepareInventory(ideal, mockContext);
        assertNotNull(task); // Should still generate task to get the required axe
    }
}
