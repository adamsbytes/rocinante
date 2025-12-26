package com.rocinante.input;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * A point in CANVAS-RELATIVE coordinates.
 * 
 * Canvas coordinates are relative to the game canvas (0,0 is top-left of the game view).
 * These coordinates come from:
 * <ul>
 *   <li>{@code widget.getBounds()}</li>
 *   <li>{@code npc.getConvexHull()}</li>
 *   <li>{@code object.getClickbox()}</li>
 *   <li>{@code Perspective.localToCanvas()}</li>
 * </ul>
 * 
 * <p>To convert to screen coordinates for Robot input, use 
 * {@link RobotMouseController#canvasToScreen(CanvasPoint)}.
 * 
 * <p>This type exists to prevent accidentally passing canvas coordinates 
 * to methods expecting screen coordinates (and vice versa).
 * 
 * @see ScreenPoint
 */
public final class CanvasPoint {
    
    private final int x;
    private final int y;
    
    public CanvasPoint(int x, int y) {
        this.x = x;
        this.y = y;
    }
    
    public CanvasPoint(Point point) {
        this.x = point.x;
        this.y = point.y;
    }
    
    public int getX() {
        return x;
    }
    
    public int getY() {
        return y;
    }
    
    public Point toAwtPoint() {
        return new Point(x, y);
    }
    
    /**
     * Create a CanvasPoint from a Rectangle's center, with optional Gaussian offset.
     */
    public static CanvasPoint fromBoundsCenter(Rectangle bounds) {
        return new CanvasPoint(
            bounds.x + bounds.width / 2,
            bounds.y + bounds.height / 2
        );
    }
    
    /**
     * Create a CanvasPoint from a Rectangle's center with random offset.
     */
    public static CanvasPoint fromBoundsWithOffset(Rectangle bounds, double xOffsetRatio, double yOffsetRatio) {
        return new CanvasPoint(
            bounds.x + bounds.width / 2 + (int)(bounds.width * xOffsetRatio),
            bounds.y + bounds.height / 2 + (int)(bounds.height * yOffsetRatio)
        );
    }
    
    @Override
    public String toString() {
        return String.format("CanvasPoint(%d, %d)", x, y);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CanvasPoint)) return false;
        CanvasPoint other = (CanvasPoint) obj;
        return x == other.x && y == other.y;
    }
    
    @Override
    public int hashCode() {
        return 31 * x + y;
    }
}

