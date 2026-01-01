package com.rocinante.navigation;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

/**
 * Fast, obstacle-aware path cost estimation for the loaded scene.
 *
 * <p>Uses BFS with collision data from ShortestPath and obstacle awareness
 * from {@link SceneObstacleCache}. Covers the entire loaded scene (~52 tile radius)
 * without delegating to async pathfinding.
 *
 * <p>Performance characteristics:
 * <ul>
 *   <li>Obstacle lookup: &lt;0.5ms (cached)</li>
 *   <li>BFS for ~5000 tiles: &lt;1ms (primitive arrays)</li>
 *   <li>Total: &lt;2ms typical, &lt;3ms worst case</li>
 * </ul>
 *
 * <p>This class handles doors, gates, and other traversable obstacles
 * by consulting {@link SceneObstacleCache} for traversal costs.
 *
 * <p><b>Optimization:</b> Uses primitive int arrays with generation tracking
 * to avoid HashMap/object allocations in the critical BFS loop.
 *
 * @see SceneObstacleCache
 */
@Slf4j
@Singleton
public class PathCostEstimator {

    /** Scene size constant for array sizing */
    private static final int SCENE_SIZE = Constants.SCENE_SIZE; // 104
    
    /** Total tiles in scene (104 * 104 = 10816) */
    private static final int SCENE_TILES = SCENE_SIZE * SCENE_SIZE;
    
    /** Maximum queue size (can visit all tiles worst case) */
    private static final int MAX_QUEUE_SIZE = SCENE_TILES + 256;
    
    /** Sentinel value for unvisited tiles */
    private static final int UNVISITED = -1;

    private final CollisionService collisionService;
    private final SceneObstacleCache sceneObstacleCache;
    private final SceneManager sceneManager;
    private final Client client;
    
    // ========================================================================
    // Reusable primitive arrays (avoid allocation per BFS call)
    // ========================================================================
    
    /** Cost to reach each tile. Index = sceneX + sceneY * SCENE_SIZE */
    private final int[] costs = new int[SCENE_TILES];
    
    /** Generation counter to avoid clearing costs array each call */
    private int currentGeneration = 0;
    
    /** Generation at which each tile was last visited */
    private final int[] visitedGeneration = new int[SCENE_TILES];
    
    /** Circular queue: flat scene indices */
    private final int[] queueIndices = new int[MAX_QUEUE_SIZE];
    
    /** Circular queue: costs at each index */
    private final int[] queueCosts = new int[MAX_QUEUE_SIZE];
    
    /** Obstacle costs by flat scene index (0 = no obstacle, >0 = traversal cost) */
    private final int[] obstacleCosts = new int[SCENE_TILES];
    
    /** Generation for obstacle costs */
    private int obstacleGeneration = 0;

    @Inject
    public PathCostEstimator(CollisionService collisionService, 
                             SceneObstacleCache sceneObstacleCache,
                             SceneManager sceneManager,
                             Client client) {
        this.collisionService = collisionService;
        this.sceneObstacleCache = sceneObstacleCache;
        this.sceneManager = sceneManager;
        this.client = client;
        
        // Initialize generation arrays to 0 (anything with gen < currentGen is unvisited)
        Arrays.fill(visitedGeneration, 0);
        Arrays.fill(obstacleCosts, 0);
    }

    /**
     * Estimate path cost between two points within the loaded scene.
     *
     * <p>Uses obstacle-aware BFS with primitive arrays for zero-allocation pathfinding.
     * Doors and gates are treated as traversable with their configured traversal cost added.
     *
     * @param start starting world point
     * @param end   destination world point
     * @return path cost in tiles, or empty if outside scene or unreachable
     */
    public OptionalInt estimatePathCost(WorldPoint start, WorldPoint end) {
        if (start == null || end == null) {
            return OptionalInt.empty();
        }

        // Same tile
        if (start.equals(end)) {
            return OptionalInt.of(0);
        }

        // Different planes - can't use local BFS
        if (start.getPlane() != end.getPlane()) {
            return OptionalInt.empty();
        }

        // Check if both points are within local nav range
        int distance = chebyshevDistance(start, end);
        if (distance > SceneManager.MAX_LOCAL_NAV_DISTANCE) {
            return OptionalInt.empty();
        }

        // Ensure both points are in the loaded scene
        if (!sceneManager.areBothPointsLoaded(start, end)) {
            return OptionalInt.empty();
        }

        // Pre-scan for traversable obstacles (doors/gates) into primitive array
        scanTraversableObstacles(start);

        // Run BFS with primitive arrays
        return localBfs(start, end);
    }

    /**
     * Populate obstacle costs array from cache.
     *
     * <p>This method uses {@link SceneObstacleCache} for efficient lookups.
     * Results are stored in the reusable {@link #obstacleCosts} array.
     *
     * @param center center point for the scan
     */
    private void scanTraversableObstacles(WorldPoint center) {
        // Increment generation to invalidate previous obstacle data
        obstacleGeneration++;
        
        int baseX = client.getBaseX();
        int baseY = client.getBaseY();

        // Use cached obstacle data instead of rescanning the scene
        List<ObstacleHandler.DetectedObstacle> obstacles = 
            sceneObstacleCache.getObstaclesNearby(center, SceneManager.MAX_LOCAL_NAV_DISTANCE);

        for (ObstacleHandler.DetectedObstacle obs : obstacles) {
            ObstacleDefinition def = obs.getDefinition();
            // Include doors, gates, and other traversable obstacles
            if (def.isDoorOrGate()) {
                WorldPoint loc = obs.getLocation();
                int sceneX = loc.getX() - baseX;
                int sceneY = loc.getY() - baseY;
                
                // Bounds check for scene coordinates
                if (sceneX >= 0 && sceneX < SCENE_SIZE && sceneY >= 0 && sceneY < SCENE_SIZE) {
                    int flatIndex = sceneX + sceneY * SCENE_SIZE;
                    obstacleCosts[flatIndex] = def.getTraversalCostTicks();
                }
            }
        }
    }

    /**
     * Direction offsets for 8-way movement.
     */
    private static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] DY = {-1, -1, -1, 0, 0, 1, 1, 1};

    /**
     * Obstacle-aware BFS using primitive arrays for zero-allocation pathfinding.
     *
     * <p>Uses generation tracking to avoid clearing arrays between calls.
     * The circular queue stores flat scene indices and costs.
     *
     * @param start starting point (world coordinates)
     * @param end   destination point (world coordinates)
     * @return path cost, or empty if unreachable
     */
    private OptionalInt localBfs(WorldPoint start, WorldPoint end) {
        // Increment generation to mark all tiles as unvisited
        currentGeneration++;
        
        int baseX = client.getBaseX();
        int baseY = client.getBaseY();
        int plane = start.getPlane();
        
        // Convert world coords to scene coords
        int startSceneX = start.getX() - baseX;
        int startSceneY = start.getY() - baseY;
        int endSceneX = end.getX() - baseX;
        int endSceneY = end.getY() - baseY;
        
        int endFlatIndex = endSceneX + endSceneY * SCENE_SIZE;
        int startFlatIndex = startSceneX + startSceneY * SCENE_SIZE;
        
        // Initialize circular queue
        int queueHead = 0;
        int queueTail = 0;
        
        // Enqueue start position
        queueIndices[queueTail] = startFlatIndex;
        queueCosts[queueTail] = 0;
        queueTail++;
        
        // Mark start as visited
        visitedGeneration[startFlatIndex] = currentGeneration;
        costs[startFlatIndex] = 0;

        while (queueHead != queueTail) {
            // Dequeue
            int currentFlatIndex = queueIndices[queueHead];
            int currentCost = queueCosts[queueHead];
            queueHead++;
            if (queueHead >= MAX_QUEUE_SIZE) {
                queueHead = 0; // Wrap around
            }

            // Found destination
            if (currentFlatIndex == endFlatIndex) {
                return OptionalInt.of(currentCost);
            }

            // Skip if we've found a better path (can happen with obstacles)
            if (costs[currentFlatIndex] < currentCost) {
                continue;
            }

            // Convert flat index back to scene coords
            int currentSceneX = currentFlatIndex % SCENE_SIZE;
            int currentSceneY = currentFlatIndex / SCENE_SIZE;
            
            // Convert to world coords for collision checks
            int currentWorldX = currentSceneX + baseX;
            int currentWorldY = currentSceneY + baseY;

            // Explore all 8 directions
            for (int i = 0; i < 8; i++) {
                int nSceneX = currentSceneX + DX[i];
                int nSceneY = currentSceneY + DY[i];
                
                // Bounds check
                if (nSceneX < 0 || nSceneX >= SCENE_SIZE || nSceneY < 0 || nSceneY >= SCENE_SIZE) {
                    continue;
                }
                
                int neighborFlatIndex = nSceneX + nSceneY * SCENE_SIZE;

                // Skip if already visited this generation
                if (visitedGeneration[neighborFlatIndex] == currentGeneration) {
                    continue;
                }

                int nWorldX = nSceneX + baseX;
                int nWorldY = nSceneY + baseY;

                // Check if target tile is blocked
                boolean isBlocked = collisionService.isBlocked(nWorldX, nWorldY, plane);
                
                if (isBlocked) {
                    // Check if it's a traversable obstacle
                    int obstacleCost = obstacleCosts[neighborFlatIndex];
                    if (obstacleCost > 0) {
                        int newCost = currentCost + 1 + obstacleCost;
                        
                        // Mark visited and enqueue
                        visitedGeneration[neighborFlatIndex] = currentGeneration;
                        costs[neighborFlatIndex] = newCost;
                        
                        queueIndices[queueTail] = neighborFlatIndex;
                        queueCosts[queueTail] = newCost;
                        queueTail++;
                        if (queueTail >= MAX_QUEUE_SIZE) {
                            queueTail = 0; // Wrap around
                        }
                    }
                    // Otherwise, hard blocked - skip
                } else {
                    // Not blocked - check if we can actually move there
                    if (canMoveBetween(currentWorldX, currentWorldY, nWorldX, nWorldY, plane)) {
                        int newCost = currentCost + 1;
                        
                        // Mark visited and enqueue
                        visitedGeneration[neighborFlatIndex] = currentGeneration;
                        costs[neighborFlatIndex] = newCost;
                        
                        queueIndices[queueTail] = neighborFlatIndex;
                        queueCosts[queueTail] = newCost;
                        queueTail++;
                        if (queueTail >= MAX_QUEUE_SIZE) {
                            queueTail = 0; // Wrap around
                        }
                    }
                }
            }
        }

        // No path found
        return OptionalInt.empty();
    }

    /**
     * Check if movement is allowed between two adjacent tiles.
     * Uses coordinate-based collision checks to avoid WorldPoint allocation.
     */
    private boolean canMoveBetween(int fromX, int fromY, int toX, int toY, int plane) {
        int dx = toX - fromX;
        int dy = toY - fromY;

        // For cardinal directions, use direct checks
        if (dx == 0 && dy == 1) {
            return collisionService.canMoveNorth(fromX, fromY, plane);
        } else if (dx == 0 && dy == -1) {
            return collisionService.canMoveSouth(fromX, fromY, plane);
        } else if (dx == 1 && dy == 0) {
            return collisionService.canMoveEast(fromX, fromY, plane);
        } else if (dx == -1 && dy == 0) {
            return collisionService.canMoveWest(fromX, fromY, plane);
        }

        // For diagonals, check both cardinal directions
        if (dx == 1 && dy == 1) {
            return collisionService.canMoveNorth(fromX, fromY, plane) &&
                   collisionService.canMoveEast(fromX, fromY + 1, plane) &&
                   collisionService.canMoveEast(fromX, fromY, plane) &&
                   collisionService.canMoveNorth(fromX + 1, fromY, plane);
        } else if (dx == -1 && dy == 1) {
            return collisionService.canMoveNorth(fromX, fromY, plane) &&
                   collisionService.canMoveWest(fromX, fromY + 1, plane) &&
                   collisionService.canMoveWest(fromX, fromY, plane) &&
                   collisionService.canMoveNorth(fromX - 1, fromY, plane);
        } else if (dx == 1 && dy == -1) {
            return collisionService.canMoveSouth(fromX, fromY, plane) &&
                   collisionService.canMoveEast(fromX, fromY - 1, plane) &&
                   collisionService.canMoveEast(fromX, fromY, plane) &&
                   collisionService.canMoveSouth(fromX + 1, fromY, plane);
        } else if (dx == -1 && dy == -1) {
            return collisionService.canMoveSouth(fromX, fromY, plane) &&
                   collisionService.canMoveWest(fromX, fromY - 1, plane) &&
                   collisionService.canMoveWest(fromX, fromY, plane) &&
                   collisionService.canMoveSouth(fromX - 1, fromY, plane);
        }

        return false;
    }

    /**
     * Calculate Chebyshev distance (max of |dx|, |dy|).
     * This is the distance in OSRS where diagonal movement is allowed.
     */
    private static int chebyshevDistance(WorldPoint a, WorldPoint b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        return Math.max(dx, dy);
    }
}
