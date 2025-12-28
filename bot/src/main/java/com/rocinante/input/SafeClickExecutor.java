package com.rocinante.input;

import com.rocinante.input.EntityOverlapChecker.OverlapResult;
import com.rocinante.timing.DelayProfile;
import com.rocinante.timing.HumanTimer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;
import net.runelite.api.ObjectComposition;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Executes clicks with pre-click validation to prevent misclicks on overlapping entities.
 *
 * <p>When an NPC, player, or object is "in the way" of the intended target, this executor
 * will either:
 * <ul>
 *   <li>Find an alternative click point on the target that isn't blocked</li>
 *   <li>Use right-click menu selection to guarantee clicking the correct target</li>
 * </ul>
 *
 * <p>The strategy selection is randomized (with configurable weights) for human-like behavior.
 *
 * <p>Usage:
 * <pre>{@code
 * // Click an object safely
 * safeClickExecutor.clickObject(targetObject, "Use", "Raw shrimps")
 *     .thenAccept(success -> log.info("Click result: {}", success));
 *
 * // Click an NPC safely
 * safeClickExecutor.clickNpc(targetNpc, "Talk-to")
 *     .thenAccept(success -> log.info("Click result: {}", success));
 * }</pre>
 */
@Slf4j
@Singleton
public class SafeClickExecutor {

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Maximum attempts to find a clear click point before falling back to menu.
     */
    private static final int MAX_CLEAR_POINT_ATTEMPTS = 8;

    /**
     * Probability of using right-click menu when blocked (vs finding alternative point).
     * Range: 0.0 to 1.0
     */
    private static final double MENU_STRATEGY_PROBABILITY = 0.4;

    /**
     * Probability of using right-click menu even when NOT blocked (paranoid mode).
     * This adds unpredictability to click patterns.
     */
    private static final double PARANOID_MENU_PROBABILITY = 0.05;

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final Client client;
    private final EntityOverlapChecker overlapChecker;
    private final RobotMouseController mouseController;
    private final MenuHelper menuHelper;
    private final HumanTimer humanTimer;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public SafeClickExecutor(
            Client client,
            EntityOverlapChecker overlapChecker,
            RobotMouseController mouseController,
            MenuHelper menuHelper,
            HumanTimer humanTimer) {
        this.client = client;
        this.overlapChecker = overlapChecker;
        this.mouseController = mouseController;
        this.menuHelper = menuHelper;
        this.humanTimer = humanTimer;
        log.info("SafeClickExecutor initialized");
    }

    // ========================================================================
    // Public API - Object Clicks
    // ========================================================================

    /**
     * Click a TileObject safely with automatic overlap detection.
     *
     * @param target the object to click
     * @param menuAction the menu action (e.g., "Use", "Mine", "Chop")
     * @param itemName optional item name for "Use X on Y" actions (e.g., "Raw shrimps")
     * @return CompletableFuture completing with true if click succeeded
     */
    public CompletableFuture<Boolean> clickObject(TileObject target, String menuAction, @Nullable String itemName) {
        if (target == null) {
            return CompletableFuture.completedFuture(false);
        }

        Shape clickbox = target.getClickbox();
        if (clickbox == null) {
            log.warn("Target object has no clickbox");
            return CompletableFuture.completedFuture(false);
        }

        Rectangle bounds = clickbox.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            log.warn("Target object has invalid bounds");
            return CompletableFuture.completedFuture(false);
        }

        // Calculate initial click point
        Point initialPoint = ClickPointCalculator.getGaussianClickPoint(bounds);
        if (initialPoint == null || !clickbox.contains(initialPoint)) {
            initialPoint = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        }

        // Pre-click validation
        OverlapResult overlap = overlapChecker.checkPointForObject(initialPoint, target);

        // Sometimes use menu even when clear (adds unpredictability)
        boolean forceMenu = ThreadLocalRandom.current().nextDouble() < PARANOID_MENU_PROBABILITY;

        if (!overlap.hasBlockingEntity() && !forceMenu) {
            // Clear to click - use left click
            log.debug("Click point clear, using left-click at ({}, {})", initialPoint.x, initialPoint.y);
            return executeLeftClick(initialPoint);
        }

        if (overlap.hasBlockingEntity()) {
            log.debug("Click blocked by {}", overlap.getBlockerDescription());
        }

        // Blocked or paranoid mode - choose strategy
        ClickStrategy strategy = chooseStrategy(overlap.hasBlockingEntity());
        log.debug("Using {} strategy", strategy);

        switch (strategy) {
            case FIND_CLEAR_POINT:
                return executeFindClearPointStrategy(target, menuAction, itemName, getObjectName(target));

            case RIGHT_CLICK_MENU:
            default:
                return executeMenuStrategy(bounds, menuAction, itemName, getObjectName(target));
        }
    }

    /**
     * Click a TileObject with a simple action (no item involved).
     */
    public CompletableFuture<Boolean> clickObject(TileObject target, String menuAction) {
        return clickObject(target, menuAction, null);
    }

    // ========================================================================
    // Public API - NPC Clicks
    // ========================================================================

    /**
     * Click an NPC safely with automatic overlap detection.
     *
     * @param target the NPC to click
     * @param menuAction the menu action (e.g., "Talk-to", "Attack", "Trade")
     * @return CompletableFuture completing with true if click succeeded
     */
    public CompletableFuture<Boolean> clickNpc(NPC target, String menuAction) {
        return clickNpc(target, menuAction, null);
    }

    /**
     * Click an NPC safely with automatic overlap detection.
     *
     * @param target the NPC to click
     * @param menuAction the menu action (e.g., "Use")
     * @param itemName optional item name for "Use X on Y" actions
     * @return CompletableFuture completing with true if click succeeded
     */
    public CompletableFuture<Boolean> clickNpc(NPC target, String menuAction, @Nullable String itemName) {
        if (target == null) {
            return CompletableFuture.completedFuture(false);
        }

        Shape hull = target.getConvexHull();
        if (hull == null) {
            log.warn("Target NPC has no convex hull");
            return CompletableFuture.completedFuture(false);
        }

        Rectangle bounds = hull.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            log.warn("Target NPC has invalid bounds");
            return CompletableFuture.completedFuture(false);
        }

        // Calculate initial click point
        Point initialPoint = ClickPointCalculator.getGaussianClickPoint(bounds);
        if (initialPoint == null || !hull.contains(initialPoint)) {
            initialPoint = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        }

        // Pre-click validation
        OverlapResult overlap = overlapChecker.checkPointForNpc(initialPoint, target);

        boolean forceMenu = ThreadLocalRandom.current().nextDouble() < PARANOID_MENU_PROBABILITY;

        if (!overlap.hasBlockingEntity() && !forceMenu) {
            log.debug("NPC click point clear, using left-click at ({}, {})", initialPoint.x, initialPoint.y);
            return executeLeftClick(initialPoint);
        }

        if (overlap.hasBlockingEntity()) {
            log.debug("NPC click blocked by {}", overlap.getBlockerDescription());
        }

        ClickStrategy strategy = chooseStrategy(overlap.hasBlockingEntity());
        log.debug("Using {} strategy for NPC", strategy);

        switch (strategy) {
            case FIND_CLEAR_POINT:
                return executeFindClearPointStrategyNpc(target, menuAction, itemName);

            case RIGHT_CLICK_MENU:
            default:
                return executeMenuStrategy(bounds, menuAction, itemName, target.getName());
        }
    }

    // ========================================================================
    // Public API - Ground Item Clicks
    // ========================================================================

    /**
     * Click a ground item safely with automatic overlap detection.
     *
     * @param canvasPoint the canvas point where the ground item is located
     * @param itemName the item name for menu matching
     * @return CompletableFuture completing with true if click succeeded
     */
    public CompletableFuture<Boolean> clickGroundItem(Point canvasPoint, String itemName) {
        if (canvasPoint == null) {
            return CompletableFuture.completedFuture(false);
        }

        // Create a small hitbox around the ground item point
        Rectangle hitbox = new Rectangle(
                canvasPoint.x - 16, 
                canvasPoint.y - 16, 
                32, 32
        );

        // Calculate initial click point using humanized offset
        Point initialPoint = ClickPointCalculator.getGaussianClickPoint(hitbox);
        if (initialPoint == null) {
            initialPoint = canvasPoint;
        }

        // Pre-click validation for ground items
        OverlapResult overlap = overlapChecker.checkPointAtLocation(initialPoint);

        boolean forceMenu = ThreadLocalRandom.current().nextDouble() < PARANOID_MENU_PROBABILITY;

        if (!overlap.hasBlockingEntity() && !forceMenu) {
            log.debug("Ground item click point clear, using left-click at ({}, {})", initialPoint.x, initialPoint.y);
            return executeLeftClick(initialPoint);
        }

        if (overlap.hasBlockingEntity()) {
            log.debug("Ground item click blocked by {}", overlap.getBlockerDescription());
        }

        ClickStrategy strategy = chooseStrategy(overlap.hasBlockingEntity());
        log.debug("Using {} strategy for ground item", strategy);

        switch (strategy) {
            case FIND_CLEAR_POINT:
                return executeFindClearPointStrategyGroundItem(hitbox, itemName);

            case RIGHT_CLICK_MENU:
            default:
                return executeMenuStrategy(hitbox, "Take", null, itemName);
        }
    }

    /**
     * Try to find a clear click point for ground item, falling back to menu if none found.
     */
    private CompletableFuture<Boolean> executeFindClearPointStrategyGroundItem(
            Rectangle hitbox, String itemName) {

        // Try to find a clear point within the hitbox
        for (int attempt = 0; attempt < MAX_CLEAR_POINT_ATTEMPTS; attempt++) {
            Point testPoint = ClickPointCalculator.getGaussianClickPoint(hitbox);
            if (testPoint != null) {
                OverlapResult testOverlap = overlapChecker.checkPointAtLocation(testPoint);
                if (!testOverlap.hasBlockingEntity()) {
                    log.debug("Found clear ground item point at ({}, {}), using left-click", testPoint.x, testPoint.y);
                    return executeLeftClick(testPoint);
                }
            }
        }

        log.debug("No clear ground item point found, falling back to right-click menu");
        return executeMenuStrategy(hitbox, "Take", null, itemName);
    }

    // ========================================================================
    // Strategy Selection
    // ========================================================================

    /**
     * Choose a strategy for handling blocked clicks.
     * Randomized for human-like behavior.
     */
    private ClickStrategy chooseStrategy(boolean isBlocked) {
        double roll = ThreadLocalRandom.current().nextDouble();

        if (isBlocked) {
            // When blocked, higher chance of using menu (more reliable)
            return roll < MENU_STRATEGY_PROBABILITY ? ClickStrategy.RIGHT_CLICK_MENU : ClickStrategy.FIND_CLEAR_POINT;
        } else {
            // When not blocked but using paranoid mode, always use menu
            return ClickStrategy.RIGHT_CLICK_MENU;
        }
    }

    private enum ClickStrategy {
        FIND_CLEAR_POINT,
        RIGHT_CLICK_MENU
    }

    // ========================================================================
    // Strategy Execution
    // ========================================================================

    /**
     * Execute left-click at a point.
     */
    private CompletableFuture<Boolean> executeLeftClick(Point point) {
        return mouseController.moveToCanvas(point.x, point.y)
                .thenCompose(v -> mouseController.click())
                .thenApply(v -> true)
                .exceptionally(e -> {
                    log.error("Left-click failed", e);
                    return false;
                });
    }

    /**
     * Try to find a clear click point, falling back to menu if none found.
     */
    private CompletableFuture<Boolean> executeFindClearPointStrategy(
            TileObject target, String menuAction, @Nullable String itemName, @Nullable String targetName) {

        Point clearPoint = overlapChecker.findClearClickPoint(target, MAX_CLEAR_POINT_ATTEMPTS);

        if (clearPoint != null) {
            log.debug("Found clear point at ({}, {}), using left-click", clearPoint.x, clearPoint.y);
            return executeLeftClick(clearPoint);
        }

        // No clear point found - fall back to menu
        log.debug("No clear point found, falling back to right-click menu");
        Shape clickbox = target.getClickbox();
        Rectangle bounds = clickbox != null ? clickbox.getBounds() : null;
        if (bounds == null) {
            return CompletableFuture.completedFuture(false);
        }
        return executeMenuStrategy(bounds, menuAction, itemName, targetName);
    }

    /**
     * Try to find a clear click point for NPC, falling back to menu if none found.
     */
    private CompletableFuture<Boolean> executeFindClearPointStrategyNpc(
            NPC target, String menuAction, @Nullable String itemName) {

        Point clearPoint = overlapChecker.findClearClickPoint(target, MAX_CLEAR_POINT_ATTEMPTS);

        if (clearPoint != null) {
            log.debug("Found clear NPC point at ({}, {}), using left-click", clearPoint.x, clearPoint.y);
            return executeLeftClick(clearPoint);
        }

        log.debug("No clear NPC point found, falling back to right-click menu");
        Shape hull = target.getConvexHull();
        Rectangle bounds = hull != null ? hull.getBounds() : null;
        if (bounds == null) {
            return CompletableFuture.completedFuture(false);
        }
        return executeMenuStrategy(bounds, menuAction, itemName, target.getName());
    }

    /**
     * Execute right-click menu selection.
     */
    private CompletableFuture<Boolean> executeMenuStrategy(
            Rectangle bounds, String menuAction, @Nullable String itemName, @Nullable String targetName) {

        // Build the full action string for menu matching
        // For "Use X on Y" actions, the menu shows "Use X -> Y" 
        String fullTarget = targetName;
        if (itemName != null && targetName != null) {
            // Menu entries for "Use item on target" show as "Use [item] -> [target]"
            fullTarget = targetName;
        }

        log.debug("Using right-click menu: action='{}', target='{}'", menuAction, fullTarget);
        return menuHelper.selectMenuEntry(bounds, menuAction, fullTarget);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Get object name from client definitions.
     */
    @Nullable
    private String getObjectName(TileObject obj) {
        if (obj == null) {
            return null;
        }
        try {
            int id = -1;
            if (obj instanceof net.runelite.api.GameObject) {
                id = ((net.runelite.api.GameObject) obj).getId();
            } else if (obj instanceof net.runelite.api.WallObject) {
                id = ((net.runelite.api.WallObject) obj).getId();
            } else if (obj instanceof net.runelite.api.GroundObject) {
                id = ((net.runelite.api.GroundObject) obj).getId();
            } else if (obj instanceof net.runelite.api.DecorativeObject) {
                id = ((net.runelite.api.DecorativeObject) obj).getId();
            }

            if (id != -1) {
                ObjectComposition def = client.getObjectDefinition(id);
                return def != null ? def.getName() : null;
            }
        } catch (Exception e) {
            log.trace("Could not get object name", e);
        }
        return null;
    }
}

