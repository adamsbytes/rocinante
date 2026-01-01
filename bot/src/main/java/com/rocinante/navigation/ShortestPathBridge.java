package com.rocinante.navigation;

import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.Pathfinder;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.PrimitiveIntList;
import shortestpath.ShortestPathPlugin;
import shortestpath.WorldPointUtil;
import shortestpath.transport.Transport;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Bridge to the Shortest Path external plugin for pathfinding and collision data.
 *
 * <p>This class provides:
 * <ul>
 *   <li>Async pathfinding requests via PluginMessage API</li>
 *   <li>Direct reflection access to SP's CollisionMap for reachability checks</li>
 *   <li>Transport detection and extraction from paths</li>
 *   <li>Global collision queries (fences, rivers, walls)</li>
 * </ul>
 *
 * <p>The Shortest Path plugin is accessed in two ways:
 * <ol>
 *   <li><strong>PluginMessage API</strong>: For requesting paths (async) and receiving results</li>
 *   <li><strong>Reflection</strong>: For direct access to CollisionMap and Pathfinder state</li>
 * </ol>
 *
 * <p>Key integration points:
 * <ul>
 *   <li>{@link #requestPath(WorldPoint, WorldPoint)} - Request async pathfinding</li>
 *   <li>{@link #isPathReady()} - Poll for path completion</li>
 *   <li>{@link #getCurrentPath()} - Get computed path</li>
 *   <li>{@link #canMoveTo(WorldPoint, WorldPoint)} - Check if movement is allowed</li>
 *   <li>{@link #analyzePathForTransports(List)} - Extract transport segments from path</li>
 * </ul>
 *
 * @see <a href="https://github.com/Skretzo/shortest-path">Shortest Path Plugin</a>
 */
@Slf4j
@Singleton
public class ShortestPathBridge {

    // ========================================================================
    // Constants
    // ========================================================================

    /** Plugin class name for finding via PluginManager */
    private static final String SHORTEST_PATH_CLASS = "shortestpath.ShortestPathPlugin";

    /** Namespace for PluginMessage communication - must match ShortestPathPlugin.CONFIG_GROUP */
    private static final String CONFIG_GROUP = "shortestpath";

    /** PluginMessage action for path requests */
    private static final String MESSAGE_PATH = "path";

    /** PluginMessage action to clear path */
    private static final String MESSAGE_CLEAR = "clear";

    /** Maximum time to wait for pathfinding in milliseconds */
    private static final long PATHFINDING_TIMEOUT_MS = 10_000;

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final Client client;
    private final EventBus eventBus;

    // ========================================================================
    // Plugin Access (via Reflection)
    // ========================================================================

    /** Reference to ShortestPathPlugin instance */
    @Getter
    private ShortestPathPlugin shortestPathPlugin;

    /** Whether the bridge is initialized and ready */
    @Getter
    private boolean available = false;

    // Direct CollisionMap access (no reflection - loaded from ShortestPath resources)
    // This is thread-safe: CollisionMap methods only read from immutable SplitFlagMap
    private CollisionMap collisionMap;
    
    // Reflection only needed for private field access
    private Field allDestinationsField;

    // ========================================================================
    // State
    // ========================================================================

    /** Time when current pathfinding request was made */
    private long pathRequestTime = 0;

    /** Cached current path (unpacked from SP's format) */
    private List<WorldPoint> cachedPath = Collections.emptyList();

    /** Whether we're waiting for a path result */
    private boolean pathfindingInProgress = false;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public ShortestPathBridge(Client client, EventBus eventBus) {
        this.client = client;
        this.eventBus = eventBus;
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    /**
     * Initialize the bridge with a reference to the Shortest Path plugin.
     * Called by RocinantePlugin after external plugins have loaded.
     *
     * @param plugin the ShortestPathPlugin instance (must not be null)
     * @throws IllegalArgumentException if plugin is null
     * @throws IllegalStateException if setup fails
     */
    public void initialize(Object plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("ShortestPathPlugin cannot be null");
        }

        if (!(plugin instanceof ShortestPathPlugin)) {
            throw new IllegalArgumentException("Plugin must be ShortestPathPlugin, got: " + plugin.getClass().getName());
        }

        this.shortestPathPlugin = (ShortestPathPlugin) plugin;

        try {
            setup();
            this.available = true;
            log.info("ShortestPathBridge initialized successfully (direct access, minimal reflection)");
        } catch (Exception e) {
            this.available = false;
            throw new IllegalStateException("Failed to set up ShortestPath bridge", e);
        }
    }

    /**
     * Set up direct access to ShortestPath classes.
     * Only uses reflection for private field access (allDestinations).
     */
    private void setup() throws Exception {
        // Load CollisionMap DIRECTLY from ShortestPath's resources
        SplitFlagMap mapData = SplitFlagMap.fromResources();
        this.collisionMap = new CollisionMap(mapData);
        log.info("Loaded collision map directly from ShortestPath resources");

        // Only reflection needed: allDestinations is a private field
        PathfinderConfig pathfinderConfig = shortestPathPlugin.getPathfinderConfig();
        if (pathfinderConfig != null) {
            allDestinationsField = PathfinderConfig.class.getDeclaredField("allDestinations");
            allDestinationsField.setAccessible(true);
        }
    }

    // ========================================================================
    // Pathfinding - Async Request/Poll Pattern
    // ========================================================================

    /**
     * Request a path from start to destination.
     * This is an asynchronous operation - use {@link #isPathReady()} to check completion.
     *
     * @param start starting position (null uses current player position)
     * @param destination target position
     */
    public void requestPath(@Nullable WorldPoint start, WorldPoint destination) {
        requestPath(start, Set.of(destination), null);
    }

    /**
     * Request a path from start to any of the target destinations.
     *
     * @param start starting position (null uses current player position)
     * @param destinations set of possible target positions
     */
    public void requestPath(@Nullable WorldPoint start, Set<WorldPoint> destinations) {
        requestPath(start, destinations, null);
    }

    /**
     * Request a path with custom configuration overrides.
     *
     * @param start starting position (null uses current player position)
     * @param destinations set of possible target positions
     * @param configOverride optional configuration overrides (e.g., "useAgilityShortcuts" -> true)
     */
    public void requestPath(@Nullable WorldPoint start, Set<WorldPoint> destinations,
                           @Nullable Map<String, Object> configOverride) {
        Map<String, Object> data = new HashMap<>();
        
        if (start != null) {
            data.put("start", start);
        }
        
        // Convert destinations to packed ints for the Set<Object> target field
        if (destinations.size() == 1) {
            data.put("target", destinations.iterator().next());
        } else {
            data.put("target", destinations);
        }

        if (configOverride != null && !configOverride.isEmpty()) {
            data.put("config", configOverride);
        }

        pathfindingInProgress = true;
        pathRequestTime = System.currentTimeMillis();
        cachedPath = Collections.emptyList();

        eventBus.post(new PluginMessage(CONFIG_GROUP, MESSAGE_PATH, data));
        log.debug("Requested path from {} to {} destination(s)", start, destinations.size());
    }

    /**
     * Clear the current path and cancel any pending pathfinding.
     */
    public void clearPath() {
        eventBus.post(new PluginMessage(CONFIG_GROUP, MESSAGE_CLEAR, Collections.emptyMap()));
        pathfindingInProgress = false;
        cachedPath = Collections.emptyList();
        log.debug("Cleared path");
    }

    /**
     * Check if pathfinding has completed.
     *
     * @return true if path is ready (or failed/timed out), false if still computing
     */
    public boolean isPathReady() {
        if (!available || !pathfindingInProgress) {
            return true;
        }

        // Check for timeout
        if (System.currentTimeMillis() - pathRequestTime > PATHFINDING_TIMEOUT_MS) {
            log.warn("Pathfinding timed out after {}ms", PATHFINDING_TIMEOUT_MS);
            pathfindingInProgress = false;
            return true;
        }

        Pathfinder pathfinder = shortestPathPlugin.getPathfinder();
        if (pathfinder == null) {
            return true;
        }

        boolean done = pathfinder.isDone();
        if (done) {
            pathfindingInProgress = false;
            cachedPath = extractPath(pathfinder);
        }
        return done;
    }

    /**
     * Get the current computed path.
     * Call {@link #isPathReady()} first to ensure pathfinding is complete.
     *
     * @return list of world points forming the path, empty if no path available
     */
    public List<WorldPoint> getCurrentPath() {
        if (!cachedPath.isEmpty()) {
            return cachedPath;
        }

        Pathfinder pathfinder = shortestPathPlugin.getPathfinder();
        if (pathfinder == null) {
            return Collections.emptyList();
        }

        cachedPath = extractPath(pathfinder);
        return cachedPath;
    }

    /**
     * Extract the path from the Pathfinder object.
     */
    private List<WorldPoint> extractPath(Pathfinder pathfinder) {
        PrimitiveIntList packedPath = pathfinder.getPath();
        if (packedPath == null || packedPath.isEmpty()) {
            return Collections.emptyList();
        }

        int size = packedPath.size();
        List<WorldPoint> path = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            int packed = packedPath.get(i);
            path.add(WorldPointUtil.unpackWorldPoint(packed));
        }

        return path;
    }

    // ========================================================================
    // Collision Queries - Global Static Collision
    // ========================================================================

    /**
     * Check if a tile is completely blocked (cannot be entered from any direction).
     *
     * @param x world X coordinate
     * @param y world Y coordinate
     * @param z plane (0-3)
     * @return true if blocked
     */
    public boolean isBlocked(int x, int y, int z) {
        return collisionMap.isBlocked(x, y, z);
    }

    /**
     * Check if a tile is completely blocked.
     *
     * @param point world point to check
     * @return true if blocked
     */
    public boolean isBlocked(WorldPoint point) {
        if (point == null) {
            return true;
        }
        return isBlocked(point.getX(), point.getY(), point.getPlane());
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
        return collisionMap.n(x, y, z);
    }

    /**
     * Check if movement is allowed from a tile to the south.
     */
    public boolean canMoveSouth(int x, int y, int z) {
        return collisionMap.s(x, y, z);
    }

    /**
     * Check if movement is allowed from a tile to the east.
     */
    public boolean canMoveEast(int x, int y, int z) {
        return collisionMap.e(x, y, z);
    }

    /**
     * Check if movement is allowed from a tile to the west.
     */
    public boolean canMoveWest(int x, int y, int z) {
        return collisionMap.w(x, y, z);
    }

    /**
     * Check if movement is allowed between two adjacent tiles.
     * This is the key method for reachability checks.
     *
     * @param from source tile
     * @param to destination tile (must be adjacent)
     * @return true if movement is allowed
     */
    public boolean canMoveTo(WorldPoint from, WorldPoint to) {
        if (from == null || to == null) {
            return false;
        }

        // Must be on same plane
        if (from.getPlane() != to.getPlane()) {
            return false;
        }

        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();

        // Must be adjacent (distance <= 1 in both dimensions)
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
            return false;
        }

        // Same tile
        if (dx == 0 && dy == 0) {
            return true;
        }

        int x = from.getX();
        int y = from.getY();
        int z = from.getPlane();

        // Cardinal directions
        if (dy == 1 && dx == 0) return canMoveNorth(x, y, z);
        if (dy == -1 && dx == 0) return canMoveSouth(x, y, z);
        if (dx == 1 && dy == 0) return canMoveEast(x, y, z);
        if (dx == -1 && dy == 0) return canMoveWest(x, y, z);

        // Diagonal directions - check both adjacent cardinals AND the diagonal
        if (dx == 1 && dy == 1) {
            // Northeast: need to pass north AND east AND destination not blocked
            return canMoveNorth(x, y, z) && canMoveEast(x, y + 1, z) 
                && canMoveEast(x, y, z) && canMoveNorth(x + 1, y, z)
                && !isBlocked(x + 1, y + 1, z);
        }
        if (dx == -1 && dy == 1) {
            // Northwest
            return canMoveNorth(x, y, z) && canMoveWest(x, y + 1, z)
                && canMoveWest(x, y, z) && canMoveNorth(x - 1, y, z)
                && !isBlocked(x - 1, y + 1, z);
        }
        if (dx == 1 && dy == -1) {
            // Southeast
            return canMoveSouth(x, y, z) && canMoveEast(x, y - 1, z)
                && canMoveEast(x, y, z) && canMoveSouth(x + 1, y, z)
                && !isBlocked(x + 1, y - 1, z);
        }
        if (dx == -1 && dy == -1) {
            // Southwest
            return canMoveSouth(x, y, z) && canMoveWest(x, y - 1, z)
                && canMoveWest(x, y, z) && canMoveSouth(x - 1, y, z)
                && !isBlocked(x - 1, y - 1, z);
        }

        return false;
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
        if (playerPos == null || targetPos == null) {
            return false;
        }

        // Must be on same plane
        if (playerPos.getPlane() != targetPos.getPlane()) {
            return false;
        }

        int distance = playerPos.distanceTo(targetPos);

        // Same tile - can always interact
        if (distance == 0) {
            return true;
        }

        // Must be adjacent for melee interaction
        if (distance > 1) {
            return false;
        }

        // Check if movement is allowed (no fence/wall/river blocking)
        return canMoveTo(playerPos, targetPos);
    }

    // ========================================================================
    // Transport Analysis
    // ========================================================================

    /**
     * Analyze a path for transport segments (non-adjacent tile jumps).
     *
     * @param path the path to analyze
     * @return list of transport segments found in the path
     */
    public List<TransportSegment> analyzePathForTransports(List<WorldPoint> path) {
        if (path == null || path.size() < 2) {
            return Collections.emptyList();
        }

        List<TransportSegment> segments = new ArrayList<>();

        for (int i = 0; i < path.size() - 1; i++) {
            WorldPoint current = path.get(i);
            WorldPoint next = path.get(i + 1);

            // Check if this is a transport (non-adjacent or different plane)
            int distance = current.distanceTo(next);
            boolean differentPlane = current.getPlane() != next.getPlane();

            if (distance > 1 || differentPlane) {
                // This is a transport segment
                TransportInfo info = getTransportAt(current, next);
                segments.add(new TransportSegment(i, current, next, info));
                log.debug("Found transport at path index {}: {} -> {} (type: {})", 
                        i, current, next, info != null ? info.getType() : "unknown");
            }
        }

        return segments;
    }

    /**
     * Get transport information for a specific origin-destination pair.
     *
     * @param origin transport origin
     * @param destination transport destination
     * @return transport info if found, null otherwise
     */
    @Nullable
    public TransportInfo getTransportAt(WorldPoint origin, WorldPoint destination) {
        if (!available || origin == null) {
            return null;
        }

        PathfinderConfig pathfinderConfig = shortestPathPlugin.getPathfinderConfig();
        if (pathfinderConfig == null) {
            return null;
        }

        Map<Integer, Set<Transport>> transports = pathfinderConfig.getTransports();
        if (transports == null) {
            return null;
        }

        int packedOrigin = WorldPointUtil.packWorldPoint(origin);
        Set<Transport> originTransports = transports.get(packedOrigin);

        if (originTransports == null || originTransports.isEmpty()) {
            return null;
        }

        int packedDest = WorldPointUtil.packWorldPoint(destination);

        // Find transport matching destination
        for (Transport transport : originTransports) {
            if (transport.getDestination() == packedDest) {
                return extractTransportInfo(transport);
            }
        }

        return null;
    }

    /**
     * Extract TransportInfo from a Transport object.
     */
    private TransportInfo extractTransportInfo(Transport transport) {
        int packedOrigin = transport.getOrigin();
        int packedDestination = transport.getDestination();
        String typeName = transport.getType() != null ? transport.getType().toString() : "TRANSPORT";

        return new TransportInfo(
                packedOrigin,
                packedDestination,
                WorldPointUtil.unpackWorldPoint(packedOrigin),
                WorldPointUtil.unpackWorldPoint(packedDestination),
                typeName,
                transport.getObjectInfo(),
                transport.getDisplayInfo(),
                transport.getDuration()
        );
    }

    // ========================================================================
    // Destination Queries (Banks, Altars, Anvils, etc.)
    // ========================================================================

    /**
     * Get all bank locations from Shortest Path's destination data.
     *
     * @return set of all bank world points, empty if not available
     */
    public Set<WorldPoint> getAllBankLocations() {
        return getDestinations("bank");
    }

    /**
     * Get all altar locations from Shortest Path's destination data.
     *
     * @return set of all altar world points, empty if not available
     */
    public Set<WorldPoint> getAllAltarLocations() {
        return getDestinations("altar");
    }

    /**
     * Get all anvil locations from Shortest Path's destination data.
     *
     * @return set of all anvil world points, empty if not available
     */
    public Set<WorldPoint> getAllAnvilLocations() {
        return getDestinations("anvil");
    }

    /**
     * Get destinations of a specific type from Shortest Path's destination data.
     *
     * @param destinationType the destination type (e.g., "bank", "altar", "anvil")
     * @return set of world points, empty if not available
     */
    @SuppressWarnings("unchecked")
    public Set<WorldPoint> getDestinations(String destinationType) {
        try {
            PathfinderConfig pathfinderConfig = shortestPathPlugin.getPathfinderConfig();
            if (pathfinderConfig == null) {
                return Collections.emptySet();
            }

            Map<String, Set<Integer>> allDestinations = 
                    (Map<String, Set<Integer>>) allDestinationsField.get(pathfinderConfig);
            if (allDestinations == null) {
                return Collections.emptySet();
            }

            Set<Integer> packedDestinations = allDestinations.get(destinationType);
            if (packedDestinations == null || packedDestinations.isEmpty()) {
                return Collections.emptySet();
            }

            Set<WorldPoint> result = new HashSet<>(packedDestinations.size());
            for (int packed : packedDestinations) {
                result.add(WorldPointUtil.unpackWorldPoint(packed));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException(
                String.format("Failed to get destinations of type '%s'", destinationType), e);
        }
    }

    /**
     * Find the nearest bank to a position.
     *
     * @param from the reference position
     * @return the nearest bank position, or null if none found
     */
    @Nullable
    public WorldPoint findNearestBank(WorldPoint from) {
        return findNearest(from, getAllBankLocations());
    }

    /**
     * Find the nearest altar to a position.
     *
     * @param from the reference position
     * @return the nearest altar position, or null if none found
     */
    @Nullable
    public WorldPoint findNearestAltar(WorldPoint from) {
        return findNearest(from, getAllAltarLocations());
    }

    /**
     * Find the nearest anvil to a position.
     *
     * @param from the reference position
     * @return the nearest anvil position, or null if none found
     */
    @Nullable
    public WorldPoint findNearestAnvil(WorldPoint from) {
        return findNearest(from, getAllAnvilLocations());
    }

    /**
     * Find the nearest position from a set of candidates.
     */
    @Nullable
    private WorldPoint findNearest(WorldPoint from, Set<WorldPoint> candidates) {
        if (from == null || candidates.isEmpty()) {
            return null;
        }

        WorldPoint nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (WorldPoint candidate : candidates) {
            // Only consider same plane
            if (candidate.getPlane() != from.getPlane()) {
                continue;
            }
            int dist = from.distanceTo(candidate);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = candidate;
            }
        }

        return nearest;
    }

    // ========================================================================
    // Coordinate Utilities
    // ========================================================================

    /**
     * Pack a WorldPoint into an int using SP's format.
     */
    public int packPoint(WorldPoint point) {
        return WorldPointUtil.packWorldPoint(point);
    }

    /**
     * Unpack an int to a WorldPoint using SP's format.
     */
    public WorldPoint unpackPoint(int packed) {
        if (packed == WorldPointUtil.UNDEFINED) {
            return null;
        }
        return WorldPointUtil.unpackWorldPoint(packed);
    }

    // ========================================================================
    // Data Classes
    // ========================================================================

    /**
     * Information about a transport (teleport, boat, fairy ring, etc.).
     */
    @Value
    public static class TransportInfo {
        int packedOrigin;
        int packedDestination;
        WorldPoint origin;
        WorldPoint destination;
        String type;          // e.g., "FAIRY_RING", "SPIRIT_TREE", "TELEPORTATION_SPELL"
        String objectInfo;    // e.g., "Configure Fairy ring 29560"
        String displayInfo;   // e.g., "AIQ" for fairy ring code, destination name for spirit tree
        int durationTicks;

        /**
         * Parse objectInfo to extract menu option and object ID.
         *
         * @return parsed menu info, or null if parsing fails
         */
        @Nullable
        public ParsedMenuInfo parseObjectInfo() {
            if (objectInfo == null || objectInfo.isEmpty()) {
                return null;
            }

            // Format: "menuOption menuTarget objectID"
            // e.g., "Configure Fairy ring 29560"
            // or "Open Door 9398"
            String[] parts = objectInfo.split(" ");
            if (parts.length < 2) {
                return null;
            }

            try {
                int objectId = Integer.parseInt(parts[parts.length - 1]);
                String menuOption = parts[0];
                String menuTarget = String.join(" ", 
                        Arrays.copyOfRange(parts, 1, parts.length - 1));
                return new ParsedMenuInfo(menuOption, menuTarget, objectId);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /**
     * Parsed menu interaction info from Transport.objectInfo.
     */
    @Value
    public static class ParsedMenuInfo {
        String menuOption;  // e.g., "Configure", "Open", "Travel"
        String menuTarget;  // e.g., "Fairy ring", "Door", "Spirit tree"
        int objectId;       // e.g., 29560, 9398
    }

    /**
     * A segment of a path that uses a transport.
     */
    @Value
    public static class TransportSegment {
        int pathIndex;          // Index in the path where transport starts
        WorldPoint origin;      // Transport origin tile
        WorldPoint destination; // Transport destination tile
        TransportInfo transport; // Transport info (may be null if not found)
    }
}
