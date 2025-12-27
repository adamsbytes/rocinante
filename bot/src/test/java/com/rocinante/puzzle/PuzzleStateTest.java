package com.rocinante.puzzle;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for PuzzleState.
 */
public class PuzzleStateTest {

    // ========================================================================
    // Sliding Puzzle State Tests
    // ========================================================================

    @Test
    public void testSlidingPuzzle_Creation() {
        int[] tiles = new int[25];
        for (int i = 0; i < 24; i++) {
            tiles[i] = i;
        }
        tiles[24] = PuzzleState.BLANK_TILE;

        PuzzleState state = new PuzzleState(tiles);

        assertEquals(PuzzleType.SLIDING_PUZZLE, state.getPuzzleType());
        assertEquals(24, state.getBlankPosition());
        assertTrue(state.isSolved());
    }

    @Test
    public void testSlidingPuzzle_Unsolved() {
        int[] tiles = new int[25];
        // Shuffled puzzle: blank at position 0
        tiles[0] = PuzzleState.BLANK_TILE;
        for (int i = 1; i < 25; i++) {
            tiles[i] = i - 1;
        }

        PuzzleState state = new PuzzleState(tiles);

        assertFalse(state.isSolved());
        assertEquals(0, state.getBlankPosition());
    }

    @Test
    public void testSlidingPuzzle_GetTile() {
        int[] tiles = new int[25];
        for (int i = 0; i < 24; i++) {
            tiles[i] = i;
        }
        tiles[24] = PuzzleState.BLANK_TILE;

        PuzzleState state = new PuzzleState(tiles);

        // Test getTile(x, y)
        assertEquals(0, state.getTile(0, 0));
        assertEquals(4, state.getTile(4, 0));
        assertEquals(5, state.getTile(0, 1));
        assertEquals(23, state.getTile(3, 4));
        assertEquals(PuzzleState.BLANK_TILE, state.getTile(4, 4));

        // Test getTile(index)
        assertEquals(0, state.getTile(0));
        assertEquals(12, state.getTile(12));
    }

    @Test
    public void testSlidingPuzzle_BlankCoordinates() {
        int[] tiles = new int[25];
        for (int i = 0; i < 12; i++) {
            tiles[i] = i;
        }
        tiles[12] = PuzzleState.BLANK_TILE; // Middle of grid
        for (int i = 13; i < 25; i++) {
            tiles[i] = i - 1;
        }

        PuzzleState state = new PuzzleState(tiles);

        assertEquals(2, state.getBlankX()); // 12 % 5 = 2
        assertEquals(2, state.getBlankY()); // 12 / 5 = 2
    }

    @Test
    public void testSlidingPuzzle_Swap() {
        // Create state with blank at position 12 (center)
        int[] tiles = new int[25];
        for (int i = 0; i < 12; i++) {
            tiles[i] = i;
        }
        tiles[12] = PuzzleState.BLANK_TILE;
        for (int i = 13; i < 25; i++) {
            tiles[i] = i - 1;
        }

        PuzzleState state = new PuzzleState(tiles);
        assertEquals(12, state.getBlankPosition());

        // Swap blank with tile to the right (position 13)
        PuzzleState newState = state.swap(13);

        assertEquals(13, newState.getBlankPosition());
        assertEquals(12, newState.getTile(12)); // Old position of blank now has value 12
        assertEquals(PuzzleState.BLANK_TILE, newState.getTile(13));

        // Original state should be unchanged (immutable)
        assertEquals(12, state.getBlankPosition());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSlidingPuzzle_Swap_InvalidMove() {
        // Create state with blank at position 0
        int[] tiles = new int[25];
        tiles[0] = PuzzleState.BLANK_TILE;
        for (int i = 1; i < 25; i++) {
            tiles[i] = i - 1;
        }

        PuzzleState state = new PuzzleState(tiles);

        // Try to swap with non-adjacent position (diagonal)
        state.swap(6); // Position 6 is diagonal from 0
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSlidingPuzzle_InvalidTileCount() {
        int[] tiles = new int[20]; // Wrong size
        new PuzzleState(tiles);
    }

    // ========================================================================
    // Light Box State Tests
    // ========================================================================

    @Test
    public void testLightBox_Creation_AllOff() {
        boolean[] lights = new boolean[40];
        // All lights off
        
        PuzzleState state = new PuzzleState(lights);

        assertEquals(PuzzleType.LIGHT_BOX, state.getPuzzleType());
        assertTrue(state.isSolved()); // All off = solved
    }

    @Test
    public void testLightBox_Creation_SomeOn() {
        boolean[] lights = new boolean[40];
        lights[0] = true;
        lights[10] = true;
        lights[20] = true;

        PuzzleState state = new PuzzleState(lights);

        assertEquals(PuzzleType.LIGHT_BOX, state.getPuzzleType());
        assertFalse(state.isSolved());
    }

    @Test
    public void testLightBox_GetLight() {
        boolean[] lights = new boolean[40];
        lights[0] = true;
        lights[8] = true; // (0, 1) in 8-column layout
        lights[16] = true; // (0, 2) in 8-column layout

        PuzzleState state = new PuzzleState(lights);

        assertTrue(state.getLight(0, 0));
        assertTrue(state.getLight(0, 1));
        assertTrue(state.getLight(0, 2));
        assertFalse(state.getLight(1, 0));
        assertFalse(state.getLight(7, 4));
    }

    @Test
    public void testLightBox_ToggleLights() {
        boolean[] lights = new boolean[40];
        lights[0] = true;
        lights[1] = true;

        PuzzleState state = new PuzzleState(lights);

        // Toggle pattern that flips first two lights
        boolean[] toggle = new boolean[40];
        toggle[0] = true;
        toggle[1] = true;

        PuzzleState newState = state.toggleLights(toggle);

        assertFalse(newState.getLight(0)); // Was on, now off
        assertFalse(newState.getLight(1)); // Was on, now off

        // Original unchanged
        assertTrue(state.getLight(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLightBox_InvalidLightCount() {
        boolean[] lights = new boolean[30]; // Wrong size
        new PuzzleState(lights);
    }

    // ========================================================================
    // Cross-type Tests
    // ========================================================================

    @Test(expected = IllegalStateException.class)
    public void testGetTile_OnLightBox_ThrowsException() {
        boolean[] lights = new boolean[40];
        PuzzleState state = new PuzzleState(lights);

        state.getTile(0, 0); // Should throw
    }

    @Test(expected = IllegalStateException.class)
    public void testGetLight_OnSlidingPuzzle_ThrowsException() {
        int[] tiles = new int[25];
        for (int i = 0; i < 24; i++) tiles[i] = i;
        tiles[24] = PuzzleState.BLANK_TILE;

        PuzzleState state = new PuzzleState(tiles);

        state.getLight(0, 0); // Should throw
    }

    // ========================================================================
    // Equality Tests
    // ========================================================================

    @Test
    public void testEquals_SameSlidingState() {
        int[] tiles1 = new int[25];
        int[] tiles2 = new int[25];
        for (int i = 0; i < 24; i++) {
            tiles1[i] = i;
            tiles2[i] = i;
        }
        tiles1[24] = PuzzleState.BLANK_TILE;
        tiles2[24] = PuzzleState.BLANK_TILE;

        PuzzleState state1 = new PuzzleState(tiles1);
        PuzzleState state2 = new PuzzleState(tiles2);

        assertEquals(state1, state2);
        assertEquals(state1.hashCode(), state2.hashCode());
    }

    @Test
    public void testEquals_DifferentSlidingState() {
        int[] tiles1 = new int[25];
        int[] tiles2 = new int[25];
        for (int i = 0; i < 24; i++) {
            tiles1[i] = i;
            tiles2[i] = i;
        }
        tiles1[24] = PuzzleState.BLANK_TILE;
        tiles2[24] = PuzzleState.BLANK_TILE;

        // Swap two tiles in state2
        tiles2[0] = 1;
        tiles2[1] = 0;

        PuzzleState state1 = new PuzzleState(tiles1);
        PuzzleState state2 = new PuzzleState(tiles2);

        assertNotEquals(state1, state2);
    }

    @Test
    public void testEquals_DifferentTypes() {
        int[] tiles = new int[25];
        for (int i = 0; i < 24; i++) tiles[i] = i;
        tiles[24] = PuzzleState.BLANK_TILE;

        boolean[] lights = new boolean[40];

        PuzzleState slidingState = new PuzzleState(tiles);
        PuzzleState lightState = new PuzzleState(lights);

        assertNotEquals(slidingState, lightState);
    }
}

