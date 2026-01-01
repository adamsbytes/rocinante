package com.rocinante.core;

import com.rocinante.navigation.SceneObstacleCache;
import com.rocinante.navigation.SpatialObjectIndex;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance monitoring service for tracking tick execution times and cache effectiveness.
 *
 * <p>Monitors critical path operations to ensure tick responsiveness (600ms budget).
 * Logs warnings when operations exceed thresholds and tracks cache hit rates.
 *
 * <p>Performance thresholds:
 * <ul>
 *   <li>Severe (&gt;100ms): Critical - bot may lag behind game</li>
 *   <li>Warning (&gt;50ms): Concerning - may affect responsiveness</li>
 *   <li>Info (&gt;10ms): Notable - worth investigating</li>
 *   <li>Debug (&gt;5ms): Fine detail for optimization</li>
 * </ul>
 *
 * <p>This service runs at lowest priority to capture the full tick processing time.
 */
@Slf4j
@Singleton
public class PerformanceMonitor {

    /**
     * Tick budget threshold - severe warning if exceeded (100ms).
     */
    private static final long SEVERE_THRESHOLD_NS = 100_000_000;

    /**
     * Warning threshold (50ms).
     */
    private static final long WARNING_THRESHOLD_NS = 50_000_000;

    /**
     * Info threshold (10ms).
     */
    private static final long INFO_THRESHOLD_NS = 10_000_000;

    /**
     * Debug threshold (5ms).
     */
    private static final long DEBUG_THRESHOLD_NS = 5_000_000;

    /**
     * Number of ticks between performance summaries.
     */
    private static final int SUMMARY_INTERVAL = 100;

    private final Client client;
    private final SceneObstacleCache sceneObstacleCache;
    private final SpatialObjectIndex spatialObjectIndex;

    // Timing metrics
    private final AtomicLong totalTickTimeNs = new AtomicLong(0);
    private final AtomicLong maxTickTimeNs = new AtomicLong(0);
    private final AtomicLong tickCount = new AtomicLong(0);
    private final AtomicLong severeCount = new AtomicLong(0);
    private final AtomicLong warningCount = new AtomicLong(0);

    // Per-operation timing (set by other components)
    @Getter
    private volatile long lastPathCostEstimateNs = 0;
    @Getter
    private volatile long lastEntityFindNs = 0;
    @Getter
    private volatile long lastTaskExecutionNs = 0;

    // Tick timing start (set by high-priority handler)
    private volatile long tickStartNs = 0;

    @Inject
    public PerformanceMonitor(Client client, 
                              SceneObstacleCache sceneObstacleCache,
                              SpatialObjectIndex spatialObjectIndex) {
        this.client = client;
        this.sceneObstacleCache = sceneObstacleCache;
        this.spatialObjectIndex = spatialObjectIndex;
        log.info("PerformanceMonitor initialized");
    }

    /**
     * Record the start of tick processing.
     * Called by high-priority tick handler.
     */
    public void recordTickStart() {
        tickStartNs = System.nanoTime();
    }

    /**
     * Record an operation's execution time.
     *
     * @param operationName name of the operation
     * @param elapsedNs     time in nanoseconds
     */
    public void recordOperation(String operationName, long elapsedNs) {
        if (elapsedNs > DEBUG_THRESHOLD_NS) {
            String level = elapsedNs > SEVERE_THRESHOLD_NS ? "SEVERE" :
                          elapsedNs > WARNING_THRESHOLD_NS ? "WARN" :
                          elapsedNs > INFO_THRESHOLD_NS ? "INFO" : "DEBUG";
            log.debug("[PERF:{}] {} took {}ms", level, operationName, elapsedNs / 1_000_000.0);
        }

        // Track specific operations
        switch (operationName) {
            case "PathCostEstimate":
                lastPathCostEstimateNs = elapsedNs;
                break;
            case "EntityFind":
                lastEntityFindNs = elapsedNs;
                break;
            case "TaskExecution":
                lastTaskExecutionNs = elapsedNs;
                break;
        }
    }

    /**
     * Called at end of tick to record total tick time.
     * Uses lowest priority to run after all other handlers.
     */
    @Subscribe(priority = Integer.MIN_VALUE)
    public void onGameTick(GameTick event) {
        if (tickStartNs == 0) {
            return; // Start wasn't recorded
        }

        long elapsed = System.nanoTime() - tickStartNs;
        tickStartNs = 0;

        long count = tickCount.incrementAndGet();
        totalTickTimeNs.addAndGet(elapsed);

        // Update max
        long currentMax;
        do {
            currentMax = maxTickTimeNs.get();
            if (elapsed <= currentMax) {
                break;
            }
        } while (!maxTickTimeNs.compareAndSet(currentMax, elapsed));

        // Log if threshold exceeded
        if (elapsed > SEVERE_THRESHOLD_NS) {
            severeCount.incrementAndGet();
            log.error("[PERF] Tick {} exceeded severe threshold: {}ms (budget: 100ms). " +
                      "Bot may be lagging behind game!",
                      count, elapsed / 1_000_000.0);
        } else if (elapsed > WARNING_THRESHOLD_NS) {
            warningCount.incrementAndGet();
            log.warn("[PERF] Tick {} exceeded warning threshold: {}ms (target: <50ms)",
                     count, elapsed / 1_000_000.0);
        } else if (elapsed > INFO_THRESHOLD_NS) {
            log.info("[PERF] Tick {} took {}ms (target: <10ms)", count, elapsed / 1_000_000.0);
        }

        // Periodic summary
        if (count % SUMMARY_INTERVAL == 0) {
            logSummary();
        }
    }

    /**
     * Log a performance summary including cache hit rates.
     */
    public void logSummary() {
        long count = tickCount.get();
        if (count == 0) {
            return;
        }

        double avgTickMs = (totalTickTimeNs.get() / count) / 1_000_000.0;
        double maxTickMs = maxTickTimeNs.get() / 1_000_000.0;
        double obstacleHitRate = sceneObstacleCache.getHitRate() * 100;

        log.info("[PERF] === Performance Summary (last {} ticks) ===", count);
        log.info("[PERF] Tick timing: avg={}ms, max={}ms", 
                 String.format("%.2f", avgTickMs), String.format("%.2f", maxTickMs));
        log.info("[PERF] Threshold violations: severe={}, warning={}", 
                 severeCount.get(), warningCount.get());
        log.info("[PERF] Cache: {}", sceneObstacleCache.getStats());
        log.info("[PERF] Index: {}", spatialObjectIndex.getStats());
        log.info("[PERF] Obstacle cache hit rate: {}%", String.format("%.1f", obstacleHitRate));
    }

    /**
     * Get the average tick time in milliseconds.
     *
     * @return average tick time, or 0 if no ticks recorded
     */
    public double getAverageTickTimeMs() {
        long count = tickCount.get();
        if (count == 0) {
            return 0;
        }
        return (totalTickTimeNs.get() / count) / 1_000_000.0;
    }

    /**
     * Get the maximum tick time in milliseconds.
     *
     * @return maximum tick time
     */
    public double getMaxTickTimeMs() {
        return maxTickTimeNs.get() / 1_000_000.0;
    }

    /**
     * Get total number of ticks processed.
     *
     * @return tick count
     */
    public long getTickCount() {
        return tickCount.get();
    }

    /**
     * Get number of severe threshold violations.
     *
     * @return severe count
     */
    public long getSevereCount() {
        return severeCount.get();
    }

    /**
     * Get number of warning threshold violations.
     *
     * @return warning count
     */
    public long getWarningCount() {
        return warningCount.get();
    }

    /**
     * Reset all performance metrics.
     */
    public void reset() {
        totalTickTimeNs.set(0);
        maxTickTimeNs.set(0);
        tickCount.set(0);
        severeCount.set(0);
        warningCount.set(0);
        lastPathCostEstimateNs = 0;
        lastEntityFindNs = 0;
        lastTaskExecutionNs = 0;
        sceneObstacleCache.resetStats();
        log.info("[PERF] Performance metrics reset");
    }
}
