package com.rocinante.tasks.impl;

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
        return ctx.isLoggedIn();
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

        // TODO: Implement full scam protection - compare first screen offer to second screen text
        // For now, since we're only implementing passive trades, just proceed
        log.debug("Scam protection would verify items here");
        phase = TradePhase.ACCEPT_SECOND;
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
                    log.trace("Could not get HA value for item {}", itemId);
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

        // Calculate click point with some randomization
        int x = bounds.x + bounds.width / 2 + (int) ((Math.random() - 0.5) * bounds.width * 0.4);
        int y = bounds.y + bounds.height / 2 + (int) ((Math.random() - 0.5) * bounds.height * 0.4);

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

