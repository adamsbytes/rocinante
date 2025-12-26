package com.rocinante.state;

import com.rocinante.quest.conditions.VarbitCondition;
import com.rocinante.quest.conditions.ZoneCondition;
import com.rocinante.quest.conditions.ZoneCondition.Zone;
import net.runelite.api.coords.WorldPoint;

/**
 * Factory methods for common StateCondition predicates.
 *
 * Per REQUIREMENTS.md Section 6.3, provides conditions for:
 * <ul>
 *   <li>Player position and area checks</li>
 *   <li>Player state (idle, animating, interacting)</li>
 *   <li>Inventory and equipment checks</li>
 *   <li>Health, prayer, and run energy thresholds</li>
 *   <li>Combat state conditions (WorldState, CombatState)</li>
 *   <li>Widget and dialogue visibility</li>
 *   <li>NPC existence and targeting</li>
 *   <li>Special attack and poison/venom status</li>
 * </ul>
 *
 * <p>All conditions are composable via and(), or(), not() methods.
 */
public final class Conditions {

    private Conditions() {
        // Static factory class
    }

    // ========================================================================
    // Player Position Conditions
    // ========================================================================

    /**
     * Player is at a specific tile.
     *
     * @param x world X coordinate
     * @param y world Y coordinate
     * @return condition checking player position
     */
    public static StateCondition playerAtTile(int x, int y) {
        return ctx -> {
            PlayerState player = ctx.getPlayerState();
            return player.isAtTile(x, y);
        };
    }

    /**
     * Player is at a specific WorldPoint.
     *
     * @param point the target point
     * @return condition checking player position
     */
    public static StateCondition playerAtTile(WorldPoint point) {
        return ctx -> {
            PlayerState player = ctx.getPlayerState();
            return player.isAtTile(point);
        };
    }

    /**
     * Player is within a rectangular area.
     *
     * @param minX minimum X coordinate
     * @param minY minimum Y coordinate
     * @param maxX maximum X coordinate
     * @param maxY maximum Y coordinate
     * @return condition checking player is in area
     */
    public static StateCondition playerInArea(int minX, int minY, int maxX, int maxY) {
        return ctx -> {
            PlayerState player = ctx.getPlayerState();
            return player.isInArea(minX, minY, maxX, maxY);
        };
    }

    /**
     * Player is within a certain distance of a point.
     *
     * @param target   the target point
     * @param distance maximum distance in tiles
     * @return condition checking distance
     */
    public static StateCondition playerNear(WorldPoint target, int distance) {
        return ctx -> {
            PlayerState player = ctx.getPlayerState();
            int dist = player.distanceTo(target);
            return dist >= 0 && dist <= distance;
        };
    }

    // ========================================================================
    // Player State Conditions
    // ========================================================================

    /**
     * Player is idle (not moving, not animating, not interacting).
     *
     * @return condition checking player is idle
     */
    public static StateCondition playerIsIdle() {
        return ctx -> ctx.getPlayerState().isIdle();
    }

    /**
     * Player is currently moving.
     *
     * @return condition checking player is moving
     */
    public static StateCondition playerIsMoving() {
        return ctx -> ctx.getPlayerState().isMoving();
    }

    /**
     * Player is playing any animation.
     *
     * @return condition checking player is animating
     */
    public static StateCondition playerIsAnimating() {
        return ctx -> ctx.getPlayerState().isAnimating();
    }

    /**
     * Player is playing a specific animation.
     *
     * @param animationId the expected animation ID
     * @return condition checking specific animation
     */
    public static StateCondition playerIsAnimating(int animationId) {
        return ctx -> ctx.getPlayerState().isAnimating(animationId);
    }

    /**
     * Player is interacting with something.
     *
     * @return condition checking player is interacting
     */
    public static StateCondition playerIsInteracting() {
        return ctx -> ctx.getPlayerState().isInteracting();
    }

    /**
     * Player is in combat.
     *
     * @return condition checking player is in combat
     */
    public static StateCondition playerInCombat() {
        return ctx -> ctx.getPlayerState().isInCombat();
    }

    /**
     * Player state is valid (logged in and loaded).
     *
     * @return condition checking player validity
     */
    public static StateCondition playerIsValid() {
        return ctx -> ctx.getPlayerState().isValid();
    }

    // ========================================================================
    // Health and Resource Conditions
    // ========================================================================

    /**
     * Health is below a threshold percentage.
     *
     * @param percent threshold (0.0 to 1.0)
     * @return condition checking health
     */
    public static StateCondition healthBelow(double percent) {
        return ctx -> ctx.getPlayerState().isHealthBelow(percent);
    }

    /**
     * Health is above a threshold percentage.
     *
     * @param percent threshold (0.0 to 1.0)
     * @return condition checking health
     */
    public static StateCondition healthAbove(double percent) {
        return ctx -> ctx.getPlayerState().getHealthPercent() >= percent;
    }

    /**
     * Prayer is below a threshold percentage.
     *
     * @param percent threshold (0.0 to 1.0)
     * @return condition checking prayer
     */
    public static StateCondition prayerBelow(double percent) {
        return ctx -> ctx.getPlayerState().isPrayerBelow(percent);
    }

    /**
     * Prayer is above a threshold percentage.
     *
     * @param percent threshold (0.0 to 1.0)
     * @return condition checking prayer
     */
    public static StateCondition prayerAbove(double percent) {
        return ctx -> ctx.getPlayerState().getPrayerPercent() >= percent;
    }

    /**
     * Run energy is above a threshold.
     *
     * @param threshold energy level (0-100)
     * @return condition checking run energy
     */
    public static StateCondition runEnergyAbove(int threshold) {
        return ctx -> ctx.getPlayerState().isRunEnergyAbove(threshold);
    }

    /**
     * Run energy is below a threshold.
     *
     * @param threshold energy level (0-100)
     * @return condition checking run energy
     */
    public static StateCondition runEnergyBelow(int threshold) {
        return ctx -> ctx.getPlayerState().getRunEnergy() < threshold;
    }

    // ========================================================================
    // Status Effect Conditions
    // ========================================================================

    /**
     * Player is poisoned.
     *
     * @return condition checking poison status
     */
    public static StateCondition isPoisoned() {
        return ctx -> ctx.getPlayerState().isPoisoned();
    }

    /**
     * Player is venomed.
     *
     * @return condition checking venom status
     */
    public static StateCondition isVenomed() {
        return ctx -> ctx.getPlayerState().isVenomed();
    }

    /**
     * Player has any poison effect (poison or venom).
     *
     * @return condition checking any poison
     */
    public static StateCondition hasPoisonEffect() {
        return ctx -> ctx.getPlayerState().hasPoisonEffect();
    }

    /**
     * Player is skulled.
     *
     * @return condition checking skull status
     */
    public static StateCondition isSkulled() {
        return ctx -> ctx.getPlayerState().isSkulled();
    }

    // ========================================================================
    // Inventory Conditions
    // ========================================================================

    /**
     * Inventory contains at least one of an item.
     *
     * @param itemId the item ID
     * @return condition checking item presence
     */
    public static StateCondition hasItem(int itemId) {
        return ctx -> ctx.getInventoryState().hasItem(itemId);
    }

    /**
     * Inventory contains at least a quantity of an item.
     *
     * @param itemId   the item ID
     * @param quantity minimum quantity
     * @return condition checking item quantity
     */
    public static StateCondition hasItem(int itemId, int quantity) {
        return ctx -> ctx.getInventoryState().hasItem(itemId, quantity);
    }

    /**
     * Inventory contains any of the specified items.
     *
     * @param itemIds the item IDs to check
     * @return condition checking any item present
     */
    public static StateCondition hasAnyItem(int... itemIds) {
        return ctx -> ctx.getInventoryState().hasAnyItem(itemIds);
    }

    /**
     * Inventory contains all of the specified items.
     *
     * @param itemIds the item IDs that must be present
     * @return condition checking all items present
     */
    public static StateCondition hasAllItems(int... itemIds) {
        return ctx -> ctx.getInventoryState().hasAllItems(itemIds);
    }

    /**
     * Inventory is full (no free slots).
     *
     * @return condition checking inventory full
     */
    public static StateCondition inventoryFull() {
        return ctx -> ctx.getInventoryState().isFull();
    }

    /**
     * Inventory is empty.
     *
     * @return condition checking inventory empty
     */
    public static StateCondition inventoryEmpty() {
        return ctx -> ctx.getInventoryState().isEmpty();
    }

    /**
     * Inventory has at least N free slots.
     *
     * @param slots minimum free slots
     * @return condition checking free slots
     */
    public static StateCondition inventoryHasFreeSlots(int slots) {
        return ctx -> ctx.getInventoryState().getFreeSlots() >= slots;
    }

    /**
     * Inventory contains food.
     *
     * @return condition checking food presence
     */
    public static StateCondition hasFood() {
        return ctx -> ctx.getInventoryState().hasFood();
    }

    /**
     * Inventory contains at least N food items.
     *
     * @param count minimum food count
     * @return condition checking food count
     */
    public static StateCondition hasFoodCount(int count) {
        return ctx -> ctx.getInventoryState().countFood() >= count;
    }

    // ========================================================================
    // Equipment Conditions
    // ========================================================================

    /**
     * An item is equipped anywhere.
     *
     * @param itemId the item ID
     * @return condition checking item equipped
     */
    public static StateCondition hasEquipped(int itemId) {
        return ctx -> ctx.getEquipmentState().hasEquipped(itemId);
    }

    /**
     * Any of the specified items are equipped.
     *
     * @param itemIds the item IDs
     * @return condition checking any equipped
     */
    public static StateCondition hasAnyEquipped(int... itemIds) {
        return ctx -> ctx.getEquipmentState().hasAnyEquipped(itemIds);
    }

    /**
     * All of the specified items are equipped.
     *
     * @param itemIds the item IDs
     * @return condition checking all equipped
     */
    public static StateCondition hasAllEquipped(int... itemIds) {
        return ctx -> ctx.getEquipmentState().hasAllEquipped(itemIds);
    }

    /**
     * Ring of Life is equipped (HCIM safety).
     *
     * @return condition checking Ring of Life
     */
    public static StateCondition hasRingOfLife() {
        return ctx -> ctx.getEquipmentState().hasRingOfLife();
    }

    /**
     * Phoenix Necklace is equipped.
     *
     * @return condition checking Phoenix Necklace
     */
    public static StateCondition hasPhoenixNecklace() {
        return ctx -> ctx.getEquipmentState().hasPhoenixNecklace();
    }

    /**
     * Has any safety equipment (Ring of Life or Phoenix Necklace).
     *
     * @return condition checking safety equipment
     */
    public static StateCondition hasSafetyEquipment() {
        return ctx -> ctx.getEquipmentState().hasRingOfLife()
                || ctx.getEquipmentState().hasPhoenixNecklace();
    }

    // ========================================================================
    // Logged In Condition
    // ========================================================================

    /**
     * Player is logged in.
     *
     * @return condition checking login state
     */
    public static StateCondition isLoggedIn() {
        return ctx -> ctx.isLoggedIn();
    }

    // ========================================================================
    // Task Variable Conditions
    // ========================================================================

    /**
     * A task variable exists.
     *
     * @param key the variable key
     * @return condition checking variable existence
     */
    public static StateCondition hasVariable(String key) {
        return ctx -> ctx.hasVariable(key);
    }

    /**
     * A task variable equals a specific value.
     *
     * @param key   the variable key
     * @param value the expected value
     * @return condition checking variable value
     */
    public static StateCondition variableEquals(String key, Object value) {
        return ctx -> ctx.getVariable(key)
                .map(v -> v.equals(value))
                .orElse(false);
    }

    // ========================================================================
    // Composite Conditions (Convenience Methods)
    // ========================================================================

    /**
     * Ready for combat: has food, not low health, has safety equipment.
     * Useful as a base condition for combat tasks.
     *
     * @param minFoodCount minimum food required
     * @param minHealth    minimum health percent
     * @return composite combat readiness condition
     */
    public static StateCondition combatReady(int minFoodCount, double minHealth) {
        return StateCondition.allOf(
                hasFoodCount(minFoodCount),
                healthAbove(minHealth),
                playerIsValid()
        );
    }

    /**
     * Safe to continue: not low health, has food or high health.
     *
     * @param lowHealthThreshold  health percent to consider "low"
     * @param emergencyFoodCount  food count that allows continuing at low health
     * @return composite safety condition
     */
    public static StateCondition safeToContinue(double lowHealthThreshold, int emergencyFoodCount) {
        return healthAbove(lowHealthThreshold)
                .or(hasFoodCount(emergencyFoodCount));
    }

    /**
     * HCIM safe for combat: has Ring of Life, has food, health above threshold.
     *
     * @param minFood   minimum food count
     * @param minHealth minimum health percent
     * @return composite HCIM safety condition
     */
    public static StateCondition hcimCombatSafe(int minFood, double minHealth) {
        return StateCondition.allOf(
                hasRingOfLife(),
                hasFoodCount(minFood),
                healthAbove(minHealth)
        );
    }

    // ========================================================================
    // NPC and World State Conditions (Section 6.3 additions)
    // ========================================================================

    /**
     * NPC with specified ID exists within range.
     *
     * @param npcId the NPC definition ID
     * @return condition checking NPC presence
     */
    public static StateCondition npcExists(int npcId) {
        return ctx -> ctx.getWorldState().hasNpc(npcId);
    }

    /**
     * NPC with specified ID exists within a specific radius.
     *
     * @param npcId  the NPC definition ID
     * @param radius maximum distance in tiles
     * @return condition checking NPC presence within radius
     */
    public static StateCondition npcExists(int npcId, int radius) {
        return ctx -> {
            WorldState world = ctx.getWorldState();
            PlayerState player = ctx.getPlayerState();
            WorldPoint playerPos = player.getWorldPosition();
            if (playerPos == null) return false;
            
            return world.getNpcsById(npcId).stream()
                    .anyMatch(npc -> npc.isWithinDistance(playerPos, radius));
        };
    }

    /**
     * Any NPC is currently targeting the player.
     *
     * @return condition checking if player is being targeted
     */
    public static StateCondition npcTargetingPlayer() {
        return ctx -> ctx.getWorldState().isPlayerTargeted();
    }

    /**
     * Target NPC health is below a threshold.
     *
     * @param percent health threshold (0.0 to 1.0)
     * @return condition checking target health
     */
    public static StateCondition npcHealthBelow(double percent) {
        return ctx -> {
            CombatState combat = ctx.getCombatState();
            NpcSnapshot target = combat.getTargetNpc();
            if (target == null) return false;
            return target.isHealthBelow(percent);
        };
    }

    /**
     * Ground item with specified ID exists nearby.
     *
     * @param itemId the item definition ID
     * @return condition checking ground item presence
     */
    public static StateCondition groundItemExists(int itemId) {
        return ctx -> ctx.getWorldState().hasGroundItem(itemId);
    }

    /**
     * Valuable ground items exist (above threshold).
     *
     * @param minValue minimum GE value in gp
     * @return condition checking valuable loot
     */
    public static StateCondition valuableLootExists(int minValue) {
        return ctx -> !ctx.getWorldState().getValuableGroundItems(minValue).isEmpty();
    }

    /**
     * Game object with specified ID exists nearby.
     *
     * @param objectId the object definition ID
     * @return condition checking object presence
     */
    public static StateCondition objectExists(int objectId) {
        return ctx -> ctx.getWorldState().hasObject(objectId);
    }

    // ========================================================================
    // Combat State Conditions (Section 6.3 additions)
    // ========================================================================

    /**
     * Player has a combat target.
     *
     * @return condition checking target presence
     */
    public static StateCondition hasTarget() {
        return ctx -> ctx.getCombatState().hasTarget();
    }

    /**
     * Player is being attacked by any NPC.
     *
     * @return condition checking if under attack
     */
    public static StateCondition isBeingAttacked() {
        return ctx -> ctx.getCombatState().isBeingAttacked();
    }

    /**
     * Player is piled up by multiple NPCs (HCIM danger).
     *
     * @return condition checking pile-up status
     */
    public static StateCondition isPiledUp() {
        return ctx -> ctx.getCombatState().isPiledUp();
    }

    /**
     * Player is in multi-combat area.
     *
     * @return condition checking multi-combat status
     */
    public static StateCondition inMultiCombat() {
        return ctx -> ctx.getCombatState().isInMultiCombat();
    }

    /**
     * Special attack energy is above threshold.
     *
     * @param percent threshold (0-100)
     * @return condition checking spec energy
     */
    public static StateCondition specialAttackAbove(int percent) {
        return ctx -> ctx.getCombatState().hasSpecEnergy(percent);
    }

    /**
     * Player can use special attack with given cost.
     *
     * @param cost spec cost (25, 50, or 100)
     * @return condition checking spec availability
     */
    public static StateCondition canUseSpecialAttack(int cost) {
        return ctx -> ctx.getCombatState().canUseSpec(cost);
    }

    /**
     * Venom is active on player.
     *
     * @return condition checking venom status
     */
    public static StateCondition venomActive() {
        return ctx -> ctx.getCombatState().isVenomed();
    }

    /**
     * Poison (not venom) is active on player.
     *
     * @return condition checking poison status
     */
    public static StateCondition poisonActive() {
        return ctx -> ctx.getCombatState().isPoisoned();
    }

    /**
     * Venom is at critical damage levels (16+).
     *
     * @return condition checking critical venom
     */
    public static StateCondition venomCritical() {
        return ctx -> ctx.getCombatState().isVenomCritical();
    }

    /**
     * An incoming attack has been detected.
     *
     * @return condition checking incoming attack
     */
    public static StateCondition hasIncomingAttack() {
        return ctx -> ctx.getCombatState().hasIncomingAttack();
    }

    /**
     * Number of aggressors is at or above threshold.
     *
     * @param count minimum aggressor count
     * @return condition checking aggressor count
     */
    public static StateCondition aggressorCountAtLeast(int count) {
        return ctx -> ctx.getCombatState().getAggressorCount() >= count;
    }

    /**
     * Player's attack is ready (cooldown elapsed).
     *
     * @return condition checking attack readiness
     */
    public static StateCondition attackReady() {
        return ctx -> ctx.getCombatState().isAttackReady();
    }

    // ========================================================================
    // HCIM Safety Conditions (Section 10.1.4)
    // ========================================================================

    /**
     * HCIM should flee based on current state.
     * Checks: pile-up, low HP with no food, critical venom.
     *
     * @return condition checking if HCIM should flee
     */
    public static StateCondition hcimShouldFlee() {
        return StateCondition.anyOf(
                isPiledUp(),
                StateCondition.allOf(healthBelow(0.50), hasFood().not()),
                venomCritical(),
                isSkulled()
        );
    }

    /**
     * HCIM pre-combat ready: all safety equipment and minimum supplies.
     *
     * @param minFood minimum food count
     * @return condition checking HCIM pre-combat readiness
     */
    public static StateCondition hcimPreCombatReady(int minFood) {
        return StateCondition.allOf(
                hasRingOfLife(),
                hasFoodCount(minFood),
                healthAbove(0.80),
                isSkulled().not()
        );
    }

    // ========================================================================
    // Widget Conditions
    // ========================================================================

    /**
     * A specific widget group is visible.
     *
     * @param widgetGroupId the widget group ID
     * @return condition checking widget visibility
     */
    public static StateCondition widgetVisible(int widgetGroupId) {
        return ctx -> ctx.getWorldState().isWidgetVisible(widgetGroupId);
    }

    /**
     * Bank interface is open.
     *
     * @return condition checking bank open
     */
    public static StateCondition bankIsOpen() {
        return widgetVisible(12); // Bank widget group
    }

    /**
     * Inventory interface is visible.
     *
     * @return condition checking inventory tab open
     */
    public static StateCondition inventoryTabOpen() {
        return widgetVisible(149); // Inventory widget group
    }

    // ========================================================================
    // Varbit Conditions (Quest/Game Progress)
    // ========================================================================

    /**
     * Check if a varbit equals a specific value.
     *
     * @param varbitId the varbit ID to check
     * @param value    the expected value
     * @return condition checking varbit equality
     */
    public static StateCondition varbitEquals(int varbitId, int value) {
        return VarbitCondition.equals(varbitId, value);
    }

    /**
     * Check if a varbit is greater than a value.
     *
     * @param varbitId the varbit ID to check
     * @param value    the threshold value
     * @return condition checking varbit is greater
     */
    public static StateCondition varbitGreaterThan(int varbitId, int value) {
        return VarbitCondition.greaterThan(varbitId, value);
    }

    /**
     * Check if a varbit is greater than or equal to a value.
     *
     * @param varbitId the varbit ID to check
     * @param value    the threshold value
     * @return condition checking varbit is greater or equal
     */
    public static StateCondition varbitGreaterThanOrEqual(int varbitId, int value) {
        return VarbitCondition.greaterThanOrEqual(varbitId, value);
    }

    /**
     * Check if a varbit is less than a value.
     *
     * @param varbitId the varbit ID to check
     * @param value    the threshold value
     * @return condition checking varbit is less
     */
    public static StateCondition varbitLessThan(int varbitId, int value) {
        return VarbitCondition.lessThan(varbitId, value);
    }

    /**
     * Check if a varbit is within a range (inclusive).
     *
     * @param varbitId the varbit ID to check
     * @param minValue minimum value (inclusive)
     * @param maxValue maximum value (inclusive)
     * @return condition checking varbit is in range
     */
    public static StateCondition varbitInRange(int varbitId, int minValue, int maxValue) {
        return VarbitCondition.inRange(varbitId, minValue, maxValue);
    }

    // ========================================================================
    // Zone Conditions
    // ========================================================================

    /**
     * Check if player is within a zone defined by two corners.
     *
     * @param corner1 first corner WorldPoint
     * @param corner2 second corner WorldPoint
     * @return condition checking zone membership
     */
    public static StateCondition inZone(WorldPoint corner1, WorldPoint corner2) {
        return ZoneCondition.fromCorners(corner1, corner2);
    }

    /**
     * Check if player is within a named zone.
     *
     * @param corner1     first corner WorldPoint
     * @param corner2     second corner WorldPoint
     * @param description human-readable zone name
     * @return condition checking zone membership
     */
    public static StateCondition inZone(WorldPoint corner1, WorldPoint corner2, String description) {
        return ZoneCondition.fromCorners(corner1, corner2, description);
    }

    /**
     * Check if player is within a Zone object.
     *
     * @param zone the zone to check
     * @return condition checking zone membership
     */
    public static StateCondition inZone(Zone zone) {
        return ZoneCondition.in(zone);
    }

    /**
     * Check if player is within a region.
     *
     * @param regionId the region ID
     * @return condition checking region membership
     */
    public static StateCondition inRegion(int regionId) {
        return ZoneCondition.inRegion(regionId);
    }

    /**
     * Check if player is at a specific tile.
     *
     * @param point the exact WorldPoint
     * @return condition checking exact position
     */
    public static StateCondition atExactTile(WorldPoint point) {
        return ZoneCondition.atPoint(point);
    }
}

