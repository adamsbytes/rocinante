package com.rocinante.quest.steps;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.DialogueTask;
import com.rocinante.tasks.impl.InteractNpcTask;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Quest step for interacting with an NPC.
 *
 * This step generates an {@link InteractNpcTask} configured for the specified NPC
 * and menu action. It supports dialogue handling and optional walking to the NPC.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Talk to Gielinor Guide
 * NpcQuestStep talkToGuide = new NpcQuestStep(NpcID.GIELINOR_GUIDE, "Talk to the Gielinor Guide")
 *     .withMenuAction("Talk-to")
 *     .withDialogueExpected(true);
 *
 * // Pickpocket a man
 * NpcQuestStep pickpocket = new NpcQuestStep(NpcID.MAN, "Pickpocket the man")
 *     .withMenuAction("Pickpocket");
 * }</pre>
 */
@Getter
@Setter
@Accessors(chain = true)
public class NpcQuestStep extends QuestStep {

    /**
     * The NPC ID to interact with.
     */
    private final int npcId;

    /**
     * Optional NPC name for filtering.
     */
    private String npcName;

    /**
     * The menu action to use (default: "Talk-to").
     */
    private String menuAction = "Talk-to";

    /**
     * Whether dialogue is expected after interaction.
     */
    private boolean dialogueExpected = true;

    /**
     * Optional location to walk to before interacting.
     */
    private WorldPoint walkToLocation;

    /**
     * Search radius for finding the NPC.
     */
    private int searchRadius = 15;

    /**
     * Dialogue options to select (in order).
     */
    private List<String> dialogueOptions = new ArrayList<>();

    /**
     * Create an NPC quest step.
     *
     * @param npcId the NPC ID
     * @param text  instruction text
     */
    public NpcQuestStep(int npcId, String text) {
        super(text);
        this.npcId = npcId;
    }

    /**
     * Create an NPC quest step with default text.
     *
     * @param npcId the NPC ID
     */
    public NpcQuestStep(int npcId) {
        super("Talk to NPC");
        this.npcId = npcId;
    }

    @Override
    public StepType getType() {
        return StepType.NPC;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        List<Task> tasks = new ArrayList<>();

        // Optionally walk to location first
        // Note: Walking task would be added here if walkToLocation is set

        // Create the NPC interaction task
        InteractNpcTask npcTask = new InteractNpcTask(npcId, menuAction)
                .withDialogueExpected(dialogueExpected)
                .withSearchRadius(searchRadius)
                .withDescription(getText());

        if (npcName != null) {
            npcTask.withNpcName(npcName);
        }

        tasks.add(npcTask);

        // Add DialogueTask if dialogue is expected
        if (dialogueExpected) {
            DialogueTask dialogueTask = new DialogueTask()
                    .withClickThroughAll(true)
                    .withDescription("Handle dialogue for: " + getText());

            // Add specific dialogue options if provided
            if (!dialogueOptions.isEmpty()) {
                dialogueTask.withOptionSequence(dialogueOptions.toArray(new String[0]));
            }

            tasks.add(dialogueTask);
        }

        return tasks;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set the menu action (builder-style).
     *
     * @param action the menu action text
     * @return this step for chaining
     */
    public NpcQuestStep withMenuAction(String action) {
        this.menuAction = action;
        return this;
    }

    /**
     * Set whether dialogue is expected (builder-style).
     *
     * @param expected true if dialogue expected
     * @return this step for chaining
     */
    public NpcQuestStep withDialogueExpected(boolean expected) {
        this.dialogueExpected = expected;
        return this;
    }

    /**
     * Set NPC name filter (builder-style).
     *
     * @param name the NPC name
     * @return this step for chaining
     */
    public NpcQuestStep withNpcName(String name) {
        this.npcName = name;
        return this;
    }

    /**
     * Set location to walk to before interacting (builder-style).
     *
     * @param location the location
     * @return this step for chaining
     */
    public NpcQuestStep withWalkTo(WorldPoint location) {
        this.walkToLocation = location;
        return this;
    }

    /**
     * Set search radius (builder-style).
     *
     * @param radius the radius in tiles
     * @return this step for chaining
     */
    public NpcQuestStep withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Add dialogue options to select (builder-style).
     *
     * @param options the dialogue options in order
     * @return this step for chaining
     */
    public NpcQuestStep withDialogueOptions(String... options) {
        for (String opt : options) {
            this.dialogueOptions.add(opt);
        }
        return this;
    }
}

