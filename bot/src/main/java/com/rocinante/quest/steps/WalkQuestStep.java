package com.rocinante.quest.steps;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.WalkToTask;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Quest step for walking to a location.
 *
 * This step generates a {@link WalkToTask} configured for the specified destination.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Walk to specific coordinates
 * WalkQuestStep walkToHouse = new WalkQuestStep(3094, 3107, "Enter the house");
 *
 * // Walk to a WorldPoint
 * WalkQuestStep walkToPoint = new WalkQuestStep(new WorldPoint(3094, 3107, 0), "Walk to the guide");
 *
 * // Walk to named location
 * WalkQuestStep walkToBank = WalkQuestStep.toLocation("tutorial_bank", "Go to the bank");
 * }</pre>
 */
@Getter
@Setter
@Accessors(chain = true)
public class WalkQuestStep extends QuestStep {

    /**
     * The destination WorldPoint.
     */
    private WorldPoint destination;

    /**
     * Named location (for WebWalker).
     */
    private String namedLocation;

    /**
     * Object ID to walk to (nearest instance).
     */
    private int targetObjectId = -1;

    /**
     * NPC ID to walk to (nearest instance).
     */
    private int targetNpcId = -1;

    /**
     * Arrival tolerance in tiles.
     */
    private int arrivalDistance = 2;

    /**
     * Create a walk quest step to coordinates.
     *
     * @param x    world X coordinate
     * @param y    world Y coordinate
     * @param text instruction text
     */
    public WalkQuestStep(int x, int y, String text) {
        super(text);
        this.destination = new WorldPoint(x, y, 0);
    }

    /**
     * Create a walk quest step to coordinates with plane.
     *
     * @param x     world X coordinate
     * @param y     world Y coordinate
     * @param plane the plane
     * @param text  instruction text
     */
    public WalkQuestStep(int x, int y, int plane, String text) {
        super(text);
        this.destination = new WorldPoint(x, y, plane);
    }

    /**
     * Create a walk quest step to a WorldPoint.
     *
     * @param destination the destination
     * @param text        instruction text
     */
    public WalkQuestStep(WorldPoint destination, String text) {
        super(text);
        this.destination = destination;
    }

    /**
     * Private constructor for factory methods.
     */
    private WalkQuestStep(String text) {
        super(text);
    }

    @Override
    public StepType getType() {
        return StepType.WALK;
    }

    @Override
    public WorldPoint getTargetLocation() {
        return destination;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        List<Task> tasks = new ArrayList<>();

        WalkToTask walkTask;

        if (namedLocation != null) {
            // Named locations no longer supported - this is a data error
            throw new IllegalStateException("Named locations not supported. WalkQuestStep should use coordinates. namedLocation: " + namedLocation);
        } else if (targetObjectId > 0) {
            walkTask = WalkToTask.toObject(targetObjectId);
        } else if (targetNpcId > 0) {
            walkTask = WalkToTask.toNpc(targetNpcId);
        } else if (destination != null) {
            walkTask = new WalkToTask(destination);
        } else {
            // No valid destination
            return tasks;
        }

        tasks.add(walkTask);
        return tasks;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a walk step to a named location.
     *
     * @param locationName the location name
     * @param text         instruction text
     * @return walk step
     */
    public static WalkQuestStep toLocation(String locationName, String text) {
        WalkQuestStep step = new WalkQuestStep(text);
        step.namedLocation = locationName;
        return step;
    }

    /**
     * Create a walk step to an object.
     *
     * @param objectId the object ID
     * @param text     instruction text
     * @return walk step
     */
    public static WalkQuestStep toObject(int objectId, String text) {
        WalkQuestStep step = new WalkQuestStep(text);
        step.targetObjectId = objectId;
        return step;
    }

    /**
     * Create a walk step to an NPC.
     *
     * @param npcId the NPC ID
     * @param text  instruction text
     * @return walk step
     */
    public static WalkQuestStep toNpc(int npcId, String text) {
        WalkQuestStep step = new WalkQuestStep(text);
        step.targetNpcId = npcId;
        return step;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set arrival tolerance (builder-style).
     *
     * @param distance the distance in tiles
     * @return this step for chaining
     */
    public WalkQuestStep withArrivalDistance(int distance) {
        this.arrivalDistance = distance;
        return this;
    }
}

