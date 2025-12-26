package com.rocinante.navigation;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

/**
 * A* pathfinding on the tile grid using RuneLite's collision data.
 * Per REQUIREMENTS.md Section 7.1.
 *
 * <p>Features:
 * <ul>
 *   <li>A* algorithm on tile grid</li>
 *   <li>Collision data sourced from client's collision maps</li>
 *   <li>Support for blocked tiles, water, obstacles</li>
 *   <li>Path caching for unchanged start/end</li>
 *   <li>Maximum path length: 100 tiles (longer paths delegate to WebWalker)</li>
 *   <li>Diagonal movement support where collision allows</li>
 * </ul>
 */
@Slf4j
@Singleton
public class PathFinder {

    /**
     * Maximum path length in tiles.
     * Paths longer than this should use WebWalker instead.
     */
    public static final int MAX_PATH_LENGTH = 100;

    /**
     * Maximum number of iterations before giving up.
     * Prevents infinite loops on impossible paths.
     */
    private static final int MAX_ITERATIONS = 5000;

    /**
     * Movement directions: N, NE, E, SE, S, SW, W, NW
     */
    private static final int[][] DIRECTIONS = {
            {0, 1},   // N
            {1, 1},   // NE
            {1, 0},   // E
            {1, -1},  // SE
            {0, -1},  // S
            {-1, -1}, // SW
            {-1, 0},  // W
            {-1, 1}   // NW
    };

    /**
     * Cardinal direction indices (N, E, S, W).
     */
    private static final int[] CARDINAL_INDICES = {0, 2, 4, 6};

    /**
     * Diagonal direction indices (NE, SE, SW, NW).
     */
    private static final int[] DIAGONAL_INDICES = {1, 3, 5, 7};

    private final Client client;

    // ========================================================================
    // Path Caching
    // ========================================================================

    /**
     * Cached path result.
     */
    @Getter
    private CachedPath cachedPath;

    @Inject
    public PathFinder(Client client) {
        this.client = client;
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Find a path from start to end using A* algorithm.
     *
     * @param start the starting world point
     * @param end   the destination world point
     * @return list of world points forming the path (empty if no path found)
     */
    public List<WorldPoint> findPath(WorldPoint start, WorldPoint end) {
        return findPath(start, end, false);
    }

    /**
     * Find a path from start to end using A* algorithm.
     *
     * @param start         the starting world point
     * @param end           the destination world point
     * @param ignoreCache   if true, bypass the cache
     * @return list of world points forming the path (empty if no path found)
     */
    public List<WorldPoint> findPath(WorldPoint start, WorldPoint end, boolean ignoreCache) {
        if (start == null || end == null) {
            log.warn("PathFinder: null start or end point");
            return Collections.emptyList();
        }

        // Different planes - cannot path directly
        if (start.getPlane() != end.getPlane()) {
            log.debug("PathFinder: different planes ({} vs {}), cannot path directly",
                    start.getPlane(), end.getPlane());
            return Collections.emptyList();
        }

        // Check cache
        if (!ignoreCache && cachedPath != null &&
                cachedPath.start.equals(start) && cachedPath.end.equals(end)) {
            log.trace("PathFinder: returning cached path");
            return cachedPath.path;
        }

        // Check straight-line distance
        int distance = chebyshevDistance(start, end);
        if (distance > MAX_PATH_LENGTH) {
            log.debug("PathFinder: distance {} exceeds max {}, should use WebWalker",
                    distance, MAX_PATH_LENGTH);
            return Collections.emptyList();
        }

        // Already at destination
        if (distance == 0) {
            return Collections.singletonList(start);
        }

        // Run A* algorithm
        List<WorldPoint> path = runAStar(start, end);

        // Cache the result
        cachedPath = new CachedPath(start, end, path);

        if (path.isEmpty()) {
            log.debug("PathFinder: no path found from {} to {}", start, end);
        } else {
            log.debug("PathFinder: found path with {} tiles from {} to {}",
                    path.size(), start, end);
        }

        return path;
    }

    /**
     * Check if a direct path exists between two points.
     *
     * @param start the starting point
     * @param end   the ending point
     * @return true if a path exists
     */
    public boolean hasPath(WorldPoint start, WorldPoint end) {
        return !findPath(start, end).isEmpty();
    }

    /**
     * Check if a tile is walkable at the given position.
     *
     * @param point the world point to check
     * @return true if the tile is walkable
     */
    public boolean isWalkable(WorldPoint point) {
        CollisionData[] collisionData = client.getCollisionMaps();
        if (collisionData == null) {
            return false;
        }

        int plane = point.getPlane();
        if (plane < 0 || plane >= collisionData.length || collisionData[plane] == null) {
            return false;
        }

        // Convert to scene coordinates
        LocalPoint local = LocalPoint.fromWorld(client, point);
        if (local == null) {
            // Point is outside the loaded scene
            return false;
        }

        int sceneX = local.getSceneX();
        int sceneY = local.getSceneY();

        if (!isInScene(sceneX, sceneY)) {
            return false;
        }

        int[][] flags = collisionData[plane].getFlags();
        int flag = flags[sceneX][sceneY];

        return !isBlocked(flag);
    }

    /**
     * Invalidate the cached path.
     */
    public void invalidateCache() {
        cachedPath = null;
    }

    // ========================================================================
    // A* Implementation
    // ========================================================================

    private List<WorldPoint> runAStar(WorldPoint start, WorldPoint end) {
        CollisionData[] collisionData = client.getCollisionMaps();
        if (collisionData == null) {
            log.warn("PathFinder: collision data not available");
            return Collections.emptyList();
        }

        int plane = start.getPlane();
        if (plane < 0 || plane >= collisionData.length || collisionData[plane] == null) {
            log.warn("PathFinder: collision data not available for plane {}", plane);
            return Collections.emptyList();
        }

        int[][] flags = collisionData[plane].getFlags();

        // Convert to scene coordinates
        LocalPoint startLocal = LocalPoint.fromWorld(client, start);
        LocalPoint endLocal = LocalPoint.fromWorld(client, end);

        if (startLocal == null || endLocal == null) {
            log.debug("PathFinder: start or end outside loaded scene");
            return Collections.emptyList();
        }

        int startX = startLocal.getSceneX();
        int startY = startLocal.getSceneY();
        int endX = endLocal.getSceneX();
        int endY = endLocal.getSceneY();

        // Validate scene bounds
        if (!isInScene(startX, startY) || !isInScene(endX, endY)) {
            log.debug("PathFinder: start or end outside scene bounds");
            return Collections.emptyList();
        }

        // A* data structures
        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingInt(n -> n.fScore));
        Map<Long, Node> allNodes = new HashMap<>();
        Set<Long> closedSet = new HashSet<>();

        // Initialize start node
        Node startNode = new Node(startX, startY);
        startNode.gScore = 0;
        startNode.fScore = heuristic(startX, startY, endX, endY);
        openSet.add(startNode);
        allNodes.put(nodeKey(startX, startY), startNode);

        int iterations = 0;

        while (!openSet.isEmpty() && iterations < MAX_ITERATIONS) {
            iterations++;

            Node current = openSet.poll();

            // Check if we reached the goal
            if (current.x == endX && current.y == endY) {
                return reconstructPath(current, start.getPlane());
            }

            long currentKey = nodeKey(current.x, current.y);
            if (closedSet.contains(currentKey)) {
                continue;
            }
            closedSet.add(currentKey);

            // Check all neighbors
            for (int i = 0; i < DIRECTIONS.length; i++) {
                int dx = DIRECTIONS[i][0];
                int dy = DIRECTIONS[i][1];
                int nx = current.x + dx;
                int ny = current.y + dy;

                // Check scene bounds
                if (!isInScene(nx, ny)) {
                    continue;
                }

                long neighborKey = nodeKey(nx, ny);
                if (closedSet.contains(neighborKey)) {
                    continue;
                }

                // Check if movement is blocked
                if (!canMove(flags, current.x, current.y, dx, dy)) {
                    continue;
                }

                // Calculate tentative g score
                // Diagonal moves cost more (sqrt(2) ≈ 1.41, use 14 vs 10)
                int moveCost = (dx != 0 && dy != 0) ? 14 : 10;
                int tentativeG = current.gScore + moveCost;

                Node neighbor = allNodes.get(neighborKey);
                if (neighbor == null) {
                    neighbor = new Node(nx, ny);
                    allNodes.put(neighborKey, neighbor);
                }

                if (tentativeG < neighbor.gScore) {
                    neighbor.parent = current;
                    neighbor.gScore = tentativeG;
                    neighbor.fScore = tentativeG + heuristic(nx, ny, endX, endY);

                    // Add to open set if not already there
                    if (!openSet.contains(neighbor)) {
                        openSet.add(neighbor);
                    }
                }
            }
        }

        if (iterations >= MAX_ITERATIONS) {
            log.warn("PathFinder: max iterations reached, path search abandoned");
        }

        return Collections.emptyList();
    }

    /**
     * Check if movement from (x,y) in direction (dx,dy) is allowed.
     */
    private boolean canMove(int[][] flags, int x, int y, int dx, int dy) {
        int destX = x + dx;
        int destY = y + dy;

        // Check destination is walkable
        if (isBlocked(flags[destX][destY])) {
            return false;
        }

        // For cardinal directions, just check destination
        if (dx == 0 || dy == 0) {
            return !isBlockedDirection(flags[x][y], dx, dy);
        }

        // For diagonal moves, check that cardinal components are not blocked
        // and the diagonal itself isn't blocked

        // Check the two adjacent cardinal tiles
        int cardinalX = x + dx;
        int cardinalY = y;
        if (isBlocked(flags[cardinalX][cardinalY])) {
            return false;
        }

        cardinalX = x;
        cardinalY = y + dy;
        if (isBlocked(flags[cardinalX][cardinalY])) {
            return false;
        }

        // Check directional blocking flags
        return !isBlockedDirection(flags[x][y], dx, dy) &&
               !isBlockedDiagonal(flags, x, y, dx, dy);
    }

    /**
     * Check if a tile is fully blocked.
     */
    private boolean isBlocked(int flag) {
        // Check for full blocking
        return (flag & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0;
    }

    /**
     * Check if movement in a direction is blocked by flags.
     */
    private boolean isBlockedDirection(int flag, int dx, int dy) {
        // Check directional blocking based on direction
        if (dx == 0 && dy == 1) {
            return (flag & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0;
        }
        if (dx == 0 && dy == -1) {
            return (flag & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0;
        }
        if (dx == 1 && dy == 0) {
            return (flag & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0;
        }
        if (dx == -1 && dy == 0) {
            return (flag & CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0;
        }

        // Diagonal directions
        if (dx == 1 && dy == 1) {
            return (flag & CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST) != 0;
        }
        if (dx == 1 && dy == -1) {
            return (flag & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST) != 0;
        }
        if (dx == -1 && dy == -1) {
            return (flag & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST) != 0;
        }
        if (dx == -1 && dy == 1) {
            return (flag & CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST) != 0;
        }

        return false;
    }

    /**
     * Additional diagonal blocking check.
     */
    private boolean isBlockedDiagonal(int[][] flags, int x, int y, int dx, int dy) {
        // For diagonal movement, also check if the cardinal tiles block diagonal passage
        // E.g., moving NE requires both N and E tiles to allow diagonal exit

        // Check the tile in the X direction
        int adjX = x + dx;
        if (dy > 0) {
            if ((flags[adjX][y] & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0) {
                return true;
            }
        } else {
            if ((flags[adjX][y] & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0) {
                return true;
            }
        }

        // Check the tile in the Y direction
        int adjY = y + dy;
        if (dx > 0) {
            if ((flags[x][adjY] & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0) {
                return true;
            }
        } else {
            if ((flags[x][adjY] & CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Reconstruct path from goal node back to start.
     */
    private List<WorldPoint> reconstructPath(Node goal, int plane) {
        List<WorldPoint> path = new ArrayList<>();
        Node current = goal;

        while (current != null) {
            // Convert scene coordinates back to world coordinates
            WorldPoint worldPoint = sceneToWorld(current.x, current.y, plane);
            if (worldPoint != null) {
                path.add(worldPoint);
            }
            current = current.parent;
        }

        // Reverse to get start -> end order
        Collections.reverse(path);
        return path;
    }

    /**
     * Convert scene coordinates to world point.
     */
    private WorldPoint sceneToWorld(int sceneX, int sceneY, int plane) {
        int baseX = client.getBaseX();
        int baseY = client.getBaseY();
        return new WorldPoint(baseX + sceneX, baseY + sceneY, plane);
    }

    /**
     * Heuristic function for A* (Chebyshev distance * 10).
     */
    private int heuristic(int x1, int y1, int x2, int y2) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        // Chebyshev distance with diagonal cost adjustment
        return 10 * Math.max(dx, dy) + 4 * Math.min(dx, dy);
    }

    /**
     * Chebyshev distance between two world points.
     */
    private int chebyshevDistance(WorldPoint a, WorldPoint b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    /**
     * Check if coordinates are within the scene.
     */
    private boolean isInScene(int sceneX, int sceneY) {
        return sceneX >= 0 && sceneX < Constants.SCENE_SIZE &&
               sceneY >= 0 && sceneY < Constants.SCENE_SIZE;
    }

    /**
     * Create a unique key for node coordinates.
     */
    private long nodeKey(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    /**
     * A* search node.
     */
    private static class Node {
        final int x;
        final int y;
        Node parent;
        int gScore = Integer.MAX_VALUE;
        int fScore = Integer.MAX_VALUE;

        Node(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Node node = (Node) o;
            return x == node.x && y == node.y;
        }

        @Override
        public int hashCode() {
            return 31 * x + y;
        }
    }

    /**
     * Cached path result.
     */
    @Getter
    public static class CachedPath {
        private final WorldPoint start;
        private final WorldPoint end;
        private final List<WorldPoint> path;
        private final long timestamp;

        CachedPath(WorldPoint start, WorldPoint end, List<WorldPoint> path) {
            this.start = start;
            this.end = end;
            this.path = Collections.unmodifiableList(new ArrayList<>(path));
            this.timestamp = System.currentTimeMillis();
        }
    }
}

