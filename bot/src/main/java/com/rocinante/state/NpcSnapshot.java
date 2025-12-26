package com.rocinante.state;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Immutable snapshot of an NPC's state at a point in time.
 *
 * Per REQUIREMENTS.md Section 6.2.4, captures NPC data within 20 tiles including
 * health bars for combat tracking.
 */
@Value
@Builder
public class NpcSnapshot {

    /**
     * The NPC's unique index in the client's NPC array.
     * Used for targeting and tracking across ticks.
     */
    int index;

    /**
     * The NPC's definition ID.
     * Used for identifying NPC type (e.g., which monster it is).
     */
    int id;

    /**
     * The NPC's name from its composition.
     * May be null for some NPCs.
     */
    String name;

    /**
     * The NPC's combat level.
     * 0 for non-combat NPCs.
     */
    int combatLevel;

    /**
     * The NPC's world position.
     */
    WorldPoint worldPosition;

    /**
     * Current health ratio (0-255 scale from client).
     * -1 if health bar is not visible.
     */
    int healthRatio;

    /**
     * Maximum health scale (typically 30 for most NPCs).
     * Used to calculate health percentage: healthRatio / healthScale.
     * -1 if health bar is not visible.
     */
    int healthScale;

    /**
     * Current animation ID being played.
     * -1 if no animation.
     */
    int animationId;

    /**
     * Index of the actor this NPC is interacting with.
     * -1 if not interacting with anyone.
     * Positive values are NPC indices, negative values are player indices (offset by 32768).
     */
    int interactingIndex;

    /**
     * Whether the NPC is currently interacting with the local player.
     */
    boolean targetingPlayer;

    /**
     * Whether the NPC is dead (health depleted).
     */
    boolean isDead;

    /**
     * The NPC's size in tiles (1 for most NPCs, larger for bosses).
     */
    int size;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Get health as a percentage (0.0 to 1.0).
     *
     * @return health percentage, or 1.0 if health bar not visible
     */
    public double getHealthPercent() {
        if (healthRatio < 0 || healthScale <= 0) {
            return 1.0; // Assume full health if not visible
        }
        return (double) healthRatio / healthScale;
    }

    /**
     * Check if health bar is visible (NPC has been damaged).
     *
     * @return true if health bar is visible
     */
    public boolean isHealthBarVisible() {
        return healthRatio >= 0 && healthScale > 0;
    }

    /**
     * Check if health is below a threshold percentage.
     *
     * @param percent threshold (0.0 to 1.0)
     * @return true if health is below threshold
     */
    public boolean isHealthBelow(double percent) {
        return getHealthPercent() < percent;
    }

    /**
     * Check if this NPC is currently animating an attack.
     *
     * @return true if an animation is playing
     */
    public boolean isAnimating() {
        return animationId != -1;
    }

    /**
     * Check if this NPC is animating a specific animation.
     *
     * @param expectedAnimationId the animation to check for
     * @return true if playing the specified animation
     */
    public boolean isAnimating(int expectedAnimationId) {
        return animationId == expectedAnimationId;
    }

    /**
     * Calculate distance from this NPC to a world point.
     *
     * @param target the target point
     * @return distance in tiles, or -1 if position invalid
     */
    public int distanceTo(WorldPoint target) {
        if (worldPosition == null || target == null) {
            return -1;
        }
        return worldPosition.distanceTo(target);
    }

    /**
     * Check if this NPC is within a certain distance of a point.
     *
     * @param target   the target point
     * @param distance maximum distance in tiles
     * @return true if within distance
     */
    public boolean isWithinDistance(WorldPoint target, int distance) {
        int dist = distanceTo(target);
        return dist >= 0 && dist <= distance;
    }

    /**
     * Get a summary string for logging.
     *
     * @return summary of this NPC
     */
    public String getSummary() {
        return String.format("NPC[%s (id=%d, idx=%d) at %s, hp=%.0f%%, targeting=%s]",
                name != null ? name : "Unknown",
                id,
                index,
                worldPosition,
                getHealthPercent() * 100,
                targetingPlayer);
    }
}

