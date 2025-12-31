package com.rocinante.navigation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Centralized service for scene loading and coordinate validation.
 *
 * <p>This service provides consistent methods for determining:
 * <ul>
 *   <li>Whether a world point is within the currently loaded scene</li>
 *   <li>Maximum distances for local navigation</li>
 *   <li>Scene coordinate conversion utilities</li>
 * </ul>
 *
 * <p>By centralizing these checks, we ensure:
 * <ul>
 *   <li>Consistent LOCAL_NAV_MAX_DISTANCE across all components</li>
 *   <li>Proper bounds validation before collision map access</li>
 *   <li>Clear semantics for "in scene" vs "reachable"</li>
 * </ul>
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SceneManager {

    /**
     * Maximum distance (in tiles) for local A* pathfinding.
     *
     * <p>Beyond this distance, navigation should use WebWalker for graph-based routing.
     * This is approximately half the scene size (52 tiles), ensuring both endpoints are 
     * within the loaded scene for reliable pathfinding.
     */
    public static final int MAX_LOCAL_NAV_DISTANCE = 52;

    /**
     * Maximum distance for adjacency validation checks.
     *
     * <p>Slightly larger than MAX_LOCAL_NAV_DISTANCE to allow validation before nav fails.
     * Scene is 104x104, so 60 tiles ensures we're still within loaded scene bounds.
     */
    public static final int MAX_VALIDATION_DISTANCE = 60;

    /**
     * Scene size constant for external reference.
     */
    public static final int SCENE_SIZE = Constants.SCENE_SIZE;

    private final Client client;

    // ========================================================================
    // Scene Loading Checks
    // ========================================================================

    /**
     * Check if a world point is within the currently loaded scene.
     *
     * <p>A point is "in scene" if:
     * <ul>
     *   <li>The client has a valid base position</li>
     *   <li>The point's world coordinates fall within the scene bounds</li>
     *   <li>LocalPoint.fromWorld() returns a valid result</li>
     * </ul>
     *
     * @param point world point to check
     * @return true if the point is within the loaded scene
     */
    public boolean isInLoadedScene(@Nullable WorldPoint point) {
        if (point == null) {
            return false;
        }

        // Check if we can convert to local coordinates
        LocalPoint local = LocalPoint.fromWorld(client, point);
        if (local == null) {
            log.debug("isInLoadedScene: {} outside loaded scene (LocalPoint conversion failed)", point);
            return false;
        }

        // Validate scene bounds
        int sceneX = local.getSceneX();
        int sceneY = local.getSceneY();
        if (!isInSceneBounds(sceneX, sceneY)) {
            log.debug("isInLoadedScene: {} has scene coords ({}, {}) outside bounds [0, {})",
                    point, sceneX, sceneY, SCENE_SIZE);
            return false;
        }
        return true;
    }

    /**
     * Check if both points are within the currently loaded scene.
     *
     * <p>This is useful for operations that require both start and end
     * points to be accessible, such as local pathfinding.
     *
     * @param a first point
     * @param b second point
     * @return true if both points are in the loaded scene
     */
    public boolean areBothPointsLoaded(@Nullable WorldPoint a, @Nullable WorldPoint b) {
        return isInLoadedScene(a) && isInLoadedScene(b);
    }

    /**
     * Check if a world point is within local navigation range of the player.
     *
     * <p>This checks both:
     * <ul>
     *   <li>Distance is within MAX_LOCAL_NAV_DISTANCE</li>
     *   <li>The point is in the loaded scene</li>
     * </ul>
     *
     * @param playerPos player's current position
     * @param target    target position to check
     * @return true if local A* pathfinding should work for this target
     */
    public boolean isWithinLocalNavRange(@Nullable WorldPoint playerPos, @Nullable WorldPoint target) {
        if (playerPos == null || target == null) {
            return false;
        }

        // Distance check
        int distance = playerPos.distanceTo(target);
        if (distance > MAX_LOCAL_NAV_DISTANCE) {
            return false;
        }

        // Both points must be in loaded scene
        return areBothPointsLoaded(playerPos, target);
    }

    /**
     * Get the maximum distance for local navigation.
     *
     * @return maximum local nav distance in tiles
     */
    public int getMaxLocalNavDistance() {
        return MAX_LOCAL_NAV_DISTANCE;
    }

    // ========================================================================
    // Scene Coordinate Utilities
    // ========================================================================

    /**
     * Convert world coordinates to scene coordinates.
     *
     * @param world world point to convert
     * @return array of [sceneX, sceneY], or null if outside loaded scene
     */
    @Nullable
    public int[] worldToScene(@Nullable WorldPoint world) {
        if (world == null) {
            return null;
        }

        LocalPoint local = LocalPoint.fromWorld(client, world);
        if (local == null) {
            return null;
        }

        int sceneX = local.getSceneX();
        int sceneY = local.getSceneY();

        if (!isInSceneBounds(sceneX, sceneY)) {
            return null;
        }

        return new int[]{sceneX, sceneY};
    }

    /**
     * Convert scene coordinates to world coordinates.
     *
     * @param sceneX scene X coordinate
     * @param sceneY scene Y coordinate
     * @param plane  plane/floor level
     * @return world point, or null if scene coordinates are invalid
     */
    @Nullable
    public WorldPoint sceneToWorld(int sceneX, int sceneY, int plane) {
        if (!isInSceneBounds(sceneX, sceneY)) {
            return null;
        }

        int baseX = client.getBaseX();
        int baseY = client.getBaseY();

        return new WorldPoint(baseX + sceneX, baseY + sceneY, plane);
    }

    /**
     * Get the current scene base position.
     *
     * @return world point at scene origin (0, 0), or null if not available
     */
    @Nullable
    public WorldPoint getSceneBase() {
        int baseX = client.getBaseX();
        int baseY = client.getBaseY();

        if (baseX == 0 && baseY == 0) {
            log.debug("getSceneBase: Client scene not initialized (base=0,0)");
            return null;
        }

        return new WorldPoint(baseX, baseY, client.getPlane());
    }

    /**
     * Get the player's current scene coordinates.
     *
     * @return array of [sceneX, sceneY], or null if player position unavailable
     */
    @Nullable
    public int[] getPlayerSceneCoords() {
        if (client.getLocalPlayer() == null) {
            return null;
        }

        WorldPoint playerPos = client.getLocalPlayer().getWorldLocation();
        return worldToScene(playerPos);
    }

    // ========================================================================
    // Bounds Validation
    // ========================================================================

    /**
     * Check if scene coordinates are within valid bounds.
     *
     * @param sceneX scene X coordinate
     * @param sceneY scene Y coordinate
     * @return true if coordinates are within [0, SCENE_SIZE)
     */
    public boolean isInSceneBounds(int sceneX, int sceneY) {
        return sceneX >= 0 && sceneX < SCENE_SIZE &&
               sceneY >= 0 && sceneY < SCENE_SIZE;
    }

    /**
     * Check if scene coordinates are valid for pathfinding with some margin.
     *
     * <p>Pathfinding near scene edges can be unreliable. This method
     * provides a stricter check with a configurable margin.
     *
     * @param sceneX scene X coordinate
     * @param sceneY scene Y coordinate
     * @param margin minimum distance from edge (typically 1-5)
     * @return true if coordinates are safely within bounds
     */
    public boolean isInSceneBoundsWithMargin(int sceneX, int sceneY, int margin) {
        return sceneX >= margin && sceneX < (SCENE_SIZE - margin) &&
               sceneY >= margin && sceneY < (SCENE_SIZE - margin);
    }

    // ========================================================================
    // Distance Utilities
    // ========================================================================

    /**
     * Calculate whether a path between two points should use local or web navigation.
     *
     * @param from starting point
     * @param to   destination point
     * @return true if local navigation is appropriate, false if web walker should be used
     */
    public boolean shouldUseLocalNav(@Nullable WorldPoint from, @Nullable WorldPoint to) {
        if (from == null || to == null) {
            return false;
        }

        // Different planes always require web walker (for stairs/ladders)
        if (from.getPlane() != to.getPlane()) {
            return false;
        }

        // Check distance
        int distance = from.distanceTo(to);
        if (distance > MAX_LOCAL_NAV_DISTANCE) {
            return false;
        }

        // Both points must be in loaded scene
        return areBothPointsLoaded(from, to);
    }

    /**
     * Get the distance category for navigation decision making.
     *
     * @param from starting point
     * @param to   destination point
     * @return distance category
     */
    public DistanceCategory getDistanceCategory(@Nullable WorldPoint from, @Nullable WorldPoint to) {
        if (from == null || to == null) {
            return DistanceCategory.UNREACHABLE;
        }

        if (from.getPlane() != to.getPlane()) {
            return DistanceCategory.DIFFERENT_PLANE;
        }

        int distance = from.distanceTo(to);

        if (distance == 0) {
            return DistanceCategory.SAME_TILE;
        }

        if (distance == 1) {
            return DistanceCategory.ADJACENT;
        }

        if (distance <= MAX_LOCAL_NAV_DISTANCE && areBothPointsLoaded(from, to)) {
            return DistanceCategory.LOCAL_NAV;
        }

        return DistanceCategory.WEB_NAV;
    }

    /**
     * Categories for navigation distance/method selection.
     */
    public enum DistanceCategory {
        /** Points are the same tile */
        SAME_TILE,
        /** Points are adjacent (1 tile apart) */
        ADJACENT,
        /** Points are within local A* range */
        LOCAL_NAV,
        /** Points require web-based navigation */
        WEB_NAV,
        /** Points are on different planes */
        DIFFERENT_PLANE,
        /** Points are unreachable (null input) */
        UNREACHABLE
    }
}
