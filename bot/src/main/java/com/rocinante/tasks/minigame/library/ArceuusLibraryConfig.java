package com.rocinante.tasks.minigame.library;

import com.rocinante.tasks.minigame.MinigameConfig;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import java.util.List;

/**
 * Configuration for Arceuus Library minigame training.
 *
 * <p>Extends the base {@link MinigameConfig} with Library-specific options:
 * <ul>
 *   <li>Target skill (Magic or Runecraft)</li>
 *   <li>Customer preference</li>
 *   <li>Book collection behavior</li>
 * </ul>
 *
 * <p>XP rewards scale with level:
 * <ul>
 *   <li>Magic: 15 × Magic level per book</li>
 *   <li>Runecraft: 5 × Runecraft level per book</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * ArceuusLibraryConfig config = ArceuusLibraryConfig.forMagic(55);
 * ArceuusLibraryTask task = new ArceuusLibraryTask(config);
 * }</pre>
 *
 * @see ArceuusLibraryTask
 */
@Data
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ArceuusLibraryConfig extends MinigameConfig {

    // ========================================================================
    // Library Constants
    // ========================================================================

    /**
     * Arceuus Library region ID.
     */
    public static final int LIBRARY_REGION = 6459;

    /**
     * Book of Arcane Knowledge item ID (reward for delivering books).
     */
    public static final int BOOK_OF_ARCANE_KNOWLEDGE_ID = 13513;

    /**
     * Animation ID for searching a bookcase.
     */
    public static final int SEARCH_BOOKCASE_ANIMATION = 832;

    /**
     * XP multiplier for Magic training (15 × level).
     */
    public static final double MAGIC_XP_MULTIPLIER = 15.0;

    /**
     * XP multiplier for Runecraft training (5 × level).
     */
    public static final double RUNECRAFT_XP_MULTIPLIER = 5.0;

    /**
     * Estimated books delivered per hour with efficient pathing.
     */
    public static final int BOOKS_PER_HOUR = 45;

    /**
     * Entry point for the library (center ground floor).
     */
    public static final WorldPoint LIBRARY_CENTER = new WorldPoint(1632, 3807, 0);

    // ========================================================================
    // Strategy Configuration
    // ========================================================================

    /**
     * The skill to train (MAGIC or RUNECRAFT).
     */
    @Builder.Default
    Skill targetSkill = Skill.MAGIC;

    /**
     * Preferred customer to speak to.
     * If null, uses nearest available customer.
     */
    LibraryCustomer preferredCustomer;

    /**
     * Whether to collect extra copies of books for future requests.
     * This can speed up subsequent deliveries if the same book is requested again.
     */
    @Builder.Default
    boolean collectExtraBooks = false;

    /**
     * Maximum books to hold in inventory (when collectExtraBooks is true).
     * More books = faster future deliveries, but less inventory space.
     */
    @Builder.Default
    int maxBooksInInventory = 5;

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a configuration for Magic training.
     *
     * @param targetLevel the Magic level to reach
     * @return configured ArceuusLibraryConfig
     */
    public static ArceuusLibraryConfig forMagic(int targetLevel) {
        return ArceuusLibraryConfig.builder()
                .minigameId("arceuus_library")
                .minigameName("Arceuus Library")
                .regionIds(List.of(LIBRARY_REGION))
                .targetLevel(targetLevel)
                .targetSkill(Skill.MAGIC)
                .bringFood(false)
                .build();
    }

    /**
     * Create a configuration for Runecraft training.
     *
     * @param targetLevel the Runecraft level to reach
     * @return configured ArceuusLibraryConfig
     */
    public static ArceuusLibraryConfig forRunecraft(int targetLevel) {
        return ArceuusLibraryConfig.builder()
                .minigameId("arceuus_library")
                .minigameName("Arceuus Library")
                .regionIds(List.of(LIBRARY_REGION))
                .targetLevel(targetLevel)
                .targetSkill(Skill.RUNECRAFT)
                .bringFood(false)
                .build();
    }

    /**
     * Create a configuration for training to a specific XP amount.
     *
     * @param skill    the skill to train (MAGIC or RUNECRAFT)
     * @param targetXp the XP to reach
     * @return configured ArceuusLibraryConfig
     */
    public static ArceuusLibraryConfig forXp(Skill skill, long targetXp) {
        return ArceuusLibraryConfig.builder()
                .minigameId("arceuus_library")
                .minigameName("Arceuus Library")
                .regionIds(List.of(LIBRARY_REGION))
                .targetXp(targetXp)
                .targetSkill(skill)
                .bringFood(false)
                .build();
    }

    /**
     * Create a configuration for delivering a specific number of books.
     *
     * @param skill       the skill to train (MAGIC or RUNECRAFT)
     * @param targetBooks the number of books to deliver
     * @return configured ArceuusLibraryConfig
     */
    public static ArceuusLibraryConfig forBooks(Skill skill, int targetBooks) {
        return ArceuusLibraryConfig.builder()
                .minigameId("arceuus_library")
                .minigameName("Arceuus Library")
                .regionIds(List.of(LIBRARY_REGION))
                .targetRounds(targetBooks)
                .targetSkill(skill)
                .bringFood(false)
                .build();
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Get the XP multiplier for the target skill.
     *
     * @return 15.0 for Magic, 5.0 for Runecraft
     */
    public double getXpMultiplier() {
        return targetSkill == Skill.MAGIC ? MAGIC_XP_MULTIPLIER : RUNECRAFT_XP_MULTIPLIER;
    }

    /**
     * Calculate XP per book at a given level.
     *
     * @param level the player's level in the target skill
     * @return XP gained per book delivered
     */
    public double getXpPerBook(int level) {
        return level * getXpMultiplier();
    }

    /**
     * Calculate estimated XP per hour at a given level.
     *
     * @param level the player's level in the target skill
     * @return estimated XP per hour
     */
    public double getXpPerHour(int level) {
        return getXpPerBook(level) * BOOKS_PER_HOUR;
    }

    /**
     * Check if training Magic.
     *
     * @return true if target skill is Magic
     */
    public boolean isTrainingMagic() {
        return targetSkill == Skill.MAGIC;
    }

    /**
     * Check if training Runecraft.
     *
     * @return true if target skill is Runecraft
     */
    public boolean isTrainingRunecraft() {
        return targetSkill == Skill.RUNECRAFT;
    }
}

