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
 * everything needed to train a skill: location, objects to interact with, items
 * to use, banking behavior, and XP rates.
 *
 * <p>Example methods:
 * <ul>
 *   <li>Mining: "iron_ore_powermine" - Power mine iron ore at Al Kharid</li>
 *   <li>Woodcutting: "willows_banking" - Cut willows at Draynor, bank logs</li>
 *   <li>Fletching: "maple_longbow_u" - Fletch maple logs into unstrung bows</li>
 * </ul>
 *
 * @see TrainingMethodRepository
 * @see MethodType
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
     * Human-readable name (e.g., "Iron Ore (Power Mining)").
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

    /**
     * Maximum level this method is efficient for (-1 for no limit).
     * Used by SkillPlanner to select appropriate methods.
     */
    @Builder.Default
    int maxLevel = -1;

    // ========================================================================
    // XP and Efficiency
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
     * Theoretical maximum actions per hour (used for efficiency calculations).
     */
    int actionsPerHour;

    /**
     * Estimated GP profit/loss per hour (negative = cost).
     */
    @Builder.Default
    int gpPerHour = 0;

    // ========================================================================
    // Location
    // ========================================================================

    /**
     * Reference to a node in web.json for navigation.
     */
    String locationId;

    /**
     * Optional exact position to stand at.
     */
    WorldPoint exactPosition;

    /**
     * Bank location for methods that require banking.
     */
    String bankLocationId;

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
    // Requirements
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

    /**
     * Quest requirements (quest IDs that must be completed).
     */
    @Builder.Default
    List<Integer> questRequirements = List.of();

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
    // Utility Methods
    // ========================================================================

    /**
     * Check if this method is valid for a given level.
     *
     * @param level the player's current level
     * @return true if level is within [minLevel, maxLevel]
     */
    public boolean isValidForLevel(int level) {
        if (level < minLevel) {
            return false;
        }
        return maxLevel < 0 || level <= maxLevel;
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
     * Calculate theoretical XP per hour at a specific level.
     * For level-based methods, uses level * xpMultiplier * actionsPerHour.
     *
     * @param level the player's current level in the trained skill
     * @return estimated XP per hour
     */
    public double getXpPerHour(int level) {
        return getXpPerAction(level) * actionsPerHour;
    }

    /**
     * Calculate theoretical XP per hour using static xpPerAction.
     * For level-based methods, this returns 0 - use getXpPerHour(int level) instead.
     *
     * @return estimated XP per hour (0 if level-based)
     */
    public double getXpPerHour() {
        if (hasLevelBasedXp()) {
            return 0; // Must use getXpPerHour(level) for level-based methods
        }
        return xpPerAction * actionsPerHour;
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
        return requiresInventorySpace && !dropWhenFull && bankLocationId != null;
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
     * Get a summary string for logging.
     *
     * @return human-readable summary
     */
    public String getSummary() {
        if (hasLevelBasedXp()) {
            return String.format("%s [%s] (lvl %d-%s, %.0fx level xp/action)",
                    name,
                    skill.getName(),
                    minLevel,
                    maxLevel < 0 ? "99" : String.valueOf(maxLevel),
                    xpMultiplier);
        }
        return String.format("%s [%s] (lvl %d-%s, %.0f xp/hr)",
                name,
                skill.getName(),
                minLevel,
                maxLevel < 0 ? "99" : String.valueOf(maxLevel),
                getXpPerHour());
    }

    /**
     * Get a summary string with level-specific XP rates.
     *
     * @param level the player's current level
     * @return human-readable summary with actual XP rates
     */
    public String getSummary(int level) {
        return String.format("%s [%s] (lvl %d-%s, %.0f xp/hr @ lvl %d)",
                name,
                skill.getName(),
                minLevel,
                maxLevel < 0 ? "99" : String.valueOf(maxLevel),
                getXpPerHour(level),
                level);
    }
}

