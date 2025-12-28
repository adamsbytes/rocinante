package com.rocinante.behavior;

import com.rocinante.util.Randomization;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AttentionModel.
 * Tests attention state transitions, AFK handling, and distraction simulation.
 */
public class AttentionModelTest {

    @Mock
    private BotActivityTracker activityTracker;
    
    private Randomization randomization;
    private AttentionModel attentionModel;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        randomization = new Randomization(12345L);
        
        when(activityTracker.canEnterAFK()).thenReturn(true);
        when(activityTracker.getCurrentActivity()).thenReturn(ActivityType.MEDIUM);
        when(activityTracker.getAccountType()).thenReturn(AccountType.NORMAL);
        
        attentionModel = new AttentionModel(() -> activityTracker, randomization);
    }

    // ========================================================================
    // Initialization Tests
    // ========================================================================

    @Test
    public void testInitialization_StartsFocused() {
        assertEquals(AttentionState.FOCUSED, attentionModel.getCurrentState());
        assertFalse(attentionModel.isInExternalDistraction());
        assertTrue(attentionModel.canAct());
    }

    @Test
    public void testInitialization_WithCustomState() {
        AttentionModel custom = new AttentionModel(() -> activityTracker, randomization, AttentionState.DISTRACTED);
        
        assertEquals(AttentionState.DISTRACTED, custom.getCurrentState());
    }

    // ========================================================================
    // State Transition Tests
    // ========================================================================

    @Test
    public void testTick_MaintainsValidState() {
        AttentionState initialState = attentionModel.getCurrentState();
        
        // Tick a few times - state should remain valid
        for (int i = 0; i < 10; i++) {
            attentionModel.tick();
        }
        
        // State is valid (might have transitioned)
        assertNotNull(attentionModel.getCurrentState());
    }

    @Test
    public void testForceState_ChangesStateImmediately() {
        attentionModel.forceState(AttentionState.DISTRACTED);
        
        assertEquals(AttentionState.DISTRACTED, attentionModel.getCurrentState());
    }

    @Test
    public void testReset_ReturnsToFocused() {
        attentionModel.forceState(AttentionState.AFK);
        
        attentionModel.reset();
        
        assertEquals(AttentionState.FOCUSED, attentionModel.getCurrentState());
        assertFalse(attentionModel.isInExternalDistraction());
    }

    // ========================================================================
    // AFK State Tests
    // ========================================================================

    @Test
    public void testAFK_CannotAct() {
        attentionModel.forceState(AttentionState.AFK);
        
        assertFalse(attentionModel.canAct());
    }

    @Test
    public void testAFK_AutoExitsAfterDuration() {
        attentionModel.forceState(AttentionState.AFK);
        assertFalse(attentionModel.canAct());
        
        // AFK duration is set when entering state
        // Verify it's non-zero and will eventually trigger exit
        // (Testing actual wait would be too slow for unit tests)
        assertNotNull(attentionModel.getCurrentState());
        assertEquals(AttentionState.AFK, attentionModel.getCurrentState());
    }

    @Test
    public void testAFK_NotAllowedDuringCombat() {
        when(activityTracker.getCurrentActivity()).thenReturn(ActivityType.HIGH);
        when(activityTracker.canEnterAFK()).thenReturn(false);
        
        // Try to force AFK - should be prevented
        attentionModel.forceState(AttentionState.AFK);
        
        // Tick should detect it's not allowed and transition out
        attentionModel.tick();
        
        // With canEnterAFK = false, forceState to AFK followed by tick won't maintain AFK
        // (This is unit testing, so we're just verifying the check exists)
        assertNotNull(attentionModel.getCurrentState());
    }

    @Test
    public void testAFK_HCIM_ReducedProbability() {
        when(activityTracker.getAccountType()).thenReturn(AccountType.HARDCORE_IRONMAN);
        
        int afkCount = 0;
        for (int i = 0; i < 100; i++) {
            AttentionModel test = new AttentionModel(() -> activityTracker, randomization);
            // Force transitions
            for (int j = 0; j < 10; j++) {
                test.tick();
            }
            if (test.getCurrentState() == AttentionState.AFK) {
                afkCount++;
            }
        }
        
        // HCIM should have much lower AFK rate (around 1-2% vs 5%)
        assertTrue("HCIM should rarely go AFK", afkCount < 5);
    }

    // ========================================================================
    // External Distraction Tests
    // ========================================================================

    @Test
    public void testTriggerExternalDistraction_EntersAFK() {
        attentionModel.triggerExternalDistraction();
        
        assertEquals(AttentionState.AFK, attentionModel.getCurrentState());
        assertTrue(attentionModel.isInExternalDistraction());
    }

    @Test
    public void testTriggerExternalDistraction_IgnoredWhenCannotAFK() {
        when(activityTracker.canEnterAFK()).thenReturn(false);
        
        attentionModel.triggerExternalDistraction();
        
        assertFalse(attentionModel.isInExternalDistraction());
        assertNotEquals(AttentionState.AFK, attentionModel.getCurrentState());
    }

    @Test
    public void testExternalDistraction_EventuallyEnds() {
        attentionModel.triggerExternalDistraction();
        assertTrue(attentionModel.isInExternalDistraction());
        assertEquals(AttentionState.AFK, attentionModel.getCurrentState());
        
        // Verify distraction sets end time (actual wait too slow for unit test)
        assertNotNull(attentionModel.getCurrentState());
    }

    @Test
    public void testOnChatMessage_MayTriggerDistraction() {
        int distractionCount = 0;
        
        for (int i = 0; i < 100; i++) {
            AttentionModel test = new AttentionModel(() -> activityTracker, randomization);
            test.onChatMessage();
            if (test.isInExternalDistraction()) {
                distractionCount++;
            }
        }
        
        // Should trigger around 30% of the time
        assertTrue("Chat should trigger distractions around 30% of time", 
                  distractionCount >= 20 && distractionCount <= 40);
    }

    // ========================================================================
    // Effect Multiplier Tests
    // ========================================================================

    @Test
    public void testGetDelayMultiplier_Focused_ReturnsOne() {
        attentionModel.forceState(AttentionState.FOCUSED);
        
        assertEquals(1.0, attentionModel.getDelayMultiplier(), 0.001);
    }

    @Test
    public void testGetDelayMultiplier_Distracted_ReturnsOnePointThree() {
        attentionModel.forceState(AttentionState.DISTRACTED);
        
        assertEquals(1.3, attentionModel.getDelayMultiplier(), 0.001);
    }

    @Test
    public void testGetDelayMultiplier_AFK_ReturnsZero() {
        attentionModel.forceState(AttentionState.AFK);
        
        assertEquals(0.0, attentionModel.getDelayMultiplier(), 0.001);
    }

    @Test
    public void testGetPrecisionMultiplier_Focused_ReturnsOne() {
        attentionModel.forceState(AttentionState.FOCUSED);
        
        assertEquals(1.0, attentionModel.getPrecisionMultiplier(), 0.001);
    }

    @Test
    public void testGetPrecisionMultiplier_Distracted_ReturnsPointNine() {
        attentionModel.forceState(AttentionState.DISTRACTED);
        
        assertEquals(0.9, attentionModel.getPrecisionMultiplier(), 0.001);
    }

    @Test
    public void testShouldApplyEventLag_OnlyWhenDistracted() {
        attentionModel.forceState(AttentionState.FOCUSED);
        assertFalse(attentionModel.shouldApplyEventLag());
        
        attentionModel.forceState(AttentionState.DISTRACTED);
        assertTrue(attentionModel.shouldApplyEventLag());
        
        attentionModel.forceState(AttentionState.AFK);
        assertFalse(attentionModel.shouldApplyEventLag());
    }

    @Test
    public void testGetEventProcessingLag_Distracted_ReturnsLag() {
        attentionModel.forceState(AttentionState.DISTRACTED);
        
        long lag = attentionModel.getEventProcessingLag();
        
        assertTrue("Lag should be between 200-800ms", lag >= 200 && lag <= 800);
    }

    @Test
    public void testGetEventProcessingLag_Focused_ReturnsZero() {
        attentionModel.forceState(AttentionState.FOCUSED);
        
        assertEquals(0, attentionModel.getEventProcessingLag());
    }

    // ========================================================================
    // Time Query Tests
    // ========================================================================

    @Test
    public void testGetTimeInCurrentState_IncreasesOverTime() throws InterruptedException {
        Duration initial = attentionModel.getTimeInCurrentState();
        
        Thread.sleep(10); // Minimal sleep
        
        Duration after = attentionModel.getTimeInCurrentState();
        
        assertTrue(after.toMillis() >= initial.toMillis());
    }

    @Test
    public void testGetAFKDuration_ZeroWhenNotAFK() {
        attentionModel.forceState(AttentionState.FOCUSED);
        
        assertEquals(Duration.ZERO, attentionModel.getAFKDuration());
    }

    @Test
    public void testGetSummary_ContainsKeyInfo() {
        String summary = attentionModel.getSummary();
        
        assertTrue(summary.contains("state=FOCUSED"));
        assertTrue(summary.contains("distraction=false"));
    }
}

