package com.rocinante.navigation;

import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.TravelTask;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Centralized navigation API for all bot tasks.
 *
 * <p>This service provides:
 * <ul>
 *   <li>Resource-aware pathfinding with cost-based transport selection</li>
 *   <li>Path cost caching (invalidates on >10 tile movement)</li>
 *   <li>Entity finding (nearest reachable objects/NPCs)</li>
 *   <li>Collision validation (fence/wall/river checking)</li>
 *   <li>Obstacle detection and handling</li>
 * </ul>
 *
 * <h2>Account-Type Awareness</h2>
 * Transport costs are adjusted based on account type and resources:
 * <ul>
 *   <li>HCIM: +1980 tick penalty for law rune teleports, -10 for fairy rings</li>
 *   <li>Ironman: +40 tick penalty for law runes (when scarce), -10 for fairy rings</li>
 *   <li>Normal (broke): Gold-based penalties for charter ships, carpets</li>
 * </ul>
 *
 * <h2>Fence-Crossing Prevention</h2>
 * All entity finding uses actual path costs, not straight-line distance.
 * Objects/NPCs across fences/rivers are rejected as unreachable.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Request path (async)
 * nav.requestPath(ctx, playerPos, destination);
 * while (!nav.isPathReady()) { wait(); }
 * List<WorldPoint> path = nav.getCurrentPath();
 *
 * // Find nearest tree (uses cached path costs)
 * Optional<ObjectSearchResult> tree = nav.findNearestReachableObject(
 *     ctx, playerPos, Set.of(TREE_IDS), 20);
 *
 * // Check collision
 * if (!nav.canMoveTo(from, to)) {
 *     // Fence/wall blocks movement
 * }
 * }</pre>
 *
 * @see PathCostCache
 * @see CollisionService
 * @see ResourceAwareness
 */
@Slf4j
@Singleton
public class NavigationService {

    /**
     * Maximum time to wait for synchronous path cost computation (10 x 50ms polls = 500ms).
     */
    private static final int PATH_COMPUTATION_TIMEOUT_MS = 500;

    /**
     * Number of polling attempts for synchronous path cost.
     */
    private static final int MAX_POLL_ATTEMPTS = 10;

    /**
     * Sleep interval between polling attempts in milliseconds.
     */
    private static final int POLL_INTERVAL_MS = 50;

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final Provider<ShortestPathBridge> bridgeProvider;
    private final CollisionService collisionService;
    
    @Getter
    private final PathCostCache pathCostCache;
    
    private final Client client;

    // Internal components (not exposed to tasks)
    private final Provider<EntityFinder> entityFinderProvider;
    private final Provider<Reachability> reachabilityProvider;
    private final Provider<ObstacleHandler> obstacleHandlerProvider;
    
    @Getter
    private final TrainingSpotCache trainingSpotCache;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public NavigationService(
            Provider<ShortestPathBridge> bridgeProvider,
            CollisionService collisionService,
            PathCostCache pathCostCache,
            Client client,
            Provider<EntityFinder> entityFinderProvider,
            Provider<Reachability> reachabilityProvider,
            Provider<ObstacleHandler> obstacleHandlerProvider,
            TrainingSpotCache trainingSpotCache) {
        this.bridgeProvider = bridgeProvider;
        this.collisionService = collisionService;
        this.pathCostCache = pathCostCache;
        this.client = client;
        this.entityFinderProvider = entityFinderProvider;
        this.reachabilityProvider = reachabilityProvider;
        this.obstacleHandlerProvider = obstacleHandlerProvider;
        this.trainingSpotCache = trainingSpotCache;
    }

    /**
     * Get the ShortestPathBridge instance.
     */
    private ShortestPathBridge getBridge() {
        return bridgeProvider.get();
    }

    /**
     * Get the EntityFinder instance.
     */
    private EntityFinder getEntityFinder() {
        return entityFinderProvider.get();
    }

    /**
     * Get the Reachability instance (internal use only).
     */
    private Reachability getReachability() {
        return reachabilityProvider.get();
    }

    /**
     * Check if player can interact with a target object from adjacent position.
     *
     * @param playerPos player's current position
     * @param target    the target TileObject
     * @return true if interaction is possible
     */
    public boolean canInteract(WorldPoint playerPos, net.runelite.api.TileObject target) {
        Reachability reach = getReachability();
        return reach != null && reach.canInteract(playerPos, target);
    }

    /**
     * Get the footprint (set of tiles) occupied by an object.
     *
     * @param target the target TileObject
     * @return set of WorldPoints the object occupies
     */
    public java.util.Set<WorldPoint> getObjectFootprint(net.runelite.api.TileObject target) {
        Reachability reach = getReachability();
        if (reach != null) {
            return reach.getObjectFootprint(target);
        }
        WorldPoint pos = TileObjectUtils.getWorldPoint(target);
        return pos != null ? java.util.Collections.singleton(pos) : java.util.Collections.emptySet();
    }

    /**
     * Get the ObstacleHandler instance.
     */
    private ObstacleHandler getObstacleHandler() {
        return obstacleHandlerProvider.get();
    }

    // ========================================================================
    // Availability Check
    // ========================================================================

    // ========================================================================
    // Primary Pathfinding API
    // ========================================================================

    /**
     * Request a path with resource-aware transport costs.
     * This is an asynchronous operation - use {@link #isPathReady()} to poll.
     *
     * @param ctx TaskContext for resource awareness
     * @param start starting position
     * @param destination target position
     */
    public void requestPath(TaskContext ctx, WorldPoint start, WorldPoint destination) {
        requestPath(ctx, start, Set.of(destination));
    }

    /**
     * Request a path to any of multiple destinations with resource-aware transport costs.
     *
     * @param ctx TaskContext for resource awareness
     * @param start starting position
     * @param destinations set of possible target positions
     */
    public void requestPath(TaskContext ctx, WorldPoint start, Set<WorldPoint> destinations) {
        Map<String, Object> configOverride = buildTransportCostOverrides(ctx);
        getBridge().requestPath(start, destinations, configOverride);
    }

    /**
     * Check if pathfinding has completed.
     *
     * @return true if path is ready (or failed/timed out)
     */
    public boolean isPathReady() {
        return getBridge().isPathReady();
    }

    /**
     * Get the current computed path.
     * Call {@link #isPathReady()} first to ensure pathfinding is complete.
     *
     * @return list of world points forming the path, empty if no path available
     */
    public List<WorldPoint> getCurrentPath() {
        return getBridge().getCurrentPath();
    }

    /**
     * Clear the current path and cancel any pending pathfinding.
     */
    public void clearPath() {
        getBridge().clearPath();
    }

    /**
     * Analyze a path for transport segments (non-adjacent tile jumps).
     *
     * @param path the path to analyze
     * @return list of transport segments found in the path
     */
    public List<ShortestPathBridge.TransportSegment> analyzePathForTransports(List<WorldPoint> path) {
        return getBridge().analyzePathForTransports(path);
    }

    // ========================================================================
    // Synchronous Path Cost API
    // ========================================================================

    /**
     * Get path cost synchronously (BLOCKS up to 200ms if not cached).
     *
     * <p>This method first checks the cache. On a cache miss, it requests
     * a path from ShortestPath and blocks until the result is available
     * or the timeout expires.
     *
     * @param ctx TaskContext for resource awareness
     * @param start starting world point
     * @param end ending world point
     * @return path cost in tiles, or OptionalInt.empty() if unreachable or timeout
     */
    public OptionalInt getPathCost(TaskContext ctx, WorldPoint start, WorldPoint end) {
        if (start == null || end == null) {
            return OptionalInt.empty();
        }

        // Same tile = cost 1
        if (start.equals(end)) {
            return OptionalInt.of(1);
        }

        // Check cache first
        Optional<Integer> cached = pathCostCache.getPathCost(start, end);
        if (cached.isPresent()) {
            return OptionalInt.of(cached.get());
        }

        // Cache miss - compute synchronously (BLOCKS!)
        log.debug("Path cost cache miss: {} -> {}, computing...", start, end);
        requestPath(ctx, start, end);

        // Poll with timeout
        int attempts = 0;
        while (!getBridge().isPathReady() && attempts < MAX_POLL_ATTEMPTS) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Path computation interrupted");
                return OptionalInt.empty();
            }
            attempts++;
        }

        if (!getBridge().isPathReady()) {
            log.debug("Path computation timed out after {}ms: {} -> {}",
                    PATH_COMPUTATION_TIMEOUT_MS, start, end);
            return OptionalInt.empty();
        }

        List<WorldPoint> path = getBridge().getCurrentPath();
        int totalWaitMs = attempts * POLL_INTERVAL_MS;
        if (path.isEmpty()) {
            log.debug("No path found: {} -> {} - unreachable (waited {}ms)", start, end, totalWaitMs);
            return OptionalInt.empty();
        }

        // Cache and return
        pathCostCache.cachePathResult(start, end, path);
        log.debug("Path cost computed: {} -> {} = {} tiles (waited {}ms)", 
                start, end, path.size(), totalWaitMs);
        return OptionalInt.of(path.size());
    }

    // ========================================================================
    // Entity Finding (delegates to internal EntityFinder)
    // ========================================================================

    /**
     * Find the nearest reachable object matching the specified IDs.
     *
     * @param ctx TaskContext for path cost calculations
     * @param playerPos player's current position
     * @param objectIds set of acceptable object IDs
     * @param radius maximum search radius in tiles
     * @return search result with object and adjacent tile if found
     */
    public Optional<EntityFinder.ObjectSearchResult> findNearestReachableObject(
            TaskContext ctx,
            WorldPoint playerPos,
            Collection<Integer> objectIds,
            int radius) {
        return getEntityFinder().findNearestReachableObject(ctx, playerPos, objectIds, radius);
    }

    /**
     * Find the nearest reachable NPC matching the specified IDs.
     *
     * @param ctx TaskContext for path cost calculations
     * @param playerPos player's current position
     * @param npcIds set of acceptable NPC IDs
     * @param radius maximum search radius in tiles
     * @return search result with NPC and adjacent tile if found
     */
    public Optional<EntityFinder.NpcSearchResult> findNearestReachableNpc(
            TaskContext ctx,
            WorldPoint playerPos,
            Collection<Integer> npcIds,
            int radius) {
        return getEntityFinder().findNearestReachableNpc(ctx, playerPos, npcIds, radius);
    }

    /**
     * Find the nearest attackable NPC matching the specified IDs.
     *
     * @param ctx TaskContext for path cost calculations
     * @param playerPos player's current position
     * @param npcIds set of acceptable NPC IDs
     * @param radius maximum search radius in tiles
     * @param weaponRange attack range of current weapon
     * @return search result with NPC and adjacent tile if found
     */
    public Optional<EntityFinder.NpcSearchResult> findNearestAttackableNpc(
            TaskContext ctx,
            WorldPoint playerPos,
            Collection<Integer> npcIds,
            int radius,
            int weaponRange) {
        return getEntityFinder().findNearestAttackableNpc(ctx, playerPos, npcIds, radius, weaponRange);
    }

    // ========================================================================
    // Training Spot Ranking (Smart Training)
    // ========================================================================

    /**
     * Default search radius for training spot ranking.
     */
    private static final int DEFAULT_TRAINING_RADIUS = 25;

    /**
     * Maximum candidates to return from ranking.
     */
    private static final int MAX_RANKED_CANDIDATES = 10;

    /**
     * Rank nearby objects by training efficiency.
     *
     * <p>If banking: ranks by roundtrip cost (object → bank → object).
     * If not banking: ranks by path cost from reference point.
     *
     * <p>Results are cached for 7 days per location+objectIds+bankFlag.
     * Cache is shared across bot instances.
     *
     * @param ctx TaskContext for path calculations
     * @param objectIds Object IDs to find (uses scene scanning)
     * @param referencePoint Center of search area
     * @param searchRadius Tile radius to search
     * @param bankRequired Whether to optimize for banking roundtrip
     * @return Ordered list of candidates, index 0 = best efficiency
     */
    public List<RankedCandidate> rankTrainingCandidates(
            TaskContext ctx,
            Collection<Integer> objectIds,
            WorldPoint referencePoint,
            int searchRadius,
            boolean bankRequired) {

        if (objectIds == null || objectIds.isEmpty() || referencePoint == null) {
            return Collections.emptyList();
        }

        // Generate cache key
        int regionId = referencePoint.getRegionID();
        String cacheKey = TrainingSpotCache.trainingSpotKey(regionId, objectIds, bankRequired);

        // Check cache first
        Optional<List<RankedCandidate>> cached = trainingSpotCache.get(cacheKey);
        if (cached.isPresent()) {
            log.debug("Training spot cache hit for region {} with {} objects (banking={})",
                    regionId, objectIds.size(), bankRequired);
            return cached.get();
        }

        log.debug("Computing training spot rankings for region {} with {} objects (banking={})",
                regionId, objectIds.size(), bankRequired);

        // Find nearest bank if banking required
        WorldPoint bankPosition = null;
        if (bankRequired) {
            bankPosition = findNearestBank(referencePoint);
            if (bankPosition == null) {
                log.warn("Banking required but no bank found near {}", referencePoint);
                // Fall back to non-banking ranking
                bankRequired = false;
            }
        }

        // Scan scene for matching objects
        List<ObjectCandidate> candidates = scanForObjects(objectIds, referencePoint, searchRadius);
        if (candidates.isEmpty()) {
            log.debug("No objects found matching {} near {}", objectIds, referencePoint);
            return Collections.emptyList();
        }

        log.debug("Found {} candidate objects", candidates.size());

        // Compute costs for each candidate
        List<RankedCandidate> ranked;
        if (bankRequired && bankPosition != null) {
            ranked = rankWithBanking(ctx, candidates, bankPosition);
        } else {
            ranked = rankWithoutBanking(ctx, candidates, referencePoint);
        }

        // Sort by cost (ascending)
        ranked.sort(Comparator.comparingInt(RankedCandidate::cost));

        // Log efficiency distribution for debugging
        if (!ranked.isEmpty()) {
            log.debug("Training spot efficiency - Best: {} tiles, Median: {} tiles, Worst: {} tiles",
                    ranked.get(0).cost(),
                    ranked.get(ranked.size() / 2).cost(),
                    ranked.get(ranked.size() - 1).cost());
        }

        // Limit to top N
        if (ranked.size() > MAX_RANKED_CANDIDATES) {
            ranked = new ArrayList<>(ranked.subList(0, MAX_RANKED_CANDIDATES));
        }

        // Cache results
        if (!ranked.isEmpty()) {
            trainingSpotCache.put(cacheKey, ranked, bankPosition);
            log.info("Cached {} ranked training spots for region {} (best cost: {})",
                    ranked.size(), regionId, ranked.get(0).cost());
        }

        return ranked;
    }

    /**
     * Rank training candidates using default search radius.
     *
     * @param ctx TaskContext for path calculations
     * @param objectIds Object IDs to find
     * @param referencePoint Center of search area
     * @param bankRequired Whether to optimize for banking roundtrip
     * @return Ordered list of candidates
     */
    public List<RankedCandidate> rankTrainingCandidates(
            TaskContext ctx,
            Collection<Integer> objectIds,
            WorldPoint referencePoint,
            boolean bankRequired) {
        return rankTrainingCandidates(ctx, objectIds, referencePoint, DEFAULT_TRAINING_RADIUS, bankRequired);
    }

    /**
     * Internal candidate holder during scanning.
     */
    private record ObjectCandidate(WorldPoint position, int objectId) {}

    /**
     * Scan the current scene for objects matching the given IDs.
     */
    private List<ObjectCandidate> scanForObjects(
            Collection<Integer> objectIds,
            WorldPoint referencePoint,
            int searchRadius) {

        List<ObjectCandidate> candidates = new ArrayList<>();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int playerPlane = referencePoint.getPlane();

        Set<Integer> targetIds = objectIds instanceof Set ? (Set<Integer>) objectIds : new HashSet<>(objectIds);

        for (int x = 0; x < Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[playerPlane][x][y];
                if (tile == null) {
                    continue;
                }

                // Check game objects
                GameObject[] gameObjects = tile.getGameObjects();
                if (gameObjects != null) {
                    for (GameObject obj : gameObjects) {
                        if (obj != null && targetIds.contains(obj.getId())) {
                            WorldPoint objPos = obj.getWorldLocation();
                            if (objPos != null && objPos.distanceTo(referencePoint) <= searchRadius) {
                                candidates.add(new ObjectCandidate(objPos, obj.getId()));
                            }
                        }
                    }
                }

                // Check wall objects
                net.runelite.api.WallObject wallObj = tile.getWallObject();
                if (wallObj != null && targetIds.contains(wallObj.getId())) {
                    WorldPoint objPos = wallObj.getWorldLocation();
                    if (objPos != null && objPos.distanceTo(referencePoint) <= searchRadius) {
                        candidates.add(new ObjectCandidate(objPos, wallObj.getId()));
                    }
                }

                // Check ground objects
                net.runelite.api.GroundObject groundObj = tile.getGroundObject();
                if (groundObj != null && targetIds.contains(groundObj.getId())) {
                    WorldPoint objPos = groundObj.getWorldLocation();
                    if (objPos != null && objPos.distanceTo(referencePoint) <= searchRadius) {
                        candidates.add(new ObjectCandidate(objPos, groundObj.getId()));
                    }
                }
            }
        }

        return candidates;
    }

    /**
     * Rank candidates by roundtrip cost to bank.
     * Cost = (object → bank) + (bank → object)
     */
    private List<RankedCandidate> rankWithBanking(
            TaskContext ctx,
            List<ObjectCandidate> candidates,
            WorldPoint bankPosition) {

        List<RankedCandidate> ranked = new ArrayList<>(candidates.size());

        for (ObjectCandidate candidate : candidates) {
            // Cost from object to bank
            OptionalInt toBank = getPathCost(ctx, candidate.position(), bankPosition);
            if (toBank.isEmpty()) {
                log.debug("Skipping {} - no path to bank", candidate.position());
                continue;
            }

            // Cost from bank back to object
            OptionalInt fromBank = getPathCost(ctx, bankPosition, candidate.position());
            if (fromBank.isEmpty()) {
                log.debug("Skipping {} - no path from bank", candidate.position());
                continue;
            }

            int roundtripCost = toBank.getAsInt() + fromBank.getAsInt();
            int bankDistance = toBank.getAsInt();

            ranked.add(RankedCandidate.withBanking(
                    candidate.position(),
                    candidate.objectId(),
                    roundtripCost,
                    bankDistance
            ));
        }

        return ranked;
    }

    /**
     * Rank candidates by simple path cost from reference point.
     */
    private List<RankedCandidate> rankWithoutBanking(
            TaskContext ctx,
            List<ObjectCandidate> candidates,
            WorldPoint referencePoint) {

        List<RankedCandidate> ranked = new ArrayList<>(candidates.size());

        for (ObjectCandidate candidate : candidates) {
            // Cost from reference point to object
            OptionalInt cost = getPathCost(ctx, referencePoint, candidate.position());
            if (cost.isEmpty()) {
                log.debug("Skipping {} - unreachable from {}", candidate.position(), referencePoint);
                continue;
            }

            ranked.add(RankedCandidate.withoutBanking(
                    candidate.position(),
                    candidate.objectId(),
                    cost.getAsInt()
            ));
        }

        return ranked;
    }

    // ========================================================================
    // Collision API (delegates to CollisionService)
    // ========================================================================

    /**
     * Check if movement is allowed between two adjacent tiles.
     *
     * @param from source tile
     * @param to destination tile (must be adjacent)
     * @return true if movement is allowed, false if blocked
     */
    public boolean canMoveTo(WorldPoint from, WorldPoint to) {
        return collisionService.canMoveTo(from, to);
    }

    /**
     * Check if a tile is completely blocked.
     *
     * @param point world point to check
     * @return true if blocked
     */
    public boolean isBlocked(WorldPoint point) {
        return collisionService.isBlocked(point);
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
        return collisionService.isBlocked(x, y, z);
    }

    /**
     * Check if the player can interact with a target from their current position.
     *
     * @param playerPos player's world position
     * @param targetPos target's world position
     * @return true if interaction is possible
     */
    public boolean canInteractWith(WorldPoint playerPos, WorldPoint targetPos) {
        return collisionService.canInteractWith(playerPos, targetPos);
    }

    // ========================================================================
    // Obstacle Handling (delegates to internal ObstacleHandler)
    // ========================================================================

    /**
     * Find an obstacle blocking movement between two points.
     *
     * @param from source position
     * @param to destination position
     * @return detected obstacle if found, empty otherwise
     */
    public Optional<ObstacleHandler.DetectedObstacle> findBlockingObstacle(WorldPoint from, WorldPoint to) {
        return getObstacleHandler().findBlockingObstacle(from, to);
    }

    /**
     * Create a task to handle a blocking obstacle.
     *
     * @param obstacle the detected obstacle to handle
     * @return task to handle the obstacle, or empty if not possible
     */
    public Optional<com.rocinante.tasks.impl.InteractObjectTask> createHandleTask(ObstacleHandler.DetectedObstacle obstacle) {
        return getObstacleHandler().createHandleTask(obstacle);
    }

    /**
     * Handle a blocking obstacle by finding and creating a task for it.
     *
     * @param from source position
     * @param to destination position
     * @return task to handle any blocking obstacle, or empty if none found
     */
    public Optional<com.rocinante.tasks.impl.InteractObjectTask> handleBlockingObstacle(WorldPoint from, WorldPoint to) {
        return getObstacleHandler().handleBlockingObstacle(from, to);
    }

    // ========================================================================
    // Transport Creation
    // ========================================================================

    /**
     * Create a TravelTask from a transport segment.
     *
     * @param segment the transport segment from path analysis
     * @return TravelTask for the transport, or empty if not supported
     */
    public Optional<TravelTask> createTravelTask(ShortestPathBridge.TransportSegment segment) {
        ShortestPathBridge.TransportInfo info = segment.getTransport();
        if (info == null) {
            return Optional.empty();
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put("travel_type", mapTransportType(info.getType()));
        metadata.put("display_info", info.getDisplayInfo() != null ? info.getDisplayInfo() : "");

        ShortestPathBridge.ParsedMenuInfo menu = info.parseObjectInfo();
        if (menu != null) {
            metadata.put("object_id", String.valueOf(menu.getObjectId()));
            metadata.put("action", menu.getMenuOption());
        }

        TravelTask task = TravelTask.fromNavigationMetadata(metadata);
        if (task != null) {
            task.setExpectedDestination(info.getDestination());
        }
        return Optional.ofNullable(task);
    }

    /**
     * Map shortest-path transport type names to TravelTask type names.
     */
    private String mapTransportType(String spType) {
        if (spType == null) {
            return "unknown";
        }
        switch (spType) {
            case "FAIRY_RING":
                return "fairy_ring";
            case "SPIRIT_TREE":
                return "spirit_tree";
            case "GNOME_GLIDER":
                return "gnome_glider";
            case "CHARTER_SHIP":
                return "charter_ship";
            case "MAGIC_CARPET":
                return "magic_carpet";
            case "MINECART":
                return "minecart";
            case "QUETZAL":
                return "quetzal";
            case "HOT_AIR_BALLOON":
                return "balloon";
            case "CANOE":
                return "canoe";
            case "SHIP":
                return "ship";
            case "TELEPORTATION_SPELL":
                return "spell";
            case "TELEPORTATION_ITEM":
                return "tablet";
            case "TELEPORTATION_LEVER":
                return "lever";
            case "TELEPORTATION_PORTAL":
                return "portal";
            default:
                log.debug("Unknown transport type from shortest-path: {}", spType);
                return spType.toLowerCase();
        }
    }

    // ========================================================================
    // Resource Awareness Integration (Private)
    // ========================================================================

    /**
     * Build transport config overrides from ResourceAwareness.
     *
     * <p>This converts ResourceAwareness preferences into config overrides
     * that the ShortestPath plugin understands. Includes both:
     * <ul>
     *   <li>Boolean transport enable/disable flags (use*)</li>
     *   <li>Cost threshold adjustments (cost*)</li>
     * </ul>
     *
     * <p>Per ShortestPath wiki, valid override keys include:
     * <ul>
     *   <li>avoidWilderness, useAgilityShortcuts, useBoats, useCanoes, etc.</li>
     *   <li>useTeleportationItems (String: "None", "Inventory", "Inventory (perm)", etc.)</li>
     *   <li>costFairyRings, costSpiritTrees, costTeleportationSpells, etc.</li>
     * </ul>
     *
     * @param ctx TaskContext containing resource awareness
     * @return map of config overrides for pathfinding
     */
    private Map<String, Object> buildTransportCostOverrides(@Nullable TaskContext ctx) {
        if (ctx == null) {
            return Collections.emptyMap();
        }

        ResourceAwareness ra = ctx.getResourceAwareness();
        if (ra == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> overrides = new HashMap<>();

        // ====================================================================
        // Boolean Transport Enable/Disable Overrides
        // ====================================================================

        // Wilderness avoidance - HCIM should always avoid
        if (ra.shouldAvoidWilderness()) {
            overrides.put("avoidWilderness", true);
        }

        // Teleportation spells - disable for HCIM or when law runes scarce
        if (!ra.shouldUseTeleportationSpells()) {
            overrides.put("useTeleportationSpells", false);
        }

        // Teleportation items - set appropriate level based on account type
        String teleportItemsSetting = ra.getTeleportationItemsSetting();
        overrides.put("useTeleportationItems", teleportItemsSetting);

        // Charter ships - disable when broke or ironman with limited funds
        if (!ra.shouldUseCharterShips()) {
            overrides.put("useCharterShips", false);
        }

        // Magic carpets - disable when very broke
        if (!ra.shouldUseMagicCarpets()) {
            overrides.put("useMagicCarpets", false);
        }

        // Grapple shortcuts - disable for HCIM (risky)
        if (!ra.shouldUseGrappleShortcuts()) {
            overrides.put("useGrappleShortcuts", false);
        }

        // Wilderness obelisks - never for HCIM
        if (!ra.shouldUseWildernessObelisks()) {
            overrides.put("useWildernessObelisks", false);
        }

        // Canoes - generally enable (free transport)
        if (ra.shouldUseCanoes()) {
            overrides.put("useCanoes", true);
        }

        // ====================================================================
        // Cost Threshold Overrides (make preferred transports cheaper)
        // ====================================================================

        // Fairy rings: negative cost = incentive for ironmen
        int fairyBonus = ra.getFairyRingBonus();
        if (fairyBonus != 0) {
            // Note: Negative values reduce the threshold, making fairy rings more likely to be used
            overrides.put("costFairyRings", Math.abs(fairyBonus));
        }

        // Spirit trees: negative cost = incentive for ironmen
        int spiritBonus = ra.getSpiritTreeBonus();
        if (spiritBonus != 0) {
            overrides.put("costSpiritTrees", Math.abs(spiritBonus));
        }

        // Teleportation spells: add penalty when enabled but should be discouraged
        if (ra.shouldUseTeleportationSpells()) {
            int baseTeleportTicks = 20;
            int adjustedCost = ra.adjustTeleportCost(baseTeleportTicks, 1); // 1 law rune
            int teleportPenalty = adjustedCost - baseTeleportTicks;
            if (teleportPenalty > 0) {
                overrides.put("costTeleportationSpells", teleportPenalty);
            }
        }

        // Charter ships: gold-based penalty when enabled
        if (ra.shouldUseCharterShips()) {
            int baseTransportTicks = 10;
            int typicalCharterFare = 1000;
            int adjustedCharterCost = ra.adjustGoldTravelCost(baseTransportTicks, typicalCharterFare);
            int charterPenalty = adjustedCharterCost - baseTransportTicks;
            if (charterPenalty > 0) {
                overrides.put("costCharterShips", charterPenalty);
            }
        }

        // Magic carpets: gold-based penalty when enabled
        if (ra.shouldUseMagicCarpets()) {
            int baseTransportTicks = 10;
            int carpetFare = 200;
            int adjustedCarpetCost = ra.adjustGoldTravelCost(baseTransportTicks, carpetFare);
            int carpetPenalty = adjustedCarpetCost - baseTransportTicks;
            if (carpetPenalty > 0) {
                overrides.put("costMagicCarpets", carpetPenalty);
            }
        }

        if (!overrides.isEmpty()) {
            log.debug("Transport config overrides: {}", overrides);
        }

        return overrides;
    }

    // ========================================================================
    // Shortest-Path Data Access
    // ========================================================================

    /**
     * Get all bank locations from Shortest Path's destination data.
     *
     * @return set of all bank world points
     */
    public Set<WorldPoint> getAllBankLocations() {
        return getBridge().getAllBankLocations();
    }

    /**
     * Find the nearest bank to a position.
     *
     * @param from the reference position
     * @return the nearest bank position, or null if none found
     */
    @Nullable
    public WorldPoint findNearestBank(WorldPoint from) {
        return getBridge().findNearestBank(from);
    }

    /**
     * Get all altar locations from Shortest Path's destination data.
     *
     * @return set of all altar world points
     */
    public Set<WorldPoint> getAllAltarLocations() {
        return getBridge().getAllAltarLocations();
    }

    /**
     * Find the nearest altar to a position.
     *
     * @param from the reference position
     * @return the nearest altar position, or null if none found
     */
    @Nullable
    public WorldPoint findNearestAltar(WorldPoint from) {
        return getBridge().findNearestAltar(from);
    }

    /**
     * Get all anvil locations from Shortest Path's destination data.
     *
     * @return set of all anvil world points
     */
    public Set<WorldPoint> getAllAnvilLocations() {
        return getBridge().getAllAnvilLocations();
    }

    /**
     * Find the nearest anvil to a position.
     *
     * @param from the reference position
     * @return the nearest anvil position, or null if none found
     */
    @Nullable
    public WorldPoint findNearestAnvil(WorldPoint from) {
        return getBridge().findNearestAnvil(from);
    }

    /**
     * Get destinations of a specific type.
     *
     * @param destinationType the destination type (e.g., "bank", "altar")
     * @return set of world points for that destination type
     */
    public Set<WorldPoint> getDestinations(String destinationType) {
        return getBridge().getDestinations(destinationType);
    }

    /**
     * Get transport information for a specific origin-destination pair.
     *
     * @param origin transport origin
     * @param destination transport destination
     * @return transport info if found, null otherwise
     */
    @Nullable
    public ShortestPathBridge.TransportInfo getTransportAt(WorldPoint origin, WorldPoint destination) {
        return getBridge().getTransportAt(origin, destination);
    }

    /**
     * Pack a WorldPoint into an int using SP's format.
     *
     * @param point world point to pack
     * @return packed int value
     */
    public int packPoint(WorldPoint point) {
        return getBridge().packPoint(point);
    }

    /**
     * Unpack an int to a WorldPoint using SP's format.
     *
     * @param packed packed int value
     * @return unpacked world point
     */
    public WorldPoint unpackPoint(int packed) {
        return getBridge().unpackPoint(packed);
    }
}
