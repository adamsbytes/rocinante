package com.rocinante.tasks.impl.skills.cooking;

import com.rocinante.input.RobotKeyboardController;
import com.rocinante.navigation.EntityFinder;
import com.rocinante.progression.MethodLocation;
import com.rocinante.progression.TrainingMethod;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.impl.DropInventoryTask;
import com.rocinante.tasks.impl.ResupplyTask;
import com.rocinante.tasks.impl.UseItemOnObjectTask;
import com.rocinante.tasks.impl.WalkToTask;
import com.rocinante.util.ObjectCollections;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import java.awt.event.KeyEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * High-level orchestrator for Cooking skill training.
 *
 * <p>CookingSkillTask coordinates all aspects of a cooking training session:
 * <ul>
 *   <li>Navigate to cooking location (fire or range)</li>
 *   <li>Use raw food on fire/range</li>
 *   <li>Handle make-all interface</li>
 *   <li>Wait for cooking to complete</li>
 *   <li>Loop for all raw food in inventory</li>
 *   <li>Drop or bank products based on configuration</li>
 * </ul>
 *
 * <p>Finds fires/ranges automatically using {@link ObjectCollections#COOKING_FIRES}
 * and {@link ObjectCollections#COOKING_RANGES} - no need to specify object IDs in method config.
 *
 * <p>Example usage:
 * <pre>{@code
 * TrainingMethod method = methodRepo.getMethodById("shrimp_cooking").get();
 * CookingSkillTaskConfig config = CookingSkillTaskConfig.builder()
 *     .method(method)
 *     .locationId("lumbridge")
 *     .productHandling(ProductHandling.DROP_ALL)
 *     .targetLevel(30)
 *     .build();
 * CookingSkillTask task = new CookingSkillTask(config);
 * }</pre>
 *
 * @see CookingSkillTaskConfig for configuration options
 * @see UseItemOnObjectTask for the underlying cooking interaction
 */
@Slf4j
public class CookingSkillTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Maximum ticks to wait for make-all interface.
     */
    private static final int INTERFACE_WAIT_TIMEOUT_TICKS = 15;

    /**
     * Maximum ticks to wait for cooking animation to complete.
     */
    private static final int COOKING_TIMEOUT_TICKS = 150;

    /**
     * Ticks to confirm player is idle before moving on.
     */
    private static final int IDLE_CONFIRMATION_TICKS = 3;

    /**
     * Make-all widget group ID.
     */
    private static final int MAKE_ALL_WIDGET_GROUP = 270;

    /**
     * Search radius for finding fires/ranges (tiles).
     */
    private static final int COOKING_OBJECT_SEARCH_RADIUS = 15;

    /**
     * Minimum delay before pressing space on make-all interface.
     */
    private static final long MIN_INTERFACE_DELAY_MS = 200;

    /**
     * Maximum delay before pressing space on make-all interface.
     */
    private static final long MAX_INTERFACE_DELAY_MS = 600;

    // ========================================================================
    // Burnt Food Mapping
    // ========================================================================

    /**
     * Mapping of cooked food → burnt food.
     * Used to identify burnt food for BANK_BUT_DROP_BURNT mode.
     */
    private static final Map<Integer, Integer> COOKED_TO_BURNT = new HashMap<>();

    static {
        // Shrimp
        COOKED_TO_BURNT.put(ItemID.SHRIMPS, ItemID.BURNT_SHRIMP);
        // Fish - many share BURNT_FISH (343)
        COOKED_TO_BURNT.put(ItemID.TROUT, ItemID.BURNT_FISH);
        COOKED_TO_BURNT.put(ItemID.SALMON, ItemID.BURNT_FISH);
        COOKED_TO_BURNT.put(ItemID.ANCHOVIES, ItemID.BURNT_FISH);
        COOKED_TO_BURNT.put(ItemID.HERRING, ItemID.BURNT_FISH);
        COOKED_TO_BURNT.put(ItemID.SARDINE, ItemID.BURNT_FISH);
        COOKED_TO_BURNT.put(ItemID.PIKE, ItemID.BURNT_FISH);
        // Lobster
        COOKED_TO_BURNT.put(ItemID.LOBSTER, ItemID.BURNT_LOBSTER);
        // Swordfish
        COOKED_TO_BURNT.put(ItemID.SWORDFISH, ItemID.BURNT_SWORDFISH);
        // Shark
        COOKED_TO_BURNT.put(ItemID.SHARK, ItemID.BURNT_SHARK);
        // Monkfish
        COOKED_TO_BURNT.put(ItemID.MONKFISH, ItemID.BURNT_MONKFISH);
        // Karambwan
        COOKED_TO_BURNT.put(ItemID.COOKED_KARAMBWAN, ItemID.BURNT_KARAMBWAN);
        // Tuna
        COOKED_TO_BURNT.put(ItemID.TUNA, ItemID.BURNT_FISH);
        // Anglerfish
        COOKED_TO_BURNT.put(ItemID.ANGLERFISH, ItemID.BURNT_ANGLERFISH);
        // Manta ray
        COOKED_TO_BURNT.put(ItemID.MANTA_RAY, ItemID.BURNT_MANTA_RAY);
        // Sea turtle
        COOKED_TO_BURNT.put(ItemID.SEA_TURTLE, ItemID.BURNT_SEA_TURTLE);
        // Dark crab
        COOKED_TO_BURNT.put(ItemID.DARK_CRAB, ItemID.BURNT_DARK_CRAB);
    }

    /**
     * Get all known burnt food item IDs.
     */
    private static Set<Integer> getAllBurntFoodIds() {
        return new HashSet<>(COOKED_TO_BURNT.values());
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Task configuration.
     */
    @Getter
    private final CookingSkillTaskConfig config;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private CookingPhase phase = CookingPhase.PREPARE;

    /**
     * Active sub-task.
     */
    private Task currentSubTask;

    /**
     * Task start time.
     */
    private Instant startTime;

    /**
     * Starting XP.
     */
    private int startXp = -1;

    /**
     * Number of items cooked.
     */
    @Getter
    private int itemsCooked = 0;

    /**
     * Number of items burnt.
     */
    @Getter
    private int itemsBurnt = 0;

    /**
     * Whether we're waiting for an async operation.
     */
    private boolean operationPending = false;

    /**
     * Ticks spent in current phase.
     */
    private int phaseTicks = 0;

    /**
     * Ticks spent idle (not animating).
     */
    private int idleTicks = 0;

    /**
     * The raw food item ID we're currently cooking.
     */
    private int currentRawFoodId = -1;

    /**
     * Cached combined list of all cooking objects (fires + ranges).
     */
    private List<Integer> cookingObjectIds;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a cooking skill task with the given configuration.
     *
     * @param config the task configuration
     */
    public CookingSkillTask(CookingSkillTaskConfig config) {
        this.config = config;

        // Combine fires and ranges into one list
        this.cookingObjectIds = new ArrayList<>();
        this.cookingObjectIds.addAll(ObjectCollections.COOKING_FIRES);
        this.cookingObjectIds.addAll(ObjectCollections.COOKING_RANGES);
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (!ctx.isLoggedIn()) {
            return false;
        }

        // Check if we've reached target level
        if (config.getTargetLevel() > 0) {
            int currentLevel = ctx.getClient().getRealSkillLevel(Skill.COOKING);
            if (currentLevel >= config.getTargetLevel()) {
                return false;
            }
        }

        // Check if we've reached target XP
        if (config.getTargetXp() > 0) {
            int currentXp = ctx.getClient().getSkillExperience(Skill.COOKING);
            if (currentXp >= config.getTargetXp()) {
                return false;
            }
        }

        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (startTime == null) {
            initializeTask(ctx);
        }

        // Check completion
        if (isCompleted(ctx)) {
            completeTask(ctx);
            return;
        }

        // Execute active sub-task if present
        if (currentSubTask != null) {
            currentSubTask.execute(ctx);
            if (currentSubTask.getState().isTerminal()) {
                handleSubTaskComplete(ctx);
            }
            return;
        }

        // Execute current phase
        phaseTicks++;

        switch (phase) {
            case PREPARE:
                executePrepare(ctx);
                break;
            case COOK:
                executeCook(ctx);
                break;
            case WAIT_INTERFACE:
                executeWaitInterface(ctx);
                break;
            case WAIT_COMPLETE:
                executeWaitComplete(ctx);
                break;
            case CHECK_MORE_TO_COOK:
                executeCheckMoreToCook(ctx);
                break;
            case DROP_OR_BANK:
                executeDropOrBank(ctx);
                break;
            case CHECK_CONTINUE:
                executeCheckContinue(ctx);
                break;
        }
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    private void initializeTask(TaskContext ctx) {
        startTime = Instant.now();
        startXp = ctx.getClient().getSkillExperience(Skill.COOKING);
        log.info("CookingSkillTask started: {} at {}", 
                config.getMethod().getName(),
                config.getLocation() != null ? config.getLocation().getName() : "unknown location");
    }

    // ========================================================================
    // Completion Checking
    // ========================================================================

    private boolean isCompleted(TaskContext ctx) {
        // Check time limit
        if (config.hasTimeLimit() && startTime != null) {
            Duration elapsed = Duration.between(startTime, Instant.now());
            if (elapsed.compareTo(config.getMaxDuration()) >= 0) {
                log.info("Time limit reached");
                return true;
            }
        }

        // Check target level
        if (config.getTargetLevel() > 0) {
            int currentLevel = ctx.getClient().getRealSkillLevel(Skill.COOKING);
            if (currentLevel >= config.getTargetLevel()) {
                log.info("Target level {} reached", config.getTargetLevel());
                return true;
            }
        }

        // Check target XP
        if (config.getTargetXp() > 0) {
            int currentXp = ctx.getClient().getSkillExperience(Skill.COOKING);
            if (currentXp >= config.getTargetXp()) {
                log.info("Target XP {} reached", config.getTargetXp());
                return true;
            }
        }

        return false;
    }

    private void completeTask(TaskContext ctx) {
        int xpGained = ctx.getClient().getSkillExperience(Skill.COOKING) - startXp;
        log.info("CookingSkillTask complete: {} items cooked, {} burnt, {} XP gained",
                itemsCooked, itemsBurnt, xpGained);
        complete();
    }

    // ========================================================================
    // Phase: Prepare
    // ========================================================================

    private void executePrepare(TaskContext ctx) {
        MethodLocation location = config.getLocation();
        if (location == null) {
            fail("No location configured");
            return;
        }

        WorldPoint targetPos = location.getTrainingArea();
        if (targetPos == null) {
            targetPos = location.getExactPosition();
        }

        if (targetPos != null) {
            WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
            if (playerPos.distanceTo(targetPos) > 15) {
                log.info("Traveling to cooking location: {}", location.getName());
                currentSubTask = new WalkToTask(targetPos)
                        .withDescription("Travel to " + location.getName());
                return;
            }
        }

        // Check if we have raw food
        TrainingMethod method = config.getMethod();
        int rawFoodId = method.getSourceItemId();
        if (rawFoodId <= 0) {
            fail("No raw food item ID configured in method");
            return;
        }

        InventoryState inventory = ctx.getInventoryState();
        if (!inventory.hasItem(rawFoodId)) {
            // No raw food - check if we should bank for more
            if (config.getProductHandling() != CookingSkillTaskConfig.ProductHandling.DROP_ALL) {
                log.info("No raw food in inventory, banking");
                recordPhaseTransition(CookingPhase.DROP_OR_BANK);
                phase = CookingPhase.DROP_OR_BANK;
            } else {
                log.info("No raw food in inventory");
                completeTask(ctx);
            }
            return;
        }

        recordPhaseTransition(CookingPhase.COOK);
        phase = CookingPhase.COOK;
        phaseTicks = 0;
    }

    // ========================================================================
    // Phase: Cook
    // ========================================================================

    private void executeCook(TaskContext ctx) {
        TrainingMethod method = config.getMethod();
        int rawFoodId = method.getSourceItemId();

        InventoryState inventory = ctx.getInventoryState();
        if (!inventory.hasItem(rawFoodId)) {
            // No more of this raw food, check for others
            recordPhaseTransition(CookingPhase.CHECK_MORE_TO_COOK);
            phase = CookingPhase.CHECK_MORE_TO_COOK;
            phaseTicks = 0;
            return;
        }

        currentRawFoodId = rawFoodId;

        // Create UseItemOnObjectTask for cooking
        currentSubTask = new UseItemOnObjectTask(rawFoodId, cookingObjectIds)
                .withDescription("Cook " + method.getName());

        log.debug("Starting cooking interaction: raw food {} on fire/range", rawFoodId);
    }

    // ========================================================================
    // Phase: Wait Interface
    // ========================================================================

    private void executeWaitInterface(TaskContext ctx) {
        if (operationPending) {
            return;
        }

        // Check if make-all interface is visible
        Client client = ctx.getClient();
        Widget makeAllWidget = client.getWidget(MAKE_ALL_WIDGET_GROUP, 0);

        if (makeAllWidget != null && !makeAllWidget.isHidden()) {
            // Interface visible - press space after humanized delay
            log.debug("Make-all interface visible, pressing space");
            operationPending = true;

            long delay = ctx.getRandomization().uniformRandomLong(
                    MIN_INTERFACE_DELAY_MS, MAX_INTERFACE_DELAY_MS);
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).thenRun(() -> {
                RobotKeyboardController keyboard = ctx.getKeyboardController();
                keyboard.pressKey(KeyEvent.VK_SPACE).join();
                operationPending = false;

                recordPhaseTransition(CookingPhase.WAIT_COMPLETE);
                phase = CookingPhase.WAIT_COMPLETE;
                phaseTicks = 0;
                idleTicks = 0;
            });
            return;
        }

        // Check timeout
        if (phaseTicks > INTERFACE_WAIT_TIMEOUT_TICKS) {
            log.warn("Timeout waiting for make-all interface, retrying cook");
            recordPhaseTransition(CookingPhase.COOK);
            phase = CookingPhase.COOK;
            phaseTicks = 0;
        }
    }

    // ========================================================================
    // Phase: Wait Complete
    // ========================================================================

    private void executeWaitComplete(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();

        if (!player.isAnimating()) {
            idleTicks++;

            if (idleTicks >= IDLE_CONFIRMATION_TICKS) {
                // Cooking complete for this batch
                log.debug("Cooking animation complete");

                // Count cooked items (approximate based on inventory changes)
                updateCookingStats(ctx);

                recordPhaseTransition(CookingPhase.CHECK_MORE_TO_COOK);
                phase = CookingPhase.CHECK_MORE_TO_COOK;
                phaseTicks = 0;
            }
        } else {
            idleTicks = 0;
        }

        // Check timeout
        if (phaseTicks > COOKING_TIMEOUT_TICKS) {
            log.warn("Timeout waiting for cooking to complete");
            recordPhaseTransition(CookingPhase.CHECK_MORE_TO_COOK);
            phase = CookingPhase.CHECK_MORE_TO_COOK;
            phaseTicks = 0;
        }
    }

    private void updateCookingStats(TaskContext ctx) {
        // This is called after each cooking batch completes
        // We could track inventory changes more precisely, but for now
        // we'll just increment based on assumed success
        InventoryState inventory = ctx.getInventoryState();
        TrainingMethod method = config.getMethod();

        int cookedId = method.getOutputItemId();
        int burntId = COOKED_TO_BURNT.getOrDefault(cookedId, -1);

        int cookedCount = cookedId > 0 ? inventory.countItem(cookedId) : 0;
        int burntCount = burntId > 0 ? inventory.countItem(burntId) : 0;

        // These are running totals, so we don't add here - stats are updated in completeTask
        log.debug("Inventory: {} cooked, {} burnt", cookedCount, burntCount);
    }

    // ========================================================================
    // Phase: Check More To Cook
    // ========================================================================

    private void executeCheckMoreToCook(TaskContext ctx) {
        TrainingMethod method = config.getMethod();
        int rawFoodId = method.getSourceItemId();

        InventoryState inventory = ctx.getInventoryState();

        if (inventory.hasItem(rawFoodId)) {
            // More raw food available - cook it
            log.debug("More raw food available, continuing cooking");
            recordPhaseTransition(CookingPhase.COOK);
            phase = CookingPhase.COOK;
            phaseTicks = 0;
        } else {
            // No more raw food - time to drop/bank
            log.debug("No more raw food, moving to drop/bank phase");
            recordPhaseTransition(CookingPhase.DROP_OR_BANK);
            phase = CookingPhase.DROP_OR_BANK;
            phaseTicks = 0;
        }
    }

    // ========================================================================
    // Phase: Drop or Bank
    // ========================================================================

    private void executeDropOrBank(TaskContext ctx) {
        TrainingMethod method = config.getMethod();
        int cookedFoodId = method.getOutputItemId();
        int rawFoodId = method.getSourceItemId();

        // Get training location for return after banking
        MethodLocation location = config.getLocation();
        WorldPoint returnPos = location != null ? 
                (location.getTrainingArea() != null ? location.getTrainingArea() : location.getExactPosition())
                : null;

        switch (config.getProductHandling()) {
            case DROP_ALL:
                // Drop all cooked and burnt food
                List<Integer> toDrop = new ArrayList<>();
                toDrop.add(cookedFoodId);

                // Add burnt version if known
                Integer burntId = COOKED_TO_BURNT.get(cookedFoodId);
                if (burntId != null) {
                    toDrop.add(burntId);
                }
                // Also add generic burnt fish
                toDrop.add(ItemID.BURNT_FISH);

                currentSubTask = DropInventoryTask.forItemIds(toDrop)
                        .withDescription("Drop cooked/burnt food")
                        .withPattern(DropInventoryTask.DropPattern.COLUMN);
                break;

            case BANK_ALL:
                // Bank everything, withdraw more raw food
                ResupplyTask.ResupplyTaskBuilder bankAllBuilder = ResupplyTask.builder()
                        .depositInventory(true)
                        .withdrawItem(rawFoodId, 28);
                
                if (returnPos != null) {
                    bankAllBuilder.returnPosition(returnPos);
                }

                currentSubTask = bankAllBuilder.build();
                break;

            case BANK_BUT_DROP_BURNT:
                // First drop burnt, then bank
                List<Integer> burntToDrop = new ArrayList<>(getAllBurntFoodIds());

                InventoryState inventory = ctx.getInventoryState();
                boolean hasBurnt = burntToDrop.stream().anyMatch(inventory::hasItem);

                if (hasBurnt) {
                    currentSubTask = DropInventoryTask.forItemIds(burntToDrop)
                            .withDescription("Drop burnt food");
                } else {
                    // No burnt food, bank cooked and get more raw food
                    ResupplyTask.ResupplyTaskBuilder bankBuilder = ResupplyTask.builder()
                            .depositInventory(true)
                            .withdrawItem(rawFoodId, 28);
                    
                    if (returnPos != null) {
                        bankBuilder.returnPosition(returnPos);
                    }

                    currentSubTask = bankBuilder.build();
                }
                break;
        }
    }

    // ========================================================================
    // Phase: Check Continue
    // ========================================================================

    private void executeCheckContinue(TaskContext ctx) {
        // Check if we should continue (have more raw food after banking)
        TrainingMethod method = config.getMethod();
        int rawFoodId = method.getSourceItemId();

        InventoryState inventory = ctx.getInventoryState();

        if (inventory.hasItem(rawFoodId)) {
            // Have more raw food - go back to prepare
            log.debug("Have more raw food after banking, continuing");
            recordPhaseTransition(CookingPhase.PREPARE);
            phase = CookingPhase.PREPARE;
            phaseTicks = 0;
        } else {
            // No more raw food in bank/inventory
            log.info("No more raw food available");
            completeTask(ctx);
        }
    }

    // ========================================================================
    // Sub-task Handling
    // ========================================================================

    private void handleSubTaskComplete(TaskContext ctx) {
        TaskState subState = currentSubTask.getState();
        String subDesc = currentSubTask.getDescription();

        if (subState == TaskState.FAILED) {
            log.warn("Sub-task failed: {}", subDesc);
        }

        // Determine next phase based on current phase and sub-task
        switch (phase) {
            case PREPARE:
                // Travel complete
                recordPhaseTransition(CookingPhase.COOK);
                phase = CookingPhase.COOK;
                break;

            case COOK:
                // UseItemOnObjectTask complete - now wait for interface
                recordPhaseTransition(CookingPhase.WAIT_INTERFACE);
                phase = CookingPhase.WAIT_INTERFACE;
                break;

            case DROP_OR_BANK:
                if (config.getProductHandling() == CookingSkillTaskConfig.ProductHandling.BANK_BUT_DROP_BURNT
                        && currentSubTask instanceof DropInventoryTask) {
                    // Just dropped burnt, now bank the cooked and get more raw food
                    TrainingMethod method = config.getMethod();
                    int rawFoodId = method.getSourceItemId();
                    
                    MethodLocation location = config.getLocation();
                    WorldPoint returnPos = location != null ? 
                            (location.getTrainingArea() != null ? location.getTrainingArea() : location.getExactPosition())
                            : null;
                    
                    ResupplyTask.ResupplyTaskBuilder bankBuilder = ResupplyTask.builder()
                            .depositInventory(true)
                            .withdrawItem(rawFoodId, 28);
                    
                    if (returnPos != null) {
                        bankBuilder.returnPosition(returnPos);
                    }

                    currentSubTask = bankBuilder.build();
                    return; // Don't clear currentSubTask yet
                }

                // Dropping/banking complete
                recordPhaseTransition(CookingPhase.CHECK_CONTINUE);
                phase = CookingPhase.CHECK_CONTINUE;
                break;

            default:
                break;
        }

        currentSubTask = null;
        phaseTicks = 0;
    }

    // ========================================================================
    // Phase Transition Recording
    // ========================================================================

    private void recordPhaseTransition(CookingPhase newPhase) {
        log.debug("Phase transition: {} -> {}", phase, newPhase);
        currentPhaseName = newPhase.name();
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getTaskType() {
        return "COOKING";
    }
    
    @Override
    public String getDescription() {
        TrainingMethod method = config.getMethod();
        if (method != null) {
            return "Cooking: " + method.getName();
        }
        return "CookingSkillTask";
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum CookingPhase {
        /**
         * Navigate to cooking location.
         */
        PREPARE,

        /**
         * Use raw food on fire/range.
         */
        COOK,

        /**
         * Wait for make-all interface to appear.
         */
        WAIT_INTERFACE,

        /**
         * Wait for cooking animations to complete.
         */
        WAIT_COMPLETE,

        /**
         * Check if more raw food needs cooking.
         */
        CHECK_MORE_TO_COOK,

        /**
         * Drop or bank products.
         */
        DROP_OR_BANK,

        /**
         * Check if we should continue (more raw food available).
         */
        CHECK_CONTINUE
    }
}
