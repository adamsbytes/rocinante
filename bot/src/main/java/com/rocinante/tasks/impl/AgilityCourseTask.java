package com.rocinante.tasks.impl;

import com.rocinante.agility.AgilityCourse;
import com.rocinante.agility.AgilityObstacle;
import com.rocinante.state.GroundItemSnapshot;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Task for running agility courses (primarily rooftop courses).
 *
 * <p>This task handles:
 * <ul>
 *   <li>Sequential obstacle navigation based on course definition</li>
 *   <li>Position-based obstacle detection (determining which obstacle to do next)</li>
 *   <li>Mark of grace pickup (opportunistic ground item collection)</li>
 *   <li>Lap counting and progress tracking</li>
 *   <li>Failure handling (falling from obstacles)</li>
 * </ul>
 *
 * <p>State Machine:
 * <pre>
 * INIT -> DETERMINE_POSITION -> NAVIGATE_TO_OBSTACLE | INTERACT_OBSTACLE
 *                                      |                      |
 *                                      v                      v
 *                            INTERACT_OBSTACLE      VERIFY_LANDING
 *                                                         |
 *                                                         v
 *                                          CHECK_GROUND_ITEMS -> PICKUP_ITEM
 *                                                    |               |
 *                                                    v               |
 *                                          DETERMINE_POSITION <------+
 * </pre>
 *
 * <p>Example usage:
 * <pre>{@code
 * AgilityCourse course = courseRepo.getCourseById("draynor_rooftop").get();
 * AgilityCourseConfig config = AgilityCourseConfig.builder()
 *     .course(course)
 *     .targetLevel(40)
 *     .pickupMarksOfGrace(true)
 *     .build();
 * AgilityCourseTask task = new AgilityCourseTask(config);
 * }</pre>
 *
 * @see AgilityCourseConfig
 * @see AgilityCourse
 * @see AgilityObstacle
 */
@Slf4j
public class AgilityCourseTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Mark of Grace item ID.
     */
    private static final int MARK_OF_GRACE_ID = ItemID.MARK_OF_GRACE;

    /**
     * Maximum ticks to wait for obstacle completion before retry.
     */
    private static final int OBSTACLE_TIMEOUT_TICKS = 30;

    /**
     * Ticks to confirm player is idle after obstacle.
     */
    private static final int IDLE_CONFIRMATION_TICKS = 3;

    /**
     * Maximum ticks to wait in any phase before timeout.
     */
    private static final int MAX_PHASE_WAIT_TICKS = 50;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Course training configuration.
     */
    @Getter
    private final AgilityCourseConfig config;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private CoursePhase phase = CoursePhase.INIT;

    /**
     * Active sub-task for delegation.
     */
    private Task activeSubTask;

    /**
     * The obstacle currently being attempted.
     */
    private AgilityObstacle currentObstacle;

    /**
     * Task start time.
     */
    private Instant startTime;

    /**
     * Starting XP when task began.
     */
    private int startXp = -1;

    /**
     * Number of laps completed.
     */
    @Getter
    private int lapsCompleted = 0;

    /**
     * Number of marks of grace collected.
     */
    @Getter
    private int marksCollected = 0;

    /**
     * Number of obstacles completed this lap.
     */
    private int obstaclesThisLap = 0;

    /**
     * Number of failures (falls).
     */
    @Getter
    private int failures = 0;

    /**
     * Ticks spent in current phase (for timeout detection).
     */
    private int phaseWaitTicks = 0;

    /**
     * Ticks player has been idle.
     */
    private int idleTicks = 0;

    /**
     * Ground item we're currently trying to pick up.
     */
    private GroundItemSnapshot pendingMark;

    /**
     * Custom task description.
     */
    @Setter
    private String description;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create an agility course task.
     *
     * @param config the course configuration
     */
    public AgilityCourseTask(AgilityCourseConfig config) {
        this.config = config;
        config.validate();

        // Set timeout based on config
        if (config.hasMaxDuration()) {
            this.timeout = config.getMaxDuration().plusMinutes(5);
        } else {
            this.timeout = Duration.ofHours(8); // Default long timeout
        }
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
    public AgilityCourseTask withDescription(String description) {
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

        // Check if already at target
        if (isTargetReached(ctx)) {
            return false;
        }

        // Check Agility level requirement
        int agilityLevel = ctx.getClient().getRealSkillLevel(Skill.AGILITY);
        if (agilityLevel < config.getCourse().getRequiredLevel()) {
            log.debug("Agility level {} is below required {} for {}",
                    agilityLevel, config.getCourse().getRequiredLevel(), config.getCourse().getName());
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
        if (isTargetReached(ctx)) {
            completeTask(ctx);
            return;
        }

        // Execute active sub-task if present
        if (activeSubTask != null) {
            activeSubTask.execute(ctx);
            if (activeSubTask.getState().isTerminal()) {
                handleSubTaskComplete(ctx);
            }
            return;
        }

        // Execute current phase
        switch (phase) {
            case INIT:
                executeInit(ctx);
                break;
            case DETERMINE_POSITION:
                executeDeterminePosition(ctx);
                break;
            case NAVIGATE_TO_OBSTACLE:
                executeNavigateToObstacle(ctx);
                break;
            case INTERACT_OBSTACLE:
                executeInteractObstacle(ctx);
                break;
            case VERIFY_LANDING:
                executeVerifyLanding(ctx);
                break;
            case HANDLE_FAILURE:
                executeHandleFailure(ctx);
                break;
            case CHECK_GROUND_ITEMS:
                executeCheckGroundItems(ctx);
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
        startXp = ctx.getClient().getSkillExperience(Skill.AGILITY);
        log.info("AgilityCourseTask started: {}", config.getSummary());
        log.debug("Starting XP: {}, Course: {}", startXp, config.getCourse().getName());
    }

    // ========================================================================
    // Phase: Init
    // ========================================================================

    private void executeInit(TaskContext ctx) {
        // Basic initialization, transition to position determination
        currentObstacle = null;
        obstaclesThisLap = 0;
        phase = CoursePhase.DETERMINE_POSITION;
        phaseWaitTicks = 0;
    }

    // ========================================================================
    // Phase: Determine Position
    // ========================================================================

    private void executeDeterminePosition(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();
        AgilityCourse course = config.getCourse();

        // First, check for reachable marks of grace if enabled
        if (config.isPickupMarksOfGrace() && !player.isAnimating()) {
            Optional<GroundItemSnapshot> mark = findNearestMark(ctx);
            if (mark.isPresent()) {
                log.debug("Reachable mark of grace found at {} (plane {}), distance: {}",
                        mark.get().getWorldPosition(),
                        mark.get().getWorldPosition().getPlane(),
                        mark.get().distanceTo(playerPos));
                pendingMark = mark.get();
                phase = CoursePhase.PICKUP_ITEM;
                phaseWaitTicks = 0;
                return;
            }
            // Log if there are marks visible but not reachable (different plane)
            logUnreachableMarks(ctx);
        }

        // Determine which obstacle to do next
        Optional<AgilityObstacle> nextObstacle = course.determineNextObstacle(playerPos);

        if (nextObstacle.isPresent()) {
            currentObstacle = nextObstacle.get();
            log.debug("Next obstacle: {} (index {})", currentObstacle.getName(), currentObstacle.getIndex());

            // Check if we can interact from current position
            if (currentObstacle.canInteractFrom(playerPos)) {
                phase = CoursePhase.INTERACT_OBSTACLE;
            } else {
                phase = CoursePhase.NAVIGATE_TO_OBSTACLE;
            }
            phaseWaitTicks = 0;
        } else {
            // Can't determine next obstacle - might need to navigate to start
            log.debug("Can't determine next obstacle, checking if at start");

            if (!course.isAtStart(playerPos)) {
                // Need to walk to course start
                log.info("Not at course start, navigating to: {}", course.getStartArea());
                activeSubTask = new WalkToTask(course.getStartArea())
                        .withDescription("Walk to " + course.getName() + " start");
            } else {
                // At start but no obstacle detected - wait a bit
                phaseWaitTicks++;
                if (phaseWaitTicks > MAX_PHASE_WAIT_TICKS) {
                    log.warn("Timeout waiting for obstacle detection at start");
                    phaseWaitTicks = 0;
                }
            }
        }
    }

    // ========================================================================
    // Phase: Navigate to Obstacle
    // ========================================================================

    private void executeNavigateToObstacle(TaskContext ctx) {
        if (currentObstacle == null) {
            phase = CoursePhase.DETERMINE_POSITION;
            return;
        }

        // If no active sub-task, create one to walk to obstacle
        if (activeSubTask == null) {
            WorldPoint targetPos = currentObstacle.getExpectedLanding();
            if (targetPos == null && currentObstacle.getInteractArea() != null) {
                // Walk to center of interact area
                var area = currentObstacle.getInteractArea();
                targetPos = new WorldPoint(
                        area.getX() + area.getWidth() / 2,
                        area.getY() + area.getHeight() / 2,
                        area.getPlane()
                );
            }

            if (targetPos != null) {
                activeSubTask = new WalkToTask(targetPos)
                        .withDescription("Walk to " + currentObstacle.getName());
            } else {
                // No target position, try interacting anyway
                phase = CoursePhase.INTERACT_OBSTACLE;
            }
        }
    }

    // ========================================================================
    // Phase: Interact with Obstacle
    // ========================================================================

    private void executeInteractObstacle(TaskContext ctx) {
        if (currentObstacle == null) {
            phase = CoursePhase.DETERMINE_POSITION;
            return;
        }

        PlayerState player = ctx.getPlayerState();

        // If player is already animating, wait
        if (player.isAnimating()) {
            idleTicks = 0;
            return;
        }

        // If no active sub-task, create interaction task
        if (activeSubTask == null) {
            InteractObjectTask interactTask = new InteractObjectTask(
                    currentObstacle.getObjectId(),
                    currentObstacle.getAction()
            );

            // Add alternate object IDs if any
            List<Integer> alternates = currentObstacle.getAlternateIds();
            if (alternates != null && !alternates.isEmpty()) {
                interactTask.withAlternateIds(alternates);
            }

            // Set animation for success detection
            if (currentObstacle.getAnimationId() > 0) {
                interactTask.withSuccessAnimation(currentObstacle.getAnimationId());
            }

            activeSubTask = interactTask;
            log.debug("Interacting with obstacle: {} (obj={})",
                    currentObstacle.getName(), currentObstacle.getObjectId());
        }
    }

    // ========================================================================
    // Phase: Verify Landing
    // ========================================================================

    private void executeVerifyLanding(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Wait for player to stop moving/animating
        if (player.isAnimating() || player.isMoving()) {
            idleTicks = 0;
            phaseWaitTicks++;

            if (phaseWaitTicks > OBSTACLE_TIMEOUT_TICKS) {
                log.warn("Obstacle timeout waiting for landing");
                phase = CoursePhase.DETERMINE_POSITION;
                phaseWaitTicks = 0;
            }
            return;
        }

        // Player is idle, verify position
        idleTicks++;
        if (idleTicks < IDLE_CONFIRMATION_TICKS) {
            return;
        }

        // Check if at expected landing
        if (currentObstacle != null && currentObstacle.isAtLanding(playerPos)) {
            log.debug("Successfully completed obstacle: {}", currentObstacle.getName());
            obstaclesThisLap++;

            // Check if this was the last obstacle (lap complete)
            AgilityCourse course = config.getCourse();
            if (course.isAtEnd(playerPos)) {
                lapsCompleted++;
                obstaclesThisLap = 0;
                log.info("Lap {} complete! Total XP gained: {}",
                        lapsCompleted, getXpGained(ctx));
            }

            // Check for marks before moving on
            phase = CoursePhase.CHECK_GROUND_ITEMS;
            phaseWaitTicks = 0;
            idleTicks = 0;
            return;
        }

        // Check if at failure position
        if (currentObstacle != null && currentObstacle.isAtFailureLanding(playerPos)) {
            log.warn("Failed obstacle: {} - fell to {}", currentObstacle.getName(), playerPos);
            failures++;

            if (config.isStopOnFailure()) {
                fail("Fell from obstacle: " + currentObstacle.getName());
                return;
            }

            phase = CoursePhase.HANDLE_FAILURE;
            phaseWaitTicks = 0;
            return;
        }

        // Unknown position - go back to determine
        log.debug("At unexpected position {}, re-determining", playerPos);
        phase = CoursePhase.DETERMINE_POSITION;
        phaseWaitTicks = 0;
    }

    // ========================================================================
    // Phase: Handle Failure
    // ========================================================================

    private void executeHandleFailure(TaskContext ctx) {
        // After a fall, we need to get back to the course
        // For most rooftop courses, we'll be at the ground level
        // Just transition to determine position which will walk us back to start
        log.debug("Recovering from fall, returning to course");
        currentObstacle = null;
        phase = CoursePhase.DETERMINE_POSITION;
        phaseWaitTicks = 0;
    }

    // ========================================================================
    // Phase: Check Ground Items
    // ========================================================================

    private void executeCheckGroundItems(TaskContext ctx) {
        if (!config.isPickupMarksOfGrace()) {
            phase = CoursePhase.DETERMINE_POSITION;
            return;
        }

        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // findNearestMark already filters by plane and distance
        Optional<GroundItemSnapshot> mark = findNearestMark(ctx);
        if (mark.isPresent()) {
            log.debug("Found reachable mark of grace at {} (plane {}), distance: {}",
                    mark.get().getWorldPosition(),
                    mark.get().getWorldPosition().getPlane(),
                    mark.get().distanceTo(playerPos));
            pendingMark = mark.get();
            phase = CoursePhase.PICKUP_ITEM;
            phaseWaitTicks = 0;
            return;
        }

        // Log unreachable marks for debugging (marks on other planes we'll get later)
        logUnreachableMarks(ctx);

        // No reachable marks to pick up, continue with next obstacle
        phase = CoursePhase.DETERMINE_POSITION;
    }

    // ========================================================================
    // Phase: Pickup Item
    // ========================================================================

    private void executePickupItem(TaskContext ctx) {
        if (pendingMark == null) {
            phase = CoursePhase.DETERMINE_POSITION;
            return;
        }

        if (activeSubTask == null) {
            InventoryState inventory = ctx.getInventoryState();
            if (inventory.isFull()) {
                log.debug("Inventory full, skipping mark pickup");
                pendingMark = null;
                phase = CoursePhase.DETERMINE_POSITION;
                return;
            }

            activeSubTask = new PickupItemTask(MARK_OF_GRACE_ID, "Mark of grace")
                    .withLocation(pendingMark.getWorldPosition())
                    .withDescription("Pick up mark of grace");
            log.debug("Picking up mark at {}", pendingMark.getWorldPosition());
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
        }

        Task completedTask = activeSubTask;
        activeSubTask = null;

        // Determine next phase based on current phase and sub-task type
        switch (phase) {
            case NAVIGATE_TO_OBSTACLE:
                // Arrived at obstacle, interact
                phase = CoursePhase.INTERACT_OBSTACLE;
                break;

            case INTERACT_OBSTACLE:
                // Interaction complete, verify landing
                phase = CoursePhase.VERIFY_LANDING;
                idleTicks = 0;
                break;

            case PICKUP_ITEM:
                // Pickup complete
                if (subTaskState == TaskState.COMPLETED) {
                    marksCollected++;
                    log.info("Collected mark of grace #{}", marksCollected);
                }
                pendingMark = null;
                phase = CoursePhase.DETERMINE_POSITION;
                break;

            case DETERMINE_POSITION:
                // Walk to start complete
                phase = CoursePhase.DETERMINE_POSITION;
                break;

            default:
                phase = CoursePhase.DETERMINE_POSITION;
                break;
        }

        phaseWaitTicks = 0;
    }

    // ========================================================================
    // Mark of Grace Detection
    // ========================================================================

    /**
     * Tolerance for matching mark positions to spawn tiles (in tiles).
     * Marks may spawn slightly offset from the exact spawn tile coordinates.
     */
    private static final int MARK_SPAWN_TOLERANCE = 3;

    /**
     * Find the nearest reachable mark of grace on the ground.
     *
     * <p>A mark is considered reachable if:
     * <ul>
     *   <li>It's on the same plane (height level) as the player</li>
     *   <li>It's near a spawn tile associated with an obstacle we've already completed</li>
     *   <li>It's within the configured max pickup distance</li>
     * </ul>
     *
     * <p>On rooftop courses, multiple platforms share the same plane but aren't
     * walkable between each other. A mark on platform 2 is unreachable from platform 1,
     * even if both are on plane 3 and only 6 tiles apart. We use the course's mark
     * spawn tile data to determine which marks are actually reachable based on
     * which obstacles have been completed this lap.
     */
    private Optional<GroundItemSnapshot> findNearestMark(TaskContext ctx) {
        WorldState worldState = ctx.getWorldState();
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();
        AgilityCourse course = config.getCourse();

        // Calculate last completed obstacle index
        // obstaclesThisLap is incremented after completing each obstacle
        // So if obstaclesThisLap = 2, we've done obstacles 0 and 1, lastCompleted = 1
        int lastCompletedObstacleIndex = obstaclesThisLap - 1;

        // Filter marks to only those that are reachable based on course progress
        return worldState.getGroundItemsById(MARK_OF_GRACE_ID).stream()
                .filter(mark -> isMarkReachable(mark.getWorldPosition(), playerPos, lastCompletedObstacleIndex, course))
                .filter(mark -> mark.distanceTo(playerPos) <= config.getMaxMarkPickupDistance())
                .min((a, b) -> Integer.compare(a.distanceTo(playerPos), b.distanceTo(playerPos)));
    }

    /**
     * Check if a mark of grace at a given position is reachable from the player's position.
     *
     * <p>Reachability is determined by:
     * <ol>
     *   <li>Same plane check (basic - marks on different planes are obviously unreachable)</li>
     *   <li>Obstacle progression check - the mark must be near a spawn tile associated with
     *       an obstacle we've already completed (afterObstacle <= lastCompletedObstacleIndex)</li>
     * </ol>
     *
     * @param markPos the mark's position
     * @param playerPos the player's position
     * @param lastCompletedObstacleIndex the index of the last obstacle we completed (-1 if none)
     * @param course the agility course being run
     * @return true if the mark can be reached
     */
    private boolean isMarkReachable(WorldPoint markPos, WorldPoint playerPos, int lastCompletedObstacleIndex, AgilityCourse course) {
        // Basic plane check first
        if (markPos.getPlane() != playerPos.getPlane()) {
            return false;
        }

        // Use course data to determine if this mark is on a platform we can access
        // The course knows which spawn tiles are associated with which obstacles
        return course.isMarkReachable(markPos, lastCompletedObstacleIndex, MARK_SPAWN_TOLERANCE);
    }

    /**
     * Log any marks that are visible but not reachable (for debugging).
     * Called when no reachable marks are found but there might be marks on other platforms.
     */
    private void logUnreachableMarks(TaskContext ctx) {
        WorldState worldState = ctx.getWorldState();
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();
        AgilityCourse course = config.getCourse();
        int lastCompletedObstacleIndex = obstaclesThisLap - 1;

        List<GroundItemSnapshot> allMarks = worldState.getGroundItemsById(MARK_OF_GRACE_ID);
        List<GroundItemSnapshot> unreachableMarks = allMarks.stream()
                .filter(mark -> !isMarkReachable(mark.getWorldPosition(), playerPos, lastCompletedObstacleIndex, course))
                .collect(Collectors.toList());

        if (!unreachableMarks.isEmpty()) {
            log.trace("Found {} unreachable marks (completed {} obstacles, on plane {}): {}",
                    unreachableMarks.size(),
                    obstaclesThisLap,
                    playerPos.getPlane(),
                    unreachableMarks.stream()
                            .map(m -> {
                                int markObstacleIdx = course.getMarkObstacleIndex(m.getWorldPosition(), MARK_SPAWN_TOLERANCE);
                                return String.format("at %s (requires obstacle %d)", m.getWorldPosition(), markObstacleIdx);
                            })
                            .collect(Collectors.joining(", ")));
        }
    }

    // ========================================================================
    // Completion Checking
    // ========================================================================

    private boolean isTargetReached(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check target level
        if (config.hasTargetLevel()) {
            int currentLevel = client.getRealSkillLevel(Skill.AGILITY);
            if (currentLevel >= config.getTargetLevel()) {
                log.info("Target level {} reached!", config.getTargetLevel());
                return true;
            }
        }

        // Check target XP
        if (config.hasTargetXp() && startXp >= 0) {
            int xpGained = getXpGained(ctx);
            if (xpGained >= config.getTargetXp()) {
                log.info("Target XP {} reached!", config.getTargetXp());
                return true;
            }
        }

        // Check target laps
        if (config.hasTargetLaps()) {
            if (lapsCompleted >= config.getTargetLaps()) {
                log.info("Target {} laps reached!", config.getTargetLaps());
                return true;
            }
        }

        // Check target marks
        if (config.hasTargetMarks()) {
            if (marksCollected >= config.getTargetMarks()) {
                log.info("Target {} marks reached!", config.getTargetMarks());
                return true;
            }
        }

        // Check time limit
        if (config.hasMaxDuration() && startTime != null) {
            Duration elapsed = Duration.between(startTime, Instant.now());
            if (elapsed.compareTo(config.getMaxDuration()) >= 0) {
                log.info("Max duration {} reached!", config.getMaxDuration());
                return true;
            }
        }

        return false;
    }

    private void completeTask(TaskContext ctx) {
        Duration elapsed = startTime != null ? Duration.between(startTime, Instant.now()) : Duration.ZERO;
        int xpGained = getXpGained(ctx);

        log.info("AgilityCourseTask completed: {} - {} XP gained, {} laps, {} marks in {}",
                config.getCourse().getName(),
                String.format("%,d", xpGained),
                lapsCompleted,
                marksCollected,
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
        int currentXp = ctx.getClient().getSkillExperience(Skill.AGILITY);
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
        log.warn("AgilityCourseTask failed after {} laps, {} marks collected", lapsCompleted, marksCollected);
    }

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("AgilityCourse[%s, lap %d]",
                config.getCourse().getName(), lapsCompleted + 1);
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    /**
     * Phases of agility course execution.
     */
    private enum CoursePhase {
        /**
         * Initial setup.
         */
        INIT,

        /**
         * Determine current position and next obstacle.
         */
        DETERMINE_POSITION,

        /**
         * Navigate to the next obstacle's interaction area.
         */
        NAVIGATE_TO_OBSTACLE,

        /**
         * Interact with the current obstacle.
         */
        INTERACT_OBSTACLE,

        /**
         * Verify player landed at expected position.
         */
        VERIFY_LANDING,

        /**
         * Handle failure (fall from obstacle).
         */
        HANDLE_FAILURE,

        /**
         * Check for marks of grace to pick up.
         */
        CHECK_GROUND_ITEMS,

        /**
         * Pick up a mark of grace.
         */
        PICKUP_ITEM
    }
}

