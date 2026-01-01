package com.rocinante.navigation;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GroundObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spatial index for fast game object lookups.
 *
 * <p>This index divides the scene into a grid of cells and maintains a list of
 * objects in each cell. This allows O(1) cell lookup + small list scan instead
 * of O(n) full scene scan for object searches.
 *
 * <p>The index is updated incrementally via RuneLite events:
 * <ul>
 *   <li>{@link GameObjectSpawned} - add object to index</li>
 *   <li>{@link GameObjectDespawned} - remove object from index</li>
 *   <li>{@link GameStateChanged} - clear index on scene change</li>
 * </ul>
 *
 * <p>Performance characteristics:
 * <ul>
 *   <li>Initial build: 1-3ms (on first access after scene load)</li>
 *   <li>Incremental update: &lt;0.1ms per event</li>
 *   <li>Query (nearby objects): &lt;1ms for typical searches</li>
 * </ul>
 *
 * <p>Grid configuration:
 * <ul>
 *   <li>Cell size: 8x8 tiles (104/8 = 13 cells per axis)</li>
 *   <li>Total cells: 169 per plane (13x13)</li>
 *   <li>Memory: ~1KB per plane (object references)</li>
 * </ul>
 *
 * @see EntityFinder
 */
@Slf4j
@Singleton
public class SpatialObjectIndex {

    /**
     * Cell size in tiles. Must evenly divide scene size (104).
     * 8 tiles = 13 cells per axis, good balance of cell count and objects per cell.
     */
    private static final int CELL_SIZE = 8;

    /**
     * Number of cells per axis (104 / 8 = 13).
     */
    private static final int CELLS_PER_AXIS = Constants.SCENE_SIZE / CELL_SIZE;

    /**
     * Total cells per plane.
     */
    private static final int CELLS_PER_PLANE = CELLS_PER_AXIS * CELLS_PER_AXIS;

    private final Client client;

    /**
     * Grid of object lists indexed by cell.
     * Index = plane * CELLS_PER_PLANE + cellY * CELLS_PER_AXIS + cellX
     */
    private final Map<Integer, Set<IndexedObject>> grid = new HashMap<>();

    /**
     * Reverse lookup: object hash -> cell index (for fast removal).
     */
    private final Map<Integer, Integer> objectToCell = new HashMap<>();

    /**
     * Whether the index has been built for the current scene.
     */
    private volatile boolean initialized = false;

    /**
     * Last known base position for scene change detection.
     */
    private volatile int lastBaseX = -1;
    private volatile int lastBaseY = -1;

    // Metrics
    private final AtomicLong queryCount = new AtomicLong(0);
    private final AtomicLong objectsScanned = new AtomicLong(0);

    @Inject
    public SpatialObjectIndex(Client client) {
        this.client = client;
        log.info("SpatialObjectIndex initialized (cell size: {}x{}, cells: {}x{})",
                CELL_SIZE, CELL_SIZE, CELLS_PER_AXIS, CELLS_PER_AXIS);
    }

    // ========================================================================
    // Query API
    // ========================================================================

    /**
     * Find all objects matching the given IDs within a radius of a center point.
     *
     * <p>This is the main query method that replaces full scene scans in
     * {@link EntityFinder}.
     *
     * @param center    center point for the search
     * @param radius    search radius in tiles
     * @param objectIds set of acceptable object IDs (null = all objects)
     * @return list of matching objects with their positions
     */
    public List<ObjectEntry> findObjectsNearby(WorldPoint center, int radius, Collection<Integer> objectIds) {
        if (center == null || radius <= 0) {
            return Collections.emptyList();
        }

        ensureInitialized();
        queryCount.incrementAndGet();

        int plane = center.getPlane();
        Set<Integer> targetIds = objectIds != null ? new HashSet<>(objectIds) : null;

        // Convert to scene coordinates
        LocalPoint localCenter = LocalPoint.fromWorld(client, center);
        if (localCenter == null) {
            log.debug("findObjectsNearby: center {} not in scene", center);
            return Collections.emptyList();
        }

        int centerSceneX = localCenter.getSceneX();
        int centerSceneY = localCenter.getSceneY();

        // Calculate cell range to search
        int minCellX = Math.max(0, (centerSceneX - radius) / CELL_SIZE);
        int maxCellX = Math.min(CELLS_PER_AXIS - 1, (centerSceneX + radius) / CELL_SIZE);
        int minCellY = Math.max(0, (centerSceneY - radius) / CELL_SIZE);
        int maxCellY = Math.min(CELLS_PER_AXIS - 1, (centerSceneY + radius) / CELL_SIZE);

        List<ObjectEntry> results = new ArrayList<>();
        int cellsChecked = 0;
        int objectsInRange = 0;

        // Search relevant cells
        for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                int cellIndex = getCellIndex(plane, cellX, cellY);
                Set<IndexedObject> cellObjects = grid.get(cellIndex);
                cellsChecked++;
                if (cellObjects == null || cellObjects.isEmpty()) {
                    continue;
                }

                for (IndexedObject indexed : cellObjects) {
                    objectsScanned.incrementAndGet();
                    objectsInRange++;

                    // ID filter
                    if (targetIds != null && !targetIds.contains(indexed.objectId)) {
                        continue;
                    }

                    // Distance filter
                    int dx = indexed.sceneX - centerSceneX;
                    int dy = indexed.sceneY - centerSceneY;
                    int chebyshevDist = Math.max(Math.abs(dx), Math.abs(dy));
                    if (chebyshevDist > radius) {
                        continue;
                    }

                    // Get current TileObject (may have changed since indexing)
                    TileObject currentObject = getTileObjectAt(indexed.sceneX, indexed.sceneY, plane, indexed.objectId);
                    if (currentObject != null) {
                        WorldPoint worldPos = currentObject.getWorldLocation();
                        if (worldPos != null) {
                            results.add(new ObjectEntry(currentObject, worldPos, chebyshevDist));
                        }
                    } else {
                        // Object was indexed but no longer exists at that location - stale index entry
                        log.debug("Stale index: object {} at scene ({},{}) no longer exists", 
                                indexed.objectId, indexed.sceneX, indexed.sceneY);
                    }
                }
            }
        }

        if (targetIds != null && targetIds.size() > 1) {
            // Log which IDs from the request were actually found
            Set<Integer> foundIds = new HashSet<>();
            for (ObjectEntry entry : results) {
                foundIds.add(entry.getObject().getId());
            }
            Set<Integer> missingIds = new HashSet<>(targetIds);
            missingIds.removeAll(foundIds);
            if (!missingIds.isEmpty()) {
                log.debug("findObjectsNearby: found {} for IDs {}, missing IDs {} (checked {} cells, {} objects in range)",
                        foundIds, targetIds, missingIds, cellsChecked, objectsInRange);
            }
        }

        return results;
    }

    /**
     * Check if there's any object matching the given IDs within radius.
     * Faster than findObjectsNearby when you just need existence check.
     *
     * @param center    center point for the search
     * @param radius    search radius in tiles
     * @param objectIds set of acceptable object IDs
     * @return true if at least one matching object exists
     */
    public boolean hasObjectNearby(WorldPoint center, int radius, Collection<Integer> objectIds) {
        if (center == null || radius <= 0 || objectIds == null || objectIds.isEmpty()) {
            return false;
        }

        ensureInitialized();

        int plane = center.getPlane();
        Set<Integer> targetIds = new HashSet<>(objectIds);

        LocalPoint localCenter = LocalPoint.fromWorld(client, center);
        if (localCenter == null) {
            return false;
        }

        int centerSceneX = localCenter.getSceneX();
        int centerSceneY = localCenter.getSceneY();

        int minCellX = Math.max(0, (centerSceneX - radius) / CELL_SIZE);
        int maxCellX = Math.min(CELLS_PER_AXIS - 1, (centerSceneX + radius) / CELL_SIZE);
        int minCellY = Math.max(0, (centerSceneY - radius) / CELL_SIZE);
        int maxCellY = Math.min(CELLS_PER_AXIS - 1, (centerSceneY + radius) / CELL_SIZE);

        for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                int cellIndex = getCellIndex(plane, cellX, cellY);
                Set<IndexedObject> cellObjects = grid.get(cellIndex);
                if (cellObjects == null) {
                    continue;
                }

                for (IndexedObject indexed : cellObjects) {
                    if (!targetIds.contains(indexed.objectId)) {
                        continue;
                    }

                    int dx = indexed.sceneX - centerSceneX;
                    int dy = indexed.sceneY - centerSceneY;
                    int chebyshevDist = Math.max(Math.abs(dx), Math.abs(dy));
                    if (chebyshevDist <= radius) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // ========================================================================
    // Index Management
    // ========================================================================

    /**
     * Ensure the index is initialized for the current scene.
     */
    private void ensureInitialized() {
        // Check for scene change
        int baseX = client.getBaseX();
        int baseY = client.getBaseY();

        if (baseX != lastBaseX || baseY != lastBaseY) {
            // Scene changed - rebuild
            log.debug("Scene changed ({},{}) -> ({},{}), rebuilding index", lastBaseX, lastBaseY, baseX, baseY);
            clear();
            lastBaseX = baseX;
            lastBaseY = baseY;
        }

        if (!initialized) {
            buildIndex();
            initialized = true;
            log.debug("Index initialized: {} cells, {} objects", grid.size(), objectToCell.size());
        }
    }

    /**
     * Build the full index from the current scene.
     */
    private void buildIndex() {
        long startTime = System.nanoTime();
        log.debug("buildIndex: starting...");

        Scene scene = client.getScene();
        if (scene == null) {
            log.warn("buildIndex: scene is null!");
            return;
        }

        Tile[][][] tiles = scene.getTiles();
        int objectCount = 0;

        for (int plane = 0; plane < tiles.length; plane++) {
            for (int x = 0; x < Constants.SCENE_SIZE; x++) {
                for (int y = 0; y < Constants.SCENE_SIZE; y++) {
                    Tile tile = tiles[plane][x][y];
                    if (tile == null) {
                        continue;
                    }

                    // Index all object types on this tile
                    objectCount += indexTileObjects(tile, x, y, plane);
                }
            }
        }

        long elapsed = System.nanoTime() - startTime;
        log.debug("SpatialObjectIndex built: {} objects indexed in {}ms",
                objectCount, elapsed / 1_000_000.0);
    }

    /**
     * Index all objects on a tile.
     *
     * @return number of objects indexed
     */
    private int indexTileObjects(Tile tile, int sceneX, int sceneY, int plane) {
        int count = 0;

        // Game objects
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject obj : gameObjects) {
                if (obj != null) {
                    addToIndex(obj.getId(), sceneX, sceneY, plane);
                    count++;
                }
            }
        }

        // Wall object
        WallObject wallObject = tile.getWallObject();
        if (wallObject != null) {
            addToIndex(wallObject.getId(), sceneX, sceneY, plane);
            count++;
        }

        // Ground object
        GroundObject groundObject = tile.getGroundObject();
        if (groundObject != null) {
            addToIndex(groundObject.getId(), sceneX, sceneY, plane);
            count++;
        }

        // Decorative object
        DecorativeObject decorativeObject = tile.getDecorativeObject();
        if (decorativeObject != null) {
            addToIndex(decorativeObject.getId(), sceneX, sceneY, plane);
            count++;
        }

        return count;
    }

    /**
     * Add an object to the spatial index.
     */
    private void addToIndex(int objectId, int sceneX, int sceneY, int plane) {
        int cellX = sceneX / CELL_SIZE;
        int cellY = sceneY / CELL_SIZE;
        int cellIndex = getCellIndex(plane, cellX, cellY);

        IndexedObject indexed = new IndexedObject(objectId, sceneX, sceneY);

        grid.computeIfAbsent(cellIndex, k -> new HashSet<>()).add(indexed);
        objectToCell.put(indexed.hashCode(), cellIndex);
    }

    /**
     * Remove an object from the spatial index.
     */
    private void removeFromIndex(int objectId, int sceneX, int sceneY, int plane) {
        int cellX = sceneX / CELL_SIZE;
        int cellY = sceneY / CELL_SIZE;
        int cellIndex = getCellIndex(plane, cellX, cellY);

        Set<IndexedObject> cellObjects = grid.get(cellIndex);
        if (cellObjects != null) {
            IndexedObject toRemove = new IndexedObject(objectId, sceneX, sceneY);
            cellObjects.remove(toRemove);
            objectToCell.remove(toRemove.hashCode());
        }
    }

    /**
     * Clear the entire index.
     */
    public void clear() {
        grid.clear();
        objectToCell.clear();
        initialized = false;
        log.debug("SpatialObjectIndex cleared");
    }

    // ========================================================================
    // Event Handlers
    // ========================================================================

    /**
     * Handle game object spawn - add to index.
     */
    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event) {
        if (!initialized) {
            return; // Will be indexed on next query
        }

        GameObject obj = event.getGameObject();
        if (obj == null) {
            return;
        }

        Tile tile = event.getTile();
        if (tile == null) {
            return;
        }

        LocalPoint local = tile.getLocalLocation();
        if (local == null) {
            return;
        }

        int sceneX = local.getSceneX();
        int sceneY = local.getSceneY();
        int plane = tile.getPlane();

        addToIndex(obj.getId(), sceneX, sceneY, plane);
    }

    /**
     * Handle game object despawn - remove from index.
     */
    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event) {
        if (!initialized) {
            return;
        }

        GameObject obj = event.getGameObject();
        if (obj == null) {
            return;
        }

        Tile tile = event.getTile();
        if (tile == null) {
            return;
        }

        LocalPoint local = tile.getLocalLocation();
        if (local == null) {
            return;
        }

        int sceneX = local.getSceneX();
        int sceneY = local.getSceneY();
        int plane = tile.getPlane();

        removeFromIndex(obj.getId(), sceneX, sceneY, plane);
    }

    /**
     * Handle game state changes - clear index on scene load.
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState state = event.getGameState();
        if (state == GameState.LOADING || state == GameState.LOGIN_SCREEN ||
            state == GameState.HOPPING || state == GameState.CONNECTION_LOST) {
            clear();
        }
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Calculate cell index from plane and cell coordinates.
     */
    private static int getCellIndex(int plane, int cellX, int cellY) {
        return plane * CELLS_PER_PLANE + cellY * CELLS_PER_AXIS + cellX;
    }

    /**
     * Get a TileObject at specific scene coordinates with matching ID.
     */
    private TileObject getTileObjectAt(int sceneX, int sceneY, int plane, int objectId) {
        Scene scene = client.getScene();
        if (scene == null) {
            return null;
        }

        Tile[][][] tiles = scene.getTiles();
        if (sceneX < 0 || sceneX >= Constants.SCENE_SIZE ||
            sceneY < 0 || sceneY >= Constants.SCENE_SIZE ||
            plane < 0 || plane >= tiles.length) {
            return null;
        }

        Tile tile = tiles[plane][sceneX][sceneY];
        if (tile == null) {
            return null;
        }

        // Check game objects
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject obj : gameObjects) {
                if (obj != null && obj.getId() == objectId) {
                    return obj;
                }
            }
        }

        // Check wall object
        WallObject wallObject = tile.getWallObject();
        if (wallObject != null && wallObject.getId() == objectId) {
            return wallObject;
        }

        // Check ground object
        GroundObject groundObject = tile.getGroundObject();
        if (groundObject != null && groundObject.getId() == objectId) {
            return groundObject;
        }

        // Check decorative object
        DecorativeObject decorativeObject = tile.getDecorativeObject();
        if (decorativeObject != null && decorativeObject.getId() == objectId) {
            return decorativeObject;
        }

        return null;
    }

    /**
     * Get statistics for monitoring.
     *
     * @return formatted statistics string
     */
    public String getStats() {
        return String.format("SpatialObjectIndex[cells=%d, objects=%d, queries=%d, scanned=%d]",
                grid.size(), objectToCell.size(), queryCount.get(), objectsScanned.get());
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    /**
     * Indexed object entry stored in the grid.
     */
    private static class IndexedObject {
        final int objectId;
        final int sceneX;
        final int sceneY;

        IndexedObject(int objectId, int sceneX, int sceneY) {
            this.objectId = objectId;
            this.sceneX = sceneX;
            this.sceneY = sceneY;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            IndexedObject that = (IndexedObject) o;
            return objectId == that.objectId && sceneX == that.sceneX && sceneY == that.sceneY;
        }

        @Override
        public int hashCode() {
            return objectId * 31 * 31 + sceneX * 31 + sceneY;
        }
    }

    /**
     * Result entry returned from queries.
     */
    public static class ObjectEntry {
        private final TileObject object;
        private final WorldPoint position;
        private final int distance;

        public ObjectEntry(TileObject object, WorldPoint position, int distance) {
            this.object = object;
            this.position = position;
            this.distance = distance;
        }

        public TileObject getObject() {
            return object;
        }

        public WorldPoint getPosition() {
            return position;
        }

        public int getDistance() {
            return distance;
        }
    }
}
