package com.rocinante.quest.bridge;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Status classes for quest requirements with actual player state.
 */
public final class RequirementStatus {
    private RequirementStatus() {} // Utility class

    /**
     * Skill requirement with current player level.
     */
    @Value
    @Builder
    public static class SkillStatus {
        String skillName;
        int required;
        int current;
        boolean met;
        boolean boostable;
    }

    /**
     * Quest requirement with completion status.
     */
    @Value
    @Builder
    public static class QuestStatus {
        String questId;
        String questName;
        boolean met;
    }

    /**
     * Item requirement with counts across all containers.
     */
    @Value
    @Builder
    public static class ItemStatus {
        int itemId;
        String name;
        int quantityRequired;
        int inInventory;
        int equipped;
        int inBank;
        boolean met;
        boolean obtainableDuringQuest;
        boolean recommended;
        List<Integer> alternateIds;
    }

    /**
     * Complete requirements status for a quest.
     */
    @Value
    @Builder
    public static class QuestRequirementsStatus {
        String questId;
        String questName;
        String difficulty;
        boolean members;
        int questPoints;
        String state;
        boolean canStart;
        List<SkillStatus> skillRequirements;
        List<QuestStatus> questRequirements;
        List<ItemStatus> itemRequirements;
    }
}

