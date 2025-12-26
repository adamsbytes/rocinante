package com.rocinante.navigation;

import net.runelite.api.Client;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Tests for ObstacleHandler door and gate handling.
 */
public class ObstacleHandlerTest {

    @Mock
    private Client client;

    @Mock
    private Scene scene;

    private ObstacleHandler obstacleHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        obstacleHandler = new ObstacleHandler(client);
    }

    // ========================================================================
    // Initialization Tests
    // ========================================================================

    @Test
    public void testRegistryInitialized() {
        assertTrue("Should have registered obstacles",
                obstacleHandler.getRegisteredObstacleCount() > 0);
    }

    @Test
    public void testCommonDoorsRegistered() {
        // Test some common door IDs are registered
        assertTrue("Should have wooden door registered",
                obstacleHandler.isKnownObstacle(1535));
        assertTrue("Should have another door state registered",
                obstacleHandler.isKnownObstacle(1534));
    }

    @Test
    public void testGatesRegistered() {
        // Test gate IDs
        assertTrue("Should have gate registered",
                obstacleHandler.isKnownObstacle(1551));
    }

    // ========================================================================
    // Obstacle Definition Tests
    // ========================================================================

    @Test
    public void testGetObstacleDefinition() {
        Optional<ObstacleDefinition> def = obstacleHandler.getObstacleDefinition(1535);

        assertTrue("Should find obstacle definition", def.isPresent());
        assertEquals("Wooden Door", def.get().getName());
        assertEquals(ObstacleDefinition.ObstacleType.DOOR, def.get().getType());
    }

    @Test
    public void testGetObstacleDefinitionNotFound() {
        Optional<ObstacleDefinition> def = obstacleHandler.getObstacleDefinition(999999);

        assertFalse("Should not find nonexistent obstacle", def.isPresent());
    }

    @Test
    public void testIsKnownObstacle() {
        assertTrue("Known obstacle should return true",
                obstacleHandler.isKnownObstacle(1535));
        assertFalse("Unknown ID should return false",
                obstacleHandler.isKnownObstacle(999999));
    }

    // ========================================================================
    // Traversal Cost Tests
    // ========================================================================

    @Test
    public void testGetTraversalCost() {
        int cost = obstacleHandler.getTraversalCost(1535);

        assertTrue("Traversal cost should be positive", cost > 0);
    }

    @Test
    public void testGetTraversalCostUnknown() {
        int cost = obstacleHandler.getTraversalCost(999999);

        assertEquals("Unknown obstacle should have 0 cost", 0, cost);
    }

    // ========================================================================
    // Obstacle Definition Factory Tests
    // ========================================================================

    @Test
    public void testDoorFactory() {
        ObstacleDefinition door = ObstacleDefinition.door("Test Door", 100, 101);

        assertEquals("Test Door", door.getName());
        assertEquals(ObstacleDefinition.ObstacleType.DOOR, door.getType());
        assertEquals(100, door.getBlockedStateId());
        assertEquals(101, door.getPassableStateId());
        assertEquals("Open", door.getAction());
        assertTrue(door.matches(100));
        assertTrue(door.matches(101));
        assertFalse(door.matches(999));
    }

    @Test
    public void testGateFactory() {
        ObstacleDefinition gate = ObstacleDefinition.gate("Test Gate", 200, 201);

        assertEquals("Test Gate", gate.getName());
        assertEquals(ObstacleDefinition.ObstacleType.GATE, gate.getType());
        assertEquals(200, gate.getBlockedStateId());
        assertEquals(201, gate.getPassableStateId());
        assertEquals("Open", gate.getAction());
    }

    @Test
    public void testAgilityShortcutFactory() {
        ObstacleDefinition shortcut = ObstacleDefinition.agilityShortcut(
                "Test Shortcut", 300, "Climb", 50, 0.9);

        assertEquals("Test Shortcut", shortcut.getName());
        assertEquals(ObstacleDefinition.ObstacleType.AGILITY_SHORTCUT, shortcut.getType());
        assertEquals(50, shortcut.getRequiredAgilityLevel());
        assertEquals(0.9, shortcut.getSuccessRate(), 0.001);
        assertEquals("Climb", shortcut.getAction());
    }

    // ========================================================================
    // Obstacle State Tests
    // ========================================================================

    @Test
    public void testIsBlocked() {
        ObstacleDefinition door = ObstacleDefinition.door("Door", 100, 101);

        assertTrue("Blocked state should be blocked", door.isBlocked(100));
        assertFalse("Passable state should not be blocked", door.isBlocked(101));
        assertFalse("Unknown state should not be blocked", door.isBlocked(999));
    }

    @Test
    public void testIsPassable() {
        ObstacleDefinition door = ObstacleDefinition.door("Door", 100, 101);

        assertFalse("Blocked state should not be passable", door.isPassable(100));
        assertTrue("Passable state should be passable", door.isPassable(101));
        assertFalse("Unknown state should not be passable", door.isPassable(999));
    }

    @Test
    public void testMatches() {
        ObstacleDefinition door = ObstacleDefinition.door("Door", 100, 101);

        assertTrue("Should match blocked ID", door.matches(100));
        assertTrue("Should match passable ID", door.matches(101));
        assertFalse("Should not match unknown ID", door.matches(999));
    }

    // ========================================================================
    // Custom Registration Tests
    // ========================================================================

    @Test
    public void testRegisterCustomObstacle() {
        ObstacleDefinition custom = ObstacleDefinition.builder()
                .name("Custom Obstacle")
                .type(ObstacleDefinition.ObstacleType.OTHER)
                .objectIds(java.util.List.of(12345, 12346))
                .blockedStateId(12345)
                .passableStateId(12346)
                .action("Use")
                .traversalCostTicks(5)
                .build();

        int initialCount = obstacleHandler.getRegisteredObstacleCount();
        obstacleHandler.registerObstacle(custom);

        assertTrue("Should register new obstacle",
                obstacleHandler.getRegisteredObstacleCount() > initialCount);
        assertTrue("Should find custom obstacle by ID",
                obstacleHandler.isKnownObstacle(12345));
        assertTrue("Should find custom obstacle by alternate ID",
                obstacleHandler.isKnownObstacle(12346));
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testObstacleDefinitionBuilder() {
        ObstacleDefinition.ObstacleDefinitionBuilder builder = ObstacleDefinition.builder();

        // Test builder with all fields
        ObstacleDefinition full = builder
                .name("Full Obstacle")
                .type(ObstacleDefinition.ObstacleType.DOOR)
                .objectIds(java.util.List.of(1, 2, 3))
                .blockedStateId(1)
                .passableStateId(2)
                .action("Open")
                .requiredAgilityLevel(0)
                .successRate(1.0)
                .traversalCostTicks(2)
                .build();

        assertNotNull(full);
        assertEquals("Full Obstacle", full.getName());
        assertEquals(3, full.getObjectIds().size());
    }

    @Test
    public void testObstacleDefinitionToString() {
        ObstacleDefinition door = ObstacleDefinition.door("Test Door", 100, 101);
        String str = door.toString();

        assertNotNull(str);
        assertTrue("toString should contain name", str.contains("Test Door"));
        assertTrue("toString should contain type", str.contains("DOOR"));
    }
}

