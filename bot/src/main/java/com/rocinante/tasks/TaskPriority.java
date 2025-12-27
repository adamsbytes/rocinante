package com.rocinante.tasks;

/**
 * Priority levels for task queue ordering.
 * Higher priority tasks are executed before lower priority tasks.
 *
 * Per REQUIREMENTS.md Section 5.5:
 * - URGENT interrupts current task (for combat reactions, death handling, emergencies)
 * - BEHAVIORAL pauses current task for breaks and attention lapses
 * - NORMAL is the default priority for most tasks
 * - LOW is for background/optional tasks
 */
public enum TaskPriority {

    /**
     * Highest priority - interrupts current task immediately.
     * Use for: combat reactions, death handling, emergency escapes, poison cures.
     */
    URGENT(0),

    /**
     * Behavioral priority - pauses current task temporarily.
     * Use for: breaks, attention lapses, session rituals.
     * These can be interrupted by URGENT tasks but not NORMAL.
     */
    BEHAVIORAL(1),

    /**
     * Normal priority - default for most tasks.
     * Use for: quest steps, skilling actions, routine gameplay.
     */
    NORMAL(2),

    /**
     * Low priority - executed when nothing else is queued.
     * Use for: idle behaviors, optional optimizations, background tasks.
     */
    LOW(3);

    private final int ordinalValue;

    TaskPriority(int ordinalValue) {
        this.ordinalValue = ordinalValue;
    }

    /**
     * Get the ordinal value for priority comparison.
     * Lower values = higher priority.
     *
     * @return the ordinal value
     */
    public int getOrdinalValue() {
        return ordinalValue;
    }

    /**
     * Check if this priority is higher than another.
     *
     * @param other the other priority to compare
     * @return true if this priority is higher (more urgent)
     */
    public boolean isHigherThan(TaskPriority other) {
        return this.ordinalValue < other.ordinalValue;
    }

    /**
     * Check if this priority should interrupt the current task.
     *
     * @return true if this is an interrupting priority
     */
    public boolean shouldInterrupt() {
        return this == URGENT;
    }
}

