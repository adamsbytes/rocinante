package com.rocinante.tasks.impl;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.ItemID;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.util.Map;

/**
 * Configuration for firemaking training.
 *
 * <p>Supports both line-based firemaking (traditional) and location-based
 * firemaking at the Grand Exchange or other popular spots.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Simple oak logs training
 * FiremakingConfig config = FiremakingConfig.builder()
 *     .logItemId(ItemID.OAK_LOGS)
 *     .targetLevel(45)
 *     .build();
 *
 * // GE training with banking
 * FiremakingConfig geConfig = FiremakingConfig.builder()
 *     .logItemId(ItemID.WILLOW_LOGS)
 *     .startPosition(FiremakingConfig.GE_START_POINT)
 *     .bankForLogs(true)
 *     .targetLevel(60)
 *     .build();
 * }</pre>
 *
 * @see FiremakingTask
 */
@Value
@Builder(toBuilder = true)
public class FiremakingConfig {

    // ========================================================================
    // Common Start Locations
    // ========================================================================

    /**
     * Grand Exchange popular firemaking spot (north-east corner).
     */
    public static final WorldPoint GE_START_POINT = new WorldPoint(3165, 3487, 0);

    /**
     * Varrock West Bank firemaking spot.
     */
    public static final WorldPoint VARROCK_WEST_START = new WorldPoint(3185, 3436, 0);

    // ========================================================================
    // Log Types with XP Values
    // ========================================================================

    /**
     * XP values for different log types.
     */
    public static final Map<Integer, Double> LOG_XP = Map.ofEntries(
            Map.entry(ItemID.LOGS, 40.0),
            Map.entry(ItemID.ACHEY_TREE_LOGS, 40.0),
            Map.entry(ItemID.OAK_LOGS, 60.0),
            Map.entry(ItemID.WILLOW_LOGS, 90.0),
            Map.entry(ItemID.TEAK_LOGS, 105.0),
            Map.entry(ItemID.ARCTIC_PINE_LOGS, 125.0),
            Map.entry(ItemID.MAPLE_LOGS, 135.0),
            Map.entry(ItemID.MAHOGANY_LOGS, 157.5),
            Map.entry(ItemID.YEW_LOGS, 202.5),
            Map.entry(ItemID.BLISTERWOOD_LOGS, 96.0),
            Map.entry(ItemID.MAGIC_LOGS, 303.8),
            Map.entry(ItemID.REDWOOD_LOGS, 350.0)
    );

    /**
     * Required firemaking levels for different log types.
     */
    public static final Map<Integer, Integer> LOG_LEVELS = Map.ofEntries(
            Map.entry(ItemID.LOGS, 1),
            Map.entry(ItemID.ACHEY_TREE_LOGS, 1),
            Map.entry(ItemID.OAK_LOGS, 15),
            Map.entry(ItemID.WILLOW_LOGS, 30),
            Map.entry(ItemID.TEAK_LOGS, 35),
            Map.entry(ItemID.ARCTIC_PINE_LOGS, 42),
            Map.entry(ItemID.MAPLE_LOGS, 45),
            Map.entry(ItemID.MAHOGANY_LOGS, 50),
            Map.entry(ItemID.YEW_LOGS, 60),
            Map.entry(ItemID.BLISTERWOOD_LOGS, 62),
            Map.entry(ItemID.MAGIC_LOGS, 75),
            Map.entry(ItemID.REDWOOD_LOGS, 90)
    );

    // ========================================================================
    // Required Configuration
    // ========================================================================

    /**
     * The type of logs to burn.
     */
    int logItemId;

    // ========================================================================
    // Target Goals (at least one required)
    // ========================================================================

    /**
     * Target firemaking level.
     * Training stops when this level is reached.
     * Set to -1 to ignore.
     */
    @Builder.Default
    int targetLevel = -1;

    /**
     * Target XP to reach.
     * Set to -1 to ignore.
     */
    @Builder.Default
    long targetXp = -1;

    /**
     * Target number of logs to burn.
     * Set to -1 for unlimited.
     */
    @Builder.Default
    int targetLogsBurned = -1;

    /**
     * Maximum session duration.
     * Set to null for no limit.
     */
    Duration maxDuration;

    // ========================================================================
    // Location Configuration
    // ========================================================================

    /**
     * Starting position for firemaking line.
     * Should be the east end of a clear line.
     * Defaults to GE if not specified.
     */
    @Builder.Default
    WorldPoint startPosition = GE_START_POINT;

    /**
     * Direction to move when making fires.
     * True = West (default OSRS behavior), False = East.
     */
    @Builder.Default
    boolean moveWest = true;

    /**
     * Minimum clear tiles needed for a fire line.
     * Used when finding new line start positions.
     */
    @Builder.Default
    int minLineTiles = 10;

    // ========================================================================
    // Banking Configuration
    // ========================================================================

    /**
     * Whether to bank for more logs when inventory is empty.
     */
    @Builder.Default
    boolean bankForLogs = true;

    /**
     * Bank location ID (from web.json) or null to auto-detect nearest.
     */
    String bankLocationId;

    // ========================================================================
    // Tinderbox Configuration
    // ========================================================================

    /**
     * Tinderbox item ID to use.
     * Default is regular tinderbox; can be set to infernal axe combo.
     */
    @Builder.Default
    int tinderboxItemId = ItemID.TINDERBOX;

    // ========================================================================
    // Behavior Configuration
    // ========================================================================

    /**
     * Whether to wait for fire animation to complete before next action.
     */
    @Builder.Default
    boolean waitForFireAnimation = true;

    /**
     * Maximum ticks to wait for fire lighting before retrying.
     */
    @Builder.Default
    int lightingTimeoutTicks = 10;

    /**
     * Whether to randomly vary starting position within valid range.
     * Adds humanization to line starts.
     */
    @Builder.Default
    boolean randomizeStartPosition = true;

    // ========================================================================
    // Validation
    // ========================================================================

    /**
     * Validate that this configuration is valid.
     *
     * @throws IllegalStateException if invalid
     */
    public void validate() {
        if (logItemId <= 0) {
            throw new IllegalStateException("Log item ID must be specified");
        }
        if (targetLevel < 0 && targetXp < 0 && targetLogsBurned < 0 && maxDuration == null) {
            throw new IllegalStateException("At least one target goal must be specified");
        }
    }

    /**
     * Get the XP per log burned for the configured log type.
     *
     * @return XP per log, or 0 if unknown
     */
    public double getXpPerLog() {
        return LOG_XP.getOrDefault(logItemId, 0.0);
    }

    /**
     * Get the required firemaking level for the configured log type.
     *
     * @return required level, or 1 if unknown
     */
    public int getRequiredLevel() {
        return LOG_LEVELS.getOrDefault(logItemId, 1);
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
     * Check if there's a logs burned target.
     */
    public boolean hasLogsBurnedTarget() {
        return targetLogsBurned > 0;
    }

    /**
     * Check if there's a time limit.
     */
    public boolean hasTimeLimit() {
        return maxDuration != null && !maxDuration.isZero();
    }
}

