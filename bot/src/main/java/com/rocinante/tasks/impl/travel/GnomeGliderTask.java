package com.rocinante.tasks.impl.travel;

import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import javax.annotation.Nullable;
import java.awt.Rectangle;

/**
 * Specialized task for gnome glider travel.
 *
 * <p>Handles interaction with gnome glider NPCs and destination selection.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Completion of The Grand Tree quest</li>
 *   <li>Player must be near a gnome glider captain</li>
 * </ul>
 *
 * <p>Available destinations (from RuneLite TransportationPointLocation):
 * <ul>
 *   <li>Ta Quir Priw (Gnome Stronghold) - default hub</li>
 *   <li>Sindarpos (White Wolf Mountain)</li>
 *   <li>Lemanto Andra (Digsite)</li>
 *   <li>Kar-Hewo (Al Kharid)</li>
 *   <li>Gandius (Karamja)</li>
 *   <li>Ookookolly Undri (Ape Atoll) - requires Monkey Madness II</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * GnomeGliderTask task = GnomeGliderTask.to("Karamja");
 * task.init(ctx);
 * while (task.tick(ctx) == Status.IN_PROGRESS) {
 *     // wait for next game tick
 * }
 * }</pre>
 */
@Slf4j
public class GnomeGliderTask implements TravelSubTask {

    // ========================================================================
    // Widget Constants
    // ========================================================================

    /** Gnome glider widget group. */
    public static final int WIDGET_GROUP = 138;

    // ========================================================================
    // NPC Constants
    // ========================================================================

    /** Gnome glider captain NPC IDs. */
    public static final int[] GLIDER_CAPTAIN_IDS = {
        1800, // Captain Errdo (Gnome Stronghold)
        1801, // Captain Dalbur (White Wolf Mountain)
        1802, // Captain Klemfoodle (Digsite)
        1803, // Captain Bleemadge (Al Kharid)
        1804  // Captain Ninto (Karamja)
    };

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
    private NPC targetCaptain;

    // ========================================================================
    // Phases
    // ========================================================================

    private enum Phase {
        INIT,
        FIND_CAPTAIN,
        INTERACT_CAPTAIN,
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

    private GnomeGliderTask(String destination) {
        if (destination == null || destination.isEmpty()) {
            throw new IllegalArgumentException("Gnome glider destination cannot be null or empty");
        }
        this.destination = destination;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a gnome glider task to travel to the specified destination.
     *
     * @param destination the destination name (e.g., "Karamja", "Al Kharid")
     * @return new GnomeGliderTask
     */
    public static GnomeGliderTask to(String destination) {
        return new GnomeGliderTask(destination);
    }

    /**
     * Create a gnome glider task with destination verification.
     *
     * @param destination the destination name
     * @param expectedPoint expected destination point
     * @return new GnomeGliderTask
     */
    public static GnomeGliderTask to(String destination, WorldPoint expectedPoint) {
        GnomeGliderTask task = new GnomeGliderTask(destination);
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
        targetCaptain = null;
        startPosition = ctx.getPlayerState().getWorldPosition();
        log.debug("Initializing GnomeGliderTask for destination: {}", destination);
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (waiting) {
            return Status.IN_PROGRESS;
        }

        switch (phase) {
            case INIT:
                phase = Phase.FIND_CAPTAIN;
                return Status.IN_PROGRESS;

            case FIND_CAPTAIN:
                return tickFindCaptain(ctx);

            case INTERACT_CAPTAIN:
                return tickInteractCaptain(ctx);

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
        return "Glide to " + destination;
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
        targetCaptain = null;
    }

    // ========================================================================
    // Phase Implementations
    // ========================================================================

    private Status tickFindCaptain(TaskContext ctx) {
        Client client = ctx.getClient();

        for (NPC npc : client.getNpcs()) {
            if (npc == null) continue;
            int npcId = npc.getId();
            for (int captainId : GLIDER_CAPTAIN_IDS) {
                if (npcId == captainId) {
                    targetCaptain = npc;
                    log.debug("Found gnome glider captain: {}", npc.getName());
                    phase = Phase.INTERACT_CAPTAIN;
                    return Status.IN_PROGRESS;
                }
            }
        }

        waitTicks++;
        if (waitTicks > 10) {
            failureReason = "Gnome glider captain not found nearby";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickInteractCaptain(TaskContext ctx) {
        if (targetCaptain == null) {
            failureReason = "No target captain set";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        // Click the captain with "Glider" action
        var convexHull = targetCaptain.getConvexHull();
        if (convexHull == null) {
            failureReason = "Captain has no clickable area";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        Rectangle bounds = convexHull.getBounds();
        log.debug("Interacting with gnome glider captain");

        waiting = true;
        ctx.getMenuHelper().selectMenuEntry(bounds, "Glider")
                .thenAccept(success -> {
                    waiting = false;
                    if (success) {
                        phase = Phase.WAIT_FOR_INTERFACE;
                        waitTicks = 0;
                    } else {
                        // Try "Talk-to" as fallback
                        ctx.getMenuHelper().selectMenuEntry(bounds, "Talk-to")
                                .thenAccept(talkSuccess -> {
                                    if (talkSuccess) {
                                        phase = Phase.WAIT_FOR_INTERFACE;
                                        waitTicks = 0;
                                    } else {
                                        failureReason = "Failed to interact with gnome glider captain";
                                        phase = Phase.FAILED;
                                    }
                                });
                    }
                })
                .exceptionally(e -> {
                    waiting = false;
                    log.error("Gnome glider captain interaction failed", e);
                    failureReason = "Interaction failed: " + e.getMessage();
                    phase = Phase.FAILED;
                    return null;
                });

        return Status.IN_PROGRESS;
    }

    private Status tickWaitForInterface(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget gliderInterface = client.getWidget(WIDGET_GROUP, 0);

        if (gliderInterface != null && !gliderInterface.isHidden()) {
            log.debug("Gnome glider interface is open");
            phase = Phase.SELECT_DESTINATION;
            waitTicks = 0;
            return Status.IN_PROGRESS;
        }

        waitTicks++;
        if (waitTicks > 30) {
            failureReason = "Gnome glider interface did not open";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickSelectDestination(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget container = client.getWidget(WIDGET_GROUP, 0);

        if (container == null) {
            failureReason = "Gnome glider container not found";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        // Search through all children for the destination
        Widget destinationWidget = findDestinationWidget(container, destination);

        if (destinationWidget != null) {
            log.debug("Found gnome glider destination: {}", destination);
            clickWidget(ctx, destinationWidget);
            return Status.IN_PROGRESS;
        }

        failureReason = "Gnome glider destination not found: " + destination;
        phase = Phase.FAILED;
        return Status.FAILED;
    }

    private Widget findDestinationWidget(Widget parent, String dest) {
        String destLower = dest.toLowerCase();

        // Check this widget's text
        String text = parent.getText();
        if (text != null && text.toLowerCase().contains(destLower)) {
            return parent;
        }

        // Check static children
        Widget[] staticChildren = parent.getStaticChildren();
        if (staticChildren != null) {
            for (Widget child : staticChildren) {
                if (child == null) continue;
                Widget found = findDestinationWidget(child, dest);
                if (found != null) return found;
            }
        }

        // Check dynamic children
        Widget[] dynamicChildren = parent.getDynamicChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) {
                if (child == null) continue;
                Widget found = findDestinationWidget(child, dest);
                if (found != null) return found;
            }
        }

        // Check nested children
        Widget[] nestedChildren = parent.getNestedChildren();
        if (nestedChildren != null) {
            for (Widget child : nestedChildren) {
                if (child == null) continue;
                Widget found = findDestinationWidget(child, dest);
                if (found != null) return found;
            }
        }

        return null;
    }

    private Status tickWaitForTravel(TaskContext ctx) {
        WorldPoint currentPos = ctx.getPlayerState().getWorldPosition();

        if (currentPos != null && startPosition != null) {
            int distance = currentPos.distanceTo(startPosition);
            if (distance > 20) { // Gliders travel far
                log.debug("Gnome glider travel completed, moved {} tiles", distance);
                if (expectedDestination != null) {
                    phase = Phase.VERIFY_ARRIVAL;
                } else {
                    phase = Phase.COMPLETED;
                }
                return expectedDestination != null ? Status.IN_PROGRESS : Status.COMPLETED;
            }
        }

        waitTicks++;
        if (waitTicks > 30) {
            failureReason = "Gnome glider travel timed out";
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
            log.debug("Arrived at gnome glider destination (within {} tiles)", distance);
        } else {
            log.warn("Gnome glider destination mismatch: expected {}, got {} (distance: {})",
                    expectedDestination, currentPos, distance);
        }
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
                    log.error("Gnome glider click failed", e);
                    failureReason = "Click failed: " + e.getMessage();
                    phase = Phase.FAILED;
                    return null;
                });
    }
}

