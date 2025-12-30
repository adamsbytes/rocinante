package com.rocinante.state;

import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BuyLimitTracker} utility class.
 *
 * Tests GE buy limit lookups and limit status queries.
 * Note: BuyLimitTracker is a read-only utility; purchase tracking is in GrandExchangeStateManager.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class BuyLimitTrackerTest {

    @Mock
    private ItemManager itemManager;

    @Mock
    private ItemStats sharkStats;

    @Mock
    private ItemStats bandosStats;

    @Mock
    private ItemStats unknownStats;

    private BuyLimitTracker tracker;

    @Before
    public void setUp() {
        // Use the testing constructor that doesn't require GrandExchangeStateManager
        tracker = new BuyLimitTracker(itemManager);
    }

    // ========================================================================
    // getBuyLimit() Tests
    // ========================================================================

    @Test
    public void testGetBuyLimit_KnownItem_ReturnsCorrectLimit() {
        int sharkId = 385;
        when(itemManager.getItemStats(sharkId)).thenReturn(sharkStats);
        when(sharkStats.getGeLimit()).thenReturn(13000);
        
        assertEquals("Shark should have limit of 13000", 
                13000, tracker.getBuyLimit(sharkId));
    }

    @Test
    public void testGetBuyLimit_RareItem_ReturnsLowLimit() {
        int bandosChestplateId = 11832;
        when(itemManager.getItemStats(bandosChestplateId)).thenReturn(bandosStats);
        when(bandosStats.getGeLimit()).thenReturn(8);
        
        assertEquals("Bandos chestplate should have limit of 8", 
                8, tracker.getBuyLimit(bandosChestplateId));
    }

    @Test
    public void testGetBuyLimit_UnknownItem_ReturnsDefault() {
        int unknownItemId = 999999;
        when(itemManager.getItemStats(unknownItemId)).thenReturn(unknownStats);
        when(unknownStats.getGeLimit()).thenReturn(-1);
        
        assertEquals("Unknown item should return default limit", 
                BuyLimitTracker.DEFAULT_BUY_LIMIT, tracker.getBuyLimit(unknownItemId));
    }

    @Test
    public void testGetBuyLimit_ZeroLimit_ReturnsDefault() {
        int itemId = 12345;
        when(itemManager.getItemStats(itemId)).thenReturn(unknownStats);
        when(unknownStats.getGeLimit()).thenReturn(0);
        
        assertEquals("Zero limit should return default", 
                BuyLimitTracker.DEFAULT_BUY_LIMIT, tracker.getBuyLimit(itemId));
    }

    @Test
    public void testGetBuyLimit_NullItemStats_ReturnsDefault() {
        int itemId = 99999;
        when(itemManager.getItemStats(itemId)).thenReturn(null);
        
        assertEquals("Null item stats should return default", 
                BuyLimitTracker.DEFAULT_BUY_LIMIT, tracker.getBuyLimit(itemId));
    }

    // ========================================================================
    // getBuyLimitOptional() Tests
    // ========================================================================

    @Test
    public void testGetBuyLimitOptional_KnownItem_ReturnsPresent() {
        int sharkId = 385;
        when(itemManager.getItemStats(sharkId)).thenReturn(sharkStats);
        when(sharkStats.getGeLimit()).thenReturn(13000);
        
        assertTrue("Known item should return present optional", 
                tracker.getBuyLimitOptional(sharkId).isPresent());
        assertEquals("Should return correct limit", 
                Integer.valueOf(13000), tracker.getBuyLimitOptional(sharkId).get());
    }

    @Test
    public void testGetBuyLimitOptional_UnknownItem_ReturnsEmpty() {
        int unknownId = 99999;
        when(itemManager.getItemStats(unknownId)).thenReturn(null);
        
        assertFalse("Unknown item should return empty optional", 
                tracker.getBuyLimitOptional(unknownId).isPresent());
    }

    // ========================================================================
    // hasKnownLimit() Tests
    // ========================================================================

    @Test
    public void testHasKnownLimit_KnownItem_True() {
        int sharkId = 385;
        when(itemManager.getItemStats(sharkId)).thenReturn(sharkStats);
        when(sharkStats.getGeLimit()).thenReturn(13000);
        
        assertTrue("Shark should have known limit", tracker.hasKnownLimit(sharkId));
    }

    @Test
    public void testHasKnownLimit_UnknownItem_False() {
        int unknownId = 99999;
        when(itemManager.getItemStats(unknownId)).thenReturn(null);
        
        assertFalse("Unknown item should not have known limit", tracker.hasKnownLimit(unknownId));
    }

    // ========================================================================
    // getRemainingLimit() Tests (without GE state manager)
    // ========================================================================

    @Test
    public void testGetRemainingLimit_NoStateManager_ReturnsFull() {
        int sharkId = 385;
        when(itemManager.getItemStats(sharkId)).thenReturn(sharkStats);
        when(sharkStats.getGeLimit()).thenReturn(13000);
        
        // Without GE state manager, remaining = full limit
        assertEquals("Without state manager, should return full limit", 
                13000, tracker.getRemainingLimit(sharkId));
    }

    // ========================================================================
    // isLimitReached() Tests
    // ========================================================================

    @Test
    public void testIsLimitReached_NoStateManager_False() {
        int sharkId = 385;
        when(itemManager.getItemStats(sharkId)).thenReturn(sharkStats);
        when(sharkStats.getGeLimit()).thenReturn(13000);
        
        // Without GE state manager, limit is never reached
        assertFalse("Without state manager, limit should not be reached", 
                tracker.isLimitReached(sharkId));
    }

    // ========================================================================
    // canBuyQuantity() Tests
    // ========================================================================

    @Test
    public void testCanBuyQuantity_SufficientLimit_True() {
        int sharkId = 385;
        when(itemManager.getItemStats(sharkId)).thenReturn(sharkStats);
        when(sharkStats.getGeLimit()).thenReturn(13000);
        
        assertTrue("Should be able to buy 5000 with full 13000 limit", 
                tracker.canBuyQuantity(sharkId, 5000));
    }

    @Test
    public void testCanBuyQuantity_ZeroQuantity_True() {
        int sharkId = 385;
        when(itemManager.getItemStats(sharkId)).thenReturn(sharkStats);
        when(sharkStats.getGeLimit()).thenReturn(13000);
        
        assertTrue("Should always be able to buy 0", 
                tracker.canBuyQuantity(sharkId, 0));
    }

    // ========================================================================
    // getMaxPurchasableQuantity() Tests
    // ========================================================================

    @Test
    public void testGetMaxPurchasableQuantity_WantLessThanLimit() {
        int sharkId = 385;
        when(itemManager.getItemStats(sharkId)).thenReturn(sharkStats);
        when(sharkStats.getGeLimit()).thenReturn(13000);
        
        assertEquals("Should return requested amount when within limit", 
                5000, tracker.getMaxPurchasableQuantity(sharkId, 5000));
    }

    @Test
    public void testGetMaxPurchasableQuantity_WantMoreThanLimit() {
        int bandosId = 11832;
        when(itemManager.getItemStats(bandosId)).thenReturn(bandosStats);
        when(bandosStats.getGeLimit()).thenReturn(8);
        
        assertEquals("Should cap at limit when requested exceeds limit", 
                8, tracker.getMaxPurchasableQuantity(bandosId, 100));
    }

    // ========================================================================
    // getExcessQuantity() Tests
    // ========================================================================

    @Test
    public void testGetExcessQuantity_WithinLimit_Zero() {
        int sharkId = 385;
        when(itemManager.getItemStats(sharkId)).thenReturn(sharkStats);
        when(sharkStats.getGeLimit()).thenReturn(13000);
        
        assertEquals("Within limit should have 0 excess", 
                0, tracker.getExcessQuantity(sharkId, 5000));
    }

    @Test
    public void testGetExcessQuantity_ExceedsLimit_ReturnsExcess() {
        int bandosId = 11832;
        when(itemManager.getItemStats(bandosId)).thenReturn(bandosStats);
        when(bandosStats.getGeLimit()).thenReturn(8);
        
        assertEquals("Exceeding limit should show excess", 
                92, tracker.getExcessQuantity(bandosId, 100));
    }

    // ========================================================================
    // getLimitStatus() Tests
    // ========================================================================

    @Test
    public void testGetLimitStatus_ReturnsCorrectData() {
        int sharkId = 385;
        when(itemManager.getItemStats(sharkId)).thenReturn(sharkStats);
        when(sharkStats.getGeLimit()).thenReturn(13000);
        
        BuyLimitTracker.LimitStatus status = tracker.getLimitStatus(sharkId);
        
        assertNotNull("Status should not be null", status);
        assertEquals("Item ID should match", sharkId, status.itemId());
        assertEquals("Max limit should be 13000", 13000, status.maxLimit());
        assertEquals("Remaining should be full without state manager", 13000, status.remaining());
    }

    // ========================================================================
    // LimitStatus Record Tests
    // ========================================================================

    @Test
    public void testLimitStatus_IsReached_True() {
        BuyLimitTracker.LimitStatus status = 
                new BuyLimitTracker.LimitStatus(385, 13000, 0, 13000, Duration.ofHours(3));
        
        assertTrue("Limit should be reached when remaining is 0", status.isReached());
    }

    @Test
    public void testLimitStatus_IsReached_False() {
        BuyLimitTracker.LimitStatus status = 
                new BuyLimitTracker.LimitStatus(385, 13000, 1000, 12000, Duration.ofHours(3));
        
        assertFalse("Limit should not be reached with remaining > 0", status.isReached());
    }

    @Test
    public void testLimitStatus_PercentUsed_HalfUsed() {
        BuyLimitTracker.LimitStatus status = 
                new BuyLimitTracker.LimitStatus(385, 10000, 5000, 5000, Duration.ofHours(2));
        
        assertEquals("50% should be used", 50, status.percentUsed());
    }

    @Test
    public void testLimitStatus_PercentUsed_FullyUsed() {
        BuyLimitTracker.LimitStatus status = 
                new BuyLimitTracker.LimitStatus(385, 13000, 0, 13000, Duration.ofHours(1));
        
        assertEquals("100% should be used", 100, status.percentUsed());
    }

    @Test
    public void testLimitStatus_PercentUsed_NoneUsed() {
        BuyLimitTracker.LimitStatus status = 
                new BuyLimitTracker.LimitStatus(385, 13000, 13000, 0, Duration.ZERO);
        
        assertEquals("0% should be used", 0, status.percentUsed());
    }

    @Test
    public void testLimitStatus_HasActiveWindow_True() {
        BuyLimitTracker.LimitStatus status = 
                new BuyLimitTracker.LimitStatus(385, 13000, 8000, 5000, Duration.ofHours(3));
        
        assertTrue("Should have active window with bought > 0 and time remaining", 
                status.hasActiveWindow());
    }

    @Test
    public void testLimitStatus_HasActiveWindow_False_NoBuys() {
        BuyLimitTracker.LimitStatus status = 
                new BuyLimitTracker.LimitStatus(385, 13000, 13000, 0, Duration.ZERO);
        
        assertFalse("Should not have active window with no buys", 
                status.hasActiveWindow());
    }

    @Test
    public void testLimitStatus_GetFormattedTimeUntilReset_Ready() {
        BuyLimitTracker.LimitStatus status = 
                new BuyLimitTracker.LimitStatus(385, 13000, 13000, 0, Duration.ZERO);
        
        assertEquals("Zero duration should show 'Ready'", "Ready", 
                status.getFormattedTimeUntilReset());
    }

    @Test
    public void testLimitStatus_GetFormattedTimeUntilReset_WithHours() {
        BuyLimitTracker.LimitStatus status = 
                new BuyLimitTracker.LimitStatus(385, 13000, 0, 13000, Duration.ofHours(3).plusMinutes(45));
        
        String formatted = status.getFormattedTimeUntilReset();
        assertTrue("Should contain hours", formatted.contains("3h"));
        assertTrue("Should contain minutes", formatted.contains("45m"));
    }

    @Test
    public void testLimitStatus_GetFormattedTimeUntilReset_MinutesOnly() {
        BuyLimitTracker.LimitStatus status = 
                new BuyLimitTracker.LimitStatus(385, 13000, 1000, 12000, Duration.ofMinutes(45));
        
        String formatted = status.getFormattedTimeUntilReset();
        assertEquals("Should show minutes only", "45m", formatted);
    }

    // ========================================================================
    // Constants Tests
    // ========================================================================

    @Test
    public void testDefaultBuyLimit_Is100() {
        assertEquals("Default buy limit should be 100", 
                100, BuyLimitTracker.DEFAULT_BUY_LIMIT);
    }

    @Test
    public void testResetDuration_Is4Hours() {
        assertEquals("Reset duration should be 4 hours", 
                Duration.ofHours(4), BuyLimitTracker.RESET_DURATION);
    }

    // ========================================================================
    // getTimeUntilReset() Tests (without GE state manager)
    // ========================================================================

    @Test
    public void testGetTimeUntilReset_NoStateManager_ReturnsZero() {
        int sharkId = 385;
        when(itemManager.getItemStats(sharkId)).thenReturn(sharkStats);
        when(sharkStats.getGeLimit()).thenReturn(13000);
        
        assertEquals("Without state manager, time until reset should be zero", 
                Duration.ZERO, tracker.getTimeUntilReset(sharkId));
    }

    // ========================================================================
    // getQuantityBoughtInWindow() Tests (without GE state manager)
    // ========================================================================

    @Test
    public void testGetQuantityBoughtInWindow_NoStateManager_ReturnsZero() {
        int sharkId = 385;
        when(itemManager.getItemStats(sharkId)).thenReturn(sharkStats);
        when(sharkStats.getGeLimit()).thenReturn(13000);
        
        assertEquals("Without state manager, quantity bought should be 0", 
                0, tracker.getQuantityBoughtInWindow(sharkId));
    }

    // ========================================================================
    // getResetTime() Tests (without GE state manager)
    // ========================================================================

    @Test
    public void testGetResetTime_NoStateManager_ReturnsNull() {
        int sharkId = 385;
        when(itemManager.getItemStats(sharkId)).thenReturn(sharkStats);
        when(sharkStats.getGeLimit()).thenReturn(13000);
        
        assertNull("Without state manager, reset time should be null", 
                tracker.getResetTime(sharkId));
    }
}

