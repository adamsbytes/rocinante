package com.rocinante.tasks.impl.skills.fletching;

import com.rocinante.progression.MethodLocation;
import com.rocinante.progression.TrainingMethod;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.impl.DropInventoryTask;
import com.rocinante.tasks.impl.InteractObjectTask;
import com.rocinante.tasks.impl.ProcessItemTask;
import com.rocinante.tasks.impl.ResupplyTask;
import com.rocinante.tasks.impl.WalkToTask;
import com.rocinante.util.CollectionResolver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Task for combined gather-and-fletch training.
 *
 * <p>Workflow:
 * <ol>
 *   <li>GATHER: Chop trees until inventory is full</li>
 *   <li>PROCESS: Fletch all logs into arrow shafts (knife on logs)</li>
 *   <li>HANDLE_PRODUCTS: Drop/retain/bank arrow shafts</li>
 *   <li>CHECK_CONTINUE: Evaluate if training should continue</li>
 * </ol>
 *
 * <p>Since arrow shafts are stackable, this method is efficient:
 * <ul>
 *   <li>Chop 27 logs (keeping knife)</li>
 *   <li>Fletch to 405 arrow shafts (15 per log)</li>
 *   <li>Repeat - shafts stack, so no banking needed</li>
 * </ul>
 *
 * <p>This task tracks XP gains for both woodcutting (primary) and fletching (secondary) skills.
 */
@Slf4j
public class GatherAndFletchTask extends AbstractTask {

    // ========================================================================
    // Phase Definitions
    // ========================================================================

    private enum GatherFletchPhase {
        /** Initial navigation to trees. */
        WALK_TO_GATHER,
        /** Actively chopping - waiting for inventory to fill. */
        GATHERING,
        /** Fletching all logs. */
        PROCESSING,
        /** Handle products (drop/retain/bank). */
        HANDLE_PRODUCTS,
        /** Evaluate whether to continue training. */
        CHECK_CONTINUE
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    @Getter
    private final GatherAndFletchConfig config;

    // ========================================================================
    // Execution State
    // ========================================================================

    /** Current phase in the gather-fletch cycle. */
    private GatherFletchPhase phase = GatherFletchPhase.WALK_TO_GATHER;

    /** Active sub-task being executed. */
    @Nullable
    private Task currentSubTask;

    /** Number of complete gather-fletch cycles. */
    private int cycleCount = 0;

    /** Woodcutting XP at start of training. */
    private int startWoodcuttingXp = -1;

    /** Fletching XP at start of training. */
    private int startFletchingXp = -1;

    /** Training session start time. */
    @Nullable
    private Instant startTime;

    /** Whether session is initialized. */
    private boolean initialized = false;

    // ========================================================================
    // Log Mappings
    // ========================================================================

    private static final Map<Integer, String> LOG_TO_TREE_ACTION = Map.of(
            ItemID.LOGS, "Chop down",
            ItemID.OAK_LOGS, "Chop down",
            ItemID.WILLOW_LOGS, "Chop down",
            ItemID.MAPLE_LOGS, "Chop down",
            ItemID.YEW_LOGS, "Chop down",
            ItemID.MAGIC_LOGS, "Chop down"
    );

    // ========================================================================
    // Constructor
    // ========================================================================

    public GatherAndFletchTask(GatherAndFletchConfig config) {
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
            case HANDLE_PRODUCTS:
                executeHandleProducts(ctx);
                break;
            case CHECK_CONTINUE:
                executeCheckContinue(ctx);
                break;
        }
    }

    @Override
    public String getDescription() {
        TrainingMethod method = config.getMethod();
        return String.format("Gather and Fletch: %s (cycle %d, phase: %s)",
                method.getName(), cycleCount, phase);
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    private void initialize(TaskContext ctx) {
        Client client = ctx.getClient();
        startWoodcuttingXp = client.getSkillExperience(Skill.WOODCUTTING);
        startFletchingXp = client.getSkillExperience(Skill.FLETCHING);
        startTime = Instant.now();
        initialized = true;

        log.info("Starting gather-and-fletch training: {} (Woodcutting: {}, Fletching: {})",
                config.getMethod().getName(),
                client.getRealSkillLevel(Skill.WOODCUTTING),
                client.getRealSkillLevel(Skill.FLETCHING));
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

        // Check fletching level target
        if (config.getTargetFletchingLevel() > 0) {
            int currentLevel = client.getRealSkillLevel(Skill.FLETCHING);
            if (currentLevel >= config.getTargetFletchingLevel()) {
                log.info("Reached target fletching level: {}", currentLevel);
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

        // Check fletching XP target
        if (config.getTargetFletchingXp() > 0) {
            int gained = client.getSkillExperience(Skill.FLETCHING) - startFletchingXp;
            if (gained >= config.getTargetFletchingXp()) {
                log.info("Reached target fletching XP: {}", gained);
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
            log.warn("No location configured for gather-and-fletch");
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
            // Close enough, start chopping
            log.debug("At tree location, starting to gather");
            phase = GatherFletchPhase.GATHERING;
            return;
        }

        // Walk to trees
        currentSubTask = new WalkToTask(gatherArea)
                .withDescription("Walk to trees");
    }

    private void executeGathering(TaskContext ctx) {
        TrainingMethod method = config.getMethod();
        InventoryState inventory = ctx.getInventoryState();

        // Check if inventory is full (27 slots for logs, 1 for knife)
        // Actually check if we have no more space for logs
        int logId = method.getSourceItemId(); // The log type we're collecting
        int freeSlots = 28 - inventory.getUsedSlots();

        // Need at least 1 free slot for a log
        if (freeSlots <= 0) {
            log.debug("Inventory full, switching to fletching");
            phase = GatherFletchPhase.PROCESSING;
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

        // Set success animations if available
        if (!method.getSuccessAnimationIds().isEmpty()) {
            chopTask.withSuccessAnimations(method.getSuccessAnimationIds());
        }

        currentSubTask = chopTask;
    }

    private void executeProcessing(TaskContext ctx) {
        TrainingMethod method = config.getMethod();
        InventoryState inventory = ctx.getInventoryState();

        int logId = method.getSourceItemId();
        int knifeId = method.getProcessingToolId() > 0 ? method.getProcessingToolId() : ItemID.KNIFE;

        // Check if we still have logs to fletch
        if (!inventory.hasItem(logId)) {
            log.debug("All logs fletched, moving to handle products");
            phase = GatherFletchPhase.HANDLE_PRODUCTS;
            return;
        }

        // Use knife on logs - ProcessItemTask handles make-all
        // Arrow shafts are the first option when fletching logs
        ProcessItemTask fletchTask = new ProcessItemTask(knifeId, logId)
                .withOutputItemId(ItemID.ARROW_SHAFT)
                .withDescription("Fletch logs to arrow shafts");

        currentSubTask = fletchTask;
    }

    private void executeHandleProducts(TaskContext ctx) {
        switch (config.getProductHandling()) {
            case DROP:
                // Drop arrow shafts
                currentSubTask = DropInventoryTask.forItemIds(List.of(ItemID.ARROW_SHAFT))
                        .withDescription("Drop arrow shafts");
                break;

            case RETAIN:
                // Arrow shafts stack, so just continue to next cycle
                log.debug("Retaining arrow shafts (stackable), continuing");
                phase = GatherFletchPhase.CHECK_CONTINUE;
                return;

            case BANK:
                // Bank arrow shafts
                MethodLocation location = config.getLocation();
                WorldPoint returnPos = location != null ?
                        (location.getTrainingArea() != null ? location.getTrainingArea() : location.getExactPosition())
                        : null;

                ResupplyTask.ResupplyTaskBuilder bankBuilder = ResupplyTask.builder()
                        .depositInventory(true);

                if (returnPos != null) {
                    bankBuilder.returnPosition(returnPos);
                }

                currentSubTask = bankBuilder.build();
                break;
        }
    }

    private void executeCheckContinue(TaskContext ctx) {
        // Increment cycle count
        cycleCount++;

        log.info("Completed gather-fletch cycle {}. Woodcutting XP: +{}, Fletching XP: +{}",
                cycleCount,
                ctx.getClient().getSkillExperience(Skill.WOODCUTTING) - startWoodcuttingXp,
                ctx.getClient().getSkillExperience(Skill.FLETCHING) - startFletchingXp);

        // Check if we should continue
        if (shouldStopTraining(ctx)) {
            complete();
            return;
        }

        // Start next cycle
        phase = GatherFletchPhase.WALK_TO_GATHER;
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
            // For gathering, just retry
            if (phase == GatherFletchPhase.GATHERING) {
                log.debug("Retrying gather phase");
                return;
            }
            // For processing, check if we still have logs
            if (phase == GatherFletchPhase.PROCESSING) {
                if (!ctx.getInventoryState().hasItem(config.getMethod().getSourceItemId())) {
                    // No more logs, move on
                    phase = GatherFletchPhase.HANDLE_PRODUCTS;
                    return;
                }
                // Still have logs, retry
                return;
            }
            fail("Sub-task failed: " + currentSubTask.getDescription());
            return;
        }

        // Handle phase transitions
        switch (phase) {
            case WALK_TO_GATHER:
                phase = GatherFletchPhase.GATHERING;
                break;

            case GATHERING:
                // Check if inventory is full now
                if (ctx.getInventoryState().getUsedSlots() >= 28) {
                    phase = GatherFletchPhase.PROCESSING;
                }
                // Otherwise will restart chopping next tick
                break;

            case PROCESSING:
                // Check if any logs left
                if (!ctx.getInventoryState().hasItem(config.getMethod().getSourceItemId())) {
                    phase = GatherFletchPhase.HANDLE_PRODUCTS;
                }
                // Otherwise continue processing
                break;

            case HANDLE_PRODUCTS:
                phase = GatherFletchPhase.CHECK_CONTINUE;
                break;

            case CHECK_CONTINUE:
                // Nothing to do - executeCheckContinue handles it
                break;
        }
    }
}
