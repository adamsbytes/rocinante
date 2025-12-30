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

    // ========================================================================
    // Slayer Equipment - Protective
    // ========================================================================

    /**
     * Slayer helmets (all variants including imbued and colored).
     * All provide protection against multiple slayer creatures.
     */
    public static final List<Integer> SLAYER_HELMETS = List.of(
            ItemID.SLAYER_HELMET,
            ItemID.SLAYER_HELMET_I,
            ItemID.BLACK_SLAYER_HELMET,
            ItemID.BLACK_SLAYER_HELMET_I,
            ItemID.GREEN_SLAYER_HELMET,
            ItemID.GREEN_SLAYER_HELMET_I,
            ItemID.RED_SLAYER_HELMET,
            ItemID.RED_SLAYER_HELMET_I,
            ItemID.PURPLE_SLAYER_HELMET,
            ItemID.PURPLE_SLAYER_HELMET_I,
            ItemID.TURQUOISE_SLAYER_HELMET,
            ItemID.TURQUOISE_SLAYER_HELMET_I,
            ItemID.HYDRA_SLAYER_HELMET,
            ItemID.HYDRA_SLAYER_HELMET_I,
            ItemID.TWISTED_SLAYER_HELMET,
            ItemID.TWISTED_SLAYER_HELMET_I,
            ItemID.TZTOK_SLAYER_HELMET,
            ItemID.TZTOK_SLAYER_HELMET_I,
            ItemID.VAMPYRIC_SLAYER_HELMET,
            ItemID.VAMPYRIC_SLAYER_HELMET_I,
            ItemID.TZKAL_SLAYER_HELMET,
            ItemID.TZKAL_SLAYER_HELMET_I,
            ItemID.ARAXYTE_SLAYER_HELMET,
            ItemID.ARAXYTE_SLAYER_HELMET_I
    );

    /**
     * Nose peg - required for aberrant spectres.
     */
    public static final List<Integer> NOSE_PEGS = List.of(
            ItemID.NOSE_PEG
    );

    /**
     * Earmuffs - required for banshees.
     */
    public static final List<Integer> EARMUFFS = List.of(
            ItemID.EARMUFFS
    );

    /**
     * Mirror shield - required for basilisks and cockatrice.
     */
    public static final List<Integer> MIRROR_SHIELDS = List.of(
            ItemID.MIRROR_SHIELD
    );

    /**
     * Witchwood icon - required for cave horrors.
     */
    public static final List<Integer> WITCHWOOD_ICONS = List.of(
            ItemID.WITCHWOOD_ICON
    );

    /**
     * All spectre protection items (nose peg or slayer helmet).
     */
    public static final List<Integer> SPECTRE_PROTECTION = List.of(
            ItemID.NOSE_PEG,
            ItemID.SLAYER_HELMET,
            ItemID.SLAYER_HELMET_I,
            ItemID.BLACK_SLAYER_HELMET,
            ItemID.BLACK_SLAYER_HELMET_I,
            ItemID.GREEN_SLAYER_HELMET,
            ItemID.GREEN_SLAYER_HELMET_I,
            ItemID.RED_SLAYER_HELMET,
            ItemID.RED_SLAYER_HELMET_I,
            ItemID.PURPLE_SLAYER_HELMET,
            ItemID.PURPLE_SLAYER_HELMET_I,
            ItemID.TURQUOISE_SLAYER_HELMET,
            ItemID.TURQUOISE_SLAYER_HELMET_I,
            ItemID.HYDRA_SLAYER_HELMET,
            ItemID.HYDRA_SLAYER_HELMET_I,
            ItemID.TWISTED_SLAYER_HELMET,
            ItemID.TWISTED_SLAYER_HELMET_I
    );

    // ========================================================================
    // Slayer Equipment - Finishing Items
    // ========================================================================

    /**
     * Rock hammer - used to finish gargoyles below 9 HP.
     */
    public static final List<Integer> ROCK_HAMMERS = List.of(
            ItemID.ROCK_HAMMER
    );

    /**
     * Bag of salt - used to finish rockslugs below 4 HP.
     */
    public static final List<Integer> BAGS_OF_SALT = List.of(
            ItemID.BAG_OF_SALT
    );

    /**
     * Ice cooler - used to finish desert lizards below 4 HP.
     */
    public static final List<Integer> ICE_COOLERS = List.of(
            ItemID.ICE_COOLER
    );

    // ========================================================================
    // Slayer Equipment - Leaf-Bladed Weapons
    // ========================================================================

    /**
     * Leaf-bladed weapons - required to damage kurasks and turoths.
     */
    public static final List<Integer> LEAF_BLADED_WEAPONS = List.of(
            ItemID.LEAFBLADED_SPEAR,
            ItemID.LEAFBLADED_SWORD,
            ItemID.LEAFBLADED_BATTLEAXE
    );

    // ========================================================================
    // Slayer Ammunition - Broad
    // ========================================================================

    /**
     * Broad arrows - can damage kurasks and turoths.
     */
    public static final List<Integer> BROAD_ARROWS = List.of(
            ItemID.BROAD_ARROWS,
            ItemID.BROAD_ARROWS_4160
    );

    /**
     * Broad bolts - can damage kurasks and turoths.
     */
    public static final List<Integer> BROAD_BOLTS = List.of(
            ItemID.BROAD_BOLTS,
            ItemID.AMETHYST_BROAD_BOLTS
    );

    // ========================================================================
    // Antipoison Potions
    // ========================================================================

    /**
     * Regular antipoison potions (all doses).
     */
    public static final List<Integer> ANTIPOISON_POTIONS = List.of(
            ItemID.ANTIPOISON4,
            ItemID.ANTIPOISON3,
            ItemID.ANTIPOISON2,
            ItemID.ANTIPOISON1
    );

    /**
     * Super antipoison potions (all doses).
     */
    public static final List<Integer> SUPER_ANTIPOISON_POTIONS = List.of(
            ItemID.SUPERANTIPOISON4,
            ItemID.SUPERANTIPOISON3,
            ItemID.SUPERANTIPOISON2,
            ItemID.SUPERANTIPOISON1
    );

    /**
     * Antidote++ potions (all doses) - strongest antipoison.
     */
    public static final List<Integer> ANTIDOTE_PLUS_PLUS = List.of(
            ItemID.ANTIDOTE4,
            ItemID.ANTIDOTE3,
            ItemID.ANTIDOTE2,
            ItemID.ANTIDOTE1
    );

    // ========================================================================
    // Antifire Potions
    // ========================================================================

    /**
     * Regular antifire potions (all doses).
     */
    public static final List<Integer> ANTIFIRE_POTIONS = List.of(
            ItemID.ANTIFIRE_POTION4,
            ItemID.ANTIFIRE_POTION3,
            ItemID.ANTIFIRE_POTION2,
            ItemID.ANTIFIRE_POTION1
    );

    /**
     * Extended antifire potions (all doses).
     */
    public static final List<Integer> EXTENDED_ANTIFIRE_POTIONS = List.of(
            ItemID.EXTENDED_ANTIFIRE4,
            ItemID.EXTENDED_ANTIFIRE3,
            ItemID.EXTENDED_ANTIFIRE2,
            ItemID.EXTENDED_ANTIFIRE1
    );

    /**
     * Super antifire potions (all doses).
     */
    public static final List<Integer> SUPER_ANTIFIRE_POTIONS = List.of(
            ItemID.SUPER_ANTIFIRE_POTION4,
            ItemID.SUPER_ANTIFIRE_POTION3,
            ItemID.SUPER_ANTIFIRE_POTION2,
            ItemID.SUPER_ANTIFIRE_POTION1
    );

    /**
     * Extended super antifire potions (all doses).
     */
    public static final List<Integer> EXTENDED_SUPER_ANTIFIRE_POTIONS = List.of(
            ItemID.EXTENDED_SUPER_ANTIFIRE4,
            ItemID.EXTENDED_SUPER_ANTIFIRE3,
            ItemID.EXTENDED_SUPER_ANTIFIRE2,
            ItemID.EXTENDED_SUPER_ANTIFIRE1
    );

    // ========================================================================
    // Dragonfire Protection Shields
    // ========================================================================

    /**
     * Anti-dragon shields - basic dragonfire protection.
     */
    public static final List<Integer> ANTI_DRAGON_SHIELDS = List.of(
            ItemID.ANTIDRAGON_SHIELD,
            ItemID.ANTIDRAGON_SHIELD_8282
    );

    /**
     * Dragonfire shields - superior dragonfire protection with special attack.
     */
    public static final List<Integer> DRAGONFIRE_SHIELDS = List.of(
            ItemID.DRAGONFIRE_SHIELD,
            ItemID.DRAGONFIRE_SHIELD_11284
    );

    /**
     * Dragonfire wards - ranged variant of dragonfire shield.
     */
    public static final List<Integer> DRAGONFIRE_WARDS = List.of(
            ItemID.DRAGONFIRE_WARD,
            ItemID.DRAGONFIRE_WARD_22003
    );

    /**
     * Ancient wyvern shields - ice dragonfire protection.
     */
    public static final List<Integer> ANCIENT_WYVERN_SHIELDS = List.of(
            ItemID.ANCIENT_WYVERN_SHIELD,
            ItemID.ANCIENT_WYVERN_SHIELD_21634
    );

    /**
     * All dragonfire protection equipment.
     */
    public static final List<Integer> ALL_DRAGONFIRE_PROTECTION = List.of(
            ItemID.ANTIDRAGON_SHIELD,
            ItemID.ANTIDRAGON_SHIELD_8282,
            ItemID.DRAGONFIRE_SHIELD,
            ItemID.DRAGONFIRE_SHIELD_11284,
            ItemID.DRAGONFIRE_WARD,
            ItemID.DRAGONFIRE_WARD_22003,
            ItemID.ANCIENT_WYVERN_SHIELD,
            ItemID.ANCIENT_WYVERN_SHIELD_21634
    );
}
