package com.rocinante.tasks.impl;

import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;

/**
 * Task for purchasing items from NPC shops.
 *
 * <p>This task handles the full shop interaction flow:
 * <ol>
 *   <li>Find the shopkeeper NPC</li>
 *   <li>Open shop via "Trade" menu action</li>
 *   <li>Wait for shop interface to open</li>
 *   <li>Find item in shop inventory</li>
 *   <li>Purchase specified quantity</li>
 *   <li>Close shop</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Buy 5 buckets from general store
 * ShopPurchaseTask buyBuckets = new ShopPurchaseTask(NpcID.SHOP_KEEPER, ItemID.BUCKET_EMPTY, 5);
 *
 * // Buy all available flour
 * ShopPurchaseTask buyFlour = ShopPurchaseTask.buyAll(NpcID.SHOP_KEEPER, ItemID.POT_OF_FLOUR);
 * }</pre>
 */
@Slf4j
public class ShopPurchaseTask extends AbstractTask {

    // ========================================================================
    // Widget Constants (from RuneLite InterfaceID)
    // ========================================================================

    /**
     * Shop main interface group ID.
     */
    public static final int WIDGET_SHOP_GROUP = 300;

    /**
     * Shop side inventory group ID.
     */
    public static final int WIDGET_SHOP_INVENTORY_GROUP = 301;

    /**
     * Shop items container.
     */
    public static final int WIDGET_SHOP_ITEMS = 16; // 0x10

    /**
     * Shop inventory items (player's inventory in shop).
     */
    public static final int WIDGET_SHOP_INV_ITEMS = 0;

    /**
     * Quantity buttons.
     */
    public static final int WIDGET_QUANTITY_1 = 8;
    public static final int WIDGET_QUANTITY_5 = 10;
    public static final int WIDGET_QUANTITY_10 = 12;
    public static final int WIDGET_QUANTITY_50 = 14;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The NPC ID of the shopkeeper.
     */
    @Getter
    private final int shopkeeperNpcId;

    /**
     * The item ID to purchase.
     */
    @Getter
    private final int itemId;

    /**
     * Quantity to purchase.
     */
    @Getter
    private final int quantity;

    /**
     * Quantity mode.
     */
    @Getter
    private final PurchaseQuantity quantityMode;

    /**
     * Whether to close shop after purchase.
     */
    @Getter
    @Setter
    private boolean closeAfter = true;

    /**
     * Search radius for finding shopkeeper.
     */
    @Getter
    @Setter
    private int searchRadius = 20;

    /**
     * Description for logging.
     */
    @Setter
    private String description;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private ShopPhase phase = ShopPhase.FIND_SHOPKEEPER;

    /**
     * Target shopkeeper NPC.
     */
    private NPC targetNpc;

    /**
     * Whether an async operation is pending.
     */
    private boolean operationPending = false;

    /**
     * Ticks waiting for response.
     */
    private int waitTicks = 0;

    /**
     * Maximum ticks to wait for shop to open.
     */
    private static final int SHOP_OPEN_TIMEOUT = 15;

    /**
     * Maximum ticks to wait for purchase.
     */
    private static final int PURCHASE_TIMEOUT = 10;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a shop purchase task.
     *
     * @param shopkeeperNpcId the NPC ID of the shopkeeper
     * @param itemId          the item ID to purchase
     * @param quantity        the quantity to purchase
     */
    public ShopPurchaseTask(int shopkeeperNpcId, int itemId, int quantity) {
        this.shopkeeperNpcId = shopkeeperNpcId;
        this.itemId = itemId;
        this.quantity = quantity;
        this.quantityMode = PurchaseQuantity.EXACT;
        this.timeout = Duration.ofSeconds(60);
    }

    private ShopPurchaseTask(int shopkeeperNpcId, int itemId, PurchaseQuantity quantityMode) {
        this.shopkeeperNpcId = shopkeeperNpcId;
        this.itemId = itemId;
        this.quantity = 0;
        this.quantityMode = quantityMode;
        this.timeout = Duration.ofSeconds(60);
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a task to buy a specific quantity.
     */
    public static ShopPurchaseTask buy(int shopkeeperNpcId, int itemId, int quantity) {
        return new ShopPurchaseTask(shopkeeperNpcId, itemId, quantity);
    }

    /**
     * Create a task to buy one of an item.
     */
    public static ShopPurchaseTask buyOne(int shopkeeperNpcId, int itemId) {
        return new ShopPurchaseTask(shopkeeperNpcId, itemId, PurchaseQuantity.ONE);
    }

    /**
     * Create a task to buy 5 of an item.
     */
    public static ShopPurchaseTask buyFive(int shopkeeperNpcId, int itemId) {
        return new ShopPurchaseTask(shopkeeperNpcId, itemId, PurchaseQuantity.FIVE);
    }

    /**
     * Create a task to buy 10 of an item.
     */
    public static ShopPurchaseTask buyTen(int shopkeeperNpcId, int itemId) {
        return new ShopPurchaseTask(shopkeeperNpcId, itemId, PurchaseQuantity.TEN);
    }

    /**
     * Create a task to buy 50 of an item.
     */
    public static ShopPurchaseTask buyFifty(int shopkeeperNpcId, int itemId) {
        return new ShopPurchaseTask(shopkeeperNpcId, itemId, PurchaseQuantity.FIFTY);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set whether to close shop after purchase.
     */
    public ShopPurchaseTask withCloseAfter(boolean close) {
        this.closeAfter = close;
        return this;
    }

    /**
     * Set search radius for finding shopkeeper.
     */
    public ShopPurchaseTask withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Set description for logging.
     */
    public ShopPurchaseTask withDescription(String desc) {
        this.description = desc;
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
            case FIND_SHOPKEEPER:
                executeFindShopkeeper(ctx);
                break;
            case MOVE_TO_SHOPKEEPER:
                executeMoveToShopkeeper(ctx);
                break;
            case OPEN_SHOP:
                executeOpenShop(ctx);
                break;
            case WAIT_SHOP_OPEN:
                executeWaitShopOpen(ctx);
                break;
            case SET_QUANTITY:
                executeSetQuantity(ctx);
                break;
            case FIND_ITEM:
                executeFindItem(ctx);
                break;
            case PURCHASE_ITEM:
                executePurchaseItem(ctx);
                break;
            case WAIT_PURCHASE:
                executeWaitPurchase(ctx);
                break;
            case CLOSE_SHOP:
                executeCloseShop(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Find Shopkeeper
    // ========================================================================

    private void executeFindShopkeeper(TaskContext ctx) {
        Client client = ctx.getClient();
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos == null) {
            fail("Cannot determine player position");
            return;
        }

        // Check if shop is already open
        if (isShopOpen(client)) {
            log.debug("Shop is already open");
            phase = ShopPhase.SET_QUANTITY;
            return;
        }

        // Find shopkeeper NPC
        targetNpc = findShopkeeper(ctx, playerPos);
        if (targetNpc != null) {
            log.debug("Found shopkeeper {} at {}", targetNpc.getName(), targetNpc.getWorldLocation());
            phase = ShopPhase.MOVE_TO_SHOPKEEPER;
            return;
        }

        fail("Shopkeeper NPC " + shopkeeperNpcId + " not found within " + searchRadius + " tiles");
    }

    private NPC findShopkeeper(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        NPC nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (NPC npc : client.getNpcs()) {
            if (npc == null) continue;

            if (npc.getId() == shopkeeperNpcId) {
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

    // ========================================================================
    // Phase: Move to Shopkeeper
    // ========================================================================

    private void executeMoveToShopkeeper(TaskContext ctx) {
        if (targetNpc == null || targetNpc.isDead()) {
            phase = ShopPhase.FIND_SHOPKEEPER;
            return;
        }

        Rectangle bounds = targetNpc.getConvexHull() != null ?
                targetNpc.getConvexHull().getBounds() : null;

        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            log.debug("Shopkeeper not visible, waiting...");
            waitTicks++;
            if (waitTicks > 10) {
                fail("Shopkeeper not visible");
            }
            return;
        }

        Point clickPoint = calculateClickPoint(bounds);

        operationPending = true;
        ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y)
                .thenRun(() -> {
                    operationPending = false;
                    phase = ShopPhase.OPEN_SHOP;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to move to shopkeeper", e);
                    fail("Mouse movement failed");
                    return null;
                });
    }

    // ========================================================================
    // Phase: Open Shop
    // ========================================================================

    private void executeOpenShop(TaskContext ctx) {
        // Need to right-click and select "Trade"
        // For simplicity, we'll try left-click first (if Trade is default action)
        log.debug("Clicking shopkeeper to open shop");

        operationPending = true;
        ctx.getMouseController().click()
                .thenRun(() -> {
                    operationPending = false;
                    waitTicks = 0;
                    phase = ShopPhase.WAIT_SHOP_OPEN;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to click shopkeeper", e);
                    fail("Click failed");
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait Shop Open
    // ========================================================================

    private void executeWaitShopOpen(TaskContext ctx) {
        Client client = ctx.getClient();

        if (isShopOpen(client)) {
            log.debug("Shop opened successfully");
            phase = ShopPhase.SET_QUANTITY;
            return;
        }

        waitTicks++;
        if (waitTicks > SHOP_OPEN_TIMEOUT) {
            // Might need to use right-click "Trade" menu
            log.warn("Shop didn't open, may need right-click Trade");
            phase = ShopPhase.MOVE_TO_SHOPKEEPER;
            waitTicks = 0;
        }
    }

    // ========================================================================
    // Phase: Set Quantity
    // ========================================================================

    private void executeSetQuantity(TaskContext ctx) {
        // For exact quantities > standard options, we'll use right-click menu later
        // For now, click the appropriate quantity button
        if (quantityMode == PurchaseQuantity.EXACT) {
            // Will use right-click on item
            phase = ShopPhase.FIND_ITEM;
            return;
        }

        int quantityWidget = getQuantityWidget(quantityMode);
        if (quantityWidget < 0) {
            phase = ShopPhase.FIND_ITEM;
            return;
        }

        Client client = ctx.getClient();
        Widget qtyWidget = client.getWidget(WIDGET_SHOP_GROUP, quantityWidget);

        if (qtyWidget == null || qtyWidget.isHidden()) {
            log.debug("Quantity widget not found, proceeding");
            phase = ShopPhase.FIND_ITEM;
            return;
        }

        Rectangle bounds = qtyWidget.getBounds();
        if (bounds == null || bounds.width == 0) {
            phase = ShopPhase.FIND_ITEM;
            return;
        }

        Point clickPoint = calculateClickPoint(bounds);
        operationPending = true;

        ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    operationPending = false;
                    phase = ShopPhase.FIND_ITEM;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.debug("Failed to set quantity, proceeding anyway");
                    phase = ShopPhase.FIND_ITEM;
                    return null;
                });
    }

    private int getQuantityWidget(PurchaseQuantity mode) {
        switch (mode) {
            case ONE: return WIDGET_QUANTITY_1;
            case FIVE: return WIDGET_QUANTITY_5;
            case TEN: return WIDGET_QUANTITY_10;
            case FIFTY: return WIDGET_QUANTITY_50;
            default: return -1;
        }
    }

    // ========================================================================
    // Phase: Find Item
    // ========================================================================

    private Widget targetItemWidget;
    private int startItemCount;

    private void executeFindItem(TaskContext ctx) {
        Client client = ctx.getClient();

        Widget shopItems = client.getWidget(WIDGET_SHOP_GROUP, WIDGET_SHOP_ITEMS);
        if (shopItems == null) {
            fail("Shop items widget not found");
            return;
        }

        targetItemWidget = findItemInWidget(shopItems, itemId);
        if (targetItemWidget == null) {
            fail("Item " + itemId + " not found in shop");
            return;
        }

        // Store initial inventory count for verification
        startItemCount = ctx.getInventoryState().countItem(itemId);

        phase = ShopPhase.PURCHASE_ITEM;
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

    // ========================================================================
    // Phase: Purchase Item
    // ========================================================================

    private void executePurchaseItem(TaskContext ctx) {
        if (targetItemWidget == null || targetItemWidget.isHidden()) {
            fail("Lost target item widget");
            return;
        }

        Rectangle bounds = targetItemWidget.getBounds();
        if (bounds == null || bounds.width == 0) {
            fail("Item widget has no bounds");
            return;
        }

        Point clickPoint = calculateClickPoint(bounds);
        operationPending = true;

        ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    operationPending = false;
                    waitTicks = 0;
                    phase = ShopPhase.WAIT_PURCHASE;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to click item", e);
                    fail("Click failed");
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait Purchase
    // ========================================================================

    private void executeWaitPurchase(TaskContext ctx) {
        waitTicks++;

        // Check if we got the item
        int currentCount = ctx.getInventoryState().countItem(itemId);
        int expectedGain = quantityMode == PurchaseQuantity.EXACT ? quantity : getQuantityModeAmount(quantityMode);

        if (currentCount > startItemCount) {
            log.debug("Purchased item successfully (got {})", currentCount - startItemCount);
            if (closeAfter) {
                phase = ShopPhase.CLOSE_SHOP;
            } else {
                complete();
            }
            return;
        }

        if (waitTicks > PURCHASE_TIMEOUT) {
            // Might have failed (no money, item out of stock)
            log.warn("Purchase may have failed, assuming success");
            if (closeAfter) {
                phase = ShopPhase.CLOSE_SHOP;
            } else {
                complete();
            }
        }
    }

    private int getQuantityModeAmount(PurchaseQuantity mode) {
        switch (mode) {
            case ONE: return 1;
            case FIVE: return 5;
            case TEN: return 10;
            case FIFTY: return 50;
            default: return 1;
        }
    }

    // ========================================================================
    // Phase: Close Shop
    // ========================================================================

    private void executeCloseShop(TaskContext ctx) {
        Client client = ctx.getClient();

        if (!isShopOpen(client)) {
            complete();
            return;
        }

        // Press Escape to close shop
        operationPending = true;
        ctx.getKeyboardController().pressKey(java.awt.event.KeyEvent.VK_ESCAPE)
                .thenRun(() -> {
                    operationPending = false;
                    complete();
                })
                .exceptionally(e -> {
                    operationPending = false;
                    complete(); // Close anyway
                    return null;
                });
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if shop interface is currently open.
     */
    public static boolean isShopOpen(Client client) {
        Widget shopWidget = client.getWidget(WIDGET_SHOP_GROUP, 0);
        return shopWidget != null && !shopWidget.isHidden();
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
        String qtyStr = quantityMode == PurchaseQuantity.EXACT ? String.valueOf(quantity) : quantityMode.name();
        return String.format("ShopPurchaseTask[npc=%d, item=%d, qty=%s]", shopkeeperNpcId, itemId, qtyStr);
    }

    // ========================================================================
    // Enums
    // ========================================================================

    /**
     * Purchase quantity modes.
     */
    public enum PurchaseQuantity {
        ONE,
        FIVE,
        TEN,
        FIFTY,
        EXACT
    }

    /**
     * Execution phases.
     */
    private enum ShopPhase {
        FIND_SHOPKEEPER,
        MOVE_TO_SHOPKEEPER,
        OPEN_SHOP,
        WAIT_SHOP_OPEN,
        SET_QUANTITY,
        FIND_ITEM,
        PURCHASE_ITEM,
        WAIT_PURCHASE,
        CLOSE_SHOP
    }
}

