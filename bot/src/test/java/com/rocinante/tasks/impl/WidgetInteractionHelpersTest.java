package com.rocinante.tasks.impl;

import com.rocinante.behavior.PlayerProfile;
import com.rocinante.core.GameStateService;
import com.rocinante.tasks.TaskContext;
import com.rocinante.util.WidgetInteractionHelpers;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for WidgetInteractionHelpers static tab helper methods.
 * These helpers are used by TravelTask, CombatTask, and other tasks to open tabs
 * while respecting player hotkey preferences.
 */
public class WidgetInteractionHelpersTest {

    @Mock
    private TaskContext ctx;

    @Mock
    private PlayerProfile playerProfile;

    @Mock
    private PlayerProfile.ProfileData profileData;

    @Mock
    private GameStateService gameStateService;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(ctx.getPlayerProfile()).thenReturn(playerProfile);
        when(ctx.getGameStateService()).thenReturn(gameStateService);
        when(playerProfile.getProfileData()).thenReturn(profileData);
    }

    // ========================================================================
    // shouldUseHotkey Tests - forceClick Override Priority
    // ========================================================================

    @Test
    public void shouldUseHotkey_ForceClickTrue_ReturnsFalse() {
        // forceClick=true means "force click, don't use hotkey"
        when(playerProfile.isLoaded()).thenReturn(true);
        when(profileData.isPrefersHotkeys()).thenReturn(true);  // Would normally use hotkey

        boolean result = WidgetInteractionHelpers.shouldUseHotkey(ctx, true);

        assertFalse("forceClick=true should return false (use click)", result);
    }

    @Test
    public void shouldUseHotkey_ForceClickFalse_ReturnsTrue() {
        // forceClick=false means "force hotkey"
        when(playerProfile.isLoaded()).thenReturn(true);
        when(profileData.isPrefersHotkeys()).thenReturn(false);  // Would normally click

        boolean result = WidgetInteractionHelpers.shouldUseHotkey(ctx, false);

        assertTrue("forceClick=false should return true (use hotkey)", result);
    }

    @Test
    public void shouldUseHotkey_ForceClickTrue_IgnoresPlayerProfile() {
        // Even if profile prefers hotkeys, forceClick=true should force click
        when(playerProfile.isLoaded()).thenReturn(true);
        when(profileData.isPrefersHotkeys()).thenReturn(true);

        boolean result = WidgetInteractionHelpers.shouldUseHotkey(ctx, true);

        assertFalse("forceClick should override profile preference", result);
        // Verify profile was NOT consulted
        verify(profileData, never()).isPrefersHotkeys();
    }

    // ========================================================================
    // shouldUseHotkey Tests - PlayerProfile Preference
    // ========================================================================

    @Test
    public void shouldUseHotkey_NullForceClick_ProfilePrefersHotkeys_ReturnsTrue() {
        when(playerProfile.isLoaded()).thenReturn(true);
        when(profileData.isPrefersHotkeys()).thenReturn(true);

        boolean result = WidgetInteractionHelpers.shouldUseHotkey(ctx, null);

        assertTrue("Should use hotkey when profile prefers hotkeys", result);
    }

    @Test
    public void shouldUseHotkey_NullForceClick_ProfilePrefersClicks_ReturnsFalse() {
        when(playerProfile.isLoaded()).thenReturn(true);
        when(profileData.isPrefersHotkeys()).thenReturn(false);

        boolean result = WidgetInteractionHelpers.shouldUseHotkey(ctx, null);

        assertFalse("Should click when profile prefers clicks", result);
    }

    @Test
    public void shouldUseHotkey_NullForceClick_ProfileNotLoaded_DefaultsToHotkey() {
        when(playerProfile.isLoaded()).thenReturn(false);

        boolean result = WidgetInteractionHelpers.shouldUseHotkey(ctx, null);

        assertTrue("Should default to hotkey when profile not loaded", result);
    }

    @Test
    public void shouldUseHotkey_NullForceClick_NullProfile_DefaultsToHotkey() {
        when(ctx.getPlayerProfile()).thenReturn(null);

        boolean result = WidgetInteractionHelpers.shouldUseHotkey(ctx, null);

        assertTrue("Should default to hotkey when profile is null", result);
    }

    // ========================================================================
    // getTabWidgetIds Tests - Interface Mode Handling
    // ========================================================================

    @Test
    public void getTabWidgetIds_FixedMode_ReturnsCorrectGroupAndChild() {
        when(gameStateService.getInterfaceMode()).thenReturn(GameStateService.InterfaceMode.FIXED);

        int[] ids = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_SPELLBOOK);

        assertEquals("Fixed mode should use group 548", 548, ids[0]);
        assertEquals("Fixed mode spellbook child should be 70", 70, ids[1]);
    }

    @Test
    public void getTabWidgetIds_ResizableClassic_ReturnsCorrectGroupAndChild() {
        when(gameStateService.getInterfaceMode()).thenReturn(GameStateService.InterfaceMode.RESIZABLE_CLASSIC);

        int[] ids = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_SPELLBOOK);

        assertEquals("Resizable classic should use group 161", 161, ids[0]);
        assertEquals("Resizable classic spellbook child should be 65", 65, ids[1]);
    }

    @Test
    public void getTabWidgetIds_ResizableModern_ReturnsCorrectGroupAndChild() {
        when(gameStateService.getInterfaceMode()).thenReturn(GameStateService.InterfaceMode.RESIZABLE_MODERN);

        int[] ids = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_SPELLBOOK);

        assertEquals("Resizable modern should use group 164", 164, ids[0]);
        assertEquals("Resizable modern spellbook child should be 58", 58, ids[1]);
    }

    @Test
    public void getTabWidgetIds_UnknownMode_FallsBackToFixed() {
        when(gameStateService.getInterfaceMode()).thenReturn(GameStateService.InterfaceMode.UNKNOWN);

        int[] ids = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_SPELLBOOK);

        assertEquals("Unknown mode should fallback to fixed group 548", 548, ids[0]);
        assertEquals("Unknown mode should fallback to fixed spellbook child 70", 70, ids[1]);
    }

    @Test
    public void getTabWidgetIds_NullMode_FallsBackToFixed() {
        when(gameStateService.getInterfaceMode()).thenReturn(null);

        int[] ids = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_SPELLBOOK);

        assertEquals("Null mode should fallback to fixed group 548", 548, ids[0]);
    }

    // ========================================================================
    // getTabWidgetIds Tests - All Tab Indices
    // ========================================================================

    @Test
    public void getTabWidgetIds_InventoryTab_AllModes() {
        // Test inventory tab (index 3) across all modes
        when(gameStateService.getInterfaceMode()).thenReturn(GameStateService.InterfaceMode.FIXED);
        int[] fixedIds = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_INVENTORY);
        assertEquals("Fixed inventory group", 548, fixedIds[0]);
        assertEquals("Fixed inventory child", 67, fixedIds[1]);

        when(gameStateService.getInterfaceMode()).thenReturn(GameStateService.InterfaceMode.RESIZABLE_CLASSIC);
        int[] classicIds = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_INVENTORY);
        assertEquals("Resizable classic inventory group", 161, classicIds[0]);
        assertEquals("Resizable classic inventory child", 62, classicIds[1]);

        when(gameStateService.getInterfaceMode()).thenReturn(GameStateService.InterfaceMode.RESIZABLE_MODERN);
        int[] modernIds = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_INVENTORY);
        assertEquals("Resizable modern inventory group", 164, modernIds[0]);
        assertEquals("Resizable modern inventory child", 55, modernIds[1]);
    }

    @Test
    public void getTabWidgetIds_EquipmentTab_AllModes() {
        // Test equipment tab (index 4) across all modes
        when(gameStateService.getInterfaceMode()).thenReturn(GameStateService.InterfaceMode.FIXED);
        int[] fixedIds = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_EQUIPMENT);
        assertEquals("Fixed equipment group", 548, fixedIds[0]);
        assertEquals("Fixed equipment child", 68, fixedIds[1]);

        when(gameStateService.getInterfaceMode()).thenReturn(GameStateService.InterfaceMode.RESIZABLE_CLASSIC);
        int[] classicIds = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_EQUIPMENT);
        assertEquals("Resizable classic equipment group", 161, classicIds[0]);
        assertEquals("Resizable classic equipment child", 63, classicIds[1]);

        when(gameStateService.getInterfaceMode()).thenReturn(GameStateService.InterfaceMode.RESIZABLE_MODERN);
        int[] modernIds = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_EQUIPMENT);
        assertEquals("Resizable modern equipment group", 164, modernIds[0]);
        assertEquals("Resizable modern equipment child", 56, modernIds[1]);
    }

    @Test
    public void getTabWidgetIds_CombatTab_FixedMode() {
        when(gameStateService.getInterfaceMode()).thenReturn(GameStateService.InterfaceMode.FIXED);

        int[] ids = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_COMBAT);

        assertEquals("Combat tab group", 548, ids[0]);
        assertEquals("Combat tab child (first tab)", 64, ids[1]);
    }

    @Test
    public void getTabWidgetIds_PrayerTab_FixedMode() {
        when(gameStateService.getInterfaceMode()).thenReturn(GameStateService.InterfaceMode.FIXED);

        int[] ids = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_PRAYER);

        assertEquals("Prayer tab group", 548, ids[0]);
        assertEquals("Prayer tab child", 69, ids[1]);
    }

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    @Test
    public void shouldUseHotkey_MultipleCallsWithDifferentForceClick_ReturnsCorrectly() {
        // Test that forceClick state doesn't leak between calls
        when(playerProfile.isLoaded()).thenReturn(true);
        when(profileData.isPrefersHotkeys()).thenReturn(true);

        // First call with forceClick=true
        assertFalse(WidgetInteractionHelpers.shouldUseHotkey(ctx, true));

        // Second call with forceClick=null should use profile
        assertTrue(WidgetInteractionHelpers.shouldUseHotkey(ctx, null));

        // Third call with forceClick=false
        assertTrue(WidgetInteractionHelpers.shouldUseHotkey(ctx, false));
    }

    @Test
    public void getTabWidgetIds_ReturnsNewArrayEachCall() {
        // Ensure we're not returning a shared mutable array
        when(gameStateService.getInterfaceMode()).thenReturn(GameStateService.InterfaceMode.FIXED);

        int[] ids1 = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_SPELLBOOK);
        int[] ids2 = WidgetInteractionHelpers.getTabWidgetIds(ctx, WidgetInteractionHelpers.TAB_SPELLBOOK);

        assertNotSame("Should return new array instances", ids1, ids2);
        assertArrayEquals("But arrays should have same content", ids1, ids2);
    }

    @Test
    public void getTabWidgetIds_AllTabIndicesValid_FixedMode() {
        // Verify all 14 tab indices return valid widget IDs without throwing
        when(gameStateService.getInterfaceMode()).thenReturn(GameStateService.InterfaceMode.FIXED);

        for (int i = 0; i <= WidgetInteractionHelpers.TAB_MUSIC; i++) {
            int[] ids = WidgetInteractionHelpers.getTabWidgetIds(ctx, i);
            assertNotNull("Tab " + i + " should return non-null", ids);
            assertEquals("Tab " + i + " should return 2-element array", 2, ids.length);
            assertTrue("Tab " + i + " group should be positive", ids[0] > 0);
            assertTrue("Tab " + i + " child should be positive", ids[1] > 0);
        }
    }
}

