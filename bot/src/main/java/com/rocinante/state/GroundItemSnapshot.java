package com.rocinante.state;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Immutable snapshot of a ground item's state at a point in time.
 *
 * Per REQUIREMENTS.md Section 6.2.4, captures ground items within 15 tiles.
 * Ground items are dropped items visible on the ground.
 */
@Value
@Builder
public class GroundItemSnapshot {

    /**
     * The item's definition ID.
     */
    int id;

    /**
     * The quantity of items in this stack.
     */
    int quantity;

    /**
     * The item's world position (tile location).
     */
    WorldPoint worldPosition;

    /**
     * The item's name from composition.
     */
    String name;

    /**
     * Grand Exchange value of the item (per unit).
     * -1 if not tradeable or unknown.
     */
    int gePrice;

    /**
     * High alchemy value of the item.
     */
    int haPrice;

    /**
     * Whether the item is tradeable.
     */
    boolean tradeable;

    /**
     * Whether the item is stackable.
     */
    boolean stackable;

    /**
     * Game tick when this item will despawn.
     * -1 if unknown.
     */
    int despawnTick;

    /**
     * Whether this item is visible only to the local player (private loot).
     */
    boolean privateItem;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Get the total GE value of this item stack.
     *
     * @return total value, or -1 if price unknown
     */
    public long getTotalGeValue() {
        if (gePrice < 0) {
            return -1;
        }
        return (long) gePrice * quantity;
    }

    /**
     * Get the total HA value of this item stack.
     *
     * @return total high alchemy value
     */
    public long getTotalHaValue() {
        return (long) haPrice * quantity;
    }

    /**
     * Check if this item is worth more than a threshold (GE value).
     *
     * @param threshold minimum value in gp
     * @return true if item stack value exceeds threshold
     */
    public boolean isWorthMoreThan(int threshold) {
        long value = getTotalGeValue();
        return value > 0 && value >= threshold;
    }

    /**
     * Calculate ticks until this item despawns.
     *
     * @param currentTick the current game tick
     * @return ticks remaining, or -1 if despawn tick unknown
     */
    public int getTicksUntilDespawn(int currentTick) {
        if (despawnTick < 0) {
            return -1;
        }
        return Math.max(0, despawnTick - currentTick);
    }

    /**
     * Calculate distance from this item to a world point.
     *
     * @param target the target point
     * @return distance in tiles, or -1 if position invalid
     */
    public int distanceTo(WorldPoint target) {
        if (worldPosition == null || target == null) {
            return -1;
        }
        return worldPosition.distanceTo(target);
    }

    /**
     * Check if this item is within a certain distance of a point.
     *
     * @param target   the target point
     * @param distance maximum distance in tiles
     * @return true if within distance
     */
    public boolean isWithinDistance(WorldPoint target, int distance) {
        int dist = distanceTo(target);
        return dist >= 0 && dist <= distance;
    }

    /**
     * Get a summary string for logging.
     *
     * @return summary of this item
     */
    public String getSummary() {
        return String.format("GroundItem[%s x%d (id=%d) at %s, value=%d gp]",
                name != null ? name : "Unknown",
                quantity,
                id,
                worldPosition,
                getTotalGeValue());
    }
}

