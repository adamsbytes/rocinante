package com.rocinante.state;

import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GrandExchangeSlot class.
 */
public class GrandExchangeSlotTest {

    // Test item IDs
    private static final int LOBSTER = 379;
    private static final int COINS = 995;

    @Mock private GrandExchangeOffer mockBuyingOffer;
    @Mock private GrandExchangeOffer mockBoughtOffer;
    @Mock private GrandExchangeOffer mockSellingOffer;
    @Mock private GrandExchangeOffer mockSoldOffer;
    @Mock private GrandExchangeOffer mockCancelledBuyOffer;
    @Mock private GrandExchangeOffer mockCancelledSellOffer;
    @Mock private GrandExchangeOffer mockEmptyOffer;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Setup BUYING offer
        when(mockBuyingOffer.getState()).thenReturn(GrandExchangeOfferState.BUYING);
        when(mockBuyingOffer.getItemId()).thenReturn(LOBSTER);
        when(mockBuyingOffer.getPrice()).thenReturn(200);
        when(mockBuyingOffer.getTotalQuantity()).thenReturn(100);
        when(mockBuyingOffer.getQuantitySold()).thenReturn(50);
        when(mockBuyingOffer.getSpent()).thenReturn(10000);

        // Setup BOUGHT offer
        when(mockBoughtOffer.getState()).thenReturn(GrandExchangeOfferState.BOUGHT);
        when(mockBoughtOffer.getItemId()).thenReturn(LOBSTER);
        when(mockBoughtOffer.getPrice()).thenReturn(200);
        when(mockBoughtOffer.getTotalQuantity()).thenReturn(100);
        when(mockBoughtOffer.getQuantitySold()).thenReturn(100);
        when(mockBoughtOffer.getSpent()).thenReturn(20000);

        // Setup SELLING offer
        when(mockSellingOffer.getState()).thenReturn(GrandExchangeOfferState.SELLING);
        when(mockSellingOffer.getItemId()).thenReturn(COINS);
        when(mockSellingOffer.getPrice()).thenReturn(150);
        when(mockSellingOffer.getTotalQuantity()).thenReturn(200);
        when(mockSellingOffer.getQuantitySold()).thenReturn(75);
        when(mockSellingOffer.getSpent()).thenReturn(11250);

        // Setup SOLD offer
        when(mockSoldOffer.getState()).thenReturn(GrandExchangeOfferState.SOLD);
        when(mockSoldOffer.getItemId()).thenReturn(COINS);
        when(mockSoldOffer.getPrice()).thenReturn(150);
        when(mockSoldOffer.getTotalQuantity()).thenReturn(200);
        when(mockSoldOffer.getQuantitySold()).thenReturn(200);
        when(mockSoldOffer.getSpent()).thenReturn(30000);

        // Setup CANCELLED_BUY offer
        when(mockCancelledBuyOffer.getState()).thenReturn(GrandExchangeOfferState.CANCELLED_BUY);
        when(mockCancelledBuyOffer.getItemId()).thenReturn(LOBSTER);
        when(mockCancelledBuyOffer.getPrice()).thenReturn(200);
        when(mockCancelledBuyOffer.getTotalQuantity()).thenReturn(100);
        when(mockCancelledBuyOffer.getQuantitySold()).thenReturn(25);
        when(mockCancelledBuyOffer.getSpent()).thenReturn(5000);

        // Setup CANCELLED_SELL offer
        when(mockCancelledSellOffer.getState()).thenReturn(GrandExchangeOfferState.CANCELLED_SELL);
        when(mockCancelledSellOffer.getItemId()).thenReturn(COINS);
        when(mockCancelledSellOffer.getPrice()).thenReturn(150);
        when(mockCancelledSellOffer.getTotalQuantity()).thenReturn(200);
        when(mockCancelledSellOffer.getQuantitySold()).thenReturn(50);
        when(mockCancelledSellOffer.getSpent()).thenReturn(7500);

        // Setup EMPTY offer
        when(mockEmptyOffer.getState()).thenReturn(GrandExchangeOfferState.EMPTY);
        when(mockEmptyOffer.getItemId()).thenReturn(0);
        when(mockEmptyOffer.getPrice()).thenReturn(0);
        when(mockEmptyOffer.getTotalQuantity()).thenReturn(0);
        when(mockEmptyOffer.getQuantitySold()).thenReturn(0);
        when(mockEmptyOffer.getSpent()).thenReturn(0);
    }

    // ========================================================================
    // Factory Method Tests
    // ========================================================================

    @Test
    public void testFromOffer_Buying() {
        GrandExchangeSlot slot = GrandExchangeSlot.fromOffer(mockBuyingOffer, 0);

        assertEquals(0, slot.getSlotIndex());
        assertEquals(GrandExchangeOfferState.BUYING, slot.getState());
        assertEquals(LOBSTER, slot.getItemId());
        assertEquals(200, slot.getPrice());
        assertEquals(100, slot.getTotalQuantity());
        assertEquals(50, slot.getQuantitySold());
        assertEquals(10000, slot.getSpent());
    }

    @Test
    public void testFromOffer_Null() {
        GrandExchangeSlot slot = GrandExchangeSlot.fromOffer(null, 3);

        assertEquals(3, slot.getSlotIndex());
        assertTrue(slot.isEmpty());
        assertEquals(-1, slot.getItemId());
    }

    @Test
    public void testEmpty() {
        GrandExchangeSlot slot = GrandExchangeSlot.empty(5);

        assertEquals(5, slot.getSlotIndex());
        assertTrue(slot.isEmpty());
        assertEquals(GrandExchangeOfferState.EMPTY, slot.getState());
        assertEquals(-1, slot.getItemId());
        assertEquals(0, slot.getPrice());
        assertEquals(0, slot.getTotalQuantity());
        assertEquals(0, slot.getQuantitySold());
        assertEquals(0, slot.getSpent());
    }

    // ========================================================================
    // State Query Tests
    // ========================================================================

    @Test
    public void testIsEmpty() {
        assertTrue(GrandExchangeSlot.fromOffer(mockEmptyOffer, 0).isEmpty());
        assertTrue(GrandExchangeSlot.empty(0).isEmpty());
        assertFalse(GrandExchangeSlot.fromOffer(mockBuyingOffer, 0).isEmpty());
        assertFalse(GrandExchangeSlot.fromOffer(mockSellingOffer, 0).isEmpty());
    }

    @Test
    public void testIsBuyOffer() {
        assertTrue(GrandExchangeSlot.fromOffer(mockBuyingOffer, 0).isBuyOffer());
        assertTrue(GrandExchangeSlot.fromOffer(mockBoughtOffer, 0).isBuyOffer());
        assertTrue(GrandExchangeSlot.fromOffer(mockCancelledBuyOffer, 0).isBuyOffer());
        assertFalse(GrandExchangeSlot.fromOffer(mockSellingOffer, 0).isBuyOffer());
        assertFalse(GrandExchangeSlot.fromOffer(mockSoldOffer, 0).isBuyOffer());
        assertFalse(GrandExchangeSlot.fromOffer(mockEmptyOffer, 0).isBuyOffer());
    }

    @Test
    public void testIsSellOffer() {
        assertTrue(GrandExchangeSlot.fromOffer(mockSellingOffer, 0).isSellOffer());
        assertTrue(GrandExchangeSlot.fromOffer(mockSoldOffer, 0).isSellOffer());
        assertTrue(GrandExchangeSlot.fromOffer(mockCancelledSellOffer, 0).isSellOffer());
        assertFalse(GrandExchangeSlot.fromOffer(mockBuyingOffer, 0).isSellOffer());
        assertFalse(GrandExchangeSlot.fromOffer(mockBoughtOffer, 0).isSellOffer());
        assertFalse(GrandExchangeSlot.fromOffer(mockEmptyOffer, 0).isSellOffer());
    }

    @Test
    public void testIsActive() {
        assertTrue(GrandExchangeSlot.fromOffer(mockBuyingOffer, 0).isActive());
        assertTrue(GrandExchangeSlot.fromOffer(mockSellingOffer, 0).isActive());
        assertFalse(GrandExchangeSlot.fromOffer(mockBoughtOffer, 0).isActive());
        assertFalse(GrandExchangeSlot.fromOffer(mockSoldOffer, 0).isActive());
        assertFalse(GrandExchangeSlot.fromOffer(mockCancelledBuyOffer, 0).isActive());
        assertFalse(GrandExchangeSlot.fromOffer(mockEmptyOffer, 0).isActive());
    }

    @Test
    public void testIsCompleted() {
        assertTrue(GrandExchangeSlot.fromOffer(mockBoughtOffer, 0).isCompleted());
        assertTrue(GrandExchangeSlot.fromOffer(mockSoldOffer, 0).isCompleted());
        assertFalse(GrandExchangeSlot.fromOffer(mockBuyingOffer, 0).isCompleted());
        assertFalse(GrandExchangeSlot.fromOffer(mockSellingOffer, 0).isCompleted());
        assertFalse(GrandExchangeSlot.fromOffer(mockCancelledBuyOffer, 0).isCompleted());
        assertFalse(GrandExchangeSlot.fromOffer(mockEmptyOffer, 0).isCompleted());
    }

    @Test
    public void testIsCancelled() {
        assertTrue(GrandExchangeSlot.fromOffer(mockCancelledBuyOffer, 0).isCancelled());
        assertTrue(GrandExchangeSlot.fromOffer(mockCancelledSellOffer, 0).isCancelled());
        assertFalse(GrandExchangeSlot.fromOffer(mockBuyingOffer, 0).isCancelled());
        assertFalse(GrandExchangeSlot.fromOffer(mockBoughtOffer, 0).isCancelled());
        assertFalse(GrandExchangeSlot.fromOffer(mockSellingOffer, 0).isCancelled());
        assertFalse(GrandExchangeSlot.fromOffer(mockEmptyOffer, 0).isCancelled());
    }

    @Test
    public void testHasItemsToCollect() {
        // Completed offers always have items to collect
        assertTrue(GrandExchangeSlot.fromOffer(mockBoughtOffer, 0).hasItemsToCollect());
        assertTrue(GrandExchangeSlot.fromOffer(mockSoldOffer, 0).hasItemsToCollect());

        // Cancelled offers have items to collect
        assertTrue(GrandExchangeSlot.fromOffer(mockCancelledBuyOffer, 0).hasItemsToCollect());
        assertTrue(GrandExchangeSlot.fromOffer(mockCancelledSellOffer, 0).hasItemsToCollect());

        // Active offers with progress have items to collect
        assertTrue(GrandExchangeSlot.fromOffer(mockBuyingOffer, 0).hasItemsToCollect());
        assertTrue(GrandExchangeSlot.fromOffer(mockSellingOffer, 0).hasItemsToCollect());

        // Empty offers have nothing to collect
        assertFalse(GrandExchangeSlot.fromOffer(mockEmptyOffer, 0).hasItemsToCollect());
        assertFalse(GrandExchangeSlot.empty(0).hasItemsToCollect());
    }

    // ========================================================================
    // Progress Query Tests
    // ========================================================================

    @Test
    public void testGetRemainingQuantity() {
        GrandExchangeSlot buying = GrandExchangeSlot.fromOffer(mockBuyingOffer, 0);
        assertEquals(50, buying.getRemainingQuantity()); // 100 - 50

        GrandExchangeSlot bought = GrandExchangeSlot.fromOffer(mockBoughtOffer, 0);
        assertEquals(0, bought.getRemainingQuantity()); // 100 - 100

        GrandExchangeSlot empty = GrandExchangeSlot.empty(0);
        assertEquals(0, empty.getRemainingQuantity()); // 0 - 0
    }

    @Test
    public void testGetCompletionRatio() {
        GrandExchangeSlot buying = GrandExchangeSlot.fromOffer(mockBuyingOffer, 0);
        assertEquals(0.5, buying.getCompletionRatio(), 0.001); // 50/100

        GrandExchangeSlot bought = GrandExchangeSlot.fromOffer(mockBoughtOffer, 0);
        assertEquals(1.0, bought.getCompletionRatio(), 0.001); // 100/100

        GrandExchangeSlot empty = GrandExchangeSlot.empty(0);
        assertEquals(0.0, empty.getCompletionRatio(), 0.001);
    }

    @Test
    public void testGetCompletionPercent() {
        GrandExchangeSlot buying = GrandExchangeSlot.fromOffer(mockBuyingOffer, 0);
        assertEquals(50, buying.getCompletionPercent());

        GrandExchangeSlot selling = GrandExchangeSlot.fromOffer(mockSellingOffer, 0);
        assertEquals(37, selling.getCompletionPercent()); // 75/200 = 0.375 = 37%

        GrandExchangeSlot bought = GrandExchangeSlot.fromOffer(mockBoughtOffer, 0);
        assertEquals(100, bought.getCompletionPercent());
    }

    @Test
    public void testGetAveragePrice() {
        GrandExchangeSlot buying = GrandExchangeSlot.fromOffer(mockBuyingOffer, 0);
        assertEquals(200, buying.getAveragePrice()); // 10000/50

        GrandExchangeSlot empty = GrandExchangeSlot.empty(0);
        assertEquals(0, empty.getAveragePrice()); // Returns price (0) when no items sold
    }

    @Test
    public void testGetTotalValue() {
        GrandExchangeSlot buying = GrandExchangeSlot.fromOffer(mockBuyingOffer, 0);
        assertEquals(20000L, buying.getTotalValue()); // 200 * 100

        GrandExchangeSlot selling = GrandExchangeSlot.fromOffer(mockSellingOffer, 0);
        assertEquals(30000L, selling.getTotalValue()); // 150 * 200
    }

    @Test
    public void testGetRemainingValue() {
        GrandExchangeSlot buying = GrandExchangeSlot.fromOffer(mockBuyingOffer, 0);
        assertEquals(10000L, buying.getRemainingValue()); // 200 * 50

        GrandExchangeSlot bought = GrandExchangeSlot.fromOffer(mockBoughtOffer, 0);
        assertEquals(0L, bought.getRemainingValue()); // 200 * 0
    }

    // ========================================================================
    // Utility Tests
    // ========================================================================

    @Test
    public void testGetSummary_Empty() {
        GrandExchangeSlot empty = GrandExchangeSlot.empty(3);
        String summary = empty.getSummary();

        assertTrue(summary.contains("Slot[3"));
        assertTrue(summary.contains("EMPTY"));
    }

    @Test
    public void testGetSummary_BuyOffer() {
        GrandExchangeSlot buying = GrandExchangeSlot.fromOffer(mockBuyingOffer, 1);
        String summary = buying.getSummary();

        assertTrue(summary.contains("Slot[1"));
        assertTrue(summary.contains("BUY"));
        assertTrue(summary.contains("BUYING"));
        assertTrue(summary.contains(String.valueOf(LOBSTER)));
    }

    @Test
    public void testGetSummary_SellOffer() {
        GrandExchangeSlot selling = GrandExchangeSlot.fromOffer(mockSellingOffer, 2);
        String summary = selling.getSummary();

        assertTrue(summary.contains("Slot[2"));
        assertTrue(summary.contains("SELL"));
        assertTrue(summary.contains("SELLING"));
    }

    @Test
    public void testToString() {
        GrandExchangeSlot slot = GrandExchangeSlot.fromOffer(mockBuyingOffer, 0);
        assertEquals(slot.getSummary(), slot.toString());
    }

    // ========================================================================
    // Builder Tests
    // ========================================================================

    @Test
    public void testBuilder() {
        GrandExchangeSlot slot = GrandExchangeSlot.builder()
                .slotIndex(7)
                .state(GrandExchangeOfferState.BUYING)
                .itemId(12345)
                .price(1000)
                .totalQuantity(50)
                .quantitySold(25)
                .spent(25000)
                .build();

        assertEquals(7, slot.getSlotIndex());
        assertEquals(GrandExchangeOfferState.BUYING, slot.getState());
        assertEquals(12345, slot.getItemId());
        assertEquals(1000, slot.getPrice());
        assertEquals(50, slot.getTotalQuantity());
        assertEquals(25, slot.getQuantitySold());
        assertEquals(25000, slot.getSpent());
    }
}

