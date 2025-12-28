package com.rocinante.tasks.impl;

import com.rocinante.input.MenuHelper;
import com.rocinante.input.WidgetClickHelper;
import com.rocinante.state.GrandExchangeSlot;
import com.rocinante.state.GrandExchangeState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.timing.DelayProfile;
import com.rocinante.ui.GrandExchangeWidgets;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Full-featured Grand Exchange interaction task.
 *
 * <p>Supports operations:
 * <ul>
 *   <li>BUY - Create a buy offer</li>
 *   <li>SELL - Create a sell offer</li>
 *   <li>COLLECT - Collect completed offers</li>
 *   <li>ABORT - Cancel an active offer</li>
 *   <li>OPEN_ONLY - Just open the GE interface</li>
 * </ul>
 *
 * <p>Pricing modes:
 * <ul>
 *   <li>EXACT - Use a specific price</li>
 *   <li>GUIDE_PRICE - Use the guide price (mid)</li>
 *   <li>INSTANT_BUY - Guide price + 5% (fast buy)</li>
 *   <li>INSTANT_SELL - Guide price - 5% (fast sell)</li>
 *   <li>PERCENTAGE_OFFSET - Guide price +/- X%</li>
 * </ul>
 *
 * <p>Important: Ironman accounts cannot use the Grand Exchange.
 * The task will fail immediately if {@code IronmanState.canUseGrandExchange()} returns false.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Buy 1000 lobsters at instant buy price
 * GrandExchangeTask buy = GrandExchangeTask.buy(ItemID.LOBSTER, 1000, PriceMode.INSTANT_BUY);
 *
 * // Sell all coal at guide price
 * GrandExchangeTask sell = GrandExchangeTask.sell(ItemID.COAL, 500, PriceMode.GUIDE_PRICE);
 *
 * // Collect all completed offers
 * GrandExchangeTask collect = GrandExchangeTask.collect();
 *
 * // Abort offer in slot 2
 * GrandExchangeTask abort = GrandExchangeTask.abort(2);
 * }</pre>
 */
@Slf4j
public class GrandExchangeTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Maximum ticks to wait for GE interface to open.
     */
    private static final int GE_OPEN_TIMEOUT = 15;

    /**
     * Maximum ticks to wait for search results.
     */
    private static final int SEARCH_TIMEOUT = 20;

    /**
     * Maximum ticks to wait for offer creation.
     */
    private static final int OFFER_TIMEOUT = 15;

    /**
     * Search radius for finding GE clerk.
     */
    private static final int SEARCH_RADIUS = 20;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The operation to perform.
     */
    @Getter
    private final GrandExchangeOperation operation;

    /**
     * Item ID for buy/sell operations.
     */
    @Getter
    private final int itemId;

    /**
     * Quantity to buy or sell.
     */
    @Getter
    private final int quantity;

    /**
     * Pricing mode.
     */
    @Getter
    private final PriceMode priceMode;

    /**
     * Exact price (for EXACT mode).
     */
    @Getter
    private final int exactPrice;

    /**
     * Percentage offset (for PERCENTAGE_OFFSET mode).
     * Positive for above guide, negative for below.
     */
    @Getter
    private final double percentageOffset;

    /**
     * Slot index for abort operations.
     */
    @Getter
    private final int targetSlot;

    /**
     * Whether to close GE after operation.
     */
    @Getter
    @Setter
    private boolean closeAfter = true;

    /**
     * Item search term (for buy operations).
     * If null, uses item name from ItemManager.
     */
    @Getter
    @Setter
    private String searchTerm;

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
    private GrandExchangePhase phase = GrandExchangePhase.VALIDATE;

    /**
     * Target GE clerk NPC.
     */
    private NPC targetClerk;

    /**
     * The slot we're using for the operation.
     */
    private int selectedSlot = -1;

    /**
     * Whether an async operation is pending.
     */
    private boolean operationPending = false;

    /**
     * Ticks waiting for response.
     */
    private int waitTicks = 0;

    /**
     * Calculated final price for offer.
     */
    private int calculatedPrice = 0;

    // ========================================================================
    // Constructors
    // ========================================================================

    private GrandExchangeTask(
            GrandExchangeOperation operation,
            int itemId,
            int quantity,
            PriceMode priceMode,
            int exactPrice,
            double percentageOffset,
            int targetSlot) {
        this.operation = operation;
        this.itemId = itemId;
        this.quantity = quantity;
        this.priceMode = priceMode;
        this.exactPrice = exactPrice;
        this.percentageOffset = percentageOffset;
        this.targetSlot = targetSlot;
        this.timeout = Duration.ofSeconds(90);
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a buy task with exact price.
     *
     * @param itemId   the item to buy
     * @param quantity the quantity to buy
     * @param price    the exact price per item
     * @return configured GE task
     */
    public static GrandExchangeTask buy(int itemId, int quantity, int price) {
        return new GrandExchangeTask(
                GrandExchangeOperation.BUY, itemId, quantity,
                PriceMode.EXACT, price, 0, -1);
    }

    /**
     * Create a buy task with price mode.
     *
     * @param itemId    the item to buy
     * @param quantity  the quantity to buy
     * @param priceMode the pricing strategy
     * @return configured GE task
     */
    public static GrandExchangeTask buy(int itemId, int quantity, PriceMode priceMode) {
        return new GrandExchangeTask(
                GrandExchangeOperation.BUY, itemId, quantity,
                priceMode, 0, 0, -1);
    }

    /**
     * Create a buy task with percentage offset from guide price.
     *
     * @param itemId           the item to buy
     * @param quantity         the quantity to buy
     * @param percentageOffset percentage above guide price (e.g., 5.0 for +5%)
     * @return configured GE task
     */
    public static GrandExchangeTask buyWithOffset(int itemId, int quantity, double percentageOffset) {
        return new GrandExchangeTask(
                GrandExchangeOperation.BUY, itemId, quantity,
                PriceMode.PERCENTAGE_OFFSET, 0, percentageOffset, -1);
    }

    /**
     * Create a sell task with exact price.
     *
     * @param itemId   the item to sell
     * @param quantity the quantity to sell
     * @param price    the exact price per item
     * @return configured GE task
     */
    public static GrandExchangeTask sell(int itemId, int quantity, int price) {
        return new GrandExchangeTask(
                GrandExchangeOperation.SELL, itemId, quantity,
                PriceMode.EXACT, price, 0, -1);
    }

    /**
     * Create a sell task with price mode.
     *
     * @param itemId    the item to sell
     * @param quantity  the quantity to sell
     * @param priceMode the pricing strategy
     * @return configured GE task
     */
    public static GrandExchangeTask sell(int itemId, int quantity, PriceMode priceMode) {
        return new GrandExchangeTask(
                GrandExchangeOperation.SELL, itemId, quantity,
                priceMode, 0, 0, -1);
    }

    /**
     * Create a sell task with percentage offset from guide price.
     *
     * @param itemId           the item to sell
     * @param quantity         the quantity to sell
     * @param percentageOffset percentage below guide price (e.g., -5.0 for -5%)
     * @return configured GE task
     */
    public static GrandExchangeTask sellWithOffset(int itemId, int quantity, double percentageOffset) {
        return new GrandExchangeTask(
                GrandExchangeOperation.SELL, itemId, quantity,
                PriceMode.PERCENTAGE_OFFSET, 0, percentageOffset, -1);
    }

    /**
     * Create a collect task to gather all completed items.
     *
     * @return configured GE task
     */
    public static GrandExchangeTask collect() {
        return new GrandExchangeTask(
                GrandExchangeOperation.COLLECT, -1, 0,
                PriceMode.GUIDE_PRICE, 0, 0, -1);
    }

    /**
     * Create an abort task to cancel an offer in a specific slot.
     *
     * @param slotIndex the slot to abort (0-7)
     * @return configured GE task
     */
    public static GrandExchangeTask abort(int slotIndex) {
        return new GrandExchangeTask(
                GrandExchangeOperation.ABORT, -1, 0,
                PriceMode.GUIDE_PRICE, 0, 0, slotIndex);
    }

    /**
     * Create a task to just open the GE interface.
     *
     * @return configured GE task
     */
    public static GrandExchangeTask open() {
        GrandExchangeTask task = new GrandExchangeTask(
                GrandExchangeOperation.OPEN_ONLY, -1, 0,
                PriceMode.GUIDE_PRICE, 0, 0, -1);
        task.setCloseAfter(false);
        return task;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set whether to close GE after operation.
     */
    public GrandExchangeTask withCloseAfter(boolean close) {
        this.closeAfter = close;
        return this;
    }

    /**
     * Set custom search term for buy operations.
     */
    public GrandExchangeTask withSearchTerm(String term) {
        this.searchTerm = term;
        return this;
    }

    /**
     * Set description for logging.
     */
    public GrandExchangeTask withDescription(String desc) {
        this.description = desc;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (!ctx.isLoggedIn()) {
            return false;
        }

        // Critical: Ironmen cannot use GE
        var ironmanState = ctx.getIronmanState();
        if (ironmanState != null && !ironmanState.canUseGrandExchange()) {
            log.warn("Cannot execute GE task: Ironman accounts cannot use the Grand Exchange");
            return false;
        }

        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (operationPending) {
            return;
        }

        switch (phase) {
            case VALIDATE:
                executeValidate(ctx);
                break;
            case FIND_GE:
                executeFindGE(ctx);
                break;
            case MOVE_TO_GE:
                executeMoveToGE(ctx);
                break;
            case OPEN_GE:
                executeOpenGE(ctx);
                break;
            case WAIT_GE_OPEN:
                executeWaitGEOpen(ctx);
                break;
            case SELECT_SLOT:
                executeSelectSlot(ctx);
                break;
            case SEARCH_ITEM:
                executeSearchItem(ctx);
                break;
            case SELECT_SEARCH_RESULT:
                executeSelectSearchResult(ctx);
                break;
            case SET_PRICE:
                executeSetPrice(ctx);
                break;
            case SET_QUANTITY:
                executeSetQuantity(ctx);
                break;
            case CONFIRM_OFFER:
                executeConfirmOffer(ctx);
                break;
            case WAIT_OFFER_CREATED:
                executeWaitOfferCreated(ctx);
                break;
            case COLLECT_ITEMS:
                executeCollectItems(ctx);
                break;
            case ABORT_OFFER:
                executeAbortOffer(ctx);
                break;
            case CLOSE_GE:
                executeCloseGE(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Validate
    // ========================================================================

    private void executeValidate(TaskContext ctx) {
        // Double-check ironman status
        var ironmanState = ctx.getIronmanState();
        if (ironmanState != null && !ironmanState.canUseGrandExchange()) {
            fail("Ironman accounts cannot use the Grand Exchange");
            return;
        }

        // Check GE state for slot availability
        GrandExchangeState geState = ctx.getGrandExchangeState();
        
        if (operation == GrandExchangeOperation.BUY || operation == GrandExchangeOperation.SELL) {
            // Need an empty slot
            if (geState != null && !geState.isUnknown() && !geState.hasEmptySlot()) {
                fail("No empty GE slots available");
                return;
            }
        }

        if (operation == GrandExchangeOperation.ABORT) {
            // Validate target slot
            if (targetSlot < 0 || targetSlot >= GrandExchangeState.TOTAL_SLOTS) {
                fail("Invalid abort target slot: " + targetSlot);
                return;
            }
            if (geState != null && !geState.isUnknown()) {
                GrandExchangeSlot slot = geState.getSlot(targetSlot);
                if (slot.isEmpty()) {
                    fail("Cannot abort empty slot " + targetSlot);
                    return;
                }
            }
        }

        log.debug("GE task validation passed, operation: {}", operation);
        
        // Check if GE is already open
        if (GrandExchangeWidgets.isOpen(ctx.getClient())) {
            log.debug("GE is already open");
            phase = determineNextPhase();
        } else {
            phase = GrandExchangePhase.FIND_GE;
        }
    }

    private GrandExchangePhase determineNextPhase() {
        switch (operation) {
            case BUY:
            case SELL:
                return GrandExchangePhase.SELECT_SLOT;
            case COLLECT:
                return GrandExchangePhase.COLLECT_ITEMS;
            case ABORT:
                return GrandExchangePhase.ABORT_OFFER;
            case OPEN_ONLY:
                complete();
                return null;
            default:
                return GrandExchangePhase.CLOSE_GE;
        }
    }

    // ========================================================================
    // Phase: Find GE
    // ========================================================================

    private void executeFindGE(TaskContext ctx) {
        Client client = ctx.getClient();
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();

        if (playerPos == null) {
            fail("Cannot determine player position");
            return;
        }

        // Find nearest GE clerk
        targetClerk = findNearestGEClerk(ctx, playerPos);
        
        if (targetClerk == null) {
            fail("No GE clerk found within " + SEARCH_RADIUS + " tiles");
            return;
        }

        log.debug("Found GE clerk at {}", targetClerk.getWorldLocation());
        phase = GrandExchangePhase.MOVE_TO_GE;
    }

    private NPC findNearestGEClerk(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        NPC nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (NPC npc : client.getNpcs()) {
            if (npc == null) continue;

            int npcId = npc.getId();
            boolean isGEClerk = Arrays.stream(GrandExchangeWidgets.GE_CLERK_NPC_IDS)
                    .anyMatch(id -> id == npcId);

            if (isGEClerk || isGEClerkByName(npc)) {
                WorldPoint npcPos = npc.getWorldLocation();
                if (npcPos != null) {
                    int dist = playerPos.distanceTo(npcPos);
                    if (dist <= SEARCH_RADIUS && dist < nearestDist) {
                        nearest = npc;
                        nearestDist = dist;
                    }
                }
            }
        }
        return nearest;
    }

    private boolean isGEClerkByName(NPC npc) {
        String name = npc.getName();
        if (name == null) return false;
        return name.toLowerCase().contains("grand exchange clerk");
    }

    // ========================================================================
    // Phase: Move to GE
    // ========================================================================

    private void executeMoveToGE(TaskContext ctx) {
        if (targetClerk == null) {
            fail("Lost GE clerk target");
            return;
        }

        Rectangle bounds = targetClerk.getConvexHull() != null ?
                targetClerk.getConvexHull().getBounds() : null;

        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            log.debug("GE clerk not visible, waiting...");
            waitTicks++;
            if (waitTicks > 10) {
                fail("GE clerk not visible");
            }
            return;
        }

        // Use humanized click position (same formula as WidgetClickHelper)
        int clickX = bounds.x + randomOffset(bounds.width);
        int clickY = bounds.y + randomOffset(bounds.height);

        operationPending = true;
        ctx.getMouseController().moveToCanvas(clickX, clickY)
                .thenRun(() -> {
                    operationPending = false;
                    phase = GrandExchangePhase.OPEN_GE;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to move to GE clerk", e);
                    fail("Mouse movement failed");
                    return null;
                });
    }

    // ========================================================================
    // Phase: Open GE
    // ========================================================================

    private void executeOpenGE(TaskContext ctx) {
        log.debug("Clicking to open GE");

        operationPending = true;
        ctx.getMouseController().click()
                .thenRun(() -> {
                    operationPending = false;
                    waitTicks = 0;
                    phase = GrandExchangePhase.WAIT_GE_OPEN;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to click GE clerk", e);
                    fail("Click failed");
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait GE Open
    // ========================================================================

    private void executeWaitGEOpen(TaskContext ctx) {
        Client client = ctx.getClient();

        if (GrandExchangeWidgets.isOpen(client)) {
            log.debug("GE interface opened");
            
            if (operation == GrandExchangeOperation.OPEN_ONLY) {
                complete();
                return;
            }
            
            GrandExchangePhase nextPhase = determineNextPhase();
            if (nextPhase != null) {
                phase = nextPhase;
            }
            return;
        }

        waitTicks++;
        if (waitTicks > GE_OPEN_TIMEOUT) {
            log.debug("GE didn't open, retrying...");
            phase = GrandExchangePhase.MOVE_TO_GE;
            waitTicks = 0;
        }
    }

    // ========================================================================
    // Phase: Select Slot
    // ========================================================================

    private void executeSelectSlot(TaskContext ctx) {
        Client client = ctx.getClient();
        GrandExchangeState geState = ctx.getGrandExchangeState();

        // For SELL, we need to click an item in our inventory in the GE side panel
        if (operation == GrandExchangeOperation.SELL) {
            executeSellFromInventory(ctx);
            return;
        }

        // For BUY, find an empty slot and click its buy button
        Optional<GrandExchangeSlot> emptySlot = geState != null && !geState.isUnknown() ?
                geState.getFirstEmptySlot() : Optional.empty();

        if (emptySlot.isPresent()) {
            selectedSlot = emptySlot.get().getSlotIndex();
        } else {
            // Try to find visually by checking widget states
            selectedSlot = findVisuallyEmptySlot(client);
        }

        if (selectedSlot < 0) {
            fail("No empty slot found for buy offer");
            return;
        }

        log.debug("Selected slot {} for buy offer", selectedSlot);

        // Click the slot to open buy interface
        Widget slotWidget = GrandExchangeWidgets.getSlotWidget(client, selectedSlot);
        if (slotWidget == null || slotWidget.isHidden()) {
            fail("Slot widget not found");
            return;
        }

        // The slot widget should have child widgets for buy/sell buttons
        // For an empty slot, clicking it should open the selection
        clickWidget(ctx, slotWidget, "Buy slot " + selectedSlot, GrandExchangePhase.SEARCH_ITEM);
    }

    private int findVisuallyEmptySlot(Client client) {
        for (int i = 0; i < GrandExchangeState.TOTAL_SLOTS; i++) {
            Widget slot = GrandExchangeWidgets.getSlotWidget(client, i);
            if (slot != null && !slot.isHidden()) {
                // Check if this slot appears empty (would need more detailed widget inspection)
                // For now, return first found slot
                return i;
            }
        }
        return -1;
    }

    private void executeSellFromInventory(TaskContext ctx) {
        Client client = ctx.getClient();

        // Find the item in the GE inventory panel
        Widget invWidget = client.getWidget(GrandExchangeWidgets.INVENTORY_GROUP_ID, GrandExchangeWidgets.INVENTORY_ITEMS);
        if (invWidget == null) {
            fail("GE inventory panel not found");
            return;
        }

        Widget itemWidget = findItemInWidget(invWidget, itemId);
        if (itemWidget == null) {
            fail("Item " + itemId + " not found in inventory for selling");
            return;
        }

        log.debug("Found item {} in GE inventory, clicking to sell", itemId);
        clickWidget(ctx, itemWidget, "Sell item", GrandExchangePhase.SET_PRICE);
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
    // Phase: Search Item (Buy only)
    // ========================================================================

    private void executeSearchItem(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if we're in the search/setup view
        if (!GrandExchangeWidgets.isInSetupView(client)) {
            waitTicks++;
            if (waitTicks > 10) {
                fail("Failed to enter GE setup view");
            }
            return;
        }

        // Get the search term
        String term = searchTerm;
        if (term == null || term.isEmpty()) {
            // Use item name from ItemManager
            var itemComposition = client.getItemDefinition(itemId);
            if (itemComposition != null) {
                term = itemComposition.getName();
            } else {
                fail("Cannot determine item name for search");
                return;
            }
        }

        log.debug("Searching for item: {}", term);

        // Type the search term
        final String searchTermFinal = term;
        operationPending = true;
        ctx.getKeyboardController().type(searchTermFinal)
                .thenCompose(v -> ctx.getHumanTimer().sleep(DelayProfile.MENU_SELECT))
                .thenRun(() -> {
                    operationPending = false;
                    waitTicks = 0;
                    phase = GrandExchangePhase.SELECT_SEARCH_RESULT;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to type search term", e);
                    fail("Search input failed");
                    return null;
                });
    }

    // ========================================================================
    // Phase: Select Search Result
    // ========================================================================

    private void executeSelectSearchResult(TaskContext ctx) {
        Client client = ctx.getClient();

        // Search results appear in a chatbox-like interface
        // For simplicity, we'll press Enter to select the first/highlighted result
        // In a more sophisticated implementation, we'd scan the results widget

        waitTicks++;
        if (waitTicks < 3) {
            // Wait a moment for results to appear
            return;
        }

        log.debug("Selecting search result (pressing Enter)");

        operationPending = true;
        ctx.getKeyboardController().pressEnter()
                .thenCompose(v -> ctx.getHumanTimer().sleep(DelayProfile.REACTION))
                .thenRun(() -> {
                    operationPending = false;
                    waitTicks = 0;
                    phase = GrandExchangePhase.SET_PRICE;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to select search result", e);
                    fail("Result selection failed");
                    return null;
                });
    }

    // ========================================================================
    // Phase: Set Price
    // ========================================================================

    private void executeSetPrice(TaskContext ctx) {
        Client client = ctx.getClient();

        // Calculate the price based on mode
        calculatedPrice = calculatePrice(ctx);
        if (calculatedPrice <= 0) {
            fail("Failed to calculate price");
            return;
        }

        log.debug("Setting price to {} (mode: {})", calculatedPrice, priceMode);

        // The price input in GE setup - we need to click the price area and type
        // For guide price, we can skip modification
        if (priceMode == PriceMode.GUIDE_PRICE) {
            phase = GrandExchangePhase.SET_QUANTITY;
            return;
        }

        // Click the price button/area and enter custom price
        Widget priceWidget = client.getWidget(GrandExchangeWidgets.GROUP_ID, GrandExchangeWidgets.CHILD_SETUP_MARKET_PRICE);
        if (priceWidget == null || priceWidget.isHidden()) {
            // Try to proceed anyway, price might already be set
            phase = GrandExchangePhase.SET_QUANTITY;
            return;
        }

        // Use WidgetClickHelper for the click, then chain keyboard input
        WidgetClickHelper clickHelper = ctx.getWidgetClickHelper();
        operationPending = true;
        
        CompletableFuture<Boolean> clickFuture = (clickHelper != null) 
                ? clickHelper.clickWidget(priceWidget, "Price button")
                : clickWidgetAndReturn(ctx, priceWidget);
        
        clickFuture
                .thenCompose(success -> {
                    if (!success) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return ctx.getHumanTimer().sleep(DelayProfile.MENU_SELECT);
                })
                .thenCompose(v -> ctx.getKeyboardController().type(String.valueOf(calculatedPrice)))
                .thenCompose(v -> ctx.getHumanTimer().sleep(DelayProfile.REACTION))
                .thenCompose(v -> ctx.getKeyboardController().pressEnter())
                .thenRun(() -> {
                    operationPending = false;
                    waitTicks = 0;
                    phase = GrandExchangePhase.SET_QUANTITY;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to set price", e);
                    // Continue anyway
                    phase = GrandExchangePhase.SET_QUANTITY;
                    return null;
                });
    }

    /**
     * Click a widget and return a CompletableFuture<Boolean> (for chaining).
     * Fallback for when WidgetClickHelper is not available.
     */
    private CompletableFuture<Boolean> clickWidgetAndReturn(TaskContext ctx, Widget widget) {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) {
            return CompletableFuture.completedFuture(false);
        }
        int clickX = bounds.x + randomOffset(bounds.width);
        int clickY = bounds.y + randomOffset(bounds.height);
        
        return ctx.getMouseController().moveToCanvas(clickX, clickY)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenApply(v -> true)
                .exceptionally(e -> false);
    }

    private int calculatePrice(TaskContext ctx) {
        switch (priceMode) {
            case EXACT:
                return exactPrice;
            case GUIDE_PRICE:
                return getGuidePrice(ctx);
            case INSTANT_BUY:
                return (int) (getGuidePrice(ctx) * 1.05);
            case INSTANT_SELL:
                return (int) (getGuidePrice(ctx) * 0.95);
            case PERCENTAGE_OFFSET:
                return (int) (getGuidePrice(ctx) * (1.0 + percentageOffset / 100.0));
            default:
                return getGuidePrice(ctx);
        }
    }

    private int getGuidePrice(TaskContext ctx) {
        // Get guide price from ItemManager
        Client client = ctx.getClient();
        var itemComposition = client.getItemDefinition(itemId);
        if (itemComposition != null) {
            return itemComposition.getPrice();
        }
        return 0;
    }

    // ========================================================================
    // Phase: Set Quantity
    // ========================================================================

    private void executeSetQuantity(TaskContext ctx) {
        Client client = ctx.getClient();

        if (quantity <= 0) {
            // Use all or default quantity
            phase = GrandExchangePhase.CONFIRM_OFFER;
            return;
        }

        log.debug("Setting quantity to {}", quantity);

        // Type the quantity
        operationPending = true;
        ctx.getKeyboardController().type(String.valueOf(quantity))
                .thenCompose(v -> ctx.getHumanTimer().sleep(DelayProfile.REACTION))
                .thenCompose(v -> ctx.getKeyboardController().pressEnter())
                .thenRun(() -> {
                    operationPending = false;
                    waitTicks = 0;
                    phase = GrandExchangePhase.CONFIRM_OFFER;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to set quantity", e);
                    phase = GrandExchangePhase.CONFIRM_OFFER;
                    return null;
                });
    }

    // ========================================================================
    // Phase: Confirm Offer
    // ========================================================================

    private void executeConfirmOffer(TaskContext ctx) {
        Client client = ctx.getClient();

        Widget confirmWidget = GrandExchangeWidgets.getConfirmWidget(client);
        if (confirmWidget == null || confirmWidget.isHidden()) {
            waitTicks++;
            if (waitTicks > 5) {
                fail("Confirm button not found");
            }
            return;
        }

        log.debug("Clicking confirm button");
        clickWidget(ctx, confirmWidget, "Confirm offer", GrandExchangePhase.WAIT_OFFER_CREATED);
    }

    // ========================================================================
    // Phase: Wait Offer Created
    // ========================================================================

    private void executeWaitOfferCreated(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if we're back at the main GE view (offer created)
        if (GrandExchangeWidgets.isInIndexView(client)) {
            log.debug("Offer created successfully");
            if (closeAfter) {
                phase = GrandExchangePhase.CLOSE_GE;
            } else {
                complete();
            }
            return;
        }

        waitTicks++;
        if (waitTicks > OFFER_TIMEOUT) {
            // Assume success and close
            log.warn("Offer creation timeout, assuming success");
            if (closeAfter) {
                phase = GrandExchangePhase.CLOSE_GE;
            } else {
                complete();
            }
        }
    }

    // ========================================================================
    // Phase: Collect Items
    // ========================================================================

    private void executeCollectItems(TaskContext ctx) {
        Client client = ctx.getClient();

        // Click the "Collect to inventory" or "Collect to bank" button
        Widget collectAllWidget = GrandExchangeWidgets.getCollectAllWidget(client);
        if (collectAllWidget != null && !collectAllWidget.isHidden()) {
            log.debug("Clicking collect all button");
            clickWidget(ctx, collectAllWidget, "Collect all", GrandExchangePhase.CLOSE_GE);
            return;
        }

        // No items to collect, or button not found
        log.debug("No collect button found, nothing to collect");
        if (closeAfter) {
            phase = GrandExchangePhase.CLOSE_GE;
        } else {
            complete();
        }
    }

    // ========================================================================
    // Phase: Abort Offer
    // ========================================================================

    private void executeAbortOffer(TaskContext ctx) {
        Client client = ctx.getClient();

        // Click the target slot first to view offer details
        Widget slotWidget = GrandExchangeWidgets.getSlotWidget(client, targetSlot);
        if (slotWidget == null || slotWidget.isHidden()) {
            fail("Target slot " + targetSlot + " widget not found");
            return;
        }

        // If in index view, click slot to view details
        if (GrandExchangeWidgets.isInIndexView(client)) {
            log.debug("Clicking slot {} to view offer details", targetSlot);
            
            WidgetClickHelper clickHelper = ctx.getWidgetClickHelper();
            operationPending = true;
            
            CompletableFuture<Boolean> clickFuture = (clickHelper != null)
                    ? clickHelper.clickWidget(slotWidget, "Slot " + targetSlot)
                    : clickWidgetAndReturn(ctx, slotWidget);
            
            clickFuture
                    .thenCompose(success -> ctx.getHumanTimer().sleep(DelayProfile.MENU_SELECT))
                    .thenRun(() -> {
                        operationPending = false;
                        // After clicking, we should be in details view
                        // Then we need to click the abort button
                        executeAbortInDetailsView(ctx);
                    })
                    .exceptionally(e -> {
                        operationPending = false;
                        log.error("Failed to click slot for abort", e);
                        fail("Click failed");
                        return null;
                    });
        } else if (GrandExchangeWidgets.isInDetailsView(client)) {
            executeAbortInDetailsView(ctx);
        } else {
            fail("Unknown GE view state for abort");
        }
    }

    private void executeAbortInDetailsView(TaskContext ctx) {
        Client client = ctx.getClient();

        // Find and click the abort/modify button
        Widget abortWidget = client.getWidget(GrandExchangeWidgets.GROUP_ID, GrandExchangeWidgets.CHILD_DETAILS_MODIFY);
        if (abortWidget == null || abortWidget.isHidden()) {
            fail("Abort button not found in details view");
            return;
        }

        log.debug("Clicking abort button");
        clickWidget(ctx, abortWidget, "Abort offer", GrandExchangePhase.CLOSE_GE);
    }

    // ========================================================================
    // Phase: Close GE
    // ========================================================================

    private void executeCloseGE(TaskContext ctx) {
        Client client = ctx.getClient();

        if (!GrandExchangeWidgets.isOpen(client)) {
            complete();
            return;
        }

        // Press Escape to close
        operationPending = true;
        ctx.getKeyboardController().pressKey(KeyEvent.VK_ESCAPE)
                .thenRun(() -> {
                    operationPending = false;
                    complete();
                })
                .exceptionally(e -> {
                    operationPending = false;
                    // Try clicking back button using WidgetClickHelper
                    Widget backWidget = GrandExchangeWidgets.getBackWidget(client);
                    if (backWidget != null && !backWidget.isHidden()) {
                        WidgetClickHelper clickHelper = ctx.getWidgetClickHelper();
                        if (clickHelper != null) {
                            clickHelper.clickWidget(backWidget, "Close GE")
                                    .thenRun(this::complete);
                        } else {
                            // Fallback using randomOffset
                            Rectangle bounds = backWidget.getBounds();
                            int clickX = bounds.x + randomOffset(bounds.width);
                            int clickY = bounds.y + randomOffset(bounds.height);
                            ctx.getMouseController().moveToCanvas(clickX, clickY)
                                    .thenCompose(v -> ctx.getMouseController().click())
                                    .thenRun(this::complete);
                        }
                    } else {
                        complete();
                    }
                    return null;
                });
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Click a widget using the centralized WidgetClickHelper.
     * This delegates to the existing helper to maintain DRY principles and
     * consistent humanization across all widget interactions.
     */
    private void clickWidget(TaskContext ctx, Widget widget, String actionDesc, GrandExchangePhase nextPhase) {
        WidgetClickHelper clickHelper = ctx.getWidgetClickHelper();
        if (clickHelper == null) {
            // Fallback for testing or when helper is unavailable
            log.warn("WidgetClickHelper not available, using direct click");
            clickWidgetDirect(ctx, widget, actionDesc, nextPhase);
            return;
        }

        operationPending = true;
        clickHelper.clickWidget(widget, actionDesc)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        log.debug("{} clicked successfully", actionDesc);
                        waitTicks = 0;
                        phase = nextPhase;
                    } else {
                        fail("Click failed: " + actionDesc);
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to {}", actionDesc, e);
                    fail("Click failed: " + actionDesc);
                    return null;
                });
    }

    /**
     * Direct widget click fallback (for testing or when helper unavailable).
     * Uses WidgetClickHelper's humanization formula.
     */
    private void clickWidgetDirect(TaskContext ctx, Widget widget, String actionDesc, GrandExchangePhase nextPhase) {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) {
            fail("Widget has no bounds: " + actionDesc);
            return;
        }

        // Use same humanization as WidgetClickHelper (45-55% center, 15% stddev)
        int clickX = bounds.x + randomOffset(bounds.width);
        int clickY = bounds.y + randomOffset(bounds.height);
        
        operationPending = true;
        ctx.getMouseController().moveToCanvas(clickX, clickY)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    operationPending = false;
                    log.debug("{} clicked", actionDesc);
                    waitTicks = 0;
                    phase = nextPhase;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to {}", actionDesc, e);
                    fail("Click failed: " + actionDesc);
                    return null;
                });
    }

    /**
     * Generate humanized click offset per REQUIREMENTS.md Section 3.1.2.
     * Matches WidgetClickHelper's implementation for consistency.
     */
    private int randomOffset(int dimension) {
        double centerPercent = 0.45 + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 0.10;
        double center = dimension * centerPercent;
        double stdDev = dimension * 0.15;
        double offset = center + java.util.concurrent.ThreadLocalRandom.current().nextGaussian() * stdDev;
        return (int) Math.max(2, Math.min(dimension - 2, offset));
    }

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        switch (operation) {
            case BUY:
                return String.format("GETask[buy %d x%d @ %s]", itemId, quantity, priceMode);
            case SELL:
                return String.format("GETask[sell %d x%d @ %s]", itemId, quantity, priceMode);
            case COLLECT:
                return "GETask[collect]";
            case ABORT:
                return String.format("GETask[abort slot %d]", targetSlot);
            case OPEN_ONLY:
                return "GETask[open]";
            default:
                return "GETask[" + operation + "]";
        }
    }

    // ========================================================================
    // Enums
    // ========================================================================

    /**
     * Grand Exchange operation types.
     */
    public enum GrandExchangeOperation {
        /** Create a buy offer. */
        BUY,
        /** Create a sell offer. */
        SELL,
        /** Collect completed offers. */
        COLLECT,
        /** Abort/cancel an offer. */
        ABORT,
        /** Just open the GE interface. */
        OPEN_ONLY
    }

    /**
     * Pricing modes for offers.
     */
    public enum PriceMode {
        /** Use an exact specified price. */
        EXACT,
        /** Use the current guide price. */
        GUIDE_PRICE,
        /** Guide price + 5% for fast buying. */
        INSTANT_BUY,
        /** Guide price - 5% for fast selling. */
        INSTANT_SELL,
        /** Guide price +/- a custom percentage. */
        PERCENTAGE_OFFSET
    }

    /**
     * Execution phases.
     */
    private enum GrandExchangePhase {
        VALIDATE,
        FIND_GE,
        MOVE_TO_GE,
        OPEN_GE,
        WAIT_GE_OPEN,
        SELECT_SLOT,
        SEARCH_ITEM,
        SELECT_SEARCH_RESULT,
        SET_PRICE,
        SET_QUANTITY,
        CONFIRM_OFFER,
        WAIT_OFFER_CREATED,
        COLLECT_ITEMS,
        ABORT_OFFER,
        CLOSE_GE
    }
}

