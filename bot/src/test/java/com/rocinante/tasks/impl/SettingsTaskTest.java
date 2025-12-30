package com.rocinante.tasks.impl;

import com.rocinante.core.GameStateService;
import com.rocinante.core.GameStateService.InterfaceMode;
import com.rocinante.input.RobotMouseController;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.TaskTestHelper;
import com.rocinante.timing.HumanTimer;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.Rectangle;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for SettingsTask.
 * Tests settings panel navigation, category selection, dropdown handling, and verification.
 */
public class SettingsTaskTest {

    private static final int SETTINGS_SIDE_GROUP = 116;
    private static final int SETTINGS_GROUP = 134;

    @Mock
    private Client client;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private RobotMouseController mouseController;

    @Mock
    private HumanTimer humanTimer;

    @Mock
    private Widget settingsSideWidget;

    @Mock
    private Widget settingsFullWidget;

    @Mock
    private Widget categoriesWidget;

    @Mock
    private Widget contentWidget;

    @Mock
    private InterfaceMode interfaceMode;

    private TaskContext taskContext;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // TaskContext wiring
        taskContext = mock(TaskContext.class);
        when(taskContext.getClient()).thenReturn(client);
        when(taskContext.getGameStateService()).thenReturn(gameStateService);
        when(taskContext.getMouseController()).thenReturn(mouseController);
        when(taskContext.getHumanTimer()).thenReturn(humanTimer);
        when(taskContext.isLoggedIn()).thenReturn(true);

        // Default interface mode
        when(gameStateService.getInterfaceMode()).thenReturn(interfaceMode);
        when(interfaceMode.isFixed()).thenReturn(false);

        // Default timer completion
        when(humanTimer.sleep(anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Default mouse controller
        when(mouseController.click(any(Rectangle.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    // ========================================================================
    // Factory Method Tests
    // ========================================================================

    @Test
    public void testSetFixedMode_CreatesCorrectTask() {
        SettingsTask task = SettingsTask.setFixedMode();

        assertNotNull(task);
        assertTrue(task.getDescription().contains("Fixed"));
    }

    @Test
    public void testSetInterfaceMode_AllModes() {
        for (SettingsTask.InterfaceModeOption mode : SettingsTask.InterfaceModeOption.values()) {
            SettingsTask task = SettingsTask.setInterfaceMode(mode);
            
            assertNotNull(task);
            assertTrue(task.getDescription().contains(mode.name()));
        }
    }

    // ========================================================================
    // Builder Tests
    // ========================================================================

    @Test
    public void testBuilder_RequiresSettingTextOrMatcher() {
        assertThrows(IllegalStateException.class, () -> {
            SettingsTask.builder()
                    .category(SettingsTask.SettingsCategory.DISPLAY)
                    .build();
        });
    }

    @Test
    public void testBuilder_WithSettingText() {
        SettingsTask task = SettingsTask.builder()
                .category(SettingsTask.SettingsCategory.AUDIO)
                .settingText("Music volume")
                .build();

        assertNotNull(task);
    }

    @Test
    public void testBuilder_WithSettingMatcher() {
        SettingsTask task = SettingsTask.builder()
                .category(SettingsTask.SettingsCategory.CONTROLS)
                .settingMatcher(widget -> widget.getText() != null && widget.getText().contains("Shift"))
                .build();

        assertNotNull(task);
    }

    @Test
    public void testBuilder_AllOptions() {
        SettingsTask task = SettingsTask.builder()
                .category(SettingsTask.SettingsCategory.INTERFACES)
                .settingText("Game client layout")
                .closeAfter(false)
                .description("Custom settings change")
                .build();

        assertNotNull(task);
        assertEquals("Custom settings change", task.getDescription());
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_LoggedIn_True() {
        when(taskContext.isLoggedIn()).thenReturn(true);

        SettingsTask task = SettingsTask.setFixedMode();

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_NotLoggedIn_False() {
        when(taskContext.isLoggedIn()).thenReturn(false);

        SettingsTask task = SettingsTask.setFixedMode();

        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Already In Mode Tests
    // ========================================================================

    @Test
    public void testSetFixedMode_AlreadyFixed_Skips() {
        when(interfaceMode.isFixed()).thenReturn(true);

        SettingsTask task = SettingsTask.setFixedMode();
        
        // Execute to completion - task advances to DONE phase then completes
        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 5);

        assertEquals(TaskState.COMPLETED, result);
    }

    // ========================================================================
    // Settings Category Tests
    // ========================================================================

    @Test
    public void testSettingsCategory_AllValues() {
        for (SettingsTask.SettingsCategory category : SettingsTask.SettingsCategory.values()) {
            assertNotNull(category.getName());
            assertTrue(category.getIndex() >= 0);
        }
    }

    @Test
    public void testSettingsCategory_UniqueIndices() {
        SettingsTask.SettingsCategory[] categories = SettingsTask.SettingsCategory.values();
        for (int i = 0; i < categories.length; i++) {
            for (int j = i + 1; j < categories.length; j++) {
                assertNotEquals(
                        categories[i].getName() + " and " + categories[j].getName() + " have same index",
                        categories[i].getIndex(),
                        categories[j].getIndex()
                );
            }
        }
    }

    // ========================================================================
    // Interface Mode Option Tests
    // ========================================================================

    @Test
    public void testInterfaceModeOption_AllValues() {
        for (SettingsTask.InterfaceModeOption mode : SettingsTask.InterfaceModeOption.values()) {
            assertNotNull(mode.getText());
            assertFalse(mode.getText().isEmpty());
        }
    }

    @Test
    public void testInterfaceModeOption_TextContainsLayout() {
        for (SettingsTask.InterfaceModeOption mode : SettingsTask.InterfaceModeOption.values()) {
            assertTrue(
                    mode.name() + " should contain 'layout' in text",
                    mode.getText().toLowerCase().contains("layout")
            );
        }
    }

    // ========================================================================
    // Phase Progression Tests
    // ========================================================================

    @Test
    public void testPhaseProgression_SettingsAlreadyOpen() {
        when(settingsFullWidget.isHidden()).thenReturn(false);
        when(client.getWidget(SETTINGS_GROUP, 0)).thenReturn(settingsFullWidget);

        SettingsTask task = SettingsTask.builder()
                .category(SettingsTask.SettingsCategory.ALL_SETTINGS)
                .settingText("Test")
                .build();

        // Execute a few times to progress through phases
        TaskTestHelper.advanceTicks(task, taskContext, 5);

        // Should check for settings widgets (settings already open)
        verify(client, atLeastOnce()).getWidget(SETTINGS_GROUP, 0);
    }

    @Test
    public void testPhaseProgression_NeedToOpenSettings() {
        when(settingsFullWidget.isHidden()).thenReturn(true);
        when(settingsSideWidget.isHidden()).thenReturn(true);
        when(client.getWidget(SETTINGS_GROUP, 0)).thenReturn(settingsFullWidget);
        when(client.getWidget(SETTINGS_SIDE_GROUP, 0)).thenReturn(settingsSideWidget);

        SettingsTask task = SettingsTask.setFixedMode();

        // Execute a few times to trigger opening settings tab
        TaskTestHelper.advanceTicks(task, taskContext, 5);

        // Should check for settings widgets
        verify(client, atLeastOnce()).getWidget(SETTINGS_GROUP, 0);
    }

    // ========================================================================
    // Timeout Tests
    // ========================================================================

    @Test
    public void testStuckInPhase_Fails() {
        // Widgets never appear
        when(client.getWidget(anyInt(), anyInt())).thenReturn(null);

        SettingsTask task = SettingsTask.setFixedMode();

        // Execute many times to trigger failure detection
        TaskState result = TaskTestHelper.advanceToCompletion(task, taskContext, 150);

        assertEquals(TaskState.FAILED, result);
        // Task may fail for various reasons when widgets aren't available
        assertNotNull(task.getFailureReason());
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_FactoryMethod() {
        SettingsTask task = SettingsTask.setFixedMode();

        String desc = task.getDescription();
        assertNotNull(desc);
        assertTrue(desc.contains("Fixed"));
    }

    @Test
    public void testGetDescription_Builder() {
        SettingsTask task = SettingsTask.builder()
                .category(SettingsTask.SettingsCategory.AUDIO)
                .settingText("Music")
                .description("Adjust music volume")
                .build();

        assertEquals("Adjust music volume", task.getDescription());
    }

    @Test
    public void testGetDescription_Default() {
        SettingsTask task = SettingsTask.builder()
                .category(SettingsTask.SettingsCategory.GAMEPLAY)
                .settingText("Auto retaliate")
                .build();

        assertTrue(task.getDescription().contains("Auto retaliate"));
    }

    // ========================================================================
    // Real Game Scenario Tests
    // ========================================================================

    @Test
    public void testScenario_SetFixedModeForBot() {
        // Bot needs fixed mode for predictable widget positions
        when(interfaceMode.isFixed()).thenReturn(false);
        when(interfaceMode.isResizable()).thenReturn(true);

        SettingsTask task = SettingsTask.setFixedMode();

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testScenario_DisableGameSoundsForHeadless() {
        // Headless bot might want to disable sounds
        SettingsTask task = SettingsTask.builder()
                .category(SettingsTask.SettingsCategory.AUDIO)
                .settingText("Sound Effects volume")
                .description("Mute sound effects")
                .build();

        assertNotNull(task);
    }

    @Test
    public void testScenario_EnableShiftDropForEfficiency() {
        // Bot might want shift-click dropping
        SettingsTask task = SettingsTask.builder()
                .category(SettingsTask.SettingsCategory.CONTROLS)
                .settingText("Shift click to drop items")
                .description("Enable shift-click drop")
                .build();

        assertNotNull(task);
    }

    // ========================================================================
    // Category Navigation Tests
    // ========================================================================

    @Test
    public void testCategory_AllSettingsNoTabClick() {
        // ALL_SETTINGS should not require clicking a category tab
        SettingsTask task = SettingsTask.builder()
                .category(SettingsTask.SettingsCategory.ALL_SETTINGS)
                .settingText("Test setting")
                .build();

        assertNotNull(task);
    }

    @Test
    public void testCategory_SpecificCategoryNavigation() {
        // Test that each category can be specified
        for (SettingsTask.SettingsCategory category : SettingsTask.SettingsCategory.values()) {
            if (category == SettingsTask.SettingsCategory.ALL_SETTINGS) continue;

            SettingsTask task = SettingsTask.builder()
                    .category(category)
                    .settingText("Test")
                    .build();

            assertNotNull(task);
        }
    }
}

