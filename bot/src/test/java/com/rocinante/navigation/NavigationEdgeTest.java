package com.rocinante.navigation;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for NavigationEdge.
 */
public class NavigationEdgeTest {

    @Test
    public void testWalkEdgeFactory() {
        WorldPoint from = new WorldPoint(3200, 3200, 0);
        WorldPoint to = new WorldPoint(3210, 3200, 0);
        
        NavigationEdge edge = NavigationEdge.walk("node_a", "node_b", 20, from, to);
        
        assertEquals("node_a", edge.getFromNodeId());
        assertEquals("node_b", edge.getToNodeId());
        assertEquals(WebEdgeType.WALK, edge.getType());
        assertEquals(20, edge.getCostTicks());
        assertTrue(edge.isBidirectional());
        assertEquals(from, edge.getFromLocation());
        assertEquals(to, edge.getToLocation());
        
        assertFalse(edge.isPlaneTransition());
        assertFalse(edge.isAgilityShortcut());
        assertFalse(edge.isTollGate());
    }

    @Test
    public void testStairsEdgeFactory() {
        WorldPoint location = new WorldPoint(3208, 3220, 0);
        
        NavigationEdge edge = NavigationEdge.stairs(
                "lumbridge_castle", "lumbridge_bank", 5,
                0, 2, 16671, "Climb-up", location);
        
        assertEquals("lumbridge_castle", edge.getFromNodeId());
        assertEquals("lumbridge_bank", edge.getToNodeId());
        assertEquals(WebEdgeType.STAIRS, edge.getType());
        assertEquals(5, edge.getCostTicks());
        assertFalse(edge.isBidirectional());
        assertEquals(0, edge.getFromPlane());
        assertEquals(2, edge.getToPlane());
        assertEquals(16671, edge.getObjectId());
        assertEquals("Climb-up", edge.getAction());
        
        assertTrue(edge.isPlaneTransition());
        assertFalse(edge.isAgilityShortcut());
        assertTrue(edge.requiresInteraction());
    }

    @Test
    public void testAgilityShortcutFactory() {
        WorldPoint from = new WorldPoint(2935, 3355, 0);
        WorldPoint to = new WorldPoint(2936, 3355, 0);
        
        NavigationEdge edge = NavigationEdge.agilityShortcut(
                "shortcut_west", "shortcut_east", 3,
                5, 0.15, 24222, "Climb-over",
                from, to);
        
        assertEquals(WebEdgeType.AGILITY, edge.getType());
        assertEquals(5, edge.getRequiredAgilityLevel());
        assertEquals(0.15, edge.getFailureRate(), 0.01);
        
        assertTrue(edge.isAgilityShortcut());
        assertFalse(edge.isPlaneTransition());
        assertTrue(edge.hasRequirements());
    }

    @Test
    public void testTollGateFactory() {
        WorldPoint location = new WorldPoint(3268, 3227, 0);
        
        NavigationEdge edge = NavigationEdge.tollGate(
                "al_kharid_gate", "al_kharid_bank", 75,
                10, 44052, "Pay-toll", "PRINCE_ALI_RESCUE",
                location);
        
        assertEquals(WebEdgeType.TOLL, edge.getType());
        assertEquals(10, edge.getTollCost());
        assertEquals("PRINCE_ALI_RESCUE", edge.getFreePassageQuest());
        
        assertTrue(edge.isTollGate());
        assertFalse(edge.isAgilityShortcut());
    }

    @Test
    public void testFromWebEdge() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("object_id", "16671");
        metadata.put("action", "Climb-up");
        metadata.put("from_plane", "0");
        metadata.put("to_plane", "2");
        
        WebEdge webEdge = WebEdge.builder()
                .from("lumbridge_castle")
                .to("lumbridge_bank")
                .type(WebEdgeType.STAIRS)
                .costTicks(25)
                .bidirectional(true)
                .requirements(List.of())
                .metadata(metadata)
                .build();
        
        WorldPoint from = new WorldPoint(3222, 3218, 0);
        WorldPoint to = new WorldPoint(3208, 3220, 2);
        
        NavigationEdge edge = NavigationEdge.fromWebEdge(webEdge, from, to);
        
        assertEquals("lumbridge_castle", edge.getFromNodeId());
        assertEquals("lumbridge_bank", edge.getToNodeId());
        assertEquals(WebEdgeType.STAIRS, edge.getType());
        assertEquals(25, edge.getCostTicks());
        assertEquals(16671, edge.getObjectId());
        assertEquals("Climb-up", edge.getAction());
        assertEquals(0, edge.getFromPlane());
        assertEquals(2, edge.getToPlane());
    }

    @Test
    public void testMetadataAccess() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("failure_rate", "0.15");
        metadata.put("toll_cost", "10");
        metadata.put("notes", "Test edge");
        
        NavigationEdge edge = NavigationEdge.builder()
                .fromNodeId("a")
                .toNodeId("b")
                .type(WebEdgeType.AGILITY)
                .costTicks(5)
                .metadata(metadata)
                .build();
        
        assertEquals("Test edge", edge.getMetadata("notes"));
        assertEquals(0.15, edge.getMetadataDouble("failure_rate", 0), 0.01);
        assertEquals(10, edge.getMetadataInt("toll_cost", 0));
        assertEquals(0, edge.getMetadataInt("nonexistent", 0));
    }

    @Test
    public void testIsPlaneTransitionWithDifferentPlanes() {
        NavigationEdge edge = NavigationEdge.builder()
                .fromNodeId("a")
                .toNodeId("b")
                .type(WebEdgeType.WALK) // Not STAIRS type
                .costTicks(5)
                .fromPlane(0)
                .toPlane(1)
                .build();
        
        // Should still be detected as plane transition due to different planes
        assertTrue(edge.isPlaneTransition());
    }

    @Test
    public void testToString() {
        NavigationEdge edge = NavigationEdge.stairs(
                "a", "b", 5, 0, 1, 100, "Climb", new WorldPoint(0, 0, 0));
        
        String str = edge.toString();
        assertTrue(str.contains("a -> b"));
        assertTrue(str.contains("STAIRS"));
        assertTrue(str.contains("5 ticks"));
        assertTrue(str.contains("plane 0->1"));
    }
}
