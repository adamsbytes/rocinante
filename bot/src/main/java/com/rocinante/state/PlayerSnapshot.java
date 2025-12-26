package com.rocinante.state;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Immutable snapshot of another player's visible state at a point in time.
 *
 * Per REQUIREMENTS.md Section 6.2.4, captures nearby players within 20 tiles.
 * Note: This is for OTHER players, not the local player (see PlayerState).
 */
@Value
@Builder
public class PlayerSnapshot {

    /**
     * The player's display name.
     * May be null if not loaded.
     */
    String name;

    /**
     * The player's combat level.
     */
    int combatLevel;

    /**
     * The player's world position.
     */
    WorldPoint worldPosition;

    /**
     * Whether the player is skulled (has PvP skull icon).
     */
    boolean skulled;

    /**
     * The skull icon type (-1 = no skull, 0+ = skull variant).
     */
    int skullIcon;

    /**
     * Current animation ID being played.
     * -1 if no animation.
     */
    int animationId;

    /**
     * Whether the player is currently in combat (based on animation/interaction).
     */
    boolean inCombat;

    /**
     * Index of the actor this player is interacting with.
     * -1 if not interacting.
     */
    int interactingIndex;

    /**
     * Whether this player is on the local player's friends list.
     */
    boolean isFriend;

    /**
     * Whether this player is in the local player's clan.
     */
    boolean isClanMember;

    /**
     * The player's overhead prayer icon (-1 = none).
     * Used to detect what protection prayer they're using.
     */
    int overheadIcon;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if this player has any skull icon.
     *
     * @return true if skulled
     */
    public boolean isSkulled() {
        return skullIcon >= 0;
    }

    /**
     * Check if this player is currently animating.
     *
     * @return true if animation is playing
     */
    public boolean isAnimating() {
        return animationId != -1;
    }

    /**
     * Check if this player has a protection prayer active.
     *
     * @return true if overhead prayer icon is visible
     */
    public boolean hasOverheadPrayer() {
        return overheadIcon >= 0;
    }

    /**
     * Calculate distance from this player to a world point.
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
     * Check if this player is within a certain distance of a point.
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
     * @return summary of this player
     */
    public String getSummary() {
        return String.format("Player[%s (lvl %d) at %s, skulled=%s, combat=%s]",
                name != null ? name : "Unknown",
                combatLevel,
                worldPosition,
                skulled,
                inCombat);
    }
}

