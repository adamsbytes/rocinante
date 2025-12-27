package com.rocinante.core;

import com.rocinante.behavior.AttentionModel;
import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.FatigueModel;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.state.*;
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
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

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

    // ========================================================================
    // State Components
    // ========================================================================

    @Nullable
    private final com.rocinante.state.IronmanState ironmanState;

    // ========================================================================
    // Behavioral Components
    // ========================================================================

    @Nullable
    private final PlayerProfile playerProfile;
    
    @Nullable
    private final FatigueModel fatigueModel;
    
    @Nullable
    private final BreakScheduler breakScheduler;
    
    @Nullable
    private final AttentionModel attentionModel;

    // ========================================================================
    // Cached State Snapshots
    // ========================================================================

    private final CachedValue<PlayerState> playerStateCache;
    private final CachedValue<InventoryState> inventoryStateCache;
    private final CachedValue<EquipmentState> equipmentStateCache;
    private final CachedValue<WorldState> worldStateCache;
    private final CachedValue<CombatState> combatStateCache;

    // ========================================================================
    // State Tracking
    // ========================================================================

    @Getter
    private volatile int currentTick = 0;

    @Getter
    private volatile boolean loggedIn = false;

    // Dirty flags for event-invalidated caches
    private volatile boolean inventoryDirty = true;
    private volatile boolean equipmentDirty = true;
    private volatile boolean statsDirty = true;
    private volatile boolean worldStateDirty = true;

    // Combat state tracking
    private volatile int lastPlayerAttackTick = -1;
    private volatile Map<Integer, Integer> npcLastAttackTicks = new HashMap<>();
    
    // Widget discovery tracking (prevents log spam)
    private final Set<Integer> discoveredWidgetGroups = new HashSet<>();

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

    @Inject
    public GameStateService(Client client, 
                           ItemManager itemManager, 
                           BankStateManager bankStateManager,
                           @Nullable com.rocinante.state.IronmanState ironmanState,
                           @Nullable PlayerProfile playerProfile,
                           @Nullable FatigueModel fatigueModel,
                           @Nullable BreakScheduler breakScheduler,
                           @Nullable AttentionModel attentionModel) {
        this.client = client;
        this.itemManager = itemManager;
        this.bankStateManager = bankStateManager;
        this.ironmanState = ironmanState;
        this.playerProfile = playerProfile;
        this.fatigueModel = fatigueModel;
        this.breakScheduler = breakScheduler;
        this.attentionModel = attentionModel;

        // Initialize caches with appropriate policies
        this.playerStateCache = new CachedValue<>("PlayerState", CachePolicy.TICK_CACHED);
        this.inventoryStateCache = new CachedValue<>("InventoryState", CachePolicy.EVENT_INVALIDATED);
        this.equipmentStateCache = new CachedValue<>("EquipmentState", CachePolicy.EVENT_INVALIDATED);
        this.worldStateCache = new CachedValue<>("WorldState", CachePolicy.EXPENSIVE_COMPUTED);
        this.combatStateCache = new CachedValue<>("CombatState", CachePolicy.TICK_CACHED);

        log.info("GameStateService initialized (behavioral components: {})", 
                playerProfile != null && fatigueModel != null && breakScheduler != null && attentionModel != null);
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

        // Update IronmanState from varbit (tracks actual account type)
        if (ironmanState != null) {
            ironmanState.updateFromVarbit();
        }

        // Update tick on BankStateManager for freshness tracking
        bankStateManager.setCurrentTick(currentTick);

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
     */
    private void initializeSessionBehaviors() {
        // Get player name for profile loading
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            log.warn("Cannot initialize session behaviors: local player is null");
            // Will retry on first game tick when player is available
            return;
        }
        
        String accountName = localPlayer.getName();
        if (accountName == null || accountName.isEmpty()) {
            log.warn("Cannot initialize session behaviors: account name is null");
            return;
        }
        
        log.info("Initializing behavioral session for account: {}", accountName);
        
        // Detect account type for profile generation
        com.rocinante.behavior.AccountType accountType = detectAccountType();
        
        // Initialize PlayerProfile (loads or creates profile)
        if (playerProfile != null) {
            playerProfile.initializeForAccount(accountName, accountType);
        }
        
        // Start fatigue tracking
        if (fatigueModel != null) {
            fatigueModel.onSessionStart();
        }
        
        // Start break scheduling
        if (breakScheduler != null) {
            breakScheduler.onSessionStart();
        }
        
        // Reset attention model to fresh state
        if (attentionModel != null) {
            attentionModel.reset();
        }
        
        log.info("Behavioral session initialized for account type: {}", accountType);
    }
    
    /**
     * Detect the account type to use for profile generation.
     * Uses IronmanState which handles intended vs actual type reconciliation.
     * 
     * @return account type for profile generation
     */
    private com.rocinante.behavior.AccountType detectAccountType() {
        if (ironmanState != null) {
            // IronmanState handles intended vs actual type logic
            return ironmanState.getEffectiveType();
        }
        
        // Fallback: read varbit directly
        try {
            int varbitValue = client.getVarbitValue(1777); // ACCOUNT_TYPE varbit
            return com.rocinante.behavior.AccountType.fromVarbit(varbitValue);
        } catch (Exception e) {
            log.debug("Could not detect account type: {}", e.getMessage());
            return com.rocinante.behavior.AccountType.NORMAL;
        }
    }
    
    /**
     * End behavioral session when player logs out.
     * Saves profile and records session statistics.
     */
    private void endSessionBehaviors() {
        log.info("Ending behavioral session");
        
        // End fatigue session (logs stats)
        if (fatigueModel != null) {
            fatigueModel.onSessionEnd();
        }
        
        // End break scheduler session
        if (breakScheduler != null) {
            breakScheduler.onSessionEnd();
        }
        
        // Record logout time and save profile
        if (playerProfile != null) {
            playerProfile.recordLogout();
        }
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
            log.trace("Equipment marked dirty");
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
        worldStateDirty = true;
        log.trace("WorldState marked dirty: Projectile moved");
    }

    /**
     * Handle graphics object creation - invalidate WorldState.
     */
    @Subscribe
    public void onGraphicsObjectCreated(GraphicsObjectCreated event) {
        worldStateDirty = true;
        log.trace("WorldState marked dirty: GraphicsObject created");
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

        // Check if cache is still valid (within 5 ticks)
        WorldState cached = worldStateCache.getIfValid(currentTick);
        if (cached != null && !worldStateDirty) {
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
     * Get the BankStateManager for direct access to bank operations.
     *
     * @return the BankStateManager instance
     */
    public BankStateManager getBankStateManager() {
        return bankStateManager;
    }

    // ========================================================================
    // State Refresh Methods
    // ========================================================================

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
                .build();
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

        // Attack style and weapon speed (from equipped weapon)
        AttackStyle attackStyle = AttackStyle.MELEE; // Default
        int weaponSpeed = 4; // Default 4-tick weapon
        // Note: Actual weapon speed should come from WikiDataService per REQUIREMENTS.md 10.6.1

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
            // Would need projectile ID -> attack style mapping
            incomingStyle = AttackStyle.UNKNOWN; // Need projectile data to determine
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
     */
    private List<AggressorInfo> buildAggressorList(Player localPlayer) {
        List<AggressorInfo> aggressors = new ArrayList<>();

        for (NPC npc : client.getNpcs()) {
            if (npc == null || npc.getInteracting() != localPlayer) {
                continue;
            }

            // This NPC is targeting the player
            int npcIndex = npc.getIndex();
            Integer lastAttackTick = npcLastAttackTicks.get(npcIndex);
            int ticksUntilNext = -1;

            // Estimate attack timing (rough - would need NPC attack speed data)
            if (lastAttackTick != null) {
                int ticksSince = currentTick - lastAttackTick;
                int estimatedAttackSpeed = 4; // Default assumption
                ticksUntilNext = Math.max(0, estimatedAttackSpeed - ticksSince);
            }

            // Estimate max hit based on combat level
            int estimatedMaxHit = AggressorInfo.estimateMaxHit(npc.getCombatLevel());

            // Detect if currently animating an attack
            boolean isAttacking = npc.getAnimation() != -1;

            // Update last attack tick if animating
            if (isAttacking && (lastAttackTick == null || currentTick - lastAttackTick > 2)) {
                npcLastAttackTicks.put(npcIndex, currentTick);
            }

            aggressors.add(AggressorInfo.builder()
                    .npcIndex(npcIndex)
                    .npcId(npc.getId())
                    .npcName(npc.getName())
                    .combatLevel(npc.getCombatLevel())
                    .ticksUntilNextAttack(ticksUntilNext)
                    .expectedMaxHit(estimatedMaxHit)
                    .attackStyle(-1) // Would need NPC data to determine
                    .attackSpeed(-1) // Would need NPC data
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
                    int gePrice = itemComp != null ? itemManager.getItemPrice(item.getId()) : -1;
                    int haPrice = itemComp != null ? itemComp.getHaPrice() : 0;
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
     * Also logs any unknown widget groups for discovery purposes.
     */
    private Set<Integer> buildVisibleWidgetIds() {
        Set<Integer> visibleIds = new HashSet<>();
        
        // Known common widget groups
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

        // Scan all possible widget groups (0-765 covers known OSRS range)
        // This helps discover unknown widgets like license agreement, name entry, etc.
        for (int groupId = 0; groupId < 766; groupId++) {
            net.runelite.api.widgets.Widget widget = client.getWidget(groupId, 0);
            if (widget != null) {
                visibleIds.add(groupId);
                
                // Log unknown widgets for discovery
                if (!knownWidgets.contains(groupId)) {
                    logUnknownWidget(groupId, widget);
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
                "Cache Stats - PlayerState: %.1f%%, InventoryState: %.1f%%, EquipmentState: %.1f%%, WorldState: %.1f%%, CombatState: %.1f%%",
                playerStateCache.getHitRate() * 100,
                inventoryStateCache.getHitRate() * 100,
                equipmentStateCache.getHitRate() * 100,
                worldStateCache.getHitRate() * 100,
                combatStateCache.getHitRate() * 100
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

