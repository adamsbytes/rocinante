package com.rocinante.state;

/**
 * Cache invalidation strategies for game state data as specified in REQUIREMENTS.md Section 6.1.1.
 *
 * Different types of game data require different caching strategies to balance
 * performance with data freshness. This enum defines the invalidation policies
 * used by {@link CachedValue} to determine when cached data should be refreshed.
 */
public enum CachePolicy {

    /**
     * Invalidate every game tick (600ms).
     * Use for: Player position, animation, combat state.
     * These values change frequently and must be fresh each tick.
     */
    TICK_CACHED(1),

    /**
     * Invalidate only on specific events, not on tick.
     * Use for: Inventory (invalidate on ItemContainerChanged), equipment.
     * These values only change when the player explicitly modifies them.
     */
    EVENT_INVALIDATED(Integer.MAX_VALUE),

    /**
     * Invalidate every 5 ticks or on specific events.
     * Use for: Nearby NPCs/objects scan.
     * Expensive computations that don't need per-tick updates.
     */
    EXPENSIVE_COMPUTED(5),

    /**
     * Never invalidate until logout (session end).
     * Use for: Unlocked teleports, completed quests.
     * Data that doesn't change during a session.
     */
    SESSION_CACHED(Integer.MAX_VALUE),

    /**
     * Compute on first access only, then cache indefinitely.
     * Use for: Pathfinding results, wiki API data.
     * One-time computations that are reused.
     */
    LAZY_COMPUTED(Integer.MAX_VALUE);

    private final int ticksUntilExpiry;

    CachePolicy(int ticksUntilExpiry) {
        this.ticksUntilExpiry = ticksUntilExpiry;
    }

    /**
     * Get the number of game ticks until this cache policy expires.
     *
     * @return ticks until expiry, or Integer.MAX_VALUE for event-based/session-based policies
     */
    public int getTicksUntilExpiry() {
        return ticksUntilExpiry;
    }

    /**
     * Check if this policy uses tick-based expiration.
     *
     * @return true if the cache expires based on tick count
     */
    public boolean isTickBased() {
        return this == TICK_CACHED || this == EXPENSIVE_COMPUTED;
    }

    /**
     * Check if this policy requires explicit event-based invalidation.
     *
     * @return true if the cache should only be invalidated by events
     */
    public boolean isEventBased() {
        return this == EVENT_INVALIDATED;
    }

    /**
     * Check if this policy caches for the entire session.
     *
     * @return true if the cache persists until logout
     */
    public boolean isSessionBased() {
        return this == SESSION_CACHED;
    }
}

