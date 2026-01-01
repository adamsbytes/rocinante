package com.rocinante.tasks.impl.skills.firemaking;

import com.rocinante.navigation.NavigationService;
import com.rocinante.state.GameObjectSnapshot;
import com.rocinante.state.WorldState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.util.ObjectCollections;
import lombok.Value;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Utility class for finding optimal burn locations in dynamic firemaking mode.
 *
 * <p>Used when firemaking from the current position (cut-and-burn workflows)
 * rather than a fixed starting location. Considers:
 * <ul>
 *   <li>Tile walkability (not blocked by collision)</li>
 *   <li>Existing fire objects (cannot light fire on tile with fire)</li>
 *   <li>Path cost to reach candidate tiles</li>
 *   <li>Preference for continuing the same line (same Y coordinate)</li>
 * </ul>
 *
 * @see FiremakingSkillTask
 * @see FiremakingConfig
 */
@Slf4j
@UtilityClass
public class BurnLocationFinder {

    /**
     * Set of fire object IDs from ObjectCollections for quick lookup.
     */
    private static final Set<Integer> FIRE_OBJECT_IDS = new HashSet<>(ObjectCollections.COOKING_FIRES);

    /**
     * Result of a burn location search.
     */
    @Value
    public static class BurnLocation {
        /**
         * The world position suitable for burning.
         */
        WorldPoint position;

        /**
         * Path cost (in tiles) to reach this position.
         */
        int pathCost;

        /**
         * Whether this position is on the same Y coordinate as the starting position.
         * Same-line positions are preferred to continue an existing fire line.
         */
        boolean onSameLine;

        /**
         * Number of consecutive clear tiles available in the burn direction (west).
         * This determines how many logs can be burned before repositioning.
         */
        int lineLength;
    }

    /**
     * Find the optimal burn location near the current position.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Scan tiles in expanding rings around current position</li>
     *   <li>Filter out blocked tiles and tiles with existing fires</li>
     *   <li>Calculate path cost for reachable candidates</li>
     *   <li>Calculate available line length (consecutive clear tiles west)</li>
     *   <li>Rank by: line length (prefer enough for logs), then path cost</li>
     * </ol>
     *
     * @param ctx             TaskContext for navigation and world state
     * @param currentPosition current player position
     * @param searchRadius    tiles to search around current position
     * @param walkThreshold   maximum acceptable path cost
     * @return optimal burn location, or empty if no suitable spot found
     */
    public static Optional<BurnLocation> findOptimalBurnLocation(
            TaskContext ctx,
            WorldPoint currentPosition,
            int searchRadius,
            int walkThreshold) {
        // Default: try to find room for at least 10 logs
        return findOptimalBurnLocation(ctx, currentPosition, searchRadius, walkThreshold, 10);
    }

    /**
     * Find the optimal burn location with enough room for a specific number of logs.
     *
     * @param ctx             TaskContext for navigation and world state
     * @param currentPosition current player position
     * @param searchRadius    tiles to search around current position
     * @param walkThreshold   maximum acceptable path cost
     * @param desiredLogs     number of logs we want to burn (determines minimum line length)
     * @return optimal burn location, or empty if no suitable spot found
     */
    public static Optional<BurnLocation> findOptimalBurnLocation(
            TaskContext ctx,
            WorldPoint currentPosition,
            int searchRadius,
            int walkThreshold,
            int desiredLogs) {

        if (ctx == null || currentPosition == null) {
            return Optional.empty();
        }

        NavigationService navService = ctx.getNavigationService();
        if (navService == null) {
            log.warn("NavigationService not available for burn location search");
            return Optional.empty();
        }

        WorldState worldState = ctx.getWorldState();
        Set<WorldPoint> fireTiles = getFireTilePositions(worldState);

        List<BurnLocation> candidates = new ArrayList<>();

        // Scan in expanding rings for efficiency (closer tiles first)
        for (int radius = 0; radius <= searchRadius; radius++) {
            scanRing(ctx, currentPosition, radius, walkThreshold, fireTiles, navService, candidates);
        }

        if (candidates.isEmpty()) {
            log.debug("No valid burn locations found within {} tiles (threshold: {})",
                    searchRadius, walkThreshold);
            return Optional.empty();
        }

        // Sort candidates: prefer positions with enough line length, then by path cost
        // A position with lineLength >= desiredLogs is "good enough" - don't walk further for more
        final int targetLength = desiredLogs;
        candidates.sort(Comparator
                // First: prefer positions with enough room for all logs
                .comparing((BurnLocation loc) -> loc.getLineLength() >= targetLength).reversed()
                // Then: prefer same-line (continue existing fire line)
                .thenComparing(BurnLocation::isOnSameLine).reversed()
                // Then: for "good enough" positions, pick closest (lowest path cost)
                // For "not enough" positions, pick longest line
                .thenComparingInt(loc -> {
                    if (loc.getLineLength() >= targetLength) {
                        return loc.getPathCost(); // Minimize walk distance
                    } else {
                        return targetLength - loc.getLineLength(); // Maximize line length
                    }
                }));

        BurnLocation best = candidates.get(0);
        log.debug("Found {} burn location candidates, best: {} (cost: {}, lineLength: {}, sameLine: {})",
                candidates.size(), best.getPosition(), best.getPathCost(), best.getLineLength(), best.isOnSameLine());

        return Optional.of(best);
    }

    /**
     * Scan a ring of tiles at a specific distance from the center.
     */
    private static void scanRing(
            TaskContext ctx,
            WorldPoint center,
            int radius,
            int walkThreshold,
            Set<WorldPoint> fireTiles,
            NavigationService navService,
            List<BurnLocation> candidates) {

        int plane = center.getPlane();

        if (radius == 0) {
            // Check center tile itself
            evaluateCandidate(ctx, center, center, walkThreshold, fireTiles, navService, candidates);
            return;
        }

        // Scan the perimeter of the ring
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                // Only process tiles on the ring perimeter
                if (Math.abs(dx) != radius && Math.abs(dy) != radius) {
                    continue;
                }

                WorldPoint candidate = new WorldPoint(
                        center.getX() + dx,
                        center.getY() + dy,
                        plane
                );

                evaluateCandidate(ctx, center, candidate, walkThreshold, fireTiles, navService, candidates);
            }
        }
    }

    /**
     * Evaluate a single candidate tile for suitability as a burn location.
     */
    private static void evaluateCandidate(
            TaskContext ctx,
            WorldPoint origin,
            WorldPoint candidate,
            int walkThreshold,
            Set<WorldPoint> fireTiles,
            NavigationService navService,
            List<BurnLocation> candidates) {

        // Skip if tile is blocked (wall, water, etc.)
        if (navService.isBlocked(candidate)) {
            return;
        }

        // Skip if there's already a fire on this tile
        if (fireTiles.contains(candidate)) {
            return;
        }

        // Calculate path cost
        OptionalInt pathCostOpt = navService.getPathCost(ctx, origin, candidate);
        if (pathCostOpt.isEmpty()) {
            // Unreachable tile
            return;
        }

        int pathCost = pathCostOpt.getAsInt();
        if (pathCost > walkThreshold) {
            // Too far to walk
            return;
        }

        // Check if on same line (same Y coordinate = continue fire line west)
        boolean onSameLine = candidate.getY() == origin.getY();

        // Calculate how many consecutive clear tiles are available westward
        int lineLength = countClearTilesWest(candidate, fireTiles, navService);

        candidates.add(new BurnLocation(candidate, pathCost, onSameLine, lineLength));
    }

    /**
     * Count consecutive clear tiles in the burn direction (west) from a starting position.
     * Fires make the player move west after lighting, so we need clear tiles to the west.
     *
     * @param start      starting position
     * @param fireTiles  tiles that already have fires
     * @param navService navigation service for collision checking
     * @return number of consecutive clear tiles available (including start tile)
     */
    private static int countClearTilesWest(WorldPoint start, Set<WorldPoint> fireTiles, NavigationService navService) {
        int count = 1; // Start tile is already verified clear
        int maxCheck = 30; // Don't check more than 30 tiles

        for (int dx = 1; dx <= maxCheck; dx++) {
            WorldPoint tile = new WorldPoint(start.getX() - dx, start.getY(), start.getPlane());

            // Stop if blocked or has fire
            if (navService.isBlocked(tile) || fireTiles.contains(tile)) {
                break;
            }
            count++;
        }

        return count;
    }

    /**
     * Get the positions of all fire objects in the current world state.
     *
     * @param worldState the current world state
     * @return set of world points containing fire objects
     */
    private static Set<WorldPoint> getFireTilePositions(WorldState worldState) {
        Set<WorldPoint> fireTiles = new HashSet<>();

        if (worldState == null) {
            return fireTiles;
        }

        for (GameObjectSnapshot obj : worldState.getNearbyObjects()) {
            if (FIRE_OBJECT_IDS.contains(obj.getId())) {
                fireTiles.add(obj.getWorldPosition());
            }
        }

        return fireTiles;
    }

    /**
     * Find a clear spot nearby as a fallback when no optimal location is found.
     *
     * <p>Tries simple cardinal direction offsets (north, south, east, west)
     * looking for an unblocked tile.
     *
     * @param ctx     TaskContext for collision checking
     * @param current current position
     * @return a nearby clear spot, or current position if nothing found
     */
    public static WorldPoint findClearSpotNearby(TaskContext ctx, WorldPoint current) {
        if (ctx == null || current == null) {
            return current;
        }

        NavigationService navService = ctx.getNavigationService();
        if (navService == null) {
            return current;
        }

        WorldState worldState = ctx.getWorldState();
        Set<WorldPoint> fireTiles = getFireTilePositions(worldState);

        // Try simple offsets: north, south, east, west at distance 2
        int[][] offsets = {{0, 2}, {0, -2}, {2, 0}, {-2, 0}};

        for (int[] offset : offsets) {
            WorldPoint candidate = new WorldPoint(
                    current.getX() + offset[0],
                    current.getY() + offset[1],
                    current.getPlane()
            );

            if (!navService.isBlocked(candidate) && !fireTiles.contains(candidate)) {
                log.debug("Found fallback clear spot at {}", candidate);
                return candidate;
            }
        }

        // Try diagonal offsets
        int[][] diagonals = {{2, 2}, {2, -2}, {-2, 2}, {-2, -2}};
        for (int[] offset : diagonals) {
            WorldPoint candidate = new WorldPoint(
                    current.getX() + offset[0],
                    current.getY() + offset[1],
                    current.getPlane()
            );

            if (!navService.isBlocked(candidate) && !fireTiles.contains(candidate)) {
                log.debug("Found fallback clear spot (diagonal) at {}", candidate);
                return candidate;
            }
        }

        log.warn("No clear spot found near {}, staying in place", current);
        return current;
    }
}
