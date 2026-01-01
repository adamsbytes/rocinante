package com.rocinante.tasks.impl;

import com.rocinante.input.InventoryClickHelper;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.timing.DelayProfile;
import com.rocinante.util.ItemCollections;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reusable task for burying bones from inventory.
 *
 * <p>Features:
 * <ul>
 *   <li>Buries multiple bones in one execution (configurable max)</li>
 *   <li>Supports any bone types from {@link ItemCollections#BONES}</li>
 *   <li>Humanized delays between burials (200-600ms default)</li>
 *   <li>Stops when no more bones or max reached</li>
 * </ul>
 *
 * <p>This task is designed to be spawned as a subtask by {@link CombatTask}
 * when bone burying mode is enabled, but can also be used standalone.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Bury all bones in inventory
 * BuryBonesTask buryAll = new BuryBonesTask();
 *
 * // Bury up to 10 bones
 * BuryBonesTask buryLimited = new BuryBonesTask()
 *     .withMaxBones(10);
 *
 * // Bury only big bones
 * BuryBonesTask buryBigBones = new BuryBonesTask(List.of(ItemID.BIG_BONES));
 * }</pre>
 */
@Slf4j
public class BuryBonesTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Maximum ticks to wait for bury animation response.
     */
    private static final int BURY_TIMEOUT_TICKS = 8;

    /**
     * Default delay range between burying bones (ms).
     * Per REQUIREMENTS.md Section 4.1 - humanized timing.
     */
    private static final int MIN_BURY_DELAY_MS = 200;
    private static final int MAX_BURY_DELAY_MS = 600;

    /**
     * Animation ID for burying bones.
     */
    private static final int BURY_ANIMATION_ID = 827;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Bone item IDs to bury. Defaults to all bones from ItemCollections.
     */
    @Getter
    private final List<Integer> boneIds;

    /**
     * Maximum bones to bury in this execution. -1 means no limit.
     */
    @Getter
    @Setter
    private int maxBones = -1;

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
    private BuryPhase phase = BuryPhase.FIND_BONE;

    /**
     * Number of bones buried in this execution.
     */
    @Getter
    private int bonesBuried = 0;

    /**
     * The item ID of the bone currently being buried.
     */
    private int currentBoneId = -1;

    /**
     * Ticks since last bury click.
     */
    private int buryWaitTicks = 0;

    /**
     * Starting count of current bone type.
     */
    private int startBoneCount = 0;

    /**
     * Whether an async operation is pending.
     */
    private boolean operationPending = false;

    /**
     * Whether we're waiting for the humanized delay between burials.
     */
    private boolean waitingForDelay = false;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a bury bones task that will bury any bones from ItemCollections.BONES.
     */
    public BuryBonesTask() {
        this.boneIds = new ArrayList<>(ItemCollections.BONES);
    }

    /**
     * Create a bury bones task for specific bone types.
     *
     * @param boneIds the bone item IDs to bury
     */
    public BuryBonesTask(Collection<Integer> boneIds) {
        this.boneIds = new ArrayList<>(boneIds);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set maximum bones to bury (builder-style).
     *
     * @param max maximum bones to bury, or -1 for no limit
     * @return this task for chaining
     */
    public BuryBonesTask withMaxBones(int max) {
        this.maxBones = max;
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public BuryBonesTask withDescription(String description) {
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

        // Check if we have any bones to bury
        InventoryState inventory = ctx.getInventoryState();
        int totalBones = countBonesInInventory(inventory);
        
        if (totalBones == 0) {
            log.debug("Cannot bury bones: no bones in inventory");
            return false;
        }

        // Check if InventoryClickHelper is available
        if (ctx.getInventoryClickHelper() == null) {
            log.error("InventoryClickHelper not available in TaskContext");
            return false;
        }

        log.debug("BuryBonesTask ready: {} bones in inventory", totalBones);
        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (operationPending || waitingForDelay) {
            return;
        }

        switch (phase) {
            case FIND_BONE:
                executeFindBone(ctx);
                break;
            case CLICK_BONE:
                executeClickBone(ctx);
                break;
            case WAIT_BURY:
                executeWaitBury(ctx);
                break;
            case DELAY_BETWEEN:
                executeDelayBetween(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Find Bone
    // ========================================================================

    private void executeFindBone(TaskContext ctx) {
        // Check if we've hit the max limit
        if (maxBones > 0 && bonesBuried >= maxBones) {
            log.info("Reached max bones limit ({}), completing task", maxBones);
            complete();
            return;
        }

        InventoryState inventory = ctx.getInventoryState();
        
        // Find first bone in inventory
        currentBoneId = findFirstBone(inventory);
        
        if (currentBoneId == -1) {
            // No more bones to bury
            if (bonesBuried > 0) {
                log.info("Finished burying {} bones (no more bones in inventory)", bonesBuried);
                complete();
            } else {
                fail("No bones found in inventory");
            }
            return;
        }

        startBoneCount = inventory.countItem(currentBoneId);
        log.debug("Found bone {} (count: {}) to bury", currentBoneId, startBoneCount);
        
        recordPhaseTransition(BuryPhase.CLICK_BONE);
        phase = BuryPhase.CLICK_BONE;
    }

    // ========================================================================
    // Phase: Click Bone
    // ========================================================================

    private void executeClickBone(TaskContext ctx) {
        InventoryClickHelper inventoryHelper = ctx.getInventoryClickHelper();
        InventoryState inventory = ctx.getInventoryState();
        
        int slot = inventory.getSlotOf(currentBoneId);
        
        if (slot < 0) {
            log.debug("Bone {} no longer in inventory, finding next", currentBoneId);
            phase = BuryPhase.FIND_BONE;
            return;
        }

        log.debug("Clicking bone {} in slot {} to bury", currentBoneId, slot);
        operationPending = true;

        // Use the InventoryClickHelper which handles humanized clicking
        inventoryHelper.executeClick(slot, "Bury bone " + currentBoneId)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        buryWaitTicks = 0;
                        recordPhaseTransition(BuryPhase.WAIT_BURY);
                        phase = BuryPhase.WAIT_BURY;
                    } else {
                        log.warn("Failed to click bone in inventory");
                        // Try to find another bone
                        phase = BuryPhase.FIND_BONE;
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Exception clicking bone", e);
                    fail("Click failed: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait Bury
    // ========================================================================

    private void executeWaitBury(TaskContext ctx) {
        buryWaitTicks++;

        if (buryWaitTicks > BURY_TIMEOUT_TICKS) {
            // Timeout - bone may have been buried anyway, continue
            log.debug("Bury wait timed out, continuing to next bone");
            bonesBuried++;
            scheduleDelayBetweenBurials(ctx);
            return;
        }

        PlayerState player = ctx.getPlayerState();
        InventoryState inventory = ctx.getInventoryState();

        // Check for success indicators
        boolean success = false;
        String successReason = null;

        // Check for bury animation using PlayerState's isAnimating(int) method
        if (player.isAnimating(BURY_ANIMATION_ID)) {
            success = true;
            successReason = "bury animation";
        }

        // Check if bone count decreased
        if (!success) {
            int currentCount = inventory.countItem(currentBoneId);
            if (currentCount < startBoneCount) {
                success = true;
                successReason = "bone consumed";
            }
        }

        if (success) {
            log.debug("Bone {} buried successfully ({})", currentBoneId, successReason);
            bonesBuried++;
            scheduleDelayBetweenBurials(ctx);
            return;
        }

        log.debug("Waiting for bury response (tick {})", buryWaitTicks);
    }

    // ========================================================================
    // Phase: Delay Between
    // ========================================================================

    private void scheduleDelayBetweenBurials(TaskContext ctx) {
        // Check if we should continue burying
        if (maxBones > 0 && bonesBuried >= maxBones) {
            log.info("Reached max bones limit ({}), completing task", maxBones);
            complete();
            return;
        }

        // Check if more bones exist
        InventoryState inventory = ctx.getInventoryState();
        if (countBonesInInventory(inventory) == 0) {
            log.info("Finished burying {} bones (no more bones)", bonesBuried);
            complete();
            return;
        }

        // Schedule humanized delay before next burial
        recordPhaseTransition(BuryPhase.DELAY_BETWEEN);
        phase = BuryPhase.DELAY_BETWEEN;
        waitingForDelay = true;

        ctx.getHumanTimer().sleep(DelayProfile.ACTION_GAP)
                .thenRun(() -> {
                    waitingForDelay = false;
                    phase = BuryPhase.FIND_BONE;
                });
    }

    private void executeDelayBetween(TaskContext ctx) {
        // This phase is handled by async delay scheduling
        // If we get here, the delay is complete
        if (!waitingForDelay) {
            phase = BuryPhase.FIND_BONE;
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Find the first bone in inventory from our bone list.
     *
     * @param inventory the inventory state
     * @return the bone item ID, or -1 if not found
     */
    private int findFirstBone(InventoryState inventory) {
        for (int boneId : boneIds) {
            if (inventory.hasItem(boneId)) {
                return boneId;
            }
        }
        return -1;
    }

    /**
     * Count total bones in inventory from our bone list.
     *
     * @param inventory the inventory state
     * @return total bone count
     */
    private int countBonesInInventory(InventoryState inventory) {
        int total = 0;
        for (int boneId : boneIds) {
            total += inventory.countItem(boneId);
        }
        return total;
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        if (maxBones > 0) {
            return String.format("BuryBones[max=%d, buried=%d]", maxBones, bonesBuried);
        }
        return String.format("BuryBones[buried=%d]", bonesBuried);
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum BuryPhase {
        /** Find next bone to bury */
        FIND_BONE,
        /** Click the bone to bury it */
        CLICK_BONE,
        /** Wait for bury animation/confirmation */
        WAIT_BURY,
        /** Humanized delay between burials */
        DELAY_BETWEEN
    }
}

