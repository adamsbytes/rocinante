package com.rocinante.quest.steps;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.PickupItemTask;
import com.rocinante.tasks.impl.WalkToTask;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Quest step for picking up ground items.
 *
 * This step generates a {@link PickupItemTask} configured for the specified item.
 * Supports both specific locations and nearest-item search.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Pick up an egg at a known location
 * GroundItemQuestStep pickupEgg = new GroundItemQuestStep(ItemID.EGG, "Egg")
 *     .withLocation(new WorldPoint(3177, 3296, 0))
 *     .withDescription("Grab an egg from the farm");
 *
 * // Pick up nearest bones
 * GroundItemQuestStep pickupBones = new GroundItemQuestStep(ItemID.BONES, "Bones")
 *     .withSearchRadius(15);
 * }</pre>
 */
@Getter
@Setter
@Accessors(chain = true)
public class GroundItemQuestStep extends QuestStep {

    /**
     * The item ID to pick up.
     */
    private final int itemId;

    /**
     * The item name (for menu matching and logging).
     */
    private final String itemName;

    /**
     * Specific location of the item (optional).
     * If null, searches nearby for the item.
     */
    private WorldPoint location;

    /**
     * Search radius for finding the item (tiles).
     */
    private int searchRadius = 15;

    /**
     * Whether to walk to the location first.
     * Useful when the item is far away or in a specific area.
     */
    private boolean walkToLocation = false;

    /**
     * Create a ground item quest step.
     *
     * @param itemId   the item ID to pick up
     * @param itemName the item name (for menu and logging)
     * @param text     instruction text
     */
    public GroundItemQuestStep(int itemId, String itemName, String text) {
        super(text);
        this.itemId = itemId;
        this.itemName = itemName;
    }

    /**
     * Create a ground item quest step.
     *
     * @param itemId   the item ID to pick up
     * @param itemName the item name (for menu and logging)
     */
    public GroundItemQuestStep(int itemId, String itemName) {
        this(itemId, itemName, "Pick up " + itemName);
    }

    /**
     * Create a ground item quest step with just item ID.
     *
     * @param itemId the item ID to pick up
     */
    public GroundItemQuestStep(int itemId) {
        this(itemId, "item", "Pick up item");
    }

    @Override
    public StepType getType() {
        return StepType.ITEM;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        List<Task> tasks = new ArrayList<>();

        // If walkToLocation is enabled and we have a location, walk there first
        if (walkToLocation && location != null) {
            WalkToTask walkTask = new WalkToTask(location);
            walkTask.setDescription("Walk to " + itemName);
            tasks.add(walkTask);
        }

        // Create the pickup task
        PickupItemTask pickupTask = new PickupItemTask(itemId, itemName)
                .withSearchRadius(searchRadius)
                .withDescription(getText());

        if (location != null) {
            pickupTask.withLocation(location);
        }

        tasks.add(pickupTask);

        return tasks;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set specific location of the item (builder-style).
     *
     * @param location the world position of the item
     * @return this step for chaining
     */
    public GroundItemQuestStep withLocation(WorldPoint location) {
        this.location = location;
        return this;
    }

    /**
     * Set search radius (builder-style).
     *
     * @param radius search radius in tiles
     * @return this step for chaining
     */
    public GroundItemQuestStep withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Enable walking to location before pickup (builder-style).
     *
     * @return this step for chaining
     */
    public GroundItemQuestStep withWalkTo() {
        this.walkToLocation = true;
        return this;
    }

    /**
     * Set location and enable walking (builder-style convenience method).
     *
     * @param location the world position
     * @return this step for chaining
     */
    public GroundItemQuestStep withWalkTo(WorldPoint location) {
        this.location = location;
        this.walkToLocation = true;
        return this;
    }

    // ========================================================================
    // Static Factory Methods
    // ========================================================================

    /**
     * Create a step to pick up an item at a specific location.
     *
     * @param itemId   the item ID
     * @param itemName the item name
     * @param location the world position
     * @param text     instruction text
     * @return the quest step
     */
    public static GroundItemQuestStep pickUp(int itemId, String itemName, WorldPoint location, String text) {
        return new GroundItemQuestStep(itemId, itemName, text)
                .withLocation(location);
    }

    /**
     * Create a step to pick up the nearest item of a type.
     *
     * @param itemId       the item ID
     * @param itemName     the item name
     * @param searchRadius the search radius
     * @param text         instruction text
     * @return the quest step
     */
    public static GroundItemQuestStep pickUpNearest(int itemId, String itemName, int searchRadius, String text) {
        return new GroundItemQuestStep(itemId, itemName, text)
                .withSearchRadius(searchRadius);
    }
}

