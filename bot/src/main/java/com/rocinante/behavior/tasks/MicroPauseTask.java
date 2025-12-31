package com.rocinante.behavior.tasks;

import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.BreakType;
import com.rocinante.behavior.FatigueModel;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;

/**
 * A very short pause (2-8 seconds) simulating momentary distraction.
 * 
 * Per REQUIREMENTS.md Section 4.3.1:
 * - Duration: 2-8 seconds
 * - Trigger: Every 30-90 actions (30% chance)
 * - Behavior: Mouse stationary or small drift
 * 
 * This simulates things like:
 * - Glancing at phone
 * - Brief mental wandering
 * - Reading chat message
 */
@Slf4j
public class MicroPauseTask extends BehavioralTask {

    private final BreakScheduler breakScheduler;
    private final FatigueModel fatigueModel;
    private final Randomization randomization;
    
    private Instant startTime;
    private boolean started = false;
    private boolean driftPerformed = false;

    /**
     * Create a micro-pause with random duration within spec range.
     */
    public MicroPauseTask(BreakScheduler breakScheduler, 
                          FatigueModel fatigueModel,
                          Randomization randomization) {
        super(BreakType.MICRO_PAUSE, generateDuration(randomization));
        this.breakScheduler = breakScheduler;
        this.fatigueModel = fatigueModel;
        this.randomization = randomization;
    }

    private static Duration generateDuration(Randomization randomization) {
        int seconds = randomization.uniformRandomInt(
                (int) BreakType.MICRO_PAUSE.getMinDuration().toSeconds(),
                (int) BreakType.MICRO_PAUSE.getMaxDuration().toSeconds()
        );
        return Duration.ofSeconds(seconds);
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (!started) {
            startTime = Instant.now();
            started = true;
            fatigueModel.startBreak();
            
            // Clear predictive hover - player is taking a break
            if (ctx.getPredictiveHoverManager() != null) {
                ctx.getPredictiveHoverManager().clearHover();
            }
            
            log.debug("Starting micro-pause for {} seconds", targetDuration.toSeconds());
        }
        
        Duration elapsed = Duration.between(startTime, Instant.now());
        
        // Maybe do a small mouse drift (20% chance, once per pause)
        if (!driftPerformed && randomization.chance(0.20)) {
            try {
                ctx.getMouseController().performIdleBehavior().get();
            } catch (Exception e) {
                log.debug("Idle behavior interrupted: {}", e.getMessage());
            }
            driftPerformed = true;
        }
        
        // Check if pause is complete
        if (elapsed.compareTo(targetDuration) >= 0) {
            transitionTo(TaskState.COMPLETED);
        }
    }

    @Override
    public void onComplete(TaskContext ctx) {
        Duration actualDuration = Duration.between(startTime, Instant.now());
        fatigueModel.endBreak();
        breakScheduler.onBreakCompleted(BreakType.MICRO_PAUSE, actualDuration);
        log.debug("Micro-pause completed after {} seconds", actualDuration.toSeconds());
    }

    @Override
    public void onFail(TaskContext ctx, Exception e) {
        fatigueModel.endBreak();
        log.debug("Micro-pause interrupted");
    }
}

