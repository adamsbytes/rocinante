package com.rocinante.navigation;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Represents a requirement for traversing a navigation edge.
 * Per REQUIREMENTS.md Section 7.2.1 - Requirement Types.
 *
 * <p>Requirements can be:
 * <ul>
 *   <li>Skill levels (magic, agility, combat, etc.)</li>
 *   <li>Quest completion</li>
 *   <li>Items (consumed or not)</li>
 *   <li>Runes for spells</li>
 *   <li>Ironman restrictions</li>
 * </ul>
 */
@Value
@Builder
public class EdgeRequirement {

    /**
     * The type of requirement.
     */
    EdgeRequirementType type;

    /**
     * Numeric value (level, quantity, etc.).
     * Used by: MAGIC_LEVEL, AGILITY_LEVEL, SKILL, COMBAT_LEVEL, ITEM
     */
    int value;

    /**
     * String identifier (skill name, quest ID, item name, ironman type).
     * Used by: SKILL (skill name), QUEST (quest_id), IRONMAN_RESTRICTION (type)
     */
    String identifier;

    /**
     * Item ID for ITEM requirements.
     */
    int itemId;

    /**
     * Whether the item is consumed when traversing.
     * Used by: ITEM
     */
    boolean consumed;

    /**
     * Rune items for RUNES requirement.
     * List of {id, quantity} pairs.
     */
    List<RuneCost> runeCosts;

    /**
     * Quest state required (for partial quest completion).
     * Used by: QUEST
     */
    String questState;

    /**
     * Represents a rune cost entry.
     */
    @Value
    @Builder
    public static class RuneCost {
        int itemId;
        int quantity;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a magic level requirement.
     */
    public static EdgeRequirement magicLevel(int level) {
        return EdgeRequirement.builder()
                .type(EdgeRequirementType.MAGIC_LEVEL)
                .value(level)
                .build();
    }

    /**
     * Create an agility level requirement.
     */
    public static EdgeRequirement agilityLevel(int level) {
        return EdgeRequirement.builder()
                .type(EdgeRequirementType.AGILITY_LEVEL)
                .value(level)
                .build();
    }

    /**
     * Create a quest completion requirement.
     */
    public static EdgeRequirement quest(String questId) {
        return EdgeRequirement.builder()
                .type(EdgeRequirementType.QUEST)
                .identifier(questId)
                .build();
    }

    /**
     * Create a quest state requirement.
     */
    public static EdgeRequirement quest(String questId, String state) {
        return EdgeRequirement.builder()
                .type(EdgeRequirementType.QUEST)
                .identifier(questId)
                .questState(state)
                .build();
    }

    /**
     * Create an item requirement.
     */
    public static EdgeRequirement item(int itemId, int quantity, boolean consumed) {
        return EdgeRequirement.builder()
                .type(EdgeRequirementType.ITEM)
                .itemId(itemId)
                .value(quantity)
                .consumed(consumed)
                .build();
    }

    /**
     * Create a rune cost requirement.
     */
    public static EdgeRequirement runes(List<RuneCost> costs) {
        return EdgeRequirement.builder()
                .type(EdgeRequirementType.RUNES)
                .runeCosts(costs)
                .build();
    }

    /**
     * Create a skill requirement.
     */
    public static EdgeRequirement skill(String skillName, int level) {
        return EdgeRequirement.builder()
                .type(EdgeRequirementType.SKILL)
                .identifier(skillName)
                .value(level)
                .build();
    }

    /**
     * Create a combat level requirement.
     */
    public static EdgeRequirement combatLevel(int level) {
        return EdgeRequirement.builder()
                .type(EdgeRequirementType.COMBAT_LEVEL)
                .value(level)
                .build();
    }

    /**
     * Create an ironman restriction.
     */
    public static EdgeRequirement ironmanRestriction(String restrictionType) {
        return EdgeRequirement.builder()
                .type(EdgeRequirementType.IRONMAN_RESTRICTION)
                .identifier(restrictionType)
                .build();
    }

    /**
     * Create a Kourend favour requirement.
     *
     * @param houseName the Kourend house name (e.g., "Hosidius")
     * @param percent the required favour percentage (0-100)
     */
    public static EdgeRequirement favour(String houseName, int percent) {
        return EdgeRequirement.builder()
                .type(EdgeRequirementType.FAVOUR)
                .identifier(houseName)
                .value(percent)
                .build();
    }
}

