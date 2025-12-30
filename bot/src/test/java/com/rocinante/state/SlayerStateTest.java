package com.rocinante.state;

import com.rocinante.tasks.impl.skills.slayer.SlayerMaster;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SlayerState} utility class.
 *
 * Tests slayer task state queries, completion tracking, and master identification.
 */
public class SlayerStateTest {

    // ========================================================================
    // EMPTY State Tests
    // ========================================================================

    @Test
    public void testEmpty_NotNull() {
        assertNotNull("EMPTY state should not be null", SlayerState.EMPTY);
    }

    @Test
    public void testEmpty_NoTaskName() {
        assertTrue("EMPTY state should have no/empty task name", 
                SlayerState.EMPTY.getTaskName() == null || SlayerState.EMPTY.getTaskName().isEmpty());
    }

    @Test
    public void testEmpty_ZeroRemainingKills() {
        assertEquals("EMPTY state should have 0 remaining kills", 
                0, SlayerState.EMPTY.getRemainingKills());
    }

    @Test
    public void testEmpty_HasNoTask() {
        assertFalse("EMPTY state hasTask() should be false", SlayerState.EMPTY.hasTask());
    }

    @Test
    public void testEmpty_IsNotComplete() {
        assertFalse("EMPTY state isTaskComplete() should be false", 
                SlayerState.EMPTY.isTaskComplete());
    }

    // ========================================================================
    // hasTask() Tests
    // ========================================================================

    @Test
    public void testHasTask_WithValidTaskAndKills_True() {
        SlayerState state = SlayerState.builder()
                .taskName("Abyssal demons")
                .remainingKills(50)
                .initialKills(100)
                .build();
        
        assertTrue("Should have task with valid name and remaining kills", state.hasTask());
    }

    @Test
    public void testHasTask_NullTaskName_False() {
        SlayerState state = SlayerState.builder()
                .taskName(null)
                .remainingKills(50)
                .build();
        
        assertFalse("Should not have task with null name", state.hasTask());
    }

    @Test
    public void testHasTask_EmptyTaskName_False() {
        SlayerState state = SlayerState.builder()
                .taskName("")
                .remainingKills(50)
                .build();
        
        assertFalse("Should not have task with empty name", state.hasTask());
    }

    @Test
    public void testHasTask_ZeroRemainingKills_False() {
        SlayerState state = SlayerState.builder()
                .taskName("Abyssal demons")
                .remainingKills(0)
                .initialKills(100)
                .build();
        
        assertFalse("Should not have active task with 0 remaining kills", state.hasTask());
    }

    // ========================================================================
    // isTaskComplete() Tests
    // ========================================================================

    @Test
    public void testIsTaskComplete_ZeroRemainingWithInitialKills_True() {
        SlayerState state = SlayerState.builder()
                .taskName("Hellhounds")
                .remainingKills(0)
                .initialKills(100)
                .build();
        
        assertTrue("Task should be complete when remaining = 0 and initial > 0", 
                state.isTaskComplete());
    }

    @Test
    public void testIsTaskComplete_RemainingKillsLeft_False() {
        SlayerState state = SlayerState.builder()
                .taskName("Hellhounds")
                .remainingKills(50)
                .initialKills(100)
                .build();
        
        assertFalse("Task should not be complete with remaining kills", 
                state.isTaskComplete());
    }

    @Test
    public void testIsTaskComplete_ZeroInitialKills_False() {
        SlayerState state = SlayerState.builder()
                .taskName("Hellhounds")
                .remainingKills(0)
                .initialKills(0)
                .build();
        
        assertFalse("Task should not be complete with 0 initial kills", 
                state.isTaskComplete());
    }

    @Test
    public void testIsTaskComplete_NoTaskName_False() {
        SlayerState state = SlayerState.builder()
                .taskName(null)
                .remainingKills(0)
                .initialKills(100)
                .build();
        
        assertFalse("Task should not be complete without task name", 
                state.isTaskComplete());
    }

    // ========================================================================
    // getCompletionPercentage() Tests
    // ========================================================================

    @Test
    public void testGetCompletionPercentage_HalfComplete() {
        SlayerState state = SlayerState.builder()
                .taskName("Dragons")
                .remainingKills(50)
                .initialKills(100)
                .build();
        
        assertEquals("50/100 remaining should be 50% complete", 
                50, state.getCompletionPercentage());
    }

    @Test
    public void testGetCompletionPercentage_FullyComplete() {
        SlayerState state = SlayerState.builder()
                .taskName("Dragons")
                .remainingKills(0)
                .initialKills(100)
                .build();
        
        assertEquals("0/100 remaining should be 100% complete", 
                100, state.getCompletionPercentage());
    }

    @Test
    public void testGetCompletionPercentage_JustStarted() {
        SlayerState state = SlayerState.builder()
                .taskName("Dragons")
                .remainingKills(100)
                .initialKills(100)
                .build();
        
        assertEquals("100/100 remaining should be 0% complete", 
                0, state.getCompletionPercentage());
    }

    @Test
    public void testGetCompletionPercentage_ZeroInitial_ReturnsZero() {
        SlayerState state = SlayerState.builder()
                .taskName("Dragons")
                .remainingKills(0)
                .initialKills(0)
                .build();
        
        assertEquals("Zero initial should return 0%", 
                0, state.getCompletionPercentage());
    }

    // ========================================================================
    // Slayer Points Tests
    // ========================================================================

    @Test
    public void testCanSkipTask_EnoughPoints() {
        SlayerState state = SlayerState.builder()
                .taskName("Cave crawlers")
                .remainingKills(50)
                .slayerPoints(100)
                .build();
        
        assertTrue("Should be able to skip with 100 points", state.canSkipTask());
    }

    @Test
    public void testCanSkipTask_NotEnoughPoints() {
        SlayerState state = SlayerState.builder()
                .taskName("Cave crawlers")
                .remainingKills(50)
                .slayerPoints(20)
                .build();
        
        assertFalse("Should not be able to skip with 20 points", state.canSkipTask());
    }

    @Test
    public void testCanSkipTask_ExactlyEnough() {
        SlayerState state = SlayerState.builder()
                .taskName("Cave crawlers")
                .remainingKills(50)
                .slayerPoints(30)
                .build();
        
        assertTrue("Should be able to skip with exactly 30 points", state.canSkipTask());
    }

    @Test
    public void testCanBlockTask_EnoughPoints() {
        SlayerState state = SlayerState.builder()
                .taskName("Spiritual creatures")
                .remainingKills(50)
                .slayerPoints(200)
                .build();
        
        assertTrue("Should be able to block with 200 points", state.canBlockTask());
    }

    @Test
    public void testCanBlockTask_NotEnoughPoints() {
        SlayerState state = SlayerState.builder()
                .taskName("Spiritual creatures")
                .remainingKills(50)
                .slayerPoints(50)
                .build();
        
        assertFalse("Should not be able to block with 50 points", state.canBlockTask());
    }

    // ========================================================================
    // isWildernessTask() Tests
    // ========================================================================

    @Test
    public void testIsWildernessTask_KrystiliaTask_True() {
        SlayerState state = SlayerState.builder()
                .taskName("Greater demons")
                .remainingKills(80)
                .currentMaster(SlayerMaster.KRYSTILIA)
                .build();
        
        assertTrue("Krystilia task should be wilderness task", state.isWildernessTask());
    }

    @Test
    public void testIsWildernessTask_DuradelTask_False() {
        SlayerState state = SlayerState.builder()
                .taskName("Greater demons")
                .remainingKills(80)
                .currentMaster(SlayerMaster.DURADEL)
                .build();
        
        assertFalse("Duradel task should not be wilderness task", state.isWildernessTask());
    }

    @Test
    public void testIsWildernessTask_NieveTask_False() {
        SlayerState state = SlayerState.builder()
                .taskName("Abyssal demons")
                .remainingKills(150)
                .currentMaster(SlayerMaster.NIEVE)
                .build();
        
        assertFalse("Nieve task should not be wilderness task", state.isWildernessTask());
    }

    @Test
    public void testIsWildernessTask_NoMaster_False() {
        SlayerState state = SlayerState.builder()
                .taskName("Unknown task")
                .remainingKills(50)
                .currentMaster(null)
                .build();
        
        assertFalse("Task without master should not be wilderness task", 
                state.isWildernessTask());
    }

    // ========================================================================
    // isLocationRestricted() Tests
    // ========================================================================

    @Test
    public void testIsLocationRestricted_KonarTask_WithLocation_True() {
        SlayerState state = SlayerState.builder()
                .taskName("Fire giants")
                .remainingKills(100)
                .currentMaster(SlayerMaster.KONAR)
                .taskLocation("Brimhaven Dungeon")
                .build();
        
        assertTrue("Konar task with location should be restricted", 
                state.isLocationRestricted());
    }

    @Test
    public void testIsLocationRestricted_KonarTask_NoLocation_False() {
        SlayerState state = SlayerState.builder()
                .taskName("Fire giants")
                .remainingKills(100)
                .currentMaster(SlayerMaster.KONAR)
                .taskLocation(null)
                .build();
        
        assertFalse("Konar task without location should not be restricted", 
                state.isLocationRestricted());
    }

    // ========================================================================
    // getKillsCompleted() Tests
    // ========================================================================

    @Test
    public void testGetKillsCompleted_PartialCompletion() {
        SlayerState state = SlayerState.builder()
                .taskName("Dagannoth")
                .remainingKills(30)
                .initialKills(100)
                .build();
        
        assertEquals("Should have completed 70 kills", 70, state.getKillsCompleted());
    }

    @Test
    public void testGetKillsCompleted_FullCompletion() {
        SlayerState state = SlayerState.builder()
                .taskName("Dagannoth")
                .remainingKills(0)
                .initialKills(100)
                .build();
        
        assertEquals("Should have completed 100 kills", 100, state.getKillsCompleted());
    }

    @Test
    public void testGetKillsCompleted_NoProgress() {
        SlayerState state = SlayerState.builder()
                .taskName("Dagannoth")
                .remainingKills(100)
                .initialKills(100)
                .build();
        
        assertEquals("Should have completed 0 kills", 0, state.getKillsCompleted());
    }

    // ========================================================================
    // Task Streak Tests
    // ========================================================================

    @Test
    public void testGetTaskStreak() {
        SlayerState state = SlayerState.builder()
                .taskName("Bloodvelds")
                .remainingKills(120)
                .taskStreak(45)
                .build();
        
        assertEquals("Task streak should be 45", 45, state.getTaskStreak());
    }

    // ========================================================================
    // Builder Tests
    // ========================================================================

    @Test
    public void testBuilder_AllFields() {
        SlayerState state = SlayerState.builder()
                .taskName("Smoke devils")
                .remainingKills(150)
                .initialKills(200)
                .currentMaster(SlayerMaster.DURADEL)
                .taskLocation(null)
                .slayerPoints(500)
                .taskStreak(75)
                .build();
        
        assertEquals("Smoke devils", state.getTaskName());
        assertEquals(150, state.getRemainingKills());
        assertEquals(200, state.getInitialKills());
        assertEquals(SlayerMaster.DURADEL, state.getCurrentMaster());
        assertNull(state.getTaskLocation());
        assertEquals(500, state.getSlayerPoints());
        assertEquals(75, state.getTaskStreak());
    }

    @Test
    public void testBuilder_MinimalFields() {
        SlayerState state = SlayerState.builder()
                .taskName("Goblins")
                .remainingKills(30)
                .build();
        
        assertEquals("Goblins", state.getTaskName());
        assertEquals(30, state.getRemainingKills());
    }
}

