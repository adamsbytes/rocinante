package com.rocinante.combat;

import com.rocinante.core.GameStateService;
import com.rocinante.state.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.WorldType;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HCIM Safety Manager implementing Section 10.1.4 HCIM Safety Protocol.
 *
 * <p>Provides:
 * <ul>
 *   <li>Pre-combat safety checks</li>
 *   <li>During-combat monitoring</li>
 *   <li>Risk assessment scoring</li>
 *   <li>Emergency escape protocols</li>
 *   <li>Ring of Life management</li>
 * </ul>
 */
@Slf4j
@Singleton
public class HCIMSafetyManager {

    private final Client client;
    private final GameStateService gameStateService;

    // Ring of Life item IDs
    private static final int RING_OF_LIFE_ID = ItemID.RING_OF_LIFE;
    private static final int RING_OF_LIFE_I_ID = 21129;

    // Emergency teleport items (one-click escapes)
    private static final Set<Integer> EMERGENCY_TELEPORT_ITEMS = new HashSet<>();
    static {
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.RING_OF_DUELING8);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.RING_OF_DUELING7);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.RING_OF_DUELING6);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.RING_OF_DUELING5);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.RING_OF_DUELING4);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.RING_OF_DUELING3);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.RING_OF_DUELING2);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.RING_OF_DUELING1);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.ECTOPHIAL);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.ROYAL_SEED_POD);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.TELEPORT_TO_HOUSE);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.AMULET_OF_GLORY6);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.AMULET_OF_GLORY5);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.AMULET_OF_GLORY4);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.AMULET_OF_GLORY3);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.AMULET_OF_GLORY2);
        EMERGENCY_TELEPORT_ITEMS.add(ItemID.AMULET_OF_GLORY1);
    }

    @Getter
    private boolean hcimModeActive = false;

    @Inject
    public HCIMSafetyManager(Client client, GameStateService gameStateService) {
        this.client = client;
        this.gameStateService = gameStateService;
        log.info("HCIMSafetyManager initialized");
    }

    // ========================================================================
    // Pre-Combat Checks (Section 10.1.4)
    // ========================================================================

    /**
     * Perform all pre-combat safety checks for HCIM.
     *
     * @param config the combat configuration
     * @return SafetyCheckResult with pass/fail status and reasons
     */
    public SafetyCheckResult performPreCombatChecks(CombatConfig config) {
        if (!config.isHcimMode()) {
            return SafetyCheckResult.createPassed();
        }

        SafetyCheckResult result = new SafetyCheckResult();

        InventoryState inventory = gameStateService.getInventoryState();
        EquipmentState equipment = gameStateService.getEquipmentState();
        PlayerState player = gameStateService.getPlayerState();

        // Check 1: Minimum food count
        int foodCount = inventory.countFood();
        int minFood = config.getHcimMinFoodCount();
        if (foodCount < minFood) {
            result.addFailure("Insufficient food: " + foodCount + "/" + minFood);
        }

        // Check 2: Ring of Life equipped
        if (config.isHcimRequireRingOfLife() && !isRingOfLifeEquipped(equipment)) {
            result.addFailure("Ring of Life not equipped");
        }

        // Check 3: Emergency teleport available
        if (config.isHcimRequireEmergencyTeleport() && !hasEmergencyTeleport(inventory, equipment)) {
            result.addFailure("No emergency teleport available");
        }

        // Check 4: Not skulled (HCIM should NEVER be skulled)
        if (player.isSkulled()) {
            result.addFailure("CRITICAL: Player is skulled - flee immediately");
        }

        // Check 5: Check for PvP/Deadman worlds
        if (isInPvPWorld()) {
            result.addFailure("CRITICAL: In PvP world - HCIM must logout");
        }

        return result;
    }

    /**
     * Check if Ring of Life is equipped.
     * Per Section 10.1.4: Verify ItemID.RING_OF_LIFE (2570) equipped.
     */
    public boolean isRingOfLifeEquipped(EquipmentState equipment) {
        return equipment.hasEquipped(RING_OF_LIFE_ID) || 
               equipment.hasEquipped(RING_OF_LIFE_I_ID);
    }

    /**
     * Check if player has an emergency teleport available.
     * Checks inventory and equipped jewelry.
     */
    public boolean hasEmergencyTeleport(InventoryState inventory, EquipmentState equipment) {
        // Check inventory
        for (int itemId : EMERGENCY_TELEPORT_ITEMS) {
            if (inventory.hasItem(itemId)) {
                return true;
            }
        }

        // Check equipped items (amulets, rings)
        for (int itemId : EMERGENCY_TELEPORT_ITEMS) {
            if (equipment.hasEquipped(itemId)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if in a PvP or Deadman world.
     * Per Section 10.6.6: Block all PvP worlds for HCIM.
     *
     * @return true if in a PvP, Deadman, or High-Risk world
     */
    public boolean isInPvPWorld() {
        var worldTypes = client.getWorldType();
        if (worldTypes == null || worldTypes.isEmpty()) {
            return false;
        }
        
        // Check for PvP-related world types
        // WorldType.isPvpWorld checks for PVP and DEADMAN
        if (WorldType.isPvpWorld(worldTypes)) {
            return true;
        }
        
        // Also check for HIGH_RISK worlds which are dangerous for HCIM
        if (worldTypes.contains(WorldType.HIGH_RISK)) {
            return true;
        }
        
        return false;
    }

    // ========================================================================
    // During-Combat Monitoring (Section 10.1.4)
    // ========================================================================

    /**
     * Check if HCIM should flee based on current combat state.
     *
     * @param config the combat configuration
     * @return FleeReason if should flee, null otherwise
     */
    public FleeReason shouldFlee(CombatConfig config) {
        if (!config.isHcimMode()) {
            return null;
        }

        PlayerState player = gameStateService.getPlayerState();
        CombatState combat = gameStateService.getCombatState();
        InventoryState inventory = gameStateService.getInventoryState();

        // Check 1: Multi-combat pile-up (2+ enemies attacking)
        // Per Section 10.6.5: HCIM flee threshold is 2+ NPCs
        if (combat.isPiledUp()) {
            return FleeReason.MULTI_COMBAT_PILEUP;
        }

        // Check 2: Low HP with no food
        // Per Section 10.1.4: Flee if HP < 50% with no food
        double healthPercent = player.getHealthPercent();
        boolean hasFood = inventory.hasFood();
        if (healthPercent < config.getHcimFleeThreshold() && !hasFood) {
            return FleeReason.LOW_HP_NO_FOOD;
        }

        // Check 3: Venom at critical levels
        // Per Section 10.6.7: Flee if venom damage >= 16
        if (combat.isVenomCritical()) {
            return FleeReason.VENOM_CRITICAL;
        }

        // Check 4: Skulled (should never happen to HCIM)
        if (player.isSkulled()) {
            return FleeReason.SKULLED;
        }

        // Check 5: Ring of Life no longer equipped (may have been consumed)
        EquipmentState equipment = gameStateService.getEquipmentState();
        if (config.isHcimRequireRingOfLife() && !isRingOfLifeEquipped(equipment)) {
            return FleeReason.RING_OF_LIFE_MISSING;
        }

        return null;
    }

    /**
     * Calculate risk score for current combat encounter.
     * Higher score = more dangerous.
     *
     * @return risk score (0-100)
     */
    public int calculateRiskScore() {
        int score = 0;

        PlayerState player = gameStateService.getPlayerState();
        CombatState combat = gameStateService.getCombatState();
        InventoryState inventory = gameStateService.getInventoryState();

        // Factor 1: Health percentage (0-25 points)
        double healthPercent = player.getHealthPercent();
        score += (int) ((1.0 - healthPercent) * 25);

        // Factor 2: Food count (0-25 points)
        int foodCount = inventory.countFood();
        if (foodCount == 0) {
            score += 25;
        } else if (foodCount < 4) {
            score += 25 - (foodCount * 5);
        }

        // Factor 3: Number of aggressors (0-30 points)
        int aggressors = combat.getAggressorCount();
        score += Math.min(30, aggressors * 15);

        // Factor 4: Poison/venom status (0-20 points)
        if (combat.isVenomed()) {
            score += combat.isVenomCritical() ? 20 : 10;
        } else if (combat.isPoisoned()) {
            score += 5;
        }

        // Factor 5: Multi-combat area (bonus points)
        if (combat.isInMultiCombat()) {
            score += 10;
        }

        return Math.min(100, score);
    }

    // ========================================================================
    // Emergency Escape Protocol (Section 10.1.4)
    // ========================================================================

    /**
     * Determine the best escape method.
     * Per Section 10.1.4, priority order:
     * 1. Manual teleport (one-click items)
     * 2. Standard teleport spell
     * 3. Run to safe tile + logout
     * 4. Ring of Life as last resort
     *
     * @return recommended escape method
     */
    public EscapeMethod getBestEscapeMethod() {
        InventoryState inventory = gameStateService.getInventoryState();
        EquipmentState equipment = gameStateService.getEquipmentState();

        // Priority 1: One-click teleport items
        for (int itemId : EMERGENCY_TELEPORT_ITEMS) {
            if (inventory.hasItem(itemId)) {
                return new EscapeMethod(EscapeMethod.Type.ONE_CLICK_TELEPORT, itemId, false);
            }
            if (equipment.hasEquipped(itemId)) {
                return new EscapeMethod(EscapeMethod.Type.ONE_CLICK_TELEPORT, itemId, true);
            }
        }

        // Priority 2: Standard teleport spell (if not in combat/wilderness)
        // Would need to check spellbook and runes
        // Placeholder - actual implementation would check spellbook state

        // Priority 3: Run + logout
        return new EscapeMethod(EscapeMethod.Type.RUN_AND_LOGOUT, -1, false);

        // Priority 4: Ring of Life is automatic, not a manual action
    }

    // ========================================================================
    // Ring of Life Management
    // ========================================================================

    /**
     * Check if Ring of Life was recently consumed (not equipped anymore).
     * After RoL triggers, it is destroyed and must be replaced.
     */
    public boolean wasRingOfLifeConsumed(EquipmentState previousEquipment, EquipmentState currentEquipment) {
        boolean hadRoL = previousEquipment.hasEquipped(RING_OF_LIFE_ID) || 
                         previousEquipment.hasEquipped(RING_OF_LIFE_I_ID);
        boolean hasRoL = currentEquipment.hasEquipped(RING_OF_LIFE_ID) || 
                         currentEquipment.hasEquipped(RING_OF_LIFE_I_ID);
        return hadRoL && !hasRoL;
    }

    /**
     * Count spare Rings of Life in inventory.
     * Per Section 10.1.4: HCIM should maintain minimum 2 spare in bank.
     */
    public int countSpareRingsOfLife(InventoryState inventory) {
        return inventory.countItem(RING_OF_LIFE_ID) + inventory.countItem(RING_OF_LIFE_I_ID);
    }

    // ========================================================================
    // Combat Avoidance Checks
    // ========================================================================

    /**
     * Check if an NPC is too dangerous to fight.
     * Per Section 10.1.4: Calculate enemy max hit vs current HP.
     *
     * @param npc the NPC to evaluate
     * @param config combat configuration
     * @return true if combat should be refused
     */
    public boolean isTooRiskyToFight(NpcSnapshot npc, CombatConfig config) {
        if (!config.isHcimMode()) {
            return false;
        }

        PlayerState player = gameStateService.getPlayerState();
        int currentHp = player.getCurrentHitpoints();

        // Estimate max hit based on combat level
        int estimatedMaxHit = AggressorInfo.estimateMaxHit(npc.getCombatLevel());

        // Per Section 10.1.4: Refuse if max_hit >= current_hp * 0.6
        return estimatedMaxHit >= currentHp * 0.6;
    }

    /**
     * Check if the current area is too dangerous for HCIM.
     *
     * @return true if area should be avoided
     */
    public boolean isInDangerousArea() {
        // Would check for:
        // - Wilderness
        // - High-level boss areas
        // - Areas with instant-kill mechanics
        // Placeholder - actual implementation needs area detection
        return false;
    }

    // ========================================================================
    // Control
    // ========================================================================

    /**
     * Enable HCIM safety mode.
     */
    public void enable() {
        hcimModeActive = true;
        log.info("HCIM Safety Mode enabled");
    }

    /**
     * Disable HCIM safety mode.
     */
    public void disable() {
        hcimModeActive = false;
        log.info("HCIM Safety Mode disabled");
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    /**
     * Result of pre-combat safety checks.
     */
    public static class SafetyCheckResult {
        private final java.util.List<String> failures = new java.util.ArrayList<>();

        public void addFailure(String reason) {
            failures.add(reason);
        }

        public boolean passed() {
            return failures.isEmpty();
        }

        public java.util.List<String> getFailures() {
            return failures;
        }

        public static SafetyCheckResult createPassed() {
            return new SafetyCheckResult();
        }
    }

    /**
     * Reasons for HCIM to flee combat.
     */
    public enum FleeReason {
        MULTI_COMBAT_PILEUP("Multiple NPCs attacking"),
        LOW_HP_NO_FOOD("Low HP with no food"),
        VENOM_CRITICAL("Venom at critical level (16+ damage)"),
        SKULLED("Player is skulled"),
        RING_OF_LIFE_MISSING("Ring of Life not equipped"),
        PVP_WORLD("In PvP world"),
        DANGEROUS_AREA("In dangerous area");

        private final String description;

        FleeReason(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Escape method recommendation.
     */
    public static class EscapeMethod {
        public enum Type {
            ONE_CLICK_TELEPORT,
            SPELL_TELEPORT,
            RUN_AND_LOGOUT,
            RING_OF_LIFE_AUTOMATIC
        }

        private final Type type;
        private final int itemId;
        private final boolean isEquipped;

        public EscapeMethod(Type type, int itemId, boolean isEquipped) {
            this.type = type;
            this.itemId = itemId;
            this.isEquipped = isEquipped;
        }

        public Type getType() {
            return type;
        }

        public int getItemId() {
            return itemId;
        }

        public boolean isEquipped() {
            return isEquipped;
        }
    }
}

