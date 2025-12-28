package com.rocinante.tasks.minigame.library;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

import java.util.Arrays;

/**
 * NPCs in the Arceuus Library that request books.
 *
 * <p>Players speak to one of the three customers (Sam, Professor Gracklebone, or Villia)
 * to receive a book request. Delivering the correct book grants a Book of Arcane Knowledge
 * which can be used for Magic or Runecraft XP.
 *
 * <p>NPC IDs sourced from RuneLite's gameval NpcID constants.
 *
 * @see ArceuusLibraryTask
 */
@AllArgsConstructor
@Getter
public enum LibraryCustomer {

    /**
     * Sam - One of the library customers who requests books.
     */
    SAM(7047, "Sam", new WorldPoint(1625, 3808, 0)),

    /**
     * Professor Gracklebone - One of the library customers who requests books.
     */
    PROFESSOR_GRACKLEBONE(7048, "Professor Gracklebone", new WorldPoint(1625, 3802, 0)),

    /**
     * Villia - One of the library customers who requests books.
     */
    VILLIA(7049, "Villia", new WorldPoint(1645, 3816, 0));

    /**
     * The NPC ID for this customer.
     */
    private final int npcId;

    /**
     * Display name for the customer.
     */
    private final String name;

    /**
     * Approximate location where this customer can be found.
     */
    private final WorldPoint location;

    /**
     * Check if the given NPC ID is a library customer.
     *
     * @param npcId the NPC ID to check
     * @return true if this is a library customer
     */
    public static boolean isLibraryCustomer(int npcId) {
        return Arrays.stream(values())
                .anyMatch(c -> c.npcId == npcId);
    }

    /**
     * Get a customer by NPC ID.
     *
     * @param npcId the NPC ID
     * @return the customer, or null if not found
     */
    public static LibraryCustomer byNpcId(int npcId) {
        return Arrays.stream(values())
                .filter(c -> c.npcId == npcId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Get all customer NPC IDs as an array.
     *
     * @return array of customer NPC IDs
     */
    public static int[] getAllNpcIds() {
        return Arrays.stream(values())
                .mapToInt(LibraryCustomer::getNpcId)
                .toArray();
    }
}

