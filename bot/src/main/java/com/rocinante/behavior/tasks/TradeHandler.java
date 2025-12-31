package com.rocinante.behavior.tasks;

import com.rocinante.state.IronmanState;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.tasks.TaskPriority;
import com.rocinante.tasks.impl.TradeConfig;
import com.rocinante.tasks.impl.TradeTask;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles incoming trade requests from other players.
 *
 * <p>This handler:
 * <ul>
 *   <li>Listens for TRADEREQ chat messages (type 101)</li>
 *   <li>Auto-declines trades for ironman accounts</li>
 *   <li>Creates and queues TradeTask for normal accounts</li>
 *   <li>Applies rate limiting to prevent trade spam exploitation</li>
 * </ul>
 *
 * <p>Trade detection is based on the chat message format:
 * "{PlayerName} wishes to trade with you."
 *
 * <p>Architecture notes:
 * <ul>
 *   <li>Registered via Guice in BehaviorModule</li>
 *   <li>EventBus subscription handles chat events</li>
 *   <li>Tasks queued with BEHAVIORAL priority (interruptible)</li>
 * </ul>
 *
 * @see TradeTask
 * @see TradeConfig
 */
@Slf4j
@Singleton
public class TradeHandler {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Pattern to extract player name from trade request message.
     * Format: "{Name} wishes to trade with you."
     */
    private static final Pattern TRADE_REQUEST_PATTERN = Pattern.compile(
            "^(.+) wishes to trade with you\\.$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Minimum time between accepting trades (to prevent spam exploitation).
     * Default: 10 seconds
     */
    private static final long MIN_TRADE_INTERVAL_MS = 10_000;

    /**
     * Maximum trades per session to accept (safety limit).
     * Default: 100 trades
     */
    private static final int MAX_TRADES_PER_SESSION = 100;

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final Client client;
    private final IronmanState ironmanState;

    /**
     * Task executor for queuing trade tasks.
     * Set via setter to avoid circular dependency.
     */
    @Nullable
    private TaskExecutor taskExecutor;

    // ========================================================================
    // State
    // ========================================================================

    /**
     * Whether trade handling is enabled.
     */
    @Getter
    private volatile boolean enabled = true;

    /**
     * Last time a trade was accepted (for rate limiting).
     */
    private Instant lastTradeTime;

    /**
     * Number of trades accepted this session.
     */
    @Getter
    private int tradesAcceptedThisSession = 0;

    /**
     * Name of the last player who requested a trade.
     */
    @Getter
    @Nullable
    private String lastTradeRequestFrom;

    /**
     * Whether a trade is currently in progress.
     */
    @Getter
    private volatile boolean tradeInProgress = false;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public TradeHandler(Client client, IronmanState ironmanState) {
        this.client = client;
        this.ironmanState = ironmanState;
        log.info("TradeHandler initialized");
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Set the task executor for queuing trade tasks.
     * Called during plugin initialization to avoid circular dependency.
     *
     * @param taskExecutor the task executor
     */
    public void setTaskExecutor(TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    /**
     * Enable or disable trade handling.
     *
     * @param enabled true to enable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("Trade handling {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Reset session statistics.
     * Called at session start.
     */
    public void resetSession() {
        tradesAcceptedThisSession = 0;
        lastTradeTime = null;
        lastTradeRequestFrom = null;
        tradeInProgress = false;
        log.debug("TradeHandler session reset");
    }

    // ========================================================================
    // Event Handling
    // ========================================================================

    /**
     * Handle incoming chat messages to detect trade requests.
     */
    @Subscribe
    public void onChatMessage(ChatMessage event) {
        // Only process trade request messages
        if (event.getType() != ChatMessageType.TRADEREQ) {
            return;
        }

        // Check if we're logged in
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        // Check if handling is enabled
        if (!enabled) {
            log.debug("Trade request ignored - handling disabled");
            return;
        }

        // Parse the trade request
        String message = event.getMessage();
        Matcher matcher = TRADE_REQUEST_PATTERN.matcher(message);

        if (!matcher.matches()) {
            log.debug("Trade message didn't match pattern: {}", message);
            return;
        }

        String playerName = matcher.group(1);
        log.info("Trade request received from: {}", playerName);
        lastTradeRequestFrom = playerName;

        // Handle the trade request
        handleTradeRequest(playerName);
    }

    /**
     * Handle a parsed trade request.
     *
     * @param playerName the name of the player requesting trade
     */
    private void handleTradeRequest(String playerName) {
        // Check 1: Ironman accounts cannot trade
        if (ironmanState.isIronman()) {
            log.info("Auto-declining trade from {} - ironman account", playerName);
            // Ironman can't accept trades anyway, no action needed
            return;
        }

        // Check 2: Rate limiting
        if (!isRateLimitOk()) {
            log.debug("Trade request from {} ignored - rate limited", playerName);
            return;
        }

        // Check 3: Session limit
        if (tradesAcceptedThisSession >= MAX_TRADES_PER_SESSION) {
            log.warn("Trade request from {} ignored - session limit reached", playerName);
            return;
        }

        // Check 4: Already processing a trade
        if (tradeInProgress) {
            log.debug("Trade request from {} ignored - trade already in progress", playerName);
            return;
        }

        // Check 5: Task executor available
        if (taskExecutor == null) {
            log.warn("Trade request from {} ignored - task executor not available", playerName);
            return;
        }

        // Create and queue the trade task
        createAndQueueTradeTask(playerName);
    }

    /**
     * Check if rate limiting allows accepting a new trade.
     */
    private boolean isRateLimitOk() {
        if (lastTradeTime == null) {
            return true;
        }

        long elapsed = Instant.now().toEpochMilli() - lastTradeTime.toEpochMilli();
        return elapsed >= MIN_TRADE_INTERVAL_MS;
    }

    /**
     * Create and queue a TradeTask for accepting an incoming trade.
     */
    private void createAndQueueTradeTask(String playerName) {
        // Create config for random trade acceptance
        TradeConfig config = TradeConfig.builder()
                .sendResponse(true)
                .responseMessage("Ty!")
                .build();

        // Create the trade task
        TradeTask tradeTask = new TradeTask(config)
                .withDescription("Accept trade from " + playerName);

        // Mark trade as in progress
        tradeInProgress = true;

        // Queue the task with BEHAVIORAL priority
        taskExecutor.queueTask(tradeTask, TaskPriority.BEHAVIORAL);
        log.info("Queued trade task for player: {}", playerName);

        // Update statistics
        lastTradeTime = Instant.now();
        tradesAcceptedThisSession++;

        // Set up completion callback (trade task will call markTradeComplete when done)
    }

    /**
     * Mark the current trade as complete.
     * Called by TradeTask when it finishes (success or failure).
     */
    public void markTradeComplete() {
        tradeInProgress = false;
        log.debug("Trade marked as complete");
    }

    // ========================================================================
    // Status & Debugging
    // ========================================================================

    /**
     * Get a summary of trade handler state.
     *
     * @return summary string
     */
    public String getSummary() {
        return String.format(
                "TradeHandler[enabled=%s, inProgress=%s, sessionTrades=%d, lastRequest=%s]",
                enabled,
                tradeInProgress,
                tradesAcceptedThisSession,
                lastTradeRequestFrom != null ? lastTradeRequestFrom : "none"
        );
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

