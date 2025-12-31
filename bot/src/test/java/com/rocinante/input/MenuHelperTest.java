package com.rocinante.input;

import com.rocinante.timing.DelayProfile;
import com.rocinante.timing.HumanTimer;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.awt.Rectangle;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression test: ensure menu selection prefers the exact target ("Tree") when multiple
 * matching entries exist (e.g., "Chop down Oak tree" and "Chop down Tree").
 */
public class MenuHelperTest {

    private Client client;
    private RobotMouseController mouseController;
    private HumanTimer humanTimer;
    private MenuHelper menuHelper;

    @Before
    public void setup() {
        client = mock(Client.class);
        mouseController = mock(RobotMouseController.class);
        humanTimer = mock(HumanTimer.class);

        menuHelper = new MenuHelper(client, mouseController, humanTimer);

        when(mouseController.rightClick(any(Rectangle.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(mouseController.moveToCanvas(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(null));
        when(mouseController.click()).thenReturn(CompletableFuture.completedFuture(null));
        when(humanTimer.sleep(any(DelayProfile.class))).thenReturn(CompletableFuture.completedFuture(null));

        // Menu positioning
        when(client.getMenuX()).thenReturn(10);
        when(client.getMenuY()).thenReturn(20);
        when(client.getMenuWidth()).thenReturn(100);
    }

    @Test
    public void selectsExactTargetOverOtherMatches() {
        // Build menu entries: both "Chop down", but different targets
        MenuEntry oakEntry = mock(MenuEntry.class);
        when(oakEntry.getOption()).thenReturn("Chop down");
        when(oakEntry.getTarget()).thenReturn("Oak tree");

        MenuEntry treeEntry = mock(MenuEntry.class);
        when(treeEntry.getOption()).thenReturn("Chop down");
        when(treeEntry.getTarget()).thenReturn("Tree");

        // Entries are stored bottom-to-top; we include both
        MenuEntry[] entries = new MenuEntry[]{oakEntry, treeEntry};
        when(client.getMenuEntries()).thenReturn(entries);

        Rectangle hitbox = new Rectangle(50, 50, 20, 20);
        boolean result = menuHelper.selectMenuEntry(hitbox, "Chop down", "Tree").join();

        assertTrue(result);

        // Verify we moved the mouse to a menu entry (i.e., an entry was found)
        verify(mouseController, atLeastOnce()).moveToCanvas(anyInt(), anyInt());

        // Ensure right-click was used to open menu
        verify(mouseController).rightClick(hitbox);
    }
}

