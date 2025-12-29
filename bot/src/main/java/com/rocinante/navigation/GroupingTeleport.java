package com.rocinante.navigation;

import lombok.Getter;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Set;

/**
 * Enumeration of all Grouping (minigame) teleport destinations.
 * 
 * Grouping teleports share a 20-minute cooldown and teleport players to minigame locations.
 * Each teleport may have specific unlock requirements (quests, skills, combat level, favour).
 * 
 * <p>Coordinates sourced from RuneLite MinigameLocation.java.
 * <p>Requirements sourced from OSRS Wiki grouping page.
 */
@Getter
public enum GroupingTeleport {
    
    /**
     * Barbarian Assault - Wave-based minigame with roles.
     * No requirements.
     */
    BARBARIAN_ASSAULT(
            "Barbarian Assault",
            new WorldPoint(2531, 3569, 0),
            "barbarian_assault",
            null, // No quest required
            0, // No combat level required
            null, // No skill required
            0, // No skill level required
            null, // No favour house
            0 // No favour percent
    ),

    /**
     * Blast Furnace - Smithing minigame with ore smelting.
     * Requires: The Giant Dwarf started.
     */
    BLAST_FURNACE(
            "Blast Furnace",
            new WorldPoint(2931, 10196, 0),
            "blast_furnace",
            Quest.THE_GIANT_DWARF,
            0, // No combat level required
            null, // No skill required
            0, // No skill level required
            null, // No favour house
            0 // No favour percent
    ),

    /**
     * Burthorpe Games Room - Casual board games.
     * No requirements.
     */
    BURTHORPE_GAMES_ROOM(
            "Burthorpe Games Room",
            new WorldPoint(2898, 3570, 0),
            "burthorpe_games_room",
            null,
            0,
            null,
            0,
            null,
            0
    ),

    /**
     * Castle Wars - Capture the flag PvP minigame.
     * No requirements.
     */
    CASTLE_WARS(
            "Castle Wars",
            new WorldPoint(2439, 3092, 0),
            "castle_wars",
            null,
            0,
            null,
            0,
            null,
            0
    ),

    /**
     * Clan Wars - Free-for-all PvP arena.
     * No requirements.
     */
    CLAN_WARS(
            "Clan Wars",
            new WorldPoint(3133, 3621, 0),
            "clan_wars",
            null,
            0,
            null,
            0,
            null,
            0
    ),

    /**
     * Fishing Trawler - Cooperative fishing minigame.
     * No requirements.
     */
    FISHING_TRAWLER(
            "Fishing Trawler",
            new WorldPoint(2667, 3163, 0),
            "fishing_trawler",
            null,
            0,
            null,
            0,
            null,
            0
    ),

    /**
     * Giants' Foundry - Smithing minigame.
     * Requires: Sleeping Giants quest completed.
     */
    GIANTS_FOUNDRY(
            "Giants' Foundry",
            new WorldPoint(3366, 3147, 0),
            "giants_foundry",
            Quest.SLEEPING_GIANTS,
            0,
            null,
            0,
            null,
            0
    ),

    /**
     * Guardians of the Rift - Runecraft minigame.
     * Requires: Temple of the Eye quest completed.
     */
    GUARDIANS_OF_THE_RIFT(
            "Guardians of the Rift",
            new WorldPoint(3615, 9471, 0),
            "guardians_of_the_rift",
            Quest.TEMPLE_OF_THE_EYE,
            0,
            null,
            0,
            null,
            0
    ),

    /**
     * Last Man Standing - Battle royale PvP minigame.
     * Casual mode: No requirements.
     * Competitive mode: 48 combat level (we use casual destination).
     */
    LAST_MAN_STANDING(
            "Last Man Standing",
            new WorldPoint(3138, 3635, 0),
            "last_man_standing",
            null,
            0, // Casual has no combat requirement; competitive needs 48
            null,
            0,
            null,
            0
    ),

    /**
     * Nightmare Zone - Practice combat minigame.
     * Requires: 5 quest bosses defeated (any of the qualifying quests).
     * We use a placeholder - actual check is via varbit NIGHTMARE_ZONE_POINTS or similar.
     */
    NIGHTMARE_ZONE(
            "Nightmare Zone",
            new WorldPoint(2606, 3115, 0),
            "nightmare_zone",
            null, // Complex requirement - checked separately via NMZ varbit
            0,
            null,
            0,
            null,
            0
    ),

    /**
     * Pest Control - Combat minigame defending the Void Knight.
     * Requires: 40 combat level for novice boat.
     */
    PEST_CONTROL(
            "Pest Control",
            new WorldPoint(2660, 2637, 0),
            "pest_control",
            null,
            40, // Combat level requirement for novice boat
            null,
            0,
            null,
            0
    ),

    /**
     * PvP Arena - Ranked PvP fights.
     * No requirements.
     */
    PVP_ARENA(
            "PvP Arena",
            new WorldPoint(3313, 3238, 0),
            "pvp_arena",
            null,
            0,
            null,
            0,
            null,
            0
    ),

    /**
     * Rat Pits - Cat vs Rat minigame.
     * Requires: Ratcatchers quest started.
     */
    RAT_PITS(
            "Rat Pits",
            new WorldPoint(3266, 3400, 0),
            "rat_pits",
            Quest.RATCATCHERS,
            0,
            null,
            0,
            null,
            0
    ),

    /**
     * Shades of Mort'ton - Temple building minigame.
     * Requires: Shades of Mort'ton quest completed.
     */
    SHADES_OF_MORTTON(
            "Shades of Mort'ton",
            new WorldPoint(3505, 3315, 0),
            "shades_of_mortton",
            Quest.SHADES_OF_MORTTON,
            0,
            null,
            0,
            null,
            0
    ),

    /**
     * Soul Wars - Team-based PvP minigame.
     * No requirements.
     */
    SOUL_WARS(
            "Soul Wars",
            new WorldPoint(2209, 2855, 0),
            "soul_wars",
            null,
            0,
            null,
            0,
            null,
            0
    ),

    /**
     * Tempoross - Skilling boss (Fishing).
     * No requirements.
     */
    TEMPOROSS(
            "Tempoross",
            new WorldPoint(3135, 2840, 0),
            "tempoross",
            null,
            0,
            null,
            0,
            null,
            0
    ),

    /**
     * Tithe Farm - Farming minigame.
     * Requires: 100% Hosidius favour.
     */
    TITHE_FARM(
            "Tithe Farm",
            new WorldPoint(1796, 3501, 0),
            "tithe_farm",
            null,
            0,
            null,
            0,
            "Hosidius",
            100
    ),

    /**
     * TzHaar Fight Pit - PvP arena in TzHaar city.
     * No requirements.
     */
    TZHAAR_FIGHT_PIT(
            "TzHaar Fight Pit",
            new WorldPoint(2398, 5177, 0),
            "tzhaar_fight_pit",
            null,
            0,
            null,
            0,
            null,
            0
    ),

    /**
     * Volcanic Mine - Mining minigame.
     * Requires: Bone Voyage completed + 50 Mining.
     */
    VOLCANIC_MINE(
            "Volcanic Mine",
            new WorldPoint(3812, 3810, 0), // Ferry location, actual mine is underground
            "volcanic_mine",
            Quest.BONE_VOYAGE,
            0,
            Skill.MINING,
            50,
            null,
            0
    );

    /**
     * Human-readable display name for the minigame.
     */
    private final String displayName;

    /**
     * Destination coordinates for the teleport.
     */
    private final WorldPoint destination;

    /**
     * Unique identifier for navigation graph edges.
     */
    private final String edgeId;

    /**
     * Required quest (null if none). Quest must be completed (or started for some).
     */
    @Nullable
    private final Quest requiredQuest;

    /**
     * Minimum combat level required (0 if none).
     */
    private final int requiredCombatLevel;

    /**
     * Required skill for level check (null if none).
     */
    @Nullable
    private final Skill requiredSkill;

    /**
     * Minimum level in required skill (0 if none).
     */
    private final int requiredSkillLevel;

    /**
     * Kourend house for favour requirement (null if none).
     */
    @Nullable
    private final String requiredFavourHouse;

    /**
     * Minimum favour percentage (0-100, 0 if none).
     */
    private final int requiredFavourPercent;

    /**
     * Widget group ID for the Grouping interface.
     * From RuneLite InterfaceID.GROUPING = 76.
     */
    public static final int GROUPING_WIDGET_GROUP = 76;

    /**
     * Widget child ID for the teleport button.
     * From InterfaceID.Grouping.TELEPORT = 0x004c_0020 = child 32.
     */
    public static final int TELEPORT_BUTTON_CHILD = 32;

    /**
     * Widget child ID for the dropdown menu.
     * From InterfaceID.Grouping.DROPDOWN_TOP = 0x004c_0006 = child 6.
     */
    public static final int DROPDOWN_CHILD = 6;

    GroupingTeleport(String displayName, WorldPoint destination, String edgeId,
                     @Nullable Quest requiredQuest, int requiredCombatLevel,
                     @Nullable Skill requiredSkill, int requiredSkillLevel,
                     @Nullable String requiredFavourHouse, int requiredFavourPercent) {
        this.displayName = displayName;
        this.destination = destination;
        this.edgeId = edgeId;
        this.requiredQuest = requiredQuest;
        this.requiredCombatLevel = requiredCombatLevel;
        this.requiredSkill = requiredSkill;
        this.requiredSkillLevel = requiredSkillLevel;
        this.requiredFavourHouse = requiredFavourHouse;
        this.requiredFavourPercent = requiredFavourPercent;
    }

    /**
     * Check if this teleport has any unlock requirements.
     *
     * @return true if there are requirements to unlock this teleport
     */
    public boolean hasRequirements() {
        return requiredQuest != null 
                || requiredCombatLevel > 0 
                || requiredSkill != null 
                || requiredFavourHouse != null;
    }

    /**
     * Check if this teleport requires a specific quest.
     *
     * @return true if a quest is required
     */
    public boolean requiresQuest() {
        return requiredQuest != null;
    }

    /**
     * Check if this teleport requires a minimum combat level.
     *
     * @return true if combat level is required
     */
    public boolean requiresCombatLevel() {
        return requiredCombatLevel > 0;
    }

    /**
     * Check if this teleport requires a specific skill level.
     *
     * @return true if a skill level is required
     */
    public boolean requiresSkillLevel() {
        return requiredSkill != null && requiredSkillLevel > 0;
    }

    /**
     * Check if this teleport requires Kourend favour.
     *
     * @return true if favour is required
     */
    public boolean requiresFavour() {
        return requiredFavourHouse != null && requiredFavourPercent > 0;
    }

    /**
     * Get all teleports that have no requirements.
     *
     * @return set of teleports with no unlock requirements
     */
    public static Set<GroupingTeleport> getUnlockedByDefault() {
        Set<GroupingTeleport> unlocked = EnumSet.noneOf(GroupingTeleport.class);
        for (GroupingTeleport teleport : values()) {
            if (!teleport.hasRequirements()) {
                unlocked.add(teleport);
            }
        }
        return unlocked;
    }

    /**
     * Get all teleports that require a specific quest.
     *
     * @param quest the quest to check
     * @return set of teleports requiring that quest
     */
    public static Set<GroupingTeleport> getRequiringQuest(Quest quest) {
        Set<GroupingTeleport> requiring = EnumSet.noneOf(GroupingTeleport.class);
        for (GroupingTeleport teleport : values()) {
            if (teleport.requiredQuest == quest) {
                requiring.add(teleport);
            }
        }
        return requiring;
    }

    /**
     * Find a teleport by its edge ID.
     *
     * @param edgeId the edge ID to search for
     * @return the matching teleport, or null if not found
     */
    @Nullable
    public static GroupingTeleport fromEdgeId(String edgeId) {
        for (GroupingTeleport teleport : values()) {
            if (teleport.edgeId.equals(edgeId)) {
                return teleport;
            }
        }
        return null;
    }

    /**
     * Find a teleport by its display name (case-insensitive).
     *
     * @param name the display name to search for
     * @return the matching teleport, or null if not found
     */
    @Nullable
    public static GroupingTeleport fromDisplayName(String name) {
        for (GroupingTeleport teleport : values()) {
            if (teleport.displayName.equalsIgnoreCase(name)) {
                return teleport;
            }
        }
        return null;
    }
}

