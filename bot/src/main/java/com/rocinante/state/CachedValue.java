package com.rocinante.state;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Generic cache wrapper with policy-based invalidation for game state data.
 *
 * This class implements the caching strategy specified in REQUIREMENTS.md Section 6.1.1,
 * providing tick-based expiration and event-driven invalidation for different types
 * of game state data.
 *
 * Performance targets from spec:
 * - State queries: <1ms when cached
 * - Full tick update: <5ms
 * - Cache footprint: <10MB
 * - Cache hit rate: >90%
 *
 * @param <T> the type of value being cached
 */
@Slf4j
public class CachedValue<T> {

    private final CachePolicy policy;
    private final String name;

    private volatile T value;
    private volatile int lastUpdateTick;
    private volatile boolean valid;
    private volatile boolean initialized;

    // Metrics for debugging and optimization
    @Getter
    private final AtomicLong hitCount = new AtomicLong(0);
    @Getter
    private final AtomicLong missCount = new AtomicLong(0);

    /**
     * Create a new cached value with the specified policy.
     *
     * @param name   a descriptive name for logging/debugging
     * @param policy the cache invalidation policy
     */
    public CachedValue(String name, CachePolicy policy) {
        this.name = name;
        this.policy = policy;
        this.valid = false;
        this.initialized = false;
        this.lastUpdateTick = -1;
    }

    /**
     * Get the cached value, computing it if necessary.
     *
     * @param currentTick the current game tick
     * @param supplier    supplier to compute the value if cache miss
     * @return the cached or freshly computed value
     */
    public T get(int currentTick, Supplier<T> supplier) {
        if (isValid(currentTick)) {
            hitCount.incrementAndGet();
            return value;
        }

        missCount.incrementAndGet();
        return refresh(currentTick, supplier);
    }

    /**
     * Get the cached value without recomputing.
     * Returns null if the cache is invalid or uninitialized.
     *
     * @param currentTick the current game tick
     * @return the cached value, or null if invalid
     */
    public T getIfValid(int currentTick) {
        if (isValid(currentTick)) {
            hitCount.incrementAndGet();
            return value;
        }
        return null;
    }

    /**
     * Get the cached value regardless of validity.
     * Useful for accessing stale data when fresh data isn't critical.
     *
     * @return the last cached value, may be stale or null
     */
    public T getStale() {
        return value;
    }

    /**
     * Force refresh the cached value.
     *
     * @param currentTick the current game tick
     * @param supplier    supplier to compute the new value
     * @return the newly computed value
     */
    public T refresh(int currentTick, Supplier<T> supplier) {
        long startTime = System.nanoTime();

        T newValue = supplier.get();
        this.value = newValue;
        this.lastUpdateTick = currentTick;
        this.valid = true;
        this.initialized = true;

        long elapsed = System.nanoTime() - startTime;
        if (elapsed > 1_000_000) { // > 1ms
            log.debug("Cache refresh for '{}' took {}ms", name, elapsed / 1_000_000.0);
        }

        return newValue;
    }

    /**
     * Update the cached value directly without using a supplier.
     *
     * @param currentTick the current game tick
     * @param newValue    the new value to cache
     */
    public void set(int currentTick, T newValue) {
        this.value = newValue;
        this.lastUpdateTick = currentTick;
        this.valid = true;
        this.initialized = true;
    }

    /**
     * Check if the cached value is valid for the given tick.
     *
     * @param currentTick the current game tick
     * @return true if the cache is valid and not expired
     */
    public boolean isValid(int currentTick) {
        if (!valid || !initialized) {
            return false;
        }

        // Event-based and session-based policies don't expire on tick
        if (!policy.isTickBased()) {
            return true;
        }

        // Check tick-based expiration
        int ticksElapsed = currentTick - lastUpdateTick;
        return ticksElapsed < policy.getTicksUntilExpiry();
    }

    /**
     * Invalidate the cache, forcing a refresh on next access.
     * Used for event-based invalidation (e.g., ItemContainerChanged).
     */
    public void invalidate() {
        this.valid = false;
        log.trace("Cache '{}' invalidated", name);
    }

    /**
     * Reset the cache completely, clearing value and all state.
     * Used on logout or plugin shutdown.
     */
    public void reset() {
        this.value = null;
        this.valid = false;
        this.initialized = false;
        this.lastUpdateTick = -1;
        hitCount.set(0);
        missCount.set(0);
        log.trace("Cache '{}' reset", name);
    }

    /**
     * Get the cache policy for this value.
     *
     * @return the cache policy
     */
    public CachePolicy getPolicy() {
        return policy;
    }

    /**
     * Get the name of this cached value.
     *
     * @return the cache name
     */
    public String getName() {
        return name;
    }

    /**
     * Get the tick when this value was last updated.
     *
     * @return the last update tick, or -1 if never updated
     */
    public int getLastUpdateTick() {
        return lastUpdateTick;
    }

    /**
     * Check if this cache has ever been initialized.
     *
     * @return true if the cache has been set at least once
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Calculate the cache hit rate as a percentage.
     *
     * @return hit rate (0.0 to 1.0), or 0.0 if no accesses
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

    @Override
    public String toString() {
        return String.format("CachedValue[%s, policy=%s, valid=%s, hitRate=%.2f%%]",
                name, policy, valid, getHitRate() * 100);
    }
}

