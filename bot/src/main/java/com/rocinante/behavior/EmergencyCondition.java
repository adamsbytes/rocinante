package com.rocinante.behavior;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;

/**
 * Interface for conditions that trigger emergency interruptions.
 * 
 * Emergencies can interrupt ANY task, including behavioral tasks,
 * and are queued with TaskPriority.URGENT.
 * 
 * Examples:
 * - Poison/venom ticking when health is low
 * - Health dropped below flee threshold
 * - Player being attacked (for certain account types)
 */
public interface EmergencyCondition {

    /**
     * Check if this emergency condition is currently triggered.
     * 
     * @param ctx task context with access to game state
     * @return true if emergency is active
     */
    boolean isTriggered(TaskContext ctx);

    /**
     * Create a task to respond to this emergency.
     * 
     * @param ctx task context
     * @return task to handle the emergency
     */
    Task createResponseTask(TaskContext ctx);

    /**
     * Get a human-readable description of this emergency.
     * 
     * @return description string
     */
    String getDescription();

    /**
     * Get the unique identifier for this emergency type.
     * Used to prevent duplicate emergency tasks.
     * 
     * @return unique ID string
     */
    default String getId() {
        return getClass().getSimpleName();
    }

    /**
     * Get the cooldown before this emergency can trigger again.
     * Prevents spam-triggering of the same emergency.
     * 
     * @return cooldown in milliseconds
     */
    default long getCooldownMs() {
        return 5000; // 5 second default cooldown
    }
    
    /**
     * Get the severity/priority of this emergency.
     * Higher values = more severe = higher priority.
     * Used to determine which emergency to handle first when multiple trigger.
     * 
     * @return severity level (0-100, default 50)
     */
    default int getSeverity() {
        return 50; // Default medium severity
    }
}

