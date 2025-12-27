package com.rocinante.puzzle;

import lombok.Getter;
import lombok.ToString;

import java.util.Arrays;

/**
 * Represents the current state of a puzzle.
 *
 * <p>This is a generic state container that can represent different puzzle types:
 * <ul>
 *   <li>Sliding puzzle: 5x5 grid of tile values (0-24, -1 for blank)</li>
 *   <li>Light box: 5x8 grid of light states (true=on, false=off)</li>
 * </ul>
 *
 * <p>The state is immutable once created. New states are created for each move.
 */
@Getter
@ToString
public class PuzzleState {

    /**
     * Dimension for sliding puzzles (5x5 grid).
     */
    public static final int SLIDING_PUZZLE_DIMENSION = 5;

    /**
     * Value representing the blank tile in sliding puzzles.
     */
    public static final int BLANK_TILE = -1;

    /**
     * Light box grid dimensions.
     */
    public static final int LIGHT_BOX_ROWS = 5;
    public static final int LIGHT_BOX_COLS = 8;

    /**
     * The puzzle type this state represents.
     */
    private final PuzzleType puzzleType;

    /**
     * Tile values for sliding puzzle (flattened 5x5 grid).
     * Values 0-23 represent tiles, -1 represents the blank space.
     */
    private final int[] tiles;

    /**
     * Light states for light box puzzle (flattened 5x8 grid).
     * true = light is on, false = light is off.
     */
    private final boolean[] lights;

    /**
     * Position of the blank tile (for sliding puzzles).
     */
    private final int blankPosition;

    /**
     * Whether this state represents a solved puzzle.
     */
    private final boolean solved;

    /**
     * Create a sliding puzzle state.
     *
     * @param tiles the tile values (length must be 25)
     */
    public PuzzleState(int[] tiles) {
        if (tiles == null || tiles.length != SLIDING_PUZZLE_DIMENSION * SLIDING_PUZZLE_DIMENSION) {
            throw new IllegalArgumentException("Sliding puzzle requires 25 tiles");
        }

        this.puzzleType = PuzzleType.SLIDING_PUZZLE;
        this.tiles = Arrays.copyOf(tiles, tiles.length);
        this.lights = null;
        this.blankPosition = findBlankPosition();
        this.solved = isSlidingPuzzleSolved();
    }

    /**
     * Create a light box puzzle state.
     *
     * @param lights the light states (length must be 40)
     */
    public PuzzleState(boolean[] lights) {
        if (lights == null || lights.length != LIGHT_BOX_ROWS * LIGHT_BOX_COLS) {
            throw new IllegalArgumentException("Light box requires 40 lights");
        }

        this.puzzleType = PuzzleType.LIGHT_BOX;
        this.tiles = null;
        this.lights = Arrays.copyOf(lights, lights.length);
        this.blankPosition = -1;
        this.solved = isLightBoxSolved();
    }

    /**
     * Private constructor for creating derived states.
     */
    private PuzzleState(PuzzleType type, int[] tiles, boolean[] lights, int blankPos, boolean solved) {
        this.puzzleType = type;
        this.tiles = tiles;
        this.lights = lights;
        this.blankPosition = blankPos;
        this.solved = solved;
    }

    /**
     * Find the position of the blank tile in a sliding puzzle.
     */
    private int findBlankPosition() {
        if (tiles == null) return -1;
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i] == BLANK_TILE) {
                return i;
            }
        }
        throw new IllegalStateException("No blank tile found in sliding puzzle");
    }

    /**
     * Check if the sliding puzzle is in solved state.
     * Solved state: tiles 0-23 in order, blank at position 24.
     */
    private boolean isSlidingPuzzleSolved() {
        if (tiles == null) return false;
        for (int i = 0; i < tiles.length - 1; i++) {
            if (tiles[i] != i) {
                return false;
            }
        }
        return tiles[tiles.length - 1] == BLANK_TILE;
    }

    /**
     * Check if the light box is solved (all lights off).
     */
    private boolean isLightBoxSolved() {
        if (lights == null) return false;
        for (boolean light : lights) {
            if (light) return false;
        }
        return true;
    }

    /**
     * Get a tile value at the specified position.
     *
     * @param x column (0-4)
     * @param y row (0-4)
     * @return the tile value at that position
     * @throws IllegalStateException if not a sliding puzzle
     */
    public int getTile(int x, int y) {
        if (puzzleType != PuzzleType.SLIDING_PUZZLE || tiles == null) {
            throw new IllegalStateException("Not a sliding puzzle");
        }
        return tiles[y * SLIDING_PUZZLE_DIMENSION + x];
    }

    /**
     * Get a tile value at the specified flat index.
     *
     * @param index the flat index (0-24)
     * @return the tile value at that position
     * @throws IllegalStateException if not a sliding puzzle
     */
    public int getTile(int index) {
        if (puzzleType != PuzzleType.SLIDING_PUZZLE || tiles == null) {
            throw new IllegalStateException("Not a sliding puzzle");
        }
        return tiles[index];
    }

    /**
     * Get a light state at the specified position.
     *
     * @param x column (0-7)
     * @param y row (0-4)
     * @return true if the light is on
     * @throws IllegalStateException if not a light box puzzle
     */
    public boolean getLight(int x, int y) {
        if (puzzleType != PuzzleType.LIGHT_BOX || lights == null) {
            throw new IllegalStateException("Not a light box puzzle");
        }
        return lights[y * LIGHT_BOX_COLS + x];
    }

    /**
     * Get a light state at the specified flat index.
     *
     * @param index the flat index (0-39)
     * @return true if the light is on
     * @throws IllegalStateException if not a light box puzzle
     */
    public boolean getLight(int index) {
        if (puzzleType != PuzzleType.LIGHT_BOX || lights == null) {
            throw new IllegalStateException("Not a light box puzzle");
        }
        return lights[index];
    }

    /**
     * Create a new state by swapping the blank tile with an adjacent tile.
     *
     * @param newBlankPosition the position to move the blank to
     * @return a new puzzle state with the tiles swapped
     * @throws IllegalArgumentException if the move is invalid
     */
    public PuzzleState swap(int newBlankPosition) {
        if (puzzleType != PuzzleType.SLIDING_PUZZLE || tiles == null) {
            throw new IllegalStateException("Swap only valid for sliding puzzles");
        }

        // Validate adjacent move
        int blankX = blankPosition % SLIDING_PUZZLE_DIMENSION;
        int blankY = blankPosition / SLIDING_PUZZLE_DIMENSION;
        int newX = newBlankPosition % SLIDING_PUZZLE_DIMENSION;
        int newY = newBlankPosition / SLIDING_PUZZLE_DIMENSION;

        int dx = Math.abs(newX - blankX);
        int dy = Math.abs(newY - blankY);

        if (!((dx == 1 && dy == 0) || (dx == 0 && dy == 1))) {
            throw new IllegalArgumentException("Invalid swap: positions must be adjacent");
        }

        int[] newTiles = Arrays.copyOf(tiles, tiles.length);
        newTiles[blankPosition] = tiles[newBlankPosition];
        newTiles[newBlankPosition] = BLANK_TILE;

        return new PuzzleState(newTiles);
    }

    /**
     * Create a new state by toggling lights (for light box puzzle).
     *
     * @param togglePattern the pattern of lights to toggle
     * @return a new puzzle state with lights toggled
     */
    public PuzzleState toggleLights(boolean[] togglePattern) {
        if (puzzleType != PuzzleType.LIGHT_BOX || lights == null) {
            throw new IllegalStateException("Toggle only valid for light box puzzles");
        }
        if (togglePattern.length != lights.length) {
            throw new IllegalArgumentException("Toggle pattern must match light count");
        }

        boolean[] newLights = Arrays.copyOf(lights, lights.length);
        for (int i = 0; i < newLights.length; i++) {
            if (togglePattern[i]) {
                newLights[i] = !newLights[i];
            }
        }

        return new PuzzleState(newLights);
    }

    /**
     * Check if two states have the same tile/light configuration.
     *
     * @param pieces the tile values or light states to compare
     * @return true if the configurations match
     */
    public boolean hasPieces(int[] pieces) {
        if (tiles == null) return false;
        return Arrays.equals(tiles, pieces);
    }

    /**
     * Get the X coordinate of the blank tile position.
     *
     * @return the column (0-4) of the blank tile
     */
    public int getBlankX() {
        return blankPosition % SLIDING_PUZZLE_DIMENSION;
    }

    /**
     * Get the Y coordinate of the blank tile position.
     *
     * @return the row (0-4) of the blank tile
     */
    public int getBlankY() {
        return blankPosition / SLIDING_PUZZLE_DIMENSION;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PuzzleState that = (PuzzleState) o;
        if (puzzleType != that.puzzleType) return false;
        if (tiles != null) return Arrays.equals(tiles, that.tiles);
        return Arrays.equals(lights, that.lights);
    }

    @Override
    public int hashCode() {
        int result = puzzleType.hashCode();
        if (tiles != null) result = 31 * result + Arrays.hashCode(tiles);
        if (lights != null) result = 31 * result + Arrays.hashCode(lights);
        return result;
    }
}

