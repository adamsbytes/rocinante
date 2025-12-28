package com.rocinante.navigation;

import com.rocinante.tasks.impl.TravelTask;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the navigation system.
 * Tests multi-plane transitions, obstacle handling, transport edges, and edge cases.
 */
public class NavigationIntegrationTest {

    @Mock
    private Client client;

    @Mock
    private Player localPlayer;

    private WebWalker webWalker;

    // Test locations
    private static final WorldPoint LUMBRIDGE_GROUND = new WorldPoint(3222, 3218, 0);
    private static final WorldPoint LUMBRIDGE_BANK_FLOOR_2 = new WorldPoint(3208, 3220, 2);
    private static final WorldPoint VARROCK_SQUARE = new WorldPoint(3213, 3424, 0);
    private static final WorldPoint EDGEVILLE = new WorldPoint(3087, 3497, 0);
    private static final WorldPoint FALADOR = new WorldPoint(2965, 3380, 0);
    private static final WorldPoint DRAYNOR = new WorldPoint(3092, 3243, 0);

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        when(client.getLocalPlayer()).thenReturn(localPlayer);
        when(localPlayer.getCombatLevel()).thenReturn(100);

        // Setup skill levels for shortcut tests
        when(client.getRealSkillLevel(Skill.MAGIC)).thenReturn(70);
        when(client.getRealSkillLevel(Skill.AGILITY)).thenReturn(70);

        webWalker = new WebWalker(client, null, null);
    }

    // ========================================================================
    // Multi-Plane Path Tests
    // ========================================================================

    @Test
    public void testPathFromGroundToUpperFloor() {
        // Lumbridge Castle (ground) to Lumbridge Bank (2nd floor)
        List<WebNode> path = webWalker.findPath("lumbridge_castle", "lumbridge_bank");

        assertFalse("Should find path from ground to 2nd floor", path.isEmpty());
        assertEquals("Path should start at lumbridge_castle",
                "lumbridge_castle", path.get(0).getId());
        assertEquals("Path should end at lumbridge_bank",
                "lumbridge_bank", path.get(path.size() - 1).getId());

        // Verify plane transition exists in path
        verifyPlaneTransition(path, 0, 2);
    }

    @Test
    public void testPathFromUpperFloorToGround() {
        // Reverse: Bank on 2nd floor back to ground
        List<WebNode> path = webWalker.findPath("lumbridge_bank", "lumbridge_castle");

        assertFalse("Should find path from 2nd floor to ground", path.isEmpty());
        assertEquals("Path should start at lumbridge_bank",
                "lumbridge_bank", path.get(0).getId());
        assertEquals("Path should end at lumbridge_castle",
                "lumbridge_castle", path.get(path.size() - 1).getId());
    }

    @Test
    public void testPathBetweenDifferentPlanesViaWorldPoints() {
        // Test using world points that are on different planes
        List<WebNode> path = webWalker.findPath(LUMBRIDGE_GROUND, LUMBRIDGE_BANK_FLOOR_2);

        assertFalse("Should find path between different planes using world points", path.isEmpty());
    }

    // ========================================================================
    // Transport Edge Tests
    // ========================================================================

    @Test
    public void testTravelTask_CanoeFromMetadata() {
        Map<String, String> metadata = Map.of(
                "travel_type", "canoe",
                "destination", "Lumbridge"
        );

        TravelTask task = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull("Should create TravelTask for canoe", task);
        assertEquals("Method should be CANOE",
                TravelTask.TravelMethod.CANOE, task.getMethod());
        assertEquals("Destination should be Lumbridge",
                "Lumbridge", task.getTransportDestination());
    }

    @Test
    public void testTravelTask_BalloonFromMetadata() {
        Map<String, String> metadata = Map.of(
                "travel_type", "balloon",
                "destination", "Entrana",
                "log_item_id", "1511"
        );

        TravelTask task = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull("Should create TravelTask for balloon", task);
        assertEquals("Method should be BALLOON",
                TravelTask.TravelMethod.BALLOON, task.getMethod());
        assertEquals("Destination should be Entrana",
                "Entrana", task.getTransportDestination());
        assertEquals("Should have log item ID", 1511, task.getRequiredItemId());
    }

    @Test
    public void testTravelTask_ShipFromMetadata() {
        Map<String, String> metadata = Map.of(
                "travel_type", "ship",
                "destination", "Port Sarim",
                "npc_name", "Captain"
        );

        TravelTask task = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull("Should create TravelTask for ship", task);
        assertEquals("Method should be SHIP",
                TravelTask.TravelMethod.SHIP, task.getMethod());
    }

    @Test
    public void testTravelTask_RowBoatFromMetadata() {
        Map<String, String> metadata = Map.of(
                "travel_type", "row_boat",
                "destination", "Mort'ton",
                "object_id", "12345"
        );

        TravelTask task = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull("Should create TravelTask for row boat", task);
        assertEquals("Method should be ROW_BOAT",
                TravelTask.TravelMethod.ROW_BOAT, task.getMethod());
        assertEquals("Object ID should be parsed", 12345, task.getObjectId());
    }

    @Test
    public void testTravelTask_MagicCarpetFromMetadata() {
        Map<String, String> metadata = Map.of(
                "travel_type", "magic_carpet",
                "destination", "Pollnivneach",
                "fare", "200"
        );

        TravelTask task = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull("Should create TravelTask for magic carpet", task);
        assertEquals("Method should be MAGIC_CARPET",
                TravelTask.TravelMethod.MAGIC_CARPET, task.getMethod());
        assertEquals("Fare should be parsed", 200, task.getGoldCost());
    }

    @Test
    public void testTravelTask_MinecartFromMetadata() {
        Map<String, String> metadata = Map.of(
                "travel_type", "minecart",
                "destination", "Keldagrim",
                "object_id", "28135"
        );

        TravelTask task = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull("Should create TravelTask for minecart", task);
        assertEquals("Method should be MINECART",
                TravelTask.TravelMethod.MINECART, task.getMethod());
    }

    @Test
    public void testTravelTask_WildernessLeverFromMetadata() {
        Map<String, String> metadata = Map.of(
                "travel_type", "wilderness_lever",
                "object_id", "1814",
                "dest_x", "3154",
                "dest_y", "3924",
                "dest_plane", "0"
        );

        TravelTask task = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull("Should create TravelTask for wilderness lever", task);
        assertEquals("Method should be WILDERNESS_LEVER",
                TravelTask.TravelMethod.WILDERNESS_LEVER, task.getMethod());
        assertNotNull("Should have destination", task.getExpectedDestination());
        assertEquals("X coordinate should match", 3154, task.getExpectedDestination().getX());
        assertEquals("Y coordinate should match", 3924, task.getExpectedDestination().getY());
    }

    @Test
    public void testTravelTask_MushtreeFromMetadata() {
        Map<String, String> metadata = Map.of(
                "travel_type", "mushtree",
                "destination", "Verdant Valley",
                "object_id", "30821"
        );

        TravelTask task = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull("Should create TravelTask for mushtree", task);
        assertEquals("Method should be MUSHTREE",
                TravelTask.TravelMethod.MUSHTREE, task.getMethod());
    }

    // ========================================================================
    // Existing Transport Task Tests (verify no regressions)
    // ========================================================================

    @Test
    public void testTravelTask_FairyRingFromMetadata() {
        Map<String, String> metadata = Map.of(
                "travel_type", "fairy_ring",
                "code", "CKS"
        );

        TravelTask task = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull("Should create TravelTask for fairy ring", task);
        assertEquals("Method should be FAIRY_RING",
                TravelTask.TravelMethod.FAIRY_RING, task.getMethod());
        assertEquals("Code should be CKS", "CKS", task.getFairyRingCode());
    }

    @Test
    public void testTravelTask_SpiritTreeFromMetadata() {
        Map<String, String> metadata = Map.of(
                "travel_type", "spirit_tree",
                "destination", "Tree Gnome Stronghold"
        );

        TravelTask task = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull("Should create TravelTask for spirit tree", task);
        assertEquals("Method should be SPIRIT_TREE",
                TravelTask.TravelMethod.SPIRIT_TREE, task.getMethod());
    }

    @Test
    public void testTravelTask_GnomeGliderFromMetadata() {
        Map<String, String> metadata = Map.of(
                "travel_type", "gnome_glider",
                "destination", "Ta Quir Priw"
        );

        TravelTask task = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull("Should create TravelTask for gnome glider", task);
        assertEquals("Method should be GNOME_GLIDER",
                TravelTask.TravelMethod.GNOME_GLIDER, task.getMethod());
    }

    @Test
    public void testTravelTask_CharterShipFromMetadata() {
        Map<String, String> metadata = Map.of(
                "travel_type", "charter_ship",
                "destination", "Port Khazard",
                "fare", "1600"
        );

        TravelTask task = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull("Should create TravelTask for charter ship", task);
        assertEquals("Method should be CHARTER_SHIP",
                TravelTask.TravelMethod.CHARTER_SHIP, task.getMethod());
    }

    @Test
    public void testTravelTask_QuetzalFromMetadata() {
        Map<String, String> metadata = Map.of(
                "travel_type", "quetzal",
                "destination", "Varlamore"
        );

        TravelTask task = TravelTask.fromNavigationMetadata(metadata);

        assertNotNull("Should create TravelTask for quetzal", task);
        assertEquals("Method should be QUETZAL",
                TravelTask.TravelMethod.QUETZAL, task.getMethod());
    }

    // ========================================================================
    // Failed Path Tests
    // ========================================================================

    @Test
    public void testPathToNonexistentNode() {
        List<WebNode> path = webWalker.findPath("lumbridge_castle", "nonexistent_node");

        assertTrue("Should return empty path for nonexistent destination", path.isEmpty());
    }

    @Test
    public void testPathFromNonexistentNode() {
        List<WebNode> path = webWalker.findPath("nonexistent_node", "lumbridge_castle");

        assertTrue("Should return empty path for nonexistent start", path.isEmpty());
    }

    @Test
    public void testPathBetweenDisconnectedNodes() {
        // If nodes exist but are disconnected, should return empty
        // This tests the pathfinding algorithm's handling of unreachable destinations
        List<WebNode> path = webWalker.findPath("lumbridge_castle", "tutorial_island_bank");

        // Tutorial island should not be reachable from mainland
        // If tutorial_island_bank doesn't exist, path will be empty anyway
        // This test verifies the behavior is consistent
        assertTrue("Should return empty path for disconnected nodes", path.isEmpty());
    }

    // ========================================================================
    // Path Quality Tests
    // ========================================================================

    @Test
    public void testPathHasNoBacktracking() {
        List<WebNode> path = webWalker.findPath("lumbridge_castle", "varrock_west_bank");

        if (path.size() > 2) {
            // Check that we don't visit the same node twice
            for (int i = 0; i < path.size(); i++) {
                for (int j = i + 1; j < path.size(); j++) {
                    assertNotEquals("Path should not backtrack through same node",
                            path.get(i).getId(), path.get(j).getId());
                }
            }
        }
    }

    @Test
    public void testPathReachesAllBanks() {
        List<WebNode> banks = webWalker.getBanks();

        assertFalse("Should have banks loaded", banks.isEmpty());

        // Verify we can reach at least one bank from Lumbridge
        List<WebNode> pathToBank = webWalker.findPathToNearestBank(LUMBRIDGE_GROUND);
        assertFalse("Should find path to nearest bank from Lumbridge", pathToBank.isEmpty());

        WebNode destination = pathToBank.get(pathToBank.size() - 1);
        assertEquals("Destination should be a bank", WebNodeType.BANK, destination.getType());
    }

    // ========================================================================
    // Navigation Edge Creation Tests
    // ========================================================================

    @Test
    public void testStairsEdgeIsPlaneTransition() {
        NavigationEdge stairsEdge = NavigationEdge.stairs(
                "node1", "node2", 5,
                0, 1,
                16671, "Climb-up",
                LUMBRIDGE_GROUND
        );

        assertTrue("Stairs edge should be a plane transition", stairsEdge.isPlaneTransition());
        assertEquals(0, stairsEdge.getFromPlane());
        assertEquals(1, stairsEdge.getToPlane());
    }

    @Test
    public void testAgilityShortcutEdgeHasRequirement() {
        NavigationEdge agilityEdge = NavigationEdge.agilityShortcut(
                "node1", "node2", 3,
                70, 0.05,
                16511, "Jump",
                LUMBRIDGE_GROUND, VARROCK_SQUARE
        );

        assertTrue("Agility edge should be a shortcut", agilityEdge.isAgilityShortcut());
        assertEquals(70, agilityEdge.getRequiredAgilityLevel());
        assertEquals(0.05, agilityEdge.getFailureRate(), 0.001);
    }

    @Test
    public void testTollGateEdgeHasCost() {
        NavigationEdge tollEdge = NavigationEdge.tollGate(
                "node1", "node2", 5,
                10, 12345, "Pay-toll",
                "Prince Ali Rescue",
                LUMBRIDGE_GROUND
        );

        assertTrue("Toll edge should be a toll gate", tollEdge.isTollGate());
        assertEquals(10, tollEdge.getTollCost());
        assertEquals("Prince Ali Rescue", tollEdge.getFreePassageQuest());
    }

    @Test
    public void testWalkEdgeIsBidirectional() {
        NavigationEdge walkEdge = NavigationEdge.walk(
                "node1", "node2", 10,
                LUMBRIDGE_GROUND, VARROCK_SQUARE
        );

        assertTrue("Walk edge should be bidirectional", walkEdge.isBidirectional());
        assertEquals(WebEdgeType.WALK, walkEdge.getType());
    }

    // ========================================================================
    // Web Loading Validation
    // ========================================================================

    @Test
    public void testWebHasExpectedNodes() {
        NavigationWeb web = webWalker.getNavigationWeb();

        assertNotNull("Navigation web should not be null", web);

        // Verify key nodes exist
        assertNotNull("Should have lumbridge_castle node",
                web.getNode("lumbridge_castle"));
        assertNotNull("Should have varrock_west_bank node",
                web.getNode("varrock_west_bank"));
        assertNotNull("Should have edgeville_bank node",
                web.getNode("edgeville_bank"));
        assertNotNull("Should have draynor_bank node",
                web.getNode("draynor_bank"));
    }

    @Test
    public void testWebHasEdges() {
        NavigationWeb web = webWalker.getNavigationWeb();

        // Verify edges exist between connected nodes
        WebEdge lumbridgeToBank = web.getEdge("lumbridge_castle", "lumbridge_bank");
        assertNotNull("Should have edge from lumbridge castle to bank", lumbridgeToBank);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Verify that a path contains a plane transition from one plane to another.
     */
    private void verifyPlaneTransition(List<WebNode> path, int fromPlane, int toPlane) {
        boolean foundTransition = false;

        for (int i = 0; i < path.size() - 1; i++) {
            int currentPlane = path.get(i).getPlane();
            int nextPlane = path.get(i + 1).getPlane();

            // Check if there's any plane change in the path
            if (currentPlane != nextPlane) {
                foundTransition = true;
                break;
            }
        }

        // The path might not directly show plane transitions if nodes
        // are connected via implicit stairs. Check start/end planes instead.
        int startPlane = path.get(0).getPlane();
        int endPlane = path.get(path.size() - 1).getPlane();

        assertTrue("Path should include plane change or connect different planes",
                foundTransition || (startPlane == fromPlane && endPlane == toPlane));
    }
}

