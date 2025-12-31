package com.rocinante.navigation;

import net.runelite.api.Quest;
import net.runelite.api.coords.WorldPoint;

/**
 * Interface for checking player requirements.
 * 
 * <p>This abstraction provides queries about the player's current state
 * for use by tasks and navigation systems.
 *
 * <p>NOTE: Edge traversal requirements (quests, skills, items for transports)
 * are handled internally by the Shortest Path plugin. This interface focuses
 * on player state queries that tasks may need.
 *
 * <p>Implementations should delegate to {@link com.rocinante.progression.UnlockTracker}
 * and {@link com.rocinante.core.GameStateService} for actual state queries.
 */
public interface PlayerRequirements {

    // ========================================================================
    // Skill Levels
    // ========================================================================

    /**
     * Get the player's agility level.
     *
     * @return agility level (1-99)
     */
    int getAgilityLevel();

    /**
     * Get the player's magic level.
     *
     * @return magic level (1-99)
     */
    int getMagicLevel();

    /**
     * Get the player's combat level.
     *
     * @return combat level (3-126)
     */
    int getCombatLevel();

    /**
     * Get a skill level by name.
     *
     * @param skillName the skill name (e.g., "AGILITY", "MINING")
     * @return the skill level, or 1 if unknown
     */
    int getSkillLevel(String skillName);

    // ========================================================================
    // Resources
    // ========================================================================

    /**
     * Get the player's total gold (inventory + bank).
     *
     * @return total gold coins
     */
    int getTotalGold();

    /**
     * Get the player's inventory gold only.
     *
     * @return inventory gold coins
     */
    int getInventoryGold();

    /**
     * Check if the player has a specific item.
     *
     * @param itemId the item ID
     * @return true if player has at least one of the item
     */
    boolean hasItem(int itemId);

    /**
     * Check if the player has a specific item in sufficient quantity.
     *
     * @param itemId   the item ID
     * @param quantity the required quantity
     * @return true if player has at least the specified quantity
     */
    boolean hasItem(int itemId, int quantity);

    // ========================================================================
    // Quest Completion
    // ========================================================================

    /**
     * Check if a quest is completed by name.
     *
     * @param questName the quest name
     * @return true if quest is completed
     */
    boolean isQuestCompleted(String questName);

    /**
     * Check if a quest is completed by enum.
     *
     * @param quest the quest enum
     * @return true if quest is completed
     */
    boolean isQuestCompleted(Quest quest);

    // ========================================================================
    // Account Type
    // ========================================================================

    /**
     * Check if the player is any type of ironman.
     *
     * @return true if ironman, hardcore ironman, or ultimate ironman
     */
    boolean isIronman();

    /**
     * Check if the player is a hardcore ironman.
     *
     * @return true if hardcore ironman
     */
    boolean isHardcoreIronman();

    /**
     * Check if the player is an ultimate ironman.
     *
     * @return true if ultimate ironman
     */
    boolean isUltimateIronman();

    // ========================================================================
    // Location
    // ========================================================================

    /**
     * Get the player's current world position.
     *
     * @return player position, or null if unknown
     */
    WorldPoint getCurrentPosition();

    /**
     * Get the current wilderness level (0 if not in wilderness).
     *
     * @return wilderness level 0-56, or 0 if not in wilderness
     */
    int getCurrentWildernessLevel();

    // ========================================================================
    // Risk Tolerance
    // ========================================================================

    /**
     * Get the acceptable risk threshold for agility shortcuts.
     * A threshold of 0.10 means shortcuts with >10% failure rate are avoided.
     *
     * @return maximum acceptable failure rate (0.0 to 1.0)
     */
    double getAcceptableRiskThreshold();

    /**
     * Check if wilderness should be avoided.
     * Always true for hardcore ironmen.
     *
     * @return true to avoid wilderness routes
     */
    boolean shouldAvoidWilderness();

    /**
     * Wilderness Y coordinate threshold.
     * Any location with Y >= 3520 on plane 0 is considered wilderness.
     */
    int WILDERNESS_Y_THRESHOLD = 3520;

    // ========================================================================
    // Free Teleport Availability (Home/Grouping)
    // ========================================================================

    /**
     * Check if home teleport is available (not on cooldown).
     *
     * @return true if home teleport can be used
     */
    boolean isHomeTeleportAvailable();

    /**
     * Check if minigame/grouping teleport is available (not on cooldown).
     *
     * @return true if minigame teleport can be used
     */
    boolean isMinigameTeleportAvailable();

    /**
     * Check if a specific grouping teleport is unlocked (requirements met).
     * Does NOT check cooldown - use {@link #isMinigameTeleportAvailable()} for that.
     *
     * @param teleportId the grouping teleport edge ID (e.g., "barbarian_assault")
     * @return true if the teleport's unlock requirements are met
     */
    boolean isGroupingTeleportUnlocked(String teleportId);

    /**
     * Get the current home teleport destination.
     * This is the player's active respawn point.
     *
     * @return the home teleport destination
     */
    WorldPoint getHomeTeleportDestination();

    /**
     * Get the active respawn point type.
     *
     * @return the active respawn point
     */
    RespawnPoint getActiveRespawnPoint();

    // ========================================================================
    // Edge Requirement Checking
    // ========================================================================

    /**
     * Check if a single edge requirement is met.
     *
     * @param requirement the requirement to check
     * @return true if requirement is satisfied
     */
    default boolean meetsRequirement(EdgeRequirement requirement) {
        if (requirement == null) {
            return true;
        }

        switch (requirement.getType()) {
            case MAGIC_LEVEL:
                return getMagicLevel() >= requirement.getValue();
            
            case AGILITY_LEVEL:
                return getAgilityLevel() >= requirement.getValue();
            
            case COMBAT_LEVEL:
                return getCombatLevel() >= requirement.getValue();
            
            case SKILL:
                return getSkillLevel(requirement.getIdentifier()) >= requirement.getValue();
            
            case QUEST:
                return isQuestCompleted(requirement.getIdentifier());
            
            case ITEM:
                return hasItem(requirement.getItemId(), Math.max(1, requirement.getValue()));

            case GOLD:
                return getInventoryGold() >= requirement.getValue();
            
            case RUNES:
                if (requirement.getRuneCosts() == null) {
                    return true;
                }
                for (EdgeRequirement.RuneCost cost : requirement.getRuneCosts()) {
                    if (!hasItem(cost.getItemId(), cost.getQuantity())) {
                        return false;
                    }
                }
                return true;
            
            case IRONMAN_RESTRICTION:
                // If there's an ironman restriction and player is ironman, block
                if (isIronman() && requirement.getIdentifier() != null) {
                    return false;
                }
                return true;
            
            default:
                return false;
        }
    }

    // ========================================================================
    // Wilderness Helpers
    // ========================================================================

    /**
     * Check if a world point is in the wilderness.
     *
     * @param point the point to check
     * @return true if in wilderness
     */
    default boolean isInWilderness(WorldPoint point) {
        if (point == null) {
            return false;
        }
        return point.getPlane() == 0 && point.getY() >= WILDERNESS_Y_THRESHOLD;
    }
}
