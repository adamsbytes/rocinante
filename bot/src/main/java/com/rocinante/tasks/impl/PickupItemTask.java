package com.rocinante.tasks.impl;

import com.rocinante.input.SafeClickExecutor;
import com.rocinante.state.GroundItemSnapshot;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.InteractionHelper;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Optional;

/**
 * Task for picking up ground items.
 *
 * <p>Uses SafeClickExecutor with InteractionHelper for camera management:
 * <ul>
 *   <li>Automatic camera rotation when item is off-screen</li>
 *   <li>Safe click execution with overlap detection</li>
 *   <li>Humanized click behavior per REQUIREMENTS.md Section 3.1.2</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Pickup a specific item at a known location
 * PickupItemTask pickupEgg = new PickupItemTask(ItemID.EGG, "Egg")
 *     .withLocation(new WorldPoint(3177, 3296, 0));
 *
 * // Pickup nearest item of type
 * PickupItemTask pickupBones = new PickupItemTask(ItemID.BONES, "Bones")
 *     .withSearchRadius(10);
 * }</pre>
 */
@Slf4j
public class PickupItemTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Default search radius for finding ground items (tiles).
     */
    private static final int DEFAULT_SEARCH_RADIUS = 15;

    /**
     * Maximum ticks to wait for item to appear in inventory.
     */
    private static final int PICKUP_TIMEOUT_TICKS = 10;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The item ID to pick up.
     */
    @Getter
    private final int itemId;

    /**
     * The item name (for menu matching and logging).
     */
    @Getter
    private final String itemName;

    /**
     * Specific location of the item (optional - if null, searches nearby).
     */
    @Getter
    @Setter
    private WorldPoint location;

    /**
     * Search radius for finding the item (tiles).
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
    private PickupPhase phase = PickupPhase.FIND_ITEM;

    /**
     * Location of the item we're picking up.
     */
    private WorldPoint targetLocation;

    /**
     * Starting inventory count of the item.
     */
    private int startItemCount;

    /**
     * Ticks since pickup was initiated.
     */
    private int pickupTicks = 0;

    /**
     * Whether an async operation is pending.
     */
    private boolean operationPending = false;

    /**
     * Helper for camera rotation and visibility checks.
     */
    private InteractionHelper interactionHelper;

    /**
     * Whether camera rotation is in progress.
     */
    private boolean cameraRotationPending = false;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a pickup item task.
     *
     * @param itemId   the item ID to pick up
     * @param itemName the item name (for menu and logging)
     */
    public PickupItemTask(int itemId, String itemName) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.timeout = Duration.ofSeconds(30);
    }

    /**
     * Create a pickup item task with just item ID.
     *
     * @param itemId the item ID to pick up
     */
    public PickupItemTask(int itemId) {
        this(itemId, "item");
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set specific location of the item (builder-style).
     *
     * @param location the world position of the item
     * @return this task for chaining
     */
    public PickupItemTask withLocation(WorldPoint location) {
        this.location = location;
        return this;
    }

    /**
     * Set search radius (builder-style).
     *
     * @param radius search radius in tiles
     * @return this task for chaining
     */
    public PickupItemTask withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public PickupItemTask withDescription(String description) {
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

        // Check if inventory has space
        InventoryState inventory = ctx.getInventoryState();
        if (inventory.isFull()) {
            log.debug("Cannot pick up item: inventory is full");
            return false;
        }

        // SafeClickExecutor is required
        if (ctx.getSafeClickExecutor() == null) {
            log.error("SafeClickExecutor not available in TaskContext");
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
            case FIND_ITEM:
                executeFindItem(ctx);
                break;
            case CLICK_ITEM:
                executeClickItem(ctx);
                break;
            case WAIT_PICKUP:
                executeWaitPickup(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Find Item
    // ========================================================================

    private void executeFindItem(TaskContext ctx) {
        // If specific location provided, use it
        if (location != null) {
            targetLocation = location;
            log.debug("Using specified location for item '{}': {}", itemName, targetLocation);
        } else {
            // Search for nearest ground item
            WorldState worldState = ctx.getWorldState();
            PlayerState playerState = ctx.getPlayerState();
            WorldPoint playerPos = playerState.getWorldPosition();

            targetLocation = findNearestGroundItem(worldState, playerPos);

            if (targetLocation == null) {
                log.warn("Ground item '{}' (ID: {}) not found within {} tiles",
                        itemName, itemId, searchRadius);
                fail("Ground item not found: " + itemName);
                return;
            }

            log.debug("Found ground item '{}' at {}", itemName, targetLocation);
        }

        // Store starting inventory count
        startItemCount = ctx.getInventoryState().countItem(itemId);

        phase = PickupPhase.CLICK_ITEM;
    }

    // ========================================================================
    // Phase: Click Item
    // ========================================================================

    private void executeClickItem(TaskContext ctx) {
        if (operationPending || cameraRotationPending) {
            return;
        }

        // Initialize InteractionHelper on first use
        if (interactionHelper == null) {
            interactionHelper = new InteractionHelper(ctx);
        }

        SafeClickExecutor safeClick = ctx.getSafeClickExecutor();
        if (safeClick == null) {
            fail("SafeClickExecutor not available - required for ground item interaction");
            return;
        }

        // Get canvas position of the ground item
        net.runelite.api.Point canvasPoint = getGroundItemCanvasPoint(ctx, targetLocation);
        
        if (canvasPoint == null) {
            // Item not visible - rotate camera to see it
            log.debug("Ground item '{}' at {} not visible, rotating camera", itemName, targetLocation);
            cameraRotationPending = true;
            interactionHelper.startCameraRotation(targetLocation);
            
            // Check back next tick after rotation starts
            cameraRotationPending = false;
            return;
        }

        // Validate the point is actually on screen (not negative/off-canvas)
        java.awt.Canvas canvas = ctx.getClient().getCanvas();
        if (canvas != null) {
            int x = canvasPoint.getX();
            int y = canvasPoint.getY();
            if (x < 0 || x >= canvas.getWidth() || y < 0 || y >= canvas.getHeight()) {
                log.debug("Ground item canvas point ({}, {}) is off-screen, rotating camera", x, y);
                interactionHelper.startCameraRotation(targetLocation);
                return;
            }
        }

        log.debug("Clicking ground item '{}' at {} (canvas: {}, {})", 
                itemName, targetLocation, canvasPoint.getX(), canvasPoint.getY());
        operationPending = true;

            java.awt.Point awtPoint = new java.awt.Point(canvasPoint.getX(), canvasPoint.getY());
            safeClick.clickGroundItem(awtPoint, itemName)
                    .thenAccept(success -> {
                        operationPending = false;
                        if (success) {
                            pickupTicks = 0;
                            phase = PickupPhase.WAIT_PICKUP;
                        } else {
                            fail("Failed to click ground item: " + itemName);
                        }
                    })
                    .exceptionally(e -> {
                        operationPending = false;
                        log.error("Failed to click ground item", e);
                        fail("Click failed: " + e.getMessage());
                        return null;
                    });
    }

    /**
     * Get canvas point for a ground item at a world location.
     * Returns null if the item is not visible on screen.
     */
    @Nullable
    private net.runelite.api.Point getGroundItemCanvasPoint(TaskContext ctx, WorldPoint worldPos) {
        net.runelite.api.Client client = ctx.getClient();
        if (client == null || worldPos == null) {
            return null;
        }

        net.runelite.api.coords.LocalPoint localPoint = net.runelite.api.coords.LocalPoint.fromWorld(client, worldPos);
        if (localPoint == null) {
            return null;
        }

        net.runelite.api.Point canvasPoint = net.runelite.api.Perspective.localToCanvas(client, localPoint, client.getPlane(), 0);
        if (canvasPoint == null) {
            return null;
        }
        
        // Validate point is on screen
        java.awt.Canvas canvas = client.getCanvas();
        if (canvas == null) {
            return null;
        }
        
        int x = canvasPoint.getX();
        int y = canvasPoint.getY();
        if (x < 0 || x >= canvas.getWidth() || y < 0 || y >= canvas.getHeight()) {
            log.debug("Ground item at {} has off-screen canvas point ({}, {})", worldPos, x, y);
            return null;
        }
        
        return canvasPoint;
    }

    // ========================================================================
    // Phase: Wait Pickup
    // ========================================================================

    private void executeWaitPickup(TaskContext ctx) {
        pickupTicks++;

        if (pickupTicks > PICKUP_TIMEOUT_TICKS) {
            log.warn("Pickup timed out after {} ticks", PICKUP_TIMEOUT_TICKS);
            fail("Pickup timeout - item may have been taken by another player");
            return;
        }

        InventoryState inventory = ctx.getInventoryState();
        PlayerState player = ctx.getPlayerState();

        // Check for success indicators
        boolean success = false;
        String successReason = null;

        // Check if item count increased
        int currentCount = inventory.countItem(itemId);
        if (currentCount > startItemCount) {
            success = true;
            successReason = "item in inventory";
        }

        // Check for pickup animation (bending down)
        if (!success && player.isAnimating()) {
            // Wait for animation to complete - don't mark success yet
            log.debug("Player animating, waiting for pickup to complete");
            return;
        }

        // Check if player moved toward item (walking to pick up)
        if (!success && player.isMoving()) {
            log.debug("Player moving toward item, waiting...");
            return;
        }

        if (success) {
            log.info("Ground item pickup successful: {} ({})", itemName, successReason);
            complete();
            return;
        }

        log.debug("Waiting for pickup response (tick {})", pickupTicks);
    }

    // ========================================================================
    // Ground Item Search
    // ========================================================================

    /**
     * Find the nearest ground item matching our item ID.
     *
     * @param worldState the world state containing ground items
     * @param playerPos  the player's position
     * @return the location of the nearest matching item, or null if not found
     */
    private WorldPoint findNearestGroundItem(WorldState worldState, WorldPoint playerPos) {
        // Use WorldState's built-in method to find nearest item by ID
        Optional<GroundItemSnapshot> nearestItem = worldState.getNearestGroundItemById(itemId, playerPos);
        
        if (nearestItem.isPresent()) {
            GroundItemSnapshot item = nearestItem.get();
            int distance = item.distanceTo(playerPos);
            
            if (distance <= searchRadius) {
                return item.getWorldPosition();
            } else {
                log.debug("Nearest ground item '{}' is {} tiles away (max: {})", 
                        itemName, distance, searchRadius);
            }
        }
        
        return null;
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("PickupItem[%s (%d)]", itemName, itemId);
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum PickupPhase {
        FIND_ITEM,
        CLICK_ITEM,
        WAIT_PICKUP
    }
}

