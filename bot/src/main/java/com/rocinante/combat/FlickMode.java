package com.rocinante.combat;

import lombok.Getter;

/**
 * Prayer flicking intensity modes.
 *
 * Per REQUIREMENTS.md Section 10.2.2, supports three flicking intensities:
 * <ul>
 *   <li>PERFECT - Tick-perfect flicking for maximum prayer conservation</li>
 *   <li>LAZY - Activate before attack lands, deactivate after</li>
 *   <li>ALWAYS_ON - Keep prayer active during combat (no flicking)</li>
 * </ul>
 */
@Getter
public enum FlickMode {

    /**
     * Tick-perfect prayer flicking.
     * Activates prayer on the exact tick of the enemy attack animation,
     * deactivates 1 tick later.
     *
     * Maximum prayer point conservation but requires precise timing.
     * Best for ironman accounts conserving prayer potions.
     *
     * Higher risk - timing mistakes mean taking full damage.
     */
    PERFECT(
            "Tick-Perfect Flicking",
            0, // Activate on attack tick
            1, // Deactivate 1 tick after activation
            0.05 // 5% miss rate (humanization)
    ),

    /**
     * Lazy prayer flicking.
     * Activates 1 tick before attack lands, deactivates 1 tick after.
     *
     * Moderate prayer conservation with more forgiving timing.
     * Good balance between safety and resource usage.
     */
    LAZY(
            "Lazy Flicking",
            -1, // Activate 1 tick before attack
            2,  // Keep active for 2 ticks total
            0.03 // 3% miss rate
    ),

    /**
     * Keep prayer always active during combat.
     * No flicking - maximum safety but highest prayer drain.
     *
     * Recommended for:
     * - Players with plenty of prayer potions
     * - High-risk encounters where any damage is dangerous
     * - Situations where timing is difficult (multiple attackers)
     */
    ALWAYS_ON(
            "Always On",
            -999, // Activate immediately when combat starts
            -1,   // Never deactivate (until combat ends)
            0.0   // No miss rate - prayer is always on
    );

    /**
     * Human-readable name.
     */
    private final String displayName;

    /**
     * Tick offset for activation relative to incoming attack.
     * Negative = activate before, 0 = activate on attack tick, positive = after.
     */
    private final int activationTickOffset;

    /**
     * Duration to keep prayer active in ticks.
     * -1 means keep active until combat ends.
     */
    private final int activeDurationTicks;

    /**
     * Base probability of missing a flick (humanization).
     * Per REQUIREMENTS.md Section 10.2.2: 2-5%.
     */
    private final double baseMissProbability;

    FlickMode(String displayName, int activationTickOffset, int activeDurationTicks, double baseMissProbability) {
        this.displayName = displayName;
        this.activationTickOffset = activationTickOffset;
        this.activeDurationTicks = activeDurationTicks;
        this.baseMissProbability = baseMissProbability;
    }

    /**
     * Check if this mode uses flicking (vs always-on).
     *
     * @return true if flicking is active
     */
    public boolean isFlicking() {
        return this != ALWAYS_ON;
    }

    /**
     * Check if this mode is always-on (no flicking).
     *
     * @return true if prayer stays on continuously
     */
    public boolean isAlwaysOn() {
        return this == ALWAYS_ON;
    }

    /**
     * Get the tick when prayer should be activated for an attack landing at tick T.
     *
     * @param attackLandTick the tick when the attack will land
     * @return the tick to activate prayer
     */
    public int getActivationTick(int attackLandTick) {
        if (isAlwaysOn()) {
            return 0; // Activate immediately
        }
        return attackLandTick + activationTickOffset;
    }

    /**
     * Get the tick when prayer should be deactivated.
     *
     * @param activationTick the tick prayer was activated
     * @return the tick to deactivate, or -1 if should stay on
     */
    public int getDeactivationTick(int activationTick) {
        if (activeDurationTicks < 0) {
            return -1; // Don't deactivate
        }
        return activationTick + activeDurationTicks;
    }
}

