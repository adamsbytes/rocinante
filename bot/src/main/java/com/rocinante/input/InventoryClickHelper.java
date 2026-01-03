package com.rocinante.input;

import com.rocinante.behavior.PlayerProfile;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Rectangle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

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
    private final ClientThread clientThread;
    private final RobotMouseController mouseController;
    
    /**
     * Player profile for per-player click position preferences.
     * Set via setter because profile is loaded after plugin startup.
     */
    @Setter
    @Nullable
    private PlayerProfile playerProfile;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public InventoryClickHelper(Client client, ClientThread clientThread, RobotMouseController mouseController) {
        this.client = client;
        this.clientThread = clientThread;
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

        // Get click coordinates on the client thread (widgets must be accessed on client thread)
        CompletableFuture<int[]> coordsFuture = new CompletableFuture<>();
        
        clientThread.invokeLater(() -> {
            try {
                // Get inventory widget - check normal inventory first, then equipment stats inventory
                Widget inventoryWidget = getVisibleInventoryWidget();
                if (inventoryWidget == null) {
                    log.warn("No inventory widget visible (checked normal inventory and equipment stats inventory)");
                    coordsFuture.complete(null);
                    return;
                }

                // Calculate slot position
                Rectangle slotBounds = getInventorySlotBounds(inventoryWidget, slot);
                if (slotBounds == null) {
                    log.warn("Could not get bounds for inventory slot {}", slot);
                    coordsFuture.complete(null);
                    return;
                }

                // Calculate click point with humanization + profile bias (per Section 3.1.2)
                int[] coords = calculateClickWithBias(slotBounds);
                int clickX = coords[0];
                int clickY = coords[1];

                if (description != null) {
                    log.debug("{} - clicking slot {} at ({}, {})", description, slot, clickX, clickY);
                } else {
                    log.debug("Clicking inventory slot {} at ({}, {})", slot, clickX, clickY);
                }

                coordsFuture.complete(coords);
            } catch (Exception e) {
                log.error("Error getting inventory slot coordinates: {}", e.getMessage(), e);
                coordsFuture.completeExceptionally(e);
            }
        });

        return coordsFuture.thenCompose(coords -> {
            if (coords == null) {
                return CompletableFuture.completedFuture(false);
            }
            
            int clickX = coords[0];
            int clickY = coords[1];
            
            return mouseController.moveToCanvas(clickX, clickY)
                    .thenCompose(v -> mouseController.click())
                    .thenApply(v -> {
                        if (description != null) {
                            log.debug("{} - click completed", description);
                        }
                        return true;
                    });
        }).exceptionally(e -> {
            log.error("Failed to click inventory slot {}: {}", slot, e.getMessage(), e);
            return false;
        });
    }

    /**
     * Hover over an inventory slot without clicking.
     * Used for pre-positioning the mouse for quick subsequent clicks.
     *
     * @param slot the inventory slot index (0-27)
     * @return CompletableFuture that completes with true if hover succeeded
     */
    public CompletableFuture<Boolean> hoverSlot(int slot) {
        // Validate slot
        if (slot < 0 || slot >= INVENTORY_SIZE) {
            log.warn("Invalid inventory slot for hover: {} (must be 0-27)", slot);
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<int[]> coordsFuture = new CompletableFuture<>();

        clientThread.invokeLater(() -> {
            try {
                Widget inventoryWidget = getVisibleInventoryWidget();
                if (inventoryWidget == null) {
                    log.warn("No inventory widget visible for hover");
                    coordsFuture.complete(null);
                    return;
                }

                Rectangle slotBounds = getInventorySlotBounds(inventoryWidget, slot);
                if (slotBounds == null) {
                    log.warn("Could not get bounds for hover slot {}", slot);
                    coordsFuture.complete(null);
                    return;
                }

                // Calculate hover point with humanization + profile bias
                int[] coords = calculateClickWithBias(slotBounds);

                coordsFuture.complete(coords);
            } catch (Exception e) {
                log.error("Error getting hover slot coordinates: {}", e.getMessage(), e);
                coordsFuture.completeExceptionally(e);
            }
        });

        return coordsFuture.thenCompose(coords -> {
            if (coords == null) {
                return CompletableFuture.completedFuture(false);
            }

            return mouseController.moveToCanvas(coords[0], coords[1])
                    .thenApply(v -> true);
        }).exceptionally(e -> {
            log.error("Failed to hover inventory slot {}: {}", slot, e.getMessage(), e);
            return false;
        });
    }

    /**
     * Click at the current mouse position without moving.
     * Used after pre-hovering to execute an instant click.
     *
     * @param description optional description for logging
     * @return CompletableFuture that completes with true if click succeeded
     */
    public CompletableFuture<Boolean> clickCurrentPosition(@Nullable String description) {
        if (description != null) {
            log.debug("{} - clicking at current position", description);
        }

        return mouseController.click()
                .thenApply(v -> {
                    if (description != null) {
                        log.debug("{} - click completed (no movement)", description);
                    }
                    return true;
                })
                .exceptionally(e -> {
                    log.error("Failed to click at current position: {}", e.getMessage(), e);
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
        Widget inventoryWidget = getVisibleInventoryWidget();
        if (inventoryWidget == null) {
            return null;
        }
        return getInventorySlotBounds(inventoryWidget, slot);
    }

    /**
     * Get the currently visible inventory widget.
     * Checks both the normal inventory tab and the equipment stats interface inventory.
     *
     * @return the visible inventory widget, or null if neither is visible
     */
    @Nullable
    private Widget getVisibleInventoryWidget() {
        // Check normal inventory first (most common case)
        Widget normalInventory = client.getWidget(WidgetInfo.INVENTORY);
        if (normalInventory != null && !normalInventory.isHidden()) {
            return normalInventory;
        }

        // Check equipment stats interface inventory (widget 387:0)
        // This is visible when the "View equipment stats" interface is open
        Widget equipmentInventory = client.getWidget(WidgetInfo.EQUIPMENT_INVENTORY_ITEMS_CONTAINER);
        if (equipmentInventory != null && !equipmentInventory.isHidden()) {
            log.debug("Using equipment stats inventory widget");
            return equipmentInventory;
        }

        // FALLBACK: When equipment stats interface is open, the inventory widget reports
        // as "hidden" because the inventory TAB isn't active, but the inventory IS rendered
        // as part of the equipment interface. Check if widget has valid bounds regardless
        // of hidden state - valid bounds indicate it's actually on screen.
        if (normalInventory != null) {
            Rectangle bounds = normalInventory.getBounds();
            if (bounds != null && bounds.x >= 0 && bounds.y >= 0 && bounds.width > 0 && bounds.height > 0) {
                log.debug("Using inventory widget with valid bounds despite hidden=true (equipment stats interface open)");
                return normalInventory;
            }
        }

        // Debug: log what we found when all checks fail
        logInventoryWidgetDebugInfo(normalInventory, equipmentInventory);
        return null;
    }

    /**
     * Log debug info about inventory widgets when lookup fails.
     * Only called when no visible inventory widget is found.
     */
    private void logInventoryWidgetDebugInfo(Widget normalInventory, Widget equipmentInventory) {
        if (!log.isDebugEnabled()) {
            return;
        }

        StringBuilder sb = new StringBuilder("Inventory widget lookup failed - diagnostic info:\n");
        
        // Normal inventory status
        sb.append("  Normal inventory (WidgetInfo.INVENTORY): ");
        if (normalInventory == null) {
            sb.append("NULL\n");
        } else {
            sb.append("exists, hidden=").append(normalInventory.isHidden())
              .append(", bounds=").append(normalInventory.getBounds()).append("\n");
        }
        
        // Equipment inventory status
        sb.append("  Equipment inventory (EQUIPMENT_INVENTORY_ITEMS_CONTAINER): ");
        if (equipmentInventory == null) {
            sb.append("NULL\n");
        } else {
            sb.append("exists, hidden=").append(equipmentInventory.isHidden())
              .append(", bounds=").append(equipmentInventory.getBounds()).append("\n");
        }
        
        // Check common interface groups that might be blocking
        sb.append("  Visible interface groups: ");
        StringBuilder visibleGroups = new StringBuilder();
        int[] commonGroups = {149, 387, 12, 15, 541, 218, 593, 231, 217, 219, 193, 233, 465, 300};
        for (int groupId : commonGroups) {
            Widget w = client.getWidget(groupId, 0);
            if (w != null && !w.isHidden()) {
                if (visibleGroups.length() > 0) visibleGroups.append(", ");
                visibleGroups.append(groupId);
            }
        }
        sb.append(visibleGroups.length() > 0 ? visibleGroups.toString() : "none").append("\n");
        
        // If equipment interface (387) is open, list its visible children
        Widget equipmentInterface = client.getWidget(387, 0);
        if (equipmentInterface != null && !equipmentInterface.isHidden()) {
            sb.append("  Equipment interface (387) children:\n");
            for (int childId = 0; childId < 30; childId++) {
                Widget child = client.getWidget(387, childId);
                if (child != null && !child.isHidden()) {
                    Rectangle bounds = child.getBounds();
                    sb.append("    387:").append(childId)
                      .append(" - visible, bounds=").append(bounds != null ? bounds.toString() : "null");
                    Widget[] dynamicChildren = child.getDynamicChildren();
                    if (dynamicChildren != null && dynamicChildren.length > 0) {
                        sb.append(", dynamicChildren=").append(dynamicChildren.length);
                    }
                    sb.append("\n");
                }
            }
        }
        
        log.debug(sb.toString());
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
        return ClickPointCalculator.calculateGaussianOffset(dimension);
    }

    /**
     * Generate a random offset with player profile bias applied.
     * The bias shifts the center of the Gaussian distribution toward the player's preference.
     * 
     * @param dimension the width or height of the clickable area
     * @param profileBias the bias from PlayerProfile (0.35-0.65, where 0.5 is center)
     * @return humanized offset with profile bias applied
     */
    int randomOffsetWithBias(int dimension, double profileBias) {
        // Base Gaussian offset centered at 45-55% (per REQUIREMENTS.md)
        double baseCenter = 0.45 + ThreadLocalRandom.current().nextDouble() * 0.10;  // 0.45-0.55
        
        // Apply profile bias: shift center toward player's habitual click position
        // profileBias ranges 0.35-0.65, where 0.5 is neutral
        // This creates a consistent micro-offset unique to each player
        double biasShift = (profileBias - 0.5) * 0.20;  // Max ±10% shift
        double adjustedCenter = baseCenter + biasShift;
        
        // Clamp to valid range (0.35-0.65 of dimension)
        adjustedCenter = Math.max(0.35, Math.min(0.65, adjustedCenter));
        
        // Generate Gaussian distributed point with σ = 15% of dimension
        double sigma = dimension * 0.15;
        double offset = adjustedCenter * dimension + ThreadLocalRandom.current().nextGaussian() * sigma;
        
        // Clamp to valid bounds
        return (int) Math.max(1, Math.min(dimension - 1, offset));
    }

    /**
     * Calculate click coordinates for an inventory slot with profile-based biases.
     * Uses the player's habitual click position preferences within each slot.
     * 
     * @param slotBounds the bounds of the inventory slot
     * @return click coordinates [x, y] with profile-based humanization
     */
    int[] calculateClickWithBias(Rectangle slotBounds) {
        int clickX, clickY;
        
        if (playerProfile != null) {
            // Apply player-specific click position biases
            clickX = slotBounds.x + randomOffsetWithBias(slotBounds.width, playerProfile.getInventoryClickColBias());
            clickY = slotBounds.y + randomOffsetWithBias(slotBounds.height, playerProfile.getInventoryClickRowBias());
        } else {
            // Fall back to standard randomization
            clickX = slotBounds.x + randomOffset(slotBounds.width);
            clickY = slotBounds.y + randomOffset(slotBounds.height);
        }
        
        return new int[]{clickX, clickY};
    }
}

