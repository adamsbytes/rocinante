package com.rocinante.behavior.emergencies;

import com.rocinante.behavior.ActivityType;
import com.rocinante.behavior.BotActivityTracker;
import com.rocinante.state.AggressorInfo;
import com.rocinante.state.CombatState;
import com.rocinante.state.IronmanState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import net.runelite.api.Client;
import net.runelite.api.Player;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UnderAttackEmergency.
 * Tests triggering conditions and response type determination.
 */
public class UnderAttackEmergencyTest {

    @Mock
    private TaskContext taskContext;

    @Mock
    private BotActivityTracker activityTracker;

    @Mock
    private CombatState combatState;

    @Mock
    private PlayerState playerState;

    @Mock
    private IronmanState ironmanState;

    @Mock
    private Client client;

    @Mock
    private Player localPlayer;

    @Mock
    private Task mockResponseTask;

    private UnderAttackEmergency.ResponseTaskFactory mockFactory;
    private UnderAttackEmergency emergency;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Default activity: skilling (not combat)
        when(activityTracker.getCurrentActivity()).thenReturn(ActivityType.MEDIUM);

        // Default player state
        when(playerState.getCurrentHitpoints()).thenReturn(50);
        when(playerState.getMaxHitpoints()).thenReturn(50);

        // Default ironman state (normal account)
        when(ironmanState.isHardcore()).thenReturn(false);
        when(ironmanState.getFleeThresholdMultiplier()).thenReturn(1.0);
        when(ironmanState.getHcimSafetyLevel()).thenReturn(IronmanState.HCIMSafetyLevel.NORMAL);

        // Wire up task context
        when(taskContext.getCombatState()).thenReturn(combatState);
        when(taskContext.getPlayerState()).thenReturn(playerState);
        when(taskContext.getIronmanState()).thenReturn(ironmanState);
        when(taskContext.getClient()).thenReturn(client);
        when(client.getLocalPlayer()).thenReturn(localPlayer);
        when(localPlayer.getCombatLevel()).thenReturn(30);

        // Default combat state
        when(combatState.isBeingAttacked()).thenReturn(false);
        when(combatState.getAggressiveNpcs()).thenReturn(Collections.emptyList());

        mockFactory = (ctx, attacker, response) -> mockResponseTask;
        emergency = new UnderAttackEmergency(activityTracker, mockFactory);
    }

    // ========================================================================
    // Trigger Tests
    // ========================================================================

    @Test
    public void testIsTriggered_NotBeingAttacked_ReturnsFalse() {
        when(combatState.isBeingAttacked()).thenReturn(false);

        assertFalse(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_InCombatActivity_ReturnsFalse() {
        // CRITICAL (boss fight) is a combat activity type
        when(combatState.isBeingAttacked()).thenReturn(true);
        when(activityTracker.getCurrentActivity()).thenReturn(ActivityType.CRITICAL);
        
        AggressorInfo attacker = createMugger();
        when(combatState.getAggressiveNpcs()).thenReturn(List.of(attacker));

        // CRITICAL.isCombat() returns true
        assertFalse(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_NoAggressors_ReturnsFalse() {
        when(combatState.isBeingAttacked()).thenReturn(true);
        when(combatState.getAggressiveNpcs()).thenReturn(Collections.emptyList());

        assertFalse(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_BeingAttackedWhileSkilling_ReturnsTrue() {
        AggressorInfo attacker = createMugger();
        when(combatState.isBeingAttacked()).thenReturn(true);
        when(combatState.getAggressiveNpcs()).thenReturn(List.of(attacker));

        assertTrue(emergency.isTriggered(taskContext));
    }

    // ========================================================================
    // Response Type Tests - Normal Account
    // ========================================================================

    @Test
    public void testCreateResponseTask_NormalAccount_LowLevelAttacker_Fight() {
        AggressorInfo attacker = createMugger(); // Level 6
        when(combatState.isBeingAttacked()).thenReturn(true);
        when(combatState.getAggressiveNpcs()).thenReturn(List.of(attacker));
        when(localPlayer.getCombatLevel()).thenReturn(30);

        // Capture the response type
        final UnderAttackEmergency.ResponseType[] capturedResponse = new UnderAttackEmergency.ResponseType[1];
        UnderAttackEmergency.ResponseTaskFactory capturingFactory = (ctx, att, response) -> {
            capturedResponse[0] = response;
            return mockResponseTask;
        };

        UnderAttackEmergency testEmergency = new UnderAttackEmergency(activityTracker, capturingFactory);
        testEmergency.createResponseTask(taskContext);

        assertEquals(UnderAttackEmergency.ResponseType.FIGHT, capturedResponse[0]);
    }

    @Test
    public void testCreateResponseTask_NormalAccount_LowHealth_Flee() {
        AggressorInfo attacker = createMugger();
        when(combatState.isBeingAttacked()).thenReturn(true);
        when(combatState.getAggressiveNpcs()).thenReturn(List.of(attacker));
        when(playerState.getCurrentHitpoints()).thenReturn(10); // 20% HP
        when(playerState.getMaxHitpoints()).thenReturn(50);

        final UnderAttackEmergency.ResponseType[] capturedResponse = new UnderAttackEmergency.ResponseType[1];
        UnderAttackEmergency.ResponseTaskFactory capturingFactory = (ctx, att, response) -> {
            capturedResponse[0] = response;
            return mockResponseTask;
        };

        UnderAttackEmergency testEmergency = new UnderAttackEmergency(activityTracker, capturingFactory);
        testEmergency.createResponseTask(taskContext);

        assertEquals(UnderAttackEmergency.ResponseType.FLEE, capturedResponse[0]);
    }

    @Test
    public void testCreateResponseTask_NormalAccount_HighLevelAttacker_Flee() {
        AggressorInfo attacker = AggressorInfo.builder()
                .npcIndex(1)
                .npcId(456)
                .npcName("Dark Wizard")
                .combatLevel(50) // 20 levels higher than player
                .ticksUntilNextAttack(-1)
                .expectedMaxHit(10)
                .attackStyle(2) // Magic
                .attackSpeed(4)
                .lastAttackTick(-1)
                .isAttacking(true)
                .build();
        when(combatState.isBeingAttacked()).thenReturn(true);
        when(combatState.getAggressiveNpcs()).thenReturn(List.of(attacker));
        when(localPlayer.getCombatLevel()).thenReturn(30); // Level diff > 10

        final UnderAttackEmergency.ResponseType[] capturedResponse = new UnderAttackEmergency.ResponseType[1];
        UnderAttackEmergency.ResponseTaskFactory capturingFactory = (ctx, att, response) -> {
            capturedResponse[0] = response;
            return mockResponseTask;
        };

        UnderAttackEmergency testEmergency = new UnderAttackEmergency(activityTracker, capturingFactory);
        testEmergency.createResponseTask(taskContext);

        assertEquals(UnderAttackEmergency.ResponseType.FLEE, capturedResponse[0]);
    }

    // ========================================================================
    // Response Type Tests - HCIM Account
    // ========================================================================

    @Test
    public void testCreateResponseTask_HCIM_AlwaysFlee() {
        when(ironmanState.isHardcore()).thenReturn(true);
        when(ironmanState.getFleeThresholdMultiplier()).thenReturn(1.0);

        AggressorInfo attacker = createMugger(); // Even low level attacker
        when(combatState.isBeingAttacked()).thenReturn(true);
        when(combatState.getAggressiveNpcs()).thenReturn(List.of(attacker));
        when(localPlayer.getCombatLevel()).thenReturn(100); // High level player

        final UnderAttackEmergency.ResponseType[] capturedResponse = new UnderAttackEmergency.ResponseType[1];
        UnderAttackEmergency.ResponseTaskFactory capturingFactory = (ctx, att, response) -> {
            capturedResponse[0] = response;
            return mockResponseTask;
        };

        UnderAttackEmergency testEmergency = new UnderAttackEmergency(activityTracker, capturingFactory);
        testEmergency.createResponseTask(taskContext);

        assertEquals(UnderAttackEmergency.ResponseType.FLEE, capturedResponse[0]);
    }

    // ========================================================================
    // Death Range Tests
    // ========================================================================

    @Test
    public void testCreateResponseTask_InDeathRange_Flee() {
        AggressorInfo attacker = AggressorInfo.builder()
                .npcIndex(1)
                .npcId(456)
                .npcName("Guard")
                .combatLevel(21)
                .ticksUntilNextAttack(2)
                .expectedMaxHit(5) // Known max hit
                .attackStyle(0)
                .attackSpeed(4)
                .lastAttackTick(10)
                .isAttacking(true)
                .build();
        when(combatState.isBeingAttacked()).thenReturn(true);
        when(combatState.getAggressiveNpcs()).thenReturn(List.of(attacker));
        when(playerState.getCurrentHitpoints()).thenReturn(6); // <= maxHit + buffer (5+3=8)
        when(playerState.getMaxHitpoints()).thenReturn(50);

        final UnderAttackEmergency.ResponseType[] capturedResponse = new UnderAttackEmergency.ResponseType[1];
        UnderAttackEmergency.ResponseTaskFactory capturingFactory = (ctx, att, response) -> {
            capturedResponse[0] = response;
            return mockResponseTask;
        };

        UnderAttackEmergency testEmergency = new UnderAttackEmergency(activityTracker, capturingFactory);
        testEmergency.createResponseTask(taskContext);

        assertEquals(UnderAttackEmergency.ResponseType.FLEE, capturedResponse[0]);
    }

    // ========================================================================
    // Config Tests
    // ========================================================================

    @Test
    public void testConfig_AlwaysFlee_ReturnsFleeRegardless() {
        UnderAttackEmergency.Config config = UnderAttackEmergency.Config.builder()
                .alwaysFlee(true)
                .build();

        AggressorInfo attacker = createMugger();
        when(combatState.isBeingAttacked()).thenReturn(true);
        when(combatState.getAggressiveNpcs()).thenReturn(List.of(attacker));

        final UnderAttackEmergency.ResponseType[] capturedResponse = new UnderAttackEmergency.ResponseType[1];
        UnderAttackEmergency.ResponseTaskFactory capturingFactory = (ctx, att, response) -> {
            capturedResponse[0] = response;
            return mockResponseTask;
        };

        UnderAttackEmergency testEmergency = new UnderAttackEmergency(activityTracker, capturingFactory, config);
        testEmergency.createResponseTask(taskContext);

        assertEquals(UnderAttackEmergency.ResponseType.FLEE, capturedResponse[0]);
    }

    @Test
    public void testConfig_FightBackDisabled_ReturnsIgnore() {
        UnderAttackEmergency.Config config = UnderAttackEmergency.Config.builder()
                .fightBack(false)
                .fleeIfCantFight(false)
                .build();

        AggressorInfo attacker = createMugger();
        when(combatState.isBeingAttacked()).thenReturn(true);
        when(combatState.getAggressiveNpcs()).thenReturn(List.of(attacker));

        final UnderAttackEmergency.ResponseType[] capturedResponse = new UnderAttackEmergency.ResponseType[1];
        UnderAttackEmergency.ResponseTaskFactory capturingFactory = (ctx, att, response) -> {
            capturedResponse[0] = response;
            return mockResponseTask;
        };

        UnderAttackEmergency testEmergency = new UnderAttackEmergency(activityTracker, capturingFactory, config);
        testEmergency.createResponseTask(taskContext);

        assertEquals(UnderAttackEmergency.ResponseType.IGNORE, capturedResponse[0]);
    }

    @Test
    public void testConfig_MaxNpcLevelToFight_FleeIfExceeded() {
        UnderAttackEmergency.Config config = UnderAttackEmergency.Config.builder()
                .maxNpcLevelToFight(5)
                .build();

        AggressorInfo attacker = createMugger(); // Level 6
        when(combatState.isBeingAttacked()).thenReturn(true);
        when(combatState.getAggressiveNpcs()).thenReturn(List.of(attacker));

        final UnderAttackEmergency.ResponseType[] capturedResponse = new UnderAttackEmergency.ResponseType[1];
        UnderAttackEmergency.ResponseTaskFactory capturingFactory = (ctx, att, response) -> {
            capturedResponse[0] = response;
            return mockResponseTask;
        };

        UnderAttackEmergency testEmergency = new UnderAttackEmergency(activityTracker, capturingFactory, config);
        testEmergency.createResponseTask(taskContext);

        assertEquals(UnderAttackEmergency.ResponseType.FLEE, capturedResponse[0]);
    }

    // ========================================================================
    // Metadata Tests
    // ========================================================================

    @Test
    public void testGetId_ReturnsCorrectId() {
        assertEquals("UNDER_ATTACK_EMERGENCY", emergency.getId());
    }

    @Test
    public void testGetDescription_ReturnsDescription() {
        assertNotNull(emergency.getDescription());
        assertTrue(emergency.getDescription().contains("attack"));
    }

    @Test
    public void testGetSeverity_IsReasonable() {
        int severity = emergency.getSeverity();
        assertTrue("Severity should be positive", severity > 0);
        assertTrue("Severity should be less than 100", severity < 100);
    }

    @Test
    public void testGetCooldownMs_IsPositive() {
        assertTrue(emergency.getCooldownMs() > 0);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private AggressorInfo createMugger() {
        return AggressorInfo.builder()
                .npcIndex(1)
                .npcId(174)
                .npcName("Mugger")
                .combatLevel(6)
                .ticksUntilNextAttack(-1)
                .expectedMaxHit(2)
                .attackStyle(0) // Melee
                .attackSpeed(4)
                .lastAttackTick(-1)
                .isAttacking(true)
                .build();
    }
}
