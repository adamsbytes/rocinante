package com.rocinante.slayer;

import com.rocinante.combat.CombatManager;
import com.rocinante.combat.TargetSelector;
import com.rocinante.core.GameStateService;
import com.rocinante.state.InventoryState;
import com.rocinante.state.NpcSnapshot;
import com.rocinante.state.WorldState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.impl.CombatTask;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Slayer-specific combat task that extends CombatTask with slayer functionality.
 *
 * Per REQUIREMENTS.md Section 11, adds:
 * <ul>
 *   <li>Task completion tracking from SlayerState</li>
 *   <li>Superior creature prioritization</li>
 *   <li>Finish-off item usage (rock hammer, ice coolers, etc.)</li>
 *   <li>Equipment validation before combat</li>
 *   <li>Integration with SlayerManager for task lifecycle</li>
 * </ul>
 *
 * Extends {@link CombatTask} to reuse existing combat loop logic while
 * adding slayer-specific behavior through hooks.
 */
@Slf4j
public class SlayerTask extends CombatTask {

    // ========================================================================
    // Slayer-Specific Dependencies
    // ========================================================================

    private final GameStateService gameStateService;
    private final SlayerEquipmentManager equipmentManager;

    // ========================================================================
    // Slayer Configuration
    // ========================================================================

    @Getter
    private final SlayerTaskConfig slayerConfig;

    // ========================================================================
    // Slayer Execution State
    // ========================================================================

    /**
     * Whether we've validated equipment this session.
     */
    private boolean equipmentValidated = false;

    /**
     * Current superior target (if any spawned).
     */
    @Nullable
    private NpcSnapshot currentSuperior;

    /**
     * Last known kills remaining from slayer state.
     */
    private int lastKillsRemaining;

    /**
     * Slayer kills completed (tracked separately from combat kills).
     */
    @Getter
    private int slayerKillsCompleted = 0;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a slayer task with the specified configuration.
     *
     * @param slayerConfig     slayer-specific configuration
     * @param targetSelector   target selector for finding NPCs
     * @param combatManager    combat manager for combat loop
     * @param gameStateService game state service for slayer state
     * @param equipmentManager slayer equipment manager
     */
    public SlayerTask(
            SlayerTaskConfig slayerConfig,
            TargetSelector targetSelector,
            CombatManager combatManager,
            GameStateService gameStateService,
            SlayerEquipmentManager equipmentManager) {
        super(slayerConfig.getCombatConfig(), targetSelector, combatManager);
        this.slayerConfig = slayerConfig;
        this.gameStateService = gameStateService;
        this.equipmentManager = equipmentManager;
        this.lastKillsRemaining = slayerConfig.getInitialKillsRemaining();

        setDescription("Slayer Task: " + slayerConfig.getTaskName());
    }

    // ========================================================================
    // Task Lifecycle Overrides
    // ========================================================================

    @Override
    protected void executeImpl(TaskContext ctx) {
        // Equipment validation on first execution
        if (slayerConfig.isValidateEquipment() && !equipmentValidated) {
            SlayerEquipmentManager.EquipmentValidation validation = 
                    equipmentManager.validateEquipment(
                            slayerConfig.getTaskName(), 
                            ctx.getEquipmentState());
            
            if (!validation.isValid()) {
                log.error("Missing required equipment for {}: {}", 
                        slayerConfig.getTaskName(), validation.getMissingItems());
                // Don't fail immediately - let combat loop handle it
            } else {
                equipmentValidated = true;
                log.debug("Equipment validated for slayer task");
            }
        }
        
        // Check for task completion from slayer state
        if (slayerConfig.isTrackFromSlayerState() && isSlayerTaskComplete()) {
            log.info("Slayer task complete! {} kills", slayerKillsCompleted);
            complete();
            return;
        }

        // Check for superior spawn
        if (slayerConfig.canSpawnSuperiors()) {
            checkForSuperior(ctx);
        }

        // Update kill tracking from slayer state
        updateKillTracking();

        // Delegate to parent combat loop
        super.executeImpl(ctx);
    }

    @Override
    public void onComplete(TaskContext ctx) {
        super.onComplete(ctx);
        
        log.info("Slayer task {} completed. Slayer kills: {}, Combat kills: {}",
                slayerConfig.getTaskName(), slayerKillsCompleted, getKillsCompleted());
    }

    // ========================================================================
    // Slayer-Specific Logic
    // ========================================================================

    /**
     * Check if the slayer task is complete based on SlayerState.
     *
     * @return true if task complete
     */
    private boolean isSlayerTaskComplete() {
        SlayerState state = gameStateService.getSlayerState();
        if (state == null) {
            return false;
        }

        // Task complete when remaining kills reaches 0
        return state.isTaskComplete() || state.getRemainingKills() == 0;
    }

    /**
     * Update kill tracking from slayer state.
     */
    private void updateKillTracking() {
        SlayerState state = gameStateService.getSlayerState();
        if (state == null) {
            return;
        }

        int currentRemaining = state.getRemainingKills();
        if (currentRemaining < lastKillsRemaining) {
            int newKills = lastKillsRemaining - currentRemaining;
            slayerKillsCompleted += newKills;
            log.debug("Slayer progress: {} kills completed, {} remaining", 
                    slayerKillsCompleted, currentRemaining);
        }
        lastKillsRemaining = currentRemaining;
    }

    /**
     * Check for superior creature spawns.
     */
    private void checkForSuperior(TaskContext ctx) {
        if (currentSuperior != null) {
            // Already tracking a superior - verify it's still alive
            if (currentSuperior.isDead()) {
                log.info("Superior {} killed!", currentSuperior.getName());
                currentSuperior = null;
            } else {
                // Refresh snapshot from world state
                ctx.getWorldState().getNpcByIndex(currentSuperior.getIndex())
                        .ifPresent(npc -> {
                            if (!npc.isDead()) {
                                currentSuperior = npc;
                            } else {
                                log.info("Superior {} killed!", currentSuperior.getName());
                                currentSuperior = null;
                            }
                        });
            }
            return;
        }

        // Look for superior NPCs from WorldState
        List<NpcSnapshot> nearbyNpcs = ctx.getWorldState().getNearbyNpcs();
        if (nearbyNpcs == null || nearbyNpcs.isEmpty()) {
            return;
        }
        
        List<NpcSnapshot> superiors = nearbyNpcs.stream()
                .filter(npc -> npc != null && npc.getId() > 0)
                .filter(npc -> slayerConfig.isSuperiorNpc(npc.getId()))
                .filter(npc -> !npc.isDead())
                .collect(Collectors.toList());

        if (!superiors.isEmpty()) {
            currentSuperior = superiors.get(0);
            log.info("Superior spawned! Targeting: {} (id={})", 
                    currentSuperior.getName(), currentSuperior.getId());
        }
    }
    
    /**
     * Override target selection to prioritize superior creatures.
     * 
     * <p>Superior creatures despawn after ~2 minutes and have valuable drops,
     * so they should always take priority over regular slayer targets.
     *
     * @param ctx the task context
     * @return Optional containing the superior if one is being tracked, empty otherwise
     */
    @Override
    protected Optional<NpcSnapshot> getForcedTarget(TaskContext ctx) {
        if (currentSuperior != null && !currentSuperior.isDead()) {
            // Verify superior is still in world
            Optional<NpcSnapshot> refreshed = ctx.getWorldState()
                    .getNpcByIndex(currentSuperior.getIndex());
            
            if (refreshed.isPresent() && !refreshed.get().isDead()) {
                currentSuperior = refreshed.get();
                log.debug("Forcing superior target: {}", currentSuperior.getName());
                return Optional.of(currentSuperior);
            } else {
                // Superior died or despawned
                log.info("Superior no longer available");
                currentSuperior = null;
            }
        }
        
        return Optional.empty();
    }

    /**
     * Check if an NPC is a valid slayer target.
     *
     * @param npc the NPC to check
     * @return true if valid slayer target
     */
    public boolean isSlayerTarget(NPC npc) {
        if (npc == null) {
            return false;
        }

        // Check if NPC ID matches creature
        SlayerCreature creature = slayerConfig.getCreature();
        if (creature != null) {
            return creature.matchesNpcId(npc.getId());
        }

        // Fall back to checking against SlayerPluginService targets
        SlayerState state = gameStateService.getSlayerState();
        if (state != null) {
            return state.isTarget(npc);
        }

        return false;
    }

    /**
     * Get the HP threshold for finish-off item usage.
     *
     * @return threshold HP, or 0 if not applicable
     */
    public int getFinishOffThreshold() {
        return slayerConfig.getFinishOffThreshold();
    }

    /**
     * Check if target is below finish-off threshold.
     *
     * @param target the target NPC
     * @return true if finish-off should be used
     */
    public boolean shouldUseFinishOff(NPC target) {
        if (!slayerConfig.requiresFinishOffItem()) {
            return false;
        }

        if (!slayerConfig.isUseFinishOffItems()) {
            return false;
        }

        int threshold = slayerConfig.getFinishOffThreshold();
        // Use healthRatio as percentage indicator (0-255 scale in game)
        int healthRatio = target.getHealthRatio();
        int healthScale = target.getHealthScale();
        
        if (healthScale <= 0) {
            return false;
        }
        
        // Estimate current HP as percentage of max
        double healthPercent = (double) healthRatio / healthScale;
        // Rough estimate - assume ~100 HP max for most slayer monsters
        int estimatedHp = (int) (healthPercent * 100);
        
        return estimatedHp > 0 && estimatedHp <= threshold;
    }

    /**
     * Get the item to use for finishing off the target.
     *
     * @param inventory current inventory state
     * @return item ID to use, or -1 if none available
     */
    public int getFinishOffItem(InventoryState inventory) {
        if (!slayerConfig.requiresFinishOffItem()) {
            return -1;
        }

        return equipmentManager.getFinishOffItemToUse(
                slayerConfig.getTaskName(), inventory);
    }

    // ========================================================================
    // Progress Queries
    // ========================================================================

    /**
     * Get the task completion percentage based on slayer state.
     *
     * @return percentage (0-100)
     */
    public int getSlayerProgressPercent() {
        int initial = slayerConfig.getInitialKillsRemaining();
        if (initial <= 0) {
            return 0;
        }
        return Math.min(100, (slayerKillsCompleted * 100) / initial);
    }

    /**
     * Get remaining kills from slayer state.
     *
     * @return kills remaining
     */
    public int getKillsRemaining() {
        SlayerState state = gameStateService.getSlayerState();
        return state != null ? state.getRemainingKills() : lastKillsRemaining;
    }

    /**
     * Check if this is a location-restricted (Konar) task.
     *
     * @return true if Konar task
     */
    public boolean isKonarTask() {
        return slayerConfig.getKonarRegion() != null && 
               !slayerConfig.getKonarRegion().isEmpty();
    }

    /**
     * Check if the current location is valid for a Konar task.
     *
     * @param ctx task context
     * @return true if valid location
     */
    public boolean isInValidKonarRegion(TaskContext ctx) {
        if (!isKonarTask()) {
            return true;
        }

        SlayerLocation location = slayerConfig.getLocation();
        if (location == null) {
            return true;
        }

        return location.contains(ctx.getPlayerState().getWorldPosition());
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a SlayerTask from a location resolution result.
     *
     * @param resolution      resolved location
     * @param slayerState     current slayer state
     * @param targetSelector  target selector
     * @param combatManager   combat manager
     * @param gameStateService game state service
     * @param equipmentManager equipment manager
     * @return configured SlayerTask
     */
    public static SlayerTask fromResolution(
            TaskLocationResolver.LocationResolution resolution,
            SlayerState slayerState,
            TargetSelector targetSelector,
            CombatManager combatManager,
            GameStateService gameStateService,
            SlayerEquipmentManager equipmentManager) {
        
        if (!resolution.isSuccess()) {
            throw new IllegalArgumentException("Cannot create task from failed resolution: " + 
                    resolution.getFailureReason());
        }

        SlayerTaskConfig config = SlayerTaskConfig.fromResolution(
                resolution.getCreature(),
                resolution.getLocation(),
                slayerState);

        return new SlayerTask(
                config,
                targetSelector,
                combatManager,
                gameStateService,
                equipmentManager);
    }
}
