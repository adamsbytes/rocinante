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
 * Task for using one inventory item on another inventory item.
 *
 * <p>This task performs a two-step interaction:
 * <ol>
 *   <li>Click the source item in inventory (enters "Use" cursor mode)</li>
 *   <li>Click the target item in inventory</li>
 * </ol>
 *
 * <p>Supports single items or collections of acceptable items:
 * <pre>{@code
 * // Use tinderbox on logs (make fire)
 * UseItemOnItemTask lightFire = new UseItemOnItemTask(ItemID.TINDERBOX, ItemID.LOGS);
 *
 * // Use ANY tinderbox on ANY basic logs
 * UseItemOnItemTask lightAnyFire = new UseItemOnItemTask(
 *     Set.of(ItemID.TINDERBOX, ItemID.GOLDEN_TINDERBOX),
 *     Set.of(ItemID.LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS))
 *     .withDescription("Light a fire");
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
     * Acceptable source item IDs (the item to use).
     * Ordered by priority - first match wins.
     */
    @Getter
    private final List<Integer> sourceItemIds;

    /**
     * Acceptable target item IDs (the item to use the source on).
     * Ordered by priority - first match wins.
     */
    @Getter
    private final List<Integer> targetItemIds;

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
     * The resolved source item ID found in inventory.
     */
    private int resolvedSourceId = -1;

    /**
     * The resolved target item ID found in inventory.
     */
    private int resolvedTargetId = -1;

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
    // Constructors
    // ========================================================================

    /**
     * Create a use item on item task with single item IDs.
     *
     * @param sourceItemId the source item ID (to use)
     * @param targetItemId the target item ID (to use on)
     */
    public UseItemOnItemTask(int sourceItemId, int targetItemId) {
        this.sourceItemIds = Collections.singletonList(sourceItemId);
        this.targetItemIds = Collections.singletonList(targetItemId);
        this.timeout = Duration.ofSeconds(30);
    }

    /**
     * Create a use item on item task accepting any of the provided items.
     * Items are checked in order - first match wins (for priority ordering).
     *
     * @param sourceItemIds acceptable source item IDs (to use), ordered by priority
     * @param targetItemIds acceptable target item IDs (to use on), ordered by priority
     */
    public UseItemOnItemTask(Collection<Integer> sourceItemIds, Collection<Integer> targetItemIds) {
        this.sourceItemIds = new ArrayList<>(sourceItemIds);
        this.targetItemIds = new ArrayList<>(targetItemIds);
        this.timeout = Duration.ofSeconds(30);
    }

    /**
     * Create a use item on item task with single source and multiple targets.
     *
     * @param sourceItemId  the source item ID (to use)
     * @param targetItemIds acceptable target item IDs (to use on), ordered by priority
     */
    public UseItemOnItemTask(int sourceItemId, Collection<Integer> targetItemIds) {
        this.sourceItemIds = Collections.singletonList(sourceItemId);
        this.targetItemIds = new ArrayList<>(targetItemIds);
        this.timeout = Duration.ofSeconds(30);
    }

    /**
     * Create a use item on item task with multiple sources and single target.
     *
     * @param sourceItemIds acceptable source item IDs (to use), ordered by priority
     * @param targetItemId  the target item ID (to use on)
     */
    public UseItemOnItemTask(Collection<Integer> sourceItemIds, int targetItemId) {
        this.sourceItemIds = new ArrayList<>(sourceItemIds);
        this.targetItemIds = Collections.singletonList(targetItemId);
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

        InventoryState inventory = ctx.getInventoryState();

        // Find any matching source item
        resolvedSourceId = findFirstMatchingItem(inventory, sourceItemIds);
        if (resolvedSourceId == -1) {
            log.debug("No source item from {} found in inventory. Inventory contains: {}",
                    sourceItemIds, formatInventoryContents(inventory));
            return false;
        }

        // Find any matching target item
        resolvedTargetId = findFirstMatchingItem(inventory, targetItemIds);
        if (resolvedTargetId == -1) {
            log.debug("No target item from {} found in inventory. Inventory contains: {}",
                    targetItemIds, formatInventoryContents(inventory));
            return false;
        }

        log.debug("Resolved source={} target={} from acceptable sets", resolvedSourceId, resolvedTargetId);
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
        int slot = inventory.getSlotOf(resolvedSourceId);

        if (slot < 0) {
            log.debug("Source item {} no longer in inventory. Inventory: {}",
                    resolvedSourceId, formatInventoryContents(inventory));
            fail("Source item " + resolvedSourceId + " not found in inventory");
            return;
        }

        log.debug("Clicking source item {} in slot {}", resolvedSourceId, slot);
        operationPending = true;

        inventoryHelper.executeClick(slot, "Use source item " + resolvedSourceId)
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
        int slot = inventory.getSlotOf(resolvedTargetId);

        if (slot < 0) {
            log.debug("Target item {} no longer in inventory. Inventory: {}",
                    resolvedTargetId, formatInventoryContents(inventory));
            fail("Target item " + resolvedTargetId + " not found in inventory");
            return;
        }

        // Store starting state for success detection
        startAnimation = ctx.getPlayerState().getAnimationId();
        startTargetCount = inventory.countItem(resolvedTargetId);

        log.debug("Clicking target item {} in slot {}", resolvedTargetId, slot);
        operationPending = true;

        inventoryHelper.executeClick(slot, "Use on target item " + resolvedTargetId)
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
            int currentCount = inventory.countItem(resolvedTargetId);
            if (currentCount < startTargetCount) {
                success = true;
                successReason = "item consumed/transformed";
            }
        }

        // Check if source item was consumed
        if (!success && !inventory.hasItem(resolvedSourceId)) {
            success = true;
            successReason = "source item consumed";
        }

        if (success) {
            log.info("Use item on item successful: {} on {} ({})", resolvedSourceId, resolvedTargetId, successReason);
            complete();
            return;
        }

        log.debug("Waiting for interaction response (tick {})", interactionTicks);
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        if (sourceItemIds.size() == 1 && targetItemIds.size() == 1) {
            return String.format("UseItemOnItem[source=%d, target=%d]",
                    sourceItemIds.iterator().next(), targetItemIds.iterator().next());
        }
        return String.format("UseItemOnItem[sources=%s, targets=%s]", sourceItemIds, targetItemIds);
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
