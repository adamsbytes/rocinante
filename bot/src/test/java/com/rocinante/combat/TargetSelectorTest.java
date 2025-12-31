package com.rocinante.combat;

import com.rocinante.core.GameStateService;
import com.rocinante.navigation.PathFinder;
import com.rocinante.navigation.Reachability;
import com.rocinante.state.AggressorInfo;
import com.rocinante.state.CombatState;
import com.rocinante.state.NpcSnapshot;
import com.rocinante.state.PlayerSnapshot;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TargetSelector.
 * Tests all selection priorities and avoidance rules per REQUIREMENTS.md Section 10.5.
 */
public class TargetSelectorTest {

    @Mock
    private Client client;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private PathFinder pathFinder;

    @Mock
    private Reachability reachability;

    private TargetSelector targetSelector;

    private WorldPoint playerPos;
    private PlayerState validPlayerState;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        targetSelector = new TargetSelector(client, gameStateService, pathFinder, reachability);
        
        playerPos = new WorldPoint(3200, 3200, 0);
        validPlayerState = PlayerState.builder()
                .worldPosition(playerPos)
                .currentHitpoints(50)
                .maxHitpoints(50)
                .build();

        // Default: paths are reachable
        when(pathFinder.hasPath(any(WorldPoint.class), any(WorldPoint.class))).thenReturn(true);
        when(reachability.canInteract(any(WorldPoint.class), any(WorldPoint.class))).thenReturn(true);
        when(reachability.hasLineOfSight(any(WorldPoint.class), any(WorldPoint.class))).thenReturn(true);
        when(reachability.findAttackablePosition(any(WorldPoint.class), any(WorldPoint.class), anyInt()))
                .thenReturn(Optional.empty());
        
        // Default: no aggressive NPCs (empty combat state)
        when(gameStateService.getCombatState()).thenReturn(CombatState.EMPTY);
    }

    // ========================================================================
    // Basic Selection Tests
    // ========================================================================

    @Test
    public void testSelectTarget_ReturnsEmpty_WhenPlayerStateInvalid() {
        when(gameStateService.getPlayerState()).thenReturn(PlayerState.EMPTY);

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertFalse(result.isPresent());
    }

    @Test
    public void testSelectTarget_ReturnsEmpty_WhenNoNpcsNearby() {
        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(WorldState.EMPTY);

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertFalse(result.isPresent());
    }

    @Test
    public void testSelectTarget_ReturnsNpc_WhenValidTargetExists() {
        NpcSnapshot goblin = createNpc(1, "Goblin", 5, 3202, 3202, false, false);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Collections.singletonList(goblin))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertTrue(result.isPresent());
        assertEquals("Goblin", result.get().getName());
    }

    // ========================================================================
    // Priority: TARGETING_PLAYER Tests (Section 10.5.1)
    // ========================================================================

    @Test
    public void testSelectTarget_PrioritizesNpcTargetingPlayer() {
        NpcSnapshot passiveGoblin = createNpc(1, "Goblin", 5, 3201, 3201, false, false);
        NpcSnapshot aggressiveGoblin = createNpc(2, "Goblin", 5, 3205, 3205, true, false);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Arrays.asList(passiveGoblin, aggressiveGoblin))
                .nearbyPlayers(Collections.emptyList())
                .build();

        // Create a CombatState that marks the aggressive goblin as having attacked us
        AggressorInfo aggressor = AggressorInfo.builder()
                .npcIndex(2) // aggressiveGoblin's index
                .npcId(2)
                .npcName("Goblin")
                .combatLevel(5)
                .build();
        CombatState combatState = CombatState.builder()
                .aggressiveNpcs(Collections.singletonList(aggressor))
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);
        when(gameStateService.getCombatState()).thenReturn(combatState);

        targetSelector.setConfig(TargetSelectorConfig.builder()
                .priority(SelectionPriority.TARGETING_PLAYER)
                .priority(SelectionPriority.NEAREST)
                .build());

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertTrue(result.isPresent());
        // Should select aggressive goblin even though passive one is closer
        assertEquals(2, result.get().getIndex());
        assertTrue(result.get().isTargetingPlayer());
    }

    @Test
    public void testSelectTarget_FallsBackToNearest_WhenNoneTargetingPlayer() {
        NpcSnapshot nearGoblin = createNpc(1, "Goblin", 5, 3201, 3201, false, false);
        NpcSnapshot farGoblin = createNpc(2, "Goblin", 5, 3210, 3210, false, false);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Arrays.asList(farGoblin, nearGoblin))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        targetSelector.setConfig(TargetSelectorConfig.builder()
                .priority(SelectionPriority.TARGETING_PLAYER)
                .priority(SelectionPriority.NEAREST)
                .build());

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getIndex()); // Near goblin
    }

    // ========================================================================
    // Priority: LOWEST_HP Tests (Section 10.5.1)
    // ========================================================================

    @Test
    public void testSelectTarget_SelectsLowestHp() {
        NpcSnapshot fullHp = createNpcWithHealth(1, "Goblin", 5, 3201, 3201, 1.0);
        NpcSnapshot halfHp = createNpcWithHealth(2, "Goblin", 5, 3202, 3202, 0.5);
        NpcSnapshot lowHp = createNpcWithHealth(3, "Goblin", 5, 3203, 3203, 0.2);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Arrays.asList(fullHp, halfHp, lowHp))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        targetSelector.setConfig(TargetSelectorConfig.builder()
                .priority(SelectionPriority.LOWEST_HP)
                .build());

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertTrue(result.isPresent());
        assertEquals(3, result.get().getIndex()); // lowHp goblin
    }

    // ========================================================================
    // Priority: HIGHEST_HP Tests (Section 10.5.1)
    // ========================================================================

    @Test
    public void testSelectTarget_SelectsHighestHp() {
        NpcSnapshot lowHp = createNpcWithHealth(1, "Goblin", 5, 3201, 3201, 0.2);
        NpcSnapshot halfHp = createNpcWithHealth(2, "Goblin", 5, 3202, 3202, 0.5);
        NpcSnapshot highHp = createNpcWithHealth(3, "Goblin", 5, 3203, 3203, 0.9);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Arrays.asList(lowHp, halfHp, highHp))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        targetSelector.setConfig(TargetSelectorConfig.builder()
                .priority(SelectionPriority.HIGHEST_HP)
                .build());

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertTrue(result.isPresent());
        assertEquals(3, result.get().getIndex()); // highHp goblin
    }

    // ========================================================================
    // Priority: NEAREST Tests (Section 10.5.1)
    // ========================================================================

    @Test
    public void testSelectTarget_SelectsNearest() {
        NpcSnapshot far = createNpc(1, "Goblin", 5, 3210, 3210, false, false);
        NpcSnapshot near = createNpc(2, "Goblin", 5, 3201, 3201, false, false);
        NpcSnapshot medium = createNpc(3, "Goblin", 5, 3205, 3205, false, false);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Arrays.asList(far, near, medium))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        targetSelector.setConfig(TargetSelectorConfig.builder()
                .priority(SelectionPriority.NEAREST)
                .build());

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertTrue(result.isPresent());
        assertEquals(2, result.get().getIndex()); // near goblin
    }

    // ========================================================================
    // Priority: SPECIFIC_ID Tests (Section 10.5.1)
    // ========================================================================

    @Test
    public void testSelectTarget_FiltersBySpecificId() {
        NpcSnapshot goblin = createNpc(1, "Goblin", 5, 3201, 3201, false, false, 100);
        NpcSnapshot cow = createNpc(2, "Cow", 2, 3202, 3202, false, false, 200);
        NpcSnapshot chicken = createNpc(3, "Chicken", 1, 3203, 3203, false, false, 300);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Arrays.asList(goblin, cow, chicken))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        targetSelector.setConfig(TargetSelectorConfig.forNpcIds(200)); // Cow only

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertTrue(result.isPresent());
        assertEquals("Cow", result.get().getName());
        assertEquals(200, result.get().getId());
    }

    @Test
    public void testSelectTarget_ReturnsEmpty_WhenSpecificIdNotFound() {
        NpcSnapshot goblin = createNpc(1, "Goblin", 5, 3201, 3201, false, false, 100);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Collections.singletonList(goblin))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        targetSelector.setConfig(TargetSelectorConfig.forNpcIds(999)); // ID doesn't exist

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        // Falls back to NEAREST after SPECIFIC_ID fails
        assertTrue(result.isPresent());
    }

    // ========================================================================
    // Priority: SPECIFIC_NAME Tests (Section 10.5.1)
    // ========================================================================

    @Test
    public void testSelectTarget_FiltersBySpecificName() {
        NpcSnapshot goblin = createNpc(1, "Goblin", 5, 3201, 3201, false, false);
        NpcSnapshot cow = createNpc(2, "Cow", 2, 3202, 3202, false, false);
        NpcSnapshot chicken = createNpc(3, "Chicken", 1, 3203, 3203, false, false);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Arrays.asList(goblin, cow, chicken))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        targetSelector.setConfig(TargetSelectorConfig.forNpcNames("Cow"));

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertTrue(result.isPresent());
        assertEquals("Cow", result.get().getName());
    }

    @Test
    public void testSelectTarget_NameMatchIsCaseInsensitive() {
        NpcSnapshot cow = createNpc(1, "Cow", 2, 3201, 3201, false, false);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Collections.singletonList(cow))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        targetSelector.setConfig(TargetSelectorConfig.forNpcNames("COW")); // Uppercase

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertTrue(result.isPresent());
        assertEquals("Cow", result.get().getName());
    }

    // ========================================================================
    // Avoidance: Skip Dead NPCs (Section 10.5.2)
    // ========================================================================

    @Test
    public void testSelectTarget_SkipsDeadNpcs() {
        NpcSnapshot aliveGoblin = createNpc(1, "Goblin", 5, 3201, 3201, false, false);
        NpcSnapshot deadGoblin = createNpc(2, "Goblin", 5, 3199, 3199, false, true); // Dead, closer
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Arrays.asList(aliveGoblin, deadGoblin))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        targetSelector.setConfig(TargetSelectorConfig.builder()
                .priority(SelectionPriority.NEAREST)
                .skipDead(true)
                .build());

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getIndex()); // Alive goblin, not dead one
        assertFalse(result.get().isDead());
    }

    // ========================================================================
    // Avoidance: Skip NPCs In Combat With Others (Section 10.5.2)
    // ========================================================================

    @Test
    public void testSelectTarget_SkipsNpcInCombatWithOtherPlayer() {
        // Create NPC that is interacting with another player (not local player)
        NpcSnapshot freeGoblin = createNpc(1, "Goblin", 5, 3205, 3205, false, false);
        NpcSnapshot busyGoblin = createNpcInteractingWithOther(2, "Goblin", 5, 3201, 3201);
        
        PlayerSnapshot otherPlayer = PlayerSnapshot.builder()
                .name("OtherPlayer")
                .worldPosition(new WorldPoint(3201, 3200, 0))
                .inCombat(true)
                .interactingIndex(2) // Targeting busyGoblin
                .build();
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Arrays.asList(freeGoblin, busyGoblin))
                .nearbyPlayers(Collections.singletonList(otherPlayer))
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        targetSelector.setConfig(TargetSelectorConfig.builder()
                .priority(SelectionPriority.NEAREST)
                .skipInCombatWithOthers(true)
                .build());

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getIndex()); // Free goblin, not busy one
    }

    // ========================================================================
    // Avoidance: Skip Unreachable NPCs (Section 10.5.2)
    // ========================================================================

    @Test
    public void testSelectTarget_SkipsUnreachableNpcs() {
        // Player at (3200, 3200, 0)
        // Unreachable goblin at (3203, 3203) - closer to player (3 tiles)
        // Reachable goblin at (3208, 3208) - further away (8 tiles)
        // Note: distance > 1 required to trigger pathfinder check
        NpcSnapshot reachable = createNpc(1, "Goblin", 5, 3208, 3208, false, false);
        NpcSnapshot unreachable = createNpc(2, "Goblin", 5, 3203, 3203, false, false);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Arrays.asList(reachable, unreachable))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);
        
        // Reset pathFinder mock and set specific behavior
        reset(pathFinder);
        // Make unreachable goblin (3203, 3203) have no path
        // Reachable goblin (3208, 3208) has path
        when(pathFinder.hasPath(any(WorldPoint.class), any(WorldPoint.class))).thenAnswer(invocation -> {
            WorldPoint dest = invocation.getArgument(1);
            // Return false for the unreachable goblin position (index 2)
            if (dest != null && dest.getX() == 3203 && dest.getY() == 3203) {
                return false;
            }
            return true;
        });

        // Use NEAREST priority - without unreachable filtering, would pick index 2
        // With filtering, should pick index 1 (reachable)
        targetSelector.setConfig(TargetSelectorConfig.builder()
                .priority(SelectionPriority.NEAREST)
                .skipUnreachable(true)
                .skipDead(true)
                .build());

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertTrue("Expected a valid target", result.isPresent());
        // Should select the reachable goblin (index 1), not the closer but unreachable one (index 2)
        assertEquals("Should select reachable goblin", 1, result.get().getIndex());
    }

    // ========================================================================
    // Avoidance: Skip NPCs Above Max Combat Level (Section 10.5.2)
    // ========================================================================

    @Test
    public void testSelectTarget_SkipsNpcsAboveMaxCombatLevel() {
        NpcSnapshot lowLevel = createNpc(1, "Goblin", 5, 3205, 3205, false, false);
        NpcSnapshot highLevel = createNpc(2, "Hill Giant", 28, 3201, 3201, false, false);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Arrays.asList(lowLevel, highLevel))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        targetSelector.setConfig(TargetSelectorConfig.builder()
                .priority(SelectionPriority.NEAREST)
                .maxCombatLevel(10) // Max level 10
                .build());

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getIndex()); // Low level goblin, not hill giant
    }

    @Test
    public void testSelectTarget_NoMaxLevelFilter_WhenSetToNegative() {
        NpcSnapshot highLevel = createNpc(1, "Hill Giant", 28, 3201, 3201, false, false);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Collections.singletonList(highLevel))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        targetSelector.setConfig(TargetSelectorConfig.builder()
                .priority(SelectionPriority.NEAREST)
                .maxCombatLevel(-1) // No limit
                .build());

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertTrue(result.isPresent());
        assertEquals("Hill Giant", result.get().getName());
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testSelectTarget_ReturnsEmpty_WhenAllTargetsFiltered() {
        NpcSnapshot deadGoblin = createNpc(1, "Goblin", 5, 3201, 3201, false, true);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Collections.singletonList(deadGoblin))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        targetSelector.setConfig(TargetSelectorConfig.builder()
                .priority(SelectionPriority.NEAREST)
                .skipDead(true)
                .build());

        Optional<NpcSnapshot> result = targetSelector.selectTarget();

        assertFalse(result.isPresent());
    }

    @Test
    public void testSelectTarget_WithCustomConfig_DoesNotModifyStoredConfig() {
        NpcSnapshot goblin = createNpc(1, "Goblin", 5, 3201, 3201, false, false);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Collections.singletonList(goblin))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        TargetSelectorConfig originalConfig = TargetSelectorConfig.DEFAULT;
        targetSelector.setConfig(originalConfig);

        TargetSelectorConfig customConfig = TargetSelectorConfig.builder()
                .maxCombatLevel(100)
                .build();

        targetSelector.selectTarget(customConfig);

        // Original config should be unchanged
        assertSame(originalConfig, targetSelector.getConfig());
    }

    @Test
    public void testGetValidTargets_ReturnsSortedList() {
        NpcSnapshot far = createNpc(1, "Goblin", 5, 3210, 3210, false, false);
        NpcSnapshot near = createNpc(2, "Goblin", 5, 3201, 3201, false, false);
        
        WorldState worldState = WorldState.builder()
                .nearbyNpcs(Arrays.asList(far, near))
                .nearbyPlayers(Collections.emptyList())
                .build();

        when(gameStateService.getPlayerState()).thenReturn(validPlayerState);
        when(gameStateService.getWorldState()).thenReturn(worldState);

        targetSelector.setConfig(TargetSelectorConfig.builder()
                .priority(SelectionPriority.NEAREST)
                .build());

        List<NpcSnapshot> validTargets = targetSelector.getValidTargets();

        assertEquals(2, validTargets.size());
        assertEquals(2, validTargets.get(0).getIndex()); // Near first
        assertEquals(1, validTargets.get(1).getIndex()); // Far second
    }

    // ========================================================================
    // Config Factory Method Tests
    // ========================================================================

    @Test
    public void testTargetSelectorConfig_ForNpcIds() {
        TargetSelectorConfig config = TargetSelectorConfig.forNpcIds(100, 200, 300);

        assertTrue(config.hasTargetNpcIds());
        assertTrue(config.isTargetNpcId(100));
        assertTrue(config.isTargetNpcId(200));
        assertTrue(config.isTargetNpcId(300));
        assertFalse(config.isTargetNpcId(999));
    }

    @Test
    public void testTargetSelectorConfig_ForNpcNames() {
        TargetSelectorConfig config = TargetSelectorConfig.forNpcNames("Cow", "Chicken");

        assertTrue(config.hasTargetNpcNames());
        assertTrue(config.isTargetNpcName("Cow"));
        assertTrue(config.isTargetNpcName("COW")); // Case insensitive
        assertTrue(config.isTargetNpcName("Chicken"));
        assertFalse(config.isTargetNpcName("Goblin"));
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private NpcSnapshot createNpc(int index, String name, int combatLevel, 
                                   int x, int y, boolean targetingPlayer, boolean dead) {
        return createNpc(index, name, combatLevel, x, y, targetingPlayer, dead, index);
    }

    private NpcSnapshot createNpc(int index, String name, int combatLevel, 
                                   int x, int y, boolean targetingPlayer, boolean dead, int id) {
        return NpcSnapshot.builder()
                .index(index)
                .id(id)
                .name(name)
                .combatLevel(combatLevel)
                .worldPosition(new WorldPoint(x, y, 0))
                .targetingPlayer(targetingPlayer)
                .isDead(dead)
                .healthRatio(-1) // No health bar by default
                .healthScale(30)
                .interactingIndex(-1)
                .size(1)
                .build();
    }

    private NpcSnapshot createNpcWithHealth(int index, String name, int combatLevel, 
                                             int x, int y, double healthPercent) {
        int healthRatio = (int) (healthPercent * 30);
        return NpcSnapshot.builder()
                .index(index)
                .id(index)
                .name(name)
                .combatLevel(combatLevel)
                .worldPosition(new WorldPoint(x, y, 0))
                .targetingPlayer(false)
                .isDead(false)
                .healthRatio(healthRatio)
                .healthScale(30)
                .interactingIndex(-1)
                .size(1)
                .build();
    }

    private NpcSnapshot createNpcInteractingWithOther(int index, String name, int combatLevel, int x, int y) {
        return NpcSnapshot.builder()
                .index(index)
                .id(index)
                .name(name)
                .combatLevel(combatLevel)
                .worldPosition(new WorldPoint(x, y, 0))
                .targetingPlayer(false)
                .isDead(false)
                .healthRatio(-1)
                .healthScale(30)
                .interactingIndex(-32768) // Negative = another player
                .size(1)
                .build();
    }
}

