package com.rocinante.state;

import net.runelite.api.Item;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BankState class.
 */
public class BankStateTest {

    // Common item IDs for testing
    private static final int COINS = 995;
    private static final int SHARK = 379;
    private static final int LOBSTER = 347;
    private static final int RUNE_ESSENCE = 1436;
    private static final int ABYSSAL_WHIP = 4151;

    @Mock private Item mockCoins;
    @Mock private Item mockShark;
    @Mock private Item mockLobster;
    @Mock private Item mockRuneEssence;

    private List<BankState.BankItem> testBankItems;
    private BankState testState;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Setup mock items for fromItemContainer tests
        when(mockCoins.getId()).thenReturn(COINS);
        when(mockCoins.getQuantity()).thenReturn(1000000);

        when(mockShark.getId()).thenReturn(SHARK);
        when(mockShark.getQuantity()).thenReturn(50);

        when(mockLobster.getId()).thenReturn(LOBSTER);
        when(mockLobster.getQuantity()).thenReturn(100);

        when(mockRuneEssence.getId()).thenReturn(RUNE_ESSENCE);
        when(mockRuneEssence.getQuantity()).thenReturn(500);

        // Create test bank items
        testBankItems = new ArrayList<>();
        testBankItems.add(BankState.BankItem.builder()
                .itemId(COINS)
                .quantity(1000000)
                .slot(0)
                .tab(0)
                .build());
        testBankItems.add(BankState.BankItem.builder()
                .itemId(SHARK)
                .quantity(50)
                .slot(1)
                .tab(0)
                .build());
        testBankItems.add(BankState.BankItem.builder()
                .itemId(LOBSTER)
                .quantity(100)
                .slot(2)
                .tab(1)
                .build());
        testBankItems.add(BankState.BankItem.builder()
                .itemId(RUNE_ESSENCE)
                .quantity(500)
                .slot(3)
                .tab(1)
                .build());

        testState = new BankState(testBankItems, 12345, false);
    }

    // ========================================================================
    // Construction Tests
    // ========================================================================

    @Test
    public void testEmptyState() {
        BankState empty = BankState.EMPTY;

        assertTrue(empty.isEmpty());
        assertEquals(0, empty.getUsedSlots());
        assertEquals(0, empty.getUniqueItemCount());
        assertFalse(empty.isUnknown());
    }

    @Test
    public void testUnknownState() {
        BankState unknown = BankState.UNKNOWN;

        assertTrue(unknown.isEmpty());
        assertTrue(unknown.isUnknown());
        assertFalse(unknown.isKnown());
    }

    @Test
    public void testConstructorWithNullList() {
        BankState state = new BankState(null, 100, false);

        assertTrue(state.isEmpty());
        assertEquals(0, state.getUsedSlots());
    }

    @Test
    public void testConstructorDefensiveCopy() {
        List<BankState.BankItem> items = new ArrayList<>();
        items.add(BankState.BankItem.builder()
                .itemId(COINS)
                .quantity(1000)
                .slot(0)
                .tab(0)
                .build());

        BankState state = new BankState(items, 100, false);

        // Modify original list
        items.clear();

        // State should still have the item
        assertTrue(state.hasItem(COINS));
        assertEquals(1, state.getUsedSlots());
    }

    @Test
    public void testFromItemContainer() {
        Item[] items = new Item[10];
        items[0] = mockCoins;
        items[1] = mockShark;
        items[2] = mockLobster;
        // slots 3-9 are null (empty)

        BankState state = BankState.fromItemContainer(items, 100);

        assertFalse(state.isEmpty());
        assertEquals(3, state.getUsedSlots());
        assertTrue(state.hasItem(COINS));
        assertTrue(state.hasItem(SHARK));
        assertTrue(state.hasItem(LOBSTER));
        assertEquals(100, state.getLastUpdatedTick());
    }

    @Test
    public void testFromItemContainerFiltersFillers() {
        Item mockFiller = mock(Item.class);
        when(mockFiller.getId()).thenReturn(BankState.BANK_FILLER_ID);
        when(mockFiller.getQuantity()).thenReturn(1);

        Item[] items = new Item[5];
        items[0] = mockCoins;
        items[1] = mockFiller;  // Should be filtered
        items[2] = mockShark;

        BankState state = BankState.fromItemContainer(items, 100);

        assertEquals(2, state.getUsedSlots());
        assertFalse(state.hasItem(BankState.BANK_FILLER_ID));
    }

    // ========================================================================
    // Item Query Tests
    // ========================================================================

    @Test
    public void testHasItem_Present() {
        assertTrue(testState.hasItem(COINS));
        assertTrue(testState.hasItem(SHARK));
        assertTrue(testState.hasItem(LOBSTER));
        assertTrue(testState.hasItem(RUNE_ESSENCE));
    }

    @Test
    public void testHasItem_NotPresent() {
        assertFalse(testState.hasItem(ABYSSAL_WHIP));
        assertFalse(testState.hasItem(999));
    }

    @Test
    public void testHasItemWithQuantity() {
        assertTrue(testState.hasItem(COINS, 500000));   // Has 1M
        assertTrue(testState.hasItem(COINS, 1000000));  // Exact amount
        assertFalse(testState.hasItem(COINS, 2000000)); // Not enough

        assertTrue(testState.hasItem(SHARK, 50));   // Exact
        assertFalse(testState.hasItem(SHARK, 51));  // Not enough
    }

    @Test
    public void testHasAnyItem() {
        assertTrue(testState.hasAnyItem(COINS, ABYSSAL_WHIP));   // Has coins
        assertTrue(testState.hasAnyItem(ABYSSAL_WHIP, SHARK));   // Has shark
        assertFalse(testState.hasAnyItem(ABYSSAL_WHIP, 999));    // Has neither
    }

    @Test
    public void testHasAllItems() {
        assertTrue(testState.hasAllItems(COINS, SHARK));       // Has both
        assertTrue(testState.hasAllItems(COINS, SHARK, LOBSTER)); // Has all three
        assertFalse(testState.hasAllItems(COINS, ABYSSAL_WHIP)); // Missing whip
    }

    @Test
    public void testCountItem() {
        assertEquals(1000000, testState.countItem(COINS));
        assertEquals(50, testState.countItem(SHARK));
        assertEquals(100, testState.countItem(LOBSTER));
        assertEquals(0, testState.countItem(ABYSSAL_WHIP));
    }

    @Test
    public void testGetSlotOf() {
        assertEquals(0, testState.getSlotOf(COINS));
        assertEquals(1, testState.getSlotOf(SHARK));
        assertEquals(2, testState.getSlotOf(LOBSTER));
        assertEquals(-1, testState.getSlotOf(ABYSSAL_WHIP));
    }

    @Test
    public void testGetSlotsOf() {
        List<Integer> coinSlots = testState.getSlotsOf(COINS);
        assertEquals(1, coinSlots.size());
        assertTrue(coinSlots.contains(0));

        List<Integer> missingSlots = testState.getSlotsOf(ABYSSAL_WHIP);
        assertTrue(missingSlots.isEmpty());
    }

    @Test
    public void testGetSlotsOf_MultipleSlots() {
        // Create state with same item in multiple slots
        List<BankState.BankItem> items = new ArrayList<>();
        items.add(BankState.BankItem.builder().itemId(SHARK).quantity(10).slot(0).tab(0).build());
        items.add(BankState.BankItem.builder().itemId(SHARK).quantity(20).slot(5).tab(0).build());
        items.add(BankState.BankItem.builder().itemId(SHARK).quantity(30).slot(10).tab(1).build());

        BankState state = new BankState(items, 100, false);

        List<Integer> slots = state.getSlotsOf(SHARK);
        assertEquals(3, slots.size());
        assertTrue(slots.contains(0));
        assertTrue(slots.contains(5));
        assertTrue(slots.contains(10));

        // Total count should sum all
        assertEquals(60, state.countItem(SHARK));
    }

    @Test
    public void testGetItemAtSlot() {
        Optional<BankState.BankItem> item0 = testState.getItemAtSlot(0);
        assertTrue(item0.isPresent());
        assertEquals(COINS, item0.get().getItemId());

        Optional<BankState.BankItem> item99 = testState.getItemAtSlot(99);
        assertFalse(item99.isPresent());
    }

    @Test
    public void testGetAllItemIds() {
        Set<Integer> ids = testState.getAllItemIds();

        assertEquals(4, ids.size());
        assertTrue(ids.contains(COINS));
        assertTrue(ids.contains(SHARK));
        assertTrue(ids.contains(LOBSTER));
        assertTrue(ids.contains(RUNE_ESSENCE));
    }

    // ========================================================================
    // Tab Operation Tests
    // ========================================================================

    @Test
    public void testGetItemsInTab() {
        List<BankState.BankItem> tab0 = testState.getItemsInTab(0);
        assertEquals(2, tab0.size()); // Coins and Shark

        List<BankState.BankItem> tab1 = testState.getItemsInTab(1);
        assertEquals(2, tab1.size()); // Lobster and Rune essence

        List<BankState.BankItem> tab5 = testState.getItemsInTab(5);
        assertTrue(tab5.isEmpty());
    }

    @Test
    public void testGetTabCount() {
        assertEquals(2, testState.getTabCount()); // Tab 0 and Tab 1
    }

    @Test
    public void testHasItemsInTab() {
        assertTrue(testState.hasItemsInTab(0));
        assertTrue(testState.hasItemsInTab(1));
        assertFalse(testState.hasItemsInTab(5));
    }

    @Test
    public void testGetTabOf() {
        assertEquals(0, testState.getTabOf(COINS));
        assertEquals(0, testState.getTabOf(SHARK));
        assertEquals(1, testState.getTabOf(LOBSTER));
        assertEquals(-1, testState.getTabOf(ABYSSAL_WHIP));
    }

    // ========================================================================
    // Statistics Tests
    // ========================================================================

    @Test
    public void testGetUniqueItemCount() {
        assertEquals(4, testState.getUniqueItemCount());
        assertEquals(0, BankState.EMPTY.getUniqueItemCount());
    }

    @Test
    public void testGetUsedSlots() {
        assertEquals(4, testState.getUsedSlots());
        assertEquals(0, BankState.EMPTY.getUsedSlots());
    }

    @Test
    public void testGetFreeSlots() {
        assertEquals(812, testState.getFreeSlots(816)); // 816 - 4
        assertEquals(96, testState.getFreeSlots(100));  // 100 - 4
    }

    @Test
    public void testIsEmpty() {
        assertFalse(testState.isEmpty());
        assertTrue(BankState.EMPTY.isEmpty());
        assertTrue(BankState.UNKNOWN.isEmpty());
    }

    // ========================================================================
    // Freshness and Validity Tests
    // ========================================================================

    @Test
    public void testIsUnknown() {
        assertTrue(BankState.UNKNOWN.isUnknown());
        assertFalse(BankState.EMPTY.isUnknown());
        assertFalse(testState.isUnknown());
    }

    @Test
    public void testIsKnown() {
        assertFalse(BankState.UNKNOWN.isKnown());
        assertTrue(BankState.EMPTY.isKnown());
        assertTrue(testState.isKnown());
    }

    @Test
    public void testGetAgeInTicks() {
        assertEquals(100, testState.getAgeInTicks(12445)); // 12445 - 12345
        assertEquals(0, testState.getAgeInTicks(12345));   // Same tick
        assertEquals(-1, BankState.UNKNOWN.getAgeInTicks(100)); // Never updated
    }

    @Test
    public void testIsStale() {
        // testState was updated at tick 12345
        assertFalse(testState.isStale(12350, 10));  // 5 ticks old, threshold 10
        assertTrue(testState.isStale(12360, 10));   // 15 ticks old, threshold 10
        assertTrue(BankState.UNKNOWN.isStale(100, 10)); // Unknown is always stale
    }

    @Test
    public void testGetLastUpdatedTick() {
        assertEquals(12345, testState.getLastUpdatedTick());
        assertEquals(-1, BankState.UNKNOWN.getLastUpdatedTick());
        assertEquals(-1, BankState.EMPTY.getLastUpdatedTick());
    }

    // ========================================================================
    // BankItem Tests
    // ========================================================================

    @Test
    public void testBankItemBuilder() {
        BankState.BankItem item = BankState.BankItem.builder()
                .itemId(COINS)
                .quantity(1000)
                .slot(5)
                .tab(2)
                .build();

        assertEquals(COINS, item.getItemId());
        assertEquals(1000, item.getQuantity());
        assertEquals(5, item.getSlot());
        assertEquals(2, item.getTab());
    }

    @Test
    public void testBankItemDefaultTab() {
        BankState.BankItem item = BankState.BankItem.builder()
                .itemId(COINS)
                .quantity(1000)
                .slot(0)
                .build();

        assertEquals(0, item.getTab()); // Default tab is 0
    }

    @Test
    public void testBankItemIsPlaceholder() {
        BankState.BankItem placeholder = BankState.BankItem.builder()
                .itemId(COINS)
                .quantity(0)
                .slot(0)
                .tab(0)
                .build();

        BankState.BankItem normal = BankState.BankItem.builder()
                .itemId(COINS)
                .quantity(1)
                .slot(0)
                .tab(0)
                .build();

        assertTrue(placeholder.isPlaceholder());
        assertFalse(normal.isPlaceholder());
    }

    // ========================================================================
    // Utility Tests
    // ========================================================================

    @Test
    public void testGetSummary() {
        String summary = testState.getSummary();

        assertTrue(summary.contains("items=4"));
        assertTrue(summary.contains("unique=4"));
        assertTrue(summary.contains("tabs=2"));
        assertTrue(summary.contains("tick=12345"));
    }

    @Test
    public void testGetSummaryEmpty() {
        String summary = BankState.EMPTY.getSummary();
        assertTrue(summary.contains("EMPTY"));
    }

    @Test
    public void testGetSummaryUnknown() {
        String summary = BankState.UNKNOWN.getSummary();
        assertTrue(summary.contains("UNKNOWN"));
    }

    @Test
    public void testToString() {
        // toString should return same as getSummary
        assertEquals(testState.getSummary(), testState.toString());
    }

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    @Test
    public void testEmptyItemList() {
        BankState state = new BankState(new ArrayList<>(), 100, false);

        assertTrue(state.isEmpty());
        assertEquals(0, state.countItem(COINS));
        assertEquals(-1, state.getSlotOf(COINS));
        assertTrue(state.getSlotsOf(COINS).isEmpty());
    }

    @Test
    public void testLargeBank() {
        List<BankState.BankItem> items = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            items.add(BankState.BankItem.builder()
                    .itemId(1000 + i)
                    .quantity(i + 1)
                    .slot(i)
                    .tab(i % 10)
                    .build());
        }

        BankState state = new BankState(items, 100, false);

        assertEquals(500, state.getUsedSlots());
        assertEquals(500, state.getUniqueItemCount());
        assertEquals(10, state.getTabCount());
        assertTrue(state.hasItem(1000));
        assertTrue(state.hasItem(1499));
    }
}

