package com.rocinante.navigation;

import com.rocinante.tasks.TaskContext;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.*;

/**
 * Centralized entity finding utility with built-in reachability validation.
 *
 * <p>Uses collision data (via {@link CollisionService}) and path cost calculations
 * (via {@link NavigationService}) to ensure selected targets are actually reachable.
 * This prevents selecting targets across fences, rivers, or other barriers.
 *
 * <p>All methods return {@link Optional} results containing not just the entity,
 * but also the adjacent tile information needed for interaction.
 *
 * <p><b>Path Cost Calculation:</b> All path costs are computed using actual pathfinding
 * via NavigationService, not straight-line distance. This ensures the "nearest" object
 * is the one with the shortest actual path, accounting for fences, walls, and rivers.
 *
 * @see Reachability
 * @see CollisionService
 * @see NavigationService
 */
@Slf4j
@Singleton
public class EntityFinder {

    /**
     * Maximum distance for local entity search.
     */
    private static final int MAX_SEARCH_RADIUS = 50;

    private final Client client;
    private final Reachability reachability;
    private final CollisionService collisionService;
    private final Provider<NavigationService> navigationServiceProvider;

    @Inject
    public EntityFinder(Client client, Reachability reachability, CollisionService collisionService,
                       Provider<NavigationService> navigationServiceProvider) {
        this.client = client;
        this.reachability = reachability;
        this.collisionService = collisionService;
        this.navigationServiceProvider = navigationServiceProvider;
    }

    private NavigationService getNavigationService() {
        return navigationServiceProvider.get();
    }

    // ========================================================================
    // Object Finding
    // ========================================================================

    /**
     * Find the nearest reachable object matching the specified IDs.
     *
     * <p>Uses collision-aware reachability and actual path costs (not straight-line distance)
     * to ensure the returned object can actually be reached. Objects behind fences, rivers,
     * or other blocking terrain are rejected.
     *
     * @param ctx       TaskContext for path cost calculations
     * @param playerPos player's current position
     * @param objectIds set of acceptable object IDs
     * @param radius    maximum search radius in tiles
     * @return search result with object and adjacent tile if found
     */
    public Optional<ObjectSearchResult> findNearestReachableObject(
            TaskContext ctx,
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

                // Compute reachability and cost using actual pathfinding
                ObjectPathResult pathResult = computeObjectPathCost(ctx, playerPos, obj, footprintCache);
                if (pathResult == null || pathResult.cost < 0) {
                    log.debug("Object {} at {} rejected: path cost={}, reason={}",
                            obj.getId(), objPos, 
                            pathResult != null ? pathResult.cost : "N/A",
                            pathResult == null ? "no path result" : "negative cost (unreachable)");
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
                    bestAdjacentTile = pathResult.adjacentTile;
                }
            }
        }

        if (bestObject == null) {
            return Optional.empty();
        }

        return Optional.of(new ObjectSearchResult(bestObject, Collections.emptyList(), bestAdjacentTile, bestPathCost));
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
     * Uses actual pathfinding via NavigationService, not straight-line distance.
     */
    private ObjectPathResult computeObjectPathCost(TaskContext ctx,
                                                   WorldPoint playerPos,
                                                   TileObject target,
                                                   Map<String, Set<WorldPoint>> footprintCache) {
        WorldPoint targetPos = TileObjectUtils.getWorldPoint(target);
        if (targetPos == null) {
            return null;
        }

        Set<WorldPoint> footprint = getCachedFootprint(target, footprintCache);

        // Standing on the object
        if (playerPos.equals(targetPos) || footprint.contains(playerPos)) {
            return new ObjectPathResult(1, playerPos);
        }

        // Check each tile in the footprint for adjacency
        for (WorldPoint tile : footprint) {
            // Check all 8 directions around the footprint tile
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;

                    WorldPoint adjacentTile = new WorldPoint(
                            tile.getX() + dx,
                            tile.getY() + dy,
                            tile.getPlane()
                    );

                    // Check if player is at this adjacent tile
                    if (adjacentTile.equals(playerPos)) {
                        // Check if player can interact from here
                        if (reachability.canInteract(playerPos, target)) {
                            return new ObjectPathResult(1, playerPos);
                        }
                    }
                }
            }
        }

        // Player not adjacent - find best adjacent tile to walk to using actual path costs
        ObjectPathResult bestResult = findBestAdjacentTileWithPathCost(ctx, playerPos, footprint);
        return bestResult;
    }

    /**
     * Find the best adjacent tile to an object's footprint using actual path costs.
     * Returns null if no reachable adjacent tile exists.
     */
    private ObjectPathResult findBestAdjacentTileWithPathCost(TaskContext ctx, WorldPoint playerPos, Set<WorldPoint> footprint) {
        WorldPoint bestTile = null;
        int bestPathCost = Integer.MAX_VALUE;

        NavigationService navService = getNavigationService();

        for (WorldPoint footprintTile : footprint) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;

                    WorldPoint adjacentTile = new WorldPoint(
                            footprintTile.getX() + dx,
                            footprintTile.getY() + dy,
                            footprintTile.getPlane()
                    );

                    // Skip if inside the footprint
                    if (footprint.contains(adjacentTile)) {
                        continue;
                    }

                    // Check if this tile is blocked
                    if (collisionService.isBlocked(adjacentTile)) {
                        continue;
                    }

                    // Check if we can interact from this adjacent tile
                    if (!canInteractFrom(adjacentTile, footprintTile)) {
                        continue;
                    }

                    // Get actual path cost using NavigationService
                    OptionalInt pathCostOpt = navService.getPathCost(ctx, playerPos, adjacentTile);
                    if (pathCostOpt.isEmpty()) {
                        // No path to this adjacent tile - skip it (unreachable)
                        continue;
                    }

                    int pathCost = pathCostOpt.getAsInt();
                    if (pathCost < bestPathCost) {
                        bestPathCost = pathCost;
                        bestTile = adjacentTile;
                    }
                }
            }
        }

        if (bestTile != null) {
            log.debug("Selected adjacent tile for object: {} (path cost: {}, from {} candidates)",
                    bestTile, bestPathCost + 1, footprint.size() * 8);
            // Add 1 for the interaction cost
            return new ObjectPathResult(bestPathCost + 1, bestTile);
        }

        return null; // Not reachable
    }

    /**
     * Check if interaction is possible from an adjacent tile to a footprint tile.
     * Always checks collision - fences can block interaction even between adjacent tiles.
     */
    private boolean canInteractFrom(WorldPoint adjacentTile, WorldPoint footprintTile) {
        return collisionService.canMoveTo(adjacentTile, footprintTile);
    }

    private Set<WorldPoint> getCachedFootprint(TileObject target, Map<String, Set<WorldPoint>> cache) {
        // Cache key MUST include world position - two objects with same ID at different locations
        // have different footprints. Without position, Tree A's footprint could be reused for Tree B,
        // causing wrong adjacent tile calculations.
        WorldPoint pos = TileObjectUtils.getWorldPoint(target);
        String key;
        if (pos != null) {
            key = target.getId() + ":" + getOrientationSafe(target) + ":" + 
                  pos.getX() + "," + pos.getY() + "," + pos.getPlane();
        } else {
            // Fallback for null position (shouldn't happen, but be safe)
            log.debug("getCachedFootprint: Null position for object ID {}, using identity hash as cache key",
                    target.getId());
            key = target.getId() + ":" + getOrientationSafe(target) + ":" + System.identityHashCode(target);
        }
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
     * NPCs behind fences or rivers are not selected. Uses actual path costs.
     *
     * @param ctx       TaskContext for path cost calculations
     * @param playerPos player's current position
     * @param npcIds    set of acceptable NPC IDs
     * @param radius    maximum search radius in tiles
     * @return search result with NPC and adjacent tile if found
     */
    public Optional<NpcSearchResult> findNearestReachableNpc(
            TaskContext ctx,
            WorldPoint playerPos,
            Collection<Integer> npcIds,
            int radius) {

        return findNearestReachableNpc(ctx, playerPos, npcIds, null, radius);
    }

    /**
     * Find the nearest reachable NPC for melee interaction, filtered by name.
     *
     * @param ctx       TaskContext for path cost calculations
     * @param playerPos player's current position
     * @param npcIds    set of acceptable NPC IDs
     * @param npcName   optional NPC name filter (null to skip name check)
     * @param radius    maximum search radius in tiles
     * @return search result with NPC and adjacent tile if found
     */
    public Optional<NpcSearchResult> findNearestReachableNpc(
            TaskContext ctx,
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

            // Compute path cost with reachability validation using actual pathfinding
            NpcPathResult pathResult = computeNpcMeleePathCost(ctx, playerPos, npcPos, tileDistance);
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
                bestAdjacentTile = pathResult.adjacentTile;
            }
        }

        if (bestNpc == null) {
            return Optional.empty();
        }

        return Optional.of(new NpcSearchResult(bestNpc, Collections.emptyList(), bestAdjacentTile, bestPathCost));
    }

    /**
     * Compute path cost for melee NPC interaction (requires adjacency).
     * Uses actual pathfinding via NavigationService, not straight-line distance.
     */
    private NpcPathResult computeNpcMeleePathCost(TaskContext ctx, WorldPoint playerPos, WorldPoint npcPos, int tileDistance) {
        // Already adjacent
        if (tileDistance <= 1) {
            if (reachability.canInteract(playerPos, npcPos)) {
                return new NpcPathResult(1, playerPos);
            }
            return null; // Adjacent but blocked by collision
        }

        // Not adjacent - find best adjacent tile using actual path costs
        WorldPoint bestAdjacent = null;
        int bestPathCost = Integer.MAX_VALUE;

        NavigationService navService = getNavigationService();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;

                WorldPoint adjacentTile = new WorldPoint(
                        npcPos.getX() + dx,
                        npcPos.getY() + dy,
                        npcPos.getPlane()
                );

                // Check if this tile is blocked
                if (collisionService.isBlocked(adjacentTile)) {
                    continue;
                }

                // Check if we can interact from this tile
                if (!collisionService.canMoveTo(adjacentTile, npcPos)) {
                    continue;
                }

                // Get actual path cost using NavigationService
                OptionalInt pathCostOpt = navService.getPathCost(ctx, playerPos, adjacentTile);
                if (pathCostOpt.isEmpty()) {
                    // No path to this adjacent tile - skip it (unreachable)
                    continue;
                }

                int pathCost = pathCostOpt.getAsInt();
                if (pathCost < bestPathCost) {
                    bestPathCost = pathCost;
                    bestAdjacent = adjacentTile;
                }
            }
        }

        if (bestAdjacent != null) {
            // Add 1 for the interaction cost
            return new NpcPathResult(bestPathCost + 1, bestAdjacent);
        }

        return null;
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
     * @param ctx         TaskContext for path cost calculations
     * @param playerPos   player's current position
     * @param npcIds      set of acceptable NPC IDs
     * @param radius      maximum search radius in tiles
     * @param weaponRange weapon's attack range (e.g., 7 for most ranged, 10 for magic)
     * @return search result with NPC and attack position if found
     */
    public Optional<NpcSearchResult> findNearestAttackableNpc(
            TaskContext ctx,
            WorldPoint playerPos,
            Collection<Integer> npcIds,
            int radius,
            int weaponRange) {

        return findNearestAttackableNpc(ctx, playerPos, npcIds, null, radius, weaponRange);
    }

    /**
     * Find the nearest NPC attackable with ranged/magic weapons, filtered by name.
     *
     * @param ctx         TaskContext for path cost calculations
     * @param playerPos   player's current position
     * @param npcIds      set of acceptable NPC IDs
     * @param npcName     optional NPC name filter (null to skip name check)
     * @param radius      maximum search radius in tiles
     * @param weaponRange weapon's attack range (e.g., 7 for most ranged, 10 for magic)
     * @return search result with NPC and attack position if found
     */
    public Optional<NpcSearchResult> findNearestAttackableNpc(
            TaskContext ctx,
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

            // Check if attackable from current position or nearby using actual path costs
            NpcPathResult pathResult = computeNpcRangedPathCost(ctx, playerPos, npcPos, tileDistance, weaponRange);
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
                bestAttackPosition = pathResult.adjacentTile;
            }
        }

        if (bestNpc == null) {
            return Optional.empty();
        }

        return Optional.of(new NpcSearchResult(bestNpc, Collections.emptyList(), bestAttackPosition, bestPathCost));
    }

    /**
     * Compute path cost for ranged/magic NPC interaction.
     * Uses actual pathfinding via NavigationService, not straight-line distance.
     */
    private NpcPathResult computeNpcRangedPathCost(TaskContext ctx, WorldPoint playerPos, WorldPoint npcPos, int tileDistance, int weaponRange) {
        NavigationService navService = getNavigationService();

        // Already within range - check line of sight
        if (tileDistance <= weaponRange) {
            Optional<WorldPoint> attackPos = reachability.findAttackablePosition(playerPos, npcPos, weaponRange);
            if (attackPos.isPresent()) {
                WorldPoint pos = attackPos.get();
                if (pos.equals(playerPos)) {
                    return new NpcPathResult(1, playerPos);
                }
                // Need to move - get actual path cost
                OptionalInt pathCostOpt = navService.getPathCost(ctx, playerPos, pos);
                if (pathCostOpt.isPresent()) {
                    return new NpcPathResult(pathCostOpt.getAsInt() + 1, pos);
                }
            }
        }

        // Need to get closer - find a position within weapon range
        WorldPoint bestTile = null;
        int bestPathCost = Integer.MAX_VALUE;

        // Search outward from the NPC for attackable positions
        for (int r = Math.max(1, weaponRange - 2); r <= weaponRange; r++) {
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

                    // Check if tile is blocked
                    if (collisionService.isBlocked(candidate)) {
                        continue;
                    }

                    // Check line of sight from candidate
                    if (!reachability.hasLineOfSight(candidate, npcPos)) continue;

                    // Get actual path cost using NavigationService
                    OptionalInt pathCostOpt = navService.getPathCost(ctx, playerPos, candidate);
                    if (pathCostOpt.isEmpty()) {
                        // No path to this candidate - skip it (unreachable)
                        continue;
                    }

                    int pathCost = pathCostOpt.getAsInt();
                    if (pathCost < bestPathCost) {
                        bestPathCost = pathCost;
                        bestTile = candidate;
                    }
                }
            }

            // Found a valid position, stop searching
            if (bestTile != null) break;
        }

        if (bestTile != null) {
            return new NpcPathResult(bestPathCost + 1, bestTile);
        }

        return null;
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
        final WorldPoint adjacentTile;

        ObjectPathResult(int cost, WorldPoint adjacentTile) {
            this.cost = cost;
            this.adjacentTile = adjacentTile;
        }
    }

    /**
     * Internal result for NPC path computation.
     */
    private static class NpcPathResult {
        final int cost;
        final WorldPoint adjacentTile;

        NpcPathResult(int cost, WorldPoint adjacentTile) {
            this.cost = cost;
            this.adjacentTile = adjacentTile;
        }
    }
}
