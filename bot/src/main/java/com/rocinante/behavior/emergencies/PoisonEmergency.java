package com.rocinante.behavior.emergencies;

import com.rocinante.behavior.BotActivityTracker;
import com.rocinante.behavior.EmergencyCondition;
import com.rocinante.state.CombatState;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Emergency condition for poison/venom threatening player health.
 * 
 * Triggers when:
 * - Player is poisoned/venomed AND
 * - Health is below a threshold where poison damage could be lethal
 * 
 * HCIM have a higher trigger threshold (more conservative).
 */
@Slf4j
public class PoisonEmergency implements EmergencyCondition {

    /**
     * Health percentage threshold for normal accounts.
     */
    private static final double NORMAL_THRESHOLD = 0.30; // 30% HP
    
    /**
     * Health percentage threshold for HCIM (more conservative).
     */
    private static final double HCIM_THRESHOLD = 0.50; // 50% HP

    private final BotActivityTracker activityTracker;
    
    /**
     * Factory for creating cure tasks.
     */
    private final CureTaskFactory cureTaskFactory;

    public PoisonEmergency(BotActivityTracker activityTracker, CureTaskFactory cureTaskFactory) {
        this.activityTracker = activityTracker;
        this.cureTaskFactory = cureTaskFactory;
    }

    @Override
    public boolean isTriggered(TaskContext ctx) {
        CombatState combatState = ctx.getCombatState();
        
        // Check if poisoned or venomed
        if (!combatState.isPoisoned() && !combatState.isVenomed()) {
            return false;
        }
        
        // Get current health percentage from player state
        var playerState = ctx.getPlayerState();
        int currentHp = playerState.getCurrentHitpoints();
        int maxHp = playerState.getMaxHitpoints();
        
        if (maxHp <= 0) {
            return false;
        }
        
        double healthPercent = (double) currentHp / maxHp;
        
        // Get threshold based on account type
        double threshold = NORMAL_THRESHOLD;
        if (activityTracker != null && activityTracker.getAccountType().isHardcore()) {
            threshold = HCIM_THRESHOLD;
        }
        
        // Venom is more dangerous, use higher threshold
        if (combatState.isVenomed()) {
            threshold += 0.10; // +10% threshold for venom
        }
        
        boolean triggered = healthPercent <= threshold;
        
        if (triggered) {
            log.debug("Poison emergency: health={}%, threshold={}%, venom={}",
                    String.format("%.0f", healthPercent * 100),
                    String.format("%.0f", threshold * 100),
                    combatState.isVenomed());
        }
        
        return triggered;
    }

    @Override
    public Task createResponseTask(TaskContext ctx) {
        if (cureTaskFactory != null) {
            return cureTaskFactory.create(ctx);
        }
        
        // No task factory configured
        log.warn("No cure task factory configured for PoisonEmergency");
        return null;
    }

    @Override
    public String getDescription() {
        return "Poison/venom threatening player health";
    }

    @Override
    public String getId() {
        return "POISON_EMERGENCY";
    }

    @Override
    public long getCooldownMs() {
        return 10000; // 10 second cooldown to allow cure to take effect
    }

    /**
     * Factory interface for creating cure tasks.
     */
    @FunctionalInterface
    public interface CureTaskFactory {
        Task create(TaskContext ctx);
    }
}

