package com.rocinante.navigation;

/**
 * Types of edges in the navigation web.
 * Per REQUIREMENTS.md Section 7.2.1.
 */
public enum WebEdgeType {
    /**
     * Standard walking path (no interaction required).
     */
    WALK,

    /**
     * Magical teleport (spell, jewelry, tablet).
     */
    TELEPORT,

    /**
     * NPC-based transport (ship, balloon, spirit tree, fairy ring).
     */
    TRANSPORT,

    /**
     * Agility shortcut (may have failure chance).
     */
    AGILITY,

    /**
     * Path unlocked by quest completion.
     */
    QUEST_LOCKED,

    /**
     * Requires opening door/gate.
     */
    DOOR,

    /**
     * Requires climbing stairs/ladder (plane transition).
     */
    STAIRS,

    /**
     * Requires toll payment or item (may be free after quest).
     */
    TOLL,

    /**
     * Free teleport with cooldown (home teleport, grouping/minigame teleports).
     * No item cost but time-gated. Filtered by cooldown status during pathfinding.
     * 
     * <p>Home teleport: 30-minute cooldown, destination varies by respawn point.
     * <p>Grouping teleport: 20-minute shared cooldown, teleports to minigame locations.
     * 
     * <p>Edge metadata should include:
     * <ul>
     *   <li>teleport_type: "home" or "grouping"</li>
     *   <li>teleport_id: GroupingTeleport enum name (for grouping type)</li>
     *   <li>respawn_point: RespawnPoint enum name (for home type)</li>
     * </ul>
     */
    FREE_TELEPORT
}

