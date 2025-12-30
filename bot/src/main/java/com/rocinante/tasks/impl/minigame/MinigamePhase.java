package com.rocinante.tasks.impl.minigame;

/**
 * Phases of minigame task execution.
 *
 * <p>Minigames typically follow this lifecycle:
 * <pre>
 * TRAVEL -> ENTRY -> WAITING -> ACTIVE -> REWARDS -> (loop back to WAITING or EXIT)
 * </pre>
 *
 * <p>Not all minigames use all phases - for example, some may not have
 * explicit entry mechanics or reward collection steps.
 */
public enum MinigamePhase {

    /**
     * Traveling to the minigame location.
     * Handled via standard navigation (WebWalker/WalkToTask).
     */
    TRAVEL,

    /**
     * Entering the minigame area.
     * May involve interacting with NPCs, objects, or portals.
     */
    ENTRY,

    /**
     * Waiting for the minigame round/game to start.
     * Player is in the area but activity hasn't begun.
     */
    WAITING,

    /**
     * Active gameplay phase.
     * The main minigame loop executes here.
     */
    ACTIVE,

    /**
     * Collecting rewards after round completion.
     * May involve opening chests, talking to NPCs, etc.
     */
    REWARDS,

    /**
     * Preparing for next round or exiting.
     * Handles banking supplies, eating, regearing.
     */
    RESUPPLY,

    /**
     * Exiting the minigame.
     * Used when stopping training or task completion.
     */
    EXIT
}

