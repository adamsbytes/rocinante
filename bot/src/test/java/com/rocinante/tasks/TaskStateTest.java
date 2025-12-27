package com.rocinante.tasks;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for TaskState enum and state transitions.
 * Tests the PAUSED state addition and all valid transitions.
 */
public class TaskStateTest {

    @Test
    public void testPausedState_IsNotTerminal() {
        assertFalse("PAUSED should not be terminal", TaskState.PAUSED.isTerminal());
    }

    @Test
    public void testPausedState_CanContinue() {
        assertTrue("PAUSED should allow continuation", TaskState.PAUSED.canContinue());
    }

    @Test
    public void testTerminalStates_AreTerminal() {
        assertTrue("COMPLETED should be terminal", TaskState.COMPLETED.isTerminal());
        assertTrue("FAILED should be terminal", TaskState.FAILED.isTerminal());
    }

    @Test
    public void testNonTerminalStates_AreNotTerminal() {
        assertFalse("PENDING should not be terminal", TaskState.PENDING.isTerminal());
        assertFalse("RUNNING should not be terminal", TaskState.RUNNING.isTerminal());
        assertFalse("PAUSED should not be terminal", TaskState.PAUSED.isTerminal());
    }

    @Test
    public void testCanContinue_ForAllNonTerminalStates() {
        assertTrue("PENDING should allow continuation", TaskState.PENDING.canContinue());
        assertTrue("RUNNING should allow continuation", TaskState.RUNNING.canContinue());
        assertTrue("PAUSED should allow continuation", TaskState.PAUSED.canContinue());
        assertFalse("COMPLETED should not allow continuation", TaskState.COMPLETED.canContinue());
        assertFalse("FAILED should not allow continuation", TaskState.FAILED.canContinue());
    }
}

