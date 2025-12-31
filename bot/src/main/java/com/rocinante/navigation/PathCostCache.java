package com.rocinante.navigation;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for path costs and paths between world points.
 *
 * <p>This cache stores computed path costs to avoid redundant pathfinding requests.
 * It uses a tile-based caching strategy:
 * <ul>
 *   <li>Cache key is a packed pair of (startTile, endTile)</li>
 *   <li>Works for both stationary objects and mobile NPCs</li>
 *   <li>Invalidates when player moves more than {@link #INVALIDATION_DISTANCE} tiles</li>
 *   <li>Entries become stale after {@link #MAX_CACHE_AGE_MS} milliseconds</li>
 * </ul>
 *
 * <p>Cache invalidation is triggered by {@link #invalidateIfPlayerMoved(WorldPoint)}
 * which should be called each game tick from {@link com.rocinante.core.GameStateService}.
 *
 * <p>Thread-safe implementation using {@link ConcurrentHashMap}.
 *
 * @see NavigationService
 */
@Slf4j
@Singleton
public class PathCostCache {

    /**
     * Distance threshold for cache invalidation.
     * When player moves more than this many tiles, entire cache is cleared.
     * Set to 25 tiles to reduce unnecessary cache invalidation during normal movement.
     */
    private static final int INVALIDATION_DISTANCE = 25;

    /**
     * Maximum age of cached entries in milliseconds.
     * Entries older than this are considered stale and ignored.
     */
    private static final long MAX_CACHE_AGE_MS = 60_000; // 1 minute

    /**
     * Main cache storage.
     * Key: packed position pair (start + end), Value: cached result
     */
    private final Map<Long, CachedPathResult> cache = new ConcurrentHashMap<>();

    /**
     * Last known player position for invalidation tracking.
     */
    private volatile WorldPoint lastPlayerPosition = null;

    /**
     * Internal cache entry storing path cost and metadata.
     */
    @Value
    private static class CachedPathResult {
        int cost;
        List<WorldPoint> path;
        long timestamp;
        WorldPoint playerPosWhenCached;
    }

    /**
     * Get cached path cost between two points.
     *
     * @param start starting world point
     * @param end ending world point
     * @return cached cost if available and not stale, empty otherwise
     */
    public Optional<Integer> getPathCost(WorldPoint start, WorldPoint end) {
        if (start == null || end == null) {
            return Optional.empty();
        }

        long key = packPositionPair(start, end);
        CachedPathResult result = cache.get(key);

        if (result != null && !isStale(result, start)) {
            return Optional.of(result.cost);
        }
        return Optional.empty();
    }

    /**
     * Get cached path between two points.
     *
     * @param start starting world point
     * @param end ending world point
     * @return cached path if available and not stale, empty otherwise
     */
    public Optional<List<WorldPoint>> getPath(WorldPoint start, WorldPoint end) {
        if (start == null || end == null) {
            return Optional.empty();
        }

        long key = packPositionPair(start, end);
        CachedPathResult result = cache.get(key);

        if (result != null && !isStale(result, start)) {
            return Optional.of(new ArrayList<>(result.path));
        }
        return Optional.empty();
    }

    /**
     * Cache a computed path result.
     *
     * @param start starting world point
     * @param end ending world point
     * @param path computed path
     */
    public void cachePathResult(WorldPoint start, WorldPoint end, List<WorldPoint> path) {
        if (start == null || end == null || path == null || path.isEmpty()) {
            return;
        }

        long key = packPositionPair(start, end);
        CachedPathResult result = new CachedPathResult(
                path.size(),
                new ArrayList<>(path), // Defensive copy
                System.currentTimeMillis(),
                start
        );
        cache.put(key, result);
        log.debug("Cached path from {} to {}: {} tiles (cache size: {})", 
                start, end, path.size(), cache.size());
    }

    /**
     * Invalidate cache if player has moved significantly.
     * Should be called each game tick from GameStateService.
     *
     * @param currentPlayerPos current player world position
     */
    public void invalidateIfPlayerMoved(WorldPoint currentPlayerPos) {
        if (currentPlayerPos == null) {
            return;
        }

        if (lastPlayerPosition == null) {
            lastPlayerPosition = currentPlayerPos;
            return;
        }

        int distance = lastPlayerPosition.distanceTo(currentPlayerPos);
        if (distance > INVALIDATION_DISTANCE) {
            int size = cache.size();
            cache.clear();
            lastPlayerPosition = currentPlayerPos;
            log.debug("Path cache invalidated: player moved {} tiles (cleared {} entries)", 
                    distance, size);
        } else {
            // Update position even for small movements
            lastPlayerPosition = currentPlayerPos;
        }
    }

    /**
     * Clear all cached entries.
     */
    public void clear() {
        int size = cache.size();
        cache.clear();
        lastPlayerPosition = null;
        log.debug("Path cache manually cleared ({} entries)", size);
    }

    /**
     * Get current cache size.
     *
     * @return number of cached entries
     */
    public int getCacheSize() {
        return cache.size();
    }

    /**
     * Check if a cached result is stale.
     *
     * @param result the cached result
     * @param currentStart the current start position for this query
     * @return true if stale and should be ignored
     */
    private boolean isStale(CachedPathResult result, WorldPoint currentStart) {
        // Check age
        long age = System.currentTimeMillis() - result.timestamp;
        if (age > MAX_CACHE_AGE_MS) {
            return true;
        }

        // Check if player moved significantly since caching
        int movement = result.playerPosWhenCached.distanceTo(currentStart);
        return movement > INVALIDATION_DISTANCE;
    }

    /**
     * Pack two WorldPoints into a single long for use as cache key.
     *
     * <p>Packing format:
     * <pre>
     * Bits 52-63: start X (12 bits, supports 0-4095)
     * Bits 40-51: start Y (12 bits, supports 0-4095)
     * Bits 38-39: start plane (2 bits, supports 0-3)
     * Bits 26-37: end X (12 bits)
     * Bits 14-25: end Y (12 bits)
     * Bits 12-13: end plane (2 bits)
     * </pre>
     *
     * @param start starting world point
     * @param end ending world point
     * @return packed long value
     */
    private long packPositionPair(WorldPoint start, WorldPoint end) {
        long sx = (long) (start.getX() & 0xFFF);
        long sy = (long) (start.getY() & 0xFFF);
        long sz = (long) (start.getPlane() & 0x3);
        long ex = (long) (end.getX() & 0xFFF);
        long ey = (long) (end.getY() & 0xFFF);
        long ez = (long) (end.getPlane() & 0x3);

        return (sx << 52) | (sy << 40) | (sz << 38) | (ex << 26) | (ey << 14) | (ez << 12);
    }
}
