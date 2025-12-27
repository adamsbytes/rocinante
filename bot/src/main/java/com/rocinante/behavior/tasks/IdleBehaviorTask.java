package com.rocinante.behavior.tasks;

import com.rocinante.behavior.BreakType;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;

/**
 * General idle behavior task for attention-based AFK periods.
 * 
 * Per REQUIREMENTS.md Section 4.4.1:
 * - AFK periods are 3-15 seconds
 * - Minimal or no activity during AFK
 * 
 * This task is used when the AttentionModel triggers an AFK state.
 * Unlike breaks, this represents momentary inattention rather than
 * an intentional break.
 */
@Slf4j
public class IdleBehaviorTask extends BehavioralTask {

    private final Randomization randomization;
    
    private Instant startTime;
    private boolean started = false;

    /**
     * Create an idle behavior task with specified duration.
     * 
     * @param duration how long to stay idle
     * @param randomization randomization instance
     */
    public IdleBehaviorTask(Duration duration, Randomization randomization) {
        super(BreakType.MICRO_PAUSE, duration); // Treat as micro-pause for tracking
        this.randomization = randomization;
    }

    /**
     * Create an idle behavior task with random duration (3-15 seconds).
     * 
     * @param randomization randomization instance
     */
    public IdleBehaviorTask(Randomization randomization) {
        this(generateDuration(randomization), randomization);
    }

    private static Duration generateDuration(Randomization randomization) {
        // AFK periods are 3-15 seconds per spec
        int seconds = randomization.uniformRandomInt(3, 15);
        return Duration.ofSeconds(seconds);
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (!started) {
            startTime = Instant.now();
            started = true;
            log.trace("Idle behavior started for {} seconds", targetDuration.toSeconds());
        }
        
        Duration elapsed = Duration.between(startTime, Instant.now());
        
        // Check if idle period is complete
        if (elapsed.compareTo(targetDuration) >= 0) {
            transitionTo(TaskState.COMPLETED);
            return;
        }
        
        // Very small chance of tiny mouse movement (5% chance total during idle)
        // This is spread across multiple ticks
        double chancePerTick = 0.05 / (targetDuration.toSeconds() * 1.67); // ~1.67 ticks per second
        if (randomization.chance(chancePerTick)) {
            try {
                // Just a tiny drift, not full idle behavior
                ctx.getMouseController().performIdleBehavior().get();
            } catch (Exception e) {
                // Ignore - we're just idling
            }
        }
    }

    @Override
    public void onComplete(TaskContext ctx) {
        Duration actualDuration = Duration.between(startTime, Instant.now());
        log.trace("Idle behavior completed after {} seconds", actualDuration.toSeconds());
    }

    @Override
    public void onFail(TaskContext ctx, Exception e) {
        log.trace("Idle behavior interrupted");
    }

    @Override
    public String getDescription() {
        return String.format("Idle (%ds)", targetDuration.toSeconds());
    }
}

