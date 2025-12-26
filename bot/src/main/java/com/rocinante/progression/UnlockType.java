package com.rocinante.progression;

/**
 * Categories of unlockable features/abilities.
 *
 * Per REQUIREMENTS.md Section 12.5, tracks various unlock types that affect
 * available actions and navigation options.
 */
public enum UnlockType {

    /**
     * Prayer abilities unlocked by level.
     */
    PRAYER,

    /**
     * Teleport methods (spells, jewelry, tablets).
     */
    TELEPORT,

    /**
     * Transportation methods (fairy rings, spirit trees, etc.).
     */
    TRANSPORTATION,

    /**
     * Areas unlocked by quests or skills.
     */
    AREA,

    /**
     * Special features (NPC contact, house tabs, etc.).
     */
    FEATURE,

    /**
     * Skill-locked content (level thresholds).
     */
    SKILL,

    /**
     * Quest-locked content.
     */
    QUEST
}

