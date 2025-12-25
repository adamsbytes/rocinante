package com.rocinante.timing;

/**
 * Distribution types for delay generation as specified in REQUIREMENTS.md Section 4.1.1.
 *
 * Each distribution type has different characteristics suited for specific use cases:
 * - GAUSSIAN: Bell curve distribution, ideal for most delays where values cluster around a mean.
 * - POISSON: Event-based distribution, ideal for reaction times and event-triggered actions.
 * - UNIFORM: Flat distribution within bounds, ideal for bounded randomization.
 * - EXPONENTIAL: Right-skewed distribution, ideal for break durations and rare events.
 */
public enum DistributionType {

    /**
     * Gaussian (Normal) distribution.
     * Values cluster around the mean with standard deviation controlling spread.
     * Use case: Most action delays where behavior should be "average" with natural variation.
     */
    GAUSSIAN,

    /**
     * Poisson distribution.
     * Models the number of events in a fixed time interval.
     * Use case: Reaction times - responding to game events (λ=250ms typical).
     */
    POISSON,

    /**
     * Uniform distribution.
     * Equal probability across the entire range [min, max].
     * Use case: Bounded randomization where any value is equally likely.
     */
    UNIFORM,

    /**
     * Exponential distribution.
     * Right-skewed with most values near minimum, occasional large values.
     * Use case: Break durations, time between rare events.
     */
    EXPONENTIAL
}

