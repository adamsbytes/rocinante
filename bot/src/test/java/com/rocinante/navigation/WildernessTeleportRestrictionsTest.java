package com.rocinante.navigation;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link WildernessTeleportRestrictions} utility class.
 *
 * Validates wilderness level calculations and teleport restriction logic.
 * Critical for HCIM safety and navigation decisions.
 */
public class WildernessTeleportRestrictionsTest {

    // ========================================================================
    // Constants Tests
    // ========================================================================

    @Test
    public void testStandardTeleportLimit_Is20() {
        assertEquals("Standard teleport limit should be 20", 
                20, WildernessTeleportRestrictions.STANDARD_TELEPORT_LIMIT);
    }

    @Test
    public void testEnhancedTeleportLimit_Is30() {
        assertEquals("Enhanced teleport limit should be 30", 
                30, WildernessTeleportRestrictions.ENHANCED_TELEPORT_LIMIT);
    }

    @Test
    public void testWildernessYStart_Is3520() {
        assertEquals("Wilderness Y coordinate should start at 3520", 
                3520, WildernessTeleportRestrictions.WILDERNESS_Y_START);
    }

    @Test
    public void testMaxWildernessLevel_Is56() {
        assertEquals("Max wilderness level should be 56", 
                56, WildernessTeleportRestrictions.MAX_WILDERNESS_LEVEL);
    }

    @Test
    public void testLevel30TeleportItems_NotEmpty() {
        assertFalse("Level 30 teleport items set should not be empty", 
                WildernessTeleportRestrictions.LEVEL_30_TELEPORT_ITEMS.isEmpty());
    }

    // ========================================================================
    // getWildernessLevel() - Basic Tests
    // ========================================================================

    @Test
    public void testGetWildernessLevel_Null_ReturnsZero() {
        assertEquals("Null point should return 0", 
                0, WildernessTeleportRestrictions.getWildernessLevel(null));
    }

    @Test
    public void testGetWildernessLevel_BelowWilderness_ReturnsZero() {
        // Lumbridge (well below wilderness)
        WorldPoint lumbridge = new WorldPoint(3222, 3218, 0);
        assertEquals("Lumbridge should be level 0", 
                0, WildernessTeleportRestrictions.getWildernessLevel(lumbridge));
    }

    @Test
    public void testGetWildernessLevel_JustBelowWilderness_ReturnsZero() {
        // Just below wilderness line (Y=3519)
        WorldPoint justBelow = new WorldPoint(3100, 3519, 0);
        assertEquals("Y=3519 should be level 0", 
                0, WildernessTeleportRestrictions.getWildernessLevel(justBelow));
    }

    @Test
    public void testGetWildernessLevel_AtWildernessStart_ReturnsOne() {
        // Exactly at wilderness start (Y=3520)
        WorldPoint wildyStart = new WorldPoint(3100, 3520, 0);
        assertEquals("Y=3520 should be level 1", 
                1, WildernessTeleportRestrictions.getWildernessLevel(wildyStart));
    }

    @Test
    public void testGetWildernessLevel_Level2() {
        // Level 2 wilderness (Y=3528, which is 3520 + 8)
        WorldPoint level2 = new WorldPoint(3100, 3528, 0);
        assertEquals("Y=3528 should be level 2", 
                2, WildernessTeleportRestrictions.getWildernessLevel(level2));
    }

    @Test
    public void testGetWildernessLevel_Level10() {
        // Level 10 wilderness (Y=3520 + 72 = 3592)
        WorldPoint level10 = new WorldPoint(3100, 3592, 0);
        assertEquals("Y=3592 should be level 10", 
                10, WildernessTeleportRestrictions.getWildernessLevel(level10));
    }

    @Test
    public void testGetWildernessLevel_Level20() {
        // Level 20 wilderness (Y=3520 + 152 = 3672)
        WorldPoint level20 = new WorldPoint(3100, 3672, 0);
        assertEquals("Y=3672 should be level 20", 
                20, WildernessTeleportRestrictions.getWildernessLevel(level20));
    }

    @Test
    public void testGetWildernessLevel_Level30() {
        // Level 30 wilderness (Y=3520 + 232 = 3752)
        WorldPoint level30 = new WorldPoint(3100, 3752, 0);
        assertEquals("Y=3752 should be level 30", 
                30, WildernessTeleportRestrictions.getWildernessLevel(level30));
    }

    @Test
    public void testGetWildernessLevel_Level50() {
        // Level 50 wilderness (Y=3520 + 392 = 3912)
        WorldPoint level50 = new WorldPoint(3100, 3912, 0);
        assertEquals("Y=3912 should be level 50", 
                50, WildernessTeleportRestrictions.getWildernessLevel(level50));
    }

    @Test
    public void testGetWildernessLevel_MaxLevel() {
        // Very far north - should cap at max level
        WorldPoint veryFarNorth = new WorldPoint(3100, 4500, 0);
        assertEquals("Very far north should cap at max level", 
                WildernessTeleportRestrictions.MAX_WILDERNESS_LEVEL, 
                WildernessTeleportRestrictions.getWildernessLevel(veryFarNorth));
    }

    // ========================================================================
    // getWildernessLevel() - Plane Tests
    // ========================================================================

    @Test
    public void testGetWildernessLevel_Plane1_ReturnsZero() {
        // Same Y as wilderness but on plane 1
        WorldPoint plane1 = new WorldPoint(3100, 3600, 1);
        assertEquals("Plane 1 should return 0", 
                0, WildernessTeleportRestrictions.getWildernessLevel(plane1));
    }

    @Test
    public void testGetWildernessLevel_Plane2_ReturnsZero() {
        // Same Y as wilderness but on plane 2
        WorldPoint plane2 = new WorldPoint(3100, 3600, 2);
        assertEquals("Plane 2 should return 0", 
                0, WildernessTeleportRestrictions.getWildernessLevel(plane2));
    }

    // ========================================================================
    // canUseStandardTeleport() Tests
    // ========================================================================

    @Test
    public void testCanUseStandardTeleport_NotInWilderness_True() {
        WorldPoint safe = new WorldPoint(3222, 3218, 0);
        assertTrue("Should be able to teleport outside wilderness", 
                WildernessTeleportRestrictions.canUseStandardTeleport(safe));
    }

    @Test
    public void testCanUseStandardTeleport_Level1_True() {
        WorldPoint level1 = new WorldPoint(3100, 3520, 0);
        assertTrue("Should be able to teleport at level 1", 
                WildernessTeleportRestrictions.canUseStandardTeleport(level1));
    }

    @Test
    public void testCanUseStandardTeleport_Level20_True() {
        WorldPoint level20 = new WorldPoint(3100, 3672, 0);
        assertTrue("Should be able to teleport at level 20", 
                WildernessTeleportRestrictions.canUseStandardTeleport(level20));
    }

    @Test
    public void testCanUseStandardTeleport_Level21_False() {
        WorldPoint level21 = new WorldPoint(3100, 3680, 0);
        assertFalse("Should NOT be able to teleport at level 21", 
                WildernessTeleportRestrictions.canUseStandardTeleport(level21));
    }

    @Test
    public void testCanUseStandardTeleport_Level30_False() {
        WorldPoint level30 = new WorldPoint(3100, 3752, 0);
        assertFalse("Should NOT be able to standard teleport at level 30", 
                WildernessTeleportRestrictions.canUseStandardTeleport(level30));
    }

    // ========================================================================
    // canUseEnhancedTeleport() Tests
    // ========================================================================

    @Test
    public void testCanUseEnhancedTeleport_NotInWilderness_True() {
        WorldPoint safe = new WorldPoint(3222, 3218, 0);
        assertTrue("Should be able to enhanced teleport outside wilderness", 
                WildernessTeleportRestrictions.canUseEnhancedTeleport(safe));
    }

    @Test
    public void testCanUseEnhancedTeleport_Level20_True() {
        WorldPoint level20 = new WorldPoint(3100, 3672, 0);
        assertTrue("Should be able to enhanced teleport at level 20", 
                WildernessTeleportRestrictions.canUseEnhancedTeleport(level20));
    }

    @Test
    public void testCanUseEnhancedTeleport_Level21_True() {
        WorldPoint level21 = new WorldPoint(3100, 3680, 0);
        assertTrue("Should be able to enhanced teleport at level 21", 
                WildernessTeleportRestrictions.canUseEnhancedTeleport(level21));
    }

    @Test
    public void testCanUseEnhancedTeleport_Level30_True() {
        WorldPoint level30 = new WorldPoint(3100, 3752, 0);
        assertTrue("Should be able to enhanced teleport at level 30", 
                WildernessTeleportRestrictions.canUseEnhancedTeleport(level30));
    }

    @Test
    public void testCanUseEnhancedTeleport_Level31_False() {
        WorldPoint level31 = new WorldPoint(3100, 3760, 0);
        assertFalse("Should NOT be able to enhanced teleport at level 31", 
                WildernessTeleportRestrictions.canUseEnhancedTeleport(level31));
    }

    @Test
    public void testCanUseEnhancedTeleport_Level50_False() {
        WorldPoint level50 = new WorldPoint(3100, 3912, 0);
        assertFalse("Should NOT be able to enhanced teleport at level 50", 
                WildernessTeleportRestrictions.canUseEnhancedTeleport(level50));
    }

    // ========================================================================
    // canUseTeleport() with TeleportType Tests
    // ========================================================================

    @Test
    public void testCanUseTeleport_Standard_Level20_True() {
        WorldPoint level20 = new WorldPoint(3100, 3672, 0);
        assertTrue("Standard teleport should work at level 20", 
                WildernessTeleportRestrictions.canUseTeleport(level20, 
                        WildernessTeleportRestrictions.TeleportType.STANDARD));
    }

    @Test
    public void testCanUseTeleport_Standard_Level21_False() {
        WorldPoint level21 = new WorldPoint(3100, 3680, 0);
        assertFalse("Standard teleport should NOT work at level 21", 
                WildernessTeleportRestrictions.canUseTeleport(level21, 
                        WildernessTeleportRestrictions.TeleportType.STANDARD));
    }

    @Test
    public void testCanUseTeleport_Enhanced_Level21_True() {
        WorldPoint level21 = new WorldPoint(3100, 3680, 0);
        assertTrue("Enhanced teleport should work at level 21", 
                WildernessTeleportRestrictions.canUseTeleport(level21, 
                        WildernessTeleportRestrictions.TeleportType.ENHANCED));
    }

    @Test
    public void testCanUseTeleport_Enhanced_Level31_False() {
        WorldPoint level31 = new WorldPoint(3100, 3760, 0);
        assertFalse("Enhanced teleport should NOT work at level 31", 
                WildernessTeleportRestrictions.canUseTeleport(level31, 
                        WildernessTeleportRestrictions.TeleportType.ENHANCED));
    }

    // ========================================================================
    // getTeleportTypeForItem() Tests
    // ========================================================================

    @Test
    public void testGetTeleportTypeForItem_Glory_Enhanced() {
        // Amulet of glory (4) - ID 1712
        assertEquals("Glory should be ENHANCED", 
                WildernessTeleportRestrictions.TeleportType.ENHANCED, 
                WildernessTeleportRestrictions.getTeleportTypeForItem(1712));
    }

    @Test
    public void testGetTeleportTypeForItem_EternalGlory_Enhanced() {
        // Amulet of eternal glory - ID 19707
        assertEquals("Eternal glory should be ENHANCED", 
                WildernessTeleportRestrictions.TeleportType.ENHANCED, 
                WildernessTeleportRestrictions.getTeleportTypeForItem(19707));
    }

    @Test
    public void testGetTeleportTypeForItem_CombatBracelet_Enhanced() {
        // Combat bracelet (4) - ID 11118
        assertEquals("Combat bracelet should be ENHANCED", 
                WildernessTeleportRestrictions.TeleportType.ENHANCED, 
                WildernessTeleportRestrictions.getTeleportTypeForItem(11118));
    }

    @Test
    public void testGetTeleportTypeForItem_SkillsNecklace_Enhanced() {
        // Skills necklace (4) - ID 11105
        assertEquals("Skills necklace should be ENHANCED", 
                WildernessTeleportRestrictions.TeleportType.ENHANCED, 
                WildernessTeleportRestrictions.getTeleportTypeForItem(11105));
    }

    @Test
    public void testGetTeleportTypeForItem_RingOfWealth_Enhanced() {
        // Ring of wealth - ID 2572
        assertEquals("Ring of wealth should be ENHANCED", 
                WildernessTeleportRestrictions.TeleportType.ENHANCED, 
                WildernessTeleportRestrictions.getTeleportTypeForItem(2572));
    }

    @Test
    public void testGetTeleportTypeForItem_RingOfLife_Enhanced() {
        // Ring of life - ID 2570
        assertEquals("Ring of life should be ENHANCED", 
                WildernessTeleportRestrictions.TeleportType.ENHANCED, 
                WildernessTeleportRestrictions.getTeleportTypeForItem(2570));
    }

    @Test
    public void testGetTeleportTypeForItem_RoyalSeedPod_Enhanced() {
        // Royal seed pod - ID 19564
        assertEquals("Royal seed pod should be ENHANCED", 
                WildernessTeleportRestrictions.TeleportType.ENHANCED, 
                WildernessTeleportRestrictions.getTeleportTypeForItem(19564));
    }

    @Test
    public void testGetTeleportTypeForItem_SlayerRing_Enhanced() {
        // Slayer ring (8) - ID 11866
        assertEquals("Slayer ring should be ENHANCED", 
                WildernessTeleportRestrictions.TeleportType.ENHANCED, 
                WildernessTeleportRestrictions.getTeleportTypeForItem(11866));
    }

    @Test
    public void testGetTeleportTypeForItem_UnknownItem_Standard() {
        // Random item ID that's not a level-30 teleport
        assertEquals("Unknown item should be STANDARD", 
                WildernessTeleportRestrictions.TeleportType.STANDARD, 
                WildernessTeleportRestrictions.getTeleportTypeForItem(12345));
    }

    @Test
    public void testGetTeleportTypeForItem_TabletItem_Standard() {
        // Teleport tablets are standard (blocked above 20)
        assertEquals("Teleport tablet should be STANDARD", 
                WildernessTeleportRestrictions.TeleportType.STANDARD, 
                WildernessTeleportRestrictions.getTeleportTypeForItem(8007)); // Varrock tab
    }

    // ========================================================================
    // canItemTeleportFrom() Tests
    // ========================================================================

    @Test
    public void testCanItemTeleportFrom_Glory_Level25_True() {
        WorldPoint level25 = new WorldPoint(3100, 3712, 0);
        assertTrue("Glory should work at level 25", 
                WildernessTeleportRestrictions.canItemTeleportFrom(level25, 1712));
    }

    @Test
    public void testCanItemTeleportFrom_Glory_Level31_False() {
        WorldPoint level31 = new WorldPoint(3100, 3760, 0);
        assertFalse("Glory should NOT work at level 31", 
                WildernessTeleportRestrictions.canItemTeleportFrom(level31, 1712));
    }

    @Test
    public void testCanItemTeleportFrom_Tablet_Level25_False() {
        WorldPoint level25 = new WorldPoint(3100, 3712, 0);
        // Varrock teleport tablet - standard teleport, blocked above 20
        assertFalse("Tablet should NOT work at level 25", 
                WildernessTeleportRestrictions.canItemTeleportFrom(level25, 8007));
    }

    @Test
    public void testCanItemTeleportFrom_Tablet_Level20_True() {
        WorldPoint level20 = new WorldPoint(3100, 3672, 0);
        assertTrue("Tablet should work at level 20", 
                WildernessTeleportRestrictions.canItemTeleportFrom(level20, 8007));
    }

    // ========================================================================
    // getMaxTeleportLevel() Tests
    // ========================================================================

    @Test
    public void testGetMaxTeleportLevel_WithLevel30Item_Returns30() {
        assertEquals("With level 30 item should return 30", 
                30, WildernessTeleportRestrictions.getMaxTeleportLevel(true));
    }

    @Test
    public void testGetMaxTeleportLevel_WithoutLevel30Item_Returns20() {
        assertEquals("Without level 30 item should return 20", 
                20, WildernessTeleportRestrictions.getMaxTeleportLevel(false));
    }

    // ========================================================================
    // hasLevel30TeleportItem() Tests
    // ========================================================================

    @Test
    public void testHasLevel30TeleportItem_GloryInInventory_True() {
        Set<Integer> inventory = new HashSet<>();
        inventory.add(1712); // Glory (4)
        Set<Integer> equipment = Collections.emptySet();
        
        assertTrue("Should detect glory in inventory", 
                WildernessTeleportRestrictions.hasLevel30TeleportItem(inventory, equipment));
    }

    @Test
    public void testHasLevel30TeleportItem_GloryEquipped_True() {
        Set<Integer> inventory = Collections.emptySet();
        Set<Integer> equipment = new HashSet<>();
        equipment.add(1712); // Glory (4)
        
        assertTrue("Should detect glory equipped", 
                WildernessTeleportRestrictions.hasLevel30TeleportItem(inventory, equipment));
    }

    @Test
    public void testHasLevel30TeleportItem_RingOfLifeEquipped_True() {
        Set<Integer> inventory = Collections.emptySet();
        Set<Integer> equipment = new HashSet<>();
        equipment.add(2570); // Ring of life
        
        assertTrue("Should detect ring of life", 
                WildernessTeleportRestrictions.hasLevel30TeleportItem(inventory, equipment));
    }

    @Test
    public void testHasLevel30TeleportItem_NoLevel30Items_False() {
        Set<Integer> inventory = new HashSet<>();
        inventory.add(12345); // Random item
        inventory.add(8007);  // Varrock tablet
        Set<Integer> equipment = new HashSet<>();
        equipment.add(54321); // Random equipment
        
        assertFalse("Should not detect level 30 items", 
                WildernessTeleportRestrictions.hasLevel30TeleportItem(inventory, equipment));
    }

    @Test
    public void testHasLevel30TeleportItem_EmptySets_False() {
        Set<Integer> inventory = Collections.emptySet();
        Set<Integer> equipment = Collections.emptySet();
        
        assertFalse("Should return false for empty sets", 
                WildernessTeleportRestrictions.hasLevel30TeleportItem(inventory, equipment));
    }

    @Test
    public void testHasLevel30TeleportItem_MultipleLevel30Items_True() {
        Set<Integer> inventory = new HashSet<>();
        inventory.add(1712);  // Glory
        inventory.add(19564); // Royal seed pod
        Set<Integer> equipment = new HashSet<>();
        equipment.add(2570);  // Ring of life
        
        assertTrue("Should detect multiple level 30 items", 
                WildernessTeleportRestrictions.hasLevel30TeleportItem(inventory, equipment));
    }

    // ========================================================================
    // Boundary Tests
    // ========================================================================

    @Test
    public void testWildernessLevel_BoundaryBetweenLevels() {
        // Test the exact boundary between levels
        // Level 1 ends at Y=3527, Level 2 starts at Y=3528
        WorldPoint endOfLevel1 = new WorldPoint(3100, 3527, 0);
        WorldPoint startOfLevel2 = new WorldPoint(3100, 3528, 0);
        
        assertEquals("Y=3527 should still be level 1", 
                1, WildernessTeleportRestrictions.getWildernessLevel(endOfLevel1));
        assertEquals("Y=3528 should be level 2", 
                2, WildernessTeleportRestrictions.getWildernessLevel(startOfLevel2));
    }

    @Test
    public void testTeleportCutoff_ExactBoundaries() {
        // Exact level 20 boundary
        WorldPoint exactLevel20 = new WorldPoint(3100, 3672, 0);
        // Just into level 21
        WorldPoint justLevel21 = new WorldPoint(3100, 3680, 0);
        
        assertTrue("Standard teleport should work at exact level 20", 
                WildernessTeleportRestrictions.canUseStandardTeleport(exactLevel20));
        assertFalse("Standard teleport should fail at level 21", 
                WildernessTeleportRestrictions.canUseStandardTeleport(justLevel21));
        
        // Exact level 30 boundary
        WorldPoint exactLevel30 = new WorldPoint(3100, 3752, 0);
        // Just into level 31
        WorldPoint justLevel31 = new WorldPoint(3100, 3760, 0);
        
        assertTrue("Enhanced teleport should work at exact level 30", 
                WildernessTeleportRestrictions.canUseEnhancedTeleport(exactLevel30));
        assertFalse("Enhanced teleport should fail at level 31", 
                WildernessTeleportRestrictions.canUseEnhancedTeleport(justLevel31));
    }
}

