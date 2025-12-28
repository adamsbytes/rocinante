package com.rocinante.state;

import com.rocinante.util.ItemData;
import lombok.Value;
import net.runelite.api.Item;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable snapshot of inventory state as specified in REQUIREMENTS.md Section 6.2.2.
 *
 * This class represents a point-in-time snapshot of the player's inventory,
 * providing helper methods for item queries, slot management, and food detection.
 * Instances are created by {@link com.rocinante.core.GameStateService} when inventory changes.
 *
 * All fields are immutable to ensure thread-safety and prevent accidental modification.
 * 
 * Item metadata (food IDs, healing amounts, etc.) is centralized in {@link ItemData}.
 */
@Value
public class InventoryState {

    /**
     * Total number of inventory slots.
     */
    public static final int INVENTORY_SIZE = 28;

    /**
     * Empty inventory state.
     */
    public static final InventoryState EMPTY = new InventoryState(new Item[INVENTORY_SIZE]);

    /**
     * Common food item IDs for food detection.
     * @deprecated Use {@link ItemData#FOOD_IDS} directly for new code.
     */
    @Deprecated
    private static final Set<Integer> FOOD_IDS = ItemData.FOOD_IDS;

    /**
     * Healing amounts for common food items.
     * Used by getBestFood() to determine optimal food usage.
     * @deprecated Use {@link ItemData#FOOD_HEALING} directly for new code.
     */
    @Deprecated
    private static final Map<Integer, Integer> FOOD_HEALING = ItemData.FOOD_HEALING;

    /**
     * Antipoison/antidote item IDs for poison cure detection.
     * Includes all variants (different dose levels).
     * @deprecated Use {@link ItemData#ANTIPOISON_IDS} directly for new code.
     */
    @Deprecated
    private static final Set<Integer> ANTIPOISON_IDS = ItemData.ANTIPOISON_IDS;

    /**
     * The inventory items array. Null entries indicate empty slots.
     * Array is defensively copied to ensure immutability.
     */
    Item[] items;

    /**
     * Create an InventoryState from an array of items.
     * The array is defensively copied.
     *
     * @param items the inventory items (may contain nulls for empty slots)
     */
    public InventoryState(Item[] items) {
        if (items == null) {
            this.items = new Item[INVENTORY_SIZE];
        } else {
            // Defensive copy
            this.items = Arrays.copyOf(items, INVENTORY_SIZE);
        }
    }

    /**
     * Create an InventoryState from a list of items.
     *
     * @param itemList the inventory items
     * @return new InventoryState
     */
    public static InventoryState fromList(List<Item> itemList) {
        Item[] arr = new Item[INVENTORY_SIZE];
        if (itemList != null) {
            for (int i = 0; i < Math.min(itemList.size(), INVENTORY_SIZE); i++) {
                arr[i] = itemList.get(i);
            }
        }
        return new InventoryState(arr);
    }

    // ========================================================================
    // Item Query Methods
    // ========================================================================

    /**
     * Check if the inventory contains at least one of the specified item.
     *
     * @param itemId the item ID to check for
     * @return true if the item is present
     */
    public boolean hasItem(int itemId) {
        return countItem(itemId) > 0;
    }

    /**
     * Check if the inventory contains at least the specified quantity of an item.
     *
     * @param itemId   the item ID to check for
     * @param quantity the minimum quantity required
     * @return true if the item is present in sufficient quantity
     */
    public boolean hasItem(int itemId, int quantity) {
        return countItem(itemId) >= quantity;
    }

    /**
     * Check if the inventory contains any of the specified items.
     *
     * @param itemIds the item IDs to check for
     * @return true if any of the items are present
     */
    public boolean hasAnyItem(int... itemIds) {
        for (int id : itemIds) {
            if (hasItem(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the inventory contains all of the specified items.
     *
     * @param itemIds the item IDs to check for
     * @return true if all items are present
     */
    public boolean hasAllItems(int... itemIds) {
        for (int id : itemIds) {
            if (!hasItem(id)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Count the total quantity of an item in the inventory.
     * For stackable items, returns the stack size.
     * For non-stackable items, returns the count of slots containing the item.
     *
     * @param itemId the item ID to count
     * @return total quantity of the item
     */
    public int countItem(int itemId) {
        int count = 0;
        for (Item item : items) {
            if (item != null && item.getId() == itemId) {
                count += item.getQuantity();
            }
        }
        return count;
    }

    /**
     * Get the item in a specific slot.
     *
     * @param slot the slot index (0-27)
     * @return Optional containing the item, or empty if slot is empty or invalid
     */
    public Optional<Item> getItemInSlot(int slot) {
        if (slot < 0 || slot >= INVENTORY_SIZE) {
            return Optional.empty();
        }
        return Optional.ofNullable(items[slot]);
    }

    /**
     * Get the slot index of the first occurrence of an item.
     *
     * @param itemId the item ID to find
     * @return the slot index, or -1 if not found
     */
    public int getSlotOf(int itemId) {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (items[i] != null && items[i].getId() == itemId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Get all slot indices containing a specific item.
     *
     * @param itemId the item ID to find
     * @return list of slot indices (may be empty)
     */
    public List<Integer> getSlotsOf(int itemId) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (items[i] != null && items[i].getId() == itemId) {
                slots.add(i);
            }
        }
        return slots;
    }

    /**
     * Get all items as an unmodifiable list.
     * Empty slots are represented as null.
     *
     * @return unmodifiable list of items
     */
    public List<Item> getAllItems() {
        return Collections.unmodifiableList(Arrays.asList(items));
    }

    /**
     * Get all non-null items in the inventory.
     *
     * @return list of items (no nulls)
     */
    public List<Item> getNonEmptyItems() {
        List<Item> result = new ArrayList<>();
        for (Item item : items) {
            if (item != null && item.getId() != -1) {
                result.add(item);
            }
        }
        return result;
    }

    // ========================================================================
    // Slot Management
    // ========================================================================

    /**
     * Get the number of free (empty) slots.
     *
     * @return count of empty slots
     */
    public int getFreeSlots() {
        int count = 0;
        for (Item item : items) {
            if (item == null || item.getId() == -1) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get the number of used (non-empty) slots.
     *
     * @return count of used slots
     */
    public int getUsedSlots() {
        return INVENTORY_SIZE - getFreeSlots();
    }

    /**
     * Check if the inventory is full (no free slots).
     *
     * @return true if all 28 slots are occupied
     */
    public boolean isFull() {
        return getFreeSlots() == 0;
    }

    /**
     * Check if the inventory is empty (all slots free).
     *
     * @return true if all 28 slots are empty
     */
    public boolean isEmpty() {
        return getFreeSlots() == INVENTORY_SIZE;
    }

    /**
     * Get the index of the first empty slot.
     *
     * @return slot index, or -1 if inventory is full
     */
    public int getFirstEmptySlot() {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (items[i] == null || items[i].getId() == -1) {
                return i;
            }
        }
        return -1;
    }

    // ========================================================================
    // Food Detection
    // ========================================================================

    /**
     * Get all slots containing food items.
     *
     * @return list of slot indices containing food
     */
    public List<Integer> getFoodSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (items[i] != null && isFood(items[i].getId())) {
                slots.add(i);
            }
        }
        return slots;
    }

    /**
     * Check if an item ID is recognized as food.
     *
     * @param itemId the item ID to check
     * @return true if the item is food
     */
    public static boolean isFood(int itemId) {
        return FOOD_IDS.contains(itemId);
    }

    /**
     * Get the healing amount for a food item.
     *
     * @param itemId the food item ID
     * @return healing amount, or 0 if not recognized as food
     */
    public static int getFoodHealing(int itemId) {
        return FOOD_HEALING.getOrDefault(itemId, 0);
    }

    /**
     * Get the slot index of the best food item in inventory.
     * "Best" is determined by healing amount - returns the food that heals the most.
     *
     * @return slot index of best food, or -1 if no food found
     */
    public int getBestFood() {
        int bestSlot = -1;
        int bestHealing = 0;

        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (items[i] != null && isFood(items[i].getId())) {
                int healing = getFoodHealing(items[i].getId());
                if (healing > bestHealing) {
                    bestHealing = healing;
                    bestSlot = i;
                }
            }
        }

        return bestSlot;
    }

    /**
     * Get the slot index of food that would optimally heal a damage amount.
     * Prefers food that heals close to but not exceeding the damage taken,
     * to avoid wasting healing.
     *
     * @param damage the amount of damage to heal
     * @param maxHp  the player's maximum hitpoints
     * @return slot index of optimal food, or -1 if no food found
     */
    public int getOptimalFood(int damage, int maxHp) {
        int bestSlot = -1;
        int bestScore = Integer.MAX_VALUE;

        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (items[i] != null && isFood(items[i].getId())) {
                int healing = getFoodHealing(items[i].getId());
                // Score is how much healing is wasted (overheal)
                int waste = Math.max(0, healing - damage);
                // But also penalize under-healing
                int underheal = Math.max(0, damage - healing);
                int score = waste + (underheal * 2); // Penalize under-healing more

                if (score < bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
        }

        return bestSlot;
    }

    /**
     * Check if the inventory contains any food.
     *
     * @return true if at least one food item is present
     */
    public boolean hasFood() {
        return !getFoodSlots().isEmpty();
    }

    /**
     * Count the total number of food items in inventory.
     *
     * @return count of food items
     */
    public int countFood() {
        int count = 0;
        for (Item item : items) {
            if (item != null && isFood(item.getId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Calculate total potential healing from all food in inventory.
     *
     * @return total healing amount
     */
    public int getTotalFoodHealing() {
        int total = 0;
        for (Item item : items) {
            if (item != null && isFood(item.getId())) {
                total += getFoodHealing(item.getId());
            }
        }
        return total;
    }

    // ========================================================================
    // Antipoison Detection
    // ========================================================================

    /**
     * Check if an item ID is an antipoison/antidote.
     *
     * @param itemId the item ID to check
     * @return true if the item cures poison
     */
    public static boolean isAntipoison(int itemId) {
        return ANTIPOISON_IDS.contains(itemId);
    }

    /**
     * Check if the inventory contains any antipoison/antidote.
     *
     * @return true if at least one antipoison item is present
     */
    public boolean hasAntipoison() {
        for (Item item : items) {
            if (item != null && isAntipoison(item.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the slot index of the first antipoison/antidote item.
     *
     * @return slot index of antipoison, or -1 if none found
     */
    public int getAntipoisonSlot() {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (items[i] != null && isAntipoison(items[i].getId())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Get all slots containing antipoison/antidote items.
     *
     * @return list of slot indices containing antipoison
     */
    public List<Integer> getAntipoisonSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (items[i] != null && isAntipoison(items[i].getId())) {
                slots.add(i);
            }
        }
        return slots;
    }
}

