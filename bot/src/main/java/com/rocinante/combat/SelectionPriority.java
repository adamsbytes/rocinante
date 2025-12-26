package com.rocinante.combat;

/**
 * Target selection priority strategies for combat.
 *
 * Per REQUIREMENTS.md Section 10.5.1, configurable priority order determines
 * how targets are selected during combat. Multiple priorities can be combined
 * in order of preference.
 */
public enum SelectionPriority {

    /**
     * NPCs already targeting the player (reactive combat).
     * Prioritizes self-defense by attacking enemies that are already aggressive.
     */
    TARGETING_PLAYER,

    /**
     * NPCs with the lowest current HP.
     * Optimizes for fast kills and quick XP/loot.
     */
    LOWEST_HP,

    /**
     * NPCs with the highest current HP.
     * Useful for sustained combat training or when avoiding kill-stealing.
     */
    HIGHEST_HP,

    /**
     * Nearest NPC to the player.
     * Minimizes travel time between targets.
     */
    NEAREST,

    /**
     * Specific NPC by definition ID.
     * Targets only NPCs matching configured IDs.
     */
    SPECIFIC_ID,

    /**
     * Specific NPC by name.
     * Targets only NPCs matching configured names (case-insensitive).
     */
    SPECIFIC_NAME
}

