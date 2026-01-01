package com.rocinante.behavior.tasks;

import com.rocinante.behavior.BreakType;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskPriority;
import lombok.Getter;

import java.time.Duration;

/**
 * Base class for behavioral tasks (breaks, idle behaviors, rituals).
 * 
 * Behavioral tasks:
 * - Are always interruptible by URGENT tasks (emergencies)
 * - Have configurable durations
 * - Report their completion to the BreakScheduler
 * - Use BEHAVIORAL priority (between URGENT and NORMAL)
 * 
 * Important: Subclasses should clear predictive hover in their first-tick
 * initialization (e.g., when `started` flag is false) by calling
 * ctx.getPredictiveHoverManager().clearHover().
 */
public abstract class BehavioralTask extends AbstractTask {

    /**
     * The type of break this task represents.
     */
    @Getter
    protected final BreakType breakType;
    
    /**
     * Target duration for this break.
     */
    @Getter
    protected final Duration targetDuration;

    protected BehavioralTask(BreakType breakType, Duration targetDuration) {
        this.breakType = breakType;
        this.targetDuration = targetDuration;
        this.priority = TaskPriority.BEHAVIORAL;
    }

    /**
     * Behavioral tasks can be executed as long as the player is logged in.
     * They don't have complex preconditions.
     */
    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.isLoggedIn();
    }

    /**
     * Behavioral tasks can always be interrupted by higher priority tasks.
     */
    @Override
    public boolean isInterruptible() {
        return true;
    }

    /**
     * Get a readable description of this behavioral task.
     */
    @Override
    public String getDescription() {
        return String.format("%s (%ds)", breakType.getDisplayName(), targetDuration.toSeconds());
    }
}

