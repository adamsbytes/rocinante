package com.rocinante.state;

import net.runelite.api.Item;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EquipmentState class.
 */
public class EquipmentStateTest {

    // Common item IDs for testing
    private static final int ABYSSAL_WHIP = 4151;
    private static final int DRAGON_DEFENDER = 12954;
    private static final int HELM_OF_NEITIZNOT = 10828;
    private static final int FIRE_CAPE = 6570;
    private static final int BARROWS_GLOVES = 7462;
    private static final int DRAGON_BOOTS = 11840;
    private static final int BERSERKER_RING = 6737;
    private static final int AMULET_OF_FURY = 6585;
    private static final int RUNE_ARROWS = 892;
    private static final int RING_OF_LIFE = 2570;

    // Mock items
    @Mock private Item mockWhip;
    @Mock private Item mockDefender;
    @Mock private Item mockHelm;
    @Mock private Item mockCape;
    @Mock private Item mockGloves;
    @Mock private Item mockBoots;
    @Mock private Item mockRing;
    @Mock private Item mockAmulet;
    @Mock private Item mockAmmo;

    private Map<Integer, Item> testEquipment;
    private EquipmentState testState;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Setup mock items
        when(mockWhip.getId()).thenReturn(ABYSSAL_WHIP);
        when(mockWhip.getQuantity()).thenReturn(1);

        when(mockDefender.getId()).thenReturn(DRAGON_DEFENDER);
        when(mockDefender.getQuantity()).thenReturn(1);

        when(mockHelm.getId()).thenReturn(HELM_OF_NEITIZNOT);
        when(mockHelm.getQuantity()).thenReturn(1);

        when(mockCape.getId()).thenReturn(FIRE_CAPE);
        when(mockCape.getQuantity()).thenReturn(1);

        when(mockGloves.getId()).thenReturn(BARROWS_GLOVES);
        when(mockGloves.getQuantity()).thenReturn(1);

        when(mockBoots.getId()).thenReturn(DRAGON_BOOTS);
        when(mockBoots.getQuantity()).thenReturn(1);

        when(mockRing.getId()).thenReturn(BERSERKER_RING);
        when(mockRing.getQuantity()).thenReturn(1);

        when(mockAmulet.getId()).thenReturn(AMULET_OF_FURY);
        when(mockAmulet.getQuantity()).thenReturn(1);

        when(mockAmmo.getId()).thenReturn(RUNE_ARROWS);
        when(mockAmmo.getQuantity()).thenReturn(500);

        testEquipment = new HashMap<>();
        testEquipment.put(EquipmentState.SLOT_WEAPON, mockWhip);
        testEquipment.put(EquipmentState.SLOT_SHIELD, mockDefender);
        testEquipment.put(EquipmentState.SLOT_HEAD, mockHelm);
        testEquipment.put(EquipmentState.SLOT_CAPE, mockCape);
        testEquipment.put(EquipmentState.SLOT_GLOVES, mockGloves);
        testEquipment.put(EquipmentState.SLOT_BOOTS, mockBoots);
        testEquipment.put(EquipmentState.SLOT_RING, mockRing);
        testEquipment.put(EquipmentState.SLOT_AMULET, mockAmulet);
        testEquipment.put(EquipmentState.SLOT_AMMO, mockAmmo);

        testState = new EquipmentState(testEquipment);
    }

    // ========================================================================
    // Construction
    // ========================================================================

    @Test
    public void testEmptyState() {
        EquipmentState empty = EquipmentState.EMPTY;

        assertTrue(empty.isEmpty());
        assertEquals(0, empty.getEquippedCount());
        assertFalse(empty.hasAnyEquipment());
    }

    @Test
    public void testConstructorWithNullMap() {
        EquipmentState state = new EquipmentState(null);

        assertTrue(state.isEmpty());
    }

    @Test
    public void testConstructorDefensiveCopy() {
        Map<Integer, Item> map = new HashMap<>();
        map.put(EquipmentState.SLOT_WEAPON, mockWhip);

        EquipmentState state = new EquipmentState(map);

        // Modify original map
        Item mockOther = mock(Item.class);
        when(mockOther.getId()).thenReturn(999);
        map.put(EquipmentState.SLOT_WEAPON, mockOther);

        // State should still have original value
        assertTrue(state.hasEquipped(ABYSSAL_WHIP));
    }

    @Test
    public void testFromArray() {
        Item[] items = new Item[14]; // Equipment array size
        items[EquipmentState.SLOT_WEAPON] = mockWhip;
        items[EquipmentState.SLOT_SHIELD] = mockDefender;

        EquipmentState state = EquipmentState.fromArray(items);

        assertTrue(state.hasEquipped(ABYSSAL_WHIP));
        assertTrue(state.hasEquipped(DRAGON_DEFENDER));
    }

    // ========================================================================
    // Slot Query Methods
    // ========================================================================

    @Test
    public void testGetEquippedItem() {
        Optional<Item> weapon = testState.getEquippedItem(EquipmentState.SLOT_WEAPON);

        assertTrue(weapon.isPresent());
        assertEquals(ABYSSAL_WHIP, weapon.get().getId());
    }

    @Test
    public void testGetEquippedItem_EmptySlot() {
        Optional<Item> body = testState.getEquippedItem(EquipmentState.SLOT_BODY);

        assertFalse(body.isPresent());
    }

    @Test
    public void testHasEquippedSlot() {
        assertTrue(testState.hasEquippedSlot(EquipmentState.SLOT_WEAPON));
        assertTrue(testState.hasEquippedSlot(EquipmentState.SLOT_SHIELD));
        assertFalse(testState.hasEquippedSlot(EquipmentState.SLOT_BODY));
        assertFalse(testState.hasEquippedSlot(EquipmentState.SLOT_LEGS));
    }

    @Test
    public void testHasEquipped() {
        assertTrue(testState.hasEquipped(ABYSSAL_WHIP));
        assertTrue(testState.hasEquipped(DRAGON_DEFENDER));
        assertFalse(testState.hasEquipped(999)); // Not equipped
    }

    @Test
    public void testHasAnyEquipped() {
        assertTrue(testState.hasAnyEquipped(ABYSSAL_WHIP, 999));
        assertTrue(testState.hasAnyEquipped(999, DRAGON_DEFENDER));
        assertFalse(testState.hasAnyEquipped(999, 888));
    }

    @Test
    public void testHasAllEquipped() {
        assertTrue(testState.hasAllEquipped(ABYSSAL_WHIP, DRAGON_DEFENDER));
        assertFalse(testState.hasAllEquipped(ABYSSAL_WHIP, 999));
    }

    @Test
    public void testGetSlotOf() {
        Optional<Integer> slot = testState.getSlotOf(ABYSSAL_WHIP);

        assertTrue(slot.isPresent());
        assertEquals(Integer.valueOf(EquipmentState.SLOT_WEAPON), slot.get());
    }

    @Test
    public void testGetSlotOf_NotEquipped() {
        Optional<Integer> slot = testState.getSlotOf(999);

        assertFalse(slot.isPresent());
    }

    @Test
    public void testGetEquippedIds() {
        Set<Integer> ids = testState.getEquippedIds();

        assertEquals(9, ids.size());
        assertTrue(ids.contains(ABYSSAL_WHIP));
        assertTrue(ids.contains(DRAGON_DEFENDER));
        assertTrue(ids.contains(HELM_OF_NEITIZNOT));
    }

    // ========================================================================
    // Specific Slot Accessors
    // ========================================================================

    @Test
    public void testGetWeapon() {
        Optional<Item> weapon = testState.getWeapon();

        assertTrue(weapon.isPresent());
        assertEquals(ABYSSAL_WHIP, weapon.get().getId());
    }

    @Test
    public void testGetWeaponId() {
        assertEquals(ABYSSAL_WHIP, testState.getWeaponId());
    }

    @Test
    public void testGetWeaponId_NoWeapon() {
        EquipmentState empty = EquipmentState.EMPTY;

        assertEquals(-1, empty.getWeaponId());
    }

    @Test
    public void testGetShield() {
        assertTrue(testState.getShield().isPresent());
        assertEquals(DRAGON_DEFENDER, testState.getShield().get().getId());
    }

    @Test
    public void testGetHelmet() {
        assertTrue(testState.getHelmet().isPresent());
        assertEquals(HELM_OF_NEITIZNOT, testState.getHelmet().get().getId());
    }

    @Test
    public void testGetBody_Empty() {
        assertFalse(testState.getBody().isPresent());
    }

    @Test
    public void testGetLegs_Empty() {
        assertFalse(testState.getLegs().isPresent());
    }

    @Test
    public void testGetCape() {
        assertTrue(testState.getCape().isPresent());
        assertEquals(FIRE_CAPE, testState.getCape().get().getId());
    }

    @Test
    public void testGetAmulet() {
        assertTrue(testState.getAmulet().isPresent());
        assertEquals(AMULET_OF_FURY, testState.getAmulet().get().getId());
    }

    @Test
    public void testGetGloves() {
        assertTrue(testState.getGloves().isPresent());
        assertEquals(BARROWS_GLOVES, testState.getGloves().get().getId());
    }

    @Test
    public void testGetBoots() {
        assertTrue(testState.getBoots().isPresent());
        assertEquals(DRAGON_BOOTS, testState.getBoots().get().getId());
    }

    @Test
    public void testGetRing() {
        assertTrue(testState.getRing().isPresent());
        assertEquals(BERSERKER_RING, testState.getRing().get().getId());
    }

    @Test
    public void testGetAmmo() {
        assertTrue(testState.getAmmo().isPresent());
        assertEquals(RUNE_ARROWS, testState.getAmmo().get().getId());
    }

    @Test
    public void testGetAmmoCount() {
        assertEquals(500, testState.getAmmoCount());
    }

    @Test
    public void testGetAmmoCount_NoAmmo() {
        EquipmentState empty = EquipmentState.EMPTY;

        assertEquals(0, empty.getAmmoCount());
    }

    @Test
    public void testGetAmmoId() {
        assertEquals(RUNE_ARROWS, testState.getAmmoId());
    }

    // ========================================================================
    // Gear Set Comparison
    // ========================================================================

    @Test
    public void testMatchesGearSet() {
        Set<Integer> gearSet = Set.of(ABYSSAL_WHIP, DRAGON_DEFENDER, HELM_OF_NEITIZNOT);

        assertTrue(testState.matchesGearSet(gearSet));
    }

    @Test
    public void testMatchesGearSet_MissingItems() {
        Set<Integer> gearSet = Set.of(ABYSSAL_WHIP, 999); // 999 not equipped

        assertFalse(testState.matchesGearSet(gearSet));
    }

    @Test
    public void testExactlyMatchesGearSet() {
        Set<Integer> exactSet = Set.of(
                ABYSSAL_WHIP, DRAGON_DEFENDER, HELM_OF_NEITIZNOT,
                FIRE_CAPE, BARROWS_GLOVES, DRAGON_BOOTS,
                BERSERKER_RING, AMULET_OF_FURY, RUNE_ARROWS
        );

        assertTrue(testState.exactlyMatchesGearSet(exactSet));
    }

    @Test
    public void testExactlyMatchesGearSet_ExtraItems() {
        Set<Integer> subSet = Set.of(ABYSSAL_WHIP, DRAGON_DEFENDER);

        assertFalse(testState.exactlyMatchesGearSet(subSet));
    }

    @Test
    public void testGetMissingFromGearSet() {
        Set<Integer> expected = new HashSet<>(Set.of(ABYSSAL_WHIP, 999, 888));
        Set<Integer> missing = testState.getMissingFromGearSet(expected);

        assertEquals(2, missing.size());
        assertTrue(missing.contains(999));
        assertTrue(missing.contains(888));
        assertFalse(missing.contains(ABYSSAL_WHIP));
    }

    @Test
    public void testGetExtraFromGearSet() {
        Set<Integer> expected = new HashSet<>(Set.of(ABYSSAL_WHIP));
        Set<Integer> extra = testState.getExtraFromGearSet(expected);

        assertEquals(8, extra.size()); // All except whip
        assertFalse(extra.contains(ABYSSAL_WHIP));
    }

    // ========================================================================
    // Safety Equipment Checks
    // ========================================================================

    @Test
    public void testHasRingOfLife_WithRol() {
        Item mockRol = mock(Item.class);
        when(mockRol.getId()).thenReturn(RING_OF_LIFE);
        when(mockRol.getQuantity()).thenReturn(1);

        Map<Integer, Item> rolEquip = new HashMap<>();
        rolEquip.put(EquipmentState.SLOT_RING, mockRol);

        EquipmentState state = new EquipmentState(rolEquip);

        assertTrue(state.hasRingOfLife());
    }

    @Test
    public void testHasRingOfLife_WithoutRol() {
        assertFalse(testState.hasRingOfLife());
    }

    @Test
    public void testHasRingOfLife_NoRing() {
        EquipmentState empty = EquipmentState.EMPTY;

        assertFalse(empty.hasRingOfLife());
    }

    @Test
    public void testHasPhoenixNecklace() {
        Item mockPn = mock(Item.class);
        when(mockPn.getId()).thenReturn(11090); // Phoenix necklace
        when(mockPn.getQuantity()).thenReturn(1);

        Map<Integer, Item> pnEquip = new HashMap<>();
        pnEquip.put(EquipmentState.SLOT_AMULET, mockPn);

        EquipmentState state = new EquipmentState(pnEquip);

        assertTrue(state.hasPhoenixNecklace());
        assertFalse(testState.hasPhoenixNecklace());
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    @Test
    public void testGetEquippedCount() {
        assertEquals(9, testState.getEquippedCount());
    }

    @Test
    public void testHasAnyEquipment() {
        assertTrue(testState.hasAnyEquipment());
        assertFalse(EquipmentState.EMPTY.hasAnyEquipment());
    }

    @Test
    public void testIsEmpty() {
        assertFalse(testState.isEmpty());
        assertTrue(EquipmentState.EMPTY.isEmpty());
    }

    @Test
    public void testGetAllEquipment() {
        Map<Integer, Item> all = testState.getAllEquipment();

        assertEquals(9, all.size());
        assertTrue(all.containsKey(EquipmentState.SLOT_WEAPON));
    }

    @Test
    public void testGetAllEquipment_Unmodifiable() {
        Map<Integer, Item> all = testState.getAllEquipment();

        try {
            Item mockNew = mock(Item.class);
            when(mockNew.getId()).thenReturn(999);
            all.put(EquipmentState.SLOT_BODY, mockNew);
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }

    @Test
    public void testGetSummary() {
        String summary = testState.getSummary();

        assertTrue(summary.contains("WEAPON=" + ABYSSAL_WHIP));
        assertTrue(summary.contains("SHIELD=" + DRAGON_DEFENDER));
    }

    @Test
    public void testGetSummary_Empty() {
        String summary = EquipmentState.EMPTY.getSummary();

        assertEquals("No equipment", summary);
    }
}
