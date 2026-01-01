package com.rocinante.navigation;

import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for CollisionService delegation to ShortestPathBridge.
 */
public class CollisionServiceTest {

    @Mock
    private ShortestPathBridge bridge;

    private CollisionService collisionService;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        collisionService = new CollisionService(bridge);
    }

    // ========================================================================
    // Delegation Tests
    // ========================================================================

    @Test
    public void testCanMoveToDelegates() {
        WorldPoint from = new WorldPoint(3200, 3200, 0);
        WorldPoint to = new WorldPoint(3201, 3200, 0);

        when(bridge.canMoveTo(from, to)).thenReturn(true);

        assertTrue(collisionService.canMoveTo(from, to));
        verify(bridge).canMoveTo(from, to);
    }

    @Test
    public void testIsBlockedWithWorldPointDelegates() {
        WorldPoint point = new WorldPoint(3200, 3200, 0);

        when(bridge.isBlocked(point)).thenReturn(false);

        assertFalse(collisionService.isBlocked(point));
        verify(bridge).isBlocked(point);
    }

    @Test
    public void testIsBlockedWithCoordinatesDelegates() {
        when(bridge.isBlocked(3200, 3200, 0)).thenReturn(true);

        assertTrue(collisionService.isBlocked(3200, 3200, 0));
        verify(bridge).isBlocked(3200, 3200, 0);
    }

    @Test
    public void testDirectionalMovementDelegates() {
        when(bridge.canMoveNorth(3200, 3200, 0)).thenReturn(true);
        when(bridge.canMoveSouth(3200, 3200, 0)).thenReturn(false);
        when(bridge.canMoveEast(3200, 3200, 0)).thenReturn(true);
        when(bridge.canMoveWest(3200, 3200, 0)).thenReturn(false);

        assertTrue(collisionService.canMoveNorth(3200, 3200, 0));
        assertFalse(collisionService.canMoveSouth(3200, 3200, 0));
        assertTrue(collisionService.canMoveEast(3200, 3200, 0));
        assertFalse(collisionService.canMoveWest(3200, 3200, 0));

        verify(bridge).canMoveNorth(3200, 3200, 0);
        verify(bridge).canMoveSouth(3200, 3200, 0);
        verify(bridge).canMoveEast(3200, 3200, 0);
        verify(bridge).canMoveWest(3200, 3200, 0);
    }

    @Test
    public void testCanInteractWithDelegates() {
        WorldPoint player = new WorldPoint(3200, 3200, 0);
        WorldPoint target = new WorldPoint(3201, 3200, 0);

        when(bridge.canInteractWith(player, target)).thenReturn(true);

        assertTrue(collisionService.canInteractWith(player, target));
        verify(bridge).canInteractWith(player, target);
    }

}
