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
 *   <li>FREE_TELEPORT edges (home teleports, grouping teleports) available from any location</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>Cross-plane pathfinding - finds paths that require climbing stairs</li>
 *   <li>Requirement filtering - edges filtered by player skills, quests, items</li>
 *   <li>Risk-aware routing - avoids shortcuts with high failure rates</li>
 *   <li>Dynamic cost adjustment based on player state</li>
 *   <li>Virtual "any_location" node for teleports usable from anywhere</li>
 * </ul>
 * 
 * <h3>Architecture Note: "any_location" Virtual Node</h3>
 * <p>Some edges (like home teleports and grouping teleports) can be used from any location.
 * These are represented as edges from the virtual node ID "any_location". Since there's no
 * physical node for "any_location", these edges are stored separately and injected into
 * every {@link #getTraversableEdges} query. This allows Dijkstra's algorithm to consider
 * these teleports as potential paths from any node in the graph.
 */
@Slf4j
@Singleton
public class UnifiedNavigationGraph {

    /**
     * Virtual node ID representing "any location" for FREE_TELEPORT edges.
     * Edges from this node can be traversed from anywhere in the game world.
     */
    public static final String ANY_LOCATION_NODE_ID = "any_location";

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
     * Edges from the virtual "any_location" node (FREE_TELEPORT edges).
     * These edges are available from any node in the graph.
     */
    private final List<NavigationEdge> anyLocationEdges = new ArrayList<>();

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

        log.info("UnifiedNavigationGraph built: {} nodes, {} edges ({} any_location edges for FREE_TELEPORT)",
                allNodes.size(), allEdges.size(), anyLocationEdges.size());
    }

    /**
     * Convert a WebEdge to a NavigationEdge.
     * 
     * <p>Handles special cases:
     * <ul>
     *   <li>"any_location" as from node - creates edge with null fromLocation (FREE_TELEPORT)</li>
     *   <li>Unknown nodes - logs warning and returns null</li>
     * </ul>
     */
    @Nullable
    private NavigationEdge convertWebEdge(WebEdge webEdge) {
        String fromId = webEdge.getFrom();
        String toId = webEdge.getTo();
        
        // Handle "any_location" edges (FREE_TELEPORT - home teleports, grouping teleports)
        // These edges can be traversed from anywhere, so fromNode doesn't need to exist
        if (ANY_LOCATION_NODE_ID.equals(fromId)) {
            WebNode toNode = webData.getNode(toId);
            if (toNode == null) {
                log.warn("FREE_TELEPORT edge references unknown destination node: {} -> {}",
                        fromId, toId);
                return null;
            }
            WorldPoint toLoc = toNode.getWorldPoint();
            // fromLocation is null because this edge can be used from anywhere
            return NavigationEdge.fromWebEdge(webEdge, null, toLoc);
        }
        
        WebNode fromNode = webData.getNode(fromId);
        WebNode toNode = webData.getNode(toId);

        if (fromNode == null || toNode == null) {
            log.warn("WebEdge references unknown node: {} -> {} (from={}, to={})",
                    fromId, toId, fromNode != null, toNode != null);
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
            // Check if this transition's destination is known
            if (transition.getDestination() == null) {
                continue;
            }

            // For BIDIRECTIONAL transitions, create both UP and DOWN edges
            if (transition.getDirection() == PlaneTransitionHandler.TransitionDirection.BIDIRECTIONAL) {
                createBidirectionalTransitionEdges(transition);
                continue;
            }

            // Create single directional edge
            createDirectionalTransitionEdge(transition);
        }
    }

    /**
     * Create both UP and DOWN edges for a bidirectional plane transition.
     */
    private void createBidirectionalTransitionEdges(PlaneTransitionHandler.PlaneTransition transition) {
        WorldPoint basePoint = transition.getDestination();
        int basePlane = basePoint.getPlane();
        
        // For bidirectional ladders/stairs, they can typically go up or down
        // We create edges for both directions from the base plane
        
        // Create UP edge (plane -> plane + 1)
        int upperPlane = basePlane + 1;
        if (upperPlane <= 3) { // OSRS max plane is 3
            WorldPoint upperPoint = new WorldPoint(basePoint.getX(), basePoint.getY(), upperPlane);
            
            String lowerNodeId = getDynamicNodeId(basePoint);
            String upperNodeId = getDynamicNodeId(upperPoint);
            
            ensureDynamicNode(lowerNodeId, basePoint);
            ensureDynamicNode(upperNodeId, upperPoint);
            
            // Edge going UP
            NavigationEdge upEdge = NavigationEdge.builder()
                    .fromNodeId(lowerNodeId)
                    .toNodeId(upperNodeId)
                    .type(WebEdgeType.STAIRS)
                    .costTicks(5)
                    .bidirectional(false)
                    .fromPlane(basePlane)
                    .toPlane(upperPlane)
                    .objectId(transition.getObjectId())
                    .action(getUpAction(transition))
                    .fromLocation(basePoint)
                    .toLocation(upperPoint)
                    .build();
            addEdge(upEdge);
            
            // Edge going DOWN
            NavigationEdge downEdge = NavigationEdge.builder()
                    .fromNodeId(upperNodeId)
                    .toNodeId(lowerNodeId)
                    .type(WebEdgeType.STAIRS)
                    .costTicks(5)
                    .bidirectional(false)
                    .fromPlane(upperPlane)
                    .toPlane(basePlane)
                    .objectId(transition.getObjectId())
                    .action(getDownAction(transition))
                    .fromLocation(upperPoint)
                    .toLocation(basePoint)
                    .build();
            addEdge(downEdge);
        }
        
        // Also handle DOWN from base plane if possible
        int lowerPlane = basePlane - 1;
        if (lowerPlane >= 0) {
            WorldPoint lowerPoint = new WorldPoint(basePoint.getX(), basePoint.getY(), lowerPlane);
            
            String baseNodeId = getDynamicNodeId(basePoint);
            String lowerNodeId = getDynamicNodeId(lowerPoint);
            
            ensureDynamicNode(baseNodeId, basePoint);
            ensureDynamicNode(lowerNodeId, lowerPoint);
            
            // Edge going DOWN from base
            NavigationEdge downEdge = NavigationEdge.builder()
                    .fromNodeId(baseNodeId)
                    .toNodeId(lowerNodeId)
                    .type(WebEdgeType.STAIRS)
                    .costTicks(5)
                    .bidirectional(false)
                    .fromPlane(basePlane)
                    .toPlane(lowerPlane)
                    .objectId(transition.getObjectId())
                    .action(getDownAction(transition))
                    .fromLocation(basePoint)
                    .toLocation(lowerPoint)
                    .build();
            addEdge(downEdge);
            
            // Edge going UP to base
            NavigationEdge upEdge = NavigationEdge.builder()
                    .fromNodeId(lowerNodeId)
                    .toNodeId(baseNodeId)
                    .type(WebEdgeType.STAIRS)
                    .costTicks(5)
                    .bidirectional(false)
                    .fromPlane(lowerPlane)
                    .toPlane(basePlane)
                    .objectId(transition.getObjectId())
                    .action(getUpAction(transition))
                    .fromLocation(lowerPoint)
                    .toLocation(basePoint)
                    .build();
            addEdge(upEdge);
        }
    }

    /**
     * Create a single directional transition edge.
     */
    private void createDirectionalTransitionEdge(PlaneTransitionHandler.PlaneTransition transition) {
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

    /**
     * Get the "up" action for a transition, based on transition type.
     */
    private String getUpAction(PlaneTransitionHandler.PlaneTransition transition) {
        String action = transition.getAction();
        if (action != null && !action.isEmpty()) {
            // If action contains "down", replace with up variant
            if (action.toLowerCase().contains("down")) {
                return action.toLowerCase().replace("down", "up");
            }
            // If action is generic, determine by type
            if (action.equalsIgnoreCase("climb") || action.equalsIgnoreCase("use")) {
                return "Climb-up";
            }
            return action;
        }
        
        // Default based on transition type
        switch (transition.getTransitionType()) {
            case LADDER:
                return "Climb-up";
            case STAIRS:
                return "Climb-up";
            case TRAPDOOR:
                return "Climb-up";
            default:
                return "Climb-up";
        }
    }

    /**
     * Get the "down" action for a transition, based on transition type.
     */
    private String getDownAction(PlaneTransitionHandler.PlaneTransition transition) {
        String action = transition.getAction();
        if (action != null && !action.isEmpty()) {
            // If action contains "up", replace with down variant
            if (action.toLowerCase().contains("up")) {
                return action.toLowerCase().replace("up", "down");
            }
            // If action is generic, determine by type
            if (action.equalsIgnoreCase("climb") || action.equalsIgnoreCase("use")) {
                return "Climb-down";
            }
            return action;
        }
        
        // Default based on transition type
        switch (transition.getTransitionType()) {
            case LADDER:
                return "Climb-down";
            case STAIRS:
                return "Climb-down";
            case TRAPDOOR:
                return "Climb-down";
            default:
                return "Climb-down";
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
     * 
     * <p>Edges from "any_location" are stored separately in {@link #anyLocationEdges}
     * and are included in every {@link #getTraversableEdges} query.
     */
    private void addEdge(NavigationEdge edge) {
        allEdges.add(edge);
        
        // Store "any_location" edges separately - they're available from any node
        if (ANY_LOCATION_NODE_ID.equals(edge.getFromNodeId())) {
            anyLocationEdges.add(edge);
            log.debug("Added any_location edge: {} -> {} ({})", 
                    edge.getFromNodeId(), edge.getToNodeId(), edge.getType());
        } else {
        edgesBySource.computeIfAbsent(edge.getFromNodeId(), k -> new ArrayList<>()).add(edge);
        }
        
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
     * <p>This includes:
     * <ul>
     *   <li>Direct edges from the source node</li>
     *   <li>"any_location" edges (FREE_TELEPORT) that can be used from anywhere</li>
     * </ul>
     *
     * @param sourceNodeId       the source node ID
     * @param playerRequirements the player's current requirements state
     * @return list of traversable edges
     */
    public List<NavigationEdge> getTraversableEdges(String sourceNodeId, 
                                                      PlayerRequirements playerRequirements) {
        List<NavigationEdge> result = new ArrayList<>();
        
        // Add edges directly from this node
        for (NavigationEdge edge : getEdgesFrom(sourceNodeId)) {
            if (playerRequirements.canTraverseEdge(edge)) {
                result.add(edge);
            }
        }
        
        // Add "any_location" edges (FREE_TELEPORT) - these can be used from anywhere
        // Note: Don't add them if we're querying from "any_location" itself (prevents loops)
        if (!ANY_LOCATION_NODE_ID.equals(sourceNodeId)) {
            for (NavigationEdge edge : anyLocationEdges) {
                if (playerRequirements.canTraverseEdge(edge)) {
                    result.add(edge);
                }
            }
        }
        
        return result;
    }
    
    /**
     * Get all "any_location" edges (FREE_TELEPORT edges).
     * 
     * <p>These edges represent teleports that can be used from anywhere:
     * <ul>
     *   <li>Home teleport (based on player's respawn point)</li>
     *   <li>Grouping teleports (minigame teleports)</li>
     * </ul>
     *
     * @return list of all "any_location" edges
     */
    public List<NavigationEdge> getAnyLocationEdges() {
        return Collections.unmodifiableList(anyLocationEdges);
    }
    
    /**
     * Get count of "any_location" edges.
     *
     * @return number of FREE_TELEPORT edges
     */
    public int getAnyLocationEdgeCount() {
        return anyLocationEdges.size();
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

