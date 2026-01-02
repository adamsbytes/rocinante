package com.rocinante.tasks.impl;

import com.rocinante.agility.AgilityCourse;
import com.rocinante.agility.AgilityCourseRepository;
import com.rocinante.inventory.IdealInventory;
import com.rocinante.inventory.InventoryPreparation;
import com.rocinante.inventory.InventorySlotSpec;
import com.rocinante.inventory.ToolSelection;
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
import com.rocinante.tasks.impl.skills.cooking.CookingSkillTask;
import com.rocinante.tasks.impl.skills.cooking.CookingSkillTaskConfig;
import com.rocinante.tasks.impl.skills.cooking.GatherAndCookConfig;
import com.rocinante.tasks.impl.skills.cooking.GatherAndCookTask;
import com.rocinante.tasks.impl.skills.firemaking.FiremakingConfig;
import com.rocinante.tasks.impl.skills.firemaking.FiremakingSkillTask;
import com.rocinante.tasks.impl.skills.firemaking.GatherAndFiremakeConfig;
import com.rocinante.tasks.impl.skills.firemaking.GatherAndFiremakeTask;
import com.rocinante.tasks.impl.skills.fletching.GatherAndFletchConfig;
import com.rocinante.tasks.impl.skills.fletching.GatherAndFletchTask;
import com.rocinante.tasks.impl.skills.prayer.PrayerSkillTask;
import com.rocinante.tasks.impl.skills.thieving.ThievingSkillTask;
import com.rocinante.navigation.RankedCandidate;
import com.rocinante.util.CollectionResolver;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
     * Whether inventory preparation phase has completed.
     * Used to ensure we only prepare inventory once.
     */
    private boolean inventoryPrepared = false;

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

    /**
     * The current target position for gathering (to exclude from predictive hover).
     */
    private WorldPoint currentTargetPosition;

    /**
     * Cached expanded target IDs for the current training method.
     */
    private Set<Integer> expandedTargetIds;

    /**
     * Whether the current method targets NPCs (true) or objects (false).
     */
    private boolean targetsNpcs;

    /**
     * Menu action for predictive hover clicks.
     */
    private String hoverMenuAction;

    /**
     * Ranked training candidates ordered by efficiency (best first).
     * Computed on arrival at training location if banking is required.
     */
    private List<RankedCandidate> rankedCandidates;

    /**
     * Whether we've computed ranked candidates for this session.
     */
    private boolean rankedCandidatesComputed = false;

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
        } else {
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

        // Phase 1: Prepare inventory using IdealInventory system
        InventoryPreparation inventoryPrep = ctx.getInventoryPreparation();
        if (inventoryPrep != null && !inventoryPrepared) {
            IdealInventory ideal = config.getOrCreateIdealInventory(ctx.getPlayerState());
            
            // Check if inventory already matches ideal state
            if (!inventoryPrep.isInventoryReady(ideal, ctx)) {
                // Compute optimal bank location using smart routing
                PlayerState player = ctx.getPlayerState();
                WorldPoint playerPos = player.getWorldPosition();
                WorldPoint trainingArea = getTrainingAreaPosition();
                
                WorldPoint optimalBank = computeOptimalBankLocation(ctx, playerPos, trainingArea);
                WorldPoint returnPos = (optimalBank != null && trainingArea != null) ? trainingArea : null;
                
                // Use analyzeAndPrepare with smart bank routing
                InventoryPreparation.PreparationResult result = inventoryPrep.analyzeAndPrepare(
                        ideal, ctx, optimalBank, returnPos);
                
                if (result.isFailed()) {
                    // Required items are missing and cannot be obtained
                    log.warn("Cannot prepare inventory - missing required items: {}", result.getMissingItems());
                    fail("Missing required items: " + String.join(", ", result.getMissingItems()));
                    return;
                }
                
                if (result.getTask() != null) {
                    log.debug("Preparing inventory: {}", ideal.getSummary());
                    activeSubTask = result.getTask();
                    return;
                }
            }
            inventoryPrepared = true;
            log.debug("Inventory preparation complete");
        }

        // Phase 2: Travel to training location
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Enforce location proximity
        // - exactPosition: use exact coords
        // - trainingArea: use specific coords
        // - null trainingArea ("any_bank"): dynamically find nearest bank
        MethodLocation location = getSelectedLocation();
        if (location != null) {
            WorldPoint targetLocation;
            String targetDescription;
            
            if (location.getExactPosition() != null) {
                targetLocation = location.getExactPosition();
                targetDescription = location.getName();
            } else if (location.getTrainingArea() != null) {
                targetLocation = location.getTrainingArea();
                targetDescription = location.getName();
            } else {
                // "any_bank" - find nearest bank dynamically
                targetLocation = ctx.getNavigationService().findNearestBank(playerPos);
                targetDescription = "nearest bank";
            }
            
            if (targetLocation != null && playerPos.distanceTo(targetLocation) > 15) {
                log.info("Player is {} tiles from training area, traveling to {}",
                        playerPos.distanceTo(targetLocation), targetDescription);
                activeSubTask = new WalkToTask(targetLocation)
                        .withDescription("Travel to " + targetDescription);
                return;
            }
        }

        // Only set training position AFTER validating location
        trainingPosition = playerPos;

        // Phase 3: Compute ranked training candidates (for banking methods only)
        // Power methods (dropWhenFull=true) skip ranking - they use on-demand nearest object finding
        if (!rankedCandidatesComputed && method.getMethodType() == MethodType.GATHER && config.shouldBank()) {
            computeRankedCandidates(ctx, method);
        }

        // Phase 4: Verify we have required tools (legacy fallback if no InventoryPreparation)
        if (inventoryPrep == null && !hasRequiredTools(ctx)) {
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
                log.debug("Found required tool {} in inventory", itemId);
                return true;
            }
        }

        // Check equipped items (e.g., pickaxe, axe, fishing rod can be equipped)
        if (equipment != null) {
            for (int itemId : requiredItems) {
                if (equipment.hasEquipped(itemId)) {
                    log.debug("Found required tool {} equipped", itemId);
                    return true;
                }
            }
        }

        log.debug("Missing required tools. Needed one of: {}", requiredItems);
        return false;
    }

    /**
     * Compute and cache ranked training candidates for smart object selection.
     *
     * <p>For gathering methods that require banking, this ranks nearby objects by
     * roundtrip efficiency (object → bank → object). Objects with shorter roundtrips
     * are preferred, resulting in better XP/hour.
     *
     * <p>For power training (drop when full), objects are ranked by simple distance.
     *
     * <p>Results are cached both in this task instance and in the global cache
     * (TrainingSpotCache) for reuse across sessions.
     */
    private void computeRankedCandidates(TaskContext ctx, TrainingMethod method) {
        rankedCandidatesComputed = true;
        
        // Get object IDs (expanded via CollectionResolver)
        List<Integer> objectIds;
        if (method.hasTargetObjects()) {
            objectIds = CollectionResolver.expandObjectIds(method.getTargetObjectIds());
        } else if (method.hasTargetNpcs()) {
            // NPCs move, so ranking is less useful - skip
            log.debug("Skipping ranked candidates for NPC-based method");
            return;
        } else {
            return;
        }
        
        if (objectIds.isEmpty()) {
            return;
        }
        
        // Determine if banking is required
        boolean bankRequired = config.shouldBank();
        
        com.rocinante.navigation.NavigationService navService = ctx.getNavigationService();
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
        
        if (navService == null || playerPos == null) {
            log.debug("NavigationService or player position not available for ranking");
            return;
        }
        
        // Compute ranked candidates
        log.debug("Computing ranked training candidates: {} objects, banking={}", 
                objectIds.size(), bankRequired);
        
        rankedCandidates = navService.rankTrainingCandidates(
                ctx,
                objectIds,
                playerPos,
                bankRequired
        );
        
        if (rankedCandidates != null && !rankedCandidates.isEmpty()) {
            log.info("Ranked {} training candidates (best cost: {}, worst: {})",
                    rankedCandidates.size(),
                    rankedCandidates.get(0).cost(),
                    rankedCandidates.get(rankedCandidates.size() - 1).cost());
        } else {
            log.debug("No ranked candidates computed (scene may not be loaded yet)");
        }
    }

    // ========================================================================
    // Phase: Train
    // ========================================================================

    private void executeTrain(TaskContext ctx) {
        TrainingMethod method = config.getMethod();
        PlayerState player = ctx.getPlayerState();
        InventoryState inventory = ctx.getInventoryState();
        com.rocinante.behavior.PredictiveHoverManager hoverManager = ctx.getPredictiveHoverManager();

        // Check if inventory is full
        if (inventory.isFull() && method.isRequiresInventorySpace()) {
            // Clear any pending hover when inventory is full
            if (hoverManager != null) {
                hoverManager.clearHover();
            }
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
                currentTargetPosition = null; // Clear current target
                log.debug("Action complete, total actions: {}", actionsCompleted);

                // Check for watched ground items after completing an action
                if (checkForWatchedGroundItems(ctx, false)) {
                    // Clear any pending hover when picking up items
                    if (hoverManager != null) {
                        hoverManager.clearHover();
                    }
                    return; // Switched to PICKUP_ITEM phase
                }

                // Check if we have a predicted hover to execute
                if (hoverManager != null && hoverManager.hasPendingHover()) {
                    log.debug("Executing predicted click from hover");
                    // CRITICAL: Set interactionStarted SYNCHRONOUSLY before async click
                    // This prevents race condition where next tick calls startTrainingInteraction()
                    // before the async callback completes
                    interactionStarted = true;
                    hoverManager.executePredictedClick(ctx).thenAccept(success -> {
                        if (success) {
                            log.debug("Predicted click executed successfully");
                        } else {
                            // Click failed (abandoned or error) - allow retry on next cycle
                            log.debug("Predicted click failed, will retry with normal interaction");
                            interactionStarted = false;
                        }
                    });
                    return;
                }
            }

            // Need to interact with training object/NPC
            if (!interactionStarted) {
                // Before starting new interaction, check for high-priority ground items
                if (checkForWatchedGroundItems(ctx, true)) {
                    // Clear any pending hover when picking up items
                    if (hoverManager != null) {
                        hoverManager.clearHover();
                    }
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
                // Clear any pending hover when picking up items
                if (hoverManager != null) {
                    hoverManager.clearHover();
                }
                // Note: This will interrupt the current action
                return;
            }

            // === PREDICTIVE HOVER ===
            // While animating (gathering), attempt to hover over the next target
            if (hoverManager != null && method.getMethodType() == MethodType.GATHER) {
                attemptPredictiveHover(ctx, hoverManager, method);
            }
        }

        // Check phase timeout (stuck detection)
        phaseWaitTicks++;
        if (phaseWaitTicks > MAX_WAIT_TICKS && !player.isAnimating()) {
            log.warn("Training phase timeout, retrying interaction");
            interactionStarted = false;
            // Clear hover on timeout
            if (hoverManager != null) {
                hoverManager.clearHover();
            }
            phaseWaitTicks = 0;
        }
    }

    /**
     * Attempt to start or validate a predictive hover for gathering skills.
     * 
     * <p>This is called each tick while the player is animating (gathering).
     * It either:
     * <ul>
     *   <li>Starts a new hover if conditions are met (shouldPredictiveHover returns true)</li>
     *   <li>Validates an existing hover (for NPC targets that may move)</li>
     * </ul>
     *
     * @param ctx          the task context
     * @param hoverManager the predictive hover manager
     * @param method       the current training method
     */
    private void attemptPredictiveHover(
            TaskContext ctx,
            com.rocinante.behavior.PredictiveHoverManager hoverManager,
            TrainingMethod method) {
        
        // If already hovering, just validate the hover (for moving NPCs)
        if (hoverManager.hasPendingHover()) {
            if (expandedTargetIds != null) {
                hoverManager.validateAndUpdateHover(ctx, expandedTargetIds);
            }
            return;
        }

        // Not hovering yet - check if we should start
        if (!hoverManager.shouldPredictiveHover()) {
            return; // Roll failed or conditions not met
        }

        // Ensure we have expanded target IDs
        if (expandedTargetIds == null) {
            initializeTargetIds(method);
        }

        if (expandedTargetIds == null || expandedTargetIds.isEmpty()) {
            return; // No targets configured
        }

        // Start the hover
        hoverManager.startPredictiveHover(
                ctx,
                expandedTargetIds,
                currentTargetPosition,
                targetsNpcs,
                hoverMenuAction
        ).thenAccept(success -> {
            if (success) {
                log.debug("Started predictive hover for next {} target", 
                        targetsNpcs ? "NPC" : "object");
            }
        });
    }

    /**
     * Initialize target IDs and hover parameters from the training method.
     * Caches the expanded IDs to avoid repeated expansion.
     */
    private void initializeTargetIds(TrainingMethod method) {
        if (method.hasTargetObjects()) {
            List<Integer> originalIds = method.getTargetObjectIds();
            expandedTargetIds = new HashSet<>(CollectionResolver.expandObjectIds(originalIds));
            targetsNpcs = false;
            hoverMenuAction = method.getMenuAction() != null ? method.getMenuAction() : "Mine";
        } else if (method.hasTargetNpcs()) {
            List<Integer> originalIds = method.getTargetNpcIds();
            expandedTargetIds = new HashSet<>(CollectionResolver.expandNpcIds(originalIds));
            targetsNpcs = true;
            hoverMenuAction = method.getMenuAction() != null ? method.getMenuAction() : "Net";
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
                // Check if this is cooking (object-based processing)
                if (method.hasTargetObjects() && method.getSkill() == Skill.COOKING) {
                    startCookingTraining(ctx, method);
                } else {
                    // Existing item-on-item processing (fletching, herblore, etc.)
                    log.debug("Processing method, switching to process phase");
                    phase = SkillPhase.PROCESS;
                }
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

            case GATHER_AND_PROCESS:
                startGatherAndProcessTraining(ctx, method);
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
     * 
     * <p>Also initializes predictive hover parameters for gathering methods.
     */
    private void startGatherTraining(TaskContext ctx, TrainingMethod method) {
        // Initialize target IDs for predictive hovering (if not already done)
        if (expandedTargetIds == null) {
            initializeTargetIds(method);
        }

        if (method.hasTargetObjects()) {
            // Mining, woodcutting - interact with objects
            List<Integer> originalIds = method.getTargetObjectIds();
            List<Integer> objectIds = CollectionResolver.expandObjectIds(originalIds);
            String menuAction = method.getMenuAction();

            log.debug("CollectionResolver expanded {} -> {} IDs: {} -> {}", 
                    originalIds.size(), objectIds.size(), originalIds, objectIds);

            InteractObjectTask interactTask = new InteractObjectTask(
                    objectIds.get(0),
                    menuAction != null ? menuAction : "Mine"
            );

            if (objectIds.size() > 1) {
                interactTask.withAlternateIds(objectIds.subList(1, objectIds.size()));
            }

            if (!method.getSuccessAnimationIds().isEmpty()) {
                interactTask.withSuccessAnimations(method.getSuccessAnimationIds());
            }

            // For gathering, don't accept position change as success - wait for inventory change
            // This prevents the bot from switching targets while walking to a resource
            interactTask.setAcceptPositionChange(false);
            interactTask.setWaitForInventoryChange(true);

            // Track current target position for predictive hover exclusion
            interactTask.setTargetPositionCallback(pos -> currentTargetPosition = pos);

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

            // Track current target position for predictive hover exclusion
            interactTask.setTargetPositionCallback(pos -> currentTargetPosition = pos);

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

    /**
     * Start cooking training using CookingSkillTask.
     * 
     * <p>Cooking uses item-on-object interactions (raw food on fire/range)
     * which requires specialized handling. CookingSkillTask manages:
     * <ul>
     *   <li>Finding fires/ranges at the training location</li>
     *   <li>Using raw food on the cooking object</li>
     *   <li>Handling the make-all interface</li>
     *   <li>Dropping or banking products based on configuration</li>
     * </ul>
     *
     * @param ctx    the task context
     * @param method the cooking training method
     */
    private void startCookingTraining(TaskContext ctx, TrainingMethod method) {
        log.debug("Cooking method, delegating to CookingSkillTask");

        // Determine product handling mode
        CookingSkillTaskConfig.ProductHandling productHandling;
        if (config.shouldDrop()) {
            productHandling = CookingSkillTaskConfig.ProductHandling.DROP_ALL;
        } else if (config.getBankInsteadOfDrop() != null && config.getBankInsteadOfDrop()) {
            // Bank mode - drop burnt, bank cooked
            productHandling = CookingSkillTaskConfig.ProductHandling.BANK_BUT_DROP_BURNT;
        } else {
            // Default bank mode
            productHandling = CookingSkillTaskConfig.ProductHandling.BANK_ALL;
        }

        MethodLocation selectedLocation = getSelectedLocation();
        
        CookingSkillTaskConfig cookingConfig = CookingSkillTaskConfig.builder()
                .method(method)
                .locationId(selectedLocation != null ? selectedLocation.getId() : null)
                .productHandling(productHandling)
                .targetLevel(config.getTargetLevel())
                .targetXp(config.getTargetXp())
                .maxDuration(config.getMaxDuration())
                .build();

        activeSubTask = new CookingSkillTask(cookingConfig);
        log.info("Starting cooking training: {}", method.getName());
    }

    /**
     * Start gather-and-process training.
     *
     * <p>Delegates to the appropriate specialized task based on the secondary skill:
     * <ul>
     *   <li>COOKING: GatherAndCookTask (powerfish/cook)</li>
     *   <li>FLETCHING: GatherAndFletchTask (powerchop/fletch)</li>
     *   <li>FIREMAKING: GatherAndFiremakeTask (powerchop/firemake)</li>
     * </ul>
     *
     * @param ctx    the task context
     * @param method the gather-and-process training method
     */
    private void startGatherAndProcessTraining(TaskContext ctx, TrainingMethod method) {
        Skill secondarySkill = method.getSecondarySkill();
        if (secondarySkill == null) {
            log.warn("GATHER_AND_PROCESS method {} has no secondary skill defined", method.getId());
            fail("No secondary skill defined for method: " + method.getId());
            return;
        }

        MethodLocation selectedLocation = getSelectedLocation();

        switch (secondarySkill) {
            case COOKING:
                startGatherAndCookTraining(ctx, method, selectedLocation);
                break;

            case FLETCHING:
                startGatherAndFletchTraining(ctx, method, selectedLocation);
                break;

            case FIREMAKING:
                startGatherAndFiremakeTraining(ctx, method, selectedLocation);
                break;

            default:
                log.warn("Unsupported secondary skill for GATHER_AND_PROCESS: {}", secondarySkill);
                fail("Unsupported secondary skill: " + secondarySkill);
        }
    }

    private void startGatherAndCookTraining(TaskContext ctx, TrainingMethod method, MethodLocation location) {
        log.debug("Gather-and-cook method, delegating to GatherAndCookTask");

        // Determine product handling mode
        CookingSkillTaskConfig.ProductHandling productHandling;
        if (config.shouldDrop()) {
            productHandling = CookingSkillTaskConfig.ProductHandling.DROP_ALL;
        } else if (config.getBankInsteadOfDrop() != null && config.getBankInsteadOfDrop()) {
            productHandling = CookingSkillTaskConfig.ProductHandling.BANK_BUT_DROP_BURNT;
        } else {
            productHandling = CookingSkillTaskConfig.ProductHandling.BANK_ALL;
        }

        GatherAndCookConfig gatherCookConfig = GatherAndCookConfig.builder()
                .method(method)
                .location(location)
                .productHandling(productHandling)
                .targetFishingLevel(config.getTargetLevel())
                // TODO: Add secondary target level support to SkillTaskConfig
                .targetCookingLevel(-1)
                .maxDuration(config.getMaxDuration())
                .build();

        activeSubTask = new GatherAndCookTask(gatherCookConfig);
        log.info("Starting gather-and-cook training: {}", method.getName());
    }

    private void startGatherAndFletchTraining(TaskContext ctx, TrainingMethod method, MethodLocation location) {
        log.debug("Gather-and-fletch method, delegating to GatherAndFletchTask");

        // Determine product handling mode
        GatherAndFletchConfig.ProductHandling productHandling;
        if (config.shouldDrop()) {
            productHandling = GatherAndFletchConfig.ProductHandling.DROP;
        } else {
            // For fletching, RETAIN is the efficient option (arrow shafts stack)
            productHandling = GatherAndFletchConfig.ProductHandling.RETAIN;
        }

        GatherAndFletchConfig gatherFletchConfig = GatherAndFletchConfig.builder()
                .method(method)
                .location(location)
                .productHandling(productHandling)
                .targetWoodcuttingLevel(config.getTargetLevel())
                .targetFletchingLevel(-1) // TODO: Add secondary target level support
                .maxDuration(config.getMaxDuration())
                .build();

        activeSubTask = new GatherAndFletchTask(gatherFletchConfig);
        log.info("Starting gather-and-fletch training: {}", method.getName());
    }

    private void startGatherAndFiremakeTraining(TaskContext ctx, TrainingMethod method, MethodLocation location) {
        log.debug("Gather-and-firemake method, delegating to GatherAndFiremakeTask");

        GatherAndFiremakeConfig gatherFiremakeConfig = GatherAndFiremakeConfig.builder()
                .method(method)
                .location(location)
                .targetWoodcuttingLevel(config.getTargetLevel())
                .targetFiremakingLevel(-1) // TODO: Add secondary target level support
                .maxDuration(config.getMaxDuration())
                .build();

        activeSubTask = new GatherAndFiremakeTask(gatherFiremakeConfig);
        log.info("Starting gather-and-firemake training: {}", method.getName());
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
            // Use trainingArea or current position as reference for finding nearest bank
            MethodLocation location = getSelectedLocation();
            WorldPoint referencePoint = location != null && location.getTrainingArea() != null
                    ? location.getTrainingArea()
                    : trainingPosition;
            
            log.debug("Banking from training area, ResupplyTask will find nearest bank from {}", referencePoint);

            // Use ResupplyTask which handles the complete banking flow
            // Pass null for bank position - ResupplyTask auto-finds nearest bank
            activeSubTask = createResupplyTask(null);
        }
    }

    /**
     * Create a ResupplyTask for banking during skill training.
     * Uses the existing ResupplyTask which handles:
     * - Walking to bank (specific or nearest)
     * - Depositing inventory (keeping required tools)
     * - Returning to training position
     *
     * <p>Automatically extracts required item IDs from the IdealInventory
     * and passes them as exceptions so tools aren't deposited.
     *
     * @param bankPosition the specific bank position, or null to use nearest
     * @return configured ResupplyTask
     */
    private Task createResupplyTask(WorldPoint bankPosition) {
        // Use ResupplyTask.Builder for clean configuration
        ResupplyTask.ResupplyTaskBuilder builder = ResupplyTask.builder()
                .depositInventory(true)  // Deposit gathered resources
                .depositEquipment(false); // Keep equipped tools
        
        // Set specific bank location if provided
        if (bankPosition != null) {
            builder.bankLocation(bankPosition);
        }
        
        // Set return position to training spot if we have one
        if (trainingPosition != null) {
            builder.returnPosition(trainingPosition);
        }
        
        // Extract required item IDs from IdealInventory to preserve tools
        Set<Integer> keepItems = getRequiredItemIds();
        if (!keepItems.isEmpty()) {
            for (int itemId : keepItems) {
                builder.exceptItem(itemId);
            }
            log.debug("Banking will preserve {} tool types: {}", keepItems.size(), keepItems);
        }
        
        return builder.build();
    }

    /**
     * Get item IDs that should be kept during banking.
     *
     * <p>Extracts all required item IDs from the IdealInventory spec,
     * resolving collection-based specs to actual item IDs where possible.
     *
     * @return set of item IDs to preserve during banking
     */
    private Set<Integer> getRequiredItemIds() {
        Set<Integer> itemIds = new HashSet<>();
        
        IdealInventory ideal = config.getOrCreateIdealInventory(null);
        if (ideal == null || !ideal.hasRequiredItems()) {
            return itemIds;
        }
        
        for (InventorySlotSpec spec : ideal.getRequiredItems()) {
            if (spec.isSpecificItem()) {
                // Specific item - add directly
                itemIds.add(spec.getItemId());
            } else if (spec.isCollectionBased()) {
                // Collection-based - add all items in the collection
                // This ensures we keep whatever tool variant the player has
                List<Integer> collection = spec.getItemCollection();
                if (collection != null) {
                    itemIds.addAll(collection);
                }
            }
        }
        
        return itemIds;
    }

    /**
     * Get the training area position from the selected location.
     *
     * @return training area WorldPoint, or null if none configured
     */
    private WorldPoint getTrainingAreaPosition() {
        MethodLocation location = getSelectedLocation();
        if (location == null) {
            return null;
        }
        
        if (location.getExactPosition() != null) {
            return location.getExactPosition();
        }
        return location.getTrainingArea();
    }

    /**
     * Compute the optimal bank location for smart routing.
     *
     * <p>Compares two options:
     * <ol>
     *   <li>Bank nearest to player's current position, then walk to training area</li>
     *   <li>Walk to training area first, then bank at nearest bank to training area</li>
     * </ol>
     *
     * <p>Returns the bank that results in the shorter total travel distance.
     *
     * @param ctx           task context with navigation service
     * @param playerPos     player's current position
     * @param trainingArea  the training area destination (may be null)
     * @return optimal bank location, or null to use nearest (default behavior)
     */
    private WorldPoint computeOptimalBankLocation(
            TaskContext ctx, 
            WorldPoint playerPos, 
            WorldPoint trainingArea) {
        
        // If no training area, just use nearest bank from player
        if (trainingArea == null || playerPos == null) {
            return null; // Let ResupplyTask find nearest
        }
        
        // Find bank nearest to player
        WorldPoint bankNearPlayer = ctx.getNavigationService().findNearestBank(playerPos);
        
        // Find bank nearest to training area
        WorldPoint bankNearTraining = ctx.getNavigationService().findNearestBank(trainingArea);
        
        // If either lookup failed, fall back to default
        if (bankNearPlayer == null || bankNearTraining == null) {
            return bankNearPlayer != null ? bankNearPlayer : bankNearTraining;
        }
        
        // If it's the same bank, no optimization needed
        if (bankNearPlayer.equals(bankNearTraining)) {
            log.debug("Same bank nearest to player and training area: {}", bankNearPlayer);
            return bankNearPlayer;
        }
        
        // Calculate total distances for each option:
        // Option A: player -> bankNearPlayer -> trainingArea
        int distPlayerToNearBank = playerPos.distanceTo(bankNearPlayer);
        int distNearBankToTraining = bankNearPlayer.distanceTo(trainingArea);
        int totalOptionA = distPlayerToNearBank + distNearBankToTraining;
        
        // Option B: player -> trainingArea -> bankNearTraining -> trainingArea
        // (But since we return to training anyway, this is: player -> bankNearTraining -> training)
        // Actually Option B is: player -> trainingArea (skip bank), then bank later
        // Let's reconsider: the real choice is WHERE to bank first
        //
        // Correct framing:
        // Option A: player -> bankNearPlayer -> trainingArea (total: distA1 + distA2)
        // Option B: player -> bankNearTraining -> trainingArea (total: distB1 + distB2)
        
        int distPlayerToTrainingBank = playerPos.distanceTo(bankNearTraining);
        int distTrainingBankToTraining = bankNearTraining.distanceTo(trainingArea);
        int totalOptionB = distPlayerToTrainingBank + distTrainingBankToTraining;
        
        if (totalOptionA <= totalOptionB) {
            log.debug("Smart bank routing: using bank near player {} (total {} tiles) vs training bank {} (total {} tiles)",
                    bankNearPlayer, totalOptionA, bankNearTraining, totalOptionB);
            return bankNearPlayer;
        } else {
            log.debug("Smart bank routing: using bank near training {} (total {} tiles) vs player bank {} (total {} tiles)",
                    bankNearTraining, totalOptionB, bankNearPlayer, totalOptionA);
            return bankNearTraining;
        }
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

