package com.rocinante.combat;

import com.rocinante.core.GameStateService;
import com.rocinante.progression.UnlockTracker;
import com.rocinante.state.CombatState;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.GroundItemSnapshot;
import com.rocinante.state.InventoryState;
import com.rocinante.state.NpcSnapshot;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
// Use explicit import to avoid conflict with com.rocinante.combat.AttackStyle
import com.rocinante.state.AttackStyle;
import com.rocinante.input.RobotMouseController;
import com.rocinante.timing.HumanTimer;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Random;

/**
 * Main combat loop orchestrator.
 *
 * Per REQUIREMENTS.md Section 10.1, executes the combat loop every game tick:
 * <ol>
 *   <li>Check health → eat if below threshold</li>
 *   <li>Check prayer → restore or toggle prayers</li>
 *   <li>Check special attack → use if conditions met</li>
 *   <li>Check target → retarget if current dead or out of range</li>
 *   <li>Check loot → pick up valuable drops</li>
 *   <li>Execute queued combat actions (gear switches, prayer flicks)</li>
 * </ol>
 *
 * Delegates to specialized managers:
 * <ul>
 *   <li>{@link FoodManager} - Food consumption and combo eating</li>
 *   <li>{@link PrayerFlicker} - Protection prayers and flicking</li>
 *   <li>{@link SpecialAttackManager} - Special attack usage</li>
 *   <li>{@link HCIMSafetyManager} - HCIM safety checks and flee logic</li>
 * </ul>
 */
@Slf4j
@Singleton
public class CombatManager {

    private final Client client;
    private final GameStateService gameStateService;
    private final RobotMouseController mouseController;
    private final HumanTimer humanTimer;
    private final HCIMSafetyManager hcimSafetyManager;
    private final FoodManager foodManager;
    private final PrayerFlicker prayerFlicker;
    private final SpecialAttackManager specialAttackManager;
    private final Random random = new Random();

    // ========================================================================
    // State
    // ========================================================================

    @Getter
    @Setter
    private volatile boolean enabled = false;

    @Getter
    @Setter
    private CombatConfig config = CombatConfig.DEFAULT;

    @Getter
    private volatile boolean inCombat = false;

    @Getter
    private volatile boolean fleeing = false;

    @Getter
    @Nullable
    private volatile HCIMSafetyManager.FleeReason lastFleeReason = null;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public CombatManager(
            Client client,
            GameStateService gameStateService,
            RobotMouseController mouseController,
            HumanTimer humanTimer,
            HCIMSafetyManager hcimSafetyManager,
            FoodManager foodManager,
            PrayerFlicker prayerFlicker,
            SpecialAttackManager specialAttackManager) {
        this.client = client;
        this.gameStateService = gameStateService;
        this.mouseController = mouseController;
        this.humanTimer = humanTimer;
        this.hcimSafetyManager = hcimSafetyManager;
        this.foodManager = foodManager;
        this.prayerFlicker = prayerFlicker;
        this.specialAttackManager = specialAttackManager;
        log.info("CombatManager initialized with delegated managers");
    }

    // ========================================================================
    // Combat Loop (Section 10.1.1)
    // ========================================================================

    /**
     * Main combat tick handler.
     * Per Section 10.1.1, executes combat loop every game tick during combat.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        if (!enabled) {
            return;
        }

        PlayerState playerState = gameStateService.getPlayerState();
        CombatState combatState = gameStateService.getCombatState();
        InventoryState inventoryState = gameStateService.getInventoryState();
        EquipmentState equipmentState = gameStateService.getEquipmentState();

        if (!playerState.isValid()) {
            return;
        }

        // Update combat status
        inCombat = combatState.hasTarget() || combatState.isBeingAttacked();

        int currentTick = gameStateService.getCurrentTick();

        // Sync HCIM mode to delegated managers
        syncManagerConfigs();

        // HCIM Safety Check (HIGHEST PRIORITY - Section 10.1.4)
        if (config.isHcimMode()) {
            CombatAction fleeAction = checkHCIMSafety(combatState, inventoryState, equipmentState);
            if (fleeAction != null) {
                executeAction(fleeAction);
                return; // Fleeing takes absolute priority
            }
        }

        if (!inCombat) {
            fleeing = false; // Clear flee state when no longer in combat
            handleCombatEnd();
            return;
        }

        // Step 1: Check health → eat if below threshold (delegated to FoodManager)
        CombatAction eatAction = foodManager.checkAndEat(currentTick);
        if (eatAction != null) {
            executeAction(eatAction);
            return; // Eating takes priority
        }

        // Step 2: Check prayer → restore or toggle prayers (delegated to PrayerFlicker)
        CombatAction prayerAction = prayerFlicker.checkAndFlick(currentTick);
        if (prayerAction != null) {
            executeAction(prayerAction);
        }

        // Step 3: Check special attack → use if conditions met (delegated to SpecialAttackManager)
        CombatAction specAction = specialAttackManager.checkAndUseSpec(currentTick);
        if (specAction != null) {
            executeAction(specAction);
        }

        // Step 4: Check target → retarget if current dead or out of range
        CombatAction targetAction = checkTarget(combatState, playerState);
        if (targetAction != null) {
            executeAction(targetAction);
        }

        // Step 5: Check loot → pick up valuable drops
        CombatAction lootAction = checkLoot(playerState, inventoryState);
        if (lootAction != null) {
            executeAction(lootAction);
        }

        // Log combat state for debugging
        if (log.isDebugEnabled()) {
            log.debug("Combat tick: {}", combatState.getSummary());
        }
    }

    /**
     * Synchronize configuration to delegated managers.
     */
    private void syncManagerConfigs() {
        boolean isHcim = config.isHcimMode();

        // Sync FoodManager config
        foodManager.setHcimMode(isHcim);
        if (config.getFoodConfig() != null) {
            foodManager.setConfig(config.getFoodConfig());
        } else {
            foodManager.setConfig(isHcim ? FoodConfig.HCIM_SAFE : FoodConfig.DEFAULT);
        }

        // Sync PrayerFlicker config
        if (config.getPrayerConfig() != null) {
            prayerFlicker.setConfig(config.getPrayerConfig());
        } else if (isHcim) {
            // HCIM might want safer prayer settings
            prayerFlicker.setConfig(PrayerConfig.SAFE_MODE);
        }

        // Sync SpecialAttackManager config
        if (config.getSpecialAttackConfig() != null) {
            specialAttackManager.setConfig(config.getSpecialAttackConfig());
        }
    }

    /**
     * Handle combat ending - notify managers to clean up.
     */
    private void handleCombatEnd() {
        prayerFlicker.onCombatEnd();
        specialAttackManager.onCombatEnd();
    }

    // ========================================================================
    // HCIM Safety Check (Section 10.1.4)
    // ========================================================================

    /**
     * Check HCIM safety conditions and trigger flee if necessary.
     * Per Section 10.1.4:
     * - Flee if 2+ enemies attacking (any area)
     * - Flee if HP < 50% with no food
     * - Flee if venom at critical levels
     * - Flee if skulled
     */
    private CombatAction checkHCIMSafety(CombatState combatState, InventoryState inventoryState, 
                                          EquipmentState equipmentState) {
        HCIMSafetyManager.FleeReason fleeReason = hcimSafetyManager.shouldFlee(config);
        
        if (fleeReason != null) {
            lastFleeReason = fleeReason;
            fleeing = true;
            
            log.warn("HCIM FLEE TRIGGERED: {}", fleeReason.getDescription());
            
            // Determine escape method
            HCIMSafetyManager.EscapeMethod escapeMethod = hcimSafetyManager.getBestEscapeMethod();
            
            return CombatAction.builder()
                    .type(CombatAction.Type.FLEE)
                    .priority(CombatAction.Priority.URGENT)
                    .fleeMethod(escapeMethod.getType().name())
                    .primarySlot(escapeMethod.getItemId())
                    .build();
        }

        // Also check risk score and log warnings
        int riskScore = hcimSafetyManager.calculateRiskScore();
        if (riskScore > 70) {
            log.warn("HCIM HIGH RISK: Score {} - Consider retreating", riskScore);
        } else if (riskScore > 50) {
            log.debug("HCIM Elevated risk: Score {}", riskScore);
        }

        return null;
    }

    // ========================================================================
    // Step 4: Target Check
    // ========================================================================

    /**
     * Check if we need to retarget.
     * Target if:
     * - Current target is dead
     * - Current target is out of range
     * - No current target but being attacked
     */
    private CombatAction checkTarget(CombatState combatState, PlayerState playerState) {
        // If we have a valid target that's alive, no action needed
        if (combatState.hasTarget()) {
            NpcSnapshot target = combatState.getTargetNpc();
            if (target != null && !target.isDead()) {
                return null;
            }
        }

        // No valid target - check if we're being attacked and should retaliate
        if (combatState.isBeingAttacked()) {
            log.debug("Being attacked without target, would retarget");
            return CombatAction.builder()
                    .type(CombatAction.Type.RETARGET)
                    .priority(CombatAction.Priority.NORMAL)
                    .build();
        }

        return null;
    }

    // ========================================================================
    // Step 5: Loot Check (Section 10.7)
    // ========================================================================

    /**
     * Check if there's loot to pick up.
     * Per Section 10.7:
     * - Scan ground items after kills
     * - Filter by whitelist and value threshold
     * - Prioritize high-value items
     */
    private CombatAction checkLoot(PlayerState playerState, InventoryState inventoryState) {
        // Only loot if we have inventory space
        if (inventoryState.isFull()) {
            return null;
        }

        // Get world state for ground items
        WorldState worldState = gameStateService.getWorldState();
        
        // Find valuable items
        var valuableItems = worldState.getValuableGroundItems(config.getLootMinValue());
        if (valuableItems.isEmpty()) {
            return null;
        }

        // Get the most valuable item
        var bestItem = valuableItems.stream()
                .max((a, b) -> Long.compare(a.getTotalGeValue(), b.getTotalGeValue()));

        if (bestItem.isPresent()) {
            log.debug("Would loot {} (value: {} gp)", 
                    bestItem.get().getName(), bestItem.get().getTotalGeValue());
            return CombatAction.builder()
                    .type(CombatAction.Type.LOOT)
                    .groundItem(bestItem.get())
                    .priority(CombatAction.Priority.LOW)
                    .build();
        }

        return null;
    }

    // ========================================================================
    // Action Execution
    // ========================================================================

    /**
     * Execute a combat action.
     * This is where actual mouse clicks and keyboard inputs would happen.
     */
    private void executeAction(CombatAction action) {
        log.debug("Executing combat action: {}", action.getType());

        switch (action.getType()) {
            case EAT:
                executeEatAction(action);
                break;
            case PRAYER_SWITCH:
                executePrayerSwitch(action);
                break;
            case SPECIAL_ATTACK:
                executeSpecialAttack(action);
                break;
            case RETARGET:
                executeRetarget(action);
                break;
            case LOOT:
                executeLoot(action);
                break;
            case GEAR_SWITCH:
                executeGearSwitch(action);
                break;
            case FLEE:
                executeFlee(action);
                break;
            case DRINK_POTION:
                executeDrinkPotion(action);
                break;
            default:
                log.warn("Unknown combat action type: {}", action.getType());
        }
    }

    private void executeEatAction(CombatAction action) {
        int currentTick = gameStateService.getCurrentTick();
        
        // Notify FoodManager of successful eat
        foodManager.onFoodEaten(currentTick);
        
        // TODO: Implement actual eating via mouse controller
        // This would:
        // 1. Click on food in inventory slot action.getPrimarySlot()
        // 2. If combo eating, wait 1 tick then click karambwan at action.getSecondarySlot()
        log.info("Would eat food at slot {} (combo: {})", 
                action.getPrimarySlot(), action.isComboEat());
    }

    private void executePrayerSwitch(CombatAction action) {
        // TODO: Implement prayer switching via mouse controller
        // This would click on the appropriate protection prayer
        if (action.getAttackStyle() == AttackStyle.UNKNOWN) {
            log.info("Deactivating protection prayer");
        } else {
            log.info("Would switch to {} protection", action.getAttackStyle());
        }
    }

    private void executeSpecialAttack(CombatAction action) {
        int currentTick = gameStateService.getCurrentTick();
        
        // Notify SpecialAttackManager of successful spec
        specialAttackManager.onSpecUsed(currentTick);
        
        // TODO: Implement special attack via mouse controller
        // This would click the special attack orb/bar
        log.info("Would use special attack");
    }

    private void executeRetarget(CombatAction action) {
        // TODO: Implement retargeting via mouse controller
        // This would click on the nearest attacking NPC
        log.info("Would retarget nearest attacker");
    }

    private void executeLoot(CombatAction action) {
        // TODO: Implement looting via mouse controller
        // This would click on the ground item
        GroundItemSnapshot item = action.getGroundItem();
        if (item != null) {
            log.info("Would loot {} at {}", item.getName(), item.getWorldPosition());
        }
    }

    private void executeGearSwitch(CombatAction action) {
        // TODO: Implement gear switching via mouse controller
        log.info("Would switch gear");
    }

    private void executeFlee(CombatAction action) {
        // CRITICAL: HCIM flee action - highest priority
        log.warn("EXECUTING FLEE: method={}, reason={}", 
                action.getFleeMethod(), lastFleeReason);
        
        // TODO: Implement flee via mouse controller
        // This would:
        // 1. Click teleport item (if available)
        // 2. Or cast teleport spell
        // 3. Or run away and logout
        // The exact implementation depends on the flee method determined by HCIMSafetyManager
        
        String method = action.getFleeMethod();
        if ("ONE_CLICK_TELEPORT".equals(method)) {
            int itemId = action.getPrimarySlot();
            log.warn("Would teleport using item {}", itemId);
        } else if ("SPELL_TELEPORT".equals(method)) {
            log.warn("Would cast teleport spell");
        } else {
            log.warn("Would run away and logout");
        }
    }

    private void executeDrinkPotion(CombatAction action) {
        // TODO: Implement potion drinking via mouse controller
        log.info("Would drink potion at slot {}", action.getPrimarySlot());
    }

    // ========================================================================
    // Pre-Combat Safety Check (HCIM)
    // ========================================================================

    /**
     * Perform pre-combat safety checks for HCIM.
     * Should be called before initiating combat.
     *
     * @return true if safe to proceed with combat
     */
    public boolean isReadyForCombat() {
        if (!config.isHcimMode()) {
            return true;
        }

        HCIMSafetyManager.SafetyCheckResult result = hcimSafetyManager.performPreCombatChecks(config);
        
        if (!result.passed()) {
            log.warn("HCIM pre-combat checks FAILED:");
            for (String failure : result.getFailures()) {
                log.warn("  - {}", failure);
            }
            return false;
        }

        int riskScore = hcimSafetyManager.calculateRiskScore();
        log.info("HCIM pre-combat checks PASSED (risk score: {})", riskScore);
        return true;
    }

    /**
     * Get the current HCIM risk score.
     *
     * @return risk score (0-100), or -1 if not in HCIM mode
     */
    public int getCurrentRiskScore() {
        if (!config.isHcimMode()) {
            return -1;
        }
        return hcimSafetyManager.calculateRiskScore();
    }

    // ========================================================================
    // Control Methods
    // ========================================================================

    /**
     * Start combat management.
     */
    public void start() {
        enabled = true;
        log.info("CombatManager started");
    }

    /**
     * Stop combat management.
     */
    public void stop() {
        enabled = false;
        inCombat = false;
        log.info("CombatManager stopped");
    }

    /**
     * Reset combat state (e.g., on logout).
     */
    public void reset() {
        enabled = false;
        inCombat = false;
        fleeing = false;
        lastFleeReason = null;
        
        // Reset delegated managers
        foodManager.reset();
        prayerFlicker.reset();
        specialAttackManager.reset();
        
        log.info("CombatManager and delegated managers reset");
    }

    /**
     * Get the HCIM safety manager for direct access to safety checks.
     *
     * @return the HCIM safety manager
     */
    public HCIMSafetyManager getHcimSafetyManager() {
        return hcimSafetyManager;
    }
}

