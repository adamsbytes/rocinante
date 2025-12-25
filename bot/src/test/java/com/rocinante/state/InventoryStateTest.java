package com.rocinante.state;

import net.runelite.api.Item;
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

    private Item[] testItems;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Setup mock items
        when(mockShark.getId()).thenReturn(379);
        when(mockShark.getQuantity()).thenReturn(1);

        when(mockLobster.getId()).thenReturn(347);
        when(mockLobster.getQuantity()).thenReturn(1);

        when(mockCoins.getId()).thenReturn(995);
        when(mockCoins.getQuantity()).thenReturn(1000);

        when(mockSword.getId()).thenReturn(1289);
        when(mockSword.getQuantity()).thenReturn(1);

        // Create test inventory:
        // Slot 0: Shark (379) x1
        // Slot 1: Lobster (347) x1
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
        assertTrue(state.hasItem(379));
    }

    // ========================================================================
    // Item Query Methods
    // ========================================================================

    @Test
    public void testHasItem_Present() {
        InventoryState state = new InventoryState(testItems);

        assertTrue(state.hasItem(379)); // Shark
        assertTrue(state.hasItem(347)); // Lobster
        assertTrue(state.hasItem(995)); // Coins
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

        assertTrue(state.hasAnyItem(379, 999));  // Has shark
        assertTrue(state.hasAnyItem(999, 347));  // Has lobster
        assertFalse(state.hasAnyItem(999, 888)); // Has neither
    }

    @Test
    public void testHasAllItems() {
        InventoryState state = new InventoryState(testItems);

        assertTrue(state.hasAllItems(379, 347));   // Has both
        assertFalse(state.hasAllItems(379, 999));  // Missing 999
    }

    @Test
    public void testCountItem_Stackable() {
        InventoryState state = new InventoryState(testItems);

        assertEquals(1000, state.countItem(995)); // Coins
    }

    @Test
    public void testCountItem_NonStackable() {
        InventoryState state = new InventoryState(testItems);

        assertEquals(1, state.countItem(379)); // Single shark
    }

    @Test
    public void testCountItem_MultipleSlots() {
        Item mockShark2 = mock(Item.class);
        when(mockShark2.getId()).thenReturn(379);
        when(mockShark2.getQuantity()).thenReturn(1);

        Item mockShark3 = mock(Item.class);
        when(mockShark3.getId()).thenReturn(379);
        when(mockShark3.getQuantity()).thenReturn(1);

        Item[] items = new Item[28];
        items[0] = mockShark;
        items[1] = mockShark2;
        items[2] = mockShark3;

        InventoryState state = new InventoryState(items);

        assertEquals(3, state.countItem(379));
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
        assertEquals(379, shark.get().getId());

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

        assertEquals(0, state.getSlotOf(379));  // Shark in slot 0
        assertEquals(1, state.getSlotOf(347));  // Lobster in slot 1
        assertEquals(-1, state.getSlotOf(999)); // Not in inventory
    }

    @Test
    public void testGetSlotsOf() {
        Item mockShark2 = mock(Item.class);
        when(mockShark2.getId()).thenReturn(379);
        when(mockShark2.getQuantity()).thenReturn(1);

        Item mockShark3 = mock(Item.class);
        when(mockShark3.getId()).thenReturn(379);
        when(mockShark3.getQuantity()).thenReturn(1);

        Item[] items = new Item[28];
        items[0] = mockShark;
        items[5] = mockShark2;
        items[10] = mockShark3;

        InventoryState state = new InventoryState(items);
        List<Integer> slots = state.getSlotsOf(379);

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
            when(mockItem.getId()).thenReturn(379);
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
            when(mockItem.getId()).thenReturn(379);
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
        assertTrue(InventoryState.isFood(379));  // Shark
        assertTrue(InventoryState.isFood(347));  // Lobster
        assertTrue(InventoryState.isFood(3144)); // Karambwan
        assertFalse(InventoryState.isFood(995)); // Coins
        assertFalse(InventoryState.isFood(1289)); // Rune sword
    }

    @Test
    public void testGetFoodHealing() {
        assertEquals(20, InventoryState.getFoodHealing(379)); // Shark
        assertEquals(12, InventoryState.getFoodHealing(347)); // Lobster
        assertEquals(18, InventoryState.getFoodHealing(3144)); // Karambwan
        assertEquals(0, InventoryState.getFoodHealing(995));  // Coins (not food)
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
        Item mockTrout = mock(Item.class);
        when(mockTrout.getId()).thenReturn(329);
        when(mockTrout.getQuantity()).thenReturn(1);

        Item[] items = new Item[28];
        items[0] = mockShark;   // 20 hp
        items[1] = mockLobster; // 12 hp
        items[2] = mockTrout;   // 7 hp

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
        assertEquals(379, all.get(0).getId());
        assertNull(all.get(10)); // Empty slot
    }

    @Test
    public void testGetNonEmptyItems() {
        InventoryState state = new InventoryState(testItems);
        List<Item> nonEmpty = state.getNonEmptyItems();

        assertEquals(4, nonEmpty.size());
    }
}
