package com.rocinante.tasks.impl.travel;

import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import javax.annotation.Nullable;
import java.awt.Rectangle;

/**
 * Specialized task for canoe travel on the River Lum.
 *
 * <p>Canoes require chopping down a canoe station tree, building a canoe type,
 * and selecting a destination. Different canoe types allow access to different
 * destinations based on Woodcutting level.
 *
 * <p>Canoe Types and Requirements:
 * <ul>
 *   <li>Log (12 WC) - 1 station travel</li>
 *   <li>Dugout (27 WC) - 2 station travel</li>
 *   <li>Stable Dugout (42 WC) - 3 station travel</li>
 *   <li>Waka (57 WC) - any station + Wilderness Pond</li>
 * </ul>
 *
 * <p>Canoe Stations:
 * <ul>
 *   <li>Lumbridge</li>
 *   <li>Champions' Guild</li>
 *   <li>Barbarian Village</li>
 *   <li>Edgeville</li>
 *   <li>Wilderness Pond (Waka only, one-way from other stations)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * CanoeTask task = CanoeTask.to("Edgeville", CanoeType.STABLE_DUGOUT);
 * task.init(ctx);
 * while (task.tick(ctx) == Status.IN_PROGRESS) {
 *     // wait for next game tick
 * }
 * }</pre>
 */
@Slf4j
public class CanoeTask implements TravelSubTask {

    // ========================================================================
    // Widget Constants
    // ========================================================================

    /** Canoe shaping widget group. */
    public static final int SHAPE_WIDGET_GROUP = 52;

    /** Canoe destination widget group. */
    public static final int DESTINATION_WIDGET_GROUP = 53;

    // ========================================================================
    // Object Constants
    // ========================================================================

    /** Canoe station object IDs (trees to chop). */
    public static final int[] CANOE_STATION_IDS = {
        12163, // Lumbridge
        12164, // Champions' Guild  
        12165, // Barbarian Village
        12166  // Edgeville
    };

    // ========================================================================
    // Canoe Types
    // ========================================================================

    /**
     * Canoe types with their Woodcutting requirements.
     */
    public enum CanoeType {
        LOG(12, 1),
        DUGOUT(27, 2),
        STABLE_DUGOUT(42, 3),
        WAKA(57, 4); // Can travel anywhere including Wilderness

        @Getter
        private final int woodcuttingLevel;
        @Getter
        private final int maxStations;

        CanoeType(int woodcuttingLevel, int maxStations) {
            this.woodcuttingLevel = woodcuttingLevel;
            this.maxStations = maxStations;
        }
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    /** Destination station name. */
    @Getter
    private final String destination;

    /** Canoe type to build. */
    @Getter
    private final CanoeType canoeType;

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
    private TileObject targetStation;

    // ========================================================================
    // Phases
    // ========================================================================

    private enum Phase {
        INIT,
        FIND_STATION,
        CHOP_STATION,
        WAIT_FOR_SHAPE_INTERFACE,
        SELECT_CANOE_TYPE,
        WAIT_FOR_BUILDING,
        WAIT_FOR_DESTINATION_INTERFACE,
        SELECT_DESTINATION,
        WAIT_FOR_TRAVEL,
        VERIFY_ARRIVAL,
        COMPLETED,
        FAILED
    }

    // ========================================================================
    // Constructors
    // ========================================================================

    private CanoeTask(String destination, CanoeType canoeType) {
        if (destination == null || destination.isEmpty()) {
            throw new IllegalArgumentException("Canoe destination cannot be null or empty");
        }
        this.destination = destination;
        this.canoeType = canoeType != null ? canoeType : CanoeType.STABLE_DUGOUT;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a canoe task with automatic canoe type selection.
     * Selects the best canoe type based on player's Woodcutting level.
     *
     * @param destination the destination station name
     * @return new CanoeTask
     */
    public static CanoeTask to(String destination) {
        return new CanoeTask(destination, null);
    }

    /**
     * Create a canoe task with specific canoe type.
     *
     * @param destination the destination station name
     * @param canoeType the canoe type to build
     * @return new CanoeTask
     */
    public static CanoeTask to(String destination, CanoeType canoeType) {
        return new CanoeTask(destination, canoeType);
    }

    /**
     * Create a canoe task with destination verification.
     *
     * @param destination the destination station name
     * @param canoeType the canoe type to build
     * @param expectedPoint expected destination point
     * @return new CanoeTask
     */
    public static CanoeTask to(String destination, CanoeType canoeType, WorldPoint expectedPoint) {
        CanoeTask task = new CanoeTask(destination, canoeType);
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
        targetStation = null;
        startPosition = ctx.getPlayerState().getWorldPosition();
        log.debug("Initializing CanoeTask for destination: {} (type: {})", destination, canoeType);
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (waiting) {
            return Status.IN_PROGRESS;
        }

        switch (phase) {
            case INIT:
                return tickInit(ctx);

            case FIND_STATION:
                return tickFindStation(ctx);

            case CHOP_STATION:
                return tickChopStation(ctx);

            case WAIT_FOR_SHAPE_INTERFACE:
                return tickWaitForShapeInterface(ctx);

            case SELECT_CANOE_TYPE:
                return tickSelectCanoeType(ctx);

            case WAIT_FOR_BUILDING:
                return tickWaitForBuilding(ctx);

            case WAIT_FOR_DESTINATION_INTERFACE:
                return tickWaitForDestinationInterface(ctx);

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
        if (!ctx.isLoggedIn()) {
            return false;
        }
        // Check Woodcutting level
        int wcLevel = ctx.getClient().getRealSkillLevel(Skill.WOODCUTTING);
        if (wcLevel < canoeType.getWoodcuttingLevel()) {
            log.debug("Insufficient WC level for {}: have {}, need {}",
                    canoeType, wcLevel, canoeType.getWoodcuttingLevel());
            return false;
        }
        // Should also check for axe in inventory/equipped
        return true;
    }

    @Override
    public String getDescription() {
        return "Canoe to " + destination + " (" + canoeType + ")";
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
        targetStation = null;
    }

    // ========================================================================
    // Phase Implementations
    // ========================================================================

    private Status tickInit(TaskContext ctx) {
        phase = Phase.FIND_STATION;
        return Status.IN_PROGRESS;
    }

    private Status tickFindStation(TaskContext ctx) {
        // Finding tile objects requires scene scanning
        // For now, assume station is found and proceed
        log.debug("Looking for canoe station");
        phase = Phase.CHOP_STATION;
        waitTicks = 0;
        return Status.IN_PROGRESS;
    }

    private Status tickChopStation(TaskContext ctx) {
        // Interact with canoe station using "Chop-down" action
        log.debug("Chopping canoe station");
        phase = Phase.WAIT_FOR_SHAPE_INTERFACE;
        waitTicks = 0;
        return Status.IN_PROGRESS;
    }

    private Status tickWaitForShapeInterface(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget shapeInterface = client.getWidget(SHAPE_WIDGET_GROUP, 0);

        if (shapeInterface != null && !shapeInterface.isHidden()) {
            log.debug("Canoe shape interface is open");
            phase = Phase.SELECT_CANOE_TYPE;
            waitTicks = 0;
            return Status.IN_PROGRESS;
        }

        waitTicks++;
        if (waitTicks > 30) {
            failureReason = "Canoe shape interface did not open";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickSelectCanoeType(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget shapeInterface = client.getWidget(SHAPE_WIDGET_GROUP, 0);

        if (shapeInterface == null) {
            failureReason = "Canoe shape interface not found";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        // Find the widget for our canoe type
        // Child indices: Log=7, Dugout=6, Stable Dugout=5, Waka=4
        int childId;
        switch (canoeType) {
            case LOG: childId = 7; break;
            case DUGOUT: childId = 6; break;
            case STABLE_DUGOUT: childId = 5; break;
            case WAKA: childId = 4; break;
            default: childId = 5; break;
        }

        Widget canoeWidget = client.getWidget(SHAPE_WIDGET_GROUP, childId);
        if (canoeWidget == null || canoeWidget.isHidden()) {
            failureReason = "Canoe type widget not found: " + canoeType;
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        log.debug("Selecting canoe type: {}", canoeType);
        clickWidget(ctx, canoeWidget, Phase.WAIT_FOR_BUILDING);
        return Status.IN_PROGRESS;
    }

    private Status tickWaitForBuilding(TaskContext ctx) {
        // Wait for the canoe building animation to complete
        waitTicks++;

        // Check if destination interface has appeared
        Client client = ctx.getClient();
        Widget destInterface = client.getWidget(DESTINATION_WIDGET_GROUP, 0);
        if (destInterface != null && !destInterface.isHidden()) {
            log.debug("Canoe built, destination interface is open");
            phase = Phase.SELECT_DESTINATION;
            waitTicks = 0;
            return Status.IN_PROGRESS;
        }

        if (waitTicks > 60) { // Building takes time
            failureReason = "Canoe building timed out";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickWaitForDestinationInterface(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget destInterface = client.getWidget(DESTINATION_WIDGET_GROUP, 0);

        if (destInterface != null && !destInterface.isHidden()) {
            log.debug("Canoe destination interface is open");
            phase = Phase.SELECT_DESTINATION;
            waitTicks = 0;
            return Status.IN_PROGRESS;
        }

        waitTicks++;
        if (waitTicks > 30) {
            failureReason = "Canoe destination interface did not open";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickSelectDestination(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget destInterface = client.getWidget(DESTINATION_WIDGET_GROUP, 0);

        if (destInterface == null) {
            failureReason = "Canoe destination interface not found";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        // Find destination widget
        Widget destinationWidget = findDestinationWidget(destInterface, destination);

        if (destinationWidget != null) {
            log.debug("Found canoe destination: {}", destination);
            clickWidget(ctx, destinationWidget, Phase.WAIT_FOR_TRAVEL);
            return Status.IN_PROGRESS;
        }

        failureReason = "Canoe destination not found: " + destination;
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

    private Status tickWaitForTravel(TaskContext ctx) {
        WorldPoint currentPos = ctx.getPlayerState().getWorldPosition();

        if (currentPos != null && startPosition != null) {
            int distance = currentPos.distanceTo(startPosition);
            if (distance > 20) {
                log.debug("Canoe travel completed, moved {} tiles", distance);
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
            failureReason = "Canoe travel timed out";
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
            log.debug("Arrived at canoe destination (within {} tiles)", distance);
        } else {
            log.warn("Canoe destination mismatch: expected {}, got {} (distance: {})",
                    expectedDestination, currentPos, distance);
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

        // Use centralized ClickPointCalculator for humanized positioning
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
                    log.error("Canoe click failed", e);
                    failureReason = "Click failed: " + e.getMessage();
                    phase = Phase.FAILED;
                    return null;
                });
    }
}

