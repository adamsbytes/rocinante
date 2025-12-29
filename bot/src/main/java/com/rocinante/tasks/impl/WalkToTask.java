package com.rocinante.tasks.impl;

import com.rocinante.input.WidgetClickHelper;
import com.rocinante.navigation.*;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Task for walking to a destination.
 *
 * Per REQUIREMENTS.md Section 5.4.3:
 * <ul>
 *   <li>Accept destination as: tile coordinate, object ID, NPC ID, or named location</li>
 *   <li>Use WebWalker for distances > 30 tiles, direct pathfinding otherwise</li>
 *   <li>Handle run energy: enable run above 40%, disable below 15%</li>
 *   <li>Incorporate humanized path deviations: occasional 1-2 tile detours (10% of walks)</li>
 *   <li>Click ahead on minimap for long walks; click in viewport for short walks</li>
 * </ul>
 *
 * <p>This task integrates with {@link ObstacleHandler} to detect and handle obstacles
 * (doors, gates, agility shortcuts) that block the path, and with {@link PlaneTransitionHandler}
 * to handle stairs, ladders, and trapdoors for multi-floor navigation.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Walk to specific coordinates
 * WalkToTask walkToBank = new WalkToTask(3253, 3420);
 *
 * // Walk to a WorldPoint
 * WalkToTask walkToPoint = new WalkToTask(WorldPoint.fromRegion(12850, 32, 32, 0));
 *
 * // Walk to named location (uses WebWalker)
 * WalkToTask walkNamed = WalkToTask.toLocation("varrock_west_bank");
 * }</pre>
 */
@Slf4j
public class WalkToTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Run energy threshold to enable running (per REQUIREMENTS.md).
     */
    private static final int RUN_ENABLE_THRESHOLD = 40;

    /**
     * Run energy threshold to disable running (per REQUIREMENTS.md).
     */
    private static final int RUN_DISABLE_THRESHOLD = 15;

    /**
     * Distance threshold for "arrived at destination" (tiles).
     */
    private static final int ARRIVAL_DISTANCE = 2;

    /**
     * Distance threshold for using WebWalker vs direct pathfinding.
     */
    private static final int WEBWALKER_DISTANCE_THRESHOLD = 30;

    /**
     * Probability of adding humanized path deviation.
     * REQUIREMENTS.md 5.4.3: "occasional 2-10 tile detours (10% of walks)"
     */
    private static final double PATH_DEVIATION_CHANCE = 0.10;

    /**
     * Minimum deviation distance in tiles for humanized path deviations.
     * REQUIREMENTS.md 5.4.3: "occasional 2-10 tile detours"
     */
    private static final int MIN_DEVIATION_TILES = 2;
    
    /**
     * Maximum deviation distance in tiles for humanized path deviations.
     * REQUIREMENTS.md 5.4.3: "occasional 2-10 tile detours"
     */
    private static final int MAX_DEVIATION_TILES = 10;

    /**
     * Maximum tiles to click ahead on minimap.
     */
    private static final int MINIMAP_CLICK_DISTANCE = 15;

    /**
     * Minimap widget group IDs for different interface modes.
     * These are the actual minimap drawing area widgets.
     */
    private static final int MINIMAP_FIXED_GROUP = 548;
    private static final int MINIMAP_FIXED_CHILD = 23;
    private static final int MINIMAP_RESIZABLE_CLASSIC_GROUP = 161;
    private static final int MINIMAP_RESIZABLE_CLASSIC_CHILD = 28;
    private static final int MINIMAP_RESIZABLE_MODERN_GROUP = 164;
    private static final int MINIMAP_RESIZABLE_MODERN_CHILD = 19;
    
    /**
     * Fallback minimap dimensions (fixed mode defaults).
     * Only used if widget bounds cannot be determined.
     */
    private static final int FALLBACK_MINIMAP_CENTER_X = 643;
    private static final int FALLBACK_MINIMAP_CENTER_Y = 83;
    private static final int FALLBACK_MINIMAP_RADIUS = 73;

    /**
     * Maximum ticks to wait for movement to start.
     */
    private static final int MOVEMENT_START_TIMEOUT = 5;

    /**
     * Maximum ticks player can be stationary before re-clicking.
     */
    private static final int STUCK_THRESHOLD_TICKS = 8;

    /**
     * Orbs widget group ID (minimap orbs).
     * Per RuneLite InterfaceID.Orbs (group 160 = 0x00a0).
     */
    private static final int ORBS_GROUP_ID = 160;

    /**
     * Run energy orb widget child ID.
     * Per RuneLite InterfaceID.Orbs.ORB_RUNENERGY (child 27).
     */
    private static final int RUN_ORB_CHILD = 27;

    /**
     * Maximum obstacle handling attempts before re-pathing.
     */
    private static final int MAX_OBSTACLE_ATTEMPTS = 3;

    /**
     * Search radius for plane transitions.
     */
    private static final int PLANE_TRANSITION_SEARCH_RADIUS = 15;

    // ========================================================================
    // Destination Configuration
    // ========================================================================

    /**
     * The destination as a WorldPoint (primary).
     */
    @Getter
    private WorldPoint destination;

    /**
     * Optional named location (for WebWalker integration).
     */
    @Getter
    @Setter
    private String namedLocation;

    /**
     * Optional object ID to walk to (nearest instance).
     */
    @Getter
    @Setter
    private int targetObjectId = -1;

    /**
     * Optional NPC ID to walk to (nearest instance).
     */
    @Getter
    @Setter
    private int targetNpcId = -1;

    /**
     * Optional node type to walk to (nearest instance via WebWalker).
     */
    @Getter
    @Setter
    private WebNodeType targetNodeType;

    /**
     * Custom description for this walk task.
     */
    @Getter
    @Setter
    private String description;

    /**
     * Risk threshold for avoiding risky agility shortcuts.
     * Default is 10% failure rate. Set to 1.0 to allow any shortcut.
     */
    @Getter
    @Setter
    private double agilityRiskThreshold = 0.10;

    // ========================================================================
    // Navigation Services (retrieved from context)
    // ========================================================================

    private PathFinder pathFinder;
    private WebWalker webWalker;
    private ObstacleHandler obstacleHandler;
    private PlaneTransitionHandler planeTransitionHandler;
    
    /**
     * Whether navigation services have been initialized.
     */
    private boolean servicesInitialized = false;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private WalkPhase phase = WalkPhase.INIT;

    /**
     * Calculated path to destination.
     */
    private List<WorldPoint> currentPath = new ArrayList<>();

    /**
     * Current index in the path.
     */
    private int pathIndex = 0;

    /**
     * Unified navigation path with edges (supports multi-plane).
     */
    private NavigationPath unifiedPath;

    /**
     * Current edge index in unified path.
     */
    private int currentEdgeIndex = 0;

    /**
     * Last known player position.
     */
    private WorldPoint lastPosition;

    /**
     * Ticks since last position change.
     */
    private int ticksSinceMove = 0;

    /**
     * Whether we're waiting for a click action.
     */
    private boolean clickPending = false;

    /**
     * Number of re-path attempts.
     */
    private int repathAttempts = 0;

    /**
     * Maximum re-path attempts.
     */
    private static final int MAX_REPATH_ATTEMPTS = 3;

    /**
     * Whether we're waiting for a run toggle click to complete.
     */
    private boolean runTogglePending = false;

    /**
     * Whether backtracking check has been performed.
     * Per REQUIREMENTS.md 3.4.4: 2% of walks walk 1-2 tiles past destination, then return.
     */
    private boolean backtrackingChecked = false;

    /**
     * The original destination (before backtrack overshoot).
     */
    private WorldPoint originalDestination;

    /**
     * Whether we're currently in a backtrack walk.
     */
    private boolean isBacktrackWalk = false;

    // ========================================================================
    // Obstacle Handling State
    // ========================================================================

    /**
     * Currently detected blocking obstacle.
     */
    private ObstacleHandler.DetectedObstacle currentObstacle;

    /**
     * Task to handle the current obstacle.
     */
    private InteractObjectTask obstacleTask;

    /**
     * Number of attempts to handle current obstacle.
     */
    private int obstacleAttempts = 0;

    // ========================================================================
    // Plane Transition State
    // ========================================================================

    /**
     * Currently detected plane transition.
     */
    private PlaneTransitionHandler.DetectedTransition currentTransition;

    /**
     * Task to use the plane transition.
     */
    private InteractObjectTask transitionTask;

    /**
     * Target plane we're trying to reach.
     */
    private int targetPlane = -1;

    /**
     * Final destination (saved when temporarily walking to a transition).
     */
    private WorldPoint finalDestination;

    // ========================================================================
    // Teleport/Transport State
    // ========================================================================

    /**
     * Child task for executing teleport/travel.
     */
    private TravelTask teleportTask;

    /**
     * Child task for executing transport (NPC/object interaction).
     */
    private InteractObjectTask transportTask;

    /**
     * Child task for transport NPC interaction.
     */
    private InteractNpcTask transportNpcTask;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a walk task to specific coordinates.
     *
     * @param x world X coordinate
     * @param y world Y coordinate
     */
    public WalkToTask(int x, int y) {
        this(new WorldPoint(x, y, 0));
    }

    /**
     * Create a walk task to specific coordinates with plane.
     *
     * @param x     world X coordinate
     * @param y     world Y coordinate
     * @param plane the plane (0-3)
     */
    public WalkToTask(int x, int y, int plane) {
        this(new WorldPoint(x, y, plane));
    }

    /**
     * Create a walk task to a WorldPoint.
     *
     * @param destination the target point
     */
    public WalkToTask(WorldPoint destination) {
        this.destination = destination;
        this.timeout = Duration.ofMinutes(5);
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a walk task to a named location.
     * Named locations are resolved via WebWalker's navigation web.
     *
     * @param locationName the location name (e.g., "varrock_west_bank")
     * @return the walk task
     */
    public static WalkToTask toLocation(String locationName) {
        WalkToTask task = new WalkToTask(0, 0);
        task.namedLocation = locationName;
        task.description = "Walk to " + locationName;
        return task;
    }

    /**
     * Create a walk task to the nearest instance of an object.
     *
     * @param objectId the game object ID
     * @return the walk task
     */
    public static WalkToTask toObject(int objectId) {
        WalkToTask task = new WalkToTask(0, 0);
        task.targetObjectId = objectId;
        task.description = "Walk to object " + objectId;
        return task;
    }

    /**
     * Create a walk task to the nearest instance of an NPC.
     *
     * @param npcId the NPC ID
     * @return the walk task
     */
    public static WalkToTask toNpc(int npcId) {
        WalkToTask task = new WalkToTask(0, 0);
        task.targetNpcId = npcId;
        task.description = "Walk to NPC " + npcId;
        return task;
    }

    /**
     * Create a walk task to the nearest node of a specific type.
     * Uses WebWalker's unified pathfinding which includes teleports/transports.
     *
     * @param type the target node type (BANK, SHOP, GRAND_EXCHANGE, etc.)
     * @return the walk task
     */
    public static WalkToTask toNearest(WebNodeType type) {
        WalkToTask task = new WalkToTask(0, 0);
        task.targetNodeType = type;
        task.description = "Travel to nearest " + type.name().toLowerCase().replace("_", " ");
        return task;
    }

    // ========================================================================
    // Configuration Methods
    // ========================================================================

    /**
     * Set a custom description for this task.
     *
     * @param description the description
     * @return this task for chaining
     */
    public WalkToTask withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * Configure the agility risk threshold.
     *
     * @param threshold maximum acceptable failure rate (0.0 to 1.0)
     * @return this task for chaining
     */
    public WalkToTask withAgilityRiskThreshold(double threshold) {
        this.agilityRiskThreshold = threshold;
        return this;
    }

    /**
     * Allow all agility shortcuts regardless of failure rate.
     *
     * @return this task for chaining
     */
    public WalkToTask allowAllShortcuts() {
        this.agilityRiskThreshold = 1.0;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.isLoggedIn();
    }

    @Override
    protected void resetImpl() {
        // Reset all execution state for retry
        phase = WalkPhase.INIT;
        currentPath.clear();
        pathIndex = 0;
        unifiedPath = null;
        currentEdgeIndex = 0;
        lastPosition = null;
        ticksSinceMove = 0;
        clickPending = false;
        repathAttempts = 0;
        runTogglePending = false;
        log.debug("WalkToTask reset for retry");
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        // Initialize navigation services on first execution
        if (!servicesInitialized) {
            initializeServices(ctx);
        }

        switch (phase) {
            case INIT:
                executeInit(ctx);
                break;
            case RESOLVE_DESTINATION:
                executeResolveDestination(ctx);
                break;
            case CALCULATE_PATH:
                executeCalculatePath(ctx);
                break;
            case WALKING:
                executeWalking(ctx);
                break;
            case HANDLE_OBSTACLE:
                executeHandleObstacle(ctx);
                break;
            case HANDLE_PLANE_TRANSITION:
                executeHandlePlaneTransition(ctx);
                break;
            case BACKTRACKING:
                executeBacktracking(ctx);
                break;
            case BACKTRACK_WALK_TO_OVERSHOOT:
                executeBacktrackWalkToOvershoot(ctx);
                break;
            case BACKTRACK_RETURN:
                executeBacktrackReturn(ctx);
                break;
            case TELEPORT_PENDING:
                executeTeleportPending(ctx);
                break;
            case TRANSPORT_PENDING:
                executeTransportPending(ctx);
                break;
            case ARRIVED:
                complete();
                break;
        }
    }

    // ========================================================================
    // Phase: Init
    // ========================================================================

    private void executeInit(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        lastPosition = player.getWorldPosition();

        // Log start of walk
        log.info("WalkToTask starting: {} -> {}",
                lastPosition, getDestinationDescription());

        // Handle run energy
        handleRunEnergy(ctx, player);

        phase = WalkPhase.RESOLVE_DESTINATION;
    }

    // ========================================================================
    // Phase: Resolve Destination
    // ========================================================================

    private void executeResolveDestination(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Resolve named location via WebWalker
        if (namedLocation != null && (destination == null || destination.getX() == 0)) {
            WebNode node = webWalker.getNode(namedLocation);
            if (node != null) {
                destination = node.getWorldPoint();
                log.debug("Resolved named location '{}' to {}", namedLocation, destination);
            } else {
                log.warn("Could not resolve named location: {}", namedLocation);
                fail("Unknown location: " + namedLocation);
                return;
            }
        }

        // Resolve object ID to nearest location
        if (targetObjectId > 0 && (destination == null || destination.getX() == 0)) {
            WorldPoint objLocation = findNearestObject(ctx, targetObjectId);
            if (objLocation != null) {
                destination = objLocation;
                log.debug("Resolved object {} to {}", targetObjectId, destination);
            } else {
                log.warn("Could not find object: {}", targetObjectId);
                fail("Object not found: " + targetObjectId);
                return;
            }
        }

        // Resolve NPC ID to nearest location
        if (targetNpcId > 0 && (destination == null || destination.getX() == 0)) {
            WorldPoint npcLocation = findNearestNpc(ctx, targetNpcId);
            if (npcLocation != null) {
                destination = npcLocation;
                log.debug("Resolved NPC {} to {}", targetNpcId, destination);
            } else {
                log.warn("Could not find NPC: {}", targetNpcId);
                fail("NPC not found: " + targetNpcId);
                return;
            }
        }

        // Resolve node type to nearest location via WebWalker
        if (targetNodeType != null && (destination == null || destination.getX() == 0)) {
            PlayerState player2 = ctx.getPlayerState();
            WorldPoint playerPos2 = player2.getWorldPosition();
            if (playerPos2 != null && webWalker != null) {
                NavigationPath path = webWalker.findUnifiedPathToNearestType(playerPos2, targetNodeType);
                if (!path.isEmpty()) {
                    destination = path.getEndPoint();
                    // Store the pre-computed path so we don't recalculate
                    unifiedPath = path;
                    log.debug("Resolved {} to {} via unified path", targetNodeType, destination);
                } else {
                    // Fallback: find nearest node directly
                    WebNode node = webWalker.getNavigationWeb().findNearestNode(playerPos2, targetNodeType);
                    if (node != null) {
                        destination = node.getWorldPoint();
                        log.debug("Resolved {} to {} (direct node lookup)", targetNodeType, destination);
                    } else {
                        log.warn("Could not find nearest {}", targetNodeType);
                        fail("No " + targetNodeType.name().toLowerCase() + " found");
                        return;
                    }
                }
            }
        }

        // Check if already at destination
        if (playerPos != null && destination != null) {
            int distance = playerPos.distanceTo(destination);
            if (distance <= ARRIVAL_DISTANCE && playerPos.getPlane() == destination.getPlane()) {
                log.info("Already at destination");
                phase = WalkPhase.ARRIVED;
                return;
            }
        }

        // Check if we need a plane transition
        if (playerPos != null && destination != null && 
                playerPos.getPlane() != destination.getPlane()) {
            targetPlane = destination.getPlane();
            log.debug("Need plane transition: {} -> {}", playerPos.getPlane(), targetPlane);
        }

        phase = WalkPhase.CALCULATE_PATH;
    }

    // ========================================================================
    // Phase: Calculate Path
    // ========================================================================

    private void executeCalculatePath(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos == null || destination == null) {
            fail("Invalid player position or destination");
            return;
        }

        // Check if we need a plane transition first
        if (playerPos.getPlane() != destination.getPlane()) {
            findPlaneTransition(ctx, playerPos, destination.getPlane());
            if (currentTransition != null) {
                log.debug("Found plane transition to reach destination plane");
                phase = WalkPhase.HANDLE_PLANE_TRANSITION;
                return;
            }
        }

        int distance = playerPos.distanceTo(destination);
        log.debug("Distance to destination: {} tiles", distance);

        // Try pathfinding strategies in order (no recursion!)
        boolean pathFound = tryFindPath(ctx, playerPos, distance);

        if (!pathFound) {
            repathAttempts++;
            if (repathAttempts >= MAX_REPATH_ATTEMPTS) {
                fail("Could not find path to destination after " + repathAttempts + " attempts");
                return;
            }
            log.warn("No path found, will retry (attempt {})", repathAttempts);
            // Stay in CALCULATE_PATH to retry next tick
            return;
        }

        phase = WalkPhase.WALKING;
    }

    /**
     * Try pathfinding using comprehensive navigation analysis.
     * 
     * <p>Handles all scenarios including:
     * <ul>
     *   <li>Direct A* for short distances</li>
     *   <li>Graph navigation for long distances</li>
     *   <li>First-mile gaps (player far from graph)</li>
     *   <li>Last-mile gaps (destination far from graph)</li>
     *   <li>Complete isolation with appropriate failure</li>
     * </ul>
     * 
     * @param ctx the task context
     * @param playerPos current player position
     * @param distance distance to destination
     * @return true if any navigation strategy is viable
     */
    private boolean tryFindPath(TaskContext ctx, WorldPoint playerPos, int distance) {
        Randomization rand = ctx.getRandomization();
        
        // Strategy 1: Direct A* pathfinding (works within loaded scene)
        // This is fastest and works for short distances or when already near destination
        if (distance <= WEBWALKER_DISTANCE_THRESHOLD) {
            currentPath = new ArrayList<>(pathFinder.findPath(playerPos, destination));
            if (!currentPath.isEmpty()) {
                log.debug("Direct A*: found path with {} tiles", currentPath.size());
                pathIndex = 0;
                if (rand.chance(PATH_DEVIATION_CHANCE) && currentPath.size() > 3) {
                    applyPathDeviation(rand);
                }
                return true;
            }
            log.debug("Direct A*: failed - may need graph navigation");
        }
        
        // Strategy 2: Comprehensive navigation analysis
        NavigationResult navResult = webWalker.findBestEffortPath(playerPos, destination);
        lastNavigationResult = navResult; // Store for debugging/status
        
        switch (navResult.getStatus()) {
            case FULL_PATH_AVAILABLE:
                return setupGraphPath(navResult, playerPos, rand);
                
            case FIRST_MILE_MANUAL:
                log.debug("First-mile gap: {} tiles to nearest node", navResult.getFirstMileDistance());
                return setupFirstMileNavigation(navResult, playerPos, rand);
                
            case LAST_MILE_MANUAL:
                log.debug("Last-mile gap: {} tiles from last node to destination", navResult.getLastMileDistance());
                return setupGraphPath(navResult, playerPos, rand);
                
            case BOTH_ENDS_MANUAL:
                log.debug("Both ends manual: first={} tiles, last={} tiles", 
                        navResult.getFirstMileDistance(), navResult.getLastMileDistance());
                return setupFirstMileNavigation(navResult, playerPos, rand);
                
            case NO_PATH_BETWEEN_NODES:
                log.warn("No path between nodes - may need unlock or teleport: {}", 
                        navResult.getFailureReason());
                // Fall through to simple walk
                break;
                
            case PLAYER_ISOLATED:
                log.warn("Player isolated from navigation graph: {}", navResult.getFailureReason());
                if (navResult.hasSuggestion(NavigationResult.Suggestion.WALK_TO_NEAREST_NODE)) {
                    return setupWalkToNearestNode(navResult, playerPos);
                }
                // Suggest teleport in failure message
                break;
                
            case DESTINATION_ISOLATED:
                log.warn("Destination isolated from navigation graph: {}", navResult.getFailureReason());
                // Still try to get as close as possible
                if (navResult.hasGraphPath()) {
                    return setupGraphPath(navResult, playerPos, rand);
                }
                break;
                
            case COMPLETELY_ISOLATED:
                log.error("Complete navigation isolation: {}", navResult.getFailureReason());
                // Last resort: simple walk if close enough
                if (distance <= 100) {
                    break; // Fall through to simple walk
                }
                fail("Navigation impossible: " + navResult.getFailureReason() + 
                     ". Suggestions: " + navResult.getSuggestions());
                return false;
                
            case SYSTEM_NOT_AVAILABLE:
                log.error("Navigation system not available");
                // Fall through to simple walk
                break;
        }
        
        // Strategy 3: Simple walk toward destination (last resort)
        currentPath = new ArrayList<>(calculateSimpleWalkToward(playerPos, distance));
        if (!currentPath.isEmpty()) {
            log.debug("Simple walk: {} tiles toward destination", currentPath.size());
            pathIndex = 0;
            return true;
        }
        
        log.warn("All pathfinding strategies failed for {} -> {}", playerPos, destination);
        return false;
    }
    
    /**
     * Set up navigation using the graph path from analysis.
     */
    private boolean setupGraphPath(NavigationResult navResult, WorldPoint playerPos, Randomization rand) {
        unifiedPath = navResult.getGraphPath();
        if (unifiedPath == null || unifiedPath.isEmpty()) {
            return false;
        }
        
        log.debug("Graph path: {} edges, ~{} ticks", unifiedPath.size(), navResult.getEstimatedGraphTicks());
            currentEdgeIndex = 0;
            
        // Get local path to first edge
            NavigationEdge firstEdge = unifiedPath.getEdge(0);
            if (firstEdge != null && firstEdge.getFromLocation() != null) {
                currentPath = new ArrayList<>(pathFinder.findPath(playerPos, firstEdge.getFromLocation()));
            pathIndex = 0;
            
            if (currentPath.isEmpty()) {
                // Can't reach first edge - try simple walk
                currentPath = new ArrayList<>(calculateSimpleWalkToward(playerPos, 
                        playerPos.distanceTo(firstEdge.getFromLocation())));
                pathIndex = 0;
            }
        }
        
        return true;
    }
    
    /**
     * Set up first-mile navigation (walk to nearest node, then use graph).
     */
    private boolean setupFirstMileNavigation(NavigationResult navResult, WorldPoint playerPos, Randomization rand) {
        WebNode nearestNode = navResult.getNearestNodeToPlayer();
        if (nearestNode == null) {
            return false;
        }
        
        // First, walk to the nearest node
        WorldPoint nodePos = nearestNode.getWorldPoint();
        currentPath = new ArrayList<>(pathFinder.findPath(playerPos, nodePos));
        
        if (currentPath.isEmpty()) {
            // A* can't find path - use simple walk toward the node
            currentPath = new ArrayList<>(calculateSimpleWalkToward(playerPos, navResult.getFirstMileDistance()));
        }

        if (!currentPath.isEmpty()) {
            log.debug("First-mile: walking {} tiles to node '{}'", 
                    currentPath.size(), nearestNode.getId());
            pathIndex = 0;

            // Store the graph path for after we reach the first node
            unifiedPath = navResult.getGraphPath();
            currentEdgeIndex = 0;
            
            return true;
        }
        
        return false;
    }
    
    /**
     * Set up walk to the nearest navigation node (for isolated player).
     */
    private boolean setupWalkToNearestNode(NavigationResult navResult, WorldPoint playerPos) {
        WebNode nearestNode = navResult.getNearestNodeToPlayer();
        if (nearestNode == null) {
            return false;
        }
        
        WorldPoint nodePos = nearestNode.getWorldPoint();
        int distance = playerPos.distanceTo(nodePos);
        
        currentPath = new ArrayList<>(calculateSimpleWalkToward(playerPos, distance));
        if (!currentPath.isEmpty()) {
            log.debug("Walking {} tiles toward nearest node '{}'", distance, nearestNode.getId());
            pathIndex = 0;
            
            // After reaching the node, we'll recalculate with the graph
            unifiedPath = navResult.getGraphPath();
            currentEdgeIndex = 0;
            
            return true;
        }
        
        return false;
    }
    
    /** Last navigation result for debugging/status */
    private NavigationResult lastNavigationResult;
    
    /**
     * Simple fallback: create waypoints walking toward destination.
     * Used when both A* and WebWalker fail (e.g., no graph data for area).
     * 
     * @param playerPos current position
     * @param totalDistance distance to destination
     * @return list of waypoints toward destination
     */
    private List<WorldPoint> calculateSimpleWalkToward(WorldPoint playerPos, int totalDistance) {
        if (totalDistance < 1) {
            return Collections.emptyList();
        }
        
        List<WorldPoint> path = new ArrayList<>();
        path.add(playerPos);
        
        // Calculate direction
        int dx = destination.getX() - playerPos.getX();
        int dy = destination.getY() - playerPos.getY();
        double dist = Math.sqrt(dx * dx + dy * dy);
        
        // Normalize direction
        double dirX = dx / dist;
        double dirY = dy / dist;
        
        // Create waypoints every 10-15 tiles toward destination
        int stepSize = 12;
        int steps = Math.min(3, (int) Math.ceil(dist / stepSize)); // Max 3 waypoints at a time
        
        for (int i = 1; i <= steps; i++) {
            int stepDist = Math.min(i * stepSize, (int) dist);
            WorldPoint waypoint = new WorldPoint(
                    playerPos.getX() + (int) Math.round(dirX * stepDist),
                    playerPos.getY() + (int) Math.round(dirY * stepDist),
                    playerPos.getPlane()
            );
            path.add(waypoint);
            
            // If this waypoint is at or past destination, we're done
            if (stepDist >= dist) {
                break;
            }
        }
        
        // Always add destination as final waypoint if not already there
        WorldPoint lastWaypoint = path.get(path.size() - 1);
        if (!lastWaypoint.equals(destination)) {
            path.add(destination);
        }
        
        log.debug("Simple path: {} waypoints from {} toward {}", path.size(), playerPos, destination);
        return path;
    }

    /**
     * Apply random deviation to path for humanization.
     * Implements REQUIREMENTS.md 5.4.3: "occasional 2-10 tile detours (10% of walks)"
     * 
     * <p>Generates a random deviation of 2-10 tiles in a random direction,
     * ensuring the deviated point is walkable.
     */
    private void applyPathDeviation(Randomization rand) {
        int deviationPoint = 1 + rand.uniformRandomInt(0, currentPath.size() - 3);
        WorldPoint original = currentPath.get(deviationPoint);

        // Generate random distance between MIN_DEVIATION_TILES and MAX_DEVIATION_TILES
        int distance = rand.uniformRandomInt(MIN_DEVIATION_TILES, MAX_DEVIATION_TILES);
        
        // Generate random direction (angle in radians)
        double angle = rand.uniformRandom(0, 2 * Math.PI);
        
        // Calculate dx and dy from polar coordinates
        int dx = (int) Math.round(distance * Math.cos(angle));
        int dy = (int) Math.round(distance * Math.sin(angle));
        
        // Ensure at least MIN_DEVIATION_TILES distance (rounding might reduce it)
        if (Math.abs(dx) + Math.abs(dy) < MIN_DEVIATION_TILES) {
            // Fallback: just push in a cardinal direction
            if (Math.abs(dx) >= Math.abs(dy)) {
                dx = dx >= 0 ? MIN_DEVIATION_TILES : -MIN_DEVIATION_TILES;
            } else {
                dy = dy >= 0 ? MIN_DEVIATION_TILES : -MIN_DEVIATION_TILES;
            }
        }

        WorldPoint deviated = new WorldPoint(
                original.getX() + dx,
                original.getY() + dy,
                original.getPlane()
        );

        // Only apply if deviation is walkable
        if (pathFinder.isWalkable(deviated)) {
            currentPath.set(deviationPoint, deviated);
            log.trace("Applied path deviation at index {}: {} -> {} (distance={})", 
                    deviationPoint, original, deviated, distance);
        }
    }

    // ========================================================================
    // Phase: Walking
    // ========================================================================

    private void executeWalking(TaskContext ctx) {
        if (clickPending) {
            return; // Wait for click to complete
        }

        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Detect teleport (position changed drastically without movement)
        if (lastPosition != null && !player.isMoving() && playerPos != null &&
                playerPos.distanceTo(lastPosition) > 20) {
            log.debug("Detected teleport from {} to {}, recalculating path", lastPosition, playerPos);
            phase = WalkPhase.CALCULATE_PATH;
            ticksSinceMove = 0;
            return;
        }

        // Check if arrived at final destination
        if (playerPos != null && destination != null &&
                playerPos.distanceTo(destination) <= ARRIVAL_DISTANCE &&
                playerPos.getPlane() == destination.getPlane()) {
            
            // If we have a pending plane transition, we've just arrived at the transition
            // object location - go back to handle the transition
            if (finalDestination != null && targetPlane != -1) {
                log.debug("Arrived at plane transition location, now using transition");
                phase = WalkPhase.HANDLE_PLANE_TRANSITION;
                return;
            }
            
            log.info("Arrived at destination: {}", destination);
            
            // Check for backtracking (2% chance per REQUIREMENTS.md 3.4.4)
            // But only if not already a backtrack walk
            if (!isBacktrackWalk && !backtrackingChecked) {
                backtrackingChecked = true;
                var inefficiency = ctx.getInefficiencyInjector();
                if (inefficiency != null && inefficiency.shouldBacktrack()) {
                    phase = WalkPhase.BACKTRACKING;
                    return;
                }
            }
            
            phase = WalkPhase.ARRIVED;
            return;
        }

        // Check for stuck detection
        if (playerPos != null && playerPos.equals(lastPosition)) {
            ticksSinceMove++;
            if (ticksSinceMove > STUCK_THRESHOLD_TICKS && !player.isMoving()) {
                log.debug("Stuck detected, recalculating path");
                phase = WalkPhase.CALCULATE_PATH;
                ticksSinceMove = 0;
                return;
            }
        } else {
            lastPosition = playerPos;
            ticksSinceMove = 0;
        }

        // Update run energy
        handleRunEnergy(ctx, player);

        // If using unified path and finished current segment, move to next edge
        if (unifiedPath != null && !unifiedPath.isEmpty() && 
                currentPath.isEmpty() && currentEdgeIndex < unifiedPath.size()) {
            advanceUnifiedPath(ctx, playerPos);
            return;
        }

        // Click on next path point
        if (!currentPath.isEmpty()) {
            clickNextPathPoint(ctx, playerPos);
        }
    }

    /**
     * Advance to the next edge in the unified path.
     */
    private void advanceUnifiedPath(TaskContext ctx, WorldPoint playerPos) {
        NavigationEdge currentEdge = unifiedPath.getEdge(currentEdgeIndex);
        if (currentEdge == null) {
            // No more edges, go to destination
            currentPath = new ArrayList<>(pathFinder.findPath(playerPos, destination));
            pathIndex = 0;
            return;
        }

        // Check if we've reached the edge's starting point
        WorldPoint edgeStart = currentEdge.getFromLocation();
        boolean atEdgeStart = edgeStart == null || 
                playerPos.distanceTo(edgeStart) <= ARRIVAL_DISTANCE;

        if (!atEdgeStart) {
            // Need to walk to edge start first
            currentPath = new ArrayList<>(pathFinder.findPath(playerPos, edgeStart));
            pathIndex = 0;
            return;
        }

        // At edge start - execute the edge based on its type
        executeEdge(ctx, currentEdge, playerPos);
    }

    /**
     * Execute a navigation edge based on its type.
     */
    private void executeEdge(TaskContext ctx, NavigationEdge edge, WorldPoint playerPos) {
        log.debug("Executing edge: {} ({})", edge.getFromNodeId() + " -> " + edge.getToNodeId(), edge.getType());

        switch (edge.getType()) {
            case WALK:
                executeWalkEdge(edge, playerPos);
                break;
            case STAIRS:
                executeStairsEdge(ctx, edge, playerPos);
                break;
            case AGILITY:
                executeAgilityEdge(ctx, edge, playerPos);
                break;
            case TOLL:
                executeTollEdge(ctx, edge, playerPos);
                break;
            case DOOR:
                executeDoorEdge(ctx, edge, playerPos);
                break;
            case TELEPORT:
                executeTeleportEdge(ctx, edge);
                break;
            case TRANSPORT:
                executeTransportEdge(ctx, edge);
                break;
            default:
                // Default to walking
                executeWalkEdge(edge, playerPos);
                break;
        }
    }

    /**
     * Execute a WALK edge - simple pathfinding to destination.
     */
    private void executeWalkEdge(NavigationEdge edge, WorldPoint playerPos) {
        WorldPoint edgeDest = edge.getToLocation();
        if (edgeDest == null) {
            // Fall back to destination
            edgeDest = destination;
        }
        
        currentPath = new ArrayList<>(pathFinder.findPath(playerPos, edgeDest));
        pathIndex = 0;
        currentEdgeIndex++; // Move to next edge
        
        log.debug("Walk edge: pathing to {} ({} tiles)", edgeDest, currentPath.size());
    }

    /**
     * Execute a STAIRS edge - use PlaneTransitionHandler.
     */
    private void executeStairsEdge(TaskContext ctx, NavigationEdge edge, WorldPoint playerPos) {
        // Set up plane transition
        targetPlane = edge.getToPlane();
        
        // Find the transition object
        if (planeTransitionHandler != null && edge.getObjectId() > 0) {
            // Create transition task directly
            boolean goUp = edge.getToPlane() > edge.getFromPlane();
            String action = goUp ? "Climb-up" : "Climb-down";
            if (edge.getAction() != null) {
                action = edge.getAction();
            }
            
            transitionTask = new InteractObjectTask(edge.getObjectId(), action)
                    .withDescription(action + " to plane " + targetPlane)
                    .withSearchRadius(5)
                    .withWaitForIdle(true);
            
            // Save current destination
            if (finalDestination == null) {
                finalDestination = destination;
            }
            
            currentEdgeIndex++; // Move to next edge after transition
            phase = WalkPhase.HANDLE_PLANE_TRANSITION;
        } else {
            // Fall back to plane transition handler search
            findPlaneTransition(ctx, playerPos, targetPlane);
            if (currentTransition != null) {
                currentEdgeIndex++;
                phase = WalkPhase.HANDLE_PLANE_TRANSITION;
            } else {
                log.warn("Could not find stairs transition for edge");
                currentEdgeIndex++; // Skip this edge
            }
        }
    }

    /**
     * Execute an AGILITY edge - use ObstacleHandler for shortcuts.
     */
    private void executeAgilityEdge(TaskContext ctx, NavigationEdge edge, WorldPoint playerPos) {
        if (edge.getObjectId() > 0) {
            String action = edge.getAction() != null ? edge.getAction() : "Cross";
            
            obstacleTask = new InteractObjectTask(edge.getObjectId(), action)
                    .withDescription("Use shortcut: " + action)
                    .withSearchRadius(10)
                    .withWaitForIdle(true);
            
            obstacleAttempts = 0;
            currentEdgeIndex++;
            phase = WalkPhase.HANDLE_OBSTACLE;
            
            log.debug("Agility edge: using shortcut {} with action '{}'", edge.getObjectId(), action);
        } else {
            log.warn("Agility edge missing object ID");
            currentEdgeIndex++;
        }
    }

    /**
     * Execute a TOLL edge - handle payment or item usage.
     */
    private void executeTollEdge(TaskContext ctx, NavigationEdge edge, WorldPoint playerPos) {
        // Check for free passage via quest
        String freeQuest = edge.getFreePassageQuest();
        if (freeQuest != null) {
            var unlockTracker = ctx.getUnlockTracker();
            if (unlockTracker != null) {
                try {
                    net.runelite.api.Quest quest = net.runelite.api.Quest.valueOf(freeQuest);
                    if (unlockTracker.isQuestCompleted(quest)) {
                        log.debug("Free passage through toll gate via quest: {}", freeQuest);
                        // Just walk through
                        executeWalkEdge(edge, playerPos);
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    log.debug("Unknown quest enum for free toll passage: {} - {}", freeQuest, e.getMessage());
                }
            }
        }

        // Need to pay toll - interact with gate
        if (edge.getObjectId() > 0) {
            String action = edge.getAction() != null ? edge.getAction() : "Pay-toll";
            
            obstacleTask = new InteractObjectTask(edge.getObjectId(), action)
                    .withDescription("Pay toll: " + edge.getTollCost() + "gp")
                    .withSearchRadius(5)
                    .withWaitForIdle(true);
            
            obstacleAttempts = 0;
            currentEdgeIndex++;
            phase = WalkPhase.HANDLE_OBSTACLE;
            
            log.debug("Toll edge: paying {} gp at object {}", edge.getTollCost(), edge.getObjectId());
        } else {
            log.warn("Toll edge missing object ID");
            currentEdgeIndex++;
        }
    }

    /**
     * Execute a DOOR edge - use ObstacleHandler.
     */
    private void executeDoorEdge(TaskContext ctx, NavigationEdge edge, WorldPoint playerPos) {
        if (edge.getObjectId() > 0) {
            String action = edge.getAction() != null ? edge.getAction() : "Open";
            
            obstacleTask = new InteractObjectTask(edge.getObjectId(), action)
                    .withDescription("Open door/gate")
                    .withSearchRadius(5)
                    .withWaitForIdle(true);
            
            obstacleAttempts = 0;
            currentEdgeIndex++;
            phase = WalkPhase.HANDLE_OBSTACLE;
        } else {
            // No specific object, try walking through
            executeWalkEdge(edge, playerPos);
        }
    }

    /**
     * Execute a TELEPORT edge - cast spell or use item.
     * Uses TravelTask for the actual teleportation.
     */
    private void executeTeleportEdge(TaskContext ctx, NavigationEdge edge) {
        // Create TravelTask from edge metadata if not already created
        if (teleportTask == null) {
            Map<String, String> metadata = edge.getMetadata();
            teleportTask = TravelTask.fromNavigationMetadata(metadata);
            
            if (teleportTask == null) {
                log.error("Failed to create TravelTask from edge metadata: {}", metadata);
                // Skip this edge and try to continue
                currentEdgeIndex++;
                phase = WalkPhase.CALCULATE_PATH;
                return;
            }
            
            // Set expected destination if available
            if (edge.getToLocation() != null) {
                teleportTask.setExpectedDestination(edge.getToLocation());
            }
            
            log.debug("Created TravelTask: {}", teleportTask.getDescription());
        }
        
        // Transition to pending phase to execute the teleport task
        phase = WalkPhase.TELEPORT_PENDING;
    }

    /**
     * Execute a TRANSPORT edge - use TravelTask for complex transports,
     * or InteractObjectTask/InteractNpcTask for simple ones.
     *
     * <p>Complex transports (fairy rings, spirit trees, gnome gliders, etc.)
     * are handled by TravelTask which encapsulates the full interaction flow.
     */
    private void executeTransportEdge(TaskContext ctx, NavigationEdge edge) {
        // Check if we already have a transport task
        if (transportTask != null || transportNpcTask != null || teleportTask != null) {
            phase = WalkPhase.TRANSPORT_PENDING;
            return;
        }

        Map<String, String> metadata = edge.getMetadata();
        if (metadata == null) {
            log.warn("Transport edge has no metadata, skipping");
            currentEdgeIndex++;
            return;
        }

        // Check for complex transport types that should use TravelTask
        String travelType = metadata.get("travel_type");
        if (travelType != null) {
            switch (travelType) {
                case "fairy_ring":
                case "spirit_tree":
                case "gnome_glider":
                case "charter_ship":
                case "quetzal":
                case "canoe":
                    // Use TravelTask for complex transports
                    teleportTask = TravelTask.fromNavigationMetadata(metadata);
                    if (teleportTask != null) {
                        log.debug("Created TravelTask for {}: {}", travelType, teleportTask.getDescription());
                        phase = WalkPhase.TRANSPORT_PENDING;
                        return;
                    } else {
                        log.warn("Failed to create TravelTask for {}, trying fallback", travelType);
                    }
                    break;
                default:
                    // Fall through to simple transport handling
                    break;
            }
        }

        // Check for object-based transport (simple interactions)
        int objectId = edge.getObjectId();
        if (objectId <= 0 && metadata.containsKey("object_id")) {
            try {
                objectId = Integer.parseInt(metadata.get("object_id"));
            } catch (NumberFormatException e) {
                log.warn("Invalid object_id in transport metadata");
            }
        }

        if (objectId > 0) {
            String action = edge.getAction();
            if (action == null) {
                action = metadata.getOrDefault("action", "Travel");
            }
            
            transportTask = new InteractObjectTask(objectId, action);
            transportTask.setDescription("Use transport: " + metadata.getOrDefault("name", "transport"));
            
            log.debug("Created transport object task: objectId={}, action={}", objectId, action);
            phase = WalkPhase.TRANSPORT_PENDING;
            return;
        }

        // Check for NPC-based transport (simple interactions)
        String npcIdStr = metadata.get("npc_id");
        if (npcIdStr != null) {
            try {
                int npcId = Integer.parseInt(npcIdStr);
                String action = metadata.getOrDefault("action", "Travel");
                
                transportNpcTask = new InteractNpcTask(npcId, action);
                transportNpcTask.setDescription("Talk to transport NPC");
                
                log.debug("Created transport NPC task: npcId={}, action={}", npcId, action);
                phase = WalkPhase.TRANSPORT_PENDING;
                return;
            } catch (NumberFormatException e) {
                log.warn("Invalid npc_id in transport metadata: {}", npcIdStr);
            }
        }

        // No valid transport configuration found
        log.warn("Transport edge has no valid transport config, skipping: {}", metadata);
        currentEdgeIndex++;
    }

    private void clickNextPathPoint(TaskContext ctx, WorldPoint playerPos) {
        // Determine click target (skip ahead on path)
        WorldPoint clickTarget = determineClickTarget(playerPos);

        if (clickTarget == null) {
            log.warn("No valid click target found");
            return;
        }

        // Check for obstacles
        if (obstacleHandler != null) {
            var blockingObstacle = obstacleHandler.findBlockingObstacle(playerPos, clickTarget);
            if (blockingObstacle.isPresent()) {
                currentObstacle = blockingObstacle.get();
                log.debug("Obstacle detected: {}", currentObstacle);
                
                // Check if we should avoid this obstacle (risky agility shortcut)
                int playerAgilityLevel = ctx.getClient().getRealSkillLevel(Skill.AGILITY);
                if (obstacleHandler.shouldAvoidObstacle(
                        currentObstacle.getObject().getId(), 
                        playerAgilityLevel, 
                        agilityRiskThreshold)) {
                    log.debug("Avoiding risky shortcut, recalculating path");
                    currentObstacle = null;
                    phase = WalkPhase.CALCULATE_PATH;
                    return;
                }
                
                phase = WalkPhase.HANDLE_OBSTACLE;
                return;
            }
        }

        // Calculate screen point for click - try minimap first, then viewport
        Point screenPoint = calculateMinimapPoint(ctx, clickTarget);
        if (screenPoint == null) {
            // Try viewport click for nearby tiles
            screenPoint = calculateViewportPoint(ctx, clickTarget);
        }

        // If primary target failed, try progressively closer points from the path
        if (screenPoint == null && !currentPath.isEmpty()) {
            for (int i = pathIndex; i < currentPath.size(); i++) {
                WorldPoint fallbackTarget = currentPath.get(i);
                if (fallbackTarget.equals(clickTarget)) {
                    continue; // Skip the one we already tried
                }
                
                int fallbackDist = playerPos.distanceTo(fallbackTarget);
                if (fallbackDist <= 2) {
                    continue; // Too close, not worth clicking
                }
                
                screenPoint = calculateMinimapPoint(ctx, fallbackTarget);
                if (screenPoint == null) {
                    screenPoint = calculateViewportPoint(ctx, fallbackTarget);
                }
                
                if (screenPoint != null) {
                    log.debug("Using fallback click target {} (dist={}) instead of {} (dist={})", 
                            fallbackTarget, fallbackDist, clickTarget, playerPos.distanceTo(clickTarget));
                    clickTarget = fallbackTarget;
                    break;
                }
            }
        }

        if (screenPoint == null) {
            log.debug("Could not calculate click point for {} or any fallback points", clickTarget);
            return;
        }

        // Capture final target for lambda
        final WorldPoint finalClickTarget = clickTarget;

        // Perform the click (minimap and viewport points are canvas-relative)
        clickPending = true;
        CompletableFuture<Void> moveFuture = ctx.getMouseController().moveToCanvas(screenPoint.x, screenPoint.y);

        moveFuture.thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    clickPending = false;
                    log.trace("Clicked to walk toward {}", finalClickTarget);
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Walk click failed", e);
                    return null;
                });
    }

    /**
     * Determine the best point to click toward.
     */
    private WorldPoint determineClickTarget(WorldPoint playerPos) {
        if (currentPath.isEmpty()) {
            return destination;
        }

        // Find a point ahead in the path but within minimap range
        int targetIndex = pathIndex;
        for (int i = pathIndex; i < currentPath.size(); i++) {
            WorldPoint point = currentPath.get(i);
            int distance = playerPos.distanceTo(point);

            if (distance > MINIMAP_CLICK_DISTANCE) {
                break;
            }
            targetIndex = i;
        }

        // Update path index to skip passed points
        for (int i = pathIndex; i < targetIndex; i++) {
            WorldPoint point = currentPath.get(i);
            if (playerPos.distanceTo(point) <= ARRIVAL_DISTANCE) {
                pathIndex = i + 1;
            }
        }

        if (targetIndex < currentPath.size()) {
            return currentPath.get(targetIndex);
        }

        return destination;
    }

    // ========================================================================
    // Phase: Handle Obstacle
    // ========================================================================

    private void executeHandleObstacle(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // If no current obstacle, go back to walking
        if (currentObstacle == null) {
            phase = WalkPhase.WALKING;
            return;
        }

        // Create obstacle handling task if needed
        if (obstacleTask == null) {
            Optional<InteractObjectTask> taskOpt = obstacleHandler.createHandleTask(currentObstacle);
            if (taskOpt.isEmpty()) {
                log.debug("Could not create task for obstacle: {}", currentObstacle);
                currentObstacle = null;
        phase = WalkPhase.CALCULATE_PATH;
                return;
            }
            obstacleTask = taskOpt.get();
            obstacleAttempts = 0;
            log.debug("Created obstacle task: {}", obstacleTask.getDescription());
        }

        // Execute the obstacle task
        TaskState taskState = obstacleTask.getState();
        if (taskState != TaskState.COMPLETED && taskState != TaskState.FAILED) {
            obstacleTask.execute(ctx);
            return;
        }

        // Check if obstacle was successfully handled
        if (taskState == TaskState.COMPLETED) {
            log.debug("Successfully handled obstacle: {}", currentObstacle.getDefinition().getName());
            currentObstacle = null;
            obstacleTask = null;
            obstacleAttempts = 0;
            
            // Wait a moment for game state to update, then recalculate path
            ctx.getHumanTimer().sleep(ctx.getRandomization().uniformRandomLong(300, 600))
                    .thenRun(() -> {
                        phase = WalkPhase.CALCULATE_PATH;
                    });
            return;
        }

        // Obstacle handling failed
        obstacleAttempts++;
        log.warn("Failed to handle obstacle (attempt {}): {}", 
                obstacleAttempts, currentObstacle.getDefinition().getName());

        if (obstacleAttempts >= MAX_OBSTACLE_ATTEMPTS) {
            log.error("Max obstacle attempts reached, trying to find alternate path");
            currentObstacle = null;
            obstacleTask = null;
            obstacleAttempts = 0;
            repathAttempts++;
            phase = WalkPhase.CALCULATE_PATH;
            return;
        }

        // Reset task for retry by creating a new one
        obstacleTask = obstacleHandler.createHandleTask(currentObstacle).orElse(null);
    }

    // ========================================================================
    // Phase: Handle Plane Transition
    // ========================================================================

    private void executeHandlePlaneTransition(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Check if we've reached the target plane
        if (playerPos != null && playerPos.getPlane() == targetPlane) {
            log.info("Reached target plane: {}", targetPlane);
            currentTransition = null;
            transitionTask = null;
            targetPlane = -1;
            
            // Restore final destination if we temporarily changed it
            if (finalDestination != null) {
                destination = finalDestination;
                finalDestination = null;
                log.debug("Restored final destination: {}", destination);
            }
            
            phase = WalkPhase.CALCULATE_PATH;
            return;
        }

        // Find transition if not set
        if (currentTransition == null) {
            findPlaneTransition(ctx, playerPos, targetPlane);
            if (currentTransition == null) {
                log.warn("Could not find plane transition to plane {}", targetPlane);
                fail("No plane transition found to reach destination");
                return;
            }
        }

        // Create transition task if needed
        if (transitionTask == null) {
            boolean goUp = targetPlane > playerPos.getPlane();
            Optional<InteractObjectTask> taskOpt = planeTransitionHandler.createTransitionTask(currentTransition, goUp);
            if (taskOpt.isEmpty()) {
                log.debug("Could not create task for transition: {}", currentTransition);
                currentTransition = null;
                fail("Could not create transition task");
                return;
            }
            transitionTask = taskOpt.get();
            log.debug("Created plane transition task: {}", transitionTask.getDescription());
        }

        // Walk to transition if not nearby
        int distToTransition = playerPos.distanceTo(currentTransition.getLocation());
        if (distToTransition > 3) {
            // Need to walk to the transition first
            log.debug("Walking to plane transition at {}", currentTransition.getLocation());
            
            // Save the final destination before temporarily changing it
            if (finalDestination == null) {
                finalDestination = destination;
                log.debug("Saved final destination: {}", finalDestination);
            }
            
            destination = currentTransition.getLocation();
            phase = WalkPhase.CALCULATE_PATH;
            return;
        }

        // Execute the transition task
        TaskState taskState = transitionTask.getState();
        if (taskState != TaskState.COMPLETED && taskState != TaskState.FAILED) {
            transitionTask.execute(ctx);
            return;
        }

        // Check if transition was successful
        if (taskState == TaskState.COMPLETED) {
            log.debug("Successfully used transition: {}", currentTransition.getDefinition().getName());
            
            // Wait for plane change to take effect
            ctx.getHumanTimer().sleep(ctx.getRandomization().uniformRandomLong(600, 1200))
                    .thenRun(() -> {
                        // Re-check plane and continue
                        currentTransition = null;
                        transitionTask = null;
                        phase = WalkPhase.CALCULATE_PATH;
                    });
            return;
        }

        // Transition failed
        log.warn("Failed to use transition: {}", currentTransition.getDefinition().getName());
        currentTransition = null;
        transitionTask = null;
        fail("Failed to use plane transition");
    }

    /**
     * Find a plane transition to reach the target plane.
     */
    private void findPlaneTransition(TaskContext ctx, WorldPoint playerPos, int targetPlane) {
        if (planeTransitionHandler == null) {
            return;
        }

        Optional<PlaneTransitionHandler.DetectedTransition> transition = 
                planeTransitionHandler.findTransitionToPlane(playerPos, targetPlane, PLANE_TRANSITION_SEARCH_RADIUS);
        
        if (transition.isPresent()) {
            currentTransition = transition.get();
            log.debug("Found plane transition: {} at {}", 
                    currentTransition.getDefinition().getName(), 
                    currentTransition.getLocation());
        }
    }

    // ========================================================================
    // Phase: Teleport Pending
    // ========================================================================

    /**
     * Execute the teleport task and wait for completion.
     */
    private void executeTeleportPending(TaskContext ctx) {
        if (teleportTask == null) {
            log.error("No teleport task in TELEPORT_PENDING phase");
            phase = WalkPhase.CALCULATE_PATH;
            return;
        }

        TaskState taskState = teleportTask.getState();
        
        // Execute if not started or still running
        if (taskState == TaskState.PENDING || taskState == TaskState.RUNNING) {
            teleportTask.execute(ctx);
            return;
        }

        // Check result
        if (taskState == TaskState.COMPLETED) {
            log.debug("Teleport completed successfully");
            teleportTask = null;
            currentEdgeIndex++;
            
            // Continue with path after teleport
            phase = WalkPhase.CALCULATE_PATH;
        } else {
            log.warn("Teleport task failed: {}", teleportTask.getDescription());
            teleportTask = null;
            
            // Try to continue despite teleport failure
            currentEdgeIndex++;
            phase = WalkPhase.CALCULATE_PATH;
        }
    }

    // ========================================================================
    // Phase: Transport Pending
    // ========================================================================

    /**
     * Execute transport task (NPC/object interaction) and wait for completion.
     */
    private void executeTransportPending(TaskContext ctx) {
        // Handle TravelTask for complex transports (fairy rings, spirit trees, etc.)
        if (teleportTask != null) {
            TaskState taskState = teleportTask.getState();
            
            if (taskState == TaskState.PENDING || taskState == TaskState.RUNNING) {
                teleportTask.execute(ctx);
                return;
            }

            if (taskState == TaskState.COMPLETED) {
                log.debug("Transport (TravelTask) completed successfully: {}", 
                        teleportTask.getDescription());
                teleportTask = null;
                currentEdgeIndex++;
                phase = WalkPhase.CALCULATE_PATH;
            } else {
                log.warn("Transport (TravelTask) failed: {}", teleportTask.getDescription());
                teleportTask = null;
                currentEdgeIndex++;
                phase = WalkPhase.CALCULATE_PATH;
            }
            return;
        }

        // Handle object transport (simple interactions)
        if (transportTask != null) {
            TaskState taskState = transportTask.getState();
            
            if (taskState == TaskState.PENDING || taskState == TaskState.RUNNING) {
                transportTask.execute(ctx);
                return;
            }

            if (taskState == TaskState.COMPLETED) {
                log.debug("Transport (object) completed successfully");
                transportTask = null;
                currentEdgeIndex++;
                phase = WalkPhase.CALCULATE_PATH;
            } else {
                log.warn("Transport (object) failed");
                transportTask = null;
                currentEdgeIndex++;
                phase = WalkPhase.CALCULATE_PATH;
            }
            return;
        }

        // Handle NPC transport (simple interactions)
        if (transportNpcTask != null) {
            TaskState taskState = transportNpcTask.getState();
            
            if (taskState == TaskState.PENDING || taskState == TaskState.RUNNING) {
                transportNpcTask.execute(ctx);
                return;
            }

            if (taskState == TaskState.COMPLETED) {
                log.debug("Transport (NPC) completed successfully");
                transportNpcTask = null;
                currentEdgeIndex++;
                phase = WalkPhase.CALCULATE_PATH;
            } else {
                log.warn("Transport (NPC) failed");
                transportNpcTask = null;
                currentEdgeIndex++;
                phase = WalkPhase.CALCULATE_PATH;
            }
            return;
        }

        // No transport task set
        log.error("No transport task in TRANSPORT_PENDING phase");
        phase = WalkPhase.CALCULATE_PATH;
    }

    // ========================================================================
    // Phase: Backtracking
    // ========================================================================

    /**
     * Execute backtracking behavior - walk 2-10 tiles past destination, then return.
     * This simulates "not paying attention" then noticing and returning.
     * Per user spec: 2-10 tiles overshoot for realistic human behavior.
     */
    private void executeBacktracking(TaskContext ctx) {
        var inefficiency = ctx.getInefficiencyInjector();
        if (inefficiency == null) {
            phase = WalkPhase.ARRIVED;
            return;
        }

        // Get backtrack distance (2-10 tiles)
        int backtrackDistance = inefficiency.getBacktrackDistance();
        
        // Save original destination for return trip
        originalDestination = destination;
        
        // Calculate overshoot position (in the direction we were walking)
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();
        
        // Get direction from player to destination (or last position to destination)
        int dx = 0, dy = 0;
        WorldPoint referencePos = lastPosition != null ? lastPosition : playerPos;
        if (referencePos != null && destination != null) {
            dx = destination.getX() - referencePos.getX();
            dy = destination.getY() - referencePos.getY();
            // Normalize to unit direction and scale by backtrack distance
            double mag = Math.sqrt(dx * dx + dy * dy);
            if (mag > 0) {
                dx = (int) Math.round(dx / mag * backtrackDistance);
                dy = (int) Math.round(dy / mag * backtrackDistance);
            }
        }
        
        // If no direction, pick random
        if (dx == 0 && dy == 0) {
            var randomization = ctx.getRandomization();
            double angle = randomization.uniformRandom(0, 2 * Math.PI);
            dx = (int) Math.round(Math.cos(angle) * backtrackDistance);
            dy = (int) Math.round(Math.sin(angle) * backtrackDistance);
        }
        
        // Calculate overshoot destination (past the original destination)
        WorldPoint overshootDest = new WorldPoint(
            destination.getX() + dx,
            destination.getY() + dy,
            destination.getPlane()
        );
        
        log.debug("Backtracking: walking {} tiles past destination to {} (will return to {})", 
                backtrackDistance, overshootDest, originalDestination);
        
        // Set flag to prevent recursive backtracking on the overshoot walk
        isBacktrackWalk = true;
        
        // Set destination to overshoot point and recalculate path
        destination = overshootDest;
        currentPath.clear();
        pathIndex = 0;
        
        // Go to path calculation phase to walk to overshoot
        phase = WalkPhase.BACKTRACK_WALK_TO_OVERSHOOT;
    }
    
    /**
     * Execute the walk to overshoot point during backtracking.
     */
    private void executeBacktrackWalkToOvershoot(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();
        
        // Check if we've arrived at the overshoot point
        if (playerPos != null && destination != null) {
            int distance = playerPos.distanceTo(destination);
            if (distance <= ARRIVAL_DISTANCE) {
                log.debug("Backtracking: arrived at overshoot point, now returning to original destination");
                
                // Now walk back to original destination
                destination = originalDestination;
                currentPath.clear();
                pathIndex = 0;
                
                phase = WalkPhase.BACKTRACK_RETURN;
                return;
            }
        }
        
        // Calculate path to overshoot if needed
        if (currentPath.isEmpty() && pathFinder != null && playerPos != null && destination != null) {
            currentPath = new ArrayList<>(pathFinder.findPath(playerPos, destination));
            pathIndex = 0;
        }
        
        // Handle run energy
        handleRunEnergy(ctx, player);
        
        // Click to continue walking to overshoot
        if (!currentPath.isEmpty()) {
            clickNextPathPoint(ctx, playerPos);
        }
    }
    
    /**
     * Execute the return walk from overshoot to original destination.
     */
    private void executeBacktrackReturn(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();
        
        // Check if we've arrived back at original destination
        if (playerPos != null && destination != null) {
            int distance = playerPos.distanceTo(destination);
            if (distance <= ARRIVAL_DISTANCE) {
                log.debug("Backtracking: returned to original destination");
                
                // Clear backtrack state
                isBacktrackWalk = false;
                originalDestination = null;
                
                phase = WalkPhase.ARRIVED;
                return;
            }
        }
        
        // Calculate path to original destination if needed
        if (currentPath.isEmpty() && pathFinder != null && playerPos != null && destination != null) {
            currentPath = new ArrayList<>(pathFinder.findPath(playerPos, destination));
            pathIndex = 0;
        }
        
        // Handle run energy
        handleRunEnergy(ctx, player);
        
        // Click to continue walking back
        if (!currentPath.isEmpty()) {
            clickNextPathPoint(ctx, playerPos);
        }
    }

    // ========================================================================
    // Run Energy Management
    // ========================================================================

    private void handleRunEnergy(TaskContext ctx, PlayerState player) {
        // Don't toggle if we're already waiting for a click
        if (runTogglePending) {
            return;
        }

        int runEnergy = player.getRunEnergy();
        Client client = ctx.getClient();

        // Check if run is currently enabled via varp 173
        boolean runEnabled = client.getVarpValue(173) == 1;

        WidgetClickHelper widgetClickHelper = ctx.getWidgetClickHelper();
        if (widgetClickHelper == null) {
            // No widget click helper available - log once and skip
            log.trace("WidgetClickHelper not available, cannot toggle run");
            return;
        }

        if (runEnergy >= RUN_ENABLE_THRESHOLD && !runEnabled) {
            // Enable running when energy is above threshold
            log.debug("Run energy {}% >= {}%, enabling run", runEnergy, RUN_ENABLE_THRESHOLD);
            runTogglePending = true;

            widgetClickHelper.clickWidget(ORBS_GROUP_ID, RUN_ORB_CHILD, "Enable run")
                    .thenAccept(success -> {
                        runTogglePending = false;
                        if (success) {
                            log.debug("Run enabled successfully");
                        } else {
                            log.warn("Failed to enable run");
                        }
                    })
                    .exceptionally(e -> {
                        runTogglePending = false;
                        log.error("Error enabling run: {}", e.getMessage());
                        return null;
                    });

        } else if (runEnergy < RUN_DISABLE_THRESHOLD && runEnabled) {
            // Disable running when energy is below threshold to conserve energy
            log.debug("Run energy {}% < {}%, disabling run", runEnergy, RUN_DISABLE_THRESHOLD);
            runTogglePending = true;

            widgetClickHelper.clickWidget(ORBS_GROUP_ID, RUN_ORB_CHILD, "Disable run")
                    .thenAccept(success -> {
                        runTogglePending = false;
                        if (success) {
                            log.debug("Run disabled successfully");
                        } else {
                            log.warn("Failed to disable run");
                        }
                    })
                    .exceptionally(e -> {
                        runTogglePending = false;
                        log.error("Error disabling run: {}", e.getMessage());
                        return null;
                    });
        }
    }

    // ========================================================================
    // Screen Coordinate Calculation
    // ========================================================================

    private Point calculateMinimapPoint(TaskContext ctx, WorldPoint target) {
        Client client = ctx.getClient();
        Player localPlayer = client.getLocalPlayer();

        if (localPlayer == null) {
            return null;
        }

        WorldPoint playerPos = localPlayer.getWorldLocation();
        int dx = target.getX() - playerPos.getX();
        int dy = target.getY() - playerPos.getY();

        // Check if within minimap range
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance > MINIMAP_CLICK_DISTANCE) {
            return null;
        }

        // Dynamically get minimap center and radius from widget bounds
        MinimapInfo minimap = getMinimapInfo(client);
        int minimapCenterX = minimap.centerX;
        int minimapCenterY = minimap.centerY;
        int minimapRadius = minimap.radius;

        // Get CURRENT camera yaw for rotation (not target - that causes incorrect 
        // positions during camera rotation)
        int cameraYaw = client.getCameraYaw();
        double angle = Math.toRadians(cameraYaw * 360.0 / 2048.0);

        // Rotate point based on camera angle
        double rotatedX = dx * Math.cos(angle) + dy * Math.sin(angle);
        double rotatedY = dy * Math.cos(angle) - dx * Math.sin(angle);

        // Scale to minimap (4 pixels per tile approximately)
        int minimapX = minimapCenterX + (int) (rotatedX * 4);
        int minimapY = minimapCenterY - (int) (rotatedY * 4);

        // Verify within minimap bounds
        int distFromCenter = (int) Math.sqrt(
                Math.pow(minimapX - minimapCenterX, 2) +
                Math.pow(minimapY - minimapCenterY, 2));

        if (distFromCenter > minimapRadius - 5) {
            return null;
        }

        // Add slight randomization
        minimapX += Randomization.gaussianInt(0, 2);
        minimapY += Randomization.gaussianInt(0, 2);

        return new Point(minimapX, minimapY);
    }
    
    /**
     * Get minimap center and radius from widget bounds.
     * Supports fixed mode, resizable classic, and resizable modern interface modes.
     * 
     * @param client the RuneLite client
     * @return minimap information (center coordinates and radius)
     */
    private MinimapInfo getMinimapInfo(Client client) {
        // Try each interface mode's minimap widget
        Widget minimap = client.getWidget(MINIMAP_FIXED_GROUP, MINIMAP_FIXED_CHILD);
        
        if (minimap == null || minimap.isHidden()) {
            minimap = client.getWidget(MINIMAP_RESIZABLE_CLASSIC_GROUP, MINIMAP_RESIZABLE_CLASSIC_CHILD);
        }
        
        if (minimap == null || minimap.isHidden()) {
            minimap = client.getWidget(MINIMAP_RESIZABLE_MODERN_GROUP, MINIMAP_RESIZABLE_MODERN_CHILD);
        }
        
        if (minimap != null && !minimap.isHidden()) {
            Rectangle bounds = minimap.getBounds();
            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                int centerX = bounds.x + bounds.width / 2;
                int centerY = bounds.y + bounds.height / 2;
                // Minimap is roughly circular, use smaller dimension / 2 as radius
                int radius = Math.min(bounds.width, bounds.height) / 2;
                return new MinimapInfo(centerX, centerY, radius);
            }
        }
        
        // Fallback to fixed mode defaults
        return new MinimapInfo(FALLBACK_MINIMAP_CENTER_X, FALLBACK_MINIMAP_CENTER_Y, FALLBACK_MINIMAP_RADIUS);
    }
    
    /**
     * Holds minimap center and radius information.
     */
    private static class MinimapInfo {
        final int centerX;
        final int centerY;
        final int radius;
        
        MinimapInfo(int centerX, int centerY, int radius) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.radius = radius;
        }
    }

    private Point calculateViewportPoint(TaskContext ctx, WorldPoint target) {
        Client client = ctx.getClient();

        LocalPoint localPoint = LocalPoint.fromWorld(client, target);
        if (localPoint == null) {
            return null;
        }

        // Get the tile height at the target location for accurate projection
        // Using half the tile center height (ground level) for walking clicks
        int tileHeight = 0;
        try {
            // Get height at tile center (tiles are 128 local units, so offset by 64)
            tileHeight = Perspective.getTileHeight(client, localPoint, target.getPlane());
        } catch (Exception e) {
            log.trace("Could not get tile height at {}: {}", target, e.getMessage());
        }

        // Use Perspective to get canvas coordinates with proper height offset
        net.runelite.api.Point canvasPoint = Perspective.localToCanvas(
                client, localPoint, target.getPlane(), tileHeight);

        if (canvasPoint == null) {
            return null;
        }

        // Verify the point is within the viewport
        java.awt.Rectangle viewport = ctx.getGameStateService().getViewportBounds();
        if (!viewport.contains(canvasPoint.getX(), canvasPoint.getY())) {
            log.trace("Viewport point {} outside viewport {}", canvasPoint, viewport);
            return null;
        }

        // Add slight randomization using ClickPointCalculator for humanization
        int offsetX = com.rocinante.input.ClickPointCalculator.randomGaussianOffset(6, 2);
        int offsetY = com.rocinante.input.ClickPointCalculator.randomGaussianOffset(6, 2);
        int x = canvasPoint.getX() + offsetX;
        int y = canvasPoint.getY() + offsetY;

        // Clamp to viewport bounds
        x = Math.max(viewport.x, Math.min(x, viewport.x + viewport.width - 1));
        y = Math.max(viewport.y, Math.min(y, viewport.y + viewport.height - 1));

        return new Point(x, y);
    }

    // ========================================================================
    // Object/NPC Finding
    // ========================================================================

    private WorldPoint findNearestObject(TaskContext ctx, int objectId) {
        Client client = ctx.getClient();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();

        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
        if (playerPos == null) {
            return null;
        }

        int plane = playerPos.getPlane();
        WorldPoint nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (int x = 0; x < Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[plane][x][y];
                if (tile == null) continue;

                for (GameObject obj : tile.getGameObjects()) {
                    if (obj != null && obj.getId() == objectId) {
                        WorldPoint objPos = obj.getWorldLocation();
                        int dist = playerPos.distanceTo(objPos);
                        if (dist < nearestDistance) {
                            nearest = objPos;
                            nearestDistance = dist;
                        }
                    }
                }
            }
        }

        return nearest;
    }

    private WorldPoint findNearestNpc(TaskContext ctx, int npcId) {
        Client client = ctx.getClient();
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
        if (playerPos == null) {
            return null;
        }

        WorldPoint nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (NPC npc : client.getNpcs()) {
            if (npc != null && npc.getId() == npcId) {
                WorldPoint npcPos = npc.getWorldLocation();
                int dist = playerPos.distanceTo(npcPos);
                if (dist < nearestDistance) {
                    nearest = npcPos;
                    nearestDistance = dist;
                }
            }
        }

        return nearest;
    }

    // ========================================================================
    // Service Initialization
    // ========================================================================

    private void initializeServices(TaskContext ctx) {
        // Get services from context (singleton instances)
        pathFinder = ctx.getPathFinder();
        webWalker = ctx.getWebWalker();
        obstacleHandler = ctx.getObstacleHandler();
        planeTransitionHandler = ctx.getPlaneTransitionHandler();

        // If not available in context, create fallback instances
        if (pathFinder == null) {
            log.warn("PathFinder not available in TaskContext, creating instance");
            pathFinder = new PathFinder(ctx.getClient());
        }

        if (obstacleHandler == null) {
            log.warn("ObstacleHandler not available in TaskContext, creating instance");
            obstacleHandler = new ObstacleHandler(ctx.getClient());
        }

        if (planeTransitionHandler == null) {
            log.warn("PlaneTransitionHandler not available in TaskContext, creating instance");
            planeTransitionHandler = new PlaneTransitionHandler(ctx.getClient());
        }

        if (webWalker == null) {
            log.warn("WebWalker not available in TaskContext, creating instance");
            webWalker = new WebWalker(ctx.getClient(), ctx.getUnlockTracker(), planeTransitionHandler);
        }

        // Configure WebWalker with account state from centralized TaskContext
        if (webWalker != null) {
            // Set ironman state if available
            if (ctx.getIronmanState() != null) {
            webWalker.setIronman(ctx.getIronmanState().isIronman());
            webWalker.setHardcoreIronman(ctx.getIronmanState().isHardcore());
            webWalker.setUltimateIronman(ctx.getIronmanState().isUltimate());
            log.debug("WebWalker configured for ironman mode: {}, hardcore: {}, ultimate: {}",
                    ctx.getIronmanState().isIronman(), ctx.getIronmanState().isHardcore(),
                    ctx.getIronmanState().isUltimate());
            }
            
            // Set ResourceAwareness from TaskContext (centralized computation)
            ResourceAwareness resourceAwareness = ctx.getResourceAwareness();
            if (resourceAwareness != null) {
                webWalker.setResourceAwareness(resourceAwareness);
                log.debug("WebWalker configured with ResourceAwareness from TaskContext");
            }
        }

        servicesInitialized = true;
        log.debug("Navigation services initialized (PathFinder: {}, WebWalker: {}, ObstacleHandler: {}, PlaneTransitionHandler: {})",
                pathFinder != null ? "available" : "not available",
                webWalker != null ? "available" : "not available",
                obstacleHandler != null ? "available" : "not available",
                planeTransitionHandler != null ? "available" : "not available");
    }

    // ========================================================================
    // Description
    // ========================================================================

    private String getDestinationDescription() {
        if (namedLocation != null) {
            return namedLocation;
        }
        if (targetObjectId > 0) {
            return "object:" + targetObjectId;
        }
        if (targetNpcId > 0) {
            return "npc:" + targetNpcId;
        }
        if (destination != null && destination.getX() != 0) {
            return String.format("(%d, %d, %d)",
                    destination.getX(), destination.getY(), destination.getPlane());
        }
        return "unknown";
    }

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return "Walk to " + getDestinationDescription();
    }

    // ========================================================================
    // Walk Phase Enum
    // ========================================================================

    private enum WalkPhase {
        INIT,
        RESOLVE_DESTINATION,
        CALCULATE_PATH,
        WALKING,
        HANDLE_OBSTACLE,
        HANDLE_PLANE_TRANSITION,
        BACKTRACKING,
        BACKTRACK_WALK_TO_OVERSHOOT,
        BACKTRACK_RETURN,
        TELEPORT_PENDING,
        TRANSPORT_PENDING,
        ARRIVED
    }
}
