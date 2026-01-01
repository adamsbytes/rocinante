package com.rocinante.tasks.impl.skills.firemaking;

import com.rocinante.progression.MethodLocation;
import com.rocinante.progression.TrainingMethod;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.impl.InteractObjectTask;
import com.rocinante.tasks.impl.WalkToTask;
import com.rocinante.util.CollectionResolver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Task for combined gather-and-firemake training.
 *
 * <p>Workflow:
 * <ol>
 *   <li>GATHER: Chop trees until inventory is full</li>
 *   <li>PROCESS: Burn all logs (logs are consumed)</li>
 *   <li>CHECK_CONTINUE: Evaluate if training should continue</li>
 * </ol>
 *
 * <p>Since logs are consumed during firemaking, no drop/bank phase is needed.
 * The inventory becomes empty (except tinderbox) after each firemaking cycle.
 *
 * <p>This task tracks XP gains for both woodcutting (primary) and firemaking (secondary) skills.
 */
@Slf4j
public class GatherAndFiremakeTask extends AbstractTask {

    // ========================================================================
    // Phase Definitions
    // ========================================================================

    private enum GatherFiremakePhase {
        /** Initial navigation to trees. */
        WALK_TO_GATHER,
        /** Actively chopping - waiting for inventory to fill. */
        GATHERING,
        /** Burning all logs. */
        PROCESSING,
        /** Evaluate whether to continue training. */
        CHECK_CONTINUE
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    @Getter
    private final GatherAndFiremakeConfig config;

    // ========================================================================
    // Execution State
    // ========================================================================

    /** Current phase in the gather-firemake cycle. */
    private GatherFiremakePhase phase = GatherFiremakePhase.WALK_TO_GATHER;

    /** Active sub-task being executed. */
    @Nullable
    private Task currentSubTask;

    /** Number of complete gather-firemake cycles. */
    private int cycleCount = 0;

    /** Woodcutting XP at start of training. */
    private int startWoodcuttingXp = -1;

    /** Firemaking XP at start of training. */
    private int startFiremakingXp = -1;

    /** Training session start time. */
    @Nullable
    private Instant startTime;

    /** Whether session is initialized. */
    private boolean initialized = false;

    // ========================================================================
    // Constructor
    // ========================================================================

    public GatherAndFiremakeTask(GatherAndFiremakeConfig config) {
        this.config = config;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (!initialized) {
            initialize(ctx);
        }

        // Check if we've reached target levels or XP
        if (shouldStopTraining(ctx)) {
            complete();
            return;
        }

        // Handle active sub-task
        if (currentSubTask != null) {
            currentSubTask.execute(ctx);
            if (!currentSubTask.getState().isTerminal()) {
                return;
            }
            handleSubTaskComplete(ctx);
            currentSubTask = null;
            return;
        }

        // Execute current phase
        switch (phase) {
            case WALK_TO_GATHER:
                executeWalkToGather(ctx);
                break;
            case GATHERING:
                executeGathering(ctx);
                break;
            case PROCESSING:
                executeProcessing(ctx);
                break;
            case CHECK_CONTINUE:
                executeCheckContinue(ctx);
                break;
        }
    }

    @Override
    public String getDescription() {
        TrainingMethod method = config.getMethod();
        return String.format("Gather and Firemake: %s (cycle %d, phase: %s)",
                method.getName(), cycleCount, phase);
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    private void initialize(TaskContext ctx) {
        Client client = ctx.getClient();
        startWoodcuttingXp = client.getSkillExperience(Skill.WOODCUTTING);
        startFiremakingXp = client.getSkillExperience(Skill.FIREMAKING);
        startTime = Instant.now();
        initialized = true;

        log.info("Starting gather-and-firemake training: {} (Woodcutting: {}, Firemaking: {})",
                config.getMethod().getName(),
                client.getRealSkillLevel(Skill.WOODCUTTING),
                client.getRealSkillLevel(Skill.FIREMAKING));
    }

    // ========================================================================
    // Target Checking
    // ========================================================================

    private boolean shouldStopTraining(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check woodcutting level target
        if (config.getTargetWoodcuttingLevel() > 0) {
            int currentLevel = client.getRealSkillLevel(Skill.WOODCUTTING);
            if (currentLevel >= config.getTargetWoodcuttingLevel()) {
                log.info("Reached target woodcutting level: {}", currentLevel);
                return true;
            }
        }

        // Check firemaking level target
        if (config.getTargetFiremakingLevel() > 0) {
            int currentLevel = client.getRealSkillLevel(Skill.FIREMAKING);
            if (currentLevel >= config.getTargetFiremakingLevel()) {
                log.info("Reached target firemaking level: {}", currentLevel);
                return true;
            }
        }

        // Check woodcutting XP target
        if (config.getTargetWoodcuttingXp() > 0) {
            int gained = client.getSkillExperience(Skill.WOODCUTTING) - startWoodcuttingXp;
            if (gained >= config.getTargetWoodcuttingXp()) {
                log.info("Reached target woodcutting XP: {}", gained);
                return true;
            }
        }

        // Check firemaking XP target
        if (config.getTargetFiremakingXp() > 0) {
            int gained = client.getSkillExperience(Skill.FIREMAKING) - startFiremakingXp;
            if (gained >= config.getTargetFiremakingXp()) {
                log.info("Reached target firemaking XP: {}", gained);
                return true;
            }
        }

        // Check duration
        Duration maxDuration = config.getMaxDuration();
        if (maxDuration != null && startTime != null) {
            Duration elapsed = Duration.between(startTime, Instant.now());
            if (elapsed.compareTo(maxDuration) >= 0) {
                log.info("Reached max training duration: {}", maxDuration);
                return true;
            }
        }

        return false;
    }

    // ========================================================================
    // Phase Implementations
    // ========================================================================

    private void executeWalkToGather(TaskContext ctx) {
        MethodLocation location = config.getLocation();
        if (location == null) {
            log.warn("No location configured for gather-and-firemake");
            fail("No location configured");
            return;
        }

        WorldPoint gatherArea = location.getTrainingArea();
        if (gatherArea == null) {
            gatherArea = location.getExactPosition();
        }

        if (gatherArea == null) {
            log.warn("No training area defined for location: {}", location.getId());
            fail("No training area defined");
            return;
        }

        PlayerState player = ctx.getPlayerState();
        int distance = player.getWorldPosition().distanceTo(gatherArea);

        // Use 15 tiles from training area as threshold
        if (distance <= 15) {
            log.debug("At tree location, starting to gather");
            phase = GatherFiremakePhase.GATHERING;
            return;
        }

        currentSubTask = new WalkToTask(gatherArea)
                .withDescription("Walk to trees");
    }

    private void executeGathering(TaskContext ctx) {
        TrainingMethod method = config.getMethod();
        InventoryState inventory = ctx.getInventoryState();

        // Check if inventory is full
        int freeSlots = 28 - inventory.getUsedSlots();
        if (freeSlots <= 0) {
            log.debug("Inventory full, switching to firemaking");
            phase = GatherFiremakePhase.PROCESSING;
            return;
        }

        // Start chopping interaction
        if (!method.hasTargetObjects()) {
            log.warn("GATHER_AND_PROCESS method {} has no target objects for chopping", method.getId());
            fail("No trees configured");
            return;
        }

        List<Integer> expandedTrees = CollectionResolver.expandObjectIds(method.getTargetObjectIds());
        String menuAction = method.getMenuAction() != null ? method.getMenuAction() : "Chop down";

        InteractObjectTask chopTask = new InteractObjectTask(expandedTrees.get(0), menuAction);
        if (expandedTrees.size() > 1) {
            chopTask.withAlternateIds(expandedTrees.subList(1, expandedTrees.size()));
        }

        if (!method.getSuccessAnimationIds().isEmpty()) {
            chopTask.withSuccessAnimations(method.getSuccessAnimationIds());
        }

        // For gathering, don't accept position change as success - wait for inventory change
        // This prevents the bot from switching targets while walking to a tree
        chopTask.setAcceptPositionChange(false);
        chopTask.setWaitForInventoryChange(true);

        currentSubTask = chopTask;
    }

    private void executeProcessing(TaskContext ctx) {
        TrainingMethod method = config.getMethod();
        InventoryState inventory = ctx.getInventoryState();

        int logId = method.getSourceItemId();

        // Check if we still have logs to burn
        if (!inventory.hasItem(logId)) {
            log.debug("All logs burned, moving to check continue");
            phase = GatherFiremakePhase.CHECK_CONTINUE;
            return;
        }

        // Create dynamic firemaking task - burn from current position
        if (currentSubTask == null) {
            int logCount = inventory.countItem(logId);

            FiremakingConfig fmConfig = FiremakingConfig.builder()
                    .logItemId(logId)
                    .startPosition(null)  // Dynamic mode - burn here!
                    .burnHereSearchRadius(15)  // Search a bit wider for clear spots
                    .burnHereWalkThreshold(20)  // Willing to walk back to trees
                    .targetLogsBurned(logCount)  // Burn all logs in inventory
                    .bankForLogs(false)  // Never bank - we're in gather-burn cycle
                    .build();

            currentSubTask = new FiremakingSkillTask(fmConfig)
                    .withDescription("Burn logs from cutting");

            log.debug("Starting firemaking for {} logs at current location", logCount);
        }
    }

    private void executeCheckContinue(TaskContext ctx) {
        cycleCount++;

        log.info("Completed gather-firemake cycle {}. Woodcutting XP: +{}, Firemaking XP: +{}",
                cycleCount,
                ctx.getClient().getSkillExperience(Skill.WOODCUTTING) - startWoodcuttingXp,
                ctx.getClient().getSkillExperience(Skill.FIREMAKING) - startFiremakingXp);

        if (shouldStopTraining(ctx)) {
            complete();
            return;
        }

        phase = GatherFiremakePhase.WALK_TO_GATHER;
    }

    // ========================================================================
    // Sub-Task Completion
    // ========================================================================

    private void handleSubTaskComplete(TaskContext ctx) {
        if (currentSubTask == null) {
            return;
        }

        TaskState subState = currentSubTask.getState();

        if (subState == TaskState.FAILED) {
            log.warn("Sub-task failed in phase {}: {}",
                    phase, currentSubTask.getDescription());
            if (phase == GatherFiremakePhase.GATHERING) {
                log.debug("Retrying gather phase");
                return;
            }
            fail("Sub-task failed: " + currentSubTask.getDescription());
            return;
        }

        switch (phase) {
            case WALK_TO_GATHER:
                phase = GatherFiremakePhase.GATHERING;
                break;

            case GATHERING:
                if (ctx.getInventoryState().getUsedSlots() >= 28) {
                    phase = GatherFiremakePhase.PROCESSING;
                }
                break;

            case PROCESSING:
                // Firemaking task completed (or failed)
                if (!ctx.getInventoryState().hasItem(config.getMethod().getSourceItemId())) {
                    log.debug("All logs burned after firemaking sub-task completed");
                    phase = GatherFiremakePhase.CHECK_CONTINUE;
                } else {
                    // Still have logs - unexpected completion, but retry
                    log.warn("Firemaking completed but {} logs remain, retrying",
                            ctx.getInventoryState().countItem(config.getMethod().getSourceItemId()));
                    // currentSubTask is cleared after this method, so executeProcessing will create new task
                }
                break;

            case CHECK_CONTINUE:
                break;
        }
    }
}
