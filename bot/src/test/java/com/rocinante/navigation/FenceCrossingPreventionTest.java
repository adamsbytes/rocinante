package com.rocinante.navigation;

import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.OptionalInt;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for fence crossing prevention.
 *
 * <p>These tests verify the core navigation requirement: the system must correctly
 * reject objects/NPCs on the other side of a fence or barrier, even when they
 * appear close by straight-line distance.
 *
 * <p>The scenario: A tree is 2 tiles away (straight-line), but there's a fence
 * between the player and the tree. The path cost should be much higher than 2
 * (representing the detour around the fence), or the tree should be marked as
 * unreachable if there is no path.
 */
public class FenceCrossingPreventionTest {

    @Mock
    private ShortestPathBridge bridge;
    
    @Mock
    private Client client;

    private CollisionService collisionService;
    private PathCostCache pathCache;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        collisionService = new CollisionService(bridge);
        pathCache = new PathCostCache();
        
        when(bridge.isAvailable()).thenReturn(true);
    }

    // ========================================================================
    // Fence Blocking Tests
    // ========================================================================

    @Test
    public void testCannotMoveThroughFence() {
        // Player at (3200, 3200), target at (3202, 3200)
        // Fence between (3200, 3200) and (3201, 3200)
        WorldPoint player = new WorldPoint(3200, 3200, 0);
        WorldPoint fenceTile = new WorldPoint(3201, 3200, 0);
        WorldPoint target = new WorldPoint(3202, 3200, 0);

        // Fence blocks eastward movement
        when(bridge.canMoveTo(player, fenceTile)).thenReturn(false);
        when(bridge.canMoveTo(fenceTile, player)).thenReturn(false);

        assertFalse("Should not be able to move east through fence",
                collisionService.canMoveTo(player, fenceTile));
    }

    @Test
    public void testCannotInteractAcrossFence() {
        // Player at (3200, 3200), object at (3201, 3200), fence between them
        WorldPoint player = new WorldPoint(3200, 3200, 0);
        WorldPoint objectPos = new WorldPoint(3201, 3200, 0);

        when(bridge.canInteractWith(player, objectPos)).thenReturn(false);

        assertFalse("Should not be able to interact across fence",
                collisionService.canInteractWith(player, objectPos));
    }

    @Test
    public void testPathCostReflectsDetour() {
        // When there's a fence, the path cost to reach an object just 2 tiles away
        // should be much higher than 2 (because we have to go around the fence)
        WorldPoint player = new WorldPoint(3200, 3200, 0);
        WorldPoint objectBehindFence = new WorldPoint(3202, 3200, 0);

        // Assume the detour path is 20 tiles
        java.util.List<WorldPoint> detourPath = new java.util.ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            detourPath.add(new WorldPoint(3200 + i, 3200, 0));
        }

        pathCache.cachePathResult(player, objectBehindFence, detourPath);

        OptionalInt cost = pathCache.getPathCost(player, objectBehindFence).map(OptionalInt::of)
                .orElse(OptionalInt.empty());
        
        assertTrue("Should have path cost in cache", cost.isPresent());
        assertTrue("Path cost should be > 2 (detour required)", cost.getAsInt() > 2);
        assertEquals("Path cost should reflect detour length", 21, cost.getAsInt());
    }

    @Test
    public void testNoPathMeansUnreachable() {
        // If there's no path at all (completely blocked), the cache should not
        // return a result
        WorldPoint player = new WorldPoint(3200, 3200, 0);
        WorldPoint unreachable = new WorldPoint(3300, 3300, 0);

        // Don't cache anything - simulates no path found
        assertFalse("Unreachable target should have no cached path",
                pathCache.getPathCost(player, unreachable).isPresent());
    }

    // ========================================================================
    // Collision Detection Correctness
    // ========================================================================

    @Test
    public void testBlockedTileIsBlocked() {
        WorldPoint blocked = new WorldPoint(3200, 3200, 0);
        when(bridge.isBlocked(blocked)).thenReturn(true);

        assertTrue("Blocked tile should be detected as blocked",
                collisionService.isBlocked(blocked));
    }

    @Test
    public void testOpenTileIsNotBlocked() {
        WorldPoint open = new WorldPoint(3200, 3200, 0);
        when(bridge.isBlocked(open)).thenReturn(false);

        assertFalse("Open tile should not be detected as blocked",
                collisionService.isBlocked(open));
    }

    @Test
    public void testDirectionalMovementBlocked() {
        // At a fence, movement in one direction is blocked, others may be open
        int x = 3200, y = 3200, z = 0;

        when(bridge.canMoveNorth(x, y, z)).thenReturn(true);
        when(bridge.canMoveSouth(x, y, z)).thenReturn(true);
        when(bridge.canMoveEast(x, y, z)).thenReturn(false);  // Fence blocks east
        when(bridge.canMoveWest(x, y, z)).thenReturn(true);

        assertTrue("Should be able to move north", collisionService.canMoveNorth(x, y, z));
        assertTrue("Should be able to move south", collisionService.canMoveSouth(x, y, z));
        assertFalse("Should NOT be able to move east (fence)", collisionService.canMoveEast(x, y, z));
        assertTrue("Should be able to move west", collisionService.canMoveWest(x, y, z));
    }

    // ========================================================================
    // River/Water Blocking Tests
    // ========================================================================

    @Test
    public void testCannotMoveIntoWater() {
        WorldPoint land = new WorldPoint(3200, 3200, 0);
        WorldPoint water = new WorldPoint(3201, 3200, 0);

        when(bridge.isBlocked(water)).thenReturn(true);
        when(bridge.canMoveTo(land, water)).thenReturn(false);

        assertTrue("Water tile should be blocked", collisionService.isBlocked(water));
        assertFalse("Should not be able to move into water", collisionService.canMoveTo(land, water));
    }

    @Test
    public void testCannotInteractAcrossRiver() {
        WorldPoint player = new WorldPoint(3200, 3200, 0);
        WorldPoint fishingSpotAcrossRiver = new WorldPoint(3205, 3200, 0);

        // River between player and fishing spot - no direct interaction
        when(bridge.canInteractWith(player, fishingSpotAcrossRiver)).thenReturn(false);

        assertFalse("Should not be able to interact across river",
                collisionService.canInteractWith(player, fishingSpotAcrossRiver));
    }
}
