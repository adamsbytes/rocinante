package com.rocinante.state;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Immutable snapshot of a single Grand Exchange slot.
 *
 * <p>Represents the state of one of the 8 GE offer slots at a specific point in time.
 * F2P accounts only have access to slots 0-1, while members have access to all 8 slots (0-7).
 *
 * <p>Key features:
 * <ul>
 *   <li>Immutable and thread-safe</li>
 *   <li>Contains all offer details (item, price, quantity, progress)</li>
 *   <li>Provides helper methods for common queries</li>
 * </ul>
 */
@Value
@Builder
public class GrandExchangeSlot {

    /**
     * The slot index (0-7).
     * F2P accounts can only use slots 0-1.
     */
    int slotIndex;

    /**
     * The current state of this slot's offer.
     */
    GrandExchangeOfferState state;

    /**
     * The item ID being bought or sold.
     * 0 or -1 if the slot is empty.
     */
    int itemId;

    /**
     * The price per item set for this offer.
     */
    int price;

    /**
     * The total quantity requested (buy) or offered (sell).
     */
    int totalQuantity;

    /**
     * The quantity that has been bought or sold so far.
     */
    int quantitySold;

    /**
     * The total amount of GP spent (buy) or received (sell) so far.
     */
    int spent;

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a GrandExchangeSlot from a RuneLite GrandExchangeOffer.
     *
     * @param offer     the RuneLite offer object
     * @param slotIndex the slot index (0-7)
     * @return new GrandExchangeSlot snapshot
     */
    public static GrandExchangeSlot fromOffer(GrandExchangeOffer offer, int slotIndex) {
        if (offer == null) {
            return empty(slotIndex);
        }

        return GrandExchangeSlot.builder()
                .slotIndex(slotIndex)
                .state(offer.getState())
                .itemId(offer.getItemId())
                .price(offer.getPrice())
                .totalQuantity(offer.getTotalQuantity())
                .quantitySold(offer.getQuantitySold())
                .spent(offer.getSpent())
                .build();
    }

    /**
     * Create an empty slot.
     *
     * @param slotIndex the slot index
     * @return empty slot
     */
    public static GrandExchangeSlot empty(int slotIndex) {
        return GrandExchangeSlot.builder()
                .slotIndex(slotIndex)
                .state(GrandExchangeOfferState.EMPTY)
                .itemId(-1)
                .price(0)
                .totalQuantity(0)
                .quantitySold(0)
                .spent(0)
                .build();
    }

    // ========================================================================
    // State Queries
    // ========================================================================

    /**
     * Check if this slot is empty (no active offer).
     *
     * @return true if the slot has no offer
     */
    public boolean isEmpty() {
        return state == GrandExchangeOfferState.EMPTY;
    }

    /**
     * Check if this is a buy offer (active or completed).
     *
     * @return true if this is a buy offer
     */
    public boolean isBuyOffer() {
        return state == GrandExchangeOfferState.BUYING
                || state == GrandExchangeOfferState.BOUGHT
                || state == GrandExchangeOfferState.CANCELLED_BUY;
    }

    /**
     * Check if this is a sell offer (active or completed).
     *
     * @return true if this is a sell offer
     */
    public boolean isSellOffer() {
        return state == GrandExchangeOfferState.SELLING
                || state == GrandExchangeOfferState.SOLD
                || state == GrandExchangeOfferState.CANCELLED_SELL;
    }

    /**
     * Check if this offer is currently active (in progress).
     *
     * @return true if the offer is actively buying or selling
     */
    public boolean isActive() {
        return state == GrandExchangeOfferState.BUYING
                || state == GrandExchangeOfferState.SELLING;
    }

    /**
     * Check if this offer has completed (fully bought/sold).
     *
     * @return true if the offer is fully complete
     */
    public boolean isCompleted() {
        return state == GrandExchangeOfferState.BOUGHT
                || state == GrandExchangeOfferState.SOLD;
    }

    /**
     * Check if this offer was cancelled.
     *
     * @return true if the offer was cancelled
     */
    public boolean isCancelled() {
        return state == GrandExchangeOfferState.CANCELLED_BUY
                || state == GrandExchangeOfferState.CANCELLED_SELL;
    }

    /**
     * Check if this slot has items/GP to collect.
     * True if the offer is completed, cancelled with partial progress, or has any progress.
     *
     * @return true if there are items or GP to collect
     */
    public boolean hasItemsToCollect() {
        if (isEmpty()) {
            return false;
        }
        // Completed offers always have items to collect
        if (isCompleted() || isCancelled()) {
            return true;
        }
        // Active offers with partial progress have items to collect
        return quantitySold > 0;
    }

    // ========================================================================
    // Progress Queries
    // ========================================================================

    /**
     * Get the remaining quantity to be bought or sold.
     *
     * @return remaining quantity
     */
    public int getRemainingQuantity() {
        return Math.max(0, totalQuantity - quantitySold);
    }

    /**
     * Get the completion percentage (0.0 to 1.0).
     *
     * @return completion ratio
     */
    public double getCompletionRatio() {
        if (totalQuantity <= 0) {
            return 0.0;
        }
        return (double) quantitySold / totalQuantity;
    }

    /**
     * Get the completion percentage (0 to 100).
     *
     * @return completion percentage
     */
    public int getCompletionPercent() {
        return (int) (getCompletionRatio() * 100);
    }

    /**
     * Get the average price per item transacted so far.
     * Returns the offer price if no items have been transacted yet.
     *
     * @return average price per item
     */
    public int getAveragePrice() {
        if (quantitySold <= 0) {
            return price;
        }
        return spent / quantitySold;
    }

    /**
     * Get the total value of this offer (price * total quantity).
     *
     * @return total offer value
     */
    public long getTotalValue() {
        return (long) price * totalQuantity;
    }

    /**
     * Get the remaining value to be transacted.
     *
     * @return remaining value in GP
     */
    public long getRemainingValue() {
        return (long) price * getRemainingQuantity();
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Get a summary string for logging/debugging.
     *
     * @return summary string
     */
    public String getSummary() {
        if (isEmpty()) {
            return String.format("Slot[%d: EMPTY]", slotIndex);
        }
        String type = isBuyOffer() ? "BUY" : "SELL";
        return String.format("Slot[%d: %s %s item=%d, %d/%d @ %dgp, spent=%d]",
                slotIndex, type, state, itemId, quantitySold, totalQuantity, price, spent);
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

