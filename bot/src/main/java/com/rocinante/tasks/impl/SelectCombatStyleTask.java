package com.rocinante.tasks.impl;

import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.widgets.Widget;

import java.awt.event.KeyEvent;

/**
 * Task to select a combat attack style.
 *
 * Per REQUIREMENTS.md Section 10.1.8, supports XP goal-based combat style selection.
 * Changes attack style by clicking the appropriate widget in the combat options tab.
 *
 * <p>Combat styles vary by weapon type, but generally:
 * <ul>
 *   <li>Style 0: Accurate (Attack XP)</li>
 *   <li>Style 1: Aggressive (Strength XP)</li>
 *   <li>Style 2: Defensive (Defence XP)</li>
 *   <li>Style 3: Controlled (All three)</li>
 * </ul>
 */
@Slf4j
public class SelectCombatStyleTask extends AbstractTask {

    /**
     * Combat options widget group ID.
     */
    private static final int COMBAT_OPTIONS_GROUP = 593;

    /**
     * Widget child IDs for combat styles.
     * These are the clickable style buttons in the combat tab.
     */
    private static final int STYLE_1_WIDGET = 4;
    private static final int STYLE_2_WIDGET = 8;
    private static final int STYLE_3_WIDGET = 12;
    private static final int STYLE_4_WIDGET = 16;

    /**
     * Target combat style index (0-3).
     */
    @Getter
    private final int targetStyle;

    /**
     * Human-readable style description.
     */
    @Getter
    private final String styleDescription;

    /**
     * Execution state tracking.
     */
    private enum ExecutionPhase {
        OPENING_TAB,
        CLICKING_STYLE,
        VERIFYING,
        DONE
    }

    private ExecutionPhase phase = ExecutionPhase.OPENING_TAB;
    private int ticksInPhase = 0;

    /**
     * Create a combat style selection task.
     *
     * @param targetStyle      the style index (0-3)
     * @param styleDescription human-readable description
     */
    public SelectCombatStyleTask(int targetStyle, String styleDescription) {
        this.targetStyle = Math.max(0, Math.min(3, targetStyle));
        this.styleDescription = styleDescription;
    }

    /**
     * Create a task to select accurate style (Attack XP).
     */
    public static SelectCombatStyleTask accurate() {
        return new SelectCombatStyleTask(0, "Accurate (Attack)");
    }

    /**
     * Create a task to select aggressive style (Strength XP).
     */
    public static SelectCombatStyleTask aggressive() {
        return new SelectCombatStyleTask(1, "Aggressive (Strength)");
    }

    /**
     * Create a task to select defensive style (Defence XP).
     */
    public static SelectCombatStyleTask defensive() {
        return new SelectCombatStyleTask(2, "Defensive (Defence)");
    }

    /**
     * Create a task to select controlled style (Shared XP).
     */
    public static SelectCombatStyleTask controlled() {
        return new SelectCombatStyleTask(3, "Controlled (Shared)");
    }

    @Override
    public String getDescription() {
        return "Select combat style: " + styleDescription;
    }

    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.getClient().getLocalPlayer() != null;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        ticksInPhase++;

        switch (phase) {
            case OPENING_TAB -> {
                // Press F1 to open combat tab
                log.debug("Opening combat tab with F1");
                ctx.getKeyboardController().pressKey(KeyEvent.VK_F1);
                phase = ExecutionPhase.CLICKING_STYLE;
                ticksInPhase = 0;
            }
            case CLICKING_STYLE -> {
                // Wait a tick for tab to open
                if (ticksInPhase < 2) {
                    return;
                }

                int widgetChild = getWidgetChildForStyle(targetStyle);
                Widget styleWidget = ctx.getClient().getWidget(COMBAT_OPTIONS_GROUP, widgetChild);

                if (styleWidget == null || styleWidget.isHidden()) {
                    log.warn("Combat style widget not visible, retrying...");
                    if (ticksInPhase > 10) {
                        fail("Combat style widget not visible after 10 ticks");
                    }
                    return;
                }

                log.debug("Clicking combat style: {}", styleDescription);
                ctx.getWidgetClickHelper().clickWidget(COMBAT_OPTIONS_GROUP, widgetChild, styleDescription);
                phase = ExecutionPhase.VERIFYING;
                ticksInPhase = 0;
            }
            case VERIFYING -> {
                // Wait for click to register
                if (ticksInPhase >= 2) {
                    log.info("Combat style selection completed: {}", styleDescription);
                    phase = ExecutionPhase.DONE;
                    complete();
                }
            }
            case DONE -> {
                // Already completed
            }
        }
    }

    /**
     * Map style index to widget child ID.
     */
    private int getWidgetChildForStyle(int style) {
        return switch (style) {
            case 0 -> STYLE_1_WIDGET;
            case 1 -> STYLE_2_WIDGET;
            case 2 -> STYLE_3_WIDGET;
            case 3 -> STYLE_4_WIDGET;
            default -> STYLE_1_WIDGET;
        };
    }
}
