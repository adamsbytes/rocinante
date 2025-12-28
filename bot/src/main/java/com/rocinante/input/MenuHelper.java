package com.rocinante.input;

import com.rocinante.timing.DelayProfile;
import com.rocinante.timing.HumanTimer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.widgets.Widget;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Rectangle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Helper for interacting with the OSRS context menu.
 *
 * <p>Provides reusable logic for:
 * <ul>
 *   <li>Right-clicking to open context menu</li>
 *   <li>Finding menu entries by action text</li>
 *   <li>Clicking menu entries with humanized behavior</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Check if we can left-click for "Open" action
 * if (menuHelper.isLeftClickAction("Open")) {
 *     mouseController.click();  // Simple left click
 * } else {
 *     menuHelper.selectMenuEntry(hitbox, "Open")
 *         .thenAccept(success -> log.info("Menu selection: {}", success));
 * }
 * }</pre>
 */
@Slf4j
@Singleton
public class MenuHelper {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Menu option widget group ID.
     */
    private static final int MENU_WIDGET_GROUP = 187;

    /**
     * Approximate height per menu entry (pixels).
     */
    private static final int MENU_ENTRY_HEIGHT = 15;

    /**
     * Menu header offset (pixels).
     */
    private static final int MENU_HEADER_OFFSET = 19;

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final Client client;
    private final RobotMouseController mouseController;
    private final HumanTimer humanTimer;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public MenuHelper(Client client, RobotMouseController mouseController, HumanTimer humanTimer) {
        this.client = client;
        this.mouseController = mouseController;
        this.humanTimer = humanTimer;
        log.info("MenuHelper initialized");
    }

    // ========================================================================
    // Public API - Left Click Detection
    // ========================================================================

    /**
     * Check if the specified action is available via left-click (i.e., it's the first menu option).
     *
     * @param action the menu action to check (e.g., "Open", "Talk-to", "Bank")
     * @return true if this action can be performed with a left-click
     */
    public boolean isLeftClickAction(String action) {
        if (action == null || action.isEmpty()) {
            return false;
        }

        MenuEntry[] entries = client.getMenuEntries();
        if (entries == null || entries.length == 0) {
            return false;
        }

        // The first (top) entry in the reversed array is what left-click does
        // RuneLite's menu entries are stored bottom-to-top, so last entry is left-click
        MenuEntry leftClickEntry = entries[entries.length - 1];
        if (leftClickEntry == null) {
            return false;
        }

        String option = leftClickEntry.getOption();
        return option != null && option.equalsIgnoreCase(action);
    }

    /**
     * Check if the specified action is available via left-click, with case-insensitive matching.
     *
     * @param action the menu action to check
     * @return true if this action can be performed with a left-click
     */
    public boolean isLeftClickActionContains(String action) {
        if (action == null || action.isEmpty()) {
            return false;
        }

        MenuEntry[] entries = client.getMenuEntries();
        if (entries == null || entries.length == 0) {
            return false;
        }

        MenuEntry leftClickEntry = entries[entries.length - 1];
        if (leftClickEntry == null) {
            return false;
        }

        String option = leftClickEntry.getOption();
        return option != null && option.toLowerCase().contains(action.toLowerCase());
    }

    // ========================================================================
    // Public API - Right Click Menu Selection
    // ========================================================================

    /**
     * Open context menu at the given hitbox and select the specified action.
     *
     * @param hitbox the canvas-relative hitbox to right-click
     * @param action the menu action to select (e.g., "Bank", "Talk-to")
     * @return CompletableFuture that completes with true if successful
     */
    public CompletableFuture<Boolean> selectMenuEntry(Rectangle hitbox, String action) {
        return selectMenuEntry(hitbox, action, null);
    }

    /**
     * Open context menu at the given hitbox and select the specified action targeting a specific name.
     *
     * @param hitbox     the canvas-relative hitbox to right-click
     * @param action     the menu action to select (e.g., "Bank", "Talk-to")
     * @param targetName optional target name to match in menu (e.g., NPC name, object name)
     * @return CompletableFuture that completes with true if successful
     */
    public CompletableFuture<Boolean> selectMenuEntry(Rectangle hitbox, String action, @Nullable String targetName) {
        log.debug("Selecting menu entry '{}' on target '{}' at hitbox {}", action, targetName, hitbox);

        // Right-click to open menu
        return mouseController.rightClick(hitbox)
                .thenCompose(v -> humanTimer.sleep(DelayProfile.MENU_SELECT))
                .thenCompose(v -> findAndClickMenuEntry(action, targetName))
                .exceptionally(e -> {
                    log.error("Failed to select menu entry '{}': {}", action, e.getMessage(), e);
                    return false;
                });
    }

    // ========================================================================
    // Menu Entry Finding
    // ========================================================================

    /**
     * Find and click the menu entry matching the action.
     */
    private CompletableFuture<Boolean> findAndClickMenuEntry(String action, @Nullable String targetName) {
        Rectangle menuEntryBounds = findMenuEntry(action, targetName);

        if (menuEntryBounds == null) {
            log.warn("Could not find menu entry '{}' for target '{}' - menu may have closed", action, targetName);
            return CompletableFuture.completedFuture(false);
        }

        // Calculate humanized click position within the menu entry
        int clickX = menuEntryBounds.x + randomOffset(menuEntryBounds.width);
        int clickY = menuEntryBounds.y + randomOffset(menuEntryBounds.height);

        log.trace("Clicking menu entry '{}' at ({}, {})", action, clickX, clickY);

        return mouseController.moveToCanvas(clickX, clickY)
                .thenCompose(v -> mouseController.click())
                .thenApply(v -> {
                    log.debug("Menu selection completed for '{}'", action);
                    return true;
                });
    }

    /**
     * Find the bounds of a menu entry.
     *
     * @param action     the action text to find
     * @param targetName optional target name to match
     * @return the bounds of the menu entry, or null if not found
     */
    @Nullable
    private Rectangle findMenuEntry(String action, @Nullable String targetName) {
        // Try scanning menu widget group first (more reliable)
        Widget menuGroup = client.getWidget(MENU_WIDGET_GROUP, 0);
        if (menuGroup != null && !menuGroup.isHidden()) {
            Rectangle bounds = findMenuEntryInWidget(menuGroup, action, targetName);
            if (bounds != null) {
                return bounds;
            }
        }

        // Fall back to using client menu entries with calculated positions
        return findMenuEntryFromClientEntries(action, targetName);
    }

    /**
     * Find menu entry by scanning widget children.
     */
    @Nullable
    private Rectangle findMenuEntryInWidget(Widget menuWidget, String action, @Nullable String targetName) {
        Widget[] children = menuWidget.getDynamicChildren();
        if (children == null || children.length == 0) {
            children = menuWidget.getStaticChildren();
        }

        if (children == null) {
            return null;
        }

        String searchAction = action.toLowerCase();
        String searchTarget = targetName != null ? targetName.toLowerCase() : null;

        for (Widget child : children) {
            if (child == null || child.isHidden()) {
                continue;
            }

            String text = child.getText();
            if (text != null) {
                String textLower = text.toLowerCase();
                boolean actionMatches = textLower.contains(searchAction);
                boolean targetMatches = searchTarget == null || textLower.contains(searchTarget);

                if (actionMatches && targetMatches) {
                    Rectangle bounds = child.getBounds();
                    if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                        log.trace("Found menu entry '{}' with bounds {}", text, bounds);
                        return bounds;
                    }
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
                    if (nestedText != null) {
                        String nestedLower = nestedText.toLowerCase();
                        boolean actionMatches = nestedLower.contains(searchAction);
                        boolean targetMatches = searchTarget == null || nestedLower.contains(searchTarget);

                        if (actionMatches && targetMatches) {
                            Rectangle bounds = nested.getBounds();
                            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                                log.trace("Found nested menu entry '{}' with bounds {}", nestedText, bounds);
                                return bounds;
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Fall back to using client menu entries to estimate position.
     */
    @Nullable
    private Rectangle findMenuEntryFromClientEntries(String action, @Nullable String targetName) {
        MenuEntry[] entries = client.getMenuEntries();
        if (entries == null || entries.length == 0) {
            return null;
        }

        String searchAction = action.toLowerCase();
        String searchTarget = targetName != null ? targetName.toLowerCase() : null;

        int menuX = client.getMenuX();
        int menuY = client.getMenuY();
        int menuWidth = client.getMenuWidth();

        // Menu entries are stored bottom-to-top, but displayed top-to-bottom
        // So we iterate from the end (top of menu) to start (bottom of menu)
        int displayIndex = 0;
        for (int i = entries.length - 1; i >= 0; i--) {
            MenuEntry entry = entries[i];
            if (entry == null) {
                continue;
            }

            String option = entry.getOption();
            String target = entry.getTarget();

            if (option != null) {
                boolean actionMatches = option.toLowerCase().contains(searchAction);
                boolean targetMatches = searchTarget == null || 
                        (target != null && target.toLowerCase().contains(searchTarget));

                if (actionMatches && targetMatches) {
                    int entryY = menuY + MENU_HEADER_OFFSET + (displayIndex * MENU_ENTRY_HEIGHT);
                    Rectangle bounds = new Rectangle(menuX, entryY, menuWidth, MENU_ENTRY_HEIGHT);
                    log.trace("Estimated menu entry position for '{}': {}", action, bounds);
                    return bounds;
                }
            }
            displayIndex++;
        }

        return null;
    }

    // ========================================================================
    // Humanization
    // ========================================================================

    /**
     * Generate a random offset within a dimension for click position.
     * Delegates to ClickPointCalculator for centralized implementation.
     *
     * @param dimension the width or height of the clickable area
     * @return humanized offset within the dimension
     */
    private int randomOffset(int dimension) {
        return ClickPointCalculator.calculateGaussianOffset(dimension);
    }
}

