package com.rocinante.behavior;

import com.rocinante.util.Randomization;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TickJitterController.
 * Verifies jitter calculation, activity scaling, and scheduling behavior.
 */
public class TickJitterControllerTest {

    private TickJitterController jitterController;
    private Randomization randomization;
    private ScheduledExecutorService executor;
    private PlayerProfile mockProfile;
    private FatigueModel mockFatigue;
    private BotActivityTracker mockActivityTracker;

    @Before
    public void setUp() {
        randomization = new Randomization();
        executor = Executors.newSingleThreadScheduledExecutor();
        
        // Create mock profile with typical values
        mockProfile = mock(PlayerProfile.class);
        when(mockProfile.getJitterMu()).thenReturn(40.0);
        when(mockProfile.getJitterSigma()).thenReturn(15.0);
        when(mockProfile.getJitterTau()).thenReturn(20.0);
        when(mockProfile.getTickSkipBaseProbability()).thenReturn(0.05);
        when(mockProfile.getAttentionLapseProbability()).thenReturn(0.01);
        when(mockProfile.getAnticipationProbability()).thenReturn(0.15);
        
        // Create mock fatigue model (no fatigue for basic tests)
        mockFatigue = mock(FatigueModel.class);
        when(mockFatigue.getFatigueLevel()).thenReturn(0.0);
        when(mockFatigue.getSigmaMultiplier()).thenReturn(1.0);
        when(mockFatigue.getTauMultiplier()).thenReturn(1.0);
        
        // Create mock activity tracker (no repetitiveness for basic tests)
        mockActivityTracker = mock(BotActivityTracker.class);
        when(mockActivityTracker.getRepetitivenessMultiplier()).thenReturn(1.0);
        
        jitterController = new TickJitterController(
                randomization, mockProfile, mockFatigue, mockActivityTracker, executor);
    }

    // ========================================================================
    // Jitter Calculation Tests
    // ========================================================================

    @Test
    public void testCalculateJitter_ReturnsPositiveValue() {
        long jitter = jitterController.calculateJitter(ActivityType.MEDIUM);
        assertTrue("Jitter should be positive", jitter > 0);
    }

    @Test
    public void testCalculateJitter_WithinBounds() {
        // Run multiple times to test distribution
        // Note: with tick skip and attention lapse, values can be much higher
        for (int i = 0; i < 100; i++) {
            long jitter = jitterController.calculateJitter(ActivityType.MEDIUM);
            assertTrue("Jitter should be >= 15ms", jitter >= 15);
            assertTrue("Jitter should be <= 10000ms", jitter <= 10000);
        }
    }

    @Test
    public void testCalculateJitter_CriticalActivity_HasReducedJitter() {
        // CRITICAL activities should have lower jitter (faster reactions needed)
        long totalCritical = 0;
        long totalMedium = 0;
        int iterations = 100;
        
        for (int i = 0; i < iterations; i++) {
            totalCritical += jitterController.calculateJitter(ActivityType.CRITICAL);
            totalMedium += jitterController.calculateJitter(ActivityType.MEDIUM);
        }
        
        double avgCritical = (double) totalCritical / iterations;
        double avgMedium = (double) totalMedium / iterations;
        
        assertTrue("CRITICAL average jitter should be less than MEDIUM",
                avgCritical < avgMedium);
    }

    @Test
    public void testCalculateJitter_IdleActivity_HasIncreasedJitter() {
        // IDLE activities should have higher jitter (relaxed state)
        long totalIdle = 0;
        long totalMedium = 0;
        int iterations = 100;
        
        for (int i = 0; i < iterations; i++) {
            totalIdle += jitterController.calculateJitter(ActivityType.IDLE);
            totalMedium += jitterController.calculateJitter(ActivityType.MEDIUM);
        }
        
        double avgIdle = (double) totalIdle / iterations;
        double avgMedium = (double) totalMedium / iterations;
        
        assertTrue("IDLE average jitter should be greater than MEDIUM",
                avgIdle > avgMedium);
    }

    @Test
    public void testCalculateEmergencyJitter_IsMinimal() {
        // Emergency jitter should be very small (10-20ms)
        for (int i = 0; i < 50; i++) {
            long jitter = jitterController.calculateEmergencyJitter();
            assertTrue("Emergency jitter should be >= 10ms", jitter >= 10);
            assertTrue("Emergency jitter should be <= 20ms", jitter <= 20);
        }
    }

    @Test
    public void testCalculateJitter_DisabledReturnsZero() {
        jitterController.setEnabled(false);
        assertEquals("Disabled jitter should return 0", 
                0, jitterController.calculateJitter(ActivityType.MEDIUM));
    }

    // ========================================================================
    // Scheduling Tests
    // ========================================================================

    @Test
    public void testScheduleJitteredExecution_ExecutesTask() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean taskExecuted = new AtomicBoolean(false);
        
        boolean scheduled = jitterController.scheduleJitteredExecution(() -> {
            taskExecuted.set(true);
            latch.countDown();
        }, ActivityType.MEDIUM);
        
        assertTrue("Should successfully schedule task", scheduled);
        assertTrue("Task should execute within timeout", 
                latch.await(2000, TimeUnit.MILLISECONDS));
        assertTrue("Task flag should be set", taskExecuted.get());
    }

    @Test
    public void testScheduleJitteredExecution_PreventsDuplicates() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        
        // First schedule should succeed
        boolean first = jitterController.scheduleJitteredExecution(() -> {
            try { Thread.sleep(100); } catch (InterruptedException e) { }
            latch.countDown();
        }, ActivityType.MEDIUM);
        
        // Second schedule while first is pending should fail
        boolean second = jitterController.scheduleJitteredExecution(() -> {}, ActivityType.MEDIUM);
        
        assertTrue("First schedule should succeed", first);
        assertFalse("Second schedule should fail (pending)", second);
        
        // Wait for first to complete
        latch.await(2000, TimeUnit.MILLISECONDS);
    }

    @Test
    public void testIsJitterPending() throws Exception {
        assertFalse("Initially should not be pending", jitterController.isJitterPending());
        
        CountDownLatch latch = new CountDownLatch(1);
        
        jitterController.scheduleJitteredExecution(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { }
            latch.countDown();
        }, ActivityType.MEDIUM);
        
        assertTrue("Should be pending after schedule", jitterController.isJitterPending());
        
        latch.await(2000, TimeUnit.MILLISECONDS);
        Thread.sleep(50); // Wait for state to clear
        
        assertFalse("Should not be pending after completion", jitterController.isJitterPending());
    }

    @Test
    public void testCancelPending() throws Exception {
        AtomicBoolean taskExecuted = new AtomicBoolean(false);
        
        jitterController.scheduleJitteredExecution(() -> {
            try { Thread.sleep(100); } catch (InterruptedException e) { }
            taskExecuted.set(true);
        }, ActivityType.MEDIUM);
        
        assertTrue("Should be pending", jitterController.isJitterPending());
        
        jitterController.cancelPending();
        
        assertFalse("Should not be pending after cancel", jitterController.isJitterPending());
        
        // Wait to ensure task doesn't execute
        Thread.sleep(200);
        assertFalse("Task should not have executed after cancel", taskExecuted.get());
    }

    // ========================================================================
    // Profile Integration Tests
    // ========================================================================

    @Test
    public void testUsesProfileParameters() {
        // Set custom profile values
        when(mockProfile.getJitterMu()).thenReturn(50.0);
        when(mockProfile.getJitterSigma()).thenReturn(10.0);
        when(mockProfile.getJitterTau()).thenReturn(25.0);
        
        // Calculate jitter multiple times and check it's using profile values
        long sum = 0;
        int iterations = 100;
        for (int i = 0; i < iterations; i++) {
            sum += jitterController.calculateJitter(ActivityType.MEDIUM);
        }
        double avg = (double) sum / iterations;
        
        // With mu=50, sigma=10, tau=25, expected mean is around 75ms (mu + tau)
        // Allow reasonable variance (tick skips can increase this)
        assertTrue("Average should be in reasonable range for profile",
                avg > 40 && avg < 500);
    }

    // ========================================================================
    // Fatigue Integration Tests
    // ========================================================================

    @Test
    public void testFatigueIncreasesJitter() {
        // Low fatigue
        when(mockFatigue.getFatigueLevel()).thenReturn(0.0);
        when(mockFatigue.getSigmaMultiplier()).thenReturn(1.0);
        when(mockFatigue.getTauMultiplier()).thenReturn(1.0);
        
        long sumLowFatigue = 0;
        for (int i = 0; i < 100; i++) {
            sumLowFatigue += jitterController.calculateJitter(ActivityType.MEDIUM);
        }
        double avgLowFatigue = sumLowFatigue / 100.0;
        
        // High fatigue
        when(mockFatigue.getFatigueLevel()).thenReturn(0.8);
        when(mockFatigue.getSigmaMultiplier()).thenReturn(1.5);
        when(mockFatigue.getTauMultiplier()).thenReturn(2.0);
        
        long sumHighFatigue = 0;
        for (int i = 0; i < 100; i++) {
            sumHighFatigue += jitterController.calculateJitter(ActivityType.MEDIUM);
        }
        double avgHighFatigue = sumHighFatigue / 100.0;
        
        assertTrue("High fatigue should increase average jitter",
                avgHighFatigue > avgLowFatigue);
    }

    // ========================================================================
    // Repetitiveness Integration Tests
    // ========================================================================

    @Test
    public void testRepetitivenessIncreasesTickSkipChance() {
        // Low repetitiveness
        when(mockActivityTracker.getRepetitivenessMultiplier()).thenReturn(1.0);
        
        int tickSkipsLow = 0;
        for (int i = 0; i < 1000; i++) {
            long jitter = jitterController.calculateJitter(ActivityType.MEDIUM);
            if (jitter > 600) tickSkipsLow++; // Jitter > one tick = tick skip
        }
        
        // High repetitiveness
        when(mockActivityTracker.getRepetitivenessMultiplier()).thenReturn(2.0);
        
        int tickSkipsHigh = 0;
        for (int i = 0; i < 1000; i++) {
            long jitter = jitterController.calculateJitter(ActivityType.MEDIUM);
            if (jitter > 600) tickSkipsHigh++;
        }
        
        assertTrue("High repetitiveness should increase tick skip frequency",
                tickSkipsHigh > tickSkipsLow);
    }

    // ========================================================================
    // Anticipation Tests
    // ========================================================================

    @Test
    public void testAnticipationCanProduceFastReaction() {
        when(mockProfile.getAnticipationProbability()).thenReturn(1.0); // Always anticipate
        
        int fastReactions = 0;
        for (int i = 0; i < 100; i++) {
            long jitter = jitterController.calculateJitterWithAnticipation(ActivityType.MEDIUM, true);
            if (jitter >= 25 && jitter <= 50) fastReactions++;
        }
        
        // With 100% anticipation probability, all should be fast
        assertEquals("All reactions should be fast with anticipation", 100, fastReactions);
    }

    @Test
    public void testAnticipationNotTriggeredWithoutPredictableEvent() {
        when(mockProfile.getAnticipationProbability()).thenReturn(1.0);
        
        int fastReactions = 0;
        for (int i = 0; i < 100; i++) {
            long jitter = jitterController.calculateJitterWithAnticipation(ActivityType.MEDIUM, false);
            if (jitter >= 25 && jitter <= 50) fastReactions++;
        }
        
        // Without predictable event, normal distribution applies
        assertTrue("Most should not be fast reactions", fastReactions < 50);
    }

    // ========================================================================
    // Lifecycle Tests
    // ========================================================================

    @Test
    public void testReset() throws Exception {
        // Schedule a task
        jitterController.scheduleJitteredExecution(() -> {
            try { Thread.sleep(100); } catch (InterruptedException e) { }
        }, ActivityType.MEDIUM);
        
        assertTrue("Should be pending", jitterController.isJitterPending());
        
        jitterController.reset();
        
        assertFalse("Should not be pending after reset", jitterController.isJitterPending());
        assertEquals("Last jitter should be 0 after reset", 0, jitterController.getLastJitterMs());
    }

    @Test
    public void testShutdown() {
        jitterController.shutdown();
        // Shutdown is successful if no exception is thrown
        // After shutdown, the controller is in an unusable state
    }

    // ========================================================================
    // Activity Scale Factor Tests
    // ========================================================================

    @Test
    public void testActivityScaling_AllTypes() {
        // Verify all activity types produce jitter without error
        for (ActivityType activity : ActivityType.values()) {
            long jitter = jitterController.calculateJitter(activity);
            assertTrue("Jitter for " + activity + " should be positive", jitter > 0);
        }
    }
}
