package com.rocinante.slayer;

import net.runelite.api.NPC;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for SlayerState class.
 */
public class SlayerStateTest {

    // ========================================================================
    // Empty State
    // ========================================================================

    @Test
    public void testEmptyState() {
        SlayerState empty = SlayerState.EMPTY;

        assertNull(empty.getTaskName());
        assertNull(empty.getTaskLocation());
        assertEquals(0, empty.getRemainingKills());
        assertEquals(0, empty.getInitialKills());
        assertEquals(0, empty.getSlayerPoints());
        assertEquals(0, empty.getTaskStreak());
        assertNull(empty.getCurrentMaster());
        assertFalse(empty.hasTask());
        assertFalse(empty.isTaskComplete());
    }

    // ========================================================================
    // Task State Tests
    // ========================================================================

    @Test
    public void testHasTask() {
        SlayerState withTask = SlayerState.builder()
                .taskName("Black demons")
                .remainingKills(50)
                .initialKills(100)
                .build();

        assertTrue(withTask.hasTask());

        SlayerState noTask = SlayerState.builder()
                .taskName(null)
                .remainingKills(0)
                .build();

        assertFalse(noTask.hasTask());

        SlayerState emptyTaskName = SlayerState.builder()
                .taskName("")
                .remainingKills(50)
                .build();

        assertFalse(emptyTaskName.hasTask());
    }

    @Test
    public void testIsTaskComplete() {
        SlayerState complete = SlayerState.builder()
                .taskName("Black demons")
                .remainingKills(0)
                .initialKills(100)
                .build();

        assertTrue(complete.isTaskComplete());

        SlayerState inProgress = SlayerState.builder()
                .taskName("Black demons")
                .remainingKills(50)
                .initialKills(100)
                .build();

        assertFalse(inProgress.isTaskComplete());
    }

    @Test
    public void testIsLocationRestricted() {
        SlayerState konarTask = SlayerState.builder()
                .taskName("Fire giants")
                .taskLocation("Catacombs of Kourend")
                .remainingKills(50)
                .initialKills(100)
                .currentMaster(SlayerMaster.KONAR)
                .build();

        assertTrue(konarTask.isLocationRestricted());

        SlayerState regularTask = SlayerState.builder()
                .taskName("Fire giants")
                .remainingKills(50)
                .initialKills(100)
                .build();

        assertFalse(regularTask.isLocationRestricted());
    }

    @Test
    public void testIsWildernessTask() {
        SlayerState wildyTask = SlayerState.builder()
                .taskName("Greater demons")
                .remainingKills(50)
                .initialKills(100)
                .currentMaster(SlayerMaster.KRYSTILIA)
                .build();

        assertTrue(wildyTask.isWildernessTask());

        SlayerState normalTask = SlayerState.builder()
                .taskName("Greater demons")
                .remainingKills(50)
                .currentMaster(SlayerMaster.DURADEL)
                .build();

        assertFalse(normalTask.isWildernessTask());
    }

    // ========================================================================
    // Progress Calculation Tests
    // ========================================================================

    @Test
    public void testGetKillsCompleted() {
        SlayerState state = SlayerState.builder()
                .taskName("Black demons")
                .remainingKills(30)
                .initialKills(100)
                .build();

        assertEquals(70, state.getKillsCompleted());
    }

    @Test
    public void testGetCompletionPercentage() {
        SlayerState halfComplete = SlayerState.builder()
                .taskName("Black demons")
                .remainingKills(50)
                .initialKills(100)
                .build();

        assertEquals(50, halfComplete.getCompletionPercentage());

        SlayerState almostDone = SlayerState.builder()
                .taskName("Black demons")
                .remainingKills(10)
                .initialKills(100)
                .build();

        assertEquals(90, almostDone.getCompletionPercentage());
    }

    @Test
    public void testIsNearCompletion() {
        SlayerState nearComplete = SlayerState.builder()
                .taskName("Black demons")
                .remainingKills(15)
                .initialKills(100)
                .build();

        assertTrue(nearComplete.isNearCompletion());

        SlayerState notNear = SlayerState.builder()
                .taskName("Black demons")
                .remainingKills(50)
                .initialKills(100)
                .build();

        assertFalse(notNear.isNearCompletion());
    }

    // ========================================================================
    // Point Tests
    // ========================================================================

    @Test
    public void testCanAfford() {
        SlayerState wealthy = SlayerState.builder()
                .slayerPoints(500)
                .build();

        assertTrue(wealthy.canAfford(100));
        assertTrue(wealthy.canAfford(500));
        assertFalse(wealthy.canAfford(600));
    }

    @Test
    public void testCanSkipTask() {
        SlayerState canSkip = SlayerState.builder()
                .slayerPoints(50)
                .build();

        assertTrue(canSkip.canSkipTask());

        SlayerState cantSkip = SlayerState.builder()
                .slayerPoints(20)
                .build();

        assertFalse(cantSkip.canSkipTask());
    }

    @Test
    public void testCanBlockTask() {
        SlayerState canBlock = SlayerState.builder()
                .slayerPoints(150)
                .build();

        assertTrue(canBlock.canBlockTask());

        SlayerState cantBlock = SlayerState.builder()
                .slayerPoints(80)
                .build();

        assertFalse(cantBlock.canBlockTask());
    }

    // ========================================================================
    // Unlock Tests
    // ========================================================================

    @Test
    public void testHasUnlock() {
        Set<SlayerUnlock> unlocks = EnumSet.of(
                SlayerUnlock.SLAYER_HELM,
                SlayerUnlock.SUPERIOR_CREATURES
        );

        SlayerState state = SlayerState.builder()
                .unlocks(unlocks)
                .build();

        assertTrue(state.hasUnlock(SlayerUnlock.SLAYER_HELM));
        assertTrue(state.hasUnlock(SlayerUnlock.SUPERIOR_CREATURES));
        assertFalse(state.hasUnlock(SlayerUnlock.BOSS_TASKS));
    }

    @Test
    public void testHasSlayerHelm() {
        Set<SlayerUnlock> withHelm = EnumSet.of(SlayerUnlock.SLAYER_HELM);
        SlayerState stateWithHelm = SlayerState.builder()
                .unlocks(withHelm)
                .build();

        assertTrue(stateWithHelm.hasSlayerHelm());

        SlayerState stateWithoutHelm = SlayerState.builder()
                .unlocks(Collections.emptySet())
                .build();

        assertFalse(stateWithoutHelm.hasSlayerHelm());
    }

    @Test
    public void testHasSuperiorUnlock() {
        Set<SlayerUnlock> withSuperior = EnumSet.of(SlayerUnlock.SUPERIOR_CREATURES);
        SlayerState stateWithSuperior = SlayerState.builder()
                .unlocks(withSuperior)
                .build();

        assertTrue(stateWithSuperior.hasSuperiorUnlock());
    }

    // ========================================================================
    // Block/Extend Tests
    // ========================================================================

    @Test
    public void testIsTaskBlocked() {
        Set<String> blocked = new HashSet<>(Arrays.asList("Black demons", "Hellhounds"));

        SlayerState state = SlayerState.builder()
                .blockedTasks(blocked)
                .build();

        assertTrue(state.isTaskBlocked("Black demons"));
        assertTrue(state.isTaskBlocked("black demons")); // Case insensitive
        assertFalse(state.isTaskBlocked("Abyssal demons"));
    }

    @Test
    public void testGetBlockedSlotCount() {
        Set<String> blocked = new HashSet<>(Arrays.asList("Black demons", "Hellhounds", "Fire giants"));

        SlayerState state = SlayerState.builder()
                .blockedTasks(blocked)
                .build();

        assertEquals(3, state.getBlockedSlotCount());
    }

    @Test
    public void testCanBlockMore() {
        Set<String> blocked = new HashSet<>(Arrays.asList("Black demons", "Hellhounds"));

        SlayerState state = SlayerState.builder()
                .blockedTasks(blocked)
                .build();

        assertTrue(state.canBlockMore(6)); // 2 < 6
        assertFalse(state.canBlockMore(2)); // 2 >= 2
    }

    // ========================================================================
    // Streak Tests
    // ========================================================================

    @Test
    public void testGetRelevantStreak() {
        SlayerState normalTask = SlayerState.builder()
                .taskStreak(50)
                .wildernessStreak(10)
                .currentMaster(SlayerMaster.DURADEL)
                .build();

        assertEquals(50, normalTask.getRelevantStreak());

        SlayerState wildyTask = SlayerState.builder()
                .taskStreak(50)
                .wildernessStreak(10)
                .currentMaster(SlayerMaster.KRYSTILIA)
                .build();

        assertEquals(10, wildyTask.getRelevantStreak());
    }

    // ========================================================================
    // Summary Test
    // ========================================================================

    @Test
    public void testGetSummary() {
        SlayerState state = SlayerState.builder()
                .taskName("Black demons")
                .taskLocation("Catacombs of Kourend")
                .remainingKills(50)
                .initialKills(100)
                .slayerPoints(250)
                .taskStreak(45)
                .currentMaster(SlayerMaster.KONAR)
                .build();

        String summary = state.getSummary();
        assertTrue(summary.contains("Black demons"));
        assertTrue(summary.contains("Catacombs of Kourend"));
        assertTrue(summary.contains("50/100"));
        assertTrue(summary.contains("50%"));
        assertTrue(summary.contains("250"));
        assertTrue(summary.contains("45"));
        assertTrue(summary.contains("Konar"));
    }
}

