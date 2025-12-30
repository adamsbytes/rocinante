package com.rocinante.util;

import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ItemData} utility class.
 *
 * Validates food, healing, antipoison, and combo-eat detection logic.
 */
public class ItemDataTest {

    // ========================================================================
    // isFood() Tests
    // ========================================================================

    @Test
    public void testIsFood_Shark() {
        assertTrue("Shark should be recognized as food", ItemData.isFood(ItemID.SHARK));
    }

    @Test
    public void testIsFood_Lobster() {
        assertTrue("Lobster should be recognized as food", ItemData.isFood(ItemID.LOBSTER));
    }

    @Test
    public void testIsFood_Monkfish() {
        assertTrue("Monkfish should be recognized as food", ItemData.isFood(ItemID.MONKFISH));
    }

    @Test
    public void testIsFood_Anglerfish() {
        assertTrue("Anglerfish should be recognized as food", ItemData.isFood(ItemID.ANGLERFISH));
    }

    @Test
    public void testIsFood_Karambwan() {
        assertTrue("Cooked karambwan should be recognized as food", 
                ItemData.isFood(ItemID.TBWT_COOKED_KARAMBWAN));
    }

    @Test
    public void testIsFood_BlightedKarambwan() {
        assertTrue("Blighted karambwan should be recognized as food", 
                ItemData.isFood(ItemID.BLIGHTED_KARAMBWAN));
    }

    @Test
    public void testIsFood_SaradominBrew() {
        assertTrue("Saradomin brew (4) should be recognized as food", 
                ItemData.isFood(ItemID._4DOSEPOTIONOFSARADOMIN));
        assertTrue("Saradomin brew (3) should be recognized as food", 
                ItemData.isFood(ItemID._3DOSEPOTIONOFSARADOMIN));
        assertTrue("Saradomin brew (2) should be recognized as food", 
                ItemData.isFood(ItemID._2DOSEPOTIONOFSARADOMIN));
        assertTrue("Saradomin brew (1) should be recognized as food", 
                ItemData.isFood(ItemID._1DOSEPOTIONOFSARADOMIN));
    }

    @Test
    public void testIsFood_LowTierFish() {
        assertTrue("Shrimp should be recognized as food", ItemData.isFood(ItemID.SHRIMP));
        assertTrue("Trout should be recognized as food", ItemData.isFood(ItemID.TROUT));
        assertTrue("Salmon should be recognized as food", ItemData.isFood(ItemID.SALMON));
    }

    @Test
    public void testIsFood_Pizzas() {
        assertTrue("Plain pizza should be recognized as food", ItemData.isFood(ItemID.PLAIN_PIZZA));
        assertTrue("Half plain pizza should be recognized as food", ItemData.isFood(ItemID.HALF_PLAIN_PIZZA));
        assertTrue("Pineapple pizza should be recognized as food", ItemData.isFood(ItemID.PINEAPPLE_PIZZA));
    }

    @Test
    public void testIsFood_Pies() {
        assertTrue("Apple pie should be recognized as food", ItemData.isFood(ItemID.APPLE_PIE));
        assertTrue("Summer pie should be recognized as food", ItemData.isFood(ItemID.SUMMER_PIE));
    }

    @Test
    public void testIsFood_Potatoes() {
        assertTrue("Tuna potato should be recognized as food", 
                ItemData.isFood(ItemID.POTATO_TUNA_SWEETCORN));
        assertTrue("Mushroom potato should be recognized as food", 
                ItemData.isFood(ItemID.POTATO_MUSHROOM_ONION));
    }

    @Test
    public void testIsFood_UnknownItem_ReturnsFalse() {
        assertFalse("Unknown item ID should not be food", ItemData.isFood(0));
        assertFalse("Negative item ID should not be food", ItemData.isFood(-1));
        assertFalse("Random high ID should not be food", ItemData.isFood(999999));
    }

    // ========================================================================
    // getHealingAmount() Tests
    // ========================================================================

    @Test
    public void testGetHealingAmount_Shark() {
        assertEquals("Shark heals 20 HP", 20, ItemData.getHealingAmount(ItemID.SHARK));
    }

    @Test
    public void testGetHealingAmount_Lobster() {
        assertEquals("Lobster heals 12 HP", 12, ItemData.getHealingAmount(ItemID.LOBSTER));
    }

    @Test
    public void testGetHealingAmount_Monkfish() {
        assertEquals("Monkfish heals 16 HP", 16, ItemData.getHealingAmount(ItemID.MONKFISH));
    }

    @Test
    public void testGetHealingAmount_Anglerfish() {
        assertEquals("Anglerfish heals 22 HP (base)", 22, ItemData.getHealingAmount(ItemID.ANGLERFISH));
    }

    @Test
    public void testGetHealingAmount_Karambwan() {
        assertEquals("Cooked karambwan heals 18 HP", 18, 
                ItemData.getHealingAmount(ItemID.TBWT_COOKED_KARAMBWAN));
    }

    @Test
    public void testGetHealingAmount_LowTierFish() {
        assertEquals("Shrimp heals 3 HP", 3, ItemData.getHealingAmount(ItemID.SHRIMP));
        assertEquals("Anchovies heal 1 HP", 1, ItemData.getHealingAmount(ItemID.ANCHOVIES));
        assertEquals("Trout heals 7 HP", 7, ItemData.getHealingAmount(ItemID.TROUT));
        assertEquals("Salmon heals 9 HP", 9, ItemData.getHealingAmount(ItemID.SALMON));
    }

    @Test
    public void testGetHealingAmount_MidTierFish() {
        assertEquals("Tuna heals 10 HP", 10, ItemData.getHealingAmount(ItemID.TUNA));
        assertEquals("Swordfish heals 14 HP", 14, ItemData.getHealingAmount(ItemID.SWORDFISH));
        assertEquals("Bass heals 13 HP", 13, ItemData.getHealingAmount(ItemID.BASS));
    }

    @Test
    public void testGetHealingAmount_HighTierFish() {
        assertEquals("Manta ray heals 22 HP", 22, ItemData.getHealingAmount(ItemID.MANTARAY));
        assertEquals("Sea turtle heals 21 HP", 21, ItemData.getHealingAmount(ItemID.SEATURTLE));
        assertEquals("Dark crab heals 22 HP", 22, ItemData.getHealingAmount(ItemID.DARK_CRAB));
    }

    @Test
    public void testGetHealingAmount_Potatoes() {
        assertEquals("Tuna potato heals 22 HP", 22, 
                ItemData.getHealingAmount(ItemID.POTATO_TUNA_SWEETCORN));
        assertEquals("Mushroom potato heals 20 HP", 20, 
                ItemData.getHealingAmount(ItemID.POTATO_MUSHROOM_ONION));
        assertEquals("Cheese potato heals 16 HP", 16, 
                ItemData.getHealingAmount(ItemID.POTATO_CHEESE));
        assertEquals("Butter potato heals 14 HP", 14, 
                ItemData.getHealingAmount(ItemID.POTATO_BUTTER));
    }

    @Test
    public void testGetHealingAmount_Pizzas() {
        assertEquals("Plain pizza heals 7 HP per half", 7, 
                ItemData.getHealingAmount(ItemID.PLAIN_PIZZA));
        assertEquals("Pineapple pizza heals 11 HP per half", 11, 
                ItemData.getHealingAmount(ItemID.PINEAPPLE_PIZZA));
    }

    @Test
    public void testGetHealingAmount_SaradominBrew() {
        // Saradomin brew healing is approximate (16 for 99 HP)
        assertEquals("Saradomin brew (4) heals approx 16 HP", 16, 
                ItemData.getHealingAmount(ItemID._4DOSEPOTIONOFSARADOMIN));
    }

    @Test
    public void testGetHealingAmount_UnknownItem_ReturnsZero() {
        assertEquals("Unknown item ID should return 0", 0, ItemData.getHealingAmount(0));
        assertEquals("Negative item ID should return 0", 0, ItemData.getHealingAmount(-1));
        assertEquals("Random high ID should return 0", 0, ItemData.getHealingAmount(999999));
    }

    // ========================================================================
    // isAntipoison() Tests
    // ========================================================================

    @Test
    public void testIsAntipoison_RegularAntipoison() {
        assertTrue("Antipoison(4) should be recognized", ItemData.isAntipoison(2446));
        assertTrue("Antipoison(3) should be recognized", ItemData.isAntipoison(175));
        assertTrue("Antipoison(2) should be recognized", ItemData.isAntipoison(177));
        assertTrue("Antipoison(1) should be recognized", ItemData.isAntipoison(179));
    }

    @Test
    public void testIsAntipoison_Superantipoison() {
        assertTrue("Superantipoison(4) should be recognized", ItemData.isAntipoison(2448));
        assertTrue("Superantipoison(3) should be recognized", ItemData.isAntipoison(181));
        assertTrue("Superantipoison(2) should be recognized", ItemData.isAntipoison(183));
        assertTrue("Superantipoison(1) should be recognized", ItemData.isAntipoison(185));
    }

    @Test
    public void testIsAntipoison_AntidotePlus() {
        assertTrue("Antidote+(4) should be recognized", ItemData.isAntipoison(5943));
        assertTrue("Antidote+(3) should be recognized", ItemData.isAntipoison(5945));
        assertTrue("Antidote+(2) should be recognized", ItemData.isAntipoison(5947));
        assertTrue("Antidote+(1) should be recognized", ItemData.isAntipoison(5949));
    }

    @Test
    public void testIsAntipoison_AntidotePlusPlus() {
        assertTrue("Antidote++(4) should be recognized", ItemData.isAntipoison(5952));
        assertTrue("Antidote++(3) should be recognized", ItemData.isAntipoison(5954));
        assertTrue("Antidote++(2) should be recognized", ItemData.isAntipoison(5956));
        assertTrue("Antidote++(1) should be recognized", ItemData.isAntipoison(5958));
    }

    @Test
    public void testIsAntipoison_AntiVenom() {
        assertTrue("Anti-venom(4) should be recognized", ItemData.isAntipoison(12905));
        assertTrue("Anti-venom(3) should be recognized", ItemData.isAntipoison(12907));
        assertTrue("Anti-venom(2) should be recognized", ItemData.isAntipoison(12909));
        assertTrue("Anti-venom(1) should be recognized", ItemData.isAntipoison(12911));
    }

    @Test
    public void testIsAntipoison_AntiVenomPlus() {
        assertTrue("Anti-venom+(4) should be recognized", ItemData.isAntipoison(12913));
        assertTrue("Anti-venom+(3) should be recognized", ItemData.isAntipoison(12915));
        assertTrue("Anti-venom+(2) should be recognized", ItemData.isAntipoison(12917));
        assertTrue("Anti-venom+(1) should be recognized", ItemData.isAntipoison(12919));
    }

    @Test
    public void testIsAntipoison_SanfewSerum() {
        assertTrue("Sanfew serum(4) should be recognized", ItemData.isAntipoison(10925));
        assertTrue("Sanfew serum(3) should be recognized", ItemData.isAntipoison(10927));
        assertTrue("Sanfew serum(2) should be recognized", ItemData.isAntipoison(10929));
        assertTrue("Sanfew serum(1) should be recognized", ItemData.isAntipoison(10931));
    }

    @Test
    public void testIsAntipoison_RegularPotions_NotAntipoison() {
        // Saradomin brew is NOT an antipoison
        assertFalse("Saradomin brew should not be antipoison", 
                ItemData.isAntipoison(ItemID._4DOSEPOTIONOFSARADOMIN));
    }

    @Test
    public void testIsAntipoison_Food_NotAntipoison() {
        assertFalse("Shark should not be antipoison", ItemData.isAntipoison(ItemID.SHARK));
        assertFalse("Lobster should not be antipoison", ItemData.isAntipoison(ItemID.LOBSTER));
    }

    @Test
    public void testIsAntipoison_UnknownItem_ReturnsFalse() {
        assertFalse("Unknown item ID should not be antipoison", ItemData.isAntipoison(0));
        assertFalse("Negative item ID should not be antipoison", ItemData.isAntipoison(-1));
    }

    // ========================================================================
    // isKarambwan() Tests
    // ========================================================================

    @Test
    public void testIsKarambwan_CookedKarambwan() {
        assertTrue("Cooked karambwan should be recognized", 
                ItemData.isKarambwan(ItemID.TBWT_COOKED_KARAMBWAN));
    }

    @Test
    public void testIsKarambwan_BlightedKarambwan() {
        assertTrue("Blighted karambwan should be recognized", 
                ItemData.isKarambwan(ItemID.BLIGHTED_KARAMBWAN));
    }

    @Test
    public void testIsKarambwan_OtherFood_NotKarambwan() {
        assertFalse("Shark should not be karambwan", ItemData.isKarambwan(ItemID.SHARK));
        assertFalse("Manta ray should not be karambwan", ItemData.isKarambwan(ItemID.MANTARAY));
        assertFalse("Anglerfish should not be karambwan", ItemData.isKarambwan(ItemID.ANGLERFISH));
    }

    @Test
    public void testIsKarambwan_UnknownItem_ReturnsFalse() {
        assertFalse("Unknown item ID should not be karambwan", ItemData.isKarambwan(0));
        assertFalse("Negative item ID should not be karambwan", ItemData.isKarambwan(-1));
    }

    // ========================================================================
    // isSaradominBrew() Tests
    // ========================================================================

    @Test
    public void testIsSaradominBrew_AllDoses() {
        assertTrue("Saradomin brew (4) should be recognized", 
                ItemData.isSaradominBrew(ItemID._4DOSEPOTIONOFSARADOMIN));
        assertTrue("Saradomin brew (3) should be recognized", 
                ItemData.isSaradominBrew(ItemID._3DOSEPOTIONOFSARADOMIN));
        assertTrue("Saradomin brew (2) should be recognized", 
                ItemData.isSaradominBrew(ItemID._2DOSEPOTIONOFSARADOMIN));
        assertTrue("Saradomin brew (1) should be recognized", 
                ItemData.isSaradominBrew(ItemID._1DOSEPOTIONOFSARADOMIN));
    }

    @Test
    public void testIsSaradominBrew_OtherPotions_NotSaradominBrew() {
        // Antipoisons are not Saradomin brews
        assertFalse("Antipoison should not be Saradomin brew", ItemData.isSaradominBrew(2446));
        assertFalse("Anti-venom+ should not be Saradomin brew", ItemData.isSaradominBrew(12913));
    }

    @Test
    public void testIsSaradominBrew_Food_NotSaradominBrew() {
        assertFalse("Shark should not be Saradomin brew", ItemData.isSaradominBrew(ItemID.SHARK));
        assertFalse("Karambwan should not be Saradomin brew", 
                ItemData.isSaradominBrew(ItemID.TBWT_COOKED_KARAMBWAN));
    }

    @Test
    public void testIsSaradominBrew_UnknownItem_ReturnsFalse() {
        assertFalse("Unknown item ID should not be Saradomin brew", ItemData.isSaradominBrew(0));
        assertFalse("Negative item ID should not be Saradomin brew", ItemData.isSaradominBrew(-1));
    }

    // ========================================================================
    // Collection Immutability Tests
    // ========================================================================

    @Test(expected = UnsupportedOperationException.class)
    public void testFoodIds_Immutable() {
        ItemData.FOOD_IDS.add(999999);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testFoodHealing_Immutable() {
        ItemData.FOOD_HEALING.put(999999, 50);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testAntipoisonIds_Immutable() {
        ItemData.ANTIPOISON_IDS.add(999999);
    }

    // ========================================================================
    // Consistency Tests
    // ========================================================================

    @Test
    public void testFoodWithHealing_AllHaveHealingAmount() {
        // Every food item in FOOD_HEALING should also be in FOOD_IDS
        for (int itemId : ItemData.FOOD_HEALING.keySet()) {
            assertTrue("Item " + itemId + " has healing but is not in FOOD_IDS", 
                    ItemData.FOOD_IDS.contains(itemId));
        }
    }

    @Test
    public void testHealingValues_ArePositive() {
        for (int healing : ItemData.FOOD_HEALING.values()) {
            assertTrue("Healing amount should be positive", healing > 0);
        }
    }
}

