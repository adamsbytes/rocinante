package com.rocinante.navigation;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static com.rocinante.navigation.TileObjectUtils.getWorldPoint;

/**
 * Reachability checks for interaction adjacency.
 *
 * <p>Uses {@link CollisionService} (backed by ShortestPath plugin's global collision data)
 * as the primary source for determining whether movement between tiles is allowed.
 * This provides accurate collision checking for:
 * <ul>
 *   <li>Fences and walls</li>
 *   <li>Rivers and water bodies</li>
 *   <li>Static barriers anywhere in the game world</li>
 * </ul>
 *
 * <p>Falls back to RuneLite's local scene collision data when CollisionService is
 * unavailable.
 *
 * <p><strong>This class is the key to preventing "click-through" mistakes</strong>
 * where the bot would attempt to interact with objects across barriers.
 *
 * @see CollisionService
 */
@Slf4j
@Singleton
public class Reachability {

    private final Client client;
    private final CollisionService collisionService;

    @Inject
    public Reachability(Client client, CollisionService collisionService) {
        this.client = client;
        this.collisionService = collisionService;
    }

    /**
     * Determine whether the player can physically interact with a target object
     * from the specified player tile.
     *
     * @param playerPos the player's world position (must be adjacent to the target)
     * @param target    the target TileObject
     * @return true if adjacency is not blocked by collision
     */
    public boolean canInteract(WorldPoint playerPos, TileObject target) {
        if (playerPos == null || target == null) {
            return false;
        }

        WorldPoint targetOrigin = getWorldPoint(target);
        if (targetOrigin == null || playerPos.getPlane() != targetOrigin.getPlane()) {
            return false;
        }

        Set<WorldPoint> footprint = getObjectFootprint(target);
        if (footprint.isEmpty()) {
            return false;
        }

        boolean boundaryObject = target instanceof WallObject || target instanceof DecorativeObject;

        for (WorldPoint tile : footprint) {
            if (tile.distanceTo(playerPos) <= 1) {
                // For boundary objects (doors/gates/walls), we intentionally allow interaction
                // even though the tile blocks movement; the interaction is expected to clear it.
                if (boundaryObject) {
                    return true;
                }
                if (!isBoundaryBlocked(playerPos, tile)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Determine whether the player can physically interact with a target position
     * (e.g., an NPC or ground item) from the specified player tile.
     *
     * <p>This is a simplified version for 1x1 entities like NPCs. The player must be
     * adjacent to the target, and there must be no collision boundary blocking the way.
     *
     * @param playerPos the player's world position
     * @param targetPos the target's world position (1x1 tile entity like NPC)
     * @return true if the player is adjacent and can reach the target
     */
    public boolean canInteract(WorldPoint playerPos, WorldPoint targetPos) {
        if (playerPos == null || targetPos == null) {
            return false;
        }

        if (playerPos.getPlane() != targetPos.getPlane()) {
            return false;
        }

        int distance = playerPos.distanceTo(targetPos);
        if (distance > 1) {
            return false;
        }

        // Same tile - can always interact
        if (distance == 0) {
            return true;
        }

        // Adjacent tile - check boundary
        return !isBoundaryBlocked(playerPos, targetPos);
    }

    /**
     * Find the nearest reachable tile from which the player can attack a target
     * within the specified weapon range.
     *
     * @param playerPos   current player position
     * @param targetPos   target's position (NPC or other entity)
     * @param weaponRange the weapon's attack range
     * @return an Optional containing the attack position if one exists within range
     */
    public Optional<WorldPoint> findAttackablePosition(WorldPoint playerPos, WorldPoint targetPos, int weaponRange) {
        if (playerPos == null || targetPos == null || weaponRange < 1) {
            return Optional.empty();
        }

        if (playerPos.getPlane() != targetPos.getPlane()) {
            return Optional.empty();
        }

        int distance = playerPos.distanceTo(targetPos);

        // Already within range - check if we have line of sight
        if (distance <= weaponRange && hasLineOfSight(playerPos, targetPos)) {
            return Optional.of(playerPos);
        }

        // If we're within range but blocked, try to find an adjacent tile
        if (distance <= weaponRange + 1) {
            WorldPoint bestTile = null;
            int bestDistance = Integer.MAX_VALUE;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;

                    WorldPoint adjTile = new WorldPoint(
                            playerPos.getX() + dx,
                            playerPos.getY() + dy,
                            playerPos.getPlane()
                    );

                    int adjDistance = adjTile.distanceTo(targetPos);
                    if (adjDistance > weaponRange) continue;

                    // Check if we can walk to this tile
                    if (isBoundaryBlocked(playerPos, adjTile)) continue;

                    // Check if this tile has line of sight
                    if (!hasLineOfSight(adjTile, targetPos)) continue;

                    if (adjDistance < bestDistance) {
                        bestDistance = adjDistance;
                        bestTile = adjTile;
                    }
                }
            }

            if (bestTile != null) {
                return Optional.of(bestTile);
            }
        }

        return Optional.empty();
    }

    /**
     * Check if there's a clear line of sight between two positions for ranged attacks.
     *
     * @param from source position
     * @param to   target position
     * @return true if line of sight is clear
     */
    public boolean hasLineOfSight(WorldPoint from, WorldPoint to) {
        if (from == null || to == null) {
            return false;
        }

        if (from.getPlane() != to.getPlane()) {
            return false;
        }

        // Use CollisionService for global blocking check
        return hasLineOfSightViaCollisionService(from, to);
    }

    private boolean hasLineOfSightViaCollisionService(WorldPoint from, WorldPoint to) {
        int x0 = from.getX();
        int y0 = from.getY();
        int x1 = to.getX();
        int y1 = to.getY();
        int z = from.getPlane();

        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        int x = x0;
        int y = y0;

        while (true) {
            // Skip source and destination tiles
            if ((x != x0 || y != y0) && (x != x1 || y != y1)) {
                if (collisionService.isBlocked(x, y, z)) {
                    return false;
                }
            }

            if (x == x1 && y == y1) {
                break;
            }

            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }

        return true;
    }

    /**
     * Get the set of tiles occupied by the object (its footprint).
     */
    public Set<WorldPoint> getObjectFootprint(TileObject target) {
        if (target == null) {
            return Collections.emptySet();
        }

        WorldPoint origin = getWorldPoint(target);
        if (origin == null) {
            return Collections.emptySet();
        }

        ObjectComposition comp = null;
        try {
            comp = client.getObjectDefinition(target.getId());
        } catch (Exception e) {
            log.debug("Failed to fetch object definition for id {}", target.getId(), e);
        }

        int sizeX = 1;
        int sizeY = 1;

        if (comp != null) {
            sizeX = Math.max(1, comp.getSizeX());
            sizeY = Math.max(1, comp.getSizeY());
        }

        int orientation = getOrientation(target);
        if ((orientation & 1) == 1) {
            int tmp = sizeX;
            sizeX = sizeY;
            sizeY = tmp;
        }

        Set<WorldPoint> tiles = new HashSet<>();
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dy = 0; dy < sizeY; dy++) {
                tiles.add(new WorldPoint(origin.getX() + dx, origin.getY() + dy, origin.getPlane()));
            }
        }
        return tiles;
    }

    private int getOrientation(TileObject target) {
        if (target instanceof GameObject) {
            return ((GameObject) target).getOrientation();
        }
        if (target instanceof WallObject) {
            return ((WallObject) target).getOrientationA();
        }
        return 0;
    }

    /**
     * Check if the boundary between two adjacent tiles is blocked.
     */
    private boolean isBoundaryBlocked(WorldPoint from, WorldPoint to) {
        return !collisionService.canMoveTo(from, to);
    }
}
