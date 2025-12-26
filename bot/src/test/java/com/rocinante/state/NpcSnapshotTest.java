package com.rocinante.state;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for NpcSnapshot class.
 */
public class NpcSnapshotTest {

    // ========================================================================
    // Health Percentage Tests
    // ========================================================================

    @Test
    public void testGetHealthPercent_FullHealth() {
        NpcSnapshot npc = createNpcWithHealth(30, 30);
        assertEquals(1.0, npc.getHealthPercent(), 0.001);
    }

    @Test
    public void testGetHealthPercent_HalfHealth() {
        NpcSnapshot npc = createNpcWithHealth(15, 30);
        assertEquals(0.5, npc.getHealthPercent(), 0.001);
    }

    @Test
    public void testGetHealthPercent_NoHealthBar() {
        NpcSnapshot npc = createNpcWithHealth(-1, -1);
        assertEquals(1.0, npc.getHealthPercent(), 0.001); // Assumes full health
    }

    @Test
    public void testGetHealthPercent_ZeroScale() {
        NpcSnapshot npc = createNpcWithHealth(10, 0);
        assertEquals(1.0, npc.getHealthPercent(), 0.001);
    }

    @Test
    public void testIsHealthBarVisible() {
        NpcSnapshot visible = createNpcWithHealth(15, 30);
        assertTrue(visible.isHealthBarVisible());

        NpcSnapshot notVisible = createNpcWithHealth(-1, -1);
        assertFalse(notVisible.isHealthBarVisible());
    }

    @Test
    public void testIsHealthBelow() {
        NpcSnapshot halfHealth = createNpcWithHealth(15, 30);

        assertTrue(halfHealth.isHealthBelow(0.6));
        assertFalse(halfHealth.isHealthBelow(0.4));
    }

    // ========================================================================
    // Animation Tests
    // ========================================================================

    @Test
    public void testIsAnimating() {
        NpcSnapshot animating = NpcSnapshot.builder()
                .index(0)
                .id(100)
                .animationId(808)
                .build();

        assertTrue(animating.isAnimating());

        NpcSnapshot notAnimating = NpcSnapshot.builder()
                .index(0)
                .id(100)
                .animationId(-1)
                .build();

        assertFalse(notAnimating.isAnimating());
    }

    @Test
    public void testIsAnimatingSpecificId() {
        NpcSnapshot npc = NpcSnapshot.builder()
                .index(0)
                .id(100)
                .animationId(808)
                .build();

        assertTrue(npc.isAnimating(808));
        assertFalse(npc.isAnimating(809));
    }

    // ========================================================================
    // Distance Tests
    // ========================================================================

    @Test
    public void testDistanceTo() {
        NpcSnapshot npc = NpcSnapshot.builder()
                .index(0)
                .id(100)
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .build();

        WorldPoint target = new WorldPoint(3210, 3200, 0);
        assertEquals(10, npc.distanceTo(target));
    }

    @Test
    public void testDistanceTo_SamePosition() {
        NpcSnapshot npc = NpcSnapshot.builder()
                .index(0)
                .id(100)
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .build();

        WorldPoint target = new WorldPoint(3200, 3200, 0);
        assertEquals(0, npc.distanceTo(target));
    }

    @Test
    public void testDistanceTo_NullPosition() {
        NpcSnapshot npc = NpcSnapshot.builder()
                .index(0)
                .id(100)
                .worldPosition(null)
                .build();

        assertEquals(-1, npc.distanceTo(new WorldPoint(3200, 3200, 0)));
    }

    @Test
    public void testDistanceTo_NullTarget() {
        NpcSnapshot npc = NpcSnapshot.builder()
                .index(0)
                .id(100)
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .build();

        assertEquals(-1, npc.distanceTo(null));
    }

    @Test
    public void testIsWithinDistance() {
        NpcSnapshot npc = NpcSnapshot.builder()
                .index(0)
                .id(100)
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .build();

        WorldPoint target = new WorldPoint(3205, 3200, 0);

        assertTrue(npc.isWithinDistance(target, 10));
        assertTrue(npc.isWithinDistance(target, 5));
        assertFalse(npc.isWithinDistance(target, 3));
    }

    // ========================================================================
    // Targeting Tests
    // ========================================================================

    @Test
    public void testTargetingPlayer() {
        NpcSnapshot targeting = NpcSnapshot.builder()
                .index(0)
                .id(100)
                .targetingPlayer(true)
                .build();

        assertTrue(targeting.isTargetingPlayer());

        NpcSnapshot notTargeting = NpcSnapshot.builder()
                .index(0)
                .id(100)
                .targetingPlayer(false)
                .build();

        assertFalse(notTargeting.isTargetingPlayer());
    }

    // ========================================================================
    // Dead Check Test
    // ========================================================================

    @Test
    public void testIsDead() {
        NpcSnapshot dead = NpcSnapshot.builder()
                .index(0)
                .id(100)
                .isDead(true)
                .build();

        assertTrue(dead.isDead());

        NpcSnapshot alive = NpcSnapshot.builder()
                .index(0)
                .id(100)
                .isDead(false)
                .build();

        assertFalse(alive.isDead());
    }

    // ========================================================================
    // Summary Test
    // ========================================================================

    @Test
    public void testGetSummary() {
        NpcSnapshot npc = NpcSnapshot.builder()
                .index(42)
                .id(100)
                .name("Goblin")
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .healthRatio(15)
                .healthScale(30)
                .targetingPlayer(true)
                .build();

        String summary = npc.getSummary();
        assertTrue(summary.contains("Goblin"));
        assertTrue(summary.contains("id=100"));
        assertTrue(summary.contains("idx=42"));
        assertTrue(summary.contains("50%")); // Health percentage
        assertTrue(summary.contains("targeting=true"));
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private NpcSnapshot createNpcWithHealth(int healthRatio, int healthScale) {
        return NpcSnapshot.builder()
                .index(0)
                .id(100)
                .name("Test NPC")
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .healthRatio(healthRatio)
                .healthScale(healthScale)
                .animationId(-1)
                .interactingIndex(-1)
                .targetingPlayer(false)
                .isDead(false)
                .size(1)
                .build();
    }
}

