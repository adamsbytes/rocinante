package com.rocinante.tasks.impl;

import com.rocinante.input.InventoryClickHelper;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.WidgetClickHelper;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Task for processing items using item-on-item interaction with make-all interface handling.
 *
 * <p>This task handles production skills like Fletching, Crafting, Herblore, etc.
 * It wraps the item-on-item interaction and handles the "Make All" / "Make X" interfaces
 * that appear after using items together.
 *
 * <p>Processing flow:
 * <ol>
 *   <li>Click source item on target item (e.g., knife on logs)</li>
 *   <li>Wait for make-all interface to appear</li>
 *   <li>Press Space or click "Make All" button</li>
 *   <li>Wait for player to finish animating (production complete)</li>
 *   <li>Check if more materials available, repeat or complete</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Fletch oak logs into shortbows
 * ProcessItemTask fletching = new ProcessItemTask(946, 1521)  // knife, oak logs
 *     .withOutputItemId(54)  // Oak shortbow (u)
 *     .withDescription("Fletch oak shortbows");
 *
 * // Make potions
 * ProcessItemTask herblorge = new ProcessItemTask(227, 101)  // Ranarr weed, water vial
 *     .withOutputItemId(97)  // Ranarr potion (unf)
 *     .withTargetQuantity(14);
 * }</pre>
 */
@Slf4j
public class ProcessItemTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Default make-all widget group ID.
     * This is the modern skill dialog (group 270).
     */
    private static final int DEFAULT_MAKE_ALL_WIDGET_GROUP = 270;

    /**
     * Child widget ID for the "Make All" option (typically first option).
     */
    private static final int MAKE_ALL_CHILD_ID = 14;

    /**
     * Maximum ticks to wait for make-all interface to appear.
     */
    private static final int INTERFACE_WAIT_TIMEOUT_TICKS = 15;

    /**
     * Maximum ticks to wait for production animation to complete one batch.
     */
    private static final int PRODUCTION_TIMEOUT_TICKS = 100;

    /**
     * Minimum delay before pressing space/clicking make-all (humanization).
     */
    private static final long MIN_INTERFACE_DELAY_MS = 200;

    /**
     * Maximum delay before pressing space/clicking make-all (humanization).
     */
    private static final long MAX_INTERFACE_DELAY_MS = 600;

    /**
     * Delay to wait after player stops animating before re-checking.
     */
    private static final int IDLE_CONFIRMATION_TICKS = 3;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Source item ID (tool - e.g., knife, hammer).
     */
    @Getter
    private final int sourceItemId;

    /**
     * Target item ID (material - e.g., logs, ore).
     */
    @Getter
    private final int targetItemId;

    /**
     * Expected output item ID (for verification).
     */
    @Getter
    private int outputItemId = -1;

    /**
     * Number of outputs to produce per action (e.g., 15 arrowtips per bar).
     */
    @Getter
    private int outputPerAction = 1;

    /**
     * Target quantity to produce (0 = all available materials).
     */
    @Getter
    private int targetQuantity = 0;

    /**
     * Widget group ID for make-all interface.
     */
    @Getter
    private int makeAllWidgetGroup = DEFAULT_MAKE_ALL_WIDGET_GROUP;

    /**
     * Child widget ID for specific product selection (-1 for default/first).
     */
    @Getter
    private int makeAllChildId = -1;

    /**
     * Custom task description.
     */
    private String description;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private ProcessPhase phase = ProcessPhase.USE_ITEM;

    /**
     * Ticks waiting for interface/completion.
     */
    private int waitTicks = 0;

    /**
     * Ticks player has been idle (for completion detection).
     */
    private int idleTicks = 0;

    /**
     * Number of items produced so far.
     */
    @Getter
    private int producedCount = 0;

    /**
     * Starting count of target item.
     */
    private int startTargetCount = 0;

    /**
     * Starting count of output item.
     */
    private int startOutputCount = 0;

    /**
     * Whether an async operation is pending.
     */
    private volatile boolean operationPending = false;

    /**
     * Batch count for tracking progress.
     */
    private int batchNumber = 0;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a process item task.
     *
     * @param sourceItemId tool item ID (knife, hammer, etc.)
     * @param targetItemId material item ID (logs, ore, etc.)
     */
    public ProcessItemTask(int sourceItemId, int targetItemId) {
        this.sourceItemId = sourceItemId;
        this.targetItemId = targetItemId;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set expected output item ID (builder-style).
     *
     * @param outputItemId the output item ID
     * @return this task for chaining
     */
    public ProcessItemTask withOutputItemId(int outputItemId) {
        this.outputItemId = outputItemId;
        return this;
    }

    /**
     * Set outputs per action (builder-style).
     *
     * @param outputPerAction outputs per action
     * @return this task for chaining
     */
    public ProcessItemTask withOutputPerAction(int outputPerAction) {
        this.outputPerAction = Math.max(1, outputPerAction);
        return this;
    }

    /**
     * Set target quantity to produce (builder-style).
     *
     * @param quantity target quantity (0 = all available)
     * @return this task for chaining
     */
    public ProcessItemTask withTargetQuantity(int quantity) {
        this.targetQuantity = Math.max(0, quantity);
        return this;
    }

    /**
     * Set make-all widget group ID (builder-style).
     *
     * @param widgetGroup widget group ID
     * @return this task for chaining
     */
    public ProcessItemTask withMakeAllWidgetGroup(int widgetGroup) {
        this.makeAllWidgetGroup = widgetGroup;
        return this;
    }

    /**
     * Set make-all child widget ID for specific product (builder-style).
     *
     * @param childId child widget ID
     * @return this task for chaining
     */
    public ProcessItemTask withMakeAllChildId(int childId) {
        this.makeAllChildId = childId;
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public ProcessItemTask withDescription(String description) {
        this.description = description;
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

        InventoryState inventory = ctx.getInventoryState();

        // Check if we have source item (tool)
        if (!inventory.hasItem(sourceItemId)) {
            log.debug("Source item {} not in inventory", sourceItemId);
            return false;
        }

        // Check if we have target item (material)
        if (!inventory.hasItem(targetItemId)) {
            log.debug("Target item {} not in inventory", targetItemId);
            return false;
        }

        // Check if we've reached target quantity
        if (targetQuantity > 0 && producedCount >= targetQuantity) {
            log.debug("Target quantity {} reached", targetQuantity);
            return false;
        }

        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (operationPending) {
            return;
        }

        switch (phase) {
            case USE_ITEM:
                executeUseItem(ctx);
                break;
            case WAIT_INTERFACE:
                executeWaitInterface(ctx);
                break;
            case SELECT_OPTION:
                executeSelectOption(ctx);
                break;
            case WAIT_COMPLETION:
                executeWaitCompletion(ctx);
                break;
            case CHECK_CONTINUE:
                executeCheckContinue(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Use Item
    // ========================================================================

    private void executeUseItem(TaskContext ctx) {
        InventoryClickHelper inventoryHelper = ctx.getInventoryClickHelper();
        if (inventoryHelper == null) {
            fail("InventoryClickHelper not available");
            return;
        }

        InventoryState inventory = ctx.getInventoryState();

        // Record starting counts
        startTargetCount = inventory.countItem(targetItemId);
        if (outputItemId > 0) {
            startOutputCount = inventory.countItem(outputItemId);
        }

        // Find source and target slots
        int sourceSlot = inventory.getSlotOf(sourceItemId);
        int targetSlot = inventory.getSlotOf(targetItemId);

        if (sourceSlot < 0 || targetSlot < 0) {
            fail("Could not find items in inventory");
            return;
        }

        batchNumber++;
        log.debug("Starting batch #{}: using {} on {}", batchNumber, sourceItemId, targetItemId);
        operationPending = true;

        // Click source item (log-normal delay for inter-click timing)
        long delayMs = ctx.getRandomization().humanizedDelayMs(140, 100, 200);
        inventoryHelper.executeClick(sourceSlot, "Use source item")
                .thenCompose(success -> {
                    if (!success) {
                        throw new RuntimeException("Failed to click source item");
                    }
                    // Small non-blocking delay before clicking target
                    return CompletableFuture.supplyAsync(
                        () -> null,
                        CompletableFuture.delayedExecutor(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    );
                })
                .thenCompose(v -> {
                    // Click target item
                    return inventoryHelper.executeClick(targetSlot, "Use on target item");
                })
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        waitTicks = 0;
                        phase = ProcessPhase.WAIT_INTERFACE;
                        log.debug("Waiting for make-all interface");
                    } else {
                        fail("Failed to click target item");
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Use item failed", e);
                    fail("Use item failed: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait Interface
    // ========================================================================

    private void executeWaitInterface(TaskContext ctx) {
        waitTicks++;

        if (waitTicks > INTERFACE_WAIT_TIMEOUT_TICKS) {
            log.warn("Timeout waiting for make-all interface");
            fail("Make-all interface did not appear");
            return;
        }

        // Check if make-all interface is open
        Client client = ctx.getClient();
        Widget makeAllWidget = client.getWidget(makeAllWidgetGroup, 0);

        if (makeAllWidget != null && !makeAllWidget.isHidden()) {
            log.debug("Make-all interface appeared after {} ticks", waitTicks);
            phase = ProcessPhase.SELECT_OPTION;
            return;
        }

        // Also check if player started animating (some interfaces are skipped)
        PlayerState player = ctx.getPlayerState();
        if (player.isAnimating()) {
            log.debug("Player animating without interface - production started");
            waitTicks = 0;
            idleTicks = 0;
            phase = ProcessPhase.WAIT_COMPLETION;
            return;
        }

        log.debug("Waiting for interface (tick {})", waitTicks);
    }

    // ========================================================================
    // Phase: Select Option
    // ========================================================================

    private void executeSelectOption(TaskContext ctx) {
        Randomization random = ctx.getRandomization();

        // Add humanized delay before selecting
        long delay = random.uniformRandomLong(MIN_INTERFACE_DELAY_MS, MAX_INTERFACE_DELAY_MS);

        operationPending = true;

        ctx.getHumanTimer().sleep(delay)
                .thenRun(() -> {
                    // Prefer pressing Space for "Make All" (most common pattern)
                    RobotKeyboardController keyboard = ctx.getKeyboardController();
                    if (keyboard != null) {
                        keyboard.pressSpace()
                                .thenAccept(v -> {
                                    operationPending = false;
                                    waitTicks = 0;
                                    idleTicks = 0;
                                    phase = ProcessPhase.WAIT_COMPLETION;
                                    log.debug("Pressed Space for Make All");
                                })
                                .exceptionally(e -> {
                                    operationPending = false;
                                    // Fall back to clicking the widget
                                    clickMakeAllWidget(ctx);
                                    return null;
                                });
                    } else {
                        operationPending = false;
                        clickMakeAllWidget(ctx);
                    }
                });
    }

    /**
     * Click the make-all widget directly (fallback if Space doesn't work).
     */
    private void clickMakeAllWidget(TaskContext ctx) {
        WidgetClickHelper widgetHelper = ctx.getWidgetClickHelper();
        if (widgetHelper == null) {
            fail("WidgetClickHelper not available");
            return;
        }

        Client client = ctx.getClient();
        int childId = makeAllChildId >= 0 ? makeAllChildId : MAKE_ALL_CHILD_ID;
        Widget makeAllButton = client.getWidget(makeAllWidgetGroup, childId);

        if (makeAllButton == null || makeAllButton.isHidden()) {
            log.warn("Make-all button widget not found");
            fail("Make-all button not found");
            return;
        }

        operationPending = true;

        widgetHelper.clickWidget(makeAllButton, "Click Make All")
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        waitTicks = 0;
                        idleTicks = 0;
                        phase = ProcessPhase.WAIT_COMPLETION;
                        log.debug("Clicked Make All widget");
                    } else {
                        fail("Failed to click Make All widget");
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    fail("Widget click failed: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait Completion
    // ========================================================================

    private void executeWaitCompletion(TaskContext ctx) {
        waitTicks++;

        if (waitTicks > PRODUCTION_TIMEOUT_TICKS) {
            log.warn("Production timeout after {} ticks", PRODUCTION_TIMEOUT_TICKS);
            fail("Production timeout");
            return;
        }

        PlayerState player = ctx.getPlayerState();
        InventoryState inventory = ctx.getInventoryState();

        // Check if player is still animating
        if (player.isAnimating()) {
            idleTicks = 0;
            log.debug("Player animating (tick {})", waitTicks);
            return;
        }

        // Player stopped animating - wait a few ticks to confirm
        idleTicks++;

        if (idleTicks < IDLE_CONFIRMATION_TICKS) {
            log.debug("Player idle, confirming... ({}/{})", idleTicks, IDLE_CONFIRMATION_TICKS);
            return;
        }

        // Production batch complete - calculate produced amount
        int currentTargetCount = inventory.countItem(targetItemId);
        int targetConsumed = startTargetCount - currentTargetCount;

        if (outputItemId > 0) {
            int currentOutputCount = inventory.countItem(outputItemId);
            int outputProduced = currentOutputCount - startOutputCount;
            producedCount += outputProduced;
            log.debug("Batch #{} complete: produced {} (total: {})", batchNumber, outputProduced, producedCount);
        } else {
            // Estimate based on materials consumed
            producedCount += targetConsumed * outputPerAction;
            log.debug("Batch #{} complete: consumed {} materials", batchNumber, targetConsumed);
        }

        phase = ProcessPhase.CHECK_CONTINUE;
    }

    // ========================================================================
    // Phase: Check Continue
    // ========================================================================

    private void executeCheckContinue(TaskContext ctx) {
        InventoryState inventory = ctx.getInventoryState();

        // Check if we've reached target quantity
        if (targetQuantity > 0 && producedCount >= targetQuantity) {
            log.info("Reached target quantity: {} produced", producedCount);
            complete();
            return;
        }

        // Check if we have more materials
        if (!inventory.hasItem(targetItemId)) {
            log.info("Out of materials. Produced {} items", producedCount);
            complete();
            return;
        }

        // Continue with next batch
        log.debug("More materials available, starting next batch");
        phase = ProcessPhase.USE_ITEM;
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("ProcessItem[source=%d, target=%d, output=%d]",
                sourceItemId, targetItemId, outputItemId);
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Override
    public void onComplete(TaskContext ctx) {
        super.onComplete(ctx);
        log.info("Process task complete: {} items produced in {} batches", producedCount, batchNumber);
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum ProcessPhase {
        /**
         * Use source item on target item.
         */
        USE_ITEM,

        /**
         * Wait for make-all interface to appear.
         */
        WAIT_INTERFACE,

        /**
         * Select "Make All" option.
         */
        SELECT_OPTION,

        /**
         * Wait for production animation to complete.
         */
        WAIT_COMPLETION,

        /**
         * Check if more materials available, continue or complete.
         */
        CHECK_CONTINUE
    }
}

