package com.rocinante.tasks.impl;

import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.timing.DelayProfile;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;

/**
 * Reusable task for digging with a spade at a specific location.
 *
 * <p>This task can be used for:
 * <ul>
 *   <li>Quest requirements (e.g., DigStep in various quests)</li>
 *   <li>Treasure Trails / Clue Scrolls (dig clues)</li>
 *   <li>Any activity requiring spade use at a location</li>
 * </ul>
 *
 * <p>The task handles:
 * <ul>
 *   <li>Walking to the dig location if not already there</li>
 *   <li>Finding and clicking the spade in inventory</li>
 *   <li>Waiting for the dig animation</li>
 *   <li>Optional verification of dig success</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Dig at a specific location
 * DigTask digTask = new DigTask(new WorldPoint(3200, 3200, 0));
 *
 * // Dig with custom spade ID (e.g., for quest-specific spade variants)
 * DigTask digTask = new DigTask(digLocation)
 *     .withSpadeId(QUEST_SPADE_ID);
 *
 * // Dig without walking (already at location)
 * DigTask digTask = new DigTask(null)
 *     .withDescription("Dig here");
 * }</pre>
 */
@Slf4j
public class DigTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Standard spade item ID.
     */
    public static final int SPADE_ID = 952;

    /**
     * Inventory widget group ID.
     */
    private static final int INVENTORY_GROUP_ID = 149;

    /**
     * Inventory container child ID.
     */
    private static final int INVENTORY_CHILD_ID = 0;

    /**
     * Inventory container ID for item lookup.
     */
    private static final int INVENTORY_CONTAINER_ID = 93;

    /**
     * Digging animation ID.
     */
    private static final int DIG_ANIMATION_ID = 830;

    /**
     * Distance threshold to be considered "at" the dig location.
     */
    private static final int AT_LOCATION_DISTANCE = 0;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The location to dig at. May be null if player is already at the location.
     */
    @Getter
    private final WorldPoint digLocation;

    /**
     * The spade item ID to use.
     */
    @Getter
    @Setter
    private int spadeId = SPADE_ID;

    /**
     * Alternative spade item IDs (for quest-specific variants).
     */
    @Getter
    @Setter
    private int[] alternateSpadeIds;

    /**
     * Whether to walk to the dig location first.
     */
    @Getter
    @Setter
    private boolean walkToLocation = true;

    /**
     * Whether to wait for the dig animation to complete.
     */
    @Getter
    @Setter
    private boolean waitForAnimation = true;

    /**
     * Custom description.
     */
    @Setter
    private String description;

    // ========================================================================
    // Execution State
    // ========================================================================

    private DigPhase phase = DigPhase.CHECK_LOCATION;
    private boolean actionPending = false;
    private int waitTicks = 0;
    private int spadeSlot = -1;
    
    /**
     * Sub-task for walking to dig location.
     */
    private WalkToTask walkSubTask;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a dig task at a specific location.
     *
     * @param digLocation the location to dig at (may be null if already there)
     */
    public DigTask(WorldPoint digLocation) {
        this.digLocation = digLocation;
    }

    /**
     * Factory method to create a dig task at a location.
     *
     * @param x the world X coordinate
     * @param y the world Y coordinate
     * @param plane the world plane
     * @return new DigTask
     */
    public static DigTask at(int x, int y, int plane) {
        return new DigTask(new WorldPoint(x, y, plane));
    }

    /**
     * Factory method for digging at current location.
     *
     * @return new DigTask that digs at current position
     */
    public static DigTask here() {
        DigTask task = new DigTask(null);
        task.walkToLocation = false;
        return task;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set the spade item ID (builder-style).
     *
     * @param spadeId the spade item ID
     * @return this task for chaining
     */
    public DigTask withSpadeId(int spadeId) {
        this.spadeId = spadeId;
        return this;
    }

    /**
     * Set alternative spade IDs (builder-style).
     *
     * @param ids alternative spade item IDs
     * @return this task for chaining
     */
    public DigTask withAlternateSpadeIds(int... ids) {
        this.alternateSpadeIds = ids;
        return this;
    }

    /**
     * Set whether to walk to location (builder-style).
     *
     * @param walk true to walk first
     * @return this task for chaining
     */
    public DigTask withWalkToLocation(boolean walk) {
        this.walkToLocation = walk;
        return this;
    }

    /**
     * Set whether to wait for animation (builder-style).
     *
     * @param wait true to wait for dig animation
     * @return this task for chaining
     */
    public DigTask withWaitForAnimation(boolean wait) {
        this.waitForAnimation = wait;
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param desc the description
     * @return this task for chaining
     */
    public DigTask withDescription(String desc) {
        this.description = desc;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (!ctx.isLoggedIn()) {
            return false;
        }

        // Check if we have a spade
        return hasSpade(ctx.getClient());
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (actionPending) {
            return;
        }

        // Handle walk sub-task execution
        if (walkSubTask != null) {
            executeWalkSubTask(ctx);
            return;
        }

        switch (phase) {
            case CHECK_LOCATION:
                executeCheckLocation(ctx);
                break;
            case WALK_TO_LOCATION:
                executeWalkToLocation(ctx);
                break;
            case FIND_SPADE:
                executeFindSpade(ctx);
                break;
            case CLICK_SPADE:
                executeClickSpade(ctx);
                break;
            case WAIT_ANIMATION:
                executeWaitAnimation(ctx);
                break;
        }
    }

    private void executeWalkSubTask(TaskContext ctx) {
        walkSubTask.execute(ctx);

        if (walkSubTask.getState().isTerminal()) {
            if (walkSubTask.getState() == TaskState.COMPLETED) {
                log.debug("Arrived at dig location");
                phase = DigPhase.FIND_SPADE;
            } else {
                log.warn("Failed to walk to dig location: {}", walkSubTask.getFailureReason());
                fail("Failed to walk to dig location");
            }
            walkSubTask = null;
        }
    }

    // ========================================================================
    // Phase: Check Location
    // ========================================================================

    private void executeCheckLocation(TaskContext ctx) {
        if (!walkToLocation || digLocation == null) {
            // Skip walking, go straight to finding spade
            phase = DigPhase.FIND_SPADE;
            return;
        }

        Client client = ctx.getClient();
        WorldPoint playerLocation = client.getLocalPlayer().getWorldLocation();

        if (playerLocation.distanceTo(digLocation) <= AT_LOCATION_DISTANCE) {
            log.debug("Already at dig location: {}", digLocation);
            phase = DigPhase.FIND_SPADE;
        } else {
            log.debug("Need to walk to dig location: {} (current: {})", digLocation, playerLocation);
            phase = DigPhase.WALK_TO_LOCATION;
        }
    }

    // ========================================================================
    // Phase: Walk to Location
    // ========================================================================

    private void executeWalkToLocation(TaskContext ctx) {
        // Create walk sub-task to handle walking
        walkSubTask = new WalkToTask(digLocation)
                .withDescription("Walk to dig location");
        log.debug("Starting walk to dig location: {}", digLocation);
    }

    // ========================================================================
    // Phase: Find Spade
    // ========================================================================

    private void executeFindSpade(TaskContext ctx) {
        Client client = ctx.getClient();

        spadeSlot = findSpadeSlot(client);
        if (spadeSlot < 0) {
            log.warn("No spade found in inventory");
            fail("No spade in inventory");
            return;
        }

        log.debug("Found spade in slot {}", spadeSlot);
        phase = DigPhase.CLICK_SPADE;
    }

    /**
     * Find the inventory slot containing a spade.
     */
    private int findSpadeSlot(Client client) {
        ItemContainer inventory = client.getItemContainer(INVENTORY_CONTAINER_ID);
        if (inventory == null) {
            return -1;
        }

        Item[] items = inventory.getItems();
        for (int i = 0; i < items.length; i++) {
            Item item = items[i];
            if (item == null) continue;

            int itemId = item.getId();
            if (itemId == spadeId) {
                return i;
            }

            // Check alternates
            if (alternateSpadeIds != null) {
                for (int altId : alternateSpadeIds) {
                    if (itemId == altId) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    /**
     * Check if player has a spade in inventory.
     */
    private boolean hasSpade(Client client) {
        return findSpadeSlot(client) >= 0;
    }

    // ========================================================================
    // Phase: Click Spade
    // ========================================================================

    private void executeClickSpade(TaskContext ctx) {
        Client client = ctx.getClient();

        Widget inventoryWidget = client.getWidget(INVENTORY_GROUP_ID, INVENTORY_CHILD_ID);
        if (inventoryWidget == null || inventoryWidget.isHidden()) {
            // Need to open inventory first using shared helper (handles already-open check internally)
            log.debug("Opening inventory to access spade");
            actionPending = true;
            com.rocinante.util.WidgetInteractionHelpers.openTabAsync(ctx, 
                    com.rocinante.util.WidgetInteractionHelpers.TAB_INVENTORY, null)
                    .thenRun(() -> actionPending = false)
                    .exceptionally(e -> {
                        actionPending = false;
                        return null;
                    });
            return;
        }

        Widget[] children = inventoryWidget.getDynamicChildren();
        if (children == null || spadeSlot >= children.length) {
            log.warn("Could not find spade widget at slot {}", spadeSlot);
            fail("Spade widget not found");
            return;
        }

        Widget spadeWidget = children[spadeSlot];
        if (spadeWidget == null || spadeWidget.isHidden()) {
            log.warn("Spade widget is null or hidden");
            fail("Spade not visible");
            return;
        }

        Rectangle bounds = spadeWidget.getBounds();
        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            log.warn("Spade widget has invalid bounds");
            fail("Spade not clickable");
            return;
        }

        // Calculate humanized click point
        Point clickPoint = calculateClickPoint(bounds, ctx.getRandomization());
        log.debug("Clicking spade at ({}, {})", clickPoint.x, clickPoint.y);

        actionPending = true;

        // Hover briefly then click
        long hoverDelay = ctx.getHumanTimer().getDelay(DelayProfile.REACTION);

        ctx.getHumanTimer().sleep(hoverDelay)
                .thenCompose(v -> ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y))
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    actionPending = false;
                    waitTicks = 0;
                    if (waitForAnimation) {
                        phase = DigPhase.WAIT_ANIMATION;
                    } else {
                        log.info("Dig action initiated at {}", digLocation);
                        complete();
                    }
                })
                .exceptionally(e -> {
                    actionPending = false;
                    log.error("Failed to click spade", e);
                    fail("Failed to dig: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait for Animation
    // ========================================================================

    private void executeWaitAnimation(TaskContext ctx) {
        Client client = ctx.getClient();

        int animationId = client.getLocalPlayer().getAnimation();

        if (animationId == DIG_ANIMATION_ID) {
            // Currently digging, wait
            waitTicks = 0;
            return;
        }

        if (animationId != -1) {
            // Some other animation, wait
            waitTicks++;
            if (waitTicks > 10) {
                // Probably finished, complete
                log.info("Dig completed at {}", digLocation);
                complete();
            }
            return;
        }

        // No animation - either finished or didn't start
        waitTicks++;
        if (waitTicks > 5) {
            // Give time for animation to start/finish
            log.info("Dig action completed at {}", digLocation);
            complete();
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private Point calculateClickPoint(Rectangle bounds, Randomization rand) {
        double offsetX = rand.gaussianRandom(0, bounds.width * 0.15);
        double offsetY = rand.gaussianRandom(0, bounds.height * 0.15);

        int x = bounds.x + bounds.width / 2 + (int) offsetX;
        int y = bounds.y + bounds.height / 2 + (int) offsetY;

        // Clamp to bounds
        x = Math.max(bounds.x + 2, Math.min(bounds.x + bounds.width - 2, x));
        y = Math.max(bounds.y + 2, Math.min(bounds.y + bounds.height - 2, y));

        return new Point(x, y);
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        if (digLocation != null) {
            return String.format("DigTask[%d,%d,%d]",
                    digLocation.getX(), digLocation.getY(), digLocation.getPlane());
        }
        return "DigTask[current location]";
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum DigPhase {
        CHECK_LOCATION,
        WALK_TO_LOCATION,
        FIND_SPADE,
        CLICK_SPADE,
        WAIT_ANIMATION
    }
}

