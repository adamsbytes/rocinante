package com.rocinante.navigation;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Defines an obstacle that may block navigation paths.
 * Per REQUIREMENTS.md Section 7.3.
 *
 * <p>Obstacles include:
 * <ul>
 *   <li>Doors (open if closed)</li>
 *   <li>Gates</li>
 *   <li>Agility shortcuts (future)</li>
 * </ul>
 */
@Value
@Builder
public class ObstacleDefinition {

    /**
     * Object IDs that represent this obstacle.
     * Often there are multiple IDs (open vs closed state).
     */
    List<Integer> objectIds;

    /**
     * The ID representing the "blocking" state (e.g., closed door).
     */
    int blockedStateId;

    /**
     * The ID representing the "passable" state (e.g., open door).
     */
    int passableStateId;

    /**
     * Menu action to interact with obstacle.
     */
    String action;

    /**
     * Name for logging/debugging.
     */
    String name;

    /**
     * Obstacle type.
     */
    ObstacleType type;

    /**
     * Required agility level (for shortcuts).
     */
    @Builder.Default
    int requiredAgilityLevel = 0;

    /**
     * Success rate (for agility shortcuts that can fail).
     * 1.0 = always succeeds
     */
    @Builder.Default
    double successRate = 1.0;

    /**
     * Ticks added to path cost for traversing this obstacle.
     */
    @Builder.Default
    int traversalCostTicks = 2;

    /**
     * Check if an object ID matches this obstacle's blocked state.
     *
     * @param objectId the object ID to check
     * @return true if object is blocking
     */
    public boolean isBlocked(int objectId) {
        return objectId == blockedStateId;
    }

    /**
     * Check if an object ID matches this obstacle's passable state.
     *
     * @param objectId the object ID to check
     * @return true if object is passable
     */
    public boolean isPassable(int objectId) {
        return objectId == passableStateId;
    }

    /**
     * Check if an object ID matches any state of this obstacle.
     *
     * @param objectId the object ID to check
     * @return true if object matches this obstacle
     */
    public boolean matches(int objectId) {
        return objectIds != null && objectIds.contains(objectId);
    }

    /**
     * Types of obstacles.
     */
    public enum ObstacleType {
        /**
         * Standard door.
         */
        DOOR,

        /**
         * Gate that opens.
         */
        GATE,

        /**
         * Agility shortcut.
         */
        AGILITY_SHORTCUT,

        /**
         * Ladder (up/down).
         */
        LADDER,

        /**
         * Staircase.
         */
        STAIRS,

        /**
         * Other interactive obstacle.
         */
        OTHER
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a simple door obstacle.
     *
     * @param name       door name
     * @param closedId   object ID when closed
     * @param openId     object ID when open
     * @return the obstacle definition
     */
    public static ObstacleDefinition door(String name, int closedId, int openId) {
        return ObstacleDefinition.builder()
                .name(name)
                .type(ObstacleType.DOOR)
                .objectIds(List.of(closedId, openId))
                .blockedStateId(closedId)
                .passableStateId(openId)
                .action("Open")
                .traversalCostTicks(2)
                .build();
    }

    /**
     * Create a simple gate obstacle.
     *
     * @param name       gate name
     * @param closedId   object ID when closed
     * @param openId     object ID when open
     * @return the obstacle definition
     */
    public static ObstacleDefinition gate(String name, int closedId, int openId) {
        return ObstacleDefinition.builder()
                .name(name)
                .type(ObstacleType.GATE)
                .objectIds(List.of(closedId, openId))
                .blockedStateId(closedId)
                .passableStateId(openId)
                .action("Open")
                .traversalCostTicks(2)
                .build();
    }

    /**
     * Create an agility shortcut obstacle.
     *
     * @param name           shortcut name
     * @param objectId       object ID
     * @param action         menu action
     * @param agilityLevel   required level
     * @param successRate    success rate (0.0 to 1.0)
     * @return the obstacle definition
     */
    public static ObstacleDefinition agilityShortcut(String name, int objectId, String action,
                                                      int agilityLevel, double successRate) {
        return ObstacleDefinition.builder()
                .name(name)
                .type(ObstacleType.AGILITY_SHORTCUT)
                .objectIds(List.of(objectId))
                .blockedStateId(objectId)
                .passableStateId(-1) // Shortcuts don't change state
                .action(action)
                .requiredAgilityLevel(agilityLevel)
                .successRate(successRate)
                .traversalCostTicks(3)
                .build();
    }

    @Override
    public String toString() {
        return String.format("Obstacle[%s, %s, IDs=%s]", name, type, objectIds);
    }
}

