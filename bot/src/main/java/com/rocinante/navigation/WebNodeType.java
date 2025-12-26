package com.rocinante.navigation;

/**
 * Types of nodes in the navigation web.
 * Per REQUIREMENTS.md Section 7.2.1.
 */
public enum WebNodeType {
    /**
     * Bank location.
     */
    BANK,

    /**
     * Teleport destination.
     */
    TELEPORT,

    /**
     * Quest-related location.
     */
    QUEST,

    /**
     * Slayer-related location.
     */
    SLAYER,

    /**
     * Generic/general location.
     */
    GENERIC,

    /**
     * Transport hub (e.g., ship dock, spirit tree).
     */
    TRANSPORT,

    /**
     * Shop location.
     */
    SHOP,

    /**
     * Training area.
     */
    TRAINING
}

