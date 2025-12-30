package com.rocinante.progression;

import net.runelite.api.Client;
import net.runelite.api.Varbits;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoJUnit;
import org.mockito.quality.Strictness;

import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DiaryTracker} utility class.
 *
 * Tests achievement diary completion checking, tier calculations,
 * and diary benefit queries.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class DiaryTrackerTest {

    @Mock
    private Client client;

    private DiaryTracker tracker;

    @Before
    public void setUp() {
        tracker = new DiaryTracker(client);
        // Default all varbits to 0 (incomplete)
        when(client.getVarbitValue(anyInt())).thenReturn(0);
    }

    // ========================================================================
    // DiaryRegion Enum Tests
    // ========================================================================

    @Test
    public void testDiaryRegion_AllRegionsExist() {
        DiaryTracker.DiaryRegion[] regions = DiaryTracker.DiaryRegion.values();
        assertEquals("Should have 12 diary regions", 12, regions.length);
    }

    @Test
    public void testDiaryRegion_DisplayNames() {
        assertEquals("Ardougne", DiaryTracker.DiaryRegion.ARDOUGNE.getDisplayName());
        assertEquals("Desert", DiaryTracker.DiaryRegion.DESERT.getDisplayName());
        assertEquals("Falador", DiaryTracker.DiaryRegion.FALADOR.getDisplayName());
        assertEquals("Fremennik", DiaryTracker.DiaryRegion.FREMENNIK.getDisplayName());
        assertEquals("Kandarin", DiaryTracker.DiaryRegion.KANDARIN.getDisplayName());
        assertEquals("Karamja", DiaryTracker.DiaryRegion.KARAMJA.getDisplayName());
        assertEquals("Kourend & Kebos", DiaryTracker.DiaryRegion.KOUREND.getDisplayName());
        assertEquals("Lumbridge & Draynor", DiaryTracker.DiaryRegion.LUMBRIDGE.getDisplayName());
        assertEquals("Morytania", DiaryTracker.DiaryRegion.MORYTANIA.getDisplayName());
        assertEquals("Varrock", DiaryTracker.DiaryRegion.VARROCK.getDisplayName());
        assertEquals("Western Provinces", DiaryTracker.DiaryRegion.WESTERN.getDisplayName());
        assertEquals("Wilderness", DiaryTracker.DiaryRegion.WILDERNESS.getDisplayName());
    }

    // ========================================================================
    // DiaryTier Enum Tests
    // ========================================================================

    @Test
    public void testDiaryTier_Ordering() {
        assertTrue("EASY should be lower than MEDIUM", 
                DiaryTracker.DiaryTier.EASY.ordinal() < DiaryTracker.DiaryTier.MEDIUM.ordinal());
        assertTrue("MEDIUM should be lower than HARD", 
                DiaryTracker.DiaryTier.MEDIUM.ordinal() < DiaryTracker.DiaryTier.HARD.ordinal());
        assertTrue("HARD should be lower than ELITE", 
                DiaryTracker.DiaryTier.HARD.ordinal() < DiaryTracker.DiaryTier.ELITE.ordinal());
    }

    @Test
    public void testDiaryTier_Levels() {
        assertEquals("EASY should have level 1", 1, DiaryTracker.DiaryTier.EASY.getLevel());
        assertEquals("MEDIUM should have level 2", 2, DiaryTracker.DiaryTier.MEDIUM.getLevel());
        assertEquals("HARD should have level 3", 3, DiaryTracker.DiaryTier.HARD.getLevel());
        assertEquals("ELITE should have level 4", 4, DiaryTracker.DiaryTier.ELITE.getLevel());
    }

    // ========================================================================
    // isComplete() Tests - By Region and Tier
    // ========================================================================

    @Test
    public void testIsComplete_LumbridgeEasy_Complete() {
        when(client.getVarbitValue(Varbits.DIARY_LUMBRIDGE_EASY)).thenReturn(1);
        
        assertTrue("Lumbridge Easy should be complete", 
                tracker.isComplete(DiaryTracker.DiaryRegion.LUMBRIDGE, 
                        DiaryTracker.DiaryTier.EASY));
    }

    @Test
    public void testIsComplete_LumbridgeEasy_NotComplete() {
        when(client.getVarbitValue(Varbits.DIARY_LUMBRIDGE_EASY)).thenReturn(0);
        
        assertFalse("Lumbridge Easy should not be complete", 
                tracker.isComplete(DiaryTracker.DiaryRegion.LUMBRIDGE, 
                        DiaryTracker.DiaryTier.EASY));
    }

    @Test
    public void testIsComplete_LumbridgeElite_Complete() {
        when(client.getVarbitValue(Varbits.DIARY_LUMBRIDGE_ELITE)).thenReturn(1);
        
        assertTrue("Lumbridge Elite should be complete", 
                tracker.isComplete(DiaryTracker.DiaryRegion.LUMBRIDGE, 
                        DiaryTracker.DiaryTier.ELITE));
    }

    @Test
    public void testIsComplete_WildernessHard_Complete() {
        when(client.getVarbitValue(Varbits.DIARY_WILDERNESS_HARD)).thenReturn(1);
        
        assertTrue("Wilderness Hard should be complete", 
                tracker.isComplete(DiaryTracker.DiaryRegion.WILDERNESS, 
                        DiaryTracker.DiaryTier.HARD));
    }

    @Test
    public void testIsComplete_ArdougneMedium_Complete() {
        when(client.getVarbitValue(Varbits.DIARY_ARDOUGNE_MEDIUM)).thenReturn(1);
        
        assertTrue("Ardougne Medium should be complete", 
                tracker.isComplete(DiaryTracker.DiaryRegion.ARDOUGNE, 
                        DiaryTracker.DiaryTier.MEDIUM));
    }

    // ========================================================================
    // isComplete() Tests - By String
    // ========================================================================

    @Test
    public void testIsComplete_StringParsing_LumbridgeElite() {
        when(client.getVarbitValue(Varbits.DIARY_LUMBRIDGE_ELITE)).thenReturn(1);
        
        assertTrue("Should parse LUMBRIDGE_ELITE", 
                tracker.isComplete("LUMBRIDGE_ELITE"));
    }

    @Test
    public void testIsComplete_StringParsing_VarrockMedium() {
        when(client.getVarbitValue(Varbits.DIARY_VARROCK_MEDIUM)).thenReturn(1);
        
        assertTrue("Should parse VARROCK_MEDIUM", 
                tracker.isComplete("VARROCK_MEDIUM"));
    }

    @Test
    public void testIsComplete_StringParsing_LowercaseInput() {
        when(client.getVarbitValue(Varbits.DIARY_FALADOR_HARD)).thenReturn(1);
        
        assertTrue("Should parse lowercase falador_hard", 
                tracker.isComplete("falador_hard"));
    }

    @Test
    public void testIsComplete_StringParsing_InvalidFormat() {
        assertFalse("Invalid format should return false", 
                tracker.isComplete("INVALID_FORMAT"));
    }

    @Test
    public void testIsComplete_StringParsing_EmptyString() {
        assertFalse("Empty string should return false", tracker.isComplete(""));
    }

    @Test
    public void testIsComplete_StringParsing_NullString() {
        assertFalse("Null string should return false", tracker.isComplete((String) null));
    }

    // ========================================================================
    // getHighestCompletedTier() Tests
    // ========================================================================

    @Test
    public void testGetHighestCompletedTier_AllComplete_ReturnsElite() {
        when(client.getVarbitValue(Varbits.DIARY_ARDOUGNE_EASY)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_ARDOUGNE_MEDIUM)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_ARDOUGNE_HARD)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_ARDOUGNE_ELITE)).thenReturn(1);
        
        assertEquals("Should return ELITE when all complete", 
                DiaryTracker.DiaryTier.ELITE, 
                tracker.getHighestCompletedTier(DiaryTracker.DiaryRegion.ARDOUGNE));
    }

    @Test
    public void testGetHighestCompletedTier_HardComplete_ReturnsHard() {
        when(client.getVarbitValue(Varbits.DIARY_ARDOUGNE_EASY)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_ARDOUGNE_MEDIUM)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_ARDOUGNE_HARD)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_ARDOUGNE_ELITE)).thenReturn(0);
        
        assertEquals("Should return HARD when hard is highest", 
                DiaryTracker.DiaryTier.HARD, 
                tracker.getHighestCompletedTier(DiaryTracker.DiaryRegion.ARDOUGNE));
    }

    @Test
    public void testGetHighestCompletedTier_MediumComplete_ReturnsMedium() {
        when(client.getVarbitValue(Varbits.DIARY_DESERT_EASY)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_DESERT_MEDIUM)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_DESERT_HARD)).thenReturn(0);
        when(client.getVarbitValue(Varbits.DIARY_DESERT_ELITE)).thenReturn(0);
        
        assertEquals("Should return MEDIUM when medium is highest", 
                DiaryTracker.DiaryTier.MEDIUM, 
                tracker.getHighestCompletedTier(DiaryTracker.DiaryRegion.DESERT));
    }

    @Test
    public void testGetHighestCompletedTier_EasyComplete_ReturnsEasy() {
        when(client.getVarbitValue(Varbits.DIARY_FREMENNIK_EASY)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_FREMENNIK_MEDIUM)).thenReturn(0);
        when(client.getVarbitValue(Varbits.DIARY_FREMENNIK_HARD)).thenReturn(0);
        when(client.getVarbitValue(Varbits.DIARY_FREMENNIK_ELITE)).thenReturn(0);
        
        assertEquals("Should return EASY when only easy complete", 
                DiaryTracker.DiaryTier.EASY, 
                tracker.getHighestCompletedTier(DiaryTracker.DiaryRegion.FREMENNIK));
    }

    @Test
    public void testGetHighestCompletedTier_NoneComplete_ReturnsNull() {
        // All default to 0 (incomplete) from setup
        assertNull("Should return null when none complete", 
                tracker.getHighestCompletedTier(DiaryTracker.DiaryRegion.KANDARIN));
    }

    // ========================================================================
    // getCompletedTiers() Tests
    // ========================================================================

    @Test
    public void testGetCompletedTiers_AllComplete() {
        when(client.getVarbitValue(Varbits.DIARY_MORYTANIA_EASY)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_MORYTANIA_MEDIUM)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_MORYTANIA_HARD)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_MORYTANIA_ELITE)).thenReturn(1);
        
        Set<DiaryTracker.DiaryTier> completed = 
                tracker.getCompletedTiers(DiaryTracker.DiaryRegion.MORYTANIA);
        
        assertEquals("Should have 4 completed tiers", 4, completed.size());
        assertTrue("Should contain EASY", completed.contains(DiaryTracker.DiaryTier.EASY));
        assertTrue("Should contain MEDIUM", completed.contains(DiaryTracker.DiaryTier.MEDIUM));
        assertTrue("Should contain HARD", completed.contains(DiaryTracker.DiaryTier.HARD));
        assertTrue("Should contain ELITE", completed.contains(DiaryTracker.DiaryTier.ELITE));
    }

    @Test
    public void testGetCompletedTiers_PartialComplete() {
        when(client.getVarbitValue(Varbits.DIARY_VARROCK_EASY)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_VARROCK_MEDIUM)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_VARROCK_HARD)).thenReturn(0);
        when(client.getVarbitValue(Varbits.DIARY_VARROCK_ELITE)).thenReturn(0);
        
        Set<DiaryTracker.DiaryTier> completed = 
                tracker.getCompletedTiers(DiaryTracker.DiaryRegion.VARROCK);
        
        assertEquals("Should have 2 completed tiers", 2, completed.size());
        assertTrue("Should contain EASY", completed.contains(DiaryTracker.DiaryTier.EASY));
        assertTrue("Should contain MEDIUM", completed.contains(DiaryTracker.DiaryTier.MEDIUM));
    }

    @Test
    public void testGetCompletedTiers_NoneComplete() {
        // All default to 0 (incomplete) from setup
        Set<DiaryTracker.DiaryTier> completed = 
                tracker.getCompletedTiers(DiaryTracker.DiaryRegion.WESTERN);
        
        assertTrue("Should have empty set when none complete", completed.isEmpty());
    }

    // ========================================================================
    // hasFairyRingWithoutStaff() Tests
    // ========================================================================

    @Test
    public void testHasFairyRingWithoutStaff_LumbridgeEliteComplete_True() {
        when(client.getVarbitValue(Varbits.DIARY_LUMBRIDGE_ELITE)).thenReturn(1);
        
        assertTrue("Should have fairy ring without staff with Lumbridge Elite", 
                tracker.hasFairyRingWithoutStaff());
    }

    @Test
    public void testHasFairyRingWithoutStaff_LumbridgeEliteNotComplete_False() {
        when(client.getVarbitValue(Varbits.DIARY_LUMBRIDGE_ELITE)).thenReturn(0);
        
        assertFalse("Should not have fairy ring without staff without Lumbridge Elite", 
                tracker.hasFairyRingWithoutStaff());
    }

    // ========================================================================
    // hasUnlimitedArdougneTeleport() Tests
    // ========================================================================

    @Test
    public void testHasUnlimitedArdougneTeleport_MediumComplete_True() {
        when(client.getVarbitValue(Varbits.DIARY_ARDOUGNE_MEDIUM)).thenReturn(1);
        
        assertTrue("Should have unlimited Ardougne teleport with Ardougne Medium", 
                tracker.hasUnlimitedArdougneTeleport());
    }

    @Test
    public void testHasUnlimitedArdougneTeleport_MediumNotComplete_False() {
        when(client.getVarbitValue(Varbits.DIARY_ARDOUGNE_MEDIUM)).thenReturn(0);
        
        assertFalse("Should not have unlimited Ardougne teleport without Ardougne Medium", 
                tracker.hasUnlimitedArdougneTeleport());
    }

    // ========================================================================
    // hasNotedBones() Tests
    // ========================================================================

    @Test
    public void testHasNotedBones_MorytaniaHardComplete_True() {
        when(client.getVarbitValue(Varbits.DIARY_MORYTANIA_HARD)).thenReturn(1);
        
        assertTrue("Should have noted bones with Morytania Hard", 
                tracker.hasNotedBones());
    }

    // ========================================================================
    // hasExtraHerbPatch() Tests
    // ========================================================================

    @Test
    public void testHasExtraHerbPatch_FaladorHardComplete_True() {
        when(client.getVarbitValue(Varbits.DIARY_FALADOR_HARD)).thenReturn(1);
        
        assertTrue("Should have extra herb patch with Falador Hard", 
                tracker.hasExtraHerbPatch());
    }

    // ========================================================================
    // hasKaramjaElite() Tests
    // ========================================================================

    @Test
    public void testHasKaramjaElite_Complete_True() {
        when(client.getVarbitValue(Varbits.DIARY_KARAMJA_ELITE)).thenReturn(1);
        
        assertTrue("Should have Karamja Elite complete", 
                tracker.hasKaramjaElite());
    }

    // ========================================================================
    // hasWildernessElite() Tests
    // ========================================================================

    @Test
    public void testHasWildernessElite_Complete_True() {
        when(client.getVarbitValue(Varbits.DIARY_WILDERNESS_ELITE)).thenReturn(1);
        
        assertTrue("Should have Wilderness Elite complete", 
                tracker.hasWildernessElite());
    }

    // ========================================================================
    // getTotalCompletedCount() Tests
    // ========================================================================

    @Test
    public void testGetTotalCompletedCount_NoneComplete() {
        // All default to 0 (incomplete) from setup
        assertEquals("Should have 0 complete when none done", 0, tracker.getTotalCompletedCount());
    }

    @Test
    public void testGetTotalCompletedCount_SomeComplete() {
        // Complete Lumbridge Easy and Medium
        when(client.getVarbitValue(Varbits.DIARY_LUMBRIDGE_EASY)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_LUMBRIDGE_MEDIUM)).thenReturn(1);
        
        assertEquals("Should have 2 complete", 2, tracker.getTotalCompletedCount());
    }

    // ========================================================================
    // hasAnyComplete() Tests
    // ========================================================================

    @Test
    public void testHasAnyComplete_OneComplete_True() {
        when(client.getVarbitValue(Varbits.DIARY_KARAMJA_EASY)).thenReturn(1);
        
        assertTrue("Should have any complete with Easy done", 
                tracker.hasAnyComplete(DiaryTracker.DiaryRegion.KARAMJA));
    }

    @Test
    public void testHasAnyComplete_NoneComplete_False() {
        // All default to 0 (incomplete) from setup
        assertFalse("Should not have any complete with none done", 
                tracker.hasAnyComplete(DiaryTracker.DiaryRegion.KOUREND));
    }

    // ========================================================================
    // getRegionsWithCompletion() Tests
    // ========================================================================

    @Test
    public void testGetRegionsWithCompletion_SomeComplete() {
        when(client.getVarbitValue(Varbits.DIARY_LUMBRIDGE_EASY)).thenReturn(1);
        when(client.getVarbitValue(Varbits.DIARY_VARROCK_MEDIUM)).thenReturn(1);
        
        Set<DiaryTracker.DiaryRegion> regions = tracker.getRegionsWithCompletion();
        
        assertEquals("Should have 2 regions with completion", 2, regions.size());
        assertTrue("Should contain LUMBRIDGE", 
                regions.contains(DiaryTracker.DiaryRegion.LUMBRIDGE));
        assertTrue("Should contain VARROCK", 
                regions.contains(DiaryTracker.DiaryRegion.VARROCK));
    }

    @Test
    public void testGetRegionsWithCompletion_NoneComplete() {
        // All default to 0 (incomplete) from setup
        Set<DiaryTracker.DiaryRegion> regions = tracker.getRegionsWithCompletion();
        
        assertTrue("Should have empty set when none complete", regions.isEmpty());
    }
}

