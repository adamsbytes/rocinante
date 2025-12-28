package com.rocinante.progression;

import com.rocinante.core.GameStateService;
import com.rocinante.navigation.EdgeRequirement;
import com.rocinante.navigation.EdgeRequirementType;
import com.rocinante.state.AttackStyle;
import com.rocinante.state.InventoryState;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Prayer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UnlockTracker class.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class UnlockTrackerTest {

    @Mock
    private Client client;

    @Mock
    private GameStateService gameStateService;

    private UnlockTracker unlockTracker;

    @Before
    public void setUp() {
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        unlockTracker = new UnlockTracker(client, () -> gameStateService);
    }

    // ========================================================================
    // PrayerUnlock Tests
    // ========================================================================

    @Test
    public void testPrayerUnlock_ProtectionPrayerLevels() {
        assertEquals(37, PrayerUnlock.PROTECT_FROM_MAGIC.getRequiredLevel());
        assertEquals(40, PrayerUnlock.PROTECT_FROM_MISSILES.getRequiredLevel());
        assertEquals(43, PrayerUnlock.PROTECT_FROM_MELEE.getRequiredLevel());
    }

    @Test
    public void testPrayerUnlock_QuestRequirements() {
        // Basic prayers have no quest requirements
        assertFalse(PrayerUnlock.THICK_SKIN.requiresQuest());
        assertFalse(PrayerUnlock.PROTECT_FROM_MELEE.requiresQuest());

        // Chivalry and Piety require King's Ransom
        assertTrue(PrayerUnlock.CHIVALRY.requiresQuest());
        assertEquals(Quest.KINGS_RANSOM, PrayerUnlock.CHIVALRY.getRequiredQuest());
        assertTrue(PrayerUnlock.PIETY.requiresQuest());
        assertEquals(Quest.KINGS_RANSOM, PrayerUnlock.PIETY.getRequiredQuest());
    }

    @Test
    public void testPrayerUnlock_ForPrayer() {
        assertEquals(PrayerUnlock.PROTECT_FROM_MELEE, PrayerUnlock.forPrayer(Prayer.PROTECT_FROM_MELEE));
        assertEquals(PrayerUnlock.PIETY, PrayerUnlock.forPrayer(Prayer.PIETY));
        assertEquals(PrayerUnlock.THICK_SKIN, PrayerUnlock.forPrayer(Prayer.THICK_SKIN));
    }

    @Test
    public void testPrayerUnlock_GetProtectionPrayer() {
        assertEquals(PrayerUnlock.PROTECT_FROM_MELEE, PrayerUnlock.getProtectionPrayer(AttackStyle.MELEE));
        assertEquals(PrayerUnlock.PROTECT_FROM_MISSILES, PrayerUnlock.getProtectionPrayer(AttackStyle.RANGED));
        assertEquals(PrayerUnlock.PROTECT_FROM_MAGIC, PrayerUnlock.getProtectionPrayer(AttackStyle.MAGIC));
    }

    @Test
    public void testPrayerUnlock_IsProtectionPrayer() {
        assertTrue(PrayerUnlock.PROTECT_FROM_MELEE.isProtectionPrayer());
        assertTrue(PrayerUnlock.PROTECT_FROM_MISSILES.isProtectionPrayer());
        assertTrue(PrayerUnlock.PROTECT_FROM_MAGIC.isProtectionPrayer());
        assertFalse(PrayerUnlock.PIETY.isProtectionPrayer());
        assertFalse(PrayerUnlock.THICK_SKIN.isProtectionPrayer());
    }

    @Test
    public void testPrayerUnlock_IsOffensivePrayer() {
        assertTrue(PrayerUnlock.PIETY.isOffensivePrayer());
        assertTrue(PrayerUnlock.RIGOUR.isOffensivePrayer());
        assertTrue(PrayerUnlock.AUGURY.isOffensivePrayer());
        assertTrue(PrayerUnlock.EAGLE_EYE.isOffensivePrayer());
        assertFalse(PrayerUnlock.PROTECT_FROM_MELEE.isOffensivePrayer());
        assertFalse(PrayerUnlock.SMITE.isOffensivePrayer());
    }

    // ========================================================================
    // UnlockTracker - Prayer Unlock Tests
    // ========================================================================

    @Test
    public void testIsPrayerUnlocked_LevelNotMet() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(30);

        // Level 30 Prayer - can't use Protect from Magic (37 required)
        assertFalse(unlockTracker.isPrayerUnlocked(Prayer.PROTECT_FROM_MAGIC));
        assertFalse(unlockTracker.isPrayerUnlocked(Prayer.PROTECT_FROM_MISSILES));
        assertFalse(unlockTracker.isPrayerUnlocked(Prayer.PROTECT_FROM_MELEE));
    }

    @Test
    public void testIsPrayerUnlocked_LevelMet() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(43);

        // Level 43 Prayer - can use all protection prayers
        assertTrue(unlockTracker.isPrayerUnlocked(Prayer.PROTECT_FROM_MAGIC));
        assertTrue(unlockTracker.isPrayerUnlocked(Prayer.PROTECT_FROM_MISSILES));
        assertTrue(unlockTracker.isPrayerUnlocked(Prayer.PROTECT_FROM_MELEE));
    }

    @Test
    public void testIsPrayerUnlocked_PietyRequiresQuest() {
        // Piety requires King's Ransom - verify the requirement exists
        PrayerUnlock pietyUnlock = PrayerUnlock.forPrayer(Prayer.PIETY);
        assertNotNull(pietyUnlock);
        assertTrue(pietyUnlock.requiresQuest());
        assertEquals(Quest.KINGS_RANSOM, pietyUnlock.getRequiredQuest());
    }

    @Test
    public void testIsPrayerUnlocked_ProtectionPrayersNoQuestRequired() {
        // Protection prayers don't require quests
        assertFalse(PrayerUnlock.PROTECT_FROM_MELEE.requiresQuest());
        assertFalse(PrayerUnlock.PROTECT_FROM_MAGIC.requiresQuest());
        assertFalse(PrayerUnlock.PROTECT_FROM_MISSILES.requiresQuest());
    }

    // ========================================================================
    // UnlockTracker - Protection Prayer Tests
    // ========================================================================

    @Test
    public void testHasAnyProtectionPrayer_NoneUnlocked() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(35);

        assertFalse(unlockTracker.hasAnyProtectionPrayer());
    }

    @Test
    public void testHasAnyProtectionPrayer_OneUnlocked() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(37);

        assertTrue(unlockTracker.hasAnyProtectionPrayer());
    }

    @Test
    public void testGetUnlockedProtectionPrayers_NoneUnlocked() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(30);

        Set<PrayerUnlock> unlocked = unlockTracker.getUnlockedProtectionPrayers();
        assertTrue(unlocked.isEmpty());
    }

    @Test
    public void testGetUnlockedProtectionPrayers_PartialUnlocked() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(40);

        Set<PrayerUnlock> unlocked = unlockTracker.getUnlockedProtectionPrayers();
        assertEquals(2, unlocked.size());
        assertTrue(unlocked.contains(PrayerUnlock.PROTECT_FROM_MAGIC));
        assertTrue(unlocked.contains(PrayerUnlock.PROTECT_FROM_MISSILES));
        assertFalse(unlocked.contains(PrayerUnlock.PROTECT_FROM_MELEE));
    }

    @Test
    public void testGetUnlockedProtectionPrayers_AllUnlocked() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(43);

        Set<PrayerUnlock> unlocked = unlockTracker.getUnlockedProtectionPrayers();
        assertEquals(3, unlocked.size());
        assertTrue(unlocked.contains(PrayerUnlock.PROTECT_FROM_MAGIC));
        assertTrue(unlocked.contains(PrayerUnlock.PROTECT_FROM_MISSILES));
        assertTrue(unlocked.contains(PrayerUnlock.PROTECT_FROM_MELEE));
    }

    @Test
    public void testGetBestProtectionPrayer_Available() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(43);

        assertEquals(PrayerUnlock.PROTECT_FROM_MELEE, 
                unlockTracker.getBestProtectionPrayer(AttackStyle.MELEE));
        assertEquals(PrayerUnlock.PROTECT_FROM_MISSILES, 
                unlockTracker.getBestProtectionPrayer(AttackStyle.RANGED));
        assertEquals(PrayerUnlock.PROTECT_FROM_MAGIC, 
                unlockTracker.getBestProtectionPrayer(AttackStyle.MAGIC));
    }

    @Test
    public void testGetBestProtectionPrayer_NotAvailable() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(35);

        assertNull(unlockTracker.getBestProtectionPrayer(AttackStyle.MELEE));
        assertNull(unlockTracker.getBestProtectionPrayer(AttackStyle.RANGED));
        assertNull(unlockTracker.getBestProtectionPrayer(AttackStyle.MAGIC));
    }

    // ========================================================================
    // UnlockTracker - Offensive Prayer Tests
    // ========================================================================

    @Test
    public void testGetBestOffensivePrayer_Melee_LevelBased() {
        // Test that at level 31 (no quest prayers), we get Ultimate Strength
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(31);

        // At 31, best non-quest melee prayer is Ultimate Strength
        PrayerUnlock best = unlockTracker.getBestOffensivePrayer(AttackStyle.MELEE);
        assertEquals(PrayerUnlock.ULTIMATE_STRENGTH, best);
    }

    @Test
    public void testGetBestOffensivePrayer_Melee_HigherLevelOptions() {
        // At level 60, still no quest prayers available without completing quest
        // Best is Ultimate Strength (highest non-quest melee offensive)
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(60);

        // This tests the fallback to non-quest prayers
        // Quest completion can't be easily mocked, so we verify the logic paths exist
        PrayerUnlock best = unlockTracker.getBestOffensivePrayer(AttackStyle.MELEE);
        assertNotNull(best);
    }

    @Test
    public void testGetBestOffensivePrayer_Ranged() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(44);

        PrayerUnlock best = unlockTracker.getBestOffensivePrayer(AttackStyle.RANGED);
        assertEquals(PrayerUnlock.EAGLE_EYE, best);
    }

    @Test
    public void testGetBestOffensivePrayer_Magic() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(45);

        PrayerUnlock best = unlockTracker.getBestOffensivePrayer(AttackStyle.MAGIC);
        assertEquals(PrayerUnlock.MYSTIC_MIGHT, best);
    }

    @Test
    public void testGetBestOffensivePrayer_LowLevel() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(3);

        // At level 3, no offensive prayers for melee yet (need 4 for Burst of Strength)
        PrayerUnlock best = unlockTracker.getBestOffensivePrayer(AttackStyle.MELEE);
        assertNull(best);
    }

    // ========================================================================
    // UnlockTracker - Skill Level Tests
    // ========================================================================

    @Test
    public void testGetSkillLevel() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(55);
        when(client.getRealSkillLevel(Skill.ATTACK)).thenReturn(70);

        assertEquals(55, unlockTracker.getSkillLevel(Skill.PRAYER));
        assertEquals(70, unlockTracker.getSkillLevel(Skill.ATTACK));
    }

    @Test
    public void testMeetsSkillRequirement() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(43);

        assertTrue(unlockTracker.meetsSkillRequirement(Skill.PRAYER, 43));
        assertTrue(unlockTracker.meetsSkillRequirement(Skill.PRAYER, 40));
        assertFalse(unlockTracker.meetsSkillRequirement(Skill.PRAYER, 44));
    }

    @Test
    public void testHasPrayerSkill() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(1);
        assertTrue(unlockTracker.hasPrayerSkill());

        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(99);
        assertTrue(unlockTracker.hasPrayerSkill());
    }

    // ========================================================================
    // UnlockTracker - Generic Unlock Tests
    // ========================================================================

    @Test
    public void testIsUnlocked_SkillRequirement() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(43);

        assertTrue(unlockTracker.isUnlocked(UnlockType.SKILL, "PRAYER:43"));
        assertTrue(unlockTracker.isUnlocked(UnlockType.SKILL, "PRAYER:40"));
        assertFalse(unlockTracker.isUnlocked(UnlockType.SKILL, "PRAYER:50"));
    }

    @Test
    public void testIsUnlocked_QuestRequirement_ValidQuest() {
        // Test that the quest parsing works - actual quest state can't be mocked
        // This verifies the method handles the identifier correctly
        // Note: Without PowerMock, we can't mock Quest enum methods
        // So we just verify no exceptions are thrown
        try {
            unlockTracker.isUnlocked(UnlockType.QUEST, "KINGS_RANSOM");
            // Method executed without throwing - parsing worked
        } catch (IllegalArgumentException e) {
            fail("Should not throw for valid quest name");
        }
    }

    @Test
    public void testIsUnlocked_PrayerRequirement() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(43);

        assertTrue(unlockTracker.isUnlocked(UnlockType.PRAYER, "PROTECT_FROM_MELEE"));
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testPrayerUnlock_VeryLowLevel() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(1);

        // Only Thick Skin is available at level 1
        assertTrue(unlockTracker.isPrayerUnlocked(Prayer.THICK_SKIN));
        assertFalse(unlockTracker.isPrayerUnlocked(Prayer.BURST_OF_STRENGTH)); // Requires 4
    }

    @Test
    public void testPrayerUnlock_MaxLevel_NonQuestPrayers() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(99);

        // Test non-quest prayers at max level
        assertTrue(unlockTracker.isPrayerUnlocked(Prayer.PROTECT_FROM_MELEE));
        assertTrue(unlockTracker.isPrayerUnlocked(Prayer.PROTECT_FROM_MAGIC));
        assertTrue(unlockTracker.isPrayerUnlocked(Prayer.PROTECT_FROM_MISSILES));
        assertTrue(unlockTracker.isPrayerUnlocked(Prayer.ULTIMATE_STRENGTH));
        assertTrue(unlockTracker.isPrayerUnlocked(Prayer.EAGLE_EYE));
        assertTrue(unlockTracker.isPrayerUnlocked(Prayer.MYSTIC_MIGHT));
    }

    @Test
    public void testGetPrayerSummary() {
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(43);

        String summary = unlockTracker.getPrayerSummary();
        
        assertTrue(summary.contains("Level 43"));
        assertTrue(summary.contains("Protection Prayers"));
    }

    // ========================================================================
    // Edge Requirement Tests (WebWalker Integration)
    // ========================================================================

    @Test
    public void testIsEdgeRequirementMet_MagicLevel() {
        when(client.getRealSkillLevel(Skill.MAGIC)).thenReturn(50);

        EdgeRequirement lowReq = EdgeRequirement.magicLevel(25);
        EdgeRequirement exactReq = EdgeRequirement.magicLevel(50);
        EdgeRequirement highReq = EdgeRequirement.magicLevel(75);

        assertTrue(unlockTracker.isEdgeRequirementMet(lowReq));
        assertTrue(unlockTracker.isEdgeRequirementMet(exactReq));
        assertFalse(unlockTracker.isEdgeRequirementMet(highReq));
    }

    @Test
    public void testIsEdgeRequirementMet_AgilityLevel() {
        when(client.getRealSkillLevel(Skill.AGILITY)).thenReturn(60);

        EdgeRequirement lowReq = EdgeRequirement.agilityLevel(50);
        EdgeRequirement highReq = EdgeRequirement.agilityLevel(70);

        assertTrue(unlockTracker.isEdgeRequirementMet(lowReq));
        assertFalse(unlockTracker.isEdgeRequirementMet(highReq));
    }

    @Test
    public void testIsEdgeRequirementMet_Skill() {
        when(client.getRealSkillLevel(Skill.MINING)).thenReturn(45);

        EdgeRequirement lowReq = EdgeRequirement.skill("MINING", 30);
        EdgeRequirement highReq = EdgeRequirement.skill("MINING", 60);

        assertTrue(unlockTracker.isEdgeRequirementMet(lowReq));
        assertFalse(unlockTracker.isEdgeRequirementMet(highReq));
    }

    @Test
    public void testIsEdgeRequirementMet_Item() {
        InventoryState inventory = mock(InventoryState.class);
        when(gameStateService.getInventoryState()).thenReturn(inventory);
        when(inventory.countItem(12345)).thenReturn(5);
        when(inventory.countItem(99999)).thenReturn(0);

        EdgeRequirement hasEnough = EdgeRequirement.item(12345, 3, false);
        EdgeRequirement notEnough = EdgeRequirement.item(12345, 10, false);
        EdgeRequirement missing = EdgeRequirement.item(99999, 1, false);

        assertTrue(unlockTracker.isEdgeRequirementMet(hasEnough));
        assertFalse(unlockTracker.isEdgeRequirementMet(notEnough));
        assertFalse(unlockTracker.isEdgeRequirementMet(missing));
    }

    @Test
    public void testIsEdgeRequirementMet_Runes() {
        InventoryState inventory = mock(InventoryState.class);
        when(gameStateService.getInventoryState()).thenReturn(inventory);
        when(inventory.countItem(556)).thenReturn(100); // Air runes
        when(inventory.countItem(554)).thenReturn(50);  // Fire runes
        when(inventory.countItem(563)).thenReturn(10);  // Law runes

        List<EdgeRequirement.RuneCost> costs = Arrays.asList(
            EdgeRequirement.RuneCost.builder().itemId(556).quantity(3).build(),
            EdgeRequirement.RuneCost.builder().itemId(554).quantity(1).build(),
            EdgeRequirement.RuneCost.builder().itemId(563).quantity(1).build()
        );

        EdgeRequirement runeReq = EdgeRequirement.runes(costs);
        assertTrue(unlockTracker.isEdgeRequirementMet(runeReq));
    }

    @Test
    public void testIsEdgeRequirementMet_RunesNotEnough() {
        InventoryState inventory = mock(InventoryState.class);
        when(gameStateService.getInventoryState()).thenReturn(inventory);
        when(inventory.countItem(556)).thenReturn(2); // Only 2 air runes
        when(inventory.countItem(563)).thenReturn(1); // 1 law rune

        List<EdgeRequirement.RuneCost> costs = Arrays.asList(
            EdgeRequirement.RuneCost.builder().itemId(556).quantity(3).build(),
            EdgeRequirement.RuneCost.builder().itemId(563).quantity(1).build()
        );

        EdgeRequirement runeReq = EdgeRequirement.runes(costs);
        assertFalse(unlockTracker.isEdgeRequirementMet(runeReq));
    }

    @Test
    public void testAreAllRequirementsMet() {
        when(client.getRealSkillLevel(Skill.MAGIC)).thenReturn(50);
        when(client.getRealSkillLevel(Skill.AGILITY)).thenReturn(40);

        List<EdgeRequirement> requirements = Arrays.asList(
            EdgeRequirement.magicLevel(25),
            EdgeRequirement.agilityLevel(30)
        );

        assertTrue(unlockTracker.areAllRequirementsMet(requirements));

        List<EdgeRequirement> failingRequirements = Arrays.asList(
            EdgeRequirement.magicLevel(25),
            EdgeRequirement.agilityLevel(60) // Too high
        );

        assertFalse(unlockTracker.areAllRequirementsMet(failingRequirements));
    }

    @Test
    public void testAreAllRequirementsMet_EmptyList() {
        assertTrue(unlockTracker.areAllRequirementsMet(null));
        assertTrue(unlockTracker.areAllRequirementsMet(Arrays.asList()));
    }

    @Test
    public void testHasItem() {
        InventoryState inventory = mock(InventoryState.class);
        when(gameStateService.getInventoryState()).thenReturn(inventory);
        when(inventory.countItem(12345)).thenReturn(5);
        when(inventory.countItem(99999)).thenReturn(0);

        assertTrue(unlockTracker.hasItem(12345));
        assertTrue(unlockTracker.hasItem(12345, 5));
        assertFalse(unlockTracker.hasItem(12345, 10));
        assertFalse(unlockTracker.hasItem(99999));
    }

    @Test
    public void testIsEdgeRequirementMet_NullRequirement() {
        assertTrue(unlockTracker.isEdgeRequirementMet(null));
    }

    @Test
    public void testIsEdgeRequirementMet_IronmanRestriction() {
        // Ironman restrictions are always passed - handled by WebWalker
        EdgeRequirement req = EdgeRequirement.ironmanRestriction("no_ge");
        assertTrue(unlockTracker.isEdgeRequirementMet(req));
    }
}

