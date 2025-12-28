package com.rocinante.tasks.impl.travel;

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
 * Specialized task for spirit tree travel.
 *
 * <p>Handles the spirit tree destination selection interface. Spirit trees
 * provide fast travel between permanent locations across Gielinor.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Completion of Tree Gnome Village quest (for basic access)</li>
 *   <li>Specific destinations may require additional quests/unlocks</li>
 *   <li>Player must already be at a spirit tree object</li>
 * </ul>
 *
 * <p>Available destinations (standard):
 * <ul>
 *   <li>Tree Gnome Village</li>
 *   <li>Tree Gnome Stronghold</li>
 *   <li>Battlefield of Khazard</li>
 *   <li>Grand Exchange</li>
 *   <li>Feldip Hills</li>
 *   <li>Prifddinas (requires Song of the Elves)</li>
 *   <li>Farming Guild (requires 85 Farming)</li>
 *   <li>Player-grown trees (Farming level dependent)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * SpiritTreeTask task = SpiritTreeTask.to("Tree Gnome Village");
 * task.init(ctx);
 * while (task.tick(ctx) == Status.IN_PROGRESS) {
 *     // wait for next game tick
 * }
 * }</pre>
 */
@Slf4j
public class SpiritTreeTask implements TravelSubTask {

    // ========================================================================
    // Widget Constants
    // ========================================================================

    /** Spirit tree widget group. */
    public static final int WIDGET_GROUP = 187;

    /** Container child with destination options. */
    public static final int DESTINATIONS_CHILD = 3;

    // ========================================================================
    // Configuration
    // ========================================================================

    /** Destination name to travel to. */
    @Getter
    private final String destination;

    /** Expected destination for verification. */
    @Getter
    @Setter
    @Nullable
    private WorldPoint expectedDestination;

    /** Tolerance for destination verification in tiles. */
    @Getter
    @Setter
    private int destinationTolerance = 10;

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
        WAIT_FOR_INTERFACE,
        SELECT_DESTINATION,
        WAIT_FOR_TRAVEL,
        VERIFY_ARRIVAL,
        COMPLETED,
        FAILED
    }

    // ========================================================================
    // Constructors
    // ========================================================================

    private SpiritTreeTask(String destination) {
        if (destination == null || destination.isEmpty()) {
            throw new IllegalArgumentException("Spirit tree destination cannot be null or empty");
        }
        this.destination = destination;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a spirit tree task to travel to the specified destination.
     *
     * @param destination the destination name (e.g., "Tree Gnome Village")
     * @return new SpiritTreeTask
     */
    public static SpiritTreeTask to(String destination) {
        return new SpiritTreeTask(destination);
    }

    /**
     * Create a spirit tree task with destination verification.
     *
     * @param destination the destination name
     * @param expectedPoint expected destination point
     * @return new SpiritTreeTask
     */
    public static SpiritTreeTask to(String destination, WorldPoint expectedPoint) {
        SpiritTreeTask task = new SpiritTreeTask(destination);
        task.setExpectedDestination(expectedPoint);
        return task;
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
        log.debug("Initializing SpiritTreeTask for destination: {}", destination);
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (waiting) {
            return Status.IN_PROGRESS;
        }

        switch (phase) {
            case INIT:
                phase = Phase.WAIT_FOR_INTERFACE;
                return Status.IN_PROGRESS;

            case WAIT_FOR_INTERFACE:
                return tickWaitForInterface(ctx);

            case SELECT_DESTINATION:
                return tickSelectDestination(ctx);

            case WAIT_FOR_TRAVEL:
                return tickWaitForTravel(ctx);

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
        return ctx.isLoggedIn();
    }

    @Override
    public String getDescription() {
        return "Spirit tree to " + destination;
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

    private Status tickWaitForInterface(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget spiritTree = client.getWidget(WIDGET_GROUP, 0);

        if (spiritTree != null && !spiritTree.isHidden()) {
            log.debug("Spirit tree interface is open");
            phase = Phase.SELECT_DESTINATION;
            waitTicks = 0;
            return Status.IN_PROGRESS;
        }

        waitTicks++;
        if (waitTicks > 30) {
            failureReason = "Spirit tree interface did not open - ensure you interact with a spirit tree first";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickSelectDestination(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget container = client.getWidget(WIDGET_GROUP, DESTINATIONS_CHILD);

        if (container == null) {
            failureReason = "Spirit tree destination container not found";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        Widget[] children = container.getDynamicChildren();
        if (children == null || children.length == 0) {
            failureReason = "Spirit tree has no destinations";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        // Search for the destination in the children
        for (Widget child : children) {
            String text = child.getText();
            if (text != null && text.toLowerCase().contains(destination.toLowerCase())) {
                log.debug("Found spirit tree destination: {}", destination);
                clickWidget(ctx, child);
                return Status.IN_PROGRESS;
            }
        }

        // Try checking nested children
        for (Widget child : children) {
            Widget[] nestedChildren = child.getChildren();
            if (nestedChildren != null) {
                for (Widget nested : nestedChildren) {
                    if (nested == null) continue;
                    String text = nested.getText();
                    if (text != null && text.toLowerCase().contains(destination.toLowerCase())) {
                        log.debug("Found spirit tree destination (nested): {}", destination);
                        clickWidget(ctx, child); // Click the parent
                        return Status.IN_PROGRESS;
                    }
                }
            }
        }

        failureReason = "Spirit tree destination not found: " + destination;
        phase = Phase.FAILED;
        return Status.FAILED;
    }

    private Status tickWaitForTravel(TaskContext ctx) {
        WorldPoint currentPos = ctx.getPlayerState().getWorldPosition();

        if (currentPos != null && startPosition != null) {
            int distance = currentPos.distanceTo(startPosition);
            if (distance > 5) {
                log.debug("Spirit tree travel completed, moved {} tiles", distance);
                if (expectedDestination != null) {
                    phase = Phase.VERIFY_ARRIVAL;
                } else {
                    phase = Phase.COMPLETED;
                }
                return expectedDestination != null ? Status.IN_PROGRESS : Status.COMPLETED;
            }
        }

        waitTicks++;
        if (waitTicks > 20) {
            failureReason = "Spirit tree travel timed out";
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
            log.debug("Arrived at spirit tree destination (within {} tiles)", distance);
        } else {
            log.warn("Spirit tree destination mismatch: expected {}, got {} (distance: {})",
                    expectedDestination, currentPos, distance);
        }
        // Complete regardless - travel happened
        phase = Phase.COMPLETED;
        return Status.COMPLETED;
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void clickWidget(TaskContext ctx, Widget widget) {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) {
            failureReason = "Widget has invalid bounds";
            phase = Phase.FAILED;
            return;
        }

        var rand = ctx.getRandomization();
        int x = bounds.x + bounds.width / 2 + (int) ((rand.uniformRandom(0, 1) - 0.5) * bounds.width * 0.4);
        int y = bounds.y + bounds.height / 2 + (int) ((rand.uniformRandom(0, 1) - 0.5) * bounds.height * 0.4);

        waiting = true;

        ctx.getMouseController().moveToCanvas(x, y)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    waiting = false;
                    phase = Phase.WAIT_FOR_TRAVEL;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    waiting = false;
                    log.error("Spirit tree click failed", e);
                    failureReason = "Click failed: " + e.getMessage();
                    phase = Phase.FAILED;
                    return null;
                });
    }
}

