package com.rocinante.input;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;
import net.runelite.api.widgets.Widget;

import javax.annotation.Nullable;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Centralized utility for calculating humanized click points within bounds.
 *
 * <p>Per REQUIREMENTS.md Section 3.1.2 Click Behavior:
 * <ul>
 *   <li>Position variance: 2D Gaussian distribution centered at 45-55% of hitbox, σ = 15%</li>
 *   <li>Never click the geometric center</li>
 *   <li>Minimum padding from edges to avoid misclicks</li>
 * </ul>
 *
 * <p>This utility consolidates click point calculation logic that was previously
 * duplicated across multiple task implementations. All tasks should use this
 * class instead of implementing their own click point calculations.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Get click point for a widget
 * Widget prayerWidget = client.getWidget(541, 17);
 * Point clickPoint = ClickPointCalculator.getWidgetClickPoint(prayerWidget);
 *
 * // Get click point for an NPC
 * NPC monster = getNearbyNpc();
 * Point npcClick = ClickPointCalculator.getNpcClickPoint(monster);
 *
 * // Get click point for any bounds with custom distribution
 * Rectangle bounds = someObject.getClickbox().getBounds();
 * Point customClick = ClickPointCalculator.getGaussianClickPoint(bounds, 0.10, 0.50);
 * }</pre>
 */
@Slf4j
public final class ClickPointCalculator {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Minimum padding from edge of bounds (in pixels).
     * Prevents misclicks on the very edge of clickable areas.
     */
    public static final int EDGE_PADDING = 2;

    /**
     * Default center offset range (45-55% per spec).
     * Center of click distribution is randomized within this range.
     */
    public static final double CENTER_MIN = 0.45;
    public static final double CENTER_MAX = 0.55;

    /**
     * Default standard deviation for Gaussian distribution (15% per spec).
     */
    public static final double DEFAULT_STD_DEV = 0.15;

    /**
     * Smaller standard deviation for precise clicks (small targets).
     */
    public static final double PRECISE_STD_DEV = 0.10;

    /**
     * Larger standard deviation for imprecise clicks (large targets).
     */
    public static final double IMPRECISE_STD_DEV = 0.20;

    // ========================================================================
    // Private Constructor (Utility Class)
    // ========================================================================

    private ClickPointCalculator() {
        // Utility class - prevent instantiation
    }

    // ========================================================================
    // Widget Click Points
    // ========================================================================

    /**
     * Get a humanized click point for a widget.
     *
     * @param widget the widget to click
     * @return click point, or null if widget is not clickable
     */
    @Nullable
    public static Point getWidgetClickPoint(Widget widget) {
        if (widget == null || widget.isHidden()) {
            return null;
        }
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            return null;
        }
        return getGaussianClickPoint(bounds);
    }

    /**
     * Get a humanized click point for a widget by ID.
     *
     * @param client  the game client
     * @param groupId widget group ID
     * @param childId widget child ID
     * @return click point, or null if widget is not clickable
     */
    @Nullable
    public static Point getWidgetClickPoint(Client client, int groupId, int childId) {
        Widget widget = client.getWidget(groupId, childId);
        return getWidgetClickPoint(widget);
    }

    /**
     * Get a humanized click point for a widget, with a fallback point.
     *
     * @param widget   the widget to click
     * @param fallback fallback point if widget is not clickable
     * @return click point (never null if fallback is provided)
     */
    public static Point getWidgetClickPointOrDefault(Widget widget, Point fallback) {
        Point point = getWidgetClickPoint(widget);
        return point != null ? point : fallback;
    }

    // ========================================================================
    // NPC Click Points
    // ========================================================================

    /**
     * Get a humanized click point for an NPC.
     *
     * @param npc the NPC to click
     * @return click point, or null if NPC is not clickable
     */
    @Nullable
    public static Point getNpcClickPoint(NPC npc) {
        if (npc == null) {
            return null;
        }

        // Try convex hull first (more accurate)
        Shape hull = npc.getConvexHull();
        if (hull != null) {
            Rectangle bounds = hull.getBounds();
            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                return getGaussianClickPoint(bounds);
            }
        }

        // Fallback to canvas text location (name plate area)
        net.runelite.api.Point canvasLocation = npc.getCanvasTextLocation(null, "", 0);
        if (canvasLocation != null) {
            // Add some randomization around the name plate
            int x = canvasLocation.getX() + randomGaussianOffset(30, 10);
            int y = canvasLocation.getY() + randomGaussianOffset(20, 8);
            return new Point(x, y);
        }

        return null;
    }

    /**
     * Get a humanized click point for any Actor (NPC or Player).
     *
     * @param actor the actor to click
     * @return click point, or null if actor is not clickable
     */
    @Nullable
    public static Point getActorClickPoint(Actor actor) {
        if (actor == null) {
            return null;
        }

        Shape hull = actor.getConvexHull();
        if (hull != null) {
            Rectangle bounds = hull.getBounds();
            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                return getGaussianClickPoint(bounds);
            }
        }

        return null;
    }

    // ========================================================================
    // Game Object Click Points
    // ========================================================================

    /**
     * Get a humanized click point for a TileObject (GameObject, etc.).
     *
     * @param object the tile object to click
     * @return click point, or null if object is not clickable
     */
    @Nullable
    public static Point getTileObjectClickPoint(TileObject object) {
        if (object == null) {
            return null;
        }

        // Try clickbox first
        Shape clickbox = object.getClickbox();
        if (clickbox != null) {
            Rectangle bounds = clickbox.getBounds();
            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                return getGaussianClickPoint(bounds);
            }
        }

        // Fallback to canvas location
        net.runelite.api.Point canvasLocation = object.getCanvasLocation();
        if (canvasLocation != null) {
            // Add some randomization
            int x = canvasLocation.getX() + randomGaussianOffset(15, 5);
            int y = canvasLocation.getY() + randomGaussianOffset(15, 5);
            return new Point(x, y);
        }

        return null;
    }

    // ========================================================================
    // Generic Bounds Click Points
    // ========================================================================

    /**
     * Get a humanized click point within bounds using default Gaussian distribution.
     * Uses 45-55% center range and 15% standard deviation per spec.
     *
     * @param bounds the clickable bounds
     * @return humanized click point
     */
    public static Point getGaussianClickPoint(Rectangle bounds) {
        return getGaussianClickPoint(bounds, DEFAULT_STD_DEV);
    }

    /**
     * Get a humanized click point within bounds using Gaussian distribution
     * with a custom standard deviation.
     *
     * @param bounds the clickable bounds
     * @param stdDev standard deviation as fraction of dimension (0.10 = 10%)
     * @return humanized click point
     */
    public static Point getGaussianClickPoint(Rectangle bounds, double stdDev) {
        return getGaussianClickPoint(bounds, stdDev, getRandomCenterPercent());
    }

    /**
     * Get a humanized click point within bounds using Gaussian distribution
     * with custom standard deviation and center.
     *
     * @param bounds        the clickable bounds
     * @param stdDev        standard deviation as fraction of dimension
     * @param centerPercent center position as fraction (0.0-1.0)
     * @return humanized click point
     */
    public static Point getGaussianClickPoint(Rectangle bounds, double stdDev, double centerPercent) {
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            log.warn("Invalid bounds for click point calculation: {}", bounds);
            return new Point(0, 0);
        }

        int x = bounds.x + calculateGaussianOffset(bounds.width, stdDev, centerPercent);
        int y = bounds.y + calculateGaussianOffset(bounds.height, stdDev, centerPercent);

        return new Point(x, y);
    }

    /**
     * Get a uniform random click point within bounds.
     * Less humanized than Gaussian, but useful for specific scenarios.
     *
     * @param bounds the clickable bounds
     * @return random click point within bounds
     */
    public static Point getUniformClickPoint(Rectangle bounds) {
        return getUniformClickPoint(bounds, EDGE_PADDING);
    }

    /**
     * Get a uniform random click point within bounds with custom padding.
     *
     * @param bounds  the clickable bounds
     * @param padding minimum distance from edges
     * @return random click point within bounds
     */
    public static Point getUniformClickPoint(Rectangle bounds, int padding) {
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            log.warn("Invalid bounds for click point calculation: {}", bounds);
            return new Point(0, 0);
        }

        int effectiveWidth = Math.max(1, bounds.width - 2 * padding);
        int effectiveHeight = Math.max(1, bounds.height - 2 * padding);

        int x = bounds.x + padding + ThreadLocalRandom.current().nextInt(effectiveWidth);
        int y = bounds.y + padding + ThreadLocalRandom.current().nextInt(effectiveHeight);

        return new Point(x, y);
    }

    /**
     * Get the center point of bounds (for reference, not recommended for clicking).
     *
     * @param bounds the bounds
     * @return center point
     */
    public static Point getCenterPoint(Rectangle bounds) {
        if (bounds == null) {
            return new Point(0, 0);
        }
        return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }

    // ========================================================================
    // Shape Click Points (for convex hulls, polygons, etc.)
    // ========================================================================

    /**
     * Get a humanized click point within a shape (polygon, convex hull, etc.).
     *
     * @param shape the clickable shape
     * @return humanized click point, or null if shape is invalid
     */
    @Nullable
    public static Point getShapeClickPoint(Shape shape) {
        if (shape == null) {
            return null;
        }
        Rectangle bounds = shape.getBounds();
        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            return null;
        }

        // For complex shapes, generate points and verify they're within the shape
        // Try up to 10 times to find a valid point
        for (int attempt = 0; attempt < 10; attempt++) {
            Point candidate = getGaussianClickPoint(bounds);
            if (shape.contains(candidate)) {
                return candidate;
            }
        }

        // Fallback to bounds-based point
        return getGaussianClickPoint(bounds);
    }

    // ========================================================================
    // Offset Calculations
    // ========================================================================

    /**
     * Calculate a Gaussian offset within a dimension.
     * This is the core humanization algorithm per REQUIREMENTS.md Section 3.1.2.
     *
     * @param dimension     the width or height
     * @param stdDev        standard deviation as fraction
     * @param centerPercent center position as fraction
     * @return offset within dimension
     */
    public static int calculateGaussianOffset(int dimension, double stdDev, double centerPercent) {
        // Center position
        double center = dimension * centerPercent;

        // Standard deviation in pixels
        double stdDevPixels = dimension * stdDev;

        // Gaussian offset from center
        double offset = center + ThreadLocalRandom.current().nextGaussian() * stdDevPixels;

        // Clamp to valid range with edge padding
        return (int) Math.max(EDGE_PADDING, Math.min(dimension - EDGE_PADDING, offset));
    }

    /**
     * Calculate a humanized offset within a dimension using default parameters.
     * Uses 45-55% center range and 15% standard deviation.
     *
     * @param dimension the width or height
     * @return humanized offset
     */
    public static int calculateGaussianOffset(int dimension) {
        return calculateGaussianOffset(dimension, DEFAULT_STD_DEV, getRandomCenterPercent());
    }

    /**
     * Calculate a simple random Gaussian offset around a center value.
     *
     * @param range  total range of offset
     * @param stdDev standard deviation
     * @return offset from center
     */
    public static int randomGaussianOffset(int range, double stdDev) {
        return (int) (ThreadLocalRandom.current().nextGaussian() * stdDev);
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Get a random center percent within the spec range (45-55%).
     *
     * @return random center percent
     */
    public static double getRandomCenterPercent() {
        return CENTER_MIN + ThreadLocalRandom.current().nextDouble() * (CENTER_MAX - CENTER_MIN);
    }

    /**
     * Clamp a point to be within bounds.
     *
     * @param point  the point to clamp
     * @param bounds the bounds to clamp to
     * @return clamped point
     */
    public static Point clampToBounds(Point point, Rectangle bounds) {
        if (point == null || bounds == null) {
            return point;
        }
        int x = Math.max(bounds.x, Math.min(point.x, bounds.x + bounds.width - 1));
        int y = Math.max(bounds.y, Math.min(point.y, bounds.y + bounds.height - 1));
        return new Point(x, y);
    }

    /**
     * Clamp a point to be within bounds with padding.
     *
     * @param point   the point to clamp
     * @param bounds  the bounds to clamp to
     * @param padding minimum distance from edges
     * @return clamped point
     */
    public static Point clampToBounds(Point point, Rectangle bounds, int padding) {
        if (point == null || bounds == null) {
            return point;
        }
        int minX = bounds.x + padding;
        int maxX = bounds.x + bounds.width - padding;
        int minY = bounds.y + padding;
        int maxY = bounds.y + bounds.height - padding;

        int x = Math.max(minX, Math.min(point.x, maxX));
        int y = Math.max(minY, Math.min(point.y, maxY));
        return new Point(x, y);
    }

    /**
     * Check if bounds are valid for clicking.
     *
     * @param bounds the bounds to check
     * @return true if bounds are valid
     */
    public static boolean isValidBounds(Rectangle bounds) {
        return bounds != null && bounds.width > 0 && bounds.height > 0;
    }

    /**
     * Check if bounds are large enough for humanized clicking.
     * Very small bounds (< 10px) may not have room for Gaussian distribution.
     *
     * @param bounds the bounds to check
     * @return true if bounds are large enough
     */
    public static boolean isLargeEnoughForHumanization(Rectangle bounds) {
        return bounds != null && bounds.width >= 10 && bounds.height >= 10;
    }

    /**
     * Get an appropriate standard deviation for given bounds.
     * Smaller bounds get smaller stddev to avoid edge clicks.
     *
     * @param bounds the bounds
     * @return appropriate standard deviation
     */
    public static double getAppropriateStdDev(Rectangle bounds) {
        if (bounds == null) {
            return DEFAULT_STD_DEV;
        }
        int minDimension = Math.min(bounds.width, bounds.height);
        if (minDimension < 20) {
            return PRECISE_STD_DEV;
        } else if (minDimension > 100) {
            return IMPRECISE_STD_DEV;
        }
        return DEFAULT_STD_DEV;
    }

    /**
     * Get click point with automatically adjusted standard deviation based on bounds size.
     *
     * @param bounds the clickable bounds
     * @return humanized click point with appropriate precision
     */
    public static Point getAdaptiveClickPoint(Rectangle bounds) {
        double stdDev = getAppropriateStdDev(bounds);
        return getGaussianClickPoint(bounds, stdDev);
    }
}

