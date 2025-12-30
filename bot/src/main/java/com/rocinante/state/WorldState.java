package com.rocinante.state;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of the nearby game world at a point in time.
 *
 * Per REQUIREMENTS.md Section 6.2.4, captures:
 * <ul>
 *   <li>Nearby game objects (within 20 tiles)</li>
 *   <li>Nearby NPCs (within 20 tiles), including health bars</li>
 *   <li>Nearby players (within 20 tiles)</li>
 *   <li>Ground items (within 15 tiles)</li>
 *   <li>Currently visible widgets</li>
 *   <li>Projectiles and graphics objects (for prayer flicking)</li>
 * </ul>
 *
 * Uses EXPENSIVE_COMPUTED cache policy (5-tick refresh) per Section 6.1.1.
 */
@Value
@Builder
public class WorldState {

    /**
     * Maximum distance for NPC/object/player scanning.
     */
    public static final int MAX_ENTITY_DISTANCE = 20;

    /**
     * Maximum distance for ground item scanning.
     */
    public static final int MAX_GROUND_ITEM_DISTANCE = 15;

    /**
     * Empty world state for when data is unavailable.
     */
    public static final WorldState EMPTY = WorldState.builder()
            .nearbyNpcs(Collections.emptyList())
            .nearbyObjects(Collections.emptyList())
            .nearbyPlayers(Collections.emptyList())
            .groundItems(Collections.emptyList())
            .projectiles(Collections.emptyList())
            .graphicsObjects(Collections.emptyList())
            .visibleWidgetIds(Collections.emptySet())
            .build();

    /**
     * NPCs within 20 tiles of the player.
     */
    @Builder.Default
    List<NpcSnapshot> nearbyNpcs = Collections.emptyList();

    /**
     * Game objects within 20 tiles of the player.
     */
    @Builder.Default
    List<GameObjectSnapshot> nearbyObjects = Collections.emptyList();

    /**
     * Other players within 20 tiles (not including local player).
     */
    @Builder.Default
    List<PlayerSnapshot> nearbyPlayers = Collections.emptyList();

    /**
     * Ground items within 15 tiles of the player.
     */
    @Builder.Default
    List<GroundItemSnapshot> groundItems = Collections.emptyList();

    /**
     * Projectiles currently in flight.
     */
    @Builder.Default
    List<ProjectileSnapshot> projectiles = Collections.emptyList();

    /**
     * Graphics objects currently visible.
     */
    @Builder.Default
    List<GraphicsObjectSnapshot> graphicsObjects = Collections.emptyList();

    /**
     * Set of currently visible widget group IDs.
     */
    @Builder.Default
    Set<Integer> visibleWidgetIds = Collections.emptySet();

    // ========================================================================
    // NPC Query Methods
    // ========================================================================

    /**
     * Get all NPCs matching a specific ID.
     *
     * @param npcId the NPC definition ID
     * @return list of matching NPCs (may be empty)
     */
    public List<NpcSnapshot> getNpcsById(int npcId) {
        return nearbyNpcs.stream()
                .filter(npc -> npc.getId() == npcId)
                .collect(Collectors.toList());
    }

    /**
     * Get all NPCs matching any of the specified IDs.
     *
     * @param npcIds the NPC definition IDs
     * @return list of matching NPCs (may be empty)
     */
    public List<NpcSnapshot> getNpcsByIds(int... npcIds) {
        Set<Integer> idSet = new HashSet<>();
        for (int id : npcIds) {
            idSet.add(id);
        }
        return nearbyNpcs.stream()
                .filter(npc -> idSet.contains(npc.getId()))
                .collect(Collectors.toList());
    }

    /**
     * Get all NPCs matching a name (case-insensitive).
     *
     * @param name the NPC name
     * @return list of matching NPCs (may be empty)
     */
    public List<NpcSnapshot> getNpcsByName(String name) {
        if (name == null) {
            return Collections.emptyList();
        }
        return nearbyNpcs.stream()
                .filter(npc -> npc.getName() != null && name.equalsIgnoreCase(npc.getName()))
                .collect(Collectors.toList());
    }

    /**
     * Get an NPC by its unique index.
     *
     * @param index the NPC index
     * @return Optional containing the NPC, or empty if not found
     */
    public Optional<NpcSnapshot> getNpcByIndex(int index) {
        return nearbyNpcs.stream()
                .filter(npc -> npc.getIndex() == index)
                .findFirst();
    }

    /**
     * Get all NPCs currently targeting the local player.
     *
     * @return list of NPCs targeting the player
     */
    public List<NpcSnapshot> getNpcsTargetingPlayer() {
        return nearbyNpcs.stream()
                .filter(NpcSnapshot::isTargetingPlayer)
                .collect(Collectors.toList());
    }

    /**
     * Get the nearest NPC matching a specific ID.
     *
     * @param npcId      the NPC definition ID
     * @param playerPos  the player's current position
     * @return Optional containing the nearest NPC, or empty if none found
     */
    public Optional<NpcSnapshot> getNearestNpcById(int npcId, WorldPoint playerPos) {
        return getNpcsById(npcId).stream()
                .min(Comparator.comparingInt(npc -> npc.distanceTo(playerPos)));
    }

    /**
     * Get the nearest NPC that is targeting the player.
     *
     * @param playerPos the player's current position
     * @return Optional containing the nearest aggressive NPC
     */
    public Optional<NpcSnapshot> getNearestNpcTargetingPlayer(WorldPoint playerPos) {
        return getNpcsTargetingPlayer().stream()
                .min(Comparator.comparingInt(npc -> npc.distanceTo(playerPos)));
    }

    /**
     * Get all NPCs within a specific distance.
     *
     * @param playerPos the player's position
     * @param distance  maximum distance in tiles
     * @return list of NPCs within distance
     */
    public List<NpcSnapshot> getNpcsWithinDistance(WorldPoint playerPos, int distance) {
        return nearbyNpcs.stream()
                .filter(npc -> npc.isWithinDistance(playerPos, distance))
                .collect(Collectors.toList());
    }

    /**
     * Check if any NPC with the given ID exists nearby.
     *
     * @param npcId the NPC definition ID
     * @return true if at least one matching NPC exists
     */
    public boolean hasNpc(int npcId) {
        return nearbyNpcs.stream().anyMatch(npc -> npc.getId() == npcId);
    }

    /**
     * Check if any NPC is targeting the player.
     *
     * @return true if any NPC is targeting the player
     */
    public boolean isPlayerTargeted() {
        return nearbyNpcs.stream().anyMatch(NpcSnapshot::isTargetingPlayer);
    }

    /**
     * Get an NPC at a specific world position with a specific ID.
     * Useful for validating that a target NPC hasn't moved.
     *
     * @param npcId    the NPC definition ID
     * @param position the expected world position
     * @return Optional containing the NPC if found at that position
     */
    public Optional<NpcSnapshot> getNpcByPosition(int npcId, WorldPoint position) {
        if (position == null) {
            return Optional.empty();
        }
        return nearbyNpcs.stream()
                .filter(npc -> npc.getId() == npcId)
                .filter(npc -> position.equals(npc.getWorldPosition()))
                .findFirst();
    }

    /**
     * Get an NPC at a specific world position with any of the specified IDs.
     *
     * @param npcIds   the NPC definition IDs
     * @param position the expected world position
     * @return Optional containing the NPC if found at that position
     */
    public Optional<NpcSnapshot> getNpcByPosition(Set<Integer> npcIds, WorldPoint position) {
        if (position == null || npcIds == null || npcIds.isEmpty()) {
            return Optional.empty();
        }
        return nearbyNpcs.stream()
                .filter(npc -> npcIds.contains(npc.getId()))
                .filter(npc -> position.equals(npc.getWorldPosition()))
                .findFirst();
    }

    /**
     * Get the nearest NPC matching any of the specified IDs.
     *
     * @param npcIds    the NPC definition IDs
     * @param playerPos the player's current position
     * @return Optional containing the nearest NPC, or empty if none found
     */
    public Optional<NpcSnapshot> getNearestNpcByIds(Set<Integer> npcIds, WorldPoint playerPos) {
        if (npcIds == null || npcIds.isEmpty()) {
            return Optional.empty();
        }
        return nearbyNpcs.stream()
                .filter(npc -> npcIds.contains(npc.getId()))
                .min(Comparator.comparingInt(npc -> npc.distanceTo(playerPos)));
    }

    // ========================================================================
    // Game Object Query Methods
    // ========================================================================

    /**
     * Get all objects matching a specific ID.
     *
     * @param objectId the object definition ID
     * @return list of matching objects (may be empty)
     */
    public List<GameObjectSnapshot> getObjectsById(int objectId) {
        return nearbyObjects.stream()
                .filter(obj -> obj.getId() == objectId)
                .collect(Collectors.toList());
    }

    /**
     * Get all objects matching any of the specified IDs.
     *
     * @param objectIds the object definition IDs
     * @return list of matching objects (may be empty)
     */
    public List<GameObjectSnapshot> getObjectsByIds(int... objectIds) {
        Set<Integer> idSet = new HashSet<>();
        for (int id : objectIds) {
            idSet.add(id);
        }
        return nearbyObjects.stream()
                .filter(obj -> idSet.contains(obj.getId()))
                .collect(Collectors.toList());
    }

    /**
     * Get the nearest object matching a specific ID.
     *
     * @param objectId  the object definition ID
     * @param playerPos the player's current position
     * @return Optional containing the nearest object, or empty if none found
     */
    public Optional<GameObjectSnapshot> getNearestObjectById(int objectId, WorldPoint playerPos) {
        return getObjectsById(objectId).stream()
                .min(Comparator.comparingInt(obj -> obj.distanceTo(playerPos)));
    }

    /**
     * Get all objects with a specific action available.
     *
     * @param action the action name (case-insensitive)
     * @return list of objects with the action
     */
    public List<GameObjectSnapshot> getObjectsWithAction(String action) {
        return nearbyObjects.stream()
                .filter(obj -> obj.hasAction(action))
                .collect(Collectors.toList());
    }

    /**
     * Check if any object with the given ID exists nearby.
     *
     * @param objectId the object definition ID
     * @return true if at least one matching object exists
     */
    public boolean hasObject(int objectId) {
        return nearbyObjects.stream().anyMatch(obj -> obj.getId() == objectId);
    }

    // ========================================================================
    // Ground Item Query Methods
    // ========================================================================

    /**
     * Get all ground items matching a specific ID.
     *
     * @param itemId the item definition ID
     * @return list of matching items (may be empty)
     */
    public List<GroundItemSnapshot> getGroundItemsById(int itemId) {
        return groundItems.stream()
                .filter(item -> item.getId() == itemId)
                .collect(Collectors.toList());
    }

    /**
     * Get the nearest ground item matching a specific ID.
     *
     * @param itemId    the item definition ID
     * @param playerPos the player's current position
     * @return Optional containing the nearest item, or empty if none found
     */
    public Optional<GroundItemSnapshot> getNearestGroundItemById(int itemId, WorldPoint playerPos) {
        return getGroundItemsById(itemId).stream()
                .min(Comparator.comparingInt(item -> item.distanceTo(playerPos)));
    }

    /**
     * Get all ground items worth more than a threshold.
     *
     * @param minValue minimum GE value in gp
     * @return list of valuable items
     */
    public List<GroundItemSnapshot> getValuableGroundItems(int minValue) {
        return groundItems.stream()
                .filter(item -> item.isWorthMoreThan(minValue))
                .collect(Collectors.toList());
    }

    /**
     * Get all ground items sorted by value (highest first).
     *
     * @return sorted list of ground items
     */
    public List<GroundItemSnapshot> getGroundItemsByValue() {
        return groundItems.stream()
                .sorted(Comparator.comparingLong(GroundItemSnapshot::getTotalGeValue).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Check if any ground item with the given ID exists nearby.
     *
     * @param itemId the item definition ID
     * @return true if at least one matching item exists
     */
    public boolean hasGroundItem(int itemId) {
        return groundItems.stream().anyMatch(item -> item.getId() == itemId);
    }

    // ========================================================================
    // Player Query Methods
    // ========================================================================

    /**
     * Get a player by name (case-insensitive).
     *
     * @param name the player name
     * @return Optional containing the player, or empty if not found
     */
    public Optional<PlayerSnapshot> getPlayerByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return nearbyPlayers.stream()
                .filter(p -> p.getName() != null && name.equalsIgnoreCase(p.getName()))
                .findFirst();
    }

    /**
     * Get all skulled players.
     *
     * @return list of skulled players
     */
    public List<PlayerSnapshot> getSkulledPlayers() {
        return nearbyPlayers.stream()
                .filter(PlayerSnapshot::isSkulled)
                .collect(Collectors.toList());
    }

    /**
     * Get all players within a specific distance.
     *
     * @param playerPos the player's position
     * @param distance  maximum distance in tiles
     * @return list of players within distance
     */
    public List<PlayerSnapshot> getPlayersWithinDistance(WorldPoint playerPos, int distance) {
        return nearbyPlayers.stream()
                .filter(p -> p.isWithinDistance(playerPos, distance))
                .collect(Collectors.toList());
    }

    // ========================================================================
    // Projectile Query Methods
    // ========================================================================

    /**
     * Get all projectiles targeting the local player.
     *
     * @return list of projectiles aimed at the player
     */
    public List<ProjectileSnapshot> getProjectilesTargetingPlayer() {
        return projectiles.stream()
                .filter(ProjectileSnapshot::isTargetingPlayer)
                .collect(Collectors.toList());
    }

    /**
     * Get projectiles by graphic ID.
     *
     * @param graphicId the projectile graphic ID
     * @return list of matching projectiles
     */
    public List<ProjectileSnapshot> getProjectilesById(int graphicId) {
        return projectiles.stream()
                .filter(p -> p.getId() == graphicId)
                .collect(Collectors.toList());
    }

    /**
     * Check if any projectiles are targeting the player.
     *
     * @return true if projectiles are incoming
     */
    public boolean hasIncomingProjectiles() {
        return projectiles.stream().anyMatch(ProjectileSnapshot::isTargetingPlayer);
    }

    // ========================================================================
    // Graphics Object Query Methods
    // ========================================================================

    /**
     * Get all graphics objects at a specific position.
     *
     * @param position the world position
     * @return list of graphics objects at that position
     */
    public List<GraphicsObjectSnapshot> getGraphicsObjectsAt(WorldPoint position) {
        return graphicsObjects.stream()
                .filter(go -> position.equals(go.getWorldPosition()))
                .collect(Collectors.toList());
    }

    /**
     * Get graphics objects by ID.
     *
     * @param graphicId the graphics object ID
     * @return list of matching graphics objects
     */
    public List<GraphicsObjectSnapshot> getGraphicsObjectsById(int graphicId) {
        return graphicsObjects.stream()
                .filter(go -> go.getId() == graphicId)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // Widget Query Methods
    // ========================================================================

    /**
     * Check if a specific widget group is visible.
     *
     * @param widgetGroupId the widget group ID
     * @return true if the widget is visible
     */
    public boolean isWidgetVisible(int widgetGroupId) {
        return visibleWidgetIds.contains(widgetGroupId);
    }

    /**
     * Check if any of the specified widget groups are visible.
     *
     * @param widgetGroupIds the widget group IDs
     * @return true if any is visible
     */
    public boolean isAnyWidgetVisible(int... widgetGroupIds) {
        for (int id : widgetGroupIds) {
            if (visibleWidgetIds.contains(id)) {
                return true;
            }
        }
        return false;
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Get total count of all tracked entities.
     *
     * @return total entity count
     */
    public int getTotalEntityCount() {
        return nearbyNpcs.size() + nearbyObjects.size() + nearbyPlayers.size() +
                groundItems.size() + projectiles.size() + graphicsObjects.size();
    }

    /**
     * Check if the world state is empty (no entities).
     *
     * @return true if no entities are tracked
     */
    public boolean isEmpty() {
        return getTotalEntityCount() == 0;
    }

    /**
     * Get a summary string for logging.
     *
     * @return summary of world state
     */
    public String getSummary() {
        return String.format(
                "WorldState[npcs=%d, objects=%d, players=%d, items=%d, projectiles=%d, graphics=%d, widgets=%d]",
                nearbyNpcs.size(),
                nearbyObjects.size(),
                nearbyPlayers.size(),
                groundItems.size(),
                projectiles.size(),
                graphicsObjects.size(),
                visibleWidgetIds.size()
        );
    }
}

