package com.rocinante.tasks;

import com.rocinante.navigation.ObstacleHandler;
import com.rocinante.navigation.PathFinder;
import com.rocinante.navigation.Reachability;
import com.rocinante.tasks.impl.InteractObjectTask;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Abstract base class for tasks that involve interacting with an entity (Object or NPC).
 *
 * <p>Provides common functionality for:
 * <ul>
 *   <li>Path validation and obstacle detection (doors/gates)</li>
 *   <li>Obstacle handling task management</li>
 *   <li>Camera rotation</li>
 *   <li>Common interaction phases</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractInteractionTask extends AbstractTask {

    /**
     * Obstacle handling state.
     */
    protected ObstacleHandler.DetectedObstacle pendingObstacle;
    protected InteractObjectTask obstacleTask;

    /**
     * Cached path to the target.
     */
    protected List<WorldPoint> currentPath = Collections.emptyList();

    /**
     * Interaction helper for camera rotation and clickbox handling.
     * Provides centralized, human-like interaction behavior.
     */
    protected InteractionHelper interactionHelper;

    /**
     * Check if the path to the target contains any blocking obstacles (doors/gates).
     * If so, switches phase to HANDLE_OBSTACLE and returns true.
     *
     * @param ctx the task context
     * @param start the starting position
     * @param target the target position
     * @return true if an obstacle was found and handling initiated
     */
    protected boolean checkPathForObstacles(TaskContext ctx, WorldPoint start, WorldPoint target) {
        if (start == null || target == null) {
            return false;
        }

        // Calculate path if not already available or valid for current positions
        if (currentPath.isEmpty() || !isPathValidFor(start, target)) {
            PathFinder pathFinder = ctx.getPathFinder();
            if (pathFinder == null) {
                return false;
            }
            // Use A* to find path (ignoring obstacles to see what blocks us)
            // Note: We use 'true' for ignoreCache to ensure fresh check
            currentPath = pathFinder.findPath(start, target, true);
        }

        if (currentPath.isEmpty()) {
            return false;
        }

        ObstacleHandler obstacleHandler = ctx.getObstacleHandler();
        if (obstacleHandler == null) {
            return false;
        }

        // Check path segments for obstacles
        WorldPoint previous = start;
        for (WorldPoint point : currentPath) {
            if (previous != null) {
                Optional<ObstacleHandler.DetectedObstacle> obstacle = 
                        obstacleHandler.findBlockingObstacle(previous, point);
                
                if (obstacle.isPresent()) {
                    pendingObstacle = obstacle.get();
                    log.debug("Found blocking obstacle in path: {} at {}", 
                            pendingObstacle.getDefinition().getName(), pendingObstacle.getLocation());
                    
                    // Signal to subclass that obstacle handling is needed
                    return true;
                }
            }
            previous = point;
        }

        return false;
    }

    /**
     * Verify if the current path is still valid for the given start and target.
     */
    private boolean isPathValidFor(WorldPoint start, WorldPoint target) {
        if (currentPath.isEmpty()) {
            return false;
        }
        // Check if path starts near start and ends near target
        WorldPoint pathStart = currentPath.get(0);
        WorldPoint pathEnd = currentPath.get(currentPath.size() - 1);
        
        return pathStart.distanceTo(start) <= 2 && pathEnd.distanceTo(target) <= 2;
    }

    /**
     * Execute logic to handle a detected obstacle.
     * Should be called when the task is in the obstacle handling phase.
     *
     * @param ctx the task context
     * @return true if obstacle handling is in progress, false if completed or failed
     */
    protected boolean executeHandleObstacleImpl(TaskContext ctx) {
        if (pendingObstacle == null) {
            return false;
        }

        // Create obstacle handling task if needed
        if (obstacleTask == null) {
            ObstacleHandler obstacleHandler = ctx.getObstacleHandler();
            if (obstacleHandler == null) {
                log.warn("ObstacleHandler not available to handle {}", pendingObstacle.getDefinition().getName());
                pendingObstacle = null;
                return false;
            }

            Optional<InteractObjectTask> taskOpt = obstacleHandler.createHandleTask(pendingObstacle);
            if (taskOpt.isEmpty()) {
                log.warn("Could not create task for obstacle: {}", pendingObstacle.getDefinition().getName());
                pendingObstacle = null;
                return false;
            }
            obstacleTask = taskOpt.get();
            log.debug("Created obstacle task: {}", obstacleTask.getDescription());
        }

        // Execute the obstacle task
        TaskState taskState = obstacleTask.getState();
        if (taskState != TaskState.COMPLETED && taskState != TaskState.FAILED) {
            obstacleTask.execute(ctx);
            return true;
        }

        if (taskState == TaskState.COMPLETED) {
            log.debug("Successfully handled obstacle: {}", pendingObstacle.getDefinition().getName());
            
            // Clear obstacle state
            pendingObstacle = null;
            obstacleTask = null;
            currentPath = Collections.emptyList(); // Clear path to force re-evaluation
            
            // Wait a moment for game state to update (door opening animation)
            ctx.getHumanTimer().sleep(ctx.getRandomization().uniformRandomLong(300, 600));
            return false; // Handling complete
        }

        // Failed to handle obstacle
        log.warn("Failed to handle obstacle: {}", pendingObstacle.getDefinition().getName());
        pendingObstacle = null;
        obstacleTask = null;
        return false; // Handling failed (but we're done trying)
    }

    /**
     * Initialize interaction helper if needed.
     */
    protected void ensureInteractionHelper(TaskContext ctx) {
        if (interactionHelper == null) {
            interactionHelper = new InteractionHelper(ctx);
        }
    }

    /**
     * Execute camera rotation to the target.
     *
     * @param ctx the task context
     * @param target the target position
     */
    protected void rotateCameraTo(TaskContext ctx, WorldPoint target) {
        ensureInteractionHelper(ctx);
        interactionHelper.startCameraRotation(target);
    }
}

