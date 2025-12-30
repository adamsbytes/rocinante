package com.rocinante.core;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Provider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ExternalPluginsChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.slayer.SlayerPlugin;
import com.rocinante.behavior.BotActivityTracker;
import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.BreakType;
import com.rocinante.behavior.EmergencyHandler;
import com.rocinante.behavior.FatigueModel;
import com.rocinante.behavior.AttentionModel;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.behavior.emergencies.EmergencyTask;
import com.rocinante.behavior.emergencies.LowHealthEmergency;
import com.rocinante.behavior.emergencies.PoisonEmergency;
import com.rocinante.config.RocinanteConfig;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.state.InventoryState;
import com.rocinante.status.CommandProcessor;
import com.rocinante.status.StatusPublisher;
import com.rocinante.status.XpTracker;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.tasks.TaskPriority;
import com.rocinante.timing.HumanTimer;
import com.rocinante.quest.QuestExecutor;
import com.rocinante.quest.QuestService;
import com.rocinante.util.Randomization;
import com.rocinante.behavior.tasks.MicroPauseTask;
import com.rocinante.behavior.tasks.ShortBreakTask;
import com.rocinante.behavior.tasks.LongBreakTask;
import net.runelite.client.plugins.slayer.SlayerPluginService;

import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
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
@PluginDependency(SlayerPlugin.class)
public class RocinantePlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private RocinanteConfig config;

    @Inject
    private EventBus eventBus;

    @Inject
    private PluginManager pluginManager;

    @Inject
    private Provider<GameStateService> gameStateServiceProvider;
    
    /**
     * Get the GameStateService instance.
     * Uses Provider to break circular dependency during injection.
     */
    public GameStateService getGameStateService() {
        return gameStateServiceProvider.get();
    }

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
    private Randomization randomization;

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

    @Inject
    @Getter
    private QuestService questService;

    // === State Components ===

    @Inject
    @Getter
    private com.rocinante.state.IronmanState ironmanState;

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

    // Break task factory - created manually in startUp() as it requires runtime wiring
    private Function<BreakType, Task> breakTaskFactory;

    // === Behavioral Anti-Detection Components ===

    @Inject
    @Getter
    private com.rocinante.input.CameraController cameraController;

    @Inject
    @Getter
    private com.rocinante.input.MouseCameraCoupler mouseCameraCoupler;

    @Inject
    @Getter
    private com.rocinante.behavior.ActionSequencer actionSequencer;

    @Inject
    @Getter
    private com.rocinante.behavior.InefficiencyInjector inefficiencyInjector;

    @Inject
    @Getter
    private com.rocinante.behavior.PredictiveHoverManager predictiveHoverManager;

    @Inject
    @Getter
    private com.rocinante.behavior.LogoutHandler logoutHandler;

    @Inject
    @Getter
    private com.rocinante.behavior.tasks.TradeHandler tradeHandler;

    // === Status & Communication Components ===

    @Inject
    @Getter
    private XpTracker xpTracker;

    @Inject
    @Getter
    private StatusPublisher statusPublisher;

    @Inject
    @Getter
    private CommandProcessor commandProcessor;

    @Inject
    @Getter
    private com.rocinante.status.TaskFactory taskFactory;

    @Inject
    @Getter
    private com.rocinante.combat.TargetSelector targetSelector;

    @Inject
    @Getter
    private com.rocinante.combat.CombatManager combatManager;

    // Optional RuneLite plugin services - may be null if plugin not loaded
    @Inject
    @Nullable
    private SlayerPluginService slayerPluginService;

    // Quest Helper waiting state
    private static final String QUEST_HELPER_CLASS = "com.questhelper.QuestHelperPlugin";
    private static final Duration QUEST_HELPER_TIMEOUT = Duration.ofSeconds(30);
    private Instant questHelperWaitStart;
    private boolean questHelperInitialized = false;
    private boolean questHelperTimeoutReported = false;

    @Override
    protected void startUp() throws Exception
    {
        log.info("Rocinante plugin starting...");

        // Register GameStateService with the event bus
        // GameStateService needs to receive events for state tracking
        eventBus.register(gameStateServiceProvider.get());
        
        // Wire TaskExecutor to GameStateService (setter injection to break circular dependency)
        gameStateServiceProvider.get().setTaskExecutorProvider(() -> taskExecutor);
        
        // Wire SlayerPluginService to GameStateService (optional - may be null if Slayer plugin not loaded)
        gameStateServiceProvider.get().setSlayerPluginService(slayerPluginService);

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
        
        // Register FatigueModel - tracks fatigue accumulation over time
        eventBus.register(fatigueModel);
        
        // Register AttentionModel - manages attention state transitions
        eventBus.register(attentionModel);
        
        // Register BreakScheduler - schedules behavioral breaks
        eventBus.register(breakScheduler);
        
        // Register MouseCameraCoupler - idle camera behaviors
        eventBus.register(mouseCameraCoupler);
        
        // Register TradeHandler - handles incoming trade requests
        eventBus.register(tradeHandler);
        
        // Wire up TradeHandler with TaskExecutor
        tradeHandler.setTaskExecutor(taskExecutor);
        
        // Create and wire up BreakScheduler with its task factory
        breakTaskFactory = breakType -> {
            switch (breakType) {
                case MICRO_PAUSE:
                    return new MicroPauseTask(breakScheduler, fatigueModel, randomization);
                case SHORT_BREAK:
                    return new ShortBreakTask(breakScheduler, fatigueModel, playerProfile, 
                            randomization, humanTimer);
                case LONG_BREAK:
                    return new LongBreakTask(breakScheduler, fatigueModel, playerProfile,
                            randomization, humanTimer);
                case SESSION_END:
                    log.info("Session end triggered - should logout");
                    return null;
                default:
                    log.warn("Unknown break type: {}", breakType);
                    return null;
            }
        };
        breakScheduler.setBreakTaskFactory(breakTaskFactory);
        
        // NOTE: TaskExecutor now receives BreakScheduler and EmergencyHandler via constructor injection
        
        // === Wire behavioral components to input controllers ===
        
        // Wire camera controller with player profile
        cameraController.setPlayerProfile(playerProfile);
        
        // Wire mouse camera coupler with player profile and attention model
        mouseCameraCoupler.setPlayerProfile(playerProfile);
        mouseCameraCoupler.setAttentionModel(attentionModel);
        
        // Wire action sequencer with player profile
        actionSequencer.setPlayerProfile(playerProfile);
        
        // Wire inefficiency injector with player profile and fatigue model
        inefficiencyInjector.setPlayerProfile(playerProfile);
        inefficiencyInjector.setFatigueModel(fatigueModel);
        
        // Wire logout handler with controllers and profile
        logoutHandler.setMouseController(mouseController);
        logoutHandler.setKeyboardController(keyboardController);
        logoutHandler.setPlayerProfile(playerProfile);
        
        // Wire mouse controller with camera coupler, inefficiency injector, and predictive hover
        mouseController.setCameraCoupler(mouseCameraCoupler);
        mouseController.setInefficiencyInjector(inefficiencyInjector);
        mouseController.setPredictiveHoverManager(predictiveHoverManager);
        
        // Wire camera coupler with predictive hover manager for coordination
        mouseCameraCoupler.setPredictiveHoverManager(predictiveHoverManager);
        
        // Wire behavioral models to input controllers
        // (FatigueModel and PlayerProfile are constructor-injected)
        humanTimer.setFatigueModel(fatigueModel);
        humanTimer.setAttentionModel(attentionModel);
        
        // Register emergency conditions
        registerEmergencyConditions();
        
        // Configure TaskExecutor
        taskExecutor.setOnTaskStuckCallback(this::onTaskStuck);

        // === Register Status & Communication Components ===
        
        // Register XpTracker - tracks XP gains per skill
        eventBus.register(xpTracker);
        
        // Register StatusPublisher - writes status JSON for web UI
        eventBus.register(statusPublisher);
        
        // Register CommandProcessor - processes commands from web UI
        eventBus.register(commandProcessor);
        
        // Wire StatusPublisher with dependencies
        statusPublisher.setTaskExecutor(taskExecutor);
        statusPublisher.setXpTracker(xpTracker);
        statusPublisher.setFatigueModel(fatigueModel);
        statusPublisher.setBreakScheduler(breakScheduler);
        statusPublisher.setQuestService(questService);
        
        // Wire CommandProcessor with TaskExecutor, TaskFactory, and StatusPublisher
        commandProcessor.setTaskExecutor(taskExecutor);
        commandProcessor.setTaskFactory(taskFactory);
        commandProcessor.setStatusPublisher(statusPublisher);
        
        // Wire TaskFactory with dependencies (for manual task creation)
        taskFactory.setQuestExecutor(questExecutor);
        taskFactory.setTargetSelector(targetSelector);
        taskFactory.setCombatManager(combatManager);
        taskFactory.setQuestService(questService);
        
        // Try to initialize Quest Helper immediately (might already be loaded)
        if (tryInitializeQuestHelper()) {
            // Quest Helper found, start status systems
            startStatusSystems();
        } else {
            // Quest Helper not loaded yet, start waiting
            questHelperWaitStart = Instant.now();
            log.info("Waiting for Quest Helper plugin to load (timeout: {}s)...", QUEST_HELPER_TIMEOUT.getSeconds());
        }

        // Ensure required RuneLite plugins are enabled
        ensureRequiredPluginsEnabled();

        log.info("Rocinante plugin started - Services registered");
        log.info("  GameStateService: registered");
        log.info("  LoginFlowHandler: registered (auto-active)");
        log.info("  TaskExecutor: registered (stopped)");
        log.info("  QuestExecutor: registered");
        log.info("  BehavioralSystem: registered (activity={}, fatigue={}, attention={})",
                activityTracker != null, fatigueModel != null, attentionModel != null);
        log.info("  Anti-Detection: registered (camera={}, coupler={}, sequencer={}, inefficiency={}, logout={})",
                cameraController != null, mouseCameraCoupler != null, actionSequencer != null,
                inefficiencyInjector != null, logoutHandler != null);
        log.info("  StatusSystem: {} (waiting for Quest Helper: {})",
                questHelperInitialized ? "started" : "pending", !questHelperInitialized);
    }

    /**
     * Ensure required RuneLite plugins are enabled for full functionality.
     * 
     * Required plugins:
     * - Kourend Library: For Arceuus Library book location predictions
     */
    private void ensureRequiredPluginsEnabled() {
        // List of required plugin class names
        String[] requiredPlugins = {
                "net.runelite.client.plugins.kourendlibrary.KourendLibraryPlugin"
        };

        for (String pluginClassName : requiredPlugins) {
            try {
                Class<?> pluginClass = Class.forName(pluginClassName);
                
                // Find the plugin instance
                Plugin targetPlugin = null;
                for (Plugin plugin : pluginManager.getPlugins()) {
                    if (pluginClass.isInstance(plugin)) {
                        targetPlugin = plugin;
                        break;
                    }
                }

                if (targetPlugin == null) {
                    log.warn("Required plugin not found: {}", pluginClassName);
                    continue;
                }

                // Check if enabled
                if (!pluginManager.isPluginEnabled(targetPlugin)) {
                    log.info("Enabling required plugin: {}", targetPlugin.getName());
                    pluginManager.setPluginEnabled(targetPlugin, true);
                    
                    // Start the plugin if not already active
                    try {
                        pluginManager.startPlugin(targetPlugin);
                        log.info("Started required plugin: {}", targetPlugin.getName());
                    } catch (PluginInstantiationException e) {
                        log.error("Failed to start required plugin: {}", targetPlugin.getName(), e);
                    }
                } else {
                    log.debug("Required plugin already enabled: {}", targetPlugin.getName());
                }
            } catch (ClassNotFoundException e) {
                log.warn("Required plugin class not found: {} - this plugin may not be available", pluginClassName);
            }
        }
    }
    
    /**
     * Try to find and initialize Quest Helper plugin.
     * @return true if Quest Helper was found and initialized, false if not yet loaded
     */
    private boolean tryInitializeQuestHelper() {
        if (questHelperInitialized) {
            return true;
        }
        
            // Find Quest Helper plugin instance
            for (Plugin plugin : pluginManager.getPlugins()) {
                if (plugin.getClass().getName().equals(QUEST_HELPER_CLASS)) {
                questService.initializeQuestHelper(plugin);
                questHelperInitialized = true;
                log.info("Quest Helper integration initialized");
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Start status publishing and command processing systems.
     * Called after Quest Helper is confirmed available.
     */
    private void startStatusSystems() {
        statusPublisher.start();
        commandProcessor.start();
        log.info("Status systems started (Quest Helper available)");
    }
    
    /**
     * Handle external plugins being loaded/changed.
     * Quest Helper is an external plugin that may load after our plugin.
     */
    @Subscribe
    public void onExternalPluginsChanged(ExternalPluginsChanged event) {
        if (questHelperInitialized) {
            return;
        }
        
        if (tryInitializeQuestHelper()) {
            startStatusSystems();
        }
    }
    
    /**
     * Check for Quest Helper on each game tick during warmup period.
     * Also enforces timeout - logs error once if Quest Helper not found.
     */
    @Subscribe
    public void onGameTick(GameTick tick) {
        if (questHelperInitialized || questHelperTimeoutReported) {
            return;
        }
        
        // Check if Quest Helper appeared
        if (tryInitializeQuestHelper()) {
            startStatusSystems();
            return;
        }
        
        // Check timeout
        if (questHelperWaitStart != null) {
            Duration elapsed = Duration.between(questHelperWaitStart, Instant.now());
            if (elapsed.compareTo(QUEST_HELPER_TIMEOUT) > 0) {
                // Final check before failing
                if (tryInitializeQuestHelper()) {
                    startStatusSystems();
                    return;
                }
                
                // Timeout exceeded - log error ONCE and mark as failed
                // (Can't throw here - RuneLite catches and ignores exceptions in event handlers)
                questHelperTimeoutReported = true;
                log.error("Quest Helper plugin not found after {}s timeout. Quest Helper is REQUIRED.", 
                        QUEST_HELPER_TIMEOUT.getSeconds());
                log.error("Status publishing and quest data will NOT be available!");
                log.error("Ensure Quest Helper is installed from Plugin Hub and enabled.");
            }
        }
    }
    
    /**
     * Register emergency conditions with the EmergencyHandler.
     * These conditions are checked every game tick and can interrupt any task.
     */
    private void registerEmergencyConditions() {
        // Low Health Emergency - triggers when HP is critically low
        emergencyHandler.registerCondition(
                new LowHealthEmergency(activityTracker, ctx -> wrapEmergencyTask("LOW_HEALTH_EMERGENCY", createEatFoodTask(ctx)))
        );
        
        // Poison Emergency - triggers when poisoned with low health
        emergencyHandler.registerCondition(
                new PoisonEmergency(activityTracker, ctx -> wrapEmergencyTask("POISON_EMERGENCY", createDrinkAntidoteTask(ctx)))
        );
        
        log.info("Registered {} emergency conditions", emergencyHandler.getConditionCount());
    }
    
    /**
     * Wrap an emergency response task with EmergencyTask to ensure proper cleanup.
     * 
     * @param emergencyId the unique ID of the emergency
     * @param responseTask the task that handles the emergency (or null if no response)
     * @return wrapped emergency task, or null if responseTask is null
     */
    private Task wrapEmergencyTask(String emergencyId, Task responseTask) {
        if (responseTask == null) {
            return null;
        }
        return new EmergencyTask(responseTask, emergencyHandler, emergencyId);
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
        
        // === Stop Status & Communication Components ===
        statusPublisher.stop();
        commandProcessor.stop();
        xpTracker.endSession();
        
        // Unregister status components
        eventBus.unregister(commandProcessor);
        eventBus.unregister(statusPublisher);
        eventBus.unregister(xpTracker);
        
        // Clean up status file
        statusPublisher.deleteStatusFile();
        
        // Save player profile and shutdown its executor
        try {
            playerProfile.shutdown();
            log.info("Player profile saved and executor shut down");
        } catch (Exception e) {
            log.warn("Failed to shutdown player profile: {}", e.getMessage());
        }
        
        // === Shutdown Input Controller Executors ===
        // These have background thread pools that must be cleaned up
        mouseController.shutdown();
        keyboardController.shutdown();
        log.info("Input controller executors shut down");

        // === Unregister Behavioral Components ===
        eventBus.unregister(mouseCameraCoupler);
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
        eventBus.unregister(gameStateServiceProvider.get());

        // Invalidate all cached state
        gameStateServiceProvider.get().invalidateAllCaches();

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
        
        // Start XP tracking session
        xpTracker.startSession();
        
        // Reset predictive hover metrics for new session
        if (predictiveHoverManager != null) {
            predictiveHoverManager.resetMetrics();
            predictiveHoverManager.clearHover();
        }
        
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
        
        // Clear any pending predictive hover
        if (predictiveHoverManager != null) {
            predictiveHoverManager.clearHover();
        }
        
        // End XP tracking session (preserves data)
        xpTracker.endSession();
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
