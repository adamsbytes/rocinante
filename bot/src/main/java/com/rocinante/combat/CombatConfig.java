package com.rocinante.combat;

import lombok.Builder;
import lombok.Value;

/**
 * Configuration for combat behavior.
 *
 * Per REQUIREMENTS.md Section 10.1, configures:
 * <ul>
 *   <li>Eat thresholds (normal and panic)</li>
 *   <li>HCIM-specific settings</li>
 *   <li>Prayer management</li>
 *   <li>Special attack usage</li>
 *   <li>Loot settings</li>
 * </ul>
 */
@Value
@Builder
public class CombatConfig {

    /**
     * Default configuration for normal accounts.
     */
    public static final CombatConfig DEFAULT = CombatConfig.builder()
            .primaryEatThreshold(0.50)
            .panicEatThreshold(0.25)
            .minimumFoodCount(2)
            .useComboEating(true)
            .comboEatProbability(0.40)
            .panicEatExtraProbability(0.10)
            .panicEatHealthRange(new double[]{0.55, 0.60})
            .useProtectionPrayers(false)
            .useLazyFlicking(false)
            .missedFlickProbability(0.03)
            .useOffensivePrayers(false)
            .prayerRestoreThreshold(0.20)
            .useSpecialAttack(false)
            .specEnergyThreshold(100)
            .lootMinValue(1000)
            .hcimMode(false)
            .hcimMinFoodCount(4)
            .hcimFleeThreshold(0.50)
            .hcimRequireRingOfLife(true)
            .hcimRequireEmergencyTeleport(true)
            .build();

    /**
     * Configuration for HCIM accounts with enhanced safety.
     */
    public static final CombatConfig HCIM_SAFE = CombatConfig.builder()
            .primaryEatThreshold(0.65)
            .panicEatThreshold(0.40)
            .minimumFoodCount(4)
            .useComboEating(true)
            .comboEatProbability(0.40)
            .panicEatExtraProbability(0.10)
            .panicEatHealthRange(new double[]{0.55, 0.60})
            .useProtectionPrayers(true)
            .useLazyFlicking(false)
            .missedFlickProbability(0.02)
            .useOffensivePrayers(false)
            .prayerRestoreThreshold(0.30)
            .useSpecialAttack(false)
            .specEnergyThreshold(100)
            .lootMinValue(5000)
            .hcimMode(true)
            .hcimMinFoodCount(4)
            .hcimFleeThreshold(0.50)
            .hcimRequireRingOfLife(true)
            .hcimRequireEmergencyTeleport(true)
            .build();

    // ========================================================================
    // Eat Settings (Section 10.1.2)
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

    /**
     * Probability of panic eating at 55-60% HP (humanization).
     * Per Section 10.6.8: 10% probability.
     */
    @Builder.Default
    double panicEatExtraProbability = 0.10;

    /**
     * Health range for extra panic eating [min, max].
     */
    @Builder.Default
    double[] panicEatHealthRange = {0.55, 0.60};

    // ========================================================================
    // Prayer Settings (Section 10.1.3)
    // ========================================================================

    /**
     * Whether to use protection prayers.
     */
    @Builder.Default
    boolean useProtectionPrayers = false;

    /**
     * Whether to use lazy flicking (activate on attack tick only).
     */
    @Builder.Default
    boolean useLazyFlicking = false;

    /**
     * Probability of missing a prayer flick (humanization).
     * Per Section 10.2.2: 2-5%.
     */
    @Builder.Default
    double missedFlickProbability = 0.03;

    /**
     * Whether to maintain offensive prayers during combat.
     */
    @Builder.Default
    boolean useOffensivePrayers = false;

    /**
     * Prayer points threshold for restoration (0.0-1.0).
     */
    @Builder.Default
    double prayerRestoreThreshold = 0.20;

    // ========================================================================
    // Special Attack Settings (Section 10.4)
    // ========================================================================

    /**
     * Whether to use special attacks.
     */
    @Builder.Default
    boolean useSpecialAttack = false;

    /**
     * Special attack energy threshold to use spec (0-100).
     */
    @Builder.Default
    int specEnergyThreshold = 100;

    // ========================================================================
    // Loot Settings (Section 10.7)
    // ========================================================================

    /**
     * Minimum GE value for auto-looting (in gp).
     */
    @Builder.Default
    int lootMinValue = 1000;

    // ========================================================================
    // HCIM Safety Settings (Section 10.1.4)
    // ========================================================================

    /**
     * Whether HCIM safety protocols are enabled.
     */
    @Builder.Default
    boolean hcimMode = false;

    /**
     * Minimum food count for HCIM before combat.
     */
    @Builder.Default
    int hcimMinFoodCount = 4;

    /**
     * Health threshold to flee for HCIM with no food (0.0-1.0).
     */
    @Builder.Default
    double hcimFleeThreshold = 0.50;

    /**
     * Whether Ring of Life is required for HCIM combat.
     */
    @Builder.Default
    boolean hcimRequireRingOfLife = true;

    /**
     * Whether an emergency teleport is required for HCIM combat.
     */
    @Builder.Default
    boolean hcimRequireEmergencyTeleport = true;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Get the effective eat threshold based on HCIM mode.
     *
     * @param isHcim whether the account is HCIM
     * @return appropriate eat threshold
     */
    public double getEffectiveEatThreshold(boolean isHcim) {
        if (isHcim && hcimMode) {
            return Math.max(primaryEatThreshold, 0.65);
        }
        return primaryEatThreshold;
    }

    /**
     * Get the effective panic threshold based on HCIM mode.
     *
     * @param isHcim whether the account is HCIM
     * @return appropriate panic threshold
     */
    public double getEffectivePanicThreshold(boolean isHcim) {
        if (isHcim && hcimMode) {
            return Math.max(panicEatThreshold, 0.40);
        }
        return panicEatThreshold;
    }

    /**
     * Get the effective minimum food count.
     *
     * @param isHcim whether the account is HCIM
     * @return minimum food required
     */
    public int getEffectiveMinFoodCount(boolean isHcim) {
        if (isHcim && hcimMode) {
            return Math.max(minimumFoodCount, hcimMinFoodCount);
        }
        return minimumFoodCount;
    }

    /**
     * Check if the health percentage is in the panic eat range.
     *
     * @param healthPercent current health (0.0-1.0)
     * @return true if in panic range
     */
    public boolean isInPanicEatRange(double healthPercent) {
        return healthPercent >= panicEatHealthRange[0] && healthPercent <= panicEatHealthRange[1];
    }
}

