package com.rocinante.tasks.impl.minigame.wintertodt;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Training strategies for Wintertodt.
 *
 * <p>Each strategy has different gameplay patterns and XP rates:
 * <ul>
 *   <li>SIMPLE - Just chop and feed roots, fastest points but lower FM XP</li>
 *   <li>FLETCH - Fletch roots into kindling for higher FM XP</li>
 *   <li>WOODCUTTING - Focus on woodcutting XP (chop more, feed less)</li>
 * </ul>
 *
 * @see WintertodtConfig
 */
@AllArgsConstructor
@Getter
public enum WintertodtStrategy {

    /**
     * Simple strategy: chop roots, feed brazier.
     * <ul>
     *   <li>Fastest points per hour</li>
     *   <li>Lower FM XP per hour</li>
     *   <li>Good for rushing levels or supplies</li>
     * </ul>
     */
    SIMPLE("Chop and Feed", false, 10),

    /**
     * Fletch strategy: chop roots, fletch into kindling, feed.
     * <ul>
     *   <li>Higher FM XP per hour</li>
     *   <li>Additional Fletching XP</li>
     *   <li>Slower points per hour</li>
     * </ul>
     */
    FLETCH("Fletch and Feed", true, 25),

    /**
     * Woodcutting focus: maximize time chopping.
     * <ul>
     *   <li>Higher Woodcutting XP</li>
     *   <li>Only feed enough for 500 points</li>
     *   <li>Good for ironmen needing WC XP</li>
     * </ul>
     */
    WOODCUTTING("Woodcutting Focus", false, 10);

    /**
     * Human-readable description.
     */
    private final String description;

    /**
     * Whether this strategy uses fletching.
     */
    private final boolean fletch;

    /**
     * Points gained per item fed with this strategy.
     */
    private final int pointsPerFeed;

    /**
     * Check if this strategy should fletch roots.
     */
    public boolean shouldFletch() {
        return fletch;
    }
}

