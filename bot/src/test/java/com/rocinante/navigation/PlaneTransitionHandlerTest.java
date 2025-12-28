package com.rocinante.navigation;

import net.runelite.api.Client;
import net.runelite.api.ObjectID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for PlaneTransitionHandler - stairs, ladders, and trapdoor handling.
 */
public class PlaneTransitionHandlerTest {

    @Mock
    private Client client;

    private PlaneTransitionHandler transitionHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        transitionHandler = new PlaneTransitionHandler(client);
    }

    // ========================================================================
    // Initialization Tests
    // ========================================================================

    @Test
    public void testTransitionsInitialized() {
        assertTrue("Should have registered transitions",
                transitionHandler.getTransitionCount() > 0);
    }

    @Test
    public void testLaddersRegistered() {
        // Test that generic ladder is registered
        assertTrue("Should have ladder registered",
                transitionHandler.isPlaneTransition(ObjectID.LADDER));
    }

    @Test
    public void testStairsRegistered() {
        // Test that generic staircase is registered
        assertTrue("Should have staircase registered",
                transitionHandler.isPlaneTransition(ObjectID.STAIRCASE));
    }

    @Test
    public void testTrapdoorsRegistered() {
        // Test that trapdoor is registered
        assertTrue("Should have trapdoor registered",
                transitionHandler.isPlaneTransition(ObjectID.TRAPDOOR));
    }

    // ========================================================================
    // Transition Lookup Tests
    // ========================================================================

    @Test
    public void testGetTransition() {
        PlaneTransitionHandler.PlaneTransition transition = 
                transitionHandler.getTransition(ObjectID.LADDER);

        assertNotNull("Should find ladder transition", transition);
        assertEquals("Ladder", transition.getName());
        assertEquals(PlaneTransitionHandler.TransitionType.LADDER, transition.getTransitionType());
    }

    @Test
    public void testGetTransitionNotFound() {
        PlaneTransitionHandler.PlaneTransition transition = 
                transitionHandler.getTransition(999999999);

        assertNull("Should not find nonexistent transition", transition);
    }

    @Test
    public void testIsPlaneTransition() {
        assertTrue("Known transition should return true",
                transitionHandler.isPlaneTransition(ObjectID.LADDER));
        assertFalse("Unknown ID should return false",
                transitionHandler.isPlaneTransition(999999999));
    }

    // ========================================================================
    // Transition Type Tests
    // ========================================================================

    @Test
    public void testGetTransitionsByTypeLadder() {
        List<PlaneTransitionHandler.PlaneTransition> ladders = 
                transitionHandler.getTransitionsByType(PlaneTransitionHandler.TransitionType.LADDER);

        assertNotNull(ladders);
        assertFalse("Should have ladder transitions", ladders.isEmpty());

        for (PlaneTransitionHandler.PlaneTransition t : ladders) {
            assertEquals(PlaneTransitionHandler.TransitionType.LADDER, t.getTransitionType());
        }
    }

    @Test
    public void testGetTransitionsByTypeStairs() {
        List<PlaneTransitionHandler.PlaneTransition> stairs = 
                transitionHandler.getTransitionsByType(PlaneTransitionHandler.TransitionType.STAIRS);

        assertNotNull(stairs);
        assertFalse("Should have stair transitions", stairs.isEmpty());

        for (PlaneTransitionHandler.PlaneTransition t : stairs) {
            assertEquals(PlaneTransitionHandler.TransitionType.STAIRS, t.getTransitionType());
        }
    }

    @Test
    public void testGetTransitionsByTypeTrapdoor() {
        List<PlaneTransitionHandler.PlaneTransition> trapdoors = 
                transitionHandler.getTransitionsByType(PlaneTransitionHandler.TransitionType.TRAPDOOR);

        assertNotNull(trapdoors);
        assertFalse("Should have trapdoor transitions", trapdoors.isEmpty());

        for (PlaneTransitionHandler.PlaneTransition t : trapdoors) {
            assertEquals(PlaneTransitionHandler.TransitionType.TRAPDOOR, t.getTransitionType());
        }
    }

    // ========================================================================
    // Transition Direction Tests
    // ========================================================================

    @Test
    public void testBidirectionalLadder() {
        PlaneTransitionHandler.PlaneTransition transition = 
                transitionHandler.getTransition(ObjectID.LADDER);

        assertNotNull(transition);
        assertEquals("Generic ladder should be bidirectional",
                PlaneTransitionHandler.TransitionDirection.BIDIRECTIONAL,
                transition.getDirection());
    }

    @Test
    public void testUpOnlyTransition() {
        // Find an up-only transition
        List<PlaneTransitionHandler.PlaneTransition> all = transitionHandler.getAllTransitions();
        boolean foundUpOnly = all.stream()
                .anyMatch(t -> t.getDirection() == PlaneTransitionHandler.TransitionDirection.UP);

        assertTrue("Should have at least one up-only transition", foundUpOnly);
    }

    @Test
    public void testDownOnlyTransition() {
        // Find a down-only transition
        List<PlaneTransitionHandler.PlaneTransition> all = transitionHandler.getAllTransitions();
        boolean foundDownOnly = all.stream()
                .anyMatch(t -> t.getDirection() == PlaneTransitionHandler.TransitionDirection.DOWN);

        assertTrue("Should have at least one down-only transition", foundDownOnly);
    }

    // ========================================================================
    // Transition Definition Tests
    // ========================================================================

    @Test
    public void testPlaneTransitionBuilder() {
        PlaneTransitionHandler.PlaneTransition custom = PlaneTransitionHandler.PlaneTransition.builder()
                .name("Custom Ladder")
                .objectId(12345)
                .action("Climb-up")
                .transitionType(PlaneTransitionHandler.TransitionType.LADDER)
                .direction(PlaneTransitionHandler.TransitionDirection.UP)
                .planeChange(1)
                .build();

        assertEquals("Custom Ladder", custom.getName());
        assertEquals(12345, custom.getObjectId());
        assertEquals("Climb-up", custom.getAction());
        assertEquals(PlaneTransitionHandler.TransitionType.LADDER, custom.getTransitionType());
        assertEquals(PlaneTransitionHandler.TransitionDirection.UP, custom.getDirection());
        assertEquals(1, custom.getPlaneChange());
    }

    @Test
    public void testTrapdoorWithOpenState() {
        PlaneTransitionHandler.PlaneTransition trapdoor = PlaneTransitionHandler.PlaneTransition.builder()
                .name("Test Trapdoor")
                .objectId(100)
                .action("Open")
                .transitionType(PlaneTransitionHandler.TransitionType.TRAPDOOR)
                .direction(PlaneTransitionHandler.TransitionDirection.DOWN)
                .planeChange(-1)
                .requiresOpen(true)
                .openId(101)
                .build();

        assertTrue("Should require opening", trapdoor.isRequiresOpen());
        assertEquals(101, trapdoor.getOpenId());
    }

    // ========================================================================
    // ToObstacleDefinition Conversion Tests
    // ========================================================================

    @Test
    public void testLadderToObstacleDefinition() {
        PlaneTransitionHandler.PlaneTransition ladder = PlaneTransitionHandler.PlaneTransition.builder()
                .name("Test Ladder")
                .objectId(12345)
                .action("Climb")
                .transitionType(PlaneTransitionHandler.TransitionType.LADDER)
                .direction(PlaneTransitionHandler.TransitionDirection.BIDIRECTIONAL)
                .planeChange(0)
                .build();

        ObstacleDefinition def = ladder.toObstacleDefinition();

        assertEquals("Test Ladder", def.getName());
        assertEquals(ObstacleDefinition.ObstacleType.LADDER, def.getType());
        assertEquals("Climb", def.getAction());
        assertTrue(def.matches(12345));
    }

    @Test
    public void testStairsToObstacleDefinition() {
        PlaneTransitionHandler.PlaneTransition stairs = PlaneTransitionHandler.PlaneTransition.builder()
                .name("Test Stairs")
                .objectId(23456)
                .action("Climb-up")
                .transitionType(PlaneTransitionHandler.TransitionType.STAIRS)
                .direction(PlaneTransitionHandler.TransitionDirection.UP)
                .planeChange(1)
                .build();

        ObstacleDefinition def = stairs.toObstacleDefinition();

        assertEquals("Test Stairs", def.getName());
        assertEquals(ObstacleDefinition.ObstacleType.STAIRS, def.getType());
        assertEquals(1, def.getDestinationPlane());
    }

    @Test
    public void testTrapdoorToObstacleDefinition() {
        PlaneTransitionHandler.PlaneTransition trapdoor = PlaneTransitionHandler.PlaneTransition.builder()
                .name("Test Trapdoor")
                .objectId(34567)
                .action("Climb-down")
                .transitionType(PlaneTransitionHandler.TransitionType.TRAPDOOR)
                .direction(PlaneTransitionHandler.TransitionDirection.DOWN)
                .planeChange(-1)
                .requiresOpen(true)
                .openId(34568)
                .build();

        ObstacleDefinition def = trapdoor.toObstacleDefinition();

        assertEquals("Test Trapdoor", def.getName());
        assertEquals(ObstacleDefinition.ObstacleType.TRAPDOOR, def.getType());
        assertEquals(-1, def.getDestinationPlane());
    }

    // ========================================================================
    // Registration Tests
    // ========================================================================

    @Test
    public void testRegisterCustomTransition() {
        int initialCount = transitionHandler.getTransitionCount();

        PlaneTransitionHandler.PlaneTransition custom = PlaneTransitionHandler.PlaneTransition.builder()
                .name("Custom Transition")
                .objectId(99999)
                .action("Use")
                .transitionType(PlaneTransitionHandler.TransitionType.OTHER)
                .direction(PlaneTransitionHandler.TransitionDirection.BIDIRECTIONAL)
                .build();

        transitionHandler.registerTransition(custom);

        assertTrue("Should register custom transition",
                transitionHandler.getTransitionCount() > initialCount);
        assertTrue("Should find custom transition",
                transitionHandler.isPlaneTransition(99999));
    }

    // ========================================================================
    // DetectedTransition Tests
    // ========================================================================

    @Test
    public void testDetectedTransitionToString() {
        PlaneTransitionHandler.PlaneTransition def = PlaneTransitionHandler.PlaneTransition.builder()
                .name("Test Ladder")
                .objectId(12345)
                .action("Climb")
                .transitionType(PlaneTransitionHandler.TransitionType.LADDER)
                .direction(PlaneTransitionHandler.TransitionDirection.BIDIRECTIONAL)
                .build();

        PlaneTransitionHandler.DetectedTransition detected = 
                new PlaneTransitionHandler.DetectedTransition(def, null, null);

        String str = detected.toString();
        assertNotNull(str);
        assertTrue("Should contain name", str.contains("Test Ladder"));
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testAllTransitionsHaveRequiredFields() {
        List<PlaneTransitionHandler.PlaneTransition> all = transitionHandler.getAllTransitions();

        for (PlaneTransitionHandler.PlaneTransition t : all) {
            assertNotNull("Name should not be null", t.getName());
            assertNotNull("Action should not be null", t.getAction());
            assertNotNull("Type should not be null", t.getTransitionType());
            assertNotNull("Direction should not be null", t.getDirection());
        }
    }

    @Test
    public void testTransitionTypeCoverage() {
        // Ensure we have all main types covered
        List<PlaneTransitionHandler.PlaneTransition> all = transitionHandler.getAllTransitions();

        boolean hasLadder = false, hasStairs = false, hasTrapdoor = false;

        for (PlaneTransitionHandler.PlaneTransition t : all) {
            switch (t.getTransitionType()) {
                case LADDER: hasLadder = true; break;
                case STAIRS: hasStairs = true; break;
                case TRAPDOOR: hasTrapdoor = true; break;
            }
        }

        assertTrue("Should have ladder transitions", hasLadder);
        assertTrue("Should have stair transitions", hasStairs);
        assertTrue("Should have trapdoor transitions", hasTrapdoor);
    }
}

