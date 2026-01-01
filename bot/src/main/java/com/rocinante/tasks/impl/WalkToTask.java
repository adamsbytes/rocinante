package com.rocinante.tasks.impl;

import com.rocinante.behavior.InefficiencyInjector;
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
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.TileObject;

import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Task for walking to a destination using ShortestPath plugin for pathfinding.
 *
 * <p>This task uses the Shortest Path plugin for all navigation, which provides:
 * <ul>
 *   <li>Global pathfinding with pre-computed collision data</li>
 *   <li>Automatic transport detection (teleports, fairy rings, boats, etc.)</li>
 *   <li>Multi-plane navigation via stairs, ladders, etc.</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Walk to specific coordinates
 * WalkToTask walkToBank = new WalkToTask(3253, 3420);
 *
 * // Walk to a WorldPoint
 * WalkToTask walkToPoint = new WalkToTask(new WorldPoint(3253, 3420, 0));
 * }</pre>
 */
@Slf4j
public class WalkToTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    private static final int RUN_ENABLE_THRESHOLD = 40;
    private static final int RUN_DISABLE_THRESHOLD = 15;
    private static final int ARRIVAL_DISTANCE = 2;
    private static final int MINIMAP_CLICK_DISTANCE = 15;
    private static final int MIN_TICKS_BETWEEN_CLICKS = 4;
    private static final int STUCK_THRESHOLD_TICKS = 8;
    private static final int MAX_REPATH_ATTEMPTS = 3;

    // Minimap widget IDs for different interface modes
    private static final int MINIMAP_FIXED_GROUP = 548;
    private static final int MINIMAP_FIXED_CHILD = 23;
    private static final int MINIMAP_RESIZABLE_CLASSIC_GROUP = 161;
    private static final int MINIMAP_RESIZABLE_CLASSIC_CHILD = 28;
    private static final int MINIMAP_RESIZABLE_MODERN_GROUP = 164;
    private static final int MINIMAP_RESIZABLE_MODERN_CHILD = 19;
    private static final int FALLBACK_MINIMAP_CENTER_X = 643;
    private static final int FALLBACK_MINIMAP_CENTER_Y = 83;
    private static final int FALLBACK_MINIMAP_RADIUS = 73;

    private static final int ORBS_GROUP_ID = 160;
    private static final int RUN_ORB_CHILD = 27;

    // ========================================================================
    // Destination Configuration
    // ========================================================================

    @Getter
    private WorldPoint destination;

    @Getter @Setter
    private int targetObjectId = -1;

    @Getter @Setter
    private int targetNpcId = -1;

    @Getter @Setter
    private String description;

    // ========================================================================
    // Navigation Services
    // ========================================================================

    private com.rocinante.navigation.NavigationService navigationService;
    private boolean servicesInitialized = false;

    // ========================================================================
    // Execution State
    // ========================================================================

    private WalkPhase phase = WalkPhase.INIT;
    private List<WorldPoint> currentPath = new ArrayList<>();
    private int pathIndex = 0;
    private WorldPoint lastPosition;
    private int ticksSinceMove = 0;
    private int ticksSinceLastClick = 0;
    private boolean clickPending = false;
    private boolean cameraRotationPending = false;
    private int repathAttempts = 0;
    private boolean runTogglePending = false;

    // Transport handling
    private List<com.rocinante.navigation.ShortestPathBridge.TransportSegment> transportSegments = new ArrayList<>();
    private int currentTransportIndex = 0;
    private TravelTask currentTravelTask;
    private InteractObjectTask currentInteractTask;
    private InteractNpcTask currentNpcTask;

    // ========================================================================
    // Constructors
    // ========================================================================

    public WalkToTask(int x, int y) {
        this(new WorldPoint(x, y, 0));
    }

    public WalkToTask(int x, int y, int plane) {
        this(new WorldPoint(x, y, plane));
    }

    public WalkToTask(WorldPoint destination) {
        this.destination = destination;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    public static WalkToTask toObject(int objectId) {
        WalkToTask task = new WalkToTask(0, 0);
        task.targetObjectId = objectId;
        task.description = "Walk to object " + objectId;
        return task;
    }

    public static WalkToTask toNpc(int npcId) {
        WalkToTask task = new WalkToTask(0, 0);
        task.targetNpcId = npcId;
        task.description = "Walk to NPC " + npcId;
        return task;
    }

    public WalkToTask withDescription(String description) {
        this.description = description;
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
        phase = WalkPhase.INIT;
        currentPath.clear();
        pathIndex = 0;
        lastPosition = null;
        ticksSinceMove = 0;
        ticksSinceLastClick = 0;
        clickPending = false;
        cameraRotationPending = false;
        repathAttempts = 0;
        runTogglePending = false;
        transportSegments.clear();
        currentTransportIndex = 0;
        currentTravelTask = null;
        currentInteractTask = null;
        currentNpcTask = null;
        log.debug("WalkToTask reset");
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
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
            case REQUEST_PATH:
                executeRequestPath(ctx);
                break;
            case WAIT_FOR_PATH:
                executeWaitForPath(ctx);
                break;
            case WALKING:
                executeWalking(ctx);
                break;
            case EXECUTE_TRANSPORT:
                executeTransport(ctx);
                break;
            case ARRIVED:
                complete();
                break;
        }
    }

    private void initializeServices(TaskContext ctx) {
        navigationService = ctx.getNavigationService();

        if (navigationService == null) {
            throw new IllegalStateException("NavigationService is null - dependency injection failed");
        }
        // No availability check - ShortestPath must be available by this point (enforced by TaskExecutor.start())

        servicesInitialized = true;
        log.debug("Navigation services initialized");
    }

    // ========================================================================
    // Phase: Init
    // ========================================================================

    private void executeInit(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        lastPosition = player.getWorldPosition();
        handleRunEnergy(ctx, player);
        log.info("WalkToTask starting: {} -> {}", lastPosition, getDestinationDescription());
        phase = WalkPhase.RESOLVE_DESTINATION;
    }

    // ========================================================================
    // Phase: Resolve Destination
    // ========================================================================

    private void executeResolveDestination(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Resolve object ID to nearest location
        if (targetObjectId > 0 && (destination == null || destination.getX() == 0)) {
            WorldPoint objLocation = findNearestObject(ctx, targetObjectId);
            if (objLocation != null) {
                destination = objLocation;
                log.debug("Resolved object {} to {}", targetObjectId, destination);
            } else {
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
                fail("NPC not found: " + targetNpcId);
                return;
            }
        }

        // Validate destination
        if (destination == null || destination.getX() == 0) {
            fail("No valid destination");
            return;
        }

        // Check if already at destination
        if (playerPos != null && playerPos.distanceTo(destination) <= ARRIVAL_DISTANCE 
                && playerPos.getPlane() == destination.getPlane()) {
            log.info("Already at destination");
            phase = WalkPhase.ARRIVED;
            return;
        }

        phase = WalkPhase.REQUEST_PATH;
    }

    // ========================================================================
    // Phase: Request Path
    // ========================================================================

    private void executeRequestPath(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos == null) {
            fail("Invalid player position");
            return;
        }

        navigationService.requestPath(ctx, playerPos, destination);
        log.debug("Requested path from {} to {}", playerPos, destination);
        phase = WalkPhase.WAIT_FOR_PATH;
    }

    // ========================================================================
    // Phase: Wait for Path
    // ========================================================================

    private void executeWaitForPath(TaskContext ctx) {
        if (!navigationService.isPathReady()) {
            // Still computing - wait
            return;
        }

        List<WorldPoint> path = navigationService.getCurrentPath();
        if (path.isEmpty()) {
            repathAttempts++;
            if (repathAttempts >= MAX_REPATH_ATTEMPTS) {
                fail("Could not find path after " + repathAttempts + " attempts");
                return;
            }
            log.warn("No path found, retrying (attempt {})", repathAttempts);
            phase = WalkPhase.REQUEST_PATH;
            return;
        }

        currentPath = new ArrayList<>(path);
        pathIndex = 0;

        // Analyze path for transports
        transportSegments = navigationService.analyzePathForTransports(currentPath);
        currentTransportIndex = 0;

        log.debug("Path ready: {} tiles, {} transports", currentPath.size(), transportSegments.size());
        phase = WalkPhase.WALKING;
    }

    // ========================================================================
    // Phase: Walking
    // ========================================================================

    private void executeWalking(TaskContext ctx) {
        if (clickPending) {
            return;
        }

        ticksSinceLastClick++;

        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Check arrival
        if (playerPos != null && destination != null 
                && playerPos.distanceTo(destination) <= ARRIVAL_DISTANCE
                && playerPos.getPlane() == destination.getPlane()) {
            log.info("Arrived at destination: {}", destination);
            phase = WalkPhase.ARRIVED;
            return;
        }

        // Stuck detection
        if (playerPos != null && playerPos.equals(lastPosition)) {
            ticksSinceMove++;
            if (ticksSinceMove > STUCK_THRESHOLD_TICKS && !player.isMoving()) {
                log.debug("Stuck detected, re-requesting path");
                navigationService.clearPath();
                phase = WalkPhase.REQUEST_PATH;
                ticksSinceMove = 0;
                return;
            }
        } else {
            lastPosition = playerPos;
            ticksSinceMove = 0;
        }

        handleRunEnergy(ctx, player);

        // Check if we're at a transport origin
        if (!transportSegments.isEmpty() && currentTransportIndex < transportSegments.size()) {
            com.rocinante.navigation.ShortestPathBridge.TransportSegment transport = transportSegments.get(currentTransportIndex);
            if (playerPos != null && playerPos.distanceTo(transport.getOrigin()) <= 2) {
                log.debug("At transport origin, executing transport");
                setupTransportExecution(ctx, transport);
                return;
            }
        }

        // Click to walk
        boolean shouldClick = ticksSinceLastClick >= MIN_TICKS_BETWEEN_CLICKS 
                || !player.isMoving() 
                || ticksSinceMove > 2;

        if (!currentPath.isEmpty() && shouldClick) {
            clickNextPathPoint(ctx, playerPos);
        }
    }

    // ========================================================================
    // Phase: Execute Transport
    // ========================================================================

    private void setupTransportExecution(TaskContext ctx, com.rocinante.navigation.ShortestPathBridge.TransportSegment segment) {
        // Use NavigationService to create TravelTask from transport segment
        Optional<TravelTask> travelTaskOpt = navigationService.createTravelTask(segment);
        if (travelTaskOpt.isPresent()) {
            currentTravelTask = travelTaskOpt.get();
            log.debug("Created TravelTask for transport segment");
        } else {
            // Fallback: try simple object interaction
            com.rocinante.navigation.ShortestPathBridge.TransportInfo transport = segment.getTransport();
            if (transport != null) {
                com.rocinante.navigation.ShortestPathBridge.ParsedMenuInfo menuInfo = transport.parseObjectInfo();
                if (menuInfo != null && menuInfo.getObjectId() > 0) {
                    currentInteractTask = new InteractObjectTask(menuInfo.getObjectId(), menuInfo.getMenuOption());
                    currentInteractTask.setDescription("Use " + transport.getType().toLowerCase().replace("_", " "));
                    log.debug("Created InteractObjectTask for transport: {}", transport.getType());
                } else {
                    log.warn("Transport segment has no usable info, skipping");
                }
            } else {
                log.warn("Transport segment has no info, skipping");
            }
        }

        currentTransportIndex++;
        phase = WalkPhase.EXECUTE_TRANSPORT;
    }

    private void executeTransport(TaskContext ctx) {
        // Execute TravelTask if set
        if (currentTravelTask != null) {
            TaskState state = currentTravelTask.getState();
            if (state == TaskState.PENDING || state == TaskState.RUNNING) {
                currentTravelTask.execute(ctx);
                return;
            }
            if (state == TaskState.COMPLETED) {
                log.debug("Transport completed");
                currentTravelTask = null;
                phase = WalkPhase.REQUEST_PATH; // Re-request path from new location
                return;
            }
            log.warn("Transport failed");
            currentTravelTask = null;
            phase = WalkPhase.REQUEST_PATH;
            return;
        }

        // Execute InteractObjectTask if set
        if (currentInteractTask != null) {
            TaskState state = currentInteractTask.getState();
            if (state == TaskState.PENDING || state == TaskState.RUNNING) {
                currentInteractTask.execute(ctx);
                return;
            }
            if (state == TaskState.COMPLETED) {
                log.debug("Object interaction completed");
                currentInteractTask = null;
                phase = WalkPhase.REQUEST_PATH;
                return;
            }
            log.warn("Object interaction failed");
            currentInteractTask = null;
            phase = WalkPhase.REQUEST_PATH;
            return;
        }

        // Execute InteractNpcTask if set
        if (currentNpcTask != null) {
            TaskState state = currentNpcTask.getState();
            if (state == TaskState.PENDING || state == TaskState.RUNNING) {
                currentNpcTask.execute(ctx);
                return;
            }
            currentNpcTask = null;
            phase = WalkPhase.REQUEST_PATH;
            return;
        }

        // No transport task active - shouldn't happen
        log.warn("No transport task in EXECUTE_TRANSPORT phase");
        phase = WalkPhase.WALKING;
    }

    // ========================================================================
    // Click Handling
    // ========================================================================

    private void clickNextPathPoint(TaskContext ctx, WorldPoint playerPos) {
        WorldPoint clickTarget = determineClickTarget(playerPos);
        if (clickTarget == null) {
            return;
        }

        Point screenPoint = calculateMinimapPoint(ctx, clickTarget);
        
        // If minimap click fails, try intermediate point FIRST before viewport
        // Viewport clicks are risky - they can accidentally click objects while moving
        if (screenPoint == null) {
            WorldPoint intermediate = calculateIntermediatePoint(playerPos, clickTarget);
            if (intermediate != null && !intermediate.equals(clickTarget)) {
                screenPoint = calculateMinimapPoint(ctx, intermediate);
                if (screenPoint != null) {
                    clickTarget = intermediate;
                    log.debug("Using intermediate minimap point {} toward target", intermediate);
                }
            }
        }

        // Only fall back to viewport if minimap completely fails AND player isn't moving
        // This prevents clicking objects while running
        if (screenPoint == null && !ctx.getPlayerState().isMoving()) {
            screenPoint = calculateViewportPoint(ctx, clickTarget);
            if (screenPoint != null) {
                log.debug("Using viewport click for {} (player stationary)", clickTarget);
            }
        }

        if (screenPoint == null) {
            // Target not clickable - start camera rotation
            var coupler = ctx.getMouseCameraCoupler();
            if (coupler != null && !cameraRotationPending) {
                cameraRotationPending = true;
                log.debug("Target {} not clickable, rotating camera", clickTarget);
                coupler.ensureTargetVisible(clickTarget)
                        .whenComplete((v, ex) -> cameraRotationPending = false);
            }
            log.debug("Could not calculate click point for {}", clickTarget);
            return;
        }

        final WorldPoint finalTarget = clickTarget;
        clickPending = true;
        ticksSinceLastClick = 0;

        ctx.getMouseController().moveToCanvas(screenPoint.x, screenPoint.y)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    clickPending = false;
                    log.debug("Clicked toward {}", finalTarget);
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Walk click failed", e);
                    return null;
                });
    }
    
    /**
     * Calculate an intermediate point along the direction to target, within minimap click range.
     */
    private WorldPoint calculateIntermediatePoint(WorldPoint from, WorldPoint to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        double distance = Math.sqrt(dx * dx + dy * dy);
        
        if (distance <= MINIMAP_CLICK_DISTANCE) {
            return to; // Already in range
        }
        
        // Scale to just under minimap range
        double scale = (MINIMAP_CLICK_DISTANCE - 1) / distance;
        int newX = from.getX() + (int) (dx * scale);
        int newY = from.getY() + (int) (dy * scale);
        
        return new WorldPoint(newX, newY, from.getPlane());
    }

    private WorldPoint determineClickTarget(WorldPoint playerPos) {
        if (currentPath.isEmpty()) {
            return destination;
        }

        // Find point ahead in path within minimap range
        int targetIndex = pathIndex;
        for (int i = pathIndex; i < currentPath.size(); i++) {
            WorldPoint point = currentPath.get(i);
            if (playerPos.distanceTo(point) > MINIMAP_CLICK_DISTANCE) {
                break;
            }
            targetIndex = i;
        }

        // Update path index
        for (int i = pathIndex; i < targetIndex; i++) {
            if (playerPos.distanceTo(currentPath.get(i)) <= ARRIVAL_DISTANCE) {
                pathIndex = i + 1;
            }
        }

        return targetIndex < currentPath.size() ? currentPath.get(targetIndex) : destination;
    }

    private Point calculateMinimapPoint(TaskContext ctx, WorldPoint target) {
        Client client = ctx.getClient();
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) return null;

        WorldPoint playerPos = localPlayer.getWorldLocation();
        int dx = target.getX() - playerPos.getX();
        int dy = target.getY() - playerPos.getY();

        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance > MINIMAP_CLICK_DISTANCE) return null;

        MinimapInfo minimap = getMinimapInfo(client);
        int cameraYaw = client.getCameraYaw();
        double angle = Math.toRadians(cameraYaw * 360.0 / 2048.0);

        double rotatedX = dx * Math.cos(angle) + dy * Math.sin(angle);
        double rotatedY = dy * Math.cos(angle) - dx * Math.sin(angle);

        double scaledX = rotatedX * 4;
        double scaledY = rotatedY * 4;

        double distFromCenter = Math.sqrt(scaledX * scaledX + scaledY * scaledY);
        int maxDist = minimap.radius - 3;
        if (distFromCenter > maxDist && distFromCenter > 0) {
            double scale = maxDist / distFromCenter;
            scaledX *= scale;
            scaledY *= scale;
        }

        int minimapX = minimap.centerX + (int) scaledX + Randomization.gaussianInt(0, 2);
        int minimapY = minimap.centerY - (int) scaledY + Randomization.gaussianInt(0, 2);

        return new Point(minimapX, minimapY);
    }

    private MinimapInfo getMinimapInfo(Client client) {
        Widget minimap = client.getWidget(MINIMAP_FIXED_GROUP, MINIMAP_FIXED_CHILD);
        if (minimap == null || minimap.isHidden()) {
            minimap = client.getWidget(MINIMAP_RESIZABLE_CLASSIC_GROUP, MINIMAP_RESIZABLE_CLASSIC_CHILD);
        }
        if (minimap == null || minimap.isHidden()) {
            minimap = client.getWidget(MINIMAP_RESIZABLE_MODERN_GROUP, MINIMAP_RESIZABLE_MODERN_CHILD);
        }

        if (minimap != null && !minimap.isHidden()) {
            Rectangle bounds = minimap.getBounds();
            if (bounds != null && bounds.width > 0) {
                return new MinimapInfo(
                        bounds.x + bounds.width / 2,
                        bounds.y + bounds.height / 2,
                        Math.min(bounds.width, bounds.height) / 2
                );
            }
        }

        return new MinimapInfo(FALLBACK_MINIMAP_CENTER_X, FALLBACK_MINIMAP_CENTER_Y, FALLBACK_MINIMAP_RADIUS);
    }

    private Point calculateViewportPoint(TaskContext ctx, WorldPoint target) {
        Client client = ctx.getClient();
        LocalPoint localPoint = LocalPoint.fromWorld(client, target);
        if (localPoint == null) return null;

        int tileHeight = 0;
        try {
            tileHeight = Perspective.getTileHeight(client, localPoint, target.getPlane());
        } catch (Exception ignored) {}

        net.runelite.api.Point canvasPoint = Perspective.localToCanvas(
                client, localPoint, target.getPlane(), tileHeight);
        if (canvasPoint == null) return null;

        Rectangle viewport = ctx.getGameStateService().getViewportBounds();
        if (!viewport.contains(canvasPoint.getX(), canvasPoint.getY())) return null;

        int x = canvasPoint.getX() + com.rocinante.input.ClickPointCalculator.randomGaussianOffset(6, 2);
        int y = canvasPoint.getY() + com.rocinante.input.ClickPointCalculator.randomGaussianOffset(6, 2);

        x = Math.max(viewport.x, Math.min(x, viewport.x + viewport.width - 1));
        y = Math.max(viewport.y, Math.min(y, viewport.y + viewport.height - 1));

        return new Point(x, y);
    }

    // ========================================================================
    // Run Energy
    // ========================================================================

    private void handleRunEnergy(TaskContext ctx, PlayerState player) {
        if (runTogglePending) return;

        int runEnergy = player.getRunEnergy();
        Client client = ctx.getClient();
        boolean runEnabled = client.getVarpValue(173) == 1;

        WidgetClickHelper helper = ctx.getWidgetClickHelper();
        if (helper == null) return;

        if (runEnergy >= RUN_ENABLE_THRESHOLD && !runEnabled) {
            runTogglePending = true;
            helper.clickWidget(ORBS_GROUP_ID, RUN_ORB_CHILD, "Enable run")
                    .thenAccept(success -> runTogglePending = false)
                    .exceptionally(e -> { runTogglePending = false; return null; });
        } else if (runEnergy < RUN_DISABLE_THRESHOLD && runEnabled) {
            runTogglePending = true;
            helper.clickWidget(ORBS_GROUP_ID, RUN_ORB_CHILD, "Disable run")
                    .thenAccept(success -> runTogglePending = false)
                    .exceptionally(e -> { runTogglePending = false; return null; });
        }
    }

    // ========================================================================
    // Entity Finding
    // ========================================================================

    private WorldPoint findNearestObject(TaskContext ctx, int objectId) {
        if (navigationService != null) {
            WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
            Optional<com.rocinante.navigation.EntityFinder.ObjectSearchResult> result = 
                    navigationService.findNearestReachableObject(ctx, playerPos, Set.of(objectId), 100);
            return result.map(r -> TileObjectUtils.getWorldPoint(r.getObject())).orElse(null);
        }
        return findNearestObjectLegacy(ctx, objectId);
    }

    private WorldPoint findNearestObjectLegacy(TaskContext ctx, int objectId) {
        Client client = ctx.getClient();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
        if (playerPos == null) return null;

        int plane = playerPos.getPlane();
        WorldPoint nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (int x = 0; x < Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[plane][x][y];
                if (tile == null) continue;
                for (GameObject obj : tile.getGameObjects()) {
                    if (obj != null && obj.getId() == objectId) {
                        WorldPoint pos = obj.getWorldLocation();
                        int dist = playerPos.distanceTo(pos);
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearest = pos;
                        }
                    }
                }
            }
        }
        return nearest;
    }

    private WorldPoint findNearestNpc(TaskContext ctx, int npcId) {
        if (navigationService != null) {
            WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
            Optional<com.rocinante.navigation.EntityFinder.NpcSearchResult> result = 
                    navigationService.findNearestReachableNpc(ctx, playerPos, Set.of(npcId), 100);
            return result.map(r -> r.getNpc().getWorldLocation()).orElse(null);
        }

        // Fallback: simple distance-based search
        Client client = ctx.getClient();
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
        if (playerPos == null) return null;

        WorldPoint nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (NPC npc : client.getNpcs()) {
            if (npc != null && npc.getId() == npcId) {
                WorldPoint pos = npc.getWorldLocation();
                int dist = playerPos.distanceTo(pos);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = pos;
                }
            }
        }
        return nearest;
    }

    // ========================================================================
    // Description
    // ========================================================================

    private String getDestinationDescription() {
        if (targetObjectId > 0) return "object:" + targetObjectId;
        if (targetNpcId > 0) return "npc:" + targetNpcId;
        if (destination != null && destination.getX() != 0) {
            return String.format("(%d, %d, %d)", destination.getX(), destination.getY(), destination.getPlane());
        }
        return "unknown";
    }

    @Override
    public String getDescription() {
        return description != null ? description : "Walk to " + getDestinationDescription();
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    private enum WalkPhase {
        INIT,
        RESOLVE_DESTINATION,
        REQUEST_PATH,
        WAIT_FOR_PATH,
        WALKING,
        EXECUTE_TRANSPORT,
        ARRIVED
    }

    private static class MinimapInfo {
        final int centerX, centerY, radius;
        MinimapInfo(int centerX, int centerY, int radius) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.radius = radius;
        }
    }
}
