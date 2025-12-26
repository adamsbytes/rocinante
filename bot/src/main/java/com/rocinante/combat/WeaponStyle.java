package com.rocinante.combat;

import lombok.Getter;

/**
 * Weapon attack styles (damage types).
 *
 * These determine what type of damage is dealt and what defense roll enemies use.
 * Some enemies are weak to specific styles (e.g., Kalphite Queen is weak to stab).
 */
@Getter
public enum WeaponStyle {

    /**
     * Slash attacks - swords, scimitars, halberds, etc.
     */
    SLASH("Slash"),

    /**
     * Stab attacks - daggers, spears, rapiers, etc.
     */
    STAB("Stab"),

    /**
     * Crush attacks - maces, hammers, mauls, etc.
     */
    CRUSH("Crush"),

    /**
     * Magic attacks - all spellcasting.
     */
    MAGIC("Magic"),

    /**
     * Ranged attacks - bows, crossbows, thrown weapons.
     */
    RANGED("Ranged"),

    /**
     * Any style - no preference, use whatever is configured or default.
     */
    ANY("Any");

    private final String displayName;

    WeaponStyle(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Check if this is a melee style.
     *
     * @return true if slash, stab, or crush
     */
    public boolean isMelee() {
        return this == SLASH || this == STAB || this == CRUSH;
    }
}

