package com.rocinante.behavior.idle;

import com.rocinante.behavior.AttentionModel;
import com.rocinante.behavior.AttentionState;
import com.rocinante.behavior.BotActivityTracker;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.behavior.tasks.IdleBehaviorTask;
import com.rocinante.tasks.Task;
import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Supplies idle habit tasks based on player profile frequencies.
 * 
 * Per REQUIREMENTS.md Section 3.5.2 (Mid-Session Habits):
 * - Right-click player inspection: 0-5 times per hour
 * - XP checking patterns: 0-15 times per hour
 * 
 * This supplier is used by TaskExecutor.setIdleTaskSupplier() to inject
 * human-like idle behaviors when the task queue is empty.
 * 
 * The supplier tracks when behaviors were last performed and uses
 * exponential distribution to schedule the next occurrence based on
 * per-account frequencies from PlayerProfile.
 */
@Slf4j
public class IdleHabitSupplier implements Supplier<Task> {

    // === Behavior frequency tracking ===
    
    /**
     * Last time player inspection was performed.
     */
    private Instant lastPlayerInspection = Instant.now();
    
    /**
     * Last time XP check was performed.
     */
    private Instant lastXpCheck = Instant.now();
    
    /**
     * Last time idle behavior was performed (when in AFK attention state).
     */
    private Instant lastIdleBehavior = Instant.now();
    
    /**
     * Scheduled next player inspection time.
     */
    private Instant nextPlayerInspection;
    
    /**
     * Scheduled next XP check time.
     */
    private Instant nextXpCheck;
    
    // === Dependencies ===
    
    private final PlayerProfile playerProfile;
    private final Randomization randomization;
    private final AttentionModel attentionModel;
    private final BotActivityTracker activityTracker;

    /**
     * Create an idle habit supplier.
     * 
     * @param playerProfile the player profile for frequency preferences
     * @param randomization randomization for timing variance
     * @param attentionModel attention model for AFK state detection
     * @param activityTracker activity tracker for context awareness
     */
    public IdleHabitSupplier(PlayerProfile playerProfile,
                             Randomization randomization,
                             AttentionModel attentionModel,
                             BotActivityTracker activityTracker) {
        this.playerProfile = playerProfile;
        this.randomization = randomization;
        this.attentionModel = attentionModel;
        this.activityTracker = activityTracker;
        
        // Schedule initial habit times
        scheduleNextPlayerInspection();
        scheduleNextXpCheck();
        
        log.debug("IdleHabitSupplier initialized (inspection freq={}/hr, xp freq={}/hr)",
                String.format("%.1f", playerProfile.getPlayerInspectionFrequency()),
                String.format("%.1f", playerProfile.getXpCheckFrequency()));
    }

    @Override
    public Task get() {
        Instant now = Instant.now();
        
        // Check if we're in a critical activity that shouldn't be interrupted
        if (!activityTracker.canTakeBreak()) {
            return null;
        }
        
        // Check attention state - if AFK, return idle behavior instead of active habits
        if (attentionModel.getCurrentState() == AttentionState.AFK) {
            // AFK state - return idle behavior if enough time has passed
            if (Duration.between(lastIdleBehavior, now).toSeconds() > 5) {
                lastIdleBehavior = now;
                log.trace("Providing idle behavior task (AFK state)");
                return new IdleBehaviorTask(randomization);
            }
            return null;
        }
        
        // Check if player inspection is due
        if (nextPlayerInspection != null && now.isAfter(nextPlayerInspection)) {
            if (playerProfile.getPlayerInspectionFrequency() > 0) {
                lastPlayerInspection = now;
                scheduleNextPlayerInspection();
                log.trace("Providing player inspection task");
                return new PlayerInspectionBehavior(playerProfile, randomization);
            }
        }
        
        // Check if XP check is due
        if (nextXpCheck != null && now.isAfter(nextXpCheck)) {
            if (playerProfile.getXpCheckFrequency() > 0) {
                lastXpCheck = now;
                scheduleNextXpCheck();
                log.trace("Providing XP check task");
                return new XpCheckBehavior(playerProfile, randomization);
            }
        }
        
        // Nothing due - return null (no idle task)
        return null;
    }

    /**
     * Schedule the next player inspection based on profile frequency.
     * Uses exponential distribution for human-like variance.
     */
    private void scheduleNextPlayerInspection() {
        double frequencyPerHour = playerProfile.getPlayerInspectionFrequency();
        
        if (frequencyPerHour <= 0) {
            nextPlayerInspection = null;
            return;
        }
        
        // Convert frequency per hour to mean interval in seconds
        double meanIntervalSeconds = 3600.0 / frequencyPerHour;
        
        // Use exponential distribution for natural timing
        double lambda = 1.0 / meanIntervalSeconds;
        double intervalSeconds = randomization.exponentialRandom(lambda, 
                meanIntervalSeconds * 0.5,  // Min: 50% of mean
                meanIntervalSeconds * 2.0); // Max: 200% of mean
        
        nextPlayerInspection = Instant.now().plusSeconds((long) intervalSeconds);
        
        log.trace("Scheduled next player inspection in {} seconds", (long) intervalSeconds);
    }

    /**
     * Schedule the next XP check based on profile frequency.
     * Uses exponential distribution for human-like variance.
     */
    private void scheduleNextXpCheck() {
        double frequencyPerHour = playerProfile.getXpCheckFrequency();
        
        if (frequencyPerHour <= 0) {
            nextXpCheck = null;
            return;
        }
        
        // Convert frequency per hour to mean interval in seconds
        double meanIntervalSeconds = 3600.0 / frequencyPerHour;
        
        // Use exponential distribution for natural timing
        double lambda = 1.0 / meanIntervalSeconds;
        double intervalSeconds = randomization.exponentialRandom(lambda, 
                meanIntervalSeconds * 0.3,  // Min: 30% of mean (more frequent for XP checks)
                meanIntervalSeconds * 2.5); // Max: 250% of mean
        
        nextXpCheck = Instant.now().plusSeconds((long) intervalSeconds);
        
        log.trace("Scheduled next XP check in {} seconds", (long) intervalSeconds);
    }

    /**
     * Reset habit timers. Called when starting a new automation session.
     */
    public void reset() {
        Instant now = Instant.now();
        lastPlayerInspection = now;
        lastXpCheck = now;
        lastIdleBehavior = now;
        
        scheduleNextPlayerInspection();
        scheduleNextXpCheck();
        
        log.debug("IdleHabitSupplier reset");
    }

    /**
     * Get time until next player inspection.
     * 
     * @return duration until inspection, or Duration.ZERO if disabled
     */
    public Duration getTimeUntilPlayerInspection() {
        if (nextPlayerInspection == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(Instant.now(), nextPlayerInspection);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * Get time until next XP check.
     * 
     * @return duration until XP check, or Duration.ZERO if disabled
     */
    public Duration getTimeUntilXpCheck() {
        if (nextXpCheck == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(Instant.now(), nextXpCheck);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}

