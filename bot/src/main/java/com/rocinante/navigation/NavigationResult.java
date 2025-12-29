package com.rocinante.navigation;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive result of a navigation query.
 * 
 * <p>This provides callers with complete information about:
 * <ul>
 *   <li>Whether full navigation is possible</li>
 *   <li>Partial navigation capability (first/last mile gaps)</li>
 *   <li>Why navigation failed (if it did)</li>
 *   <li>Suggested alternatives</li>
 * </ul>
 * 
 * <p>WalkToTask uses this internally to handle all navigation scenarios
 * without requiring callers to understand the underlying complexity.
 */
@Value
@Builder
public class NavigationResult {
    
    /**
     * Overall navigation status.
     */
    Status status;
    
    /**
     * The graph-based path (may be null if completely isolated).
     */
    @Nullable
    NavigationPath graphPath;
    
    /**
     * Player's current position.
     */
    WorldPoint playerPosition;
    
    /**
     * Nearest graph node to player (null if completely isolated).
     */
    @Nullable
    WebNode nearestNodeToPlayer;
    
    /**
     * Distance from player to nearest graph node.
     * -1 if player is already at/near a node (within threshold).
     * Large values indicate first-mile problem.
     */
    int firstMileDistance;
    
    /**
     * The intended destination.
     */
    WorldPoint destination;
    
    /**
     * Nearest graph node to destination (null if destination is isolated).
     */
    @Nullable
    WebNode nearestNodeToDestination;
    
    /**
     * Distance from nearest graph node to destination.
     * -1 if destination is at/near a node (within threshold).
     * Large values indicate last-mile problem.
     */
    int lastMileDistance;
    
    /**
     * Total estimated ticks for the journey (graph portion only).
     * -1 if no graph path.
     */
    int estimatedGraphTicks;
    
    /**
     * Suggested actions to complete navigation.
     */
    @Builder.Default
    List<Suggestion> suggestions = new ArrayList<>();
    
    /**
     * Human-readable failure reason (if status is a failure).
     */
    @Nullable
    String failureReason;
    
    // ========================================================================
    // Status Enum
    // ========================================================================
    
    public enum Status {
        /**
         * Full graph coverage - player and destination both near nodes,
         * and a path exists between them.
         */
        FULL_PATH_AVAILABLE,
        
        /**
         * Player needs to walk to nearest node first (first mile),
         * then graph navigation works.
         */
        FIRST_MILE_MANUAL,
        
        /**
         * Graph gets us close, but destination requires manual walking
         * from the nearest node (last mile).
         */
        LAST_MILE_MANUAL,
        
        /**
         * Both first and last mile require manual walking,
         * but graph covers the middle portion.
         */
        BOTH_ENDS_MANUAL,
        
        /**
         * Graph nodes exist for both ends, but no path connects them.
         * This might indicate a missing transport or requirement.
         */
        NO_PATH_BETWEEN_NODES,
        
        /**
         * Player is too far from any graph node.
         * Suggests teleporting or extensive manual walking.
         */
        PLAYER_ISOLATED,
        
        /**
         * Destination is too far from any graph node.
         * Area may not be supported.
         */
        DESTINATION_ISOLATED,
        
        /**
         * Both player and destination are far from any nodes.
         * Navigation essentially impossible via graph.
         */
        COMPLETELY_ISOLATED,
        
        /**
         * Navigation system not initialized.
         */
        SYSTEM_NOT_AVAILABLE
    }
    
    // ========================================================================
    // Suggestion Enum
    // ========================================================================
    
    public enum Suggestion {
        /**
         * Walk directly toward destination (short distance, no obstacles expected).
         */
        SIMPLE_WALK_TO_DESTINATION,
        
        /**
         * Walk to the nearest graph node first.
         */
        WALK_TO_NEAREST_NODE,
        
        /**
         * Walk from last graph node to destination.
         */
        WALK_FROM_LAST_NODE,
        
        /**
         * Use home teleport to get to a known area.
         */
        USE_HOME_TELEPORT,
        
        /**
         * Use a minigame teleport if available.
         */
        USE_MINIGAME_TELEPORT,
        
        /**
         * Use a specific teleport item/spell (details in failureReason).
         */
        USE_TELEPORT,
        
        /**
         * Area is not supported by navigation - manual intervention needed.
         */
        AREA_NOT_SUPPORTED,
        
        /**
         * Player may need to complete a quest/unlock to access path.
         */
        UNLOCK_REQUIRED,
        
        /**
         * Retry later - temporary issue (e.g., loading).
         */
        RETRY_LATER
    }
    
    // ========================================================================
    // Convenience Methods
    // ========================================================================
    
    /**
     * Check if navigation is fully possible via graph.
     */
    public boolean isFullyNavigable() {
        return status == Status.FULL_PATH_AVAILABLE;
    }
    
    /**
     * Check if any graph path exists (even if first/last mile manual).
     */
    public boolean hasGraphPath() {
        return graphPath != null && !graphPath.isEmpty();
    }
    
    /**
     * Check if navigation is completely impossible.
     */
    public boolean isImpossible() {
        return status == Status.COMPLETELY_ISOLATED ||
               status == Status.SYSTEM_NOT_AVAILABLE;
    }
    
    /**
     * Check if first mile requires manual walking.
     */
    public boolean requiresFirstMileWalk() {
        return status == Status.FIRST_MILE_MANUAL ||
               status == Status.BOTH_ENDS_MANUAL ||
               status == Status.PLAYER_ISOLATED;
    }
    
    /**
     * Check if last mile requires manual walking.
     */
    public boolean requiresLastMileWalk() {
        return status == Status.LAST_MILE_MANUAL ||
               status == Status.BOTH_ENDS_MANUAL ||
               status == Status.DESTINATION_ISOLATED;
    }
    
    /**
     * Get total estimated distance (graph + manual portions).
     */
    public int getTotalEstimatedDistance() {
        int total = 0;
        if (firstMileDistance > 0) {
            total += firstMileDistance;
        }
        if (graphPath != null && !graphPath.isEmpty()) {
            // Rough estimate: each tick ~= 1 tile
            total += graphPath.getTotalCostTicks();
        }
        if (lastMileDistance > 0) {
            total += lastMileDistance;
        }
        return total;
    }
    
    /**
     * Check if a specific suggestion is present.
     */
    public boolean hasSuggestion(Suggestion suggestion) {
        return suggestions.contains(suggestion);
    }
    
    // ========================================================================
    // Static Factory Methods
    // ========================================================================
    
    /**
     * Create a successful full-path result.
     */
    public static NavigationResult fullPath(NavigationPath path, 
                                            WorldPoint playerPos, 
                                            WorldPoint destination) {
        return NavigationResult.builder()
                .status(Status.FULL_PATH_AVAILABLE)
                .graphPath(path)
                .playerPosition(playerPos)
                .destination(destination)
                .nearestNodeToPlayer(null) // At a node
                .nearestNodeToDestination(null) // At a node
                .firstMileDistance(-1)
                .lastMileDistance(-1)
                .estimatedGraphTicks(path.getTotalCostTicks())
                .build();
    }
    
    /**
     * Create a result indicating the system is not available.
     */
    public static NavigationResult systemUnavailable() {
        return NavigationResult.builder()
                .status(Status.SYSTEM_NOT_AVAILABLE)
                .failureReason("Navigation system not initialized")
                .suggestions(List.of(Suggestion.RETRY_LATER))
                .firstMileDistance(-1)
                .lastMileDistance(-1)
                .estimatedGraphTicks(-1)
                .build();
    }
    
    /**
     * Create a result for player isolation.
     */
    public static NavigationResult playerIsolated(WorldPoint playerPos, 
                                                   WorldPoint destination,
                                                   @Nullable WebNode nearestNode,
                                                   int distanceToNode) {
        List<Suggestion> suggestions = new ArrayList<>();
        if (distanceToNode < 100) {
            suggestions.add(Suggestion.WALK_TO_NEAREST_NODE);
        }
        suggestions.add(Suggestion.USE_HOME_TELEPORT);
        suggestions.add(Suggestion.USE_MINIGAME_TELEPORT);
        
        return NavigationResult.builder()
                .status(Status.PLAYER_ISOLATED)
                .playerPosition(playerPos)
                .destination(destination)
                .nearestNodeToPlayer(nearestNode)
                .firstMileDistance(distanceToNode)
                .lastMileDistance(-1)
                .estimatedGraphTicks(-1)
                .failureReason("Player is " + distanceToNode + " tiles from nearest navigation node")
                .suggestions(suggestions)
                .build();
    }
    
    /**
     * Create a result for destination isolation.
     */
    public static NavigationResult destinationIsolated(WorldPoint playerPos,
                                                        WorldPoint destination,
                                                        @Nullable WebNode nearestNode,
                                                        int distanceFromNode) {
        List<Suggestion> suggestions = new ArrayList<>();
        if (distanceFromNode < 50) {
            suggestions.add(Suggestion.WALK_FROM_LAST_NODE);
        } else {
            suggestions.add(Suggestion.AREA_NOT_SUPPORTED);
        }
        
        return NavigationResult.builder()
                .status(Status.DESTINATION_ISOLATED)
                .playerPosition(playerPos)
                .destination(destination)
                .nearestNodeToDestination(nearestNode)
                .firstMileDistance(-1)
                .lastMileDistance(distanceFromNode)
                .estimatedGraphTicks(-1)
                .failureReason("Destination is " + distanceFromNode + " tiles from nearest navigation node")
                .suggestions(suggestions)
                .build();
    }
}

