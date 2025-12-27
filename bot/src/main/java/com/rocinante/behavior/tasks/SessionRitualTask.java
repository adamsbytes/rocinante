package com.rocinante.behavior.tasks;

import com.rocinante.behavior.BreakType;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes session start rituals based on player profile.
 * 
 * Per REQUIREMENTS.md Section 3.5.1:
 * - 2-4 account-specific actions at session start
 * - 80% probability to execute each ritual
 * 
 * Common rituals:
 * - Bank check: Open bank, scan tabs, close
 * - Skill tab inspection: Check specific skills
 * - Friends list check: Scan for online friends
 * - Equipment review: Hover over worn items
 * - Inventory organization: Rearrange items
 * 
 * Only executed on fresh sessions (>15 min since last logout).
 */
@Slf4j
public class SessionRitualTask extends BehavioralTask {

    private final PlayerProfile playerProfile;
    private final Randomization randomization;
    private final HumanTimer humanTimer;
    
    private Instant startTime;
    private boolean started = false;
    
    private List<String> ritualsToExecute;
    private int currentRitualIndex = 0;

    public SessionRitualTask(PlayerProfile playerProfile,
                             Randomization randomization,
                             HumanTimer humanTimer) {
        super(BreakType.MICRO_PAUSE, Duration.ofSeconds(30)); // Rituals are quick
        this.playerProfile = playerProfile;
        this.randomization = randomization;
        this.humanTimer = humanTimer;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (!started) {
            startRituals();
        }
        
        // Execute rituals one at a time
        if (currentRitualIndex >= ritualsToExecute.size()) {
            transitionTo(TaskState.COMPLETED);
            return;
        }
        
        String ritual = ritualsToExecute.get(currentRitualIndex);
        
        try {
            executeRitual(ctx, ritual);
        } catch (Exception e) {
            log.trace("Ritual {} interrupted: {}", ritual, e.getMessage());
        }
        
        currentRitualIndex++;
    }

    private void startRituals() {
        startTime = Instant.now();
        started = true;
        
        // Filter rituals by execution probability
        ritualsToExecute = new ArrayList<>();
        double executionProb = playerProfile.getRitualExecutionProbability();
        
        for (String ritual : playerProfile.getSessionRituals()) {
            if (randomization.chance(executionProb)) {
                ritualsToExecute.add(ritual);
            }
        }
        
        log.info("Starting session rituals: {} of {} selected",
                ritualsToExecute.size(), playerProfile.getSessionRituals().size());
    }

    private void executeRitual(TaskContext ctx, String ritual) throws Exception {
        log.debug("Executing session ritual: {}", ritual);
        
        switch (ritual) {
            case "BANK_CHECK":
                // Would open bank, look around, close
                // Simplified: just idle movement
                ctx.getMouseController().performIdleBehavior().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(2000, 6000));
                break;
                
            case "SKILL_TAB_CHECK":
                // Would open skills tab, hover training skills
                ctx.getMouseController().performIdleBehavior().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(1000, 3000));
                break;
                
            case "FRIENDS_LIST_CHECK":
                // Would open friends list, scan
                ctx.getMouseController().performIdleBehavior().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(1000, 2000));
                break;
                
            case "EQUIPMENT_REVIEW":
                // Would open equipment tab, hover items
                ctx.getMouseController().performIdleBehavior().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(1000, 2500));
                break;
                
            case "INVENTORY_ORGANIZE":
                // Would rearrange inventory items
                ctx.getMouseController().performIdleBehavior().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(2000, 5000));
                break;
                
            case "WORLD_CHECK":
                // Would open world switcher, check population
                ctx.getMouseController().performIdleBehavior().get();
                humanTimer.sleepSync(randomization.uniformRandomLong(1000, 3000));
                break;
                
            default:
                // Unknown ritual, brief pause
                humanTimer.sleepSync(randomization.uniformRandomLong(500, 1500));
                break;
        }
        
        // Brief pause between rituals
        humanTimer.sleepSync(randomization.uniformRandomLong(300, 800));
    }

    @Override
    public void onComplete(TaskContext ctx) {
        Duration actualDuration = Duration.between(startTime, Instant.now());
        log.info("Session rituals completed in {} seconds", actualDuration.toSeconds());
    }

    @Override
    public void onFail(TaskContext ctx, Exception e) {
        log.debug("Session rituals interrupted");
    }

    @Override
    public String getDescription() {
        return "Session start rituals";
    }
}

