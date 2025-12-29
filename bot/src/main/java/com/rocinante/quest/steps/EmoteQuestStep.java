package com.rocinante.quest.steps;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.EmoteTask;
import com.rocinante.tasks.impl.WalkToTask;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Quest step that requires performing an emote.
 *
 * <p>This step wraps the reusable {@link EmoteTask} and adds quest-specific
 * functionality like walking to a location before performing the emote.
 *
 * <p>Used for:
 * <ul>
 *   <li>Quest emote requirements</li>
 *   <li>Translating Quest Helper EmoteStep</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Simple emote step
 * EmoteQuestStep step = new EmoteQuestStep(EmoteTask.Emote.BOW, "Bow to the guard");
 *
 * // Emote at a specific location
 * EmoteQuestStep step = new EmoteQuestStep(EmoteTask.Emote.DANCE, "Dance at the crossroads")
 *     .withWalkTo(new WorldPoint(3200, 3200, 0));
 *
 * // Emote from Quest Helper sprite ID
 * EmoteQuestStep step = EmoteQuestStep.fromSpriteId(spriteId, text)
 *     .withWalkTo(location);
 * }</pre>
 */
@Getter
public class EmoteQuestStep extends QuestStep {

    /**
     * The emote to perform.
     */
    private final EmoteTask.Emote emote;

    /**
     * Emote name (for dynamic resolution).
     */
    private final String emoteName;

    /**
     * Sprite ID (for Quest Helper integration).
     */
    @Setter
    private int spriteId = -1;

    /**
     * Location to walk to before performing the emote.
     */
    @Setter
    private WorldPoint walkToLocation;

    /**
     * Whether to wait for the emote animation to complete.
     */
    @Setter
    private boolean waitForAnimation = true;

    /**
     * Create an emote step with a known emote.
     *
     * @param emote the emote to perform
     * @param text  description text for this step
     */
    public EmoteQuestStep(EmoteTask.Emote emote, String text) {
        super(text);
        this.emote = emote;
        this.emoteName = emote != null ? emote.getName() : null;
    }

    /**
     * Create an emote step by name.
     *
     * @param emoteName the name of the emote
     * @param text      description text for this step
     */
    public EmoteQuestStep(String emoteName, String text) {
        super(text);
        this.emoteName = emoteName;
        this.emote = EmoteTask.Emote.fromName(emoteName);
    }

    /**
     * Create an emote step from a sprite ID (Quest Helper integration).
     *
     * @param spriteId the sprite ID
     * @param text     description text for this step
     * @return new EmoteQuestStep
     */
    public static EmoteQuestStep fromSpriteId(int spriteId, String text) {
        EmoteTask.Emote emote = EmoteTask.Emote.fromSpriteId(spriteId);
        EmoteQuestStep step;
        if (emote != null) {
            step = new EmoteQuestStep(emote, text);
        } else {
            step = new EmoteQuestStep((EmoteTask.Emote) null, text);
        }
        step.spriteId = spriteId;
        return step;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set walk-to location (builder-style).
     *
     * @param location the location to walk to first
     * @return this step for chaining
     */
    public EmoteQuestStep withWalkTo(WorldPoint location) {
        this.walkToLocation = location;
        return this;
    }

    /**
     * Set sprite ID (builder-style).
     *
     * @param spriteId the sprite ID
     * @return this step for chaining
     */
    public EmoteQuestStep withSpriteId(int spriteId) {
        this.spriteId = spriteId;
        return this;
    }

    /**
     * Set whether to wait for animation (builder-style).
     *
     * @param wait true to wait
     * @return this step for chaining
     */
    public EmoteQuestStep withWaitForAnimation(boolean wait) {
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
        return walkToLocation;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        List<Task> tasks = new ArrayList<>();

        // Add walk task if location specified
        if (walkToLocation != null) {
            tasks.add(new WalkToTask(walkToLocation));
        }

        // Create emote task
        EmoteTask emoteTask;
        if (emote != null) {
            emoteTask = new EmoteTask(emote);
        } else if (emoteName != null) {
            emoteTask = new EmoteTask(emoteName);
        } else if (spriteId > 0) {
            emoteTask = EmoteTask.fromSpriteId(spriteId);
        } else {
            throw new IllegalStateException("EmoteQuestStep has no emote configured");
        }

        emoteTask.withWaitForAnimation(waitForAnimation);
        emoteTask.withDescription("Perform " + (emoteName != null ? emoteName : "emote"));

        tasks.add(emoteTask);

        return tasks;
    }
}

