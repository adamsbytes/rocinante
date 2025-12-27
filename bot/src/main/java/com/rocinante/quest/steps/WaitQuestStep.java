package com.rocinante.quest.steps;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Quest step that waits for a condition to be met (typically varbit advancement).
 *
 * This step produces no tasks - it simply waits for the quest progress to advance.
 * Use this when a previous step has initiated an action (like combat) and we need
 * to wait for the outcome without re-initiating the action.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Step 450: Start combat
 * steps.put(450, new CombatQuestStep(NPC_GIANT_RAT, "Attack the giant rat"));
 *
 * // Step 460: Wait for combat to complete (don't start new combat)
 * steps.put(460, new WaitQuestStep("Waiting for rat to die"));
 * }</pre>
 */
@Slf4j
public class WaitQuestStep extends QuestStep {

    /**
     * Create a wait step with instruction text.
     *
     * @param text the instruction text shown while waiting
     */
    public WaitQuestStep(String text) {
        super(text);
    }

    @Override
    public StepType getType() {
        return StepType.CUSTOM;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        // Wait steps produce no tasks - we just wait for varbit to advance
        log.debug("WaitQuestStep '{}' - producing no tasks, waiting for progression", getText());
        return new ArrayList<>();
    }
}

