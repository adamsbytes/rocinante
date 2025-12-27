package com.rocinante.puzzle.solvers;

import com.rocinante.puzzle.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.widgets.Widget;

import java.util.*;

/**
 * Solver for 5x5 sliding tile puzzles (15-puzzle variant).
 *
 * <p>Uses IDA* (Iterative Deepening A*) search with Manhattan distance heuristic.
 * This is the same algorithm used by RuneLite's puzzle solver plugin.
 *
 * <p>The puzzle consists of 24 numbered tiles and one blank space in a 5x5 grid.
 * The goal is to arrange tiles in order (0-23) with the blank in position 24.
 *
 * <p>Widget IDs:
 * <ul>
 *   <li>Interface: 306 (TRAIL_SLIDEPUZZLE)</li>
 *   <li>Pieces container: child 1 (PIECES)</li>
 *   <li>Inventory: InventoryID 140 (TRAIL_PUZZLEINV)</li>
 * </ul>
 */
@Slf4j
public class SlidingPuzzleSolver implements PuzzleSolverStrategy {

    /**
     * Widget group ID for sliding puzzle interface.
     */
    private static final int WIDGET_GROUP_ID = 306;

    /**
     * Widget child ID for the puzzle pieces container.
     */
    private static final int WIDGET_PIECES_CHILD = 1;

    /**
     * Inventory ID for puzzle tiles.
     */
    private static final int INVENTORY_ID = 140; // InventoryID.TRAIL_PUZZLEINV

    /**
     * Puzzle grid dimension.
     */
    private static final int DIMENSION = PuzzleState.SLIDING_PUZZLE_DIMENSION;

    /**
     * Value representing blank tile.
     */
    private static final int BLANK_TILE = PuzzleState.BLANK_TILE;

    /**
     * Maximum iterations for IDA* search.
     */
    private static final int MAX_ITERATIONS = 100000;

    @Override
    public PuzzleType getPuzzleType() {
        return PuzzleType.SLIDING_PUZZLE;
    }

    @Override
    public int getWidgetGroupId() {
        return WIDGET_GROUP_ID;
    }

    @Override
    public boolean isPuzzleVisible(Client client) {
        Widget puzzleWidget = client.getWidget(WIDGET_GROUP_ID, WIDGET_PIECES_CHILD);
        return puzzleWidget != null && !puzzleWidget.isHidden();
    }

    @Override
    public Optional<PuzzleState> detectState(Client client) {
        if (!isPuzzleVisible(client)) {
            return Optional.empty();
        }

        ItemContainer container = client.getItemContainer(INVENTORY_ID);
        if (container == null) {
            log.debug("Puzzle inventory container not found");
            return Optional.empty();
        }

        int[] tiles = readTilesFromContainer(container);
        if (tiles == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(new PuzzleState(tiles));
        } catch (Exception e) {
            log.error("Failed to create puzzle state", e);
            return Optional.empty();
        }
    }

    /**
     * Read tile positions from the item container and convert to solver format.
     */
    private int[] readTilesFromContainer(ItemContainer container) {
        int[] tiles = new int[DIMENSION * DIMENSION];
        Item[] items = container.getItems();

        if (items.length < DIMENSION * DIMENSION - 1) {
            log.debug("Not enough items in puzzle container: {}", items.length);
            return null;
        }

        // Find the lowest item ID to normalize values
        int lowestId = Integer.MAX_VALUE;
        for (Item item : items) {
            int id = item.getId();
            if (id > 0 && id < lowestId) {
                lowestId = id;
            }
        }

        // Convert item IDs to tile values (0-23)
        for (int i = 0; i < items.length && i < tiles.length; i++) {
            int itemId = items[i].getId();
            if (itemId <= 0) {
                tiles[i] = BLANK_TILE;
            } else {
                tiles[i] = itemId - lowestId;
            }
        }

        // Handle blank tile if it's in the last position (not in items array)
        if (items.length == DIMENSION * DIMENSION - 1) {
            tiles[tiles.length - 1] = BLANK_TILE;
        }

        return tiles;
    }

    @Override
    public List<WidgetClick> solve(PuzzleState state) {
        if (state == null || state.getPuzzleType() != PuzzleType.SLIDING_PUZZLE) {
            return Collections.emptyList();
        }

        if (state.isSolved()) {
            log.debug("Puzzle already solved");
            return Collections.emptyList();
        }

        // Run IDA* search
        List<Integer> solutionMoves = idaStarSearch(state);

        if (solutionMoves == null || solutionMoves.isEmpty()) {
            log.warn("No solution found for sliding puzzle");
            return Collections.emptyList();
        }

        // Convert moves (blank positions) to widget clicks
        return convertMovesToClicks(solutionMoves);
    }

    /**
     * IDA* search for optimal solution path.
     *
     * @param initialState the starting puzzle state
     * @return list of blank tile positions representing moves, or null if unsolvable
     */
    private List<Integer> idaStarSearch(PuzzleState initialState) {
        int[] tiles = new int[DIMENSION * DIMENSION];
        for (int i = 0; i < tiles.length; i++) {
            tiles[i] = initialState.getTile(i);
        }

        int bound = manhattanDistance(tiles);
        List<Integer> path = new ArrayList<>();
        path.add(initialState.getBlankPosition());

        int iterations = 0;

        while (iterations < MAX_ITERATIONS) {
            int result = search(tiles, initialState.getBlankPosition(), 0, bound, path, -1);

            if (result == 0) {
                // Solution found
                return path;
            }

            if (result == Integer.MAX_VALUE) {
                // No solution
                return null;
            }

            bound = result;
            iterations++;
        }

        log.warn("IDA* search exceeded max iterations");
        return null;
    }

    /**
     * Recursive search step for IDA*.
     *
     * @param tiles     current tile configuration
     * @param blankPos  current blank position
     * @param g         cost to reach current state
     * @param bound     current depth bound
     * @param path      path of blank positions
     * @param lastMove  previous blank position (to avoid reversing)
     * @return 0 if solved, new bound if not found, MAX_VALUE if no solution
     */
    private int search(int[] tiles, int blankPos, int g, int bound, List<Integer> path, int lastMove) {
        int h = manhattanDistance(tiles);
        int f = g + h;

        if (f > bound) {
            return f;
        }

        if (h == 0) {
            // Solved
            return 0;
        }

        int min = Integer.MAX_VALUE;

        // Try all possible moves
        int blankX = blankPos % DIMENSION;
        int blankY = blankPos / DIMENSION;

        int[][] moves = {
                {blankX - 1, blankY}, // Left
                {blankX + 1, blankY}, // Right
                {blankX, blankY - 1}, // Up
                {blankX, blankY + 1}  // Down
        };

        for (int[] move : moves) {
            int newX = move[0];
            int newY = move[1];

            // Check bounds
            if (newX < 0 || newX >= DIMENSION || newY < 0 || newY >= DIMENSION) {
                continue;
            }

            int newBlankPos = newY * DIMENSION + newX;

            // Avoid reversing the previous move
            if (newBlankPos == lastMove) {
                continue;
            }

            // Perform the swap
            tiles[blankPos] = tiles[newBlankPos];
            tiles[newBlankPos] = BLANK_TILE;

            path.add(newBlankPos);

            int result = search(tiles, newBlankPos, g + 1, bound, path, blankPos);

            if (result == 0) {
                return 0; // Solution found
            }

            if (result < min) {
                min = result;
            }

            // Undo the swap
            path.remove(path.size() - 1);
            tiles[newBlankPos] = tiles[blankPos];
            tiles[blankPos] = BLANK_TILE;
        }

        return min;
    }

    /**
     * Calculate Manhattan distance heuristic for the current tile configuration.
     *
     * @param tiles the current tile configuration
     * @return the sum of Manhattan distances for all tiles
     */
    private int manhattanDistance(int[] tiles) {
        int distance = 0;

        for (int i = 0; i < tiles.length; i++) {
            int value = tiles[i];
            if (value == BLANK_TILE) {
                continue;
            }

            int currentX = i % DIMENSION;
            int currentY = i / DIMENSION;
            int targetX = value % DIMENSION;
            int targetY = value / DIMENSION;

            distance += Math.abs(currentX - targetX) + Math.abs(currentY - targetY);
        }

        return distance;
    }

    /**
     * Convert a list of blank positions to widget clicks.
     *
     * @param moves list of blank positions (the first is the initial position)
     * @return list of widget clicks
     */
    private List<WidgetClick> convertMovesToClicks(List<Integer> moves) {
        List<WidgetClick> clicks = new ArrayList<>();

        // Skip the first position (initial blank position)
        for (int i = 1; i < moves.size(); i++) {
            int blankPos = moves.get(i);

            // The click is on the tile that will move INTO the blank space
            // So we click the position where the blank will be AFTER the move
            clicks.add(WidgetClick.builder()
                    .groupId(WIDGET_GROUP_ID)
                    .childId(WIDGET_PIECES_CHILD)
                    .dynamicChildIndex(blankPos)
                    .description("Move tile to position " + blankPos)
                    .build());
        }

        log.debug("Generated {} clicks for sliding puzzle solution", clicks.size());
        return clicks;
    }

    @Override
    public long getSolveTimeoutMs() {
        return 2000; // 2 seconds for sliding puzzles (can be complex)
    }

    @Override
    public int getClickDelayMs() {
        return 100; // 100ms between puzzle clicks
    }
}

