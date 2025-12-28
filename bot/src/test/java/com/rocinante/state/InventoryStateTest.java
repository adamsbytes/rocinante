package com.rocinante.state;

import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InventoryState class.
 */
public class InventoryStateTest {

    // Use mock items to avoid RuneLite initialization issues
    @Mock private Item mockShark;
    @Mock private Item mockLobster;
    @Mock private Item mockCoins;
    @Mock private Item mockSword;

    // RuneLite ItemID constants for food items
    private static final int SHARK_ID = ItemID.SHARK;       // 385
    private static final int LOBSTER_ID = ItemID.LOBSTER;   // 379
    private static final int KARAMBWAN_ID = ItemID.TBWT_COOKED_KARAMBWAN; // 3144
    private static final int SALMON_ID = ItemID.SALMON;     // 329

    private Item[] testItems;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Setup mock items with correct RuneLite ItemID constants
        when(mockShark.getId()).thenReturn(SHARK_ID);
        when(mockShark.getQuantity()).thenReturn(1);

        when(mockLobster.getId()).thenReturn(LOBSTER_ID);
        when(mockLobster.getQuantity()).thenReturn(1);

        when(mockCoins.getId()).thenReturn(995);
        when(mockCoins.getQuantity()).thenReturn(1000);

        when(mockSword.getId()).thenReturn(1289);
        when(mockSword.getQuantity()).thenReturn(1);

        // Create test inventory:
        // Slot 0: Shark (385) x1
        // Slot 1: Lobster (379) x1
        // Slot 2: Coins (995) x1000
        // Slot 3: Rune sword (1289) x1
        // Slots 4-27: Empty
        testItems = new Item[28];
        testItems[0] = mockShark;
        testItems[1] = mockLobster;
        testItems[2] = mockCoins;
        testItems[3] = mockSword;
    }

    // ========================================================================
    // Construction
    // ========================================================================

    @Test
    public void testEmptyState() {
        InventoryState empty = InventoryState.EMPTY;

        assertTrue(empty.isEmpty());
        assertEquals(28, empty.getFreeSlots());
        assertEquals(0, empty.getUsedSlots());
    }

    @Test
    public void testConstructorWithNullArray() {
        InventoryState state = new InventoryState(null);

        assertTrue(state.isEmpty());
        assertEquals(28, state.getFreeSlots());
    }

    @Test
    public void testConstructorDefensiveCopy() {
        Item[] items = new Item[] { mockShark };
        InventoryState state = new InventoryState(items);

        // Modify original array
        Item mockOther = mock(Item.class);
        when(mockOther.getId()).thenReturn(999);
        items[0] = mockOther;

        // State should still have original value
        assertTrue(state.hasItem(SHARK_ID));
    }

    // ========================================================================
    // Item Query Methods
    // ========================================================================

    @Test
    public void testHasItem_Present() {
        InventoryState state = new InventoryState(testItems);

        assertTrue(state.hasItem(SHARK_ID));   // Shark
        assertTrue(state.hasItem(LOBSTER_ID)); // Lobster
        assertTrue(state.hasItem(995));        // Coins
    }

    @Test
    public void testHasItem_NotPresent() {
        InventoryState state = new InventoryState(testItems);

        assertFalse(state.hasItem(999)); // Not in inventory
    }

    @Test
    public void testHasItemWithQuantity() {
        InventoryState state = new InventoryState(testItems);

        assertTrue(state.hasItem(995, 500));  // Has 1000 coins
        assertTrue(state.hasItem(995, 1000)); // Exact amount
        assertFalse(state.hasItem(995, 1001)); // Not enough
    }

    @Test
    public void testHasAnyItem() {
        InventoryState state = new InventoryState(testItems);

        assertTrue(state.hasAnyItem(SHARK_ID, 999));   // Has shark
        assertTrue(state.hasAnyItem(999, LOBSTER_ID)); // Has lobster
        assertFalse(state.hasAnyItem(999, 888));       // Has neither
    }

    @Test
    public void testHasAllItems() {
        InventoryState state = new InventoryState(testItems);

        assertTrue(state.hasAllItems(SHARK_ID, LOBSTER_ID));  // Has both
        assertFalse(state.hasAllItems(SHARK_ID, 999));        // Missing 999
    }

    @Test
    public void testCountItem_Stackable() {
        InventoryState state = new InventoryState(testItems);

        assertEquals(1000, state.countItem(995)); // Coins
    }

    @Test
    public void testCountItem_NonStackable() {
        InventoryState state = new InventoryState(testItems);

        assertEquals(1, state.countItem(SHARK_ID)); // Single shark
    }

    @Test
    public void testCountItem_MultipleSlots() {
        Item mockShark2 = mock(Item.class);
        when(mockShark2.getId()).thenReturn(SHARK_ID);
        when(mockShark2.getQuantity()).thenReturn(1);

        Item mockShark3 = mock(Item.class);
        when(mockShark3.getId()).thenReturn(SHARK_ID);
        when(mockShark3.getQuantity()).thenReturn(1);

        Item[] items = new Item[28];
        items[0] = mockShark;
        items[1] = mockShark2;
        items[2] = mockShark3;

        InventoryState state = new InventoryState(items);

        assertEquals(3, state.countItem(SHARK_ID));
    }

    @Test
    public void testCountItem_NotPresent() {
        InventoryState state = new InventoryState(testItems);

        assertEquals(0, state.countItem(999));
    }

    // ========================================================================
    // Slot Methods
    // ========================================================================

    @Test
    public void testGetItemInSlot() {
        InventoryState state = new InventoryState(testItems);

        Optional<Item> shark = state.getItemInSlot(0);
        assertTrue(shark.isPresent());
        assertEquals(SHARK_ID, shark.get().getId());

        Optional<Item> empty = state.getItemInSlot(10);
        assertFalse(empty.isPresent());
    }

    @Test
    public void testGetItemInSlot_InvalidSlot() {
        InventoryState state = new InventoryState(testItems);

        assertFalse(state.getItemInSlot(-1).isPresent());
        assertFalse(state.getItemInSlot(28).isPresent());
    }

    @Test
    public void testGetSlotOf() {
        InventoryState state = new InventoryState(testItems);

        assertEquals(0, state.getSlotOf(SHARK_ID));   // Shark in slot 0
        assertEquals(1, state.getSlotOf(LOBSTER_ID)); // Lobster in slot 1
        assertEquals(-1, state.getSlotOf(999));       // Not in inventory
    }

    @Test
    public void testGetSlotsOf() {
        Item mockShark2 = mock(Item.class);
        when(mockShark2.getId()).thenReturn(SHARK_ID);
        when(mockShark2.getQuantity()).thenReturn(1);

        Item mockShark3 = mock(Item.class);
        when(mockShark3.getId()).thenReturn(SHARK_ID);
        when(mockShark3.getQuantity()).thenReturn(1);

        Item[] items = new Item[28];
        items[0] = mockShark;
        items[5] = mockShark2;
        items[10] = mockShark3;

        InventoryState state = new InventoryState(items);
        List<Integer> slots = state.getSlotsOf(SHARK_ID);

        assertEquals(3, slots.size());
        assertTrue(slots.contains(0));
        assertTrue(slots.contains(5));
        assertTrue(slots.contains(10));
    }

    // ========================================================================
    // Slot Management
    // ========================================================================

    @Test
    public void testGetFreeSlots() {
        InventoryState state = new InventoryState(testItems);

        assertEquals(24, state.getFreeSlots()); // 28 - 4 used
    }

    @Test
    public void testGetUsedSlots() {
        InventoryState state = new InventoryState(testItems);

        assertEquals(4, state.getUsedSlots());
    }

    @Test
    public void testIsFull() {
        Item[] fullItems = new Item[28];
        for (int i = 0; i < 28; i++) {
            Item mockItem = mock(Item.class);
            when(mockItem.getId()).thenReturn(SHARK_ID);
            when(mockItem.getQuantity()).thenReturn(1);
            fullItems[i] = mockItem;
        }

        InventoryState full = new InventoryState(fullItems);
        InventoryState notFull = new InventoryState(testItems);

        assertTrue(full.isFull());
        assertFalse(notFull.isFull());
    }

    @Test
    public void testIsEmpty() {
        InventoryState empty = new InventoryState(new Item[28]);
        InventoryState notEmpty = new InventoryState(testItems);

        assertTrue(empty.isEmpty());
        assertFalse(notEmpty.isEmpty());
    }

    @Test
    public void testGetFirstEmptySlot() {
        InventoryState state = new InventoryState(testItems);

        assertEquals(4, state.getFirstEmptySlot()); // First empty after slot 3
    }

    @Test
    public void testGetFirstEmptySlot_FullInventory() {
        Item[] fullItems = new Item[28];
        for (int i = 0; i < 28; i++) {
            Item mockItem = mock(Item.class);
            when(mockItem.getId()).thenReturn(SHARK_ID);
            when(mockItem.getQuantity()).thenReturn(1);
            fullItems[i] = mockItem;
        }

        InventoryState state = new InventoryState(fullItems);

        assertEquals(-1, state.getFirstEmptySlot());
    }

    // ========================================================================
    // Food Detection
    // ========================================================================

    @Test
    public void testGetFoodSlots() {
        InventoryState state = new InventoryState(testItems);
        List<Integer> foodSlots = state.getFoodSlots();

        assertEquals(2, foodSlots.size());
        assertTrue(foodSlots.contains(0)); // Shark
        assertTrue(foodSlots.contains(1)); // Lobster
    }

    @Test
    public void testIsFood() {
        assertTrue(InventoryState.isFood(SHARK_ID));     // Shark (385)
        assertTrue(InventoryState.isFood(LOBSTER_ID));   // Lobster (379)
        assertTrue(InventoryState.isFood(KARAMBWAN_ID)); // Karambwan (3144)
        assertFalse(InventoryState.isFood(995));         // Coins
        assertFalse(InventoryState.isFood(1289));        // Rune sword
    }

    @Test
    public void testGetFoodHealing() {
        assertEquals(20, InventoryState.getFoodHealing(SHARK_ID));     // Shark heals 20
        assertEquals(12, InventoryState.getFoodHealing(LOBSTER_ID));   // Lobster heals 12
        assertEquals(18, InventoryState.getFoodHealing(KARAMBWAN_ID)); // Karambwan heals 18
        assertEquals(0, InventoryState.getFoodHealing(995));           // Coins (not food)
    }

    @Test
    public void testGetBestFood() {
        InventoryState state = new InventoryState(testItems);

        int bestSlot = state.getBestFood();
        assertEquals(0, bestSlot); // Shark heals 20, lobster heals 12
    }

    @Test
    public void testGetBestFood_NoFood() {
        Item[] noFood = new Item[28];
        noFood[0] = mockCoins;

        InventoryState state = new InventoryState(noFood);

        assertEquals(-1, state.getBestFood());
    }

    @Test
    public void testGetOptimalFood() {
        Item mockSalmon = mock(Item.class);
        when(mockSalmon.getId()).thenReturn(SALMON_ID);  // Salmon heals 9
        when(mockSalmon.getQuantity()).thenReturn(1);

        Item[] items = new Item[28];
        items[0] = mockShark;   // 20 hp
        items[1] = mockLobster; // 12 hp
        items[2] = mockSalmon;  // 9 hp

        InventoryState state = new InventoryState(items);

        // If we need to heal 10 HP, lobster (12) is better than shark (20 - wasteful)
        int optimalSlot = state.getOptimalFood(10, 99);
        assertEquals(1, optimalSlot); // Lobster
    }

    @Test
    public void testHasFood() {
        InventoryState hasFood = new InventoryState(testItems);
        InventoryState noFood = new InventoryState(new Item[] { mockCoins });

        assertTrue(hasFood.hasFood());
        assertFalse(noFood.hasFood());
    }

    @Test
    public void testCountFood() {
        InventoryState state = new InventoryState(testItems);

        assertEquals(2, state.countFood()); // Shark + Lobster
    }

    @Test
    public void testGetTotalFoodHealing() {
        InventoryState state = new InventoryState(testItems);

        assertEquals(32, state.getTotalFoodHealing()); // Shark (20) + Lobster (12)
    }

    // ========================================================================
    // List Methods
    // ========================================================================

    @Test
    public void testGetAllItems() {
        InventoryState state = new InventoryState(testItems);
        List<Item> all = state.getAllItems();

        assertEquals(28, all.size());
        assertEquals(SHARK_ID, all.get(0).getId());
        assertNull(all.get(10)); // Empty slot
    }

    @Test
    public void testGetNonEmptyItems() {
        InventoryState state = new InventoryState(testItems);
        List<Item> nonEmpty = state.getNonEmptyItems();

        assertEquals(4, nonEmpty.size());
    }
}
