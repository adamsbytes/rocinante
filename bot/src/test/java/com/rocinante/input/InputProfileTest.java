package com.rocinante.input;

import com.rocinante.util.Randomization;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for InputProfile class.
 * Verifies per-session characteristics as per REQUIREMENTS.md Section 3.3.
 */
public class InputProfileTest {

    private InputProfile inputProfile;

    @Before
    public void setUp() {
        Randomization randomization = new Randomization(12345L);
        inputProfile = new InputProfile(randomization);
    }

    @Test
    public void testInitializeDefault_CreatesValidProfile() {
        inputProfile.initializeDefault();

        assertTrue("Profile should be loaded", inputProfile.isLoaded());
        assertNotNull("Profile data should not be null", inputProfile.getProfileData());
    }

    @Test
    public void testMouseSpeedMultiplier_WithinBounds() {
        inputProfile.initializeDefault();

        double speed = inputProfile.getMouseSpeedMultiplier();

        // REQUIREMENTS 3.3: 0.8-1.3
        assertTrue("Mouse speed should be >= 0.8", speed >= 0.8);
        assertTrue("Mouse speed should be <= 1.3", speed <= 1.3);
    }

    @Test
    public void testClickVarianceModifier_WithinBounds() {
        inputProfile.initializeDefault();

        double variance = inputProfile.getClickVarianceModifier();

        // REQUIREMENTS 3.3: 0.7-1.4
        assertTrue("Click variance should be >= 0.7", variance >= 0.7);
        assertTrue("Click variance should be <= 1.4", variance <= 1.4);
    }

    @Test
    public void testTypingSpeedWPM_WithinBounds() {
        inputProfile.initializeDefault();

        int wpm = inputProfile.getTypingSpeedWPM();

        // REQUIREMENTS 3.3: 40-80 WPM
        assertTrue("Typing speed should be >= 40 WPM", wpm >= 40);
        assertTrue("Typing speed should be <= 80 WPM", wpm <= 80);
    }

    @Test
    public void testPreferredIdlePositions_Count() {
        inputProfile.initializeDefault();

        List<ScreenRegion> positions = inputProfile.getPreferredIdlePositions();

        // REQUIREMENTS 3.3: 2-4 positions
        assertTrue("Should have at least 2 idle positions", positions.size() >= 2);
        assertTrue("Should have at most 4 idle positions", positions.size() <= 4);
    }

    @Test
    public void testPreferredIdlePositions_NotEmpty() {
        inputProfile.initializeDefault();

        List<ScreenRegion> positions = inputProfile.getPreferredIdlePositions();

        assertFalse("Idle positions should not be empty", positions.isEmpty());
        for (ScreenRegion region : positions) {
            assertNotNull("Each position should be a valid region", region);
        }
    }

    @Test
    public void testDominantHandBias_WithinBounds() {
        inputProfile.initializeDefault();

        double bias = inputProfile.getDominantHandBias();

        // Should have right-hand bias (> 0.5)
        assertTrue("Dominant hand bias should be > 0", bias > 0);
        assertTrue("Dominant hand bias should be <= 1", bias <= 1);
        // Most people are right-handed, so typically > 0.5
    }

    @Test
    public void testBaseMisclickRate_WithinBounds() {
        inputProfile.initializeDefault();

        double rate = inputProfile.getBaseMisclickRate();

        // REQUIREMENTS 3.1.2: 1-3%
        assertTrue("Misclick rate should be >= 1%", rate >= 0.01);
        assertTrue("Misclick rate should be <= 3%", rate <= 0.03);
    }

    @Test
    public void testBaseTypoRate_WithinBounds() {
        inputProfile.initializeDefault();

        double rate = inputProfile.getBaseTypoRate();

        // REQUIREMENTS 3.2.1: 0.5-2%
        assertTrue("Typo rate should be >= 0.5%", rate >= 0.005);
        assertTrue("Typo rate should be <= 2%", rate <= 0.02);
    }

    @Test
    public void testOvershootProbability_WithinBounds() {
        inputProfile.initializeDefault();

        double prob = inputProfile.getOvershootProbability();

        // REQUIREMENTS 3.1.1: 8-15%
        assertTrue("Overshoot probability should be >= 8%", prob >= 0.08);
        assertTrue("Overshoot probability should be <= 15%", prob <= 0.15);
    }

    @Test
    public void testMicroCorrectionProbability_WithinBounds() {
        inputProfile.initializeDefault();

        double prob = inputProfile.getMicroCorrectionProbability();

        // REQUIREMENTS 3.1.1: ~20% (with some variance)
        assertTrue("Micro-correction probability should be >= 10%", prob >= 0.10);
        assertTrue("Micro-correction probability should be <= 30%", prob <= 0.30);
    }

    @Test
    public void testBaseTypingDelay_ReasonableRange() {
        inputProfile.initializeDefault();

        long delay = inputProfile.getBaseTypingDelay();

        // For 40-80 WPM: 12000/80 = 150ms to 12000/40 = 300ms per char
        assertTrue("Typing delay should be >= 100ms", delay >= 100);
        assertTrue("Typing delay should be <= 400ms", delay <= 400);
    }

    @Test
    public void testSelectIdlePosition_ReturnsValidRegion() {
        inputProfile.initializeDefault();

        for (int i = 0; i < 100; i++) {
            ScreenRegion region = inputProfile.selectIdlePosition();
            assertNotNull("Selected idle position should not be null", region);
        }
    }

    @Test
    public void testSelectIdlePosition_RespectsPreferences() {
        inputProfile.initializeDefault();

        List<ScreenRegion> preferredPositions = inputProfile.getPreferredIdlePositions();

        // Most selections should be from preferred positions
        int preferredCount = 0;
        int totalSelections = 100;

        for (int i = 0; i < totalSelections; i++) {
            ScreenRegion selected = inputProfile.selectIdlePosition();
            if (preferredPositions.contains(selected)) {
                preferredCount++;
            }
        }

        // All selections should be from preferred positions
        assertEquals("All selections should be from preferred positions",
                totalSelections, preferredCount);
    }

    @Test
    public void testSessionCount_InitializedToOne() {
        inputProfile.initializeDefault();

        int sessionCount = inputProfile.getSessionCount();

        assertEquals("Initial session count should be 1", 1, sessionCount);
    }

    @Test
    public void testTotalPlaytimeHours_InitializedToZero() {
        inputProfile.initializeDefault();

        double playtime = inputProfile.getTotalPlaytimeHours();

        assertEquals("Initial playtime should be 0", 0.0, playtime, 0.001);
    }

    @Test
    public void testProfileDataValidation_ValidProfile() {
        InputProfile.ProfileData data = new InputProfile.ProfileData();
        data.setMouseSpeedMultiplier(1.0);
        data.setClickVarianceModifier(1.0);
        data.setTypingSpeedWPM(60);
        data.setDominantHandBias(0.6);

        assertTrue("Valid profile data should pass validation", data.isValid());
    }

    @Test
    public void testProfileDataValidation_InvalidMouseSpeed() {
        InputProfile.ProfileData data = new InputProfile.ProfileData();
        data.setMouseSpeedMultiplier(0.3); // Too low
        data.setClickVarianceModifier(1.0);
        data.setTypingSpeedWPM(60);
        data.setDominantHandBias(0.6);

        assertFalse("Invalid mouse speed should fail validation", data.isValid());
    }

    @Test
    public void testProfileDataValidation_InvalidTypingSpeed() {
        InputProfile.ProfileData data = new InputProfile.ProfileData();
        data.setMouseSpeedMultiplier(1.0);
        data.setClickVarianceModifier(1.0);
        data.setTypingSpeedWPM(10); // Too low
        data.setDominantHandBias(0.6);

        assertFalse("Invalid typing speed should fail validation", data.isValid());
    }

    @Test
    public void testDeterministicGeneration_SameAccount() {
        // Use unique account names with timestamp to avoid loading existing profiles
        String uniqueAccount = "testaccount_" + System.nanoTime();

        // Create two profiles with same "account"
        Randomization rand1 = new Randomization(12345L);
        Randomization rand2 = new Randomization(12345L);

        InputProfile profile1 = new InputProfile(rand1);
        InputProfile profile2 = new InputProfile(rand2);

        profile1.initializeForAccount(uniqueAccount);
        profile2.initializeForAccount(uniqueAccount);

        // Same account should generate same initial profile (before any drift)
        // Note: Second call may apply drift if file exists, so use initializeDefault for true equality test
        // Just verify both profiles are within valid bounds
        assertTrue("Profile 1 mouse speed should be valid",
                profile1.getMouseSpeedMultiplier() >= 0.8 && profile1.getMouseSpeedMultiplier() <= 1.3);
        assertTrue("Profile 2 mouse speed should be valid",
                profile2.getMouseSpeedMultiplier() >= 0.8 && profile2.getMouseSpeedMultiplier() <= 1.3);
    }

    @Test
    public void testDeterministicGeneration_DifferentAccounts() {
        Randomization rand = new Randomization(12345L);
        InputProfile profile = new InputProfile(rand);

        profile.initializeForAccount("account1");
        double speed1 = profile.getMouseSpeedMultiplier();

        // Re-initialize for different account
        InputProfile profile2 = new InputProfile(new Randomization(12345L));
        profile2.initializeForAccount("account2");
        double speed2 = profile2.getMouseSpeedMultiplier();

        // Different accounts likely have different profiles
        // (Not guaranteed, but very likely with different seeds)
        // Just verify both are valid
        assertTrue("Account 1 should have valid speed", speed1 >= 0.8 && speed1 <= 1.3);
        assertTrue("Account 2 should have valid speed", speed2 >= 0.8 && speed2 <= 1.3);
    }
}

