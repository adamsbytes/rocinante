package com.rocinante.tasks.impl;

import com.rocinante.core.GameStateService;
import com.rocinante.tasks.TaskTestHelper;
import com.rocinante.tasks.TaskTestHelper.MockResult;
import net.runelite.api.ItemID;
import org.junit.Test;

import java.awt.event.KeyEvent;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

/**
 * Tests for TravelTask focusing on real-world scenarios.
 * 
 * Uses TaskTestHelper.MockBuilder for clean, realistic mock setup.
 */
public class TravelTaskTest {

    // ========================================================================
    // Factory Method Tests
    // ========================================================================

    @Test
    public void homeTeleport_CreatesCorrectMethod() {
        TravelTask task = TravelTask.homeTeleport();
        assertEquals(TravelTask.TravelMethod.HOME_TELEPORT, task.getMethod());
    }

    @Test
    public void spell_CreatesCorrectMethodAndSpellName() {
        TravelTask task = TravelTask.spell("Varrock Teleport");
        assertEquals(TravelTask.TravelMethod.SPELL, task.getMethod());
        assertEquals("Varrock Teleport", task.getSpellName());
    }

    @Test
    public void withForceClick_SetsCorrectly() {
        assertTrue(TravelTask.homeTeleport().withForceClick(true).getForceClick());
        assertFalse(TravelTask.homeTeleport().withForceClick(false).getForceClick());
        assertNull(TravelTask.homeTeleport().withForceClick(null).getForceClick());
    }

    // ========================================================================
    // Tutorial Island: forceClick=true MUST click, never use hotkey
    // This is the critical scenario - Tutorial Island has no hotkeys
    // ========================================================================

    @Test
    public void homeTeleport_ForceClickTrue_ClicksSpellbookTab() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withHotkeyPreference()  // Would normally use hotkey
                .withSpellbookClosed()
                .build();
        
        TravelTask task = TravelTask.homeTeleport().withForceClick(true);
        
        TaskTestHelper.advanceUntil(task, mocks.ctx, 10, () -> mocks.tracker.widgetClicked);
        
        assertTrue("forceClick=true must click, not use hotkey", mocks.tracker.usedClick());
        assertEquals("Should click spellbook tab (group 548)", 548, mocks.tracker.lastClickGroupId);
        assertEquals("Should click spellbook tab (child 70)", 70, mocks.tracker.lastClickChildId);
    }

    @Test
    public void homeTeleport_ForceClickTrue_IgnoresPlayerPreference() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withHotkeyPreference()  // Player WANTS hotkeys
                .withSpellbookClosed()
                .build();
        
        TravelTask task = TravelTask.homeTeleport().withForceClick(true);
        
        TaskTestHelper.advanceUntil(task, mocks.ctx, 10, () -> mocks.tracker.widgetClicked);
        
        // forceClick=true overrides player preference
        assertTrue("Must click despite hotkey preference", mocks.tracker.usedClick());
        assertFalse("Must NOT use keyboard with forceClick=true", mocks.tracker.keyPressed);
    }

    // ========================================================================
    // Normal Gameplay: Respecting Player Preferences
    // ========================================================================

    @Test
    public void homeTeleport_PlayerPrefersHotkeys_UsesF7() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withHotkeyPreference()
                .withSpellbookClosed()
                .build();
        
        TravelTask task = TravelTask.homeTeleport();  // No forceClick
        
        TaskTestHelper.advanceUntil(task, mocks.ctx, 10, () -> mocks.tracker.keyPressed);
        
        assertTrue("Should use hotkey when player prefers", mocks.tracker.usedHotkey());
        assertEquals("Should press F7 for spellbook", KeyEvent.VK_F7, mocks.tracker.lastKeyCode);
    }

    @Test
    public void homeTeleport_PlayerPrefersClicks_ClicksTab() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withClickPreference()
                .withSpellbookClosed()
                .build();
        
        TravelTask task = TravelTask.homeTeleport();
        
        TaskTestHelper.advanceUntil(task, mocks.ctx, 10, () -> mocks.tracker.widgetClicked);
        
        assertTrue("Should click when player prefers", mocks.tracker.usedClick());
        assertEquals("Should click spellbook tab", 70, mocks.tracker.lastClickChildId);
    }

    @Test
    public void homeTeleport_NoProfileLoaded_DefaultsToHotkey() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withNoProfile()
                .withSpellbookClosed()
                .build();
        
        TravelTask task = TravelTask.homeTeleport();
        
        TaskTestHelper.advanceUntil(task, mocks.ctx, 10, () -> mocks.tracker.keyPressed);
        
        assertTrue("Should default to hotkey without profile", mocks.tracker.usedHotkey());
        assertEquals(KeyEvent.VK_F7, mocks.tracker.lastKeyCode);
    }

    // ========================================================================
    // forceClick=false: Force hotkey even if player prefers clicks
    // ========================================================================

    @Test
    public void homeTeleport_ForceClickFalse_ForcesHotkey() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withClickPreference()  // Player WANTS to click
                .withSpellbookClosed()
                .build();
        
        TravelTask task = TravelTask.homeTeleport().withForceClick(false);
        
        TaskTestHelper.advanceUntil(task, mocks.ctx, 10, () -> mocks.tracker.keyPressed);
        
        assertTrue("forceClick=false must use hotkey", mocks.tracker.usedHotkey());
        assertFalse("Must NOT click with forceClick=false", mocks.tracker.widgetClicked);
    }

    // ========================================================================
    // Interface Modes: Click targets vary by mode
    // ========================================================================

    @Test
    public void homeTeleport_FixedMode_ClicksCorrectWidget() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withInterfaceMode(GameStateService.InterfaceMode.FIXED)
                .withSpellbookClosed()
                .build();
        
        TravelTask task = TravelTask.homeTeleport().withForceClick(true);
        
        TaskTestHelper.advanceUntil(task, mocks.ctx, 10, () -> mocks.tracker.widgetClicked);
        
        assertEquals("Fixed mode uses group 548", 548, mocks.tracker.lastClickGroupId);
        assertEquals("Fixed mode spellbook is child 70", 70, mocks.tracker.lastClickChildId);
    }

    @Test
    public void homeTeleport_ResizableClassic_ClicksCorrectWidget() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withInterfaceMode(GameStateService.InterfaceMode.RESIZABLE_CLASSIC)
                .withSpellbookClosed()
                .build();
        
        TravelTask task = TravelTask.homeTeleport().withForceClick(true);
        
        TaskTestHelper.advanceUntil(task, mocks.ctx, 10, () -> mocks.tracker.widgetClicked);
        
        assertEquals("Resizable classic uses group 161", 161, mocks.tracker.lastClickGroupId);
        assertEquals("Resizable classic spellbook is child 65", 65, mocks.tracker.lastClickChildId);
    }

    @Test
    public void homeTeleport_ResizableModern_ClicksCorrectWidget() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withInterfaceMode(GameStateService.InterfaceMode.RESIZABLE_MODERN)
                .withSpellbookClosed()
                .build();
        
        TravelTask task = TravelTask.homeTeleport().withForceClick(true);
        
        TaskTestHelper.advanceUntil(task, mocks.ctx, 10, () -> mocks.tracker.widgetClicked);
        
        assertEquals("Resizable modern uses group 164", 164, mocks.tracker.lastClickGroupId);
        assertEquals("Resizable modern spellbook is child 58", 58, mocks.tracker.lastClickChildId);
    }

    @Test
    public void homeTeleport_UnknownMode_FallsBackToFixed() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withInterfaceMode(GameStateService.InterfaceMode.UNKNOWN)
                .withSpellbookClosed()
                .build();
        
        TravelTask task = TravelTask.homeTeleport().withForceClick(true);
        
        TaskTestHelper.advanceUntil(task, mocks.ctx, 10, () -> mocks.tracker.widgetClicked);
        
        assertEquals("Unknown mode falls back to fixed (548)", 548, mocks.tracker.lastClickGroupId);
        assertEquals("Unknown mode falls back to fixed child (70)", 70, mocks.tracker.lastClickChildId);
    }

    // ========================================================================
    // Tab Already Open: Skip opening
    // ========================================================================

    @Test
    public void homeTeleport_SpellbookAlreadyOpen_SkipsTabOpening() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withHotkeyPreference()
                .withSpellbookOpen()  // Already open!
                .build();
        
        TravelTask task = TravelTask.homeTeleport();
        
        TaskTestHelper.advanceTicks(task, mocks.ctx, 5);
        
        assertFalse("Should not press keyboard when already open", mocks.tracker.keyPressed);
        assertFalse("Should not click tab when already open", mocks.tracker.widgetClicked);
    }

    // ========================================================================
    // Equipment Tab: Jewelry Teleports
    // ========================================================================

    @Test
    public void jewelryTeleport_PlayerPrefersClicks_ClicksEquipmentTab() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withClickPreference()
                .withEquipmentClosed()
                .withEquippedJewelry(ItemID.RING_OF_WEALTH_5)
                .build();
        
        TravelTask task = TravelTask.jewelry(ItemID.RING_OF_WEALTH_5, "Grand Exchange");
        
        TaskTestHelper.advanceUntil(task, mocks.ctx, 10, () -> mocks.tracker.widgetClicked);
        
        assertTrue("Should click equipment tab", mocks.tracker.usedClick());
        assertEquals("Should click equipment (child 68)", 68, mocks.tracker.lastClickChildId);
    }

    @Test
    public void jewelryTeleport_PlayerPrefersHotkeys_UsesF5() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withHotkeyPreference()
                .withEquipmentClosed()
                .withEquippedJewelry(ItemID.RING_OF_WEALTH_5)
                .build();
        
        TravelTask task = TravelTask.jewelry(ItemID.RING_OF_WEALTH_5, "Grand Exchange");
        
        TaskTestHelper.advanceUntil(task, mocks.ctx, 10, () -> mocks.tracker.keyPressed);
        
        assertTrue("Should use F5 for equipment", mocks.tracker.usedHotkey());
        assertEquals(KeyEvent.VK_F5, mocks.tracker.lastKeyCode);
    }

    // ========================================================================
    // Inventory Tab: Tablet Teleports
    // ========================================================================

    @Test
    public void tabletTeleport_PlayerPrefersClicks_ClicksInventoryTab() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withClickPreference()
                .withInventoryClosed()                         // Tab needs opening
                .withInventoryContaining(ItemID.VARROCK_TELEPORT)  // Has the tablet
                .build();
        
        TravelTask task = TravelTask.tablet(ItemID.VARROCK_TELEPORT);
        
        TaskTestHelper.advanceUntil(task, mocks.ctx, 10, () -> mocks.tracker.widgetClicked);
        
        assertTrue("Should click inventory tab", mocks.tracker.usedClick());
        assertEquals("Should click inventory (child 67)", 67, mocks.tracker.lastClickChildId);
    }

    @Test
    public void tabletTeleport_PlayerPrefersHotkeys_UsesF4() {
        MockResult mocks = TaskTestHelper.mockBuilder()
                .withHotkeyPreference()
                .withInventoryClosed()
                .withInventoryContaining(ItemID.VARROCK_TELEPORT)
                .build();
        
        TravelTask task = TravelTask.tablet(ItemID.VARROCK_TELEPORT);
        
        TaskTestHelper.advanceUntil(task, mocks.ctx, 10, () -> mocks.tracker.keyPressed);
        
        assertTrue("Should use F4 for inventory", mocks.tracker.usedHotkey());
        assertEquals(KeyEvent.VK_F4, mocks.tracker.lastKeyCode);
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void canExecute_ReturnsTrue_WhenLoggedIn() {
        MockResult mocks = TaskTestHelper.mockBuilder().build();
        TravelTask task = TravelTask.homeTeleport();
        assertTrue(task.canExecute(mocks.ctx));
    }

    @Test
    public void canExecute_ReturnsFalse_WhenNotLoggedIn() {
        MockResult mocks = TaskTestHelper.mockBuilder().build();
        when(mocks.ctx.isLoggedIn()).thenReturn(false);
        
        TravelTask task = TravelTask.homeTeleport();
        assertFalse(task.canExecute(mocks.ctx));
    }
}
