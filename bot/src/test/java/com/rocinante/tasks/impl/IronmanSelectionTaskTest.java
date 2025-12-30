package com.rocinante.tasks.impl;

import com.rocinante.behavior.AccountType;
import com.rocinante.input.RobotMouseController;
import com.rocinante.state.IronmanState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
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
 * Comprehensive tests for IronmanSelectionTask.
 * Tests interface detection, button clicking, and account type verification.
 */
public class IronmanSelectionTaskTest {

    private static final int WIDGET_GROUP_IRONMAN_SETUP = 890;

    @Mock
    private Client client;

    @Mock
    private RobotMouseController mouseController;

    @Mock
    private IronmanState ironmanState;

    @Mock
    private Widget interfaceWidget;

    @Mock
    private Widget buttonWidget;

    private TaskContext taskContext;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // TaskContext wiring
        taskContext = mock(TaskContext.class);
        when(taskContext.getClient()).thenReturn(client);
        when(taskContext.getMouseController()).thenReturn(mouseController);

        // Default ironman state
        when(ironmanState.getIntendedType()).thenReturn(AccountType.IRONMAN);
        when(ironmanState.getActualType()).thenReturn(AccountType.NORMAL);

        // Default mouse controller
        when(mouseController.clickAt(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Default widget setup
        when(interfaceWidget.isHidden()).thenReturn(false);
        when(buttonWidget.isHidden()).thenReturn(false);
        when(buttonWidget.getBounds()).thenReturn(new Rectangle(400, 300, 100, 30));
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Test
    public void testConstructor_WithIronmanState() {
        when(ironmanState.getIntendedType()).thenReturn(AccountType.HARDCORE_IRONMAN);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);

        assertNotNull(task);
        assertTrue(task.getDescription().contains("Hardcore"));
    }

    @Test
    public void testConstructor_WithExplicitType() {
        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState, AccountType.ULTIMATE_IRONMAN);

        assertNotNull(task);
        assertTrue(task.getDescription().contains("Ultimate"));
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_HasClientAndMouse_True() {
        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);

        assertTrue(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_NoClient_False() {
        when(taskContext.getClient()).thenReturn(null);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);

        assertFalse(task.canExecute(taskContext));
    }

    @Test
    public void testCanExecute_NoMouseController_False() {
        when(taskContext.getMouseController()).thenReturn(null);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);

        assertFalse(task.canExecute(taskContext));
    }

    // ========================================================================
    // Wait For Interface Phase Tests
    // ========================================================================

    @Test
    public void testWaitForInterface_Found_AdvancesToClickButton() {
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0)).thenReturn(interfaceWidget);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 22)).thenReturn(buttonWidget);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);
        task.execute(taskContext);
        task.execute(taskContext);

        // Should have tried to get button widget
        verify(client, atLeastOnce()).getWidget(eq(WIDGET_GROUP_IRONMAN_SETUP), anyInt());
    }

    @Test
    public void testWaitForInterface_NotFound_WaitsAndFails() {
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0)).thenReturn(null);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);
        
        // Execute many times to trigger timeout
        for (int i = 0; i < 25; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("interface"));
    }

    @Test
    public void testWaitForInterface_Hidden_WaitsAndFails() {
        when(interfaceWidget.isHidden()).thenReturn(true);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0)).thenReturn(interfaceWidget);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);
        
        for (int i = 0; i < 25; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Click Button Phase Tests
    // ========================================================================

    @Test
    public void testClickButton_NormalAccount_ClicksCorrectButton() {
        when(ironmanState.getIntendedType()).thenReturn(AccountType.NORMAL);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0)).thenReturn(interfaceWidget);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 21)).thenReturn(buttonWidget); // NONE button

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);
        task.execute(taskContext);
        task.execute(taskContext);

        verify(client).getWidget(WIDGET_GROUP_IRONMAN_SETUP, 21);
    }

    @Test
    public void testClickButton_Ironman_ClicksCorrectButton() {
        when(ironmanState.getIntendedType()).thenReturn(AccountType.IRONMAN);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0)).thenReturn(interfaceWidget);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 22)).thenReturn(buttonWidget);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);
        task.execute(taskContext);
        task.execute(taskContext);

        verify(client).getWidget(WIDGET_GROUP_IRONMAN_SETUP, 22);
    }

    @Test
    public void testClickButton_UltimateIronman_ClicksCorrectButton() {
        when(ironmanState.getIntendedType()).thenReturn(AccountType.ULTIMATE_IRONMAN);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0)).thenReturn(interfaceWidget);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 23)).thenReturn(buttonWidget);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);
        task.execute(taskContext);
        task.execute(taskContext);

        verify(client).getWidget(WIDGET_GROUP_IRONMAN_SETUP, 23);
    }

    @Test
    public void testClickButton_HardcoreIronman_ClicksCorrectButton() {
        when(ironmanState.getIntendedType()).thenReturn(AccountType.HARDCORE_IRONMAN);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0)).thenReturn(interfaceWidget);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 24)).thenReturn(buttonWidget);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);
        task.execute(taskContext);
        task.execute(taskContext);

        verify(client).getWidget(WIDGET_GROUP_IRONMAN_SETUP, 24);
    }

    @Test
    public void testClickButton_GroupIronman_ClicksCorrectButton() {
        when(ironmanState.getIntendedType()).thenReturn(AccountType.GROUP_IRONMAN);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0)).thenReturn(interfaceWidget);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 25)).thenReturn(buttonWidget);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);
        task.execute(taskContext);
        task.execute(taskContext);

        verify(client).getWidget(WIDGET_GROUP_IRONMAN_SETUP, 25);
    }

    @Test
    public void testClickButton_ButtonNotFound_Fails() {
        when(ironmanState.getIntendedType()).thenReturn(AccountType.IRONMAN);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0)).thenReturn(interfaceWidget);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 22)).thenReturn(null);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);
        task.execute(taskContext);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("clickable"));
    }

    @Test
    public void testClickButton_InvalidBounds_Fails() {
        when(buttonWidget.getBounds()).thenReturn(new Rectangle(0, 0, 0, 0));
        when(ironmanState.getIntendedType()).thenReturn(AccountType.IRONMAN);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0)).thenReturn(interfaceWidget);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 22)).thenReturn(buttonWidget);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);
        task.execute(taskContext);
        task.execute(taskContext);

        assertEquals(TaskState.FAILED, task.getState());
    }

    // ========================================================================
    // Verification Phase Tests
    // ========================================================================

    @Test
    public void testVerifySelection_Matches_Completes() {
        when(ironmanState.getIntendedType()).thenReturn(AccountType.IRONMAN);
        when(ironmanState.getActualType()).thenReturn(AccountType.IRONMAN); // Matches!
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0))
                .thenReturn(interfaceWidget) // First call: visible
                .thenReturn(null); // After click: closed
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 22)).thenReturn(buttonWidget);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);
        
        // Execute through all phases
        for (int i = 0; i < 10; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testVerifySelection_Mismatch_Fails() {
        when(ironmanState.getIntendedType()).thenReturn(AccountType.IRONMAN);
        when(ironmanState.getActualType()).thenReturn(AccountType.NORMAL); // Mismatch!
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0))
                .thenReturn(interfaceWidget)
                .thenReturn(null);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 22)).thenReturn(buttonWidget);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);
        
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.FAILED, task.getState());
        assertTrue(task.getFailureReason().contains("Selection failed"));
    }

    // ========================================================================
    // Description Tests
    // ========================================================================

    @Test
    public void testGetDescription_IncludesAccountType() {
        when(ironmanState.getIntendedType()).thenReturn(AccountType.HARDCORE_IRONMAN);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);

        String desc = task.getDescription();
        assertTrue(desc.contains("Hardcore Ironman"));
    }

    @Test
    public void testGetDescription_AllAccountTypes() {
        for (AccountType type : AccountType.values()) {
            IronmanSelectionTask task = new IronmanSelectionTask(ironmanState, type);
            
            String desc = task.getDescription();
            assertTrue(desc.contains(type.getDisplayName()));
        }
    }

    // ========================================================================
    // Real Game Scenario Tests
    // ========================================================================

    @Test
    public void testScenario_TutorialIslandIronmanSelection() {
        // On Tutorial Island, player talks to Paul and selects ironman mode
        when(ironmanState.getIntendedType()).thenReturn(AccountType.IRONMAN);
        // getActualType is only called once in VERIFY phase - should return the selected type
        when(ironmanState.getActualType()).thenReturn(AccountType.IRONMAN);
        
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0))
                .thenReturn(interfaceWidget)
                .thenReturn(interfaceWidget)
                .thenReturn(null); // Interface closes after click
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 22)).thenReturn(buttonWidget);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);
        
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testScenario_HardcoreIronmanNewAccount() {
        // Fresh account selecting HCIM for the first time
        when(ironmanState.getIntendedType()).thenReturn(AccountType.HARDCORE_IRONMAN);
        // getActualType is only called once in VERIFY phase
        when(ironmanState.getActualType()).thenReturn(AccountType.HARDCORE_IRONMAN);
        
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0))
                .thenReturn(interfaceWidget)
                .thenReturn(interfaceWidget)
                .thenReturn(null);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 24)).thenReturn(buttonWidget);

        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState);
        
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testScenario_CorrectionFromMismatch() {
        // Player accidentally selected wrong mode, now correcting to UIM
        // getActualType is only called once in VERIFY phase - should return UIM after correction
        when(ironmanState.getActualType()).thenReturn(AccountType.ULTIMATE_IRONMAN);
        
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0))
                .thenReturn(interfaceWidget)
                .thenReturn(interfaceWidget)
                .thenReturn(null);
        when(client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 23)).thenReturn(buttonWidget);

        // Explicitly selecting UIM to correct
        IronmanSelectionTask task = new IronmanSelectionTask(ironmanState, AccountType.ULTIMATE_IRONMAN);
        
        for (int i = 0; i < 15; i++) {
            if (task.getState().isTerminal()) break;
            task.execute(taskContext);
        }

        assertEquals(TaskState.COMPLETED, task.getState());
    }
}

