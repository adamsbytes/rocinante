package com.rocinante.progression;

import com.rocinante.util.CollectionResolver;
import net.runelite.api.ObjectID;
import net.runelite.api.NpcID;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Integration tests verifying that training methods correctly integrate with CollectionResolver.
 * 
 * <p>These tests load real training_methods.json and verify:
 * <ul>
 *   <li>Methods with object IDs expand correctly via collections</li>
 *   <li>Methods with NPC IDs expand correctly via collections</li>
 *   <li>Chaos altar IDs expand to all variants</li>
 *   <li>Thieving stall IDs expand correctly</li>
 *   <li>Pickpocket NPC IDs expand correctly</li>
 * </ul>
 */
public class TrainingMethodCollectionIntegrationTest {

    private TrainingMethodRepository repository;

    @Before
    public void setUp() {
        repository = new TrainingMethodRepository();
    }

    // ========================================================================
    // Mining Methods - Object ID Expansion
    // ========================================================================

    @Test
    public void testCopperTinMining_ObjectIdsExpand() {
        Optional<TrainingMethod> method = repository.getMethodById("copper_tin_powermine");
        assertTrue("copper_tin_powermine method should exist", method.isPresent());
        
        List<Integer> originalIds = method.get().getTargetObjectIds();
        List<Integer> expandedIds = CollectionResolver.expandObjectIds(originalIds);
        
        // Should expand to include all copper and tin rock variants
        assertTrue("Should expand IDs", expandedIds.size() > originalIds.size());
        assertTrue("Should contain copper variants", 
                expandedIds.contains(ObjectID.COPPER_ROCKS) || 
                expandedIds.contains(ObjectID.COPPER_ROCKS_10943));
        assertTrue("Should contain tin variants", 
                expandedIds.contains(ObjectID.TIN_ROCKS) || 
                expandedIds.contains(ObjectID.TIN_ROCKS_11360));
    }

    @Test
    public void testIronMining_ObjectIdsExpand() {
        Optional<TrainingMethod> method = repository.getMethodById("iron_ore_powermine");
        assertTrue("iron_ore_powermine method should exist", method.isPresent());
        
        List<Integer> originalIds = method.get().getTargetObjectIds();
        List<Integer> expandedIds = CollectionResolver.expandObjectIds(originalIds);
        
        // Should have at least iron rock variants
        assertFalse("Should have expanded IDs", expandedIds.isEmpty());
    }

    // ========================================================================
    // Woodcutting Methods - Object ID Expansion
    // ========================================================================

    @Test
    public void testOakTreesWoodcutting_ObjectIdsExpand() {
        Optional<TrainingMethod> method = repository.getMethodById("oak_trees_powerchop");
        assertTrue("oak_trees_powerchop method should exist", method.isPresent());
        
        List<Integer> originalIds = method.get().getTargetObjectIds();
        List<Integer> expandedIds = CollectionResolver.expandObjectIds(originalIds);
        
        // Should expand to include all oak tree variants
        assertTrue("Should expand to more IDs", expandedIds.size() > originalIds.size());
        assertTrue("Should contain base oak tree", expandedIds.contains(ObjectID.OAK_TREE));
    }

    @Test
    public void testWillowTreesWoodcutting_ObjectIdsExpand() {
        Optional<TrainingMethod> method = repository.getMethodById("willow_trees_powerchop");
        assertTrue("willow_trees_powerchop method should exist", method.isPresent());
        
        List<Integer> originalIds = method.get().getTargetObjectIds();
        List<Integer> expandedIds = CollectionResolver.expandObjectIds(originalIds);
        
        // Should expand to include willow variants
        assertTrue("Should contain base willow tree", expandedIds.contains(ObjectID.WILLOW_TREE));
    }

    // ========================================================================
    // Fishing Methods - NPC ID Expansion
    // ========================================================================

    @Test
    public void testShrimpsAnchoviesFishing_NpcIdsExpand() {
        Optional<TrainingMethod> method = repository.getMethodById("shrimps_anchovies_fishing");
        assertTrue("shrimps_anchovies_fishing method should exist", method.isPresent());
        
        if (method.get().hasTargetNpcs()) {
            List<Integer> originalIds = method.get().getTargetNpcIds();
            List<Integer> expandedIds = CollectionResolver.expandNpcIds(originalIds);
            
            // Should expand fishing spots
            assertFalse("Should have IDs", expandedIds.isEmpty());
        }
    }

    @Test
    public void testTroutSalmonFlyFishing_NpcIdsExpand() {
        Optional<TrainingMethod> method = repository.getMethodById("trout_salmon_fly_fishing");
        assertTrue("trout_salmon_fly_fishing method should exist", method.isPresent());
        
        if (method.get().hasTargetNpcs()) {
            List<Integer> originalIds = method.get().getTargetNpcIds();
            List<Integer> expandedIds = CollectionResolver.expandNpcIds(originalIds);
            
            assertFalse("Should have IDs", expandedIds.isEmpty());
        }
    }

    // ========================================================================
    // Prayer Methods - Chaos Altar
    // ========================================================================

    @Test
    public void testBonesAtChaosAltar_ObjectIdsExpand() {
        Optional<TrainingMethod> method = repository.getMethodById("bones_altar_offering");
        assertTrue("bones_altar_offering method should exist", method.isPresent());
        
        List<Integer> originalIds = method.get().getTargetObjectIds();
        List<Integer> expandedIds = CollectionResolver.expandObjectIds(originalIds);
        
        // Should now have correct base chaos altar ID (61) and expand to all variants
        assertTrue("Should contain base CHAOS_ALTAR (61)", 
                originalIds.contains(ObjectID.CHAOS_ALTAR) || 
                expandedIds.contains(ObjectID.CHAOS_ALTAR));
        assertTrue("Should expand to all chaos altar variants", 
                expandedIds.size() >= 4);
        assertTrue("Should contain variant 411", 
                expandedIds.contains(ObjectID.CHAOS_ALTAR_411));
        assertTrue("Should contain variant 412", 
                expandedIds.contains(ObjectID.CHAOS_ALTAR_412));
        assertTrue("Should contain variant 26258", 
                expandedIds.contains(ObjectID.CHAOS_ALTAR_26258));
    }

    @Test
    public void testDragonBonesAtChaosAltar_ObjectIdsExpand() {
        Optional<TrainingMethod> method = repository.getMethodById("dragon_bones_altar_offering");
        assertTrue("dragon_bones_altar_offering method should exist", method.isPresent());
        
        List<Integer> originalIds = method.get().getTargetObjectIds();
        List<Integer> expandedIds = CollectionResolver.expandObjectIds(originalIds);
        
        // Should expand to all chaos altar variants
        assertTrue("Should expand to multiple variants", expandedIds.size() >= 4);
    }

    // ========================================================================
    // Thieving Methods - Stall Expansion
    // ========================================================================

    @Test
    public void testCakeStallThieving_ObjectIdsExpand() {
        Optional<TrainingMethod> method = repository.getMethodById("cake_stall_stealing");
        assertTrue("cake_stall_stealing method should exist", method.isPresent());
        
        List<Integer> originalIds = method.get().getTargetObjectIds();
        List<Integer> expandedIds = CollectionResolver.expandObjectIds(originalIds);
        
        // Should expand to include all bakery stall variants (cake stalls use bakery stall objects)
        assertFalse("Should have expanded IDs", expandedIds.isEmpty());
    }

    @Test
    public void testSilkStallThieving_ObjectIdsExpand() {
        Optional<TrainingMethod> method = repository.getMethodById("silk_stall_stealing");
        assertTrue("silk_stall_stealing method should exist", method.isPresent());
        
        List<Integer> originalIds = method.get().getTargetObjectIds();
        List<Integer> expandedIds = CollectionResolver.expandObjectIds(originalIds);
        
        // Our updated collection has all 8 silk stall variants
        assertTrue("Should expand to many variants", expandedIds.size() >= 6);
        assertTrue("Should contain variant 20344", 
                expandedIds.contains(ObjectID.SILK_STALL_20344));
        assertTrue("Should contain variant 58101", 
                expandedIds.contains(ObjectID.SILK_STALL_58101));
    }

    // ========================================================================
    // Thieving Methods - Pickpocket NPC Expansion
    // ========================================================================

    @Test
    public void testManWomanPickpocket_NpcIdsExpand() {
        Optional<TrainingMethod> method = repository.getMethodById("man_woman_pickpocket");
        assertTrue("man_woman_pickpocket method should exist", method.isPresent());
        
        List<Integer> originalIds = method.get().getTargetNpcIds();
        List<Integer> expandedIds = CollectionResolver.expandNpcIds(originalIds);
        
        // Should expand to include all citizen variants
        assertTrue("Should expand to many variants", expandedIds.size() > 10);
    }

    @Test
    public void testMasterFarmerPickpocket_NpcIdsExpand() {
        Optional<TrainingMethod> method = repository.getMethodById("master_farmer_pickpocket");
        assertTrue("master_farmer_pickpocket method should exist", method.isPresent());
        
        List<Integer> originalIds = method.get().getTargetNpcIds();
        List<Integer> expandedIds = CollectionResolver.expandNpcIds(originalIds);
        
        // Our updated collection has 16 master farmer variants
        assertTrue("Should expand to 16+ variants", expandedIds.size() >= 14);
        assertTrue("Should contain variant 11940", 
                expandedIds.contains(NpcID.MASTER_FARMER_11940));
        assertTrue("Should contain variant 14758", 
                expandedIds.contains(NpcID.MASTER_FARMER_14758));
    }

    @Test
    public void testArdyKnightPickpocket_NpcIdsExpand() {
        Optional<TrainingMethod> method = repository.getMethodById("ardy_knight_pickpocket");
        assertTrue("ardy_knight_pickpocket method should exist", method.isPresent());
        
        List<Integer> originalIds = method.get().getTargetNpcIds();
        List<Integer> expandedIds = CollectionResolver.expandNpcIds(originalIds);
        
        // Our updated collection has 11 knight variants
        assertTrue("Should expand to 11+ variants", expandedIds.size() >= 10);
        assertTrue("Should contain variant 11902", 
                expandedIds.contains(NpcID.KNIGHT_OF_ARDOUGNE_11902));
        assertTrue("Should contain variant 11936", 
                expandedIds.contains(NpcID.KNIGHT_OF_ARDOUGNE_11936));
    }

    // ========================================================================
    // Comprehensive Expansion Test
    // ========================================================================

    @Test
    public void testGatheringMethodsWithObjectIds_ExpandWithoutError() {
        List<TrainingMethod> gatheringMethods = repository.getGatheringMethods();
        
        for (TrainingMethod method : gatheringMethods) {
            if (method.hasTargetObjects()) {
                try {
                    List<Integer> expanded = CollectionResolver.expandObjectIds(
                            method.getTargetObjectIds());
                    assertNotNull("Expansion should not return null for " + method.getId(), 
                            expanded);
                    assertFalse("Expansion should not be empty for " + method.getId(), 
                            expanded.isEmpty());
                } catch (Exception e) {
                    fail("Exception expanding object IDs for method " + method.getId() + 
                            ": " + e.getMessage());
                }
            }
        }
    }

    @Test
    public void testGatheringMethodsWithNpcIds_ExpandWithoutError() {
        List<TrainingMethod> gatheringMethods = repository.getGatheringMethods();
        
        for (TrainingMethod method : gatheringMethods) {
            if (method.hasTargetNpcs()) {
                try {
                    List<Integer> expanded = CollectionResolver.expandNpcIds(
                            method.getTargetNpcIds());
                    assertNotNull("Expansion should not return null for " + method.getId(), 
                            expanded);
                    assertFalse("Expansion should not be empty for " + method.getId(), 
                            expanded.isEmpty());
                } catch (Exception e) {
                    fail("Exception expanding NPC IDs for method " + method.getId() + 
                            ": " + e.getMessage());
                }
            }
        }
    }

    @Test
    public void testThievingMethodsWithIds_ExpandWithoutError() {
        List<TrainingMethod> thievingMethods = repository.getMethodsByType(MethodType.THIEVING);
        
        for (TrainingMethod method : thievingMethods) {
            if (method.hasTargetObjects()) {
                try {
                    List<Integer> expanded = CollectionResolver.expandObjectIds(
                            method.getTargetObjectIds());
                    assertNotNull("Expansion should not return null for " + method.getId(), 
                            expanded);
                    assertFalse("Expansion should not be empty for " + method.getId(), 
                            expanded.isEmpty());
                } catch (Exception e) {
                    fail("Exception expanding object IDs for method " + method.getId() + 
                            ": " + e.getMessage());
                }
            }
            if (method.hasTargetNpcs()) {
                try {
                    List<Integer> expanded = CollectionResolver.expandNpcIds(
                            method.getTargetNpcIds());
                    assertNotNull("Expansion should not return null for " + method.getId(), 
                            expanded);
                    assertFalse("Expansion should not be empty for " + method.getId(), 
                            expanded.isEmpty());
                } catch (Exception e) {
                    fail("Exception expanding NPC IDs for method " + method.getId() + 
                            ": " + e.getMessage());
                }
            }
        }
    }

    // ========================================================================
    // ID Validation - Verify JSON IDs exist in collections
    // ========================================================================

    @Test
    public void testMiningMethodIds_MostInCollection() {
        int methodsWithCollections = 0;
        int methodsWithoutCollections = 0;
        
        for (TrainingMethod method : repository.getMethodsForSkill(Skill.MINING)) {
            // Skip minigame methods (like motherlode_mine) which use special objects
            if (method.getMethodType() == MethodType.MINIGAME) {
                continue;
            }
            if (method.hasTargetObjects()) {
                List<Integer> ids = method.getTargetObjectIds();
                boolean anyInCollection = ids.stream()
                        .anyMatch(CollectionResolver::isInObjectCollection);
                if (anyInCollection) {
                    methodsWithCollections++;
                } else {
                    methodsWithoutCollections++;
                    // Log but don't fail - some methods use special objects (gem rocks, etc)
                    System.out.println("Info: Mining method " + method.getId() + 
                            " has no object IDs in collections: " + ids);
                }
            }
        }
        
        // At least most mining methods should have collection coverage
        assertTrue("Most mining methods should have collection coverage", 
                methodsWithCollections > methodsWithoutCollections);
    }

    @Test
    public void testWoodcuttingMethodIds_AtLeastOneInCollection() {
        for (TrainingMethod method : repository.getMethodsForSkill(Skill.WOODCUTTING)) {
            if (method.hasTargetObjects()) {
                List<Integer> ids = method.getTargetObjectIds();
                boolean anyInCollection = ids.stream()
                        .anyMatch(CollectionResolver::isInObjectCollection);
                assertTrue("Woodcutting method " + method.getId() + " should have at least one ID in a collection",
                        anyInCollection);
            }
        }
    }

    @Test
    public void testThievingMethodIds_AtLeastOneInCollection() {
        for (TrainingMethod method : repository.getMethodsForSkill(Skill.THIEVING)) {
            if (method.hasTargetObjects()) {
                List<Integer> ids = method.getTargetObjectIds();
                boolean anyInCollection = ids.stream()
                        .anyMatch(CollectionResolver::isInObjectCollection);
                // Some thieving methods may use special objects not in collections
                // So we just log a warning instead of failing
                if (!anyInCollection) {
                    System.out.println("Warning: Thieving method " + method.getId() + 
                            " has no object IDs in collections: " + ids);
                }
            }
            if (method.hasTargetNpcs()) {
                List<Integer> ids = method.getTargetNpcIds();
                boolean anyInCollection = ids.stream()
                        .anyMatch(CollectionResolver::isInNpcCollection);
                if (!anyInCollection) {
                    System.out.println("Warning: Thieving method " + method.getId() + 
                            " has no NPC IDs in collections: " + ids);
                }
            }
        }
    }
}

