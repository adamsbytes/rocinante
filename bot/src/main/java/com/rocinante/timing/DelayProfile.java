package com.rocinante.timing;

import lombok.Getter;

/**
 * Named delay profiles as specified in REQUIREMENTS.md Section 4.1.2.
 *
 * Each profile defines:
 * - Distribution type (Gaussian, Poisson, Uniform, Exponential)
 * - Mean/lambda value for the distribution
 * - Standard deviation (for Gaussian)
 * - Optional minimum and maximum bounds
 * - Description of the use case
 *
 * Profiles are designed to produce humanized delays that match real player behavior patterns.
 */
@Getter
public enum DelayProfile {

    /**
     * REACTION - Responding to game events.
     * Distribution: Poisson (λ=250ms)
     * Bounds: min=150ms, max=600ms
     *
     * Used when the bot needs to react to something that just happened in the game,
     * such as an NPC appearing, a dialogue opening, or combat starting.
     */
    REACTION(
            DistributionType.POISSON,
            250.0,  // lambda
            0.0,    // stdDev (not used for Poisson)
            150L,   // min
            600L,   // max
            "Responding to game events"
    ),

    /**
     * ACTION_GAP - Delay between routine actions.
     * Distribution: Gaussian (μ=800ms, σ=200ms)
     * Bounds: min=400ms, max=2000ms
     *
     * Used between sequential actions that are part of routine gameplay,
     * such as clicking inventory items, selecting menu options, etc.
     */
    ACTION_GAP(
            DistributionType.GAUSSIAN,
            800.0,  // mean
            200.0,  // stdDev
            400L,   // min
            2000L,  // max
            "Between routine actions"
    ),

    /**
     * MENU_SELECT - Choosing a menu option after right-click.
     * Distribution: Gaussian (μ=180ms, σ=50ms)
     * Bounds: none (natural Gaussian bounds)
     *
     * Used when selecting an option from a right-click context menu.
     * This is a quick, deliberate action.
     */
    MENU_SELECT(
            DistributionType.GAUSSIAN,
            180.0,  // mean
            50.0,   // stdDev
            null,   // min (unbounded, Gaussian natural)
            null,   // max (unbounded, Gaussian natural)
            "Choosing menu option after right-click"
    ),

    /**
     * DIALOGUE_READ - Reading NPC dialogue.
     * Distribution: Gaussian (μ=1200ms base + 50ms/word, σ=300ms)
     * Bounds: none
     *
     * Base delay for reading dialogue. The actual delay should be calculated as:
     * delay = base + (wordCount * 50ms)
     * Use HumanTimer.getDialogueDelay(wordCount) for proper calculation.
     */
    DIALOGUE_READ(
            DistributionType.GAUSSIAN,
            1200.0, // base mean (add 50ms per word)
            300.0,  // stdDev
            null,   // min
            null,   // max
            "Reading NPC dialogue"
    ),

    /**
     * INVENTORY_SCAN - Finding an item in inventory.
     * Distribution: Gaussian (μ=400ms, σ=100ms)
     * Bounds: none
     *
     * Time to visually locate and move to an inventory item.
     * Assumes player roughly knows where items are.
     */
    INVENTORY_SCAN(
            DistributionType.GAUSSIAN,
            400.0,  // mean
            100.0,  // stdDev
            null,   // min
            null,   // max
            "Finding item in inventory"
    ),

    /**
     * BANK_SEARCH - Locating an item in bank.
     * Distribution: Gaussian (μ=600ms, σ=150ms)
     * Bounds: none
     *
     * Time to locate an item in the bank interface.
     * Slightly longer than inventory due to larger search area.
     */
    BANK_SEARCH(
            DistributionType.GAUSSIAN,
            600.0,  // mean
            150.0,  // stdDev
            null,   // min
            null,   // max
            "Locating item in bank"
    ),

    /**
     * PRAYER_SWITCH - Prayer flicking.
     * Distribution: Gaussian (μ=80ms, σ=20ms)
     * Bounds: min=50ms
     *
     * Very fast switching for prayer flicking during combat.
     * Minimum bound ensures humanly possible reaction time.
     */
    PRAYER_SWITCH(
            DistributionType.GAUSSIAN,
            80.0,   // mean
            20.0,   // stdDev
            50L,    // min
            null,   // max
            "Prayer flicking"
    ),

    /**
     * GEAR_SWITCH - Equipment swaps.
     * Distribution: Gaussian (μ=120ms, σ=30ms)
     * Bounds: none
     *
     * Time between individual equipment swaps during gear switching.
     * Fast but slightly slower than prayer switching.
     */
    GEAR_SWITCH(
            DistributionType.GAUSSIAN,
            120.0,  // mean
            30.0,   // stdDev
            null,   // min
            null,   // max
            "Equipment swaps"
    );

    /**
     * The type of probability distribution to use for generating delays.
     */
    private final DistributionType distributionType;

    /**
     * The mean (μ) for Gaussian/Uniform, or lambda (λ) for Poisson/Exponential.
     */
    private final double mean;

    /**
     * The standard deviation (σ) for Gaussian distribution.
     * Not used for Poisson, Uniform, or Exponential.
     */
    private final double stdDev;

    /**
     * Optional minimum bound for the delay (inclusive).
     * Null means no minimum bound (use natural distribution).
     */
    private final Long min;

    /**
     * Optional maximum bound for the delay (inclusive).
     * Null means no maximum bound (use natural distribution).
     */
    private final Long max;

    /**
     * Human-readable description of the use case.
     */
    private final String description;

    /**
     * Per-word delay addition for DIALOGUE_READ profile.
     * Total dialogue delay = mean + (wordCount * DIALOGUE_MS_PER_WORD)
     */
    public static final double DIALOGUE_MS_PER_WORD = 50.0;

    DelayProfile(DistributionType distributionType, double mean, double stdDev,
                 Long min, Long max, String description) {
        this.distributionType = distributionType;
        this.mean = mean;
        this.stdDev = stdDev;
        this.min = min;
        this.max = max;
        this.description = description;
    }

    /**
     * Check if this profile has a minimum bound.
     *
     * @return true if min is not null
     */
    public boolean hasMin() {
        return min != null;
    }

    /**
     * Check if this profile has a maximum bound.
     *
     * @return true if max is not null
     */
    public boolean hasMax() {
        return max != null;
    }

    /**
     * Get the minimum bound, defaulting to 0 if not set.
     *
     * @return the minimum bound or 0
     */
    public long getMinOrDefault() {
        return min != null ? min : 0L;
    }

    /**
     * Get the maximum bound, defaulting to Long.MAX_VALUE if not set.
     *
     * @return the maximum bound or Long.MAX_VALUE
     */
    public long getMaxOrDefault() {
        return max != null ? max : Long.MAX_VALUE;
    }

    /**
     * Calculate the adjusted mean for DIALOGUE_READ based on word count.
     *
     * @param wordCount the number of words in the dialogue
     * @return the adjusted mean including per-word delay
     */
    public double getAdjustedMeanForDialogue(int wordCount) {
        if (this != DIALOGUE_READ) {
            return mean;
        }
        return mean + (wordCount * DIALOGUE_MS_PER_WORD);
    }
}

