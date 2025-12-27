package com.rocinante.puzzle;

import net.runelite.api.Client;

import java.util.List;
import java.util.Optional;

/**
 * Strategy interface for puzzle-specific solvers.
 *
 * <p>Each puzzle type has a dedicated solver that implements this interface.
 * Solvers are responsible for:
 * <ul>
 *   <li>Detecting if their puzzle type is currently visible</li>
 *   <li>Reading the current puzzle state from the game client</li>
 *   <li>Computing a solution (sequence of widget clicks)</li>
 * </ul>
 *
 * <p>Implementations should be stateless - all state is passed via method parameters.
 */
public interface PuzzleSolverStrategy {

    /**
     * Get the puzzle type this solver handles.
     *
     * @return the puzzle type
     */
    PuzzleType getPuzzleType();

    /**
     * Get the widget group ID for detecting this puzzle.
     *
     * @return the widget group ID
     */
    int getWidgetGroupId();

    /**
     * Check if this puzzle type is currently visible in the game.
     *
     * @param client the RuneLite client
     * @return true if the puzzle interface is open
     */
    boolean isPuzzleVisible(Client client);

    /**
     * Read the current puzzle state from the game client.
     *
     * @param client the RuneLite client
     * @return the current puzzle state, or empty if state cannot be read
     */
    Optional<PuzzleState> detectState(Client client);

    /**
     * Compute a solution for the given puzzle state.
     *
     * <p>The solution is a sequence of widget clicks that, when executed in order,
     * will solve the puzzle from the given state.
     *
     * @param state the current puzzle state
     * @return list of widget clicks to solve the puzzle, or empty list if unsolvable
     */
    List<WidgetClick> solve(PuzzleState state);

    /**
     * Check if the puzzle is currently in a solved state.
     *
     * @param client the RuneLite client
     * @return true if the puzzle is solved
     */
    default boolean isSolved(Client client) {
        return detectState(client)
                .map(PuzzleState::isSolved)
                .orElse(false);
    }

    /**
     * Get the maximum time allowed for computing a solution.
     *
     * @return timeout in milliseconds
     */
    default long getSolveTimeoutMs() {
        return 1500; // 1.5 seconds default
    }

    /**
     * Get the recommended delay between puzzle clicks.
     * This helps maintain humanized behavior.
     *
     * @return delay in milliseconds
     */
    default int getClickDelayMs() {
        return 150; // 150ms default, can be randomized by the task
    }
}

