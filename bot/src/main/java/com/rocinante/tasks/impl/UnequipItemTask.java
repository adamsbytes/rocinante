package com.rocinante.tasks.impl;

import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.widgets.Widget;

import java.awt.Rectangle;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task for unequipping an item from a specific equipment slot.
 *
 * <p>Uses the equipment tab widget to unequip items.
 */
@Slf4j
public class UnequipItemTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Equipment widget group.
     * Verified in RuneLite source: InterfaceID.EQUIPMENT = 387
     */
    private static final int EQUIPMENT_GROUP = 387;

    /**
     * Equipment slot to widget child ID mapping.
     * Verified against RuneLite source (net.runelite.api.widgets.WidgetInfo).
     */
    private static final int[] SLOT_WIDGETS = new int[14];
    static {
        // Mapping from EquipmentInventorySlot index to Widget child ID
        // Based on WidgetInfo.EQUIPMENT_*
        SLOT_WIDGETS[EquipmentState.SLOT_HEAD] = 6;      // EQUIPMENT_HELMET
        SLOT_WIDGETS[EquipmentState.SLOT_CAPE] = 7;      // EQUIPMENT_CAPE
        SLOT_WIDGETS[EquipmentState.SLOT_AMULET] = 8;    // EQUIPMENT_AMULET
        SLOT_WIDGETS[EquipmentState.SLOT_WEAPON] = 9;    // EQUIPMENT_WEAPON
        SLOT_WIDGETS[EquipmentState.SLOT_BODY] = 10;     // EQUIPMENT_BODY
        SLOT_WIDGETS[EquipmentState.SLOT_SHIELD] = 11;   // EQUIPMENT_SHIELD
        SLOT_WIDGETS[EquipmentState.SLOT_LEGS] = 12;     // EQUIPMENT_LEGS
        SLOT_WIDGETS[EquipmentState.SLOT_GLOVES] = 13;   // EQUIPMENT_GLOVES
        SLOT_WIDGETS[EquipmentState.SLOT_BOOTS] = 14;    // EQUIPMENT_BOOTS
        SLOT_WIDGETS[EquipmentState.SLOT_RING] = 15;     // EQUIPMENT_RING
        SLOT_WIDGETS[EquipmentState.SLOT_AMMO] = 16;     // EQUIPMENT_AMMO
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The equipment slot to unequip from.
     */
    @Getter
    private final int slot;

    /**
     * The item ID expected to be in the slot (optional, -1 to ignore).
     */
    @Getter
    private final int expectedItemId;

    // ========================================================================
    // State
    // ========================================================================

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<CompletableFuture<Boolean>> pendingOperation = new AtomicReference<>();

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a task to unequip whatever is in the specified slot.
     *
     * @param slot the equipment slot index (see EquipmentState)
     */
    public UnequipItemTask(int slot) {
        this(slot, -1);
    }

    /**
     * Create a task to unequip a specific item from a slot.
     *
     * @param slot the equipment slot index
     * @param expectedItemId the item ID expected to be there
     */
    public UnequipItemTask(int slot, int expectedItemId) {
        this.slot = slot;
        this.expectedItemId = expectedItemId;
        this.timeout = Duration.ofSeconds(5);
    }

    /**
     * Create a task to unequip a specific item by ID.
     * Finds the slot automatically.
     *
     * @param itemId the item ID to unequip
     * @return the task, or fails execution if item not equipped
     */
    public static UnequipItemTask forItem(int itemId) {
        // Slot will be resolved in executeImpl
        return new UnequipItemTask(-1, itemId);
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (!ctx.isLoggedIn()) {
            return false;
        }

        EquipmentState equipment = ctx.getEquipmentState();
        InventoryState inventory = ctx.getInventoryState();

        if (inventory.isFull()) {
            log.warn("Cannot unequip item: inventory is full");
            return false;
        }

        if (slot != -1) {
            // Check specific slot
            return equipment.getEquippedItem(slot).isPresent();
        } else if (expectedItemId != -1) {
            // Check by item ID
            return equipment.hasEquipped(expectedItemId);
        }

        return false;
    }

    @Override
    public String getDescription() {
        return "Unequip " + (expectedItemId != -1 ? "item " + expectedItemId : "slot " + slot);
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        // Resolve slot if needed
        int targetSlot = this.slot;
        if (targetSlot == -1 && expectedItemId != -1) {
            EquipmentState equipment = ctx.getEquipmentState();
            var slotOpt = equipment.getSlotOf(expectedItemId);
            if (slotOpt.isEmpty()) {
                log.debug("Item {} not equipped, nothing to unequip", expectedItemId);
                complete();
                return;
            }
            targetSlot = slotOpt.get();
        }

        if (targetSlot < 0 || targetSlot >= SLOT_WIDGETS.length) {
            fail("Invalid equipment slot: " + targetSlot);
            return;
        }

        // Capture for lambda
        final int slotToUnequip = targetSlot;

        // Check if already empty
        EquipmentState equipment = ctx.getEquipmentState();
        if (equipment.getEquippedItem(slotToUnequip).isEmpty()) {
            log.debug("Slot {} already empty", slotToUnequip);
            complete();
            return;
        }

        // Start operation
        if (started.compareAndSet(false, true)) {
            // Open equipment tab
            com.rocinante.util.WidgetInteractionHelpers.openTabAsync(ctx,
                    com.rocinante.util.WidgetInteractionHelpers.TAB_EQUIPMENT, null)
                    .thenCompose(v -> {
                        // Click slot widget
                        int childId = SLOT_WIDGETS[slotToUnequip];
                        Widget widget = ctx.getClient().getWidget(EQUIPMENT_GROUP, childId);
                        
                        if (widget == null || widget.isHidden()) {
                            throw new RuntimeException("Equipment slot widget not visible");
                        }

                        Rectangle bounds = widget.getBounds();
                        if (bounds == null) {
                            throw new RuntimeException("Widget bounds null");
                        }

                        // Use centralized click calculator
                        java.awt.Point clickPoint = com.rocinante.input.ClickPointCalculator.getGaussianClickPoint(bounds);
                        
                        log.debug("Clicking unequip slot {} at {}", slotToUnequip, clickPoint);
                        return ctx.getMouseController().clickAt(clickPoint.x, clickPoint.y);
                    })
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.error("Unequip failed", error);
                            fail("Unequip failed: " + error.getMessage());
                        } else {
                            // Completion checked on next tick
                        }
                    });
        }

        // Check completion
        if (started.get()) {
            // Wait for item to be removed from slot
            if (ctx.getEquipmentState().getEquippedItem(slotToUnequip).isEmpty()) {
                complete();
            }
        }
    }
}

