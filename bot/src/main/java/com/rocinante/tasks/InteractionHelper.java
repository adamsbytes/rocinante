package com.rocinante.tasks;

import com.rocinante.input.CameraController;
import com.rocinante.input.ClickPointCalculator;
import com.rocinante.input.MouseCameraCoupler;
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
                    java.awt.Canvas canvas = client.getCanvas();
                    if (canvas != null) {
                        startCameraRotationTowardCanvasPoint(canvasPoint.getX(), canvasPoint.getY(), 
                                canvas.getWidth(), canvas.getHeight());
                        return;
                    }
                }
            }
        }

        // Fallback to world-position based rotation if we couldn't get canvas point
        startCameraRotationByWorldPosition(targetPosition);
    }
    
    /**
     * Rotate camera based on where the object currently is on the canvas.
     * This is the CORRECT approach - if X < 0, rotate LEFT. If X > width, rotate RIGHT.
     */
    private void startCameraRotationTowardCanvasPoint(int canvasX, int canvasY, int canvasWidth, int canvasHeight) {
        CameraController cameraController = ctx.getCameraController();
        if (cameraController == null) {
            log.debug("No camera controller available");
            return;
        }
        
        // Calculate how far off-screen the object is
        int offscreenAmount = 0;
        boolean rotateLeft = false; // Positive yaw change = rotate right (clockwise)
        
        if (canvasX < 0) {
            // Object is off-screen LEFT - need to rotate LEFT (counter-clockwise)
            offscreenAmount = -canvasX;
            rotateLeft = true;
        } else if (canvasX >= canvasWidth) {
            // Object is off-screen RIGHT - need to rotate RIGHT (clockwise)
            offscreenAmount = canvasX - canvasWidth;
            rotateLeft = false;
        } else {
            // Object is on screen horizontally - check vertical
            if (canvasY < 0 || canvasY >= canvasHeight) {
                // Off-screen vertically - just rotate a bit to try to help
                offscreenAmount = 50;
                rotateLeft = ctx.getRandomization().chance(0.5);
            } else {
                // Object is actually on screen - no rotation needed
                log.debug("Object at ({}, {}) is on screen, no rotation needed", canvasX, canvasY);
                return;
            }
        }
        
        // Calculate rotation amount based on how far off-screen
        // Roughly: 90 degrees per half-canvas-width off-screen
        int canvasHalfWidth = canvasWidth / 2;
        double rotationFraction = Math.min(1.5, (double) offscreenAmount / canvasHalfWidth);
        int rotationDegrees = (int) (45 + rotationFraction * 60); // 45-105 degrees based on distance
        
        // Add some randomization
        rotationDegrees += (int) ctx.getRandomization().gaussianRandom(0, 10);
        rotationDegrees = Math.max(30, Math.min(120, rotationDegrees));
        
        log.debug("Object at canvas ({}, {}), off-screen by {} pixels, rotating {} by {}°",
                canvasX, canvasY, offscreenAmount, rotateLeft ? "LEFT" : "RIGHT", rotationDegrees);
        
        if (cameraRotationInProgress.compareAndSet(false, true)) {
            // Negative degrees = rotate left (counter-clockwise)
            double rotationDegreesWithSign = rotateLeft ? -rotationDegrees : rotationDegrees;
            
            CompletableFuture<Void> rotateFuture = cameraController.rotateBy(rotationDegreesWithSign, 0);
            
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
                Point point = ClickPointCalculator.getGaussianClickPoint(bounds);
                
                // CRITICAL: Verify point is actually visible in viewport
                if (!isPointVisible(point)) {
                    log.debug("Clickbox point ({}, {}) is outside viewport - need camera rotation", 
                            point.x, point.y);
                    // Trigger camera rotation instead of returning invalid point
                    if (cameraRetryCount.get() < MAX_CAMERA_RETRIES) {
                        cameraRetryCount.incrementAndGet();
                        if (worldPoint != null) {
                            startCameraRotation(worldPoint);
                        }
                        return ClickPointResult.needsRotation("clickbox outside viewport");
                    }
                    return ClickPointResult.failed("clickbox outside viewport after max retries");
                }
                
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

        // Validate point is actually on screen - don't return offscreen coordinates
        int x = canvasPoint.getX();
        int y = canvasPoint.getY();
        
        java.awt.Canvas canvas = client.getCanvas();
        if (canvas == null) {
            return null;
        }
        
        int canvasWidth = canvas.getWidth();
        int canvasHeight = canvas.getHeight();
        
        // Point must be within canvas bounds (with small margin)
        if (x < 0 || x >= canvasWidth || y < 0 || y >= canvasHeight) {
            log.debug("Fallback point ({}, {}) is off-screen (canvas {}x{}), object not visible",
                    x, y, canvasWidth, canvasHeight);
            return null;
        }

        // Add Gaussian randomization
        Randomization rand = ctx.getRandomization();
        int offsetX = (int) rand.gaussianRandom(0, 15);
        int offsetY = (int) rand.gaussianRandom(0, 15);
        
        // Clamp to canvas bounds
        x = Math.max(10, Math.min(canvasWidth - 10, x + offsetX));
        y = Math.max(10, Math.min(canvasHeight - 10, y + offsetY));

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
                Point point = ClickPointCalculator.getGaussianClickPoint(bounds);
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

        // Validate point is actually on screen
        int x = canvasPoint.getX();
        int y = canvasPoint.getY();
        
        java.awt.Canvas canvas = client.getCanvas();
        if (canvas == null) {
            return null;
        }
        
        int canvasWidth = canvas.getWidth();
        int canvasHeight = canvas.getHeight();
        
        if (x < 0 || x >= canvasWidth || y < 0 || y >= canvasHeight) {
            log.debug("NPC fallback point ({}, {}) is off-screen, NPC not visible", x, y);
            return null;
        }

        Randomization rand = ctx.getRandomization();
        int offsetX = (int) rand.gaussianRandom(0, 20);
        int offsetY = (int) rand.gaussianRandom(0, 20);
        
        // Clamp to canvas bounds
        x = Math.max(10, Math.min(canvasWidth - 10, x + offsetX));
        y = Math.max(10, Math.min(canvasHeight - 10, y + offsetY));

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

