package com.rocinante.tasks.minigame.wintertodt;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * The four brazier locations in Wintertodt.
 *
 * <p>Each brazier has associated positions for:
 * <ul>
 *   <li>The brazier object itself</li>
 *   <li>The bruma root tree nearby</li>
 *   <li>A safe standing position near the brazier</li>
 * </ul>
 *
 * @see WintertodtTask
 */
@AllArgsConstructor
@Getter
public enum BrazierLocation {

    /**
     * Southwest brazier (default/most common).
     */
    SOUTHWEST(
            new WorldPoint(1620, 3997, 0),  // Brazier position
            new WorldPoint(1621, 3988, 0),  // Bruma root tree
            new WorldPoint(1622, 3996, 0)   // Safe standing position
    ),

    /**
     * Southeast brazier.
     */
    SOUTHEAST(
            new WorldPoint(1638, 3997, 0),
            new WorldPoint(1637, 3988, 0),
            new WorldPoint(1636, 3996, 0)
    ),

    /**
     * Northwest brazier.
     */
    NORTHWEST(
            new WorldPoint(1620, 4015, 0),
            new WorldPoint(1621, 4024, 0),
            new WorldPoint(1622, 4014, 0)
    ),

    /**
     * Northeast brazier.
     */
    NORTHEAST(
            new WorldPoint(1638, 4015, 0),
            new WorldPoint(1637, 4024, 0),
            new WorldPoint(1636, 4014, 0)
    );

    /**
     * Position of the brazier game object.
     */
    private final WorldPoint brazierPosition;

    /**
     * Position of the nearby bruma root tree.
     */
    private final WorldPoint rootTreePosition;

    /**
     * Safe position for standing near the brazier.
     * Player should stand here for most activities.
     */
    private final WorldPoint safePosition;

    /**
     * Get the distance from a position to this brazier.
     *
     * @param position the position to check
     * @return distance in tiles
     */
    public int distanceTo(WorldPoint position) {
        return position.distanceTo(brazierPosition);
    }

    /**
     * Find the nearest brazier to a given position.
     *
     * @param position the position to check from
     * @return the nearest BrazierLocation
     */
    public static BrazierLocation nearest(WorldPoint position) {
        BrazierLocation nearest = SOUTHWEST;
        int minDistance = Integer.MAX_VALUE;

        for (BrazierLocation loc : values()) {
            int distance = loc.distanceTo(position);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = loc;
            }
        }

        return nearest;
    }

    /**
     * Check if a position is within operating range of this brazier.
     *
     * @param position the position to check
     * @return true if within 3 tiles of brazier
     */
    public boolean isInRange(WorldPoint position) {
        return distanceTo(position) <= 3;
    }
}

