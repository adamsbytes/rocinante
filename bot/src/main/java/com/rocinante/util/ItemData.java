package com.rocinante.util;

import net.runelite.api.gameval.ItemID;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralized repository of item metadata for the bot.
 * 
 * Uses RuneLite's ItemID constants for maintainability.
 * All collections are unmodifiable for thread safety.
 * 
 * Categories:
 * - Food and healing items
 * - Antipoison/antidote potions
 * - (Future: combat potions, teleports, ammunition, etc.)
 */
public final class ItemData {

    private ItemData() {
        // Utility class - no instantiation
    }

    // ========================================================================
    // Food Items
    // ========================================================================

    /**
     * Set of all food item IDs that can be eaten to restore HP.
     */
    public static final Set<Integer> FOOD_IDS;

    /**
     * Map of food item ID to healing amount.
     * Note: Some items (Anglerfish, Saradomin brew) scale with HP level;
     * values here are approximate for high HP levels.
     */
    public static final Map<Integer, Integer> FOOD_HEALING;

    // ========================================================================
    // Antipoison Items
    // ========================================================================

    /**
     * Set of all antipoison/antidote item IDs that cure poison.
     */
    public static final Set<Integer> ANTIPOISON_IDS;

    // ========================================================================
    // Static Initialization
    // ========================================================================

    static {
        // ----- Antipoison IDs -----
        Set<Integer> antipoisonIds = new HashSet<>();
        
        // Regular antipoison (1-4 dose)
        antipoisonIds.add(2446);  // Antipoison(4)
        antipoisonIds.add(175);   // Antipoison(3)
        antipoisonIds.add(177);   // Antipoison(2)
        antipoisonIds.add(179);   // Antipoison(1)
        
        // Superantipoison (1-4 dose)
        antipoisonIds.add(2448);  // Superantipoison(4)
        antipoisonIds.add(181);   // Superantipoison(3)
        antipoisonIds.add(183);   // Superantipoison(2)
        antipoisonIds.add(185);   // Superantipoison(1)
        
        // Antidote+ (1-4 dose)
        antipoisonIds.add(5943);  // Antidote+(4)
        antipoisonIds.add(5945);  // Antidote+(3)
        antipoisonIds.add(5947);  // Antidote+(2)
        antipoisonIds.add(5949);  // Antidote+(1)
        
        // Antidote++ (1-4 dose)
        antipoisonIds.add(5952);  // Antidote++(4)
        antipoisonIds.add(5954);  // Antidote++(3)
        antipoisonIds.add(5956);  // Antidote++(2)
        antipoisonIds.add(5958);  // Antidote++(1)
        
        // Anti-venom (1-4 dose)
        antipoisonIds.add(12905); // Anti-venom(4)
        antipoisonIds.add(12907); // Anti-venom(3)
        antipoisonIds.add(12909); // Anti-venom(2)
        antipoisonIds.add(12911); // Anti-venom(1)
        
        // Anti-venom+ (1-4 dose)
        antipoisonIds.add(12913); // Anti-venom+(4)
        antipoisonIds.add(12915); // Anti-venom+(3)
        antipoisonIds.add(12917); // Anti-venom+(2)
        antipoisonIds.add(12919); // Anti-venom+(1)
        
        // Sanfew serum (cures poison and restores stats)
        antipoisonIds.add(10925); // Sanfew serum(4)
        antipoisonIds.add(10927); // Sanfew serum(3)
        antipoisonIds.add(10929); // Sanfew serum(2)
        antipoisonIds.add(10931); // Sanfew serum(1)
        
        ANTIPOISON_IDS = Collections.unmodifiableSet(antipoisonIds);

        // ----- Food IDs -----
        Set<Integer> foodIds = new HashSet<>();

        // Basic fish (low to mid tier)
        foodIds.add(ItemID.SHRIMP);              // 3 HP
        foodIds.add(ItemID.ANCHOVIES);           // 1 HP
        foodIds.add(ItemID.TROUT);               // 7 HP
        foodIds.add(ItemID.SALMON);              // 9 HP
        foodIds.add(ItemID.COD);                 // 7 HP
        foodIds.add(ItemID.PIKE);                // 8 HP
        foodIds.add(ItemID.TUNA);                // 10 HP
        foodIds.add(ItemID.BASS);                // 13 HP
        foodIds.add(ItemID.SWORDFISH);           // 14 HP
        foodIds.add(ItemID.LOBSTER);             // 12 HP
        foodIds.add(ItemID.MONKFISH);            // 16 HP
        foodIds.add(ItemID.SHARK);               // 20 HP
        foodIds.add(ItemID.MANTARAY);            // 22 HP
        foodIds.add(ItemID.SEATURTLE);           // 21 HP
        foodIds.add(ItemID.DARK_CRAB);           // 22 HP
        foodIds.add(ItemID.ANGLERFISH);          // 22 HP (can overheal)

        // Karambwan (can combo eat)
        foodIds.add(ItemID.TBWT_COOKED_KARAMBWAN); // 18 HP

        // Blighted food (wilderness only)
        foodIds.add(ItemID.BLIGHTED_MANTARAY);   // 22 HP
        foodIds.add(ItemID.BLIGHTED_ANGLERFISH); // 22 HP
        foodIds.add(ItemID.BLIGHTED_KARAMBWAN);  // 18 HP

        // Meat
        foodIds.add(ItemID.COOKED_CHICKEN);      // 3 HP
        foodIds.add(ItemID.COOKED_MEAT);         // 3 HP

        // Bread and basic food
        foodIds.add(ItemID.BREAD);               // 5 HP

        // Cakes
        foodIds.add(ItemID.CAKE);                // 4 HP per bite (12 total)
        foodIds.add(ItemID.PARTIAL_CAKE);        // 2/3 cake
        foodIds.add(ItemID.CAKE_SLICE);          // Slice of cake
        foodIds.add(ItemID.CHOCOLATE_CAKE);      // 5 HP per bite (15 total)
        foodIds.add(ItemID.PARTIAL_CHOCOLATE_CAKE);
        foodIds.add(1901);                       // Chocolate slice (no ItemID constant)

        // Pizzas
        foodIds.add(ItemID.PLAIN_PIZZA);         // 7 HP per half (14 total)
        foodIds.add(ItemID.HALF_PLAIN_PIZZA);
        foodIds.add(ItemID.MEAT_PIZZA);          // 8 HP per half (16 total)
        foodIds.add(ItemID.HALF_MEAT_PIZZA);
        foodIds.add(ItemID.ANCHOVIE_PIZZA);      // 9 HP per half (18 total)
        foodIds.add(ItemID.HALF_ANCHOVIE_PIZZA);
        foodIds.add(ItemID.PINEAPPLE_PIZZA);     // 11 HP per half (22 total)
        foodIds.add(ItemID.HALF_PINEAPPLE_PIZZA);

        // Pies
        foodIds.add(ItemID.APPLE_PIE);           // 7 HP per half (14 total)
        foodIds.add(ItemID.HALF_AN_APPLE_PIE);
        foodIds.add(ItemID.MEAT_PIE);            // 6 HP per half (12 total)
        foodIds.add(ItemID.HALF_A_MEAT_PIE);
        foodIds.add(ItemID.REDBERRY_PIE);        // 5 HP per half (10 total)
        foodIds.add(ItemID.HALF_A_REDBERRY_PIE);
        foodIds.add(ItemID.FISH_PIE);            // 6 HP per half (12 total)
        foodIds.add(ItemID.ADMIRAL_PIE);         // 8 HP per half (16 total)
        foodIds.add(ItemID.WILD_PIE);            // 11 HP per half (22 total)
        foodIds.add(ItemID.SUMMER_PIE);          // 11 HP per half (22 total) + Agility boost

        // Potato dishes (high tier)
        foodIds.add(ItemID.POTATO_BUTTER);       // 14 HP
        foodIds.add(ItemID.POTATO_CHEESE);       // 16 HP
        foodIds.add(ItemID.POTATO_EGG_TOMATO);   // 16 HP (Egg potato)
        foodIds.add(ItemID.POTATO_MUSHROOM_ONION); // 20 HP (Mushroom potato)
        foodIds.add(ItemID.POTATO_TUNA_SWEETCORN); // 22 HP (Tuna potato)

        // Wine
        foodIds.add(ItemID.JUG_WINE);            // 11 HP (reduces attack temporarily)

        // Saradomin brews (heals 15% of max HP per dose)
        foodIds.add(ItemID._4DOSEPOTIONOFSARADOMIN);
        foodIds.add(ItemID._3DOSEPOTIONOFSARADOMIN);
        foodIds.add(ItemID._2DOSEPOTIONOFSARADOMIN);
        foodIds.add(ItemID._1DOSEPOTIONOFSARADOMIN);

        FOOD_IDS = Collections.unmodifiableSet(foodIds);

        // ----- Healing Amounts -----
        Map<Integer, Integer> healing = new HashMap<>();

        // Basic fish
        healing.put(ItemID.SHRIMP, 3);
        healing.put(ItemID.ANCHOVIES, 1);
        healing.put(ItemID.TROUT, 7);
        healing.put(ItemID.SALMON, 9);
        healing.put(ItemID.COD, 7);
        healing.put(ItemID.PIKE, 8);
        healing.put(ItemID.TUNA, 10);
        healing.put(ItemID.BASS, 13);
        healing.put(ItemID.SWORDFISH, 14);
        healing.put(ItemID.LOBSTER, 12);
        healing.put(ItemID.MONKFISH, 16);
        healing.put(ItemID.SHARK, 20);
        healing.put(ItemID.MANTARAY, 22);
        healing.put(ItemID.SEATURTLE, 21);
        healing.put(ItemID.DARK_CRAB, 22);
        healing.put(ItemID.ANGLERFISH, 22);  // Can overheal, scales with HP level

        // Karambwan and blighted
        healing.put(ItemID.TBWT_COOKED_KARAMBWAN, 18);
        healing.put(ItemID.BLIGHTED_MANTARAY, 22);
        healing.put(ItemID.BLIGHTED_ANGLERFISH, 22);
        healing.put(ItemID.BLIGHTED_KARAMBWAN, 18);

        // Meat
        healing.put(ItemID.COOKED_CHICKEN, 3);
        healing.put(ItemID.COOKED_MEAT, 3);

        // Bread
        healing.put(ItemID.BREAD, 5);

        // Cakes (per bite)
        healing.put(ItemID.CAKE, 4);
        healing.put(ItemID.PARTIAL_CAKE, 4);
        healing.put(ItemID.CAKE_SLICE, 4);
        healing.put(ItemID.CHOCOLATE_CAKE, 5);
        healing.put(ItemID.PARTIAL_CHOCOLATE_CAKE, 5);
        healing.put(1901, 5);  // Chocolate slice

        // Pizzas (per half)
        healing.put(ItemID.PLAIN_PIZZA, 7);
        healing.put(ItemID.HALF_PLAIN_PIZZA, 7);
        healing.put(ItemID.MEAT_PIZZA, 8);
        healing.put(ItemID.HALF_MEAT_PIZZA, 8);
        healing.put(ItemID.ANCHOVIE_PIZZA, 9);
        healing.put(ItemID.HALF_ANCHOVIE_PIZZA, 9);
        healing.put(ItemID.PINEAPPLE_PIZZA, 11);
        healing.put(ItemID.HALF_PINEAPPLE_PIZZA, 11);

        // Pies (per half)
        healing.put(ItemID.APPLE_PIE, 7);
        healing.put(ItemID.HALF_AN_APPLE_PIE, 7);
        healing.put(ItemID.MEAT_PIE, 6);
        healing.put(ItemID.HALF_A_MEAT_PIE, 6);
        healing.put(ItemID.REDBERRY_PIE, 5);
        healing.put(ItemID.HALF_A_REDBERRY_PIE, 5);
        healing.put(ItemID.FISH_PIE, 6);
        healing.put(ItemID.ADMIRAL_PIE, 8);
        healing.put(ItemID.WILD_PIE, 11);
        healing.put(ItemID.SUMMER_PIE, 11);

        // Potatoes
        healing.put(ItemID.POTATO_BUTTER, 14);
        healing.put(ItemID.POTATO_CHEESE, 16);
        healing.put(ItemID.POTATO_EGG_TOMATO, 16);
        healing.put(ItemID.POTATO_MUSHROOM_ONION, 20);
        healing.put(ItemID.POTATO_TUNA_SWEETCORN, 22);

        // Wine (note: also reduces Attack temporarily)
        healing.put(ItemID.JUG_WINE, 11);

        // Saradomin brew (heals 15% max HP per dose, listed as approximate for 99 HP)
        healing.put(ItemID._4DOSEPOTIONOFSARADOMIN, 16);
        healing.put(ItemID._3DOSEPOTIONOFSARADOMIN, 16);
        healing.put(ItemID._2DOSEPOTIONOFSARADOMIN, 16);
        healing.put(ItemID._1DOSEPOTIONOFSARADOMIN, 16);

        FOOD_HEALING = Collections.unmodifiableMap(healing);
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if an item is food (can be eaten to restore HP).
     *
     * @param itemId the item ID to check
     * @return true if the item is food
     */
    public static boolean isFood(int itemId) {
        return FOOD_IDS.contains(itemId);
    }

    /**
     * Get the healing amount for a food item.
     *
     * @param itemId the food item ID
     * @return the healing amount, or 0 if not a known food item
     */
    public static int getHealingAmount(int itemId) {
        return FOOD_HEALING.getOrDefault(itemId, 0);
    }

    /**
     * Check if an item is an antipoison or antidote.
     *
     * @param itemId the item ID to check
     * @return true if the item cures poison
     */
    public static boolean isAntipoison(int itemId) {
        return ANTIPOISON_IDS.contains(itemId);
    }

    /**
     * Check if an item is a Karambwan (can be combo-eaten).
     *
     * @param itemId the item ID to check
     * @return true if the item is a karambwan variant
     */
    public static boolean isKarambwan(int itemId) {
        return itemId == ItemID.TBWT_COOKED_KARAMBWAN 
            || itemId == ItemID.BLIGHTED_KARAMBWAN;
    }

    /**
     * Check if an item is a Saradomin brew.
     *
     * @param itemId the item ID to check
     * @return true if the item is a Saradomin brew
     */
    public static boolean isSaradominBrew(int itemId) {
        return itemId == ItemID._4DOSEPOTIONOFSARADOMIN
            || itemId == ItemID._3DOSEPOTIONOFSARADOMIN
            || itemId == ItemID._2DOSEPOTIONOFSARADOMIN
            || itemId == ItemID._1DOSEPOTIONOFSARADOMIN;
    }
}

