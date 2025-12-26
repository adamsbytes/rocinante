package com.rocinante.state;

/**
 * Enum representing the type of poison effect on the player.
 *
 * Per REQUIREMENTS.md Section 10.6.4, distinguishes between poison and venom.
 */
public enum PoisonType {

    /**
     * No poison effect active.
     */
    NONE,

    /**
     * Standard poison.
     * Initial damage: 6 HP, decreases by 1 HP each hit until reaches 1 HP.
     * Hits every 18 seconds (30 ticks).
     * Cured with any antipoison.
     */
    POISON,

    /**
     * Venom (more dangerous).
     * Initial damage: 6 HP, INCREASES by 2 HP each hit (6 → 8 → 10 → ... → max 20 HP).
     * Hits every 18 seconds (30 ticks).
     * Requires antivenom to fully cure. Antipoison downgrades to regular poison.
     * Serpentine helm provides passive immunity.
     */
    VENOM
}

