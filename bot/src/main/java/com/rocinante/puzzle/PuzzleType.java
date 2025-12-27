package com.rocinante.puzzle;

/**
 * Supported puzzle types in OSRS.
 *
 * <p>Each puzzle type has:
 * <ul>
 *   <li>A unique widget group ID for detection</li>
 *   <li>A specific solving algorithm</li>
 *   <li>Different state representation</li>
 * </ul>
 *
 * <p>Currently supports core treasure trail puzzles:
 * <ul>
 *   <li>{@link #SLIDING_PUZZLE} - 5x5 sliding tile puzzle (15-puzzle variant)</li>
 *   <li>{@link #LIGHT_BOX} - Lights-out style puzzle with 8 buttons</li>
 * </ul>
 */
public enum PuzzleType {

    /**
     * 5x5 sliding tile puzzle (15-puzzle variant).
     * 
     * <p>Found in treasure trails and some quests (e.g., Recruitment Drive).
     * Widget ID: 306 (InterfaceID.TRAIL_SLIDEPUZZLE)
     * 
     * <p>Solution uses IDA* search with Manhattan distance heuristic.
     */
    SLIDING_PUZZLE(306, "Sliding Puzzle"),

    /**
     * Light box puzzle with 8 buttons (A-H).
     * 
     * <p>Found in treasure trails. Each button toggles a pattern of lights.
     * Widget ID: 322 (InterfaceID.LIGHT_PUZZLE)
     * 
     * <p>Solution tracks button effects and uses combination logic.
     */
    LIGHT_BOX(322, "Light Box"),

    /**
     * Unknown or unsupported puzzle type.
     * Used when puzzle detection fails or for future extensibility.
     */
    UNKNOWN(-1, "Unknown Puzzle");

    private final int widgetGroupId;
    private final String displayName;

    PuzzleType(int widgetGroupId, String displayName) {
        this.widgetGroupId = widgetGroupId;
        this.displayName = displayName;
    }

    /**
     * Get the widget group ID for this puzzle type.
     *
     * @return the widget group ID, or -1 for unknown
     */
    public int getWidgetGroupId() {
        return widgetGroupId;
    }

    /**
     * Get a human-readable display name for this puzzle type.
     *
     * @return the display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Detect puzzle type from a visible widget group ID.
     *
     * @param widgetGroupId the widget group ID to check
     * @return the matching puzzle type, or {@link #UNKNOWN} if not recognized
     */
    public static PuzzleType fromWidgetGroupId(int widgetGroupId) {
        for (PuzzleType type : values()) {
            if (type.widgetGroupId == widgetGroupId) {
                return type;
            }
        }
        return UNKNOWN;
    }

    /**
     * Check if this puzzle type is supported (has a known solver).
     *
     * @return true if the puzzle can be solved algorithmically
     */
    public boolean isSupported() {
        return this != UNKNOWN;
    }
}

