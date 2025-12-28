package com.rocinante.navigation;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Unified edge representation for navigation pathfinding.
 * 
 * <p>This class represents any traversable connection in the navigation graph:
 * <ul>
 *   <li>Walking paths between locations</li>
 *   <li>Plane transitions (stairs, ladders, trapdoors)</li>
 *   <li>Agility shortcuts with level requirements</li>
 *   <li>Toll gates requiring payment or items</li>
 *   <li>Teleports (spells, items, transport)</li>
 *   <li>Doors and gates requiring interaction</li>
 * </ul>
 *
 * <p>All edges share a common structure with type-specific fields.
 * The unified representation allows Dijkstra's algorithm to consider
 * all traversal options simultaneously.
 */
@Value
@Builder(toBuilder = true)
public class NavigationEdge {

    // ========================================================================
    // Core Fields (all edges)
    // ========================================================================

    /**
     * Source node ID in the navigation graph.
     */
    String fromNodeId;

    /**
     * Destination node ID in the navigation graph.
     */
    String toNodeId;

    /**
     * Edge type determining execution behavior.
     */
    WebEdgeType type;

    /**
     * Estimated traversal cost in game ticks.
     * Used as weight for pathfinding.
     */
    int costTicks;

    /**
     * Whether this edge can be traversed in both directions.
     */
    boolean bidirectional;

    /**
     * Requirements to traverse this edge.
     */
    @Builder.Default
    List<EdgeRequirement> requirements = Collections.emptyList();

    /**
     * Additional metadata specific to edge type.
     */
    @Nullable
    Map<String, String> metadata;

    // ========================================================================
    // Location Fields
    // ========================================================================

    /**
     * World location of the "from" point.
     * Used for local pathfinding to the edge start.
     */
    @Nullable
    WorldPoint fromLocation;

    /**
     * World location of the "to" point.
     * Used for determining where player ends up.
     */
    @Nullable
    WorldPoint toLocation;

    // ========================================================================
    // Plane Transition Fields
    // ========================================================================

    /**
     * Source plane (for plane transitions).
     * -1 if not a plane transition.
     */
    @Builder.Default
    int fromPlane = -1;

    /**
     * Destination plane (for plane transitions).
     * -1 if not a plane transition.
     */
    @Builder.Default
    int toPlane = -1;

    // ========================================================================
    // Interaction Fields
    // ========================================================================

    /**
     * Object ID to interact with (for obstacles, shortcuts, stairs).
     * -1 if no object interaction required.
     */
    @Builder.Default
    int objectId = -1;

    /**
     * Menu action for interaction (e.g., "Open", "Climb-up", "Cross").
     */
    @Nullable
    String action;

    // ========================================================================
    // Agility Shortcut Fields
    // ========================================================================

    /**
     * Required agility level for shortcuts.
     * 0 if no agility requirement.
     */
    @Builder.Default
    int requiredAgilityLevel = 0;

    /**
     * Failure rate at the required level (0.0 to 1.0).
     * 0.0 means always succeeds.
     */
    @Builder.Default
    double failureRate = 0.0;

    // ========================================================================
    // Toll Gate Fields
    // ========================================================================

    /**
     * Gold cost to traverse (for toll gates).
     * 0 if no gold cost.
     */
    @Builder.Default
    int tollCost = 0;

    /**
     * Item ID required to traverse (e.g., Shantay Pass).
     * -1 if no item required.
     */
    @Builder.Default
    int requiredItemId = -1;

    /**
     * Quest that grants free passage through toll gate.
     * Null if no free passage quest.
     */
    @Nullable
    String freePassageQuest;

    // ========================================================================
    // Query Methods
    // ========================================================================

    /**
     * Check if this is a plane transition edge.
     */
    public boolean isPlaneTransition() {
        return type == WebEdgeType.STAIRS || 
               (fromPlane != -1 && toPlane != -1 && fromPlane != toPlane);
    }

    /**
     * Check if this is an agility shortcut.
     */
    public boolean isAgilityShortcut() {
        return type == WebEdgeType.AGILITY || requiredAgilityLevel > 0;
    }

    /**
     * Check if this is a toll gate.
     */
    public boolean isTollGate() {
        return type == WebEdgeType.TOLL || tollCost > 0 || requiredItemId > 0;
    }

    /**
     * Check if this edge requires object interaction.
     */
    public boolean requiresInteraction() {
        return objectId > 0 && action != null;
    }

    /**
     * Check if this edge has any requirements.
     */
    public boolean hasRequirements() {
        return requirements != null && !requirements.isEmpty();
    }

    /**
     * Get metadata value.
     */
    @Nullable
    public String getMetadata(String key) {
        return metadata != null ? metadata.get(key) : null;
    }

    /**
     * Get metadata value as integer.
     */
    public int getMetadataInt(String key, int defaultValue) {
        String value = getMetadata(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get metadata value as double.
     */
    public double getMetadataDouble(String key, double defaultValue) {
        String value = getMetadata(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a walking edge between two nodes.
     */
    public static NavigationEdge walk(String from, String to, int costTicks, 
                                       WorldPoint fromLoc, WorldPoint toLoc) {
        return NavigationEdge.builder()
                .fromNodeId(from)
                .toNodeId(to)
                .type(WebEdgeType.WALK)
                .costTicks(costTicks)
                .bidirectional(true)
                .fromLocation(fromLoc)
                .toLocation(toLoc)
                .build();
    }

    /**
     * Create a stairs/ladder edge for plane transitions.
     */
    public static NavigationEdge stairs(String from, String to, int costTicks,
                                         int fromPlane, int toPlane,
                                         int objectId, String action,
                                         WorldPoint location) {
        return NavigationEdge.builder()
                .fromNodeId(from)
                .toNodeId(to)
                .type(WebEdgeType.STAIRS)
                .costTicks(costTicks)
                .bidirectional(false) // Stairs usually have separate up/down objects
                .fromPlane(fromPlane)
                .toPlane(toPlane)
                .objectId(objectId)
                .action(action)
                .fromLocation(location)
                .toLocation(location) // Same XY, different plane
                .build();
    }

    /**
     * Create an agility shortcut edge.
     */
    public static NavigationEdge agilityShortcut(String from, String to, int costTicks,
                                                  int requiredLevel, double failureRate,
                                                  int objectId, String action,
                                                  WorldPoint fromLoc, WorldPoint toLoc) {
        return NavigationEdge.builder()
                .fromNodeId(from)
                .toNodeId(to)
                .type(WebEdgeType.AGILITY)
                .costTicks(costTicks)
                .bidirectional(false) // Most shortcuts are one-way
                .requiredAgilityLevel(requiredLevel)
                .failureRate(failureRate)
                .objectId(objectId)
                .action(action)
                .fromLocation(fromLoc)
                .toLocation(toLoc)
                .requirements(List.of(EdgeRequirement.agilityLevel(requiredLevel)))
                .build();
    }

    /**
     * Create a toll gate edge.
     */
    public static NavigationEdge tollGate(String from, String to, int costTicks,
                                           int tollCost, int objectId, String action,
                                           @Nullable String freePassageQuest,
                                           WorldPoint location) {
        List<EdgeRequirement> reqs = List.of(
                EdgeRequirement.item(995, tollCost, true) // Gold consumed
        );
        
        return NavigationEdge.builder()
                .fromNodeId(from)
                .toNodeId(to)
                .type(WebEdgeType.TOLL)
                .costTicks(costTicks)
                .bidirectional(true)
                .tollCost(tollCost)
                .objectId(objectId)
                .action(action)
                .freePassageQuest(freePassageQuest)
                .fromLocation(location)
                .toLocation(location)
                .requirements(reqs)
                .build();
    }

    /**
     * Create an edge from a WebEdge (from web.json).
     */
    public static NavigationEdge fromWebEdge(WebEdge webEdge, 
                                              @Nullable WorldPoint fromLoc,
                                              @Nullable WorldPoint toLoc) {
        NavigationEdge.NavigationEdgeBuilder builder = NavigationEdge.builder()
                .fromNodeId(webEdge.getFrom())
                .toNodeId(webEdge.getTo())
                .type(webEdge.getType())
                .costTicks(webEdge.getCostTicks())
                .bidirectional(webEdge.isBidirectional())
                .requirements(webEdge.getRequirements())
                .metadata(webEdge.getMetadata())
                .fromLocation(fromLoc)
                .toLocation(toLoc);

        // Extract type-specific fields from metadata
        if (webEdge.getMetadata() != null) {
            Map<String, String> meta = webEdge.getMetadata();
            
            // Plane transitions
            if (meta.containsKey("from_plane")) {
                builder.fromPlane(Integer.parseInt(meta.get("from_plane")));
            }
            if (meta.containsKey("to_plane")) {
                builder.toPlane(Integer.parseInt(meta.get("to_plane")));
            }
            
            // Object interaction
            if (meta.containsKey("object_id")) {
                builder.objectId(Integer.parseInt(meta.get("object_id")));
            }
            if (meta.containsKey("action")) {
                builder.action(meta.get("action"));
            }
            
            // Agility shortcuts
            if (meta.containsKey("failure_rate")) {
                builder.failureRate(Double.parseDouble(meta.get("failure_rate")));
            }
            
            // Toll gates
            if (meta.containsKey("toll_cost")) {
                builder.tollCost(Integer.parseInt(meta.get("toll_cost")));
            }
            if (meta.containsKey("required_item_id")) {
                builder.requiredItemId(Integer.parseInt(meta.get("required_item_id")));
            }
            if (meta.containsKey("free_passage_quest")) {
                builder.freePassageQuest(meta.get("free_passage_quest"));
            }
        }

        // Extract agility level from requirements
        builder.requiredAgilityLevel(webEdge.getRequiredAgilityLevel());

        return builder.build();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Edge[").append(fromNodeId).append(" -> ").append(toNodeId);
        sb.append(", ").append(type);
        sb.append(", ").append(costTicks).append(" ticks");
        
        if (isPlaneTransition()) {
            sb.append(", plane ").append(fromPlane).append("->").append(toPlane);
        }
        if (isAgilityShortcut()) {
            sb.append(", agility ").append(requiredAgilityLevel);
        }
        if (isTollGate()) {
            sb.append(", toll ").append(tollCost).append("gp");
        }
        
        sb.append("]");
        return sb.toString();
    }
}

