package com.rocinante.inventory;

import com.rocinante.state.BankState;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.CompositeTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.EquipItemTask;
import com.rocinante.tasks.impl.ResupplyTask;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.Skill;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Service for preparing inventory to match an {@link IdealInventory} specification.
 *
 * <p>Analyzes the current inventory/equipment/bank state, determines what changes
 * are needed, and generates a sequence of tasks to achieve the desired state.
 *
 * <p>Key features:
 * <ul>
 *   <li>Level-based tool selection via {@link ToolSelection}</li>
 *   <li>Reuses existing tasks ({@link ResupplyTask}, {@link EquipItemTask})</li>
 *   <li>Optimizes by returning null if already prepared</li>
 *   <li>Thread-safe singleton service</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * IdealInventory ideal = IdealInventory.builder()
 *     .requiredItem(InventorySlotSpec.forToolCollection(
 *         ItemCollections.AXES, ItemCollections.AXE_LEVELS, Skill.WOODCUTTING))
 *     .depositInventoryFirst(true)
 *     .build();
 *
 * Task prepTask = inventoryPreparation.prepareInventory(ideal, ctx);
 * if (prepTask != null) {
 *     // Execute the preparation task
 *     taskExecutor.execute(prepTask);
 * } else {
 *     // Already prepared, proceed with main task
 * }
 * }</pre>
 *
 * @see IdealInventory
 * @see InventorySlotSpec
 * @see ToolSelection
 */
@Slf4j
@Singleton
public class InventoryPreparation {

    @Inject
    public InventoryPreparation() {
        // Guice injection
    }

    // ========================================================================
    // Main API
    // ========================================================================

    /**
     * Result of inventory preparation analysis.
     */
    public static class PreparationResult {
        private final Task task;
        private final List<String> missingItems;
        private final boolean failed;

        private PreparationResult(Task task, List<String> missingItems, boolean failed) {
            this.task = task;
            this.missingItems = missingItems != null ? missingItems : List.of();
            this.failed = failed;
        }

        /** The task to execute, or null if already prepared or failed */
        @Nullable
        public Task getTask() {
            return task;
        }

        /** List of items that could not be found/acquired */
        public List<String> getMissingItems() {
            return missingItems;
        }

        /** True if preparation failed due to missing required items */
        public boolean isFailed() {
            return failed;
        }

        /** True if inventory is ready (either already prepared or task is null and not failed) */
        public boolean isReady() {
            return !failed && task == null;
        }

        static PreparationResult ready() {
            return new PreparationResult(null, List.of(), false);
        }

        static PreparationResult needsTask(Task task) {
            return new PreparationResult(task, List.of(), false);
        }

        static PreparationResult failed(List<String> missingItems) {
            return new PreparationResult(null, missingItems, true);
        }
    }

    /**
     * Generate tasks to prepare inventory according to the ideal specification.
     *
     * <p>Returns null if inventory is already in the ideal state (optimization).
     *
     * @param ideal the desired inventory state
     * @param ctx   the task context with current game state
     * @return composite task to prepare inventory, or null if already prepared
     * @throws IllegalStateException if failOnMissingItems is true and items are missing
     */
    @Nullable
    public Task prepareInventory(IdealInventory ideal, TaskContext ctx) {
        PreparationResult result = analyzeAndPrepare(ideal, ctx);
        
        if (result.isFailed() && ideal != null && ideal.isFailOnMissingItems()) {
            throw new IllegalStateException("Required items not available: " + result.getMissingItems());
        }
        
        return result.getTask();
    }

    /**
     * Generate tasks to prepare inventory with detailed result information.
     *
     * <p>Unlike {@link #prepareInventory}, this method returns a result object
     * that includes information about missing items and failure status.
     *
     * @param ideal the desired inventory state
     * @param ctx   the task context with current game state
     * @return preparation result with task, missing items info, and status
     */
    public PreparationResult analyzeAndPrepare(IdealInventory ideal, TaskContext ctx) {
        if (ideal == null) {
            log.debug("No ideal inventory specified, skipping preparation");
            return PreparationResult.ready();
        }

        InventoryState inventory = ctx.getInventoryState();
        PreparationPlan plan = analyzePlan(ideal, ctx, inventory);
        
        // Check for missing required items
        if (!plan.missingItems.isEmpty() && ideal.isFailOnMissingItems()) {
            log.warn("Missing required items: {}", plan.missingItems);
            return PreparationResult.failed(plan.missingItems);
        }
        
        if (plan.isReady()) {
            log.debug("Inventory already matches ideal state, no preparation needed");
            return PreparationResult.ready();
        }

        List<Task> tasks = new ArrayList<>();

        // Phase 1: Banking (deposit + withdraw)
        if (plan.needsBanking()) {
            ResupplyTask bankTask = buildBankTask(plan, ideal);
            if (bankTask != null) {
                tasks.add(bankTask);
            }
        }

        // Phase 2: Equip items from inventory
        if (!plan.itemsToEquip.isEmpty()) {
            for (int itemId : plan.itemsToEquip) {
                tasks.add(new EquipItemTask(itemId));
            }
        }

        if (tasks.isEmpty()) {
            return PreparationResult.ready();
        }

        Task resultTask;
        if (tasks.size() == 1) {
            resultTask = tasks.get(0);
        } else {
            CompositeTask composite = CompositeTask.sequential(tasks.toArray(new Task[0]));
            composite.setDescription("Prepare inventory: " + ideal.getSummary());
            resultTask = composite;
        }
        
        return PreparationResult.needsTask(resultTask);
    }

    /**
     * Check if the current inventory matches the ideal specification.
     *
     * @param ideal the desired inventory state
     * @param ctx   the task context with current game state
     * @return true if inventory already matches ideal
     */
    public boolean isInventoryReady(IdealInventory ideal, TaskContext ctx) {
        if (ideal == null) {
            return true;
        }
        InventoryState inventory = ctx.getInventoryState();
        PreparationPlan plan = analyzePlan(ideal, ctx, inventory);
        
        // Not ready if there are missing required items
        if (!plan.missingItems.isEmpty() && ideal.isFailOnMissingItems()) {
            return false;
        }
        
        return plan.isReady();
    }

    /**
     * Get the item ID that would be selected for a tool spec.
     *
     * <p>Useful for displaying which tool will be used before executing.
     *
     * @param spec  the tool specification
     * @param ctx   the task context
     * @return selected item ID, or empty if no suitable item found
     */
    public Optional<Integer> getSelectedToolId(InventorySlotSpec spec, TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        InventoryState inventory = ctx.getInventoryState();
        EquipmentState equipment = ctx.getEquipmentState();
        BankState bank = ctx.getBankState();

        int skillLevel = getSkillLevel(player, spec.getSkillForLevelCheck());
        int attackLevel = getSkillLevel(player, Skill.ATTACK);

        ToolSelection.ToolSelectionResult result = ToolSelection.selectBestToolFromSpec(
                spec, skillLevel, attackLevel, inventory, equipment, bank);

        return result.isFound() ? Optional.of(result.getItemId()) : Optional.empty();
    }

    // ========================================================================
    // Plan Analysis
    // ========================================================================

    /**
     * Analyze what preparation steps are needed.
     */
    private PreparationPlan analyzePlan(IdealInventory ideal, TaskContext ctx, InventoryState inventory) {
        PreparationPlan plan = new PreparationPlan();

        PlayerState player = ctx.getPlayerState();
        EquipmentState equipment = ctx.getEquipmentState();
        BankState bank = ctx.getBankState();

        // Get player skill levels for tool selection
        int attackLevel = getSkillLevel(player, Skill.ATTACK);
        int agilityLevel = getSkillLevel(player, Skill.AGILITY);

        // Track what items are already in place and don't need action
        Set<Integer> itemsAlreadyInInventory = new HashSet<>();
        Set<Integer> itemsAlreadyEquipped = new HashSet<>();
        
        // Track what items we WANT in each location (for unwanted detection)
        Set<Integer> wantedInInventory = new HashSet<>();
        Set<Integer> wantedEquipped = new HashSet<>();

        // Analyze required items
        if (ideal.hasRequiredItems()) {
            for (InventorySlotSpec spec : ideal.getRequiredItems()) {
                analyzeSlotSpec(spec, plan, player, inventory, equipment, bank,
                        attackLevel, agilityLevel, 
                        itemsAlreadyInInventory, itemsAlreadyEquipped,
                        wantedInInventory, wantedEquipped);
            }
        }

        // Determine if we need to deposit unwanted items
        if (ideal.isDepositInventoryFirst() && !ideal.isKeepExistingItems()) {
            if (hasUnwantedInventoryItems(inventory, wantedInInventory, wantedEquipped, itemsAlreadyInInventory)) {
                plan.depositInventory = true;
            }
        }

        if (ideal.isDepositEquipmentFirst()) {
            plan.depositEquipment = true;
        }

        // Handle fill rest with remaining slots calculation
        if (ideal.hasFillItem()) {
            int fillItem = ideal.getFillRestWithItemId();
            int maxFill = ideal.getFillRestMaxQuantity();
            
            // Calculate slots that will be occupied after preparation
            int occupiedSlots = calculateOccupiedSlots(plan, ideal, inventory, itemsAlreadyInInventory);
            int availableSlots = Math.max(0, 28 - occupiedSlots);
            
            int fillQuantity = maxFill > 0 ? Math.min(maxFill, availableSlots) : availableSlots;
            if (fillQuantity > 0) {
                int existingCount = inventory != null ? inventory.countItem(fillItem) : 0;
                int needed = fillQuantity - existingCount;
                if (needed > 0) {
                    plan.itemsToWithdraw.merge(fillItem, needed, Integer::sum);
                }
            }
        }

        return plan;
    }

    /**
     * Calculate how many inventory slots will be occupied after preparation.
     * 
     * <p>Accounts for:
     * <ul>
     *   <li>Items being withdrawn from bank (that won't be equipped)</li>
     *   <li>Items already in inventory that will be kept</li>
     *   <li>Items that are equipped but preference is PREFER_INVENTORY (would need unequip)</li>
     * </ul>
     */
    private int calculateOccupiedSlots(
            PreparationPlan plan,
            IdealInventory ideal,
            @Nullable InventoryState inventory,
            Set<Integer> itemsAlreadyInInventory) {
        
        int slots = 0;
        
        // Count items being withdrawn that won't be equipped
        for (Map.Entry<Integer, Integer> entry : plan.itemsToWithdraw.entrySet()) {
            int itemId = entry.getKey();
            int quantity = entry.getValue();
            
            // If this item will be equipped, it doesn't take an inventory slot
            if (!plan.itemsToEquip.contains(itemId)) {
                slots += quantity;
            }
        }
        
        // Count items already in inventory that will be kept
        if (ideal.isKeepExistingItems() && inventory != null) {
            // All existing items stay
            slots += inventory.getUsedSlots();
        } else {
            // Only items we explicitly want stay
            slots += itemsAlreadyInInventory.size();
        }
        
        return slots;
    }

    /**
     * Analyze a single slot specification and update the plan.
     */
    private void analyzeSlotSpec(
            InventorySlotSpec spec,
            PreparationPlan plan,
            PlayerState player,
            InventoryState inventory,
            @Nullable EquipmentState equipment,
            @Nullable BankState bank,
            int attackLevel,
            int agilityLevel,
            Set<Integer> itemsAlreadyInInventory,
            Set<Integer> itemsAlreadyEquipped,
            Set<Integer> wantedInInventory,
            Set<Integer> wantedEquipped) {

        // Determine which item ID to use
        Integer targetItemId = resolveItemId(spec, player, inventory, equipment, bank, attackLevel);
        
        if (targetItemId == null) {
            if (!spec.isOptional()) {
                log.warn("Required item not available: {}", spec.getDescription());
                plan.missingItems.add(spec.getDescription());
            }
            return;
        }

        EquipPreference preference = spec.getEffectiveEquipPreference(spec.isCollectionBased());
        boolean canEquip = ToolSelection.canEquipItem(targetItemId, attackLevel, agilityLevel);
        
        // Find where the item currently is
        ToolSelection.ItemLocation location = ToolSelection.findItemLocation(
                targetItemId, inventory, equipment, bank);

        switch (preference) {
            case MUST_EQUIP:
                handleMustEquip(targetItemId, location, canEquip, spec, plan, 
                        itemsAlreadyEquipped, wantedEquipped);
                break;
            case PREFER_EQUIP:
                handlePreferEquip(targetItemId, location, canEquip, spec, plan, 
                        itemsAlreadyInInventory, itemsAlreadyEquipped,
                        wantedInInventory, wantedEquipped);
                break;
            case PREFER_INVENTORY:
                handlePreferInventory(targetItemId, spec.getQuantity(), location, canEquip, spec, plan,
                        itemsAlreadyInInventory, itemsAlreadyEquipped,
                        wantedInInventory, wantedEquipped);
                break;
            case EITHER:
            default:
                handleEitherLocation(targetItemId, spec.getQuantity(), location, plan,
                        itemsAlreadyInInventory, itemsAlreadyEquipped,
                        wantedInInventory, wantedEquipped);
                break;
        }
    }

    /**
     * Resolve which item ID to use for a spec (specific or from collection).
     */
    @Nullable
    private Integer resolveItemId(
            InventorySlotSpec spec,
            PlayerState player,
            InventoryState inventory,
            @Nullable EquipmentState equipment,
            @Nullable BankState bank,
            int attackLevel) {

        if (spec.isSpecificItem()) {
            // Specific item - check if available
            int itemId = spec.getItemId();
            boolean available = ToolSelection.isItemAvailable(itemId, inventory, equipment, bank);
            return available ? itemId : null;
        }

        if (spec.isCollectionBased()) {
            // Collection-based selection
            int skillLevel = getSkillLevel(player, spec.getSkillForLevelCheck());
            
            ToolSelection.ToolSelectionResult result = ToolSelection.selectBestToolFromSpec(
                    spec, skillLevel, attackLevel, inventory, equipment, bank);

            if (result.isFound()) {
                return result.getItemId();
            }
            
            // If fallback allowed and best not available, try best equippable
            if (spec.isAllowFallback() && result.getBestEquippableAlternative() != null) {
                return result.getBestEquippableAlternative();
            }
        }

        return null;
    }

    /**
     * Handle MUST_EQUIP preference.
     */
    private void handleMustEquip(
            int itemId,
            ToolSelection.ItemLocation location,
            boolean canEquip,
            InventorySlotSpec spec,
            PreparationPlan plan,
            Set<Integer> itemsAlreadyEquipped,
            Set<Integer> wantedEquipped) {

        wantedEquipped.add(itemId);
        
        if (!canEquip) {
            if (!spec.isOptional()) {
                log.warn("Cannot equip required item {} - insufficient levels", itemId);
                plan.missingItems.add("Cannot equip: " + spec.getDescription());
            }
            return;
        }

        switch (location) {
            case EQUIPPED:
                // Already equipped, good
                itemsAlreadyEquipped.add(itemId);
                break;
            case INVENTORY:
                // Need to equip from inventory
                plan.itemsToEquip.add(itemId);
                break;
            case BANK:
                // Need to withdraw and equip
                plan.itemsToWithdraw.put(itemId, 1);
                plan.itemsToEquip.add(itemId);
                break;
            case NOT_FOUND:
                if (!spec.isOptional()) {
                    plan.missingItems.add(spec.getDescription());
                }
                break;
        }
    }

    /**
     * Handle PREFER_EQUIP preference.
     */
    private void handlePreferEquip(
            int itemId,
            ToolSelection.ItemLocation location,
            boolean canEquip,
            InventorySlotSpec spec,
            PreparationPlan plan,
            Set<Integer> itemsAlreadyInInventory,
            Set<Integer> itemsAlreadyEquipped,
            Set<Integer> wantedInInventory,
            Set<Integer> wantedEquipped) {

        // Record where we want it
        if (canEquip) {
            wantedEquipped.add(itemId);
        } else {
            wantedInInventory.add(itemId);
        }

        switch (location) {
            case EQUIPPED:
                // Already equipped, good
                itemsAlreadyEquipped.add(itemId);
                break;
            case INVENTORY:
                if (canEquip) {
                    // Can equip from inventory
                    plan.itemsToEquip.add(itemId);
                } else {
                    // Keep in inventory
                    itemsAlreadyInInventory.add(itemId);
                }
                break;
            case BANK:
                // Need to withdraw
                plan.itemsToWithdraw.put(itemId, 1);
                if (canEquip) {
                    plan.itemsToEquip.add(itemId);
                }
                break;
            case NOT_FOUND:
                if (!spec.isOptional()) {
                    plan.missingItems.add(spec.getDescription());
                }
                break;
        }
    }

    /**
     * Handle PREFER_INVENTORY preference.
     * 
     * <p>If item is equipped, we still consider it "in place" since we don't have
     * an unequip mechanism yet. The item is accessible even if not in ideal location.
     */
    private void handlePreferInventory(
            int itemId,
            int quantity,
            ToolSelection.ItemLocation location,
            boolean canEquip,
            InventorySlotSpec spec,
            PreparationPlan plan,
            Set<Integer> itemsAlreadyInInventory,
            Set<Integer> itemsAlreadyEquipped,
            Set<Integer> wantedInInventory,
            Set<Integer> wantedEquipped) {

        wantedInInventory.add(itemId);

        switch (location) {
            case EQUIPPED:
                // Item is equipped but we prefer inventory
                // For now, we accept it as "in place" since it's accessible
                // TODO: When UnequipItemTask is implemented, add to itemsToUnequip
                itemsAlreadyEquipped.add(itemId);
                log.debug("Item {} is equipped but PREFER_INVENTORY specified - accepting as accessible", itemId);
                break;
            case INVENTORY:
                // Perfect - already where we want it
                itemsAlreadyInInventory.add(itemId);
                break;
            case BANK:
                // Need to withdraw
                plan.itemsToWithdraw.put(itemId, quantity);
                break;
            case NOT_FOUND:
                if (!spec.isOptional()) {
                    plan.missingItems.add(spec.getDescription());
                }
                break;
        }
    }

    /**
     * Handle EITHER location preference.
     * 
     * <p>Accepts item wherever it currently is, only withdraws if not present.
     */
    private void handleEitherLocation(
            int itemId,
            int quantity,
            ToolSelection.ItemLocation location,
            PreparationPlan plan,
            Set<Integer> itemsAlreadyInInventory,
            Set<Integer> itemsAlreadyEquipped,
            Set<Integer> wantedInInventory,
            Set<Integer> wantedEquipped) {

        switch (location) {
            case EQUIPPED:
                itemsAlreadyEquipped.add(itemId);
                wantedEquipped.add(itemId); // It's here, so we "want" it here
                break;
            case INVENTORY:
                itemsAlreadyInInventory.add(itemId);
                wantedInInventory.add(itemId); // It's here, so we "want" it here
                break;
            case BANK:
                // Need to withdraw to inventory
                plan.itemsToWithdraw.put(itemId, quantity);
                wantedInInventory.add(itemId);
                break;
            case NOT_FOUND:
                // Item not available anywhere - this is an issue for EITHER preference
                // but we don't fail since the spec might be optional
                break;
        }
    }

    /**
     * Check if inventory has items we don't want.
     * 
     * <p>An item is "unwanted" if:
     * <ul>
     *   <li>It's not in the set of items we want in inventory</li>
     *   <li>It's not in the set of items we want equipped (those will be equipped)</li>
     *   <li>It's not already tracked as "in place" in inventory</li>
     * </ul>
     */
    private boolean hasUnwantedInventoryItems(
            @Nullable InventoryState inventory,
            Set<Integer> wantedInInventory,
            Set<Integer> wantedEquipped,
            Set<Integer> itemsAlreadyInInventory) {

        if (inventory == null || inventory.isEmpty()) {
            return false;
        }

        // All items that are acceptable to have in inventory
        Set<Integer> acceptable = new HashSet<>();
        acceptable.addAll(wantedInInventory);
        acceptable.addAll(wantedEquipped); // Items to be equipped are OK in inventory temporarily
        acceptable.addAll(itemsAlreadyInInventory);

        // Check each slot for unwanted items
        for (Item item : inventory.getAllItems()) {
            if (item != null && item.getId() > 0 && !acceptable.contains(item.getId())) {
                return true;
            }
        }
        return false;
    }

    // ========================================================================
    // Task Building
    // ========================================================================

    /**
     * Build the ResupplyTask for banking operations.
     */
    @Nullable
    private ResupplyTask buildBankTask(PreparationPlan plan, IdealInventory ideal) {
        if (!plan.needsBanking()) {
            return null;
        }

        ResupplyTask.ResupplyTaskBuilder builder = ResupplyTask.builder();

        // Set deposit behavior
        builder.depositInventory(plan.depositInventory);
        builder.depositEquipment(plan.depositEquipment);

        // Add items to withdraw
        for (Map.Entry<Integer, Integer> entry : plan.itemsToWithdraw.entrySet()) {
            builder.withdrawItem(entry.getKey(), entry.getValue());
        }

        return builder.build();
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Get player skill level, defaulting to 1 if not available.
     */
    private int getSkillLevel(@Nullable PlayerState player, @Nullable Skill skill) {
        if (player == null || skill == null) {
            return 1;
        }
        Map<Skill, Integer> levels = player.getBaseSkillLevels();
        return levels != null ? levels.getOrDefault(skill, 1) : 1;
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    /**
     * Internal plan for preparation steps.
     */
    private static class PreparationPlan {
        boolean depositInventory = false;
        boolean depositEquipment = false;
        Map<Integer, Integer> itemsToWithdraw = new HashMap<>();
        Set<Integer> itemsToEquip = new HashSet<>();
        List<String> missingItems = new ArrayList<>();

        /**
         * Check if the inventory is already ready (no actions needed AND no missing items).
         */
        boolean isReady() {
            return !depositInventory 
                    && !depositEquipment
                    && itemsToWithdraw.isEmpty() 
                    && itemsToEquip.isEmpty()
                    && missingItems.isEmpty();
        }

        /**
         * Check if any banking operation is needed.
         */
        boolean needsBanking() {
            return depositInventory || depositEquipment || !itemsToWithdraw.isEmpty();
        }
    }
}
