package com.rocinante.input;

import com.rocinante.timing.DelayProfile;
import com.rocinante.timing.HumanTimer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Helper for clicking ground items with humanized behavior.
 *
 * <p>Handles the complexity of stacked items:
 * <ul>
 *   <li>Left-click: When target item is on top of the stack (first in list)</li>
 *   <li>Right-click + menu select: When target item is buried under other items</li>
 * </ul>
 *
 * <p>Per REQUIREMENTS.md Section 3.1.2 Click Behavior:
 * <ul>
 *   <li>Position variance: 2D Gaussian distribution centered at 45-55% of hitbox, σ = 15%</li>
 *   <li>Never click the geometric center</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * groundItemClickHelper.clickGroundItem(worldPoint, itemId, "Bones")
 *     .thenAccept(success -> {
 *         if (success) {
 *             log.info("Ground item clicked successfully");
 *         }
 *     });
 * }</pre>
 */
@Slf4j
@Singleton
public class GroundItemClickHelper {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Size of the clickable area around a ground item (pixels).
     */
    private static final int GROUND_ITEM_CLICK_SIZE = 32;

    /**
     * Maximum time to wait for menu to appear (ms).
     */
    private static final long MENU_WAIT_TIMEOUT_MS = 2000;

    /**
     * Menu option widget group ID.
     */
    private static final int MENU_WIDGET_GROUP = 187;

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final Client client;
    private final RobotMouseController mouseController;
    private final HumanTimer humanTimer;
    private MouseCameraCoupler cameraCoupler;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public GroundItemClickHelper(Client client, RobotMouseController mouseController, HumanTimer humanTimer) {
        this.client = client;
        this.mouseController = mouseController;
        this.humanTimer = humanTimer;
        log.info("GroundItemClickHelper initialized");
    }
    
    /**
     * Set the camera coupler for rotating camera to see off-screen items.
     */
    public void setCameraCoupler(MouseCameraCoupler cameraCoupler) {
        this.cameraCoupler = cameraCoupler;
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Click a ground item. Automatically determines left vs right click based on stack order.
     * Will rotate camera if the item is not visible on screen.
     *
     * @param worldPosition tile where item is located
     * @param itemId        the item ID to pick up
     * @param itemName      for menu matching and logging
     * @return CompletableFuture that completes with true if click succeeded
     */
    public CompletableFuture<Boolean> clickGroundItem(WorldPoint worldPosition, int itemId, @Nullable String itemName) {
        // Validate input
        if (worldPosition == null) {
            log.warn("Cannot click ground item: worldPosition is null");
            return CompletableFuture.completedFuture(false);
        }

        String displayName = itemName != null ? itemName : "item " + itemId;

        // Get canvas position of the tile
        Point canvasPoint = getGroundItemCanvasPoint(worldPosition);
        if (canvasPoint == null) {
            // Item not visible - rotate camera and retry
            if (cameraCoupler != null) {
                log.debug("Ground item '{}' at {} not visible, rotating camera", displayName, worldPosition);
                return cameraCoupler.ensureTargetVisible(worldPosition)
                        .thenCompose(v -> {
                            // After rotation, try to get canvas point again
                            Point newCanvasPoint = getGroundItemCanvasPoint(worldPosition);
                            if (newCanvasPoint == null) {
                                log.warn("Ground item '{}' still not visible after camera rotation", displayName);
                                return CompletableFuture.completedFuture(false);
                            }
                            return executeClickAfterCameraRotation(worldPosition, itemId, displayName, newCanvasPoint);
                        });
            } else {
                log.warn("Cannot get canvas position for ground item at {} and no camera coupler available", worldPosition);
            return CompletableFuture.completedFuture(false);
            }
        }

        // Check if we need to right-click (item not on top)
        boolean needsRightClick = !isItemOnTop(worldPosition, itemId);

        log.debug("Clicking ground item '{}' at {} (rightClick={})", displayName, worldPosition, needsRightClick);

        if (needsRightClick) {
            return executeRightClickLoot(canvasPoint, itemId, displayName);
        } else {
            return executeLeftClickLoot(canvasPoint, displayName);
        }
    }
    
    /**
     * Execute click after camera rotation completed.
     */
    private CompletableFuture<Boolean> executeClickAfterCameraRotation(
            WorldPoint worldPosition, int itemId, String displayName, Point canvasPoint) {
        boolean needsRightClick = !isItemOnTop(worldPosition, itemId);
        log.debug("Clicking ground item '{}' after camera rotation (rightClick={})", displayName, needsRightClick);

        if (needsRightClick) {
            return executeRightClickLoot(canvasPoint, itemId, displayName);
        } else {
            return executeLeftClickLoot(canvasPoint, displayName);
        }
    }

    // ========================================================================
    // Click Execution
    // ========================================================================

    /**
     * Execute a left-click loot action (item is on top of stack).
     */
    private CompletableFuture<Boolean> executeLeftClickLoot(Point canvasPoint, String itemName) {
        // Calculate humanized click position
        int clickX = canvasPoint.getX() + randomOffset(GROUND_ITEM_CLICK_SIZE);
        int clickY = canvasPoint.getY() + randomOffset(GROUND_ITEM_CLICK_SIZE);

        log.trace("Left-click looting '{}' at canvas ({}, {})", itemName, clickX, clickY);

        return mouseController.moveToCanvas(clickX, clickY)
                .thenCompose(v -> mouseController.click())
                .thenApply(v -> {
                    log.debug("Left-click loot completed for '{}'", itemName);
                    return true;
                })
                .exceptionally(e -> {
                    log.error("Failed to left-click ground item '{}': {}", itemName, e.getMessage(), e);
                    return false;
                });
    }

    /**
     * Execute a right-click loot action (item is buried in stack).
     */
    private CompletableFuture<Boolean> executeRightClickLoot(Point canvasPoint, int itemId, String itemName) {
        // Calculate humanized click position
        int clickX = canvasPoint.getX() + randomOffset(GROUND_ITEM_CLICK_SIZE);
        int clickY = canvasPoint.getY() + randomOffset(GROUND_ITEM_CLICK_SIZE);

        log.trace("Right-click looting '{}' at canvas ({}, {})", itemName, clickX, clickY);

        // Create hitbox for right-click
        Rectangle hitbox = new Rectangle(
                clickX - GROUND_ITEM_CLICK_SIZE / 2,
                clickY - GROUND_ITEM_CLICK_SIZE / 2,
                GROUND_ITEM_CLICK_SIZE,
                GROUND_ITEM_CLICK_SIZE
        );

        return mouseController.moveToCanvas(clickX, clickY)
                .thenCompose(v -> mouseController.rightClick(hitbox))
                .thenCompose(v -> humanTimer.sleep(DelayProfile.MENU_SELECT))
                .thenCompose(v -> selectMenuOption(itemId, itemName))
                .exceptionally(e -> {
                    log.error("Failed to right-click ground item '{}': {}", itemName, e.getMessage(), e);
                    return false;
                });
    }

    /**
     * Find and click the "Take {itemName}" option in the context menu.
     */
    private CompletableFuture<Boolean> selectMenuOption(int itemId, String itemName) {
        // Try to find the menu option
        Rectangle menuEntryBounds = findMenuEntry(itemId, itemName);

        if (menuEntryBounds == null) {
            log.warn("Could not find menu entry for 'Take {}' - menu may have closed", itemName);
            return CompletableFuture.completedFuture(false);
        }

        // Click the menu entry
        int clickX = menuEntryBounds.x + randomOffset(menuEntryBounds.width);
        int clickY = menuEntryBounds.y + randomOffset(menuEntryBounds.height);

        log.trace("Clicking menu entry 'Take {}' at ({}, {})", itemName, clickX, clickY);

        return mouseController.moveToCanvas(clickX, clickY)
                .thenCompose(v -> mouseController.click())
                .thenApply(v -> {
                    log.debug("Menu selection completed for 'Take {}'", itemName);
                    return true;
                });
    }

    // ========================================================================
    // Menu Scanning
    // ========================================================================

    /**
     * Find the bounds of a menu entry for "Take {itemName}".
     *
     * @param itemId   the item ID
     * @param itemName the item name to search for
     * @return the bounds of the menu entry, or null if not found
     */
    @Nullable
    private Rectangle findMenuEntry(int itemId, String itemName) {
        // Try scanning menu widget group first
        Widget menuGroup = client.getWidget(MENU_WIDGET_GROUP, 0);
        if (menuGroup != null && !menuGroup.isHidden()) {
            Rectangle bounds = findMenuEntryInWidget(menuGroup, itemName);
            if (bounds != null) {
                return bounds;
            }
        }

        // Fall back to using client menu entries with calculated positions
        return findMenuEntryFromClientEntries(itemId, itemName);
    }

    /**
     * Find menu entry by scanning widget children.
     */
    @Nullable
    private Rectangle findMenuEntryInWidget(Widget menuWidget, String itemName) {
        Widget[] children = menuWidget.getDynamicChildren();
        if (children == null || children.length == 0) {
            children = menuWidget.getStaticChildren();
        }

        if (children == null) {
            return null;
        }

        String searchTarget = "Take " + itemName;
        String searchTargetLower = searchTarget.toLowerCase();

        for (Widget child : children) {
            if (child == null || child.isHidden()) {
                continue;
            }

            String text = child.getText();
            if (text != null && text.toLowerCase().contains(searchTargetLower)) {
                Rectangle bounds = child.getBounds();
                if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                    log.trace("Found menu entry '{}' with bounds {}", text, bounds);
                    return bounds;
                }
            }

            // Check nested children
            Widget[] nestedChildren = child.getDynamicChildren();
            if (nestedChildren != null) {
                for (Widget nested : nestedChildren) {
                    if (nested == null || nested.isHidden()) {
                        continue;
                    }
                    String nestedText = nested.getText();
                    if (nestedText != null && nestedText.toLowerCase().contains(searchTargetLower)) {
                        Rectangle bounds = nested.getBounds();
                        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                            log.trace("Found nested menu entry '{}' with bounds {}", nestedText, bounds);
                            return bounds;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Fall back to using client menu entries to estimate position.
     * This is less reliable but works when widgets aren't accessible.
     */
    @Nullable
    private Rectangle findMenuEntryFromClientEntries(int itemId, String itemName) {
        net.runelite.api.MenuEntry[] entries = client.getMenuEntries();
        if (entries == null || entries.length == 0) {
            return null;
        }

        String searchTarget = "Take";
        int menuX = client.getMenuX();
        int menuY = client.getMenuY();
        int menuWidth = client.getMenuWidth();
        int entryHeight = 15; // Approximate height per menu entry

        int index = 0;
        for (int i = entries.length - 1; i >= 0; i--) {
            net.runelite.api.MenuEntry entry = entries[i];
            if (entry == null) {
                continue;
            }

            String option = entry.getOption();
            String target = entry.getTarget();

            // Check if this is a "Take" option for our item
            if (option != null && option.contains(searchTarget)) {
                // Check by item name in target (target often contains item name with color codes)
                if (target != null && (target.contains(itemName) || entry.getIdentifier() == itemId)) {
                    // Calculate approximate bounds
                    int entryY = menuY + 19 + (index * entryHeight); // 19 = menu header
                    Rectangle bounds = new Rectangle(menuX, entryY, menuWidth, entryHeight);
                    log.trace("Estimated menu entry position for '{}': {}", itemName, bounds);
                    return bounds;
                }
            }
            index++;
        }

        return null;
    }

    // ========================================================================
    // Stack Detection
    // ========================================================================

    /**
     * Check if the target item is on top of the ground item stack.
     *
     * @param worldPosition the tile position
     * @param itemId        the item ID to check
     * @return true if item is first in the ground item list (on top)
     */
    private boolean isItemOnTop(WorldPoint worldPosition, int itemId) {
        Tile tile = getTileAt(worldPosition);
        if (tile == null) {
            // Can't determine, assume it's on top
            return true;
        }

        List<TileItem> groundItems = tile.getGroundItems();
        if (groundItems == null || groundItems.isEmpty()) {
            // No items, can't determine
            return true;
        }

        // First item in the list is on top
        TileItem topItem = groundItems.get(0);
        return topItem != null && topItem.getId() == itemId;
    }

    /**
     * Get the tile at the specified world position.
     */
    @Nullable
    private Tile getTileAt(WorldPoint worldPosition) {
        Scene scene = client.getScene();
        if (scene == null) {
            return null;
        }

        Tile[][][] tiles = scene.getTiles();
        if (tiles == null) {
            return null;
        }

        int plane = worldPosition.getPlane();
        int sceneX = worldPosition.getX() - client.getBaseX();
        int sceneY = worldPosition.getY() - client.getBaseY();

        if (sceneX < 0 || sceneX >= Constants.SCENE_SIZE ||
            sceneY < 0 || sceneY >= Constants.SCENE_SIZE ||
            plane < 0 || plane >= tiles.length) {
            return null;
        }

        return tiles[plane][sceneX][sceneY];
    }

    // ========================================================================
    // Canvas Position
    // ========================================================================

    /**
     * Get the canvas position for a ground item at the given world position.
     * Returns null if the item is not visible on screen.
     */
    @Nullable
    private Point getGroundItemCanvasPoint(WorldPoint worldPosition) {
        LocalPoint localPoint = LocalPoint.fromWorld(client, worldPosition);
        if (localPoint == null) {
            return null;
        }

        // Get canvas coordinates using Perspective
        // Height 0 is ground level for ground items
        Point canvasPoint = Perspective.localToCanvas(client, localPoint, worldPosition.getPlane(), 0);
        if (canvasPoint == null) {
            return null;
        }

        // Validate point is on screen - don't return off-screen coordinates
        java.awt.Canvas canvas = client.getCanvas();
        if (canvas == null) {
            return null;
        }
        
        int x = canvasPoint.getX();
        int y = canvasPoint.getY();
        int canvasWidth = canvas.getWidth();
        int canvasHeight = canvas.getHeight();
        
        if (x < 0 || x >= canvasWidth || y < 0 || y >= canvasHeight) {
            log.debug("Ground item at {} has off-screen canvas point ({}, {}), item not visible",
                    worldPosition, x, y);
            return null;
        }

        return canvasPoint;
    }

    // ========================================================================
    // Humanization (Section 3.1.2)
    // ========================================================================

    /**
     * Generate a random offset within a dimension for click position.
     * Per REQUIREMENTS.md Section 3.1.2:
     * - 2D Gaussian distribution centered at 45-55% of the hitbox
     * - σ = 15% of dimension
     * - Never click the geometric center
     *
     * @param dimension the width or height of the clickable area
     * @return humanized offset within the dimension
     */
    private int randomOffset(int dimension) {
        return ClickPointCalculator.calculateGaussianOffset(dimension);
    }
}

