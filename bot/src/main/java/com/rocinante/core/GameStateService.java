package com.rocinante.core;

import com.rocinante.behavior.AttentionModel;
import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.FatigueModel;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.combat.WeaponDataService;
import com.rocinante.quest.impl.TutorialIsland;
import com.rocinante.status.XpTracker;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.tasks.impl.SettingsTask;
import javax.inject.Provider;
import com.rocinante.data.NpcCombatDataLoader;
import com.rocinante.data.ProjectileDataLoader;
import com.rocinante.state.SlayerState;
import com.rocinante.tasks.impl.skills.slayer.SlayerMaster;
import com.rocinante.tasks.impl.skills.slayer.SlayerUnlock;
import com.rocinante.state.*;
import com.rocinante.state.GrandExchangeStateManager;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.ChatMessageType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.plugins.slayer.SlayerPluginService;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Centralized game state polling and caching service as specified in REQUIREMENTS.md Section 6.1.
 *
 * Single source of truth for all game state queries. Polls client once per game tick and caches
 * values. Modules should never call Client directly - they should use this service instead.
 *
 * <p>Key design principles:
 * <ul>
 *   <li>Uses {@code @Subscribe(priority = -10)} for state updates to run AFTER other plugins</li>
 *   <li>Implements intelligent caching with policy-based invalidation</li>
 *   <li>All getters return immutable snapshot objects</li>
 *   <li>Thread-safe with volatile fields</li>
 * </ul>
 *
 * <p>Performance targets from spec:
 * <ul>
 *   <li>State queries: {@literal <}1ms when cached</li>
 *   <li>Full tick update: {@literal <}5ms</li>
 *   <li>Cache footprint: {@literal <}10MB</li>
 *   <li>Cache hit rate: {@literal >}90%</li>
 * </ul>
 */
@Slf4j
@Singleton
public class GameStateService {

    private final Client client;
    private final ItemManager itemManager;
    private final BankStateManager bankStateManager;
    private final GrandExchangeStateManager grandExchangeStateManager;

    // ========================================================================
    // State Components
    // ========================================================================

    // IronmanState is a @Singleton always provided by Guice - not nullable
    private final com.rocinante.state.IronmanState ironmanState;

    // ========================================================================
    // Behavioral Components - all @Singleton, always present after injection
    // ========================================================================

    private final PlayerProfile playerProfile;
    
    private final FatigueModel fatigueModel;
    
    private final BreakScheduler breakScheduler;
    
    private final AttentionModel attentionModel;
    
    private final XpTracker xpTracker;
    
    // TaskExecutor is set via setter to break circular dependency
    // GameStateService <-> TaskContext <-> TaskExecutor
    @Nullable
    private Provider<TaskExecutor> taskExecutorProvider;

    // ========================================================================
    // Data Services - all @Singleton, always present after injection
    // ========================================================================

    private final WeaponDataService weaponDataService;

    private final NpcCombatDataLoader npcCombatDataLoader;

    private final ProjectileDataLoader projectileDataLoader;

    // SlayerPluginService is set via setter - it's a RuneLite plugin service that
    // may not be available if the Slayer plugin isn't loaded
    @Nullable
    private SlayerPluginService slayerPluginService;

    // ========================================================================
    // Cached State Snapshots
    // ========================================================================

    private final CachedValue<PlayerState> playerStateCache;
    private final CachedValue<InventoryState> inventoryStateCache;
    private final CachedValue<EquipmentState> equipmentStateCache;
    private final CachedValue<WorldState> worldStateCache;
    private final CachedValue<CombatState> combatStateCache;
    private final CachedValue<SlayerState> slayerStateCache;

    // ========================================================================
    // Slayer Caching (reduces DB lookups inside per-tick builds)
    // ========================================================================

    /**
     * Cache of Slayer taskId -> task name (DB lookup is expensive; cache per session).
     */
    private final Map<Integer, String> slayerTaskNameCache = new HashMap<>();

    /**
     * Cache of Slayer areaId -> area name (DB lookup is expensive; cache per session).
     */
    private final Map<Integer, String> slayerAreaNameCache = new HashMap<>();

    /**
     * Last seen blocked-task varplayer values (per varp id) for change detection.
     */
    private final Map<Integer, Integer> blockedVarpValues = new HashMap<>();

    /**
     * Cached blocked task names per varp id to avoid repeated DB lookups.
     */
    private final Map<Integer, String> blockedTaskNamesByVarp = new HashMap<>();

    // ========================================================================
    // State Tracking
    // ========================================================================

    @Getter
    private volatile int currentTick = 0;

    /**
     * Tick when WorldState was last refreshed. Used to coalesce multiple
     * invalidations in the same game tick and avoid rebuilding more than once.
     */
    private volatile int lastWorldRefreshTick = -1;

    @Getter
    private volatile boolean loggedIn = false;
    
    /** Whether behavioral session has been successfully initialized */
    private volatile boolean behaviorsInitialized = false;
    
    /** Whether we've already ensured fixed mode this session */
    private volatile boolean fixedModeEnsured = false;
    
    /**
     * Current interface mode (fixed vs resizable variants).
     * Updated on login and when interface changes.
     */
    @Getter
    private volatile InterfaceMode interfaceMode = InterfaceMode.UNKNOWN;

    // Dirty flags for event-invalidated caches
    private volatile boolean inventoryDirty = true;
    private volatile boolean equipmentDirty = true;
    private volatile boolean statsDirty = true;
    private volatile boolean worldStateDirty = true;

    // Combat state tracking
    private volatile int lastPlayerAttackTick = -1;
    private final Map<Integer, Integer> npcLastAttackTicks = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Widget discovery tracking (prevents log spam)
    private final Set<Integer> discoveredWidgetGroups = new HashSet<>();
    
    // Widget caching - avoids scanning 0-1000 every call
    // This set contains all widget groups we've found visible during this session
    private final Set<Integer> cachedWidgetGroups = new HashSet<>();
    private volatile boolean widgetCacheInitialized = false;

    /**
     * GE price cache to avoid invoking ItemManager pricing on the game thread
     * during WorldState builds. Populated asynchronously.
     */
    private final Map<Integer, Integer> itemPriceCache = new ConcurrentHashMap<>();

    /** Single-thread executor for off-thread price fetches. */
    private final ExecutorService priceExecutor = Executors.newSingleThreadExecutor(
            r -> {
                Thread t = new Thread(r, "rocinante-price-cache");
                t.setDaemon(true);
                return t;
            });
    
    // ========================================================================
    // Combat Message Tracking
    // ========================================================================
    
    /**
     * Tick when "I can't reach that" message was last received.
     * Used by CombatTask to detect unreachable targets and return to home position.
     */
    private volatile int lastCantReachTick = -1;
    
    /**
     * Interface mode enum - determines which widget IDs to use for tabs, etc.
     * Values from RuneLite InterfaceID.java
     */
    public enum InterfaceMode {
        /** Fixed mode (group 548 = TOPLEVEL) */
        FIXED(548),
        /** Resizable classic/stretch (group 161 = TOPLEVEL_OSRS_STRETCH) */
        RESIZABLE_CLASSIC(161),
        /** Resizable modern/bottom line (group 164 = TOPLEVEL_PRE_EOC) */
        RESIZABLE_MODERN(164),
        /** Unknown/not yet detected */
        UNKNOWN(-1);
        
        @Getter
        private final int topLevelGroupId;
        
        InterfaceMode(int topLevelGroupId) {
            this.topLevelGroupId = topLevelGroupId;
        }
        
        public boolean isResizable() {
            return this == RESIZABLE_CLASSIC || this == RESIZABLE_MODERN;
        }
        
        public boolean isFixed() {
            return this == FIXED;
        }
        
        /**
         * Check if this mode has a "safe" viewport (no UI occlusion).
         * Only Fixed mode guarantees the entire viewport is visible.
         */
        public boolean hasSafeViewport() {
            return this == FIXED;
        }
        
        /**
         * Get the fixed viewport dimensions for this mode.
         * In fixed mode, viewport is always at a known position.
         * In resizable modes, use client.getViewport* methods.
         */
        public java.awt.Rectangle getFixedViewportBounds() {
            // Fixed mode viewport: starts at (4, 4) and is 512x334
            // These are the 3D game area bounds in fixed mode
            return this == FIXED ? new java.awt.Rectangle(4, 4, 512, 334) : null;
        }
        
        public static InterfaceMode fromTopLevelId(int id) {
            for (InterfaceMode mode : values()) {
                if (mode.topLevelGroupId == id) {
                    return mode;
                }
            }
            // Log unknown interface ID for debugging
            if (id > 0) {
                // Will be logged at TRACE level to avoid spam
            }
            return UNKNOWN;
        }
    }

    // ========================================================================
    // VarPlayer/Varbit Constants
    // ========================================================================

    /**
     * VarPlayer index for poison status.
     * Positive values = poison damage, negative values = venom damage.
     * 0 = not poisoned/venomed.
     */
    private static final int VARP_POISON = 102;

    /**
     * Varbit for spellbook.
     * 0 = Standard, 1 = Ancient, 2 = Lunar, 3 = Arceuus.
     */
    private static final int VARBIT_SPELLBOOK = 4070;

    /**
     * VarPlayer for special attack energy (0-1000, divide by 10 for percentage).
     */
    private static final int VARP_SPECIAL_ATTACK = 300;

    /**
     * Varbit for multi-combat area.
     * 1 = in multi-combat, 0 = single combat.
     */
    private static final int VARBIT_MULTICOMBAT = 4605;
    
    /**
     * Varbit for resizable stone arrangement.
     * Only relevant when client.isResized() is true.
     * 0 = Resizable Classic (stones on side)
     * 1 = Resizable Modern / Pre-EOC (stones on bottom)
     */
    private static final int VARBIT_RESIZABLE_STONE_ARRANGEMENT = 4607;

    /**
     * VarPlayer for last home teleport usage.
     * Stores minutes since epoch when home teleport was last used.
     * Per RuneLite VarPlayer.LAST_HOME_TELEPORT.
     */
    private static final int VARP_LAST_HOME_TELEPORT = 892;

    /**
     * VarPlayer for last minigame teleport usage.
     * Stores minutes since epoch when minigame/grouping teleport was last used.
     * Per RuneLite VarPlayer.LAST_MINIGAME_TELEPORT.
     */
    private static final int VARP_LAST_MINIGAME_TELEPORT = 888;

    /**
     * Home teleport cooldown duration in minutes.
     * Per RuneLite GameTimer.HOME_TELEPORT.
     */
    private static final int HOME_TELEPORT_COOLDOWN_MINUTES = 30;

    /**
     * Minigame teleport cooldown duration in minutes.
     * Per RuneLite GameTimer.MINIGAME_TELEPORT.
     */
    private static final int MINIGAME_TELEPORT_COOLDOWN_MINUTES = 20;

    @Inject
    public GameStateService(Client client, 
                           ItemManager itemManager, 
                           BankStateManager bankStateManager,
                           GrandExchangeStateManager grandExchangeStateManager,
                           com.rocinante.state.IronmanState ironmanState,
                           PlayerProfile playerProfile,
                           FatigueModel fatigueModel,
                           BreakScheduler breakScheduler,
                           AttentionModel attentionModel,
                           XpTracker xpTracker,
                           WeaponDataService weaponDataService,
                           NpcCombatDataLoader npcCombatDataLoader,
                           ProjectileDataLoader projectileDataLoader) {
        this.client = client;
        this.itemManager = itemManager;
        this.bankStateManager = bankStateManager;
        this.grandExchangeStateManager = grandExchangeStateManager;
        this.ironmanState = ironmanState;
        this.playerProfile = playerProfile;
        this.fatigueModel = fatigueModel;
        this.breakScheduler = breakScheduler;
        this.attentionModel = attentionModel;
        this.xpTracker = xpTracker;
        this.weaponDataService = weaponDataService;
        this.npcCombatDataLoader = npcCombatDataLoader;
        this.projectileDataLoader = projectileDataLoader;

        // Initialize caches with appropriate policies
        this.playerStateCache = new CachedValue<>("PlayerState", CachePolicy.TICK_CACHED);
        this.inventoryStateCache = new CachedValue<>("InventoryState", CachePolicy.EVENT_INVALIDATED);
        this.equipmentStateCache = new CachedValue<>("EquipmentState", CachePolicy.EVENT_INVALIDATED);
        this.worldStateCache = new CachedValue<>("WorldState", CachePolicy.EXPENSIVE_COMPUTED);
        this.combatStateCache = new CachedValue<>("CombatState", CachePolicy.TICK_CACHED);
        this.slayerStateCache = new CachedValue<>("SlayerState", CachePolicy.TICK_CACHED);

        log.info("GameStateService initialized with all behavioral and data components");
    }

    /**
     * Set the TaskExecutor provider.
     * This is done via setter to break circular dependency:
     * GameStateService -> TaskExecutor -> TaskContext -> GameStateService
     *
     * @param taskExecutorProvider the task executor provider
     */
    public void setTaskExecutorProvider(@Nullable Provider<TaskExecutor> taskExecutorProvider) {
        this.taskExecutorProvider = taskExecutorProvider;
    }

    /**
     * Set the SlayerPluginService.
     * This is done via setter because SlayerPluginService is a RuneLite plugin service
     * that may not be available if the Slayer plugin isn't loaded.
     *
     * @param slayerPluginService the slayer plugin service, or null if not available
     */
    public void setSlayerPluginService(@Nullable SlayerPluginService slayerPluginService) {
        this.slayerPluginService = slayerPluginService;
    }

    // ========================================================================
    // Event Handlers
    // ========================================================================

    /**
     * Main tick handler - runs AFTER other plugins have processed the tick.
     * Updates tick-cached state and checks dirty flags for event-invalidated caches.
     */
    @Subscribe(priority = -10)
    public void onGameTick(GameTick event) {
        if (!loggedIn) {
            return;
        }

        currentTick++;
        long startTime = System.nanoTime();
        
        // Retry session initialization if it failed on login (player name wasn't ready)
        if (!behaviorsInitialized) {
            initializeSessionBehaviors();
        }

        // Update IronmanState from varbit (tracks actual account type)
        ironmanState.updateFromVarbit();
        
        // Update interface mode (cheap check, cached result)
        updateInterfaceMode();
        
        // Ensure fixed mode if settings tab is unlocked
        // varp 281: Tutorial progress (0 = non-tutorial, 7+ = past settings step, 1000 = complete)
        ensureFixedModeIfUnlocked();

        // Update tick on state managers for freshness tracking
        bankStateManager.setCurrentTick(currentTick);
        grandExchangeStateManager.setCurrentTick(currentTick);

        // Always refresh tick-cached state
        refreshPlayerState();

        // Refresh event-invalidated caches only if dirty
        if (inventoryDirty) {
            refreshInventoryState();
            inventoryDirty = false;
        }

        if (equipmentDirty) {
            refreshEquipmentState();
            equipmentDirty = false;
        }

        long elapsed = System.nanoTime() - startTime;
        if (elapsed > 5_000_000) { // > 5ms
            log.warn("GameTick state update took {}ms (target <5ms)", elapsed / 1_000_000.0);
        }
    }

    /**
     * Handle game state changes (login/logout).
     * Manages behavioral component session lifecycle.
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState state = event.getGameState();

        if (state == GameState.LOGGED_IN) {
            loggedIn = true;
            log.info("Player logged in - initializing state caches");
            // Mark all caches dirty to force initial population
            inventoryDirty = true;
            equipmentDirty = true;
            statsDirty = true;
            
            // Initialize behavioral components for this session
            initializeSessionBehaviors();
        } else if (state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST) {
            loggedIn = false;
            log.info("Player logged out - resetting state caches");
            
            // End behavioral session
            endSessionBehaviors();
            
            resetAllCaches();
        }
    }
    
    /**
     * Initialize behavioral components when player logs in.
     * Loads player profile and resets behavioral tracking for new session.
     * May be called multiple times (from login event + game tick) until successful.
     */
    private void initializeSessionBehaviors() {
        if (behaviorsInitialized) {
            return; // Already done
        }
        
        // Get player name for profile loading
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            log.debug("Cannot initialize session behaviors yet: local player is null");
            // Will retry on next game tick
            return;
        }
        
        String accountName = localPlayer.getName();
        if (accountName == null || accountName.isEmpty()) {
            log.debug("Cannot initialize session behaviors yet: account name is null");
            // Will retry on next game tick
            return;
        }
        
        log.info("Initializing behavioral session for account: {}", accountName);
        
        // Detect account type for profile generation
        com.rocinante.behavior.AccountType accountType = detectAccountType();
        
        // Initialize PlayerProfile (loads or creates profile)
        playerProfile.initializeForAccount(accountName, accountType);
        
        // Start fatigue tracking
        fatigueModel.onSessionStart();
        
        // Start break scheduling
        breakScheduler.onSessionStart();
        
        // Reset attention model to fresh state
        attentionModel.reset();
        
        // Start XP/stats tracking session
        xpTracker.startSession();
        
        behaviorsInitialized = true;
        log.info("Behavioral session initialized for account type: {}", accountType);
    }
    
    /**
     * Detect the account type to use for profile generation.
     * Uses IronmanState which handles intended vs actual type reconciliation.
     * 
     * @return account type for profile generation
     */
    private com.rocinante.behavior.AccountType detectAccountType() {
        // IronmanState handles intended vs actual type logic
        return ironmanState.getEffectiveType();
    }
    
    /**
     * End behavioral session when player logs out.
     * Saves profile and records session statistics.
     */
    private void endSessionBehaviors() {
        log.info("Ending behavioral session");
        
        // End fatigue session (logs stats)
        fatigueModel.onSessionEnd();
        
        // End break scheduler session
        breakScheduler.onSessionEnd();
        
        // Record logout time and save profile
        playerProfile.recordLogout();
        
        // End XP tracking session
        xpTracker.endSession();
        
        behaviorsInitialized = false;
        fixedModeEnsured = false;
    }

    /**
     * Handle inventory and equipment container changes.
     * Sets dirty flags for event-invalidated caches.
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        int containerId = event.getContainerId();

        if (containerId == InventoryID.INVENTORY.getId()) {
            inventoryDirty = true;
            log.trace("Inventory marked dirty");
        } else if (containerId == InventoryID.EQUIPMENT.getId()) {
            equipmentDirty = true;
            // Invalidate combat state cache too - weapon speed/style may have changed
            combatStateCache.invalidate();
            log.trace("Equipment marked dirty, combat state invalidated");
        }
    }

    /**
     * Handle stat changes (HP, prayer, etc.).
     * Stats are updated every tick, but this helps catch mid-tick changes.
     */
    @Subscribe
    public void onStatChanged(StatChanged event) {
        statsDirty = true;
    }

    /**
     * Handle animation changes to detect player attacks.
     * Per REQUIREMENTS.md Section 10.6.5, we need to record player attack timing.
     */
    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        if (event.getActor() != client.getLocalPlayer()) {
            return;
        }
        
        int animationId = event.getActor().getAnimation();
        if (animationId == -1) {
            return; // Animation ended, not started
        }
        
        // Check if this is an attack animation using WeaponDataService
        int weaponId = getEquippedWeaponId();
        if (weaponDataService.isPlayerAttackAnimation(weaponId, animationId)) {
            recordPlayerAttack();
            log.trace("Player attack recorded, animation {}", animationId);
        }
    }

    /**
     * Handle NPC spawn events - invalidate WorldState.
     */
    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        worldStateDirty = true;
        log.trace("WorldState marked dirty: NPC spawned");
    }

    /**
     * Handle NPC despawn events - invalidate WorldState.
     */
    @Subscribe
    public void onNpcDespawned(NpcDespawned event) {
        worldStateDirty = true;
        log.trace("WorldState marked dirty: NPC despawned");
    }

    /**
     * Handle game object spawn events - invalidate WorldState.
     */
    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event) {
        worldStateDirty = true;
        log.trace("WorldState marked dirty: GameObject spawned");
    }

    /**
     * Handle game object despawn events - invalidate WorldState.
     */
    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event) {
        worldStateDirty = true;
        log.trace("WorldState marked dirty: GameObject despawned");
    }

    /**
     * Handle ground item spawn events - invalidate WorldState.
     */
    @Subscribe
    public void onItemSpawned(ItemSpawned event) {
        worldStateDirty = true;
        log.trace("WorldState marked dirty: Item spawned");
    }

    /**
     * Handle ground item despawn events - invalidate WorldState.
     */
    @Subscribe
    public void onItemDespawned(ItemDespawned event) {
        worldStateDirty = true;
        log.trace("WorldState marked dirty: Item despawned");
    }

    /**
     * Handle projectile events - invalidate WorldState.
     */
    @Subscribe
    public void onProjectileMoved(ProjectileMoved event) {
        // Intentionally not marking world state dirty for projectiles to avoid
        // thrashing the expensive world scan; rely on TTL refresh instead.
    }

    /**
     * Handle graphics object creation - invalidate WorldState.
     */
    @Subscribe
    public void onGraphicsObjectCreated(GraphicsObjectCreated event) {
        // Same as projectile handling: skip dirtying to keep cache effective.
    }

    /**
     * Handle chat messages - track combat-relevant messages.
     */
    @Subscribe
    public void onChatMessage(ChatMessage event) {
        // Check both ENGINE and GAMEMESSAGE - "I can't reach that" comes as ENGINE
        ChatMessageType type = event.getType();
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.ENGINE) {
            return;
        }
        
        String message = event.getMessage();
        
        // "I can't reach that!" - player tried to attack unreachable target
        if (message.contains("can't reach that")) {
            lastCantReachTick = currentTick;
            log.info("Detected 'can't reach that' message at tick {}", currentTick);
        }
    }

    /**
     * Check if "I can't reach that" message was received recently.
     * CombatTask uses this to detect when it needs to return to home position.
     *
     * @param withinTicks how many ticks ago to check (e.g., 5 = last 5 ticks)
     * @return true if message was received within the specified tick window
     */
    public boolean wasCantReachRecent(int withinTicks) {
        return lastCantReachTick >= 0 && (currentTick - lastCantReachTick) <= withinTicks;
    }

    /**
     * Clear the "can't reach" flag. Called after handling the condition.
     */
    public void clearCantReachFlag() {
        lastCantReachTick = -1;
    }

    // ========================================================================
    // State Accessors
    // ========================================================================

    /**
     * Get the current player state snapshot.
     * Returns cached value if valid, otherwise refreshes from client.
     *
     * @return immutable PlayerState snapshot
     */
    public PlayerState getPlayerState() {
        if (!loggedIn) {
            return PlayerState.EMPTY;
        }

        PlayerState cached = playerStateCache.getIfValid(currentTick);
        if (cached != null) {
            return cached;
        }

        return refreshPlayerState();
    }

    /**
     * Get the current inventory state snapshot.
     * Returns cached value if valid, otherwise refreshes from client.
     *
     * @return immutable InventoryState snapshot
     */
    public InventoryState getInventoryState() {
        if (!loggedIn) {
            return InventoryState.EMPTY;
        }

        InventoryState cached = inventoryStateCache.getIfValid(currentTick);
        if (cached != null) {
            return cached;
        }

        return refreshInventoryState();
    }

    /**
     * Get the current equipment state snapshot.
     * Returns cached value if valid, otherwise refreshes from client.
     *
     * @return immutable EquipmentState snapshot
     */
    public EquipmentState getEquipmentState() {
        if (!loggedIn) {
            return EquipmentState.EMPTY;
        }

        EquipmentState cached = equipmentStateCache.getIfValid(currentTick);
        if (cached != null) {
            return cached;
        }

        return refreshEquipmentState();
    }

    /**
     * Get the current world state snapshot.
     * Returns cached value if valid, otherwise refreshes from client.
     * Uses EXPENSIVE_COMPUTED policy (5-tick TTL) as this is an expensive scan.
     *
     * @return immutable WorldState snapshot
     */
    public WorldState getWorldState() {
        if (!loggedIn) {
            return WorldState.EMPTY;
        }

        // Check cache first
        WorldState cached = worldStateCache.getIfValid(currentTick);
        if (cached != null) {
            // If not dirty, or we already refreshed this tick, reuse cached value
            if (!worldStateDirty || lastWorldRefreshTick == currentTick) {
                return cached;
            }
        }

        // Coalesce to at most one rebuild per tick
        if (lastWorldRefreshTick == currentTick && cached != null) {
            return cached;
        }

        return refreshWorldState();
    }

    /**
     * Get the current combat state snapshot.
     * Returns cached value if valid, otherwise refreshes from client.
     * Uses TICK_CACHED policy for real-time combat tracking.
     *
     * @return immutable CombatState snapshot
     */
    public CombatState getCombatState() {
        if (!loggedIn) {
            return CombatState.EMPTY;
        }

        CombatState cached = combatStateCache.getIfValid(currentTick);
        if (cached != null) {
            return cached;
        }

        return refreshCombatState();
    }

    /**
     * Get the current bank state snapshot.
     * Bank state persists across bank interface closures and is loaded from disk on login.
     * Returns UNKNOWN state if the bank has never been opened/captured.
     *
     * @return immutable BankState snapshot
     */
    public BankState getBankState() {
        if (!loggedIn) {
            return BankState.UNKNOWN;
        }

        return bankStateManager.getBankState();
    }

    /**
     * Get the ironman state (account type and restrictions).
     * 
     * @return ironman state, or null if not available
     */
    @Nullable
    public com.rocinante.state.IronmanState getIronmanState() {
        return ironmanState;
    }

    /**
     * Get the current slayer state snapshot.
     *
     * Per REQUIREMENTS.md Section 6.2.7, includes:
     * <ul>
     *   <li>Current task name and location (Konar)</li>
     *   <li>Kill counts (initial, remaining)</li>
     *   <li>Slayer points and streak</li>
     *   <li>Current master</li>
     *   <li>Unlocked rewards</li>
     *   <li>Target NPCs from SlayerPluginService</li>
     * </ul>
     *
     * Uses TICK_CACHED policy for real-time slayer tracking.
     *
     * @return immutable SlayerState snapshot
     */
    public SlayerState getSlayerState() {
        if (!loggedIn) {
            return SlayerState.EMPTY;
        }

        SlayerState cached = slayerStateCache.getIfValid(currentTick);
        if (cached != null) {
            return cached;
        }

        return refreshSlayerState();
    }

    /**
     * Refresh slayer state from client and SlayerPluginService.
     */
    private SlayerState refreshSlayerState() {
        SlayerState state = buildSlayerState();
        slayerStateCache.set(currentTick, state);
        return state;
    }

    /**
     * Build SlayerState from client varbits and SlayerPluginService.
     * Per REQUIREMENTS.md Section 6.2.7 and 11.1.
     */
    private SlayerState buildSlayerState() {
        // Get task info from SlayerPluginService if available
        String taskName = null;
        String taskLocation = null;
        int remainingKills = 0;
        int initialKills = 0;
        java.util.List<NPC> targetNpcs = java.util.Collections.emptyList();

        if (slayerPluginService != null) {
            try {
                // Use SlayerPluginService methods
                taskName = slayerPluginService.getTask();
                taskLocation = slayerPluginService.getTaskLocation();
                initialKills = slayerPluginService.getInitialAmount();
                remainingKills = slayerPluginService.getRemainingAmount();
                targetNpcs = slayerPluginService.getTargets();
            } catch (Exception e) {
                log.debug("Error getting slayer info from plugin service: {}", e.getMessage());
            }
        }

        // Fallback to varbits if plugin service not available or returned empty
        if (taskName == null || taskName.isEmpty()) {
            remainingKills = client.getVarpValue(net.runelite.api.VarPlayer.SLAYER_TASK_SIZE);

            // Fallback task name/location from varplayers with cached DB lookups
            int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
            int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);

            taskName = getCachedSlayerTaskName(taskId);
            taskLocation = getCachedSlayerAreaName(areaId);

            // Initial amount (original assignment) from varp
            if (initialKills == 0) {
                initialKills = client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL);
            }
        }

        // Read slayer varbits for points, streak, master, unlocks
        // Using RuneLite VarbitID constants for accuracy
        int slayerPoints = client.getVarbitValue(VarbitID.SLAYER_POINTS);
        int taskStreak = client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED);
        int wildernessStreak = client.getVarbitValue(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED);
        int masterId = client.getVarbitValue(VarbitID.SLAYER_MASTER);

        SlayerMaster currentMaster = SlayerMaster.fromMasterId(masterId);

        // Read unlocks from varbits
        java.util.Set<SlayerUnlock> unlocks = java.util.EnumSet.noneOf(SlayerUnlock.class);
        for (SlayerUnlock unlock : SlayerUnlock.values()) {
            if (unlock.isUnlocked(client)) {
                unlocks.add(unlock);
            }
        }

        // Read blocked tasks from varplayers
        java.util.Set<String> blockedTasks = readBlockedTasks();

        // Read extended tasks (derived from LONGER_* unlocks)
        java.util.Set<String> extendedTasks = buildExtendedTaskSet(unlocks);

        // Get slayer level
        int slayerLevel = client.getRealSkillLevel(Skill.SLAYER);

        return SlayerState.builder()
                .taskName(taskName)
                .taskLocation(taskLocation)
                .remainingKills(remainingKills)
                .initialKills(initialKills)
                .slayerPoints(slayerPoints)
                .taskStreak(taskStreak)
                .wildernessStreak(wildernessStreak)
                .currentMaster(currentMaster)
                .unlocks(unlocks)
                .blockedTasks(blockedTasks)
                .extendedTasks(extendedTasks)
                .targetNpcs(targetNpcs != null ? targetNpcs : java.util.Collections.emptyList())
                .slayerLevel(slayerLevel)
                .build();
    }

    /**
     * Read blocked slayer tasks from varplayers.
     * 
     * VarPlayers for blocked tasks:
     * - SLAYER_REWARDS_BLOCKED (1096) - first slot
     * - SLAYER_REWARDS_BLOCKED_2 through _13 (4830-4841) - additional slots
     * - SLAYER_REWARDS_BLOCKED_DIARY_1 and _2 (4842-4843) - diary slots
     * 
     * Each varplayer stores a task ID that must be looked up via DBTableID.SlayerTask.
     *
     * @return set of blocked task names (creature names)
     */
    private java.util.Set<String> readBlockedTasks() {
        java.util.Set<String> blocked = new java.util.HashSet<>();

        // All blocked task varplayers - IDs from RuneLite VarPlayerID
        int[] blockedVarplayers = {
            VarPlayerID.SLAYER_REWARDS_BLOCKED,       // 1096 - first slot
            VarPlayerID.SLAYER_REWARDS_BLOCKED_2,     // 4830
            VarPlayerID.SLAYER_REWARDS_BLOCKED_3,     // 4831
            VarPlayerID.SLAYER_REWARDS_BLOCKED_4,     // 4832
            VarPlayerID.SLAYER_REWARDS_BLOCKED_5,     // 4833
            VarPlayerID.SLAYER_REWARDS_BLOCKED_6,     // 4834
            VarPlayerID.SLAYER_REWARDS_BLOCKED_7,     // 4835
            VarPlayerID.SLAYER_REWARDS_BLOCKED_8,     // 4836
            VarPlayerID.SLAYER_REWARDS_BLOCKED_9,     // 4837
            VarPlayerID.SLAYER_REWARDS_BLOCKED_10,    // 4838
            VarPlayerID.SLAYER_REWARDS_BLOCKED_11,    // 4839
            VarPlayerID.SLAYER_REWARDS_BLOCKED_12,    // 4840
            VarPlayerID.SLAYER_REWARDS_BLOCKED_13,    // 4841
            VarPlayerID.SLAYER_REWARDS_BLOCKED_DIARY_1, // 4842
            VarPlayerID.SLAYER_REWARDS_BLOCKED_DIARY_2  // 4843
        };

        for (int varplayerId : blockedVarplayers) {
            try {
                int taskId = client.getVarpValue(varplayerId);

                // If value unchanged and we have a cached name, reuse without DB hit
                Integer lastValue = blockedVarpValues.get(varplayerId);
                if (lastValue != null && lastValue == taskId) {
                    String cachedName = blockedTaskNamesByVarp.get(varplayerId);
                    if (cachedName != null) {
                        blocked.add(cachedName);
                    }
                    continue;
                }

                // Value changed - update cache
                blockedVarpValues.put(varplayerId, taskId);
                if (taskId <= 0) {
                    blockedTaskNamesByVarp.remove(varplayerId);
                    continue;
                }

                String taskNameFromDb = getCachedSlayerTaskName(taskId);
                if (taskNameFromDb != null && !taskNameFromDb.isEmpty()) {
                    blocked.add(taskNameFromDb);
                    blockedTaskNamesByVarp.put(varplayerId, taskNameFromDb);
                    log.trace("Found blocked task ID {} = {}", taskId, taskNameFromDb);
                }
            } catch (Exception e) {
                log.trace("Error reading blocked task varplayer {}: {}", varplayerId, e.getMessage());
            }
        }

        return blocked;
    }

    /**
     * Look up a slayer task name from its task ID using the game's DB table.
     * 
     * Uses DBTableID.SlayerTask.COL_ID to find the row, then reads COL_NAME_UPPERCASE.
     *
     * @param taskId the task ID from varplayer
     * @return the task name, or null if not found
     */
    private String lookupSlayerTaskName(int taskId) {
        // Cache first to avoid repeated DB lookups
        String cached = slayerTaskNameCache.get(taskId);
        if (cached != null) {
            return cached;
        }

        try {
            // Find the DB row for this task ID
            java.util.List<Integer> taskRows = client.getDBRowsByValue(
                DBTableID.SlayerTask.ID,
                DBTableID.SlayerTask.COL_ID,
                0,
                taskId
            );

            if (taskRows == null || taskRows.isEmpty()) {
                log.trace("No DB row found for slayer task ID {}", taskId);
                return null;
            }

            // Get the task name from the first matching row
            int dbRow = taskRows.get(0);
            Object[] nameData = client.getDBTableField(dbRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0);
            
            if (nameData != null && nameData.length > 0 && nameData[0] instanceof String) {
                String name = (String) nameData[0];
                slayerTaskNameCache.put(taskId, name);
                return name;
            }
        } catch (Exception e) {
            log.debug("Error looking up slayer task name for ID {}: {}", taskId, e.getMessage());
        }
        return null;
    }

    /**
     * Build the set of extended (longer) task names from unlocked LONGER_* rewards.
     * 
     * Maps SlayerUnlock extensions to their creature names for easy lookup.
     *
     * @param unlocks the set of unlocked slayer rewards
     * @return set of creature names that have extended tasks enabled
     */
    private java.util.Set<String> buildExtendedTaskSet(java.util.Set<SlayerUnlock> unlocks) {
        java.util.Set<String> extended = new java.util.HashSet<>();

        // Map LONGER_* unlocks to creature names
        // These mappings are based on the unlock display names
        for (SlayerUnlock unlock : unlocks) {
            if (!unlock.isExtension()) {
                continue;
            }

            // Extract creature name from the unlock
            String creatureName = getCreatureNameForExtension(unlock);
            if (creatureName != null) {
                extended.add(creatureName);
            }
        }

        return extended;
    }

    /**
     * Get the creature name for a task extension unlock.
     * 
     * @param unlock the LONGER_* unlock
     * @return the creature name this extension applies to
     */
    private String getCreatureNameForExtension(SlayerUnlock unlock) {
        // Map based on enum name -> creature name
        // These match the task names used in SlayerPluginService
        switch (unlock) {
            case LONGER_ANKOU: return "Ankou";
            case LONGER_SUQAH: return "Suqah";
            case LONGER_BLACK_DRAGONS: return "Black dragons";
            case LONGER_METAL_DRAGONS: return "Metal dragons";
            case LONGER_ABYSSAL_DEMONS: return "Abyssal demons";
            case LONGER_BLACK_DEMONS: return "Black demons";
            case LONGER_GREATER_DEMONS: return "Greater demons";
            case LONGER_BLOODVELD: return "Bloodveld";
            case LONGER_ABERRANT_SPECTRES: return "Aberrant spectres";
            case LONGER_AVIANSIES: return "Aviansies";
            case LONGER_MITHRIL_DRAGONS: return "Mithril dragons";
            case LONGER_CAVE_HORRORS: return "Cave horrors";
            case LONGER_DUST_DEVILS: return "Dust devils";
            case LONGER_SKELETAL_WYVERNS: return "Skeletal wyverns";
            case LONGER_GARGOYLES: return "Gargoyles";
            case LONGER_NECHRYAEL: return "Nechryael";
            case LONGER_CAVE_KRAKEN: return "Cave kraken";
            case LONGER_SPIRITUAL_GWD: return "Spiritual creatures";
            case LONGER_VAMPYRES: return "Vampyres";
            case LONGER_DARK_BEASTS: return "Dark beasts";
            case LONGER_FOSSIL_WYVERNS: return "Fossil island wyverns";
            case LONGER_ADAMANT_DRAGONS: return "Adamant dragons";
            case LONGER_RUNE_DRAGONS: return "Rune dragons";
            case LONGER_SCABARITES: return "Scabarites";
            case LONGER_BASILISKS: return "Basilisks";
            case LONGER_REVENANTS: return "Revenants";
            case LONGER_ARAXYTES: return "Araxytes";
            case LONGER_CUSTODIANS: return "Custodian stalkers";
            default: return null;
        }
    }

    /**
     * Get the BankStateManager for direct access to bank operations.
     *
     * @return the BankStateManager instance
     */
    public BankStateManager getBankStateManager() {
        return bankStateManager;
    }

    /**
     * Get the current Grand Exchange state snapshot.
     * GE state includes offer slots, buy limit tracking, and membership status.
     * Returns UNKNOWN state if the GE has never been observed.
     *
     * @return immutable GrandExchangeState snapshot
     */
    public GrandExchangeState getGrandExchangeState() {
        if (!loggedIn) {
            return GrandExchangeState.UNKNOWN;
        }

        return grandExchangeStateManager.getGeState();
    }

    /**
     * Get the GrandExchangeStateManager for direct access to GE operations.
     *
     * @return the GrandExchangeStateManager instance
     */
    public GrandExchangeStateManager getGrandExchangeStateManager() {
        return grandExchangeStateManager;
    }

    // ========================================================================
    // State Refresh Methods
    // ========================================================================

    /**
     * Update the interface mode from client.
     * Uses client.isResized() and varbit 4607 (RESIZABLE_STONE_ARRANGEMENT) for detection.
     * 
     * Detection logic:
     * - Fixed: !client.isResized()
     * - Resizable Classic: client.isResized() && varbit 4607 == 0 (stones on side)
     * - Resizable Modern: client.isResized() && varbit 4607 == 1 (stones on bottom)
     */
    private void updateInterfaceMode() {
        try {
            boolean resized = client.isResized();
            InterfaceMode newMode;
            
            if (!resized) {
                newMode = InterfaceMode.FIXED;
            } else {
                // Check varbit 4607 for stone arrangement
                int stoneArrangement = client.getVarbitValue(VARBIT_RESIZABLE_STONE_ARRANGEMENT);
                newMode = (stoneArrangement == 1) ? InterfaceMode.RESIZABLE_MODERN : InterfaceMode.RESIZABLE_CLASSIC;
            }
            
            if (newMode != interfaceMode) {
                log.info("Interface mode changed: {} -> {} (resized={}, stoneArrangement={})", 
                        interfaceMode, newMode, resized, 
                        resized ? client.getVarbitValue(VARBIT_RESIZABLE_STONE_ARRANGEMENT) : "n/a");
                interfaceMode = newMode;
            }
        } catch (Exception e) {
            log.trace("Could not detect interface mode: {}", e.getMessage());
        }
    }
    
    /**
     * Ensure fixed mode is set if the settings tab is unlocked.
     * 
     * This handles:
     * - Existing accounts (varp 281 >=7)
     * 
     * varp 281 values:
     * - 3: Tutorial step to open settings
     * - 7+: Past the settings step (settings unlocked)
     * - 1000: Tutorial complete
     */
    private void ensureFixedModeIfUnlocked() {
        if (fixedModeEnsured) {
            return; // Already handled this session
        }
        
        // Already in fixed mode?
        if (interfaceMode == InterfaceMode.FIXED) {
            fixedModeEnsured = true;
            log.debug("Already in fixed mode");
            return;
        }
        
        // Check if settings tab is unlocked
        int tutorialProgress = client.getVarpValue(TutorialIsland.VARP_TUTORIAL_PROGRESS);

        // Any accounts before step 7 will have this enforced by the tutorial completion
        if (tutorialProgress >= 7) {
            log.info("Settings tab unlocked (varp 281 = {}), ensuring fixed mode", tutorialProgress);
            queueFixedModeTask();
            fixedModeEnsured = true;
        }
    }
    
    /**
     * Queue the SettingsTask to change to fixed mode.
     */
    private void queueFixedModeTask() {
        if (taskExecutorProvider == null) {
            log.warn("Cannot queue fixed mode task: TaskExecutor not available");
            return;
        }
        
        try {
            TaskExecutor executor = taskExecutorProvider.get();
            if (executor != null) {
                executor.queueTask(SettingsTask.setFixedMode());
                log.info("Queued SettingsTask to set fixed mode");
            }
        } catch (Exception e) {
            log.warn("Failed to queue fixed mode task: {}", e.getMessage());
        }
    }

    // ========================================================================
    // Viewport Utilities
    // ========================================================================
    
    /**
     * Get the current viewport bounds (the 3D game area).
     * This is where game world clicks should land.
     * 
     * @return viewport rectangle in canvas coordinates
     */
    public java.awt.Rectangle getViewportBounds() {
        // In fixed mode, viewport is at a known position
        if (interfaceMode == InterfaceMode.FIXED) {
            return new java.awt.Rectangle(4, 4, 512, 334);
        }
        
        // In resizable modes, use client API
        int x = client.getViewportXOffset();
        int y = client.getViewportYOffset();
        int w = client.getViewportWidth();
        int h = client.getViewportHeight();
        
        return new java.awt.Rectangle(x, y, w, h);
    }
    
    /**
     * Get the "safe" center zone of the viewport (middle 2/3rds).
     * Humans don't click targets at the very edge of their vision - they 
     * naturally move/rotate to center targets before interacting.
     * 
     * @return the center 2/3 rectangle of the viewport
     */
    public java.awt.Rectangle getViewportSafeZone() {
        java.awt.Rectangle viewport = getViewportBounds();
        
        // Calculate inset: 1/6th on each side = middle 2/3
        int insetX = viewport.width / 6;
        int insetY = viewport.height / 6;
        
        return new java.awt.Rectangle(
                viewport.x + insetX,
                viewport.y + insetY,
                viewport.width - (2 * insetX),
                viewport.height - (2 * insetY)
        );
    }
    
    /**
     * Check if a point is within the "safe zone" (center 2/3) of the viewport.
     * This is the area humans would naturally target - not the edges.
     * 
     * @param x canvas x coordinate
     * @param y canvas y coordinate
     * @return true if point is in safe zone
     */
    public boolean isPointInViewport(int x, int y) {
        java.awt.Rectangle safeZone = getViewportSafeZone();
        return safeZone.contains(x, y);
    }
    
    /**
     * Check if a rectangle's CENTER is within the viewport safe zone.
     * We check the center because the entire clickbox doesn't need to be
     * centered - just close enough that clicking won't look unnatural.
     * 
     * @param bounds the rectangle to check
     * @return true if bounds center is in safe zone
     */
    public boolean isInViewport(java.awt.Rectangle bounds) {
        if (bounds == null) {
            return false;
        }
        // Check if the CENTER of the bounds is in the safe zone
        int centerX = bounds.x + bounds.width / 2;
        int centerY = bounds.y + bounds.height / 2;
        return isPointInViewport(centerX, centerY);
    }
    
    /**
     * Check if a point is anywhere in the viewport (including edges).
     * Use this for "is it visible at all" checks, not for "should we click it".
     * 
     * @param x canvas x coordinate
     * @param y canvas y coordinate
     * @return true if point is in viewport
     */
    public boolean isPointVisibleInViewport(int x, int y) {
        java.awt.Rectangle viewport = getViewportBounds();
        return viewport.contains(x, y);
    }
    
    /**
     * Clamp a point to be within the viewport bounds.
     * 
     * @param x canvas x coordinate
     * @param y canvas y coordinate
     * @return clamped point within viewport
     */
    public java.awt.Point clampToViewport(int x, int y) {
        java.awt.Rectangle viewport = getViewportBounds();
        int clampedX = Math.max(viewport.x, Math.min(x, viewport.x + viewport.width - 1));
        int clampedY = Math.max(viewport.y, Math.min(y, viewport.y + viewport.height - 1));
        return new java.awt.Point(clampedX, clampedY);
    }

    /**
     * Refresh player state from client API.
     */
    private PlayerState refreshPlayerState() {
        PlayerState state = buildPlayerState();
        playerStateCache.set(currentTick, state);
        return state;
    }

    /**
     * Refresh inventory state from client API.
     */
    private InventoryState refreshInventoryState() {
        InventoryState state = buildInventoryState();
        inventoryStateCache.set(currentTick, state);
        return state;
    }

    /**
     * Refresh equipment state from client API.
     */
    private EquipmentState refreshEquipmentState() {
        EquipmentState state = buildEquipmentState();
        equipmentStateCache.set(currentTick, state);
        return state;
    }

    /**
     * Refresh world state from client API.
     */
    private WorldState refreshWorldState() {
        WorldState state = buildWorldState();
        worldStateCache.set(currentTick, state);
        worldStateDirty = false;
        lastWorldRefreshTick = currentTick;
        return state;
    }

    /**
     * Refresh combat state from client API.
     */
    private CombatState refreshCombatState() {
        CombatState state = buildCombatState();
        combatStateCache.set(currentTick, state);
        return state;
    }

    // ========================================================================
    // State Building Methods
    // ========================================================================

    /**
     * Build PlayerState from client API calls.
     */
    private PlayerState buildPlayerState() {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return PlayerState.EMPTY;
        }

        // Position
        WorldPoint worldPos = localPlayer.getWorldLocation();
        LocalPoint localPos = localPlayer.getLocalLocation();

        // Animation and movement
        int animationId = localPlayer.getAnimation();
        boolean isMoving = isPlayerMoving(localPlayer);
        boolean isInteracting = localPlayer.getInteracting() != null;

        // Health and resources
        int currentHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        int maxHp = client.getRealSkillLevel(Skill.HITPOINTS);
        int currentPrayer = client.getBoostedSkillLevel(Skill.PRAYER);
        int maxPrayer = client.getRealSkillLevel(Skill.PRAYER);
        int runEnergy = client.getEnergy() / 100; // Client returns 0-10000, we want 0-100

        // Combat state
        Actor target = localPlayer.getInteracting();
        boolean inCombat = isInCombat(localPlayer, target);
        int targetNpcIndex = getTargetNpcIndex(target);

        // Status effects
        int skullIcon = localPlayer.getSkullIcon();
        int poisonValue = client.getVarpValue(VARP_POISON);
        boolean isPoisoned = poisonValue > 0;
        boolean isVenomed = poisonValue < 0;

        // Spellbook
        int spellbook = client.getVarbitValue(VARBIT_SPELLBOOK);

        // Teleport cooldowns
        int homeTeleportCooldown = calculateTeleportCooldown(VARP_LAST_HOME_TELEPORT, HOME_TELEPORT_COOLDOWN_MINUTES);
        int minigameTeleportCooldown = calculateTeleportCooldown(VARP_LAST_MINIGAME_TELEPORT, MINIGAME_TELEPORT_COOLDOWN_MINUTES);

        // Skill levels - build maps for all skills
        java.util.Map<Skill, Integer> baseSkillLevels = new java.util.EnumMap<>(Skill.class);
        java.util.Map<Skill, Integer> boostedSkillLevels = new java.util.EnumMap<>(Skill.class);
        for (Skill skill : Skill.values()) {
            if (skill != Skill.OVERALL) { // Skip the aggregate "Overall" skill
                baseSkillLevels.put(skill, client.getRealSkillLevel(skill));
                boostedSkillLevels.put(skill, client.getBoostedSkillLevel(skill));
            }
        }

        return PlayerState.builder()
                .worldPosition(worldPos)
                .localPosition(localPos)
                .animationId(animationId)
                .isMoving(isMoving)
                .isInteracting(isInteracting)
                .currentHitpoints(currentHp)
                .maxHitpoints(maxHp)
                .currentPrayer(currentPrayer)
                .maxPrayer(maxPrayer)
                .runEnergy(runEnergy)
                .inCombat(inCombat)
                .targetNpcIndex(targetNpcIndex)
                .skullIcon(skullIcon)
                .isPoisoned(isPoisoned)
                .isVenomed(isVenomed)
                .spellbook(spellbook)
                .homeTeleportCooldownSeconds(homeTeleportCooldown)
                .minigameTeleportCooldownSeconds(minigameTeleportCooldown)
                .baseSkillLevels(java.util.Collections.unmodifiableMap(baseSkillLevels))
                .boostedSkillLevels(java.util.Collections.unmodifiableMap(boostedSkillLevels))
                .build();
    }

    /**
     * Calculate remaining teleport cooldown in seconds.
     * 
     * The varplayer stores the "minutes since epoch" when the teleport was last used.
     * We calculate the remaining cooldown as:
     * remaining = (lastUseMinutes + cooldownMinutes) * 60 - currentEpochSeconds
     *
     * @param varplayerId the varplayer ID storing last teleport time
     * @param cooldownMinutes the cooldown duration in minutes
     * @return remaining cooldown in seconds, or 0 if available
     */
    private int calculateTeleportCooldown(int varplayerId, int cooldownMinutes) {
        try {
            int lastUseMinutes = client.getVarpValue(varplayerId);
            if (lastUseMinutes <= 0) {
                // Never used or invalid value - no cooldown
                return 0;
            }
            
            // Calculate when cooldown ends (in seconds since epoch)
            long cooldownEndsSeconds = (long) (lastUseMinutes + cooldownMinutes) * 60;
            
            // Get current time in seconds since epoch
            long currentSeconds = System.currentTimeMillis() / 1000;
            
            // Calculate remaining seconds
            long remainingSeconds = cooldownEndsSeconds - currentSeconds;
            
            // Return remaining, clamped to 0 if negative (cooldown has expired)
            return (int) Math.max(0, remainingSeconds);
        } catch (Exception e) {
            log.trace("Error calculating teleport cooldown for varp {}: {}", varplayerId, e.getMessage());
            return 0; // Assume available on error
        }
    }

    /**
     * Check if player is moving by comparing pose animation to idle pose.
     */
    private boolean isPlayerMoving(Player player) {
        if (player == null) {
            return false;
        }
        int poseAnimation = player.getPoseAnimation();
        int idlePoseAnimation = player.getIdlePoseAnimation();
        return poseAnimation != idlePoseAnimation;
    }

    /**
     * Determine if player is in combat.
     * True if player is targeting an NPC that is also targeting the player.
     */
    private boolean isInCombat(Player player, Actor target) {
        if (target == null || !(target instanceof NPC)) {
            return false;
        }
        NPC npc = (NPC) target;
        Actor npcTarget = npc.getInteracting();
        return npcTarget == player;
    }

    /**
     * Get the index of the target NPC, or -1 if not targeting an NPC.
     */
    private int getTargetNpcIndex(Actor target) {
        if (target instanceof NPC) {
            return ((NPC) target).getIndex();
        }
        return -1;
    }

    /**
     * Build InventoryState from client API calls.
     */
    private InventoryState buildInventoryState() {
        ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
        if (container == null) {
            return InventoryState.EMPTY;
        }

        Item[] items = container.getItems();
        return new InventoryState(items);
    }

    /**
     * Build EquipmentState from client API calls.
     */
    private EquipmentState buildEquipmentState() {
        ItemContainer container = client.getItemContainer(InventoryID.EQUIPMENT);
        if (container == null) {
            return EquipmentState.EMPTY;
        }

        Item[] items = container.getItems();
        return EquipmentState.fromArray(items);
    }

    /**
     * Build CombatState from client API calls.
     * Per REQUIREMENTS.md Section 6.2.6.
     */
    private CombatState buildCombatState() {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return CombatState.EMPTY;
        }

        // Target information
        Actor target = localPlayer.getInteracting();
        NpcSnapshot targetNpc = null;
        boolean hasTarget = target instanceof NPC;

        if (hasTarget) {
            NPC npc = (NPC) target;
            targetNpc = buildNpcSnapshot(npc, localPlayer);
        }

        // Special attack energy
        int specEnergy = client.getVarpValue(VARP_SPECIAL_ATTACK) / 10;

        // Attack style and weapon speed (from equipped weapon via WeaponDataService)
        int weaponId = getEquippedWeaponId();
        AttackStyle attackStyle = weaponDataService.getAttackStyle(weaponId);
        int weaponSpeed = weaponDataService.getWeaponSpeed(weaponId);

        // Boosted combat stats
        Map<Skill, Integer> boostedStats = new EnumMap<>(Skill.class);
        boostedStats.put(Skill.ATTACK, client.getBoostedSkillLevel(Skill.ATTACK));
        boostedStats.put(Skill.STRENGTH, client.getBoostedSkillLevel(Skill.STRENGTH));
        boostedStats.put(Skill.DEFENCE, client.getBoostedSkillLevel(Skill.DEFENCE));
        boostedStats.put(Skill.RANGED, client.getBoostedSkillLevel(Skill.RANGED));
        boostedStats.put(Skill.MAGIC, client.getBoostedSkillLevel(Skill.MAGIC));
        boostedStats.put(Skill.HITPOINTS, client.getBoostedSkillLevel(Skill.HITPOINTS));
        boostedStats.put(Skill.PRAYER, client.getBoostedSkillLevel(Skill.PRAYER));

        // Poison/venom state
        int poisonValue = client.getVarpValue(VARP_POISON);
        PoisonState poisonState = PoisonState.fromVarpValue(poisonValue, currentTick);

        // Multi-combat detection
        boolean inMultiCombat = client.getVarbitValue(VARBIT_MULTICOMBAT) == 1;

        // Build aggressor list
        List<AggressorInfo> aggressors = buildAggressorList(localPlayer);

        // Detect incoming attacks from projectiles
        AttackStyle incomingStyle = AttackStyle.UNKNOWN;
        int ticksUntilLands = -1;
        Optional<ProjectileSnapshot> incomingProjectile = findIncomingProjectile(localPlayer);
        if (incomingProjectile.isPresent()) {
            int projectileId = incomingProjectile.get().getId();
            // Use ProjectileDataLoader to determine attack style
            incomingStyle = projectileDataLoader.getAttackStyle(projectileId);
            ticksUntilLands = incomingProjectile.get().getTicksUntilImpact(client.getGameCycle());
        }

        // Attack timing
        int ticksSinceAttack = lastPlayerAttackTick >= 0 ? currentTick - lastPlayerAttackTick : -1;
        boolean canAttack = ticksSinceAttack < 0 || ticksSinceAttack >= weaponSpeed;

        return CombatState.builder()
                .targetNpc(targetNpc)
                .targetPresent(hasTarget)
                .specialAttackEnergy(specEnergy)
                .currentAttackStyle(attackStyle)
                .weaponAttackSpeed(weaponSpeed)
                .boostedStats(boostedStats)
                .poisonState(poisonState)
                .aggressiveNpcs(aggressors)
                .inMultiCombat(inMultiCombat)
                .incomingAttackStyle(incomingStyle)
                .ticksUntilAttackLands(ticksUntilLands)
                .lastAttackTick(lastPlayerAttackTick)
                .ticksSinceLastAttack(ticksSinceAttack)
                .canAttack(canAttack)
                .build();
    }

    /**
     * Get the currently equipped weapon ID.
     * @return weapon item ID, or -1 if unarmed
     */
    private int getEquippedWeaponId() {
        ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
        if (equipment == null) {
            return -1;
        }
        Item[] items = equipment.getItems();
        // Weapon slot is index 3
        if (items.length > 3 && items[3] != null) {
            return items[3].getId();
        }
        return -1;
    }

    /**
     * Build a single NPC snapshot for combat state.
     */
    private NpcSnapshot buildNpcSnapshot(NPC npc, Player localPlayer) {
        WorldPoint npcPos = npc.getWorldLocation();
        boolean targetingPlayer = npc.getInteracting() == localPlayer;

        int interactingIndex = -1;
        Actor interacting = npc.getInteracting();
        if (interacting instanceof NPC) {
            interactingIndex = ((NPC) interacting).getIndex();
        } else if (interacting instanceof Player) {
            interactingIndex = -32768 - client.getPlayers().indexOf(interacting);
        }

        return NpcSnapshot.builder()
                .index(npc.getIndex())
                .id(npc.getId())
                .name(npc.getName())
                .combatLevel(npc.getCombatLevel())
                .worldPosition(npcPos)
                .healthRatio(npc.getHealthRatio())
                .healthScale(npc.getHealthScale())
                .animationId(npc.getAnimation())
                .interactingIndex(interactingIndex)
                .targetingPlayer(targetingPlayer)
                .isDead(npc.isDead())
                .size(npc.getComposition() != null ? npc.getComposition().getSize() : 1)
                .build();
    }

    /**
     * Build list of NPCs currently targeting and attacking the player.
     * Per REQUIREMENTS.md Section 6.2.6 and 10.6.5.
     * 
     * IMPORTANT: Only includes NPCs that have ACTUALLY ATTACKED the player,
     * not just NPCs that are "interacting" (which includes dialogue/talking).
     * An NPC is considered attacking if:
     * - It has a recorded attack tick (we've seen it attack us before), OR
     * - It's currently playing an attack animation targeting us
     */
    private List<AggressorInfo> buildAggressorList(Player localPlayer) {
        List<AggressorInfo> aggressors = new ArrayList<>();

        for (NPC npc : client.getNpcs()) {
            if (npc == null || npc.getInteracting() != localPlayer) {
                continue;
            }

            // This NPC is targeting the player - but is it actually ATTACKING?
            int npcIndex = npc.getIndex();
            int npcId = npc.getId();
            Integer lastAttackTick = npcLastAttackTicks.get(npcIndex);
            int ticksUntilNext = -1;

            // Get NPC attack data from data loader
            int npcAttackSpeed = npcCombatDataLoader.getAttackSpeed(npcId);
            AttackStyle npcAttackStyle = npcCombatDataLoader.getAttackStyle(npcId);
            int npcMaxHit = AggressorInfo.estimateMaxHit(npc.getCombatLevel());
            
            // Override max hit if data exists for this NPC
            var npcData = npcCombatDataLoader.getNpcData(npcId);
            if (npcData != null && npcData.maxHit() > 0) {
                npcMaxHit = npcData.maxHit();
            }

            // Estimate attack timing using NPC data
            if (lastAttackTick != null) {
                int ticksSince = currentTick - lastAttackTick;
                ticksUntilNext = Math.max(0, npcAttackSpeed - ticksSince);
            }

            // Detect if currently animating an attack
            int animationId = npc.getAnimation();
            boolean isAttacking = npcCombatDataLoader.isAttackAnimation(npcId, animationId);
            if (!isAttacking && animationId > 0) {
                // Fallback: assume any positive animation could be an attack if not in our data
                isAttacking = true;
            }

            // Update last attack tick if attacking
            if (isAttacking && (lastAttackTick == null || currentTick - lastAttackTick > 2)) {
                npcLastAttackTicks.put(npcIndex, currentTick);
                lastAttackTick = currentTick; // Use updated value
            }

            // ONLY add to aggressors if they've actually attacked us
            // Just "interacting" (dialogue, teaching, etc.) is NOT an attack
            boolean hasAttackedUs = lastAttackTick != null;
            if (!hasAttackedUs) {
                continue; // Skip NPCs that are just talking to us
            }

            aggressors.add(AggressorInfo.builder()
                    .npcIndex(npcIndex)
                    .npcId(npcId)
                    .npcName(npc.getName())
                    .combatLevel(npc.getCombatLevel())
                    .ticksUntilNextAttack(ticksUntilNext)
                    .expectedMaxHit(npcMaxHit)
                    .attackStyle(npcAttackStyle.ordinal())
                    .attackSpeed(npcAttackSpeed)
                    .lastAttackTick(lastAttackTick != null ? lastAttackTick : -1)
                    .isAttacking(isAttacking)
                    .build());
        }

        return aggressors;
    }

    /**
     * Find any projectile targeting the local player.
     */
    private Optional<ProjectileSnapshot> findIncomingProjectile(Player localPlayer) {
        for (Projectile projectile : client.getProjectiles()) {
            if (projectile == null) {
                continue;
            }

            if (projectile.getInteracting() == localPlayer) {
                LocalPoint startLocal = new LocalPoint((int) projectile.getX1(), (int) projectile.getY1());
                LocalPoint endLocal = new LocalPoint((int) projectile.getX(), (int) projectile.getY());
                WorldPoint startWorld = WorldPoint.fromLocal(client, startLocal);
                WorldPoint endWorld = WorldPoint.fromLocal(client, endLocal);

                return Optional.of(ProjectileSnapshot.builder()
                        .id(projectile.getId())
                        .startPosition(startWorld)
                        .endPosition(endWorld)
                        .cycleStart(projectile.getStartCycle())
                        .cycleEnd(projectile.getEndCycle())
                        .targetActorIndex(-1)
                        .targetingPlayer(true)
                        .height((int) projectile.getHeight())
                        .startHeight(projectile.getStartHeight())
                        .endHeight(projectile.getEndHeight())
                        .slope(projectile.getSlope())
                        .build());
            }
        }
        return Optional.empty();
    }

    /**
     * Record when the player attacks (call from animation detection).
     */
    public void recordPlayerAttack() {
        lastPlayerAttackTick = currentTick;
    }

    /**
     * Build WorldState from client API calls.
     * This is an expensive operation - scans all nearby entities.
     */
    private WorldState buildWorldState() {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return WorldState.EMPTY;
        }

        WorldPoint playerPos = localPlayer.getWorldLocation();
        if (playerPos == null) {
            return WorldState.EMPTY;
        }

        long startTime = System.nanoTime();

        // Build all entity lists
        List<NpcSnapshot> npcs = buildNpcSnapshots(playerPos);
        List<GameObjectSnapshot> objects = buildGameObjectSnapshots(playerPos);
        List<PlayerSnapshot> players = buildPlayerSnapshots(localPlayer, playerPos);
        List<GroundItemSnapshot> items = buildGroundItemSnapshots(playerPos);
        List<ProjectileSnapshot> projectiles = buildProjectileSnapshots(localPlayer);
        List<GraphicsObjectSnapshot> graphics = buildGraphicsObjectSnapshots(playerPos);
        Set<Integer> visibleWidgets = buildVisibleWidgetIds();

        long elapsed = System.nanoTime() - startTime;
        if (elapsed > 2_000_000) { // > 2ms
            log.debug("WorldState build took {}ms", elapsed / 1_000_000.0);
        }

        return WorldState.builder()
                .nearbyNpcs(npcs)
                .nearbyObjects(objects)
                .nearbyPlayers(players)
                .groundItems(items)
                .projectiles(projectiles)
                .graphicsObjects(graphics)
                .visibleWidgetIds(visibleWidgets)
                .build();
    }

    /**
     * Build NPC snapshots for all NPCs within range.
     *
     * <p><b>Performance Note:</b> Calls to {@code npc.getComposition()} do not require local caching.
     * RuneLite's client internally caches NPC compositions via its native cache system. This is the
     * standard pattern used by official RuneLite plugins (e.g., NpcIndicatorsPlugin). See
     * REQUIREMENTS.md Section 1.6 Priority 1: always use RuneLite native APIs rather than building
     * custom solutions.
     *
     * <p>This method is called when {@code worldStateDirty} is set (on spawn/despawn events),
     * ensuring data freshness without unnecessary polling. The refresh rate is event-driven,
     * which is the correct approach for maintaining accurate game state.
     */
    private List<NpcSnapshot> buildNpcSnapshots(WorldPoint playerPos) {
        List<NpcSnapshot> snapshots = new ArrayList<>();
        List<NPC> npcs = client.getNpcs();

        for (NPC npc : npcs) {
            if (npc == null) {
                continue;
            }

            WorldPoint npcPos = npc.getWorldLocation();
            if (npcPos == null) {
                continue;
            }

            int distance = playerPos.distanceTo(npcPos);
            if (distance > WorldState.MAX_ENTITY_DISTANCE) {
                continue;
            }

            Player localPlayer = client.getLocalPlayer();
            boolean targetingPlayer = npc.getInteracting() == localPlayer;

            int interactingIndex = -1;
            Actor interacting = npc.getInteracting();
            if (interacting instanceof NPC) {
                interactingIndex = ((NPC) interacting).getIndex();
            } else if (interacting instanceof Player) {
                // Player indices are offset by -32768 in OSRS
                interactingIndex = -32768 - client.getPlayers().indexOf(interacting);
            }

            snapshots.add(NpcSnapshot.builder()
                    .index(npc.getIndex())
                    .id(npc.getId())
                    .name(npc.getName())
                    .combatLevel(npc.getCombatLevel())
                    .worldPosition(npcPos)
                    .healthRatio(npc.getHealthRatio())
                    .healthScale(npc.getHealthScale())
                    .animationId(npc.getAnimation())
                    .interactingIndex(interactingIndex)
                    .targetingPlayer(targetingPlayer)
                    .isDead(npc.isDead())
                    .size(npc.getComposition() != null ? npc.getComposition().getSize() : 1)
                    .build());
        }

        return snapshots;
    }

    /**
     * Build game object snapshots for all objects within range.
     *
     * <p><b>Performance Note:</b> Calls to {@code client.getObjectDefinition()} do not require
     * local caching. RuneLite's client internally caches ObjectComposition data via
     * {@code Client.getObjectCompositionCache()} (a NodeCache). This is the standard pattern
     * used by official RuneLite plugins (e.g., WoodcuttingPlugin, WikiPlugin). See
     * REQUIREMENTS.md Section 1.6 Priority 1: always use RuneLite native APIs.
     *
     * <p>This method scans a ~40x40 tile area around the player (MAX_ENTITY_DISTANCE radius).
     * The scan is only performed when {@code worldStateDirty} is set (on spawn/despawn events),
     * not every game tick. This event-driven refresh ensures data freshness without excessive
     * iteration. The internal cache makes individual getObjectDefinition() calls effectively O(1).
     */
    private List<GameObjectSnapshot> buildGameObjectSnapshots(WorldPoint playerPos) {
        List<GameObjectSnapshot> snapshots = new ArrayList<>();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = client.getPlane();

        int baseX = client.getBaseX();
        int baseY = client.getBaseY();

        // Scan tiles around the player
        int playerSceneX = playerPos.getX() - baseX;
        int playerSceneY = playerPos.getY() - baseY;

        int scanRadius = WorldState.MAX_ENTITY_DISTANCE;
        int minX = Math.max(0, playerSceneX - scanRadius);
        int maxX = Math.min(103, playerSceneX + scanRadius);
        int minY = Math.max(0, playerSceneY - scanRadius);
        int maxY = Math.min(103, playerSceneY + scanRadius);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                Tile tile = tiles[plane][x][y];
                if (tile == null) {
                    continue;
                }

                // Get game objects on this tile
                GameObject[] gameObjects = tile.getGameObjects();
                if (gameObjects != null) {
                    for (GameObject obj : gameObjects) {
                        if (obj == null) {
                            continue;
                        }

                        WorldPoint objPos = obj.getWorldLocation();
                        if (objPos == null) {
                            continue;
                        }

                        int distance = playerPos.distanceTo(objPos);
                        if (distance > WorldState.MAX_ENTITY_DISTANCE) {
                            continue;
                        }

                        ObjectComposition comp = client.getObjectDefinition(obj.getId());
                        List<String> actions = Collections.emptyList();
                        String name = null;
                        int sizeX = 1;
                        int sizeY = 1;
                        boolean impassable = false;

                        if (comp != null) {
                            name = comp.getName();
                            String[] rawActions = comp.getActions();
                            if (rawActions != null) {
                                List<String> actionList = new ArrayList<>();
                                for (String action : rawActions) {
                                    if (action != null) {
                                        actionList.add(action);
                                    }
                                }
                                actions = actionList;
                            }
                            sizeX = comp.getSizeX();
                            sizeY = comp.getSizeY();
                            impassable = comp.getImpostorIds() != null; // Rough heuristic
                        }

                        snapshots.add(GameObjectSnapshot.builder()
                                .id(obj.getId())
                                .worldPosition(objPos)
                                .plane(objPos.getPlane())
                                .name(name)
                                .actions(actions)
                                .sizeX(sizeX)
                                .sizeY(sizeY)
                                .orientation(obj.getOrientation())
                                .impassable(impassable)
                                .build());
                    }
                }
            }
        }

        return snapshots;
    }

    /**
     * Build player snapshots for all other players within range.
     */
    private List<PlayerSnapshot> buildPlayerSnapshots(Player localPlayer, WorldPoint playerPos) {
        List<PlayerSnapshot> snapshots = new ArrayList<>();

        for (Player player : client.getPlayers()) {
            if (player == null || player == localPlayer) {
                continue;
            }

            WorldPoint otherPos = player.getWorldLocation();
            if (otherPos == null) {
                continue;
            }

            int distance = playerPos.distanceTo(otherPos);
            if (distance > WorldState.MAX_ENTITY_DISTANCE) {
                continue;
            }

            int interactingIndex = -1;
            Actor interacting = player.getInteracting();
            if (interacting instanceof NPC) {
                interactingIndex = ((NPC) interacting).getIndex();
            } else if (interacting instanceof Player) {
                interactingIndex = -32768 - client.getPlayers().indexOf(interacting);
            }

            boolean inCombat = interacting != null && player.getAnimation() != -1;

            snapshots.add(PlayerSnapshot.builder()
                    .name(player.getName())
                    .combatLevel(player.getCombatLevel())
                    .worldPosition(otherPos)
                    .skulled(player.getSkullIcon() >= 0)
                    .skullIcon(player.getSkullIcon())
                    .animationId(player.getAnimation())
                    .inCombat(inCombat)
                    .interactingIndex(interactingIndex)
                    .isFriend(player.isFriend())
                    .isClanMember(player.isFriendsChatMember())
                    .overheadIcon(player.getOverheadIcon() != null ? player.getOverheadIcon().ordinal() : -1)
                    .build());
        }

        return snapshots;
    }

    /**
     * Build ground item snapshots for all items within range.
     *
     * <p><b>Performance Note:</b> Calls to {@code itemManager.getItemComposition()} do not require
     * local caching. The ItemManager simply delegates to {@code client.getItemDefinition()}, which
     * is backed by RuneLite's internal composition cache (see ItemManager.java in RuneLite source).
     * This is the standard pattern used by official RuneLite plugins (e.g., GroundItemsPlugin,
     * BankPlugin). See REQUIREMENTS.md Section 1.6 Priority 1: always use RuneLite native APIs.
     *
     * <p>This method is called when {@code worldStateDirty} is set (on item spawn/despawn events),
     * ensuring accurate ground item state without polling. The internal cache makes individual
     * getItemComposition() calls effectively O(1).
     */
    private List<GroundItemSnapshot> buildGroundItemSnapshots(WorldPoint playerPos) {
        List<GroundItemSnapshot> snapshots = new ArrayList<>();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = client.getPlane();

        int baseX = client.getBaseX();
        int baseY = client.getBaseY();

        int playerSceneX = playerPos.getX() - baseX;
        int playerSceneY = playerPos.getY() - baseY;

        int scanRadius = WorldState.MAX_GROUND_ITEM_DISTANCE;
        int minX = Math.max(0, playerSceneX - scanRadius);
        int maxX = Math.min(103, playerSceneX + scanRadius);
        int minY = Math.max(0, playerSceneY - scanRadius);
        int maxY = Math.min(103, playerSceneY + scanRadius);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                Tile tile = tiles[plane][x][y];
                if (tile == null) {
                    continue;
                }

                List<TileItem> groundItems = tile.getGroundItems();
                if (groundItems == null) {
                    continue;
                }

                WorldPoint tilePos = WorldPoint.fromScene(client, x, y, plane);

                for (TileItem item : groundItems) {
                    if (item == null) {
                        continue;
                    }

                    int distance = playerPos.distanceTo(tilePos);
                    if (distance > WorldState.MAX_GROUND_ITEM_DISTANCE) {
                        continue;
                    }

                    // Get item composition for name and prices
                    net.runelite.api.ItemComposition itemComp = itemManager.getItemComposition(item.getId());
                    String name = itemComp != null ? itemComp.getName() : null;
                    int haPrice = itemComp != null ? itemComp.getHaPrice() : 0;

                    // Use cached GE price if present; fetch async otherwise
                    Integer cachedPrice = itemPriceCache.get(item.getId());
                    if (cachedPrice == null) {
                        schedulePriceFetch(item.getId());
                    }
                    int gePrice = cachedPrice != null ? cachedPrice : -1;
                    boolean tradeable = itemComp != null && itemComp.isTradeable();
                    boolean stackable = itemComp != null && itemComp.isStackable();

                    snapshots.add(GroundItemSnapshot.builder()
                            .id(item.getId())
                            .quantity(item.getQuantity())
                            .worldPosition(tilePos)
                            .name(name)
                            .gePrice(gePrice)
                            .haPrice(haPrice)
                            .tradeable(tradeable)
                            .stackable(stackable)
                            .despawnTick(-1) // RuneLite doesn't expose this directly
                            .privateItem(false) // Would need tracking to determine
                            .build());
                }
            }
        }

        return snapshots;
    }

    /**
     * Populate GE price cache off the game thread to keep world scans fast.
     */
    private void schedulePriceFetch(int itemId) {
        if (itemPriceCache.containsKey(itemId)) {
            return;
        }

        priceExecutor.submit(() -> {
            try {
                int price = itemManager.getItemPrice(itemId);
                itemPriceCache.put(itemId, price);
            } catch (Exception e) {
                log.trace("Price fetch failed for item {}: {}", itemId, e.getMessage());
            }
        });
    }

    /**
     * Build projectile snapshots for all active projectiles.
     */
    private List<ProjectileSnapshot> buildProjectileSnapshots(Player localPlayer) {
        List<ProjectileSnapshot> snapshots = new ArrayList<>();

        for (Projectile projectile : client.getProjectiles()) {
            if (projectile == null) {
                continue;
            }

            // Convert local coordinates to world coordinates
            LocalPoint startLocal = new LocalPoint((int) projectile.getX1(), (int) projectile.getY1());
            LocalPoint endLocal = new LocalPoint((int) projectile.getX(), (int) projectile.getY());
            WorldPoint startWorld = WorldPoint.fromLocal(client, startLocal);
            WorldPoint endWorld = WorldPoint.fromLocal(client, endLocal);

            // Check if targeting local player
            int targetIndex = projectile.getInteracting() != null ?
                    (projectile.getInteracting() instanceof NPC ?
                            ((NPC) projectile.getInteracting()).getIndex() : -1) : -1;
            boolean targetingPlayer = projectile.getInteracting() == localPlayer;

            snapshots.add(ProjectileSnapshot.builder()
                    .id(projectile.getId())
                    .startPosition(startWorld)
                    .endPosition(endWorld)
                    .cycleStart(projectile.getStartCycle())
                    .cycleEnd(projectile.getEndCycle())
                    .targetActorIndex(targetIndex)
                    .targetingPlayer(targetingPlayer)
                    .height((int) projectile.getHeight())
                    .startHeight(projectile.getStartHeight())
                    .endHeight(projectile.getEndHeight())
                    .slope(projectile.getSlope())
                    .build());
        }

        return snapshots;
    }

    /**
     * Build graphics object snapshots.
     */
    private List<GraphicsObjectSnapshot> buildGraphicsObjectSnapshots(WorldPoint playerPos) {
        List<GraphicsObjectSnapshot> snapshots = new ArrayList<>();

        for (GraphicsObject gfx : client.getGraphicsObjects()) {
            if (gfx == null) {
                continue;
            }

            LocalPoint localPoint = gfx.getLocation();
            if (localPoint == null) {
                continue;
            }

            WorldPoint worldPos = WorldPoint.fromLocal(client, localPoint);
            if (worldPos == null) {
                continue;
            }

            int distance = playerPos.distanceTo(worldPos);
            if (distance > WorldState.MAX_ENTITY_DISTANCE) {
                continue;
            }

            snapshots.add(GraphicsObjectSnapshot.builder()
                    .id(gfx.getId())
                    .localPosition(localPoint)
                    .worldPosition(worldPos)
                    .frame(0) // Frame not directly accessible
                    .startCycle(gfx.getStartCycle())
                    .height(gfx.getZ())
                    .finished(gfx.finished())
                    .build());
        }

        return snapshots;
    }

    /**
     * Build set of currently visible widget group IDs.
     * Uses caching to avoid scanning 0-1000 every call.
     * On first call (or after logout), scans all widgets and caches them.
     * On subsequent calls, only checks cached widget IDs for visibility.
     * Also logs any unknown widget groups for discovery purposes.
     */
    private Set<Integer> buildVisibleWidgetIds() {
        Set<Integer> visibleIds = new HashSet<>();
        
        // Known common widget groups (for logging purposes and late-appearing widgets)
        Set<Integer> knownWidgets = Set.of(
                149,  // Inventory
                161,  // Prayer tab
                218,  // Spellbook (standard)
                387,  // Equipment
                162,  // Clan tab
                163,  // Account management
                182,  // Logout
                541,  // Resizable pane
                548,  // Fixed pane
                601,  // Spellbook (ancient)
                217,  // Spellbook (lunar)
                216,  // Spellbook (arceuus)
                12,   // Bank
                131,  // Quest list
                84,   // GE
                270,  // Dialogue (NPC)
                219,  // Dialogue (option)
                231,  // Continue dialogue
                116   // Level up
        );

        // First call after login/reset: do full scan to populate cache
        if (!widgetCacheInitialized) {
            log.debug("Initializing widget cache with full scan (0-999)");
            for (int groupId = 0; groupId < 1000; groupId++) {
                net.runelite.api.widgets.Widget widget = client.getWidget(groupId, 0);
                if (widget != null) {
                    visibleIds.add(groupId);
                    cachedWidgetGroups.add(groupId);
                    
                    // Log unknown widgets for discovery
                    if (!knownWidgets.contains(groupId)) {
                        logUnknownWidget(groupId, widget);
                    }
                }
            }
            widgetCacheInitialized = true;
            log.debug("Widget cache initialized with {} groups", cachedWidgetGroups.size());
            return visibleIds;
        }
        
        // Subsequent calls: only check cached widget groups for visibility
        for (int groupId : cachedWidgetGroups) {
            net.runelite.api.widgets.Widget widget = client.getWidget(groupId, 0);
            if (widget != null) {
                visibleIds.add(groupId);
            }
        }
        
        // Also check known widgets that might not have been visible during initial scan
        // This catches widgets like dialogue boxes that appear later
        for (int groupId : knownWidgets) {
            if (!cachedWidgetGroups.contains(groupId)) {
                net.runelite.api.widgets.Widget widget = client.getWidget(groupId, 0);
                if (widget != null) {
                    visibleIds.add(groupId);
                    // Add to cache for future checks
                    cachedWidgetGroups.add(groupId);
                    log.debug("Added widget {} to cache (appeared after init)", groupId);
                }
            }
        }

        return visibleIds;
    }
    
    /**
     * Log details about an unknown widget group for discovery purposes.
     * This helps identify widget IDs for license agreement, name entry, etc.
     */
    private void logUnknownWidget(int groupId, net.runelite.api.widgets.Widget widget) {
        // Only log once per session to avoid spam
        if (discoveredWidgetGroups.contains(groupId)) {
            return;
        }
        discoveredWidgetGroups.add(groupId);
        
        StringBuilder sb = new StringBuilder();
        sb.append("WIDGET DISCOVERY: Group ").append(groupId);
        
        // Try to get text content from the widget and its children
        String text = widget.getText();
        if (text != null && !text.isEmpty()) {
            sb.append(" | Text: '").append(text.substring(0, Math.min(text.length(), 50))).append("'");
        }
        
        // Check for children with text
        net.runelite.api.widgets.Widget[] children = widget.getStaticChildren();
        if (children != null && children.length > 0) {
            sb.append(" | Children: ").append(children.length);
            for (int i = 0; i < Math.min(children.length, 5); i++) {
                net.runelite.api.widgets.Widget child = children[i];
                if (child != null) {
                    String childText = child.getText();
                    if (childText != null && !childText.isEmpty()) {
                        sb.append(" | Child[").append(i).append("]: '")
                          .append(childText.substring(0, Math.min(childText.length(), 30))).append("'");
                    }
                }
            }
        }
        
        // Log bounds for click targeting
        java.awt.Rectangle bounds = widget.getBounds();
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            sb.append(" | Bounds: ").append(bounds.x).append(",").append(bounds.y)
              .append(" ").append(bounds.width).append("x").append(bounds.height);
        }
        
        log.info(sb.toString());
    }

    // ========================================================================
    // Cache Management
    // ========================================================================

    /**
     * Reset all caches. Called on logout.
     */
    private void resetAllCaches() {
        playerStateCache.reset();
        inventoryStateCache.reset();
        equipmentStateCache.reset();
        worldStateCache.reset();
        combatStateCache.reset();
        currentTick = 0;
        inventoryDirty = true;
        equipmentDirty = true;
        statsDirty = true;
        worldStateDirty = true;
        lastPlayerAttackTick = -1;
        npcLastAttackTicks.clear();
        discoveredWidgetGroups.clear(); // Reset so widgets are logged on next login
        cachedWidgetGroups.clear(); // Reset widget cache
        widgetCacheInitialized = false; // Force full scan on next login

        // Slayer caches
        slayerTaskNameCache.clear();
        slayerAreaNameCache.clear();
        blockedVarpValues.clear();
        blockedTaskNamesByVarp.clear();
    }

    // ========================================================================
    // Slayer Helpers (cached lookups)
    // ========================================================================

    /**
    * Get slayer task name using cached DB lookup.
    */
    private String getCachedSlayerTaskName(int taskId) {
        if (taskId <= 0) {
            return null;
        }
        String cached = slayerTaskNameCache.get(taskId);
        if (cached != null) {
            return cached;
        }
        log.debug("Slayer task cache miss for id {}", taskId);
        return lookupSlayerTaskName(taskId);
    }

    /**
    * Get slayer area name using cached DB lookup.
    */
    private String getCachedSlayerAreaName(int areaId) {
        if (areaId <= 0) {
            return null;
        }
        String cached = slayerAreaNameCache.get(areaId);
        if (cached != null) {
            return cached;
        }

        log.debug("Slayer area cache miss for id {}", areaId);

        try {
            java.util.List<Integer> areaRows = client.getDBRowsByValue(
                    DBTableID.SlayerArea.ID,
                    DBTableID.SlayerArea.COL_AREA_ID,
                    0,
                    areaId
            );

            if (areaRows == null || areaRows.isEmpty()) {
                return null;
            }

            Object[] nameData = client.getDBTableField(
                    areaRows.get(0),
                    DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER,
                    0
            );

            if (nameData != null && nameData.length > 0 && nameData[0] instanceof String) {
                String name = (String) nameData[0];
                slayerAreaNameCache.put(areaId, name);
                return name;
            }
        } catch (Exception e) {
            log.debug("Error looking up slayer area name for ID {}: {}", areaId, e.getMessage());
        }

        return null;
    }

    /**
     * Force invalidation of all caches.
     * Useful for debugging or when client state may be inconsistent.
     */
    public void invalidateAllCaches() {
        playerStateCache.invalidate();
        inventoryStateCache.invalidate();
        equipmentStateCache.invalidate();
        worldStateCache.invalidate();
        combatStateCache.invalidate();
        inventoryDirty = true;
        equipmentDirty = true;
        statsDirty = true;
        worldStateDirty = true;
        log.debug("All state caches invalidated");
    }

    // ========================================================================
    // Diagnostics
    // ========================================================================

    /**
     * Get cache hit rate statistics for monitoring.
     *
     * @return formatted string with cache statistics
     */
    public String getCacheStats() {
        return String.format(
                "Cache Stats - PlayerState: %.1f%%, InventoryState: %.1f%%, EquipmentState: %.1f%%, WorldState: %.1f%%, CombatState: %.1f%%, SlayerState: %.1f%%",
                playerStateCache.getHitRate() * 100,
                inventoryStateCache.getHitRate() * 100,
                equipmentStateCache.getHitRate() * 100,
                worldStateCache.getHitRate() * 100,
                combatStateCache.getHitRate() * 100,
                slayerStateCache.getHitRate() * 100
        );
    }

    /**
     * Check if cache hit rates meet the 90% target.
     *
     * @return true if all caches have >90% hit rate (after warmup)
     */
    public boolean areCacheTargetsMet() {
        // Need at least 100 accesses to have meaningful stats
        long totalAccesses = playerStateCache.getHitCount().get() + playerStateCache.getMissCount().get();
        if (totalAccesses < 100) {
            return true; // Skip check during warmup
        }

        return playerStateCache.getHitRate() >= 0.90
                && inventoryStateCache.getHitRate() >= 0.90
                && equipmentStateCache.getHitRate() >= 0.90
                && worldStateCache.getHitRate() >= 0.90
                && combatStateCache.getHitRate() >= 0.90;
    }
}

