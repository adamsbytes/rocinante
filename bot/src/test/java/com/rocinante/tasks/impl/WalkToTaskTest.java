package com.rocinante.tasks.impl;

import com.rocinante.navigation.NavigationEdge;
import com.rocinante.navigation.NavigationPath;
import com.rocinante.navigation.WebEdgeType;
import com.rocinante.tasks.impl.TravelTask;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for WalkToTask.
 * Tests constructors, builder methods, edge type handling, and TravelTask creation.
 */
public class WalkToTaskTest {

    // Test positions
    private static final WorldPoint LUMBRIDGE = new WorldPoint(3222, 3218, 0);
    private static final WorldPoint VARROCK = new WorldPoint(3213, 3424, 0);
    private static final WorldPoint NEARBY = new WorldPoint(3225, 3218, 0);

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Test
    public void testConstructor_WorldPoint() {
        WalkToTask task = new WalkToTask(VARROCK);

        assertEquals("Walk to (3213, 3424, 0)", task.getDescription());
    }

    @Test
    public void testConstructor_Coordinates() {
        WalkToTask task = new WalkToTask(3213, 3424);

        assertNotNull(task);
    }

    @Test
    public void testConstructor_CoordinatesWithPlane() {
        WalkToTask task = new WalkToTask(3213, 3424, 1);

        assertNotNull(task);
    }

    // ========================================================================
    // Builder Pattern Tests
    // ========================================================================

    @Test
    public void testWithDescription() {
        WalkToTask task = new WalkToTask(VARROCK)
                .withDescription("Custom walk task");

        assertEquals("Custom walk task", task.getDescription());
    }

    @Test
    public void testWithAgilityRiskThreshold() {
        WalkToTask task = new WalkToTask(VARROCK)
                .withAgilityRiskThreshold(0.15);

        assertEquals(0.15, task.getAgilityRiskThreshold(), 0.001);
    }

    @Test
    public void testAllowAllShortcuts() {
        WalkToTask task = new WalkToTask(VARROCK)
                .allowAllShortcuts();

        assertEquals(1.0, task.getAgilityRiskThreshold(), 0.001);
    }

    // ========================================================================
    // Task Description Tests
    // ========================================================================

    @Test
    public void testDescriptionFormat() {
        WalkToTask task = new WalkToTask(VARROCK);
        
        String desc = task.getDescription();
        assertTrue(desc.contains("Walk to"));
        assertTrue(desc.contains("3213"));
        assertTrue(desc.contains("3424"));
    }

    @Test
    public void testCustomDescriptionOverridesDefault() {
        WalkToTask task = new WalkToTask(VARROCK)
                .withDescription("Go to Varrock");

        assertEquals("Go to Varrock", task.getDescription());
    }

    // ========================================================================
    // NavigationEdge Creation Tests
    // ========================================================================

    @Test
    public void testWalkEdgeCreation() {
        NavigationEdge walkEdge = NavigationEdge.builder()
                .fromNodeId("node1")
                .toNodeId("node2")
                .type(WebEdgeType.WALK)
                .costTicks(10)
                .fromLocation(LUMBRIDGE)
                .toLocation(NEARBY)
                .build();

        assertEquals(WebEdgeType.WALK, walkEdge.getType());
        assertEquals(10, walkEdge.getCostTicks());
        assertEquals(LUMBRIDGE, walkEdge.getFromLocation());
        assertEquals(NEARBY, walkEdge.getToLocation());
    }

    @Test
    public void testStairsEdgeCreation() {
        NavigationEdge stairsEdge = NavigationEdge.builder()
                .fromNodeId("node1")
                .toNodeId("node2")
                .type(WebEdgeType.STAIRS)
                .costTicks(5)
                .fromPlane(0)
                .toPlane(1)
                .objectId(16671)
                .action("Climb-up")
                .fromLocation(LUMBRIDGE)
                .toLocation(new WorldPoint(LUMBRIDGE.getX(), LUMBRIDGE.getY(), 1))
                .build();

        assertEquals(WebEdgeType.STAIRS, stairsEdge.getType());
        assertTrue(stairsEdge.isPlaneTransition());
        assertEquals(0, stairsEdge.getFromPlane());
        assertEquals(1, stairsEdge.getToPlane());
        assertEquals(16671, stairsEdge.getObjectId());
        assertEquals("Climb-up", stairsEdge.getAction());
    }

    @Test
    public void testTeleportEdgeCreation() {
        Map<String, String> metadata = Map.of(
                "teleport_type", "spell",
                "spell_name", "Varrock Teleport"
        );

        NavigationEdge teleportEdge = NavigationEdge.builder()
                .fromNodeId("node1")
                .toNodeId("node2")
                .type(WebEdgeType.TELEPORT)
                .costTicks(5)
                .metadata(metadata)
                .fromLocation(LUMBRIDGE)
                .toLocation(VARROCK)
                .build();

        assertEquals(WebEdgeType.TELEPORT, teleportEdge.getType());
        assertEquals("spell", teleportEdge.getMetadata("teleport_type"));
        assertEquals("Varrock Teleport", teleportEdge.getMetadata("spell_name"));
    }

    @Test
    public void testTransportEdgeCreation() {
        Map<String, String> metadata = Map.of(
                "object_id", "12345",
                "action", "Travel",
                "name", "Ship"
        );

        NavigationEdge transportEdge = NavigationEdge.builder()
                .fromNodeId("node1")
                .toNodeId("node2")
                .type(WebEdgeType.TRANSPORT)
                .costTicks(30)
                .objectId(12345)
                .action("Travel")
                .metadata(metadata)
                .fromLocation(LUMBRIDGE)
                .toLocation(VARROCK)
                .build();

        assertEquals(WebEdgeType.TRANSPORT, transportEdge.getType());
        assertEquals(12345, transportEdge.getObjectId());
        assertEquals("Travel", transportEdge.getAction());
    }

    // ========================================================================
    // TravelTask Factory Method Tests
    // ========================================================================

    @Test
    public void testTravelTask_FromSpellMetadata() {
        Map<String, String> metadata = Map.of(
                "teleport_type", "spell",
                "spell_name", "Varrock Teleport"
        );

        TravelTask travelTask = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull(travelTask);
        assertEquals("Cast Varrock Teleport", travelTask.getDescription());
        assertEquals(TravelTask.TravelMethod.SPELL, travelTask.getMethod());
        assertEquals("Varrock Teleport", travelTask.getSpellName());
    }

    @Test
    public void testTravelTask_HomeTeleport() {
        Map<String, String> metadata = Map.of("teleport_type", "home_teleport");

        TravelTask travelTask = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull(travelTask);
        assertEquals("Home Teleport", travelTask.getDescription());
        assertEquals(TravelTask.TravelMethod.HOME_TELEPORT, travelTask.getMethod());
    }

    @Test
    public void testTravelTask_TabletMetadata() {
        Map<String, String> metadata = Map.of(
                "teleport_type", "tablet",
                "item_id", "8007"
        );

        TravelTask travelTask = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull(travelTask);
        assertEquals(TravelTask.TravelMethod.TABLET, travelTask.getMethod());
        assertEquals(8007, travelTask.getItemId());
    }

    @Test
    public void testTravelTask_JewelryMetadata() {
        Map<String, String> metadata = Map.of(
                "teleport_type", "jewelry",
                "item_id", "11978",
                "teleport_option", "Edgeville"
        );

        TravelTask travelTask = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull(travelTask);
        assertEquals(TravelTask.TravelMethod.JEWELRY_EQUIPPED, travelTask.getMethod());
        assertEquals(11978, travelTask.getItemId());
        assertEquals("Edgeville", travelTask.getTeleportOption());
    }

    @Test
    public void testTravelTask_JewelryInventoryMetadata() {
        Map<String, String> metadata = Map.of(
                "teleport_type", "jewelry",
                "item_id", "11978",
                "teleport_option", "Edgeville",
                "location", "inventory"
        );

        TravelTask travelTask = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull(travelTask);
        assertEquals(TravelTask.TravelMethod.JEWELRY_INVENTORY, travelTask.getMethod());
    }

    @Test
    public void testTravelTask_InvalidMetadata_ReturnsNull() {
        Map<String, String> metadata = Map.of("invalid_key", "value");

        TravelTask travelTask = TravelTask.fromNavigationMetadata(metadata);

        assertNull(travelTask);
    }

    @Test
    public void testTravelTask_NullMetadata_ReturnsNull() {
        TravelTask travelTask = TravelTask.fromNavigationMetadata(null);

        assertNull(travelTask);
    }

    @Test
    public void testTravelTask_UnknownType_ReturnsNull() {
        Map<String, String> metadata = Map.of("teleport_type", "unknown_type");

        TravelTask travelTask = TravelTask.fromNavigationMetadata(metadata);

        assertNull(travelTask);
    }

    // ========================================================================
    // TravelTask Direct Factory Methods
    // ========================================================================

    @Test
    public void testTravelTask_SpellFactory() {
        TravelTask task = TravelTask.spell("Varrock Teleport");

        assertEquals(TravelTask.TravelMethod.SPELL, task.getMethod());
        assertEquals("Varrock Teleport", task.getSpellName());
        assertEquals("Cast Varrock Teleport", task.getDescription());
    }

    @Test
    public void testTravelTask_SpellWithDestination() {
        TravelTask task = TravelTask.spell("Varrock Teleport", VARROCK);

        assertEquals(TravelTask.TravelMethod.SPELL, task.getMethod());
        assertEquals(VARROCK, task.getExpectedDestination());
    }

    @Test
    public void testTravelTask_HomeTeleportFactory() {
        TravelTask task = TravelTask.homeTeleport();

        assertEquals(TravelTask.TravelMethod.HOME_TELEPORT, task.getMethod());
        assertEquals("Home Teleport", task.getDescription());
    }

    @Test
    public void testTravelTask_TabletFactory() {
        TravelTask task = TravelTask.tablet(8007);

        assertEquals(TravelTask.TravelMethod.TABLET, task.getMethod());
        assertEquals(8007, task.getItemId());
    }

    @Test
    public void testTravelTask_JewelryFactory() {
        TravelTask task = TravelTask.jewelry(11978, "Edgeville");

        assertEquals(TravelTask.TravelMethod.JEWELRY_EQUIPPED, task.getMethod());
        assertEquals(11978, task.getItemId());
        assertEquals("Edgeville", task.getTeleportOption());
    }

    @Test
    public void testTravelTask_JewelryFromInventoryFactory() {
        TravelTask task = TravelTask.jewelryFromInventory(11978, "Edgeville");

        assertEquals(TravelTask.TravelMethod.JEWELRY_INVENTORY, task.getMethod());
    }

    // ========================================================================
    // TravelTask Builder Methods
    // ========================================================================

    @Test
    public void testTravelTask_WithDestination() {
        TravelTask task = TravelTask.spell("Varrock Teleport")
                .withDestination(VARROCK);

        assertEquals(VARROCK, task.getExpectedDestination());
    }

    @Test
    public void testTravelTask_WithTolerance() {
        TravelTask task = TravelTask.spell("Varrock Teleport")
                .withTolerance(15);

        assertEquals(15, task.getDestinationTolerance());
    }

    @Test
    public void testTravelTask_WithVerifyArrival() {
        TravelTask task = TravelTask.spell("Varrock Teleport")
                .withVerifyArrival(false);

        assertFalse(task.isVerifyArrival());
    }

    @Test
    public void testTravelTask_WithDescription() {
        TravelTask task = TravelTask.spell("Varrock Teleport")
                .withDescription("Custom teleport");

        assertEquals("Custom teleport", task.getDescription());
    }

    // ========================================================================
    // NavigationPath Tests
    // ========================================================================

    @Test
    public void testNavigationPath_Empty() {
        NavigationPath emptyPath = NavigationPath.empty();

        assertTrue(emptyPath.isEmpty());
        assertTrue(emptyPath.getEdges().isEmpty());
    }

    @Test
    public void testNavigationPath_WithEdges() {
        NavigationEdge edge = NavigationEdge.builder()
                .fromNodeId("a")
                .toNodeId("b")
                .type(WebEdgeType.WALK)
                .costTicks(5)
                .build();

        NavigationPath path = NavigationPath.builder()
                .edges(List.of(edge))
                .totalCostTicks(5)
                .startPoint(LUMBRIDGE)
                .endPoint(NEARBY)
                .build();

        assertFalse(path.isEmpty());
        assertEquals(1, path.getEdges().size());
        assertEquals(5, path.getTotalCostTicks());
        assertEquals(LUMBRIDGE, path.getStartPoint());
        assertEquals(NEARBY, path.getEndPoint());
    }
}
