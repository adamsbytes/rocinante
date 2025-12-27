package com.rocinante.tasks.impl;

import com.rocinante.input.InventoryClickHelper;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.WorldPoint;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Task for using an inventory item on a game object.
 *
 * <p>This task performs a two-step interaction:
 * <ol>
 *   <li>Click the item in inventory (enters "Use" cursor mode)</li>
 *   <li>Click the target object in the world</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Use raw shrimp on fire
 * UseItemOnObjectTask cookShrimp = new UseItemOnObjectTask(ItemID.RAW_SHRIMP, ObjectID.FIRE);
 *
 * // Use tinderbox on logs
 * UseItemOnObjectTask lightLogs = new UseItemOnObjectTask(ItemID.TINDERBOX, ObjectID.LOGS)
 *     .withDescription("Light the logs");
 * }</pre>
 */
@Slf4j
public class UseItemOnObjectTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Default search radius for finding objects (tiles).
     */
    private static final int DEFAULT_SEARCH_RADIUS = 15;

    /**
     * Maximum ticks to wait for interaction response.
     */
    private static final int INTERACTION_TIMEOUT_TICKS = 10;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The item ID to use.
     */
    @Getter
    private final int itemId;

    /**
     * The object ID to use the item on.
     */
    @Getter
    private final int objectId;

    /**
     * Search radius for finding the object (tiles).
     */
    @Getter
    @Setter
    private int searchRadius = DEFAULT_SEARCH_RADIUS;

    /**
     * Custom description.
     */
    @Setter
    private String description;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private UseItemPhase phase = UseItemPhase.CLICK_ITEM;

    /**
     * The object we found and are interacting with.
     */
    private TileObject targetObject;

    /**
     * Player position when interaction started.
     */
    private WorldPoint startPosition;

    /**
     * Player animation when interaction started.
     */
    private int startAnimation;

    /**
     * Ticks since interaction started.
     */
    private int interactionTicks = 0;

    /**
     * Whether an async operation is pending.
     */
    private boolean operationPending = false;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a use item on object task.
     *
     * @param itemId   the item ID to use
     * @param objectId the object ID to use the item on
     */
    public UseItemOnObjectTask(int itemId, int objectId) {
        this.itemId = itemId;
        this.objectId = objectId;
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
    public UseItemOnObjectTask withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public UseItemOnObjectTask withDescription(String description) {
        this.description = description;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (!ctx.isLoggedIn()) {
            return false;
        }

        // Check if we have the item in inventory
        InventoryState inventory = ctx.getInventoryState();
        if (!inventory.hasItem(itemId)) {
            log.debug("Item {} not in inventory", itemId);
            return false;
        }

        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (operationPending) {
            return;
        }

        switch (phase) {
            case CLICK_ITEM:
                executeClickItem(ctx);
                break;
            case FIND_OBJECT:
                executeFindObject(ctx);
                break;
            case CLICK_OBJECT:
                executeClickObject(ctx);
                break;
            case WAIT_RESPONSE:
                executeWaitResponse(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Click Item
    // ========================================================================

    private void executeClickItem(TaskContext ctx) {
        InventoryClickHelper inventoryHelper = ctx.getInventoryClickHelper();
        if (inventoryHelper == null) {
            log.error("InventoryClickHelper not available in TaskContext");
            fail("InventoryClickHelper not available");
            return;
        }

        InventoryState inventory = ctx.getInventoryState();
        int slot = inventory.getSlotOf(itemId);

        if (slot < 0) {
            fail("Item " + itemId + " not found in inventory");
            return;
        }

        log.debug("Clicking item {} in slot {}", itemId, slot);
        operationPending = true;

        inventoryHelper.executeClick(slot, "Use item " + itemId)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        phase = UseItemPhase.FIND_OBJECT;
                    } else {
                        fail("Failed to click item in inventory");
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to click inventory item", e);
                    fail("Click failed: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Find Object
    // ========================================================================

    private void executeFindObject(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Search for object
        targetObject = findNearestObject(ctx, playerPos);

        if (targetObject == null) {
            log.warn("Object {} not found within {} tiles", objectId, searchRadius);
            fail("Object not found: " + objectId);
            return;
        }

        // Store starting state for success detection
        startPosition = playerPos;
        startAnimation = player.getAnimationId();

        log.debug("Found object {} at {}", objectId, getObjectWorldPoint(targetObject));
        phase = UseItemPhase.CLICK_OBJECT;
    }

    // ========================================================================
    // Phase: Click Object
    // ========================================================================

    private void executeClickObject(TaskContext ctx) {
        if (targetObject == null) {
            fail("Target object lost");
            return;
        }

        // Calculate click point on object
        Point clickPoint = calculateClickPoint(targetObject);
        if (clickPoint == null) {
            log.warn("Could not calculate click point for object {}", objectId);
            fail("Cannot determine click point");
            return;
        }

        log.debug("Clicking object {} at canvas point ({}, {})", objectId, clickPoint.x, clickPoint.y);

        operationPending = true;
        CompletableFuture<Void> moveFuture = ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y);

        moveFuture.thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    operationPending = false;
                    interactionTicks = 0;
                    phase = UseItemPhase.WAIT_RESPONSE;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to click object", e);
                    fail("Click failed: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait Response
    // ========================================================================

    private void executeWaitResponse(TaskContext ctx) {
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

        // Check for any animation
        if (player.isAnimating()) {
            success = true;
            successReason = "playing animation";
        }

        // Check for position change
        if (!success) {
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
            log.info("Use item on object successful: {} on {} ({})", itemId, objectId, successReason);
            complete();
            return;
        }

        log.trace("Waiting for interaction response (tick {})", interactionTicks);
    }

    // ========================================================================
    // Object Finding (similar to InteractObjectTask)
    // ========================================================================

    /**
     * Find the nearest instance of the target object.
     */
    private TileObject findNearestObject(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();

        int playerPlane = playerPos.getPlane();
        TileObject nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (int x = 0; x < Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[playerPlane][x][y];
                if (tile == null) {
                    continue;
                }

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
    private WorldPoint getObjectWorldPoint(TileObject obj) {
        if (obj instanceof GameObject) {
            return ((GameObject) obj).getWorldLocation();
        }
        if (obj instanceof WallObject) {
            return ((WallObject) obj).getWorldLocation();
        }
        if (obj instanceof GroundObject) {
            return ((GroundObject) obj).getWorldLocation();
        }
        if (obj instanceof DecorativeObject) {
            return ((DecorativeObject) obj).getWorldLocation();
        }
        return null;
    }

    /**
     * Calculate the screen point to click on the object.
     */
    private Point calculateClickPoint(TileObject obj) {
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

        // Calculate random point within the clickable area using Gaussian distribution
        int centerX = bounds.x + bounds.width / 2;
        int centerY = bounds.y + bounds.height / 2;

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
        return String.format("UseItemOnObject[item=%d, object=%d]", itemId, objectId);
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum UseItemPhase {
        CLICK_ITEM,
        FIND_OBJECT,
        CLICK_OBJECT,
        WAIT_RESPONSE
    }
}

