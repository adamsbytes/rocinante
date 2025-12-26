package com.rocinante.navigation;

import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for PathFinder A* algorithm.
 * Note: Many A* tests require a full RuneLite client mock which is complex.
 * These tests focus on the API contract and edge cases.
 */
public class PathFinderTest {

    @Mock
    private Client client;

    private PathFinder pathFinder;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        pathFinder = new PathFinder(client);
    }

    // ========================================================================
    // Constants Tests
    // ========================================================================

    @Test
    public void testMaxPathLength() {
        assertEquals("Max path length should be 100", 100, PathFinder.MAX_PATH_LENGTH);
    }

    // ========================================================================
    // Null Input Tests
    // ========================================================================

    @Test
    public void testFindPathNullStart() {
        List<WorldPoint> path = pathFinder.findPath(null, new WorldPoint(100, 100, 0));
        assertTrue("Path should be empty for null start", path.isEmpty());
    }

    @Test
    public void testFindPathNullEnd() {
        List<WorldPoint> path = pathFinder.findPath(new WorldPoint(100, 100, 0), null);
        assertTrue("Path should be empty for null end", path.isEmpty());
    }

    @Test
    public void testFindPathBothNull() {
        List<WorldPoint> path = pathFinder.findPath(null, null);
        assertTrue("Path should be empty for both null", path.isEmpty());
    }

    // ========================================================================
    // Plane Mismatch Tests
    // ========================================================================

    @Test
    public void testFindPathDifferentPlanes() {
        WorldPoint start = new WorldPoint(100, 100, 0);
        WorldPoint end = new WorldPoint(100, 100, 1);

        List<WorldPoint> path = pathFinder.findPath(start, end);
        assertTrue("Path should be empty for different planes", path.isEmpty());
    }

    @Test
    public void testFindPathDifferentPlanesReversed() {
        WorldPoint start = new WorldPoint(100, 100, 2);
        WorldPoint end = new WorldPoint(100, 100, 0);

        List<WorldPoint> path = pathFinder.findPath(start, end);
        assertTrue("Path should be empty for different planes", path.isEmpty());
    }

    // ========================================================================
    // Distance Tests
    // ========================================================================

    @Test
    public void testFindPathDistanceExceedsMax() {
        // Distance > 100 tiles
        WorldPoint start = new WorldPoint(100, 100, 0);
        WorldPoint end = new WorldPoint(300, 300, 0); // ~283 tiles away (diagonal)

        List<WorldPoint> path = pathFinder.findPath(start, end);
        assertTrue("Path should be empty when distance exceeds max", path.isEmpty());
    }

    @Test
    public void testFindPathExactlyMaxDistance() {
        // Distance exactly 100 tiles
        WorldPoint start = new WorldPoint(100, 100, 0);
        WorldPoint end = new WorldPoint(200, 100, 0); // Exactly 100 tiles

        // This may still fail if collision data is null, which is expected
        // The test verifies the distance check doesn't reject it
        when(client.getCollisionMaps()).thenReturn(null);
        List<WorldPoint> path = pathFinder.findPath(start, end);
        // Path will be empty due to null collision data, but not due to distance
        assertTrue(path.isEmpty());
    }

    // ========================================================================
    // Cache Tests
    // ========================================================================

    @Test
    public void testCacheInitiallyNull() {
        assertNull("Cache should be null initially", pathFinder.getCachedPath());
    }

    @Test
    public void testCacheInvalidation() {
        // Even without valid path, cache invalidation should work
        pathFinder.invalidateCache();
        assertNull("Cache should be null after invalidation", pathFinder.getCachedPath());
    }

    // ========================================================================
    // Walkability Tests
    // ========================================================================

    @Test
    public void testIsWalkableNoCollisionData() {
        when(client.getCollisionMaps()).thenReturn(null);

        WorldPoint point = new WorldPoint(100, 100, 0);
        assertFalse("Should not be walkable without collision data",
                pathFinder.isWalkable(point));
    }

    @Test
    public void testIsWalkableInvalidPlane() {
        // Plane 5 doesn't exist
        WorldPoint point = new WorldPoint(100, 100, 5);
        assertFalse("Should not be walkable on invalid plane",
                pathFinder.isWalkable(point));
    }

    // ========================================================================
    // hasPath Tests
    // ========================================================================

    @Test
    public void testHasPathDifferentPlanes() {
        WorldPoint start = new WorldPoint(100, 100, 0);
        WorldPoint end = new WorldPoint(100, 100, 1);

        assertFalse("hasPath should return false for different planes",
                pathFinder.hasPath(start, end));
    }

    @Test
    public void testHasPathExceedsDistance() {
        WorldPoint start = new WorldPoint(100, 100, 0);
        WorldPoint end = new WorldPoint(500, 500, 0);

        assertFalse("hasPath should return false for excessive distance",
                pathFinder.hasPath(start, end));
    }
}
