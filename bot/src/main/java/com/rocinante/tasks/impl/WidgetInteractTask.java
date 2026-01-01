package com.rocinante.tasks.impl;

import com.rocinante.state.WorldState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.util.Randomization;
import com.rocinante.util.WidgetInteractionHelpers;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static com.rocinante.util.WidgetInteractionHelpers.*;

/**
 * Task for interacting with UI widgets.
 *
 * Per REQUIREMENTS.md Section 5.4.8:
 * <ul>
 *   <li>Click on widgets by group/child ID</li>
 *   <li>Support hover, click, drag operations</li>
 *   <li>Used for: opening tabs, clicking buttons, equipment screen</li>
 *   <li>Humanized click positions within widget bounds</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Click inventory tab
 * WidgetInteractTask openInv = new WidgetInteractTask(548, 55)
 *     .withAction(WidgetAction.CLICK);
 *
 * // Open settings via F-key
 * WidgetInteractTask openSettings = WidgetInteractTask.pressKey(java.awt.event.KeyEvent.VK_ESCAPE);
 *
 * // Click specific button
 * WidgetInteractTask clickButton = new WidgetInteractTask(387, 17)
 *     .withDescription("Click equip button");
 * }</pre>
 */
@Slf4j
public class WidgetInteractTask extends AbstractTask {

    // ========================================================================
    // Common Widget Constants
    // ========================================================================

    // Fixed mode interface
    public static final int FIXED_INVENTORY_TAB = 548;
    public static final int FIXED_INVENTORY_TAB_CHILD = 55;

    // Resizable mode interface
    public static final int RESIZABLE_INVENTORY_TAB = 161;

    // Tab constants are in WidgetInteractionHelpers (imported via static import above)
    

    // ========================================================================
    // Widget Constants
    // ========================================================================

    // Spellbook (Group 218)
    public static final int SPELLBOOK_GROUP = 218;
    public static final int SPELL_VARROCK_TELEPORT = 23;
    public static final int SPELL_LUMBRIDGE_TELEPORT = 26;
    public static final int SPELL_FALADOR_TELEPORT = 29;
    public static final int SPELL_CAMELOT_TELEPORT = 34; // 0x22
    public static final int SPELL_ARDOUGNE_TELEPORT = 41; // 0x29
    public static final int SPELL_WATCHTOWER_TELEPORT = 47; // 0x2f
    public static final int SPELL_TROLLHEIM_TELEPORT = 54; // 0x36
    public static final int SPELL_KOUREND_TELEPORT = 36; // 0x24
    public static final int SPELL_TELEPORT_TO_HOUSE = 31; // 0x1f
    public static final int SPELL_HOME_TELEPORT = 7;

    // Equipment (Group 387)
    public static final int EQUIPMENT_GROUP = 387;
    // Slots based on InterfaceID.Wornitems (0x0183_000F = 15, etc.)
    // Note: Wornitems numbering seems offset from EquipmentInventorySlot
    // Let's use the explicit child IDs derived from InterfaceID.Wornitems
    // 0x0F=15 (Head?), 0x10=16 (Cape?), etc.
    // Actually, let's map these to EquipmentInventorySlot indices more carefully if possible.
    // Based on my UnequipItemTask array:
    // Head=6, Cape=7, Amulet=8, Weapon=9, Body=10, Shield=11, Legs=12, Gloves=13, Boots=14, Ring=15, Ammo=16
    // Checking Wornitems again:
    // SLOT0 = 15 (0x0F) -> This might be Ring?
    // Let's rely on the verified values I put in UnequipItemTask which matched WidgetInfo.EQUIPMENT_* naming convention
    // WidgetInfo.EQUIPMENT_HELMET is 387, 6.
    public static final int WIDGET_EQUIPMENT_HELMET = 6;
    public static final int WIDGET_EQUIPMENT_CAPE = 7;
    public static final int WIDGET_EQUIPMENT_AMULET = 8;
    public static final int WIDGET_EQUIPMENT_WEAPON = 9;
    public static final int WIDGET_EQUIPMENT_BODY = 10;
    public static final int WIDGET_EQUIPMENT_SHIELD = 11;
    public static final int WIDGET_EQUIPMENT_LEGS = 12;
    public static final int WIDGET_EQUIPMENT_GLOVES = 13;
    public static final int WIDGET_EQUIPMENT_BOOTS = 14;
    public static final int WIDGET_EQUIPMENT_RING = 15;
    public static final int WIDGET_EQUIPMENT_AMMO = 16;

    // ========================================================================
    // Action Types
    // ========================================================================

    /**
     * Widget interaction actions.
     */
    public enum WidgetAction {
        CLICK,
        RIGHT_CLICK,
        HOVER,
        DOUBLE_CLICK,
        DRAG
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Widget group ID.
     */
    @Getter
    private final int widgetGroupId;

    /**
     * Widget child ID.
     */
    @Getter
    @Setter
    private int widgetChildId = 0;

    /**
     * Optional grandchild index (for dynamic children).
     */
    @Getter
    @Setter
    private int dynamicChildIndex = -1;

    /**
     * The action to perform.
     */
    @Getter
    @Setter
    private WidgetAction action = WidgetAction.CLICK;

    /**
     * Key to press instead of clicking (optional).
     */
    @Getter
    @Setter
    private int keyCode = -1;

    /**
     * Whether to wait for the widget to become visible.
     */
    @Getter
    @Setter
    private boolean waitForVisible = true;

    /**
     * Maximum ticks to wait for widget visibility.
     */
    @Getter
    @Setter
    private int visibilityTimeout = 10;

    /**
     * Whether to verify widget is visible after interaction.
     */
    @Getter
    @Setter
    private boolean verifyAfter = false;

    /**
     * Widget group to verify is visible after interaction.
     */
    @Getter
    @Setter
    private int verifyWidgetGroup = -1;

    /**
     * Custom description.
     */
    @Getter
    @Setter
    private String description;

    /**
     * Force click interaction instead of using hotkeys.
     * - null: Use PlayerProfile preference (default)
     * - true: Always click the widget (ignore hotkeys)
     * - false: Always use hotkeys (if available)
     * 
     * This allows explicit control from quest steps (e.g., Tutorial Island
     * explicitly teaches clicking tabs) while defaulting to player preference.
     */
    @Getter
    @Setter
    private Boolean forceClick = null;

    /**
     * Tab index for tab-switching tasks (used with forceClick).
     * Set automatically by openTab() factory method.
     */
    @Getter
    @Setter
    private int tabIndex = -1;

    /**
     * Target widget group ID for DRAG action.
     */
    @Getter
    @Setter
    private int dragTargetGroupId = -1;

    /**
     * Target widget child ID for DRAG action.
     */
    @Getter
    @Setter
    private int dragTargetChildId = -1;

    /**
     * Target dynamic child index for DRAG action.
     */
    @Getter
    @Setter
    private int dragTargetDynamicChildIndex = -1;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private InteractionPhase phase = InteractionPhase.WAIT_VISIBLE;

    /**
     * Ticks waiting for visibility.
     */
    private int waitTicks = 0;

    /**
     * Whether a click is pending.
     */
    private boolean clickPending = false;
    
    /**
     * Cached reference to TaskContext for interface mode lookup.
     */
    private TaskContext lastContext = null;

    /**
     * Whether a key press is pending.
     */
    private boolean keyPending = false;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a widget interact task.
     *
     * @param widgetGroupId the widget group ID
     */
    public WidgetInteractTask(int widgetGroupId) {
        this.widgetGroupId = widgetGroupId;
    }

    /**
     * Create a widget interact task with child ID.
     *
     * @param widgetGroupId the widget group ID
     * @param widgetChildId the widget child ID
     */
    public WidgetInteractTask(int widgetGroupId, int widgetChildId) {
        this.widgetGroupId = widgetGroupId;
        this.widgetChildId = widgetChildId;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a task to press a key.
     *
     * @param keyCode the key code (e.g., KeyEvent.VK_ESCAPE)
     * @return widget task
     */
    public static WidgetInteractTask pressKey(int keyCode) {
        WidgetInteractTask task = new WidgetInteractTask(-1);
        task.keyCode = keyCode;
        task.waitForVisible = false;
        return task;
    }

    /**
     * Create a task to open a specific tab by index.
     * 
     * The task will use hotkeys by default (based on PlayerProfile preference),
     * but can be configured to click the tab icon directly via withForceClick(true).
     *
     * @param tabIndex the tab index (0-13)
     * @return widget task
     */
    public static WidgetInteractTask openTab(int tabIndex) {
        if (tabIndex < 0 || tabIndex > TAB_MUSIC) {
            throw new IllegalArgumentException("Invalid tab index: " + tabIndex);
        }

        // Store both the keyCode (for hotkey mode) and tab info (for click mode)
        int keyCode = WidgetInteractionHelpers.getTabFKey(tabIndex);
        
        WidgetInteractTask task;
        if (keyCode != 0) {
            task = pressKey(keyCode);
        } else {
            // No hotkey available, will need to click
            // Use sentinel values (-1) - actual widget lookup happens at runtime via findTabStoneWidget()
            task = new WidgetInteractTask(-1, -1);
            task.setForceClick(true);  // Must click, no hotkey available
        }
        
        task.setTabIndex(tabIndex);
        task.setDescription("Open tab " + tabIndex);
        return task;
    }

    /**
     * Create a task to open a specific tab by clicking the tab icon.
     * This is useful when explicitly teaching players to click (e.g., Tutorial Island)
     * or when hotkeys are not configured.
     *
     * @param tabIndex the tab index (0-13)
     * @return widget task configured to click the tab icon
     */
    public static WidgetInteractTask openTabByClick(int tabIndex) {
        if (tabIndex < 0 || tabIndex > TAB_MUSIC) {
            throw new IllegalArgumentException("Invalid tab index: " + tabIndex);
        }

        // Use sentinel values (-1) - actual widget lookup happens at runtime via findTabStoneWidget()
        WidgetInteractTask task = new WidgetInteractTask(-1, -1);
        task.setTabIndex(tabIndex);
        task.setForceClick(true);
        task.setDescription("Click tab " + tabIndex);
        return task;
    }

    /**
     * Create a task to open inventory.
     *
     * @return widget task
     */
    public static WidgetInteractTask openInventory() {
        return openTab(TAB_INVENTORY)
                .withDescription("Open inventory")
                .withVerifyWidget(149);
    }

    /**
     * Create a task to open equipment tab.
     *
     * @return widget task
     */
    public static WidgetInteractTask openEquipment() {
        return openTab(TAB_EQUIPMENT)
                .withDescription("Open equipment")
                .withVerifyWidget(387);
    }

    /**
     * Create a task to open skills tab.
     *
     * @return widget task
     */
    public static WidgetInteractTask openSkills() {
        return openTab(TAB_SKILLS)
                .withDescription("Open skills")
                .withVerifyWidget(320);
    }

    /**
     * Create a task to open quest tab.
     *
     * @return widget task
     */
    public static WidgetInteractTask openQuests() {
        return openTab(TAB_QUESTS)
                .withDescription("Open quests")
                .withVerifyWidget(629);
    }

    /**
     * Create a task to open prayer tab.
     *
     * @return widget task
     */
    public static WidgetInteractTask openPrayer() {
        return openTab(TAB_PRAYER)
                .withDescription("Open prayer")
                .withVerifyWidget(541);
    }

    /**
     * Create a task to open spellbook.
     *
     * @return widget task
     */
    public static WidgetInteractTask openSpellbook() {
        return openTab(TAB_SPELLBOOK)
                .withDescription("Open spellbook")
                .withVerifyWidget(218);
    }

    /**
     * Create a task to open combat styles.
     *
     * @return widget task
     */
    public static WidgetInteractTask openCombatStyles() {
        return openTab(TAB_COMBAT)
                .withDescription("Open combat styles")
                .withVerifyWidget(593);
    }

    /**
     * Create a task to open friends list.
     *
     * @return widget task
     */
    public static WidgetInteractTask openFriends() {
        return openTab(TAB_FRIENDS)
                .withDescription("Open friends list")
                .withVerifyWidget(429);
    }

    /**
     * Create a task to open account management.
     *
     * @return widget task
     */
    public static WidgetInteractTask openAccount() {
        return openTab(TAB_ACCOUNT)
                .withDescription("Open account management")
                .withVerifyWidget(109);
    }

    /**
     * Create a task to open settings.
     *
     * @return widget task
     */
    public static WidgetInteractTask openSettings() {
        return openTab(TAB_SETTINGS)
                .withDescription("Open settings")
                .withVerifyWidget(261);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set widget child ID (builder-style).
     *
     * @param childId the child ID
     * @return this task for chaining
     */
    public WidgetInteractTask withChildId(int childId) {
        this.widgetChildId = childId;
        return this;
    }

    /**
     * Set dynamic child index (builder-style).
     *
     * @param index the dynamic child index
     * @return this task for chaining
     */
    public WidgetInteractTask withDynamicChild(int index) {
        this.dynamicChildIndex = index;
        return this;
    }

    /**
     * Set action type (builder-style).
     *
     * @param action the action
     * @return this task for chaining
     */
    public WidgetInteractTask withAction(WidgetAction action) {
        this.action = action;
        return this;
    }

    /**
     * Set key code for keyboard interaction (builder-style).
     *
     * @param keyCode the key code
     * @return this task for chaining
     */
    public WidgetInteractTask withKeyCode(int keyCode) {
        this.keyCode = keyCode;
        return this;
    }

    /**
     * Set whether to wait for widget visibility (builder-style).
     *
     * @param wait true to wait
     * @return this task for chaining
     */
    public WidgetInteractTask withWaitForVisible(boolean wait) {
        this.waitForVisible = wait;
        return this;
    }

    /**
     * Set widget to verify is visible after interaction (builder-style).
     *
     * @param widgetGroup the widget group to verify
     * @return this task for chaining
     */
    public WidgetInteractTask withVerifyWidget(int widgetGroup) {
        this.verifyAfter = true;
        this.verifyWidgetGroup = widgetGroup;
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param desc the description
     * @return this task for chaining
     */
    public WidgetInteractTask withDescription(String desc) {
        this.description = desc;
        return this;
    }

    /**
     * Set force click mode (builder-style).
     * 
     * When true, forces clicking the widget instead of using hotkeys.
     * When false, forces using hotkeys (if available).
     * When null (default), defers to PlayerProfile preference.
     *
     * @param force true to force click, false to force hotkey, null for profile default
     * @return this task for chaining
     */
    public WidgetInteractTask withForceClick(Boolean force) {
        this.forceClick = force;
        return this;
    }

    /**
     * Set drag target widget (builder-style).
     * Required for DRAG action.
     *
     * @param groupId target widget group ID
     * @param childId target widget child ID
     * @return this task for chaining
     */
    public WidgetInteractTask withDragTarget(int groupId, int childId) {
        this.dragTargetGroupId = groupId;
        this.dragTargetChildId = childId;
        this.action = WidgetAction.DRAG;
        return this;
    }

    /**
     * Set drag target widget with dynamic child (builder-style).
     * Required for DRAG action on dynamic children.
     *
     * @param groupId target widget group ID
     * @param childId target widget child ID
     * @param dynamicChildIndex target dynamic child index
     * @return this task for chaining
     */
    public WidgetInteractTask withDragTarget(int groupId, int childId, int dynamicChildIndex) {
        this.dragTargetGroupId = groupId;
        this.dragTargetChildId = childId;
        this.dragTargetDynamicChildIndex = dynamicChildIndex;
        this.action = WidgetAction.DRAG;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.isLoggedIn();
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        // Cache context for interface mode lookup
        this.lastContext = ctx;
        
        if (clickPending || keyPending) {
            return;
        }

        switch (phase) {
            case WAIT_VISIBLE:
                executeWaitVisible(ctx);
                break;
            case INTERACT:
                executeInteract(ctx);
                break;
            case VERIFY:
                executeVerify(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Wait Visible
    // ========================================================================

    private void executeWaitVisible(TaskContext ctx) {
        // If using key press AND we're not forcing click, skip visibility check
        if (keyCode > 0 && shouldUseHotkey(ctx)) {
            phase = InteractionPhase.INTERACT;
            return;
        }

        if (!waitForVisible) {
            phase = InteractionPhase.INTERACT;
            return;
        }

        Client client = ctx.getClient();
        Widget widget = findClickTargetWidget(client);

        if (widget != null && !widget.isHidden()) {
            log.debug("Widget {}:{} is visible", widgetGroupId, widgetChildId);
            phase = InteractionPhase.INTERACT;
            return;
        }

        waitTicks++;
        if (waitTicks > visibilityTimeout) {
            log.warn("Widget {}:{} not visible after {} ticks",
                    widgetGroupId, widgetChildId, visibilityTimeout);
            fail("Widget not visible");
        }
    }

    // ========================================================================
    // Phase: Interact
    // ========================================================================

    private void executeInteract(TaskContext ctx) {
        // Determine if we should use hotkey or click
        boolean shouldUseHotkey = shouldUseHotkey(ctx);

        // Key press interaction (if we should use hotkey and have a keyCode)
        if (shouldUseHotkey && keyCode > 0) {
            performKeyPress(ctx);
            return;
        }

        // Widget click interaction - need to find the correct widget to click
        Client client = ctx.getClient();
        Widget widget = findClickTargetWidget(client);

        if (widget == null || widget.isHidden()) {
            log.warn("Widget {}:{} not found or hidden", widgetGroupId, widgetChildId);
            fail("Widget not found");
            return;
        }

        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            log.warn("Widget {}:{} has invalid bounds", widgetGroupId, widgetChildId);
            fail("Widget has no bounds");
            return;
        }

        Point clickPoint = calculateClickPoint(bounds, ctx.getRandomization());
        log.debug("Clicking widget {}:{} at ({}, {})",
                widgetGroupId, widgetChildId, clickPoint.x, clickPoint.y);

        performClick(ctx, clickPoint, bounds);
    }

    /**
     * Determine if we should use hotkey interaction based on:
     * 1. Explicit forceClick setting (highest priority)
     * 2. PlayerProfile preference (if forceClick is null)
     * 3. Default to hotkeys if profile unavailable
     */
    private boolean shouldUseHotkey(TaskContext ctx) {
        return com.rocinante.util.WidgetInteractionHelpers.shouldUseHotkey(ctx, forceClick);
    }

    /**
     * Find the widget to click. For tab switches, this finds the tab stone icon.
     * This method is called when we've already determined we're clicking (not using hotkey).
     */
    private Widget findClickTargetWidget(Client client) {
        // If this is a tab switch, use the tab stone widget for clicking
        if (tabIndex >= 0 && tabIndex <= TAB_MUSIC) {
            Widget tabWidget = findTabStoneWidget(client, tabIndex);
            if (tabWidget != null) {
                return tabWidget;
            }
            
            // Don't fall back if widgetChildId is invalid - that would crash
            if (widgetChildId < 0) {
                log.warn("Tab stone widget not found for tabIndex={}, and no valid fallback widget (childId={})", 
                        tabIndex, widgetChildId);
                return null;
            }
            
            log.debug("Tab stone widget not found, falling back to default widget");
        }

        // Otherwise use the normal target widget (only if valid)
        if (widgetChildId < 0) {
            log.warn("Cannot find target widget: invalid childId={}", widgetChildId);
            return null;
        }
        return findTargetWidget(client);
    }
    
    /**
     * Find the tab stone widget for a given tab index, using the correct interface mode.
     * Uses GameStateService's cached interface mode.
     */
    private Widget findTabStoneWidget(Client client, int tabIdx) {
        if (lastContext == null) {
            log.warn("No context available for tab stone lookup");
            return null;
        }
        
        int[] ids = com.rocinante.util.WidgetInteractionHelpers.getTabWidgetIds(lastContext, tabIdx);
        Widget widget = client.getWidget(ids[0], ids[1]);
        
        if (widget == null || widget.isHidden()) {
            log.warn("Tab stone widget not found for tabIdx={}", tabIdx);
            return null;
        }
        
            return widget;
    }

    // ========================================================================
    // Phase: Verify
    // ========================================================================

    private void executeVerify(TaskContext ctx) {
        if (!verifyAfter || verifyWidgetGroup < 0) {
            complete();
            return;
        }

        Client client = ctx.getClient();
        Widget verifyWidget = client.getWidget(verifyWidgetGroup, 0);

        if (verifyWidget != null && !verifyWidget.isHidden()) {
            log.debug("Verification widget {} is visible", verifyWidgetGroup);
            complete();
            return;
        }

        waitTicks++;
        if (waitTicks > 5) {
            // Widget didn't appear, but interaction was performed
            log.warn("Verification widget {} not visible, completing anyway", verifyWidgetGroup);
            complete();
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private Widget findTargetWidget(Client client) {
        Widget widget = client.getWidget(widgetGroupId, widgetChildId);

        // Handle dynamic children
        if (widget != null && dynamicChildIndex >= 0) {
            Widget[] children = widget.getDynamicChildren();
            if (children != null && dynamicChildIndex < children.length) {
                return children[dynamicChildIndex];
            }
        }

        return widget;
    }

    private Point calculateClickPoint(Rectangle bounds, Randomization rand) {
        // Random point within bounds, biased toward center (Gaussian)
        double offsetX = rand.gaussianRandom(0, bounds.width * 0.15);
        double offsetY = rand.gaussianRandom(0, bounds.height * 0.15);

        int x = bounds.x + bounds.width / 2 + (int) offsetX;
        int y = bounds.y + bounds.height / 2 + (int) offsetY;

        // Clamp to bounds
        x = Math.max(bounds.x + 2, Math.min(bounds.x + bounds.width - 2, x));
        y = Math.max(bounds.y + 2, Math.min(bounds.y + bounds.height - 2, y));

        return new Point(x, y);
    }

    private void performClick(TaskContext ctx, Point point, Rectangle bounds) {
        clickPending = true;

        // Widget bounds are canvas-relative, so use moveToCanvas
        CompletableFuture<Void> moveFuture = ctx.getMouseController().moveToCanvas(point.x, point.y);

        moveFuture.thenCompose(v -> {
            switch (action) {
                case RIGHT_CLICK:
                    // Pass the bounds for context menu click area
                    return ctx.getMouseController().rightClick(bounds);
                case DOUBLE_CLICK:
                    return ctx.getMouseController().click()
                            .thenCompose(v2 -> ctx.getMouseController().click());
                case HOVER:
                    return CompletableFuture.completedFuture(null);
                case DRAG:
                    return performDrag(ctx, bounds);
                default:
                    return ctx.getMouseController().click();
            }
        }).thenRun(() -> {
            clickPending = false;
            waitTicks = 0;
            if (verifyAfter) {
                phase = InteractionPhase.VERIFY;
            } else {
                complete();
            }
        }).exceptionally(e -> {
            clickPending = false;
            log.error("Widget click failed", e);
            fail("Click failed: " + e.getMessage());
            return null;
        });
    }

    /**
     * Perform a drag operation from current widget to target widget.
     */
    private CompletableFuture<Void> performDrag(TaskContext ctx, Rectangle sourceBounds) {
        // Find target widget
        if (dragTargetGroupId < 0) {
            log.error("DRAG action requires dragTargetGroupId to be set");
            return CompletableFuture.failedFuture(
                    new IllegalStateException("DRAG action requires drag target widget"));
        }

        Client client = ctx.getClient();
        Widget targetWidget = client.getWidget(dragTargetGroupId, dragTargetChildId);

        // Handle dynamic children for target
        if (targetWidget != null && dragTargetDynamicChildIndex >= 0) {
            Widget[] children = targetWidget.getDynamicChildren();
            if (children != null && dragTargetDynamicChildIndex < children.length) {
                targetWidget = children[dragTargetDynamicChildIndex];
            }
        }

        if (targetWidget == null || targetWidget.isHidden()) {
            log.error("Drag target widget {}:{} not found or hidden", 
                    dragTargetGroupId, dragTargetChildId);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Drag target widget not found"));
        }

        Rectangle targetBounds = targetWidget.getBounds();
        if (targetBounds == null || targetBounds.width == 0 || targetBounds.height == 0) {
            log.error("Drag target widget has invalid bounds");
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Drag target has no bounds"));
        }

        log.debug("Performing drag from {}:{} to {}:{}", 
                widgetGroupId, widgetChildId, dragTargetGroupId, dragTargetChildId);

        // Use the drag method from RobotMouseController
        return ctx.getMouseController().drag(sourceBounds, targetBounds);
    }

    private void performKeyPress(TaskContext ctx) {
        keyPending = true;

        ctx.getKeyboardController().pressKey(keyCode)
                .thenRun(() -> {
                    keyPending = false;
                    waitTicks = 0;
                    if (verifyAfter) {
                        phase = InteractionPhase.VERIFY;
                    } else {
                        complete();
                    }
                })
                .exceptionally(e -> {
                    keyPending = false;
                    log.error("Key press failed", e);
                    fail("Key press failed: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        if (keyCode > 0) {
            return "WidgetInteract[key: " + keyCode + "]";
        }
        return String.format("WidgetInteract[%d:%d]", widgetGroupId, widgetChildId);
    }

    // ========================================================================
    // Interaction Phase Enum
    // ========================================================================

    private enum InteractionPhase {
        WAIT_VISIBLE,
        INTERACT,
        VERIFY
    }
}

