package com.rocinante.state;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of a game object's state at a point in time.
 *
 * Per REQUIREMENTS.md Section 6.2.4, captures game objects within 20 tiles.
 * Game objects include trees, rocks, doors, chests, etc.
 */
@Value
@Builder
public class GameObjectSnapshot {

    /**
     * The object's definition ID.
     */
    int id;

    /**
     * The object's world position (south-west tile for multi-tile objects).
     */
    WorldPoint worldPosition;

    /**
     * The plane/level the object is on (0 = ground floor).
     */
    int plane;

    /**
     * Available actions on this object (e.g., "Open", "Mine", "Chop down").
     * Null entries indicate no action for that menu slot.
     */
    @Builder.Default
    List<String> actions = Collections.emptyList();

    /**
     * The object's name from its composition.
     */
    String name;

    /**
     * Size of the object in tiles (width).
     */
    int sizeX;

    /**
     * Size of the object in tiles (height).
     */
    int sizeY;

    /**
     * The object's orientation (0-3, representing N/E/S/W).
     */
    int orientation;

    /**
     * Whether this object blocks movement/line of sight.
     */
    boolean impassable;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if this object has a specific action.
     *
     * @param action the action to check for (case-insensitive)
     * @return true if the action is available
     */
    public boolean hasAction(String action) {
        if (action == null || actions == null) {
            return false;
        }
        return actions.stream()
                .filter(a -> a != null)
                .anyMatch(a -> a.equalsIgnoreCase(action));
    }

    /**
     * Get the first available action.
     *
     * @return the first non-null action, or null if none
     */
    public String getFirstAction() {
        if (actions == null) {
            return null;
        }
        return actions.stream()
                .filter(a -> a != null)
                .findFirst()
                .orElse(null);
    }

    /**
     * Calculate distance from this object to a world point.
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
     * Check if this object is within a certain distance of a point.
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
     * Check if this is a single-tile object.
     *
     * @return true if object is 1x1 tiles
     */
    public boolean isSingleTile() {
        return sizeX == 1 && sizeY == 1;
    }

    /**
     * Get the center tile of this object (for multi-tile objects).
     *
     * @return center world point, or the position if single-tile
     */
    public WorldPoint getCenterTile() {
        if (worldPosition == null) {
            return null;
        }
        if (isSingleTile()) {
            return worldPosition;
        }
        return new WorldPoint(
                worldPosition.getX() + sizeX / 2,
                worldPosition.getY() + sizeY / 2,
                worldPosition.getPlane()
        );
    }

    /**
     * Get a summary string for logging.
     *
     * @return summary of this object
     */
    public String getSummary() {
        return String.format("Object[%s (id=%d) at %s, actions=%s]",
                name != null ? name : "Unknown",
                id,
                worldPosition,
                actions);
    }
}

