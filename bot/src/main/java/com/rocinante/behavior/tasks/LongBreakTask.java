package com.rocinante.behavior.tasks;

import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.BreakType;
import com.rocinante.behavior.FatigueModel;
import com.rocinante.behavior.LogoutHandler;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * A long break (5-20 minutes) with extended idle or logout.
 * 
 * Per REQUIREMENTS.md Section 4.3.1:
 * - Duration: 5-20 minutes
 * - Trigger: Every 60-120 minutes (80% chance)
 * - Behavior: Extended AFK, may logout
 * 
 * Long breaks simulate:
 * - Meal breaks
 * - Phone calls
 * - Other real-life interruptions
 */
@Slf4j
public class LongBreakTask extends BehavioralTask {

    private final BreakScheduler breakScheduler;
    private final FatigueModel fatigueModel;
    private final PlayerProfile playerProfile;
    private final Randomization randomization;
    private final HumanTimer humanTimer;
    
    /**
     * Whether this break should include logout.
     */
    private final boolean shouldLogout;
    
    private Instant startTime;
    private boolean started = false;
    private boolean logoutInitiated = false;

    public LongBreakTask(BreakScheduler breakScheduler,
                         FatigueModel fatigueModel,
                         PlayerProfile playerProfile,
                         Randomization randomization,
                         HumanTimer humanTimer) {
        super(BreakType.LONG_BREAK, generateDuration(randomization));
        this.breakScheduler = breakScheduler;
        this.fatigueModel = fatigueModel;
        this.playerProfile = playerProfile;
        this.randomization = randomization;
        this.humanTimer = humanTimer;
        
        // 40% chance to logout during long break
        this.shouldLogout = randomization.chance(0.40);
    }

    private static Duration generateDuration(Randomization randomization) {
        int seconds = randomization.uniformRandomInt(
                (int) BreakType.LONG_BREAK.getMinDuration().toSeconds(),
                (int) BreakType.LONG_BREAK.getMaxDuration().toSeconds()
        );
        return Duration.ofSeconds(seconds);
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (!started) {
            startBreak(ctx);
        }
        
        Duration elapsed = Duration.between(startTime, Instant.now());
        
        // Check if break is complete
        if (elapsed.compareTo(targetDuration) >= 0) {
            transitionTo(TaskState.COMPLETED);
            return;
        }
        
        // If logging out, just wait
        if (logoutInitiated) {
            return;
        }
        
        // Occasionally do idle behavior (10% chance per tick)
        if (randomization.chance(0.001)) {  // ~0.1% per tick = occasional
            try {
                ctx.getMouseController().performIdleBehavior().get();
            } catch (Exception e) {
                log.trace("Idle behavior interrupted: {}", e.getMessage());
            }
        }
    }

    private void startBreak(TaskContext ctx) {
        startTime = Instant.now();
        started = true;
        fatigueModel.startBreak();
        
        log.info("Starting long break for {} minutes (logout={})",
                targetDuration.toMinutes(), shouldLogout);
        
        if (shouldLogout) {
            initiateLogout(ctx);
        } else {
            // Just move mouse to idle position
            try {
                ctx.getMouseController().performIdleBehavior().get();
            } catch (Exception e) {
                log.trace("Initial idle behavior failed: {}", e.getMessage());
            }
        }
    }

    private void initiateLogout(TaskContext ctx) {
        logoutInitiated = true;
        
        LogoutHandler logoutHandler = ctx.getLogoutHandler();
        if (logoutHandler == null) {
            log.warn("LogoutHandler not available, skipping logout");
            return;
        }
        
        log.debug("Initiating humanized logout");
        
        // Execute logout with normal context (break time)
        CompletableFuture<Boolean> logoutFuture = logoutHandler.executeLogout(
                LogoutHandler.LogoutContext.NORMAL);
        
        logoutFuture.whenComplete((success, ex) -> {
            if (ex != null) {
                log.warn("Logout failed: {}", ex.getMessage());
            } else if (success) {
                log.info("Logout completed successfully");
            } else {
                log.warn("Logout returned false");
            }
        });
    }

    @Override
    public void onComplete(TaskContext ctx) {
        Duration actualDuration = Duration.between(startTime, Instant.now());
        fatigueModel.endBreak();
        breakScheduler.onBreakCompleted(BreakType.LONG_BREAK, actualDuration);
        
        if (logoutInitiated) {
            // Would need to handle reconnection here
            log.info("Long break with logout completed after {} minutes", 
                    actualDuration.toMinutes());
        } else {
            log.info("Long break completed after {} minutes", actualDuration.toMinutes());
        }
    }

    @Override
    public void onFail(TaskContext ctx, Exception e) {
        fatigueModel.endBreak();
        log.debug("Long break interrupted");
    }
}

