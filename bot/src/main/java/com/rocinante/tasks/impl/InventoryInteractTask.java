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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

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
     * Acceptable item IDs to interact with.
     * Ordered by priority - first match wins.
     */
    @Getter
    private final List<Integer> itemIds;

    /**
     * The action to perform (eat, drink, use, drop).
     */
    @Getter
    private final String action;

    /**
     * The resolved item ID found in inventory.
     */
    private int resolvedItemId = -1;

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
    // Constructors
    // ========================================================================

    /**
     * Create an inventory interact task with a single item.
     *
     * @param itemId the item ID to interact with
     * @param action the action to perform (eat, drink, use, drop)
     */
    public InventoryInteractTask(int itemId, String action) {
        this.itemIds = Collections.singletonList(itemId);
        this.action = action.toLowerCase();
        this.timeout = Duration.ofSeconds(15);
    }

    /**
     * Create an inventory interact task accepting any of the provided items.
     * Items are checked in order - first match wins (for priority ordering).
     *
     * @param itemIds acceptable item IDs to interact with, ordered by priority
     * @param action  the action to perform (eat, drink, use, drop)
     */
    public InventoryInteractTask(Collection<Integer> itemIds, String action) {
        this.itemIds = new ArrayList<>(itemIds);
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

        // Find any matching item in inventory
        InventoryState inventory = ctx.getInventoryState();
        resolvedItemId = findFirstMatchingItem(inventory, itemIds);
        if (resolvedItemId == -1) {
            log.debug("No item from {} found in inventory. Inventory contains: {}",
                    itemIds, formatInventoryContents(inventory));
            return false;
        }

        log.debug("Resolved item {} from acceptable list {}", resolvedItemId, itemIds);
        return true;
    }

    /**
     * Find the first item ID from the list that exists in inventory.
     * Returns in priority order (first in list = highest priority).
     */
    private int findFirstMatchingItem(InventoryState inventory, List<Integer> itemIds) {
        for (int itemId : itemIds) {
            if (inventory.hasItem(itemId)) {
                return itemId;
            }
        }
        return -1;
    }

    /**
     * Format inventory contents for debug logging.
     */
    private String formatInventoryContents(InventoryState inventory) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (net.runelite.api.Item item : inventory.getNonEmptyItems()) {
            if (!first) sb.append(", ");
            sb.append(item.getId());
            if (item.getQuantity() > 1) {
                sb.append("x").append(item.getQuantity());
            }
            first = false;
        }
        sb.append("]");
        return sb.toString();
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
        int slot = inventory.getSlotOf(resolvedItemId);

        if (slot < 0) {
            log.debug("Item {} no longer in inventory. Inventory: {}",
                    resolvedItemId, formatInventoryContents(inventory));
            fail("Item " + resolvedItemId + " not found in inventory");
            return;
        }

        // Store starting state
        startItemCount = inventory.countItem(resolvedItemId);

        // Log slot position for debugging click issues
        int row = slot / 4;
        int col = slot % 4;
        log.debug("Clicking item {} in slot {} (row={}, col={}, action='{}', count={})",
                resolvedItemId, slot, row, col, action, startItemCount);
        operationPending = true;

        inventoryHelper.executeClick(slot, action + " item " + resolvedItemId)
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
        int animationId = player.getAnimationId();
        if (player.isAnimating()) {
            success = true;
            successReason = "playing animation (id=" + animationId + ")";
        }

        // Check if item count decreased (consumed)
        if (!success) {
            int currentCount = inventory.countItem(resolvedItemId);
            if (currentCount < startItemCount) {
                success = true;
                successReason = "item consumed (" + startItemCount + " -> " + currentCount + ")";
            }
        }

        // For "use" action, just completing the click is often enough
        if (!success && "use".equals(action)) {
            success = true;
            successReason = "use action sent";
        }

        if (success) {
            log.debug("Inventory interaction successful: {} {} ({})", action, resolvedItemId, successReason);
            complete();
            return;
        }

        log.trace("Waiting for interaction response (tick {}/{}, animating={}, count={})",
                interactionTicks, INTERACTION_TIMEOUT_TICKS, player.isAnimating(),
                inventory.countItem(resolvedItemId));
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        if (itemIds.size() == 1) {
            return String.format("InventoryInteract[%s %d]", action, itemIds.get(0));
        }
        return String.format("InventoryInteract[%s %s]", action, itemIds);
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum InteractPhase {
        CLICK_ITEM,
        WAIT_RESPONSE
    }
}

