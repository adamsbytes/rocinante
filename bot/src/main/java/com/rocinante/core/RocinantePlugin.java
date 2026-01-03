package com.rocinante.core;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Provider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
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
import net.runelite.client.plugins.randomevents.RandomEventPlugin;
import com.rocinante.behavior.BotActivityTracker;
import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.BreakType;
import com.rocinante.behavior.EmergencyHandler;
import com.rocinante.behavior.FatigueModel;
import com.rocinante.behavior.AttentionModel;
import com.rocinante.behavior.RandomEventHandler;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.behavior.emergencies.DeathEmergency;
import com.rocinante.behavior.emergencies.EmergencyTask;
import com.rocinante.behavior.emergencies.LowHealthEmergency;
import com.rocinante.behavior.emergencies.PoisonEmergency;
import com.rocinante.behavior.emergencies.UnderAttackEmergency;
import com.rocinante.state.AggressorInfo;
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
import com.rocinante.tasks.impl.DeathTask;
import com.rocinante.tasks.impl.FleeTask;
import com.rocinante.tasks.impl.InteractNpcTask;
import com.rocinante.tasks.impl.WalkToTask;
import com.rocinante.timing.HumanTimer;
import com.rocinante.quest.QuestExecutor;
import com.rocinante.quest.QuestService;
import com.rocinante.navigation.SceneObstacleCache;
import com.rocinante.navigation.ShortestPathBridge;
import com.rocinante.navigation.SpatialObjectIndex;
import com.rocinante.util.Randomization;
import com.rocinante.behavior.tasks.MicroPauseTask;
import com.rocinante.behavior.tasks.ShortBreakTask;
import com.rocinante.behavior.tasks.LongBreakTask;
import com.rocinante.behavior.tasks.SessionEndTask;
import com.rocinante.behavior.tasks.SessionRitualTask;
import com.rocinante.behavior.idle.IdleHabitSupplier;
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
    name = "Config Extension",
    description = "Extended configuration options",
    tags = {"config", "util"},
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

    @Inject
    @Getter
    private ShortestPathBridge shortestPathBridge;

    // === Performance Optimization Components ===

    @Inject
    @Getter
    private SceneObstacleCache sceneObstacleCache;

    @Inject
    @Getter
    private SpatialObjectIndex spatialObjectIndex;

    @Inject
    @Getter
    private PerformanceMonitor performanceMonitor;

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

    @Inject
    @Getter
    private RandomEventHandler randomEventHandler;

    // Break task factory - created manually in startUp() as it requires runtime wiring
    private Function<BreakType, Task> breakTaskFactory;
    
    // Idle habit supplier - provides human-like idle behaviors when queue is empty
    private IdleHabitSupplier idleHabitSupplier;
    
    // Security monitor - detects debugging/tracing attempts
    private SecurityMonitor securityMonitor;

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

    // Shortest Path waiting state
    private static final String SHORTEST_PATH_CLASS = "shortestpath.ShortestPathPlugin";
    private static final Duration SHORTEST_PATH_TIMEOUT = Duration.ofSeconds(30);
    private Instant shortestPathWaitStart;
    private boolean shortestPathInitialized = false;
    private boolean shortestPathTimeoutReported = false;

    @Override
    protected void startUp() throws Exception
    {
        log.info("Rocinante plugin starting...");
        
        // Start security monitoring (detects debuggers/tracers)
        // On detection, triggers graceful shutdown
        securityMonitor = new SecurityMonitor(() -> {
            log.error("Security check failed - initiating shutdown");
            try {
                shutDown();
            } catch (Exception e) {
                log.error("Shutdown failed: {}", e.getMessage());
            }
        });
        securityMonitor.start();

        // Register GameStateService with the event bus
        // GameStateService needs to receive events for state tracking
        eventBus.register(gameStateServiceProvider.get());

        // === Register Performance Optimization Components ===
        
        // Register SceneObstacleCache - caches obstacle scan results
        eventBus.register(sceneObstacleCache);
        
        // Register SpatialObjectIndex - spatial index for fast object lookups
        eventBus.register(spatialObjectIndex);
        
        // Register PerformanceMonitor - tracks tick timing and cache effectiveness
        eventBus.register(performanceMonitor);
        
        // Wire TaskExecutor to GameStateService (setter injection to break circular dependency)
        gameStateServiceProvider.get().setTaskExecutorProvider(() -> taskExecutor);
        
        // Wire SlayerPluginService to GameStateService (optional - may be null if Slayer plugin not loaded)
        gameStateServiceProvider.get().setSlayerPluginService(slayerPluginService);
        
        // Wire PerformanceMonitor to GameStateService
        gameStateServiceProvider.get().setPerformanceMonitor(performanceMonitor);

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

        // Register RandomEventHandler - handles random event NPC interactions
        eventBus.register(randomEventHandler);
        
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
                    log.info("Session end triggered - creating logout task");
                    return new SessionEndTask(breakScheduler, logoutHandler, playerProfile, taskExecutor);
                default:
                    log.warn("Unknown break type: {}", breakType);
                    return null;
            }
        };
        breakScheduler.setBreakTaskFactory(breakTaskFactory);
        
        // Create and wire up idle habit supplier for human-like idle behaviors
        idleHabitSupplier = new IdleHabitSupplier(playerProfile, randomization, attentionModel, activityTracker);
        taskExecutor.setIdleTaskSupplier(idleHabitSupplier);
        
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

        // Try to initialize Shortest Path immediately (might already be loaded)
        if (tryInitializeShortestPath()) {
            log.info("Shortest Path navigation integration ready");
        } else {
            // Shortest Path not loaded yet, start waiting
            shortestPathWaitStart = Instant.now();
            log.info("Waiting for Shortest Path plugin to load (timeout: {}s)...", SHORTEST_PATH_TIMEOUT.getSeconds());
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
        log.info("  Navigation: {} (waiting for Shortest Path: {})",
                shortestPathInitialized ? "SP-backed" : "pending", !shortestPathInitialized);
    }

    /**
     * Ensure required RuneLite plugins are enabled for full functionality.
     * 
     * Required plugins:
     * - Kourend Library: For Arceuus Library book location predictions
     * - Random Events: For native random event menu stripping/detection
     */
    private void ensureRequiredPluginsEnabled() {
        // List of required plugin class names
        String[] requiredPlugins = {
                "net.runelite.client.plugins.kourendlibrary.KourendLibraryPlugin",
                "net.runelite.client.plugins.randomevents.RandomEventPlugin"
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
     * Try to find and initialize Shortest Path plugin.
     * @return true if Shortest Path was found and initialized, false if not yet loaded
     */
    private boolean tryInitializeShortestPath() {
        if (shortestPathInitialized) {
            return true;
        }
        
        // Find Shortest Path plugin instance
        for (Plugin plugin : pluginManager.getPlugins()) {
            if (plugin.getClass().getName().equals(SHORTEST_PATH_CLASS)) {
                try {
                    shortestPathBridge.initialize(plugin);
                    shortestPathInitialized = true;
                    log.info("Shortest Path navigation integration initialized");
                    return true;
                } catch (Exception e) {
                    log.error("Failed to initialize Shortest Path bridge: {}", e.getMessage(), e);
                    shortestPathTimeoutReported = true; // Don't keep trying
                    return false;
                }
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
     * Quest Helper and Shortest Path are external plugins that may load after our plugin.
     */
    @Subscribe
    public void onExternalPluginsChanged(ExternalPluginsChanged event) {
        // Check Quest Helper
        if (!questHelperInitialized && tryInitializeQuestHelper()) {
            startStatusSystems();
        }
        
        // Check Shortest Path
        if (!shortestPathInitialized && tryInitializeShortestPath()) {
            log.info("Shortest Path navigation integration ready (loaded via external plugins)");
        }
    }
    
    /**
     * Check for Quest Helper and Shortest Path on each game tick during warmup period.
     * Also enforces timeout - logs error once if required plugins not found.
     */
    @Subscribe
    public void onGameTick(GameTick tick) {
        // Check Quest Helper
        if (!questHelperInitialized && !questHelperTimeoutReported) {
            if (tryInitializeQuestHelper()) {
                startStatusSystems();
            } else if (questHelperWaitStart != null) {
                Duration elapsed = Duration.between(questHelperWaitStart, Instant.now());
                if (elapsed.compareTo(QUEST_HELPER_TIMEOUT) > 0) {
                    if (tryInitializeQuestHelper()) {
                        startStatusSystems();
                    } else {
                        questHelperTimeoutReported = true;
                        log.error("Quest Helper plugin not found after {}s timeout. Quest Helper is REQUIRED.", 
                                QUEST_HELPER_TIMEOUT.getSeconds());
                        log.error("Status publishing and quest data will NOT be available!");
                        log.error("Ensure Quest Helper is installed from Plugin Hub and enabled.");
                    }
                }
            }
        }
        
        // Check Shortest Path
        if (!shortestPathInitialized && !shortestPathTimeoutReported) {
            if (tryInitializeShortestPath()) {
                log.info("Shortest Path navigation integration ready");
            } else if (shortestPathWaitStart != null) {
                Duration elapsed = Duration.between(shortestPathWaitStart, Instant.now());
                if (elapsed.compareTo(SHORTEST_PATH_TIMEOUT) > 0) {
                    if (tryInitializeShortestPath()) {
                        log.info("Shortest Path navigation integration ready");
                    } else {
                        shortestPathTimeoutReported = true;
                        log.error("Shortest Path plugin not found after {}s timeout. Shortest Path is REQUIRED.", 
                                SHORTEST_PATH_TIMEOUT.getSeconds());
                        log.error("Navigation will NOT work correctly without Shortest Path!");
                        log.error("Ensure Shortest Path is installed from Plugin Hub and enabled.");
                        // Stop TaskExecutor if running - tasks cannot execute without navigation
                        if (taskExecutor.getEnabled().get()) {
                            log.error("STOPPING TaskExecutor - navigation unavailable!");
                            taskExecutor.stop();
                        }
                    }
                }
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
        
        // Under Attack Emergency - triggers when attacked while skilling
        emergencyHandler.registerCondition(
                new UnderAttackEmergency(activityTracker, this::createUnderAttackResponseTask)
        );
        
        // Death Emergency - triggers when player dies and is at Death's Office
        emergencyHandler.registerCondition(
                new DeathEmergency(ctx -> wrapEmergencyTask("DEATH_EMERGENCY", createDeathRecoveryTask(ctx)))
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

    /**
     * Create a response task for the UnderAttackEmergency.
     * Returns either a fight or flee task based on the response type.
     */
    private Task createUnderAttackResponseTask(TaskContext ctx, AggressorInfo attacker, 
            UnderAttackEmergency.ResponseType response) {
        
        switch (response) {
            case FIGHT:
                return wrapEmergencyTask("UNDER_ATTACK_EMERGENCY", createFightBackTask(ctx, attacker));
            case FLEE:
                return wrapEmergencyTask("UNDER_ATTACK_EMERGENCY", createFleeTask(ctx, attacker));
            case IGNORE:
            default:
                log.debug("Ignoring attack from {} per response decision", attacker.getNpcName());
                return null;
        }
    }

    /**
     * Create a task to fight back against an attacker.
     */
    private Task createFightBackTask(TaskContext ctx, AggressorInfo attacker) {
        log.info("Creating fight-back task for {} (ID: {})", attacker.getNpcName(), attacker.getNpcId());
        
        InteractNpcTask fightTask = new InteractNpcTask(attacker.getNpcId(), "Attack");
        fightTask.setDescription("Fight back: " + attacker.getNpcName());
        fightTask.setSearchRadius(5); // Attacker should be very close
        fightTask.setWaitForIdle(true); // Wait until combat ends or NPC dies
        
        return fightTask;
    }

    /**
     * Create a task to flee from an attacker.
     * If we were walking somewhere (commuting), flees toward that destination.
     * Otherwise, flees away from attacker and returns to task location after.
     */
    private Task createFleeTask(TaskContext ctx, AggressorInfo attacker) {
        // Get the current task location to return to (from activity tracker)
        WorldPoint returnLocation = null;
        if (activityTracker != null) {
            returnLocation = activityTracker.getCurrentTaskLocation();
        }
        
        // Check if we were walking somewhere - if so, flee TOWARD that destination
        WorldPoint fleeTowardDestination = null;
        Task currentTask = taskExecutor.getCurrentTask();
        if (currentTask instanceof WalkToTask) {
            WalkToTask walkTask = (WalkToTask) currentTask;
            fleeTowardDestination = walkTask.getDestination();
            log.info("Creating flee task from {} - fleeing TOWARD walk destination {} (was commuting)", 
                    attacker.getNpcName(), fleeTowardDestination);
        } else {
            log.info("Creating flee task from {} (return to: {})", 
                    attacker.getNpcName(), returnLocation);
        }
        
        return new FleeTask(attacker, returnLocation, fleeTowardDestination);
    }

    /**
     * Create a task to handle death and item recovery.
     * Used by DeathEmergency.
     */
    private Task createDeathRecoveryTask(TaskContext ctx) {
        // Get the return location from activity tracker
        WorldPoint returnLocation = null;
        if (activityTracker != null) {
            returnLocation = activityTracker.getCurrentTaskLocation();
        }
        
        log.info("Creating death recovery task (return to: {})", returnLocation);
        
        DeathTask deathTask = new DeathTask();
        deathTask.withReturnLocation(returnLocation);
        deathTask.withPreferGravestone(false); // Default to Death's Office for simplicity
        
        return deathTask;
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("Rocinante plugin stopping...");
        
        // Stop security monitoring first
        if (securityMonitor != null) {
            securityMonitor.stop();
        }

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
        eventBus.unregister(randomEventHandler);
        
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
        
        // === Unregister Performance Optimization Components ===
        eventBus.unregister(performanceMonitor);
        eventBus.unregister(spatialObjectIndex);
        eventBus.unregister(sceneObstacleCache);

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
        
        // Reset idle habit supplier timers for new session
        if (idleHabitSupplier != null) {
            idleHabitSupplier.reset();
        }
        
        // Start break scheduler session tracking
        breakScheduler.onSessionStart();
        
        // Queue session rituals if this is a fresh session (>15 min since last logout)
        // Per REQUIREMENTS.md Section 3.5.1: 2-4 account-specific actions at session start
        if (playerProfile.isFreshSession()) {
            log.info("Fresh session detected - queuing session rituals");
            SessionRitualTask ritualTask = new SessionRitualTask(playerProfile, randomization, humanTimer);
            taskExecutor.queueTask(ritualTask, TaskPriority.BEHAVIORAL);
        } else {
            log.debug("Resuming session - skipping rituals (not a fresh session)");
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
