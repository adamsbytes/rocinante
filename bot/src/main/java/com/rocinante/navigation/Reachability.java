package com.rocinante.navigation;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
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
 * <p>Ensures we do not attempt to interact with objects blocked by fences, rivers,
 * or walls when standing on an adjacent tile. Uses collision flags rather than
 * line-of-sight to avoid "click-through" mistakes.</p>
 */
@Slf4j
@Singleton
public class Reachability {

    private final Client client;
    private final CollisionChecker collisionChecker;

    @Inject
    public Reachability(Client client, CollisionChecker collisionChecker) {
        this.client = client;
        this.collisionChecker = collisionChecker;
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
     * <p>For ranged/magic combat, the player doesn't need to be adjacent - they just
     * need a clear boundary path from some reachable tile that's within weapon range
     * of the target.
     *
     * <p>OSRS ranged/magic requires:
     * <ul>
     *   <li>The attacker tile must be within weapon range of the target</li>
     *   <li>There must be no full-blocking terrain between attacker and target</li>
     * </ul>
     *
     * @param playerPos   current player position
     * @param targetPos   target's position (NPC or other entity)
     * @param weaponRange the weapon's attack range (e.g., 7 for most ranged, 10 for magic)
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

        // If we're within range but blocked, we could try to find an adjacent tile
        // that's also in range and has line of sight
        if (distance <= weaponRange + 1) {
            // Try adjacent tiles to player's current position
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

                    // Check if we can walk to this tile from current position
                    if (isBoundaryBlocked(playerPos, adjTile)) continue;

                    // Check if this tile has line of sight to target
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
     * <p>In OSRS, ranged/magic attacks require that no full-blocking terrain exists
     * along the path. This uses a simple Bresenham-style line check, verifying each
     * tile along the line isn't fully blocked.
     *
     * <p>Note: This is a simplified check. OSRS actual projectile blocking is more
     * complex, but for target selection purposes this is sufficient.
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

        CollisionData[] collisionData = client.getCollisionMaps();
        if (collisionData == null) {
            return false;
        }

        int plane = from.getPlane();
        if (plane < 0 || plane >= collisionData.length || collisionData[plane] == null) {
            return false;
        }

        int[][] flags = collisionData[plane].getFlags();

        LocalPoint fromLocal = LocalPoint.fromWorld(client, from);
        LocalPoint toLocal = LocalPoint.fromWorld(client, to);
        if (fromLocal == null || toLocal == null) {
            return false;
        }

        int x0 = fromLocal.getSceneX();
        int y0 = fromLocal.getSceneY();
        int x1 = toLocal.getSceneX();
        int y1 = toLocal.getSceneY();

        // Bresenham's line algorithm to check each tile
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        int x = x0;
        int y = y0;

        while (true) {
            // Skip the destination tile - it may be blocked (e.g., NPC standing on it)
            if (x != x1 || y != y1) {
                // Also skip the source tile
                if (x != x0 || y != y0) {
                    // Check if this intermediate tile is fully blocked
                    if (isInScene(x, y) && collisionChecker.isBlocked(flags[x][y])) {
                        return false;
                    }
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
     * Check if the given scene coordinates are within the loaded scene bounds.
     */
    private boolean isInScene(int sceneX, int sceneY) {
        return sceneX >= 0 && sceneX < net.runelite.api.Constants.SCENE_SIZE &&
               sceneY >= 0 && sceneY < net.runelite.api.Constants.SCENE_SIZE;
    }

    /**
     * Get the set of tiles occupied by the object (its footprint).
     *
     * @param target the target TileObject
     * @return set of world tiles comprising the object's footprint (may be empty)
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
            // Swap dimensions for rotated objects
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
        // Ground objects don't carry orientation; treat as default.
        return 0;
    }

    /**
     * Check if the boundary between two adjacent tiles is blocked.
     *
     * @return true if blocked, false if clear or if data unavailable
     */
    private boolean isBoundaryBlocked(WorldPoint from, WorldPoint to) {
        CollisionData[] collisionData = client.getCollisionMaps();
        if (collisionData == null) {
            return true;
        }

        int plane = from.getPlane();
        if (plane < 0 || plane >= collisionData.length || collisionData[plane] == null) {
            return true;
        }

        LocalPoint fromLocal = LocalPoint.fromWorld(client, from);
        LocalPoint toLocal = LocalPoint.fromWorld(client, to);
        if (fromLocal == null || toLocal == null) {
            return true;
        }

        int fromX = fromLocal.getSceneX();
        int fromY = fromLocal.getSceneY();
        int toX = toLocal.getSceneX();
        int toY = toLocal.getSceneY();

        int dx = toX - fromX;
        int dy = toY - fromY;

        if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || (dx == 0 && dy == 0)) {
            return true;
        }

        int[][] flags = collisionData[plane].getFlags();
        return !movementAllowed(flags, fromX, fromY, dx, dy);
    }

    /**
     * Check if movement from one tile to an adjacent tile is allowed.
     *
     * <p>For INTERACTION reachability, we do NOT check if the destination tile itself
     * is blocked (e.g., by a tree or rock). We only check if the boundary between
     * tiles is clear (no fence, wall, or river blocking the way).
     *
     * <p>This is semantically different from PathFinder's walking check, which
     * requires the destination to be a walkable tile.
     */
    private boolean movementAllowed(int[][] flags, int x, int y, int dx, int dy) {
        // Delegate to CollisionChecker for boundary checking only
        // (isBoundaryPassable already handles cardinal/diagonal logic)
        return collisionChecker.isBoundaryPassable(flags, x, y, dx, dy);
    }
}

