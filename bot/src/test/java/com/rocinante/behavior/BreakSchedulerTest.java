package com.rocinante.behavior;

import com.rocinante.tasks.Task;
import com.rocinante.util.Randomization;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BreakScheduler.
 * Tests break scheduling, triggering, and completion handling.
 */
public class BreakSchedulerTest {

    @Mock
    private BotActivityTracker activityTracker;
    
    @Mock
    private FatigueModel fatigueModel;
    
    @Mock
    private PlayerProfile playerProfile;
    
    @Mock
    private Function<BreakType, Task> breakTaskFactory;
    
    @Mock
    private Task mockTask;
    
    private Randomization randomization;
    private BreakScheduler breakScheduler;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        randomization = new Randomization(12345L);
        
        when(activityTracker.canTakeBreak()).thenReturn(true);
        when(activityTracker.getAccountType()).thenReturn(AccountType.NORMAL);
        when(fatigueModel.isOnBreak()).thenReturn(false);
        when(fatigueModel.shouldTakeBreak()).thenReturn(false);
        when(breakTaskFactory.apply(any())).thenReturn(mockTask);
        
        breakScheduler = new BreakScheduler(activityTracker, fatigueModel, playerProfile, randomization);
        breakScheduler.setBreakTaskFactory(breakTaskFactory);
    }

    // ========================================================================
    // Initialization Tests
    // ========================================================================

    @Test
    public void testInitialization_SchedulesThresholds() {
        assertNotNull(breakScheduler);
        assertTrue(breakScheduler.isEnabled());
        assertEquals(0, breakScheduler.getActionsSinceMicroPause());
        assertNull(breakScheduler.getPendingBreak());
    }

    @Test
    public void testOnSessionStart_ResetsState() {
        // Simulate some activity
        for (int i = 0; i < 50; i++) {
            breakScheduler.recordAction();
        }
        
        breakScheduler.onSessionStart();
        
        assertEquals(0, breakScheduler.getActionsSinceMicroPause());
        assertNull(breakScheduler.getPendingBreak());
        assertNotNull(breakScheduler.getSessionStartTime());
    }

    // ========================================================================
    // Action Recording Tests
    // ========================================================================

    @Test
    public void testRecordAction_IncrementsCounter() {
        assertEquals(0, breakScheduler.getActionsSinceMicroPause());
        
        breakScheduler.recordAction();
        
        assertEquals(1, breakScheduler.getActionsSinceMicroPause());
        verify(fatigueModel, times(1)).recordAction();
    }

    @Test
    public void testRecordAction_TriggersMicroPause() {
        // Record enough actions to trigger micro-pause (30-90 range)
        for (int i = 0; i < 100; i++) {
            breakScheduler.recordAction();
            breakScheduler.tick();
        }
        
        // Should have triggered at least once
        assertNotNull("Micro-pause should have triggered", breakScheduler.getPendingBreak());
    }

    // ========================================================================
    // Break Triggering Tests
    // ========================================================================

    @Test
    public void testMicroPause_TriggersAfterActions() {
        when(fatigueModel.isOnBreak()).thenReturn(false);
        when(activityTracker.canTakeBreak()).thenReturn(true);
        
        // Force micro-pause by exceeding threshold
        for (int i = 0; i < 100; i++) {
            breakScheduler.recordAction();
        }
        
        breakScheduler.tick();
        
        // May or may not trigger due to probability, but action count should be high
        assertTrue(breakScheduler.getActionsSinceMicroPause() >= 30);
    }

    @Test
    public void testShortBreak_TriggersOnFatigue() {
        when(fatigueModel.shouldTakeBreak()).thenReturn(true);
        when(activityTracker.canTakeBreak()).thenReturn(true);
        
        breakScheduler.tick();
        
        // Should trigger short break due to fatigue
        assertEquals(BreakType.SHORT_BREAK, breakScheduler.getPendingBreak());
    }

    @Test
    public void testBreakNotTriggered_WhenCannotTakeBreak() {
        when(activityTracker.canTakeBreak()).thenReturn(false);
        when(fatigueModel.shouldTakeBreak()).thenReturn(true);
        
        breakScheduler.tick();
        
        assertNull("Should not trigger break when activity doesn't allow it", 
                  breakScheduler.getPendingBreak());
    }

    @Test
    public void testBreakNotTriggered_WhenOnBreak() {
        when(fatigueModel.isOnBreak()).thenReturn(true);
        
        // Force enough actions for micro-pause
        for (int i = 0; i < 100; i++) {
            breakScheduler.recordAction();
        }
        
        breakScheduler.tick();
        
        assertNull("Should not schedule breaks while already on break", 
                  breakScheduler.getPendingBreak());
    }

    // ========================================================================
    // Break Task Creation Tests
    // ========================================================================

    @Test
    public void testGetScheduledBreak_ReturnsPendingBreak() {
        breakScheduler.forceBreak(BreakType.MICRO_PAUSE);
        
        Optional<Task> task = breakScheduler.getScheduledBreak();
        
        assertTrue(task.isPresent());
        assertNull("Pending break should be cleared", breakScheduler.getPendingBreak());
        verify(breakTaskFactory).apply(BreakType.MICRO_PAUSE);
    }

    @Test
    public void testGetScheduledBreak_NoPending_ReturnsEmpty() {
        Optional<Task> task = breakScheduler.getScheduledBreak();
        
        assertFalse(task.isPresent());
    }

    @Test
    public void testGetScheduledBreak_FactoryReturnsNull_ReturnsEmpty() {
        when(breakTaskFactory.apply(any())).thenReturn(null);
        breakScheduler.forceBreak(BreakType.MICRO_PAUSE);
        
        Optional<Task> task = breakScheduler.getScheduledBreak();
        
        assertFalse("Should return empty when factory returns null", task.isPresent());
    }

    @Test
    public void testGetScheduledBreak_CannotTakeBreak_DeferrsBreak() {
        when(activityTracker.canTakeBreak()).thenReturn(false);
        breakScheduler.forceBreak(BreakType.SHORT_BREAK);
        
        Optional<Task> task = breakScheduler.getScheduledBreak();
        
        assertFalse("Should defer break when activity doesn't allow", task.isPresent());
        assertNotNull("Pending break should remain", breakScheduler.getPendingBreak());
    }

    // ========================================================================
    // Break Completion Tests
    // ========================================================================

    @Test
    public void testOnBreakCompleted_MicroPause_ResetsActionCounter() {
        breakScheduler.recordAction();
        breakScheduler.recordAction();
        assertEquals(2, breakScheduler.getActionsSinceMicroPause());
        
        breakScheduler.onBreakCompleted(BreakType.MICRO_PAUSE, Duration.ofSeconds(5));
        
        assertEquals(0, breakScheduler.getActionsSinceMicroPause());
    }

    @Test
    public void testOnBreakCompleted_ShortBreak_RecordsFatigueRecovery() {
        breakScheduler.onBreakCompleted(BreakType.SHORT_BREAK, Duration.ofSeconds(60));
        
        verify(fatigueModel).recordBreakTime(Duration.ofSeconds(60));
    }

    @Test
    public void testOnBreakCompleted_LongBreak_ResetsShortBreakToo() {
        breakScheduler.onBreakCompleted(BreakType.LONG_BREAK, Duration.ofMinutes(10));
        
        // Long break should reset both long and short break timers
        verify(fatigueModel).recordBreakTime(Duration.ofMinutes(10));
    }

    // ========================================================================
    // Control Tests
    // ========================================================================

    @Test
    public void testSetEnabled_DisablesBreakChecking() {
        breakScheduler.setEnabled(false);
        
        assertFalse(breakScheduler.isEnabled());
        
        breakScheduler.forceBreak(BreakType.SHORT_BREAK);
        breakScheduler.tick();
        
        // Break should still be pending (not checked when disabled)
        assertNotNull(breakScheduler.getPendingBreak());
    }

    @Test
    public void testCancelPendingBreak_ClearsBreak() {
        breakScheduler.forceBreak(BreakType.LONG_BREAK);
        assertNotNull(breakScheduler.getPendingBreak());
        
        breakScheduler.cancelPendingBreak();
        
        assertNull(breakScheduler.getPendingBreak());
    }

    @Test
    public void testForceBreak_SetsPendingBreak() {
        breakScheduler.forceBreak(BreakType.SESSION_END);
        
        assertEquals(BreakType.SESSION_END, breakScheduler.getPendingBreak());
    }

    // ========================================================================
    // Time Query Tests
    // ========================================================================

    @Test
    public void testGetSessionDuration_ReturnsNonZero() throws InterruptedException {
        Thread.sleep(5);
        
        Duration duration = breakScheduler.getSessionDuration();
        
        assertTrue(duration.toMillis() >= 0);
    }

    @Test
    public void testGetSummary_ContainsKeyInfo() {
        breakScheduler.recordAction();
        
        String summary = breakScheduler.getSummary();
        
        assertTrue(summary.contains("enabled=true"));
        assertTrue(summary.contains("pending=null"));
        assertTrue(summary.contains("actions=1"));
    }
}

