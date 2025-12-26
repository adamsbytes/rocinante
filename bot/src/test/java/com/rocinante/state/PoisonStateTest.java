package com.rocinante.state;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for PoisonState class.
 */
public class PoisonStateTest {

    // ========================================================================
    // Factory Method Tests
    // ========================================================================

    @Test
    public void testFromVarpValue_NoPoisonReturnsNone() {
        PoisonState state = PoisonState.fromVarpValue(0, 100);

        assertEquals(PoisonType.NONE, state.getType());
        assertEquals(0, state.getCurrentDamage());
        assertFalse(state.hasEffect());
    }

    @Test
    public void testFromVarpValue_PositiveValueIsPoison() {
        PoisonState state = PoisonState.fromVarpValue(15, 100);

        assertEquals(PoisonType.POISON, state.getType());
        assertTrue(state.isPoisoned());
        assertFalse(state.isVenomed());
        assertTrue(state.hasEffect());
    }

    @Test
    public void testFromVarpValue_NegativeValueIsVenom() {
        PoisonState state = PoisonState.fromVarpValue(-10, 100);

        assertEquals(PoisonType.VENOM, state.getType());
        assertFalse(state.isPoisoned());
        assertTrue(state.isVenomed());
        assertTrue(state.hasEffect());
    }

    // ========================================================================
    // Empty/None State Tests
    // ========================================================================

    @Test
    public void testNoneState() {
        PoisonState none = PoisonState.NONE;

        assertEquals(PoisonType.NONE, none.getType());
        assertEquals(0, none.getCurrentDamage());
        assertEquals(-1, none.getLastPoisonTick());
        assertEquals(-1, none.getNextDamageTick());
        assertFalse(none.hasEffect());
        assertFalse(none.isPoisoned());
        assertFalse(none.isVenomed());
    }

    // ========================================================================
    // Venom Damage Prediction Tests
    // ========================================================================

    @Test
    public void testPredictNextVenomDamage_IncreasesByTwo() {
        PoisonState state = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(6)
                .build();

        assertEquals(8, state.predictNextVenomDamage());
    }

    @Test
    public void testPredictNextVenomDamage_CapsAt20() {
        PoisonState state = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(20)
                .build();

        assertEquals(20, state.predictNextVenomDamage());
    }

    @Test
    public void testPredictNextVenomDamage_AtMaxReturnsMax() {
        PoisonState state = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(18)
                .build();

        assertEquals(20, state.predictNextVenomDamage());
    }

    @Test
    public void testPredictNextVenomDamage_NotVenomed() {
        PoisonState state = PoisonState.builder()
                .type(PoisonType.POISON)
                .currentDamage(6)
                .build();

        assertEquals(6, state.predictNextVenomDamage());
    }

    // ========================================================================
    // Poison Damage Prediction Tests
    // ========================================================================

    @Test
    public void testPredictNextPoisonDamage_DecreasesByOne() {
        PoisonState state = PoisonState.builder()
                .type(PoisonType.POISON)
                .currentDamage(6)
                .build();

        assertEquals(5, state.predictNextPoisonDamage());
    }

    @Test
    public void testPredictNextPoisonDamage_MinIsOne() {
        PoisonState state = PoisonState.builder()
                .type(PoisonType.POISON)
                .currentDamage(1)
                .build();

        assertEquals(1, state.predictNextPoisonDamage());
    }

    // ========================================================================
    // Venom Critical Tests
    // ========================================================================

    @Test
    public void testIsVenomCritical_TrueAt16Plus() {
        PoisonState critical = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(16)
                .build();

        assertTrue(critical.isVenomCritical());
    }

    @Test
    public void testIsVenomCritical_FalseBelow16() {
        PoisonState notCritical = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(14)
                .build();

        assertFalse(notCritical.isVenomCritical());
    }

    @Test
    public void testIsVenomCritical_FalseIfNotVenomed() {
        PoisonState poisoned = PoisonState.builder()
                .type(PoisonType.POISON)
                .currentDamage(16)
                .build();

        assertFalse(poisoned.isVenomCritical());
    }

    // ========================================================================
    // Damage Timing Tests
    // ========================================================================

    @Test
    public void testGetTicksUntilNextDamage() {
        PoisonState state = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(10)
                .nextDamageTick(150)
                .build();

        assertEquals(50, state.getTicksUntilNextDamage(100));
        assertEquals(0, state.getTicksUntilNextDamage(150));
        assertEquals(0, state.getTicksUntilNextDamage(200)); // Clamped to 0
    }

    @Test
    public void testGetTicksUntilNextDamage_NotPoisoned() {
        PoisonState state = PoisonState.NONE;

        assertEquals(-1, state.getTicksUntilNextDamage(100));
    }

    // ========================================================================
    // Expected Damage Calculation Tests
    // ========================================================================

    @Test
    public void testCalculateExpectedDamage_Venom() {
        // Venom starts at 10, increases by 2 each hit (30 ticks apart)
        PoisonState state = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(10)
                .build();

        // 60 ticks = 2 hits: 10 + 12 = 22
        int damage = state.calculateExpectedDamage(60);
        assertEquals(22, damage);
    }

    @Test
    public void testCalculateExpectedDamage_Poison() {
        // Poison starts at 6, decreases by 1 each hit
        PoisonState state = PoisonState.builder()
                .type(PoisonType.POISON)
                .currentDamage(6)
                .build();

        // 60 ticks = 2 hits: 6 + 5 = 11
        int damage = state.calculateExpectedDamage(60);
        assertEquals(11, damage);
    }

    @Test
    public void testCalculateExpectedDamage30Seconds() {
        // 30 seconds = 50 ticks = 1 hit
        PoisonState state = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(10)
                .build();

        int damage = state.calculateExpectedDamage30Seconds();
        assertEquals(10, damage);
    }

    @Test
    public void testCalculateExpectedDamage_NoEffect() {
        PoisonState state = PoisonState.NONE;

        assertEquals(0, state.calculateExpectedDamage(100));
    }

    // ========================================================================
    // Summary Test
    // ========================================================================

    @Test
    public void testGetSummary_None() {
        assertEquals("PoisonState[NONE]", PoisonState.NONE.getSummary());
    }

    @Test
    public void testGetSummary_WithEffect() {
        PoisonState state = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(10)
                .nextDamageTick(150)
                .build();

        String summary = state.getSummary();
        assertTrue(summary.contains("VENOM"));
        assertTrue(summary.contains("dmg=10"));
    }
}

