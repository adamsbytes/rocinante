package com.rocinante.tasks.impl;

import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.timing.DelayProfile;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.widgets.Widget;

import java.awt.Rectangle;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Task to handle reconnection after logout (e.g., after long breaks).
 *
 * <p>This task handles the reconnection flow when the player has logged out
 * but the client is still running. It does NOT handle initial login with
 * credentials (that's handled by the launcher/post_launch.py).
 *
 * <p>Flow handled:
 * <ol>
 *   <li>Detect login state (lobby vs logged out vs logging in)</li>
 *   <li>Click "Click here to play" on lobby screen</li>
 *   <li>Handle "World is full" retry with exponential backoff</li>
 *   <li>Wait for LOGGED_IN state</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>{@code
 * // After a long break with logout
 * LoginTask loginTask = new LoginTask();
 * taskExecutor.queueTask(loginTask);
 * }</pre>
 */
@Slf4j
public class LoginTask extends AbstractTask {

    // ========================================================================
    // Widget IDs for Login Screen
    // ========================================================================

    // Login screen widget group (contains "Click here to play" etc.)
    // The login screen uses group 378 for the main content area
    private static final int LOGIN_SCREEN_GROUP = 378;
    
    // "Click here to play" button (or "CLICK HERE TO PLAY") - child varies by state
    // In the lobby after logout, this is typically visible
    private static final int CLICK_TO_PLAY_CHILD = 78;
    
    // Alternative: the large "PLAY" button area
    private static final int PLAY_BUTTON_CHILD = 73;
    
    // Login response messages (world full, etc.)
    // Widget 378:30 contains login status text
    private static final int LOGIN_RESPONSE_CHILD = 30;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Maximum retry attempts for world full.
     */
    @Getter
    @Setter
    private int maxRetries = 5;

    /**
     * Initial retry delay in milliseconds.
     */
    @Getter
    @Setter
    private long initialRetryDelayMs = 5000;

    /**
     * Maximum retry delay in milliseconds.
     */
    @Getter
    @Setter
    private long maxRetryDelayMs = 60000;

    /**
     * Backoff multiplier for retry delays.
     */
    @Getter
    @Setter
    private double backoffMultiplier = 2.0;

    // ========================================================================
    // State
    // ========================================================================

    private LoginPhase phase = LoginPhase.DETECT_STATE;
    private int retryCount = 0;
    private long currentRetryDelay;
    private boolean actionPending = false;
    private int ticksWaiting = 0;
    private static final int MAX_WAIT_TICKS = 100; // ~60 seconds

    // ========================================================================
    // Constructor
    // ========================================================================

    public LoginTask() {
        this.timeout = Duration.ofMinutes(5);
        this.currentRetryDelay = initialRetryDelayMs;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        // Can execute when at login screen or connection lost
        GameState state = ctx.getClient().getGameState();
        return state == GameState.LOGIN_SCREEN 
            || state == GameState.CONNECTION_LOST
            || state == GameState.LOGGING_IN;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (actionPending) {
            return;
        }

        Client client = ctx.getClient();
        GameState gameState = client.getGameState();

        // Check if already logged in
        if (gameState == GameState.LOGGED_IN) {
            log.info("Successfully logged in");
            complete();
            return;
        }

        // Check if currently logging in
        if (gameState == GameState.LOGGING_IN || gameState == GameState.LOADING) {
            ticksWaiting++;
            if (ticksWaiting > MAX_WAIT_TICKS) {
                log.warn("Login taking too long, failing");
                fail("Login timeout");
            }
            return;
        }

        switch (phase) {
            case DETECT_STATE:
                detectLoginState(ctx);
                break;
            case CLICK_PLAY:
                clickPlay(ctx);
                break;
            case WAIT_LOGIN:
                waitForLogin(ctx);
                break;
            case HANDLE_WORLD_FULL:
                handleWorldFull(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Detect State
    // ========================================================================

    private void detectLoginState(TaskContext ctx) {
        Client client = ctx.getClient();
        GameState state = client.getGameState();

        log.debug("Detecting login state: gameState={}, loginIndex={}", 
                state, client.getLoginIndex());

        if (state == GameState.LOGGED_IN) {
            complete();
            return;
        }

        if (state == GameState.LOGIN_SCREEN) {
            int loginIndex = client.getLoginIndex();
            
            // loginIndex values:
            // 0 = Main login screen (title)
            // 2 = Username/password entry
            // 4 = Authenticator entry
            // 24 = "Click here to play" lobby (after credentials saved)
            
            if (loginIndex == 24 || loginIndex == 0) {
                // At lobby or title - click to play
                log.info("At login lobby (loginIndex={}), clicking to play", loginIndex);
                phase = LoginPhase.CLICK_PLAY;
                return;
            }
            
            // Check for login response message (world full, etc.)
            Widget responseWidget = client.getWidget(LOGIN_SCREEN_GROUP, LOGIN_RESPONSE_CHILD);
            if (responseWidget != null && !responseWidget.isHidden()) {
                String text = responseWidget.getText();
                if (text != null) {
                    text = text.toLowerCase();
                    if (text.contains("full") || text.contains("busy")) {
                        log.info("World is full, will retry");
                        phase = LoginPhase.HANDLE_WORLD_FULL;
                        return;
                    }
                    if (text.contains("error") || text.contains("disabled")) {
                        log.error("Login error: {}", responseWidget.getText());
                        fail("Login error: " + responseWidget.getText());
                        return;
                    }
                }
            }
            
            // Default: try to click play button
            phase = LoginPhase.CLICK_PLAY;
        } else if (state == GameState.CONNECTION_LOST) {
            log.info("Connection lost, waiting for login screen");
            ticksWaiting++;
            if (ticksWaiting > 20) {
                // Connection lost for too long
                log.warn("Connection lost for too long");
                phase = LoginPhase.CLICK_PLAY; // Try clicking anyway
            }
        }
    }

    // ========================================================================
    // Phase: Click Play
    // ========================================================================

    private void clickPlay(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // Try to find the play button
        Widget playButton = client.getWidget(LOGIN_SCREEN_GROUP, CLICK_TO_PLAY_CHILD);
        if (playButton == null || playButton.isHidden()) {
            playButton = client.getWidget(LOGIN_SCREEN_GROUP, PLAY_BUTTON_CHILD);
        }

        if (playButton == null || playButton.isHidden()) {
            log.debug("Play button not found, retrying next tick");
            ticksWaiting++;
            if (ticksWaiting > 10) {
                // Try clicking in the center of the screen as fallback
                log.info("Play button not found, clicking center screen");
                clickCenterScreen(ctx);
            }
            return;
        }

        Rectangle bounds = playButton.getBounds();
        if (bounds == null || bounds.width <= 0) {
            log.warn("Invalid play button bounds");
            return;
        }

        log.info("Clicking play button");
        actionPending = true;
        ticksWaiting = 0;

        ctx.getHumanTimer().sleep(DelayProfile.REACTION)
            .thenCompose(v -> ctx.getMouseController().click(bounds))
            .thenRun(() -> {
                actionPending = false;
                phase = LoginPhase.WAIT_LOGIN;
            })
            .exceptionally(e -> {
                actionPending = false;
                log.error("Failed to click play button", e);
                return null;
            });
    }

    private void clickCenterScreen(TaskContext ctx) {
        // Click center of screen as fallback (works for "Click here to play")
        int centerX = 383; // 765/2
        int centerY = 280; // Approximate center of play area

        actionPending = true;
        ticksWaiting = 0;

        ctx.getHumanTimer().sleep(DelayProfile.REACTION)
            .thenCompose(v -> ctx.getMouseController().moveToCanvas(centerX, centerY))
            .thenCompose(v -> ctx.getMouseController().click())
            .thenRun(() -> {
                actionPending = false;
                phase = LoginPhase.WAIT_LOGIN;
            })
            .exceptionally(e -> {
                actionPending = false;
                log.error("Failed to click center screen", e);
                return null;
            });
    }

    // ========================================================================
    // Phase: Wait Login
    // ========================================================================

    private void waitForLogin(TaskContext ctx) {
        Client client = ctx.getClient();
        GameState state = client.getGameState();

        if (state == GameState.LOGGED_IN) {
            log.info("Login successful");
            complete();
            return;
        }

        if (state == GameState.LOGGING_IN || state == GameState.LOADING) {
            // Good, login is in progress
            ticksWaiting = 0;
            return;
        }

        // Still at login screen - check for errors
        if (state == GameState.LOGIN_SCREEN) {
            Widget responseWidget = client.getWidget(LOGIN_SCREEN_GROUP, LOGIN_RESPONSE_CHILD);
            if (responseWidget != null && !responseWidget.isHidden()) {
                String text = responseWidget.getText();
                if (text != null) {
                    text = text.toLowerCase();
                    if (text.contains("full") || text.contains("busy")) {
                        log.info("World is full");
                        phase = LoginPhase.HANDLE_WORLD_FULL;
                        return;
                    }
                }
            }
        }

        ticksWaiting++;
        if (ticksWaiting > 30) {
            log.warn("Login not progressing, retrying");
            phase = LoginPhase.DETECT_STATE;
            ticksWaiting = 0;
        }
    }

    // ========================================================================
    // Phase: Handle World Full
    // ========================================================================

    private void handleWorldFull(TaskContext ctx) {
        retryCount++;
        
        if (retryCount > maxRetries) {
            log.error("Max retry attempts ({}) exceeded for world full", maxRetries);
            fail("World full - max retries exceeded");
            return;
        }

        log.info("World full, retry {}/{} after {}ms", 
                retryCount, maxRetries, currentRetryDelay);

        actionPending = true;

        ctx.getHumanTimer().sleep(currentRetryDelay)
            .thenRun(() -> {
                actionPending = false;
                // Increase delay for next retry (exponential backoff)
                currentRetryDelay = Math.min(
                        (long) (currentRetryDelay * backoffMultiplier),
                        maxRetryDelayMs);
                phase = LoginPhase.CLICK_PLAY;
                ticksWaiting = 0;
            });
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Override
    protected void resetImpl() {
        phase = LoginPhase.DETECT_STATE;
        retryCount = 0;
        currentRetryDelay = initialRetryDelayMs;
        actionPending = false;
        ticksWaiting = 0;
    }

    @Override
    public String getDescription() {
        return "LoginTask[phase=" + phase + ", retries=" + retryCount + "]";
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum LoginPhase {
        DETECT_STATE,
        CLICK_PLAY,
        WAIT_LOGIN,
        HANDLE_WORLD_FULL
    }
}

