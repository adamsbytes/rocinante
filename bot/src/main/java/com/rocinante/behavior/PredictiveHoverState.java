package com.rocinante.behavior;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

import java.time.Instant;

/**
 * Immutable state tracking for predictive hovering during gathering and combat activities.
 * 
 * <p>This class captures the current state of a predictive hover operation, including:
 * <ul>
 *   <li>Target identification (position, ID, type)</li>
 *   <li>Timing information (when hover started)</li>
 *   <li>Planned click behavior (determined at hover start based on engagement)</li>
 *   <li>Validation tracking for moving targets (NPCs like fishing spots, combat targets)</li>
 * </ul>
 * 
 * <p>The predictive hover system allows the bot to hover over the next target while the current
 * action is completing, mimicking engaged human behavior. For example:
 * <ul>
 *   <li>While cutting a tree, hover over the next tree</li>
 *   <li>While mining a rock, hover over the next rock</li>
 *   <li>While fishing at one spot, hover over the next fishing spot</li>
 *   <li>While killing an NPC, hover over the next combat target</li>
 * </ul>
 * 
 * <p>Instances are immutable and created via the builder pattern. State updates
 * are performed by creating new instances with modified values.
 */
@Value
@Builder(toBuilder = true)
public class PredictiveHoverState {

    /**
     * The world position of the target being hovered.
     * For multi-tile objects, this is the south-west corner tile.
     */
    WorldPoint targetPosition;

    /**
     * The definition ID of the target (ObjectID or NpcID).
     * Used for validation to ensure the target hasn't been replaced.
     */
    int targetId;

    /**
     * Whether this target is an NPC (true) or a game object (false).
     * NPCs require continuous validation as they can move or despawn.
     * Objects are typically static but can be depleted.
     */
    boolean isNpc;

    /**
     * The NPC index if this is an NPC target, -1 otherwise.
     * Used for precise NPC tracking across ticks.
     */
    @Builder.Default
    int npcIndex = -1;

    /**
     * The menu action to use when clicking (e.g., "Chop down", "Mine", "Net").
     * Stored at hover time to ensure consistent action when click occurs.
     */
    String menuAction;

    /**
     * When the hover operation started.
     * Used for timing calculations and metrics.
     */
    Instant hoverStartTime;

    /**
     * The planned click behavior when the current action completes.
     * Determined at hover start based on attention state, fatigue, and profile.
     * May be re-rolled if engagement state changes significantly.
     */
    ClickBehavior plannedBehavior;

    /**
     * Whether this hover target has been validated since the last game tick.
     * For NPCs, validation checks that the target still exists at the expected position.
     * Reset to false each tick, set to true after successful validation.
     */
    @Builder.Default
    boolean validatedThisTick = false;

    /**
     * Count of consecutive ticks this hover has been valid.
     * Used for metrics and to detect "stable" hovers vs "chasing" hovers.
     */
    @Builder.Default
    int consecutiveValidTicks = 0;

    /**
     * Count of times this hover has been re-acquired after target movement.
     * High re-acquisition counts indicate an unstable target (e.g., moving fishing spot).
     */
    @Builder.Default
    int reacquisitionCount = 0;

    /**
     * The original hover position before any re-acquisitions.
     * Used to calculate drift distance for metrics.
     */
    WorldPoint originalHoverPosition;

    /**
     * The player position when hover started.
     * Used for distance calculations and validation.
     */
    WorldPoint playerPositionAtHoverStart;

    /**
     * The precision of this hover - whether it landed exactly on target or missed.
     * Imprecise hovers require micro-correction when action starts.
     */
    @Builder.Default
    HoverPrecision hoverPrecision = HoverPrecision.PRECISE;

    /**
     * Offset from target center for imprecise hovers (in pixels).
     * Zero for precise hovers.
     */
    @Builder.Default
    int impreciseOffsetX = 0;

    /**
     * Offset from target center for imprecise hovers (in pixels).
     * Zero for precise hovers.
     */
    @Builder.Default
    int impreciseOffsetY = 0;

    /**
     * Defines how precisely the hover landed on the target.
     */
    public enum HoverPrecision {
        /**
         * Hover landed exactly on target. No correction needed.
         */
        PRECISE,

        /**
         * Hover landed near target but slightly off. Requires micro-correction.
         * This is realistic - humans don't always aim perfectly.
         */
        IMPRECISE,

        /**
         * Hover landed on empty space near target. Requires full re-targeting.
         * Represents distracted or sloppy hovering.
         */
        MISSED_EMPTY_SPACE,

        /**
         * Hover landed on a different nearby object. Requires correction.
         * Represents attention lapse or confusion with similar objects.
         */
        WRONG_TARGET
    }

    /**
     * Defines the click behavior when the current action completes.
     */
    public enum ClickBehavior {
        /**
         * Click immediately when the current action completes.
         * Represents an engaged, focused player who anticipates the action ending.
         * Most common when FOCUSED with low fatigue.
         */
        INSTANT,

        /**
         * Add a small hesitation delay (100-400ms) before clicking.
         * Represents a player who was hovering but took a moment to notice
         * the action completed.
         */
        DELAYED,

        /**
         * Abandon the hover without clicking.
         * Represents a player who got distracted or changed their mind.
         * The normal task cycle will find a new target.
         */
        ABANDON
    }

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if this is a valid hover state with all required fields.
     *
     * @return true if the hover state is valid
     */
    public boolean isValid() {
        return targetPosition != null 
            && targetId > 0 
            && hoverStartTime != null 
            && plannedBehavior != null;
    }

    /**
     * Check if the target is likely to move (NPCs can move, objects typically don't).
     * 
     * @return true if the target may move and requires validation
     */
    public boolean requiresValidation() {
        return isNpc;
    }

    /**
     * Get the duration this hover has been active.
     *
     * @return duration since hover started
     */
    public java.time.Duration getHoverDuration() {
        if (hoverStartTime == null) {
            return java.time.Duration.ZERO;
        }
        return java.time.Duration.between(hoverStartTime, Instant.now());
    }

    /**
     * Get the hover duration in milliseconds.
     *
     * @return milliseconds since hover started
     */
    public long getHoverDurationMs() {
        return getHoverDuration().toMillis();
    }

    /**
     * Check if this hover should be considered for instant click.
     * Only INSTANT behavior hovers that are validated should instant-click.
     *
     * @return true if instant click is appropriate
     */
    public boolean shouldInstantClick() {
        return plannedBehavior == ClickBehavior.INSTANT && (!isNpc || validatedThisTick);
    }

    /**
     * Check if this hover should be delayed before clicking.
     *
     * @return true if delayed click is appropriate
     */
    public boolean shouldDelayedClick() {
        return plannedBehavior == ClickBehavior.DELAYED && (!isNpc || validatedThisTick);
    }

    /**
     * Check if this hover requires micro-correction before clicking.
     * Imprecise hovers need the mouse to move slightly to hit the target.
     *
     * @return true if micro-correction is needed
     */
    public boolean needsMicroCorrection() {
        return hoverPrecision == HoverPrecision.IMPRECISE;
    }

    /**
     * Check if this hover completely missed the target.
     * Missed hovers need full re-targeting.
     *
     * @return true if hover missed entirely
     */
    public boolean missedTarget() {
        return hoverPrecision == HoverPrecision.MISSED_EMPTY_SPACE 
            || hoverPrecision == HoverPrecision.WRONG_TARGET;
    }

    /**
     * Check if this hover landed on target (precise or imprecise but on target).
     *
     * @return true if hover is usable with at most micro-correction
     */
    public boolean isUsableHover() {
        return hoverPrecision == HoverPrecision.PRECISE 
            || hoverPrecision == HoverPrecision.IMPRECISE;
    }

    /**
     * Check if this hover should be abandoned.
     *
     * @return true if the hover should be abandoned
     */
    public boolean shouldAbandon() {
        return plannedBehavior == ClickBehavior.ABANDON;
    }

    /**
     * Calculate the distance the hover has drifted from its original position
     * due to re-acquisitions.
     *
     * @return drift distance in tiles, or 0 if no drift
     */
    public int getDriftDistance() {
        if (originalHoverPosition == null || targetPosition == null) {
            return 0;
        }
        return originalHoverPosition.distanceTo(targetPosition);
    }

    /**
     * Check if this hover has experienced significant drift (multiple re-acquisitions
     * or large distance change).
     *
     * @return true if the hover is considered unstable
     */
    public boolean isUnstable() {
        return reacquisitionCount >= 3 || getDriftDistance() >= 5;
    }

    /**
     * Create a new state with updated validation status.
     *
     * @param validated whether validation succeeded
     * @return new state with updated validation
     */
    public PredictiveHoverState withValidation(boolean validated) {
        return this.toBuilder()
                .validatedThisTick(validated)
                .consecutiveValidTicks(validated ? consecutiveValidTicks + 1 : 0)
                .build();
    }

    /**
     * Create a new state after re-acquiring a moved target.
     *
     * @param newPosition the new target position
     * @param newNpcIndex the new NPC index (or -1 for objects)
     * @return new state with updated position and re-acquisition count
     */
    public PredictiveHoverState withReacquisition(WorldPoint newPosition, int newNpcIndex) {
        return this.toBuilder()
                .targetPosition(newPosition)
                .npcIndex(newNpcIndex)
                .validatedThisTick(true)
                .consecutiveValidTicks(1)
                .reacquisitionCount(reacquisitionCount + 1)
                .build();
    }

    /**
     * Create a new state with updated planned behavior.
     * Used when re-rolling behavior due to significant engagement change.
     *
     * @param newBehavior the new planned click behavior
     * @return new state with updated behavior
     */
    public PredictiveHoverState withBehavior(ClickBehavior newBehavior) {
        return this.toBuilder()
                .plannedBehavior(newBehavior)
                .build();
    }

    /**
     * Get a summary string for logging.
     *
     * @return human-readable summary of this hover state
     */
    public String getSummary() {
        return String.format("Hover[%s id=%d at %s, behavior=%s, valid=%s, reacq=%d, duration=%dms]",
                isNpc ? "NPC" : "Object",
                targetId,
                targetPosition,
                plannedBehavior,
                validatedThisTick,
                reacquisitionCount,
                getHoverDurationMs());
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

