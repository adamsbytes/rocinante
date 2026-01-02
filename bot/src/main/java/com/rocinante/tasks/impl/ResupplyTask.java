package com.rocinante.tasks.impl;

import com.rocinante.behavior.ActionSequencer;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import javax.annotation.Nullable;
import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reusable task for banking and resupply operations.
 *
 * <p>Handles the complete resupply flow:
 * <ol>
 *   <li>Walk to bank (specific location or nearest)</li>
 *   <li>Open bank interface</li>
 *   <li>Optionally deposit inventory/equipment</li>
 *   <li>Withdraw specified items</li>
 *   <li>Close bank</li>
 *   <li>Optionally return to original position</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Simple resupply - get 10 sharks
 * ResupplyTask resupply = ResupplyTask.builder()
 *     .withdrawItem(ItemID.SHARK, 10)
 *     .depositInventory(true)
 *     .build();
 *
 * // Complex resupply with return to position
 * ResupplyTask resupply = ResupplyTask.builder()
 *     .bankLocation(new WorldPoint(3185, 3436, 0))  // Varrock west
 *     .depositInventory(true)
 *     .withdrawItem(ItemID.SHARK, 15)
 *     .withdrawItem(ItemID.SUPER_COMBAT_POTION4, 2)
 *     .withdrawItem(ItemID.PRAYER_POTION4, 4)
 *     .returnPosition(playerCurrentPosition)
 *     .build();
 * }</pre>
 *
 * <p>This task is designed to be used by:
 * <ul>
 *   <li>CombatTask - for combat resupply trips</li>
 *   <li>SlayerManager - for equipment and finish-off items</li>
 *   <li>SkillTask - for skilling supplies</li>
 *   <li>Any other task needing banking functionality</li>
 * </ul>
 */
@Slf4j
public class ResupplyTask extends AbstractTask {

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Specific bank location to walk to, or null to find nearest.
     */
    @Getter
    @Nullable
    private final WorldPoint bankLocation;

    /**
     * Items to withdraw from bank (itemId -> quantity).
     * Uses LinkedHashMap to preserve insertion order for predictable withdrawal sequence.
     */
    @Getter
    private final Map<Integer, Integer> withdrawItems;

    /**
     * Whether to deposit entire inventory before withdrawing.
     */
    @Getter
    private final boolean depositInventory;

    /**
     * Whether to deposit equipment before withdrawing.
     */
    @Getter
    private final boolean depositEquipment;

    /**
     * Position to return to after banking, or null to stay at bank.
     */
    @Getter
    @Nullable
    private final WorldPoint returnPosition;

    /**
     * Maximum distance considered "at bank" for skipping walk.
     */
    @Getter
    private final int bankProximityThreshold;

    /**
     * Maximum distance considered "at return position".
     */
    @Getter
    private final int returnProximityThreshold;

    /**
     * Item IDs to keep when depositing inventory (not deposited).
     * When depositInventory is true and this set is non-empty, items in this set
     * are preserved in inventory while all other items are deposited.
     */
    @Getter
    private final Set<Integer> exceptItemIds;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current phase in the resupply state machine.
     */
    private ResupplyPhase phase = ResupplyPhase.WALK_TO_BANK;

    /**
     * Active sub-task being executed (walk, bank operations, etc.).
     */
    @Nullable
    private Task activeSubTask;

    /**
     * Iterator index for withdrawing items one at a time.
     */
    private int withdrawIndex = 0;

    /**
     * Array of withdraw entries for iteration.
     */
    @Nullable
    private Map.Entry<Integer, Integer>[] withdrawEntries;

    /**
     * Flag to prevent concurrent operations.
     */
    private volatile boolean operationPending = false;
    
    /**
     * Sequenced operations to perform (from ActionSequencer).
     */
    @Nullable
    private List<ActionSequencer.BankOperation> operationSequence;
    
    /**
     * Current index in the operation sequence.
     */
    private int operationIndex = 0;
    
    /**
     * Whether operations sequence has been initialized.
     */
    private boolean sequenceInitialized = false;
    
    /**
     * The sequence type used (for reinforcement on success).
     */
    @Nullable
    private String usedSequenceType;
    
    /**
     * Tracks whether equipment deposit has been done (within deposit phase).
     */
    private boolean equipmentDeposited = false;
    
    /**
     * Tracks whether inventory deposit has been done (within deposit phase).
     */
    private boolean inventoryDeposited = false;

    // ========================================================================
    // Construction
    // ========================================================================

    @Builder
    private ResupplyTask(
            @Nullable WorldPoint bankLocation,
            @Singular("withdrawItem") Map<Integer, Integer> withdrawItems,
            Boolean depositInventory,
            Boolean depositEquipment,
            @Nullable WorldPoint returnPosition,
            Integer bankProximityThreshold,
            Integer returnProximityThreshold,
            @Singular("exceptItem") Set<Integer> exceptItemIds) {
        
        this.bankLocation = bankLocation;
        this.withdrawItems = withdrawItems != null 
                ? new LinkedHashMap<>(withdrawItems) 
                : new LinkedHashMap<>();
        // Apply defaults for null values
        this.depositInventory = depositInventory != null ? depositInventory : true;
        this.depositEquipment = depositEquipment != null ? depositEquipment : false;
        this.returnPosition = returnPosition;
        this.bankProximityThreshold = bankProximityThreshold != null ? bankProximityThreshold : 5;
        this.returnProximityThreshold = returnProximityThreshold != null ? returnProximityThreshold : 3;
        this.exceptItemIds = exceptItemIds != null && !exceptItemIds.isEmpty()
                ? new HashSet<>(exceptItemIds)
                : Collections.emptySet();
    }

    /**
     * Create a simple resupply task to withdraw specific items.
     *
     * @param items map of itemId to quantity
     * @return configured ResupplyTask
     */
    public static ResupplyTask forItems(Map<Integer, Integer> items) {
        return ResupplyTask.builder()
                .depositInventory(true)
                .withdrawItems(items)
                .build();
    }

    /**
     * Create a resupply task with return to original position.
     *
     * @param items          items to withdraw
     * @param returnPosition position to walk back to after banking
     * @return configured ResupplyTask
     */
    public static ResupplyTask forItemsWithReturn(Map<Integer, Integer> items, WorldPoint returnPosition) {
        return ResupplyTask.builder()
                .depositInventory(true)
                .withdrawItems(items)
                .returnPosition(returnPosition)
                .build();
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        // Can always attempt resupply - individual phases handle validation
        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        // Handle active sub-task first
        if (activeSubTask != null) {
            activeSubTask.execute(ctx);
            if (activeSubTask.getState().isTerminal()) {
            // Check if sub-task failed
            if (activeSubTask.getState() == TaskState.FAILED) {
                log.warn("Resupply sub-task failed in phase {}: {}", 
                        phase, activeSubTask.getDescription());
                fail("Sub-task failed: " + activeSubTask.getDescription());
                return;
            }
            activeSubTask = null;
            }
            return; // Sub-task handled, phase runs next tick
        }

        // Skip if operation pending (async keyboard/click)
        if (operationPending) {
            return;
        }

        // Execute current phase
        switch (phase) {
            case WALK_TO_BANK:
                executeWalkToBank(ctx);
                break;
            case OPEN_BANK:
                executeOpenBank(ctx);
                break;
            case DEPOSIT:
                executeDeposit(ctx);
                break;
            case WITHDRAW:
                executeWithdraw(ctx);
                break;
            case CLOSE_BANK:
                executeCloseBank(ctx);
                break;
            case RETURN:
                executeReturn(ctx);
                break;
        }
    }

    @Override
    public String getDescription() {
        return "Resupply: " + withdrawItems.size() + " item types" +
                (returnPosition != null ? " (with return)" : "");
    }
    
    @Override
    public void onComplete(TaskContext ctx) {
        // Reinforce successful banking sequence
        if (usedSequenceType != null && ctx.getActionSequencer() != null) {
            ctx.getActionSequencer().reinforceBankingSequence(usedSequenceType);
            log.debug("Reinforced banking sequence: {}", usedSequenceType);
        }
        super.onComplete(ctx);
    }

    // ========================================================================
    // Phase Implementations
    // ========================================================================

    /**
     * Phase 1: Walk to bank location.
     */
    private void executeWalkToBank(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // Check if bank is already open
        if (isBankOpen(client)) {
            log.debug("Bank already open, skipping walk");
            phase = depositInventory || depositEquipment 
                    ? ResupplyPhase.DEPOSIT 
                    : ResupplyPhase.WITHDRAW;
            return;
        }

        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Check if already at bank
        if (bankLocation != null && playerPos != null) {
            if (playerPos.distanceTo(bankLocation) <= bankProximityThreshold) {
                log.debug("Already at bank location, opening bank");
                phase = ResupplyPhase.OPEN_BANK;
                return;
            }
        }

        // Walk to bank
        if (bankLocation != null) {
            log.debug("Walking to bank at {}", bankLocation);
            WalkToTask walkTask = new WalkToTask(bankLocation);
            walkTask.setDescription("Walk to bank for resupply");
            activeSubTask = walkTask;
        } else {
            // No specific bank location - try to open nearest
            log.debug("No bank location specified, will open nearest");
            phase = ResupplyPhase.OPEN_BANK;
        }
    }

    /**
     * Phase 2: Open bank interface.
     */
    private void executeOpenBank(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if bank already open
        if (isBankOpen(client)) {
            log.debug("Bank opened successfully");
            
            // Initialize operation sequence using ActionSequencer for human-like order variation
            if (!sequenceInitialized) {
                initializeOperationSequence(ctx);
            }
            
            // Move to first operation in sequence
            advanceToNextOperation();
            return;
        }

        // Open bank
        log.debug("Opening bank");
        activeSubTask = BankTask.open();
    }

    /**
     * Phase 3: Deposit inventory/equipment.
     */
    private void executeDeposit(TaskContext ctx) {
        Client client = ctx.getClient();

        // Verify bank is still open
        if (!isBankOpen(client)) {
            log.warn("Bank closed unexpectedly during deposit");
            phase = ResupplyPhase.OPEN_BANK;
            return;
        }

        // Deposit equipment first if requested and not yet done
        if (depositEquipment && !equipmentDeposited) {
            log.debug("Depositing equipment");
            BankTask depositTask = BankTask.depositEquipment();
            depositTask.setCloseAfter(false);
            activeSubTask = depositTask;
            equipmentDeposited = true;
            return; // Wait for completion before continuing
        }
        
        // Deposit inventory if requested and not yet done
        if (depositInventory && !inventoryDeposited) {
            log.debug("Depositing inventory{}", 
                    exceptItemIds.isEmpty() ? "" : " (keeping " + exceptItemIds.size() + " item types)");
            BankTask depositTask = BankTask.depositAll();
            if (!exceptItemIds.isEmpty()) {
                depositTask.exceptItems(exceptItemIds);
            }
            depositTask.setCloseAfter(false);
            activeSubTask = depositTask;
            inventoryDeposited = true;
            return; // Wait for completion before continuing
        }

        // Deposit complete - advance to next operation in sequence
        advanceToNextOperation();
    }

    /**
     * Phase 4: Withdraw items one at a time.
     */
    private void executeWithdraw(TaskContext ctx) {
        Client client = ctx.getClient();

        // Verify bank is still open
        if (!isBankOpen(client)) {
            log.warn("Bank closed unexpectedly during withdraw");
            phase = ResupplyPhase.OPEN_BANK;
            return;
        }

        // Initialize withdraw sequence if needed
        if (withdrawEntries == null) {
            initializeWithdrawSequence();
        }

        // Check if all items withdrawn
        if (withdrawIndex >= withdrawEntries.length) {
            log.debug("All items withdrawn ({} types)", withdrawEntries.length);
            // Advance to next operation in sequence (or close bank)
            advanceToNextOperation();
            return;
        }

        // Withdraw next item
        Map.Entry<Integer, Integer> entry = withdrawEntries[withdrawIndex];
        int itemId = entry.getKey();
        int quantity = entry.getValue();

        log.debug("Withdrawing item {} x{} ({}/{})", 
                itemId, quantity, withdrawIndex + 1, withdrawEntries.length);
        
        BankTask withdrawTask = BankTask.withdraw(itemId, quantity);
        withdrawTask.setCloseAfter(false);
        activeSubTask = withdrawTask;
        withdrawIndex++;
    }

    /**
     * Phase 5: Close bank interface.
     */
    private void executeCloseBank(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if already closed
        if (!isBankOpen(client)) {
            log.debug("Bank already closed");
            if (returnPosition != null) {
                phase = ResupplyPhase.RETURN;
            } else {
                log.info("Resupply complete (no return position)");
                complete();
            }
            return;
        }

        // Close bank via Escape key
        log.debug("Closing bank");
        operationPending = true;
        ctx.getKeyboardController().pressKey(KeyEvent.VK_ESCAPE)
                .thenRun(() -> {
                    operationPending = false;
                    if (returnPosition != null) {
                        phase = ResupplyPhase.RETURN;
                    } else {
                        log.info("Resupply complete");
                        complete();
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to close bank", e);
                    fail("Failed to close bank: " + e.getMessage());
                    return null;
                });
    }

    /**
     * Phase 6: Return to original position.
     */
    private void executeReturn(TaskContext ctx) {
        if (returnPosition == null) {
            log.info("Resupply complete (no return position)");
            complete();
            return;
        }

        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Check if already at return position
        if (playerPos != null && playerPos.distanceTo(returnPosition) <= returnProximityThreshold) {
            log.info("Resupply complete, returned to position {}", returnPosition);
            complete();
            return;
        }

        // Walk back
        if (activeSubTask == null) {
            log.debug("Walking back to {}", returnPosition);
            WalkToTask walkTask = new WalkToTask(returnPosition);
            walkTask.setDescription("Return to position after resupply");
            activeSubTask = walkTask;
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Check if the bank interface is currently open.
     */
    private boolean isBankOpen(Client client) {
        Widget bankWidget = client.getWidget(
                BankTask.WIDGET_BANK_GROUP, 
                BankTask.WIDGET_BANK_CONTAINER);
        return bankWidget != null && !bankWidget.isHidden();
    }

    /**
     * Initialize the withdraw sequence from the items map.
     */
    @SuppressWarnings("unchecked")
    private void initializeWithdrawSequence() {
        withdrawEntries = withdrawItems.entrySet().toArray(new Map.Entry[0]);
        withdrawIndex = 0;
        log.debug("Initialized withdraw sequence with {} items", withdrawEntries.length);
    }
    
    /**
     * Initialize the operation sequence using ActionSequencer.
     * This determines the order of deposit/withdraw operations based on player profile.
     */
    private void initializeOperationSequence(TaskContext ctx) {
        sequenceInitialized = true;
        
        // Determine which operations we actually need
        Set<ActionSequencer.BankOperation> neededOps = EnumSet.noneOf(ActionSequencer.BankOperation.class);
        
        if (depositInventory || depositEquipment) {
            neededOps.add(ActionSequencer.BankOperation.DEPOSIT_ALL);
        }
        if (!withdrawItems.isEmpty()) {
            neededOps.add(ActionSequencer.BankOperation.WITHDRAW);
        }
        
        if (neededOps.isEmpty()) {
            operationSequence = List.of();
            return;
        }
        
        // Get profile-based sequence (filtered to only needed operations)
        var sequenceResult = ctx.getBankingSequence(neededOps);
        if (sequenceResult.isPresent()) {
            usedSequenceType = sequenceResult.get().getSequenceType();
            operationSequence = sequenceResult.get().getSequence().stream()
                    .filter(neededOps::contains)
                    .toList();
        } else {
            // Fallback to deterministic order when sequencing unavailable
            usedSequenceType = "DEFAULT";
            operationSequence = List.of(
                    ActionSequencer.BankOperation.DEPOSIT_ALL,
                    ActionSequencer.BankOperation.WITHDRAW
            );
        }
        
        operationIndex = 0;
        
        log.debug("Using banking sequence {} -> {}", usedSequenceType, operationSequence);
    }
    
    /**
     * Advance to the next operation in the sequence.
     */
    private void advanceToNextOperation() {
        if (operationSequence == null || operationIndex >= operationSequence.size()) {
            phase = ResupplyPhase.CLOSE_BANK;
            return;
        }
        
        ActionSequencer.BankOperation nextOp = operationSequence.get(operationIndex);
        operationIndex++;
        
        switch (nextOp) {
            case DEPOSIT_ALL:
            case DEPOSIT_SPECIFIC:
                phase = ResupplyPhase.DEPOSIT;
                break;
            case WITHDRAW:
                phase = ResupplyPhase.WITHDRAW;
                initializeWithdrawSequence();
                break;
        }
    }

    // ========================================================================
    // Resupply Phases
    // ========================================================================

    /**
     * Phases in the resupply state machine.
     */
    private enum ResupplyPhase {
        /** Walking to the bank location. */
        WALK_TO_BANK,
        /** Opening the bank interface. */
        OPEN_BANK,
        /** Depositing inventory/equipment. */
        DEPOSIT,
        /** Withdrawing required items. */
        WITHDRAW,
        /** Closing the bank interface. */
        CLOSE_BANK,
        /** Returning to original position. */
        RETURN
    }
}

