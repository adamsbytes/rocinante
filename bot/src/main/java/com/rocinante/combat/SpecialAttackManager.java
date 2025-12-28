package com.rocinante.combat;

import com.rocinante.core.GameStateService;
import com.rocinante.data.WikiDataService;
import com.rocinante.data.model.WeaponInfo;
import com.rocinante.state.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;

import com.rocinante.util.Randomization;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages special attack usage during combat.
 *
 * Per REQUIREMENTS.md Section 10.4 and 10.6.3:
 * <ul>
 *   <li>Detect equipped weapon's special attack capability</li>
 *   <li>Track special attack energy (0-100%)</li>
 *   <li>Threshold-based usage (use at X% energy)</li>
 *   <li>Target-based usage (boss only option)</li>
 *   <li>Stacking: for specs that stack (DDS), use multiple times</li>
 *   <li>Weapon switching: switch to spec weapon, use spec, switch back</li>
 * </ul>
 *
 * Per Section 10.6.3 mechanics:
 * <ul>
 *   <li>Maximum energy: 100%</li>
 *   <li>Regenerates at 10% per minute (passive)</li>
 *   <li>Weapon switches take 1 tick</li>
 *   <li>Human delay variance: 100±30ms</li>
 * </ul>
 */
@Slf4j
@Singleton
public class SpecialAttackManager {

    private final Client client;
    private final GameStateService gameStateService;
    private final GearSwitcher gearSwitcher;
    private final WikiDataService wikiDataService;
    private final ItemManager itemManager;
    private final Randomization randomization;

    /**
     * Cache for weapon info from WikiDataService.
     * Maps item ID to WeaponInfo.
     */
    private final Map<Integer, WeaponInfo> weaponInfoCache = new ConcurrentHashMap<>();

    // ========================================================================
    // Configuration
    // ========================================================================

    @Getter
    @Setter
    private SpecialAttackConfig config = SpecialAttackConfig.DEFAULT;

    // ========================================================================
    // State
    // ========================================================================

    /** Currently tracked spec weapon (equipped or in inventory). */
    @Getter
    @Nullable
    private volatile SpecialWeapon currentSpecWeapon = null;

    /** Item ID of main weapon to switch back to. */
    @Getter
    private volatile int mainWeaponItemId = -1;

    /** Tick when we last used a special attack. */
    @Getter
    private volatile int lastSpecTick = -1;

    /** Number of specs used in current sequence. */
    @Getter
    private volatile int specsUsedInSequence = 0;

    /** Whether we're in the middle of a spec sequence. */
    @Getter
    private volatile boolean inSpecSequence = false;

    /** Whether we're waiting to switch back to main weapon. */
    @Getter
    private volatile boolean pendingSwitchBack = false;

    // ========================================================================
    // Constants
    // ========================================================================

    /** Special attack energy regeneration rate per minute. */
    public static final int ENERGY_REGEN_PER_MINUTE = 10;

    /** Ticks per minute (100 ticks = 60 seconds). */
    private static final int TICKS_PER_MINUTE = 100;

    /** Minimum ticks between spec attacks (1 tick = 600ms). */
    private static final int MIN_SPEC_INTERVAL_TICKS = 1;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public SpecialAttackManager(
            Client client,
            GameStateService gameStateService,
            GearSwitcher gearSwitcher,
            WikiDataService wikiDataService,
            ItemManager itemManager,
            Randomization randomization) {
        this.client = client;
        this.gameStateService = gameStateService;
        this.gearSwitcher = gearSwitcher;
        this.wikiDataService = wikiDataService;
        this.itemManager = itemManager;
        this.randomization = randomization;
        log.info("SpecialAttackManager initialized with WikiDataService");
    }

    // ========================================================================
    // Main Check Method
    // ========================================================================

    /**
     * Check if we should use special attack and return action if so.
     *
     * @param currentTick current game tick
     * @return CombatAction for special attack, or null if none needed
     */
    @Nullable
    public CombatAction checkAndUseSpec(int currentTick) {
        if (!config.isEnabled()) {
            return null;
        }

        CombatState combatState = gameStateService.getCombatState();
        EquipmentState equipmentState = gameStateService.getEquipmentState();
        InventoryState inventoryState = gameStateService.getInventoryState();

        // Must have a target
        if (!combatState.hasTarget()) {
            return null;
        }

        // Handle pending switch back to main weapon
        if (pendingSwitchBack) {
            return handleSwitchBack(inventoryState);
        }

        // Check current special attack energy
        int specEnergy = combatState.getSpecialAttackEnergy();

        // Check if energy meets threshold
        if (specEnergy < config.getEnergyThreshold()) {
            return null;
        }

        // Check target eligibility
        NpcSnapshot target = combatState.getTargetNpc();
        if (target != null && !shouldSpecTarget(target)) {
            return null;
        }

        // Find available spec weapon
        SpecialWeapon specWeapon = findSpecWeapon(equipmentState, inventoryState);
        if (specWeapon == null) {
            return null;
        }

        // Check if we have enough energy for this weapon's spec
        if (specEnergy < specWeapon.getEnergyCost()) {
            return null;
        }

        // Check spec timing
        if (!canSpec(currentTick)) {
            return null;
        }

        // Calculate how many specs to use
        int specCount = config.calculateSpecCount(specEnergy, specWeapon.getEnergyCost());
        if (specCount <= 0) {
            return null;
        }

        // Check if we need to switch to spec weapon first
        boolean needsWeaponSwitch = needsWeaponSwitch(equipmentState, specWeapon);
        if (needsWeaponSwitch) {
            return handleWeaponSwitchToSpec(equipmentState, specWeapon);
        }

        // Use special attack
        return createSpecAction(specWeapon, specCount, currentTick);
    }

    // ========================================================================
    // Target Eligibility
    // ========================================================================

    /**
     * Check if we should use spec on the given target.
     */
    private boolean shouldSpecTarget(NpcSnapshot target) {
        // TODO: Implement boss detection via WikiDataService or NPC data
        boolean isBoss = target.getCombatLevel() >= 100; // Simple heuristic for now

        return config.shouldSpecTarget(
                target.getId(),
                target.getCombatLevel(),
                isBoss
        );
    }

    // ========================================================================
    // Spec Weapon Detection
    // ========================================================================

    /**
     * Find available spec weapon from equipment or inventory.
     *
     * @return SpecialWeapon, or null if none available
     */
    @Nullable
    private SpecialWeapon findSpecWeapon(EquipmentState equipment, InventoryState inventory) {
        // Check if specific spec weapon is configured
        if (config.isUseSpecWeapon() && config.getSpecWeaponItemId() > 0) {
            SpecialWeapon configured = SpecialWeapon.forItemId(config.getSpecWeaponItemId());
            if (configured != null) {
                // Verify we have it
                if (equipment.hasEquipped(config.getSpecWeaponItemId()) ||
                    inventory.hasItem(config.getSpecWeaponItemId())) {
                    currentSpecWeapon = configured;
                    return configured;
                }
            }
        }

        // Check currently equipped weapon
        int equippedWeaponId = equipment.getWeaponId();
        if (equippedWeaponId > 0) {
            SpecialWeapon equipped = SpecialWeapon.forItemId(equippedWeaponId);
            if (equipped != null) {
                currentSpecWeapon = equipped;
                return equipped;
            }
        }

        // Auto-detect from inventory if configured
        if (config.isUseSpecWeapon()) {
            Optional<SpecialWeapon> fromInventory = findSpecWeaponInInventory(inventory);
            if (fromInventory.isPresent()) {
                currentSpecWeapon = fromInventory.get();
                return currentSpecWeapon;
            }
        }

        currentSpecWeapon = null;
        return null;
    }

    /**
     * Find the best spec weapon in inventory.
     */
    private Optional<SpecialWeapon> findSpecWeaponInInventory(InventoryState inventory) {
        // Priority order: DWH > BGS > DDS > other
        int[] priorityIds = {
                net.runelite.api.ItemID.DRAGON_WARHAMMER,
                net.runelite.api.ItemID.BANDOS_GODSWORD,
                net.runelite.api.ItemID.DRAGON_DAGGER,
                net.runelite.api.ItemID.DRAGON_DAGGERP,
                net.runelite.api.ItemID.ARMADYL_GODSWORD
        };

        for (int itemId : priorityIds) {
            if (inventory.hasItem(itemId)) {
                SpecialWeapon weapon = SpecialWeapon.forItemId(itemId);
                if (weapon != null) {
                    return Optional.of(weapon);
                }
            }
        }

        // Check any item with spec
        for (var item : inventory.getNonEmptyItems()) {
            SpecialWeapon weapon = SpecialWeapon.forItemId(item.getId());
            if (weapon != null) {
                return Optional.of(weapon);
            }
        }

        return Optional.empty();
    }

    // ========================================================================
    // Weapon Switching
    // ========================================================================

    /**
     * Check if we need to switch weapons to use spec.
     */
    private boolean needsWeaponSwitch(EquipmentState equipment, SpecialWeapon specWeapon) {
        if (!config.isUseSpecWeapon()) {
            return false;
        }

        int equippedWeaponId = equipment.getWeaponId();
        for (int specItemId : specWeapon.getItemIds()) {
            if (equippedWeaponId == specItemId) {
                return false; // Already equipped
            }
        }

        return true;
    }

    /**
     * Handle switching to spec weapon.
     */
    private CombatAction handleWeaponSwitchToSpec(EquipmentState equipment, SpecialWeapon specWeapon) {
        // Store main weapon for switching back
        mainWeaponItemId = equipment.getWeaponId();
        inSpecSequence = true;

        log.debug("Switching to spec weapon: {} (main weapon ID: {})",
                specWeapon.getName(), mainWeaponItemId);

        return CombatAction.builder()
                .type(CombatAction.Type.GEAR_SWITCH)
                .gearSetName("spec_weapon_" + specWeapon.name())
                .primarySlot(specWeapon.getItemIds()[0])
                .priority(CombatAction.Priority.HIGH)
                .build();
    }

    /**
     * Handle switching back to main weapon.
     */
    @Nullable
    private CombatAction handleSwitchBack(InventoryState inventory) {
        if (!config.isSwitchBackAfterSpec() || mainWeaponItemId < 0) {
            pendingSwitchBack = false;
            return null;
        }

        if (!inventory.hasItem(mainWeaponItemId)) {
            log.warn("Main weapon (ID: {}) not found in inventory for switch back", mainWeaponItemId);
            pendingSwitchBack = false;
            mainWeaponItemId = -1;
            return null;
        }

        pendingSwitchBack = false;
        int weaponId = mainWeaponItemId;
        mainWeaponItemId = -1;
        inSpecSequence = false;

        log.debug("Switching back to main weapon (ID: {})", weaponId);

        return CombatAction.builder()
                .type(CombatAction.Type.GEAR_SWITCH)
                .gearSetName("main_weapon")
                .primarySlot(weaponId)
                .priority(CombatAction.Priority.NORMAL)
                .build();
    }

    // ========================================================================
    // Spec Execution
    // ========================================================================

    /**
     * Create the special attack action.
     */
    private CombatAction createSpecAction(SpecialWeapon weapon, int specCount, int currentTick) {
        lastSpecTick = currentTick;
        specsUsedInSequence++;

        log.debug("Using special attack: {} (energy cost: {}, count: {})",
                weapon.getName(), weapon.getEnergyCost(), specCount);

        // Schedule switch back if needed
        if (config.isSwitchBackAfterSpec() && mainWeaponItemId > 0) {
            pendingSwitchBack = true;
        }

        // Check if we should stack specs
        boolean willStack = config.isStackSpecs() && 
                           weapon.canStack() && 
                           specCount > 1;

        return CombatAction.builder()
                .type(CombatAction.Type.SPECIAL_ATTACK)
                .priority(CombatAction.Priority.HIGH)
                .primarySlot(weapon.getEnergyCost()) // Store energy cost for reference
                .secondarySlot(willStack ? specCount : 1)
                .build();
    }

    /**
     * Check if enough time has passed to use spec.
     */
    private boolean canSpec(int currentTick) {
        if (lastSpecTick < 0) {
            return true;
        }
        return currentTick - lastSpecTick >= MIN_SPEC_INTERVAL_TICKS;
    }

    // ========================================================================
    // Energy Tracking
    // ========================================================================

    /**
     * Get current special attack energy.
     *
     * @return energy percentage (0-100)
     */
    public int getCurrentEnergy() {
        return gameStateService.getCombatState().getSpecialAttackEnergy();
    }

    /**
     * Estimate ticks until spec energy reaches threshold.
     *
     * @return ticks until threshold, or 0 if already met
     */
    public int getTicksUntilThreshold() {
        int currentEnergy = getCurrentEnergy();
        int threshold = config.getEnergyThreshold();

        if (currentEnergy >= threshold) {
            return 0;
        }

        int energyNeeded = threshold - currentEnergy;
        // Energy regenerates at 10% per minute = 10% per 100 ticks
        // So 1% per 10 ticks
        return energyNeeded * 10;
    }

    /**
     * Check if special attack is ready (energy meets threshold).
     *
     * @return true if spec is ready
     */
    public boolean isSpecReady() {
        return getCurrentEnergy() >= config.getEnergyThreshold();
    }

    // ========================================================================
    // Weapon Info (WikiDataService Integration)
    // ========================================================================

    /**
     * Get the spec energy cost for an item.
     * Queries WikiDataService with fallback to hardcoded SpecialWeapon enum.
     *
     * @param itemId the item ID
     * @return energy cost, or -1 if not a spec weapon
     */
    public int getSpecEnergyCost(int itemId) {
        // Check local cache first
        WeaponInfo cached = weaponInfoCache.get(itemId);
        if (cached != null && cached.canSpecial()) {
            return cached.specialAttackCost();
        }

        // Try hardcoded fallback (fast path)
        int fallbackCost = SpecialWeapon.getEnergyCost(itemId);
        if (fallbackCost > 0) {
            return fallbackCost;
        }

        // Query WikiDataService asynchronously and cache result
        // For this call, return fallback while fetching in background
        String itemName = getItemName(itemId);
        if (itemName != null && !itemName.isEmpty()) {
            wikiDataService.getWeaponInfo(itemId, itemName)
                    .thenAccept(info -> {
                        if (info != null && info.isValid()) {
                            weaponInfoCache.put(itemId, info);
                            log.debug("Cached weapon info from wiki for {}: spec cost = {}",
                                    itemName, info.specialAttackCost());
                        }
                    });
        }

        return fallbackCost;
    }

    /**
     * Check if an item has a special attack.
     * Queries WikiDataService with fallback to hardcoded SpecialWeapon enum.
     *
     * @param itemId the item ID
     * @return true if item has spec
     */
    public boolean hasSpecialAttack(int itemId) {
        // Check local cache first
        WeaponInfo cached = weaponInfoCache.get(itemId);
        if (cached != null) {
            return cached.hasSpecialAttack();
        }

        // Try hardcoded fallback (fast path)
        if (SpecialWeapon.hasSpecialAttack(itemId)) {
            return true;
        }

        // Query WikiDataService asynchronously and cache result
        String itemName = getItemName(itemId);
        if (itemName != null && !itemName.isEmpty()) {
            wikiDataService.getWeaponInfo(itemId, itemName)
                    .thenAccept(info -> {
                        if (info != null && info.isValid()) {
                            weaponInfoCache.put(itemId, info);
                            log.debug("Cached weapon info from wiki for {}: has spec = {}",
                                    itemName, info.hasSpecialAttack());
                        }
                    });
        }

        return false;
    }

    /**
     * Get weapon attack speed in game ticks.
     * Per REQUIREMENTS.md Section 10.6.1, weapon speeds should be queried dynamically.
     *
     * @param itemId the item ID
     * @return attack speed in ticks, or 4 (default) if unknown
     */
    public int getWeaponAttackSpeed(int itemId) {
        // Check local cache first
        WeaponInfo cached = weaponInfoCache.get(itemId);
        if (cached != null && cached.hasKnownAttackSpeed()) {
            return cached.attackSpeed();
        }

        // Query WikiDataService synchronously for combat-critical data
        String itemName = getItemName(itemId);
        if (itemName != null && !itemName.isEmpty()) {
            WeaponInfo info = wikiDataService.getWeaponInfoSync(itemId, itemName);
            if (info != null && info.isValid()) {
                weaponInfoCache.put(itemId, info);
                if (info.hasKnownAttackSpeed()) {
                    return info.attackSpeed();
                }
            }
        }

        // Default attack speed (4 ticks = average weapon)
        return WeaponInfo.SPEED_AVERAGE;
    }

    /**
     * Get detailed weapon information.
     *
     * @param itemId the item ID
     * @return weapon info (may be empty if not found)
     */
    public WeaponInfo getWeaponInfo(int itemId) {
        // Check local cache first
        WeaponInfo cached = weaponInfoCache.get(itemId);
        if (cached != null) {
            return cached;
        }

        // Query WikiDataService
        String itemName = getItemName(itemId);
        if (itemName != null && !itemName.isEmpty()) {
            WeaponInfo info = wikiDataService.getWeaponInfoSync(itemId, itemName);
            if (info != null && info.isValid()) {
                weaponInfoCache.put(itemId, info);
                return info;
            }
        }

        return WeaponInfo.EMPTY;
    }

    /**
     * Prefetch weapon info for items in inventory/equipment.
     * Called during combat setup to warm the cache.
     *
     * @param itemIds list of item IDs to prefetch
     */
    public void prefetchWeaponInfo(int... itemIds) {
        for (int itemId : itemIds) {
            if (itemId <= 0 || weaponInfoCache.containsKey(itemId)) {
                continue;
            }
            String itemName = getItemName(itemId);
            if (itemName != null && !itemName.isEmpty()) {
                wikiDataService.getWeaponInfo(itemId, itemName)
                        .thenAccept(info -> {
                            if (info != null && info.isValid()) {
                                weaponInfoCache.put(itemId, info);
                            }
                        });
            }
        }
    }

    /**
     * Get item name from ItemManager.
     *
     * @param itemId the item ID
     * @return item name, or null if not found
     */
    @Nullable
    private String getItemName(int itemId) {
        if (itemId <= 0) {
            return null;
        }
        try {
            var composition = itemManager.getItemComposition(itemId);
            return composition != null ? composition.getName() : null;
        } catch (Exception e) {
            log.trace("Failed to get item name for ID {}: {}", itemId, e.getMessage());
            return null;
        }
    }

    /**
     * Clear the weapon info cache.
     * Called on logout or when data may be stale.
     */
    public void clearWeaponInfoCache() {
        weaponInfoCache.clear();
        log.debug("Weapon info cache cleared");
    }

    // ========================================================================
    // Events
    // ========================================================================

    /**
     * Called when a special attack is successfully used.
     */
    public void onSpecUsed(int currentTick) {
        lastSpecTick = currentTick;
        specsUsedInSequence++;
        log.debug("Special attack used at tick {}", currentTick);
    }

    /**
     * Called when combat ends to reset state.
     */
    public void onCombatEnd() {
        specsUsedInSequence = 0;
        inSpecSequence = false;
        pendingSwitchBack = false;
        log.debug("SpecialAttackManager reset for combat end");
    }

    // ========================================================================
    // Reset
    // ========================================================================

    /**
     * Reset state (e.g., on logout).
     */
    public void reset() {
        currentSpecWeapon = null;
        mainWeaponItemId = -1;
        lastSpecTick = -1;
        specsUsedInSequence = 0;
        inSpecSequence = false;
        pendingSwitchBack = false;
        log.debug("SpecialAttackManager reset");
    }
}

