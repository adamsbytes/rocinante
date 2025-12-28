package com.rocinante.navigation;

import com.rocinante.progression.UnlockTracker;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.*;

/**
 * Long-distance navigation via pre-defined navigation graph.
 * Per REQUIREMENTS.md Section 7.2.
 *
 * <p>Features:
 * <ul>
 *   <li>Dijkstra's algorithm on unified navigation graph</li>
 *   <li>Multi-plane pathfinding (stairs, ladders, trapdoors)</li>
 *   <li>Agility shortcut integration with risk assessment</li>
 *   <li>Toll gate handling with quest-based free passage</li>
 *   <li>Requirement checking for edges (skills, items, quests)</li>
 *   <li>Ironman restriction filtering</li>
 *   <li>HCIM safety filtering (wilderness avoidance)</li>
 *   <li>Teleport preference integration</li>
 * </ul>
 */
@Slf4j
@Singleton
public class WebWalker {

    private final Client client;

    /**
     * UnlockTracker for checking edge requirements.
     * Optional - if null, falls back to basic checks.
     */
    @Setter
    @Nullable
    private UnlockTracker unlockTracker;

    /**
     * The raw navigation web data.
     */
    @Getter
    private NavigationWeb navigationWeb;

    /**
     * The unified navigation graph (includes plane transitions).
     */
    @Getter
    @Nullable
    private UnifiedNavigationGraph unifiedGraph;

    /**
     * Plane transition handler for vertical movement.
     */
    @Setter
    @Nullable
    private PlaneTransitionHandler planeTransitionHandler;

    /**
     * Whether the player is an ironman (affects edge filtering).
     */
    private boolean isIronman = false;

    /**
     * Whether the player is a HCIM (affects safety filtering).
     */
    private boolean isHardcoreIronman = false;

    /**
     * Whether wilderness edges should be avoided.
     */
    private boolean avoidWilderness = true;

    /**
     * Maximum acceptable failure rate for agility shortcuts.
     * Default: 10% - shortcuts with higher failure rates are avoided.
     */
    @Setter
    private double maxAcceptableRisk = 0.10;

    /**
     * Primary constructor for dependency injection.
     * Uses the full constructor with all optional dependencies.
     */
    @Inject
    public WebWalker(Client client, @Nullable UnlockTracker unlockTracker, 
                     @Nullable PlaneTransitionHandler planeTransitionHandler) {
        this.client = client;
        this.unlockTracker = unlockTracker;
        this.planeTransitionHandler = planeTransitionHandler;
        loadNavigationWeb();
        buildUnifiedGraph();
        
        if (unlockTracker != null) {
            log.info("WebWalker initialized with UnlockTracker integration");
        }
        if (unifiedGraph != null) {
            log.info("WebWalker using unified graph with {} nodes, {} edges",
                    unifiedGraph.getNodeCount(), unifiedGraph.getEdgeCount());
        }
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    /**
     * Load the navigation web from resources.
     */
    private void loadNavigationWeb() {
        try {
            navigationWeb = NavigationWeb.loadFromResources();
            log.info("WebWalker loaded navigation web with {} nodes and {} edges",
                    navigationWeb.getNodes().size(),
                    navigationWeb.getEdges().size());
        } catch (IOException e) {
            log.error("Failed to load navigation web", e);
            navigationWeb = null;
        }
    }

    /**
     * Build the unified navigation graph.
     * This must succeed or the entire navigation system is broken.
     * 
     * @throws IllegalStateException if the graph cannot be built
     */
    private void buildUnifiedGraph() {
        if (navigationWeb == null) {
            throw new IllegalStateException(
                "Cannot build unified graph: navigation web not loaded. " +
                "Ensure /data/navigation_web.json exists and is valid.");
        }

        try {
            if (planeTransitionHandler != null) {
                unifiedGraph = new UnifiedNavigationGraph(navigationWeb, planeTransitionHandler);
            } else {
                unifiedGraph = new UnifiedNavigationGraph(navigationWeb);
            }
            
            if (unifiedGraph == null || unifiedGraph.getNodeCount() == 0) {
                throw new IllegalStateException("Unified graph was built but is empty");
            }
            
            log.info("Built unified graph: {} nodes, {} edges",
                    unifiedGraph.getNodeCount(), unifiedGraph.getEdgeCount());
        } catch (IllegalStateException e) {
            throw e; // Re-throw our own exceptions
        } catch (Exception e) {
            log.error("Failed to build unified navigation graph", e);
            throw new IllegalStateException(
                "Failed to build unified navigation graph: " + e.getMessage(), e);
        }
    }

    /**
     * Reload the navigation web and rebuild unified graph.
     */
    public void reloadNavigationWeb() {
        loadNavigationWeb();
        buildUnifiedGraph();
    }

    /**
     * Set the plane transition handler and rebuild graph.
     */
    public void setPlaneTransitionHandler(PlaneTransitionHandler handler) {
        this.planeTransitionHandler = handler;
        buildUnifiedGraph();
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Set ironman mode for edge filtering.
     *
     * @param ironman true if player is ironman
     */
    public void setIronman(boolean ironman) {
        this.isIronman = ironman;
    }

    /**
     * Set HCIM mode for safety filtering.
     *
     * @param hardcore true if player is HCIM
     */
    public void setHardcoreIronman(boolean hardcore) {
        this.isHardcoreIronman = hardcore;
        if (hardcore) {
            this.avoidWilderness = true;
        }
    }

    /**
     * Set wilderness avoidance.
     *
     * @param avoid true to avoid wilderness
     */
    public void setAvoidWilderness(boolean avoid) {
        this.avoidWilderness = avoid;
    }

    // ========================================================================
    // Path Finding (Legacy - returns List<WebNode>)
    // ========================================================================

    /**
     * Find a path between two world points using the navigation web.
     *
     * @param start the starting world point
     * @param end   the destination world point
     * @return list of web nodes forming the path (empty if no path found)
     * @deprecated Use {@link #findUnifiedPath(WorldPoint, WorldPoint)} for multi-plane support
     */
    @Deprecated
    public List<WebNode> findPath(WorldPoint start, WorldPoint end) {
        if (navigationWeb == null) {
            log.warn("WebWalker: navigation web not loaded");
            return Collections.emptyList();
        }

        // Find nearest nodes to start and end
        WebNode startNode = navigationWeb.findNearestNode(start);
        WebNode endNode = navigationWeb.findNearestNode(end);

        if (startNode == null || endNode == null) {
            log.debug("WebWalker: could not find nodes near start ({}) or end ({})", start, end);
            return Collections.emptyList();
        }

        return findPath(startNode.getId(), endNode.getId());
    }

    /**
     * Find a path between two nodes by ID.
     *
     * @param startId the starting node ID
     * @param endId   the destination node ID
     * @return list of web nodes forming the path (empty if no path found)
     * @deprecated Use {@link #findUnifiedPath(String, String)} for multi-plane support
     */
    @Deprecated
    public List<WebNode> findPath(String startId, String endId) {
        if (navigationWeb == null) {
            log.warn("WebWalker: navigation web not loaded");
            return Collections.emptyList();
        }

        WebNode startNode = navigationWeb.getNode(startId);
        WebNode endNode = navigationWeb.getNode(endId);

        if (startNode == null || endNode == null) {
            log.debug("WebWalker: invalid node IDs - start: {}, end: {}", startId, endId);
            return Collections.emptyList();
        }

        return runDijkstra(startNode, endNode);
    }

    /**
     * Find a path to the nearest node of a specific type.
     *
     * @param start the starting world point
     * @param type  the target node type
     * @return list of web nodes forming the path (empty if no path found)
     */
    public List<WebNode> findPathToNearestType(WorldPoint start, WebNodeType type) {
        if (navigationWeb == null) {
            return Collections.emptyList();
        }

        WebNode startNode = navigationWeb.findNearestNode(start);
        if (startNode == null) {
            return Collections.emptyList();
        }

        // Find all nodes of the target type
        List<WebNode> targets = navigationWeb.getNodesByType(type);
        if (targets.isEmpty()) {
            return Collections.emptyList();
        }

        // Find shortest path to any target
        List<WebNode> bestPath = null;
        int bestCost = Integer.MAX_VALUE;

        for (WebNode target : targets) {
            List<WebNode> path = runDijkstra(startNode, target);
            if (!path.isEmpty()) {
                int cost = calculatePathCost(path);
                if (cost < bestCost) {
                    bestPath = path;
                    bestCost = cost;
                }
            }
        }

        return bestPath != null ? bestPath : Collections.emptyList();
    }

    /**
     * Find path to the nearest bank.
     *
     * @param start the starting world point
     * @return list of web nodes forming the path
     */
    public List<WebNode> findPathToNearestBank(WorldPoint start) {
        return findPathToNearestType(start, WebNodeType.BANK);
    }

    // ========================================================================
    // Unified Path Finding (returns NavigationPath with edges)
    // ========================================================================

    /**
     * Find a unified path between two world points.
     * 
     * <p>This method supports:
     * <ul>
     *   <li>Multi-plane pathfinding (paths that go up/down stairs)</li>
     *   <li>Agility shortcuts when player has sufficient level</li>
     *   <li>Toll gates with cost consideration</li>
     *   <li>All edge types in a single path</li>
     * </ul>
     *
     * @param start the starting world point
     * @param end   the destination world point
     * @return NavigationPath with edges (empty path if no path found)
     */
    public NavigationPath findUnifiedPath(WorldPoint start, WorldPoint end) {
        if (unifiedGraph == null) {
            throw new IllegalStateException(
                "WebWalker: unified graph not available. " +
                "Navigation is broken - ensure buildUnifiedGraph() succeeded during initialization.");
        }

        // Find nearest nodes, allowing cross-plane search for destination
        WebNode startNode = unifiedGraph.findNearestNodeSamePlane(start);
        WebNode endNode = unifiedGraph.findNearestNodeAnyPlane(end);

        if (startNode == null || endNode == null) {
            log.debug("WebWalker: could not find nodes near start ({}) or end ({})", start, end);
            return NavigationPath.empty();
        }

        return findUnifiedPath(startNode.getId(), endNode.getId(), start, end);
    }

    /**
     * Find a unified path between two nodes by ID.
     *
     * @param startId the starting node ID
     * @param endId   the destination node ID
     * @return NavigationPath with edges (empty path if no path found)
     */
    public NavigationPath findUnifiedPath(String startId, String endId) {
        if (unifiedGraph == null) {
            throw new IllegalStateException(
                "WebWalker: unified graph not available. " +
                "Navigation is broken - ensure buildUnifiedGraph() succeeded during initialization.");
        }

        WebNode startNode = unifiedGraph.getNode(startId);
        WebNode endNode = unifiedGraph.getNode(endId);

        if (startNode == null || endNode == null) {
            log.debug("WebWalker: invalid node IDs - start: {}, end: {}", startId, endId);
            return NavigationPath.empty();
        }

        return findUnifiedPath(startId, endId, 
                startNode.getWorldPoint(), endNode.getWorldPoint());
    }

    /**
     * Find a unified path with explicit start/end points.
     */
    private NavigationPath findUnifiedPath(String startId, String endId,
                                            WorldPoint startPoint, WorldPoint endPoint) {
        // Create player requirements from current state
        PlayerRequirements playerReqs = createPlayerRequirements();

        // Run unified Dijkstra
        List<NavigationEdge> edges = runUnifiedDijkstra(startId, endId, playerReqs);

        if (edges.isEmpty()) {
            log.debug("WebWalker: no unified path found from {} to {}", startId, endId);
            return NavigationPath.empty();
        }

        // Calculate total cost
        int totalCost = edges.stream()
                .mapToInt(NavigationEdge::getCostTicks)
                .sum();

        // Build node ID list
        List<String> nodeIds = new ArrayList<>();
        nodeIds.add(startId);
        for (NavigationEdge edge : edges) {
            nodeIds.add(edge.getToNodeId());
        }

        return NavigationPath.builder()
                .edges(edges)
                .nodeIds(nodeIds)
                .totalCostTicks(totalCost)
                .startPoint(startPoint)
                .endPoint(endPoint)
                .build();
    }

    /**
     * Find a unified path to the nearest node of a specific type.
     *
     * @param start the starting world point
     * @param type  the target node type
     * @return NavigationPath (empty if no path found)
     */
    public NavigationPath findUnifiedPathToNearestType(WorldPoint start, WebNodeType type) {
        if (unifiedGraph == null) {
            throw new IllegalStateException(
                "WebWalker: unified graph not available. " +
                "Navigation is broken - ensure buildUnifiedGraph() succeeded during initialization.");
        }

        WebNode startNode = unifiedGraph.findNearestNodeSamePlane(start);
        if (startNode == null) {
            return NavigationPath.empty();
        }

        // Find all nodes of the target type
        List<WebNode> targets = navigationWeb.getNodesByType(type);
        if (targets.isEmpty()) {
            return NavigationPath.empty();
        }

        // Find shortest path to any target
        NavigationPath bestPath = NavigationPath.empty();
        int bestCost = Integer.MAX_VALUE;

        PlayerRequirements playerReqs = createPlayerRequirements();

        for (WebNode target : targets) {
            List<NavigationEdge> edges = runUnifiedDijkstra(startNode.getId(), target.getId(), playerReqs);
            if (!edges.isEmpty()) {
                int cost = edges.stream().mapToInt(NavigationEdge::getCostTicks).sum();
                if (cost < bestCost) {
                    List<String> nodeIds = new ArrayList<>();
                    nodeIds.add(startNode.getId());
                    for (NavigationEdge edge : edges) {
                        nodeIds.add(edge.getToNodeId());
                    }

                    bestPath = NavigationPath.builder()
                            .edges(edges)
                            .nodeIds(nodeIds)
                            .totalCostTicks(cost)
                            .startPoint(start)
                            .endPoint(target.getWorldPoint())
                            .build();
                    bestCost = cost;
                }
            }
        }

        return bestPath;
    }

    /**
     * Find unified path to the nearest bank.
     *
     * @param start the starting world point
     * @return NavigationPath
     */
    public NavigationPath findUnifiedPathToNearestBank(WorldPoint start) {
        return findUnifiedPathToNearestType(start, WebNodeType.BANK);
    }

    // ========================================================================
    // Dijkstra's Algorithm (Legacy - returns List<WebNode>)
    // ========================================================================

    /**
     * Run Dijkstra's algorithm to find shortest path.
     */
    private List<WebNode> runDijkstra(WebNode start, WebNode end) {
        // Priority queue ordered by cost
        PriorityQueue<DijkstraNode> openSet = new PriorityQueue<>(
                Comparator.comparingInt(n -> n.cost));

        // Track visited nodes and best costs
        Map<String, Integer> bestCost = new HashMap<>();
        Map<String, String> cameFrom = new HashMap<>();

        // Initialize
        openSet.add(new DijkstraNode(start.getId(), 0));
        bestCost.put(start.getId(), 0);

        while (!openSet.isEmpty()) {
            DijkstraNode current = openSet.poll();

            // Check if we reached the goal
            if (current.nodeId.equals(end.getId())) {
                return reconstructPath(cameFrom, end.getId());
            }

            // Skip if we've found a better path to this node
            Integer currentBest = bestCost.get(current.nodeId);
            if (currentBest != null && current.cost > currentBest) {
                continue;
            }

            // Explore neighbors
            List<WebEdge> edges = getTraversableEdges(current.nodeId);
            for (WebEdge edge : edges) {
                String neighborId = edge.getTo();

                // Skip wilderness edges if configured
                if (avoidWilderness && navigationWeb.isInWilderness(neighborId)) {
                    continue;
                }

                int newCost = current.cost + edge.getCostTicks();
                Integer neighborBest = bestCost.get(neighborId);

                if (neighborBest == null || newCost < neighborBest) {
                    bestCost.put(neighborId, newCost);
                    cameFrom.put(neighborId, current.nodeId);
                    openSet.add(new DijkstraNode(neighborId, newCost));
                }
            }
        }

        // No path found
        log.debug("WebWalker: no path found from {} to {}", start.getId(), end.getId());
        return Collections.emptyList();
    }

    // ========================================================================
    // Unified Dijkstra's Algorithm (returns List<NavigationEdge>)
    // ========================================================================

    /**
     * Run Dijkstra's algorithm on the unified graph.
     * Returns a list of edges forming the path.
     */
    private List<NavigationEdge> runUnifiedDijkstra(String startId, String endId,
                                                     PlayerRequirements playerReqs) {
        if (unifiedGraph == null) {
            return Collections.emptyList();
        }

        // Priority queue ordered by cost
        PriorityQueue<UnifiedDijkstraNode> openSet = new PriorityQueue<>(
                Comparator.comparingInt(n -> n.cost));

        // Track visited nodes and best costs
        Map<String, Integer> bestCost = new HashMap<>();
        Map<String, NavigationEdge> cameFromEdge = new HashMap<>();

        // Initialize
        openSet.add(new UnifiedDijkstraNode(startId, 0, null));
        bestCost.put(startId, 0);

        while (!openSet.isEmpty()) {
            UnifiedDijkstraNode current = openSet.poll();

            // Check if we reached the goal
            if (current.nodeId.equals(endId)) {
                return reconstructEdgePath(cameFromEdge, startId, endId);
            }

            // Skip if we've found a better path to this node
            Integer currentBest = bestCost.get(current.nodeId);
            if (currentBest != null && current.cost > currentBest) {
                continue;
            }

            // Explore neighbors using unified graph
            List<NavigationEdge> edges = unifiedGraph.getTraversableEdges(current.nodeId, playerReqs);
            for (NavigationEdge edge : edges) {
                String neighborId = edge.getToNodeId();

                // Skip wilderness edges if configured
                if (avoidWilderness && navigationWeb.isInWilderness(neighborId)) {
                    continue;
                }

                // Calculate edge cost with adjustments
                int edgeCost = calculateAdjustedCost(edge, playerReqs);
                int newCost = current.cost + edgeCost;
                Integer neighborBest = bestCost.get(neighborId);

                if (neighborBest == null || newCost < neighborBest) {
                    bestCost.put(neighborId, newCost);
                    cameFromEdge.put(neighborId, edge);
                    openSet.add(new UnifiedDijkstraNode(neighborId, newCost, edge));
                }
            }
        }

        // No path found
        return Collections.emptyList();
    }

    /**
     * Calculate adjusted cost for an edge based on player state.
     */
    private int calculateAdjustedCost(NavigationEdge edge, PlayerRequirements playerReqs) {
        int baseCost = edge.getCostTicks();

        // Add risk penalty for agility shortcuts
        if (edge.isAgilityShortcut()) {
            double failureRate = edge.getFailureRate();
            if (failureRate > 0) {
                // Add penalty based on expected failure cost (time to retry)
                int failurePenalty = (int) (failureRate * baseCost * 3); // 3x retry cost estimate
                baseCost += failurePenalty;
            }
        }

        // Toll gates: add small penalty to prefer free routes
        if (edge.isTollGate()) {
            // Check if player has free passage
            String freeQuest = edge.getFreePassageQuest();
            if (freeQuest != null && playerReqs.isQuestCompleted(freeQuest)) {
                // No penalty - free passage
            } else if (edge.getTollCost() > 0) {
                // Add penalty proportional to gold cost
                baseCost += edge.getTollCost() / 10; // 10gp = 1 tick penalty
            }
        }

        return baseCost;
    }

    /**
     * Reconstruct edge path from Dijkstra results.
     */
    private List<NavigationEdge> reconstructEdgePath(Map<String, NavigationEdge> cameFromEdge,
                                                       String startId, String endId) {
        List<NavigationEdge> path = new ArrayList<>();
        String currentId = endId;

        while (!currentId.equals(startId)) {
            NavigationEdge edge = cameFromEdge.get(currentId);
            if (edge == null) {
                break; // Shouldn't happen if path exists
            }
            path.add(edge);
            currentId = edge.getFromNodeId();
        }

        Collections.reverse(path);
        return path;
    }

    /**
     * Create PlayerRequirements from current player state.
     */
    private PlayerRequirements createPlayerRequirements() {
        return new PlayerRequirements() {
            @Override
            public int getAgilityLevel() {
                return WebWalker.this.getSkillLevel(Skill.AGILITY);
            }

            @Override
            public int getMagicLevel() {
                return WebWalker.this.getSkillLevel(Skill.MAGIC);
            }

            @Override
            public int getCombatLevel() {
                return WebWalker.this.getCombatLevel();
            }

            @Override
            public int getSkillLevel(String skillName) {
                Skill skill = getSkillByName(skillName);
                return skill != null ? WebWalker.this.getSkillLevel(skill) : 1;
            }

            @Override
            public int getTotalGold() {
                // Try to get gold from inventory
                // Note: Bank gold requires additional tracking
                return getInventoryGold();
            }

            @Override
            public int getInventoryGold() {
                // Gold coins item ID is 995
                // Without direct inventory access, return 0
                // This would be populated by TaskContext or GameStateService
                return 0;
            }

            @Override
            public boolean hasItem(int itemId) {
                return hasItem(itemId, 1);
            }

            @Override
            public boolean hasItem(int itemId, int quantity) {
                if (unlockTracker != null) {
                    return unlockTracker.hasItem(itemId, quantity);
                }
                return false;
            }

            @Override
            public boolean isQuestCompleted(Quest quest) {
                if (unlockTracker != null) {
                    return unlockTracker.isQuestCompleted(quest);
                }
                return false;
            }

            @Override
            public boolean isQuestCompleted(String questName) {
                if (unlockTracker != null) {
                    try {
                        Quest quest = Quest.valueOf(questName);
                        return unlockTracker.isQuestCompleted(quest);
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                }
                return false;
            }

            @Override
            public boolean isIronman() {
                return WebWalker.this.isIronman;
            }

            @Override
            public boolean isHardcore() {
                return WebWalker.this.isHardcoreIronman;
            }

            @Override
            public boolean isUltimateIronman() {
                // TODO: Add UIM detection if needed
                return false;
            }

            @Override
            public double getAcceptableRiskThreshold() {
                return maxAcceptableRisk;
            }

            @Override
            public boolean shouldAvoidWilderness() {
                return avoidWilderness;
            }
        };
    }

    /**
     * Get traversable edges from a node, filtered by requirements.
     */
    private List<WebEdge> getTraversableEdges(String nodeId) {
        return navigationWeb.getTraversableEdges(nodeId, this::canTraverseEdge);
    }

    /**
     * Check if an edge can be traversed based on current player state.
     */
    private boolean canTraverseEdge(WebEdge edge) {
        if (!edge.hasRequirements()) {
            return true;
        }

        for (EdgeRequirement req : edge.getRequirements()) {
            if (!checkRequirement(req)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check a single requirement.
     * Delegates to UnlockTracker when available for comprehensive checking.
     */
    private boolean checkRequirement(EdgeRequirement req) {
        // Use UnlockTracker for most requirement types if available
        if (unlockTracker != null) {
            // Handle ironman restriction separately (account-type based, not unlock-based)
            if (req.getType() == EdgeRequirementType.IRONMAN_RESTRICTION) {
                if (isIronman && req.getIdentifier() != null) {
                    return false;
                }
                return true;
            }
            
            // Delegate to UnlockTracker for quest, item, rune, and skill checks
            return unlockTracker.isEdgeRequirementMet(req);
        }

        // Fallback to basic checks if UnlockTracker not available
        switch (req.getType()) {
            case MAGIC_LEVEL:
                return getSkillLevel(Skill.MAGIC) >= req.getValue();

            case AGILITY_LEVEL:
                return getSkillLevel(Skill.AGILITY) >= req.getValue();

            case COMBAT_LEVEL:
                return getCombatLevel() >= req.getValue();

            case SKILL:
                Skill skill = getSkillByName(req.getIdentifier());
                return skill != null && getSkillLevel(skill) >= req.getValue();

            case QUEST:
                // Cannot check without UnlockTracker - be conservative
                log.trace("Quest requirement check requires UnlockTracker: {}", req.getIdentifier());
                return false;

            case ITEM:
            case RUNES:
                // Cannot check without UnlockTracker - be conservative
                log.trace("Item/rune requirement check requires UnlockTracker");
                return false;

            case IRONMAN_RESTRICTION:
                // If player is ironman and there's a restriction, block the edge
                if (isIronman && req.getIdentifier() != null) {
                    return false;
                }
                return true;

            default:
                log.warn("Unknown requirement type: {}", req.getType());
                return false;
        }
    }

    /**
     * Reconstruct path from Dijkstra results.
     */
    private List<WebNode> reconstructPath(Map<String, String> cameFrom, String endId) {
        List<WebNode> path = new ArrayList<>();
        String currentId = endId;

        while (currentId != null) {
            WebNode node = navigationWeb.getNode(currentId);
            if (node != null) {
                path.add(node);
            }
            currentId = cameFrom.get(currentId);
        }

        Collections.reverse(path);
        return path;
    }

    /**
     * Calculate total cost of a path.
     */
    private int calculatePathCost(List<WebNode> path) {
        if (path.size() < 2) {
            return 0;
        }

        int totalCost = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            WebEdge edge = navigationWeb.getEdge(path.get(i).getId(), path.get(i + 1).getId());
            if (edge != null) {
                totalCost += edge.getCostTicks();
            }
        }
        return totalCost;
    }

    // ========================================================================
    // Player State Queries
    // ========================================================================

    /**
     * Get the player's skill level.
     */
    private int getSkillLevel(Skill skill) {
        try {
            return client.getRealSkillLevel(skill);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Get the player's combat level.
     */
    private int getCombatLevel() {
        try {
            return client.getLocalPlayer() != null ?
                    client.getLocalPlayer().getCombatLevel() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Get skill enum by name.
     */
    private Skill getSkillByName(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Skill.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if the navigation web is loaded.
     *
     * @return true if web is available
     */
    public boolean isWebLoaded() {
        return navigationWeb != null;
    }

    /**
     * Get the nearest node to a world point.
     *
     * @param point the world point
     * @return the nearest node or null
     */
    public WebNode getNearestNode(WorldPoint point) {
        return navigationWeb != null ? navigationWeb.findNearestNode(point) : null;
    }

    /**
     * Get a node by ID.
     *
     * @param nodeId the node ID
     * @return the node or null
     */
    public WebNode getNode(String nodeId) {
        return navigationWeb != null ? navigationWeb.getNode(nodeId) : null;
    }

    /**
     * Estimate travel time between two nodes.
     *
     * @param startId start node ID
     * @param endId   end node ID
     * @return estimated ticks, or -1 if no path
     */
    public int estimateTravelTime(String startId, String endId) {
        List<WebNode> path = findPath(startId, endId);
        if (path.isEmpty()) {
            return -1;
        }
        return calculatePathCost(path);
    }

    /**
     * Get all available bank nodes.
     *
     * @return list of bank nodes
     */
    public List<WebNode> getBanks() {
        return navigationWeb != null ? navigationWeb.getBanks() : Collections.emptyList();
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    /**
     * Node for legacy Dijkstra's algorithm.
     */
    private static class DijkstraNode {
        final String nodeId;
        final int cost;

        DijkstraNode(String nodeId, int cost) {
            this.nodeId = nodeId;
            this.cost = cost;
        }
    }

    /**
     * Node for unified Dijkstra's algorithm.
     */
    private static class UnifiedDijkstraNode {
        final String nodeId;
        final int cost;
        @Nullable
        final NavigationEdge incomingEdge;

        UnifiedDijkstraNode(String nodeId, int cost, @Nullable NavigationEdge incomingEdge) {
            this.nodeId = nodeId;
            this.cost = cost;
            this.incomingEdge = incomingEdge;
        }
    }

}

