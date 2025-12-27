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
 * Task for simple inventory item interactions (Eat, Drink, Use, Drop).
 *
 * <p>This task performs a single click on an inventory item with the default action.
 * The action is typically determined by the item type (e.g., food items have "Eat" as default).
 *
 * <p>Example usage:
 * <pre>{@code
 * // Eat food
 * InventoryInteractTask eatShrimp = new InventoryInteractTask(ItemID.SHRIMP, "eat");
 *
 * // Drink potion
 * InventoryInteractTask drinkPotion = new InventoryInteractTask(ItemID.PRAYER_POTION_4, "drink");
 *
 * // Use (generic)
 * InventoryInteractTask useItem = new InventoryInteractTask(ItemID.TINDERBOX, "use");
 *
 * // Drop item
 * InventoryInteractTask dropItem = new InventoryInteractTask(ItemID.BONES, "drop")
 *     .withDescription("Drop the bones");
 * }</pre>
 */
@Slf4j
public class InventoryInteractTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Maximum ticks to wait for interaction response.
     */
    private static final int INTERACTION_TIMEOUT_TICKS = 5;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The item ID to interact with.
     */
    @Getter
    private final int itemId;

    /**
     * The action to perform (eat, drink, use, drop).
     */
    @Getter
    private final String action;

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
    private InteractPhase phase = InteractPhase.CLICK_ITEM;

    /**
     * Starting count of the item in inventory.
     */
    private int startItemCount;

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
     * Create an inventory interact task.
     *
     * @param itemId the item ID to interact with
     * @param action the action to perform (eat, drink, use, drop)
     */
    public InventoryInteractTask(int itemId, String action) {
        this.itemId = itemId;
        this.action = action.toLowerCase();
        this.timeout = Duration.ofSeconds(15);
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
    public InventoryInteractTask withDescription(String description) {
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

        // Check if we have the item in inventory
        InventoryState inventory = ctx.getInventoryState();
        if (!inventory.hasItem(itemId)) {
            log.debug("Item {} not in inventory", itemId);
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
            case CLICK_ITEM:
                executeClickItem(ctx);
                break;
            case WAIT_RESPONSE:
                executeWaitResponse(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Click Item
    // ========================================================================

    private void executeClickItem(TaskContext ctx) {
        InventoryClickHelper inventoryHelper = ctx.getInventoryClickHelper();
        if (inventoryHelper == null) {
            log.error("InventoryClickHelper not available in TaskContext");
            fail("InventoryClickHelper not available");
            return;
        }

        InventoryState inventory = ctx.getInventoryState();
        int slot = inventory.getSlotOf(itemId);

        if (slot < 0) {
            fail("Item " + itemId + " not found in inventory");
            return;
        }

        // Store starting state
        startItemCount = inventory.countItem(itemId);

        log.debug("Clicking item {} in slot {} (action: {})", itemId, slot, action);
        operationPending = true;

        inventoryHelper.executeClick(slot, action + " item " + itemId)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        interactionTicks = 0;
                        phase = InteractPhase.WAIT_RESPONSE;
                    } else {
                        fail("Failed to click item in inventory");
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to click item", e);
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
            // For simple interactions, timeout is often acceptable (item may not have been consumed)
            log.debug("Interaction wait timed out after {} ticks - completing anyway", INTERACTION_TIMEOUT_TICKS);
            complete();
            return;
        }

        PlayerState player = ctx.getPlayerState();
        InventoryState inventory = ctx.getInventoryState();

        // Check for success indicators
        boolean success = false;
        String successReason = null;

        // Check for animation (eating/drinking typically has animation)
        if (player.isAnimating()) {
            success = true;
            successReason = "playing animation";
        }

        // Check if item count decreased (consumed)
        if (!success) {
            int currentCount = inventory.countItem(itemId);
            if (currentCount < startItemCount) {
                success = true;
                successReason = "item consumed";
            }
        }

        // For "use" action, just completing the click is often enough
        if (!success && "use".equals(action)) {
            success = true;
            successReason = "use action sent";
        }

        if (success) {
            log.info("Inventory interaction successful: {} {} ({})", action, itemId, successReason);
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
        return String.format("InventoryInteract[%s %d]", action, itemId);
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum InteractPhase {
        CLICK_ITEM,
        WAIT_RESPONSE
    }
}

