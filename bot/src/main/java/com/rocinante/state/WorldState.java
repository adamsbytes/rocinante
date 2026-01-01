package com.rocinante.state;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

import java.util.*;

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
 *
 * <p><b>Performance Note:</b> All query methods use manual iteration instead of streams
 * to minimize allocations on the critical path. Per REQUIREMENTS.md Section 6.1:
 * state queries must complete in &lt;1ms when cached.
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
        if (nearbyNpcs.isEmpty()) {
            return Collections.emptyList();
        }
        List<NpcSnapshot> result = new ArrayList<>(nearbyNpcs.size());
        for (NpcSnapshot npc : nearbyNpcs) {
            if (npc.getId() == npcId) {
                result.add(npc);
            }
        }
        return result;
    }

    /**
     * Get all NPCs matching any of the specified IDs.
     *
     * @param npcIds the NPC definition IDs
     * @return list of matching NPCs (may be empty)
     */
    public List<NpcSnapshot> getNpcsByIds(int... npcIds) {
        if (nearbyNpcs.isEmpty() || npcIds == null || npcIds.length == 0) {
            return Collections.emptyList();
        }
        Set<Integer> idSet = new HashSet<>(npcIds.length);
        for (int id : npcIds) {
            idSet.add(id);
        }
        List<NpcSnapshot> result = new ArrayList<>(nearbyNpcs.size());
        for (NpcSnapshot npc : nearbyNpcs) {
            if (idSet.contains(npc.getId())) {
                result.add(npc);
            }
        }
        return result;
    }

    /**
     * Get all NPCs matching a name (case-insensitive).
     *
     * @param name the NPC name
     * @return list of matching NPCs (may be empty)
     */
    public List<NpcSnapshot> getNpcsByName(String name) {
        if (name == null || nearbyNpcs.isEmpty()) {
            return Collections.emptyList();
        }
        List<NpcSnapshot> result = new ArrayList<>(nearbyNpcs.size());
        for (NpcSnapshot npc : nearbyNpcs) {
            if (npc.getName() != null && name.equalsIgnoreCase(npc.getName())) {
                result.add(npc);
            }
        }
        return result;
    }

    /**
     * Get an NPC by its unique index.
     *
     * @param index the NPC index
     * @return Optional containing the NPC, or empty if not found
     */
    public Optional<NpcSnapshot> getNpcByIndex(int index) {
        for (NpcSnapshot npc : nearbyNpcs) {
            if (npc.getIndex() == index) {
                return Optional.of(npc);
            }
        }
        return Optional.empty();
    }

    /**
     * Get all NPCs currently targeting the local player.
     *
     * @return list of NPCs targeting the player
     */
    public List<NpcSnapshot> getNpcsTargetingPlayer() {
        if (nearbyNpcs.isEmpty()) {
            return Collections.emptyList();
        }
        List<NpcSnapshot> result = new ArrayList<>(nearbyNpcs.size());
        for (NpcSnapshot npc : nearbyNpcs) {
            if (npc.isTargetingPlayer()) {
                result.add(npc);
            }
        }
        return result;
    }

    /**
     * Get the nearest NPC matching a specific ID.
     *
     * @param npcId      the NPC definition ID
     * @param playerPos  the player's current position
     * @return Optional containing the nearest NPC, or empty if none found
     */
    public Optional<NpcSnapshot> getNearestNpcById(int npcId, WorldPoint playerPos) {
        NpcSnapshot nearest = null;
        int nearestDist = Integer.MAX_VALUE;
        for (NpcSnapshot npc : nearbyNpcs) {
            if (npc.getId() == npcId) {
                int dist = npc.distanceTo(playerPos);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = npc;
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    /**
     * Get the nearest NPC that is targeting the player.
     *
     * @param playerPos the player's current position
     * @return Optional containing the nearest aggressive NPC
     */
    public Optional<NpcSnapshot> getNearestNpcTargetingPlayer(WorldPoint playerPos) {
        NpcSnapshot nearest = null;
        int nearestDist = Integer.MAX_VALUE;
        for (NpcSnapshot npc : nearbyNpcs) {
            if (npc.isTargetingPlayer()) {
                int dist = npc.distanceTo(playerPos);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = npc;
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    /**
     * Get all NPCs within a specific distance.
     *
     * @param playerPos the player's position
     * @param distance  maximum distance in tiles
     * @return list of NPCs within distance
     */
    public List<NpcSnapshot> getNpcsWithinDistance(WorldPoint playerPos, int distance) {
        if (nearbyNpcs.isEmpty()) {
            return Collections.emptyList();
        }
        List<NpcSnapshot> result = new ArrayList<>(nearbyNpcs.size());
        for (NpcSnapshot npc : nearbyNpcs) {
            if (npc.isWithinDistance(playerPos, distance)) {
                result.add(npc);
            }
        }
        return result;
    }

    /**
     * Check if any NPC with the given ID exists nearby.
     *
     * @param npcId the NPC definition ID
     * @return true if at least one matching NPC exists
     */
    public boolean hasNpc(int npcId) {
        for (NpcSnapshot npc : nearbyNpcs) {
            if (npc.getId() == npcId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if any NPC is targeting the player.
     *
     * @return true if any NPC is targeting the player
     */
    public boolean isPlayerTargeted() {
        for (NpcSnapshot npc : nearbyNpcs) {
            if (npc.isTargetingPlayer()) {
                return true;
            }
        }
        return false;
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
        for (NpcSnapshot npc : nearbyNpcs) {
            if (npc.getId() == npcId && position.equals(npc.getWorldPosition())) {
                return Optional.of(npc);
            }
        }
        return Optional.empty();
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
        for (NpcSnapshot npc : nearbyNpcs) {
            if (npcIds.contains(npc.getId()) && position.equals(npc.getWorldPosition())) {
                return Optional.of(npc);
            }
        }
        return Optional.empty();
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
        NpcSnapshot nearest = null;
        int nearestDist = Integer.MAX_VALUE;
        for (NpcSnapshot npc : nearbyNpcs) {
            if (npcIds.contains(npc.getId())) {
                int dist = npc.distanceTo(playerPos);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = npc;
                }
            }
        }
        return Optional.ofNullable(nearest);
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
        if (nearbyObjects.isEmpty()) {
            return Collections.emptyList();
        }
        List<GameObjectSnapshot> result = new ArrayList<>(nearbyObjects.size());
        for (GameObjectSnapshot obj : nearbyObjects) {
            if (obj.getId() == objectId) {
                result.add(obj);
            }
        }
        return result;
    }

    /**
     * Get all objects matching any of the specified IDs.
     *
     * @param objectIds the object definition IDs
     * @return list of matching objects (may be empty)
     */
    public List<GameObjectSnapshot> getObjectsByIds(int... objectIds) {
        if (nearbyObjects.isEmpty() || objectIds == null || objectIds.length == 0) {
            return Collections.emptyList();
        }
        Set<Integer> idSet = new HashSet<>(objectIds.length);
        for (int id : objectIds) {
            idSet.add(id);
        }
        List<GameObjectSnapshot> result = new ArrayList<>(nearbyObjects.size());
        for (GameObjectSnapshot obj : nearbyObjects) {
            if (idSet.contains(obj.getId())) {
                result.add(obj);
            }
        }
        return result;
    }

    /**
     * Get the nearest object matching a specific ID.
     *
     * @param objectId  the object definition ID
     * @param playerPos the player's current position
     * @return Optional containing the nearest object, or empty if none found
     */
    public Optional<GameObjectSnapshot> getNearestObjectById(int objectId, WorldPoint playerPos) {
        GameObjectSnapshot nearest = null;
        int nearestDist = Integer.MAX_VALUE;
        for (GameObjectSnapshot obj : nearbyObjects) {
            if (obj.getId() == objectId) {
                int dist = obj.distanceTo(playerPos);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = obj;
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    /**
     * Get all objects with a specific action available.
     *
     * @param action the action name (case-insensitive)
     * @return list of objects with the action
     */
    public List<GameObjectSnapshot> getObjectsWithAction(String action) {
        if (nearbyObjects.isEmpty() || action == null) {
            return Collections.emptyList();
        }
        List<GameObjectSnapshot> result = new ArrayList<>(nearbyObjects.size());
        for (GameObjectSnapshot obj : nearbyObjects) {
            if (obj.hasAction(action)) {
                result.add(obj);
            }
        }
        return result;
    }

    /**
     * Check if any object with the given ID exists nearby.
     *
     * @param objectId the object definition ID
     * @return true if at least one matching object exists
     */
    public boolean hasObject(int objectId) {
        for (GameObjectSnapshot obj : nearbyObjects) {
            if (obj.getId() == objectId) {
                return true;
            }
        }
        return false;
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
        if (groundItems.isEmpty()) {
            return Collections.emptyList();
        }
        List<GroundItemSnapshot> result = new ArrayList<>(groundItems.size());
        for (GroundItemSnapshot item : groundItems) {
            if (item.getId() == itemId) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * Get the nearest ground item matching a specific ID.
     *
     * @param itemId    the item definition ID
     * @param playerPos the player's current position
     * @return Optional containing the nearest item, or empty if none found
     */
    public Optional<GroundItemSnapshot> getNearestGroundItemById(int itemId, WorldPoint playerPos) {
        GroundItemSnapshot nearest = null;
        int nearestDist = Integer.MAX_VALUE;
        for (GroundItemSnapshot item : groundItems) {
            if (item.getId() == itemId) {
                int dist = item.distanceTo(playerPos);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = item;
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    /**
     * Get all ground items worth more than a threshold.
     *
     * @param minValue minimum GE value in gp
     * @return list of valuable items
     */
    public List<GroundItemSnapshot> getValuableGroundItems(int minValue) {
        if (groundItems.isEmpty()) {
            return Collections.emptyList();
        }
        List<GroundItemSnapshot> result = new ArrayList<>(groundItems.size());
        for (GroundItemSnapshot item : groundItems) {
            if (item.isWorthMoreThan(minValue)) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * Get all ground items sorted by value (highest first).
     *
     * @return sorted list of ground items
     */
    public List<GroundItemSnapshot> getGroundItemsByValue() {
        if (groundItems.isEmpty()) {
            return Collections.emptyList();
        }
        List<GroundItemSnapshot> result = new ArrayList<>(groundItems);
        result.sort(Comparator.comparingLong(GroundItemSnapshot::getTotalGeValue).reversed());
        return result;
    }

    /**
     * Check if any ground item with the given ID exists nearby.
     *
     * @param itemId the item definition ID
     * @return true if at least one matching item exists
     */
    public boolean hasGroundItem(int itemId) {
        for (GroundItemSnapshot item : groundItems) {
            if (item.getId() == itemId) {
                return true;
            }
        }
        return false;
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
        for (PlayerSnapshot p : nearbyPlayers) {
            if (p.getName() != null && name.equalsIgnoreCase(p.getName())) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /**
     * Get all skulled players.
     *
     * @return list of skulled players
     */
    public List<PlayerSnapshot> getSkulledPlayers() {
        if (nearbyPlayers.isEmpty()) {
            return Collections.emptyList();
        }
        List<PlayerSnapshot> result = new ArrayList<>(nearbyPlayers.size());
        for (PlayerSnapshot p : nearbyPlayers) {
            if (p.isSkulled()) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Get all players within a specific distance.
     *
     * @param playerPos the player's position
     * @param distance  maximum distance in tiles
     * @return list of players within distance
     */
    public List<PlayerSnapshot> getPlayersWithinDistance(WorldPoint playerPos, int distance) {
        if (nearbyPlayers.isEmpty()) {
            return Collections.emptyList();
        }
        List<PlayerSnapshot> result = new ArrayList<>(nearbyPlayers.size());
        for (PlayerSnapshot p : nearbyPlayers) {
            if (p.isWithinDistance(playerPos, distance)) {
                result.add(p);
            }
        }
        return result;
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
        if (projectiles.isEmpty()) {
            return Collections.emptyList();
        }
        List<ProjectileSnapshot> result = new ArrayList<>(projectiles.size());
        for (ProjectileSnapshot p : projectiles) {
            if (p.isTargetingPlayer()) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Get projectiles by graphic ID.
     *
     * @param graphicId the projectile graphic ID
     * @return list of matching projectiles
     */
    public List<ProjectileSnapshot> getProjectilesById(int graphicId) {
        if (projectiles.isEmpty()) {
            return Collections.emptyList();
        }
        List<ProjectileSnapshot> result = new ArrayList<>(projectiles.size());
        for (ProjectileSnapshot p : projectiles) {
            if (p.getId() == graphicId) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Check if any projectiles are targeting the player.
     *
     * @return true if projectiles are incoming
     */
    public boolean hasIncomingProjectiles() {
        for (ProjectileSnapshot p : projectiles) {
            if (p.isTargetingPlayer()) {
                return true;
            }
        }
        return false;
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
        if (graphicsObjects.isEmpty() || position == null) {
            return Collections.emptyList();
        }
        List<GraphicsObjectSnapshot> result = new ArrayList<>(graphicsObjects.size());
        for (GraphicsObjectSnapshot go : graphicsObjects) {
            if (position.equals(go.getWorldPosition())) {
                result.add(go);
            }
        }
        return result;
    }

    /**
     * Get graphics objects by ID.
     *
     * @param graphicId the graphics object ID
     * @return list of matching graphics objects
     */
    public List<GraphicsObjectSnapshot> getGraphicsObjectsById(int graphicId) {
        if (graphicsObjects.isEmpty()) {
            return Collections.emptyList();
        }
        List<GraphicsObjectSnapshot> result = new ArrayList<>(graphicsObjects.size());
        for (GraphicsObjectSnapshot go : graphicsObjects) {
            if (go.getId() == graphicId) {
                result.add(go);
            }
        }
        return result;
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
