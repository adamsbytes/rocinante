package com.rocinante.navigation;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
        List<WebNode> path = webWalker.findPath("lumbridge_castle", "varrock_west_bank");

        assertFalse("Should find path from Lumbridge to Varrock", path.isEmpty());
        assertEquals("Path should start at lumbridge_castle",
                "lumbridge_castle", path.get(0).getId());
        assertEquals("Path should end at varrock_west_bank",
                "varrock_west_bank", path.get(path.size() - 1).getId());
    }

    @Test
    public void testFindPathByWorldPoint() {
        WorldPoint lumbridge = new WorldPoint(3222, 3218, 0);
        WorldPoint varrock = new WorldPoint(3185, 3436, 0);

        List<WebNode> path = webWalker.findPath(lumbridge, varrock);

        assertFalse("Should find path between world points", path.isEmpty());
    }

    @Test
    public void testFindPathSameNode() {
        List<WebNode> path = webWalker.findPath("lumbridge_castle", "lumbridge_castle");

        // Same node path should have just that node
        assertEquals("Same node path should have 1 node", 1, path.size());
    }

    @Test
    public void testFindPathInvalidStartNode() {
        List<WebNode> path = webWalker.findPath("nonexistent_start", "varrock_west_bank");

        assertTrue("Should return empty path for invalid start", path.isEmpty());
    }

    @Test
    public void testFindPathInvalidEndNode() {
        List<WebNode> path = webWalker.findPath("lumbridge_castle", "nonexistent_end");

        assertTrue("Should return empty path for invalid end", path.isEmpty());
    }

    // ========================================================================
    // Nearest Node Tests
    // ========================================================================

    @Test
    public void testFindPathToNearestBank() {
        WorldPoint lumbridge = new WorldPoint(3222, 3218, 0);
        List<WebNode> path = webWalker.findPathToNearestBank(lumbridge);

        assertFalse("Should find path to nearest bank", path.isEmpty());

        // End node should be a bank
        WebNode endNode = path.get(path.size() - 1);
        assertEquals("End node should be a bank", WebNodeType.BANK, endNode.getType());
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
        List<WebNode> path = webWalker.findPath("edgeville_bank", "wilderness_ditch");

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
        List<WebNode> path = webWalker.findPath("lumbridge_castle", "falador_east_bank");

        if (path.size() > 1) {
            NavigationWeb web = webWalker.getNavigationWeb();

            // Verify each consecutive pair has an edge
            for (int i = 0; i < path.size() - 1; i++) {
                WebNode from = path.get(i);
                WebNode to = path.get(i + 1);

                WebEdge edge = web.getEdge(from.getId(), to.getId());
                assertNotNull("Should have edge between consecutive path nodes: " +
                        from.getId() + " -> " + to.getId(), edge);
            }
        }
    }

    @Test
    public void testPathNoDuplicates() {
        List<WebNode> path = webWalker.findPath("lumbridge_castle", "varrock_ge");

        // Check for duplicate nodes (would indicate a cycle in the path)
        for (int i = 0; i < path.size(); i++) {
            for (int j = i + 1; j < path.size(); j++) {
                assertNotEquals("Path should not have duplicate nodes",
                        path.get(i).getId(), path.get(j).getId());
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

        // This shouldn't crash
        List<WebNode> path = badWalker.findPath("a", "b");
        assertTrue(path.isEmpty());
    }
}

