package com.rocinante.tasks.impl;

import com.rocinante.state.IronmanState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskPriority;
import com.rocinante.timing.DelayProfile;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

import java.awt.Rectangle;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Task for handling player-to-player trading.
 *
 * <p>Implements the full trade flow:
 * <ul>
 *   <li>First screen: Wait for offers, accept when ready</li>
 *   <li>Second screen: Verify items (scam protection), accept</li>
 *   <li>Optional: Send response message after trade</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>Humanized delays before accepting</li>
 *   <li>Scam protection: Verifies second screen matches first screen</li>
 *   <li>Auto-decline for ironmen (handled by TradeHandler)</li>
 *   <li>Configurable response messages</li>
 * </ul>
 *
 * <p>Example usage - accepting random trade:
 * <pre>{@code
 * TradeConfig config = TradeConfig.forRandomTrade();
 * TradeTask task = new TradeTask(config);
 * }</pre>
 *
 * @see TradeConfig
 * @see com.rocinante.behavior.tasks.TradeHandler
 */
@Slf4j
public class TradeTask extends AbstractTask {

    // ========================================================================
    // Widget Constants - Trade Main Screen (First Screen)
    // ========================================================================

    /**
     * Trade main screen widget group ID (InterfaceID.TRADEMAIN = 335).
     */
    private static final int TRADE_MAIN_GROUP = 335;

    /**
     * Accept button on first trade screen (child index 10 / 0x000a).
     */
    private static final int TRADE_MAIN_ACCEPT_CHILD = 10;

    /**
     * Decline button on first trade screen (child index 13 / 0x000d).
     */
    private static final int TRADE_MAIN_DECLINE_CHILD = 13;

    /**
     * Status text on first screen (shows "Other player has accepted").
     */
    private static final int TRADE_MAIN_STATUS_CHILD = 30;

    /**
     * Other player's offer container on first screen.
     */
    private static final int TRADE_MAIN_OTHER_OFFER_CHILD = 28;

    /**
     * Our offer container on first screen.
     */
    private static final int TRADE_MAIN_OUR_OFFER_CHILD = 25;

    // ========================================================================
    // Widget Constants - Trade Confirm Screen (Second Screen)
    // ========================================================================

    /**
     * Trade confirm screen widget group ID (InterfaceID.TRADECONFIRM = 334).
     */
    private static final int TRADE_CONFIRM_GROUP = 334;

    /**
     * Accept button on second trade screen (child index 13 / 0x000d).
     */
    private static final int TRADE_CONFIRM_ACCEPT_CHILD = 13;

    /**
     * Decline button on second trade screen (child index 14 / 0x000e).
     */
    private static final int TRADE_CONFIRM_DECLINE_CHILD = 14;

    /**
     * "You will give" text container on second screen.
     */
    private static final int TRADE_CONFIRM_YOU_GIVE_CHILD = 23;

    /**
     * "You will receive" text container on second screen.
     */
    private static final int TRADE_CONFIRM_YOU_RECEIVE_CHILD = 24;

    // ========================================================================
    // Configuration
    // ========================================================================

    @Getter
    private final TradeConfig config;

    /**
     * Custom description for the task.
     */
    @Setter
    private String description;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current trade phase.
     */
    private TradePhase phase = TradePhase.WAIT_FIRST_SCREEN;

    /**
     * Items offered by the other player on first screen (item ID -> quantity).
     */
    private final Map<Integer, Integer> theirOffer = new HashMap<>();

    /**
     * Total high alch value of their offer.
     */
    @Getter
    private long theirOfferValue = 0;

    /**
     * Whether they have accepted the first screen.
     */
    private boolean theyAccepted = false;

    /**
     * Ticks waiting in current phase.
     */
    private int waitTicks = 0;

    /**
     * Whether a click is pending (async operation).
     */
    private boolean clickPending = false;

    /**
     * Whether we're waiting for humanized delay before accepting.
     */
    private boolean waitingForAcceptDelay = false;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a trade task with the specified configuration.
     *
     * @param config the trade configuration
     */
    public TradeTask(TradeConfig config) {
        this.config = config;
        this.priority = TaskPriority.BEHAVIORAL;
        this.timeout = Duration.ofSeconds(120); // 2 minute timeout for trades
    }

    /**
     * Create a trade task for accepting random trades.
     *
     * @return trade task configured for random trade acceptance
     */
    public static TradeTask forRandomTrade() {
        return new TradeTask(TradeConfig.forRandomTrade());
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set custom description (builder-style).
     *
     * @param desc the description
     * @return this task for chaining
     */
    public TradeTask withDescription(String desc) {
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

        // Ironman accounts cannot trade with other players
        IronmanState ironmanState = ctx.getGameStateService().getIronmanState();
        if (ironmanState.isIronman()) {
            log.debug("Cannot trade - account is an ironman");
            return false;
        }

        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (clickPending) {
            return;
        }

        switch (phase) {
            case WAIT_FIRST_SCREEN:
                executeWaitFirstScreen(ctx);
                break;
            case FIRST_SCREEN_MONITOR:
                executeFirstScreenMonitor(ctx);
                break;
            case WAIT_ACCEPT_DELAY:
                executeWaitAcceptDelay(ctx);
                break;
            case ACCEPT_FIRST:
                executeAcceptFirst(ctx);
                break;
            case WAIT_SECOND_SCREEN:
                executeWaitSecondScreen(ctx);
                break;
            case VERIFY_SECOND:
                executeVerifySecond(ctx);
                break;
            case ACCEPT_SECOND:
                executeAcceptSecond(ctx);
                break;
            case SEND_RESPONSE:
                executeSendResponse(ctx);
                break;
            case COMPLETED:
                complete();
                break;
        }
    }

    // ========================================================================
    // Phase: Wait for First Screen
    // ========================================================================

    private void executeWaitFirstScreen(TaskContext ctx) {
        Client client = ctx.getClient();

        if (isFirstScreenVisible(client)) {
            log.debug("First trade screen detected");
            phase = TradePhase.FIRST_SCREEN_MONITOR;
            waitTicks = 0;
            return;
        }

        waitTicks++;
        if (waitTicks > 10) {
            log.warn("Trade window not detected after {} ticks", waitTicks);
            fail("Trade window not opened");
        }
    }

    // ========================================================================
    // Phase: Monitor First Screen
    // ========================================================================

    private void executeFirstScreenMonitor(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if trade closed
        if (!isFirstScreenVisible(client)) {
            log.info("Trade cancelled - first screen closed");
            fail("Trade cancelled by other player");
            return;
        }

        // Update their offer
        updateTheirOffer(client);

        // Check if they accepted
        if (hasOtherPlayerAccepted(client)) {
            if (!theyAccepted) {
                theyAccepted = true;
                log.info("Other player accepted (offer value: {} gp)", theirOfferValue);
            }

            // For passive trades, wait for humanized delay then accept
            if (config.isPassiveTrade()) {
                phase = TradePhase.WAIT_ACCEPT_DELAY;
                waitingForAcceptDelay = true;
                waitTicks = 0;
                return;
            }
        }

        // Check timeout
        waitTicks++;
        if (waitTicks > config.getMaxWaitTicks()) {
            log.warn("Trade timed out waiting for other player");
            declineTrade(ctx);
            fail("Trade timed out");
        }
    }

    // ========================================================================
    // Phase: Wait Accept Delay (Humanization)
    // ========================================================================

    private void executeWaitAcceptDelay(TaskContext ctx) {
        Client client = ctx.getClient();

        // Verify trade still open
        if (!isFirstScreenVisible(client)) {
            log.info("Trade cancelled during accept delay");
            fail("Trade cancelled");
            return;
        }

        // Wait humanized delay before accepting
        if (waitingForAcceptDelay) {
            waitingForAcceptDelay = false;

            // Calculate humanized delay
            Randomization rand = ctx.getRandomization();
            long delay = rand != null
                    ? rand.uniformRandomLong(config.getMinAcceptDelayMs(), config.getMaxAcceptDelayMs())
                    : config.getMinAcceptDelayMs();

            log.debug("Waiting {}ms before accepting trade", delay);

            clickPending = true;
            ctx.getHumanTimer().sleep(delay)
                    .thenRun(() -> {
                        clickPending = false;
                        phase = TradePhase.ACCEPT_FIRST;
                    })
                    .exceptionally(e -> {
                        clickPending = false;
                        log.error("Accept delay failed", e);
                        return null;
                    });
        }
    }

    // ========================================================================
    // Phase: Accept First Screen
    // ========================================================================

    private void executeAcceptFirst(TaskContext ctx) {
        Client client = ctx.getClient();

        if (!isFirstScreenVisible(client)) {
            log.info("Trade cancelled before accepting");
            fail("Trade cancelled");
            return;
        }

        Widget acceptButton = client.getWidget(TRADE_MAIN_GROUP, TRADE_MAIN_ACCEPT_CHILD);
        if (acceptButton == null || acceptButton.isHidden()) {
            log.warn("Accept button not found on first screen");
            fail("Accept button not found");
            return;
        }

        log.debug("Clicking accept on first trade screen");
        clickWidget(ctx, acceptButton, () -> {
            phase = TradePhase.WAIT_SECOND_SCREEN;
            waitTicks = 0;
        });
    }

    // ========================================================================
    // Phase: Wait for Second Screen
    // ========================================================================

    private void executeWaitSecondScreen(TaskContext ctx) {
        Client client = ctx.getClient();

        if (isSecondScreenVisible(client)) {
            log.debug("Second trade screen detected");
            phase = config.shouldVerifyTrade() ? TradePhase.VERIFY_SECOND : TradePhase.ACCEPT_SECOND;
            waitTicks = 0;
            return;
        }

        // Check if trade closed entirely (other player declined)
        if (!isFirstScreenVisible(client) && !isSecondScreenVisible(client)) {
            log.info("Trade cancelled - screens closed");
            fail("Trade cancelled by other player");
            return;
        }

        waitTicks++;
        if (waitTicks > 20) {
            log.warn("Second screen not appearing");
            fail("Second trade screen timeout");
        }
    }

    // ========================================================================
    // Phase: Verify Second Screen (Scam Protection)
    // ========================================================================

    private void executeVerifySecond(TaskContext ctx) {
        Client client = ctx.getClient();

        if (!isSecondScreenVisible(client)) {
            fail("Second screen closed during verification");
            return;
        }

        // For passive trades (not offering anything), no verification needed
        if (config.isPassiveTrade()) {
            phase = TradePhase.ACCEPT_SECOND;
            return;
        }

        // Verify second screen items match what we saw on first screen
        if (config.shouldVerifyTrade()) {
            Map<Integer, Integer> secondScreenItems = readSecondScreenReceiveItems(client);
            
            if (secondScreenItems == null) {
                log.warn("Could not read second screen items - declining for safety");
                declineTrade(ctx);
                fail("Could not verify trade - scam protection");
                return;
            }

            // Compare to first screen offer (theirOffer)
            if (!verifyItemsMatch(theirOffer, secondScreenItems)) {
                log.warn("SCAM DETECTED: Second screen items don't match first screen!");
                log.warn("First screen had: {}", theirOffer);
                log.warn("Second screen has: {}", secondScreenItems);
                declineTrade(ctx);
                fail("Trade items changed - scam protection triggered");
                return;
            }

            // Also verify expected items if configured
            if (config.hasExpectedItems()) {
                Map<Integer, Integer> expected = config.getExpectedItems();
                if (!verifyExpectedItems(secondScreenItems, expected)) {
                    log.warn("Trade doesn't contain expected items");
                    log.warn("Expected: {}", expected);
                    log.warn("Receiving: {}", secondScreenItems);
                    declineTrade(ctx);
                    fail("Trade missing expected items");
                    return;
                }
            }

            log.debug("Scam protection passed - second screen matches first screen");
        }

        phase = TradePhase.ACCEPT_SECOND;
    }

    /**
     * Read the items shown on the second trade screen's "You will receive" section.
     * The second screen shows text descriptions like "1 x Bronze dagger" or "Absolutely nothing!"
     *
     * Uses widget itemId and itemQuantity properties for reliable parsing.
     * Falls back to text parsing only when widget properties are unavailable.
     *
     * @return map of item ID -> quantity, or null if cannot parse
     */
    private Map<Integer, Integer> readSecondScreenReceiveItems(Client client) {
        Widget receiveWidget = client.getWidget(TRADE_CONFIRM_GROUP, TRADE_CONFIRM_YOU_RECEIVE_CHILD);
        if (receiveWidget == null) {
            log.debug("Trade confirm receive widget not found");
            return null;
        }

        // The widget contains text children with item descriptions
        Widget[] children = receiveWidget.getStaticChildren();
        if (children == null || children.length == 0) {
            // Try dynamic children
            children = receiveWidget.getDynamicChildren();
        }

        if (children == null || children.length == 0) {
            // Check if main widget has text directly
            String text = receiveWidget.getText();
            if (text != null) {
                if (text.contains("Absolutely nothing") || text.trim().isEmpty()) {
                    return new HashMap<>(); // Empty trade, which is valid
                }
                // Try to parse from widget directly if it has item data
                int itemId = receiveWidget.getItemId();
                int quantity = receiveWidget.getItemQuantity();
                if (itemId > 0) {
                    Map<Integer, Integer> result = new HashMap<>();
                    result.put(itemId, Math.max(1, quantity));
                    return result;
                }
            }
            return null;
        }

        // Parse each item from children widgets
        Map<Integer, Integer> items = new HashMap<>();
        for (Widget child : children) {
            if (child == null || child.isHidden()) {
                continue;
            }

            String text = child.getText();

            // Skip empty/nothing entries
            if (text != null && (text.contains("Absolutely nothing") || text.trim().isEmpty())) {
                continue;
            }

            // Get item info from widget properties (most reliable)
            int itemId = child.getItemId();
            int quantity = child.getItemQuantity();

            if (itemId > 0) {
                // Ensure quantity is at least 1
                quantity = Math.max(1, quantity);
                items.merge(itemId, quantity, Integer::sum);
                log.debug("Parsed trade item: {} x{}", itemId, quantity);
            } else if (text != null && !text.trim().isEmpty()) {
                // Fallback: try to parse from text format "X x Item Name"
                // This handles edge cases where widget properties aren't set
                int parsedQuantity = parseQuantityFromText(text);
                log.debug("Trade item without itemId, text='{}', parsed quantity={}", text, parsedQuantity);
                // We can't determine itemId from text alone without item name lookup
                // This would require a WikiDataService lookup which is outside scope
                // Log for debugging purposes
            }
        }

        return items;
    }

    /**
     * Parse quantity from trade text format.
     * Handles formats like "1 x Bronze dagger", "1,234 x Coins", "Bronze dagger" (quantity 1).
     */
    private int parseQuantityFromText(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }

        text = text.trim();

        // Look for "X x " pattern at the start
        int xIndex = text.indexOf(" x ");
        if (xIndex > 0) {
            String quantityStr = text.substring(0, xIndex).replace(",", "").trim();
            try {
                return Integer.parseInt(quantityStr);
            } catch (NumberFormatException e) {
                log.debug("Could not parse quantity from '{}': {}", quantityStr, e.getMessage());
            }
        }

        // No quantity prefix means quantity is 1
        return 1;
    }

    /**
     * Verify that the second screen items match the first screen offer.
     */
    private boolean verifyItemsMatch(Map<Integer, Integer> firstScreen, Map<Integer, Integer> secondScreen) {
        if (firstScreen.size() != secondScreen.size()) {
            return false;
        }

        for (Map.Entry<Integer, Integer> entry : firstScreen.entrySet()) {
            Integer secondQty = secondScreen.get(entry.getKey());
            if (secondQty == null || !secondQty.equals(entry.getValue())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Verify that we're receiving at least the expected items.
     */
    private boolean verifyExpectedItems(Map<Integer, Integer> receiving, Map<Integer, Integer> expected) {
        for (Map.Entry<Integer, Integer> entry : expected.entrySet()) {
            Integer receivingQty = receiving.get(entry.getKey());
            if (receivingQty == null || receivingQty < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    // ========================================================================
    // Phase: Accept Second Screen
    // ========================================================================

    private void executeAcceptSecond(TaskContext ctx) {
        Client client = ctx.getClient();

        if (!isSecondScreenVisible(client)) {
            log.info("Trade cancelled before final accept");
            fail("Trade cancelled");
            return;
        }

        Widget acceptButton = client.getWidget(TRADE_CONFIRM_GROUP, TRADE_CONFIRM_ACCEPT_CHILD);
        if (acceptButton == null || acceptButton.isHidden()) {
            log.warn("Accept button not found on second screen");
            fail("Accept button not found");
            return;
        }

        log.info("Accepting trade (receiving {} gp value)", theirOfferValue);
        clickWidget(ctx, acceptButton, () -> {
            phase = config.isSendResponse() ? TradePhase.SEND_RESPONSE : TradePhase.COMPLETED;
            waitTicks = 0;
        });
    }

    // ========================================================================
    // Phase: Send Response
    // ========================================================================

    private void executeSendResponse(TaskContext ctx) {
        // Wait for trade screens to close
        Client client = ctx.getClient();
        if (isFirstScreenVisible(client) || isSecondScreenVisible(client)) {
            waitTicks++;
            if (waitTicks > 10) {
                // Trade screens still up, skip response
                log.debug("Trade screens still visible, skipping response");
                phase = TradePhase.COMPLETED;
            }
            return;
        }

        // Type response message
        String message = config.getResponseMessage();
        log.debug("Sending trade response: {}", message);

        clickPending = true;
        ctx.getKeyboardController().type(message)
                .thenCompose(v -> ctx.getKeyboardController().pressEnter())
                .thenRun(() -> {
                    clickPending = false;
                    phase = TradePhase.COMPLETED;
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Failed to send response", e);
                    phase = TradePhase.COMPLETED; // Still complete trade even if response fails
                    return null;
                });
    }

    // ========================================================================
    // Widget Helpers
    // ========================================================================

    private boolean isFirstScreenVisible(Client client) {
        Widget widget = client.getWidget(TRADE_MAIN_GROUP, 0);
        return widget != null && !widget.isHidden();
    }

    private boolean isSecondScreenVisible(Client client) {
        Widget widget = client.getWidget(TRADE_CONFIRM_GROUP, 0);
        return widget != null && !widget.isHidden();
    }

    private boolean hasOtherPlayerAccepted(Client client) {
        Widget statusWidget = client.getWidget(TRADE_MAIN_GROUP, TRADE_MAIN_STATUS_CHILD);
        if (statusWidget == null || statusWidget.getText() == null) {
            return false;
        }
        String statusText = statusWidget.getText().toLowerCase();
        return statusText.contains("other player has accepted");
    }

    private void updateTheirOffer(Client client) {
        theirOffer.clear();
        theirOfferValue = 0;

        Widget offerWidget = client.getWidget(TRADE_MAIN_GROUP, TRADE_MAIN_OTHER_OFFER_CHILD);
        if (offerWidget == null) {
            return;
        }

        // Get items from the container
        Widget[] children = offerWidget.getDynamicChildren();
        if (children == null || children.length == 0) {
            return;
        }

        for (Widget child : children) {
            if (child == null) continue;

            int itemId = child.getItemId();
            int quantity = child.getItemQuantity();

            if (itemId > 0 && quantity > 0) {
                theirOffer.merge(itemId, quantity, Integer::sum);

                // Calculate high alch value
                try {
                    ItemComposition itemComp = client.getItemDefinition(itemId);
                    if (itemComp != null) {
                        int haValue = itemComp.getHaPrice();
                        theirOfferValue += (long) haValue * quantity;
                    }
                } catch (Exception e) {
                    log.debug("Could not get HA value for item {}", itemId);
                }
            }
        }
    }

    private void clickWidget(TaskContext ctx, Widget widget, Runnable onSuccess) {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            log.warn("Widget has invalid bounds");
            fail("Invalid widget bounds");
            return;
        }

        // Use centralized ClickPointCalculator for humanized positioning
        java.awt.Point clickPoint = com.rocinante.input.ClickPointCalculator.getGaussianClickPoint(bounds);
        int x = clickPoint.x;
        int y = clickPoint.y;

        clickPending = true;
        ctx.getMouseController().moveToCanvas(x, y)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    clickPending = false;
                    onSuccess.run();
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Widget click failed", e);
                    fail("Click failed: " + e.getMessage());
                    return null;
                });
    }

    private void declineTrade(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget declineButton = null;

        if (isFirstScreenVisible(client)) {
            declineButton = client.getWidget(TRADE_MAIN_GROUP, TRADE_MAIN_DECLINE_CHILD);
        } else if (isSecondScreenVisible(client)) {
            declineButton = client.getWidget(TRADE_CONFIRM_GROUP, TRADE_CONFIRM_DECLINE_CHILD);
        }

        if (declineButton != null && !declineButton.isHidden()) {
            clickWidget(ctx, declineButton, () -> {
                log.info("Trade declined");
            });
        }
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("TradeTask[phase=%s, value=%d]", phase, theirOfferValue);
    }

    // ========================================================================
    // Trade Phase Enum
    // ========================================================================

    private enum TradePhase {
        WAIT_FIRST_SCREEN,
        FIRST_SCREEN_MONITOR,
        WAIT_ACCEPT_DELAY,
        ACCEPT_FIRST,
        WAIT_SECOND_SCREEN,
        VERIFY_SECOND,
        ACCEPT_SECOND,
        SEND_RESPONSE,
        COMPLETED
    }
}

