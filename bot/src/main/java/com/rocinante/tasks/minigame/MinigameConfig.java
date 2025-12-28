package com.rocinante.tasks.minigame;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.util.List;

/**
 * Base configuration for minigame tasks.
 *
 * <p>Provides common configuration options shared across all minigames:
 * <ul>
 *   <li>Target goals (level, XP, rounds, duration)</li>
 *   <li>Entry location and requirements</li>
 *   <li>Food/supply settings</li>
 *   <li>Exit conditions</li>
 * </ul>
 *
 * <p>Specific minigames extend this with their own configuration options.
 *
 * @see MinigameTask
 */
@Data
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode
public class MinigameConfig {

    // ========================================================================
    // Minigame Identification
    // ========================================================================

    /**
     * Unique identifier for this minigame (e.g., "wintertodt", "tempoross").
     */
    String minigameId;

    /**
     * Human-readable name for logging/UI.
     */
    String minigameName;

    /**
     * Region ID(s) where the minigame takes place.
     * Used for isInMinigameArea() detection.
     */
    @Builder.Default
    List<Integer> regionIds = List.of();

    // ========================================================================
    // Target Goals
    // ========================================================================

    /**
     * Target skill level to achieve.
     * Training stops when this level is reached.
     * Set to -1 to ignore.
     */
    @Builder.Default
    int targetLevel = -1;

    /**
     * Target XP amount to achieve.
     * Set to -1 to ignore.
     */
    @Builder.Default
    long targetXp = -1;

    /**
     * Target number of rounds/games to complete.
     * Set to -1 for unlimited.
     */
    @Builder.Default
    int targetRounds = -1;

    /**
     * Maximum duration for this training session.
     * Set to null for no time limit.
     */
    Duration maxDuration;

    // ========================================================================
    // Entry Configuration
    // ========================================================================

    /**
     * Location to travel to for entering the minigame.
     * Can be a web.json node ID or exact WorldPoint.
     */
    String entryLocationId;

    /**
     * Exact world point for minigame entry.
     */
    WorldPoint entryPoint;

    /**
     * Object ID to interact with for entry (portal, boat, etc.).
     * -1 if no object interaction needed.
     */
    @Builder.Default
    int entryObjectId = -1;

    /**
     * NPC ID to interact with for entry.
     * -1 if no NPC interaction needed.
     */
    @Builder.Default
    int entryNpcId = -1;

    /**
     * Menu action for entry interaction.
     */
    String entryMenuAction;

    // ========================================================================
    // Food/Supply Configuration
    // ========================================================================

    /**
     * Whether to bring food to the minigame.
     */
    @Builder.Default
    boolean bringFood = true;

    /**
     * Preferred food item ID.
     * -1 to auto-select best available.
     */
    @Builder.Default
    int preferredFoodId = -1;

    /**
     * Number of food items to bring.
     */
    @Builder.Default
    int foodCount = 10;

    /**
     * HP percentage threshold to eat food.
     * Uses FoodManager for actual eating logic.
     */
    @Builder.Default
    double eatThreshold = 0.5;

    /**
     * Whether to bank for supplies when running low.
     */
    @Builder.Default
    boolean resupplyEnabled = true;

    // ========================================================================
    // Exit Conditions
    // ========================================================================

    /**
     * Minimum food count before exiting to resupply.
     */
    @Builder.Default
    int minFoodBeforeExit = 2;

    /**
     * Whether to exit after completing target rounds.
     */
    @Builder.Default
    boolean exitOnTargetReached = true;

    // ========================================================================
    // Validation
    // ========================================================================

    /**
     * Validate that this configuration is valid.
     *
     * @throws IllegalStateException if configuration is invalid
     */
    public void validate() {
        if (minigameId == null || minigameId.isEmpty()) {
            throw new IllegalStateException("Minigame ID must be specified");
        }
        if (regionIds == null || regionIds.isEmpty()) {
            throw new IllegalStateException("At least one region ID must be specified");
        }
        if (targetLevel < 0 && targetXp < 0 && targetRounds < 0 && maxDuration == null) {
            throw new IllegalStateException("At least one target goal must be specified");
        }
    }

    /**
     * Check if there's a level target.
     */
    public boolean hasLevelTarget() {
        return targetLevel > 0;
    }

    /**
     * Check if there's an XP target.
     */
    public boolean hasXpTarget() {
        return targetXp > 0;
    }

    /**
     * Check if there's a rounds target.
     */
    public boolean hasRoundsTarget() {
        return targetRounds > 0;
    }

    /**
     * Check if there's a time limit.
     */
    public boolean hasTimeLimit() {
        return maxDuration != null && !maxDuration.isZero();
    }
}

