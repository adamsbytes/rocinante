package com.rocinante.ui;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

public class GrandExchangeWidgetsTest {

    @Mock
    private Client client;

    @Mock
    private Widget widget;

    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(widget.isHidden()).thenReturn(false);
    }

    @Test
    public void packHelpersComputePackedIds() {
        int packedMain = GrandExchangeWidgets.pack(GrandExchangeWidgets.CHILD_INDEX);
        assertEquals((GrandExchangeWidgets.GROUP_ID << 16) | GrandExchangeWidgets.CHILD_INDEX, packedMain);

        int packedInv = GrandExchangeWidgets.packInventory(GrandExchangeWidgets.INVENTORY_ITEMS);
        assertEquals((GrandExchangeWidgets.INVENTORY_GROUP_ID << 16) | GrandExchangeWidgets.INVENTORY_ITEMS, packedInv);
    }

    @Test
    public void slotChildIdValidation() {
        assertEquals(GrandExchangeWidgets.CHILD_SLOT_3, GrandExchangeWidgets.getSlotChildId(3));
        try {
            GrandExchangeWidgets.getSlotChildId(-1);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Test
    public void isOpenAndViewsReflectWidgetVisibility() {
        when(client.getWidget(GrandExchangeWidgets.GROUP_ID, GrandExchangeWidgets.CHILD_UNIVERSE)).thenReturn(widget);
        assertTrue(GrandExchangeWidgets.isOpen(client));

        when(client.getWidget(GrandExchangeWidgets.GROUP_ID, GrandExchangeWidgets.CHILD_INDEX)).thenReturn(widget);
        assertTrue(GrandExchangeWidgets.isInIndexView(client));

        when(client.getWidget(GrandExchangeWidgets.GROUP_ID, GrandExchangeWidgets.CHILD_SETUP)).thenReturn(widget);
        assertTrue(GrandExchangeWidgets.isInSetupView(client));

        when(client.getWidget(GrandExchangeWidgets.GROUP_ID, GrandExchangeWidgets.CHILD_DETAILS)).thenReturn(widget);
        assertTrue(GrandExchangeWidgets.isInDetailsView(client));

        // Hidden widgets should report false
        when(widget.isHidden()).thenReturn(true);
        assertFalse(GrandExchangeWidgets.isInDetailsView(client));
    }
}


