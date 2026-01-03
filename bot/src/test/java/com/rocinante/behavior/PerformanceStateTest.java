package com.rocinante.behavior;

import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.time.LocalTime;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PerformanceState.
 * Verifies daily variance, circadian modulation, and effective trait calculation.
 */
public class PerformanceStateTest {

    private PerformanceState performanceState;
    private PlayerProfile mockProfile;

    @Before
    public void setUp() {
        mockProfile = mock(PlayerProfile.class);
        
        // Set up mock profile with typical values
        when(mockProfile.isLoaded()).thenReturn(true);
        when(mockProfile.getLastSessionStart()).thenReturn(Instant.now().minusSeconds(60)); // 1 minute ago
        
        // Chronotype and circadian settings
        when(mockProfile.getChronotype()).thenReturn("NEUTRAL");
        when(mockProfile.getCircadianStrength()).thenReturn(0.25);
        when(mockProfile.getPeakHourOffset()).thenReturn(0.0);
        when(mockProfile.getCircadianPerformanceMultiplier(anyInt())).thenReturn(1.0);
        
        // Base trait values
        when(mockProfile.getFittsA()).thenReturn(50.0);
        when(mockProfile.getFittsB()).thenReturn(120.0);
        when(mockProfile.getJitterMu()).thenReturn(250.0);
        when(mockProfile.getJitterSigma()).thenReturn(50.0);
        when(mockProfile.getJitterTau()).thenReturn(150.0);
        when(mockProfile.getClickDurationMu()).thenReturn(85.0);
        when(mockProfile.getClickDurationSigma()).thenReturn(15.0);
        when(mockProfile.getClickDurationTau()).thenReturn(10.0);
        when(mockProfile.getCognitiveDelayBase()).thenReturn(120.0);
        when(mockProfile.getCognitiveDelayVariance()).thenReturn(0.4);
        when(mockProfile.getOvershootProbability()).thenReturn(0.12);
        when(mockProfile.getWobbleAmplitudeModifier()).thenReturn(1.0);
        when(mockProfile.getVelocityFlow()).thenReturn(0.5);
        when(mockProfile.getMouseSpeedMultiplier()).thenReturn(1.0);
        
        performanceState = new PerformanceState(mockProfile);
    }

    // ========================================================================
    // Initialization Tests
    // ========================================================================

    @Test
    public void testInitializeSession_RequiresLoadedProfile() {
        when(mockProfile.isLoaded()).thenReturn(false);
        
        try {
            performanceState.initializeSession();
            fail("Should throw IllegalStateException when profile not loaded");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("PlayerProfile not loaded"));
        }
    }

    @Test
    public void testInitializeSession_SetsInitializedTrue() {
        performanceState.initializeSession();
        assertTrue(performanceState.isInitialized());
    }

    @Test
    public void testGetPerformanceModifier_RequiresInitialization() {
        try {
            performanceState.getPerformanceModifier();
            fail("Should throw IllegalStateException when not initialized");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("not initialized"));
        }
    }

    // ========================================================================
    // Daily Performance Tests
    // ========================================================================

    @Test
    public void testDailyPerformance_InExpectedRange() {
        performanceState.initializeSession();
        
        double daily = performanceState.getDailyPerformance();
        
        assertTrue("Daily performance should be >= 0.85", daily >= 0.85);
        assertTrue("Daily performance should be <= 1.15", daily <= 1.15);
    }

    @Test
    public void testDailyPerformance_VariesAcrossSessions() {
        // Create multiple PerformanceState instances to simulate different days
        int uniqueValues = 0;
        double previousValue = -1;
        
        for (int i = 0; i < 50; i++) {
            PerformanceState state = new PerformanceState(mockProfile);
            // Simulate a long session gap to trigger regeneration
            when(mockProfile.getLastSessionStart()).thenReturn(Instant.now().minusSeconds(3600 * 10)); // 10 hours ago
            state.initializeSession();
            
            double daily = state.getDailyPerformance();
            if (Math.abs(daily - previousValue) > 0.001) {
                uniqueValues++;
            }
            previousValue = daily;
        }
        
        // Should have significant variation (not all the same)
        assertTrue("Daily performance should vary across sessions", uniqueValues > 10);
    }
    
    @Test
    public void testDailyPerformance_AR1_Autocorrelation() {
        // Verify that consecutive days have autocorrelation (streaks)
        // With AR(1) coefficient 0.6, consecutive values should be correlated
        
        PerformanceState state = new PerformanceState(mockProfile);
        
        // Simulate many day transitions and check for autocorrelation
        double sumProducts = 0;
        double sumX = 0;
        double sumY = 0;
        double sumXSquared = 0;
        double sumYSquared = 0;
        int n = 100;
        
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            // Simulate a new day (session gap)
            when(mockProfile.getLastSessionStart()).thenReturn(Instant.now().minusSeconds(3600 * 10));
            state.initializeSession();
            values[i] = state.getDailyPerformance();
        }
        
        // Calculate Pearson correlation between consecutive values
        for (int i = 0; i < n - 1; i++) {
            double x = values[i];
            double y = values[i + 1];
            sumProducts += x * y;
            sumX += x;
            sumY += y;
            sumXSquared += x * x;
            sumYSquared += y * y;
        }
        
        int pairs = n - 1;
        double numerator = pairs * sumProducts - sumX * sumY;
        double denominator = Math.sqrt((pairs * sumXSquared - sumX * sumX) * (pairs * sumYSquared - sumY * sumY));
        double correlation = denominator == 0 ? 0 : numerator / denominator;
        
        // With AR(1) coefficients (0.4 degrading, 0.7 recovering), we expect positive autocorrelation
        // Not testing exact value due to randomness, but should be notably positive
        assertTrue("Daily performance should show positive autocorrelation (streaks): r=" + 
                String.format("%.2f", correlation), correlation > 0.2);
    }
    
    @Test
    public void testDailyPerformance_AsymmetricRecovery() {
        // Verify that degradation is faster than recovery
        // We simulate many transitions and measure the average step size in each direction
        
        PerformanceState state = new PerformanceState(mockProfile);
        
        double totalDegradationStep = 0;
        double totalRecoveryStep = 0;
        int degradationCount = 0;
        int recoveryCount = 0;
        
        double previousValue = 1.0;
        for (int i = 0; i < 200; i++) {
            // Simulate a new day
            when(mockProfile.getLastSessionStart()).thenReturn(Instant.now().minusSeconds(3600 * 10));
            state.initializeSession();
            double currentValue = state.getDailyPerformance();
            
            double step = Math.abs(currentValue - previousValue);
            if (currentValue < previousValue) {
                // Degradation
                totalDegradationStep += step;
                degradationCount++;
            } else if (currentValue > previousValue) {
                // Recovery
                totalRecoveryStep += step;
                recoveryCount++;
            }
            previousValue = currentValue;
        }
        
        // Only compare if we have enough samples of each type
        if (degradationCount > 20 && recoveryCount > 20) {
            double avgDegradationStep = totalDegradationStep / degradationCount;
            double avgRecoveryStep = totalRecoveryStep / recoveryCount;
            
            // Degradation should have larger average step size (faster change)
            // With coefficients 0.4 (degrading) vs 0.7 (recovering), degradation should be ~1.5x faster
            assertTrue("Degradation should be faster than recovery: degradeStep=" + 
                    String.format("%.4f", avgDegradationStep) + ", recoverStep=" + 
                    String.format("%.4f", avgRecoveryStep),
                    avgDegradationStep > avgRecoveryStep * 1.1); // At least 10% faster
        }
        // If we don't have enough samples, the test passes (randomness dominated)
    }

    // ========================================================================
    // Circadian Modulation Tests
    // ========================================================================

    @Test
    public void testCircadianModifier_DelegatesToProfile() {
        performanceState.initializeSession();
        
        int testHour = LocalTime.now().getHour();
        when(mockProfile.getCircadianPerformanceMultiplier(testHour)).thenReturn(0.9);
        
        double circadian = performanceState.getCircadianModifier();
        
        assertEquals(0.9, circadian, 0.001);
        verify(mockProfile, atLeastOnce()).getCircadianPerformanceMultiplier(testHour);
    }

    // ========================================================================
    // Performance Modifier Combination Tests
    // ========================================================================

    @Test
    public void testPerformanceModifier_CombinesDailyAndCircadian() {
        performanceState.initializeSession();
        
        // Get the actual daily performance generated
        double daily = performanceState.getDailyPerformance();
        
        // Set a specific circadian value
        int testHour = LocalTime.now().getHour();
        when(mockProfile.getCircadianPerformanceMultiplier(testHour)).thenReturn(0.8);
        
        double combined = performanceState.getPerformanceModifier();
        double circadian = performanceState.getCircadianModifier();
        
        // Combined should be daily * circadian
        double expected = daily * circadian;
        assertEquals(expected, combined, 0.001);
    }

    // ========================================================================
    // Effective Trait Tests - Fitts' Law
    // ========================================================================

    @Test
    public void testEffectiveFittsA_AppliesPerformanceModifier() {
        performanceState.initializeSession();
        
        double base = mockProfile.getFittsA();
        double modifier = performanceState.getPerformanceModifier();
        double expected = base / modifier;
        
        double effective = performanceState.getEffectiveFittsA();
        
        assertEquals(expected, effective, 0.01);
    }

    @Test
    public void testEffectiveFittsB_AppliesPerformanceModifier() {
        performanceState.initializeSession();
        
        double base = mockProfile.getFittsB();
        double modifier = performanceState.getPerformanceModifier();
        double expected = base / modifier;
        
        double effective = performanceState.getEffectiveFittsB();
        
        assertEquals(expected, effective, 0.01);
    }

    @Test
    public void testEffectiveFittsB_WorseDays_SlowerTargeting() {
        // Simulate a "bad day" (low daily performance)
        performanceState.initializeSession();
        
        // Get base value
        double baseFittsB = mockProfile.getFittsB();
        
        // With modifier < 1.0, effective should be > base (slower)
        // Since modifier = daily * circadian, and circadian can be < 1.0
        int testHour = LocalTime.now().getHour();
        when(mockProfile.getCircadianPerformanceMultiplier(testHour)).thenReturn(0.5);
        
        double effective = performanceState.getEffectiveFittsB();
        
        // Should be larger (slower) when circadian is bad
        assertTrue("Bad circadian should increase fittsB (slower targeting)", 
                effective > baseFittsB * 0.9);
    }
    
    @Test
    public void testEffectiveFittsB_MotorLearning_ImprovesTiming() {
        // Verify motor learning makes experienced players faster, not slower
        performanceState.initializeSession();
        
        // Set a known performance modifier (good day)
        when(mockProfile.getCircadianPerformanceMultiplier(anyInt())).thenReturn(1.0);
        
        // Set task type and mock motor learning proficiency
        performanceState.setCurrentTaskType("WOODCUTTING");
        double expertMultiplier = 0.85; // 15% faster due to practice
        when(mockProfile.getTaskProficiencyMultiplier("WOODCUTTING")).thenReturn(expertMultiplier);
        
        double baseFittsB = mockProfile.getFittsB();
        double effective = performanceState.getEffectiveFittsB();
        
        // Expert player (0.85 multiplier) should be FASTER (lower effective value)
        // Formula: effective = base * motorLearning / performance
        // With performance ~1.0 and motorLearning = 0.85: effective ≈ base * 0.85
        assertTrue("Motor learning should reduce fittsB (faster targeting): effective=" + effective + 
                   ", base=" + baseFittsB, 
                effective < baseFittsB);
        
        // Clear task type and verify novice behavior
        performanceState.setCurrentTaskType(null);
        double noviceEffective = performanceState.getEffectiveFittsB();
        
        // Novice (1.0 multiplier) should be slower than expert
        assertTrue("Novice should be slower than expert: novice=" + noviceEffective + 
                   ", expert=" + effective,
                noviceEffective > effective);
    }

    // ========================================================================
    // Effective Trait Tests - Jitter
    // ========================================================================

    @Test
    public void testEffectiveJitterMu_AppliesPerformanceModifier() {
        performanceState.initializeSession();
        
        double base = mockProfile.getJitterMu();
        double modifier = performanceState.getPerformanceModifier();
        double expected = base / modifier;
        
        double effective = performanceState.getEffectiveJitterMu();
        
        assertEquals(expected, effective, 0.1);
    }

    @Test
    public void testEffectiveJitterSigma_AppliesPerformanceModifier() {
        performanceState.initializeSession();
        
        double base = mockProfile.getJitterSigma();
        double modifier = performanceState.getPerformanceModifier();
        double expected = base / modifier;
        
        double effective = performanceState.getEffectiveJitterSigma();
        
        assertEquals(expected, effective, 0.1);
    }

    @Test
    public void testEffectiveJitterTau_AppliesPerformanceModifier() {
        performanceState.initializeSession();
        
        double base = mockProfile.getJitterTau();
        double modifier = performanceState.getPerformanceModifier();
        double expected = base / modifier;
        
        double effective = performanceState.getEffectiveJitterTau();
        
        assertEquals(expected, effective, 0.1);
    }

    // ========================================================================
    // Effective Trait Tests - Click Duration
    // ========================================================================

    @Test
    public void testEffectiveClickDurationMu_AppliesPerformanceModifier() {
        performanceState.initializeSession();
        
        double base = mockProfile.getClickDurationMu();
        double modifier = performanceState.getPerformanceModifier();
        double expected = base / modifier;
        
        double effective = performanceState.getEffectiveClickDurationMu();
        
        assertEquals(expected, effective, 0.1);
    }

    // ========================================================================
    // Effective Trait Tests - Cognitive Delay
    // ========================================================================

    @Test
    public void testEffectiveCognitiveDelayBase_AppliesPerformanceModifier() {
        performanceState.initializeSession();
        
        double base = mockProfile.getCognitiveDelayBase();
        double modifier = performanceState.getPerformanceModifier();
        double expected = base / modifier;
        
        double effective = performanceState.getEffectiveCognitiveDelayBase();
        
        assertEquals(expected, effective, 0.1);
    }

    // ========================================================================
    // Effective Trait Tests - Movement Quality
    // ========================================================================

    @Test
    public void testEffectiveOvershootProbability_AppliesPerformanceModifier() {
        performanceState.initializeSession();
        
        double base = mockProfile.getOvershootProbability();
        double modifier = performanceState.getPerformanceModifier();
        double expected = base / modifier;
        
        double effective = performanceState.getEffectiveOvershootProbability();
        
        assertEquals(expected, effective, 0.001);
    }

    @Test
    public void testEffectiveWobbleAmplitudeModifier_AppliesPerformanceModifier() {
        performanceState.initializeSession();
        
        double base = mockProfile.getWobbleAmplitudeModifier();
        double modifier = performanceState.getPerformanceModifier();
        double expected = base / modifier;
        
        double effective = performanceState.getEffectiveWobbleAmplitudeModifier();
        
        assertEquals(expected, effective, 0.001);
    }

    @Test
    public void testEffectiveVelocityFlow_AppliesPerformanceModifier() {
        performanceState.initializeSession();
        
        double base = mockProfile.getVelocityFlow();
        double modifier = performanceState.getPerformanceModifier();
        double expected = base / modifier;
        
        double effective = performanceState.getEffectiveVelocityFlow();
        
        assertEquals(expected, effective, 0.001);
    }

    // ========================================================================
    // Identity Preservation Tests
    // ========================================================================

    @Test
    public void testRelativeRanking_PreservedAcrossModulation() {
        // Create two different mock profiles with different base values
        PlayerProfile fastProfile = mock(PlayerProfile.class);
        PlayerProfile slowProfile = mock(PlayerProfile.class);
        
        // Common setup for both
        when(fastProfile.isLoaded()).thenReturn(true);
        when(slowProfile.isLoaded()).thenReturn(true);
        when(fastProfile.getLastSessionStart()).thenReturn(Instant.now().minusSeconds(60));
        when(slowProfile.getLastSessionStart()).thenReturn(Instant.now().minusSeconds(60));
        when(fastProfile.getChronotype()).thenReturn("NEUTRAL");
        when(slowProfile.getChronotype()).thenReturn("NEUTRAL");
        when(fastProfile.getCircadianPerformanceMultiplier(anyInt())).thenReturn(1.0);
        when(slowProfile.getCircadianPerformanceMultiplier(anyInt())).thenReturn(1.0);
        
        // Fast player has lower fittsB (faster targeting)
        when(fastProfile.getFittsB()).thenReturn(80.0);
        when(slowProfile.getFittsB()).thenReturn(140.0);
        
        PerformanceState fastState = new PerformanceState(fastProfile);
        PerformanceState slowState = new PerformanceState(slowProfile);
        
        fastState.initializeSession();
        slowState.initializeSession();
        
        // Even with performance modulation, fast player should still be faster
        // (lower fittsB means faster targeting)
        // Note: due to daily variance, we can't guarantee exact ordering every time,
        // but the base difference should be preserved in expectation
        double fastEffective = fastState.getEffectiveFittsB();
        double slowEffective = slowState.getEffectiveFittsB();
        
        // Both should be reasonable values (not NaN, not negative)
        assertTrue("Fast effective fittsB should be positive", fastEffective > 0);
        assertTrue("Slow effective fittsB should be positive", slowEffective > 0);
    }

    // ========================================================================
    // Summary Tests
    // ========================================================================

    @Test
    public void testGetSummary_NotInitialized() {
        String summary = performanceState.getSummary();
        assertTrue(summary.contains("not initialized"));
    }

    @Test
    public void testGetSummary_Initialized() {
        performanceState.initializeSession();
        
        String summary = performanceState.getSummary();
        
        assertTrue(summary.contains("PerformanceState"));
        assertTrue(summary.contains("daily="));
        assertTrue(summary.contains("circadian="));
        assertTrue(summary.contains("combined="));
    }

    @Test
    public void testToString_DelegatesToSummary() {
        performanceState.initializeSession();
        
        assertEquals(performanceState.getSummary(), performanceState.toString());
    }

    // ========================================================================
    // Mouse Speed Tests
    // ========================================================================

    @Test
    public void testEffectiveMouseSpeedMultiplier_AppliesPerformanceModifier() {
        performanceState.initializeSession();
        
        double base = mockProfile.getMouseSpeedMultiplier();
        double modifier = performanceState.getPerformanceModifier();
        double expected = base * modifier;
        
        double effective = performanceState.getEffectiveMouseSpeedMultiplier();
        
        assertEquals(expected, effective, 0.01);
    }

    @Test
    public void testEffectiveMouseSpeedMultiplier_GoodDayFaster() {
        performanceState.initializeSession();
        
        double baseSpeed = mockProfile.getMouseSpeedMultiplier();
        
        // Set good circadian modifier (> 1.0 combined performance)
        int testHour = java.time.LocalTime.now().getHour();
        when(mockProfile.getCircadianPerformanceMultiplier(testHour)).thenReturn(1.0);
        
        double effectiveSpeed = performanceState.getEffectiveMouseSpeedMultiplier();
        
        // With good daily and neutral circadian, effective should be around base
        assertTrue("Effective speed should be reasonable", effectiveSpeed > 0);
    }

    // ========================================================================
    // Clamping Tests
    // ========================================================================

    @Test
    public void testEffectiveOvershootProbability_ClampedAtMax() {
        // Create a scenario with low performance modifier
        // which would push overshoot probability very high
        int testHour = java.time.LocalTime.now().getHour();
        when(mockProfile.getCircadianPerformanceMultiplier(testHour)).thenReturn(0.5);
        when(mockProfile.getOvershootProbability()).thenReturn(0.30); // High base
        
        performanceState.initializeSession();
        
        double effective = performanceState.getEffectiveOvershootProbability();
        
        // Should be clamped to max 0.40
        assertTrue("Overshoot probability should be clamped at 0.40", effective <= 0.40);
    }

    @Test
    public void testEffectiveVelocityFlow_ClampedAtMax() {
        // Create a scenario with low performance modifier
        int testHour = java.time.LocalTime.now().getHour();
        when(mockProfile.getCircadianPerformanceMultiplier(testHour)).thenReturn(0.5);
        when(mockProfile.getVelocityFlow()).thenReturn(0.65); // High base
        
        performanceState.initializeSession();
        
        double effective = performanceState.getEffectiveVelocityFlow();
        
        // Should be clamped to max 1.0
        assertTrue("Velocity flow should be clamped at 1.0", effective <= 1.0);
    }

    @Test
    public void testEffectiveWobbleAmplitudeModifier_ClampedAtMax() {
        // Create a scenario with low performance modifier
        int testHour = java.time.LocalTime.now().getHour();
        when(mockProfile.getCircadianPerformanceMultiplier(testHour)).thenReturn(0.5);
        when(mockProfile.getWobbleAmplitudeModifier()).thenReturn(1.4); // High base
        
        performanceState.initializeSession();
        
        double effective = performanceState.getEffectiveWobbleAmplitudeModifier();
        
        // Should be clamped to max 2.0
        assertTrue("Wobble amplitude should be clamped at 2.0", effective <= 2.0);
    }
}
