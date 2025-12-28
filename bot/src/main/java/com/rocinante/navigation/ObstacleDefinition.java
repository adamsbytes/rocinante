package com.rocinante.navigation;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.Quest;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Defines an obstacle that may block navigation paths.
 * Per REQUIREMENTS.md Section 7.3.
 *
 * <p>Obstacles include:
 * <ul>
 *   <li>Doors (open if closed)</li>
 *   <li>Gates</li>
 *   <li>Agility shortcuts (with level requirements and failure rates)</li>
 *   <li>Stairs and ladders (plane transitions)</li>
 * </ul>
 *
 * <p>For agility shortcuts, this class supports:
 * <ul>
 *   <li>Required agility level checking</li>
 *   <li>Failure rate calculation based on player level</li>
 *   <li>Quest requirements (some shortcuts unlock after quest completion)</li>
 *   <li>World location tracking for pathfinding integration</li>
 * </ul>
 */
@Value
@Builder(toBuilder = true)
public class ObstacleDefinition {

    // ========================================================================
    // Base Fields
    // ========================================================================

    /**
     * Object IDs that represent this obstacle.
     * Often there are multiple IDs (open vs closed state, or different variants).
     */
    List<Integer> objectIds;

    /**
     * The ID representing the "blocking" state (e.g., closed door).
     * For shortcuts, this is typically the primary interactable object ID.
     */
    int blockedStateId;

    /**
     * The ID representing the "passable" state (e.g., open door).
     * For shortcuts, this is typically -1 as they don't change state.
     */
    int passableStateId;

    /**
     * Menu action to interact with obstacle (e.g., "Open", "Climb", "Squeeze-through").
     */
    String action;

    /**
     * Name for logging/debugging (e.g., "Varrock South Fence", "Grand Exchange Tunnel").
     */
    String name;

    /**
     * Obstacle type classification.
     */
    ObstacleType type;

    // ========================================================================
    // Agility Shortcut Fields
    // ========================================================================

    /**
     * Required agility level (for shortcuts).
     * 0 means no agility requirement.
     */
    @Builder.Default
    int requiredAgilityLevel = 0;

    /**
     * Base success rate at the required level (for agility shortcuts that can fail).
     * 1.0 = always succeeds at required level, lower values mean failure is possible.
     * Most shortcuts have 0.9 (90%) base success rate at the required level.
     */
    @Builder.Default
    double baseSuccessRate = 1.0;

    /**
     * Ticks added to path cost for traversing this obstacle.
     * Used by pathfinding algorithms to weight routes.
     */
    @Builder.Default
    int traversalCostTicks = 2;

    // ========================================================================
    // Toll Gate Fields
    // ========================================================================

    /**
     * Gold cost to pass through this obstacle (toll gates).
     * 0 means no toll cost.
     */
    @Builder.Default
    int tollCost = 0;

    /**
     * Minimum total gold (inventory + bank) a player should have before using this toll gate.
     * This prevents depleting all resources just to pass through.
     * 0 means no minimum requirement (always allow if player has the toll).
     */
    @Builder.Default
    int minimumGoldToUse = 0;

    /**
     * Quest that grants free passage through this toll gate (null if no free passage quest).
     * If the player has completed this quest, they don't need to pay the toll.
     */
    @Nullable
    Quest freePassageQuest;

    /**
     * Item ID required to pass through (e.g., Shantay Pass item for Shantay Pass).
     * -1 means no item required.
     */
    @Builder.Default
    int requiredItemId = -1;

    // ========================================================================
    // Quest Requirement Fields
    // ========================================================================

    /**
     * Quest required to use this obstacle (null if no quest requirement).
     * The quest must be FINISHED for the obstacle to be usable.
     */
    @Nullable
    Quest requiredQuest;

    // ========================================================================
    // Location Fields
    // ========================================================================

    /**
     * World map location of this obstacle (for world map display and pathfinding).
     * May be null if location is not fixed or not relevant.
     */
    @Nullable
    WorldPoint worldMapLocation;

    /**
     * World location of the obstacle object itself.
     * Used when the world map location differs from the actual interactable location.
     */
    @Nullable
    WorldPoint worldLocation;

    /**
     * Destination plane after traversing (for plane transitions like stairs/ladders).
     * -1 means same plane (no plane change).
     */
    @Builder.Default
    int destinationPlane = -1;

    /**
     * Destination point after traversing (for obstacles that move the player).
     * May be null if destination is implicit or varies.
     */
    @Nullable
    WorldPoint destinationPoint;

    // ========================================================================
    // State Checking Methods
    // ========================================================================

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

    // ========================================================================
    // Agility Level Methods
    // ========================================================================

    /**
     * Check if a player can attempt this obstacle based on agility level.
     *
     * @param playerAgilityLevel the player's current agility level
     * @return true if the player meets the level requirement
     */
    public boolean canAttempt(int playerAgilityLevel) {
        return playerAgilityLevel >= requiredAgilityLevel;
    }

    /**
     * Calculate the success rate for a player attempting this obstacle.
     * 
     * <p>Formula based on OSRS mechanics:
     * <ul>
     *   <li>At required level: base success rate (typically 90%)</li>
     *   <li>Every 5 levels above required: +2% success rate</li>
     *   <li>At 99 agility: most shortcuts have ~100% success rate</li>
     * </ul>
     *
     * @param playerAgilityLevel the player's current agility level
     * @return success rate between 0.0 and 1.0, or 0.0 if player doesn't meet requirements
     */
    public double calculateSuccessRate(int playerAgilityLevel) {
        if (playerAgilityLevel < requiredAgilityLevel) {
            return 0.0;
        }

        // If base rate is already 1.0, it always succeeds
        if (baseSuccessRate >= 1.0) {
            return 1.0;
        }

        // Calculate bonus from levels above requirement
        int levelsAbove = playerAgilityLevel - requiredAgilityLevel;
        double bonus = (levelsAbove / 5.0) * 0.02; // +2% per 5 levels

        // Cap at 100%
        return Math.min(1.0, baseSuccessRate + bonus);
    }

    /**
     * Calculate the failure rate for a player attempting this obstacle.
     *
     * @param playerAgilityLevel the player's current agility level
     * @return failure rate between 0.0 and 1.0
     */
    public double calculateFailureRate(int playerAgilityLevel) {
        return 1.0 - calculateSuccessRate(playerAgilityLevel);
    }

    /**
     * Check if this obstacle is considered "risky" for a player.
     * A shortcut is risky if the failure rate exceeds the threshold.
     *
     * @param playerAgilityLevel the player's current agility level
     * @param riskThreshold      maximum acceptable failure rate (e.g., 0.1 for 10%)
     * @return true if failure rate exceeds the threshold
     */
    public boolean isRisky(int playerAgilityLevel, double riskThreshold) {
        return calculateFailureRate(playerAgilityLevel) > riskThreshold;
    }

    // ========================================================================
    // Type Checking Methods
    // ========================================================================

    /**
     * Check if this is an agility shortcut.
     *
     * @return true if type is AGILITY_SHORTCUT
     */
    public boolean isAgilityShortcut() {
        return type == ObstacleType.AGILITY_SHORTCUT;
    }

    /**
     * Check if this is a door or gate.
     *
     * @return true if type is DOOR or GATE
     */
    public boolean isDoorOrGate() {
        return type == ObstacleType.DOOR || type == ObstacleType.GATE;
    }

    /**
     * Check if this is a plane transition (stairs, ladder, trapdoor).
     *
     * @return true if type is STAIRS, LADDER, or TRAPDOOR
     */
    public boolean isPlaneTransition() {
        return type == ObstacleType.STAIRS || 
               type == ObstacleType.LADDER || 
               type == ObstacleType.TRAPDOOR;
    }

    /**
     * Check if this obstacle has a quest requirement.
     *
     * @return true if a quest must be completed
     */
    public boolean hasQuestRequirement() {
        return requiredQuest != null;
    }

    // ========================================================================
    // Toll Gate Methods
    // ========================================================================

    /**
     * Check if this is a toll gate requiring payment.
     *
     * @return true if this is a toll gate
     */
    public boolean isTollGate() {
        return type == ObstacleType.TOLL_GATE || tollCost > 0;
    }

    /**
     * Check if a player has free passage through this toll gate.
     * Free passage is granted if the player has completed the freePassageQuest.
     *
     * @param questCompleted whether the free passage quest is completed
     * @return true if the player has free passage
     */
    public boolean hasFreePassage(boolean questCompleted) {
        return freePassageQuest != null && questCompleted;
    }

    /**
     * Check if a player can afford to use this toll gate.
     *
     * @param playerTotalGold total gold (inventory + bank)
     * @param hasRequiredItem whether the player has the required item (if any)
     * @return true if the player can use this toll gate
     */
    public boolean canAffordToll(int playerTotalGold, boolean hasRequiredItem) {
        // Check item requirement
        if (requiredItemId > 0 && !hasRequiredItem) {
            return false;
        }

        // Check gold requirement
        if (tollCost > 0) {
            // Must have enough gold after paying the toll to meet minimum threshold
            return playerTotalGold >= tollCost && 
                   (minimumGoldToUse == 0 || playerTotalGold >= minimumGoldToUse);
        }

        return true;
    }

    /**
     * Check if using this toll gate is advisable (has enough gold cushion).
     * This is more conservative than canAffordToll - it ensures the player
     * won't be depleted of resources.
     *
     * @param playerTotalGold total gold (inventory + bank)
     * @return true if using this toll gate is advisable
     */
    public boolean shouldUseTollGate(int playerTotalGold) {
        if (!isTollGate()) {
            return true;
        }
        return minimumGoldToUse > 0 && playerTotalGold >= minimumGoldToUse;
    }

    // ========================================================================
    // Obstacle Types
    // ========================================================================

    /**
     * Types of obstacles that can block or require interaction for navigation.
     */
    public enum ObstacleType {
        /**
         * Standard door that can be opened.
         */
        DOOR,

        /**
         * Gate that can be opened.
         */
        GATE,

        /**
         * Toll gate requiring payment (e.g., Al Kharid gate, Shantay Pass).
         */
        TOLL_GATE,

        /**
         * Agility shortcut requiring a minimum agility level.
         */
        AGILITY_SHORTCUT,

        /**
         * Ladder for climbing up or down between planes.
         */
        LADDER,

        /**
         * Staircase for moving between floors.
         */
        STAIRS,

        /**
         * Trapdoor for moving down to lower levels.
         */
        TRAPDOOR,

        /**
         * Other interactive obstacle (wilderness ditch, etc.).
         */
        OTHER
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a simple door obstacle.
     *
     * @param name       door name for logging
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
     * @param name       gate name for logging
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
     * Create a toll gate obstacle that requires payment.
     *
     * @param name              gate name for logging
     * @param objectId          object ID of the gate
     * @param action            menu action (e.g., "Pay-toll(10gp)")
     * @param tollCost          gold cost to pass through
     * @param minimumGold       minimum total gold player should have before using
     * @param freePassageQuest  quest that grants free passage (null if none)
     * @return the obstacle definition
     */
    public static ObstacleDefinition tollGate(String name, int objectId, String action,
                                               int tollCost, int minimumGold,
                                               @Nullable Quest freePassageQuest) {
        return ObstacleDefinition.builder()
                .name(name)
                .type(ObstacleType.TOLL_GATE)
                .objectIds(List.of(objectId))
                .blockedStateId(objectId)
                .passableStateId(-1)
                .action(action)
                .tollCost(tollCost)
                .minimumGoldToUse(minimumGold)
                .freePassageQuest(freePassageQuest)
                .traversalCostTicks(10) // High cost to discourage unless necessary
                .build();
    }

    /**
     * Create a toll gate that requires an item to pass (e.g., Shantay Pass).
     *
     * @param name              gate name for logging
     * @param objectId          object ID of the gate
     * @param action            menu action
     * @param requiredItemId    item ID required to pass
     * @param itemCost          gold cost to buy the required item
     * @param minimumGold       minimum total gold player should have before using
     * @return the obstacle definition
     */
    public static ObstacleDefinition itemTollGate(String name, int objectId, String action,
                                                   int requiredItemId, int itemCost, int minimumGold) {
        return ObstacleDefinition.builder()
                .name(name)
                .type(ObstacleType.TOLL_GATE)
                .objectIds(List.of(objectId))
                .blockedStateId(objectId)
                .passableStateId(-1)
                .action(action)
                .requiredItemId(requiredItemId)
                .tollCost(itemCost)
                .minimumGoldToUse(minimumGold)
                .traversalCostTicks(15) // Higher cost due to item requirement complexity
                .build();
    }

    /**
     * Create an agility shortcut obstacle with a single object ID.
     *
     * @param name           shortcut name
     * @param objectId       primary object ID
     * @param action         menu action (e.g., "Climb", "Squeeze-through")
     * @param agilityLevel   required agility level
     * @param baseSuccessRate base success rate at required level (typically 0.9)
     * @return the obstacle definition
     */
    public static ObstacleDefinition agilityShortcut(String name, int objectId, String action,
                                                      int agilityLevel, double baseSuccessRate) {
        return ObstacleDefinition.builder()
                .name(name)
                .type(ObstacleType.AGILITY_SHORTCUT)
                .objectIds(List.of(objectId))
                .blockedStateId(objectId)
                .passableStateId(-1) // Shortcuts don't change state
                .action(action)
                .requiredAgilityLevel(agilityLevel)
                .baseSuccessRate(baseSuccessRate)
                .traversalCostTicks(3)
                .build();
    }

    /**
     * Create an agility shortcut obstacle with multiple object IDs.
     *
     * @param name           shortcut name
     * @param objectIds      list of object IDs for this shortcut
     * @param action         menu action
     * @param agilityLevel   required agility level
     * @param baseSuccessRate base success rate at required level
     * @param worldLocation  world location of the shortcut
     * @return the obstacle definition
     */
    public static ObstacleDefinition agilityShortcut(String name, List<Integer> objectIds, 
                                                      String action, int agilityLevel, 
                                                      double baseSuccessRate,
                                                      @Nullable WorldPoint worldLocation) {
        return ObstacleDefinition.builder()
                .name(name)
                .type(ObstacleType.AGILITY_SHORTCUT)
                .objectIds(objectIds)
                .blockedStateId(objectIds.isEmpty() ? -1 : objectIds.get(0))
                .passableStateId(-1)
                .action(action)
                .requiredAgilityLevel(agilityLevel)
                .baseSuccessRate(baseSuccessRate)
                .traversalCostTicks(3)
                .worldLocation(worldLocation)
                .build();
    }

    /**
     * Create a ladder obstacle.
     *
     * @param name          ladder name
     * @param objectId      object ID
     * @param action        menu action ("Climb-up", "Climb-down", "Climb")
     * @param destPlane     destination plane after climbing
     * @return the obstacle definition
     */
    public static ObstacleDefinition ladder(String name, int objectId, String action, int destPlane) {
        return ObstacleDefinition.builder()
                .name(name)
                .type(ObstacleType.LADDER)
                .objectIds(List.of(objectId))
                .blockedStateId(objectId)
                .passableStateId(-1)
                .action(action)
                .traversalCostTicks(3)
                .destinationPlane(destPlane)
                .build();
    }

    /**
     * Create a staircase obstacle.
     *
     * @param name          staircase name
     * @param objectId      object ID
     * @param action        menu action ("Climb-up", "Climb-down", "Climb")
     * @param destPlane     destination plane after climbing
     * @return the obstacle definition
     */
    public static ObstacleDefinition staircase(String name, int objectId, String action, int destPlane) {
        return ObstacleDefinition.builder()
                .name(name)
                .type(ObstacleType.STAIRS)
                .objectIds(List.of(objectId))
                .blockedStateId(objectId)
                .passableStateId(-1)
                .action(action)
                .traversalCostTicks(3)
                .destinationPlane(destPlane)
                .build();
    }

    /**
     * Create a trapdoor obstacle.
     *
     * @param name          trapdoor name
     * @param closedId      object ID when closed
     * @param openId        object ID when open (or -1 if single state)
     * @param action        menu action ("Open", "Climb-down")
     * @param destPlane     destination plane
     * @return the obstacle definition
     */
    public static ObstacleDefinition trapdoor(String name, int closedId, int openId, 
                                               String action, int destPlane) {
        List<Integer> ids = openId > 0 ? List.of(closedId, openId) : List.of(closedId);
        return ObstacleDefinition.builder()
                .name(name)
                .type(ObstacleType.TRAPDOOR)
                .objectIds(ids)
                .blockedStateId(closedId)
                .passableStateId(openId)
                .action(action)
                .traversalCostTicks(3)
                .destinationPlane(destPlane)
                .build();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Obstacle[").append(name).append(", ").append(type);
        if (requiredAgilityLevel > 0) {
            sb.append(", lvl=").append(requiredAgilityLevel);
        }
        if (requiredQuest != null) {
            sb.append(", quest=").append(requiredQuest.getName());
        }
        sb.append(", IDs=").append(objectIds).append("]");
        return sb.toString();
    }
}
