package com.rocinante.quest;

import com.rocinante.behavior.AccountType;
import com.rocinante.quest.impl.TutorialIsland;
import com.rocinante.state.IronmanState;
import org.junit.Before;
import org.junit.Test;

import javax.inject.Provider;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for QuestService.
 *
 * Tests verify:
 * - Manual quest registry functionality
 * - Tutorial Island lazy loading with IronmanState
 * - Quest lookup by ID (case-insensitive, normalized)
 * - Quest Helper integration (mocked)
 * - Statistics reporting
 */
public class QuestServiceTest {

    private QuestService questService;
    private IronmanState mockIronmanState;
    private Provider<IronmanState> ironmanStateProvider;

    @Before
    public void setUp() {
        mockIronmanState = mock(IronmanState.class);
        when(mockIronmanState.getIntendedType()).thenReturn(AccountType.NORMAL);
        
        ironmanStateProvider = () -> mockIronmanState;
        questService = new QuestService(ironmanStateProvider);
    }

    // ========================================================================
    // Tutorial Island Tests
    // ========================================================================

    @Test
    public void testGetTutorialIslandById() {
        Quest quest = questService.getQuestById("tutorial_island");
        
        assertNotNull("Tutorial Island should be found", quest);
        assertEquals("tutorial_island", quest.getId());
        assertEquals("Tutorial Island", quest.getName());
    }

    @Test
    public void testGetTutorialIslandCaseInsensitive() {
        Quest quest = questService.getQuestById("TUTORIAL_ISLAND");
        
        assertNotNull("Tutorial Island should be found (case insensitive)", quest);
        assertEquals("tutorial_island", quest.getId());
    }

    @Test
    public void testGetTutorialIslandWithSpaces() {
        Quest quest = questService.getQuestById("tutorial island");
        
        assertNotNull("Tutorial Island should be found (with spaces)", quest);
        assertEquals("tutorial_island", quest.getId());
    }

    @Test
    public void testTutorialIslandUsesIronmanState() {
        // Create service with ironman state
        IronmanState ironmanState = mock(IronmanState.class);
        when(ironmanState.getIntendedType()).thenReturn(AccountType.IRONMAN);
        
        QuestService ironmanService = new QuestService(() -> ironmanState);
        Quest quest = ironmanService.getQuestById("tutorial_island");
        
        assertNotNull("Tutorial Island should be found for ironman", quest);
        assertTrue("Should be TutorialIsland instance", quest instanceof TutorialIsland);
        
        // Load steps and verify ironman-specific step 610 is COMPOSITE
        Map<Integer, com.rocinante.quest.steps.QuestStep> steps = quest.loadSteps();
        assertEquals("Step 610 should be COMPOSITE for ironman",
                com.rocinante.quest.steps.QuestStep.StepType.COMPOSITE,
                steps.get(610).getType());
    }

    // ========================================================================
    // Manual Quest Registry Tests
    // ========================================================================

    @Test
    public void testRegisterManualQuest() {
        Quest mockQuest = createMockQuest("test_quest", "Test Quest");
        
        questService.registerQuest(mockQuest);
        Quest found = questService.getQuestById("test_quest");
        
        assertNotNull("Registered quest should be found", found);
        assertSame("Should return the same quest instance", mockQuest, found);
    }

    @Test
    public void testManualQuestTakesPrecedence() {
        // Register a quest with the same ID
        Quest manualQuest = createMockQuest("tutorial_island", "Manual Tutorial Island");
        questService.registerQuest(manualQuest);
        
        Quest found = questService.getQuestById("tutorial_island");
        
        assertNotNull("Quest should be found", found);
        assertEquals("Manual quest should take precedence", 
                "Manual Tutorial Island", found.getName());
    }

    @Test
    public void testGetAvailableQuestsIncludesManual() {
        Quest mockQuest = createMockQuest("custom_quest", "Custom Quest");
        questService.registerQuest(mockQuest);
        
        // Force Tutorial Island to be loaded
        questService.getQuestById("tutorial_island");
        
        List<Quest> quests = questService.getAvailableQuests();
        
        assertTrue("Should have at least 2 quests", quests.size() >= 2);
        assertTrue("Should contain custom quest",
                quests.stream().anyMatch(q -> q.getId().equals("custom_quest")));
    }

    // ========================================================================
    // Quest Not Found Tests
    // ========================================================================

    @Test
    public void testGetQuestByIdNotFound() {
        Quest quest = questService.getQuestById("nonexistent_quest");
        
        assertNull("Nonexistent quest should return null", quest);
    }

    @Test
    public void testGetQuestByIdNull() {
        Quest quest = questService.getQuestById(null);
        
        assertNull("Null questId should return null", quest);
    }

    @Test
    public void testGetQuestByIdEmpty() {
        Quest quest = questService.getQuestById("");
        
        assertNull("Empty questId should return null", quest);
    }

    // ========================================================================
    // Quest Availability Tests
    // ========================================================================

    @Test
    public void testIsQuestAvailable() {
        assertTrue("Tutorial Island should be available", 
                questService.isQuestAvailable("tutorial_island"));
        assertFalse("Unknown quest should not be available",
                questService.isQuestAvailable("unknown_quest"));
    }

    // ========================================================================
    // Quest Helper Integration Tests (without actual plugin)
    // ========================================================================

    @Test
    public void testQuestHelperNotAvailableInitially() {
        assertFalse("Quest Helper should not be available initially",
                questService.isQuestHelperAvailable());
    }

    @Test
    public void testInitializeQuestHelperNull() {
        questService.initializeQuestHelper(null);
        
        assertFalse("Quest Helper should not be available after null init",
                questService.isQuestHelperAvailable());
    }

    @Test
    public void testGetSelectedQuestWithoutHelper() {
        Quest quest = questService.getSelectedQuestFromHelper();
        
        assertNull("Should return null when Quest Helper not available", quest);
    }

    // ========================================================================
    // Statistics Tests
    // ========================================================================

    @Test
    public void testGetStatistics() {
        Map<String, Object> stats = questService.getStatistics();
        
        assertNotNull("Statistics should not be null", stats);
        assertTrue("Should have questHelperAvailable stat", 
                stats.containsKey("questHelperAvailable"));
        assertTrue("Should have manualQuestCount stat",
                stats.containsKey("manualQuestCount"));
        assertTrue("Should have bridgeCacheSize stat",
                stats.containsKey("bridgeCacheSize"));
    }

    @Test
    public void testStatisticsReflectState() {
        // Register a quest and load Tutorial Island
        Quest mockQuest = createMockQuest("test", "Test");
        questService.registerQuest(mockQuest);
        questService.getQuestById("tutorial_island");
        
        Map<String, Object> stats = questService.getStatistics();
        
        int manualCount = (int) stats.get("manualQuestCount");
        assertTrue("Should have at least 2 manual quests", manualCount >= 2);
    }

    // ========================================================================
    // Cache Tests
    // ========================================================================

    @Test
    public void testClearBridgeCache() {
        // This is a no-op when Quest Helper isn't available,
        // but we test that it doesn't throw
        questService.clearBridgeCache();
        
        Map<String, Object> stats = questService.getStatistics();
        assertEquals("Bridge cache should be empty", 0, stats.get("bridgeCacheSize"));
    }

    // ========================================================================
    // ID Normalization Tests
    // ========================================================================

    @Test
    public void testIdNormalizationStripsSpecialChars() {
        Quest mockQuest = createMockQuest("cook's_assistant", "Cook's Assistant");
        questService.registerQuest(mockQuest);
        
        // Test various ID formats
        assertNotNull("Should find with apostrophe", 
                questService.getQuestById("cook's_assistant"));
        assertNotNull("Should find without apostrophe",
                questService.getQuestById("cooks_assistant"));
        assertNotNull("Should find with spaces",
                questService.getQuestById("cooks assistant"));
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private Quest createMockQuest(String id, String name) {
        Quest mockQuest = mock(Quest.class);
        when(mockQuest.getId()).thenReturn(id);
        when(mockQuest.getName()).thenReturn(name);
        when(mockQuest.getProgressVarbit()).thenReturn(1);
        when(mockQuest.getCompletionValue()).thenReturn(100);
        when(mockQuest.loadSteps()).thenReturn(java.util.Collections.emptyMap());
        return mockQuest;
    }
}

