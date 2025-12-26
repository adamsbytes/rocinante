package com.rocinante.state;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Immutable snapshot of a projectile's state at a point in time.
 *
 * Per REQUIREMENTS.md Section 6.2.4, projectiles are tracked for prayer flicking.
 * Projectiles are in-flight attacks (arrows, magic spells, etc.).
 */
@Value
@Builder
public class ProjectileSnapshot {

    /**
     * The projectile's graphic ID (determines appearance).
     */
    int id;

    /**
     * The projectile's starting world position.
     */
    WorldPoint startPosition;

    /**
     * The projectile's target world position (where it will land).
     */
    WorldPoint endPosition;

    /**
     * The game cycle when this projectile was spawned.
     */
    int cycleStart;

    /**
     * The game cycle when this projectile will arrive at its target.
     */
    int cycleEnd;

    /**
     * Index of the target actor.
     * Positive = NPC index, negative = player index (offset by 32768).
     * -1 if no specific actor target.
     */
    int targetActorIndex;

    /**
     * Whether this projectile is targeting the local player.
     */
    boolean targetingPlayer;

    /**
     * The projectile's current height (Z coordinate).
     */
    int height;

    /**
     * The projectile's starting height.
     */
    int startHeight;

    /**
     * The projectile's ending height.
     */
    int endHeight;

    /**
     * Slope of the projectile arc.
     */
    int slope;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Calculate the number of game ticks until this projectile lands.
     *
     * @param currentCycle the current game cycle
     * @return ticks remaining (approximate, as cycles != ticks exactly)
     */
    public int getTicksUntilImpact(int currentCycle) {
        if (cycleEnd <= currentCycle) {
            return 0;
        }
        // Game cycles run faster than ticks (30 cycles per tick approximately)
        return Math.max(0, (cycleEnd - currentCycle) / 30);
    }

    /**
     * Check if this projectile has already landed.
     *
     * @param currentCycle the current game cycle
     * @return true if the projectile has arrived
     */
    public boolean hasLanded(int currentCycle) {
        return currentCycle >= cycleEnd;
    }

    /**
     * Get the total flight duration in cycles.
     *
     * @return flight duration
     */
    public int getFlightDuration() {
        return cycleEnd - cycleStart;
    }

    /**
     * Calculate the distance the projectile travels.
     *
     * @return distance in tiles, or -1 if positions invalid
     */
    public int getTravelDistance() {
        if (startPosition == null || endPosition == null) {
            return -1;
        }
        return startPosition.distanceTo(endPosition);
    }

    /**
     * Get a summary string for logging.
     *
     * @return summary of this projectile
     */
    public String getSummary() {
        return String.format("Projectile[id=%d, from=%s to=%s, targetingPlayer=%s, cycles=%d-%d]",
                id,
                startPosition,
                endPosition,
                targetingPlayer,
                cycleStart,
                cycleEnd);
    }
}

