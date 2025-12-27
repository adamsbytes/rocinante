package com.rocinante.puzzle;

import com.rocinante.puzzle.solvers.SlidingPuzzleSolver;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for SlidingPuzzleSolver.
 */
public class SlidingPuzzleSolverTest {

    private SlidingPuzzleSolver solver;

    @Before
    public void setUp() {
        solver = new SlidingPuzzleSolver();
    }

    // ========================================================================
    // Basic Configuration Tests
    // ========================================================================

    @Test
    public void testGetPuzzleType() {
        assertEquals(PuzzleType.SLIDING_PUZZLE, solver.getPuzzleType());
    }

    @Test
    public void testGetWidgetGroupId() {
        assertEquals(306, solver.getWidgetGroupId());
    }

    @Test
    public void testGetSolveTimeoutMs() {
        assertEquals(2000, solver.getSolveTimeoutMs());
    }

    @Test
    public void testGetClickDelayMs() {
        assertEquals(100, solver.getClickDelayMs());
    }

    // ========================================================================
    // Solve Tests - Already Solved
    // ========================================================================

    @Test
    public void testSolve_AlreadySolved() {
        // Create solved puzzle state
        int[] tiles = new int[25];
        for (int i = 0; i < 24; i++) {
            tiles[i] = i;
        }
        tiles[24] = PuzzleState.BLANK_TILE;

        PuzzleState state = new PuzzleState(tiles);
        assertTrue(state.isSolved());

        List<WidgetClick> solution = solver.solve(state);

        assertTrue(solution.isEmpty());
    }

    // ========================================================================
    // Solve Tests - Simple Cases
    // ========================================================================

    @Test
    public void testSolve_OneMove() {
        // Create puzzle one move from solved
        // Blank at position 23, tile 23 at position 24
        int[] tiles = new int[25];
        for (int i = 0; i < 23; i++) {
            tiles[i] = i;
        }
        tiles[23] = PuzzleState.BLANK_TILE;
        tiles[24] = 23;

        PuzzleState state = new PuzzleState(tiles);
        assertFalse(state.isSolved());

        List<WidgetClick> solution = solver.solve(state);

        assertNotNull(solution);
        assertEquals(1, solution.size());

        // Verify click targets the piece container
        WidgetClick click = solution.get(0);
        assertEquals(306, click.getGroupId());
        assertEquals(1, click.getChildId());
    }

    @Test
    public void testSolve_TwoMoves() {
        // Create puzzle two moves from solved
        // Move blank left then down
        int[] tiles = new int[25];
        for (int i = 0; i < 22; i++) {
            tiles[i] = i;
        }
        tiles[22] = PuzzleState.BLANK_TILE;
        tiles[23] = 22;
        tiles[24] = 23;

        PuzzleState state = new PuzzleState(tiles);
        assertFalse(state.isSolved());

        List<WidgetClick> solution = solver.solve(state);

        assertNotNull(solution);
        assertEquals(2, solution.size());
    }

    @Test
    public void testSolve_ThreeMoves() {
        // Create puzzle three moves from solved
        int[] tiles = new int[25];
        for (int i = 0; i < 21; i++) {
            tiles[i] = i;
        }
        tiles[21] = PuzzleState.BLANK_TILE;
        tiles[22] = 21;
        tiles[23] = 22;
        tiles[24] = 23;

        PuzzleState state = new PuzzleState(tiles);
        assertFalse(state.isSolved());

        List<WidgetClick> solution = solver.solve(state);

        assertNotNull(solution);
        assertEquals(3, solution.size());
    }

    // ========================================================================
    // Solve Tests - Moderate Cases (limited moves to keep tests fast)
    // ========================================================================

    @Test
    public void testSolve_FourMoves() {
        // Create puzzle by making 4 moves from solved state
        // Start with solved state and move blank: 24 -> 23 -> 22 -> 21 -> 20
        // This means: swap 23 with blank, swap 22 with blank, etc.
        // Result: tiles 20,21,22,23 shifted right, blank at position 20
        int[] tiles = new int[25];
        for (int i = 0; i < 20; i++) {
            tiles[i] = i;
        }
        tiles[20] = PuzzleState.BLANK_TILE;
        tiles[21] = 20;
        tiles[22] = 21;
        tiles[23] = 22;
        tiles[24] = 23;

        PuzzleState state = new PuzzleState(tiles);
        assertFalse(state.isSolved());

        List<WidgetClick> solution = solver.solve(state);

        assertNotNull(solution);
        assertFalse(solution.isEmpty());

        // Verify all clicks target valid positions
        for (WidgetClick click : solution) {
            assertEquals(306, click.getGroupId());
            assertEquals(1, click.getChildId());
            assertTrue(click.getDynamicChildIndex() >= 0);
            assertTrue(click.getDynamicChildIndex() < 25);
        }
    }

    @Test
    public void testSolve_VerticalMoves() {
        // Create puzzle by moving blank up: 24 -> 19 -> 14
        // Tiles 14, 19 moved down, blank at position 14
        int[] tiles = new int[25];
        for (int i = 0; i < 14; i++) {
            tiles[i] = i;
        }
        tiles[14] = PuzzleState.BLANK_TILE;
        tiles[15] = 15;
        tiles[16] = 16;
        tiles[17] = 17;
        tiles[18] = 18;
        tiles[19] = 14;
        tiles[20] = 20;
        tiles[21] = 21;
        tiles[22] = 22;
        tiles[23] = 23;
        tiles[24] = 19;

        PuzzleState state = new PuzzleState(tiles);
        assertFalse(state.isSolved());

        List<WidgetClick> solution = solver.solve(state);

        assertNotNull(solution);
        assertFalse(solution.isEmpty());
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testSolve_NullState() {
        List<WidgetClick> solution = solver.solve(null);
        assertTrue(solution.isEmpty());
    }

    @Test
    public void testSolve_WrongPuzzleType() {
        // Create light box state
        boolean[] lights = new boolean[40];
        PuzzleState lightState = new PuzzleState(lights);

        List<WidgetClick> solution = solver.solve(lightState);
        assertTrue(solution.isEmpty());
    }

    // ========================================================================
    // Solution Validation
    // ========================================================================

    @Test
    public void testSolve_SolutionIsValid() {
        // Create a simple puzzle (3 moves from solved) and verify the solution works
        int[] tiles = new int[25];
        for (int i = 0; i < 21; i++) {
            tiles[i] = i;
        }
        tiles[21] = PuzzleState.BLANK_TILE;
        tiles[22] = 21;
        tiles[23] = 22;
        tiles[24] = 23;

        PuzzleState state = new PuzzleState(tiles);
        assertFalse(state.isSolved());

        List<WidgetClick> solution = solver.solve(state);

        assertNotNull(solution);
        assertFalse(solution.isEmpty());

        // Verify solution by simulating clicks
        PuzzleState current = state;
        for (WidgetClick click : solution) {
            int targetPos = click.getDynamicChildIndex();
            // The click is on where the blank will be after the move
            // So we need to simulate swapping blank with the adjacent tile
            current = current.swap(targetPos);
        }

        assertTrue("Solution should result in solved state", current.isSolved());
    }

    // ========================================================================
    // Widget Click Tests
    // ========================================================================

    @Test
    public void testWidgetClick_Properties() {
        // Create simple puzzle
        int[] tiles = new int[25];
        for (int i = 0; i < 23; i++) {
            tiles[i] = i;
        }
        tiles[23] = PuzzleState.BLANK_TILE;
        tiles[24] = 23;

        PuzzleState state = new PuzzleState(tiles);
        List<WidgetClick> solution = solver.solve(state);

        assertEquals(1, solution.size());

        WidgetClick click = solution.get(0);
        assertTrue(click.hasDynamicChild());
        assertNotNull(click.getDescription());
        assertNotNull(click.toDisplayString());
        assertTrue(click.toDisplayString().contains("306"));
    }
}

