package com.rocinante.quest;

import com.rocinante.quest.steps.QuestStep;
import com.rocinante.tasks.CompositeTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.impl.WalkToTask;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
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

    /**
     * Distance threshold in tiles. If player is further than this from the step's
     * target location, automatically prepend a WalkToTask.
     * 
     * Set to 15 tiles (roughly render/interaction range) - if the target is within
     * this range, the step's own logic should be able to handle finding/interacting.
     */
    /**
     * Distance threshold for auto-walking to step locations.
     * If player is further than this from the step's target location, we walk first.
     * Keep this tight - we want to be close to combat locations for reliable targeting.
     */
    private static final int AUTO_WALK_THRESHOLD_TILES = 3;

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
     * Handle game state changes - force varbit refresh on login.
     * 
     * This ensures that after any disconnect/reconnect, the quest progress
     * is re-synchronized with the actual game state rather than relying on
     * potentially stale cached values.
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            if (running && progress != null) {
                log.info("Login detected - forcing quest progress refresh from actual varbits");
                
                // Force a refresh by resetting the tracked value
                // This ensures the next update() call will re-read and re-evaluate
                forceProgressRefresh();
            }
        }
    }

    /**
     * Force a refresh of quest progress from actual game varbits.
     * 
     * Call this after login, reconnect, or any situation where the cached
     * progress state might be stale.
     */
    public void forceProgressRefresh() {
        if (progress == null || client == null) {
            return;
        }
        
        // Reset the task state so we don't try to continue stale tasks
        currentStepTask = null;
        lastExecutedStep = null;
        waitingForStepCompletion = false;
        
        // Force progress to re-evaluate by clearing its cached state
        progress.forceRefresh(client);
        
        log.info("Quest progress refreshed: {}", progress);
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
            log.debug("Step conditions not met: {}", currentStep.getText());
            return;
        }

        // Check if player should be idle
        if (currentStep.isRequiresIdle()) {
            if (!taskContext.getPlayerState().isIdle()) {
                log.debug("Waiting for player to be idle for step: {}", currentStep.getText());
                return;
            }
        }

        // Convert step to tasks and execute
        executeStep(currentStep);
    }

    /**
     * Execute a quest step by converting it to tasks.
     * 
     * If the step has a target location and the player is far from it,
     * this automatically prepends a WalkToTask to get there first.
     *
     * @param step the step to execute
     */
    private void executeStep(QuestStep step) {
        log.info("Executing step: {}", step.getText());

        // Check if we need to walk to the step's location first
        WorldPoint targetLocation = step.getTargetLocation();
        WorldPoint playerLocation = client.getLocalPlayer() != null 
                ? client.getLocalPlayer().getWorldLocation() 
                : null;
        
        boolean needsAutoWalk = false;
        if (targetLocation != null && playerLocation != null) {
            int distance = playerLocation.distanceTo(targetLocation);
            if (distance > AUTO_WALK_THRESHOLD_TILES) {
                log.info("Player is {} tiles from step target location {}, auto-walking first", 
                        distance, targetLocation);
                needsAutoWalk = true;
            } else {
                log.debug("Player is {} tiles from step target location {}, no auto-walk needed", 
                        distance, targetLocation);
            }
        }

        // Convert step to tasks
        List<Task> stepTasks = step.toTasks(taskContext);

        if (stepTasks == null || stepTasks.isEmpty()) {
            log.warn("Step {} produced no tasks", step.getText());
            waitingForStepCompletion = true;
            lastExecutedStep = step;
            return;
        }

        // Build final task list, potentially with WalkToTask prepended
        List<Task> tasks;
        if (needsAutoWalk) {
            tasks = new ArrayList<>();
            WalkToTask walkTask = new WalkToTask(targetLocation)
                    .withDescription("Auto-walk to step location: " + step.getText());
            tasks.add(walkTask);
            tasks.addAll(stepTasks);
        } else {
            tasks = stepTasks;
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

