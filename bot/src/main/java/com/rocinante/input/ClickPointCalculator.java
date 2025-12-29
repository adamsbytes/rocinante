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
        return getNpcClickPoint(npc, null);
    }
    
    /**
     * Get a humanized click point for an NPC, validated against canvas bounds.
     * Clickboxes can extend beyond the 3D game area, so we use canvas bounds.
     *
     * @param npc    the NPC to click
     * @param client the game client for canvas validation
     * @return click point within canvas, or null if NPC is not on screen
     */
    @Nullable
    public static Point getNpcClickPoint(NPC npc, @Nullable Client client) {
        if (npc == null) {
            return null;
        }
        
        Rectangle canvasBounds = getCanvasBounds(client);

        // Try convex hull first (more accurate)
        Shape hull = npc.getConvexHull();
        if (hull != null) {
            Rectangle bounds = hull.getBounds();
            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                // Use intersection to ensure we only click within canvas
                Point point = getGaussianClickPointIntersected(bounds, canvasBounds);
                if (point != null) {
                    return point;
                }
            }
        }

        // Fallback to canvas text location (name plate area)
        net.runelite.api.Point canvasLocation = npc.getCanvasTextLocation(null, "", 0);
        if (canvasLocation != null) {
            // Add some randomization around the name plate
            int x = canvasLocation.getX() + randomGaussianOffset(30, 10);
            int y = canvasLocation.getY() + randomGaussianOffset(20, 8);
            Point point = new Point(x, y);
            
            // Validate within canvas
            if (canvasBounds.contains(point)) {
                return point;
            }
            // Try clamping if close to edge
            Point clamped = clampToBounds(point, canvasBounds);
            if (Math.abs(clamped.x - point.x) < 20 && Math.abs(clamped.y - point.y) < 20) {
                return clamped;
            }
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
        return getActorClickPoint(actor, null);
    }
    
    /**
     * Get a humanized click point for any Actor, validated against canvas bounds.
     *
     * @param actor  the actor to click
     * @param client the game client for canvas validation
     * @return click point within canvas, or null if actor is not on screen
     */
    @Nullable
    public static Point getActorClickPoint(Actor actor, @Nullable Client client) {
        if (actor == null) {
            return null;
        }
        
        Rectangle canvasBounds = getCanvasBounds(client);

        Shape hull = actor.getConvexHull();
        if (hull != null) {
            Rectangle bounds = hull.getBounds();
            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                // Use intersection to ensure we only click within canvas
                return getGaussianClickPointIntersected(bounds, canvasBounds);
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
        return getTileObjectClickPoint(object, null);
    }
    
    /**
     * Get a humanized click point for a TileObject, validated against canvas bounds.
     * Clickboxes can extend beyond the 3D game area, so we use canvas bounds.
     *
     * @param object the tile object to click
     * @param client the game client for canvas validation
     * @return click point within canvas, or null if object is not on screen
     */
    @Nullable
    public static Point getTileObjectClickPoint(TileObject object, @Nullable Client client) {
        if (object == null) {
            return null;
        }
        
        Rectangle canvasBounds = getCanvasBounds(client);

        // Try clickbox first
        Shape clickbox = object.getClickbox();
        if (clickbox != null) {
            Rectangle bounds = clickbox.getBounds();
            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                // Use intersection to ensure we only click within canvas
                Point point = getGaussianClickPointIntersected(bounds, canvasBounds);
                if (point != null) {
                    return point;
                }
            }
        }

        // Fallback to canvas location
        net.runelite.api.Point canvasLocation = object.getCanvasLocation();
        if (canvasLocation != null) {
            // Add some randomization
            int x = canvasLocation.getX() + randomGaussianOffset(15, 5);
            int y = canvasLocation.getY() + randomGaussianOffset(15, 5);
            Point point = new Point(x, y);
            
            // Validate within canvas
            if (canvasBounds.contains(point)) {
                return point;
            }
            // Try clamping if close to edge
            Point clamped = clampToBounds(point, canvasBounds);
            if (Math.abs(clamped.x - point.x) < 15 && Math.abs(clamped.y - point.y) < 15) {
                return clamped;
            }
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
     * GUARANTEES: The returned point is ALWAYS within the bounds rectangle.
     *
     * @param bounds        the clickable bounds
     * @param stdDev        standard deviation as fraction of dimension
     * @param centerPercent center position as fraction (0.0-1.0)
     * @return humanized click point, guaranteed within bounds
     */
    public static Point getGaussianClickPoint(Rectangle bounds, double stdDev, double centerPercent) {
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            log.warn("Invalid bounds for click point calculation: {}", bounds);
            return new Point(0, 0);
        }

        int offsetX = calculateGaussianOffset(bounds.width, stdDev, centerPercent);
        int offsetY = calculateGaussianOffset(bounds.height, stdDev, centerPercent);
        
        int x = bounds.x + offsetX;
        int y = bounds.y + offsetY;

        // HARD CLAMP: Guarantee point is within bounds (paranoid safety)
        x = Math.max(bounds.x, Math.min(x, bounds.x + bounds.width - 1));
        y = Math.max(bounds.y, Math.min(y, bounds.y + bounds.height - 1));

        return new Point(x, y);
    }
    
    /**
     * Get a humanized click point within bounds, also clamped to canvas.
     * Use this when you need to guarantee the click is within the game window.
     *
     * @param bounds     the clickable bounds
     * @param canvasRect the canvas rectangle (entire game window)
     * @return humanized click point, guaranteed within both bounds and canvas
     */
    public static Point getGaussianClickPointInCanvas(Rectangle bounds, Rectangle canvasRect) {
        Point point = getGaussianClickPoint(bounds);
        
        if (canvasRect == null || canvasRect.width <= 0 || canvasRect.height <= 0) {
            return point;
        }
        
        // Clamp to canvas
        int x = Math.max(canvasRect.x, Math.min(point.x, canvasRect.x + canvasRect.width - 1));
        int y = Math.max(canvasRect.y, Math.min(point.y, canvasRect.y + canvasRect.height - 1));
        
        return new Point(x, y);
    }
    
    /**
     * Alias for getGaussianClickPointInCanvas.
     * @deprecated Use getGaussianClickPointInCanvas for clarity
     */
    @Deprecated
    public static Point getGaussianClickPointInViewport(Rectangle bounds, Rectangle viewportRect) {
        return getGaussianClickPointInCanvas(bounds, viewportRect);
    }
    
    /**
     * Alias for getGaussianClickPointInCanvas.
     * @deprecated Use getGaussianClickPointInCanvas - game objects can have clickboxes outside game area
     */
    @Deprecated
    public static Point getGaussianClickPointInGameArea(Rectangle bounds, Rectangle gameArea) {
        return getGaussianClickPointInCanvas(bounds, gameArea);
    }
    
    /**
     * Get a humanized click point, intersecting bounds with valid area first.
     * This ensures the click is only within the visible portion of the bounds.
     *
     * @param bounds    the clickable bounds
     * @param validArea the valid clickable area (viewport or game area)
     * @return humanized click point within the intersection, or null if no intersection
     */
    @Nullable
    public static Point getGaussianClickPointIntersected(Rectangle bounds, Rectangle validArea) {
        if (bounds == null || validArea == null) {
            return null;
        }
        
        // Calculate intersection
        Rectangle intersection = bounds.intersection(validArea);
        if (intersection.isEmpty() || intersection.width <= 0 || intersection.height <= 0) {
            log.debug("Bounds {} do not intersect valid area {}", bounds, validArea);
            return null;
        }
        
        // Generate point within the intersection only
        return getGaussianClickPoint(intersection);
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
        return getShapeClickPoint(shape, null);
    }
    
    /**
     * Get a humanized click point within a shape, validated against canvas bounds.
     *
     * @param shape  the clickable shape
     * @param client the game client for canvas validation
     * @return humanized click point within canvas, or null if shape is not on screen
     */
    @Nullable
    public static Point getShapeClickPoint(Shape shape, @Nullable Client client) {
        if (shape == null) {
            return null;
        }
        Rectangle bounds = shape.getBounds();
        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            return null;
        }
        
        Rectangle canvasBounds = getCanvasBounds(client);
        
        // Calculate intersection with canvas
        Rectangle validBounds = bounds.intersection(canvasBounds);
        if (validBounds.isEmpty() || validBounds.width <= 0 || validBounds.height <= 0) {
            return null; // Shape is not on screen
        }

        // For complex shapes, generate points and verify they're within the shape AND canvas
        // Try up to 10 times to find a valid point
        for (int attempt = 0; attempt < 10; attempt++) {
            Point candidate = getGaussianClickPoint(validBounds);
            if (shape.contains(candidate) && canvasBounds.contains(candidate)) {
                return candidate;
            }
        }

        // Fallback to intersection-based point (guaranteed in canvas)
        return getGaussianClickPoint(validBounds);
    }

    // ========================================================================
    // Offset Calculations
    // ========================================================================

    /**
     * Calculate a Gaussian offset within a dimension.
     * This is the core humanization algorithm per REQUIREMENTS.md Section 3.1.2.
     * 
     * GUARANTEES: The returned offset is ALWAYS within [0, dimension-1].
     * Edge padding is applied when dimension allows, but small dimensions
     * still produce valid in-bounds offsets.
     *
     * @param dimension     the width or height
     * @param stdDev        standard deviation as fraction
     * @param centerPercent center position as fraction
     * @return offset within dimension, guaranteed to be in [0, dimension-1]
     */
    public static int calculateGaussianOffset(int dimension, double stdDev, double centerPercent) {
        if (dimension <= 0) {
            return 0;
        }
        
        // For very small dimensions, just return center
        if (dimension <= 2 * EDGE_PADDING) {
            return dimension / 2;
        }
        
        // Center position
        double center = dimension * centerPercent;

        // Standard deviation in pixels
        double stdDevPixels = dimension * stdDev;

        // Gaussian offset from center
        double offset = center + ThreadLocalRandom.current().nextGaussian() * stdDevPixels;

        // HARD CLAMP to valid range - guaranteed within bounds
        int minOffset = Math.min(EDGE_PADDING, dimension - 1);
        int maxOffset = Math.max(0, dimension - 1 - EDGE_PADDING);
        
        // Ensure min <= max
        if (minOffset > maxOffset) {
            return dimension / 2;
        }
        
        return (int) Math.max(minOffset, Math.min(maxOffset, offset));
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

    // ========================================================================
    // Canvas and Game Area Utilities
    // ========================================================================
    
    /** Fixed mode canvas dimensions (entire game window) */
    private static final int FIXED_CANVAS_WIDTH = 765;
    private static final int FIXED_CANVAS_HEIGHT = 503;
    
    /** Fixed mode 3D game area (world viewport within canvas) */
    private static final int FIXED_GAME_AREA_X = 4;
    private static final int FIXED_GAME_AREA_Y = 4;
    private static final int FIXED_GAME_AREA_WIDTH = 512;
    private static final int FIXED_GAME_AREA_HEIGHT = 334;
    
    /**
     * Get the canvas rectangle (entire game window).
     * This is the area where clicks are valid - includes UI and 3D world.
     *
     * @param client the game client (can be null for fixed mode fallback)
     * @return canvas rectangle
     */
    public static Rectangle getCanvasBounds(@Nullable Client client) {
        if (client == null) {
            return new Rectangle(0, 0, FIXED_CANVAS_WIDTH, FIXED_CANVAS_HEIGHT);
        }
        
        java.awt.Canvas canvas = client.getCanvas();
        if (canvas == null) {
            return new Rectangle(0, 0, FIXED_CANVAS_WIDTH, FIXED_CANVAS_HEIGHT);
        }
        
        return new Rectangle(0, 0, canvas.getWidth(), canvas.getHeight());
    }
    
    /**
     * Get the 3D game area rectangle (world viewport within canvas).
     * This is where game objects are RENDERED, but their clickboxes may
     * extend beyond this into the UI space.
     *
     * @param client the game client (can be null for fixed mode fallback)
     * @return 3D game area rectangle
     */
    public static Rectangle getGameAreaBounds(@Nullable Client client) {
        if (client == null) {
            return new Rectangle(FIXED_GAME_AREA_X, FIXED_GAME_AREA_Y, 
                    FIXED_GAME_AREA_WIDTH, FIXED_GAME_AREA_HEIGHT);
        }
        
        int x = client.getViewportXOffset();
        int y = client.getViewportYOffset();
        int width = client.getViewportWidth();
        int height = client.getViewportHeight();
        
        if (width <= 0 || height <= 0) {
            return new Rectangle(FIXED_GAME_AREA_X, FIXED_GAME_AREA_Y, 
                    FIXED_GAME_AREA_WIDTH, FIXED_GAME_AREA_HEIGHT);
        }
        
        return new Rectangle(x, y, width, height);
    }
    
    /**
     * Alias for getCanvasBounds - for semantic clarity in some contexts.
     * @deprecated Use getCanvasBounds for clicks, getGameAreaBounds for visibility checks
     */
    @Deprecated
    public static Rectangle getViewportBounds(@Nullable Client client) {
        return getCanvasBounds(client);
    }
    
    /**
     * Check if a point is within the canvas (clickable area).
     *
     * @param point  the point to check
     * @param client the game client
     * @return true if point is within canvas
     */
    public static boolean isInCanvas(Point point, @Nullable Client client) {
        if (point == null) {
            return false;
        }
        return getCanvasBounds(client).contains(point);
    }
    
    /**
     * Check if a point is within the canvas.
     * @deprecated Use isInCanvas for clarity
     */
    @Deprecated
    public static boolean isInViewport(Point point, @Nullable Client client) {
        return isInCanvas(point, client);
    }
    
    /**
     * Check if a point is within the 3D game area.
     * Use this for visibility checks, NOT for click validation.
     *
     * @param point  the point to check
     * @param client the game client
     * @return true if point is within 3D game area
     */
    public static boolean isInGameArea(Point point, @Nullable Client client) {
        if (point == null) {
            return false;
        }
        return getGameAreaBounds(client).contains(point);
    }
    
    /**
     * Clamp a point to be within the canvas (clickable area).
     *
     * @param point  the point to clamp
     * @param client the game client
     * @return clamped point
     */
    public static Point clampToCanvas(Point point, @Nullable Client client) {
        return clampToBounds(point, getCanvasBounds(client));
    }
    
    /**
     * Clamp a point to be within the canvas.
     * @deprecated Use clampToCanvas for clarity
     */
    @Deprecated
    public static Point clampToViewport(Point point, @Nullable Client client) {
        return clampToCanvas(point, client);
    }
    
    /**
     * Clamp a point to be within the 3D game area.
     *
     * @param point  the point to clamp
     * @param client the game client
     * @return clamped point
     */
    public static Point clampToGameArea(Point point, @Nullable Client client) {
        return clampToBounds(point, getGameAreaBounds(client));
    }
    
    // ========================================================================
    // Bounds Clamping
    // ========================================================================
    
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

