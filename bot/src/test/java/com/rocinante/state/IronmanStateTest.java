package com.rocinante.state;

import com.rocinante.behavior.AccountType;
import com.rocinante.state.IronmanState.HCIMSafetyLevel;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Varbits;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for IronmanState.
 * Tests account type detection, safety levels, and ironman restrictions.
 */
public class IronmanStateTest {

    @Mock
    private Client client;

    private IronmanState ironmanState;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        
        // Default varbit value - normal account
        when(client.getVarbitValue(Varbits.ACCOUNT_TYPE)).thenReturn(0);
        
        ironmanState = new IronmanState(client);
    }

    // ========================================================================
    // HCIMSafetyLevel Tests
    // ========================================================================

    @Test
    public void testHCIMSafetyLevel_NormalMultiplier() {
        assertEquals(1.0, HCIMSafetyLevel.NORMAL.getFleeThresholdMultiplier(), 0.01);
    }

    @Test
    public void testHCIMSafetyLevel_CautiousMultiplier() {
        assertEquals(1.3, HCIMSafetyLevel.CAUTIOUS.getFleeThresholdMultiplier(), 0.01);
    }

    @Test
    public void testHCIMSafetyLevel_ParanoidMultiplier() {
        assertEquals(1.6, HCIMSafetyLevel.PARANOID.getFleeThresholdMultiplier(), 0.01);
    }

    @Test
    public void testHCIMSafetyLevel_FromString_Valid() {
        assertEquals(HCIMSafetyLevel.NORMAL, HCIMSafetyLevel.fromString("NORMAL"));
        assertEquals(HCIMSafetyLevel.CAUTIOUS, HCIMSafetyLevel.fromString("CAUTIOUS"));
        assertEquals(HCIMSafetyLevel.PARANOID, HCIMSafetyLevel.fromString("PARANOID"));
    }

    @Test
    public void testHCIMSafetyLevel_FromString_CaseInsensitive() {
        assertEquals(HCIMSafetyLevel.CAUTIOUS, HCIMSafetyLevel.fromString("cautious"));
        assertEquals(HCIMSafetyLevel.PARANOID, HCIMSafetyLevel.fromString("pArAnOiD"));
    }

    @Test
    public void testHCIMSafetyLevel_FromString_Invalid_DefaultsToNormal() {
        assertEquals(HCIMSafetyLevel.NORMAL, HCIMSafetyLevel.fromString("invalid"));
        assertEquals(HCIMSafetyLevel.NORMAL, HCIMSafetyLevel.fromString(""));
        assertEquals(HCIMSafetyLevel.NORMAL, HCIMSafetyLevel.fromString(null));
    }

    // ========================================================================
    // Account Type Query Tests
    // ========================================================================

    @Test
    public void testIsIronman_NormalAccount_ReturnsFalse() {
        assertFalse(ironmanState.isIronman());
    }

    @Test
    public void testIsHardcore_NormalAccount_ReturnsFalse() {
        assertFalse(ironmanState.isHardcore());
    }

    @Test
    public void testIsUltimate_NormalAccount_ReturnsFalse() {
        assertFalse(ironmanState.isUltimate());
    }

    // ========================================================================
    // Ironman Restriction Tests
    // ========================================================================

    @Test
    public void testCanUseGrandExchange_NormalAccount_ReturnsTrue() {
        assertTrue(ironmanState.canUseGrandExchange());
    }

    @Test
    public void testCanTrade_NormalAccount_ReturnsTrue() {
        assertTrue(ironmanState.canTrade());
    }

    @Test
    public void testCanUseBank_NormalAccount_ReturnsTrue() {
        assertTrue(ironmanState.canUseBank());
    }

    // ========================================================================
    // Flee Threshold Multiplier Tests
    // ========================================================================

    @Test
    public void testGetFleeThresholdMultiplier_NormalAccount_Returns1() {
        assertEquals(1.0, ironmanState.getFleeThresholdMultiplier(), 0.01);
    }

    // ========================================================================
    // Risk Assessment Tests
    // ========================================================================

    @Test
    public void testIsTooRisky_NormalAccount_AlwaysFalse() {
        // Normal accounts don't care about death risk
        assertFalse(ironmanState.isTooRisky(0.5));
        assertFalse(ironmanState.isTooRisky(0.99));
    }

    // ========================================================================
    // Varbit Update Tests
    // ========================================================================

    @Test
    public void testUpdateFromVarbit_NotLoggedIn_NoUpdate() {
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        
        ironmanState.updateFromVarbit();
        
        // Should not query varbit when not logged in
        verify(client, never()).getVarbitValue(Varbits.ACCOUNT_TYPE);
    }

    @Test
    public void testUpdateFromVarbit_LoggedIn_ReadsVarbit() {
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        
        ironmanState.updateFromVarbit();
        
        verify(client).getVarbitValue(Varbits.ACCOUNT_TYPE);
    }

    // ========================================================================
    // Mismatch Detection Tests
    // ========================================================================

    @Test
    public void testHasMismatch_NormalIntended_NeverMismatch() {
        // If intended is normal, there's no mismatch to care about
        assertFalse(ironmanState.hasMismatch());
    }

    // ========================================================================
    // Tutorial Completion Tests
    // ========================================================================

    @Test
    public void testTutorialCompleted_InitiallyFalse() {
        assertFalse(ironmanState.isTutorialCompleted());
    }

    @Test
    public void testMarkTutorialCompleted_SetsFlag() {
        ironmanState.markTutorialCompleted();
        assertTrue(ironmanState.isTutorialCompleted());
    }

    // ========================================================================
    // Effective Type Tests
    // ========================================================================

    @Test
    public void testGetEffectiveType_BeforeTutorial_ReturnsIntended() {
        // Before tutorial, effective = intended
        assertEquals(ironmanState.getIntendedType(), ironmanState.getEffectiveType());
    }

    @Test
    public void testGetEffectiveType_AfterTutorial_ReturnsActual() {
        ironmanState.markTutorialCompleted();
        assertEquals(ironmanState.getActualType(), ironmanState.getEffectiveType());
    }

    // ========================================================================
    // Set Actual Type Tests
    // ========================================================================

    @Test
    public void testSetActualType_UpdatesActual() {
        ironmanState.setActualType(AccountType.IRONMAN);
        assertEquals(AccountType.IRONMAN, ironmanState.getActualType());
    }

    // ========================================================================
    // Reconcile Tests
    // ========================================================================

    @Test
    public void testReconcileToActual_UpdatesIntendedToMatchActual() {
        ironmanState.setActualType(AccountType.HARDCORE_IRONMAN);
        ironmanState.reconcileToActual();
        
        assertEquals(AccountType.HARDCORE_IRONMAN, ironmanState.getIntendedType());
    }

    // ========================================================================
    // Summary Tests
    // ========================================================================

    @Test
    public void testGetSummary_ContainsAllFields() {
        String summary = ironmanState.getSummary();
        
        assertTrue(summary.contains("intended"));
        assertTrue(summary.contains("actual"));
        assertTrue(summary.contains("effective"));
        assertTrue(summary.contains("tutorialDone"));
        assertTrue(summary.contains("safety"));
    }

    @Test
    public void testToString_ReturnsSummary() {
        assertEquals(ironmanState.getSummary(), ironmanState.toString());
    }

    // ========================================================================
    // Initial State Tests
    // ========================================================================

    @Test
    public void testInitialState_DefaultsToNormal() {
        assertEquals(AccountType.NORMAL, ironmanState.getIntendedType());
        assertEquals(AccountType.NORMAL, ironmanState.getActualType());
        assertEquals(HCIMSafetyLevel.NORMAL, ironmanState.getHcimSafetyLevel());
    }
}
