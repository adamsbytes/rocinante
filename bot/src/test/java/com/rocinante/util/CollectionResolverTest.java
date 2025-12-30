package com.rocinante.util;

import net.runelite.api.NpcID;
import net.runelite.api.ObjectID;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for CollectionResolver utility class.
 * Validates reflection-based collection expansion for training method IDs.
 * 
 * <p>Tests verify:
 * <ul>
 *   <li>Single ID expansion to full collection</li>
 *   <li>Multiple ID expansion merging collections</li>
 *   <li>Fallback behavior for unknown IDs</li>
 *   <li>Edge cases (null, empty lists)</li>
 * </ul>
 */
public class CollectionResolverTest {

    // ========================================================================
    // Object ID Expansion Tests
    // ========================================================================

    @Test
    public void testExpandSingleObjectId_CopperRocks() {
        // 10079 is ObjectID.COPPER_ROCKS, should expand to all copper rock variants
        List<Integer> input = Collections.singletonList(ObjectID.COPPER_ROCKS);
        List<Integer> result = CollectionResolver.expandObjectIds(input);
        
        // Should expand to include all copper rock variants
        assertTrue("Result should contain more than input", result.size() > 1);
        assertTrue("Result should contain original ID", result.contains(ObjectID.COPPER_ROCKS));
        assertTrue("Result should contain variant 10943", result.contains(ObjectID.COPPER_ROCKS_10943));
        assertTrue("Result should contain variant 11161", result.contains(ObjectID.COPPER_ROCKS_11161));
        assertTrue("Result should contain variant 37944", result.contains(ObjectID.COPPER_ROCKS_37944));
    }

    @Test
    public void testExpandSingleObjectId_TinRocks() {
        List<Integer> input = Collections.singletonList(ObjectID.TIN_ROCKS);
        List<Integer> result = CollectionResolver.expandObjectIds(input);
        
        assertTrue("Result should contain more than input", result.size() > 1);
        assertTrue("Result should contain original ID", result.contains(ObjectID.TIN_ROCKS));
    }

    @Test
    public void testExpandMultipleCollections_CopperAndTin() {
        // IDs from two different collections
        List<Integer> input = Arrays.asList(ObjectID.COPPER_ROCKS, ObjectID.TIN_ROCKS);
        List<Integer> result = CollectionResolver.expandObjectIds(input);
        
        // Should include variants from both collections
        assertTrue("Result should have multiple IDs", result.size() > 2);
        assertTrue("Should contain copper rock", result.contains(ObjectID.COPPER_ROCKS));
        assertTrue("Should contain copper variant", result.contains(ObjectID.COPPER_ROCKS_10943));
        assertTrue("Should contain tin rock", result.contains(ObjectID.TIN_ROCKS));
        assertTrue("Should contain tin variant", result.contains(ObjectID.TIN_ROCKS_11360));
    }

    @Test
    public void testExpandOakTrees() {
        List<Integer> input = Collections.singletonList(ObjectID.OAK_TREE_10820);
        List<Integer> result = CollectionResolver.expandObjectIds(input);
        
        assertTrue("Result should contain multiple oak variants", result.size() > 1);
        assertTrue("Result should contain base oak", result.contains(ObjectID.OAK_TREE));
        assertTrue("Result should contain the input ID", result.contains(ObjectID.OAK_TREE_10820));
    }

    @Test
    public void testExpandWillowTrees() {
        List<Integer> input = Collections.singletonList(ObjectID.WILLOW_TREE_10819);
        List<Integer> result = CollectionResolver.expandObjectIds(input);
        
        assertTrue("Result should contain multiple willow variants", result.size() > 1);
        assertTrue("Result should contain base willow", result.contains(ObjectID.WILLOW_TREE));
    }

    @Test
    public void testExpandChaosAltars() {
        List<Integer> input = Collections.singletonList(ObjectID.CHAOS_ALTAR);
        List<Integer> result = CollectionResolver.expandObjectIds(input);
        
        // Our updated CHAOS_ALTARS collection should have all variants
        assertTrue("Result should contain multiple altar variants", result.size() >= 4);
        assertTrue("Should contain base chaos altar", result.contains(ObjectID.CHAOS_ALTAR));
        assertTrue("Should contain variant 411", result.contains(ObjectID.CHAOS_ALTAR_411));
        assertTrue("Should contain variant 412", result.contains(ObjectID.CHAOS_ALTAR_412));
        assertTrue("Should contain variant 26258", result.contains(ObjectID.CHAOS_ALTAR_26258));
    }

    @Test
    public void testExpandBakeryStalls() {
        List<Integer> input = Collections.singletonList(ObjectID.BAKERY_STALL);
        List<Integer> result = CollectionResolver.expandObjectIds(input);
        
        assertTrue("Result should contain multiple stall variants", result.size() > 1);
        assertTrue("Should contain variant 44031", result.contains(ObjectID.BAKERY_STALL_44031));
    }

    @Test
    public void testExpandSilkStalls() {
        List<Integer> input = Collections.singletonList(ObjectID.SILK_STALL);
        List<Integer> result = CollectionResolver.expandObjectIds(input);
        
        // Our updated SILK_STALLS collection has all 8 variants
        assertTrue("Result should contain multiple stall variants", result.size() >= 8);
        assertTrue("Should contain variant 20344", result.contains(ObjectID.SILK_STALL_20344));
        assertTrue("Should contain variant 58101", result.contains(ObjectID.SILK_STALL_58101));
    }

    @Test
    public void testNonCollectionObjectId_Fallback() {
        // Use an ID that's unlikely to be in any collection
        List<Integer> input = Collections.singletonList(99999);
        List<Integer> result = CollectionResolver.expandObjectIds(input);
        
        assertEquals("Should return original list unchanged", input, result);
    }

    @Test
    public void testMixedKnownAndUnknownObjectIds() {
        // One ID from collection, one unknown
        List<Integer> input = Arrays.asList(ObjectID.COPPER_ROCKS, 99999);
        List<Integer> result = CollectionResolver.expandObjectIds(input);
        
        // Should expand copper rocks but keep unknown ID
        assertTrue("Result should contain more IDs", result.size() > 2);
        assertTrue("Should contain copper variants", result.contains(ObjectID.COPPER_ROCKS_10943));
        assertTrue("Should preserve unknown ID", result.contains(99999));
    }

    // ========================================================================
    // NPC ID Expansion Tests
    // ========================================================================

    @Test
    public void testExpandSingleNpcId_MasterFarmer() {
        List<Integer> input = Collections.singletonList(NpcID.MASTER_FARMER);
        List<Integer> result = CollectionResolver.expandNpcIds(input);
        
        // Our updated MASTER_FARMERS collection has 16 variants
        assertTrue("Result should contain multiple farmer variants", result.size() >= 14);
        assertTrue("Should contain base farmer", result.contains(NpcID.MASTER_FARMER));
        assertTrue("Should contain variant 5731", result.contains(NpcID.MASTER_FARMER_5731));
        assertTrue("Should contain variant 11940", result.contains(NpcID.MASTER_FARMER_11940));
        assertTrue("Should contain variant 14758", result.contains(NpcID.MASTER_FARMER_14758));
    }

    @Test
    public void testExpandKnightOfArdougne() {
        List<Integer> input = Collections.singletonList(NpcID.KNIGHT_OF_ARDOUGNE);
        List<Integer> result = CollectionResolver.expandNpcIds(input);
        
        // Our updated KNIGHTS_OF_ARDOUGNE collection has 11 variants
        assertTrue("Result should contain multiple knight variants", result.size() >= 11);
        assertTrue("Should contain variant 3300", result.contains(NpcID.KNIGHT_OF_ARDOUGNE_3300));
        assertTrue("Should contain variant 11902", result.contains(NpcID.KNIGHT_OF_ARDOUGNE_11902));
        assertTrue("Should contain variant 11936", result.contains(NpcID.KNIGHT_OF_ARDOUGNE_11936));
    }

    @Test
    public void testExpandCitizens() {
        // Use a Man NPC ID
        List<Integer> input = Collections.singletonList(NpcID.MAN);
        List<Integer> result = CollectionResolver.expandNpcIds(input);
        
        // Should expand to include many man/woman variants
        assertTrue("Result should contain many citizen variants", result.size() > 10);
    }

    @Test
    public void testExpandFishingSpots() {
        List<Integer> input = Collections.singletonList(NpcID.FISHING_SPOT);
        List<Integer> result = CollectionResolver.expandNpcIds(input);
        
        assertTrue("Result should contain multiple fishing spot variants", result.size() > 1);
    }

    @Test
    public void testNonCollectionNpcId_Fallback() {
        List<Integer> input = Collections.singletonList(99999);
        List<Integer> result = CollectionResolver.expandNpcIds(input);
        
        assertEquals("Should return original list unchanged", input, result);
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testExpandObjectIds_NullInput() {
        List<Integer> result = CollectionResolver.expandObjectIds(null);
        assertNull("Should return null for null input", result);
    }

    @Test
    public void testExpandObjectIds_EmptyList() {
        List<Integer> input = Collections.emptyList();
        List<Integer> result = CollectionResolver.expandObjectIds(input);
        
        assertTrue("Should return empty list for empty input", result.isEmpty());
    }

    @Test
    public void testExpandNpcIds_NullInput() {
        List<Integer> result = CollectionResolver.expandNpcIds(null);
        assertNull("Should return null for null input", result);
    }

    @Test
    public void testExpandNpcIds_EmptyList() {
        List<Integer> input = Collections.emptyList();
        List<Integer> result = CollectionResolver.expandNpcIds(input);
        
        assertTrue("Should return empty list for empty input", result.isEmpty());
    }

    @Test
    public void testNoDuplicatesInResult() {
        // If we pass duplicate IDs or IDs from same collection
        List<Integer> input = Arrays.asList(
                ObjectID.COPPER_ROCKS,
                ObjectID.COPPER_ROCKS_10943  // Already in same collection
        );
        List<Integer> result = CollectionResolver.expandObjectIds(input);
        
        // Count each ID
        long uniqueCount = result.stream().distinct().count();
        assertEquals("Result should have no duplicates", uniqueCount, result.size());
    }

    @Test
    public void testPreservesOrderWithCollectionFirst() {
        List<Integer> input = Arrays.asList(ObjectID.COPPER_ROCKS, ObjectID.TIN_ROCKS);
        List<Integer> result = CollectionResolver.expandObjectIds(input);
        
        // First collection's IDs should come before second collection's
        int copperIndex = result.indexOf(ObjectID.COPPER_ROCKS);
        int tinIndex = result.indexOf(ObjectID.TIN_ROCKS);
        
        assertTrue("Copper rocks should appear before tin rocks", copperIndex < tinIndex);
    }

    // ========================================================================
    // Helper Method Tests
    // ========================================================================

    @Test
    public void testIsInObjectCollection_Known() {
        assertTrue("COPPER_ROCKS should be in a collection", 
                CollectionResolver.isInObjectCollection(ObjectID.COPPER_ROCKS));
        assertTrue("OAK_TREE should be in a collection", 
                CollectionResolver.isInObjectCollection(ObjectID.OAK_TREE));
        assertTrue("BANK_BOOTH should be in a collection", 
                CollectionResolver.isInObjectCollection(ObjectID.BANK_BOOTH));
    }

    @Test
    public void testIsInObjectCollection_Unknown() {
        assertFalse("Unknown ID should not be in collection", 
                CollectionResolver.isInObjectCollection(99999));
    }

    @Test
    public void testIsInNpcCollection_Known() {
        assertTrue("MASTER_FARMER should be in a collection", 
                CollectionResolver.isInNpcCollection(NpcID.MASTER_FARMER));
        assertTrue("BANKER should be in a collection", 
                CollectionResolver.isInNpcCollection(NpcID.BANKER));
    }

    @Test
    public void testIsInNpcCollection_Unknown() {
        assertFalse("Unknown ID should not be in collection", 
                CollectionResolver.isInNpcCollection(99999));
    }

    @Test
    public void testGetObjectCollection_ReturnsFullCollection() {
        List<Integer> collection = CollectionResolver.getObjectCollection(ObjectID.COPPER_ROCKS);
        
        assertNotNull("Should return collection for known ID", collection);
        assertTrue("Collection should have multiple entries", collection.size() > 1);
        assertEquals("Should match ObjectCollections.COPPER_ROCKS", 
                ObjectCollections.COPPER_ROCKS, collection);
    }

    @Test
    public void testGetObjectCollection_ReturnsNullForUnknown() {
        List<Integer> collection = CollectionResolver.getObjectCollection(99999);
        assertNull("Should return null for unknown ID", collection);
    }

    @Test
    public void testGetNpcCollection_ReturnsFullCollection() {
        List<Integer> collection = CollectionResolver.getNpcCollection(NpcID.MASTER_FARMER);
        
        assertNotNull("Should return collection for known ID", collection);
        assertTrue("Collection should have multiple entries", collection.size() > 1);
        assertEquals("Should match NpcCollections.MASTER_FARMERS", 
                NpcCollections.MASTER_FARMERS, collection);
    }

    @Test
    public void testGetStats_ReturnsValidCounts() {
        Map<String, Integer> stats = CollectionResolver.getStats();
        
        assertNotNull("Stats should not be null", stats);
        assertTrue("Should have object ID count", stats.containsKey("objectIdCount"));
        assertTrue("Should have NPC ID count", stats.containsKey("npcIdCount"));
        assertTrue("Object ID count should be positive", stats.get("objectIdCount") > 0);
        assertTrue("NPC ID count should be positive", stats.get("npcIdCount") > 0);
    }
}

