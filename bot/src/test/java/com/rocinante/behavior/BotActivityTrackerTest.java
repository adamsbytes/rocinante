package com.rocinante.behavior;

import com.rocinante.state.CombatState;
import com.rocinante.state.IronmanState;
import com.rocinante.state.NpcSnapshot;
import com.rocinante.tasks.Task;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BotActivityTracker.
 * Tests activity detection, account type handling, and interruption logic.
 */
public class BotActivityTrackerTest {

    @Mock
    private Client client;
    
    @Mock
    private Supplier<CombatState> combatStateSupplier;
    
    @Mock
    private Supplier<Task> currentTaskSupplier;
    
    @Mock
    private IronmanState ironmanState;
    
    private BotActivityTracker activityTracker;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        when(client.getVarbitValue(anyInt())).thenReturn(0);
        when(combatStateSupplier.get()).thenReturn(CombatState.EMPTY);
        when(currentTaskSupplier.get()).thenReturn(null);
        when(ironmanState.getEffectiveType()).thenReturn(AccountType.NORMAL);
        
        activityTracker = new BotActivityTracker(client, combatStateSupplier, currentTaskSupplier, ironmanState);
    }

    // ========================================================================
    // Initialization Tests
    // ========================================================================

    @Test
    public void testInitialization_StartsIdle() {
        assertEquals(ActivityType.IDLE, activityTracker.getCurrentActivity());
        assertFalse(activityTracker.isInDangerousArea());
        assertFalse(activityTracker.isInBossFight());
        assertEquals(0, activityTracker.getWildernessLevel());
    }

    // ========================================================================
    // Account Type Detection Tests
    // ========================================================================

    @Test
    public void testAccountType_Normal() {
        when(ironmanState.getEffectiveType()).thenReturn(AccountType.NORMAL);
        
        activityTracker.tick();
        
        assertEquals(AccountType.NORMAL, activityTracker.getAccountType());
        assertFalse(activityTracker.getAccountType().isIronman());
        assertFalse(activityTracker.getAccountType().isHardcore());
    }

    @Test
    public void testAccountType_Ironman() {
        when(ironmanState.getEffectiveType()).thenReturn(AccountType.IRONMAN);
        
        activityTracker.tick();
        
        assertEquals(AccountType.IRONMAN, activityTracker.getAccountType());
        assertTrue(activityTracker.getAccountType().isIronman());
        assertFalse(activityTracker.getAccountType().isHardcore());
    }

    @Test
    public void testAccountType_HCIM() {
        when(ironmanState.getEffectiveType()).thenReturn(AccountType.HARDCORE_IRONMAN);
        
        activityTracker.tick();
        
        assertEquals(AccountType.HARDCORE_IRONMAN, activityTracker.getAccountType());
        assertTrue(activityTracker.getAccountType().isIronman());
        assertTrue(activityTracker.getAccountType().isHardcore());
    }

    // ========================================================================
    // Dangerous Area Detection Tests
    // ========================================================================

    @Test
    public void testDangerousArea_Wilderness() {
        when(client.getVarbitValue(5963)).thenReturn(20); // Level 20 wilderness
        
        activityTracker.tick();
        
        assertTrue(activityTracker.isInDangerousArea());
        assertEquals(20, activityTracker.getWildernessLevel());
    }

    @Test
    public void testDangerousArea_NotInWilderness() {
        when(client.getVarbitValue(5963)).thenReturn(0);
        
        activityTracker.tick();
        
        assertFalse(activityTracker.isInDangerousArea());
        assertEquals(0, activityTracker.getWildernessLevel());
    }

    // ========================================================================
    // Boss Fight Detection Tests
    // ========================================================================

    @Test
    public void testBossFight_DetectsKnownBoss() {
        NpcSnapshot boss = NpcSnapshot.builder()
                .name("Zulrah")
                .index(100)
                .worldPosition(new WorldPoint(2268, 3069, 0))
                .build();
        
        CombatState combatState = CombatState.builder()
                .targetNpc(boss)
                .targetPresent(true)
                .build();
        
        when(combatStateSupplier.get()).thenReturn(combatState);
        
        activityTracker.tick();
        
        assertTrue("Zulrah should be detected as boss", activityTracker.isInBossFight());
    }

    @Test
    public void testBossFight_IgnoresNonBoss() {
        NpcSnapshot goblin = NpcSnapshot.builder()
                .name("Goblin")
                .index(100)
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .build();
        
        CombatState combatState = CombatState.builder()
                .targetNpc(goblin)
                .targetPresent(true)
                .build();
        
        when(combatStateSupplier.get()).thenReturn(combatState);
        
        activityTracker.tick();
        
        assertFalse("Goblin should not be detected as boss", activityTracker.isInBossFight());
    }

    @Test
    public void testIsBoss_RecognizesCommonBosses() {
        assertTrue(BotActivityTracker.isBoss("Zulrah"));
        assertTrue(BotActivityTracker.isBoss("Vorkath"));
        assertTrue(BotActivityTracker.isBoss("General Graardor"));
        assertTrue(BotActivityTracker.isBoss("TzTok-Jad"));
        
        assertFalse(BotActivityTracker.isBoss("Cow"));
        assertFalse(BotActivityTracker.isBoss("Goblin"));
        assertFalse(BotActivityTracker.isBoss(null));
    }

    // ========================================================================
    // Activity Type Detection Tests
    // ========================================================================

    @Test
    public void testActivity_Idle_WhenNoTaskAndNoCombat() {
        when(combatStateSupplier.get()).thenReturn(CombatState.EMPTY);
        when(currentTaskSupplier.get()).thenReturn(null);
        
        activityTracker.tick();
        
        assertEquals(ActivityType.IDLE, activityTracker.getCurrentActivity());
    }

    @Test
    public void testActivity_Critical_WhenBossFight() {
        NpcSnapshot boss = NpcSnapshot.builder()
                .name("Vorkath")
                .index(100)
                .worldPosition(new WorldPoint(2268, 3069, 0))
                .build();
        
        CombatState combatState = CombatState.builder()
                .targetNpc(boss)
                .targetPresent(true)
                .build();
        
        when(combatStateSupplier.get()).thenReturn(combatState);
        
        activityTracker.tick();
        
        assertEquals(ActivityType.CRITICAL, activityTracker.getCurrentActivity());
    }

    @Test
    public void testActivity_Critical_WhenWildernessCombat() {
        when(client.getVarbitValue(5963)).thenReturn(10); // Wilderness
        
        CombatState combatState = CombatState.builder()
                .targetPresent(true)
                .build();
        
        when(combatStateSupplier.get()).thenReturn(combatState);
        
        activityTracker.tick();
        
        assertEquals(ActivityType.CRITICAL, activityTracker.getCurrentActivity());
    }

    @Test
    public void testActivity_High_WhenInCombat() {
        CombatState combatState = CombatState.builder()
                .targetPresent(true)
                .build();
        
        when(combatStateSupplier.get()).thenReturn(combatState);
        
        activityTracker.tick();
        
        assertEquals(ActivityType.HIGH, activityTracker.getCurrentActivity());
    }

    @Test
    public void testActivity_Medium_WhenDoingTask() {
        when(currentTaskSupplier.get()).thenReturn(mock(Task.class));
        
        activityTracker.tick();
        
        assertEquals(ActivityType.MEDIUM, activityTracker.getCurrentActivity());
    }

    // ========================================================================
    // Explicit Activity Tests
    // ========================================================================

    @Test
    public void testSetExplicitActivity_OverridesDetection() {
        when(combatStateSupplier.get()).thenReturn(CombatState.EMPTY);
        
        activityTracker.setExplicitActivity(ActivityType.AFK_COMBAT);
        activityTracker.tick();
        
        assertEquals(ActivityType.AFK_COMBAT, activityTracker.getCurrentActivity());
    }

    @Test
    public void testClearExplicitActivity_ReturnsToDetection() {
        activityTracker.setExplicitActivity(ActivityType.AFK_COMBAT);
        activityTracker.clearExplicitActivity();
        activityTracker.tick();
        
        // Should return to IDLE (no task, no combat)
        assertEquals(ActivityType.IDLE, activityTracker.getCurrentActivity());
    }

    // ========================================================================
    // Interruption Logic Tests
    // ========================================================================

    @Test
    public void testCanInterrupt_True_ForMediumActivity() {
        activityTracker.setExplicitActivity(ActivityType.MEDIUM);
        
        assertTrue(activityTracker.canInterrupt());
    }

    @Test
    public void testCanInterrupt_False_ForCriticalActivity() {
        activityTracker.setExplicitActivity(ActivityType.CRITICAL);
        activityTracker.tick();
        
        assertFalse(activityTracker.canInterrupt());
    }

    @Test
    public void testCanInterrupt_HCIM_False_ForAnyCombat() {
        when(ironmanState.getEffectiveType()).thenReturn(AccountType.HARDCORE_IRONMAN);
        activityTracker.tick();
        
        activityTracker.setExplicitActivity(ActivityType.HIGH);
        activityTracker.tick();
        
        assertFalse("HCIM should not be interruptible during any combat", 
                   activityTracker.canInterrupt());
    }

    @Test
    public void testCanEnterAFK_True_ForLowActivity() {
        activityTracker.setExplicitActivity(ActivityType.LOW);
        
        assertTrue(activityTracker.canEnterAFK());
    }

    @Test
    public void testCanEnterAFK_False_ForCriticalActivity() {
        activityTracker.setExplicitActivity(ActivityType.CRITICAL);
        activityTracker.tick();
        
        assertFalse(activityTracker.canEnterAFK());
    }

    @Test
    public void testCanEnterAFK_HCIM_False_ForCombat() {
        when(ironmanState.getEffectiveType()).thenReturn(AccountType.HARDCORE_IRONMAN);
        activityTracker.tick();
        
        activityTracker.setExplicitActivity(ActivityType.HIGH);
        activityTracker.tick();
        
        assertFalse("HCIM should never AFK during combat", activityTracker.canEnterAFK());
    }

    @Test
    public void testCanTakeBreak_True_WhenInterruptible() {
        activityTracker.setExplicitActivity(ActivityType.MEDIUM);
        
        assertTrue(activityTracker.canTakeBreak());
    }

    @Test
    public void testCanTakeBreak_False_WhenNotInterruptible() {
        activityTracker.setExplicitActivity(ActivityType.CRITICAL);
        activityTracker.tick();
        
        assertFalse(activityTracker.canTakeBreak());
    }

    // ========================================================================
    // Summary Tests
    // ========================================================================

    @Test
    public void testGetSummary_ContainsKeyInfo() {
        activityTracker.tick();
        
        String summary = activityTracker.getSummary();
        
        assertTrue(summary.contains("activity="));
        assertTrue(summary.contains("account="));
        assertTrue(summary.contains("dangerous="));
        assertTrue(summary.contains("boss="));
    }
}

