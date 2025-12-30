package com.rocinante.tasks.impl.minigame.library;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tracks the current state of the Arceuus Library minigame.
 *
 * <p>This state class maintains:
 * <ul>
 *   <li>Current book request from the customer</li>
 *   <li>Known/predicted book locations</li>
 *   <li>Books currently held in inventory</li>
 *   <li>Statistics for the session</li>
 * </ul>
 *
 * <p>The state is populated from dialogue parsing and bookcase searches,
 * building up knowledge of book locations over time.
 *
 * @see ArceuusLibraryTask
 */
@Slf4j
public class LibraryState {

    // ========================================================================
    // Current Request State
    // ========================================================================

    /**
     * The book currently requested by the customer.
     * Null if no book is currently requested.
     */
    @Getter
    @Setter
    private LibraryBook requestedBook;

    /**
     * The customer who made the current request.
     * Null if no request is active.
     */
    @Getter
    @Setter
    private LibraryCustomer currentCustomer;

    /**
     * The customer's NPC model ID (for widget detection).
     */
    @Getter
    @Setter
    private int customerModelId = -1;

    // ========================================================================
    // Book Location Tracking
    // ========================================================================

    /**
     * Known book locations from confirmed searches.
     * Maps book to the WorldPoint of the bookcase containing it.
     */
    private final Map<LibraryBook, WorldPoint> confirmedLocations = new EnumMap<>(LibraryBook.class);

    /**
     * Predicted book locations from the RuneLite plugin's solver.
     * Maps book to the WorldPoint of the predicted bookcase.
     */
    private final Map<LibraryBook, WorldPoint> predictedLocations = new EnumMap<>(LibraryBook.class);

    /**
     * Whether the plugin's book predictor has fully solved the library layout.
     */
    @Getter
    @Setter
    private boolean layoutSolved = false;

    // ========================================================================
    // Inventory State
    // ========================================================================

    /**
     * Books currently held in the player's inventory.
     */
    private final Set<LibraryBook> inventoryBooks = EnumSet.noneOf(LibraryBook.class);

    /**
     * Number of Book of Arcane Knowledge items in inventory.
     */
    @Getter
    @Setter
    private int arcaneKnowledgeCount = 0;

    // ========================================================================
    // Session Statistics
    // ========================================================================

    /**
     * Total books delivered this session.
     */
    @Getter
    private int booksDelivered = 0;

    /**
     * Total XP gained from using Books of Arcane Knowledge.
     */
    @Getter
    private double xpGained = 0;

    /**
     * Bookcases searched to train the predictor.
     */
    @Getter
    private int bookcasesSearched = 0;

    // ========================================================================
    // Book Location Methods
    // ========================================================================

    /**
     * Get the known or predicted location of a book.
     *
     * @param book the book to find
     * @return optional containing the book's location
     */
    public Optional<WorldPoint> getBookLocation(LibraryBook book) {
        // Prefer confirmed locations over predictions
        WorldPoint confirmed = confirmedLocations.get(book);
        if (confirmed != null) {
            return Optional.of(confirmed);
        }
        return Optional.ofNullable(predictedLocations.get(book));
    }

    /**
     * Get the location of the currently requested book.
     *
     * @return optional containing the book's location
     */
    public Optional<WorldPoint> getRequestedBookLocation() {
        if (requestedBook == null) {
            return Optional.empty();
        }
        return getBookLocation(requestedBook);
    }

    /**
     * Set a confirmed book location (from player finding the book).
     *
     * @param book     the book found
     * @param location the bookcase location
     */
    public void setConfirmedLocation(LibraryBook book, WorldPoint location) {
        confirmedLocations.put(book, location);
        log.debug("Confirmed {} at {}", book.getShortName(), location);
    }

    /**
     * Set a predicted book location (from plugin solver).
     *
     * @param book     the book
     * @param location the predicted bookcase location
     */
    public void setPredictedLocation(LibraryBook book, WorldPoint location) {
        predictedLocations.put(book, location);
    }

    /**
     * Clear all book locations (called when books shuffle).
     */
    public void clearLocations() {
        confirmedLocations.clear();
        predictedLocations.clear();
        layoutSolved = false;
        log.info("Book locations cleared - library has reshuffled");
    }

    /**
     * Update predicted locations from the plugin.
     *
     * @param predictions map of book to predicted location
     * @param solved      whether the layout is fully solved
     */
    public void updatePredictions(Map<LibraryBook, WorldPoint> predictions, boolean solved) {
        predictedLocations.clear();
        predictedLocations.putAll(predictions);
        layoutSolved = solved;
        log.debug("Updated {} predictions, solved={}", predictions.size(), solved);
    }

    // ========================================================================
    // Inventory Methods
    // ========================================================================

    /**
     * Check if the player has a specific book.
     *
     * @param book the book to check for
     * @return true if the book is in inventory
     */
    public boolean hasBook(LibraryBook book) {
        return inventoryBooks.contains(book);
    }

    /**
     * Check if the player has the requested book.
     *
     * @return true if the requested book is in inventory
     */
    public boolean hasRequestedBook() {
        return requestedBook != null && hasBook(requestedBook);
    }

    /**
     * Add a book to inventory tracking.
     *
     * @param book the book picked up
     */
    public void addBook(LibraryBook book) {
        inventoryBooks.add(book);
    }

    /**
     * Remove a book from inventory tracking.
     *
     * @param book the book delivered/dropped
     */
    public void removeBook(LibraryBook book) {
        inventoryBooks.remove(book);
    }

    /**
     * Update inventory books from item container.
     *
     * @param bookItemIds set of book item IDs in inventory
     */
    public void updateInventoryBooks(Set<Integer> bookItemIds) {
        inventoryBooks.clear();
        for (int itemId : bookItemIds) {
            LibraryBook book = LibraryBook.byItemId(itemId);
            if (book != null) {
                inventoryBooks.add(book);
            }
        }
    }

    /**
     * Get all books currently in inventory.
     *
     * @return unmodifiable set of inventory books
     */
    public Set<LibraryBook> getInventoryBooks() {
        return EnumSet.copyOf(inventoryBooks);
    }

    /**
     * Get the number of books in inventory.
     *
     * @return book count
     */
    public int getInventoryBookCount() {
        return inventoryBooks.size();
    }

    // ========================================================================
    // Request State Methods
    // ========================================================================

    /**
     * Check if there is an active book request.
     *
     * @return true if a book is currently requested
     */
    public boolean hasActiveRequest() {
        return requestedBook != null;
    }

    /**
     * Set the current book request.
     *
     * @param book     the requested book
     * @param customer the customer who made the request
     * @param modelId  the customer's model ID
     */
    public void setRequest(LibraryBook book, LibraryCustomer customer, int modelId) {
        this.requestedBook = book;
        this.currentCustomer = customer;
        this.customerModelId = modelId;
        log.info("Book request: {} from {}", book.getShortName(), customer != null ? customer.getName() : "unknown");
    }

    /**
     * Clear the current request (after delivery or cancellation).
     */
    public void clearRequest() {
        this.requestedBook = null;
        this.currentCustomer = null;
        this.customerModelId = -1;
    }

    // ========================================================================
    // Statistics Methods
    // ========================================================================

    /**
     * Record a book delivery.
     *
     * @param xp XP gained from the delivery
     */
    public void recordDelivery(double xp) {
        booksDelivered++;
        xpGained += xp;
        log.debug("Delivered book #{}, +{} XP (total: {})", booksDelivered, xp, xpGained);
    }

    /**
     * Record a bookcase search.
     */
    public void recordSearch() {
        bookcasesSearched++;
    }

    /**
     * Reset all session statistics.
     */
    public void resetStatistics() {
        booksDelivered = 0;
        xpGained = 0;
        bookcasesSearched = 0;
    }

    /**
     * Get a summary of the current state.
     *
     * @return human-readable state summary
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("LibraryState[");
        if (requestedBook != null) {
            sb.append("request=").append(requestedBook.getShortName());
            if (hasRequestedBook()) {
                sb.append("(have)");
            } else {
                getRequestedBookLocation().ifPresent(loc -> 
                    sb.append("@").append(loc.getX()).append(",").append(loc.getY())
                );
            }
        } else {
            sb.append("no request");
        }
        sb.append(", delivered=").append(booksDelivered);
        sb.append(", solved=").append(layoutSolved);
        sb.append("]");
        return sb.toString();
    }
}

