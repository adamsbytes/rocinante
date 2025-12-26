package com.rocinante.quest.steps;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.InteractObjectTask;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Quest step for interacting with a game object.
 *
 * This step generates an {@link InteractObjectTask} configured for the specified
 * object and menu action.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Open a door
 * ObjectQuestStep openDoor = new ObjectQuestStep(DOOR_ID, "Open", "Open the door");
 *
 * // Chop a tree
 * ObjectQuestStep chopTree = new ObjectQuestStep(TREE_ID, "Chop down", "Chop down the tree")
 *     .withSuccessAnimation(WOODCUTTING_ANIM);
 *
 * // Click on furnace
 * ObjectQuestStep useFurnace = new ObjectQuestStep(FURNACE_ID, "Smelt", "Use the furnace");
 * }</pre>
 */
@Getter
@Setter
@Accessors(chain = true)
public class ObjectQuestStep extends QuestStep {

    /**
     * The object ID to interact with.
     */
    private final int objectId;

    /**
     * The menu action to use.
     */
    private final String menuAction;

    /**
     * Optional location to walk to before interacting.
     */
    private WorldPoint walkToLocation;

    /**
     * Search radius for finding the object.
     */
    private int searchRadius = 15;

    /**
     * Expected animation ID after interaction (optional).
     */
    private int successAnimationId = -1;

    /**
     * Whether to wait for player to be idle after interaction.
     */
    private boolean waitForIdle = true;

    /**
     * Create an object quest step.
     *
     * @param objectId   the object ID
     * @param menuAction the menu action
     * @param text       instruction text
     */
    public ObjectQuestStep(int objectId, String menuAction, String text) {
        super(text);
        this.objectId = objectId;
        this.menuAction = menuAction;
    }

    /**
     * Create an object quest step with default text.
     *
     * @param objectId   the object ID
     * @param menuAction the menu action
     */
    public ObjectQuestStep(int objectId, String menuAction) {
        super(menuAction + " object");
        this.objectId = objectId;
        this.menuAction = menuAction;
    }

    @Override
    public StepType getType() {
        return StepType.OBJECT;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        List<Task> tasks = new ArrayList<>();

        // Create the object interaction task
        InteractObjectTask objectTask = new InteractObjectTask(objectId, menuAction)
                .withSearchRadius(searchRadius)
                .withWaitForIdle(waitForIdle)
                .withDescription(getText());

        if (successAnimationId > 0) {
            objectTask.withSuccessAnimation(successAnimationId);
        }

        tasks.add(objectTask);

        return tasks;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set location to walk to before interacting (builder-style).
     *
     * @param location the location
     * @return this step for chaining
     */
    public ObjectQuestStep withWalkTo(WorldPoint location) {
        this.walkToLocation = location;
        return this;
    }

    /**
     * Set search radius (builder-style).
     *
     * @param radius the radius in tiles
     * @return this step for chaining
     */
    public ObjectQuestStep withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Set expected success animation (builder-style).
     *
     * @param animationId the animation ID
     * @return this step for chaining
     */
    public ObjectQuestStep withSuccessAnimation(int animationId) {
        this.successAnimationId = animationId;
        return this;
    }

    /**
     * Set whether to wait for idle (builder-style).
     *
     * @param wait true to wait for idle
     * @return this step for chaining
     */
    public ObjectQuestStep withWaitForIdle(boolean wait) {
        this.waitForIdle = wait;
        return this;
    }
}

