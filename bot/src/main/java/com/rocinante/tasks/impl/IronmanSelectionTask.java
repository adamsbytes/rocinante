package com.rocinante.tasks.impl;

import com.rocinante.behavior.AccountType;
import com.rocinante.state.IronmanState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.timing.DelayProfile;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import java.awt.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task to select Ironman mode via the Ironman Setup interface.
 * 
 * This interface appears when talking to Paul (Ironman tutor) on Tutorial Island
 * and selecting "I'd like to change my Ironman mode."
 * 
 * Interface: Group 890 (IRONMAN_SETUP)
 * Buttons:
 * - Widget 890:21 (0x037a_0015) = NONE (Standard account)
 * - Widget 890:22 (0x037a_0016) = Standard Ironman
 * - Widget 890:23 (0x037a_0017) = Ultimate Ironman
 * - Widget 890:24 (0x037a_0018) = Hardcore Ironman
 * - Widget 890:25 (0x037a_0019) = Group Ironman
 * - Widget 890:26 (0x037a_001a) = Hardcore Group Ironman
 * - Widget 890:27 (0x037a_001b) = Unranked Group Ironman
 * 
 * Note: Tutorial Island only allows Standard/Hardcore/Ultimate.
 * Group variants require talking to Adam the Ironman tutor in Lumbridge after tutorial.
 */
@Slf4j
public class IronmanSelectionTask extends AbstractTask {

    private static final int WIDGET_GROUP_IRONMAN_SETUP = 890;
    private static final int CHILD_NONE_BUTTON = 21;
    private static final int CHILD_STANDARD_IRONMAN_BUTTON = 22;
    private static final int CHILD_ULTIMATE_IRONMAN_BUTTON = 23;
    private static final int CHILD_HARDCORE_IRONMAN_BUTTON = 24;
    private static final int CHILD_GROUP_IRONMAN_BUTTON = 25;
    private static final int CHILD_HARDCORE_GROUP_IRONMAN_BUTTON = 26;
    
    private final IronmanState ironmanState;
    private final AccountType targetType;
    
    private Phase phase = Phase.WAIT_FOR_INTERFACE;
    private int waitTicks = 0;
    private final AtomicReference<CompletableFuture<Void>> pendingClick = new AtomicReference<>();

    private enum Phase {
        WAIT_FOR_INTERFACE,
        CLICK_BUTTON,
        WAIT_FOR_CLOSE,
        VERIFY_SELECTION
    }

    /**
     * Create an ironman selection task.
     * 
     * @param ironmanState the ironman state (for getting intended type)
     */
    public IronmanSelectionTask(IronmanState ironmanState) {
        this.ironmanState = ironmanState;
        this.targetType = ironmanState.getIntendedType();
        this.timeout = Duration.ofSeconds(30);
    }

    /**
     * Create an ironman selection task with explicit target type.
     * Used for correcting mismatches.
     * 
     * @param ironmanState the ironman state
     * @param targetType the account type to select
     */
    public IronmanSelectionTask(IronmanState ironmanState, AccountType targetType) {
        this.ironmanState = ironmanState;
        this.targetType = targetType;
        this.timeout = Duration.ofSeconds(30);
    }

    @Override
    public String getDescription() {
        return "Select ironman mode: " + targetType.getDisplayName();
    }

    @Override
    public boolean canExecute(TaskContext context) {
        return context.getClient() != null && context.getMouseController() != null;
    }

    @Override
    protected void executeImpl(TaskContext context) {
        switch (phase) {
            case WAIT_FOR_INTERFACE:
                executeWaitForInterface(context);
                break;
            case CLICK_BUTTON:
                executeClickButton(context);
                break;
            case WAIT_FOR_CLOSE:
                executeWaitForClose(context);
                break;
            case VERIFY_SELECTION:
                executeVerifySelection(context);
                break;
        }
    }

    private void executeWaitForInterface(TaskContext context) {
        Client client = context.getClient();
        Widget interfaceWidget = client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0);
        
        if (interfaceWidget != null && !interfaceWidget.isHidden()) {
            log.debug("Ironman setup interface detected");
            phase = Phase.CLICK_BUTTON;
            waitTicks = 0;
            return;
        }
        
        waitTicks++;
        if (waitTicks > 20) {
            log.warn("Ironman setup interface not found after {} ticks", waitTicks);
            fail("Ironman interface did not appear");
        }
    }

    private void executeClickButton(TaskContext context) {
        Client client = context.getClient();
        
        // Determine which button to click based on target type
        int buttonChild = getButtonChildForType(targetType);
        if (buttonChild < 0) {
            fail("Unsupported account type for Tutorial Island: " + targetType);
            return;
        }
        
        Widget buttonWidget = client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, buttonChild);
        if (buttonWidget == null || buttonWidget.isHidden()) {
            log.warn("Button widget {}:{} not found or hidden", WIDGET_GROUP_IRONMAN_SETUP, buttonChild);
            fail("Button not clickable");
            return;
        }
        
        Rectangle bounds = buttonWidget.getBounds();
        if (bounds == null || bounds.width == 0) {
            log.warn("Button has invalid bounds");
            fail("Button not clickable");
            return;
        }
        
        // Click the button
        if (pendingClick.get() == null) {
            int clickX = bounds.x + bounds.width / 2;
            int clickY = bounds.y + bounds.height / 2;
            
            log.info("Clicking {} button at ({}, {})", targetType.getDisplayName(), clickX, clickY);
            
            CompletableFuture<Void> click = context.getMouseController().clickAt(clickX, clickY);
            pendingClick.set(click);
        }
        
        // Wait for click to complete
        CompletableFuture<Void> click = pendingClick.get();
        if (click.isDone()) {
            try {
                click.get(); // Just ensure it completed without exception
                log.debug("Button clicked successfully, waiting for interface to close");
                phase = Phase.WAIT_FOR_CLOSE;
                waitTicks = 0;
            } catch (Exception e) {
                fail("Button click exception: " + e.getMessage());
            }
            pendingClick.set(null);
        }
    }

    private void executeWaitForClose(TaskContext context) {
        Client client = context.getClient();
        Widget interfaceWidget = client.getWidget(WIDGET_GROUP_IRONMAN_SETUP, 0);
        
        // Interface should close after selection
        if (interfaceWidget == null || interfaceWidget.isHidden()) {
            log.debug("Ironman interface closed, verifying selection");
            phase = Phase.VERIFY_SELECTION;
            waitTicks = 0;
            return;
        }
        
        waitTicks++;
        if (waitTicks > 10) {
            log.warn("Interface did not close after selection");
            fail("Interface did not close");
        }
    }

    private void executeVerifySelection(TaskContext context) {
        // Give varbit a moment to update
        waitTicks++;
        if (waitTicks < 3) {
            return; // Wait a few ticks for varbit to propagate
        }
        
        // Force immediate varbit update
        if (ironmanState != null) {
            ironmanState.updateFromVarbit();
            AccountType actualType = ironmanState.getActualType();
            
            if (actualType == targetType) {
                log.info("Ironman mode selected successfully: {}", actualType.getDisplayName());
                complete();
            } else {
                log.error("Ironman mode mismatch after selection: target={}, actual={}", 
                        targetType, actualType);
                fail(String.format("Selection failed: got %s instead of %s", 
                        actualType.getDisplayName(), targetType.getDisplayName()));
            }
        } else {
            // No IronmanState to verify with - just complete
            log.warn("No IronmanState available for verification");
            complete();
        }
    }

    /**
     * Get the widget child ID for the button corresponding to an account type.
     * 
     * @param accountType the account type
     * @return child widget ID, or -1 if not applicable
     */
    private int getButtonChildForType(AccountType accountType) {
        return switch (accountType) {
            case NORMAL -> CHILD_NONE_BUTTON;
            case IRONMAN -> CHILD_STANDARD_IRONMAN_BUTTON;
            case ULTIMATE_IRONMAN -> CHILD_ULTIMATE_IRONMAN_BUTTON;
            case HARDCORE_IRONMAN -> CHILD_HARDCORE_IRONMAN_BUTTON;
            case GROUP_IRONMAN -> CHILD_GROUP_IRONMAN_BUTTON;
            case HARDCORE_GROUP_IRONMAN -> CHILD_HARDCORE_GROUP_IRONMAN_BUTTON;
        };
    }
}

