package com.rocinante.tasks.impl;

import com.rocinante.input.MenuHelper;
import com.rocinante.input.SafeClickExecutor;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

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
     * Sub-task for walking/traveling to shopkeeper when too far.
     */
    private WalkToTask walkSubTask;

    /**
     * Whether an async operation is pending.
     */
    private boolean operationPending = false;

    /**
     * Ticks waiting for response.
     */
    private int waitTicks = 0;

    /**
     * Maximum distance (tiles) to directly interact without walking closer.
     */
    private static final int INTERACTION_DISTANCE = 15;

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

        // Handle sub-task execution
        if (walkSubTask != null) {
            executeWalkSubTask(ctx);
            return;
        }

        switch (phase) {
            case FIND_SHOPKEEPER:
                executeFindShopkeeper(ctx);
                break;
            case WALK_TO_SHOPKEEPER:
                executeWalkToShopkeeper(ctx);
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
            int distance = playerPos.distanceTo(targetNpc.getWorldLocation());
            if (distance > INTERACTION_DISTANCE) {
                log.debug("Shopkeeper is {} tiles away, walking closer first", distance);
                phase = ShopPhase.WALK_TO_SHOPKEEPER;
            } else {
                phase = ShopPhase.OPEN_SHOP;
            }
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
    // Phase: Walk to Shopkeeper
    // ========================================================================

    private void executeWalkToShopkeeper(TaskContext ctx) {
        if (targetNpc == null || targetNpc.isDead()) {
            phase = ShopPhase.FIND_SHOPKEEPER;
            return;
        }

        WorldPoint targetPos = targetNpc.getWorldLocation();
        if (targetPos == null) {
            fail("Lost shopkeeper target while traveling");
            return;
        }

        // Create walk sub-task (uses WebWalker for optimal path including teleports)
        walkSubTask = new WalkToTask(targetPos)
                .withDescription("Travel to shopkeeper");
        log.debug("Starting travel to shopkeeper at {}", targetPos);
    }

    private void executeWalkSubTask(TaskContext ctx) {
        // Execute the walk task (handles state transitions automatically)
        walkSubTask.execute(ctx);

        // Check if walk is done
        if (walkSubTask.getState().isTerminal()) {
            if (walkSubTask.getState() == TaskState.COMPLETED) {
                log.debug("Travel to shopkeeper completed, proceeding to open");
                phase = ShopPhase.OPEN_SHOP;
            } else {
                log.warn("Travel to shopkeeper failed: {}", walkSubTask.getFailureReason());
                fail("Failed to travel to shopkeeper");
            }
            walkSubTask = null;
        }
    }

    // ========================================================================
    // Phase: Open Shop (uses SafeClickExecutor)
    // ========================================================================

    private void executeOpenShop(TaskContext ctx) {
        if (targetNpc == null || targetNpc.isDead()) {
            phase = ShopPhase.FIND_SHOPKEEPER;
            return;
        }

        Rectangle bounds = targetNpc.getConvexHull() != null ?
                targetNpc.getConvexHull().getBounds() : null;
        if (bounds == null || bounds.width == 0) {
            log.debug("Shopkeeper not visible, walking closer...");
            waitTicks++;
            if (waitTicks > 5) {
                phase = ShopPhase.WALK_TO_SHOPKEEPER;
                waitTicks = 0;
            }
            return;
        }

        log.debug("Clicking shopkeeper to open shop");
        operationPending = true;

        SafeClickExecutor safeClick = ctx.getSafeClickExecutor();
        safeClick.clickNpc(targetNpc, "Trade")
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        waitTicks = 0;
                        phase = ShopPhase.WAIT_SHOP_OPEN;
                    } else {
                        fail("Failed to click shopkeeper");
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Shopkeeper click failed", e);
                    fail("Click failed: " + e.getMessage());
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
            // Re-evaluate - target may have moved or we need different action
            log.debug("Shop didn't open, re-evaluating...");
            phase = ShopPhase.FIND_SHOPKEEPER;
            targetNpc = null;
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

        java.awt.Point clickPoint = com.rocinante.input.ClickPointCalculator.getGaussianClickPoint(bounds);
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
    private int actualPurchased;
    private int remainingToBuy;

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
        actualPurchased = 0;
        remainingToBuy = quantityMode == PurchaseQuantity.EXACT ? quantity : getQuantityModeAmount(quantityMode);

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

        if (quantityMode == PurchaseQuantity.EXACT) {
            if (remainingToBuy <= 0) {
                complete();
                return;
            }

            MenuHelper menuHelper = ctx.getMenuHelper();
            if (menuHelper == null) {
                fail("MenuHelper unavailable for exact shop purchase");
                return;
            }

            int chunk = chooseChunkSize(remainingToBuy);
            String action = "Buy " + chunk;

            operationPending = true;
            menuHelper.selectMenuEntry(bounds, action)
                    .thenAccept(success -> {
                        operationPending = false;
                        if (success) {
                            waitTicks = 0;
                            phase = ShopPhase.WAIT_PURCHASE;
                        } else {
                            fail("Failed to select menu entry: " + action);
                        }
                    })
                    .exceptionally(e -> {
                        operationPending = false;
                        log.error("Exact purchase menu selection failed", e);
                        fail("Exact purchase failed: " + e.getMessage());
                        return null;
                    });
            return;
        }

        java.awt.Point clickPoint = com.rocinante.input.ClickPointCalculator.getGaussianClickPoint(bounds);
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

        int currentCount = ctx.getInventoryState().countItem(itemId);

        // Track progress for all quantity modes
        int gained = currentCount - startItemCount;
        boolean madeProgress = gained > actualPurchased;
        if (madeProgress) {
            actualPurchased = gained;
            waitTicks = 0;
        }

        if (quantityMode == PurchaseQuantity.EXACT) {
            remainingToBuy = Math.max(quantity - actualPurchased, 0);

            if (remainingToBuy <= 0) {
                log.debug("Exact purchase complete: requested {}, got {}", quantity, actualPurchased);
                if (closeAfter) {
                    phase = ShopPhase.CLOSE_SHOP;
                } else {
                    complete();
                }
                return;
            }

            if (madeProgress) {
                // Continue buying remaining amount
                phase = ShopPhase.PURCHASE_ITEM;
                return;
            }

            int availableStock = targetItemWidget != null ? targetItemWidget.getItemQuantity() : -1;
            boolean outOfStock = availableStock == 0;

            if (waitTicks > PURCHASE_TIMEOUT) {
                if (actualPurchased > 0) {
                    log.warn("Partial purchase: requested {}, acquired {}, remaining {}",
                            quantity, actualPurchased, remainingToBuy);
                    if (closeAfter) {
                        phase = ShopPhase.CLOSE_SHOP;
                    } else {
                        complete();
                    }
                } else {
                    String reason = outOfStock ? "Shop is out of stock" : "Exact purchase timed out (no progress)";
                    fail(reason);
                }
            }
            return;
        }

        // Non-exact modes: expect a single increment
        int expectedGain = getQuantityModeAmount(quantityMode);
        if (gained >= expectedGain) {
            log.debug("Purchased item successfully (got {})", gained);
            if (closeAfter) {
                phase = ShopPhase.CLOSE_SHOP;
            } else {
                complete();
            }
            return;
        }

        if (waitTicks > PURCHASE_TIMEOUT) {
            fail("Purchase timed out with no progress");
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

    /**
     * Choose the menu chunk size for exact purchases, preferring larger buy options first.
     */
    private int chooseChunkSize(int remaining) {
        if (remaining >= 50) {
            return 50;
        }
        if (remaining >= 10) {
            return 10;
        }
        if (remaining >= 5) {
            return 5;
        }
        return 1;
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
        WALK_TO_SHOPKEEPER,
        OPEN_SHOP,
        WAIT_SHOP_OPEN,
        SET_QUANTITY,
        FIND_ITEM,
        PURCHASE_ITEM,
        WAIT_PURCHASE,
        CLOSE_SHOP
    }
}

