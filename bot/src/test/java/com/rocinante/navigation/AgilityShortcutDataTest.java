package com.rocinante.navigation;

import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.AgilityShortcut;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for AgilityShortcutData - RuneLite AgilityShortcut integration.
 */
public class AgilityShortcutDataTest {

    @Mock
    private Client client;

    private AgilityShortcutData shortcutData;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        shortcutData = new AgilityShortcutData(client);
    }

    // ========================================================================
    // Initialization Tests
    // ========================================================================

    @Test
    public void testShortcutsLoaded() {
        assertTrue("Should load shortcuts from RuneLite",
                shortcutData.getShortcutCount() > 0);
    }

    @Test
    public void testAllRuneLiteShortcutsLoaded() {
        // AgilityShortcut enum should have all entries loaded
        int runeLiteCount = AgilityShortcut.values().length;
        assertEquals("Should load all RuneLite shortcuts",
                runeLiteCount, shortcutData.getShortcutCount());
    }

    @Test
    public void testObjectIdsIndexed() {
        assertTrue("Should have object IDs indexed",
                shortcutData.getObjectIdCount() > 0);
    }

    // ========================================================================
    // Lookup Tests
    // ========================================================================

    @Test
    public void testGetAllShortcuts() {
        List<ObstacleDefinition> all = shortcutData.getAllShortcuts();

        assertNotNull(all);
        assertFalse("Should have shortcuts", all.isEmpty());

        // All should be agility shortcuts
        for (ObstacleDefinition def : all) {
            assertEquals("All should be AGILITY_SHORTCUT type",
                    ObstacleDefinition.ObstacleType.AGILITY_SHORTCUT, def.getType());
        }
    }

    @Test
    public void testGetDefinitionByObjectId() {
        // Pick a known shortcut object ID from AgilityShortcut
        // Grand Exchange shortcut pipe has object ID 16235
        int geShortcutId = 16235;

        if (shortcutData.isKnownShortcut(geShortcutId)) {
            ObstacleDefinition def = shortcutData.getDefinitionByObjectId(geShortcutId);
            assertNotNull("Should find GE shortcut", def);
            assertEquals(ObstacleDefinition.ObstacleType.AGILITY_SHORTCUT, def.getType());
        }
    }

    @Test
    public void testIsKnownShortcut() {
        // Test with a known shortcut ID (if available)
        List<ObstacleDefinition> all = shortcutData.getAllShortcuts();
        if (!all.isEmpty() && all.get(0).getObjectIds() != null && !all.get(0).getObjectIds().isEmpty()) {
            int knownId = all.get(0).getObjectIds().get(0);
            assertTrue("Should recognize known shortcut ID",
                    shortcutData.isKnownShortcut(knownId));
        }

        // Test with definitely unknown ID
        assertFalse("Should not recognize unknown ID",
                shortcutData.isKnownShortcut(999999999));
    }

    @Test
    public void testGetShortcutsForObjectId() {
        List<ObstacleDefinition> all = shortcutData.getAllShortcuts();
        if (!all.isEmpty() && all.get(0).getObjectIds() != null && !all.get(0).getObjectIds().isEmpty()) {
            int knownId = all.get(0).getObjectIds().get(0);
            List<AgilityShortcut> shortcuts = shortcutData.getShortcutsForObjectId(knownId);

            assertNotNull(shortcuts);
            assertFalse("Should find shortcuts for known ID", shortcuts.isEmpty());
        }
    }

    // ========================================================================
    // Level Filtering Tests
    // ========================================================================

    @Test
    public void testGetUsableShortcuts() {
        List<ObstacleDefinition> usableAtLevel1 = shortcutData.getUsableShortcuts(1);
        List<ObstacleDefinition> usableAtLevel99 = shortcutData.getUsableShortcuts(99);

        // Level 99 should unlock more shortcuts than level 1
        assertTrue("Level 99 should have at least as many shortcuts as level 1",
                usableAtLevel99.size() >= usableAtLevel1.size());

        // All level 1 shortcuts should require level 1 or less
        for (ObstacleDefinition def : usableAtLevel1) {
            assertTrue("All should be usable at level 1",
                    def.getRequiredAgilityLevel() <= 1);
        }
    }

    @Test
    public void testGetSafeShortcuts() {
        List<ObstacleDefinition> safeAt50 = shortcutData.getSafeShortcuts(50);
        List<ObstacleDefinition> safeAt99 = shortcutData.getSafeShortcuts(99);

        // Level 99 should have more safe shortcuts
        assertTrue("Level 99 should have more safe shortcuts",
                safeAt99.size() >= safeAt50.size());

        // All safe shortcuts should be attemptable
        for (ObstacleDefinition def : safeAt50) {
            assertTrue("All safe shortcuts should be attemptable",
                    def.canAttempt(50));
        }
    }

    @Test
    public void testGetSafeShortcutsWithThreshold() {
        // Test with different risk thresholds
        List<ObstacleDefinition> lenientThreshold = shortcutData.getSafeShortcuts(50, 0.20);
        List<ObstacleDefinition> strictThreshold = shortcutData.getSafeShortcuts(50, 0.05);

        // Lenient threshold should allow more shortcuts
        assertTrue("Lenient threshold should allow more shortcuts",
                lenientThreshold.size() >= strictThreshold.size());
    }

    // ========================================================================
    // Level Grouping Tests
    // ========================================================================

    @Test
    public void testGetShortcutsByLevel() {
        Map<Integer, List<ObstacleDefinition>> byLevel = shortcutData.getShortcutsByLevel();

        assertNotNull(byLevel);
        assertFalse("Should have shortcuts grouped", byLevel.isEmpty());

        // Each group should have shortcuts at that level
        for (Map.Entry<Integer, List<ObstacleDefinition>> entry : byLevel.entrySet()) {
            int level = entry.getKey();
            for (ObstacleDefinition def : entry.getValue()) {
                assertEquals("Shortcuts should be at correct level",
                        level, def.getRequiredAgilityLevel());
            }
        }
    }

    @Test
    public void testGetMaxRequiredLevel() {
        int maxLevel = shortcutData.getMaxRequiredLevel();

        assertTrue("Max level should be positive", maxLevel > 0);
        assertTrue("Max level should not exceed 99", maxLevel <= 99);
    }

    // ========================================================================
    // Location Search Tests
    // ========================================================================

    @Test
    public void testFindShortcutsNear() {
        // Find shortcuts near Varrock (central OSRS location)
        WorldPoint varrock = new WorldPoint(3213, 3424, 0);
        List<ObstacleDefinition> nearby = shortcutData.findShortcutsNear(varrock, 100);

        // May or may not find any depending on shortcut locations
        assertNotNull(nearby);
    }

    @Test
    public void testFindShortcutsNearEmptyArea() {
        // Far from any shortcuts
        WorldPoint middle = new WorldPoint(0, 0, 0);
        List<ObstacleDefinition> nearby = shortcutData.findShortcutsNear(middle, 10);

        assertNotNull(nearby);
        // May be empty for areas without shortcuts
    }

    // ========================================================================
    // Definition Quality Tests
    // ========================================================================

    @Test
    public void testDefinitionHasRequiredFields() {
        List<ObstacleDefinition> all = shortcutData.getAllShortcuts();

        int shortcutsWithIds = 0;
        for (ObstacleDefinition def : all) {
            assertNotNull("Name should not be null", def.getName());
            assertNotNull("Type should not be null", def.getType());
            assertNotNull("Action should not be null", def.getAction());
            assertNotNull("Object IDs should not be null", def.getObjectIds());
            
            // Some shortcuts may have no object IDs (edge cases in RuneLite data)
            if (!def.getObjectIds().isEmpty()) {
                shortcutsWithIds++;
            }
        }
        
        // Most shortcuts should have object IDs
        assertTrue("Most shortcuts should have object IDs",
                shortcutsWithIds > all.size() / 2);
    }

    @Test
    public void testDefinitionSuccessRates() {
        List<ObstacleDefinition> all = shortcutData.getAllShortcuts();

        for (ObstacleDefinition def : all) {
            double baseRate = def.getBaseSuccessRate();
            assertTrue("Base success rate should be between 0 and 1",
                    baseRate >= 0.0 && baseRate <= 1.0);
        }
    }

    @Test
    public void testDefinitionTraversalCosts() {
        List<ObstacleDefinition> all = shortcutData.getAllShortcuts();

        for (ObstacleDefinition def : all) {
            int cost = def.getTraversalCostTicks();
            assertTrue("Traversal cost should be positive", cost > 0);
        }
    }

    // ========================================================================
    // Integration Tests
    // ========================================================================

    @Test
    public void testSuccessRateCalculation() {
        // Test success rate calculation for shortcuts
        List<ObstacleDefinition> all = shortcutData.getAllShortcuts();

        for (ObstacleDefinition def : all) {
            int reqLevel = def.getRequiredAgilityLevel();

            // Below level = 0% success
            if (reqLevel > 1) {
                assertEquals("Success should be 0 below level",
                        0.0, def.calculateSuccessRate(reqLevel - 1), 0.001);
            }

            // At level = base success rate
            double atLevel = def.calculateSuccessRate(reqLevel);
            assertEquals("Success at level should be base rate",
                    def.getBaseSuccessRate(), atLevel, 0.001);

            // Well above level should approach 100%
            double highLevel = def.calculateSuccessRate(99);
            assertTrue("Success at 99 should be >= base rate",
                    highLevel >= def.getBaseSuccessRate());
        }
    }
}

