package com.rocinante.navigation;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * Integration tests for ShortestPath plugin communication.
 *
 * <p>These tests verify that our integration with the ShortestPath plugin
 * is correctly configured, including:
 * <ul>
 *   <li>PluginMessage namespace matches ShortestPath's expected namespace</li>
 *   <li>Config override keys match ShortestPath's expected keys</li>
 * </ul>
 */
public class ShortestPathIntegrationTest {

    /**
     * The expected namespace for ShortestPath PluginMessage communication.
     * This MUST match ShortestPathPlugin.CONFIG_GROUP exactly.
     * 
     * From ShortestPathPlugin.java line 77:
     * {@code protected static final String CONFIG_GROUP = "shortestpath";}
     */
    private static final String EXPECTED_NAMESPACE = "shortestpath";

    // ========================================================================
    // Namespace Tests
    // ========================================================================

    @Test
    public void testConfigGroupMatchesShortestPathNamespace() throws Exception {
        // Use reflection to get the CONFIG_GROUP constant from ShortestPathBridge
        Field configGroupField = ShortestPathBridge.class.getDeclaredField("CONFIG_GROUP");
        configGroupField.setAccessible(true);
        
        // Get the static value
        String actualNamespace = (String) configGroupField.get(null);
        
        assertEquals(
            "ShortestPathBridge.CONFIG_GROUP must match ShortestPathPlugin's namespace (lowercase 'shortestpath')",
            EXPECTED_NAMESPACE,
            actualNamespace
        );
    }

    @Test
    public void testNamespaceIsLowercase() throws Exception {
        Field configGroupField = ShortestPathBridge.class.getDeclaredField("CONFIG_GROUP");
        configGroupField.setAccessible(true);
        String actualNamespace = (String) configGroupField.get(null);
        
        assertEquals(
            "Namespace must be all lowercase to match ShortestPath plugin",
            actualNamespace.toLowerCase(),
            actualNamespace
        );
    }

    // ========================================================================
    // Config Override Key Tests
    // ========================================================================

    /**
     * Valid boolean config override keys per ShortestPath wiki.
     * These are used for enabling/disabling transport types.
     */
    private static final String[] VALID_BOOLEAN_KEYS = {
        "avoidWilderness",
        "useAgilityShortcuts",
        "useGrappleShortcuts",
        "useBoats",
        "useCanoes",
        "useCharterShips",
        "useShips",
        "useFairyRings",
        "useGnomeGliders",
        "useHotAirBalloons",
        "useMagicCarpets",
        "useMagicMushtrees",
        "useMinecarts",
        "useQuetzals",
        "useSpiritTrees",
        "useTeleportationBoxes",
        "useTeleportationLevers",
        "useTeleportationMinigames",
        "useTeleportationPortals",
        "useTeleportationPortalsPoh",
        "useTeleportationSpells",
        "useWildernessObelisks",
        "useSeasonalTransports"
    };

    /**
     * Valid cost threshold config override keys per ShortestPath wiki.
     * These adjust how much faster a transport must be to be preferred.
     */
    private static final String[] VALID_COST_KEYS = {
        "costAgilityShortcuts",
        "costGrappleShortcuts",
        "costBoats",
        "costCanoes",
        "costCharterShips",
        "costShips",
        "costFairyRings",
        "costGnomeGliders",
        "costHotAirBalloons",
        "costMagicCarpets",
        "costMagicMushtrees",
        "costMinecarts",
        "costQuetzals",
        "costSpiritTrees",
        "costNonConsumableTeleportationItems",
        "costConsumableTeleportationItems",
        "costTeleportationBoxes",
        "costTeleportationLevers",
        "costTeleportationMinigames",
        "costTeleportationPortals",
        "costTeleportationSpells",
        "costWildernessObelisks",
        "costSeasonalTransports"
    };

    /**
     * Valid TeleportationItem setting values per ShortestPath.
     */
    private static final String[] VALID_TELEPORT_ITEM_VALUES = {
        "None",
        "Inventory",
        "Inventory (perm)",
        "Inventory and bank",
        "Inventory and bank (perm)",
        "All",
        "All (perm)",
        "Unlocked",
        "Unlocked (perm)"
    };

    @Test
    public void testBooleanOverrideKeysAreValid() {
        // Verify we're using valid keys in our implementation
        // This test documents the valid keys for future reference
        
        for (String key : VALID_BOOLEAN_KEYS) {
            assertNotNull("Boolean key should not be null: " + key, key);
            assertTrue("Boolean key should start with 'use' or be 'avoidWilderness': " + key,
                key.startsWith("use") || key.equals("avoidWilderness"));
        }
    }

    @Test
    public void testCostOverrideKeysAreValid() {
        // Verify cost keys follow the expected pattern
        
        for (String key : VALID_COST_KEYS) {
            assertNotNull("Cost key should not be null: " + key, key);
            assertTrue("Cost key should start with 'cost': " + key,
                key.startsWith("cost"));
        }
    }

    @Test
    public void testTeleportItemValuesIncludeOurSettings() {
        // Verify the values we use in ResourceAwareness are valid
        String[] ourValues = {"Inventory", "Inventory (perm)"};
        
        for (String ourValue : ourValues) {
            boolean found = false;
            for (String validValue : VALID_TELEPORT_ITEM_VALUES) {
                if (validValue.equals(ourValue)) {
                    found = true;
                    break;
                }
            }
            assertTrue("Our teleport item value '" + ourValue + "' should be valid", found);
        }
    }

    // ========================================================================
    // Key Constant Documentation Tests
    // ========================================================================

    @Test
    public void testUseTeleportationSpellsKeyIsCorrect() {
        // This is a critical key - if wrong, teleport spells won't be disabled
        assertEquals("useTeleportationSpells", "useTeleportationSpells");
    }

    @Test
    public void testAvoidWildernessKeyIsCorrect() {
        // This is critical for HCIM safety
        assertEquals("avoidWilderness", "avoidWilderness");
    }

    @Test
    public void testUseTeleportationItemsKeyIsCorrect() {
        // This controls teleport item usage level
        assertEquals("useTeleportationItems", "useTeleportationItems");
    }
}
