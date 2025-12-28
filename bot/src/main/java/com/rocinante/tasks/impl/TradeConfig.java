package com.rocinante.tasks.impl;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;

/**
 * Configuration for TradeTask execution.
 *
 * <p>Supports both passive trade acceptance (receiving gifts) and active trading
 * (offering items with scam protection).
 *
 * <p>Example usage - accepting random trades:
 * <pre>{@code
 * TradeConfig config = TradeConfig.builder()
 *     .sendResponse(true)
 *     .responseMessage("Ty!")
 *     .build();
 * }</pre>
 *
 * <p>Example usage - active trade with scam protection:
 * <pre>{@code
 * TradeConfig config = TradeConfig.builder()
 *     .sendResponse(false)
 *     .offeringItems(Map.of(ItemID.COINS_995, 10000))
 *     .expectedItems(Map.of(ItemID.RUNE_SCIMITAR, 1))
 *     .verifySecondScreen(true)
 *     .build();
 * }</pre>
 *
 * @see TradeTask
 */
@Getter
@Builder
public class TradeConfig {

    // ========================================================================
    // Response Configuration
    // ========================================================================

    /**
     * Whether to send a response message after completing the trade.
     * Default: false
     */
    @Builder.Default
    private final boolean sendResponse = false;

    /**
     * Message to send after trade completion (if sendResponse is true).
     * Default: "Ty!"
     */
    @Builder.Default
    private final String responseMessage = "Ty!";

    // ========================================================================
    // Item Configuration
    // ========================================================================

    /**
     * Items we are offering in the trade (item ID -> quantity).
     * Empty map means we're not offering anything (passive acceptance).
     * Default: empty (not offering anything)
     */
    @Builder.Default
    private final Map<Integer, Integer> offeringItems = Collections.emptyMap();

    /**
     * Items we expect to receive (item ID -> minimum quantity).
     * Empty map means we accept anything (for random trades/gifts).
     * Default: empty (accept anything)
     */
    @Builder.Default
    private final Map<Integer, Integer> expectedItems = Collections.emptyMap();

    // ========================================================================
    // Scam Protection
    // ========================================================================

    /**
     * Whether to verify second screen items match first screen.
     * Only relevant when offeringItems is not empty.
     * Default: true
     */
    @Builder.Default
    private final boolean verifySecondScreen = true;

    /**
     * Maximum time (in game ticks) to wait for the other player to accept.
     * After this, the trade will be declined.
     * Default: 100 ticks (~60 seconds)
     */
    @Builder.Default
    private final int maxWaitTicks = 100;

    // ========================================================================
    // Behavior Configuration  
    // ========================================================================

    /**
     * Minimum humanized delay (ms) before accepting after they accept.
     * Default: 1000ms
     */
    @Builder.Default
    private final long minAcceptDelayMs = 1000;

    /**
     * Maximum humanized delay (ms) before accepting after they accept.
     * Default: 3000ms
     */
    @Builder.Default
    private final long maxAcceptDelayMs = 3000;

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if this is a passive trade (not offering anything).
     *
     * @return true if we're not offering any items
     */
    public boolean isPassiveTrade() {
        return offeringItems.isEmpty();
    }

    /**
     * Check if we expect specific items from the trade.
     *
     * @return true if we have item expectations
     */
    public boolean hasExpectedItems() {
        return !expectedItems.isEmpty();
    }

    /**
     * Check if scam protection should be active.
     * Only applies when we're offering items.
     *
     * @return true if scam protection is enabled and relevant
     */
    public boolean shouldVerifyTrade() {
        return verifySecondScreen && !offeringItems.isEmpty();
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a config for accepting random trades/gifts.
     * Sends "Ty!" response after trade.
     *
     * @return config for passive trade acceptance
     */
    public static TradeConfig forRandomTrade() {
        return TradeConfig.builder()
                .sendResponse(true)
                .responseMessage("Ty!")
                .build();
    }

    /**
     * Create a config for declining trades (used internally).
     * Should not normally be used - TradeHandler handles decline directly.
     *
     * @return minimal config
     */
    public static TradeConfig forDecline() {
        return TradeConfig.builder()
                .sendResponse(false)
                .maxWaitTicks(0)
                .build();
    }
}

