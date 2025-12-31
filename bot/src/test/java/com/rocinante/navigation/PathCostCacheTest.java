package com.rocinante.navigation;

import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Tests for PathCostCache caching and invalidation logic.
 */
public class PathCostCacheTest {

    private PathCostCache cache;

    @Before
    public void setUp() {
        cache = new PathCostCache();
    }

    // ========================================================================
    // Basic Caching Tests
    // ========================================================================

    @Test
    public void testCacheHitAfterStore() {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3210, 3210, 0);
        List<WorldPoint> path = Arrays.asList(start, end);

        cache.cachePathResult(start, end, path);

        Optional<Integer> cost = cache.getPathCost(start, end);
        assertTrue("Should have cache hit", cost.isPresent());
        assertEquals("Path cost should be path size", 2, cost.get().intValue());
    }

    @Test
    public void testCacheMissForNewPath() {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3210, 3210, 0);

        Optional<Integer> cost = cache.getPathCost(start, end);
        assertFalse("Should have cache miss for new path", cost.isPresent());
    }

    @Test
    public void testCacheMissForDifferentEndpoint() {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end1 = new WorldPoint(3210, 3210, 0);
        WorldPoint end2 = new WorldPoint(3220, 3220, 0);
        List<WorldPoint> path = Arrays.asList(start, end1);

        cache.cachePathResult(start, end1, path);

        Optional<Integer> cost = cache.getPathCost(start, end2);
        assertFalse("Should have cache miss for different endpoint", cost.isPresent());
    }

    @Test
    public void testGetPathReturnsStoredPath() {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3210, 3210, 0);
        WorldPoint mid = new WorldPoint(3205, 3205, 0);
        List<WorldPoint> path = Arrays.asList(start, mid, end);

        cache.cachePathResult(start, end, path);

        Optional<List<WorldPoint>> retrieved = cache.getPath(start, end);
        assertTrue("Should have path in cache", retrieved.isPresent());
        assertEquals("Path should have 3 points", 3, retrieved.get().size());
    }

    // ========================================================================
    // Invalidation Tests
    // ========================================================================

    @Test
    public void testInvalidateOnLargePlayerMovement() {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3210, 3210, 0);
        List<WorldPoint> path = Arrays.asList(start, end);

        cache.cachePathResult(start, end, path);

        // Player moves small distance - should not invalidate
        WorldPoint smallMove = new WorldPoint(3205, 3200, 0);
        cache.invalidateIfPlayerMoved(smallMove);
        assertTrue("Small move should not invalidate", cache.getPathCost(start, end).isPresent());

        // Player moves large distance (>10 tiles) - should invalidate
        WorldPoint largeMove = new WorldPoint(3250, 3200, 0);
        cache.invalidateIfPlayerMoved(largeMove);
        assertFalse("Large move should invalidate cache", cache.getPathCost(start, end).isPresent());
    }

    @Test
    public void testClearRemovesAllEntries() {
        WorldPoint start1 = new WorldPoint(3200, 3200, 0);
        WorldPoint end1 = new WorldPoint(3210, 3210, 0);
        WorldPoint start2 = new WorldPoint(3300, 3300, 0);
        WorldPoint end2 = new WorldPoint(3310, 3310, 0);

        cache.cachePathResult(start1, end1, Arrays.asList(start1, end1));
        cache.cachePathResult(start2, end2, Arrays.asList(start2, end2));

        cache.clear();

        assertFalse("First path should be cleared", cache.getPathCost(start1, end1).isPresent());
        assertFalse("Second path should be cleared", cache.getPathCost(start2, end2).isPresent());
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testNullHandling() {
        Optional<Integer> cost1 = cache.getPathCost(null, new WorldPoint(3200, 3200, 0));
        assertFalse("Null start should return empty", cost1.isPresent());

        Optional<Integer> cost2 = cache.getPathCost(new WorldPoint(3200, 3200, 0), null);
        assertFalse("Null end should return empty", cost2.isPresent());

        // Should not throw
        cache.cachePathResult(null, new WorldPoint(3200, 3200, 0), Arrays.asList());
        cache.cachePathResult(new WorldPoint(3200, 3200, 0), null, Arrays.asList());
        cache.invalidateIfPlayerMoved(null);
    }

    @Test
    public void testEmptyPathNotCached() {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3210, 3210, 0);

        cache.cachePathResult(start, end, Arrays.asList());

        assertFalse("Empty path should not be cached", cache.getPathCost(start, end).isPresent());
    }

    @Test
    public void testDifferentPlanesAreDifferentPaths() {
        WorldPoint start0 = new WorldPoint(3200, 3200, 0);
        WorldPoint end0 = new WorldPoint(3210, 3210, 0);
        WorldPoint start1 = new WorldPoint(3200, 3200, 1);
        WorldPoint end1 = new WorldPoint(3210, 3210, 1);

        cache.cachePathResult(start0, end0, Arrays.asList(start0, end0));

        assertTrue("Plane 0 should be cached", cache.getPathCost(start0, end0).isPresent());
        assertFalse("Plane 1 should not be cached", cache.getPathCost(start1, end1).isPresent());
    }
}
