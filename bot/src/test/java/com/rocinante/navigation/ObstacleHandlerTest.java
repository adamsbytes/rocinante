package com.rocinante.navigation;

import net.runelite.api.Client;
import net.runelite.api.Scene;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for ObstacleHandler door, gate, and agility shortcut handling.
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
        ObstacleDefinition def = obstacleHandler.getObstacleDefinition(1535);

        assertNotNull("Should find obstacle definition", def);
        assertEquals("Wooden Door", def.getName());
        assertEquals(ObstacleDefinition.ObstacleType.DOOR, def.getType());
    }

    @Test
    public void testGetObstacleDefinitionNotFound() {
        ObstacleDefinition def = obstacleHandler.getObstacleDefinition(999999);

        assertNull("Should not find nonexistent obstacle", def);
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
        assertEquals(0.9, shortcut.getBaseSuccessRate(), 0.001);
        assertEquals("Climb", shortcut.getAction());
    }

    @Test
    public void testLadderFactory() {
        ObstacleDefinition ladder = ObstacleDefinition.ladder("Test Ladder", 400, "Climb-up", 1);

        assertEquals("Test Ladder", ladder.getName());
        assertEquals(ObstacleDefinition.ObstacleType.LADDER, ladder.getType());
        assertEquals("Climb-up", ladder.getAction());
        assertEquals(1, ladder.getDestinationPlane());
    }

    @Test
    public void testStaircaseFactory() {
        ObstacleDefinition stairs = ObstacleDefinition.staircase("Test Stairs", 500, "Climb-down", 0);

        assertEquals("Test Stairs", stairs.getName());
        assertEquals(ObstacleDefinition.ObstacleType.STAIRS, stairs.getType());
        assertEquals("Climb-down", stairs.getAction());
        assertEquals(0, stairs.getDestinationPlane());
    }

    @Test
    public void testTrapdoorFactory() {
        ObstacleDefinition trapdoor = ObstacleDefinition.trapdoor("Test Trapdoor", 600, 601, "Climb-down", -1);

        assertEquals("Test Trapdoor", trapdoor.getName());
        assertEquals(ObstacleDefinition.ObstacleType.TRAPDOOR, trapdoor.getType());
        assertEquals(-1, trapdoor.getDestinationPlane());
        assertTrue(trapdoor.matches(600));
        assertTrue(trapdoor.matches(601));
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
    // Agility Level Requirement Tests
    // ========================================================================

    @Test
    public void testCanAttempt() {
        ObstacleDefinition shortcut = ObstacleDefinition.agilityShortcut(
                "Test Shortcut", 300, "Climb", 50, 0.9);

        assertFalse("Should not be able to attempt at level 49", shortcut.canAttempt(49));
        assertTrue("Should be able to attempt at level 50", shortcut.canAttempt(50));
        assertTrue("Should be able to attempt at level 99", shortcut.canAttempt(99));
    }

    @Test
    public void testCanAttemptNoRequirement() {
        ObstacleDefinition door = ObstacleDefinition.door("Door", 100, 101);

        assertTrue("Door should be attemptable at any level", door.canAttempt(1));
        assertTrue("Door should be attemptable at any level", door.canAttempt(99));
    }

    // ========================================================================
    // Success Rate Calculation Tests
    // ========================================================================

    @Test
    public void testCalculateSuccessRateBelowLevel() {
        ObstacleDefinition shortcut = ObstacleDefinition.agilityShortcut(
                "Test Shortcut", 300, "Climb", 50, 0.9);

        assertEquals("Success rate should be 0 below level", 0.0, shortcut.calculateSuccessRate(49), 0.001);
    }

    @Test
    public void testCalculateSuccessRateAtLevel() {
        ObstacleDefinition shortcut = ObstacleDefinition.agilityShortcut(
                "Test Shortcut", 300, "Climb", 50, 0.9);

        assertEquals("Success rate should be base rate at level", 0.9, shortcut.calculateSuccessRate(50), 0.001);
    }

    @Test
    public void testCalculateSuccessRateAboveLevel() {
        ObstacleDefinition shortcut = ObstacleDefinition.agilityShortcut(
                "Test Shortcut", 300, "Climb", 50, 0.9);

        // 5 levels above = +2%
        assertEquals("Success rate should increase", 0.92, shortcut.calculateSuccessRate(55), 0.001);

        // 10 levels above = +4%
        assertEquals("Success rate should increase", 0.94, shortcut.calculateSuccessRate(60), 0.001);

        // 45 levels above = +18% (capped at 100%)
        // 0.9 + 0.18 = 1.08, but capped at 1.0
        assertEquals("Success rate should cap at 1.0", 1.0, shortcut.calculateSuccessRate(95), 0.001);
    }

    @Test
    public void testCalculateSuccessRateSafeShortcut() {
        ObstacleDefinition shortcut = ObstacleDefinition.agilityShortcut(
                "Safe Shortcut", 300, "Climb", 1, 1.0);

        assertEquals("Safe shortcut should always succeed", 1.0, shortcut.calculateSuccessRate(1), 0.001);
        assertEquals("Safe shortcut should always succeed", 1.0, shortcut.calculateSuccessRate(99), 0.001);
    }

    @Test
    public void testCalculateFailureRate() {
        ObstacleDefinition shortcut = ObstacleDefinition.agilityShortcut(
                "Test Shortcut", 300, "Climb", 50, 0.9);

        assertEquals("Failure rate should be inverse of success", 0.1, shortcut.calculateFailureRate(50), 0.001);
        assertEquals("Failure rate should be 1.0 below level", 1.0, shortcut.calculateFailureRate(49), 0.001);
    }

    // ========================================================================
    // Risk Assessment Tests
    // ========================================================================

    @Test
    public void testIsRisky() {
        ObstacleDefinition shortcut = ObstacleDefinition.agilityShortcut(
                "Test Shortcut", 300, "Climb", 50, 0.9);

        // At level 50, failure rate is 10%, threshold is 10%
        assertFalse("Should not be risky at exactly threshold", shortcut.isRisky(50, 0.10));

        // Below threshold
        assertTrue("Should be risky below level", shortcut.isRisky(49, 0.10));

        // With tighter threshold
        assertTrue("Should be risky with tight threshold", shortcut.isRisky(50, 0.05));

        // Well above level
        assertFalse("Should not be risky well above level", shortcut.isRisky(70, 0.10));
    }

    // ========================================================================
    // ObstacleHandler Risk Methods Tests
    // ========================================================================

    @Test
    public void testCanTraverse() {
        // Register a shortcut for testing
        ObstacleDefinition shortcut = ObstacleDefinition.agilityShortcut(
                "Test Shortcut", 88888, "Climb", 50, 0.9);
        obstacleHandler.registerObstacle(shortcut);

        assertFalse("Should not be able to traverse below level", obstacleHandler.canTraverse(88888, 49));
        assertTrue("Should be able to traverse at level", obstacleHandler.canTraverse(88888, 50));
        assertTrue("Should be able to traverse above level", obstacleHandler.canTraverse(88888, 99));
    }

    @Test
    public void testGetFailureRate() {
        ObstacleDefinition shortcut = ObstacleDefinition.agilityShortcut(
                "Test Shortcut", 77777, "Climb", 50, 0.9);
        obstacleHandler.registerObstacle(shortcut);

        assertEquals("Failure rate should match", 0.1, obstacleHandler.getFailureRate(77777, 50), 0.001);
        assertEquals("Unknown obstacle should have 0 failure rate", 0.0, obstacleHandler.getFailureRate(99999, 50), 0.001);
    }

    @Test
    public void testShouldAvoidObstacle() {
        ObstacleDefinition shortcut = ObstacleDefinition.agilityShortcut(
                "Test Shortcut", 66666, "Climb", 50, 0.85);
        obstacleHandler.registerObstacle(shortcut);

        // At level 50, base failure rate is 15%
        assertTrue("Should avoid risky shortcut at required level",
                obstacleHandler.shouldAvoidObstacle(66666, 50, 0.10));

        // At higher level, failure rate decreases
        // Level 60: +4% success, so 89% success, 11% failure - still above 10%
        assertTrue("Should still avoid at level 60",
                obstacleHandler.shouldAvoidObstacle(66666, 60, 0.10));

        // Level 70: +8% success, so 93% success, 7% failure - below 10%
        assertFalse("Should not avoid at level 70",
                obstacleHandler.shouldAvoidObstacle(66666, 70, 0.10));

        // Below required level
        assertTrue("Should avoid below level",
                obstacleHandler.shouldAvoidObstacle(66666, 49));
    }

    @Test
    public void testShouldAvoidNonShortcut() {
        // Doors and gates should never be "avoided"
        ObstacleDefinition door = ObstacleDefinition.door("Test Door", 55555, 55556);
        obstacleHandler.registerObstacle(door);

        assertFalse("Should not avoid doors", obstacleHandler.shouldAvoidObstacle(55555, 1, 0.0));
    }

    // ========================================================================
    // Type Checking Tests
    // ========================================================================

    @Test
    public void testIsAgilityShortcut() {
        ObstacleDefinition shortcut = ObstacleDefinition.agilityShortcut(
                "Test Shortcut", 300, "Climb", 50, 0.9);
        ObstacleDefinition door = ObstacleDefinition.door("Door", 100, 101);

        assertTrue("Agility shortcut should be identified", shortcut.isAgilityShortcut());
        assertFalse("Door should not be agility shortcut", door.isAgilityShortcut());
    }

    @Test
    public void testIsDoorOrGate() {
        ObstacleDefinition door = ObstacleDefinition.door("Door", 100, 101);
        ObstacleDefinition gate = ObstacleDefinition.gate("Gate", 200, 201);
        ObstacleDefinition shortcut = ObstacleDefinition.agilityShortcut(
                "Shortcut", 300, "Climb", 50, 0.9);

        assertTrue("Door should be identified", door.isDoorOrGate());
        assertTrue("Gate should be identified", gate.isDoorOrGate());
        assertFalse("Shortcut should not be door/gate", shortcut.isDoorOrGate());
    }

    @Test
    public void testIsPlaneTransition() {
        ObstacleDefinition ladder = ObstacleDefinition.ladder("Ladder", 400, "Climb", 1);
        ObstacleDefinition stairs = ObstacleDefinition.staircase("Stairs", 500, "Climb", 1);
        ObstacleDefinition trapdoor = ObstacleDefinition.trapdoor("Trapdoor", 600, 601, "Climb-down", -1);
        ObstacleDefinition door = ObstacleDefinition.door("Door", 100, 101);

        assertTrue("Ladder should be plane transition", ladder.isPlaneTransition());
        assertTrue("Stairs should be plane transition", stairs.isPlaneTransition());
        assertTrue("Trapdoor should be plane transition", trapdoor.isPlaneTransition());
        assertFalse("Door should not be plane transition", door.isPlaneTransition());
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
                .baseSuccessRate(1.0)
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

    @Test
    public void testAgilityShortcutWithMultipleIds() {
        ObstacleDefinition shortcut = ObstacleDefinition.agilityShortcut(
                "Multi-ID Shortcut",
                List.of(1001, 1002, 1003),
                "Jump",
                30,
                0.95,
                null);

        assertEquals(3, shortcut.getObjectIds().size());
        assertTrue(shortcut.matches(1001));
        assertTrue(shortcut.matches(1002));
        assertTrue(shortcut.matches(1003));
        assertFalse(shortcut.matches(1004));
    }

    // ========================================================================
    // Toll Gate Tests
    // ========================================================================

    @Test
    public void testTollGateFactory() {
        ObstacleDefinition tollGate = ObstacleDefinition.tollGate(
                "Test Toll Gate", 12345, "Pay-toll(10gp)", 10, 1000, null);

        assertEquals("Test Toll Gate", tollGate.getName());
        assertEquals(ObstacleDefinition.ObstacleType.TOLL_GATE, tollGate.getType());
        assertEquals(10, tollGate.getTollCost());
        assertEquals(1000, tollGate.getMinimumGoldToUse());
        assertNull(tollGate.getFreePassageQuest());
        assertTrue(tollGate.isTollGate());
    }

    @Test
    public void testItemTollGateFactory() {
        ObstacleDefinition itemTollGate = ObstacleDefinition.itemTollGate(
                "Test Item Toll", 23456, "Go-through", 1854, 5, 1000);

        assertEquals("Test Item Toll", itemTollGate.getName());
        assertEquals(ObstacleDefinition.ObstacleType.TOLL_GATE, itemTollGate.getType());
        assertEquals(1854, itemTollGate.getRequiredItemId());
        assertEquals(5, itemTollGate.getTollCost());
        assertEquals(1000, itemTollGate.getMinimumGoldToUse());
        assertTrue(itemTollGate.isTollGate());
    }

    @Test
    public void testTollGateCanAffordToll() {
        ObstacleDefinition tollGate = ObstacleDefinition.tollGate(
                "Al Kharid Gate", 12345, "Pay-toll(10gp)", 10, 1000, null);

        // Can't afford with less than toll cost
        assertFalse(tollGate.canAffordToll(5, true));

        // Can afford with toll but below minimum
        assertFalse(tollGate.canAffordToll(500, true));

        // Can afford with minimum gold
        assertTrue(tollGate.canAffordToll(1000, true));

        // Can afford with more than minimum
        assertTrue(tollGate.canAffordToll(5000, true));
    }

    @Test
    public void testItemTollGateCanAffordToll() {
        ObstacleDefinition itemTollGate = ObstacleDefinition.itemTollGate(
                "Shantay Pass", 23456, "Go-through", 1854, 5, 1000);

        // Can't use without required item
        assertFalse(itemTollGate.canAffordToll(5000, false));

        // Can use with required item and enough gold
        assertTrue(itemTollGate.canAffordToll(1000, true));
    }

    @Test
    public void testTollGateShouldUseTollGate() {
        ObstacleDefinition tollGate = ObstacleDefinition.tollGate(
                "Al Kharid Gate", 12345, "Pay-toll(10gp)", 10, 1000, null);

        // Should not use below minimum
        assertFalse(tollGate.shouldUseTollGate(500));

        // Should use at or above minimum
        assertTrue(tollGate.shouldUseTollGate(1000));
        assertTrue(tollGate.shouldUseTollGate(5000));
    }

    @Test
    public void testTollGateFreePassage() {
        // Note: This test would need proper mocking of Quest enum
        // For now, test with null (no free passage quest)
        ObstacleDefinition tollGate = ObstacleDefinition.tollGate(
                "Al Kharid Gate", 12345, "Pay-toll(10gp)", 10, 1000, null);

        // No free passage without quest
        assertFalse(tollGate.hasFreePassage(true));
        assertFalse(tollGate.hasFreePassage(false));
    }

    @Test
    public void testTollGateHighTraversalCost() {
        ObstacleDefinition tollGate = ObstacleDefinition.tollGate(
                "Test Toll Gate", 12345, "Pay-toll(10gp)", 10, 1000, null);
        ObstacleDefinition regularGate = ObstacleDefinition.gate("Regular Gate", 100, 101);

        // Toll gates should have higher traversal cost than regular gates
        assertTrue("Toll gate should have higher cost than regular gate",
                tollGate.getTraversalCostTicks() > regularGate.getTraversalCostTicks());
    }

    @Test
    public void testObstacleHandlerTollGateMethods() {
        // Register a toll gate
        ObstacleDefinition tollGate = ObstacleDefinition.tollGate(
                "Test Toll Gate", 99999, "Pay-toll(10gp)", 10, 1000, null);
        obstacleHandler.registerObstacle(tollGate);

        // Test isTollGate
        assertTrue(obstacleHandler.isTollGate(99999));
        assertFalse(obstacleHandler.isTollGate(1535)); // Regular door

        // Test getTollCost
        assertEquals(10, obstacleHandler.getTollCost(99999));
        assertEquals(0, obstacleHandler.getTollCost(1535)); // Regular door

        // Test shouldUseTollGate
        assertTrue(obstacleHandler.shouldUseTollGate(99999, 5000));
        assertFalse(obstacleHandler.shouldUseTollGate(99999, 500));

        // Test canUseTollGate
        assertTrue(obstacleHandler.canUseTollGate(99999, 1000, false, true));
        assertFalse(obstacleHandler.canUseTollGate(99999, 500, false, true));
    }

    @Test
    public void testNonTollGatesNotAffected() {
        ObstacleDefinition door = ObstacleDefinition.door("Test Door", 100, 101);

        assertFalse("Regular door should not be toll gate", door.isTollGate());
        assertEquals(0, door.getTollCost());
        assertEquals(-1, door.getRequiredItemId());
        assertTrue("Regular door should always be usable", door.shouldUseTollGate(0));
    }
}
