package com.rocinante.tasks.impl.skills.thieving;

import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.ItemID;
import net.runelite.api.Varbits;

/**
 * Tracks Dodgy Necklace charges and handles auto-replacement.
 *
 * <p>The Dodgy Necklace is a crucial item for Thieving training:
 * <ul>
 *   <li>Provides 25% chance to avoid being stunned and damaged when pickpocketing</li>
 *   <li>Has 10 charges per necklace</li>
 *   <li>Crumbles to dust when charges are depleted</li>
 *   <li>Charges are tracked via Varbit 5820</li>
 * </ul>
 *
 * <p>This tracker:
 * <ul>
 *   <li>Monitors current charge count via varbits</li>
 *   <li>Detects when necklace breaks (charges hit 0)</li>
 *   <li>Tracks total necklaces consumed</li>
 *   <li>Checks for replacement necklaces in inventory</li>
 *   <li>Can trigger auto-equip of replacement</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * DodgyNecklaceTracker tracker = new DodgyNecklaceTracker();
 *
 * // In task execution loop
 * tracker.update(ctx);
 *
 * if (tracker.needsReplacement()) {
 *     if (tracker.hasReplacementInInventory(ctx)) {
 *         // Equip new necklace
 *         equipReplacementTask.execute(ctx);
 *     } else {
 *         // No replacements - may need to bank
 *         log.warn("Out of dodgy necklaces!");
 *     }
 * }
 *
 * // Get stats
 * log.info("Charges remaining: {}, Necklaces used: {}",
 *     tracker.getCurrentCharges(), tracker.getNecklacesConsumed());
 * }</pre>
 */
@Slf4j
public class DodgyNecklaceTracker {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Dodgy Necklace item ID.
     * From RuneLite ItemID.DODGY_NECKLACE (21143).
     */
    public static final int DODGY_NECKLACE_ID = ItemID.DODGY_NECKLACE;

    /**
     * Varbit that tracks Dodgy Necklace charges.
     * Value 1-10 when wearing necklace, 0 when not wearing or depleted.
     */
    public static final int DODGY_NECKLACE_CHARGES_VARBIT = 5820;

    /**
     * Maximum charges on a fresh Dodgy Necklace.
     */
    public static final int MAX_CHARGES = 10;

    /**
     * Threshold at which to warn about low charges.
     */
    public static final int LOW_CHARGE_THRESHOLD = 3;

    // ========================================================================
    // State
    // ========================================================================

    /**
     * Current number of charges on equipped necklace.
     * 0 if not wearing a dodgy necklace.
     */
    @Getter
    private int currentCharges = 0;

    /**
     * Previous charge count (for detecting charge usage).
     */
    private int previousCharges = 0;

    /**
     * Whether a dodgy necklace is currently equipped.
     */
    @Getter
    private boolean necklaceEquipped = false;

    /**
     * Total number of necklaces consumed (broken) this session.
     */
    @Getter
    private int necklacesConsumed = 0;

    /**
     * Total charges used this session.
     */
    @Getter
    private int totalChargesUsed = 0;

    /**
     * Number of stuns prevented by the necklace this session.
     * Estimated from charge usage.
     */
    @Getter
    private int stunsPrevented = 0;

    /**
     * Last tick we updated on.
     */
    private int lastUpdateTick = -1;

    /**
     * Whether we need to equip a replacement necklace.
     */
    @Getter
    private boolean needsReplacement = false;

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Update tracker state. Call this every game tick.
     *
     * @param ctx the task context
     */
    public void update(TaskContext ctx) {
        if (ctx == null || !ctx.isLoggedIn()) {
            return;
        }

        Client client = ctx.getClient();
        int currentTick = client.getTickCount();

        // Don't double-update on same tick
        if (currentTick == lastUpdateTick) {
            return;
        }
        lastUpdateTick = currentTick;

        // Read charge count from varbit
        int newCharges = client.getVarbitValue(DODGY_NECKLACE_CHARGES_VARBIT);

        // Check if necklace is equipped
        EquipmentState equipment = ctx.getEquipmentState();
        boolean wasEquipped = necklaceEquipped;
        necklaceEquipped = equipment != null && equipment.hasEquipped(DODGY_NECKLACE_ID);

        // Detect charge changes
        if (newCharges < previousCharges && previousCharges > 0) {
            // Charge was used (stun was prevented)
            int chargesUsed = previousCharges - newCharges;
            totalChargesUsed += chargesUsed;
            stunsPrevented += chargesUsed; // Each charge use = 1 stun prevented

            log.debug("Dodgy necklace saved from stun! Charges: {} -> {} (total saves: {})",
                    previousCharges, newCharges, stunsPrevented);

            // Check if necklace broke (went from >0 to 0)
            if (newCharges == 0 && previousCharges > 0) {
                onNecklaceBroken();
            }
        }

        // Detect new necklace equipped (charges went from 0 to >0, or higher)
        if (newCharges > previousCharges && previousCharges == 0 && newCharges > 0) {
            log.debug("New dodgy necklace equipped with {} charges", newCharges);
            needsReplacement = false;
        }

        // Update state
        previousCharges = currentCharges;
        currentCharges = newCharges;

        // Set replacement flag if not wearing one and we should be
        if (!necklaceEquipped && wasEquipped) {
            needsReplacement = true;
        }
    }

    /**
     * Check if we have low charges and should consider replacing soon.
     *
     * @return true if charges are at or below the low threshold
     */
    public boolean hasLowCharges() {
        return necklaceEquipped && currentCharges > 0 && currentCharges <= LOW_CHARGE_THRESHOLD;
    }

    /**
     * Check if there's a replacement necklace in inventory.
     *
     * @param ctx the task context
     * @return true if inventory contains at least one dodgy necklace
     */
    public boolean hasReplacementInInventory(TaskContext ctx) {
        if (ctx == null) {
            return false;
        }
        InventoryState inventory = ctx.getInventoryState();
        return inventory != null && inventory.hasItem(DODGY_NECKLACE_ID);
    }

    /**
     * Count replacement necklaces in inventory.
     *
     * @param ctx the task context
     * @return number of dodgy necklaces in inventory
     */
    public int countReplacementsInInventory(TaskContext ctx) {
        if (ctx == null) {
            return 0;
        }
        InventoryState inventory = ctx.getInventoryState();
        return inventory != null ? inventory.countItem(DODGY_NECKLACE_ID) : 0;
    }

    /**
     * Get inventory slot containing a replacement necklace.
     *
     * @param ctx the task context
     * @return slot index (0-27) or -1 if none found
     */
    public int getReplacementSlot(TaskContext ctx) {
        if (ctx == null) {
            return -1;
        }
        InventoryState inventory = ctx.getInventoryState();
        return inventory != null ? inventory.getSlotOf(DODGY_NECKLACE_ID) : -1;
    }

    /**
     * Mark that we've acknowledged the need for replacement.
     * Call this after successfully equipping a new necklace.
     */
    public void acknowledgeReplacement() {
        needsReplacement = false;
    }

    /**
     * Estimate how many more pickpockets until necklace breaks.
     * Assumes 25% chance to use a charge per pickpocket attempt.
     *
     * @return estimated pickpockets remaining, or -1 if not wearing necklace
     */
    public int estimateRemainingPickpockets() {
        if (!necklaceEquipped || currentCharges == 0) {
            return -1;
        }
        // On average, we use 1 charge per 4 failed pickpockets
        // But stun rate varies by thieving level, so this is rough
        return currentCharges * 4; // Conservative estimate
    }

    /**
     * Get effectiveness stats as a formatted string.
     *
     * @return stats string for logging
     */
    public String getStatsString() {
        return String.format("Dodgy Stats: charges=%d, necklaces=%d, saves=%d, total_charges=%d",
                currentCharges, necklacesConsumed, stunsPrevented, totalChargesUsed);
    }

    /**
     * Reset all state (for new session/task).
     */
    public void reset() {
        currentCharges = 0;
        previousCharges = 0;
        necklaceEquipped = false;
        necklacesConsumed = 0;
        totalChargesUsed = 0;
        stunsPrevented = 0;
        lastUpdateTick = -1;
        needsReplacement = false;
    }

    // ========================================================================
    // Event Handlers
    // ========================================================================

    private void onNecklaceBroken() {
        necklacesConsumed++;
        needsReplacement = true;
        log.info("Dodgy necklace broke! Total consumed: {}, Total stuns prevented: {}",
                necklacesConsumed, stunsPrevented);
    }
}

