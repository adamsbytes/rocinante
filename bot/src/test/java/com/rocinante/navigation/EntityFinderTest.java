package com.rocinante.navigation;

import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.NPC;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for EntityFinder centralized entity finding.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Object finding with reachability</li>
 *   <li>NPC finding for melee (adjacency required)</li>
 *   <li>NPC finding for ranged (weapon range support)</li>
 *   <li>Distance-only finding (for destination resolution)</li>
 * </ul>
 */
public class EntityFinderTest {

    @Mock
    private Client client;

    @Mock
    private PathFinder pathFinder;

    @Mock
    private Reachability reachability;

    @Mock
    private WebWalker webWalker;

    @Mock
    private Scene scene;

    private EntityFinder entityFinder;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        entityFinder = new EntityFinder(client, pathFinder, reachability, webWalker);

        // Set up basic scene mock
        when(client.getScene()).thenReturn(scene);
        Tile[][][] tiles = new Tile[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        when(scene.getTiles()).thenReturn(tiles);
    }

    // ========================================================================
    // findNearestReachableObject Tests
    // ========================================================================

    @Test
    public void testFindNearestReachableObject_NullPlayerPos_ReturnsEmpty() {
        Optional<EntityFinder.ObjectSearchResult> result = entityFinder.findNearestReachableObject(
                null, Set.of(1234), 15);

        assertTrue("Null player pos should return empty", result.isEmpty());
    }

    @Test
    public void testFindNearestReachableObject_NullObjectIds_ReturnsEmpty() {
        WorldPoint playerPos = new WorldPoint(3250, 3250, 0);

        Optional<EntityFinder.ObjectSearchResult> result = entityFinder.findNearestReachableObject(
                playerPos, null, 15);

        assertTrue("Null object IDs should return empty", result.isEmpty());
    }

    @Test
    public void testFindNearestReachableObject_EmptyObjectIds_ReturnsEmpty() {
        WorldPoint playerPos = new WorldPoint(3250, 3250, 0);

        Optional<EntityFinder.ObjectSearchResult> result = entityFinder.findNearestReachableObject(
                playerPos, Collections.emptySet(), 15);

        assertTrue("Empty object IDs should return empty", result.isEmpty());
    }

    // ========================================================================
    // findNearestReachableNpc Tests
    // ========================================================================

    @Test
    public void testFindNearestReachableNpc_NullPlayerPos_ReturnsEmpty() {
        Optional<EntityFinder.NpcSearchResult> result = entityFinder.findNearestReachableNpc(
                null, Set.of(1234), 15);

        assertTrue("Null player pos should return empty", result.isEmpty());
    }

    @Test
    public void testFindNearestReachableNpc_NullNpcIds_ReturnsEmpty() {
        WorldPoint playerPos = new WorldPoint(3250, 3250, 0);

        Optional<EntityFinder.NpcSearchResult> result = entityFinder.findNearestReachableNpc(
                playerPos, null, 15);

        assertTrue("Null NPC IDs should return empty", result.isEmpty());
    }

    @Test
    public void testFindNearestReachableNpc_EmptyNpcIds_ReturnsEmpty() {
        WorldPoint playerPos = new WorldPoint(3250, 3250, 0);

        Optional<EntityFinder.NpcSearchResult> result = entityFinder.findNearestReachableNpc(
                playerPos, Collections.emptySet(), 15);

        assertTrue("Empty NPC IDs should return empty", result.isEmpty());
    }

    @Test
    public void testFindNearestReachableNpc_NoNpcsInWorld_ReturnsEmpty() {
        WorldPoint playerPos = new WorldPoint(3250, 3250, 0);
        when(client.getNpcs()).thenReturn(Collections.emptyList());

        Optional<EntityFinder.NpcSearchResult> result = entityFinder.findNearestReachableNpc(
                playerPos, Set.of(1234), 15);

        assertTrue("No NPCs in world should return empty", result.isEmpty());
    }

    @Test
    public void testFindNearestReachableNpc_NpcOutOfRange_ReturnsEmpty() {
        WorldPoint playerPos = new WorldPoint(3250, 3250, 0);

        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(1234);
        when(npc.isDead()).thenReturn(false);
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3300, 3300, 0)); // Far away

        when(client.getNpcs()).thenReturn(Collections.singletonList(npc));

        Optional<EntityFinder.NpcSearchResult> result = entityFinder.findNearestReachableNpc(
                playerPos, Set.of(1234), 15);

        assertTrue("NPC out of range should return empty", result.isEmpty());
    }

    @Test
    public void testFindNearestReachableNpc_NpcDead_ReturnsEmpty() {
        WorldPoint playerPos = new WorldPoint(3250, 3250, 0);

        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(1234);
        when(npc.isDead()).thenReturn(true); // Dead NPC
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3251, 3250, 0));

        when(client.getNpcs()).thenReturn(Collections.singletonList(npc));

        Optional<EntityFinder.NpcSearchResult> result = entityFinder.findNearestReachableNpc(
                playerPos, Set.of(1234), 15);

        assertTrue("Dead NPC should be skipped", result.isEmpty());
    }

    @Test
    public void testFindNearestReachableNpc_NpcDifferentPlane_ReturnsEmpty() {
        WorldPoint playerPos = new WorldPoint(3250, 3250, 0);

        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(1234);
        when(npc.isDead()).thenReturn(false);
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3251, 3250, 1)); // Different plane

        when(client.getNpcs()).thenReturn(Collections.singletonList(npc));

        Optional<EntityFinder.NpcSearchResult> result = entityFinder.findNearestReachableNpc(
                playerPos, Set.of(1234), 15);

        assertTrue("NPC on different plane should be skipped", result.isEmpty());
    }

    @Test
    public void testFindNearestReachableNpc_NpcWrongId_ReturnsEmpty() {
        WorldPoint playerPos = new WorldPoint(3250, 3250, 0);

        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(9999); // Wrong ID
        when(npc.isDead()).thenReturn(false);
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3251, 3250, 0));

        when(client.getNpcs()).thenReturn(Collections.singletonList(npc));

        Optional<EntityFinder.NpcSearchResult> result = entityFinder.findNearestReachableNpc(
                playerPos, Set.of(1234), 15);

        assertTrue("NPC with wrong ID should be skipped", result.isEmpty());
    }

    @Test
    public void testFindNearestReachableNpc_NameFilterMismatch_ReturnsEmpty() {
        WorldPoint playerPos = new WorldPoint(3250, 3250, 0);

        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(1234);
        when(npc.isDead()).thenReturn(false);
        when(npc.getName()).thenReturn("Goblin");
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3251, 3250, 0));

        when(client.getNpcs()).thenReturn(Collections.singletonList(npc));

        // Looking for "Cow" but found "Goblin"
        Optional<EntityFinder.NpcSearchResult> result = entityFinder.findNearestReachableNpc(
                playerPos, Set.of(1234), "Cow", 15);

        assertTrue("NPC with wrong name should be skipped", result.isEmpty());
    }

    // ========================================================================
    // findNearestAttackableNpc Tests (Ranged/Magic)
    // ========================================================================

    @Test
    public void testFindNearestAttackableNpc_NullPlayerPos_ReturnsEmpty() {
        Optional<EntityFinder.NpcSearchResult> result = entityFinder.findNearestAttackableNpc(
                null, Set.of(1234), 15, 7);

        assertTrue("Null player pos should return empty", result.isEmpty());
    }

    @Test
    public void testFindNearestAttackableNpc_InvalidRange_ReturnsEmpty() {
        WorldPoint playerPos = new WorldPoint(3250, 3250, 0);

        Optional<EntityFinder.NpcSearchResult> result = entityFinder.findNearestAttackableNpc(
                playerPos, Set.of(1234), 15, 0);

        assertTrue("Zero weapon range should return empty", result.isEmpty());
    }

    // ========================================================================
    // findNearestObjectByDistance Tests
    // ========================================================================

    @Test
    public void testFindNearestObjectByDistance_NullPlayerPos_ReturnsEmpty() {
        Optional<net.runelite.api.TileObject> result = entityFinder.findNearestObjectByDistance(
                null, Set.of(1234), 15);

        assertTrue("Null player pos should return empty", result.isEmpty());
    }

    @Test
    public void testFindNearestObjectByDistance_NullObjectIds_ReturnsEmpty() {
        WorldPoint playerPos = new WorldPoint(3250, 3250, 0);

        Optional<net.runelite.api.TileObject> result = entityFinder.findNearestObjectByDistance(
                playerPos, null, 15);

        assertTrue("Null object IDs should return empty", result.isEmpty());
    }

    // ========================================================================
    // findNearestNpcByDistance Tests
    // ========================================================================

    @Test
    public void testFindNearestNpcByDistance_NullPlayerPos_ReturnsEmpty() {
        Optional<NPC> result = entityFinder.findNearestNpcByDistance(
                null, Set.of(1234), 15);

        assertTrue("Null player pos should return empty", result.isEmpty());
    }

    @Test
    public void testFindNearestNpcByDistance_NoNpcs_ReturnsEmpty() {
        WorldPoint playerPos = new WorldPoint(3250, 3250, 0);
        when(client.getNpcs()).thenReturn(Collections.emptyList());

        Optional<NPC> result = entityFinder.findNearestNpcByDistance(
                playerPos, Set.of(1234), 15);

        assertTrue("No NPCs should return empty", result.isEmpty());
    }

    @Test
    public void testFindNearestNpcByDistance_FindsValidNpc() {
        WorldPoint playerPos = new WorldPoint(3250, 3250, 0);

        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(1234);
        when(npc.isDead()).thenReturn(false);
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3251, 3250, 0));

        when(client.getNpcs()).thenReturn(Collections.singletonList(npc));

        Optional<NPC> result = entityFinder.findNearestNpcByDistance(
                playerPos, Set.of(1234), 15);

        assertTrue("Should find valid NPC", result.isPresent());
        assertEquals("Should return the correct NPC", npc, result.get());
    }

    // ========================================================================
    // Result Value Class Tests
    // ========================================================================

    @Test
    public void testObjectSearchResult_ValuesCorrect() {
        net.runelite.api.TileObject obj = mock(net.runelite.api.TileObject.class);
        java.util.List<WorldPoint> path = Collections.singletonList(new WorldPoint(3250, 3250, 0));
        WorldPoint adjacent = new WorldPoint(3251, 3250, 0);

        EntityFinder.ObjectSearchResult result = new EntityFinder.ObjectSearchResult(obj, path, adjacent, 5);

        assertEquals(obj, result.getObject());
        assertEquals(path, result.getPath());
        assertEquals(adjacent, result.getAdjacentTile());
        assertEquals(5, result.getPathCost());
    }

    @Test
    public void testNpcSearchResult_ValuesCorrect() {
        NPC npc = mock(NPC.class);
        java.util.List<WorldPoint> path = Collections.singletonList(new WorldPoint(3250, 3250, 0));
        WorldPoint adjacent = new WorldPoint(3251, 3250, 0);

        EntityFinder.NpcSearchResult result = new EntityFinder.NpcSearchResult(npc, path, adjacent, 3);

        assertEquals(npc, result.getNpc());
        assertEquals(path, result.getPath());
        assertEquals(adjacent, result.getAdjacentTile());
        assertEquals(3, result.getPathCost());
    }
}

