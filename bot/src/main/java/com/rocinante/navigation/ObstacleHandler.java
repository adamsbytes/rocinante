package com.rocinante.navigation;

import com.rocinante.tasks.impl.InteractObjectTask;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

/**
 * Handles obstacles that block navigation paths.
 * Per REQUIREMENTS.md Section 7.3.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Registry of obstacles: doors, gates, and agility shortcuts</li>
 *   <li>Detection of obstacles blocking a path</li>
 *   <li>Generation of tasks to handle obstacles</li>
 *   <li>Integration with PathFinder for obstacle traversal cost</li>
 *   <li>Risk assessment for agility shortcuts based on player level</li>
 * </ul>
 *
 * <p>This handler integrates with RuneLite's {@code AgilityShortcut} enum via
 * {@link AgilityShortcutData} to provide comprehensive coverage of all OSRS
 * agility shortcuts (150+).
 */
@Slf4j
@Singleton
public class ObstacleHandler {

    /**
     * Default risk threshold for avoiding risky shortcuts (10% failure rate).
     */
    public static final double DEFAULT_RISK_THRESHOLD = 0.10;

    private final Client client;
    private final AgilityShortcutData agilityShortcutData;
    private final SpatialObjectIndex spatialObjectIndex;

    /**
     * Registry of known obstacles by object ID.
     * Includes doors, gates, and manually registered obstacles.
     */
    private final Map<Integer, ObstacleDefinition> obstacleRegistry = new HashMap<>();

    /**
     * Registry of obstacles by location (for position-based lookup).
     */
    private final Map<WorldPoint, List<ObstacleDefinition>> obstaclesByLocation = new HashMap<>();

    /**
     * Telemetry cache for unknown obstacles we have already logged.
     */
    private final Set<Integer> loggedUnknownObstacleIds = new HashSet<>();

    @Inject
    public ObstacleHandler(Client client, AgilityShortcutData agilityShortcutData, 
                           SpatialObjectIndex spatialObjectIndex) {
        this.client = client;
        this.agilityShortcutData = agilityShortcutData;
        this.spatialObjectIndex = Objects.requireNonNull(spatialObjectIndex, "spatialObjectIndex required");
        initializeRegistry();
    }

    // ========================================================================
    // Registry Initialization
    // ========================================================================

    /**
     * Initialize the obstacle registry with doors, gates, and other obstacles.
     * Agility shortcuts are loaded from {@link AgilityShortcutData}.
     */
    private void initializeRegistry() {
        registerDoors();
        registerGates();
        registerTollGates();
        registerMiscObstacles();

        // Log summary
        int shortcutCount = agilityShortcutData != null ? agilityShortcutData.getShortcutCount() : 0;
        log.info("ObstacleHandler initialized: {} doors/gates/misc, {} agility shortcuts",
                obstacleRegistry.size(), shortcutCount);
    }

    /**
     * Register common door obstacles.
     * Uses RuneLite ObjectID constants for accurate IDs.
     */
    private void registerDoors() {
        // ====================================================================
        // Generic Wooden Doors (very common throughout the game)
        // ====================================================================
        registerObstacle(ObstacleDefinition.door("Wooden Door", ObjectID.DOOR, ObjectID.DOOR_4));
        registerObstacle(ObstacleDefinition.door("Wooden Door", ObjectID.DOOR_22, ObjectID.DOOR_24));
        registerObstacle(ObstacleDefinition.door("Wooden Door", ObjectID.DOOR_59, ObjectID.DOOR_59 + 1));
        registerObstacle(ObstacleDefinition.door("Wooden Door", ObjectID.DOOR_81, ObjectID.DOOR_82));
        registerObstacle(ObstacleDefinition.door("Wooden Door", ObjectID.DOOR_92, ObjectID.DOOR_93));
        registerObstacle(ObstacleDefinition.door("Wooden Door", ObjectID.DOOR_99, ObjectID.DOOR_99 + 1));
        registerObstacle(ObstacleDefinition.door("Wooden Door", ObjectID.DOOR_102, ObjectID.DOOR_102 + 1));
        registerObstacle(ObstacleDefinition.door("Wooden Door", ObjectID.DOOR_131, ObjectID.DOOR_131 + 1));
        registerObstacle(ObstacleDefinition.door("Wooden Door", ObjectID.DOOR_136, ObjectID.DOOR_136 + 1));
        
        // Common door IDs used throughout OSRS
        registerObstacle(ObstacleDefinition.door("Wooden Door", 1535, 1534));
        registerObstacle(ObstacleDefinition.door("Wooden Door", 1530, 1531));
        registerObstacle(ObstacleDefinition.door("Wooden Door", 1533, 1532));
        registerObstacle(ObstacleDefinition.door("Door", 1536, 1537));
        registerObstacle(ObstacleDefinition.door("Door", 1538, 1539));
        registerObstacle(ObstacleDefinition.door("Door", 1540, 1541));
        registerObstacle(ObstacleDefinition.door("Door", 1542, 1543));
        registerObstacle(ObstacleDefinition.door("Door", 1544, 1545));
        registerObstacle(ObstacleDefinition.door("Door", 1546, 1547));

        // ====================================================================
        // Large Doors
        // ====================================================================
        registerObstacle(ObstacleDefinition.door("Large Door", ObjectID.LARGE_DOOR, ObjectID.LARGE_DOOR_72));
        registerObstacle(ObstacleDefinition.door("Large Door", ObjectID.LARGE_DOOR_73, ObjectID.LARGE_DOOR_74));
        registerObstacle(ObstacleDefinition.door("Large Door", ObjectID.LARGE_DOOR_134, ObjectID.LARGE_DOOR_135));

        // ====================================================================
        // Lumbridge Castle Doors
        // ====================================================================
        registerObstacle(ObstacleDefinition.door("Lumbridge Castle Door", 1543, 1544));
        registerObstacle(ObstacleDefinition.door("Lumbridge Castle Door", 1545, 1546));

        // ====================================================================
        // Bank Doors
        // ====================================================================
        registerObstacle(ObstacleDefinition.door("Bank Door", 1804, 1805));
        registerObstacle(ObstacleDefinition.door("Bank Door", 11775, 11776));
        registerObstacle(ObstacleDefinition.door("Bank Door", 24381, 24382));
        registerObstacle(ObstacleDefinition.door("Bank Door", 11773, 11774));
        registerObstacle(ObstacleDefinition.door("Bank Door", 36978, 36979));

        // ====================================================================
        // Varrock Doors
        // ====================================================================
        registerObstacle(ObstacleDefinition.door("Varrock Door", 1540, 1541));
        registerObstacle(ObstacleDefinition.door("Varrock Palace Door", 1548, 1549));
        registerObstacle(ObstacleDefinition.door("Varrock Palace Door", 1550, 1551));

        // ====================================================================
        // Falador Doors
        // ====================================================================
        registerObstacle(ObstacleDefinition.door("Falador Door", 1558, 1559));
        registerObstacle(ObstacleDefinition.door("Falador Door", 1560, 1561));
        registerObstacle(ObstacleDefinition.door("Falador Castle Door", 24082, 24083));

        // ====================================================================
        // Draynor Doors
        // ====================================================================
        registerObstacle(ObstacleDefinition.door("Draynor Manor Door", 134, 135));
        registerObstacle(ObstacleDefinition.door("Draynor Manor Door", 136, 137));

        // ====================================================================
        // Prison Doors
        // ====================================================================
        registerObstacle(ObstacleDefinition.door("Prison Door", ObjectID.PRISON_GATE, ObjectID.PRISON_GATE_78));
        registerObstacle(ObstacleDefinition.door("Prison Door", ObjectID.PRISON_GATE_79, ObjectID.PRISON_GATE_80));

        // ====================================================================
        // Dungeon Doors
        // ====================================================================
        registerObstacle(ObstacleDefinition.door("Dungeon Door", 1725, 1726));
        registerObstacle(ObstacleDefinition.door("Dungeon Door", 1727, 1728));
        registerObstacle(ObstacleDefinition.door("Dungeon Door", 1551, 1552));

        // ====================================================================
        // Bamboo Door (Karamja)
        // ====================================================================
        registerObstacle(ObstacleDefinition.door("Bamboo Door", ObjectID.BAMBOO_DOOR, ObjectID.BAMBOO_DOOR + 1));
    }

    /**
     * Register common gate obstacles.
     */
    private void registerGates() {
        // ====================================================================
        // Generic Gates
        // ====================================================================
        registerObstacle(ObstacleDefinition.gate("Gate", ObjectID.GATE, ObjectID.GATE_38));
        registerObstacle(ObstacleDefinition.gate("Gate", ObjectID.GATE_39, ObjectID.GATE_39 + 1));
        registerObstacle(ObstacleDefinition.gate("Gate", ObjectID.GATE_47, ObjectID.GATE_48));
        registerObstacle(ObstacleDefinition.gate("Gate", ObjectID.GATE_49, ObjectID.GATE_50));
        registerObstacle(ObstacleDefinition.gate("Gate", ObjectID.GATE_52, ObjectID.GATE_53));
        registerObstacle(ObstacleDefinition.gate("Gate", ObjectID.GATE_89, ObjectID.GATE_90));
        registerObstacle(ObstacleDefinition.gate("Gate", ObjectID.GATE_94, ObjectID.GATE_95));
        registerObstacle(ObstacleDefinition.gate("Gate", ObjectID.GATE_166, ObjectID.GATE_167));
        registerObstacle(ObstacleDefinition.gate("Gate", ObjectID.GATE_190, ObjectID.GATE_190 + 1));

        // Common gate IDs
        registerObstacle(ObstacleDefinition.gate("Gate", 1551, 1552));
        registerObstacle(ObstacleDefinition.gate("Gate", 1553, 1554));
        registerObstacle(ObstacleDefinition.gate("Gate", 1555, 1556));
        registerObstacle(ObstacleDefinition.gate("Gate", 1596, 1597));
        registerObstacle(ObstacleDefinition.gate("Gate", 1598, 1599));

        // ====================================================================
        // Al Kharid Gate (toll gate)
        // ====================================================================
        registerObstacle(ObstacleDefinition.gate("Al Kharid Gate", 2882, 2883));
        registerObstacle(ObstacleDefinition.gate("Al Kharid Gate", 2881, 2880));

        // ====================================================================
        // Farm Gates
        // ====================================================================
        registerObstacle(ObstacleDefinition.gate("Farm Gate", 7136, 7137));
        registerObstacle(ObstacleDefinition.gate("Farm Gate", 7138, 7139));
        registerObstacle(ObstacleDefinition.gate("Farm Gate", 12985, 12986));
        registerObstacle(ObstacleDefinition.gate("Farm Gate", 15510, 15511));
        registerObstacle(ObstacleDefinition.gate("Farm Gate", 15514, 15515));

        // ====================================================================
        // Garden Gates
        // ====================================================================
        registerObstacle(ObstacleDefinition.gate("Garden Gate", 2050, 2051));
        registerObstacle(ObstacleDefinition.gate("Garden Gate", 2052, 2053));

        // ====================================================================
        // Cemetery/Church Gates
        // ====================================================================
        registerObstacle(ObstacleDefinition.gate("Cemetery Gate", 2623, 2624));
        registerObstacle(ObstacleDefinition.gate("Church Gate", 8723, 8724));

        // ====================================================================
        // City Gates
        // ====================================================================
        registerObstacle(ObstacleDefinition.gate("City Gate", ObjectID.CITY_GATE, ObjectID.CITY_GATE + 1));
        registerObstacle(ObstacleDefinition.gate("Ardougne Gate", 8727, 8728));
        registerObstacle(ObstacleDefinition.gate("Yanille Gate", 16522, 16523));

        // ====================================================================
        // Wilderness Gates
        // ====================================================================
        registerObstacle(ObstacleDefinition.gate("Wilderness Gate", 1596, 1597));

        // ====================================================================
        // Gnome Stronghold Gates
        // ====================================================================
        registerObstacle(ObstacleDefinition.gate("Gnome Gate", 2438, 2439));
        registerObstacle(ObstacleDefinition.gate("Gnome Gate", 2440, 2441));
    }

    /**
     * Register toll gates that require payment.
     * These gates have high traversal cost to discourage use unless necessary.
     */
    private void registerTollGates() {
        // ====================================================================
        // Al Kharid Gate (Lumbridge <-> Al Kharid)
        // ====================================================================
        // Costs 10gp to pass, free after Prince Ali Rescue quest
        // Only use if player has at least 1000gp total
        // Object IDs: 44052, 44053, 44054, 44055 (different gate sections)
        registerObstacle(ObstacleDefinition.tollGate(
                "Al Kharid Gate",
                44052,
                "Pay-toll(10gp)",
                10,                         // 10gp toll
                1000,                       // Only use if player has 1000+ gp total
                Quest.PRINCE_ALI_RESCUE     // Free passage after quest
        ));
        registerObstacle(ObstacleDefinition.tollGate(
                "Al Kharid Gate",
                44053,
                "Pay-toll(10gp)",
                10,
                1000,
                Quest.PRINCE_ALI_RESCUE
        ));
        registerObstacle(ObstacleDefinition.tollGate(
                "Al Kharid Gate",
                44054,
                "Pay-toll(10gp)",
                10,
                1000,
                Quest.PRINCE_ALI_RESCUE
        ));
        registerObstacle(ObstacleDefinition.tollGate(
                "Al Kharid Gate",
                44055,
                "Pay-toll(10gp)",
                10,
                1000,
                Quest.PRINCE_ALI_RESCUE
        ));

        // ====================================================================
        // Shantay Pass (Al Kharid <-> Kharidian Desert)
        // ====================================================================
        // Requires Shantay Pass item (costs 5gp from Shantay)
        // Only use if player has at least 1000gp total
        // Item ID: 1854 (Shantay Pass)
        registerObstacle(ObstacleDefinition.itemTollGate(
                "Shantay Pass",
                ObjectID.SHANTAY_PASS,      // 4031
                "Go-through",
                1854,                        // Shantay Pass item ID
                5,                           // 5gp to buy pass
                1000                         // Minimum gold to use
        ));
        // Alternate Shantay Pass object ID
        registerObstacle(ObstacleDefinition.itemTollGate(
                "Shantay Pass",
                41326,                       // SHANTAY_PASS_41326
                "Go-through",
                1854,
                5,
                1000
        ));
    }

    /**
     * Register miscellaneous obstacles.
     */
    private void registerMiscObstacles() {
        // ====================================================================
        // Wilderness Ditch - spans multiple tiles along the border
        // Object IDs from RuneLite gameval/ObjectID.java:
        //   DITCH_WILDERNESS1_GROUND = 23261, DITCH_WILDERNESS1A_GROUND = 23262
        //   DITCH_WILDERNESS3_GROUND = 23263, DITCH_WILDERNESS3A_GROUND = 23264
        //   DITCH_WILDERNESS4_GROUND = 23265, DITCH_WILDERNESS4A_GROUND = 23266
        //   DITCH_WILDERNESSE_GROUND = 23267, DITCH_WILDERNESSEA_GROUND = 23268
        //   DITCH_WILDERNESS_END_NORTH = 23269, DITCH_WILDERNESS_END_SOUTH = 23270
        //   DITCH_WILDERNESS_COVER = 23271 (main clickable)
        //   DITCH_WILDERNESS_COVER_MEMBERS = 50652
        // ====================================================================
        // Wilderness ditch spans multiple tiles - use findClosestWildernessDitch() for interaction
        registerObstacle(ObstacleDefinition.builder()
                .name("Wilderness Ditch")
                .type(ObstacleDefinition.ObstacleType.OTHER)
                .objectIds(List.of(
                        23271,  // DITCH_WILDERNESS_COVER (main)
                        50652,  // DITCH_WILDERNESS_COVER_MEMBERS
                        23261, 23262, 23263, 23264, 23265, 23266, 23267, 23268, 23269, 23270))
                .blockedStateId(23271)
                .passableStateId(-1)
                .action("Cross")
                .traversalCostTicks(3)
                .build());

        // ====================================================================
        // Trapdoors (common dungeon entrances)
        // ====================================================================
        registerObstacle(ObstacleDefinition.trapdoor("Edgeville Dungeon Trapdoor", 1579, 1580, "Open", -1));
        registerObstacle(ObstacleDefinition.trapdoor("Trapdoor", ObjectID.TRAPDOOR, ObjectID.TRAPDOOR_105, "Open", -1));
        registerObstacle(ObstacleDefinition.trapdoor("Trapdoor", ObjectID.TRAPDOOR_106, -1, "Climb-down", -1));

        // ====================================================================
        // Webs (slashable obstacles)
        // ====================================================================
        registerObstacle(ObstacleDefinition.builder()
                .name("Web")
                .type(ObstacleDefinition.ObstacleType.OTHER)
                .objectIds(List.of(733))
                .blockedStateId(733)
                .passableStateId(734)
                .action("Slash")
                .traversalCostTicks(2)
                .build());

        // ====================================================================
        // Rockslides
        // ====================================================================
        registerObstacle(ObstacleDefinition.builder()
                .name("Rockslide")
                .type(ObstacleDefinition.ObstacleType.OTHER)
                .objectIds(List.of(880, 881))
                .blockedStateId(880)
                .passableStateId(-1)
                .action("Mine")
                .traversalCostTicks(5)
                .build());

        // ====================================================================
        // Crawl-through tunnels
        // ====================================================================
        registerObstacle(ObstacleDefinition.builder()
                .name("Crawl Tunnel")
                .type(ObstacleDefinition.ObstacleType.OTHER)
                .objectIds(List.of(9293, 9294))
                .blockedStateId(9293)
                .passableStateId(-1)
                .action("Crawl-through")
                .traversalCostTicks(4)
                .build());
    }

    // ========================================================================
    // Registration Methods
    // ========================================================================

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
     * <p>Uses {@link SpatialObjectIndex} for O(1) grid-based lookup instead of 
     * O((2R+1)^2) tile iteration.
     *
     * @param center the center point
     * @param radius the search radius in tiles
     * @return list of detected obstacles
     */
    public List<DetectedObstacle> findObstaclesNearby(WorldPoint center, int radius) {
        List<DetectedObstacle> obstacles = new ArrayList<>();
        
        if (center == null) {
            return obstacles;
        }

        // Get all objects in the search area (pass null for objectIds = all objects)
        List<SpatialObjectIndex.ObjectEntry> entries = 
            spatialObjectIndex.findObjectsNearby(center, radius, null);
        
        for (SpatialObjectIndex.ObjectEntry entry : entries) {
            TileObject obj = entry.getObject();
            if (obj == null) {
                continue;
            }
            
            int objectId = obj.getId();
            ObstacleDefinition def = getObstacleDefinition(objectId);
            if (def != null) {
                obstacles.add(new DetectedObstacle(def, obj, entry.getPosition(),
                        def.isBlocked(objectId)));
            }
        }
        
        return obstacles;
    }

    /**
     * Find the closest obstacle instance by name within search radius.
     * 
     * <p>This is particularly useful for obstacles that span multiple tiles like the
     * wilderness ditch, where the player should interact with the nearest clickable segment.
     *
     * @param playerLocation the player's current location
     * @param obstacleName   the obstacle name to search for
     * @param searchRadius   maximum search radius in tiles
     * @return the closest detected obstacle, or empty if none found
     */
    public Optional<DetectedObstacle> findClosestObstacleByName(WorldPoint playerLocation, 
                                                                  String obstacleName, 
                                                                  int searchRadius) {
        List<DetectedObstacle> nearby = findObstaclesNearby(playerLocation, searchRadius);
        
        return nearby.stream()
                .filter(obs -> obs.getDefinition().getName().equalsIgnoreCase(obstacleName))
                .min(Comparator.comparingInt(obs -> {
                    int dx = obs.getLocation().getX() - playerLocation.getX();
                    int dy = obs.getLocation().getY() - playerLocation.getY();
                    return dx * dx + dy * dy; // Squared distance (avoids sqrt)
                }));
    }

    /**
     * Find the closest obstacle instance by object ID within search radius.
     * 
     * <p>For obstacles with multiple valid object IDs (like wilderness ditch segments),
     * this finds any matching instance and returns the closest one.
     *
     * @param playerLocation the player's current location
     * @param objectIds      set of valid object IDs
     * @param searchRadius   maximum search radius in tiles
     * @return the closest detected obstacle, or empty if none found
     */
    public Optional<DetectedObstacle> findClosestObstacleByIds(WorldPoint playerLocation, 
                                                                 Set<Integer> objectIds, 
                                                                 int searchRadius) {
        Scene scene = client.getScene();
        if (scene == null) {
            return Optional.empty();
        }

        Tile[][][] tiles = scene.getTiles();
        int plane = playerLocation.getPlane();

        LocalPoint centerLocal = LocalPoint.fromWorld(client, playerLocation);
        if (centerLocal == null) {
            return Optional.empty();
        }

        int centerSceneX = centerLocal.getSceneX();
        int centerSceneY = centerLocal.getSceneY();

        DetectedObstacle closest = null;
        int closestDistSq = Integer.MAX_VALUE;

        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dy = -searchRadius; dy <= searchRadius; dy++) {
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

                // Check game objects
                for (GameObject gameObject : tile.getGameObjects()) {
                    if (gameObject == null) {
                        continue;
                    }
                    if (objectIds.contains(gameObject.getId())) {
                        WorldPoint loc = tile.getWorldLocation();
                        int distSq = dx * dx + dy * dy;
                        if (distSq < closestDistSq) {
                            ObstacleDefinition def = getObstacleDefinition(gameObject.getId());
                            if (def != null) {
                                closest = new DetectedObstacle(def, gameObject, loc, true);
                                closestDistSq = distSq;
                            }
                        }
                    }
                }
            }
        }

        return Optional.ofNullable(closest);
    }

    /**
     * Find the closest wilderness ditch segment to cross.
     * 
     * <p>The wilderness ditch spans the entire northern border. This method finds
     * the nearest clickable ditch segment so the player takes the shortest path
     * to cross into or out of the wilderness.
     *
     * @param playerLocation the player's current location
     * @return the closest ditch segment, or empty if none visible
     */
    public Optional<DetectedObstacle> findClosestWildernessDitch(WorldPoint playerLocation) {
        // Wilderness ditch object IDs
        Set<Integer> ditchIds = Set.of(
                23271,  // DITCH_WILDERNESS_COVER (main clickable)
                50652   // DITCH_WILDERNESS_COVER_MEMBERS
        );
        // Search within 20 tiles - ditch should always be visible when nearby
        return findClosestObstacleByIds(playerLocation, ditchIds, 20);
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
            ObstacleDefinition def = getObstacleDefinition(wallObject.getId());
            if (def != null && def.isBlocked(wallObject.getId())) {
                // Check if wall is in the direction of movement
                if (isWallBlockingDirection(wallObject, check, other)) {
                    return Optional.of(new DetectedObstacle(def, wallObject, check, true));
                }
            } else if (def == null && isWallBlockingDirection(wallObject, check, other)) {
                logUnknownObstacle(wallObject.getId(), wallObject.getWorldLocation());
            }
        }

        // Check game objects
        for (GameObject gameObject : tile.getGameObjects()) {
            if (gameObject == null) {
                continue;
            }
            ObstacleDefinition def = getObstacleDefinition(gameObject.getId());
            if (def != null && def.isBlocked(gameObject.getId())) {
                return Optional.of(new DetectedObstacle(def, gameObject, check, true));
            } else if (def == null) {
                logUnknownObstacle(gameObject.getId(), gameObject.getWorldLocation());
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

    /**
     * Log unknown obstacle IDs once to avoid spam, then debug on subsequent hits.
     */
    private void logUnknownObstacle(int objectId, WorldPoint location) {
        if (objectId <= 0) {
            return;
        }
        if (loggedUnknownObstacleIds.add(objectId)) {
            log.info("Detected unknown obstacle id={} at {}", objectId, location);
        } else {
            log.debug("Detected unknown obstacle id={} at {}", objectId, location);
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
        // Check doors/gates registry
        if (obstacleRegistry.containsKey(objectId)) {
            return true;
        }
        // Check agility shortcuts
        if (agilityShortcutData != null && agilityShortcutData.isKnownShortcut(objectId)) {
            return true;
        }
        return false;
    }

    /**
     * Get the obstacle definition for an object ID.
     * Checks both doors/gates and agility shortcuts.
     *
     * @param objectId the object ID
     * @return the obstacle definition, or null if not found
     */
    @Nullable
    public ObstacleDefinition getObstacleDefinition(int objectId) {
        // Check doors/gates registry first
        ObstacleDefinition def = obstacleRegistry.get(objectId);
        if (def != null) {
            return def;
        }
        // Check agility shortcuts
        if (agilityShortcutData != null) {
            return agilityShortcutData.getDefinitionByObjectId(objectId);
        }
        return null;
    }

    /**
     * Get the traversal cost for an obstacle.
     *
     * @param objectId the object ID
     * @return cost in ticks, or 0 if not an obstacle
     */
    public int getTraversalCost(int objectId) {
        ObstacleDefinition def = getObstacleDefinition(objectId);
        return def != null ? def.getTraversalCostTicks() : 0;
    }

    /**
     * Get the total number of registered obstacles (excluding agility shortcuts).
     *
     * @return obstacle count
     */
    public int getRegisteredObstacleCount() {
        return obstacleRegistry.size();
    }

    /**
     * Get the total number of agility shortcuts.
     *
     * @return shortcut count
     */
    public int getAgilityShortcutCount() {
        return agilityShortcutData != null ? agilityShortcutData.getShortcutCount() : 0;
    }

    // ========================================================================
    // Agility Shortcut Methods
    // ========================================================================

    /**
     * Check if a player can traverse an obstacle based on requirements.
     *
     * @param objectId           the object ID
     * @param playerAgilityLevel the player's agility level
     * @return true if the player can traverse the obstacle
     */
    public boolean canTraverse(int objectId, int playerAgilityLevel) {
        ObstacleDefinition def = getObstacleDefinition(objectId);
        if (def == null) {
            return false;
        }
        return def.canAttempt(playerAgilityLevel);
    }

    /**
     * Get the failure rate for an obstacle at a given player level.
     *
     * @param objectId           the object ID
     * @param playerAgilityLevel the player's agility level
     * @return failure rate between 0.0 and 1.0, or 0.0 if not an obstacle
     */
    public double getFailureRate(int objectId, int playerAgilityLevel) {
        ObstacleDefinition def = getObstacleDefinition(objectId);
        if (def == null) {
            return 0.0;
        }
        return def.calculateFailureRate(playerAgilityLevel);
    }

    /**
     * Check if an obstacle should be avoided based on risk.
     *
     * @param objectId           the object ID
     * @param playerAgilityLevel the player's agility level
     * @param riskThreshold      maximum acceptable failure rate (0.0 to 1.0)
     * @return true if the obstacle should be avoided
     */
    public boolean shouldAvoidObstacle(int objectId, int playerAgilityLevel, double riskThreshold) {
        ObstacleDefinition def = getObstacleDefinition(objectId);
        if (def == null) {
            return false;
        }

        // Only apply risk checking to agility shortcuts
        if (!def.isAgilityShortcut()) {
            return false;
        }

        // Can't traverse if level is too low
        if (!def.canAttempt(playerAgilityLevel)) {
            return true;
        }

        // Avoid if too risky
        return def.isRisky(playerAgilityLevel, riskThreshold);
    }

    /**
     * Check if an obstacle should be avoided using the default risk threshold.
     *
     * @param objectId           the object ID
     * @param playerAgilityLevel the player's agility level
     * @return true if the obstacle should be avoided
     */
    public boolean shouldAvoidObstacle(int objectId, int playerAgilityLevel) {
        return shouldAvoidObstacle(objectId, playerAgilityLevel, DEFAULT_RISK_THRESHOLD);
    }

    /**
     * Get all agility shortcuts usable by a player.
     *
     * @param playerAgilityLevel the player's agility level
     * @return list of usable shortcuts
     */
    public List<ObstacleDefinition> getUsableShortcuts(int playerAgilityLevel) {
        if (agilityShortcutData == null) {
            return Collections.emptyList();
        }
        return agilityShortcutData.getUsableShortcuts(playerAgilityLevel);
    }

    /**
     * Get all agility shortcuts that are safe for a player.
     *
     * @param playerAgilityLevel the player's agility level
     * @return list of safe shortcuts
     */
    public List<ObstacleDefinition> getSafeShortcuts(int playerAgilityLevel) {
        if (agilityShortcutData == null) {
            return Collections.emptyList();
        }
        return agilityShortcutData.getSafeShortcuts(playerAgilityLevel);
    }

    // ========================================================================
    // Toll Gate Methods
    // ========================================================================

    /**
     * Check if an obstacle is a toll gate.
     *
     * @param objectId the object ID
     * @return true if this is a toll gate
     */
    public boolean isTollGate(int objectId) {
        ObstacleDefinition def = getObstacleDefinition(objectId);
        return def != null && def.isTollGate();
    }

    /**
     * Check if a player can use a toll gate.
     * Considers total gold (inventory + bank), quest completion for free passage,
     * and item requirements.
     *
     * @param objectId         the object ID
     * @param totalGold        player's total gold (inventory + bank)
     * @param questCompleted   whether the free passage quest is completed
     * @param hasRequiredItem  whether the player has the required item (for item toll gates)
     * @return true if the player can use this toll gate
     */
    public boolean canUseTollGate(int objectId, int totalGold, boolean questCompleted, boolean hasRequiredItem) {
        ObstacleDefinition def = getObstacleDefinition(objectId);
        if (def == null || !def.isTollGate()) {
            return true; // Not a toll gate, can always use
        }

        // Check for free passage via quest
        if (def.hasFreePassage(questCompleted)) {
            return true;
        }

        // Check if player can afford the toll
        return def.canAffordToll(totalGold, hasRequiredItem);
    }

    /**
     * Check if using a toll gate is advisable (has enough gold cushion).
     * This is more conservative - ensures player won't deplete resources.
     *
     * @param objectId   the object ID
     * @param totalGold  player's total gold (inventory + bank)
     * @return true if using this toll gate is advisable
     */
    public boolean shouldUseTollGate(int objectId, int totalGold) {
        ObstacleDefinition def = getObstacleDefinition(objectId);
        if (def == null || !def.isTollGate()) {
            return true; // Not a toll gate
        }
        return def.shouldUseTollGate(totalGold);
    }

    /**
     * Get the toll cost for an obstacle.
     *
     * @param objectId the object ID
     * @return toll cost in gold, or 0 if not a toll gate
     */
    public int getTollCost(int objectId) {
        ObstacleDefinition def = getObstacleDefinition(objectId);
        if (def == null) {
            return 0;
        }
        return def.getTollCost();
    }

    /**
     * Get the required item ID for a toll gate.
     *
     * @param objectId the object ID
     * @return required item ID, or -1 if no item required
     */
    public int getTollRequiredItem(int objectId) {
        ObstacleDefinition def = getObstacleDefinition(objectId);
        if (def == null) {
            return -1;
        }
        return def.getRequiredItemId();
    }

    /**
     * Get the free passage quest for a toll gate.
     *
     * @param objectId the object ID
     * @return the quest that grants free passage, or null if none
     */
    @Nullable
    public Quest getTollFreePassageQuest(int objectId) {
        ObstacleDefinition def = getObstacleDefinition(objectId);
        if (def == null) {
            return null;
        }
        return def.getFreePassageQuest();
    }

    /**
     * Find agility shortcuts near a location.
     *
     * @param center      the center point
     * @param maxDistance maximum distance in tiles
     * @return list of nearby shortcuts
     */
    public List<ObstacleDefinition> findShortcutsNear(WorldPoint center, int maxDistance) {
        if (agilityShortcutData == null) {
            return Collections.emptyList();
        }
        return agilityShortcutData.findShortcutsNear(center, maxDistance);
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
