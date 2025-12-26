package com.rocinante.navigation;

import com.rocinante.progression.UnlockTracker;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
 *   <li>Dijkstra's algorithm on navigation web</li>
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
     * The navigation web graph.
     */
    @Getter
    private NavigationWeb navigationWeb;

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

    @Inject
    public WebWalker(Client client, @Nullable UnlockTracker unlockTracker) {
        this.client = client;
        this.unlockTracker = unlockTracker;
        loadNavigationWeb();
        
        if (unlockTracker != null) {
            log.info("WebWalker initialized with UnlockTracker integration");
        } else {
            log.info("WebWalker initialized without UnlockTracker (limited requirement checking)");
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
     * Reload the navigation web.
     */
    public void reloadNavigationWeb() {
        loadNavigationWeb();
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
    // Path Finding
    // ========================================================================

    /**
     * Find a path between two world points using the navigation web.
     *
     * @param start the starting world point
     * @param end   the destination world point
     * @return list of web nodes forming the path (empty if no path found)
     */
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
     */
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
    // Dijkstra's Algorithm
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
     * Node for Dijkstra's algorithm.
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
     * Result of a web walking path calculation.
     */
    @Getter
    public static class WebPath {
        private final List<WebNode> nodes;
        private final int totalCostTicks;
        private final boolean requiresTeleport;
        private final boolean requiresItems;

        public WebPath(List<WebNode> nodes, int totalCostTicks,
                       boolean requiresTeleport, boolean requiresItems) {
            this.nodes = Collections.unmodifiableList(nodes);
            this.totalCostTicks = totalCostTicks;
            this.requiresTeleport = requiresTeleport;
            this.requiresItems = requiresItems;
        }

        public boolean isEmpty() {
            return nodes.isEmpty();
        }

        public int size() {
            return nodes.size();
        }

        public WebNode getStart() {
            return nodes.isEmpty() ? null : nodes.get(0);
        }

        public WebNode getEnd() {
            return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1);
        }
    }
}

