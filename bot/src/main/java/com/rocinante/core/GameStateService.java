package com.rocinante.core;

import com.rocinante.state.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.EnumMap;
import java.util.Map;

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

    // ========================================================================
    // Cached State Snapshots
    // ========================================================================

    private final CachedValue<PlayerState> playerStateCache;
    private final CachedValue<InventoryState> inventoryStateCache;
    private final CachedValue<EquipmentState> equipmentStateCache;

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

    @Inject
    public GameStateService(Client client) {
        this.client = client;

        // Initialize caches with appropriate policies
        this.playerStateCache = new CachedValue<>("PlayerState", CachePolicy.TICK_CACHED);
        this.inventoryStateCache = new CachedValue<>("InventoryState", CachePolicy.EVENT_INVALIDATED);
        this.equipmentStateCache = new CachedValue<>("EquipmentState", CachePolicy.EVENT_INVALIDATED);

        log.info("GameStateService initialized");
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
        } else if (state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST) {
            loggedIn = false;
            log.info("Player logged out - resetting state caches");
            resetAllCaches();
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
        currentTick = 0;
        inventoryDirty = true;
        equipmentDirty = true;
        statsDirty = true;
    }

    /**
     * Force invalidation of all caches.
     * Useful for debugging or when client state may be inconsistent.
     */
    public void invalidateAllCaches() {
        playerStateCache.invalidate();
        inventoryStateCache.invalidate();
        equipmentStateCache.invalidate();
        inventoryDirty = true;
        equipmentDirty = true;
        statsDirty = true;
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
                "Cache Stats - PlayerState: %.1f%%, InventoryState: %.1f%%, EquipmentState: %.1f%%",
                playerStateCache.getHitRate() * 100,
                inventoryStateCache.getHitRate() * 100,
                equipmentStateCache.getHitRate() * 100
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
                && equipmentStateCache.getHitRate() >= 0.90;
    }
}

