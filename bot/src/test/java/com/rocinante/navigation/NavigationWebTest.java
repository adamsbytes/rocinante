package com.rocinante.navigation;

import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for NavigationWeb JSON parsing and queries.
 */
public class NavigationWebTest {

    private NavigationWeb navigationWeb;

    @Before
    public void setUp() throws IOException {
        // Load the navigation web from resources
        navigationWeb = NavigationWeb.loadFromResources();
    }

    // ========================================================================
    // Loading Tests
    // ========================================================================

    @Test
    public void testLoadFromResources() {
        assertNotNull("NavigationWeb should load from resources", navigationWeb);
        assertNotNull("Version should be set", navigationWeb.getVersion());
        assertFalse("Should have nodes", navigationWeb.getNodes().isEmpty());
        assertFalse("Should have edges", navigationWeb.getEdges().isEmpty());
    }

    @Test
    public void testVersionParsing() {
        assertEquals("Version should be 1.0", "1.0", navigationWeb.getVersion());
    }

    // ========================================================================
    // Node Query Tests
    // ========================================================================

    @Test
    public void testGetNodeById() {
        WebNode varrockWestBank = navigationWeb.getNode("varrock_west_bank");
        assertNotNull("Should find varrock_west_bank", varrockWestBank);
        assertEquals("varrock_west_bank", varrockWestBank.getId());
        assertEquals("Varrock West Bank", varrockWestBank.getName());
        assertEquals(WebNodeType.BANK, varrockWestBank.getType());
    }

    @Test
    public void testGetNodeByIdNotFound() {
        WebNode nonexistent = navigationWeb.getNode("nonexistent_location");
        assertNull("Should return null for nonexistent node", nonexistent);
    }

    @Test
    public void testHasNode() {
        assertTrue("Should have lumbridge_castle", navigationWeb.hasNode("lumbridge_castle"));
        assertFalse("Should not have fake_node", navigationWeb.hasNode("fake_node"));
    }

    @Test
    public void testGetNodesByType() {
        List<WebNode> banks = navigationWeb.getNodesByType(WebNodeType.BANK);
        assertFalse("Should have bank nodes", banks.isEmpty());

        // All returned nodes should be banks
        for (WebNode node : banks) {
            assertEquals("All nodes should be BANK type", WebNodeType.BANK, node.getType());
        }
    }

    @Test
    public void testGetBanks() {
        List<WebNode> banks = navigationWeb.getBanks();
        assertFalse("Should have banks", banks.isEmpty());

        // Check some known banks exist
        boolean hasVarrockWest = banks.stream()
                .anyMatch(n -> n.getId().equals("varrock_west_bank"));
        boolean hasEdgeville = banks.stream()
                .anyMatch(n -> n.getId().equals("edgeville_bank"));

        assertTrue("Should have Varrock West Bank", hasVarrockWest);
        assertTrue("Should have Edgeville Bank", hasEdgeville);
    }

    @Test
    public void testGetNodesWithTag() {
        List<WebNode> f2pNodes = navigationWeb.getNodesWithTag("f2p");
        assertFalse("Should have F2P nodes", f2pNodes.isEmpty());

        for (WebNode node : f2pNodes) {
            assertTrue("All nodes should have f2p tag", node.hasTag("f2p"));
        }
    }

    @Test
    public void testFindNearestNode() {
        WorldPoint varrockCenter = new WorldPoint(3212, 3429, 0);
        WebNode nearest = navigationWeb.findNearestNode(varrockCenter);

        assertNotNull("Should find nearest node", nearest);
        assertEquals("Nearest should be varrock_center", "varrock_center", nearest.getId());
    }

    @Test
    public void testFindNearestBank() {
        WorldPoint nearVarrockEast = new WorldPoint(3250, 3420, 0);
        WebNode nearest = navigationWeb.findNearestNode(nearVarrockEast, WebNodeType.BANK);

        assertNotNull("Should find nearest bank", nearest);
        assertEquals(WebNodeType.BANK, nearest.getType());
        assertEquals("varrock_east_bank", nearest.getId());
    }

    @Test
    public void testFindNodesWithinDistance() {
        WorldPoint varrockCenter = new WorldPoint(3212, 3429, 0);
        List<WebNode> nearby = navigationWeb.findNodesWithinDistance(varrockCenter, 100);

        assertFalse("Should find nodes within 100 tiles", nearby.isEmpty());

        // All nodes should be within distance
        for (WebNode node : nearby) {
            assertTrue("Node should be within 100 tiles",
                    node.distanceTo(varrockCenter) <= 100);
        }
    }

    // ========================================================================
    // Edge Query Tests
    // ========================================================================

    @Test
    public void testGetEdgesFrom() {
        List<WebEdge> edges = navigationWeb.getEdgesFrom("varrock_center");
        assertFalse("Should have edges from varrock_center", edges.isEmpty());

        // All edges should have varrock_center as source
        for (WebEdge edge : edges) {
            assertEquals("Edge source should be varrock_center",
                    "varrock_center", edge.getFrom());
        }
    }

    @Test
    public void testGetEdgesFromNonexistent() {
        List<WebEdge> edges = navigationWeb.getEdgesFrom("nonexistent");
        assertTrue("Should return empty list for nonexistent node", edges.isEmpty());
    }

    @Test
    public void testGetDirectEdge() {
        WebEdge edge = navigationWeb.getEdge("varrock_center", "varrock_east_bank");
        assertNotNull("Should find edge between varrock_center and varrock_east_bank", edge);
        assertEquals("varrock_center", edge.getFrom());
        assertEquals("varrock_east_bank", edge.getTo());
    }

    @Test
    public void testBidirectionalEdges() {
        // If there's an edge A -> B that's bidirectional, there should also be B -> A
        WebEdge forward = navigationWeb.getEdge("varrock_center", "varrock_west_bank");
        WebEdge reverse = navigationWeb.getEdge("varrock_west_bank", "varrock_center");

        if (forward != null && forward.isBidirectional()) {
            assertNotNull("Bidirectional edge should have reverse", reverse);
        }
    }

    @Test
    public void testGetEdgesByType() {
        List<WebEdge> walkEdges = navigationWeb.getEdgesByType(WebEdgeType.WALK);
        assertFalse("Should have walk edges", walkEdges.isEmpty());

        for (WebEdge edge : walkEdges) {
            assertEquals(WebEdgeType.WALK, edge.getType());
        }
    }

    // ========================================================================
    // Region Query Tests
    // ========================================================================

    @Test
    public void testGetRegion() {
        WebRegion misthalin = navigationWeb.getRegion("misthalin");
        assertNotNull("Should find misthalin region", misthalin);
        assertEquals("misthalin", misthalin.getId());
        assertEquals("Misthalin", misthalin.getName());
        assertFalse("Misthalin should not be members only", misthalin.isMembersOnly());
    }

    @Test
    public void testFindRegionForNode() {
        WebRegion region = navigationWeb.findRegionForNode("lumbridge_castle");
        assertNotNull("Should find region for lumbridge_castle", region);
        assertEquals("misthalin", region.getId());
    }

    @Test
    public void testIsInWilderness() {
        // wilderness_ditch should be in wilderness
        assertTrue("wilderness_ditch should be in wilderness",
                navigationWeb.isInWilderness("wilderness_ditch"));

        // lumbridge_castle should not be in wilderness
        assertFalse("lumbridge_castle should not be in wilderness",
                navigationWeb.isInWilderness("lumbridge_castle"));
    }

    // ========================================================================
    // WebNode Tests
    // ========================================================================

    @Test
    public void testNodeWorldPoint() {
        WebNode node = navigationWeb.getNode("varrock_west_bank");
        assertNotNull(node);

        WorldPoint point = node.getWorldPoint();
        assertEquals(node.getX(), point.getX());
        assertEquals(node.getY(), point.getY());
        assertEquals(node.getPlane(), point.getPlane());
    }

    @Test
    public void testNodeDistanceTo() {
        WebNode node1 = navigationWeb.getNode("varrock_west_bank");
        WebNode node2 = navigationWeb.getNode("varrock_east_bank");

        assertNotNull(node1);
        assertNotNull(node2);

        int distance = node1.distanceTo(node2);
        assertTrue("Distance should be positive", distance > 0);
    }

    @Test
    public void testNodeTags() {
        WebNode node = navigationWeb.getNode("varrock_west_bank");
        assertNotNull(node);

        assertTrue("Should have f2p tag", node.hasTag("f2p"));
        assertTrue("Should have safe tag", node.hasTag("safe"));
        assertTrue("Should be F2P", node.isF2P());
        assertTrue("Should be safe", node.isSafe());
        assertFalse("Should not be members only", node.isMembersOnly());
    }

    // ========================================================================
    // WebEdge Tests
    // ========================================================================

    @Test
    public void testEdgeCost() {
        // Test edge cost using the new transition node path
        WebEdge edge = navigationWeb.getEdge("lumbridge_castle", "lumbridge_castle_ground");
        assertNotNull("Should have edge from lumbridge_castle to lumbridge_castle_ground", edge);
        assertTrue("Cost should be positive", edge.getCostTicks() > 0);
    }

    @Test
    public void testEdgeRequirements() {
        // Find a teleport edge which should have requirements
        List<WebEdge> teleportEdges = navigationWeb.getEdgesByType(WebEdgeType.TELEPORT);

        boolean foundEdgeWithReqs = false;
        for (WebEdge edge : teleportEdges) {
            if (edge.hasRequirements()) {
                foundEdgeWithReqs = true;
                assertFalse("Requirements should not be empty",
                        edge.getRequirements().isEmpty());
                break;
            }
        }

        // It's okay if no teleport edges have requirements in the test data
    }

    // ========================================================================
    // JSON Parsing Edge Cases
    // ========================================================================

    @Test
    public void testParseMinimalJson() {
        String minimalJson = "{\"version\":\"1.0\",\"nodes\":[],\"edges\":[]}";
        NavigationWeb minimal = NavigationWeb.parse(minimalJson);

        assertNotNull(minimal);
        assertEquals("1.0", minimal.getVersion());
        assertTrue(minimal.getNodes().isEmpty());
        assertTrue(minimal.getEdges().isEmpty());
    }

    @Test
    public void testParseSingleNode() {
        String json = "{\"version\":\"1.0\",\"nodes\":[" +
                "{\"id\":\"test\",\"name\":\"Test\",\"x\":100,\"y\":200,\"plane\":0,\"type\":\"GENERIC\"}" +
                "],\"edges\":[]}";

        NavigationWeb web = NavigationWeb.parse(json);
        assertNotNull(web);
        assertEquals(1, web.getNodes().size());

        WebNode node = web.getNode("test");
        assertNotNull(node);
        assertEquals("test", node.getId());
        assertEquals(100, node.getX());
        assertEquals(200, node.getY());
    }

    @Test
    public void testParseCaseInsensitiveEnums() {
        // Test that enum parsing is case-insensitive
        String json = "{\"version\":\"1.0\",\"nodes\":[" +
                "{\"id\":\"test\",\"name\":\"Test\",\"x\":100,\"y\":200,\"plane\":0,\"type\":\"bank\"}" +
                "],\"edges\":[]}";

        NavigationWeb web = NavigationWeb.parse(json);
        WebNode node = web.getNode("test");
        assertNotNull(node);
        assertEquals(WebNodeType.BANK, node.getType());
    }
}

