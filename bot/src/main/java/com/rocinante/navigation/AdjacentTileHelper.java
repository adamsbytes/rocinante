package com.rocinante.navigation;

import lombok.Value;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Utilities for finding a reachable adjacent tile to an interactable target.
 *
 * <p>Uses {@link CollisionService}'s global collision data to ensure we never attempt
 * to interact with objects/NPCs that are separated by fences, rivers, or other
 * blocking terrain.
 *
 * @see CollisionService
 * @see Reachability
 */
@Slf4j
@UtilityClass
public class AdjacentTileHelper {

    private static final int[][] ADJACENT_DELTAS = {
            {-1, -1}, {-1, 0}, {-1, 1},
            {0, -1},           {0, 1},
            {1, -1},  {1, 0},  {1, 1}
    };

    /**
     * Find a reachable adjacent tile to the target with optional footprint.
     *
     * <p>Uses CollisionService's collision data to validate adjacency.
     *
     * @param collision   CollisionService for collision checks
     * @param reachability optional reachability checker for boundary validation
     * @param start        player start position
     * @param target       target world point
     * @param footprint    optional set of world points representing the target's footprint
     * @return best adjacent tile if one exists
     */
    public static Optional<AdjacentPath> findReachableAdjacent(
            CollisionService collision,
            @Nullable Reachability reachability,
            WorldPoint start,
            WorldPoint target,
            @Nullable Set<WorldPoint> footprint) {

        if (collision == null || start == null || target == null) {
            return Optional.empty();
        }

        if (start.getPlane() != target.getPlane()) {
            return Optional.empty();
        }

        Set<WorldPoint> effectiveFootprint = (footprint != null && !footprint.isEmpty())
                ? footprint
                : Collections.singleton(target);

        return findBestAdjacentTile(collision, reachability, start, effectiveFootprint);
    }

    /**
     * Find a reachable adjacent tile to a simple single-tile target.
     */
    public static Optional<AdjacentPath> findReachableAdjacent(
            CollisionService collision,
            WorldPoint start,
            WorldPoint target) {
        return findReachableAdjacent(collision, null, start, target, null);
    }

    /**
     * Find a reachable adjacent tile to a TileObject.
     */
    public static Optional<AdjacentPath> findReachableAdjacent(
            CollisionService collision,
            Reachability reachability,
            WorldPoint start,
            TileObject target) {
        if (reachability == null || target == null) {
            return Optional.empty();
        }
        Set<WorldPoint> footprint = reachability.getObjectFootprint(target);
        WorldPoint origin = TileObjectUtils.getWorldPoint(target);
        if (origin == null) {
            return Optional.empty();
        }
        return findReachableAdjacent(collision, reachability, start, origin, footprint);
    }

    /**
     * Core logic - finds best adjacent tile to any tile in footprint.
     */
    private static Optional<AdjacentPath> findBestAdjacentTile(
            CollisionService collision,
            @Nullable Reachability reachability,
            WorldPoint start,
            Set<WorldPoint> footprint) {

        WorldPoint bestTile = null;
        int bestDistance = Integer.MAX_VALUE;

        for (WorldPoint footprintTile : footprint) {
            if (footprintTile.getPlane() != start.getPlane()) {
                continue;
            }

            for (int[] delta : ADJACENT_DELTAS) {
                WorldPoint adj = new WorldPoint(
                        footprintTile.getX() + delta[0],
                        footprintTile.getY() + delta[1],
                        footprintTile.getPlane());

                // Skip tiles inside the footprint
                if (footprint.contains(adj)) {
                    continue;
                }

                // Check if tile is blocked
                if (collision.isBlocked(adj)) {
                    continue;
                }

                // Check if we can move from adjacent to footprint tile (no fence/wall)
                if (!collision.canMoveTo(adj, footprintTile)) {
                    log.debug("Rejected adjacent {} for {}: boundary blocked", adj, footprintTile);
                    continue;
                }

                // Check boundary with reachability
                if (!reachability.canInteract(adj, footprintTile)) {
                    log.debug("Rejected adjacent {} for {}: reachability blocked", adj, footprintTile);
                    continue;
                }

                // Keep closest
                int dist = start.distanceTo(adj);
                if (dist < bestDistance) {
                    bestDistance = dist;
                    bestTile = adj;
                }
            }
        }

        if (bestTile != null) {
            return Optional.of(new AdjacentPath(bestTile, Collections.emptyList()));
        }
        return Optional.empty();
    }

    /**
     * Result of adjacent tile search.
     */
    @Value
    public static class AdjacentPath {
        WorldPoint destination;
        List<WorldPoint> path; // Empty - path computed separately by NavigationService
    }
}
