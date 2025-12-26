package com.rocinante.quest;

import com.rocinante.quest.steps.QuestStep;
import com.rocinante.tasks.CompositeTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.tasks.TaskState;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

/**
 * Orchestrates quest execution by monitoring progress and translating steps to tasks.
 *
 * QuestExecutor is the bridge between the declarative Quest/QuestStep system and
 * the imperative Task execution system. It:
 * <ul>
 *   <li>Monitors the quest's progress varbit each game tick</li>
 *   <li>Determines the current step from varbit value</li>
 *   <li>Translates the current step into executable Tasks</li>
 *   <li>Queues tasks with the TaskExecutor</li>
 *   <li>Handles step transitions and completion</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * // Start a quest
 * questExecutor.startQuest(new TutorialIsland());
 *
 * // Quest runs automatically via GameTick events
 *
 * // Stop execution
 * questExecutor.stopQuest();
 * }</pre>
 */
@Slf4j
@Singleton
public class QuestExecutor {

    private final Client client;
    private final TaskExecutor taskExecutor;
    private final TaskContext taskContext;

    /**
     * Current quest being executed.
     */
    @Getter
    private Quest currentQuest;

    /**
     * Progress tracker for current quest.
     */
    @Getter
    private QuestProgress progress;

    /**
     * Whether quest execution is active.
     */
    @Getter
    private volatile boolean running = false;

    /**
     * Current task(s) being executed for the step.
     */
    private Task currentStepTask;

    /**
     * Last step that was converted to tasks.
     */
    private QuestStep lastExecutedStep;

    /**
     * Whether we're waiting for the current step task to complete.
     */
    private boolean waitingForStepCompletion = false;

    @Inject
    public QuestExecutor(Client client, TaskExecutor taskExecutor, TaskContext taskContext) {
        this.client = client;
        this.taskExecutor = taskExecutor;
        this.taskContext = taskContext;
    }

    /**
     * Start executing a quest.
     *
     * @param quest the quest to execute
     */
    public void startQuest(Quest quest) {
        if (running) {
            log.warn("Cannot start quest {} - already running {}", quest.getName(),
                    currentQuest != null ? currentQuest.getName() : "unknown");
            return;
        }

        this.currentQuest = quest;
        this.progress = new QuestProgress(quest);
        this.running = true;
        this.lastExecutedStep = null;
        this.currentStepTask = null;
        this.waitingForStepCompletion = false;

        // Initial progress check
        progress.update(client);

        log.info("Started quest execution: {}", quest.getName());
        log.info("Current progress: {}", progress);
    }

    /**
     * Stop quest execution.
     */
    public void stopQuest() {
        if (!running) {
            return;
        }

        running = false;

        // Cancel any pending tasks
        if (currentStepTask != null && currentStepTask.getState() == TaskState.RUNNING) {
            // TaskExecutor should handle cancellation
            log.info("Stopping current step task");
        }

        log.info("Stopped quest execution: {}",
                currentQuest != null ? currentQuest.getName() : "none");

        currentQuest = null;
        progress = null;
        currentStepTask = null;
        lastExecutedStep = null;
    }

    /**
     * Game tick handler - called each tick to check progress and execute steps.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        if (!running || currentQuest == null || progress == null) {
            return;
        }

        // Update progress from varbit
        boolean progressChanged = progress.update(client);

        // Check for quest completion
        if (progress.isCompleted()) {
            log.info("Quest {} completed!", currentQuest.getName());
            stopQuest();
            return;
        }

        // Get current step
        QuestStep currentStep = progress.getCurrentStep();
        if (currentStep == null) {
            log.debug("No current step for varbit {}", progress.getLastVarbitValue());
            return;
        }

        // If progress changed or waiting for completion, handle step transition
        if (progressChanged) {
            log.debug("Progress changed, current step: {}", currentStep.getText());
            waitingForStepCompletion = false;
            lastExecutedStep = null;
            currentStepTask = null;
        }

        // If we're still executing a task for this step, let it continue
        if (currentStepTask != null) {
            TaskState taskState = currentStepTask.getState();

            if (taskState == TaskState.COMPLETED) {
                log.debug("Step task completed: {}", currentStep.getText());
                currentStepTask = null;
                waitingForStepCompletion = true;
                // Wait for varbit to advance
                return;
            } else if (taskState == TaskState.FAILED) {
                log.warn("Step task failed: {}", currentStep.getText());
                currentStepTask = null;
                // Will retry on next tick
                return;
            } else if (taskState == TaskState.RUNNING || taskState == TaskState.PENDING) {
                // Still executing
                return;
            }
        }

        // If waiting for completion and step hasn't changed, wait more
        if (waitingForStepCompletion && currentStep == lastExecutedStep) {
            // Check if step has a completion condition
            if (currentStep.isComplete(taskContext)) {
                log.debug("Step completion condition met: {}", currentStep.getText());
                waitingForStepCompletion = false;
                // Will advance when varbit changes
            }
            return;
        }

        // Check step conditions
        if (!currentStep.conditionsMet(taskContext)) {
            log.trace("Step conditions not met: {}", currentStep.getText());
            return;
        }

        // Check if player should be idle
        if (currentStep.isRequiresIdle()) {
            if (!taskContext.getPlayerState().isIdle()) {
                log.trace("Waiting for player to be idle for step: {}", currentStep.getText());
                return;
            }
        }

        // Convert step to tasks and execute
        executeStep(currentStep);
    }

    /**
     * Execute a quest step by converting it to tasks.
     *
     * @param step the step to execute
     */
    private void executeStep(QuestStep step) {
        log.info("Executing step: {}", step.getText());

        // Convert step to tasks
        List<Task> tasks = step.toTasks(taskContext);

        if (tasks == null || tasks.isEmpty()) {
            log.warn("Step {} produced no tasks", step.getText());
            waitingForStepCompletion = true;
            lastExecutedStep = step;
            return;
        }

        // Create composite if multiple tasks
        if (tasks.size() == 1) {
            currentStepTask = tasks.get(0);
        } else {
            currentStepTask = CompositeTask.sequential(tasks.toArray(new Task[0]))
                    .withDescription("Step: " + step.getText());
        }

        // Queue with task executor
        taskExecutor.queueTask(currentStepTask);
        lastExecutedStep = step;
        waitingForStepCompletion = false;
    }

    /**
     * Get status summary for debugging/display.
     *
     * @return status string
     */
    public String getStatus() {
        if (!running || currentQuest == null) {
            return "Not running";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Quest: ").append(currentQuest.getName()).append("\n");

        if (progress != null) {
            sb.append("Progress: ").append(String.format("%.0f%%", progress.getProgressPercent() * 100));
            sb.append(" (").append(progress.getCurrentStepNumber());
            sb.append("/").append(progress.getTotalSteps()).append(")\n");
            sb.append("Varbit: ").append(progress.getLastVarbitValue()).append("\n");

            QuestStep step = progress.getCurrentStep();
            if (step != null) {
                sb.append("Current step: ").append(step.getText()).append("\n");
            }
        }

        if (currentStepTask != null) {
            sb.append("Task state: ").append(currentStepTask.getState()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Check if a quest is currently being executed.
     *
     * @param quest the quest to check
     * @return true if this specific quest is running
     */
    public boolean isExecuting(Quest quest) {
        return running && currentQuest != null && currentQuest.getId().equals(quest.getId());
    }

    /**
     * Force advance to next step (for debugging/testing).
     */
    public void forceNextStep() {
        if (!running || progress == null) {
            return;
        }

        QuestStep next = progress.getNextStep();
        if (next != null) {
            log.warn("Force advancing to next step: {}", next.getText());
            lastExecutedStep = null;
            currentStepTask = null;
            waitingForStepCompletion = false;
        }
    }
}

