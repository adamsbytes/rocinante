package com.rocinante.tasks.impl.travel;

import com.rocinante.navigation.GroupingTeleport;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import javax.annotation.Nullable;
import java.awt.Rectangle;

/**
 * Specialized task for Grouping (minigame) teleports.
 *
 * <p>Handles opening the Grouping interface, selecting a minigame from the dropdown,
 * and clicking the teleport button.
 *
 * <p>Grouping teleports share a 20-minute cooldown and teleport to various minigame locations.
 * The cooldown is tracked via VarPlayer.LAST_MINIGAME_TELEPORT in {@link com.rocinante.state.PlayerState}.
 *
 * <p>Usage:
 * <pre>{@code
 * GroupingTeleportTask task = GroupingTeleportTask.to(GroupingTeleport.PEST_CONTROL);
 * task.init(ctx);
 * while (task.tick(ctx) == Status.IN_PROGRESS) {
 *     // wait for next game tick
 * }
 * }</pre>
 *
 * @see GroupingTeleport
 * @see com.rocinante.progression.UnlockTracker#isGroupingTeleportUnlocked(GroupingTeleport)
 */
@Slf4j
public class GroupingTeleportTask implements TravelSubTask {

    // ========================================================================
    // Widget Constants (from RuneLite InterfaceID.Grouping)
    // ========================================================================

    /** Grouping interface widget group (InterfaceID.GROUPING = 76). */
    public static final int WIDGET_GROUP = GroupingTeleport.GROUPING_WIDGET_GROUP;

    /** Teleport button widget child (InterfaceID.Grouping.TELEPORT = 0x004c_0020 = child 32). */
    public static final int TELEPORT_BUTTON_CHILD = GroupingTeleport.TELEPORT_BUTTON_CHILD;

    /** Dropdown widget child (InterfaceID.Grouping.DROPDOWN_TOP = 0x004c_0006 = child 6). */
    public static final int DROPDOWN_CHILD = GroupingTeleport.DROPDOWN_CHILD;

    /** Dropdown list container child (appears when dropdown is expanded). */
    public static final int DROPDOWN_LIST_CHILD = 7;

    /** Ticks to wait for interface to appear/respond. */
    private static final int INTERFACE_TIMEOUT_TICKS = 30;

    /** Ticks to wait for teleport animation to complete. */
    private static final int TELEPORT_TIMEOUT_TICKS = 30;

    // ========================================================================
    // Configuration
    // ========================================================================

    /** The grouping teleport destination. */
    @Getter
    private final GroupingTeleport teleport;

    /** Expected destination for verification. */
    @Getter
    @Setter
    @Nullable
    private WorldPoint expectedDestination;

    /** Tolerance for destination verification in tiles. */
    @Getter
    @Setter
    private int destinationTolerance = 15; // Grouping teleports can have some variance

    // ========================================================================
    // Execution State
    // ========================================================================

    private Phase phase = Phase.INIT;
    private boolean waiting = false;
    private String failureReason = null;
    private WorldPoint startPosition;
    private int waitTicks = 0;

    // ========================================================================
    // Phases
    // ========================================================================

    private enum Phase {
        INIT,
        OPEN_GROUPING_INTERFACE,
        WAIT_FOR_INTERFACE,
        CLICK_DROPDOWN,
        WAIT_FOR_DROPDOWN,
        SELECT_MINIGAME,
        WAIT_FOR_SELECTION,
        CLICK_TELEPORT,
        WAIT_FOR_TELEPORT,
        VERIFY_ARRIVAL,
        COMPLETED,
        FAILED
    }

    // ========================================================================
    // Constructors
    // ========================================================================

    private GroupingTeleportTask(GroupingTeleport teleport) {
        if (teleport == null) {
            throw new IllegalArgumentException("Grouping teleport cannot be null");
        }
        this.teleport = teleport;
        this.expectedDestination = teleport.getDestination();
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a grouping teleport task for the specified destination.
     *
     * @param teleport the grouping teleport destination
     * @return new GroupingTeleportTask
     */
    public static GroupingTeleportTask to(GroupingTeleport teleport) {
        return new GroupingTeleportTask(teleport);
    }

    /**
     * Create a grouping teleport task by display name.
     *
     * @param displayName the display name (e.g., "Pest Control", "Castle Wars")
     * @return new GroupingTeleportTask, or null if name not found
     */
    @Nullable
    public static GroupingTeleportTask toByName(String displayName) {
        GroupingTeleport teleport = GroupingTeleport.fromDisplayName(displayName);
        if (teleport == null) {
            log.warn("Unknown grouping teleport name: {}", displayName);
            return null;
        }
        return new GroupingTeleportTask(teleport);
    }

    /**
     * Create a grouping teleport task by edge ID.
     *
     * @param edgeId the edge ID (e.g., "pest_control", "castle_wars")
     * @return new GroupingTeleportTask, or null if ID not found
     */
    @Nullable
    public static GroupingTeleportTask toByEdgeId(String edgeId) {
        GroupingTeleport teleport = GroupingTeleport.fromEdgeId(edgeId);
        if (teleport == null) {
            log.warn("Unknown grouping teleport edge ID: {}", edgeId);
            return null;
        }
        return new GroupingTeleportTask(teleport);
    }

    // ========================================================================
    // TravelSubTask Implementation
    // ========================================================================

    @Override
    public void init(TaskContext ctx) {
        phase = Phase.INIT;
        waiting = false;
        failureReason = null;
        waitTicks = 0;
        startPosition = ctx.getPlayerState().getWorldPosition();
        log.debug("Initializing GroupingTeleportTask for: {}", teleport.getDisplayName());
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (waiting) {
            return Status.IN_PROGRESS;
        }

        switch (phase) {
            case INIT:
                phase = Phase.OPEN_GROUPING_INTERFACE;
                return Status.IN_PROGRESS;

            case OPEN_GROUPING_INTERFACE:
                return tickOpenGroupingInterface(ctx);

            case WAIT_FOR_INTERFACE:
                return tickWaitForInterface(ctx);

            case CLICK_DROPDOWN:
                return tickClickDropdown(ctx);

            case WAIT_FOR_DROPDOWN:
                return tickWaitForDropdown(ctx);

            case SELECT_MINIGAME:
                return tickSelectMinigame(ctx);

            case WAIT_FOR_SELECTION:
                return tickWaitForSelection(ctx);

            case CLICK_TELEPORT:
                return tickClickTeleport(ctx);

            case WAIT_FOR_TELEPORT:
                return tickWaitForTeleport(ctx);

            case VERIFY_ARRIVAL:
                return tickVerifyArrival(ctx);

            case COMPLETED:
                return Status.COMPLETED;

            case FAILED:
                return Status.FAILED;

            default:
                failureReason = "Unknown phase: " + phase;
                phase = Phase.FAILED;
                return Status.FAILED;
        }
    }

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (!ctx.isLoggedIn()) {
            return false;
        }
        // Cooldown check is done at the navigation level via UnlockTracker
        // This task assumes the caller has already verified availability
        return true;
    }

    @Override
    public String getDescription() {
        return "Grouping teleport to " + teleport.getDisplayName();
    }

    @Override
    @Nullable
    public String getFailureReason() {
        return failureReason;
    }

    @Override
    public boolean isWaiting() {
        return waiting;
    }

    @Override
    public void reset() {
        phase = Phase.INIT;
        waiting = false;
        failureReason = null;
        waitTicks = 0;
    }

    // ========================================================================
    // Phase Implementations
    // ========================================================================

    private Status tickOpenGroupingInterface(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if interface is already open
        Widget groupingInterface = client.getWidget(WIDGET_GROUP, 0);
        if (groupingInterface != null && !groupingInterface.isHidden()) {
            log.debug("Grouping interface already open");
            phase = Phase.CLICK_DROPDOWN;
            return Status.IN_PROGRESS;
        }

        // Open the Grouping interface via the clan chat tab (F8)
        // Note: Grouping is accessed through the clan chat interface
        log.debug("Opening Grouping interface");
        waiting = true;

        // Use the shared helper which handles hotkey/click based on player preference
        com.rocinante.util.WidgetInteractionHelpers.openTabAsync(ctx, 
                com.rocinante.util.WidgetInteractionHelpers.TAB_CLAN, null)
                .thenRun(() -> {
                    waiting = false;
                    phase = Phase.WAIT_FOR_INTERFACE;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    waiting = false;
                    log.error("Failed to open Grouping interface via tab", e);
                    // Fall back to clicking the button
                    tryClickGroupingButton(ctx);
                    return null;
                });

        return Status.IN_PROGRESS;
    }

    private void tryClickGroupingButton(TaskContext ctx) {
        // The grouping button is typically in the chat-channel tab area
        // Widget group 162, child 38 or similar depending on client version
        Client client = ctx.getClient();
        Widget groupingButton = client.getWidget(162, 38);

        if (groupingButton != null && !groupingButton.isHidden()) {
            Rectangle bounds = groupingButton.getBounds();
            if (bounds != null && bounds.width > 0) {
                java.awt.Point clickPoint = com.rocinante.input.ClickPointCalculator.getGaussianClickPoint(bounds);
                waiting = true;
                ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y)
                        .thenCompose(v -> ctx.getMouseController().click())
                        .thenRun(() -> {
                            waiting = false;
                            phase = Phase.WAIT_FOR_INTERFACE;
                            waitTicks = 0;
                        })
                        .exceptionally(e -> {
                            waiting = false;
                            log.error("Failed to click Grouping button", e);
                            failureReason = "Could not open Grouping interface";
                            phase = Phase.FAILED;
                            return null;
                        });
                return;
            }
        }

        failureReason = "Grouping button not found";
        phase = Phase.FAILED;
    }

    private Status tickWaitForInterface(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget groupingInterface = client.getWidget(WIDGET_GROUP, 0);

        if (groupingInterface != null && !groupingInterface.isHidden()) {
            log.debug("Grouping interface opened");
            phase = Phase.CLICK_DROPDOWN;
            waitTicks = 0;
            return Status.IN_PROGRESS;
        }

        waitTicks++;
        if (waitTicks > INTERFACE_TIMEOUT_TICKS) {
            failureReason = "Grouping interface did not open";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickClickDropdown(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget dropdown = client.getWidget(WIDGET_GROUP, DROPDOWN_CHILD);

        if (dropdown == null || dropdown.isHidden()) {
            failureReason = "Grouping dropdown not found";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        // Check if the correct minigame is already selected
        String currentSelection = dropdown.getText();
        if (currentSelection != null && currentSelection.contains(teleport.getDisplayName())) {
            log.debug("Minigame {} already selected", teleport.getDisplayName());
            phase = Phase.CLICK_TELEPORT;
            return Status.IN_PROGRESS;
        }

        // Click the dropdown to open it
        log.debug("Clicking dropdown to select minigame");
        clickWidget(ctx, dropdown, Phase.WAIT_FOR_DROPDOWN);

        return Status.IN_PROGRESS;
    }

    private Status tickWaitForDropdown(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // Check for dropdown list - it's typically a child widget that becomes visible
        Widget dropdownList = client.getWidget(WIDGET_GROUP, DROPDOWN_LIST_CHILD);
        
        // Alternative: check for the expanded dropdown widget
        if (dropdownList != null && !dropdownList.isHidden()) {
            log.debug("Dropdown list opened");
            phase = Phase.SELECT_MINIGAME;
            waitTicks = 0;
            return Status.IN_PROGRESS;
        }

        // Also check dynamic children of the main widget for dropdown entries
        Widget container = client.getWidget(WIDGET_GROUP, 0);
        if (container != null) {
            Widget[] dynamicChildren = container.getDynamicChildren();
            if (dynamicChildren != null && dynamicChildren.length > 0) {
                // Dropdown might have expanded
                phase = Phase.SELECT_MINIGAME;
                waitTicks = 0;
                return Status.IN_PROGRESS;
            }
        }

        waitTicks++;
        if (waitTicks > INTERFACE_TIMEOUT_TICKS) {
            // Maybe dropdown is already showing entries, proceed
            log.debug("Dropdown wait timeout, proceeding to selection");
            phase = Phase.SELECT_MINIGAME;
            waitTicks = 0;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickSelectMinigame(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget container = client.getWidget(WIDGET_GROUP, 0);

        if (container == null) {
            failureReason = "Grouping container widget not found";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        // Search for the minigame entry in the dropdown
        Widget minigameEntry = findMinigameWidget(container, teleport.getDisplayName());

        if (minigameEntry != null) {
            log.debug("Found minigame entry: {}", teleport.getDisplayName());
            clickWidget(ctx, minigameEntry, Phase.WAIT_FOR_SELECTION);
            return Status.IN_PROGRESS;
        }

        // Try searching in the dropdown list widget specifically
        Widget dropdownList = client.getWidget(WIDGET_GROUP, DROPDOWN_LIST_CHILD);
        if (dropdownList != null) {
            minigameEntry = findMinigameWidget(dropdownList, teleport.getDisplayName());
            if (minigameEntry != null) {
                log.debug("Found minigame entry in dropdown list: {}", teleport.getDisplayName());
                clickWidget(ctx, minigameEntry, Phase.WAIT_FOR_SELECTION);
                return Status.IN_PROGRESS;
            }
        }

        failureReason = "Minigame entry not found: " + teleport.getDisplayName();
        phase = Phase.FAILED;
        return Status.FAILED;
    }

    private Widget findMinigameWidget(Widget parent, String minigameName) {
        if (parent == null) return null;

        String nameLower = minigameName.toLowerCase();

        // Check this widget's text
        String text = parent.getText();
        if (text != null && text.toLowerCase().contains(nameLower)) {
            return parent;
        }

        // Check static children
        Widget[] staticChildren = parent.getStaticChildren();
        if (staticChildren != null) {
            for (Widget child : staticChildren) {
                if (child == null) continue;
                Widget found = findMinigameWidget(child, minigameName);
                if (found != null) return found;
            }
        }

        // Check dynamic children (dropdown entries are often dynamic)
        Widget[] dynamicChildren = parent.getDynamicChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) {
                if (child == null) continue;
                Widget found = findMinigameWidget(child, minigameName);
                if (found != null) return found;
            }
        }

        // Check nested children
        Widget[] nestedChildren = parent.getNestedChildren();
        if (nestedChildren != null) {
            for (Widget child : nestedChildren) {
                if (child == null) continue;
                Widget found = findMinigameWidget(child, minigameName);
                if (found != null) return found;
            }
        }

        return null;
    }

    private Status tickWaitForSelection(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget dropdown = client.getWidget(WIDGET_GROUP, DROPDOWN_CHILD);

        if (dropdown != null) {
            String currentSelection = dropdown.getText();
            if (currentSelection != null && currentSelection.toLowerCase().contains(teleport.getDisplayName().toLowerCase())) {
                log.debug("Minigame {} selected", teleport.getDisplayName());
                phase = Phase.CLICK_TELEPORT;
                waitTicks = 0;
                return Status.IN_PROGRESS;
            }
        }

        waitTicks++;
        if (waitTicks > INTERFACE_TIMEOUT_TICKS) {
            // Proceed anyway - selection might have worked
            log.debug("Selection wait timeout, proceeding to teleport");
            phase = Phase.CLICK_TELEPORT;
            waitTicks = 0;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickClickTeleport(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget teleportButton = client.getWidget(WIDGET_GROUP, TELEPORT_BUTTON_CHILD);

        if (teleportButton == null || teleportButton.isHidden()) {
            failureReason = "Teleport button not found";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        log.debug("Clicking teleport button");
        clickWidget(ctx, teleportButton, Phase.WAIT_FOR_TELEPORT);

        return Status.IN_PROGRESS;
    }

    private Status tickWaitForTeleport(TaskContext ctx) {
        WorldPoint currentPos = ctx.getPlayerState().getWorldPosition();

        if (currentPos != null && startPosition != null) {
            int distance = currentPos.distanceTo(startPosition);
            if (distance > 10) {
                log.debug("Grouping teleport completed, moved {} tiles", distance);
                if (expectedDestination != null) {
                    phase = Phase.VERIFY_ARRIVAL;
                } else {
                    phase = Phase.COMPLETED;
                }
                return expectedDestination != null ? Status.IN_PROGRESS : Status.COMPLETED;
            }
        }

        waitTicks++;
        if (waitTicks > TELEPORT_TIMEOUT_TICKS) {
            failureReason = "Grouping teleport timed out - may be on cooldown";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickVerifyArrival(TaskContext ctx) {
        WorldPoint currentPos = ctx.getPlayerState().getWorldPosition();

        if (currentPos == null) {
            failureReason = "Could not get player position";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        int distance = currentPos.distanceTo(expectedDestination);
        if (distance <= destinationTolerance) {
            log.debug("Arrived at grouping teleport destination (within {} tiles)", distance);
        } else {
            log.warn("Grouping teleport destination mismatch: expected {}, got {} (distance: {})",
                    expectedDestination, currentPos, distance);
            // Still complete since teleport happened, just not where expected
        }
        phase = Phase.COMPLETED;
        return Status.COMPLETED;
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void clickWidget(TaskContext ctx, Widget widget, Phase nextPhase) {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) {
            failureReason = "Widget has invalid bounds";
            phase = Phase.FAILED;
            return;
        }

        java.awt.Point clickPoint = com.rocinante.input.ClickPointCalculator.getGaussianClickPoint(bounds);
        int x = clickPoint.x;
        int y = clickPoint.y;

        waiting = true;

        ctx.getMouseController().moveToCanvas(x, y)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    waiting = false;
                    phase = nextPhase;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    waiting = false;
                    log.error("Widget click failed", e);
                    failureReason = "Click failed: " + e.getMessage();
                    phase = Phase.FAILED;
                    return null;
                });
    }
}

