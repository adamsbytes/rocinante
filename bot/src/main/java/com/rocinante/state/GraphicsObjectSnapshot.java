package com.rocinante.state;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Immutable snapshot of a graphics object's state at a point in time.
 *
 * Per REQUIREMENTS.md Section 6.2.4, graphics objects are tracked for prayer flicking.
 * Graphics objects are visual effects (spell impacts, AOE indicators, etc.).
 */
@Value
@Builder
public class GraphicsObjectSnapshot {

    /**
     * The graphics object's ID (determines visual appearance).
     */
    int id;

    /**
     * The local position where this graphics object is displayed.
     */
    LocalPoint localPosition;

    /**
     * The world position (converted from local).
     */
    WorldPoint worldPosition;

    /**
     * The animation frame currently being displayed.
     */
    int frame;

    /**
     * The game cycle when this graphics object was created.
     */
    int startCycle;

    /**
     * The height offset for display.
     */
    int height;

    /**
     * Whether this graphics object is finished playing.
     */
    boolean finished;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Calculate distance from this graphics object to a world point.
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
     * Check if this graphics object is within a certain distance of a point.
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
     * Check if this is an AOE attack indicator (common patterns).
     * Note: This is heuristic-based and may need expansion for specific content.
     *
     * @return true if this appears to be an AOE indicator
     */
    public boolean isAoeIndicator() {
        // Common AOE graphics IDs - expand as needed
        // These are examples; actual IDs depend on specific content
        return id >= 1 && id <= 10; // Placeholder - needs actual AOE graphic IDs
    }

    /**
     * Get a summary string for logging.
     *
     * @return summary of this graphics object
     */
    public String getSummary() {
        return String.format("GraphicsObject[id=%d at %s, frame=%d, finished=%s]",
                id,
                worldPosition,
                frame,
                finished);
    }
}

