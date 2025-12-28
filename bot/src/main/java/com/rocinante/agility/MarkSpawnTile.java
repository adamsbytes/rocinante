package com.rocinante.agility;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Represents a potential spawn location for a Mark of Grace on an agility course.
 *
 * <p>Each mark spawn tile is associated with a specific obstacle index. Marks can only
 * spawn on tiles associated with obstacles the player has already completed in the
 * current lap. This information is crucial for determining reachability - on rooftop
 * courses, multiple platforms may share the same plane but are not walkable between
 * each other.
 *
 * <p>For example, on Draynor rooftop:
 * <ul>
 *   <li>After completing obstacle 0 (rough wall), marks at afterObstacle=0 are reachable</li>
 *   <li>After completing obstacle 1 (first tightrope), marks at afterObstacle=0,1 are reachable</li>
 *   <li>Marks at afterObstacle=2 are NOT reachable until you complete obstacle 2</li>
 * </ul>
 *
 * @see AgilityCourse
 * @see AgilityObstacle
 */
@Value
@Builder
public class MarkSpawnTile {

    /**
     * The world position where the mark can spawn.
     */
    WorldPoint position;

    /**
     * The obstacle index after which this tile becomes reachable.
     * A mark at this tile is only reachable if the player has completed
     * obstacle N where N >= afterObstacle.
     *
     * <p>For example:
     * <ul>
     *   <li>afterObstacle=0: Reachable after completing the first obstacle</li>
     *   <li>afterObstacle=3: Reachable after completing obstacles 0, 1, 2, and 3</li>
     * </ul>
     */
    int afterObstacle;

    /**
     * Optional description for debugging/documentation purposes.
     */
    String description;

    /**
     * Check if this mark spawn tile is reachable given the last completed obstacle index.
     *
     * @param lastCompletedObstacleIndex the index of the most recently completed obstacle (-1 if none)
     * @return true if a mark at this tile would be reachable
     */
    public boolean isReachableAfterObstacle(int lastCompletedObstacleIndex) {
        return lastCompletedObstacleIndex >= afterObstacle;
    }

    /**
     * Check if a world point matches this spawn tile's position.
     *
     * @param point the point to check
     * @return true if the point matches this tile's position
     */
    public boolean matchesPosition(WorldPoint point) {
        return position != null && position.equals(point);
    }

    /**
     * Check if a world point is within a tolerance of this spawn tile.
     *
     * @param point the point to check
     * @param tolerance max distance in tiles
     * @return true if the point is within tolerance of this tile
     */
    public boolean isNearPosition(WorldPoint point, int tolerance) {
        if (position == null || point == null) {
            return false;
        }
        return position.distanceTo(point) <= tolerance;
    }
}

