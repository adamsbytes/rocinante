package com.rocinante.behavior;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Handles emergency conditions that require immediate task interruption.
 * 
 * Emergency conditions are checked each game tick and can interrupt
 * any running task, including behavioral tasks.
 * 
 * Emergencies are queued with TaskPriority.URGENT.
 */
@Slf4j
@Singleton
public class EmergencyHandler {

    private final List<EmergencyCondition> conditions;
    private final Map<String, Instant> cooldowns;
    
    /**
     * Currently active emergency (if any).
     */
    private String activeEmergencyId = null;

    /**
     * When true, all emergency checks are skipped.
     * Use for quests/scenarios that require death or specific health states.
     */
    private volatile boolean suppressed = false;

    @Inject
    public EmergencyHandler() {
        this.conditions = new CopyOnWriteArrayList<>();
        this.cooldowns = new HashMap<>();
        log.info("EmergencyHandler initialized");
    }

    /**
     * Register an emergency condition.
     * 
     * @param condition the condition to register
     */
    public void registerCondition(EmergencyCondition condition) {
        conditions.add(condition);
        log.debug("Registered emergency condition: {}", condition.getDescription());
    }

    /**
     * Unregister an emergency condition.
     * 
     * @param condition the condition to remove
     */
    public void unregisterCondition(EmergencyCondition condition) {
        conditions.remove(condition);
    }

    /**
     * Clear all registered conditions.
     */
    public void clearConditions() {
        conditions.clear();
        cooldowns.clear();
    }

    // ========================================================================
    // Game Tick Handler
    // ========================================================================

    /**
     * Check for emergencies each game tick.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        // Emergencies are checked when TaskExecutor calls checkEmergencies()
    }

    // ========================================================================
    // Emergency Checking
    // ========================================================================

    /**
     * Check all registered conditions and return an emergency task if triggered.
     * 
     * @param ctx task context
     * @return Optional containing emergency task, or empty if no emergency
     */
    public Optional<Task> checkEmergencies(TaskContext ctx) {
        if (suppressed) {
            return Optional.empty();
        }
        
        Instant now = Instant.now();
        
        for (EmergencyCondition condition : conditions) {
            // Skip if on cooldown
            String conditionId = condition.getId();
            Instant cooldownUntil = cooldowns.get(conditionId);
            if (cooldownUntil != null && now.isBefore(cooldownUntil)) {
                continue;
            }
            
            // Skip if this emergency is already active
            if (conditionId.equals(activeEmergencyId)) {
                continue;
            }
            
            try {
                if (condition.isTriggered(ctx)) {
                    log.warn("Emergency triggered: {}", condition.getDescription());
                    
                    // Set cooldown
                    cooldowns.put(conditionId, now.plusMillis(condition.getCooldownMs()));
                    activeEmergencyId = conditionId;
                    
                    // Create response task
                    Task responseTask = condition.createResponseTask(ctx);
                    if (responseTask != null) {
                        return Optional.of(responseTask);
                    }
                }
            } catch (Exception e) {
                log.error("Error checking emergency condition {}: {}", 
                        conditionId, e.getMessage());
            }
        }
        
        return Optional.empty();
    }

    /**
     * Mark an emergency as resolved.
     * Called when the emergency response task completes.
     * 
     * @param emergencyId the emergency ID
     */
    public void emergencyResolved(String emergencyId) {
        if (emergencyId.equals(activeEmergencyId)) {
            activeEmergencyId = null;
            // Clear cooldown so emergency can retrigger if conditions occur again
            cooldowns.remove(emergencyId);
            log.debug("Emergency resolved: {} (cooldown cleared)", emergencyId);
        }
    }

    /**
     * Clear cooldown for a specific condition.
     * 
     * @param conditionId the condition ID
     */
    public void clearCooldown(String conditionId) {
        cooldowns.remove(conditionId);
    }

    /**
     * Clear all cooldowns.
     */
    public void clearAllCooldowns() {
        cooldowns.clear();
        activeEmergencyId = null;
    }

    // ========================================================================
    // Suppression
    // ========================================================================

    /**
     * Suppress all emergency checks.
     * Use for quests/scenarios that require death or specific health states.
     */
    public void suppress() {
        this.suppressed = true;
        log.info("Emergency handling suppressed");
    }

    /**
     * Resume emergency checks after suppression.
     */
    public void unsuppress() {
        this.suppressed = false;
        log.info("Emergency handling resumed");
    }

    /**
     * Check if emergency handling is currently suppressed.
     * 
     * @return true if suppressed
     */
    public boolean isSuppressed() {
        return suppressed;
    }

    // ========================================================================
    // Status
    // ========================================================================

    /**
     * Check if there's an active emergency.
     * 
     * @return true if emergency is active
     */
    public boolean hasActiveEmergency() {
        return activeEmergencyId != null;
    }

    /**
     * Get the active emergency ID.
     * 
     * @return emergency ID or null
     */
    public String getActiveEmergencyId() {
        return activeEmergencyId;
    }

    /**
     * Get the number of registered conditions.
     * 
     * @return condition count
     */
    public int getConditionCount() {
        return conditions.size();
    }

    /**
     * Get a summary of handler state.
     * 
     * @return summary string
     */
    public String getSummary() {
        return String.format("EmergencyHandler[conditions=%d, active=%s, cooldowns=%d, suppressed=%s]",
                conditions.size(), activeEmergencyId, cooldowns.size(), suppressed);
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

