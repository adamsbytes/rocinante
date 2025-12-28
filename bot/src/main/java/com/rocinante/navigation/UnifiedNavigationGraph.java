package com.rocinante.navigation;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Unified navigation graph that merges web.json data with plane transitions.
 * 
 * <p>This service provides a single, unified view of all traversable connections:
 * <ul>
 *   <li>Walking paths from web.json</li>
 *   <li>Plane transitions (stairs, ladders, trapdoors) from PlaneTransitionHandler</li>
 *   <li>Agility shortcuts from web.json edge definitions</li>
 *   <li>Toll gates from web.json edge definitions</li>
 *   <li>Teleports and transports from web.json</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>Cross-plane pathfinding - finds paths that require climbing stairs</li>
 *   <li>Requirement filtering - edges filtered by player skills, quests, items</li>
 *   <li>Risk-aware routing - avoids shortcuts with high failure rates</li>
 *   <li>Dynamic cost adjustment based on player state</li>
 * </ul>
 */
@Slf4j
@Singleton
public class UnifiedNavigationGraph {

    /**
     * The underlying web data.
     */
    @Getter
    private final NavigationWeb webData;

    /**
     * Handler for plane transitions.
     */
    private final PlaneTransitionHandler planeTransitionHandler;

    /**
     * All unified edges indexed by source node ID.
     */
    private final Map<String, List<NavigationEdge>> edgesBySource = new HashMap<>();

    /**
     * All unified edges indexed by destination node ID.
     */
    private final Map<String, List<NavigationEdge>> edgesByDestination = new HashMap<>();

    /**
     * All unified edges.
     */
    @Getter
    private final List<NavigationEdge> allEdges = new ArrayList<>();

    /**
     * Dynamic nodes created for plane transitions (not in web.json).
     */
    private final Map<String, WebNode> dynamicNodes = new HashMap<>();

    /**
     * Combined node lookup (web.json + dynamic).
     */
    private final Map<String, WebNode> allNodes = new HashMap<>();

    @Inject
    public UnifiedNavigationGraph(NavigationWeb webData, PlaneTransitionHandler planeTransitionHandler) {
        this.webData = webData;
        this.planeTransitionHandler = planeTransitionHandler;
        buildUnifiedGraph();
    }

    /**
     * Constructor for testing without injection.
     */
    public UnifiedNavigationGraph(NavigationWeb webData) {
        this.webData = webData;
        this.planeTransitionHandler = null;
        buildUnifiedGraph();
    }

    // ========================================================================
    // Graph Building
    // ========================================================================

    /**
     * Build the unified navigation graph.
     */
    private void buildUnifiedGraph() {
        // Copy all web nodes to combined index
        allNodes.putAll(webData.getNodes());

        // Convert all web edges to unified edges
        for (WebEdge webEdge : webData.getEdges()) {
            NavigationEdge edge = convertWebEdge(webEdge);
            if (edge != null) {
                addEdge(edge);
            }
        }

        // Add plane transition edges
        if (planeTransitionHandler != null) {
            addPlaneTransitionEdges();
        }

        log.info("UnifiedNavigationGraph built: {} nodes, {} edges",
                allNodes.size(), allEdges.size());
    }

    /**
     * Convert a WebEdge to a NavigationEdge.
     */
    @Nullable
    private NavigationEdge convertWebEdge(WebEdge webEdge) {
        WebNode fromNode = webData.getNode(webEdge.getFrom());
        WebNode toNode = webData.getNode(webEdge.getTo());

        if (fromNode == null || toNode == null) {
            log.warn("WebEdge references unknown node: {} -> {}",
                    webEdge.getFrom(), webEdge.getTo());
            return null;
        }

        WorldPoint fromLoc = fromNode.getWorldPoint();
        WorldPoint toLoc = toNode.getWorldPoint();

        return NavigationEdge.fromWebEdge(webEdge, fromLoc, toLoc);
    }

    /**
     * Add plane transition edges derived from PlaneTransitionHandler.
     */
    private void addPlaneTransitionEdges() {
        // For each web node, check if there are plane transitions at that location
        // that could connect to other nodes on different planes
        for (WebNode node : webData.getNodes().values()) {
            // Find other nodes at the same XY but different plane
            List<WebNode> otherPlaneNodes = findNodesAtXY(node.getX(), node.getY())
                    .stream()
                    .filter(n -> n.getPlane() != node.getPlane())
                    .collect(Collectors.toList());

            // If there are nodes at different planes, add transition edges
            for (WebNode otherNode : otherPlaneNodes) {
                int planeDiff = otherNode.getPlane() - node.getPlane();
                
                // Create edge for going up or down
                String action = planeDiff > 0 ? "Climb-up" : "Climb-down";
                int objectId = -1; // Will be determined at runtime

                // Check metadata for specific object ID
                String metaObjectId = node.getMetadata("stair_object_id");
                if (metaObjectId != null) {
                    try {
                        objectId = Integer.parseInt(metaObjectId);
                    } catch (NumberFormatException ignored) {}
                }

                NavigationEdge transitionEdge = NavigationEdge.builder()
                        .fromNodeId(node.getId())
                        .toNodeId(otherNode.getId())
                        .type(WebEdgeType.STAIRS)
                        .costTicks(5) // Base cost for stairs
                        .bidirectional(false)
                        .fromPlane(node.getPlane())
                        .toPlane(otherNode.getPlane())
                        .objectId(objectId)
                        .action(action)
                        .fromLocation(node.getWorldPoint())
                        .toLocation(otherNode.getWorldPoint())
                        .build();

                addEdge(transitionEdge);
            }
        }

        // Add transitions registered in PlaneTransitionHandler that don't overlap
        // with existing web.json edges
        addExplicitTransitions();
    }

    /**
     * Add explicit plane transitions from PlaneTransitionHandler registry.
     */
    private void addExplicitTransitions() {
        for (PlaneTransitionHandler.PlaneTransition transition : planeTransitionHandler.getAllTransitions()) {
            // Skip bidirectional entries (they're handled by specific up/down variants)
            if (transition.getDirection() == PlaneTransitionHandler.TransitionDirection.BIDIRECTIONAL) {
                continue;
            }

            // Check if this transition's destination is known
            if (transition.getDestination() == null) {
                continue;
            }

            // Create dynamic nodes if needed
            WorldPoint sourcePoint = transition.getDestination();
            int sourcePlane = sourcePoint.getPlane();
            int destPlane = sourcePlane + transition.getPlaneChange();

            String sourceNodeId = getDynamicNodeId(sourcePoint);
            String destNodeId = getDynamicNodeId(
                    new WorldPoint(sourcePoint.getX(), sourcePoint.getY(), destPlane));

            // Ensure dynamic nodes exist
            ensureDynamicNode(sourceNodeId, sourcePoint);
            ensureDynamicNode(destNodeId, 
                    new WorldPoint(sourcePoint.getX(), sourcePoint.getY(), destPlane));

            NavigationEdge edge = NavigationEdge.builder()
                    .fromNodeId(sourceNodeId)
                    .toNodeId(destNodeId)
                    .type(WebEdgeType.STAIRS)
                    .costTicks(5)
                    .bidirectional(false)
                    .fromPlane(sourcePlane)
                    .toPlane(destPlane)
                    .objectId(transition.getObjectId())
                    .action(transition.getAction())
                    .fromLocation(sourcePoint)
                    .toLocation(new WorldPoint(sourcePoint.getX(), sourcePoint.getY(), destPlane))
                    .build();

            addEdge(edge);
        }
    }

    /**
     * Generate a dynamic node ID for a world point.
     */
    private String getDynamicNodeId(WorldPoint point) {
        return String.format("dyn_%d_%d_%d", point.getX(), point.getY(), point.getPlane());
    }

    /**
     * Ensure a dynamic node exists for a world point.
     */
    private void ensureDynamicNode(String nodeId, WorldPoint point) {
        if (!allNodes.containsKey(nodeId)) {
            WebNode node = WebNode.builder()
                    .id(nodeId)
                    .name("Dynamic Node")
                    .x(point.getX())
                    .y(point.getY())
                    .plane(point.getPlane())
                    .type(WebNodeType.GENERIC)
                    .tags(List.of("dynamic"))
                    .build();
            dynamicNodes.put(nodeId, node);
            allNodes.put(nodeId, node);
        }
    }

    /**
     * Add an edge to the graph.
     */
    private void addEdge(NavigationEdge edge) {
        allEdges.add(edge);
        edgesBySource.computeIfAbsent(edge.getFromNodeId(), k -> new ArrayList<>()).add(edge);
        edgesByDestination.computeIfAbsent(edge.getToNodeId(), k -> new ArrayList<>()).add(edge);
    }

    // ========================================================================
    // Node Queries
    // ========================================================================

    /**
     * Get a node by ID.
     *
     * @param nodeId the node ID
     * @return the node, or null if not found
     */
    @Nullable
    public WebNode getNode(String nodeId) {
        return allNodes.get(nodeId);
    }

    /**
     * Check if a node exists.
     *
     * @param nodeId the node ID
     * @return true if node exists
     */
    public boolean hasNode(String nodeId) {
        return allNodes.containsKey(nodeId);
    }

    /**
     * Find the nearest node to a point, ignoring plane.
     * 
     * <p>This allows finding entry points for multi-plane pathfinding.
     *
     * @param point the world point
     * @return the nearest node, or null if no nodes exist
     */
    @Nullable
    public WebNode findNearestNodeAnyPlane(WorldPoint point) {
        return allNodes.values().stream()
                .min(Comparator.comparingInt(n -> {
                    // Calculate 2D distance (ignore plane)
                    int dx = Math.abs(n.getX() - point.getX());
                    int dy = Math.abs(n.getY() - point.getY());
                    return Math.max(dx, dy);
                }))
                .orElse(null);
    }

    /**
     * Find the nearest node to a point on the same plane.
     *
     * @param point the world point
     * @return the nearest node on the same plane, or null if none found
     */
    @Nullable
    public WebNode findNearestNodeSamePlane(WorldPoint point) {
        return allNodes.values().stream()
                .filter(n -> n.getPlane() == point.getPlane())
                .min(Comparator.comparingInt(n -> n.distanceTo(point)))
                .orElse(null);
    }

    /**
     * Find nodes at a specific XY coordinate (any plane).
     *
     * @param x the X coordinate
     * @param y the Y coordinate
     * @return list of nodes at that XY
     */
    public List<WebNode> findNodesAtXY(int x, int y) {
        return allNodes.values().stream()
                .filter(n -> n.getX() == x && n.getY() == y)
                .collect(Collectors.toList());
    }

    /**
     * Find nodes within a distance of a point (2D distance, ignores plane).
     *
     * @param point       the center point
     * @param maxDistance maximum distance in tiles
     * @return list of nodes within distance
     */
    public List<WebNode> findNodesWithinDistance2D(WorldPoint point, int maxDistance) {
        return allNodes.values().stream()
                .filter(n -> {
                    int dx = Math.abs(n.getX() - point.getX());
                    int dy = Math.abs(n.getY() - point.getY());
                    return Math.max(dx, dy) <= maxDistance;
                })
                .sorted(Comparator.comparingInt(n -> {
                    int dx = Math.abs(n.getX() - point.getX());
                    int dy = Math.abs(n.getY() - point.getY());
                    return Math.max(dx, dy);
                }))
                .collect(Collectors.toList());
    }

    /**
     * Find nodes within a distance of a point (3D distance, same plane only).
     *
     * @param point       the center point
     * @param maxDistance maximum distance in tiles
     * @return list of nodes within distance on the same plane
     */
    public List<WebNode> findNodesWithinDistance(WorldPoint point, int maxDistance) {
        return allNodes.values().stream()
                .filter(n -> n.getPlane() == point.getPlane())
                .filter(n -> n.distanceTo(point) <= maxDistance)
                .sorted(Comparator.comparingInt(n -> n.distanceTo(point)))
                .collect(Collectors.toList());
    }

    // ========================================================================
    // Edge Queries
    // ========================================================================

    /**
     * Get all edges from a source node.
     *
     * @param sourceNodeId the source node ID
     * @return list of edges from this node (empty if none)
     */
    public List<NavigationEdge> getEdgesFrom(String sourceNodeId) {
        return edgesBySource.getOrDefault(sourceNodeId, Collections.emptyList());
    }

    /**
     * Get all edges to a destination node.
     *
     * @param destNodeId the destination node ID
     * @return list of edges to this node (empty if none)
     */
    public List<NavigationEdge> getEdgesTo(String destNodeId) {
        return edgesByDestination.getOrDefault(destNodeId, Collections.emptyList());
    }

    /**
     * Get traversable edges from a node based on player requirements.
     *
     * @param sourceNodeId       the source node ID
     * @param playerRequirements the player's current requirements state
     * @return list of traversable edges
     */
    public List<NavigationEdge> getTraversableEdges(String sourceNodeId, 
                                                      PlayerRequirements playerRequirements) {
        return getEdgesFrom(sourceNodeId).stream()
                .filter(edge -> playerRequirements.canTraverseEdge(edge))
                .collect(Collectors.toList());
    }

    /**
     * Get edges of a specific type.
     *
     * @param type the edge type
     * @return list of edges of that type
     */
    public List<NavigationEdge> getEdgesByType(WebEdgeType type) {
        return allEdges.stream()
                .filter(e -> e.getType() == type)
                .collect(Collectors.toList());
    }

    /**
     * Get direct edge between two nodes.
     *
     * @param fromId source node ID
     * @param toId   destination node ID
     * @return the edge, or null if no direct connection
     */
    @Nullable
    public NavigationEdge getEdge(String fromId, String toId) {
        return getEdgesFrom(fromId).stream()
                .filter(e -> e.getToNodeId().equals(toId))
                .findFirst()
                .orElse(null);
    }

    // ========================================================================
    // Plane Transition Queries
    // ========================================================================

    /**
     * Find edges that connect to a different plane.
     *
     * @param sourceNodeId the source node ID
     * @return list of plane-changing edges from this node
     */
    public List<NavigationEdge> getPlaneTransitionEdges(String sourceNodeId) {
        return getEdgesFrom(sourceNodeId).stream()
                .filter(NavigationEdge::isPlaneTransition)
                .collect(Collectors.toList());
    }

    /**
     * Find an edge that transitions to a specific plane.
     *
     * @param sourceNodeId the source node ID
     * @param targetPlane  the target plane
     * @return an edge that goes to the target plane, or null
     */
    @Nullable
    public NavigationEdge findTransitionToPlane(String sourceNodeId, int targetPlane) {
        return getEdgesFrom(sourceNodeId).stream()
                .filter(e -> e.isPlaneTransition() && e.getToPlane() == targetPlane)
                .findFirst()
                .orElse(null);
    }

    /**
     * Check if a path exists between two nodes (considering plane transitions).
     *
     * @param fromNodeId         source node ID
     * @param toNodeId           destination node ID
     * @param playerRequirements player requirements for filtering
     * @return true if a path might exist
     */
    public boolean hasPath(String fromNodeId, String toNodeId, PlayerRequirements playerRequirements) {
        // Simple connectivity check using BFS
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(fromNodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(toNodeId)) {
                return true;
            }

            if (visited.contains(current)) {
                continue;
            }
            visited.add(current);

            for (NavigationEdge edge : getTraversableEdges(current, playerRequirements)) {
                if (!visited.contains(edge.getToNodeId())) {
                    queue.add(edge.getToNodeId());
                }
            }
        }

        return false;
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    /**
     * Get the total number of nodes.
     */
    public int getNodeCount() {
        return allNodes.size();
    }

    /**
     * Get the number of web.json nodes.
     */
    public int getWebNodeCount() {
        return webData.getNodes().size();
    }

    /**
     * Get the number of dynamic nodes.
     */
    public int getDynamicNodeCount() {
        return dynamicNodes.size();
    }

    /**
     * Get the total number of edges.
     */
    public int getEdgeCount() {
        return allEdges.size();
    }

    /**
     * Get edge count by type.
     */
    public Map<WebEdgeType, Long> getEdgeCountByType() {
        return allEdges.stream()
                .collect(Collectors.groupingBy(NavigationEdge::getType, Collectors.counting()));
    }
}

