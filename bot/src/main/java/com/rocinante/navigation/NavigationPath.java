package com.rocinante.navigation;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Result of pathfinding that includes edges with execution information.
 * 
 * <p>Unlike a simple list of nodes, NavigationPath includes:
 * <ul>
 *   <li>The edges to traverse, with their types and requirements</li>
 *   <li>Total cost estimation</li>
 *   <li>Flags for special traversals (plane changes, shortcuts, etc.)</li>
 *   <li>Methods for querying path properties</li>
 * </ul>
 *
 * <p>This allows WalkToTask to execute each edge appropriately based on its type.
 */
@Value
@Builder
public class NavigationPath {

    /**
     * The sequence of edges to traverse.
     */
    @Builder.Default
    List<NavigationEdge> edges = Collections.emptyList();

    /**
     * The sequence of node IDs in the path.
     */
    @Builder.Default
    List<String> nodeIds = Collections.emptyList();

    /**
     * Total estimated cost in game ticks.
     */
    int totalCostTicks;

    /**
     * Starting world point.
     */
    @Nullable
    WorldPoint startPoint;

    /**
     * Ending world point.
     */
    @Nullable
    WorldPoint endPoint;

    // ========================================================================
    // Path Properties
    // ========================================================================

    /**
     * Check if the path is empty (no path found).
     */
    public boolean isEmpty() {
        return edges == null || edges.isEmpty();
    }

    /**
     * Get the number of edges in the path.
     */
    public int size() {
        return edges != null ? edges.size() : 0;
    }

    /**
     * Get the starting node ID.
     *
     * @return the first node ID, or null if path is empty
     */
    @Nullable
    public String getStartNodeId() {
        if (isEmpty()) {
            return null;
        }
        return edges.get(0).getFromNodeId();
    }

    /**
     * Get the ending node ID.
     *
     * @return the last node ID, or null if path is empty
     */
    @Nullable
    public String getEndNodeId() {
        if (isEmpty()) {
            return null;
        }
        return edges.get(edges.size() - 1).getToNodeId();
    }

    /**
     * Check if the path requires any plane changes.
     */
    public boolean requiresPlaneChange() {
        if (edges == null) {
            return false;
        }
        return edges.stream().anyMatch(NavigationEdge::isPlaneTransition);
    }

    /**
     * Check if the path includes agility shortcuts.
     */
    public boolean requiresShortcuts() {
        if (edges == null) {
            return false;
        }
        return edges.stream().anyMatch(NavigationEdge::isAgilityShortcut);
    }

    /**
     * Check if the path includes toll gates.
     */
    public boolean requiresTollGates() {
        if (edges == null) {
            return false;
        }
        return edges.stream().anyMatch(NavigationEdge::isTollGate);
    }

    /**
     * Check if the path includes teleports.
     */
    public boolean requiresTeleport() {
        if (edges == null) {
            return false;
        }
        return edges.stream().anyMatch(e -> e.getType() == WebEdgeType.TELEPORT);
    }

    /**
     * Check if the path includes any object interactions.
     */
    public boolean requiresInteractions() {
        if (edges == null) {
            return false;
        }
        return edges.stream().anyMatch(NavigationEdge::requiresInteraction);
    }

    /**
     * Get the minimum agility level required for this path.
     */
    public int getRequiredAgilityLevel() {
        if (edges == null) {
            return 0;
        }
        return edges.stream()
                .mapToInt(NavigationEdge::getRequiredAgilityLevel)
                .max()
                .orElse(0);
    }

    /**
     * Get all edge types in this path.
     */
    public Set<WebEdgeType> getEdgeTypes() {
        if (edges == null) {
            return Collections.emptySet();
        }
        return edges.stream()
                .map(NavigationEdge::getType)
                .collect(Collectors.toSet());
    }

    /**
     * Get the first edge.
     */
    @Nullable
    public NavigationEdge getFirstEdge() {
        return edges != null && !edges.isEmpty() ? edges.get(0) : null;
    }

    /**
     * Get the last edge.
     */
    @Nullable
    public NavigationEdge getLastEdge() {
        return edges != null && !edges.isEmpty() ? edges.get(edges.size() - 1) : null;
    }

    /**
     * Get edge at index.
     */
    @Nullable
    public NavigationEdge getEdge(int index) {
        if (edges == null || index < 0 || index >= edges.size()) {
            return null;
        }
        return edges.get(index);
    }

    /**
     * Get remaining path from a specific edge index.
     */
    public NavigationPath subPath(int fromIndex) {
        if (edges == null || fromIndex >= edges.size()) {
            return empty();
        }
        
        List<NavigationEdge> subEdges = edges.subList(fromIndex, edges.size());
        List<String> subNodeIds = nodeIds.subList(fromIndex, nodeIds.size());
        
        int subCost = subEdges.stream()
                .mapToInt(NavigationEdge::getCostTicks)
                .sum();

        WorldPoint subStart = fromIndex < edges.size() ? 
                edges.get(fromIndex).getFromLocation() : null;

        return NavigationPath.builder()
                .edges(subEdges)
                .nodeIds(subNodeIds)
                .totalCostTicks(subCost)
                .startPoint(subStart)
                .endPoint(endPoint)
                .build();
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create an empty path (no path found).
     */
    public static NavigationPath empty() {
        return NavigationPath.builder()
                .edges(Collections.emptyList())
                .nodeIds(Collections.emptyList())
                .totalCostTicks(0)
                .build();
    }

    /**
     * Create a simple walk path with a single edge.
     */
    public static NavigationPath singleWalk(WorldPoint from, WorldPoint to, int costTicks) {
        NavigationEdge edge = NavigationEdge.walk("start", "end", costTicks, from, to);
        return NavigationPath.builder()
                .edges(List.of(edge))
                .nodeIds(List.of("start", "end"))
                .totalCostTicks(costTicks)
                .startPoint(from)
                .endPoint(to)
                .build();
    }

    // ========================================================================
    // String Representation
    // ========================================================================

    @Override
    public String toString() {
        if (isEmpty()) {
            return "NavigationPath[EMPTY]";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("NavigationPath[");
        sb.append(edges.size()).append(" edges, ");
        sb.append(totalCostTicks).append(" ticks");

        if (requiresPlaneChange()) {
            sb.append(", plane-change");
        }
        if (requiresShortcuts()) {
            sb.append(", shortcuts");
        }
        if (requiresTollGates()) {
            sb.append(", tolls");
        }
        if (requiresTeleport()) {
            sb.append(", teleport");
        }

        sb.append("]");
        return sb.toString();
    }

    /**
     * Get a detailed string representation for debugging.
     */
    public String toDetailedString() {
        if (isEmpty()) {
            return "NavigationPath[EMPTY]";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("NavigationPath {\n");
        sb.append("  totalCost: ").append(totalCostTicks).append(" ticks\n");
        sb.append("  edges: [\n");

        for (int i = 0; i < edges.size(); i++) {
            NavigationEdge edge = edges.get(i);
            sb.append("    ").append(i).append(": ").append(edge).append("\n");
        }

        sb.append("  ]\n}");
        return sb.toString();
    }
}

