package com.rocinante.quest.steps;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.DigTask;
import com.rocinante.tasks.impl.WalkToTask;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Quest step that requires digging with a spade at a specific location.
 *
 * <p>This step wraps the reusable {@link DigTask} and adds quest-specific
 * functionality.
 *
 * <p>Used for:
 * <ul>
 *   <li>Quest dig requirements</li>
 *   <li>Translating Quest Helper DigStep</li>
 *   <li>Treasure Trail dig clues</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Dig at a location
 * DigQuestStep step = new DigQuestStep(new WorldPoint(3200, 3200, 0), "Dig here to find the treasure");
 *
 * // Dig with custom spade
 * DigQuestStep step = new DigQuestStep(location, "Use the ancient spade")
 *     .withSpadeId(ANCIENT_SPADE_ID);
 * }</pre>
 */
@Getter
public class DigQuestStep extends QuestStep {

    /**
     * The location to dig at.
     */
    private final WorldPoint digLocation;

    /**
     * Custom spade item ID (uses standard spade by default).
     */
    @Setter
    private int spadeId = DigTask.SPADE_ID;

    /**
     * Alternative spade item IDs.
     */
    @Setter
    private int[] alternateSpadeIds;

    /**
     * Whether to wait for the dig animation.
     */
    @Setter
    private boolean waitForAnimation = true;

    /**
     * Create a dig step at a specific location.
     *
     * @param digLocation the location to dig at
     * @param text        description text for this step
     */
    public DigQuestStep(WorldPoint digLocation, String text) {
        super(text);
        this.digLocation = digLocation;
    }

    /**
     * Factory method to create dig step.
     *
     * @param x     world X coordinate
     * @param y     world Y coordinate
     * @param plane world plane
     * @param text  description text
     * @return new DigQuestStep
     */
    public static DigQuestStep at(int x, int y, int plane, String text) {
        return new DigQuestStep(new WorldPoint(x, y, plane), text);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set spade item ID (builder-style).
     *
     * @param spadeId the spade item ID
     * @return this step for chaining
     */
    public DigQuestStep withSpadeId(int spadeId) {
        this.spadeId = spadeId;
        return this;
    }

    /**
     * Set alternate spade IDs (builder-style).
     *
     * @param ids alternate spade item IDs
     * @return this step for chaining
     */
    public DigQuestStep withAlternateSpadeIds(int... ids) {
        this.alternateSpadeIds = ids;
        return this;
    }

    /**
     * Set whether to wait for animation (builder-style).
     *
     * @param wait true to wait
     * @return this step for chaining
     */
    public DigQuestStep withWaitForAnimation(boolean wait) {
        this.waitForAnimation = wait;
        return this;
    }

    // ========================================================================
    // Task Generation
    // ========================================================================

    @Override
    public StepType getType() {
        return StepType.CUSTOM;
    }

    @Override
    public WorldPoint getTargetLocation() {
        return digLocation;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        List<Task> tasks = new ArrayList<>();

        // Walk to dig location if specified
        if (digLocation != null) {
            tasks.add(new WalkToTask(digLocation));
        }

        // Create dig task
        DigTask digTask = new DigTask(digLocation)
                .withSpadeId(spadeId)
                .withWalkToLocation(false) // Already walked
                .withWaitForAnimation(waitForAnimation)
                .withDescription("Dig with spade");

        if (alternateSpadeIds != null) {
            digTask.withAlternateSpadeIds(alternateSpadeIds);
        }

        tasks.add(digTask);

        return tasks;
    }
}

