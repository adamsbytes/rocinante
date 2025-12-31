package com.rocinante.navigation;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

/**
 * Centralized entity finding utility with built-in reachability validation.
 *
 * <p>Replaces duplicated findNearest implementations across tasks with a single,
 * collision-aware entity finder that ensures selected targets are actually reachable.
 *
 * <p>All methods return {@link Optional} results containing not just the entity,
 * but also the validated path and adjacent tile information needed for interaction.
 *
 * @see Reachability
 * @see PathFinder
 * @see AdjacentTileHelper
 */
@Slf4j
@Singleton
public class EntityFinder {

    /**
     * Maximum distance for local pathfinding (beyond this, delegate to WebWalker).
     */
    private static final int LOCAL_NAV_MAX_DISTANCE = 30;

    private final Client client;
    private final PathFinder pathFinder;
    private final Reachability reachability;
    private final WebWalker webWalker;

    @Inject
    public EntityFinder(Client client, PathFinder pathFinder, Reachability reachability, WebWalker webWalker) {
        this.client = client;
        this.pathFinder = pathFinder;
        this.reachability = reachability;
        this.webWalker = webWalker;
    }

    // ========================================================================
    // Object Finding
    // ========================================================================

    /**
     * Find the nearest reachable object matching the specified IDs.
     *
     * <p>Uses collision-aware pathfinding to ensure the returned object can actually
     * be reached. Objects behind fences, rivers, or other blocking terrain are rejected.
     *
     * @param playerPos player's current position
     * @param objectIds set of acceptable object IDs
     * @param radius    maximum search radius in tiles
     * @return search result with object, path, and adjacent tile if found
     */
    public Optional<ObjectSearchResult> findNearestReachableObject(
            WorldPoint playerPos,
            Collection<Integer> objectIds,
            int radius) {

        if (playerPos == null || objectIds == null || objectIds.isEmpty()) {
            return Optional.empty();
        }

        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int playerPlane = playerPos.getPlane();

        Map<String, Set<WorldPoint>> footprintCache = new HashMap<>();

        TileObject bestObject = null;
        int bestPathCost = Integer.MAX_VALUE;
        boolean bestVisible = false;
        int bestDistance = Integer.MAX_VALUE;
        List<WorldPoint> bestPath = Collections.emptyList();
        WorldPoint bestAdjacentTile = null;

        for (int x = 0; x < Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[playerPlane][x][y];
                if (tile == null) {
                    continue;
                }

                TileObject obj = findMatchingObjectOnTile(tile, objectIds);
                if (obj == null) {
                    continue;
                }

                WorldPoint objPos = TileObjectUtils.getWorldPoint(obj);
                if (objPos == null) {
                    continue;
                }

                int tileDistance = playerPos.distanceTo(objPos);
                if (tileDistance > radius) {
                    continue;
                }

                // Compute path cost with reachability validation
                ObjectPathResult pathResult = computeObjectPathCost(playerPos, obj, tileDistance, footprintCache);
                if (pathResult == null || pathResult.cost < 0) {
                    log.debug("Object {} at {} rejected: no reachable path", obj.getId(), objPos);
                    continue;
                }

                boolean visible = obj.getClickbox() != null;

                boolean better =
                        pathResult.cost < bestPathCost ||
                        (pathResult.cost == bestPathCost && visible && !bestVisible) ||
                        (pathResult.cost == bestPathCost && visible == bestVisible && tileDistance < bestDistance);

                if (better) {
                    bestObject = obj;
                    bestPathCost = pathResult.cost;
                    bestVisible = visible;
                    bestDistance = tileDistance;
                    bestPath = pathResult.path;
                    bestAdjacentTile = pathResult.adjacentTile;
                }
            }
        }

        if (bestObject == null) {
            return Optional.empty();
        }

        return Optional.of(new ObjectSearchResult(bestObject, bestPath, bestAdjacentTile, bestPathCost));
    }

    /**
     * Find any object matching the IDs on the given tile.
     */
    private TileObject findMatchingObjectOnTile(Tile tile, Collection<Integer> objectIds) {
        // Check game objects
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject obj : gameObjects) {
                if (obj != null && objectIds.contains(obj.getId())) {
                    return obj;
                }
            }
        }

        // Check wall objects
        WallObject wallObj = tile.getWallObject();
        if (wallObj != null && objectIds.contains(wallObj.getId())) {
            return wallObj;
        }

        // Check ground objects
        GroundObject groundObj = tile.getGroundObject();
        if (groundObj != null && objectIds.contains(groundObj.getId())) {
            return groundObj;
        }

        // Check decorative objects
        DecorativeObject decObj = tile.getDecorativeObject();
        if (decObj != null && objectIds.contains(decObj.getId())) {
            return decObj;
        }

        return null;
    }

    /**
     * Compute path cost to object with full reachability validation.
     */
    private ObjectPathResult computeObjectPathCost(WorldPoint playerPos,
                                                   TileObject target,
                                                   int tileDistance,
                                                   Map<String, Set<WorldPoint>> footprintCache) {
        WorldPoint targetPos = TileObjectUtils.getWorldPoint(target);
        if (targetPos == null) {
            return null;
        }

        // Standing on the object
        if (playerPos.equals(targetPos)) {
            return new ObjectPathResult(1, Collections.singletonList(playerPos), playerPos);
        }

        // Local pathfinding for close targets
        if (tileDistance <= LOCAL_NAV_MAX_DISTANCE) {
            Optional<AdjacentTileHelper.AdjacentPath> adjacent =
                    AdjacentTileHelper.findReachableAdjacent(pathFinder, reachability, playerPos, target,
                            getCachedFootprint(target, footprintCache));

            if (adjacent.isEmpty()) {
                return null; // Not reachable
            }

            AdjacentTileHelper.AdjacentPath result = adjacent.get();
            int cost = Math.max(1, result.getPath().size());
            return new ObjectPathResult(cost, result.getPath(), result.getDestination());
        }

        // Long-range: use web navigation, local validation will happen when near
        if (target != null && !webWalker.hasReachableAdjacent(targetPos, target)) {
            log.debug("Object {} at {} rejected early: no reachable adjacency", target.getId(), targetPos);
            return null;
        }

        NavigationPath navPath = webWalker.findUnifiedPath(playerPos, targetPos);
        if (navPath == null || navPath.isEmpty()) {
            return null;
        }

        int cost = Math.max(1, navPath.getTotalCostTicks());
        return new ObjectPathResult(cost, Collections.emptyList(), null);
    }

    private Set<WorldPoint> getCachedFootprint(TileObject target, Map<String, Set<WorldPoint>> cache) {
        String key = target.getId() + ":" + getOrientationSafe(target);
        return cache.computeIfAbsent(key, id -> reachability.getObjectFootprint(target));
    }

    private int getOrientationSafe(TileObject target) {
        if (target instanceof GameObject) {
            return ((GameObject) target).getOrientation();
        }
        if (target instanceof WallObject) {
            return ((WallObject) target).getOrientationA();
        }
        return 0;
    }

    // ========================================================================
    // NPC Finding - Melee (Adjacent Required)
    // ========================================================================

    /**
     * Find the nearest reachable NPC for melee interaction.
     *
     * <p>Validates that there's an adjacent tile with a clear path, ensuring
     * NPCs behind fences or rivers are not selected.
     *
     * @param playerPos player's current position
     * @param npcIds    set of acceptable NPC IDs
     * @param radius    maximum search radius in tiles
     * @return search result with NPC, path, and adjacent tile if found
     */
    public Optional<NpcSearchResult> findNearestReachableNpc(
            WorldPoint playerPos,
            Collection<Integer> npcIds,
            int radius) {

        return findNearestReachableNpc(playerPos, npcIds, null, radius);
    }

    /**
     * Find the nearest reachable NPC for melee interaction, filtered by name.
     *
     * @param playerPos player's current position
     * @param npcIds    set of acceptable NPC IDs
     * @param npcName   optional NPC name filter (null to skip name check)
     * @param radius    maximum search radius in tiles
     * @return search result with NPC, path, and adjacent tile if found
     */
    public Optional<NpcSearchResult> findNearestReachableNpc(
            WorldPoint playerPos,
            Collection<Integer> npcIds,
            String npcName,
            int radius) {

        if (playerPos == null || npcIds == null || npcIds.isEmpty()) {
            return Optional.empty();
        }

        NPC bestNpc = null;
        int bestPathCost = Integer.MAX_VALUE;
        int bestDistance = Integer.MAX_VALUE;
        List<WorldPoint> bestPath = Collections.emptyList();
        WorldPoint bestAdjacentTile = null;

        for (NPC npc : client.getNpcs()) {
            if (npc == null || npc.isDead()) {
                continue;
            }

            // Check ID match
            if (!npcIds.contains(npc.getId())) {
                continue;
            }

            // Check name match if specified
            if (npcName != null && !npcName.equals(npc.getName())) {
                continue;
            }

            WorldPoint npcPos = npc.getWorldLocation();
            if (npcPos == null || npcPos.getPlane() != playerPos.getPlane()) {
                continue;
            }

            int tileDistance = playerPos.distanceTo(npcPos);
            if (tileDistance > radius) {
                continue;
            }

            // Compute path cost with reachability validation
            NpcPathResult pathResult = computeNpcMeleePathCost(playerPos, npcPos, tileDistance);
            if (pathResult == null || pathResult.cost < 0) {
                continue;
            }

            boolean better =
                    pathResult.cost < bestPathCost ||
                    (pathResult.cost == bestPathCost && tileDistance < bestDistance);

            if (better) {
                bestNpc = npc;
                bestPathCost = pathResult.cost;
                bestDistance = tileDistance;
                bestPath = pathResult.path;
                bestAdjacentTile = pathResult.adjacentTile;
            }
        }

        if (bestNpc == null) {
            return Optional.empty();
        }

        return Optional.of(new NpcSearchResult(bestNpc, bestPath, bestAdjacentTile, bestPathCost));
    }

    /**
     * Compute path cost for melee NPC interaction (requires adjacency).
     */
    private NpcPathResult computeNpcMeleePathCost(WorldPoint playerPos, WorldPoint npcPos, int tileDistance) {
        // Already adjacent
        if (tileDistance <= 1) {
            if (reachability.canInteract(playerPos, npcPos)) {
                return new NpcPathResult(1, Collections.singletonList(playerPos), playerPos);
            }
            return null; // Adjacent but blocked by collision
        }

        // Local pathfinding for close targets
        if (tileDistance <= LOCAL_NAV_MAX_DISTANCE) {
            Optional<AdjacentTileHelper.AdjacentPath> adjacent =
                    AdjacentTileHelper.findReachableAdjacent(pathFinder, playerPos, npcPos);

            if (adjacent.isEmpty()) {
                return null; // Not reachable
            }

            AdjacentTileHelper.AdjacentPath result = adjacent.get();

            // Additional check: can we actually interact from that adjacent tile?
            if (!reachability.canInteract(result.getDestination(), npcPos)) {
                return null;
            }

            int cost = Math.max(1, result.getPath().size());
            return new NpcPathResult(cost, result.getPath(), result.getDestination());
        }

        // Long-range: use web navigation
        NavigationPath navPath = webWalker.findUnifiedPath(playerPos, npcPos);
        if (navPath == null || navPath.isEmpty()) {
            return null;
        }

        int cost = Math.max(1, navPath.getTotalCostTicks());
        return new NpcPathResult(cost, Collections.emptyList(), null);
    }

    // ========================================================================
    // NPC Finding - Ranged/Magic (Within Weapon Range)
    // ========================================================================

    /**
     * Find the nearest NPC attackable with ranged/magic weapons.
     *
     * <p>Unlike melee, the player doesn't need to be adjacent - they just need
     * to be within weapon range with line of sight to the target.
     *
     * @param playerPos   player's current position
     * @param npcIds      set of acceptable NPC IDs
     * @param radius      maximum search radius in tiles
     * @param weaponRange weapon's attack range (e.g., 7 for most ranged, 10 for magic)
     * @return search result with NPC and attack position if found
     */
    public Optional<NpcSearchResult> findNearestAttackableNpc(
            WorldPoint playerPos,
            Collection<Integer> npcIds,
            int radius,
            int weaponRange) {

        return findNearestAttackableNpc(playerPos, npcIds, null, radius, weaponRange);
    }

    /**
     * Find the nearest NPC attackable with ranged/magic weapons, filtered by name.
     *
     * @param playerPos   player's current position
     * @param npcIds      set of acceptable NPC IDs
     * @param npcName     optional NPC name filter (null to skip name check)
     * @param radius      maximum search radius in tiles
     * @param weaponRange weapon's attack range (e.g., 7 for most ranged, 10 for magic)
     * @return search result with NPC and attack position if found
     */
    public Optional<NpcSearchResult> findNearestAttackableNpc(
            WorldPoint playerPos,
            Collection<Integer> npcIds,
            String npcName,
            int radius,
            int weaponRange) {

        if (playerPos == null || npcIds == null || npcIds.isEmpty() || weaponRange < 1) {
            return Optional.empty();
        }

        NPC bestNpc = null;
        int bestPathCost = Integer.MAX_VALUE;
        int bestDistance = Integer.MAX_VALUE;
        List<WorldPoint> bestPath = Collections.emptyList();
        WorldPoint bestAttackPosition = null;

        for (NPC npc : client.getNpcs()) {
            if (npc == null || npc.isDead()) {
                continue;
            }

            // Check ID match
            if (!npcIds.contains(npc.getId())) {
                continue;
            }

            // Check name match if specified
            if (npcName != null && !npcName.equals(npc.getName())) {
                continue;
            }

            WorldPoint npcPos = npc.getWorldLocation();
            if (npcPos == null || npcPos.getPlane() != playerPos.getPlane()) {
                continue;
            }

            int tileDistance = playerPos.distanceTo(npcPos);
            if (tileDistance > radius) {
                continue;
            }

            // Check if attackable from current position or nearby
            NpcPathResult pathResult = computeNpcRangedPathCost(playerPos, npcPos, tileDistance, weaponRange);
            if (pathResult == null || pathResult.cost < 0) {
                continue;
            }

            boolean better =
                    pathResult.cost < bestPathCost ||
                    (pathResult.cost == bestPathCost && tileDistance < bestDistance);

            if (better) {
                bestNpc = npc;
                bestPathCost = pathResult.cost;
                bestDistance = tileDistance;
                bestPath = pathResult.path;
                bestAttackPosition = pathResult.adjacentTile;
            }
        }

        if (bestNpc == null) {
            return Optional.empty();
        }

        return Optional.of(new NpcSearchResult(bestNpc, bestPath, bestAttackPosition, bestPathCost));
    }

    /**
     * Compute path cost for ranged/magic NPC interaction.
     */
    private NpcPathResult computeNpcRangedPathCost(WorldPoint playerPos, WorldPoint npcPos, int tileDistance, int weaponRange) {
        // Already within range - check line of sight
        if (tileDistance <= weaponRange) {
            Optional<WorldPoint> attackPos = reachability.findAttackablePosition(playerPos, npcPos, weaponRange);
            if (attackPos.isPresent()) {
                // Can attack from current position or adjacent tile
                WorldPoint pos = attackPos.get();
                if (pos.equals(playerPos)) {
                    return new NpcPathResult(1, Collections.singletonList(playerPos), playerPos);
                }
                // Need to move one tile
                List<WorldPoint> path = pathFinder.findPath(playerPos, pos, true);
                if (!path.isEmpty()) {
                    return new NpcPathResult(path.size(), path, pos);
                }
            }
        }

        // Need to get closer - find a position within weapon range
        if (tileDistance <= LOCAL_NAV_MAX_DISTANCE + weaponRange) {
            // Try to find a tile within weapon range that we can path to
            WorldPoint bestTile = null;
            int bestCost = Integer.MAX_VALUE;
            List<WorldPoint> bestPath = Collections.emptyList();

            // Search outward from the NPC for attackable positions
            for (int r = Math.max(1, tileDistance - weaponRange - 2); r <= Math.min(LOCAL_NAV_MAX_DISTANCE, tileDistance + 2); r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = -r; dy <= r; dy++) {
                        if (Math.abs(dx) != r && Math.abs(dy) != r) continue; // Only perimeter

                        WorldPoint candidate = new WorldPoint(
                                npcPos.getX() + dx,
                                npcPos.getY() + dy,
                                npcPos.getPlane()
                        );

                        int distToNpc = candidate.distanceTo(npcPos);
                        if (distToNpc > weaponRange) continue;

                        // Check line of sight from candidate
                        if (!reachability.hasLineOfSight(candidate, npcPos)) continue;

                        // Check if we can path there
                        List<WorldPoint> path = pathFinder.findPath(playerPos, candidate, true);
                        if (path.isEmpty()) continue;

                        if (path.size() < bestCost) {
                            bestCost = path.size();
                            bestPath = path;
                            bestTile = candidate;
                        }
                    }
                }

                // Found a valid position, stop searching
                if (bestTile != null) break;
            }

            if (bestTile != null) {
                return new NpcPathResult(bestCost, bestPath, bestTile);
            }
        }

        // Long-range: use web navigation to get close, then local validation will happen
        NavigationPath navPath = webWalker.findUnifiedPath(playerPos, npcPos);
        if (navPath == null || navPath.isEmpty()) {
            return null;
        }

        int cost = Math.max(1, navPath.getTotalCostTicks());
        return new NpcPathResult(cost, Collections.emptyList(), null);
    }

    // ========================================================================
    // Simple Finding (Distance-Only, for Destination Resolution)
    // ========================================================================

    /**
     * Find the nearest object by distance only (no reachability validation).
     *
     * <p>Use this only for destination resolution in walk tasks where the actual
     * reachability will be validated upon arrival.
     *
     * @param playerPos player's current position
     * @param objectIds set of acceptable object IDs
     * @param radius    maximum search radius in tiles
     * @return the nearest object if found
     */
    public Optional<TileObject> findNearestObjectByDistance(
            WorldPoint playerPos,
            Collection<Integer> objectIds,
            int radius) {

        if (playerPos == null || objectIds == null || objectIds.isEmpty()) {
            return Optional.empty();
        }

        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int playerPlane = playerPos.getPlane();

        TileObject nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (int x = 0; x < Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[playerPlane][x][y];
                if (tile == null) {
                    continue;
                }

                TileObject obj = findMatchingObjectOnTile(tile, objectIds);
                if (obj == null) {
                    continue;
                }

                WorldPoint objPos = TileObjectUtils.getWorldPoint(obj);
                if (objPos == null) {
                    continue;
                }

                int distance = playerPos.distanceTo(objPos);
                if (distance <= radius && distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = obj;
                }
            }
        }

        return Optional.ofNullable(nearest);
    }

    /**
     * Find the nearest NPC by distance only (no reachability validation).
     *
     * <p>Use this only for destination resolution in walk tasks where the actual
     * reachability will be validated upon arrival.
     *
     * @param playerPos player's current position
     * @param npcIds    set of acceptable NPC IDs
     * @param radius    maximum search radius in tiles
     * @return the nearest NPC if found
     */
    public Optional<NPC> findNearestNpcByDistance(
            WorldPoint playerPos,
            Collection<Integer> npcIds,
            int radius) {

        return findNearestNpcByDistance(playerPos, npcIds, null, radius);
    }

    /**
     * Find the nearest NPC by distance only, filtered by name.
     *
     * @param playerPos player's current position
     * @param npcIds    set of acceptable NPC IDs
     * @param npcName   optional NPC name filter (null to skip name check)
     * @param radius    maximum search radius in tiles
     * @return the nearest NPC if found
     */
    public Optional<NPC> findNearestNpcByDistance(
            WorldPoint playerPos,
            Collection<Integer> npcIds,
            String npcName,
            int radius) {

        if (playerPos == null || npcIds == null || npcIds.isEmpty()) {
            return Optional.empty();
        }

        NPC nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (NPC npc : client.getNpcs()) {
            if (npc == null || npc.isDead()) {
                continue;
            }

            if (!npcIds.contains(npc.getId())) {
                continue;
            }

            if (npcName != null && !npcName.equals(npc.getName())) {
                continue;
            }

            WorldPoint npcPos = npc.getWorldLocation();
            if (npcPos == null || npcPos.getPlane() != playerPos.getPlane()) {
                continue;
            }

            int distance = playerPos.distanceTo(npcPos);
            if (distance <= radius && distance < nearestDistance) {
                nearestDistance = distance;
                nearest = npc;
            }
        }

        return Optional.ofNullable(nearest);
    }

    // ========================================================================
    // Result Classes
    // ========================================================================

    /**
     * Result of an object search with reachability information.
     */
    @Value
    public static class ObjectSearchResult {
        TileObject object;
        List<WorldPoint> path;
        WorldPoint adjacentTile;
        int pathCost;
    }

    /**
     * Result of an NPC search with reachability information.
     */
    @Value
    public static class NpcSearchResult {
        NPC npc;
        List<WorldPoint> path;
        WorldPoint adjacentTile; // For melee: adjacent tile. For ranged: attack position.
        int pathCost;
    }

    /**
     * Internal result for object path computation.
     */
    private static class ObjectPathResult {
        final int cost;
        final List<WorldPoint> path;
        final WorldPoint adjacentTile;

        ObjectPathResult(int cost, List<WorldPoint> path, WorldPoint adjacentTile) {
            this.cost = cost;
            this.path = path;
            this.adjacentTile = adjacentTile;
        }
    }

    /**
     * Internal result for NPC path computation.
     */
    private static class NpcPathResult {
        final int cost;
        final List<WorldPoint> path;
        final WorldPoint adjacentTile;

        NpcPathResult(int cost, List<WorldPoint> path, WorldPoint adjacentTile) {
            this.cost = cost;
            this.path = path;
            this.adjacentTile = adjacentTile;
        }
    }
}

