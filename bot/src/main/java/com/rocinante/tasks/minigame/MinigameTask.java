package com.rocinante.tasks.minigame;

import com.rocinante.combat.FoodManager;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.impl.WalkToTask;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.time.Instant;

/**
 * Abstract base class for minigame automation tasks.
 *
 * <p>Provides a reusable framework for implementing minigame training:
 * <ul>
 *   <li>Phase-based lifecycle management (travel, entry, active, rewards, exit)</li>
 *   <li>Region-based area detection</li>
 *   <li>Round/game tracking</li>
 *   <li>Food/HP management integration with existing {@link FoodManager}</li>
 *   <li>XP and progress tracking</li>
 * </ul>
 *
 * <p>Subclasses implement specific minigame logic by overriding:
 * <ul>
 *   <li>{@link #executeWaitingPhase(TaskContext)} - Wait for round start</li>
 *   <li>{@link #executeActivePhase(TaskContext)} - Main gameplay loop</li>
 *   <li>{@link #executeRewardsPhase(TaskContext)} - Collect rewards</li>
 *   <li>{@link #isRoundActive(TaskContext)} - Detect if round is in progress</li>
 *   <li>{@link #shouldCollectRewards(TaskContext)} - Detect if rewards available</li>
 * </ul>
 *
 * <p>Example subclass:
 * <pre>{@code
 * public class WintertodtTask extends MinigameTask {
 *     @Override
 *     protected void executeActivePhase(TaskContext ctx) {
 *         // Chop roots, fletch, feed brazier
 *     }
 * }
 * }</pre>
 *
 * @see MinigameConfig
 * @see MinigamePhase
 */
@Slf4j
public abstract class MinigameTask extends AbstractTask {

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Base minigame configuration.
     */
    @Getter
    protected final MinigameConfig baseConfig;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current minigame phase.
     */
    @Getter
    protected MinigamePhase phase = MinigamePhase.TRAVEL;

    /**
     * Active sub-task for delegation (walking, interacting, etc.).
     */
    protected Task activeSubTask;

    /**
     * Task start time.
     */
    protected Instant startTime;

    /**
     * Starting XP when task began.
     */
    protected int startXp = -1;

    /**
     * Number of rounds/games completed.
     */
    @Getter
    protected int roundsCompleted = 0;

    /**
     * Total points/score accumulated (if applicable).
     */
    @Getter
    protected int totalPoints = 0;

    /**
     * Ticks spent in current phase.
     */
    protected int phaseWaitTicks = 0;

    /**
     * Maximum ticks to wait in any phase before timeout.
     */
    protected static final int MAX_PHASE_WAIT_TICKS = 100;

    /**
     * Custom task description.
     */
    @Setter
    protected String description;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a minigame task with the specified configuration.
     *
     * @param baseConfig the minigame configuration
     */
    protected MinigameTask(MinigameConfig baseConfig) {
        this.baseConfig = baseConfig;
        baseConfig.validate();

        // Set timeout based on config
        if (baseConfig.hasTimeLimit()) {
            this.timeout = baseConfig.getMaxDuration().plusMinutes(5);
        } else {
            this.timeout = Duration.ofHours(8); // Default long timeout
        }
    }

    // ========================================================================
    // Abstract Methods - Subclasses Must Implement
    // ========================================================================

    /**
     * Get the skill trained by this minigame.
     *
     * @return the primary skill
     */
    protected abstract Skill getTrainedSkill();

    /**
     * Execute the waiting phase logic.
     * Called when in minigame area but round hasn't started.
     *
     * @param ctx the task context
     */
    protected abstract void executeWaitingPhase(TaskContext ctx);

    /**
     * Execute the active gameplay phase.
     * Main minigame loop - subclasses implement specific actions.
     *
     * @param ctx the task context
     */
    protected abstract void executeActivePhase(TaskContext ctx);

    /**
     * Execute the rewards collection phase.
     *
     * @param ctx the task context
     */
    protected abstract void executeRewardsPhase(TaskContext ctx);

    /**
     * Check if a round/game is currently active.
     *
     * @param ctx the task context
     * @return true if round is in progress
     */
    protected abstract boolean isRoundActive(TaskContext ctx);

    /**
     * Check if rewards are available to collect.
     *
     * @param ctx the task context
     * @return true if rewards should be collected
     */
    protected abstract boolean shouldCollectRewards(TaskContext ctx);

    // ========================================================================
    // Optional Overrides
    // ========================================================================

    /**
     * Execute entry phase logic.
     * Default implementation handles object/NPC interaction.
     * Override for custom entry mechanics.
     *
     * @param ctx the task context
     */
    protected void executeEntryPhase(TaskContext ctx) {
        // Default: just transition to waiting if already in area
        if (isInMinigameArea(ctx)) {
            log.info("Already in {} area, transitioning to waiting", baseConfig.getMinigameName());
            transitionToPhase(MinigamePhase.WAITING);
            return;
        }

        // Otherwise, entry logic would be implemented by subclass or handled via sub-task
        log.debug("Entry phase - waiting for area detection");
        phaseWaitTicks++;

        if (phaseWaitTicks > MAX_PHASE_WAIT_TICKS) {
            fail("Timed out waiting for minigame entry");
        }
    }

    /**
     * Execute resupply phase logic.
     * Default implementation transitions back to waiting/active.
     * Override for custom resupply behavior.
     *
     * @param ctx the task context
     */
    protected void executeResupplyPhase(TaskContext ctx) {
        // Default: just go back to waiting
        log.debug("Resupply phase - transitioning to waiting");
        transitionToPhase(MinigamePhase.WAITING);
    }

    /**
     * Execute exit phase logic.
     * Default implementation completes the task.
     * Override for custom exit behavior.
     *
     * @param ctx the task context
     */
    protected void executeExitPhase(TaskContext ctx) {
        log.info("Exiting {}", baseConfig.getMinigameName());
        completeTask(ctx);
    }

    /**
     * Called when entering a new round.
     * Override to perform round-start setup.
     *
     * @param ctx the task context
     */
    protected void onRoundStart(TaskContext ctx) {
        log.debug("Round started in {}", baseConfig.getMinigameName());
    }

    /**
     * Called when a round ends.
     * Override to perform round-end cleanup.
     *
     * @param ctx the task context
     */
    protected void onRoundEnd(TaskContext ctx) {
        roundsCompleted++;
        log.info("Round {} completed in {}", roundsCompleted, baseConfig.getMinigameName());
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
            transitionToPhase(MinigamePhase.EXIT);
        }

        // Execute active sub-task if present
        if (activeSubTask != null) {
            activeSubTask.execute(ctx);
            if (activeSubTask.getState().isTerminal()) {
                handleSubTaskComplete(ctx);
            }
            return;
        }

        // Check for food needs using FoodManager
        if (shouldEatFood(ctx)) {
            handleFoodConsumption(ctx);
            return;
        }

        // Execute current phase
        switch (phase) {
            case TRAVEL:
                executeTravelPhase(ctx);
                break;
            case ENTRY:
                executeEntryPhase(ctx);
                break;
            case WAITING:
                // Check if round has started
                if (isRoundActive(ctx)) {
                    onRoundStart(ctx);
                    transitionToPhase(MinigamePhase.ACTIVE);
                } else {
                    executeWaitingPhase(ctx);
                }
                break;
            case ACTIVE:
                // Check if round has ended
                if (!isRoundActive(ctx)) {
                    onRoundEnd(ctx);
                    if (shouldCollectRewards(ctx)) {
                        transitionToPhase(MinigamePhase.REWARDS);
                    } else {
                        transitionToPhase(MinigamePhase.WAITING);
                    }
                } else {
                    executeActivePhase(ctx);
                }
                break;
            case REWARDS:
                if (!shouldCollectRewards(ctx)) {
                    // Check if we need to resupply
                    if (needsResupply(ctx)) {
                        transitionToPhase(MinigamePhase.RESUPPLY);
                    } else {
                        transitionToPhase(MinigamePhase.WAITING);
                    }
                } else {
                    executeRewardsPhase(ctx);
                }
                break;
            case RESUPPLY:
                executeResupplyPhase(ctx);
                break;
            case EXIT:
                executeExitPhase(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Travel
    // ========================================================================

    /**
     * Execute travel phase - navigate to minigame location.
     */
    protected void executeTravelPhase(TaskContext ctx) {
        // Check if already at minigame
        if (isInMinigameArea(ctx)) {
            log.info("Already at {}, skipping travel", baseConfig.getMinigameName());
            transitionToPhase(MinigamePhase.ENTRY);
            return;
        }

        // Create walk task if needed
        if (activeSubTask == null) {
            WorldPoint destination = getEntryPoint(ctx);
            if (destination != null) {
                activeSubTask = new WalkToTask(destination)
                        .withDescription("Travel to " + baseConfig.getMinigameName());
                log.info("Traveling to {}", baseConfig.getMinigameName());
            } else {
                fail("No entry point configured for " + baseConfig.getMinigameName());
            }
        }
    }

    /**
     * Get the entry point for traveling to the minigame.
     */
    protected WorldPoint getEntryPoint(TaskContext ctx) {
        if (baseConfig.getEntryPoint() != null) {
            return baseConfig.getEntryPoint();
        }
        // Subclasses can override to provide dynamic entry points
        return null;
    }

    // ========================================================================
    // Area Detection
    // ========================================================================

    /**
     * Check if the player is in the minigame area.
     * Uses region ID detection by default.
     *
     * @param ctx the task context
     * @return true if in minigame area
     */
    protected boolean isInMinigameArea(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint pos = player.getWorldPosition();

        if (pos == null) {
            return false;
        }

        int currentRegion = pos.getRegionID();
        return baseConfig.getRegionIds().contains(currentRegion);
    }

    // ========================================================================
    // Food Management (uses existing FoodManager)
    // ========================================================================

    /**
     * Check if the player should eat food.
     * Delegates to FoodManager for consistent behavior.
     *
     * @param ctx the task context
     * @return true if should eat
     */
    protected boolean shouldEatFood(TaskContext ctx) {
        if (!baseConfig.isBringFood()) {
            return false;
        }

        FoodManager foodManager = ctx.getFoodManager();
        PlayerState player = ctx.getPlayerState();
        double hpPercent = (double) player.getCurrentHitpoints() / player.getMaxHitpoints();

        if (foodManager == null) {
            // Fallback: simple HP check
            return hpPercent < baseConfig.getEatThreshold();
        }

        // Use FoodManager's canEat plus our threshold
        return hpPercent < baseConfig.getEatThreshold() && foodManager.canEat(ctx.getCurrentTick());
    }

    /**
     * Handle food consumption.
     * Uses FoodManager for eating logic.
     *
     * @param ctx the task context
     */
    protected void handleFoodConsumption(TaskContext ctx) {
        FoodManager foodManager = ctx.getFoodManager();
        if (foodManager != null) {
            // FoodManager handles the eating
            // The actual click is handled elsewhere in the food management system
            log.debug("Food consumption needed - FoodManager will handle");
        }
    }

    /**
     * Check if resupply is needed.
     *
     * @param ctx the task context
     * @return true if should resupply
     */
    protected boolean needsResupply(TaskContext ctx) {
        if (!baseConfig.isResupplyEnabled()) {
            return false;
        }

        InventoryState inventory = ctx.getInventoryState();
        int foodCount = inventory.countFood();

        return foodCount < baseConfig.getMinFoodBeforeExit();
    }

    // ========================================================================
    // Completion Checking
    // ========================================================================

    /**
     * Check if the task is completed.
     *
     * @param ctx the task context
     * @return true if completed
     */
    protected boolean isCompleted(TaskContext ctx) {
        if (isTargetReached(ctx)) {
            return true;
        }

        // Check time limit
        if (baseConfig.hasTimeLimit() && startTime != null) {
            Duration elapsed = Duration.between(startTime, Instant.now());
            if (elapsed.compareTo(baseConfig.getMaxDuration()) >= 0) {
                log.info("Time limit reached: {}", elapsed);
                return true;
            }
        }

        // Check rounds target
        if (baseConfig.hasRoundsTarget() && roundsCompleted >= baseConfig.getTargetRounds()) {
            log.info("Rounds target reached: {}", roundsCompleted);
            return true;
        }

        return false;
    }

    /**
     * Check if level/XP target is reached.
     *
     * @param ctx the task context
     * @return true if target reached
     */
    protected boolean isTargetReached(TaskContext ctx) {
        Client client = ctx.getClient();
        Skill skill = getTrainedSkill();

        if (baseConfig.hasLevelTarget()) {
            int currentLevel = client.getRealSkillLevel(skill);
            if (currentLevel >= baseConfig.getTargetLevel()) {
                log.info("Level target {} reached", baseConfig.getTargetLevel());
                return true;
            }
        }

        if (baseConfig.hasXpTarget()) {
            int currentXp = client.getSkillExperience(skill);
            if (currentXp >= baseConfig.getTargetXp()) {
                log.info("XP target {} reached", baseConfig.getTargetXp());
                return true;
            }
        }

        return false;
    }

    // ========================================================================
    // State Management
    // ========================================================================

    /**
     * Transition to a new phase.
     *
     * @param newPhase the target phase
     */
    protected void transitionToPhase(MinigamePhase newPhase) {
        log.debug("{}: {} -> {}", baseConfig.getMinigameName(), phase, newPhase);
        this.phase = newPhase;
        this.phaseWaitTicks = 0;
    }

    /**
     * Initialize the task on first execution.
     */
    protected void initializeTask(TaskContext ctx) {
        startTime = Instant.now();
        startXp = ctx.getClient().getSkillExperience(getTrainedSkill());
        log.info("{} task started. Target: {}", 
                baseConfig.getMinigameName(), 
                getTargetDescription());
    }

    /**
     * Handle sub-task completion.
     */
    protected void handleSubTaskComplete(TaskContext ctx) {
        TaskState subTaskState = activeSubTask.getState();
        String subTaskDesc = activeSubTask.getDescription();

        if (subTaskState == TaskState.FAILED) {
            log.warn("Sub-task failed: {}", subTaskDesc);
        }

        activeSubTask = null;

        // Handle travel completion
        if (phase == MinigamePhase.TRAVEL && isInMinigameArea(ctx)) {
            transitionToPhase(MinigamePhase.ENTRY);
        }
    }

    /**
     * Complete the task with stats logging.
     */
    protected void completeTask(TaskContext ctx) {
        Client client = ctx.getClient();
        int currentXp = client.getSkillExperience(getTrainedSkill());
        int xpGained = currentXp - startXp;
        Duration elapsed = startTime != null ? Duration.between(startTime, Instant.now()) : Duration.ZERO;

        log.info("{} completed: {} XP gained, {} rounds in {}",
                baseConfig.getMinigameName(),
                String.format("%,d", xpGained),
                roundsCompleted,
                formatDuration(elapsed));

        complete();
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

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
        int currentXp = ctx.getClient().getSkillExperience(getTrainedSkill());
        return currentXp - startXp;
    }

    /**
     * Get estimated XP per hour.
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

    /**
     * Get a description of the target goal.
     */
    protected String getTargetDescription() {
        if (baseConfig.hasLevelTarget()) {
            return "Level " + baseConfig.getTargetLevel();
        }
        if (baseConfig.hasXpTarget()) {
            return String.format("%,d XP", baseConfig.getTargetXp());
        }
        if (baseConfig.hasRoundsTarget()) {
            return baseConfig.getTargetRounds() + " rounds";
        }
        if (baseConfig.hasTimeLimit()) {
            return baseConfig.getMaxDuration().toMinutes() + " minutes";
        }
        return "unlimited";
    }

    /**
     * Format a duration for logging.
     */
    protected String formatDuration(Duration duration) {
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
        log.warn("{} failed after {} rounds", baseConfig.getMinigameName(), roundsCompleted);
    }

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("%s[rounds=%d, phase=%s]",
                baseConfig.getMinigameName(), roundsCompleted, phase);
    }
}

