package com.rocinante.combat;

import com.rocinante.state.PlayerState;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Player;

import java.time.Duration;
import java.time.Instant;

/**
 * Detects and tracks player stun state from various sources.
 *
 * <p>Stuns can occur from:
 * <ul>
 *   <li>Failed pickpocket attempts (Thieving)</li>
 *   <li>Combat abilities (Abyssal Sire, etc.)</li>
 *   <li>Boss mechanics (various)</li>
 *   <li>PvP spells and special attacks</li>
 * </ul>
 *
 * <p>This handler is designed to be reusable across different systems:
 * Thieving tasks, combat managers, boss handlers, etc.
 *
 * <p>Detection methods:
 * <ul>
 *   <li>Stun animation ID (1054 for thieving, others for combat)</li>
 *   <li>Stun graphic/spotanim (245 for thieving, others for combat)</li>
 *   <li>Player overhead icon (if applicable)</li>
 *   <li>Movement blocking detection</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * StunHandler stunHandler = new StunHandler();
 *
 * // In task execution loop
 * stunHandler.update(ctx);
 *
 * if (stunHandler.isStunned()) {
 *     // Wait for stun to end
 *     log.debug("Stunned, waiting {} ticks remaining", stunHandler.getRemainingStunTicks());
 *     return;
 * }
 *
 * // Safe to perform action
 * performPickpocket();
 * }</pre>
 */
@Slf4j
public class StunHandler {

    // ========================================================================
    // Stun Animation/Graphic IDs
    // ========================================================================

    /**
     * Animation ID when stunned from failed pickpocket.
     * From RuneLite AnimationID.STUNNED_THIEVING (1054).
     */
    public static final int ANIM_STUNNED_THIEVING = 1054;

    /**
     * Graphic ID (SpotanimID) for thieving stun.
     * Shows stars above player's head.
     */
    public static final int GFX_STUNNED_THIEVING = 245;

    /**
     * Alternative stun graphics (colored variants).
     */
    public static final int GFX_STUNNED_THIEVING_BLUE = 1307;
    public static final int GFX_STUNNED_THIEVING_RED = 1308;
    public static final int GFX_STUNNED_THIEVING_GREEN = 1309;

    /**
     * Generic stun graphic (from combat).
     */
    public static final int GFX_STUNNED_GENERIC = 80;

    /**
     * Blackjack stun graphic.
     */
    public static final int GFX_STUNNED_BLACKJACK = 348;

    /**
     * Default thieving stun duration in game ticks (approximately 5 seconds).
     */
    public static final int THIEVING_STUN_DURATION_TICKS = 8;

    /**
     * Default combat stun duration in game ticks.
     */
    public static final int COMBAT_STUN_DURATION_TICKS = 5;

    // ========================================================================
    // State
    // ========================================================================

    /**
     * Whether we detected a stun this tick.
     */
    @Getter
    private boolean stunned = false;

    /**
     * The type of stun detected.
     */
    @Getter
    private StunType stunType = StunType.NONE;

    /**
     * Tick when stun was detected.
     */
    private int stunStartTick = -1;

    /**
     * Expected duration of the stun in ticks.
     */
    private int expectedStunDuration = 0;

    /**
     * Time when stun was detected.
     */
    private Instant stunStartTime;

    /**
     * Total number of stuns detected in this session.
     */
    @Getter
    private int totalStunCount = 0;

    /**
     * Consecutive stuns (resets after successful action).
     */
    @Getter
    private int consecutiveStunCount = 0;

    /**
     * Last game tick we updated on.
     */
    private int lastUpdateTick = -1;

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Update stun detection state. Call this every game tick.
     *
     * @param ctx the task context
     */
    public void update(TaskContext ctx) {
        if (ctx == null || !ctx.isLoggedIn()) {
            return;
        }

        Client client = ctx.getClient();
        int currentTick = client.getTickCount();

        // Don't double-update on same tick
        if (currentTick == lastUpdateTick) {
            return;
        }
        lastUpdateTick = currentTick;

        PlayerState player = ctx.getPlayerState();
        Player localPlayer = client.getLocalPlayer();

        if (localPlayer == null) {
            return;
        }

        // Check for active stun indicators
        StunType detectedType = detectStunType(player, localPlayer);

        if (detectedType != StunType.NONE) {
            if (!stunned) {
                // New stun detected
                onStunStart(detectedType, currentTick);
            }
            // Still stunned
            stunned = true;
            stunType = detectedType;
        } else if (stunned) {
            // Check if stun should have ended based on expected duration
            int ticksSinceStun = currentTick - stunStartTick;
            if (ticksSinceStun >= expectedStunDuration) {
                onStunEnd(currentTick);
            }
            // Also check if animation/graphic cleared
            if (!isPlayerShowingStunIndicators(player, localPlayer)) {
                onStunEnd(currentTick);
            }
        }
    }

    /**
     * Check if player is currently stunned.
     *
     * @return true if stunned
     */
    public boolean isStunned() {
        return stunned;
    }

    /**
     * Get estimated remaining stun ticks.
     *
     * @param ctx the task context
     * @return remaining ticks, or 0 if not stunned
     */
    public int getRemainingStunTicks(TaskContext ctx) {
        if (!stunned || ctx == null) {
            return 0;
        }

        int currentTick = ctx.getClient().getTickCount();
        int elapsed = currentTick - stunStartTick;
        int remaining = expectedStunDuration - elapsed;
        return Math.max(0, remaining);
    }

    /**
     * Get stun duration so far.
     *
     * @return duration since stun started, or ZERO if not stunned
     */
    public Duration getStunDuration() {
        if (!stunned || stunStartTime == null) {
            return Duration.ZERO;
        }
        return Duration.between(stunStartTime, Instant.now());
    }

    /**
     * Manually clear stun state.
     * Call this after successfully completing an action that proves we're not stunned.
     */
    public void clearStun() {
        if (stunned) {
            log.debug("Stun manually cleared");
        }
        stunned = false;
        stunType = StunType.NONE;
        stunStartTick = -1;
        stunStartTime = null;
        consecutiveStunCount = 0;
    }

    /**
     * Record a successful action (resets consecutive stun counter).
     */
    public void recordSuccessfulAction() {
        consecutiveStunCount = 0;
    }

    /**
     * Reset all state (for new session/task).
     */
    public void reset() {
        stunned = false;
        stunType = StunType.NONE;
        stunStartTick = -1;
        stunStartTime = null;
        expectedStunDuration = 0;
        totalStunCount = 0;
        consecutiveStunCount = 0;
        lastUpdateTick = -1;
    }

    // ========================================================================
    // Detection Logic
    // ========================================================================

    /**
     * Detect what type of stun (if any) the player is experiencing.
     */
    private StunType detectStunType(PlayerState player, Player localPlayer) {
        // Check thieving stun animation
        if (player.isAnimating(ANIM_STUNNED_THIEVING)) {
            return StunType.THIEVING;
        }

        // Check stun graphics
        int graphicId = localPlayer.getGraphic();
        if (graphicId == GFX_STUNNED_THIEVING ||
            graphicId == GFX_STUNNED_THIEVING_BLUE ||
            graphicId == GFX_STUNNED_THIEVING_RED ||
            graphicId == GFX_STUNNED_THIEVING_GREEN) {
            return StunType.THIEVING;
        }

        if (graphicId == GFX_STUNNED_BLACKJACK) {
            return StunType.BLACKJACK;
        }

        if (graphicId == GFX_STUNNED_GENERIC) {
            return StunType.COMBAT;
        }

        return StunType.NONE;
    }

    /**
     * Check if player is showing any stun visual indicators.
     */
    private boolean isPlayerShowingStunIndicators(PlayerState player, Player localPlayer) {
        // Check animation
        if (player.isAnimating(ANIM_STUNNED_THIEVING)) {
            return true;
        }

        // Check graphic
        int graphicId = localPlayer.getGraphic();
        return graphicId == GFX_STUNNED_THIEVING ||
               graphicId == GFX_STUNNED_THIEVING_BLUE ||
               graphicId == GFX_STUNNED_THIEVING_RED ||
               graphicId == GFX_STUNNED_THIEVING_GREEN ||
               graphicId == GFX_STUNNED_BLACKJACK ||
               graphicId == GFX_STUNNED_GENERIC;
    }

    // ========================================================================
    // Event Handlers
    // ========================================================================

    private void onStunStart(StunType type, int tick) {
        stunStartTick = tick;
        stunStartTime = Instant.now();
        totalStunCount++;
        consecutiveStunCount++;

        // Set expected duration based on stun type
        switch (type) {
            case THIEVING:
            case BLACKJACK:
                expectedStunDuration = THIEVING_STUN_DURATION_TICKS;
                break;
            case COMBAT:
            default:
                expectedStunDuration = COMBAT_STUN_DURATION_TICKS;
                break;
        }

        log.debug("Stun detected: type={}, expected duration={} ticks, total stuns={}, consecutive={}",
                type, expectedStunDuration, totalStunCount, consecutiveStunCount);
    }

    private void onStunEnd(int tick) {
        int duration = tick - stunStartTick;
        log.debug("Stun ended after {} ticks (type was {})", duration, stunType);

        stunned = false;
        stunType = StunType.NONE;
        stunStartTick = -1;
        stunStartTime = null;
    }

    // ========================================================================
    // Stun Type Enum
    // ========================================================================

    /**
     * Types of stuns that can affect the player.
     */
    public enum StunType {
        /** No stun active */
        NONE,
        /** Stun from failed pickpocket attempt */
        THIEVING,
        /** Stun from blackjack knockout */
        BLACKJACK,
        /** Stun from combat (boss mechanics, PvP, etc.) */
        COMBAT
    }
}

