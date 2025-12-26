package com.rocinante.quest.conditions;

import com.rocinante.state.PlayerState;
import com.rocinante.state.StateCondition;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * Condition that checks if the player is within a defined 3D zone.
 *
 * This mirrors Quest Helper's Zone and ZoneRequirement patterns but integrates
 * with our StateCondition system. Zones are defined by two corner WorldPoints
 * and optionally multiple planes.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Tutorial Island Gielinor Guide's house
 * Zone guideHouse = Zone.fromCorners(
 *     new WorldPoint(3094, 3107, 0),
 *     new WorldPoint(3098, 3111, 0)
 * );
 * StateCondition inGuideHouse = new ZoneCondition(guideHouse);
 *
 * // Combine with other conditions
 * StateCondition readyToTalk = inGuideHouse
 *     .and(VarbitCondition.equals(281, 2));
 * }</pre>
 */
public class ZoneCondition implements StateCondition {

    @Getter
    private final Zone zone;

    @Getter
    private final String description;

    /**
     * Create a zone condition.
     *
     * @param zone the zone to check
     */
    public ZoneCondition(Zone zone) {
        this(zone, null);
    }

    /**
     * Create a zone condition with a description.
     *
     * @param zone        the zone to check
     * @param description human-readable description
     */
    public ZoneCondition(Zone zone, String description) {
        this.zone = zone;
        this.description = description;
    }

    @Override
    public boolean test(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos == null) {
            return false;
        }

        return zone.contains(playerPos);
    }

    @Override
    public String describe() {
        if (description != null) {
            return description;
        }
        return String.format("inZone[%d,%d - %d,%d, plane %d-%d]",
                zone.getMinX(), zone.getMinY(),
                zone.getMaxX(), zone.getMaxY(),
                zone.getMinPlane(), zone.getMaxPlane());
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a zone condition from a Zone.
     *
     * @param zone the zone
     * @return zone condition
     */
    public static ZoneCondition in(Zone zone) {
        return new ZoneCondition(zone);
    }

    /**
     * Create a zone condition from a Zone with description.
     *
     * @param zone        the zone
     * @param description human-readable name for the zone
     * @return zone condition
     */
    public static ZoneCondition in(Zone zone, String description) {
        return new ZoneCondition(zone, description);
    }

    /**
     * Create a zone condition from two corner points.
     *
     * @param corner1 first corner
     * @param corner2 second corner
     * @return zone condition
     */
    public static ZoneCondition fromCorners(WorldPoint corner1, WorldPoint corner2) {
        return new ZoneCondition(Zone.fromCorners(corner1, corner2));
    }

    /**
     * Create a zone condition from two corner points with description.
     *
     * @param corner1     first corner
     * @param corner2     second corner
     * @param description human-readable name
     * @return zone condition
     */
    public static ZoneCondition fromCorners(WorldPoint corner1, WorldPoint corner2, String description) {
        return new ZoneCondition(Zone.fromCorners(corner1, corner2), description);
    }

    /**
     * Create a zone condition from a single point (1x1 tile).
     *
     * @param point the single tile
     * @return zone condition
     */
    public static ZoneCondition atPoint(WorldPoint point) {
        return new ZoneCondition(Zone.fromPoint(point));
    }

    /**
     * Create a zone condition for a region.
     *
     * @param regionId the region ID
     * @return zone condition
     */
    public static ZoneCondition inRegion(int regionId) {
        return new ZoneCondition(Zone.fromRegion(regionId));
    }

    /**
     * Create a zone condition for a region on a specific plane.
     *
     * @param regionId the region ID
     * @param plane    the plane
     * @return zone condition
     */
    public static ZoneCondition inRegion(int regionId, int plane) {
        return new ZoneCondition(Zone.fromRegion(regionId, plane));
    }

    // ========================================================================
    // Zone Inner Class
    // ========================================================================

    /**
     * Represents a 3D rectangular zone in the game world.
     * Mirrors Quest Helper's Zone class structure.
     */
    public static class Zone {
        @Getter
        private final int minX;
        @Getter
        private final int maxX;
        @Getter
        private final int minY;
        @Getter
        private final int maxY;
        @Getter
        private final int minPlane;
        @Getter
        private final int maxPlane;

        private static final int REGION_SIZE = 64;

        private Zone(int minX, int maxX, int minY, int maxY, int minPlane, int maxPlane) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minPlane = minPlane;
            this.maxPlane = maxPlane;
        }

        /**
         * Create a zone from two corner WorldPoints.
         *
         * @param corner1 first corner
         * @param corner2 second corner
         * @return the zone
         */
        public static Zone fromCorners(WorldPoint corner1, WorldPoint corner2) {
            return new Zone(
                    Math.min(corner1.getX(), corner2.getX()),
                    Math.max(corner1.getX(), corner2.getX()),
                    Math.min(corner1.getY(), corner2.getY()),
                    Math.max(corner1.getY(), corner2.getY()),
                    Math.min(corner1.getPlane(), corner2.getPlane()),
                    Math.max(corner1.getPlane(), corner2.getPlane())
            );
        }

        /**
         * Create a single-tile zone from a WorldPoint.
         *
         * @param point the single tile
         * @return the zone
         */
        public static Zone fromPoint(WorldPoint point) {
            return new Zone(
                    point.getX(), point.getX(),
                    point.getY(), point.getY(),
                    point.getPlane(), point.getPlane()
            );
        }

        /**
         * Create a zone from a region ID (covers all planes).
         *
         * @param regionId the region ID
         * @return the zone
         */
        public static Zone fromRegion(int regionId) {
            int minX = ((regionId >> 8) & 0xFF) << 6;
            int minY = (regionId & 0xFF) << 6;
            return new Zone(
                    minX, minX + REGION_SIZE - 1,
                    minY, minY + REGION_SIZE - 1,
                    0, 3
            );
        }

        /**
         * Create a zone from a region ID on a specific plane.
         *
         * @param regionId the region ID
         * @param plane    the plane
         * @return the zone
         */
        public static Zone fromRegion(int regionId, int plane) {
            int minX = ((regionId >> 8) & 0xFF) << 6;
            int minY = (regionId & 0xFF) << 6;
            return new Zone(
                    minX, minX + REGION_SIZE - 1,
                    minY, minY + REGION_SIZE - 1,
                    plane, plane
            );
        }

        /**
         * Create the "overworld" zone (main game surface).
         *
         * @return the overworld zone
         */
        public static Zone overworld() {
            return new Zone(1152, 3903, 2496, 4159, 0, 0);
        }

        /**
         * Check if a WorldPoint is within this zone.
         *
         * @param point the point to check
         * @return true if the point is inside the zone
         */
        public boolean contains(WorldPoint point) {
            return point.getX() >= minX && point.getX() <= maxX
                    && point.getY() >= minY && point.getY() <= maxY
                    && point.getPlane() >= minPlane && point.getPlane() <= maxPlane;
        }

        /**
         * Get the center point of this zone.
         *
         * @return center WorldPoint
         */
        public WorldPoint getCenter() {
            return new WorldPoint(
                    (minX + maxX) / 2,
                    (minY + maxY) / 2,
                    minPlane
            );
        }

        /**
         * Get the minimum corner WorldPoint.
         *
         * @return minimum corner
         */
        public WorldPoint getMinWorldPoint() {
            return new WorldPoint(minX, minY, minPlane);
        }

        /**
         * Get the maximum corner WorldPoint.
         *
         * @return maximum corner
         */
        public WorldPoint getMaxWorldPoint() {
            return new WorldPoint(maxX, maxY, maxPlane);
        }

        /**
         * Get the width of the zone.
         *
         * @return width in tiles
         */
        public int getWidth() {
            return maxX - minX + 1;
        }

        /**
         * Get the height of the zone.
         *
         * @return height in tiles
         */
        public int getHeight() {
            return maxY - minY + 1;
        }

        @Override
        public String toString() {
            return String.format("Zone[(%d,%d)-(%d,%d), plane %d-%d]",
                    minX, minY, maxX, maxY, minPlane, maxPlane);
        }
    }
}

