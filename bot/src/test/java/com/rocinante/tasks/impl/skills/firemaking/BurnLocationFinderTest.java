package com.rocinante.tasks.impl.skills.firemaking;

import com.rocinante.navigation.NavigationService;
import com.rocinante.state.GameObjectSnapshot;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
import com.rocinante.tasks.TaskContext;
import net.runelite.api.ObjectID;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BurnLocationFinder utility class.
 * Tests the dynamic burn location finding algorithm for cut-and-burn firemaking.
 */
public class BurnLocationFinderTest {

    private static final WorldPoint TEST_POSITION = new WorldPoint(3200, 3200, 0);

    @Mock
    private TaskContext taskContext;

    @Mock
    private NavigationService navigationService;

    @Mock
    private WorldState worldState;

    @Mock
    private PlayerState playerState;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        when(taskContext.getNavigationService()).thenReturn(navigationService);
        when(taskContext.getWorldState()).thenReturn(worldState);
        when(taskContext.getPlayerState()).thenReturn(playerState);
        when(playerState.getWorldPosition()).thenReturn(TEST_POSITION);

        // Default: no blocked tiles, no fires
        when(navigationService.isBlocked(any(WorldPoint.class))).thenReturn(false);
        when(worldState.getNearbyObjects()).thenReturn(Collections.emptyList());
    }

    // ========================================================================
    // Basic Functionality Tests
    // ========================================================================

    @Test
    public void testFindOptimalBurnLocation_NullContext_ReturnsEmpty() {
        Optional<BurnLocationFinder.BurnLocation> result =
                BurnLocationFinder.findOptimalBurnLocation(null, TEST_POSITION, 10, 15);

        assertFalse(result.isPresent());
    }

    @Test
    public void testFindOptimalBurnLocation_NullPosition_ReturnsEmpty() {
        Optional<BurnLocationFinder.BurnLocation> result =
                BurnLocationFinder.findOptimalBurnLocation(taskContext, null, 10, 15);

        assertFalse(result.isPresent());
    }

    @Test
    public void testFindOptimalBurnLocation_NullNavigationService_ReturnsEmpty() {
        when(taskContext.getNavigationService()).thenReturn(null);

        Optional<BurnLocationFinder.BurnLocation> result =
                BurnLocationFinder.findOptimalBurnLocation(taskContext, TEST_POSITION, 10, 15);

        assertFalse(result.isPresent());
    }

    @Test
    public void testFindOptimalBurnLocation_CenterTileAvailable_ReturnsCenterFirst() {
        // Center tile (0,0 offset) is available with path cost 1
        when(navigationService.getPathCost(eq(taskContext), eq(TEST_POSITION), eq(TEST_POSITION)))
                .thenReturn(OptionalInt.of(1));

        Optional<BurnLocationFinder.BurnLocation> result =
                BurnLocationFinder.findOptimalBurnLocation(taskContext, TEST_POSITION, 10, 15);

        assertTrue(result.isPresent());
        assertEquals(TEST_POSITION, result.get().getPosition());
        assertEquals(1, result.get().getPathCost());
        assertTrue(result.get().isOnSameLine());
    }

    // ========================================================================
    // Blocked Tile Tests
    // ========================================================================

    @Test
    public void testFindOptimalBurnLocation_CenterBlocked_FindsNearbyTile() {
        // Center tile is blocked
        when(navigationService.isBlocked(TEST_POSITION)).thenReturn(true);

        // Adjacent tile (1,0) is available
        WorldPoint adjacent = new WorldPoint(TEST_POSITION.getX() + 1, TEST_POSITION.getY(), 0);
        when(navigationService.getPathCost(eq(taskContext), eq(TEST_POSITION), eq(adjacent)))
                .thenReturn(OptionalInt.of(1));

        Optional<BurnLocationFinder.BurnLocation> result =
                BurnLocationFinder.findOptimalBurnLocation(taskContext, TEST_POSITION, 10, 15);

        assertTrue(result.isPresent());
        assertNotEquals(TEST_POSITION, result.get().getPosition());
    }

    @Test
    public void testFindOptimalBurnLocation_AllTilesBlocked_ReturnsEmpty() {
        // All tiles are blocked
        when(navigationService.isBlocked(any(WorldPoint.class))).thenReturn(true);

        Optional<BurnLocationFinder.BurnLocation> result =
                BurnLocationFinder.findOptimalBurnLocation(taskContext, TEST_POSITION, 5, 15);

        assertFalse(result.isPresent());
    }

    // ========================================================================
    // Fire Object Avoidance Tests
    // ========================================================================

    @Test
    public void testFindOptimalBurnLocation_FireAtCenter_FindsNearbyTile() {
        // Fire object at center position
        List<GameObjectSnapshot> objects = new ArrayList<>();
        objects.add(createFireSnapshot(TEST_POSITION));
        when(worldState.getNearbyObjects()).thenReturn(objects);

        // Adjacent tile is available
        WorldPoint adjacent = new WorldPoint(TEST_POSITION.getX() + 1, TEST_POSITION.getY(), 0);
        when(navigationService.getPathCost(eq(taskContext), eq(TEST_POSITION), eq(adjacent)))
                .thenReturn(OptionalInt.of(1));

        Optional<BurnLocationFinder.BurnLocation> result =
                BurnLocationFinder.findOptimalBurnLocation(taskContext, TEST_POSITION, 10, 15);

        assertTrue(result.isPresent());
        assertNotEquals(TEST_POSITION, result.get().getPosition());
    }

    @Test
    public void testFindOptimalBurnLocation_MultipleFires_AvoidsAll() {
        // Multiple fire objects
        List<GameObjectSnapshot> objects = new ArrayList<>();
        objects.add(createFireSnapshot(TEST_POSITION));
        objects.add(createFireSnapshot(new WorldPoint(TEST_POSITION.getX() - 1, TEST_POSITION.getY(), 0)));
        objects.add(createFireSnapshot(new WorldPoint(TEST_POSITION.getX() - 2, TEST_POSITION.getY(), 0)));
        when(worldState.getNearbyObjects()).thenReturn(objects);

        // Only tile at x+1 is available
        WorldPoint available = new WorldPoint(TEST_POSITION.getX() + 1, TEST_POSITION.getY(), 0);
        when(navigationService.getPathCost(eq(taskContext), eq(TEST_POSITION), eq(available)))
                .thenReturn(OptionalInt.of(1));

        Optional<BurnLocationFinder.BurnLocation> result =
                BurnLocationFinder.findOptimalBurnLocation(taskContext, TEST_POSITION, 10, 15);

        assertTrue(result.isPresent());
        assertEquals(available, result.get().getPosition());
    }

    // ========================================================================
    // Path Cost Tests
    // ========================================================================

    @Test
    public void testFindOptimalBurnLocation_PrefersLowerPathCost() {
        // Tile at (1,0) has cost 5, tile at (2,0) has cost 3
        WorldPoint nearTile = new WorldPoint(TEST_POSITION.getX() + 1, TEST_POSITION.getY(), 0);
        WorldPoint farTile = new WorldPoint(TEST_POSITION.getX() + 2, TEST_POSITION.getY(), 0);

        // Center is blocked
        when(navigationService.isBlocked(TEST_POSITION)).thenReturn(true);

        when(navigationService.getPathCost(eq(taskContext), eq(TEST_POSITION), eq(nearTile)))
                .thenReturn(OptionalInt.of(5));
        when(navigationService.getPathCost(eq(taskContext), eq(TEST_POSITION), eq(farTile)))
                .thenReturn(OptionalInt.of(3));

        Optional<BurnLocationFinder.BurnLocation> result =
                BurnLocationFinder.findOptimalBurnLocation(taskContext, TEST_POSITION, 10, 15);

        assertTrue(result.isPresent());
        // Should prefer lower path cost
        assertEquals(3, result.get().getPathCost());
    }

    @Test
    public void testFindOptimalBurnLocation_RespectsWalkThreshold() {
        // All nearby tiles exceed walk threshold
        when(navigationService.isBlocked(TEST_POSITION)).thenReturn(true);

        WorldPoint nearTile = new WorldPoint(TEST_POSITION.getX() + 1, TEST_POSITION.getY(), 0);
        when(navigationService.getPathCost(eq(taskContext), eq(TEST_POSITION), eq(nearTile)))
                .thenReturn(OptionalInt.of(20)); // Exceeds threshold of 15

        Optional<BurnLocationFinder.BurnLocation> result =
                BurnLocationFinder.findOptimalBurnLocation(taskContext, TEST_POSITION, 10, 15);

        assertFalse(result.isPresent());
    }

    @Test
    public void testFindOptimalBurnLocation_UnreachableTile_Skipped() {
        // Center is blocked
        when(navigationService.isBlocked(TEST_POSITION)).thenReturn(true);

        // Adjacent tile is unreachable (no path)
        WorldPoint adjacent = new WorldPoint(TEST_POSITION.getX() + 1, TEST_POSITION.getY(), 0);
        when(navigationService.getPathCost(eq(taskContext), eq(TEST_POSITION), eq(adjacent)))
                .thenReturn(OptionalInt.empty()); // No path

        Optional<BurnLocationFinder.BurnLocation> result =
                BurnLocationFinder.findOptimalBurnLocation(taskContext, TEST_POSITION, 1, 15);

        assertFalse(result.isPresent());
    }

    // ========================================================================
    // Same Line Preference Tests
    // ========================================================================

    @Test
    public void testFindOptimalBurnLocation_PrefersSameLineOverLowerCost() {
        // Center is blocked
        when(navigationService.isBlocked(TEST_POSITION)).thenReturn(true);

        // Tile on same line (same Y) with higher cost
        WorldPoint sameLine = new WorldPoint(TEST_POSITION.getX() + 1, TEST_POSITION.getY(), 0);
        // Tile on different line (different Y) with lower cost
        WorldPoint differentLine = new WorldPoint(TEST_POSITION.getX(), TEST_POSITION.getY() + 1, 0);

        when(navigationService.getPathCost(eq(taskContext), eq(TEST_POSITION), eq(sameLine)))
                .thenReturn(OptionalInt.of(5));
        when(navigationService.getPathCost(eq(taskContext), eq(TEST_POSITION), eq(differentLine)))
                .thenReturn(OptionalInt.of(2));

        Optional<BurnLocationFinder.BurnLocation> result =
                BurnLocationFinder.findOptimalBurnLocation(taskContext, TEST_POSITION, 10, 15);

        assertTrue(result.isPresent());
        // Should prefer same line even with higher cost
        assertTrue(result.get().isOnSameLine());
        assertEquals(sameLine, result.get().getPosition());
    }

    @Test
    public void testFindOptimalBurnLocation_SameLineNotAvailable_UsesDifferentLine() {
        // Center is blocked
        when(navigationService.isBlocked(TEST_POSITION)).thenReturn(true);

        // All same-line tiles are blocked
        for (int x = -10; x <= 10; x++) {
            WorldPoint sameLine = new WorldPoint(TEST_POSITION.getX() + x, TEST_POSITION.getY(), 0);
            when(navigationService.isBlocked(sameLine)).thenReturn(true);
        }

        // Different line tile is available
        WorldPoint differentLine = new WorldPoint(TEST_POSITION.getX(), TEST_POSITION.getY() + 1, 0);
        when(navigationService.isBlocked(differentLine)).thenReturn(false);
        when(navigationService.getPathCost(eq(taskContext), eq(TEST_POSITION), eq(differentLine)))
                .thenReturn(OptionalInt.of(2));

        Optional<BurnLocationFinder.BurnLocation> result =
                BurnLocationFinder.findOptimalBurnLocation(taskContext, TEST_POSITION, 10, 15);

        assertTrue(result.isPresent());
        assertFalse(result.get().isOnSameLine());
    }

    // ========================================================================
    // Search Radius Tests
    // ========================================================================

    @Test
    public void testFindOptimalBurnLocation_RespectsSearchRadius() {
        // Center is blocked
        when(navigationService.isBlocked(TEST_POSITION)).thenReturn(true);

        // Tile within radius is blocked
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                WorldPoint tile = new WorldPoint(TEST_POSITION.getX() + x, TEST_POSITION.getY() + y, 0);
                when(navigationService.isBlocked(tile)).thenReturn(true);
            }
        }

        // Tile outside radius (at distance 5) is not blocked
        WorldPoint outsideRadius = new WorldPoint(TEST_POSITION.getX() + 5, TEST_POSITION.getY(), 0);
        when(navigationService.isBlocked(outsideRadius)).thenReturn(false);
        when(navigationService.getPathCost(eq(taskContext), eq(TEST_POSITION), eq(outsideRadius)))
                .thenReturn(OptionalInt.of(5));

        // Search radius of 3 should not find the tile at distance 5
        Optional<BurnLocationFinder.BurnLocation> result =
                BurnLocationFinder.findOptimalBurnLocation(taskContext, TEST_POSITION, 3, 15);

        assertFalse(result.isPresent());
    }

    // ========================================================================
    // Fallback Clear Spot Tests
    // ========================================================================

    @Test
    public void testFindClearSpotNearby_NullContext_ReturnsCurrentPosition() {
        WorldPoint result = BurnLocationFinder.findClearSpotNearby(null, TEST_POSITION);
        assertEquals(TEST_POSITION, result);
    }

    @Test
    public void testFindClearSpotNearby_NullPosition_ReturnsNull() {
        WorldPoint result = BurnLocationFinder.findClearSpotNearby(taskContext, null);
        assertNull(result);
    }

    @Test
    public void testFindClearSpotNearby_NullNavigationService_ReturnsCurrentPosition() {
        when(taskContext.getNavigationService()).thenReturn(null);

        WorldPoint result = BurnLocationFinder.findClearSpotNearby(taskContext, TEST_POSITION);
        assertEquals(TEST_POSITION, result);
    }

    @Test
    public void testFindClearSpotNearby_FindsUnblockedTile() {
        // Current position has a fire
        List<GameObjectSnapshot> objects = new ArrayList<>();
        objects.add(createFireSnapshot(TEST_POSITION));
        when(worldState.getNearbyObjects()).thenReturn(objects);

        WorldPoint result = BurnLocationFinder.findClearSpotNearby(taskContext, TEST_POSITION);

        assertNotEquals(TEST_POSITION, result);
    }

    @Test
    public void testFindClearSpotNearby_AllNearbyBlocked_ReturnsCurrentPosition() {
        // All cardinal and diagonal offsets are blocked
        when(navigationService.isBlocked(any(WorldPoint.class))).thenReturn(true);

        WorldPoint result = BurnLocationFinder.findClearSpotNearby(taskContext, TEST_POSITION);

        assertEquals(TEST_POSITION, result);
    }

    @Test
    public void testFindClearSpotNearby_TriesDiagonalsIfCardinalsBlocked() {
        // Cardinal directions are blocked
        WorldPoint north = new WorldPoint(TEST_POSITION.getX(), TEST_POSITION.getY() + 2, 0);
        WorldPoint south = new WorldPoint(TEST_POSITION.getX(), TEST_POSITION.getY() - 2, 0);
        WorldPoint east = new WorldPoint(TEST_POSITION.getX() + 2, TEST_POSITION.getY(), 0);
        WorldPoint west = new WorldPoint(TEST_POSITION.getX() - 2, TEST_POSITION.getY(), 0);

        when(navigationService.isBlocked(north)).thenReturn(true);
        when(navigationService.isBlocked(south)).thenReturn(true);
        when(navigationService.isBlocked(east)).thenReturn(true);
        when(navigationService.isBlocked(west)).thenReturn(true);

        // Diagonal is available
        WorldPoint diagonal = new WorldPoint(TEST_POSITION.getX() + 2, TEST_POSITION.getY() + 2, 0);
        when(navigationService.isBlocked(diagonal)).thenReturn(false);

        WorldPoint result = BurnLocationFinder.findClearSpotNearby(taskContext, TEST_POSITION);

        // Should find the diagonal tile
        assertNotEquals(TEST_POSITION, result);
    }

    // ========================================================================
    // BurnLocation Data Class Tests
    // ========================================================================

    @Test
    public void testBurnLocation_CorrectValues() {
        WorldPoint position = new WorldPoint(100, 100, 0);
        BurnLocationFinder.BurnLocation location =
                new BurnLocationFinder.BurnLocation(position, 5, true);

        assertEquals(position, location.getPosition());
        assertEquals(5, location.getPathCost());
        assertTrue(location.isOnSameLine());
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private GameObjectSnapshot createFireSnapshot(WorldPoint position) {
        return GameObjectSnapshot.builder()
                .id(ObjectID.FIRE)
                .worldPosition(position)
                .actions(Arrays.asList("Light"))
                .build();
    }
}
