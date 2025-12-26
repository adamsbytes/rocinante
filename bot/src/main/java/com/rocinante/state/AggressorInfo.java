package com.rocinante.state;

import lombok.Builder;
import lombok.Value;

/**
 * Information about an NPC that is aggressively targeting the player.
 *
 * Per REQUIREMENTS.md Section 6.2.6, tracks per-NPC aggro data including:
 * <ul>
 *   <li>NPC identity (index)</li>
 *   <li>Ticks until next attack</li>
 *   <li>Expected damage from next attack</li>
 * </ul>
 *
 * Used for pile-up detection and damage prediction in CombatState.
 */
@Value
@Builder
public class AggressorInfo {

    /**
     * The NPC's index in the client's NPC array.
     */
    int npcIndex;

    /**
     * The NPC's definition ID.
     */
    int npcId;

    /**
     * The NPC's name.
     */
    String npcName;

    /**
     * The NPC's combat level.
     */
    int combatLevel;

    /**
     * Estimated ticks until this NPC's next attack lands.
     * Based on NPC attack speed and last attack timing.
     * -1 if unknown.
     */
    int ticksUntilNextAttack;

    /**
     * Estimated maximum damage from this NPC's attack.
     * Based on NPC combat level and attack type.
     * -1 if unknown.
     */
    int expectedMaxHit;

    /**
     * The attack style this NPC uses (for prayer protection).
     * 0 = melee, 1 = ranged, 2 = magic, -1 = unknown.
     */
    int attackStyle;

    /**
     * The NPC's attack speed in game ticks.
     * -1 if unknown.
     */
    int attackSpeed;

    /**
     * Game tick when this NPC last attacked.
     * -1 if no attack observed.
     */
    int lastAttackTick;

    /**
     * Whether this NPC is currently animating an attack.
     */
    boolean isAttacking;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if this NPC's next attack is imminent (within 2 ticks).
     *
     * @return true if attack is coming soon
     */
    public boolean isAttackImminent() {
        return ticksUntilNextAttack >= 0 && ticksUntilNextAttack <= 2;
    }

    /**
     * Get the attack style as a string.
     *
     * @return attack style name
     */
    public String getAttackStyleName() {
        switch (attackStyle) {
            case 0:
                return "Melee";
            case 1:
                return "Ranged";
            case 2:
                return "Magic";
            default:
                return "Unknown";
        }
    }

    /**
     * Check if we know this NPC's attack timing.
     *
     * @return true if timing is tracked
     */
    public boolean hasAttackTiming() {
        return ticksUntilNextAttack >= 0;
    }

    /**
     * Calculate expected damage per minute from this NPC.
     * Useful for sustained combat calculations.
     *
     * @return expected damage per minute, or -1 if unknown
     */
    public double getExpectedDamagePerMinute() {
        if (expectedMaxHit < 0 || attackSpeed < 0) {
            return -1;
        }
        // Assuming average hit is half max hit
        double avgHit = expectedMaxHit / 2.0;
        // Ticks per minute = 100
        double attacksPerMinute = 100.0 / attackSpeed;
        return avgHit * attacksPerMinute;
    }

    /**
     * Get a summary string for logging.
     *
     * @return summary of this aggressor
     */
    public String getSummary() {
        return String.format("Aggressor[%s (lvl %d), nextAtk=%d ticks, maxHit=%d, style=%s]",
                npcName != null ? npcName : "NPC#" + npcIndex,
                combatLevel,
                ticksUntilNextAttack,
                expectedMaxHit,
                getAttackStyleName());
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create an AggressorInfo with minimal known data.
     *
     * @param npcIndex    the NPC index
     * @param npcId       the NPC definition ID
     * @param npcName     the NPC name
     * @param combatLevel the NPC combat level
     * @return new AggressorInfo with unknown timing
     */
    public static AggressorInfo unknown(int npcIndex, int npcId, String npcName, int combatLevel) {
        return AggressorInfo.builder()
                .npcIndex(npcIndex)
                .npcId(npcId)
                .npcName(npcName)
                .combatLevel(combatLevel)
                .ticksUntilNextAttack(-1)
                .expectedMaxHit(-1)
                .attackStyle(-1)
                .attackSpeed(-1)
                .lastAttackTick(-1)
                .isAttacking(false)
                .build();
    }

    /**
     * Estimate max hit based on combat level.
     * This is a rough approximation - actual max hits vary by NPC.
     *
     * @param combatLevel the NPC's combat level
     * @return estimated max hit
     */
    public static int estimateMaxHit(int combatLevel) {
        // Very rough estimation: max hit ≈ combat level / 3 for most regular NPCs
        // Bosses and special NPCs will have higher max hits
        return Math.max(1, combatLevel / 3);
    }
}

