package com.rocinante.quest.steps;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.EquipItemTask;
import com.rocinante.tasks.impl.InventoryInteractTask;
import com.rocinante.tasks.impl.UseItemOnItemTask;
import com.rocinante.tasks.impl.UseItemOnNpcTask;
import com.rocinante.tasks.impl.UseItemOnObjectTask;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Quest step for using or equipping items.
 *
 * This step handles inventory interactions like equipping items, using items
 * on objects, or combining items.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Equip bronze dagger
 * ItemQuestStep equipDagger = ItemQuestStep.equip(ItemID.BRONZE_DAGGER, "Equip the bronze dagger");
 *
 * // Use item on object
 * ItemQuestStep useOnFurnace = ItemQuestStep.useOn(ItemID.TIN_ORE, ObjectID.FURNACE, "Smelt the ore");
 *
 * // Use item on item
 * ItemQuestStep makeDough = ItemQuestStep.useOnItem(ItemID.BUCKET_OF_WATER, ItemID.POT_OF_FLOUR, "Make bread dough");
 * }</pre>
 */
@Slf4j
@Getter
@Setter
@Accessors(chain = true)
public class ItemQuestStep extends QuestStep {

    /**
     * Item action types.
     */
    public enum ItemAction {
        EQUIP,
        USE,
        USE_ON_OBJECT,
        USE_ON_NPC,
        USE_ON_ITEM,
        DROP,
        EAT,
        DRINK
    }

    /**
     * The primary item ID (legacy, for single item).
     */
    private final int itemId;

    /**
     * Acceptable source item IDs (for multi-item support).
     * Ordered by priority - first match wins.
     */
    private List<Integer> sourceItemIds;

    /**
     * The action to perform.
     */
    private ItemAction action = ItemAction.USE;

    /**
     * Target object ID (for USE_ON_OBJECT, single object).
     */
    private int targetObjectId = -1;

    /**
     * Acceptable target object IDs (for USE_ON_OBJECT with multi-object support).
     * Ordered by priority - first match wins.
     */
    private List<Integer> targetObjectIds;

    /**
     * Target NPC ID (for USE_ON_NPC).
     */
    private int targetNpcId = -1;

    /**
     * Target item ID (for USE_ON_ITEM, single item).
     */
    private int targetItemId = -1;

    /**
     * Acceptable target item IDs (for USE_ON_ITEM with multi-item support).
     * Ordered by priority - first match wins.
     */
    private List<Integer> targetItemIds;

    /**
     * Create an item quest step with a single item.
     *
     * @param itemId the item ID
     * @param text   instruction text
     */
    public ItemQuestStep(int itemId, String text) {
        super(text);
        this.itemId = itemId;
        this.sourceItemIds = null; // Use itemId
    }

    /**
     * Create an item quest step with multiple acceptable items.
     * Items are checked in order - first match wins (for priority ordering).
     *
     * @param itemIds acceptable item IDs, ordered by priority
     * @param text    instruction text
     */
    public ItemQuestStep(Collection<Integer> itemIds, String text) {
        super(text);
        this.itemId = itemIds.iterator().next(); // Legacy compatibility
        this.sourceItemIds = new ArrayList<>(itemIds);
    }

    @Override
    public StepType getType() {
        return StepType.ITEM;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        List<Task> tasks = new ArrayList<>();

        switch (action) {
            case EQUIP:
                log.debug("Creating EquipItemTask for item {}", itemId);
                tasks.add(new EquipItemTask(itemId)
                        .withDescription(getText()));
                break;

            case USE_ON_OBJECT:
                if (targetObjectId < 0 && (targetObjectIds == null || targetObjectIds.isEmpty())) {
                    log.error("USE_ON_OBJECT requires a target object ID");
                    throw new IllegalStateException("Target object ID not set for USE_ON_OBJECT action");
                }
                // Use collections if available, otherwise fall back to single IDs
                List<Integer> objectItems = sourceItemIds != null ? sourceItemIds : Collections.singletonList(itemId);
                List<Integer> objectTargets = targetObjectIds != null && !targetObjectIds.isEmpty()
                        ? targetObjectIds : Collections.singletonList(targetObjectId);
                log.debug("Creating UseItemOnObjectTask for items {} on objects {}", objectItems, objectTargets);
                tasks.add(new UseItemOnObjectTask(objectItems, objectTargets)
                        .withDescription(getText()));
                break;

            case USE_ON_ITEM:
                if (targetItemId < 0 && (targetItemIds == null || targetItemIds.isEmpty())) {
                    log.error("USE_ON_ITEM requires a target item ID");
                    throw new IllegalStateException("Target item ID not set for USE_ON_ITEM action");
                }
                // Use collections if available, otherwise fall back to single IDs
                List<Integer> sources = sourceItemIds != null ? sourceItemIds : Collections.singletonList(itemId);
                List<Integer> targets = targetItemIds != null ? targetItemIds : Collections.singletonList(targetItemId);
                log.debug("Creating UseItemOnItemTask for items {} on items {}", sources, targets);
                tasks.add(new UseItemOnItemTask(sources, targets)
                        .withDescription(getText()));
                break;

            case USE_ON_NPC:
                if (targetNpcId < 0) {
                    log.error("USE_ON_NPC requires a target NPC ID");
                    throw new IllegalStateException("Target NPC ID not set for USE_ON_NPC action");
                }
                log.debug("Creating UseItemOnNpcTask for item {} on NPC {}", itemId, targetNpcId);
                tasks.add(new UseItemOnNpcTask(itemId, targetNpcId)
                        .withDescription(getText()));
                break;

            case EAT:
                log.debug("Creating InventoryInteractTask (eat) for item {}", itemId);
                tasks.add(new InventoryInteractTask(itemId, "eat")
                        .withDescription(getText()));
                break;

            case DRINK:
                log.debug("Creating InventoryInteractTask (drink) for item {}", itemId);
                tasks.add(new InventoryInteractTask(itemId, "drink")
                        .withDescription(getText()));
                break;

            case USE:
                log.debug("Creating InventoryInteractTask (use) for item {}", itemId);
                tasks.add(new InventoryInteractTask(itemId, "use")
                        .withDescription(getText()));
                break;

            case DROP:
                log.debug("Creating InventoryInteractTask (drop) for item {}", itemId);
                tasks.add(new InventoryInteractTask(itemId, "drop")
                        .withDescription(getText()));
                break;

            default:
                log.warn("Unknown ItemAction: {}", action);
                break;
        }

        return tasks;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a step to equip an item.
     *
     * @param itemId the item ID
     * @param text   instruction text
     * @return item step
     */
    public static ItemQuestStep equip(int itemId, String text) {
        ItemQuestStep step = new ItemQuestStep(itemId, text);
        step.action = ItemAction.EQUIP;
        return step;
    }

    /**
     * Create a step to equip multiple items.
     *
     * @param text    instruction text
     * @param itemIds the item IDs to equip
     * @return item step for the first item (chain multiple for all)
     */
    public static ItemQuestStep equipAll(String text, int... itemIds) {
        if (itemIds.length == 0) {
            throw new IllegalArgumentException("At least one item ID required");
        }
        ItemQuestStep step = new ItemQuestStep(itemIds[0], text);
        step.action = ItemAction.EQUIP;
        // Note: For multiple items, would need to create a composite
        return step;
    }

    /**
     * Create a step to use an item on an object.
     *
     * @param itemId   the item ID
     * @param objectId the target object ID
     * @param text     instruction text
     * @return item step
     */
    public static ItemQuestStep useOn(int itemId, int objectId, String text) {
        ItemQuestStep step = new ItemQuestStep(itemId, text);
        step.action = ItemAction.USE_ON_OBJECT;
        step.targetObjectId = objectId;
        return step;
    }

    /**
     * Create a step to use any of the provided items on an object.
     *
     * @param itemIds  acceptable item IDs
     * @param objectId the target object ID
     * @param text     instruction text
     * @return item step
     */
    public static ItemQuestStep useOn(Collection<Integer> itemIds, int objectId, String text) {
        ItemQuestStep step = new ItemQuestStep(itemIds, text);
        step.action = ItemAction.USE_ON_OBJECT;
        step.targetObjectId = objectId;
        return step;
    }

    /**
     * Create a step to use an item on any of the provided objects.
     *
     * @param itemId    the item ID
     * @param objectIds acceptable object IDs, ordered by priority
     * @param text      instruction text
     * @return the quest step
     */
    public static ItemQuestStep useOn(int itemId, Collection<Integer> objectIds, String text) {
        ItemQuestStep step = new ItemQuestStep(itemId, text);
        step.action = ItemAction.USE_ON_OBJECT;
        step.targetObjectIds = new ArrayList<>(objectIds);
        return step;
    }

    /**
     * Create a step to use any of the provided items on any of the provided objects.
     * Both items and objects are checked in order - first match wins (for priority ordering).
     *
     * @param itemIds   acceptable item IDs, ordered by priority
     * @param objectIds acceptable object IDs, ordered by priority
     * @param text      instruction text
     * @return the quest step
     */
    public static ItemQuestStep useOn(Collection<Integer> itemIds, Collection<Integer> objectIds, String text) {
        ItemQuestStep step = new ItemQuestStep(itemIds, text);
        step.action = ItemAction.USE_ON_OBJECT;
        step.targetObjectIds = new ArrayList<>(objectIds);
        return step;
    }

    /**
     * Create a step to use an item on an NPC.
     *
     * @param itemId the item ID
     * @param npcId  the target NPC ID
     * @param text   instruction text
     * @return item step
     */
    public static ItemQuestStep useOnNpc(int itemId, int npcId, String text) {
        ItemQuestStep step = new ItemQuestStep(itemId, text);
        step.action = ItemAction.USE_ON_NPC;
        step.targetNpcId = npcId;
        return step;
    }

    /**
     * Create a step to use an item on another item.
     *
     * @param itemId       the item ID
     * @param targetItemId the target item ID
     * @param text         instruction text
     * @return item step
     */
    public static ItemQuestStep useOnItem(int itemId, int targetItemId, String text) {
        ItemQuestStep step = new ItemQuestStep(itemId, text);
        step.action = ItemAction.USE_ON_ITEM;
        step.targetItemId = targetItemId;
        return step;
    }

    /**
     * Create a step to use any of the source items on any of the target items.
     * Useful for flexible tasks like "light fire with any logs".
     * Items are checked in order - first match wins (for priority ordering).
     *
     * @param sourceItemIds acceptable source item IDs, ordered by priority
     * @param targetItemIds acceptable target item IDs, ordered by priority
     * @param text          instruction text
     * @return item step
     */
    public static ItemQuestStep useOnItem(Collection<Integer> sourceItemIds, Collection<Integer> targetItemIds, String text) {
        ItemQuestStep step = new ItemQuestStep(sourceItemIds, text);
        step.action = ItemAction.USE_ON_ITEM;
        step.targetItemIds = new ArrayList<>(targetItemIds);
        return step;
    }

    /**
     * Create a step to eat food.
     *
     * @param itemId the food item ID
     * @param text   instruction text
     * @return item step
     */
    public static ItemQuestStep eat(int itemId, String text) {
        ItemQuestStep step = new ItemQuestStep(itemId, text);
        step.action = ItemAction.EAT;
        return step;
    }

    /**
     * Create a step to drop an item.
     *
     * @param itemId the item ID
     * @param text   instruction text
     * @return item step
     */
    public static ItemQuestStep drop(int itemId, String text) {
        ItemQuestStep step = new ItemQuestStep(itemId, text);
        step.action = ItemAction.DROP;
        return step;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set the action type (builder-style).
     *
     * @param action the action
     * @return this step for chaining
     */
    public ItemQuestStep withAction(ItemAction action) {
        this.action = action;
        return this;
    }

    /**
     * Set target object ID (builder-style).
     *
     * @param objectId the object ID
     * @return this step for chaining
     */
    public ItemQuestStep withTargetObject(int objectId) {
        this.targetObjectId = objectId;
        return this;
    }

    /**
     * Set target NPC ID (builder-style).
     *
     * @param npcId the NPC ID
     * @return this step for chaining
     */
    public ItemQuestStep withTargetNpc(int npcId) {
        this.targetNpcId = npcId;
        return this;
    }

    /**
     * Set target item ID (builder-style).
     *
     * @param itemId the item ID
     * @return this step for chaining
     */
    public ItemQuestStep withTargetItem(int itemId) {
        this.targetItemId = itemId;
        return this;
    }
}

