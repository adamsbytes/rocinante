package com.rocinante.behavior;

import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;

/**
 * Models player attention as a state machine with transitions between
 * focused, distracted, and AFK states.
 * 
 * Per REQUIREMENTS.md Section 4.4:
 * 
 * Focus States:
 * - FOCUSED (70% of active time): Normal delays, high precision
 * - DISTRACTED (25% of active time): 1.3x delays, 200-800ms event processing lag
 * - AFK (5% of active time): 3-15 second unresponsive periods
 * 
 * Transitions occur every 30-180 seconds with weighted probabilities.
 * 
 * External Distractions:
 * - 2-15 second pauses, 2-6 times per hour
 * - Triggered by: game chat (30% chance) or random intervals (exponential)
 * 
 * HCIM Special Rules:
 * - No AFK during ANY combat (not just bosses)
 * - More conservative transition weights
 */
@Slf4j
@Singleton
public class AttentionModel {

    // === Transition timing ===
    
    /**
     * Minimum time between state transitions (seconds).
     */
    private static final int MIN_TRANSITION_INTERVAL_SECONDS = 30;
    
    /**
     * Maximum time between state transitions (seconds).
     */
    private static final int MAX_TRANSITION_INTERVAL_SECONDS = 180;
    
    // === AFK duration ===
    
    /**
     * Minimum AFK duration (seconds).
     */
    private static final int MIN_AFK_DURATION_SECONDS = 3;
    
    /**
     * Maximum AFK duration (seconds).
     */
    private static final int MAX_AFK_DURATION_SECONDS = 15;
    
    // === External distraction ===
    
    /**
     * External distractions per hour (base rate).
     */
    private static final double DISTRACTIONS_PER_HOUR = 4.0;
    
    /**
     * Minimum distraction duration (seconds).
     */
    private static final int MIN_DISTRACTION_SECONDS = 2;
    
    /**
     * Maximum distraction duration (seconds).
     */
    private static final int MAX_DISTRACTION_SECONDS = 15;
    
    /**
     * Probability of distraction when chat message appears.
     */
    private static final double CHAT_DISTRACTION_PROBABILITY = 0.30;
    
    // === Event processing lag when DISTRACTED ===
    
    private static final long MIN_EVENT_LAG_MS = 200;
    private static final long MAX_EVENT_LAG_MS = 800;

    // === Dependencies ===
    
    // Using Provider to break circular dependency:
    // GameStateService -> AttentionModel -> BotActivityTracker -> TaskExecutor -> TaskContext -> GameStateService
    private final Provider<BotActivityTracker> activityTrackerProvider;
    private final Randomization randomization;
    
    // === State ===
    
    /**
     * Current attention state.
     */
    @Getter
    private volatile AttentionState currentState = AttentionState.FOCUSED;
    
    /**
     * Time of last state transition.
     */
    private Instant lastTransitionTime = Instant.now();
    
    /**
     * Scheduled next transition time.
     */
    private Instant nextTransitionTime;
    
    /**
     * If in AFK state, when it should end.
     */
    private Instant afkEndTime = null;
    
    /**
     * If external distraction triggered, when it ends.
     */
    private Instant distractionEndTime = null;
    
    /**
     * Time of last distraction check (for rate limiting).
     */
    private Instant lastDistractionCheck = Instant.now();
    
    /**
     * Whether currently in an externally-triggered distraction.
     */
    @Getter
    private volatile boolean inExternalDistraction = false;

    @Inject
    public AttentionModel(Provider<BotActivityTracker> activityTrackerProvider, Randomization randomization) {
        this.activityTrackerProvider = activityTrackerProvider;
        this.randomization = randomization;
        scheduleNextTransition();
        log.info("AttentionModel initialized");
    }

    /**
     * Constructor for testing.
     */
    public AttentionModel(Provider<BotActivityTracker> activityTrackerProvider, Randomization randomization,
                          AttentionState initialState) {
        this.activityTrackerProvider = activityTrackerProvider;
        this.randomization = randomization;
        this.currentState = initialState;
        scheduleNextTransition();
    }

    // ========================================================================
    // Game Tick Handler
    // ========================================================================

    /**
     * Update attention state each game tick.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        tick();
    }

    /**
     * Manual tick for when not using event subscription.
     */
    public void tick() {
        Instant now = Instant.now();
        
        // Check if AFK period has ended
        if (currentState == AttentionState.AFK && afkEndTime != null && now.isAfter(afkEndTime)) {
            exitAFK();
        }
        
        // Check if external distraction has ended
        if (inExternalDistraction && distractionEndTime != null && now.isAfter(distractionEndTime)) {
            endExternalDistraction();
        }
        
        // Check for scheduled state transition
        if (nextTransitionTime != null && now.isAfter(nextTransitionTime)) {
            performStateTransition();
        }
        
        // Check for random external distraction
        checkForRandomDistraction();
    }

    // ========================================================================
    // State Transitions
    // ========================================================================

    private void performStateTransition() {
        AttentionState newState = selectNextState();
        
        // Validate transition is allowed given current activity
        if (newState == AttentionState.AFK && !canEnterAFK()) {
            // Can't go AFK, stay in FOCUSED or DISTRACTED
            newState = randomization.chance(0.7) ? AttentionState.FOCUSED : AttentionState.DISTRACTED;
        }
        
        transitionTo(newState);
        scheduleNextTransition();
    }

    private AttentionState selectNextState() {
        // Get base weights
        double focusedWeight = AttentionState.FOCUSED.getBaseWeight();
        double distractedWeight = AttentionState.DISTRACTED.getBaseWeight();
        double afkWeight = AttentionState.AFK.getBaseWeight();
        
        // Modify weights based on current state (tend to stay in current state a bit)
        switch (currentState) {
            case FOCUSED:
                focusedWeight *= 1.2;  // Slight preference to stay focused
                break;
            case DISTRACTED:
                distractedWeight *= 1.1;
                break;
            case AFK:
                // AFK is always temporary, don't weight toward staying
                afkWeight *= 0.5;
                break;
        }
        
        // HCIM: reduce AFK probability
        BotActivityTracker tracker = activityTrackerProvider != null ? activityTrackerProvider.get() : null;
        if (tracker != null && tracker.getAccountType().isHardcore()) {
            afkWeight *= 0.3;  // 70% less likely to go AFK
        }
        
        // During combat: no AFK, reduce distracted
        if (tracker != null && tracker.getCurrentActivity().isCombat()) {
            afkWeight = 0;
            distractedWeight *= 0.5;
        }
        
        // Normalize and select
        double total = focusedWeight + distractedWeight + afkWeight;
        double[] weights = {
                focusedWeight / total,
                distractedWeight / total,
                afkWeight / total
        };
        
        int selected = randomization.weightedChoice(weights);
        return AttentionState.values()[selected];
    }

    private void transitionTo(AttentionState newState) {
        AttentionState oldState = currentState;
        currentState = newState;
        lastTransitionTime = Instant.now();
        
        if (newState == AttentionState.AFK) {
            // Schedule AFK end
            int afkDuration = randomization.uniformRandomInt(
                    MIN_AFK_DURATION_SECONDS, MAX_AFK_DURATION_SECONDS);
            afkEndTime = Instant.now().plusSeconds(afkDuration);
            log.debug("Entering AFK for {} seconds", afkDuration);
        } else {
            afkEndTime = null;
        }
        
        if (oldState != newState) {
            log.debug("Attention state transition: {} -> {}", oldState, newState);
        }
    }

    private void exitAFK() {
        // Transition to FOCUSED or DISTRACTED
        AttentionState newState = randomization.chance(0.75) 
                ? AttentionState.FOCUSED 
                : AttentionState.DISTRACTED;
        
        transitionTo(newState);
        log.debug("Exited AFK -> {}", newState);
    }

    /**
     * Schedule the next attention state transition using log-normal distribution.
     * 
     * Log-normal is more realistic than uniform because human attention spans have:
     * - A natural "typical" duration (the median, ~90 seconds)
     * - Occasional much longer focus periods (the fat tail)
     * - No very short periods (the left bound)
     * 
     * Parameters chosen to give:
     * - Median: ~90 seconds
     * - Mean: ~107 seconds  
     * - 95th percentile: ~220 seconds
     * - Rare outliers up to 10 minutes (capped)
     */
    private void scheduleNextTransition() {
        // Log-normal parameters: μ=4.5, σ=0.6
        // This gives median≈90s, mean≈107s, with fat tail to 300s+
        double logNormalMu = 4.5;
        double logNormalSigma = 0.6;
        
        long intervalSeconds = randomization.logNormalRandomLong(
                logNormalMu, logNormalSigma,
                MIN_TRANSITION_INTERVAL_SECONDS, 600); // Cap at 10 minutes
        
        nextTransitionTime = Instant.now().plusSeconds(intervalSeconds);
    }

    // ========================================================================
    // External Distractions
    // ========================================================================

    /**
     * Check if a random external distraction should occur.
     */
    private void checkForRandomDistraction() {
        if (inExternalDistraction || currentState == AttentionState.AFK) {
            return;
        }
        
        // Rate limit checks to roughly once per game tick (600ms)
        Instant now = Instant.now();
        if (Duration.between(lastDistractionCheck, now).toMillis() < 600) {
            return;
        }
        lastDistractionCheck = now;
        
        // Calculate probability based on distractions per hour
        // ~6000 ticks per hour, so probability per tick = rate / 6000
        double probabilityPerTick = DISTRACTIONS_PER_HOUR / 6000.0;
        
        if (randomization.chance(probabilityPerTick) && canEnterAFK()) {
            triggerExternalDistraction();
        }
    }

    /**
     * Trigger an external distraction (random event).
     */
    public void triggerExternalDistraction() {
        if (inExternalDistraction || !canEnterAFK()) {
            return;
        }
        
        inExternalDistraction = true;
        int duration = randomization.uniformRandomInt(
                MIN_DISTRACTION_SECONDS, MAX_DISTRACTION_SECONDS);
        distractionEndTime = Instant.now().plusSeconds(duration);
        
        // Distraction puts us in AFK state temporarily
        currentState = AttentionState.AFK;
        afkEndTime = distractionEndTime;
        
        log.debug("External distraction triggered for {} seconds", duration);
    }

    /**
     * Called when a chat message is received - may trigger distraction.
     */
    public void onChatMessage() {
        if (inExternalDistraction || !canEnterAFK()) {
            return;
        }
        
        if (randomization.chance(CHAT_DISTRACTION_PROBABILITY)) {
            triggerExternalDistraction();
            log.debug("Chat message triggered distraction");
        }
    }

    private void endExternalDistraction() {
        inExternalDistraction = false;
        distractionEndTime = null;
        
        // Return to focused or distracted
        currentState = randomization.chance(0.8) 
                ? AttentionState.FOCUSED 
                : AttentionState.DISTRACTED;
        afkEndTime = null;
        
        log.debug("External distraction ended -> {}", currentState);
    }

    // ========================================================================
    // Context-Aware Checks
    // ========================================================================

    /**
     * Check if AFK state is allowed given current activity and account type.
     * 
     * @return true if AFK is safe
     */
    public boolean canEnterAFK() {
        BotActivityTracker tracker = activityTrackerProvider != null ? activityTrackerProvider.get() : null;
        if (tracker == null) {
            return true;
        }
        return tracker.canEnterAFK();
    }

    /**
     * Check if the player should process events with lag.
     * When DISTRACTED, events have 200-800ms extra processing delay.
     * 
     * @return true if event lag should be applied
     */
    public boolean shouldApplyEventLag() {
        return currentState == AttentionState.DISTRACTED;
    }

    // ========================================================================
    // Effect Multipliers
    // ========================================================================

    /**
     * Get the cognitive load level for motor coupling.
     * 
     * Why this matters for motor control:
     * The brain has limited cognitive bandwidth. When processing complex game situations
     * (boss mechanics, PvP decisions) or when attention is divided (checking phone),
     * fewer neural resources remain for fine motor control. This manifests as increased
     * tremor and reduced precision - the "dual-task interference" effect.
     * 
     * Research basis: Woollacott & Shumway-Cook (2002) found cognitive load increases
     * motor variability by 20-60%. This is distinct from fatigue (muscle tiredness).
     * 
     * @return cognitive load level (0.0 = minimal, 1.0 = maximum)
     */
    public double getCognitiveLoad() {
        // Why distraction INCREASES cognitive load for motor tasks:
        // A player watching YouTube while playing is mentally juggling two tasks.
        // The game's motor control competes with the other task for neural bandwidth.
        double attentionLoad = switch (currentState) {
            case FOCUSED -> 0.1;   // Baseline - even focused players think about the game
            case DISTRACTED -> 0.5; // Significant - attention split between game and distraction
            case AFK -> 0.0;        // Not acting, motor load is irrelevant
        };
        
        // Why complex activities increase cognitive load:
        // Boss fights require tracking mechanics, cooldowns, positioning - leaving
        // fewer resources for precise mouse control. AFK fishing needs almost none.
        double activityLoad = 0.0;
        BotActivityTracker tracker = activityTrackerProvider != null ? activityTrackerProvider.get() : null;
        if (tracker != null) {
            activityLoad = switch (tracker.getCurrentActivity()) {
                case CRITICAL -> 0.5;      // Boss/PvP: tracking mechanics, prayer flicking, etc.
                case HIGH -> 0.3;          // Combat: target selection, eating, positioning
                case MEDIUM -> 0.15;       // Questing: reading dialogue, navigating
                case LOW, AFK_COMBAT -> 0.05; // Woodcutting, NMZ: almost autopilot
                case IDLE -> 0.0;          // Standing around: no cognitive demand
            };
        }
        
        // Simple additive combination, capped at 1.0
        // Both factors draw from the same neural bandwidth pool
        return Math.min(1.0, attentionLoad + activityLoad);
    }

    /**
     * Get the delay multiplier based on current attention state.
     * FOCUSED: 1.0, DISTRACTED: 1.3, AFK: 0.0 (no actions)
     * 
     * @return delay multiplier
     */
    public double getDelayMultiplier() {
        return currentState.getDelayMultiplier();
    }

    /**
     * Get the precision multiplier based on current attention state.
     * FOCUSED: 1.0, DISTRACTED: 0.9, AFK: 0.0
     * 
     * @return precision multiplier
     */
    public double getPrecisionMultiplier() {
        return currentState.getPrecisionMultiplier();
    }

    /**
     * Get the event processing lag for the current state.
     * 
     * @return lag in milliseconds (randomized within range)
     */
    public long getEventProcessingLag() {
        if (currentState != AttentionState.DISTRACTED) {
            return 0;
        }
        return randomization.uniformRandomLong(MIN_EVENT_LAG_MS, MAX_EVENT_LAG_MS);
    }

    /**
     * Get the AFK duration if currently in AFK state.
     * 
     * @return duration until AFK ends, or ZERO if not in AFK
     */
    public Duration getAFKDuration() {
        if (currentState != AttentionState.AFK || afkEndTime == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(Instant.now(), afkEndTime);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * Check if actions can be performed in the current state.
     * 
     * @return true if player can act
     */
    public boolean canAct() {
        return currentState.canAct();
    }

    // ========================================================================
    // Time Since State
    // ========================================================================

    /**
     * Get time since last state transition.
     * 
     * @return duration in current state
     */
    public Duration getTimeInCurrentState() {
        return Duration.between(lastTransitionTime, Instant.now());
    }

    /**
     * Get time until next scheduled transition.
     * 
     * @return duration until transition, or ZERO if imminent
     */
    public Duration getTimeUntilNextTransition() {
        if (nextTransitionTime == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(Instant.now(), nextTransitionTime);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    // ========================================================================
    // Manual Control (for testing)
    // ========================================================================

    /**
     * Force a specific attention state. Use for testing only.
     * 
     * @param state the state to set
     */
    public void forceState(AttentionState state) {
        transitionTo(state);
        log.debug("Attention state forced to {}", state);
    }

    /**
     * Reset to focused state.
     */
    public void reset() {
        currentState = AttentionState.FOCUSED;
        lastTransitionTime = Instant.now();
        afkEndTime = null;
        inExternalDistraction = false;
        distractionEndTime = null;
        scheduleNextTransition();
        log.info("Attention model reset to FOCUSED");
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Get a summary of current attention state.
     * 
     * @return summary string
     */
    public String getSummary() {
        return String.format(
                "Attention[state=%s, inState=%ds, nextTransition=%ds, distraction=%s]",
                currentState,
                getTimeInCurrentState().toSeconds(),
                getTimeUntilNextTransition().toSeconds(),
                inExternalDistraction
        );
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

