package com.rocinante.data.model;

import lombok.Builder;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Drop table data from OSRS Wiki.
 *
 * <p>Per REQUIREMENTS.md Section 8A.3.1, contains parsed drop information
 * for a monster. Used for:
 * <ul>
 *   <li>Ironman item acquisition planning</li>
 *   <li>GP/hour calculations</li>
 *   <li>Slayer loot expectations</li>
 * </ul>
 *
 * <p>Example wiki API query:
 * {@code GET /api.php?action=parse&page=Abyssal_demon&prop=wikitext&format=json}
 */
@Builder
public record DropTable(
        /**
         * Monster name as it appears on the wiki.
         */
        String monsterName,

        /**
         * Monster combat level (0 for non-combat NPCs).
         */
        int combatLevel,

        /**
         * Slayer level required to kill this monster (0 if none).
         */
        int slayerLevel,

        /**
         * List of all drops from this monster.
         */
        List<Drop> drops,

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
     * Get an unmodifiable view of drops.
     */
    @Override
    public List<Drop> drops() {
        return drops != null ? Collections.unmodifiableList(drops) : Collections.emptyList();
    }

    /**
     * A single drop entry from the drop table.
     */
    @Builder
    public record Drop(
            /**
             * Item ID (from RuneLite ItemID or wiki).
             * -1 if unknown/unresolved.
             */
            int itemId,

            /**
             * Item name as displayed on wiki.
             */
            String itemName,

            /**
             * Quantity expression (e.g., "1", "1-3", "100 (noted)").
             */
            String quantity,

            /**
             * Minimum quantity (parsed from quantity expression).
             */
            int quantityMin,

            /**
             * Maximum quantity (parsed from quantity expression).
             */
            int quantityMax,

            /**
             * Rarity category (e.g., "Always", "Common", "Uncommon", "Rare", "Very rare").
             */
            String rarity,

            /**
             * Fractional drop rate if specified (e.g., "1/128").
             * Empty string if not specified.
             */
            String dropRate,

            /**
             * Parsed drop rate as decimal (e.g., 0.0078125 for 1/128).
             * -1 if not parseable.
             */
            double dropRateDecimal,

            /**
             * Whether this is a members-only drop.
             */
            boolean membersOnly,

            /**
             * Whether this drop is noted.
             */
            boolean noted,

            /**
             * Additional notes or requirements for this drop.
             */
            String notes
    ) {
        /**
         * Check if this drop has a known item ID.
         */
        public boolean hasItemId() {
            return itemId > 0;
        }

        /**
         * Check if this is a guaranteed drop.
         */
        public boolean isAlways() {
            return "Always".equalsIgnoreCase(rarity) || dropRateDecimal >= 1.0;
        }

        /**
         * Check if this is considered rare (1/100 or rarer).
         */
        public boolean isRare() {
            if ("Rare".equalsIgnoreCase(rarity) || "Very rare".equalsIgnoreCase(rarity)) {
                return true;
            }
            return dropRateDecimal > 0 && dropRateDecimal <= 0.01;
        }

        /**
         * Get estimated GP value for this drop.
         * Returns -1 if item ID is unknown.
         *
         * @param itemPrice the price per item
         * @return estimated value or -1
         */
        public long estimatedValue(int itemPrice) {
            if (itemPrice <= 0) {
                return -1;
            }
            // Use average quantity for estimation
            int avgQuantity = (quantityMin + quantityMax) / 2;
            if (avgQuantity <= 0) {
                avgQuantity = 1;
            }
            return (long) itemPrice * avgQuantity;
        }
    }

    /**
     * Find a specific drop by item ID.
     *
     * @param itemId the item ID to find
     * @return the drop, or empty if not found
     */
    public Optional<Drop> findDropById(int itemId) {
        return drops().stream()
                .filter(d -> d.itemId() == itemId)
                .findFirst();
    }

    /**
     * Find drops by item name (case-insensitive partial match).
     *
     * @param itemName the item name to search
     * @return list of matching drops
     */
    public List<Drop> findDropsByName(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return Collections.emptyList();
        }
        String searchLower = itemName.toLowerCase();
        return drops().stream()
                .filter(d -> d.itemName() != null && d.itemName().toLowerCase().contains(searchLower))
                .toList();
    }

    /**
     * Get all guaranteed drops (rarity = "Always").
     *
     * @return list of guaranteed drops
     */
    public List<Drop> getGuaranteedDrops() {
        return drops().stream()
                .filter(Drop::isAlways)
                .toList();
    }

    /**
     * Get all rare drops.
     *
     * @return list of rare drops
     */
    public List<Drop> getRareDrops() {
        return drops().stream()
                .filter(Drop::isRare)
                .toList();
    }

    /**
     * Check if this monster drops a specific item.
     *
     * @param itemId the item ID to check
     * @return true if monster drops this item
     */
    public boolean dropsItem(int itemId) {
        return findDropById(itemId).isPresent();
    }

    /**
     * Get the drop rate for a specific item.
     *
     * @param itemId the item ID
     * @return the drop rate as decimal, or -1 if not found/unknown
     */
    public double getDropRate(int itemId) {
        return findDropById(itemId)
                .map(Drop::dropRateDecimal)
                .orElse(-1.0);
    }

    /**
     * Calculate expected kills to get a specific item.
     *
     * @param itemId the item ID
     * @return expected kills, or -1 if drop rate unknown
     */
    public int expectedKillsForDrop(int itemId) {
        double rate = getDropRate(itemId);
        if (rate <= 0) {
            return -1;
        }
        return (int) Math.ceil(1.0 / rate);
    }

    /**
     * Check if this is a valid drop table (has at least one drop).
     *
     * @return true if valid
     */
    public boolean isValid() {
        return monsterName != null && !monsterName.isEmpty() && !drops().isEmpty();
    }

    /**
     * Empty drop table singleton.
     */
    public static final DropTable EMPTY = DropTable.builder()
            .monsterName("")
            .combatLevel(0)
            .slayerLevel(0)
            .drops(Collections.emptyList())
            .wikiUrl("")
            .fetchedAt("")
            .build();
}

