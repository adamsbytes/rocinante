package com.rocinante.state;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable snapshot of the player's poison/venom status.
 *
 * Per REQUIREMENTS.md Section 6.2.6 and 10.6.4, tracks:
 * <ul>
 *   <li>Poison type (none, poison, or venom)</li>
 *   <li>Current damage level</li>
 *   <li>Timing for next damage tick</li>
 *   <li>Damage prediction</li>
 * </ul>
 *
 * <p>Poison mechanics:
 * <ul>
 *   <li>Poison: Initial 6 HP, decreases by 1 each hit, hits every 30 ticks (18 seconds)</li>
 *   <li>Venom: Initial 6 HP, increases by 2 each hit (max 20), hits every 30 ticks</li>
 * </ul>
 */
@Value
@Builder
public class PoisonState {

    /**
     * Ticks between poison/venom damage hits (18 seconds).
     */
    public static final int POISON_TICK_INTERVAL = 30;

    /**
     * Minimum poison damage.
     */
    public static final int MIN_POISON_DAMAGE = 1;

    /**
     * Initial venom damage.
     */
    public static final int INITIAL_VENOM_DAMAGE = 6;

    /**
     * Maximum venom damage.
     */
    public static final int MAX_VENOM_DAMAGE = 20;

    /**
     * Venom damage increment per hit.
     */
    public static final int VENOM_DAMAGE_INCREMENT = 2;

    /**
     * Empty/no poison state.
     */
    public static final PoisonState NONE = PoisonState.builder()
            .type(PoisonType.NONE)
            .currentDamage(0)
            .lastPoisonTick(-1)
            .nextDamageTick(-1)
            .rawVarpValue(0)
            .build();

    /**
     * The type of poison effect.
     */
    PoisonType type;

    /**
     * The current damage value that will be dealt on next tick.
     * For poison: 1-6 (decreasing)
     * For venom: 6-20 (increasing by 2 each hit)
     */
    int currentDamage;

    /**
     * Game tick when the last poison damage was taken.
     * -1 if not poisoned or no damage yet.
     */
    int lastPoisonTick;

    /**
     * Predicted game tick when next poison damage will occur.
     * -1 if not poisoned.
     */
    int nextDamageTick;

    /**
     * Raw VarPlayer POISON value from the client.
     * Positive = poison damage, negative = venom damage indicator.
     */
    int rawVarpValue;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if the player is poisoned (any type).
     *
     * @return true if poisoned or venomed
     */
    public boolean isPoisoned() {
        return type == PoisonType.POISON;
    }

    /**
     * Check if the player is venomed.
     *
     * @return true if venomed
     */
    public boolean isVenomed() {
        return type == PoisonType.VENOM;
    }

    /**
     * Check if the player has any poison effect.
     *
     * @return true if any poison effect is active
     */
    public boolean hasEffect() {
        return type != PoisonType.NONE;
    }

    /**
     * Predict the next venom damage value.
     * Venom increases by 2 HP each hit, max 20.
     *
     * @return predicted next damage, or current damage if not venomed
     */
    public int predictNextVenomDamage() {
        if (type != PoisonType.VENOM) {
            return currentDamage;
        }
        return Math.min(currentDamage + VENOM_DAMAGE_INCREMENT, MAX_VENOM_DAMAGE);
    }

    /**
     * Predict the next poison damage value.
     * Poison decreases by 1 HP each hit, min 1.
     *
     * @return predicted next damage, or current damage if not poisoned
     */
    public int predictNextPoisonDamage() {
        if (type != PoisonType.POISON) {
            return currentDamage;
        }
        return Math.max(currentDamage - 1, MIN_POISON_DAMAGE);
    }

    /**
     * Calculate ticks until next poison damage.
     *
     * @param currentTick the current game tick
     * @return ticks remaining, or -1 if not poisoned
     */
    public int getTicksUntilNextDamage(int currentTick) {
        if (!hasEffect() || nextDamageTick < 0) {
            return -1;
        }
        return Math.max(0, nextDamageTick - currentTick);
    }

    /**
     * Calculate expected total poison/venom damage over a time period.
     * Useful for flee threshold calculations.
     *
     * @param ticks number of ticks to calculate damage over
     * @return total expected damage
     */
    public int calculateExpectedDamage(int ticks) {
        if (!hasEffect() || currentDamage <= 0) {
            return 0;
        }

        int totalDamage = 0;
        int hits = ticks / POISON_TICK_INTERVAL;
        int damage = currentDamage;

        for (int i = 0; i < hits; i++) {
            totalDamage += damage;
            if (type == PoisonType.VENOM) {
                damage = Math.min(damage + VENOM_DAMAGE_INCREMENT, MAX_VENOM_DAMAGE);
            } else {
                damage = Math.max(damage - 1, MIN_POISON_DAMAGE);
            }
        }

        return totalDamage;
    }

    /**
     * Calculate expected damage over 30 seconds (50 ticks).
     * Per REQUIREMENTS.md Section 10.6.7, used for flee threshold adjustment.
     *
     * @return expected damage in next 30 seconds
     */
    public int calculateExpectedDamage30Seconds() {
        return calculateExpectedDamage(50); // 30 seconds = 50 ticks
    }

    /**
     * Check if venom has escalated to critical levels (16+ damage).
     * Per REQUIREMENTS.md Section 10.6.7, HCIM should flee at this point.
     *
     * @return true if venom damage is critical
     */
    public boolean isVenomCritical() {
        return type == PoisonType.VENOM && currentDamage >= 16;
    }

    /**
     * Get a summary string for logging.
     *
     * @return summary of poison state
     */
    public String getSummary() {
        if (!hasEffect()) {
            return "PoisonState[NONE]";
        }
        return String.format("PoisonState[%s, dmg=%d, nextTick=%d]",
                type, currentDamage, nextDamageTick);
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a PoisonState from the raw VarPlayer POISON value.
     *
     * @param varpValue   the raw varp value (positive = poison, negative = venom)
     * @param currentTick the current game tick
     * @return new PoisonState
     */
    public static PoisonState fromVarpValue(int varpValue, int currentTick) {
        if (varpValue == 0) {
            return NONE;
        }

        PoisonType poisonType;
        int damage;

        if (varpValue > 0) {
            // Poison: value indicates damage (1-6, typically scaled)
            poisonType = PoisonType.POISON;
            // The varp value is scaled by 5, so divide to get actual damage
            damage = Math.max(1, (varpValue + 4) / 5);
        } else {
            // Venom: negative values
            poisonType = PoisonType.VENOM;
            // Venom varp is negative, damage is calculated from the absolute value
            // Starting at 6, increasing by 2 each hit
            int absValue = Math.abs(varpValue);
            // Calculate damage based on progression
            damage = INITIAL_VENOM_DAMAGE + ((absValue - 1) / 5) * VENOM_DAMAGE_INCREMENT;
            damage = Math.min(damage, MAX_VENOM_DAMAGE);
        }

        return PoisonState.builder()
                .type(poisonType)
                .currentDamage(damage)
                .lastPoisonTick(-1) // Would need tracking to know this
                .nextDamageTick(currentTick + POISON_TICK_INTERVAL)
                .rawVarpValue(varpValue)
                .build();
    }
}

