package com.rocinante.combat;

import com.rocinante.core.GameStateService;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemID;

import com.rocinante.util.Randomization;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Optional;

/**
 * Manages food consumption during combat.
 *
 * Per REQUIREMENTS.md Section 10.1.2, handles:
 * <ul>
 *   <li>Eat threshold management (normal and panic)</li>
 *   <li>Combo eating (food + karambwan tick eating)</li>
 *   <li>Saradomin brew management with restore pairing</li>
 *   <li>Optimal food selection based on damage</li>
 *   <li>HCIM-specific behaviors (pre-eat, minimum food)</li>
 * </ul>
 */
@Slf4j
@Singleton
public class FoodManager {

    private final Client client;
    private final GameStateService gameStateService;
    private final Randomization randomization;

    // ========================================================================
    // Item IDs
    // ========================================================================

    private static final int KARAMBWAN_ID = ItemID.COOKED_KARAMBWAN;
    
    // Saradomin brew IDs (4-dose to 1-dose)
    private static final int[] SARADOMIN_BREW_IDS = {
            ItemID.SARADOMIN_BREW4,
            ItemID.SARADOMIN_BREW3,
            ItemID.SARADOMIN_BREW2,
            ItemID.SARADOMIN_BREW1
    };

    // Super restore IDs (4-dose to 1-dose)
    private static final int[] SUPER_RESTORE_IDS = {
            ItemID.SUPER_RESTORE4,
            ItemID.SUPER_RESTORE3,
            ItemID.SUPER_RESTORE2,
            ItemID.SUPER_RESTORE1
    };

    // ========================================================================
    // Configuration
    // ========================================================================

    @Getter
    @Setter
    private FoodConfig config = FoodConfig.DEFAULT;

    @Getter
    @Setter
    private boolean hcimMode = false;

    // ========================================================================
    // State
    // ========================================================================

    /** Game tick when we last ate food. */
    @Getter
    private volatile int lastEatTick = -1;

    /** Number of brew sips since last restore. */
    @Getter
    private volatile int brewSipsSinceRestore = 0;

    /** Food delay in ticks (standard food = 3 ticks). */
    private static final int FOOD_DELAY_TICKS = 3;

    /** Karambwan can be eaten 1 tick after regular food. */
    private static final int COMBO_EAT_DELAY_TICKS = 1;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public FoodManager(Client client, GameStateService gameStateService, Randomization randomization) {
        this.client = client;
        this.gameStateService = gameStateService;
        this.randomization = randomization;
        log.info("FoodManager initialized");
    }

    // ========================================================================
    // Main Check Method
    // ========================================================================

    /**
     * Check if we need to eat based on current health and configuration.
     * Returns a CombatAction if eating is required, null otherwise.
     *
     * @param currentTick current game tick
     * @return CombatAction to eat, or null if no eating needed
     */
    @Nullable
    public CombatAction checkAndEat(int currentTick) {
        PlayerState playerState = gameStateService.getPlayerState();
        InventoryState inventoryState = gameStateService.getInventoryState();

        if (!playerState.isValid()) {
            return null;
        }

        double healthPercent = playerState.getHealthPercent();

        // Check if we can eat (food delay elapsed)
        if (!canEat(currentTick)) {
            return null;
        }

        // Get effective thresholds
        double eatThreshold = config.getEffectiveEatThreshold(hcimMode);
        double panicThreshold = config.getEffectivePanicThreshold(hcimMode);

        // Check food availability
        if (!inventoryState.hasFood() && !hasSaradominBrew(inventoryState)) {
            if (healthPercent < panicThreshold) {
                log.warn("Low health ({}%) with no food - triggering emergency FLEE!", String.format("%.1f", healthPercent * 100));
                return CombatAction.builder()
                        .type(CombatAction.Type.FLEE)
                        .priority(CombatAction.Priority.URGENT)
                        .fleeMethod("emergency_teleport")
                        .build();
            }
            return null;
        }

        // Panic eat - immediately
        if (healthPercent < panicThreshold) {
            log.info("Panic eating at {}% health", healthPercent * 100);
            return createEatAction(inventoryState, playerState, true);
        }

        // Normal eat threshold
        if (healthPercent < eatThreshold) {
            log.debug("Eating at {}% health (threshold: {}%)", 
                    healthPercent * 100, eatThreshold * 100);
            return createEatAction(inventoryState, playerState, false);
        }

        // Humanization: occasional panic eat in extra range
        if (config.isInPanicEatRange(healthPercent)) {
            if (randomization.chance(config.getPanicEatExtraProbability())) {
                log.debug("Humanized panic eat at {}% health", healthPercent * 100);
                return createEatAction(inventoryState, playerState, false);
            }
        }

        return null;
    }

    /**
     * Check if we should pre-eat before engaging a target.
     * Only applies to HCIM mode.
     *
     * @param currentTick current game tick
     * @return CombatAction to eat, or null if no pre-eating needed
     */
    @Nullable
    public CombatAction checkPreEat(int currentTick) {
        if (!hcimMode || !config.isPreEatBeforeEngaging()) {
            return null;
        }

        PlayerState playerState = gameStateService.getPlayerState();
        InventoryState inventoryState = gameStateService.getInventoryState();

        if (!playerState.isValid() || !canEat(currentTick)) {
            return null;
        }

        double healthPercent = playerState.getHealthPercent();

        if (healthPercent < config.getPreEatThreshold()) {
            if (inventoryState.hasFood() || hasSaradominBrew(inventoryState)) {
                log.debug("HCIM pre-eating at {}% health before engaging", healthPercent * 100);
                return createEatAction(inventoryState, playerState, false);
            }
        }

        return null;
    }

    // ========================================================================
    // Eat Action Creation
    // ========================================================================

    /**
     * Create an eat action, potentially with combo eating.
     */
    private CombatAction createEatAction(InventoryState inventoryState, PlayerState playerState, boolean isPanic) {
        int damageTaken = playerState.getMaxHitpoints() - playerState.getCurrentHitpoints();

        // Check if we should use a Saradomin brew
        Optional<Integer> brewSlot = getSaradominBrewSlot(inventoryState);
        if (brewSlot.isPresent() && shouldUseBrew(inventoryState, playerState)) {
            return createBrewAction(inventoryState, brewSlot.get());
        }

        // Get food slot (optimal or best)
        int foodSlot;
        if (config.isUseOptimalFoodSelection()) {
            foodSlot = inventoryState.getOptimalFood(damageTaken, playerState.getMaxHitpoints());
            if (foodSlot < 0) {
                foodSlot = inventoryState.getBestFood();
            }
        } else {
            foodSlot = inventoryState.getBestFood();
        }

        if (foodSlot < 0) {
            // No regular food, try brew as fallback
            if (brewSlot.isPresent()) {
                return createBrewAction(inventoryState, brewSlot.get());
            }
            return null;
        }

        // Determine if we should combo eat
        boolean shouldCombo = shouldComboEat(isPanic, inventoryState);

        // Check for karambwan for combo eating
        int karambwanSlot = -1;
        if (shouldCombo) {
            karambwanSlot = inventoryState.getSlotOf(KARAMBWAN_ID);
            if (karambwanSlot < 0) {
                shouldCombo = false;
            }
        }

        // Get item ID for tracking
        int foodItemId = inventoryState.getItemInSlot(foodSlot)
                .map(item -> item.getId())
                .orElse(-1);

        return CombatAction.builder()
                .type(CombatAction.Type.EAT)
                .primarySlot(foodSlot)
                .secondarySlot(shouldCombo ? karambwanSlot : -1)
                .comboEat(shouldCombo)
                .itemId(foodItemId)
                .priority(isPanic ? CombatAction.Priority.URGENT : CombatAction.Priority.HIGH)
                .build();
    }

    /**
     * Create a Saradomin brew action with restore pairing.
     */
    private CombatAction createBrewAction(InventoryState inventoryState, int brewSlot) {
        // Check if we need a restore after this brew
        boolean needsRestore = (brewSipsSinceRestore + 1) >= config.getBrewRestoreRatio();
        
        int restoreSlot = -1;
        if (needsRestore) {
            restoreSlot = getSuperRestoreSlot(inventoryState).orElse(-1);
        }

        log.debug("Using Saradomin brew (sips since restore: {}, will restore: {})", 
                brewSipsSinceRestore, restoreSlot >= 0);

        // Get brew item ID for tracking
        int brewItemId = inventoryState.getItemInSlot(brewSlot)
                .map(item -> item.getId())
                .orElse(-1);

        return CombatAction.builder()
                .type(CombatAction.Type.EAT)
                .primarySlot(brewSlot)
                .secondarySlot(restoreSlot)
                .comboEat(restoreSlot >= 0)
                .itemId(brewItemId)
                .priority(CombatAction.Priority.HIGH)
                .build();
    }

    // ========================================================================
    // Brew Management
    // ========================================================================

    /**
     * Check if we have a Saradomin brew in inventory.
     */
    public boolean hasSaradominBrew(InventoryState inventoryState) {
        return getSaradominBrewSlot(inventoryState).isPresent();
    }

    /**
     * Get the slot of a Saradomin brew, if present.
     */
    private Optional<Integer> getSaradominBrewSlot(InventoryState inventoryState) {
        for (int brewId : SARADOMIN_BREW_IDS) {
            int slot = inventoryState.getSlotOf(brewId);
            if (slot >= 0) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    /**
     * Get the slot of a Super restore, if present.
     */
    private Optional<Integer> getSuperRestoreSlot(InventoryState inventoryState) {
        for (int restoreId : SUPER_RESTORE_IDS) {
            int slot = inventoryState.getSlotOf(restoreId);
            if (slot >= 0) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    /**
     * Determine if we should use a brew instead of regular food.
     * Brews are preferred when:
     * - We have restores to pair with them
     * - Health is critically low (brews heal more in emergencies)
     * - We're out of regular food
     */
    private boolean shouldUseBrew(InventoryState inventoryState, PlayerState playerState) {
        // If no regular food, use brew
        if (!inventoryState.hasFood()) {
            return true;
        }

        // If health is critically low and we have restore, prioritize brew
        if (playerState.getHealthPercent() < 0.30) {
            return getSuperRestoreSlot(inventoryState).isPresent();
        }

        // Otherwise prefer regular food
        return false;
    }

    /**
     * Called after successfully using a brew to track ratio.
     */
    public void onBrewUsed() {
        brewSipsSinceRestore++;
        log.debug("Brew sip count: {}", brewSipsSinceRestore);
    }

    /**
     * Called after successfully using a restore to reset tracking.
     */
    public void onRestoreUsed() {
        brewSipsSinceRestore = 0;
        log.debug("Brew sip count reset after restore");
    }

    /**
     * Check if an item is a Saradomin brew.
     *
     * @param itemId the item ID to check
     * @return true if this is a Saradomin brew
     */
    public boolean isSaradominBrew(int itemId) {
        for (int brewId : SARADOMIN_BREW_IDS) {
            if (itemId == brewId) return true;
        }
        return false;
    }

    /**
     * Check if an item is a super restore.
     *
     * @param itemId the item ID to check
     * @return true if this is a super restore
     */
    public boolean isSuperRestore(int itemId) {
        for (int restoreId : SUPER_RESTORE_IDS) {
            if (itemId == restoreId) return true;
        }
        return false;
    }

    // ========================================================================
    // Combo Eating
    // ========================================================================

    /**
     * Determine if we should combo eat.
     */
    private boolean shouldComboEat(boolean isPanic, InventoryState inventoryState) {
        if (!config.isUseComboEating()) {
            return false;
        }

        // Always try to combo in panic situations
        if (isPanic) {
            return true;
        }

        // Random chance based on config
        return randomization.chance(config.getComboEatProbability());
    }

    // ========================================================================
    // Timing
    // ========================================================================

    /**
     * Check if enough time has passed since last eat.
     */
    public boolean canEat(int currentTick) {
        if (lastEatTick < 0) {
            return true;
        }
        return currentTick - lastEatTick >= FOOD_DELAY_TICKS;
    }

    /**
     * Called after successfully eating to record the tick.
     */
    public void onFoodEaten(int currentTick) {
        lastEatTick = currentTick;
    }

    // ========================================================================
    // Food Availability Checks
    // ========================================================================

    /**
     * Check if we have enough food to continue combat.
     *
     * @return true if food count meets minimum requirement
     */
    public boolean hasEnoughFood() {
        InventoryState inventoryState = gameStateService.getInventoryState();
        int foodCount = inventoryState.countFood();
        int minRequired = config.getEffectiveMinFoodCount(hcimMode);
        return foodCount >= minRequired;
    }

    /**
     * Get the current food count in inventory.
     */
    public int getFoodCount() {
        return gameStateService.getInventoryState().countFood();
    }

    /**
     * Get total potential healing from current food.
     */
    public int getTotalHealing() {
        return gameStateService.getInventoryState().getTotalFoodHealing();
    }

    // ========================================================================
    // Reset
    // ========================================================================

    /**
     * Reset state (e.g., on logout).
     */
    public void reset() {
        lastEatTick = -1;
        brewSipsSinceRestore = 0;
        log.debug("FoodManager reset");
    }
}

