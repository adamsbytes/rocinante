package com.rocinante.combat;

import lombok.Builder;
import lombok.Value;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Configuration for special attack usage during combat.
 *
 * Per REQUIREMENTS.md Section 10.4:
 * <ul>
 *   <li>Threshold-based: use at X% energy</li>
 *   <li>Target-based: use on specific enemy types (e.g., boss only)</li>
 *   <li>Stacking: for specs that stack (DDS), use multiple times</li>
 *   <li>Weapon switching: switch to spec weapon, use spec, switch back</li>
 * </ul>
 */
@Value
@Builder
public class SpecialAttackConfig {

    /**
     * Default configuration - use spec at 100% energy.
     */
    public static final SpecialAttackConfig DEFAULT = SpecialAttackConfig.builder()
            .enabled(true)
            .energyThreshold(100)
            .useOnlyOnBosses(false)
            .stackSpecs(false)
            .maxStackCount(1)
            .useSpecWeapon(false)
            .specWeaponItemId(-1)
            .switchBackAfterSpec(true)
            .specDelayMs(100)
            .specDelayVarianceMs(30)
            .build();

    /**
     * Configuration for DPS specs - use as soon as possible.
     */
    public static final SpecialAttackConfig DPS_MODE = SpecialAttackConfig.builder()
            .enabled(true)
            .energyThreshold(25) // Use as soon as we have enough
            .useOnlyOnBosses(false)
            .stackSpecs(true)
            .maxStackCount(4)
            .useSpecWeapon(true)
            .specWeaponItemId(-1) // Will auto-detect
            .switchBackAfterSpec(true)
            .specDelayMs(100)
            .specDelayVarianceMs(30)
            .build();

    /**
     * Configuration for boss utility specs (DWH, BGS).
     */
    public static final SpecialAttackConfig BOSS_UTILITY = SpecialAttackConfig.builder()
            .enabled(true)
            .energyThreshold(50)
            .useOnlyOnBosses(true)
            .stackSpecs(false)
            .maxStackCount(1)
            .useSpecWeapon(true)
            .specWeaponItemId(-1)
            .switchBackAfterSpec(true)
            .specDelayMs(150)
            .specDelayVarianceMs(50)
            .build();

    // ========================================================================
    // Basic Settings
    // ========================================================================

    /**
     * Whether special attacks are enabled.
     */
    @Builder.Default
    boolean enabled = true;

    /**
     * Special attack energy threshold to use spec (0-100).
     * Spec will be used when energy reaches or exceeds this value.
     */
    @Builder.Default
    int energyThreshold = 100;

    // ========================================================================
    // Target Filtering
    // ========================================================================

    /**
     * Whether to only use special attack on bosses.
     * Useful for saving spec for important targets.
     */
    @Builder.Default
    boolean useOnlyOnBosses = false;

    /**
     * Specific NPC IDs to use special attack on.
     * If empty, will use spec on all targets (respecting useOnlyOnBosses).
     */
    @Nullable
    Set<Integer> targetNpcIds;

    /**
     * Minimum NPC combat level to use spec on.
     * 0 means no minimum.
     */
    @Builder.Default
    int minTargetCombatLevel = 0;

    // ========================================================================
    // Stacking
    // ========================================================================

    /**
     * Whether to stack special attacks (use multiple in succession).
     * Per Section 10.6.3: for specs that stack (DDS), use multiple times.
     */
    @Builder.Default
    boolean stackSpecs = false;

    /**
     * Maximum number of specs to stack.
     * Limited by energy and weapon capabilities.
     */
    @Builder.Default
    int maxStackCount = 1;

    // ========================================================================
    // Weapon Switching
    // ========================================================================

    /**
     * Whether to switch to a specific spec weapon.
     */
    @Builder.Default
    boolean useSpecWeapon = false;

    /**
     * Item ID of spec weapon to switch to.
     * -1 means auto-detect from inventory.
     */
    @Builder.Default
    int specWeaponItemId = -1;

    /**
     * Whether to switch back to main weapon after speccing.
     */
    @Builder.Default
    boolean switchBackAfterSpec = true;

    // ========================================================================
    // Timing
    // ========================================================================

    /**
     * Base delay between weapon switch and spec (milliseconds).
     * Per Section 10.6.3: Weapon switches take 1 tick.
     */
    @Builder.Default
    int specDelayMs = 100;

    /**
     * Variance in spec delay (humanization).
     * Per Section 10.6.3: Add human delay variance (100±30ms).
     */
    @Builder.Default
    int specDelayVarianceMs = 30;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if should use spec on a specific target.
     *
     * @param npcId the NPC ID
     * @param combatLevel the NPC combat level
     * @param isBoss whether the NPC is considered a boss
     * @return true if should spec this target
     */
    public boolean shouldSpecTarget(int npcId, int combatLevel, boolean isBoss) {
        if (!enabled) {
            return false;
        }

        // Check boss-only filter
        if (useOnlyOnBosses && !isBoss) {
            return false;
        }

        // Check combat level filter
        if (combatLevel < minTargetCombatLevel) {
            return false;
        }

        // Check specific NPC filter
        if (targetNpcIds != null && !targetNpcIds.isEmpty()) {
            return targetNpcIds.contains(npcId);
        }

        return true;
    }

    /**
     * Calculate the number of specs to use given current energy.
     *
     * @param currentEnergy current special attack energy (0-100)
     * @param specCost cost per spec
     * @return number of specs to use
     */
    public int calculateSpecCount(int currentEnergy, int specCost) {
        if (!enabled || currentEnergy < energyThreshold) {
            return 0;
        }

        if (!stackSpecs) {
            return 1;
        }

        int maxFromEnergy = currentEnergy / specCost;
        return Math.min(maxFromEnergy, maxStackCount);
    }

    /**
     * Get the delay to use before speccing, with variance.
     *
     * @param random a Random instance
     * @return delay in milliseconds
     */
    public int getSpecDelayWithVariance(java.util.Random random) {
        if (specDelayVarianceMs <= 0) {
            return specDelayMs;
        }
        int variance = random.nextInt(specDelayVarianceMs * 2 + 1) - specDelayVarianceMs;
        return Math.max(0, specDelayMs + variance);
    }
}

