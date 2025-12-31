package com.rocinante.navigation;

import lombok.Value;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Utilities for finding a reachable adjacent tile to an interactable target.
 *
 * <p>Used to ensure we never attempt to interact with an object/NPC that
 * is separated by fences, rivers, or other collision that blocks adjacency.
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
     * Find a reachable adjacent tile to the target and the path to it using A*.
     *
     * @param pathFinder pathfinder for local collision-aware routing
     * @param start      player start position
     * @param target     target world point
     * @return best adjacent path if one exists
     */
    public static Optional<AdjacentPath> findReachableAdjacent(PathFinder pathFinder,
                                                                WorldPoint start,
                                                                WorldPoint target) {
        if (pathFinder == null || start == null || target == null) {
            return Optional.empty();
        }

        if (start.getPlane() != target.getPlane()) {
            return Optional.empty();
        }

        AdjacentPath best = null;

        for (int[] delta : ADJACENT_DELTAS) {
            WorldPoint adj = new WorldPoint(target.getX() + delta[0], target.getY() + delta[1], target.getPlane());
            if (adj.getPlane() != start.getPlane()) {
                continue;
            }

            List<WorldPoint> path = pathFinder.findPath(start, adj, true);
            if (path.isEmpty()) {
                continue;
            }

            if (best == null || path.size() < best.path.size()) {
                best = new AdjacentPath(adj, new ArrayList<>(path));
            }
        }

        return Optional.ofNullable(best);
    }

    /**
     * Find a reachable adjacent tile to a multi-tile object using collision-aware reachability.
     *
     * <p>This method understands object footprint and verifies that the interaction boundary
     * is not blocked (fence/river) using {@link Reachability}.</p>
     */
    public static Optional<AdjacentPath> findReachableAdjacent(PathFinder pathFinder,
                                                                Reachability reachability,
                                                                WorldPoint start,
                                                                net.runelite.api.TileObject target) {
        Set<WorldPoint> footprint = reachability != null ? reachability.getObjectFootprint(target) : null;
        return findReachableAdjacent(pathFinder, reachability, start, target, footprint);
    }

    /**
     * Cache-aware overload using a precomputed footprint.
     */
    public static Optional<AdjacentPath> findReachableAdjacent(PathFinder pathFinder,
                                                                Reachability reachability,
                                                                WorldPoint start,
                                                                net.runelite.api.TileObject target,
                                                                Set<WorldPoint> precomputedFootprint) {
        if (pathFinder == null || reachability == null || start == null || target == null) {
            return Optional.empty();
        }

        WorldPoint targetOrigin = getObjectOrigin(target);
        if (targetOrigin == null || targetOrigin.getPlane() != start.getPlane()) {
            return Optional.empty();
        }

        Set<WorldPoint> footprint = precomputedFootprint;
        if (footprint == null || footprint.isEmpty()) {
            footprint = reachability.getObjectFootprint(target);
        }
        if (footprint == null || footprint.isEmpty()) {
            return Optional.empty();
        }

        AdjacentPath best = null;

        for (WorldPoint footprintTile : footprint) {
            for (int[] delta : ADJACENT_DELTAS) {
                WorldPoint adj = new WorldPoint(
                        footprintTile.getX() + delta[0],
                        footprintTile.getY() + delta[1],
                        footprintTile.getPlane());

                // Skip tiles inside the footprint
                if (footprint.contains(adj)) {
                    continue;
                }

                List<WorldPoint> path = pathFinder.findPath(start, adj, true);
                if (path.isEmpty()) {
                    continue;
                }

                if (!reachability.canInteract(adj, target)) {
                    log.debug("Rejected adjacent {} for target at {}: boundary blocked", adj, footprintTile);
                    continue;
                }

                if (best == null || path.size() < best.path.size()) {
                    best = new AdjacentPath(adj, new ArrayList<>(path));
                }
            }
        }

        return Optional.ofNullable(best);
    }

    private static WorldPoint getObjectOrigin(net.runelite.api.TileObject target) {
        if (target instanceof net.runelite.api.GameObject) {
            return ((net.runelite.api.GameObject) target).getWorldLocation();
        }
        if (target instanceof net.runelite.api.WallObject) {
            return ((net.runelite.api.WallObject) target).getWorldLocation();
        }
        if (target instanceof net.runelite.api.DecorativeObject) {
            return ((net.runelite.api.DecorativeObject) target).getWorldLocation();
        }
        if (target instanceof net.runelite.api.GroundObject) {
            return ((net.runelite.api.GroundObject) target).getWorldLocation();
        }
        return null;
    }

    @Value
    public static class AdjacentPath {
        WorldPoint destination;
        List<WorldPoint> path;
    }
}

