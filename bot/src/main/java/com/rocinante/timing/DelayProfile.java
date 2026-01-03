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
     * Distribution: Ex-Gaussian (μ=200ms, σ=30ms, τ=50ms)
     * Bounds: min=150ms, max=600ms
     *
     * Ex-Gaussian closely models real human reaction time distributions:
     * - Gaussian core: most reactions cluster around the mean (μ=200ms)
     * - Exponential tail: occasional slower reactions (τ=50ms adds right skew)
     * - Mean reaction time ≈ μ + τ = 250ms
     * - Right-skewed: fast reactions are bounded, slow ones have fat tail
     *
     * Used when the bot needs to react to something that just happened in the game,
     * such as an NPC appearing, a dialogue opening, or combat starting.
     */
    REACTION(
            DistributionType.EX_GAUSSIAN,
            200.0,  // mu (Gaussian mean)
            30.0,   // sigma (Gaussian std dev)
            50.0,   // tau (exponential mean - creates right skew)
            150L,   // min
            600L,   // max
            "Responding to game events"
    ),

    /**
     * REACTION_EXPECTED - Responding to an anticipated event.
     * Distribution: Ex-Gaussian (μ=150ms, σ=25ms, τ=30ms)
     * Bounds: min=100ms, max=400ms
     *
     * Used when the player is waiting for something to happen:
     * - Dialogue continues to next line
     * - Bank interface opens after clicking booth
     * - Animation completes (you were watching it)
     * 
     * Faster than general REACTION because attention is focused.
     * Mean reaction ≈ 180ms (μ + τ)
     */
    REACTION_EXPECTED(
            DistributionType.EX_GAUSSIAN,
            150.0,  // mu - faster base (expecting it)
            25.0,   // sigma - more consistent
            30.0,   // tau - shorter tail
            100L,   // min
            400L,   // max
            "Responding to anticipated event"
    ),

    /**
     * REACTION_UNEXPECTED - Responding to a surprise event.
     * Distribution: Ex-Gaussian (μ=350ms, σ=60ms, τ=100ms)
     * Bounds: min=250ms, max=800ms
     *
     * Used when something unexpected happens:
     * - Random event appears
     * - PKer logs in (when not expecting PvP)
     * - NPC spawns or de-spawns unexpectedly
     * - Error/failure message appears
     * 
     * Slower because the player needs to:
     * 1. Notice something happened
     * 2. Process what it is
     * 3. Decide how to respond
     * 
     * Mean reaction ≈ 450ms (μ + τ), with fat tail for confusion
     */
    REACTION_UNEXPECTED(
            DistributionType.EX_GAUSSIAN,
            350.0,  // mu - slower base (surprised)
            60.0,   // sigma - more variable
            100.0,  // tau - longer tail (confusion)
            250L,   // min
            800L,   // max
            "Responding to unexpected event"
    ),

    /**
     * REACTION_COMPLEX - Responding when a decision is required.
     * Distribution: Ex-Gaussian (μ=500ms, σ=100ms, τ=200ms)
     * Bounds: min=300ms, max=1500ms
     *
     * Used when the player needs to think before acting:
     * - Choosing which item to use
     * - Selecting from multiple options in a menu
     * - Deciding where to click on the map
     * - GE price decisions
     * - Quest dialogue choices
     * 
     * Slowest reaction type because cognitive processing is needed.
     * Mean reaction ≈ 700ms (μ + τ), with very fat tail for deliberation
     */
    REACTION_COMPLEX(
            DistributionType.EX_GAUSSIAN,
            500.0,  // mu - thinking time
            100.0,  // sigma - highly variable
            200.0,  // tau - long tail (deliberation)
            300L,   // min
            1500L,  // max
            "Responding with decision required"
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
     * The mean (μ) for Gaussian/Uniform/Ex-Gaussian, or lambda (λ) for Poisson/Exponential.
     */
    private final double mean;

    /**
     * The standard deviation (σ) for Gaussian and Ex-Gaussian distributions.
     * Not used for Poisson, Uniform, or Exponential.
     */
    private final double stdDev;

    /**
     * The exponential tail parameter (τ) for Ex-Gaussian distribution.
     * Higher values create heavier right tail (more occasional slow reactions).
     * Only used for EX_GAUSSIAN distribution type.
     */
    private final double tau;

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

    /**
     * Constructor for profiles that don't use tau (most profiles).
     */
    DelayProfile(DistributionType distributionType, double mean, double stdDev,
                 Long min, Long max, String description) {
        this(distributionType, mean, stdDev, 0.0, min, max, description);
    }

    /**
     * Constructor for Ex-Gaussian profiles that need tau parameter.
     */
    DelayProfile(DistributionType distributionType, double mean, double stdDev, double tau,
                 Long min, Long max, String description) {
        this.distributionType = distributionType;
        this.mean = mean;
        this.stdDev = stdDev;
        this.tau = tau;
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

