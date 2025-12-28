package com.rocinante.navigation;

import net.runelite.api.Quest;

/**
 * Interface for checking player requirements during pathfinding.
 * 
 * <p>This abstraction allows the navigation system to filter edges
 * based on the player's current state without tight coupling to
 * specific game state implementations.
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
     * @return true if player has enough of the item
     */
    boolean hasItem(int itemId, int quantity);

    // ========================================================================
    // Quest Completion
    // ========================================================================

    /**
     * Check if a quest is completed.
     *
     * @param quest the quest to check
     * @return true if quest is finished
     */
    boolean isQuestCompleted(Quest quest);

    /**
     * Check if a quest is completed by name.
     *
     * @param questName the quest name/ID
     * @return true if quest is finished
     */
    boolean isQuestCompleted(String questName);

    // ========================================================================
    // Account Type
    // ========================================================================

    /**
     * Check if the player is an ironman.
     *
     * @return true if ironman, hardcore ironman, or ultimate ironman
     */
    boolean isIronman();

    /**
     * Check if the player is a hardcore ironman.
     *
     * @return true if hardcore ironman
     */
    boolean isHardcore();

    /**
     * Check if the player is an ultimate ironman.
     *
     * @return true if ultimate ironman
     */
    boolean isUltimateIronman();

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

    /**
     * Check if all requirements for an edge are met.
     *
     * @param edge the navigation edge
     * @return true if all requirements are satisfied
     */
    default boolean canTraverseEdge(NavigationEdge edge) {
        if (edge == null) {
            return false;
        }

        // Check explicit requirements
        if (edge.hasRequirements()) {
            for (EdgeRequirement req : edge.getRequirements()) {
                if (!meetsRequirement(req)) {
                    return false;
                }
            }
        }

        // Check agility level for shortcuts
        if (edge.isAgilityShortcut()) {
            if (getAgilityLevel() < edge.getRequiredAgilityLevel()) {
                return false;
            }
            // Check risk threshold
            if (edge.getFailureRate() > getAcceptableRiskThreshold()) {
                return false;
            }
        }

        // Check toll gates
        if (edge.isTollGate()) {
            // Check for free passage via quest
            if (edge.getFreePassageQuest() != null && 
                isQuestCompleted(edge.getFreePassageQuest())) {
                return true;
            }
            // Check if can afford toll
            if (edge.getTollCost() > 0 && getInventoryGold() < edge.getTollCost()) {
                return false;
            }
            // Check required item
            if (edge.getRequiredItemId() > 0 && !hasItem(edge.getRequiredItemId())) {
                return false;
            }
        }

        return true;
    }
}

