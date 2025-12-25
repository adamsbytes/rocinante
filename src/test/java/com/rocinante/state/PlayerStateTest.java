package com.rocinante.state;

import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for PlayerState class.
 */
public class PlayerStateTest {

    // ========================================================================
    // Empty State
    // ========================================================================

    @Test
    public void testEmptyState() {
        PlayerState empty = PlayerState.EMPTY;

        assertNull(empty.getWorldPosition());
        assertNull(empty.getLocalPosition());
        assertEquals(-1, empty.getAnimationId());
        assertFalse(empty.isMoving());
        assertFalse(empty.isInteracting());
        assertEquals(0, empty.getCurrentHitpoints());
        assertEquals(0, empty.getMaxHitpoints());
        assertFalse(empty.isInCombat());
        assertFalse(empty.isValid());
    }

    @Test
    public void testEmptyStateIsSingleton() {
        assertSame(PlayerState.EMPTY, PlayerState.EMPTY);
    }

    // ========================================================================
    // Builder and Immutability
    // ========================================================================

    @Test
    public void testBuilderCreatesValidState() {
        WorldPoint pos = new WorldPoint(3200, 3200, 0);

        PlayerState state = PlayerState.builder()
                .worldPosition(pos)
                .currentHitpoints(50)
                .maxHitpoints(99)
                .currentPrayer(43)
                .maxPrayer(70)
                .runEnergy(80)
                .animationId(808)
                .isMoving(true)
                .inCombat(false)
                .spellbook(0)
                .build();

        assertEquals(pos, state.getWorldPosition());
        assertEquals(50, state.getCurrentHitpoints());
        assertEquals(99, state.getMaxHitpoints());
        assertEquals(43, state.getCurrentPrayer());
        assertEquals(70, state.getMaxPrayer());
        assertEquals(80, state.getRunEnergy());
        assertEquals(808, state.getAnimationId());
        assertTrue(state.isMoving());
        assertFalse(state.isInCombat());
        assertTrue(state.isValid());
    }

    @Test
    public void testToBuilderCreatesModifiableCopy() {
        PlayerState original = PlayerState.builder()
                .worldPosition(new WorldPoint(100, 100, 0))
                .currentHitpoints(50)
                .maxHitpoints(99)
                .build();

        PlayerState modified = original.toBuilder()
                .currentHitpoints(75)
                .build();

        assertEquals(50, original.getCurrentHitpoints());
        assertEquals(75, modified.getCurrentHitpoints());
        assertEquals(original.getWorldPosition(), modified.getWorldPosition());
    }

    // ========================================================================
    // Validity Check
    // ========================================================================

    @Test
    public void testIsValid_TrueWhenPositionSet() {
        PlayerState state = PlayerState.builder()
                .worldPosition(new WorldPoint(0, 0, 0))
                .build();

        assertTrue(state.isValid());
    }

    @Test
    public void testIsValid_FalseWhenPositionNull() {
        PlayerState state = PlayerState.builder()
                .worldPosition(null)
                .build();

        assertFalse(state.isValid());
    }

    // ========================================================================
    // Idle State Detection
    // ========================================================================

    @Test
    public void testIsIdle_TrueWhenNoActivity() {
        PlayerState state = PlayerState.builder()
                .worldPosition(new WorldPoint(0, 0, 0))
                .isMoving(false)
                .animationId(-1)
                .isInteracting(false)
                .build();

        assertTrue(state.isIdle());
    }

    @Test
    public void testIsIdle_FalseWhenMoving() {
        PlayerState state = PlayerState.builder()
                .isMoving(true)
                .animationId(-1)
                .isInteracting(false)
                .build();

        assertFalse(state.isIdle());
    }

    @Test
    public void testIsIdle_FalseWhenAnimating() {
        PlayerState state = PlayerState.builder()
                .isMoving(false)
                .animationId(808)
                .isInteracting(false)
                .build();

        assertFalse(state.isIdle());
    }

    @Test
    public void testIsIdle_FalseWhenInteracting() {
        PlayerState state = PlayerState.builder()
                .isMoving(false)
                .animationId(-1)
                .isInteracting(true)
                .build();

        assertFalse(state.isIdle());
    }

    // ========================================================================
    // Animation Checks
    // ========================================================================

    @Test
    public void testIsAnimating_TrueWhenAnimationPlaying() {
        PlayerState state = PlayerState.builder()
                .animationId(808)
                .build();

        assertTrue(state.isAnimating());
    }

    @Test
    public void testIsAnimating_FalseWhenNoAnimation() {
        PlayerState state = PlayerState.builder()
                .animationId(-1)
                .build();

        assertFalse(state.isAnimating());
    }

    @Test
    public void testIsAnimatingSpecificId() {
        PlayerState state = PlayerState.builder()
                .animationId(808)
                .build();

        assertTrue(state.isAnimating(808));
        assertFalse(state.isAnimating(809));
    }

    // ========================================================================
    // Health and Prayer Percentages
    // ========================================================================

    @Test
    public void testGetHealthPercent() {
        PlayerState state = PlayerState.builder()
                .currentHitpoints(50)
                .maxHitpoints(100)
                .build();

        assertEquals(0.5, state.getHealthPercent(), 0.001);
    }

    @Test
    public void testGetHealthPercent_ZeroMax() {
        PlayerState state = PlayerState.builder()
                .currentHitpoints(50)
                .maxHitpoints(0)
                .build();

        assertEquals(0.0, state.getHealthPercent(), 0.001);
    }

    @Test
    public void testGetPrayerPercent() {
        PlayerState state = PlayerState.builder()
                .currentPrayer(35)
                .maxPrayer(70)
                .build();

        assertEquals(0.5, state.getPrayerPercent(), 0.001);
    }

    @Test
    public void testIsHealthBelow() {
        PlayerState state = PlayerState.builder()
                .currentHitpoints(30)
                .maxHitpoints(99)
                .build();

        assertTrue(state.isHealthBelow(0.5));
        assertFalse(state.isHealthBelow(0.2));
    }

    @Test
    public void testIsPrayerBelow() {
        PlayerState state = PlayerState.builder()
                .currentPrayer(20)
                .maxPrayer(70)
                .build();

        assertTrue(state.isPrayerBelow(0.5));
        assertFalse(state.isPrayerBelow(0.2));
    }

    @Test
    public void testIsRunEnergyAbove() {
        PlayerState state = PlayerState.builder()
                .runEnergy(50)
                .build();

        assertTrue(state.isRunEnergyAbove(40));
        assertFalse(state.isRunEnergyAbove(60));
    }

    // ========================================================================
    // Status Effects
    // ========================================================================

    @Test
    public void testIsSkulled_TrueWithSkullIcon() {
        PlayerState state = PlayerState.builder()
                .skullIcon(0) // 0 = white skull
                .build();

        assertTrue(state.isSkulled());
    }

    @Test
    public void testIsSkulled_FalseWithNoSkull() {
        PlayerState state = PlayerState.builder()
                .skullIcon(-1) // -1 = no skull
                .build();

        assertFalse(state.isSkulled());
    }

    @Test
    public void testHasPoisonEffect() {
        PlayerState poisoned = PlayerState.builder()
                .isPoisoned(true)
                .isVenomed(false)
                .build();

        PlayerState venomed = PlayerState.builder()
                .isPoisoned(false)
                .isVenomed(true)
                .build();

        PlayerState healthy = PlayerState.builder()
                .isPoisoned(false)
                .isVenomed(false)
                .build();

        assertTrue(poisoned.hasPoisonEffect());
        assertTrue(venomed.hasPoisonEffect());
        assertFalse(healthy.hasPoisonEffect());
    }

    // ========================================================================
    // Spellbook
    // ========================================================================

    @Test
    public void testGetSpellbookName() {
        assertEquals("Standard", PlayerState.builder().spellbook(0).build().getSpellbookName());
        assertEquals("Ancient", PlayerState.builder().spellbook(1).build().getSpellbookName());
        assertEquals("Lunar", PlayerState.builder().spellbook(2).build().getSpellbookName());
        assertEquals("Arceuus", PlayerState.builder().spellbook(3).build().getSpellbookName());
        assertEquals("Unknown", PlayerState.builder().spellbook(99).build().getSpellbookName());
    }

    // ========================================================================
    // Position Checks
    // ========================================================================

    @Test
    public void testIsAtTile_Coordinates() {
        PlayerState state = PlayerState.builder()
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .build();

        assertTrue(state.isAtTile(3200, 3200));
        assertFalse(state.isAtTile(3201, 3200));
    }

    @Test
    public void testIsAtTile_WorldPoint() {
        WorldPoint pos = new WorldPoint(3200, 3200, 0);
        PlayerState state = PlayerState.builder()
                .worldPosition(pos)
                .build();

        assertTrue(state.isAtTile(pos));
        assertFalse(state.isAtTile(new WorldPoint(3201, 3200, 0)));
    }

    @Test
    public void testIsAtTile_NullPosition() {
        PlayerState state = PlayerState.builder()
                .worldPosition(null)
                .build();

        assertFalse(state.isAtTile(3200, 3200));
        assertFalse(state.isAtTile(new WorldPoint(3200, 3200, 0)));
    }

    @Test
    public void testIsInArea() {
        PlayerState state = PlayerState.builder()
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .build();

        assertTrue(state.isInArea(3100, 3100, 3300, 3300));
        assertTrue(state.isInArea(3200, 3200, 3200, 3200)); // Exact match
        assertFalse(state.isInArea(3201, 3201, 3300, 3300)); // Just outside
    }

    @Test
    public void testIsInArea_NullPosition() {
        PlayerState state = PlayerState.builder()
                .worldPosition(null)
                .build();

        assertFalse(state.isInArea(0, 0, 10000, 10000));
    }

    @Test
    public void testDistanceTo() {
        PlayerState state = PlayerState.builder()
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .build();

        assertEquals(0, state.distanceTo(new WorldPoint(3200, 3200, 0)));
        assertEquals(10, state.distanceTo(new WorldPoint(3210, 3200, 0)));
    }

    @Test
    public void testDistanceTo_InvalidPosition() {
        PlayerState state = PlayerState.builder()
                .worldPosition(null)
                .build();

        assertEquals(-1, state.distanceTo(new WorldPoint(3200, 3200, 0)));
    }

    @Test
    public void testDistanceTo_NullTarget() {
        PlayerState state = PlayerState.builder()
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .build();

        assertEquals(-1, state.distanceTo(null));
    }
}

