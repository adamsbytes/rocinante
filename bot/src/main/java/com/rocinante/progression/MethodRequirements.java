package com.rocinante.progression;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Requirements that must be met to use a training method or location.
 *
 * <p>Requirements can be specified at the method level (applies to all locations)
 * or at individual location level (applies only to that location).
 *
 * <p>Examples:
 * <ul>
 *   <li>Prifddinas locations require "Song of the Elves" quest</li>
 *   <li>Most efficient locations are members-only</li>
 *   <li>Some methods require diary completions for shortcuts</li>
 * </ul>
 */
@Value
@Builder
public class MethodRequirements {

    /**
     * Quest names that must be completed.
     * Uses quest display names (e.g., "Song of the Elves", "Dragon Slayer").
     */
    @Builder.Default
    List<String> quests = List.of();

    /**
     * Achievement diary requirements.
     * Format: "{region} {tier}" (e.g., "Ardougne Elite", "Lumbridge Hard").
     */
    @Builder.Default
    List<String> diaries = List.of();

    /**
     * Skill level requirements beyond the method's minLevel.
     * Key: skill name (e.g., "Agility"), Value: required level.
     */
    @Builder.Default
    Map<String, Integer> skills = Map.of();

    /**
     * Whether this requires a members (P2P) account.
     * Default false means available to F2P.
     */
    @Builder.Default
    boolean members = false;

    /**
     * Area unlock requirements (e.g., "Prifddinas", "Kourend").
     * These are areas that require quest completion or other unlocks to access.
     */
    @Builder.Default
    List<String> areas = List.of();

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if there are any requirements defined.
     *
     * @return true if at least one requirement is set
     */
    public boolean hasRequirements() {
        return !quests.isEmpty() 
                || !diaries.isEmpty() 
                || !skills.isEmpty() 
                || members 
                || !areas.isEmpty();
    }

    /**
     * Check if this is F2P compatible (no membership requirement).
     *
     * @return true if available to free players
     */
    public boolean isF2PCompatible() {
        return !members;
    }

    /**
     * Get a summary string of requirements.
     *
     * @return human-readable requirement summary
     */
    public String getSummary() {
        if (!hasRequirements()) {
            return "None";
        }

        StringBuilder sb = new StringBuilder();
        
        if (members) {
            sb.append("Members");
        }
        
        if (!quests.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("Quests: ").append(String.join(", ", quests));
        }
        
        if (!diaries.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("Diaries: ").append(String.join(", ", diaries));
        }
        
        if (!skills.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("Skills: ");
            skills.forEach((skill, level) -> 
                    sb.append(skill).append(" ").append(level).append(", "));
            sb.setLength(sb.length() - 2); // Remove trailing ", "
        }
        
        if (!areas.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("Areas: ").append(String.join(", ", areas));
        }

        return sb.toString();
    }

    /**
     * Create an empty requirements object (no restrictions).
     *
     * @return MethodRequirements with no requirements set
     */
    public static MethodRequirements none() {
        return MethodRequirements.builder().build();
    }

    /**
     * Create a members-only requirement.
     *
     * @return MethodRequirements requiring membership
     */
    public static MethodRequirements membersOnly() {
        return MethodRequirements.builder()
                .members(true)
                .build();
    }
}

