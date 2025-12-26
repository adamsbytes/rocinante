package com.rocinante.tasks.impl;

import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.timing.DelayProfile;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ObjectID;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Task for interacting with game objects.
 *
 * Per REQUIREMENTS.md Section 5.4.1:
 * <ul>
 *   <li>Locate object by ID within configurable radius (default 15 tiles)</li>
 *   <li>Camera rotation toward object (configurable probability)</li>
 *   <li>Hover briefly before clicking (100-400ms)</li>
 *   <li>Wait for player animation/position change as success indicator</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Interact with a bank booth
 * InteractObjectTask bankTask = new InteractObjectTask(BANK_BOOTH_ID, "Bank");
 *
 * // Interact with a tree for woodcutting
 * InteractObjectTask chopTask = new InteractObjectTask(TREE_ID, "Chop down")
 *     .withSearchRadius(10)
 *     .withSuccessAnimation(WOODCUTTING_ANIM);
 *
 * // Interact with a door
 * InteractObjectTask doorTask = new InteractObjectTask(DOOR_ID, "Open")
 *     .withCameraRotationChance(0.8);
 * }</pre>
 */
@Slf4j
public class InteractObjectTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Default search radius for finding objects (tiles).
     */
    private static final int DEFAULT_SEARCH_RADIUS = 15;

    /**
     * Default camera rotation probability.
     */
    private static final double DEFAULT_CAMERA_ROTATION_CHANCE = 0.3;

    /**
     * Hover delay range before clicking (ms).
     */
    private static final int HOVER_DELAY_MIN = 100;
    private static final int HOVER_DELAY_MAX = 400;

    /**
     * Maximum ticks to wait for interaction response.
     */
    private static final int INTERACTION_TIMEOUT_TICKS = 10;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The object ID to interact with.
     */
    @Getter
    private final int objectId;

    /**
     * The menu action to use (e.g., "Bank", "Chop down", "Open").
     */
    @Getter
    private final String menuAction;

    /**
     * Search radius for finding the object (tiles).
     */
    @Getter
    @Setter
    private int searchRadius = DEFAULT_SEARCH_RADIUS;

    /**
     * Probability of rotating camera toward object (0.0 - 1.0).
     */
    @Getter
    @Setter
    private double cameraRotationChance = DEFAULT_CAMERA_ROTATION_CHANCE;

    /**
     * Expected animation ID after successful interaction (optional).
     * Used to verify interaction was successful.
     */
    @Getter
    @Setter
    private int successAnimationId = -1;

    /**
     * Whether to wait for the player to become idle after interaction.
     */
    @Getter
    @Setter
    private boolean waitForIdle = true;

    /**
     * Whether to accept position change as success indicator.
     */
    @Getter
    @Setter
    private boolean acceptPositionChange = true;

    /**
     * Custom description.
     */
    @Getter
    @Setter
    private String description;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    protected InteractionPhase phase = InteractionPhase.FIND_OBJECT;

    /**
     * The object we found and are interacting with.
     */
    protected TileObject targetObject;

    /**
     * World position of the target object.
     */
    protected WorldPoint targetPosition;

    /**
     * Player position when interaction started.
     */
    protected WorldPoint startPosition;

    /**
     * Player animation when interaction started.
     */
    protected int startAnimation;

    /**
     * Ticks since interaction started.
     */
    protected int interactionTicks = 0;

    /**
     * Whether mouse movement is currently pending (async).
     */
    protected boolean movePending = false;

    /**
     * Whether click is currently pending (async).
     */
    protected boolean clickPending = false;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create an interact object task.
     *
     * @param objectId   the game object ID
     * @param menuAction the menu action text
     */
    public InteractObjectTask(int objectId, String menuAction) {
        this.objectId = objectId;
        this.menuAction = menuAction;
        this.timeout = Duration.ofSeconds(30);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set search radius (builder-style).
     *
     * @param radius search radius in tiles
     * @return this task for chaining
     */
    public InteractObjectTask withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Set camera rotation chance (builder-style).
     *
     * @param chance probability (0.0 - 1.0)
     * @return this task for chaining
     */
    public InteractObjectTask withCameraRotationChance(double chance) {
        this.cameraRotationChance = chance;
        return this;
    }

    /**
     * Set expected success animation (builder-style).
     *
     * @param animationId the expected animation ID
     * @return this task for chaining
     */
    public InteractObjectTask withSuccessAnimation(int animationId) {
        this.successAnimationId = animationId;
        return this;
    }

    /**
     * Set whether to wait for idle (builder-style).
     *
     * @param wait true to wait for idle
     * @return this task for chaining
     */
    public InteractObjectTask withWaitForIdle(boolean wait) {
        this.waitForIdle = wait;
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public InteractObjectTask withDescription(String description) {
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
    protected void executeImpl(TaskContext ctx) {
        switch (phase) {
            case FIND_OBJECT:
                executeFindObject(ctx);
                break;
            case ROTATE_CAMERA:
                executeRotateCamera(ctx);
                break;
            case MOVE_MOUSE:
                executeMoveMouse(ctx);
                break;
            case HOVER_DELAY:
                executeHoverDelay(ctx);
                break;
            case CLICK:
                executeClick(ctx);
                break;
            case WAIT_RESPONSE:
                executeWaitResponse(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Find Object
    // ========================================================================

    protected void executeFindObject(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Search for object
        targetObject = findNearestObject(ctx, playerPos);

        if (targetObject == null) {
            log.warn("Object {} not found within {} tiles", objectId, searchRadius);
            fail("Object not found: " + objectId);
            return;
        }

        // Store object position
        targetPosition = getObjectWorldPoint(targetObject);
        log.debug("Found object {} at {}", objectId, targetPosition);

        // Store starting state for success detection
        startPosition = playerPos;
        startAnimation = player.getAnimationId();

        // Decide if we should rotate camera
        if (cameraRotationChance > 0 && Math.random() < cameraRotationChance) {
            phase = InteractionPhase.ROTATE_CAMERA;
        } else {
            phase = InteractionPhase.MOVE_MOUSE;
        }
    }

    // ========================================================================
    // Phase: Rotate Camera
    // ========================================================================

    protected void executeRotateCamera(TaskContext ctx) {
        // STUB: Camera rotation will be implemented with input system
        // For now, skip to mouse movement
        log.debug("STUB: Would rotate camera toward object at {}", targetPosition);
        phase = InteractionPhase.MOVE_MOUSE;
    }

    // ========================================================================
    // Phase: Move Mouse
    // ========================================================================

    protected void executeMoveMouse(TaskContext ctx) {
        if (movePending) {
            return; // Still moving
        }

        if (targetObject == null) {
            fail("Target object lost");
            return;
        }

        // Calculate click point on object
        Point clickPoint = calculateClickPoint(ctx, targetObject);
        if (clickPoint == null) {
            log.warn("Could not calculate click point for object {}", objectId);
            fail("Cannot determine click point");
            return;
        }

        log.debug("Moving mouse to object at screen point ({}, {})", clickPoint.x, clickPoint.y);

        // Start async mouse movement
        movePending = true;
        CompletableFuture<Void> moveFuture = ctx.getMouseController().moveTo(clickPoint.x, clickPoint.y);

        moveFuture.thenRun(() -> {
            movePending = false;
            phase = InteractionPhase.HOVER_DELAY;
        }).exceptionally(e -> {
            movePending = false;
            log.error("Mouse movement failed", e);
            fail("Mouse movement failed: " + e.getMessage());
            return null;
        });
    }

    // ========================================================================
    // Phase: Hover Delay
    // ========================================================================

    protected void executeHoverDelay(TaskContext ctx) {
        // Generate humanized hover delay
        int hoverMs = HOVER_DELAY_MIN + (int) (Math.random() * (HOVER_DELAY_MAX - HOVER_DELAY_MIN));
        log.debug("Hovering for {}ms before click", hoverMs);

        // Schedule transition to click phase
        // For now, just proceed (actual delay is in mouse controller)
        phase = InteractionPhase.CLICK;
    }

    // ========================================================================
    // Phase: Click
    // ========================================================================

    protected void executeClick(TaskContext ctx) {
        if (clickPending) {
            return; // Click in progress
        }

        log.debug("Clicking object {} with action '{}'", objectId, menuAction);

        // Start async click
        clickPending = true;

        // Use humanized click via mouse controller
        CompletableFuture<Void> clickFuture = ctx.getMouseController().click();

        clickFuture.thenRun(() -> {
            clickPending = false;
            interactionTicks = 0;
            phase = InteractionPhase.WAIT_RESPONSE;
        }).exceptionally(e -> {
            clickPending = false;
            log.error("Click failed", e);
            fail("Click failed: " + e.getMessage());
            return null;
        });
    }

    // ========================================================================
    // Phase: Wait Response
    // ========================================================================

    protected void executeWaitResponse(TaskContext ctx) {
        interactionTicks++;

        if (interactionTicks > INTERACTION_TIMEOUT_TICKS) {
            log.warn("Interaction timed out after {} ticks", INTERACTION_TIMEOUT_TICKS);
            fail("Interaction timeout - no response from game");
            return;
        }

        PlayerState player = ctx.getPlayerState();

        // Check for success indicators
        boolean success = false;
        String successReason = null;

        // Check for expected animation
        if (successAnimationId > 0 && player.isAnimating(successAnimationId)) {
            success = true;
            successReason = "playing expected animation";
        }

        // Check for any animation (if no specific animation expected)
        if (!success && successAnimationId < 0 && player.isAnimating()) {
            success = true;
            successReason = "playing animation";
        }

        // Check for position change
        if (!success && acceptPositionChange) {
            WorldPoint currentPos = player.getWorldPosition();
            if (currentPos != null && !currentPos.equals(startPosition)) {
                success = true;
                successReason = "position changed";
            }
        }

        // Check for interaction target change
        if (!success && player.isInteracting()) {
            success = true;
            successReason = "interacting with entity";
        }

        if (success) {
            log.info("Object interaction successful: {} ({})", objectId, successReason);
            complete();
            return;
        }

        // Not yet successful, continue waiting
        log.trace("Waiting for interaction response (tick {})", interactionTicks);
    }

    // ========================================================================
    // Object Finding
    // ========================================================================

    /**
     * Find the nearest instance of the target object.
     */
    protected TileObject findNearestObject(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();

        int playerPlane = playerPos.getPlane();
        TileObject nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        // Search tiles within radius
        for (int x = 0; x < Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[playerPlane][x][y];
                if (tile == null) {
                    continue;
                }

                // Check all object types on this tile
                TileObject obj = findObjectOnTile(tile);
                if (obj != null) {
                    WorldPoint objPos = getObjectWorldPoint(obj);
                    int distance = playerPos.distanceTo(objPos);

                    if (distance <= searchRadius && distance < nearestDistance) {
                        nearest = obj;
                        nearestDistance = distance;
                    }
                }
            }
        }

        return nearest;
    }

    /**
     * Find the target object on a specific tile.
     */
    private TileObject findObjectOnTile(Tile tile) {
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

        // Check decorative object
        DecorativeObject decorativeObject = tile.getDecorativeObject();
        if (decorativeObject != null && decorativeObject.getId() == objectId) {
            return decorativeObject;
        }

        // Check ground object
        GroundObject groundObject = tile.getGroundObject();
        if (groundObject != null && groundObject.getId() == objectId) {
            return groundObject;
        }

        return null;
    }

    /**
     * Get the WorldPoint of a TileObject.
     */
    protected WorldPoint getObjectWorldPoint(TileObject obj) {
        if (obj instanceof GameObject) {
            return ((GameObject) obj).getWorldLocation();
        }
        // For other TileObject types (WallObject, GroundObject, DecorativeObject),
        // we use the tile's world location since they don't have getWorldLocation()
        if (obj instanceof WallObject) {
            return ((WallObject) obj).getWorldLocation();
        }
        if (obj instanceof GroundObject) {
            return ((GroundObject) obj).getWorldLocation();
        }
        if (obj instanceof DecorativeObject) {
            return ((DecorativeObject) obj).getWorldLocation();
        }
        // Fallback - shouldn't happen but just in case
        return null;
    }

    // ========================================================================
    // Click Point Calculation
    // ========================================================================

    /**
     * Calculate the screen point to click on the object.
     */
    protected Point calculateClickPoint(TaskContext ctx, TileObject obj) {
        // Get object convex hull or bounding box
        Shape clickableArea = obj.getClickbox();

        if (clickableArea == null) {
            log.warn("Object has no clickable area");
            return null;
        }

        Rectangle bounds = clickableArea.getBounds();
        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            log.warn("Object has invalid bounds");
            return null;
        }

        // Calculate random point within the clickable area
        // Use Gaussian distribution centered on the object
        int centerX = bounds.x + bounds.width / 2;
        int centerY = bounds.y + bounds.height / 2;

        // Add some variance using Gaussian distribution
        double[] offset = Randomization.staticGaussian2D(0, 0, bounds.width / 4.0, bounds.height / 4.0);
        int clickX = centerX + (int) offset[0];
        int clickY = centerY + (int) offset[1];

        // Clamp to bounds
        clickX = Math.max(bounds.x, Math.min(clickX, bounds.x + bounds.width));
        clickY = Math.max(bounds.y, Math.min(clickY, bounds.y + bounds.height));

        return new Point(clickX, clickY);
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("InteractObject[%d, '%s']", objectId, menuAction);
    }

    // ========================================================================
    // Execution Phase Enum
    // ========================================================================

    /**
     * Phases of object interaction.
     */
    protected enum InteractionPhase {
        FIND_OBJECT,
        ROTATE_CAMERA,
        MOVE_MOUSE,
        HOVER_DELAY,
        CLICK,
        WAIT_RESPONSE
    }
}

