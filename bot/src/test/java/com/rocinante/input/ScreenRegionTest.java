package com.rocinante.input;

import com.rocinante.util.Randomization;
import org.junit.Before;
import org.junit.Test;

import java.awt.Rectangle;

import static org.junit.Assert.*;

/**
 * Unit tests for ScreenRegion enum.
 * Verifies screen region definitions and utility methods.
 */
public class ScreenRegionTest {

    private static final int SAMPLE_SIZE = 100;

    private Randomization randomization;

    @Before
    public void setUp() {
        randomization = new Randomization(12345L);
    }

    // ========================================================================
    // Region Definition Tests
    // ========================================================================

    @Test
    public void testRegion_HasValidDimensions() {
        for (ScreenRegion region : ScreenRegion.values()) {
            assertTrue("Region " + region.name() + " should have positive width",
                    region.getWidth() > 0);
            assertTrue("Region " + region.name() + " should have positive height",
                    region.getHeight() > 0);
            assertTrue("Region " + region.name() + " should have non-negative X",
                    region.getX() >= 0);
            assertTrue("Region " + region.name() + " should have non-negative Y",
                    region.getY() >= 0);
        }
    }

    @Test
    public void testRegion_CenterCalculation() {
        for (ScreenRegion region : ScreenRegion.values()) {
            int expectedCenterX = region.getX() + region.getWidth() / 2;
            int expectedCenterY = region.getY() + region.getHeight() / 2;

            assertEquals("Center X should be calculated correctly",
                    expectedCenterX, region.getCenterX());
            assertEquals("Center Y should be calculated correctly",
                    expectedCenterY, region.getCenterY());
        }
    }

    @Test
    public void testRegion_ToRectangle() {
        for (ScreenRegion region : ScreenRegion.values()) {
            Rectangle rect = region.toRectangle();

            assertEquals("Rectangle X should match", region.getX(), rect.x);
            assertEquals("Rectangle Y should match", region.getY(), rect.y);
            assertEquals("Rectangle width should match", region.getWidth(), rect.width);
            assertEquals("Rectangle height should match", region.getHeight(), rect.height);
        }
    }

    // ========================================================================
    // Contains Point Tests
    // ========================================================================

    @Test
    public void testContains_PointInside() {
        ScreenRegion region = ScreenRegion.INVENTORY;

        int insideX = region.getX() + region.getWidth() / 2;
        int insideY = region.getY() + region.getHeight() / 2;

        assertTrue("Center point should be inside region",
                region.contains(insideX, insideY));
    }

    @Test
    public void testContains_PointOutside() {
        ScreenRegion region = ScreenRegion.INVENTORY;

        int outsideX = region.getX() - 10;
        int outsideY = region.getY() - 10;

        assertFalse("Point outside should not be in region",
                region.contains(outsideX, outsideY));
    }

    @Test
    public void testContains_PointOnBoundary() {
        ScreenRegion region = ScreenRegion.INVENTORY;

        // Top-left corner (should be inside)
        assertTrue("Top-left corner should be inside",
                region.contains(region.getX(), region.getY()));

        // Just outside right boundary
        assertFalse("Point past right boundary should be outside",
                region.contains(region.getX() + region.getWidth(), region.getY()));
    }

    // ========================================================================
    // Random Point Generation Tests
    // ========================================================================

    @Test
    public void testGetRandomPoint_WithinBounds() {
        ScreenRegion region = ScreenRegion.INVENTORY;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int[] point = region.getRandomPoint(randomization);

            assertTrue("Random X should be >= region X",
                    point[0] >= region.getX());
            assertTrue("Random X should be < region X + width",
                    point[0] < region.getX() + region.getWidth());
            assertTrue("Random Y should be >= region Y",
                    point[1] >= region.getY());
            assertTrue("Random Y should be < region Y + height",
                    point[1] < region.getY() + region.getHeight());
        }
    }

    @Test
    public void testGetGaussianPoint_WithinBounds() {
        ScreenRegion region = ScreenRegion.INVENTORY;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int[] point = region.getGaussianPoint(randomization);

            assertTrue("Gaussian X should be within region",
                    point[0] >= region.getX() && point[0] <= region.getX() + region.getWidth());
            assertTrue("Gaussian Y should be within region",
                    point[1] >= region.getY() && point[1] <= region.getY() + region.getHeight());
        }
    }

    @Test
    public void testGetGaussianPoint_BiasedTowardCenter() {
        ScreenRegion region = ScreenRegion.INVENTORY;

        double sumX = 0, sumY = 0;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int[] point = region.getGaussianPoint(randomization);
            sumX += point[0];
            sumY += point[1];
        }

        double meanX = sumX / SAMPLE_SIZE;
        double meanY = sumY / SAMPLE_SIZE;

        // Mean should be close to center (within 20% of dimensions)
        double expectedCenterX = region.getX() + region.getWidth() * 0.5;
        double expectedCenterY = region.getY() + region.getHeight() * 0.5;

        assertEquals("Mean X should be near center",
                expectedCenterX, meanX, region.getWidth() * 0.2);
        assertEquals("Mean Y should be near center",
                expectedCenterY, meanY, region.getHeight() * 0.2);
    }

    // ========================================================================
    // Scaling Tests
    // ========================================================================

    @Test
    public void testScaled_DoubleSize() {
        ScreenRegion region = ScreenRegion.INVENTORY;

        Rectangle scaled = region.scaled(2.0, 2.0);

        assertEquals("Scaled X should be doubled",
                region.getX() * 2, scaled.x);
        assertEquals("Scaled Y should be doubled",
                region.getY() * 2, scaled.y);
        assertEquals("Scaled width should be doubled",
                region.getWidth() * 2, scaled.width);
        assertEquals("Scaled height should be doubled",
                region.getHeight() * 2, scaled.height);
    }

    @Test
    public void testScaled_HalfSize() {
        ScreenRegion region = ScreenRegion.INVENTORY;

        Rectangle scaled = region.scaled(0.5, 0.5);

        assertEquals("Scaled X should be halved",
                region.getX() / 2, scaled.x);
        assertEquals("Scaled Y should be halved",
                region.getY() / 2, scaled.y);
        assertEquals("Scaled width should be halved",
                region.getWidth() / 2, scaled.width);
        assertEquals("Scaled height should be halved",
                region.getHeight() / 2, scaled.height);
    }

    @Test
    public void testScaled_AsymmetricScale() {
        ScreenRegion region = ScreenRegion.INVENTORY;

        Rectangle scaled = region.scaled(1.5, 0.8);

        assertEquals("Scaled X should use scaleX",
                (int) (region.getX() * 1.5), scaled.x);
        assertEquals("Scaled Y should use scaleY",
                (int) (region.getY() * 0.8), scaled.y);
    }

    // ========================================================================
    // Default Regions Tests
    // ========================================================================

    @Test
    public void testGetDefaultIdleRegions_NotEmpty() {
        ScreenRegion[] defaults = ScreenRegion.getDefaultIdleRegions();

        assertNotNull("Default idle regions should not be null", defaults);
        assertTrue("Should have at least one default idle region", defaults.length > 0);
    }

    @Test
    public void testGetDefaultIdleRegions_ContainsInventory() {
        ScreenRegion[] defaults = ScreenRegion.getDefaultIdleRegions();

        boolean hasInventory = false;
        for (ScreenRegion region : defaults) {
            if (region == ScreenRegion.INVENTORY) {
                hasInventory = true;
                break;
            }
        }

        assertTrue("Default idle regions should include INVENTORY", hasInventory);
    }

    @Test
    public void testGetCombatRegions_NotEmpty() {
        ScreenRegion[] combatRegions = ScreenRegion.getCombatRegions();

        assertNotNull("Combat regions should not be null", combatRegions);
        assertTrue("Should have at least one combat region", combatRegions.length > 0);
    }

    @Test
    public void testGetRightSideRegions_AllOnRight() {
        ScreenRegion[] rightRegions = ScreenRegion.getRightSideRegions();

        for (ScreenRegion region : rightRegions) {
            assertTrue("Region " + region.name() + " should be on right side",
                    region.isRightSide());
        }
    }

    @Test
    public void testGetLeftSideRegions_AllOnLeft() {
        ScreenRegion[] leftRegions = ScreenRegion.getLeftSideRegions();

        for (ScreenRegion region : leftRegions) {
            assertTrue("Region " + region.name() + " should be on left side",
                    region.isLeftSide());
        }
    }

    // ========================================================================
    // Side Detection Tests
    // ========================================================================

    @Test
    public void testIsRightSide_Inventory() {
        assertTrue("Inventory should be on right side",
                ScreenRegion.INVENTORY.isRightSide());
    }

    @Test
    public void testIsRightSide_Minimap() {
        assertTrue("Minimap should be on right side",
                ScreenRegion.MINIMAP.isRightSide());
    }

    @Test
    public void testIsLeftSide_Chat() {
        assertTrue("Chat should be on left side",
                ScreenRegion.CHAT.isLeftSide());
    }

    @Test
    public void testIsLeftSide_Viewport() {
        assertTrue("Viewport should be on left side",
                ScreenRegion.VIEWPORT.isLeftSide());
    }

    @Test
    public void testIsRightSide_MutuallyExclusive() {
        for (ScreenRegion region : ScreenRegion.values()) {
            // A region must be either left or right, not both or neither
            assertTrue("Region " + region.name() + " should be on one side",
                    region.isRightSide() != region.isLeftSide());
        }
    }

    // ========================================================================
    // Specific Region Tests
    // ========================================================================

    @Test
    public void testInventoryRegion_ReasonableDimensions() {
        ScreenRegion inventory = ScreenRegion.INVENTORY;

        // Inventory is typically ~176x261 pixels
        assertTrue("Inventory width should be reasonable",
                inventory.getWidth() > 100 && inventory.getWidth() < 300);
        assertTrue("Inventory height should be reasonable",
                inventory.getHeight() > 150 && inventory.getHeight() < 400);
    }

    @Test
    public void testMinimapRegion_TopRight() {
        ScreenRegion minimap = ScreenRegion.MINIMAP;

        // Minimap should be in top-right area
        assertTrue("Minimap X should be > 400", minimap.getX() > 400);
        assertTrue("Minimap Y should be < 200", minimap.getY() < 200);
    }

    @Test
    public void testChatRegion_Bottom() {
        ScreenRegion chat = ScreenRegion.CHAT;

        // Chat should be at bottom of screen
        assertTrue("Chat Y should be > 300", chat.getY() > 300);
    }

    @Test
    public void testViewportRegion_LargestArea() {
        ScreenRegion viewport = ScreenRegion.VIEWPORT;

        // Viewport should be the largest region
        int viewportArea = viewport.getWidth() * viewport.getHeight();

        for (ScreenRegion region : ScreenRegion.values()) {
            if (region != ScreenRegion.VIEWPORT && region != ScreenRegion.BANK) {
                int regionArea = region.getWidth() * region.getHeight();
                assertTrue("Viewport should be larger than " + region.name(),
                        viewportArea >= regionArea);
            }
        }
    }
}

