package com.rocinante.quest.steps;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.WidgetInteractTask;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * Quest step for interacting with a UI widget.
 *
 * This step handles interactions like opening tabs, clicking buttons,
 * and navigating UI interfaces.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Open the inventory tab
 * WidgetQuestStep openInventory = new WidgetQuestStep(WidgetInfo.INVENTORY, "Open the inventory tab");
 *
 * // Open the settings tab
 * WidgetQuestStep openSettings = new WidgetQuestStep(261, 1, "Open the settings menu");
 *
 * // Click continue in dialogue
 * WidgetQuestStep clickContinue = new WidgetQuestStep(231, 3, "Click continue")
 *     .withAction(WidgetAction.CLICK);
 * }</pre>
 */
@Getter
@Setter
@Accessors(chain = true)
public class WidgetQuestStep extends QuestStep {

    /**
     * Widget interaction actions.
     */
    public enum WidgetAction {
        CLICK,
        HOVER,
        DRAG,
        TYPE
    }

    /**
     * The widget group ID.
     */
    private final int widgetGroupId;

    /**
     * The widget child ID (0 for root).
     */
    private int widgetChildId = 0;

    /**
     * The action to perform.
     */
    private WidgetAction action = WidgetAction.CLICK;

    /**
     * Text to type (for TYPE action).
     */
    private String typeText;

    /**
     * Optional key to press (for keyboard shortcuts).
     */
    private Character keyPress;

    /**
     * Whether this is a tab switch (uses fixed key mapping).
     */
    private boolean isTabSwitch = false;

    /**
     * Tab ID for tab switching (uses fixed key bindings).
     */
    private int tabId = -1;

    /**
     * Create a widget quest step.
     *
     * @param widgetGroupId the widget group ID
     * @param text          instruction text
     */
    public WidgetQuestStep(int widgetGroupId, String text) {
        super(text);
        this.widgetGroupId = widgetGroupId;
    }

    /**
     * Create a widget quest step with child ID.
     *
     * @param widgetGroupId the widget group ID
     * @param widgetChildId the widget child ID
     * @param text          instruction text
     */
    public WidgetQuestStep(int widgetGroupId, int widgetChildId, String text) {
        super(text);
        this.widgetGroupId = widgetGroupId;
        this.widgetChildId = widgetChildId;
    }

    @Override
    public StepType getType() {
        return StepType.WIDGET;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        List<Task> tasks = new ArrayList<>();

        // Handle keyboard shortcut for tab switching
        if (isTabSwitch && tabId >= 0) {
            WidgetInteractTask tabTask = WidgetInteractTask.openTab(tabId)
                    .withDescription(getText());
            tasks.add(tabTask);
            return tasks;
        }

        // Handle direct key press
        if (keyPress != null) {
            WidgetInteractTask keyTask = WidgetInteractTask.pressKey(keyPress)
                    .withDescription(getText());
            tasks.add(keyTask);
            return tasks;
        }

        // Handle widget click
        WidgetInteractTask widgetTask = new WidgetInteractTask(widgetGroupId, widgetChildId)
                .withDescription(getText());

        switch (action) {
            case CLICK:
                widgetTask.withAction(WidgetInteractTask.WidgetAction.CLICK);
                break;
            case HOVER:
                widgetTask.withAction(WidgetInteractTask.WidgetAction.HOVER);
                break;
            case DRAG:
                // Drag not yet implemented in WidgetInteractTask
                widgetTask.withAction(WidgetInteractTask.WidgetAction.CLICK);
                break;
            case TYPE:
                // Typing handled separately
                break;
        }

        tasks.add(widgetTask);
        return tasks;
    }

    // ========================================================================
    // Factory Methods for Common Tabs
    // ========================================================================

    /**
     * Create a step to open the inventory tab.
     *
     * @param text instruction text
     * @return widget step
     */
    public static WidgetQuestStep openInventory(String text) {
        return new WidgetQuestStep(149, text)
                .withTabId(3)
                .withTabSwitch(true);
    }

    /**
     * Create a step to open the skills tab.
     *
     * @param text instruction text
     * @return widget step
     */
    public static WidgetQuestStep openSkills(String text) {
        return new WidgetQuestStep(320, text)
                .withTabId(1)
                .withTabSwitch(true);
    }

    /**
     * Create a step to open the quest tab.
     *
     * @param text instruction text
     * @return widget step
     */
    public static WidgetQuestStep openQuests(String text) {
        return new WidgetQuestStep(629, text)
                .withTabId(2)
                .withTabSwitch(true);
    }

    /**
     * Create a step to open the equipment tab.
     *
     * @param text instruction text
     * @return widget step
     */
    public static WidgetQuestStep openEquipment(String text) {
        return new WidgetQuestStep(387, text)
                .withTabId(4)
                .withTabSwitch(true);
    }

    /**
     * Create a step to open the prayer tab.
     *
     * @param text instruction text
     * @return widget step
     */
    public static WidgetQuestStep openPrayer(String text) {
        return new WidgetQuestStep(541, text)
                .withTabId(5)
                .withTabSwitch(true);
    }

    /**
     * Create a step to open the spellbook tab.
     *
     * @param text instruction text
     * @return widget step
     */
    public static WidgetQuestStep openSpellbook(String text) {
        return new WidgetQuestStep(218, text)
                .withTabId(6)
                .withTabSwitch(true);
    }

    /**
     * Create a step to open the settings tab.
     *
     * @param text instruction text
     * @return widget step
     */
    public static WidgetQuestStep openSettings(String text) {
        // Settings doesn't have a fixed tab key, need to click the widget
        // Or use ESC key which opens settings on modern clients
        return new WidgetQuestStep(261, text)
                .withTabId(11)
                .withTabSwitch(true);
    }

    /**
     * Create a step to open the combat styles tab.
     *
     * @param text instruction text
     * @return widget step
     */
    public static WidgetQuestStep openCombatStyles(String text) {
        return new WidgetQuestStep(593, text)
                .withTabId(0)
                .withTabSwitch(true);
    }

    /**
     * Create a step to open the friends list.
     *
     * @param text instruction text
     * @return widget step
     */
    public static WidgetQuestStep openFriendsList(String text) {
        return new WidgetQuestStep(429, text)
                .withTabId(9)
                .withTabSwitch(true);
    }

    /**
     * Create a step to open the account management tab.
     *
     * @param text instruction text
     * @return widget step
     */
    public static WidgetQuestStep openAccountManagement(String text) {
        return new WidgetQuestStep(109, text)
                .withTabId(10)
                .withTabSwitch(true);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set the widget child ID (builder-style).
     *
     * @param childId the child ID
     * @return this step for chaining
     */
    public WidgetQuestStep withChildId(int childId) {
        this.widgetChildId = childId;
        return this;
    }

    /**
     * Set the action to perform (builder-style).
     *
     * @param action the action
     * @return this step for chaining
     */
    public WidgetQuestStep withAction(WidgetAction action) {
        this.action = action;
        return this;
    }

    /**
     * Set text to type (builder-style).
     *
     * @param text the text to type
     * @return this step for chaining
     */
    public WidgetQuestStep withTypeText(String text) {
        this.typeText = text;
        this.action = WidgetAction.TYPE;
        return this;
    }

    /**
     * Set key to press (builder-style).
     *
     * @param key the key character
     * @return this step for chaining
     */
    public WidgetQuestStep withKeyPress(char key) {
        this.keyPress = key;
        return this;
    }

    /**
     * Mark as a tab switch (builder-style).
     *
     * @param isTab true if this is a tab switch
     * @return this step for chaining
     */
    public WidgetQuestStep withTabSwitch(boolean isTab) {
        this.isTabSwitch = isTab;
        return this;
    }

    /**
     * Set the tab ID for tab switching (builder-style).
     *
     * @param tabId the tab ID
     * @return this step for chaining
     */
    public WidgetQuestStep withTabId(int tabId) {
        this.tabId = tabId;
        return this;
    }
}

