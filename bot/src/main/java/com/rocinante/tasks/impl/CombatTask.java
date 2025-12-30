package com.rocinante.tasks.impl;

import com.rocinante.combat.CombatManager;
import com.rocinante.combat.TargetSelector;
import com.rocinante.combat.spell.CombatSpell;
import com.rocinante.state.*;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.InteractionHelper;
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
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
     * Number of attacks/casts completed.
     * Used when attackCount is set (e.g., Tutorial Island: cast spell once).
     */
    @Getter
    private int attacksCompleted = 0;

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
     * Interaction helper for camera rotation and clickbox handling.
     */
    private InteractionHelper interactionHelper;

    /**
     * Maximum ticks to wait before considering target lost.
     */
    private static final int MAX_WAIT_TICKS = 20;

    /**
     * Maximum ticks to wait for combat to start after clicking a target.
     * For ranged/magic, if combat doesn't start, the target may be obstructed.
     */
    private static final int MAX_ATTACK_WAIT_TICKS = 8;

    /**
     * Ticks since we clicked on target (waiting for combat to start).
     */
    private int attackWaitTicks = 0;

    /**
     * Set of NPC indices that we've tried to attack but couldn't hit.
     * These are temporarily skipped to try other targets.
     */
    private final Set<Integer> failedTargetIndices = new HashSet<>();

    /**
     * Tick count when failed targets were last cleared.
     */
    private int failedTargetsClearTick = 0;

    /**
     * Clear failed targets every N ticks to allow retrying.
     */
    private static final int FAILED_TARGETS_CLEAR_INTERVAL = 50;

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
     * Active resupply task (when in RESUPPLY phase).
     */
    private ResupplyTask activeResupplyTask;

    // ========================================================================
    // Bone Burying State
    // ========================================================================

    /**
     * Total bones buried in this combat session.
     */
    @Getter
    private int totalBonesBuried = 0;

    /**
     * Kills since the last bone burying session.
     */
    private int killsSinceLastBury = 0;

    /**
     * Timestamp of the last kill (for timing bone burying).
     */
    private Instant lastKillTime;

    /**
     * Active bone burying task (when in BURY_BONES phase).
     */
    private BuryBonesTask activeBuryBonesTask;

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
    protected void resetImpl() {
        // Reset all execution state for retry
        phase = CombatPhase.FIND_TARGET;
        currentTarget = null;
        lastTargetPosition = null;
        // Note: killsCompleted is NOT reset - this is cumulative for the task
        taskStartTime = null;
        movePending = false;
        clickPending = false;
        idleTicks = 0;
        phaseWaitTicks = 0;
        attackWaitTicks = 0;
        failedTargetIndices.clear();
        failedTargetsClearTick = 0;
        if (interactionHelper != null) {
            interactionHelper.reset();
        }
        log.debug("CombatTask reset for retry");
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

        // Check for "I can't reach that" message - need to return to home position
        if (phase != CombatPhase.RETURN_HOME && ctx.getGameStateService().wasCantReachRecent(5)) {
            log.info("Detected 'can't reach' - returning to home position");
            ctx.getGameStateService().clearCantReachFlag();
            currentTarget = null;
            spellSelected = false;
            phase = CombatPhase.RETURN_HOME;
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
            case BURY_BONES:
                executeBuryBones(ctx);
                break;
            case RESUPPLY:
                executeResupply(ctx);
                break;
            case RETURN_HOME:
                executeReturnHome(ctx);
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

        // Periodically clear failed targets to allow retrying
        int currentTick = getExecutionTicks();
        if (currentTick - failedTargetsClearTick > FAILED_TARGETS_CLEAR_INTERVAL) {
            if (!failedTargetIndices.isEmpty()) {
                log.debug("Clearing {} failed targets after {} ticks", 
                        failedTargetIndices.size(), FAILED_TARGETS_CLEAR_INTERVAL);
                failedTargetIndices.clear();
            }
            failedTargetsClearTick = currentTick;
        }

        // If player is in combat, go to monitor phase
        if (player.isInCombat()) {
            updateCurrentTarget(ctx);
            if (currentTarget != null) {
                phase = CombatPhase.MONITOR_COMBAT;
                return;
            }
        }

        // Check for forced target from subclass (e.g., superior creatures in SlayerTask)
        Optional<NpcSnapshot> forcedTarget = getForcedTarget(ctx);
        if (forcedTarget.isPresent()) {
            currentTarget = forcedTarget.get();
            lastTargetPosition = currentTarget.getWorldPosition();
            idleTicks = 0;
            phaseWaitTicks = 0;
            attackWaitTicks = 0;
            log.debug("Using forced target: {}", currentTarget.getSummary());
            
            // Go directly to attack for priority targets
            phase = CombatPhase.ATTACK;
            return;
        }

        // Select a new target via normal selection, excluding failed targets
        Optional<NpcSnapshot> target = targetSelector.selectTarget()
                .filter(npc -> !failedTargetIndices.contains(npc.getIndex()));

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
        interactionHelper = null; // Reset for new target
        phaseWaitTicks = 0;
        attackWaitTicks = 0;

        log.debug("Selected target: {}", currentTarget.getSummary());

        // Determine next phase
        if (config.isSafeSpotConfigured()) {
            phase = CombatPhase.POSITION;
        } else {
            phase = CombatPhase.ATTACK;
        }
    }
    
    /**
     * Hook for subclasses to provide a forced target that takes priority over normal selection.
     * 
     * <p>Override this method to implement priority targeting (e.g., superior creatures
     * in SlayerTask). When this returns a non-empty Optional, that target will be used
     * instead of the normal target selection logic.
     *
     * @param ctx the task context
     * @return Optional containing the forced target, or empty to use normal selection
     */
    protected Optional<NpcSnapshot> getForcedTarget(TaskContext ctx) {
        return Optional.empty();
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
            // Open spellbook tab using helper (handles already-open check internally)
            log.debug("Opening spellbook tab to cast {}", spell.getSpellName());
            clickPending = true;
            
            com.rocinante.util.WidgetInteractionHelpers.openTabAsync(ctx, 
                    com.rocinante.util.WidgetInteractionHelpers.TAB_SPELLBOOK, null)
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

        // Use centralized ClickPointCalculator for humanized positioning
        java.awt.Point clickPoint = com.rocinante.input.ClickPointCalculator.getGaussianClickPoint(bounds);
        int clickX = clickPoint.x;
        int clickY = clickPoint.y;

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
            // Waiting for click to complete - increment attack wait counter
            attackWaitTicks++;
            return;
        }

        PlayerState player = ctx.getPlayerState();

        // Check if we're already attacking this target
        if (player.isInCombat() && player.getTargetNpcIndex() == currentTarget.getIndex()) {
            spellSelected = false;
            attackWaitTicks = 0; // Successfully started combat
            phase = CombatPhase.MONITOR_COMBAT;
            return;
        }

        // Check if we've been waiting too long for combat to start
        // This means we clicked but couldn't actually hit the target (e.g., obstructed for ranged)
        if (attackWaitTicks > MAX_ATTACK_WAIT_TICKS && config.getTargetConfig() != null 
                && !config.getTargetConfig().isSkipUnreachable()) {
            log.debug("Attack failed after {} ticks - marking target {} as failed (obstructed?)", 
                    attackWaitTicks, currentTarget.getIndex());
            failedTargetIndices.add(currentTarget.getIndex());
            currentTarget = null;
            spellSelected = false;
            attackWaitTicks = 0;
            phase = CombatPhase.FIND_TARGET;
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

        // Initialize interaction helper if needed
        if (interactionHelper == null) {
            interactionHelper = new InteractionHelper(ctx);
            interactionHelper.startCameraRotation(targetNpc.getWorldLocation());
        }

        // Use centralized click point resolution
        InteractionHelper.ClickPointResult result = interactionHelper.getClickPointForNpc(targetNpc);
        
        Point clickPoint;
        if (result.hasPoint()) {
            clickPoint = result.point;
        } else if (result.shouldRotateCamera) {
            interactionHelper.startCameraRotation(targetNpc.getWorldLocation());
            return;
        } else if (result.shouldWait) {
            return;
        } else {
            log.warn("Cannot get click point for target: {}", result.reason);
            phaseWaitTicks++;
            if (phaseWaitTicks > MAX_WAIT_TICKS) {
                currentTarget = null;
                interactionHelper = null; // Reset for next target
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
                    // Count this attack (for attackCount-based completion)
                    attacksCompleted++;
                    log.debug("Attack {} completed", attacksCompleted);
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
            killsSinceLastBury++;
            lastKillTime = Instant.now();
            
            // Reset spell state for next target
            spellSelected = false;
            
            // Prepare to loot
            if (config.isLootEnabled() || config.isBuryBonesEnabled()) {
                prepareLoot(ctx);
                if (pendingLoot != null && !pendingLoot.isEmpty()) {
                    phase = CombatPhase.LOOT;
                    return;
                }
            }
            
            // Check if we should bury bones after looting (even if no loot)
            if (shouldTriggerBoneBurying(ctx)) {
                startBoneBurying(ctx);
                return;
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
            // Done looting - check if we should bury bones
            pendingLoot = null;
            lootIndex = 0;
            if (shouldTriggerBoneBurying(ctx)) {
                startBoneBurying(ctx);
                return;
            }
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        // Check max loot per kill
        if (config.getMaxLootPerKill() > 0 && lootIndex >= config.getMaxLootPerKill()) {
            log.debug("Max loot per kill reached ({})", config.getMaxLootPerKill());
            pendingLoot = null;
            lootIndex = 0;
            if (shouldTriggerBoneBurying(ctx)) {
                startBoneBurying(ctx);
                return;
            }
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        // Check inventory space
        InventoryState inventory = ctx.getInventoryState();
        if (inventory.isFull()) {
            log.debug("Inventory full, skipping remaining loot");
            pendingLoot = null;
            lootIndex = 0;
            // Bury bones to make room if enabled
            if (shouldTriggerBoneBurying(ctx)) {
                startBoneBurying(ctx);
                return;
            }
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

        // Check if account is ironman for loot restrictions
        var ironmanState = ctx.getIronmanState();
        boolean isIronman = ironmanState.isIronman();

        // Get ground items near the kill location
        List<GroundItemSnapshot> nearbyItems = worldState.getGroundItems().stream()
                .filter(item -> item.getWorldPosition().distanceTo(targetPos) <= 3)
                .filter(item -> config.shouldLootItem(item.getId(), (int) item.getTotalGeValue()))
                // Ironmen can only pick up their own loot (private items)
                .filter(item -> !isIronman || item.isPrivateItem())
                .sorted(Comparator.comparingLong(GroundItemSnapshot::getTotalGeValue).reversed())
                .collect(Collectors.toList());

        if (!nearbyItems.isEmpty()) {
            pendingLoot = nearbyItems;
            lootIndex = 0;
            log.debug("Found {} items to loot{}", nearbyItems.size(), 
                    isIronman ? " (ironman mode - own drops only)" : "");
        } else {
            pendingLoot = null;
        }
    }

    // ========================================================================
    // Phase: Bury Bones
    // ========================================================================

    /**
     * Check if bone burying should be triggered.
     * Based on config settings: min kills, min time, and inventory bones.
     */
    private boolean shouldTriggerBoneBurying(TaskContext ctx) {
        if (!config.isBuryBonesEnabled()) {
            return false;
        }

        // Check if we have any bones in inventory
        InventoryState inventory = ctx.getInventoryState();
        boolean hasBones = com.rocinante.util.ItemCollections.BONES.stream()
                .anyMatch(inventory::hasItem);
        
        if (!hasBones) {
            return false;
        }

        // Check if we've reached max ratio (never bury more than kills * ratio)
        int maxBones = config.getMaxBonesToBury(killsCompleted);
        if (totalBonesBuried >= maxBones) {
            log.debug("At max bone bury ratio ({}/{}), skipping", totalBonesBuried, maxBones);
            return false;
        }

        // Calculate seconds since last kill
        long secondsSinceLastKill = 0;
        if (lastKillTime != null) {
            secondsSinceLastKill = Duration.between(lastKillTime, Instant.now()).getSeconds();
        }

        // Use config method to check timing
        boolean shouldBury = config.shouldBuryBones(killsSinceLastBury, secondsSinceLastKill);
        
        if (shouldBury) {
            log.debug("Bone burying triggered: {} kills since last bury, {}s since last kill",
                    killsSinceLastBury, secondsSinceLastKill);
        }
        
        return shouldBury;
    }

    /**
     * Start a bone burying session.
     */
    private void startBoneBurying(TaskContext ctx) {
        // Calculate how many bones we can bury (respect ratio limit)
        int maxAllowed = config.getMaxBonesToBury(killsCompleted) - totalBonesBuried;
        
        if (maxAllowed <= 0) {
            log.debug("Cannot bury more bones - at ratio limit");
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        log.info("Starting bone burying session (max {} bones, buried {} so far)", 
                maxAllowed, totalBonesBuried);
        
        // Create the BuryBonesTask with the max limit
        activeBuryBonesTask = new BuryBonesTask()
                .withMaxBones(maxAllowed)
                .withDescription("Bury bones during combat");
        
        phase = CombatPhase.BURY_BONES;
    }

    /**
     * Execute bone burying phase - delegates to BuryBonesTask.
     */
    private void executeBuryBones(TaskContext ctx) {
        if (activeBuryBonesTask == null) {
            log.error("Bury bones phase without active task");
            phase = CombatPhase.FIND_TARGET;
            return;
        }

        // Execute the bury bones task
        activeBuryBonesTask.execute(ctx);

        // Check if complete
        if (activeBuryBonesTask.getState().isTerminal()) {
            int buried = activeBuryBonesTask.getBonesBuried();
            totalBonesBuried += buried;
            killsSinceLastBury = 0; // Reset kill counter after burying
            
            if (activeBuryBonesTask.getState() == TaskState.FAILED) {
                log.warn("Bone burying task failed: {}", activeBuryBonesTask.getFailureReason());
            } else {
                log.info("Buried {} bones (total: {})", buried, totalBonesBuried);
            }
            
            activeBuryBonesTask = null;
            currentTarget = null;
            phase = CombatPhase.FIND_TARGET;
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private boolean isCompleted(TaskContext ctx) {
        // Check attack count first (takes priority - e.g., "cast spell once")
        if (config.hasAttackCountLimit() && attacksCompleted >= config.getAttackCount()) {
            log.info("Attack count reached: {}/{}", attacksCompleted, config.getAttackCount());
            return true;
        }

        // Check kill count (only if attackCount not set)
        if (!config.hasAttackCountLimit() && config.hasKillCountLimit() 
                && killsCompleted >= config.getKillCount()) {
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
        // Determine return position
        WorldPoint returnPos = null;
        if (config.isReturnToSameSpot()) {
            returnPos = ctx.getPlayerState().getWorldPosition();
            log.debug("Stored pre-resupply position: {}", returnPos);
        }

        // Build resupply task
        ResupplyTask.ResupplyTaskBuilder builder = ResupplyTask.builder()
                .depositInventory(true);
        
        // Add configured bank location
        if (config.getResupplyBankLocation() != null) {
            builder.bankLocation(config.getResupplyBankLocation());
        }
        
        // Add items to withdraw
        if (config.getResupplyItems() != null) {
            builder.withdrawItems(config.getResupplyItems());
        }
        
        // Add return position if configured
        if (returnPos != null) {
            builder.returnPosition(returnPos);
        }
        
        activeResupplyTask = builder.build();
        log.info("Starting resupply task for {} item types", 
                config.getResupplyItems() != null ? config.getResupplyItems().size() : 0);

        // Switch to resupply phase
        phase = CombatPhase.RESUPPLY;
    }

    // ========================================================================
    // Phase: Resupply
    // ========================================================================

    /**
     * Execute resupply phase - delegates to ResupplyTask.
     */
    private void executeResupply(TaskContext ctx) {
        if (activeResupplyTask == null) {
            log.error("Resupply phase without active resupply task");
            phase = CombatPhase.FIND_TARGET;
            return;
        }
        
        // Execute the resupply task
        activeResupplyTask.execute(ctx);
        
        // Check if complete
        if (activeResupplyTask.getState().isTerminal()) {
            if (activeResupplyTask.getState() == TaskState.FAILED) {
                log.warn("Resupply task failed: {}", activeResupplyTask.getDescription());
                // Try to recover by resuming combat anyway
            } else {
                log.info("Resupply trip #{} complete", resupplyTripsCompleted + 1);
            }
            
            resupplyTripsCompleted++;
            activeResupplyTask = null;
            phase = CombatPhase.FIND_TARGET;
        }
    }

    // ========================================================================
    // Phase: Return Home
    // ========================================================================

    /**
     * Execute return home phase - walk back to combat location after "can't reach" failure.
     * Once back at combat location, resume finding targets.
     */
    private void executeReturnHome(TaskContext ctx) {
        WorldPoint combatLoc = config.getCombatLocation();
        if (combatLoc == null) {
            log.error("RETURN_HOME phase without combat location configured - cannot recover");
            fail("No combat location configured for recovery");
            return;
        }

        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Check if we've arrived at combat location
        int distanceToCombat = playerPos.distanceTo(combatLoc);
        if (distanceToCombat <= 2) {
            log.info("Returned to combat location, resuming combat");
            phase = CombatPhase.FIND_TARGET;
            phaseWaitTicks = 0;
            return;
        }

        // Need to walk back - use WalkToTask
        if (activeSubTask == null && !player.isMoving()) {
            log.debug("Walking back to combat location: {} (distance={})", combatLoc, distanceToCombat);
            activeSubTask = new WalkToTask(combatLoc);
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
        String base = String.format("CombatTask[%s, kills=%d/%d]", 
                config.getTargetConfig().getSummary(),
                killsCompleted,
                config.hasKillCountLimit() ? config.getKillCount() : -1);
        if (config.isBuryBonesEnabled() && totalBonesBuried > 0) {
            return base + String.format(", buried=%d bones", totalBonesBuried);
        }
        return base;
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
         * Burying bones collected during combat.
         */
        BURY_BONES,

        /**
         * Banking for resupply.
         */
        RESUPPLY,

        /**
         * Returning to home position after "I can't reach that" failure.
         */
        RETURN_HOME
    }
}

