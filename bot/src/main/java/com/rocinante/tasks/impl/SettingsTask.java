package com.rocinante.tasks.impl;

import com.rocinante.core.GameStateService;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Reusable task for interacting with the OSRS settings interface.
 * 
 * Uses WidgetInteractTask for all widget interactions to ensure proper:
 * - Interface mode detection
 * - Human-like mouse movement
 * - Click randomization
 * 
 * Supports:
 * - Opening the full settings panel
 * - Navigating to specific categories (Display, Audio, Controls, etc.)
 * - Clicking specific settings by text matching
 * - Closing settings when done
 * 
 * Usage:
 * <pre>
 * // Set to fixed mode
 * SettingsTask.setFixedMode()
 * 
 * // Custom setting
 * SettingsTask.builder()
 *     .category(SettingsCategory.DISPLAY)
 *     .settingText("Fixed - Classic layout")
 *     .build()
 * </pre>
 */
@Slf4j
public class SettingsTask extends AbstractTask {

    // ========================================================================
    // Widget IDs - from RuneLite InterfaceID
    // ========================================================================
    
    /** Settings side panel group ID */
    private static final int SETTINGS_SIDE_GROUP = 116;
    
    /** Button to open full settings from side panel (child 32) */
    private static final int SETTINGS_SIDE_OPEN_BUTTON = 32;
    
    /** Full settings interface group ID */
    private static final int SETTINGS_GROUP = 134;
    
    /** Settings categories container (child 17) */
    private static final int SETTINGS_CATEGORIES = 17;
    
    /** Settings content area where options appear (child 19) */
    private static final int SETTINGS_CONTENT = 19;
    
    /** Close button for settings (child 4) */
    private static final int SETTINGS_CLOSE = 4;

    // ========================================================================
    // Settings Categories
    // ========================================================================
    
    /**
     * Settings categories available in the full settings panel.
     */
    public enum SettingsCategory {
        ALL_SETTINGS("All Settings", 0),
        DISPLAY("Display", 1),
        AUDIO("Audio", 2),
        CHAT("Chat", 3),
        CONTROLS("Controls", 4),
        KEYBINDS("Keybinds", 5),
        GAMEPLAY("Gameplay", 6),
        WARNINGS("Warnings", 7),
        ACCOUNT("Account", 8);
        
        @Getter
        private final String name;
        @Getter
        private final int index;
        
        SettingsCategory(String name, int index) {
            this.name = name;
            this.index = index;
        }
    }
    
    /**
     * Interface mode options.
     */
    public enum InterfaceModeOption {
        FIXED("Fixed - Classic layout"),
        RESIZABLE_CLASSIC("Resizable - Classic layout"),
        RESIZABLE_MODERN("Resizable - Modern layout");
        
        @Getter
        private final String text;
        
        InterfaceModeOption(String text) {
            this.text = text;
        }
    }

    // ========================================================================
    // Task State
    // ========================================================================
    
    private enum Phase {
        CHECK_IF_NEEDED,
        OPEN_SETTINGS_TAB,
        WAIT_FOR_SETTINGS_TAB,
        OPEN_FULL_SETTINGS,
        WAIT_FOR_FULL_SETTINGS,
        SELECT_CATEGORY,
        WAIT_FOR_CATEGORY,
        FIND_AND_CLICK_SETTING,
        VERIFY_CHANGE,
        CLOSE_SETTINGS,
        DONE
    }
    
    @Getter
    @Setter
    private String description;
    
    private Phase currentPhase = Phase.CHECK_IF_NEEDED;
    private int ticksInPhase = 0;
    private static final int MAX_TICKS_PER_PHASE = 15;
    
    /** The category to navigate to */
    private final SettingsCategory category;
    
    /** Text to search for in settings content */
    private final String settingText;
    
    /** Optional predicate to match specific widget */
    private final Predicate<Widget> settingMatcher;
    
    /** Whether to close settings after changing */
    private final boolean closeAfter;
    
    /** Current sub-task being executed */
    private Task currentSubTask;
    
    /** Found setting widget for clicking */
    private Widget targetSettingWidget;

    // ========================================================================
    // Constructors
    // ========================================================================
    
    private SettingsTask(SettingsCategory category, String settingText, 
                         Predicate<Widget> settingMatcher, boolean closeAfter, String taskDescription) {
        this.category = category;
        this.settingText = settingText;
        this.settingMatcher = settingMatcher;
        this.closeAfter = closeAfter;
        this.description = taskDescription != null ? taskDescription : "Change setting: " + settingText;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================
    
    /**
     * Create a task to set the interface to Fixed mode.
     */
    public static SettingsTask setFixedMode() {
        return new SettingsTask(
            SettingsCategory.DISPLAY,
            InterfaceModeOption.FIXED.getText(),
            null,
            true,
            "Set interface to Fixed mode"
        );
    }
    
    /**
     * Create a task to set a specific interface mode.
     */
    public static SettingsTask setInterfaceMode(InterfaceModeOption mode) {
        return new SettingsTask(
            SettingsCategory.DISPLAY,
            mode.getText(),
            null,
            true,
            "Set interface to " + mode.name() + " mode"
        );
    }
    
    /**
     * Create a builder for custom settings tasks.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for creating custom SettingsTask instances.
     */
    public static class Builder {
        private SettingsCategory category = SettingsCategory.ALL_SETTINGS;
        private String settingText;
        private Predicate<Widget> settingMatcher;
        private boolean closeAfter = true;
        private String description;
        
        public Builder category(SettingsCategory category) {
            this.category = category;
            return this;
        }
        
        public Builder settingText(String text) {
            this.settingText = text;
            return this;
        }
        
        public Builder settingMatcher(Predicate<Widget> matcher) {
            this.settingMatcher = matcher;
            return this;
        }
        
        public Builder closeAfter(boolean close) {
            this.closeAfter = close;
            return this;
        }
        
        public Builder description(String description) {
            this.description = description;
            return this;
        }
        
        public SettingsTask build() {
            if (settingText == null && settingMatcher == null) {
                throw new IllegalStateException("Must specify settingText or settingMatcher");
            }
            return new SettingsTask(category, settingText, settingMatcher, closeAfter, description);
        }
    }

    // ========================================================================
    // Execution
    // ========================================================================
    
    @Override
    protected void executeImpl(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // Execute current sub-task if one is active
        if (currentSubTask != null) {
            currentSubTask.execute(ctx);
            
            // Check sub-task completion status
            if (currentSubTask instanceof AbstractTask) {
                AbstractTask abstractTask = (AbstractTask) currentSubTask;
                TaskState subState = abstractTask.getState();
                
                if (subState != TaskState.COMPLETED && subState != TaskState.FAILED) {
                    return; // Still running
                }
                if (subState == TaskState.FAILED) {
                    log.warn("Sub-task failed: {}", currentSubTask.getDescription());
                    // Continue anyway, might retry or move on
                }
            }
            currentSubTask = null;
        }
        
        ticksInPhase++;
        if (ticksInPhase > MAX_TICKS_PER_PHASE) {
            log.warn("Settings task stuck in phase {} for {} ticks", currentPhase, ticksInPhase);
            fail("Stuck in phase: " + currentPhase);
            return;
        }
        
        switch (currentPhase) {
            case CHECK_IF_NEEDED:
                executeCheckIfNeeded(ctx);
                break;
            case OPEN_SETTINGS_TAB:
                executeOpenSettingsTab(ctx);
                break;
            case WAIT_FOR_SETTINGS_TAB:
                executeWaitForSettingsTab(ctx);
                break;
            case OPEN_FULL_SETTINGS:
                executeOpenFullSettings(ctx);
                break;
            case WAIT_FOR_FULL_SETTINGS:
                executeWaitForFullSettings(ctx);
                break;
            case SELECT_CATEGORY:
                executeSelectCategory(ctx);
                break;
            case WAIT_FOR_CATEGORY:
                executeWaitForCategory(ctx);
                break;
            case FIND_AND_CLICK_SETTING:
                executeFindAndClickSetting(ctx);
                break;
            case VERIFY_CHANGE:
                executeVerifyChange(ctx);
                break;
            case CLOSE_SETTINGS:
                executeCloseSettings(ctx);
                break;
            case DONE:
                complete();
                break;
        }
    }
    
    private void advancePhase(Phase nextPhase) {
        log.debug("Settings task: {} -> {}", currentPhase, nextPhase);
        currentPhase = nextPhase;
        ticksInPhase = 0;
    }
    
    private void executeCheckIfNeeded(TaskContext ctx) {
        // For interface mode changes, check if already in desired mode
        if (settingText != null && settingText.contains("Fixed")) {
            GameStateService gameState = ctx.getGameStateService();
            if (gameState != null && gameState.getInterfaceMode().isFixed()) {
                log.info("Already in Fixed mode, skipping settings change");
                advancePhase(Phase.DONE);
                return;
            }
        }
        
        advancePhase(Phase.OPEN_SETTINGS_TAB);
    }
    
    private void executeOpenSettingsTab(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // Check if full settings is already open
        Widget settingsPanel = client.getWidget(SETTINGS_GROUP, 0);
        if (settingsPanel != null && !settingsPanel.isHidden()) {
            log.debug("Full settings already open");
            advancePhase(Phase.SELECT_CATEGORY);
            return;
        }
        
        // Check if settings side panel is visible (settings tab is open)
        Widget settingsSide = client.getWidget(SETTINGS_SIDE_GROUP, 0);
        if (settingsSide != null && !settingsSide.isHidden()) {
            log.debug("Settings side panel visible");
            advancePhase(Phase.OPEN_FULL_SETTINGS);
            return;
        }
        
        // Use WidgetInteractTask to open settings tab (index 11)
        log.debug("Opening settings tab via WidgetInteractTask");
        currentSubTask = WidgetInteractTask.openTabByClick(11);
        advancePhase(Phase.WAIT_FOR_SETTINGS_TAB);
    }
    
    private void executeWaitForSettingsTab(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // Check if settings side panel appeared
        Widget settingsSide = client.getWidget(SETTINGS_SIDE_GROUP, 0);
        if (settingsSide != null && !settingsSide.isHidden()) {
            log.debug("Settings tab opened");
            advancePhase(Phase.OPEN_FULL_SETTINGS);
        }
    }
    
    private void executeOpenFullSettings(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // Check if full settings is already open
        Widget settingsPanel = client.getWidget(SETTINGS_GROUP, 0);
        if (settingsPanel != null && !settingsPanel.isHidden()) {
            log.debug("Full settings panel opened");
            advancePhase(Phase.SELECT_CATEGORY);
            return;
        }
        
        // Click the "All Settings" button using WidgetInteractTask
        log.debug("Clicking All Settings button");
        currentSubTask = new WidgetInteractTask(SETTINGS_SIDE_GROUP, SETTINGS_SIDE_OPEN_BUTTON)
                .withDescription("Click All Settings")
                .withForceClick(true);
        advancePhase(Phase.WAIT_FOR_FULL_SETTINGS);
    }
    
    private void executeWaitForFullSettings(TaskContext ctx) {
        Client client = ctx.getClient();
        
        Widget settingsPanel = client.getWidget(SETTINGS_GROUP, 0);
        if (settingsPanel != null && !settingsPanel.isHidden()) {
            log.debug("Full settings panel opened");
            advancePhase(Phase.SELECT_CATEGORY);
        }
    }
    
    private void executeSelectCategory(TaskContext ctx) {
        Client client = ctx.getClient();
        
        if (category == SettingsCategory.ALL_SETTINGS) {
            // No category selection needed
            advancePhase(Phase.FIND_AND_CLICK_SETTING);
            return;
        }
        
        // Find the category widget by text
        Widget categoriesContainer = client.getWidget(SETTINGS_GROUP, SETTINGS_CATEGORIES);
        if (categoriesContainer == null || categoriesContainer.isHidden()) {
            log.debug("Waiting for categories container");
            return;
        }
        
        Widget categoryWidget = findChildByText(categoriesContainer, category.getName());
        if (categoryWidget != null) {
            // Create WidgetInteractTask to click the category
            log.debug("Clicking category: {}", category.getName());
            currentSubTask = createWidgetClickTask(categoryWidget, "Category: " + category.getName());
            advancePhase(Phase.WAIT_FOR_CATEGORY);
        } else {
            log.warn("Could not find category widget for: {}", category.getName());
        }
    }
    
    private void executeWaitForCategory(TaskContext ctx) {
        // Give time for category content to load
        if (ticksInPhase >= 2) {
            advancePhase(Phase.FIND_AND_CLICK_SETTING);
        }
    }
    
    private void executeFindAndClickSetting(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // Find settings content area
        Widget contentContainer = client.getWidget(SETTINGS_GROUP, SETTINGS_CONTENT);
        if (contentContainer == null || contentContainer.isHidden()) {
            log.debug("Waiting for settings content");
            return;
        }
        
        // Search for the setting widget
        Widget settingWidget = null;
        if (settingMatcher != null) {
            settingWidget = findChildByPredicate(contentContainer, settingMatcher);
        } else if (settingText != null) {
            settingWidget = findChildByText(contentContainer, settingText);
        }
        
        if (settingWidget != null) {
            log.debug("Found setting widget: {}", settingText);
            currentSubTask = createWidgetClickTask(settingWidget, "Setting: " + settingText);
            advancePhase(Phase.VERIFY_CHANGE);
        } else {
            log.debug("Setting not found yet: {} (may need scrolling)", settingText);
            // TODO: Implement scrolling if needed
        }
    }
    
    private void executeVerifyChange(TaskContext ctx) {
        // Give time for the change to apply
        if (ticksInPhase < 2) {
            return;
        }
        
        // For interface mode changes, verify via GameStateService
        if (settingText != null && settingText.contains("Fixed")) {
            GameStateService gameState = ctx.getGameStateService();
            if (gameState != null) {
                // Force refresh the interface mode
                var mode = gameState.getInterfaceMode();
                if (mode.isFixed()) {
                    log.info("Successfully changed to Fixed mode");
                    advancePhase(closeAfter ? Phase.CLOSE_SETTINGS : Phase.DONE);
                    return;
                } else {
                    log.debug("Interface mode not yet Fixed, current: {}", mode);
                }
            }
        }
        
        // Generic verification - assume success after clicking
        if (ticksInPhase >= 3) {
            log.debug("Setting change assumed successful");
            advancePhase(closeAfter ? Phase.CLOSE_SETTINGS : Phase.DONE);
        }
    }
    
    private void executeCloseSettings(TaskContext ctx) {
        Client client = ctx.getClient();
        
        Widget settingsPanel = client.getWidget(SETTINGS_GROUP, 0);
        if (settingsPanel == null || settingsPanel.isHidden()) {
            log.debug("Settings panel closed");
            advancePhase(Phase.DONE);
            return;
        }
        
        // Click close button using WidgetInteractTask
        Widget closeButton = client.getWidget(SETTINGS_GROUP, SETTINGS_CLOSE);
        if (closeButton != null && !closeButton.isHidden()) {
            log.debug("Clicking close button");
            currentSubTask = createWidgetClickTask(closeButton, "Close settings");
        } else {
            // Fallback: press ESC
            log.debug("Close button not found, pressing ESC");
            currentSubTask = WidgetInteractTask.pressKey(java.awt.event.KeyEvent.VK_ESCAPE);
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    /**
     * Create a WidgetInteractTask to click a specific widget by its bounds.
     */
    private WidgetInteractTask createWidgetClickTask(Widget widget, String desc) {
        // Get widget group and child IDs
        int packedId = widget.getId();
        int groupId = packedId >> 16;
        int childId = packedId & 0xFFFF;
        
        return new WidgetInteractTask(groupId, childId)
                .withDescription(desc)
                .withForceClick(true);
    }
    
    /**
     * Recursively find a child widget by text content.
     */
    private Widget findChildByText(Widget parent, String text) {
        if (parent == null) {
            return null;
        }
        
        // Check parent text
        String parentText = parent.getText();
        if (parentText != null && parentText.contains(text)) {
            return parent;
        }
        
        // Check dynamic children
        Widget[] dynamicChildren = parent.getDynamicChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) {
                Widget found = findChildByText(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        
        // Check nested children
        Widget[] nestedChildren = parent.getNestedChildren();
        if (nestedChildren != null) {
            for (Widget child : nestedChildren) {
                Widget found = findChildByText(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        
        // Check static children
        Widget[] staticChildren = parent.getStaticChildren();
        if (staticChildren != null) {
            for (Widget child : staticChildren) {
                Widget found = findChildByText(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Recursively find a child widget matching a predicate.
     */
    private Widget findChildByPredicate(Widget parent, Predicate<Widget> predicate) {
        if (parent == null) {
            return null;
        }
        
        if (predicate.test(parent)) {
            return parent;
        }
        
        // Check all children types
        Widget[] dynamicChildren = parent.getDynamicChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) {
                Widget found = findChildByPredicate(child, predicate);
                if (found != null) {
                    return found;
                }
            }
        }
        
        Widget[] nestedChildren = parent.getNestedChildren();
        if (nestedChildren != null) {
            for (Widget child : nestedChildren) {
                Widget found = findChildByPredicate(child, predicate);
                if (found != null) {
                    return found;
                }
            }
        }
        
        Widget[] staticChildren = parent.getStaticChildren();
        if (staticChildren != null) {
            for (Widget child : staticChildren) {
                Widget found = findChildByPredicate(child, predicate);
                if (found != null) {
                    return found;
                }
            }
        }
        
        return null;
    }

    // ========================================================================
    // Task Overrides
    // ========================================================================
    
    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.isLoggedIn();
    }
}
