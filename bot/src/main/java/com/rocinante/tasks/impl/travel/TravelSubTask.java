package com.rocinante.tasks.impl.travel;

import com.rocinante.tasks.TaskContext;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;

/**
 * Interface for specialized travel sub-tasks.
 *
 * <p>Implementations handle specific transport types that require complex interfaces
 * or interactions (fairy rings, spirit trees, charter ships, etc.). Simple teleports
 * remain inline in {@link com.rocinante.tasks.impl.TravelTask}.
 *
 * <p>Sub-tasks are executed by TravelTask when complex transport methods are needed.
 * They can also be used directly by quest handlers for fine-grained control.
 *
 * <p>Example usage (direct):
 * <pre>{@code
 * FairyRingTask fairyRing = FairyRingTask.toCode("AJR");
 * fairyRing.execute(ctx);
 * }</pre>
 *
 * <p>Example usage (via TravelTask):
 * <pre>{@code
 * TravelTask.fairyRing("AJR").execute(ctx);  // Delegates to FairyRingTask internally
 * }</pre>
 */
public interface TravelSubTask {

    /**
     * Execution result status.
     */
    enum Status {
        /** Task is still executing, call tick() again */
        IN_PROGRESS,
        /** Task completed successfully */
        COMPLETED,
        /** Task failed */
        FAILED
    }

    /**
     * Initialize the sub-task. Called once before first tick.
     *
     * @param ctx the task context
     */
    void init(TaskContext ctx);

    /**
     * Execute one tick of the sub-task.
     * Called repeatedly until status is COMPLETED or FAILED.
     *
     * @param ctx the task context
     * @return current status
     */
    Status tick(TaskContext ctx);

    /**
     * Check if this sub-task can be executed given current game state.
     *
     * @param ctx the task context
     * @return true if executable
     */
    boolean canExecute(TaskContext ctx);

    /**
     * Get the expected destination after travel (for verification).
     *
     * @return expected destination, or null if unknown
     */
    @Nullable
    WorldPoint getExpectedDestination();

    /**
     * Set the expected destination for arrival verification.
     *
     * @param destination the expected destination
     */
    void setExpectedDestination(@Nullable WorldPoint destination);

    /**
     * Get a human-readable description of this travel.
     *
     * @return description string
     */
    String getDescription();

    /**
     * Get failure reason if status is FAILED.
     *
     * @return failure reason, or null if not failed
     */
    @Nullable
    String getFailureReason();

    /**
     * Check if this sub-task is currently waiting for an async operation.
     *
     * @return true if waiting
     */
    boolean isWaiting();

    /**
     * Reset the sub-task to initial state for re-use.
     */
    void reset();
}

