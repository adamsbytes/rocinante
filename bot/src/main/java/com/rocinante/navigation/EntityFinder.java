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
 * <p>Object finding uses {@link SpatialObjectIndex} for efficient O(1) grid-based
 * lookups instead of full scene scans.
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
 * @see SpatialObjectIndex
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
    private final SpatialObjectIndex spatialObjectIndex;

    @Inject
    public EntityFinder(Client client, Reachability reachability, CollisionService collisionService,
                       Provider<NavigationService> navigationServiceProvider,
                       SpatialObjectIndex spatialObjectIndex) {
        this.client = client;
        this.reachability = reachability;
        this.collisionService = collisionService;
        this.navigationServiceProvider = navigationServiceProvider;
        this.spatialObjectIndex = spatialObjectIndex;
    }

    private NavigationService getNavigationService() {
        return navigationServiceProvider.get();
    }

    // ========================================================================
    // Object Finding
    // ========================================================================

    /**
     * Initial candidates to compute path cost for (after sorting by distance).
     * If none are reachable, will continue checking remaining candidates.
     */
    private static final int INITIAL_PATH_COST_CANDIDATES = 10;

    /**
     * Find the nearest reachable object matching the specified IDs.
     *
     * <p>Uses spatial index for efficient object lookup:
     * <ol>
     *   <li>Query spatial index for objects matching IDs within radius</li>
     *   <li>Sort by Chebyshev distance</li>
     *   <li>Compute actual path cost only for top N candidates</li>
     * </ol>
     *
     * <p>This ensures O(1) grid lookup + small list scan instead of O(n) scene scan.
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

        // Phase 1: Query spatial index for matching objects
        List<SpatialObjectIndex.ObjectEntry> indexResults = 
            spatialObjectIndex.findObjectsNearby(playerPos, radius, objectIds);

        if (indexResults.isEmpty()) {
            return Optional.empty();
        }

        // Convert to candidates with visibility info
        List<ObjectCandidate> candidates = new ArrayList<>(indexResults.size());
        for (SpatialObjectIndex.ObjectEntry entry : indexResults) {
            TileObject obj = entry.getObject();
            boolean visible = obj.getClickbox() != null;
            candidates.add(new ObjectCandidate(obj, entry.getPosition(), entry.getDistance(), visible));
        }

        // Phase 2: Sort by Chebyshev distance (visible objects preferred at same distance)
        candidates.sort((a, b) -> {
            if (a.distance != b.distance) {
                return Integer.compare(a.distance, b.distance);
            }
            // Prefer visible at same distance
            if (a.visible != b.visible) {
                return a.visible ? -1 : 1;
            }
            return 0;
        });

        // Phase 3: Compute actual path cost for candidates
        // Start with initial batch, continue if none reachable
        Map<String, Set<WorldPoint>> footprintCache = new HashMap<>();
        int initialBatch = Math.min(INITIAL_PATH_COST_CANDIDATES, candidates.size());

        TileObject bestObject = null;
        int bestPathCost = Integer.MAX_VALUE;
        boolean bestVisible = false;
        WorldPoint bestAdjacentTile = null;

        // Check initial batch
        int checkedCount = 0;
        for (int i = 0; i < initialBatch; i++) {
            ObjectCandidate candidate = candidates.get(i);
            checkedCount++;

            ObjectPathResult pathResult = computeObjectPathCost(ctx, playerPos, candidate.object, footprintCache);
            if (pathResult == null || pathResult.cost < 0) {
                log.debug("Object {} at {} rejected: unreachable",
                        candidate.object.getId(), candidate.position);
                continue;
            }

            boolean better =
                    pathResult.cost < bestPathCost ||
                    (pathResult.cost == bestPathCost && candidate.visible && !bestVisible);

            if (better) {
                bestObject = candidate.object;
                bestPathCost = pathResult.cost;
                bestVisible = candidate.visible;
                bestAdjacentTile = pathResult.adjacentTile;
            }
        }

        // If none reachable in initial batch, continue checking remaining candidates
        if (bestObject == null && checkedCount < candidates.size()) {
            log.debug("No reachable object in first {} candidates, checking remaining {}", 
                    checkedCount, candidates.size() - checkedCount);
            
            for (int i = checkedCount; i < candidates.size(); i++) {
                ObjectCandidate candidate = candidates.get(i);

                ObjectPathResult pathResult = computeObjectPathCost(ctx, playerPos, candidate.object, footprintCache);
                if (pathResult == null || pathResult.cost < 0) {
                    log.debug("Object {} at {} rejected: unreachable",
                            candidate.object.getId(), candidate.position);
                    continue;
                }

                // Found a reachable one - use it (already sorted by distance)
                bestObject = candidate.object;
                bestPathCost = pathResult.cost;
                bestVisible = candidate.visible;
                bestAdjacentTile = pathResult.adjacentTile;
                break; // Take the first reachable one from remaining
            }
        }

        if (bestObject == null) {
            return Optional.empty();
        }

        return Optional.of(new ObjectSearchResult(bestObject, Collections.emptyList(), bestAdjacentTile, bestPathCost));
    }

    /**
     * Candidate object for path cost computation.
     */
    private static class ObjectCandidate {
        final TileObject object;
        final WorldPoint position;
        final int distance;
        final boolean visible;

        ObjectCandidate(TileObject object, WorldPoint position, int distance, boolean visible) {
            this.object = object;
            this.position = position;
            this.distance = distance;
            this.visible = visible;
        }
    }

    /**
     * Calculate Chebyshev distance (max of |dx|, |dy|).
     */
    private static int chebyshevDistance(WorldPoint a, WorldPoint b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        return Math.max(dx, dy);
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
        NavigationService navService = getNavigationService();

        // Collect all valid adjacent tiles (not blocked, not in footprint)
        List<WorldPoint> candidates = new ArrayList<>();
        int blockedCount = 0;
        
        Set<WorldPoint> seen = new HashSet<>();
        for (WorldPoint footprintTile : footprint) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;

                    WorldPoint adjacentTile = new WorldPoint(
                            footprintTile.getX() + dx,
                            footprintTile.getY() + dy,
                            footprintTile.getPlane()
                    );

                    // Skip duplicates (tile adjacent to multiple footprint tiles)
                    if (!seen.add(adjacentTile)) {
                        continue;
                    }

                    // Skip if inside the footprint
                    if (footprint.contains(adjacentTile)) {
                        continue;
                    }

                    // Check if this tile is blocked (can't stand there)
                    if (collisionService.isBlocked(adjacentTile)) {
                        blockedCount++;
                        continue;
                    }

                    candidates.add(adjacentTile);
                }
            }
        }

        if (candidates.isEmpty()) {
            log.debug("No valid adjacent tiles: footprint={} tiles, blocked={}", footprint.size(), blockedCount);
            return null;
                    }

        // Sort by straight-line distance from player (closest first)
        candidates.sort(Comparator.comparingInt(tile -> chebyshevDistance(playerPos, tile)));

        // Check path cost in order - return first reachable one
        int noPathCount = 0;
        for (WorldPoint adjacentTile : candidates) {
                    OptionalInt pathCostOpt = navService.getPathCost(ctx, playerPos, adjacentTile);
            if (pathCostOpt.isPresent()) {
                    int pathCost = pathCostOpt.getAsInt();
                log.debug("Found reachable adjacent tile: {} (path cost: {}, checked {} of {} candidates)",
                        adjacentTile, pathCost + 1, noPathCount + 1, candidates.size());
                return new ObjectPathResult(pathCost + 1, adjacentTile);
        }
            noPathCount++;
        }

        log.debug("No reachable adjacent tile: footprint={} tiles, blocked={}, noPath={}",
                footprint.size(), blockedCount, noPathCount);
        return null;
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
     * <p>Uses a two-phase approach for performance:
     * <ol>
     *   <li>Collect all matching NPCs and sort by Chebyshev distance</li>
     *   <li>Compute actual path cost only for top N candidates</li>
     * </ol>
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

        // Phase 1: Collect candidates with distance
        List<NpcCandidate> candidates = new ArrayList<>();

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

            int chebyshevDist = chebyshevDistance(playerPos, npcPos);
            if (chebyshevDist > radius) {
                continue;
            }

            candidates.add(new NpcCandidate(npc, npcPos, chebyshevDist));
        }

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // Phase 2: Sort by Chebyshev distance
        candidates.sort(Comparator.comparingInt(c -> c.distance));

        // Phase 3: Compute actual path cost for candidates
        int initialBatch = Math.min(INITIAL_PATH_COST_CANDIDATES, candidates.size());

        NPC bestNpc = null;
        int bestPathCost = Integer.MAX_VALUE;
        WorldPoint bestAdjacentTile = null;

        int checkedCount = 0;
        for (int i = 0; i < initialBatch; i++) {
            NpcCandidate candidate = candidates.get(i);
            checkedCount++;

            NpcPathResult pathResult = computeNpcMeleePathCost(ctx, playerPos, candidate.position, candidate.distance);
            if (pathResult == null || pathResult.cost < 0) {
                continue;
            }

            if (pathResult.cost < bestPathCost) {
                bestNpc = candidate.npc;
                bestPathCost = pathResult.cost;
                bestAdjacentTile = pathResult.adjacentTile;
            }
        }

        // If none reachable in initial batch, continue checking remaining
        if (bestNpc == null && checkedCount < candidates.size()) {
            for (int i = checkedCount; i < candidates.size(); i++) {
                NpcCandidate candidate = candidates.get(i);

                NpcPathResult pathResult = computeNpcMeleePathCost(ctx, playerPos, candidate.position, candidate.distance);
                if (pathResult == null || pathResult.cost < 0) {
                    continue;
                }

                bestNpc = candidate.npc;
                bestPathCost = pathResult.cost;
                bestAdjacentTile = pathResult.adjacentTile;
                break;
            }
        }

        if (bestNpc == null) {
            return Optional.empty();
        }

        return Optional.of(new NpcSearchResult(bestNpc, Collections.emptyList(), bestAdjacentTile, bestPathCost));
    }

    /**
     * Candidate NPC for path cost computation.
     */
    private static class NpcCandidate {
        final NPC npc;
        final WorldPoint position;
        final int distance;

        NpcCandidate(NPC npc, WorldPoint position, int distance) {
            this.npc = npc;
            this.position = position;
            this.distance = distance;
        }
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
     * <p>Uses a two-phase approach for performance:
     * <ol>
     *   <li>Collect all matching NPCs and sort by Chebyshev distance</li>
     *   <li>Compute actual path cost only for top N candidates</li>
     * </ol>
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

        // Phase 1: Collect candidates with distance
        List<NpcCandidate> candidates = new ArrayList<>();

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

            int chebyshevDist = chebyshevDistance(playerPos, npcPos);
            if (chebyshevDist > radius) {
                continue;
            }

            candidates.add(new NpcCandidate(npc, npcPos, chebyshevDist));
        }

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // Phase 2: Sort by Chebyshev distance
        candidates.sort(Comparator.comparingInt(c -> c.distance));

        // Phase 3: Compute actual path cost for candidates
        int initialBatch = Math.min(INITIAL_PATH_COST_CANDIDATES, candidates.size());

        NPC bestNpc = null;
        int bestPathCost = Integer.MAX_VALUE;
        WorldPoint bestAttackPosition = null;

        int checkedCount = 0;
        for (int i = 0; i < initialBatch; i++) {
            NpcCandidate candidate = candidates.get(i);
            checkedCount++;

            NpcPathResult pathResult = computeNpcRangedPathCost(ctx, playerPos, candidate.position, candidate.distance, weaponRange);
            if (pathResult == null || pathResult.cost < 0) {
                continue;
            }

            if (pathResult.cost < bestPathCost) {
                bestNpc = candidate.npc;
                bestPathCost = pathResult.cost;
                bestAttackPosition = pathResult.adjacentTile;
            }
        }

        // If none reachable in initial batch, continue checking remaining
        if (bestNpc == null && checkedCount < candidates.size()) {
            for (int i = checkedCount; i < candidates.size(); i++) {
                NpcCandidate candidate = candidates.get(i);

                NpcPathResult pathResult = computeNpcRangedPathCost(ctx, playerPos, candidate.position, candidate.distance, weaponRange);
                if (pathResult == null || pathResult.cost < 0) {
                    continue;
                }

                bestNpc = candidate.npc;
                bestPathCost = pathResult.cost;
                bestAttackPosition = pathResult.adjacentTile;
                break;
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
     * <p>Uses spatial index for efficient lookup. Use this only for destination 
     * resolution in walk tasks where the actual reachability will be validated 
     * upon arrival.
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

        // Use spatial index for efficient lookup
        List<SpatialObjectIndex.ObjectEntry> results = 
            spatialObjectIndex.findObjectsNearby(playerPos, radius, objectIds);

        if (results.isEmpty()) {
            return Optional.empty();
        }

        // Find nearest by distance
        TileObject nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (SpatialObjectIndex.ObjectEntry entry : results) {
            if (entry.getDistance() < nearestDistance) {
                nearestDistance = entry.getDistance();
                nearest = entry.getObject();
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
