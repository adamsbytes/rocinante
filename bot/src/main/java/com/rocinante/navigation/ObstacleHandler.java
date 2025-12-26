package com.rocinante.navigation;

import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.InteractObjectTask;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

/**
 * Handles obstacles that block navigation paths.
 * Per REQUIREMENTS.md Section 7.3.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Registry of common obstacles (doors, gates)</li>
 *   <li>Detection of obstacles blocking a path</li>
 *   <li>Generation of tasks to handle obstacles</li>
 *   <li>Integration with PathFinder for obstacle traversal cost</li>
 * </ul>
 */
@Slf4j
@Singleton
public class ObstacleHandler {

    private final Client client;

    /**
     * Registry of known obstacles by object ID.
     */
    private final Map<Integer, ObstacleDefinition> obstacleRegistry = new HashMap<>();

    /**
     * Registry of obstacles by location (for position-based lookup).
     */
    private final Map<WorldPoint, List<ObstacleDefinition>> obstaclesByLocation = new HashMap<>();

    @Inject
    public ObstacleHandler(Client client) {
        this.client = client;
        initializeRegistry();
    }

    // ========================================================================
    // Registry Initialization
    // ========================================================================

    /**
     * Initialize the obstacle registry with common doors and gates.
     */
    private void initializeRegistry() {
        // Common door IDs in OSRS
        // Note: Many doors have multiple IDs for open/closed states

        // Generic wooden doors (very common throughout game)
        registerObstacle(ObstacleDefinition.door("Wooden Door", 1535, 1534));
        registerObstacle(ObstacleDefinition.door("Wooden Door", 1530, 1531));
        registerObstacle(ObstacleDefinition.door("Wooden Door", 1533, 1532));

        // Lumbridge Castle doors
        registerObstacle(ObstacleDefinition.door("Lumbridge Castle Door", 1543, 1544));
        registerObstacle(ObstacleDefinition.door("Lumbridge Castle Door", 1545, 1546));

        // Bank doors
        registerObstacle(ObstacleDefinition.door("Bank Door", 1804, 1805));
        registerObstacle(ObstacleDefinition.door("Bank Door", 11775, 11776));
        registerObstacle(ObstacleDefinition.door("Bank Door", 24381, 24382));

        // Varrock doors
        registerObstacle(ObstacleDefinition.door("Varrock Door", 1540, 1541));
        registerObstacle(ObstacleDefinition.door("Varrock Palace Door", 1548, 1549));

        // Falador doors
        registerObstacle(ObstacleDefinition.door("Falador Door", 1558, 1559));

        // Al Kharid gate (toll gate)
        registerObstacle(ObstacleDefinition.gate("Al Kharid Gate", 2882, 2883));
        registerObstacle(ObstacleDefinition.gate("Al Kharid Gate", 2881, 2880));

        // Common gates
        registerObstacle(ObstacleDefinition.gate("Gate", 1551, 1552));
        registerObstacle(ObstacleDefinition.gate("Gate", 1553, 1554));
        registerObstacle(ObstacleDefinition.gate("Gate", 1555, 1556));
        registerObstacle(ObstacleDefinition.gate("Gate", 1596, 1597));

        // Farm gates
        registerObstacle(ObstacleDefinition.gate("Farm Gate", 7136, 7137));
        registerObstacle(ObstacleDefinition.gate("Farm Gate", 12985, 12986));

        // Garden gates
        registerObstacle(ObstacleDefinition.gate("Garden Gate", 2050, 2051));

        // Wilderness ditch
        registerObstacle(ObstacleDefinition.builder()
                .name("Wilderness Ditch")
                .type(ObstacleDefinition.ObstacleType.OTHER)
                .objectIds(List.of(23271))
                .blockedStateId(23271)
                .passableStateId(-1)
                .action("Cross")
                .traversalCostTicks(3)
                .build());

        // Edgeville dungeon trapdoor
        registerObstacle(ObstacleDefinition.builder()
                .name("Trapdoor")
                .type(ObstacleDefinition.ObstacleType.OTHER)
                .objectIds(List.of(1579, 1580))
                .blockedStateId(1579)
                .passableStateId(1580)
                .action("Open")
                .traversalCostTicks(3)
                .build());

        log.info("ObstacleHandler initialized with {} obstacle definitions",
                obstacleRegistry.size());
    }

    /**
     * Register an obstacle definition.
     *
     * @param definition the obstacle definition
     */
    public void registerObstacle(ObstacleDefinition definition) {
        if (definition.getObjectIds() != null) {
            for (int id : definition.getObjectIds()) {
                obstacleRegistry.put(id, definition);
            }
        }
    }

    /**
     * Register an obstacle at a specific location.
     *
     * @param location   the world point
     * @param definition the obstacle definition
     */
    public void registerObstacleAtLocation(WorldPoint location, ObstacleDefinition definition) {
        obstaclesByLocation.computeIfAbsent(location, k -> new ArrayList<>()).add(definition);
    }

    // ========================================================================
    // Obstacle Detection
    // ========================================================================

    /**
     * Find obstacles blocking movement between two adjacent tiles.
     *
     * @param from the starting tile
     * @param to   the destination tile
     * @return optional containing the blocking obstacle, if any
     */
    public Optional<DetectedObstacle> findBlockingObstacle(WorldPoint from, WorldPoint to) {
        if (from == null || to == null) {
            return Optional.empty();
        }

        // Check for obstacles at or between the two tiles
        Scene scene = client.getScene();
        if (scene == null) {
            return Optional.empty();
        }

        Tile[][][] tiles = scene.getTiles();
        int plane = from.getPlane();

        // Check the 'from' tile for walls/doors
        Optional<DetectedObstacle> obstacle = checkTileForObstacle(tiles, from, to, plane);
        if (obstacle.isPresent()) {
            return obstacle;
        }

        // Check the 'to' tile for obstacles
        return checkTileForObstacle(tiles, to, from, plane);
    }

    /**
     * Find all obstacles within a radius of a point.
     *
     * @param center the center point
     * @param radius the search radius in tiles
     * @return list of detected obstacles
     */
    public List<DetectedObstacle> findObstaclesNearby(WorldPoint center, int radius) {
        List<DetectedObstacle> obstacles = new ArrayList<>();

        Scene scene = client.getScene();
        if (scene == null) {
            return obstacles;
        }

        Tile[][][] tiles = scene.getTiles();
        int plane = center.getPlane();

        LocalPoint centerLocal = LocalPoint.fromWorld(client, center);
        if (centerLocal == null) {
            return obstacles;
        }

        int centerSceneX = centerLocal.getSceneX();
        int centerSceneY = centerLocal.getSceneY();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                int sceneX = centerSceneX + dx;
                int sceneY = centerSceneY + dy;

                if (sceneX < 0 || sceneX >= Constants.SCENE_SIZE ||
                        sceneY < 0 || sceneY >= Constants.SCENE_SIZE) {
                    continue;
                }

                Tile tile = tiles[plane][sceneX][sceneY];
                if (tile == null) {
                    continue;
                }

                // Check wall objects
                WallObject wallObject = tile.getWallObject();
                if (wallObject != null) {
                    ObstacleDefinition def = obstacleRegistry.get(wallObject.getId());
                    if (def != null) {
                        WorldPoint location = tile.getWorldLocation();
                        obstacles.add(new DetectedObstacle(def, wallObject, location,
                                def.isBlocked(wallObject.getId())));
                    }
                }

                // Check game objects
                for (GameObject gameObject : tile.getGameObjects()) {
                    if (gameObject == null) {
                        continue;
                    }
                    ObstacleDefinition def = obstacleRegistry.get(gameObject.getId());
                    if (def != null) {
                        WorldPoint location = tile.getWorldLocation();
                        obstacles.add(new DetectedObstacle(def, gameObject, location,
                                def.isBlocked(gameObject.getId())));
                    }
                }
            }
        }

        return obstacles;
    }

    /**
     * Check a specific tile for obstacles.
     */
    private Optional<DetectedObstacle> checkTileForObstacle(Tile[][][] tiles, WorldPoint check, WorldPoint other, int plane) {
        LocalPoint checkLocal = LocalPoint.fromWorld(client, check);
        if (checkLocal == null) {
            return Optional.empty();
        }

        int sceneX = checkLocal.getSceneX();
        int sceneY = checkLocal.getSceneY();

        if (sceneX < 0 || sceneX >= Constants.SCENE_SIZE ||
                sceneY < 0 || sceneY >= Constants.SCENE_SIZE) {
            return Optional.empty();
        }

        Tile tile = tiles[plane][sceneX][sceneY];
        if (tile == null) {
            return Optional.empty();
        }

        // Check wall object first (most common for doors)
        WallObject wallObject = tile.getWallObject();
        if (wallObject != null) {
            ObstacleDefinition def = obstacleRegistry.get(wallObject.getId());
            if (def != null && def.isBlocked(wallObject.getId())) {
                // Check if wall is in the direction of movement
                if (isWallBlockingDirection(wallObject, check, other)) {
                    return Optional.of(new DetectedObstacle(def, wallObject, check, true));
                }
            }
        }

        // Check game objects
        for (GameObject gameObject : tile.getGameObjects()) {
            if (gameObject == null) {
                continue;
            }
            ObstacleDefinition def = obstacleRegistry.get(gameObject.getId());
            if (def != null && def.isBlocked(gameObject.getId())) {
                return Optional.of(new DetectedObstacle(def, gameObject, check, true));
            }
        }

        return Optional.empty();
    }

    /**
     * Check if a wall object blocks movement in a specific direction.
     */
    private boolean isWallBlockingDirection(WallObject wall, WorldPoint from, WorldPoint to) {
        // Get wall orientation
        int orientation = wall.getOrientationA();

        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();

        // Wall orientations:
        // 0 = West wall, 1 = North wall, 2 = East wall, 3 = South wall
        // Corner walls have additional bits

        // Simplified check - if there's a wall and we're moving perpendicular to it
        switch (orientation) {
            case 0: // West wall blocks east/west movement
                return dx != 0;
            case 1: // North wall blocks north/south movement
                return dy != 0;
            case 2: // East wall
                return dx != 0;
            case 3: // South wall
                return dy != 0;
            default:
                // For corner walls, block diagonal movement
                return dx != 0 && dy != 0;
        }
    }

    // ========================================================================
    // Obstacle Handling Tasks
    // ========================================================================

    /**
     * Create a task to handle a detected obstacle.
     *
     * @param obstacle the detected obstacle
     * @return optional containing the task to handle the obstacle
     */
    public Optional<InteractObjectTask> createHandleTask(DetectedObstacle obstacle) {
        if (obstacle == null || !obstacle.isBlocking()) {
            return Optional.empty();
        }

        ObstacleDefinition def = obstacle.getDefinition();
        TileObject object = obstacle.getObject();

        if (object == null) {
            return Optional.empty();
        }

        // Create task to interact with the obstacle
        InteractObjectTask task = new InteractObjectTask(object.getId(), def.getAction())
                .withDescription("Handle " + def.getName())
                .withSearchRadius(5)
                .withWaitForIdle(true);

        log.debug("Created task to handle obstacle: {} at {} using action '{}'",
                def.getName(), obstacle.getLocation(), def.getAction());

        return Optional.of(task);
    }

    /**
     * Attempt to handle an obstacle blocking the path.
     *
     * @param from the starting point
     * @param to   the destination point
     * @return optional containing a task to handle the obstacle
     */
    public Optional<InteractObjectTask> handleBlockingObstacle(WorldPoint from, WorldPoint to) {
        return findBlockingObstacle(from, to).flatMap(this::createHandleTask);
    }

    // ========================================================================
    // Obstacle Queries
    // ========================================================================

    /**
     * Check if an object ID is a known obstacle.
     *
     * @param objectId the object ID
     * @return true if this is a known obstacle
     */
    public boolean isKnownObstacle(int objectId) {
        return obstacleRegistry.containsKey(objectId);
    }

    /**
     * Get the obstacle definition for an object ID.
     *
     * @param objectId the object ID
     * @return optional containing the definition
     */
    public Optional<ObstacleDefinition> getObstacleDefinition(int objectId) {
        return Optional.ofNullable(obstacleRegistry.get(objectId));
    }

    /**
     * Get the traversal cost for an obstacle.
     *
     * @param objectId the object ID
     * @return cost in ticks, or 0 if not an obstacle
     */
    public int getTraversalCost(int objectId) {
        ObstacleDefinition def = obstacleRegistry.get(objectId);
        return def != null ? def.getTraversalCostTicks() : 0;
    }

    /**
     * Get the total number of registered obstacles.
     *
     * @return obstacle count
     */
    public int getRegisteredObstacleCount() {
        return obstacleRegistry.size();
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    /**
     * Represents a detected obstacle in the game world.
     */
    @Getter
    public static class DetectedObstacle {
        private final ObstacleDefinition definition;
        private final TileObject object;
        private final WorldPoint location;
        private final boolean blocking;

        public DetectedObstacle(ObstacleDefinition definition, TileObject object,
                                WorldPoint location, boolean blocking) {
            this.definition = definition;
            this.object = object;
            this.location = location;
            this.blocking = blocking;
        }

        @Override
        public String toString() {
            return String.format("DetectedObstacle[%s at %s, blocking=%s]",
                    definition.getName(), location, blocking);
        }
    }
}

