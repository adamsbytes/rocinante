package com.rocinante.tasks.impl.skills.cooking;

import com.rocinante.progression.MethodLocation;
import com.rocinante.progression.TrainingMethod;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.impl.DropInventoryTask;
import com.rocinante.tasks.impl.InteractNpcTask;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Task for combined gather-and-cook training.
 *
 * <p>Workflow:
 * <ol>
 *   <li>GATHER: Fish at designated spot until inventory is full</li>
 *   <li>PROCESS: Cook all raw fish on fire/range</li>
 *   <li>DROP_OR_BANK: Drop all products or bank cooked fish</li>
 *   <li>CHECK_CONTINUE: Evaluate if training should continue</li>
 * </ol>
 *
 * <p>Example: Powerfish/cook at Barbarian Village
 * <ul>
 *   <li>Fish trout/salmon at river spots</li>
 *   <li>Cook on the permanent fire near Gunthor's hut</li>
 *   <li>Drop all cooked/burnt fish</li>
 *   <li>Repeat until target level reached</li>
 * </ul>
 *
 * <p>This task tracks XP gains for both fishing (primary) and cooking (secondary) skills.
 */
@Slf4j
public class GatherAndCookTask extends AbstractTask {

    // ========================================================================
    // Phase Definitions
    // ========================================================================

    private enum GatherCookPhase {
        /** Initial navigation to fishing spot. */
        WALK_TO_GATHER,
        /** Actively fishing - waiting for inventory to fill. */
        GATHERING,
        /** Walk to fire/range for cooking. */
        WALK_TO_COOK,
        /** Cooking all raw fish. */
        PROCESSING,
        /** Drop or bank products. */
        DROP_OR_BANK,
        /** Evaluate whether to continue training. */
        CHECK_CONTINUE
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    @Getter
    private final GatherAndCookConfig config;

    // ========================================================================
    // Execution State
    // ========================================================================

    /** Current phase in the gather-cook cycle. */
    private GatherCookPhase phase = GatherCookPhase.WALK_TO_GATHER;

    /** Active sub-task being executed. */
    @Nullable
    private Task currentSubTask;

    /** Number of complete gather-cook cycles. */
    private int cycleCount = 0;

    /** Fishing XP at start of training. */
    private int startFishingXp = -1;

    /** Cooking XP at start of training. */
    private int startCookingXp = -1;

    /** Training session start time. */
    @Nullable
    private Instant startTime;

    /** Whether session is initialized. */
    private boolean initialized = false;

    // ========================================================================
    // Burnt Fish Mappings
    // ========================================================================

    private static final Map<Integer, Integer> RAW_TO_COOKED = Map.of(
            ItemID.RAW_TROUT, ItemID.TROUT,
            ItemID.RAW_SALMON, ItemID.SALMON,
            ItemID.RAW_SHRIMPS, ItemID.SHRIMPS,
            ItemID.RAW_ANCHOVIES, ItemID.ANCHOVIES,
            ItemID.RAW_TUNA, ItemID.TUNA,
            ItemID.RAW_LOBSTER, ItemID.LOBSTER,
            ItemID.RAW_SWORDFISH, ItemID.SWORDFISH
    );

    private static final Map<Integer, Integer> COOKED_TO_BURNT = Map.of(
            ItemID.TROUT, ItemID.BURNT_FISH,
            ItemID.SALMON, ItemID.BURNT_FISH,
            ItemID.SHRIMPS, ItemID.BURNT_SHRIMP,
            ItemID.ANCHOVIES, ItemID.BURNT_FISH,
            ItemID.TUNA, ItemID.BURNT_FISH,
            ItemID.LOBSTER, ItemID.BURNT_LOBSTER,
            ItemID.SWORDFISH, ItemID.BURNT_SWORDFISH
    );

    // ========================================================================
    // Constructor
    // ========================================================================

    public GatherAndCookTask(GatherAndCookConfig config) {
        this.config = config;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        // Can always attempt gather-and-cook
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
            case WALK_TO_COOK:
                executeWalkToCook(ctx);
                break;
            case PROCESSING:
                executeProcessing(ctx);
                break;
            case DROP_OR_BANK:
                executeDropOrBank(ctx);
                break;
            case CHECK_CONTINUE:
                executeCheckContinue(ctx);
                break;
        }
    }

    @Override
    public String getDescription() {
        TrainingMethod method = config.getMethod();
        return String.format("Gather and Cook: %s (cycle %d, phase: %s)",
                method.getName(), cycleCount, phase);
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    private void initialize(TaskContext ctx) {
        Client client = ctx.getClient();
        startFishingXp = client.getSkillExperience(Skill.FISHING);
        startCookingXp = client.getSkillExperience(Skill.COOKING);
        startTime = Instant.now();
        initialized = true;

        log.info("Starting gather-and-cook training: {} (Fishing: {}, Cooking: {})",
                config.getMethod().getName(),
                client.getRealSkillLevel(Skill.FISHING),
                client.getRealSkillLevel(Skill.COOKING));
    }

    // ========================================================================
    // Target Checking
    // ========================================================================

    private boolean shouldStopTraining(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check fishing level target
        if (config.getTargetFishingLevel() > 0) {
            int currentLevel = client.getRealSkillLevel(Skill.FISHING);
            if (currentLevel >= config.getTargetFishingLevel()) {
                log.info("Reached target fishing level: {}", currentLevel);
                return true;
            }
        }

        // Check cooking level target
        if (config.getTargetCookingLevel() > 0) {
            int currentLevel = client.getRealSkillLevel(Skill.COOKING);
            if (currentLevel >= config.getTargetCookingLevel()) {
                log.info("Reached target cooking level: {}", currentLevel);
                return true;
            }
        }

        // Check fishing XP target
        if (config.getTargetFishingXp() > 0) {
            int gained = client.getSkillExperience(Skill.FISHING) - startFishingXp;
            if (gained >= config.getTargetFishingXp()) {
                log.info("Reached target fishing XP: {}", gained);
                return true;
            }
        }

        // Check cooking XP target
        if (config.getTargetCookingXp() > 0) {
            int gained = client.getSkillExperience(Skill.COOKING) - startCookingXp;
            if (gained >= config.getTargetCookingXp()) {
                log.info("Reached target cooking XP: {}", gained);
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
            log.warn("No location configured for gather-and-cook");
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
            // Close enough, start gathering
            log.debug("At fishing location, starting to gather");
            phase = GatherCookPhase.GATHERING;
            return;
        }

        // Walk to fishing spot
        currentSubTask = new WalkToTask(gatherArea)
                .withDescription("Walk to fishing spot");
    }

    private void executeGathering(TaskContext ctx) {
        TrainingMethod method = config.getMethod();
        InventoryState inventory = ctx.getInventoryState();

        // Check if inventory is full
        if (inventory.isFull()) {
            log.debug("Inventory full, switching to cooking");
            phase = GatherCookPhase.WALK_TO_COOK;
            return;
        }

        // Start fishing interaction
        if (!method.hasTargetNpcs()) {
            log.warn("GATHER_AND_PROCESS method {} has no target NPCs for fishing", method.getId());
            fail("No fishing spots configured");
            return;
        }

        List<Integer> expandedNpcs = CollectionResolver.expandNpcIds(method.getTargetNpcIds());
        String menuAction = method.getMenuAction() != null ? method.getMenuAction() : "Lure";

        InteractNpcTask fishTask = new InteractNpcTask(expandedNpcs.get(0), menuAction)
                .withAlternateIds(expandedNpcs.subList(1, expandedNpcs.size()))
                .withDescription("Fish at spot");

        // Set fishing area constraint - search within 20 tiles of training area
        if (config.getLocation() != null && config.getLocation().getTrainingArea() != null) {
            fishTask.withSearchRadius(20);
        }

        currentSubTask = fishTask;
    }

    private void executeWalkToCook(TaskContext ctx) {
        MethodLocation location = config.getLocation();

        // For Barbarian Village, fire is near the fishing spot - minimal walking
        // In the future, we could have separate cookingArea in MethodLocation
        WorldPoint cookArea = location != null ? location.getTrainingArea() : null;
        if (cookArea == null && location != null) {
            cookArea = location.getExactPosition();
        }

        if (cookArea != null) {
            PlayerState player = ctx.getPlayerState();
            int distance = player.getWorldPosition().distanceTo(cookArea);

            if (distance > 15) {
                currentSubTask = new WalkToTask(cookArea)
                        .withDescription("Walk to fire");
                return;
            }
        }

        // Close enough or no location, proceed to cooking
        phase = GatherCookPhase.PROCESSING;
    }

    private void executeProcessing(TaskContext ctx) {
        TrainingMethod method = config.getMethod();

        // Create a CookingSkillTaskConfig for the cooking portion
        // Note: CookingSkillTask internally handles finding fires and cooking
        MethodLocation location = config.getLocation();
        CookingSkillTaskConfig cookingConfig = CookingSkillTaskConfig.builder()
                .method(method)
                .locationId(location != null ? location.getId() : null)
                .productHandling(CookingSkillTaskConfig.ProductHandling.DROP_ALL) // Temporary - we handle drop/bank ourselves
                .build();

        // We need to cook until no raw fish remain, then handle drop/bank ourselves
        // For now, create a simple cooking loop
        InventoryState inventory = ctx.getInventoryState();
        int rawFoodId = method.getSourceItemId();

        if (!inventory.hasItem(rawFoodId)) {
            // No more raw food to cook - done processing
            log.debug("All food cooked, moving to drop/bank phase");
            phase = GatherCookPhase.DROP_OR_BANK;
            return;
        }

        // Start cooking with CookingSkillTask
        // It will handle finding fire, using item, make-all interface
        CookingSkillTask cookingTask = new CookingSkillTask(cookingConfig);
        currentSubTask = cookingTask;
    }

    private void executeDropOrBank(TaskContext ctx) {
        TrainingMethod method = config.getMethod();
        int cookedFoodId = method.getOutputItemId();
        int rawFoodId = method.getSourceItemId();

        // Get return position for banking
        MethodLocation location = config.getLocation();
        WorldPoint returnPos = location != null ?
                (location.getTrainingArea() != null ? location.getTrainingArea() : location.getExactPosition())
                : null;

        switch (config.getProductHandling()) {
            case DROP_ALL:
                // Drop all cooked and burnt food
                List<Integer> toDrop = new ArrayList<>();
                toDrop.add(cookedFoodId);

                // Add burnt version
                Integer burntId = COOKED_TO_BURNT.get(cookedFoodId);
                if (burntId != null) {
                    toDrop.add(burntId);
                }
                toDrop.add(ItemID.BURNT_FISH);

                currentSubTask = DropInventoryTask.forItemIds(toDrop)
                        .withDescription("Drop cooked/burnt food")
                        .withPattern(DropInventoryTask.DropPattern.COLUMN);
                break;

            case BANK_ALL:
                // Bank everything, withdraw more raw food for next cycle
                ResupplyTask.ResupplyTaskBuilder bankAllBuilder = ResupplyTask.builder()
                        .depositInventory(true)
                        .withdrawItem(rawFoodId, 28);

                if (returnPos != null) {
                    bankAllBuilder.returnPosition(returnPos);
                }

                currentSubTask = bankAllBuilder.build();
                break;

            case BANK_BUT_DROP_BURNT:
                // First drop burnt items
                List<Integer> burntToDrop = new ArrayList<>();
                for (Integer burnt : COOKED_TO_BURNT.values()) {
                    burntToDrop.add(burnt);
                }
                burntToDrop.add(ItemID.BURNT_FISH);

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

    private void executeCheckContinue(TaskContext ctx) {
        // Increment cycle count
        cycleCount++;

        log.info("Completed gather-cook cycle {}. Fishing XP: +{}, Cooking XP: +{}",
                cycleCount,
                ctx.getClient().getSkillExperience(Skill.FISHING) - startFishingXp,
                ctx.getClient().getSkillExperience(Skill.COOKING) - startCookingXp);

        // Check if we should continue
        if (shouldStopTraining(ctx)) {
            complete();
            return;
        }

        // Start next cycle
        phase = GatherCookPhase.WALK_TO_GATHER;
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
            if (phase == GatherCookPhase.GATHERING) {
                log.debug("Retrying gather phase");
                return; // Will recreate task next tick
            }
            // For other phases, propagate failure
            fail("Sub-task failed: " + currentSubTask.getDescription());
            return;
        }

        // Handle phase transitions based on current phase
        switch (phase) {
            case WALK_TO_GATHER:
                phase = GatherCookPhase.GATHERING;
                break;

            case GATHERING:
                // Check if inventory is full now
                if (ctx.getInventoryState().isFull()) {
                    phase = GatherCookPhase.WALK_TO_COOK;
                }
                // Otherwise will restart gathering next tick
                break;

            case WALK_TO_COOK:
                phase = GatherCookPhase.PROCESSING;
                break;

            case PROCESSING:
                // CookingSkillTask completed - check if any raw food left
                int rawFoodId = config.getMethod().getSourceItemId();
                if (ctx.getInventoryState().hasItem(rawFoodId)) {
                    // Still have raw food - continue cooking
                    // (CookingSkillTask might have stopped for some reason)
                    return; // Will restart cooking next tick
                }
                phase = GatherCookPhase.DROP_OR_BANK;
                break;

            case DROP_OR_BANK:
                if (config.getProductHandling() == CookingSkillTaskConfig.ProductHandling.BANK_BUT_DROP_BURNT
                        && currentSubTask instanceof DropInventoryTask) {
                    // Just dropped burnt, now bank
                    int rawId = config.getMethod().getSourceItemId();
                    WorldPoint returnPos = getReturnPosition();

                    ResupplyTask.ResupplyTaskBuilder bankBuilder = ResupplyTask.builder()
                            .depositInventory(true)
                            .withdrawItem(rawId, 28);

                    if (returnPos != null) {
                        bankBuilder.returnPosition(returnPos);
                    }

                    currentSubTask = bankBuilder.build();
                    return; // Don't clear currentSubTask
                }

                phase = GatherCookPhase.CHECK_CONTINUE;
                break;

            case CHECK_CONTINUE:
                // Nothing to do - executeCheckContinue handles it
                break;
        }
    }

    @Nullable
    private WorldPoint getReturnPosition() {
        MethodLocation location = config.getLocation();
        if (location == null) {
            return null;
        }
        return location.getTrainingArea() != null ? location.getTrainingArea() : location.getExactPosition();
    }
}
