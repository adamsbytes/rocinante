package com.rocinante.navigation;

import net.runelite.api.CollisionDataFlag;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for CollisionChecker collision flag interpretation.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>Full blocking detection</li>
 *   <li>Directional blocking (N/S/E/W and diagonals)</li>
 *   <li>Diagonal corner blocking</li>
 *   <li>Boundary passability checks</li>
 * </ul>
 */
public class CollisionCheckerTest {

    private CollisionChecker collisionChecker;

    @Before
    public void setUp() {
        collisionChecker = new CollisionChecker();
    }

    // ========================================================================
    // isBlocked Tests
    // ========================================================================

    @Test
    public void testIsBlocked_NoFlags_ReturnsFalse() {
        assertFalse("Empty tile should not be blocked", collisionChecker.isBlocked(0));
    }

    @Test
    public void testIsBlocked_FullBlock_ReturnsTrue() {
        assertTrue("BLOCK_MOVEMENT_FULL should be blocked",
                collisionChecker.isBlocked(CollisionDataFlag.BLOCK_MOVEMENT_FULL));
    }

    @Test
    public void testIsBlocked_DirectionalFlagOnly_ReturnsFalse() {
        // Directional flags alone don't fully block a tile
        assertFalse("North-only block should not fully block tile",
                collisionChecker.isBlocked(CollisionDataFlag.BLOCK_MOVEMENT_NORTH));
    }

    @Test
    public void testIsBlocked_CombinedFlags_ReturnsTrue() {
        int flags = CollisionDataFlag.BLOCK_MOVEMENT_FULL | CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        assertTrue("Combined with FULL should be blocked", collisionChecker.isBlocked(flags));
    }

    // ========================================================================
    // isBlockedDirection Cardinal Tests
    // ========================================================================

    @Test
    public void testIsBlockedDirection_North_Blocked() {
        int flag = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        assertTrue("North should be blocked", collisionChecker.isBlockedDirection(flag, 0, 1));
    }

    @Test
    public void testIsBlockedDirection_South_Blocked() {
        int flag = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
        assertTrue("South should be blocked", collisionChecker.isBlockedDirection(flag, 0, -1));
    }

    @Test
    public void testIsBlockedDirection_East_Blocked() {
        int flag = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
        assertTrue("East should be blocked", collisionChecker.isBlockedDirection(flag, 1, 0));
    }

    @Test
    public void testIsBlockedDirection_West_Blocked() {
        int flag = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
        assertTrue("West should be blocked", collisionChecker.isBlockedDirection(flag, -1, 0));
    }

    @Test
    public void testIsBlockedDirection_North_NotBlocked() {
        // Flag for south, but checking north
        int flag = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
        assertFalse("North should not be blocked by south flag",
                collisionChecker.isBlockedDirection(flag, 0, 1));
    }

    // ========================================================================
    // isBlockedDirection Diagonal Tests
    // ========================================================================

    @Test
    public void testIsBlockedDirection_NorthEast_Blocked() {
        int flag = CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
        assertTrue("NE should be blocked", collisionChecker.isBlockedDirection(flag, 1, 1));
    }

    @Test
    public void testIsBlockedDirection_SouthEast_Blocked() {
        int flag = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
        assertTrue("SE should be blocked", collisionChecker.isBlockedDirection(flag, 1, -1));
    }

    @Test
    public void testIsBlockedDirection_SouthWest_Blocked() {
        int flag = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
        assertTrue("SW should be blocked", collisionChecker.isBlockedDirection(flag, -1, -1));
    }

    @Test
    public void testIsBlockedDirection_NorthWest_Blocked() {
        int flag = CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
        assertTrue("NW should be blocked", collisionChecker.isBlockedDirection(flag, -1, 1));
    }

    // ========================================================================
    // isBlockedDiagonal Tests
    // ========================================================================

    @Test
    public void testIsBlockedDiagonal_NorthEast_EastTileBlocksNorth() {
        // Create a 3x3 flag array
        int[][] flags = new int[3][3];
        // East tile (x+1, y) blocks north
        flags[2][1] = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        
        // Moving NE from (1,1) should be blocked
        assertTrue("NE blocked when east tile blocks north",
                collisionChecker.isBlockedDiagonal(flags, 1, 1, 1, 1));
    }

    @Test
    public void testIsBlockedDiagonal_NorthEast_NorthTileBlocksEast() {
        int[][] flags = new int[3][3];
        // North tile (x, y+1) blocks east
        flags[1][2] = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
        
        assertTrue("NE blocked when north tile blocks east",
                collisionChecker.isBlockedDiagonal(flags, 1, 1, 1, 1));
    }

    @Test
    public void testIsBlockedDiagonal_NorthEast_Clear() {
        int[][] flags = new int[3][3];
        // All clear
        assertFalse("NE should not be blocked when tiles are clear",
                collisionChecker.isBlockedDiagonal(flags, 1, 1, 1, 1));
    }

    @Test
    public void testIsBlockedDiagonal_SouthWest_WestTileBlocksSouth() {
        int[][] flags = new int[3][3];
        // West tile blocks south
        flags[0][1] = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
        
        assertTrue("SW blocked when west tile blocks south",
                collisionChecker.isBlockedDiagonal(flags, 1, 1, -1, -1));
    }

    // ========================================================================
    // isCardinalMovementBlocked Tests
    // ========================================================================

    @Test
    public void testIsCardinalMovementBlocked_SourceBlocks() {
        int[][] flags = new int[3][3];
        // Source tile (1,1) blocks north exit
        flags[1][1] = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        
        assertTrue("Movement blocked when source blocks exit",
                collisionChecker.isCardinalMovementBlocked(flags, 1, 1, 0, 1));
    }

    @Test
    public void testIsCardinalMovementBlocked_DestBlocks() {
        int[][] flags = new int[3][3];
        // Destination tile (1,2) blocks south entry
        flags[1][2] = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
        
        assertTrue("Movement blocked when dest blocks entry",
                collisionChecker.isCardinalMovementBlocked(flags, 1, 1, 0, 1));
    }

    @Test
    public void testIsCardinalMovementBlocked_Clear() {
        int[][] flags = new int[3][3];
        
        assertFalse("Movement not blocked when tiles are clear",
                collisionChecker.isCardinalMovementBlocked(flags, 1, 1, 0, 1));
    }

    // ========================================================================
    // isBoundaryPassable Tests
    // ========================================================================

    @Test
    public void testIsBoundaryPassable_CardinalClear() {
        int[][] flags = new int[3][3];
        
        assertTrue("Cardinal movement should be passable when clear",
                collisionChecker.isBoundaryPassable(flags, 1, 1, 0, 1));
    }

    @Test
    public void testIsBoundaryPassable_CardinalBlocked() {
        int[][] flags = new int[3][3];
        flags[1][1] = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        
        assertFalse("Cardinal movement blocked by directional flag",
                collisionChecker.isBoundaryPassable(flags, 1, 1, 0, 1));
    }

    @Test
    public void testIsBoundaryPassable_DiagonalClear() {
        int[][] flags = new int[3][3];
        
        assertTrue("Diagonal movement should be passable when clear",
                collisionChecker.isBoundaryPassable(flags, 1, 1, 1, 1));
    }

    @Test
    public void testIsBoundaryPassable_DiagonalBlockedByAdjacentCardinal() {
        int[][] flags = new int[3][3];
        // East tile is fully blocked
        flags[2][1] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        
        assertFalse("Diagonal blocked when adjacent cardinal tile is blocked",
                collisionChecker.isBoundaryPassable(flags, 1, 1, 1, 1));
    }

    @Test
    public void testIsBoundaryPassable_DiagonalBlockedByCorner() {
        int[][] flags = new int[3][3];
        // East tile blocks north (corner blocking)
        flags[2][1] = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        
        assertFalse("Diagonal blocked by corner collision",
                collisionChecker.isBoundaryPassable(flags, 1, 1, 1, 1));
    }

    // ========================================================================
    // Real-World Scenario Tests
    // ========================================================================

    @Test
    public void testFenceScenario_BlocksMovement() {
        // Simulates a fence between two tiles
        // Fence on north edge of tile (1,1) blocks north movement
        int[][] flags = new int[3][3];
        flags[1][1] = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        
        assertFalse("Fence should block crossing",
                collisionChecker.isBoundaryPassable(flags, 1, 1, 0, 1));
    }

    @Test
    public void testWallCornerScenario_BlocksDiagonal() {
        // Simulates wall corners blocking diagonal movement
        int[][] flags = new int[3][3];
        // Wall on east side of (1,1) and north side of (2,1)
        flags[1][1] = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
        flags[2][1] = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        
        assertFalse("Wall corner should block diagonal movement",
                collisionChecker.isBoundaryPassable(flags, 1, 1, 1, 1));
    }
}

