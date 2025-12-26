package com.rocinante.combat;

/**
 * Attack styles for combat.
 *
 * Per REQUIREMENTS.md Section 10.3, determines what type of gear
 * should be equipped for combat.
 */
public enum AttackStyle {

    /**
     * Melee combat with swords, scimitars, etc.
     * Attack range: 1 tile.
     */
    MELEE(1),

    /**
     * Ranged combat with bows, crossbows, thrown weapons.
     * Attack range: 4-10 tiles depending on weapon.
     */
    RANGED(7),

    /**
     * Magic combat with spells.
     * Attack range: 10 tiles (standard spells).
     */
    MAGIC(10);

    private final int defaultRange;

    AttackStyle(int defaultRange) {
        this.defaultRange = defaultRange;
    }

    /**
     * Get the default attack range for this style.
     *
     * @return attack range in tiles
     */
    public int getDefaultRange() {
        return defaultRange;
    }
}

