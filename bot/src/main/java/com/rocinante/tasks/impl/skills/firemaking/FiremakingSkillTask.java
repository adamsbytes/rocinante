package com.rocinante.tasks.impl.skills.firemaking;

import com.rocinante.input.InventoryClickHelper;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.impl.BankTask;
import com.rocinante.tasks.impl.WalkToTask;
import com.rocinante.util.ItemCollections;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Task for line-based firemaking training.
 *
 * <p>Implements traditional OSRS firemaking:
 * <ul>
 *   <li>Uses tinderbox on logs in inventory</li>
 *   <li>Detects successful fire lighting via animation (ID: 733)</li>
 *   <li>Tracks player movement west after each fire</li>
 *   <li>Repositions to new line start when blocked</li>
 *   <li>Integrates with BankTask for log resupply</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * FiremakingConfig config = FiremakingConfig.builder()
 *     .logItemId(ItemID.WILLOW_LOGS)
 *     .targetLevel(45)
 *     .bankForLogs(true)
 *     .build();
 *
 * FiremakingSkillTask task = new FiremakingSkillTask(config);
 * }</pre>
 *
 * @see FiremakingConfig
 */
@Slf4j
public class FiremakingSkillTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Animation ID for lighting a fire.
     * From RuneLite's AnimationID.HUMAN_CREATEFIRE (733).
     */
    private static final int FIRE_LIGHTING_ANIMATION = 733;

    /**
     * Maximum ticks to wait for fire lighting response.
     */
    private static final int LIGHTING_TIMEOUT_TICKS = 10;

    /**
     * Ticks to wait after successful fire lighting before next action.
     */
    private static final int POST_FIRE_DELAY_TICKS = 2;

    // ========================================================================
    // Configuration
    // ========================================================================

    @Getter
    private final FiremakingConfig config;

    private String description;

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public FiremakingSkillTask withDescription(String description) {
        this.description = description;
        return this;
    }

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private FiremakingPhase phase = FiremakingPhase.SETUP;

    /**
     * Active sub-task (banking, walking).
     */
    private Task activeSubTask;

    /**
     * Position before starting to light fire.
     * Used to detect movement after successful lighting.
     */
    private WorldPoint positionBeforeFire;

    /**
     * Ticks spent waiting for current action.
     */
    private int waitTicks = 0;

    /**
     * Total logs burned this session.
     */
    @Getter
    private int logsBurned = 0;

    /**
     * Starting XP for tracking.
     */
    private int startXp = -1;

    /**
     * Task start time.
     */
    private Instant startTime;

    /**
     * Whether async inventory click is pending.
     */
    private boolean clickPending = false;

    /**
     * Current click phase (tinderbox or logs).
     */
    private ClickPhase clickPhase = ClickPhase.CLICK_TINDERBOX;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a firemaking task with the specified configuration.
     *
     * @param config the firemaking configuration
     */
    public FiremakingSkillTask(FiremakingConfig config) {
        this.config = config;
        config.validate();

        // Set timeout based on config
        if (config.hasTimeLimit()) {
            this.timeout = config.getMaxDuration().plusMinutes(5);
        } else {
            this.timeout = Duration.ofHours(8);
        }
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (!ctx.isLoggedIn()) {
            return false;
        }

        // Check if already at target
        if (isTargetReached(ctx)) {
            return false;
        }

        // Check level requirement for logs
        Client client = ctx.getClient();
        int fmLevel = client.getRealSkillLevel(Skill.FIREMAKING);
        if (fmLevel < config.getRequiredLevel()) {
            log.debug("Firemaking level {} is below required {} for configured logs",
                    fmLevel, config.getRequiredLevel());
            return false;
        }

        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (startTime == null) {
            initializeTask(ctx);
        }

        // Check for completion
        if (isTargetReached(ctx)) {
            completeTask(ctx);
            return;
        }

        // Handle active sub-task
        if (activeSubTask != null) {
            activeSubTask.execute(ctx);
            if (activeSubTask.getState().isTerminal()) {
                handleSubTaskComplete(ctx);
            }
            return;
        }

        // Skip if click operation pending
        if (clickPending) {
            return;
        }

        // Execute current phase
        switch (phase) {
            case SETUP:
                executeSetup(ctx);
                break;
            case MOVING_TO_START:
                executeMovingToStart(ctx);
                break;
            case LIGHTING_FIRE:
                executeLightingFire(ctx);
                break;
            case WAITING_FOR_FIRE:
                executeWaitingForFire(ctx);
                break;
            case REPOSITIONING:
                executeRepositioning(ctx);
                break;
            case BANKING:
                executeBanking(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Setup
    // ========================================================================

    private void executeSetup(TaskContext ctx) {
        InventoryState inventory = ctx.getInventoryState();

        // Check for tinderbox (any from collection)
        if (!hasAnyTinderbox(inventory)) {
            log.error("No tinderbox in inventory");
            fail("Missing tinderbox");
            return;
        }

        // Check for logs
        if (!inventory.hasItem(config.getLogItemId())) {
            if (config.isBankForLogs()) {
                log.info("No logs in inventory, transitioning to banking");
                transitionToPhase(FiremakingPhase.BANKING);
            } else {
                log.info("No logs in inventory and banking disabled, completing");
                completeTask(ctx);
            }
            return;
        }

        // Check if at start position
        PlayerState player = ctx.getPlayerState();
        WorldPoint currentPos = player.getWorldPosition();
        WorldPoint startPos = config.getStartPosition();

        if (currentPos.distanceTo(startPos) > 15) {
            log.info("Not near start position, moving to {}", startPos);
            transitionToPhase(FiremakingPhase.MOVING_TO_START);
        } else {
            // Ready to start firemaking
            transitionToPhase(FiremakingPhase.LIGHTING_FIRE);
        }
    }

    // ========================================================================
    // Phase: Moving to Start
    // ========================================================================

    private void executeMovingToStart(TaskContext ctx) {
        if (activeSubTask == null) {
            WorldPoint targetPos = config.getStartPosition();

            // Randomize start position slightly for humanization
            if (config.isRandomizeStartPosition() && ctx.getRandomization() != null) {
                int xOffset = ctx.getRandomization().uniformRandomInt(-2, 2);
                targetPos = new WorldPoint(
                        targetPos.getX() + xOffset,
                        targetPos.getY(),
                        targetPos.getPlane()
                );
            }

            activeSubTask = new WalkToTask(targetPos)
                    .withDescription("Walk to firemaking start position");
            log.debug("Walking to start position: {}", targetPos);
        }
    }

    // ========================================================================
    // Phase: Lighting Fire
    // ========================================================================

    private void executeLightingFire(TaskContext ctx) {
        InventoryState inventory = ctx.getInventoryState();

        // Check we still have logs
        if (!inventory.hasItem(config.getLogItemId())) {
            if (config.isBankForLogs()) {
                transitionToPhase(FiremakingPhase.BANKING);
            } else {
                completeTask(ctx);
            }
            return;
        }

        // Check if current position is blocked (can't light fire here)
        if (isPositionBlocked(ctx)) {
            log.debug("Current position blocked, repositioning");
            transitionToPhase(FiremakingPhase.REPOSITIONING);
            return;
        }

        // Store position before attempting to light
        positionBeforeFire = ctx.getPlayerState().getWorldPosition();

        // Use tinderbox on logs (two-click process)
        InventoryClickHelper clickHelper = ctx.getInventoryClickHelper();
        if (clickHelper == null) {
            log.error("InventoryClickHelper not available");
            fail("InventoryClickHelper not available");
            return;
        }

        if (clickPhase == ClickPhase.CLICK_TINDERBOX) {
            int tinderboxSlot = findTinderboxSlot(inventory);
            if (tinderboxSlot < 0) {
                fail("Tinderbox not found in inventory");
                return;
            }

            log.debug("Clicking tinderbox in slot {}", tinderboxSlot);
            clickPending = true;

            clickHelper.executeClick(tinderboxSlot, "Use tinderbox")
                    .thenAccept(success -> {
                        clickPending = false;
                        if (success) {
                            clickPhase = ClickPhase.CLICK_LOGS;
                        } else {
                            log.warn("Failed to click tinderbox");
                        }
                    })
                    .exceptionally(e -> {
                        clickPending = false;
                        log.error("Tinderbox click failed", e);
                        return null;
                    });
        } else if (clickPhase == ClickPhase.CLICK_LOGS) {
            int logSlot = inventory.getSlotOf(config.getLogItemId());
            if (logSlot < 0) {
                fail("Logs not found in inventory");
                return;
            }

            log.debug("Clicking logs in slot {}", logSlot);
            clickPending = true;

            clickHelper.executeClick(logSlot, "Use on logs")
                    .thenAccept(success -> {
                        clickPending = false;
                        if (success) {
                            clickPhase = ClickPhase.CLICK_TINDERBOX;
                            waitTicks = 0;
                            transitionToPhase(FiremakingPhase.WAITING_FOR_FIRE);
                        } else {
                            log.warn("Failed to click logs");
                            clickPhase = ClickPhase.CLICK_TINDERBOX;
                        }
                    })
                    .exceptionally(e -> {
                        clickPending = false;
                        log.error("Logs click failed", e);
                        clickPhase = ClickPhase.CLICK_TINDERBOX;
                        return null;
                    });
        }
    }

    // ========================================================================
    // Phase: Waiting for Fire
    // ========================================================================

    private void executeWaitingForFire(TaskContext ctx) {
        waitTicks++;

        // Check for timeout
        if (waitTicks > config.getLightingTimeoutTicks()) {
            log.debug("Fire lighting timed out, checking position");
            // Check if we moved (fire was lit but we missed the animation)
            WorldPoint currentPos = ctx.getPlayerState().getWorldPosition();
            if (!currentPos.equals(positionBeforeFire)) {
                onFireLit(ctx);
            } else {
                // Position blocked, need to reposition
                log.debug("Timed out at same position, repositioning");
                transitionToPhase(FiremakingPhase.REPOSITIONING);
            }
            return;
        }

        // Check for fire lighting animation
        PlayerState player = ctx.getPlayerState();
        if (player.getAnimationId() == FIRE_LIGHTING_ANIMATION) {
            log.debug("Fire lighting animation detected");
            // Continue waiting for completion
            return;
        }

        // Check if player moved (fire was successfully lit)
        WorldPoint currentPos = player.getWorldPosition();
        if (!currentPos.equals(positionBeforeFire)) {
            onFireLit(ctx);
            return;
        }

        // Still waiting
        log.debug("Waiting for fire... tick {}", waitTicks);
    }

    /**
     * Called when a fire is successfully lit.
     */
    private void onFireLit(TaskContext ctx) {
        logsBurned++;
        log.debug("Fire lit! Total logs burned: {}", logsBurned);

        // Small delay before next fire (humanization)
        waitTicks = 0;

        // Check if we need to reposition (blocked or end of line)
        PlayerState player = ctx.getPlayerState();
        WorldPoint currentPos = player.getWorldPosition();

        // If we've moved significantly west, we might be at end of line
        int distanceFromStart = Math.abs(currentPos.getX() - config.getStartPosition().getX());
        if (distanceFromStart >= config.getMinLineTiles()) {
            log.debug("Reached end of line (distance {}), repositioning", distanceFromStart);
            transitionToPhase(FiremakingPhase.REPOSITIONING);
        } else {
            // Continue with next fire
            transitionToPhase(FiremakingPhase.LIGHTING_FIRE);
        }
    }

    // ========================================================================
    // Phase: Repositioning
    // ========================================================================

    private void executeRepositioning(TaskContext ctx) {
        if (activeSubTask == null) {
            // Find new line start position
            WorldPoint newStart = findNewLineStart(ctx);
            if (newStart == null) {
                // Fall back to configured start
                newStart = config.getStartPosition();
            }

            activeSubTask = new WalkToTask(newStart)
                    .withDescription("Reposition for new fire line");
            log.debug("Repositioning to new line start: {}", newStart);
        }
    }

    /**
     * Find a new clear line start position.
     * Tries to find a position north or south of the current line.
     */
    private WorldPoint findNewLineStart(TaskContext ctx) {
        WorldPoint currentStart = config.getStartPosition();
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();

        // Try positions north and south of current start
        int[] yOffsets = {2, -2, 4, -4, 6, -6};

        for (int yOffset : yOffsets) {
            WorldPoint candidate = new WorldPoint(
                    currentStart.getX(),
                    currentStart.getY() + yOffset,
                    currentStart.getPlane()
            );

            // Basic distance check (don't go too far)
            if (candidate.distanceTo(playerPos) < 30) {
                // Could add tile collision checking here with WorldState
                return candidate;
            }
        }

        return currentStart;
    }

    // ========================================================================
    // Phase: Banking
    // ========================================================================

    private void executeBanking(TaskContext ctx) {
        if (activeSubTask == null) {
            // Create bank task to withdraw logs (leave 1 slot for tinderbox)
            BankTask bankTask = BankTask.withdraw(config.getLogItemId(), 27);

            activeSubTask = bankTask;
            log.info("Banking for logs");
        }
    }

    // ========================================================================
    // Position Checking
    // ========================================================================

    /**
     * Check if the current position is blocked (fire, object, etc.).
     * Uses WorldState to check for nearby game objects at player position.
     */
    private boolean isPositionBlocked(TaskContext ctx) {
        WorldState world = ctx.getWorldState();
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();

        // Check for fire objects at current position
        // Fire object IDs are typically in the 26000-26500 range
        return world.getNearbyObjects().stream()
                .anyMatch(obj -> {
                    if (obj.getWorldPosition().equals(playerPos)) {
                        int id = obj.getId();
                        // Fire object IDs (common fire objects)
                        return (id >= 26185 && id <= 26186) || // Normal fires
                               (id >= 43475 && id <= 43476);   // Other fires
                    }
                    return false;
                });
    }

    // ========================================================================
    // Completion
    // ========================================================================

    private boolean isTargetReached(TaskContext ctx) {
        Client client = ctx.getClient();

        if (config.hasLevelTarget()) {
            int currentLevel = client.getRealSkillLevel(Skill.FIREMAKING);
            if (currentLevel >= config.getTargetLevel()) {
                return true;
            }
        }

        if (config.hasXpTarget()) {
            int currentXp = client.getSkillExperience(Skill.FIREMAKING);
            if (currentXp >= config.getTargetXp()) {
                return true;
            }
        }

        if (config.hasLogsBurnedTarget() && logsBurned >= config.getTargetLogsBurned()) {
            return true;
        }

        if (config.hasTimeLimit() && startTime != null) {
            Duration elapsed = Duration.between(startTime, Instant.now());
            if (elapsed.compareTo(config.getMaxDuration()) >= 0) {
                return true;
            }
        }

        return false;
    }

    private void completeTask(TaskContext ctx) {
        Client client = ctx.getClient();
        int currentXp = client.getSkillExperience(Skill.FIREMAKING);
        int xpGained = currentXp - startXp;
        Duration elapsed = startTime != null ? Duration.between(startTime, Instant.now()) : Duration.ZERO;

        log.info("Firemaking completed: {} logs burned, {} XP gained in {}",
                logsBurned,
                String.format("%,d", xpGained),
                formatDuration(elapsed));

        complete();
    }

    // ========================================================================
    // State Management
    // ========================================================================

    private void initializeTask(TaskContext ctx) {
        startTime = Instant.now();
        startXp = ctx.getClient().getSkillExperience(Skill.FIREMAKING);
        log.info("Firemaking task started. Target: {}", getTargetDescription());
    }

    private void transitionToPhase(FiremakingPhase newPhase) {
        log.debug("Firemaking: {} -> {}", phase, newPhase);
        this.phase = newPhase;
        this.waitTicks = 0;
    }

    private void handleSubTaskComplete(TaskContext ctx) {
        TaskState subTaskState = activeSubTask.getState();

        if (subTaskState == TaskState.FAILED) {
            log.warn("Sub-task failed: {}", activeSubTask.getDescription());
        }

        // Determine next phase based on what completed
        if (phase == FiremakingPhase.MOVING_TO_START || phase == FiremakingPhase.REPOSITIONING) {
            transitionToPhase(FiremakingPhase.LIGHTING_FIRE);
        } else if (phase == FiremakingPhase.BANKING) {
            transitionToPhase(FiremakingPhase.SETUP);
        }

        activeSubTask = null;
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if inventory has any tinderbox from the configured collection.
     */
    private boolean hasAnyTinderbox(InventoryState inventory) {
        List<Integer> tinderboxIds = config.getTinderboxItemIds();
        for (int id : tinderboxIds) {
            if (inventory.hasItem(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find the slot of the best tinderbox in inventory.
     * Returns -1 if none found.
     */
    private int findTinderboxSlot(InventoryState inventory) {
        // Use best-first ordering (golden tinderbox > regular)
        List<Integer> tinderboxIds = ItemCollections.bestFirst(config.getTinderboxItemIds());
        for (int id : tinderboxIds) {
            int slot = inventory.getSlotOf(id);
            if (slot >= 0) {
                return slot;
            }
        }
        return -1;
    }

    private String getTargetDescription() {
        if (config.hasLevelTarget()) {
            return "Level " + config.getTargetLevel();
        }
        if (config.hasXpTarget()) {
            return String.format("%,d XP", config.getTargetXp());
        }
        if (config.hasLogsBurnedTarget()) {
            return config.getTargetLogsBurned() + " logs";
        }
        if (config.hasTimeLimit()) {
            return config.getMaxDuration().toMinutes() + " minutes";
        }
        return "unlimited";
    }

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
     * Get XP gained this session.
     */
    public int getXpGained(TaskContext ctx) {
        if (startXp < 0) {
            return 0;
        }
        return ctx.getClient().getSkillExperience(Skill.FIREMAKING) - startXp;
    }

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("Firemaking[logs=%d, phase=%s]", logsBurned, phase);
    }

    // ========================================================================
    // Enums
    // ========================================================================

    private enum FiremakingPhase {
        SETUP,
        MOVING_TO_START,
        LIGHTING_FIRE,
        WAITING_FOR_FIRE,
        REPOSITIONING,
        BANKING
    }

    private enum ClickPhase {
        CLICK_TINDERBOX,
        CLICK_LOGS
    }
}

