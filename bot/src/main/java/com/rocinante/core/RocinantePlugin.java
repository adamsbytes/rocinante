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
import com.rocinante.behavior.BotActivityTracker;
import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.BreakType;
import com.rocinante.behavior.EmergencyHandler;
import com.rocinante.behavior.FatigueModel;
import com.rocinante.behavior.AttentionModel;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.behavior.emergencies.LowHealthEmergency;
import com.rocinante.behavior.emergencies.PoisonEmergency;
import com.rocinante.config.RocinanteConfig;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.state.InventoryState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.tasks.TaskPriority;
import com.rocinante.timing.HumanTimer;
import com.rocinante.quest.QuestExecutor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import java.util.List;
import java.util.function.Function;

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

    // === Behavioral Components ===

    @Inject
    @Getter
    private BotActivityTracker activityTracker;

    @Inject
    @Getter
    private FatigueModel fatigueModel;

    @Inject
    @Getter
    private AttentionModel attentionModel;

    @Inject
    @Getter
    private PlayerProfile playerProfile;

    @Inject
    @Getter
    private BreakScheduler breakScheduler;

    @Inject
    @Getter
    private EmergencyHandler emergencyHandler;

    @Inject
    private Function<BreakType, Task> breakTaskFactory;

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

        // === Register Behavioral Components ===
        
        // Register BotActivityTracker - tracks current activity type
        eventBus.register(activityTracker);
        
        // Register AttentionModel - manages attention state transitions
        eventBus.register(attentionModel);
        
        // Register BreakScheduler - schedules behavioral breaks
        eventBus.register(breakScheduler);
        
        // Wire up BreakScheduler with its task factory
        breakScheduler.setBreakTaskFactory(breakTaskFactory);
        
        // Wire up TaskExecutor with behavioral components
        taskExecutor.setBreakScheduler(breakScheduler);
        taskExecutor.setEmergencyHandler(emergencyHandler);
        
        // Register emergency conditions
        registerEmergencyConditions();
        
        // Configure TaskExecutor
        taskExecutor.setOnTaskStuckCallback(this::onTaskStuck);

        log.info("Rocinante plugin started - Services registered");
        log.info("  GameStateService: registered");
        log.info("  LoginFlowHandler: registered (auto-active)");
        log.info("  TaskExecutor: registered (stopped)");
        log.info("  QuestExecutor: registered");
        log.info("  BehavioralSystem: registered (activity={}, fatigue={}, attention={})",
                activityTracker != null, fatigueModel != null, attentionModel != null);
    }
    
    /**
     * Register emergency conditions with the EmergencyHandler.
     * These conditions are checked every game tick and can interrupt any task.
     */
    private void registerEmergencyConditions() {
        // Low Health Emergency - triggers when HP is critically low
        emergencyHandler.registerCondition(
                new LowHealthEmergency(activityTracker, ctx -> createEatFoodTask(ctx))
        );
        
        // Poison Emergency - triggers when poisoned with low health
        emergencyHandler.registerCondition(
                new PoisonEmergency(activityTracker, ctx -> createDrinkAntidoteTask(ctx))
        );
        
        log.info("Registered {} emergency conditions", emergencyHandler.getConditionCount());
    }

    /**
     * Create a task to eat the best available food.
     * Used by LowHealthEmergency.
     */
    private Task createEatFoodTask(TaskContext ctx) {
        InventoryState inventory = ctx.getInventoryState();
        int foodSlot = inventory.getBestFood();
        
        if (foodSlot < 0) {
            log.warn("Emergency: No food available to eat");
            return null;
        }
        
        return new AbstractTask() {
            private final AtomicBoolean started = new AtomicBoolean(false);
            private final AtomicReference<CompletableFuture<Boolean>> pending = new AtomicReference<>();
            
            {
                this.timeout = Duration.ofSeconds(5);
                this.priority = TaskPriority.URGENT;
            }
            
            @Override
            public String getDescription() {
                return "Emergency: Eat food";
            }
            
            @Override
            public boolean canExecute(TaskContext context) {
                return context.getInventoryClickHelper() != null;
            }
            
            @Override
            protected void executeImpl(TaskContext context) {
                if (started.compareAndSet(false, true)) {
                    log.info("Emergency eating food from slot {}", foodSlot);
                    var clickHelper = context.getInventoryClickHelper();
                    pending.set(clickHelper.executeClick(foodSlot, "emergency food"));
                }
                
                CompletableFuture<Boolean> future = pending.get();
                if (future != null && future.isDone()) {
                    complete();
                }
            }
        };
    }

    /**
     * Create a task to drink an antipoison/antidote.
     * Used by PoisonEmergency.
     */
    private Task createDrinkAntidoteTask(TaskContext ctx) {
        InventoryState inventory = ctx.getInventoryState();
        int antidoteSlot = inventory.getAntipoisonSlot();
        
        if (antidoteSlot < 0) {
            log.warn("Emergency: No antipoison available");
            return null;
        }
        
        return new AbstractTask() {
            private final AtomicBoolean started = new AtomicBoolean(false);
            private final AtomicReference<CompletableFuture<Boolean>> pending = new AtomicReference<>();
            
            {
                this.timeout = Duration.ofSeconds(5);
                this.priority = TaskPriority.URGENT;
            }
            
            @Override
            public String getDescription() {
                return "Emergency: Drink antipoison";
            }
            
            @Override
            public boolean canExecute(TaskContext context) {
                return context.getInventoryClickHelper() != null;
            }
            
            @Override
            protected void executeImpl(TaskContext context) {
                if (started.compareAndSet(false, true)) {
                    log.info("Emergency drinking antipoison from slot {}", antidoteSlot);
                    var clickHelper = context.getInventoryClickHelper();
                    pending.set(clickHelper.executeClick(antidoteSlot, "emergency antipoison"));
                }
                
                CompletableFuture<Boolean> future = pending.get();
                if (future != null && future.isDone()) {
                    complete();
                }
            }
        };
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("Rocinante plugin stopping...");

        // Stop task execution first
        taskExecutor.stop();
        
        // Save player profile before shutdown
        try {
            playerProfile.saveProfile();
            log.info("Player profile saved");
        } catch (Exception e) {
            log.warn("Failed to save player profile: {}", e.getMessage());
        }

        // === Unregister Behavioral Components ===
        eventBus.unregister(breakScheduler);
        eventBus.unregister(attentionModel);
        eventBus.unregister(activityTracker);
        
        // Clear emergency conditions
        emergencyHandler.clearConditions();

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
