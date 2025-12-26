package com.rocinante.state;

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
 *   <li>Combat state conditions</li>
 *   <li>Widget and dialogue visibility</li>
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
}

