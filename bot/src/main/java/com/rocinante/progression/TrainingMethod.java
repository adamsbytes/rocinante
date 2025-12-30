package com.rocinante.progression;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import java.util.List;

/**
 * Immutable configuration for a skill training method.
 *
 * <p>Training methods are loaded from {@code data/training_methods.json} and define
 * everything needed to train a skill: locations, objects to interact with, items
 * to use, banking behavior, and XP rates.
 *
 * <p>Each method can have multiple locations with different efficiency rates.
 * The UI allows users to select which location to use.
 *
 * <p>Example methods:
 * <ul>
 *   <li>Mining: "iron_ore_powermine" - Power mine iron ore at multiple locations</li>
 *   <li>Woodcutting: "willows_banking" - Cut willows at Draynor, bank logs</li>
 *   <li>Fletching: "maple_longbow_u" - Fletch maple logs into unstrung bows</li>
 * </ul>
 *
 * @see TrainingMethodRepository
 * @see MethodType
 * @see MethodLocation
 */
@Value
@Builder(toBuilder = true)
public class TrainingMethod {

    // ========================================================================
    // Identification
    // ========================================================================

    /**
     * Unique identifier for this method (e.g., "iron_ore_powermine").
     */
    String id;

    /**
     * Human-readable name (e.g., "Mining - Iron Ore (Power)").
     */
    String name;

    /**
     * The skill this method trains.
     */
    Skill skill;

    /**
     * Type of training method (GATHER, PROCESS, COMBAT, etc.).
     */
    MethodType methodType;

    // ========================================================================
    // Level Requirements
    // ========================================================================

    /**
     * Minimum skill level required to use this method.
     */
    int minLevel;

    // ========================================================================
    // XP Configuration
    // ========================================================================

    /**
     * XP gained per successful action.
     * For methods with level-based XP (xpMultiplier > 0), this is ignored.
     */
    double xpPerAction;

    /**
     * Multiplier for level-based XP calculation.
     * When > 0, XP per action = level * xpMultiplier (overrides xpPerAction).
     * Examples: Arceuus Library Magic = 15, Arceuus Library Runecraft = 5.
     */
    @Builder.Default
    double xpMultiplier = 0;

    /**
     * Estimated GP profit/loss per hour (negative = cost).
     */
    @Builder.Default
    int gpPerHour = 0;

    // ========================================================================
    // Locations
    // ========================================================================

    /**
     * Available locations where this method can be performed.
     * Each location has its own efficiency rate and requirements.
     * At least one location is required.
     */
    @Builder.Default
    List<MethodLocation> locations = List.of();

    // ========================================================================
    // Requirements
    // ========================================================================

    /**
     * Method-level requirements (applies to all locations).
     * Individual locations may have additional requirements.
     */
    @Builder.Default
    MethodRequirements requirements = MethodRequirements.none();

    // ========================================================================
    // Gathering Configuration (GATHER methods)
    // ========================================================================

    /**
     * Object IDs to interact with (rocks, trees, fishing spots).
     * Multiple IDs support objects with different states (e.g., full/depleted rocks).
     */
    @Builder.Default
    List<Integer> targetObjectIds = List.of();

    /**
     * NPC IDs for fishing spot-style interactions.
     */
    @Builder.Default
    List<Integer> targetNpcIds = List.of();

    /**
     * Menu action to use (e.g., "Mine", "Chop down", "Lure").
     */
    String menuAction;

    /**
     * Animation ID played during successful action.
     * Used to verify interaction started correctly.
     */
    @Builder.Default
    int successAnimationId = -1;

    // ========================================================================
    // Inventory Handling
    // ========================================================================

    /**
     * Whether this method requires free inventory space.
     */
    @Builder.Default
    boolean requiresInventorySpace = true;

    /**
     * If true, drop products when inventory is full (power training).
     * If false, bank products when full.
     */
    @Builder.Default
    boolean dropWhenFull = false;

    /**
     * Item IDs produced by this method (for dropping or banking).
     */
    @Builder.Default
    List<Integer> productItemIds = List.of();

    // ========================================================================
    // Processing Configuration (PROCESS methods)
    // ========================================================================

    /**
     * Source item ID to use (e.g., knife, hammer).
     * -1 if no source item needed.
     */
    @Builder.Default
    int sourceItemId = -1;

    /**
     * Target item ID to use source on (e.g., logs, ore).
     * -1 if not applicable.
     */
    @Builder.Default
    int targetItemId = -1;

    /**
     * Object ID for processing (e.g., furnace, anvil, cooking range).
     * -1 if using item-on-item instead.
     */
    @Builder.Default
    int processingObjectId = -1;

    /**
     * Expected output item ID.
     */
    @Builder.Default
    int outputItemId = -1;

    /**
     * Number of outputs per action (e.g., 15 arrowtips per bar).
     */
    @Builder.Default
    int outputPerAction = 1;

    /**
     * Widget ID for make-all interface (usually 270).
     */
    @Builder.Default
    int makeAllWidgetId = 270;

    /**
     * Child widget ID for the specific item in make-all interface.
     * -1 to use default (usually first option).
     */
    @Builder.Default
    int makeAllChildId = -1;

    // ========================================================================
    // Tool Requirements
    // ========================================================================

    /**
     * Item IDs of required tools (pickaxe, axe, hammer, etc.).
     * Must be in inventory or equipped.
     */
    @Builder.Default
    List<Integer> requiredItemIds = List.of();

    /**
     * Whether this method is viable for ironman accounts.
     * Methods requiring GE supplies are not ironman viable.
     */
    @Builder.Default
    boolean ironmanViable = true;

    // ========================================================================
    // Ground Item Watching
    // ========================================================================

    /**
     * Ground items to watch for and pick up during training.
     * Used for marks of grace (agility), bird's nests (woodcutting), etc.
     */
    @Builder.Default
    List<GroundItemWatch> watchedGroundItems = List.of();

    // ========================================================================
    // Agility Configuration
    // ========================================================================

    /**
     * Reference to an agility course ID (for AGILITY method type).
     * Maps to a course definition in agility_courses.json.
     */
    String courseId;

    // ========================================================================
    // Minigame Configuration
    // ========================================================================

    /**
     * Minigame identifier for MINIGAME method type.
     * Examples: "wintertodt", "tempoross", "guardians_of_the_rift".
     */
    String minigameId;

    /**
     * Minigame strategy (e.g., "simple", "fletch" for Wintertodt).
     */
    String minigameStrategy;

    // ========================================================================
    // Firemaking Configuration
    // ========================================================================

    /**
     * Log item ID for FIREMAKING method type.
     */
    @Builder.Default
    int logItemId = -1;

    // ========================================================================
    // Notes
    // ========================================================================

    /**
     * Optional notes or tips for this method.
     */
    String notes;

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if this method is valid for a given level.
     *
     * @param level the player's current level
     * @return true if level >= minLevel
     */
    public boolean isValidForLevel(int level) {
        return level >= minLevel;
    }

    /**
     * Check if this method uses level-based XP calculation.
     *
     * @return true if xpMultiplier is set (> 0)
     */
    public boolean hasLevelBasedXp() {
        return xpMultiplier > 0;
    }

    /**
     * Get XP per action at a specific level.
     * For level-based methods, calculates level * xpMultiplier.
     * For static methods, returns xpPerAction.
     *
     * @param level the player's current level in the trained skill
     * @return XP gained per successful action
     */
    public double getXpPerAction(int level) {
        if (hasLevelBasedXp()) {
            return level * xpMultiplier;
        }
        return xpPerAction;
    }

    /**
     * Get the default/first location.
     *
     * @return the first location, or null if none defined
     */
    public MethodLocation getDefaultLocation() {
        if (locations == null || locations.isEmpty()) {
            return null;
        }
        return locations.get(0);
    }

    /**
     * Get a location by its ID.
     *
     * @param locationId the location ID to find
     * @return the location, or null if not found
     */
    public MethodLocation getLocation(String locationId) {
        if (locations == null || locationId == null) {
            return null;
        }
        return locations.stream()
                .filter(loc -> locationId.equals(loc.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Calculate XP per hour at the default location.
     *
     * @param level the player's current level (for level-based methods)
     * @return estimated XP per hour, or 0 if no locations defined
     */
    public double getXpPerHour(int level) {
        MethodLocation loc = getDefaultLocation();
        if (loc == null) {
            return 0;
        }
        return getXpPerAction(level) * loc.getActionsPerHour();
    }

    /**
     * Calculate XP per hour at a specific location.
     *
     * @param level the player's current level
     * @param locationId the location to calculate for
     * @return estimated XP per hour
     */
    public double getXpPerHour(int level, String locationId) {
        MethodLocation loc = getLocation(locationId);
        if (loc == null) {
            loc = getDefaultLocation();
        }
        if (loc == null) {
            return 0;
        }
        return getXpPerAction(level) * loc.getActionsPerHour();
    }

    /**
     * Get the XP/hr range across all locations (min to max).
     *
     * @param level the player's current level
     * @return array of [minXpPerHour, maxXpPerHour]
     */
    public double[] getXpPerHourRange(int level) {
        if (locations == null || locations.isEmpty()) {
            return new double[]{0, 0};
        }
        
        double xpPerAction = getXpPerAction(level);
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        
        for (MethodLocation loc : locations) {
            double xpHr = xpPerAction * loc.getActionsPerHour();
            min = Math.min(min, xpHr);
            max = Math.max(max, xpHr);
        }
        
        return new double[]{min, max};
    }

    /**
     * Check if this is a gathering method (mining, woodcutting, fishing).
     *
     * @return true if method type is GATHER
     */
    public boolean isGatheringMethod() {
        return methodType == MethodType.GATHER;
    }

    /**
     * Check if this is a processing method (fletching, crafting, etc.).
     *
     * @return true if method type is PROCESS
     */
    public boolean isProcessingMethod() {
        return methodType == MethodType.PROCESS;
    }

    /**
     * Check if this method requires banking (not power training).
     *
     * @return true if method uses banking instead of dropping
     */
    public boolean requiresBanking() {
        return requiresInventorySpace && !dropWhenFull;
    }

    /**
     * Check if this method has target objects to interact with.
     *
     * @return true if targetObjectIds is not empty
     */
    public boolean hasTargetObjects() {
        return targetObjectIds != null && !targetObjectIds.isEmpty();
    }

    /**
     * Check if this method has target NPCs to interact with.
     *
     * @return true if targetNpcIds is not empty
     */
    public boolean hasTargetNpcs() {
        return targetNpcIds != null && !targetNpcIds.isEmpty();
    }

    /**
     * Check if this method uses item-on-item processing.
     *
     * @return true if sourceItemId and targetItemId are set
     */
    public boolean usesItemOnItem() {
        return sourceItemId > 0 && targetItemId > 0;
    }

    /**
     * Check if this method uses an object for processing (furnace, anvil, etc.).
     *
     * @return true if processingObjectId is set
     */
    public boolean usesProcessingObject() {
        return processingObjectId > 0;
    }

    /**
     * Check if this method has ground items to watch for.
     *
     * @return true if watchedGroundItems is not empty
     */
    public boolean hasWatchedGroundItems() {
        return watchedGroundItems != null && !watchedGroundItems.isEmpty();
    }

    /**
     * Check if this is an agility course method.
     *
     * @return true if method type is AGILITY and courseId is set
     */
    public boolean isAgilityCourse() {
        return methodType == MethodType.AGILITY && courseId != null && !courseId.isEmpty();
    }

    /**
     * Check if this is a minigame-based training method.
     *
     * @return true if method type is MINIGAME and minigameId is set
     */
    public boolean isMinigameMethod() {
        return methodType == MethodType.MINIGAME && minigameId != null && !minigameId.isEmpty();
    }

    /**
     * Check if this is a firemaking method.
     *
     * @return true if method type is FIREMAKING and logItemId is set
     */
    public boolean isFiremakingMethod() {
        return methodType == MethodType.FIREMAKING && logItemId > 0;
    }

    /**
     * Check if this method has any requirements.
     *
     * @return true if method-level requirements exist
     */
    public boolean hasRequirements() {
        return requirements != null && requirements.hasRequirements();
    }

    /**
     * Check if this method requires membership.
     *
     * @return true if method is members-only
     */
    public boolean requiresMembership() {
        return requirements != null && requirements.isMembers();
    }

    /**
     * Get number of available locations.
     *
     * @return count of locations
     */
    public int getLocationCount() {
        return locations == null ? 0 : locations.size();
    }

    /**
     * Check if this method has multiple location options.
     *
     * @return true if more than one location is available
     */
    public boolean hasMultipleLocations() {
        return getLocationCount() > 1;
    }

    /**
     * Get a summary string for logging.
     *
     * @return human-readable summary
     */
    public String getSummary() {
        double[] xpRange = getXpPerHourRange(minLevel);
        if (hasLevelBasedXp()) {
            return String.format("%s [%s] (lvl %d+, %.0fx level xp/action, %d locations)",
                    name,
                    skill.getName(),
                    minLevel,
                    xpMultiplier,
                    getLocationCount());
        }
        
        if (xpRange[0] == xpRange[1]) {
            return String.format("%s [%s] (lvl %d+, %,.0f xp/hr, %d locations)",
                    name,
                    skill.getName(),
                    minLevel,
                    xpRange[0],
                    getLocationCount());
        }
        
        return String.format("%s [%s] (lvl %d+, %,.0f-%,.0f xp/hr, %d locations)",
                name,
                skill.getName(),
                minLevel,
                xpRange[0],
                xpRange[1],
                getLocationCount());
    }

    /**
     * Get a summary string with level-specific XP rates.
     *
     * @param level the player's current level
     * @return human-readable summary with actual XP rates
     */
    public String getSummary(int level) {
        double[] xpRange = getXpPerHourRange(level);
        
        if (xpRange[0] == xpRange[1]) {
            return String.format("%s [%s] (lvl %d+, %,.0f xp/hr @ lvl %d)",
                    name,
                    skill.getName(),
                    minLevel,
                    xpRange[0],
                    level);
        }
        
        return String.format("%s [%s] (lvl %d+, %,.0f-%,.0f xp/hr @ lvl %d)",
                name,
                skill.getName(),
                minLevel,
                xpRange[0],
                xpRange[1],
                level);
    }
}
