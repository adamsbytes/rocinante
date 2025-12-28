package com.rocinante.util;

import com.rocinante.state.BankState;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import net.runelite.api.ItemID;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Common item collections for tasks that accept multiple item variants.
 * 
 * <p>Collections are ordered worst→best (by level requirement or value).
 * This ordering is used for "sacrifice/consume" scenarios where we want
 * to use the cheapest item first.
 * 
 * <p>For "use best available" scenarios (tools), use {@link #bestFirst(List)}
 * to reverse the order, then filter by what's actually available.
 * 
 * <p>Example usage:
 * <pre>{@code
 * // Get best pickaxe player can use from inventory
 * Optional<Integer> pick = ItemCollections.getBestAvailable(
 *     ItemCollections.PICKAXES,
 *     ItemCollections.PICKAXE_LEVELS,
 *     playerMiningLevel,
 *     inventory::hasItem
 * );
 * }</pre>
 */
public final class ItemCollections {

    private ItemCollections() {
        // Utility class
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Returns the collection in best-first order (reversed).
     * Use this for "use best tool" scenarios where we want the best available.
     *
     * @param collection the collection ordered worst→best
     * @return new list ordered best→worst
     */
    public static List<Integer> bestFirst(List<Integer> collection) {
        List<Integer> reversed = new ArrayList<>(collection);
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * Get the best item from a collection that meets the level requirement
     * and passes the availability check.
     *
     * @param collection    items ordered worst→best
     * @param levelReqs     map of item ID to required level (items not in map default to level 1)
     * @param playerLevel   player's current level in the relevant skill
     * @param isAvailable   predicate to check if item is available (e.g., inventory::hasItem)
     * @return the best available item, or empty if none found
     */
    public static Optional<Integer> getBestAvailable(
            List<Integer> collection,
            Map<Integer, Integer> levelReqs,
            int playerLevel,
            Predicate<Integer> isAvailable) {
        
        return bestFirst(collection).stream()
                .filter(id -> levelReqs.getOrDefault(id, 1) <= playerLevel)
                .filter(isAvailable)
                .findFirst();
    }

    /**
     * Get the best item from a collection that meets the level requirement
     * and is present in inventory, equipment, or bank.
     *
     * @param collection    items ordered worst→best
     * @param levelReqs     map of item ID to required level
     * @param playerLevel   player's current level
     * @param inventory     player's inventory state
     * @param equipment     player's equipment state (may be null)
     * @param bank          player's bank state (may be null)
     * @return the best available item, or empty if none found
     */
    public static Optional<Integer> getBestAvailable(
            List<Integer> collection,
            Map<Integer, Integer> levelReqs,
            int playerLevel,
            InventoryState inventory,
            EquipmentState equipment,
            BankState bank) {
        
        Predicate<Integer> isAvailable = id -> {
            if (inventory != null && inventory.hasItem(id)) return true;
            if (equipment != null && equipment.hasEquipped(id)) return true;
            if (bank != null && bank.hasItem(id)) return true;
            return false;
        };
        
        return getBestAvailable(collection, levelReqs, playerLevel, isAvailable);
    }

    /**
     * Get the first item from a collection that passes the availability check.
     * Uses worst→best order (for consuming/sacrificing items).
     *
     * @param collection  items ordered worst→best
     * @param isAvailable predicate to check if item is available
     * @return the first available item, or empty if none found
     */
    public static Optional<Integer> getFirstAvailable(
            List<Integer> collection,
            Predicate<Integer> isAvailable) {
        
        return collection.stream()
                .filter(isAvailable)
                .findFirst();
    }

    // ========================================================================
    // Tinderboxes
    // ========================================================================

    /**
     * All tinderbox variants that can light fires.
     * Regular tinderbox first (most common).
     */
    public static final List<Integer> TINDERBOXES = List.of(
            ItemID.TINDERBOX,
            ItemID.GOLDEN_TINDERBOX
    );

    // ========================================================================
    // Logs (Burnable) - ordered by Firemaking level requirement
    // ========================================================================

    /**
     * Basic logs that can be burned with a tinderbox for Firemaking XP.
     * Ordered by level requirement (lowest first) so we burn cheap logs before expensive ones.
     * Does NOT include pyre logs (Shades of Mort'ton) or colored logs.
     */
    public static final List<Integer> BURNABLE_LOGS = List.of(
            ItemID.LOGS,              // Level 1
            ItemID.LOGS_2511,         // Tutorial Island variant
            ItemID.ACHEY_TREE_LOGS,   // Level 1
            ItemID.OAK_LOGS,          // Level 15
            ItemID.WILLOW_LOGS,       // Level 30
            ItemID.TEAK_LOGS,         // Level 35
            ItemID.ARCTIC_PINE_LOGS,  // Level 42
            ItemID.MAPLE_LOGS,        // Level 45
            ItemID.MAHOGANY_LOGS,     // Level 50
            ItemID.YEW_LOGS,          // Level 60
            ItemID.BLISTERWOOD_LOGS,  // Level 62
            ItemID.MAGIC_LOGS,        // Level 75
            ItemID.REDWOOD_LOGS,      // Level 90
            ItemID.JUNIPER_LOGS       // Level 1 but special (Zeah)
    );

    // ========================================================================
    // Water Containers
    // ========================================================================

    /**
     * Water containers that can be used for cooking/crafting (bread dough, etc).
     * Bucket first as most common.
     */
    public static final List<Integer> WATER_CONTAINERS = List.of(
            ItemID.BUCKET_OF_WATER,
            ItemID.JUG_OF_WATER,
            ItemID.BOWL_OF_WATER
    );

    // ========================================================================
    // Axes - ordered by Woodcutting level requirement
    // ========================================================================

    /**
     * All axes that can be used for Woodcutting.
     * Ordered by level requirement (lowest first).
     */
    public static final List<Integer> AXES = List.of(
            ItemID.BRONZE_AXE,        // Level 1
            ItemID.IRON_AXE,          // Level 1
            ItemID.STEEL_AXE,         // Level 6
            ItemID.BLACK_AXE,         // Level 11
            ItemID.MITHRIL_AXE,       // Level 21
            ItemID.ADAMANT_AXE,       // Level 31
            ItemID.RUNE_AXE,          // Level 41
            ItemID.DRAGON_AXE,        // Level 61
            ItemID._3RD_AGE_AXE,      // Level 61
            ItemID.INFERNAL_AXE,      // Level 61
            ItemID.CRYSTAL_AXE        // Level 71
    );

    /**
     * Woodcutting level requirements for axes.
     */
    public static final Map<Integer, Integer> AXE_LEVELS = Map.ofEntries(
            Map.entry(ItemID.BRONZE_AXE, 1),
            Map.entry(ItemID.IRON_AXE, 1),
            Map.entry(ItemID.STEEL_AXE, 6),
            Map.entry(ItemID.BLACK_AXE, 11),
            Map.entry(ItemID.MITHRIL_AXE, 21),
            Map.entry(ItemID.ADAMANT_AXE, 31),
            Map.entry(ItemID.RUNE_AXE, 41),
            Map.entry(ItemID.DRAGON_AXE, 61),
            Map.entry(ItemID._3RD_AGE_AXE, 61),
            Map.entry(ItemID.INFERNAL_AXE, 61),
            Map.entry(ItemID.CRYSTAL_AXE, 71)
    );

    // ========================================================================
    // Pickaxes - ordered by Mining level requirement
    // ========================================================================

    /**
     * All pickaxes that can be used for Mining.
     * Ordered by level requirement (lowest first).
     */
    public static final List<Integer> PICKAXES = List.of(
            ItemID.BRONZE_PICKAXE,    // Level 1
            ItemID.IRON_PICKAXE,      // Level 1
            ItemID.STEEL_PICKAXE,     // Level 6
            ItemID.BLACK_PICKAXE,     // Level 11
            ItemID.MITHRIL_PICKAXE,   // Level 21
            ItemID.ADAMANT_PICKAXE,   // Level 31
            ItemID.RUNE_PICKAXE,      // Level 41
            ItemID.DRAGON_PICKAXE,    // Level 61
            ItemID._3RD_AGE_PICKAXE,  // Level 61
            ItemID.INFERNAL_PICKAXE,  // Level 61
            ItemID.CRYSTAL_PICKAXE    // Level 71
    );

    /**
     * Mining level requirements for pickaxes.
     */
    public static final Map<Integer, Integer> PICKAXE_LEVELS = Map.ofEntries(
            Map.entry(ItemID.BRONZE_PICKAXE, 1),
            Map.entry(ItemID.IRON_PICKAXE, 1),
            Map.entry(ItemID.STEEL_PICKAXE, 6),
            Map.entry(ItemID.BLACK_PICKAXE, 11),
            Map.entry(ItemID.MITHRIL_PICKAXE, 21),
            Map.entry(ItemID.ADAMANT_PICKAXE, 31),
            Map.entry(ItemID.RUNE_PICKAXE, 41),
            Map.entry(ItemID.DRAGON_PICKAXE, 61),
            Map.entry(ItemID._3RD_AGE_PICKAXE, 61),
            Map.entry(ItemID.INFERNAL_PICKAXE, 61),
            Map.entry(ItemID.CRYSTAL_PICKAXE, 71)
    );

    // ========================================================================
    // Raw Fish
    // ========================================================================

    /**
     * Raw shrimp variants (includes Tutorial Island variant).
     */
    public static final List<Integer> RAW_SHRIMP = List.of(
            ItemID.RAW_SHRIMPS,
            ItemID.RAW_SHRIMPS_2514   // Tutorial Island variant
    );

    // ========================================================================
    // Cooking Ingredients
    // ========================================================================

    /**
     * Pot of flour variants (includes Tutorial Island variant).
     */
    public static final List<Integer> POTS_OF_FLOUR = List.of(
            ItemID.POT_OF_FLOUR,
            ItemID.POT_OF_FLOUR_2516  // Tutorial Island variant
    );

    // ========================================================================
    // Bones - ordered by Prayer XP (lowest first)
    // ========================================================================

    /**
     * Common bones for Prayer training.
     * Ordered by XP given (lowest first) so we use cheap bones before expensive ones.
     */
    public static final List<Integer> BONES = List.of(
            ItemID.BONES,             // 4.5 XP
            ItemID.BURNT_BONES,       // 4.5 XP
            ItemID.WOLF_BONES,        // 4.5 XP
            ItemID.MONKEY_BONES,      // 5 XP
            ItemID.BAT_BONES,         // 5.3 XP
            ItemID.BIG_BONES,         // 15 XP
            ItemID.ZOGRE_BONES,       // 22.5 XP
            ItemID.BABYDRAGON_BONES,  // 30 XP
            ItemID.WYVERN_BONES,      // 72 XP
            ItemID.DRAGON_BONES,      // 72 XP
            ItemID.FAYRG_BONES,       // 84 XP
            ItemID.LAVA_DRAGON_BONES, // 85 XP
            ItemID.RAURG_BONES,       // 96 XP
            ItemID.DAGANNOTH_BONES,   // 125 XP
            ItemID.OURG_BONES,        // 140 XP
            ItemID.SUPERIOR_DRAGON_BONES // 150 XP
    );

    // ========================================================================
    // Food - ordered by healing amount (lowest first)
    // ========================================================================

    /**
     * Common food items for healing.
     * Ordered by healing amount (lowest first) so we eat cheap food before expensive.
     */
    public static final List<Integer> FOOD = List.of(
            ItemID.SHRIMPS,           // 3 HP
            ItemID.COOKED_CHICKEN,    // 3 HP
            ItemID.COOKED_MEAT,       // 3 HP
            ItemID.BREAD,             // 5 HP
            ItemID.TROUT,             // 7 HP
            ItemID.SALMON,            // 9 HP
            ItemID.TUNA,              // 10 HP
            ItemID.LOBSTER,           // 12 HP
            ItemID.BASS,              // 13 HP
            ItemID.SWORDFISH,         // 14 HP
            ItemID.MONKFISH,          // 16 HP
            ItemID.SHARK,             // 20 HP
            ItemID.MANTA_RAY,         // 22 HP
            ItemID.DARK_CRAB,         // 22 HP
            ItemID.ANGLERFISH         // 22 HP (scales with HP level)
    );
}
