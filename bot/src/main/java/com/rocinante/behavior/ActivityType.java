package com.rocinante.behavior;

import lombok.Getter;

/**
 * Classification of bot activities by intensity level.
 * 
 * Intensity affects:
 * - Fatigue accumulation rate (multiplier applied to base rates)
 * - Interruptibility (whether behavioral tasks can interrupt)
 * 
 * Per REQUIREMENTS.md Section 4.2 and Phase 5 behavioral anti-detection.
 */
@Getter
public enum ActivityType {
    
    /**
     * Critical activities that should never be interrupted.
     * Examples: Boss fights, Wilderness PvM, Inferno, Raids
     * 
     * High fatigue accumulation due to intense focus required.
     */
    CRITICAL(1.5, false, "Critical activity requiring full attention"),
    
    /**
     * High-intensity activities that should only be interrupted after completion.
     * Examples: Normal combat, Slayer tasks, dangerous NPCs
     * 
     * Above-average fatigue accumulation.
     */
    HIGH(1.2, false, "High-intensity activity"),
    
    /**
     * Medium-intensity activities that can be interrupted after current action.
     * Examples: Questing, banking, walking, general gameplay
     * 
     * Standard fatigue accumulation rate.
     */
    MEDIUM(1.0, true, "Standard gameplay activity"),
    
    /**
     * Low-intensity AFK-style activities that can be freely interrupted.
     * Examples: Woodcutting, fishing, mining (non-tick manipulation)
     * 
     * Reduced fatigue accumulation - these are relaxing activities.
     */
    LOW(0.7, true, "Low-intensity AFK activity"),
    
    /**
     * Explicit AFK combat - safe combat situations like rock crabs, NMZ.
     * Must be explicitly flagged by the task.
     * 
     * Same fatigue as LOW but in a combat context.
     */
    AFK_COMBAT(0.7, true, "Safe AFK combat"),
    
    /**
     * Idle/break state - standing around, on break, waiting.
     * 
     * Minimal fatigue accumulation.
     */
    IDLE(0.3, true, "Idle or on break");
    
    /**
     * Multiplier applied to base fatigue accumulation rates.
     * Higher values = faster fatigue buildup.
     */
    private final double fatigueMultiplier;
    
    /**
     * Whether behavioral tasks (breaks, attention lapses) can interrupt
     * activities of this type.
     */
    private final boolean interruptible;
    
    /**
     * Human-readable description of this activity type.
     */
    private final String description;
    
    ActivityType(double fatigueMultiplier, boolean interruptible, String description) {
        this.fatigueMultiplier = fatigueMultiplier;
        this.interruptible = interruptible;
        this.description = description;
    }
    
    /**
     * Check if this activity type represents any form of combat.
     * 
     * @return true if combat-related
     */
    public boolean isCombat() {
        return this == CRITICAL || this == HIGH || this == AFK_COMBAT;
    }
    
    /**
     * Check if this activity type is high-stakes (CRITICAL or HIGH).
     * Used for HCIM special handling.
     * 
     * @return true if high-stakes activity
     */
    public boolean isHighStakes() {
        return this == CRITICAL || this == HIGH;
    }
    
    /**
     * Check if this is an AFK-style activity (LOW or AFK_COMBAT).
     * 
     * @return true if AFK-style
     */
    public boolean isAfkStyle() {
        return this == LOW || this == AFK_COMBAT || this == IDLE;
    }
}

