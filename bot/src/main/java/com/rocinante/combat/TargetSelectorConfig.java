package com.rocinante.combat;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Configuration for target selection behavior.
 *
 * Per REQUIREMENTS.md Section 10.5, configures:
 * <ul>
 *   <li>Selection priority order</li>
 *   <li>Target filters (NPC IDs, names, combat level)</li>
 *   <li>Avoidance rules (skip in-combat, unreachable, high level)</li>
 *   <li>Search parameters (radius)</li>
 * </ul>
 */
@Value
@Builder
public class TargetSelectorConfig {

    /**
     * Default configuration: prioritize NPCs targeting player, then nearest.
     */
    public static final TargetSelectorConfig DEFAULT = TargetSelectorConfig.builder()
            .priority(SelectionPriority.TARGETING_PLAYER)
            .priority(SelectionPriority.NEAREST)
            .searchRadius(15)
            .skipInCombatWithOthers(true)
            .skipUnreachable(true)
            .maxCombatLevel(-1) // No limit
            .build();

    /**
     * Configuration for farming specific NPCs by ID.
     */
    public static TargetSelectorConfig forNpcIds(int... npcIds) {
        TargetSelectorConfig.TargetSelectorConfigBuilder builder = TargetSelectorConfig.builder()
                .priority(SelectionPriority.TARGETING_PLAYER)
                .priority(SelectionPriority.SPECIFIC_ID)
                .priority(SelectionPriority.NEAREST)
                .searchRadius(15)
                .skipInCombatWithOthers(true)
                .skipUnreachable(true)
                .maxCombatLevel(-1);

        for (int id : npcIds) {
            builder.targetNpcId(id);
        }

        return builder.build();
    }

    /**
     * Configuration for farming specific NPCs by ID collection.
     * NPCs are selected in priority order from the collection.
     *
     * @param npcIds collection of NPC IDs to target
     * @return configured selector
     */
    public static TargetSelectorConfig forNpcIds(Collection<Integer> npcIds) {
        TargetSelectorConfig.TargetSelectorConfigBuilder builder = TargetSelectorConfig.builder()
                .priority(SelectionPriority.TARGETING_PLAYER)
                .priority(SelectionPriority.SPECIFIC_ID)
                .priority(SelectionPriority.NEAREST)
                .searchRadius(15)
                .skipInCombatWithOthers(true)
                .skipUnreachable(true)
                .maxCombatLevel(-1);

        for (int id : npcIds) {
            builder.targetNpcId(id);
        }

        return builder.build();
    }

    /**
     * Configuration for farming specific NPCs by name.
     */
    public static TargetSelectorConfig forNpcNames(String... npcNames) {
        TargetSelectorConfig.TargetSelectorConfigBuilder builder = TargetSelectorConfig.builder()
                .priority(SelectionPriority.TARGETING_PLAYER)
                .priority(SelectionPriority.SPECIFIC_NAME)
                .priority(SelectionPriority.NEAREST)
                .searchRadius(15)
                .skipInCombatWithOthers(true)
                .skipUnreachable(true)
                .maxCombatLevel(-1);

        for (String name : npcNames) {
            builder.targetNpcName(name);
        }

        return builder.build();
    }

    // ========================================================================
    // Selection Priority (Section 10.5.1)
    // ========================================================================

    /**
     * Ordered list of selection priorities.
     * The selector iterates through these in order until a valid target is found.
     * Default: [TARGETING_PLAYER, NEAREST]
     */
    @Singular("priority")
    List<SelectionPriority> priorities;

    // ========================================================================
    // Target Filters
    // ========================================================================

    /**
     * Set of NPC definition IDs to target.
     * Only used when SPECIFIC_ID is in priorities.
     * Empty set means all NPCs are valid (no ID filter).
     */
    @Singular("targetNpcId")
    Set<Integer> targetNpcIds;

    /**
     * Set of NPC names to target (case-insensitive matching).
     * Only used when SPECIFIC_NAME is in priorities.
     * Empty set means all NPCs are valid (no name filter).
     */
    @Singular("targetNpcName")
    Set<String> targetNpcNames;

    /**
     * Maximum NPC combat level to attack.
     * -1 means no limit.
     * Per Section 10.5.2: Optional skip NPCs above certain combat level.
     */
    @Builder.Default
    int maxCombatLevel = -1;

    /**
     * Search radius for finding NPCs (in tiles).
     * Default: 15 tiles.
     */
    @Builder.Default
    int searchRadius = 15;

    /**
     * Weapon attack range for reachability checks.
     * Used to determine if targets are attackable:
     * <ul>
     *   <li>1 = Melee (most weapons)</li>
     *   <li>2 = Halberd melee</li>
     *   <li>7 = Ranged (bows, crossbows)</li>
     *   <li>10 = Magic (spells)</li>
     * </ul>
     * Default: 1 (melee).
     */
    @Builder.Default
    int weaponRange = 1;

    // ========================================================================
    // Avoidance Rules (Section 10.5.2)
    // ========================================================================

    /**
     * Whether to skip NPCs that are in combat with other players.
     * Per Section 10.5.2: Skip NPCs in combat with other players.
     * Default: true
     */
    @Builder.Default
    boolean skipInCombatWithOthers = true;

    /**
     * Whether to skip NPCs that are unreachable (no valid path).
     * Per Section 10.5.2: Skip NPCs that are unreachable.
     * Default: true
     */
    @Builder.Default
    boolean skipUnreachable = true;

    /**
     * Whether to skip NPCs that are already dead.
     * Default: true (always skip dead NPCs).
     */
    @Builder.Default
    boolean skipDead = true;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if the configuration has a max combat level filter.
     *
     * @return true if combat level filter is active
     */
    public boolean hasMaxCombatLevel() {
        return maxCombatLevel > 0;
    }

    /**
     * Check if the configuration filters by specific NPC IDs.
     *
     * @return true if ID filtering is configured
     */
    public boolean hasTargetNpcIds() {
        return !targetNpcIds.isEmpty();
    }

    /**
     * Check if the configuration filters by specific NPC names.
     *
     * @return true if name filtering is configured
     */
    public boolean hasTargetNpcNames() {
        return !targetNpcNames.isEmpty();
    }

    /**
     * Check if a specific NPC ID is in the target list.
     *
     * @param npcId the NPC definition ID
     * @return true if the ID is targeted or no ID filter is set
     */
    public boolean isTargetNpcId(int npcId) {
        return targetNpcIds.isEmpty() || targetNpcIds.contains(npcId);
    }

    /**
     * Check if a specific NPC name is in the target list (case-insensitive).
     *
     * @param npcName the NPC name
     * @return true if the name is targeted or no name filter is set
     */
    public boolean isTargetNpcName(String npcName) {
        if (targetNpcNames.isEmpty()) {
            return true;
        }
        if (npcName == null) {
            return false;
        }
        return targetNpcNames.stream()
                .anyMatch(name -> name.equalsIgnoreCase(npcName));
    }

    /**
     * Check if an NPC's combat level is acceptable.
     *
     * @param combatLevel the NPC's combat level
     * @return true if within configured max or no limit set
     */
    public boolean isCombatLevelAcceptable(int combatLevel) {
        return maxCombatLevel < 0 || combatLevel <= maxCombatLevel;
    }

    /**
     * Get a summary string for logging.
     *
     * @return summary of this configuration
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder("TargetSelectorConfig[");
        sb.append("priorities=").append(priorities);
        if (hasTargetNpcIds()) {
            sb.append(", ids=").append(targetNpcIds);
        }
        if (hasTargetNpcNames()) {
            sb.append(", names=").append(targetNpcNames);
        }
        if (hasMaxCombatLevel()) {
            sb.append(", maxLevel=").append(maxCombatLevel);
        }
        sb.append(", radius=").append(searchRadius);
        sb.append("]");
        return sb.toString();
    }
}

