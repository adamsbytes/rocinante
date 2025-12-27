package com.rocinante.combat;

import com.rocinante.core.GameStateService;
import com.rocinante.input.GroundItemClickHelper;
import com.rocinante.input.InventoryClickHelper;
import com.rocinante.input.RobotMouseController;
import com.rocinante.input.MenuHelper;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.WidgetClickHelper;

import java.awt.event.KeyEvent;

import com.rocinante.state.AggressorInfo;
import com.rocinante.state.CombatState;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.GroundItemSnapshot;
import com.rocinante.state.InventoryState;
import com.rocinante.state.NpcSnapshot;
import com.rocinante.state.PoisonState;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
// Use explicit import to avoid conflict with com.rocinante.combat.AttackStyle
import com.rocinante.state.AttackStyle;
import com.rocinante.timing.HumanTimer;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Prayer;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

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
    private final InventoryClickHelper inventoryClickHelper;
    private final GroundItemClickHelper groundItemClickHelper;
    private final WidgetClickHelper widgetClickHelper;
    private final RobotKeyboardController keyboardController;
    private final MenuHelper menuHelper;
    private final RobotMouseController mouseController;
    private final GearSwitcher gearSwitcher;
    private final HumanTimer humanTimer;
    private final HCIMSafetyManager hcimSafetyManager;
    private final FoodManager foodManager;
    private final PrayerFlicker prayerFlicker;
    private final SpecialAttackManager specialAttackManager;
    private final Random random = new Random();

    // ========================================================================
    // Widget Constants (from RuneLite InterfaceID)
    // ========================================================================

    /**
     * Prayer book widget group ID.
     * Per RuneLite InterfaceID.Prayerbook (group 541 = 0x021d).
     */
    private static final int PRAYERBOOK_GROUP_ID = 541;

    /**
     * Protect from Magic prayer widget child ID (prayer 17 in book).
     */
    private static final int PRAYER_PROTECT_MAGIC_CHILD = 25; // 0x021d_0019 = child 25 (0x19)

    /**
     * Protect from Missiles (Ranged) prayer widget child ID (prayer 18 in book).
     */
    private static final int PRAYER_PROTECT_MISSILES_CHILD = 26; // 0x021d_001a = child 26 (0x1a)

    /**
     * Protect from Melee prayer widget child ID (prayer 19 in book).
     */
    private static final int PRAYER_PROTECT_MELEE_CHILD = 27; // 0x021d_001b = child 27 (0x1b)

    /**
     * Orbs widget group ID (minimap orbs).
     * Per RuneLite InterfaceID.Orbs (group 160 = 0x00a0).
     */
    private static final int ORBS_GROUP_ID = 160;

    /**
     * Special attack orb widget child ID.
     * Per RuneLite InterfaceID.Orbs.ORB_SPECENERGY (0x00a0_0022 = child 34).
     */
    private static final int SPEC_ORB_CHILD = 34;

    /**
     * Run energy orb widget child ID.
     * Per RuneLite InterfaceID.Orbs.ORB_RUNENERGY.
     */
    private static final int RUN_ORB_CHILD = 27;

    /**
     * Logout button widget group ID (fixed mode).
     */
    private static final int LOGOUT_TAB_GROUP_ID = 182;

    /**
     * Logout button child ID.
     */
    private static final int LOGOUT_BUTTON_CHILD = 8;

    /**
     * Combo eat delay in ticks (karambwan can be eaten 1 tick after regular food).
     */
    private static final int COMBO_EAT_DELAY_TICKS = 1;

    /**
     * Milliseconds per game tick (approximately 600ms).
     */
    private static final int MS_PER_TICK = 600;

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
            InventoryClickHelper inventoryClickHelper,
            GroundItemClickHelper groundItemClickHelper,
            WidgetClickHelper widgetClickHelper,
            RobotKeyboardController keyboardController,
            MenuHelper menuHelper,
            RobotMouseController mouseController,
            GearSwitcher gearSwitcher,
            HumanTimer humanTimer,
            HCIMSafetyManager hcimSafetyManager,
            FoodManager foodManager,
            PrayerFlicker prayerFlicker,
            SpecialAttackManager specialAttackManager) {
        this.client = client;
        this.gameStateService = gameStateService;
        this.inventoryClickHelper = inventoryClickHelper;
        this.groundItemClickHelper = groundItemClickHelper;
        this.widgetClickHelper = widgetClickHelper;
        this.keyboardController = keyboardController;
        this.menuHelper = menuHelper;
        this.mouseController = mouseController;
        this.gearSwitcher = gearSwitcher;
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

        // Step 1.5: Check poison/venom → cure if poisoned and have antipoison
        CombatAction cureAction = checkPoisonCure(combatState, inventoryState);
        if (cureAction != null) {
            executeAction(cureAction);
            return; // Curing takes priority
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
    // Poison/Venom Cure Check
    // ========================================================================

    /**
     * Common antipoison item IDs.
     */
    private static final int[] ANTIPOISON_IDS = {
            175, 177, 179, 181,       // Antipoison(4-1)
            2448, 2450, 2452, 2454,   // Superantipoison(4-1)
            5952, 5954, 5956, 5958,   // Antidote+(4-1)
            5945, 5947, 5949, 5951    // Antidote++(4-1)
    };

    /**
     * Anti-venom item IDs.
     */
    private static final int[] ANTIVENOM_IDS = {
            12907, 12909, 12911, 12913,  // Anti-venom(4-1)
            12917, 12919, 12921, 12923   // Anti-venom+(4-1)
    };

    /**
     * Check if player is poisoned/venomed and should drink cure.
     * Per REQUIREMENTS.md, venom takes priority and should trigger flee at critical levels.
     */
    private CombatAction checkPoisonCure(CombatState combatState, InventoryState inventoryState) {
        PoisonState poisonState = combatState.getPoisonState();
        
        if (!poisonState.isPoisoned() && !poisonState.isVenomed()) {
            return null; // Not poisoned
        }

        // Check for anti-venom first if venomed
        if (poisonState.isVenomed()) {
            int antivenomSlot = findAntivenom(inventoryState);
            if (antivenomSlot >= 0) {
                log.debug("Drinking anti-venom for venom damage {}", poisonState.getCurrentDamage());
                return createPotionAction(antivenomSlot, inventoryState);
            }
            // No anti-venom - log warning (HCIM safety will handle flee)
            log.warn("Venomed with no anti-venom! Damage: {}", poisonState.getCurrentDamage());
        }

        // Check for antipoison if poisoned
        int antipoisonSlot = findAntipoison(inventoryState);
        if (antipoisonSlot >= 0) {
            log.debug("Drinking antipoison for poison damage {}", poisonState.getCurrentDamage());
            return createPotionAction(antipoisonSlot, inventoryState);
        }

        // No cure available
        if (poisonState.isPoisoned()) {
            log.debug("Poisoned with no antipoison, damage: {}", poisonState.getCurrentDamage());
        }

        return null;
    }

    /**
     * Find an antipoison in inventory.
     */
    private int findAntipoison(InventoryState inventoryState) {
        for (int id : ANTIPOISON_IDS) {
            int slot = inventoryState.getSlotOf(id);
            if (slot >= 0) return slot;
        }
        return -1;
    }

    /**
     * Find an anti-venom in inventory.
     */
    private int findAntivenom(InventoryState inventoryState) {
        for (int id : ANTIVENOM_IDS) {
            int slot = inventoryState.getSlotOf(id);
            if (slot >= 0) return slot;
        }
        return -1;
    }

    /**
     * Create a potion drinking action.
     */
    private CombatAction createPotionAction(int slot, InventoryState inventoryState) {
        int itemId = inventoryState.getItemInSlot(slot)
                .map(item -> item.getId())
                .orElse(-1);
        
        return CombatAction.builder()
                .type(CombatAction.Type.DRINK_POTION)
                .primarySlot(slot)
                .potionId(itemId)
                .itemId(itemId)
                .priority(CombatAction.Priority.HIGH)
                .build();
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
        int primarySlot = action.getPrimarySlot();
        
        if (primarySlot < 0) {
            log.warn("Invalid primary slot for eat action: {}", primarySlot);
            return;
        }
        
        // Click on food in inventory
        log.debug("Eating food at slot {} (combo: {})", primarySlot, action.isComboEat());
        
        CompletableFuture<Boolean> eatFuture = inventoryClickHelper.executeClick(primarySlot, "Eating food");
        
        eatFuture.thenAccept(success -> {
            if (!success) {
                log.warn("Failed to click food at slot {}", primarySlot);
                return;
            }
            
            // Notify FoodManager of successful eat and track brew/restore
            int itemId = action.getItemId();
            foodManager.onFoodEaten(currentTick);
            
            // Track Saradomin brew usage
            if (foodManager.isSaradominBrew(itemId)) {
                foodManager.onBrewUsed();
            }
            // Track super restore usage
            else if (foodManager.isSuperRestore(itemId)) {
                foodManager.onRestoreUsed();
            }
            
            // If combo eating, wait 1 tick then click karambwan at secondary slot
            // Per Section 10.1.2: support food + karambwan tick eating when low
            if (action.isComboEat() && action.getSecondarySlot() >= 0) {
                long comboDelayMs = COMBO_EAT_DELAY_TICKS * MS_PER_TICK;
                
                humanTimer.sleep(comboDelayMs).thenRun(() -> {
                    log.debug("Combo eating karambwan at slot {}", action.getSecondarySlot());
                    inventoryClickHelper.executeClick(action.getSecondarySlot(), "Combo eating karambwan")
                            .thenAccept(comboSuccess -> {
                                if (comboSuccess) {
                                    log.debug("Combo eat completed successfully");
                                } else {
                                    log.warn("Failed to click karambwan at slot {}", action.getSecondarySlot());
                                }
                            });
                });
            }
        });
    }

    /**
     * Map a Prayer enum to its widget child ID.
     */
    private int getPrayerWidgetChild(Prayer prayer) {
        if (prayer == null) return PRAYER_PROTECT_MELEE_CHILD;
        
        switch (prayer) {
            case PROTECT_FROM_MAGIC:
                return PRAYER_PROTECT_MAGIC_CHILD;
            case PROTECT_FROM_MISSILES:
                return PRAYER_PROTECT_MISSILES_CHILD;
            case PROTECT_FROM_MELEE:
                return PRAYER_PROTECT_MELEE_CHILD;
            default:
                // For other prayers, calculate based on prayer ordinal
                // Prayers are ordered: 0=Thick Skin, ..., 17=Protect Magic, 18=Protect Missiles, 19=Protect Melee
                // Widget children start at 4 for first prayer
                return 4 + prayer.ordinal();
        }
    }

    private void executePrayerSwitch(CombatAction action) {
        AttackStyle style = action.getAttackStyle();
        
        // Determine which prayer widget child to click based on attack style
        // Per Section 10.2 and 10.6.2: Protection prayers for melee/ranged/magic
        int prayerChildId;
        String prayerName;
        
        switch (style) {
            case MAGIC:
                prayerChildId = PRAYER_PROTECT_MAGIC_CHILD;
                prayerName = "Protect from Magic";
                break;
            case RANGED:
                prayerChildId = PRAYER_PROTECT_MISSILES_CHILD;
                prayerName = "Protect from Missiles";
                break;
            case MELEE:
                prayerChildId = PRAYER_PROTECT_MELEE_CHILD;
                prayerName = "Protect from Melee";
                break;
            case UNKNOWN:
            default:
                // Deactivate - click currently active protection prayer to toggle off
                Prayer activePrayer = prayerFlicker.getActiveProtectionPrayer();
                if (activePrayer == null) {
                    log.debug("No active protection prayer to deactivate");
                    return;
                }
                // Map active prayer to widget child ID
                prayerChildId = getPrayerWidgetChild(activePrayer);
                prayerName = "Deactivate " + activePrayer.name();
                log.debug("Deactivating active prayer: {}", activePrayer.name());
                break;
        }
        
        log.debug("Switching protection prayer: {} (widget {}:{})", 
                prayerName, PRAYERBOOK_GROUP_ID, prayerChildId);
        
        // Check if prayer tab is open
        Widget prayerWidget = client.getWidget(PRAYERBOOK_GROUP_ID, prayerChildId);
        
        if (prayerWidget == null || prayerWidget.isHidden()) {
            // Prayer tab not open - press F5 to open it first
            log.debug("Prayer widget not visible, opening prayer tab with F5");
            
            // Capture final vars for lambda
            final int finalPrayerChildId = prayerChildId;
            final String finalPrayerName = prayerName;
            
            // Press F5 and then wait for tab to open
            keyboardController.pressKey(KeyEvent.VK_F5).thenCompose(v -> {
                // Wait 50-100ms for tab to open
                long tabOpenDelay = 50 + random.nextInt(50);
                return humanTimer.sleep(tabOpenDelay);
            }).thenRun(() -> {
                widgetClickHelper.clickWidget(PRAYERBOOK_GROUP_ID, finalPrayerChildId, finalPrayerName)
                        .thenAccept(success -> {
                            if (success) {
                                log.debug("Prayer switch to {} completed (after tab open)", finalPrayerName);
                            } else {
                                log.warn("Failed to switch prayer to {} after tab open", finalPrayerName);
                            }
                        });
            });
            return;
        }
        
        widgetClickHelper.clickWidget(PRAYERBOOK_GROUP_ID, prayerChildId, prayerName)
                .thenAccept(success -> {
                    if (success) {
                        log.debug("Prayer switch to {} completed", prayerName);
                    } else {
                        log.warn("Failed to switch prayer to {}", prayerName);
                    }
                })
                .exceptionally(e -> {
                    log.error("Prayer switch error for {}: {}", prayerName, e.getMessage());
                    return null;
                });
    }

    private void executeSpecialAttack(CombatAction action) {
        int currentTick = gameStateService.getCurrentTick();
        
        log.debug("Executing special attack (energy cost: {})", action.getPrimarySlot());
        
        // Per Section 10.4 and 10.6.3: Click the special attack orb in minimap area
        // Widget: InterfaceID.Orbs.ORB_SPECENERGY (group 160, child 34)
        widgetClickHelper.clickWidget(ORBS_GROUP_ID, SPEC_ORB_CHILD, "Special attack orb")
                .thenAccept(success -> {
                    if (success) {
                        // Notify SpecialAttackManager of successful spec click
                        specialAttackManager.onSpecUsed(currentTick);
                        log.debug("Special attack orb clicked successfully");
                        
                        // Check if we should stack specs (e.g., DDS)
                        // Per Section 10.6.3: Some specs can be stacked by switching rapidly
                        int specCount = action.getSecondarySlot();
                        if (specCount > 1) {
                            log.debug("Stacking {} special attacks", specCount);
                            // Additional spec clicks would be handled by subsequent combat ticks
                        }
                    } else {
                        log.warn("Failed to click special attack orb");
                    }
                })
                .exceptionally(e -> {
                    log.error("Special attack error: {}", e.getMessage());
                    return null;
                });
    }

    private void executeRetarget(CombatAction action) {
        // Per Section 10.1.1: Retarget the nearest attacking NPC
        CombatState combatState = gameStateService.getCombatState();
        
        // Get the most dangerous or nearest aggressor
        Optional<AggressorInfo> targetOpt = combatState.getMostDangerousAggressor();
        
        if (targetOpt.isEmpty()) {
            // Fall back to any aggressor
            if (combatState.getAggressorCount() > 0) {
                targetOpt = Optional.of(combatState.getAggressiveNpcs().get(0));
            }
        }
        
        if (targetOpt.isEmpty()) {
            log.debug("No aggressor found to retarget");
            return;
        }
        
        AggressorInfo aggressor = targetOpt.get();
        int npcIndex = aggressor.getNpcIndex();
        
        // Find the NPC in the client's NPC array (same pattern as CombatTask)
        NPC targetNpc = null;
        for (NPC npc : client.getNpcs()) {
            if (npc != null && npc.getIndex() == npcIndex) {
                targetNpc = npc;
                break;
            }
        }
        
        if (targetNpc == null) {
            log.warn("Target NPC (index {}) not found in client NPC list", npcIndex);
            return;
        }
        
        // Calculate click point on NPC using convex hull or model center
        Point clickPoint = calculateNpcClickPoint(targetNpc);
        if (clickPoint == null) {
            log.warn("Cannot calculate click point for NPC {}", aggressor.getNpcName());
            return;
        }
        
        log.debug("Retargeting {} at canvas point ({}, {})", 
                aggressor.getNpcName(), clickPoint.x, clickPoint.y);
        
        // Move mouse and click on the NPC
        mouseController.moveToCanvas(clickPoint.x, clickPoint.y)
                .thenCompose(v -> mouseController.click())
                .thenRun(() -> {
                    log.debug("Retarget click on {} completed", aggressor.getNpcName());
                })
                .exceptionally(e -> {
                    log.error("Retarget click failed for {}: {}", aggressor.getNpcName(), e.getMessage());
                    return null;
                });
    }

    /**
     * Calculate the canvas point to click on an NPC.
     * Uses the NPC's convex hull for accurate click targeting.
     * Falls back to model center if hull unavailable.
     *
     * @param npc the NPC to click
     * @return canvas point, or null if cannot be calculated
     */
    @Nullable
    private Point calculateNpcClickPoint(NPC npc) {
        // Try to use convex hull for accurate clickable area
        Shape clickableArea = npc.getConvexHull();
        
        if (clickableArea != null) {
            Rectangle bounds = clickableArea.getBounds();
            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                // Calculate random point within bounds using Gaussian distribution
                // Per Section 3.1.2: Position variance with 2D Gaussian
                int centerX = bounds.x + bounds.width / 2;
                int centerY = bounds.y + bounds.height / 2;
                
                // Apply Gaussian offset (σ = 25% of dimension)
                double offsetX = random.nextGaussian() * (bounds.width / 4.0);
                double offsetY = random.nextGaussian() * (bounds.height / 4.0);
                
                int clickX = centerX + (int) offsetX;
                int clickY = centerY + (int) offsetY;
                
                // Clamp to bounds
                clickX = Math.max(bounds.x, Math.min(clickX, bounds.x + bounds.width));
                clickY = Math.max(bounds.y, Math.min(clickY, bounds.y + bounds.height));
                
                return new Point(clickX, clickY);
            }
        }
        
        // Fall back to local point projection
        net.runelite.api.coords.LocalPoint localPoint = npc.getLocalLocation();
        if (localPoint != null) {
            net.runelite.api.Point canvasPoint = net.runelite.api.Perspective.localToCanvas(
                    client, localPoint, client.getPlane(), npc.getLogicalHeight() / 2);
            if (canvasPoint != null) {
                return new Point(canvasPoint.getX(), canvasPoint.getY());
            }
        }
        
        // Last resort: use canvas center (not ideal but better than nothing)
        log.warn("Using canvas center as fallback for NPC click point");
        java.awt.Dimension canvasSize = client.getCanvas().getSize();
        return new Point(canvasSize.width / 2, canvasSize.height / 2);
    }

    private void executeLoot(CombatAction action) {
        GroundItemSnapshot item = action.getGroundItem();
        if (item == null) {
            log.warn("Loot action has no ground item");
            return;
        }

        log.debug("Looting {} at {}", item.getName(), item.getWorldPosition());

        groundItemClickHelper.clickGroundItem(item.getWorldPosition(), item.getId(), item.getName())
                .exceptionally(e -> {
                    log.error("Failed to loot {}: {}", item.getName(), e.getMessage());
                    return false;
                });
    }

    private void executeGearSwitch(CombatAction action) {
        String gearSetName = action.getGearSetName();
        if (gearSetName == null || gearSetName.isEmpty()) {
            log.warn("Gear switch action has no gear set name");
            return;
        }

        log.debug("Switching to gear set: {}", gearSetName);

        gearSwitcher.switchTo(gearSetName)
                .thenAccept(success -> {
                    if (success) {
                        log.debug("Gear switch to '{}' completed", gearSetName);
                    } else {
                        log.warn("Gear switch to '{}' failed", gearSetName);
                    }
                })
                .exceptionally(e -> {
                    log.error("Gear switch to '{}' error: {}", gearSetName, e.getMessage());
                    return null;
                });
    }

    private void executeFlee(CombatAction action) {
        // CRITICAL: HCIM flee action - highest priority
        // Per Section 10.1.4 and 12A.3.3: Emergency escape protocol
        log.warn("EXECUTING FLEE: method={}, reason={}", 
                action.getFleeMethod(), lastFleeReason);
        
        String method = action.getFleeMethod();
        
        if ("ONE_CLICK_TELEPORT".equals(method)) {
            executeOneClickTeleport(action);
        } else if ("SPELL_TELEPORT".equals(method)) {
            executeSpellTeleport();
        } else {
            // RUN_AND_LOGOUT or fallback
            executeRunAndLogout();
        }
    }

    /**
     * Execute one-click teleport using an item (ring, amulet, tablet, etc.).
     * Per Section 12A.3.3: Priority escape method.
     */
    private void executeOneClickTeleport(CombatAction action) {
        int itemId = action.getPrimarySlot();
        InventoryState inventory = gameStateService.getInventoryState();
        
        // Find the teleport item slot
        int slot = inventory.getSlotOf(itemId);
        
        if (slot >= 0) {
            log.warn("FLEE: One-click teleport using item {} at slot {}", itemId, slot);
            
            inventoryClickHelper.executeClick(slot, "EMERGENCY TELEPORT")
                    .thenAccept(success -> {
                        if (success) {
                            log.warn("FLEE: Teleport item clicked successfully");
                            fleeing = false; // Will be re-evaluated on next tick
                        } else {
                            log.error("FLEE: Failed to click teleport item - trying backup method");
                            executeRunAndLogout();
                        }
                    })
                    .exceptionally(e -> {
                        log.error("FLEE: Teleport item click error - trying backup method: {}", e.getMessage());
                        executeRunAndLogout();
                        return null;
                    });
        } else {
            // Item not in inventory - check if equipped (jewelry)
            EquipmentState equipment = gameStateService.getEquipmentState();
            if (equipment.hasEquipped(itemId)) {
                log.warn("FLEE: Teleport item {} is equipped - using equipment menu", itemId);
                executeEquippedTeleport(itemId, equipment);
            } else {
                log.error("FLEE: Teleport item {} not found - trying backup method", itemId);
                executeRunAndLogout();
            }
        }
    }

    /**
     * Execute teleport using equipped jewelry via menu interaction.
     * Per Section 12A.3.3: Handles ring of wealth, amulet of glory, etc.
     */
    private void executeEquippedTeleport(int itemId, EquipmentState equipment) {
        // Equipment widget group and slot constants
        int equipmentGroup = 387; // Equipment tab widget group
        
        // Determine which slot the item is in
        int slotChildId = getEquipmentSlotChildId(itemId, equipment);
        if (slotChildId < 0) {
            log.error("FLEE: Could not determine equipment slot for item {}", itemId);
            executeRunAndLogout();
            return;
        }
        
        // Get the widget bounds for the equipment slot
        Widget slotWidget = client.getWidget(equipmentGroup, slotChildId);
        if (slotWidget == null || slotWidget.isHidden()) {
            log.error("FLEE: Equipment slot widget not visible - trying run and logout");
            executeRunAndLogout();
            return;
        }
        
        Rectangle hitbox = slotWidget.getBounds();
        
        // Use menu helper to select teleport option
        // Common teleport options: "Edgeville", "Grand Exchange", "Duel Arena", etc.
        // The exact option depends on the item - try common ones
        menuHelper.selectMenuEntry(hitbox, "Edgeville")
                .thenCompose(success -> {
                    if (success) {
                        log.warn("FLEE: Equipped teleport to Edgeville successful");
                        return CompletableFuture.completedFuture(true);
                    }
                    // Try Grand Exchange as fallback
                    return menuHelper.selectMenuEntry(hitbox, "Grand Exchange");
                })
                .thenAccept(success -> {
                    if (success) {
                        log.warn("FLEE: Equipped teleport completed");
                        fleeing = false;
                    } else {
                        log.error("FLEE: All equipped teleport options failed - trying run and logout");
                        executeRunAndLogout();
                    }
                })
                .exceptionally(e -> {
                    log.error("FLEE: Equipped teleport error: {}", e.getMessage());
                    executeRunAndLogout();
                    return null;
                });
    }

    /**
     * Get the equipment widget child ID for the slot containing the item.
     */
    private int getEquipmentSlotChildId(int itemId, EquipmentState equipment) {
        // Equipment widget child IDs in group 387
        // These map equipment slot constants to widget child IDs
        Optional<Integer> slotOpt = equipment.getSlotOf(itemId);
        if (slotOpt.isEmpty()) {
            return -1;
        }
        
        int slot = slotOpt.get();
        
        // Map equipment slot to widget child ID
        return switch (slot) {
            case EquipmentState.SLOT_RING -> 13;
            case EquipmentState.SLOT_AMULET -> 6;
            case EquipmentState.SLOT_GLOVES -> 12;
            case EquipmentState.SLOT_CAPE -> 4;
            case EquipmentState.SLOT_WEAPON -> 8;
            default -> -1;
        };
    }

    /**
     * Execute teleport via spellbook.
     * Per Section 12A.3.3: Secondary escape method.
     */
    private void executeSpellTeleport() {
        log.warn("FLEE: Attempting spell teleport");
        
        // Home teleport is always available (though slow)
        // Standard spellbook teleports require runes
        // For HCIM safety, we prefer the fastest available option
        
        // Spellbook widget group: 218 (standard spellbook)
        // Home teleport is typically at a known position
        // Varrock teleport = child 19, Lumbridge = child 23, etc.
        
        // For now, attempt Home Teleport as it's always available
        // Widget group 218, child 5 for Home Teleport (standard spellbook)
        int spellbookGroup = 218;
        int homeTeleportChild = 5;
        
        widgetClickHelper.clickWidget(spellbookGroup, homeTeleportChild, "EMERGENCY HOME TELEPORT")
                .thenAccept(success -> {
                    if (success) {
                        log.warn("FLEE: Home teleport clicked - waiting for cast");
                    } else {
                        log.error("FLEE: Failed to click home teleport - trying run and logout");
                        executeRunAndLogout();
                    }
                })
                .exceptionally(e -> {
                    log.error("FLEE: Spell teleport error - trying run and logout: {}", e.getMessage());
                    executeRunAndLogout();
                    return null;
                });
    }

    /**
     * Execute run away and logout as last resort.
     * Per Section 12A.3.3: Final escape method.
     */
    private void executeRunAndLogout() {
        log.warn("FLEE: Executing run and logout protocol");
        
        // Step 1: Enable running if we have energy (always try since we can't easily check run state)
        PlayerState player = gameStateService.getPlayerState();
        
        CompletableFuture<Void> runFuture;
        if (player.getRunEnergy() > 10) {
            // Try to enable run mode - clicking the orb toggles it
            log.warn("FLEE: Attempting to enable run mode (energy: {}%)", player.getRunEnergy());
            runFuture = widgetClickHelper.clickWidget(ORBS_GROUP_ID, RUN_ORB_CHILD, "Enable run")
                    .thenAccept(success -> {
                        if (!success) {
                            log.warn("FLEE: Failed to enable run, continuing with logout");
                        }
                    });
        } else {
            log.warn("FLEE: Low run energy ({}%), skipping run toggle", player.getRunEnergy());
            runFuture = CompletableFuture.completedFuture(null);
        }
        
        // Step 2: Click logout button after brief delay
        runFuture.thenCompose(v -> humanTimer.sleep(100)) // Brief delay
                .thenCompose(v -> {
                    log.warn("FLEE: Clicking logout button");
                    return widgetClickHelper.clickWidget(LOGOUT_TAB_GROUP_ID, LOGOUT_BUTTON_CHILD, "EMERGENCY LOGOUT");
                })
                .thenAccept(success -> {
                    if (success) {
                        log.warn("FLEE: Logout button clicked");
                    } else {
                        // Try alternative logout: press F10 then click logout
                        log.error("FLEE: Failed to click logout button - attempting escape key");
                        // Could try pressing Escape or other logout methods here
                    }
                })
                .exceptionally(e -> {
                    log.error("FLEE: Run and logout failed: {}", e.getMessage());
                    return null;
                });
    }

    private void executeDrinkPotion(CombatAction action) {
        int slot = action.getPrimarySlot();
        
        if (slot < 0) {
            log.warn("Invalid slot for drink potion action: {}", slot);
            return;
        }
        
        log.debug("Drinking potion at slot {}", slot);
        
        inventoryClickHelper.executeClick(slot, "Drinking potion")
                .thenAccept(success -> {
                    if (success) {
                        log.debug("Potion drink completed at slot {}", slot);
                    } else {
                        log.warn("Failed to drink potion at slot {}", slot);
                    }
                });
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

