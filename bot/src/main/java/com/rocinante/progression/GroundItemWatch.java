package com.rocinante.progression;

import lombok.Builder;
import lombok.Value;

/**
 * Configuration for watching and picking up ground items during skill training.
 *
 * <p>This allows skills like Agility and Woodcutting to opportunistically pick up
 * valuable spawns (marks of grace, bird's nests) without interrupting the main
 * training flow excessively.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Watch for marks of grace during agility
 * GroundItemWatch markWatch = GroundItemWatch.builder()
 *     .itemId(11849)  // ItemID.MARK_OF_GRACE
 *     .itemName("Mark of grace")
 *     .priority(100)
 *     .maxPickupDistance(20)
 *     .interruptAction(false)  // Wait for current obstacle to complete
 *     .build();
 *
 * // Watch for bird's nests during woodcutting
 * GroundItemWatch nestWatch = GroundItemWatch.builder()
 *     .itemId(5070)  // ItemID.BIRD_NEST
 *     .itemName("Bird nest")
 *     .priority(50)
 *     .maxPickupDistance(5)
 *     .interruptAction(true)  // Stop chopping immediately
 *     .build();
 * }</pre>
 *
 * @see TrainingMethod#getWatchedGroundItems()
 */
@Value
@Builder
public class GroundItemWatch {

    /**
     * The item ID to watch for.
     * Common values:
     * - 11849: Mark of grace
     * - 5070-5074: Bird's nest variants
     * - 1617: Uncut diamond (from gem rocks)
     */
    int itemId;

    /**
     * Human-readable item name for logging and menu matching.
     */
    String itemName;

    /**
     * Priority level for this item (higher = more urgent).
     * Suggested values:
     * - 100: Very valuable (marks of grace)
     * - 50: Valuable (bird's nests)
     * - 25: Nice to have (random gems)
     *
     * When multiple watched items are present, higher priority items
     * are picked up first.
     */
    @Builder.Default
    int priority = 50;

    /**
     * Maximum distance (in tiles) to travel to pick up this item.
     * If the item spawns further than this, it will be ignored.
     *
     * Suggested values:
     * - 20: For agility courses (marks can spawn anywhere on course)
     * - 5: For stationary training (bird's nests near tree)
     * - 10: General default
     */
    @Builder.Default
    int maxPickupDistance = 10;

    /**
     * Whether to interrupt the current action to pick up this item.
     *
     * If true: Stop current animation (chopping, mining) immediately
     * If false: Wait for current action to complete, then pick up
     *
     * Marks of grace should typically use false (they don't despawn quickly),
     * while bird's nests should use true (they despawn faster).
     */
    @Builder.Default
    boolean interruptAction = false;

    /**
     * Check if this watch configuration matches a given item ID.
     *
     * @param id the item ID to check
     * @return true if this watch is for the given item
     */
    public boolean matches(int id) {
        return itemId == id;
    }

    /**
     * Get a summary string for logging.
     *
     * @return human-readable summary
     */
    public String getSummary() {
        return String.format("%s (ID: %d, priority: %d, maxDist: %d)",
                itemName, itemId, priority, maxPickupDistance);
    }
}

