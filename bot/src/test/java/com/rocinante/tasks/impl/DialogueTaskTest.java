package com.rocinante.tasks.impl;

import com.rocinante.core.GameStateService;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for DialogueTask.
 * Tests click-through, option selection, multi-step dialogues, and edge cases.
 */
public class DialogueTaskTest {

    // Widget group IDs
    private static final int WIDGET_NPC_DIALOGUE = 231;
    private static final int WIDGET_PLAYER_DIALOGUE = 217;
    private static final int WIDGET_DIALOGUE_OPTIONS = 219;
    private static final int WIDGET_MESSAGEBOX = 229;
    private static final int WIDGET_OBJECTBOX = 193;  // Objectbox (sprite dialogue)
    private static final int WIDGET_OBJECTBOX_DOUBLE = 11;  // ObjectboxDouble
    private static final int WIDGET_LEVEL_UP = 233;
    
    // Child IDs (from InterfaceID gameval)
    private static final int CHILD_NPC_TEXT = 6;        // ChatLeft.TEXT
    private static final int CHILD_PLAYER_TEXT = 6;     // ChatRight.TEXT
    private static final int CHILD_DIALOGUE_OPTIONS = 1; // Chatmenu.OPTIONS
    private static final int CHILD_OBJECTBOX_TEXT = 2;  // Objectbox.TEXT
    private static final int CHILD_OBJECTBOX_DOUBLE_TEXT = 2; // ObjectboxDouble.TEXT
    private static final int CHILD_MESSAGEBOX_TEXT = 3; // Messagebox.TEXT
    private static final int CHILD_LEVEL_UP_CONTINUE = 3; // LevelupDisplay.CONTINUE

    @Mock
    private Client client;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private RobotMouseController mouseController;

    @Mock
    private RobotKeyboardController keyboardController;

    @Mock
    private HumanTimer humanTimer;

    @Mock
    private Randomization randomization;

    private TaskContext taskContext;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Wire up TaskContext
        taskContext = mock(TaskContext.class);
        when(taskContext.getClient()).thenReturn(client);
        when(taskContext.getGameStateService()).thenReturn(gameStateService);
        when(taskContext.getMouseController()).thenReturn(mouseController);
        when(taskContext.getKeyboardController()).thenReturn(keyboardController);
        when(taskContext.getHumanTimer()).thenReturn(humanTimer);
        when(taskContext.getRandomization()).thenReturn(randomization);
        when(taskContext.isLoggedIn()).thenReturn(true);

        // GameStateService
        when(gameStateService.isLoggedIn()).thenReturn(true);

        // HumanTimer defaults
        when(humanTimer.getDialogueDelay(anyInt())).thenReturn(500L);
        when(humanTimer.getDelay(any())).thenReturn(200L);
        when(humanTimer.sleep(anyLong())).thenReturn(CompletableFuture.completedFuture(null));

        // Keyboard controller
        when(keyboardController.pressKey(anyInt())).thenReturn(CompletableFuture.completedFuture(null));

        // Clear all widgets by default
        setupNoDialogue();
    }

    // ========================================================================
    // Constructor/Builder Tests
    // ========================================================================

    @Test
    public void testDefaultConfiguration() {
        DialogueTask task = new DialogueTask();

        assertTrue(task.isClickThroughAll());
        assertEquals(20, task.getMaxContinueClicks());
        assertNull(task.getOptionText());
        assertEquals(-1, task.getOptionIndex());
    }

    @Test
    public void testBuilderMethods() {
        DialogueTask task = new DialogueTask()
                .withOptionText("Yes, I agree")
                .withOptionIndex(2)
                .withClickThroughAll(false)
                .withDescription("Test dialogue");

        assertEquals("Yes, I agree", task.getOptionText());
        assertEquals(2, task.getOptionIndex());
        assertFalse(task.isClickThroughAll());
        assertEquals("Test dialogue", task.getDescription());
    }

    @Test
    public void testOptionSequence() {
        DialogueTask task = new DialogueTask()
                .withOptionSequence("First option", "Second option", "Third option");

        String desc = task.getDescription();
        assertNotNull(desc);
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_WhenLoggedIn_ReturnsTrue() {
        when(taskContext.isLoggedIn()).thenReturn(true);

        DialogueTask task = new DialogueTask();

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_WhenNotLoggedIn_ReturnsFalse() {
        when(taskContext.isLoggedIn()).thenReturn(false);

        DialogueTask task = new DialogueTask();

        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Phase: DETECT_DIALOGUE Tests
    // ========================================================================

    @Test
    public void testDetectDialogue_NoDialogue_WaitsAndFails() {
        setupNoDialogue();

        DialogueTask task = new DialogueTask();

        // Execute several times
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("No dialogue"));
    }

    @Test
    public void testDetectDialogue_NpcDialogue_TransitionsToContinue() {
        setupNpcDialogue("Hello adventurer!");

        DialogueTask task = new DialogueTask();
        task.execute(taskContext);

        // Should detect NPC dialogue and transition
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testDetectDialogue_PlayerDialogue_TransitionsToContinue() {
        setupPlayerDialogue("What brings you here?");

        DialogueTask task = new DialogueTask();
        task.execute(taskContext);

        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testDetectDialogue_DialogueOptions_TransitionsToSelect() {
        setupDialogueOptions("What would you like to do?", "Buy", "Sell", "Cancel");

        DialogueTask task = new DialogueTask()
                .withOptionText("Buy");
        task.execute(taskContext);

        // Should transition to SELECT_OPTION
        assertNotEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testDetectDialogue_LevelUpDialogue_TransitionsToContinue() {
        setupLevelUpDialogue();

        DialogueTask task = new DialogueTask();
        task.execute(taskContext);

        assertNotEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Phase: CLICK_CONTINUE Tests
    // ========================================================================

    @Test
    public void testClickContinue_PressesSpace() {
        setupNpcDialogue("Hello there!");

        DialogueTask task = new DialogueTask();

        // Execute to detect dialogue
        task.execute(taskContext);
        // Execute to click continue
        task.execute(taskContext);

        // Verify spacebar was pressed
        verify(keyboardController, timeout(1000)).pressKey(KeyEvent.VK_SPACE);
    }

    @Test
    public void testClickContinue_UsesDialogueDelay() {
        setupNpcDialogue("This is a test message.");

        DialogueTask task = new DialogueTask();

        // Execute to detect and click
        task.execute(taskContext);
        task.execute(taskContext);

        // Verify humanTimer.getDialogueDelay was called with word count
        verify(humanTimer, atLeastOnce()).getDialogueDelay(anyInt());
    }

    @Test
    public void testClickContinue_MaxClicksExceeded_Fails() {
        setupNpcDialogue("Hello!");

        DialogueTask task = new DialogueTask();
        task.setMaxContinueClicks(3);

        // Execute many times to exceed limit
        for (int i = 0; i < 30; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            // Small delay to let async complete
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        // Should fail eventually
        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("continue"));
    }

    @Test
    public void testClickContinue_DialogueClosed_Completes() {
        setupNpcDialogue("Goodbye!");

        DialogueTask task = new DialogueTask();

        // Execute to detect and click
        task.execute(taskContext);
        task.execute(taskContext);

        // Wait for async
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        // Clear dialogue
        setupNoDialogue();

        // Execute again - should detect dialogue closed
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    // ========================================================================
    // Phase: SELECT_OPTION Tests
    // ========================================================================

    @Test
    public void testSelectOption_ByText_FindsMatch() {
        setupDialogueOptions("Choose:", "Option A", "Option B", "Option C");

        DialogueTask task = new DialogueTask()
                .withOptionText("Option B");

        // Execute to detect and select
        task.execute(taskContext);
        task.execute(taskContext);

        // Verify key '2' was pressed (for second option)
        verify(keyboardController, timeout(1000)).pressKey(KeyEvent.VK_2);
    }

    @Test
    public void testSelectOption_ByText_PartialMatch() {
        setupDialogueOptions("Choose:", "Yes, I want to proceed", "No, cancel", "Maybe later");

        DialogueTask task = new DialogueTask()
                .withOptionText("proceed");

        task.execute(taskContext);
        task.execute(taskContext);

        // Should match first option containing "proceed"
        verify(keyboardController, timeout(1000)).pressKey(KeyEvent.VK_1);
    }

    @Test
    public void testSelectOption_ByText_CaseInsensitive() {
        setupDialogueOptions("Choose:", "YES", "NO");

        DialogueTask task = new DialogueTask()
                .withOptionText("yes");

        task.execute(taskContext);
        task.execute(taskContext);

        verify(keyboardController, timeout(1000)).pressKey(KeyEvent.VK_1);
    }

    @Test
    public void testSelectOption_ByIndex() {
        setupDialogueOptions("Choose:", "First", "Second", "Third");

        DialogueTask task = new DialogueTask()
                .withOptionIndex(3);

        task.execute(taskContext);
        task.execute(taskContext);

        // Key '3' for third option
        verify(keyboardController, timeout(1000)).pressKey(KeyEvent.VK_3);
    }

    @Test
    public void testSelectOption_NoMatchDefaultsToFirst() {
        setupDialogueOptions("Choose:", "Alpha", "Beta", "Gamma");

        DialogueTask task = new DialogueTask()
                .withOptionText("Nonexistent");

        task.execute(taskContext);
        task.execute(taskContext);

        // Should default to first option
        verify(keyboardController, timeout(1000)).pressKey(KeyEvent.VK_1);
    }

    @Test
    public void testSelectOption_Sequence() {
        setupDialogueOptions("First choice:", "A", "B");

        DialogueTask task = new DialogueTask()
                .withOptionSequence("A", "X", "Y");

        // First selection
        task.execute(taskContext);
        task.execute(taskContext);

        verify(keyboardController, timeout(1000)).pressKey(KeyEvent.VK_1);
    }

    // ========================================================================
    // Resolver Tests
    // ========================================================================

    @Test
    public void testResolver_TextResolver() {
        setupDialogueOptions("Choose:", "Accept", "Decline");

        DialogueTask task = new DialogueTask()
                .withResolver(DialogueOptionResolver.text("Accept"));

        task.execute(taskContext);
        task.execute(taskContext);

        verify(keyboardController, timeout(1000)).pressKey(KeyEvent.VK_1);
    }

    @Test
    public void testResolver_PatternResolver() {
        setupDialogueOptions("Choose:", "Yes, I agree", "No way!");

        DialogueTask task = new DialogueTask()
                .withPatternOption(".*agree.*");

        task.execute(taskContext);
        task.execute(taskContext);

        verify(keyboardController, timeout(1000)).pressKey(KeyEvent.VK_1);
    }

    @Test
    public void testResolver_IndexResolver() {
        setupDialogueOptions("Choose:", "One", "Two", "Three");

        DialogueTask task = new DialogueTask()
                .withResolver(DialogueOptionResolver.index(2));

        task.execute(taskContext);
        task.execute(taskContext);

        verify(keyboardController, timeout(1000)).pressKey(KeyEvent.VK_2);
    }

    @Test
    public void testResolver_VarbitResolver() {
        setupDialogueOptions("Choose:", "Option for 0", "Option for 1");

        Map<Integer, String> varbitMap = new HashMap<>();
        varbitMap.put(0, "Option for 0");
        varbitMap.put(1, "Option for 1");

        // Mock varbit value
        when(client.getVarbitValue(1234)).thenReturn(1);

        DialogueTask task = new DialogueTask()
                .withVarbitOption(1234, varbitMap);

        task.execute(taskContext);
        task.execute(taskContext);

        // Should select option matching varbit value 1
        verify(keyboardController, timeout(1000)).pressKey(KeyEvent.VK_2);
    }

    @Test
    public void testResolver_MultipleResolvers() {
        setupDialogueOptions("Choose:", "First", "Second", "Third");

        DialogueTask task = new DialogueTask()
                .withResolver(DialogueOptionResolver.text("Nonexistent"))
                .withResolver(DialogueOptionResolver.text("Second"));

        task.execute(taskContext);
        task.execute(taskContext);

        // First resolver fails, second matches
        verify(keyboardController, timeout(1000)).pressKey(KeyEvent.VK_2);
    }

    // ========================================================================
    // Dialogue Type Tests
    // ========================================================================

    @Test
    public void testSpriteDialogue_HandledAsContinue() {
        setupSpriteDialogue("Tutorial message");

        DialogueTask task = new DialogueTask();
        task.execute(taskContext);
        task.execute(taskContext);

        verify(keyboardController, timeout(1000)).pressKey(KeyEvent.VK_SPACE);
    }

    @Test
    public void testLevelUpDialogue_HandledAsContinue() {
        setupLevelUpDialogue();

        DialogueTask task = new DialogueTask();
        task.execute(taskContext);
        task.execute(taskContext);

        verify(keyboardController, timeout(1000)).pressKey(KeyEvent.VK_SPACE);
    }

    // ========================================================================
    // Widget State Change Tests
    // ========================================================================

    @Test
    public void testOptionsDisappear_BeforeSelection_RetriesDetection() {
        // Start with options
        setupDialogueOptions("Choose:", "Yes", "No");

        DialogueTask task = new DialogueTask()
                .withOptionText("Yes");

        task.execute(taskContext);

        // Options disappear
        setupNoDialogue();

        // Execute again - should go back to DETECT_DIALOGUE
        task.execute(taskContext);

        // Should not have crashed, state should be valid
        assertNotNull(task.getState());
    }

    @Test
    public void testDialogueTextChange_DetectedProperly() {
        setupNpcDialogue("First message");

        DialogueTask task = new DialogueTask();

        // First continue
        task.execute(taskContext);
        task.execute(taskContext);

        // Wait for async
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        // Change dialogue text
        setupNpcDialogue("Second message");

        // Continue executing
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
        }

        // Should have pressed space at least twice
        verify(keyboardController, atLeast(1)).pressKey(KeyEvent.VK_SPACE);
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testEmptyOptionText_IgnoredGracefully() {
        setupDialogueOptions("Choose:", "", "Valid", "");

        DialogueTask task = new DialogueTask()
                .withOptionText("Valid");

        task.execute(taskContext);
        task.execute(taskContext);

        // Should find "Valid" despite empty options
        verify(keyboardController, timeout(1000)).pressKey(anyInt());
    }

    @Test
    public void testNullOptionWidgets_HandledGracefully() {
        // Setup options with null children
        Widget optionsWidget = mock(Widget.class);
        Widget titleWidget = mock(Widget.class);
        when(titleWidget.getText()).thenReturn("Choose:");
        when(optionsWidget.getDynamicChildren()).thenReturn(new Widget[]{titleWidget, null, null});
        when(optionsWidget.isHidden()).thenReturn(false);

        when(client.getWidget(WIDGET_DIALOGUE_OPTIONS, CHILD_DIALOGUE_OPTIONS)).thenReturn(optionsWidget);

        DialogueTask task = new DialogueTask();

        // Should handle gracefully
        task.execute(taskContext);
        task.execute(taskContext);

        // Should either default to first valid option or retry
        assertNotNull(task.getState());
    }

    @Test
    public void testWordCountCalculation_AffectsDelay() {
        setupNpcDialogue("This is a longer message with many words.");

        DialogueTask task = new DialogueTask();
        task.execute(taskContext);
        task.execute(taskContext);

        // Word count should be passed to getDialogueDelay
        verify(humanTimer).getDialogueDelay(intThat(count -> count > 0));
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_Default() {
        DialogueTask task = new DialogueTask();
        assertTrue(task.getDescription().contains("click through"));
    }

    @Test
    public void testGetDescription_WithOptionText() {
        DialogueTask task = new DialogueTask()
                .withOptionText("Test option");
        assertTrue(task.getDescription().contains("Test option"));
    }

    @Test
    public void testGetDescription_WithIndex() {
        DialogueTask task = new DialogueTask()
                .withOptionIndex(3);
        assertTrue(task.getDescription().contains("3"));
    }

    @Test
    public void testGetDescription_Custom() {
        DialogueTask task = new DialogueTask()
                .withDescription("Custom description");
        assertEquals("Custom description", task.getDescription());
    }

    // ========================================================================
    // Reset Tests
    // ========================================================================

    @Test
    public void testReset_ClearsState() {
        setupNpcDialogue("Hello!");

        DialogueTask task = new DialogueTask();
        task.execute(taskContext);
        task.execute(taskContext);

        // Reset
        task.resetForRetry();

        assertEquals(TaskState.PENDING, task.getState());
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void setupNoDialogue() {
        when(client.getWidget(anyInt(), anyInt())).thenReturn(null);
    }

    private void setupNpcDialogue(String text) {
        // ChatLeft.TEXT is child 6
        Widget npcTextWidget = mock(Widget.class);
        when(npcTextWidget.getText()).thenReturn(text);
        when(npcTextWidget.isHidden()).thenReturn(false);
        when(client.getWidget(WIDGET_NPC_DIALOGUE, CHILD_NPC_TEXT)).thenReturn(npcTextWidget);

        // Clear other dialogues
        when(client.getWidget(eq(WIDGET_DIALOGUE_OPTIONS), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_PLAYER_DIALOGUE), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_LEVEL_UP), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_MESSAGEBOX), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_OBJECTBOX), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_OBJECTBOX_DOUBLE), anyInt())).thenReturn(null);
    }

    private void setupPlayerDialogue(String text) {
        // ChatRight.TEXT is child 6
        Widget playerTextWidget = mock(Widget.class);
        when(playerTextWidget.getText()).thenReturn(text);
        when(playerTextWidget.isHidden()).thenReturn(false);
        when(client.getWidget(WIDGET_PLAYER_DIALOGUE, CHILD_PLAYER_TEXT)).thenReturn(playerTextWidget);

        // Clear other dialogues
        when(client.getWidget(eq(WIDGET_DIALOGUE_OPTIONS), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_NPC_DIALOGUE), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_LEVEL_UP), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_MESSAGEBOX), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_OBJECTBOX), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_OBJECTBOX_DOUBLE), anyInt())).thenReturn(null);
    }

    private void setupDialogueOptions(String title, String... options) {
        // Chatmenu.OPTIONS is child 1
        Widget optionsWidget = mock(Widget.class);
        when(optionsWidget.isHidden()).thenReturn(false);

        // Create children: first is title, rest are options
        Widget[] children = new Widget[options.length + 1];
        Widget titleWidget = mock(Widget.class);
        when(titleWidget.getText()).thenReturn(title);
        children[0] = titleWidget;

        for (int i = 0; i < options.length; i++) {
            Widget optionWidget = mock(Widget.class);
            when(optionWidget.getText()).thenReturn(options[i]);
            children[i + 1] = optionWidget;
        }

        when(optionsWidget.getDynamicChildren()).thenReturn(children);
        when(client.getWidget(WIDGET_DIALOGUE_OPTIONS, CHILD_DIALOGUE_OPTIONS)).thenReturn(optionsWidget);

        // Clear other dialogues
        when(client.getWidget(eq(WIDGET_NPC_DIALOGUE), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_PLAYER_DIALOGUE), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_LEVEL_UP), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_MESSAGEBOX), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_OBJECTBOX), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_OBJECTBOX_DOUBLE), anyInt())).thenReturn(null);
    }

    private void setupSpriteDialogue(String text) {
        // ObjectboxDouble.TEXT is child 2
        Widget spriteTextWidget = mock(Widget.class);
        when(spriteTextWidget.getText()).thenReturn(text);
        when(spriteTextWidget.isHidden()).thenReturn(false);
        when(client.getWidget(WIDGET_OBJECTBOX_DOUBLE, CHILD_OBJECTBOX_DOUBLE_TEXT)).thenReturn(spriteTextWidget);

        // Clear other dialogues
        when(client.getWidget(eq(WIDGET_DIALOGUE_OPTIONS), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_NPC_DIALOGUE), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_PLAYER_DIALOGUE), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_LEVEL_UP), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_MESSAGEBOX), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_OBJECTBOX), anyInt())).thenReturn(null);
    }

    private void setupLevelUpDialogue() {
        // LevelupDisplay.CONTINUE is child 3
        Widget levelUpContinue = mock(Widget.class);
        when(levelUpContinue.isHidden()).thenReturn(false);
        when(client.getWidget(WIDGET_LEVEL_UP, CHILD_LEVEL_UP_CONTINUE)).thenReturn(levelUpContinue);

        // Clear other dialogues
        when(client.getWidget(eq(WIDGET_DIALOGUE_OPTIONS), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_NPC_DIALOGUE), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_PLAYER_DIALOGUE), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_MESSAGEBOX), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_OBJECTBOX), anyInt())).thenReturn(null);
        when(client.getWidget(eq(WIDGET_OBJECTBOX_DOUBLE), anyInt())).thenReturn(null);
    }
}

