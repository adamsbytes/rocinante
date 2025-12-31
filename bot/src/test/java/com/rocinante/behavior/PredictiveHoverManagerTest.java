package com.rocinante.behavior;

import com.rocinante.input.RobotMouseController;
import com.rocinante.navigation.NavigationService;
import com.rocinante.state.GameObjectSnapshot;
import com.rocinante.state.NpcSnapshot;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.awt.Rectangle;
import java.awt.Shape;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PredictiveHoverManager.
 * 
 * Tests cover:
 * - Prediction rate calculation with fatigue and attention modulation
 * - Click behavior distribution by attention state
 * - NPC target validation and re-acquisition
 * - Integration with PlayerProfile base rates
 * - Edge cases (no targets, all targets at same position)
 * - Metrics tracking
 */
public class PredictiveHoverManagerTest {

    @Mock
    private PlayerProfile playerProfile;

    @Mock
    private FatigueModel fatigueModel;

    @Mock
    private AttentionModel attentionModel;

    @Mock
    private TaskContext taskContext;

    @Mock
    private Client client;

    @Mock
    private RobotMouseController mouseController;

    @Mock
    private HumanTimer humanTimer;

    @Mock
    private WorldState worldState;

    @Mock
    private PlayerState playerState;

    @Mock
    private NavigationService navigationService;

    private Randomization randomization;
    private PredictiveHoverManager hoverManager;

    private static final WorldPoint PLAYER_POS = new WorldPoint(3200, 3200, 0);
    private static final WorldPoint TARGET_POS = new WorldPoint(3202, 3202, 0);
    private static final WorldPoint TARGET_POS_2 = new WorldPoint(3205, 3205, 0);
    private static final int TEST_NPC_ID = 1234;
    private static final int TEST_OBJECT_ID = 5678;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Use a seeded randomization for predictable tests
        randomization = new Randomization(12345L);

        // Setup default profile values
        when(playerProfile.getBasePredictionRate()).thenReturn(0.70);
        when(playerProfile.getPredictionClickSpeedBias()).thenReturn(0.50);

        // Setup default fatigue and attention
        when(fatigueModel.getFatigueLevel()).thenReturn(0.0);
        when(attentionModel.getCurrentState()).thenReturn(AttentionState.FOCUSED);

        // Setup task context
        when(taskContext.getClient()).thenReturn(client);
        when(taskContext.getMouseController()).thenReturn(mouseController);
        when(taskContext.getHumanTimer()).thenReturn(humanTimer);
        when(taskContext.getWorldState()).thenReturn(worldState);
        when(taskContext.getPlayerState()).thenReturn(playerState);
        when(taskContext.getRandomization()).thenReturn(randomization);

        // Setup player state
        when(playerState.getWorldPosition()).thenReturn(PLAYER_POS);

        // Setup mouse controller to return successful futures
        when(mouseController.moveToCanvas(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mouseController.click())
                .thenReturn(CompletableFuture.completedFuture(null));

        // Create the manager
        hoverManager = new PredictiveHoverManager(playerProfile, fatigueModel, attentionModel, randomization, navigationService);
    }

    // ========================================================================
    // Initialization Tests
    // ========================================================================

    @Test
    public void testInitialization_NoHoverPending() {
        assertFalse(hoverManager.hasPendingHover());
        assertNull(hoverManager.getCurrentHover());
    }

    @Test
    public void testInitialization_MetricsAtZero() {
        assertEquals(0, hoverManager.getHoverAttemptsTotal());
        assertEquals(0, hoverManager.getHoverSuccessCount());
        assertEquals(0, hoverManager.getInstantClickCount());
        assertEquals(0, hoverManager.getDelayedClickCount());
        assertEquals(0, hoverManager.getAbandonedHoverCount());
    }

    // ========================================================================
    // Prediction Rate Calculation Tests
    // ========================================================================

    @Test
    public void testEffectivePredictionRate_BaseRateOnly() {
        // No fatigue, FOCUSED attention
        when(fatigueModel.getFatigueLevel()).thenReturn(0.0);
        when(attentionModel.getCurrentState()).thenReturn(AttentionState.FOCUSED);
        when(playerProfile.getBasePredictionRate()).thenReturn(0.80);

        double rate = hoverManager.calculateEffectivePredictionRate();

        // Should be close to base rate
        assertEquals(0.80, rate, 0.01);
    }

    @Test
    public void testEffectivePredictionRate_WithFatigue() {
        // 50% fatigue should reduce rate by 25% (fatigue impact factor is 0.5)
        when(fatigueModel.getFatigueLevel()).thenReturn(0.50);
        when(attentionModel.getCurrentState()).thenReturn(AttentionState.FOCUSED);
        when(playerProfile.getBasePredictionRate()).thenReturn(0.80);

        double rate = hoverManager.calculateEffectivePredictionRate();

        // 0.80 * (1.0 - 0.50 * 0.50) = 0.80 * 0.75 = 0.60
        assertEquals(0.60, rate, 0.01);
    }

    @Test
    public void testEffectivePredictionRate_MaxFatigue() {
        // Max fatigue should reduce by 50%
        when(fatigueModel.getFatigueLevel()).thenReturn(1.0);
        when(attentionModel.getCurrentState()).thenReturn(AttentionState.FOCUSED);
        when(playerProfile.getBasePredictionRate()).thenReturn(0.80);

        double rate = hoverManager.calculateEffectivePredictionRate();

        // 0.80 * (1.0 - 1.0 * 0.50) = 0.80 * 0.50 = 0.40
        assertEquals(0.40, rate, 0.01);
    }

    @Test
    public void testEffectivePredictionRate_DistractedState() {
        // DISTRACTED multiplies by 0.40
        when(fatigueModel.getFatigueLevel()).thenReturn(0.0);
        when(attentionModel.getCurrentState()).thenReturn(AttentionState.DISTRACTED);
        when(playerProfile.getBasePredictionRate()).thenReturn(0.80);

        double rate = hoverManager.calculateEffectivePredictionRate();

        // 0.80 * 0.40 = 0.32
        assertEquals(0.32, rate, 0.01);
    }

    @Test
    public void testEffectivePredictionRate_FatigueAndDistracted() {
        // Combined effects
        when(fatigueModel.getFatigueLevel()).thenReturn(0.50);
        when(attentionModel.getCurrentState()).thenReturn(AttentionState.DISTRACTED);
        when(playerProfile.getBasePredictionRate()).thenReturn(0.80);

        double rate = hoverManager.calculateEffectivePredictionRate();

        // 0.80 * 0.75 (fatigue) * 0.40 (distracted) = 0.24
        assertEquals(0.24, rate, 0.01);
    }

    @Test
    public void testEffectivePredictionRate_MinBound() {
        // Even with extreme modifiers, should not go below minimum (0.10)
        when(fatigueModel.getFatigueLevel()).thenReturn(1.0);
        when(attentionModel.getCurrentState()).thenReturn(AttentionState.DISTRACTED);
        when(playerProfile.getBasePredictionRate()).thenReturn(0.30);

        double rate = hoverManager.calculateEffectivePredictionRate();

        // 0.30 * 0.50 (fatigue) * 0.40 (distracted) = 0.06, but clamped to 0.10
        assertEquals(0.10, rate, 0.01);
    }

    @Test
    public void testEffectivePredictionRate_MaxBound() {
        // Should not exceed maximum (0.95)
        when(fatigueModel.getFatigueLevel()).thenReturn(0.0);
        when(attentionModel.getCurrentState()).thenReturn(AttentionState.FOCUSED);
        when(playerProfile.getBasePredictionRate()).thenReturn(1.0);

        double rate = hoverManager.calculateEffectivePredictionRate();

        assertEquals(0.95, rate, 0.01);
    }

    // ========================================================================
    // Should Predictive Hover Tests
    // ========================================================================

    @Test
    public void testShouldPredictiveHover_FalseWhenAFK() {
        when(attentionModel.getCurrentState()).thenReturn(AttentionState.AFK);

        assertFalse(hoverManager.shouldPredictiveHover());
    }

    @Test
    public void testShouldPredictiveHover_FalseWhenAlreadyHovering() {
        // Create an initial hover state
        PredictiveHoverState initialState = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_NPC_ID)
                .isNpc(true)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .build();

        hoverManager = new PredictiveHoverManager(
                playerProfile, fatigueModel, attentionModel, randomization, navigationService, initialState);

        assertFalse(hoverManager.shouldPredictiveHover());
    }

    @Test
    public void testShouldPredictiveHover_UsesEffectiveRate() {
        // With 100% base rate and no modifiers, should always return true
        when(playerProfile.getBasePredictionRate()).thenReturn(1.0);
        when(fatigueModel.getFatigueLevel()).thenReturn(0.0);
        when(attentionModel.getCurrentState()).thenReturn(AttentionState.FOCUSED);

        // Run multiple times - with very high rate, should almost always be true
        int successCount = 0;
        for (int i = 0; i < 100; i++) {
            // Reset rate limiting by waiting
            hoverManager = new PredictiveHoverManager(playerProfile, fatigueModel, attentionModel,
                    new Randomization(i), navigationService);
            if (hoverManager.shouldPredictiveHover()) {
                successCount++;
            }
        }

        // With 95% effective rate (capped), expect ~95% success
        assertTrue("Expected high success rate, got " + successCount, successCount > 80);
    }

    // ========================================================================
    // Clear Hover Tests
    // ========================================================================

    @Test
    public void testClearHover_RemovesPendingHover() {
        // Setup initial hover
        PredictiveHoverState initialState = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_NPC_ID)
                .isNpc(true)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .build();

        hoverManager = new PredictiveHoverManager(
                playerProfile, fatigueModel, attentionModel, randomization, navigationService, initialState);

        assertTrue(hoverManager.hasPendingHover());

        hoverManager.clearHover();

        assertFalse(hoverManager.hasPendingHover());
        assertNull(hoverManager.getCurrentHover());
    }

    @Test
    public void testClearHover_SafeWhenNoHover() {
        // Should not throw when no hover exists
        hoverManager.clearHover();
        assertFalse(hoverManager.hasPendingHover());
    }

    // ========================================================================
    // Hover State Tests
    // ========================================================================

    @Test
    public void testHoverState_IsValid() {
        PredictiveHoverState state = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_NPC_ID)
                .isNpc(true)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .build();

        assertTrue(state.isValid());
    }

    @Test
    public void testHoverState_InvalidWithoutPosition() {
        PredictiveHoverState state = PredictiveHoverState.builder()
                .targetPosition(null)
                .targetId(TEST_NPC_ID)
                .isNpc(true)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .build();

        assertFalse(state.isValid());
    }

    @Test
    public void testHoverState_RequiresValidationForNpc() {
        PredictiveHoverState npcState = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_NPC_ID)
                .isNpc(true)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .build();

        assertTrue(npcState.requiresValidation());
    }

    @Test
    public void testHoverState_NoValidationForObject() {
        PredictiveHoverState objectState = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_OBJECT_ID)
                .isNpc(false)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .build();

        assertFalse(objectState.requiresValidation());
    }

    @Test
    public void testHoverState_WithValidation() {
        PredictiveHoverState state = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_NPC_ID)
                .isNpc(true)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .validatedThisTick(false)
                .consecutiveValidTicks(0)
                .build();

        PredictiveHoverState validated = state.withValidation(true);

        assertTrue(validated.isValidatedThisTick());
        assertEquals(1, validated.getConsecutiveValidTicks());
    }

    @Test
    public void testHoverState_WithReacquisition() {
        PredictiveHoverState state = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_NPC_ID)
                .isNpc(true)
                .npcIndex(0)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .originalHoverPosition(TARGET_POS)
                .reacquisitionCount(0)
                .build();

        PredictiveHoverState reacquired = state.withReacquisition(TARGET_POS_2, 1);

        assertEquals(TARGET_POS_2, reacquired.getTargetPosition());
        assertEquals(1, reacquired.getNpcIndex());
        assertEquals(1, reacquired.getReacquisitionCount());
        assertTrue(reacquired.isValidatedThisTick());
    }

    @Test
    public void testHoverState_DriftDistance() {
        PredictiveHoverState state = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS_2)
                .targetId(TEST_NPC_ID)
                .isNpc(true)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .originalHoverPosition(TARGET_POS)
                .build();

        // Distance from (3202,3202) to (3205,3205) = ~4.24 tiles
        int drift = state.getDriftDistance();
        assertTrue("Expected drift > 0, got " + drift, drift > 0);
    }

    @Test
    public void testHoverState_IsUnstable() {
        // Unstable when reacquisition count >= 3
        PredictiveHoverState unstable = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_NPC_ID)
                .isNpc(true)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .originalHoverPosition(TARGET_POS)
                .reacquisitionCount(3)
                .build();

        assertTrue(unstable.isUnstable());
    }

    // ========================================================================
    // Click Behavior Tests
    // ========================================================================

    @Test
    public void testHoverState_ShouldInstantClick() {
        PredictiveHoverState state = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_OBJECT_ID)
                .isNpc(false)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .build();

        assertTrue(state.shouldInstantClick());
        assertFalse(state.shouldDelayedClick());
        assertFalse(state.shouldAbandon());
    }

    @Test
    public void testHoverState_ShouldDelayedClick() {
        PredictiveHoverState state = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_OBJECT_ID)
                .isNpc(false)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.DELAYED)
                .build();

        assertFalse(state.shouldInstantClick());
        assertTrue(state.shouldDelayedClick());
        assertFalse(state.shouldAbandon());
    }

    @Test
    public void testHoverState_ShouldAbandon() {
        PredictiveHoverState state = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_OBJECT_ID)
                .isNpc(false)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.ABANDON)
                .build();

        assertFalse(state.shouldInstantClick());
        assertFalse(state.shouldDelayedClick());
        assertTrue(state.shouldAbandon());
    }

    @Test
    public void testHoverState_NpcRequiresValidationForInstantClick() {
        // NPC with INSTANT behavior but not validated should not instant click
        PredictiveHoverState state = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_NPC_ID)
                .isNpc(true)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .validatedThisTick(false)
                .build();

        assertFalse(state.shouldInstantClick());
    }

    // ========================================================================
    // Metrics Tests
    // ========================================================================

    @Test
    public void testResetMetrics() {
        // Create manager with some metrics
        PredictiveHoverState initialState = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_NPC_ID)
                .isNpc(true)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .build();

        hoverManager = new PredictiveHoverManager(
                playerProfile, fatigueModel, attentionModel, randomization, navigationService, initialState);

        // Simulate some activity to generate metrics
        hoverManager.clearHover(); // triggers abandoned count

        hoverManager.resetMetrics();

        assertEquals(0, hoverManager.getHoverAttemptsTotal());
        assertEquals(0, hoverManager.getHoverSuccessCount());
        assertEquals(0, hoverManager.getInstantClickCount());
        assertEquals(0, hoverManager.getDelayedClickCount());
        assertEquals(0, hoverManager.getAbandonedHoverCount());
    }

    @Test
    public void testHoverSuccessRate_NoAttempts() {
        assertEquals(0.0, hoverManager.getHoverSuccessRate(), 0.001);
    }

    @Test
    public void testMetricsSummary_ContainsAllMetrics() {
        String summary = hoverManager.getMetricsSummary();

        assertTrue(summary.contains("Hovers:"));
        assertTrue(summary.contains("attempts"));
        assertTrue(summary.contains("success"));
        assertTrue(summary.contains("instant"));
        assertTrue(summary.contains("delayed"));
        assertTrue(summary.contains("abandoned"));
    }

    // ========================================================================
    // NPC Validation Tests
    // ========================================================================

    @Test
    public void testValidateAndUpdateHover_ObjectNoValidation() {
        // Objects don't need validation, should just mark as validated
        PredictiveHoverState objectState = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_OBJECT_ID)
                .isNpc(false)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .validatedThisTick(false)
                .build();

        hoverManager = new PredictiveHoverManager(
                playerProfile, fatigueModel, attentionModel, randomization, navigationService, objectState);

        Set<Integer> targetIds = new HashSet<>(Arrays.asList(TEST_OBJECT_ID));
        hoverManager.validateAndUpdateHover(taskContext, targetIds);

        // Should be validated now
        PredictiveHoverState updated = hoverManager.getCurrentHover();
        assertNotNull(updated);
        assertTrue(updated.isValidatedThisTick());
    }

    @Test
    public void testValidateAndUpdateHover_NpcStillAtPosition() {
        // Setup NPC at expected position
        NpcSnapshot npc = NpcSnapshot.builder()
                .id(TEST_NPC_ID)
                .index(0)
                .worldPosition(TARGET_POS)
                .isDead(false)
                .build();

        when(worldState.getNearbyNpcs()).thenReturn(Arrays.asList(npc));

        PredictiveHoverState npcState = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_NPC_ID)
                .isNpc(true)
                .npcIndex(0)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .validatedThisTick(false)
                .originalHoverPosition(TARGET_POS)
                .build();

        hoverManager = new PredictiveHoverManager(
                playerProfile, fatigueModel, attentionModel, randomization, navigationService, npcState);

        Set<Integer> targetIds = new HashSet<>(Arrays.asList(TEST_NPC_ID));
        hoverManager.validateAndUpdateHover(taskContext, targetIds);

        // Should be validated
        PredictiveHoverState updated = hoverManager.getCurrentHover();
        assertNotNull(updated);
        assertTrue(updated.isValidatedThisTick());
    }

    @Test
    public void testValidateAndUpdateHover_NpcMoved_Reacquire() {
        // NPC moved to new position
        NpcSnapshot movedNpc = NpcSnapshot.builder()
                .id(TEST_NPC_ID)
                .index(1)
                .worldPosition(TARGET_POS_2)
                .isDead(false)
                .build();

        when(worldState.getNearbyNpcs()).thenReturn(Arrays.asList(movedNpc));

        PredictiveHoverState npcState = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_NPC_ID)
                .isNpc(true)
                .npcIndex(0)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .validatedThisTick(false)
                .originalHoverPosition(TARGET_POS)
                .reacquisitionCount(0)
                .build();

        hoverManager = new PredictiveHoverManager(
                playerProfile, fatigueModel, attentionModel, randomization, navigationService, npcState);

        // Mock the client NPCs list for re-hover
        NPC mockNpc = mock(NPC.class);
        Shape convexHull = new Rectangle(100, 100, 50, 50);
        when(mockNpc.getConvexHull()).thenReturn(convexHull);
        when(mockNpc.getIndex()).thenReturn(1);
        when(client.getNpcs()).thenReturn(Arrays.asList(mockNpc));

        Set<Integer> targetIds = new HashSet<>(Arrays.asList(TEST_NPC_ID));
        hoverManager.validateAndUpdateHover(taskContext, targetIds);

        // Hover should be updated to new position with incremented reacquisition count
        PredictiveHoverState updated = hoverManager.getCurrentHover();
        assertNotNull("Hover should not be cleared after reacquisition", updated);
        assertEquals(TARGET_POS_2, updated.getTargetPosition());
        assertEquals(1, updated.getReacquisitionCount());
    }

    @Test
    public void testValidateAndUpdateHover_TooManyReacquisitions_Abandon() {
        // No NPC at expected position, and already at max reacquisitions
        when(worldState.getNearbyNpcs()).thenReturn(Collections.emptyList());

        PredictiveHoverState npcState = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_NPC_ID)
                .isNpc(true)
                .npcIndex(0)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                .validatedThisTick(false)
                .originalHoverPosition(TARGET_POS)
                .reacquisitionCount(3) // At max
                .build();

        hoverManager = new PredictiveHoverManager(
                playerProfile, fatigueModel, attentionModel, randomization, navigationService, npcState);

        Set<Integer> targetIds = new HashSet<>(Arrays.asList(TEST_NPC_ID));
        hoverManager.validateAndUpdateHover(taskContext, targetIds);

        // Hover should be abandoned
        assertFalse(hoverManager.hasPendingHover());
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testStartPredictiveHover_NoTargetsFound() {
        // No NPCs nearby
        when(worldState.getNearbyNpcs()).thenReturn(Collections.emptyList());

        Set<Integer> targetIds = new HashSet<>(Arrays.asList(TEST_NPC_ID));
        CompletableFuture<Boolean> result = hoverManager.startPredictiveHover(
                taskContext, targetIds, null, true, "Net");

        // Should return false
        assertFalse(result.join());
        assertFalse(hoverManager.hasPendingHover());
    }

    @Test
    public void testStartPredictiveHover_AllTargetsExcluded() {
        // Only one NPC, but it's at the excluded position
        NpcSnapshot npc = NpcSnapshot.builder()
                .id(TEST_NPC_ID)
                .index(0)
                .worldPosition(TARGET_POS)
                .isDead(false)
                .build();

        when(worldState.getNearbyNpcs()).thenReturn(Arrays.asList(npc));

        Set<Integer> targetIds = new HashSet<>(Arrays.asList(TEST_NPC_ID));
        CompletableFuture<Boolean> result = hoverManager.startPredictiveHover(
                taskContext, targetIds, TARGET_POS, true, "Net"); // Exclude the only target

        // Should return false - no valid targets
        assertFalse(result.join());
        assertFalse(hoverManager.hasPendingHover());
    }

    @Test
    public void testStartPredictiveHover_NullPlayerPosition() {
        when(playerState.getWorldPosition()).thenReturn(null);

        Set<Integer> targetIds = new HashSet<>(Arrays.asList(TEST_NPC_ID));
        CompletableFuture<Boolean> result = hoverManager.startPredictiveHover(
                taskContext, targetIds, null, true, "Net");

        // Should return false
        assertFalse(result.join());
    }

    @Test
    public void testExecutePredictedClick_NoHover() {
        // No hover pending
        CompletableFuture<Boolean> result = hoverManager.executePredictedClick(taskContext);

        assertFalse(result.join());
    }

    @Test
    public void testExecutePredictedClick_AbandonBehavior() {
        PredictiveHoverState abandonState = PredictiveHoverState.builder()
                .targetPosition(TARGET_POS)
                .targetId(TEST_OBJECT_ID)
                .isNpc(false)
                .hoverStartTime(Instant.now())
                .plannedBehavior(PredictiveHoverState.ClickBehavior.ABANDON)
                .build();

        hoverManager = new PredictiveHoverManager(
                playerProfile, fatigueModel, attentionModel, randomization, navigationService, abandonState);

        CompletableFuture<Boolean> result = hoverManager.executePredictedClick(taskContext);

        assertFalse(result.join());
        assertEquals(1, hoverManager.getAbandonedHoverCount());
        assertFalse(hoverManager.hasPendingHover()); // Hover should be cleared
    }

    @Test
    public void testExecutePredictedClick_InstantBehavior() {
        // Setup scene mocking for object target validation
        Scene mockScene = mock(Scene.class);
        Tile mockTile = mock(Tile.class);
        GameObject mockObject = mock(GameObject.class);
        Shape mockClickbox = mock(Shape.class);
        Rectangle clickboxBounds = new Rectangle(100, 100, 50, 50);
        
        when(client.getScene()).thenReturn(mockScene);
        
        // Create a proper 3D tile array
        Tile[][][] tiles = new Tile[4][104][104];
        tiles[0][2][2] = mockTile; // Scene coords for TARGET_POS (3202,3202) relative to base
        when(mockScene.getTiles()).thenReturn(tiles);
        
        // Setup the tile and object
        when(mockTile.getGameObjects()).thenReturn(new GameObject[] { mockObject });
        when(mockObject.getId()).thenReturn(TEST_OBJECT_ID);
        when(mockObject.getClickbox()).thenReturn(mockClickbox);
        when(mockClickbox.getBounds()).thenReturn(clickboxBounds);
        
        // Mock mouse position to be on target
        when(mouseController.getCurrentPosition()).thenReturn(new java.awt.Point(125, 125));

        // Mock LocalPoint.fromWorld using mockito-inline
        try (MockedStatic<LocalPoint> mockedLocalPoint = mockStatic(LocalPoint.class)) {
            LocalPoint mockLocalPoint = mock(LocalPoint.class);
            when(mockLocalPoint.getSceneX()).thenReturn(2);
            when(mockLocalPoint.getSceneY()).thenReturn(2);
            mockedLocalPoint.when(() -> LocalPoint.fromWorld(eq(client), any(WorldPoint.class)))
                    .thenReturn(mockLocalPoint);

            PredictiveHoverState instantState = PredictiveHoverState.builder()
                    .targetPosition(TARGET_POS)
                    .targetId(TEST_OBJECT_ID)
                    .isNpc(false)
                    .hoverStartTime(Instant.now())
                    .plannedBehavior(PredictiveHoverState.ClickBehavior.INSTANT)
                    .build();

            hoverManager = new PredictiveHoverManager(
                    playerProfile, fatigueModel, attentionModel, randomization, navigationService, instantState);

            CompletableFuture<Boolean> result = hoverManager.executePredictedClick(taskContext);

            assertTrue(result.join());
            assertEquals(1, hoverManager.getInstantClickCount());
            assertFalse(hoverManager.hasPendingHover()); // Hover should be cleared after click
            verify(mouseController).click();
        }
    }
}

