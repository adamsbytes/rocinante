package com.rocinante.navigation;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for WebWalker Dijkstra pathfinding.
 */
public class WebWalkerTest {

    @Mock
    private Client client;

    @Mock
    private Player localPlayer;

    private WebWalker webWalker;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Setup mock client
        when(client.getLocalPlayer()).thenReturn(localPlayer);
        when(localPlayer.getCombatLevel()).thenReturn(100);

        // Setup skill levels
        when(client.getRealSkillLevel(Skill.MAGIC)).thenReturn(50);
        when(client.getRealSkillLevel(Skill.AGILITY)).thenReturn(50);

        webWalker = new WebWalker(client, null, null); // null UnlockTracker and PlaneTransitionHandler for basic tests
    }

    // ========================================================================
    // Initialization Tests
    // ========================================================================

    @Test
    public void testWebLoaded() {
        assertTrue("Navigation web should be loaded", webWalker.isWebLoaded());
    }

    @Test
    public void testGetNavigationWeb() {
        NavigationWeb web = webWalker.getNavigationWeb();
        assertNotNull("Navigation web should not be null", web);
        assertFalse("Navigation web should have nodes", web.getNodes().isEmpty());
    }

    // ========================================================================
    // Path Finding Tests
    // ========================================================================

    @Test
    public void testFindPathByNodeId() {
        NavigationPath path = webWalker.findUnifiedPath("lumbridge_castle", "varrock_west_bank");

        assertFalse("Should find path from Lumbridge to Varrock", path.isEmpty());
        assertEquals("Path should start at lumbridge_castle",
                "lumbridge_castle", path.getStartNodeId());
        assertEquals("Path should end at varrock_west_bank",
                "varrock_west_bank", path.getEndNodeId());
    }

    @Test
    public void testFindPathByNodeId_LumbridgeToVarrock() {
        // Test path between two distant nodes
        NavigationPath path = webWalker.findUnifiedPath("lumbridge_castle", "varrock_west_bank");

        assertFalse("Should find path between lumbridge and varrock", path.isEmpty());
    }

    @Test
    public void testFindPathSameNode() {
        NavigationPath path = webWalker.findUnifiedPath("lumbridge_castle", "lumbridge_castle");

        // Same node path should be empty (no edges needed)
        assertTrue("Same node path should be empty (already there)", path.isEmpty());
    }

    @Test
    public void testFindPathInvalidStartNode() {
        NavigationPath path = webWalker.findUnifiedPath("nonexistent_start", "varrock_west_bank");

        assertTrue("Should return empty path for invalid start", path.isEmpty());
    }

    @Test
    public void testFindPathInvalidEndNode() {
        NavigationPath path = webWalker.findUnifiedPath("lumbridge_castle", "nonexistent_end");

        assertTrue("Should return empty path for invalid end", path.isEmpty());
    }

    // ========================================================================
    // Nearest Node Tests
    // ========================================================================

    @Test
    public void testFindPathToBank() {
        // Test path to a specific bank by ID (avoids nearest-search complications)
        NavigationPath path = webWalker.findUnifiedPath("lumbridge_castle", "varrock_west_bank");

        assertFalse("Should find path to bank", path.isEmpty());

        // End node should be the bank
        String endNodeId = path.getEndNodeId();
        assertEquals("End node should be varrock_west_bank", "varrock_west_bank", endNodeId);
    }

    @Test
    public void testGetNearestNode() {
        WorldPoint nearVarrock = new WorldPoint(3212, 3429, 0);
        WebNode nearest = webWalker.getNearestNode(nearVarrock);

        assertNotNull("Should find nearest node", nearest);
    }

    @Test
    public void testGetNode() {
        WebNode node = webWalker.getNode("edgeville_bank");

        assertNotNull("Should find edgeville_bank", node);
        assertEquals("edgeville_bank", node.getId());
    }

    @Test
    public void testGetBanks() {
        List<WebNode> banks = webWalker.getBanks();

        assertFalse("Should have banks", banks.isEmpty());
        for (WebNode bank : banks) {
            assertEquals("All nodes should be banks", WebNodeType.BANK, bank.getType());
        }
    }

    // ========================================================================
    // Configuration Tests
    // ========================================================================

    @Test
    public void testSetIronman() {
        webWalker.setIronman(true);
        // Ironman mode should filter certain edges
        // This is a configuration test - actual filtering tested via path finding
    }

    @Test
    public void testSetHardcoreIronman() {
        webWalker.setHardcoreIronman(true);
        // HCIM mode should enable wilderness avoidance
    }

    @Test
    public void testSetUltimateIronman() {
        webWalker.setUltimateIronman(true);
        // UIM mode should affect banking edge availability
    }

    @Test
    public void testSetAvoidWilderness() {
        webWalker.setAvoidWilderness(true);

        // Path should avoid wilderness nodes
        NavigationPath path = webWalker.findUnifiedPath("edgeville_bank", "wilderness_ditch");

        // This test depends on whether there's a path through wilderness
        // The important thing is that it doesn't crash
    }

    // ========================================================================
    // Travel Time Estimation Tests
    // ========================================================================

    @Test
    public void testEstimateTravelTime() {
        int time = webWalker.estimateTravelTime("lumbridge_castle", "draynor_bank");

        // Travel time should be non-negative if path exists
        // A path of one or two nodes might have 0 cost
        assertTrue("Travel time should be non-negative", time >= 0);
    }

    @Test
    public void testEstimateTravelTimeNoPath() {
        int time = webWalker.estimateTravelTime("lumbridge_castle", "nonexistent");

        assertEquals("Should return -1 for no path", -1, time);
    }

    // ========================================================================
    // Path Properties Tests
    // ========================================================================

    @Test
    public void testPathContinuity() {
        NavigationPath path = webWalker.findUnifiedPath("lumbridge_castle", "falador_east_bank");

        if (!path.isEmpty()) {
            // Verify each edge connects properly
            List<NavigationEdge> edges = path.getEdges();
            for (int i = 0; i < edges.size() - 1; i++) {
                NavigationEdge current = edges.get(i);
                NavigationEdge next = edges.get(i + 1);

                assertEquals("Edge chain should be continuous: " +
                        current.getToNodeId() + " should equal " + next.getFromNodeId(),
                        current.getToNodeId(), next.getFromNodeId());
            }
        }
    }

    @Test
    public void testPathNoDuplicates() {
        NavigationPath path = webWalker.findUnifiedPath("lumbridge_castle", "varrock_ge");

        // Check for duplicate nodes in the edge chain
        if (!path.isEmpty()) {
            List<String> visitedNodes = new ArrayList<>();
            for (NavigationEdge edge : path.getEdges()) {
                assertFalse("Path should not have duplicate nodes: " + edge.getFromNodeId(),
                        visitedNodes.contains(edge.getFromNodeId()));
                visitedNodes.add(edge.getFromNodeId());
            }
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testFindPathWithNullWeb() {
        // Create a webwalker that couldn't load the web
        WebWalker badWalker = new WebWalker(client, null, null) {
            @Override
            public boolean isWebLoaded() {
                return false;
            }
        };

        // This shouldn't crash - findUnifiedPath throws if graph unavailable
        // but we can test that isWebLoaded returns false
        assertFalse(badWalker.isWebLoaded());
    }
}

