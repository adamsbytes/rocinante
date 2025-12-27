package com.rocinante.behavior;

import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.VarClientInt;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Humanized logout behavior handler.
 * 
 * Per REQUIREMENTS.md Section 3.4.5 (Logout Pattern Humanization):
 * Instant logout after dangerous events = bot flag. Add human-like logout behavior.
 * 
 * Logout Timing:
 * - After combat escape: Wait 2-15 seconds (catching breath, checking stats)
 * - After level-up: Wait 5-20 seconds (reading level-up text, checking new unlocks)
 * - At break time: Don't logout immediately at fatigue threshold; 20% chance to continue 1-5 more actions
 * - Random logout probability: 0.1% chance per minute during safe activities
 * 
 * Logout Methods:
 * - 60%: Direct logout button click
 * - 30%: Press ESC → click logout
 * - 10%: Click logout, wait 1-2 sec, close game window (simulating alt-tab away)
 */
@Slf4j
@Singleton
public class LogoutHandler {

    // === Logout Timing Constants ===
    
    /**
     * Minimum delay after combat escape before logout (ms).
     */
    private static final long MIN_POST_COMBAT_DELAY_MS = 2000;
    
    /**
     * Maximum delay after combat escape before logout (ms).
     */
    private static final long MAX_POST_COMBAT_DELAY_MS = 15000;
    
    /**
     * Minimum delay after level-up before logout (ms).
     */
    private static final long MIN_POST_LEVELUP_DELAY_MS = 5000;
    
    /**
     * Maximum delay after level-up before logout (ms).
     */
    private static final long MAX_POST_LEVELUP_DELAY_MS = 20000;
    
    /**
     * Probability to continue 1-5 more actions at break threshold.
     */
    private static final double CONTINUE_AT_BREAK_PROBABILITY = 0.20;
    
    /**
     * Minimum extra actions when continuing at break.
     */
    private static final int MIN_EXTRA_ACTIONS = 1;
    
    /**
     * Maximum extra actions when continuing at break.
     */
    private static final int MAX_EXTRA_ACTIONS = 5;
    
    /**
     * Random logout probability per minute during safe activities.
     */
    private static final double RANDOM_LOGOUT_CHANCE_PER_MINUTE = 0.001;
    
    // === Logout Method Weights ===
    
    /**
     * Weight for direct logout button click.
     */
    private static final double DIRECT_LOGOUT_WEIGHT = 0.60;
    
    /**
     * Weight for ESC -> logout.
     */
    private static final double ESC_LOGOUT_WEIGHT = 0.30;
    
    /**
     * Weight for click logout -> wait -> simulate alt-tab.
     */
    private static final double ALTTAB_LOGOUT_WEIGHT = 0.10;
    
    // === Alt-Tab Simulation Delay ===
    
    /**
     * Minimum delay for alt-tab simulation (ms).
     */
    private static final long MIN_ALTTAB_DELAY_MS = 1000;
    
    /**
     * Maximum delay for alt-tab simulation (ms).
     */
    private static final long MAX_ALTTAB_DELAY_MS = 2000;

    // === Dependencies ===
    
    private final Client client;
    private final Randomization randomization;
    private final HumanTimer humanTimer;
    
    @Setter
    @Nullable
    private RobotMouseController mouseController;
    
    @Setter
    @Nullable
    private RobotKeyboardController keyboardController;
    
    @Setter
    @Nullable
    private PlayerProfile playerProfile;
    
    // === State ===
    
    /**
     * Whether a logout is currently in progress.
     */
    @Getter
    private final AtomicBoolean loggingOut = new AtomicBoolean(false);
    
    /**
     * Last time random logout check was performed.
     */
    private Instant lastRandomLogoutCheck = Instant.now();
    
    /**
     * Number of extra actions remaining if continuing at break.
     */
    @Getter
    private int extraActionsRemaining = 0;
    
    /**
     * Context of the current/pending logout.
     */
    @Getter
    private LogoutContext currentContext = LogoutContext.NORMAL;

    @Inject
    public LogoutHandler(Client client, Randomization randomization, HumanTimer humanTimer) {
        this.client = client;
        this.randomization = randomization;
        this.humanTimer = humanTimer;
        log.info("LogoutHandler initialized");
    }

    // ========================================================================
    // Logout Context
    // ========================================================================

    /**
     * Context for logout timing.
     */
    public enum LogoutContext {
        /** Normal logout (break time, session end) */
        NORMAL,
        /** Logout after escaping combat */
        POST_COMBAT_ESCAPE,
        /** Logout after level-up */
        POST_LEVEL_UP,
        /** Random IRL interruption */
        RANDOM_IRL,
        /** Emergency logout (dangerous situation) */
        EMERGENCY
    }

    /**
     * Method used for logout.
     */
    public enum LogoutMethod {
        /** Direct click on logout button */
        DIRECT_CLICK,
        /** Press ESC then click logout */
        ESC_THEN_CLICK,
        /** Click logout, wait, then simulate leaving */
        ALT_TAB_AWAY
    }

    // ========================================================================
    // Logout Timing
    // ========================================================================

    /**
     * Get the appropriate delay before logout based on context.
     * 
     * @param context the logout context
     * @return delay in milliseconds
     */
    public long getLogoutDelay(LogoutContext context) {
        switch (context) {
            case POST_COMBAT_ESCAPE:
                return randomization.uniformRandomLong(MIN_POST_COMBAT_DELAY_MS, MAX_POST_COMBAT_DELAY_MS);
            case POST_LEVEL_UP:
                return randomization.uniformRandomLong(MIN_POST_LEVELUP_DELAY_MS, MAX_POST_LEVELUP_DELAY_MS);
            case EMERGENCY:
                // Still add minimal human delay even in emergency
                return randomization.uniformRandomLong(500, 2000);
            case RANDOM_IRL:
                // Quick logout for IRL interruption
                return randomization.uniformRandomLong(500, 3000);
            case NORMAL:
            default:
                // Normal delay range
                return randomization.uniformRandomLong(1000, 5000);
        }
    }

    /**
     * Check if we should continue with more actions at break threshold.
     * 20% chance to continue 1-5 more actions before logout.
     * 
     * @return true if should continue, false if should logout now
     */
    public boolean shouldContinueAtBreak() {
        if (randomization.chance(CONTINUE_AT_BREAK_PROBABILITY)) {
            extraActionsRemaining = randomization.uniformRandomInt(MIN_EXTRA_ACTIONS, MAX_EXTRA_ACTIONS);
            log.debug("Continuing {} more actions before logout", extraActionsRemaining);
            return true;
        }
        extraActionsRemaining = 0;
        return false;
    }

    /**
     * Decrement extra actions and check if logout should proceed.
     * 
     * @return true if extra actions completed and should logout now
     */
    public boolean decrementExtraActions() {
        if (extraActionsRemaining > 0) {
            extraActionsRemaining--;
            log.trace("Extra actions remaining: {}", extraActionsRemaining);
            return extraActionsRemaining <= 0;
        }
        return true;
    }

    // ========================================================================
    // Random Logout Check
    // ========================================================================

    /**
     * Check for random logout during safe activities.
     * 0.1% chance per minute.
     * 
     * @param isSafeActivity whether current activity is safe
     * @return true if random logout should occur
     */
    public boolean checkRandomLogout(boolean isSafeActivity) {
        if (!isSafeActivity) {
            return false;
        }
        
        Instant now = Instant.now();
        Duration elapsed = Duration.between(lastRandomLogoutCheck, now);
        
        // Only check once per minute
        if (elapsed.toMinutes() < 1) {
            return false;
        }
        
        lastRandomLogoutCheck = now;
        
        if (randomization.chance(RANDOM_LOGOUT_CHANCE_PER_MINUTE)) {
            log.info("Random IRL logout triggered");
            currentContext = LogoutContext.RANDOM_IRL;
            return true;
        }
        
        return false;
    }

    // ========================================================================
    // Logout Method Selection
    // ========================================================================

    /**
     * Select the logout method to use.
     * 
     * @return selected LogoutMethod
     */
    public LogoutMethod selectLogoutMethod() {
        double roll = randomization.uniformRandom(0, 1);
        
        if (roll < DIRECT_LOGOUT_WEIGHT) {
            return LogoutMethod.DIRECT_CLICK;
        } else if (roll < DIRECT_LOGOUT_WEIGHT + ESC_LOGOUT_WEIGHT) {
            return LogoutMethod.ESC_THEN_CLICK;
        } else {
            return LogoutMethod.ALT_TAB_AWAY;
        }
    }

    // ========================================================================
    // Logout Execution
    // ========================================================================

    /**
     * Execute logout with humanized behavior.
     * 
     * @param context the logout context
     * @return CompletableFuture that completes when logout is initiated
     */
    public CompletableFuture<Boolean> executeLogout(LogoutContext context) {
        if (loggingOut.get()) {
            log.warn("Logout already in progress");
            return CompletableFuture.completedFuture(false);
        }
        
        if (client.getGameState() != GameState.LOGGED_IN) {
            log.debug("Not logged in, skipping logout");
            return CompletableFuture.completedFuture(true);
        }
        
        loggingOut.set(true);
        currentContext = context;
        
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        // Execute on background thread
        CompletableFuture.runAsync(() -> {
            try {
                // Get appropriate delay
                long delay = getLogoutDelay(context);
                log.debug("Executing logout with context {} after {}ms delay", context, delay);
                
                // Wait the appropriate delay
                humanTimer.sleepSync(delay);
                
                // Select and execute logout method
                LogoutMethod method = selectLogoutMethod();
                log.debug("Using logout method: {}", method);
                
                boolean success = executeLogoutMethod(method);
                
                // Record logout in profile
                if (success && playerProfile != null) {
                    playerProfile.recordLogout();
                }
                
                future.complete(success);
            } catch (Exception e) {
                log.error("Logout failed", e);
                future.completeExceptionally(e);
            } finally {
                loggingOut.set(false);
            }
        });
        
        return future;
    }

    /**
     * Execute a specific logout method.
     * 
     * @param method the logout method
     * @return true if logout was successful
     */
    private boolean executeLogoutMethod(LogoutMethod method) {
        try {
            switch (method) {
                case DIRECT_CLICK:
                    return executeDirectLogout();
                case ESC_THEN_CLICK:
                    return executeEscLogout();
                case ALT_TAB_AWAY:
                    return executeAltTabLogout();
                default:
                    return executeDirectLogout();
            }
        } catch (Exception e) {
            log.error("Logout method {} failed", method, e);
            return false;
        }
    }

    /**
     * Execute direct logout by clicking the logout button.
     */
    private boolean executeDirectLogout() throws Exception {
        // Open the logout tab if not already open
        if (!openLogoutTab()) {
            return false;
        }
        
        // Find and click logout button
        return clickLogoutButton();
    }

    /**
     * Execute logout via ESC -> click logout.
     */
    private boolean executeEscLogout() throws Exception {
        if (keyboardController == null) {
            log.warn("No keyboard controller, falling back to direct logout");
            return executeDirectLogout();
        }
        
        // Press ESC to close any open interfaces and potentially show logout
        keyboardController.pressEscape().get();
        humanTimer.sleepSync(randomization.uniformRandomLong(200, 500));
        
        // Open logout tab
        if (!openLogoutTab()) {
            return false;
        }
        
        // Click logout
        return clickLogoutButton();
    }

    /**
     * Execute logout with alt-tab simulation (click logout, wait, then leave).
     */
    private boolean executeAltTabLogout() throws Exception {
        // Open logout tab
        if (!openLogoutTab()) {
            return false;
        }
        
        // Click logout
        boolean clicked = clickLogoutButton();
        if (!clicked) {
            return false;
        }
        
        // Wait 1-2 seconds (simulating distraction)
        long waitTime = randomization.uniformRandomLong(MIN_ALTTAB_DELAY_MS, MAX_ALTTAB_DELAY_MS);
        humanTimer.sleepSync(waitTime);
        
        // In a real alt-tab scenario, we'd move mouse away or similar
        // For now, just complete the logout
        log.debug("Alt-tab simulation complete");
        return true;
    }

    /**
     * Open the logout tab.
     */
    private boolean openLogoutTab() throws Exception {
        // Try to open logout tab via the interface
        // The logout tab is typically accessed via the "Door" icon
        Widget logoutTab = client.getWidget(WidgetInfo.RESIZABLE_VIEWPORT_LOGOUT_TAB);
        if (logoutTab == null) {
            logoutTab = client.getWidget(WidgetInfo.FIXED_VIEWPORT_LOGOUT_TAB);
        }
        
        if (logoutTab != null && !logoutTab.isHidden() && mouseController != null) {
            Rectangle bounds = logoutTab.getBounds();
            if (bounds != null && bounds.width > 0) {
                Point clickPoint = new Point(
                    bounds.x + bounds.width / 2,
                    bounds.y + bounds.height / 2
                );
                mouseController.clickAt(clickPoint.x, clickPoint.y).get();
                humanTimer.sleepSync(randomization.uniformRandomLong(300, 600));
                return true;
            }
        }
        
        log.debug("Could not find logout tab widget");
        return true; // Proceed anyway, button might be visible
    }

    /**
     * Click the logout button.
     */
    private boolean clickLogoutButton() throws Exception {
        // Try the main logout button
        Widget logoutButton = client.getWidget(WidgetInfo.LOGOUT_BUTTON);
        
        if (logoutButton != null && !logoutButton.isHidden() && mouseController != null) {
            Rectangle bounds = logoutButton.getBounds();
            if (bounds != null && bounds.width > 0) {
                // Add some variance to click position
                int clickX = bounds.x + randomization.uniformRandomInt(5, bounds.width - 5);
                int clickY = bounds.y + randomization.uniformRandomInt(5, bounds.height - 5);
                
                mouseController.clickAt(clickX, clickY).get();
                log.debug("Clicked logout button");
                return true;
            }
        }
        
        // Fallback: try world switcher logout
        Widget worldSwitcherLogout = client.getWidget(182, 8); // World switcher logout button
        if (worldSwitcherLogout != null && !worldSwitcherLogout.isHidden() && mouseController != null) {
            Rectangle bounds = worldSwitcherLogout.getBounds();
            if (bounds != null && bounds.width > 0) {
                int clickX = bounds.x + randomization.uniformRandomInt(5, bounds.width - 5);
                int clickY = bounds.y + randomization.uniformRandomInt(5, bounds.height - 5);
                
                mouseController.clickAt(clickX, clickY).get();
                log.debug("Clicked world switcher logout button");
                return true;
            }
        }
        
        log.warn("Could not find logout button");
        return false;
    }

    // ========================================================================
    // State Management
    // ========================================================================

    /**
     * Reset handler state. Called on session start.
     */
    public void reset() {
        loggingOut.set(false);
        lastRandomLogoutCheck = Instant.now();
        extraActionsRemaining = 0;
        currentContext = LogoutContext.NORMAL;
    }

    /**
     * Check if logout is currently in progress.
     */
    public boolean isLoggingOut() {
        return loggingOut.get();
    }

    /**
     * Set the logout context for the next logout.
     * 
     * @param context the context
     */
    public void setContext(LogoutContext context) {
        this.currentContext = context;
    }
}

