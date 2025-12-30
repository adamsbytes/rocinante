package com.rocinante.tasks.impl;

import com.rocinante.input.InventoryClickHelper;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.state.InventoryState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;

import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.*;

/**
 * Task for dropping items from inventory using shift+click.
 *
 * <p>This task implements humanized item dropping as specified in REQUIREMENTS.md Section 5.4.6:
 * <ul>
 *   <li>Shift+click dropping (hold shift, click items sequentially, release shift)</li>
 *   <li>Humanized delays between drops (80-200ms)</li>
 *   <li>Occasional micro-pauses between drops (5-10% chance)</li>
 *   <li>Support for keeping N of each item (for tools)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Drop all iron ore
 * DropInventoryTask dropOre = new DropInventoryTask(List.of(440));
 *
 * // Drop all fish but keep 1 (for karambwan combo eating)
 * DropInventoryTask dropFish = new DropInventoryTask(List.of(331, 335))
 *     .keepAmount(1);
 *
 * // Drop specific items for power mining
 * DropInventoryTask powerMine = DropInventoryTask.forItemIds(List.of(436, 438, 440))
 *     .withDescription("Drop ores");
 * }</pre>
 */
@Slf4j
public class DropInventoryTask extends AbstractTask {

    // ========================================================================
    // Constants - Humanization (REQUIREMENTS 5.4.6)
    // ========================================================================

    /**
     * Minimum delay between item drops in milliseconds.
     */
    private static final long MIN_DROP_DELAY_MS = 80;

    /**
     * Maximum delay between item drops in milliseconds.
     */
    private static final long MAX_DROP_DELAY_MS = 200;

    /**
     * Probability of a micro-pause between drops.
     */
    private static final double MICRO_PAUSE_CHANCE = 0.07; // 7% chance

    /**
     * Minimum micro-pause duration in milliseconds.
     */
    private static final long MIN_MICRO_PAUSE_MS = 400;

    /**
     * Maximum micro-pause duration in milliseconds.
     */
    private static final long MAX_MICRO_PAUSE_MS = 1200;

    /**
     * Delay before pressing shift to simulate natural behavior.
     */
    private static final long PRE_SHIFT_DELAY_MS = 100;

    /**
     * Delay after releasing shift.
     */
    private static final long POST_SHIFT_DELAY_MS = 50;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Item IDs to drop.
     */
    @Getter
    private final Set<Integer> itemIdsToDrop;

    /**
     * Number of each item type to keep (not drop).
     */
    @Getter
    private int keepAmount = 0;

    /**
     * Custom task description.
     */
    private String description;

    /**
     * Drop pattern: SEQUENTIAL (top to bottom), RANDOM, or COLUMN (column by column).
     */
    @Getter
    private DropPattern dropPattern = DropPattern.SEQUENTIAL;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private DropPhase phase = DropPhase.PREPARE;

    /**
     * Queue of slots to drop.
     */
    private final Queue<Integer> slotsToDropQueue = new LinkedList<>();

    /**
     * Whether an async operation is pending.
     */
    private volatile boolean operationPending = false;

    /**
     * Count of items dropped this session.
     */
    private int droppedCount = 0;

    /**
     * Whether shift key is currently held.
     */
    private boolean shiftHeld = false;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a drop task for specific item IDs.
     *
     * @param itemIds the item IDs to drop
     */
    public DropInventoryTask(Collection<Integer> itemIds) {
        this.itemIdsToDrop = new HashSet<>(itemIds);
        this.timeout = Duration.ofMinutes(2);
    }

    /**
     * Create a drop task for a single item ID.
     *
     * @param itemId the item ID to drop
     */
    public DropInventoryTask(int itemId) {
        this(List.of(itemId));
    }

    /**
     * Static factory for creating drop task.
     *
     * @param itemIds the item IDs to drop
     * @return new DropInventoryTask
     */
    public static DropInventoryTask forItemIds(Collection<Integer> itemIds) {
        return new DropInventoryTask(itemIds);
    }

    /**
     * Static factory for creating drop task from varargs.
     *
     * @param itemIds the item IDs to drop
     * @return new DropInventoryTask
     */
    public static DropInventoryTask forItemIds(int... itemIds) {
        List<Integer> list = new ArrayList<>();
        for (int id : itemIds) {
            list.add(id);
        }
        return new DropInventoryTask(list);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set number of each item to keep (builder-style).
     *
     * @param amount the amount to keep
     * @return this task for chaining
     */
    public DropInventoryTask keepAmount(int amount) {
        this.keepAmount = Math.max(0, amount);
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public DropInventoryTask withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * Set drop pattern (builder-style).
     *
     * @param pattern the drop pattern
     * @return this task for chaining
     */
    public DropInventoryTask withPattern(DropPattern pattern) {
        this.dropPattern = pattern;
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

        // Check if we have any items to drop
        InventoryState inventory = ctx.getInventoryState();
        for (int itemId : itemIdsToDrop) {
            int count = inventory.countItem(itemId);
            if (count > keepAmount) {
                return true;
            }
        }

        log.debug("No items to drop (or keeping all)");
        return false;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (operationPending) {
            return;
        }

        switch (phase) {
            case PREPARE:
                executePrepare(ctx);
                break;
            case HOLD_SHIFT:
                executeHoldShift(ctx);
                break;
            case DROP_ITEMS:
                executeDropItems(ctx);
                break;
            case RELEASE_SHIFT:
                executeReleaseShift(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Prepare
    // ========================================================================

    private void executePrepare(TaskContext ctx) {
        InventoryState inventory = ctx.getInventoryState();

        // Build list of slots to drop
        List<Integer> slotsToProcess = new ArrayList<>();

        // Track counts per item ID to respect keepAmount
        Map<Integer, Integer> keptCounts = new HashMap<>();
        for (int itemId : itemIdsToDrop) {
            keptCounts.put(itemId, 0);
        }

        // Find all slots containing items we want to drop
        for (int slot = 0; slot < InventoryState.INVENTORY_SIZE; slot++) {
            Optional<Item> itemOpt = inventory.getItemInSlot(slot);
            if (itemOpt.isEmpty()) {
                continue;
            }

            Item item = itemOpt.get();
            if (!itemIdsToDrop.contains(item.getId())) {
                continue;
            }

            // Check if we should keep this one
            int kept = keptCounts.getOrDefault(item.getId(), 0);
            if (kept < keepAmount) {
                keptCounts.put(item.getId(), kept + 1);
                continue;
            }

            slotsToProcess.add(slot);
        }

        if (slotsToProcess.isEmpty()) {
            log.info("No items to drop after keeping {} of each", keepAmount);
            complete();
            return;
        }

        // Apply drop pattern
        List<Integer> orderedSlots = applyDropPattern(slotsToProcess);
        slotsToDropQueue.clear();
        slotsToDropQueue.addAll(orderedSlots);

        log.debug("Prepared {} slots to drop with pattern {}", slotsToDropQueue.size(), dropPattern);
        phase = DropPhase.HOLD_SHIFT;
    }

    /**
     * Apply the configured drop pattern to order the slots.
     */
    private List<Integer> applyDropPattern(List<Integer> slots) {
        List<Integer> result = new ArrayList<>(slots);

        switch (dropPattern) {
            case SEQUENTIAL:
                // Already in order (top-left to bottom-right)
                break;

            case RANDOM:
                Collections.shuffle(result);
                break;

            case COLUMN:
                // Sort by column first, then row
                result.sort((a, b) -> {
                    int colA = a % 4;
                    int colB = b % 4;
                    if (colA != colB) {
                        return Integer.compare(colA, colB);
                    }
                    return Integer.compare(a / 4, b / 4);
                });
                break;
        }

        return result;
    }

    // ========================================================================
    // Phase: Hold Shift
    // ========================================================================

    private void executeHoldShift(TaskContext ctx) {
        RobotKeyboardController keyboard = ctx.getKeyboardController();
        if (keyboard == null) {
            log.error("Keyboard controller is null, cannot drop items");
            fail("No keyboard controller available");
            return;
        }
        
        operationPending = true;

        // Small delay before pressing shift (natural behavior)
        ctx.getHumanTimer().sleep(PRE_SHIFT_DELAY_MS)
                .thenRun(() -> {
                    keyboard.holdKey(KeyEvent.VK_SHIFT)
                            .thenAccept(v -> {
                                shiftHeld = true;
                                operationPending = false;
                                phase = DropPhase.DROP_ITEMS;
                                log.debug("Shift key held down for dropping");
                            })
                            .exceptionally(e -> {
                                operationPending = false;
                                log.error("Failed to hold shift key", e);
                                fail("Shift key failed: " + e.getMessage());
                                return null;
                            });
                });
    }

    // ========================================================================
    // Phase: Drop Items
    // ========================================================================

    private void executeDropItems(TaskContext ctx) {
        if (slotsToDropQueue.isEmpty()) {
            phase = DropPhase.RELEASE_SHIFT;
            return;
        }

        InventoryClickHelper inventoryHelper = ctx.getInventoryClickHelper();
        RobotKeyboardController keyboard = ctx.getKeyboardController();
        Randomization random = ctx.getRandomization();

        int slot = slotsToDropQueue.poll();

        // Verify item is still there
        InventoryState inventory = ctx.getInventoryState();
        Optional<Item> itemOpt = inventory.getItemInSlot(slot);
        if (itemOpt.isEmpty() || !itemIdsToDrop.contains(itemOpt.get().getId())) {
            // Item moved or already dropped, skip to next
            log.trace("Slot {} no longer contains target item, skipping", slot);
            return;
        }

        operationPending = true;

        // Execute shift+click drop
        executeShiftClickDrop(ctx, slot, random);
    }

    /**
     * Execute a single shift+click drop with humanization.
     * Shift is already held from executeHoldShift phase.
     */
    private void executeShiftClickDrop(TaskContext ctx, int slot, Randomization random) {
        InventoryClickHelper inventoryHelper = ctx.getInventoryClickHelper();

        // Shift is already held, just click the item
        inventoryHelper.executeClick(slot, "Drop item")
                .thenAccept(success -> {
                    if (success) {
                        droppedCount++;
                        log.trace("Dropped item in slot {} ({} total)", slot, droppedCount);

                        // Schedule next drop with humanized delay
                        long delay = random.uniformRandomLong(MIN_DROP_DELAY_MS, MAX_DROP_DELAY_MS);

                        // Occasional micro-pause
                        if (random.chance(MICRO_PAUSE_CHANCE) && !slotsToDropQueue.isEmpty()) {
                            long pause = random.uniformRandomLong(MIN_MICRO_PAUSE_MS, MAX_MICRO_PAUSE_MS);
                            delay += pause;
                            log.trace("Adding micro-pause of {}ms", pause);
                        }

                        ctx.getHumanTimer().sleep(delay)
                                .thenRun(() -> operationPending = false);
                    } else {
                        log.warn("Failed to click slot {} for dropping", slot);
                        operationPending = false;
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Drop operation failed", e);
                    return null;
                });
    }

    // ========================================================================
    // Phase: Release Shift
    // ========================================================================

    private void executeReleaseShift(TaskContext ctx) {
        RobotKeyboardController keyboard = ctx.getKeyboardController();
        operationPending = true;

        // Release the shift key that was held during dropping
        keyboard.releaseKey(KeyEvent.VK_SHIFT)
                .thenCompose(v -> ctx.getHumanTimer().sleep(POST_SHIFT_DELAY_MS))
                .thenRun(() -> {
                    shiftHeld = false;
                    operationPending = false;
                    log.info("Dropped {} items successfully", droppedCount);
                    complete();
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to release shift key", e);
                    // Still complete - items were dropped
                    complete();
                    return null;
                });
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("DropInventory[items=%s, keep=%d]", itemIdsToDrop, keepAmount);
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Override
    public void onComplete(TaskContext ctx) {
        super.onComplete(ctx);
        log.info("Completed dropping {} items", droppedCount);
    }

    @Override
    public void onFail(TaskContext ctx, Exception e) {
        super.onFail(ctx, e);
        // Make sure shift is released if we fail mid-drop
        if (shiftHeld && ctx.getKeyboardController() != null) {
            log.debug("Releasing shift key after failure");
            ctx.getKeyboardController().releaseKey(KeyEvent.VK_SHIFT);
        }
    }

    // ========================================================================
    // Enums
    // ========================================================================

    /**
     * Execution phases for the drop task.
     */
    private enum DropPhase {
        PREPARE,
        HOLD_SHIFT,
        DROP_ITEMS,
        RELEASE_SHIFT
    }

    /**
     * Patterns for drop order.
     */
    public enum DropPattern {
        /**
         * Drop items in slot order (0 to 27, top-left to bottom-right).
         */
        SEQUENTIAL,

        /**
         * Drop items in random order.
         */
        RANDOM,

        /**
         * Drop items column by column (humanizes mouse movement).
         */
        COLUMN
    }
}

