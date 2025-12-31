package com.rocinante.navigation;

import net.runelite.api.CollisionDataFlag;

import javax.inject.Singleton;

/**
 * Centralized collision flag checking utility.
 *
 * <p>Consolidates collision flag interpretation logic used across the navigation system,
 * ensuring consistent boundary and movement checking in PathFinder, Reachability, and
 * related components.
 *
 * <p>This class handles:
 * <ul>
 *   <li>Full tile blocking detection</li>
 *   <li>Directional movement blocking (N/S/E/W and diagonals)</li>
 *   <li>Diagonal movement corner blocking</li>
 * </ul>
 *
 * @see PathFinder
 * @see Reachability
 */
@Singleton
public class CollisionChecker {

    /**
     * Check if a tile is fully blocked for movement.
     *
     * <p>A tile with BLOCK_MOVEMENT_FULL set cannot be walked on at all.
     * This includes objects, walls, water bodies, and other impassable terrain.
     *
     * @param collisionFlag the collision flag from the collision map
     * @return true if the tile is completely blocked
     */
    public boolean isBlocked(int collisionFlag) {
        return (collisionFlag & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0;
    }

    /**
     * Check if movement in a specific direction is blocked by the tile's collision flags.
     *
     * <p>This checks for walls, fences, and other boundary objects that block movement
     * in a specific direction without blocking the entire tile.
     *
     * @param collisionFlag the collision flag of the source tile
     * @param dx            direction X component (-1, 0, or 1)
     * @param dy            direction Y component (-1, 0, or 1)
     * @return true if movement in that direction is blocked
     */
    public boolean isBlockedDirection(int collisionFlag, int dx, int dy) {
        // Cardinal directions
        if (dx == 0 && dy == 1) {
            return (collisionFlag & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0;
        }
        if (dx == 0 && dy == -1) {
            return (collisionFlag & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0;
        }
        if (dx == 1 && dy == 0) {
            return (collisionFlag & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0;
        }
        if (dx == -1 && dy == 0) {
            return (collisionFlag & CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0;
        }

        // Diagonal directions
        if (dx == 1 && dy == 1) {
            return (collisionFlag & CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST) != 0;
        }
        if (dx == 1 && dy == -1) {
            return (collisionFlag & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST) != 0;
        }
        if (dx == -1 && dy == -1) {
            return (collisionFlag & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST) != 0;
        }
        if (dx == -1 && dy == 1) {
            return (collisionFlag & CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST) != 0;
        }

        return false;
    }

    /**
     * Check if diagonal movement is blocked by corner walls/objects.
     *
     * <p>For diagonal movement (e.g., NE), both adjacent cardinal tiles (N and E)
     * must allow passage in the secondary direction. For example, moving NE requires:
     * <ul>
     *   <li>The tile to the east (x+1, y) must not block northward movement</li>
     *   <li>The tile to the north (x, y+1) must not block eastward movement</li>
     * </ul>
     *
     * <p>This prevents "cutting corners" through fence posts, wall corners, etc.
     *
     * @param flags the collision flag array from the collision map
     * @param x     scene X coordinate of the source tile
     * @param y     scene Y coordinate of the source tile
     * @param dx    direction X component (-1 or 1)
     * @param dy    direction Y component (-1 or 1)
     * @return true if diagonal movement is blocked by corner collision
     */
    public boolean isBlockedDiagonal(int[][] flags, int x, int y, int dx, int dy) {
        // Check the tile in the X direction for vertical blocking
        int adjX = x + dx;
        if (dy > 0) {
            if ((flags[adjX][y] & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0) {
                return true;
            }
        } else {
            if ((flags[adjX][y] & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0) {
                return true;
            }
        }

        // Check the tile in the Y direction for horizontal blocking
        int adjY = y + dy;
        if (dx > 0) {
            if ((flags[x][adjY] & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0) {
                return true;
            }
        } else {
            if ((flags[x][adjY] & CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if movement from one tile to an adjacent tile is allowed, considering
     * boundary flags on both tiles.
     *
     * <p>This is the complete check for cardinal movement: verifies that neither
     * the source tile blocks exit in that direction, nor the destination tile
     * blocks entry from that direction.
     *
     * @param flags the collision flag array
     * @param fromX scene X of source tile
     * @param fromY scene Y of source tile
     * @param dx    direction X component (-1, 0, or 1)
     * @param dy    direction Y component (-1, 0, or 1)
     * @return true if cardinal movement is blocked
     */
    public boolean isCardinalMovementBlocked(int[][] flags, int fromX, int fromY, int dx, int dy) {
        int destX = fromX + dx;
        int destY = fromY + dy;
        return isBlockedDirection(flags[fromX][fromY], dx, dy) ||
               isBlockedDirection(flags[destX][destY], -dx, -dy);
    }

    /**
     * Complete check for whether movement to an adjacent tile is allowed.
     *
     * <p>For cardinal movement, checks boundary flags on both tiles.
     * For diagonal movement, additionally checks:
     * <ul>
     *   <li>Both cardinal adjacent tiles are not fully blocked</li>
     *   <li>Corner blocking via {@link #isBlockedDiagonal}</li>
     * </ul>
     *
     * <p>Note: This does NOT check if the destination tile itself is blocked
     * (use {@link #isBlocked} separately for walking vs interaction scenarios).
     *
     * @param flags the collision flag array
     * @param x     scene X of source tile
     * @param y     scene Y of source tile
     * @param dx    direction X component (-1, 0, or 1)
     * @param dy    direction Y component (-1, 0, or 1)
     * @return true if the boundary allows passage
     */
    public boolean isBoundaryPassable(int[][] flags, int x, int y, int dx, int dy) {
        int destX = x + dx;
        int destY = y + dy;

        // Cardinal movement
        if (dx == 0 || dy == 0) {
            return !isBlockedDirection(flags[x][y], dx, dy) &&
                   !isBlockedDirection(flags[destX][destY], -dx, -dy);
        }

        // Diagonal movement: check both cardinal adjacent tiles
        int cardinalX = x + dx;
        if (isBlocked(flags[cardinalX][y]) ||
            isBlockedDirection(flags[x][y], dx, 0) ||
            isBlockedDirection(flags[cardinalX][y], -dx, 0)) {
            return false;
        }

        int cardinalY = y + dy;
        if (isBlocked(flags[x][cardinalY]) ||
            isBlockedDirection(flags[x][y], 0, dy) ||
            isBlockedDirection(flags[x][cardinalY], 0, -dy)) {
            return false;
        }

        // Check corner blocking
        return !isBlockedDiagonal(flags, x, y, dx, dy);
    }
}

