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
            logWidgetDebugInfo(groupId, childId, widget);
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

    // ========================================================================
    // Widget Visibility Methods
    // ========================================================================

    /**
     * Check if a widget is visible.
     *
     * @param groupId the widget group ID
     * @param childId the widget child ID
     * @return true if the widget exists and is visible
     */
    public boolean isWidgetVisible(int groupId, int childId) {
        Widget widget = client.getWidget(groupId, childId);
        return isWidgetVisible(widget);
    }

    /**
     * Check if a widget is visible.
     *
     * @param widget the widget to check
     * @return true if the widget exists and is visible
     */
    public boolean isWidgetVisible(Widget widget) {
        return widget != null && !widget.isHidden();
    }

    /**
     * Check if a widget is visible by packed ID.
     *
     * @param packedId the packed widget ID (group << 16 | child)
     * @return true if the widget exists and is visible
     */
    public boolean isWidgetVisibleByPackedId(int packedId) {
        int groupId = packedId >> 16;
        int childId = packedId & 0xFFFF;
        return isWidgetVisible(groupId, childId);
    }

    /**
     * Check if a widget group (container) is loaded.
     * Useful for checking if a major interface (bank, GE, etc.) is open.
     *
     * @param groupId the widget group ID
     * @return true if the widget group exists
     */
    public boolean isWidgetGroupLoaded(int groupId) {
        Widget widget = client.getWidget(groupId, 0);
        return widget != null;
    }

    /**
     * Check if a widget group is fully visible (not just loaded).
     *
     * @param groupId the widget group ID
     * @return true if the group is loaded and not hidden
     */
    public boolean isWidgetGroupVisible(int groupId) {
        Widget widget = client.getWidget(groupId, 0);
        return widget != null && !widget.isHidden();
    }

    /**
     * Check if any of the specified widgets are visible.
     *
     * @param widgetIds array of packed widget IDs to check
     * @return true if any widget is visible
     */
    public boolean isAnyWidgetVisible(int... widgetIds) {
        for (int packedId : widgetIds) {
            if (isWidgetVisibleByPackedId(packedId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if all of the specified widgets are visible.
     *
     * @param widgetIds array of packed widget IDs to check
     * @return true if all widgets are visible
     */
    public boolean areAllWidgetsVisible(int... widgetIds) {
        for (int packedId : widgetIds) {
            if (!isWidgetVisibleByPackedId(packedId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Find the first visible widget from a list of candidates.
     *
     * @param widgetIds array of packed widget IDs to check
     * @return the first visible widget, or null if none visible
     */
    @Nullable
    public Widget findFirstVisibleWidget(int... widgetIds) {
        for (int packedId : widgetIds) {
            int groupId = packedId >> 16;
            int childId = packedId & 0xFFFF;
            Widget widget = client.getWidget(groupId, childId);
            if (widget != null && !widget.isHidden()) {
                return widget;
            }
        }
        return null;
    }

    // ========================================================================
    // Widget Content Methods
    // ========================================================================

    /**
     * Check if a widget contains specific text.
     *
     * @param groupId the widget group ID
     * @param childId the widget child ID
     * @param text    the text to search for (case-insensitive contains)
     * @return true if widget exists and contains the text
     */
    public boolean widgetContainsText(int groupId, int childId, String text) {
        Widget widget = client.getWidget(groupId, childId);
        return widgetContainsText(widget, text);
    }

    /**
     * Check if a widget contains specific text.
     *
     * @param widget the widget to check
     * @param text   the text to search for (case-insensitive contains)
     * @return true if widget exists and contains the text
     */
    public boolean widgetContainsText(Widget widget, String text) {
        if (widget == null || widget.isHidden() || text == null) {
            return false;
        }
        String widgetText = widget.getText();
        return widgetText != null && widgetText.toLowerCase().contains(text.toLowerCase());
    }

    /**
     * Check if a widget has the exact text.
     *
     * @param groupId the widget group ID
     * @param childId the widget child ID
     * @param text    the exact text to match
     * @return true if widget exists and has the exact text
     */
    public boolean widgetHasText(int groupId, int childId, String text) {
        Widget widget = client.getWidget(groupId, childId);
        if (widget == null || widget.isHidden()) {
            return false;
        }
        String widgetText = widget.getText();
        return widgetText != null && widgetText.equals(text);
    }

    /**
     * Get the text of a widget.
     *
     * @param groupId the widget group ID
     * @param childId the widget child ID
     * @return the widget text, or null if not available
     */
    @Nullable
    public String getWidgetText(int groupId, int childId) {
        Widget widget = client.getWidget(groupId, childId);
        if (widget == null || widget.isHidden()) {
            return null;
        }
        return widget.getText();
    }

    /**
     * Get the item ID from a widget (for inventory-style widgets).
     *
     * @param groupId the widget group ID
     * @param childId the widget child ID
     * @return the item ID, or -1 if not applicable
     */
    public int getWidgetItemId(int groupId, int childId) {
        Widget widget = client.getWidget(groupId, childId);
        if (widget == null || widget.isHidden()) {
            return -1;
        }
        return widget.getItemId();
    }

    /**
     * Get the item quantity from a widget.
     *
     * @param groupId the widget group ID
     * @param childId the widget child ID
     * @return the item quantity, or 0 if not applicable
     */
    public int getWidgetItemQuantity(int groupId, int childId) {
        Widget widget = client.getWidget(groupId, childId);
        if (widget == null || widget.isHidden()) {
            return 0;
        }
        return widget.getItemQuantity();
    }

    // ========================================================================
    // Widget Child Search Methods
    // ========================================================================

    /**
     * Find a child widget by text content.
     *
     * @param groupId the widget group ID
     * @param text    the text to search for (case-insensitive contains)
     * @return the matching child widget, or null if not found
     */
    @Nullable
    public Widget findChildByText(int groupId, String text) {
        Widget parent = client.getWidget(groupId, 0);
        if (parent == null) {
            return null;
        }

        Widget[] children = parent.getStaticChildren();
        if (children != null) {
            for (Widget child : children) {
                if (widgetContainsText(child, text)) {
                    return child;
                }
            }
        }

        Widget[] dynamicChildren = parent.getDynamicChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) {
                if (widgetContainsText(child, text)) {
                    return child;
                }
            }
        }

        return null;
    }

    /**
     * Find a child widget by item ID.
     *
     * @param groupId the widget group ID
     * @param itemId  the item ID to find
     * @return the matching child widget, or null if not found
     */
    @Nullable
    public Widget findChildByItemId(int groupId, int itemId) {
        Widget parent = client.getWidget(groupId, 0);
        if (parent == null) {
            return null;
        }

        Widget[] children = parent.getStaticChildren();
        if (children != null) {
            for (Widget child : children) {
                if (child != null && child.getItemId() == itemId) {
                    return child;
                }
            }
        }

        Widget[] dynamicChildren = parent.getDynamicChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) {
                if (child != null && child.getItemId() == itemId) {
                    return child;
                }
            }
        }

        return null;
    }

    // ========================================================================
    // Widget Bounds Methods
    // ========================================================================

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

    /**
     * Check if a widget has valid (clickable) bounds.
     *
     * @param groupId the widget group ID
     * @param childId the widget child ID
     * @return true if widget has valid bounds
     */
    public boolean hasValidBounds(int groupId, int childId) {
        Rectangle bounds = getWidgetBounds(groupId, childId);
        return bounds != null && bounds.width > 0 && bounds.height > 0;
    }

    /**
     * Check if a widget has valid (clickable) bounds.
     *
     * @param widget the widget to check
     * @return true if widget has valid bounds
     */
    public boolean hasValidBounds(Widget widget) {
        if (widget == null || widget.isHidden()) {
            return false;
        }
        Rectangle bounds = widget.getBounds();
        return bounds != null && bounds.width > 0 && bounds.height > 0;
    }

    // ========================================================================
    // Common Interface Checks
    // ========================================================================

    /**
     * Common widget group IDs for quick reference.
     */
    public static final int WIDGET_INVENTORY = 149;
    public static final int WIDGET_BANK = 12;
    public static final int WIDGET_BANK_INVENTORY = 15;
    public static final int WIDGET_EQUIPMENT = 387;
    public static final int WIDGET_PRAYER = 541;
    public static final int WIDGET_MAGIC = 218;
    public static final int WIDGET_COMBAT = 593;
    public static final int WIDGET_DIALOGUE_NPC = 231;
    public static final int WIDGET_DIALOGUE_PLAYER = 217;
    public static final int WIDGET_DIALOGUE_OPTIONS = 219;
    public static final int WIDGET_DIALOGUE_SPRITE = 193;
    public static final int WIDGET_LEVEL_UP = 233;
    public static final int WIDGET_GRAND_EXCHANGE = 465;
    public static final int WIDGET_SHOP = 300;

    /**
     * Check if the bank interface is open.
     *
     * @return true if bank is open
     */
    public boolean isBankOpen() {
        return isWidgetGroupVisible(WIDGET_BANK);
    }

    /**
     * Check if any dialogue is open.
     *
     * @return true if dialogue is active
     */
    public boolean isDialogueOpen() {
        return isWidgetGroupVisible(WIDGET_DIALOGUE_NPC) ||
               isWidgetGroupVisible(WIDGET_DIALOGUE_PLAYER) ||
               isWidgetGroupVisible(WIDGET_DIALOGUE_OPTIONS) ||
               isWidgetGroupVisible(WIDGET_DIALOGUE_SPRITE);
    }

    /**
     * Check if dialogue options are available.
     *
     * @return true if option dialogue is open
     */
    public boolean hasDialogueOptions() {
        return isWidgetGroupVisible(WIDGET_DIALOGUE_OPTIONS);
    }

    /**
     * Check if a continue dialogue is showing.
     *
     * @return true if continue button is available
     */
    public boolean hasContinueDialogue() {
        return isWidgetGroupVisible(WIDGET_DIALOGUE_NPC) ||
               isWidgetGroupVisible(WIDGET_DIALOGUE_PLAYER);
    }

    /**
     * Check if level up dialogue is showing.
     *
     * @return true if level up notification is visible
     */
    public boolean isLevelUpShowing() {
        return isWidgetGroupVisible(WIDGET_LEVEL_UP);
    }

    /**
     * Check if Grand Exchange interface is open.
     *
     * @return true if GE is open
     */
    public boolean isGrandExchangeOpen() {
        return isWidgetGroupVisible(WIDGET_GRAND_EXCHANGE);
    }

    /**
     * Check if a shop interface is open.
     *
     * @return true if shop is open
     */
    public boolean isShopOpen() {
        return isWidgetGroupVisible(WIDGET_SHOP);
    }

    // ========================================================================
    // Humanization (Section 3.1.2)
    // ========================================================================

    /**
     * Generate a random offset within a dimension for click position.
     * Delegates to ClickPointCalculator for centralized humanization.
     *
     * @param dimension the width or height of the clickable area
     * @return humanized offset within the dimension
     */
    int randomOffset(int dimension) {
        return ClickPointCalculator.calculateGaussianOffset(dimension);
    }

    // ========================================================================
    // Debug Logging
    // ========================================================================

    /**
     * Log debug info about widget state when lookup fails.
     * Only logs at DEBUG level to avoid spam during normal operation.
     *
     * @param groupId the requested group ID
     * @param childId the requested child ID
     * @param widget  the widget that was found (may be null or hidden)
     */
    private void logWidgetDebugInfo(int groupId, int childId, @Nullable Widget widget) {
        if (!log.isDebugEnabled()) {
            return;
        }

        StringBuilder sb = new StringBuilder("Widget lookup failed - diagnostic info:\n");
        sb.append("  Requested: ").append(groupId).append(":").append(childId).append("\n");
        
        // Status of the requested widget
        sb.append("  Widget status: ");
        if (widget == null) {
            sb.append("NULL\n");
        } else {
            sb.append("exists, hidden=").append(widget.isHidden())
              .append(", bounds=").append(widget.getBounds()).append("\n");
        }
        
        // Check if the parent group exists
        Widget parent = client.getWidget(groupId, 0);
        sb.append("  Parent group (").append(groupId).append(":0): ");
        if (parent == null) {
            sb.append("NULL - group not loaded\n");
        } else {
            sb.append("exists, hidden=").append(parent.isHidden()).append("\n");
            
            // List visible children in this group
            sb.append("  Visible children in group ").append(groupId).append(":\n");
            int visibleCount = 0;
            for (int i = 0; i < 50 && visibleCount < 20; i++) {
                Widget child = client.getWidget(groupId, i);
                if (child != null && !child.isHidden()) {
                    Rectangle bounds = child.getBounds();
                    sb.append("    ").append(groupId).append(":").append(i)
                      .append(" - bounds=").append(bounds != null ? bounds.toString() : "null");
                    String text = child.getText();
                    if (text != null && !text.isEmpty()) {
                        sb.append(", text=\"").append(text.length() > 30 ? text.substring(0, 30) + "..." : text).append("\"");
                    }
                    Widget[] dynamicChildren = child.getDynamicChildren();
                    if (dynamicChildren != null && dynamicChildren.length > 0) {
                        sb.append(", dynamicChildren=").append(dynamicChildren.length);
                    }
                    sb.append("\n");
                    visibleCount++;
                }
            }
            if (visibleCount == 0) {
                sb.append("    (none visible)\n");
            }
        }
        
        // List other visible interface groups
        sb.append("  Other visible interface groups: ");
        StringBuilder visibleGroups = new StringBuilder();
        int[] commonGroups = {149, 387, 12, 15, 541, 218, 593, 231, 217, 219, 193, 233, 465, 300, 162, 163, 164};
        for (int gid : commonGroups) {
            if (gid == groupId) continue; // Skip the one we already checked
            Widget w = client.getWidget(gid, 0);
            if (w != null && !w.isHidden()) {
                if (visibleGroups.length() > 0) visibleGroups.append(", ");
                visibleGroups.append(gid);
            }
        }
        sb.append(visibleGroups.length() > 0 ? visibleGroups.toString() : "none").append("\n");
        
        log.debug(sb.toString());
    }
}

