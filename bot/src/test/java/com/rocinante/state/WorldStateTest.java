package com.rocinante.state;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Unit tests for WorldState class.
 */
public class WorldStateTest {

    // ========================================================================
    // Empty State
    // ========================================================================

    @Test
    public void testEmptyState() {
        WorldState empty = WorldState.EMPTY;

        assertTrue(empty.getNearbyNpcs().isEmpty());
        assertTrue(empty.getNearbyObjects().isEmpty());
        assertTrue(empty.getNearbyPlayers().isEmpty());
        assertTrue(empty.getGroundItems().isEmpty());
        assertTrue(empty.getProjectiles().isEmpty());
        assertTrue(empty.getGraphicsObjects().isEmpty());
        assertTrue(empty.getVisibleWidgetIds().isEmpty());
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.getTotalEntityCount());
    }

    @Test
    public void testEmptyStateIsSingleton() {
        assertSame(WorldState.EMPTY, WorldState.EMPTY);
    }

    // ========================================================================
    // NPC Query Methods
    // ========================================================================

    @Test
    public void testGetNpcsById() {
        NpcSnapshot goblin1 = createNpcSnapshot(0, 100, "Goblin", 5);
        NpcSnapshot goblin2 = createNpcSnapshot(1, 100, "Goblin", 5);
        NpcSnapshot chicken = createNpcSnapshot(2, 200, "Chicken", 1);

        WorldState state = WorldState.builder()
                .nearbyNpcs(Arrays.asList(goblin1, goblin2, chicken))
                .build();

        List<NpcSnapshot> goblins = state.getNpcsById(100);
        assertEquals(2, goblins.size());

        List<NpcSnapshot> chickens = state.getNpcsById(200);
        assertEquals(1, chickens.size());

        List<NpcSnapshot> cows = state.getNpcsById(300);
        assertTrue(cows.isEmpty());
    }

    @Test
    public void testGetNpcsByIds() {
        NpcSnapshot goblin = createNpcSnapshot(0, 100, "Goblin", 5);
        NpcSnapshot chicken = createNpcSnapshot(1, 200, "Chicken", 1);
        NpcSnapshot cow = createNpcSnapshot(2, 300, "Cow", 2);

        WorldState state = WorldState.builder()
                .nearbyNpcs(Arrays.asList(goblin, chicken, cow))
                .build();

        List<NpcSnapshot> found = state.getNpcsByIds(100, 200);
        assertEquals(2, found.size());
    }

    @Test
    public void testGetNpcsByName() {
        NpcSnapshot goblin1 = createNpcSnapshot(0, 100, "Goblin", 5);
        NpcSnapshot goblin2 = createNpcSnapshot(1, 101, "Goblin", 7);
        NpcSnapshot chicken = createNpcSnapshot(2, 200, "Chicken", 1);

        WorldState state = WorldState.builder()
                .nearbyNpcs(Arrays.asList(goblin1, goblin2, chicken))
                .build();

        List<NpcSnapshot> goblins = state.getNpcsByName("Goblin");
        assertEquals(2, goblins.size());

        // Case insensitive
        List<NpcSnapshot> goblinsLower = state.getNpcsByName("goblin");
        assertEquals(2, goblinsLower.size());
    }

    @Test
    public void testGetNpcByIndex() {
        NpcSnapshot goblin = createNpcSnapshot(42, 100, "Goblin", 5);

        WorldState state = WorldState.builder()
                .nearbyNpcs(Collections.singletonList(goblin))
                .build();

        Optional<NpcSnapshot> found = state.getNpcByIndex(42);
        assertTrue(found.isPresent());
        assertEquals("Goblin", found.get().getName());

        Optional<NpcSnapshot> notFound = state.getNpcByIndex(99);
        assertFalse(notFound.isPresent());
    }

    @Test
    public void testGetNpcsTargetingPlayer() {
        NpcSnapshot aggressive = createNpcSnapshotTargeting(0, 100, "Goblin", true);
        NpcSnapshot passive = createNpcSnapshotTargeting(1, 100, "Goblin", false);

        WorldState state = WorldState.builder()
                .nearbyNpcs(Arrays.asList(aggressive, passive))
                .build();

        List<NpcSnapshot> targeting = state.getNpcsTargetingPlayer();
        assertEquals(1, targeting.size());
        assertTrue(targeting.get(0).isTargetingPlayer());
    }

    @Test
    public void testGetNearestNpcById() {
        WorldPoint playerPos = new WorldPoint(3200, 3200, 0);

        NpcSnapshot far = createNpcSnapshotAt(0, 100, "Goblin", new WorldPoint(3210, 3200, 0));
        NpcSnapshot near = createNpcSnapshotAt(1, 100, "Goblin", new WorldPoint(3202, 3200, 0));

        WorldState state = WorldState.builder()
                .nearbyNpcs(Arrays.asList(far, near))
                .build();

        Optional<NpcSnapshot> nearest = state.getNearestNpcById(100, playerPos);
        assertTrue(nearest.isPresent());
        assertEquals(1, nearest.get().getIndex());
    }

    @Test
    public void testHasNpc() {
        NpcSnapshot goblin = createNpcSnapshot(0, 100, "Goblin", 5);

        WorldState state = WorldState.builder()
                .nearbyNpcs(Collections.singletonList(goblin))
                .build();

        assertTrue(state.hasNpc(100));
        assertFalse(state.hasNpc(200));
    }

    @Test
    public void testIsPlayerTargeted() {
        NpcSnapshot aggressive = createNpcSnapshotTargeting(0, 100, "Goblin", true);

        WorldState stateWithAggro = WorldState.builder()
                .nearbyNpcs(Collections.singletonList(aggressive))
                .build();

        assertTrue(stateWithAggro.isPlayerTargeted());

        WorldState stateNoAggro = WorldState.builder()
                .nearbyNpcs(Collections.emptyList())
                .build();

        assertFalse(stateNoAggro.isPlayerTargeted());
    }

    // ========================================================================
    // Game Object Query Methods
    // ========================================================================

    @Test
    public void testGetObjectsById() {
        GameObjectSnapshot tree1 = createObjectSnapshot(1000, "Tree");
        GameObjectSnapshot tree2 = createObjectSnapshot(1000, "Tree");
        GameObjectSnapshot rock = createObjectSnapshot(2000, "Rock");

        WorldState state = WorldState.builder()
                .nearbyObjects(Arrays.asList(tree1, tree2, rock))
                .build();

        List<GameObjectSnapshot> trees = state.getObjectsById(1000);
        assertEquals(2, trees.size());

        List<GameObjectSnapshot> rocks = state.getObjectsById(2000);
        assertEquals(1, rocks.size());
    }

    @Test
    public void testGetObjectsWithAction() {
        GameObjectSnapshot tree = createObjectSnapshotWithActions(1000, "Tree", Arrays.asList("Chop down", "Examine"));
        GameObjectSnapshot rock = createObjectSnapshotWithActions(2000, "Rock", Arrays.asList("Mine", "Examine"));

        WorldState state = WorldState.builder()
                .nearbyObjects(Arrays.asList(tree, rock))
                .build();

        List<GameObjectSnapshot> choppable = state.getObjectsWithAction("Chop down");
        assertEquals(1, choppable.size());
        assertEquals("Tree", choppable.get(0).getName());

        List<GameObjectSnapshot> examinable = state.getObjectsWithAction("Examine");
        assertEquals(2, examinable.size());
    }

    @Test
    public void testHasObject() {
        GameObjectSnapshot tree = createObjectSnapshot(1000, "Tree");

        WorldState state = WorldState.builder()
                .nearbyObjects(Collections.singletonList(tree))
                .build();

        assertTrue(state.hasObject(1000));
        assertFalse(state.hasObject(2000));
    }

    // ========================================================================
    // Ground Item Query Methods
    // ========================================================================

    @Test
    public void testGetGroundItemsById() {
        GroundItemSnapshot bones1 = createGroundItemSnapshot(526, "Bones", 1, 100);
        GroundItemSnapshot bones2 = createGroundItemSnapshot(526, "Bones", 1, 100);
        GroundItemSnapshot gold = createGroundItemSnapshot(995, "Coins", 100, 1);

        WorldState state = WorldState.builder()
                .groundItems(Arrays.asList(bones1, bones2, gold))
                .build();

        List<GroundItemSnapshot> bonesList = state.getGroundItemsById(526);
        assertEquals(2, bonesList.size());
    }

    @Test
    public void testGetValuableGroundItems() {
        GroundItemSnapshot cheap = createGroundItemSnapshot(526, "Bones", 1, 100);
        GroundItemSnapshot expensive = createGroundItemSnapshot(995, "Coins", 10000, 1);

        WorldState state = WorldState.builder()
                .groundItems(Arrays.asList(cheap, expensive))
                .build();

        List<GroundItemSnapshot> valuable = state.getValuableGroundItems(1000);
        assertEquals(1, valuable.size());
        assertEquals("Coins", valuable.get(0).getName());
    }

    @Test
    public void testGetGroundItemsByValue() {
        GroundItemSnapshot cheap = createGroundItemSnapshot(526, "Bones", 1, 100);
        GroundItemSnapshot medium = createGroundItemSnapshot(1234, "Item", 1, 5000);
        GroundItemSnapshot expensive = createGroundItemSnapshot(995, "Coins", 10000, 1);

        WorldState state = WorldState.builder()
                .groundItems(Arrays.asList(cheap, medium, expensive))
                .build();

        List<GroundItemSnapshot> sorted = state.getGroundItemsByValue();
        assertEquals(3, sorted.size());
        assertEquals("Coins", sorted.get(0).getName());
        assertEquals("Item", sorted.get(1).getName());
        assertEquals("Bones", sorted.get(2).getName());
    }

    @Test
    public void testHasGroundItem() {
        GroundItemSnapshot bones = createGroundItemSnapshot(526, "Bones", 1, 100);

        WorldState state = WorldState.builder()
                .groundItems(Collections.singletonList(bones))
                .build();

        assertTrue(state.hasGroundItem(526));
        assertFalse(state.hasGroundItem(995));
    }

    // ========================================================================
    // Projectile Query Methods
    // ========================================================================

    @Test
    public void testGetProjectilesTargetingPlayer() {
        ProjectileSnapshot incoming = createProjectileSnapshot(100, true);
        ProjectileSnapshot notIncoming = createProjectileSnapshot(100, false);

        WorldState state = WorldState.builder()
                .projectiles(Arrays.asList(incoming, notIncoming))
                .build();

        List<ProjectileSnapshot> targeting = state.getProjectilesTargetingPlayer();
        assertEquals(1, targeting.size());
        assertTrue(targeting.get(0).isTargetingPlayer());
    }

    @Test
    public void testHasIncomingProjectiles() {
        ProjectileSnapshot incoming = createProjectileSnapshot(100, true);

        WorldState stateWithIncoming = WorldState.builder()
                .projectiles(Collections.singletonList(incoming))
                .build();

        assertTrue(stateWithIncoming.hasIncomingProjectiles());

        WorldState stateNoIncoming = WorldState.builder()
                .projectiles(Collections.emptyList())
                .build();

        assertFalse(stateNoIncoming.hasIncomingProjectiles());
    }

    // ========================================================================
    // Widget Query Methods
    // ========================================================================

    @Test
    public void testIsWidgetVisible() {
        WorldState state = WorldState.builder()
                .visibleWidgetIds(new HashSet<>(Arrays.asList(149, 161, 218)))
                .build();

        assertTrue(state.isWidgetVisible(149));  // Inventory
        assertTrue(state.isWidgetVisible(161));  // Prayer
        assertFalse(state.isWidgetVisible(12));  // Bank
    }

    @Test
    public void testIsAnyWidgetVisible() {
        WorldState state = WorldState.builder()
                .visibleWidgetIds(new HashSet<>(Arrays.asList(149, 161)))
                .build();

        assertTrue(state.isAnyWidgetVisible(12, 149, 218));  // 149 is visible
        assertFalse(state.isAnyWidgetVisible(12, 387));      // Neither visible
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    @Test
    public void testGetTotalEntityCount() {
        WorldState state = WorldState.builder()
                .nearbyNpcs(Arrays.asList(createNpcSnapshot(0, 100, "A", 1), createNpcSnapshot(1, 101, "B", 1)))
                .nearbyObjects(Collections.singletonList(createObjectSnapshot(1000, "Tree")))
                .groundItems(Collections.singletonList(createGroundItemSnapshot(526, "Bones", 1, 100)))
                .projectiles(Collections.singletonList(createProjectileSnapshot(100, true)))
                .build();

        assertEquals(5, state.getTotalEntityCount());
    }

    @Test
    public void testIsEmpty() {
        assertTrue(WorldState.EMPTY.isEmpty());

        WorldState withNpc = WorldState.builder()
                .nearbyNpcs(Collections.singletonList(createNpcSnapshot(0, 100, "A", 1)))
                .build();

        assertFalse(withNpc.isEmpty());
    }

    @Test
    public void testGetSummary() {
        WorldState state = WorldState.builder()
                .nearbyNpcs(Collections.singletonList(createNpcSnapshot(0, 100, "A", 1)))
                .nearbyObjects(Collections.singletonList(createObjectSnapshot(1000, "Tree")))
                .build();

        String summary = state.getSummary();
        assertTrue(summary.contains("npcs=1"));
        assertTrue(summary.contains("objects=1"));
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private NpcSnapshot createNpcSnapshot(int index, int id, String name, int combatLevel) {
        return NpcSnapshot.builder()
                .index(index)
                .id(id)
                .name(name)
                .combatLevel(combatLevel)
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .healthRatio(-1)
                .healthScale(-1)
                .animationId(-1)
                .interactingIndex(-1)
                .targetingPlayer(false)
                .isDead(false)
                .size(1)
                .build();
    }

    private NpcSnapshot createNpcSnapshotTargeting(int index, int id, String name, boolean targeting) {
        return NpcSnapshot.builder()
                .index(index)
                .id(id)
                .name(name)
                .combatLevel(5)
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .healthRatio(-1)
                .healthScale(-1)
                .animationId(-1)
                .interactingIndex(-1)
                .targetingPlayer(targeting)
                .isDead(false)
                .size(1)
                .build();
    }

    private NpcSnapshot createNpcSnapshotAt(int index, int id, String name, WorldPoint pos) {
        return NpcSnapshot.builder()
                .index(index)
                .id(id)
                .name(name)
                .combatLevel(5)
                .worldPosition(pos)
                .healthRatio(-1)
                .healthScale(-1)
                .animationId(-1)
                .interactingIndex(-1)
                .targetingPlayer(false)
                .isDead(false)
                .size(1)
                .build();
    }

    private GameObjectSnapshot createObjectSnapshot(int id, String name) {
        return GameObjectSnapshot.builder()
                .id(id)
                .name(name)
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .plane(0)
                .sizeX(1)
                .sizeY(1)
                .build();
    }

    private GameObjectSnapshot createObjectSnapshotWithActions(int id, String name, List<String> actions) {
        return GameObjectSnapshot.builder()
                .id(id)
                .name(name)
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .plane(0)
                .actions(actions)
                .sizeX(1)
                .sizeY(1)
                .build();
    }

    private GroundItemSnapshot createGroundItemSnapshot(int id, String name, int quantity, int gePrice) {
        return GroundItemSnapshot.builder()
                .id(id)
                .name(name)
                .quantity(quantity)
                .gePrice(gePrice)
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .build();
    }

    private ProjectileSnapshot createProjectileSnapshot(int id, boolean targetingPlayer) {
        return ProjectileSnapshot.builder()
                .id(id)
                .targetingPlayer(targetingPlayer)
                .startPosition(new WorldPoint(3200, 3200, 0))
                .endPosition(new WorldPoint(3205, 3205, 0))
                .cycleStart(0)
                .cycleEnd(100)
                .build();
    }
}

