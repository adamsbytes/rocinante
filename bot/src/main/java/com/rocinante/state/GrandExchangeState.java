package com.rocinante.state;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of the Grand Exchange state.
 *
 * <p>Represents the complete state of the player's GE offers at a specific point in time,
 * including all 8 slots and buy limit tracking information.
 *
 * <p>Key features:
 * <ul>
 *   <li>Immutable and thread-safe</li>
 *   <li>Supports 8 GE slots (F2P: 2 usable, Members: 8 usable)</li>
 *   <li>Buy limit tracking with 4-hour reset windows</li>
 *   <li>Staleness tracking via lastUpdatedTick</li>
 *   <li>Query methods for offers by type/state</li>
 * </ul>
 *
 * <p>Slot availability:
 * <ul>
 *   <li>F2P accounts: slots 0-1 available (2 total)</li>
 *   <li>Members: slots 0-7 available (8 total)</li>
 * </ul>
 */
@Getter
@EqualsAndHashCode
public final class GrandExchangeState {

    /**
     * Total number of GE slots in the interface.
     */
    public static final int TOTAL_SLOTS = 8;

    /**
     * Number of slots available to F2P accounts.
     */
    public static final int F2P_SLOTS = 2;

    /**
     * Number of slots available to members.
     */
    public static final int MEMBERS_SLOTS = 8;

    /**
     * Buy limit reset window duration (4 hours).
     */
    public static final Duration BUY_LIMIT_RESET_DURATION = Duration.ofHours(4);

    /**
     * Empty GE state with no offers.
     */
    public static final GrandExchangeState EMPTY = new GrandExchangeState(
            createEmptySlots(), Collections.emptyMap(), -1, false, true);

    /**
     * Unknown GE state - indicates GE has never been opened/captured.
     */
    public static final GrandExchangeState UNKNOWN = new GrandExchangeState(
            createEmptySlots(), Collections.emptyMap(), -1, true, true);

    /**
     * The 8 GE offer slots.
     */
    private final List<GrandExchangeSlot> slots;

    /**
     * Buy limit tracking information per item ID.
     */
    private final Map<Integer, BuyLimitInfo> buyLimits;

    /**
     * The game tick when this state was last captured.
     * -1 if the GE has never been observed.
     */
    private final int lastUpdatedTick;

    /**
     * Whether this represents an unknown/unobserved GE state.
     */
    private final boolean unknown;

    /**
     * Whether the player has membership (affects slot availability).
     */
    private final boolean isMember;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a GrandExchangeState from components.
     *
     * @param slots           the 8 GE slots (defensively copied)
     * @param buyLimits       buy limit tracking data (defensively copied)
     * @param lastUpdatedTick the tick when captured
     * @param unknown         whether this is an unknown state
     * @param isMember        whether the player is a member
     */
    public GrandExchangeState(
            List<GrandExchangeSlot> slots,
            Map<Integer, BuyLimitInfo> buyLimits,
            int lastUpdatedTick,
            boolean unknown,
            boolean isMember) {
        // Ensure we always have 8 slots
        List<GrandExchangeSlot> normalizedSlots = new ArrayList<>(TOTAL_SLOTS);
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            if (slots != null && i < slots.size() && slots.get(i) != null) {
                normalizedSlots.add(slots.get(i));
            } else {
                normalizedSlots.add(GrandExchangeSlot.empty(i));
            }
        }
        this.slots = Collections.unmodifiableList(normalizedSlots);
        this.buyLimits = buyLimits == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(buyLimits));
        this.lastUpdatedTick = lastUpdatedTick;
        this.unknown = unknown;
        this.isMember = isMember;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a GrandExchangeState from RuneLite GrandExchangeOffer array.
     *
     * @param offers          the RuneLite offer array (8 elements)
     * @param buyLimits       current buy limit tracking data
     * @param lastUpdatedTick the tick when captured
     * @param isMember        whether the player is a member
     * @return new GrandExchangeState snapshot
     */
    public static GrandExchangeState fromOffers(
            GrandExchangeOffer[] offers,
            Map<Integer, BuyLimitInfo> buyLimits,
            int lastUpdatedTick,
            boolean isMember) {
        if (offers == null) {
            return EMPTY;
        }

        List<GrandExchangeSlot> slots = new ArrayList<>(TOTAL_SLOTS);
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            GrandExchangeOffer offer = i < offers.length ? offers[i] : null;
            slots.add(GrandExchangeSlot.fromOffer(offer, i));
        }

        return new GrandExchangeState(slots, buyLimits, lastUpdatedTick, false, isMember);
    }

    /**
     * Create an empty slots list.
     */
    private static List<GrandExchangeSlot> createEmptySlots() {
        List<GrandExchangeSlot> slots = new ArrayList<>(TOTAL_SLOTS);
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            slots.add(GrandExchangeSlot.empty(i));
        }
        return slots;
    }

    // ========================================================================
    // Slot Queries
    // ========================================================================

    /**
     * Get a specific slot by index.
     *
     * @param index slot index (0-7)
     * @return the slot, or empty slot if index is invalid
     */
    public GrandExchangeSlot getSlot(int index) {
        if (index < 0 || index >= TOTAL_SLOTS) {
            return GrandExchangeSlot.empty(index);
        }
        return slots.get(index);
    }

    /**
     * Get the number of available slots based on membership.
     *
     * @return 2 for F2P, 8 for members
     */
    public int getAvailableSlotCount() {
        return isMember ? MEMBERS_SLOTS : F2P_SLOTS;
    }

    /**
     * Get slots that are available for use (not locked by membership).
     *
     * @return list of available slots
     */
    public List<GrandExchangeSlot> getAvailableSlots() {
        return slots.subList(0, getAvailableSlotCount());
    }

    /**
     * Check if a slot is locked (F2P restriction).
     *
     * @param slotIndex the slot index to check
     * @return true if the slot is locked for this account
     */
    public boolean isSlotLocked(int slotIndex) {
        return slotIndex >= getAvailableSlotCount();
    }

    /**
     * Get all empty slots that are available for use.
     *
     * @return list of empty, usable slots
     */
    public List<GrandExchangeSlot> getEmptySlots() {
        return getAvailableSlots().stream()
                .filter(GrandExchangeSlot::isEmpty)
                .collect(Collectors.toList());
    }

    /**
     * Get the first empty slot available, if any.
     *
     * @return first empty slot, or empty optional if all slots are in use
     */
    public Optional<GrandExchangeSlot> getFirstEmptySlot() {
        return getEmptySlots().stream().findFirst();
    }

    /**
     * Check if there are any empty slots available.
     *
     * @return true if at least one empty slot exists
     */
    public boolean hasEmptySlot() {
        return getEmptySlots().size() > 0;
    }

    /**
     * Get the number of empty slots available.
     *
     * @return count of empty slots
     */
    public int getEmptySlotCount() {
        return getEmptySlots().size();
    }

    // ========================================================================
    // Offer Type Queries
    // ========================================================================

    /**
     * Get all non-empty offers (active, completed, or cancelled).
     *
     * @return list of all offers with content
     */
    public List<GrandExchangeSlot> getActiveOffers() {
        return getAvailableSlots().stream()
                .filter(slot -> !slot.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Get all buy offers (any state).
     *
     * @return list of buy offers
     */
    public List<GrandExchangeSlot> getBuyOffers() {
        return getAvailableSlots().stream()
                .filter(GrandExchangeSlot::isBuyOffer)
                .collect(Collectors.toList());
    }

    /**
     * Get all sell offers (any state).
     *
     * @return list of sell offers
     */
    public List<GrandExchangeSlot> getSellOffers() {
        return getAvailableSlots().stream()
                .filter(GrandExchangeSlot::isSellOffer)
                .collect(Collectors.toList());
    }

    /**
     * Get offers that are actively in progress (BUYING or SELLING).
     *
     * @return list of in-progress offers
     */
    public List<GrandExchangeSlot> getInProgressOffers() {
        return getAvailableSlots().stream()
                .filter(GrandExchangeSlot::isActive)
                .collect(Collectors.toList());
    }

    /**
     * Get completed offers (BOUGHT or SOLD).
     *
     * @return list of completed offers
     */
    public List<GrandExchangeSlot> getCompletedOffers() {
        return getAvailableSlots().stream()
                .filter(GrandExchangeSlot::isCompleted)
                .collect(Collectors.toList());
    }

    /**
     * Get cancelled offers.
     *
     * @return list of cancelled offers
     */
    public List<GrandExchangeSlot> getCancelledOffers() {
        return getAvailableSlots().stream()
                .filter(GrandExchangeSlot::isCancelled)
                .collect(Collectors.toList());
    }

    /**
     * Get offers that have items or GP to collect.
     *
     * @return list of offers with collectible items
     */
    public List<GrandExchangeSlot> getCollectibleOffers() {
        return getAvailableSlots().stream()
                .filter(GrandExchangeSlot::hasItemsToCollect)
                .collect(Collectors.toList());
    }

    /**
     * Check if there are any items to collect from any slot.
     *
     * @return true if any slot has collectible items
     */
    public boolean hasItemsToCollect() {
        return getCollectibleOffers().size() > 0;
    }

    // ========================================================================
    // Item-Specific Queries
    // ========================================================================

    /**
     * Find offers for a specific item.
     *
     * @param itemId the item ID to search for
     * @return list of offers for this item
     */
    public List<GrandExchangeSlot> getOffersForItem(int itemId) {
        return getAvailableSlots().stream()
                .filter(slot -> slot.getItemId() == itemId)
                .collect(Collectors.toList());
    }

    /**
     * Find active buy offers for a specific item.
     *
     * @param itemId the item ID to search for
     * @return list of active buy offers for this item
     */
    public List<GrandExchangeSlot> getActiveBuyOffersForItem(int itemId) {
        return getAvailableSlots().stream()
                .filter(slot -> slot.getItemId() == itemId
                        && slot.getState() == GrandExchangeOfferState.BUYING)
                .collect(Collectors.toList());
    }

    /**
     * Find active sell offers for a specific item.
     *
     * @param itemId the item ID to search for
     * @return list of active sell offers for this item
     */
    public List<GrandExchangeSlot> getActiveSellOffersForItem(int itemId) {
        return getAvailableSlots().stream()
                .filter(slot -> slot.getItemId() == itemId
                        && slot.getState() == GrandExchangeOfferState.SELLING)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // Buy Limit Queries
    // ========================================================================

    /**
     * Get buy limit info for an item.
     *
     * @param itemId the item ID
     * @return buy limit info, or empty optional if not tracked
     */
    public Optional<BuyLimitInfo> getBuyLimitInfo(int itemId) {
        return Optional.ofNullable(buyLimits.get(itemId));
    }

    /**
     * Get the remaining buy limit for an item.
     * Returns the full limit if no purchases have been tracked.
     *
     * @param itemId   the item ID
     * @param maxLimit the maximum buy limit for this item (from ItemStats)
     * @return remaining quantity that can be purchased
     */
    public int getRemainingBuyLimit(int itemId, int maxLimit) {
        BuyLimitInfo info = buyLimits.get(itemId);
        if (info == null) {
            return maxLimit;
        }
        return info.getRemainingLimit(maxLimit);
    }

    /**
     * Get the time until the buy limit resets for an item.
     *
     * @param itemId the item ID
     * @return duration until reset, or Duration.ZERO if already reset/not tracked
     */
    public Duration getTimeUntilLimitReset(int itemId) {
        BuyLimitInfo info = buyLimits.get(itemId);
        if (info == null) {
            return Duration.ZERO;
        }
        return info.getTimeUntilReset();
    }

    /**
     * Check if the buy limit for an item has been reached.
     *
     * @param itemId   the item ID
     * @param maxLimit the maximum buy limit for this item
     * @return true if the limit has been reached
     */
    public boolean isBuyLimitReached(int itemId, int maxLimit) {
        return getRemainingBuyLimit(itemId, maxLimit) <= 0;
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Check if the state is stale (older than specified ticks).
     *
     * @param currentTick  current game tick
     * @param maxAgeTicks  maximum acceptable age in ticks
     * @return true if the state is stale
     */
    public boolean isStale(int currentTick, int maxAgeTicks) {
        if (lastUpdatedTick < 0) {
            return true;
        }
        return (currentTick - lastUpdatedTick) > maxAgeTicks;
    }

    /**
     * Get a summary of the current GE state for logging.
     *
     * @return summary string
     */
    public String getSummary() {
        if (unknown) {
            return "GrandExchangeState[UNKNOWN]";
        }
        long active = getActiveOffers().size();
        long empty = getEmptySlotCount();
        long collectible = getCollectibleOffers().size();
        return String.format("GrandExchangeState[%s, slots: %d/%d used, %d empty, %d collectible, tick=%d]",
                isMember ? "MEMBER" : "F2P",
                active, getAvailableSlotCount(), empty, collectible, lastUpdatedTick);
    }

    @Override
    public String toString() {
        return getSummary();
    }

    // ========================================================================
    // Nested Classes
    // ========================================================================

    /**
     * Buy limit tracking information for a specific item.
     *
     * <p>Tracks how many of an item have been purchased within the current
     * 4-hour buy limit window.
     */
    @Value
    @Builder
    public static class BuyLimitInfo {

        /**
         * The item ID being tracked.
         */
        int itemId;

        /**
         * The quantity purchased in the current limit window.
         */
        int quantityBought;

        /**
         * When the current buy limit window started (first purchase).
         */
        Instant windowStartTime;

        /**
         * Get the remaining buy limit.
         *
         * @param maxLimit the maximum limit for this item
         * @return remaining quantity that can be purchased
         */
        public int getRemainingLimit(int maxLimit) {
            if (hasReset()) {
                return maxLimit;
            }
            return Math.max(0, maxLimit - quantityBought);
        }

        /**
         * Check if the limit window has reset (4 hours have passed).
         *
         * @return true if the window has reset
         */
        public boolean hasReset() {
            if (windowStartTime == null) {
                return true;
            }
            return Instant.now().isAfter(windowStartTime.plus(BUY_LIMIT_RESET_DURATION));
        }

        /**
         * Get the time until the limit resets.
         *
         * @return duration until reset, or Duration.ZERO if already reset
         */
        public Duration getTimeUntilReset() {
            if (windowStartTime == null) {
                return Duration.ZERO;
            }
            Instant resetTime = windowStartTime.plus(BUY_LIMIT_RESET_DURATION);
            Duration remaining = Duration.between(Instant.now(), resetTime);
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }

        /**
         * Get the reset time instant.
         *
         * @return the time when the limit will reset
         */
        public Instant getResetTime() {
            if (windowStartTime == null) {
                return Instant.now();
            }
            return windowStartTime.plus(BUY_LIMIT_RESET_DURATION);
        }

        /**
         * Create updated info with additional quantity purchased.
         *
         * @param additionalQuantity quantity just purchased
         * @return new BuyLimitInfo with updated values
         */
        public BuyLimitInfo withAdditionalPurchase(int additionalQuantity) {
            // If limit has reset, start a new window
            if (hasReset()) {
                return BuyLimitInfo.builder()
                        .itemId(itemId)
                        .quantityBought(additionalQuantity)
                        .windowStartTime(Instant.now())
                        .build();
            }
            // Otherwise, add to current window
            return BuyLimitInfo.builder()
                    .itemId(itemId)
                    .quantityBought(quantityBought + additionalQuantity)
                    .windowStartTime(windowStartTime)
                    .build();
        }

        /**
         * Create a new BuyLimitInfo for an item's first purchase.
         *
         * @param itemId   the item ID
         * @param quantity the quantity purchased
         * @return new BuyLimitInfo
         */
        public static BuyLimitInfo create(int itemId, int quantity) {
            return BuyLimitInfo.builder()
                    .itemId(itemId)
                    .quantityBought(quantity)
                    .windowStartTime(Instant.now())
                    .build();
        }
    }
}

