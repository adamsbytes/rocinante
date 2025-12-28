package com.rocinante.slayer;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for SlayerLocation class.
 */
public class SlayerLocationTest {

    // ========================================================================
    // Basic Properties Tests
    // ========================================================================

    @Test
    public void testBasicProperties() {
        SlayerLocation location = createTestLocation();

        assertEquals("Catacombs of Kourend", location.getName());
        assertEquals(new WorldPoint(1698, 10098, 0), location.getCenter());
        assertEquals(20, location.getRadius());
        assertTrue(location.isMultiCombat());
        assertEquals(5, location.getHcimSafetyRating());
    }

    // ========================================================================
    // HCIM Safety Tests
    // ========================================================================

    @Test
    public void testIsSafeForHcim() {
        SlayerLocation safeLocation = SlayerLocation.builder()
                .name("Safe Spot")
                .center(new WorldPoint(3200, 3200, 0))
                .hcimSafetyRating(8)
                .multiCombat(false)
                .build();

        assertTrue(safeLocation.isSafeForHcim(6));
        assertTrue(safeLocation.isSafeForHcim(8));
        assertFalse(safeLocation.isSafeForHcim(9));

        SlayerLocation dangerousLocation = SlayerLocation.builder()
                .name("Wilderness")
                .center(new WorldPoint(3100, 3600, 0))
                .hcimSafetyRating(2)
                .multiCombat(true)
                .wilderness(true)
                .build();

        assertFalse(dangerousLocation.isSafeForHcim(6));
        assertTrue(dangerousLocation.isSafeForHcim(2));
    }

    // ========================================================================
    // Konar Region Tests
    // ========================================================================

    @Test
    public void testMatchesKonarRegion() {
        SlayerLocation catacombLocation = SlayerLocation.builder()
                .name("Catacombs")
                .center(new WorldPoint(1698, 10098, 0))
                .konarRegion("Catacombs of Kourend")
                .build();

        assertTrue(catacombLocation.matchesKonarRegion("Catacombs of Kourend"));
        assertTrue(catacombLocation.matchesKonarRegion("catacombs of kourend")); // Case insensitive
        assertFalse(catacombLocation.matchesKonarRegion("Slayer Tower"));

        SlayerLocation nonKonarLocation = SlayerLocation.builder()
                .name("Generic Spot")
                .center(new WorldPoint(3200, 3200, 0))
                .build();

        // Non-Konar location matches any non-Konar task
        assertTrue(nonKonarLocation.matchesKonarRegion(null));
    }

    // ========================================================================
    // Required Items Tests
    // ========================================================================

    @Test
    public void testHasRequiredItems() {
        SlayerLocation withItems = SlayerLocation.builder()
                .name("Cave")
                .center(new WorldPoint(3200, 3200, 0))
                .requiredItems(Set.of(954)) // Rope
                .build();

        assertTrue(withItems.hasRequiredItems());

        SlayerLocation noItems = SlayerLocation.builder()
                .name("Surface")
                .center(new WorldPoint(3200, 3200, 0))
                .build();

        assertFalse(noItems.hasRequiredItems());
    }

    // ========================================================================
    // Cannon Tests
    // ========================================================================

    @Test
    public void testSupportsCannon() {
        SlayerLocation withCannon = SlayerLocation.builder()
                .name("Kalphite Cave")
                .center(new WorldPoint(3200, 3200, 0))
                .cannonSpot(new WorldPoint(3205, 3205, 0))
                .build();

        assertTrue(withCannon.supportsCanon());

        SlayerLocation noCannon = SlayerLocation.builder()
                .name("Slayer Tower")
                .center(new WorldPoint(3436, 3548, 0))
                .build();

        assertFalse(noCannon.supportsCanon());
    }

    // ========================================================================
    // Quest Requirement Tests
    // ========================================================================

    @Test
    public void testRequiresQuest() {
        SlayerLocation questRequired = SlayerLocation.builder()
                .name("Mos Le'Harmless")
                .center(new WorldPoint(3750, 9373, 0))
                .requiredQuest("Cabin Fever")
                .build();

        assertTrue(questRequired.requiresQuest());

        SlayerLocation noQuest = SlayerLocation.builder()
                .name("Public Area")
                .center(new WorldPoint(3200, 3200, 0))
                .build();

        assertFalse(noQuest.requiresQuest());
    }

    // ========================================================================
    // Bounding Box Tests
    // ========================================================================

    @Test
    public void testGetBoundingBox() {
        SlayerLocation location = SlayerLocation.builder()
                .name("Test")
                .center(new WorldPoint(3200, 3200, 0))
                .radius(10)
                .build();

        WorldPoint[] bounds = location.getBoundingBox();
        assertEquals(2, bounds.length);

        // SW corner
        assertEquals(3190, bounds[0].getX());
        assertEquals(3190, bounds[0].getY());

        // NE corner
        assertEquals(3210, bounds[1].getX());
        assertEquals(3210, bounds[1].getY());
    }

    @Test
    public void testGetBoundingBox_ZeroRadius() {
        SlayerLocation location = SlayerLocation.builder()
                .name("Point")
                .center(new WorldPoint(3200, 3200, 0))
                .radius(0)
                .build();

        WorldPoint[] bounds = location.getBoundingBox();
        assertEquals(1, bounds.length);
        assertEquals(new WorldPoint(3200, 3200, 0), bounds[0]);
    }

    // ========================================================================
    // Contains Tests
    // ========================================================================

    @Test
    public void testContains() {
        SlayerLocation location = SlayerLocation.builder()
                .name("Test Area")
                .center(new WorldPoint(3200, 3200, 0))
                .radius(10)
                .build();

        assertTrue(location.contains(new WorldPoint(3200, 3200, 0))); // Center
        assertTrue(location.contains(new WorldPoint(3205, 3205, 0))); // Within radius
        assertFalse(location.contains(new WorldPoint(3220, 3220, 0))); // Outside radius
        assertFalse(location.contains(new WorldPoint(3200, 3200, 1))); // Different plane
        assertFalse(location.contains(null));
    }

    // ========================================================================
    // Estimated Kills Per Hour Tests
    // ========================================================================

    @Test
    public void testGetEstimatedKillsPerHour() {
        SlayerLocation highDensity = SlayerLocation.builder()
                .name("High Density")
                .center(new WorldPoint(3200, 3200, 0))
                .monsterDensity(15)
                .multiCombat(true)
                .build();

        // Base 60 + density bonus (15 * 5) + multi bonus (30) = 165
        assertEquals(165, highDensity.getEstimatedKillsPerHour());

        SlayerLocation lowDensity = SlayerLocation.builder()
                .name("Low Density")
                .center(new WorldPoint(3200, 3200, 0))
                .monsterDensity(3)
                .multiCombat(false)
                .build();

        // Base 60 + density bonus (3 * 5) + no multi bonus = 75
        assertEquals(75, lowDensity.getEstimatedKillsPerHour());
    }

    // ========================================================================
    // ToString Test
    // ========================================================================

    @Test
    public void testToString() {
        SlayerLocation location = SlayerLocation.builder()
                .name("Catacombs of Kourend")
                .center(new WorldPoint(1698, 10098, 0))
                .konarRegion("Catacombs of Kourend")
                .multiCombat(true)
                .hcimSafetyRating(5)
                .build();

        String str = location.toString();
        assertTrue(str.contains("Catacombs of Kourend"));
        assertTrue(str.contains("MULTI"));
        assertTrue(str.contains("safety=5"));
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private SlayerLocation createTestLocation() {
        return SlayerLocation.builder()
                .name("Catacombs of Kourend")
                .center(new WorldPoint(1698, 10098, 0))
                .radius(20)
                .nearestBank("kourend_castle_bank")
                .nearestTeleport("xeric_heart")
                .multiCombat(true)
                .hcimSafetyRating(5)
                .konarRegion("Catacombs of Kourend")
                .monsterDensity(10)
                .build();
    }
}

