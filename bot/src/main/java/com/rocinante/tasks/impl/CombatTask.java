package com.rocinante.tasks.impl;

import com.rocinante.combat.CombatManager;
import com.rocinante.combat.TargetSelector;
import com.rocinante.state.*;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskPriority;
import com.rocinante.tasks.TaskState;
import com.rocinante.util.Randomization;
import java.util.concurrent.ThreadLocalRandom;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Task for sustained combat against NPCs.
 *
 * Per REQUIREMENTS.md Section 5.4.9:
 * <ul>
 *   <li>Attack specified NPC or nearest attackable NPC matching criteria</li>
 *   <li>Integrate with CombatManager for ongoing combat loop</li>
 *   <li>Support for safe-spotting: maintain distance while attacking</li>
 *   <li>Loot drops based on configurable value threshold or item whitelist</li>
 * </ul>
 *
 * <p>Execution Flow:
 * <pre>
 * FIND_TARGET -> POSITION (if safe-spotting) -> ATTACK -> MONITOR_COMBAT -> LOOT -> (repeat)
 * </pre>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Kill 10 cows
 * CombatTask killCows = new CombatTask(
 *     CombatTaskConfig.forNpcNames(10, "Cow"),
 *     targetSelector,
 *     combatManager
 * );
 *
 * // Safe-spot goblins with ranged
 * CombatTask safeSpotGoblins = new CombatTask(
 *     CombatTaskConfig.builder()
 *         .targetConfig(TargetSelectorConfig.forNpcNames("Goblin"))
 *         .useSafeSpot(true)
 *         .safeSpotPosition(new WorldPoint(3245, 3235, 0))
 *         .attackRange(7)
 *         .build(),
 *     targetSelector,
 *     combatManager
 * );
 * }</pre>
 */
@Slf4j
public class CombatTask extends AbstractTask {

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final TargetSelector targetSelector;
    private final CombatManager combatManager;

    // ========================================================================
    // Configuration
    // ========================================================================

    @Getter
    private final CombatTaskConfig config;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private CombatPhase phase = CombatPhase.FIND_TARGET;

    /**
     * Current target NPC snapshot.
     */
    private NpcSnapshot currentTarget;

    /**
     * Last known position of target (for tracking).
     */
    private WorldPoint lastTargetPosition;

    /**
     * Number of kills completed.
     */
    @Getter
    private int killsCompleted = 0;

    /**
     * Time when task started.
     */
    private Instant taskStartTime;

    /**
     * Whether mouse movement is pending.
     */
    private boolean movePending = false;

    /**
     * Whether click is pending.
     */
    private boolean clickPending = false;

    /**
     * Ticks since last action.
     */
    private int idleTicks = 0;

    /**
     * Ticks waiting in current phase.
     */
    private int phaseWaitTicks = 0;

    /**
     * Maximum ticks to wait before considering target lost.
     */
    private static final int MAX_WAIT_TICKS = 20;

    /**
     * Maximum ticks idle before retrying.
     */
    private static final int MAX_IDLE_TICKS = 10;

    /**
     * Ground items to loot after kill.
     */
    private List<GroundItemSnapshot> pendingLoot;

    /**
     * Index of current loot item being picked up.
     */
    private int lootIndex = 0;

    /**
     * Active sub-task for delegation (e.g., WalkToTask for positioning).
     * Allows CombatTask to reuse existing task implementations (DRY).
     */
    private Task activeSubTask;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a combat task with the specified configuration.
     *
     * @param config          the combat task configuration
     * @param targetSelector  the target selector for finding NPCs
     * @param combatManager   the combat manager for combat loop
     */
    public CombatTask(CombatTaskConfig config, 
                      TargetSelector targetSelector, 
                      CombatManager combatManager) {
        this.config = config;
        this.targetSelector = targetSelector;
        this.combatManager = combatManager;
        
        // Configure target selector
        this.targetSelector.setConfig(config.getTargetConfig());
        
        // Set appropriate timeout
        if (config.hasDurationLimit()) {
            this.timeout = config.getMaxDuration().plusMinutes(1);
        } else {
            this.timeout = Duration.ofHours(4); // Default long timeout
        }
        
        this.priority = TaskPriority.NORMAL;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set custom description.
     */
    @Setter
    private String description;

    /**
     * Set custom description (builder-style).
     */
    public CombatTask withDescription(String description) {
        this.description = description;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (!ctx.isLoggedIn()) {
            return false;
        }

        // Check HCIM safety if applicable
        if (combatManager.getConfig().isHcimMode()) {
            return combatManager.isReadyForCombat();
        }

        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (taskStartTime == null) {
            taskStartTime = Instant.now();
            combatManager.start();
            log.info("CombatTask started: {}", config.getSummary());
        }

        // Check completion conditions
        if (isCompleted(ctx)) {
            completeTask(ctx);
            return;
        }

        // Check if we should stop due to resource depletion
        if (shouldStopForResources(ctx)) {
            log.info("CombatTask stopping: resources depleted");
            completeTask(ctx);
            return;
        }

        // Execute active sub-task if present (DRY delegation to WalkToTask, etc.)
        if (activeSubTask != null) {
            activeSubTask.execute(ctx);
            if (activeSubTask.getState().isTerminal()) {
                activeSubTask = null;
            } else {
                return; // Sub-task still running
            }
        }

        // Execute current phase
        switch (phase) {
            case FIND_TARGET:
                executeFindTarget(ctx);
                break;
            case POSITION:
                executePosition(ctx);
                break;
            case ATTACK:
                executeAttack(ctx);
                break;
            case MONITOR_COMBAT:
                executeMonitorCombat(ctx);
                break;
            case LOOT:
                executeLoot(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Find Target
    // ========================================================================

    private void executeFindTarget(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();

        // If player is in combat, go to monitor phase
        if (player.isInCombat()) {
            updateCurrentTarget(ctx);
            if (currentTarget != null) {
                phase = CombatPhase.MONITOR_COMBAT;
                return;
            }
        }

        // Select a new target
        Optional<NpcSnapshot> target = targetSelector.selectTarget();

        if (target.isEmpty()) {
            idleTicks++;
            if (idleTicks > MAX_IDLE_TICKS) {
                log.debug("No valid targets found after {} ticks", idleTicks);
            }
            return;
        }

        currentTarget = target.get();
        lastTargetPosition = currentTarget.getWorldPosition();
        idleTicks = 0;
        phaseWaitTicks = 0;

        log.debug("Selected target: {}", currentTarget.getSummary());

        // Determine next phase
        if (config.isSafeSpotConfigured()) {
            phase = CombatPhase.POSITION;
        } else {
            phase = CombatPhase.ATTACK;
        }
    }

    // ========================================================================
    // Phase: Position (Safe-spotting)
    // ========================================================================

    private void executePosition(TaskContext ctx) {
        if (!config.isSafeSpotConfigured()) {
            phase = CombatPhase.ATTACK;
            return;
        }

        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();
        WorldPoint safeSpot = config.getSafeSpotPosition();

        // Check if we're at the safe spot
        int distanceFromSafeSpot = playerPos.distanceTo(safeSpot);
        
        if (distanceFromSafeSpot <= config.getSafeSpotMaxDistance()) {
            // We're in position, proceed to attack
            phaseWaitTicks = 0;
            phase = CombatPhase.ATTACK;
            return;
        }

        // Need to move to safe spot - delegate to WalkToTask (DRY)
        if (activeSubTask == null && !player.isMoving()) {
            log.debug("Moving to safe spot at {}", safeSpot);
            activeSubTask = new WalkToTask(safeSpot);
            phaseWaitTicks = 0;
            return; // WalkToTask will be executed next tick by executeImpl
        }

        phaseWaitTicks++;
        if (phaseWaitTicks > MAX_WAIT_TICKS) {
            log.warn("Failed to reach safe spot, continuing to attack");
            activeSubTask = null; // Cancel any pending walk
            phase = CombatPhase.ATTACK;
        }
    }

    // ========================================================================
    // Phase: Attack
    // ========================================================================

    private void executeAttack(TaskContext ctx) {
        if (currentTarget == null) {
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        // Check if target is still valid
        if (!isTargetValid(ctx)) {
            log.debug("Target no longer valid, finding new target");
            currentTarget = null;
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        if (movePending || clickPending) {
            return; // Wait for pending actions
        }

        PlayerState player = ctx.getPlayerState();

        // Check if we're already attacking this target
        if (player.isInCombat() && player.getTargetNpcIndex() == currentTarget.getIndex()) {
            phase = CombatPhase.MONITOR_COMBAT;
            return;
        }

        // Check attack range for safe-spotting
        if (config.isSafeSpotConfigured()) {
            int distanceToTarget = player.getWorldPosition().distanceTo(currentTarget.getWorldPosition());
            if (distanceToTarget > config.getAttackRange()) {
                log.debug("Target out of attack range ({} > {}), repositioning", 
                        distanceToTarget, config.getAttackRange());
                phase = CombatPhase.POSITION;
                return;
            }
        }

        // Move mouse to target and click
        executeAttackClick(ctx);
    }

    private void executeAttackClick(TaskContext ctx) {
        Client client = ctx.getClient();

        // Find the NPC in the client's NPC array
        NPC targetNpc = null;
        for (NPC npc : client.getNpcs()) {
            if (npc != null && npc.getIndex() == currentTarget.getIndex()) {
                targetNpc = npc;
                break;
            }
        }

        if (targetNpc == null) {
            log.debug("Target NPC not found in client");
            currentTarget = null;
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        // Calculate click point
        Point clickPoint = calculateNpcClickPoint(ctx, targetNpc);
        if (clickPoint == null) {
            log.warn("Cannot calculate click point for target");
            phaseWaitTicks++;
            if (phaseWaitTicks > MAX_WAIT_TICKS) {
                currentTarget = null;
                phase = CombatPhase.FIND_TARGET;
            }
            return;
        }

        // Add humanized delay before attacking
        int delay = ThreadLocalRandom.current().nextInt(
                config.getMinAttackDelay(), config.getMaxAttackDelay() + 1);
        
        log.debug("Attacking {} at ({}, {}) after {}ms delay", 
                currentTarget.getName(), clickPoint.x, clickPoint.y, delay);

        movePending = true;
        
        // Move mouse and click
        ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y)
                .thenCompose(v -> {
                    movePending = false;
                    clickPending = true;
                    return ctx.getMouseController().click();
                })
                .thenRun(() -> {
                    clickPending = false;
                    phaseWaitTicks = 0;
                    phase = CombatPhase.MONITOR_COMBAT;
                })
                .exceptionally(e -> {
                    movePending = false;
                    clickPending = false;
                    log.error("Attack click failed", e);
                    return null;
                });
    }

    // ========================================================================
    // Phase: Monitor Combat
    // ========================================================================

    private void executeMonitorCombat(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        CombatState combatState = ctx.getCombatState();

        // Update target reference
        updateCurrentTarget(ctx);

        // Check if target is dead
        if (currentTarget != null && currentTarget.isDead()) {
            log.info("Target {} killed! Total kills: {}", currentTarget.getName(), killsCompleted + 1);
            killsCompleted++;
            
            // Prepare to loot
            if (config.isLootEnabled()) {
                prepareLoot(ctx);
                if (pendingLoot != null && !pendingLoot.isEmpty()) {
                    phase = CombatPhase.LOOT;
                    return;
                }
            }
            
            currentTarget = null;
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        // Check if we lost target
        if (!player.isInCombat() && !combatState.hasTarget()) {
            phaseWaitTicks++;
            if (phaseWaitTicks > MAX_WAIT_TICKS / 2) {
                log.debug("Lost combat target, finding new one");
                currentTarget = null;
                phase = CombatPhase.FIND_TARGET;
            }
            return;
        }

        // Safe-spot check: ensure we're still in position
        if (config.isSafeSpotConfigured()) {
            WorldPoint playerPos = player.getWorldPosition();
            int distanceFromSafeSpot = playerPos.distanceTo(config.getSafeSpotPosition());
            
            if (distanceFromSafeSpot > config.getSafeSpotMaxDistance()) {
                log.debug("Drifted from safe spot, repositioning");
                phase = CombatPhase.POSITION;
                return;
            }
        }

        // Combat is ongoing, CombatManager handles eating/prayer/etc.
        phaseWaitTicks = 0;
    }

    // ========================================================================
    // Phase: Loot
    // ========================================================================

    private void executeLoot(TaskContext ctx) {
        if (pendingLoot == null || pendingLoot.isEmpty() || lootIndex >= pendingLoot.size()) {
            // Done looting
            pendingLoot = null;
            lootIndex = 0;
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        // Check max loot per kill
        if (config.getMaxLootPerKill() > 0 && lootIndex >= config.getMaxLootPerKill()) {
            log.debug("Max loot per kill reached ({})", config.getMaxLootPerKill());
            pendingLoot = null;
            lootIndex = 0;
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        // Check inventory space
        InventoryState inventory = ctx.getInventoryState();
        if (inventory.isFull()) {
            log.debug("Inventory full, skipping remaining loot");
            pendingLoot = null;
            lootIndex = 0;
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        if (movePending || clickPending) {
            return;
        }

        GroundItemSnapshot item = pendingLoot.get(lootIndex);
        
        log.debug("Looting {} (value: {} gp)", item.getName(), item.getTotalGeValue());

        // Click on ground item using helper
        var groundItemHelper = ctx.getGroundItemClickHelper();
        if (groundItemHelper == null) {
            log.warn("GroundItemClickHelper not available, skipping loot");
            lootIndex++;
            return;
        }

        clickPending = true;
        groundItemHelper.clickGroundItem(item.getWorldPosition(), item.getId(), item.getName())
                .thenAccept(success -> {
                    clickPending = false;
                    if (success) {
                        // Add humanized delay before next loot
                        int delay = ThreadLocalRandom.current().nextInt(
                                config.getMinLootDelay(), config.getMaxLootDelay() + 1);
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ignored) {}
                    }
                    lootIndex++;
                })
                .exceptionally(e -> {
                    log.error("Failed to loot {}: {}", item.getName(), e.getMessage());
                    clickPending = false;
                    lootIndex++;
                    return null;
                });
    }

    private void prepareLoot(TaskContext ctx) {
        WorldState worldState = ctx.getWorldState();
        WorldPoint targetPos = lastTargetPosition;

        if (targetPos == null) {
            pendingLoot = null;
            return;
        }

        // Get ground items near the kill location
        List<GroundItemSnapshot> nearbyItems = worldState.getGroundItems().stream()
                .filter(item -> item.getWorldPosition().distanceTo(targetPos) <= 3)
                .filter(item -> config.shouldLootItem(item.getId(), (int) item.getTotalGeValue()))
                .sorted(Comparator.comparingLong(GroundItemSnapshot::getTotalGeValue).reversed())
                .collect(Collectors.toList());

        if (!nearbyItems.isEmpty()) {
            pendingLoot = nearbyItems;
            lootIndex = 0;
            log.debug("Found {} items to loot", nearbyItems.size());
        } else {
            pendingLoot = null;
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private boolean isCompleted(TaskContext ctx) {
        // Check kill count
        if (config.hasKillCountLimit() && killsCompleted >= config.getKillCount()) {
            log.info("Kill count reached: {}/{}", killsCompleted, config.getKillCount());
            return true;
        }

        // Check duration
        if (config.hasDurationLimit() && taskStartTime != null) {
            Duration elapsed = Duration.between(taskStartTime, Instant.now());
            if (elapsed.compareTo(config.getMaxDuration()) >= 0) {
                log.info("Duration limit reached: {}", elapsed);
                return true;
            }
        }

        return false;
    }

    private boolean shouldStopForResources(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        InventoryState inventory = ctx.getInventoryState();

        // Check "stop when out of food"
        if (config.isStopWhenOutOfFood() && !inventory.hasFood()) {
            log.debug("Out of food");
            return true;
        }

        // Check "bank when low" mode
        if (config.isStopWhenLowResources()) {
            boolean lowHp = player.getHealthPercent() < config.getLowResourcesHpThreshold();
            boolean noFood = !inventory.hasFood();
            
            if (lowHp && noFood) {
                log.debug("Low resources: HP {}%, no food", 
                        (int)(player.getHealthPercent() * 100));
                return true;
            }
        }

        return false;
    }

    /**
     * Complete the task and clean up resources.
     * This is called when the task completes normally (not via external abort).
     */
    private void completeTask(TaskContext ctx) {
        combatManager.stop();
        log.info("CombatTask completed: {} kills in {}", 
                killsCompleted, 
                taskStartTime != null ? Duration.between(taskStartTime, Instant.now()) : "N/A");
        complete();
    }

    private boolean isTargetValid(TaskContext ctx) {
        if (currentTarget == null) {
            return false;
        }

        if (currentTarget.isDead()) {
            return false;
        }

        // Verify target still exists in world state
        WorldState worldState = ctx.getWorldState();
        Optional<NpcSnapshot> npc = worldState.getNpcByIndex(currentTarget.getIndex());
        
        if (npc.isEmpty()) {
            return false;
        }

        // Update our reference
        currentTarget = npc.get();
        return !currentTarget.isDead();
    }

    private void updateCurrentTarget(TaskContext ctx) {
        if (currentTarget == null) {
            return;
        }

        WorldState worldState = ctx.getWorldState();
        Optional<NpcSnapshot> npc = worldState.getNpcByIndex(currentTarget.getIndex());
        
        if (npc.isPresent()) {
            currentTarget = npc.get();
            lastTargetPosition = currentTarget.getWorldPosition();
        }
    }

    private Point calculateNpcClickPoint(TaskContext ctx, NPC npc) {
        Shape clickableArea = npc.getConvexHull();

        if (clickableArea == null) {
            return calculateNpcModelCenter(ctx, npc);
        }

        Rectangle bounds = clickableArea.getBounds();
        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            return calculateNpcModelCenter(ctx, npc);
        }

        // Calculate random point within the clickable area using Gaussian distribution
        int centerX = bounds.x + bounds.width / 2;
        int centerY = bounds.y + bounds.height / 2;

        double[] offset = Randomization.staticGaussian2D(0, 0, bounds.width / 4.0, bounds.height / 4.0);
        int clickX = centerX + (int) offset[0];
        int clickY = centerY + (int) offset[1];

        // Clamp to bounds
        clickX = Math.max(bounds.x, Math.min(clickX, bounds.x + bounds.width));
        clickY = Math.max(bounds.y, Math.min(clickY, bounds.y + bounds.height));

        return new Point(clickX, clickY);
    }

    private Point calculateNpcModelCenter(TaskContext ctx, NPC npc) {
        LocalPoint localPoint = npc.getLocalLocation();
        if (localPoint == null) {
            return null;
        }

        // Fallback to canvas center
        Client client = ctx.getClient();
        java.awt.Dimension canvasSize = client.getCanvas().getSize();
        return new Point(canvasSize.width / 2, canvasSize.height / 2);
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Override
    public void onComplete(TaskContext ctx) {
        combatManager.stop();
        log.info("CombatTask completed: {} kills in {}", 
                killsCompleted, 
                taskStartTime != null ? Duration.between(taskStartTime, Instant.now()) : "N/A");
        super.onComplete(ctx);
    }

    @Override
    public void onFail(TaskContext ctx, Exception e) {
        combatManager.stop();
        super.onFail(ctx, e);
    }

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("CombatTask[%s, kills=%d/%d]", 
                config.getTargetConfig().getSummary(),
                killsCompleted,
                config.hasKillCountLimit() ? config.getKillCount() : -1);
    }

    // ========================================================================
    // Combat Phase Enum
    // ========================================================================

    /**
     * Phases of combat task execution.
     */
    private enum CombatPhase {
        /**
         * Looking for a valid target.
         */
        FIND_TARGET,

        /**
         * Moving to safe-spot position.
         */
        POSITION,

        /**
         * Initiating attack on target.
         */
        ATTACK,

        /**
         * Combat in progress, monitoring for kill/flee.
         */
        MONITOR_COMBAT,

        /**
         * Collecting loot after kill.
         */
        LOOT
    }
}

