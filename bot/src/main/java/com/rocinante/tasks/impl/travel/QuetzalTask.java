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
 * Specialized task for Quetzal transport in Varlamore.
 *
 * <p>Quetzals are NPC-based transport. There are two types:
 * <ul>
 *   <li>Varrock Quetzal: One-way transport TO Varlamore (click NPC at Varrock)</li>
 *   <li>Varlamore Quetzals: Travel within the network (click NPCs at landing sites)</li>
 * </ul>
 *
 * <p>Requirements:
 * <ul>
 *   <li>Completion of "Twilight's Promise" quest for Quetzal access</li>
 *   <li>Player must be near a Quetzal NPC</li>
 * </ul>
 *
 * <p>Available destinations (from RuneLite TransportationPointLocation):
 * <ul>
 *   <li>Aldarin (1388, 2899)</li>
 *   <li>Auburnvale (1410, 3363)</li>
 *   <li>Avium Savannah North East (1701, 3037)</li>
 *   <li>Avium Savannah South (1671, 2933)</li>
 *   <li>Cam Torum (1447, 3108)</li>
 *   <li>East Civitas illa Fortis (1776, 3111)</li>
 *   <li>Hunter Guild (1584, 3055)</li>
 *   <li>Kastori (1343, 3020)</li>
 *   <li>Quetzacalli Gorge (1511, 3222)</li>
 *   <li>Salvager Overlook (1612, 3302)</li>
 *   <li>Sunset Coast (1547, 2997)</li>
 *   <li>Ralos Rise (1436, 3169)</li>
 *   <li>Tal Teklan (1225, 3089)</li>
 *   <li>Varrock (bidirectional with 1699, 3142)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * QuetzalTask task = QuetzalTask.to("Cam Torum");
 * task.init(ctx);
 * while (task.tick(ctx) == Status.IN_PROGRESS) {
 *     // wait for next game tick
 * }
 * }</pre>
 */
@Slf4j
public class QuetzalTask implements TravelSubTask {

    // ========================================================================
    // Widget Constants (from RuneLite InterfaceID.QuetzalMenu)
    // ========================================================================

    /** Quetzal menu widget group (0x036a = 874 decimal). */
    public static final int WIDGET_GROUP = 874;

    /** Contents child with destination options. */
    public static final int CONTENTS_CHILD = 1;

    /** Icons child for destination icons. */
    public static final int ICONS_CHILD = 12;

    // ========================================================================
    // NPC Constants
    // ========================================================================

    /** Quetzal NPC ID (from RuneLite NpcID). */
    public static final int QUETZAL_NPC_ID = 12876;

    /** Alternative Quetzal NPC ID. */
    public static final int QUETZAL_NPC_ID_ALT = 13133;

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
    private int destinationTolerance = 15;

    // ========================================================================
    // Execution State
    // ========================================================================

    private Phase phase = Phase.INIT;
    private boolean waiting = false;
    private String failureReason = null;
    private WorldPoint startPosition;
    private int waitTicks = 0;
    private NPC targetQuetzal;

    // ========================================================================
    // Phases
    // ========================================================================

    private enum Phase {
        INIT,
        FIND_QUETZAL,
        INTERACT_QUETZAL,
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

    private QuetzalTask(String destination) {
        if (destination == null || destination.isEmpty()) {
            throw new IllegalArgumentException("Quetzal destination cannot be null or empty");
        }
        this.destination = destination;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a quetzal task to travel to the specified destination.
     *
     * @param destination the destination name (e.g., "Cam Torum", "Hunter Guild")
     * @return new QuetzalTask
     */
    public static QuetzalTask to(String destination) {
        return new QuetzalTask(destination);
    }

    /**
     * Create a quetzal task with destination verification.
     *
     * @param destination the destination name
     * @param expectedPoint expected destination point
     * @return new QuetzalTask
     */
    public static QuetzalTask to(String destination, WorldPoint expectedPoint) {
        QuetzalTask task = new QuetzalTask(destination);
        task.setExpectedDestination(expectedPoint);
        return task;
    }

    /**
     * Create a quetzal task to travel from Varrock to Varlamore.
     * This is the entry point into the Varlamore quetzal network.
     *
     * @return new QuetzalTask for Varrock->Varlamore
     */
    public static QuetzalTask toVarlamore() {
        QuetzalTask task = new QuetzalTask("Varlamore");
        task.setExpectedDestination(new WorldPoint(1699, 3142, 0));
        return task;
    }

    /**
     * Create a quetzal task to travel from Varlamore to Varrock.
     *
     * @return new QuetzalTask for Varlamore->Varrock
     */
    public static QuetzalTask toVarrock() {
        QuetzalTask task = new QuetzalTask("Varrock");
        task.setExpectedDestination(new WorldPoint(3279, 3413, 0));
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
        targetQuetzal = null;
        startPosition = ctx.getPlayerState().getWorldPosition();
        log.debug("Initializing QuetzalTask for destination: {}", destination);
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (waiting) {
            return Status.IN_PROGRESS;
        }

        switch (phase) {
            case INIT:
                phase = Phase.FIND_QUETZAL;
                return Status.IN_PROGRESS;

            case FIND_QUETZAL:
                return tickFindQuetzal(ctx);

            case INTERACT_QUETZAL:
                return tickInteractQuetzal(ctx);

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
        // Could check for Twilight's Promise quest completion here
        return ctx.isLoggedIn();
    }

    @Override
    public String getDescription() {
        return "Quetzal to " + destination;
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
        targetQuetzal = null;
    }

    // ========================================================================
    // Phase Implementations
    // ========================================================================

    private Status tickFindQuetzal(TaskContext ctx) {
        Client client = ctx.getClient();

        for (NPC npc : client.getNpcs()) {
            if (npc == null) continue;
            
            int npcId = npc.getId();
            if (npcId == QUETZAL_NPC_ID || npcId == QUETZAL_NPC_ID_ALT) {
                targetQuetzal = npc;
                log.debug("Found Quetzal NPC: {} (ID: {})", npc.getName(), npcId);
                phase = Phase.INTERACT_QUETZAL;
                return Status.IN_PROGRESS;
            }
            
            // Fallback: check by name
            String name = npc.getName();
            if (name != null && name.toLowerCase().contains("quetzal")) {
                targetQuetzal = npc;
                log.debug("Found Quetzal NPC by name: {}", name);
                phase = Phase.INTERACT_QUETZAL;
                return Status.IN_PROGRESS;
            }
        }

        waitTicks++;
        if (waitTicks > 10) {
            failureReason = "Quetzal NPC not found nearby";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickInteractQuetzal(TaskContext ctx) {
        if (targetQuetzal == null) {
            failureReason = "No target Quetzal set";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        var convexHull = targetQuetzal.getConvexHull();
        if (convexHull == null) {
            failureReason = "Quetzal has no clickable area";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        Rectangle bounds = convexHull.getBounds();
        log.debug("Interacting with Quetzal NPC");

        waiting = true;
        ctx.getMenuHelper().selectMenuEntry(bounds, "Travel")
                .thenAccept(success -> {
                    waiting = false;
                    if (success) {
                        phase = Phase.WAIT_FOR_INTERFACE;
                        waitTicks = 0;
                    } else {
                        // Try "Fly" as alternative action
                        ctx.getMenuHelper().selectMenuEntry(bounds, "Fly")
                                .thenAccept(flySuccess -> {
                                    if (flySuccess) {
                                        phase = Phase.WAIT_FOR_INTERFACE;
                                        waitTicks = 0;
                                    } else {
                                        failureReason = "Failed to interact with Quetzal";
                                        phase = Phase.FAILED;
                                    }
                                });
                    }
                })
                .exceptionally(e -> {
                    waiting = false;
                    log.error("Quetzal interaction failed", e);
                    failureReason = "Interaction failed: " + e.getMessage();
                    phase = Phase.FAILED;
                    return null;
                });

        return Status.IN_PROGRESS;
    }

    private Status tickWaitForInterface(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget quetzalInterface = client.getWidget(WIDGET_GROUP, 0);

        if (quetzalInterface != null && !quetzalInterface.isHidden()) {
            log.debug("Quetzal interface is open");
            phase = Phase.SELECT_DESTINATION;
            waitTicks = 0;
            return Status.IN_PROGRESS;
        }

        // Check if we were transported directly (no interface for Varrock<->Varlamore)
        WorldPoint currentPos = ctx.getPlayerState().getWorldPosition();
        if (currentPos != null && startPosition != null) {
            int distance = currentPos.distanceTo(startPosition);
            if (distance > 30) {
                log.debug("Direct quetzal transport detected, moved {} tiles", distance);
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
            failureReason = "Quetzal interface did not open";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickSelectDestination(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget container = client.getWidget(WIDGET_GROUP, CONTENTS_CHILD);

        if (container == null) {
            // Try icons child
            container = client.getWidget(WIDGET_GROUP, ICONS_CHILD);
        }

        if (container == null) {
            failureReason = "Quetzal destination container not found";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        // Search for destination
        Widget destinationWidget = findDestinationWidget(container, destination);

        if (destinationWidget != null) {
            log.debug("Found Quetzal destination: {}", destination);
            clickWidget(ctx, destinationWidget);
            return Status.IN_PROGRESS;
        }

        failureReason = "Quetzal destination not found: " + destination;
        phase = Phase.FAILED;
        return Status.FAILED;
    }

    private Widget findDestinationWidget(Widget parent, String dest) {
        String destLower = dest.toLowerCase();

        String text = parent.getText();
        if (text != null && text.toLowerCase().contains(destLower)) {
            return parent;
        }

        // Check name property as well
        String name = parent.getName();
        if (name != null && name.toLowerCase().contains(destLower)) {
            return parent;
        }

        Widget[] dynamicChildren = parent.getDynamicChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) {
                if (child == null) continue;
                Widget found = findDestinationWidget(child, dest);
                if (found != null) return found;
            }
        }

        Widget[] staticChildren = parent.getStaticChildren();
        if (staticChildren != null) {
            for (Widget child : staticChildren) {
                if (child == null) continue;
                Widget found = findDestinationWidget(child, dest);
                if (found != null) return found;
            }
        }

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
            if (distance > 30) { // Quetzals travel far
                log.debug("Quetzal travel completed, moved {} tiles", distance);
                if (expectedDestination != null) {
                    phase = Phase.VERIFY_ARRIVAL;
                } else {
                    phase = Phase.COMPLETED;
                }
                return expectedDestination != null ? Status.IN_PROGRESS : Status.COMPLETED;
            }
        }

        waitTicks++;
        if (waitTicks > 40) {
            failureReason = "Quetzal travel timed out";
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
            log.debug("Arrived at Quetzal destination (within {} tiles)", distance);
        } else {
            log.warn("Quetzal destination mismatch: expected {}, got {} (distance: {})",
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
                    log.error("Quetzal click failed", e);
                    failureReason = "Click failed: " + e.getMessage();
                    phase = Phase.FAILED;
                    return null;
                });
    }
}

