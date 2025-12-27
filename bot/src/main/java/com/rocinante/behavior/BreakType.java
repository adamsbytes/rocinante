package com.rocinante.behavior;

import lombok.Getter;

import java.time.Duration;

/**
 * Types of breaks that can be scheduled during gameplay.
 * 
 * Per REQUIREMENTS.md Section 4.3:
 * - Micro-pause: 2-8 seconds every 30-90 actions (30% chance)
 * - Short break: 30-180 seconds every 15-40 minutes (60% chance)  
 * - Long break: 5-20 minutes every 60-120 minutes (80% chance)
 * - Session end: After 2-6 hours (100% - mandatory)
 * 
 * Break timing inherits exponential distribution to avoid predictable patterns.
 */
@Getter
public enum BreakType {
    
    /**
     * Very short pause - mouse stops, maybe small drift.
     * Simulates momentary distraction (phone buzz, thinking, etc.)
     */
    MICRO_PAUSE(
            Duration.ofSeconds(2), Duration.ofSeconds(8),    // Duration range
            30, 90,                                          // Action interval
            0.30,                                            // Probability
            false,                                           // No logout
            "Micro-pause"
    ),
    
    /**
     * Short break - may open tabs, check stats, idle camera.
     * Simulates quick bio break, checking phone, etc.
     */
    SHORT_BREAK(
            Duration.ofSeconds(30), Duration.ofMinutes(3),   // Duration range
            15 * 60, 40 * 60,                                // Time interval (seconds)
            0.60,                                            // Probability
            false,                                           // Usually no logout
            "Short break"
    ),
    
    /**
     * Long break - extended AFK, may logout.
     * Simulates meal, phone call, other activity.
     */
    LONG_BREAK(
            Duration.ofMinutes(5), Duration.ofMinutes(20),   // Duration range
            60 * 60, 120 * 60,                               // Time interval (seconds)
            0.80,                                            // Probability
            true,                                            // May logout
            "Long break"
    ),
    
    /**
     * Session end - mandatory logout after long play session.
     * Simulates end of play session.
     */
    SESSION_END(
            Duration.ZERO, Duration.ZERO,                    // N/A - logout
            2 * 60 * 60, 6 * 60 * 60,                        // Session length (seconds)
            1.00,                                            // Always happens
            true,                                            // Mandatory logout
            "Session end"
    );
    
    /**
     * Minimum duration of this break type.
     */
    private final Duration minDuration;
    
    /**
     * Maximum duration of this break type.
     */
    private final Duration maxDuration;
    
    /**
     * Minimum interval before this break can trigger.
     * For MICRO_PAUSE: action count
     * For others: seconds since last break of this type
     */
    private final int minInterval;
    
    /**
     * Maximum interval before this break should trigger.
     */
    private final int maxInterval;
    
    /**
     * Probability of taking this break when interval is reached.
     */
    private final double probability;
    
    /**
     * Whether this break type may include logging out.
     */
    private final boolean mayLogout;
    
    /**
     * Human-readable name for logging.
     */
    private final String displayName;
    
    BreakType(Duration minDuration, Duration maxDuration, 
              int minInterval, int maxInterval,
              double probability, boolean mayLogout, String displayName) {
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;
        this.minInterval = minInterval;
        this.maxInterval = maxInterval;
        this.probability = probability;
        this.mayLogout = mayLogout;
        this.displayName = displayName;
    }
    
    /**
     * Check if this break type uses action count (vs time) for scheduling.
     * 
     * @return true if action-based (MICRO_PAUSE)
     */
    public boolean isActionBased() {
        return this == MICRO_PAUSE;
    }
    
    /**
     * Check if this is a mandatory break (SESSION_END).
     * 
     * @return true if break cannot be skipped
     */
    public boolean isMandatory() {
        return probability >= 1.0;
    }
    
    /**
     * Get the average duration of this break type.
     * 
     * @return average duration
     */
    public Duration getAverageDuration() {
        if (this == SESSION_END) {
            return Duration.ZERO; // Logout, no break duration
        }
        long avgSeconds = (minDuration.toSeconds() + maxDuration.toSeconds()) / 2;
        return Duration.ofSeconds(avgSeconds);
    }
    
    /**
     * Get a description of the trigger condition for this break.
     * 
     * @return trigger description
     */
    public String getTriggerDescription() {
        if (isActionBased()) {
            return String.format("Every %d-%d actions (%.0f%% chance)", 
                    minInterval, maxInterval, probability * 100);
        } else if (this == SESSION_END) {
            return String.format("After %d-%d hours", 
                    minInterval / 3600, maxInterval / 3600);
        } else {
            return String.format("Every %d-%d minutes (%.0f%% chance)",
                    minInterval / 60, maxInterval / 60, probability * 100);
        }
    }
}

