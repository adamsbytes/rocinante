package com.rocinante.slayer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.NpcID;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Enum of all slayer masters with their locations, requirements, and NPC IDs.
 *
 * Per REQUIREMENTS.md Section 11.1.2, tracks:
 * <ul>
 *   <li>Master locations (WorldPoint)</li>
 *   <li>Combat level requirements</li>
 *   <li>Slayer level requirements</li>
 *   <li>NPC IDs for interaction</li>
 *   <li>Master IDs from varbit SLAYER_MASTER (VarbitID.SLAYER_MASTER)</li>
 * </ul>
 *
 * NPC IDs sourced from RuneLite's {@link NpcID} constants.
 * Varbit master IDs sourced from RuneLite SlayerPlugin.java.
 */
@Getter
@RequiredArgsConstructor
public enum SlayerMaster {

    // ========================================================================
    // Standard Slayer Masters
    // ========================================================================

    /**
     * Turael - Beginner master in Burthorpe.
     * Lowest level tasks, can be used to reset task streaks.
     */
    TURAEL(
            "Turael",
            1,  // Master ID from varbit
            new WorldPoint(2930, 3536, 0),
            3,   // Combat level requirement
            1,   // Slayer level requirement
            NpcID.TURAEL,
            NpcID.SPRIA, // Alternate - Spria in Draynor (same functionality)
            false // Not wilderness
    ),

    /**
     * Spria - Backup beginner master (same as Turael).
     * Located in Draynor Village during A Porcine of Interest.
     */
    SPRIA(
            "Spria",
            1,  // Same master ID as Turael
            new WorldPoint(3092, 3267, 0),
            3,
            1,
            NpcID.SPRIA,
            NpcID.SPRIA,
            false
    ),

    /**
     * Mazchna - Low-level master in Canifis.
     * Requires Priest in Peril for Morytania access.
     */
    MAZCHNA(
            "Mazchna",
            2,
            new WorldPoint(3510, 3507, 0),
            20,
            1,
            NpcID.MAZCHNA,
            NpcID.MAZCHNA,
            false
    ),

    /**
     * Vannaka - Mid-level master in Edgeville Dungeon.
     */
    VANNAKA(
            "Vannaka",
            3,
            new WorldPoint(3145, 9913, 0),
            40,
            1,
            NpcID.VANNAKA,
            NpcID.VANNAKA,
            false
    ),

    /**
     * Chaeldar - Mid-high level master in Zanaris.
     * Requires Lost City quest completion.
     */
    CHAELDAR(
            "Chaeldar",
            4,
            new WorldPoint(2445, 4431, 0),
            70,
            1,
            NpcID.CHAELDAR,
            NpcID.CHAELDAR,
            false
    ),

    /**
     * Konar quo Maten - High level master on Mount Karuulm.
     * Assigns location-specific tasks with bonus loot.
     */
    KONAR(
            "Konar quo Maten",
            6,
            new WorldPoint(1308, 3786, 0),
            75,
            1,
            NpcID.KONAR_QUO_MATEN,
            NpcID.KONAR_QUO_MATEN,
            false
    ),

    /**
     * Nieve - High level master in Tree Gnome Stronghold.
     * After MM2, replaced by Steve.
     */
    NIEVE(
            "Nieve",
            5,
            new WorldPoint(2432, 3423, 0),
            85,
            1,
            NpcID.NIEVE,
            NpcID.STEVE, // Steve's NPC ID (post-MM2)
            false
    ),

    /**
     * Steve - Replaces Nieve after Monkey Madness II.
     * Same location and requirements as Nieve.
     */
    STEVE(
            "Steve",
            5,  // Same master ID as Nieve
            new WorldPoint(2432, 3423, 0),
            85,
            1,
            NpcID.STEVE,
            NpcID.STEVE,
            false
    ),

    /**
     * Duradel - Highest level standard master in Shilo Village.
     * Requires Shilo Village quest completion.
     */
    DURADEL(
            "Duradel",
            8,
            new WorldPoint(2869, 2982, 1), // Upstairs in Shilo
            100,
            50,
            NpcID.DURADEL,
            NpcID.DURADEL,
            false
    ),

    // ========================================================================
    // Wilderness Slayer Master
    // ========================================================================

    /**
     * Krystilia - Wilderness slayer master in Edgeville.
     * Assigns wilderness-only tasks with separate streak.
     */
    KRYSTILIA(
            "Krystilia",
            7,
            new WorldPoint(3109, 3516, 0),
            1,  // No combat requirement
            1,
            NpcID.KRYSTILIA,
            NpcID.KRYSTILIA,
            true
    );

    // ========================================================================
    // Fields
    // ========================================================================

    /**
     * Display name of the master.
     */
    private final String displayName;

    /**
     * Master ID as stored in varbit SLAYER_MASTER (VarbitID.SLAYER_MASTER).
     * Used to identify which master assigned the current task.
     */
    private final int masterId;

    /**
     * Location where this master can be found.
     */
    private final WorldPoint location;

    /**
     * Minimum combat level required to receive tasks.
     */
    private final int combatLevelRequirement;

    /**
     * Minimum slayer level required to receive tasks.
     */
    private final int slayerLevelRequirement;

    /**
     * Primary NPC ID for this master.
     * Sourced from RuneLite's {@link NpcID}.
     */
    private final int npcId;

    /**
     * Alternate NPC ID (e.g., Steve for Nieve).
     * Sourced from RuneLite's {@link NpcID}.
     */
    private final int alternateNpcId;

    /**
     * Whether this master assigns wilderness tasks.
     */
    private final boolean wildernessOnly;

    // ========================================================================
    // Static Lookup Methods
    // ========================================================================

    /**
     * Find a master by their varbit master ID.
     *
     * @param masterId the master ID from VarbitID.SLAYER_MASTER
     * @return the master, or null if not found
     */
    @Nullable
    public static SlayerMaster fromMasterId(int masterId) {
        for (SlayerMaster master : values()) {
            if (master.masterId == masterId) {
                return master;
            }
        }
        return null;
    }

    /**
     * Find a master by NPC ID.
     *
     * @param npcId the NPC ID
     * @return the master, or null if not found
     */
    @Nullable
    public static SlayerMaster fromNpcId(int npcId) {
        // First check primary NPC IDs (exact match takes precedence)
        for (SlayerMaster master : values()) {
            if (master.npcId == npcId) {
                return master;
            }
        }
        // Then check alternate NPC IDs
        for (SlayerMaster master : values()) {
            if (master.alternateNpcId == npcId) {
                return master;
            }
        }
        return null;
    }

    /**
     * Get the best available master for a given combat and slayer level.
     * Excludes wilderness master and Konar by default.
     *
     * @param combatLevel player's combat level
     * @param slayerLevel player's slayer level
     * @return the highest-tier master available
     */
    public static SlayerMaster getBestMaster(int combatLevel, int slayerLevel) {
        return getBestMaster(combatLevel, slayerLevel, false, false);
    }

    /**
     * Get the best available master for given levels with options.
     *
     * @param combatLevel player's combat level
     * @param slayerLevel player's slayer level
     * @param allowKonar whether to consider Konar (location-restricted)
     * @param allowWilderness whether to consider Krystilia (wilderness)
     * @return the highest-tier master available
     */
    public static SlayerMaster getBestMaster(int combatLevel, int slayerLevel, 
                                              boolean allowKonar, boolean allowWilderness) {
        SlayerMaster best = TURAEL;
        int bestCombatReq = TURAEL.combatLevelRequirement;

        for (SlayerMaster master : values()) {
            // Skip wilderness master unless explicitly allowed
            if (master.wildernessOnly && !allowWilderness) {
                continue;
            }

            // Skip Konar unless explicitly allowed
            if (master == KONAR && !allowKonar) {
                continue;
            }

            // Skip duplicates (Spria, Steve)
            if (master == SPRIA || master == STEVE) {
                continue;
            }

            // Check requirements
            if (combatLevel >= master.combatLevelRequirement &&
                slayerLevel >= master.slayerLevelRequirement) {
                // Prefer higher combat requirement masters (they give better tasks)
                if (master.combatLevelRequirement > bestCombatReq) {
                    best = master;
                    bestCombatReq = master.combatLevelRequirement;
                }
            }
        }

        return best;
    }

    /**
     * Get all non-wilderness masters sorted by tier (lowest to highest).
     *
     * @return array of standard masters
     */
    public static SlayerMaster[] getStandardMasters() {
        return Arrays.stream(values())
                .filter(m -> !m.wildernessOnly)
                .filter(m -> m != SPRIA && m != STEVE) // Exclude duplicates
                .sorted((a, b) -> Integer.compare(a.combatLevelRequirement, b.combatLevelRequirement))
                .toArray(SlayerMaster[]::new);
    }

    // ========================================================================
    // Instance Methods
    // ========================================================================

    /**
     * Check if a player meets the requirements for this master.
     *
     * @param combatLevel player's combat level
     * @param slayerLevel player's slayer level
     * @return true if requirements are met
     */
    public boolean meetsRequirements(int combatLevel, int slayerLevel) {
        return combatLevel >= this.combatLevelRequirement &&
               slayerLevel >= this.slayerLevelRequirement;
    }

    /**
     * Get the WebWalker destination ID for this master.
     * Can be used for navigation.
     *
     * @return web node identifier
     */
    public String getWebNodeId() {
        return "slayer_master_" + name().toLowerCase();
    }

    /**
     * Check if this master assigns location-specific tasks (Konar).
     *
     * @return true if tasks are location-restricted
     */
    public boolean hasLocationRestrictions() {
        return this == KONAR;
    }

    /**
     * Check if this is a beginner master that can reset streaks.
     *
     * @return true if Turael or Spria
     */
    public boolean canResetStreak() {
        return this == TURAEL || this == SPRIA;
    }

    /**
     * Get the dialogue options needed to get a new task.
     * Used by DialogueTask for automation.
     *
     * @return array of dialogue option texts
     */
    public String[] getNewTaskDialogueOptions() {
        // Most masters use similar dialogue
        return new String[]{
                "I need another assignment",
                "assignment",
                "task"
        };
    }

    /**
     * Get the dialogue options to check current task.
     *
     * @return array of dialogue option texts
     */
    public String[] getCheckTaskDialogueOptions() {
        return new String[]{
                "How am I doing",
                "current task",
                "check"
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}
