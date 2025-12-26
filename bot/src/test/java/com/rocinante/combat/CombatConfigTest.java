package com.rocinante.combat;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for CombatConfig class.
 */
public class CombatConfigTest {

    // ========================================================================
    // Default Config Tests
    // ========================================================================

    @Test
    public void testDefaultConfig() {
        CombatConfig config = CombatConfig.DEFAULT;

        assertEquals(0.50, config.getPrimaryEatThreshold(), 0.001);
        assertEquals(0.25, config.getPanicEatThreshold(), 0.001);
        assertEquals(2, config.getMinimumFoodCount());
        assertTrue(config.isUseComboEating());
        assertFalse(config.isHcimMode());
    }

    @Test
    public void testHcimSafeConfig() {
        CombatConfig config = CombatConfig.HCIM_SAFE;

        assertEquals(0.65, config.getPrimaryEatThreshold(), 0.001);
        assertEquals(0.40, config.getPanicEatThreshold(), 0.001);
        assertEquals(4, config.getMinimumFoodCount());
        assertTrue(config.isHcimMode());
        assertTrue(config.isHcimRequireRingOfLife());
        assertTrue(config.isHcimRequireEmergencyTeleport());
    }

    // ========================================================================
    // Effective Threshold Tests
    // ========================================================================

    @Test
    public void testGetEffectiveEatThreshold_Normal() {
        CombatConfig config = CombatConfig.builder()
                .primaryEatThreshold(0.50)
                .hcimMode(false)
                .build();

        assertEquals(0.50, config.getEffectiveEatThreshold(false), 0.001);
        assertEquals(0.50, config.getEffectiveEatThreshold(true), 0.001); // HCIM mode not enabled
    }

    @Test
    public void testGetEffectiveEatThreshold_HcimMode() {
        CombatConfig config = CombatConfig.builder()
                .primaryEatThreshold(0.50)
                .hcimMode(true)
                .build();

        assertEquals(0.50, config.getEffectiveEatThreshold(false), 0.001);
        assertEquals(0.65, config.getEffectiveEatThreshold(true), 0.001); // Minimum 65% for HCIM
    }

    @Test
    public void testGetEffectivePanicThreshold_Normal() {
        CombatConfig config = CombatConfig.builder()
                .panicEatThreshold(0.25)
                .hcimMode(false)
                .build();

        assertEquals(0.25, config.getEffectivePanicThreshold(false), 0.001);
    }

    @Test
    public void testGetEffectivePanicThreshold_HcimMode() {
        CombatConfig config = CombatConfig.builder()
                .panicEatThreshold(0.25)
                .hcimMode(true)
                .build();

        assertEquals(0.40, config.getEffectivePanicThreshold(true), 0.001); // Minimum 40% for HCIM
    }

    @Test
    public void testGetEffectiveMinFoodCount_Normal() {
        CombatConfig config = CombatConfig.builder()
                .minimumFoodCount(2)
                .hcimMinFoodCount(4)
                .hcimMode(false)
                .build();

        assertEquals(2, config.getEffectiveMinFoodCount(false));
    }

    @Test
    public void testGetEffectiveMinFoodCount_HcimMode() {
        CombatConfig config = CombatConfig.builder()
                .minimumFoodCount(2)
                .hcimMinFoodCount(4)
                .hcimMode(true)
                .build();

        assertEquals(4, config.getEffectiveMinFoodCount(true));
    }

    // ========================================================================
    // Panic Eat Range Tests
    // ========================================================================

    @Test
    public void testIsInPanicEatRange() {
        CombatConfig config = CombatConfig.builder()
                .panicEatHealthRange(new double[]{0.55, 0.60})
                .build();

        assertTrue(config.isInPanicEatRange(0.55));
        assertTrue(config.isInPanicEatRange(0.57));
        assertTrue(config.isInPanicEatRange(0.60));
        assertFalse(config.isInPanicEatRange(0.54));
        assertFalse(config.isInPanicEatRange(0.61));
    }

    // ========================================================================
    // Builder Tests
    // ========================================================================

    @Test
    public void testBuilderDefaultValues() {
        CombatConfig config = CombatConfig.builder().build();

        assertEquals(0.50, config.getPrimaryEatThreshold(), 0.001);
        assertEquals(0.25, config.getPanicEatThreshold(), 0.001);
        assertEquals(2, config.getMinimumFoodCount());
        assertTrue(config.isUseComboEating());
        assertEquals(0.40, config.getComboEatProbability(), 0.001);
        assertEquals(0.10, config.getPanicEatExtraProbability(), 0.001);
        assertFalse(config.isUseProtectionPrayers());
        assertFalse(config.isUseSpecialAttack());
        assertEquals(100, config.getSpecEnergyThreshold());
        assertEquals(1000, config.getLootMinValue());
        assertFalse(config.isHcimMode());
    }

    @Test
    public void testBuilderCustomValues() {
        CombatConfig config = CombatConfig.builder()
                .primaryEatThreshold(0.70)
                .panicEatThreshold(0.50)
                .minimumFoodCount(5)
                .useComboEating(false)
                .useProtectionPrayers(true)
                .useSpecialAttack(true)
                .specEnergyThreshold(50)
                .lootMinValue(5000)
                .hcimMode(true)
                .build();

        assertEquals(0.70, config.getPrimaryEatThreshold(), 0.001);
        assertEquals(0.50, config.getPanicEatThreshold(), 0.001);
        assertEquals(5, config.getMinimumFoodCount());
        assertFalse(config.isUseComboEating());
        assertTrue(config.isUseProtectionPrayers());
        assertTrue(config.isUseSpecialAttack());
        assertEquals(50, config.getSpecEnergyThreshold());
        assertEquals(5000, config.getLootMinValue());
        assertTrue(config.isHcimMode());
    }

    // ========================================================================
    // Prayer Settings Tests
    // ========================================================================

    @Test
    public void testPrayerSettings() {
        CombatConfig config = CombatConfig.builder()
                .useProtectionPrayers(true)
                .useLazyFlicking(true)
                .missedFlickProbability(0.05)
                .useOffensivePrayers(true)
                .prayerRestoreThreshold(0.30)
                .build();

        assertTrue(config.isUseProtectionPrayers());
        assertTrue(config.isUseLazyFlicking());
        assertEquals(0.05, config.getMissedFlickProbability(), 0.001);
        assertTrue(config.isUseOffensivePrayers());
        assertEquals(0.30, config.getPrayerRestoreThreshold(), 0.001);
    }

    // ========================================================================
    // HCIM Settings Tests
    // ========================================================================

    @Test
    public void testHcimSettings() {
        CombatConfig config = CombatConfig.builder()
                .hcimMode(true)
                .hcimMinFoodCount(6)
                .hcimFleeThreshold(0.60)
                .hcimRequireRingOfLife(true)
                .hcimRequireEmergencyTeleport(true)
                .build();

        assertTrue(config.isHcimMode());
        assertEquals(6, config.getHcimMinFoodCount());
        assertEquals(0.60, config.getHcimFleeThreshold(), 0.001);
        assertTrue(config.isHcimRequireRingOfLife());
        assertTrue(config.isHcimRequireEmergencyTeleport());
    }
}

