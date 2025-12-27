package com.rocinante.quest.bridge;

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
 *   <li>Checking if items are in bank (requires BankState)</li>
 *   <li>Determining shop purchase if bank doesn't have item</li>
 * </ol>
 *
 * <p>The planner creates a list of tasks to execute in order:
 * <ul>
 *   <li>Bank withdrawals are batched into single bank visit</li>
 *   <li>Shop purchases are grouped by shopkeeper</li>
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

            // Check bank first (if we have bank state - for now assume bank has it)
            // TODO: Integrate with BankState when available
            boolean inBank = canGetFromBank(req, ctx);

            if (inBank) {
                needFromBank.add(req);
                log.debug("Will withdraw {} from bank (need {})", req.getName(), needed);
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
     * Count how many of an item (including alternates) the player has.
     */
    private int countItemWithAlternates(InventoryState inventory, ItemRequirementInfo req) {
        int count = 0;
        for (int id : req.getAllIds()) {
            count += inventory.countItem(id);
        }
        return count;
    }

    /**
     * Check if the item can be obtained from bank.
     * TODO: This needs proper BankState integration.
     */
    private boolean canGetFromBank(ItemRequirementInfo req, TaskContext ctx) {
        // For now, assume all non-shop items can come from bank
        // In a real implementation, we'd check BankState
        return !req.hasKnownShop() || true; // Prefer bank over shop
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
}

