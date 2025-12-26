package com.rocinante.core;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import com.rocinante.config.RocinanteConfig;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.tasks.TaskPriority;
import com.rocinante.timing.HumanTimer;
import com.rocinante.quest.QuestExecutor;

import java.util.List;

/**
 * Main plugin entry point for the Rocinante automation framework.
 *
 * This plugin manages the lifecycle of all automation components and
 * coordinates between the various services (state management, input,
 * timing, task execution).
 *
 * Per REQUIREMENTS.md Section 2.1, this is the single entry point with
 * lifecycle management for the entire framework.
 */
@Slf4j
@PluginDescriptor(
    name = "Rocinante",
    description = "Human-like automation framework",
    tags = {"automation", "quests", "combat", "slayer"},
    enabledByDefault = true
)
public class RocinantePlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private RocinanteConfig config;

    @Inject
    private EventBus eventBus;

    @Inject
    @Getter
    private GameStateService gameStateService;

    @Inject
    @Getter
    private RobotMouseController mouseController;

    @Inject
    @Getter
    private RobotKeyboardController keyboardController;

    @Inject
    @Getter
    private HumanTimer humanTimer;

    @Inject
    @Getter
    private TaskContext taskContext;

    @Inject
    @Getter
    private TaskExecutor taskExecutor;

    @Inject
    @Getter
    private LoginFlowHandler loginFlowHandler;

    @Inject
    @Getter
    private QuestExecutor questExecutor;

    @Override
    protected void startUp() throws Exception
    {
        log.info("Rocinante plugin starting...");

        // Register GameStateService with the event bus
        // GameStateService needs to receive events for state tracking
        eventBus.register(gameStateService);

        // Register LoginFlowHandler with the event bus
        // LoginFlowHandler auto-handles license agreement and name entry screens
        // This runs before task automation and is always active
        eventBus.register(loginFlowHandler);

        // Register TaskExecutor with the event bus
        // TaskExecutor handles task execution on each GameTick
        eventBus.register(taskExecutor);

        // Register QuestExecutor with the event bus
        // QuestExecutor monitors quest progress and queues tasks
        eventBus.register(questExecutor);

        // Configure TaskExecutor
        taskExecutor.setOnTaskStuckCallback(this::onTaskStuck);

        log.info("Rocinante plugin started - Services registered");
        log.info("  GameStateService: registered");
        log.info("  LoginFlowHandler: registered (auto-active)");
        log.info("  TaskExecutor: registered (stopped)");
        log.info("  QuestExecutor: registered");
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("Rocinante plugin stopping...");

        // Stop task execution first
        taskExecutor.stop();

        // Unregister QuestExecutor from the event bus
        eventBus.unregister(questExecutor);

        // Unregister TaskExecutor from the event bus
        eventBus.unregister(taskExecutor);

        // Unregister LoginFlowHandler from the event bus
        eventBus.unregister(loginFlowHandler);
        loginFlowHandler.reset();

        // Unregister GameStateService from the event bus
        eventBus.unregister(gameStateService);

        // Invalidate all cached state
        gameStateService.invalidateAllCaches();

        // Clear task context variables
        taskContext.clearVariables();

        log.info("Rocinante plugin stopped - Services unregistered");
    }

    /**
     * Callback invoked when a task appears to be stuck (nearing timeout).
     *
     * @param task the stuck task
     */
    private void onTaskStuck(Task task)
    {
        log.warn("Task appears stuck: {} (execution time exceeded 80% of timeout)",
                task.getDescription());
        // Could implement additional handling here:
        // - Take screenshot for debugging
        // - Attempt recovery actions
        // - Notify monitoring system
    }

    // ========================================================================
    // Task Management API
    // ========================================================================

    /**
     * Start the task executor.
     * Tasks will begin executing on each game tick.
     */
    public void startAutomation()
    {
        log.info("Starting automation...");
        taskExecutor.start();
    }

    /**
     * Stop the task executor.
     * Current task will be aborted and queue will be preserved.
     */
    public void stopAutomation()
    {
        log.info("Stopping automation...");
        taskExecutor.stop();
    }

    /**
     * Check if automation is currently running.
     *
     * @return true if task executor is enabled
     */
    public boolean isAutomationRunning()
    {
        return taskExecutor.getEnabled().get();
    }

    /**
     * Queue a task for execution.
     *
     * @param task the task to queue
     */
    public void queueTask(Task task)
    {
        taskExecutor.queueTask(task);
    }

    /**
     * Queue a task with specific priority.
     *
     * @param task     the task to queue
     * @param priority the priority level
     */
    public void queueTask(Task task, TaskPriority priority)
    {
        taskExecutor.queueTask(task, priority);
    }

    /**
     * Queue multiple tasks for sequential execution.
     *
     * @param tasks the tasks to queue
     */
    public void queueTasks(List<Task> tasks)
    {
        taskExecutor.queueTasks(tasks);
    }

    /**
     * Clear all pending tasks.
     */
    public void clearTaskQueue()
    {
        taskExecutor.clearQueue();
    }

    /**
     * Get the current task status for debugging/UI.
     *
     * @return status string
     */
    public String getTaskStatus()
    {
        return taskExecutor.getStatus();
    }

    /**
     * Check if the login flow is complete (license accepted, name entered if needed).
     * This should be true before automation tasks can run effectively.
     *
     * @return true if the bot is ready for task automation
     */
    public boolean isLoginFlowComplete()
    {
        return loginFlowHandler.isLoginFlowComplete();
    }

    @Provides
    RocinanteConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(RocinanteConfig.class);
    }
}
