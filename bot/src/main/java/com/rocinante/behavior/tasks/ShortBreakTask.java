package com.rocinante.behavior.tasks;

import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.BreakType;
import com.rocinante.behavior.FatigueModel;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import com.rocinante.util.WidgetInteractionHelpers;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A short break (30-180 seconds) with various idle activities.
 * 
 * Per REQUIREMENTS.md Section 4.3.1 and 3.5.4:
 * - Duration: 30-180 seconds
 * - Trigger: Every 15-40 minutes (60% chance) or fatigue threshold
 * - Behavior: Open tabs, check stats, camera drift, etc.
 * 
 * Activities are selected based on PlayerProfile preferences.
 */
@Slf4j
public class ShortBreakTask extends BehavioralTask {

    private final BreakScheduler breakScheduler;
    private final FatigueModel fatigueModel;
    private final PlayerProfile playerProfile;
    private final Randomization randomization;
    private final HumanTimer humanTimer;
    
    private Instant startTime;
    private boolean started = false;
    
    /**
     * Activities to perform during this break.
     */
    private List<String> scheduledActivities;
    private int currentActivityIndex = 0;
    private Instant nextActivityTime;

    public ShortBreakTask(BreakScheduler breakScheduler,
                          FatigueModel fatigueModel,
                          PlayerProfile playerProfile,
                          Randomization randomization,
                          HumanTimer humanTimer) {
        super(BreakType.SHORT_BREAK, generateDuration(randomization));
        this.breakScheduler = breakScheduler;
        this.fatigueModel = fatigueModel;
        this.playerProfile = playerProfile;
        this.randomization = randomization;
        this.humanTimer = humanTimer;
    }

    private static Duration generateDuration(Randomization randomization) {
        int seconds = randomization.uniformRandomInt(
                (int) BreakType.SHORT_BREAK.getMinDuration().toSeconds(),
                (int) BreakType.SHORT_BREAK.getMaxDuration().toSeconds()
        );
        return Duration.ofSeconds(seconds);
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (!started) {
            // Clear predictive hover - player is taking a break
            if (ctx.getPredictiveHoverManager() != null) {
                ctx.getPredictiveHoverManager().clearHover();
            }
            startBreak();
        }
        
        Duration elapsed = Duration.between(startTime, Instant.now());
        
        // Check if break is complete
        if (elapsed.compareTo(targetDuration) >= 0) {
            transitionTo(TaskState.COMPLETED);
            return;
        }
        
        // Execute activities at scheduled times
        if (nextActivityTime != null && Instant.now().isAfter(nextActivityTime)) {
            executeNextActivity(ctx);
        }
    }

    private void startBreak() {
        startTime = Instant.now();
        started = true;
        fatigueModel.startBreak();
        
        // Schedule activities for this break
        scheduledActivities = planActivities();
        scheduleNextActivity();
        
        log.debug("Starting short break for {} seconds with {} activities",
                targetDuration.toSeconds(), scheduledActivities.size());
    }

    /**
     * Plan which activities to do during this break.
     */
    private List<String> planActivities() {
        List<String> activities = new ArrayList<>();
        
        // Calculate how many activities fit in the break duration
        // Average activity takes 5-15 seconds
        int maxActivities = (int) (targetDuration.toSeconds() / 10);
        int numActivities = randomization.uniformRandomInt(1, Math.max(2, maxActivities));
        
        // 30% chance to just AFK (do nothing)
        if (randomization.chance(0.30)) {
            activities.add("PURE_AFK");
            return activities;
        }
        
        // Select activities from profile preferences
        for (int i = 0; i < numActivities; i++) {
            String activity = playerProfile.selectBreakActivity();
            activities.add(activity);
        }
        
        return activities;
    }

    private void scheduleNextActivity() {
        if (currentActivityIndex >= scheduledActivities.size()) {
            nextActivityTime = null;
            return;
        }
        
        // Space activities throughout the break
        long totalSeconds = targetDuration.toSeconds();
        long intervalSeconds = totalSeconds / (scheduledActivities.size() + 1);
        long offsetSeconds = intervalSeconds * (currentActivityIndex + 1);
        
        // Add some variance
        offsetSeconds += randomization.uniformRandomInt(-5, 5);
        
        nextActivityTime = startTime.plusSeconds(Math.max(1, offsetSeconds));
    }

    private void executeNextActivity(TaskContext ctx) {
        if (currentActivityIndex >= scheduledActivities.size()) {
            return;
        }
        
        String activity = scheduledActivities.get(currentActivityIndex);
        log.debug("Executing break activity: {}", activity);
        
        try {
            performActivity(ctx, activity);
            playerProfile.reinforceBreakActivity(activity);
        } catch (Exception e) {
            log.debug("Activity {} interrupted: {}", activity, e.getMessage());
        }
        
        currentActivityIndex++;
        scheduleNextActivity();
    }

    /**
     * Perform a specific break activity.
     */
    private void performActivity(TaskContext ctx, String activity) throws Exception {
        switch (activity) {
            case "SKILLS_TAB_HOVER":
                // Open skills tab and hover over it
                WidgetInteractionHelpers.openTabAsync(ctx, WidgetInteractionHelpers.TAB_SKILLS, null).get();
                humanTimer.sleepSync(randomization.uniformRandomLong(300, 600));
                ctx.getMouseController().performIdleBehavior().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(1000, 3000));
                break;
                
            case "INVENTORY_HOVER":
                ctx.getMouseController().performIdleBehavior().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(500, 2000));
                break;
                
            case "EQUIPMENT_CHECK":
                // Open equipment tab and hover over it
                WidgetInteractionHelpers.openTabAsync(ctx, WidgetInteractionHelpers.TAB_EQUIPMENT, null).get();
                humanTimer.sleepSync(randomization.uniformRandomLong(300, 600));
                ctx.getMouseController().performIdleBehavior().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(1000, 2500));
                break;
                
            case "FRIENDS_LIST_CHECK":
                // Open friends tab and hover over it
                WidgetInteractionHelpers.openTabAsync(ctx, WidgetInteractionHelpers.TAB_FRIENDS, null).get();
                humanTimer.sleepSync(randomization.uniformRandomLong(300, 600));
                ctx.getMouseController().performIdleBehavior().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(1000, 2000));
                break;
                
            case "CAMERA_DRIFT":
                // Perform slow camera rotation
                ctx.getCameraController().performIdleDrift().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(1000, 3000));
                break;
                
            case "PURE_AFK":
                // Just wait
                humanTimer.sleepSync(randomization.uniformRandomLong(3000, 8000));
                break;
                
            case "XP_TRACKER_HOVER":
                ctx.getMouseController().performIdleBehavior().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(500, 1500));
                break;
                
            case "CHAT_SCROLL":
                ctx.getMouseController().performIdleBehavior().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(1000, 3000));
                break;
                
            case "MINIMAP_DRAG":
                ctx.getMouseController().performIdleBehavior().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(1000, 2000));
                break;
                
            default:
                // Unknown activity, just idle
                ctx.getMouseController().performIdleBehavior().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(1000, 2000));
                break;
        }
    }

    @Override
    public void onComplete(TaskContext ctx) {
        Duration actualDuration = Duration.between(startTime, Instant.now());
        fatigueModel.endBreak();
        breakScheduler.onBreakCompleted(BreakType.SHORT_BREAK, actualDuration);
        log.debug("Short break completed after {} seconds", actualDuration.toSeconds());
    }

    @Override
    public void onFail(TaskContext ctx, Exception e) {
        fatigueModel.endBreak();
        log.debug("Short break interrupted");
    }
}

