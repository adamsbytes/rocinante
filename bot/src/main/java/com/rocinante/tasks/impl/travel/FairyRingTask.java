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
 * Specialized task for fairy ring travel.
 *
 * <p>Handles the fairy ring interface with 3-dial code entry. Each dial can be
 * rotated left or right to select letters A, B, C, or D.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Completion of Fairy Tale Part II (started) for fairy ring access</li>
 *   <li>Dramen staff or Lunar staff equipped (unless Lumbridge Elite diary)</li>
 *   <li>Player must already be at a fairy ring object</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * // Direct usage by quest handlers
 * FairyRingTask task = FairyRingTask.toCode("AJR");
 * task.init(ctx);
 * while (task.tick(ctx) == Status.IN_PROGRESS) {
 *     // wait for next game tick
 * }
 *
 * // Or use static factory with destination verification
 * FairyRingTask task = FairyRingTask.toCode("CKS", new WorldPoint(2801, 3003, 0));
 * }</pre>
 */
@Slf4j
public class FairyRingTask implements TravelSubTask {

    // ========================================================================
    // Widget Constants
    // ========================================================================

    /** Fairy ring widget group. */
    public static final int WIDGET_GROUP = 398;

    /** First dial (left) rotation buttons. */
    public static final int DIAL_1_LEFT = 19;
    public static final int DIAL_1_RIGHT = 20;

    /** Second dial (middle) rotation buttons. */
    public static final int DIAL_2_LEFT = 21;
    public static final int DIAL_2_RIGHT = 22;

    /** Third dial (right) rotation buttons. */
    public static final int DIAL_3_LEFT = 23;
    public static final int DIAL_3_RIGHT = 24;

    /** Confirm/teleport button. */
    public static final int CONFIRM_BUTTON = 26;

    // ========================================================================
    // Varbit Constants (canonical source for dial positions)
    // ========================================================================

    /** Varbit for first dial position (0-3 = A-D). */
    public static final int VARBIT_DIAL_1 = 3985;

    /** Varbit for second dial position (0-3 = A-D). */
    public static final int VARBIT_DIAL_2 = 3986;

    /** Varbit for third dial position (0-3 = A-D). */
    public static final int VARBIT_DIAL_3 = 3987;

    /** 
     * Dial letters for each dial, indexed by varbit value.
     * Order is NOT sequential! From RuneLite FairyRingPlugin:
     * - Left dial (varbit 3985): varbit 0=A, 1=D, 2=C, 3=B
     * - Middle dial (varbit 3986): varbit 0=I, 1=L, 2=K, 3=J
     * - Right dial (varbit 3987): varbit 0=P, 1=S, 2=R, 3=Q
     */
    private static final char[][] DIAL_LETTERS = {
        {'A', 'D', 'C', 'B'},  // Left dial - varbit positions 0,1,2,3
        {'I', 'L', 'K', 'J'},  // Middle dial - varbit positions 0,1,2,3
        {'P', 'S', 'R', 'Q'}   // Right dial - varbit positions 0,1,2,3
    };

    // ========================================================================
    // Item IDs
    // ========================================================================

    /** Dramen staff item ID. */
    public static final int DRAMEN_STAFF = 772;

    /** Lunar staff item ID. */
    public static final int LUNAR_STAFF = 9084;

    // ========================================================================
    // Configuration
    // ========================================================================

    /** The 3-letter fairy ring code (e.g., "AJR", "BKS"). */
    @Getter
    private final String code;

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

    /** Current dial positions [0-3] representing A-D. */
    private int[] currentDialPositions = new int[3];

    /** Which dial we're currently setting (0-2). */
    private int currentDialIndex = 0;

    /** Starting position for movement detection. */
    private WorldPoint startPosition;

    /** Ticks waited in current phase. */
    private int waitTicks = 0;

    // ========================================================================
    // Phases
    // ========================================================================

    private enum Phase {
        INIT,
        WAIT_FOR_INTERFACE,
        READ_DIAL_POSITIONS,
        SET_DIALS,
        CONFIRM_TELEPORT,
        WAIT_FOR_TRAVEL,
        VERIFY_ARRIVAL,
        COMPLETED,
        FAILED
    }

    // ========================================================================
    // Constructors
    // ========================================================================

    private FairyRingTask(String code) {
        if (code == null || code.length() != 3) {
            throw new IllegalArgumentException("Fairy ring code must be exactly 3 letters");
        }
        String upperCode = code.toUpperCase();
        
        // Validate each letter is valid for its dial
        // Left dial: A, B, C, D
        // Middle dial: I, J, K, L
        // Right dial: P, Q, R, S
        char c1 = upperCode.charAt(0);
        char c2 = upperCode.charAt(1);
        char c3 = upperCode.charAt(2);
        
        if (!isValidDialLetter(0, c1)) {
            throw new IllegalArgumentException("First dial must be A/B/C/D, got: " + c1);
        }
        if (!isValidDialLetter(1, c2)) {
            throw new IllegalArgumentException("Second dial must be I/J/K/L, got: " + c2);
        }
        if (!isValidDialLetter(2, c3)) {
            throw new IllegalArgumentException("Third dial must be P/Q/R/S, got: " + c3);
        }
        
        this.code = upperCode;
    }

    /**
     * Check if a letter is valid for a given dial.
     */
    private static boolean isValidDialLetter(int dialIndex, char letter) {
        for (char c : DIAL_LETTERS[dialIndex]) {
            if (c == letter) return true;
        }
        return false;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a fairy ring task to travel to the specified code.
     *
     * @param code the 3-letter fairy ring code (e.g., "AJR")
     * @return new FairyRingTask
     */
    public static FairyRingTask toCode(String code) {
        return new FairyRingTask(code);
    }

    /**
     * Create a fairy ring task with destination verification.
     *
     * @param code the 3-letter fairy ring code
     * @param destination expected destination point
     * @return new FairyRingTask
     */
    public static FairyRingTask toCode(String code, WorldPoint destination) {
        FairyRingTask task = new FairyRingTask(code);
        task.setExpectedDestination(destination);
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
        currentDialIndex = 0;
        waitTicks = 0;
        startPosition = ctx.getPlayerState().getWorldPosition();
        log.debug("Initializing FairyRingTask for code: {}", code);
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

            case READ_DIAL_POSITIONS:
                return tickReadDialPositions(ctx);

            case SET_DIALS:
                return tickSetDials(ctx);

            case CONFIRM_TELEPORT:
                return tickConfirmTeleport(ctx);

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
        // Could check for Dramen/Lunar staff here, but Lumbridge Elite diary
        // removes that requirement, so we just attempt and fail if needed
        return true;
    }

    @Override
    public String getDescription() {
        return "Use fairy ring " + code;
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
        currentDialIndex = 0;
        waitTicks = 0;
        currentDialPositions = new int[3];
    }

    // ========================================================================
    // Phase Implementations
    // ========================================================================

    private Status tickWaitForInterface(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget fairyRing = client.getWidget(WIDGET_GROUP, 0);

        if (fairyRing != null && !fairyRing.isHidden()) {
            log.debug("Fairy ring interface is open");
            phase = Phase.READ_DIAL_POSITIONS;
            waitTicks = 0;
            return Status.IN_PROGRESS;
        }

        waitTicks++;
        if (waitTicks > 30) {
            failureReason = "Fairy ring interface did not open - ensure you interact with a fairy ring first";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        return Status.IN_PROGRESS;
    }

    private Status tickReadDialPositions(TaskContext ctx) {
        Client client = ctx.getClient();

        // Read current dial positions from varbits (canonical source)
        currentDialPositions[0] = client.getVarbitValue(VARBIT_DIAL_1);
        currentDialPositions[1] = client.getVarbitValue(VARBIT_DIAL_2);
        currentDialPositions[2] = client.getVarbitValue(VARBIT_DIAL_3);

        log.debug("Current fairy ring dials: positions [{}, {}, {}] = code {}{}{}",
                currentDialPositions[0], currentDialPositions[1], currentDialPositions[2],
                DIAL_LETTERS[0][currentDialPositions[0]],
                DIAL_LETTERS[1][currentDialPositions[1]],
                DIAL_LETTERS[2][currentDialPositions[2]]);

        currentDialIndex = 0;
        phase = Phase.SET_DIALS;
        return Status.IN_PROGRESS;
    }

    private Status tickSetDials(TaskContext ctx) {
        if (currentDialIndex >= 3) {
            // All dials set, confirm teleport
            phase = Phase.CONFIRM_TELEPORT;
            return Status.IN_PROGRESS;
        }

        // Re-read current position from varbit (verifies dial actually moved)
        Client client = ctx.getClient();
        int varbitId = currentDialIndex == 0 ? VARBIT_DIAL_1 
                     : currentDialIndex == 1 ? VARBIT_DIAL_2 : VARBIT_DIAL_3;
        currentDialPositions[currentDialIndex] = client.getVarbitValue(varbitId);

        char targetLetter = code.charAt(currentDialIndex);
        int targetPosition = letterToPosition(currentDialIndex, targetLetter);
        int currentPosition = currentDialPositions[currentDialIndex];

        if (currentPosition == targetPosition) {
            // This dial is already correct, move to next
            currentDialIndex++;
            log.debug("Dial {} already at target {}", currentDialIndex, targetLetter);
            return Status.IN_PROGRESS;
        }

        // Calculate clicks needed (can go left or right)
        int clockwiseClicks = (targetPosition - currentPosition + 4) % 4;
        int counterClicks = (currentPosition - targetPosition + 4) % 4;

        // Choose direction with fewer clicks
        int buttonChildId;
        int newPosition;
        if (clockwiseClicks <= counterClicks) {
            // Click right (clockwise)
            buttonChildId = currentDialIndex == 0 ? DIAL_1_RIGHT
                    : currentDialIndex == 1 ? DIAL_2_RIGHT : DIAL_3_RIGHT;
            newPosition = (currentPosition + 1) % 4;
        } else {
            // Click left (counter-clockwise)
            buttonChildId = currentDialIndex == 0 ? DIAL_1_LEFT
                    : currentDialIndex == 1 ? DIAL_2_LEFT : DIAL_3_LEFT;
            newPosition = (currentPosition + 3) % 4;
        }

        Widget button = client.getWidget(WIDGET_GROUP, buttonChildId);

        if (button == null || button.isHidden()) {
            failureReason = "Fairy ring dial button not found";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        log.debug("Rotating dial {}: {} -> {} (target: {})",
                currentDialIndex + 1,
                DIAL_LETTERS[currentDialIndex][currentPosition],
                DIAL_LETTERS[currentDialIndex][newPosition],
                targetLetter);

        clickWidget(ctx, button);
        return Status.IN_PROGRESS;
    }

    /**
     * Convert a dial letter to its varbit position (0-3).
     * The mapping is NOT sequential - see DIAL_LETTERS for actual order.
     * 
     * @param dialIndex which dial (0, 1, or 2)
     * @param letter the letter
     * @return varbit position 0-3
     */
    private int letterToPosition(int dialIndex, char letter) {
        char[] dialChars = DIAL_LETTERS[dialIndex];
        for (int i = 0; i < dialChars.length; i++) {
            if (dialChars[i] == letter) {
                return i;
            }
        }
        throw new IllegalArgumentException("Invalid letter " + letter + " for dial " + dialIndex);
    }

    private Status tickConfirmTeleport(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget confirmButton = client.getWidget(WIDGET_GROUP, CONFIRM_BUTTON);

        if (confirmButton == null || confirmButton.isHidden()) {
            failureReason = "Fairy ring confirm button not found";
            phase = Phase.FAILED;
            return Status.FAILED;
        }

        log.debug("Confirming fairy ring teleport to: {}", code);
        clickWidget(ctx, confirmButton);
        phase = Phase.WAIT_FOR_TRAVEL;
        waitTicks = 0;
        return Status.IN_PROGRESS;
    }

    private Status tickWaitForTravel(TaskContext ctx) {
        WorldPoint currentPos = ctx.getPlayerState().getWorldPosition();

        if (currentPos != null && startPosition != null) {
            int distance = currentPos.distanceTo(startPosition);
            if (distance > 5) {
                log.debug("Fairy ring travel completed, moved {} tiles", distance);
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
            failureReason = "Fairy ring travel timed out";
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
            log.debug("Arrived at fairy ring destination (within {} tiles)", distance);
            phase = Phase.COMPLETED;
            return Status.COMPLETED;
        } else {
            log.warn("Fairy ring destination mismatch: expected {}, got {} (distance: {})",
                    expectedDestination, currentPos, distance);
            // Still complete since travel happened
            phase = Phase.COMPLETED;
            return Status.COMPLETED;
        }
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

        // Calculate humanized click point
        var rand = ctx.getRandomization();
        int x = bounds.x + bounds.width / 2 + (int) ((rand.uniformRandom(0, 1) - 0.5) * bounds.width * 0.4);
        int y = bounds.y + bounds.height / 2 + (int) ((rand.uniformRandom(0, 1) - 0.5) * bounds.height * 0.4);

        waiting = true;

        ctx.getMouseController().moveToCanvas(x, y)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    waiting = false;
                })
                .exceptionally(e -> {
                    waiting = false;
                    log.error("Fairy ring click failed", e);
                    failureReason = "Click failed: " + e.getMessage();
                    phase = Phase.FAILED;
                    return null;
                });
    }
}

