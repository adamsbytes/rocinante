package com.rocinante.tasks.impl;

import com.rocinante.agility.AgilityCourse;
import com.rocinante.agility.AgilityCourseRepository;
import com.rocinante.progression.GroundItemWatch;
import com.rocinante.progression.MethodLocation;
import com.rocinante.progression.MethodType;
import com.rocinante.progression.TrainingMethod;
import com.rocinante.state.GroundItemSnapshot;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.impl.minigame.library.ArceuusLibraryConfig;
import com.rocinante.tasks.impl.minigame.library.ArceuusLibraryTask;
import com.rocinante.tasks.impl.minigame.wintertodt.WintertodtConfig;
import com.rocinante.tasks.impl.minigame.wintertodt.WintertodtStrategy;
import com.rocinante.tasks.impl.minigame.wintertodt.WintertodtTask;
import com.rocinante.tasks.impl.skills.agility.AgilityCourseConfig;
import com.rocinante.tasks.impl.skills.agility.AgilityCourseTask;
import com.rocinante.tasks.impl.skills.firemaking.FiremakingConfig;
import com.rocinante.tasks.impl.skills.firemaking.FiremakingSkillTask;
import com.rocinante.tasks.impl.skills.prayer.PrayerSkillTask;
import com.rocinante.tasks.impl.skills.thieving.ThievingSkillTask;
import com.rocinante.util.CollectionResolver;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * High-level orchestrator task for skill training sessions.
 *
 * <p>Implements skill training following the pattern established by {@link CombatTask}:
 * <ul>
 *   <li>Phase-based execution with state machine</li>
 *   <li>Sub-task delegation for movement, banking, etc.</li>
 *   <li>Configurable via {@link SkillTaskConfig}</li>
 *   <li>Supports gathering (mining, woodcutting, fishing) and processing (fletching, crafting)</li>
 * </ul>
 *
 * <p>Training Flow:
 * <pre>
 * PREPARE -> TRAIN -> {BANK | DROP | PROCESS} -> TRAIN -> ... -> COMPLETE
 * </pre>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Train mining to level 50 via iron power mining
 * TrainingMethod method = methodRepo.getMethodById("iron_ore_powermine").get();
 * SkillTaskConfig config = SkillTaskConfig.forLevel(Skill.MINING, 50, method);
 * SkillTask task = new SkillTask(config);
 *
 * // Train woodcutting for 2 hours with banking
 * SkillTaskConfig wcConfig = SkillTaskConfig.builder()
 *     .skill(Skill.WOODCUTTING)
 *     .targetLevel(99)
 *     .method(methodRepo.getMethodById("willow_trees_banking").get())
 *     .maxDuration(Duration.ofHours(2))
 *     .build();
 * SkillTask wcTask = new SkillTask(wcConfig);
 * }</pre>
 */
@Slf4j
public class SkillTask extends AbstractTask {

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Skill training configuration.
     */
    @Getter
    private final SkillTaskConfig config;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private SkillPhase phase = SkillPhase.PREPARE;

    /**
     * Active sub-task for delegation.
     */
    private Task activeSubTask;

    /**
     * Task start time.
     */
    private Instant startTime;

    /**
     * Starting XP when task began.
     */
    private int startXp = -1;

    /**
     * Number of successful actions completed.
     */
    @Getter
    private int actionsCompleted = 0;

    /**
     * Number of bank trips completed.
     */
    @Getter
    private int bankTripsCompleted = 0;

    /**
     * Player's position when training started.
     */
    private WorldPoint trainingPosition;

    /**
     * Ticks idle (for detecting when action completes).
     */
    private int idleTicks = 0;

    /**
     * Ticks waiting in current phase.
     */
    private int phaseWaitTicks = 0;

    /**
     * Maximum ticks to wait before timeout in certain phases.
     */
    private static final int MAX_WAIT_TICKS = 30;

    /**
     * Ticks to wait after player stops animating to confirm idle.
     */
    private static final int IDLE_CONFIRMATION_TICKS = 3;

    /**
     * Whether we've interacted with training object this cycle.
     */
    private boolean interactionStarted = false;

    /**
     * Ground item we're currently trying to pick up.
     */
    private GroundItemSnapshot pendingGroundItem;

    /**
     * The watch configuration for the pending ground item.
     */
    private GroundItemWatch pendingItemWatch;

    /**
     * Phase to return to after picking up ground item.
     */
    private SkillPhase returnPhaseAfterPickup;

    /**
     * Number of ground items picked up.
     */
    @Getter
    private int groundItemsPickedUp = 0;

    /**
     * Custom task description.
     */
    @Setter
    private String description;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a skill training task.
     *
     * @param config the training configuration
     */
    public SkillTask(SkillTaskConfig config) {
        this.config = config;

        // Validate configuration
        config.validate();

        // Set timeout based on config
        if (config.hasTimeLimit()) {
            this.timeout = config.getMaxDuration().plusMinutes(5);
        } else {
            this.timeout = Duration.ofHours(8); // Default long timeout
        }
    }

    // ========================================================================
    // Location Selection
    // ========================================================================

    /**
     * Get the selected location for this task.
     * Uses locationId from config if specified, otherwise returns the default location.
     *
     * @return the selected MethodLocation, or null if method has no locations
     */
    private MethodLocation getSelectedLocation() {
        TrainingMethod method = config.getMethod();
        String locationId = config.getLocationId();
        
        if (locationId != null && !locationId.isEmpty()) {
            MethodLocation loc = method.getLocation(locationId);
            if (loc != null) {
                return loc;
            }
            log.warn("Location ID '{}' not found in method {}, using default", locationId, method.getId());
        }
        
        return method.getDefaultLocation();
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public SkillTask withDescription(String description) {
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

        // Check if we've already reached target
        if (isTargetReached(ctx)) {
            return false;
        }

        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (startTime == null) {
            initializeTask(ctx);
        }

        // Check completion conditions
        if (isCompleted(ctx)) {
            completeTask(ctx);
            return;
        }

        // Execute active sub-task if present
        if (activeSubTask != null) {
            activeSubTask.execute(ctx);
            if (activeSubTask.getState().isTerminal()) {
                handleSubTaskComplete(ctx);
            }
            return; // Sub-task still running
        }

        // Execute current phase
        switch (phase) {
            case PREPARE:
                executePrepare(ctx);
                break;
            case TRAIN:
                executeTrain(ctx);
                break;
            case BANK:
                executeBank(ctx);
                break;
            case DROP:
                executeDrop(ctx);
                break;
            case PROCESS:
                executeProcess(ctx);
                break;
            case PICKUP_ITEM:
                executePickupItem(ctx);
                break;
        }
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    private void initializeTask(TaskContext ctx) {
        startTime = Instant.now();
        startXp = ctx.getClient().getSkillExperience(config.getSkill());
        log.info("SkillTask started: {}", config.getSummary());
        log.debug("Starting XP: {}", startXp);
    }

    // ========================================================================
    // Phase: Prepare
    // ========================================================================

    private void executePrepare(TaskContext ctx) {
        TrainingMethod method = config.getMethod();

        // Check if we need to travel to training location
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // If method has a location with an exact position, check if we're there
        MethodLocation location = getSelectedLocation();
        if (location != null && location.getExactPosition() != null) {
            WorldPoint targetPos = location.getExactPosition();
            if (playerPos.distanceTo(targetPos) > 5) {
                log.debug("Traveling to training location: {} at {}", location.getName(), targetPos);
                activeSubTask = new WalkToTask(targetPos)
                        .withDescription("Walk to " + location.getName());
                return;
            }
        }

        // Store training position for return after banking
        trainingPosition = playerPos;

        // Verify we have required tools
        if (!hasRequiredTools(ctx)) {
            log.warn("Missing required tools for {}", method.getName());
            fail("Missing required tools");
            return;
        }

        log.debug("Preparation complete, beginning training");
        phase = SkillPhase.TRAIN;
        phaseWaitTicks = 0;
    }

    /**
     * Check if player has required tools for the training method.
     * Checks both inventory and equipped items.
     */
    private boolean hasRequiredTools(TaskContext ctx) {
        TrainingMethod method = config.getMethod();
        List<Integer> requiredItems = method.getRequiredItemIds();

        if (requiredItems == null || requiredItems.isEmpty()) {
            return true;
        }

        InventoryState inventory = ctx.getInventoryState();
        com.rocinante.state.EquipmentState equipment = ctx.getEquipmentState();

        // For tools, we just need any one of the required items (different tiers)
        // Check inventory first
        for (int itemId : requiredItems) {
            if (inventory.hasItem(itemId)) {
                log.trace("Found required tool {} in inventory", itemId);
                return true;
            }
        }

        // Check equipped items (e.g., pickaxe, axe, fishing rod can be equipped)
        if (equipment != null) {
            for (int itemId : requiredItems) {
                if (equipment.hasEquipped(itemId)) {
                    log.trace("Found required tool {} equipped", itemId);
                    return true;
                }
            }
        }

        log.debug("Missing required tools. Needed one of: {}", requiredItems);
        return false;
    }

    // ========================================================================
    // Phase: Train
    // ========================================================================

    private void executeTrain(TaskContext ctx) {
        TrainingMethod method = config.getMethod();
        PlayerState player = ctx.getPlayerState();
        InventoryState inventory = ctx.getInventoryState();

        // Check if inventory is full
        if (inventory.isFull() && method.isRequiresInventorySpace()) {
            if (config.shouldDrop()) {
                log.debug("Inventory full, switching to drop phase");
                phase = SkillPhase.DROP;
                return;
            } else if (config.shouldBank()) {
                log.debug("Inventory full, switching to bank phase");
                phase = SkillPhase.BANK;
                return;
            }
        }

        // Check if player is idle (not animating)
        if (!player.isAnimating()) {
            idleTicks++;

            // If we've been idle for a while and have started an interaction, action is complete
            if (interactionStarted && idleTicks >= IDLE_CONFIRMATION_TICKS) {
                actionsCompleted++;
                interactionStarted = false;
                idleTicks = 0;
                log.trace("Action complete, total actions: {}", actionsCompleted);

                // Check for watched ground items after completing an action
                if (checkForWatchedGroundItems(ctx, false)) {
                    return; // Switched to PICKUP_ITEM phase
                }
            }

            // Need to interact with training object/NPC
            if (!interactionStarted) {
                // Before starting new interaction, check for high-priority ground items
                if (checkForWatchedGroundItems(ctx, true)) {
                    return; // Switched to PICKUP_ITEM phase
                }

                startTrainingInteraction(ctx);
            }
        } else {
            // Player is animating - training in progress
            idleTicks = 0;
            interactionStarted = true;

            // Check for ground items that should interrupt current action
            if (checkForWatchedGroundItems(ctx, true)) {
                // Note: This will interrupt the current action
                return;
            }
        }

        // Check phase timeout (stuck detection)
        phaseWaitTicks++;
        if (phaseWaitTicks > MAX_WAIT_TICKS && !player.isAnimating()) {
            log.warn("Training phase timeout, retrying interaction");
            interactionStarted = false;
            phaseWaitTicks = 0;
        }
    }

    /**
     * Start interaction with the training object or NPC.
     */
    private void startTrainingInteraction(TaskContext ctx) {
        TrainingMethod method = config.getMethod();

        switch (method.getMethodType()) {
            case GATHER:
                startGatherTraining(ctx, method);
                break;

            case PROCESS:
                log.debug("Processing method, switching to process phase");
                phase = SkillPhase.PROCESS;
                break;

            case AGILITY:
                startAgilityTraining(ctx, method);
                break;

            case FIREMAKING:
                startFiremakingTraining(ctx, method);
                break;

            case MINIGAME:
                startMinigameTraining(ctx, method);
                break;

            case PRAYER:
                startPrayerTraining(ctx, method);
                break;

            case THIEVING:
                startThievingTraining(ctx, method);
                break;

            default:
                log.warn("Unsupported method type: {}", method.getMethodType());
                fail("Unsupported method type: " + method.getMethodType());
                return;
        }

        phaseWaitTicks = 0;
    }

    /**
     * Start gathering skill training (mining, woodcutting, fishing).
     * 
     * <p>Object and NPC IDs are automatically expanded via {@link CollectionResolver}
     * to include all variants from matching collections. This allows training_methods.json
     * to use single representative IDs while supporting all game variants.
     */
    private void startGatherTraining(TaskContext ctx, TrainingMethod method) {
        if (method.hasTargetObjects()) {
            // Mining, woodcutting - interact with objects
            List<Integer> originalIds = method.getTargetObjectIds();
            List<Integer> objectIds = CollectionResolver.expandObjectIds(originalIds);
            String menuAction = method.getMenuAction();

            InteractObjectTask interactTask = new InteractObjectTask(
                    objectIds.get(0),
                    menuAction != null ? menuAction : "Mine"
            );

            if (objectIds.size() > 1) {
                interactTask.withAlternateIds(objectIds.subList(1, objectIds.size()));
            }

            if (method.getSuccessAnimationId() > 0) {
                interactTask.withSuccessAnimation(method.getSuccessAnimationId());
            }

            activeSubTask = interactTask;
            log.debug("Starting interaction with object(s): {} (expanded from {})", 
                    objectIds.size(), originalIds.size());

        } else if (method.hasTargetNpcs()) {
            // Fishing - interact with NPCs (fishing spots)
            List<Integer> originalIds = method.getTargetNpcIds();
            List<Integer> npcIds = CollectionResolver.expandNpcIds(originalIds);
            String menuAction = method.getMenuAction();

            InteractNpcTask interactTask = new InteractNpcTask(
                    npcIds.get(0),
                    menuAction != null ? menuAction : "Net"
            );

            if (npcIds.size() > 1) {
                interactTask.withAlternateIds(npcIds.subList(1, npcIds.size()));
            }

            activeSubTask = interactTask;
            log.debug("Starting interaction with NPC(s): {} (expanded from {})", 
                    npcIds.size(), originalIds.size());
        }
    }

    /**
     * Start agility course training.
     */
    private void startAgilityTraining(TaskContext ctx, TrainingMethod method) {
        if (!method.isAgilityCourse()) {
            log.warn("AGILITY method {} doesn't have courseId configured", method.getId());
            fail("Invalid agility method configuration");
            return;
        }

        AgilityCourseRepository courseRepo = ctx.getAgilityCourseRepository();
        if (courseRepo == null) {
            log.warn("AgilityCourseRepository not available in TaskContext");
            fail("AgilityCourseRepository not available");
            return;
        }

        AgilityCourse course = courseRepo.getCourseById(method.getCourseId()).orElse(null);
        if (course == null) {
            log.warn("Agility course '{}' not found", method.getCourseId());
            fail("Agility course not found: " + method.getCourseId());
            return;
        }

        AgilityCourseConfig courseConfig = AgilityCourseConfig.builder()
                .course(course)
                .targetLevel(config.getTargetLevel())
                .targetXp(config.getTargetXp())
                .maxDuration(config.getMaxDuration())
                .pickupMarksOfGrace(method.hasWatchedGroundItems())
                .build();

        activeSubTask = new AgilityCourseTask(courseConfig)
                .withDescription("Train Agility at " + course.getName());
        log.info("Starting agility training at {}", course.getName());
    }

    /**
     * Start firemaking training.
     */
    private void startFiremakingTraining(TaskContext ctx, TrainingMethod method) {
        if (!method.isFiremakingMethod()) {
            log.warn("FIREMAKING method {} doesn't have logItemId configured", method.getId());
            fail("Invalid firemaking method configuration");
            return;
        }

        FiremakingConfig fmConfig = FiremakingConfig.builder()
                .logItemId(method.getLogItemId())
                .targetLevel(config.getTargetLevel())
                .targetXp(config.getTargetXp())
                .maxDuration(config.getMaxDuration())
                .bankForLogs(method.requiresBanking())
                .build();

        activeSubTask = new FiremakingSkillTask(fmConfig)
                .withDescription("Train Firemaking with " + method.getName());
        log.info("Starting firemaking training: {}", method.getName());
    }

    /**
     * Start minigame-based training.
     */
    private void startMinigameTraining(TaskContext ctx, TrainingMethod method) {
        if (!method.isMinigameMethod()) {
            log.warn("MINIGAME method {} doesn't have minigameId configured", method.getId());
            fail("Invalid minigame method configuration");
            return;
        }

        String minigameId = method.getMinigameId();

        if ("wintertodt".equalsIgnoreCase(minigameId)) {
            startWintertodtTraining(method);
        } else if ("arceuus_library".equalsIgnoreCase(minigameId)) {
            startArceuusLibraryTraining(method);
        } else {
            log.warn("Unknown minigame: {}", minigameId);
            fail("Unsupported minigame: " + minigameId);
        }
    }

    /**
     * Start Wintertodt minigame training.
     */
    private void startWintertodtTraining(TrainingMethod method) {
        WintertodtConfig.WintertodtConfigBuilder<?, ?> wtBuilder = WintertodtConfig.builder()
                .targetLevel(config.getTargetLevel())
                .targetXp(config.getTargetXp())
                .maxDuration(config.getMaxDuration());

        String strategyStr = method.getMinigameStrategy();
        if ("fletch".equalsIgnoreCase(strategyStr)) {
            wtBuilder.strategy(WintertodtStrategy.FLETCH);
        } else {
            wtBuilder.strategy(WintertodtStrategy.SIMPLE);
        }

        activeSubTask = new WintertodtTask(wtBuilder.build());
        log.info("Starting Wintertodt training: {} strategy", strategyStr != null ? strategyStr : "simple");
    }

    /**
     * Start Arceuus Library minigame training.
     */
    private void startArceuusLibraryTraining(TrainingMethod method) {
        Skill targetSkill = Skill.MAGIC;
        String strategyStr = method.getMinigameStrategy();
        if ("runecraft".equalsIgnoreCase(strategyStr)) {
            targetSkill = Skill.RUNECRAFT;
        }

        ArceuusLibraryConfig libraryConfig;
        if (config.getTargetLevel() > 0) {
            libraryConfig = targetSkill == Skill.MAGIC
                    ? ArceuusLibraryConfig.forMagic(config.getTargetLevel())
                    : ArceuusLibraryConfig.forRunecraft(config.getTargetLevel());
        } else if (config.getTargetXp() > 0) {
            libraryConfig = ArceuusLibraryConfig.forXp(targetSkill, config.getTargetXp());
        } else {
            libraryConfig = targetSkill == Skill.MAGIC
                    ? ArceuusLibraryConfig.forMagic(99)
                    : ArceuusLibraryConfig.forRunecraft(99);
        }

        activeSubTask = new ArceuusLibraryTask(libraryConfig);
        log.info("Starting Arceuus Library training: {} ({})", targetSkill.getName(), strategyStr);
    }

    /**
     * Start prayer training.
     */
    private void startPrayerTraining(TaskContext ctx, TrainingMethod method) {
        log.debug("Prayer method, delegating to PrayerSkillTask");

        PrayerSkillTask prayerTask;

        if (method.hasTargetObjects()) {
            boolean isChaosAltar = method.getId() != null && 
                    method.getId().toLowerCase().contains("chaos");
            prayerTask = isChaosAltar ? PrayerSkillTask.chaosAltar() : PrayerSkillTask.basicAltar();
        } else {
            prayerTask = PrayerSkillTask.buryBones();
        }

        if (config.getTargetLevel() > 0) {
            prayerTask.withTargetLevel(config.getTargetLevel());
        }

        activeSubTask = prayerTask;
        log.info("Starting prayer training: {}", method.getName());
    }

    /**
     * Start thieving training.
     * 
     * <p>Object and NPC IDs are automatically expanded via {@link CollectionResolver}
     * to include all variants from matching collections. This allows training_methods.json
     * to use single representative IDs while supporting all game variants.
     */
    private void startThievingTraining(TaskContext ctx, TrainingMethod method) {
        log.debug("Thieving method, delegating to ThievingSkillTask");

        ThievingSkillTask thievingTask;

        if (method.hasTargetObjects()) {
            List<Integer> expandedStalls = CollectionResolver.expandObjectIds(
                    method.getTargetObjectIds());
            thievingTask = new ThievingSkillTask(ThievingSkillTask.ThievingMethod.STALL)
                    .withTargetStalls(expandedStalls);
            log.debug("Thieving stalls expanded: {} -> {} IDs", 
                    method.getTargetObjectIds().size(), expandedStalls.size());
        } else if (method.hasTargetNpcs()) {
            List<Integer> expandedNpcs = CollectionResolver.expandNpcIds(
                    method.getTargetNpcIds());
            thievingTask = new ThievingSkillTask(ThievingSkillTask.ThievingMethod.PICKPOCKET)
                    .withTargetNpcs(expandedNpcs);
            log.debug("Thieving NPCs expanded: {} -> {} IDs", 
                    method.getTargetNpcIds().size(), expandedNpcs.size());
        } else {
            log.warn("THIEVING method {} has no target objects or NPCs configured", method.getId());
            fail("Invalid thieving method configuration");
            return;
        }

        MethodLocation thievingLocation = getSelectedLocation();
        if (thievingLocation != null && thievingLocation.getExactPosition() != null) {
            thievingTask.withLocation(thievingLocation.getExactPosition());
        }

        if (config.getTargetLevel() > 0) {
            thievingTask.withTargetLevel(config.getTargetLevel());
        }

        activeSubTask = thievingTask;
        log.info("Starting thieving training: {}", method.getName());
    }

    // ========================================================================
    // Ground Item Watching
    // ========================================================================

    /**
     * Check for watched ground items and switch to pickup phase if found.
     *
     * @param ctx the task context
     * @param requireInterrupt if true, only pick up items with interruptAction=true
     * @return true if switching to PICKUP_ITEM phase
     */
    private boolean checkForWatchedGroundItems(TaskContext ctx, boolean requireInterrupt) {
        TrainingMethod method = config.getMethod();

        if (!method.hasWatchedGroundItems()) {
            return false;
        }

        List<GroundItemWatch> watchedItems = method.getWatchedGroundItems();
        WorldState worldState = ctx.getWorldState();
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Find the highest priority matching ground item
        Optional<GroundItemMatchResult> match = watchedItems.stream()
                .filter(watch -> !requireInterrupt || watch.isInterruptAction())
                .flatMap(watch -> worldState.getGroundItems().stream()
                        .filter(item -> watch.matches(item.getId()))
                        .filter(item -> playerPos.distanceTo(item.getWorldPosition()) <= watch.getMaxPickupDistance())
                        .map(item -> new GroundItemMatchResult(watch, item)))
                .max(Comparator.comparingInt(result -> result.watch.getPriority()));

        if (match.isPresent()) {
            GroundItemMatchResult result = match.get();
            log.info("Found watched ground item: {} at {} (priority: {}, distance: {})",
                    result.watch.getItemName(),
                    result.item.getWorldPosition(),
                    result.watch.getPriority(),
                    playerPos.distanceTo(result.item.getWorldPosition()));

            // Store state for pickup
            pendingGroundItem = result.item;
            pendingItemWatch = result.watch;
            returnPhaseAfterPickup = phase;

            // Switch to pickup phase
            phase = SkillPhase.PICKUP_ITEM;
            phaseWaitTicks = 0;
            interactionStarted = false;

            return true;
        }

        return false;
    }

    /**
     * Internal class to pair a watch config with a matching ground item.
     */
    private static class GroundItemMatchResult {
        final GroundItemWatch watch;
        final GroundItemSnapshot item;

        GroundItemMatchResult(GroundItemWatch watch, GroundItemSnapshot item) {
            this.watch = watch;
            this.item = item;
        }
    }

    // ========================================================================
    // Phase: Pickup Item
    // ========================================================================

    private void executePickupItem(TaskContext ctx) {
        if (activeSubTask == null) {
            if (pendingGroundItem == null) {
                log.warn("No pending ground item to pick up");
                phase = returnPhaseAfterPickup != null ? returnPhaseAfterPickup : SkillPhase.TRAIN;
                return;
            }

            // Create pickup task
            PickupItemTask pickupTask = new PickupItemTask(
                    pendingGroundItem.getId(),
                    pendingItemWatch.getItemName()
            );
            pickupTask.withLocation(pendingGroundItem.getWorldPosition());
            pickupTask.withDescription("Pick up " + pendingItemWatch.getItemName());

            activeSubTask = pickupTask;
            log.debug("Starting pickup for {} at {}",
                    pendingItemWatch.getItemName(), pendingGroundItem.getWorldPosition());
        }
    }

    // ========================================================================
    // Phase: Bank
    // ========================================================================

    private void executeBank(TaskContext ctx) {
        // Create banking sub-task using ResupplyTask
        if (activeSubTask == null) {
            TrainingMethod method = config.getMethod();

            // Look up bank location from the selected location
            MethodLocation location = getSelectedLocation();
            String bankLocationId = location != null ? location.getBankLocationId() : null;
            WorldPoint bankPosition = lookupBankLocation(ctx, bankLocationId);
            
            if (bankPosition != null) {
                log.debug("Using configured bank location: {} at {}", bankLocationId, bankPosition);
            } else {
                log.debug("No configured bank location, ResupplyTask will find nearest bank");
            }

            // Use ResupplyTask which handles the complete banking flow
            activeSubTask = createResupplyTask(bankPosition);
        }
    }

    /**
     * Look up bank location from NavigationWeb by ID.
     * This allows training methods to specify preferred banks (e.g., closest to training spot).
     *
     * @param ctx            the task context
     * @param bankLocationId the bank node ID from TrainingMethod
     * @return the bank WorldPoint, or null if not found
     */
    private WorldPoint lookupBankLocation(TaskContext ctx, String bankLocationId) {
        if (bankLocationId == null || bankLocationId.isEmpty()) {
            return null;
        }

        try {
            // Get NavigationWeb from WebWalker
            com.rocinante.navigation.WebWalker webWalker = ctx.getWebWalker();
            if (webWalker == null) {
                log.warn("WebWalker not available for bank lookup");
                return null;
            }

            com.rocinante.navigation.NavigationWeb navigationWeb = webWalker.getNavigationWeb();
            if (navigationWeb == null) {
                log.warn("NavigationWeb not available for bank lookup");
                return null;
            }

            // Look up the bank node by ID
            com.rocinante.navigation.WebNode bankNode = navigationWeb.getNode(bankLocationId);
            if (bankNode == null) {
                log.warn("Bank location ID '{}' not found in NavigationWeb", bankLocationId);
                return null;
            }

            // Verify it's actually a bank
            if (bankNode.getType() != com.rocinante.navigation.WebNodeType.BANK) {
                log.warn("Node '{}' is not a bank (type={})", bankLocationId, bankNode.getType());
                return null;
            }

            log.debug("Resolved bank '{}' to position {}", bankLocationId, bankNode.getWorldPoint());
            return bankNode.getWorldPoint();
        } catch (Exception e) {
            log.warn("Error looking up bank location '{}': {}", bankLocationId, e.getMessage());
            return null;
        }
    }

    /**
     * Create a ResupplyTask for banking during skill training.
     * Uses the existing ResupplyTask which handles:
     * - Walking to bank (specific or nearest)
     * - Depositing inventory
     * - Returning to training position
     *
     * @param bankPosition the specific bank position, or null to use nearest
     * @return configured ResupplyTask
     */
    private Task createResupplyTask(WorldPoint bankPosition) {
        TrainingMethod method = config.getMethod();
        
        // Use ResupplyTask.Builder for clean configuration
        ResupplyTask.ResupplyTaskBuilder builder = ResupplyTask.builder()
                .depositInventory(true)  // Deposit all gathered resources
                .depositEquipment(false); // Keep equipped tools
        
        // Set specific bank location if provided
        if (bankPosition != null) {
            builder.bankLocation(bankPosition);
        }
        
        // Set return position to training spot if we have one
        if (trainingPosition != null) {
            builder.returnPosition(trainingPosition);
        }
        
        return builder.build();
    }

    // ========================================================================
    // Phase: Drop
    // ========================================================================

    private void executeDrop(TaskContext ctx) {
        if (activeSubTask == null) {
            TrainingMethod method = config.getMethod();
            List<Integer> productIds = method.getProductItemIds();

            if (productIds == null || productIds.isEmpty()) {
                log.warn("No product IDs configured for dropping");
                phase = SkillPhase.TRAIN;
                return;
            }

            // Create drop task for products
            DropInventoryTask dropTask = DropInventoryTask.forItemIds(productIds)
                    .withDescription("Drop " + method.getName() + " products")
                    .withPattern(DropInventoryTask.DropPattern.COLUMN);

            activeSubTask = dropTask;
            log.debug("Starting drop task for items: {}", productIds);
        }
    }

    // ========================================================================
    // Phase: Process
    // ========================================================================

    private void executeProcess(TaskContext ctx) {
        if (activeSubTask == null) {
            TrainingMethod method = config.getMethod();

            if (!method.usesItemOnItem()) {
                log.warn("Processing method doesn't have source/target items configured");
                fail("Invalid processing method configuration");
                return;
            }

            // Create process task
            ProcessItemTask processTask = new ProcessItemTask(
                    method.getSourceItemId(),
                    method.getTargetItemId()
            );

            if (method.getOutputItemId() > 0) {
                processTask.withOutputItemId(method.getOutputItemId());
            }
            if (method.getOutputPerAction() > 1) {
                processTask.withOutputPerAction(method.getOutputPerAction());
            }

            processTask.withDescription("Process " + method.getName());

            activeSubTask = processTask;
            log.debug("Starting process task: {} on {}", 
                    method.getSourceItemId(), method.getTargetItemId());
        }
    }

    // ========================================================================
    // Sub-task Handling
    // ========================================================================

    private void handleSubTaskComplete(TaskContext ctx) {
        TaskState subTaskState = activeSubTask.getState();
        String subTaskDesc = activeSubTask.getDescription();

        if (subTaskState == TaskState.FAILED) {
            log.warn("Sub-task failed: {}", subTaskDesc);
            // Don't fail the whole task, just retry
        }

        // Clear sub-task reference
        activeSubTask = null;

        // Determine next phase based on current phase
        switch (phase) {
            case PREPARE:
                // Preparation travel complete
                phase = SkillPhase.TRAIN;
                break;

            case TRAIN:
                // Training interaction complete
                interactionStarted = true;
                phaseWaitTicks = 0;
                break;

            case BANK:
                // Banking complete, return to training spot
                bankTripsCompleted++;
                log.info("Bank trip {} complete", bankTripsCompleted);

                if (config.isReturnToExactSpot() && trainingPosition != null) {
                    activeSubTask = new WalkToTask(trainingPosition)
                            .withDescription("Return to training spot");
                } else {
                    phase = SkillPhase.TRAIN;
                }
                break;

            case DROP:
                // Dropping complete, resume training
                log.debug("Drop complete, resuming training");
                phase = SkillPhase.TRAIN;
                break;

            case PROCESS:
                // Processing complete, check if more materials or bank
                InventoryState inventory = ctx.getInventoryState();
                TrainingMethod method = config.getMethod();

                if (!inventory.hasItem(method.getTargetItemId())) {
                    if (config.shouldBank()) {
                        log.debug("Out of materials, banking");
                        phase = SkillPhase.BANK;
                    } else {
                        log.info("Out of materials");
                        completeTask(ctx);
                    }
                } else {
                    // More materials available, continue processing
                    log.debug("More materials available, continuing");
                }
                break;

            case PICKUP_ITEM:
                // Ground item pickup complete
                if (subTaskState == TaskState.COMPLETED) {
                    groundItemsPickedUp++;
                    log.info("Picked up ground item: {} (total: {})",
                            pendingItemWatch != null ? pendingItemWatch.getItemName() : "unknown",
                            groundItemsPickedUp);
                }

                // Clear pending item state
                pendingGroundItem = null;
                pendingItemWatch = null;

                // Return to previous phase
                phase = returnPhaseAfterPickup != null ? returnPhaseAfterPickup : SkillPhase.TRAIN;
                returnPhaseAfterPickup = null;
                break;
        }

        phaseWaitTicks = 0;
    }

    // ========================================================================
    // Completion Checking
    // ========================================================================

    private boolean isCompleted(TaskContext ctx) {
        // Check target level/XP
        if (isTargetReached(ctx)) {
            log.info("Target reached!");
            return true;
        }

        // Check time limit
        if (config.hasTimeLimit() && startTime != null) {
            Duration elapsed = Duration.between(startTime, Instant.now());
            if (elapsed.compareTo(config.getMaxDuration()) >= 0) {
                log.info("Time limit reached: {}", elapsed);
                return true;
            }
        }

        // Check action limit
        if (config.hasActionLimit() && actionsCompleted >= config.getMaxActions()) {
            log.info("Action limit reached: {}", actionsCompleted);
            return true;
        }

        return false;
    }

    private boolean isTargetReached(TaskContext ctx) {
        Client client = ctx.getClient();
        Skill skill = config.getSkill();

        if (config.getTargetLevel() > 0) {
            int currentLevel = client.getRealSkillLevel(skill);
            return currentLevel >= config.getTargetLevel();
        }

        if (config.getTargetXp() > 0) {
            int currentXp = client.getSkillExperience(skill);
            return currentXp >= config.getTargetXp();
        }

        return false;
    }

    private void completeTask(TaskContext ctx) {
        Client client = ctx.getClient();
        int currentXp = client.getSkillExperience(config.getSkill());
        int xpGained = currentXp - startXp;
        Duration elapsed = startTime != null ? Duration.between(startTime, Instant.now()) : Duration.ZERO;

        log.info("SkillTask completed: {} - {} XP gained, {} actions, {} bank trips in {}",
                config.getSkill().getName(),
                String.format("%,d", xpGained),
                actionsCompleted,
                bankTripsCompleted,
                formatDuration(elapsed));

        complete();
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    /**
     * Get XP gained since task started.
     *
     * @param ctx the task context
     * @return XP gained
     */
    public int getXpGained(TaskContext ctx) {
        if (startXp < 0) {
            return 0;
        }
        int currentXp = ctx.getClient().getSkillExperience(config.getSkill());
        return currentXp - startXp;
    }

    /**
     * Get estimated XP per hour based on current progress.
     *
     * @param ctx the task context
     * @return XP per hour
     */
    public double getXpPerHour(TaskContext ctx) {
        if (startTime == null) {
            return 0;
        }

        Duration elapsed = Duration.between(startTime, Instant.now());
        if (elapsed.isZero()) {
            return 0;
        }

        int xpGained = getXpGained(ctx);
        double hours = elapsed.toMillis() / (1000.0 * 60 * 60);
        return xpGained / hours;
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Override
    public void onComplete(TaskContext ctx) {
        super.onComplete(ctx);
    }

    @Override
    public void onFail(TaskContext ctx, Exception e) {
        super.onFail(ctx, e);
        log.warn("SkillTask failed after {} actions", actionsCompleted);
    }

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("SkillTask[%s]", config.getSummary());
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    /**
     * Phases of skill training execution.
     */
    private enum SkillPhase {
        /**
         * Initial preparation: travel to location, verify equipment.
         */
        PREPARE,

        /**
         * Main training loop: interact with objects/NPCs.
         */
        TRAIN,

        /**
         * Banking: deposit products, optionally withdraw supplies.
         */
        BANK,

        /**
         * Dropping: power training drop loop.
         */
        DROP,

        /**
         * Processing: item-on-item production.
         */
        PROCESS,

        /**
         * Picking up watched ground items (marks of grace, bird's nests).
         */
        PICKUP_ITEM
    }
}

