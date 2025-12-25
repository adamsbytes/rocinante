package com.rocinante.state;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for CachePolicy enum.
 */
public class CachePolicyTest {

    @Test
    public void testTickCachedPolicy() {
        CachePolicy policy = CachePolicy.TICK_CACHED;

        assertEquals(1, policy.getTicksUntilExpiry());
        assertTrue(policy.isTickBased());
        assertFalse(policy.isEventBased());
        assertFalse(policy.isSessionBased());
    }

    @Test
    public void testEventInvalidatedPolicy() {
        CachePolicy policy = CachePolicy.EVENT_INVALIDATED;

        assertEquals(Integer.MAX_VALUE, policy.getTicksUntilExpiry());
        assertFalse(policy.isTickBased());
        assertTrue(policy.isEventBased());
        assertFalse(policy.isSessionBased());
    }

    @Test
    public void testExpensiveComputedPolicy() {
        CachePolicy policy = CachePolicy.EXPENSIVE_COMPUTED;

        assertEquals(5, policy.getTicksUntilExpiry());
        assertTrue(policy.isTickBased());
        assertFalse(policy.isEventBased());
        assertFalse(policy.isSessionBased());
    }

    @Test
    public void testSessionCachedPolicy() {
        CachePolicy policy = CachePolicy.SESSION_CACHED;

        assertEquals(Integer.MAX_VALUE, policy.getTicksUntilExpiry());
        assertFalse(policy.isTickBased());
        assertFalse(policy.isEventBased());
        assertTrue(policy.isSessionBased());
    }

    @Test
    public void testLazyComputedPolicy() {
        CachePolicy policy = CachePolicy.LAZY_COMPUTED;

        assertEquals(Integer.MAX_VALUE, policy.getTicksUntilExpiry());
        assertFalse(policy.isTickBased());
        assertFalse(policy.isEventBased());
        assertFalse(policy.isSessionBased());
    }

    @Test
    public void testAllPoliciesExist() {
        // Verify all expected policies exist
        assertEquals(5, CachePolicy.values().length);

        assertNotNull(CachePolicy.TICK_CACHED);
        assertNotNull(CachePolicy.EVENT_INVALIDATED);
        assertNotNull(CachePolicy.EXPENSIVE_COMPUTED);
        assertNotNull(CachePolicy.SESSION_CACHED);
        assertNotNull(CachePolicy.LAZY_COMPUTED);
    }
}

