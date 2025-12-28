package com.rocinante.tasks.impl;

import com.rocinante.navigation.NavigationEdge;
import com.rocinante.navigation.NavigationPath;
import com.rocinante.navigation.WebEdgeType;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for WalkToTask.
 * Tests constructors, builder methods, edge type handling, and TeleportTask creation.
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
    // TeleportTask Factory Method Tests
    // ========================================================================

    @Test
    public void testTeleportTask_FromSpellMetadata() {
        Map<String, String> metadata = Map.of(
                "teleport_type", "spell",
                "spell_name", "Varrock Teleport"
        );

        TeleportTask teleportTask = TeleportTask.fromNavigationMetadata(metadata);

        assertNotNull(teleportTask);
        assertEquals("Cast Varrock Teleport", teleportTask.getDescription());
        assertEquals(TeleportTask.TeleportMethod.SPELL, teleportTask.getMethod());
        assertEquals("Varrock Teleport", teleportTask.getSpellName());
    }

    @Test
    public void testTeleportTask_HomeTeleport() {
        Map<String, String> metadata = Map.of("teleport_type", "home_teleport");

        TeleportTask teleportTask = TeleportTask.fromNavigationMetadata(metadata);

        assertNotNull(teleportTask);
        assertEquals("Home Teleport", teleportTask.getDescription());
        assertEquals(TeleportTask.TeleportMethod.HOME_TELEPORT, teleportTask.getMethod());
    }

    @Test
    public void testTeleportTask_TabletMetadata() {
        Map<String, String> metadata = Map.of(
                "teleport_type", "tablet",
                "item_id", "8007"
        );

        TeleportTask teleportTask = TeleportTask.fromNavigationMetadata(metadata);

        assertNotNull(teleportTask);
        assertEquals(TeleportTask.TeleportMethod.TABLET, teleportTask.getMethod());
        assertEquals(8007, teleportTask.getItemId());
    }

    @Test
    public void testTeleportTask_JewelryMetadata() {
        Map<String, String> metadata = Map.of(
                "teleport_type", "jewelry",
                "item_id", "11978",
                "teleport_option", "Edgeville"
        );

        TeleportTask teleportTask = TeleportTask.fromNavigationMetadata(metadata);

        assertNotNull(teleportTask);
        assertEquals(TeleportTask.TeleportMethod.JEWELRY_EQUIPPED, teleportTask.getMethod());
        assertEquals(11978, teleportTask.getItemId());
        assertEquals("Edgeville", teleportTask.getTeleportOption());
    }

    @Test
    public void testTeleportTask_JewelryInventoryMetadata() {
        Map<String, String> metadata = Map.of(
                "teleport_type", "jewelry",
                "item_id", "11978",
                "teleport_option", "Edgeville",
                "location", "inventory"
        );

        TeleportTask teleportTask = TeleportTask.fromNavigationMetadata(metadata);

        assertNotNull(teleportTask);
        assertEquals(TeleportTask.TeleportMethod.JEWELRY_INVENTORY, teleportTask.getMethod());
    }

    @Test
    public void testTeleportTask_InvalidMetadata_ReturnsNull() {
        Map<String, String> metadata = Map.of("invalid_key", "value");

        TeleportTask teleportTask = TeleportTask.fromNavigationMetadata(metadata);

        assertNull(teleportTask);
    }

    @Test
    public void testTeleportTask_NullMetadata_ReturnsNull() {
        TeleportTask teleportTask = TeleportTask.fromNavigationMetadata(null);

        assertNull(teleportTask);
    }

    @Test
    public void testTeleportTask_UnknownType_ReturnsNull() {
        Map<String, String> metadata = Map.of("teleport_type", "unknown_type");

        TeleportTask teleportTask = TeleportTask.fromNavigationMetadata(metadata);

        assertNull(teleportTask);
    }

    // ========================================================================
    // TeleportTask Direct Factory Methods
    // ========================================================================

    @Test
    public void testTeleportTask_SpellFactory() {
        TeleportTask task = TeleportTask.spell("Varrock Teleport");

        assertEquals(TeleportTask.TeleportMethod.SPELL, task.getMethod());
        assertEquals("Varrock Teleport", task.getSpellName());
        assertEquals("Cast Varrock Teleport", task.getDescription());
    }

    @Test
    public void testTeleportTask_SpellWithDestination() {
        TeleportTask task = TeleportTask.spell("Varrock Teleport", VARROCK);

        assertEquals(TeleportTask.TeleportMethod.SPELL, task.getMethod());
        assertEquals(VARROCK, task.getExpectedDestination());
    }

    @Test
    public void testTeleportTask_HomeTeleportFactory() {
        TeleportTask task = TeleportTask.homeTeleport();

        assertEquals(TeleportTask.TeleportMethod.HOME_TELEPORT, task.getMethod());
        assertEquals("Home Teleport", task.getDescription());
    }

    @Test
    public void testTeleportTask_TabletFactory() {
        TeleportTask task = TeleportTask.tablet(8007);

        assertEquals(TeleportTask.TeleportMethod.TABLET, task.getMethod());
        assertEquals(8007, task.getItemId());
    }

    @Test
    public void testTeleportTask_JewelryFactory() {
        TeleportTask task = TeleportTask.jewelry(11978, "Edgeville");

        assertEquals(TeleportTask.TeleportMethod.JEWELRY_EQUIPPED, task.getMethod());
        assertEquals(11978, task.getItemId());
        assertEquals("Edgeville", task.getTeleportOption());
    }

    @Test
    public void testTeleportTask_JewelryFromInventoryFactory() {
        TeleportTask task = TeleportTask.jewelryFromInventory(11978, "Edgeville");

        assertEquals(TeleportTask.TeleportMethod.JEWELRY_INVENTORY, task.getMethod());
    }

    // ========================================================================
    // TeleportTask Builder Methods
    // ========================================================================

    @Test
    public void testTeleportTask_WithDestination() {
        TeleportTask task = TeleportTask.spell("Varrock Teleport")
                .withDestination(VARROCK);

        assertEquals(VARROCK, task.getExpectedDestination());
    }

    @Test
    public void testTeleportTask_WithTolerance() {
        TeleportTask task = TeleportTask.spell("Varrock Teleport")
                .withTolerance(15);

        assertEquals(15, task.getDestinationTolerance());
    }

    @Test
    public void testTeleportTask_WithVerifyArrival() {
        TeleportTask task = TeleportTask.spell("Varrock Teleport")
                .withVerifyArrival(false);

        assertFalse(task.isVerifyArrival());
    }

    @Test
    public void testTeleportTask_WithDescription() {
        TeleportTask task = TeleportTask.spell("Varrock Teleport")
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
