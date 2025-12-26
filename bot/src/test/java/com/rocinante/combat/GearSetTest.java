package com.rocinante.combat;

import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import net.runelite.api.Item;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for GearSet.
 */
public class GearSetTest {

    private InventoryState mockInventory;
    private EquipmentState mockEquipment;

    // Test item IDs
    private static final int SHORTBOW = 841;
    private static final int BRONZE_ARROW = 882;
    private static final int AIR_STAFF = 1381;
    private static final int BRONZE_SWORD = 1277;
    private static final int WOODEN_SHIELD = 1171;
    private static final int RUNE_SCIMITAR = 1333;

    @Before
    public void setUp() {
        mockInventory = mock(InventoryState.class);
        mockEquipment = mock(EquipmentState.class);
        
        // Default: empty equipment
        when(mockEquipment.getEquippedItem(anyInt())).thenReturn(Optional.empty());
    }

    // ========================================================================
    // Builder Tests
    // ========================================================================

    @Test
    public void testBuilder_FullGearSet() {
        GearSet set = GearSet.builder()
                .name("Test Set")
                .attackStyle(AttackStyle.MELEE)
                .weapon(BRONZE_SWORD)
                .shield(WOODEN_SHIELD)
                .build();

        assertEquals("Test Set", set.getName());
        assertEquals(AttackStyle.MELEE, set.getAttackStyle());
        assertEquals(2, set.size());
        assertEquals(BRONZE_SWORD, set.getWeaponId());
        assertTrue(set.hasWeapon());
        assertFalse(set.hasAmmo());
    }

    @Test
    public void testBuilder_RangedGearSet() {
        GearSet set = GearSet.builder()
                .name("Ranged")
                .attackStyle(AttackStyle.RANGED)
                .weapon(SHORTBOW)
                .ammo(BRONZE_ARROW)
                .build();

        assertEquals("Ranged", set.getName());
        assertEquals(AttackStyle.RANGED, set.getAttackStyle());
        assertTrue(set.hasWeapon());
        assertTrue(set.hasAmmo());
        assertEquals(2, set.size());
    }

    @Test
    public void testBuilder_EmptySet() {
        GearSet set = GearSet.builder()
                .name("Empty")
                .build();

        assertTrue(set.isEmpty());
        assertEquals(0, set.size());
        assertEquals(-1, set.getWeaponId());
    }

    // ========================================================================
    // Availability Tests
    // ========================================================================

    @Test
    public void testIsAvailable_AllItemsInInventory() {
        when(mockInventory.hasItem(SHORTBOW)).thenReturn(true);
        when(mockInventory.hasItem(BRONZE_ARROW)).thenReturn(true);

        GearSet set = GearSet.builder()
                .weapon(SHORTBOW)
                .ammo(BRONZE_ARROW)
                .build();

        assertTrue(set.isAvailable(mockInventory, mockEquipment));
    }

    @Test
    public void testIsAvailable_ItemAlreadyEquipped() {
        // Shortbow is already equipped in weapon slot
        Item equippedBow = mock(Item.class);
        when(equippedBow.getId()).thenReturn(SHORTBOW);
        when(mockEquipment.getEquippedItem(EquipmentState.SLOT_WEAPON)).thenReturn(Optional.of(equippedBow));
        
        // Arrows in inventory
        when(mockInventory.hasItem(BRONZE_ARROW)).thenReturn(true);

        GearSet set = GearSet.builder()
                .weapon(SHORTBOW)
                .ammo(BRONZE_ARROW)
                .build();

        assertTrue(set.isAvailable(mockInventory, mockEquipment));
    }

    @Test
    public void testIsAvailable_MissingItem() {
        when(mockInventory.hasItem(SHORTBOW)).thenReturn(true);
        when(mockInventory.hasItem(BRONZE_ARROW)).thenReturn(false); // Missing arrows

        GearSet set = GearSet.builder()
                .weapon(SHORTBOW)
                .ammo(BRONZE_ARROW)
                .build();

        assertFalse(set.isAvailable(mockInventory, mockEquipment));
    }

    // ========================================================================
    // Equipment Check Tests
    // ========================================================================

    @Test
    public void testIsEquipped_AllItemsEquipped() {
        // Both items equipped
        Item equippedBow = mock(Item.class);
        when(equippedBow.getId()).thenReturn(SHORTBOW);
        when(mockEquipment.getEquippedItem(EquipmentState.SLOT_WEAPON)).thenReturn(Optional.of(equippedBow));
        
        Item equippedArrows = mock(Item.class);
        when(equippedArrows.getId()).thenReturn(BRONZE_ARROW);
        when(mockEquipment.getEquippedItem(EquipmentState.SLOT_AMMO)).thenReturn(Optional.of(equippedArrows));

        GearSet set = GearSet.builder()
                .weapon(SHORTBOW)
                .ammo(BRONZE_ARROW)
                .build();

        assertTrue(set.isEquipped(mockEquipment));
    }

    @Test
    public void testIsEquipped_PartiallyEquipped() {
        // Only weapon equipped
        Item equippedBow = mock(Item.class);
        when(equippedBow.getId()).thenReturn(SHORTBOW);
        when(mockEquipment.getEquippedItem(EquipmentState.SLOT_WEAPON)).thenReturn(Optional.of(equippedBow));

        GearSet set = GearSet.builder()
                .weapon(SHORTBOW)
                .ammo(BRONZE_ARROW)
                .build();

        assertFalse(set.isEquipped(mockEquipment));
    }

    @Test
    public void testIsEquipped_WrongItemEquipped() {
        // Different weapon equipped
        Item differentWeapon = mock(Item.class);
        when(differentWeapon.getId()).thenReturn(RUNE_SCIMITAR);
        when(mockEquipment.getEquippedItem(EquipmentState.SLOT_WEAPON)).thenReturn(Optional.of(differentWeapon));

        GearSet set = GearSet.builder()
                .weapon(SHORTBOW)
                .build();

        assertFalse(set.isEquipped(mockEquipment));
    }

    // ========================================================================
    // Items to Equip Tests
    // ========================================================================

    @Test
    public void testGetItemsToEquip_NothingEquipped() {
        GearSet set = GearSet.builder()
                .weapon(SHORTBOW)
                .ammo(BRONZE_ARROW)
                .build();

        Map<Integer, Integer> toEquip = set.getItemsToEquip(mockEquipment);
        
        assertEquals(2, toEquip.size());
        assertEquals(Integer.valueOf(SHORTBOW), toEquip.get(EquipmentState.SLOT_WEAPON));
        assertEquals(Integer.valueOf(BRONZE_ARROW), toEquip.get(EquipmentState.SLOT_AMMO));
    }

    @Test
    public void testGetItemsToEquip_PartiallyEquipped() {
        // Weapon already equipped
        Item equippedBow = mock(Item.class);
        when(equippedBow.getId()).thenReturn(SHORTBOW);
        when(mockEquipment.getEquippedItem(EquipmentState.SLOT_WEAPON)).thenReturn(Optional.of(equippedBow));

        GearSet set = GearSet.builder()
                .weapon(SHORTBOW)
                .ammo(BRONZE_ARROW)
                .build();

        Map<Integer, Integer> toEquip = set.getItemsToEquip(mockEquipment);
        
        assertEquals(1, toEquip.size());
        assertNull(toEquip.get(EquipmentState.SLOT_WEAPON)); // Already equipped
        assertEquals(Integer.valueOf(BRONZE_ARROW), toEquip.get(EquipmentState.SLOT_AMMO));
    }

    // ========================================================================
    // Auto-Detection Tests
    // ========================================================================

    @Test
    public void testAutoDetect_RangedFromInventory() {
        when(mockInventory.hasItem(SHORTBOW)).thenReturn(true);
        when(mockInventory.hasItem(BRONZE_ARROW)).thenReturn(true);

        GearSet detected = GearSet.autoDetect(AttackStyle.RANGED, mockInventory, mockEquipment);

        assertFalse(detected.isEmpty());
        assertEquals(AttackStyle.RANGED, detected.getAttackStyle());
        assertTrue(detected.hasWeapon());
    }

    @Test
    public void testAutoDetect_MagicFromInventory() {
        when(mockInventory.hasItem(AIR_STAFF)).thenReturn(true);

        GearSet detected = GearSet.autoDetect(AttackStyle.MAGIC, mockInventory, mockEquipment);

        assertFalse(detected.isEmpty());
        assertEquals(AttackStyle.MAGIC, detected.getAttackStyle());
        assertTrue(detected.hasWeapon());
    }

    @Test
    public void testAutoDetect_NoRangedGear() {
        // No ranged gear in inventory
        when(mockInventory.hasItem(anyInt())).thenReturn(false);

        GearSet detected = GearSet.autoDetect(AttackStyle.RANGED, mockInventory, mockEquipment);

        assertTrue(detected.isEmpty());
    }

    @Test
    public void testAutoDetect_Melee() {
        // Melee doesn't require specific gear detection
        GearSet detected = GearSet.autoDetect(AttackStyle.MELEE, mockInventory, mockEquipment);

        // Melee auto-detect returns a non-empty set (default is melee)
        assertNotNull(detected);
        assertEquals(AttackStyle.MELEE, detected.getAttackStyle());
    }

    // ========================================================================
    // Utility Tests
    // ========================================================================

    @Test
    public void testGetItemIds() {
        GearSet set = GearSet.builder()
                .weapon(SHORTBOW)
                .ammo(BRONZE_ARROW)
                .shield(WOODEN_SHIELD)
                .build();

        Set<Integer> ids = set.getItemIds();
        
        assertEquals(3, ids.size());
        assertTrue(ids.contains(SHORTBOW));
        assertTrue(ids.contains(BRONZE_ARROW));
        assertTrue(ids.contains(WOODEN_SHIELD));
    }

    @Test
    public void testGetSummary() {
        GearSet set = GearSet.builder()
                .name("Test Ranged")
                .attackStyle(AttackStyle.RANGED)
                .weapon(SHORTBOW)
                .build();

        String summary = set.getSummary();
        
        assertTrue(summary.contains("Test Ranged"));
        assertTrue(summary.contains("1 items"));
        assertTrue(summary.contains("RANGED"));
    }

    @Test
    public void testEmptyGearSet() {
        assertNotNull(GearSet.EMPTY);
        assertTrue(GearSet.EMPTY.isEmpty());
        assertEquals("Empty", GearSet.EMPTY.getName());
    }

    @Test
    public void testForItems_Ranged() {
        GearSet set = GearSet.forItems(SHORTBOW, BRONZE_ARROW);
        
        assertEquals(AttackStyle.RANGED, set.getAttackStyle());
        assertTrue(set.getItemIds().contains(SHORTBOW));
    }

    @Test
    public void testForItems_Magic() {
        GearSet set = GearSet.forItems(AIR_STAFF);
        
        assertEquals(AttackStyle.MAGIC, set.getAttackStyle());
        assertTrue(set.getItemIds().contains(AIR_STAFF));
    }
}

