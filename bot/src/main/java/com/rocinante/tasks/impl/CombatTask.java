package com.rocinante.tasks.impl;

import com.rocinante.combat.CombatManager;
import com.rocinante.combat.TargetSelector;
import com.rocinante.combat.spell.CombatSpell;
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
import net.runelite.api.widgets.Widget;

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
     * Maximum attempts to drag NPC away from safe spot.
     */
    private static final int MAX_SAFESPOT_DRAG_ATTEMPTS = 5;

    /**
     * Counter for safe spot drag attempts.
     */
    private int safeSpotDragAttempts = 0;

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
    // Magic Combat State
    // ========================================================================

    /**
     * Current spell index when cycling through multiple spells.
     */
    private int currentSpellIndex = 0;

    /**
     * Widget group ID for the spellbook tab.
     */
    private static final int WIDGET_SPELLBOOK = 218;

    /**
     * Whether we've clicked the spell and are waiting to click the target.
     */
    private boolean spellSelected = false;

    /**
     * Whether autocast has been set up for this combat session.
     */
    private boolean autocastSetUp = false;

    // ========================================================================
    // Resupply State
    // ========================================================================

    /**
     * Number of resupply trips completed.
     */
    @Getter
    private int resupplyTripsCompleted = 0;

    /**
     * Position before starting resupply (for return).
     */
    private WorldPoint preResupplyPosition;

    /**
     * Current phase of resupply process.
     */
    private ResupplyPhase resupplyPhase = ResupplyPhase.WALK_TO_BANK;

    /**
     * Phases within resupply process.
     */
    private enum ResupplyPhase {
        WALK_TO_BANK,
        DEPOSIT_INVENTORY,
        WITHDRAW_ITEMS,
        CLOSE_BANK,
        RETURN_TO_SPOT
    }

    /**
     * Index of current item being withdrawn during resupply.
     */
    private int resupplyItemIndex = 0;

    /**
     * List of items to withdraw (from config).
     */
    private java.util.List<java.util.Map.Entry<Integer, Integer>> resupplyItemList;

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

        // Coordinate with CombatManager: skip if a click is already in progress
        // This prevents race conditions on the same game tick
        if (ctx.getMouseController().isClickInProgress()) {
            log.trace("Deferring CombatTask action - click already in progress");
            return;
        }

        // Check completion conditions
        if (isCompleted(ctx)) {
            completeTask(ctx);
            return;
        }

        // Check if we should stop or resupply due to resource depletion
        if (shouldStopForResources(ctx)) {
            if (shouldTriggerResupply(ctx)) {
                log.info("CombatTask: triggering resupply");
                startResupply(ctx);
            } else {
                log.info("CombatTask stopping: resources depleted");
                completeTask(ctx);
            }
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

        // Check if we need to set up autocast first (one-time)
        if (shouldSetupAutocast() && !autocastSetUp) {
            phase = CombatPhase.SETUP_AUTOCAST;
        }

        // Execute current phase
        switch (phase) {
            case SETUP_AUTOCAST:
                executeSetupAutocast(ctx);
                break;
            case FIND_TARGET:
                executeFindTarget(ctx);
                break;
            case POSITION:
                executePosition(ctx);
                break;
            case CAST_SPELL:
                executeCastSpell(ctx);
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
            case RESUPPLY:
                executeResupply(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Setup Autocast
    // ========================================================================

    /**
     * Check if autocast should be set up.
     */
    private boolean shouldSetupAutocast() {
        if (!config.isMagicCombat()) {
            return false;
        }
        if (!config.isUseAutocast()) {
            return false;
        }
        CombatSpell primarySpell = config.getPrimarySpell();
        if (primarySpell == null) {
            return false;
        }
        if (!primarySpell.isAutocastable()) {
            return false;
        }
        return true;
    }

    /**
     * Execute autocast setup phase using AutocastTask.
     */
    private void executeSetupAutocast(TaskContext ctx) {
        // If we already have a sub-task running, let it complete
        if (activeSubTask != null) {
            return;
        }

        // Check if autocast is already active for our spell
        Client client = ctx.getClient();
        if (AutocastTask.isAutocastActive(client)) {
            // Check if it's the right spell (best effort - might not match exactly)
            log.debug("Autocast already active, skipping setup");
            autocastSetUp = true;
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        // Create the AutocastTask
        CombatSpell spell = config.getPrimarySpell();
        AutocastTask autocastTask = new AutocastTask(spell)
                .withDefensive(config.isDefensiveAutocast());

        log.info("Setting up autocast for {}", spell.getSpellName());

        // Use as sub-task
        activeSubTask = autocastTask;
        autocastTask.execute(ctx);

        // Check if completed
        if (autocastTask.getState() == TaskState.COMPLETED) {
            autocastSetUp = true;
            activeSubTask = null;
            phase = CombatPhase.FIND_TARGET;
            log.info("Autocast setup complete");
        } else if (autocastTask.getState() == TaskState.FAILED) {
            activeSubTask = null;
            log.warn("Autocast setup failed: {}, continuing without autocast",
                    autocastTask.getFailureReason());
            autocastSetUp = true; // Mark as "done" so we don't retry
            phase = CombatPhase.FIND_TARGET;
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
            // Check if safe spot is blocked by aggro NPC targeting us
            if (isSafeSpotBlockedByAggro(ctx, safeSpot)) {
                safeSpotDragAttempts++;
                
                // Check if we've exceeded drag attempt limit
                if (safeSpotDragAttempts > MAX_SAFESPOT_DRAG_ATTEMPTS) {
                    log.warn("Safe spot still blocked after {} drag attempts, attacking anyway", 
                            MAX_SAFESPOT_DRAG_ATTEMPTS);
                    safeSpotDragAttempts = 0;
                    phase = CombatPhase.ATTACK;
                    return;
                }
                
                log.debug("Safe spot blocked by aggro NPC, dragging away (attempt {}/{})", 
                        safeSpotDragAttempts, MAX_SAFESPOT_DRAG_ATTEMPTS);
                // Walk 3-5 tiles away to drag NPC, then return
                WorldPoint dragPosition = calculateDragPosition(playerPos, safeSpot, ctx.getRandomization());
                activeSubTask = new WalkToTask(dragPosition);
                phaseWaitTicks = 0;
                return;
            }
            
            // We're in position, proceed to attack
            phaseWaitTicks = 0;
            safeSpotDragAttempts = 0; // Reset drag counter
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
    
    /**
     * Check if safe spot is blocked by an aggro NPC that's targeting us.
     */
    private boolean isSafeSpotBlockedByAggro(TaskContext ctx, WorldPoint safeSpot) {
        WorldState world = ctx.getWorldState();
        CombatState combat = ctx.getCombatState();
        
        // Get all NPCs at safe spot location
        for (NpcSnapshot npc : world.getNearbyNpcs()) {
            WorldPoint npcPos = npc.getWorldPosition();
            if (npcPos.equals(safeSpot)) {
                // Check if this NPC is aggressive and targeting us
                // Compare NPC index with aggressors list
                int npcIndex = npc.getIndex();
                boolean isAggressive = combat.getAggressiveNpcs().stream()
                    .anyMatch(aggressor -> aggressor.getNpcIndex() == npcIndex);
                if (isAggressive) {
                    log.debug("Safe spot blocked by aggro NPC: {}", npc.getName());
                    return true;
                }
                // Non-aggro NPCs or players are fine - ignore them
            }
        }

        
        return false;
    }
    
    /**
     * Calculate a position to drag aggro NPC away from safe spot.
     */
    private WorldPoint calculateDragPosition(WorldPoint playerPos, WorldPoint safeSpot, Randomization rand) {
        // Calculate direction away from safe spot
        int dx = playerPos.getX() - safeSpot.getX();
        int dy = playerPos.getY() - safeSpot.getY();
        
        // Normalize and extend by 3-5 tiles
        double mag = Math.sqrt(dx * dx + dy * dy);
        if (mag == 0) {
            // Player is exactly on safe spot, pick random direction
            double angle = rand.uniformRandom(0, 2 * Math.PI);
            dx = (int) Math.round(Math.cos(angle) * 4);
            dy = (int) Math.round(Math.sin(angle) * 4);
        } else {
            int dragDistance = rand.uniformRandomInt(3, 5);
            dx = (int) Math.round(dx / mag * dragDistance);
            dy = (int) Math.round(dy / mag * dragDistance);
        }
        
        return new WorldPoint(
            playerPos.getX() + dx,
            playerPos.getY() + dy,
            playerPos.getPlane()
        );
    }

    // ========================================================================
    // Phase: Cast Spell (Magic Combat)
    // ========================================================================

    /**
     * Execute spell selection phase for manual magic combat.
     * Opens spellbook if needed and clicks the spell widget.
     */
    private void executeCastSpell(TaskContext ctx) {
        if (movePending || clickPending) {
            return; // Wait for pending actions
        }

        // Check for phase timeout
        phaseWaitTicks++;
        if (phaseWaitTicks > MAX_WAIT_TICKS) {
            log.warn("Spell selection timed out after {} ticks", MAX_WAIT_TICKS);
            fail("Could not select spell - timed out");
            return;
        }

        // Get the current spell to cast
        CombatSpell spell = getCurrentSpell();
        if (spell == null) {
            log.warn("No spell configured for magic combat, falling back to melee");
            spellSelected = false;
            phase = CombatPhase.ATTACK;
            return;
        }

        Client client = ctx.getClient();

        // Check if spellbook tab is visible
        Widget spellbookWidget = client.getWidget(WIDGET_SPELLBOOK, 0);
        if (spellbookWidget == null || spellbookWidget.isHidden()) {
            // Open spellbook tab using F6 key
            log.debug("Opening spellbook tab to cast {}", spell.getSpellName());
            clickPending = true;
            
            ctx.getKeyboardController().pressKey(java.awt.event.KeyEvent.VK_F6)
                    .thenRun(() -> {
                        clickPending = false;
                        phaseWaitTicks = 0;
                    })
                    .exceptionally(e -> {
                        clickPending = false;
                        log.error("Failed to open spellbook", e);
                        return null;
                    });
            return;
        }

        // Spellbook is open, click on the spell
        int packedWidgetId = spell.getWidgetId();
        int groupId = packedWidgetId >> 16;
        int childId = packedWidgetId & 0xFFFF;

        Widget spellWidget = client.getWidget(groupId, childId);
        if (spellWidget == null || spellWidget.isHidden()) {
            log.warn("Spell widget not found for {} (widget {}:{})", 
                    spell.getSpellName(), groupId, childId);
            phaseWaitTicks++;
            if (phaseWaitTicks > MAX_WAIT_TICKS / 2) {
                // Spell not available, maybe wrong spellbook or level
                log.error("Cannot find spell {}, falling back to melee", spell.getSpellName());
                spellSelected = false;
                phase = CombatPhase.ATTACK;
            }
            return;
        }

        // Calculate click point on spell widget
        Rectangle bounds = spellWidget.getBounds();
        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            log.warn("Spell widget has invalid bounds");
            phaseWaitTicks++;
            return;
        }

        // Humanized click position within spell icon
        int clickX = bounds.x + bounds.width / 2 + ThreadLocalRandom.current().nextInt(-3, 4);
        int clickY = bounds.y + bounds.height / 2 + ThreadLocalRandom.current().nextInt(-3, 4);

        log.debug("Clicking spell {} at ({}, {})", spell.getSpellName(), clickX, clickY);

        movePending = true;
        ctx.getMouseController().moveToCanvas(clickX, clickY)
                .thenCompose(v -> {
                    movePending = false;
                    clickPending = true;
                    return ctx.getMouseController().click();
                })
                .thenRun(() -> {
                    clickPending = false;
                    spellSelected = true;
                    phaseWaitTicks = 0;
                    // Advance spell index for cycling
                    advanceSpellIndex();
                    // Now proceed to click the target
                    phase = CombatPhase.ATTACK;
                })
                .exceptionally(e -> {
                    movePending = false;
                    clickPending = false;
                    log.error("Failed to click spell", e);
                    return null;
                });
    }

    /**
     * Get the current spell to cast based on spell cycle mode.
     */
    private CombatSpell getCurrentSpell() {
        if (!config.isMagicCombat()) {
            return null;
        }

        List<CombatSpell> spells = config.getSpells();
        if (spells == null || spells.isEmpty()) {
            return null;
        }

        // Ensure index is valid
        if (currentSpellIndex >= spells.size()) {
            currentSpellIndex = 0;
        }

        switch (config.getSpellCycleMode()) {
            case RANDOM:
                return spells.get(ThreadLocalRandom.current().nextInt(spells.size()));
            case HIGHEST_AVAILABLE:
                // For now, just return the last spell (assumed highest tier)
                // Full implementation would check runes and level
                return spells.get(spells.size() - 1);
            case IN_ORDER:
            case SEQUENTIAL:
            default:
                return spells.get(currentSpellIndex);
        }
    }

    /**
     * Advance spell index for IN_ORDER cycling.
     */
    private void advanceSpellIndex() {
        if (config.getSpellCycleMode() == CombatTaskConfig.SpellCycleMode.IN_ORDER) {
            List<CombatSpell> spells = config.getSpells();
            if (spells != null && !spells.isEmpty()) {
                currentSpellIndex = (currentSpellIndex + 1) % spells.size();
            }
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
            spellSelected = false;
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        if (movePending || clickPending) {
            return; // Wait for pending actions
        }

        PlayerState player = ctx.getPlayerState();

        // Check if we're already attacking this target
        if (player.isInCombat() && player.getTargetNpcIndex() == currentTarget.getIndex()) {
            spellSelected = false;
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

        // For magic combat with manual casting, need to select spell first
        if (config.isMagicCombat() && !config.isUseAutocast() && !spellSelected) {
            phase = CombatPhase.CAST_SPELL;
            return;
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
                    // Reset spell selection after attack (next attack needs new spell click)
                    spellSelected = false;
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
            
            // Reset spell state for next target
            spellSelected = false;
            
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
                spellSelected = false;
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
                .thenCompose(success -> {
                    clickPending = false;
                    if (success) {
                        // Add humanized delay before next loot using async delay
                        int delay = ThreadLocalRandom.current().nextInt(
                                config.getMinLootDelay(), config.getMaxLootDelay() + 1);
                        return CompletableFuture.runAsync(
                                () -> {},
                                CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                        );
                    }
                    return CompletableFuture.completedFuture(null);
                })
                .thenRun(() -> lootIndex++)
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

        // Check if resupply threshold reached
        if (config.isResupplyConfigured()) {
            int foodCount = inventory.countFood();
            if (config.shouldResupply(foodCount)) {
                log.debug("Food count ({}) at or below resupply threshold ({})",
                        foodCount, config.getMinFoodToResupply());
                return true;
            }
        }

        return false;
    }

    /**
     * Check if resupply should be triggered instead of stopping.
     */
    private boolean shouldTriggerResupply(TaskContext ctx) {
        if (!config.isResupplyConfigured()) {
            return false;
        }

        // Check if max trips reached
        if (config.hasReachedMaxResupplyTrips(resupplyTripsCompleted)) {
            log.info("Max resupply trips ({}) reached", config.getMaxResupplyTrips());
            return false;
        }

        return true;
    }

    /**
     * Initialize and start the resupply process.
     */
    private void startResupply(TaskContext ctx) {
        // Store current position for return
        if (config.isReturnToSameSpot()) {
            preResupplyPosition = ctx.getPlayerState().getWorldPosition();
            log.debug("Stored pre-resupply position: {}", preResupplyPosition);
        }

        // Reset resupply state
        resupplyPhase = ResupplyPhase.WALK_TO_BANK;
        resupplyItemIndex = 0;
        resupplyItemList = config.getResupplyItems() != null
                ? new java.util.ArrayList<>(config.getResupplyItems().entrySet())
                : new java.util.ArrayList<>();

        // Switch to resupply phase
        phase = CombatPhase.RESUPPLY;
    }

    // ========================================================================
    // Phase: Resupply
    // ========================================================================

    /**
     * Execute resupply phase - bank trip for supplies.
     */
    private void executeResupply(TaskContext ctx) {
        if (activeSubTask != null) {
            activeSubTask.execute(ctx);
            if (!activeSubTask.getState().isTerminal()) {
                return;  // Sub-task still running
            }
            activeSubTask = null;
        }

        switch (resupplyPhase) {
            case WALK_TO_BANK:
                executeResupplyWalkToBank(ctx);
                break;
            case DEPOSIT_INVENTORY:
                executeResupplyDeposit(ctx);
                break;
            case WITHDRAW_ITEMS:
                executeResupplyWithdraw(ctx);
                break;
            case CLOSE_BANK:
                executeResupplyCloseBank(ctx);
                break;
            case RETURN_TO_SPOT:
                executeResupplyReturn(ctx);
                break;
        }
    }

    /**
     * Walk to bank for resupply.
     */
    private void executeResupplyWalkToBank(TaskContext ctx) {
        WorldPoint bankLocation = config.getResupplyBankLocation();

        // If no specific bank, just open nearest
        // For now, we'll try to open bank directly if nearby
        Client client = ctx.getClient();
        Widget bankWidget = client.getWidget(BankTask.WIDGET_BANK_GROUP, BankTask.WIDGET_BANK_CONTAINER);
        if (bankWidget != null && !bankWidget.isHidden()) {
            // Bank is already open
            log.debug("Bank already open, proceeding to deposit");
            resupplyPhase = ResupplyPhase.DEPOSIT_INVENTORY;
            return;
        }

        // Need to walk to bank and open it
        if (bankLocation != null) {
            PlayerState player = ctx.getPlayerState();
            WorldPoint playerPos = player.getWorldPosition();

            if (playerPos != null && playerPos.distanceTo(bankLocation) <= 5) {
                // We're at the bank, open it
                log.debug("At bank location, opening bank");
                activeSubTask = BankTask.open();
                return;
            }

            // Walk to bank
            log.debug("Walking to bank at {}", bankLocation);
            WalkToTask walkTask = new WalkToTask(bankLocation);
            walkTask.setDescription("Walk to bank for resupply");
            activeSubTask = walkTask;
        } else {
            // Try to open nearest bank
            log.debug("Opening nearest bank");
            activeSubTask = BankTask.open();
        }
    }

    /**
     * Deposit inventory before withdrawing supplies.
     */
    private void executeResupplyDeposit(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if bank is open
        Widget bankWidget = client.getWidget(BankTask.WIDGET_BANK_GROUP, BankTask.WIDGET_BANK_CONTAINER);
        if (bankWidget == null || bankWidget.isHidden()) {
            // Bank closed unexpectedly
            log.warn("Bank closed during resupply, returning to walk phase");
            resupplyPhase = ResupplyPhase.WALK_TO_BANK;
            return;
        }

        // Deposit all inventory
        log.debug("Depositing inventory");
        activeSubTask = BankTask.depositAll();
        resupplyPhase = ResupplyPhase.WITHDRAW_ITEMS;
    }

    /**
     * Withdraw required items from bank.
     */
    private void executeResupplyWithdraw(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if bank is still open
        Widget bankWidget = client.getWidget(BankTask.WIDGET_BANK_GROUP, BankTask.WIDGET_BANK_CONTAINER);
        if (bankWidget == null || bankWidget.isHidden()) {
            log.warn("Bank closed during resupply withdraw");
            resupplyPhase = ResupplyPhase.WALK_TO_BANK;
            return;
        }

        // Check if we've withdrawn all items
        if (resupplyItemList == null || resupplyItemIndex >= resupplyItemList.size()) {
            log.debug("All items withdrawn, closing bank");
            resupplyPhase = ResupplyPhase.CLOSE_BANK;
            return;
        }

        // Withdraw next item
        java.util.Map.Entry<Integer, Integer> item = resupplyItemList.get(resupplyItemIndex);
        int itemId = item.getKey();
        int quantity = item.getValue();

        log.debug("Withdrawing item {} x{}", itemId, quantity);
        activeSubTask = BankTask.withdraw(itemId, quantity);
        resupplyItemIndex++;
    }

    /**
     * Close bank after withdrawing items.
     */
    private void executeResupplyCloseBank(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if bank is still open
        Widget bankWidget = client.getWidget(BankTask.WIDGET_BANK_GROUP, BankTask.WIDGET_BANK_CONTAINER);
        if (bankWidget == null || bankWidget.isHidden()) {
            // Already closed
            log.debug("Bank already closed");
            resupplyTripsCompleted++;
            resupplyPhase = config.isReturnToSameSpot() && preResupplyPosition != null
                    ? ResupplyPhase.RETURN_TO_SPOT
                    : null;

            if (resupplyPhase == null) {
                // No return needed, resume combat
                phase = CombatPhase.FIND_TARGET;
            }
            return;
        }

        // Close bank by pressing Escape
        clickPending = true;
        ctx.getKeyboardController().pressKey(java.awt.event.KeyEvent.VK_ESCAPE)
                .thenRun(() -> {
                    clickPending = false;
                    resupplyTripsCompleted++;
                    resupplyPhase = config.isReturnToSameSpot() && preResupplyPosition != null
                            ? ResupplyPhase.RETURN_TO_SPOT
                            : null;

                    if (resupplyPhase == null) {
                        phase = CombatPhase.FIND_TARGET;
                    }
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Failed to close bank", e);
                    return null;
                });
    }

    /**
     * Return to previous position after resupply.
     */
    private void executeResupplyReturn(TaskContext ctx) {
        if (preResupplyPosition == null) {
            log.debug("No return position stored, resuming combat");
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos != null && playerPos.distanceTo(preResupplyPosition) <= 3) {
            log.info("Returned to combat position, resuming (trip #{})", resupplyTripsCompleted);
            preResupplyPosition = null;
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        // Walk back to original position
        if (activeSubTask == null) {
            log.debug("Walking back to {}", preResupplyPosition);
            WalkToTask walkTask = new WalkToTask(preResupplyPosition);
            walkTask.setDescription("Return to combat spot after resupply");
            activeSubTask = walkTask;
        }
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
         * Setting up autocast for magic combat (one-time setup).
         */
        SETUP_AUTOCAST,

        /**
         * Looking for a valid target.
         */
        FIND_TARGET,

        /**
         * Moving to safe-spot position.
         */
        POSITION,

        /**
         * Selecting spell from spellbook (magic combat only, non-autocast).
         */
        CAST_SPELL,

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
        LOOT,

        /**
         * Banking for resupply.
         */
        RESUPPLY
    }
}

