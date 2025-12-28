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
 * Specialized task for charter ship travel.
 *
 * <p>Handles interaction with Trader Crewmembers and destination/payment selection.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Gold coins for the fare (varies by destination)</li>
 *   <li>Player must be near a charter ship port</li>
 *   <li>Ring of Charos (a) reduces fares by 50%</li>
 * </ul>
 *
 * <p>Available ports (from RuneLite TransportationPointLocation):
 * <ul>
 *   <li>Port Sarim, Port Phasmatys, Port Tyras, Brimhaven</li>
 *   <li>Catherby, Musa Point, Corsair Cove, Mos Le'Harmless</li>
 *   <li>Shipyard, Land's End, Port Khazard, Prifddinas</li>
 *   <li>And more...</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * CharterShipTask task = CharterShipTask.to("Port Sarim", 1600);
 * task.init(ctx);
 * while (task.tick(ctx) == Status.IN_PROGRESS) {
 *     // wait for next game tick
 * }
 * }</pre>
 */
@Slf4j
public class CharterShipTask implements TravelSubTask {

    // ========================================================================
    // Widget Constants
    // ========================================================================

    /** Charter ship widget group. */
    public static final int WIDGET_GROUP = 72;

    /** Charter destination scroll container. */
    public static final int DESTINATIONS_CHILD = 7;

    // ========================================================================
    // NPC Constants
    // ========================================================================

    /** Trader Crewmember NPC IDs at various ports. */
    public static final int[] TRADER_CREWMEMBER_IDS = {
        4650, 4651, 4652, 4653, 4654, 4655, 4656, 4657,
        4658, 4659, 4660, 4661, 4662, 4663, 4664, 4665
    };

    // ========================================================================
    // Configuration
    // ========================================================================

    /** Destination port name. */
    @Getter
    private final String destination;

    /** Gold cost for the fare. */
    @Getter
    private final int fare;

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
    private NPC targetCrewmember;

    // ========================================================================
    // Phases
    // ========================================================================

    private enum Phase {
        INIT,
        FIND_CREWMEMBER,
        INTERACT_CREWMEMBER,
        WAIT_FOR_INTERFACE,
        SELECT_DESTINATION,
        CONFIRM_PAYMENT,
        WAIT_FOR_TRAVEL,
        VERIFY_ARRIVAL,
        COMPLETED,
        FAILED
    }

    // ========================================================================
    // Constructors
    // ========================================================================

    private CharterShipTask(String destination, int fare) {
        if (destination == null || destination.isEmpty()) {
            throw new IllegalArgumentException("Charter ship destination cannot be null or empty");
        }
        this.destination = destination;
        this.fare = fare;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a charter ship task to travel to the specified port.
     *
     * @param destination the destination port name
     * @param fare gold cost for the journey
     * @return new CharterShipTask
     */
    public static CharterShipTask to(String destination, int fare) {
        return new CharterShipTask(destination, fare);
    }

    /**
     * Create a charter ship task with destination verification.
     *
     * @param destination the destination port name
     * @param fare gold cost
     * @param expectedPoint expected destination point
     * @return new CharterShipTask
     */
    public static CharterShipTask to(String destination, int fare, WorldPoint expectedPoint) {
        CharterShipTask task = new CharterShipTask(destination, fare);
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
        targetCrewmember = null;
        startPosition = ctx.getPlayerState().getWorldPosition();
        log.debug("Initializing CharterShipTask for destination: {} (fare: {}gp)", destination, fare);
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (waiting) {
            return Status.IN_PROGRESS;
        }

        switch (phase) {
            case INIT:
                phase = Phase.FIND_CREWMEMBER;
                return Status.IN_PROGRESS;

            case FIND_CREWMEMBER:
                return tickFindCrewmember(ctx);

            case INTERACT_CREWMEMBER:
                return tickInteractCrewmember(ctx);

            case WAIT_FOR_INTERFACE:
                return tickWaitForInterface(ctx);

            case SELECT_DESTINATION:
                return tickSelectDestination(ctx);

            case CONFIRM_PAYMENT:
                return tickConfirmPayment(ctx);

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
        if (!ctx.isLoggedIn()) {
            return false;
        }
        // Check if player has enough gold
        int gold = ctx.getInventoryState().countItem(995); // Coins
        if (gold < fare) {
            log.debug("Insufficient gold for charter ship: have {}, need {}", gold, fare);
            return false;
        }
        return true;
    }

    @Override
    public String getDescription() {
        return "Charter ship to " + destination + " (" + fare + "gp)";
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
        targetCrewmember = null;
    }

    // ========================================================================
    // Phase Implementations
    // ========================================================================

    private Status tickFindCrewmember(TaskContext ctx) {
        Client client = ctx.getClient();

        for (NPC npc : client.getNpcs()) {
            if (npc == null) continue;
            
            // Check by ID
            int npcId = npc.getId();
            for (int crewId : TRADER_CREWMEMBER_IDS) {
                if (npcId == crewId) {
                    targetCrewmember = npc;
                    log.debug("Found trader crewmember: {}", npc.getName());
                    phase = Phase.INTERACT_CREWMEMBER;
                    return Status.IN_PROGRESS;
                }
            }
            
            // Fallback: check by name
            String name = npc.getName();
            if (name != null && name.contains("Trader")) {
                targetCrewmember = npc;
                log.debug("Found trader crewmember by name: {}", name);
                phase = Phase.INTERACT_CREWMEMBER;
                return Status.IN_PROGRESS;
            }
        }

        waitTicks++;
        if (waitTicks > 10) {
            failureReason = "Trader crewmember not found nearby";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickInteractCrewmember(TaskContext ctx) {
        if (targetCrewmember == null) {
            failureReason = "No target crewmember set";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        var convexHull = targetCrewmember.getConvexHull();
        if (convexHull == null) {
            failureReason = "Crewmember has no clickable area";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        Rectangle bounds = convexHull.getBounds();
        log.debug("Interacting with trader crewmember");

        waiting = true;
        ctx.getMenuHelper().selectMenuEntry(bounds, "Charter")
                .thenAccept(success -> {
                    waiting = false;
                    if (success) {
                        phase = Phase.WAIT_FOR_INTERFACE;
                        waitTicks = 0;
                    } else {
                        failureReason = "Failed to open charter interface";
                        phase = Phase.FAILED;
                    }
                })
                .exceptionally(e -> {
                    waiting = false;
                    log.error("Charter crewmember interaction failed", e);
                    failureReason = "Interaction failed: " + e.getMessage();
                    phase = Phase.FAILED;
                    return null;
                });

        return Status.IN_PROGRESS;
    }

    private Status tickWaitForInterface(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget charterInterface = client.getWidget(WIDGET_GROUP, 0);

        if (charterInterface != null && !charterInterface.isHidden()) {
            log.debug("Charter ship interface is open");
            phase = Phase.SELECT_DESTINATION;
            waitTicks = 0;
            return Status.IN_PROGRESS;
        }

        waitTicks++;
        if (waitTicks > 30) {
            failureReason = "Charter ship interface did not open";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickSelectDestination(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget container = client.getWidget(WIDGET_GROUP, DESTINATIONS_CHILD);

        if (container == null) {
            failureReason = "Charter destination container not found";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        // Search for destination in scroll list
        Widget destinationWidget = findDestinationWidget(container, destination);

        if (destinationWidget != null) {
            log.debug("Found charter destination: {}", destination);
            clickWidget(ctx, destinationWidget);
            return Status.IN_PROGRESS;
        }

        failureReason = "Charter destination not found: " + destination;
        phase = Phase.FAILED;
        return Status.FAILED;
    }

    private Widget findDestinationWidget(Widget parent, String dest) {
        String destLower = dest.toLowerCase();

        String text = parent.getText();
        if (text != null && text.toLowerCase().contains(destLower)) {
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

        return null;
    }

    private Status tickConfirmPayment(TaskContext ctx) {
        // Payment is usually automatic after clicking destination
        // This phase handles any confirmation dialog if needed
        phase = Phase.WAIT_FOR_TRAVEL;
        waitTicks = 0;
        return Status.IN_PROGRESS;
    }

    private Status tickWaitForTravel(TaskContext ctx) {
        WorldPoint currentPos = ctx.getPlayerState().getWorldPosition();

        if (currentPos != null && startPosition != null) {
            int distance = currentPos.distanceTo(startPosition);
            if (distance > 30) { // Charter ships travel far
                log.debug("Charter ship travel completed, moved {} tiles", distance);
                if (expectedDestination != null) {
                    phase = Phase.VERIFY_ARRIVAL;
                } else {
                    phase = Phase.COMPLETED;
                }
                return expectedDestination != null ? Status.IN_PROGRESS : Status.COMPLETED;
            }
        }

        waitTicks++;
        if (waitTicks > 50) { // Longer timeout for sea travel
            failureReason = "Charter ship travel timed out";
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
            log.debug("Arrived at charter destination (within {} tiles)", distance);
        } else {
            log.warn("Charter destination mismatch: expected {}, got {} (distance: {})",
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

        // Use centralized ClickPointCalculator for humanized positioning
        java.awt.Point clickPoint = com.rocinante.input.ClickPointCalculator.getGaussianClickPoint(bounds);
        int x = clickPoint.x;
        int y = clickPoint.y;

        waiting = true;

        ctx.getMouseController().moveToCanvas(x, y)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    waiting = false;
                    phase = Phase.CONFIRM_PAYMENT;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    waiting = false;
                    log.error("Charter ship click failed", e);
                    failureReason = "Click failed: " + e.getMessage();
                    phase = Phase.FAILED;
                    return null;
                });
    }
}

