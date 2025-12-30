package com.rocinante.status;

import com.rocinante.state.PlayerState;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.StatChanged;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests the status/data snapshot helpers that feed the web UI.
 */
public class StatusModelsTest {

    @Mock
    private PlayerState playerState;

    @Mock
    private XpTracker xpTracker;

    @Mock
    private com.rocinante.behavior.FatigueModel fatigueModel;

    @Mock
    private com.rocinante.behavior.BreakScheduler breakScheduler;

    @Mock
    private net.runelite.api.Client client;

    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @Test
    public void botStatusSerializesAndComputesCombatLevel() {
        // Player snapshot
        when(playerState.isValid()).thenReturn(true);
        when(playerState.getWorldPosition()).thenReturn(new WorldPoint(3200, 3200, 0));
        when(playerState.getCurrentHitpoints()).thenReturn(50);
        when(playerState.getMaxHitpoints()).thenReturn(50);
        when(playerState.getCurrentPrayer()).thenReturn(40);
        when(playerState.getMaxPrayer()).thenReturn(40);
        when(playerState.getRunEnergy()).thenReturn(70);
        when(playerState.isPoisoned()).thenReturn(false);
        when(playerState.isVenomed()).thenReturn(false);

        // Skills for combat level calculation
        when(xpTracker.getCurrentLevel(Skill.ATTACK)).thenReturn(50);
        when(xpTracker.getCurrentLevel(Skill.STRENGTH)).thenReturn(50);
        when(xpTracker.getCurrentLevel(Skill.DEFENCE)).thenReturn(50);
        when(xpTracker.getCurrentLevel(Skill.HITPOINTS)).thenReturn(50);
        when(xpTracker.getCurrentLevel(Skill.PRAYER)).thenReturn(50);
        when(xpTracker.getCurrentLevel(Skill.RANGED)).thenReturn(1);
        when(xpTracker.getCurrentLevel(Skill.MAGIC)).thenReturn(1);
        when(xpTracker.getCurrentXp(any(Skill.class))).thenReturn(1000);
        when(xpTracker.getBoostedLevel(any(Skill.class))).thenReturn(0);

        Task current = new StubTask("current", TaskState.RUNNING);
        Task queued = new StubTask("queued", TaskState.PENDING);

        BotStatus status = BotStatus.capture(
                GameState.LOGGED_IN,
                current,
                SessionStats.EMPTY,
                playerState,
                xpTracker,
                "TestPlayer",
                5,
                List.of(queued),
                BotStatus.QuestsData.empty());

        String json = status.toJson();
        BotStatus roundTrip = BotStatus.fromJson(json);

        assertNotNull(roundTrip.getPlayer());
        assertEquals("TestPlayer", roundTrip.getPlayer().getName());
        assertEquals(63, roundTrip.getPlayer().getCombatLevel()); // derived from stats above
        assertEquals(1, roundTrip.getQueue().getPending());
        assertEquals("queued", roundTrip.getQueue().getDescriptions().get(0));
        assertEquals("RUNNING", roundTrip.getTask().getState());
        assertEquals("current", roundTrip.getTask().getDescription());
    }

    @Test
    public void sessionStatsCaptureFormatsRuntimeAndFatigue() {
        when(xpTracker.isTracking()).thenReturn(true);
        when(xpTracker.getSessionStartTime()).thenReturn(Instant.EPOCH);
        when(xpTracker.getSessionDurationMs()).thenReturn(3_600_000L);
        when(xpTracker.getTotalSessionXp()).thenReturn(5_000L);
        when(xpTracker.getTotalXpPerHour()).thenReturn(5_000.0);
        when(xpTracker.getAllXpGained()).thenReturn(Map.of(Skill.ATTACK, 5_000));

        when(fatigueModel.getFatigueLevel()).thenReturn(0.5);
        when(fatigueModel.getSessionActionCount()).thenReturn(123L);

        when(breakScheduler.getBreaksTaken()).thenReturn(2);
        when(breakScheduler.getTotalBreakDuration()).thenReturn(Duration.ofMinutes(10));

        SessionStats stats = SessionStats.capture(xpTracker, fatigueModel, breakScheduler);

        assertEquals(3_600_000L, stats.getRuntimeMs());
        assertEquals(123L, stats.getActionCount());
        assertEquals(0.5, stats.getFatigueLevel(), 0.0001);
        assertEquals("01:00:00", stats.getFormattedRuntime());
        assertEquals("5.0K", stats.getFormattedTotalXp());
        assertTrue("Has data", stats.hasData());
        assertTrue("Actions per hour should be > 0", stats.getActionsPerHour() > 0);
    }

    @Test
    public void xpTrackerAccumulatesGainsFromStatChanged() {
        when(client.getSkillExperience(any(Skill.class))).thenReturn(0);
        when(client.getRealSkillLevel(any(Skill.class))).thenReturn(1);
        when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(1);

        XpTracker tracker = new XpTracker(client);
        tracker.startSession();

        StatChanged event = new StatChanged(Skill.ATTACK, 100, 5, 5);

        tracker.onStatChanged(event);

        assertTrue(tracker.isTracking());
        assertEquals(100, tracker.getXpGained(Skill.ATTACK));
        assertEquals(100, tracker.getCurrentXp(Skill.ATTACK));
        assertEquals(100L, tracker.getTotalSessionXp());
    }

    private static class StubTask implements Task {
        private final String desc;
        private final TaskState state;

        StubTask(String desc, TaskState state) {
            this.desc = desc;
            this.state = state;
        }

        @Override
        public TaskState getState() {
            return state;
        }

        @Override
        public boolean canExecute(TaskContext ctx) {
            return true;
        }

        @Override
        public void execute(TaskContext ctx) {
        }

        @Override
        public void onComplete(TaskContext ctx) {
        }

        @Override
        public void onFail(TaskContext ctx, Exception e) {
        }

        @Override
        public String getDescription() {
            return desc;
        }
    }
}


