package com.rocinante.slayer;

import net.runelite.api.ItemID;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for SlayerEquipmentManager validation helpers.
 * 
 * Note: Item ID constants are now private and use ItemID from RuneLite.
 * Tests focus on behavior rather than implementation details.
 */
public class SlayerEquipmentManagerTest {

    // ========================================================================
    // Validation Result Tests
    // ========================================================================

    @Test
    public void testEquipmentValidationValid() {
        SlayerEquipmentManager.EquipmentValidation valid = 
                SlayerEquipmentManager.EquipmentValidation.valid();

        assertTrue(valid.isValid());
        assertTrue(valid.getMissingItems().isEmpty());
        assertTrue(valid.getRequiredItemIds().isEmpty());
    }

    @Test
    public void testEquipmentValidationInvalid() {
        SlayerEquipmentManager.EquipmentValidation invalid = 
                SlayerEquipmentManager.EquipmentValidation.invalid(
                        java.util.Arrays.asList("Nose peg"),
                        Set.of(ItemID.NOSE_PEG)
                );

        assertFalse(invalid.isValid());
        assertEquals(1, invalid.getMissingItems().size());
        assertEquals("Nose peg", invalid.getMissingItems().get(0));
        assertTrue(invalid.getRequiredItemIds().contains(ItemID.NOSE_PEG));
    }

    @Test
    public void testWeaponValidationValid() {
        SlayerEquipmentManager.WeaponValidation valid = 
                SlayerEquipmentManager.WeaponValidation.valid();

        assertTrue(valid.isValid());
        assertNull(valid.getReason());
    }

    @Test
    public void testWeaponValidationInvalid() {
        SlayerEquipmentManager.WeaponValidation invalid = 
                SlayerEquipmentManager.WeaponValidation.invalid(
                        "Requires leaf-bladed weapon",
                        Set.of(ItemID.LEAFBLADED_SPEAR, ItemID.LEAFBLADED_SWORD, ItemID.LEAFBLADED_BATTLEAXE)
                );

        assertFalse(invalid.isValid());
        assertEquals("Requires leaf-bladed weapon", invalid.getReason());
        assertEquals(3, invalid.getValidWeaponIds().size());
    }

    // ========================================================================
    // Finish-off Validation Tests
    // ========================================================================

    @Test
    public void testFinishOffValidationNotRequired() {
        SlayerEquipmentManager.FinishOffValidation notRequired = 
                SlayerEquipmentManager.FinishOffValidation.notRequired();

        assertFalse(notRequired.isRequired());
        assertTrue(notRequired.isHasItem());
        assertFalse(notRequired.isAutoKillEnabled());
    }

    @Test
    public void testFinishOffValidationAutoKill() {
        SlayerEquipmentManager.FinishOffValidation autoKill = 
                SlayerEquipmentManager.FinishOffValidation.autoKillEnabled(
                        SlayerUnlock.AUTOKILL_GARGOYLES);

        assertTrue(autoKill.isRequired());
        assertTrue(autoKill.isHasItem());
        assertTrue(autoKill.isAutoKillEnabled());
        assertEquals(SlayerUnlock.AUTOKILL_GARGOYLES, autoKill.getAutoKillUnlock());
    }

    @Test
    public void testFinishOffValidationHasItem() {
        SlayerEquipmentManager.FinishOffValidation hasItem = 
                SlayerEquipmentManager.FinishOffValidation.hasItem(ItemID.ROCK_HAMMER, 9);

        assertTrue(hasItem.isRequired());
        assertTrue(hasItem.isHasItem());
        assertFalse(hasItem.isAutoKillEnabled());
        assertEquals(ItemID.ROCK_HAMMER, hasItem.getItemId());
        assertEquals(9, hasItem.getThreshold());
    }

    @Test
    public void testFinishOffValidationMissing() {
        SlayerEquipmentManager.FinishOffValidation missing = 
                SlayerEquipmentManager.FinishOffValidation.missing("Rock hammer", ItemID.ROCK_HAMMER, 9);

        assertTrue(missing.isRequired());
        assertFalse(missing.isHasItem());
        assertEquals("Rock hammer", missing.getMissingItemName());
        assertEquals(ItemID.ROCK_HAMMER, missing.getItemId());
        assertEquals(9, missing.getThreshold());
    }

    // ========================================================================
    // Required Items List Tests
    // ========================================================================

    @Test
    public void testRequiredItemsListEmpty() {
        SlayerEquipmentManager.RequiredItemsList empty = 
                new SlayerEquipmentManager.RequiredItemsList(java.util.Collections.emptyList());

        assertTrue(empty.isEmpty());
        assertTrue(empty.getItemIds().isEmpty());
    }

    @Test
    public void testRequiredItemsListWithItems() {
        SlayerEquipmentManager.RequiredItem item1 = 
                new SlayerEquipmentManager.RequiredItem(
                        ItemID.NOSE_PEG, 1, SlayerEquipmentManager.ItemType.EQUIPMENT, "Nose peg");
        SlayerEquipmentManager.RequiredItem item2 = 
                new SlayerEquipmentManager.RequiredItem(
                        ItemID.ROCK_HAMMER, 1, SlayerEquipmentManager.ItemType.CONSUMABLE, "Rock hammer");

        SlayerEquipmentManager.RequiredItemsList list = 
                new SlayerEquipmentManager.RequiredItemsList(
                        java.util.Arrays.asList(item1, item2));

        assertFalse(list.isEmpty());
        assertEquals(2, list.getItems().size());
        
        Set<Integer> ids = list.getItemIds();
        assertTrue(ids.contains(ItemID.NOSE_PEG));
        assertTrue(ids.contains(ItemID.ROCK_HAMMER));
    }

    // ========================================================================
    // Item Type Tests
    // ========================================================================

    @Test
    public void testItemTypes() {
        assertEquals(SlayerEquipmentManager.ItemType.EQUIPMENT, 
                SlayerEquipmentManager.ItemType.valueOf("EQUIPMENT"));
        assertEquals(SlayerEquipmentManager.ItemType.CONSUMABLE, 
                SlayerEquipmentManager.ItemType.valueOf("CONSUMABLE"));
        assertEquals(SlayerEquipmentManager.ItemType.ACCESS, 
                SlayerEquipmentManager.ItemType.valueOf("ACCESS"));
    }
}
