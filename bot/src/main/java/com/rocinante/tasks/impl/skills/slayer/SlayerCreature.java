package com.rocinante.tasks.impl.skills.slayer;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Data class representing a slayer creature and its requirements.
 *
 * Per REQUIREMENTS.md Section 11.2 and 11.3, contains:
 * <ul>
 *   <li>Creature name and NPC IDs</li>
 *   <li>Slayer level requirement</li>
 *   <li>Available locations</li>
 *   <li>Required equipment (earmuffs, nose peg, etc.)</li>
 *   <li>Required finish-off items (rock hammer, ice coolers, etc.)</li>
 *   <li>Valid weapon types (leaf-bladed for turoth/kurask)</li>
 *   <li>Weakness information for gear optimization</li>
 *   <li>Superior variant information</li>
 * </ul>
 *
 * Loaded from slayer_locations.json data file.
 */
@Value
@Builder
public class SlayerCreature {

    /**
     * Canonical name of the creature (matches task name from SlayerPluginService).
     */
    String name;

    /**
     * NPC IDs for all variants of this creature.
     * Includes regular, superior, and alternative forms.
     */
    @Singular("npcId")
    Set<Integer> npcIds;

    /**
     * Slayer level required to damage this creature.
     */
    int slayerLevelRequired;

    /**
     * All available locations where this creature can be found.
     */
    @Singular("location")
    List<SlayerLocation> locations;

    // ========================================================================
    // Equipment Requirements
    // ========================================================================

    /**
     * Item IDs for equipment that MUST be worn to fight this creature.
     * Examples: Earmuffs (4166) for banshees, Nose peg (4168) for aberrant spectres.
     * Empty set means no special equipment needed.
     */
    @Builder.Default
    Set<Integer> requiredEquipmentIds = Collections.emptySet();

    /**
     * Item IDs for items that MUST be in inventory to finish kills.
     * Examples: Rock hammer (4162) for gargoyles, Ice coolers (6696) for desert lizards.
     * These items are used to deal the final blow below a health threshold.
     */
    @Builder.Default
    Set<Integer> finishOffItemIds = Collections.emptySet();

    /**
     * HP threshold below which finish-off item must be used.
     * 0 means no threshold (auto-kill unlock handles it).
     * Examples: Gargoyles at 9 HP, Rockslugs at 4 HP.
     */
    @Builder.Default
    int finishOffThreshold = 0;

    /**
     * Item IDs for valid weapons that can damage this creature.
     * Empty set means any weapon works.
     * Examples: Leaf-bladed weapons for Turoths/Kurask.
     */
    @Builder.Default
    Set<Integer> validWeaponIds = Collections.emptySet();

    /**
     * Whether broad bolts/arrows can damage this creature.
     * True for Turoths and Kurask.
     */
    boolean broadAmmoValid;

    /**
     * Whether Magic Dart spell can damage this creature.
     * True for Turoths and Kurask.
     */
    boolean magicDartValid;

    // ========================================================================
    // Combat Information
    // ========================================================================

    /**
     * Primary weakness of this creature.
     * Used for gear optimization. Null if no specific weakness.
     */
    @Nullable
    String weakness;

    /**
     * Attack styles this creature uses.
     * Used for protection prayer selection.
     */
    @Builder.Default
    Set<String> attackStyles = Collections.emptySet();

    /**
     * Whether this creature can poison the player.
     */
    boolean canPoison;

    /**
     * Whether this creature can venom the player.
     */
    boolean canVenom;

    /**
     * Typical combat level range (min).
     */
    @Builder.Default
    int combatLevelMin = 1;

    /**
     * Typical combat level range (max).
     */
    @Builder.Default
    int combatLevelMax = 500;

    // ========================================================================
    // Superior Information
    // ========================================================================

    /**
     * Whether this creature has a superior variant.
     * Requires "Bigger and Badder" unlock (SlayerUnlock.SUPERIOR_CREATURES).
     */
    boolean hasSuperior;

    /**
     * NPC IDs for the superior variant, if any.
     */
    @Builder.Default
    Set<Integer> superiorNpcIds = Collections.emptySet();

    /**
     * Name of the superior variant (for display/logging).
     */
    @Nullable
    String superiorName;

    // ========================================================================
    // Task Information
    // ========================================================================

    /**
     * Base slayer XP per kill.
     */
    @Builder.Default
    double baseSlayerXp = 0;

    /**
     * Average task size range (min).
     */
    @Builder.Default
    int taskSizeMin = 0;

    /**
     * Average task size range (max).
     */
    @Builder.Default
    int taskSizeMax = 0;

    /**
     * Task weight at high-level masters.
     * Higher = more common assignment.
     */
    @Builder.Default
    int taskWeight = 0;

    /**
     * Brief notes or tips for this creature.
     */
    @Nullable
    String notes;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if this creature requires special equipment.
     *
     * @return true if equipment is required
     */
    public boolean requiresEquipment() {
        return !requiredEquipmentIds.isEmpty();
    }

    /**
     * Check if this creature requires a finish-off item.
     *
     * @return true if finish-off item is needed
     */
    public boolean requiresFinishOffItem() {
        return !finishOffItemIds.isEmpty();
    }

    /**
     * Check if this creature requires specific weapons.
     *
     * @return true if weapon restrictions apply
     */
    public boolean hasWeaponRestrictions() {
        return !validWeaponIds.isEmpty();
    }

    /**
     * Check if a given NPC ID matches this creature.
     *
     * @param npcId the NPC ID to check
     * @return true if ID matches this creature or its superior
     */
    public boolean matchesNpcId(int npcId) {
        return npcIds.contains(npcId) || superiorNpcIds.contains(npcId);
    }

    /**
     * Check if a given NPC ID is a superior variant.
     *
     * @param npcId the NPC ID to check
     * @return true if ID is a superior
     */
    public boolean isSuperiorNpcId(int npcId) {
        return superiorNpcIds.contains(npcId);
    }

    /**
     * Check if the player meets slayer level requirement.
     *
     * @param playerSlayerLevel player's slayer level
     * @return true if requirement is met
     */
    public boolean meetsLevelRequirement(int playerSlayerLevel) {
        return playerSlayerLevel >= slayerLevelRequired;
    }

    /**
     * Get the best location for HCIM with minimum safety.
     *
     * @param minimumSafety minimum HCIM safety rating
     * @return safest location meeting threshold, or null
     */
    @Nullable
    public SlayerLocation getSafestLocation(int minimumSafety) {
        return locations.stream()
                .filter(loc -> loc.isSafeForHcim(minimumSafety))
                .max((a, b) -> Integer.compare(a.getHcimSafetyRating(), b.getHcimSafetyRating()))
                .orElse(null);
    }

    /**
     * Get the first location matching a Konar region.
     *
     * @param konarRegion the region restriction
     * @return matching location, or null
     */
    @Nullable
    public SlayerLocation getLocationForKonar(String konarRegion) {
        if (konarRegion == null) {
            return locations.isEmpty() ? null : locations.get(0);
        }
        return locations.stream()
                .filter(loc -> loc.matchesKonarRegion(konarRegion))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get the best location overall (highest density, non-multi preferred).
     *
     * @return best location or null if none
     */
    @Nullable
    public SlayerLocation getBestLocation() {
        if (locations.isEmpty()) {
            return null;
        }
        // Prefer non-multi combat for safety, then highest density
        return locations.stream()
                .sorted((a, b) -> {
                    // Non-multi first
                    if (a.isMultiCombat() != b.isMultiCombat()) {
                        return a.isMultiCombat() ? 1 : -1;
                    }
                    // Then highest density
                    return Integer.compare(b.getMonsterDensity(), a.getMonsterDensity());
                })
                .findFirst()
                .orElse(locations.get(0));
    }

    /**
     * Check if this creature uses a specific attack style.
     *
     * @param style the attack style (melee, ranged, magic)
     * @return true if creature uses this style
     */
    public boolean usesAttackStyle(String style) {
        return attackStyles.stream()
                .anyMatch(s -> s.equalsIgnoreCase(style));
    }

    /**
     * Get the recommended protection prayer style.
     *
     * @return primary attack style or "melee" as default
     */
    public String getPrimaryAttackStyle() {
        if (attackStyles.isEmpty()) {
            return "melee";
        }
        // Prioritize magic > ranged > melee for protection
        if (attackStyles.contains("magic")) return "magic";
        if (attackStyles.contains("ranged")) return "ranged";
        return "melee";
    }

    /**
     * Get estimated task duration in minutes.
     *
     * @param taskSize number of kills assigned
     * @param location the location being used
     * @return estimated minutes to complete
     */
    public int getEstimatedTaskDuration(int taskSize, SlayerLocation location) {
        if (location == null) {
            return taskSize; // 1 minute per kill worst case
        }
        int killsPerHour = location.getEstimatedKillsPerHour();
        if (killsPerHour <= 0) {
            return taskSize;
        }
        return (taskSize * 60) / killsPerHour;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name);
        sb.append(" (Slayer ").append(slayerLevelRequired).append(")");
        if (!locations.isEmpty()) {
            sb.append(" [").append(locations.size()).append(" locations]");
        }
        if (requiresEquipment()) {
            sb.append(" [EQUIP]");
        }
        if (requiresFinishOffItem()) {
            sb.append(" [FINISH]");
        }
        if (hasSuperior) {
            sb.append(" [SUP]");
        }
        return sb.toString();
    }
}

