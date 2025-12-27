package com.rocinante.puzzle;

import lombok.Builder;
import lombok.Value;

/**
 * Represents a widget click instruction for puzzle solving.
 *
 * <p>Encapsulates all information needed to click a specific widget element:
 * <ul>
 *   <li>Widget group and child IDs</li>
 *   <li>Optional dynamic child index (for widgets with dynamic children)</li>
 *   <li>Optional description for logging</li>
 * </ul>
 *
 * <p>Used by puzzle solvers to specify the sequence of clicks needed to solve a puzzle.
 */
@Value
@Builder
public class WidgetClick {

    /**
     * The widget group ID.
     */
    int groupId;

    /**
     * The widget child ID within the group.
     */
    int childId;

    /**
     * Optional dynamic child index for widgets with dynamic children.
     * -1 indicates no dynamic child (click the widget itself).
     */
    @Builder.Default
    int dynamicChildIndex = -1;

    /**
     * Optional description for logging/debugging.
     */
    String description;

    /**
     * Create a simple widget click (no dynamic child).
     *
     * @param groupId the widget group ID
     * @param childId the widget child ID
     * @return a new WidgetClick
     */
    public static WidgetClick of(int groupId, int childId) {
        return WidgetClick.builder()
                .groupId(groupId)
                .childId(childId)
                .build();
    }

    /**
     * Create a widget click with dynamic child index.
     *
     * @param groupId          the widget group ID
     * @param childId          the widget child ID
     * @param dynamicChildIndex the dynamic child index
     * @return a new WidgetClick
     */
    public static WidgetClick of(int groupId, int childId, int dynamicChildIndex) {
        return WidgetClick.builder()
                .groupId(groupId)
                .childId(childId)
                .dynamicChildIndex(dynamicChildIndex)
                .build();
    }

    /**
     * Create a widget click with description.
     *
     * @param groupId     the widget group ID
     * @param childId     the widget child ID
     * @param description the click description
     * @return a new WidgetClick
     */
    public static WidgetClick of(int groupId, int childId, String description) {
        return WidgetClick.builder()
                .groupId(groupId)
                .childId(childId)
                .description(description)
                .build();
    }

    /**
     * Create a widget click with all parameters.
     *
     * @param groupId          the widget group ID
     * @param childId          the widget child ID
     * @param dynamicChildIndex the dynamic child index
     * @param description      the click description
     * @return a new WidgetClick
     */
    public static WidgetClick of(int groupId, int childId, int dynamicChildIndex, String description) {
        return WidgetClick.builder()
                .groupId(groupId)
                .childId(childId)
                .dynamicChildIndex(dynamicChildIndex)
                .description(description)
                .build();
    }

    /**
     * Check if this click targets a dynamic child.
     *
     * @return true if dynamicChildIndex is >= 0
     */
    public boolean hasDynamicChild() {
        return dynamicChildIndex >= 0;
    }

    /**
     * Get a human-readable representation of this click.
     *
     * @return formatted string describing the click target
     */
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Widget[").append(groupId).append(":").append(childId);
        if (hasDynamicChild()) {
            sb.append(":").append(dynamicChildIndex);
        }
        sb.append("]");
        if (description != null && !description.isEmpty()) {
            sb.append(" - ").append(description);
        }
        return sb.toString();
    }
}

