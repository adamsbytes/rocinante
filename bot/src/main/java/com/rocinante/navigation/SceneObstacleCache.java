package com.rocinante.navigation;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache for obstacle scan results to avoid expensive repeated scene scans.
 *
 * <p>This cache stores the results of {@link ObstacleHandler#findObstaclesNearby(WorldPoint, int)}
 * keyed by the scene's region ID. The cache is invalidated when:
 * <ul>
 *   <li>Player changes regions (moves more than 64 tiles)</li>
 *   <li>Scene is reloaded (login, teleport, etc.)</li>
 *   <li>Explicit invalidation is requested</li>
 * </ul>
 *
 * <p>Performance targets:
 * <ul>
 *   <li>Cache hit: &lt;0.1ms (map lookup)</li>
 *   <li>Cache miss: 5-10ms (full scene scan - same as before)</li>
 *   <li>Expected hit rate: &gt;95% during normal gameplay</li>
 * </ul>
 *
 * <p>The cache is region-based rather than position-based because:
 * <ul>
 *   <li>Obstacles don't move - they're static scene objects</li>
 *   <li>Scene is 104x104 tiles, fully loaded within a region</li>
 *   <li>Region change is the natural invalidation boundary</li>
 * </ul>
 *
 * @see ObstacleHandler
 * @see PathCostEstimator
 */
@Slf4j
@Singleton
public class SceneObstacleCache {

    /**
     * Distance threshold for region change detection.
     * If player moves more than this many tiles, cache is invalidated.
     * Set to 40 tiles (less than half a scene) to ensure cache stays valid
     * while player is in the same general area.
     */
    private static final int REGION_CHANGE_THRESHOLD = 40;

    /**
     * Maximum cache entries to prevent memory issues.
     * Each region's obstacle list is relatively small (typically <100 obstacles).
     */
    private static final int MAX_CACHE_ENTRIES = 16;

    private final Provider<ObstacleHandler> obstacleHandlerProvider;

    /**
     * Cache of obstacle scan results keyed by packed region info.
     * Key format: (regionId << 16) | (plane << 8) | radius
     */
    private final Map<Long, CachedObstacles> cache = new ConcurrentHashMap<>();

    /**
     * Last known player position for region change detection.
     */
    private volatile WorldPoint lastPlayerPosition = null;

    /**
     * Last known region ID for quick change detection.
     */
    private volatile int lastRegionId = -1;

    // Metrics for monitoring cache effectiveness
    @Getter
    private final AtomicLong hitCount = new AtomicLong(0);
    @Getter
    private final AtomicLong missCount = new AtomicLong(0);

    @Inject
    public SceneObstacleCache(Provider<ObstacleHandler> obstacleHandlerProvider) {
        this.obstacleHandlerProvider = obstacleHandlerProvider;
        log.info("SceneObstacleCache initialized");
    }

    /**
     * Get obstacles within a radius of a center point, using cache if available.
     *
     * <p>This method replaces direct calls to {@link ObstacleHandler#findObstaclesNearby(WorldPoint, int)}
     * for performance-critical paths like {@link PathCostEstimator}.
     *
     * @param center the center point for the obstacle search
     * @param radius the search radius in tiles
     * @return list of detected obstacles (may be empty, never null)
     */
    public List<ObstacleHandler.DetectedObstacle> getObstaclesNearby(WorldPoint center, int radius) {
        if (center == null) {
            return Collections.emptyList();
        }

        // Check for region change and invalidate if needed
        checkRegionChange(center);

        // Generate cache key
        long cacheKey = packCacheKey(center.getRegionID(), center.getPlane(), radius);

        // Check cache
        CachedObstacles cached = cache.get(cacheKey);
        if (cached != null && !cached.isStale(center)) {
            hitCount.incrementAndGet();
            return cached.obstacles;
        }

        // Cache miss - perform scan
        missCount.incrementAndGet();
        List<ObstacleHandler.DetectedObstacle> obstacles = performScan(center, radius);

        // Store in cache (with size limit)
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            // Simple eviction - clear oldest entries
            cache.clear();
            log.debug("Cache cleared due to size limit");
        }
        cache.put(cacheKey, new CachedObstacles(obstacles, center, System.currentTimeMillis()));

        return obstacles;
    }

    /**
     * Perform the actual obstacle scan via ObstacleHandler.
     */
    private List<ObstacleHandler.DetectedObstacle> performScan(WorldPoint center, int radius) {
        long startTime = System.nanoTime();
        List<ObstacleHandler.DetectedObstacle> obstacles = 
            obstacleHandlerProvider.get().findObstaclesNearby(center, radius);
        long elapsed = System.nanoTime() - startTime;
        
        if (elapsed > 5_000_000) { // > 5ms
            log.debug("Obstacle scan took {}ms for {} obstacles at {} (radius {})",
                    elapsed / 1_000_000.0, obstacles.size(), center, radius);
        }
        
        return obstacles;
    }

    /**
     * Check if player has changed regions and invalidate cache if needed.
     */
    private void checkRegionChange(WorldPoint currentPosition) {
        if (currentPosition == null) {
            return;
        }

        int currentRegionId = currentPosition.getRegionID();

        // Quick check - region ID changed?
        if (lastRegionId != -1 && lastRegionId != currentRegionId) {
            invalidate("region change from " + lastRegionId + " to " + currentRegionId);
            lastRegionId = currentRegionId;
            lastPlayerPosition = currentPosition;
            return;
        }

        // Distance check - moved too far within same region?
        if (lastPlayerPosition != null) {
            int distance = lastPlayerPosition.distanceTo(currentPosition);
            if (distance > REGION_CHANGE_THRESHOLD) {
                invalidate("distance " + distance + " exceeds threshold");
                lastPlayerPosition = currentPosition;
                return;
            }
        }

        // Update tracking
        lastRegionId = currentRegionId;
        lastPlayerPosition = currentPosition;
    }

    /**
     * Invalidate the entire cache.
     *
     * @param reason the reason for invalidation (for logging)
     */
    public void invalidate(String reason) {
        int size = cache.size();
        cache.clear();
        if (size > 0) {
            log.debug("SceneObstacleCache invalidated ({} entries): {}", size, reason);
        }
    }

    /**
     * Invalidate cache on game state changes (logout, teleport, etc.).
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState state = event.getGameState();
        if (state == GameState.LOADING || state == GameState.LOGIN_SCREEN || 
            state == GameState.HOPPING || state == GameState.CONNECTION_LOST) {
            invalidate("game state: " + state);
            lastPlayerPosition = null;
            lastRegionId = -1;
        }
    }

    /**
     * Get the current cache hit rate.
     *
     * @return hit rate as a percentage (0.0 to 1.0)
     */
    public double getHitRate() {
        long hits = hitCount.get();
        long misses = missCount.get();
        long total = hits + misses;
        if (total == 0) {
            return 0.0;
        }
        return (double) hits / total;
    }

    /**
     * Get cache statistics for monitoring.
     *
     * @return formatted statistics string
     */
    public String getStats() {
        return String.format("SceneObstacleCache[entries=%d, hits=%d, misses=%d, hitRate=%.1f%%]",
                cache.size(), hitCount.get(), missCount.get(), getHitRate() * 100);
    }

    /**
     * Reset cache statistics (for testing/debugging).
     */
    public void resetStats() {
        hitCount.set(0);
        missCount.set(0);
    }

    /**
     * Pack cache key from region, plane, and radius.
     */
    private static long packCacheKey(int regionId, int plane, int radius) {
        return ((long) regionId << 16) | ((long) plane << 8) | (radius & 0xFF);
    }

    /**
     * Cached obstacle scan result with metadata.
     */
    private static class CachedObstacles {
        final List<ObstacleHandler.DetectedObstacle> obstacles;
        final WorldPoint centerWhenCached;
        final long timestamp;

        CachedObstacles(List<ObstacleHandler.DetectedObstacle> obstacles, 
                       WorldPoint centerWhenCached, long timestamp) {
            this.obstacles = Collections.unmodifiableList(obstacles);
            this.centerWhenCached = centerWhenCached;
            this.timestamp = timestamp;
        }

        /**
         * Check if this cache entry is stale.
         * Entry is stale if the query center is too far from the cached center.
         */
        boolean isStale(WorldPoint queryCenter) {
            if (centerWhenCached == null || queryCenter == null) {
                return true;
            }
            // If query center is more than 10 tiles from cached center, consider stale
            // This handles edge cases where player is near region boundary
            return centerWhenCached.distanceTo(queryCenter) > 10;
        }
    }
}
