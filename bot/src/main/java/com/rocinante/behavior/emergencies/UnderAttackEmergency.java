package com.rocinante.behavior.emergencies;

import com.rocinante.behavior.BotActivityTracker;
import com.rocinante.behavior.EmergencyCondition;
import com.rocinante.state.AggressorInfo;
import com.rocinante.state.CombatState;
import com.rocinante.state.IronmanState;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.DeathTask;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Emergency condition for being attacked while skilling or idle.
 * 
 * Triggers when:
 * - Player is being attacked by aggressive NPCs
 * - Player is NOT in an intentional combat task
 * 
 * Response depends on:
 * - Account type (HCIM always flees)
 * - HCIM safety level (CAUTIOUS/PARANOID flee earlier)
 * - Current HP vs estimated max hit
 * - Combat level comparison
 * - Active gravestone recovery (accept more risk to recover items)
 */
@Slf4j
public class UnderAttackEmergency implements EmergencyCondition {

    /**
     * Base minimum HP percentage to fight (normal accounts).
     */
    private static final double BASE_MIN_HEALTH_TO_FIGHT = 0.40; // 40% HP

    /**
     * Combat level difference threshold to consider fighting.
     */
    private static final int MAX_LEVEL_DIFFERENCE = 10;

    /**
     * During gravestone recovery, reduce flee thresholds by this multiplier.
     * 0.5 = accept 50% lower health before fleeing (i.e., fight longer to recover items)
     */
    private static final double GRAVESTONE_RECOVERY_THRESHOLD_REDUCTION = 0.5;

    private final BotActivityTracker activityTracker;
    private final ResponseTaskFactory responseTaskFactory;
    private final Config config;

    @Builder
    @Getter
    public static class Config {
        /**
         * Whether to fight back when attacked (default true).
         */
        @Builder.Default
        private boolean fightBack = true;

        /**
         * Whether to flee if can't/won't fight (default true).
         */
        @Builder.Default
        private boolean fleeIfCantFight = true;

        /**
         * Whether to return to previous activity after dealing with attacker.
         */
        @Builder.Default
        private boolean returnAfterCombat = true;

        /**
         * Maximum NPC combat level to fight (0 = no limit).
         */
        @Builder.Default
        private int maxNpcLevelToFight = 0;

        /**
         * Force flee mode (never fight, always run).
         */
        @Builder.Default
        private boolean alwaysFlee = false;

        /**
         * Base safety buffer in HP points above estimated max hit.
         * HCIM safety level multiplies this.
         */
        @Builder.Default
        private int baseMaxHitSafetyBuffer = 3;
    }

    public UnderAttackEmergency(BotActivityTracker activityTracker, ResponseTaskFactory responseTaskFactory) {
        this(activityTracker, responseTaskFactory, Config.builder().build());
    }

    public UnderAttackEmergency(BotActivityTracker activityTracker, ResponseTaskFactory responseTaskFactory, Config config) {
        this.activityTracker = activityTracker;
        this.responseTaskFactory = responseTaskFactory;
        this.config = config;
    }

    @Override
    public boolean isTriggered(TaskContext ctx) {
        CombatState combatState = ctx.getCombatState();

        // Not triggered if not being attacked
        if (!combatState.isBeingAttacked()) {
            return false;
        }

        // Don't trigger if we're already in intentional combat (CombatTask handles it)
        // Note: We check for actual CombatTask, not just combat activity type,
        // because being attacked while idle also sets activity to HIGH (combat)
        if (activityTracker.isInIntentionalCombat()) {
            return false;
        }

        List<AggressorInfo> aggressors = combatState.getAggressiveNpcs();
        if (aggressors.isEmpty()) {
            return false;
        }

        AggressorInfo attacker = aggressors.get(0);

        // If we're walking somewhere, DON'T INTERRUPT - the walk IS our escape
        // Interrupting a walk to flee in a different direction makes no sense
        // The walk destination is probably safer than random flee direction
        // Exception: emergency teleport (not implemented yet)
        if (activityTracker.isCurrentlyWalking()) {
            log.debug("Under attack by {} (lvl {}) but already walking - continuing walk rather than interrupting",
                    attacker.getNpcName(), attacker.getCombatLevel());
            return false;
        }

        log.warn("Under attack by {} (level {}) while not walking!",
                attacker.getNpcName(), attacker.getCombatLevel());

        return true;
    }

    @Override
    public Task createResponseTask(TaskContext ctx) {
        CombatState combatState = ctx.getCombatState();
        var playerState = ctx.getPlayerState();

        List<AggressorInfo> aggressors = combatState.getAggressiveNpcs();
        if (aggressors.isEmpty()) {
            return null;
        }

        AggressorInfo attacker = aggressors.get(0);
        ResponseType response = determineResponse(ctx, attacker);

        log.info("Under attack response: {} (attacker: {} lvl {}, player HP: {}/{})",
                response, attacker.getNpcName(), attacker.getCombatLevel(),
                playerState.getCurrentHitpoints(), playerState.getMaxHitpoints());

        return responseTaskFactory.create(ctx, attacker, response);
    }

    /**
     * Determine whether to fight or flee based on situation.
     */
    private ResponseType determineResponse(TaskContext ctx, AggressorInfo attacker) {
        var playerState = ctx.getPlayerState();
        IronmanState ironmanState = ctx.getIronmanState();
        
        boolean isHcim = ironmanState.isHardcore();
        double safetyMultiplier = ironmanState.getFleeThresholdMultiplier();

        // Force flee if configured
        if (config.isAlwaysFlee()) {
            return ResponseType.FLEE;
        }

        int currentHp = playerState.getCurrentHitpoints();
        int maxHp = playerState.getMaxHitpoints();
        int npcLevel = attacker.getCombatLevel();

        // Check if we're recovering a gravestone - accept more risk to get items back
        boolean inGravestoneRecovery = DeathTask.hasActiveGravestone(ctx.getClient());
        double riskReduction = inGravestoneRecovery ? GRAVESTONE_RECOVERY_THRESHOLD_REDUCTION : 1.0;
        
        if (inGravestoneRecovery) {
            log.debug("Gravestone recovery active - accepting {}% more risk before fleeing",
                    (int) ((1.0 - riskReduction) * 100));
        }

        // Use actual expected max hit if known, otherwise estimate from combat level
        int estimatedMaxHit = attacker.getExpectedMaxHit() > 0 
                ? attacker.getExpectedMaxHit() 
                : AggressorInfo.estimateMaxHit(npcLevel);

        // Calculate safety buffer - HCIM safety level multiplies this
        // During gravestone recovery, reduce the buffer to accept more risk
        int safetyBuffer = (int) (config.getBaseMaxHitSafetyBuffer() * safetyMultiplier * riskReduction);
        
        // CRITICAL: Check if we're in death range (HP <= max hit + buffer)
        int deathThreshold = estimatedMaxHit + safetyBuffer;
        if (currentHp <= deathThreshold) {
            log.warn("In death range! HP={}, estimated max hit={}, buffer={}, threshold={} - FLEEING{}",
                    currentHp, estimatedMaxHit, safetyBuffer, deathThreshold,
                    inGravestoneRecovery ? " (even with reduced threshold)" : "");
            return ResponseType.FLEE;
        }

        // Calculate minimum health threshold - HCIM safety level affects this too
        // Base 40%, multiplied by safety level (CAUTIOUS=1.3 -> 52%, PARANOID=1.6 -> 64%)
        // During gravestone recovery, reduce by riskReduction (e.g., 40% * 0.5 = 20%)
        double minHealthPercent = Math.min(0.80, BASE_MIN_HEALTH_TO_FIGHT * safetyMultiplier * riskReduction);
        double healthPercent = (double) currentHp / maxHp;

        if (healthPercent < minHealthPercent) {
            log.debug("Health too low to fight ({}% < {}%), fleeing{}",
                    String.format("%.0f", healthPercent * 100),
                    String.format("%.0f", minHealthPercent * 100),
                    inGravestoneRecovery ? " (reduced threshold for gravestone)" : "");
            return ResponseType.FLEE;
        }

        // HCIM: always flee from unexpected combat (regardless of health)
        // Exception: during gravestone recovery, HCIM can fight if health is above death threshold
        if (isHcim && !inGravestoneRecovery) {
            log.debug("HCIM mode (safety={}) - fleeing from unexpected combat (attacker: {} lvl {})",
                    ironmanState.getHcimSafetyLevel(), attacker.getNpcName(), npcLevel);
            return ResponseType.FLEE;
        }

        // Check if fight back is enabled
        if (!config.isFightBack()) {
            return config.isFleeIfCantFight() ? ResponseType.FLEE : ResponseType.IGNORE;
        }

        // Check level difference
        int playerCombatLevel = ctx.getClient().getLocalPlayer().getCombatLevel();
        int levelDiff = npcLevel - playerCombatLevel;

        if (levelDiff > MAX_LEVEL_DIFFERENCE) {
            log.debug("NPC level {} too high (player: {}, diff: {}), fleeing",
                    npcLevel, playerCombatLevel, levelDiff);
            return config.isFleeIfCantFight() ? ResponseType.FLEE : ResponseType.IGNORE;
        }

        // Check max level config
        if (config.getMaxNpcLevelToFight() > 0 && npcLevel > config.getMaxNpcLevelToFight()) {
            log.debug("NPC level {} exceeds max fight level {}, fleeing",
                    npcLevel, config.getMaxNpcLevelToFight());
            return config.isFleeIfCantFight() ? ResponseType.FLEE : ResponseType.IGNORE;
        }

        // Fight back!
        log.debug("Deciding to fight {} (lvl {}, max hit {}, player HP {})",
                attacker.getNpcName(), npcLevel, estimatedMaxHit, currentHp);
        return ResponseType.FIGHT;
    }

    @Override
    public String getDescription() {
        return "Player under attack while skilling";
    }

    @Override
    public String getId() {
        return "UNDER_ATTACK_EMERGENCY";
    }

    @Override
    public long getCooldownMs() {
        return 10000; // 10 second cooldown
    }

    @Override
    public int getSeverity() {
        return 70; // High but below low-health (90)
    }

    /**
     * Type of response to an attack.
     */
    public enum ResponseType {
        FIGHT,
        FLEE,
        IGNORE
    }

    /**
     * Factory interface for creating response tasks.
     */
    @FunctionalInterface
    public interface ResponseTaskFactory {
        Task create(TaskContext ctx, AggressorInfo attacker, ResponseType response);
    }
}
