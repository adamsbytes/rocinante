package com.rocinante.ui;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import javax.annotation.Nullable;

/**
 * Widget constants and utilities for Grand Exchange interface interaction.
 *
 * <p>The GE interface uses two main widget groups:
 * <ul>
 *   <li>{@link #GROUP_ID} (465) - Main GE interface</li>
 *   <li>{@link #INVENTORY_GROUP_ID} (467) - GE inventory panel</li>
 * </ul>
 *
 * <p>Widget IDs are derived from RuneLite's InterfaceID.GeOffers class.
 */
public final class GrandExchangeWidgets {

    private GrandExchangeWidgets() {
        // Utility class
    }

    // ========================================================================
    // Group IDs
    // ========================================================================

    /**
     * Main GE interface group ID (InterfaceID.GE_OFFERS = 465).
     */
    public static final int GROUP_ID = 465;

    /**
     * GE inventory panel group ID (InterfaceID.GE_OFFERS_SIDE = 467).
     */
    public static final int INVENTORY_GROUP_ID = 467;

    /**
     * GE price checker group ID (InterfaceID.GE_PRICECHECKER = 464).
     */
    public static final int PRICE_CHECKER_GROUP_ID = 464;

    // ========================================================================
    // Main Interface Child IDs (relative to GROUP_ID)
    // ========================================================================

    /**
     * Root container widget.
     */
    public static final int CHILD_UNIVERSE = 0;

    /**
     * Main content area.
     */
    public static final int CHILD_CONTENTS = 1;

    /**
     * Window frame.
     */
    public static final int CHILD_FRAME = 2;

    /**
     * History button.
     */
    public static final int CHILD_HISTORY = 3;

    /**
     * Back button.
     */
    public static final int CHILD_BACK = 4;

    /**
     * Index/main view.
     */
    public static final int CHILD_INDEX = 5;

    /**
     * Collect all button.
     */
    public static final int CHILD_COLLECT_ALL = 6;

    // ========================================================================
    // Offer Slot Child IDs (0-7)
    // ========================================================================

    /**
     * Offer slot 0 widget.
     */
    public static final int CHILD_SLOT_0 = 7;

    /**
     * Offer slot 1 widget.
     */
    public static final int CHILD_SLOT_1 = 8;

    /**
     * Offer slot 2 widget.
     */
    public static final int CHILD_SLOT_2 = 9;

    /**
     * Offer slot 3 widget.
     */
    public static final int CHILD_SLOT_3 = 10;

    /**
     * Offer slot 4 widget.
     */
    public static final int CHILD_SLOT_4 = 11;

    /**
     * Offer slot 5 widget.
     */
    public static final int CHILD_SLOT_5 = 12;

    /**
     * Offer slot 6 widget.
     */
    public static final int CHILD_SLOT_6 = 13;

    /**
     * Offer slot 7 widget.
     */
    public static final int CHILD_SLOT_7 = 14;

    /**
     * Array of slot child IDs for iteration.
     */
    public static final int[] SLOT_CHILD_IDS = {
            CHILD_SLOT_0, CHILD_SLOT_1, CHILD_SLOT_2, CHILD_SLOT_3,
            CHILD_SLOT_4, CHILD_SLOT_5, CHILD_SLOT_6, CHILD_SLOT_7
    };

    // ========================================================================
    // Offer Details View Child IDs
    // ========================================================================

    /**
     * Offer details container (when viewing a specific offer).
     */
    public static final int CHILD_DETAILS = 15;

    /**
     * Offer details description text.
     */
    public static final int CHILD_DETAILS_DESC = 16;

    /**
     * Market price display in details.
     */
    public static final int CHILD_DETAILS_MARKET_PRICE = 17;

    /**
     * Fee display in details.
     */
    public static final int CHILD_DETAILS_FEE = 18;

    /**
     * Offer status in details view.
     */
    public static final int CHILD_DETAILS_STATUS = 23;

    /**
     * Collect button in details view.
     */
    public static final int CHILD_DETAILS_COLLECT = 24;

    /**
     * Modify/abort button in details view.
     */
    public static final int CHILD_DETAILS_MODIFY = 25;

    // ========================================================================
    // Offer Setup View Child IDs (when creating a new offer)
    // ========================================================================

    /**
     * Offer setup container (buy/sell creation view).
     */
    public static final int CHILD_SETUP = 26;

    /**
     * Setup description text.
     */
    public static final int CHILD_SETUP_DESC = 27;

    /**
     * Market price in setup view.
     */
    public static final int CHILD_SETUP_MARKET_PRICE = 28;

    /**
     * Fee display in setup view.
     */
    public static final int CHILD_SETUP_FEE = 29;

    /**
     * Confirm button in setup view.
     */
    public static final int CHILD_SETUP_CONFIRM = 30;

    /**
     * Popup container.
     */
    public static final int CHILD_POPUP = 32;

    /**
     * Tooltip container.
     */
    public static final int CHILD_TOOLTIP = 33;

    // ========================================================================
    // Inventory Side Panel Child IDs (relative to INVENTORY_GROUP_ID)
    // ========================================================================

    /**
     * Inventory items container in GE side panel.
     */
    public static final int INVENTORY_ITEMS = 0;

    /**
     * Glow/highlight effect.
     */
    public static final int INVENTORY_GLOW = 1;

    // ========================================================================
    // Slot-Specific Offsets (child widgets within each slot)
    // ========================================================================

    /**
     * Offset for buy button within a slot.
     */
    public static final int SLOT_OFFSET_BUY_BUTTON = 0;

    /**
     * Offset for sell button within a slot.
     */
    public static final int SLOT_OFFSET_SELL_BUTTON = 1;

    /**
     * Offset for item icon within a slot.
     */
    public static final int SLOT_OFFSET_ITEM_ICON = 2;

    /**
     * Offset for item name text within a slot.
     */
    public static final int SLOT_OFFSET_ITEM_NAME = 3;

    /**
     * Offset for progress bar within a slot.
     */
    public static final int SLOT_OFFSET_PROGRESS = 4;

    // ========================================================================
    // Search Interface
    // ========================================================================

    /**
     * Chat input group ID (for item search typing).
     */
    public static final int CHATBOX_GROUP_ID = 162;

    /**
     * Search results container (appears in chatbox area).
     */
    public static final int SEARCH_RESULTS_GROUP_ID = 162;

    // ========================================================================
    // NPCs and Objects
    // ========================================================================

    /**
     * Grand Exchange Clerk NPC ID.
     */
    public static final int GE_CLERK_NPC_ID = 2148;

    /**
     * Alternative GE Clerk NPC IDs.
     */
    public static final int[] GE_CLERK_NPC_IDS = {2148, 2149, 2150, 2151};

    /**
     * Grand Exchange Booth object ID.
     */
    public static final int GE_BOOTH_OBJECT_ID = 10060;

    /**
     * Alternative GE Booth object IDs.
     */
    public static final int[] GE_BOOTH_OBJECT_IDS = {10060, 10061, 30390};

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Get the slot child ID for a given slot index (0-7).
     *
     * @param slotIndex the slot index
     * @return the child widget ID
     * @throws IllegalArgumentException if slotIndex is out of range
     */
    public static int getSlotChildId(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= SLOT_CHILD_IDS.length) {
            throw new IllegalArgumentException("Invalid slot index: " + slotIndex);
        }
        return SLOT_CHILD_IDS[slotIndex];
    }

    /**
     * Get the packed widget ID for a GE main interface widget.
     *
     * @param childId the child widget ID
     * @return packed widget ID (group << 16 | child)
     */
    public static int pack(int childId) {
        return (GROUP_ID << 16) | childId;
    }

    /**
     * Get the packed widget ID for a GE inventory widget.
     *
     * @param childId the child widget ID
     * @return packed widget ID
     */
    public static int packInventory(int childId) {
        return (INVENTORY_GROUP_ID << 16) | childId;
    }

    /**
     * Check if the GE interface is open.
     *
     * @param client the game client
     * @return true if GE is visible
     */
    public static boolean isOpen(Client client) {
        Widget geWidget = client.getWidget(GROUP_ID, CHILD_UNIVERSE);
        return geWidget != null && !geWidget.isHidden();
    }

    /**
     * Check if the GE is in the main index view (showing all slots).
     *
     * @param client the game client
     * @return true if in index view
     */
    public static boolean isInIndexView(Client client) {
        Widget indexWidget = client.getWidget(GROUP_ID, CHILD_INDEX);
        return indexWidget != null && !indexWidget.isHidden();
    }

    /**
     * Check if the GE is in the setup view (creating an offer).
     *
     * @param client the game client
     * @return true if in setup view
     */
    public static boolean isInSetupView(Client client) {
        Widget setupWidget = client.getWidget(GROUP_ID, CHILD_SETUP);
        return setupWidget != null && !setupWidget.isHidden();
    }

    /**
     * Check if the GE is showing offer details.
     *
     * @param client the game client
     * @return true if showing details
     */
    public static boolean isInDetailsView(Client client) {
        Widget detailsWidget = client.getWidget(GROUP_ID, CHILD_DETAILS);
        return detailsWidget != null && !detailsWidget.isHidden();
    }

    /**
     * Get a slot widget by index.
     *
     * @param client    the game client
     * @param slotIndex the slot index (0-7)
     * @return the slot widget, or null if not found
     */
    @Nullable
    public static Widget getSlotWidget(Client client, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= SLOT_CHILD_IDS.length) {
            return null;
        }
        return client.getWidget(GROUP_ID, SLOT_CHILD_IDS[slotIndex]);
    }

    /**
     * Get the collect all button widget.
     *
     * @param client the game client
     * @return the collect all widget, or null if not found
     */
    @Nullable
    public static Widget getCollectAllWidget(Client client) {
        return client.getWidget(GROUP_ID, CHILD_COLLECT_ALL);
    }

    /**
     * Get the confirm button widget in setup view.
     *
     * @param client the game client
     * @return the confirm widget, or null if not found
     */
    @Nullable
    public static Widget getConfirmWidget(Client client) {
        return client.getWidget(GROUP_ID, CHILD_SETUP_CONFIRM);
    }

    /**
     * Get the back button widget.
     *
     * @param client the game client
     * @return the back widget, or null if not found
     */
    @Nullable
    public static Widget getBackWidget(Client client) {
        return client.getWidget(GROUP_ID, CHILD_BACK);
    }
}

