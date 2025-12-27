package com.rocinante.data.model;

import lombok.Builder;

import java.util.Collections;
import java.util.List;

/**
 * Item acquisition source data from OSRS Wiki.
 *
 * <p>Per REQUIREMENTS.md Section 8A.3.2, contains information about where
 * and how to obtain an item. Used for:
 * <ul>
 *   <li>Ironman acquisition planning</li>
 *   <li>Gear progression planning</li>
 *   <li>Quest item preparation</li>
 * </ul>
 *
 * <p>Wiki Infobox Fields parsed:
 * <ul>
 *   <li>source — How to obtain the item</li>
 *   <li>examine — Item description</li>
 *   <li>quest — Quest requirements</li>
 *   <li>tradeable — Can be traded (affects ironman methods)</li>
 * </ul>
 */
@Builder
public record ItemSource(
        /**
         * Item ID.
         */
        int itemId,

        /**
         * Item name as it appears on the wiki.
         */
        String itemName,

        /**
         * Item examine text.
         */
        String examine,

        /**
         * Whether the item is tradeable on GE.
         */
        boolean tradeable,

        /**
         * Whether the item is members only.
         */
        boolean membersOnly,

        /**
         * Quest required to obtain/use this item (empty if none).
         */
        String questRequirement,

        /**
         * All known sources for obtaining this item.
         */
        List<Source> sources,

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
     * Get an unmodifiable view of sources.
     */
    @Override
    public List<Source> sources() {
        return sources != null ? Collections.unmodifiableList(sources) : Collections.emptyList();
    }

    /**
     * A single source for obtaining an item.
     */
    @Builder
    public record Source(
            /**
             * Source type (e.g., "Monster drop", "Shop", "Quest reward", "Crafting", "Spawn").
             */
            SourceType type,

            /**
             * Source name (monster name, shop name, quest name, etc.).
             */
            String name,

            /**
             * Location where the source is found.
             */
            String location,

            /**
             * Quantity available/dropped (may be a range like "1-3").
             */
            String quantity,

            /**
             * Cost if purchasable (in GP).
             * -1 if not applicable.
             */
            int cost,

            /**
             * Drop rate if from monster drops.
             */
            String dropRate,

            /**
             * Requirements to access this source.
             */
            String requirements,

            /**
             * Whether this source is members only.
             */
            boolean membersOnly,

            /**
             * Additional notes.
             */
            String notes
    ) {
        /**
         * Check if this is a free source (no cost).
         */
        public boolean isFree() {
            return cost <= 0 && type != SourceType.SHOP;
        }

        /**
         * Check if this source is available to ironmen.
         */
        public boolean isIronmanAccessible() {
            // All sources except GE are ironman accessible
            return type != SourceType.GRAND_EXCHANGE;
        }
    }

    /**
     * Types of item sources.
     */
    public enum SourceType {
        /**
         * Dropped by monsters.
         */
        MONSTER_DROP,

        /**
         * Sold in NPC shops.
         */
        SHOP,

        /**
         * Obtained from completing quests.
         */
        QUEST_REWARD,

        /**
         * Created through skills (crafting, smithing, etc.).
         */
        CRAFTING,

        /**
         * Spawns in the world.
         */
        SPAWN,

        /**
         * Obtained from thieving.
         */
        THIEVING,

        /**
         * Obtained from clue scrolls.
         */
        CLUE_SCROLL,

        /**
         * Obtained from minigames.
         */
        MINIGAME,

        /**
         * Purchased from Grand Exchange (not available to ironmen).
         */
        GRAND_EXCHANGE,

        /**
         * Obtained from farming.
         */
        FARMING,

        /**
         * Other/unknown source type.
         */
        OTHER;

        /**
         * Parse source type from wiki text.
         *
         * @param text the wiki text
         * @return the source type
         */
        public static SourceType fromWikiText(String text) {
            if (text == null || text.isEmpty()) {
                return OTHER;
            }
            String lower = text.toLowerCase();
            if (lower.contains("monster") || lower.contains("drop") || lower.contains("kill")) {
                return MONSTER_DROP;
            }
            if (lower.contains("shop") || lower.contains("store") || lower.contains("buy")) {
                return SHOP;
            }
            if (lower.contains("quest")) {
                return QUEST_REWARD;
            }
            if (lower.contains("craft") || lower.contains("smith") || lower.contains("fletch") || lower.contains("make")) {
                return CRAFTING;
            }
            if (lower.contains("spawn") || lower.contains("respawn") || lower.contains("pick up")) {
                return SPAWN;
            }
            if (lower.contains("thiev") || lower.contains("pickpocket") || lower.contains("steal")) {
                return THIEVING;
            }
            if (lower.contains("clue") || lower.contains("treasure")) {
                return CLUE_SCROLL;
            }
            if (lower.contains("minigame") || lower.contains("reward")) {
                return MINIGAME;
            }
            if (lower.contains("grand exchange") || lower.contains("ge")) {
                return GRAND_EXCHANGE;
            }
            if (lower.contains("farm") || lower.contains("grow") || lower.contains("harvest")) {
                return FARMING;
            }
            return OTHER;
        }
    }

    /**
     * Get all sources available to ironmen.
     *
     * @return list of ironman-accessible sources
     */
    public List<Source> getIronmanSources() {
        return sources().stream()
                .filter(Source::isIronmanAccessible)
                .toList();
    }

    /**
     * Get all shop sources.
     *
     * @return list of shop sources
     */
    public List<Source> getShopSources() {
        return sources().stream()
                .filter(s -> s.type() == SourceType.SHOP)
                .toList();
    }

    /**
     * Get all monster drop sources.
     *
     * @return list of monster drop sources
     */
    public List<Source> getMonsterDropSources() {
        return sources().stream()
                .filter(s -> s.type() == SourceType.MONSTER_DROP)
                .toList();
    }

    /**
     * Get the cheapest shop source.
     *
     * @return the cheapest shop source, or null if none
     */
    public Source getCheapestShop() {
        return sources().stream()
                .filter(s -> s.type() == SourceType.SHOP && s.cost() > 0)
                .min((a, b) -> Integer.compare(a.cost(), b.cost()))
                .orElse(null);
    }

    /**
     * Check if this item is obtainable without combat.
     *
     * @return true if non-combat sources exist
     */
    public boolean hasNonCombatSource() {
        return sources().stream()
                .anyMatch(s -> s.type() != SourceType.MONSTER_DROP);
    }

    /**
     * Check if this item is a quest reward.
     *
     * @return true if obtainable from a quest
     */
    public boolean isQuestReward() {
        return sources().stream()
                .anyMatch(s -> s.type() == SourceType.QUEST_REWARD);
    }

    /**
     * Check if this item has a world spawn.
     *
     * @return true if spawns in the world
     */
    public boolean hasWorldSpawn() {
        return sources().stream()
                .anyMatch(s -> s.type() == SourceType.SPAWN);
    }

    /**
     * Check if this is valid item source data.
     *
     * @return true if valid
     */
    public boolean isValid() {
        return itemName != null && !itemName.isEmpty();
    }

    /**
     * Empty item source singleton.
     */
    public static final ItemSource EMPTY = ItemSource.builder()
            .itemId(-1)
            .itemName("")
            .examine("")
            .tradeable(false)
            .membersOnly(false)
            .questRequirement("")
            .sources(Collections.emptyList())
            .wikiUrl("")
            .fetchedAt("")
            .build();
}

