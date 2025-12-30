package com.rocinante.inventory;

import com.rocinante.util.ItemCollections;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for IdealInventory and InventorySlotSpec classes.
 */
public class IdealInventoryTest {

    private static final int RUNE_AXE_ID = ItemID.RUNE_AXE;
    private static final int SHARK_ID = ItemID.SHARK;
    private static final int LOBSTER_ID = ItemID.LOBSTER;
    private static final int RUNE_SCIMITAR_ID = ItemID.RUNE_SCIMITAR;

    // ========================================================================
    // InventorySlotSpec Factory Method Tests
    // ========================================================================

    @Test
    public void testForItem_Basic() {
        InventorySlotSpec spec = InventorySlotSpec.forItem(RUNE_AXE_ID);

        assertTrue(spec.isSpecificItem());
        assertFalse(spec.isCollectionBased());
        assertEquals(Integer.valueOf(RUNE_AXE_ID), spec.getItemId());
        assertEquals(1, spec.getQuantity());
        assertEquals(EquipPreference.TASK_DEFAULT, spec.getEquipPreference());
    }

    @Test
    public void testForItem_WithPreference() {
        InventorySlotSpec spec = InventorySlotSpec.forItem(RUNE_AXE_ID, EquipPreference.MUST_EQUIP);

        assertEquals(Integer.valueOf(RUNE_AXE_ID), spec.getItemId());
        assertEquals(EquipPreference.MUST_EQUIP, spec.getEquipPreference());
    }

    @Test
    public void testForItems_Multiple() {
        InventorySlotSpec spec = InventorySlotSpec.forItems(SHARK_ID, 10);

        assertEquals(Integer.valueOf(SHARK_ID), spec.getItemId());
        assertEquals(10, spec.getQuantity());
        assertEquals(EquipPreference.PREFER_INVENTORY, spec.getEquipPreference());
    }

    @Test
    public void testForToolCollection() {
        InventorySlotSpec spec = InventorySlotSpec.forToolCollection(
                ItemCollections.AXES,
                ItemCollections.AXE_LEVELS,
                Skill.WOODCUTTING
        );

        assertFalse(spec.isSpecificItem());
        assertTrue(spec.isCollectionBased());
        assertTrue(spec.hasLevelRequirements());
        assertEquals(Skill.WOODCUTTING, spec.getSkillForLevelCheck());
        assertEquals(EquipPreference.PREFER_EQUIP, spec.getEquipPreference());
    }

    @Test
    public void testForAnyFromCollection() {
        List<Integer> foodItems = List.of(SHARK_ID, LOBSTER_ID);
        InventorySlotSpec spec = InventorySlotSpec.forAnyFromCollection(foodItems, 5);

        assertTrue(spec.isCollectionBased());
        assertFalse(spec.hasLevelRequirements());
        assertEquals(5, spec.getQuantity());
        assertEquals(EquipPreference.PREFER_INVENTORY, spec.getEquipPreference());
    }

    // ========================================================================
    // InventorySlotSpec Utility Method Tests
    // ========================================================================

    @Test
    public void testGetEffectiveEquipPreference_TaskDefault_Tool() {
        InventorySlotSpec spec = InventorySlotSpec.builder()
                .itemCollection(ItemCollections.AXES)
                .equipPreference(EquipPreference.TASK_DEFAULT)
                .build();

        // Tools default to PREFER_EQUIP
        assertEquals(EquipPreference.PREFER_EQUIP, spec.getEffectiveEquipPreference(true));
    }

    @Test
    public void testGetEffectiveEquipPreference_TaskDefault_Consumable() {
        InventorySlotSpec spec = InventorySlotSpec.builder()
                .itemId(SHARK_ID)
                .quantity(10)
                .equipPreference(EquipPreference.TASK_DEFAULT)
                .build();

        // Multiple items default to PREFER_INVENTORY
        assertEquals(EquipPreference.PREFER_INVENTORY, spec.getEffectiveEquipPreference(false));
    }

    @Test
    public void testGetEffectiveEquipPreference_Explicit() {
        InventorySlotSpec spec = InventorySlotSpec.builder()
                .itemId(RUNE_AXE_ID)
                .equipPreference(EquipPreference.MUST_EQUIP)
                .build();

        // Explicit preference is honored
        assertEquals(EquipPreference.MUST_EQUIP, spec.getEffectiveEquipPreference(true));
    }

    // ========================================================================
    // IdealInventory Factory Method Tests
    // ========================================================================

    @Test
    public void testNone_ReturnsSkipSpec() {
        IdealInventory none = IdealInventory.none();

        assertFalse(none.isDepositInventoryFirst());
        assertFalse(none.isDepositEquipmentFirst());
        assertTrue(none.isKeepExistingItems());
        assertFalse(none.isFailOnMissingItems());
        assertFalse(none.isValidateAfterPreparation());
    }

    @Test
    public void testEmptyInventory_DepositsButNothingElse() {
        IdealInventory empty = IdealInventory.emptyInventory();

        assertTrue(empty.isDepositInventoryFirst());
        assertFalse(empty.isDepositEquipmentFirst());
        assertFalse(empty.isKeepExistingItems());
        assertFalse(empty.hasRequiredItems());
    }

    @Test
    public void testForTool_HasOneTool() {
        InventorySlotSpec axeSpec = InventorySlotSpec.forToolCollection(
                ItemCollections.AXES,
                ItemCollections.AXE_LEVELS,
                Skill.WOODCUTTING
        );
        IdealInventory inv = IdealInventory.forTool(axeSpec);

        assertTrue(inv.hasRequiredItems());
        assertEquals(1, inv.getRequiredItems().size());
        assertTrue(inv.isDepositInventoryFirst());
    }

    @Test
    public void testForCombat_HasWeaponAndFood() {
        InventorySlotSpec weaponSpec = InventorySlotSpec.forItem(RUNE_SCIMITAR_ID, EquipPreference.MUST_EQUIP);
        IdealInventory inv = IdealInventory.forCombat(weaponSpec, SHARK_ID, 10);

        assertTrue(inv.hasRequiredItems());
        assertEquals(2, inv.getRequiredItems().size());
        assertTrue(inv.hasFillItem());
        assertEquals(Integer.valueOf(SHARK_ID), inv.getFillRestWithItemId());
    }

    // ========================================================================
    // IdealInventory Builder Tests
    // ========================================================================

    @Test
    public void testBuilder_SingleItem() {
        IdealInventory inv = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID))
                .depositInventoryFirst(true)
                .build();

        assertTrue(inv.hasRequiredItems());
        assertEquals(1, inv.getRequiredItems().size());
        assertTrue(inv.isDepositInventoryFirst());
    }

    @Test
    public void testBuilder_MultipleItems() {
        IdealInventory inv = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_SCIMITAR_ID, EquipPreference.MUST_EQUIP))
                .requiredItem(InventorySlotSpec.forItems(SHARK_ID, 10))
                .depositInventoryFirst(true)
                .build();

        assertEquals(2, inv.getRequiredItems().size());
    }

    @Test
    public void testBuilder_WithFillRest() {
        IdealInventory inv = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_SCIMITAR_ID))
                .fillRestWithItemId(SHARK_ID)
                .fillRestMaxQuantity(20)
                .build();

        assertTrue(inv.hasFillItem());
        assertEquals(Integer.valueOf(SHARK_ID), inv.getFillRestWithItemId());
        assertEquals(20, inv.getFillRestMaxQuantity());
    }

    // ========================================================================
    // IdealInventory Utility Method Tests
    // ========================================================================

    @Test
    public void testGetMinimumSlotsNeeded() {
        IdealInventory inv = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_SCIMITAR_ID, EquipPreference.MUST_EQUIP))
                .requiredItem(InventorySlotSpec.forItems(SHARK_ID, 10))
                .build();

        // Weapon is MUST_EQUIP so doesn't count, 10 food does
        assertEquals(10, inv.getMinimumSlotsNeeded());
    }

    @Test
    public void testGetEquipmentItems() {
        IdealInventory inv = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_SCIMITAR_ID, EquipPreference.MUST_EQUIP))
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID, EquipPreference.PREFER_EQUIP))
                .requiredItem(InventorySlotSpec.forItems(SHARK_ID, 10))
                .build();

        List<InventorySlotSpec> equipment = inv.getEquipmentItems();
        assertEquals(2, equipment.size());
    }

    @Test
    public void testGetInventoryItems() {
        IdealInventory inv = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_SCIMITAR_ID, EquipPreference.MUST_EQUIP))
                .requiredItem(InventorySlotSpec.forItems(SHARK_ID, 10))
                .requiredItem(InventorySlotSpec.forItem(LOBSTER_ID, EquipPreference.EITHER))
                .build();

        List<InventorySlotSpec> inventory = inv.getInventoryItems();
        assertEquals(2, inventory.size()); // PREFER_INVENTORY and EITHER
    }

    @Test
    public void testWithAdditionalItem() {
        IdealInventory original = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID))
                .build();

        IdealInventory modified = original.withAdditionalItem(
                InventorySlotSpec.forItems(SHARK_ID, 5));

        assertEquals(1, original.getRequiredItems().size());
        assertEquals(2, modified.getRequiredItems().size());
    }

    @Test
    public void testGetSummary() {
        IdealInventory inv = IdealInventory.builder()
                .requiredItem(InventorySlotSpec.forItem(RUNE_AXE_ID))
                .requiredItem(InventorySlotSpec.forItems(SHARK_ID, 10))
                .depositInventoryFirst(true)
                .fillRestWithItemId(SHARK_ID)
                .build();

        String summary = inv.getSummary();
        assertTrue(summary.contains("2 items"));
        assertTrue(summary.contains("deposit first"));
        assertTrue(summary.contains("fill with"));
    }
}

