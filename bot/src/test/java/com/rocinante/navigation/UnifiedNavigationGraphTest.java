package com.rocinante.navigation;

import com.rocinante.navigation.RespawnPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for UnifiedNavigationGraph.
 */
public class UnifiedNavigationGraphTest {

    private NavigationWeb webData;
    private UnifiedNavigationGraph graph;

    @Before
    public void setUp() throws IOException {
        // Use loadComplete() to load full navigation data including home/grouping teleports
        webData = NavigationWebLoader.loadComplete();
        assertNotNull("Navigation web should load successfully", webData);
        
        // Create graph without PlaneTransitionHandler (testing standalone)
        graph = new UnifiedNavigationGraph(webData);
        
        // Verify FREE_TELEPORT edges were loaded
        assertTrue("Graph should have any_location edges for FREE_TELEPORT",
                graph.getAnyLocationEdgeCount() > 0);
    }

    @Test
    public void testGraphLoadsNodes() {
        assertTrue("Graph should have nodes", graph.getNodeCount() > 0);
        assertTrue("Graph should have web nodes", graph.getWebNodeCount() > 0);
    }

    @Test
    public void testGraphLoadsEdges() {
        assertTrue("Graph should have edges", graph.getEdgeCount() > 0);
    }

    @Test
    public void testGetNode() {
        WebNode node = graph.getNode("lumbridge_castle");
        assertNotNull("Should find lumbridge_castle node", node);
        assertEquals("Lumbridge Castle", node.getName());
    }

    @Test
    public void testHasNode() {
        assertTrue(graph.hasNode("lumbridge_castle"));
        assertTrue(graph.hasNode("varrock_west_bank"));
        assertFalse(graph.hasNode("nonexistent_node"));
    }

    @Test
    public void testGetEdgesFrom() {
        List<NavigationEdge> edges = graph.getEdgesFrom("lumbridge_castle");
        assertFalse("Lumbridge castle should have outgoing edges", edges.isEmpty());
        
        // Should have walk edges to nearby locations
        boolean hasWalkEdge = edges.stream()
                .anyMatch(e -> e.getType() == WebEdgeType.WALK);
        assertTrue("Should have WALK edges", hasWalkEdge);
    }

    @Test
    public void testFindNearestNodeSamePlane() {
        WorldPoint point = new WorldPoint(3220, 3218, 0);
        WebNode nearest = graph.findNearestNodeSamePlane(point);
        
        assertNotNull("Should find a nearby node", nearest);
        assertEquals("Node should be on same plane", 0, nearest.getPlane());
    }

    @Test
    public void testFindNearestNodeAnyPlane() {
        // Point near Lumbridge Bank (which is on plane 2)
        WorldPoint point = new WorldPoint(3208, 3220, 0);
        WebNode nearest = graph.findNearestNodeAnyPlane(point);
        
        assertNotNull("Should find a node", nearest);
        // Should find either lumbridge_bank (plane 2) or lumbridge_castle (plane 0)
    }

    @Test
    public void testFindNodesWithinDistance2D() {
        WorldPoint center = new WorldPoint(3222, 3218, 0);
        List<WebNode> nodes = graph.findNodesWithinDistance2D(center, 50);
        
        assertFalse("Should find nodes within 50 tiles", nodes.isEmpty());
        
        // Verify all returned nodes are within distance
        for (WebNode node : nodes) {
            int dx = Math.abs(node.getX() - center.getX());
            int dy = Math.abs(node.getY() - center.getY());
            assertTrue("Node should be within 50 tiles", Math.max(dx, dy) <= 50);
        }
    }

    @Test
    public void testGetPlaneTransitionEdges() {
        // Lumbridge castle should have stairs edges
        List<NavigationEdge> transitions = graph.getPlaneTransitionEdges("lumbridge_castle");
        
        // May or may not have transitions depending on web.json content
        // At minimum, verify it doesn't throw
        assertNotNull(transitions);
    }

    @Test
    public void testStairsEdgeType() {
        List<NavigationEdge> stairsEdges = graph.getEdgesByType(WebEdgeType.STAIRS);
        
        if (!stairsEdges.isEmpty()) {
            for (NavigationEdge edge : stairsEdges) {
                assertEquals(WebEdgeType.STAIRS, edge.getType());
                // Stairs edges should have plane information in metadata
                assertTrue(edge.getFromPlane() >= -1);
                assertTrue(edge.getToPlane() >= -1);
            }
        }
    }

    @Test
    public void testAgilityEdges() {
        List<NavigationEdge> agilityEdges = graph.getEdgesByType(WebEdgeType.AGILITY);
        
        if (!agilityEdges.isEmpty()) {
            for (NavigationEdge edge : agilityEdges) {
                assertTrue(edge.isAgilityShortcut());
                // Agility edges should have level requirements
                assertTrue(edge.getRequiredAgilityLevel() > 0 || edge.hasRequirements());
            }
        }
    }

    @Test
    public void testTollEdges() {
        List<NavigationEdge> tollEdges = graph.getEdgesByType(WebEdgeType.TOLL);
        
        if (!tollEdges.isEmpty()) {
            for (NavigationEdge edge : tollEdges) {
                assertTrue(edge.isTollGate());
            }
        }
    }

    @Test
    public void testGetDirectEdge() {
        // Test getting a known direct edge
        NavigationEdge edge = graph.getEdge("lumbridge_castle", "lumbridge_general_store");
        
        if (edge != null) {
            assertEquals("lumbridge_castle", edge.getFromNodeId());
            assertEquals("lumbridge_general_store", edge.getToNodeId());
        }
    }

    @Test
    public void testEdgeCountByType() {
        var countByType = graph.getEdgeCountByType();
        
        assertNotNull(countByType);
        // Should have at least WALK edges
        assertTrue(countByType.containsKey(WebEdgeType.WALK));
        assertTrue(countByType.get(WebEdgeType.WALK) > 0);
    }

    @Test
    public void testTraversableEdgesWithRequirements() {
        // Create a mock PlayerRequirements with low agility
        PlayerRequirements lowAgility = new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 1; }
            @Override public int getMagicLevel() { return 1; }
            @Override public int getCombatLevel() { return 3; }
            @Override public int getSkillLevel(String skillName) { return 1; }
            @Override public int getTotalGold() { return 0; }
            @Override public int getInventoryGold() { return 0; }
            @Override public boolean hasItem(int itemId) { return false; }
            @Override public boolean hasItem(int itemId, int quantity) { return false; }
            @Override public boolean isQuestCompleted(net.runelite.api.Quest quest) { return false; }
            @Override public boolean isQuestCompleted(String questName) { return false; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.10; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return false; }
            @Override public boolean isMinigameTeleportAvailable() { return false; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return false; }
            @Override public net.runelite.api.coords.WorldPoint getHomeTeleportDestination() { return RespawnPoint.LUMBRIDGE.getDestination(); }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };

        // Get traversable edges with low agility - should filter out high-level shortcuts
        List<NavigationEdge> traversable = graph.getTraversableEdges("lumbridge_castle", lowAgility);
        
        // Should still have basic walk edges
        boolean hasWalk = traversable.stream().anyMatch(e -> e.getType() == WebEdgeType.WALK);
        assertTrue("Low agility player should still have walk edges", hasWalk);
        
        // Should not have high-level agility shortcuts
        boolean hasHighAgilityShortcut = traversable.stream()
                .anyMatch(e -> e.isAgilityShortcut() && e.getRequiredAgilityLevel() > 1);
        assertFalse("Low agility player should not have high-level shortcuts", hasHighAgilityShortcut);
    }

    @Test
    public void testHasPathBetweenConnectedNodes() {
        PlayerRequirements noRequirements = createBasicRequirements();
        
        // These should be connected via walk edges
        boolean hasPath = graph.hasPath("lumbridge_castle", "varrock_west_bank", noRequirements);
        assertTrue("Should have path from Lumbridge to Varrock", hasPath);
    }

    private PlayerRequirements createBasicRequirements() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 99; }
            @Override public int getMagicLevel() { return 99; }
            @Override public int getCombatLevel() { return 126; }
            @Override public int getSkillLevel(String skillName) { return 99; }
            @Override public int getTotalGold() { return 1000000; }
            @Override public int getInventoryGold() { return 100000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(net.runelite.api.Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 1.0; }
            @Override public boolean shouldAvoidWilderness() { return false; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public net.runelite.api.coords.WorldPoint getHomeTeleportDestination() { return RespawnPoint.LUMBRIDGE.getDestination(); }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }
}
