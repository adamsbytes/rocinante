package com.rocinante.tasks.impl.minigame.wintertodt;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Types of interruptions that can occur during Wintertodt.
 * Based on RuneLite's WintertodtInterruptType.
 *
 * @see WintertodtTask
 */
@AllArgsConstructor
@Getter
public enum WintertodtInterruptType {

    /**
     * "The cold of the Wintertodt seeps into your bones."
     * Deals damage based on proximity to Wintertodt.
     */
    COLD("Cold damage"),

    /**
     * "The freezing cold attack hits you."
     * Area of effect attack from Wintertodt.
     */
    SNOWFALL("Snowfall attack"),

    /**
     * "The brazier is broken and shrapnel damages you."
     * Damage from brazier breaking.
     */
    BRAZIER("Brazier explosion"),

    /**
     * "You have run out of bruma roots to feed the fire."
     * No more roots in inventory.
     */
    OUT_OF_ROOTS("Out of roots"),

    /**
     * "Your inventory is too full to hold any more roots."
     * Inventory full when trying to chop.
     */
    INVENTORY_FULL("Inventory full"),

    /**
     * "You fix the brazier."
     * Successfully repaired brazier.
     */
    FIXED_BRAZIER("Fixed brazier"),

    /**
     * "You light the brazier."
     * Successfully lit brazier.
     */
    LIT_BRAZIER("Lit brazier"),

    /**
     * "The brazier has gone out."
     * Brazier needs to be relit.
     */
    BRAZIER_WENT_OUT("Brazier went out");

    private final String description;

    /**
     * Check if this interrupt type deals damage.
     *
     * @return true if this interrupt damages the player
     */
    public boolean causesDamage() {
        return this == COLD || this == SNOWFALL || this == BRAZIER;
    }

    /**
     * Check if this interrupt requires action from the player.
     *
     * @return true if player should respond
     */
    public boolean requiresAction() {
        return this == OUT_OF_ROOTS || 
               this == INVENTORY_FULL || 
               this == BRAZIER_WENT_OUT ||
               causesDamage();
    }
}

