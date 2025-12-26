package com.rocinante.navigation;

/**
 * Types of requirements for traversing navigation edges.
 * Per REQUIREMENTS.md Section 7.2.1 - Requirement Types.
 */
public enum EdgeRequirementType {
    /**
     * Minimum magic level required.
     */
    MAGIC_LEVEL,

    /**
     * Minimum agility level required.
     */
    AGILITY_LEVEL,

    /**
     * Quest completion requirement.
     */
    QUEST,

    /**
     * Required item (may be consumed).
     */
    ITEM,

    /**
     * Rune cost for spell.
     */
    RUNES,

    /**
     * Any skill requirement.
     */
    SKILL,

    /**
     * Minimum combat level.
     */
    COMBAT_LEVEL,

    /**
     * Ironman-specific restriction.
     */
    IRONMAN_RESTRICTION
}

