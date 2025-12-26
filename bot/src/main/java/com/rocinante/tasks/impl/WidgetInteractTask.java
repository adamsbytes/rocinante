package com.rocinante.tasks.impl;

import com.rocinante.state.WorldState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

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

    // Tab indices (for keyboard shortcuts)
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

    // F-key mappings
    private static final int[] TAB_FKEYS = {
            java.awt.event.KeyEvent.VK_F1,      // Combat
            java.awt.event.KeyEvent.VK_F2,      // Skills
            java.awt.event.KeyEvent.VK_F3,      // Quests
            java.awt.event.KeyEvent.VK_ESCAPE,  // Inventory (ESC)
            java.awt.event.KeyEvent.VK_F4,      // Equipment
            java.awt.event.KeyEvent.VK_F5,      // Prayer
            java.awt.event.KeyEvent.VK_F6,      // Spellbook
            java.awt.event.KeyEvent.VK_F7,      // Clan
            java.awt.event.KeyEvent.VK_F8,      // Friends
            java.awt.event.KeyEvent.VK_F9,      // Account
            java.awt.event.KeyEvent.VK_F10,     // Logout
            java.awt.event.KeyEvent.VK_F11,     // Settings
            java.awt.event.KeyEvent.VK_F12,     // Emotes
            0                                    // Music (no default)
    };

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
        DOUBLE_CLICK
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
        this.timeout = Duration.ofSeconds(30);
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
        this.timeout = Duration.ofSeconds(30);
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
     * @param tabIndex the tab index (0-13)
     * @return widget task
     */
    public static WidgetInteractTask openTab(int tabIndex) {
        if (tabIndex < 0 || tabIndex >= TAB_FKEYS.length) {
            throw new IllegalArgumentException("Invalid tab index: " + tabIndex);
        }

        int keyCode = TAB_FKEYS[tabIndex];
        if (keyCode == 0) {
            throw new IllegalArgumentException("No key binding for tab: " + tabIndex);
        }

        WidgetInteractTask task = pressKey(keyCode);
        task.setDescription("Open tab " + tabIndex);
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

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.isLoggedIn();
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
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
        // If using key press, skip visibility check
        if (keyCode > 0) {
            phase = InteractionPhase.INTERACT;
            return;
        }

        if (!waitForVisible) {
            phase = InteractionPhase.INTERACT;
            return;
        }

        Client client = ctx.getClient();
        Widget widget = findTargetWidget(client);

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
        // Key press interaction
        if (keyCode > 0) {
            performKeyPress(ctx);
            return;
        }

        // Widget click interaction
        Client client = ctx.getClient();
        Widget widget = findTargetWidget(client);

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

        Point clickPoint = calculateClickPoint(bounds);
        log.debug("Clicking widget {}:{} at ({}, {})",
                widgetGroupId, widgetChildId, clickPoint.x, clickPoint.y);

        performClick(ctx, clickPoint, bounds);
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

    private Point calculateClickPoint(Rectangle bounds) {
        // Random point within bounds, biased toward center (Gaussian)
        double offsetX = (Math.random() - 0.5) * bounds.width * 0.6;
        double offsetY = (Math.random() - 0.5) * bounds.height * 0.6;

        int x = bounds.x + bounds.width / 2 + (int) offsetX;
        int y = bounds.y + bounds.height / 2 + (int) offsetY;

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

