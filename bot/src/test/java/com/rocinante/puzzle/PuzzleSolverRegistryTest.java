package com.rocinante.puzzle;

import com.rocinante.puzzle.solvers.LightBoxSolver;
import com.rocinante.puzzle.solvers.SlidingPuzzleSolver;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Tests for PuzzleSolverRegistry.
 */
public class PuzzleSolverRegistryTest {

    private PuzzleSolverRegistry registry;

    @Before
    public void setUp() {
        registry = new PuzzleSolverRegistry();
    }

    // ========================================================================
    // Registration Tests
    // ========================================================================

    @Test
    public void testDefaultSolversRegistered() {
        // Registry should have sliding puzzle and light box solvers by default
        Set<PuzzleType> types = registry.getRegisteredTypes();

        assertTrue(types.contains(PuzzleType.SLIDING_PUZZLE));
        assertTrue(types.contains(PuzzleType.LIGHT_BOX));
        assertEquals(2, types.size());
    }

    @Test
    public void testGetRegisteredWidgetIds() {
        Set<Integer> widgetIds = registry.getRegisteredWidgetIds();

        assertTrue(widgetIds.contains(306)); // Sliding puzzle
        assertTrue(widgetIds.contains(322)); // Light box
        assertEquals(2, widgetIds.size());
    }

    // ========================================================================
    // Solver Lookup Tests
    // ========================================================================

    @Test
    public void testGetSolver_ByType_SlidingPuzzle() {
        Optional<PuzzleSolverStrategy> solver = registry.getSolver(PuzzleType.SLIDING_PUZZLE);

        assertTrue(solver.isPresent());
        assertTrue(solver.get() instanceof SlidingPuzzleSolver);
        assertEquals(PuzzleType.SLIDING_PUZZLE, solver.get().getPuzzleType());
    }

    @Test
    public void testGetSolver_ByType_LightBox() {
        Optional<PuzzleSolverStrategy> solver = registry.getSolver(PuzzleType.LIGHT_BOX);

        assertTrue(solver.isPresent());
        assertTrue(solver.get() instanceof LightBoxSolver);
        assertEquals(PuzzleType.LIGHT_BOX, solver.get().getPuzzleType());
    }

    @Test
    public void testGetSolver_ByType_Unknown() {
        Optional<PuzzleSolverStrategy> solver = registry.getSolver(PuzzleType.UNKNOWN);

        assertFalse(solver.isPresent());
    }

    @Test
    public void testGetSolverByWidgetId_SlidingPuzzle() {
        Optional<PuzzleSolverStrategy> solver = registry.getSolverByWidgetId(306);

        assertTrue(solver.isPresent());
        assertEquals(PuzzleType.SLIDING_PUZZLE, solver.get().getPuzzleType());
    }

    @Test
    public void testGetSolverByWidgetId_LightBox() {
        Optional<PuzzleSolverStrategy> solver = registry.getSolverByWidgetId(322);

        assertTrue(solver.isPresent());
        assertEquals(PuzzleType.LIGHT_BOX, solver.get().getPuzzleType());
    }

    @Test
    public void testGetSolverByWidgetId_Unknown() {
        Optional<PuzzleSolverStrategy> solver = registry.getSolverByWidgetId(999);

        assertFalse(solver.isPresent());
    }

    // ========================================================================
    // Has Solver Tests
    // ========================================================================

    @Test
    public void testHasSolver_True() {
        assertTrue(registry.hasSolver(PuzzleType.SLIDING_PUZZLE));
        assertTrue(registry.hasSolver(PuzzleType.LIGHT_BOX));
    }

    @Test
    public void testHasSolver_False() {
        assertFalse(registry.hasSolver(PuzzleType.UNKNOWN));
    }

    // ========================================================================
    // Custom Solver Registration Tests
    // ========================================================================

    @Test
    public void testRegisterCustomSolver() {
        // Create a mock solver for testing
        PuzzleSolverStrategy customSolver = new PuzzleSolverStrategy() {
            @Override
            public PuzzleType getPuzzleType() {
                return PuzzleType.UNKNOWN;
            }

            @Override
            public int getWidgetGroupId() {
                return 999;
            }

            @Override
            public boolean isPuzzleVisible(net.runelite.api.Client client) {
                return false;
            }

            @Override
            public Optional<PuzzleState> detectState(net.runelite.api.Client client) {
                return Optional.empty();
            }

            @Override
            public java.util.List<WidgetClick> solve(PuzzleState state) {
                return java.util.Collections.emptyList();
            }
        };

        registry.registerSolver(customSolver);

        assertTrue(registry.hasSolver(PuzzleType.UNKNOWN));
        assertTrue(registry.getSolverByWidgetId(999).isPresent());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterNullSolver() {
        registry.registerSolver(null);
    }
}

