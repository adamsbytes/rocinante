package com.rocinante.tasks.minigame.library;

import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Interface for finding book locations in the Arceuus Library.
 *
 * <p>This abstraction allows different implementations:
 * <ul>
 *   <li>{@link RuneLiteLibraryBookFinder} - Uses RuneLite's Kourend Library plugin</li>
 *   <li>Manual tracking based on searches</li>
 * </ul>
 *
 * <p>The RuneLite plugin uses a constraint satisfaction solver to predict
 * book locations based on partial information from searches.
 *
 * @see ArceuusLibraryTask
 */
public interface LibraryBookFinder {

    /**
     * Get the book currently requested by a customer.
     *
     * @return the requested book, or null if no request is active
     */
    @Nullable
    LibraryBook getCustomerBook();

    /**
     * Get the NPC ID of the current customer.
     *
     * @return customer model ID, or -1 if not available
     */
    int getCustomerId();

    /**
     * Check if the library layout has been fully solved.
     *
     * @return true if all book locations are known
     */
    boolean isSolved();

    /**
     * Get the predicted location of a book.
     *
     * @param book the book to find
     * @return the predicted bookcase location, or null if unknown
     */
    @Nullable
    WorldPoint getBookLocation(LibraryBook book);

    /**
     * Get all bookcase locations that haven't been checked.
     *
     * @return list of unchecked bookcase world points
     */
    List<WorldPoint> getUncheckedBookcases();

    /**
     * Record that a book was found at a location.
     * This helps the solver narrow down possibilities.
     *
     * @param book     the book found
     * @param location the bookcase location
     */
    void recordBookFound(LibraryBook book, WorldPoint location);

    /**
     * Record that a bookcase was searched but contained no book.
     *
     * @param location the bookcase location
     */
    void recordEmptyBookcase(WorldPoint location);

    /**
     * Reset the solver state (called when books shuffle).
     */
    void reset();

    /**
     * Get all known/predicted book locations.
     *
     * @return map of book to location
     */
    Map<LibraryBook, WorldPoint> getAllBookLocations();
}

