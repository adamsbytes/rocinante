package com.rocinante.tasks;

import com.rocinante.input.CameraController;
import com.rocinante.input.ClickPointCalculator;
import com.rocinante.input.MouseCameraCoupler;
import com.rocinante.util.ObjectCollections;
import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized helper for entity interactions (objects, NPCs, ground items).
 * 
 * Provides:
 * - Fire-and-forget camera rotation (non-blocking, human-like)
 * - Clickbox waiting with configurable timeout
 * - Fallback click point calculation when clickbox unavailable
 * - Consistent logging and error handling
 * 
 * Usage:
 * <pre>{@code
 * InteractionHelper helper = new InteractionHelper(ctx);
 * helper.startCameraRotation(targetPosition);
 * 
 * // On each tick:
 * Point clickPoint = helper.getClickPoint(targetObject);
 * if (clickPoint != null) {
 *     // Proceed with click
 * } else if (helper.isWaitingForClickbox()) {
 *     // Still waiting, try again next tick
 * } else {
 *     // Failed after max retries
 * }
 * }</pre>
 */
@Slf4j
public class InteractionHelper {

    // ========================================================================
    // Configuration Constants
    // ========================================================================

    /**
     * Maximum ticks to wait for clickbox to become available.
     */
    public static final int DEFAULT_MAX_CLICKBOX_WAIT_TICKS = 8;

    /**
     * Tick at which to trigger camera rotation if clickbox still unavailable.
     */
    public static final int CAMERA_RETRY_TRIGGER_TICK = 3;

    /**
     * Maximum camera rotation retries.
     */
    public static final int MAX_CAMERA_RETRIES = 3;

    // ========================================================================
    // State
    // ========================================================================

    private final TaskContext ctx;
    private final AtomicBoolean cameraRotationInProgress = new AtomicBoolean(false);
    private final AtomicInteger clickboxWaitTicks = new AtomicInteger(0);
    private final AtomicInteger cameraRetryCount = new AtomicInteger(0);
    private int maxClickboxWaitTicks = DEFAULT_MAX_CLICKBOX_WAIT_TICKS;

    // ========================================================================
    // Constructor
    // ========================================================================

    public InteractionHelper(TaskContext ctx) {
        this.ctx = ctx;
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Set maximum ticks to wait for clickbox.
     * 
     * @param ticks maximum wait ticks
     * @return this for chaining
     */
    public InteractionHelper withMaxClickboxWait(int ticks) {
        this.maxClickboxWaitTicks = ticks;
        return this;
    }

    /**
     * Reset state for a new interaction.
     */
    public void reset() {
        cameraRotationInProgress.set(false);
        clickboxWaitTicks.set(0);
        cameraRetryCount.set(0);
    }

    // ========================================================================
    // Camera Rotation
    // ========================================================================

    /**
     * Start camera rotation toward target position (fire-and-forget).
     * Does not block - rotation happens asynchronously like a human holding arrow keys.
     * 
     * @param targetPosition world position to rotate toward
     */
    public void startCameraRotation(@Nullable WorldPoint targetPosition) {
        if (targetPosition == null) {
            return;
        }
        
        // First, try to calculate where the object currently is on screen
        // and rotate based on that (more accurate than world position)
        Client client = ctx.getClient();
        if (client != null) {
            LocalPoint localPoint = LocalPoint.fromWorld(client, targetPosition);
            if (localPoint != null) {
                net.runelite.api.Point canvasPoint = net.runelite.api.Perspective.localToCanvas(
                        client, localPoint, client.getPlane(), 0);
                if (canvasPoint != null) {
                    // Use 3D game area bounds, not full canvas - objects are only
                    // clickable within the 3D viewport, not in UI areas
                    int gameAreaX = client.getViewportXOffset();
                    int gameAreaY = client.getViewportYOffset();
                    int gameAreaWidth = client.getViewportWidth();
                    int gameAreaHeight = client.getViewportHeight();
                    
                    // Fallback for fixed mode
                    if (gameAreaWidth <= 0 || gameAreaHeight <= 0) {
                        gameAreaX = 4;
                        gameAreaY = 4;
                        gameAreaWidth = 512;
                        gameAreaHeight = 334;
                    }
                    
                    startCameraRotationTowardCanvasPoint(
                            canvasPoint.getX(), canvasPoint.getY(),
                            gameAreaX, gameAreaY, 
                            gameAreaX + gameAreaWidth, gameAreaY + gameAreaHeight);
                    return;
                }
            }
        }

        // Fallback to world-position based rotation if we couldn't get canvas point
        startCameraRotationByWorldPosition(targetPosition);
    }
    
    /**
     * Threshold for using arrow keys vs mouse-based rotation.
     * Rotations >= this use arrow keys (more human-like for larger turns).
     * Rotations < this use mouse drag (more precise for small adjustments).
     */
    private static final int ARROW_KEY_THRESHOLD_DEGREES = 20;

    /**
     * Rotate camera based on where the object currently is relative to the 3D game area.
     * Objects outside the game area (in UI regions) need camera rotation to bring them into view.
     * 
     * Uses arrow keys for normal rotations (>= 20°) and mouse for small adjustments.
     */
    private void startCameraRotationTowardCanvasPoint(int canvasX, int canvasY, 
            int gameAreaLeft, int gameAreaTop, int gameAreaRight, int gameAreaBottom) {
        CameraController cameraController = ctx.getCameraController();
        if (cameraController == null) {
            log.debug("No camera controller available");
            return;
        }
        
        // Calculate how far outside the 3D game area the object is
        int offscreenAmount = 0;
        boolean rotateLeft = false;
        
        if (canvasX < gameAreaLeft) {
            // Object is off-screen LEFT - rotate camera RIGHT to bring it into view
            // Camera rotating RIGHT (clockwise) shifts objects LEFT on screen,
            // which brings objects from the LEFT side into the viewport
            offscreenAmount = gameAreaLeft - canvasX;
            rotateLeft = false;  // rotate RIGHT
        } else if (canvasX >= gameAreaRight) {
            // Object is off-screen RIGHT - rotate camera LEFT to bring it into view
            // Camera rotating LEFT (counter-clockwise) shifts objects RIGHT on screen,
            // which brings objects from the RIGHT side into the viewport
            offscreenAmount = canvasX - gameAreaRight;
            rotateLeft = true;  // rotate LEFT
        } else {
            // Object is on screen horizontally - check vertical
            if (canvasY < gameAreaTop || canvasY >= gameAreaBottom) {
                // Off-screen vertically - just rotate a bit to try to help
                offscreenAmount = 50;
                rotateLeft = ctx.getRandomization().chance(0.5);
            } else {
                // Object is actually within 3D game area - no rotation needed
                log.debug("Object at ({}, {}) is within game area ({},{} to {},{}), no rotation needed", 
                        canvasX, canvasY, gameAreaLeft, gameAreaTop, gameAreaRight, gameAreaBottom);
                return;
            }
        }
        
        int gameAreaWidth = gameAreaRight - gameAreaLeft;
        
        // Calculate rotation amount based on how far off-screen
        // For reference: game area is ~512 pixels wide, so objects can be thousands of pixels off-screen
        // when the camera is facing the wrong direction entirely (need 180° rotation)
        int gameAreaHalfWidth = gameAreaWidth / 2;
        double rotationFraction = Math.min(3.0, (double) offscreenAmount / gameAreaHalfWidth);
        // Scale: 0 -> 45°, 1 (half-width) -> 90°, 2 (full-width) -> 135°, 3+ -> 180°
        int rotationDegrees = (int) (45 + rotationFraction * 45);
        
        // Add some randomization
        rotationDegrees += (int) ctx.getRandomization().gaussianRandom(0, 10);
        rotationDegrees = Math.max(30, Math.min(210, rotationDegrees));
        
        log.debug("Object at canvas ({}, {}), off-screen by {} pixels, rotating {} by {}° using {}",
                canvasX, canvasY, offscreenAmount, rotateLeft ? "LEFT" : "RIGHT", rotationDegrees,
                rotationDegrees >= ARROW_KEY_THRESHOLD_DEGREES ? "arrow keys" : "mouse");
        
        if (cameraRotationInProgress.compareAndSet(false, true)) {
            CompletableFuture<Void> rotateFuture;
            
            if (rotationDegrees >= ARROW_KEY_THRESHOLD_DEGREES) {
                // Use arrow keys for normal/large rotations (more human-like)
                CameraController.Direction direction = rotateLeft 
                        ? CameraController.Direction.LEFT 
                        : CameraController.Direction.RIGHT;
                
                // Calculate hold duration based on rotation needed
                // At MEDIUM speed (60°/sec), estimate duration needed
                long estimatedDurationMs = (long) (rotationDegrees / 60.0 * 1000) + 200; // +200ms buffer
                estimatedDurationMs = Math.max(500, Math.min(3000, estimatedDurationMs));
                
                rotateFuture = cameraController.performCameraHold(direction, estimatedDurationMs, rotationDegrees + 15);
            } else {
                // Use mouse for small adjustments (more precise)
                double rotationDegreesWithSign = rotateLeft ? -rotationDegrees : rotationDegrees;
                rotateFuture = cameraController.rotateBy(rotationDegreesWithSign, 0);
            }
            
            rotateFuture.whenComplete((v, ex) -> {
                cameraRotationInProgress.set(false);
                if (ex != null) {
                    log.debug("Camera rotation completed with error: {}", ex.getMessage());
                } else {
                    log.debug("Camera rotation completed successfully");
                }
            });
        }
    }
    
    /**
     * Fallback: rotate camera using world position (less accurate).
     */
    private void startCameraRotationByWorldPosition(@Nullable WorldPoint targetPosition) {
        if (targetPosition == null) {
            return;
        }

        MouseCameraCoupler coupler = ctx.getMouseCameraCoupler();
        if (coupler == null) {
            log.debug("No camera coupler available");
            return;
        }

        if (cameraRotationInProgress.compareAndSet(false, true)) {
            CompletableFuture<Void> rotateFuture = coupler.ensureTargetVisible(targetPosition);
            
            rotateFuture.whenComplete((v, ex) -> {
                cameraRotationInProgress.set(false);
                if (ex != null) {
                    log.debug("Camera rotation completed with error: {}", ex.getMessage());
                } else {
                    log.debug("Camera rotation completed successfully");
                }
            });
            
            log.debug("Started world-position-based camera rotation toward {}", targetPosition);
        }
    }

    /**
     * Check if camera rotation is currently in progress.
     * 
     * @return true if rotating
     */
    public boolean isCameraRotating() {
        return cameraRotationInProgress.get();
    }

    // ========================================================================
    // Click Point Resolution for TileObjects
    // ========================================================================

    /**
     * Result of click point resolution.
     */
    public static class ClickPointResult {
        public final Point point;
        public final boolean shouldWait;
        public final boolean shouldRotateCamera;
        public final String reason;

        private ClickPointResult(Point point, boolean shouldWait, boolean shouldRotateCamera, String reason) {
            this.point = point;
            this.shouldWait = shouldWait;
            this.shouldRotateCamera = shouldRotateCamera;
            this.reason = reason;
        }

        public static ClickPointResult success(Point point) {
            return new ClickPointResult(point, false, false, "clickbox available");
        }

        public static ClickPointResult waiting(String reason) {
            return new ClickPointResult(null, true, false, reason);
        }

        public static ClickPointResult needsRotation(String reason) {
            return new ClickPointResult(null, true, true, reason);
        }

        public static ClickPointResult fallback(Point point, String reason) {
            return new ClickPointResult(point, false, false, reason);
        }

        public static ClickPointResult failed(String reason) {
            return new ClickPointResult(null, false, false, reason);
        }

        public boolean hasPoint() {
            return point != null;
        }
    }

    /**
     * Get click point for a TileObject with smart waiting and fallback logic.
     * 
     * PROACTIVE approach: Check if clickbox is fully visible BEFORE generating click point.
     * If not fully visible, rotate camera first. Never generate clicks outside viewport.
     * 
     * Call this each tick until you get a point or shouldWait is false.
     * 
     * @param obj the tile object to get click point for
     * @param worldPoint the object's world position (for fallback calculation)
     * @return result indicating success, wait, or failure
     */
    public ClickPointResult getClickPointForObject(TileObject obj, @Nullable WorldPoint worldPoint) {
        if (obj == null) {
            return ClickPointResult.failed("object is null");
        }

        // Try to get clickbox
        Shape clickableArea = obj.getClickbox();
        
        if (clickableArea != null) {
            Rectangle bounds = clickableArea.getBounds();
            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                
                // PROACTIVE: Check if ENTIRE clickbox is in viewport BEFORE generating click
                // If not fully visible, rotate camera first - never plan clicks outside viewport
                Rectangle viewport = getViewportBounds();
                if (!isClickboxFullyVisible(bounds, viewport)) {
                    log.debug("Clickbox bounds {} not fully in viewport {} - rotating camera first",
                            bounds, viewport);
                    
                    if (cameraRetryCount.get() < MAX_CAMERA_RETRIES) {
                        cameraRetryCount.incrementAndGet();
                        // Rotate to center the clickbox in the viewport
                        rotateToCenterClickbox(bounds, viewport);
                        return ClickPointResult.needsRotation("clickbox not fully visible, centering camera");
                    }
                    return ClickPointResult.failed("clickbox not fully visible after max camera rotations");
                }
                
                // Clickbox is fully visible - now safe to generate click point
                int objectId = obj.getId();
                double stdDev = ObjectCollections.requiresPreciseClick(objectId)
                        ? ClickPointCalculator.PRECISE_STD_DEV
                        : ClickPointCalculator.DEFAULT_STD_DEV;
                
                // Generate click point biased toward current mouse position (lazy human behavior)
                Point mousePos = getCurrentMouseCanvasPosition();
                
                log.debug("Generating click point for object {}: bounds={}, stdDev={}, mousePos={}", 
                        objectId, bounds, stdDev, mousePos);
                
                Point point = generateBiasedClickPoint(bounds, stdDev, mousePos);
                
                // Sanity check: point MUST be within bounds
                if (!bounds.contains(point.x, point.y)) {
                    log.error("BUG: Generated click point ({}, {}) is OUTSIDE bounds {}!", 
                            point.x, point.y, bounds);
                    // Fallback to exact center
                    point = new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                }
                
                log.debug("Final click point for object {}: ({}, {})", objectId, point.x, point.y);
                
                clickboxWaitTicks.set(0); // Reset for next interaction
                return ClickPointResult.success(point);
            }
        }

        // Clickbox not available - handle waiting/retry logic
        int waitTicks = clickboxWaitTicks.incrementAndGet();

        // If camera is still rotating, keep waiting
        if (cameraRotationInProgress.get()) {
            log.trace("Waiting for clickbox (tick {}/{}, camera rotating)", waitTicks, maxClickboxWaitTicks);
            return ClickPointResult.waiting("camera rotating");
        }

        // Trigger camera rotation after a few ticks if still no clickbox
        if (waitTicks == CAMERA_RETRY_TRIGGER_TICK && cameraRetryCount.get() < MAX_CAMERA_RETRIES) {
            cameraRetryCount.incrementAndGet();
            log.debug("Triggering camera rotation to reveal clickbox (attempt {})", cameraRetryCount.get());
            if (worldPoint != null) {
                startCameraRotation(worldPoint);
            }
            return ClickPointResult.needsRotation("triggering camera rotation");
        }

        // Still have time to wait
        if (waitTicks < maxClickboxWaitTicks) {
            log.debug("Clickbox not available, waiting (tick {}/{})", waitTicks, maxClickboxWaitTicks);
            return ClickPointResult.waiting("object may still be loading");
        }

        // Max wait exceeded - try fallback
        Point fallbackPoint = calculateFallbackClickPoint(obj, worldPoint);
        if (fallbackPoint != null) {
            log.debug("Using fallback click point at ({}, {})", fallbackPoint.x, fallbackPoint.y);
            return ClickPointResult.fallback(fallbackPoint, "using tile-based fallback");
        }

        // Fallback failed (object not visible on screen) - need camera rotation
        if (cameraRetryCount.get() < MAX_CAMERA_RETRIES) {
            cameraRetryCount.incrementAndGet();
            log.debug("Object not visible after {} ticks, rotating camera (attempt {})", 
                    waitTicks, cameraRetryCount.get());
            clickboxWaitTicks.set(0); // Reset wait counter for another cycle
            if (worldPoint != null) {
                startCameraRotation(worldPoint);
            }
            return ClickPointResult.needsRotation("object not visible, rotating camera");
        }

        return ClickPointResult.failed("object not visible after " + cameraRetryCount.get() + " camera rotations");
    }

    /**
     * Calculate fallback click point from object's tile position.
     * Returns null if the point would be off-screen (object not visible).
     */
    @Nullable
    private Point calculateFallbackClickPoint(TileObject obj, @Nullable WorldPoint worldPoint) {
        Client client = ctx.getClient();
        if (client == null) {
            return null;
        }

        WorldPoint wp = worldPoint;
        if (wp == null) {
            wp = getObjectWorldPoint(obj);
        }
        if (wp == null) {
            return null;
        }

        LocalPoint localPoint = LocalPoint.fromWorld(client, wp);
        if (localPoint == null) {
            return null;
        }

        net.runelite.api.Point canvasPoint = net.runelite.api.Perspective.localToCanvas(
                client, localPoint, client.getPlane(), 0);

        if (canvasPoint == null) {
            return null;
        }

        // Validate point is within the 3D game area (not just canvas) - objects can only 
        // be clicked within the 3D viewport, not in UI areas like chat box or inventory
        int x = canvasPoint.getX();
        int y = canvasPoint.getY();
        
        // Get 3D game area bounds - this is where objects are RENDERED and CLICKABLE
        int gameAreaX = client.getViewportXOffset();
        int gameAreaY = client.getViewportYOffset();
        int gameAreaWidth = client.getViewportWidth();
        int gameAreaHeight = client.getViewportHeight();
        
        // Fallback for fixed mode if viewport dimensions are invalid
        if (gameAreaWidth <= 0 || gameAreaHeight <= 0) {
            gameAreaX = 4;
            gameAreaY = 4;
            gameAreaWidth = 512;
            gameAreaHeight = 334;
        }
        
        int gameAreaRight = gameAreaX + gameAreaWidth;
        int gameAreaBottom = gameAreaY + gameAreaHeight;
        
        // Point must be within 3D game area (where objects are clickable)
        if (x < gameAreaX || x >= gameAreaRight || y < gameAreaY || y >= gameAreaBottom) {
            log.debug("Fallback point ({}, {}) is outside 3D game area ({},{} to {},{}), object not visible",
                    x, y, gameAreaX, gameAreaY, gameAreaRight, gameAreaBottom);
            return null;
        }

        // Add Gaussian randomization
        Randomization rand = ctx.getRandomization();
        int offsetX = (int) rand.gaussianRandom(0, 15);
        int offsetY = (int) rand.gaussianRandom(0, 15);
        
        // Clamp to 3D game area bounds (with margin)
        x = Math.max(gameAreaX + 10, Math.min(gameAreaRight - 10, x + offsetX));
        y = Math.max(gameAreaY + 10, Math.min(gameAreaBottom - 10, y + offsetY));

        return new Point(x, y);
    }

    /**
     * Get world point from a TileObject.
     */
    @Nullable
    private WorldPoint getObjectWorldPoint(TileObject obj) {
        if (obj instanceof net.runelite.api.GameObject) {
            return ((net.runelite.api.GameObject) obj).getWorldLocation();
        }
        if (obj instanceof net.runelite.api.WallObject) {
            return ((net.runelite.api.WallObject) obj).getWorldLocation();
        }
        if (obj instanceof net.runelite.api.GroundObject) {
            return ((net.runelite.api.GroundObject) obj).getWorldLocation();
        }
        if (obj instanceof net.runelite.api.DecorativeObject) {
            return ((net.runelite.api.DecorativeObject) obj).getWorldLocation();
        }
        return null;
    }

    // ========================================================================
    // Proactive Camera Centering for Clickbox Visibility
    // ========================================================================

    /**
     * Get the viewport bounds (3D game area, not full canvas).
     */
    private Rectangle getViewportBounds() {
        Client client = ctx.getClient();
        if (client == null) {
            return new Rectangle(4, 4, 512, 334); // Fixed mode fallback
        }
        
        int x = client.getViewportXOffset();
        int y = client.getViewportYOffset();
        int w = client.getViewportWidth();
        int h = client.getViewportHeight();
        
        if (w <= 0 || h <= 0) {
            return new Rectangle(4, 4, 512, 334); // Fixed mode fallback
        }
        
        return new Rectangle(x, y, w, h);
    }

    /**
     * Check if the entire clickbox is within the viewport.
     * We want the WHOLE clickbox visible so any click point we generate will be valid.
     * 
     * @param clickbox the clickbox bounds
     * @param viewport the viewport bounds
     * @return true if clickbox is fully contained within viewport
     */
    private boolean isClickboxFullyVisible(Rectangle clickbox, Rectangle viewport) {
        // Add a small margin so we're not clicking right at the edge
        int margin = 5;
        Rectangle safeViewport = new Rectangle(
                viewport.x + margin,
                viewport.y + margin,
                viewport.width - 2 * margin,
                viewport.height - 2 * margin);
        
        return safeViewport.contains(clickbox);
    }

    /**
     * Rotate the camera to center the clickbox in the viewport.
     * This is called BEFORE generating a click point, ensuring clicks will be valid.
     * 
     * @param clickbox the clickbox bounds to center
     * @param viewport the viewport bounds
     */
    private void rotateToCenterClickbox(Rectangle clickbox, Rectangle viewport) {
        CameraController cameraController = ctx.getCameraController();
        if (cameraController == null) {
            log.debug("No camera controller available for clickbox centering");
            return;
        }
        
        // Determine which edge(s) of the clickbox are outside the viewport
        boolean leftOutside = clickbox.x < viewport.x;
        boolean rightOutside = clickbox.x + clickbox.width > viewport.x + viewport.width;
        boolean topOutside = clickbox.y < viewport.y;
        boolean bottomOutside = clickbox.y + clickbox.height > viewport.y + viewport.height;
        
        // Calculate how much of the clickbox is outside on each side
        int leftOverhang = leftOutside ? viewport.x - clickbox.x : 0;
        int rightOverhang = rightOutside ? (clickbox.x + clickbox.width) - (viewport.x + viewport.width) : 0;
        
        log.debug("Clickbox {} vs viewport {}: leftOut={}, rightOut={}, topOut={}, bottomOut={}, leftOverhang={}, rightOverhang={}",
                clickbox, viewport, leftOutside, rightOutside, topOutside, bottomOutside, leftOverhang, rightOverhang);
        
        // Determine rotation direction based on which side has more overhang
        // If left edge is outside viewport: rotate camera RIGHT to bring it in
        // If right edge is outside viewport: rotate camera LEFT to bring it in
        if (leftOverhang > 0 || rightOverhang > 0) {
            boolean rotateLeft = rightOverhang > leftOverhang;
            int overhang = Math.max(leftOverhang, rightOverhang);
            
            // Calculate rotation: more overhang = more rotation needed
            // Rough estimate: 1 pixel overhang ≈ 0.5 degrees rotation
            int rotationDegrees = Math.max(20, Math.min(90, overhang / 2));
            rotationDegrees += (int) ctx.getRandomization().gaussianRandom(0, 5);
            
            log.debug("Rotating {} by {}° to center clickbox (overhang: {}px)", 
                    rotateLeft ? "LEFT" : "RIGHT", rotationDegrees, overhang);
            
            if (cameraRotationInProgress.compareAndSet(false, true)) {
                CompletableFuture<Void> rotateFuture;
                if (rotationDegrees >= ARROW_KEY_THRESHOLD_DEGREES) {
                    // Use arrow keys for larger rotations
                    CameraController.Direction direction = rotateLeft 
                            ? CameraController.Direction.LEFT 
                            : CameraController.Direction.RIGHT;
                    // Calculate hold duration based on rotation needed (~60°/sec)
                    long estimatedDurationMs = (long) (rotationDegrees / 60.0 * 1000) + 200;
                    estimatedDurationMs = Math.max(500, Math.min(3000, estimatedDurationMs));
                    rotateFuture = cameraController.performCameraHold(direction, estimatedDurationMs, rotationDegrees + 10);
                } else {
                    // Use mouse for smaller adjustments
                    double rotationDegreesWithSign = rotateLeft ? -rotationDegrees : rotationDegrees;
                    rotateFuture = cameraController.rotateBy(rotationDegreesWithSign, 0);
                }
                rotateFuture
                        .orTimeout(5, TimeUnit.SECONDS)
                        .whenComplete((v, ex) -> {
                            cameraRotationInProgress.set(false);
                            if (ex != null) {
                                log.warn("Clickbox centering rotation failed: {}", ex.getMessage());
                            }
                        });
            }
        } else if (topOutside || bottomOutside) {
            // Vertical overhang only - do a small random rotation to try to help
            log.debug("Clickbox has vertical overhang only, doing small corrective rotation");
            int rotationDegrees = 30 + (int) ctx.getRandomization().gaussianRandom(0, 10);
            boolean rotateLeft = ctx.getRandomization().chance(0.5);
            
            if (cameraRotationInProgress.compareAndSet(false, true)) {
                double rotationDegreesWithSign = rotateLeft ? -rotationDegrees : rotationDegrees;
                cameraController.rotateBy(rotationDegreesWithSign, 0)
                        .orTimeout(5, TimeUnit.SECONDS)
                        .whenComplete((v, ex) -> cameraRotationInProgress.set(false));
            }
        } else {
            // Clickbox is actually fully visible (shouldn't reach here normally)
            log.debug("Clickbox {} is actually fully within viewport {}", clickbox, viewport);
        }
    }

    /**
     * Get current mouse position in canvas coordinates.
     * Returns null if position cannot be determined.
     */
    @Nullable
    private Point getCurrentMouseCanvasPosition() {
        try {
            java.awt.PointerInfo pointerInfo = java.awt.MouseInfo.getPointerInfo();
            if (pointerInfo == null) {
                return null;
            }
            java.awt.Point screenPos = pointerInfo.getLocation();
            
            // Convert screen coordinates to canvas coordinates
            Client client = ctx.getClient();
            if (client == null || client.getCanvas() == null) {
                return null;
            }
            
            java.awt.Point canvasLocation = client.getCanvas().getLocationOnScreen();
            int canvasX = screenPos.x - canvasLocation.x;
            int canvasY = screenPos.y - canvasLocation.y;
            
            return new Point(canvasX, canvasY);
        } catch (Exception e) {
            // Canvas might not be displayable, etc.
            return null;
        }
    }

    /**
     * Generate a click point within the clickbox, biased toward the current mouse position.
     * Humans are lazy and tend to click the part of an object closest to their cursor.
     * 
     * @param bounds the clickbox bounds
     * @param stdDev standard deviation as percentage (0.15 = 15% of dimension)
     * @param mousePos current mouse position (nullable)
     * @return click point GUARANTEED to be within bounds
     */
    private Point generateBiasedClickPoint(Rectangle bounds, double stdDev, @Nullable Point mousePos) {
        Randomization rand = ctx.getRandomization();
        
        // Default center of bounds
        double centerX = bounds.getCenterX();
        double centerY = bounds.getCenterY();
        
        // If we have mouse position and it's reasonably close, bias toward it
        // (humans are lazy and click the part of an object closest to their cursor)
        if (mousePos != null) {
            double distToMouse = Math.hypot(mousePos.x - centerX, mousePos.y - centerY);
            
            // Only apply bias if mouse is within reasonable distance (prevents extreme bias)
            if (distToMouse < 200) {
                // Moderate bias factor - people don't click exactly at edges
                double biasFactor = 0.2;
                
                // Calculate direction from bounds center to mouse
                double dirX = mousePos.x - centerX;
                double dirY = mousePos.y - centerY;
                
                // Clamp the bias to at most 20% of dimensions (keeps point near center)
                double maxBiasX = bounds.width * 0.2;
                double maxBiasY = bounds.height * 0.2;
                
                double biasX = Math.max(-maxBiasX, Math.min(maxBiasX, dirX * biasFactor));
                double biasY = Math.max(-maxBiasY, Math.min(maxBiasY, dirY * biasFactor));
                
                centerX += biasX;
                centerY += biasY;
            }
        }
        
        // Generate gaussian point around the (potentially biased) center
        // CRITICAL: stdDev is a percentage (0.15 = 15%), must multiply by dimension!
        double offsetX = rand.gaussianRandom(0, bounds.width * stdDev);
        double offsetY = rand.gaussianRandom(0, bounds.height * stdDev);
        
        double x = centerX + offsetX;
        double y = centerY + offsetY;
        
        // HARD CLAMP: Click point MUST be within bounds with small margin for safety
        int margin = 3;
        int clampedX = (int) Math.max(bounds.x + margin, Math.min(bounds.x + bounds.width - margin - 1, x));
        int clampedY = (int) Math.max(bounds.y + margin, Math.min(bounds.y + bounds.height - margin - 1, y));
        
        // Debug log to help diagnose issues
        if (log.isTraceEnabled()) {
            log.trace("Generated click point: center=({}, {}), bias=({}, {}), offset=({}, {}), final=({}, {}), bounds={}",
                    (int)bounds.getCenterX(), (int)bounds.getCenterY(),
                    (int)(centerX - bounds.getCenterX()), (int)(centerY - bounds.getCenterY()),
                    (int)offsetX, (int)offsetY,
                    clampedX, clampedY, bounds);
        }
        
        return new Point(clampedX, clampedY);
    }

    // ========================================================================
    // Click Point Resolution for NPCs
    // ========================================================================

    /**
     * Get click point for an NPC with smart waiting and fallback logic.
     * 
     * @param npc the NPC to get click point for
     * @return result indicating success, wait, or failure
     */
    public ClickPointResult getClickPointForNpc(NPC npc) {
        if (npc == null || npc.isDead()) {
            return ClickPointResult.failed("NPC is null or dead");
        }

        // Try to get convex hull
        Shape convexHull = npc.getConvexHull();

        if (convexHull != null) {
            Rectangle bounds = convexHull.getBounds();
            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                
                // PROACTIVE: Check if ENTIRE clickbox is in viewport BEFORE generating click
                Rectangle viewport = getViewportBounds();
                if (!isClickboxFullyVisible(bounds, viewport)) {
                    log.debug("NPC clickbox bounds {} not fully in viewport {} - rotating camera first",
                            bounds, viewport);
                    
                    if (cameraRetryCount.get() < MAX_CAMERA_RETRIES) {
                        cameraRetryCount.incrementAndGet();
                        rotateToCenterClickbox(bounds, viewport);
                        return ClickPointResult.needsRotation("NPC clickbox not fully visible, centering camera");
                    }
                    return ClickPointResult.failed("NPC clickbox not fully visible after max camera rotations");
                }
                
                // Clickbox is fully visible - safe to generate click point
                Point mousePos = getCurrentMouseCanvasPosition();
                
                log.debug("Generating click point for NPC {}: bounds={}, mousePos={}", 
                        npc.getName(), bounds, mousePos);
                
                Point point = generateBiasedClickPoint(bounds, ClickPointCalculator.DEFAULT_STD_DEV, mousePos);
                
                // Sanity check: point MUST be within bounds
                if (!bounds.contains(point.x, point.y)) {
                    log.error("BUG: Generated NPC click point ({}, {}) is OUTSIDE bounds {}!", 
                            point.x, point.y, bounds);
                    // Fallback to exact center
                    point = new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                }
                
                log.debug("Final click point for NPC {}: ({}, {})", npc.getName(), point.x, point.y);
                
                clickboxWaitTicks.set(0);
                return ClickPointResult.success(point);
            }
        }

        // Convex hull not available - handle waiting/retry logic
        int waitTicks = clickboxWaitTicks.incrementAndGet();
        WorldPoint npcPosition = npc.getWorldLocation();

        if (cameraRotationInProgress.get()) {
            log.trace("Waiting for NPC convex hull (tick {}/{}, camera rotating)", waitTicks, maxClickboxWaitTicks);
            return ClickPointResult.waiting("camera rotating");
        }

        if (waitTicks == CAMERA_RETRY_TRIGGER_TICK && cameraRetryCount.get() < MAX_CAMERA_RETRIES) {
            cameraRetryCount.incrementAndGet();
            log.debug("Triggering camera rotation to reveal NPC (attempt {})", cameraRetryCount.get());
            startCameraRotation(npcPosition);
            return ClickPointResult.needsRotation("triggering camera rotation");
        }

        if (waitTicks < maxClickboxWaitTicks) {
            log.debug("NPC convex hull not available, waiting (tick {}/{})", waitTicks, maxClickboxWaitTicks);
            return ClickPointResult.waiting("NPC may still be loading");
        }

        // Max wait exceeded - try fallback using NPC's tile
        Point fallbackPoint = calculateNpcFallbackClickPoint(npc);
        if (fallbackPoint != null) {
            log.debug("Using NPC fallback click point at ({}, {})", fallbackPoint.x, fallbackPoint.y);
            return ClickPointResult.fallback(fallbackPoint, "using tile-based fallback");
        }

        // Fallback failed (NPC not visible on screen) - need camera rotation
        if (cameraRetryCount.get() < MAX_CAMERA_RETRIES) {
            cameraRetryCount.incrementAndGet();
            log.debug("NPC not visible after {} ticks, rotating camera (attempt {})", 
                    waitTicks, cameraRetryCount.get());
            clickboxWaitTicks.set(0); // Reset wait counter for another cycle
            startCameraRotation(npcPosition);
            return ClickPointResult.needsRotation("NPC not visible, rotating camera");
        }

        return ClickPointResult.failed("NPC not visible after " + cameraRetryCount.get() + " camera rotations");
    }

    /**
     * Calculate fallback click point from NPC's position.
     * Returns null if the point would be off-screen.
     */
    @Nullable
    private Point calculateNpcFallbackClickPoint(NPC npc) {
        Client client = ctx.getClient();
        if (client == null || npc == null) {
            return null;
        }

        LocalPoint localPoint = npc.getLocalLocation();
        if (localPoint == null) {
            return null;
        }

        // Use a height offset to aim at NPC body rather than feet
        int heightOffset = 100; // Roughly NPC body height
        net.runelite.api.Point canvasPoint = net.runelite.api.Perspective.localToCanvas(
                client, localPoint, client.getPlane(), heightOffset);

        if (canvasPoint == null) {
            return null;
        }

        // Validate point is within the 3D game area - NPCs can only be clicked in viewport
        int x = canvasPoint.getX();
        int y = canvasPoint.getY();
        
        // Get 3D game area bounds
        int gameAreaX = client.getViewportXOffset();
        int gameAreaY = client.getViewportYOffset();
        int gameAreaWidth = client.getViewportWidth();
        int gameAreaHeight = client.getViewportHeight();
        
        // Fallback for fixed mode
        if (gameAreaWidth <= 0 || gameAreaHeight <= 0) {
            gameAreaX = 4;
            gameAreaY = 4;
            gameAreaWidth = 512;
            gameAreaHeight = 334;
        }
        
        int gameAreaRight = gameAreaX + gameAreaWidth;
        int gameAreaBottom = gameAreaY + gameAreaHeight;
        
        if (x < gameAreaX || x >= gameAreaRight || y < gameAreaY || y >= gameAreaBottom) {
            log.debug("NPC fallback point ({}, {}) is outside 3D game area ({},{} to {},{}), NPC not visible",
                    x, y, gameAreaX, gameAreaY, gameAreaRight, gameAreaBottom);
            return null;
        }

        Randomization rand = ctx.getRandomization();
        int offsetX = (int) rand.gaussianRandom(0, 20);
        int offsetY = (int) rand.gaussianRandom(0, 20);
        
        // Clamp to 3D game area bounds
        x = Math.max(gameAreaX + 10, Math.min(gameAreaRight - 10, x + offsetX));
        y = Math.max(gameAreaY + 10, Math.min(gameAreaBottom - 10, y + offsetY));

        return new Point(x, y);
    }

    // ========================================================================
    // Viewport Checks
    // ========================================================================

    /**
     * Check if a point is visible in the game viewport.
     * 
     * @param point the point to check
     * @return true if visible
     */
    public boolean isPointVisible(Point point) {
        if (point == null || ctx.getGameStateService() == null) {
            return false;
        }
        return ctx.getGameStateService().isPointVisibleInViewport(point.x, point.y);
    }

    /**
     * Check if a point is in the "safe zone" (center 2/3) of viewport.
     * 
     * @param point the point to check
     * @return true if in safe zone
     */
    public boolean isPointInSafeZone(Point point) {
        if (point == null || ctx.getGameStateService() == null) {
            return false;
        }
        return ctx.getGameStateService().isPointInViewport(point.x, point.y);
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Get the 3D game area bounds where objects are rendered and clickable.
     * 
     * @param client the RuneLite client
     * @return Rectangle representing the clickable game area
     */
    private Rectangle getGameAreaBounds(Client client) {
        if (client == null) {
            // Default fallback for fixed mode
            return new Rectangle(4, 4, 512, 334);
        }
        
        int gameAreaX = client.getViewportXOffset();
        int gameAreaY = client.getViewportYOffset();
        int gameAreaWidth = client.getViewportWidth();
        int gameAreaHeight = client.getViewportHeight();
        
        // Fallback for fixed mode if viewport dimensions are invalid
        if (gameAreaWidth <= 0 || gameAreaHeight <= 0) {
            gameAreaX = 4;
            gameAreaY = 4;
            gameAreaWidth = 512;
            gameAreaHeight = 334;
        }
        
        return new Rectangle(gameAreaX, gameAreaY, gameAreaWidth, gameAreaHeight);
    }

    // ========================================================================
    // Status
    // ========================================================================

    /**
     * Check if still waiting for clickbox (not failed, not success).
     * 
     * @return true if should continue waiting
     */
    public boolean isWaitingForClickbox() {
        return clickboxWaitTicks.get() > 0 && clickboxWaitTicks.get() < maxClickboxWaitTicks;
    }

    /**
     * Get current wait tick count.
     * 
     * @return ticks waited so far
     */
    public int getWaitTicks() {
        return clickboxWaitTicks.get();
    }

    /**
     * Get camera retry count.
     * 
     * @return number of camera rotation attempts
     */
    public int getCameraRetryCount() {
        return cameraRetryCount.get();
    }
}

