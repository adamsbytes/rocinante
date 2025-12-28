package com.rocinante.tasks.minigame.wintertodt;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Activities the player can perform in Wintertodt.
 * Matches RuneLite's WintertodtActivity enum for consistency.
 *
 * @see WintertodtTask
 */
@AllArgsConstructor
@Getter
public enum WintertodtActivity {

    /**
     * Not performing any action.
     */
    IDLE("Idle"),

    /**
     * Chopping bruma roots from the tree.
     */
    WOODCUTTING("Woodcutting"),

    /**
     * Fletching bruma roots into kindling.
     */
    FLETCHING("Fletching"),

    /**
     * Feeding roots/kindling to the brazier.
     */
    FEEDING_BRAZIER("Feeding"),

    /**
     * Fixing a broken brazier.
     */
    FIXING_BRAZIER("Fixing"),

    /**
     * Lighting an unlit brazier.
     */
    LIGHTING_BRAZIER("Lighting"),

    /**
     * Walking/traveling within the minigame.
     */
    WALKING("Walking"),

    /**
     * Eating food to restore HP.
     */
    EATING("Eating");

    private final String actionString;

    /**
     * Check if this activity is interruptible by damage.
     * Woodcutting and idle are not interrupted.
     *
     * @return true if activity can be interrupted
     */
    public boolean isInterruptible() {
        return this != WOODCUTTING && this != IDLE;
    }
}

