package com.rocinante.behavior;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.tasks.TaskPriority;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.gameval.NpcID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for RandomEventHandler detection and task queuing.
 */
public class RandomEventHandlerTest {

    @Mock
    private Client client;

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private TaskContext taskContext;

    @Mock
    private NPC npc;

    @Mock
    private Player player;

    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        System.setProperty("rocinante.random.lampSkill", "PRAYER");

        when(client.getLocalPlayer()).thenReturn(player);
        when(client.getTickCount()).thenReturn(1000);
        when(npc.getIndex()).thenReturn(7);
    }

    @After
    public void tearDown() throws Exception {
        System.clearProperty("rocinante.random.lampSkill");
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    public void dismissesNonGenieRandomEvent() {
        when(npc.getId()).thenReturn(NpcID.MACRO_BEEKEEPER_INVITATION);

        RandomEventHandler handler = new RandomEventHandler(client, taskExecutor, taskContext);

        handler.onInteractingChanged(new InteractingChanged(npc, player));

        ArgumentCaptor<TaskPriority> priorityCaptor = ArgumentCaptor.forClass(TaskPriority.class);
        verify(taskExecutor, times(1)).queueTask(any(Task.class), priorityCaptor.capture());
        assertEquals(TaskPriority.URGENT, priorityCaptor.getValue());
    }

    @Test
    public void queuesGenieSequence() {
        when(npc.getId()).thenReturn(NpcID.MACRO_GENI);

        RandomEventHandler handler = new RandomEventHandler(client, taskExecutor, taskContext);

        handler.onInteractingChanged(new InteractingChanged(npc, player));

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskExecutor).queueTask(taskCaptor.capture(), eq(TaskPriority.URGENT));

        Task queued = taskCaptor.getValue();
        assertNotNull(queued);
        assertTrue(queued.getDescription().contains("Genie"));
    }
}

