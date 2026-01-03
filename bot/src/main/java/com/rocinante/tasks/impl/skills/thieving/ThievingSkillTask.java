package com.rocinante.tasks.impl.skills.thieving;

import com.rocinante.combat.StunHandler;
import com.rocinante.progression.MethodType;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.impl.DropInventoryTask;
import com.rocinante.tasks.impl.ResupplyTask;
import com.rocinante.tasks.impl.WalkToTask;
import com.rocinante.util.ItemCollections;
import com.rocinante.util.NpcCollections;
import com.rocinante.util.ObjectCollections;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * High-level orchestrator for Thieving skill training.
 *
 * <p>ThievingSkillTask coordinates all aspects of a thieving training session:
 * <ul>
 *   <li>Travel to training location</li>
 *   <li>Execute appropriate thieving method (pickpocket or stall)</li>
 *   <li>Handle food/supplies management</li>
 *   <li>Banking when inventory is full or supplies are low</li>
 *   <li>Drop items if configured for power training</li>
 * </ul>
 *
 * <p>Supported training methods (via {@link ThievingMethod}):
 * <ul>
 *   <li>{@link ThievingMethod#PICKPOCKET} - Steal from NPCs using {@link PickpocketTask}</li>
 *   <li>{@link ThievingMethod#STALL} - Steal from market stalls using {@link StallThievingTask}</li>
 * </ul>
 *
 * <p>Pre-configured training methods:
 * <ul>
 *   <li>Men/Women in Lumbridge (level 1)</li>
 *   <li>Bakery stalls in East Ardougne (level 5)</li>
 *   <li>Fruit stalls in Hosidius (level 25)</li>
 *   <li>Master Farmers (level 38)</li>
 *   <li>Knights of Ardougne (level 55)</li>
 *   <li>Ardy Cake Stall Safespot (special)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Basic man pickpocketing in Lumbridge
 * ThievingSkillTask task = ThievingSkillTask.men()
 *     .withTargetLevel(10);
 *
 * // Knight pickpocketing with food management
 * ThievingSkillTask knights = ThievingSkillTask.ardyKnights()
 *     .withFoodIds(List.of(ItemID.WINE))
 *     .withDodgyNecklaces(10)
 *     .withTargetLevel(70);
 *
 * // Ardy cake stall safespot (low-level food gathering + thieving XP)
 * ThievingSkillTask cakes = ThievingSkillTask.ardyCakeStallSafespot()
 *     .withTargetCount(1000);  // Get 1000 cakes
 *
 * // Custom configuration
 * ThievingSkillTask custom = new ThievingSkillTask(ThievingMethod.PICKPOCKET)
 *     .withTargetNpcs(NpcCollections.MASTER_FARMERS)
 *     .withLocation(new WorldPoint(3086, 3249, 0))  // Draynor
 *     .withTargetLevel(50);
 * }</pre>
 *
 * @see PickpocketTask for NPC pickpocketing
 * @see StallThievingTask for stall stealing
 * @see StunHandler for stun detection
 * @see DodgyNecklaceTracker for necklace management
 */
@Slf4j
public class ThievingSkillTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Minimum food count before banking.
     */
    private static final int MIN_FOOD_BEFORE_BANK = 3;

    /**
     * Minimum necklace count before banking (if using dodgy necklaces).
     */
    private static final int MIN_NECKLACES_BEFORE_BANK = 2;

    // ========================================================================
    // Pre-configured Locations
    // ========================================================================

    /**
     * Lumbridge - Men/Women.
     */
    public static final WorldPoint LUMBRIDGE_MEN = new WorldPoint(3222, 3219, 0);

    /**
     * Varrock - Men/Women in castle.
     */
    public static final WorldPoint VARROCK_MEN = new WorldPoint(3212, 3476, 0);

    /**
     * Draynor Village - Master Farmer.
     */
    public static final WorldPoint DRAYNOR_MASTER_FARMER = new WorldPoint(3086, 3249, 0);

    /**
     * East Ardougne market square - Knights.
     */
    public static final WorldPoint ARDY_KNIGHTS = new WorldPoint(2654, 3296, 0);

    /**
     * East Ardougne - Bakery stalls.
     */
    public static final WorldPoint ARDY_BAKERY = new WorldPoint(2669, 3310, 0);

    /**
     * East Ardougne - Cake stall safespot.
     */
    public static final WorldPoint ARDY_CAKE_SAFESPOT = new WorldPoint(2669, 3310, 0);

    /**
     * East Ardougne - Cake stall location.
     */
    public static final WorldPoint ARDY_CAKE_STALL = new WorldPoint(2669, 3311, 0);

    /**
     * Hosidius - Fruit stalls.
     */
    public static final WorldPoint HOSIDIUS_FRUIT_STALLS = new WorldPoint(1800, 3608, 0);

    /**
     * Rellekka - Fish stalls.
     */
    public static final WorldPoint RELLEKKA_FISH_STALLS = new WorldPoint(2649, 3677, 0);

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The thieving method to use.
     */
    @Getter
    private final ThievingMethod method;

    /**
     * Target NPC IDs for pickpocketing.
     */
    @Getter
    private List<Integer> targetNpcIds = new ArrayList<>();

    /**
     * Target stall IDs for stall thieving.
     */
    @Getter
    private List<Integer> targetStallIds = new ArrayList<>();

    /**
     * Training location.
     */
    @Getter
    @Setter
    private WorldPoint trainingLocation;

    /**
     * Optional safespot tile for stall thieving.
     */
    @Getter
    @Setter
    private WorldPoint safespot;

    /**
     * Radius for NPC/stall selection.
     */
    @Getter
    @Setter
    private int searchRadius = 15;

    /**
     * Target thieving level to reach.
     */
    @Getter
    @Setter
    private int targetLevel = -1;

    /**
     * Target successful theft count.
     */
    @Getter
    @Setter
    private int targetCount = -1;

    /**
     * Food item IDs.
     */
    @Getter
    private List<Integer> foodIds = new ArrayList<>(ItemCollections.FOOD);

    /**
     * Food count to withdraw from bank.
     */
    @Getter
    @Setter
    private int foodWithdrawCount = 10;

    /**
     * Minimum HP before eating.
     */
    @Getter
    @Setter
    private int minHp = 10;

    /**
     * Whether to use dodgy necklaces.
     */
    @Getter
    @Setter
    private boolean useDodgyNecklaces = false;

    /**
     * Number of dodgy necklaces to withdraw.
     */
    @Getter
    @Setter
    private int necklaceWithdrawCount = 5;

    /**
     * Whether to bank when full (vs drop).
     */
    @Getter
    @Setter
    private boolean bankWhenFull = true;

    /**
     * Item IDs to drop if power training.
     */
    @Getter
    private List<Integer> dropItemIds = new ArrayList<>();

    /**
     * Whether to drop food as loot from stalls (cakes, etc).
     */
    @Getter
    @Setter
    private boolean dropStallLoot = false;

    /**
     * Bank location override.
     */
    @Getter
    @Setter
    private WorldPoint bankLocation;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current phase.
     */
    private ThievingPhase phase = ThievingPhase.CHECK_REQUIREMENTS;

    /**
     * Active sub-task.
     */
    private Task currentSubTask;

    /**
     * Starting XP.
     */
    private int startXp = -1;

    /**
     * Total thefts completed.
     */
    @Getter
    private int totalThefts = 0;

    /**
     * Whether we've traveled to location.
     */
    private boolean atLocation = false;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a thieving skill task with the specified method.
     *
     * @param method the thieving method
     */
    public ThievingSkillTask(ThievingMethod method) {
        this.method = method;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a task for pickpocketing men/women in Lumbridge.
     */
    public static ThievingSkillTask men() {
        ThievingSkillTask task = new ThievingSkillTask(ThievingMethod.PICKPOCKET);
        task.targetNpcIds.addAll(NpcCollections.MEN);
        task.targetNpcIds.addAll(NpcCollections.WOMEN);
        task.trainingLocation = LUMBRIDGE_MEN;
        task.useDodgyNecklaces = false;
        return task;
    }

    /**
     * Create a task for pickpocketing farmers.
     */
    public static ThievingSkillTask farmers() {
        ThievingSkillTask task = new ThievingSkillTask(ThievingMethod.PICKPOCKET);
        task.targetNpcIds.addAll(NpcCollections.FARMERS_PICKPOCKET);
        task.trainingLocation = DRAYNOR_MASTER_FARMER;
        return task;
    }

    /**
     * Create a task for pickpocketing master farmers.
     */
    public static ThievingSkillTask masterFarmers() {
        ThievingSkillTask task = new ThievingSkillTask(ThievingMethod.PICKPOCKET);
        task.targetNpcIds.addAll(NpcCollections.MASTER_FARMERS);
        task.trainingLocation = DRAYNOR_MASTER_FARMER;
        return task;
    }

    /**
     * Create a task for pickpocketing guards.
     */
    public static ThievingSkillTask guards() {
        ThievingSkillTask task = new ThievingSkillTask(ThievingMethod.PICKPOCKET);
        task.targetNpcIds.addAll(NpcCollections.GUARDS_PICKPOCKET);
        task.trainingLocation = new WorldPoint(3215, 3435, 0);  // Varrock palace
        task.useDodgyNecklaces = true;
        return task;
    }

    /**
     * Create a task for pickpocketing Knights of Ardougne.
     */
    public static ThievingSkillTask ardyKnights() {
        ThievingSkillTask task = new ThievingSkillTask(ThievingMethod.PICKPOCKET);
        task.targetNpcIds.addAll(NpcCollections.KNIGHTS_OF_ARDOUGNE);
        task.trainingLocation = ARDY_KNIGHTS;
        task.searchRadius = 10;
        task.useDodgyNecklaces = true;
        return task;
    }

    /**
     * Create a task for stealing from bakery stalls.
     */
    public static ThievingSkillTask bakeryStalls() {
        ThievingSkillTask task = new ThievingSkillTask(ThievingMethod.STALL);
        task.targetStallIds.addAll(ObjectCollections.BAKERY_STALLS);
        task.trainingLocation = ARDY_BAKERY;
        task.dropStallLoot = true;
        return task;
    }

    /**
     * Create a task for the Ardy Cake Stall Safespot method.
     * This is a specific training method where you stand on a safespot tile
     * and steal cakes without guards catching you.
     */
    public static ThievingSkillTask ardyCakeStallSafespot() {
        ThievingSkillTask task = new ThievingSkillTask(ThievingMethod.STALL);
        task.targetStallIds.addAll(ObjectCollections.BAKERY_STALLS);
        task.trainingLocation = ARDY_CAKE_SAFESPOT;
        task.safespot = ARDY_CAKE_SAFESPOT;
        task.searchRadius = 3;
        task.dropStallLoot = false;  // Keep cakes for food
        task.bankWhenFull = true;
        return task;
    }

    /**
     * Create a task for stealing from fruit stalls in Hosidius.
     */
    public static ThievingSkillTask hosidiusFruitStalls() {
        ThievingSkillTask task = new ThievingSkillTask(ThievingMethod.STALL);
        task.targetStallIds.addAll(ObjectCollections.FRUIT_STALLS);
        task.trainingLocation = HOSIDIUS_FRUIT_STALLS;
        task.dropStallLoot = true;
        return task;
    }

    /**
     * Create a task for stealing from silk stalls.
     */
    public static ThievingSkillTask silkStalls() {
        ThievingSkillTask task = new ThievingSkillTask(ThievingMethod.STALL);
        task.targetStallIds.addAll(ObjectCollections.SILK_STALLS);
        task.trainingLocation = ARDY_BAKERY;  // Same area
        task.bankWhenFull = true;  // Silk can be sold
        return task;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set target NPCs (builder-style).
     */
    public ThievingSkillTask withTargetNpcs(Collection<Integer> npcIds) {
        this.targetNpcIds = new ArrayList<>(npcIds);
        return this;
    }

    /**
     * Set target stalls (builder-style).
     */
    public ThievingSkillTask withTargetStalls(Collection<Integer> stallIds) {
        this.targetStallIds = new ArrayList<>(stallIds);
        return this;
    }

    /**
     * Set training location (builder-style).
     */
    public ThievingSkillTask withLocation(WorldPoint location) {
        this.trainingLocation = location;
        return this;
    }

    /**
     * Set safespot (builder-style).
     */
    public ThievingSkillTask withSafespot(WorldPoint safespot) {
        this.safespot = safespot;
        return this;
    }

    /**
     * Set target level (builder-style).
     */
    public ThievingSkillTask withTargetLevel(int level) {
        this.targetLevel = level;
        return this;
    }

    /**
     * Set target count (builder-style).
     */
    public ThievingSkillTask withTargetCount(int count) {
        this.targetCount = count;
        return this;
    }

    /**
     * Set food IDs (builder-style).
     */
    public ThievingSkillTask withFoodIds(Collection<Integer> foodIds) {
        this.foodIds = new ArrayList<>(foodIds);
        return this;
    }

    /**
     * Set food withdraw count (builder-style).
     */
    public ThievingSkillTask withFoodCount(int count) {
        this.foodWithdrawCount = count;
        return this;
    }

    /**
     * Set minimum HP (builder-style).
     */
    public ThievingSkillTask withMinHp(int hp) {
        this.minHp = hp;
        return this;
    }

    /**
     * Set dodgy necklace usage (builder-style).
     */
    public ThievingSkillTask withDodgyNecklaces(int count) {
        this.useDodgyNecklaces = true;
        this.necklaceWithdrawCount = count;
        return this;
    }

    /**
     * Set banking vs dropping behavior (builder-style).
     */
    public ThievingSkillTask withBankWhenFull(boolean bank) {
        this.bankWhenFull = bank;
        return this;
    }

    /**
     * Set items to drop when power training (builder-style).
     */
    public ThievingSkillTask withDropItems(Collection<Integer> itemIds) {
        this.dropItemIds = new ArrayList<>(itemIds);
        return this;
    }

    /**
     * Set search radius (builder-style).
     */
    public ThievingSkillTask withSearchRadius(int radius) {
        this.searchRadius = radius;
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

        // Must have a target
        if (method == ThievingMethod.PICKPOCKET && targetNpcIds.isEmpty()) {
            log.error("No target NPCs configured for pickpocketing");
            return false;
        }
        if (method == ThievingMethod.STALL && targetStallIds.isEmpty()) {
            log.error("No target stalls configured for stall thieving");
            return false;
        }

        // Must have a location
        if (trainingLocation == null) {
            log.error("No training location configured");
            return false;
        }

        return true;
    }

    @Override
    protected void resetImpl() {
        phase = ThievingPhase.CHECK_REQUIREMENTS;
        currentSubTask = null;
        startXp = -1;
        totalThefts = 0;
        atLocation = false;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        // Check for target level reached
        if (targetLevel > 0) {
            int currentLevel = ctx.getClient().getRealSkillLevel(Skill.THIEVING);
            if (currentLevel >= targetLevel) {
                log.info("Target thieving level {} reached!", targetLevel);
                logStatistics(ctx);
                complete();
                return;
            }
        }

        // Record starting XP
        if (startXp < 0) {
            startXp = ctx.getClient().getSkillExperience(Skill.THIEVING);
        }

        // Handle sub-task execution
        if (currentSubTask != null) {
            if (!currentSubTask.getState().isTerminal()) {
                currentSubTask.execute(ctx);
                return;
            } else if (currentSubTask.getState() == TaskState.COMPLETED) {
                handleSubTaskComplete(ctx);
                currentSubTask = null;
            } else if (currentSubTask.getState() == TaskState.FAILED) {
                log.warn("Sub-task failed: {}", currentSubTask.getDescription());
                currentSubTask = null;
                // Try to recover
            }
        }

        switch (phase) {
            case CHECK_REQUIREMENTS:
                executeCheckRequirements(ctx);
                break;
            case TRAVEL_TO_LOCATION:
                executeTravelToLocation(ctx);
                break;
            case CHECK_SUPPLIES:
                executeCheckSupplies(ctx);
                break;
            case BANK:
                executeBank(ctx);
                break;
            case TRAIN:
                executeTrain(ctx);
                break;
            case DROP_LOOT:
                executeDropLoot(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Check Requirements
    // ========================================================================

    private void executeCheckRequirements(TaskContext ctx) {
        // Could check level requirements here
        // For now, just proceed
        recordPhaseTransition(ThievingPhase.TRAVEL_TO_LOCATION);
        phase = ThievingPhase.TRAVEL_TO_LOCATION;
    }

    // ========================================================================
    // Phase: Travel to Location
    // ========================================================================

    private void executeTravelToLocation(TaskContext ctx) {
        if (atLocation) {
            recordPhaseTransition(ThievingPhase.CHECK_SUPPLIES);
            phase = ThievingPhase.CHECK_SUPPLIES;
            return;
        }

        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();
        int distance = playerPos.distanceTo(trainingLocation);

        if (distance <= 10) {
            atLocation = true;
            log.debug("Arrived at training location");
            recordPhaseTransition(ThievingPhase.CHECK_SUPPLIES);
            phase = ThievingPhase.CHECK_SUPPLIES;
            return;
        }

        log.debug("Traveling to training location (distance: {})", distance);
        currentSubTask = new WalkToTask(trainingLocation);
        currentSubTask.execute(ctx);
    }

    // ========================================================================
    // Phase: Check Supplies
    // ========================================================================

    private void executeCheckSupplies(TaskContext ctx) {
        InventoryState inventory = ctx.getInventoryState();

        // For pickpocketing, check food and necklaces
        if (method == ThievingMethod.PICKPOCKET) {
            int foodCount = countFoodInInventory(inventory);
            int necklaceCount = inventory.countItem(DodgyNecklaceTracker.DODGY_NECKLACE_ID);

            boolean needFood = foodCount < MIN_FOOD_BEFORE_BANK;
            boolean needNecklaces = useDodgyNecklaces && necklaceCount < MIN_NECKLACES_BEFORE_BANK;

            if (needFood || needNecklaces) {
                log.debug("Need supplies: food={} (need: {}), necklaces={} (need: {})",
                        foodCount, needFood, necklaceCount, needNecklaces);
                recordPhaseTransition(ThievingPhase.BANK);
                phase = ThievingPhase.BANK;
                return;
            }
        }

        // For stall thieving, check inventory space
        if (method == ThievingMethod.STALL) {
            if (inventory.isFull()) {
                if (bankWhenFull) {
                    recordPhaseTransition(ThievingPhase.BANK);
                    phase = ThievingPhase.BANK;
                } else {
                    recordPhaseTransition(ThievingPhase.DROP_LOOT);
                    phase = ThievingPhase.DROP_LOOT;
                }
                return;
            }
        }

        recordPhaseTransition(ThievingPhase.TRAIN);
        phase = ThievingPhase.TRAIN;
    }

    // ========================================================================
    // Phase: Bank
    // ========================================================================

    private void executeBank(TaskContext ctx) {
        ResupplyTask.ResupplyTaskBuilder builder = ResupplyTask.builder()
                .depositInventory(true)
                .returnPosition(trainingLocation);

        // Withdraw food for pickpocketing
        if (method == ThievingMethod.PICKPOCKET && foodWithdrawCount > 0 && !foodIds.isEmpty()) {
            builder.withdrawItem(foodIds.get(0), foodWithdrawCount);
        }

        // Withdraw dodgy necklaces if needed
        if (useDodgyNecklaces && necklaceWithdrawCount > 0) {
            builder.withdrawItem(DodgyNecklaceTracker.DODGY_NECKLACE_ID, necklaceWithdrawCount);
        }

        currentSubTask = builder.build();
        currentSubTask.execute(ctx);
    }

    // ========================================================================
    // Phase: Train
    // ========================================================================

    private void executeTrain(TaskContext ctx) {
        Task trainingTask;

        if (method == ThievingMethod.PICKPOCKET) {
            PickpocketTask pickpocket = new PickpocketTask(targetNpcIds)
                    .withLocation(trainingLocation, searchRadius)
                    .withFoodIds(foodIds)
                    .withMinHp(minHp)
                    .withAutoEquipNecklace(useDodgyNecklaces)
                    .withSearchRadius(searchRadius);

            // Set target count if specified
            if (targetCount > 0) {
                pickpocket.withTargetCount(targetCount - totalThefts);
            }

            trainingTask = pickpocket;
        } else {
            StallThievingTask stallTask = new StallThievingTask(targetStallIds)
                    .withSearchRadius(searchRadius);

            if (safespot != null) {
                stallTask.withSafespot(safespot);
            }

            // Set drop behavior
            if (dropStallLoot && !dropItemIds.isEmpty()) {
                stallTask.withDropWhenFull(true, dropItemIds);
            }

            // Set target count if specified
            if (targetCount > 0) {
                stallTask.withTargetCount(targetCount - totalThefts);
            }

            trainingTask = stallTask;
        }

        currentSubTask = trainingTask;
        currentSubTask.execute(ctx);
    }

    // ========================================================================
    // Phase: Drop Loot
    // ========================================================================

    private void executeDropLoot(TaskContext ctx) {
        InventoryState inventory = ctx.getInventoryState();
        
        // Build list of items to drop
        List<Integer> itemsToDrop = new ArrayList<>();
        
        if (!dropItemIds.isEmpty()) {
            // Drop specific items
            itemsToDrop.addAll(dropItemIds);
        } else {
            // Drop everything except food and necklaces
            Set<Integer> keepItems = new HashSet<>(foodIds);
            if (useDodgyNecklaces) {
                keepItems.add(DodgyNecklaceTracker.DODGY_NECKLACE_ID);
            }
            
            // Get all unique item IDs in inventory that aren't in keep list
            for (int slot = 0; slot < 28; slot++) {
                Optional<net.runelite.api.Item> itemOpt = inventory.getItemInSlot(slot);
                if (itemOpt.isPresent()) {
                    int itemId = itemOpt.get().getId();
                    if (!keepItems.contains(itemId) && !itemsToDrop.contains(itemId)) {
                        itemsToDrop.add(itemId);
                    }
                }
            }
        }
        
        if (itemsToDrop.isEmpty()) {
            // Nothing to drop, go back to training
            recordPhaseTransition(ThievingPhase.TRAIN);
            phase = ThievingPhase.TRAIN;
            return;
        }

        currentSubTask = new DropInventoryTask(itemsToDrop);
        currentSubTask.execute(ctx);
    }

    // ========================================================================
    // Sub-task Completion
    // ========================================================================

    private void handleSubTaskComplete(TaskContext ctx) {
        switch (phase) {
            case TRAVEL_TO_LOCATION:
                atLocation = true;
                recordPhaseTransition(ThievingPhase.CHECK_SUPPLIES);
                phase = ThievingPhase.CHECK_SUPPLIES;
                break;

            case BANK:
                // Return to training location
                atLocation = false;
                recordPhaseTransition(ThievingPhase.TRAVEL_TO_LOCATION);
                phase = ThievingPhase.TRAVEL_TO_LOCATION;
                break;

            case TRAIN:
                // Training sub-task completed (inventory full or target reached)
                if (currentSubTask instanceof PickpocketTask) {
                    totalThefts += ((PickpocketTask) currentSubTask).getSuccessfulPickpockets();
                } else if (currentSubTask instanceof StallThievingTask) {
                    totalThefts += ((StallThievingTask) currentSubTask).getSuccessfulSteals();
                }

                // Check if done
                if (targetCount > 0 && totalThefts >= targetCount) {
                    log.info("Target theft count {} reached!", targetCount);
                    logStatistics(ctx);
                    complete();
                    return;
                }

                recordPhaseTransition(ThievingPhase.CHECK_SUPPLIES);
                phase = ThievingPhase.CHECK_SUPPLIES;
                break;

            case DROP_LOOT:
                recordPhaseTransition(ThievingPhase.TRAIN);
                phase = ThievingPhase.TRAIN;
                break;

            default:
                phase = ThievingPhase.CHECK_SUPPLIES;
                break;
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Count food items in inventory.
     */
    private int countFoodInInventory(InventoryState inventory) {
        int count = 0;
        for (int foodId : foodIds) {
            count += inventory.countItem(foodId);
        }
        return count;
    }

    /**
     * Log statistics.
     */
    private void logStatistics(TaskContext ctx) {
        int currentXp = ctx.getClient().getSkillExperience(Skill.THIEVING);
        int xpGained = currentXp - startXp;
        int currentLevel = ctx.getClient().getRealSkillLevel(Skill.THIEVING);

        log.info("=== Thieving Session Complete ===");
        log.info("Method: {}", method);
        log.info("Total thefts: {}", totalThefts);
        log.info("XP gained: {}", xpGained);
        log.info("Final level: {}", currentLevel);
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getTaskType() {
        return "THIEVING";
    }
    
    @Override
    public String getDescription() {
        return String.format("Thieving[%s, thefts=%d, phase=%s]",
                method, totalThefts, phase);
    }

    // ========================================================================
    // Enums
    // ========================================================================

    /**
     * Thieving training methods.
     */
    public enum ThievingMethod {
        /** Pickpocket NPCs */
        PICKPOCKET,
        /** Steal from market stalls */
        STALL
    }

    /**
     * Execution phases.
     */
    private enum ThievingPhase {
        CHECK_REQUIREMENTS,
        TRAVEL_TO_LOCATION,
        CHECK_SUPPLIES,
        BANK,
        TRAIN,
        DROP_LOOT
    }
}

