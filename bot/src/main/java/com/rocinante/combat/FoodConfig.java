package com.rocinante.combat;

import lombok.Builder;
import lombok.Value;

/**
 * Configuration for food management during combat.
 *
 * Per REQUIREMENTS.md Section 10.1.2:
 * <ul>
 *   <li>Primary threshold: 50% HP (65% HCIM)</li>
 *   <li>Panic threshold: 25% HP (40% HCIM)</li>
 *   <li>Combo eating support (food + karambwan)</li>
 *   <li>Saradomin brew pairing with restores</li>
 * </ul>
 */
@Value
@Builder
public class FoodConfig {

    /**
     * Default configuration for normal accounts.
     */
    public static final FoodConfig DEFAULT = FoodConfig.builder()
            .primaryEatThreshold(0.50)
            .panicEatThreshold(0.25)
            .minimumFoodCount(2)
            .useComboEating(true)
            .comboEatProbability(0.40)
            .panicEatExtraProbability(0.10)
            .panicEatHealthMin(0.55)
            .panicEatHealthMax(0.60)
            .brewRestoreRatio(3)
            .useOptimalFoodSelection(true)
            .build();

    /**
     * Configuration for HCIM accounts with enhanced safety.
     */
    public static final FoodConfig HCIM_SAFE = FoodConfig.builder()
            .primaryEatThreshold(0.65)
            .panicEatThreshold(0.40)
            .minimumFoodCount(4)
            .useComboEating(true)
            .comboEatProbability(0.60)
            .panicEatExtraProbability(0.15)
            .panicEatHealthMin(0.60)
            .panicEatHealthMax(0.70)
            .brewRestoreRatio(3)
            .useOptimalFoodSelection(true)
            .preEatBeforeEngaging(true)
            .preEatThreshold(0.70)
            .build();

    // ========================================================================
    // Eat Thresholds
    // ========================================================================

    /**
     * Primary health threshold for eating (0.0-1.0).
     * Default: 50% for normal, 65% for HCIM.
     */
    @Builder.Default
    double primaryEatThreshold = 0.50;

    /**
     * Panic health threshold - eat immediately even mid-action (0.0-1.0).
     * Default: 25% for normal, 40% for HCIM.
     */
    @Builder.Default
    double panicEatThreshold = 0.25;

    /**
     * Minimum food items to maintain before aborting combat.
     */
    @Builder.Default
    int minimumFoodCount = 2;

    // ========================================================================
    // Combo Eating
    // ========================================================================

    /**
     * Whether to use combo eating (food + karambwan).
     */
    @Builder.Default
    boolean useComboEating = true;

    /**
     * Probability of combo eating when HP is critical (0.0-1.0).
     * Per Section 10.6.8: 60% time eat separately, 40% combo eat.
     */
    @Builder.Default
    double comboEatProbability = 0.40;

    // ========================================================================
    // Humanization
    // ========================================================================

    /**
     * Probability of panic eating in the extra range (humanization).
     * Per Section 10.6.8: 10% probability.
     */
    @Builder.Default
    double panicEatExtraProbability = 0.10;

    /**
     * Minimum health for extra panic eating range.
     */
    @Builder.Default
    double panicEatHealthMin = 0.55;

    /**
     * Maximum health for extra panic eating range.
     */
    @Builder.Default
    double panicEatHealthMax = 0.60;

    // ========================================================================
    // Saradomin Brew Management
    // ========================================================================

    /**
     * Number of brew sips before requiring a restore.
     * Standard ratio is 3 brew sips to 1 restore sip.
     */
    @Builder.Default
    int brewRestoreRatio = 3;

    // ========================================================================
    // Food Selection
    // ========================================================================

    /**
     * Whether to select optimal food based on damage taken.
     * When true, selects food that minimizes wasted healing.
     */
    @Builder.Default
    boolean useOptimalFoodSelection = true;

    // ========================================================================
    // HCIM-Specific
    // ========================================================================

    /**
     * Whether to pre-eat before engaging new targets.
     * Per Section 10.1.2: HCIM pre-eat if HP < 70%.
     */
    @Builder.Default
    boolean preEatBeforeEngaging = false;

    /**
     * Health threshold for pre-eating before combat.
     */
    @Builder.Default
    double preEatThreshold = 0.70;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if the health percentage is in the extra panic eat range.
     *
     * @param healthPercent current health (0.0-1.0)
     * @return true if in panic range
     */
    public boolean isInPanicEatRange(double healthPercent) {
        return healthPercent >= panicEatHealthMin && healthPercent <= panicEatHealthMax;
    }

    /**
     * Get effective eat threshold, applying HCIM minimums if needed.
     *
     * @param isHcim whether account is HCIM
     * @return effective threshold
     */
    public double getEffectiveEatThreshold(boolean isHcim) {
        if (isHcim) {
            return Math.max(primaryEatThreshold, 0.65);
        }
        return primaryEatThreshold;
    }

    /**
     * Get effective panic threshold, applying HCIM minimums if needed.
     *
     * @param isHcim whether account is HCIM
     * @return effective threshold
     */
    public double getEffectivePanicThreshold(boolean isHcim) {
        if (isHcim) {
            return Math.max(panicEatThreshold, 0.40);
        }
        return panicEatThreshold;
    }

    /**
     * Get effective minimum food count.
     *
     * @param isHcim whether account is HCIM
     * @return minimum food required
     */
    public int getEffectiveMinFoodCount(boolean isHcim) {
        if (isHcim) {
            return Math.max(minimumFoodCount, 4);
        }
        return minimumFoodCount;
    }
}

