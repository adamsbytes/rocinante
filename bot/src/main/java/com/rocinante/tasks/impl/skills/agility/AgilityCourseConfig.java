package com.rocinante.tasks.impl.skills.agility;

import com.rocinante.agility.AgilityCourse;
import lombok.Builder;
import lombok.Value;

import java.time.Duration;

/**
 * Configuration for agility course training.
 *
 * <p>This class configures how the {@link AgilityCourseTask} should run:
 * training targets, mark of grace handling, and safety options.
 *
 * <p>Example usage:
 * <pre>{@code
 * AgilityCourseConfig config = AgilityCourseConfig.builder()
 *     .course(courseRepo.getCourseById("draynor_rooftop").get())
 *     .targetLevel(40)
 *     .pickupMarksOfGrace(true)
 *     .build();
 * }</pre>
 *
 * @see AgilityCourseTask
 * @see AgilityCourse
 */
@Value
@Builder
public class AgilityCourseConfig {

    // ========================================================================
    // Required Configuration
    // ========================================================================

    /**
     * The agility course to train on.
     */
    AgilityCourse course;

    // ========================================================================
    // Target Configuration
    // ========================================================================

    /**
     * Target Agility level to reach.
     * Training stops when this level is reached.
     * -1 means no level target (use other stopping conditions).
     */
    @Builder.Default
    int targetLevel = -1;

    /**
     * Target XP to gain.
     * Training stops when this much XP has been gained from the starting point.
     * -1 means no XP target.
     */
    @Builder.Default
    long targetXp = -1;

    /**
     * Target number of laps to complete.
     * Training stops after this many laps.
     * -1 means no lap target.
     */
    @Builder.Default
    int targetLaps = -1;

    /**
     * Maximum duration for training.
     * Training stops after this duration.
     * Null means no time limit.
     */
    Duration maxDuration;

    // ========================================================================
    // Mark of Grace Configuration
    // ========================================================================

    /**
     * Whether to pick up marks of grace.
     * Default is true - most players want marks.
     */
    @Builder.Default
    boolean pickupMarksOfGrace = true;

    /**
     * Maximum distance to travel to pick up a mark (in tiles).
     * Marks further than this are ignored.
     */
    @Builder.Default
    int maxMarkPickupDistance = 20;

    /**
     * Target number of marks to collect.
     * Training stops after collecting this many marks.
     * -1 means no mark target.
     */
    @Builder.Default
    int targetMarks = -1;

    // ========================================================================
    // Safety Configuration
    // ========================================================================

    /**
     * Whether to stop training if the player falls (fails an obstacle).
     * Useful for low-level accounts or dangerous courses.
     */
    @Builder.Default
    boolean stopOnFailure = false;

    /**
     * Minimum HP to maintain before pausing to eat/heal.
     * Training pauses if HP drops below this.
     * -1 means no HP check.
     */
    @Builder.Default
    int minHealthThreshold = -1;

    /**
     * Food item IDs to eat when HP is low.
     * Empty means no food (must stop if HP too low).
     */
    @Builder.Default
    int[] foodItemIds = new int[0];

    // ========================================================================
    // Behavior Configuration
    // ========================================================================

    /**
     * Whether to use the course shortcut if available and level permits.
     * Some courses (like Seers) have shortcuts that improve XP/hr.
     */
    @Builder.Default
    boolean useShortcut = true;

    /**
     * Whether to hop worlds if another player is using the course.
     * Can reduce competition for marks.
     */
    @Builder.Default
    boolean worldHopIfCrowded = false;

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if a target level is set.
     *
     * @return true if targetLevel > 0
     */
    public boolean hasTargetLevel() {
        return targetLevel > 0;
    }

    /**
     * Check if a target XP is set.
     *
     * @return true if targetXp > 0
     */
    public boolean hasTargetXp() {
        return targetXp > 0;
    }

    /**
     * Check if a target lap count is set.
     *
     * @return true if targetLaps > 0
     */
    public boolean hasTargetLaps() {
        return targetLaps > 0;
    }

    /**
     * Check if a max duration is set.
     *
     * @return true if maxDuration is set and positive
     */
    public boolean hasMaxDuration() {
        return maxDuration != null && !maxDuration.isZero() && !maxDuration.isNegative();
    }

    /**
     * Check if a target mark count is set.
     *
     * @return true if targetMarks > 0
     */
    public boolean hasTargetMarks() {
        return targetMarks > 0;
    }

    /**
     * Check if HP monitoring is enabled.
     *
     * @return true if minHealthThreshold > 0
     */
    public boolean hasHealthMonitoring() {
        return minHealthThreshold > 0;
    }

    /**
     * Check if any stopping condition is set.
     *
     * @return true if at least one target or limit is configured
     */
    public boolean hasStoppingCondition() {
        return hasTargetLevel() || hasTargetXp() || hasTargetLaps() || 
               hasMaxDuration() || hasTargetMarks();
    }

    /**
     * Get a summary string for logging.
     *
     * @return human-readable summary
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(course.getName());

        if (hasTargetLevel()) {
            sb.append(" to lvl ").append(targetLevel);
        }
        if (hasTargetLaps()) {
            sb.append(" (").append(targetLaps).append(" laps)");
        }
        if (hasMaxDuration()) {
            sb.append(" max ").append(maxDuration);
        }
        if (pickupMarksOfGrace) {
            sb.append(" +marks");
        }

        return sb.toString();
    }

    /**
     * Validate the configuration.
     *
     * @throws IllegalStateException if configuration is invalid
     */
    public void validate() {
        if (course == null) {
            throw new IllegalStateException("Course must be specified");
        }
        if (!hasStoppingCondition()) {
            throw new IllegalStateException("At least one stopping condition must be set");
        }
    }
}

