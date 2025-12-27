package com.rocinante.tasks.impl;

import com.rocinante.input.WidgetClickHelper;
import com.rocinante.navigation.*;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
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
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

import java.awt.Point;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
     */
    private static final double PATH_DEVIATION_CHANCE = 0.10;

    /**
     * Maximum deviation distance in tiles.
     */
    private static final int MAX_DEVIATION_TILES = 2;

    /**
     * Maximum tiles to click ahead on minimap.
     */
    private static final int MINIMAP_CLICK_DISTANCE = 15;

    /**
     * Minimap dimensions (approximate for fixed mode).
     */
    private static final int MINIMAP_CENTER_X = 643;
    private static final int MINIMAP_CENTER_Y = 83;
    private static final int MINIMAP_RADIUS = 73;

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
     * Custom description for this walk task.
     */
    @Getter
    @Setter
    private String description;

    // ========================================================================
    // Navigation Services (injected via context)
    // ========================================================================

    private PathFinder pathFinder;
    private WebWalker webWalker;
    private ObstacleHandler obstacleHandler;

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
     * Web nodes path (for long-distance walking).
     */
    private List<WebNode> webPath = new ArrayList<>();

    /**
     * Current index in web path.
     */
    private int webPathIndex = 0;

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

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.isLoggedIn();
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        // Initialize navigation services on first execution
        if (pathFinder == null) {
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

        // Check if already at destination
        if (playerPos != null && destination != null) {
            int distance = playerPos.distanceTo(destination);
            if (distance <= ARRIVAL_DISTANCE) {
                log.info("Already at destination");
                phase = WalkPhase.ARRIVED;
                return;
            }
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

        int distance = playerPos.distanceTo(destination);
        log.debug("Distance to destination: {} tiles", distance);

        // Choose pathfinding strategy based on distance
        if (distance > WEBWALKER_DISTANCE_THRESHOLD) {
            // Use WebWalker for long distances
            calculateWebPath(playerPos);
        } else {
            // Use direct PathFinder for short distances
            calculateDirectPath(playerPos);
        }

        if (currentPath.isEmpty() && webPath.isEmpty()) {
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

    private void calculateWebPath(WorldPoint playerPos) {
        webPath = webWalker.findPath(playerPos, destination);

        if (!webPath.isEmpty()) {
            log.debug("WebWalker found path with {} nodes", webPath.size());
            webPathIndex = 0;

            // Calculate direct path to first web node
            if (webPath.size() > 0) {
                WorldPoint firstNode = webPath.get(0).getWorldPoint();
                currentPath = pathFinder.findPath(playerPos, firstNode);
                pathIndex = 0;
            }
        } else {
            log.debug("WebWalker could not find path, trying direct pathfinding");
            calculateDirectPath(playerPos);
        }
    }

    private void calculateDirectPath(WorldPoint playerPos) {
        currentPath = pathFinder.findPath(playerPos, destination);

        if (!currentPath.isEmpty()) {
            log.debug("PathFinder found path with {} tiles", currentPath.size());
            pathIndex = 0;

            // Apply humanized deviation
            if (Math.random() < PATH_DEVIATION_CHANCE && currentPath.size() > 3) {
                applyPathDeviation();
            }
        }
    }

    /**
     * Apply small random deviation to path for humanization.
     */
    private void applyPathDeviation() {
        int deviationPoint = 1 + (int) (Math.random() * (currentPath.size() - 2));
        WorldPoint original = currentPath.get(deviationPoint);

        int dx = (int) (Math.random() * (MAX_DEVIATION_TILES * 2 + 1)) - MAX_DEVIATION_TILES;
        int dy = (int) (Math.random() * (MAX_DEVIATION_TILES * 2 + 1)) - MAX_DEVIATION_TILES;

        WorldPoint deviated = new WorldPoint(
                original.getX() + dx,
                original.getY() + dy,
                original.getPlane()
        );

        // Only apply if deviation is walkable
        if (pathFinder.isWalkable(deviated)) {
            currentPath.set(deviationPoint, deviated);
            log.trace("Applied path deviation at index {}: {} -> {}", deviationPoint, original, deviated);
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

        // Check if arrived at final destination
        if (playerPos != null && destination != null &&
                playerPos.distanceTo(destination) <= ARRIVAL_DISTANCE) {
            log.info("Arrived at destination: {}", destination);
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

        // If we have a web path and finished current segment, move to next
        if (!webPath.isEmpty() && currentPath.isEmpty() && webPathIndex < webPath.size()) {
            advanceWebPath(playerPos);
            return;
        }

        // Click on next path point
        if (!currentPath.isEmpty()) {
            clickNextPathPoint(ctx, playerPos);
        }
    }

    private void advanceWebPath(WorldPoint playerPos) {
        WebNode currentTarget = webPath.get(webPathIndex);

        // Check if reached current web node
        if (playerPos.distanceTo(currentTarget.getWorldPoint()) <= ARRIVAL_DISTANCE) {
            webPathIndex++;

            if (webPathIndex >= webPath.size()) {
                // Reached final web node, calculate path to actual destination
                currentPath = pathFinder.findPath(playerPos, destination);
                pathIndex = 0;
            } else {
                // Calculate path to next web node
                WorldPoint nextNode = webPath.get(webPathIndex).getWorldPoint();
                currentPath = pathFinder.findPath(playerPos, nextNode);
                pathIndex = 0;
            }
        }
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
                log.debug("Obstacle detected: {}", blockingObstacle.get());
                phase = WalkPhase.HANDLE_OBSTACLE;
                return;
            }
        }

        // Calculate screen point for click
        Point screenPoint = calculateMinimapPoint(ctx, clickTarget);
        if (screenPoint == null) {
            // Try viewport click for nearby tiles
            screenPoint = calculateViewportPoint(ctx, clickTarget);
        }

        if (screenPoint == null) {
            log.debug("Could not calculate click point for {}", clickTarget);
            return;
        }

        // Perform the click (minimap and viewport points are canvas-relative)
        clickPending = true;
        CompletableFuture<Void> moveFuture = ctx.getMouseController().moveToCanvas(screenPoint.x, screenPoint.y);

        moveFuture.thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    clickPending = false;
                    log.trace("Clicked to walk toward {}", clickTarget);
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

        // For now, simply try to recalculate path
        // In future, this could trigger an InteractObjectTask for doors/gates
        log.debug("Handling obstacle - recalculating path");
        phase = WalkPhase.CALCULATE_PATH;
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

        // Get camera yaw for rotation
        int cameraYaw = client.getCameraYawTarget();
        double angle = Math.toRadians(cameraYaw * 360.0 / 2048.0);

        // Rotate point based on camera angle
        double rotatedX = dx * Math.cos(angle) + dy * Math.sin(angle);
        double rotatedY = dy * Math.cos(angle) - dx * Math.sin(angle);

        // Scale to minimap (4 pixels per tile approximately)
        int minimapX = MINIMAP_CENTER_X + (int) (rotatedX * 4);
        int minimapY = MINIMAP_CENTER_Y - (int) (rotatedY * 4);

        // Verify within minimap bounds
        int distFromCenter = (int) Math.sqrt(
                Math.pow(minimapX - MINIMAP_CENTER_X, 2) +
                Math.pow(minimapY - MINIMAP_CENTER_Y, 2));

        if (distFromCenter > MINIMAP_RADIUS - 5) {
            return null;
        }

        // Add slight randomization
        minimapX += Randomization.gaussianInt(0, 2);
        minimapY += Randomization.gaussianInt(0, 2);

        return new Point(minimapX, minimapY);
    }

    private Point calculateViewportPoint(TaskContext ctx, WorldPoint target) {
        Client client = ctx.getClient();

        LocalPoint localPoint = LocalPoint.fromWorld(client, target);
        if (localPoint == null) {
            return null;
        }

        // Use Perspective to get canvas coordinates
        // This is a simplified version - proper implementation would use full 3D projection
        net.runelite.api.Point canvasPoint = Perspective.localToCanvas(
                client, localPoint, target.getPlane());

        if (canvasPoint == null) {
            return null;
        }

        // Add randomization
        int x = canvasPoint.getX() + Randomization.gaussianInt(0, 3);
        int y = canvasPoint.getY() + Randomization.gaussianInt(0, 3);

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
        Client client = ctx.getClient();

        // Create PathFinder
        pathFinder = new PathFinder(client);

        // Create WebWalker with UnlockTracker from context for edge requirement checking
        // UnlockTracker enables proper checking of:
        // - Magic level requirements for teleport edges
        // - Quest completion requirements for shortcuts/areas
        // - Agility level requirements for obstacles
        // - Item requirements (runes, etc.)
        webWalker = new WebWalker(client, ctx.getUnlockTracker());

        // Create ObstacleHandler
        obstacleHandler = new ObstacleHandler(client);

        log.debug("Navigation services initialized (UnlockTracker: {})", 
                ctx.getUnlockTracker() != null ? "available" : "not available");
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
        ARRIVED
    }
}
