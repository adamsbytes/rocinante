package com.rocinante.state;

import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GrandExchangeState class.
 */
public class GrandExchangeStateTest {

    // Test item IDs
    private static final int LOBSTER = 379;
    private static final int SHARK = 385;
    private static final int COINS = 995;
    private static final int RUNE_ORE = 451;

    private GrandExchangeOffer createMockOffer(GrandExchangeOfferState state, int itemId, 
            int price, int totalQty, int qtySold, int spent) {
        GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
        when(offer.getState()).thenReturn(state);
        when(offer.getItemId()).thenReturn(itemId);
        when(offer.getPrice()).thenReturn(price);
        when(offer.getTotalQuantity()).thenReturn(totalQty);
        when(offer.getQuantitySold()).thenReturn(qtySold);
        when(offer.getSpent()).thenReturn(spent);
        return offer;
    }

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ========================================================================
    // Constant Tests
    // ========================================================================

    @Test
    public void testConstants() {
        assertEquals(8, GrandExchangeState.TOTAL_SLOTS);
        assertEquals(2, GrandExchangeState.F2P_SLOTS);
        assertEquals(8, GrandExchangeState.MEMBERS_SLOTS);
        assertEquals(Duration.ofHours(4), GrandExchangeState.BUY_LIMIT_RESET_DURATION);
    }

    // ========================================================================
    // Static State Tests
    // ========================================================================

    @Test
    public void testEmptyState() {
        GrandExchangeState empty = GrandExchangeState.EMPTY;

        assertFalse(empty.isUnknown());
        assertEquals(8, empty.getSlots().size());
        assertTrue(empty.getEmptySlots().size() > 0);
        assertEquals(-1, empty.getLastUpdatedTick());
    }

    @Test
    public void testUnknownState() {
        GrandExchangeState unknown = GrandExchangeState.UNKNOWN;

        assertTrue(unknown.isUnknown());
        assertEquals(8, unknown.getSlots().size());
        assertEquals(-1, unknown.getLastUpdatedTick());
    }

    // ========================================================================
    // Factory Method Tests
    // ========================================================================

    @Test
    public void testFromOffers_NullOffers() {
        GrandExchangeState state = GrandExchangeState.fromOffers(null, null, 100, true);

        assertSame(GrandExchangeState.EMPTY, state);
    }

    @Test
    public void testFromOffers_MemberState() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        offers[1] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        offers[2] = createMockOffer(GrandExchangeOfferState.SELLING, SHARK, 1000, 50, 25, 25000);
        for (int i = 3; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 12345, true);

        assertFalse(state.isUnknown());
        assertTrue(state.isMember());
        assertEquals(8, state.getAvailableSlotCount());
        assertEquals(12345, state.getLastUpdatedTick());
        assertEquals(2, state.getActiveOffers().size());
    }

    @Test
    public void testFromOffers_F2PState() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        for (int i = 0; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, false);

        assertFalse(state.isMember());
        assertEquals(2, state.getAvailableSlotCount());
        assertEquals(1, state.getActiveOffers().size());
    }

    // ========================================================================
    // Slot Query Tests
    // ========================================================================

    @Test
    public void testGetSlot_ValidIndex() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        for (int i = 1; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, true);

        GrandExchangeSlot slot = state.getSlot(0);
        assertNotNull(slot);
        assertEquals(LOBSTER, slot.getItemId());
        assertEquals(GrandExchangeOfferState.BUYING, slot.getState());
    }

    @Test
    public void testGetSlot_InvalidIndex() {
        GrandExchangeState state = GrandExchangeState.EMPTY;

        // Invalid indices return empty slots
        GrandExchangeSlot slot = state.getSlot(-1);
        assertTrue(slot.isEmpty());

        slot = state.getSlot(10);
        assertTrue(slot.isEmpty());
    }

    @Test
    public void testGetAvailableSlotCount() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        for (int i = 0; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState memberState = GrandExchangeState.fromOffers(offers, null, 100, true);
        assertEquals(8, memberState.getAvailableSlotCount());

        GrandExchangeState f2pState = GrandExchangeState.fromOffers(offers, null, 100, false);
        assertEquals(2, f2pState.getAvailableSlotCount());
    }

    @Test
    public void testGetAvailableSlots() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        for (int i = 0; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        }

        GrandExchangeState memberState = GrandExchangeState.fromOffers(offers, null, 100, true);
        assertEquals(8, memberState.getAvailableSlots().size());

        GrandExchangeState f2pState = GrandExchangeState.fromOffers(offers, null, 100, false);
        assertEquals(2, f2pState.getAvailableSlots().size());
    }

    @Test
    public void testIsSlotLocked() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        for (int i = 0; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState memberState = GrandExchangeState.fromOffers(offers, null, 100, true);
        for (int i = 0; i < 8; i++) {
            assertFalse(memberState.isSlotLocked(i));
        }

        GrandExchangeState f2pState = GrandExchangeState.fromOffers(offers, null, 100, false);
        assertFalse(f2pState.isSlotLocked(0));
        assertFalse(f2pState.isSlotLocked(1));
        assertTrue(f2pState.isSlotLocked(2));
        assertTrue(f2pState.isSlotLocked(7));
    }

    @Test
    public void testGetEmptySlots() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        offers[1] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        for (int i = 2; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState memberState = GrandExchangeState.fromOffers(offers, null, 100, true);
        assertEquals(7, memberState.getEmptySlots().size());

        GrandExchangeState f2pState = GrandExchangeState.fromOffers(offers, null, 100, false);
        assertEquals(1, f2pState.getEmptySlots().size()); // Only slot 1 available for F2P
    }

    @Test
    public void testGetFirstEmptySlot() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        offers[1] = createMockOffer(GrandExchangeOfferState.SELLING, SHARK, 1000, 50, 25, 25000);
        for (int i = 2; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState memberState = GrandExchangeState.fromOffers(offers, null, 100, true);
        Optional<GrandExchangeSlot> emptySlot = memberState.getFirstEmptySlot();
        assertTrue(emptySlot.isPresent());
        assertEquals(2, emptySlot.get().getSlotIndex());

        GrandExchangeState f2pState = GrandExchangeState.fromOffers(offers, null, 100, false);
        emptySlot = f2pState.getFirstEmptySlot();
        assertFalse(emptySlot.isPresent()); // Both F2P slots are in use
    }

    @Test
    public void testHasEmptySlot() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        for (int i = 1; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, true);
        assertTrue(state.hasEmptySlot());

        // Fill all slots
        for (int i = 0; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        }
        state = GrandExchangeState.fromOffers(offers, null, 100, true);
        assertFalse(state.hasEmptySlot());
    }

    @Test
    public void testGetEmptySlotCount() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        offers[1] = createMockOffer(GrandExchangeOfferState.SELLING, SHARK, 1000, 50, 25, 25000);
        for (int i = 2; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, true);
        assertEquals(6, state.getEmptySlotCount());
    }

    // ========================================================================
    // Offer Type Query Tests
    // ========================================================================

    @Test
    public void testGetActiveOffers() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        offers[1] = createMockOffer(GrandExchangeOfferState.BOUGHT, SHARK, 1000, 50, 50, 50000);
        offers[2] = createMockOffer(GrandExchangeOfferState.SELLING, COINS, 1, 1000, 500, 500);
        offers[3] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        for (int i = 4; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, true);
        
        List<GrandExchangeSlot> activeOffers = state.getActiveOffers();
        assertEquals(3, activeOffers.size()); // Non-empty offers
    }

    @Test
    public void testGetBuyOffers() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        offers[1] = createMockOffer(GrandExchangeOfferState.BOUGHT, SHARK, 1000, 50, 50, 50000);
        offers[2] = createMockOffer(GrandExchangeOfferState.SELLING, COINS, 1, 1000, 500, 500);
        offers[3] = createMockOffer(GrandExchangeOfferState.CANCELLED_BUY, RUNE_ORE, 500, 20, 10, 5000);
        for (int i = 4; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, true);
        
        List<GrandExchangeSlot> buyOffers = state.getBuyOffers();
        assertEquals(3, buyOffers.size()); // BUYING, BOUGHT, CANCELLED_BUY
    }

    @Test
    public void testGetSellOffers() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.SELLING, LOBSTER, 200, 100, 50, 10000);
        offers[1] = createMockOffer(GrandExchangeOfferState.SOLD, SHARK, 1000, 50, 50, 50000);
        offers[2] = createMockOffer(GrandExchangeOfferState.BUYING, COINS, 1, 1000, 500, 500);
        offers[3] = createMockOffer(GrandExchangeOfferState.CANCELLED_SELL, RUNE_ORE, 500, 20, 10, 5000);
        for (int i = 4; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, true);
        
        List<GrandExchangeSlot> sellOffers = state.getSellOffers();
        assertEquals(3, sellOffers.size()); // SELLING, SOLD, CANCELLED_SELL
    }

    @Test
    public void testGetInProgressOffers() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        offers[1] = createMockOffer(GrandExchangeOfferState.SELLING, SHARK, 1000, 50, 25, 25000);
        offers[2] = createMockOffer(GrandExchangeOfferState.BOUGHT, COINS, 1, 1000, 1000, 1000);
        offers[3] = createMockOffer(GrandExchangeOfferState.SOLD, RUNE_ORE, 500, 20, 20, 10000);
        for (int i = 4; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, true);
        
        List<GrandExchangeSlot> inProgress = state.getInProgressOffers();
        assertEquals(2, inProgress.size()); // BUYING, SELLING only
    }

    @Test
    public void testGetCompletedOffers() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        offers[1] = createMockOffer(GrandExchangeOfferState.BOUGHT, SHARK, 1000, 50, 50, 50000);
        offers[2] = createMockOffer(GrandExchangeOfferState.SOLD, COINS, 1, 1000, 1000, 1000);
        for (int i = 3; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, true);
        
        List<GrandExchangeSlot> completed = state.getCompletedOffers();
        assertEquals(2, completed.size()); // BOUGHT, SOLD
    }

    @Test
    public void testGetCancelledOffers() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.CANCELLED_BUY, LOBSTER, 200, 100, 50, 10000);
        offers[1] = createMockOffer(GrandExchangeOfferState.CANCELLED_SELL, SHARK, 1000, 50, 25, 25000);
        offers[2] = createMockOffer(GrandExchangeOfferState.BUYING, COINS, 1, 1000, 500, 500);
        for (int i = 3; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, true);
        
        List<GrandExchangeSlot> cancelled = state.getCancelledOffers();
        assertEquals(2, cancelled.size());
    }

    @Test
    public void testGetCollectibleOffers() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.BOUGHT, LOBSTER, 200, 100, 100, 20000);
        offers[1] = createMockOffer(GrandExchangeOfferState.CANCELLED_BUY, SHARK, 1000, 50, 25, 25000);
        offers[2] = createMockOffer(GrandExchangeOfferState.BUYING, COINS, 1, 1000, 500, 500); // Partial progress
        for (int i = 3; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, true);
        
        List<GrandExchangeSlot> collectible = state.getCollectibleOffers();
        assertEquals(3, collectible.size());
        assertTrue(state.hasItemsToCollect());
    }

    // ========================================================================
    // Item-Specific Query Tests
    // ========================================================================

    @Test
    public void testGetOffersForItem() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        offers[1] = createMockOffer(GrandExchangeOfferState.SELLING, LOBSTER, 250, 50, 25, 6250);
        offers[2] = createMockOffer(GrandExchangeOfferState.BUYING, SHARK, 1000, 20, 10, 10000);
        for (int i = 3; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, true);
        
        List<GrandExchangeSlot> lobsterOffers = state.getOffersForItem(LOBSTER);
        assertEquals(2, lobsterOffers.size());

        List<GrandExchangeSlot> sharkOffers = state.getOffersForItem(SHARK);
        assertEquals(1, sharkOffers.size());

        List<GrandExchangeSlot> runeOreOffers = state.getOffersForItem(RUNE_ORE);
        assertTrue(runeOreOffers.isEmpty());
    }

    @Test
    public void testGetActiveBuyOffersForItem() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        offers[1] = createMockOffer(GrandExchangeOfferState.BOUGHT, LOBSTER, 250, 50, 50, 12500);
        for (int i = 2; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, true);
        
        List<GrandExchangeSlot> activeBuys = state.getActiveBuyOffersForItem(LOBSTER);
        assertEquals(1, activeBuys.size()); // Only BUYING, not BOUGHT
    }

    @Test
    public void testGetActiveSellOffersForItem() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.SELLING, LOBSTER, 200, 100, 50, 10000);
        offers[1] = createMockOffer(GrandExchangeOfferState.SOLD, LOBSTER, 250, 50, 50, 12500);
        for (int i = 2; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, true);
        
        List<GrandExchangeSlot> activeSells = state.getActiveSellOffersForItem(LOBSTER);
        assertEquals(1, activeSells.size()); // Only SELLING, not SOLD
    }

    // ========================================================================
    // Buy Limit Tests
    // ========================================================================

    @Test
    public void testGetBuyLimitInfo_Present() {
        Map<Integer, GrandExchangeState.BuyLimitInfo> buyLimits = new HashMap<>();
        buyLimits.put(LOBSTER, GrandExchangeState.BuyLimitInfo.create(LOBSTER, 500));

        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        for (int i = 0; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, buyLimits, 100, true);
        
        Optional<GrandExchangeState.BuyLimitInfo> info = state.getBuyLimitInfo(LOBSTER);
        assertTrue(info.isPresent());
        assertEquals(LOBSTER, info.get().getItemId());
        assertEquals(500, info.get().getQuantityBought());
    }

    @Test
    public void testGetBuyLimitInfo_NotPresent() {
        GrandExchangeState state = GrandExchangeState.EMPTY;
        
        Optional<GrandExchangeState.BuyLimitInfo> info = state.getBuyLimitInfo(LOBSTER);
        assertFalse(info.isPresent());
    }

    @Test
    public void testGetRemainingBuyLimit_NoTracking() {
        GrandExchangeState state = GrandExchangeState.EMPTY;
        
        // No tracking = full limit available
        assertEquals(10000, state.getRemainingBuyLimit(LOBSTER, 10000));
    }

    @Test
    public void testGetRemainingBuyLimit_WithTracking() {
        Map<Integer, GrandExchangeState.BuyLimitInfo> buyLimits = new HashMap<>();
        buyLimits.put(LOBSTER, GrandExchangeState.BuyLimitInfo.create(LOBSTER, 3000));

        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        for (int i = 0; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, buyLimits, 100, true);
        
        // 10000 limit, 3000 bought = 7000 remaining
        assertEquals(7000, state.getRemainingBuyLimit(LOBSTER, 10000));
    }

    @Test
    public void testGetTimeUntilLimitReset_NoTracking() {
        GrandExchangeState state = GrandExchangeState.EMPTY;
        
        assertEquals(Duration.ZERO, state.getTimeUntilLimitReset(LOBSTER));
    }

    @Test
    public void testIsBuyLimitReached() {
        Map<Integer, GrandExchangeState.BuyLimitInfo> buyLimits = new HashMap<>();
        buyLimits.put(LOBSTER, GrandExchangeState.BuyLimitInfo.create(LOBSTER, 10000));
        buyLimits.put(SHARK, GrandExchangeState.BuyLimitInfo.create(SHARK, 5000));

        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        for (int i = 0; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, buyLimits, 100, true);
        
        assertTrue(state.isBuyLimitReached(LOBSTER, 10000)); // 10000/10000 bought
        assertFalse(state.isBuyLimitReached(SHARK, 10000)); // 5000/10000 bought
        assertFalse(state.isBuyLimitReached(RUNE_ORE, 10000)); // No tracking
    }

    // ========================================================================
    // Utility Tests
    // ========================================================================

    @Test
    public void testIsStale() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        for (int i = 0; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 100, true);
        
        assertFalse(state.isStale(105, 10)); // 5 ticks old, threshold 10
        assertTrue(state.isStale(115, 10)); // 15 ticks old, threshold 10
        assertTrue(GrandExchangeState.UNKNOWN.isStale(100, 10)); // Unknown is always stale
    }

    @Test
    public void testGetSummary() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);
        offers[1] = createMockOffer(GrandExchangeOfferState.BOUGHT, SHARK, 1000, 50, 50, 50000);
        for (int i = 2; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, null, 12345, true);
        String summary = state.getSummary();
        
        assertTrue(summary.contains("MEMBER"));
        assertTrue(summary.contains("tick=12345"));
    }

    @Test
    public void testGetSummary_Unknown() {
        String summary = GrandExchangeState.UNKNOWN.getSummary();
        assertTrue(summary.contains("UNKNOWN"));
    }

    @Test
    public void testToString() {
        GrandExchangeState state = GrandExchangeState.EMPTY;
        assertEquals(state.getSummary(), state.toString());
    }

    // ========================================================================
    // BuyLimitInfo Tests
    // ========================================================================

    @Test
    public void testBuyLimitInfo_Create() {
        GrandExchangeState.BuyLimitInfo info = GrandExchangeState.BuyLimitInfo.create(LOBSTER, 500);
        
        assertEquals(LOBSTER, info.getItemId());
        assertEquals(500, info.getQuantityBought());
        assertNotNull(info.getWindowStartTime());
        assertFalse(info.hasReset()); // Just created
    }

    @Test
    public void testBuyLimitInfo_GetRemainingLimit() {
        GrandExchangeState.BuyLimitInfo info = GrandExchangeState.BuyLimitInfo.create(LOBSTER, 3000);
        
        assertEquals(7000, info.getRemainingLimit(10000)); // 10000 - 3000
        assertEquals(0, info.getRemainingLimit(2000)); // 2000 - 3000 = negative, clamped to 0
    }

    @Test
    public void testBuyLimitInfo_GetResetTime() {
        Instant before = Instant.now();
        GrandExchangeState.BuyLimitInfo info = GrandExchangeState.BuyLimitInfo.create(LOBSTER, 500);
        Instant after = Instant.now();
        
        Instant resetTime = info.getResetTime();
        
        // Reset time should be ~4 hours from now
        Duration expectedMin = Duration.ofHours(4).minusSeconds(1);
        Duration expectedMax = Duration.ofHours(4).plusSeconds(1);
        
        Duration actualFromBefore = Duration.between(before, resetTime);
        Duration actualFromAfter = Duration.between(after, resetTime);
        
        assertTrue(actualFromBefore.compareTo(expectedMin) >= 0);
        assertTrue(actualFromAfter.compareTo(expectedMax) <= 0);
    }

    @Test
    public void testBuyLimitInfo_WithAdditionalPurchase() {
        GrandExchangeState.BuyLimitInfo info = GrandExchangeState.BuyLimitInfo.create(LOBSTER, 500);
        Instant originalStart = info.getWindowStartTime();
        
        GrandExchangeState.BuyLimitInfo updated = info.withAdditionalPurchase(300);
        
        assertEquals(800, updated.getQuantityBought()); // 500 + 300
        assertEquals(originalStart, updated.getWindowStartTime()); // Same window
    }

    @Test
    public void testBuyLimitInfo_GetTimeUntilReset() {
        GrandExchangeState.BuyLimitInfo info = GrandExchangeState.BuyLimitInfo.create(LOBSTER, 500);
        
        Duration timeUntil = info.getTimeUntilReset();
        
        // Should be close to 4 hours
        assertTrue(timeUntil.toHours() >= 3);
        assertTrue(timeUntil.toHours() <= 4);
    }

    // ========================================================================
    // Constructor Defensive Copy Tests
    // ========================================================================

    @Test
    public void testConstructor_DefensiveCopy() {
        GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        for (int i = 0; i < 8; i++) {
            offers[i] = createMockOffer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        }
        offers[0] = createMockOffer(GrandExchangeOfferState.BUYING, LOBSTER, 200, 100, 50, 10000);

        Map<Integer, GrandExchangeState.BuyLimitInfo> buyLimits = new HashMap<>();
        buyLimits.put(LOBSTER, GrandExchangeState.BuyLimitInfo.create(LOBSTER, 500));

        GrandExchangeState state = GrandExchangeState.fromOffers(offers, buyLimits, 100, true);
        
        // Modify original data
        offers[0] = createMockOffer(GrandExchangeOfferState.SOLD, SHARK, 1000, 50, 50, 50000);
        buyLimits.clear();
        
        // State should be unchanged
        assertEquals(GrandExchangeOfferState.BUYING, state.getSlot(0).getState());
        assertTrue(state.getBuyLimitInfo(LOBSTER).isPresent());
    }
}

