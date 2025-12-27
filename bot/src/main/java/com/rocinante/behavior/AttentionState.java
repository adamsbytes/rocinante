package com.rocinante.behavior;

import lombok.Getter;

/**
 * Player attention states for simulating human focus patterns.
 * 
 * Per REQUIREMENTS.md Section 4.4:
 * - FOCUSED (70% of active time): Normal delays, high precision
 * - DISTRACTED (25% of active time): 1.3x delays, miss events with lag
 * - AFK (5% of active time): 3-15 second unresponsive periods
 * 
 * Transitions occur every 30-180 seconds with weighted probabilities.
 */
@Getter
public enum AttentionState {
    
    /**
     * Player is fully focused on the game.
     * Normal operation with standard delays and precision.
     * 
     * Distribution: ~70% of active time
     */
    FOCUSED(1.0, 1.0, 0.70, "Fully focused on game"),
    
    /**
     * Player is somewhat distracted (phone, other tab, etc.).
     * Increased delays, reduced precision, may miss game events.
     * 
     * Distribution: ~25% of active time
     */
    DISTRACTED(1.3, 0.9, 0.25, "Partially distracted"),
    
    /**
     * Player has looked away from the game entirely.
     * Unresponsive for 3-15 seconds.
     * 
     * Distribution: ~5% of active time (excluding scheduled breaks)
     */
    AFK(0.0, 0.0, 0.05, "Away from keyboard");
    
    /**
     * Multiplier applied to all delays.
     * 0.0 for AFK means no actions are taken.
     */
    private final double delayMultiplier;
    
    /**
     * Multiplier applied to click/action precision.
     * Lower values mean less precise (more variance).
     * 0.0 for AFK is unused since no actions occur.
     */
    private final double precisionMultiplier;
    
    /**
     * Base probability weight for this state during normal gameplay.
     * Used for state transition calculations.
     */
    private final double baseWeight;
    
    /**
     * Human-readable description of this attention state.
     */
    private final String description;
    
    AttentionState(double delayMultiplier, double precisionMultiplier, 
                   double baseWeight, String description) {
        this.delayMultiplier = delayMultiplier;
        this.precisionMultiplier = precisionMultiplier;
        this.baseWeight = baseWeight;
        this.description = description;
    }
    
    /**
     * Check if actions should be performed in this state.
     * 
     * @return true if the player can take actions
     */
    public boolean canAct() {
        return this != AFK;
    }
    
    /**
     * Check if this state represents any form of distraction.
     * 
     * @return true if DISTRACTED or AFK
     */
    public boolean isDistracted() {
        return this == DISTRACTED || this == AFK;
    }
    
    /**
     * Get the event processing lag for this state.
     * When DISTRACTED, there's extra delay (200-800ms) before
     * responding to game events.
     * 
     * @return base lag in milliseconds (actual value randomized around this)
     */
    public long getEventProcessingLagMs() {
        return switch (this) {
            case FOCUSED -> 0;
            case DISTRACTED -> 500; // Base, will be randomized 200-800ms
            case AFK -> 0; // N/A, no actions taken
        };
    }
}

