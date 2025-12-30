package com.rocinante.tasks;

import com.rocinante.behavior.AccountType;
import com.rocinante.behavior.ActionSequencer;
import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.InefficiencyInjector;
import com.rocinante.behavior.LogoutHandler;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.combat.CombatManager;
import com.rocinante.combat.FoodManager;
import com.rocinante.combat.GearSwitcher;
import com.rocinante.combat.TargetSelector;
import com.rocinante.core.GameStateService;
import com.rocinante.input.CameraController;
import com.rocinante.input.GroundItemClickHelper;
import com.rocinante.input.InventoryClickHelper;
import com.rocinante.input.MenuHelper;
import com.rocinante.input.MouseCameraCoupler;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.input.SafeClickExecutor;
import com.rocinante.input.WidgetClickHelper;
import com.rocinante.navigation.ResourceAwareness;
import com.rocinante.agility.AgilityCourseRepository;
import com.rocinante.progression.UnlockTracker;
import com.rocinante.puzzle.PuzzleSolverRegistry;
import com.rocinante.state.SlayerState;
import com.rocinante.state.BankState;
import com.rocinante.state.CombatState;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.GrandExchangeState;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared context passed to all tasks during execution.
 *
 * Per REQUIREMENTS.md Section 5.2, TaskContext provides:
 * <ul>
 *   <li>Read-only access to PlayerState, InventoryState, EquipmentState, WorldState, CombatState, SlayerState</li>
 *   <li>Access to RobotMouseController and RobotKeyboardController</li>
 *   <li>Access to HumanTimer for delay scheduling</li>
 *   <li>Mutable taskVariables map for passing data between subtasks</li>
 *   <li>abortTask() method for immediate task termination</li>
 * </ul>
 *
 * <p>Note: QuestState will be added in later phases as that state class is implemented.
 */
@Slf4j
public class TaskContext {

    // ========================================================================
    // Core Services
    // ========================================================================

    @Getter
    private final Client client;

    /**
     * Provider for GameStateService to break circular dependency.
     * Use getGameStateService() to access.
     */
    private final Provider<GameStateService> gameStateServiceProvider;
    
    /**
     * Get the GameStateService instance.
     * Uses a Provider internally to break circular dependency with TaskExecutor.
     */
    public GameStateService getGameStateService() {
        return gameStateServiceProvider.get();
    }

    @Getter
    private final RobotMouseController mouseController;

    @Getter
    private final RobotKeyboardController keyboardController;

    @Getter
    private final HumanTimer humanTimer;

    // ========================================================================
    // Combat System
    // ========================================================================

    @Getter
    @Nullable
    private final TargetSelector targetSelector;

    @Getter
    @Nullable
    private final CombatManager combatManager;

    @Getter
    @Nullable
    private final GearSwitcher gearSwitcher;

    @Getter
    @Nullable
    private final FoodManager foodManager;

    @Getter
    @Nullable
    private final InventoryClickHelper inventoryClickHelper;

    @Getter
    @Nullable
    private final GroundItemClickHelper groundItemClickHelper;

    @Getter
    @Nullable
    private final WidgetClickHelper widgetClickHelper;

    @Getter
    @Nullable
    private final MenuHelper menuHelper;

    @Getter
    @Nullable
    private final SafeClickExecutor safeClickExecutor;

    // ========================================================================
    // Progression System
    // ========================================================================

    @Getter
    @Nullable
    private final UnlockTracker unlockTracker;

    @Getter
    @Nullable
    private final AgilityCourseRepository agilityCourseRepository;

    // ========================================================================
    // Navigation System
    // ========================================================================

    @Getter
    @Nullable
    private final com.rocinante.navigation.PathFinder pathFinder;

    @Getter
    @Nullable
    private final com.rocinante.navigation.WebWalker webWalker;

    @Getter
    @Nullable
    private final com.rocinante.navigation.ObstacleHandler obstacleHandler;

    @Getter
    @Nullable
    private final com.rocinante.navigation.PlaneTransitionHandler planeTransitionHandler;

    // ========================================================================
    // Behavioral System
    // ========================================================================

    @Getter
    @Nullable
    private final PlayerProfile playerProfile;

    @Getter
    @Nullable
    private final CameraController cameraController;

    @Getter
    @Nullable
    private final MouseCameraCoupler mouseCameraCoupler;

    @Getter
    @Nullable
    private final ActionSequencer actionSequencer;

    @Getter
    @Nullable
    private final InefficiencyInjector inefficiencyInjector;

    @Getter
    @Nullable
    private final LogoutHandler logoutHandler;
    
    @Getter
    @Nullable
    private final BreakScheduler breakScheduler;

    @Getter
    @Nullable
    private final Randomization randomization;

    // ========================================================================
    // Puzzle System
    // ========================================================================

    @Getter
    @Nullable
    private final PuzzleSolverRegistry puzzleSolverRegistry;

    // ========================================================================
    // RuneLite Plugin References
    // ========================================================================

    // ========================================================================
    // Task Variables
    // ========================================================================

    /**
     * Mutable map for passing data between tasks and subtasks.
     * Thread-safe to support potential parallel task execution.
     */
    private final Map<String, Object> taskVariables = new ConcurrentHashMap<>();

    // ========================================================================
    // Abort Flag
    // ========================================================================

    /**
     * Flag indicating the current task should abort.
     */
    private volatile boolean abortRequested = false;

    /**
     * Reason for abort request.
     */
    @Getter
    private volatile String abortReason;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public TaskContext(
            Client client,
            Provider<GameStateService> gameStateServiceProvider,
            RobotMouseController mouseController,
            RobotKeyboardController keyboardController,
            HumanTimer humanTimer,
            @Nullable TargetSelector targetSelector,
            @Nullable CombatManager combatManager,
            @Nullable GearSwitcher gearSwitcher,
            @Nullable FoodManager foodManager,
            @Nullable InventoryClickHelper inventoryClickHelper,
            @Nullable GroundItemClickHelper groundItemClickHelper,
            @Nullable WidgetClickHelper widgetClickHelper,
            @Nullable MenuHelper menuHelper,
            @Nullable SafeClickExecutor safeClickExecutor,
            @Nullable UnlockTracker unlockTracker,
            @Nullable AgilityCourseRepository agilityCourseRepository,
            @Nullable PlayerProfile playerProfile,
            @Nullable PuzzleSolverRegistry puzzleSolverRegistry,
            @Nullable CameraController cameraController,
            @Nullable MouseCameraCoupler mouseCameraCoupler,
            @Nullable ActionSequencer actionSequencer,
            @Nullable InefficiencyInjector inefficiencyInjector,
            @Nullable LogoutHandler logoutHandler,
            @Nullable BreakScheduler breakScheduler,
            @Nullable Randomization randomization,
            @Nullable com.rocinante.navigation.PathFinder pathFinder,
            @Nullable com.rocinante.navigation.WebWalker webWalker,
            @Nullable com.rocinante.navigation.ObstacleHandler obstacleHandler,
            @Nullable com.rocinante.navigation.PlaneTransitionHandler planeTransitionHandler) {
        this.client = client;
        this.gameStateServiceProvider = gameStateServiceProvider;
        this.mouseController = mouseController;
        this.keyboardController = keyboardController;
        this.humanTimer = humanTimer;
        this.targetSelector = targetSelector;
        this.combatManager = combatManager;
        this.gearSwitcher = gearSwitcher;
        this.foodManager = foodManager;
        this.inventoryClickHelper = inventoryClickHelper;
        this.groundItemClickHelper = groundItemClickHelper;
        this.widgetClickHelper = widgetClickHelper;
        this.menuHelper = menuHelper;
        this.safeClickExecutor = safeClickExecutor;
        this.unlockTracker = unlockTracker;
        this.agilityCourseRepository = agilityCourseRepository;
        this.playerProfile = playerProfile;
        this.puzzleSolverRegistry = puzzleSolverRegistry;
        this.cameraController = cameraController;
        this.mouseCameraCoupler = mouseCameraCoupler;
        this.actionSequencer = actionSequencer;
        this.inefficiencyInjector = inefficiencyInjector;
        this.logoutHandler = logoutHandler;
        this.breakScheduler = breakScheduler;
        this.randomization = randomization;
        this.pathFinder = pathFinder;
        this.webWalker = webWalker;
        this.obstacleHandler = obstacleHandler;
        this.planeTransitionHandler = planeTransitionHandler;
        log.debug("TaskContext created");
    }

    /**
     * Constructor for TaskContext without click helpers.
     * Used for testing or when gear switching is not needed.
     */
    public TaskContext(
            Client client,
            GameStateService gameStateService,
            RobotMouseController mouseController,
            RobotKeyboardController keyboardController,
            HumanTimer humanTimer,
            @Nullable TargetSelector targetSelector,
            @Nullable CombatManager combatManager) {
        this(client, () -> gameStateService, mouseController, keyboardController, humanTimer, 
                targetSelector, combatManager, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Constructor for basic TaskContext without combat system.
     * Used for testing or non-combat tasks.
     */
    public TaskContext(
            Client client,
            GameStateService gameStateService,
            RobotMouseController mouseController,
            RobotKeyboardController keyboardController,
            HumanTimer humanTimer) {
        this(client, () -> gameStateService, mouseController, keyboardController, humanTimer, 
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Testing constructor with Randomization support.
     */
    public TaskContext(
            Client client,
            GameStateService gameStateService,
            RobotMouseController mouseController,
            RobotKeyboardController keyboardController,
            HumanTimer humanTimer,
            Randomization randomization) {
        this(client, () -> gameStateService, mouseController, keyboardController, humanTimer, 
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, randomization, null, null, null, null);
    }

    // ========================================================================
    // State Accessors (Read-Only Snapshots)
    // ========================================================================

    /**
     * Get the current player state snapshot.
     *
     * @return immutable PlayerState
     */
    public PlayerState getPlayerState() {
        return gameStateServiceProvider.get().getPlayerState();
    }

    /**
     * Get the current inventory state snapshot.
     *
     * @return immutable InventoryState
     */
    public InventoryState getInventoryState() {
        return gameStateServiceProvider.get().getInventoryState();
    }

    /**
     * Get the current equipment state snapshot.
     *
     * @return immutable EquipmentState
     */
    public EquipmentState getEquipmentState() {
        return gameStateServiceProvider.get().getEquipmentState();
    }

    /**
     * Get the current world state snapshot.
     * Contains nearby NPCs, objects, players, ground items, projectiles, and graphics objects.
     *
     * @return immutable WorldState
     */
    public WorldState getWorldState() {
        return gameStateServiceProvider.get().getWorldState();
    }

    /**
     * Get the current combat state snapshot.
     * Contains target info, special attack energy, poison/venom status, and aggressor tracking.
     *
     * @return immutable CombatState
     */
    public CombatState getCombatState() {
        return gameStateServiceProvider.get().getCombatState();
    }

    /**
     * Get the current bank state snapshot.
     * Bank state persists across bank interface closures and is loaded from disk on login.
     * Returns UNKNOWN state if the bank has never been opened/captured.
     *
     * @return immutable BankState
     */
    public BankState getBankState() {
        return gameStateServiceProvider.get().getBankState();
    }

    /**
     * Get the current Grand Exchange state snapshot.
     * GE state includes offer slots, buy limit tracking, and membership status.
     * Returns UNKNOWN state if the GE has never been observed.
     *
     * @return immutable GrandExchangeState
     */
    public GrandExchangeState getGrandExchangeState() {
        return gameStateServiceProvider.get().getGrandExchangeState();
    }

    /**
     * Get the ironman state (account type and restrictions).
     *
     * @return the ironman state, or null if not available
     */
    @javax.annotation.Nullable
    public com.rocinante.state.IronmanState getIronmanState() {
        return gameStateServiceProvider.get().getIronmanState();
    }

    /**
     * Get the current slayer state snapshot.
     * Contains task info, points, streak, unlocks, and target NPCs.
     *
     * @return immutable SlayerState
     */
    public SlayerState getSlayerState() {
        return gameStateServiceProvider.get().getSlayerState();
    }

    // ========================================================================
    // Navigation Resource Awareness
    // ========================================================================

    /**
     * Get resource awareness for navigation cost calculations.
     * 
     * <p>ResourceAwareness models the player's current resource state for pathfinding:
     * <ul>
     *   <li>Account type affects teleport cost penalties (HCIM never uses law runes)</li>
     *   <li>Law rune count affects teleport willingness</li>
     *   <li>Gold amount affects charter ship/toll gate preferences</li>
     *   <li>Fairy ring/spirit tree access enables free travel bonuses</li>
     * </ul>
     * 
     * <p>This is computed fresh each call from current game state, ensuring navigation
     * decisions reflect the player's actual resources.
     *
     * @return ResourceAwareness computed from current state, or null if state unavailable
     */
    @Nullable
    public ResourceAwareness getResourceAwareness() {
        // Get account type
        com.rocinante.state.IronmanState ironmanState = getIronmanState();
        AccountType accountType = ironmanState.getEffectiveType();
        
        // Get inventory and equipment state
        InventoryState inventory = getInventoryState();
        EquipmentState equipment = getEquipmentState();
        
        if (inventory == null) {
            // Return default for account type if inventory unavailable
            return ResourceAwareness.forAccountType(accountType);
        }
        
        // Check for fairy ring access
        boolean hasFairyRingAccess = checkFairyRingAccess(inventory, equipment);
        
        // Spirit tree access requires Tree Gnome Village quest completion
        // Individual destination requirements (Corsair Curse, Song of the Elves, etc.)
        // are handled by the navigation graph edge requirements
        boolean hasSpiritTreeAccess = unlockTracker.isQuestCompleted(net.runelite.api.Quest.TREE_GNOME_VILLAGE);
        
        return ResourceAwareness.fromGameState(
                accountType,
                inventory,
                equipment,
                hasFairyRingAccess,
                hasSpiritTreeAccess
        );
    }
    
    /**
     * Check if player has fairy ring access based on having a dramen/lunar staff.
     * 
     * <p>Full fairy ring access also requires Fairytale II - Cure a Queen started,
     * but we simplify to just checking for the staff since the quest check would
     * be done by the edge requirements in the navigation graph.
     *
     * @param inventory the player's inventory
     * @param equipment the player's equipment
     * @return true if player has staff required for fairy rings
     */
    private boolean checkFairyRingAccess(InventoryState inventory, @Nullable EquipmentState equipment) {
        // Dramen staff and Lunar staff item IDs
        final int DRAMEN_STAFF = 772;
        final int LUNAR_STAFF = 9084;
        
        // Check inventory
        if (inventory.hasItem(DRAMEN_STAFF) || inventory.hasItem(LUNAR_STAFF)) {
            return true;
        }
        
        // Check equipment
        if (equipment != null) {
            if (equipment.hasEquipped(DRAMEN_STAFF) || equipment.hasEquipped(LUNAR_STAFF)) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Get the current game tick count.
     *
     * @return the current tick
     */
    public int getCurrentTick() {
        return gameStateServiceProvider.get().getCurrentTick();
    }

    /**
     * Check if the player is logged in.
     *
     * @return true if logged in
     */
    public boolean isLoggedIn() {
        return gameStateServiceProvider.get().isLoggedIn();
    }

    // ========================================================================
    // Task Variables
    // ========================================================================

    /**
     * Store a variable for use by other tasks.
     *
     * @param key   the variable key
     * @param value the value to store
     */
    public void setVariable(String key, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("Variable key cannot be null");
        }
        if (value == null) {
            taskVariables.remove(key);
        } else {
            taskVariables.put(key, value);
        }
    }

    /**
     * Get a stored variable.
     *
     * @param key the variable key
     * @return Optional containing the value, or empty if not found
     */
    public Optional<Object> getVariable(String key) {
        return Optional.ofNullable(taskVariables.get(key));
    }

    /**
     * Get a stored variable with type casting.
     *
     * @param key  the variable key
     * @param type the expected type class
     * @param <T>  the expected type
     * @return Optional containing the typed value, or empty if not found or wrong type
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getVariable(String key, Class<T> type) {
        Object value = taskVariables.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (type.isInstance(value)) {
            return Optional.of((T) value);
        }
        log.warn("Variable '{}' is not of type {}: actual type is {}",
                key, type.getSimpleName(), value.getClass().getSimpleName());
        return Optional.empty();
    }

    /**
     * Get a stored variable with a default value.
     *
     * @param key          the variable key
     * @param defaultValue the default if not found
     * @param <T>          the value type
     * @return the stored value or default
     */
    @SuppressWarnings("unchecked")
    public <T> T getVariableOrDefault(String key, T defaultValue) {
        Object value = taskVariables.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    /**
     * Check if a variable exists.
     *
     * @param key the variable key
     * @return true if the variable exists
     */
    public boolean hasVariable(String key) {
        return taskVariables.containsKey(key);
    }

    /**
     * Remove a variable.
     *
     * @param key the variable key
     * @return the removed value, or null if not found
     */
    public Object removeVariable(String key) {
        return taskVariables.remove(key);
    }

    /**
     * Clear all task variables.
     */
    public void clearVariables() {
        taskVariables.clear();
    }

    /**
     * Get the number of stored variables.
     *
     * @return variable count
     */
    public int getVariableCount() {
        return taskVariables.size();
    }

    // ========================================================================
    // Abort Control
    // ========================================================================

    /**
     * Request immediate termination of the current task.
     * Called by tasks when they need to abort execution.
     */
    public void abortTask() {
        abortTask("Abort requested by task");
    }

    /**
     * Request immediate termination with a reason.
     *
     * @param reason the abort reason
     */
    public void abortTask(String reason) {
        this.abortRequested = true;
        this.abortReason = reason;
        log.info("Task abort requested: {}", reason);
    }

    /**
     * Check if an abort has been requested.
     *
     * @return true if abort was requested
     */
    public boolean isAbortRequested() {
        return abortRequested;
    }

    /**
     * Clear the abort flag.
     * Called by TaskExecutor after handling the abort.
     */
    public void clearAbort() {
        this.abortRequested = false;
        this.abortReason = null;
    }

    // ========================================================================
    // Action Recording
    // ========================================================================

    /**
     * Record an action for fatigue and break tracking.
     * Should be called after each significant action (click, interaction, movement).
     * 
     * Integrates with behavioral anti-detection:
     * - Increments action count for micro-pause scheduling
     * - Accumulates fatigue in FatigueModel
     */
    public void recordAction() {
        if (breakScheduler != null) {
            breakScheduler.recordAction();
        }
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Create a snapshot of the current context state for logging/debugging.
     *
     * @return summary string
     */
    public String getSummary() {
        PlayerState player = getPlayerState();
        InventoryState inventory = getInventoryState();

        return String.format(
                "TaskContext[loggedIn=%s, tick=%d, pos=%s, hp=%d/%d, inv=%d/28, vars=%d]",
                isLoggedIn(),
                getCurrentTick(),
                player.getWorldPosition(),
                player.getCurrentHitpoints(),
                player.getMaxHitpoints(),
                inventory.getUsedSlots(),
                taskVariables.size()
        );
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

