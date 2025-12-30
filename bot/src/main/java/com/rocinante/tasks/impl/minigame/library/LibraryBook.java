package com.rocinante.tasks.impl.minigame.library;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Books found in the Arceuus Library.
 *
 * <p>These 16 books can be found in bookcases and delivered to customers
 * for experience rewards. Book locations change every 80-100 minutes.
 *
 * <p>Item IDs sourced from RuneLite's gameval ItemID constants.
 *
 * @see ArceuusLibraryTask
 */
@AllArgsConstructor
@Getter
public enum LibraryBook {

    /**
     * Rada's Census - Census of King Rada III, by Matthias Vorseth.
     */
    RADAS_CENSUS(13524, "Rada's Census", "Census of King Rada III, by Matthias Vorseth."),

    /**
     * Ricktor's Diary 7 - Diary of Steklan Ricktor, volume 7.
     */
    RICKTORS_DIARY_7(13525, "Ricktor's Diary 7", "Diary of Steklan Ricktor, volume 7."),

    /**
     * Eathram & Rada extract - An extract from Eathram & Rada.
     */
    EATHRAM_RADA_EXTRACT(13526, "Eathram & Rada extract", "An extract from Eathram & Rada, by Anonymous."),

    /**
     * Killing of a King - Killing of a King, by Griselle.
     */
    KILLING_OF_A_KING(13527, "Killing of a King", "Killing of a King, by Griselle."),

    /**
     * Hosidius Letter - A letter from Lord Hosidius to the Council of Elders.
     */
    HOSIDIUS_LETTER(13528, "Hosidius Letter", "A letter from Lord Hosidius to the Council of Elders."),

    /**
     * Wintertodt Parable - The Parable of the Wintertodt.
     */
    WINTERTODT_PARABLE(13529, "Wintertodt Parable", "The Parable of the Wintertodt, by Anonymous."),

    /**
     * Twill Accord - The Royal Accord of Twill.
     */
    TWILL_ACCORD(13530, "Twill Accord", "The Royal Accord of Twill."),

    /**
     * Byrne's Coronation Speech - Speech of King Byrne I.
     */
    BYRNES_CORONATION_SPEECH(13531, "Byrnes Coronation Speech", "Speech of King Byrne I, on the occasion of his coronation."),

    /**
     * The Ideology of Darkness - The Ideology of Darkness, by Philophaire.
     */
    IDEOLOGY_OF_DARKNESS(13532, "The Ideology of Darkness", "The Ideology of Darkness, by Philophaire."),

    /**
     * Rada's Journey - The Journey of Rada, by Griselle.
     */
    RADAS_JOURNEY(13533, "Rada's Journey", "The Journey of Rada, by Griselle."),

    /**
     * Transvergence Theory - The Theory of Transvergence, by Amon Ducot.
     */
    TRANSVERGENCE_THEORY(13534, "Transvergence Theory", "The Theory of Transvergence, by Amon Ducot."),

    /**
     * Tristessa's Tragedy - The Tragedy of Tristessa.
     */
    TRISTESSAS_TRAGEDY(13535, "Tristessa's Tragedy", "The Tragedy of Tristessa."),

    /**
     * The Treachery of Royalty - The Treachery of Royalty, by Professor Answith.
     */
    TREACHERY_OF_ROYALTY(13536, "The Treachery of Royalty", "The Treachery of Royalty, by Professor Answith."),

    /**
     * Transportation Incantations - Unlocks Arceuus teleports when read.
     */
    TRANSPORTATION_INCANTATIONS(13537, "Transportation Incantations", "Transportation Incantations, by Amon Ducot."),

    /**
     * Soul Journey - Reading starts the Bear Your Soul miniquest.
     */
    SOUL_JOURNEY(19637, "Soul Journey", "The Journey of Souls, by Aretha."),

    /**
     * Varlamore Envoy - The Envoy to Varlamore, by Deryk Paulson.
     * Used in The Depths of Despair quest.
     */
    VARLAMORE_ENVOY(21756, "Varlamore Envoy", "The Envoy to Varlamore, by Deryk Paulson.");

    private static final Map<Integer, LibraryBook> BY_ITEM_ID;
    private static final Map<String, LibraryBook> BY_NAME;

    static {
        Map<Integer, LibraryBook> byId = new HashMap<>();
        Map<String, LibraryBook> byName = new HashMap<>();
        for (LibraryBook book : values()) {
            byId.put(book.itemId, book);
            byName.put(book.shortName, book);
        }
        BY_ITEM_ID = Collections.unmodifiableMap(byId);
        BY_NAME = Collections.unmodifiableMap(byName);
    }

    /**
     * The item ID for this book.
     */
    private final int itemId;

    /**
     * Short display name for the book.
     */
    private final String shortName;

    /**
     * Full description/title of the book.
     */
    private final String fullName;

    /**
     * Look up a book by its item ID.
     *
     * @param itemId the item ID
     * @return the book, or null if not found
     */
    public static LibraryBook byItemId(int itemId) {
        return BY_ITEM_ID.get(itemId);
    }

    /**
     * Look up a book by its short name.
     *
     * @param name the book's short name
     * @return the book, or null if not found
     */
    public static LibraryBook byName(String name) {
        return BY_NAME.get(name);
    }

    /**
     * Check if this book has special effects when read.
     * Transportation Incantations unlocks teleports.
     * Soul Journey starts a miniquest.
     *
     * @return true if the book has special effects
     */
    public boolean hasSpecialEffect() {
        return this == TRANSPORTATION_INCANTATIONS || this == SOUL_JOURNEY;
    }

    /**
     * Check if this book is used in a quest.
     *
     * @return true if quest-related
     */
    public boolean isQuestBook() {
        return this == VARLAMORE_ENVOY;
    }
}

