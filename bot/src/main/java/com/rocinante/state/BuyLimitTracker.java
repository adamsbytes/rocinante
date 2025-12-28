package com.rocinante.state;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Utility class for Grand Exchange buy limit management.
 *
 * <p>This service provides:
 * <ul>
 *   <li>Static buy limit lookup from ItemManager</li>
 *   <li>Remaining limit calculation based on GE state</li>
 *   <li>Time-until-reset queries</li>
 *   <li>Limit status checks</li>
 * </ul>
 *
 * <p>Buy limits are 4-hour rolling windows that reset from the time of first purchase.
 * This class works in conjunction with {@link GrandExchangeStateManager} which tracks
 * actual purchase history.
 *
 * <p>Note: This is a read-only utility. Purchase tracking happens in
 * {@link GrandExchangeStateManager} via GE offer events.
 */
@Slf4j
@Singleton
public class BuyLimitTracker {

    /**
     * Default buy limit for items with unknown limits.
     * Most items have a limit of at least 100.
     */
    public static final int DEFAULT_BUY_LIMIT = 100;

    /**
     * Buy limit reset duration (4 hours).
     */
    public static final Duration RESET_DURATION = Duration.ofHours(4);

    private final ItemManager itemManager;
    private final GrandExchangeStateManager geStateManager;

    @Inject
    public BuyLimitTracker(ItemManager itemManager, GrandExchangeStateManager geStateManager) {
        this.itemManager = itemManager;
        this.geStateManager = geStateManager;
        log.debug("BuyLimitTracker initialized");
    }

    /**
     * Constructor for manual instantiation (testing).
     */
    public BuyLimitTracker(ItemManager itemManager) {
        this.itemManager = itemManager;
        this.geStateManager = null;
    }

    // ========================================================================
    // Static Limit Queries (from ItemManager)
    // ========================================================================

    /**
     * Get the maximum buy limit for an item.
     *
     * @param itemId the item ID
     * @return the buy limit, or {@link #DEFAULT_BUY_LIMIT} if unknown
     */
    public int getBuyLimit(int itemId) {
        int limit = getItemBuyLimitFromManager(itemId);
        return limit > 0 ? limit : DEFAULT_BUY_LIMIT;
    }

    /**
     * Get the maximum buy limit for an item, or empty if unknown.
     *
     * @param itemId the item ID
     * @return optional containing the limit, or empty if not found
     */
    public Optional<Integer> getBuyLimitOptional(int itemId) {
        int limit = getItemBuyLimitFromManager(itemId);
        return limit > 0 ? Optional.of(limit) : Optional.empty();
    }

    /**
     * Check if an item has a known buy limit.
     *
     * @param itemId the item ID
     * @return true if the limit is known
     */
    public boolean hasKnownLimit(int itemId) {
        return getItemBuyLimitFromManager(itemId) > 0;
    }

    /**
     * Get buy limit from ItemManager.
     */
    private int getItemBuyLimitFromManager(int itemId) {
        if (itemManager == null) {
            return -1;
        }

        try {
            // Use the non-deprecated getItemStats(int) method
            ItemStats stats = itemManager.getItemStats(itemId);
            if (stats != null) {
                return stats.getGeLimit();
            }
        } catch (Exception e) {
            log.trace("Failed to get item stats for {}: {}", itemId, e.getMessage());
        }

        return -1;
    }

    // ========================================================================
    // Dynamic Limit Queries (from GE State)
    // ========================================================================

    /**
     * Get the remaining buy limit for an item.
     * Takes into account recent purchases tracked in GE state.
     *
     * @param itemId the item ID
     * @return remaining quantity that can be purchased
     */
    public int getRemainingLimit(int itemId) {
        int maxLimit = getBuyLimit(itemId);
        
        if (geStateManager == null) {
            return maxLimit;
        }
        
        return geStateManager.getGeState().getRemainingBuyLimit(itemId, maxLimit);
    }

    /**
     * Get the quantity already bought in the current 4-hour window.
     *
     * @param itemId the item ID
     * @return quantity bought, or 0 if not tracked
     */
    public int getQuantityBoughtInWindow(int itemId) {
        if (geStateManager == null) {
            return 0;
        }
        
        return geStateManager.getGeState()
                .getBuyLimitInfo(itemId)
                .map(GrandExchangeState.BuyLimitInfo::getQuantityBought)
                .orElse(0);
    }

    /**
     * Get time until the buy limit resets for an item.
     *
     * @param itemId the item ID
     * @return duration until reset, or Duration.ZERO if not tracked/already reset
     */
    public Duration getTimeUntilReset(int itemId) {
        if (geStateManager == null) {
            return Duration.ZERO;
        }
        
        return geStateManager.getGeState().getTimeUntilLimitReset(itemId);
    }

    /**
     * Get the instant when the buy limit will reset.
     *
     * @param itemId the item ID
     * @return reset time, or null if not tracked
     */
    @Nullable
    public Instant getResetTime(int itemId) {
        if (geStateManager == null) {
            return null;
        }
        
        return geStateManager.getGeState()
                .getBuyLimitInfo(itemId)
                .map(GrandExchangeState.BuyLimitInfo::getResetTime)
                .orElse(null);
    }

    // ========================================================================
    // Limit Status Checks
    // ========================================================================

    /**
     * Check if the buy limit has been reached for an item.
     *
     * @param itemId the item ID
     * @return true if the limit has been reached
     */
    public boolean isLimitReached(int itemId) {
        return getRemainingLimit(itemId) <= 0;
    }

    /**
     * Check if a specific quantity can be purchased.
     *
     * @param itemId   the item ID
     * @param quantity the quantity to check
     * @return true if the quantity can be bought within the limit
     */
    public boolean canBuyQuantity(int itemId, int quantity) {
        return getRemainingLimit(itemId) >= quantity;
    }

    /**
     * Get the maximum quantity that can be bought right now.
     * This is the minimum of the remaining limit and the requested quantity.
     *
     * @param itemId          the item ID
     * @param desiredQuantity the quantity the user wants to buy
     * @return the maximum purchasable quantity
     */
    public int getMaxPurchasableQuantity(int itemId, int desiredQuantity) {
        int remaining = getRemainingLimit(itemId);
        return Math.min(remaining, desiredQuantity);
    }

    /**
     * Calculate how much of a quantity would exceed the limit.
     *
     * @param itemId   the item ID
     * @param quantity the quantity to check
     * @return the amount that would exceed the limit, or 0 if within limit
     */
    public int getExcessQuantity(int itemId, int quantity) {
        int remaining = getRemainingLimit(itemId);
        return Math.max(0, quantity - remaining);
    }

    // ========================================================================
    // Limit Information Summary
    // ========================================================================

    /**
     * Get a summary of the buy limit status for an item.
     *
     * @param itemId the item ID
     * @return summary object with all limit information
     */
    public LimitStatus getLimitStatus(int itemId) {
        int maxLimit = getBuyLimit(itemId);
        int remaining = getRemainingLimit(itemId);
        int bought = getQuantityBoughtInWindow(itemId);
        Duration timeUntilReset = getTimeUntilReset(itemId);
        
        return new LimitStatus(itemId, maxLimit, remaining, bought, timeUntilReset);
    }

    /**
     * Summary of buy limit status for an item.
     */
    public record LimitStatus(
            int itemId,
            int maxLimit,
            int remaining,
            int boughtInWindow,
            Duration timeUntilReset
    ) {
        /**
         * Check if the limit has been reached.
         */
        public boolean isReached() {
            return remaining <= 0;
        }

        /**
         * Get the percentage of limit used (0-100).
         */
        public int percentUsed() {
            if (maxLimit <= 0) return 0;
            return (int) ((boughtInWindow * 100.0) / maxLimit);
        }

        /**
         * Check if there's an active tracking window.
         */
        public boolean hasActiveWindow() {
            return boughtInWindow > 0 && !timeUntilReset.isZero();
        }

        /**
         * Get formatted time until reset (e.g., "3h 45m").
         */
        public String getFormattedTimeUntilReset() {
            if (timeUntilReset.isZero()) {
                return "Ready";
            }
            long hours = timeUntilReset.toHours();
            long minutes = timeUntilReset.toMinutesPart();
            if (hours > 0) {
                return String.format("%dh %dm", hours, minutes);
            }
            return String.format("%dm", minutes);
        }

        @Override
        public String toString() {
            return String.format("LimitStatus[item=%d, %d/%d used, remaining=%d, reset=%s]",
                    itemId, boughtInWindow, maxLimit, remaining, getFormattedTimeUntilReset());
        }
    }
}

