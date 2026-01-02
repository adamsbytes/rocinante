package com.rocinante.util;

import com.rocinante.tasks.TaskContext;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import java.util.concurrent.CompletableFuture;

/**
 * Centralized helpers for widget and tab interactions.
 * 
 * <p>All tasks that need to open tabs should use {@link #openTabAsync} instead of 
 * implementing their own hotkey/click logic. This ensures:
 * <ul>
 *   <li>Consistent behavior across all tasks</li>
 *   <li>Respect for player preferences (hotkeys vs clicks)</li>
 *   <li>Automatic "already open" detection to avoid redundant actions</li>
 *   <li>Correct F-key mappings (no more wrong keys!)</li>
 * </ul>
 */
@Slf4j
public final class WidgetInteractionHelpers {

    private WidgetInteractionHelpers() {
        // Utility class - no instantiation
    }

    // ========================================================================
    // Tab Index Constants
    // ========================================================================

    public static final int TAB_COMBAT = 0;
    public static final int TAB_SKILLS = 1;
    public static final int TAB_QUESTS = 2;
    public static final int TAB_INVENTORY = 3;
    public static final int TAB_EQUIPMENT = 4;
    public static final int TAB_PRAYER = 5;
    public static final int TAB_SPELLBOOK = 6;
    public static final int TAB_CLAN = 7;
    public static final int TAB_FRIENDS = 8;
    public static final int TAB_ACCOUNT = 9;
    public static final int TAB_LOGOUT = 10;
    public static final int TAB_SETTINGS = 11;
    public static final int TAB_EMOTES = 12;
    public static final int TAB_MUSIC = 13;

    // ========================================================================
    // F-Key Mappings (OSRS default keybinds)
    // ========================================================================

    private static final int[] TAB_FKEYS = {
            java.awt.event.KeyEvent.VK_F1,      // Combat (0)
            java.awt.event.KeyEvent.VK_F2,      // Skills (1)
            java.awt.event.KeyEvent.VK_F3,      // Quests (2)
            java.awt.event.KeyEvent.VK_F4,      // Inventory (3)
            java.awt.event.KeyEvent.VK_F5,      // Equipment (4)
            java.awt.event.KeyEvent.VK_F6,      // Prayer (5)
            java.awt.event.KeyEvent.VK_F7,      // Spellbook (6)
            java.awt.event.KeyEvent.VK_F8,      // Clan (7)
            java.awt.event.KeyEvent.VK_F9,      // Friends (8)
            java.awt.event.KeyEvent.VK_F10,     // Account (9)
            0,                                   // Logout (10) - no default key
            java.awt.event.KeyEvent.VK_F11,     // Settings (11)
            java.awt.event.KeyEvent.VK_F12,     // Emotes (12)
            0                                    // Music (13) - no default key
    };

    // ========================================================================
    // Tab Stone Widget IDs (for click mode)
    // ========================================================================

    // Resizable classic layout (group 161 = ToplevelOsrsStretch)
    private static final int TAB_STONE_GROUP_RESIZABLE_CLASSIC = 161;
    private static final int[] TAB_STONE_CHILDREN_RESIZABLE_CLASSIC = {
            59, 60, 61, 62, 63, 64, 65, 43, 45, 44, 46, 47, 48, 49
    };

    // Resizable modern/bottom-line layout (group 164 = ToplevelPreEoc)
    private static final int TAB_STONE_GROUP_RESIZABLE_MODERN = 164;
    private static final int[] TAB_STONE_CHILDREN_RESIZABLE_MODERN = {
            52, 53, 54, 55, 56, 57, 58, 38, 40, 39, 34, 41, 42, 43
    };

    // Fixed mode layout (group 548 = Toplevel)
    private static final int TAB_STONE_GROUP_FIXED = 548;
    private static final int[] TAB_STONE_CHILDREN_FIXED = {
            64, 65, 66, 67, 68, 69, 70, 48, 50, 49, 51, 52, 53, 54
    };

    // ========================================================================
    // Tab Content Widget Group IDs (to check if tab is open)
    // ========================================================================

    private static final int[] TAB_CONTENT_WIDGET_GROUPS = {
            593,  // Combat (0)
            320,  // Skills (1)
            629,  // Quests (2)
            149,  // Inventory (3)
            387,  // Equipment (4)
            541,  // Prayer (5)
            218,  // Spellbook (6)
            707,  // Clan (7)
            429,  // Friends (8)
            109,  // Account (9)
            182,  // Logout (10)
            261,  // Settings (11)
            216,  // Emotes (12)
            239   // Music (13)
    };

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Open a tab asynchronously, handling hotkey vs click automatically.
     * 
     * <p>This method first checks if the tab is already open - if so, it completes 
     * immediately without pressing any keys or clicking, avoiding redundant actions.
     *
     * <p>The decision to use hotkey vs click is based on:
     * <ol>
     *   <li>If forceClick is non-null, it overrides everything (true=click, false=hotkey)</li>
     *   <li>If PlayerProfile is loaded, uses prefersHotkeys preference</li>
     *   <li>Default: use hotkeys (most efficient)</li>
     * </ol>
     *
     * @param ctx        the task context
     * @param tabIndex   which tab to open (use TAB_* constants)
     * @param forceClick null=use PlayerProfile preference, true=click, false=hotkey
     * @return CompletableFuture that completes when the tab action is initiated (or immediately if already open)
     */
    public static CompletableFuture<Void> openTabAsync(TaskContext ctx, int tabIndex, Boolean forceClick) {
        String tabName = getTabName(tabIndex);
        
        // Check if tab is already open - don't do anything if it is
        if (isTabOpen(ctx.getClient(), tabIndex)) {
            log.debug("Tab {} already open, skipping open action", tabName);
            return CompletableFuture.completedFuture(null);
        }

        if (shouldUseHotkey(ctx, forceClick)) {
            int keyCode = TAB_FKEYS[tabIndex];
            if (keyCode == 0) {
                // No hotkey for this tab, must click
                log.debug("Opening {} tab via click (no hotkey available)", tabName);
                return openTabByClickAsync(ctx, tabIndex);
            }
            log.debug("Opening {} tab via hotkey F{}", tabName, tabIndex + 1);
            return ctx.getKeyboardController().pressKey(keyCode);
        } else {
            log.debug("Opening {} tab via click (forceClick={}, prefersHotkeys={})", 
                    tabName, forceClick, getHotkeyPreference(ctx));
            return openTabByClickAsync(ctx, tabIndex);
        }
    }
    
    /**
     * Get the hotkey preference for logging purposes.
     */
    private static String getHotkeyPreference(TaskContext ctx) {
        if (ctx.getPlayerProfile() == null || !ctx.getPlayerProfile().isLoaded()) {
            return "unloaded";
        }
        return String.valueOf(ctx.getPlayerProfile().getProfileData().isPrefersHotkeys());
    }

    /**
     * Check if a tab is currently open/visible.
     *
     * @param client   the game client
     * @param tabIndex the tab index to check
     * @return true if the tab's content widget is visible
     */
    public static boolean isTabOpen(Client client, int tabIndex) {
        if (tabIndex < 0 || tabIndex >= TAB_CONTENT_WIDGET_GROUPS.length) {
            return false;
        }
        int widgetGroup = TAB_CONTENT_WIDGET_GROUPS[tabIndex];
        if (widgetGroup <= 0) {
            return false;
        }
        Widget tabWidget = client.getWidget(widgetGroup, 0);
        return tabWidget != null && !tabWidget.isHidden();
    }

    /**
     * Check if hotkey should be used for tab opening.
     * Priority: forceClick override > PlayerProfile preference > default (hotkey)
     */
    public static boolean shouldUseHotkey(TaskContext ctx, Boolean forceClick) {
        if (forceClick != null) {
            boolean useHotkey = !forceClick;
            log.debug("shouldUseHotkey: forceClick={} -> useHotkey={}", forceClick, useHotkey);
            return useHotkey;
        }
        if (ctx.getPlayerProfile() != null && ctx.getPlayerProfile().isLoaded()) {
            boolean prefersHotkeys = ctx.getPlayerProfile().getProfileData().isPrefersHotkeys();
            log.debug("shouldUseHotkey: using profile preference -> useHotkey={}", prefersHotkeys);
            return prefersHotkeys;
        }
        log.debug("shouldUseHotkey: no profile loaded, defaulting to hotkey");
        return true;
    }

    /**
     * Get the F-key code for a tab index.
     * Returns 0 if the tab has no default hotkey (e.g., logout, music).
     */
    public static int getTabFKey(int tabIndex) {
        if (tabIndex < 0 || tabIndex >= TAB_FKEYS.length) {
            return 0;
        }
        return TAB_FKEYS[tabIndex];
    }

    /**
     * Get the [groupId, childId] for a tab widget based on interface mode.
     */
    public static int[] getTabWidgetIds(TaskContext ctx, int tabIndex) {
        var mode = ctx.getGameStateService().getInterfaceMode();
        int[] ids;
        String modeUsed;
        
        if (mode == null) {
            ids = new int[]{TAB_STONE_GROUP_FIXED, TAB_STONE_CHILDREN_FIXED[tabIndex]};
            modeUsed = "FIXED (null fallback)";
        } else {
            switch (mode) {
                case FIXED:
                    ids = new int[]{TAB_STONE_GROUP_FIXED, TAB_STONE_CHILDREN_FIXED[tabIndex]};
                    modeUsed = "FIXED";
                    break;
                case RESIZABLE_CLASSIC:
                    ids = new int[]{TAB_STONE_GROUP_RESIZABLE_CLASSIC, TAB_STONE_CHILDREN_RESIZABLE_CLASSIC[tabIndex]};
                    modeUsed = "RESIZABLE_CLASSIC";
                    break;
                case RESIZABLE_MODERN:
                    ids = new int[]{TAB_STONE_GROUP_RESIZABLE_MODERN, TAB_STONE_CHILDREN_RESIZABLE_MODERN[tabIndex]};
                    modeUsed = "RESIZABLE_MODERN";
                    break;
                default:
                    ids = new int[]{TAB_STONE_GROUP_FIXED, TAB_STONE_CHILDREN_FIXED[tabIndex]};
                    modeUsed = "FIXED (unknown fallback)";
                    break;
            }
        }
        
        log.debug("getTabWidgetIds: tab={} mode={} -> widget {}:{}", 
                getTabName(tabIndex), modeUsed, ids[0], ids[1]);
        return ids;
    }

    // ========================================================================
    // Internal Helpers
    // ========================================================================

    /**
     * Open a tab by clicking the tab stone widget.
     */
    private static CompletableFuture<Void> openTabByClickAsync(TaskContext ctx, int tabIndex) {
        int[] ids = getTabWidgetIds(ctx, tabIndex);
        String tabName = getTabName(tabIndex);
        return ctx.getWidgetClickHelper().clickWidget(ids[0], ids[1], "Open " + tabName)
                .thenApply(success -> null);
    }

    /**
     * Get a human-readable name for a tab index.
     */
    private static String getTabName(int tabIndex) {
        switch (tabIndex) {
            case TAB_COMBAT: return "combat";
            case TAB_SKILLS: return "skills";
            case TAB_QUESTS: return "quests";
            case TAB_INVENTORY: return "inventory";
            case TAB_EQUIPMENT: return "equipment";
            case TAB_PRAYER: return "prayer";
            case TAB_SPELLBOOK: return "spellbook";
            case TAB_CLAN: return "clan";
            case TAB_FRIENDS: return "friends";
            case TAB_ACCOUNT: return "account";
            case TAB_LOGOUT: return "logout";
            case TAB_SETTINGS: return "settings";
            case TAB_EMOTES: return "emotes";
            case TAB_MUSIC: return "music";
            default: return "tab " + tabIndex;
        }
    }

    // ========================================================================
    // Close Button Finding
    // ========================================================================

    /**
     * Common sprite IDs for close/X buttons in OSRS interfaces.
     */
    private static final int[] CLOSE_BUTTON_SPRITE_IDS = {
            535,   // Standard X button
            536,   // X button hover state
            537,   // Another X variant
            831,   // Red X  
            832,   // Red X hover
            850,   // Close icon variant
            851,   // Close icon hover
    };

    /**
     * Find a close button (X) within a widget or its children.
     * Searches by:
     * 1. Widgets with "Close" action
     * 2. Widgets with known close button sprite IDs
     * 3. Small square widgets in top-right area (heuristic for X buttons)
     *
     * @param parentWidget the widget to search within
     * @return the close button widget, or null if not found
     */
    public static Widget findCloseButton(Widget parentWidget) {
        if (parentWidget == null) {
            return null;
        }

        // First try static children
        Widget[] children = parentWidget.getChildren();
        Widget found = searchChildrenForCloseButton(children, parentWidget);
        if (found != null) {
            return found;
        }

        // Try dynamic children
        Widget[] dynamicChildren = parentWidget.getDynamicChildren();
        found = searchChildrenForCloseButton(dynamicChildren, parentWidget);
        if (found != null) {
            return found;
        }

        // Try nested children
        Widget[] nestedChildren = parentWidget.getNestedChildren();
        found = searchChildrenForCloseButton(nestedChildren, parentWidget);
        if (found != null) {
            return found;
        }

        return null;
    }

    /**
     * Search an array of child widgets for a close button.
     */
    private static Widget searchChildrenForCloseButton(Widget[] children, Widget parent) {
        if (children == null || children.length == 0) {
            return null;
        }

        java.awt.Rectangle parentBounds = parent.getBounds();
        Widget bestCandidate = null;
        int bestScore = 0;

        for (Widget child : children) {
            if (child == null || child.isHidden()) {
                continue;
            }

            int score = scoreAsCloseButton(child, parentBounds);
            if (score > bestScore) {
                bestScore = score;
                bestCandidate = child;
            }

            // Also check nested children of this child
            Widget nestedClose = findCloseButton(child);
            if (nestedClose != null) {
                int nestedScore = scoreAsCloseButton(nestedClose, parentBounds);
                if (nestedScore > bestScore) {
                    bestScore = nestedScore;
                    bestCandidate = nestedClose;
                }
            }
        }

        // Only return if we have a reasonably confident match
        return bestScore >= 50 ? bestCandidate : null;
    }

    /**
     * Score a widget on how likely it is to be a close button.
     * Higher score = more likely.
     *
     * @param widget the widget to score
     * @param parentBounds bounds of the parent container (for position heuristics)
     * @return score from 0-100
     */
    private static int scoreAsCloseButton(Widget widget, java.awt.Rectangle parentBounds) {
        int score = 0;

        // Check for "Close" action - very strong signal
        String[] actions = widget.getActions();
        if (actions != null) {
            for (String action : actions) {
                if (action != null && action.equalsIgnoreCase("Close")) {
                    score += 80;
                    break;
                }
            }
        }

        // Check sprite ID - strong signal
        int spriteId = widget.getSpriteId();
        for (int closeSprite : CLOSE_BUTTON_SPRITE_IDS) {
            if (spriteId == closeSprite) {
                score += 60;
                break;
            }
        }

        // Check if it's a small square widget (typical for X buttons)
        java.awt.Rectangle bounds = widget.getBounds();
        if (bounds != null && bounds.width > 0) {
            // X buttons are typically small squares (10-25 pixels)
            if (bounds.width >= 10 && bounds.width <= 30 && 
                bounds.height >= 10 && bounds.height <= 30 &&
                Math.abs(bounds.width - bounds.height) <= 5) {
                score += 20;
            }

            // Check if positioned in top-right area of parent
            if (parentBounds != null && parentBounds.width > 0) {
                int rightEdge = parentBounds.x + parentBounds.width;
                int topArea = parentBounds.y + 40; // Top 40 pixels
                
                if (bounds.x + bounds.width >= rightEdge - 30 && bounds.y <= topArea) {
                    score += 15;
                }
            }
        }

        // Check widget name/text for hints
        String name = widget.getName();
        if (name != null && (name.toLowerCase().contains("close") || name.toLowerCase().contains("exit"))) {
            score += 30;
        }

        return score;
    }

    /**
     * Find a close button in a widget group by checking multiple child IDs.
     *
     * @param client the game client
     * @param groupId the widget group ID
     * @param possibleChildIds array of child IDs to check for the close button
     * @return the close button widget, or null if not found
     */
    public static Widget findCloseButtonInGroup(Client client, int groupId, int... possibleChildIds) {
        for (int childId : possibleChildIds) {
            Widget widget = client.getWidget(groupId, childId);
            if (widget != null && !widget.isHidden()) {
                int score = scoreAsCloseButton(widget, null);
                if (score >= 50) {
                    log.debug("Found close button at {}:{} (score: {})", groupId, childId, score);
                    return widget;
                }
            }
        }
        return null;
    }

    /**
     * Log widget structure for debugging - useful when trying to find the right widget.
     *
     * @param widget the widget to log
     * @param depth current recursion depth
     * @param maxDepth maximum recursion depth
     */
    public static void logWidgetStructure(Widget widget, int depth, int maxDepth) {
        if (widget == null || depth > maxDepth) {
            return;
        }

        String indent = "  ".repeat(depth);
        String[] actions = widget.getActions();
        String actionStr = actions != null ? String.join(",", (CharSequence[]) actions) : "null";
        
        log.info("{}Widget id={} sprite={} bounds={} actions=[{}] hidden={} text='{}'",
                indent, widget.getId(), widget.getSpriteId(), widget.getBounds(),
                actionStr, widget.isHidden(),
                widget.getText() != null ? widget.getText().substring(0, Math.min(20, widget.getText().length())) : "null");

        Widget[] children = widget.getChildren();
        if (children != null) {
            for (int i = 0; i < Math.min(children.length, 10); i++) {
                if (children[i] != null) {
                    logWidgetStructure(children[i], depth + 1, maxDepth);
                }
            }
        }
    }

    // ========================================================================
    // Generic Tutorial / Screen Highlight System
    // ========================================================================

    /**
     * Screen highlight / tutorial overlay group (664 = 0x0298).
     * OSRS uses this for various first-time tutorials (bank, equipment, etc.)
     */
    public static final int WIDGET_SCREEN_HIGHLIGHT_GROUP = 664;
    
    /**
     * Close button in the screen highlight tutorial (664:29 = 0x0298_001d).
     */
    public static final int WIDGET_SCREEN_HIGHLIGHT_CLOSE = 29;

    /**
     * Check if a generic tutorial overlay (screen highlight) is visible.
     * This is used for various first-time tutorials in OSRS.
     *
     * @param client the game client
     * @return true if a tutorial overlay is visible and blocking
     */
    public static boolean isGenericTutorialVisible(Client client) {
        Widget closeButton = client.getWidget(WIDGET_SCREEN_HIGHLIGHT_GROUP, WIDGET_SCREEN_HIGHLIGHT_CLOSE);
        return closeButton != null && !closeButton.isHidden();
    }

    /**
     * Get the close button widget for the generic tutorial overlay.
     *
     * @param client the game client
     * @return the close button widget, or null if tutorial not visible
     */
    public static Widget getGenericTutorialCloseButton(Client client) {
        Widget closeButton = client.getWidget(WIDGET_SCREEN_HIGHLIGHT_GROUP, WIDGET_SCREEN_HIGHLIGHT_CLOSE);
        if (closeButton != null && !closeButton.isHidden()) {
            return closeButton;
        }
        return null;
    }

    /**
     * Dismiss the generic tutorial overlay by clicking its close button.
     *
     * @param ctx the task context
     * @return CompletableFuture that completes when the click is done
     */
    public static CompletableFuture<Boolean> dismissGenericTutorial(TaskContext ctx) {
        Widget closeButton = getGenericTutorialCloseButton(ctx.getClient());
        if (closeButton == null) {
            log.debug("No generic tutorial visible to dismiss");
            return CompletableFuture.completedFuture(false);
        }
        
        log.info("Dismissing generic tutorial (clicking 664:29)");
        return ctx.getWidgetClickHelper().clickWidget(closeButton, "Close tutorial");
    }
}

