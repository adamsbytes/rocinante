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
 *     .category(SettingsCategory.INTERFACES)
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
    
    /** Settings categories tabs - the actual clickable tabs (child 25) */
    private static final int SETTINGS_CATEGORIES_TABS = 25;
    
    /** Settings content area where options appear (child 19) */
    private static final int SETTINGS_CONTENT = 19;
    
    /** Settings scrollbar (child 21) */
    private static final int SETTINGS_SCROLLBAR = 21;
    
    /** Close button for settings (child 4) */
    private static final int SETTINGS_CLOSE = 4;
    
    /** RuneLite script ID for updating scrollbar position */
    private static final int UPDATE_SCROLLBAR_SCRIPT = 72;

    // ========================================================================
    // Settings Categories
    // ========================================================================
    
    /**
     * Settings categories available in the full settings panel.
     * Names must match exactly what appears in the OSRS settings interface.
     */
    public enum SettingsCategory {
        ALL_SETTINGS("All Settings", 0),
        ACTIVITIES("Activities", 1),
        AUDIO("Audio", 2),
        CHAT("Chat", 3),
        CONTROLS("Controls", 4),
        DISPLAY("Display", 5),
        GAMEPLAY("Gameplay", 6),
        INTERFACES("Interfaces", 7),  // Game client layout settings are here
        WARNINGS("Warnings", 8);
        
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
        CLICK_DROPDOWN,           // NEW: For dropdown settings - click the dropdown
        WAIT_FOR_DROPDOWN,        // NEW: Wait for dropdown options to appear
        FIND_AND_CLICK_SETTING,
        SCROLL_TO_SETTING,
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
    
    /** Text to search for in settings content (or dropdown option text if isDropdown) */
    private final String settingText;
    
    /** Optional predicate to match specific widget */
    private final Predicate<Widget> settingMatcher;
    
    /** Whether to close settings after changing */
    private final boolean closeAfter;
    
    /** If true, settingText is a dropdown option and we need to click the dropdown first */
    private final boolean isDropdownSetting;
    
    /** Label text for finding the dropdown (e.g., "Game client layout") */
    private final String dropdownLabelText;
    
    /** Current sub-task being executed */
    private Task currentSubTask;
    
    /** Found setting widget for clicking */
    private Widget targetSettingWidget;
    
    /** Wait ticks for scroll operations */
    private int scrollWaitTicks = 0;
    
    /** Number of scroll attempts made */
    private int scrollAttempts = 0;
    
    /** Maximum scroll attempts before giving up */
    private static final int MAX_SCROLL_ATTEMPTS = 10;

    // ========================================================================
    // Constructors
    // ========================================================================
    
    private SettingsTask(SettingsCategory category, String settingText, 
                         Predicate<Widget> settingMatcher, boolean closeAfter, String taskDescription,
                         boolean isDropdownSetting, String dropdownLabelText) {
        this.category = category;
        this.settingText = settingText;
        this.settingMatcher = settingMatcher;
        this.closeAfter = closeAfter;
        this.isDropdownSetting = isDropdownSetting;
        this.dropdownLabelText = dropdownLabelText;
        this.description = taskDescription != null ? taskDescription : "Change setting: " + settingText;
    }
    
    // Legacy constructor for non-dropdown settings
    private SettingsTask(SettingsCategory category, String settingText, 
                         Predicate<Widget> settingMatcher, boolean closeAfter, String taskDescription) {
        this(category, settingText, settingMatcher, closeAfter, taskDescription, false, null);
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================
    
    /**
     * Create a task to set the interface to Fixed mode.
     * This is a dropdown setting - we need to click the dropdown first, then select the option.
     */
    public static SettingsTask setFixedMode() {
        return new SettingsTask(
            SettingsCategory.INTERFACES,
            InterfaceModeOption.FIXED.getText(),  // "Fixed - Classic layout"
            null,
            true,
            "Set interface to Fixed mode",
            true,                                  // This is a dropdown setting
            "Game client layout"                   // The dropdown label
        );
    }
    
    /**
     * Create a task to set a specific interface mode.
     * This is a dropdown setting - we need to click the dropdown first, then select the option.
     */
    public static SettingsTask setInterfaceMode(InterfaceModeOption mode) {
        return new SettingsTask(
            SettingsCategory.INTERFACES,
            mode.getText(),
            null,
            true,
            "Set interface to " + mode.name() + " mode",
            true,                                  // This is a dropdown setting
            "Game client layout"                   // The dropdown label
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
            case CLICK_DROPDOWN:
                executeClickDropdown(ctx);
                break;
            case WAIT_FOR_DROPDOWN:
                executeWaitForDropdown(ctx);
                break;
            case FIND_AND_CLICK_SETTING:
                executeFindAndClickSetting(ctx);
                break;
            case SCROLL_TO_SETTING:
                executeScrollToSetting(ctx);
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
        
        // Find the category tabs container
        Widget categoriesContainer = client.getWidget(SETTINGS_GROUP, SETTINGS_CATEGORIES_TABS);
        if (categoriesContainer == null || categoriesContainer.isHidden()) {
            log.debug("Waiting for categories tabs container");
            return;
        }
        
        Widget categoryWidget = null;
        int categoryEnumIndex = category.getIndex();
        
        Widget[] dynamicChildren = categoriesContainer.getDynamicChildren();
        
        // Widget structure verified from logs:
        // Index 9=Activities, 19=Audio, 29=Chat, 39=Controls, 49=Display, 59=Gameplay, 69=Interfaces, 79=Warnings
        // Formula: widgetIndex = (categoryEnumIndex * 10) - 1
        // Note: ALL_SETTINGS(0) is not a clickable tab in this container
        
        if (categoryEnumIndex == 0) {
            log.warn("ALL_SETTINGS is not a clickable category tab - use a specific category");
            currentPhase = Phase.DONE;
            transitionTo(TaskState.FAILED);
            return;
        }
        
        int widgetIndex = (categoryEnumIndex * 10) - 1;
        
        if (dynamicChildren != null && widgetIndex >= 0 && widgetIndex < dynamicChildren.length) {
            categoryWidget = dynamicChildren[widgetIndex];
            log.debug("Found category {} at dynamic child index {} (enum index {}, {} total children)", 
                    category.getName(), widgetIndex, categoryEnumIndex, dynamicChildren.length);
            
            // Create WidgetInteractTask using the parent container + dynamic child index
            // NOT using widget.getId() which returns the parent's ID for dynamic children!
            currentSubTask = new WidgetInteractTask(SETTINGS_GROUP, SETTINGS_CATEGORIES_TABS)
                    .withDynamicChild(widgetIndex)
                    .withDescription("Category: " + category.getName())
                    .withForceClick(true);
            advancePhase(Phase.WAIT_FOR_CATEGORY);
        } else {
            log.warn("Could not find category widget for: {} (index {} out of range)", 
                    category.getName(), widgetIndex);
        }
    }
    
    private void executeWaitForCategory(TaskContext ctx) {
        // Give time for category content to load
        if (ticksInPhase >= 2) {
            if (isDropdownSetting && dropdownLabelText != null) {
                advancePhase(Phase.CLICK_DROPDOWN);
            } else {
            advancePhase(Phase.FIND_AND_CLICK_SETTING);
            }
        }
    }
    
    /**
     * Click the dropdown button to open dropdown options.
     * For dropdown settings, we find the label (e.g., "Game client layout") 
     * and then click the dropdown widget next to it.
     */
    private void executeClickDropdown(TaskContext ctx) {
        Client client = ctx.getClient();
        
        Widget contentContainer = client.getWidget(SETTINGS_GROUP, SETTINGS_CONTENT);
        if (contentContainer == null || contentContainer.isHidden()) {
            log.debug("Waiting for settings content for dropdown");
            return;
        }
        
        Widget[] dynamicChildren = contentContainer.getDynamicChildren();
        if (dynamicChildren == null) {
            log.warn("Content container has no dynamic children");
            return;
        }
        
        // Find the label widget (e.g., "Game client layout")
        int labelIndex = -1;
        for (int i = 0; i < dynamicChildren.length; i++) {
            Widget child = dynamicChildren[i];
            if (child != null) {
                String text = child.getText();
                if (text != null && text.equals(dropdownLabelText)) {
                    labelIndex = i;
                    log.debug("Found dropdown label '{}' at index {}", dropdownLabelText, i);
                    break;
                }
            }
        }
        
        if (labelIndex == -1) {
            log.warn("Could not find dropdown label: {}", dropdownLabelText);
            fail("Dropdown label not found: " + dropdownLabelText);
            return;
        }
        
        // The dropdown widget is typically 5 indices after the label
        // Pattern observed: dyn[3]="Game client layout", dyn[8]="Resizable - Modern layout"
        int dropdownIndex = labelIndex + 5;
        
        if (dropdownIndex >= dynamicChildren.length) {
            log.warn("Dropdown index {} out of bounds (max {})", dropdownIndex, dynamicChildren.length);
            fail("Dropdown widget not found");
            return;
        }
        
        Widget dropdownWidget = dynamicChildren[dropdownIndex];
        if (dropdownWidget == null) {
            log.warn("Dropdown widget at index {} is null", dropdownIndex);
            fail("Dropdown widget is null");
            return;
        }
        
        String dropdownText = dropdownWidget.getText();
        log.info("Clicking dropdown at index {} with current value: '{}'", dropdownIndex, dropdownText);
        
        // Click the dropdown using dynamic child
        currentSubTask = new WidgetInteractTask(SETTINGS_GROUP, SETTINGS_CONTENT)
                .withDynamicChild(dropdownIndex)
                .withDescription("Open dropdown: " + dropdownLabelText)
                .withForceClick(true);
        advancePhase(Phase.WAIT_FOR_DROPDOWN);
    }
    
    /**
     * Wait for the dropdown options to appear after clicking.
     */
    private void executeWaitForDropdown(TaskContext ctx) {
        // Give time for dropdown to open
        if (ticksInPhase >= 5) {
            log.debug("Dropdown should be open, searching for option: {}", settingText);
            advancePhase(Phase.FIND_AND_CLICK_SETTING);
        }
    }
    
    private void executeFindAndClickSetting(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // For dropdown options, search from the whole settings group (dropdown popup is at 134:29)
        // For regular settings, search from the content area (134:19)
        Widget searchContainer;
        if (isDropdownSetting) {
            // Dropdown options are in 134:29 - search from root of settings group
            searchContainer = client.getWidget(SETTINGS_GROUP, 0);
            if (searchContainer == null || searchContainer.isHidden()) {
                log.debug("Waiting for settings panel (dropdown mode)");
                return;
            }
            log.debug("Searching for dropdown option '{}' in whole settings group", settingText);
        } else {
            // Regular settings are in content area 134:19
            searchContainer = client.getWidget(SETTINGS_GROUP, SETTINGS_CONTENT);
            if (searchContainer == null || searchContainer.isHidden()) {
            log.debug("Waiting for settings content");
            return;
            }
        }
        
        
        // Search for the setting widget
        Widget settingWidget = null;
        if (settingMatcher != null) {
            settingWidget = findChildByPredicate(searchContainer, settingMatcher);
        } else if (settingText != null) {
            settingWidget = findChildByText(searchContainer, settingText);
        }
        
        if (settingWidget != null) {
            targetSettingWidget = settingWidget;
            
            // Check if the setting is visible in the scroll area
            if (isSettingVisible(client, settingWidget)) {
                log.debug("Found visible setting widget: {}", settingText);
                currentSubTask = createWidgetClickTask(settingWidget, "Setting: " + settingText);
                advancePhase(Phase.VERIFY_CHANGE);
            } else {
                log.debug("Setting found but not visible, scrolling...");
                advancePhase(Phase.SCROLL_TO_SETTING);
            }
        } else {
            // Setting not found - try scrolling to search more of the list
            if (scrollAttempts < MAX_SCROLL_ATTEMPTS) {
                log.debug("Setting not found: {} (attempt {}/{}), scrolling to search", 
                        settingText, scrollAttempts + 1, MAX_SCROLL_ATTEMPTS);
                advancePhase(Phase.SCROLL_TO_SETTING);
            } else {
                log.warn("Setting not found after {} scroll attempts: {}", 
                        MAX_SCROLL_ATTEMPTS, settingText);
                fail("Setting not found: " + settingText);
            }
        }
    }
    
    /**
     * Check if the setting widget is visible in the scroll area.
     */
    private boolean isSettingVisible(Client client, Widget settingWidget) {
        Widget contentWindow = client.getWidget(SETTINGS_GROUP, SETTINGS_CONTENT);
        if (contentWindow == null) {
            return true; // Assume visible if can't check
        }
        
        java.awt.Rectangle windowBounds = contentWindow.getBounds();
        java.awt.Rectangle settingBounds = settingWidget.getBounds();
        
        if (windowBounds == null || settingBounds == null) {
            return true;
        }
        
        // Check if setting is within visible scroll area
        return settingBounds.y >= windowBounds.y &&
               settingBounds.y + settingBounds.height <= windowBounds.y + windowBounds.height;
    }
    
    /**
     * Scroll the settings content to find/reveal the target setting.
     */
    private void executeScrollToSetting(TaskContext ctx) {
        Client client = ctx.getClient();
        
        Widget contentContainer = client.getWidget(SETTINGS_GROUP, SETTINGS_CONTENT);
        if (contentContainer == null) {
            advancePhase(Phase.FIND_AND_CLICK_SETTING);
            return;
        }
        
        // Calculate scroll amount
        int targetScroll;
        if (targetSettingWidget != null) {
            // Scroll to make target visible
            int targetY = targetSettingWidget.getRelativeY();
            int containerHeight = contentContainer.getHeight();
            targetScroll = Math.max(0, targetY - containerHeight / 2 + targetSettingWidget.getHeight() / 2);
            log.debug("Scrolling to setting at Y={}, scroll={}", targetY, targetScroll);
        } else {
            // Setting not found yet - scroll down incrementally to search
            int currentScroll = contentContainer.getScrollY();
            int scrollIncrement = contentContainer.getHeight() / 2; // Half page at a time
            targetScroll = currentScroll + scrollIncrement;
            log.debug("Scrolling down to search: {} -> {}", currentScroll, targetScroll);
        }
        
        try {
            // Use client script to update scrollbar
            client.runScript(
                    UPDATE_SCROLLBAR_SCRIPT,
                    SETTINGS_GROUP << 16 | SETTINGS_SCROLLBAR,
                    SETTINGS_GROUP << 16 | SETTINGS_CONTENT,
                    targetScroll
            );
        } catch (Exception e) {
            log.debug("Could not use scroll script: {}", e.getMessage());
        }
        
        scrollAttempts++;
        
        // Wait for scroll to apply, then go back to finding
        scrollWaitTicks++;
        if (scrollWaitTicks >= 2) {
            scrollWaitTicks = 0;
            advancePhase(Phase.FIND_AND_CLICK_SETTING);
        }
    }
    
    private void executeVerifyChange(TaskContext ctx) {
        // Give time for the change to apply
        // Interface mode changes need more time as the UI completely restructures
        int minWaitTicks = (settingText != null && settingText.contains("layout")) ? 5 : 2;
        if (ticksInPhase < minWaitTicks) {
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
        
        // Check getChildren() - this is where dropdown options are!
        Widget[] children = parent.getChildren();
        if (children != null) {
            for (Widget child : children) {
                Widget found = findChildByText(child, text);
                if (found != null) {
                    return found;
                }
            }
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
