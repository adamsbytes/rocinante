package com.rocinante.combat;

import com.rocinante.progression.PrayerUnlock;
import lombok.Builder;
import lombok.Value;

import javax.annotation.Nullable;

/**
 * Configuration for prayer management during combat.
 *
 * Per REQUIREMENTS.md Section 10.1.3 and 10.2:
 * <ul>
 *   <li>Protection prayer switching based on incoming attacks</li>
 *   <li>Configurable flick modes (perfect, lazy, always-on)</li>
 *   <li>Offensive prayer support</li>
 *   <li>Prayer point restoration</li>
 * </ul>
 */
@Value
@Builder
public class PrayerConfig {

    /**
     * Default configuration - protection prayers with lazy flicking.
     */
    public static final PrayerConfig DEFAULT = PrayerConfig.builder()
            .useProtectionPrayers(true)
            .flickMode(FlickMode.LAZY)
            .missedFlickProbability(0.03)
            .flickTimingVarianceMs(50)
            .useOffensivePrayers(false)
            .offensivePrayer(null)
            .prayerRestoreThreshold(0.20)
            .disableWhenLowPoints(true)
            .lowPointsThreshold(0.10)
            .build();

    /**
     * Configuration for ironman accounts - tick-perfect flicking to conserve prayer.
     */
    public static final PrayerConfig IRONMAN_CONSERVE = PrayerConfig.builder()
            .useProtectionPrayers(true)
            .flickMode(FlickMode.PERFECT)
            .missedFlickProbability(0.05)
            .flickTimingVarianceMs(50)
            .useOffensivePrayers(false)
            .offensivePrayer(null)
            .prayerRestoreThreshold(0.15)
            .disableWhenLowPoints(true)
            .lowPointsThreshold(0.05)
            .build();

    /**
     * Configuration for safe combat - always-on protection.
     */
    public static final PrayerConfig SAFE_MODE = PrayerConfig.builder()
            .useProtectionPrayers(true)
            .flickMode(FlickMode.ALWAYS_ON)
            .missedFlickProbability(0.0)
            .flickTimingVarianceMs(0)
            .useOffensivePrayers(true)
            .offensivePrayer(null) // Will use best available
            .prayerRestoreThreshold(0.30)
            .disableWhenLowPoints(false)
            .lowPointsThreshold(0.0)
            .build();

    // ========================================================================
    // Protection Prayers
    // ========================================================================

    /**
     * Whether to use protection prayers during combat.
     */
    @Builder.Default
    boolean useProtectionPrayers = true;

    /**
     * The flicking mode to use for protection prayers.
     */
    @Builder.Default
    FlickMode flickMode = FlickMode.LAZY;

    /**
     * Probability of missing a prayer flick (humanization).
     * Per Section 10.2.2: 2-5%.
     */
    @Builder.Default
    double missedFlickProbability = 0.03;

    /**
     * Timing variance in milliseconds for flicks (humanization).
     * Per Section 10.2.2: ±50ms variance.
     */
    @Builder.Default
    int flickTimingVarianceMs = 50;

    // ========================================================================
    // Offensive Prayers
    // ========================================================================

    /**
     * Whether to maintain offensive prayers during combat.
     */
    @Builder.Default
    boolean useOffensivePrayers = false;

    /**
     * Specific offensive prayer to use, or null to auto-select best available.
     */
    @Nullable
    PrayerUnlock offensivePrayer;

    // ========================================================================
    // Prayer Point Management
    // ========================================================================

    /**
     * Prayer points threshold for restoration (0.0-1.0).
     * When prayer drops below this, drink a prayer potion.
     */
    @Builder.Default
    double prayerRestoreThreshold = 0.20;

    /**
     * Whether to disable prayers when points are critically low.
     */
    @Builder.Default
    boolean disableWhenLowPoints = true;

    /**
     * Prayer points threshold to disable prayers (0.0-1.0).
     * Per Section 10.1.3: disable if critically low and no restore available.
     */
    @Builder.Default
    double lowPointsThreshold = 0.10;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if flicking is enabled.
     *
     * @return true if using any flicking mode (not always-on)
     */
    public boolean isFlickingEnabled() {
        return flickMode != null && flickMode.isFlicking();
    }

    /**
     * Get the effective miss probability, combining base and config.
     *
     * @return effective miss probability (0.0-1.0)
     */
    public double getEffectiveMissProbability() {
        if (flickMode == null || flickMode.isAlwaysOn()) {
            return 0.0;
        }
        // Combine config override with base mode probability
        return Math.max(missedFlickProbability, flickMode.getBaseMissProbability());
    }

    /**
     * Get the effective timing variance in ticks.
     * 1 tick = 600ms, so 50ms variance = ~0.083 ticks.
     *
     * @return timing variance as fraction of a tick
     */
    public double getTimingVarianceTicks() {
        return flickTimingVarianceMs / 600.0;
    }

    /**
     * Check if offensive prayers should be used.
     *
     * @return true if offensive prayers are enabled
     */
    public boolean shouldUseOffensivePrayers() {
        return useOffensivePrayers;
    }

    /**
     * Check if a specific offensive prayer is configured.
     *
     * @return true if a specific prayer is set (vs auto-select)
     */
    public boolean hasSpecificOffensivePrayer() {
        return offensivePrayer != null;
    }
}

