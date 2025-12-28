package com.rocinante.navigation;

import com.rocinante.behavior.AccountType;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;

/**
 * Models resource awareness for pathfinding cost calculations.
 *
 * <p>Per REQUIREMENTS.md, the navigation system should consider situational resource
 * availability when calculating path costs. Different account types have different
 * resource constraints:
 *
 * <ul>
 *   <li><b>HCIM</b>: Never use law runes (too risky to obtain safely), prefer fairy rings/spirit trees</li>
 *   <li><b>Ironman</b>: Timid about law rune teleports (costly to obtain), prefer alternatives</li>
 *   <li><b>Normal (broke)</b>: Prefer low-cost/free options, avoid expensive fares</li>
 *   <li><b>Normal (rich)</b>: Always prefer speed, gold is not a constraint</li>
 * </ul>
 *
 * <p>This class provides cost multipliers and thresholds that the WebWalker uses
 * to adjust edge costs based on current resource state.
 */
@Slf4j
@Getter
@Builder
public class ResourceAwareness {

    // ========================================================================
    // Item IDs
    // ========================================================================

    /** Law rune item ID. */
    public static final int LAW_RUNE_ID = 563;

    /** Coins item ID. */
    public static final int COINS_ID = 995;

    // ========================================================================
    // Thresholds
    // ========================================================================

    /** Law runes considered "scarce" for ironmen. */
    private static final int IRONMAN_LAW_RUNE_SCARCE_THRESHOLD = 50;

    /** Law runes considered "scarce" for normal accounts. */
    private static final int NORMAL_LAW_RUNE_SCARCE_THRESHOLD = 20;

    /** Gold considered "broke" for paying fares. */
    private static final int BROKE_GOLD_THRESHOLD = 10000;

    /** Gold considered "rich" (no cost concerns). */
    private static final int RICH_GOLD_THRESHOLD = 100000;

    // ========================================================================
    // State
    // ========================================================================

    /** Current account type. */
    private final AccountType accountType;

    /** Current law rune count. */
    private final int lawRuneCount;

    /** Current gold amount. */
    private final int goldAmount;

    /** Number of available jewelry charges (combined from all teleport jewelry). */
    private final int jewelryCharges;

    /** Whether fairy ring access is available. */
    private final boolean hasFairyRingAccess;

    /** Whether spirit tree access is available. */
    private final boolean hasSpiritTreeAccess;

    // ========================================================================
    // Teleport Jewelry Item IDs and Charges
    // ========================================================================

    /**
     * Ring of Dueling variants (8 charges max).
     * Maps item ID -> remaining charges.
     */
    private static final java.util.Map<Integer, Integer> RING_OF_DUELING_CHARGES = java.util.Map.of(
        ItemID.RING_OF_DUELING8, 8,
        ItemID.RING_OF_DUELING7, 7,
        ItemID.RING_OF_DUELING6, 6,
        ItemID.RING_OF_DUELING5, 5,
        ItemID.RING_OF_DUELING4, 4,
        ItemID.RING_OF_DUELING3, 3,
        ItemID.RING_OF_DUELING2, 2,
        ItemID.RING_OF_DUELING1, 1
    );

    /**
     * Amulet of Glory variants (6 charges max).
     * Includes both regular and trimmed variants.
     */
    private static final java.util.Map<Integer, Integer> AMULET_OF_GLORY_CHARGES = java.util.Map.ofEntries(
        java.util.Map.entry(ItemID.AMULET_OF_GLORY6, 6),
        java.util.Map.entry(ItemID.AMULET_OF_GLORY5, 5),
        java.util.Map.entry(ItemID.AMULET_OF_GLORY4, 4),
        java.util.Map.entry(ItemID.AMULET_OF_GLORY3, 3),
        java.util.Map.entry(ItemID.AMULET_OF_GLORY2, 2),
        java.util.Map.entry(ItemID.AMULET_OF_GLORY1, 1),
        // Trimmed variants (treasure trail)
        java.util.Map.entry(ItemID.AMULET_OF_GLORY_T6, 6),
        java.util.Map.entry(ItemID.AMULET_OF_GLORY_T5, 5),
        java.util.Map.entry(ItemID.AMULET_OF_GLORY_T4, 4),
        java.util.Map.entry(ItemID.AMULET_OF_GLORY_T3, 3),
        java.util.Map.entry(ItemID.AMULET_OF_GLORY_T2, 2),
        java.util.Map.entry(ItemID.AMULET_OF_GLORY_T1, 1)
    );

    /**
     * Ring of Wealth variants (5 charges max).
     * Includes both regular and imbued variants.
     */
    private static final java.util.Map<Integer, Integer> RING_OF_WEALTH_CHARGES = java.util.Map.of(
        ItemID.RING_OF_WEALTH_5, 5,
        ItemID.RING_OF_WEALTH_4, 4,
        ItemID.RING_OF_WEALTH_3, 3,
        ItemID.RING_OF_WEALTH_2, 2,
        ItemID.RING_OF_WEALTH_1, 1,
        // Imbued variants
        ItemID.RING_OF_WEALTH_I5, 5,
        ItemID.RING_OF_WEALTH_I4, 4,
        ItemID.RING_OF_WEALTH_I3, 3
    );

    /**
     * Games Necklace variants (8 charges max).
     */
    private static final java.util.Map<Integer, Integer> GAMES_NECKLACE_CHARGES = java.util.Map.of(
        ItemID.GAMES_NECKLACE8, 8,
        ItemID.GAMES_NECKLACE7, 7,
        ItemID.GAMES_NECKLACE6, 6,
        ItemID.GAMES_NECKLACE5, 5,
        ItemID.GAMES_NECKLACE4, 4,
        ItemID.GAMES_NECKLACE3, 3,
        ItemID.GAMES_NECKLACE2, 2,
        ItemID.GAMES_NECKLACE1, 1
    );

    /**
     * Combat Bracelet variants (6 charges max).
     */
    private static final java.util.Map<Integer, Integer> COMBAT_BRACELET_CHARGES = java.util.Map.of(
        ItemID.COMBAT_BRACELET6, 6,
        ItemID.COMBAT_BRACELET5, 5,
        ItemID.COMBAT_BRACELET4, 4,
        ItemID.COMBAT_BRACELET3, 3,
        ItemID.COMBAT_BRACELET2, 2,
        ItemID.COMBAT_BRACELET1, 1
    );

    /**
     * Skills Necklace variants (6 charges max).
     */
    private static final java.util.Map<Integer, Integer> SKILLS_NECKLACE_CHARGES = java.util.Map.of(
        ItemID.SKILLS_NECKLACE6, 6,
        ItemID.SKILLS_NECKLACE5, 5,
        ItemID.SKILLS_NECKLACE4, 4,
        ItemID.SKILLS_NECKLACE3, 3,
        ItemID.SKILLS_NECKLACE2, 2,
        ItemID.SKILLS_NECKLACE1, 1
    );

    /**
     * All teleport jewelry charge maps combined for easy iteration.
     */
    @SuppressWarnings("unchecked")
    private static final java.util.List<java.util.Map<Integer, Integer>> ALL_JEWELRY_CHARGES = java.util.List.of(
        RING_OF_DUELING_CHARGES,
        AMULET_OF_GLORY_CHARGES,
        RING_OF_WEALTH_CHARGES,
        GAMES_NECKLACE_CHARGES,
        COMBAT_BRACELET_CHARGES,
        SKILLS_NECKLACE_CHARGES
    );

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create ResourceAwareness from current game state.
     *
     * @param accountType the account type
     * @param inventory the player's inventory state
     * @param hasFairyRing whether player has fairy ring access
     * @param hasSpiritTree whether player has spirit tree access
     * @return new ResourceAwareness instance
     */
    public static ResourceAwareness fromGameState(
            AccountType accountType,
            InventoryState inventory,
            boolean hasFairyRing,
            boolean hasSpiritTree) {
        
        return fromGameState(accountType, inventory, null, hasFairyRing, hasSpiritTree);
    }

    /**
     * Create ResourceAwareness from current game state including equipment.
     *
     * @param accountType the account type
     * @param inventory the player's inventory state
     * @param equipment the player's equipment state (nullable)
     * @param hasFairyRing whether player has fairy ring access
     * @param hasSpiritTree whether player has spirit tree access
     * @return new ResourceAwareness instance
     */
    public static ResourceAwareness fromGameState(
            AccountType accountType,
            InventoryState inventory,
            EquipmentState equipment,
            boolean hasFairyRing,
            boolean hasSpiritTree) {
        
        int totalCharges = calculateTotalJewelryCharges(inventory, equipment);
        
        return ResourceAwareness.builder()
                .accountType(accountType)
                .lawRuneCount(inventory.countItem(LAW_RUNE_ID))
                .goldAmount(inventory.countItem(COINS_ID))
                .jewelryCharges(totalCharges)
                .hasFairyRingAccess(hasFairyRing)
                .hasSpiritTreeAccess(hasSpiritTree)
                .build();
    }

    /**
     * Calculate total jewelry charges from inventory and equipped items.
     *
     * Checks all teleport jewelry types:
     * - Ring of Dueling (8 charges, teleports to Duel Arena, Clan Wars, etc.)
     * - Amulet of Glory (6 charges, teleports to Edgeville, Karamja, Draynor, Al Kharid)
     * - Ring of Wealth (5 charges, teleports to GE, Miscellania, Falador Park)
     * - Games Necklace (8 charges, teleports to minigames)
     * - Combat Bracelet (6 charges, teleports to combat locations)
     * - Skills Necklace (6 charges, teleports to skill locations)
     *
     * @param inventory the player's inventory
     * @param equipment the player's equipment (nullable)
     * @return total number of teleport charges available
     */
    private static int calculateTotalJewelryCharges(InventoryState inventory, EquipmentState equipment) {
        int totalCharges = 0;

        // Check inventory for all jewelry types
        for (java.util.Map<Integer, Integer> chargeMap : ALL_JEWELRY_CHARGES) {
            totalCharges += calculateChargesFromMap(inventory, chargeMap);
        }

        // Check equipped items if equipment state is available
        if (equipment != null) {
            totalCharges += calculateEquippedJewelryCharges(equipment);
        }

        return totalCharges;
    }

    /**
     * Calculate charges from a single jewelry type in inventory.
     *
     * @param inventory the inventory to check
     * @param chargeMap map of item ID -> charges for this jewelry type
     * @return total charges for this jewelry type in inventory
     */
    private static int calculateChargesFromMap(InventoryState inventory, java.util.Map<Integer, Integer> chargeMap) {
        int charges = 0;
        for (java.util.Map.Entry<Integer, Integer> entry : chargeMap.entrySet()) {
            int itemId = entry.getKey();
            int chargesPerItem = entry.getValue();
            int count = inventory.countItem(itemId);
            charges += count * chargesPerItem;
        }
        return charges;
    }

    /**
     * Calculate jewelry charges from equipped items.
     * Checks ring, amulet, and gloves slots.
     *
     * @param equipment the equipment state
     * @return total charges from equipped jewelry
     */
    private static int calculateEquippedJewelryCharges(EquipmentState equipment) {
        int charges = 0;

        // Check ring slot (Ring of Dueling, Ring of Wealth)
        int ringId = equipment.getRing().map(item -> item.getId()).orElse(-1);
        charges += getChargesForItem(ringId);

        // Check amulet slot (Amulet of Glory, Games Necklace, Skills Necklace)
        int amuletId = equipment.getAmulet().map(item -> item.getId()).orElse(-1);
        charges += getChargesForItem(amuletId);

        // Check gloves slot (Combat Bracelet)
        int glovesId = equipment.getGloves().map(item -> item.getId()).orElse(-1);
        charges += getChargesForItem(glovesId);

        return charges;
    }

    /**
     * Get the number of charges for a specific item ID.
     *
     * @param itemId the item ID to check
     * @return number of charges, or 0 if not teleport jewelry
     */
    private static int getChargesForItem(int itemId) {
        if (itemId < 0) {
            return 0;
        }
        for (java.util.Map<Integer, Integer> chargeMap : ALL_JEWELRY_CHARGES) {
            Integer charges = chargeMap.get(itemId);
            if (charges != null) {
                return charges;
            }
        }
        return 0;
    }

    /**
     * Create a default ResourceAwareness for testing or when state is unavailable.
     *
     * @param accountType the account type
     * @return new ResourceAwareness with default values
     */
    public static ResourceAwareness forAccountType(AccountType accountType) {
        return ResourceAwareness.builder()
                .accountType(accountType)
                .lawRuneCount(100)
                .goldAmount(50000)
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();
    }

    // ========================================================================
    // Cost Calculation Methods
    // ========================================================================

    /**
     * Get cost multiplier for law rune consumption.
     *
     * <p>HCIM: Effectively infinite (never use), returns 100x multiplier
     * <p>Ironman with scarce runes: 5x multiplier
     * <p>Ironman with plenty: 2x multiplier
     * <p>Normal account: 1x (no penalty)
     *
     * @return cost multiplier for law rune usage
     */
    public double getLawRuneCostMultiplier() {
        if (accountType.isHardcore()) {
            // HCIM should never use law runes - too risky to farm
            return 100.0;
        }

        if (accountType.isIronman()) {
            // Ironmen should be conservative with law runes
            if (lawRuneCount < IRONMAN_LAW_RUNE_SCARCE_THRESHOLD) {
                return 5.0; // Heavy penalty when scarce
            }
            return 2.0; // Moderate penalty even when plentiful
        }

        // Normal accounts - only penalize if very low
        if (lawRuneCount < NORMAL_LAW_RUNE_SCARCE_THRESHOLD) {
            return 1.5;
        }
        return 1.0;
    }

    /**
     * Get cost multiplier for gold expenditure.
     *
     * <p>This affects charter ships, magic carpets, toll gates, etc.
     *
     * @param goldCost the gold cost of the travel option
     * @return cost multiplier for gold expenditure
     */
    public double getGoldCostMultiplier(int goldCost) {
        if (accountType.isIronman()) {
            // Ironmen have limited gold income - more conservative
            if (goldAmount < goldCost * 2) {
                return 3.0; // Can barely afford it
            }
            if (goldAmount < goldCost * 10) {
                return 1.5; // Affordable but noticeable
            }
            return 1.0;
        }

        // Normal accounts
        if (goldAmount < goldCost) {
            return 100.0; // Can't afford it
        }
        if (goldAmount < BROKE_GOLD_THRESHOLD) {
            return 2.0; // Broke - prefer free options
        }
        if (goldAmount < RICH_GOLD_THRESHOLD) {
            return 1.2; // Moderate funds
        }
        return 1.0; // Rich - gold is no concern
    }

    /**
     * Check if free travel methods should be strongly preferred.
     *
     * <p>Returns true if:
     * <ul>
     *   <li>Account is HCIM (always prefer safe/free)</li>
     *   <li>Account is ironman with scarce resources</li>
     *   <li>Account is normal but broke</li>
     * </ul>
     *
     * @return true if free travel should be strongly preferred
     */
    public boolean preferFreeTravelMethods() {
        if (accountType.isHardcore()) {
            return true;
        }
        if (accountType.isIronman() && lawRuneCount < IRONMAN_LAW_RUNE_SCARCE_THRESHOLD) {
            return true;
        }
        return goldAmount < BROKE_GOLD_THRESHOLD;
    }

    /**
     * Get bonus for using fairy rings (negative cost = incentive).
     *
     * <p>Fairy rings are free and don't consume runes, making them ideal for
     * resource-conscious accounts.
     *
     * @return tick bonus (negative value) for fairy ring usage
     */
    public int getFairyRingBonus() {
        if (!hasFairyRingAccess) {
            return 0;
        }
        if (accountType.isHardcore() || accountType.isIronman()) {
            return -10; // Strong incentive for ironmen
        }
        if (preferFreeTravelMethods()) {
            return -5; // Moderate incentive when broke
        }
        return 0;
    }

    /**
     * Get bonus for using spirit trees (negative cost = incentive).
     *
     * @return tick bonus (negative value) for spirit tree usage
     */
    public int getSpiritTreeBonus() {
        if (!hasSpiritTreeAccess) {
            return 0;
        }
        if (accountType.isHardcore() || accountType.isIronman()) {
            return -10;
        }
        if (preferFreeTravelMethods()) {
            return -5;
        }
        return 0;
    }

    /**
     * Calculate adjusted cost for teleport spell usage.
     *
     * @param baseCost the base cost in ticks
     * @param lawRunesRequired number of law runes consumed
     * @return adjusted cost
     */
    public int adjustTeleportCost(int baseCost, int lawRunesRequired) {
        if (lawRunesRequired <= 0) {
            return baseCost;
        }

        double multiplier = getLawRuneCostMultiplier();
        int lawPenalty = (int) (lawRunesRequired * 10 * (multiplier - 1));
        return baseCost + lawPenalty;
    }

    /**
     * Calculate adjusted cost for gold-based travel.
     *
     * @param baseCost the base cost in ticks
     * @param goldRequired gold required for the journey
     * @return adjusted cost
     */
    public int adjustGoldTravelCost(int baseCost, int goldRequired) {
        if (goldRequired <= 0) {
            return baseCost;
        }

        double multiplier = getGoldCostMultiplier(goldRequired);
        int goldPenalty = (int) (goldRequired / 100 * (multiplier - 1));
        return baseCost + goldPenalty;
    }

    /**
     * Log current resource awareness state for debugging.
     */
    public void logState() {
        log.debug("ResourceAwareness: type={}, lawRunes={}, gold={}, jewelry={}, fairy={}, spirit={}",
                accountType, lawRuneCount, goldAmount, jewelryCharges,
                hasFairyRingAccess, hasSpiritTreeAccess);
        log.debug("  lawMultiplier={}, preferFree={}", 
                getLawRuneCostMultiplier(), preferFreeTravelMethods());
    }
}

