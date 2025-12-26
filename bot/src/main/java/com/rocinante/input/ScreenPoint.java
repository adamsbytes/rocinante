package com.rocinante.input;

import java.awt.Point;

/**
 * A point in SCREEN-ABSOLUTE coordinates.
 * 
 * Screen coordinates are absolute to the display (0,0 is top-left of the monitor).
 * These coordinates are used by:
 * <ul>
 *   <li>{@code java.awt.Robot.mouseMove()}</li>
 *   <li>{@code MouseInfo.getPointerInfo().getLocation()}</li>
 *   <li>{@code component.getLocationOnScreen()}</li>
 * </ul>
 * 
 * <p>Most game coordinates are canvas-relative and need to be converted 
 * to screen coordinates before use with Robot.
 * 
 * <p>This type exists to prevent accidentally passing screen coordinates 
 * to methods expecting canvas coordinates (and vice versa).
 * 
 * @see CanvasPoint
 */
public final class ScreenPoint {
    
    private final int x;
    private final int y;
    
    public ScreenPoint(int x, int y) {
        this.x = x;
        this.y = y;
    }
    
    public ScreenPoint(Point point) {
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
     * Calculate distance to another screen point.
     */
    public double distanceTo(ScreenPoint other) {
        int dx = other.x - x;
        int dy = other.y - y;
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    /**
     * Create a new ScreenPoint offset from this one.
     */
    public ScreenPoint offset(int dx, int dy) {
        return new ScreenPoint(x + dx, y + dy);
    }
    
    @Override
    public String toString() {
        return String.format("ScreenPoint(%d, %d)", x, y);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ScreenPoint)) return false;
        ScreenPoint other = (ScreenPoint) obj;
        return x == other.x && y == other.y;
    }
    
    @Override
    public int hashCode() {
        return 31 * x + y;
    }
}

