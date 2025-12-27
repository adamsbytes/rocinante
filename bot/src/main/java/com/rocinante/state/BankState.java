package com.rocinante.state;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import net.runelite.api.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable snapshot of the player's bank state.
 *
 * <p>This class represents a point-in-time snapshot of the player's bank contents,
 * providing helper methods for item queries, slot management, and tab operations.
 * Bank state persists across bank interface closures and is saved to disk for
 * cross-session availability.
 *
 * <p>Key features:
 * <ul>
 *   <li>Immutable and thread-safe</li>
 *   <li>Supports up to 816 bank slots (expandable with membership)</li>
 *   <li>Tab-aware item organization</li>
 *   <li>Placeholder detection and handling</li>
 *   <li>Staleness tracking via lastUpdatedTick</li>
 * </ul>
 *
 * <p>All fields are immutable to ensure thread-safety and prevent accidental modification.
 */
@Getter
@EqualsAndHashCode
public final class BankState {

    /**
     * Maximum number of bank slots (standard account).
     * Players can have 800 base slots + additional from membership perks.
     */
    public static final int MAX_BANK_SLOTS = 816;

    /**
     * Maximum number of bank tabs (main tab + 9 custom tabs).
     */
    public static final int MAX_TABS = 10;

    /**
     * Bank filler item ID - used to identify placeholder slots.
     */
    public static final int BANK_FILLER_ID = 20594;

    /**
     * Empty bank state with no items.
     */
    public static final BankState EMPTY = new BankState(new ArrayList<>(), -1, false);

    /**
     * Unknown bank state - indicates bank has never been opened/captured.
     * Different from EMPTY which indicates an empty bank was observed.
     */
    public static final BankState UNKNOWN = new BankState(new ArrayList<>(), -1, true);

    /**
     * The bank items. Each entry contains item ID, quantity, slot index, and tab index.
     * Empty/placeholder slots may be omitted or have quantity 0.
     */
    private final List<BankItem> items;

    /**
     * The game tick when this bank state was last captured.
     * -1 if the bank has never been observed.
     */
    private final int lastUpdatedTick;

    /**
     * Whether this represents an unknown/unobserved bank state.
     */
    private final boolean unknown;

    // ========================================================================
    // Cached Computed Values (computed once at construction)
    // ========================================================================

    /**
     * Cached item ID to bank items mapping for fast lookups.
     */
    private final Map<Integer, List<BankItem>> itemIdIndex;

    /**
     * Cached tab to items mapping.
     */
    private final Map<Integer, List<BankItem>> tabIndex;

    /**
     * Create a BankState from a list of bank items.
     *
     * @param items           the bank items (defensively copied)
     * @param lastUpdatedTick the tick when captured
     * @param unknown         whether this is an unknown state
     */
    public BankState(List<BankItem> items, int lastUpdatedTick, boolean unknown) {
        this.items = items == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(items));
        this.lastUpdatedTick = lastUpdatedTick;
        this.unknown = unknown;
        
        // Build indexes
        Map<Integer, List<BankItem>> idIndex = new HashMap<>();
        Map<Integer, List<BankItem>> tIndex = new HashMap<>();
        
        for (BankItem item : this.items) {
            idIndex.computeIfAbsent(item.getItemId(), k -> new ArrayList<>()).add(item);
            tIndex.computeIfAbsent(item.getTab(), k -> new ArrayList<>()).add(item);
        }
        
        this.itemIdIndex = Collections.unmodifiableMap(idIndex);
        this.tabIndex = Collections.unmodifiableMap(tIndex);
    }

    /**
     * Create a BankState from RuneLite Item array (when bank is open).
     *
     * @param bankItems       the bank item container contents
     * @param lastUpdatedTick the tick when captured
     * @return new BankState snapshot
     */
    public static BankState fromItemContainer(Item[] bankItems, int lastUpdatedTick) {
        if (bankItems == null) {
            return EMPTY;
        }

        List<BankItem> items = new ArrayList<>();
        int currentTab = 0;
        
        for (int slot = 0; slot < bankItems.length && slot < MAX_BANK_SLOTS; slot++) {
            Item item = bankItems[slot];
            if (item != null && item.getId() > 0 && item.getId() != BANK_FILLER_ID) {
                items.add(BankItem.builder()
                        .itemId(item.getId())
                        .quantity(item.getQuantity())
                        .slot(slot)
                        .tab(currentTab)
                        .build());
            }
        }

        return new BankState(items, lastUpdatedTick, false);
    }

    // ========================================================================
    // Item Query Methods
    // ========================================================================

    /**
     * Check if the bank contains at least one of the specified item.
     *
     * @param itemId the item ID to check for
     * @return true if the item is present
     */
    public boolean hasItem(int itemId) {
        return countItem(itemId) > 0;
    }

    /**
     * Check if the bank contains at least the specified quantity of an item.
     *
     * @param itemId   the item ID to check for
     * @param quantity the minimum quantity required
     * @return true if the item is present in sufficient quantity
     */
    public boolean hasItem(int itemId, int quantity) {
        return countItem(itemId) >= quantity;
    }

    /**
     * Check if the bank contains any of the specified items.
     *
     * @param itemIds the item IDs to check for
     * @return true if any of the items are present
     */
    public boolean hasAnyItem(int... itemIds) {
        for (int id : itemIds) {
            if (hasItem(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the bank contains all of the specified items.
     *
     * @param itemIds the item IDs to check for
     * @return true if all items are present
     */
    public boolean hasAllItems(int... itemIds) {
        for (int id : itemIds) {
            if (!hasItem(id)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Count the total quantity of an item in the bank.
     * For stackable items, returns the stack size.
     * For non-stackable items, returns the count of slots containing the item.
     *
     * @param itemId the item ID to count
     * @return total quantity of the item
     */
    public int countItem(int itemId) {
        List<BankItem> matching = itemIdIndex.get(itemId);
        if (matching == null || matching.isEmpty()) {
            return 0;
        }
        return matching.stream()
                .mapToInt(BankItem::getQuantity)
                .sum();
    }

    /**
     * Get the slot index of the first occurrence of an item.
     *
     * @param itemId the item ID to find
     * @return the slot index, or -1 if not found
     */
    public int getSlotOf(int itemId) {
        List<BankItem> matching = itemIdIndex.get(itemId);
        if (matching == null || matching.isEmpty()) {
            return -1;
        }
        return matching.get(0).getSlot();
    }

    /**
     * Get all slot indices containing a specific item.
     *
     * @param itemId the item ID to find
     * @return list of slot indices (may be empty)
     */
    public List<Integer> getSlotsOf(int itemId) {
        List<BankItem> matching = itemIdIndex.get(itemId);
        if (matching == null || matching.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> slots = new ArrayList<>();
        for (BankItem item : matching) {
            slots.add(item.getSlot());
        }
        return slots;
    }

    /**
     * Get the BankItem at a specific slot.
     *
     * @param slot the slot index
     * @return Optional containing the BankItem, or empty if slot is empty
     */
    public Optional<BankItem> getItemAtSlot(int slot) {
        for (BankItem item : items) {
            if (item.getSlot() == slot) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    /**
     * Get all unique item IDs in the bank.
     *
     * @return set of item IDs
     */
    public Set<Integer> getAllItemIds() {
        return Collections.unmodifiableSet(itemIdIndex.keySet());
    }

    /**
     * Get all bank items as an unmodifiable list.
     *
     * @return unmodifiable list of BankItems
     */
    public List<BankItem> getAllItems() {
        return items; // Already unmodifiable from constructor
    }

    // ========================================================================
    // Tab Operations
    // ========================================================================

    /**
     * Get all items in a specific bank tab.
     *
     * @param tabIndex the tab index (0 = main tab, 1-9 = custom tabs)
     * @return list of items in the tab
     */
    public List<BankItem> getItemsInTab(int tabIndex) {
        List<BankItem> tabItems = this.tabIndex.get(tabIndex);
        return tabItems == null ? Collections.emptyList() : Collections.unmodifiableList(tabItems);
    }

    /**
     * Get the number of tabs with items.
     *
     * @return count of non-empty tabs
     */
    public int getTabCount() {
        return tabIndex.size();
    }

    /**
     * Check if a specific tab has any items.
     *
     * @param tabIndex the tab index to check
     * @return true if the tab has items
     */
    public boolean hasItemsInTab(int tabIndex) {
        List<BankItem> tabItems = this.tabIndex.get(tabIndex);
        return tabItems != null && !tabItems.isEmpty();
    }

    /**
     * Find which tab an item is in.
     *
     * @param itemId the item ID to find
     * @return the tab index, or -1 if not found
     */
    public int getTabOf(int itemId) {
        List<BankItem> matching = itemIdIndex.get(itemId);
        if (matching == null || matching.isEmpty()) {
            return -1;
        }
        return matching.get(0).getTab();
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    /**
     * Get the total number of unique items in the bank.
     *
     * @return count of unique item types
     */
    public int getUniqueItemCount() {
        return itemIdIndex.size();
    }

    /**
     * Get the total number of occupied slots.
     *
     * @return count of occupied slots
     */
    public int getUsedSlots() {
        return items.size();
    }

    /**
     * Get the number of free slots (approximate - depends on account type).
     *
     * @param maxSlots the maximum slots for this account
     * @return approximate free slots
     */
    public int getFreeSlots(int maxSlots) {
        return Math.max(0, maxSlots - items.size());
    }

    /**
     * Check if the bank is empty.
     *
     * @return true if no items in bank
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    // ========================================================================
    // Freshness and Validity
    // ========================================================================

    /**
     * Check if this bank state is unknown (never observed).
     *
     * @return true if bank has never been opened/captured
     */
    public boolean isUnknown() {
        return unknown;
    }

    /**
     * Check if this bank state is known (has been observed at least once).
     *
     * @return true if bank has been observed
     */
    public boolean isKnown() {
        return !unknown;
    }

    /**
     * Get the age of this bank state in ticks.
     *
     * @param currentTick the current game tick
     * @return ticks since last update, or -1 if never updated
     */
    public int getAgeInTicks(int currentTick) {
        if (lastUpdatedTick < 0) {
            return -1;
        }
        return currentTick - lastUpdatedTick;
    }

    /**
     * Check if the bank state is stale (older than threshold).
     *
     * @param currentTick    the current game tick
     * @param staleTicks     threshold in ticks
     * @return true if state is older than threshold
     */
    public boolean isStale(int currentTick, int staleTicks) {
        if (lastUpdatedTick < 0) {
            return true;
        }
        return (currentTick - lastUpdatedTick) > staleTicks;
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Get a summary string for logging.
     *
     * @return summary string
     */
    public String getSummary() {
        if (unknown) {
            return "BankState[UNKNOWN]";
        }
        if (isEmpty()) {
            return "BankState[EMPTY, tick=" + lastUpdatedTick + "]";
        }
        return String.format("BankState[items=%d, unique=%d, tabs=%d, tick=%d]",
                items.size(), getUniqueItemCount(), getTabCount(), lastUpdatedTick);
    }

    @Override
    public String toString() {
        return getSummary();
    }

    // ========================================================================
    // BankItem Inner Class
    // ========================================================================

    /**
     * Represents a single item in the bank.
     */
    @Value
    @lombok.Builder
    public static class BankItem {
        /**
         * The item ID.
         */
        int itemId;

        /**
         * The quantity (stack size for stackable items, 1 for non-stackable).
         */
        int quantity;

        /**
         * The slot index in the bank (0-based).
         */
        int slot;

        /**
         * The tab index (0 = main tab, 1-9 = custom tabs).
         */
        @lombok.Builder.Default
        int tab = 0;

        /**
         * Check if this is a placeholder (quantity 0 but slot reserved).
         *
         * @return true if placeholder
         */
        public boolean isPlaceholder() {
            return quantity == 0;
        }
    }
}
