package com.rocinante.input;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Rectangle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Helper for clicking inventory slots with humanized behavior.
 *
 * Per REQUIREMENTS.md Section 3.1.2 Click Behavior:
 * <ul>
 *   <li>Position variance: 2D Gaussian distribution centered at 45-55% of hitbox, σ = 15%</li>
 *   <li>Never click the geometric center</li>
 * </ul>
 *
 * <p>This class provides a centralized, DRY implementation for inventory slot clicking
 * used by combat systems (eating, drinking potions) and gear switching.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Click food at slot 5
 * inventoryClickHelper.executeClick(5, "Eating shark")
 *     .thenAccept(success -> {
 *         if (success) {
 *             log.info("Food clicked successfully");
 *         }
 *     });
 * }</pre>
 */
@Slf4j
@Singleton
public class InventoryClickHelper {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Total number of inventory slots.
     */
    public static final int INVENTORY_SIZE = 28;

    /**
     * Number of inventory columns.
     */
    private static final int INVENTORY_COLUMNS = 4;

    /**
     * Number of inventory rows.
     */
    private static final int INVENTORY_ROWS = 7;

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final Client client;
    private final RobotMouseController mouseController;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public InventoryClickHelper(Client client, RobotMouseController mouseController) {
        this.client = client;
        this.mouseController = mouseController;
        log.info("InventoryClickHelper initialized");
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Click an inventory slot.
     *
     * @param slot the inventory slot index (0-27)
     * @return CompletableFuture that completes with true if click succeeded
     */
    public CompletableFuture<Boolean> executeClick(int slot) {
        return executeClick(slot, null);
    }

    /**
     * Click an inventory slot with a description for logging.
     *
     * @param slot        the inventory slot index (0-27)
     * @param description optional description for logging (e.g., "Eating shark")
     * @return CompletableFuture that completes with true if click succeeded
     */
    public CompletableFuture<Boolean> executeClick(int slot, @Nullable String description) {
        // Validate slot
        if (slot < 0 || slot >= INVENTORY_SIZE) {
            log.warn("Invalid inventory slot: {} (must be 0-27)", slot);
            return CompletableFuture.completedFuture(false);
        }

        // Get inventory widget
        Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);
        if (inventoryWidget == null || inventoryWidget.isHidden()) {
            log.warn("Inventory widget not visible");
            return CompletableFuture.completedFuture(false);
        }

        // Calculate slot position
        Rectangle slotBounds = getInventorySlotBounds(inventoryWidget, slot);
        if (slotBounds == null) {
            log.warn("Could not get bounds for inventory slot {}", slot);
            return CompletableFuture.completedFuture(false);
        }

        // Calculate click point with humanization (per Section 3.1.2)
        int clickX = slotBounds.x + randomOffset(slotBounds.width);
        int clickY = slotBounds.y + randomOffset(slotBounds.height);

        if (description != null) {
            log.debug("{} - clicking slot {} at ({}, {})", description, slot, clickX, clickY);
        } else {
            log.trace("Clicking inventory slot {} at ({}, {})", slot, clickX, clickY);
        }

        return mouseController.moveToCanvas(clickX, clickY)
                .thenCompose(v -> mouseController.click())
                .thenApply(v -> {
                    if (description != null) {
                        log.debug("{} - click completed", description);
                    }
                    return true;
                })
                .exceptionally(e -> {
                    log.error("Failed to click inventory slot {}: {}", slot, e.getMessage(), e);
                    return false;
                });
    }

    // ========================================================================
    // Slot Bounds Calculation
    // ========================================================================

    /**
     * Get the bounds of an inventory slot.
     *
     * @param inventoryWidget the inventory widget
     * @param slot            the slot index (0-27)
     * @return the slot bounds, or null if unavailable
     */
    @Nullable
    public Rectangle getInventorySlotBounds(Widget inventoryWidget, int slot) {
        if (slot < 0 || slot >= INVENTORY_SIZE) {
            return null;
        }

        Widget[] children = inventoryWidget.getDynamicChildren();
        if (children != null && slot < children.length) {
            Widget slotWidget = children[slot];
            if (slotWidget != null) {
                return slotWidget.getBounds();
            }
        }

        // Fall back to manual calculation
        Rectangle parentBounds = inventoryWidget.getBounds();
        if (parentBounds == null) {
            return null;
        }

        int col = slot % INVENTORY_COLUMNS;
        int row = slot / INVENTORY_COLUMNS;
        int slotWidth = parentBounds.width / INVENTORY_COLUMNS;
        int slotHeight = parentBounds.height / INVENTORY_ROWS;

        return new Rectangle(
                parentBounds.x + col * slotWidth,
                parentBounds.y + row * slotHeight,
                slotWidth,
                slotHeight
        );
    }

    /**
     * Get the bounds of an inventory slot by looking up the widget.
     *
     * @param slot the slot index (0-27)
     * @return the slot bounds, or null if unavailable
     */
    @Nullable
    public Rectangle getInventorySlotBounds(int slot) {
        Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);
        if (inventoryWidget == null || inventoryWidget.isHidden()) {
            return null;
        }
        return getInventorySlotBounds(inventoryWidget, slot);
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
    int randomOffset(int dimension) {
        // Center at 45-55% of dimension (per Section 3.1.2)
        double centerPercent = 0.45 + ThreadLocalRandom.current().nextDouble() * 0.10;
        double center = dimension * centerPercent;

        // Standard deviation = 15% of dimension
        double stdDev = dimension * 0.15;

        // Gaussian offset
        double offset = center + ThreadLocalRandom.current().nextGaussian() * stdDev;

        // Clamp to valid range (minimum 2px from edge)
        return (int) Math.max(2, Math.min(dimension - 2, offset));
    }
}

