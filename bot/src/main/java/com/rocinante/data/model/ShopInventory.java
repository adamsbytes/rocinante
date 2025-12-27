package com.rocinante.data.model;

import lombok.Builder;
import net.runelite.api.coords.WorldPoint;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Shop inventory data from OSRS Wiki.
 *
 * <p>Per REQUIREMENTS.md Section 8A.3.3, contains shop stock information.
 * Used for:
 * <ul>
 *   <li>Shop run planning</li>
 *   <li>Ironman supply acquisition</li>
 *   <li>World hopping optimization</li>
 * </ul>
 *
 * <p>Data Extracted:
 * <ul>
 *   <li>Shop name and location</li>
 *   <li>Items sold (ID, price, stock)</li>
 *   <li>Restock mechanics (timed vs. player-sold)</li>
 * </ul>
 */
@Builder
public record ShopInventory(
        /**
         * Shop name as it appears on wiki.
         */
        String shopName,

        /**
         * NPC who runs the shop.
         */
        String shopkeeperName,

        /**
         * NPC ID of the shopkeeper.
         * -1 if unknown.
         */
        int shopkeeperNpcId,

        /**
         * Shop location description.
         */
        String location,

        /**
         * World point of the shop.
         * May be null if not known.
         */
        WorldPoint worldPoint,

        /**
         * Whether this is a members-only shop.
         */
        boolean membersOnly,

        /**
         * Whether this shop is a general store (buys/sells most items).
         */
        boolean generalStore,

        /**
         * Currency used by this shop (usually "Coins").
         */
        String currency,

        /**
         * List of items sold by this shop.
         */
        List<ShopItem> items,

        /**
         * Wiki page URL for reference.
         */
        String wikiUrl,

        /**
         * Timestamp when this data was fetched.
         */
        String fetchedAt
) {
    /**
     * Get an unmodifiable view of shop items.
     */
    @Override
    public List<ShopItem> items() {
        return items != null ? Collections.unmodifiableList(items) : Collections.emptyList();
    }

    /**
     * A single item in a shop's inventory.
     */
    @Builder
    public record ShopItem(
            /**
             * Item ID.
             * -1 if unknown.
             */
            int itemId,

            /**
             * Item name.
             */
            String itemName,

            /**
             * Base stock (amount available when fully stocked).
             * 0 means player-sold only (no base stock).
             */
            int baseStock,

            /**
             * Base price in shop currency.
             */
            int basePrice,

            /**
             * Whether this item is sold by players (variable stock).
             */
            boolean playerSold,

            /**
             * Whether this item is members only.
             */
            boolean membersOnly,

            /**
             * Stock restock rate in game ticks.
             * -1 if unknown or instant.
             */
            int restockRate,

            /**
             * Maximum stock this shop can hold.
             * -1 if unlimited.
             */
            int maxStock,

            /**
             * Additional notes about this item.
             */
            String notes
    ) {
        /**
         * Calculate the price at a given stock level.
         * Shop prices increase when stock is low and decrease when high.
         *
         * @param currentStock current stock level
         * @return price at this stock level
         */
        public int priceAtStock(int currentStock) {
            // OSRS shop price formula
            // Price increases as stock decreases below base stock
            // Price decreases as stock increases above base stock
            if (baseStock <= 0) {
                return basePrice;
            }

            double stockRatio = (double) currentStock / baseStock;
            
            if (stockRatio >= 1.0) {
                // At or above base stock - use base price
                return basePrice;
            } else if (currentStock <= 0) {
                // No stock - maximum price (130% of base)
                return (int) (basePrice * 1.3);
            } else {
                // Below base stock - price increases linearly
                double priceMultiplier = 1.0 + (0.3 * (1.0 - stockRatio));
                return (int) (basePrice * priceMultiplier);
            }
        }

        /**
         * Check if this item has base stock (not player-sold only).
         */
        public boolean hasBaseStock() {
            return baseStock > 0;
        }

        /**
         * Check if this item is in stock at base level.
         */
        public boolean isInStock() {
            return hasBaseStock() || playerSold;
        }
    }

    /**
     * Find an item by ID.
     *
     * @param itemId the item ID
     * @return the shop item, or empty if not found
     */
    public Optional<ShopItem> findItemById(int itemId) {
        return items().stream()
                .filter(i -> i.itemId() == itemId)
                .findFirst();
    }

    /**
     * Find items by name (case-insensitive partial match).
     *
     * @param itemName the item name to search
     * @return list of matching items
     */
    public List<ShopItem> findItemsByName(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return Collections.emptyList();
        }
        String searchLower = itemName.toLowerCase();
        return items().stream()
                .filter(i -> i.itemName() != null && i.itemName().toLowerCase().contains(searchLower))
                .toList();
    }

    /**
     * Check if this shop sells a specific item.
     *
     * @param itemId the item ID
     * @return true if the shop sells this item
     */
    public boolean sellsItem(int itemId) {
        return findItemById(itemId).isPresent();
    }

    /**
     * Get the price for an item at this shop.
     *
     * @param itemId the item ID
     * @return the base price, or -1 if not sold
     */
    public int getPrice(int itemId) {
        return findItemById(itemId)
                .map(ShopItem::basePrice)
                .orElse(-1);
    }

    /**
     * Get the stock for an item at this shop.
     *
     * @param itemId the item ID
     * @return the base stock, or -1 if not sold
     */
    public int getStock(int itemId) {
        return findItemById(itemId)
                .map(ShopItem::baseStock)
                .orElse(-1);
    }

    /**
     * Get all items with base stock (for shop runs).
     *
     * @return list of items with base stock
     */
    public List<ShopItem> getItemsWithStock() {
        return items().stream()
                .filter(ShopItem::hasBaseStock)
                .toList();
    }

    /**
     * Check if this is a valid shop inventory.
     *
     * @return true if valid
     */
    public boolean isValid() {
        return shopName != null && !shopName.isEmpty();
    }

    /**
     * Empty shop inventory singleton.
     */
    public static final ShopInventory EMPTY = ShopInventory.builder()
            .shopName("")
            .shopkeeperName("")
            .shopkeeperNpcId(-1)
            .location("")
            .worldPoint(null)
            .membersOnly(false)
            .generalStore(false)
            .currency("Coins")
            .items(Collections.emptyList())
            .wikiUrl("")
            .fetchedAt("")
            .build();
}

