package com.rocinante.slayer;

import com.rocinante.combat.TargetSelectorConfig;
import com.rocinante.tasks.impl.CombatTaskConfig;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;

/**
 * Configuration for SlayerTask, extending CombatTaskConfig with slayer-specific options.
 *
 * Per REQUIREMENTS.md Section 11, adds:
 * <ul>
 *   <li>Slayer creature and location information</li>
 *   <li>Task tracking from SlayerState</li>
 *   <li>Finish-off item handling</li>
 *   <li>Superior creature prioritization</li>
 *   <li>HCIM safety options</li>
 *   <li>Auto-banking for resupply</li>
 * </ul>
 *
 * Uses composition with CombatTaskConfig rather than inheritance to keep
 * configurations cleanly separated and independently configurable.
 */
@Value
@Builder
public class SlayerTaskConfig {

    // ========================================================================
    // Embedded Combat Configuration
    // ========================================================================

    /**
     * Combat task configuration for underlying combat loop.
     * Contains target selection, looting, resource management, etc.
     */
    @Builder.Default
    CombatTaskConfig combatConfig = CombatTaskConfig.DEFAULT;

    // ========================================================================
    // Slayer Task Information
    // ========================================================================

    /**
     * Slayer creature data from SlayerDataLoader.
     */
    @Nullable
    SlayerCreature creature;

    /**
     * Selected slayer location.
     */
    @Nullable
    SlayerLocation location;

    /**
     * Task name from SlayerState (matches creature name).
     */
    @Nullable
    String taskName;

    /**
     * Konar location restriction (if applicable).
     */
    @Nullable
    String konarRegion;

    // ========================================================================
    // Kill Tracking
    // ========================================================================

    /**
     * Track kills from slayer state remaining count.
     * If true, task completes when SlayerState.remainingKills reaches 0.
     * If false, uses combatConfig.killCount for completion.
     */
    @Builder.Default
    boolean trackFromSlayerState = true;

    /**
     * Initial kill count when task started.
     * Used for progress calculation when trackFromSlayerState is true.
     */
    @Builder.Default
    int initialKillsRemaining = 0;

    // ========================================================================
    // Finish-off Item Handling
    // ========================================================================

    /**
     * Whether to use finish-off items (rock hammer, ice coolers, etc.).
     * If false, relies on auto-kill unlock for creatures that need finishing.
     */
    @Builder.Default
    boolean useFinishOffItems = true;

    /**
     * Item ID for finish-off item (if required by creature).
     * Populated from SlayerCreature data.
     */
    @Builder.Default
    int finishOffItemId = -1;

    /**
     * HP threshold for using finish-off item.
     * From SlayerCreature.finishOffThreshold.
     */
    @Builder.Default
    int finishOffThreshold = 0;

    // ========================================================================
    // Superior Handling
    // ========================================================================

    /**
     * Whether to prioritize attacking superior creatures when they spawn.
     * Requires "Bigger and Badder" unlock (SlayerUnlock.SUPERIOR_CREATURES).
     */
    @Builder.Default
    boolean prioritizeSuperiors = true;

    /**
     * NPC IDs for superior variants (from SlayerCreature).
     */
    @Builder.Default
    Set<Integer> superiorNpcIds = Collections.emptySet();

    // ========================================================================
    // Equipment Validation
    // ========================================================================

    /**
     * Whether to validate equipment before attacking.
     * If true, checks for required protective equipment.
     */
    @Builder.Default
    boolean validateEquipment = true;

    /**
     * Required equipment item IDs (from SlayerCreature).
     */
    @Builder.Default
    Set<Integer> requiredEquipmentIds = Collections.emptySet();

    /**
     * Valid weapon IDs for creatures with weapon restrictions.
     */
    @Builder.Default
    Set<Integer> validWeaponIds = Collections.emptySet();

    // ========================================================================
    // Banking / Resupply
    // ========================================================================

    /**
     * Bank node ID for resupply trips.
     * From SlayerLocation.nearestBank.
     */
    @Nullable
    String bankNodeId;

    /**
     * Position to return to after banking.
     * Typically the location center.
     */
    @Nullable
    WorldPoint returnPosition;

    // ========================================================================
    // HCIM Safety
    // ========================================================================

    /**
     * Whether this is a HCIM-safe task location.
     */
    @Builder.Default
    boolean hcimSafe = true;

    /**
     * HCIM safety rating (1-10).
     */
    @Builder.Default
    int hcimSafetyRating = 5;

    /**
     * Whether location is multi-combat.
     */
    @Builder.Default
    boolean multiCombat = false;

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create config from resolved location data.
     *
     * @param creature  slayer creature data
     * @param location  selected location
     * @param slayerState current slayer state for kill tracking
     * @return configured SlayerTaskConfig
     */
    public static SlayerTaskConfig fromResolution(
            SlayerCreature creature,
            SlayerLocation location,
            SlayerState slayerState) {

        // Build target selector config from creature NPC IDs
        TargetSelectorConfig.TargetSelectorConfigBuilder targetBuilder = TargetSelectorConfig.builder()
                .priority(com.rocinante.combat.SelectionPriority.TARGETING_PLAYER)
                .priority(com.rocinante.combat.SelectionPriority.SPECIFIC_ID)
                .priority(com.rocinante.combat.SelectionPriority.NEAREST)
                .searchRadius(15)
                .skipInCombatWithOthers(true)
                .skipUnreachable(true)
                .skipDead(true)
                .maxCombatLevel(-1);
        
        // Add all NPC IDs from the creature
        for (int npcId : creature.getNpcIds()) {
            targetBuilder.targetNpcId(npcId);
        }
        
        TargetSelectorConfig targetConfig = targetBuilder.build();

        // Build combat config with slayer-appropriate settings
        CombatTaskConfig combatConfig = CombatTaskConfig.builder()
                .targetConfig(targetConfig)
                .killCount(-1) // Track from slayer state instead
                .maxDuration(Duration.ofHours(4)) // Slayer tasks can be long
                .lootEnabled(true)
                .lootMinValue(1000)
                .stopWhenOutOfFood(true)
                .enableResupply(true)
                .returnToSameSpot(true)
                .build();

        return SlayerTaskConfig.builder()
                .combatConfig(combatConfig)
                .creature(creature)
                .location(location)
                .taskName(creature.getName())
                .konarRegion(location.getKonarRegion())
                .trackFromSlayerState(true)
                .initialKillsRemaining(slayerState.getRemainingKills())
                .useFinishOffItems(creature.requiresFinishOffItem())
                .finishOffItemId(creature.getFinishOffItemIds().isEmpty() ? -1 :
                        creature.getFinishOffItemIds().iterator().next())
                .finishOffThreshold(creature.getFinishOffThreshold())
                .prioritizeSuperiors(creature.isHasSuperior())
                .superiorNpcIds(creature.getSuperiorNpcIds())
                .validateEquipment(creature.requiresEquipment())
                .requiredEquipmentIds(creature.getRequiredEquipmentIds())
                .validWeaponIds(creature.getValidWeaponIds())
                .bankNodeId(location.getNearestBank())
                .returnPosition(location.getCenter())
                .hcimSafe(location.getHcimSafetyRating() >= 6)
                .hcimSafetyRating(location.getHcimSafetyRating())
                .multiCombat(location.isMultiCombat())
                .build();
    }

    /**
     * Create a basic config for a task name (without detailed creature/location data).
     *
     * @param taskName the task name
     * @param killCount expected kill count
     * @return basic config
     */
    public static SlayerTaskConfig forTaskName(String taskName, int killCount) {
        return SlayerTaskConfig.builder()
                .taskName(taskName)
                .initialKillsRemaining(killCount)
                .trackFromSlayerState(true)
                .build();
    }

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if finish-off items are needed.
     *
     * @return true if creature requires finish-off item
     */
    public boolean requiresFinishOffItem() {
        return finishOffItemId > 0 && finishOffThreshold > 0;
    }

    /**
     * Check if weapon validation is needed.
     *
     * @return true if creature has weapon restrictions
     */
    public boolean hasWeaponRestrictions() {
        return validWeaponIds != null && !validWeaponIds.isEmpty();
    }

    /**
     * Check if creature can spawn superiors.
     *
     * @return true if superior tracking is enabled
     */
    public boolean canSpawnSuperiors() {
        return prioritizeSuperiors && superiorNpcIds != null && !superiorNpcIds.isEmpty();
    }

    /**
     * Check if an NPC ID is a superior variant.
     *
     * @param npcId the NPC ID
     * @return true if superior
     */
    public boolean isSuperiorNpc(int npcId) {
        return superiorNpcIds != null && superiorNpcIds.contains(npcId);
    }

    /**
     * Get a summary string for logging.
     *
     * @return summary
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder("SlayerTaskConfig[");
        sb.append("task=").append(taskName);
        if (location != null) {
            sb.append(", loc=").append(location.getName());
        }
        if (konarRegion != null) {
            sb.append(" (Konar: ").append(konarRegion).append(")");
        }
        sb.append(", kills=").append(initialKillsRemaining);
        if (requiresFinishOffItem()) {
            sb.append(", finishOff=").append(finishOffItemId);
        }
        if (canSpawnSuperiors()) {
            sb.append(", superiors=").append(superiorNpcIds.size());
        }
        if (multiCombat) {
            sb.append(" [MULTI]");
        }
        sb.append(", safety=").append(hcimSafetyRating);
        sb.append("]");
        return sb.toString();
    }
}

