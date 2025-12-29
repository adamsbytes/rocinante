package com.rocinante.state;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.Skill;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable snapshot of combat-specific state at a point in time.
 *
 * Per REQUIREMENTS.md Section 6.2.6, captures:
 * <ul>
 *   <li>Currently targeted NPC (if any)</li>
 *   <li>Incoming attack detection (animation/projectile)</li>
 *   <li>Attack style and speed of current weapon</li>
 *   <li>Special attack energy percentage</li>
 *   <li>Current boosted/drained combat stats</li>
 *   <li>Poison/Venom tracking</li>
 *   <li>Multi-combat NPC aggro tracking</li>
 * </ul>
 *
 * Uses TICK_CACHED policy for real-time combat tracking.
 */
@Value
@Builder
public class CombatState {

    /**
     * Empty combat state for when not in combat.
     */
    public static final CombatState EMPTY = CombatState.builder()
            .targetNpc(null)
            .targetPresent(false)
            .specialAttackEnergy(0)
            .currentAttackStyle(AttackStyle.UNKNOWN)
            .weaponAttackSpeed(4)
            .boostedStats(Collections.emptyMap())
            .poisonState(PoisonState.NONE)
            .aggressiveNpcs(Collections.emptyList())
            .inMultiCombat(false)
            .incomingAttackStyle(AttackStyle.UNKNOWN)
            .ticksUntilAttackLands(-1)
            .lastAttackTick(-1)
            .ticksSinceLastAttack(-1)
            .canAttack(false)
            .build();

    // ========================================================================
    // Target Information
    // ========================================================================

    /**
     * The NPC currently being targeted by the player.
     * Null if not targeting anything.
     */
    NpcSnapshot targetNpc;

    /**
     * Whether the player has a valid combat target.
     * Note: Access this via hasTarget() method, not isHasTarget().
     */
    boolean targetPresent;

    // ========================================================================
    // Weapon Information
    // ========================================================================

    /**
     * Special attack energy percentage (0-100).
     */
    int specialAttackEnergy;

    /**
     * The attack style of the currently equipped weapon.
     */
    AttackStyle currentAttackStyle;

    /**
     * Attack speed of the current weapon in game ticks.
     * Common values: 2 (fastest), 3 (fast), 4 (average), 5-6 (slow).
     */
    int weaponAttackSpeed;

    // ========================================================================
    // Combat Stats
    // ========================================================================

    /**
     * Current boosted (or drained) combat skill levels.
     * Map of Skill -> boosted level.
     */
    @Builder.Default
    Map<Skill, Integer> boostedStats = Collections.emptyMap();

    // ========================================================================
    // Status Effects
    // ========================================================================

    /**
     * Current poison/venom state.
     */
    @Builder.Default
    PoisonState poisonState = PoisonState.NONE;

    // ========================================================================
    // Aggro Tracking
    // ========================================================================

    /**
     * List of NPCs currently targeting and attacking the player.
     * Per Section 6.2.6: getAggressiveNPCs() and getNPCAttackCooldowns().
     */
    @Builder.Default
    List<AggressorInfo> aggressiveNpcs = Collections.emptyList();

    /**
     * Whether the player is in a multi-combat area.
     * Per Section 10.6.5: Varbits.MULTICOMBAT_AREA.
     */
    boolean inMultiCombat;

    // ========================================================================
    // Incoming Attack Detection
    // ========================================================================

    /**
     * The attack style of the next incoming attack (for prayer switching).
     * UNKNOWN if no incoming attack detected.
     */
    @Builder.Default
    AttackStyle incomingAttackStyle = AttackStyle.UNKNOWN;

    /**
     * Ticks until the detected incoming attack lands.
     * -1 if no incoming attack.
     */
    int ticksUntilAttackLands;

    // ========================================================================
    // Attack Timing
    // ========================================================================

    /**
     * Game tick when the player last attacked.
     * -1 if no attack has been made this session.
     */
    int lastAttackTick;

    /**
     * Ticks since the player's last attack.
     * -1 if no attack timing data.
     */
    int ticksSinceLastAttack;

    /**
     * Whether the player can currently attack (attack cooldown elapsed).
     */
    boolean canAttack;

    // ========================================================================
    // Target Convenience Method
    // ========================================================================

    /**
     * Check if the player has a valid combat target.
     * This is a convenience method for the targetPresent field.
     *
     * @return true if a target is present
     */
    public boolean hasTarget() {
        return targetPresent;
    }

    // ========================================================================
    // Convenience Methods - Aggro
    // ========================================================================

    /**
     * Get the count of NPCs currently targeting the player.
     * Per REQUIREMENTS.md Section 6.2.6: getAggressorCount().
     *
     * @return number of aggressive NPCs
     */
    public int getAggressorCount() {
        return aggressiveNpcs.size();
    }

    /**
     * Check if multiple NPCs are attacking (pile-up detection).
     * Per Section 10.6.5: HCIM flee threshold is 2+ NPCs.
     *
     * @return true if 2 or more NPCs are attacking
     */
    public boolean isPiledUp() {
        return aggressiveNpcs.size() >= 2;
    }

    /**
     * Check if being attacked (any NPCs targeting player).
     *
     * @return true if at least one NPC is attacking
     */
    public boolean isBeingAttacked() {
        return !aggressiveNpcs.isEmpty();
    }

    /**
     * Get the aggressor with the highest expected damage.
     *
     * @return Optional containing the most dangerous aggressor
     */
    public Optional<AggressorInfo> getMostDangerousAggressor() {
        return aggressiveNpcs.stream()
                .max((a, b) -> Integer.compare(a.getExpectedMaxHit(), b.getExpectedMaxHit()));
    }

    /**
     * Get the aggressor with the soonest attack.
     *
     * @return Optional containing the aggressor attacking soonest
     */
    public Optional<AggressorInfo> getNextAttacker() {
        return aggressiveNpcs.stream()
                .filter(a -> a.getTicksUntilNextAttack() >= 0)
                .min((a, b) -> Integer.compare(a.getTicksUntilNextAttack(), b.getTicksUntilNextAttack()));
    }

    /**
     * Calculate total expected damage from all aggressors in the next tick.
     *
     * @return expected damage from imminent attacks
     */
    public int getExpectedDamageNextTick() {
        return aggressiveNpcs.stream()
                .filter(AggressorInfo::isAttackImminent)
                .mapToInt(AggressorInfo::getExpectedMaxHit)
                .filter(d -> d > 0)
                .sum();
    }

    // ========================================================================
    // Convenience Methods - Poison
    // ========================================================================

    /**
     * Check if the player is poisoned.
     *
     * @return true if poisoned (not venomed)
     */
    public boolean isPoisoned() {
        return poisonState.isPoisoned();
    }

    /**
     * Check if the player is venomed.
     *
     * @return true if venomed
     */
    public boolean isVenomed() {
        return poisonState.isVenomed();
    }

    /**
     * Check if venom damage is at critical levels (16+).
     * Per REQUIREMENTS.md Section 10.6.7: HCIM should flee at this point.
     *
     * @return true if venom is critical
     */
    public boolean isVenomCritical() {
        return poisonState.isVenomCritical();
    }

    /**
     * Predict next venom damage.
     * Per REQUIREMENTS.md Section 6.2.6: predictNextVenomDamage().
     *
     * @return predicted next venom damage, or 0 if not venomed
     */
    public int predictNextVenomDamage() {
        return poisonState.predictNextVenomDamage();
    }

    /**
     * Get the current venom damage level.
     * Per REQUIREMENTS.md Section 6.2.6: currentVenomDamage.
     *
     * @return current venom damage, or 0 if not venomed
     */
    public int getCurrentVenomDamage() {
        return isVenomed() ? poisonState.getCurrentDamage() : 0;
    }

    /**
     * Get the tick of the last venom hit.
     * Per REQUIREMENTS.md Section 6.2.6: lastVenomTick.
     *
     * @return last venom tick, or -1 if not venomed
     */
    public int getLastVenomTick() {
        return poisonState.getLastPoisonTick();
    }

    // ========================================================================
    // Convenience Methods - Combat Stats
    // ========================================================================

    /**
     * Get the boosted level for a specific combat skill.
     *
     * @param skill the skill to check
     * @return boosted level, or -1 if not tracked
     */
    public int getBoostedLevel(Skill skill) {
        return boostedStats.getOrDefault(skill, -1);
    }

    /**
     * Check if a combat stat is drained (below base level).
     * 
     * <p>Note: This method only checks the boosted stats stored in CombatState.
     * For accurate comparison against base levels, use 
     * {@link PlayerState#isSkillDrained(Skill)} instead, which compares 
     * boosted levels against base (real) skill levels.
     *
     * @param skill the skill to check
     * @param baseLevel the base (real) level to compare against
     * @return true if the boosted level is below the base level
     */
    public boolean isStatDrained(Skill skill, int baseLevel) {
        int boosted = getBoostedLevel(skill);
        return boosted >= 0 && boosted < baseLevel;
    }

    /**
     * Check if a combat stat is drained.
     * 
     * @deprecated Use {@link #isStatDrained(Skill, int)} with base level, 
     *             or use {@link PlayerState#isSkillDrained(Skill)} for accurate checks.
     * @param skill the skill to check
     * @return true if the boosted level is significantly below normal
     */
    @Deprecated
    public boolean isStatDrained(Skill skill) {
        // Legacy fallback - compare against stored boosted level only
        // This is unreliable; callers should use PlayerState.isSkillDrained() instead
        int boosted = getBoostedLevel(skill);
        // Estimate: if boosted is below 90% of a reasonable minimum, consider drained
        // This is a heuristic for backwards compatibility only
        return boosted >= 0 && boosted < 10; 
    }

    // ========================================================================
    // Convenience Methods - Special Attack
    // ========================================================================

    /**
     * Check if special attack energy is above a threshold.
     *
     * @param percent threshold (0-100)
     * @return true if spec energy is at or above threshold
     */
    public boolean hasSpecEnergy(int percent) {
        return specialAttackEnergy >= percent;
    }

    /**
     * Check if the player can use a special attack with a given cost.
     *
     * @param cost the spec cost (25, 50, or 100)
     * @return true if enough energy for the spec
     */
    public boolean canUseSpec(int cost) {
        return specialAttackEnergy >= cost;
    }

    // ========================================================================
    // Convenience Methods - Attack Timing
    // ========================================================================

    /**
     * Check if the player can attack now (cooldown elapsed).
     *
     * @return true if attack is off cooldown
     */
    public boolean isAttackReady() {
        return canAttack;
    }

    /**
     * Get ticks until the player can attack again.
     *
     * @return ticks remaining, or 0 if ready
     */
    public int getTicksUntilCanAttack() {
        if (canAttack || ticksSinceLastAttack < 0) {
            return 0;
        }
        return Math.max(0, weaponAttackSpeed - ticksSinceLastAttack);
    }

    // ========================================================================
    // Convenience Methods - Incoming Attacks
    // ========================================================================

    /**
     * Check if an incoming attack has been detected.
     *
     * @return true if an attack is incoming
     */
    public boolean hasIncomingAttack() {
        return incomingAttackStyle != AttackStyle.UNKNOWN && ticksUntilAttackLands >= 0;
    }

    /**
     * Check if an incoming attack is imminent (within 2 ticks).
     *
     * @return true if attack is about to land
     */
    public boolean isAttackImminent() {
        return hasIncomingAttack() && ticksUntilAttackLands <= 2;
    }

    // ========================================================================
    // Summary
    // ========================================================================

    /**
     * Get a summary string for logging.
     *
     * @return summary of combat state
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder("CombatState[");
        if (hasTarget() && targetNpc != null) {
            sb.append("target=").append(targetNpc.getName());
        } else {
            sb.append("no target");
        }
        sb.append(", spec=").append(specialAttackEnergy).append("%");
        sb.append(", aggressors=").append(aggressiveNpcs.size());
        if (inMultiCombat) {
            sb.append(" (MULTI)");
        }
        if (poisonState.hasEffect()) {
            sb.append(", ").append(poisonState.getType());
        }
        if (hasIncomingAttack()) {
            sb.append(", incoming=").append(incomingAttackStyle);
        }
        sb.append("]");
        return sb.toString();
    }
}

