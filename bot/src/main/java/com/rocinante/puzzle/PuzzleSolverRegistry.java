package com.rocinante.puzzle;

import com.rocinante.puzzle.solvers.LightBoxSolver;
import com.rocinante.puzzle.solvers.SlidingPuzzleSolver;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

/**
 * Registry for puzzle solvers.
 *
 * <p>Provides lookup and detection of puzzle solvers based on:
 * <ul>
 *   <li>Puzzle type enum</li>
 *   <li>Widget group ID</li>
 *   <li>Currently visible puzzle interface</li>
 * </ul>
 *
 * <p>All solvers are registered at construction time. New solvers can be added
 * by extending this class or via the {@link #registerSolver} method.
 */
@Slf4j
@Singleton
public class PuzzleSolverRegistry {

    private final Map<PuzzleType, PuzzleSolverStrategy> solversByType = new EnumMap<>(PuzzleType.class);
    private final Map<Integer, PuzzleSolverStrategy> solversByWidgetId = new HashMap<>();

    /**
     * Create a new registry with default solvers.
     */
    @Inject
    public PuzzleSolverRegistry() {
        // Register built-in solvers
        registerSolver(new SlidingPuzzleSolver());
        registerSolver(new LightBoxSolver());

        log.info("PuzzleSolverRegistry initialized with {} solvers", solversByType.size());
    }

    /**
     * Register a puzzle solver.
     *
     * @param solver the solver to register
     */
    public void registerSolver(PuzzleSolverStrategy solver) {
        if (solver == null) {
            throw new IllegalArgumentException("Solver cannot be null");
        }

        PuzzleType type = solver.getPuzzleType();
        int widgetId = solver.getWidgetGroupId();

        if (solversByType.containsKey(type)) {
            log.warn("Replacing existing solver for puzzle type: {}", type);
        }

        solversByType.put(type, solver);
        solversByWidgetId.put(widgetId, solver);

        log.debug("Registered solver for {} (widget: {})", type, widgetId);
    }

    /**
     * Get a solver by puzzle type.
     *
     * @param type the puzzle type
     * @return the solver, or empty if not registered
     */
    public Optional<PuzzleSolverStrategy> getSolver(PuzzleType type) {
        return Optional.ofNullable(solversByType.get(type));
    }

    /**
     * Get a solver by widget group ID.
     *
     * @param widgetGroupId the widget group ID
     * @return the solver, or empty if not registered
     */
    public Optional<PuzzleSolverStrategy> getSolverByWidgetId(int widgetGroupId) {
        return Optional.ofNullable(solversByWidgetId.get(widgetGroupId));
    }

    /**
     * Detect and return a solver for any currently visible puzzle.
     *
     * @param client the RuneLite client
     * @return the solver for the visible puzzle, or empty if no puzzle is visible
     */
    public Optional<PuzzleSolverStrategy> detectVisiblePuzzle(Client client) {
        for (PuzzleSolverStrategy solver : solversByType.values()) {
            if (solver.isPuzzleVisible(client)) {
                log.debug("Detected visible puzzle: {}", solver.getPuzzleType());
                return Optional.of(solver);
            }
        }
        return Optional.empty();
    }

    /**
     * Get all registered puzzle types.
     *
     * @return set of registered puzzle types
     */
    public Set<PuzzleType> getRegisteredTypes() {
        return Collections.unmodifiableSet(solversByType.keySet());
    }

    /**
     * Get all registered widget IDs.
     *
     * @return set of registered widget IDs
     */
    public Set<Integer> getRegisteredWidgetIds() {
        return Collections.unmodifiableSet(solversByWidgetId.keySet());
    }

    /**
     * Check if a puzzle type has a registered solver.
     *
     * @param type the puzzle type
     * @return true if a solver is registered
     */
    public boolean hasSolver(PuzzleType type) {
        return solversByType.containsKey(type);
    }
}

