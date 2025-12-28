package com.rocinante.input;

import org.junit.Test;

import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.Assert.*;

/**
 * Tests for {@link ClickPointCalculator}.
 * Verifies humanized click point generation per REQUIREMENTS.md Section 3.1.2.
 */
public class ClickPointCalculatorTest {

    // ========================================================================
    // Constants
    // ========================================================================

    private static final Rectangle STANDARD_BOUNDS = new Rectangle(100, 100, 50, 50);
    private static final Rectangle SMALL_BOUNDS = new Rectangle(200, 200, 10, 10);
    private static final Rectangle LARGE_BOUNDS = new Rectangle(0, 0, 200, 200);

    // ========================================================================
    // Basic Click Point Generation
    // ========================================================================

    @Test
    public void gaussianClickPointWithinBounds() {
        // Generate many points and verify all are within bounds
        for (int i = 0; i < 1000; i++) {
            Point point = ClickPointCalculator.getGaussianClickPoint(STANDARD_BOUNDS);
            
            assertTrue("Point x=" + point.x + " should be >= bounds.x=" + STANDARD_BOUNDS.x,
                    point.x >= STANDARD_BOUNDS.x);
            assertTrue("Point x=" + point.x + " should be < bounds.x+width=" + (STANDARD_BOUNDS.x + STANDARD_BOUNDS.width),
                    point.x < STANDARD_BOUNDS.x + STANDARD_BOUNDS.width);
            assertTrue("Point y=" + point.y + " should be >= bounds.y=" + STANDARD_BOUNDS.y,
                    point.y >= STANDARD_BOUNDS.y);
            assertTrue("Point y=" + point.y + " should be < bounds.y+height=" + (STANDARD_BOUNDS.y + STANDARD_BOUNDS.height),
                    point.y < STANDARD_BOUNDS.y + STANDARD_BOUNDS.height);
        }
    }

    @Test
    public void gaussianClickPointRespectsEdgePadding() {
        for (int i = 0; i < 1000; i++) {
            Point point = ClickPointCalculator.getGaussianClickPoint(STANDARD_BOUNDS);
            
            // Should be at least EDGE_PADDING (2) pixels from edge
            assertTrue("Point x should respect edge padding",
                    point.x >= STANDARD_BOUNDS.x + ClickPointCalculator.EDGE_PADDING);
            assertTrue("Point x should respect edge padding on right side",
                    point.x <= STANDARD_BOUNDS.x + STANDARD_BOUNDS.width - ClickPointCalculator.EDGE_PADDING);
            assertTrue("Point y should respect edge padding",
                    point.y >= STANDARD_BOUNDS.y + ClickPointCalculator.EDGE_PADDING);
            assertTrue("Point y should respect edge padding on bottom",
                    point.y <= STANDARD_BOUNDS.y + STANDARD_BOUNDS.height - ClickPointCalculator.EDGE_PADDING);
        }
    }

    @Test
    public void clickPointsHaveVariance() {
        // Generate many points and verify they're not all the same
        int uniqueX = 0, uniqueY = 0;
        Point lastPoint = null;
        
        for (int i = 0; i < 100; i++) {
            Point point = ClickPointCalculator.getGaussianClickPoint(STANDARD_BOUNDS);
            if (lastPoint != null) {
                if (point.x != lastPoint.x) uniqueX++;
                if (point.y != lastPoint.y) uniqueY++;
            }
            lastPoint = point;
        }
        
        // At least 80% should be different (accounting for coincidental matches)
        assertTrue("Should have variance in X coordinates", uniqueX > 80);
        assertTrue("Should have variance in Y coordinates", uniqueY > 80);
    }

    @Test
    public void clickPointsClusterAroundCenter() {
        int centerX = LARGE_BOUNDS.x + LARGE_BOUNDS.width / 2;
        int centerY = LARGE_BOUNDS.y + LARGE_BOUNDS.height / 2;
        int centerRadius = 40; // Within 40px of center
        int inCenterCount = 0;
        
        for (int i = 0; i < 1000; i++) {
            Point point = ClickPointCalculator.getGaussianClickPoint(LARGE_BOUNDS);
            double distance = Math.sqrt(
                    Math.pow(point.x - centerX, 2) + 
                    Math.pow(point.y - centerY, 2)
            );
            if (distance < centerRadius) {
                inCenterCount++;
            }
        }
        
        // Most points (at least 50%) should be within center region (with Gaussian distribution)
        // Using 50% threshold to avoid flakiness due to random variance
        assertTrue("At least 50% of points should be near center, got " + (inCenterCount / 10.0) + "%",
                inCenterCount > 500);
    }

    // ========================================================================
    // Uniform Click Points
    // ========================================================================

    @Test
    public void uniformClickPointWithinBounds() {
        for (int i = 0; i < 1000; i++) {
            Point point = ClickPointCalculator.getUniformClickPoint(STANDARD_BOUNDS);
            
            assertTrue("Point x should be >= bounds.x", point.x >= STANDARD_BOUNDS.x);
            assertTrue("Point x should be < bounds.x+width", point.x < STANDARD_BOUNDS.x + STANDARD_BOUNDS.width);
            assertTrue("Point y should be >= bounds.y", point.y >= STANDARD_BOUNDS.y);
            assertTrue("Point y should be < bounds.y+height", point.y < STANDARD_BOUNDS.y + STANDARD_BOUNDS.height);
        }
    }

    @Test
    public void uniformClickPointDistribution() {
        int[] xBuckets = new int[5]; // Split into 5 regions
        int[] yBuckets = new int[5];
        
        for (int i = 0; i < 5000; i++) {
            Point point = ClickPointCalculator.getUniformClickPoint(LARGE_BOUNDS, 0);
            int xBucket = Math.min(4, (point.x - LARGE_BOUNDS.x) * 5 / LARGE_BOUNDS.width);
            int yBucket = Math.min(4, (point.y - LARGE_BOUNDS.y) * 5 / LARGE_BOUNDS.height);
            xBuckets[xBucket]++;
            yBuckets[yBucket]++;
        }
        
        // Each bucket should have roughly 1000 points (20% each)
        for (int i = 0; i < 5; i++) {
            assertTrue("X bucket " + i + " should have reasonable count, got " + xBuckets[i], xBuckets[i] > 700);
            assertTrue("X bucket " + i + " should have reasonable count, got " + xBuckets[i], xBuckets[i] < 1300);
            assertTrue("Y bucket " + i + " should have reasonable count, got " + yBuckets[i], yBuckets[i] > 700);
            assertTrue("Y bucket " + i + " should have reasonable count, got " + yBuckets[i], yBuckets[i] < 1300);
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void handleNullBounds() {
        Point point = ClickPointCalculator.getGaussianClickPoint(null);
        assertNotNull("Should return non-null point for null bounds", point);
        assertEquals("Should return (0,0) for null bounds", 0, point.x);
        assertEquals("Should return (0,0) for null bounds", 0, point.y);
    }

    @Test
    public void handleZeroSizeBounds() {
        Rectangle zeroBounds = new Rectangle(100, 100, 0, 0);
        Point point = ClickPointCalculator.getGaussianClickPoint(zeroBounds);
        assertNotNull("Should return non-null point for zero bounds", point);
    }

    @Test
    public void handleSmallBounds() {
        for (int i = 0; i < 100; i++) {
            Point point = ClickPointCalculator.getGaussianClickPoint(SMALL_BOUNDS);
            assertNotNull(point);
            // With small bounds, points should still be valid
            assertTrue(point.x >= SMALL_BOUNDS.x && point.x < SMALL_BOUNDS.x + SMALL_BOUNDS.width);
            assertTrue(point.y >= SMALL_BOUNDS.y && point.y < SMALL_BOUNDS.y + SMALL_BOUNDS.height);
        }
    }

    // ========================================================================
    // Offset Calculation
    // ========================================================================

    @Test
    public void gaussianOffsetWithinDimension() {
        int dimension = 100;
        for (int i = 0; i < 1000; i++) {
            int offset = ClickPointCalculator.calculateGaussianOffset(dimension);
            assertTrue("Offset should be >= edge padding", offset >= ClickPointCalculator.EDGE_PADDING);
            assertTrue("Offset should be <= dimension - edge padding",
                    offset <= dimension - ClickPointCalculator.EDGE_PADDING);
        }
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    @Test
    public void randomCenterPercentInRange() {
        for (int i = 0; i < 1000; i++) {
            double center = ClickPointCalculator.getRandomCenterPercent();
            assertTrue("Center should be >= " + ClickPointCalculator.CENTER_MIN,
                    center >= ClickPointCalculator.CENTER_MIN);
            assertTrue("Center should be <= " + ClickPointCalculator.CENTER_MAX,
                    center <= ClickPointCalculator.CENTER_MAX);
        }
    }

    @Test
    public void clampToBoundsWorks() {
        Rectangle bounds = new Rectangle(10, 10, 50, 50);
        
        // Point inside bounds - should not change
        Point inside = new Point(30, 30);
        Point clampedInside = ClickPointCalculator.clampToBounds(inside, bounds);
        assertEquals(30, clampedInside.x);
        assertEquals(30, clampedInside.y);
        
        // Point outside left - should clamp x
        Point outsideLeft = new Point(5, 30);
        Point clampedLeft = ClickPointCalculator.clampToBounds(outsideLeft, bounds);
        assertEquals(10, clampedLeft.x);
        assertEquals(30, clampedLeft.y);
        
        // Point outside top - should clamp y
        Point outsideTop = new Point(30, 5);
        Point clampedTop = ClickPointCalculator.clampToBounds(outsideTop, bounds);
        assertEquals(30, clampedTop.x);
        assertEquals(10, clampedTop.y);
        
        // Point outside right - should clamp x
        Point outsideRight = new Point(100, 30);
        Point clampedRight = ClickPointCalculator.clampToBounds(outsideRight, bounds);
        assertEquals(59, clampedRight.x); // bounds.x + bounds.width - 1
        assertEquals(30, clampedRight.y);
        
        // Point outside bottom - should clamp y
        Point outsideBottom = new Point(30, 100);
        Point clampedBottom = ClickPointCalculator.clampToBounds(outsideBottom, bounds);
        assertEquals(30, clampedBottom.x);
        assertEquals(59, clampedBottom.y); // bounds.y + bounds.height - 1
    }

    @Test
    public void isValidBoundsWorks() {
        assertTrue(ClickPointCalculator.isValidBounds(STANDARD_BOUNDS));
        assertTrue(ClickPointCalculator.isValidBounds(SMALL_BOUNDS));
        assertTrue(ClickPointCalculator.isValidBounds(LARGE_BOUNDS));
        
        assertFalse(ClickPointCalculator.isValidBounds(null));
        assertFalse(ClickPointCalculator.isValidBounds(new Rectangle(0, 0, 0, 0)));
        assertFalse(ClickPointCalculator.isValidBounds(new Rectangle(0, 0, -10, 10)));
        assertFalse(ClickPointCalculator.isValidBounds(new Rectangle(0, 0, 10, -10)));
    }

    @Test
    public void isLargeEnoughForHumanizationWorks() {
        assertTrue(ClickPointCalculator.isLargeEnoughForHumanization(STANDARD_BOUNDS));
        assertTrue(ClickPointCalculator.isLargeEnoughForHumanization(LARGE_BOUNDS));
        
        assertFalse(ClickPointCalculator.isLargeEnoughForHumanization(null));
        assertFalse(ClickPointCalculator.isLargeEnoughForHumanization(new Rectangle(0, 0, 5, 5)));
        assertFalse(ClickPointCalculator.isLargeEnoughForHumanization(new Rectangle(0, 0, 9, 9)));
        assertTrue(ClickPointCalculator.isLargeEnoughForHumanization(new Rectangle(0, 0, 10, 10)));
    }

    @Test
    public void getAppropriateStdDevScales() {
        // Small bounds should get precise stddev
        assertEquals(ClickPointCalculator.PRECISE_STD_DEV, 
                ClickPointCalculator.getAppropriateStdDev(new Rectangle(0, 0, 15, 15)), 0.001);
        
        // Medium bounds should get default stddev
        assertEquals(ClickPointCalculator.DEFAULT_STD_DEV,
                ClickPointCalculator.getAppropriateStdDev(STANDARD_BOUNDS), 0.001);
        
        // Large bounds should get imprecise stddev
        assertEquals(ClickPointCalculator.IMPRECISE_STD_DEV,
                ClickPointCalculator.getAppropriateStdDev(new Rectangle(0, 0, 150, 150)), 0.001);
    }

    @Test
    public void getAdaptiveClickPointWorks() {
        // Just verify it returns valid points for different sizes
        for (int size = 10; size <= 200; size += 10) {
            Rectangle bounds = new Rectangle(0, 0, size, size);
            Point point = ClickPointCalculator.getAdaptiveClickPoint(bounds);
            assertNotNull(point);
            assertTrue(point.x >= 0 && point.x < size);
            assertTrue(point.y >= 0 && point.y < size);
        }
    }

    @Test
    public void getCenterPointWorks() {
        Point center = ClickPointCalculator.getCenterPoint(STANDARD_BOUNDS);
        assertEquals(125, center.x); // 100 + 50/2
        assertEquals(125, center.y); // 100 + 50/2
        
        Point center2 = ClickPointCalculator.getCenterPoint(new Rectangle(0, 0, 100, 50));
        assertEquals(50, center2.x);
        assertEquals(25, center2.y);
    }
}
