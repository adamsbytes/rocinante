package com.rocinante.navigation;

import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for SceneManager centralized scene loading utilities.
 */
public class SceneManagerTest {

    @Mock
    private Client client;

    @Mock
    private Player localPlayer;

    private SceneManager sceneManager;

    private static final int BASE_X = 3200;
    private static final int BASE_Y = 3200;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        sceneManager = new SceneManager(client);

        when(client.getBaseX()).thenReturn(BASE_X);
        when(client.getBaseY()).thenReturn(BASE_Y);
        when(client.getPlane()).thenReturn(0);
        when(client.getLocalPlayer()).thenReturn(localPlayer);
    }

    // ========================================================================
    // Constants Tests
    // ========================================================================

    @Test
    public void testMaxLocalNavDistance_IsHalfSceneSize() {
        assertEquals("MAX_LOCAL_NAV_DISTANCE should be half of SCENE_SIZE",
                Constants.SCENE_SIZE / 2, SceneManager.MAX_LOCAL_NAV_DISTANCE);
    }

    @Test
    public void testSceneSize_MatchesConstants() {
        assertEquals("SCENE_SIZE should match Constants.SCENE_SIZE",
                Constants.SCENE_SIZE, SceneManager.SCENE_SIZE);
    }

    // ========================================================================
    // isInLoadedScene Tests
    // ========================================================================

    @Test
    public void testIsInLoadedScene_NullPoint_ReturnsFalse() {
        assertFalse("Null point should return false",
                sceneManager.isInLoadedScene(null));
    }

    @Test
    public void testIsInLoadedScene_PointInScene_ReturnsTrue() {
        WorldPoint point = new WorldPoint(BASE_X + 50, BASE_Y + 50, 0);

        try (MockedStatic<LocalPoint> mocked = mockStatic(LocalPoint.class)) {
            LocalPoint local = mock(LocalPoint.class);
            when(local.getSceneX()).thenReturn(50);
            when(local.getSceneY()).thenReturn(50);
            mocked.when(() -> LocalPoint.fromWorld(client, point)).thenReturn(local);

            assertTrue("Point in scene should return true",
                    sceneManager.isInLoadedScene(point));
        }
    }

    @Test
    public void testIsInLoadedScene_PointOutsideScene_ReturnsFalse() {
        WorldPoint point = new WorldPoint(BASE_X + 200, BASE_Y + 200, 0); // Way outside

        try (MockedStatic<LocalPoint> mocked = mockStatic(LocalPoint.class)) {
            mocked.when(() -> LocalPoint.fromWorld(client, point)).thenReturn(null);

            assertFalse("Point outside scene should return false",
                    sceneManager.isInLoadedScene(point));
        }
    }

    // ========================================================================
    // areBothPointsLoaded Tests
    // ========================================================================

    @Test
    public void testAreBothPointsLoaded_BothNull_ReturnsFalse() {
        assertFalse("Both null should return false",
                sceneManager.areBothPointsLoaded(null, null));
    }

    @Test
    public void testAreBothPointsLoaded_OneNull_ReturnsFalse() {
        WorldPoint point = new WorldPoint(BASE_X + 50, BASE_Y + 50, 0);
        
        try (MockedStatic<LocalPoint> mocked = mockStatic(LocalPoint.class)) {
            LocalPoint local = mock(LocalPoint.class);
            when(local.getSceneX()).thenReturn(50);
            when(local.getSceneY()).thenReturn(50);
            mocked.when(() -> LocalPoint.fromWorld(client, point)).thenReturn(local);
            
            assertFalse("One null should return false",
                    sceneManager.areBothPointsLoaded(point, null));
            assertFalse("One null should return false",
                    sceneManager.areBothPointsLoaded(null, point));
        }
    }

    // ========================================================================
    // isInSceneBounds Tests
    // ========================================================================

    @Test
    public void testIsInSceneBounds_ValidCoordinates_ReturnsTrue() {
        assertTrue("(0,0) should be in bounds",
                sceneManager.isInSceneBounds(0, 0));
        assertTrue("(50,50) should be in bounds",
                sceneManager.isInSceneBounds(50, 50));
        assertTrue("(103,103) should be in bounds",
                sceneManager.isInSceneBounds(Constants.SCENE_SIZE - 1, Constants.SCENE_SIZE - 1));
    }

    @Test
    public void testIsInSceneBounds_NegativeCoordinates_ReturnsFalse() {
        assertFalse("Negative X should be out of bounds",
                sceneManager.isInSceneBounds(-1, 50));
        assertFalse("Negative Y should be out of bounds",
                sceneManager.isInSceneBounds(50, -1));
    }

    @Test
    public void testIsInSceneBounds_OverflowCoordinates_ReturnsFalse() {
        assertFalse("X >= SCENE_SIZE should be out of bounds",
                sceneManager.isInSceneBounds(Constants.SCENE_SIZE, 50));
        assertFalse("Y >= SCENE_SIZE should be out of bounds",
                sceneManager.isInSceneBounds(50, Constants.SCENE_SIZE));
    }

    // ========================================================================
    // isInSceneBoundsWithMargin Tests
    // ========================================================================

    @Test
    public void testIsInSceneBoundsWithMargin_CenterPoint_ReturnsTrue() {
        assertTrue("Center point should be within margin",
                sceneManager.isInSceneBoundsWithMargin(50, 50, 5));
    }

    @Test
    public void testIsInSceneBoundsWithMargin_EdgePoint_ReturnsFalse() {
        assertFalse("Edge point should be outside margin",
                sceneManager.isInSceneBoundsWithMargin(2, 50, 5));
        assertFalse("Edge point should be outside margin",
                sceneManager.isInSceneBoundsWithMargin(Constants.SCENE_SIZE - 2, 50, 5));
    }

    // ========================================================================
    // sceneToWorld Tests
    // ========================================================================

    @Test
    public void testSceneToWorld_ValidCoordinates_ReturnsWorldPoint() {
        WorldPoint result = sceneManager.sceneToWorld(50, 50, 0);

        assertNotNull("Result should not be null", result);
        assertEquals("World X should be base + scene", BASE_X + 50, result.getX());
        assertEquals("World Y should be base + scene", BASE_Y + 50, result.getY());
        assertEquals("Plane should match", 0, result.getPlane());
    }

    @Test
    public void testSceneToWorld_InvalidCoordinates_ReturnsNull() {
        assertNull("Negative scene coords should return null",
                sceneManager.sceneToWorld(-1, 50, 0));
        assertNull("Overflow scene coords should return null",
                sceneManager.sceneToWorld(Constants.SCENE_SIZE + 1, 50, 0));
    }

    // ========================================================================
    // getSceneBase Tests
    // ========================================================================

    @Test
    public void testGetSceneBase_ValidBase_ReturnsWorldPoint() {
        WorldPoint base = sceneManager.getSceneBase();

        assertNotNull("Base should not be null", base);
        assertEquals("Base X should match", BASE_X, base.getX());
        assertEquals("Base Y should match", BASE_Y, base.getY());
    }

    @Test
    public void testGetSceneBase_UninitializedClient_ReturnsNull() {
        when(client.getBaseX()).thenReturn(0);
        when(client.getBaseY()).thenReturn(0);

        assertNull("Uninitialized client should return null base",
                sceneManager.getSceneBase());
    }

    // ========================================================================
    // shouldUseLocalNav Tests
    // ========================================================================

    @Test
    public void testShouldUseLocalNav_NullPoints_ReturnsFalse() {
        assertFalse("Null from should return false",
                sceneManager.shouldUseLocalNav(null, new WorldPoint(BASE_X + 50, BASE_Y + 50, 0)));
        assertFalse("Null to should return false",
                sceneManager.shouldUseLocalNav(new WorldPoint(BASE_X + 50, BASE_Y + 50, 0), null));
    }

    @Test
    public void testShouldUseLocalNav_DifferentPlanes_ReturnsFalse() {
        WorldPoint from = new WorldPoint(BASE_X + 50, BASE_Y + 50, 0);
        WorldPoint to = new WorldPoint(BASE_X + 55, BASE_Y + 55, 1);

        assertFalse("Different planes should return false",
                sceneManager.shouldUseLocalNav(from, to));
    }

    @Test
    public void testShouldUseLocalNav_TooFar_ReturnsFalse() {
        WorldPoint from = new WorldPoint(BASE_X + 10, BASE_Y + 10, 0);
        WorldPoint to = new WorldPoint(BASE_X + 100, BASE_Y + 100, 0); // Far apart

        // Even if both are in scene, distance is too far
        assertFalse("Distance > MAX_LOCAL_NAV_DISTANCE should return false",
                sceneManager.shouldUseLocalNav(from, to));
    }

    // ========================================================================
    // getDistanceCategory Tests
    // ========================================================================

    @Test
    public void testGetDistanceCategory_NullPoints_ReturnsUnreachable() {
        assertEquals("Null points should be UNREACHABLE",
                SceneManager.DistanceCategory.UNREACHABLE,
                sceneManager.getDistanceCategory(null, null));
    }

    @Test
    public void testGetDistanceCategory_DifferentPlanes_ReturnsDifferentPlane() {
        WorldPoint from = new WorldPoint(BASE_X + 50, BASE_Y + 50, 0);
        WorldPoint to = new WorldPoint(BASE_X + 50, BASE_Y + 50, 1);

        assertEquals("Different planes should be DIFFERENT_PLANE",
                SceneManager.DistanceCategory.DIFFERENT_PLANE,
                sceneManager.getDistanceCategory(from, to));
    }

    @Test
    public void testGetDistanceCategory_SameTile_ReturnsSameTile() {
        WorldPoint point = new WorldPoint(BASE_X + 50, BASE_Y + 50, 0);

        assertEquals("Same tile should be SAME_TILE",
                SceneManager.DistanceCategory.SAME_TILE,
                sceneManager.getDistanceCategory(point, point));
    }

    @Test
    public void testGetDistanceCategory_Adjacent_ReturnsAdjacent() {
        WorldPoint from = new WorldPoint(BASE_X + 50, BASE_Y + 50, 0);
        WorldPoint to = new WorldPoint(BASE_X + 51, BASE_Y + 50, 0);

        assertEquals("Adjacent should be ADJACENT",
                SceneManager.DistanceCategory.ADJACENT,
                sceneManager.getDistanceCategory(from, to));
    }

    @Test
    public void testGetDistanceCategory_FarAway_ReturnsWebNav() {
        WorldPoint from = new WorldPoint(BASE_X + 10, BASE_Y + 10, 0);
        WorldPoint to = new WorldPoint(BASE_X + 100, BASE_Y + 100, 0);

        assertEquals("Far points should be WEB_NAV",
                SceneManager.DistanceCategory.WEB_NAV,
                sceneManager.getDistanceCategory(from, to));
    }

    // ========================================================================
    // getMaxLocalNavDistance Tests
    // ========================================================================

    @Test
    public void testGetMaxLocalNavDistance_ReturnsConstant() {
        assertEquals("Should return MAX_LOCAL_NAV_DISTANCE",
                SceneManager.MAX_LOCAL_NAV_DISTANCE,
                sceneManager.getMaxLocalNavDistance());
    }
}
