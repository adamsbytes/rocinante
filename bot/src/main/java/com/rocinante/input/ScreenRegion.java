package com.rocinante.input;

import com.rocinante.util.Randomization;
import java.awt.Rectangle;

/**
 * Screen regions for idle mouse behavior as specified in REQUIREMENTS.md Section 3.1.3.
 * These represent common "rest positions" where human players typically leave their mouse.
 *
 * Coordinates are based on the standard RuneLite client in fixed mode (765x503 game area).
 * Resizable mode support can be added by scaling these regions relative to client dimensions.
 */
public enum ScreenRegion {

    /**
     * Inventory area - most common rest position for mouse.
     * Located on the right side of the screen.
     */
    INVENTORY(561, 213, 176, 261),

    /**
     * Minimap area - used for navigation and checking surroundings.
     * Located in the top-right corner.
     */
    MINIMAP(527, 4, 210, 167),

    /**
     * Chat box area - where messages appear.
     * Located at the bottom of the screen.
     */
    CHAT(7, 345, 505, 128),

    /**
     * Prayer tab area - frequently used during combat.
     */
    PRAYER(526, 213, 88, 260),

    /**
     * Equipment tab area - used for gear checks.
     */
    EQUIPMENT(561, 213, 176, 261),

    /**
     * Main game viewport - the 3D game world area.
     * This is where most interactions occur.
     */
    VIEWPORT(4, 4, 512, 334),

    /**
     * Combat options area - attack styles tab.
     */
    COMBAT_OPTIONS(526, 213, 88, 260),

    /**
     * Magic spellbook area.
     */
    MAGIC(526, 213, 88, 260),

    /**
     * Skills tab area.
     */
    SKILLS(526, 213, 88, 260),

    /**
     * Quest tab area.
     */
    QUESTS(526, 213, 88, 260),

    /**
     * Bank interface area (when bank is open).
     */
    BANK(73, 41, 408, 280),

    /**
     * Logout button area.
     */
    LOGOUT(627, 475, 26, 26),

    /**
     * World map button area.
     */
    WORLD_MAP(527, 125, 35, 35),

    /**
     * XP tracker area (near minimap).
     */
    XP_TRACKER(527, 175, 35, 35),

    /**
     * Run energy orb area.
     */
    RUN_ORB(527, 140, 35, 35),

    /**
     * Health orb area.
     */
    HEALTH_ORB(527, 75, 35, 35),

    /**
     * Prayer orb area.
     */
    PRAYER_ORB(527, 108, 35, 35),

    /**
     * Special attack orb area.
     */
    SPECIAL_ATTACK_ORB(570, 140, 35, 35);

    private final int x;
    private final int y;
    private final int width;
    private final int height;

    ScreenRegion(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /**
     * Get the X coordinate of the region's top-left corner.
     */
    public int getX() {
        return x;
    }

    /**
     * Get the Y coordinate of the region's top-left corner.
     */
    public int getY() {
        return y;
    }

    /**
     * Get the width of the region.
     */
    public int getWidth() {
        return width;
    }

    /**
     * Get the height of the region.
     */
    public int getHeight() {
        return height;
    }

    /**
     * Get the center X coordinate of the region.
     */
    public int getCenterX() {
        return x + width / 2;
    }

    /**
     * Get the center Y coordinate of the region.
     */
    public int getCenterY() {
        return y + height / 2;
    }

    /**
     * Get the region as a Rectangle.
     */
    public Rectangle toRectangle() {
        return new Rectangle(x, y, width, height);
    }

    /**
     * Check if a point is within this region.
     *
     * @param px the X coordinate
     * @param py the Y coordinate
     * @return true if the point is within the region
     */
    public boolean contains(int px, int py) {
        return px >= x && px < x + width && py >= y && py < y + height;
    }

    /**
     * Get a random point within this region using uniform distribution.
     *
     * @param random the randomization instance
     * @return an int array [x, y] with a random point in the region
     */
    public int[] getRandomPoint(Randomization random) {
        int px = random.uniformRandomInt(x, x + width - 1);
        int py = random.uniformRandomInt(y, y + height - 1);
        return new int[]{px, py};
    }

    /**
     * Get a random point biased toward the center (Gaussian distribution).
     *
     * @param random the randomization instance
     * @return an int array [x, y] with a random point biased toward center
     */
    public int[] getGaussianPoint(Randomization random) {
        return random.generateClickPosition(x, y, width, height);
    }

    /**
     * Scale this region for resizable mode.
     *
     * @param scaleX horizontal scale factor (clientWidth / 765.0)
     * @param scaleY vertical scale factor (clientHeight / 503.0)
     * @return a new Rectangle with scaled coordinates
     */
    public Rectangle scaled(double scaleX, double scaleY) {
        return new Rectangle(
                (int) (x * scaleX),
                (int) (y * scaleY),
                (int) (width * scaleX),
                (int) (height * scaleY)
        );
    }

    /**
     * Get the default idle regions as specified in REQUIREMENTS.md Section 3.1.3.
     * "Mouse moves to a rest position (inventory area, minimap, or chat)"
     *
     * @return array of default idle regions
     */
    public static ScreenRegion[] getDefaultIdleRegions() {
        return new ScreenRegion[]{INVENTORY, MINIMAP, CHAT};
    }

    /**
     * Get regions commonly used during combat.
     *
     * @return array of combat-related regions
     */
    public static ScreenRegion[] getCombatRegions() {
        return new ScreenRegion[]{INVENTORY, PRAYER, HEALTH_ORB, PRAYER_ORB, SPECIAL_ATTACK_ORB};
    }

    /**
     * Get regions on the right side of the screen (for dominant hand bias).
     * As per REQUIREMENTS.md Section 3.3: "Dominant hand simulation
     * (slight bias toward right-side screen interactions)"
     *
     * @return array of right-side regions
     */
    public static ScreenRegion[] getRightSideRegions() {
        return new ScreenRegion[]{INVENTORY, MINIMAP, PRAYER, EQUIPMENT, COMBAT_OPTIONS, MAGIC, SKILLS, QUESTS};
    }

    /**
     * Get regions on the left side of the screen.
     *
     * @return array of left-side regions
     */
    public static ScreenRegion[] getLeftSideRegions() {
        return new ScreenRegion[]{CHAT, VIEWPORT};
    }

    /**
     * Check if this region is on the right side of the screen.
     *
     * @return true if the region's center is on the right half
     */
    public boolean isRightSide() {
        return getCenterX() > 382; // Half of 765
    }

    /**
     * Check if this region is on the left side of the screen.
     *
     * @return true if the region's center is on the left half
     */
    public boolean isLeftSide() {
        return !isRightSide();
    }
}

