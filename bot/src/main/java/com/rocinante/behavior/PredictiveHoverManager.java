package com.rocinante.behavior;

import com.rocinante.input.ClickPointCalculator;
import com.rocinante.input.MouseCameraCoupler;
import com.rocinante.input.RobotMouseController;
import com.rocinante.state.GameObjectSnapshot;
import com.rocinante.state.NpcSnapshot;
import com.rocinante.state.WorldState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.timing.DelayProfile;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.GameObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Central coordinator for predictive hovering behavior during gathering and combat activities.
 * 
 * <p>The predictive hover system allows the bot to hover over the next target while the current
 * action is completing, mimicking engaged human behavior. This significantly improves
 * human-likeness for activities like:
 * <ul>
 *   <li>Woodcutting - hover next tree while current one falls</li>
 *   <li>Mining - hover next rock while current one depletes</li>
 *   <li>Fishing - hover next spot while current one moves (with validation)</li>
 *   <li>Combat - hover next target while current one dies</li>
 * </ul>
 * 
 * <h3>Decision Logic</h3>
 * The system uses a multi-factor approach to determine hover behavior:
 * <ol>
 *   <li><b>Base Rate</b>: From PlayerProfile (40-95%), represents this player's natural tendency</li>
 *   <li><b>Fatigue Modifier</b>: Reduces prediction rate as fatigue increases (up to -50%)</li>
 *   <li><b>Attention Modifier</b>: DISTRACTED reduces by 60%, AFK disables entirely</li>
 * </ol>
 * 
 * <h3>Click Behavior Distribution</h3>
 * When hovering succeeds and the action completes, the click behavior is determined by:
 * <ul>
 *   <li>PlayerProfile's click speed bias (0=hesitant, 1=snappy)</li>
 *   <li>Current attention state (FOCUSED vs DISTRACTED)</li>
 * </ul>
 * 
 * <h3>Target Validation</h3>
 * For moving targets (NPCs like fishing spots), the system continuously validates:
 * <ul>
 *   <li>Target still exists at expected position</li>
 *   <li>If moved, attempts re-acquisition of nearest valid target</li>
 *   <li>Tracks re-acquisition count and drift distance</li>
 *   <li>Abandons hover if target becomes unstable (3+ re-acquisitions or 5+ tile drift)</li>
 * </ul>
 * 
 * @see PredictiveHoverState
 * @see PlayerProfile#getBasePredictionRate()
 * @see FatigueModel#getSigmaMultiplier()
 * @see AttentionModel#getCurrentState()
 */
@Slf4j
@Singleton
public class PredictiveHoverManager {

    // ========================================================================
    // Configuration Constants
    // ========================================================================

    /**
     * Minimum effective prediction rate after all modifiers.
     * Prevents prediction from being completely disabled by fatigue.
     */
    private static final double MIN_EFFECTIVE_PREDICTION_RATE = 0.10;

    /**
     * Maximum effective prediction rate after all modifiers.
     */
    private static final double MAX_EFFECTIVE_PREDICTION_RATE = 0.95;

    /**
     * Fatigue impact factor on prediction rate.
     * At max fatigue (1.0), prediction rate is reduced by this percentage.
     */
    private static final double FATIGUE_IMPACT_FACTOR = 0.50;

    /**
     * Attention impact when DISTRACTED.
     * Multiplies the effective prediction rate by this factor.
     */
    private static final double DISTRACTED_MULTIPLIER = 0.40;

    /**
     * Minimum hesitation delay in milliseconds for DELAYED behavior.
     */
    private static final long MIN_HESITATION_DELAY_MS = 100;

    /**
     * Maximum hesitation delay in milliseconds for DELAYED behavior.
     */
    private static final long MAX_HESITATION_DELAY_MS = 400;

    /**
     * Maximum distance (tiles) from player for target consideration.
     */
    private static final int MAX_TARGET_DISTANCE = 15;

    /**
     * Maximum number of re-acquisitions before abandoning hover.
     */
    private static final int MAX_REACQUISITIONS = 3;

    /**
     * Maximum drift distance (tiles) before abandoning hover.
     */
    private static final int MAX_DRIFT_DISTANCE = 5;

    // ========================================================================
    // Imperfect Hover Constants (Human Realism)
    // ========================================================================
    // Real humans don't always hover perfectly on target. They:
    // - Sometimes miss slightly (need micro-correction on action)
    // - Occasionally hover over the wrong thing
    // - Sometimes hover over empty space near target
    // - Have cursor drift while waiting

    /**
     * Probability of an imprecise hover (lands near but not on target).
     * Requires micro-correction when action starts.
     */
    private static final double IMPRECISE_HOVER_PROBABILITY = 0.15;

    /**
     * Maximum offset (pixels) for imprecise hover.
     * The mouse lands this far from the intended point.
     */
    private static final int IMPRECISE_HOVER_MAX_OFFSET_PX = 40;

    /**
     * Minimum offset (pixels) for imprecise hover.
     * Must be far enough to actually miss the target.
     */
    private static final int IMPRECISE_HOVER_MIN_OFFSET_PX = 15;

    /**
     * Probability of hovering over empty space near target.
     * More likely when distracted or fatigued.
     */
    private static final double EMPTY_SPACE_HOVER_PROBABILITY = 0.08;

    /**
     * Maximum distance (pixels) from target center for empty space hover.
     */
    private static final int EMPTY_SPACE_HOVER_RADIUS_PX = 80;

    /**
     * Probability of hovering over a different nearby object (wrong target).
     * More likely when there are multiple similar objects nearby.
     */
    private static final double WRONG_TARGET_HOVER_PROBABILITY = 0.05;

    /**
     * Probability per tick of cursor drifting while hovering.
     * Simulates natural hand movement during wait.
     */
    private static final double HOVER_DRIFT_PROBABILITY_PER_TICK = 0.03;

    /**
     * Maximum drift per tick in pixels.
     */
    private static final int HOVER_DRIFT_MAX_PX = 3;

    // ========================================================================
    // Click Behavior Distribution Constants
    // ========================================================================

    // Base distributions for FOCUSED state (adjusted by click speed bias)
    private static final double FOCUSED_INSTANT_BASE = 0.50;
    private static final double FOCUSED_DELAYED_BASE = 0.35;
    private static final double FOCUSED_ABANDON_BASE = 0.15;

    // Distributions for DISTRACTED state (less affected by speed bias)
    private static final double DISTRACTED_INSTANT_BASE = 0.25;
    private static final double DISTRACTED_DELAYED_BASE = 0.40;
    private static final double DISTRACTED_ABANDON_BASE = 0.35;

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final PlayerProfile playerProfile;
    private final FatigueModel fatigueModel;
    private final AttentionModel attentionModel;
    private final Randomization randomization;
    private final com.rocinante.navigation.NavigationService navigationService;

    // ========================================================================
    // State
    // ========================================================================

    /**
     * The current hover state, or null if not hovering.
     * Uses AtomicReference for thread-safe access from game thread and mouse thread.
     */
    private final AtomicReference<PredictiveHoverState> currentHover = new AtomicReference<>(null);

    /**
     * Timestamp of last hover attempt (success or failure).
     * Used to prevent spamming hover attempts.
     */
    private volatile Instant lastHoverAttempt = Instant.EPOCH;

    /**
     * Minimum time between hover attempts (milliseconds).
     */
    private static final long MIN_HOVER_ATTEMPT_INTERVAL_MS = 600;

    /**
     * Maximum hover staleness before auto-clearing (milliseconds).
     * If a hover has been active for longer than this, it's considered stale
     * and will be automatically cleared on the next validation.
     */
    private static final long MAX_HOVER_STALENESS_MS = 60_000; // 1 minute

    /**
     * Metrics: Total hover attempts this session.
     */
    private final java.util.concurrent.atomic.AtomicLong hoverAttemptsTotal = new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Metrics: Successful hovers this session.
     */
    private final java.util.concurrent.atomic.AtomicLong hoverSuccessCount = new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Metrics: Instant clicks executed this session.
     */
    private final java.util.concurrent.atomic.AtomicLong instantClickCount = new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Metrics: Delayed clicks executed this session.
     */
    private final java.util.concurrent.atomic.AtomicLong delayedClickCount = new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Metrics: Abandoned hovers this session.
     */
    private final java.util.concurrent.atomic.AtomicLong abandonedHoverCount = new java.util.concurrent.atomic.AtomicLong(0);

    // Getters for metrics
    public long getHoverAttemptsTotal() { return hoverAttemptsTotal.get(); }
    public long getHoverSuccessCount() { return hoverSuccessCount.get(); }
    public long getInstantClickCount() { return instantClickCount.get(); }
    public long getDelayedClickCount() { return delayedClickCount.get(); }
    public long getAbandonedHoverCount() { return abandonedHoverCount.get(); }

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public PredictiveHoverManager(
            PlayerProfile playerProfile,
            FatigueModel fatigueModel,
            AttentionModel attentionModel,
            Randomization randomization,
            com.rocinante.navigation.NavigationService navigationService) {
        this.playerProfile = playerProfile;
        this.fatigueModel = fatigueModel;
        this.attentionModel = attentionModel;
        this.randomization = randomization;
        this.navigationService = navigationService;
        log.info("PredictiveHoverManager initialized");
    }

    /**
     * Constructor for testing with specific initial state.
     */
    public PredictiveHoverManager(
            PlayerProfile playerProfile,
            FatigueModel fatigueModel,
            AttentionModel attentionModel,
            Randomization randomization,
            com.rocinante.navigation.NavigationService navigationService,
            @Nullable PredictiveHoverState initialState) {
        this(playerProfile, fatigueModel, attentionModel, randomization, navigationService);
        if (initialState != null) {
            currentHover.set(initialState);
        }
    }

    // ========================================================================
    // Public API - State Queries
    // ========================================================================

    /**
     * Check if there is currently an active predictive hover.
     *
     * @return true if hovering over a predicted next target
     */
    public boolean hasPendingHover() {
        PredictiveHoverState state = currentHover.get();
        return state != null && state.isValid();
    }

    /**
     * Get the current hover state, if any.
     *
     * @return the current hover state, or null if not hovering
     */
    @Nullable
    public PredictiveHoverState getCurrentHover() {
        return currentHover.get();
    }

    /**
     * Clear the current hover state.
     * Called when the hover should be abandoned (e.g., player moved, context changed).
     */
    public void clearHover() {
        PredictiveHoverState old = currentHover.getAndSet(null);
        if (old != null) {
            log.debug("Cleared predictive hover: {}", old.getSummary());
        }
    }

    /**
     * Check if the current hover should suppress idle behaviors.
     * 
     * <p>When predictive hovering is active, idle behaviors (mouse drift, rest position)
     * should be suppressed to maintain the hover. This method returns true if:
     * <ul>
     *   <li>There is an active hover</li>
     *   <li>The hover is valid (not abandoned)</li>
     *   <li>The hover was recently validated (within last 2 seconds)</li>
     * </ul>
     *
     * @return true if idle behaviors should be suppressed
     */
    public boolean shouldSuppressIdleBehavior() {
        PredictiveHoverState state = currentHover.get();
        if (state == null || !state.isValid()) {
            return false;
        }

        // Only suppress if hover started recently (active hovering)
        long hoverAge = state.getHoverDurationMs();
        return hoverAge < 30_000; // 30 seconds max - after that, allow idle
    }

    /**
     * Called when camera rotation starts.
     * If actively hovering, we need to prepare for screen position changes.
     */
    public void onCameraRotationStart() {
        PredictiveHoverState state = currentHover.get();
        if (state != null) {
            log.debug("Camera rotation started while hovering - will re-validate screen position");
            // Mark as needing re-validation
            currentHover.set(state.withValidation(false));
        }
    }

    /**
     * Called when significant camera rotation completes.
     * Triggers re-hover to correct for screen position drift.
     */
    public void onCameraRotationComplete(TaskContext ctx) {
        PredictiveHoverState state = currentHover.get();
        if (state != null && state.isValid()) {
            reHoverTarget(ctx, state);
        }
    }

    // ========================================================================
    // Public API - Hover Decision
    // ========================================================================

    /**
     * Determine if predictive hovering should occur based on current conditions.
     * 
     * <p>Takes into account:
     * <ul>
     *   <li>Player profile base prediction rate</li>
     *   <li>Current fatigue level (reduces rate)</li>
     *   <li>Current attention state (DISTRACTED reduces, AFK disables)</li>
     * </ul>
     *
     * @return true if the bot should attempt predictive hovering
     */
    public boolean shouldPredictiveHover() {
        // Never hover during AFK
        AttentionState attention = attentionModel.getCurrentState();
        if (attention == AttentionState.AFK) {
            log.debug("Skipping prediction: AFK state");
            return false;
        }

        // Check if we already have a valid hover
        if (hasPendingHover()) {
            log.debug("Skipping prediction: already hovering");
            return false;
        }

        // Rate limit hover attempts
        if (Instant.now().toEpochMilli() - lastHoverAttempt.toEpochMilli() < MIN_HOVER_ATTEMPT_INTERVAL_MS) {
            log.debug("Skipping prediction: rate limited");
            return false;
        }

        // Calculate effective prediction rate
        double effectiveRate = calculateEffectivePredictionRate();

        // Roll for prediction
        boolean shouldPredict = randomization.chance(effectiveRate);
        
        if (shouldPredict) {
            log.debug("Prediction roll succeeded (effective rate: {}%)",
                    String.format("%.3f", effectiveRate * 100));
        }

        return shouldPredict;
    }

    /**
     * Calculate the effective prediction rate based on all modifiers.
     *
     * @return effective rate between MIN and MAX bounds
     */
    public double calculateEffectivePredictionRate() {
        double baseRate = playerProfile.getBasePredictionRate();
        double fatigueLevel = fatigueModel.getFatigueLevel();
        AttentionState attention = attentionModel.getCurrentState();

        // Apply fatigue reduction: at max fatigue, reduce by FATIGUE_IMPACT_FACTOR
        double fatigueModifier = 1.0 - (fatigueLevel * FATIGUE_IMPACT_FACTOR);
        double effectiveRate = baseRate * fatigueModifier;

        // Apply attention modifier
        if (attention == AttentionState.DISTRACTED) {
            effectiveRate *= DISTRACTED_MULTIPLIER;
        }

        // Clamp to bounds
        effectiveRate = Math.max(MIN_EFFECTIVE_PREDICTION_RATE, 
                        Math.min(MAX_EFFECTIVE_PREDICTION_RATE, effectiveRate));

        log.debug(
                "Effective prediction rate: {}% (base={}%, fatigue={}%, attention={})",
                String.format("%.3f", effectiveRate * 100),
                String.format("%.3f", baseRate * 100),
                String.format("%.3f", fatigueLevel * 100),
                attention);

        return effectiveRate;
    }

    // ========================================================================
    // Public API - Start Hover
    // ========================================================================

    /**
     * Start a predictive hover over the next available target.
     * 
     * <p>This method:
     * <ol>
     *   <li>Finds the nearest valid target (excluding current target position)</li>
     *   <li>Determines click behavior based on engagement state</li>
     *   <li>Moves mouse to the target</li>
     *   <li>Records hover state for later click execution</li>
     * </ol>
     *
     * @param ctx           the task context
     * @param targetIds     set of valid target IDs (ObjectID or NpcID)
     * @param excludePosition position to exclude (current target), or null
     * @param isNpc         true if targeting NPCs (fishing spots), false for objects
     * @param menuAction    the menu action to use when clicking
     * @return CompletableFuture that completes when hover is established (or fails)
     */
    public CompletableFuture<Boolean> startPredictiveHover(
            TaskContext ctx,
            Set<Integer> targetIds,
            @Nullable WorldPoint excludePosition,
            boolean isNpc,
            String menuAction) {
        
        lastHoverAttempt = Instant.now();
        hoverAttemptsTotal.incrementAndGet();

        // Already hovering?
        if (hasPendingHover()) {
            log.debug("Already have pending hover, skipping new hover attempt");
            return CompletableFuture.completedFuture(false);
        }

        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
        if (playerPos == null) {
            log.warn("Cannot start hover: player position unknown");
            return CompletableFuture.completedFuture(false);
        }

        // Find next target
        Optional<HoverTarget> nextTarget = isNpc
                ? findNextNpcTarget(ctx, targetIds, excludePosition, playerPos)
                : findNextObjectTarget(ctx, targetIds, excludePosition, playerPos);

        if (nextTarget.isEmpty()) {
            log.debug("No valid next target found for predictive hover");
            return CompletableFuture.completedFuture(false);
        }

        HoverTarget target = nextTarget.get();
        
        // Determine click behavior
        PredictiveHoverState.ClickBehavior behavior = determineClickBehavior();
        
        // Determine hover precision (humans don't always aim perfectly)
        HoverPrecisionResult precisionResult = determineHoverPrecision(ctx, target, targetIds, isNpc);

        // Create hover state with precision info
        PredictiveHoverState hoverState = PredictiveHoverState.builder()
                .targetPosition(target.position)
                .targetId(target.id)
                .isNpc(isNpc)
                .npcIndex(target.npcIndex)
                .menuAction(menuAction)
                .hoverStartTime(Instant.now())
                .plannedBehavior(behavior)
                .validatedThisTick(true)
                .consecutiveValidTicks(1)
                .reacquisitionCount(0)
                .originalHoverPosition(target.position)
                .playerPositionAtHoverStart(playerPos)
                .hoverPrecision(precisionResult.precision)
                .impreciseOffsetX(precisionResult.offsetX)
                .impreciseOffsetY(precisionResult.offsetY)
                .build();

        // Move mouse to target (with imprecision if applicable)
        return moveToTargetWithPrecision(ctx, target, precisionResult)
                .thenApply(success -> {
                    if (success) {
                        currentHover.set(hoverState);
                        hoverSuccessCount.incrementAndGet();
                        log.debug("Started predictive hover: {} (behavior={}, precision={})", 
                                hoverState.getSummary(), behavior, precisionResult.precision);
                    } else {
                        log.debug("Failed to move mouse to predicted target");
                    }
                    return success;
                });
    }
    
    /**
     * Result of hover precision determination.
     */
    private static class HoverPrecisionResult {
        final PredictiveHoverState.HoverPrecision precision;
        final int offsetX;
        final int offsetY;
        final HoverTarget alternateTarget; // For WRONG_TARGET
        
        HoverPrecisionResult(PredictiveHoverState.HoverPrecision precision, int offsetX, int offsetY, HoverTarget alternate) {
            this.precision = precision;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.alternateTarget = alternate;
        }
        
        static HoverPrecisionResult precise() {
            return new HoverPrecisionResult(PredictiveHoverState.HoverPrecision.PRECISE, 0, 0, null);
        }
        
        static HoverPrecisionResult imprecise(int offsetX, int offsetY) {
            return new HoverPrecisionResult(PredictiveHoverState.HoverPrecision.IMPRECISE, offsetX, offsetY, null);
        }
        
        static HoverPrecisionResult emptySpace(int offsetX, int offsetY) {
            return new HoverPrecisionResult(PredictiveHoverState.HoverPrecision.MISSED_EMPTY_SPACE, offsetX, offsetY, null);
        }
        
        static HoverPrecisionResult wrongTarget(HoverTarget alternate) {
            return new HoverPrecisionResult(PredictiveHoverState.HoverPrecision.WRONG_TARGET, 0, 0, alternate);
        }
    }
    
    /**
     * Determine the precision of this hover attempt.
     * 
     * Real humans don't always hover perfectly:
     * - Sometimes they miss slightly (imprecise)
     * - Sometimes they hover over empty space
     * - Sometimes they hover over the wrong nearby object
     * 
     * Probabilities are affected by fatigue and attention state.
     */
    private HoverPrecisionResult determineHoverPrecision(TaskContext ctx, HoverTarget target, 
            Set<Integer> targetIds, boolean isNpc) {
        
        // Calculate modifiers based on fatigue and attention
        double fatigueLevel = fatigueModel.getFatigueLevel();
        AttentionState attentionState = attentionModel.getCurrentState();
        
        // Base probabilities are increased by fatigue and distraction
        double fatigueMultiplier = 1.0 + fatigueLevel * 0.8;  // Up to 1.8x at max fatigue
        double attentionMultiplier = attentionState == AttentionState.DISTRACTED ? 1.5 : 
                                     attentionState == AttentionState.AFK ? 2.0 : 1.0;
        
        double impreciseProb = IMPRECISE_HOVER_PROBABILITY * fatigueMultiplier * attentionMultiplier;
        double emptySpaceProb = EMPTY_SPACE_HOVER_PROBABILITY * fatigueMultiplier * attentionMultiplier;
        double wrongTargetProb = WRONG_TARGET_HOVER_PROBABILITY * fatigueMultiplier * attentionMultiplier;
        
        // Roll for precision type
        double roll = randomization.uniformRandom(0, 1);
        
        if (roll < wrongTargetProb) {
            // Try to find an alternate nearby target to hover over instead
            Optional<HoverTarget> alternateTarget = findAlternateTarget(ctx, target, targetIds, isNpc);
            if (alternateTarget.isPresent()) {
                log.debug("Hover precision: WRONG_TARGET (fatigue={}, attention={})", 
                        fatigueLevel, attentionState);
                return HoverPrecisionResult.wrongTarget(alternateTarget.get());
            }
            // No alternate found, fall through to empty space
            roll = wrongTargetProb + randomization.uniformRandom(0, 1) * (1 - wrongTargetProb);
        }
        
        if (roll < wrongTargetProb + emptySpaceProb) {
            // Hover to empty space near target
            int offsetX = randomization.uniformRandomInt(-EMPTY_SPACE_HOVER_RADIUS_PX, EMPTY_SPACE_HOVER_RADIUS_PX);
            int offsetY = randomization.uniformRandomInt(-EMPTY_SPACE_HOVER_RADIUS_PX, EMPTY_SPACE_HOVER_RADIUS_PX);
            // Ensure offset is far enough to actually miss
            if (Math.abs(offsetX) < IMPRECISE_HOVER_MIN_OFFSET_PX && Math.abs(offsetY) < IMPRECISE_HOVER_MIN_OFFSET_PX) {
                offsetX = offsetX >= 0 ? EMPTY_SPACE_HOVER_RADIUS_PX / 2 : -EMPTY_SPACE_HOVER_RADIUS_PX / 2;
            }
            log.debug("Hover precision: MISSED_EMPTY_SPACE offset=({}, {}) (fatigue={}, attention={})", 
                    offsetX, offsetY, fatigueLevel, attentionState);
            return HoverPrecisionResult.emptySpace(offsetX, offsetY);
        }
        
        if (roll < wrongTargetProb + emptySpaceProb + impreciseProb) {
            // Imprecise hover - close but not quite on target
            // Use Gaussian offset to cluster most imprecise hovers close to target
            double gaussianX = randomization.gaussianRandom(0, IMPRECISE_HOVER_MAX_OFFSET_PX / 3.0);
            double gaussianY = randomization.gaussianRandom(0, IMPRECISE_HOVER_MAX_OFFSET_PX / 3.0);
            
            int offsetX = (int) Math.max(IMPRECISE_HOVER_MIN_OFFSET_PX, 
                    Math.min(IMPRECISE_HOVER_MAX_OFFSET_PX, Math.abs(gaussianX))) * (gaussianX >= 0 ? 1 : -1);
            int offsetY = (int) Math.max(IMPRECISE_HOVER_MIN_OFFSET_PX, 
                    Math.min(IMPRECISE_HOVER_MAX_OFFSET_PX, Math.abs(gaussianY))) * (gaussianY >= 0 ? 1 : -1);
            
            log.debug("Hover precision: IMPRECISE offset=({}, {}) (fatigue={}, attention={})", 
                    offsetX, offsetY, fatigueLevel, attentionState);
            return HoverPrecisionResult.imprecise(offsetX, offsetY);
        }
        
        // Precise hover (most common)
        return HoverPrecisionResult.precise();
    }
    
    /**
     * Find an alternate nearby target for "wrong target" hover behavior.
     * Looks for a different object/NPC of the same type that's close to the intended target.
     */
    private Optional<HoverTarget> findAlternateTarget(TaskContext ctx, HoverTarget intendedTarget,
            Set<Integer> targetIds, boolean isNpc) {
        
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
        if (playerPos == null) {
            return Optional.empty();
        }
        
        // Find all valid targets
        List<HoverTarget> allTargets = isNpc
                ? findAllNpcTargets(ctx, targetIds, playerPos)
                : findAllObjectTargets(ctx, targetIds, playerPos);
        
        // Filter to targets that are:
        // 1. Not the intended target
        // 2. Within reasonable distance of intended target (nearby)
        List<HoverTarget> alternates = allTargets.stream()
                .filter(t -> !t.position.equals(intendedTarget.position))
                .filter(t -> t.position.distanceTo(intendedTarget.position) <= 5) // Within 5 tiles
                .collect(Collectors.toList());
        
        if (alternates.isEmpty()) {
            return Optional.empty();
        }
        
        // Pick a random alternate (humans don't always pick the closest wrong thing)
        return Optional.of(alternates.get(randomization.uniformRandomInt(0, alternates.size() - 1)));
    }

    /**
     * Overload for List-based target IDs (convenience).
     */
    public CompletableFuture<Boolean> startPredictiveHover(
            TaskContext ctx,
            List<Integer> targetIds,
            @Nullable WorldPoint excludePosition,
            boolean isNpc,
            String menuAction) {
        return startPredictiveHover(ctx, 
                targetIds.stream().collect(Collectors.toSet()),
                excludePosition, isNpc, menuAction);
    }

    // ========================================================================
    // Public API - Validation
    // ========================================================================

    /**
     * Maximum player movement (tiles) before hover is invalidated.
     * If player moves more than this distance, the hover target may no longer be reachable.
     */
    private static final int MAX_PLAYER_MOVEMENT = 3;

    /**
     * Validate and update the current hover target.
     * 
     * <p>For NPC targets (fishing spots, combat targets), this checks if the target
     * still exists at the expected position. If the target moved, attempts to
     * re-acquire the nearest valid target.
     * 
     * <p>Also validates:
     * <ul>
     *   <li>Screen position hasn't drifted significantly due to camera rotation</li>
     *   <li>Player hasn't moved significantly since hover started</li>
     * </ul>
     *
     * @param ctx the task context
     * @param targetIds set of valid target IDs for re-acquisition
     */
    public void validateAndUpdateHover(TaskContext ctx, Set<Integer> targetIds) {
        PredictiveHoverState state = currentHover.get();
        if (state == null) {
            return;
        }

        // Check for stale hovers (auto-clear after MAX_HOVER_STALENESS_MS)
        long hoverAge = state.getHoverDurationMs();
        if (hoverAge > MAX_HOVER_STALENESS_MS) {
            log.debug("Clearing stale hover: {}ms old (max {}ms)", hoverAge, MAX_HOVER_STALENESS_MS);
            clearHover();
            abandonedHoverCount.incrementAndGet();
            return;
        }

        // Check if player has moved significantly since hover started
        WorldPoint currentPlayerPos = ctx.getPlayerState().getWorldPosition();
        if (currentPlayerPos != null && state.getPlayerPositionAtHoverStart() != null) {
            int playerMovement = currentPlayerPos.distanceTo(state.getPlayerPositionAtHoverStart());
            if (playerMovement > MAX_PLAYER_MOVEMENT) {
                log.debug("Clearing hover: player moved {} tiles since hover started", playerMovement);
                clearHover();
                abandonedHoverCount.incrementAndGet();
                return;
            }
        }

        // === Cursor Drift During Hover ===
        // Real humans don't hold the mouse perfectly still while waiting.
        // Small drift simulates natural hand movement.
        if (randomization.chance(HOVER_DRIFT_PROBABILITY_PER_TICK)) {
            applyCursorDrift(ctx);
        }

        // For objects: validate screen position hasn't drifted from camera rotation
        if (!state.requiresValidation()) {
            // Check if we should re-hover due to camera rotation causing screen drift
            if (shouldReHoverDueToCameraDrift(ctx, state)) {
                reHoverTarget(ctx, state);
            } else if (!state.isValidatedThisTick()) {
                currentHover.set(state.withValidation(true));
            }
            return;
        }

        // NPC validation
        WorldState world = ctx.getWorldState();
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
        
        // Check if target still exists at expected position
        Optional<NpcSnapshot> existingTarget = findNpcAtPosition(world, state.getTargetId(), state.getTargetPosition());
        
        if (existingTarget.isPresent()) {
            // Target still exists - but check for camera drift
            if (shouldReHoverDueToCameraDrift(ctx, state)) {
                reHoverTarget(ctx, state);
            } else {
                currentHover.set(state.withValidation(true));
            }
            return;
        }

        // Target moved or despawned - attempt re-acquisition
        log.debug("Hover target moved from {}, attempting re-acquisition", state.getTargetPosition());
        
        // Check for unstable hover (too many re-acquisitions or too much drift)
        if (state.getReacquisitionCount() >= MAX_REACQUISITIONS) {
            log.debug("Abandoning hover: too many re-acquisitions ({})", state.getReacquisitionCount());
            clearHover();
            abandonedHoverCount.incrementAndGet();
            return;
        }

        // Find nearest valid NPC
        Optional<NpcSnapshot> newTarget = world.getNearbyNpcs().stream()
                .filter(npc -> targetIds.contains(npc.getId()))
                .filter(npc -> !npc.isDead())
                .filter(npc -> npc.isWithinDistance(playerPos, MAX_TARGET_DISTANCE))
                // Prefer targets near the original position
                .min(Comparator.comparingInt(npc -> {
                    int distFromOriginal = npc.distanceTo(state.getOriginalHoverPosition());
                    int distFromPlayer = npc.distanceTo(playerPos);
                    return distFromOriginal * 2 + distFromPlayer; // Weight original position higher
                }));

        if (newTarget.isEmpty()) {
            log.debug("No valid re-acquisition target found, abandoning hover");
            clearHover();
            abandonedHoverCount.incrementAndGet();
            return;
        }

        NpcSnapshot acquired = newTarget.get();
        WorldPoint newPos = acquired.getWorldPosition();
        int drift = state.getOriginalHoverPosition().distanceTo(newPos);

        if (drift > MAX_DRIFT_DISTANCE) {
            log.debug("Abandoning hover: drift too large ({} tiles)", drift);
            clearHover();
            abandonedHoverCount.incrementAndGet();
            return;
        }

        // Update hover state with new target
        PredictiveHoverState updatedState = state.withReacquisition(newPos, acquired.getIndex());
        currentHover.set(updatedState);
        
        // Move mouse to new position
        moveToNpc(ctx, acquired).thenAccept(success -> {
            if (!success) {
                log.debug("Failed to move to re-acquired target");
                clearHover();
            }
        });

        log.debug("Re-acquired hover target at {} (drift={} tiles, reacq={})", 
                newPos, drift, updatedState.getReacquisitionCount());
    }

    /**
     * Overload for List-based target IDs.
     */
    public void validateAndUpdateHover(TaskContext ctx, List<Integer> targetIds) {
        validateAndUpdateHover(ctx, targetIds.stream().collect(Collectors.toSet()));
    }

    // ========================================================================
    // Public API - Click Execution
    // ========================================================================

    /**
     * Execute the predicted click based on the hover state's planned behavior.
     * 
     * <p>Should be called when the current action completes and the bot is ready
     * to interact with the predicted next target.
     *
     * @param ctx the task context
     * @return CompletableFuture completing with true if click succeeded
     */
    public CompletableFuture<Boolean> executePredictedClick(TaskContext ctx) {
        PredictiveHoverState state = currentHover.get();
        if (state == null || !state.isValid()) {
            log.debug("No valid hover state for predicted click");
            return CompletableFuture.completedFuture(false);
        }

        // Clear the hover state before executing
        currentHover.set(null);

        switch (state.getPlannedBehavior()) {
            case INSTANT:
                return executeInstantClick(ctx, state);
            case DELAYED:
                return executeDelayedClick(ctx, state);
            case ABANDON:
            default:
                abandonedHoverCount.incrementAndGet();
                log.debug("Abandoned predicted click per planned behavior");
                return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Execute an instant click (no hesitation delay).
     */
    private CompletableFuture<Boolean> executeInstantClick(TaskContext ctx, PredictiveHoverState state) {
        log.debug("Executing instant predicted click");
        instantClickCount.incrementAndGet();
        return performClick(ctx, state);
    }

    /**
     * Execute a delayed click (with hesitation).
     */
    private CompletableFuture<Boolean> executeDelayedClick(TaskContext ctx, PredictiveHoverState state) {
        // Calculate hesitation delay (log-normal for decision timing, modified by fatigue)
        double fatigueMultiplier = 1.0 + fatigueModel.getFatigueLevel() * 0.5;
        long baseDelay = randomization.humanizedDelayMs(200, MIN_HESITATION_DELAY_MS, MAX_HESITATION_DELAY_MS);
        long delay = (long) (baseDelay * fatigueMultiplier);

        log.debug("Executing delayed predicted click ({}ms hesitation)", delay);
        delayedClickCount.incrementAndGet();

        HumanTimer timer = ctx.getHumanTimer();
        if (timer != null) {
            return timer.sleep(delay).thenCompose(v -> performClick(ctx, state));
        } else {
            // Fallback: blocking sleep (not ideal but functional)
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CompletableFuture.completedFuture(false);
            }
            return performClick(ctx, state);
        }
    }

    /**
     * Perform the actual click on the hovered target.
     * 
     * <p>Before clicking, validates that:
     * <ul>
     *   <li>The target is still visible on screen</li>
     *   <li>The mouse is approximately over the target</li>
     * </ul>
     */
    private CompletableFuture<Boolean> performClick(TaskContext ctx, PredictiveHoverState state) {
        RobotMouseController mouse = ctx.getMouseController();
        if (mouse == null) {
            log.debug("Cannot perform predicted click: mouse controller not available");
            return CompletableFuture.completedFuture(false);
        }

        // Validate target is still visible and mouse is approximately over it
        Point targetScreen = getTargetScreenPosition(ctx, state);
        if (targetScreen == null) {
            log.debug("Cannot perform predicted click: target not visible on screen");
            return CompletableFuture.completedFuture(false);
        }

        Point mousePos = mouse.getCurrentPosition();
        if (mousePos != null) {
            int dx = Math.abs(mousePos.x - targetScreen.x);
            int dy = Math.abs(mousePos.y - targetScreen.y);
            int distance = (int) Math.sqrt(dx * dx + dy * dy);
            
            // If mouse has drifted too far from target, re-hover first
            if (distance > MAX_SCREEN_DRIFT_PIXELS) {
                log.debug("Mouse drifted {}px from target, re-hovering before click", distance);
                return ctx.getMouseController().moveToCanvas(targetScreen.x, targetScreen.y)
                        .thenCompose(v -> mouse.click())
                        .thenApply(v -> true)
                        .exceptionally(e -> {
                            log.debug("Predicted click after re-hover failed: {}", e.getMessage());
                            return false;
                        });
            }
        }

        // Click - target is visible and mouse is on target
        return mouse.click()
                .thenApply(v -> true)
                .exceptionally(e -> {
                    log.debug("Predicted click failed: {}", e.getMessage());
                    return false;
                });
    }

    // ========================================================================
    // Private - Click Behavior Determination
    // ========================================================================

    /**
     * Determine the click behavior for this hover based on engagement factors.
     * 
     * @return the planned click behavior
     */
    private PredictiveHoverState.ClickBehavior determineClickBehavior() {
        AttentionState attention = attentionModel.getCurrentState();
        double speedBias = playerProfile.getPredictionClickSpeedBias();
        
        double instantProb, delayedProb, abandonProb;

        if (attention == AttentionState.FOCUSED) {
            // FOCUSED: High variation based on speed bias
            // Speed bias 0.0 -> 30% instant, 45% delayed, 25% abandon
            // Speed bias 1.0 -> 80% instant, 15% delayed, 5% abandon
            instantProb = FOCUSED_INSTANT_BASE + (speedBias * 0.30);  // 50-80%
            delayedProb = FOCUSED_DELAYED_BASE - (speedBias * 0.20);  // 35-15%
            abandonProb = FOCUSED_ABANDON_BASE - (speedBias * 0.10);  // 15-5%
        } else {
            // DISTRACTED: Less affected by speed bias, more abandons
            instantProb = DISTRACTED_INSTANT_BASE + (speedBias * 0.10);  // 25-35%
            delayedProb = DISTRACTED_DELAYED_BASE;                        // 40%
            abandonProb = DISTRACTED_ABANDON_BASE - (speedBias * 0.10);  // 35-25%
        }

        // Normalize (ensure they sum to 1.0)
        double total = instantProb + delayedProb + abandonProb;
        instantProb /= total;
        delayedProb /= total;
        abandonProb /= total;

        // Roll for behavior
        double roll = randomization.uniformRandom(0, 1);
        
        if (roll < instantProb) {
            return PredictiveHoverState.ClickBehavior.INSTANT;
        } else if (roll < instantProb + delayedProb) {
            return PredictiveHoverState.ClickBehavior.DELAYED;
        } else {
            return PredictiveHoverState.ClickBehavior.ABANDON;
        }
    }

    // ========================================================================
    // Private - Target Finding
    // ========================================================================

    /**
     * Find the next valid NPC target for predictive hover.
     */
    private Optional<HoverTarget> findNextNpcTarget(
            TaskContext ctx,
            Set<Integer> targetIds,
            @Nullable WorldPoint excludePosition,
            WorldPoint playerPos) {
        
        WorldState world = ctx.getWorldState();
        
        return world.getNearbyNpcs().stream()
                .filter(npc -> targetIds.contains(npc.getId()))
                .filter(npc -> !npc.isDead())
                .filter(npc -> npc.isWithinDistance(playerPos, MAX_TARGET_DISTANCE))
                .filter(npc -> excludePosition == null || 
                        !npc.getWorldPosition().equals(excludePosition))
                .min(Comparator.comparingInt(npc -> npc.distanceTo(playerPos)))
                .map(npc -> new HoverTarget(
                        npc.getWorldPosition(),
                        npc.getId(),
                        npc.getIndex(),
                        null
                ));
    }

    /**
     * Find the next valid game object target for predictive hover.
     */
    private Optional<HoverTarget> findNextObjectTarget(
            TaskContext ctx,
            Set<Integer> targetIds,
            @Nullable WorldPoint excludePosition,
            WorldPoint playerPos) {

        WorldState world = ctx.getWorldState();

        return world.getNearbyObjects().stream()
                .filter(obj -> targetIds.contains(obj.getId()))
                .filter(obj -> obj.isWithinDistance(playerPos, MAX_TARGET_DISTANCE))
                .filter(obj -> excludePosition == null ||
                        !obj.getWorldPosition().equals(excludePosition))
                .map(obj -> buildHoverCandidate(ctx, obj, playerPos))
                .filter(Objects::nonNull)
                .min(Comparator
                        // Prefer shorter walking path
                        .comparingInt((HoverCandidate c) -> c.pathCost)
                        // Then prefer visible targets (clickbox available)
                        .thenComparingInt(c -> c.visible ? 0 : 1)
                        // Tie-breaker: straight-line distance
                        .thenComparingInt(c -> c.tileDistance))
                .map(c -> new HoverTarget(
                        c.position,
                        c.id,
                        -1,
                        c.tileObject
                ));
    }

    /**
     * Build a hover candidate with reachability and visibility metadata.
     */
    private HoverCandidate buildHoverCandidate(TaskContext ctx, GameObjectSnapshot obj, WorldPoint playerPos) {
        WorldPoint position = obj.getWorldPosition();
        if (position == null) {
            return null;
        }

        java.util.OptionalInt pathCostOpt = tryPathCost(ctx, playerPos, position);
        if (pathCostOpt.isEmpty()) {
            return null; // unreachable
        }
        int pathCost = pathCostOpt.getAsInt();

        TileObject tileObject = findTileObject(ctx, obj);
        boolean visible = tileObject != null && tileObject.getClickbox() != null;
        int tileDistance = obj.distanceTo(playerPos);

        return new HoverCandidate(obj.getId(), position, pathCost, tileDistance, visible, tileObject);
    }

    private java.util.OptionalInt tryPathCost(TaskContext ctx, WorldPoint start, WorldPoint end) {
        if (start.equals(end)) {
            return java.util.OptionalInt.of(1);
        }
        try {
            int cost = getPathCost(ctx, start, end);
            if (cost < 0) {
                return java.util.OptionalInt.empty();
            }
            return java.util.OptionalInt.of(cost);
        } catch (IllegalStateException e) {
            log.debug("Skipping unreachable hover target between {} and {}: {}", start, end, e.getMessage());
            return java.util.OptionalInt.empty();
        }
    }

    /**
     * Find an NPC at a specific position with a specific ID.
     */
    private Optional<NpcSnapshot> findNpcAtPosition(WorldState world, int npcId, WorldPoint position) {
        return world.getNearbyNpcs().stream()
                .filter(npc -> npc.getId() == npcId)
                .filter(npc -> npc.getWorldPosition().equals(position))
                .findFirst();
    }

    // ========================================================================
    // Camera Drift Detection
    // ========================================================================

    /**
     * Maximum screen position drift (pixels) before re-hover is triggered.
     * Camera rotation can cause the target to move on screen.
     */
    private static final int MAX_SCREEN_DRIFT_PIXELS = 50;

    /**
     * Minimum time between re-hovers due to camera drift (ms).
     * Prevents constant re-hovering during active camera rotation.
     */
    private static final long MIN_REHOVER_INTERVAL_MS = 500;

    /**
     * Last time we performed a re-hover due to camera drift.
     */
    private volatile long lastReHoverTime = 0;

    /**
     * Check if the target's screen position has drifted significantly due to camera rotation.
     * 
     * @param ctx the task context
     * @param state the current hover state
     * @return true if re-hover is needed
     */
    private boolean shouldReHoverDueToCameraDrift(TaskContext ctx, PredictiveHoverState state) {
        // Rate limit re-hovers
        if (System.currentTimeMillis() - lastReHoverTime < MIN_REHOVER_INTERVAL_MS) {
            return false;
        }

        RobotMouseController mouse = ctx.getMouseController();
        if (mouse == null) {
            return false;
        }

        Point currentMousePos = mouse.getCurrentPosition();
        if (currentMousePos == null) {
            return false;
        }

        // Get current screen position of the target
        Point currentTargetScreen = getTargetScreenPosition(ctx, state);
        if (currentTargetScreen == null) {
            // Target not visible - might need to clear hover
            return false;
        }

        // Calculate drift distance
        int driftX = Math.abs(currentMousePos.x - currentTargetScreen.x);
        int driftY = Math.abs(currentMousePos.y - currentTargetScreen.y);
        int totalDrift = (int) Math.sqrt(driftX * driftX + driftY * driftY);

        if (totalDrift > MAX_SCREEN_DRIFT_PIXELS) {
            log.debug("Camera drift detected: mouse at ({},{}) but target at ({},{}) - drift={}px",
                    currentMousePos.x, currentMousePos.y, 
                    currentTargetScreen.x, currentTargetScreen.y, totalDrift);
            return true;
        }

        return false;
    }

    /**
     * Get the current screen position of a hover target.
     * 
     * @param ctx the task context
     * @param state the hover state
     * @return screen position, or null if not visible
     */
    @Nullable
    private Point getTargetScreenPosition(TaskContext ctx, PredictiveHoverState state) {
        Client client = ctx.getClient();
        if (client == null) {
            return null;
        }

        if (state.isNpc()) {
            return getNpcScreenPosition(ctx, state);
        } else {
            return getObjectScreenPosition(ctx, state);
        }
    }

    /**
     * Get screen position for an NPC target.
     */
    @Nullable
    private Point getNpcScreenPosition(TaskContext ctx, PredictiveHoverState state) {
        Client client = ctx.getClient();
        if (state.getNpcIndex() < 0) {
            return null;
        }

        // Find the NPC by index
        for (NPC npc : client.getNpcs()) {
            if (npc != null && npc.getIndex() == state.getNpcIndex()) {
                Shape hull = npc.getConvexHull();
                if (hull != null) {
                    Rectangle bounds = hull.getBounds();
                    return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
                }
            }
        }
        return null;
    }

    /**
     * Get screen position for an object target.
     */
    @Nullable
    private Point getObjectScreenPosition(TaskContext ctx, PredictiveHoverState state) {
        // Find the object at the target position and get its current clickbox
        GameObjectSnapshot snapshot = GameObjectSnapshot.builder()
                .id(state.getTargetId())
                .worldPosition(state.getTargetPosition())
                .plane(state.getTargetPosition().getPlane())
                .build();

        TileObject obj = findTileObject(ctx, snapshot);
        if (obj != null) {
            Shape clickbox = obj.getClickbox();
            if (clickbox != null) {
                Rectangle bounds = clickbox.getBounds();
                return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
            }
        }
        return null;
    }

    /**
     * Apply small cursor drift to simulate natural hand movement during hover wait.
     * Real humans don't hold the mouse perfectly still - there's always slight movement.
     */
    private void applyCursorDrift(TaskContext ctx) {
        RobotMouseController mouseController = ctx.getMouseController();
        if (mouseController == null) {
            return;
        }
        
        // Small random drift (Gaussian centered on 0)
        int driftX = (int) randomization.gaussianRandom(0, HOVER_DRIFT_MAX_PX / 2.0, 
                -HOVER_DRIFT_MAX_PX, HOVER_DRIFT_MAX_PX);
        int driftY = (int) randomization.gaussianRandom(0, HOVER_DRIFT_MAX_PX / 2.0, 
                -HOVER_DRIFT_MAX_PX, HOVER_DRIFT_MAX_PX);
        
        if (driftX == 0 && driftY == 0) {
            return;
        }
        
        // Apply drift as relative mouse movement
        // Use sync method but don't block - this is called from game tick
        try {
            mouseController.moveRelativeSync(driftX, driftY);
            log.trace("Applied hover drift: ({}, {})", driftX, driftY);
        } catch (Exception e) {
            log.trace("Cursor drift failed: {}", e.getMessage());
        }
    }

    /**
     * Re-hover the target at its current screen position.
     * Called when camera drift is detected.
     */
    private void reHoverTarget(TaskContext ctx, PredictiveHoverState state) {
        lastReHoverTime = System.currentTimeMillis();

        Point targetScreen = getTargetScreenPosition(ctx, state);
        if (targetScreen == null) {
            log.debug("Cannot re-hover: target not visible on screen");
            return;
        }

        // Move mouse to new position (with slight variance for human-likeness)
        int varX = randomization.uniformRandomInt(-5, 5);
        int varY = randomization.uniformRandomInt(-5, 5);

        ctx.getMouseController().moveToCanvas(targetScreen.x + varX, targetScreen.y + varY)
                .thenAccept(v -> log.debug("Re-hovered target after camera drift"))
                .exceptionally(e -> {
                    log.debug("Failed to re-hover: {}", e.getMessage());
                    return null;
                });
    }

    /**
     * Find the actual TileObject from a GameObjectSnapshot.
     */
    @Nullable
    private TileObject findTileObject(TaskContext ctx, GameObjectSnapshot snapshot) {
        Client client = ctx.getClient();
        if (client == null || snapshot.getWorldPosition() == null) {
            return null;
        }

        LocalPoint localPoint = LocalPoint.fromWorld(client, snapshot.getWorldPosition());
        if (localPoint == null) {
            return null;
        }

        Scene scene = client.getScene();
        if (scene == null) {
            return null;
        }
        
        Tile[][][] tiles = scene.getTiles();
        if (tiles == null) {
            return null;
        }
        int plane = snapshot.getPlane();
        int sceneX = localPoint.getSceneX();
        int sceneY = localPoint.getSceneY();

        if (plane < 0 || plane >= tiles.length ||
            sceneX < 0 || sceneX >= tiles[plane].length ||
            sceneY < 0 || sceneY >= tiles[plane][sceneX].length) {
            return null;
        }

        Tile tile = tiles[plane][sceneX][sceneY];
        if (tile == null) {
            return null;
        }

        // Check game objects
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject obj : gameObjects) {
                if (obj != null && obj.getId() == snapshot.getId()) {
                    return obj;
                }
            }
        }

        return null;
    }

    // ========================================================================
    // Private - Mouse Movement
    // ========================================================================

    /**
     * Move the mouse to a hover target.
     */
    private CompletableFuture<Boolean> moveToTarget(TaskContext ctx, HoverTarget target) {
        if (target.npcIndex >= 0) {
            // NPC target - find live NPC by index
            Client client = ctx.getClient();
            if (client == null) {
                return CompletableFuture.completedFuture(false);
            }
            
            // Find NPC by index in the NPCs list
            NPC foundNpc = null;
            for (NPC npc : client.getNpcs()) {
                if (npc != null && npc.getIndex() == target.npcIndex) {
                    foundNpc = npc;
                    break;
                }
            }
            
            if (foundNpc == null) {
                return CompletableFuture.completedFuture(false);
            }
            
            return moveToNpc(ctx, foundNpc);
        } else {
            // Object target (may need visibility/camera help)
            return moveToObject(ctx, target);
        }
    }
    
    /**
     * Move mouse to target with optional precision offset.
     * This is the main entry point for hover movement with human-like imprecision.
     */
    private CompletableFuture<Boolean> moveToTargetWithPrecision(TaskContext ctx, HoverTarget target, 
            HoverPrecisionResult precisionResult) {
        
        switch (precisionResult.precision) {
            case PRECISE:
                // Normal precise hover
                return moveToTarget(ctx, target);
                
            case IMPRECISE:
                // Move to target then apply offset (simulates slightly missing)
                return moveToTargetWithOffset(ctx, target, precisionResult.offsetX, precisionResult.offsetY);
                
            case MISSED_EMPTY_SPACE:
                // Move to empty space near target
                return moveToEmptySpaceNear(ctx, target, precisionResult.offsetX, precisionResult.offsetY);
                
            case WRONG_TARGET:
                // Move to the alternate target instead
                if (precisionResult.alternateTarget != null) {
                    return moveToTarget(ctx, precisionResult.alternateTarget);
                }
                // Fallback to normal if no alternate
                return moveToTarget(ctx, target);
                
            default:
                return moveToTarget(ctx, target);
        }
    }
    
    /**
     * Move to target with a pixel offset (for imprecise hovers).
     * The offset is applied after calculating the target's screen position.
     */
    private CompletableFuture<Boolean> moveToTargetWithOffset(TaskContext ctx, HoverTarget target, 
            int offsetX, int offsetY) {
        
        if (target.npcIndex >= 0) {
            // NPC target
            Client client = ctx.getClient();
            if (client == null) {
                return CompletableFuture.completedFuture(false);
            }
            
            NPC foundNpc = null;
            for (NPC npc : client.getNpcs()) {
                if (npc != null && npc.getIndex() == target.npcIndex) {
                    foundNpc = npc;
                    break;
                }
            }
            
            if (foundNpc == null) {
                return CompletableFuture.completedFuture(false);
            }
            
            Shape convexHull = foundNpc.getConvexHull();
            if (convexHull == null) {
                return CompletableFuture.completedFuture(false);
            }
            
            Rectangle bounds = convexHull.getBounds();
            if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
                return CompletableFuture.completedFuture(false);
            }
            
            // Calculate target point then apply offset
            Point clickPoint = ClickPointCalculator.getGaussianClickPoint(bounds);
            if (clickPoint == null) {
                clickPoint = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
            }
            
            int finalX = clickPoint.x + offsetX;
            int finalY = clickPoint.y + offsetY;
            
            return ctx.getMouseController().moveToCanvas(finalX, finalY)
                    .thenApply(v -> true)
                    .exceptionally(e -> false);
        } else {
            // Object target - need to find screen position
            return moveToObjectWithOffset(ctx, target, offsetX, offsetY);
        }
    }
    
    /**
     * Move to object with pixel offset.
     */
    private CompletableFuture<Boolean> moveToObjectWithOffset(TaskContext ctx, HoverTarget target, 
            int offsetX, int offsetY) {
        
        Client client = ctx.getClient();
        if (client == null) {
            return CompletableFuture.completedFuture(false);
        }
        
        // Find the object's screen position via its TileObject or scene lookup
        LocalPoint localPoint = LocalPoint.fromWorld(client, target.position);
        if (localPoint == null) {
            return CompletableFuture.completedFuture(false);
        }
        
        // Get screen position of tile center
        net.runelite.api.Point tileScreen = net.runelite.api.Perspective.localToCanvas(
                client, localPoint, 0);
        
        if (tileScreen == null) {
            return CompletableFuture.completedFuture(false);
        }
        
        // Apply offset from tile center
        int finalX = tileScreen.getX() + offsetX;
        int finalY = tileScreen.getY() + offsetY;
        
        return ctx.getMouseController().moveToCanvas(finalX, finalY)
                .thenApply(v -> true)
                .exceptionally(e -> false);
    }
    
    /**
     * Move to empty space near a target (for completely missed hovers).
     */
    private CompletableFuture<Boolean> moveToEmptySpaceNear(TaskContext ctx, HoverTarget target, 
            int offsetX, int offsetY) {
        
        Client client = ctx.getClient();
        if (client == null) {
            return CompletableFuture.completedFuture(false);
        }
        
        // Get the target's approximate screen center
        LocalPoint localPoint = LocalPoint.fromWorld(client, target.position);
        if (localPoint == null) {
            return CompletableFuture.completedFuture(false);
        }
        
        // Calculate approximate screen position of target
        int tileHeight = 0; // Ground level
        net.runelite.api.Point targetScreen = net.runelite.api.Perspective.localToCanvas(
                client, localPoint, tileHeight);
        
        if (targetScreen == null) {
            return CompletableFuture.completedFuture(false);
        }
        
        // Apply offset to move to empty space
        int finalX = targetScreen.getX() + offsetX;
        int finalY = targetScreen.getY() + offsetY;
        
        return ctx.getMouseController().moveToCanvas(finalX, finalY)
                .thenApply(v -> true)
                .exceptionally(e -> false);
    }
    
    /**
     * Find all NPC targets matching the given IDs (not just the nearest).
     * Used for finding alternate targets for "wrong target" hover behavior.
     */
    private List<HoverTarget> findAllNpcTargets(TaskContext ctx, Set<Integer> targetIds, WorldPoint playerPos) {
        WorldState worldState = ctx.getWorldState();
        if (worldState == null) {
            return List.of();
        }
        
        return worldState.getNearbyNpcs().stream()
                .filter(Objects::nonNull)
                .filter(npc -> targetIds.contains(npc.getId()))
                .filter(npc -> npc.getWorldPosition() != null)
                .filter(npc -> npc.getWorldPosition().distanceTo(playerPos) <= MAX_TARGET_DISTANCE)
                .map(npc -> new HoverTarget(npc.getWorldPosition(), npc.getId(), npc.getIndex(), null))
                .collect(Collectors.toList());
    }
    
    /**
     * Find all object targets matching the given IDs (not just the nearest).
     * Used for finding alternate targets for "wrong target" hover behavior.
     */
    private List<HoverTarget> findAllObjectTargets(TaskContext ctx, Set<Integer> targetIds, WorldPoint playerPos) {
        WorldState worldState = ctx.getWorldState();
        if (worldState == null) {
            return List.of();
        }
        
        return worldState.getNearbyObjects().stream()
                .filter(Objects::nonNull)
                .filter(obj -> targetIds.contains(obj.getId()))
                .filter(obj -> obj.getWorldPosition() != null)
                .filter(obj -> obj.getWorldPosition().distanceTo(playerPos) <= MAX_TARGET_DISTANCE)
                .map(obj -> new HoverTarget(obj.getWorldPosition(), obj.getId(), -1, null))
                .collect(Collectors.toList());
    }

    /**
     * Calculate actual walking path cost between two points using NavigationService.
     * Falls back to straight-line distance if path calculation fails.
     */
    private int getPathCost(TaskContext ctx, WorldPoint start, WorldPoint end) {
        if (start.equals(end)) {
            return 1;
        }
        
        // Use NavigationService for actual path cost (considers fences, rivers, etc.)
        java.util.OptionalInt pathCost = navigationService.getPathCost(ctx, start, end);
        if (pathCost.isPresent()) {
            return pathCost.getAsInt();
        }
        
        // Path unreachable - fall back to distance estimate with large penalty
        log.debug("Path unreachable from {} to {}, using distance fallback with penalty", start, end);
        return Math.max(1, start.distanceTo(end) * 2); // Penalize unreachable paths
    }

    /**
     * Move mouse to an NPC.
     */
    private CompletableFuture<Boolean> moveToNpc(TaskContext ctx, NPC npc) {
        Shape convexHull = npc.getConvexHull();
        if (convexHull == null) {
            log.debug("NPC has no convex hull for mouse move");
            return CompletableFuture.completedFuture(false);
        }

        Rectangle bounds = convexHull.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return CompletableFuture.completedFuture(false);
        }

        Point clickPoint = ClickPointCalculator.getGaussianClickPoint(bounds);
        if (clickPoint == null || !convexHull.contains(clickPoint)) {
            clickPoint = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        }

        return ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y)
                .thenApply(v -> true)
                .exceptionally(e -> {
                    log.debug("Failed to move mouse to NPC: {}", e.getMessage());
                    return false;
                });
    }

    /**
     * Move mouse to an NPC snapshot.
     */
    private CompletableFuture<Boolean> moveToNpc(TaskContext ctx, NpcSnapshot snapshot) {
        Client client = ctx.getClient();
        if (client == null || snapshot.getIndex() < 0) {
            return CompletableFuture.completedFuture(false);
        }

        // Find NPC by index
        NPC foundNpc = null;
        for (NPC npc : client.getNpcs()) {
            if (npc != null && npc.getIndex() == snapshot.getIndex()) {
                foundNpc = npc;
                break;
            }
        }
        
        if (foundNpc == null) {
            return CompletableFuture.completedFuture(false);
        }

        return moveToNpc(ctx, foundNpc);
    }

    /**
     * Move mouse to a TileObject, rotating camera if needed to reveal the clickbox.
     */
    private CompletableFuture<Boolean> moveToObject(TaskContext ctx, HoverTarget target) {
        TileObject object = target.tileObject != null ? target.tileObject : refetchTileObject(ctx, target);
        Shape clickbox = object != null ? object.getClickbox() : null;

        if (clickbox == null) {
            // Attempt to rotate camera to make the object visible
            MouseCameraCoupler coupler = ctx.getMouseCameraCoupler();
            if (coupler == null) {
                log.debug("Object has no clickbox and no camera coupler available");
                return CompletableFuture.completedFuture(false);
            }

            return coupler.ensureTargetVisible(target.position)
                    .thenCompose(v -> {
                        TileObject refreshed = refetchTileObject(ctx, target);
                        Shape refreshedClickbox = refreshed != null ? refreshed.getClickbox() : null;
                        if (refreshed == null || refreshedClickbox == null) {
                            log.debug("Object still not visible after camera adjustment");
                            return CompletableFuture.completedFuture(false);
                        }
                        return moveToObjectWithClickbox(ctx, refreshed, refreshedClickbox);
                    })
                    .exceptionally(e -> {
                        log.debug("Failed to rotate camera for object hover: {}", e.getMessage());
                        return false;
                    });
        }

        return moveToObjectWithClickbox(ctx, object, clickbox);
    }

    private CompletableFuture<Boolean> moveToObjectWithClickbox(TaskContext ctx, TileObject object, Shape clickbox) {
        Rectangle bounds = clickbox.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return CompletableFuture.completedFuture(false);
        }

        Point clickPoint = ClickPointCalculator.getGaussianClickPoint(bounds);
        if (clickPoint == null || !clickbox.contains(clickPoint)) {
            clickPoint = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        }

        return ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y)
                .thenApply(v -> true)
                .exceptionally(e -> {
                    log.debug("Failed to move mouse to object: {}", e.getMessage());
                    return false;
                });
    }

    /**
     * Re-fetch the TileObject after camera rotation.
     */
    @Nullable
    private TileObject refetchTileObject(TaskContext ctx, HoverTarget target) {
        GameObjectSnapshot snapshot = GameObjectSnapshot.builder()
                .id(target.id)
                .worldPosition(target.position)
                .plane(target.position.getPlane())
                .build();
        return findTileObject(ctx, snapshot);
    }

    /**
     * Move mouse to a world point (fallback, less accurate).
     * 
     * <p>This is a last-resort fallback that should rarely be used.
     * Object/NPC movements should use their convex hulls or clickboxes instead.
     * Returns false to trigger the normal interaction flow rather than using
     * inaccurate screen coordinates.
     */
    private CompletableFuture<Boolean> moveToWorldPoint(TaskContext ctx, WorldPoint worldPoint) {
        // World point conversion to screen coordinates requires Perspective API
        // which depends on camera position, distance, etc. Rather than use an
        // inaccurate approximation, we return false to let the normal task flow
        // find and interact with the target properly.
        log.debug("moveToWorldPoint fallback - returning false to use normal interaction flow");
        return CompletableFuture.completedFuture(false);
    }

    // ========================================================================
    // Metrics
    // ========================================================================

    /**
     * Reset all session metrics. Called at session start.
     */
    public void resetMetrics() {
        hoverAttemptsTotal.set(0);
        hoverSuccessCount.set(0);
        instantClickCount.set(0);
        delayedClickCount.set(0);
        abandonedHoverCount.set(0);
    }

    /**
     * Get the hover success rate for this session.
     *
     * @return success rate (0.0 to 1.0), or 0.0 if no attempts
     */
    public double getHoverSuccessRate() {
        long attempts = hoverAttemptsTotal.get();
        if (attempts == 0) {
            return 0.0;
        }
        return (double) hoverSuccessCount.get() / attempts;
    }

    /**
     * Get summary statistics for logging.
     *
     * @return human-readable summary
     */
    public String getMetricsSummary() {
        return String.format(
                "Hovers: %d attempts, %d success (%.1f%%), clicks: %d instant, %d delayed, %d abandoned",
                hoverAttemptsTotal.get(), hoverSuccessCount.get(), getHoverSuccessRate() * 100,
                instantClickCount.get(), delayedClickCount.get(), abandonedHoverCount.get());
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    /**
     * Internal holder for hover candidate scoring.
     */
    private static class HoverCandidate {
        final int id;
        final WorldPoint position;
        final int pathCost;
        final int tileDistance;
        final boolean visible;
        @Nullable
        final TileObject tileObject;

        HoverCandidate(int id, WorldPoint position, int pathCost, int tileDistance, boolean visible, @Nullable TileObject tileObject) {
            this.id = id;
            this.position = position;
            this.pathCost = pathCost;
            this.tileDistance = tileDistance;
            this.visible = visible;
            this.tileObject = tileObject;
        }
    }

    /**
     * Internal holder for hover target information.
     */
    private static class HoverTarget {
        final WorldPoint position;
        final int id;
        final int npcIndex; // -1 for objects
        @Nullable
        final TileObject tileObject; // null for NPCs

        HoverTarget(WorldPoint position, int id, int npcIndex, @Nullable TileObject tileObject) {
            this.position = position;
            this.id = id;
            this.npcIndex = npcIndex;
            this.tileObject = tileObject;
        }
    }
}

