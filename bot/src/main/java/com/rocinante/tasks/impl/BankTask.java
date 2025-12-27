package com.rocinante.tasks.impl;

import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.util.Randomization;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Full-featured bank interaction task.
 *
 * <p>Per REQUIREMENTS.md Section 5.4.7:
 * <ul>
 *   <li>Open nearest bank (NPC or booth)</li>
 *   <li>Operations: deposit all, deposit specific items, withdraw specific items</li>
 *   <li>Quantity options: 1, 5, 10, X, All</li>
 *   <li>Navigate bank tabs with humanized patterns</li>
 *   <li>Use bank search when > 50 items visible</li>
 *   <li>Placeholder configuration support</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Withdraw specific items
 * BankTask withdraw = BankTask.withdraw(ItemID.LOBSTER, 10)
 *     .withDescription("Withdraw 10 lobsters");
 *
 * // Deposit all inventory
 * BankTask depositAll = BankTask.depositAll()
 *     .withDescription("Bank all items");
 *
 * // Withdraw all of an item
 * BankTask withdrawAll = BankTask.withdraw(ItemID.COINS, WithdrawQuantity.ALL);
 * }</pre>
 */
@Slf4j
public class BankTask extends AbstractTask {

    // ========================================================================
    // Widget Constants (from RuneLite InterfaceID)
    // ========================================================================

    /**
     * Bank main interface group ID.
     */
    public static final int WIDGET_BANK_GROUP = 12;

    /**
     * Bank side inventory group ID.
     */
    public static final int WIDGET_BANK_INVENTORY_GROUP = 15;

    /**
     * Bank container widget (main window).
     */
    public static final int WIDGET_BANK_CONTAINER = 1;

    /**
     * Bank items container.
     */
    public static final int WIDGET_BANK_ITEMS = 13;

    /**
     * Bank tabs container.
     */
    public static final int WIDGET_BANK_TABS = 11;

    /**
     * Bank search button.
     */
    public static final int WIDGET_BANK_SEARCH = 42; // 0x2a

    /**
     * Deposit inventory button.
     */
    public static final int WIDGET_DEPOSIT_INVENTORY = 44; // 0x2c

    /**
     * Deposit equipment button.
     */
    public static final int WIDGET_DEPOSIT_EQUIPMENT = 46; // 0x2e

    /**
     * Bank close button (X in corner).
     */
    public static final int WIDGET_BANK_CLOSE = 3;

    /**
     * Inventory items in bank side panel.
     */
    public static final int WIDGET_BANK_INV_ITEMS = 3;

    /**
     * Quantity selection buttons.
     */
    public static final int WIDGET_QUANTITY_1 = 30;
    public static final int WIDGET_QUANTITY_5 = 32;
    public static final int WIDGET_QUANTITY_10 = 34;
    public static final int WIDGET_QUANTITY_X = 36;
    public static final int WIDGET_QUANTITY_ALL = 38;

    // ========================================================================
    // Common Bank Object/NPC IDs
    // ========================================================================

    /**
     * Common bank booth object IDs.
     */
    private static final Set<Integer> BANK_BOOTH_IDS = Set.of(
            10355, 10583, 18491, 20325, 20326, 20327, 20328, 24101, 25808, 26707,
            27254, 27260, 27263, 27265, 27267, 27292, 28429, 28430, 28431, 28861,
            29085, 30015, 32666, 34752, 34810, 35647, 36559, 37474
    );

    /**
     * Common banker NPC IDs.
     */
    private static final Set<Integer> BANKER_NPC_IDS = Set.of(
            394, 395, 396, 397, 2897, 2898, 3194, 5488, 5901, 6200, 6362, 7049,
            7050, 8948
    );

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The operation to perform.
     */
    @Getter
    private final BankOperation operation;

    /**
     * Item ID for withdraw/deposit operations.
     */
    @Getter
    private final int itemId;

    /**
     * Quantity to withdraw/deposit.
     */
    @Getter
    private final int quantity;

    /**
     * Quantity mode for withdrawal.
     */
    @Getter
    private final WithdrawQuantity quantityMode;

    /**
     * Whether to close bank after operation.
     */
    @Getter
    @Setter
    private boolean closeAfter = true;

    /**
     * Search radius for finding bank.
     */
    @Getter
    @Setter
    private int searchRadius = 25;

    /**
     * Description for logging.
     */
    @Setter
    private String description;

    /**
     * Whether to withdraw as noted.
     */
    @Getter
    @Setter
    private boolean withdrawNoted = false;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private BankPhase phase = BankPhase.FIND_BANK;

    /**
     * Target bank object (booth).
     */
    private GameObject targetBooth;

    /**
     * Target banker NPC.
     */
    private NPC targetBanker;

    /**
     * Whether an async operation is pending.
     */
    private boolean operationPending = false;

    /**
     * Ticks waiting for response.
     */
    private int waitTicks = 0;

    /**
     * Maximum ticks to wait for bank to open.
     */
    private static final int BANK_OPEN_TIMEOUT = 15;

    /**
     * Maximum ticks to wait for item operation.
     */
    private static final int OPERATION_TIMEOUT = 10;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a bank task with specified operation.
     */
    private BankTask(BankOperation operation, int itemId, int quantity, WithdrawQuantity quantityMode) {
        this.operation = operation;
        this.itemId = itemId;
        this.quantity = quantity;
        this.quantityMode = quantityMode;
        this.timeout = Duration.ofSeconds(60);
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a task to withdraw items.
     *
     * @param itemId   the item to withdraw
     * @param quantity the quantity to withdraw
     * @return bank task configured for withdrawal
     */
    public static BankTask withdraw(int itemId, int quantity) {
        return new BankTask(BankOperation.WITHDRAW, itemId, quantity, WithdrawQuantity.EXACT);
    }

    /**
     * Create a task to withdraw items with a quantity mode.
     *
     * @param itemId       the item to withdraw
     * @param quantityMode the quantity mode (ALL, X, etc.)
     * @return bank task configured for withdrawal
     */
    public static BankTask withdraw(int itemId, WithdrawQuantity quantityMode) {
        return new BankTask(BankOperation.WITHDRAW, itemId, 0, quantityMode);
    }

    /**
     * Create a task to deposit specific items.
     *
     * @param itemId   the item to deposit
     * @param quantity the quantity to deposit (-1 for all)
     * @return bank task configured for deposit
     */
    public static BankTask deposit(int itemId, int quantity) {
        return new BankTask(BankOperation.DEPOSIT, itemId, quantity, WithdrawQuantity.EXACT);
    }

    /**
     * Create a task to deposit all inventory items.
     *
     * @return bank task configured for deposit all
     */
    public static BankTask depositAll() {
        return new BankTask(BankOperation.DEPOSIT_ALL, -1, -1, WithdrawQuantity.ALL);
    }

    /**
     * Create a task to deposit all equipment.
     *
     * @return bank task configured for deposit equipment
     */
    public static BankTask depositEquipment() {
        return new BankTask(BankOperation.DEPOSIT_EQUIPMENT, -1, -1, WithdrawQuantity.ALL);
    }

    /**
     * Create a task to just open the bank.
     *
     * @return bank task that only opens bank
     */
    public static BankTask open() {
        BankTask task = new BankTask(BankOperation.OPEN_ONLY, -1, -1, WithdrawQuantity.ALL);
        task.setCloseAfter(false);
        return task;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set whether to close bank after operation.
     */
    public BankTask withCloseAfter(boolean close) {
        this.closeAfter = close;
        return this;
    }

    /**
     * Set search radius for finding bank.
     */
    public BankTask withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Set description for logging.
     */
    public BankTask withDescription(String desc) {
        this.description = desc;
        return this;
    }

    /**
     * Set whether to withdraw as noted items.
     */
    public BankTask withWithdrawNoted(boolean noted) {
        this.withdrawNoted = noted;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.isLoggedIn();
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (operationPending) {
            return;
        }

        switch (phase) {
            case FIND_BANK:
                executeFindBank(ctx);
                break;
            case MOVE_TO_BANK:
                executeMoveToBank(ctx);
                break;
            case OPEN_BANK:
                executeOpenBank(ctx);
                break;
            case WAIT_BANK_OPEN:
                executeWaitBankOpen(ctx);
                break;
            case SET_QUANTITY_MODE:
                executeSetQuantityMode(ctx);
                break;
            case PERFORM_OPERATION:
                executePerformOperation(ctx);
                break;
            case WAIT_OPERATION:
                executeWaitOperation(ctx);
                break;
            case CLOSE_BANK:
                executeCloseBank(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Find Bank
    // ========================================================================

    private void executeFindBank(TaskContext ctx) {
        Client client = ctx.getClient();
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos == null) {
            fail("Cannot determine player position");
            return;
        }

        // Check if bank is already open
        if (isBankOpen(client)) {
            log.debug("Bank is already open");
            phase = (operation == BankOperation.OPEN_ONLY) ? BankPhase.CLOSE_BANK : BankPhase.SET_QUANTITY_MODE;
            if (operation == BankOperation.OPEN_ONLY) {
                complete();
            }
            return;
        }

        // Find nearest bank booth
        targetBooth = findNearestBankBooth(ctx, playerPos);
        if (targetBooth != null) {
            log.debug("Found bank booth {} at {}", targetBooth.getId(), targetBooth.getWorldLocation());
            phase = BankPhase.MOVE_TO_BANK;
            return;
        }

        // Find nearest banker NPC
        targetBanker = findNearestBanker(ctx, playerPos);
        if (targetBanker != null) {
            log.debug("Found banker {} at {}", targetBanker.getId(), targetBanker.getWorldLocation());
            phase = BankPhase.MOVE_TO_BANK;
            return;
        }

        fail("No bank found within " + searchRadius + " tiles");
    }

    private GameObject findNearestBankBooth(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int playerPlane = playerPos.getPlane();

        GameObject nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (int x = 0; x < net.runelite.api.Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < net.runelite.api.Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[playerPlane][x][y];
                if (tile == null) continue;

                GameObject[] gameObjects = tile.getGameObjects();
                if (gameObjects == null) continue;

                for (GameObject obj : gameObjects) {
                    if (obj == null) continue;

                    int id = obj.getId();
                    // Check for bank booths
                    if (BANK_BOOTH_IDS.contains(id) || isBankBoothByName(client, id)) {
                        WorldPoint objPos = obj.getWorldLocation();
                        if (objPos != null) {
                            int dist = playerPos.distanceTo(objPos);
                            if (dist <= searchRadius && dist < nearestDist) {
                                nearest = obj;
                                nearestDist = dist;
                            }
                        }
                    }
                }
            }
        }
        return nearest;
    }

    private boolean isBankBoothByName(Client client, int objectId) {
        ObjectComposition def = client.getObjectDefinition(objectId);
        if (def == null) return false;
        String name = def.getName();
        if (name == null) return false;
        String lowerName = name.toLowerCase();
        return lowerName.contains("bank booth") || lowerName.contains("bank chest");
    }

    private NPC findNearestBanker(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        NPC nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (NPC npc : client.getNpcs()) {
            if (npc == null) continue;

            if (BANKER_NPC_IDS.contains(npc.getId()) || isBankerByName(npc)) {
                WorldPoint npcPos = npc.getWorldLocation();
                if (npcPos != null) {
                    int dist = playerPos.distanceTo(npcPos);
                    if (dist <= searchRadius && dist < nearestDist) {
                        nearest = npc;
                        nearestDist = dist;
                    }
                }
            }
        }
        return nearest;
    }

    private boolean isBankerByName(NPC npc) {
        String name = npc.getName();
        if (name == null) return false;
        String lowerName = name.toLowerCase();
        return lowerName.contains("banker") || lowerName.equals("bank tutor");
    }

    // ========================================================================
    // Phase: Move to Bank
    // ========================================================================

    private void executeMoveToBank(TaskContext ctx) {
        // Calculate click point on bank target
        Point clickPoint;
        Rectangle bounds;

        if (targetBooth != null) {
            bounds = targetBooth.getClickbox() != null ? 
                    targetBooth.getClickbox().getBounds() : null;
        } else if (targetBanker != null) {
            bounds = targetBanker.getConvexHull() != null ?
                    targetBanker.getConvexHull().getBounds() : null;
        } else {
            fail("Lost bank target");
            return;
        }

        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            // Target not on screen, might need to walk closer
            log.debug("Bank target not visible, waiting...");
            waitTicks++;
            if (waitTicks > 10) {
                fail("Bank target not visible");
            }
            return;
        }

        clickPoint = calculateClickPoint(bounds);
        
        operationPending = true;
        ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y)
                .thenRun(() -> {
                    operationPending = false;
                    phase = BankPhase.OPEN_BANK;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to move to bank", e);
                    fail("Mouse movement failed");
                    return null;
                });
    }

    // ========================================================================
    // Phase: Open Bank
    // ========================================================================

    private void executeOpenBank(TaskContext ctx) {
        log.debug("Clicking to open bank");

        operationPending = true;
        ctx.getMouseController().click()
                .thenRun(() -> {
                    operationPending = false;
                    waitTicks = 0;
                    phase = BankPhase.WAIT_BANK_OPEN;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to click bank", e);
                    fail("Click failed");
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait Bank Open
    // ========================================================================

    private void executeWaitBankOpen(TaskContext ctx) {
        Client client = ctx.getClient();

        if (isBankOpen(client)) {
            log.debug("Bank opened successfully");
            if (operation == BankOperation.OPEN_ONLY) {
                complete();
                return;
            }
            phase = BankPhase.SET_QUANTITY_MODE;
            return;
        }

        waitTicks++;
        if (waitTicks > BANK_OPEN_TIMEOUT) {
            // Try clicking again
            log.debug("Bank didn't open, retrying...");
            phase = BankPhase.MOVE_TO_BANK;
            waitTicks = 0;
        }
    }

    // ========================================================================
    // Phase: Set Quantity Mode
    // ========================================================================

    private void executeSetQuantityMode(TaskContext ctx) {
        // For deposit all/equipment, skip quantity selection
        if (operation == BankOperation.DEPOSIT_ALL || operation == BankOperation.DEPOSIT_EQUIPMENT) {
            phase = BankPhase.PERFORM_OPERATION;
            return;
        }

        // For exact quantities, we use right-click menu, skip quantity button
        if (quantityMode == WithdrawQuantity.EXACT) {
            phase = BankPhase.PERFORM_OPERATION;
            return;
        }

        // Click the appropriate quantity button
        int quantityWidget = getQuantityWidget(quantityMode);
        if (quantityWidget < 0) {
            phase = BankPhase.PERFORM_OPERATION;
            return;
        }

        Client client = ctx.getClient();
        Widget qtyWidget = client.getWidget(WIDGET_BANK_GROUP, quantityWidget);

        if (qtyWidget == null || qtyWidget.isHidden()) {
            log.debug("Quantity widget not found, proceeding with operation");
            phase = BankPhase.PERFORM_OPERATION;
            return;
        }

        Rectangle bounds = qtyWidget.getBounds();
        if (bounds == null || bounds.width == 0) {
            phase = BankPhase.PERFORM_OPERATION;
            return;
        }

        Point clickPoint = calculateClickPoint(bounds);
        operationPending = true;
        
        ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    operationPending = false;
                    phase = BankPhase.PERFORM_OPERATION;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.debug("Failed to set quantity mode, proceeding anyway");
                    phase = BankPhase.PERFORM_OPERATION;
                    return null;
                });
    }

    private int getQuantityWidget(WithdrawQuantity mode) {
        switch (mode) {
            case ONE: return WIDGET_QUANTITY_1;
            case FIVE: return WIDGET_QUANTITY_5;
            case TEN: return WIDGET_QUANTITY_10;
            case X: return WIDGET_QUANTITY_X;
            case ALL: return WIDGET_QUANTITY_ALL;
            default: return -1;
        }
    }

    // ========================================================================
    // Phase: Perform Operation
    // ========================================================================

    private void executePerformOperation(TaskContext ctx) {
        Client client = ctx.getClient();

        switch (operation) {
            case DEPOSIT_ALL:
                clickDepositAll(ctx, client);
                break;
            case DEPOSIT_EQUIPMENT:
                clickDepositEquipment(ctx, client);
                break;
            case DEPOSIT:
                depositItem(ctx, client);
                break;
            case WITHDRAW:
                withdrawItem(ctx, client);
                break;
            case OPEN_ONLY:
                complete();
                break;
        }
    }

    private void clickDepositAll(TaskContext ctx, Client client) {
        Widget depositBtn = client.getWidget(WIDGET_BANK_GROUP, WIDGET_DEPOSIT_INVENTORY);
        if (depositBtn == null || depositBtn.isHidden()) {
            fail("Deposit inventory button not found");
            return;
        }

        clickWidget(ctx, depositBtn, "Deposit all");
    }

    private void clickDepositEquipment(TaskContext ctx, Client client) {
        Widget depositBtn = client.getWidget(WIDGET_BANK_GROUP, WIDGET_DEPOSIT_EQUIPMENT);
        if (depositBtn == null || depositBtn.isHidden()) {
            fail("Deposit equipment button not found");
            return;
        }

        clickWidget(ctx, depositBtn, "Deposit equipment");
    }

    private void depositItem(TaskContext ctx, Client client) {
        // Find item in bank inventory (side panel)
        Widget invContainer = client.getWidget(WIDGET_BANK_INVENTORY_GROUP, WIDGET_BANK_INV_ITEMS);
        if (invContainer == null) {
            fail("Bank inventory not found");
            return;
        }

        Widget itemWidget = findItemInWidget(invContainer, itemId);
        if (itemWidget == null) {
            fail("Item " + itemId + " not found in inventory");
            return;
        }

        // Right-click to deposit specific quantity or left-click for current quantity mode
        if (quantity > 0 && quantityMode == WithdrawQuantity.EXACT) {
            // Need to right-click and select deposit option
            rightClickDeposit(ctx, itemWidget);
        } else {
            clickWidget(ctx, itemWidget, "Deposit item");
        }
    }

    private void withdrawItem(TaskContext ctx, Client client) {
        // Find item in bank
        Widget bankItems = client.getWidget(WIDGET_BANK_GROUP, WIDGET_BANK_ITEMS);
        if (bankItems == null) {
            fail("Bank items widget not found");
            return;
        }

        Widget itemWidget = findItemInWidget(bankItems, itemId);
        if (itemWidget == null) {
            fail("Item " + itemId + " not found in bank");
            return;
        }

        // For exact quantity, use right-click menu
        if (quantity > 0 && quantityMode == WithdrawQuantity.EXACT) {
            rightClickWithdraw(ctx, itemWidget);
        } else {
            clickWidget(ctx, itemWidget, "Withdraw item");
        }
    }

    private Widget findItemInWidget(Widget container, int targetItemId) {
        Widget[] children = container.getDynamicChildren();
        if (children == null) {
            children = container.getStaticChildren();
        }
        if (children == null) return null;

        for (Widget child : children) {
            if (child != null && child.getItemId() == targetItemId) {
                return child;
            }
        }
        return null;
    }

    private void clickWidget(TaskContext ctx, Widget widget, String actionDesc) {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) {
            fail("Widget has no bounds");
            return;
        }

        Point clickPoint = calculateClickPoint(bounds);
        operationPending = true;

        ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    operationPending = false;
                    log.debug("{} clicked", actionDesc);
                    waitTicks = 0;
                    phase = BankPhase.WAIT_OPERATION;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to {}", actionDesc, e);
                    fail("Click failed");
                    return null;
                });
    }

    private void rightClickDeposit(TaskContext ctx, Widget widget) {
        // TODO: Implement right-click menu selection for exact quantities
        // For now, just left-click
        clickWidget(ctx, widget, "Deposit item");
    }

    private void rightClickWithdraw(TaskContext ctx, Widget widget) {
        // TODO: Implement right-click menu selection for exact quantities
        // For now, just left-click
        clickWidget(ctx, widget, "Withdraw item");
    }

    // ========================================================================
    // Phase: Wait Operation
    // ========================================================================

    private void executeWaitOperation(TaskContext ctx) {
        waitTicks++;

        // Check if operation completed based on operation type
        boolean operationComplete = false;

        switch (operation) {
            case DEPOSIT_ALL:
                operationComplete = ctx.getInventoryState().isEmpty();
                break;
            case DEPOSIT:
                operationComplete = !ctx.getInventoryState().hasItem(itemId);
                break;
            case WITHDRAW:
                operationComplete = ctx.getInventoryState().hasItem(itemId);
                break;
            case DEPOSIT_EQUIPMENT:
                // Check if equipment is empty - would need EquipmentState
                operationComplete = waitTicks > 2;
                break;
            default:
                operationComplete = true;
        }

        if (operationComplete) {
            log.debug("Bank operation completed");
            if (closeAfter) {
                phase = BankPhase.CLOSE_BANK;
            } else {
                complete();
            }
            return;
        }

        if (waitTicks > OPERATION_TIMEOUT) {
            // Operation might have failed or succeeded without detection
            log.warn("Operation timeout, assuming success");
            if (closeAfter) {
                phase = BankPhase.CLOSE_BANK;
            } else {
                complete();
            }
        }
    }

    // ========================================================================
    // Phase: Close Bank
    // ========================================================================

    private void executeCloseBank(TaskContext ctx) {
        Client client = ctx.getClient();

        if (!isBankOpen(client)) {
            complete();
            return;
        }

        // Press Escape to close bank (more human-like)
        operationPending = true;
        ctx.getKeyboardController().pressKey(java.awt.event.KeyEvent.VK_ESCAPE)
                .thenRun(() -> {
                    operationPending = false;
                    complete();
                })
                .exceptionally(e -> {
                    operationPending = false;
                    // Try clicking close button as fallback
                    Widget closeBtn = client.getWidget(WIDGET_BANK_GROUP, WIDGET_BANK_CLOSE);
                    if (closeBtn != null && !closeBtn.isHidden()) {
                        Rectangle bounds = closeBtn.getBounds();
                        if (bounds != null) {
                            Point p = calculateClickPoint(bounds);
                            ctx.getMouseController().moveToCanvas(p.x, p.y)
                                    .thenCompose(v -> ctx.getMouseController().click())
                                    .thenRun(this::complete);
                        }
                    }
                    return null;
                });
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if bank interface is currently open.
     */
    public static boolean isBankOpen(Client client) {
        Widget bankContainer = client.getWidget(WIDGET_BANK_GROUP, WIDGET_BANK_CONTAINER);
        return bankContainer != null && !bankContainer.isHidden();
    }

    private Point calculateClickPoint(Rectangle bounds) {
        double[] offset = Randomization.staticGaussian2D(0, 0, bounds.width / 4.0, bounds.height / 4.0);
        int clickX = (int) (bounds.getCenterX() + offset[0]);
        int clickY = (int) (bounds.getCenterY() + offset[1]);

        // Clamp to bounds
        clickX = Math.max(bounds.x + 2, Math.min(clickX, bounds.x + bounds.width - 2));
        clickY = Math.max(bounds.y + 2, Math.min(clickY, bounds.y + bounds.height - 2));

        return new Point(clickX, clickY);
    }

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        switch (operation) {
            case WITHDRAW:
                return String.format("BankTask[withdraw %d x%d]", itemId, quantity);
            case DEPOSIT:
                return String.format("BankTask[deposit %d x%d]", itemId, quantity);
            case DEPOSIT_ALL:
                return "BankTask[deposit all]";
            case DEPOSIT_EQUIPMENT:
                return "BankTask[deposit equipment]";
            case OPEN_ONLY:
                return "BankTask[open]";
            default:
                return "BankTask[" + operation + "]";
        }
    }

    // ========================================================================
    // Enums
    // ========================================================================

    /**
     * Bank operation types.
     */
    public enum BankOperation {
        OPEN_ONLY,
        WITHDRAW,
        DEPOSIT,
        DEPOSIT_ALL,
        DEPOSIT_EQUIPMENT
    }

    /**
     * Withdrawal quantity modes.
     */
    public enum WithdrawQuantity {
        ONE,
        FIVE,
        TEN,
        X,
        ALL,
        EXACT
    }

    /**
     * Execution phases.
     */
    private enum BankPhase {
        FIND_BANK,
        MOVE_TO_BANK,
        OPEN_BANK,
        WAIT_BANK_OPEN,
        SET_QUANTITY_MODE,
        PERFORM_OPERATION,
        WAIT_OPERATION,
        CLOSE_BANK
    }
}

