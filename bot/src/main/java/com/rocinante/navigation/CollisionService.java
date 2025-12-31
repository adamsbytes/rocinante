package com.rocinante.navigation;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Collision detection service using ShortestPath's global collision data.
 *
 * <p>This service provides collision queries for:
 * <ul>
 *   <li>Tile blocking status (walls, water, impassable terrain)</li>
 *   <li>Directional movement validation (fences, barriers)</li>
 *   <li>Adjacency-based interaction validation</li>
 * </ul>
 *
 * <p>All methods delegate to {@link ShortestPathBridge} for actual collision data
 * from the Shortest Path plugin's global CollisionMap.
 *
 * <p>This service is the preferred way to access collision data. Tasks should use
 * {@link NavigationService} which delegates collision queries here.
 *
 * @see ShortestPathBridge
 * @see NavigationService
 */
@Slf4j
@Singleton
public class CollisionService {

    private final ShortestPathBridge bridge;

    @Inject
    public CollisionService(ShortestPathBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Check if movement is allowed between two adjacent tiles.
     * This is the primary method for checking if a fence/wall/river blocks movement.
     *
     * @param from source tile
     * @param to destination tile (must be adjacent)
     * @return true if movement is allowed, false if blocked
     */
    public boolean canMoveTo(WorldPoint from, WorldPoint to) {
        return bridge.canMoveTo(from, to);
    }

    /**
     * Check if a tile is completely blocked (cannot be entered from any direction).
     *
     * @param point world point to check
     * @return true if blocked
     */
    public boolean isBlocked(WorldPoint point) {
        return bridge.isBlocked(point);
    }

    /**
     * Check if a tile is completely blocked.
     *
     * @param x world X coordinate
     * @param y world Y coordinate
     * @param z plane (0-3)
     * @return true if blocked
     */
    public boolean isBlocked(int x, int y, int z) {
        return bridge.isBlocked(x, y, z);
    }

    /**
     * Check if movement is allowed from a tile to the north.
     *
     * @param x world X coordinate
     * @param y world Y coordinate
     * @param z plane
     * @return true if northward movement is allowed
     */
    public boolean canMoveNorth(int x, int y, int z) {
        return bridge.canMoveNorth(x, y, z);
    }

    /**
     * Check if movement is allowed from a tile to the south.
     *
     * @param x world X coordinate
     * @param y world Y coordinate
     * @param z plane
     * @return true if southward movement is allowed
     */
    public boolean canMoveSouth(int x, int y, int z) {
        return bridge.canMoveSouth(x, y, z);
    }

    /**
     * Check if movement is allowed from a tile to the east.
     *
     * @param x world X coordinate
     * @param y world Y coordinate
     * @param z plane
     * @return true if eastward movement is allowed
     */
    public boolean canMoveEast(int x, int y, int z) {
        return bridge.canMoveEast(x, y, z);
    }

    /**
     * Check if movement is allowed from a tile to the west.
     *
     * @param x world X coordinate
     * @param y world Y coordinate
     * @param z plane
     * @return true if westward movement is allowed
     */
    public boolean canMoveWest(int x, int y, int z) {
        return bridge.canMoveWest(x, y, z);
    }

    /**
     * Check if the player can interact with a target from their current position.
     * This is the primary method for preventing clicking targets across barriers.
     *
     * <p>For objects with multi-tile footprints, check each tile of the footprint
     * for adjacency.
     *
     * @param playerPos player's world position
     * @param targetPos target's world position (or one tile of footprint)
     * @return true if interaction is possible (adjacent and no blocking terrain)
     */
    public boolean canInteractWith(WorldPoint playerPos, WorldPoint targetPos) {
        return bridge.canInteractWith(playerPos, targetPos);
    }
}
