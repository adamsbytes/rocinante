package com.rocinante.slayer;

import com.rocinante.combat.CombatManager;
import com.rocinante.combat.TargetSelector;
import com.rocinante.core.GameStateService;
import com.rocinante.navigation.WebWalker;
import com.rocinante.progression.UnlockTracker;
import com.rocinante.state.IronmanState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.impl.DialogueTask;
import com.rocinante.tasks.impl.InteractNpcTask;
import com.rocinante.tasks.impl.ResupplyTask;
import com.rocinante.tasks.impl.WalkToTask;
import com.rocinante.tasks.impl.WidgetInteractTask;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.slayer.SlayerPluginService;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates the complete slayer task lifecycle.
 *
 * Per REQUIREMENTS.md Section 11.1, manages:
 * <ul>
 *   <li>Task detection from SlayerPluginService and varbits</li>
 *   <li>Master selection based on combat level and preferences</li>
 *   <li>Task acquisition via master dialogue</li>
 *   <li>Location resolution and travel</li>
 *   <li>Combat loop coordination with SlayerTask</li>
 *   <li>Banking and resupply between kills</li>
 *   <li>Task skip/block decision logic</li>
 * </ul>
 *
 * Task Lifecycle (from REQUIREMENTS.md 11.1.1):
 * <ol>
 *   <li>Detect current task from SlayerState</li>
 *   <li>If no task: travel to master, get new task via dialogue</li>
 *   <li>Resolve location via TaskLocationResolver</li>
 *   <li>Check/acquire required items</li>
 *   <li>Travel to location</li>
 *   <li>Execute combat loop (via SlayerTask)</li>
 *   <li>Handle banking between kills as needed</li>
 *   <li>Return to step 1</li>
 * </ol>
 *
 * Uses event-driven architecture via GameTick subscription, same pattern as QuestExecutor.
 */
@Slf4j
@Singleton
public class SlayerManager {

    // ========================================================================
    // Slayer Rewards Widget Constants (from RuneLite InterfaceID.SlayerRewards)
    // ========================================================================

    /**
     * Slayer rewards interface group ID (426).
     */
    private static final int SLAYER_REWARDS_GROUP = 426;

    /**
     * Main container widget in rewards interface.
     */
    private static final int SLAYER_REWARDS_MAIN = 3;

    /**
     * Tasks tab button (child 27 = 0x1b).
     */
    private static final int SLAYER_REWARDS_TASKS_TAB = 27;

    /**
     * Current task display area (child 37 = 0x25) - has "Cancel task" option.
     */
    private static final int SLAYER_REWARDS_CURRENT_TASK = 37;

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final Client client;
    private final GameStateService gameStateService;
    private final SlayerPluginService slayerPluginService;
    private final TaskLocationResolver locationResolver;
    private final SlayerEquipmentManager equipmentManager;
    private final SlayerDataLoader dataLoader;
    private final UnlockTracker unlockTracker;
    private final WebWalker webWalker;
    private final CombatManager combatManager;
    private final TargetSelector targetSelector;
    private final TaskExecutor taskExecutor;
    private final TaskContext taskContext;

    // ========================================================================
    // Session State
    // ========================================================================

    @Getter
    @Setter
    private volatile boolean sessionActive = false;

    @Getter
    @Setter
    private SlayerSessionConfig sessionConfig;

    @Getter
    private volatile SlayerSessionPhase currentPhase = SlayerSessionPhase.IDLE;

    @Getter
    @Nullable
    private volatile TaskLocationResolver.LocationResolution currentResolution;

    @Getter
    @Nullable
    private volatile SlayerTask currentSlayerTask;

    /**
     * Current task being awaited (for state machine transitions).
     */
    @Nullable
    private Task pendingTask;

    /**
     * The slayer master we're currently interacting with.
     */
    @Nullable
    private SlayerMaster currentMaster;

    // ========================================================================
    // Banking State (for resupply)
    // ========================================================================

    /**
     * Equipment item IDs that need to be withdrawn from bank.
     */
    @Nullable
    private Set<Integer> missingEquipmentIds;

    /**
     * Finish-off item ID that needs to be withdrawn.
     */
    private int missingFinishOffItemId = -1;

    /**
     * Location-specific item IDs that need to be withdrawn.
     */
    @Nullable
    private Set<Integer> missingLocationItemIds;

    /**
     * Position to return to after banking (if returnToSameSpot enabled).
     */
    @Nullable
    private WorldPoint preResupplyPosition;

    /**
     * Active resupply task when in BANKING phase.
     */
    @Nullable
    private ResupplyTask activeResupplyTask;

    /**
     * Current sub-phase for task skip flow.
     */
    private SkipPhase skipPhase = SkipPhase.OPENING_REWARDS;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public SlayerManager(
            Client client,
            GameStateService gameStateService,
            SlayerPluginService slayerPluginService,
            TaskLocationResolver locationResolver,
            SlayerEquipmentManager equipmentManager,
            SlayerDataLoader dataLoader,
            UnlockTracker unlockTracker,
            WebWalker webWalker,
            CombatManager combatManager,
            TargetSelector targetSelector,
            TaskExecutor taskExecutor,
            TaskContext taskContext) {
        this.client = client;
        this.gameStateService = gameStateService;
        this.slayerPluginService = slayerPluginService;
        this.locationResolver = locationResolver;
        this.equipmentManager = equipmentManager;
        this.dataLoader = dataLoader;
        this.unlockTracker = unlockTracker;
        this.webWalker = webWalker;
        this.combatManager = combatManager;
        this.targetSelector = targetSelector;
        this.taskExecutor = taskExecutor;
        this.taskContext = taskContext;
    }

    // ========================================================================
    // Session Control
    // ========================================================================

    /**
     * Start a slayer training session.
     *
     * @param config session configuration
     */
    public void startSession(SlayerSessionConfig config) {
        if (sessionActive) {
            log.warn("Slayer session already active");
            return;
        }

        log.info("Starting slayer session with config: {}", config);
        this.sessionConfig = config;
        this.sessionActive = true;
        this.currentPhase = SlayerSessionPhase.CHECKING_TASK;
        this.pendingTask = null;
        this.currentMaster = null;
    }

    /**
     * Stop the current slayer session.
     */
    public void stopSession() {
        log.info("Stopping slayer session");
        this.sessionActive = false;
        this.currentPhase = SlayerSessionPhase.IDLE;
        this.currentResolution = null;
        this.pendingTask = null;
        this.currentMaster = null;

        if (currentSlayerTask != null) {
            if (currentSlayerTask instanceof AbstractTask) {
                ((AbstractTask) currentSlayerTask).abort();
            }
            currentSlayerTask = null;
        }
    }

    /**
     * Get the current slayer state.
     *
     * @return current slayer state from GameStateService
     */
    public SlayerState getSlayerState() {
        return gameStateService.getSlayerState();
    }

    // ========================================================================
    // Main Game Tick Handler
    // ========================================================================

    /**
     * Game tick handler - drives the slayer state machine.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        if (!sessionActive) {
            return;
        }

        // If we're waiting for a task to complete, check its state
        if (pendingTask != null) {
            TaskState state = pendingTask.getState();
            if (state == TaskState.RUNNING || state == TaskState.PENDING) {
                // Still executing, wait
                return;
            } else if (state == TaskState.COMPLETED) {
                log.debug("Pending task completed: {}", pendingTask.getDescription());
                onPendingTaskCompleted();
            } else if (state == TaskState.FAILED) {
                log.warn("Pending task failed: {}", pendingTask.getDescription());
                onPendingTaskFailed();
            }
            pendingTask = null;
        }

        // State machine based on current phase
        switch (currentPhase) {
            case IDLE:
                // Not active
                break;

            case CHECKING_TASK:
                handleCheckingTask();
                break;

            case TRAVELING_TO_MASTER:
                // Waiting for travel task - handled above
                break;

            case TALKING_TO_MASTER:
                // Waiting for interaction task - handled above
                break;

            case GETTING_TASK:
                // Waiting for dialogue task - handled above
                break;

            case SKIPPING_TASK:
                // Waiting for skip interaction - handled above
                break;

            case PREPARING:
                handlePreparing();
                break;

            case TRAVELING_TO_TASK:
                // Waiting for travel task - handled above
                break;

            case FIGHTING:
                handleFighting();
                break;

            case BANKING:
                handleBanking();
                break;

            case COMPLETING_TASK:
                handleCompletingTask();
                break;
        }
    }

    // ========================================================================
    // State Machine Handlers
    // ========================================================================

    private void handleCheckingTask() {
        SlayerState state = getSlayerState();

        if (state.hasTask()) {
            // We have a task - check if we should skip it
            if (shouldSkipTask(state)) {
                log.info("Task {} should be skipped", state.getTaskName());
                startSkipTask();
            } else {
                log.info("Have task: {} x{}", state.getTaskName(), state.getRemainingKills());
                startPrepareForTask();
            }
        } else {
            // No task - go get one
            log.info("No current task, getting new task");
            startGetNewTask();
        }
    }

    private void handlePreparing() {
        // Resolve location if not already done
        if (currentResolution == null) {
            SlayerState state = getSlayerState();
            resolveTaskLocation(state);
            if (currentResolution == null || !currentResolution.isSuccess()) {
                log.error("Failed to resolve task location");
                currentPhase = SlayerSessionPhase.CHECKING_TASK;
                return;
            }
        }

        // Validate required equipment (protective gear: earmuffs, nose peg, etc.)
        SlayerState state = getSlayerState();
        String taskName = state.getTaskName();
        
        SlayerEquipmentManager.EquipmentValidation equipValidation = 
                equipmentManager.validateEquipment(taskName, taskContext.getEquipmentState());
        
        if (!equipValidation.isValid()) {
            log.warn("Missing required equipment for {}: {}", taskName, equipValidation.getMissingItems());
            if (sessionConfig != null && sessionConfig.isResupplyEnabled()) {
                // Store what items we need for banking
                missingEquipmentIds = equipValidation.getRequiredItemIds();
                currentPhase = SlayerSessionPhase.BANKING;
                return;
            } else {
                log.error("Missing equipment and resupply disabled - cannot proceed with task");
                stopSession();
                return;
            }
        }

        // Validate finish-off items (rock hammer, ice coolers, etc.)
        SlayerEquipmentManager.FinishOffValidation finishOffValidation = 
                equipmentManager.validateFinishOffItems(taskName, taskContext.getInventoryState(), state);
        
        if (finishOffValidation.isRequired() && !finishOffValidation.isHasItem() && !finishOffValidation.isAutoKillEnabled()) {
            log.warn("Missing finish-off item for {}: {}", taskName, finishOffValidation.getMissingItemName());
            if (sessionConfig != null && sessionConfig.isResupplyEnabled()) {
                // Need to bank for finish-off items
                missingFinishOffItemId = finishOffValidation.getItemId();
                currentPhase = SlayerSessionPhase.BANKING;
                return;
            } else {
                log.error("Missing finish-off items and resupply disabled - cannot proceed with task");
                stopSession();
                return;
            }
        }

        // Check location-specific required items
        Set<Integer> locationRequiredItems = currentResolution.getRequiredItems();
        if (!locationRequiredItems.isEmpty()) {
            for (int itemId : locationRequiredItems) {
                if (!taskContext.getInventoryState().hasItem(itemId) && 
                    !taskContext.getEquipmentState().hasEquipped(itemId)) {
                    log.warn("Missing location-required item: {}", itemId);
                    if (sessionConfig != null && sessionConfig.isResupplyEnabled()) {
                        missingLocationItemIds = locationRequiredItems;
                        currentPhase = SlayerSessionPhase.BANKING;
                        return;
                    } else {
                        log.error("Missing location items and resupply disabled - cannot proceed");
                        stopSession();
                        return;
                    }
                }
            }
        }

        log.debug("All required items validated for task: {}", taskName);
        
        // Travel to task location
        startTravelToTask();
    }

    private void handleFighting() {
        // Check if task is complete
        SlayerState state = getSlayerState();
        if (!state.hasTask() || state.getRemainingKills() == 0) {
            log.info("Slayer task complete!");
            currentPhase = SlayerSessionPhase.COMPLETING_TASK;
            currentSlayerTask = null;
            return;
        }

        // Check if slayer task is still running
        if (currentSlayerTask != null) {
            TaskState taskState = currentSlayerTask.getState();
            if (taskState == TaskState.COMPLETED) {
                log.info("Combat task completed");
                currentSlayerTask = null;
                currentPhase = SlayerSessionPhase.COMPLETING_TASK;
            } else if (taskState == TaskState.FAILED) {
                log.warn("Combat task failed");
                currentSlayerTask = null;
                // Try to recover by re-checking task
                currentPhase = SlayerSessionPhase.CHECKING_TASK;
            }
            // Still running - continue
        }
    }

    private void handleBanking() {
        // If we have an active resupply task, execute it
        if (activeResupplyTask != null) {
            activeResupplyTask.execute(taskContext);
            
            if (!activeResupplyTask.getState().isTerminal()) {
                return; // Still in progress
            }
            
            // Check result
            if (activeResupplyTask.getState() == TaskState.FAILED) {
                log.error("Resupply failed: {}", activeResupplyTask.getDescription());
                stopSession();
                return;
            }
            
            // Resupply complete
            log.info("Resupply complete, resuming task preparation");
            activeResupplyTask = null;
            clearMissingItemsState();
            
            // Go back to preparing (will re-validate items)
            currentPhase = SlayerSessionPhase.PREPARING;
            return;
        }
        
        // Build list of items to withdraw
        Map<Integer, Integer> itemsToWithdraw = buildResupplyItemList();
        
        if (itemsToWithdraw.isEmpty()) {
            log.warn("Banking triggered but no items to withdraw - returning to preparing");
            clearMissingItemsState();
            currentPhase = SlayerSessionPhase.PREPARING;
            return;
        }
        
        // Determine return position
        WorldPoint returnPos = null;
        if (sessionConfig != null && sessionConfig.isReturnToSameSpot()) {
            returnPos = preResupplyPosition;
        } else if (currentResolution != null && currentResolution.getLocation() != null) {
            // Return to task location after banking
            returnPos = currentResolution.getLocation().getCenter();
        }
        
        // Store current position if configured
        if (sessionConfig != null && sessionConfig.isReturnToSameSpot() && preResupplyPosition == null) {
            preResupplyPosition = taskContext.getPlayerState().getWorldPosition();
        }
        
        // Build and start resupply task
        ResupplyTask.ResupplyTaskBuilder builder = ResupplyTask.builder()
                .depositInventory(true)
                .withdrawItems(itemsToWithdraw);
        
        // Use configured bank location if available
        if (sessionConfig != null && sessionConfig.getBankLocation() != null) {
            builder.bankLocation(sessionConfig.getBankLocation());
        }
        
        if (returnPos != null) {
            builder.returnPosition(returnPos);
        }
        
        activeResupplyTask = builder.build();
        log.info("Starting resupply task for {} item types", itemsToWithdraw.size());
    }
    
    /**
     * Build the list of items to withdraw based on missing items state.
     */
    private Map<Integer, Integer> buildResupplyItemList() {
        Map<Integer, Integer> items = new java.util.LinkedHashMap<>();
        
        // Add missing equipment (quantity 1 each)
        if (missingEquipmentIds != null) {
            for (int itemId : missingEquipmentIds) {
                items.put(itemId, 1);
            }
        }
        
        // Add missing finish-off items
        if (missingFinishOffItemId > 0 && sessionConfig != null) {
            items.put(missingFinishOffItemId, sessionConfig.getFinishOffQuantity());
        }
        
        // Add missing location-specific items
        if (missingLocationItemIds != null) {
            for (int itemId : missingLocationItemIds) {
                items.put(itemId, 1);
            }
        }
        
        // Add food if configured
        if (sessionConfig != null && sessionConfig.getFoodItemId() > 0) {
            items.put(sessionConfig.getFoodItemId(), sessionConfig.getFoodQuantity());
        }
        
        return items;
    }
    
    /**
     * Clear the missing items state after banking.
     */
    private void clearMissingItemsState() {
        missingEquipmentIds = null;
        missingFinishOffItemId = -1;
        missingLocationItemIds = null;
        preResupplyPosition = null;
    }

    private void handleCompletingTask() {
        currentResolution = null;
        currentSlayerTask = null;

        if (sessionConfig != null && sessionConfig.isAutoGetNewTask()) {
            currentPhase = SlayerSessionPhase.CHECKING_TASK;
        } else {
            currentPhase = SlayerSessionPhase.IDLE;
            sessionActive = false;
            log.info("Task complete, session ended (autoGetNewTask=false)");
        }
    }

    // ========================================================================
    // Task Completion Handlers
    // ========================================================================

    private void onPendingTaskCompleted() {
        switch (currentPhase) {
            case TRAVELING_TO_MASTER:
                // Arrived at master, now interact
                startInteractWithMaster();
                break;

            case TALKING_TO_MASTER:
                // Interaction done, now dialogue
                startDialogueWithMaster();
                break;

            case GETTING_TASK:
                // Dialogue complete, check what task we got
                currentPhase = SlayerSessionPhase.CHECKING_TASK;
                break;

            case SKIPPING_TASK:
                // Handle skip task sub-state machine
                if (skipPhase == SkipPhase.COMPLETE) {
                    // Skip complete, check for new task
                    skipPhase = SkipPhase.OPENING_REWARDS; // Reset for next time
                    currentPhase = SlayerSessionPhase.CHECKING_TASK;
                } else {
                    handleSkipTaskProgress();
                }
                break;

            case TRAVELING_TO_TASK:
                // Arrived at task location, start fighting
                startFighting();
                break;

            default:
                // Nothing special
                break;
        }
    }

    private void onPendingTaskFailed() {
        log.warn("Task failed in phase {}", currentPhase);
        // Recover by going back to checking task
        currentPhase = SlayerSessionPhase.CHECKING_TASK;
    }

    // ========================================================================
    // Action Initiators
    // ========================================================================

    private void startGetNewTask() {
        currentMaster = selectMaster(sessionConfig != null ? sessionConfig.getPreferredMaster() : null);
        log.info("Getting new task from {}", currentMaster);

        // Travel to master first
        startTravelToMaster();
    }

    private void startTravelToMaster() {
        WorldPoint masterLocation = currentMaster.getLocation();
        WorldPoint playerLocation = taskContext.getPlayerState().getWorldPosition();

        if (playerLocation != null && playerLocation.distanceTo(masterLocation) < 10) {
            log.debug("Already near master {}", currentMaster);
            // Skip travel, go straight to interaction
            startInteractWithMaster();
            return;
        }

        log.info("Traveling to master {} at {}", currentMaster, masterLocation);
        currentPhase = SlayerSessionPhase.TRAVELING_TO_MASTER;

        WalkToTask walkTask = new WalkToTask(masterLocation)
                .withDescription("Travel to " + currentMaster.getDisplayName());

        queueAndAwait(walkTask);
    }

    private void startInteractWithMaster() {
        currentPhase = SlayerSessionPhase.TALKING_TO_MASTER;

        InteractNpcTask interactTask = new InteractNpcTask(currentMaster.getNpcId(), "Talk-to")
                .withAlternateIds(currentMaster.getAlternateNpcId())
                .withDescription("Talk to " + currentMaster.getDisplayName());

        queueAndAwait(interactTask);
    }

    private void startDialogueWithMaster() {
        currentPhase = SlayerSessionPhase.GETTING_TASK;

        DialogueTask dialogueTask = new DialogueTask()
                .withOptionSequence(currentMaster.getNewTaskDialogueOptions())
                .withClickThroughAll(true)
                .withDescription("Get new task from " + currentMaster.getDisplayName());

        queueAndAwait(dialogueTask);
    }

    private void startSkipTask() {
        currentMaster = getSlayerState().getCurrentMaster();
        if (currentMaster == null) {
            currentMaster = autoSelectMaster();
        }

        log.info("Skipping task via {}", currentMaster);

        // Travel to master if needed
        WorldPoint masterLocation = currentMaster.getLocation();
        WorldPoint playerLocation = taskContext.getPlayerState().getWorldPosition();

        if (playerLocation != null && playerLocation.distanceTo(masterLocation) < 10) {
            startSkipInteraction();
        } else {
            currentPhase = SlayerSessionPhase.TRAVELING_TO_MASTER;
            WalkToTask walkTask = new WalkToTask(masterLocation)
                    .withDescription("Travel to " + currentMaster.getDisplayName() + " to skip");
            queueAndAwait(walkTask);
        }
    }

    private void startSkipInteraction() {
        currentPhase = SlayerSessionPhase.SKIPPING_TASK;
        skipPhase = SkipPhase.OPENING_REWARDS;

        InteractNpcTask interactTask = new InteractNpcTask(currentMaster.getNpcId(), "Rewards")
                .withAlternateIds(currentMaster.getAlternateNpcId())
                .withDescription("Open rewards for " + currentMaster.getDisplayName());

        queueAndAwait(interactTask);
    }
    
    /**
     * Handle the skip task sub-state machine within SKIPPING_TASK phase.
     * Called when a skip-related pending task completes.
     */
    private void handleSkipTaskProgress() {
        Client client = taskContext.getClient();
        
        switch (skipPhase) {
            case OPENING_REWARDS:
                // Check if rewards interface is open
                Widget rewardsWidget = client.getWidget(SLAYER_REWARDS_GROUP, SLAYER_REWARDS_MAIN);
                if (rewardsWidget != null && !rewardsWidget.isHidden()) {
                    log.debug("Rewards interface opened, clicking Tasks tab");
                    skipPhase = SkipPhase.CLICKING_TASKS_TAB;
                    
                    // Click the Tasks tab
                    WidgetInteractTask clickTasksTab = new WidgetInteractTask(
                            SLAYER_REWARDS_GROUP, SLAYER_REWARDS_TASKS_TAB)
                            .withDescription("Click Tasks tab in rewards");
                    queueAndAwait(clickTasksTab);
                } else {
                    log.warn("Rewards interface not visible after interaction");
                    // Retry opening
                    startSkipInteraction();
                }
                break;
                
            case CLICKING_TASKS_TAB:
                // Now need to click the cancel button on current task
                log.debug("Tasks tab clicked, clicking cancel on current task");
                skipPhase = SkipPhase.CLICKING_CANCEL;
                
                // Click the current task widget - the "Cancel" option is a direct click
                // The tasks view shows current task with a cancel button
                WidgetInteractTask clickCancel = new WidgetInteractTask(
                        SLAYER_REWARDS_GROUP, SLAYER_REWARDS_CURRENT_TASK)
                        .withDescription("Cancel current slayer task");
                queueAndAwait(clickCancel);
                break;
                
            case CLICKING_CANCEL:
                // Check for confirmation dialogue or completion
                // The task should be cancelled now - verify via SlayerState
                SlayerState state = getSlayerState();
                if (!state.hasTask()) {
                    log.info("Task successfully cancelled");
                    skipPhase = SkipPhase.COMPLETE;
                    closeRewardsInterface();
                } else {
                    // Might need to handle confirmation dialogue
                    log.debug("Checking for confirmation dialogue");
                    skipPhase = SkipPhase.CONFIRMING;
                    
                    // Handle any confirmation dialogue
                    DialogueTask confirmTask = new DialogueTask()
                            .withPatternOption("Yes|Confirm|Cancel task")
                            .withDescription("Confirm task cancellation");
                    queueAndAwait(confirmTask);
                }
                break;
                
            case CONFIRMING:
                // After confirmation, should be done
                log.info("Task skip confirmed");
                skipPhase = SkipPhase.COMPLETE;
                closeRewardsInterface();
                break;
                
            case COMPLETE:
                // Skip is done, transition to CHECKING_TASK handled by onPendingTaskCompleted
                break;
        }
    }
    
    /**
     * Close the rewards interface after skipping task.
     */
    private void closeRewardsInterface() {
        // Press Escape to close
        WidgetInteractTask closeTask = WidgetInteractTask.pressKey(java.awt.event.KeyEvent.VK_ESCAPE)
                .withDescription("Close rewards interface");
        queueAndAwait(closeTask);
    }
    
    /**
     * Phases within the skip task flow.
     */
    private enum SkipPhase {
        OPENING_REWARDS,
        CLICKING_TASKS_TAB,
        CLICKING_CANCEL,
        CONFIRMING,
        COMPLETE
    }

    private void startPrepareForTask() {
        currentPhase = SlayerSessionPhase.PREPARING;
        // Will be handled in next tick by handlePreparing()
    }

    private void startTravelToTask() {
        if (currentResolution == null || !currentResolution.isSuccess()) {
            log.error("Cannot travel - no valid resolution");
            currentPhase = SlayerSessionPhase.CHECKING_TASK;
            return;
        }

        SlayerLocation location = currentResolution.getLocation();
        log.info("Traveling to {} for task", location.getName());
        currentPhase = SlayerSessionPhase.TRAVELING_TO_TASK;

        WalkToTask walkTask = new WalkToTask(location.getCenter())
                .withDescription("Travel to " + location.getName());

        queueAndAwait(walkTask);
    }

    private void startFighting() {
        if (currentResolution == null || !currentResolution.isSuccess()) {
            log.error("Cannot fight - no valid resolution");
            currentPhase = SlayerSessionPhase.CHECKING_TASK;
            return;
        }

        log.info("Starting combat at {}", currentResolution.getLocation().getName());
        currentPhase = SlayerSessionPhase.FIGHTING;

        // Create and queue the slayer task
        SlayerState state = getSlayerState();
        currentSlayerTask = SlayerTask.fromResolution(
                currentResolution,
                state,
                targetSelector,
                combatManager,
                gameStateService,
                equipmentManager);

        taskExecutor.queueTask(currentSlayerTask);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void resolveTaskLocation(SlayerState state) {
        String taskName = state.getTaskName();
        String konarRegion = state.getTaskLocation();
        IronmanState ironmanState = gameStateService.getIronmanState();
        WorldPoint playerLocation = taskContext.getPlayerState().getWorldPosition();

        TaskLocationResolver.ResolverOptions options = getResolverOptions();
        currentResolution = locationResolver.resolveLocation(
                taskName, konarRegion, ironmanState, playerLocation, options);

        if (currentResolution.isSuccess()) {
            log.info("Resolved location for {}: {}", taskName, currentResolution.getLocation().getName());
        } else {
            log.error("Failed to resolve location for {}: {}", taskName, currentResolution.getFailureReason());
        }
    }

    private TaskLocationResolver.ResolverOptions getResolverOptions() {
        if (sessionConfig == null) {
            return TaskLocationResolver.ResolverOptions.DEFAULT;
        }

        IronmanState ironmanState = gameStateService.getIronmanState();
        boolean isHcim = ironmanState != null && ironmanState.isHardcore();

        if (!isHcim || !sessionConfig.isHcimSafetyEnabled()) {
            return TaskLocationResolver.ResolverOptions.IGNORE_SAFETY;
        }

        return TaskLocationResolver.ResolverOptions.builder()
                .applyHcimSafety(true)
                .minHcimSafety(sessionConfig.getMinHcimSafety())
                .allowMultiCombat(sessionConfig.isAllowMultiCombat())
                .considerDistance(true)
                .preferHighDensity(true)
                .build();
    }

    /**
     * Queue a task and set it as the pending task we're waiting for.
     */
    private void queueAndAwait(Task task) {
        this.pendingTask = task;
        taskExecutor.queueTask(task);
    }

    // ========================================================================
    // Master Selection
    // ========================================================================

    /**
     * Select the best slayer master based on player stats and preferences.
     *
     * @param preferredMaster optional preferred master, or null for auto-select
     * @return the selected master
     */
    public SlayerMaster selectMaster(@Nullable SlayerMaster preferredMaster) {
        if (preferredMaster != null) {
            int combatLevel = unlockTracker.getCombatLevel();
            int slayerLevel = unlockTracker.getSkillLevel(Skill.SLAYER);

            if (preferredMaster.meetsRequirements(combatLevel, slayerLevel)) {
                return preferredMaster;
            }
            log.warn("Preferred master {} requirements not met (combat={}, slayer={}), auto-selecting",
                    preferredMaster, combatLevel, slayerLevel);
        }

        return autoSelectMaster();
    }

    /**
     * Auto-select the best master based on current stats.
     *
     * @return the best available master
     */
    public SlayerMaster autoSelectMaster() {
        int combatLevel = unlockTracker.getCombatLevel();
        int slayerLevel = unlockTracker.getSkillLevel(Skill.SLAYER);

        boolean allowKonar = sessionConfig != null && sessionConfig.isAllowKonar();
        boolean allowWilderness = sessionConfig != null && sessionConfig.isAllowWilderness();

        SlayerMaster selected = SlayerMaster.getBestMaster(
                combatLevel, slayerLevel, allowKonar, allowWilderness);

        log.debug("Auto-selected master {} for combat={}, slayer={}",
                selected, combatLevel, slayerLevel);
        return selected;
    }

    // ========================================================================
    // Task Skip/Block Logic
    // ========================================================================

    /**
     * Determine if the current task should be skipped.
     *
     * @param state current slayer state
     * @return true if task should be skipped
     */
    public boolean shouldSkipTask(SlayerState state) {
        if (!state.hasTask() || sessionConfig == null) {
            return false;
        }

        String taskName = state.getTaskName();

        // Check if task is on block list
        if (state.isTaskBlocked(taskName)) {
            log.debug("Task {} is blocked, cannot get this task", taskName);
            return false; // Already blocked, shouldn't receive
        }

        // Check skip list in config
        Set<String> skipList = sessionConfig.getSkipTaskList();
        if (skipList != null && skipList.stream().anyMatch(s -> s.equalsIgnoreCase(taskName))) {
            if (state.canSkipTask()) {
                log.info("Task {} is in skip list and can afford skip", taskName);
                return true;
            }
            log.warn("Task {} is in skip list but cannot afford skip (points={})",
                    taskName, state.getSlayerPoints());
        }

        return false;
    }

    // ========================================================================
    // Session Phase Enum
    // ========================================================================

    public enum SlayerSessionPhase {
        IDLE,
        CHECKING_TASK,
        TRAVELING_TO_MASTER,
        TALKING_TO_MASTER,
        GETTING_TASK,
        SKIPPING_TASK,
        PREPARING,
        TRAVELING_TO_TASK,
        FIGHTING,
        BANKING,
        COMPLETING_TASK
    }

    // ========================================================================
    // Session Configuration
    // ========================================================================

    @Value
    @Builder
    public static class SlayerSessionConfig {

        /**
         * Preferred slayer master, or null for auto-select.
         */
        @Nullable
        SlayerMaster preferredMaster;

        /**
         * Whether to allow Konar (location-restricted) tasks.
         */
        @Builder.Default
        boolean allowKonar = true;

        /**
         * Whether to allow wilderness tasks (Krystilia).
         */
        @Builder.Default
        boolean allowWilderness = false;

        /**
         * Task names to skip (will spend points to skip).
         */
        @Nullable
        Set<String> skipTaskList;

        /**
         * Whether to apply HCIM safety filtering.
         */
        @Builder.Default
        boolean hcimSafetyEnabled = true;

        /**
         * Minimum HCIM safety rating (1-10).
         */
        @Builder.Default
        int minHcimSafety = 6;

        /**
         * Whether HCIM can use multi-combat areas.
         */
        @Builder.Default
        boolean allowMultiCombat = false;

        /**
         * Whether to automatically get new tasks when current completes.
         */
        @Builder.Default
        boolean autoGetNewTask = true;

        /**
         * Whether to use superiors if unlocked.
         */
        @Builder.Default
        boolean prioritizeSuperiors = true;

        /**
         * Whether to use finish-off items manually (vs auto-kill unlock).
         */
        @Builder.Default
        boolean useFinishOffItems = true;

        // ====================================================================
        // Banking / Resupply Configuration
        // ====================================================================

        /**
         * Whether to enable banking/resupply during slayer sessions.
         */
        @Builder.Default
        boolean resupplyEnabled = true;

        /**
         * Preferred bank location, or null to use nearest bank.
         */
        @Nullable
        WorldPoint bankLocation;

        /**
         * Food item ID to withdraw.
         */
        @Builder.Default
        int foodItemId = ItemID.SHARK;

        /**
         * Quantity of food to withdraw.
         */
        @Builder.Default
        int foodQuantity = 10;

        /**
         * Quantity of finish-off items to withdraw (e.g., ice coolers).
         */
        @Builder.Default
        int finishOffQuantity = 50;

        /**
         * Whether to return to exact pre-banking position after resupply.
         */
        @Builder.Default
        boolean returnToSameSpot = false;

        /**
         * Create default config for regular accounts.
         */
        public static SlayerSessionConfig defaultConfig() {
            return SlayerSessionConfig.builder().build();
        }

        /**
         * Create safe config for HCIM.
         */
        public static SlayerSessionConfig hcimConfig() {
            return SlayerSessionConfig.builder()
                    .allowWilderness(false)
                    .hcimSafetyEnabled(true)
                    .minHcimSafety(7)
                    .allowMultiCombat(false)
                    .build();
        }
    }
}
