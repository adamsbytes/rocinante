package com.rocinante.tasks.minigame.wintertodt;

import com.rocinante.combat.FoodManager;
import com.rocinante.input.InventoryClickHelper;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.impl.InteractObjectTask;
import com.rocinante.tasks.impl.WalkToTask;
import com.rocinante.tasks.minigame.MinigamePhase;
import com.rocinante.tasks.minigame.MinigameTask;
import com.rocinante.state.GameObjectSnapshot;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Task for automating Wintertodt minigame training.
 *
 * <p>Implements the full Wintertodt gameplay loop:
 * <ul>
 *   <li>Traveling to Wintertodt area</li>
 *   <li>Waiting for round start</li>
 *   <li>Chopping bruma roots</li>
 *   <li>Optional: Fletching roots into kindling</li>
 *   <li>Feeding/fixing/lighting brazier</li>
 *   <li>HP management via FoodManager</li>
 *   <li>Collecting supply crate rewards</li>
 * </ul>
 *
 * <p>Extends {@link MinigameTask} for common minigame patterns.
 *
 * <p>Example usage:
 * <pre>{@code
 * WintertodtConfig config = WintertodtConfig.builder()
 *     .targetLevel(75)
 *     .strategy(WintertodtStrategy.FLETCH)
 *     .build();
 *
 * WintertodtTask task = new WintertodtTask(config);
 * }</pre>
 *
 * @see WintertodtConfig
 * @see MinigameTask
 */
@Slf4j
public class WintertodtTask extends MinigameTask {

    // ========================================================================
    // RuneLite Item/Object IDs
    // ========================================================================

    /**
     * Bruma root item ID from RuneLite's ItemID.WINT_BRUMA_ROOT.
     */
    private static final int BRUMA_ROOT_ID = 20695;

    /**
     * Bruma kindling item ID from RuneLite's ItemID.WINT_BRUMA_KINDLING.
     */
    private static final int BRUMA_KINDLING_ID = 20696;

    /**
     * Knife item ID for fletching.
     */
    private static final int KNIFE_ID = 946;

    /**
     * Tinderbox item ID for lighting brazier.
     */
    private static final int TINDERBOX_ID = 590;

    /**
     * Hammer item ID for fixing brazier.
     */
    private static final int HAMMER_ID = 2347;

    /**
     * Bruma root tree object ID.
     */
    private static final int BRUMA_ROOT_TREE_ID = 29311;

    /**
     * Lit brazier object ID.
     */
    private static final int BRAZIER_LIT_ID = 29314;

    /**
     * Unlit brazier object ID.
     */
    private static final int BRAZIER_UNLIT_ID = 29312;

    /**
     * Broken brazier object ID.
     */
    private static final int BRAZIER_BROKEN_ID = 29313;

    /**
     * Supply crate object ID (rewards).
     */
    private static final int SUPPLY_CRATE_ID = 29322;

    // ========================================================================
    // Animation IDs (from RuneLite AnimationID)
    // ========================================================================

    /**
     * Fletching animation.
     */
    private static final int ANIMATION_FLETCHING = 1248;

    /**
     * Feeding brazier animation (pickup table).
     */
    private static final int ANIMATION_FEEDING = 832;

    /**
     * Lighting brazier animation (create fire).
     */
    private static final int ANIMATION_LIGHTING = 733;

    /**
     * Fixing brazier animation (POH build).
     */
    private static final int ANIMATION_FIXING = 3676;

    // ========================================================================
    // Varbit IDs
    // ========================================================================

    /**
     * Wintertodt timer varbit.
     * Value indicates time until round starts (decreasing) or 0 when active.
     */
    private static final int VARBIT_TIMER = 7980;

    /**
     * Wintertodt warmth varbit (player warmth level).
     */
    private static final int VARBIT_WARMTH = 7978;

    // ========================================================================
    // Configuration
    // ========================================================================

    @Getter
    private final WintertodtConfig wintertodtConfig;

    // ========================================================================
    // State
    // ========================================================================

    /**
     * Current activity being performed.
     */
    @Getter
    private WintertodtActivity currentActivity = WintertodtActivity.IDLE;

    /**
     * Selected brazier for this session.
     */
    private BrazierLocation activeBrazier;

    /**
     * Points accumulated this round.
     */
    @Getter
    private int roundPoints = 0;

    /**
     * Total points accumulated across all rounds.
     */
    @Getter
    private int totalPoints = 0;

    /**
     * Number of bruma roots in inventory.
     */
    private int rootCount = 0;

    /**
     * Number of kindling in inventory.
     */
    private int kindlingCount = 0;

    /**
     * Last activity timestamp for timeout detection.
     */
    private Instant lastActivityTime;

    /**
     * Activity timeout duration.
     */
    private static final Duration ACTIVITY_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Ticks spent idle in current activity.
     */
    private int idleTicks = 0;

    /**
     * Active sub-task for object interactions.
     */
    private Task activeSubTask;

    /**
     * Whether an inventory click is pending.
     */
    private boolean clickPending = false;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a Wintertodt task with the specified configuration.
     *
     * @param config the Wintertodt configuration
     */
    public WintertodtTask(WintertodtConfig config) {
        super(config);
        this.wintertodtConfig = config;
    }

    // ========================================================================
    // Task Requirements
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (!super.canExecute(ctx)) {
            return false;
        }

        // Check warm clothing requirement
        if (wintertodtConfig.isEnforceWarmClothing()) {
            int warmItemCount = countWarmClothing(ctx);
            if (warmItemCount < wintertodtConfig.getRequiredWarmItems()) {
                log.warn("Wintertodt requires {} warm clothing items, but only {} equipped. " +
                        "Warm clothing reduces damage significantly.",
                        wintertodtConfig.getRequiredWarmItems(), warmItemCount);
                return false;
            }
        }

        // Check for required items (tinderbox, knife for fletch strategy, hammer for fixing)
        if (!ctx.getInventoryState().hasItem(TINDERBOX_ID)) {
            log.warn("Wintertodt requires a tinderbox");
            return false;
        }

        if (wintertodtConfig.getStrategy() == WintertodtStrategy.FLETCH &&
            !ctx.getInventoryState().hasItem(KNIFE_ID)) {
            log.warn("Fletch strategy requires a knife");
            return false;
        }

        return true;
    }

    /**
     * Count the number of warm clothing items equipped.
     *
     * @param ctx the task context
     * @return count of warm items equipped
     */
    private int countWarmClothing(TaskContext ctx) {
        int count = 0;
        
        // Check equipped items via equipment state
        EquipmentState equipment = ctx.getEquipmentState();
        if (equipment != null) {
            Set<Integer> equippedIds = equipment.getEquippedIds();
            for (int itemId : equippedIds) {
                if (WintertodtConfig.WARM_CLOTHING.contains(itemId)) {
                    count++;
                }
            }
        }
        
        // Also count bruma torch if in inventory (it provides warmth when held)
        InventoryState inventory = ctx.getInventoryState();
        if (inventory != null && inventory.hasItem(20720)) { // BRUMA_TORCH
            count++;
        }
        
        log.debug("Warm clothing count: {}", count);
        return count;
    }

    // ========================================================================
    // MinigameTask Implementation
    // ========================================================================

    @Override
    protected Skill getTrainedSkill() {
        return Skill.FIREMAKING;
    }

    @Override
    protected void executeWaitingPhase(TaskContext ctx) {
        // Update inventory counts
        updateInventoryCounts(ctx);

        // Check if round is about to start
        int timerValue = getTimerValue(ctx);
        if (timerValue > 0 && timerValue <= 5) {
            log.debug("Round starting soon (timer={}), preparing...", timerValue);
            // Ensure we're at the correct brazier
            ensureAtBrazier(ctx);
        }

        idleTicks++;
        if (idleTicks > MAX_PHASE_WAIT_TICKS * 2) {
            log.debug("Waiting for round... (ticks={})", idleTicks);
            idleTicks = 0;
        }
    }

    @Override
    protected void executeActivePhase(TaskContext ctx) {
        // Handle active sub-task first
        if (activeSubTask != null) {
            activeSubTask.execute(ctx);
            if (activeSubTask.getState().isTerminal()) {
                activeSubTask = null;
            }
            return;
        }

        // Skip if click pending
        if (clickPending) {
            return;
        }

        // Update state
        updateInventoryCounts(ctx);
        updateActivityFromAnimation(ctx);
        checkActivityTimeout();

        // Check if we should eat
        if (shouldEatFood(ctx)) {
            handleEating(ctx);
            return;
        }

        // Decide next action based on current state
        decideNextAction(ctx);
    }

    @Override
    protected void executeRewardsPhase(TaskContext ctx) {
        // Look for supply crate
        WorldState world = ctx.getWorldState();
        Optional<GameObjectSnapshot> crate = world.getNearbyObjects().stream()
                .filter(obj -> obj.getId() == SUPPLY_CRATE_ID)
                .findFirst();

        if (crate.isPresent()) {
            if (activeSubTask == null) {
                activeSubTask = new InteractObjectTask(SUPPLY_CRATE_ID, "Search")
                        .withDescription("Collect supply crate");
                log.info("Collecting supply crate");
            }

            activeSubTask.execute(ctx);
            if (activeSubTask.getState().isTerminal()) {
                activeSubTask = null;
                // Reset round points after collection
                totalPoints += roundPoints;
                roundPoints = 0;
            }
        } else {
            // No crate found, transition to waiting
            transitionToPhase(MinigamePhase.WAITING);
        }
    }

    @Override
    protected boolean isRoundActive(TaskContext ctx) {
        int timerValue = getTimerValue(ctx);
        // Timer is 0 during active round, positive when waiting
        return timerValue == 0 && isInMinigameArea(ctx);
    }

    @Override
    protected boolean shouldCollectRewards(TaskContext ctx) {
        // Check if we got enough points for reward
        return roundPoints >= WintertodtConfig.MIN_POINTS_FOR_REWARD;
    }

    @Override
    protected void onRoundStart(TaskContext ctx) {
        super.onRoundStart(ctx);
        roundPoints = 0;
        currentActivity = WintertodtActivity.IDLE;
        idleTicks = 0;

        // Select brazier if not already set
        if (activeBrazier == null) {
            activeBrazier = wintertodtConfig.getPreferredBrazier();
            if (activeBrazier == null) {
                activeBrazier = BrazierLocation.nearest(ctx.getPlayerState().getWorldPosition());
            }
        }

        log.info("Wintertodt round started. Using {} brazier", activeBrazier);
    }

    @Override
    protected void onRoundEnd(TaskContext ctx) {
        super.onRoundEnd(ctx);
        log.info("Round ended with {} points", roundPoints);
        currentActivity = WintertodtActivity.IDLE;
    }

    // ========================================================================
    // Action Decision Logic
    // ========================================================================

    /**
     * Decide what action to take based on current state.
     */
    private void decideNextAction(TaskContext ctx) {
        // If we're already doing something, continue
        if (currentActivity != WintertodtActivity.IDLE) {
            return;
        }

        // Priority 1: Fix broken brazier
        if (wintertodtConfig.isFixBraziers() && isBrazierBroken(ctx)) {
            if (hasHammer(ctx)) {
                startFixingBrazier(ctx);
                return;
            }
        }

        // Priority 2: Light unlit brazier
        if (wintertodtConfig.isLightBraziers() && isBrazierUnlit(ctx)) {
            if (hasTinderbox(ctx)) {
                startLightingBrazier(ctx);
                return;
            }
        }

        // Priority 3: Feed brazier if we have roots/kindling and brazier is lit
        if (isBrazierLit(ctx) && (rootCount > 0 || kindlingCount > 0)) {
            // Check if we should fletch first
            if (wintertodtConfig.getStrategy().shouldFletch() && 
                rootCount > 0 && 
                kindlingCount < getIdealKindlingCount()) {
                startFletching(ctx);
            } else {
                startFeedingBrazier(ctx);
            }
            return;
        }

        // Priority 4: Chop roots if inventory has space
        InventoryState inventory = ctx.getInventoryState();
        if (inventory.getFreeSlots() > 0) {
            startChoppingRoots(ctx);
            return;
        }

        // If inventory full and we have points target, idle
        if (roundPoints >= wintertodtConfig.getTargetPointsPerRound()) {
            log.debug("Target points reached ({}), idling", roundPoints);
            return;
        }

        // Otherwise, keep feeding
        if (rootCount > 0 || kindlingCount > 0) {
            startFeedingBrazier(ctx);
        }
    }

    /**
     * Get ideal kindling count based on strategy.
     */
    private int getIdealKindlingCount() {
        // Fletch about half inventory worth
        return 10;
    }

    // ========================================================================
    // Actions
    // ========================================================================

    private void startChoppingRoots(TaskContext ctx) {
        currentActivity = WintertodtActivity.WOODCUTTING;
        lastActivityTime = Instant.now();

        activeSubTask = new InteractObjectTask(BRUMA_ROOT_TREE_ID, "Chop")
                .withDescription("Chop bruma roots");
        log.trace("Starting to chop bruma roots");
    }

    private void startFletching(TaskContext ctx) {
        currentActivity = WintertodtActivity.FLETCHING;
        lastActivityTime = Instant.now();

        // Use knife on bruma root (inventory click)
        InventoryClickHelper clickHelper = ctx.getInventoryClickHelper();
        if (clickHelper == null) {
            log.warn("InventoryClickHelper not available");
            currentActivity = WintertodtActivity.IDLE;
            return;
        }

        InventoryState inventory = ctx.getInventoryState();
        int knifeSlot = inventory.getSlotOf(KNIFE_ID);
        int rootSlot = inventory.getSlotOf(BRUMA_ROOT_ID);

        if (knifeSlot < 0 || rootSlot < 0) {
            log.debug("Missing knife or roots for fletching");
            currentActivity = WintertodtActivity.IDLE;
            return;
        }

        clickPending = true;

        // Click knife first
        clickHelper.executeClick(knifeSlot, "Use knife")
                .thenCompose(success -> {
                    if (!success) {
                        throw new RuntimeException("Failed to click knife");
                    }
                    // Then click root
                    return clickHelper.executeClick(rootSlot, "Use on root");
                })
                .thenAccept(success -> {
                    clickPending = false;
                    if (!success) {
                        currentActivity = WintertodtActivity.IDLE;
                    }
                })
                .exceptionally(e -> {
                    clickPending = false;
                    currentActivity = WintertodtActivity.IDLE;
                    log.error("Fletching click failed", e);
                    return null;
                });

        log.trace("Starting to fletch kindling");
    }

    private void startFeedingBrazier(TaskContext ctx) {
        currentActivity = WintertodtActivity.FEEDING_BRAZIER;
        lastActivityTime = Instant.now();

        activeSubTask = new InteractObjectTask(BRAZIER_LIT_ID, "Feed")
                .withDescription("Feed brazier");

        // Calculate points gained
        int itemsToFeed = kindlingCount > 0 ? kindlingCount : rootCount;
        int pointsPerItem = kindlingCount > 0 ? 
                WintertodtConfig.POINTS_PER_KINDLING : 
                WintertodtConfig.POINTS_PER_ROOT;

        log.trace("Starting to feed brazier ({} items, {} pts each)", itemsToFeed, pointsPerItem);
    }

    private void startLightingBrazier(TaskContext ctx) {
        currentActivity = WintertodtActivity.LIGHTING_BRAZIER;
        lastActivityTime = Instant.now();

        activeSubTask = new InteractObjectTask(BRAZIER_UNLIT_ID, "Light")
                .withDescription("Light brazier");
        log.trace("Starting to light brazier");
    }

    private void startFixingBrazier(TaskContext ctx) {
        currentActivity = WintertodtActivity.FIXING_BRAZIER;
        lastActivityTime = Instant.now();

        activeSubTask = new InteractObjectTask(BRAZIER_BROKEN_ID, "Fix")
                .withDescription("Fix brazier");
        log.trace("Starting to fix brazier");
    }

    // ========================================================================
    // HP Management
    // ========================================================================

    @Override
    protected boolean shouldEatFood(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        double hpPercent = (double) player.getCurrentHitpoints() / player.getMaxHitpoints();

        // Use config threshold
        return hpPercent < wintertodtConfig.getEatThreshold();
    }

    private void handleEating(TaskContext ctx) {
        currentActivity = WintertodtActivity.EATING;

        FoodManager foodManager = ctx.getFoodManager();
        if (foodManager != null && foodManager.canEat(ctx.getCurrentTick())) {
            // FoodManager handles the actual eating
            log.debug("Eating food");
        } else {
            // Manual fallback - find and click food
            InventoryState inventory = ctx.getInventoryState();
            InventoryClickHelper clickHelper = ctx.getInventoryClickHelper();

            if (clickHelper != null && inventory.hasFood()) {
                int foodSlot = inventory.getFoodSlots().get(0);
                clickPending = true;

                clickHelper.executeClick(foodSlot, "Eat food")
                        .thenAccept(success -> {
                            clickPending = false;
                            currentActivity = WintertodtActivity.IDLE;
                        })
                        .exceptionally(e -> {
                            clickPending = false;
                            currentActivity = WintertodtActivity.IDLE;
                            return null;
                        });
            }
        }
    }

    // ========================================================================
    // State Helpers
    // ========================================================================

    private void updateInventoryCounts(TaskContext ctx) {
        InventoryState inventory = ctx.getInventoryState();
        rootCount = inventory.countItem(BRUMA_ROOT_ID);
        kindlingCount = inventory.countItem(BRUMA_KINDLING_ID);
    }

    private void updateActivityFromAnimation(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        int animId = player.getAnimationId();

        // Update activity based on animation
        WintertodtActivity detected = detectActivityFromAnimation(animId);
        if (detected != null && detected != WintertodtActivity.IDLE) {
            currentActivity = detected;
            lastActivityTime = Instant.now();
        }
    }

    private WintertodtActivity detectActivityFromAnimation(int animId) {
        // Woodcutting animations (various axe types)
        if (isWoodcuttingAnimation(animId)) {
            return WintertodtActivity.WOODCUTTING;
        }

        switch (animId) {
            case ANIMATION_FLETCHING:
                return WintertodtActivity.FLETCHING;
            case ANIMATION_FEEDING:
                return WintertodtActivity.FEEDING_BRAZIER;
            case ANIMATION_LIGHTING:
                return WintertodtActivity.LIGHTING_BRAZIER;
            case ANIMATION_FIXING:
                return WintertodtActivity.FIXING_BRAZIER;
            default:
                return null;
        }
    }

    private boolean isWoodcuttingAnimation(int animId) {
        // Woodcutting animation range (bronze through crystal axes)
        return (animId >= 875 && animId <= 883) || // Standard axes
               (animId >= 2846 && animId <= 2848) || // Special axes
               animId == 8324 || // Infernal axe
               animId == 8778 || // Dragon axe
               animId == 8303;   // Crystal axe
    }

    private void checkActivityTimeout() {
        if (currentActivity == WintertodtActivity.IDLE || lastActivityTime == null) {
            return;
        }

        Duration sinceActivity = Duration.between(lastActivityTime, Instant.now());
        if (sinceActivity.compareTo(ACTIVITY_TIMEOUT) >= 0) {
            log.debug("Activity {} timed out", currentActivity);
            currentActivity = WintertodtActivity.IDLE;
        }
    }

    private int getTimerValue(TaskContext ctx) {
        Client client = ctx.getClient();
        return client.getVarbitValue(VARBIT_TIMER);
    }

    // ========================================================================
    // Brazier State Checks
    // ========================================================================

    private boolean isBrazierLit(TaskContext ctx) {
        return findBrazierWithId(ctx, BRAZIER_LIT_ID).isPresent();
    }

    private boolean isBrazierUnlit(TaskContext ctx) {
        return findBrazierWithId(ctx, BRAZIER_UNLIT_ID).isPresent();
    }

    private boolean isBrazierBroken(TaskContext ctx) {
        return findBrazierWithId(ctx, BRAZIER_BROKEN_ID).isPresent();
    }

    private Optional<GameObjectSnapshot> findBrazierWithId(TaskContext ctx, int objectId) {
        WorldState world = ctx.getWorldState();
        WorldPoint brazierPos = activeBrazier != null ? 
                activeBrazier.getBrazierPosition() : 
                ctx.getPlayerState().getWorldPosition();

        return world.getNearbyObjects().stream()
                .filter(obj -> obj.getId() == objectId)
                .filter(obj -> obj.getWorldPosition().distanceTo(brazierPos) <= 5)
                .findFirst();
    }

    // ========================================================================
    // Equipment Checks
    // ========================================================================

    private boolean hasKnife(TaskContext ctx) {
        return ctx.getInventoryState().hasItem(KNIFE_ID);
    }

    private boolean hasTinderbox(TaskContext ctx) {
        return ctx.getInventoryState().hasItem(TINDERBOX_ID);
    }

    private boolean hasHammer(TaskContext ctx) {
        return ctx.getInventoryState().hasItem(HAMMER_ID);
    }

    // ========================================================================
    // Navigation
    // ========================================================================

    private void ensureAtBrazier(TaskContext ctx) {
        if (activeBrazier == null) {
            return;
        }

        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
        if (!activeBrazier.isInRange(playerPos)) {
            if (activeSubTask == null) {
                activeSubTask = new WalkToTask(activeBrazier.getSafePosition())
                        .withDescription("Move to brazier");
            }
        }
    }

    @Override
    protected WorldPoint getEntryPoint(TaskContext ctx) {
        // Wintertodt entrance near games necklace teleport
        return new WorldPoint(1630, 3944, 0);
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("Wintertodt[rounds=%d, pts=%d, activity=%s]",
                roundsCompleted, roundPoints, currentActivity);
    }
}

