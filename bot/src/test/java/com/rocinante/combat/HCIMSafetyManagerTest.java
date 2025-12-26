package com.rocinante.combat;

import com.rocinante.state.AggressorInfo;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for HCIMSafetyManager inner classes and enums.
 */
public class HCIMSafetyManagerTest {

    // ========================================================================
    // SafetyCheckResult Tests
    // ========================================================================

    @Test
    public void testSafetyCheckResult_PassedWhenEmpty() {
        HCIMSafetyManager.SafetyCheckResult result = new HCIMSafetyManager.SafetyCheckResult();
        assertTrue(result.passed());
        assertTrue(result.getFailures().isEmpty());
    }

    @Test
    public void testSafetyCheckResult_FailedWithReasons() {
        HCIMSafetyManager.SafetyCheckResult result = new HCIMSafetyManager.SafetyCheckResult();
        result.addFailure("No food");
        result.addFailure("No ring of life");

        assertFalse(result.passed());
        assertEquals(2, result.getFailures().size());
        assertTrue(result.getFailures().contains("No food"));
        assertTrue(result.getFailures().contains("No ring of life"));
    }

    @Test
    public void testSafetyCheckResult_StaticPassed() {
        HCIMSafetyManager.SafetyCheckResult result = HCIMSafetyManager.SafetyCheckResult.createPassed();
        assertTrue(result.passed());
    }

    // ========================================================================
    // FleeReason Tests
    // ========================================================================

    @Test
    public void testFleeReason_Descriptions() {
        assertEquals("Multiple NPCs attacking", 
                HCIMSafetyManager.FleeReason.MULTI_COMBAT_PILEUP.getDescription());
        assertEquals("Low HP with no food", 
                HCIMSafetyManager.FleeReason.LOW_HP_NO_FOOD.getDescription());
        assertEquals("Venom at critical level (16+ damage)", 
                HCIMSafetyManager.FleeReason.VENOM_CRITICAL.getDescription());
        assertEquals("Player is skulled", 
                HCIMSafetyManager.FleeReason.SKULLED.getDescription());
        assertEquals("Ring of Life not equipped", 
                HCIMSafetyManager.FleeReason.RING_OF_LIFE_MISSING.getDescription());
    }

    // ========================================================================
    // EscapeMethod Tests
    // ========================================================================

    @Test
    public void testEscapeMethod_OneClickTeleport() {
        HCIMSafetyManager.EscapeMethod method = new HCIMSafetyManager.EscapeMethod(
                HCIMSafetyManager.EscapeMethod.Type.ONE_CLICK_TELEPORT, 
                12625, // Ring of dueling
                false
        );

        assertEquals(HCIMSafetyManager.EscapeMethod.Type.ONE_CLICK_TELEPORT, method.getType());
        assertEquals(12625, method.getItemId());
        assertFalse(method.isEquipped());
    }

    @Test
    public void testEscapeMethod_RunAndLogout() {
        HCIMSafetyManager.EscapeMethod method = new HCIMSafetyManager.EscapeMethod(
                HCIMSafetyManager.EscapeMethod.Type.RUN_AND_LOGOUT, 
                -1, 
                false
        );

        assertEquals(HCIMSafetyManager.EscapeMethod.Type.RUN_AND_LOGOUT, method.getType());
        assertEquals(-1, method.getItemId());
    }

    // ========================================================================
    // AggressorInfo.estimateMaxHit Tests
    // ========================================================================

    @Test
    public void testEstimateMaxHit() {
        // Combat level 3 -> max hit 1
        assertEquals(1, AggressorInfo.estimateMaxHit(3));
        
        // Combat level 30 -> max hit 10
        assertEquals(10, AggressorInfo.estimateMaxHit(30));
        
        // Combat level 150 -> max hit 50
        assertEquals(50, AggressorInfo.estimateMaxHit(150));
        
        // Combat level 0 -> min 1
        assertEquals(1, AggressorInfo.estimateMaxHit(0));
    }
}

