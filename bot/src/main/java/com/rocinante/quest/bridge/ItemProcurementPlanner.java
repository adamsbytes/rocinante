package com.rocinante.quest.bridge;

import com.rocinante.inventory.EquipPreference;
import com.rocinante.inventory.IdealInventory;
import com.rocinante.inventory.InventoryPreparation;
import com.rocinante.inventory.InventorySlotSpec;
import com.rocinante.state.BankState;
import com.rocinante.state.InventoryState;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.BankTask;
import com.rocinante.tasks.impl.ShopPurchaseTask;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Plans item procurement tasks for quest preparation.
 *
 * <p>This planner determines how to acquire items needed for a quest by:
 * <ol>
 *   <li>Checking if items are already in inventory</li>
 *   <li>Checking if items are in bank (using {@link BankState})</li>
 *   <li>Determining shop purchase if bank doesn't have item</li>
 * </ol>
 *
 * <p>The planner creates a list of tasks to execute in order:
 * <ul>
 *   <li>Bank withdrawals are batched into single bank visit</li>
 *   <li>Shop purchases are grouped by shopkeeper</li>
 * </ul>
 *
 * <p>Bank state handling:
 * <ul>
 *   <li>If bank state is unknown (never opened), assumes items might be in bank</li>
 *   <li>If bank state is known, checks actual quantities</li>
 *   <li>Prefers bank withdrawal over shop purchase when item is in bank</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * ItemProcurementPlanner planner = new ItemProcurementPlanner();
 *
 * // Plan procurement for quest items
 * List<ItemRequirementInfo> needed = bridge.getPreQuestItems();
 * List<Task> tasks = planner.planProcurement(needed, ctx);
 *
 * // Execute tasks
 * for (Task task : tasks) {
 *     executor.queue(task);
 * }
 * }</pre>
 */
@Slf4j
public class ItemProcurementPlanner {

    /**
     * Plan procurement tasks for the given item requirements.
     *
     * <p>This method checks current inventory and bank state to determine
     * what needs to be acquired and how.
     *
     * @param requirements the item requirements to procure
     * @param ctx          the task context for state access
     * @return list of tasks to acquire the items
     */
    public List<Task> planProcurement(List<ItemRequirementInfo> requirements, TaskContext ctx) {
        List<Task> tasks = new ArrayList<>();

        if (requirements == null || requirements.isEmpty()) {
            return tasks;
        }

        InventoryState inventory = ctx.getInventoryState();

        // Separate into categories
        List<ItemRequirementInfo> needFromBank = new ArrayList<>();
        List<ItemRequirementInfo> needFromShop = new ArrayList<>();
        List<ItemRequirementInfo> alreadyHave = new ArrayList<>();
        List<ItemRequirementInfo> cannotObtain = new ArrayList<>();

        for (ItemRequirementInfo req : requirements) {
            // Skip quest-obtainable items
            if (req.isObtainableDuringQuest()) {
                log.debug("Skipping quest-obtainable item: {} ({})", req.getName(), req.getItemId());
                continue;
            }

            // Check if we already have enough
            int currentCount = countItemWithAlternates(inventory, req);
            if (currentCount >= req.getQuantity()) {
                log.debug("Already have enough of {} ({}/{})", req.getName(), currentCount, req.getQuantity());
                alreadyHave.add(req);
                continue;
            }

            int needed = req.getQuantity() - currentCount;

            // Check bank first using BankState
            int bankCount = countItemWithAlternatesInBank(ctx.getBankState(), req);
            boolean inBank = canGetFromBank(req, needed, bankCount, ctx);

            if (inBank) {
                needFromBank.add(req);
                log.debug("Will withdraw {} from bank (need {}, bank has {})", req.getName(), needed, bankCount);
            } else if (req.hasKnownShop()) {
                needFromShop.add(req);
                log.debug("Will buy {} from shop (need {})", req.getName(), needed);
            } else if (req.hasGroundSpawn()) {
                // Will be handled during quest
                log.debug("Item {} has ground spawn, will get during quest", req.getName());
            } else {
                cannotObtain.add(req);
                log.warn("Cannot determine how to obtain item: {} ({})", req.getName(), req.getItemId());
            }
        }

        // Create bank tasks first (single bank visit)
        if (!needFromBank.isEmpty()) {
            tasks.addAll(createBankWithdrawTasks(needFromBank, inventory));
        }

        // Create shop tasks (grouped by shopkeeper)
        if (!needFromShop.isEmpty()) {
            tasks.addAll(createShopPurchaseTasks(needFromShop, inventory));
        }

        log.info("Planned {} procurement tasks ({} bank withdrawals, {} shop purchases, {} already owned, {} unobtainable)",
                tasks.size(), needFromBank.size(), needFromShop.size(), alreadyHave.size(), cannotObtain.size());

        return tasks;
    }

    /**
     * Count how many of an item (including alternates) the player has in inventory.
     */
    private int countItemWithAlternates(InventoryState inventory, ItemRequirementInfo req) {
        int count = 0;
        for (int id : req.getAllIds()) {
            count += inventory.countItem(id);
        }
        return count;
    }

    /**
     * Count how many of an item (including alternates) the player has in bank.
     */
    private int countItemWithAlternatesInBank(BankState bank, ItemRequirementInfo req) {
        if (bank == null || bank.isUnknown()) {
            return 0;
        }
        int count = 0;
        for (int id : req.getAllIds()) {
            count += bank.countItem(id);
        }
        return count;
    }

    /**
     * Check if the item can be obtained from bank.
     * 
     * <p>Decision logic:
     * <ul>
     *   <li>If bank state is unknown, assume item might be in bank (optimistic)</li>
     *   <li>If bank has sufficient quantity, return true</li>
     *   <li>If bank has some but not enough, still try bank first (partial withdrawal)</li>
     *   <li>If bank has none and shop is known, prefer shop</li>
     * </ul>
     *
     * @param req       the item requirement
     * @param needed    the quantity needed
     * @param bankCount the quantity in bank (0 if bank unknown)
     * @param ctx       the task context
     * @return true if should attempt bank withdrawal
     */
    private boolean canGetFromBank(ItemRequirementInfo req, int needed, int bankCount, TaskContext ctx) {
        BankState bank = ctx.getBankState();
        
        // If bank state is unknown, be optimistic and assume item might be in bank
        // This avoids unnecessary shop purchases when we haven't checked bank yet
        if (bank.isUnknown()) {
            log.debug("Bank state unknown, assuming {} might be in bank", req.getName());
            return true;
        }
        
        // If bank has any of this item, try to withdraw from bank first
        if (bankCount > 0) {
            return true;
        }
        
        // Bank doesn't have the item
        // If there's a known shop, prefer shop; otherwise still try bank
        // (player might have deposited since last bank state capture)
        if (req.hasKnownShop()) {
            log.debug("Bank has no {}, will try shop instead", req.getName());
            return false;
        }
        
        // No known shop, so try bank anyway (state might be stale)
        return true;
    }

    /**
     * Create bank withdrawal tasks for the given items.
     */
    private List<Task> createBankWithdrawTasks(List<ItemRequirementInfo> items, InventoryState inventory) {
        List<Task> tasks = new ArrayList<>();

        // Open bank once
        BankTask openBank = BankTask.open()
                .withCloseAfter(false)
                .withDescription("Open bank for quest item withdrawal");
        tasks.add(openBank);

        // Withdraw each item
        for (ItemRequirementInfo item : items) {
            int currentCount = countItemWithAlternates(inventory, item);
            int needed = item.getQuantity() - currentCount;

            if (needed > 0) {
                BankTask withdraw = BankTask.withdraw(item.getItemId(), needed)
                        .withCloseAfter(false)
                        .withDescription("Withdraw " + needed + " " + item.getName());
                tasks.add(withdraw);
            }
        }

        // Close bank at end
        // Add a task to close bank - we'll use escape key via the last withdraw
        // Actually, let's make the last withdrawal close the bank
        if (!tasks.isEmpty() && tasks.get(tasks.size() - 1) instanceof BankTask) {
            BankTask lastWithdraw = (BankTask) tasks.get(tasks.size() - 1);
            lastWithdraw.setCloseAfter(true);
        }

        return tasks;
    }

    /**
     * Create shop purchase tasks for the given items.
     */
    private List<Task> createShopPurchaseTasks(List<ItemRequirementInfo> items, InventoryState inventory) {
        List<Task> tasks = new ArrayList<>();

        // Group by shop NPC
        var byShop = items.stream()
                .filter(ItemRequirementInfo::hasKnownShop)
                .collect(Collectors.groupingBy(ItemRequirementInfo::getShopNpcId));

        for (var entry : byShop.entrySet()) {
            int shopNpcId = entry.getKey();
            List<ItemRequirementInfo> shopItems = entry.getValue();

            // Create purchase task for each item from this shop
            for (int i = 0; i < shopItems.size(); i++) {
                ItemRequirementInfo item = shopItems.get(i);
                int currentCount = countItemWithAlternates(inventory, item);
                int needed = item.getQuantity() - currentCount;

                if (needed > 0) {
                    boolean isLast = (i == shopItems.size() - 1);
                    ShopPurchaseTask purchase = new ShopPurchaseTask(shopNpcId, item.getItemId(), needed)
                            .withCloseAfter(isLast) // Only close after last item
                            .withDescription("Buy " + needed + " " + item.getName());
                    tasks.add(purchase);
                }
            }
        }

        return tasks;
    }

    /**
     * Check if all required items are present in inventory.
     *
     * @param requirements the item requirements to check
     * @param ctx          the task context for inventory access
     * @return true if all items are present in sufficient quantity
     */
    public boolean hasAllItems(List<ItemRequirementInfo> requirements, TaskContext ctx) {
        if (requirements == null || requirements.isEmpty()) {
            return true;
        }

        InventoryState inventory = ctx.getInventoryState();

        for (ItemRequirementInfo req : requirements) {
            // Skip quest-obtainable items
            if (req.isObtainableDuringQuest()) {
                continue;
            }

            int currentCount = countItemWithAlternates(inventory, req);
            if (currentCount < req.getQuantity()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Get a list of items that are still missing.
     *
     * @param requirements the item requirements to check
     * @param ctx          the task context for inventory access
     * @return list of items still needed
     */
    public List<ItemRequirementInfo> getMissingItems(List<ItemRequirementInfo> requirements, TaskContext ctx) {
        List<ItemRequirementInfo> missing = new ArrayList<>();

        if (requirements == null || requirements.isEmpty()) {
            return missing;
        }

        InventoryState inventory = ctx.getInventoryState();

        for (ItemRequirementInfo req : requirements) {
            // Skip quest-obtainable items
            if (req.isObtainableDuringQuest()) {
                continue;
            }

            int currentCount = countItemWithAlternates(inventory, req);
            if (currentCount < req.getQuantity()) {
                missing.add(req);
            }
        }

        return missing;
    }

    // ========================================================================
    // IdealInventory Integration
    // ========================================================================

    /**
     * Convert item requirements to an IdealInventory specification.
     *
     * <p>This creates an IdealInventory that can be used with
     * {@link InventoryPreparation} for a more integrated approach.
     *
     * <p>Note: This method only handles bank-obtainable items.
     * Shop purchases must still be handled separately.
     *
     * @param requirements the item requirements
     * @return IdealInventory specification
     */
    public IdealInventory toIdealInventory(List<ItemRequirementInfo> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return IdealInventory.emptyInventory();
        }

        IdealInventory.IdealInventoryBuilder builder = IdealInventory.builder()
                .depositInventoryFirst(false)  // Don't clear - might have useful items
                .keepExistingItems(true)       // Keep what we have
                .failOnMissingItems(false);    // Graceful handling

        for (ItemRequirementInfo req : requirements) {
            // Skip quest-obtainable items
            if (req.isObtainableDuringQuest()) {
                continue;
            }

            // Create spec for this requirement
            InventorySlotSpec spec = createSlotSpec(req);
            if (spec != null) {
                builder.requiredItem(spec);
            }
        }

        return builder.build();
    }

    /**
     * Create an InventorySlotSpec from an ItemRequirementInfo.
     */
    private InventorySlotSpec createSlotSpec(ItemRequirementInfo req) {
        List<Integer> allIds = req.getAllIds();

        if (allIds.size() == 1) {
            // Single item
            return InventorySlotSpec.builder()
                    .itemId(allIds.get(0))
                    .quantity(req.getQuantity())
                    .equipPreference(EquipPreference.PREFER_INVENTORY)
                    .displayName(req.getName())
                    .optional(false)
                    .build();
        } else {
            // Multiple alternates - use collection
            return InventorySlotSpec.builder()
                    .itemCollection(allIds)
                    .quantity(req.getQuantity())
                    .equipPreference(EquipPreference.PREFER_INVENTORY)
                    .displayName(req.getName())
                    .optional(false)
                    .allowFallback(true)
                    .build();
        }
    }

    /**
     * Plan procurement using InventoryPreparation service if available.
     *
     * <p>This is an enhanced version of {@link #planProcurement} that uses
     * the IdealInventory system for bank withdrawals when the
     * InventoryPreparation service is available.
     *
     * <p>Falls back to legacy behavior if InventoryPreparation is not available.
     *
     * @param requirements the item requirements to procure
     * @param ctx          the task context
     * @return list of tasks to acquire the items
     */
    public List<Task> planProcurementWithIdealInventory(List<ItemRequirementInfo> requirements, TaskContext ctx) {
        if (requirements == null || requirements.isEmpty()) {
            return new ArrayList<>();
        }

        InventoryPreparation inventoryPrep = ctx.getInventoryPreparation();

        // Separate bank items from shop items
        List<ItemRequirementInfo> bankItems = new ArrayList<>();
        List<ItemRequirementInfo> shopItems = new ArrayList<>();
        InventoryState inventory = ctx.getInventoryState();

        for (ItemRequirementInfo req : requirements) {
            if (req.isObtainableDuringQuest()) {
                continue;
            }

            int currentCount = countItemWithAlternates(inventory, req);
            if (currentCount >= req.getQuantity()) {
                continue; // Already have enough
            }

            int needed = req.getQuantity() - currentCount;
            int bankCount = countItemWithAlternatesInBank(ctx.getBankState(), req);

            if (canGetFromBank(req, needed, bankCount, ctx)) {
                bankItems.add(req);
            } else if (req.hasKnownShop()) {
                shopItems.add(req);
            }
        }

        List<Task> tasks = new ArrayList<>();

        // Use InventoryPreparation for bank items if available
        if (!bankItems.isEmpty() && inventoryPrep != null) {
            IdealInventory bankIdeal = toIdealInventory(bankItems);
            Task prepTask = inventoryPrep.prepareInventory(bankIdeal, ctx);
            if (prepTask != null) {
                tasks.add(prepTask);
                log.info("Using InventoryPreparation for {} bank items", bankItems.size());
            }
        } else if (!bankItems.isEmpty()) {
            // Fallback to legacy bank tasks
            tasks.addAll(createBankWithdrawTasks(bankItems, inventory));
        }

        // Shop purchases still use legacy system
        if (!shopItems.isEmpty()) {
            tasks.addAll(createShopPurchaseTasks(shopItems, inventory));
        }

        log.info("Planned {} procurement tasks ({} via IdealInventory, {} via shop)",
                tasks.size(), bankItems.size(), shopItems.size());

        return tasks;
    }
}

