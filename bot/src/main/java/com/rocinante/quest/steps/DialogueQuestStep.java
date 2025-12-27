package com.rocinante.quest.steps;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.DialogueTask;

import java.util.List;

/**
 * Quest step for handling dialogue interactions.
 * 
 * Wraps DialogueTask for quest step usage.
 * Supports:
 * - Clicking through dialogue
 * - Selecting specific options by text
 * - Selecting options by index
 * - Multi-step dialogue sequences
 */
public class DialogueQuestStep extends QuestStep {

    private final String optionText;
    private final int optionIndex;
    private final boolean clickThrough;
    private final List<String> optionSequence;

    /**
     * Create a dialogue step that just clicks through.
     * 
     * @param description step description
     */
    public DialogueQuestStep(String description) {
        super(description);
        this.optionText = null;
        this.optionIndex = -1;
        this.clickThrough = true;
        this.optionSequence = null;
    }

    /**
     * Create a dialogue step with an option text to select.
     * 
     * @param description step description  
     * @param optionText option text to select (partial match)
     */
    private DialogueQuestStep(String description, String optionText) {
        super(description);
        this.optionText = optionText;
        this.optionIndex = -1;
        this.clickThrough = false;
        this.optionSequence = null;
    }

    /**
     * Create a dialogue step with an option index to select.
     * 
     * @param description step description
     * @param optionIndex 1-based option index
     */
    private DialogueQuestStep(String description, int optionIndex) {
        super(description);
        this.optionText = null;
        this.optionIndex = optionIndex;
        this.clickThrough = false;
        this.optionSequence = null;
    }

    /**
     * Create a dialogue step with a sequence of options to select.
     * 
     * @param description step description
     * @param optionSequence list of options to select in order
     */
    private DialogueQuestStep(String description, List<String> optionSequence) {
        super(description);
        this.optionText = null;
        this.optionIndex = -1;
        this.clickThrough = false;
        this.optionSequence = optionSequence;
    }

    @Override
    public StepType getType() {
        return StepType.CUSTOM;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        DialogueTask task = new DialogueTask();
        
        if (optionText != null) {
            task.withOptionText(optionText);
        } else if (optionIndex > 0) {
            task.withOptionIndex(optionIndex);
        } else if (optionSequence != null && !optionSequence.isEmpty()) {
            task.withOptionSequence(optionSequence.toArray(new String[0]));
        } else if (clickThrough) {
            task.withClickThroughAll(true);
        }
        
        return List.of(task);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set the option text to select.
     * 
     * @param text option text (partial match)
     * @return this step for chaining
     */
    public DialogueQuestStep withOptionText(String text) {
        return new DialogueQuestStep(getText(), text);
    }

    /**
     * Set the option index to select.
     * 
     * @param index 1-based option index
     * @return this step for chaining
     */
    public DialogueQuestStep withOptionIndex(int index) {
        return new DialogueQuestStep(getText(), index);
    }

    /**
     * Set a sequence of options to select.
     * 
     * @param options list of option texts
     * @return this step for chaining
     */
    public DialogueQuestStep withOptionSequence(List<String> options) {
        return new DialogueQuestStep(getText(), options);
    }

    /**
     * Set whether to click through all dialogue.
     * Note: This returns a new instance because DialogueQuestStep is immutable.
     * 
     * @param shouldClickThrough true to click through
     * @return new DialogueQuestStep for chaining
     */
    public DialogueQuestStep withClickThrough(boolean shouldClickThrough) {
        // Just return this since clickThrough is already set in constructor
        // This method exists for API compatibility but doesn't change behavior
        return this;
    }
}

