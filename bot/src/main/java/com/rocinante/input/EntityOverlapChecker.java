package com.rocinante.input;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.Constants;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Utility for checking what entities/objects are at a screen coordinate.
 *
 * <p>This is crucial for preventing misclicks when an NPC or player is
 * standing "in front of" the intended click target. OSRS renders entities
 * with overlapping clickboxes based on render order, so clicking a point
 * may interact with an unintended entity.
 *
 * <p>Usage:
 * <pre>{@code
 * // Check what's at a click point before clicking
 * OverlapResult result = overlapChecker.checkPoint(clickPoint, targetObject);
 * if (result.hasBlockingEntity()) {
 *     // Use right-click menu or find alternative point
 * } else {
 *     // Safe to left-click
 * }
 * }</pre>
 */
@Slf4j
@Singleton
public class EntityOverlapChecker {

    private final Client client;

    @Inject
    public EntityOverlapChecker(Client client) {
        this.client = client;
        log.info("EntityOverlapChecker initialized");
    }

    // ========================================================================
    // Main API
    // ========================================================================

    /**
     * Check what entities/objects have clickboxes containing the given point.
     *
     * @param screenPoint the screen coordinate to check
     * @param intendedTarget the object we WANT to click (null if clicking NPC/player)
     * @return analysis of what would be clicked at this point
     */
    public OverlapResult checkPointForObject(Point screenPoint, @Nullable TileObject intendedTarget) {
        List<ClickableEntity> entitiesAtPoint = findEntitiesAtPoint(screenPoint);
        
        // Check if any entity would intercept the click
        boolean targetFound = false;
        ClickableEntity blockingEntity = null;
        
        for (ClickableEntity entity : entitiesAtPoint) {
            if (entity.getType() == EntityType.OBJECT && entity.getTileObject() == intendedTarget) {
                targetFound = true;
                // Target is in the list - but is it the TOP entity?
                if (entitiesAtPoint.indexOf(entity) == 0) {
                    // Target is topmost - safe to click
                    return new OverlapResult(false, null, targetFound, entitiesAtPoint);
                }
            }
            
            // First non-target entity is the blocker
            if (blockingEntity == null && 
                (entity.getType() != EntityType.OBJECT || entity.getTileObject() != intendedTarget)) {
                blockingEntity = entity;
            }
        }
        
        return new OverlapResult(blockingEntity != null, blockingEntity, targetFound, entitiesAtPoint);
    }

    /**
     * Check what entities/objects have clickboxes containing the given point
     * when trying to click an NPC.
     *
     * @param screenPoint the screen coordinate to check
     * @param intendedNpc the NPC we WANT to click
     * @return analysis of what would be clicked at this point
     */
    public OverlapResult checkPointForNpc(Point screenPoint, @Nullable NPC intendedNpc) {
        List<ClickableEntity> entitiesAtPoint = findEntitiesAtPoint(screenPoint);
        
        boolean targetFound = false;
        ClickableEntity blockingEntity = null;
        
        for (ClickableEntity entity : entitiesAtPoint) {
            if (entity.getType() == EntityType.NPC && entity.getNpc() == intendedNpc) {
                targetFound = true;
                if (entitiesAtPoint.indexOf(entity) == 0) {
                    return new OverlapResult(false, null, targetFound, entitiesAtPoint);
                }
            }
            
            if (blockingEntity == null && 
                (entity.getType() != EntityType.NPC || entity.getNpc() != intendedNpc)) {
                blockingEntity = entity;
            }
        }
        
        return new OverlapResult(blockingEntity != null, blockingEntity, targetFound, entitiesAtPoint);
    }

    /**
     * Check if any NPC or player is blocking a click at the given point.
     * Used for ground item clicks where we don't have a specific target object/NPC.
     *
     * @param screenPoint the screen coordinate to check
     * @return analysis of what would be clicked at this point
     */
    public OverlapResult checkPointAtLocation(Point screenPoint) {
        List<ClickableEntity> entitiesAtPoint = findEntitiesAtPoint(screenPoint);
        
        // For ground items, any NPC or player at the point is a blocker
        ClickableEntity blockingEntity = null;
        
        for (ClickableEntity entity : entitiesAtPoint) {
            // NPCs and players block ground item clicks
            if (entity.getType() == EntityType.NPC || entity.getType() == EntityType.PLAYER) {
                blockingEntity = entity;
                break; // First (topmost) entity is the blocker
            }
        }
        
        return new OverlapResult(blockingEntity != null, blockingEntity, true, entitiesAtPoint);
    }

    /**
     * Find an unoccluded click point on the target object.
     * Samples multiple points on the object's clickbox to find one that isn't blocked.
     *
     * @param targetObject the object to find a clear click point for
     * @param maxAttempts maximum number of points to try
     * @return a clear point, or null if all points are blocked
     */
    @Nullable
    public Point findClearClickPoint(TileObject targetObject, int maxAttempts) {
        Shape clickbox = targetObject.getClickbox();
        if (clickbox == null) {
            return null;
        }
        
        Rectangle bounds = clickbox.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return null;
        }
        
        // Try multiple points across the clickbox
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Point candidate = ClickPointCalculator.getGaussianClickPoint(bounds);
            
            // Verify it's within the actual shape (not just bounds)
            if (!clickbox.contains(candidate)) {
                continue;
            }
            
            OverlapResult result = checkPointForObject(candidate, targetObject);
            if (!result.hasBlockingEntity()) {
                log.debug("Found clear click point at ({}, {}) on attempt {}", 
                        candidate.x, candidate.y, attempt + 1);
                return candidate;
            }
        }
        
        log.debug("Could not find clear click point after {} attempts", maxAttempts);
        return null;
    }

    /**
     * Find an unoccluded click point on the target NPC.
     *
     * @param targetNpc the NPC to find a clear click point for
     * @param maxAttempts maximum number of points to try
     * @return a clear point, or null if all points are blocked
     */
    @Nullable
    public Point findClearClickPoint(NPC targetNpc, int maxAttempts) {
        Shape hull = targetNpc.getConvexHull();
        if (hull == null) {
            return null;
        }
        
        Rectangle bounds = hull.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return null;
        }
        
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Point candidate = ClickPointCalculator.getGaussianClickPoint(bounds);
            
            if (!hull.contains(candidate)) {
                continue;
            }
            
            OverlapResult result = checkPointForNpc(candidate, targetNpc);
            if (!result.hasBlockingEntity()) {
                log.debug("Found clear NPC click point at ({}, {}) on attempt {}", 
                        candidate.x, candidate.y, attempt + 1);
                return candidate;
            }
        }
        
        log.debug("Could not find clear NPC click point after {} attempts", maxAttempts);
        return null;
    }

    // ========================================================================
    // Entity Detection
    // ========================================================================

    /**
     * Find all clickable entities at a screen point.
     * Returns them in render order (topmost first - what would be clicked).
     */
    private List<ClickableEntity> findEntitiesAtPoint(Point point) {
        List<ClickableEntity> entities = new ArrayList<>();
        
        // Check NPCs
        for (NPC npc : client.getNpcs()) {
            if (npc == null || npc.isDead()) {
                continue;
            }
            
            Shape hull = npc.getConvexHull();
            if (hull != null && hull.contains(point)) {
                entities.add(new ClickableEntity(
                        EntityType.NPC,
                        npc.getName(),
                        npc.getId(),
                        npc,
                        null,
                        null,
                        hull.getBounds(),
                        getRenderPriority(npc)
                ));
            }
        }
        
        // Check other players
        for (Player player : client.getPlayers()) {
            if (player == null || player == client.getLocalPlayer()) {
                continue;
            }
            
            Shape hull = player.getConvexHull();
            if (hull != null && hull.contains(point)) {
                entities.add(new ClickableEntity(
                        EntityType.PLAYER,
                        player.getName(),
                        -1,
                        null,
                        player,
                        null,
                        hull.getBounds(),
                        getRenderPriority(player)
                ));
            }
        }
        
        // Check game objects (only ones with clickboxes containing the point)
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer != null) {
            WorldPoint playerPos = localPlayer.getWorldLocation();
            if (playerPos != null) {
                addObjectsAtPoint(entities, point, playerPos);
            }
        }
        
        // Sort by render priority (higher = rendered on top = clicked first)
        entities.sort(Comparator.comparingInt(ClickableEntity::getRenderPriority).reversed());
        
        return entities;
    }

    /**
     * Add game objects at a point to the entity list.
     */
    private void addObjectsAtPoint(List<ClickableEntity> entities, Point point, WorldPoint playerPos) {
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = playerPos.getPlane();
        
        for (int x = 0; x < Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[plane][x][y];
                if (tile == null) {
                    continue;
                }
                
                // Check game objects
                GameObject[] gameObjects = tile.getGameObjects();
                if (gameObjects != null) {
                    for (GameObject obj : gameObjects) {
                        if (obj == null) {
                            continue;
                        }
                        
                        Shape clickbox = obj.getClickbox();
                        if (clickbox != null && clickbox.contains(point)) {
                            entities.add(new ClickableEntity(
                                    EntityType.OBJECT,
                                    getObjectName(obj.getId()),
                                    obj.getId(),
                                    null,
                                    null,
                                    obj,
                                    clickbox.getBounds(),
                                    getRenderPriority(obj, playerPos)
                            ));
                        }
                    }
                }
            }
        }
    }

    /**
     * Get approximate render priority for an NPC.
     * NPCs closer to camera are rendered on top.
     */
    private int getRenderPriority(NPC npc) {
        // Use canvas Y position as proxy for render order (lower Y = further back)
        Shape hull = npc.getConvexHull();
        if (hull != null) {
            return hull.getBounds().y + hull.getBounds().height;
        }
        return 0;
    }

    /**
     * Get approximate render priority for a player.
     */
    private int getRenderPriority(Player player) {
        Shape hull = player.getConvexHull();
        if (hull != null) {
            return hull.getBounds().y + hull.getBounds().height;
        }
        return 0;
    }

    /**
     * Get approximate render priority for an object.
     */
    private int getRenderPriority(GameObject obj, WorldPoint playerPos) {
        // Objects closer to player are typically rendered on top
        WorldPoint objPos = obj.getWorldLocation();
        if (objPos != null) {
            int distance = playerPos.distanceTo(objPos);
            return 1000 - distance * 10; // Closer = higher priority
        }
        return 0;
    }

    /**
     * Get object name from client definitions.
     */
    @Nullable
    private String getObjectName(int objectId) {
        try {
            var def = client.getObjectDefinition(objectId);
            return def != null ? def.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ========================================================================
    // Result Types
    // ========================================================================

    /**
     * Result of checking for entity overlap at a point.
     */
    @Value
    public static class OverlapResult {
        /**
         * Whether another entity would intercept the click.
         */
        boolean blocked;
        
        /**
         * The entity that would be clicked instead (null if no blocker).
         */
        @Nullable
        ClickableEntity blockingEntity;
        
        /**
         * Whether the intended target's clickbox contains the point at all.
         */
        boolean targetAtPoint;
        
        /**
         * All entities at this point, in render order.
         */
        List<ClickableEntity> allEntitiesAtPoint;
        
        public boolean hasBlockingEntity() {
            return blocked;
        }

        public String getBlockerDescription() {
            if (blockingEntity == null) {
                return "none";
            }
            return String.format("%s '%s' (id=%d)", 
                    blockingEntity.getType(), 
                    blockingEntity.getName(), 
                    blockingEntity.getId());
        }
    }

    /**
     * A clickable entity at a screen point.
     */
    @Value
    public static class ClickableEntity {
        EntityType type;
        @Nullable String name;
        int id;
        @Nullable NPC npc;
        @Nullable Player player;
        @Nullable TileObject tileObject;
        Rectangle bounds;
        int renderPriority;
    }

    /**
     * Type of clickable entity.
     */
    public enum EntityType {
        NPC,
        PLAYER,
        OBJECT
    }
}

