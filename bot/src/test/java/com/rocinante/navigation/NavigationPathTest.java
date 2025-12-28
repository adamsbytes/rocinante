package com.rocinante.navigation;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for NavigationPath.
 */
public class NavigationPathTest {

    @Test
    public void testEmptyPath() {
        NavigationPath path = NavigationPath.empty();
        
        assertTrue(path.isEmpty());
        assertEquals(0, path.size());
        assertEquals(0, path.getTotalCostTicks());
        assertNull(path.getFirstEdge());
        assertNull(path.getLastEdge());
        assertFalse(path.requiresPlaneChange());
        assertFalse(path.requiresShortcuts());
        assertFalse(path.requiresTollGates());
    }

    @Test
    public void testSingleWalkPath() {
        WorldPoint from = new WorldPoint(3200, 3200, 0);
        WorldPoint to = new WorldPoint(3210, 3210, 0);
        
        NavigationPath path = NavigationPath.singleWalk(from, to, 20);
        
        assertFalse(path.isEmpty());
        assertEquals(1, path.size());
        assertEquals(20, path.getTotalCostTicks());
        assertNotNull(path.getFirstEdge());
        assertEquals(from, path.getStartPoint());
        assertEquals(to, path.getEndPoint());
    }

    @Test
    public void testPathWithMultipleEdges() {
        NavigationEdge walk = NavigationEdge.walk("a", "b", 10,
                new WorldPoint(0, 0, 0), new WorldPoint(10, 0, 0));
        NavigationEdge stairs = NavigationEdge.stairs("b", "c", 5,
                0, 1, 100, "Climb", new WorldPoint(10, 0, 0));
        NavigationEdge agility = NavigationEdge.agilityShortcut("c", "d", 3,
                33, 0.1, 200, "Cross",
                new WorldPoint(10, 0, 1), new WorldPoint(20, 0, 1));
        
        NavigationPath path = NavigationPath.builder()
                .edges(List.of(walk, stairs, agility))
                .nodeIds(List.of("a", "b", "c", "d"))
                .totalCostTicks(18)
                .startPoint(new WorldPoint(0, 0, 0))
                .endPoint(new WorldPoint(20, 0, 1))
                .build();
        
        assertEquals(3, path.size());
        assertEquals(18, path.getTotalCostTicks());
        assertTrue(path.requiresPlaneChange());
        assertTrue(path.requiresShortcuts());
        assertFalse(path.requiresTollGates());
        assertEquals(33, path.getRequiredAgilityLevel());
    }

    @Test
    public void testPathRequiresTollGates() {
        NavigationEdge toll = NavigationEdge.tollGate("a", "b", 75,
                10, 44052, "Pay-toll", "PRINCE_ALI_RESCUE",
                new WorldPoint(3268, 3227, 0));
        
        NavigationPath path = NavigationPath.builder()
                .edges(List.of(toll))
                .nodeIds(List.of("a", "b"))
                .totalCostTicks(75)
                .build();
        
        assertTrue(path.requiresTollGates());
        assertFalse(path.requiresShortcuts());
    }

    @Test
    public void testGetEdgeByIndex() {
        NavigationEdge edge1 = NavigationEdge.walk("a", "b", 10, null, null);
        NavigationEdge edge2 = NavigationEdge.walk("b", "c", 15, null, null);
        
        NavigationPath path = NavigationPath.builder()
                .edges(List.of(edge1, edge2))
                .nodeIds(List.of("a", "b", "c"))
                .totalCostTicks(25)
                .build();
        
        assertEquals(edge1, path.getEdge(0));
        assertEquals(edge2, path.getEdge(1));
        assertNull(path.getEdge(2));
        assertNull(path.getEdge(-1));
    }

    @Test
    public void testSubPath() {
        NavigationEdge edge1 = NavigationEdge.walk("a", "b", 10,
                new WorldPoint(0, 0, 0), new WorldPoint(10, 0, 0));
        NavigationEdge edge2 = NavigationEdge.walk("b", "c", 15,
                new WorldPoint(10, 0, 0), new WorldPoint(20, 0, 0));
        NavigationEdge edge3 = NavigationEdge.walk("c", "d", 5,
                new WorldPoint(20, 0, 0), new WorldPoint(25, 0, 0));
        
        NavigationPath fullPath = NavigationPath.builder()
                .edges(List.of(edge1, edge2, edge3))
                .nodeIds(List.of("a", "b", "c", "d"))
                .totalCostTicks(30)
                .startPoint(new WorldPoint(0, 0, 0))
                .endPoint(new WorldPoint(25, 0, 0))
                .build();
        
        NavigationPath subPath = fullPath.subPath(1);
        
        assertEquals(2, subPath.size());
        assertEquals(edge2, subPath.getEdge(0));
        assertEquals(edge3, subPath.getEdge(1));
    }

    @Test
    public void testEdgeTypes() {
        NavigationEdge walk = NavigationEdge.walk("a", "b", 10, null, null);
        NavigationEdge stairs = NavigationEdge.stairs("b", "c", 5, 0, 1, 100, "Climb", null);
        
        NavigationPath path = NavigationPath.builder()
                .edges(List.of(walk, stairs))
                .nodeIds(List.of("a", "b", "c"))
                .totalCostTicks(15)
                .build();
        
        var types = path.getEdgeTypes();
        assertEquals(2, types.size());
        assertTrue(types.contains(WebEdgeType.WALK));
        assertTrue(types.contains(WebEdgeType.STAIRS));
    }

    @Test
    public void testToString() {
        NavigationEdge edge = NavigationEdge.walk("a", "b", 10, null, null);
        NavigationPath path = NavigationPath.builder()
                .edges(List.of(edge))
                .nodeIds(List.of("a", "b"))
                .totalCostTicks(10)
                .build();
        
        String str = path.toString();
        assertTrue(str.contains("1 edges"));
        assertTrue(str.contains("10 ticks"));
    }

    @Test
    public void testToDetailedString() {
        NavigationEdge edge = NavigationEdge.walk("a", "b", 10,
                new WorldPoint(0, 0, 0), new WorldPoint(10, 0, 0));
        NavigationPath path = NavigationPath.builder()
                .edges(List.of(edge))
                .nodeIds(List.of("a", "b"))
                .totalCostTicks(10)
                .build();
        
        String str = path.toDetailedString();
        assertTrue(str.contains("NavigationPath"));
        assertTrue(str.contains("a -> b"));
    }
}
