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
import java.util.stream.Collectors;

/**
 * Handles plane transitions (vertical movement) via stairs, ladders, and trapdoors.
 * 
 * <p>This service provides:
 * <ul>
 *   <li>Registry of common stairs, ladders, and trapdoors by object ID</li>
 *   <li>Source/destination plane tracking for each transition</li>
 *   <li>Bidirectional transition support (up/down)</li>
 *   <li>Action mapping ("Climb-up", "Climb-down", "Climb", "Descend")</li>
 *   <li>Integration with pathfinding for multi-floor navigation</li>
 * </ul>
 *
 * <p>Plane transitions move the player between floors. In OSRS:
 * <ul>
 *   <li>Plane 0 = Ground floor</li>
 *   <li>Plane 1 = First floor (upstairs)</li>
 *   <li>Plane 2 = Second floor</li>
 *   <li>Plane 3 = Third floor (max)</li>
 *   <li>Some areas use negative planes for underground</li>
 * </ul>
 */
@Slf4j
@Singleton
public class PlaneTransitionHandler {

    private final Client client;

    /**
     * Map of object ID to plane transition definitions.
     */
    private final Map<Integer, PlaneTransition> transitionsByObjectId = new HashMap<>();

    /**
     * Map of location to plane transitions (for ambiguous cases).
     */
    private final Map<WorldPoint, List<PlaneTransition>> transitionsByLocation = new HashMap<>();

    /**
     * All registered transitions.
     */
    @Getter
    private final List<PlaneTransition> allTransitions = new ArrayList<>();

    @Inject
    public PlaneTransitionHandler(Client client) {
        this.client = client;
        initializeTransitions();
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    /**
     * Initialize the plane transition registry.
     */
    private void initializeTransitions() {
        registerLadders();
        registerStairs();
        registerTrapdoors();
        registerSpecialTransitions();

        log.info("PlaneTransitionHandler initialized: {} transitions registered",
                allTransitions.size());
    }

    /**
     * Register common ladder transitions.
     */
    private void registerLadders() {
        // ====================================================================
        // Generic Ladders (bidirectional)
        // ====================================================================
        // Most ladders can go both up and down based on context
        registerBidirectionalLadder("Ladder", ObjectID.LADDER, "Climb");
        registerBidirectionalLadder("Ladder", ObjectID.LADDER_11, "Climb");
        registerBidirectionalLadder("Ladder", ObjectID.LADDER_60, "Climb");
        registerBidirectionalLadder("Ladder", ObjectID.LADDER_101, "Climb");
        registerBidirectionalLadder("Ladder", ObjectID.LADDER_132, "Climb");
        registerBidirectionalLadder("Ladder", ObjectID.LADDER_133, "Climb");
        registerBidirectionalLadder("Ladder", ObjectID.LADDER_195, "Climb");
        registerBidirectionalLadder("Ladder", ObjectID.LADDER_287, "Climb");

        // Numbered ladder variants (common in dungeons and buildings)
        registerBidirectionalLadder("Ladder", 16679, "Climb");
        registerBidirectionalLadder("Ladder", 16680, "Climb");
        registerBidirectionalLadder("Ladder", 16681, "Climb");
        registerBidirectionalLadder("Ladder", 16682, "Climb");
        registerBidirectionalLadder("Ladder", 16683, "Climb");
        registerBidirectionalLadder("Ladder", 16684, "Climb");
        registerBidirectionalLadder("Ladder", ObjectID.LADDER_2147, "Climb");
        registerBidirectionalLadder("Ladder", ObjectID.LADDER_2148, "Climb");
        registerBidirectionalLadder("Ladder", ObjectID.LADDER_2268, "Climb");
        registerBidirectionalLadder("Ladder", ObjectID.LADDER_2269, "Climb");

        // ====================================================================
        // Up-only Ladders
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Ladder (Up)")
                .objectId(ObjectID.LADDER_743)
                .action("Climb-up")
                .transitionType(TransitionType.LADDER)
                .direction(TransitionDirection.UP)
                .planeChange(1)
                .build());

        // ====================================================================
        // Down-only Ladders
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Ladder (Down)")
                .objectId(ObjectID.BROKEN_LADDER)
                .action("Climb-down")
                .transitionType(TransitionType.LADDER)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .build());

        // ====================================================================
        // Lumbridge Castle Ladders
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Lumbridge Castle Ladder")
                .objectId(16671)
                .action("Climb-up")
                .transitionType(TransitionType.LADDER)
                .direction(TransitionDirection.UP)
                .planeChange(1)
                .build());

        registerTransition(PlaneTransition.builder()
                .name("Lumbridge Castle Ladder")
                .objectId(16672)
                .action("Climb-down")
                .transitionType(TransitionType.LADDER)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .build());

        // ====================================================================
        // Varrock Ladders
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Varrock Palace Ladder")
                .objectId(11807)
                .action("Climb-up")
                .transitionType(TransitionType.LADDER)
                .direction(TransitionDirection.UP)
                .planeChange(1)
                .build());

        registerTransition(PlaneTransition.builder()
                .name("Varrock Palace Ladder")
                .objectId(11808)
                .action("Climb-down")
                .transitionType(TransitionType.LADDER)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .build());

        // ====================================================================
        // Bank Ladders
        // ====================================================================
        registerBidirectionalLadder("Bank Ladder", 11789, "Climb");
        registerBidirectionalLadder("Bank Ladder", 11790, "Climb");
        registerBidirectionalLadder("Bank Ladder", 16669, "Climb");
        registerBidirectionalLadder("Bank Ladder", 16670, "Climb");

        // ====================================================================
        // Dungeon Ladders
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Dungeon Entrance Ladder")
                .objectId(17385)
                .action("Climb-down")
                .transitionType(TransitionType.LADDER)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .build());

        registerTransition(PlaneTransition.builder()
                .name("Dungeon Exit Ladder")
                .objectId(17386)
                .action("Climb-up")
                .transitionType(TransitionType.LADDER)
                .direction(TransitionDirection.UP)
                .planeChange(1)
                .build());
    }

    /**
     * Register common staircase transitions.
     */
    private void registerStairs() {
        // ====================================================================
        // Generic Staircases
        // ====================================================================
        registerBidirectionalStairs("Staircase", ObjectID.STAIRCASE, "Climb");
        registerBidirectionalStairs("Staircase", ObjectID.STAIRCASE_2118, "Climb");
        registerBidirectionalStairs("Staircase", ObjectID.STAIRCASE_2119, "Climb");
        registerBidirectionalStairs("Staircase", ObjectID.STAIRCASE_2120, "Climb");
        registerBidirectionalStairs("Staircase", ObjectID.STAIRCASE_2121, "Climb");
        registerBidirectionalStairs("Staircase", ObjectID.STAIRCASE_2122, "Climb");
        
        // Common stair IDs
        registerBidirectionalStairs("Staircase", 16671, "Climb");
        registerBidirectionalStairs("Staircase", 16672, "Climb");
        registerBidirectionalStairs("Staircase", 16673, "Climb");
        registerBidirectionalStairs("Staircase", ObjectID.STAIRCASE_2608, "Climb");
        registerBidirectionalStairs("Staircase", ObjectID.STAIRCASE_2609, "Climb");
        registerBidirectionalStairs("Staircase", ObjectID.STAIRCASE_2610, "Climb");

        // ====================================================================
        // Stone Staircases
        // ====================================================================
        registerBidirectionalStairs("Stone Staircase", ObjectID.STONE_STAIRCASE, "Climb");
        registerBidirectionalStairs("Stone Staircase", ObjectID.STONE_STAIRCASE_3789, "Climb");

        // ====================================================================
        // Lumbridge Castle Stairs
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Lumbridge Castle Stairs")
                .objectId(16671)
                .action("Climb-up")
                .transitionType(TransitionType.STAIRS)
                .direction(TransitionDirection.UP)
                .planeChange(1)
                .build());

        registerTransition(PlaneTransition.builder()
                .name("Lumbridge Castle Stairs")
                .objectId(16673)
                .action("Climb-down")
                .transitionType(TransitionType.STAIRS)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .build());

        // ====================================================================
        // Varrock Palace Stairs
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Varrock Palace Stairs")
                .objectId(11796)
                .action("Climb-up")
                .transitionType(TransitionType.STAIRS)
                .direction(TransitionDirection.UP)
                .planeChange(1)
                .build());

        registerTransition(PlaneTransition.builder()
                .name("Varrock Palace Stairs")
                .objectId(11797)
                .action("Climb-down")
                .transitionType(TransitionType.STAIRS)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .build());

        // ====================================================================
        // Falador Castle Stairs
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Falador Castle Stairs")
                .objectId(24072)
                .action("Climb-up")
                .transitionType(TransitionType.STAIRS)
                .direction(TransitionDirection.UP)
                .planeChange(1)
                .build());

        registerTransition(PlaneTransition.builder()
                .name("Falador Castle Stairs")
                .objectId(24073)
                .action("Climb-down")
                .transitionType(TransitionType.STAIRS)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .build());

    }

    /**
     * Register trapdoor transitions.
     */
    private void registerTrapdoors() {
        // ====================================================================
        // Generic Trapdoors (usually go down)
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Trapdoor")
                .objectId(ObjectID.TRAPDOOR)
                .action("Open")
                .transitionType(TransitionType.TRAPDOOR)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .requiresOpen(true)
                .openId(ObjectID.TRAPDOOR_105)
                .build());

        registerTransition(PlaneTransition.builder()
                .name("Trapdoor")
                .objectId(ObjectID.TRAPDOOR_105)
                .action("Climb-down")
                .transitionType(TransitionType.TRAPDOOR)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .build());

        // ====================================================================
        // Edgeville Dungeon Trapdoor
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Edgeville Dungeon Trapdoor")
                .objectId(1579)
                .action("Open")
                .transitionType(TransitionType.TRAPDOOR)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .requiresOpen(true)
                .openId(1580)
                .build());

        registerTransition(PlaneTransition.builder()
                .name("Edgeville Dungeon Trapdoor")
                .objectId(1580)
                .action("Climb-down")
                .transitionType(TransitionType.TRAPDOOR)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .build());

        // ====================================================================
        // Varrock Sewers Manhole
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Varrock Sewer Manhole")
                .objectId(882)
                .action("Open")
                .transitionType(TransitionType.TRAPDOOR)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .requiresOpen(true)
                .openId(883)
                .build());

        registerTransition(PlaneTransition.builder()
                .name("Varrock Sewer Manhole")
                .objectId(883)
                .action("Climb-down")
                .transitionType(TransitionType.TRAPDOOR)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .build());

        // ====================================================================
        // Draynor Manor Basement Trapdoor
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Draynor Manor Trapdoor")
                .objectId(11443)
                .action("Climb-down")
                .transitionType(TransitionType.TRAPDOOR)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .build());
    }

    /**
     * Register special transition objects (rope, chain, etc.).
     */
    private void registerSpecialTransitions() {
        // ====================================================================
        // Rope Climbs
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Rope")
                .objectId(ObjectID.ROPE)
                .action("Climb")
                .transitionType(TransitionType.ROPE)
                .direction(TransitionDirection.BIDIRECTIONAL)
                .planeChange(1)
                .build());

        // ====================================================================
        // Chain Climbs
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Chain")
                .objectId(ObjectID.CHAIN)
                .action("Climb-up")
                .transitionType(TransitionType.CHAIN)
                .direction(TransitionDirection.UP)
                .planeChange(1)
                .build());

        // ====================================================================
        // Dungeon Entrance/Exits
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Dark Hole")
                .objectId(ObjectID.DARK_HOLE)
                .action("Enter")
                .transitionType(TransitionType.HOLE)
                .direction(TransitionDirection.DOWN)
                .planeChange(-1)
                .build());

        // ====================================================================
        // Underground Pass (Mine Cart, Pipe, etc.)
        // ====================================================================
        registerTransition(PlaneTransition.builder()
                .name("Mine Cart")
                .objectId(3241)
                .action("Ride")
                .transitionType(TransitionType.TRANSPORT)
                .direction(TransitionDirection.BIDIRECTIONAL)
                .planeChange(0)
                .build());
    }

    // ========================================================================
    // Registration Helpers
    // ========================================================================

    /**
     * Register a bidirectional ladder (can go up or down).
     */
    private void registerBidirectionalLadder(String name, int objectId, String action) {
        registerTransition(PlaneTransition.builder()
                .name(name)
                .objectId(objectId)
                .action(action)
                .transitionType(TransitionType.LADDER)
                .direction(TransitionDirection.BIDIRECTIONAL)
                .planeChange(0) // Determined at runtime
                .build());
    }

    /**
     * Register a bidirectional staircase.
     */
    private void registerBidirectionalStairs(String name, int objectId, String action) {
        registerTransition(PlaneTransition.builder()
                .name(name)
                .objectId(objectId)
                .action(action)
                .transitionType(TransitionType.STAIRS)
                .direction(TransitionDirection.BIDIRECTIONAL)
                .planeChange(0)
                .build());
    }

    /**
     * Register a plane transition.
     */
    public void registerTransition(PlaneTransition transition) {
        transitionsByObjectId.put(transition.getObjectId(), transition);
        allTransitions.add(transition);
    }

    /**
     * Register a transition at a specific location.
     */
    public void registerTransitionAtLocation(WorldPoint location, PlaneTransition transition) {
        transitionsByLocation.computeIfAbsent(location, k -> new ArrayList<>()).add(transition);
    }

    // ========================================================================
    // Transition Detection
    // ========================================================================

    /**
     * Check if an object ID is a known plane transition.
     *
     * @param objectId the object ID
     * @return true if this is a plane transition
     */
    public boolean isPlaneTransition(int objectId) {
        return transitionsByObjectId.containsKey(objectId);
    }

    /**
     * Get the plane transition definition for an object ID.
     *
     * @param objectId the object ID
     * @return the transition, or null if not found
     */
    @Nullable
    public PlaneTransition getTransition(int objectId) {
        return transitionsByObjectId.get(objectId);
    }

    /**
     * Find plane transitions near a location.
     *
     * @param center   the center point
     * @param radius   search radius in tiles
     * @return list of detected transitions
     */
    public List<DetectedTransition> findTransitionsNearby(WorldPoint center, int radius) {
        List<DetectedTransition> transitions = new ArrayList<>();

        Scene scene = client.getScene();
        if (scene == null) {
            return transitions;
        }

        Tile[][][] tiles = scene.getTiles();
        int plane = center.getPlane();

        LocalPoint centerLocal = LocalPoint.fromWorld(client, center);
        if (centerLocal == null) {
            return transitions;
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

                // Check game objects
                for (GameObject gameObject : tile.getGameObjects()) {
                    if (gameObject == null) {
                        continue;
                    }
                    PlaneTransition def = getTransition(gameObject.getId());
                    if (def != null) {
                        WorldPoint location = tile.getWorldLocation();
                        transitions.add(new DetectedTransition(def, gameObject, location));
                    }
                }

                // Check ground objects (trapdoors)
                GroundObject groundObject = tile.getGroundObject();
                if (groundObject != null) {
                    PlaneTransition def = getTransition(groundObject.getId());
                    if (def != null) {
                        WorldPoint location = tile.getWorldLocation();
                        transitions.add(new DetectedTransition(def, groundObject, location));
                    }
                }
            }
        }

        return transitions;
    }

    /**
     * Find the best transition to reach a target plane.
     *
     * @param currentLocation current player location
     * @param targetPlane     target plane to reach
     * @param searchRadius    radius to search
     * @return optional containing the best transition
     */
    public Optional<DetectedTransition> findTransitionToPlane(WorldPoint currentLocation,
                                                                int targetPlane,
                                                                int searchRadius) {
        int currentPlane = currentLocation.getPlane();
        int planeDiff = targetPlane - currentPlane;

        if (planeDiff == 0) {
            return Optional.empty();
        }

        List<DetectedTransition> nearby = findTransitionsNearby(currentLocation, searchRadius);

        // Filter transitions that go in the right direction
        TransitionDirection needed = planeDiff > 0 ? TransitionDirection.UP : TransitionDirection.DOWN;

        return nearby.stream()
                .filter(t -> {
                    TransitionDirection dir = t.getDefinition().getDirection();
                    return dir == needed || dir == TransitionDirection.BIDIRECTIONAL;
                })
                .min(Comparator.comparingInt(t ->
                        t.getLocation().distanceTo(currentLocation)));
    }

    // ========================================================================
    // Task Creation
    // ========================================================================

    /**
     * Create a task to use a plane transition.
     *
     * @param transition the detected transition
     * @param goUp       true to go up, false to go down (for bidirectional)
     * @return optional containing the task
     */
    public Optional<InteractObjectTask> createTransitionTask(DetectedTransition transition, boolean goUp) {
        if (transition == null) {
            return Optional.empty();
        }

        PlaneTransition def = transition.getDefinition();
        String action = determineAction(def, goUp);

        // If requires opening first (trapdoor), create open task
        if (def.isRequiresOpen()) {
            int currentId = transition.getObject().getId();
            if (currentId == def.getObjectId()) {
                // Still closed, open it first
                return Optional.of(new InteractObjectTask(def.getObjectId(), "Open")
                        .withDescription("Open " + def.getName())
                        .withSearchRadius(5)
                        .withWaitForIdle(true));
            }
        }

        InteractObjectTask task = new InteractObjectTask(transition.getObject().getId(), action)
                .withDescription(action + " " + def.getName())
                .withSearchRadius(5)
                .withWaitForIdle(true);

        log.debug("Created transition task: {} at {} using action '{}'",
                def.getName(), transition.getLocation(), action);

        return Optional.of(task);
    }

    /**
     * Determine the correct action for a transition.
     */
    private String determineAction(PlaneTransition def, boolean goUp) {
        TransitionDirection dir = def.getDirection();

        // If bidirectional, choose based on requested direction
        if (dir == TransitionDirection.BIDIRECTIONAL) {
            String baseAction = def.getAction();
            if (baseAction.equals("Climb")) {
                return goUp ? "Climb-up" : "Climb-down";
            }
            return baseAction;
        }

        // Use the defined action
        return def.getAction();
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Get the number of registered transitions.
     */
    public int getTransitionCount() {
        return allTransitions.size();
    }

    /**
     * Get all transitions of a specific type.
     */
    public List<PlaneTransition> getTransitionsByType(TransitionType type) {
        return allTransitions.stream()
                .filter(t -> t.getTransitionType() == type)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // Inner Classes and Enums
    // ========================================================================

    /**
     * Type of plane transition.
     */
    public enum TransitionType {
        LADDER,
        STAIRS,
        TRAPDOOR,
        ROPE,
        CHAIN,
        HOLE,
        TRANSPORT,
        OTHER
    }

    /**
     * Direction of plane transition.
     */
    public enum TransitionDirection {
        UP,
        DOWN,
        BIDIRECTIONAL
    }

    /**
     * Definition of a plane transition.
     */
    @lombok.Value
    @lombok.Builder
    public static class PlaneTransition {
        String name;
        int objectId;
        String action;
        TransitionType transitionType;
        TransitionDirection direction;

        /**
         * Change in plane when using this transition.
         * Positive = up, negative = down, 0 = determined at runtime.
         */
        @lombok.Builder.Default
        int planeChange = 0;

        /**
         * Whether this transition requires opening first (trapdoors).
         */
        @lombok.Builder.Default
        boolean requiresOpen = false;

        /**
         * Object ID when opened (for trapdoors).
         */
        @lombok.Builder.Default
        int openId = -1;

        /**
         * Destination point (if known and fixed).
         */
        @Nullable
        WorldPoint destination;

        /**
         * Convert to ObstacleDefinition for unified handling.
         */
        public ObstacleDefinition toObstacleDefinition() {
            ObstacleDefinition.ObstacleType type = switch (transitionType) {
                case LADDER -> ObstacleDefinition.ObstacleType.LADDER;
                case STAIRS -> ObstacleDefinition.ObstacleType.STAIRS;
                case TRAPDOOR -> ObstacleDefinition.ObstacleType.TRAPDOOR;
                default -> ObstacleDefinition.ObstacleType.OTHER;
            };

            return ObstacleDefinition.builder()
                    .name(name)
                    .type(type)
                    .objectIds(List.of(objectId))
                    .blockedStateId(objectId)
                    .passableStateId(openId)
                    .action(action)
                    .traversalCostTicks(3)
                    .destinationPlane(planeChange)
                    .destinationPoint(destination)
                    .build();
        }
    }

    /**
     * A detected plane transition in the game world.
     */
    @Getter
    public static class DetectedTransition {
        private final PlaneTransition definition;
        private final TileObject object;
        private final WorldPoint location;

        public DetectedTransition(PlaneTransition definition, TileObject object, WorldPoint location) {
            this.definition = definition;
            this.object = object;
            this.location = location;
        }

        @Override
        public String toString() {
            return String.format("DetectedTransition[%s at %s]", definition.getName(), location);
        }
    }
}

