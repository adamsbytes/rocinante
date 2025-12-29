package com.rocinante.tasks.impl;

import com.rocinante.input.InventoryClickHelper;
import com.rocinante.input.SafeClickExecutor;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.InteractionHelper;
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
import net.runelite.api.ItemComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.WorldPoint;

import java.awt.Point;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
     * Acceptable item IDs to use.
     * Ordered by priority - first match wins.
     */
    @Getter
    private final List<Integer> itemIds;

    /**
     * Acceptable object IDs to use the item on.
     * Ordered by priority - first match wins.
     */
    @Getter
    private final List<Integer> objectIds;

    /**
     * The resolved item ID found in inventory.
     */
    private int resolvedItemId = -1;

    /**
     * The resolved object ID found in the world.
     */
    private int resolvedObjectId = -1;

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
     * Interaction helper for camera rotation and clickbox handling.
     */
    private InteractionHelper interactionHelper;

    /**
     * The object we found and are interacting with.
     */
    private TileObject targetObject;
    
    /**
     * Target object world position (cached for camera rotation).
     */
    private WorldPoint targetPosition;

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
    // Constructors
    // ========================================================================

    /**
     * Create a use item on object task with a single item.
     *
     * @param itemId   the item ID to use
     * @param objectId the object ID to use the item on
     */
    public UseItemOnObjectTask(int itemId, int objectId) {
        this.itemIds = Collections.singletonList(itemId);
        this.objectIds = Collections.singletonList(objectId);
        this.timeout = Duration.ofSeconds(30);
    }

    /**
     * Create a use item on object task accepting any of the provided items.
     * Items are checked in order - first match wins (for priority ordering).
     *
     * @param itemIds  acceptable item IDs to use, ordered by priority
     * @param objectId the object ID to use the item on
     */
    public UseItemOnObjectTask(Collection<Integer> itemIds, int objectId) {
        this.itemIds = new ArrayList<>(itemIds);
        this.objectIds = Collections.singletonList(objectId);
        this.timeout = Duration.ofSeconds(30);
    }

    /**
     * Create a use item on object task accepting any of the provided items on any of the provided objects.
     * Both items and objects are checked in order - first match wins (for priority ordering).
     *
     * @param itemIds   acceptable item IDs to use, ordered by priority
     * @param objectIds acceptable object IDs to use items on, ordered by priority
     */
    public UseItemOnObjectTask(Collection<Integer> itemIds, Collection<Integer> objectIds) {
        this.itemIds = new ArrayList<>(itemIds);
        this.objectIds = new ArrayList<>(objectIds);
        this.timeout = Duration.ofSeconds(30);
    }

    /**
     * Create a use item on object task with a single item and multiple possible objects.
     *
     * @param itemId    the item ID to use
     * @param objectIds acceptable object IDs to use the item on, ordered by priority
     */
    public UseItemOnObjectTask(int itemId, Collection<Integer> objectIds) {
        this.itemIds = Collections.singletonList(itemId);
        this.objectIds = new ArrayList<>(objectIds);
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

        // Find any matching item in inventory
        InventoryState inventory = ctx.getInventoryState();
        resolvedItemId = findFirstMatchingItem(inventory, itemIds);
        if (resolvedItemId == -1) {
            log.debug("No item from {} found in inventory. Inventory contains: {}",
                    itemIds, formatInventoryContents(inventory));
            return false;
        }

        log.debug("Resolved item {} from acceptable set {}", resolvedItemId, itemIds);
        return true;
    }

    @Override
    protected void resetImpl() {
        // Reset all execution state for retry
        phase = UseItemPhase.CLICK_ITEM;
        targetObject = null;
        targetPosition = null;
        startPosition = null;
        startAnimation = -1;
        interactionTicks = 0;
        operationPending = false;
        if (interactionHelper != null) {
            interactionHelper.reset();
        }
        log.debug("UseItemOnObjectTask reset for retry");
    }

    /**
     * Find the first item ID from the list that exists in inventory.
     * Returns in priority order (first in list = highest priority).
     */
    private int findFirstMatchingItem(InventoryState inventory, List<Integer> itemIds) {
        for (int itemId : itemIds) {
            if (inventory.hasItem(itemId)) {
                return itemId;
            }
        }
        return -1;
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
            case WAIT_FOR_CLICKBOX:
                executeWaitForClickbox(ctx);
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
        int slot = inventory.getSlotOf(resolvedItemId);

        if (slot < 0) {
            log.debug("Item {} no longer in inventory. Inventory contains: {}",
                    resolvedItemId, formatInventoryContents(inventory));
            fail("Item " + resolvedItemId + " not found in inventory");
            return;
        }

        log.debug("Clicking item {} in slot {}", resolvedItemId, slot);
        operationPending = true;

        inventoryHelper.executeClick(slot, "Use item " + resolvedItemId)
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
            log.warn("No object from {} found within {} tiles", objectIds, searchRadius);
            logNearbyObjects(ctx, playerPos);
            fail("Object not found: " + objectIds);
            return;
        }

        // Store starting state for success detection
        startPosition = playerPos;
        startAnimation = player.getAnimationId();
        targetPosition = getObjectWorldPoint(targetObject);
        
        // Initialize interaction helper
        interactionHelper = new InteractionHelper(ctx);
        // Start camera rotation immediately (fire-and-forget)
        interactionHelper.startCameraRotation(targetPosition);

        log.debug("Found object {} at {}", resolvedObjectId, targetPosition);
        phase = UseItemPhase.WAIT_FOR_CLICKBOX;
    }
    
    // ========================================================================
    // Phase: Wait for Clickbox
    // ========================================================================
    
    private void executeWaitForClickbox(TaskContext ctx) {
        if (targetObject == null) {
            fail("Target object lost");
            return;
        }
        
        InteractionHelper.ClickPointResult result = interactionHelper.getClickPointForObject(
                targetObject, targetPosition);
        
        if (result.hasPoint()) {
            log.debug("Clickbox ready for object {} ({})", resolvedObjectId, result.reason);
        phase = UseItemPhase.CLICK_OBJECT;
        } else if (result.shouldRotateCamera) {
            interactionHelper.startCameraRotation(targetPosition);
        } else if (result.shouldWait) {
            // Keep waiting
            return;
        } else {
            log.warn("Could not get clickbox for object {}: {}", resolvedObjectId, result.reason);
            fail("Cannot interact with object: " + result.reason);
        }
    }

    // ========================================================================
    // Phase: Click Object
    // ========================================================================

    private void executeClickObject(TaskContext ctx) {
        if (targetObject == null) {
            fail("Target object lost");
            return;
        }

        log.debug("Clicking object {} with item {}", resolvedObjectId, resolvedItemId);
        operationPending = true;

        // Get item name for menu matching (for "Use X on Y" actions)
        String itemName = getItemName(ctx, resolvedItemId);

        // Use SafeClickExecutor for overlap-aware clicking
        ctx.getSafeClickExecutor().clickObject(targetObject, "Use", itemName)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                    interactionTicks = 0;
                    phase = UseItemPhase.WAIT_RESPONSE;
                    } else {
                        fail("Click failed for object " + resolvedObjectId);
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Click failed", e);
                    fail("Click failed: " + e.getMessage());
                    return null;
                });
    }

    /**
     * Get item name from client definitions for menu matching.
     */
    private String getItemName(TaskContext ctx, int itemId) {
        try {
            ItemComposition def = ctx.getClient().getItemDefinition(itemId);
            return def != null ? def.getName() : null;
        } catch (Exception e) {
            log.trace("Could not get item name for {}", itemId);
            return null;
        }
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
            log.info("Use item on object successful: {} on {} ({})", resolvedItemId, resolvedObjectId, successReason);
            complete();
            return;
        }

        log.trace("Waiting for interaction response (tick {})", interactionTicks);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Format inventory contents for debug logging.
     */
    private String formatInventoryContents(InventoryState inventory) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (net.runelite.api.Item item : inventory.getNonEmptyItems()) {
            if (!first) sb.append(", ");
            sb.append(item.getId());
            if (item.getQuantity() > 1) {
                sb.append("x").append(item.getQuantity());
            }
            first = false;
        }
        sb.append("]");
        return sb.toString();
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
     * Checks objects against the objectIds list and sets resolvedObjectId when found.
     */
    private TileObject findObjectOnTile(Tile tile) {
        // Check game objects
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject obj : gameObjects) {
                if (obj != null && objectIds.contains(obj.getId())) {
                    resolvedObjectId = obj.getId();
                    return obj;
                }
            }
        }

        // Check wall object
        WallObject wallObject = tile.getWallObject();
        if (wallObject != null && objectIds.contains(wallObject.getId())) {
            resolvedObjectId = wallObject.getId();
            return wallObject;
        }

        // Check decorative object
        DecorativeObject decorativeObject = tile.getDecorativeObject();
        if (decorativeObject != null && objectIds.contains(decorativeObject.getId())) {
            resolvedObjectId = decorativeObject.getId();
            return decorativeObject;
        }

        // Check ground object
        GroundObject groundObject = tile.getGroundObject();
        if (groundObject != null && objectIds.contains(groundObject.getId())) {
            resolvedObjectId = groundObject.getId();
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

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        String itemStr = itemIds.size() == 1 ? String.valueOf(itemIds.get(0)) : itemIds.toString();
        String objStr = objectIds.size() == 1 ? String.valueOf(objectIds.get(0)) : objectIds.toString();
        return String.format("UseItemOnObject[item=%s, object=%s]", itemStr, objStr);
    }

    /**
     * Log all nearby objects for debugging when target object isn't found.
     */
    private void logNearbyObjects(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int playerPlane = playerPos.getPlane();

        StringBuilder sb = new StringBuilder("Nearby objects within " + searchRadius + " tiles: [");
        boolean first = true;

        for (int x = 0; x < Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[playerPlane][x][y];
                if (tile == null) continue;

                // Check game objects
                GameObject[] gameObjects = tile.getGameObjects();
                if (gameObjects != null) {
                    for (GameObject obj : gameObjects) {
                        if (obj != null) {
                            WorldPoint objPos = obj.getWorldLocation();
                            int distance = playerPos.distanceTo(objPos);
                            if (distance <= searchRadius) {
                                if (!first) sb.append(", ");
                                sb.append(obj.getId());
                                first = false;
                            }
                        }
                    }
                }

                // Check wall object
                WallObject wallObject = tile.getWallObject();
                if (wallObject != null) {
                    WorldPoint objPos = wallObject.getWorldLocation();
                    int distance = playerPos.distanceTo(objPos);
                    if (distance <= searchRadius) {
                        if (!first) sb.append(", ");
                        sb.append(wallObject.getId());
                        first = false;
                    }
                }

                // Check ground object
                GroundObject groundObject = tile.getGroundObject();
                if (groundObject != null) {
                    WorldPoint objPos = groundObject.getWorldLocation();
                    int distance = playerPos.distanceTo(objPos);
                    if (distance <= searchRadius) {
                        if (!first) sb.append(", ");
                        sb.append(groundObject.getId());
                        first = false;
                    }
                }

                // Check decorative object
                DecorativeObject decorativeObject = tile.getDecorativeObject();
                if (decorativeObject != null) {
                    WorldPoint objPos = decorativeObject.getWorldLocation();
                    int distance = playerPos.distanceTo(objPos);
                    if (distance <= searchRadius) {
                        if (!first) sb.append(", ");
                        sb.append(decorativeObject.getId());
                        first = false;
                    }
                }
            }
        }
        sb.append("]");
        log.debug(sb.toString());
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum UseItemPhase {
        CLICK_ITEM,
        FIND_OBJECT,
        WAIT_FOR_CLICKBOX,
        CLICK_OBJECT,
        WAIT_RESPONSE
    }
}

