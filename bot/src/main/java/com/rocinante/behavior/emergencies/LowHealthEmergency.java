package com.rocinante.behavior.emergencies;

import com.rocinante.behavior.BotActivityTracker;
import com.rocinante.behavior.EmergencyCondition;
import com.rocinante.state.CombatState;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Emergency condition for critically low health.
 * 
 * Triggers when:
 * - Player health drops below flee threshold
 * - Threshold varies by account type (HCIM higher)
 * 
 * Response can be:
 * - Eat food
 * - Teleport away
 * - Run from combat
 */
@Slf4j
public class LowHealthEmergency implements EmergencyCondition {

    /**
     * Base health percentage threshold for normal accounts.
     */
    private static final double BASE_THRESHOLD = 0.25; // 25% HP
    
    /**
     * Additional threshold for being in combat.
     */
    private static final double COMBAT_MODIFIER = 0.10; // +10% if in combat
    
    /**
     * Additional threshold for HCIM.
     */
    private static final double HCIM_MODIFIER = 0.25; // +25% for HCIM
    
    /**
     * Additional threshold for dangerous areas.
     */
    private static final double DANGEROUS_AREA_MODIFIER = 0.15; // +15% in wildy/etc

    private final BotActivityTracker activityTracker;
    private final FleeTaskFactory fleeTaskFactory;

    public LowHealthEmergency(BotActivityTracker activityTracker, FleeTaskFactory fleeTaskFactory) {
        this.activityTracker = activityTracker;
        this.fleeTaskFactory = fleeTaskFactory;
    }

    @Override
    public boolean isTriggered(TaskContext ctx) {
        CombatState combatState = ctx.getCombatState();
        var playerState = ctx.getPlayerState();
        
        int currentHp = playerState.getCurrentHitpoints();
        int maxHp = playerState.getMaxHitpoints();
        
        if (maxHp <= 0) {
            return false;
        }
        
        double healthPercent = (double) currentHp / maxHp;
        double threshold = calculateThreshold(combatState);
        
        boolean triggered = healthPercent <= threshold;
        
        if (triggered) {
            log.debug("Low health emergency: health={}%, threshold={}%",
                    String.format("%.0f", healthPercent * 100),
                    String.format("%.0f", threshold * 100));
        }
        
        return triggered;
    }

    private double calculateThreshold(CombatState combatState) {
        double threshold = BASE_THRESHOLD;
        
        // Modify based on account type
        if (activityTracker != null) {
            if (activityTracker.getAccountType().isHardcore()) {
                threshold += HCIM_MODIFIER;
            }
            
            if (activityTracker.isInDangerousArea()) {
                threshold += DANGEROUS_AREA_MODIFIER;
            }
        }
        
        // Modify if in combat (need buffer for incoming damage)
        if (combatState.isBeingAttacked() || combatState.hasTarget()) {
            threshold += COMBAT_MODIFIER;
        }
        
        // Cap at reasonable maximum
        return Math.min(threshold, 0.75);
    }

    @Override
    public Task createResponseTask(TaskContext ctx) {
        if (fleeTaskFactory != null) {
            return fleeTaskFactory.create(ctx);
        }
        
        log.warn("No flee task factory configured for LowHealthEmergency");
        return null;
    }

    @Override
    public String getDescription() {
        return "Player health critically low";
    }

    @Override
    public String getId() {
        return "LOW_HEALTH_EMERGENCY";
    }

    @Override
    public long getCooldownMs() {
        return 3000; // 3 second cooldown - need to react quickly
    }

    @Override
    public int getSeverity() {
        return 90; // Very high severity - health is life-threatening
    }

    /**
     * Factory interface for creating flee/eat tasks.
     */
    @FunctionalInterface
    public interface FleeTaskFactory {
        Task create(TaskContext ctx);
    }
}

