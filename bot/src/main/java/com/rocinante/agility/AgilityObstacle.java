package com.rocinante.agility;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

import java.util.Collections;
import java.util.List;

/**
 * Represents a single obstacle in an agility course.
 *
 * <p>Each obstacle has:
 * <ul>
 *   <li>Object ID(s) to interact with</li>
 *   <li>An action to perform ("Climb", "Cross", "Jump-off", etc.)</li>
 *   <li>An area from which the obstacle can be interacted with</li>
 *   <li>An expected landing position on success</li>
 *   <li>Optional failure handling for obstacles that can fail</li>
 * </ul>
 *
 * <p>Example obstacle definition:
 * <pre>{@code
 * AgilityObstacle roughWall = AgilityObstacle.builder()
 *     .index(0)
 *     .name("Rough wall")
 *     .objectId(11404)
 *     .action("Climb")
 *     .interactArea(new WorldArea(3103, 3277, 5, 3, 0))
 *     .expectedLanding(new WorldPoint(3102, 3279, 3))
 *     .expectedTicks(3)
 *     .build();
 * }</pre>
 *
 * @see AgilityCourse
 */
@Value
@Builder
public class AgilityObstacle {

    // ========================================================================
    // Identification
    // ========================================================================

    /**
     * Position in the course sequence (0-based).
     * Obstacles are numbered sequentially from the start of the course.
     */
    int index;

    /**
     * Human-readable name of the obstacle.
     * Examples: "Rough wall", "Tightrope", "Narrow wall", "Wall", "Gap", "Crate"
     */
    String name;

    // ========================================================================
    // Interaction Configuration
    // ========================================================================

    /**
     * Primary object ID to interact with.
     */
    int objectId;

    /**
     * Alternate object IDs (if the obstacle has multiple valid objects).
     * For example, some tightropes may have different IDs from different angles.
     */
    @Builder.Default
    List<Integer> alternateIds = Collections.emptyList();

    /**
     * The menu action to perform on the obstacle.
     * Examples: "Climb", "Cross", "Jump-off", "Balance", "Leap", "Hurdle"
     */
    String action;

    // ========================================================================
    // Positioning
    // ========================================================================

    /**
     * Area from which this obstacle can be interacted with.
     * Player must be in this area before clicking the obstacle.
     * Can be null if any nearby position works.
     */
    WorldArea interactArea;

    /**
     * Expected landing position after successfully completing the obstacle.
     * Used to verify the obstacle was completed successfully.
     */
    WorldPoint expectedLanding;

    /**
     * Tolerance radius for landing position verification (in tiles).
     * Useful for obstacles where the exact landing tile varies slightly.
     */
    @Builder.Default
    int landingTolerance = 1;

    // ========================================================================
    // Failure Handling
    // ========================================================================

    /**
     * Whether this obstacle can fail (player falls/slips).
     * Most rooftop obstacles don't fail, but some course obstacles do.
     */
    @Builder.Default
    boolean canFail = false;

    /**
     * Position where the player lands on failure.
     * Only relevant if canFail is true.
     */
    WorldPoint failureLanding;

    /**
     * Typical damage taken on failure.
     * Only relevant if canFail is true.
     */
    @Builder.Default
    int failureDamage = 0;

    // ========================================================================
    // Timing
    // ========================================================================

    /**
     * Expected number of game ticks to complete this obstacle.
     * Used for timeout detection and progress tracking.
     * -1 if unknown.
     */
    @Builder.Default
    int expectedTicks = -1;

    /**
     * Animation ID played during obstacle traversal.
     * Used to verify the player is actually doing the obstacle.
     * -1 if unknown.
     */
    @Builder.Default
    int animationId = -1;

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if a given object ID matches this obstacle.
     *
     * @param id the object ID to check
     * @return true if it matches the primary or alternate IDs
     */
    public boolean matchesObjectId(int id) {
        if (objectId == id) {
            return true;
        }
        return alternateIds != null && alternateIds.contains(id);
    }

    /**
     * Check if the player is in the interaction area for this obstacle.
     *
     * @param playerPos the player's current position
     * @return true if player can interact with this obstacle from their position
     */
    public boolean canInteractFrom(WorldPoint playerPos) {
        if (interactArea == null) {
            return true; // No restriction
        }
        return interactArea.contains(playerPos);
    }

    /**
     * Check if a position is within the expected landing area.
     *
     * @param position the position to check
     * @return true if within landing tolerance of expected landing
     */
    public boolean isAtLanding(WorldPoint position) {
        if (expectedLanding == null || position == null) {
            return false;
        }
        return position.distanceTo(expectedLanding) <= landingTolerance;
    }

    /**
     * Check if a position is at the failure landing (if applicable).
     *
     * @param position the position to check
     * @return true if at failure landing position
     */
    public boolean isAtFailureLanding(WorldPoint position) {
        if (!canFail || failureLanding == null || position == null) {
            return false;
        }
        return position.distanceTo(failureLanding) <= 2; // Slight tolerance
    }

    /**
     * Get all valid object IDs for this obstacle.
     *
     * @return list containing primary ID and alternates
     */
    public List<Integer> getAllObjectIds() {
        if (alternateIds == null || alternateIds.isEmpty()) {
            return Collections.singletonList(objectId);
        }
        List<Integer> all = new java.util.ArrayList<>();
        all.add(objectId);
        all.addAll(alternateIds);
        return all;
    }

    /**
     * Get a summary string for logging.
     *
     * @return human-readable summary
     */
    public String getSummary() {
        return String.format("Obstacle[%d: %s (obj=%d, action=%s)]",
                index, name, objectId, action);
    }
}

