package com.rocinante.timing;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for DelayProfile enum.
 * Verifies all profiles match REQUIREMENTS.md Section 4.1.2 specifications.
 */
public class DelayProfileTest {

    // ========================================================================
    // REACTION Profile Tests
    // ========================================================================

    @Test
    public void testReaction_DistributionType() {
        // REACTION uses Ex-Gaussian for realistic human reaction time distribution
        assertEquals("REACTION should use Ex-Gaussian distribution",
                DistributionType.EX_GAUSSIAN, DelayProfile.REACTION.getDistributionType());
    }

    @Test
    public void testReaction_ExGaussianParameters() {
        // Ex-Gaussian: mu=200ms (Gaussian mean), sigma=30ms, tau=50ms
        // Effective mean reaction time = mu + tau = 250ms
        assertEquals("REACTION mu (Gaussian mean) should be 200ms",
                200.0, DelayProfile.REACTION.getMean(), 0.001);
        assertEquals("REACTION sigma should be 30ms",
                30.0, DelayProfile.REACTION.getStdDev(), 0.001);
        assertEquals("REACTION tau should be 50ms",
                50.0, DelayProfile.REACTION.getTau(), 0.001);
    }

    @Test
    public void testReaction_Bounds() {
        assertEquals("REACTION min should be 150ms",
                Long.valueOf(150), DelayProfile.REACTION.getMin());
        assertEquals("REACTION max should be 600ms",
                Long.valueOf(600), DelayProfile.REACTION.getMax());
    }

    // ========================================================================
    // REACTION_EXPECTED Profile Tests (Contextual - anticipated events)
    // ========================================================================

    @Test
    public void testReactionExpected_DistributionType() {
        assertEquals("REACTION_EXPECTED should use Ex-Gaussian distribution",
                DistributionType.EX_GAUSSIAN, DelayProfile.REACTION_EXPECTED.getDistributionType());
    }

    @Test
    public void testReactionExpected_ExGaussianParameters() {
        // Faster than general REACTION (anticipated)
        assertEquals("REACTION_EXPECTED mu should be 150ms",
                150.0, DelayProfile.REACTION_EXPECTED.getMean(), 0.001);
        assertEquals("REACTION_EXPECTED sigma should be 25ms",
                25.0, DelayProfile.REACTION_EXPECTED.getStdDev(), 0.001);
        assertEquals("REACTION_EXPECTED tau should be 30ms",
                30.0, DelayProfile.REACTION_EXPECTED.getTau(), 0.001);
    }

    @Test
    public void testReactionExpected_Bounds() {
        assertEquals("REACTION_EXPECTED min should be 100ms",
                Long.valueOf(100), DelayProfile.REACTION_EXPECTED.getMin());
        assertEquals("REACTION_EXPECTED max should be 400ms",
                Long.valueOf(400), DelayProfile.REACTION_EXPECTED.getMax());
    }

    // ========================================================================
    // REACTION_UNEXPECTED Profile Tests (Contextual - surprise events)
    // ========================================================================

    @Test
    public void testReactionUnexpected_DistributionType() {
        assertEquals("REACTION_UNEXPECTED should use Ex-Gaussian distribution",
                DistributionType.EX_GAUSSIAN, DelayProfile.REACTION_UNEXPECTED.getDistributionType());
    }

    @Test
    public void testReactionUnexpected_ExGaussianParameters() {
        // Slower than general REACTION (surprised)
        assertEquals("REACTION_UNEXPECTED mu should be 350ms",
                350.0, DelayProfile.REACTION_UNEXPECTED.getMean(), 0.001);
        assertEquals("REACTION_UNEXPECTED sigma should be 60ms",
                60.0, DelayProfile.REACTION_UNEXPECTED.getStdDev(), 0.001);
        assertEquals("REACTION_UNEXPECTED tau should be 100ms",
                100.0, DelayProfile.REACTION_UNEXPECTED.getTau(), 0.001);
    }

    @Test
    public void testReactionUnexpected_Bounds() {
        assertEquals("REACTION_UNEXPECTED min should be 250ms",
                Long.valueOf(250), DelayProfile.REACTION_UNEXPECTED.getMin());
        assertEquals("REACTION_UNEXPECTED max should be 800ms",
                Long.valueOf(800), DelayProfile.REACTION_UNEXPECTED.getMax());
    }

    // ========================================================================
    // REACTION_COMPLEX Profile Tests (Contextual - decision required)
    // ========================================================================

    @Test
    public void testReactionComplex_DistributionType() {
        assertEquals("REACTION_COMPLEX should use Ex-Gaussian distribution",
                DistributionType.EX_GAUSSIAN, DelayProfile.REACTION_COMPLEX.getDistributionType());
    }

    @Test
    public void testReactionComplex_ExGaussianParameters() {
        // Slowest reaction (cognitive processing needed)
        assertEquals("REACTION_COMPLEX mu should be 500ms",
                500.0, DelayProfile.REACTION_COMPLEX.getMean(), 0.001);
        assertEquals("REACTION_COMPLEX sigma should be 100ms",
                100.0, DelayProfile.REACTION_COMPLEX.getStdDev(), 0.001);
        assertEquals("REACTION_COMPLEX tau should be 200ms",
                200.0, DelayProfile.REACTION_COMPLEX.getTau(), 0.001);
    }

    @Test
    public void testReactionComplex_Bounds() {
        assertEquals("REACTION_COMPLEX min should be 300ms",
                Long.valueOf(300), DelayProfile.REACTION_COMPLEX.getMin());
        assertEquals("REACTION_COMPLEX max should be 1500ms",
                Long.valueOf(1500), DelayProfile.REACTION_COMPLEX.getMax());
    }

    // ========================================================================
    // ACTION_GAP Profile Tests
    // ========================================================================

    @Test
    public void testActionGap_DistributionType() {
        assertEquals("ACTION_GAP should use Gaussian distribution",
                DistributionType.GAUSSIAN, DelayProfile.ACTION_GAP.getDistributionType());
    }

    @Test
    public void testActionGap_MeanAndStdDev() {
        assertEquals("ACTION_GAP mean should be 800ms",
                800.0, DelayProfile.ACTION_GAP.getMean(), 0.001);
        assertEquals("ACTION_GAP stdDev should be 200ms",
                200.0, DelayProfile.ACTION_GAP.getStdDev(), 0.001);
    }

    @Test
    public void testActionGap_Bounds() {
        assertEquals("ACTION_GAP min should be 400ms",
                Long.valueOf(400), DelayProfile.ACTION_GAP.getMin());
        assertEquals("ACTION_GAP max should be 2000ms",
                Long.valueOf(2000), DelayProfile.ACTION_GAP.getMax());
    }

    // ========================================================================
    // MENU_SELECT Profile Tests
    // ========================================================================

    @Test
    public void testMenuSelect_DistributionType() {
        assertEquals("MENU_SELECT should use Gaussian distribution",
                DistributionType.GAUSSIAN, DelayProfile.MENU_SELECT.getDistributionType());
    }

    @Test
    public void testMenuSelect_MeanAndStdDev() {
        assertEquals("MENU_SELECT mean should be 180ms",
                180.0, DelayProfile.MENU_SELECT.getMean(), 0.001);
        assertEquals("MENU_SELECT stdDev should be 50ms",
                50.0, DelayProfile.MENU_SELECT.getStdDev(), 0.001);
    }

    @Test
    public void testMenuSelect_NoBounds() {
        assertNull("MENU_SELECT should have no min bound",
                DelayProfile.MENU_SELECT.getMin());
        assertNull("MENU_SELECT should have no max bound",
                DelayProfile.MENU_SELECT.getMax());
    }

    // ========================================================================
    // DIALOGUE_READ Profile Tests
    // ========================================================================

    @Test
    public void testDialogueRead_DistributionType() {
        assertEquals("DIALOGUE_READ should use Gaussian distribution",
                DistributionType.GAUSSIAN, DelayProfile.DIALOGUE_READ.getDistributionType());
    }

    @Test
    public void testDialogueRead_BaseMean() {
        assertEquals("DIALOGUE_READ base mean should be 1200ms",
                1200.0, DelayProfile.DIALOGUE_READ.getMean(), 0.001);
    }

    @Test
    public void testDialogueRead_StdDev() {
        assertEquals("DIALOGUE_READ stdDev should be 300ms",
                300.0, DelayProfile.DIALOGUE_READ.getStdDev(), 0.001);
    }

    @Test
    public void testDialogueRead_PerWordDelay() {
        assertEquals("DIALOGUE_READ per-word delay should be 50ms",
                50.0, DelayProfile.DIALOGUE_MS_PER_WORD, 0.001);
    }

    @Test
    public void testDialogueRead_AdjustedMean_ZeroWords() {
        double adjusted = DelayProfile.DIALOGUE_READ.getAdjustedMeanForDialogue(0);
        assertEquals("0 words should give base mean",
                1200.0, adjusted, 0.001);
    }

    @Test
    public void testDialogueRead_AdjustedMean_TenWords() {
        double adjusted = DelayProfile.DIALOGUE_READ.getAdjustedMeanForDialogue(10);
        // 1200 + (10 * 50) = 1700
        assertEquals("10 words should add 500ms to base",
                1700.0, adjusted, 0.001);
    }

    @Test
    public void testDialogueRead_AdjustedMean_OtherProfile() {
        // Non-DIALOGUE_READ profiles should just return their mean
        double adjusted = DelayProfile.ACTION_GAP.getAdjustedMeanForDialogue(10);
        assertEquals("Non-DIALOGUE_READ should return base mean",
                800.0, adjusted, 0.001);
    }

    // ========================================================================
    // INVENTORY_SCAN Profile Tests
    // ========================================================================

    @Test
    public void testInventoryScan_DistributionType() {
        assertEquals("INVENTORY_SCAN should use Gaussian distribution",
                DistributionType.GAUSSIAN, DelayProfile.INVENTORY_SCAN.getDistributionType());
    }

    @Test
    public void testInventoryScan_MeanAndStdDev() {
        assertEquals("INVENTORY_SCAN mean should be 400ms",
                400.0, DelayProfile.INVENTORY_SCAN.getMean(), 0.001);
        assertEquals("INVENTORY_SCAN stdDev should be 100ms",
                100.0, DelayProfile.INVENTORY_SCAN.getStdDev(), 0.001);
    }

    // ========================================================================
    // BANK_SEARCH Profile Tests
    // ========================================================================

    @Test
    public void testBankSearch_DistributionType() {
        assertEquals("BANK_SEARCH should use Gaussian distribution",
                DistributionType.GAUSSIAN, DelayProfile.BANK_SEARCH.getDistributionType());
    }

    @Test
    public void testBankSearch_MeanAndStdDev() {
        assertEquals("BANK_SEARCH mean should be 600ms",
                600.0, DelayProfile.BANK_SEARCH.getMean(), 0.001);
        assertEquals("BANK_SEARCH stdDev should be 150ms",
                150.0, DelayProfile.BANK_SEARCH.getStdDev(), 0.001);
    }

    // ========================================================================
    // PRAYER_SWITCH Profile Tests
    // ========================================================================

    @Test
    public void testPrayerSwitch_DistributionType() {
        assertEquals("PRAYER_SWITCH should use Gaussian distribution",
                DistributionType.GAUSSIAN, DelayProfile.PRAYER_SWITCH.getDistributionType());
    }

    @Test
    public void testPrayerSwitch_MeanAndStdDev() {
        assertEquals("PRAYER_SWITCH mean should be 80ms",
                80.0, DelayProfile.PRAYER_SWITCH.getMean(), 0.001);
        assertEquals("PRAYER_SWITCH stdDev should be 20ms",
                20.0, DelayProfile.PRAYER_SWITCH.getStdDev(), 0.001);
    }

    @Test
    public void testPrayerSwitch_MinBound() {
        assertEquals("PRAYER_SWITCH min should be 50ms",
                Long.valueOf(50), DelayProfile.PRAYER_SWITCH.getMin());
        assertNull("PRAYER_SWITCH should have no max bound",
                DelayProfile.PRAYER_SWITCH.getMax());
    }

    // ========================================================================
    // GEAR_SWITCH Profile Tests
    // ========================================================================

    @Test
    public void testGearSwitch_DistributionType() {
        assertEquals("GEAR_SWITCH should use Gaussian distribution",
                DistributionType.GAUSSIAN, DelayProfile.GEAR_SWITCH.getDistributionType());
    }

    @Test
    public void testGearSwitch_MeanAndStdDev() {
        assertEquals("GEAR_SWITCH mean should be 120ms",
                120.0, DelayProfile.GEAR_SWITCH.getMean(), 0.001);
        assertEquals("GEAR_SWITCH stdDev should be 30ms",
                30.0, DelayProfile.GEAR_SWITCH.getStdDev(), 0.001);
    }

    // ========================================================================
    // Utility Method Tests
    // ========================================================================

    @Test
    public void testHasMin_True() {
        assertTrue("REACTION should have min bound",
                DelayProfile.REACTION.hasMin());
        assertTrue("PRAYER_SWITCH should have min bound",
                DelayProfile.PRAYER_SWITCH.hasMin());
    }

    @Test
    public void testHasMin_False() {
        assertFalse("MENU_SELECT should not have min bound",
                DelayProfile.MENU_SELECT.hasMin());
        assertFalse("DIALOGUE_READ should not have min bound",
                DelayProfile.DIALOGUE_READ.hasMin());
    }

    @Test
    public void testHasMax_True() {
        assertTrue("REACTION should have max bound",
                DelayProfile.REACTION.hasMax());
        assertTrue("ACTION_GAP should have max bound",
                DelayProfile.ACTION_GAP.hasMax());
    }

    @Test
    public void testHasMax_False() {
        assertFalse("MENU_SELECT should not have max bound",
                DelayProfile.MENU_SELECT.hasMax());
        assertFalse("PRAYER_SWITCH should not have max bound",
                DelayProfile.PRAYER_SWITCH.hasMax());
    }

    @Test
    public void testGetMinOrDefault_WithMin() {
        assertEquals("REACTION minOrDefault should return 150",
                150L, DelayProfile.REACTION.getMinOrDefault());
    }

    @Test
    public void testGetMinOrDefault_WithoutMin() {
        assertEquals("MENU_SELECT minOrDefault should return 0",
                0L, DelayProfile.MENU_SELECT.getMinOrDefault());
    }

    @Test
    public void testGetMaxOrDefault_WithMax() {
        assertEquals("REACTION maxOrDefault should return 600",
                600L, DelayProfile.REACTION.getMaxOrDefault());
    }

    @Test
    public void testGetMaxOrDefault_WithoutMax() {
        assertEquals("MENU_SELECT maxOrDefault should return Long.MAX_VALUE",
                Long.MAX_VALUE, DelayProfile.MENU_SELECT.getMaxOrDefault());
    }

    // ========================================================================
    // All Profiles Have Required Fields
    // ========================================================================

    @Test
    public void testAllProfiles_HaveDistributionType() {
        for (DelayProfile profile : DelayProfile.values()) {
            assertNotNull("Profile " + profile.name() + " should have distribution type",
                    profile.getDistributionType());
        }
    }

    @Test
    public void testAllProfiles_HavePositiveMean() {
        for (DelayProfile profile : DelayProfile.values()) {
            assertTrue("Profile " + profile.name() + " should have positive mean",
                    profile.getMean() > 0);
        }
    }

    @Test
    public void testAllProfiles_HaveNonNegativeStdDev() {
        for (DelayProfile profile : DelayProfile.values()) {
            assertTrue("Profile " + profile.name() + " should have non-negative stdDev",
                    profile.getStdDev() >= 0);
        }
    }

    @Test
    public void testAllProfiles_HaveDescription() {
        for (DelayProfile profile : DelayProfile.values()) {
            assertNotNull("Profile " + profile.name() + " should have description",
                    profile.getDescription());
            assertFalse("Profile " + profile.name() + " description should not be empty",
                    profile.getDescription().isEmpty());
        }
    }

    @Test
    public void testAllProfiles_ValidBounds() {
        for (DelayProfile profile : DelayProfile.values()) {
            if (profile.hasMin() && profile.hasMax()) {
                assertTrue("Profile " + profile.name() + " min should be <= max",
                        profile.getMin() <= profile.getMax());
            }
        }
    }

    // ========================================================================
    // Profile Count Verification
    // ========================================================================

    @Test
    public void testProfileCount() {
        // 8 base profiles + 3 contextual reaction profiles = 11
        assertEquals("Should have exactly 11 delay profiles",
                11, DelayProfile.values().length);
    }
}

