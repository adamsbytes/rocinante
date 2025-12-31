package com.rocinante.navigation;

import com.rocinante.behavior.AccountType;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for transport configuration overrides based on account type and resources.
 *
 * <p>These tests verify that ResourceAwareness correctly determines transport
 * availability based on account type (HCIM, Ironman, Normal) and resource state
 * (law runes, gold, etc.).
 */
public class TransportConfigOverrideTest {

    // ========================================================================
    // HCIM Account Tests
    // ========================================================================

    @Test
    public void testHcimShouldAvoidWilderness() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.HARDCORE_IRONMAN)
                .lawRuneCount(100)
                .goldAmount(100000)
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertTrue("HCIM should always avoid wilderness", ra.shouldAvoidWilderness());
    }

    @Test
    public void testHcimShouldNotUseTeleportationSpells() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.HARDCORE_IRONMAN)
                .lawRuneCount(100)  // Even with many law runes
                .goldAmount(100000)
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertFalse("HCIM should never use teleportation spells (law runes too precious)",
                ra.shouldUseTeleportationSpells());
    }

    @Test
    public void testHcimShouldNotUseGrappleShortcuts() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.HARDCORE_IRONMAN)
                .lawRuneCount(100)
                .goldAmount(100000)
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertFalse("HCIM should not use grapple shortcuts (risky)",
                ra.shouldUseGrappleShortcuts());
    }

    @Test
    public void testHcimShouldNotUseWildernessObelisks() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.HARDCORE_IRONMAN)
                .lawRuneCount(100)
                .goldAmount(100000)
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertFalse("HCIM should never use wilderness obelisks",
                ra.shouldUseWildernessObelisks());
    }

    @Test
    public void testHcimTeleportItemsSettingIsPermanentOnly() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.HARDCORE_IRONMAN)
                .lawRuneCount(100)
                .goldAmount(100000)
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertEquals("HCIM should only use permanent teleport items",
                "Inventory (perm)", ra.getTeleportationItemsSetting());
    }

    @Test
    public void testHcimGetsFairyRingBonus() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.HARDCORE_IRONMAN)
                .lawRuneCount(100)
                .goldAmount(100000)
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertTrue("HCIM should get fairy ring bonus (incentive for free travel)",
                ra.getFairyRingBonus() < 0);
    }

    // ========================================================================
    // Ironman Account Tests
    // ========================================================================

    @Test
    public void testIronmanShouldNotAvoidWilderness() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.IRONMAN)
                .lawRuneCount(100)
                .goldAmount(100000)
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertFalse("Regular ironman does not need to avoid wilderness",
                ra.shouldAvoidWilderness());
    }

    @Test
    public void testIronmanWithManyLawRunesShouldUseTeleportSpells() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.IRONMAN)
                .lawRuneCount(100)  // Plenty of law runes
                .goldAmount(100000)
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertTrue("Ironman with many law runes can use teleport spells",
                ra.shouldUseTeleportationSpells());
    }

    @Test
    public void testIronmanWithScarceLawRunesShouldNotUseTeleportSpells() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.IRONMAN)
                .lawRuneCount(20)  // Scarce - below threshold of 50
                .goldAmount(100000)
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertFalse("Ironman with scarce law runes should not use teleport spells",
                ra.shouldUseTeleportationSpells());
    }

    @Test
    public void testIronmanWithLowGoldShouldNotUseCharterShips() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.IRONMAN)
                .lawRuneCount(100)
                .goldAmount(50000)  // Below 100k threshold for ironman
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertFalse("Ironman with moderate gold should not use charter ships",
                ra.shouldUseCharterShips());
    }

    @Test
    public void testIronmanWithFewChargesTeleportItemsIsPermanentOnly() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.IRONMAN)
                .lawRuneCount(100)
                .goldAmount(100000)
                .jewelryCharges(2)  // Very few charges
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertEquals("Ironman with few jewelry charges should use permanent items only",
                "Inventory (perm)", ra.getTeleportationItemsSetting());
    }

    @Test
    public void testIronmanWithManyChargesTeleportItemsIsInventory() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.IRONMAN)
                .lawRuneCount(100)
                .goldAmount(100000)
                .jewelryCharges(10)  // Plenty of charges
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertEquals("Ironman with many jewelry charges can use inventory items",
                "Inventory", ra.getTeleportationItemsSetting());
    }

    // ========================================================================
    // Normal Account Tests
    // ========================================================================

    @Test
    public void testNormalShouldNotAvoidWilderness() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.NORMAL)
                .lawRuneCount(100)
                .goldAmount(100000)
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertFalse("Normal account does not need to avoid wilderness",
                ra.shouldAvoidWilderness());
    }

    @Test
    public void testNormalShouldUseTeleportationSpells() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.NORMAL)
                .lawRuneCount(5)  // Even with few law runes
                .goldAmount(100000)
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertTrue("Normal account should use teleportation spells",
                ra.shouldUseTeleportationSpells());
    }

    @Test
    public void testNormalWithGoldShouldUseCharterShips() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.NORMAL)
                .lawRuneCount(100)
                .goldAmount(50000)  // Above 10k threshold
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertTrue("Normal account with gold should use charter ships",
                ra.shouldUseCharterShips());
    }

    @Test
    public void testNormalBrokeShouldNotUseCharterShips() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.NORMAL)
                .lawRuneCount(100)
                .goldAmount(5000)  // Below 10k threshold
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertFalse("Normal account when broke should not use charter ships",
                ra.shouldUseCharterShips());
    }

    @Test
    public void testNormalTeleportItemsSettingIsInventory() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.NORMAL)
                .lawRuneCount(100)
                .goldAmount(100000)
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertEquals("Normal account should use inventory teleport items",
                "Inventory", ra.getTeleportationItemsSetting());
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testVeryBrokeShouldNotUseMagicCarpets() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.NORMAL)
                .lawRuneCount(100)
                .goldAmount(500)  // Very low gold
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertFalse("Should not use magic carpets when very broke",
                ra.shouldUseMagicCarpets());
    }

    @Test
    public void testModerateGoldShouldUseMagicCarpets() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.NORMAL)
                .lawRuneCount(100)
                .goldAmount(5000)  // Above 1000 threshold
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(true)
                .build();

        assertTrue("Should use magic carpets with moderate gold",
                ra.shouldUseMagicCarpets());
    }

    @Test
    public void testCanoesShouldAlwaysBeAllowed() {
        // Test for all account types - canoes are free
        for (AccountType type : AccountType.values()) {
            ResourceAwareness ra = ResourceAwareness.builder()
                    .accountType(type)
                    .lawRuneCount(0)
                    .goldAmount(0)
                    .jewelryCharges(0)
                    .hasFairyRingAccess(false)
                    .hasSpiritTreeAccess(false)
                    .build();

            assertTrue("Canoes should always be allowed for " + type,
                    ra.shouldUseCanoes());
        }
    }

    @Test
    public void testNoFairyRingAccessMeansNoBonus() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.HARDCORE_IRONMAN)
                .lawRuneCount(100)
                .goldAmount(100000)
                .jewelryCharges(10)
                .hasFairyRingAccess(false)  // No access
                .hasSpiritTreeAccess(true)
                .build();

        assertEquals("No fairy ring access means no bonus",
                0, ra.getFairyRingBonus());
    }

    @Test
    public void testNoSpiritTreeAccessMeansNoBonus() {
        ResourceAwareness ra = ResourceAwareness.builder()
                .accountType(AccountType.HARDCORE_IRONMAN)
                .lawRuneCount(100)
                .goldAmount(100000)
                .jewelryCharges(10)
                .hasFairyRingAccess(true)
                .hasSpiritTreeAccess(false)  // No access
                .build();

        assertEquals("No spirit tree access means no bonus",
                0, ra.getSpiritTreeBonus());
    }
}
