package com.rocinante.combat;

import com.rocinante.core.GameStateService;
import com.rocinante.progression.PrayerUnlock;
import com.rocinante.progression.UnlockTracker;
import com.rocinante.state.AggressorInfo;
import com.rocinante.state.CombatState;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
// Use explicit import to avoid conflict with com.rocinante.combat.AttackStyle
import com.rocinante.state.AttackStyle;
import net.runelite.api.Client;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

/**
 * Unit tests for PrayerFlicker class.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class PrayerFlickerTest {

    @Mock
    private Client client;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private UnlockTracker unlockTracker;

    private PrayerFlicker prayerFlicker;

    @Before
    public void setUp() {
        prayerFlicker = new PrayerFlicker(client, gameStateService, unlockTracker);
        prayerFlicker.setConfig(PrayerConfig.DEFAULT);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private PlayerState createValidPlayerState(int currentPrayer, int maxPrayer) {
        return PlayerState.builder()
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .currentHitpoints(99)
                .maxHitpoints(99)
                .currentPrayer(currentPrayer)
                .maxPrayer(maxPrayer)
                .build();
    }

    private CombatState createCombatStateWithIncomingAttack(AttackStyle style, int ticksUntil) {
        return CombatState.builder()
                .targetPresent(true)
                .incomingAttackStyle(style)
                .ticksUntilAttackLands(ticksUntil)
                .aggressiveNpcs(List.of(AggressorInfo.builder()
                        .npcIndex(1)
                        .attackStyle(style.getId()) // Convert enum to int
                        .ticksUntilNextAttack(ticksUntil)
                        .expectedMaxHit(10)
                        .build()))
                .build();
    }

    private CombatState createIdleCombatState() {
        return CombatState.builder()
                .targetPresent(false)
                .incomingAttackStyle(AttackStyle.UNKNOWN)
                .ticksUntilAttackLands(-1)
                .aggressiveNpcs(Collections.emptyList())
                .build();
    }

    private InventoryState createInventoryWithRestore() {
        // Return mock inventory with prayer potion
        InventoryState inventory = mock(InventoryState.class);
        when(inventory.getSlotOf(2434)).thenReturn(0); // Prayer potion (4)
        return inventory;
    }

    private InventoryState createEmptyInventory() {
        InventoryState inventory = mock(InventoryState.class, withSettings().defaultAnswer(inv -> {
            // Return -1 for any int method (like getSlotOf) to indicate "not found"
            if (inv.getMethod().getReturnType() == int.class) {
                return -1;
            }
            return null;
        }));
        return inventory;
    }

    // ========================================================================
    // FlickMode Tests
    // ========================================================================

    @Test
    public void testFlickMode_PerfectTiming() {
        FlickMode mode = FlickMode.PERFECT;

        assertEquals(0, mode.getActivationTickOffset());
        assertEquals(1, mode.getActiveDurationTicks());
        assertTrue(mode.isFlicking());
        assertFalse(mode.isAlwaysOn());
    }

    @Test
    public void testFlickMode_LazyTiming() {
        FlickMode mode = FlickMode.LAZY;

        assertEquals(-1, mode.getActivationTickOffset());
        assertEquals(2, mode.getActiveDurationTicks());
        assertTrue(mode.isFlicking());
        assertFalse(mode.isAlwaysOn());
    }

    @Test
    public void testFlickMode_AlwaysOn() {
        FlickMode mode = FlickMode.ALWAYS_ON;

        assertFalse(mode.isFlicking());
        assertTrue(mode.isAlwaysOn());
        assertEquals(-1, mode.getActiveDurationTicks()); // Never deactivate
    }

    @Test
    public void testFlickMode_GetActivationTick() {
        // Attack lands at tick 100
        int attackTick = 100;

        assertEquals(100, FlickMode.PERFECT.getActivationTick(attackTick)); // On attack tick
        assertEquals(99, FlickMode.LAZY.getActivationTick(attackTick)); // 1 tick before
        assertEquals(0, FlickMode.ALWAYS_ON.getActivationTick(attackTick)); // Immediately
    }

    @Test
    public void testFlickMode_GetDeactivationTick() {
        int activationTick = 100;

        assertEquals(101, FlickMode.PERFECT.getDeactivationTick(activationTick)); // 1 tick after
        assertEquals(102, FlickMode.LAZY.getDeactivationTick(activationTick)); // 2 ticks after
        assertEquals(-1, FlickMode.ALWAYS_ON.getDeactivationTick(activationTick)); // Never
    }

    // ========================================================================
    // PrayerConfig Tests
    // ========================================================================

    @Test
    public void testPrayerConfig_Default() {
        PrayerConfig config = PrayerConfig.DEFAULT;

        assertTrue(config.isUseProtectionPrayers());
        assertEquals(FlickMode.LAZY, config.getFlickMode());
        assertEquals(0.03, config.getMissedFlickProbability(), 0.001);
        assertFalse(config.isUseOffensivePrayers());
    }

    @Test
    public void testPrayerConfig_IronmanConserve() {
        PrayerConfig config = PrayerConfig.IRONMAN_CONSERVE;

        assertTrue(config.isUseProtectionPrayers());
        assertEquals(FlickMode.PERFECT, config.getFlickMode());
        assertEquals(0.05, config.getMissedFlickProbability(), 0.001);
    }

    @Test
    public void testPrayerConfig_SafeMode() {
        PrayerConfig config = PrayerConfig.SAFE_MODE;

        assertTrue(config.isUseProtectionPrayers());
        assertEquals(FlickMode.ALWAYS_ON, config.getFlickMode());
        assertEquals(0.0, config.getMissedFlickProbability(), 0.001);
        assertTrue(config.isUseOffensivePrayers());
    }

    @Test
    public void testPrayerConfig_IsFlickingEnabled() {
        assertTrue(PrayerConfig.DEFAULT.isFlickingEnabled()); // LAZY mode
        assertTrue(PrayerConfig.IRONMAN_CONSERVE.isFlickingEnabled()); // PERFECT mode
        assertFalse(PrayerConfig.SAFE_MODE.isFlickingEnabled()); // ALWAYS_ON mode
    }

    @Test
    public void testPrayerConfig_EffectiveMissProbability() {
        PrayerConfig config = PrayerConfig.builder()
                .flickMode(FlickMode.LAZY)
                .missedFlickProbability(0.02)
                .build();

        // Should use max of config value and mode base value
        double effective = config.getEffectiveMissProbability();
        assertEquals(0.03, effective, 0.001); // LAZY mode base is 0.03
    }

    // ========================================================================
    // Prayer Availability Tests
    // ========================================================================

    @Test
    public void testCheckAndFlick_DisabledWhenPrayerLevelTooLow() {
        when(unlockTracker.hasAnyProtectionPrayer()).thenReturn(false);
        when(unlockTracker.getSkillLevel(Skill.PRAYER)).thenReturn(30);

        PlayerState player = createValidPlayerState(50, 50);
        CombatState combat = createCombatStateWithIncomingAttack(AttackStyle.MELEE, 2);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getCombatState()).thenReturn(combat);
        when(gameStateService.getInventoryState()).thenReturn(createEmptyInventory());

        CombatAction action = prayerFlicker.checkAndFlick(0);

        assertNull(action);
        assertFalse(prayerFlicker.isPrayerAvailable());
        assertNotNull(prayerFlicker.getPrayerUnavailableReason());
        assertTrue(prayerFlicker.getPrayerUnavailableReason().contains("30"));
    }

    @Test
    public void testCanProtectAgainst() {
        when(unlockTracker.getBestProtectionPrayer(AttackStyle.MELEE))
                .thenReturn(PrayerUnlock.PROTECT_FROM_MELEE);
        when(unlockTracker.getBestProtectionPrayer(AttackStyle.RANGED))
                .thenReturn(null); // Not unlocked

        assertTrue(prayerFlicker.canProtectAgainst(AttackStyle.MELEE));
        assertFalse(prayerFlicker.canProtectAgainst(AttackStyle.RANGED));
    }

    // ========================================================================
    // Protection Prayer Tests
    // ========================================================================

    @Test
    public void testCheckAndFlick_SwitchesPrayerOnIncomingAttack() {
        when(unlockTracker.hasAnyProtectionPrayer()).thenReturn(true);
        when(unlockTracker.getBestProtectionPrayer(AttackStyle.MELEE))
                .thenReturn(PrayerUnlock.PROTECT_FROM_MELEE);

        PlayerState player = createValidPlayerState(50, 50);
        CombatState combat = createCombatStateWithIncomingAttack(AttackStyle.MELEE, 1);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getCombatState()).thenReturn(combat);
        when(gameStateService.getInventoryState()).thenReturn(createEmptyInventory());

        // Set miss probability to 0 for deterministic testing
        prayerFlicker.setConfig(PrayerConfig.builder()
                .useProtectionPrayers(true)
                .flickMode(FlickMode.LAZY)
                .missedFlickProbability(0.0)
                .build());

        CombatAction action = prayerFlicker.checkAndFlick(0);

        assertNotNull(action);
        assertEquals(CombatAction.Type.PRAYER_SWITCH, action.getType());
        assertEquals(AttackStyle.MELEE, action.getAttackStyle());
    }

    @Test
    public void testCheckAndFlick_NullWhenNoPrayerUnlocked() {
        when(unlockTracker.hasAnyProtectionPrayer()).thenReturn(true);
        when(unlockTracker.getBestProtectionPrayer(AttackStyle.MAGIC)).thenReturn(null);

        PlayerState player = createValidPlayerState(50, 50);
        CombatState combat = createCombatStateWithIncomingAttack(AttackStyle.MAGIC, 1);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getCombatState()).thenReturn(combat);
        when(gameStateService.getInventoryState()).thenReturn(createEmptyInventory());

        CombatAction action = prayerFlicker.checkAndFlick(0);

        assertNull(action);
    }

    @Test
    public void testCheckAndFlick_DisabledInConfig() {
        prayerFlicker.setConfig(PrayerConfig.builder()
                .useProtectionPrayers(false)
                .build());

        PlayerState player = createValidPlayerState(50, 50);
        CombatState combat = createCombatStateWithIncomingAttack(AttackStyle.MELEE, 1);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getCombatState()).thenReturn(combat);

        CombatAction action = prayerFlicker.checkAndFlick(0);

        assertNull(action);
    }

    // ========================================================================
    // Prayer Point Management Tests
    // ========================================================================

    @Test
    public void testCheckAndFlick_RestoresPrayerWhenLow() {
        when(unlockTracker.hasAnyProtectionPrayer()).thenReturn(true);

        // 10% prayer = below 20% restore threshold
        PlayerState player = createValidPlayerState(5, 50);
        CombatState combat = createIdleCombatState();
        InventoryState inventory = createInventoryWithRestore();

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getCombatState()).thenReturn(combat);
        when(gameStateService.getInventoryState()).thenReturn(inventory);
        when(inventory.getItemInSlot(0)).thenReturn(java.util.Optional.of(mock(net.runelite.api.Item.class)));

        CombatAction action = prayerFlicker.checkAndFlick(0);

        assertNotNull(action);
        assertEquals(CombatAction.Type.DRINK_POTION, action.getType());
    }

    @Test
    public void testCheckAndFlick_DisablesPrayerWhenCriticallyLow() {
        when(unlockTracker.hasAnyProtectionPrayer()).thenReturn(true);

        // 5% prayer = below 10% critical threshold
        PlayerState player = createValidPlayerState(2, 50);
        CombatState combat = createIdleCombatState();
        InventoryState inventory = createEmptyInventory();

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getCombatState()).thenReturn(combat);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        CombatAction action = prayerFlicker.checkAndFlick(0);

        assertNotNull(action);
        assertEquals(CombatAction.Type.PRAYER_SWITCH, action.getType());
        assertEquals(AttackStyle.UNKNOWN, action.getAttackStyle()); // Indicates deactivation
    }

    // ========================================================================
    // Statistics Tests
    // ========================================================================

    @Test
    public void testFlickStatistics() {
        assertEquals(0, prayerFlicker.getSuccessfulFlicks());
        assertEquals(0, prayerFlicker.getMissedFlicks());
        assertEquals(100.0, prayerFlicker.getFlickSuccessRate(), 0.001);

        // Simulate some flicks by directly manipulating state
        // In a real test, we'd use reflection or make the fields package-private
        // For now, just test the getFlickStats method format
        String stats = prayerFlicker.getFlickStats();
        assertTrue(stats.contains("Flicks:"));
        assertTrue(stats.contains("successful"));
        assertTrue(stats.contains("missed"));
    }

    // ========================================================================
    // Reset Tests
    // ========================================================================

    @Test
    public void testReset() {
        // Setup some state
        prayerFlicker.setConfig(PrayerConfig.builder()
                .useProtectionPrayers(true)
                .flickMode(FlickMode.LAZY)
                .missedFlickProbability(0.0)
                .build());

        when(unlockTracker.hasAnyProtectionPrayer()).thenReturn(true);
        when(unlockTracker.getBestProtectionPrayer(AttackStyle.MELEE))
                .thenReturn(PrayerUnlock.PROTECT_FROM_MELEE);

        PlayerState player = createValidPlayerState(50, 50);
        CombatState combat = createCombatStateWithIncomingAttack(AttackStyle.MELEE, 0);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getCombatState()).thenReturn(combat);
        when(gameStateService.getInventoryState()).thenReturn(createEmptyInventory());

        // Trigger a switch
        prayerFlicker.checkAndFlick(0);

        // Now reset
        prayerFlicker.reset();

        assertNull(prayerFlicker.getActiveProtectionPrayer());
        assertNull(prayerFlicker.getActiveOffensivePrayer());
        assertEquals(-1, prayerFlicker.getLastActivationTick());
        assertEquals(-1, prayerFlicker.getScheduledDeactivationTick());
        assertFalse(prayerFlicker.isFlicking());
    }

    @Test
    public void testResetStats() {
        prayerFlicker.resetStats();

        assertEquals(0, prayerFlicker.getSuccessfulFlicks());
        assertEquals(0, prayerFlicker.getMissedFlicks());
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testCheckAndFlick_NullWhenPlayerInvalid() {
        when(unlockTracker.hasAnyProtectionPrayer()).thenReturn(true);
        when(gameStateService.getPlayerState()).thenReturn(PlayerState.EMPTY);

        CombatAction action = prayerFlicker.checkAndFlick(0);

        assertNull(action);
    }

    @Test
    public void testCheckAndFlick_NullWhenNoIncomingAttack() {
        when(unlockTracker.hasAnyProtectionPrayer()).thenReturn(true);

        PlayerState player = createValidPlayerState(50, 50);
        CombatState combat = createIdleCombatState();

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getCombatState()).thenReturn(combat);
        when(gameStateService.getInventoryState()).thenReturn(createEmptyInventory());

        prayerFlicker.setConfig(PrayerConfig.builder()
                .useProtectionPrayers(true)
                .flickMode(FlickMode.LAZY)
                .build());

        CombatAction action = prayerFlicker.checkAndFlick(0);

        assertNull(action);
    }

    @Test
    public void testOnCombatEnd() {
        // Just verify it doesn't throw and clears state
        prayerFlicker.onCombatEnd();

        assertNull(prayerFlicker.getActiveProtectionPrayer());
        assertFalse(prayerFlicker.isFlicking());
    }
}

