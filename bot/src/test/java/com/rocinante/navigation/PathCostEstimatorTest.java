package com.rocinante.navigation;

import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for PathCostEstimator BFS pathfinding and performance.
 */
public class PathCostEstimatorTest {

    private PathCostEstimator estimator;
    private CollisionService collisionService;
    private SceneObstacleCache sceneObstacleCache;
    private SceneManager sceneManager;
    private Client client;

    @Before
    public void setUp() {
        collisionService = mock(CollisionService.class);
        sceneObstacleCache = mock(SceneObstacleCache.class);
        sceneManager = mock(SceneManager.class);
        client = mock(Client.class);
        
        // Default base coordinates - offset from world coords 3200,3200
        when(client.getBaseX()).thenReturn(3150);
        when(client.getBaseY()).thenReturn(3150);
        
        estimator = new PathCostEstimator(collisionService, sceneObstacleCache, sceneManager, client);
        
        // Default: no obstacles
        when(sceneObstacleCache.getObstaclesNearby(any(), anyInt())).thenReturn(Collections.emptyList());
    }

    /**
     * Simple collision service stub for performance testing - no Mockito overhead.
     */
    private static class OpenFieldCollisionService extends CollisionService {
        OpenFieldCollisionService() {
            super(null); // No bridge needed for stub
        }
        
        @Override
        public boolean isBlocked(int x, int y, int z) { return false; }
        @Override
        public boolean isBlocked(WorldPoint point) { return false; }
        @Override
        public boolean canMoveNorth(int x, int y, int z) { return true; }
        @Override
        public boolean canMoveSouth(int x, int y, int z) { return true; }
        @Override
        public boolean canMoveEast(int x, int y, int z) { return true; }
        @Override
        public boolean canMoveWest(int x, int y, int z) { return true; }
        @Override
        public boolean canMoveTo(WorldPoint from, WorldPoint to) { return true; }
    }

    /**
     * Scene obstacle cache stub - no obstacles.
     */
    private static class NoObstacleCache extends SceneObstacleCache {
        NoObstacleCache() {
            super(null); // No handler needed for stub
        }
        
        @Override
        public List<ObstacleHandler.DetectedObstacle> getObstaclesNearby(WorldPoint center, int radius) {
            return Collections.emptyList();
        }
    }

    /**
     * Scene manager stub - everything loaded.
     */
    private static class AllLoadedSceneManager extends SceneManager {
        AllLoadedSceneManager() {
            super(null); // No client needed for stub
        }
        
        @Override
        public boolean areBothPointsLoaded(WorldPoint a, WorldPoint b) { return true; }
        @Override
        public boolean isInLoadedScene(WorldPoint point) { return true; }
    }
    
    /**
     * Create a mock Client with fixed base coordinates for performance tests.
     */
    private static Client createMockClient(int baseX, int baseY) {
        Client mockClient = mock(Client.class);
        when(mockClient.getBaseX()).thenReturn(baseX);
        when(mockClient.getBaseY()).thenReturn(baseY);
        return mockClient;
    }

    // ========================================================================
    // Basic Path Cost Tests
    // ========================================================================

    @Test
    public void testSameTileReturnsCostZero() {
        WorldPoint pos = new WorldPoint(3200, 3200, 0);
        
        OptionalInt cost = estimator.estimatePathCost(pos, pos);
        
        assertTrue("Same tile should return cost", cost.isPresent());
        assertEquals("Same tile cost should be 0", 0, cost.getAsInt());
    }

    @Test
    public void testNullStartReturnsEmpty() {
        WorldPoint end = new WorldPoint(3200, 3200, 0);
        
        OptionalInt cost = estimator.estimatePathCost(null, end);
        
        assertFalse("Null start should return empty", cost.isPresent());
    }

    @Test
    public void testNullEndReturnsEmpty() {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        
        OptionalInt cost = estimator.estimatePathCost(start, null);
        
        assertFalse("Null end should return empty", cost.isPresent());
    }

    @Test
    public void testDifferentPlanesReturnsEmpty() {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3200, 3200, 1);
        
        OptionalInt cost = estimator.estimatePathCost(start, end);
        
        assertFalse("Different planes should return empty", cost.isPresent());
    }

    @Test
    public void testOutsideSceneRangeReturnsEmpty() {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3300, 3200, 0); // 100 tiles away
        
        OptionalInt cost = estimator.estimatePathCost(start, end);
        
        assertFalse("Outside scene range should return empty", cost.isPresent());
    }

    @Test
    public void testAdjacentTileWithClearPath() {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3201, 3200, 0);
        
        // Scene is loaded
        when(sceneManager.areBothPointsLoaded(start, end)).thenReturn(true);
        // Path is clear (using coordinate-based methods)
        when(collisionService.isBlocked(anyInt(), anyInt(), anyInt())).thenReturn(false);
        when(collisionService.canMoveEast(anyInt(), anyInt(), anyInt())).thenReturn(true);
        
        OptionalInt cost = estimator.estimatePathCost(start, end);
        
        assertTrue("Adjacent tile should be reachable", cost.isPresent());
        assertEquals("Adjacent tile cost should be 1", 1, cost.getAsInt());
    }

    @Test
    public void testBlockedTileReturnsEmpty() {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3201, 3200, 0);
        
        // Scene is loaded
        when(sceneManager.areBothPointsLoaded(start, end)).thenReturn(true);
        // End tile is blocked
        when(collisionService.isBlocked(3201, 3200, 0)).thenReturn(true);
        when(collisionService.isBlocked(3200, 3200, 0)).thenReturn(false);
        
        OptionalInt cost = estimator.estimatePathCost(start, end);
        
        assertFalse("Blocked tile should return empty", cost.isPresent());
    }

    // ========================================================================
    // Performance Tests (use stubs to avoid Mockito overhead)
    // ========================================================================

    @Test
    public void testPathCostPerformance_NearbyTile() {
        // Use stubs instead of mocks for realistic performance measurement
        // Base coords set so that 3200,3200 is within scene (scene coords 50,50)
        PathCostEstimator perfEstimator = new PathCostEstimator(
            new OpenFieldCollisionService(),
            new NoObstacleCache(),
            new AllLoadedSceneManager(),
            createMockClient(3150, 3150)
        );
        
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3210, 3210, 0); // 10 tiles diagonally
        
        // Warm up
        perfEstimator.estimatePathCost(start, end);
        
        // Measure
        long startTime = System.nanoTime();
        int iterations = 100;
        
        for (int i = 0; i < iterations; i++) {
            perfEstimator.estimatePathCost(start, end);
        }
        
        long elapsedNs = System.nanoTime() - startTime;
        double avgMs = (elapsedNs / 1_000_000.0) / iterations;
        
        System.out.println("Average path cost time (10 tiles diagonal): " + avgMs + " ms");
        assertTrue("Path cost should be < 15ms for nearby tiles", avgMs < 15.0);
    }

    @Test
    public void testPathCostPerformance_MaxRange() {
        // Use stubs instead of mocks for realistic performance measurement
        // Base coords set so that 3200,3200 is within scene (scene coords 50,50)
        PathCostEstimator perfEstimator = new PathCostEstimator(
            new OpenFieldCollisionService(),
            new NoObstacleCache(),
            new AllLoadedSceneManager(),
            createMockClient(3150, 3150)
        );
        
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3250, 3200, 0); // 50 tiles east
        
        // Warm up
        perfEstimator.estimatePathCost(start, end);
        
        // Measure
        long startTime = System.nanoTime();
        int iterations = 50;
        
        for (int i = 0; i < iterations; i++) {
            perfEstimator.estimatePathCost(start, end);
        }
        
        long elapsedNs = System.nanoTime() - startTime;
        double avgMs = (elapsedNs / 1_000_000.0) / iterations;
        
        System.out.println("Average path cost time (50 tiles): " + avgMs + " ms");
        assertTrue("Path cost should be < 15ms for max range", avgMs < 15.0);
    }

    // ========================================================================
    // Chebyshev Distance Tests
    // ========================================================================

    @Test
    public void testChebyshevDistanceWithinRange() {
        // Use stubs for this larger distance test
        // Base coords set so that 3200,3200 is within scene (scene coords 50,50)
        PathCostEstimator perfEstimator = new PathCostEstimator(
            new OpenFieldCollisionService(),
            new NoObstacleCache(),
            new AllLoadedSceneManager(),
            createMockClient(3150, 3150)
        );
        
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        
        // 52 tiles in X direction - should be within range
        WorldPoint endX = new WorldPoint(3252, 3200, 0);
        
        OptionalInt costX = perfEstimator.estimatePathCost(start, endX);
        assertTrue("52 tiles should be within range", costX.isPresent());
    }

    @Test
    public void testChebyshevDistanceOutOfRange() {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        
        // 53 tiles - should be out of range
        WorldPoint endX = new WorldPoint(3253, 3200, 0);
        
        OptionalInt costX = estimator.estimatePathCost(start, endX);
        assertFalse("53 tiles should be out of range", costX.isPresent());
    }
}
