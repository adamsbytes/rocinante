package com.rocinante.input;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Rectangle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Helper for clicking UI widgets with humanized behavior.
 *
 * Per REQUIREMENTS.md Section 3.1.2 Click Behavior:
 * <ul>
 *   <li>Position variance: 2D Gaussian distribution centered at 45-55% of hitbox, σ = 15%</li>
 *   <li>Never click the geometric center</li>
 * </ul>
 *
 * <p>This class provides a centralized, DRY implementation for widget clicking
 * used by combat systems (prayer switching, special attack, logout).
 *
 * <p>Example usage:
 * <pre>{@code
 * // Click prayer widget
 * widgetClickHelper.clickWidget(541, 17, "Protect from Magic")
 *     .thenAccept(success -> {
 *         if (success) {
 *             log.info("Prayer toggled successfully");
 *         }
 *     });
 * }</pre>
 */
@Slf4j
@Singleton
public class WidgetClickHelper {

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final Client client;
    private final RobotMouseController mouseController;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public WidgetClickHelper(Client client, RobotMouseController mouseController) {
        this.client = client;
        this.mouseController = mouseController;
        log.info("WidgetClickHelper initialized");
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Click a widget by group and child ID.
     *
     * @param groupId the widget group ID
     * @param childId the widget child ID
     * @return CompletableFuture that completes with true if click succeeded
     */
    public CompletableFuture<Boolean> clickWidget(int groupId, int childId) {
        return clickWidget(groupId, childId, null);
    }

    /**
     * Click a widget by group and child ID with a description for logging.
     *
     * @param groupId     the widget group ID
     * @param childId     the widget child ID
     * @param description optional description for logging
     * @return CompletableFuture that completes with true if click succeeded
     */
    public CompletableFuture<Boolean> clickWidget(int groupId, int childId, @Nullable String description) {
        // Get the widget
        Widget widget = client.getWidget(groupId, childId);
        if (widget == null || widget.isHidden()) {
            log.warn("Widget {}:{} not found or hidden{}", groupId, childId,
                    description != null ? " (" + description + ")" : "");
            return CompletableFuture.completedFuture(false);
        }

        return clickWidget(widget, description);
    }

    /**
     * Click a widget directly.
     *
     * @param widget      the widget to click
     * @param description optional description for logging
     * @return CompletableFuture that completes with true if click succeeded
     */
    public CompletableFuture<Boolean> clickWidget(Widget widget, @Nullable String description) {
        if (widget == null || widget.isHidden()) {
            log.warn("Widget is null or hidden{}", description != null ? " (" + description + ")" : "");
            return CompletableFuture.completedFuture(false);
        }

        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            log.warn("Widget has invalid bounds{}", description != null ? " (" + description + ")" : "");
            return CompletableFuture.completedFuture(false);
        }

        // Calculate humanized click position (per Section 3.1.2)
        int clickX = bounds.x + randomOffset(bounds.width);
        int clickY = bounds.y + randomOffset(bounds.height);

        if (description != null) {
            log.debug("{} - clicking widget at ({}, {})", description, clickX, clickY);
        } else {
            log.trace("Clicking widget at ({}, {})", clickX, clickY);
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
                    log.error("Failed to click widget{}: {}",
                            description != null ? " (" + description + ")" : "",
                            e.getMessage(), e);
                    return false;
                });
    }

    /**
     * Click a widget by packed widget ID (group << 16 | child).
     *
     * @param packedId    the packed widget ID
     * @param description optional description for logging
     * @return CompletableFuture that completes with true if click succeeded
     */
    public CompletableFuture<Boolean> clickPackedWidget(int packedId, @Nullable String description) {
        int groupId = packedId >> 16;
        int childId = packedId & 0xFFFF;
        return clickWidget(groupId, childId, description);
    }

    /**
     * Check if a widget is visible.
     *
     * @param groupId the widget group ID
     * @param childId the widget child ID
     * @return true if the widget exists and is visible
     */
    public boolean isWidgetVisible(int groupId, int childId) {
        Widget widget = client.getWidget(groupId, childId);
        return widget != null && !widget.isHidden();
    }

    /**
     * Get the bounds of a widget.
     *
     * @param groupId the widget group ID
     * @param childId the widget child ID
     * @return the widget bounds, or null if not available
     */
    @Nullable
    public Rectangle getWidgetBounds(int groupId, int childId) {
        Widget widget = client.getWidget(groupId, childId);
        if (widget == null || widget.isHidden()) {
            return null;
        }
        return widget.getBounds();
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

