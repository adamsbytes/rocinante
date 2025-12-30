package com.rocinante.inventory;

import com.rocinante.state.BankState;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.util.ItemCollections;
import net.runelite.api.Item;
import net.runelite.api.ItemID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ToolSelection class.
 */
public class ToolSelectionTest {

    @Mock private Item mockBronzeAxe;
    @Mock private Item mockIronAxe;
    @Mock private Item mockRuneAxe;

    private static final int BRONZE_AXE_ID = ItemID.BRONZE_AXE;
    private static final int IRON_AXE_ID = ItemID.IRON_AXE;
    private static final int STEEL_AXE_ID = ItemID.STEEL_AXE;
    private static final int MITHRIL_AXE_ID = ItemID.MITHRIL_AXE;
    private static final int RUNE_AXE_ID = ItemID.RUNE_AXE;
    private static final int DRAGON_AXE_ID = ItemID.DRAGON_AXE;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        when(mockBronzeAxe.getId()).thenReturn(BRONZE_AXE_ID);
        when(mockBronzeAxe.getQuantity()).thenReturn(1);

        when(mockIronAxe.getId()).thenReturn(IRON_AXE_ID);
        when(mockIronAxe.getQuantity()).thenReturn(1);

        when(mockRuneAxe.getId()).thenReturn(RUNE_AXE_ID);
        when(mockRuneAxe.getQuantity()).thenReturn(1);
    }

    // ========================================================================
    // selectBestTool Tests
    // ========================================================================

    @Test
    public void testSelectBestTool_ReturnsHighestLevelAvailable() {
        // Player has 50 WC, 50 Attack
        // Available: bronze, iron, rune axes
        List<Integer> available = List.of(BRONZE_AXE_ID, IRON_AXE_ID, RUNE_AXE_ID);

        ToolSelection.ToolSelectionResult result = ToolSelection.selectBestTool(
                ItemCollections.AXES,
                ItemCollections.AXE_LEVELS,
                50,  // WC level
                50,  // Attack level
                available::contains
        );

        assertTrue(result.isFound());
        assertEquals(Integer.valueOf(RUNE_AXE_ID), result.getItemId());
        assertTrue(result.isCanEquip());
    }

    @Test
    public void testSelectBestTool_RespectsSkillLevelRequirements() {
        // Player has 30 WC, 50 Attack
        // Available: bronze, iron, rune axes
        // Can use adamant (31 WC) so should get mithril (21 WC)
        List<Integer> available = List.of(BRONZE_AXE_ID, IRON_AXE_ID, MITHRIL_AXE_ID, RUNE_AXE_ID);

        ToolSelection.ToolSelectionResult result = ToolSelection.selectBestTool(
                ItemCollections.AXES,
                ItemCollections.AXE_LEVELS,
                30,  // WC level - can't use rune (41)
                50,  // Attack level
                available::contains
        );

        assertTrue(result.isFound());
        assertEquals(Integer.valueOf(MITHRIL_AXE_ID), result.getItemId());
        assertTrue(result.isCanEquip());
    }

    @Test
    public void testSelectBestTool_HandlesLowAttackLevel() {
        // Player has 50 WC but only 1 Attack
        // Can USE rune axe but can't EQUIP it
        List<Integer> available = List.of(BRONZE_AXE_ID, IRON_AXE_ID, RUNE_AXE_ID);

        ToolSelection.ToolSelectionResult result = ToolSelection.selectBestTool(
                ItemCollections.AXES,
                ItemCollections.AXE_LEVELS,
                50,  // WC level - can USE rune
                1,   // Attack level - can only EQUIP bronze/iron
                available::contains
        );

        assertTrue(result.isFound());
        // Best usable is rune
        assertEquals(Integer.valueOf(RUNE_AXE_ID), result.getItemId());
        // But can't equip it
        assertFalse(result.isCanEquip());
        // Best equippable alternative is iron (highest they can equip)
        assertEquals(Integer.valueOf(IRON_AXE_ID), result.getBestEquippableAlternative());
    }

    @Test
    public void testSelectBestTool_ReturnsNotFoundWhenNoAvailable() {
        List<Integer> available = List.of(); // Nothing available

        ToolSelection.ToolSelectionResult result = ToolSelection.selectBestTool(
                ItemCollections.AXES,
                ItemCollections.AXE_LEVELS,
                50,
                50,
                available::contains
        );

        assertFalse(result.isFound());
    }

    @Test
    public void testSelectBestTool_ReturnsNotFoundWhenLevelTooLow() {
        // Player has 1 WC but only dragon axe available (61 WC required)
        List<Integer> available = List.of(DRAGON_AXE_ID);

        ToolSelection.ToolSelectionResult result = ToolSelection.selectBestTool(
                ItemCollections.AXES,
                ItemCollections.AXE_LEVELS,
                1,   // WC level - can't use anything good
                60,  // Attack level
                available::contains
        );

        assertFalse(result.isFound());
    }

    // ========================================================================
    // canEquipItem Tests
    // ========================================================================

    @Test
    public void testCanEquipItem_BronzeAxe() {
        // Bronze axe requires 1 Attack
        assertTrue(ToolSelection.canEquipItem(BRONZE_AXE_ID, 1, 99));
    }

    @Test
    public void testCanEquipItem_RuneAxe() {
        // Rune axe requires 40 Attack
        assertFalse(ToolSelection.canEquipItem(RUNE_AXE_ID, 39, 99));
        assertTrue(ToolSelection.canEquipItem(RUNE_AXE_ID, 40, 99));
        assertTrue(ToolSelection.canEquipItem(RUNE_AXE_ID, 50, 99));
    }

    @Test
    public void testCanEquipItem_DragonAxe() {
        // Dragon axe requires 60 Attack
        assertFalse(ToolSelection.canEquipItem(DRAGON_AXE_ID, 59, 99));
        assertTrue(ToolSelection.canEquipItem(DRAGON_AXE_ID, 60, 99));
    }

    // ========================================================================
    // getAttackLevelForEquip Tests
    // ========================================================================

    @Test
    public void testGetAttackLevelForEquip_Axes() {
        assertEquals(1, ToolSelection.getAttackLevelForEquip(BRONZE_AXE_ID));
        assertEquals(1, ToolSelection.getAttackLevelForEquip(IRON_AXE_ID));
        assertEquals(5, ToolSelection.getAttackLevelForEquip(STEEL_AXE_ID));
        assertEquals(40, ToolSelection.getAttackLevelForEquip(RUNE_AXE_ID));
        assertEquals(60, ToolSelection.getAttackLevelForEquip(DRAGON_AXE_ID));
    }

    @Test
    public void testGetAttackLevelForEquip_Pickaxes() {
        assertEquals(1, ToolSelection.getAttackLevelForEquip(ItemID.BRONZE_PICKAXE));
        assertEquals(40, ToolSelection.getAttackLevelForEquip(ItemID.RUNE_PICKAXE));
        assertEquals(60, ToolSelection.getAttackLevelForEquip(ItemID.DRAGON_PICKAXE));
    }

    @Test
    public void testGetAttackLevelForEquip_UnknownItem() {
        // Unknown items default to 1
        assertEquals(1, ToolSelection.getAttackLevelForEquip(12345));
    }

    // ========================================================================
    // findItemLocation Tests
    // ========================================================================

    @Test
    public void testFindItemLocation_InInventory() {
        Item[] items = new Item[] { mockRuneAxe };
        InventoryState inventory = new InventoryState(items);

        ToolSelection.ItemLocation location = ToolSelection.findItemLocation(
                RUNE_AXE_ID, inventory, null, null);

        assertEquals(ToolSelection.ItemLocation.INVENTORY, location);
    }

    @Test
    public void testFindItemLocation_NotFound() {
        InventoryState inventory = new InventoryState(new Item[0]);

        ToolSelection.ItemLocation location = ToolSelection.findItemLocation(
                RUNE_AXE_ID, inventory, null, null);

        assertEquals(ToolSelection.ItemLocation.NOT_FOUND, location);
    }

    // ========================================================================
    // getItemToUse Tests
    // ========================================================================

    @Test
    public void testGetItemToUse_PreferEquippable() {
        ToolSelection.ToolSelectionResult result = ToolSelection.ToolSelectionResult.builder()
                .itemId(RUNE_AXE_ID)
                .canEquip(false)
                .bestEquippableAlternative(IRON_AXE_ID)
                .build();

        // When preferring equippable, should return iron (which can be equipped)
        assertEquals(Integer.valueOf(IRON_AXE_ID), result.getItemToUse(true).orElse(null));
    }

    @Test
    public void testGetItemToUse_BestUsable() {
        ToolSelection.ToolSelectionResult result = ToolSelection.ToolSelectionResult.builder()
                .itemId(RUNE_AXE_ID)
                .canEquip(false)
                .bestEquippableAlternative(IRON_AXE_ID)
                .build();

        // When not preferring equippable, should return rune (best usable)
        assertEquals(Integer.valueOf(RUNE_AXE_ID), result.getItemToUse(false).orElse(null));
    }

    @Test
    public void testGetItemToUse_CanEquipBest() {
        ToolSelection.ToolSelectionResult result = ToolSelection.ToolSelectionResult.builder()
                .itemId(RUNE_AXE_ID)
                .canEquip(true)
                .build();

        // When can equip best, both should return the same
        assertEquals(Integer.valueOf(RUNE_AXE_ID), result.getItemToUse(true).orElse(null));
        assertEquals(Integer.valueOf(RUNE_AXE_ID), result.getItemToUse(false).orElse(null));
    }
}

