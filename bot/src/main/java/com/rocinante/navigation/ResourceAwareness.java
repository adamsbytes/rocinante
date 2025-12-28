package com.rocinante.navigation;

import com.rocinante.behavior.AccountType;
import com.rocinante.state.InventoryState;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

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
        
        return ResourceAwareness.builder()
                .accountType(accountType)
                .lawRuneCount(inventory.countItem(LAW_RUNE_ID))
                .goldAmount(inventory.countItem(COINS_ID))
                .jewelryCharges(0) // TODO: Calculate from equipped/inventory jewelry
                .hasFairyRingAccess(hasFairyRing)
                .hasSpiritTreeAccess(hasSpiritTree)
                .build();
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

