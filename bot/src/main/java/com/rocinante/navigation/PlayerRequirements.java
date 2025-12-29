package com.rocinante.navigation;

import net.runelite.api.Quest;
import net.runelite.api.coords.WorldPoint;

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
    // Location and Wilderness
    // ========================================================================

    /**
     * Get the player's current world location.
     * Required for wilderness teleport level restrictions.
     *
     * @return the player's current location, or null if unavailable
     */
    default WorldPoint getCurrentLocation() {
        return null; // Default implementation - subclasses should override
    }

    /**
     * Get the current wilderness level at the player's location.
     *
     * @return wilderness level (0 if not in wilderness)
     */
    default int getCurrentWildernessLevel() {
        WorldPoint loc = getCurrentLocation();
        return loc != null ? WildernessTeleportRestrictions.getWildernessLevel(loc) : 0;
    }

    /**
     * Check if player has any level-30 wilderness teleport item (glory, etc.).
     * 
     * <p>These items allow teleportation up to level 30 wilderness instead of the
     * standard level 20 limit.
     *
     * @return true if player has any level-30 teleport item
     */
    default boolean hasLevel30WildernessTeleportItem() {
        for (int itemId : WildernessTeleportRestrictions.LEVEL_30_TELEPORT_ITEMS) {
            if (hasItem(itemId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the maximum wilderness level from which the player can teleport.
     * Returns 30 if player has level-30 items, otherwise 20.
     *
     * @return maximum teleportable wilderness level
     */
    default int getMaxTeleportWildernessLevel() {
        return hasLevel30WildernessTeleportItem() 
                ? WildernessTeleportRestrictions.ENHANCED_TELEPORT_LIMIT
                : WildernessTeleportRestrictions.STANDARD_TELEPORT_LIMIT;
    }

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
    // Account Type and Membership
    // ========================================================================

    /**
     * Check if the player has an active membership.
     * 
     * <p>This gates access to members-only content:
     * <ul>
     *   <li>Members-only areas (Kandarin, Morytania, etc.)</li>
     *   <li>Members-only transport methods (fairy rings, spirit trees, etc.)</li>
     *   <li>Members-only quests and content</li>
     * </ul>
     *
     * @return true if the account has members access
     */
    default boolean isMember() {
        return true; // Default assumes member - F2P players should override
    }

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

    /**
     * Wilderness Y coordinate threshold.
     * Any location with Y >= 3520 on plane 0 is considered wilderness.
     */
    int WILDERNESS_Y_THRESHOLD = 3520;

    /**
     * Check if an edge leads to or through wilderness.
     * Wilderness is defined as Y >= 3520 on plane 0.
     *
     * @param edge the edge to check
     * @return true if the edge goes to/through wilderness
     */
    default boolean isWildernessEdge(NavigationEdge edge) {
        if (edge == null) {
            return false;
        }

        // Check metadata for explicit wilderness tag
        if (edge.getMetadata() != null) {
            if ("true".equals(edge.getMetadata().get("wilderness"))) {
                return true;
            }
            // Explicit non-wilderness override (e.g., for areas near wilderness boundary)
            if ("false".equals(edge.getMetadata().get("wilderness"))) {
                return false;
            }
            // Check travel_type metadata
            String travelType = edge.getMetadata().get("travel_type");
            if (travelType != null) {
                // Fairy rings, spirit trees, and similar transports are never in wilderness
                if (travelType.equals("fairy_ring") || 
                    travelType.equals("spirit_tree") ||
                    travelType.equals("gnome_glider")) {
                    return false;
                }
                // Levers can go TO wilderness - check destination metadata
                if (travelType.equals("lever")) {
                    // Check if destination is wilderness
                    String destWilderness = edge.getMetadata().get("wilderness_level");
                    if (destWilderness != null) {
                        return true; // Lever goes to wilderness
                    }
                    // Check if edge leads to wilderness node
                    String toNodeId = edge.getToNodeId();
                    if (toNodeId != null && toNodeId.contains("wilderness")) {
                        return true;
                    }
                    return false; // Lever to safe area
                }
            }
        }

        // Check node IDs for wilderness keywords
        String toNodeId = edge.getToNodeId();
        if (toNodeId != null) {
            String lowerNodeId = toNodeId.toLowerCase();
            // Explicit wilderness node
            if (lowerNodeId.contains("wilderness") || lowerNodeId.contains("wild_")) {
                return true;
            }
            // Known safe area prefixes that aren't wilderness even at high Y coords
            if (lowerNodeId.startsWith("fairy_ring") || 
                lowerNodeId.startsWith("spirit_tree") ||
                lowerNodeId.startsWith("gnome_glider")) {
                return false;
            }
        }

        // Fall back to Y coordinate check for unknown areas
        // Note: This is imprecise - the actual wilderness boundary is complex
        WorldPoint toLocation = edge.getToLocation();
        if (toLocation != null && toLocation.getPlane() == 0 && toLocation.getY() >= WILDERNESS_Y_THRESHOLD) {
            return true;
        }

        return false;
    }

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

        // Check wilderness avoidance FIRST - critical for HCIM
        if (shouldAvoidWilderness() && isWildernessEdge(edge)) {
            return false;
        }
        
        // Check wilderness teleport restrictions BEFORE other teleport checks
        // This applies to teleport edges originating IN wilderness
        if (isTeleportEdge(edge)) {
            if (!canTeleportFromCurrentLocation(edge)) {
                return false;
            }
        }

        // Check FREE_TELEPORT edges (home/grouping teleports)
        if (edge.getType() == WebEdgeType.FREE_TELEPORT) {
            return canTraverseFreeTeleport(edge);
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

    /**
     * Check if a FREE_TELEPORT edge can be traversed.
     * This checks cooldown, unlock requirements, AND wilderness restrictions.
     * 
     * <p>Wilderness restrictions for free teleports:
     * <ul>
     *   <li>Grouping teleports: DO NOT WORK AT ALL in wilderness (any level)</li>
     *   <li>Home teleport: Blocked above level 20</li>
     * </ul>
     *
     * @param edge the FREE_TELEPORT edge
     * @return true if the teleport can be used
     */
    default boolean canTraverseFreeTeleport(NavigationEdge edge) {
        if (edge == null || edge.getMetadata() == null) {
            return false;
        }

        int wildernessLevel = getCurrentWildernessLevel();
        String teleportType = edge.getMetadata().get("teleport_type");
        
        if ("grouping".equals(teleportType)) {
            // Grouping teleports DO NOT WORK AT ALL in wilderness
            if (wildernessLevel > 0) {
                return false;
            }
            
            // Outside wilderness: check cooldown AND unlock requirements
            if (!isMinigameTeleportAvailable()) {
                return false;
            }
            
            String teleportId = edge.getMetadata().get("teleport_id");
            if (teleportId != null && !isGroupingTeleportUnlocked(teleportId)) {
                return false;
            }
            
            return true;
        } else if ("home".equals(teleportType)) {
            // Home teleport: blocked above level 20
            if (wildernessLevel > WildernessTeleportRestrictions.STANDARD_TELEPORT_LIMIT) {
                return false;
            }
            
            // Check cooldown
            if (!isHomeTeleportAvailable()) {
                return false;
            }
            
            // Check if this edge's respawn point matches the active respawn
            String respawnPointId = edge.getMetadata().get("respawn_point");
            if (respawnPointId != null) {
                RespawnPoint edgeRespawn = RespawnPoint.valueOf(respawnPointId);
                if (edgeRespawn != getActiveRespawnPoint()) {
                    return false;
                }
            }
            
            return true;
        }

        // Unknown teleport type
                return false;
            }
            
    // ========================================================================
    // Teleport Edge Helpers
    // ========================================================================
    
    /**
     * Check if an edge is any kind of teleport edge.
     *
     * @param edge the edge to check
     * @return true if this is a teleport edge
     */
    default boolean isTeleportEdge(NavigationEdge edge) {
        if (edge == null) {
            return false;
        }
        WebEdgeType type = edge.getType();
        return type == WebEdgeType.FREE_TELEPORT || type == WebEdgeType.TELEPORT;
    }
    
    /**
     * Check if the player can teleport from their current location using this edge.
     * 
     * <p>Wilderness teleport restrictions:
     * <ul>
     *   <li>Level 1-20: All teleports work</li>
     *   <li>Level 21-30: Only level-30 items (glory, combat bracelet, etc.)</li>
     *   <li>Level 31+: No teleports work at all</li>
     * </ul>
     *
     * @param edge the teleport edge
     * @return true if teleport is allowed from current location
     */
    default boolean canTeleportFromCurrentLocation(NavigationEdge edge) {
        int wildernessLevel = getCurrentWildernessLevel();
        
        // Not in wilderness - all teleports work
        if (wildernessLevel == 0) {
            return true;
        }
        
        // Above level 30 - nothing works
        if (wildernessLevel > WildernessTeleportRestrictions.ENHANCED_TELEPORT_LIMIT) {
                return false;
            }
            
        // Level 1-20 - all teleports work
        if (wildernessLevel <= WildernessTeleportRestrictions.STANDARD_TELEPORT_LIMIT) {
            return true;
        }

        // Level 21-30 - only level-30 items work
        // Check if this teleport uses a level-30 item
        if (edge.getMetadata() != null) {
            String itemIdStr = edge.getMetadata().get("item_id");
            if (itemIdStr != null) {
                try {
                    int itemId = Integer.parseInt(itemIdStr);
                    return WildernessTeleportRestrictions.LEVEL_30_TELEPORT_ITEMS.contains(itemId);
                } catch (NumberFormatException e) {
                    // Invalid item ID
                }
            }
        }
        
        // No item ID or not a level-30 item - blocked above level 20
        return false;
    }
}


