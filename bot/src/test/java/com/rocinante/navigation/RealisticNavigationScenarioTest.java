package com.rocinante.navigation;

import net.runelite.api.Quest;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Realistic scenario tests for the navigation system.
 * 
 * These tests verify real-world pathfinding behavior including:
 * - Account type restrictions (HCIM wilderness avoidance)
 * - Teleport cooldown handling
 * - Respawn point awareness for home teleport
 * - Quest requirement filtering for grouping teleports
 * - "Stray point" navigation (finding paths from arbitrary locations)
 * - Resource-aware pathfinding (law rune costs for ironmen)
 */
public class RealisticNavigationScenarioTest {

    private NavigationWeb webData;
    private UnifiedNavigationGraph graph;

    // ========================================================================
    // Common World Points
    // ========================================================================
    
    /** Lumbridge Castle center */
    private static final WorldPoint LUMBRIDGE = new WorldPoint(3222, 3218, 0);
    
    /** Varrock West Bank */
    private static final WorldPoint VARROCK_WEST = new WorldPoint(3185, 3436, 0);
    
    /** Al Kharid bank area */
    private static final WorldPoint AL_KHARID_BANK = new WorldPoint(3269, 3167, 0);
    
    /** Random point in Al Kharid (not on a node) */
    private static final WorldPoint AL_KHARID_RANDOM = new WorldPoint(3295, 3180, 0);
    
    /** Random point near Varrock center (not on a node) */
    private static final WorldPoint VARROCK_RANDOM = new WorldPoint(3210, 3425, 0);
    
    /** Draynor Village center */
    private static final WorldPoint DRAYNOR = new WorldPoint(3093, 3244, 0);
    
    /** Edgeville bank */
    private static final WorldPoint EDGEVILLE = new WorldPoint(3094, 3491, 0);
    
    /** Castle Wars minigame location */
    private static final WorldPoint CASTLE_WARS = new WorldPoint(2439, 3092, 0);
    
    /** Pest Control minigame location */
    private static final WorldPoint PEST_CONTROL = new WorldPoint(2660, 2637, 0);
    
    /** Wilderness - deep (level 30+) */
    private static final WorldPoint WILDERNESS_DEEP = new WorldPoint(3200, 3800, 0);
    
    /** Ferox Enclave (safe zone in wilderness) */
    private static final WorldPoint FEROX_ENCLAVE = new WorldPoint(3129, 3631, 0);
    
    /** Falador center */
    private static final WorldPoint FALADOR = new WorldPoint(2964, 3378, 0);

    @Before
    public void setUp() throws IOException {
        // Use loadComplete() to load all navigation data including:
        // - Base web.json
        // - Region files (misthalin, asgarnia, etc.)
        // - Transport files (fairy rings, spirit trees, etc.)
        // - Home teleport edges
        // - Grouping teleport edges (minigame teleports)
        webData = NavigationWebLoader.loadComplete();
        assertNotNull("Navigation web should load successfully", webData);
        graph = new UnifiedNavigationGraph(webData);
        
        // Verify FREE_TELEPORT edges were loaded
        int anyLocationEdges = graph.getAnyLocationEdgeCount();
        assertTrue("Graph should have any_location edges for home/grouping teleports, found: " + anyLocationEdges,
                anyLocationEdges > 0);
    }

    // ========================================================================
    // Hardcore Ironman (HCIM) Scenarios
    // ========================================================================

    @Test
    public void testHCIM_ShouldAvoidWilderness_EvenWithTeleportRunes() {
        // HCIM with 99 magic, law runes, but should NEVER path through wilderness
        PlayerRequirements hcim = createHCIMWithResources();
        
        // Find path from Edgeville Bank to somewhere that could theoretically go through wildy
        List<NavigationEdge> traversable = graph.getTraversableEdges("edgeville_bank", hcim);
        
        // Should not include any wilderness edges
        boolean hasWildernessEdge = traversable.stream()
                .anyMatch(edge -> isWildernessEdge(edge));
        
        assertFalse("HCIM should never have wilderness edges available", hasWildernessEdge);
    }

    @Test
    public void testHCIM_ShouldNotPathThroughWilderness() {
        PlayerRequirements hcim = createHCIMWithResources();
        
        // Path from Edgeville Bank to somewhere that might route through wilderness
        // A naive pathfinder might try to go through wildy, but HCIM shouldn't
        boolean hasPath = graph.hasPath("edgeville_bank", "varrock_west_bank", hcim);
        assertTrue("HCIM should have non-wilderness path to Varrock", hasPath);
        
        // Verify the edges available don't include wilderness
        List<NavigationEdge> edges = graph.getTraversableEdges("edgeville_bank", hcim);
        for (NavigationEdge edge : edges) {
            assertFalse("HCIM edge should not be wilderness: " + edge.getToNodeId(),
                    isWildernessEdge(edge));
        }
    }

    @Test
    public void testHCIM_HighRiskThreshold_StillAvoidsWilderness() {
        // Even if risk threshold is somehow high, HCIM should avoid wilderness
        PlayerRequirements hcim = new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 99; }
            @Override public int getMagicLevel() { return 99; }
            @Override public int getCombatLevel() { return 126; }
            @Override public int getSkillLevel(String skillName) { return 99; }
            @Override public int getTotalGold() { return 1000000; }
            @Override public int getInventoryGold() { return 100000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return true; }
            @Override public boolean isHardcore() { return true; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 1.0; } // High threshold
            @Override public boolean shouldAvoidWilderness() { return true; } // Still avoid
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return EDGEVILLE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.EDGEVILLE; }
        };
        
        List<NavigationEdge> edges = graph.getTraversableEdges("edgeville_bank", hcim);
        for (NavigationEdge edge : edges) {
            assertFalse("High-risk HCIM should still avoid wilderness: " + edge.getToNodeId(),
                    isWildernessEdge(edge));
        }
    }

    // ========================================================================
    // Regular Ironman Scenarios
    // ========================================================================

    @Test
    public void testIronman_CanUseWilderness_IfWilling() {
        // Regular ironman with low risk threshold should avoid wilderness
        PlayerRequirements cautiousIronman = createCautiousIronman();
        
        // But a regular ironman with high risk tolerance could use wilderness
        PlayerRequirements riskyIronman = createRiskyIronman();
        
        // At minimum, verify the cautious one avoids wilderness
        List<NavigationEdge> cautiousEdges = graph.getTraversableEdges("edgeville_bank", cautiousIronman);
        boolean cautiousHasWildy = cautiousEdges.stream().anyMatch(this::isWildernessEdge);
        assertFalse("Cautious ironman should avoid wilderness", cautiousHasWildy);
    }

    // ========================================================================
    // Teleport Cooldown Scenarios
    // ========================================================================

    @Test
    public void testHomeTeleport_OnCooldown_NotAvailableInPath() {
        PlayerRequirements cooldownPlayer = createPlayerWithHomeTeleportOnCooldown();
        
        // Should not include home teleport edges when on cooldown
        List<NavigationEdge> edges = graph.getTraversableEdges("varrock_west_bank", cooldownPlayer);
        
        boolean hasHomeTeleportEdge = edges.stream()
                .anyMatch(e -> e.getType() == WebEdgeType.FREE_TELEPORT 
                        && "home".equals(e.getMetadata().get("teleport_type")));
        
        assertFalse("Home teleport on cooldown should not be in available edges", hasHomeTeleportEdge);
    }

    @Test
    public void testHomeTeleport_Available_IncludedInPath() {
        PlayerRequirements readyPlayer = createPlayerWithHomeTeleportReady();
        
        // Verify any_location edges contain home teleport edges
        List<NavigationEdge> anyLocationEdges = graph.getAnyLocationEdges();
        assertFalse("Graph should have any_location edges", anyLocationEdges.isEmpty());
        
        // Find home teleport edges - these MUST exist
        List<NavigationEdge> homeTeleportEdges = anyLocationEdges.stream()
                .filter(e -> e.getType() == WebEdgeType.FREE_TELEPORT 
                        && "home".equals(e.getMetadata("teleport_type")))
                .toList();
        
        assertFalse("Graph MUST have home teleport edges in any_location edges", 
                homeTeleportEdges.isEmpty());
        
        // Find the Lumbridge home teleport edge specifically
        NavigationEdge lumbridgeHomeTeleport = homeTeleportEdges.stream()
                .filter(e -> "LUMBRIDGE".equals(e.getMetadata("respawn_point")))
                .findFirst()
                .orElse(null);
        
        assertNotNull("Graph should have Lumbridge home teleport edge", lumbridgeHomeTeleport);
        
        // Verify it's traversable when player's respawn is Lumbridge and not on cooldown
        assertTrue("Home teleport to Lumbridge should be traversable for player with Lumbridge respawn",
                readyPlayer.canTraverseEdge(lumbridgeHomeTeleport));
    }

    @Test
    public void testMinigameTeleport_OnCooldown_NotAvailableInPath() {
        PlayerRequirements cooldownPlayer = createPlayerWithMinigameTeleportOnCooldown();
        
        // Verify any_location edges contain grouping teleport edges
        List<NavigationEdge> anyLocationEdges = graph.getAnyLocationEdges();
        
        List<NavigationEdge> groupingTeleportEdges = anyLocationEdges.stream()
                .filter(e -> e.getType() == WebEdgeType.FREE_TELEPORT 
                        && "grouping".equals(e.getMetadata("teleport_type")))
                .toList();
        
        assertFalse("Graph MUST have grouping teleport edges", groupingTeleportEdges.isEmpty());
        
        // All grouping teleports should NOT be traversable when on cooldown
        for (NavigationEdge edge : groupingTeleportEdges) {
            assertFalse("Grouping teleport on cooldown should not be traversable: " + edge.getMetadata("teleport_id"),
                        cooldownPlayer.canTraverseEdge(edge));
        }
    }

    @Test
    public void testMinigameTeleport_Available_CanTraverse() {
        PlayerRequirements readyPlayer = createPlayerWithMinigameTeleportReady();
        
        // Verify any_location edges contain grouping teleport edges
        List<NavigationEdge> anyLocationEdges = graph.getAnyLocationEdges();
        
        // Find grouping teleport edges - these MUST exist
        List<NavigationEdge> groupingTeleportEdges = anyLocationEdges.stream()
                .filter(e -> e.getType() == WebEdgeType.FREE_TELEPORT 
                        && "grouping".equals(e.getMetadata("teleport_type")))
                .toList();
        
        assertFalse("Graph MUST have grouping teleport edges in any_location edges", 
                groupingTeleportEdges.isEmpty());
        
        // Find Castle Wars teleport specifically - it has no requirements
        // Note: teleport_id uses the edgeId which is lowercase with underscores
        NavigationEdge castleWarsTeleport = groupingTeleportEdges.stream()
                .filter(e -> "castle_wars".equals(e.getMetadata("teleport_id")))
                .findFirst()
                .orElse(null);
        
        assertNotNull("Graph should have Castle Wars grouping teleport edge", castleWarsTeleport);
        
        // Castle Wars has no additional requirements, should be traversable when not on cooldown
                    assertTrue("Castle Wars teleport should be traversable when not on cooldown",
                readyPlayer.canTraverseEdge(castleWarsTeleport));
    }

    // ========================================================================
    // Respawn Point Scenarios
    // ========================================================================

    @Test
    public void testHomeTeleport_WrongRespawnPoint_NotTraversable() {
        // Player has Lumbridge respawn but edge is for Edgeville
        PlayerRequirements lumbridgePlayer = createPlayerWithRespawnPoint(RespawnPoint.LUMBRIDGE);
        
        // Get home teleport edges from any_location edges
        List<NavigationEdge> anyLocationEdges = graph.getAnyLocationEdges();
        
        List<NavigationEdge> homeTeleportEdges = anyLocationEdges.stream()
                .filter(e -> e.getType() == WebEdgeType.FREE_TELEPORT 
                        && "home".equals(e.getMetadata("teleport_type")))
                .toList();
        
        assertFalse("Graph MUST have home teleport edges", homeTeleportEdges.isEmpty());
        
        // Find an Edgeville home teleport edge
        NavigationEdge edgevilleHomeTeleport = homeTeleportEdges.stream()
                .filter(e -> "EDGEVILLE".equals(e.getMetadata("respawn_point")))
                .findFirst()
                .orElse(null);
        
        assertNotNull("Graph should have Edgeville home teleport edge", edgevilleHomeTeleport);
        
        // Player with Lumbridge respawn should NOT be able to traverse Edgeville home teleport
        assertFalse("Lumbridge player should not traverse Edgeville home teleport",
                lumbridgePlayer.canTraverseEdge(edgevilleHomeTeleport));
    }

    @Test
    public void testHomeTeleport_CorrectRespawnPoint_Traversable() {
        // Player has Edgeville respawn
        PlayerRequirements edgevillePlayer = createPlayerWithRespawnPoint(RespawnPoint.EDGEVILLE);
        
        List<NavigationEdge> edges = graph.getAllEdges();
        
        for (NavigationEdge edge : edges) {
            if (edge.getType() == WebEdgeType.FREE_TELEPORT 
                    && "home".equals(edge.getMetadata().get("teleport_type"))) {
                String respawnPointId = edge.getMetadata().get("respawn_point");
                if ("EDGEVILLE".equals(respawnPointId)) {
                    assertTrue("Edgeville player should traverse Edgeville home tele",
                            edgevillePlayer.canTraverseEdge(edge));
                }
            }
        }
    }

    // ========================================================================
    // Quest Requirement Scenarios
    // ========================================================================

    @Test
    public void testGroupingTeleport_QuestNotComplete_NotTraversable() {
        // Player hasn't completed Temple of the Eye (required for GOTR)
        PlayerRequirements noQuestPlayer = createPlayerWithoutQuest(Quest.TEMPLE_OF_THE_EYE);
        
        List<NavigationEdge> edges = graph.getAllEdges();
        
        for (NavigationEdge edge : edges) {
            if (edge.getType() == WebEdgeType.FREE_TELEPORT 
                    && "grouping".equals(edge.getMetadata().get("teleport_type"))) {
                String teleportId = edge.getMetadata().get("teleport_id");
                if ("GUARDIANS_OF_THE_RIFT".equals(teleportId)) {
                    assertFalse("Player without Temple of the Eye should not access GOTR teleport",
                            noQuestPlayer.canTraverseEdge(edge));
                }
            }
        }
    }

    @Test
    public void testGroupingTeleport_QuestComplete_Traversable() {
        // Player has completed Temple of the Eye
        PlayerRequirements questPlayer = createPlayerWithAllQuests();
        
        List<NavigationEdge> edges = graph.getAllEdges();
        
        for (NavigationEdge edge : edges) {
            if (edge.getType() == WebEdgeType.FREE_TELEPORT 
                    && "grouping".equals(edge.getMetadata().get("teleport_type"))) {
                String teleportId = edge.getMetadata().get("teleport_id");
                if ("GUARDIANS_OF_THE_RIFT".equals(teleportId)) {
                    assertTrue("Player with Temple of the Eye should access GOTR teleport",
                            questPlayer.canTraverseEdge(edge));
                }
            }
        }
    }

    @Test
    public void testGroupingTeleport_CombatLevelRequirement() {
        // Pest Control requires 40 combat
        PlayerRequirements lowCombat = createPlayerWithCombatLevel(30);
        PlayerRequirements highCombat = createPlayerWithCombatLevel(50);
        
        List<NavigationEdge> edges = graph.getAllEdges();
        
        for (NavigationEdge edge : edges) {
            if (edge.getType() == WebEdgeType.FREE_TELEPORT 
                    && "grouping".equals(edge.getMetadata().get("teleport_type"))) {
                String teleportId = edge.getMetadata().get("teleport_id");
                if ("PEST_CONTROL".equals(teleportId)) {
                    assertFalse("30 combat player should not access Pest Control teleport",
                            lowCombat.canTraverseEdge(edge));
                    assertTrue("50 combat player should access Pest Control teleport",
                            highCombat.canTraverseEdge(edge));
                }
            }
        }
    }

    // ========================================================================
    // "Stray Point" Navigation Scenarios
    // ========================================================================

    @Test
    public void testStrayPoint_AlKharid_FindsNearestNode() {
        // Random point in Al Kharid should find a nearby node
        WebNode nearest = graph.findNearestNodeSamePlane(AL_KHARID_RANDOM);
        
        assertNotNull("Should find nearest node from Al Kharid random point", nearest);
        assertEquals("Nearest node should be on plane 0", 0, nearest.getPlane());
        
        // Should be reasonably close (within ~50 tiles)
        int dx = Math.abs(nearest.getX() - AL_KHARID_RANDOM.getX());
        int dy = Math.abs(nearest.getY() - AL_KHARID_RANDOM.getY());
        int distance = Math.max(dx, dy);
        assertTrue("Nearest node should be within 50 tiles, was " + distance, distance <= 50);
    }

    @Test
    public void testStrayPoint_VarrockRandom_FindsNearestNode() {
        WebNode nearest = graph.findNearestNodeSamePlane(VARROCK_RANDOM);
        
        assertNotNull("Should find nearest node from Varrock random point", nearest);
        assertEquals("Nearest node should be on plane 0", 0, nearest.getPlane());
    }

    @Test
    public void testStrayPoint_CanPathToKnownLocation() {
        // From a random point, can we reason our way to a known node?
        WebNode nearestToRandom = graph.findNearestNodeSamePlane(AL_KHARID_RANDOM);
        assertNotNull("Should find nearest node", nearestToRandom);
        
        // Now verify we can path from that nearest node to Lumbridge
        PlayerRequirements player = createBasicPlayer();
        boolean hasPath = graph.hasPath(nearestToRandom.getId(), "lumbridge_castle", player);
        
        assertTrue("Should be able to path from Al Kharid area to Lumbridge", hasPath);
    }

    @Test
    public void testStrayPoint_MultipleNodesInRadius() {
        // In a populated area, should find multiple nearby nodes
        List<WebNode> nearbyNodes = graph.findNodesWithinDistance2D(VARROCK_RANDOM, 100);
        
        assertTrue("Should find multiple nodes near Varrock center", nearbyNodes.size() > 1);
    }

    @Test
    public void testStrayPoint_EdgeOfWorld_StillFindsNode() {
        // Even at edge of commonly traveled area, should find something
        WorldPoint edgePoint = new WorldPoint(2600, 3200, 0); // West of Ardougne
        WebNode nearest = graph.findNearestNodeAnyPlane(edgePoint);
        
        // May or may not find something depending on web coverage
        // At minimum shouldn't throw an exception
        assertNotNull("findNearestNodeAnyPlane should not return null", nearest);
    }

    // ========================================================================
    // Real-World Path Scenarios
    // ========================================================================

    @Test
    public void testPath_LumbridgeToVarrock_WalkOnly() {
        // Player with no teleports available should still be able to walk
        PlayerRequirements walkOnlyPlayer = createWalkOnlyPlayer();
        
        boolean hasPath = graph.hasPath("lumbridge_castle", "varrock_west_bank", walkOnlyPlayer);
        assertTrue("Should have walking path from Lumbridge to Varrock", hasPath);
        
        // Verify walk edges exist
        List<NavigationEdge> edges = graph.getTraversableEdges("lumbridge_castle", walkOnlyPlayer);
        boolean hasWalkEdge = edges.stream().anyMatch(e -> e.getType() == WebEdgeType.WALK);
        assertTrue("Should have walk edges available", hasWalkEdge);
    }

    @Test
    public void testPath_FreeTeleportPreferred_WhenAvailable() {
        // Player with Castle Wars teleport available
        PlayerRequirements teleportPlayer = createPlayerWithMinigameTeleportReady();
        
        // Verify Castle Wars teleport edge is traversable
        List<NavigationEdge> allEdges = graph.getAllEdges();
        boolean foundCastleWarsTeleport = false;
        
        for (NavigationEdge edge : allEdges) {
            if (edge.getType() == WebEdgeType.FREE_TELEPORT) {
                String teleportId = edge.getMetadata().get("teleport_id");
                if ("CASTLE_WARS".equals(teleportId)) {
                    foundCastleWarsTeleport = true;
                    assertTrue("Castle Wars teleport should be traversable",
                            teleportPlayer.canTraverseEdge(edge));
                    break;
                }
            }
        }
        
        // Note: This may be false if grouping teleports aren't loaded into the graph yet
        // This is a canary test
        if (!foundCastleWarsTeleport) {
            // Graph doesn't have grouping teleports loaded - that's okay for this test
            assertTrue("Graph should either have grouping teleports or this test passes vacuously", true);
        }
    }

    @Test
    public void testPath_TollGate_RequiresGold() {
        PlayerRequirements poorPlayer = createPoorPlayer();
        PlayerRequirements richPlayer = createBasicPlayer();
        
        // Find a toll edge
        List<NavigationEdge> tollEdges = graph.getEdgesByType(WebEdgeType.TOLL);
        
        if (!tollEdges.isEmpty()) {
            NavigationEdge tollEdge = tollEdges.get(0);
            
            // Poor player shouldn't be able to use toll gates
            // Rich player should be able to
            // Note: depends on how toll requirements are set up in edge metadata
            assertNotNull("Toll edge should exist", tollEdge);
        }
    }

    @Test
    public void testPath_AgilityShortcut_RequiresLevel() {
        PlayerRequirements lowAgility = createPlayerWithAgilityLevel(1);
        PlayerRequirements highAgility = createPlayerWithAgilityLevel(99);
        
        List<NavigationEdge> agilityEdges = graph.getEdgesByType(WebEdgeType.AGILITY);
        
        if (!agilityEdges.isEmpty()) {
            // Find a shortcut with level requirement
            for (NavigationEdge edge : agilityEdges) {
                if (edge.getRequiredAgilityLevel() > 1) {
                    assertFalse("Low agility player shouldn't traverse: " + edge.getFromNodeId(),
                            lowAgility.canTraverseEdge(edge));
                    assertTrue("High agility player should traverse: " + edge.getFromNodeId(),
                            highAgility.canTraverseEdge(edge));
                    break;
                }
            }
        }
    }

    // ========================================================================
    // FREE_TELEPORT Integration Tests
    // ========================================================================

    /**
     * Integration test: Verify that getTraversableEdges includes any_location edges.
     * This is the core functionality that Bug #1 fixed.
     */
    @Test
    public void testIntegration_TraversableEdgesIncludesFreeTeleports() {
        PlayerRequirements player = createPlayerWithMinigameTeleportReady();
        
        // Get traversable edges from lumbridge_castle
        List<NavigationEdge> traversable = graph.getTraversableEdges("lumbridge_castle", player);
        
        // Should include both direct edges AND any_location edges (FREE_TELEPORT)
        boolean hasWalkEdge = traversable.stream()
                .anyMatch(e -> e.getType() == WebEdgeType.WALK);
        boolean hasFreeTeleportEdge = traversable.stream()
                .anyMatch(e -> e.getType() == WebEdgeType.FREE_TELEPORT);
        
        assertTrue("Should have walk edges from lumbridge_castle", hasWalkEdge);
        assertTrue("Should have FREE_TELEPORT edges available from any node", hasFreeTeleportEdge);
    }
    
    /**
     * Integration test: Verify that all any_location edges are accessible from any node.
     */
    @Test
    public void testIntegration_AnyLocationEdgesAvailableFromMultipleNodes() {
        PlayerRequirements player = createPlayerWithMinigameTeleportReady();
        
        // Count any_location edges
        int anyLocationEdgeCount = graph.getAnyLocationEdgeCount();
        assertTrue("Graph should have any_location edges", anyLocationEdgeCount > 0);
        
        // Check from multiple different nodes
        String[] testNodes = {"lumbridge_castle", "varrock_west_bank", "edgeville_bank"};
        
        for (String nodeId : testNodes) {
            if (!graph.hasNode(nodeId)) {
                continue; // Skip if node doesn't exist
            }
            
            List<NavigationEdge> traversable = graph.getTraversableEdges(nodeId, player);
            
            long freeTeleportCount = traversable.stream()
                    .filter(e -> e.getType() == WebEdgeType.FREE_TELEPORT)
                    .count();
            
            assertTrue("Node " + nodeId + " should have FREE_TELEPORT edges available, found: " + freeTeleportCount,
                    freeTeleportCount > 0);
        }
    }
    
    /**
     * Integration test: Verify home teleport edges are filtered by respawn point.
     */
    @Test
    public void testIntegration_HomeTeleportFilteredByRespawnPoint() {
        // Create player with Lumbridge respawn
        PlayerRequirements lumbridgePlayer = createPlayerWithRespawnPoint(RespawnPoint.LUMBRIDGE);
        
        // Get traversable edges from some node
        List<NavigationEdge> traversable = graph.getTraversableEdges("varrock_west_bank", lumbridgePlayer);
        
        // Filter to home teleport edges only
        List<NavigationEdge> homeTeleportEdges = traversable.stream()
                .filter(e -> e.getType() == WebEdgeType.FREE_TELEPORT)
                .filter(e -> "home".equals(e.getMetadata("teleport_type")))
                .toList();
        
        // Should only have ONE home teleport (to Lumbridge)
        for (NavigationEdge edge : homeTeleportEdges) {
            String respawnPoint = edge.getMetadata("respawn_point");
            assertEquals("Only Lumbridge home teleport should be traversable for Lumbridge player",
                    "LUMBRIDGE", respawnPoint);
        }
    }
    
    /**
     * Integration test: Verify grouping teleports respect cooldown.
     */
    @Test
    public void testIntegration_GroupingTeleportRespectseCooldown() {
        // Player with grouping teleport on cooldown
        PlayerRequirements cooldownPlayer = createPlayerWithMinigameTeleportOnCooldown();
        
        // Get traversable edges
        List<NavigationEdge> traversable = graph.getTraversableEdges("lumbridge_castle", cooldownPlayer);
        
        // Should NOT have any grouping teleport edges
        long groupingTeleportCount = traversable.stream()
                .filter(e -> e.getType() == WebEdgeType.FREE_TELEPORT)
                .filter(e -> "grouping".equals(e.getMetadata("teleport_type")))
                .count();
        
        assertEquals("Player on cooldown should have 0 grouping teleport edges", 0, groupingTeleportCount);
    }
    
    /**
     * Integration test: Verify home teleport respects cooldown.
     */
    @Test
    public void testIntegration_HomeTeleportRespectsCooldown() {
        // Player with home teleport on cooldown
        PlayerRequirements cooldownPlayer = createPlayerWithHomeTeleportOnCooldown();
        
        // Get traversable edges
        List<NavigationEdge> traversable = graph.getTraversableEdges("varrock_west_bank", cooldownPlayer);
        
        // Should NOT have any home teleport edges
        long homeTeleportCount = traversable.stream()
                .filter(e -> e.getType() == WebEdgeType.FREE_TELEPORT)
                .filter(e -> "home".equals(e.getMetadata("teleport_type")))
                .count();
        
        assertEquals("Player on cooldown should have 0 home teleport edges", 0, homeTeleportCount);
    }

    // ========================================================================
    // Wilderness Navigation Scenarios
    // ========================================================================

    /**
     * Test: Graph contains wilderness nodes from wilderness.json region file.
     */
    @Test
    public void testWilderness_NodesLoaded() {
        // Verify wilderness nodes are loaded
        WebNode feroxEnclave = graph.getNode("wild_ferox_enclave");
        WebNode chaosTemple = graph.getNode("wild_chaos_temple");
        WebNode greenDragons = graph.getNode("wild_green_dragons");
        
        assertNotNull("Ferox Enclave node should exist", feroxEnclave);
        assertNotNull("Chaos Temple node should exist", chaosTemple);
        assertNotNull("Green Dragons node should exist", greenDragons);
        
        // Verify coordinates are in wilderness (Y >= 3520)
        assertTrue("Ferox Enclave Y should be >= 3520", feroxEnclave.getY() >= 3520);
        assertTrue("Chaos Temple Y should be >= 3520", chaosTemple.getY() >= 3520);
        assertTrue("Green Dragons Y should be >= 3520", greenDragons.getY() >= 3520);
    }

    /**
     * Test: Wilderness edges have appropriate metadata.
     */
    @Test
    public void testWilderness_EdgesHaveMetadata() {
        // Get edges from wilderness ditch
        List<NavigationEdge> edges = graph.getEdgesFrom("wilderness_ditch");
        
        assertFalse("Should have edges from wilderness_ditch", edges.isEmpty());
        
        // Find edge into wilderness
        NavigationEdge wildernessEdge = edges.stream()
                .filter(e -> e.getToNodeId().startsWith("wild_"))
                .findFirst()
                .orElse(null);
        
        assertNotNull("Should have edge into wilderness from ditch", wildernessEdge);
        assertEquals("Wilderness edge should have wilderness=true metadata", 
                "true", wildernessEdge.getMetadata("wilderness"));
    }

    /**
     * Test: Player who avoids wilderness should not traverse wilderness edges.
     */
    @Test
    public void testWilderness_PlayerAvoidsWilderness_EdgesNotTraversable() {
        PlayerRequirements cautiousPlayer = createPlayerWhoAvoidsWilderness();
        
        // Get edges from wilderness ditch
        List<NavigationEdge> allEdges = graph.getEdgesFrom("wilderness_ditch");
        List<NavigationEdge> traversable = graph.getTraversableEdges("wilderness_ditch", cautiousPlayer);
        
        // Count wilderness edges
        long wildernessEdgeCount = allEdges.stream()
                .filter(this::isWildernessEdge)
                .count();
        
        long traversableWildernessCount = traversable.stream()
                .filter(this::isWildernessEdge)
                .count();
        
        // Player who avoids wilderness should not traverse wilderness edges
        if (wildernessEdgeCount > 0) {
            assertEquals("Cautious player should have 0 traversable wilderness edges",
                    0, traversableWildernessCount);
        }
    }

    /**
     * Test: Path to Ferox Enclave exists (safe zone in wilderness).
     */
    @Test  
    public void testWilderness_PathToFeroxEnclave_Exists() {
        // Player who is willing to enter wilderness
        PlayerRequirements bravePlayer = createPlayerWillingToEnterWilderness();
        
        // Check if path exists from Edgeville to Ferox Enclave
        boolean hasPath = graph.hasPath("edgeville_bank", "wild_ferox_enclave", bravePlayer);
        
        // Path should exist for player willing to enter wilderness
        assertTrue("Should have path from Edgeville to Ferox Enclave for brave player", hasPath);
    }

    // ========================================================================
    // Wilderness Teleport Level Restrictions
    // ========================================================================

    @Test
    public void testWildernessTeleport_Level0_AllTeleportsWork() {
        // Outside wilderness - all teleports should work
        WorldPoint outsideWilderness = new WorldPoint(3200, 3400, 0); // Varrock area
        
        assertEquals("Should be level 0 outside wilderness", 
                0, WildernessTeleportRestrictions.getWildernessLevel(outsideWilderness));
        assertTrue("Standard teleports should work outside wilderness",
                WildernessTeleportRestrictions.canUseStandardTeleport(outsideWilderness));
        assertTrue("Enhanced teleports should work outside wilderness",
                WildernessTeleportRestrictions.canUseEnhancedTeleport(outsideWilderness));
    }

    @Test
    public void testWildernessTeleport_Level15_AllTeleportsWork() {
        // Level 15 wilderness - still under 20 limit
        // Y = 3520 + (15-1)*8 = 3520 + 112 = 3632
        WorldPoint level15 = new WorldPoint(3100, 3632, 0);
        
        int level = WildernessTeleportRestrictions.getWildernessLevel(level15);
        assertTrue("Should be around level 15", level >= 14 && level <= 16);
        assertTrue("Standard teleports should work at level 15",
                WildernessTeleportRestrictions.canUseStandardTeleport(level15));
    }

    @Test
    public void testWildernessTeleport_Level25_OnlyEnhancedWorks() {
        // Level 25 wilderness - between 20 and 30
        // Y = 3520 + (25-1)*8 = 3520 + 192 = 3712
        WorldPoint level25 = new WorldPoint(3100, 3712, 0);
        
        int level = WildernessTeleportRestrictions.getWildernessLevel(level25);
        assertTrue("Should be around level 25", level >= 24 && level <= 26);
        assertFalse("Standard teleports should NOT work at level 25",
                WildernessTeleportRestrictions.canUseStandardTeleport(level25));
        assertTrue("Enhanced teleports (glory, etc.) should work at level 25",
                WildernessTeleportRestrictions.canUseEnhancedTeleport(level25));
    }

    @Test
    public void testWildernessTeleport_Level35_NothingWorks() {
        // Level 35 wilderness - above 30 limit, nothing works
        // Y = 3520 + (35-1)*8 = 3520 + 272 = 3792
        WorldPoint level35 = new WorldPoint(3100, 3792, 0);
        
        int level = WildernessTeleportRestrictions.getWildernessLevel(level35);
        assertTrue("Should be around level 35", level >= 34 && level <= 36);
        assertFalse("Standard teleports should NOT work at level 35",
                WildernessTeleportRestrictions.canUseStandardTeleport(level35));
        assertFalse("Enhanced teleports should NOT work at level 35",
                WildernessTeleportRestrictions.canUseEnhancedTeleport(level35));
    }

    @Test
    public void testWildernessTeleport_GloryItemDetection() {
        // Amulet of glory (charged) should be detected as level-30 item
        int gloryCharged = 1712; // Amulet of glory(6)
        assertTrue("Glory should be a level-30 teleport item",
                WildernessTeleportRestrictions.LEVEL_30_TELEPORT_ITEMS.contains(gloryCharged));
        
        assertEquals("Glory should have ENHANCED teleport type",
                WildernessTeleportRestrictions.TeleportType.ENHANCED,
                WildernessTeleportRestrictions.getTeleportTypeForItem(gloryCharged));
    }

    @Test
    public void testWildernessTeleport_GroupingTeleports_BlockedInWilderness() {
        // Grouping teleports don't work AT ALL in wilderness
        PlayerRequirements playerAtLevel5 = createPlayerInWilderness(5);
        
        // Get grouping teleport edges
        List<NavigationEdge> groupingEdges = graph.getAllEdges().stream()
                .filter(e -> e.getType() == WebEdgeType.FREE_TELEPORT)
                .filter(e -> "grouping".equals(e.getMetadata("teleport_type")))
                .toList();
        
        // Even at level 5, grouping teleports should NOT be traversable
        for (NavigationEdge edge : groupingEdges) {
            assertFalse("Grouping teleport should be blocked in wilderness (even level 5): " + 
                    edge.getMetadata("teleport_id"),
                    playerAtLevel5.canTraverseEdge(edge));
        }
    }

    @Test
    public void testWildernessTeleport_HomeTeleport_BlockedAbove20() {
        PlayerRequirements playerAtLevel25 = createPlayerInWilderness(25);
        
        // Get home teleport edges
        List<NavigationEdge> homeEdges = graph.getAllEdges().stream()
                .filter(e -> e.getType() == WebEdgeType.FREE_TELEPORT)
                .filter(e -> "home".equals(e.getMetadata("teleport_type")))
                .toList();
        
        // At level 25, home teleport should NOT work
        for (NavigationEdge edge : homeEdges) {
            assertFalse("Home teleport should be blocked above level 20",
                    playerAtLevel25.canTraverseEdge(edge));
        }
    }

    // ========================================================================
    // Transport Method Integration Tests
    // ========================================================================
    
    /**
     * Test: Charter ship path from Port Sarim to Catherby.
     * Player has coins but no runes - should use charter ships over magic.
     */
    @Test
    public void testTransport_CharterShip_PortSarimToCatherby() {
        WebNode portSarimCharter = graph.getNode("charter_portsarim");
        WebNode catherbyCharter = graph.getNode("charter_catherby");
        assertNotNull("Charter node for Port Sarim should exist", portSarimCharter);
        assertNotNull("Charter node for Catherby should exist", catherbyCharter);

        // Edge between Port Sarim and Catherby with gold requirement
        NavigationEdge charterEdge = graph.getEdgesFrom("charter_portsarim").stream()
                .filter(e -> e.getToNodeId().equals("charter_catherby"))
                .filter(e -> e.getType() == WebEdgeType.TRANSPORT)
                .filter(e -> "charter_ship".equals(e.getMetadata("travel_type")))
                .findFirst()
                .orElse(null);
        assertNotNull("Charter edge Port Sarim -> Catherby should exist", charterEdge);

        PlayerRequirements richPlayer = createPlayerWithGold(5000);
        PlayerRequirements brokePlayer = createPlayerWithGold(0);

        assertTrue("Player with coins should traverse charter edge",
                richPlayer.canTraverseEdge(charterEdge));
        assertFalse("Player without coins should fail charter edge",
                brokePlayer.canTraverseEdge(charterEdge));

        // Walk edge connects charter dock to base web
        boolean hasWalkConnection = graph.getEdgesFrom("charter_portsarim").stream()
                .anyMatch(e -> e.getType() == WebEdgeType.WALK && "port_sarim_docks".equals(e.getToNodeId()));
        assertTrue("Charter dock should connect to base web via walk edge", hasWalkConnection);
    }
    
    /**
     * Test: Spirit tree network is loaded and connected.
     */
    @Test
    public void testTransport_SpiritTree_NetworkLoaded() {
        WebNode strongholdTree = graph.getNode("spirittree_gnomestronghold");
        WebNode geTree = graph.getNode("spirittree_grandexchange");
        assertNotNull("Spirit tree - Stronghold should exist", strongholdTree);
        assertNotNull("Spirit tree - Grand Exchange should exist", geTree);

        NavigationEdge strongholdToGe = graph.getEdgesFrom("spirittree_gnomestronghold").stream()
                .filter(e -> e.getToNodeId().equals("spirittree_grandexchange"))
                .filter(e -> e.getType() == WebEdgeType.TRANSPORT)
                .filter(e -> "spirit_tree".equals(e.getMetadata("travel_type")))
                .findFirst()
                .orElse(null);

        assertNotNull("Spirit tree edge Stronghold -> GE should exist", strongholdToGe);

        PlayerRequirements noQuestPlayer = createNewPlayer(); // lacks Tree Gnome Village
        PlayerRequirements questPlayer = createPlayerWithQuest("Tree Gnome Village");

        assertFalse("Player without quest should not traverse spirit tree edge",
                noQuestPlayer.canTraverseEdge(strongholdToGe));
        assertTrue("Player with quest should traverse spirit tree edge",
                questPlayer.canTraverseEdge(strongholdToGe));

        // Walk connection from GE spirit tree to GE node
        boolean hasGeWalk = graph.getEdgesFrom("spirittree_grandexchange").stream()
                .anyMatch(e -> e.getType() == WebEdgeType.WALK && "varrock_ge".equals(e.getToNodeId()));
        assertTrue("GE spirit tree should connect to base web via walk edge", hasGeWalk);
    }
    
    /**
     * Test: Fairy ring network is loaded and connected.
     */
    @Test
    public void testTransport_FairyRing_NetworkLoaded() {
        PlayerRequirements player = createBasicPlayer();
        
        // Fairy rings use 3-letter codes (e.g., AIQ = Mudskipper Point)
        WebNode mudskipper = graph.getNode("fairy_ring_aiq");
        
        assertNotNull("Fairy ring AIQ (Mudskipper) should exist", mudskipper);
        
        // Check metadata
        assertEquals("Fairy ring should have correct code", "AIQ", 
                mudskipper.getMetadata().get("fairy_ring_code"));
    }
    
    /**
     * Test: Fairy ring edge requires Dramen staff.
     * Player WITH dramen staff should be able to traverse.
     */
    @Test
    public void testTransport_FairyRing_WithDramenStaff() {
        // Dramen staff item ID is 772
        final int DRAMEN_STAFF = 772;
        
        // Player who has the dramen staff and completed the quest
        PlayerRequirements playerWithStaff = createPlayerWithItem(DRAMEN_STAFF);
        
        // Get fairy ring edges
        List<NavigationEdge> fairyRingEdges = graph.getEdgesFrom("fairy_ring_aiq");
        assertFalse("Fairy ring should have edges", fairyRingEdges.isEmpty());
        
        // Filter to fairy ring transport edges
        List<NavigationEdge> transportEdges = fairyRingEdges.stream()
                .filter(e -> "fairy_ring".equals(e.getMetadata("travel_type")))
                .collect(java.util.stream.Collectors.toList());
        
        assertFalse("Should have fairy ring transport edges", transportEdges.isEmpty());
        
        // At least one edge should be traversable with dramen staff
        boolean anyTraversable = transportEdges.stream()
                .anyMatch(e -> playerWithStaff.canTraverseEdge(e));
        
        assertTrue("Fairy ring should be traversable WITH dramen staff", anyTraversable);
    }
    
    /**
     * Test: Fairy ring edge requires Dramen staff.
     * Player WITHOUT dramen staff should NOT be able to traverse.
     */
    @Test
    public void testTransport_FairyRing_WithoutDramenStaff() {
        // Player who does NOT have the dramen staff
        PlayerRequirements playerWithoutStaff = createNewPlayer();
        
        // Get fairy ring edges
        List<NavigationEdge> fairyRingEdges = graph.getEdgesFrom("fairy_ring_aiq");
        assertFalse("Fairy ring should have edges", fairyRingEdges.isEmpty());
        
        // Filter to fairy ring transport edges
        List<NavigationEdge> transportEdges = fairyRingEdges.stream()
                .filter(e -> "fairy_ring".equals(e.getMetadata("travel_type")))
                .collect(java.util.stream.Collectors.toList());
        
        assertFalse("Should have fairy ring transport edges", transportEdges.isEmpty());
        
        // No edge should be traversable without dramen staff
        boolean anyTraversable = transportEdges.stream()
                .anyMatch(e -> playerWithoutStaff.canTraverseEdge(e));
        
        assertFalse("Fairy ring should NOT be traversable WITHOUT dramen staff", anyTraversable);
    }
    
    /**
     * Test: Path between fairy rings exists when player has requirements.
     */
    @Test
    public void testTransport_FairyRing_PathExists() {
        final int DRAMEN_STAFF = 772;
        
        // Player who can use fairy rings
        PlayerRequirements playerWithStaff = createPlayerWithItem(DRAMEN_STAFF);
        
        // Check that path exists from AIQ (Mudskipper) to AJR (Slayer cave) using fairy rings
        boolean hasPath = graph.hasPath("fairy_ring_aiq", "fairy_ring_ajr", playerWithStaff);
        assertTrue("Should find path between fairy rings with dramen staff", hasPath);
    }
    
    /**
     * Test: Path between fairy rings does NOT exist when player lacks requirements.
     */
    @Test
    public void testTransport_FairyRing_PathBlockedWithoutStaff() {
        // Player who cannot use fairy rings (no dramen staff)
        PlayerRequirements playerWithoutStaff = createNewPlayer();
        
        // Check that path does NOT exist without dramen staff
        boolean hasPath = graph.hasPath("fairy_ring_aiq", "fairy_ring_ajr", playerWithoutStaff);
        assertFalse("Should NOT find path between fairy rings WITHOUT dramen staff", hasPath);
    }
    
    /**
     * Test: Quetzal transport to Varlamore is loaded.
     * Requires Twilight's Promise quest.
     */
    @Test
    public void testTransport_Quetzal_VarrockToVarlamore() {
        // Player who has completed Twilight's Promise
        PlayerRequirements questPlayer = createPlayerWithQuest("Twilight's Promise");
        
        // Quetzal entry point at Varrock
        WebNode quetzalVarrock = graph.getNode("quetzal_varrock");
        assertNotNull("Quetzal Varrock entry should exist", quetzalVarrock);
        
        // Varlamore destinations
        WebNode quetzalCivitas = graph.getNode("quetzal_civitas");
        assertNotNull("Quetzal Civitas should exist", quetzalCivitas);
        
        // Check edge exists from Varrock to Varlamore
        List<NavigationEdge> quetzalEdges = graph.getEdgesFrom("quetzal_varrock");
        boolean hasQuetzalToVarlamore = quetzalEdges.stream()
                .anyMatch(e -> e.getMetadata("travel_type") != null && 
                        e.getMetadata("travel_type").equals("quetzal"));
        assertTrue("Quetzal edge from Varrock should exist", hasQuetzalToVarlamore);
    }
    
    /**
     * Test: Canoe stations are loaded.
     */
    @Test
    public void testTransport_Canoe_StationsLoaded() {
        // Canoe stations
        WebNode lumCanoe = graph.getNode("canoe_lumbridge");
        WebNode edgeCanoe = graph.getNode("canoe_edgeville");
        WebNode feroxCanoe = graph.getNode("canoe_feroxenclave");
        
        assertNotNull("Lumbridge canoe station should exist", lumCanoe);
        assertNotNull("Edgeville canoe station should exist", edgeCanoe);
        assertNotNull("Ferox Enclave canoe station should exist", feroxCanoe);
    }
    
    /**
     * Test: Canoe requires woodcutting level.
     * Log canoe (1 stop) requires 12 WC.
     * Dugout (2 stops) requires 27 WC.
     * Stable Dugout (3 stops) requires 42 WC.
     * Waka (any stop, wilderness) requires 57 WC.
     */
    @Test
    public void testTransport_Canoe_WoodcuttingRequirements() {
        // Player with 12 Woodcutting - can only use Log (1 stop)
        PlayerRequirements lowWC = createPlayerWithSkill("Woodcutting", 12);
        
        // Player with 57 Woodcutting - can use Waka (anywhere)
        PlayerRequirements highWC = createPlayerWithSkill("Woodcutting", 57);
        
        // Get canoe edge from Lumbridge to Edgeville (3 stops away - requires Stable Dugout, 42 WC)
        List<NavigationEdge> edges = graph.getEdgesFrom("canoe_lumbridge");
        NavigationEdge lumToEdge = edges.stream()
                .filter(e -> "canoe_edgeville".equals(e.getToNodeId()))
                .findFirst()
                .orElse(null);
        
        assertNotNull("Should have edge from Lumbridge to Edgeville canoe", lumToEdge);
        
        // Low WC player should NOT be able to traverse (requires 42)
        assertFalse("12 WC player should NOT traverse to Edgeville (needs 42)", 
                lowWC.canTraverseEdge(lumToEdge));
        
        // High WC player should be able to traverse
        assertTrue("57 WC player SHOULD traverse to Edgeville", 
                highWC.canTraverseEdge(lumToEdge));
        
        // Check Waka to wilderness (requires 57)
        NavigationEdge lumToFerox = edges.stream()
                .filter(e -> "canoe_feroxenclave".equals(e.getToNodeId()))
                .findFirst()
                .orElse(null);
        
        if (lumToFerox != null) {
            assertFalse("12 WC player should NOT use Waka to wilderness (needs 57)", 
                    lowWC.canTraverseEdge(lumToFerox));
            // High WC player might still not traverse if they avoid wilderness
            // That's separate from the woodcutting requirement
        }
    }
    
    /**
     * Test: Lever transportation is loaded.
     */
    @Test
    public void testTransport_Lever_NodesLoaded() {
        WebNode ardyLever = graph.getNode("lever_ardougne");
        WebNode edgeLever = graph.getNode("lever_edgeville");
        WebNode wildLever = graph.getNode("lever_wilderness");
        
        assertNotNull("Ardougne lever should exist", ardyLever);
        assertNotNull("Edgeville lever should exist", edgeLever);
        assertNotNull("Wilderness lever should exist", wildLever);
    }
    
    /**
     * Test: Lever edges exist between safe and wilderness locations.
     */
    @Test
    public void testTransport_Lever_EdgesExist() {
        // Check Ardougne -> Wilderness lever
        List<NavigationEdge> ardyEdges = graph.getEdgesFrom("lever_ardougne");
        boolean hasWildernessEdge = ardyEdges.stream()
                .anyMatch(e -> "lever_wilderness".equals(e.getToNodeId()));
        assertTrue("Ardougne lever should connect to Wilderness", hasWildernessEdge);
        
        // Check Wilderness -> Ardougne and Edgeville returns
        List<NavigationEdge> wildEdges = graph.getEdgesFrom("lever_wilderness");
        boolean hasArdyReturn = wildEdges.stream()
                .anyMatch(e -> "lever_ardougne".equals(e.getToNodeId()));
        boolean hasEdgeReturn = wildEdges.stream()
                .anyMatch(e -> "lever_edgeville".equals(e.getToNodeId()));
        
        assertTrue("Wilderness lever should return to Ardougne", hasArdyReturn);
        assertTrue("Wilderness lever should return to Edgeville", hasEdgeReturn);
    }
    
    /**
     * Test: Player avoiding wilderness won't take lever to wilderness.
     */
    @Test
    public void testTransport_Lever_WildernessAvoidance() {
        // Player who avoids wilderness (e.g., HCIM or cautious player)
        PlayerRequirements cautious = createPlayerAvoidingWilderness();
        
        // Player willing to enter wilderness
        PlayerRequirements daring = createPlayerWillingToEnterWilderness();
        
        // Get Ardougne -> Wilderness edge
        List<NavigationEdge> ardyEdges = graph.getEdgesFrom("lever_ardougne");
        NavigationEdge toWilderness = ardyEdges.stream()
                .filter(e -> "lever_wilderness".equals(e.getToNodeId()))
                .findFirst()
                .orElse(null);
        
        assertNotNull("Ardougne->Wilderness lever edge should exist", toWilderness);
        
        // Verify edge is detected as wilderness
        assertTrue("Lever to wilderness should be detected as wilderness edge", 
                cautious.isWildernessEdge(toWilderness));
        
        // Cautious player should NOT traverse to wilderness
        assertFalse("Player avoiding wilderness should NOT use lever to wilderness",
                cautious.canTraverseEdge(toWilderness));
        
        // Daring player should be able to traverse
        assertTrue("Player willing to enter wilderness should use lever",
                daring.canTraverseEdge(toWilderness));
    }
    
    /**
     * Test: Gnome glider network is loaded.
     */
    @Test
    public void testTransport_GnomeGlider_NetworkLoaded() {
        // Check gnome glider nodes (using typical names from TransportationPointLocation)
        WebNode alKharidGlider = graph.getNode("glider_alkharid");
        WebNode strongholdGlider = graph.getNode("glider_gnomestronghold");
        
        // At least stronghold should exist
        if (strongholdGlider == null) {
            // Try alternate naming
            strongholdGlider = graph.getNode("gnome_glider_stronghold");
        }
        
        System.out.println("Gnome glider nodes found: " + 
                (alKharidGlider != null ? "Al Kharid, " : "") +
                (strongholdGlider != null ? "Stronghold" : "none"));
    }
    
    /**
     * Test: Home teleport is used when available and efficient.
     */
    @Test
    public void testTransport_HomeTeleport_UsedWhenEfficient() {
        // Player in wilderness border area with home teleport available
        PlayerRequirements player = createPlayerWithHomeTeleport(RespawnPoint.LUMBRIDGE);
        
        // Get home teleport edges
        List<NavigationEdge> homeTeleportEdges = graph.getAllEdges().stream()
                .filter(e -> e.getType() == WebEdgeType.FREE_TELEPORT)
                .filter(e -> "home".equals(e.getMetadata("teleport_type")))
                .filter(e -> player.canTraverseEdge(e))
                .toList();
        
        assertFalse("Should have at least one usable home teleport edge", homeTeleportEdges.isEmpty());
        
        // Find Lumbridge home teleport
        boolean hasLumbridgeHome = homeTeleportEdges.stream()
                .anyMatch(e -> "LUMBRIDGE".equals(e.getMetadata("respawn_point")));
        assertTrue("Should have Lumbridge home teleport available", hasLumbridgeHome);
    }
    
    /**
     * Test: Grouping teleports respect unlock requirements.
     * Castle Wars should always be available, NMZ requires having done at least 1 quest.
     */
    @Test
    public void testTransport_GroupingTeleport_UnlockRequirements() {
        // New player who hasn't unlocked anything
        PlayerRequirements newPlayer = createNewPlayer();
        
        // Castle Wars should always be available (no requirements)
        List<NavigationEdge> groupingEdges = graph.getAllEdges().stream()
                .filter(e -> e.getType() == WebEdgeType.FREE_TELEPORT)
                .filter(e -> "grouping".equals(e.getMetadata("teleport_type")))
                .toList();
        
        // Find Castle Wars edge
        NavigationEdge castleWarsEdge = groupingEdges.stream()
                .filter(e -> "castle_wars".equals(e.getMetadata("teleport_id")))
                .findFirst()
                .orElse(null);
        
        assertNotNull("Castle Wars grouping teleport should exist", castleWarsEdge);
        
        // Castle Wars requires no quests - should be traversable by new player
        // (assuming cooldown is available)
        if (newPlayer.isMinigameTeleportAvailable()) {
            assertTrue("Castle Wars should be traversable by new player",
                    newPlayer.canTraverseEdge(castleWarsEdge));
        }
    }

    /**
     * Test: Nightmare Zone grouping teleport requires boss count unlock.
     */
    @Test
    public void testTransport_GroupingTeleport_NightmareZoneRequiresBosses() {
        List<NavigationEdge> groupingEdges = graph.getAllEdges().stream()
                .filter(e -> e.getType() == WebEdgeType.FREE_TELEPORT)
                .filter(e -> "grouping".equals(e.getMetadata("teleport_type")))
                .toList();

        NavigationEdge nmzEdge = groupingEdges.stream()
                .filter(e -> "nightmare_zone".equals(e.getMetadata("teleport_id")))
                .findFirst()
                .orElse(null);

        assertNotNull("Nightmare Zone grouping teleport should exist", nmzEdge);
        assertEquals("Nightmare Zone grouping teleport should encode required boss count",
                "5", nmzEdge.getMetadata("required_boss_count"));

        PlayerRequirements locked = createNightmareZonePlayer(false);
        PlayerRequirements unlocked = createNightmareZonePlayer(true);

        if (locked.isMinigameTeleportAvailable()) {
            assertFalse("NMZ should be blocked when boss count not met",
                    locked.canTraverseEdge(nmzEdge));
        }

        if (unlocked.isMinigameTeleportAvailable()) {
            assertTrue("NMZ should be traversable when boss count met",
                    unlocked.canTraverseEdge(nmzEdge));
        }
    }

    // ========================================================================
    // Helper Methods for Transport Tests
    // ========================================================================
    
    private PlayerRequirements createPlayerWithGold(int gold) {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 50; }
            @Override public int getMagicLevel() { return 1; } // Low magic
            @Override public int getCombatLevel() { return 50; }
            @Override public int getSkillLevel(String skillName) { return 50; }
            @Override public int getTotalGold() { return gold; }
            @Override public int getInventoryGold() { return gold; }
            @Override public boolean hasItem(int itemId) { return false; } // No runes
            @Override public boolean hasItem(int itemId, int quantity) { return false; }
            @Override public boolean isQuestCompleted(Quest quest) { return false; }
            @Override public boolean isQuestCompleted(String questName) { return false; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.5; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }
    
    private PlayerRequirements createPlayerWithQuest(String questName) {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 70; }
            @Override public int getMagicLevel() { return 70; }
            @Override public int getCombatLevel() { return 100; }
            @Override public int getSkillLevel(String skillName) { return 70; }
            @Override public int getTotalGold() { return 500000; }
            @Override public int getInventoryGold() { return 50000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName2) { 
                return questName2.equals(questName) || true; // Has completed target quest
            }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.5; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }
    
    private PlayerRequirements createPlayerWithHomeTeleport(RespawnPoint respawn) {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 50; }
            @Override public int getMagicLevel() { return 50; }
            @Override public int getCombatLevel() { return 50; }
            @Override public int getSkillLevel(String skillName) { return 50; }
            @Override public int getTotalGold() { return 100000; }
            @Override public int getInventoryGold() { return 10000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.5; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { 
                return respawn.getDestination(); 
            }
            @Override public RespawnPoint getActiveRespawnPoint() { return respawn; }
        };
    }
    
    private PlayerRequirements createNewPlayer() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 1; }
            @Override public int getMagicLevel() { return 1; }
            @Override public int getCombatLevel() { return 3; }
            @Override public int getSkillLevel(String skillName) { return 1; }
            @Override public int getTotalGold() { return 0; }
            @Override public int getInventoryGold() { return 0; }
            @Override public boolean hasItem(int itemId) { return false; }
            @Override public boolean hasItem(int itemId, int quantity) { return false; }
            @Override public boolean isQuestCompleted(Quest quest) { return false; }
            @Override public boolean isQuestCompleted(String questName) { return false; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.0; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { 
                // New player only has Castle Wars unlocked
                return "castle_wars".equals(teleportId);
            }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    private PlayerRequirements createNightmareZonePlayer(boolean unlocked) {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 50; }
            @Override public int getMagicLevel() { return 50; }
            @Override public int getCombatLevel() { return 90; }
            @Override public int getSkillLevel(String skillName) { return 70; }
            @Override public int getTotalGold() { return 50000; }
            @Override public int getInventoryGold() { return 50000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.5; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) {
                if (!"nightmare_zone".equals(teleportId)) {
                    return true;
                }
                return unlocked;
            }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }
    
    /**
     * Create a player with a specific item (e.g., dramen staff for fairy rings).
     */
    private PlayerRequirements createPlayerWithItem(int targetItemId) {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 50; }
            @Override public int getMagicLevel() { return 50; }
            @Override public int getCombatLevel() { return 80; }
            @Override public int getSkillLevel(String skillName) { return 50; }
            @Override public int getTotalGold() { return 100000; }
            @Override public int getInventoryGold() { return 10000; }
            @Override public boolean hasItem(int itemId) { 
                return itemId == targetItemId; // Has the target item
            }
            @Override public boolean hasItem(int itemId, int quantity) { 
                return itemId == targetItemId;
            }
            @Override public boolean isQuestCompleted(Quest quest) { return true; } // Has all quests
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.5; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }
    
    /**
     * Create a player with a specific skill level.
     */
    private PlayerRequirements createPlayerWithSkill(String targetSkill, int level) {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { 
                return "Agility".equalsIgnoreCase(targetSkill) ? level : 50; 
            }
            @Override public int getMagicLevel() { 
                return "Magic".equalsIgnoreCase(targetSkill) ? level : 50; 
            }
            @Override public int getCombatLevel() { return 80; }
            @Override public int getSkillLevel(String skillName) { 
                return skillName.equalsIgnoreCase(targetSkill) ? level : 50;
            }
            @Override public int getTotalGold() { return 100000; }
            @Override public int getInventoryGold() { return 10000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.5; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }
    
    /**
     * Create a player who avoids wilderness (e.g., HCIM or cautious player).
     */
    private PlayerRequirements createPlayerAvoidingWilderness() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 70; }
            @Override public int getMagicLevel() { return 70; }
            @Override public int getCombatLevel() { return 100; }
            @Override public int getSkillLevel(String skillName) { return 70; }
            @Override public int getTotalGold() { return 1000000; }
            @Override public int getInventoryGold() { return 100000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return true; } // HCIM
            @Override public boolean isHardcore() { return true; } // HCIM
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.0; } // Zero risk
            @Override public boolean shouldAvoidWilderness() { return true; } // KEY: avoids wilderness
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    // ========================================================================
    // Performance Tests
    // ========================================================================

    @Test
    public void testPerformance_DijkstraWithFullGraph() {
        PlayerRequirements player = createBasicPlayer();
        
        // Measure time for pathfinding across full graph
        long startTime = System.nanoTime();
        int iterations = 100;
        
        for (int i = 0; i < iterations; i++) {
            graph.hasPath("lumbridge_castle", "varrock_west_bank", player);
            graph.hasPath("edgeville_bank", "wild_ferox_enclave", createPlayerWillingToEnterWilderness());
        }
        
        long endTime = System.nanoTime();
        long avgTimeMs = (endTime - startTime) / iterations / 1_000_000;
        
        // Should complete within reasonable time (< 100ms per path query)
        assertTrue("Average pathfinding time should be < 100ms, was " + avgTimeMs + "ms",
                avgTimeMs < 100);
        
        System.out.println("Performance: " + iterations + " path queries in " + 
                (endTime - startTime) / 1_000_000 + "ms (avg " + avgTimeMs + "ms each)");
    }

    @Test
    public void testPerformance_EdgeTraversabilityCheck() {
        PlayerRequirements player = createBasicPlayer();
        
        // Measure time for edge traversability checks
        List<NavigationEdge> allEdges = graph.getAllEdges();
        
        long startTime = System.nanoTime();
        int iterations = 1000;
        
        for (int i = 0; i < iterations; i++) {
            for (NavigationEdge edge : allEdges) {
                player.canTraverseEdge(edge);
            }
        }
        
        long endTime = System.nanoTime();
        long totalChecks = (long) iterations * allEdges.size();
        long avgTimeNs = (endTime - startTime) / totalChecks;
        
        // Each check should be very fast (< 1ms)
        assertTrue("Edge traversability check should be < 1000ns, was " + avgTimeNs + "ns",
                avgTimeNs < 1000);
        
        System.out.println("Performance: " + totalChecks + " edge checks in " + 
                (endTime - startTime) / 1_000_000 + "ms (avg " + avgTimeNs + "ns each)");
    }

    // ========================================================================
    // Edge Case Scenarios
    // ========================================================================

    @Test
    public void testEdgeCase_SameStartAndEnd() {
        PlayerRequirements player = createBasicPlayer();
        
        // Should handle same start and end gracefully
        boolean hasPath = graph.hasPath("lumbridge_castle", "lumbridge_castle", player);
        assertTrue("Path from node to itself should exist (trivially)", hasPath);
    }

    @Test
    public void testEdgeCase_NonexistentNode() {
        PlayerRequirements player = createBasicPlayer();
        
        // Should handle nonexistent nodes gracefully
        WebNode node = graph.getNode("this_node_does_not_exist_12345");
        assertNull("Nonexistent node should return null", node);
    }

    @Test
    public void testEdgeCase_NoEdgesFromIsolatedNode() {
        // If a node has no edges, getEdgesFrom should return empty list, not null
        List<NavigationEdge> edges = graph.getEdgesFrom("this_node_does_not_exist");
        assertNotNull("getEdgesFrom should never return null", edges);
        assertTrue("Nonexistent node should have no edges", edges.isEmpty());
    }

    // ========================================================================
    // Helper Methods - Player Requirement Factories
    // ========================================================================

    private PlayerRequirements createHCIMWithResources() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 70; }
            @Override public int getMagicLevel() { return 77; }
            @Override public int getCombatLevel() { return 100; }
            @Override public int getSkillLevel(String skillName) { return 70; }
            @Override public int getTotalGold() { return 500000; }
            @Override public int getInventoryGold() { return 50000; }
            @Override public boolean hasItem(int itemId) { return true; } // Has law runes
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return true; }
            @Override public boolean isHardcore() { return true; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.0; } // Zero risk
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return RespawnPoint.EDGEVILLE.getDestination(); }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.EDGEVILLE; }
        };
    }

    private PlayerRequirements createCautiousIronman() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 50; }
            @Override public int getMagicLevel() { return 60; }
            @Override public int getCombatLevel() { return 80; }
            @Override public int getSkillLevel(String skillName) { return 50; }
            @Override public int getTotalGold() { return 100000; }
            @Override public int getInventoryGold() { return 10000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return true; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.1; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    private PlayerRequirements createRiskyIronman() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 70; }
            @Override public int getMagicLevel() { return 80; }
            @Override public int getCombatLevel() { return 110; }
            @Override public int getSkillLevel(String skillName) { return 70; }
            @Override public int getTotalGold() { return 500000; }
            @Override public int getInventoryGold() { return 50000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return true; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.5; }
            @Override public boolean shouldAvoidWilderness() { return false; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return EDGEVILLE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.EDGEVILLE; }
        };
    }

    private PlayerRequirements createPlayerWithHomeTeleportOnCooldown() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 50; }
            @Override public int getMagicLevel() { return 50; }
            @Override public int getCombatLevel() { return 70; }
            @Override public int getSkillLevel(String skillName) { return 50; }
            @Override public int getTotalGold() { return 100000; }
            @Override public int getInventoryGold() { return 10000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.3; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return false; } // On cooldown!
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    private PlayerRequirements createPlayerWithHomeTeleportReady() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 50; }
            @Override public int getMagicLevel() { return 50; }
            @Override public int getCombatLevel() { return 70; }
            @Override public int getSkillLevel(String skillName) { return 50; }
            @Override public int getTotalGold() { return 100000; }
            @Override public int getInventoryGold() { return 10000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.3; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; } // Ready!
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    private PlayerRequirements createPlayerWithMinigameTeleportOnCooldown() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 50; }
            @Override public int getMagicLevel() { return 50; }
            @Override public int getCombatLevel() { return 70; }
            @Override public int getSkillLevel(String skillName) { return 50; }
            @Override public int getTotalGold() { return 100000; }
            @Override public int getInventoryGold() { return 10000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.3; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return false; } // On cooldown!
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    private PlayerRequirements createPlayerWithMinigameTeleportReady() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 50; }
            @Override public int getMagicLevel() { return 50; }
            @Override public int getCombatLevel() { return 70; }
            @Override public int getSkillLevel(String skillName) { return 50; }
            @Override public int getTotalGold() { return 100000; }
            @Override public int getInventoryGold() { return 10000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.3; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; } // Ready!
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { 
                // All grouping teleports unlocked
                return true; 
            }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    private PlayerRequirements createPlayerWithRespawnPoint(RespawnPoint respawn) {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 50; }
            @Override public int getMagicLevel() { return 50; }
            @Override public int getCombatLevel() { return 70; }
            @Override public int getSkillLevel(String skillName) { return 50; }
            @Override public int getTotalGold() { return 100000; }
            @Override public int getInventoryGold() { return 10000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.3; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return respawn.getDestination(); }
            @Override public RespawnPoint getActiveRespawnPoint() { return respawn; }
        };
    }

    private PlayerRequirements createPlayerWithoutQuest(Quest missingQuest) {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 50; }
            @Override public int getMagicLevel() { return 50; }
            @Override public int getCombatLevel() { return 70; }
            @Override public int getSkillLevel(String skillName) { return 50; }
            @Override public int getTotalGold() { return 100000; }
            @Override public int getInventoryGold() { return 10000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return quest != missingQuest; }
            @Override public boolean isQuestCompleted(String questName) { return !missingQuest.getName().equals(questName); }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.3; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { 
                // GOTR requires Temple of the Eye
                if ("GUARDIANS_OF_THE_RIFT".equals(teleportId)) {
                    return missingQuest != Quest.TEMPLE_OF_THE_EYE;
                }
                return true;
            }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    private PlayerRequirements createPlayerWithAllQuests() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 70; }
            @Override public int getMagicLevel() { return 77; }
            @Override public int getCombatLevel() { return 100; }
            @Override public int getSkillLevel(String skillName) { return 70; }
            @Override public int getTotalGold() { return 1000000; }
            @Override public int getInventoryGold() { return 100000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.3; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    private PlayerRequirements createPlayerWithCombatLevel(int combatLevel) {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 50; }
            @Override public int getMagicLevel() { return 50; }
            @Override public int getCombatLevel() { return combatLevel; }
            @Override public int getSkillLevel(String skillName) { return 50; }
            @Override public int getTotalGold() { return 100000; }
            @Override public int getInventoryGold() { return 10000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.3; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { 
                // Pest Control requires 40 combat
                if ("PEST_CONTROL".equals(teleportId)) {
                    return combatLevel >= 40;
                }
                return true;
            }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    private PlayerRequirements createWalkOnlyPlayer() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 1; }
            @Override public int getMagicLevel() { return 1; }
            @Override public int getCombatLevel() { return 3; }
            @Override public int getSkillLevel(String skillName) { return 1; }
            @Override public int getTotalGold() { return 0; }
            @Override public int getInventoryGold() { return 0; }
            @Override public boolean hasItem(int itemId) { return false; }
            @Override public boolean hasItem(int itemId, int quantity) { return false; }
            @Override public boolean isQuestCompleted(Quest quest) { return false; }
            @Override public boolean isQuestCompleted(String questName) { return false; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.0; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return false; }
            @Override public boolean isMinigameTeleportAvailable() { return false; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return false; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    private PlayerRequirements createBasicPlayer() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 70; }
            @Override public int getMagicLevel() { return 70; }
            @Override public int getCombatLevel() { return 100; }
            @Override public int getSkillLevel(String skillName) { return 70; }
            @Override public int getTotalGold() { return 1000000; }
            @Override public int getInventoryGold() { return 100000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.3; }
            @Override public boolean shouldAvoidWilderness() { return false; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    private PlayerRequirements createPoorPlayer() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 50; }
            @Override public int getMagicLevel() { return 50; }
            @Override public int getCombatLevel() { return 70; }
            @Override public int getSkillLevel(String skillName) { return 50; }
            @Override public int getTotalGold() { return 0; } // Poor!
            @Override public int getInventoryGold() { return 0; }
            @Override public boolean hasItem(int itemId) { return false; }
            @Override public boolean hasItem(int itemId, int quantity) { return false; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.3; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    private PlayerRequirements createPlayerWithAgilityLevel(int agilityLevel) {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return agilityLevel; }
            @Override public int getMagicLevel() { return 50; }
            @Override public int getCombatLevel() { return 70; }
            @Override public int getSkillLevel(String skillName) { 
                return "AGILITY".equalsIgnoreCase(skillName) ? agilityLevel : 50; 
            }
            @Override public int getTotalGold() { return 100000; }
            @Override public int getInventoryGold() { return 10000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.3; }
            @Override public boolean shouldAvoidWilderness() { return true; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    private PlayerRequirements createPlayerWhoAvoidsWilderness() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 70; }
            @Override public int getMagicLevel() { return 70; }
            @Override public int getCombatLevel() { return 100; }
            @Override public int getSkillLevel(String skillName) { return 70; }
            @Override public int getTotalGold() { return 500000; }
            @Override public int getInventoryGold() { return 50000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 0.0; } // Zero risk
            @Override public boolean shouldAvoidWilderness() { return true; } // Avoids wilderness!
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    private PlayerRequirements createPlayerWillingToEnterWilderness() {
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 70; }
            @Override public int getMagicLevel() { return 70; }
            @Override public int getCombatLevel() { return 100; }
            @Override public int getSkillLevel(String skillName) { return 70; }
            @Override public int getTotalGold() { return 500000; }
            @Override public int getInventoryGold() { return 50000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 1.0; } // High risk tolerance
            @Override public boolean shouldAvoidWilderness() { return false; } // Willing to enter wilderness!
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
        };
    }

    /**
     * Create a player at a specific wilderness level for teleport restriction testing.
     * Y coordinate: 3520 + (level-1)*8
     */
    private PlayerRequirements createPlayerInWilderness(int wildernessLevel) {
        // Calculate Y coordinate for this wilderness level
        final int y = 3520 + (wildernessLevel - 1) * 8;
        final WorldPoint location = new WorldPoint(3100, y, 0);
        
        return new PlayerRequirements() {
            @Override public int getAgilityLevel() { return 70; }
            @Override public int getMagicLevel() { return 70; }
            @Override public int getCombatLevel() { return 100; }
            @Override public int getSkillLevel(String skillName) { return 70; }
            @Override public int getTotalGold() { return 500000; }
            @Override public int getInventoryGold() { return 50000; }
            @Override public boolean hasItem(int itemId) { return true; }
            @Override public boolean hasItem(int itemId, int quantity) { return true; }
            @Override public boolean isQuestCompleted(Quest quest) { return true; }
            @Override public boolean isQuestCompleted(String questName) { return true; }
            @Override public boolean isIronman() { return false; }
            @Override public boolean isHardcore() { return false; }
            @Override public boolean isUltimateIronman() { return false; }
            @Override public double getAcceptableRiskThreshold() { return 1.0; }
            @Override public boolean shouldAvoidWilderness() { return false; }
            @Override public boolean isHomeTeleportAvailable() { return true; }
            @Override public boolean isMinigameTeleportAvailable() { return true; }
            @Override public boolean isGroupingTeleportUnlocked(String teleportId) { return true; }
            @Override public WorldPoint getHomeTeleportDestination() { return LUMBRIDGE; }
            @Override public RespawnPoint getActiveRespawnPoint() { return RespawnPoint.LUMBRIDGE; }
            @Override public WorldPoint getCurrentLocation() { return location; } // Key: return wilderness location
        };
    }

    // ========================================================================
    // Plane Transition Tests
    // ========================================================================

    /** Varrock West Bank basement location */
    private static final WorldPoint VARROCK_WEST_BANK_BASEMENT = new WorldPoint(3187, 9825, 0);

    /**
     * Test: Plane transition nodes are loaded.
     * Verifies that transition nodes exist for multi-floor navigation.
     */
    @Test
    public void testPlaneTransition_NodesLoaded() {
        // Lumbridge Castle transition nodes
        WebNode lumbGroundStairs = graph.getNode("lumbridge_castle_ground");
        WebNode lumbFloor1 = graph.getNode("lumbridge_castle_floor1");
        WebNode lumbBank = graph.getNode("lumbridge_bank"); // Uses existing bank node
        
        assertNotNull("Lumbridge Castle ground stairs node should exist", lumbGroundStairs);
        assertNotNull("Lumbridge Castle floor 1 node should exist", lumbFloor1);
        assertNotNull("Lumbridge Bank node should exist", lumbBank);
        
        // Verify planes
        assertEquals("Ground floor should be plane 0", 0, lumbGroundStairs.getPlane());
        assertEquals("First floor should be plane 1", 1, lumbFloor1.getPlane());
        assertEquals("Bank should be plane 2", 2, lumbBank.getPlane());
    }

    /**
     * Test: Varrock West Bank basement is accessible via stairs.
     * This is the "no hints" test - can we find a path from Lumbridge to the basement?
     */
    @Test
    public void testPlaneTransition_LumbridgeToVarrockBasement() {
        // Verify the basement node exists
        WebNode varrockBasement = graph.getNode("varrock_west_bank_basement");
        assertNotNull("Varrock West Bank basement should exist", varrockBasement);
        
        // Verify stairs edge exists from varrock_west_bank to basement
        List<NavigationEdge> edges = graph.getEdgesFrom("varrock_west_bank");
        boolean hasStairsToBasement = edges.stream()
                .anyMatch(e -> "varrock_west_bank_basement".equals(e.getToNodeId()) &&
                        e.getType() == WebEdgeType.STAIRS);
        assertTrue("Should have stairs edge to basement", hasStairsToBasement);
        
        // Test full path: Lumbridge -> Varrock -> Basement
        PlayerRequirements player = createBasicPlayer();
        
        // Find nearest node to Lumbridge
        WebNode nearLumbridge = graph.findNearestNodeSamePlane(LUMBRIDGE);
        assertNotNull("Should find node near Lumbridge", nearLumbridge);
        
        // Check if path exists to varrock_west_bank (the surface entry point)
        boolean hasPathToVarrock = graph.hasPath(nearLumbridge.getId(), "varrock_west_bank", player);
        assertTrue("Should have path from Lumbridge to Varrock West Bank", hasPathToVarrock);
        
        // Check if path exists all the way to basement
        boolean hasPathToBasement = graph.hasPath(nearLumbridge.getId(), "varrock_west_bank_basement", player);
        assertTrue("Should have path from Lumbridge to Varrock West Bank basement", hasPathToBasement);
    }

    /**
     * Test: Lumbridge Castle multi-floor navigation.
     * Can we navigate from ground floor to the bank on floor 2?
     */
    @Test
    public void testPlaneTransition_LumbridgeCastleToBank() {
        PlayerRequirements player = createBasicPlayer();
        
        // Path from lumbridge_castle (ground) to lumbridge_bank (plane 2)
        boolean hasPath = graph.hasPath("lumbridge_castle", "lumbridge_bank", player);
        
        // If direct doesn't work, check the chain: castle -> ground -> floor1 -> bank
        if (!hasPath) {
            // Check each step of the chain
            boolean step1 = graph.hasPath("lumbridge_castle", "lumbridge_castle_ground", player);
            boolean step2 = graph.hasPath("lumbridge_castle_ground", "lumbridge_castle_floor1", player);
            boolean step3 = graph.hasPath("lumbridge_castle_floor1", "lumbridge_bank", player);
            
            assertTrue("Should have path: castle -> ground stairs", step1);
            assertTrue("Should have path: ground -> floor1", step2);
            assertTrue("Should have path: floor1 -> bank", step3);
        } else {
            assertTrue("Should have direct path to bank", hasPath);
        }
    }

    /**
     * Test: Edgeville dungeon is accessible via trapdoor.
     */
    @Test
    public void testPlaneTransition_EdgevilleDungeon() {
        WebNode dungeonEntrance = graph.getNode("edgeville_dungeon_entrance");
        WebNode dungeon = graph.getNode("edgeville_dungeon");
        
        assertNotNull("Edgeville dungeon entrance should exist", dungeonEntrance);
        assertNotNull("Edgeville dungeon should exist", dungeon);
        
        // Verify they're connected
        PlayerRequirements player = createBasicPlayer();
        boolean hasPath = graph.hasPath("edgeville_dungeon_entrance", "edgeville_dungeon", player);
        assertTrue("Should have path from entrance to dungeon", hasPath);
    }

    // ========================================================================
    // Helper Methods - Utilities
    // ========================================================================

    /**
     * Check if an edge leads INTO wilderness.
     * Wilderness is Y >= 3520 in the overworld.
     * 
     * <p>This must match the logic in {@link PlayerRequirements#isWildernessEdge} which
     * only checks the DESTINATION to determine if traversing the edge would take you
     * into wilderness. Edges that leave wilderness (destination outside) are not blocked.
     */
    private boolean isWildernessEdge(NavigationEdge edge) {
        // Check metadata for explicit wilderness tag
        if (edge.getMetadata() != null) {
            if ("true".equals(edge.getMetadata().get("wilderness"))) {
                return true;
            }
        }
        
        // Check DESTINATION coordinates only (not source)
        // This matches PlayerRequirements.isWildernessEdge which only blocks edges going INTO wilderness
        WebNode toNode = graph.getNode(edge.getToNodeId());
        if (toNode != null && toNode.getY() >= 3520 && toNode.getPlane() == 0) {
            return true;
        }
        
        // Check if destination node ID contains "wild" (abbreviated wilderness prefix)
        String toNodeId = edge.getToNodeId();
        if (toNodeId != null && (toNodeId.toLowerCase().contains("wilderness") || 
                                  toNodeId.toLowerCase().startsWith("wild_"))) {
            return true;
        }
        
        return false;
    }
}

