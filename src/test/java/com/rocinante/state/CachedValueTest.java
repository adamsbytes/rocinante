package com.rocinante.state;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Unit tests for CachedValue class.
 */
public class CachedValueTest {

    private CachedValue<String> tickCachedValue;
    private CachedValue<String> eventCachedValue;
    private CachedValue<Integer> expensiveCachedValue;

    @Before
    public void setUp() {
        tickCachedValue = new CachedValue<>("TestTickCached", CachePolicy.TICK_CACHED);
        eventCachedValue = new CachedValue<>("TestEventCached", CachePolicy.EVENT_INVALIDATED);
        expensiveCachedValue = new CachedValue<>("TestExpensive", CachePolicy.EXPENSIVE_COMPUTED);
    }

    // ========================================================================
    // Basic Operations
    // ========================================================================

    @Test
    public void testInitialState() {
        assertFalse(tickCachedValue.isInitialized());
        assertFalse(tickCachedValue.isValid(0));
        assertNull(tickCachedValue.getStale());
        assertEquals(-1, tickCachedValue.getLastUpdateTick());
    }

    @Test
    public void testSetAndGet() {
        tickCachedValue.set(1, "Hello");

        assertTrue(tickCachedValue.isInitialized());
        assertTrue(tickCachedValue.isValid(1));
        assertEquals("Hello", tickCachedValue.getStale());
        assertEquals(1, tickCachedValue.getLastUpdateTick());
    }

    @Test
    public void testGetWithSupplier() {
        AtomicInteger callCount = new AtomicInteger(0);

        String result = tickCachedValue.get(1, () -> {
            callCount.incrementAndGet();
            return "Computed";
        });

        assertEquals("Computed", result);
        assertEquals(1, callCount.get());
        assertTrue(tickCachedValue.isInitialized());
    }

    @Test
    public void testGetIfValidReturnsNull_WhenInvalid() {
        assertNull(tickCachedValue.getIfValid(1));
    }

    @Test
    public void testGetIfValidReturnsValue_WhenValid() {
        tickCachedValue.set(1, "ValidValue");
        assertEquals("ValidValue", tickCachedValue.getIfValid(1));
    }

    // ========================================================================
    // Tick-Based Expiration
    // ========================================================================

    @Test
    public void testTickCachedExpiresAfterOneTick() {
        tickCachedValue.set(1, "Value");

        assertTrue(tickCachedValue.isValid(1));
        assertFalse(tickCachedValue.isValid(2)); // Expired after 1 tick
    }

    @Test
    public void testExpensiveComputedExpiresAfterFiveTicks() {
        expensiveCachedValue.set(1, 42);

        assertTrue(expensiveCachedValue.isValid(1));
        assertTrue(expensiveCachedValue.isValid(2));
        assertTrue(expensiveCachedValue.isValid(3));
        assertTrue(expensiveCachedValue.isValid(4));
        assertTrue(expensiveCachedValue.isValid(5));
        assertFalse(expensiveCachedValue.isValid(6)); // Expired after 5 ticks
    }

    @Test
    public void testEventCachedNeverExpiresOnTick() {
        eventCachedValue.set(1, "EventValue");

        // Should remain valid for many ticks
        assertTrue(eventCachedValue.isValid(1));
        assertTrue(eventCachedValue.isValid(100));
        assertTrue(eventCachedValue.isValid(10000));
        assertTrue(eventCachedValue.isValid(Integer.MAX_VALUE - 1));
    }

    // ========================================================================
    // Cache Invalidation
    // ========================================================================

    @Test
    public void testInvalidate() {
        eventCachedValue.set(1, "Value");
        assertTrue(eventCachedValue.isValid(1));

        eventCachedValue.invalidate();
        assertFalse(eventCachedValue.isValid(1));

        // Value should still be accessible via getStale()
        assertEquals("Value", eventCachedValue.getStale());
    }

    @Test
    public void testReset() {
        tickCachedValue.set(1, "Value");
        tickCachedValue.get(1, () -> "x"); // Generate some hits

        tickCachedValue.reset();

        assertFalse(tickCachedValue.isInitialized());
        assertNull(tickCachedValue.getStale());
        assertEquals(-1, tickCachedValue.getLastUpdateTick());
        assertEquals(0, tickCachedValue.getHitCount().get());
        assertEquals(0, tickCachedValue.getMissCount().get());
    }

    // ========================================================================
    // Cache Metrics
    // ========================================================================

    @Test
    public void testHitCountIncrements() {
        tickCachedValue.set(1, "Value");

        tickCachedValue.getIfValid(1); // Hit
        tickCachedValue.getIfValid(1); // Hit

        assertEquals(2, tickCachedValue.getHitCount().get());
    }

    @Test
    public void testMissCountIncrements() {
        // First access is always a miss
        tickCachedValue.get(1, () -> "First");
        // Second access after expiration is a miss
        tickCachedValue.get(2, () -> "Second");

        assertEquals(2, tickCachedValue.getMissCount().get());
    }

    @Test
    public void testHitRateCalculation() {
        tickCachedValue.set(0, "Value");

        // 2 hits
        tickCachedValue.getIfValid(0);
        tickCachedValue.getIfValid(0);

        // 1 miss (expired)
        tickCachedValue.get(1, () -> "New");

        // hitRate = 2 / 3 = 0.666...
        assertEquals(2.0 / 3.0, tickCachedValue.getHitRate(), 0.01);
    }

    @Test
    public void testHitRateZero_WhenNoAccesses() {
        assertEquals(0.0, tickCachedValue.getHitRate(), 0.001);
    }

    // ========================================================================
    // Refresh Behavior
    // ========================================================================

    @Test
    public void testRefreshUpdatesValue() {
        tickCachedValue.set(1, "Old");
        String result = tickCachedValue.refresh(2, () -> "New");

        assertEquals("New", result);
        assertEquals("New", tickCachedValue.getStale());
        assertEquals(2, tickCachedValue.getLastUpdateTick());
    }

    @Test
    public void testSupplierOnlyCalledOnMiss() {
        AtomicInteger callCount = new AtomicInteger(0);

        // First call - miss
        tickCachedValue.get(1, () -> {
            callCount.incrementAndGet();
            return "Value";
        });

        // Second call same tick - hit (supplier not called)
        tickCachedValue.get(1, () -> {
            callCount.incrementAndGet();
            return "Value2";
        });

        assertEquals(1, callCount.get());
    }

    // ========================================================================
    // Policy and Name
    // ========================================================================

    @Test
    public void testGetPolicy() {
        assertEquals(CachePolicy.TICK_CACHED, tickCachedValue.getPolicy());
        assertEquals(CachePolicy.EVENT_INVALIDATED, eventCachedValue.getPolicy());
        assertEquals(CachePolicy.EXPENSIVE_COMPUTED, expensiveCachedValue.getPolicy());
    }

    @Test
    public void testGetName() {
        assertEquals("TestTickCached", tickCachedValue.getName());
        assertEquals("TestEventCached", eventCachedValue.getName());
        assertEquals("TestExpensive", expensiveCachedValue.getName());
    }

    @Test
    public void testToString() {
        tickCachedValue.set(1, "Value");
        String str = tickCachedValue.toString();

        assertTrue(str.contains("TestTickCached"));
        assertTrue(str.contains("TICK_CACHED"));
        assertTrue(str.contains("valid=true"));
    }
}

