package com.rocinante.combat.spell;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Spellbook types in OSRS.
 *
 * Each spellbook is accessed via different means and contains different spells.
 * The spellbook varbit (4070) indicates which spellbook is active.
 */
@AllArgsConstructor
@Getter
public enum Spellbook {

    /**
     * Standard/Normal spellbook - default for all accounts.
     */
    STANDARD(0, "Standard"),

    /**
     * Ancient Magicks - unlocked after Desert Treasure.
     */
    ANCIENT(1, "Ancient"),

    /**
     * Lunar spellbook - unlocked after Lunar Diplomacy.
     */
    LUNAR(2, "Lunar"),

    /**
     * Arceuus spellbook - unlocked via Arceuus favour.
     */
    ARCEUUS(3, "Arceuus");

    /**
     * Varbit value for this spellbook.
     */
    private final int varbitValue;

    /**
     * Display name.
     */
    private final String displayName;

    /**
     * Varbit ID that stores current spellbook.
     */
    public static final int SPELLBOOK_VARBIT = 4070;
}

