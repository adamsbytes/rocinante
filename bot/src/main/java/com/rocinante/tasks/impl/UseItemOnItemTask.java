package com.rocinante.tasks.impl;

import com.rocinante.input.InventoryClickHelper;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * Task for using one inventory item on another inventory item.
 *
 * <p>This task performs a two-step interaction:
 * <ol>
 *   <li>Click the source item in inventory (enters "Use" cursor mode)</li>
 *   <li>Click the target item in inventory</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Use tinderbox on logs (make fire)
 * UseItemOnItemTask lightFire = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS);
 *
 * // Use bucket of water on pot of flour (make dough)
 * UseItemOnItemTask makeDough = new UseItemOnItemTask(ItemID.BUCKET_OF_WATER, ItemID.POT_OF_FLOUR)
 *     .withDescription("Make bread dough");
 * }</pre>
 */
@Slf4j
public class UseItemOnItemTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Maximum ticks to wait for interaction response.
     */
    private static final int INTERACTION_TIMEOUT_TICKS = 10;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The source item ID (the item to use).
     */
    @Getter
    private final int sourceItemId;

    /**
     * The target item ID (the item to use the source on).
     */
    @Getter
    private final int targetItemId;

    /**
     * Custom description.
     */
    @Setter
    private String description;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private UseItemPhase phase = UseItemPhase.CLICK_SOURCE;

    /**
     * Player animation when interaction started.
     */
    private int startAnimation;

    /**
     * Starting inventory count of target item.
     */
    private int startTargetCount;

    /**
     * Ticks since interaction started.
     */
    private int interactionTicks = 0;

    /**
     * Whether an async operation is pending.
     */
    private boolean operationPending = false;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a use item on item task.
     *
     * @param sourceItemId the source item ID (to use)
     * @param targetItemId the target item ID (to use on)
     */
    public UseItemOnItemTask(int sourceItemId, int targetItemId) {
        this.sourceItemId = sourceItemId;
        this.targetItemId = targetItemId;
        this.timeout = Duration.ofSeconds(30);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public UseItemOnItemTask withDescription(String description) {
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

        // Check if we have both items in inventory
        InventoryState inventory = ctx.getInventoryState();
        if (!inventory.hasItem(sourceItemId)) {
            log.debug("Source item {} not in inventory", sourceItemId);
            return false;
        }
        if (!inventory.hasItem(targetItemId)) {
            log.debug("Target item {} not in inventory", targetItemId);
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
            case CLICK_SOURCE:
                executeClickSource(ctx);
                break;
            case CLICK_TARGET:
                executeClickTarget(ctx);
                break;
            case WAIT_RESPONSE:
                executeWaitResponse(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Click Source Item
    // ========================================================================

    private void executeClickSource(TaskContext ctx) {
        InventoryClickHelper inventoryHelper = ctx.getInventoryClickHelper();
        if (inventoryHelper == null) {
            log.error("InventoryClickHelper not available in TaskContext");
            fail("InventoryClickHelper not available");
            return;
        }

        InventoryState inventory = ctx.getInventoryState();
        int slot = inventory.getSlotOf(sourceItemId);

        if (slot < 0) {
            fail("Source item " + sourceItemId + " not found in inventory");
            return;
        }

        log.debug("Clicking source item {} in slot {}", sourceItemId, slot);
        operationPending = true;

        inventoryHelper.executeClick(slot, "Use source item " + sourceItemId)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        phase = UseItemPhase.CLICK_TARGET;
                    } else {
                        fail("Failed to click source item in inventory");
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to click source item", e);
                    fail("Click failed: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Click Target Item
    // ========================================================================

    private void executeClickTarget(TaskContext ctx) {
        InventoryClickHelper inventoryHelper = ctx.getInventoryClickHelper();
        if (inventoryHelper == null) {
            log.error("InventoryClickHelper not available in TaskContext");
            fail("InventoryClickHelper not available");
            return;
        }

        InventoryState inventory = ctx.getInventoryState();
        int slot = inventory.getSlotOf(targetItemId);

        if (slot < 0) {
            fail("Target item " + targetItemId + " not found in inventory");
            return;
        }

        // Store starting state for success detection
        startAnimation = ctx.getPlayerState().getAnimationId();
        startTargetCount = inventory.countItem(targetItemId);

        log.debug("Clicking target item {} in slot {}", targetItemId, slot);
        operationPending = true;

        inventoryHelper.executeClick(slot, "Use on target item " + targetItemId)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        interactionTicks = 0;
                        phase = UseItemPhase.WAIT_RESPONSE;
                    } else {
                        fail("Failed to click target item in inventory");
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to click target item", e);
                    fail("Click failed: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait Response
    // ========================================================================

    private void executeWaitResponse(TaskContext ctx) {
        interactionTicks++;

        if (interactionTicks > INTERACTION_TIMEOUT_TICKS) {
            log.warn("Interaction timed out after {} ticks", INTERACTION_TIMEOUT_TICKS);
            fail("Interaction timeout - no response from game");
            return;
        }

        PlayerState player = ctx.getPlayerState();
        InventoryState inventory = ctx.getInventoryState();

        // Check for success indicators
        boolean success = false;
        String successReason = null;

        // Check for animation (e.g., making bread dough)
        if (player.isAnimating()) {
            success = true;
            successReason = "playing animation";
        }

        // Check if target item was consumed/transformed
        if (!success) {
            int currentCount = inventory.countItem(targetItemId);
            if (currentCount < startTargetCount) {
                success = true;
                successReason = "item consumed/transformed";
            }
        }

        // Check if source item was consumed
        if (!success && !inventory.hasItem(sourceItemId)) {
            success = true;
            successReason = "source item consumed";
        }

        if (success) {
            log.info("Use item on item successful: {} on {} ({})", sourceItemId, targetItemId, successReason);
            complete();
            return;
        }

        log.trace("Waiting for interaction response (tick {})", interactionTicks);
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("UseItemOnItem[source=%d, target=%d]", sourceItemId, targetItemId);
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum UseItemPhase {
        CLICK_SOURCE,
        CLICK_TARGET,
        WAIT_RESPONSE
    }
}

