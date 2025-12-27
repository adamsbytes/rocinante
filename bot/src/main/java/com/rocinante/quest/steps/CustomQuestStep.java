package com.rocinante.quest.steps;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;

import java.util.List;
import java.util.function.Function;

/**
 * Quest step that uses a custom task factory function.
 * 
 * This allows dynamic task creation based on runtime state,
 * which is useful for complex conditional logic or when standard
 * quest step types are insufficient.
 * 
 * Example usage:
 * <pre>{@code
 * new CustomQuestStep("Select ironman type", 
 *     ctx -> new IronmanSelectionTask(ironmanState))
 * }</pre>
 */
public class CustomQuestStep extends QuestStep {

    private final Function<TaskContext, Task> taskFactory;

    /**
     * Create a custom quest step.
     * 
     * @param description step description
     * @param taskFactory function that creates the task
     */
    public CustomQuestStep(String description, Function<TaskContext, Task> taskFactory) {
        super(description);
        this.taskFactory = taskFactory;
    }

    @Override
    public StepType getType() {
        return StepType.CUSTOM;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        Task task = taskFactory.apply(ctx);
        if (task == null) {
            throw new IllegalStateException("CustomQuestStep task factory returned null");
        }
        return List.of(task);
    }
}

