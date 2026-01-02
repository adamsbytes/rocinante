package com.rocinante.tasks.impl.skills.firemaking;

import com.rocinante.util.ItemCollections;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.ItemID;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Configuration for firemaking training.
 *
 * <p>Supports three modes of operation:
 * <ul>
 *   <li><b>Fixed line mode</b>: Traditional firemaking at a fixed start position</li>
 *   <li><b>Location-based</b>: Firemaking at the GE or other popular spots</li>
 *   <li><b>Dynamic burn mode</b>: Burns from current position (for cut-and-burn)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Simple oak logs training (defaults to no start position - dynamic mode)
 * FiremakingConfig config = FiremakingConfig.builder()
 *     .logItemId(ItemID.OAK_LOGS)
 *     .targetLevel(45)
 *     .build();
 *
 * // GE training with banking (fixed start position)
 * FiremakingConfig geConfig = FiremakingConfig.builder()
 *     .logItemId(ItemID.WILLOW_LOGS)
 *     .startPosition(FiremakingConfig.GE_START_POINT)
 *     .bankForLogs(true)
 *     .targetLevel(60)
 *     .build();
 *
 * // Dynamic burn mode for cut-and-burn (no start position)
 * FiremakingConfig burnHere = FiremakingConfig.builder()
 *     .logItemId(ItemID.WILLOW_LOGS)
 *     .startPosition(null)  // Dynamic mode - burn wherever we are
 *     .burnHereSearchRadius(15)
 *     .burnHereWalkThreshold(20)
 *     .targetLogsBurned(27)
 *     .bankForLogs(false)
 *     .build();
 * }</pre>
 *
 * @see FiremakingSkillTask
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
     * 
     * <p>If null, operates in "burn here" mode - burns dynamically from
     * current player position. This is ideal for cut-and-burn workflows.
     */
    @Nullable
    WorldPoint startPosition;

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
    // Dynamic Burn Mode Configuration
    // ========================================================================

    /**
     * Search radius for finding burn locations in burn-here mode.
     * Only used when startPosition is null (dynamic burn mode).
     * Tiles are searched within this radius of the current position.
     */
    @Builder.Default
    int burnHereSearchRadius = 10;

    /**
     * Maximum path cost (in tiles) to walk to a burn spot.
     * If no spot is available within this cost, a new line will be started nearby.
     * Only used when startPosition is null (dynamic burn mode).
     */
    @Builder.Default
    int burnHereWalkThreshold = 15;

    /**
     * Anchor point to constrain dynamic burn locations.
     * When set, burn locations must be within {@link #maxDistanceFromAnchor} of this point.
     * Prevents drifting too far from training area during dynamic mode.
     * Only used when startPosition is null (dynamic burn mode).
     */
    @Nullable
    WorldPoint anchorPoint;

    /**
     * Maximum distance from anchor point for burn locations.
     * Only used when anchorPoint is set in dynamic burn mode.
     */
    @Builder.Default
    int maxDistanceFromAnchor = 20;

    // ========================================================================
    // Banking Configuration
    // ========================================================================

    /**
     * Whether to bank for more logs when inventory is empty.
     * When true, the nearest bank will be automatically found.
     */
    @Builder.Default
    boolean bankForLogs = true;

    // ========================================================================
    // Tinderbox Configuration
    // ========================================================================

    /**
     * Acceptable tinderbox item IDs.
     * Defaults to all tinderboxes (regular and golden).
     */
    @Builder.Default
    List<Integer> tinderboxItemIds = ItemCollections.TINDERBOXES;

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

    /**
     * Check if operating in dynamic burn mode (burn here).
     * Dynamic mode burns from current position rather than a fixed location.
     *
     * @return true if startPosition is null (dynamic mode)
     */
    public boolean isDynamicBurnMode() {
        return startPosition == null;
    }
}

