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
    DOOR
}

