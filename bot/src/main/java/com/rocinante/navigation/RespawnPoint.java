package com.rocinante.navigation;

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;

/**
 * Enumeration of all respawn/home teleport destinations in OSRS.
 * 
 * Home teleport takes the player to their current respawn point.
 * Different respawn points can be unlocked through quests, achievements, and purchases.
 * 
 * <p>Varbit references from RuneLite VarbitID:
 * <ul>
 *   <li>EDGEVILLE_SPAWN (621) - Edgeville enabled</li>
 *   <li>EDGEVILLE_SPAWN_UNLOCKED (623) - Desert Treasure completion</li>
 *   <li>FALADOR_SPAWN (668) - Recruitment Drive completion</li>
 *   <li>CAMELOT_SPAWN (3910) - Knight Waves Training Grounds</li>
 *   <li>CIVITAS_SPAWN (9805) - Civitas illa Fortis</li>
 *   <li>KOUREND_SPAWN (12310) - Architectural Alliance</li>
 *   <li>WILDERNESS_SPAWN (10528) - Ferox Enclave</li>
 * </ul>
 */
@Getter
public enum RespawnPoint {

    /**
     * Lumbridge Castle - Default spawn for all accounts.
     * Always available, no requirements.
     */
    LUMBRIDGE(
            "Lumbridge",
            new WorldPoint(3222, 3218, 0),
            "lumbridge_spawn",
            -1, // No varbit - always available
            null
    ),

    /**
     * Falador - Unlocked by completing Recruitment Drive quest.
     */
    FALADOR(
            "Falador",
            new WorldPoint(2971, 3340, 0),
            "falador_spawn",
            668, // FALADOR_SPAWN
            "Recruitment Drive"
    ),

    /**
     * Camelot - Unlocked by completing Knight Waves Training Grounds.
     */
    CAMELOT(
            "Camelot",
            new WorldPoint(2757, 3477, 0),
            "camelot_spawn",
            3910, // CAMELOT_SPAWN
            "Knight Waves Training Grounds"
    ),

    /**
     * Edgeville - Unlocked by completing Desert Treasure quest.
     */
    EDGEVILLE(
            "Edgeville",
            new WorldPoint(3094, 3491, 0),
            "edgeville_spawn",
            621, // EDGEVILLE_SPAWN (or 623 for unlocked check)
            "Desert Treasure"
    ),

    /**
     * Ferox Enclave - Unlocked by visiting the Ferox Enclave.
     * Located in the Wilderness but is a safe zone.
     */
    FEROX_ENCLAVE(
            "Ferox Enclave",
            new WorldPoint(3129, 3631, 0),
            "ferox_enclave_spawn",
            10528, // WILDERNESS_SPAWN
            null // Just visit to unlock
    ),

    /**
     * Kourend Castle - Unlocked by completing Architectural Alliance.
     */
    KOUREND(
            "Kourend Castle",
            new WorldPoint(1640, 3672, 0),
            "kourend_spawn",
            12310, // KOUREND_SPAWN
            "Architectural Alliance"
    ),

    /**
     * Civitas illa Fortis - Unlocked by visiting the city.
     */
    CIVITAS_ILLA_FORTIS(
            "Civitas illa Fortis",
            new WorldPoint(1681, 3131, 0),
            "civitas_spawn",
            9805, // CIVITAS_SPAWN
            null // Just visit to unlock
    );

    /**
     * Human-readable display name.
     */
    private final String displayName;

    /**
     * WorldPoint destination for home teleport.
     */
    private final WorldPoint destination;

    /**
     * Unique edge ID for navigation graph.
     */
    private final String edgeId;

    /**
     * Varbit ID to check if this spawn is currently active.
     * -1 for Lumbridge (always available).
     */
    private final int varbitId;

    /**
     * Unlock requirement description (quest name or null if no quest).
     */
    @Nullable
    private final String unlockRequirement;

    /**
     * Home teleport cooldown in minutes.
     */
    public static final int HOME_TELEPORT_COOLDOWN_MINUTES = 30;

    /**
     * Home teleport animation time in ticks (~16 seconds = 26.67 ticks, rounded to 27).
     */
    public static final int HOME_TELEPORT_ANIMATION_TICKS = 27;

    RespawnPoint(String displayName, WorldPoint destination, String edgeId,
                 int varbitId, @Nullable String unlockRequirement) {
        this.displayName = displayName;
        this.destination = destination;
        this.edgeId = edgeId;
        this.varbitId = varbitId;
        this.unlockRequirement = unlockRequirement;
    }

    /**
     * Check if this spawn point requires unlocking.
     *
     * @return true if there's an unlock requirement
     */
    public boolean requiresUnlock() {
        return this != LUMBRIDGE;
    }

    /**
     * Check if this spawn point requires a quest to unlock.
     *
     * @return true if a quest is required
     */
    public boolean requiresQuest() {
        return unlockRequirement != null && !unlockRequirement.isEmpty();
    }

    /**
     * Check if this spawn has a varbit to verify active status.
     *
     * @return true if varbit can be checked
     */
    public boolean hasVarbitCheck() {
        return varbitId > 0;
    }

    /**
     * Find a respawn point by its edge ID.
     *
     * @param edgeId the edge ID to search for
     * @return the matching respawn point, or null if not found
     */
    @Nullable
    public static RespawnPoint fromEdgeId(String edgeId) {
        for (RespawnPoint point : values()) {
            if (point.edgeId.equals(edgeId)) {
                return point;
            }
        }
        return null;
    }

    /**
     * Find a respawn point by its display name (case-insensitive).
     *
     * @param name the display name to search for
     * @return the matching respawn point, or null if not found
     */
    @Nullable
    public static RespawnPoint fromDisplayName(String name) {
        for (RespawnPoint point : values()) {
            if (point.displayName.equalsIgnoreCase(name)) {
                return point;
            }
        }
        return null;
    }

    /**
     * Get the default respawn point (Lumbridge).
     *
     * @return the default spawn point
     */
    public static RespawnPoint getDefault() {
        return LUMBRIDGE;
    }
}

