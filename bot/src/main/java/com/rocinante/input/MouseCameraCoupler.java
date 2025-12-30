package com.rocinante.input;

import com.rocinante.behavior.AttentionModel;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.ScriptID;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Coordinates mouse movements with camera rotations to simulate natural human behavior.
 * 
 * Per REQUIREMENTS.md Section 3.4.3 (Camera Behavior Coupling):
 * 
 * - During long mouse movements (>500px): 30% chance to adjust camera angle by 5-20°
 * - When clicking off-screen objects: Rotate camera toward object before clicking (70%)
 * - Idle camera drift: During breaks or waiting, slowly rotate 1-3° per 2-5 seconds (20%)
 * - Manual camera checks: Periodically (every 2-5 minutes), rotate 360° to "look around" (5%)
 * - Camera hold: During idle periods, hold arrow key to spin (profile-based 5-30%)
 * 
 * "Meet in the Middle" Behavior:
 * When clicking a target that requires both mouse movement AND camera rotation:
 * 1. Calculate target position and required camera rotation
 * 2. Start camera rotating toward target
 * 3. Simultaneously start mouse moving toward where target WILL BE
 * 4. Both arrive at approximately the same time
 */
@Slf4j
@Singleton
public class MouseCameraCoupler {

    // === Coupling Thresholds ===
    
    /**
     * Minimum mouse movement distance to trigger coupled camera rotation (pixels).
     */
    private static final int LONG_MOVEMENT_THRESHOLD = 500;
    
    /**
     * Probability of camera adjustment during long mouse movements.
     */
    private static final double LONG_MOVEMENT_CAMERA_CHANCE = 0.30;
    
    /**
     * Minimum camera adjustment during long movements (degrees).
     */
    private static final int MIN_COUPLED_ROTATION_DEGREES = 5;
    
    /**
     * Maximum camera adjustment during long movements (degrees).
     */
    private static final int MAX_COUPLED_ROTATION_DEGREES = 20;
    
    /**
     * Probability of pre-rotating camera for off-screen targets.
     */
    private static final double OFFSCREEN_ROTATE_CHANCE = 0.70;
    
    // === Idle Drift ===
    
    /**
     * Probability of idle drift per check.
     */
    private static final double IDLE_DRIFT_CHANCE = 0.20;
    
    /**
     * Minimum interval between drift checks (ms).
     */
    private static final long MIN_DRIFT_INTERVAL_MS = 2000;
    
    /**
     * Maximum interval between drift checks (ms).
     */
    private static final long MAX_DRIFT_INTERVAL_MS = 5000;
    
    // === 360 Look-Around ===
    
    /**
     * Probability of 360 look-around per minute.
     */
    private static final double LOOK_AROUND_CHANCE_PER_MINUTE = 0.05;
    
    /**
     * Minimum interval between look-around checks (ms).
     */
    private static final long LOOK_AROUND_CHECK_INTERVAL_MS = 60000;
    
    // === Screen Margin for Off-Screen Detection ===
    
    /**
     * Margin from screen edge to consider a target "off-screen" (pixels).
     */
    private static final int SCREEN_MARGIN = 50;
    
    // === Zoom Settings ===
    
    /**
     * Preferred zoom level (lower = more zoomed out).
     * The default game range is roughly 128-896 without RuneLite expansion.
     * Value ~128-200 is max zoomed out, ~800-1400 is max zoomed in.
     */
    private static final int PREFERRED_ZOOM_LEVEL = 128;
    
    /**
     * If zoom is above this threshold, we're "too zoomed in" and should zoom out.
     */
    private static final int ZOOM_TOO_CLOSE_THRESHOLD = 300;
    
    /**
     * Minimum interval between zoom checks (ms).
     */
    private static final long ZOOM_CHECK_INTERVAL_MS = 10000;

    // === Dependencies ===
    
    private final Client client;
    private final CameraController cameraController;
    private final Randomization randomization;
    private final ClientThread clientThread;
    
    @Setter
    @Nullable
    private PlayerProfile playerProfile;
    
    @Setter
    @Nullable
    private AttentionModel attentionModel;

    /**
     * PredictiveHoverManager for coordinating camera behavior during hover.
     * When set and actively hovering, idle camera drifts are suppressed or
     * trigger a re-hover to compensate.
     */
    @Setter
    @Nullable
    private com.rocinante.behavior.PredictiveHoverManager predictiveHoverManager;
    
    // === State ===
    
    /**
     * Whether coupling is enabled.
     */
    @Getter
    @Setter
    private boolean enabled = true;
    
    /**
     * Last time an idle drift was performed.
     */
    private Instant lastDriftTime = Instant.now();
    
    /**
     * Next scheduled drift check time.
     */
    private Instant nextDriftCheckTime = Instant.now();
    
    /**
     * Last time a 360 look-around check was performed.
     */
    private Instant lastLookAroundCheckTime = Instant.now();
    
    /**
     * Last time a camera hold was performed.
     */
    private Instant lastCameraHoldTime = Instant.now();
    
    /**
     * Last time zoom was checked/adjusted.
     */
    private Instant lastZoomCheckTime = Instant.EPOCH;

    @Inject
    public MouseCameraCoupler(Client client, CameraController cameraController, Randomization randomization, ClientThread clientThread) {
        this.client = client;
        this.cameraController = cameraController;
        this.randomization = randomization;
        this.clientThread = clientThread;
        
        // Schedule first drift check
        scheduleNextDriftCheck();
        
        log.info("MouseCameraCoupler initialized");
    }

    // ========================================================================
    // Long Movement Coupling
    // ========================================================================

    /**
     * Called before a mouse movement to potentially couple with camera rotation.
     * 
     * @param startX starting X position
     * @param startY starting Y position
     * @param targetX target X position
     * @param targetY target Y position
     * @return CompletableFuture that completes when any camera movement is done
     */
    public CompletableFuture<Void> onBeforeMouseMove(int startX, int startY, int targetX, int targetY) {
        if (!enabled || cameraController.isRotating()) {
            return CompletableFuture.completedFuture(null);
        }
        
        double distance = Math.sqrt(Math.pow(targetX - startX, 2) + Math.pow(targetY - startY, 2));
        
        // Check for long movement coupling
        if (distance > LONG_MOVEMENT_THRESHOLD && randomization.chance(LONG_MOVEMENT_CAMERA_CHANCE)) {
            // Calculate direction of mouse movement
            double angle = Math.toDegrees(Math.atan2(targetY - startY, targetX - startX));
            
            // Convert to camera rotation (roughly same direction)
            int rotationDegrees = randomization.uniformRandomInt(
                    MIN_COUPLED_ROTATION_DEGREES, MAX_COUPLED_ROTATION_DEGREES);
            
            // Rotate in the general direction of the movement
            if (angle > 90 || angle < -90) {
                rotationDegrees = -rotationDegrees; // Rotate left if moving left
            }
            
            log.trace("Long movement coupling: {}px movement, rotating {}°", 
                    (int) distance, rotationDegrees);
            
            return cameraController.rotateBy(rotationDegrees, 0);
        }
        
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Execute a coordinated mouse+camera movement where both converge on target.
     * 
     * <p>This is the "meet in the middle" behavior where:
     * <ol>
     *   <li>Camera starts rotating toward the target</li>
     *   <li>Mouse starts moving toward where the target WILL BE after rotation</li>
     *   <li>Both arrive at approximately the same time</li>
     * </ol>
     * 
     * @param targetWorldPoint the world point being targeted
     * @param mouseController the mouse controller to use
     * @return CompletableFuture that completes when both movements are done
     */
    public CompletableFuture<Void> executeCoupledMovement(WorldPoint targetWorldPoint, 
                                                           RobotMouseController mouseController) {
        if (!enabled || targetWorldPoint == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Get current mouse position
        java.awt.Point currentPos = java.awt.MouseInfo.getPointerInfo().getLocation();
        CoupledMovementPlan plan = planCoupledMovement(targetWorldPoint, currentPos.x, currentPos.y);
        
        if (plan == null) {
            // No coupling needed - target already on screen
            return CompletableFuture.completedFuture(null);
        }
        
        // Don't couple if rotation is already in progress
        if (cameraController.isRotating()) {
            return CompletableFuture.completedFuture(null);
        }
        
        log.debug("Executing coupled movement: rotating {}° while moving mouse to estimated ({},{})", 
                (int) plan.getCameraRotationDegrees(), plan.getEstimatedTargetX(), plan.getEstimatedTargetY());
        
        // Calculate where center of screen will be after rotation
        // The target should end up roughly centered after rotation
        Canvas canvas = client.getCanvas();
        int centerX = canvas != null ? canvas.getWidth() / 2 : 400;
        int centerY = canvas != null ? canvas.getHeight() / 2 : 300;
        
        // Apply some randomization to not always target dead center
        int targetX = centerX + randomization.uniformRandomInt(-50, 50);
        int targetY = centerY + randomization.uniformRandomInt(-50, 50);
        
        // Start both movements in parallel
        CompletableFuture<Void> cameraFuture = cameraController.rotateBy(plan.getCameraRotationDegrees(), 0);
        CompletableFuture<Void> mouseFuture = mouseController.moveToCanvas(targetX, targetY);
        
        // Wait for both to complete
        return CompletableFuture.allOf(cameraFuture, mouseFuture);
    }

    // ========================================================================
    // Off-Screen Target Handling
    // ========================================================================

    /**
     * Check if a canvas point is on screen (within visible canvas bounds).
     * 
     * @param canvasX X coordinate on canvas
     * @param canvasY Y coordinate on canvas
     * @return true if the point is visible
     */
    public boolean isOnScreen(int canvasX, int canvasY) {
        Canvas canvas = client.getCanvas();
        if (canvas == null) {
            return true; // Assume on-screen if can't determine
        }
        
        int width = canvas.getWidth();
        int height = canvas.getHeight();
        
        return canvasX >= SCREEN_MARGIN && canvasX < width - SCREEN_MARGIN
            && canvasY >= SCREEN_MARGIN && canvasY < height - SCREEN_MARGIN;
    }

    /**
     * Threshold for using arrow keys vs mouse-based rotation.
     * Rotations >= this use arrow keys (more human-like for larger turns).
     */
    private static final int ARROW_KEY_THRESHOLD_DEGREES = 20;

    /**
     * Pre-rotate camera to bring a world point on screen.
     * Called before clicking on off-screen objects/NPCs.
     * Uses arrow keys for normal rotations, mouse for small adjustments.
     * 
     * @param worldPoint the world point to rotate toward
     * @return CompletableFuture that completes when rotation is done (or skipped)
     */
    public CompletableFuture<Void> rotateToShowTarget(WorldPoint worldPoint) {
        if (!enabled || cameraController.isRotating()) {
            return CompletableFuture.completedFuture(null);
        }
        
        // 70% chance to pre-rotate
        if (!randomization.chance(OFFSCREEN_ROTATE_CHANCE)) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Calculate angle from player to target
        WorldPoint playerPos = client.getLocalPlayer().getWorldLocation();
        if (playerPos == null || worldPoint == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        int dx = worldPoint.getX() - playerPos.getX();
        int dy = worldPoint.getY() - playerPos.getY();
        
        // Convert to camera yaw (note: OSRS uses different coordinate system)
        // North is 0, East is 512, South is 1024, West is 1536
        double targetAngle = Math.toDegrees(Math.atan2(dx, dy));
        if (targetAngle < 0) {
            targetAngle += 360;
        }
        
        // Calculate rotation needed
        double currentAngle = cameraController.getCurrentYawDegrees();
        double deltaAngle = targetAngle - currentAngle;
        
        // Normalize to -180 to 180
        while (deltaAngle > 180) deltaAngle -= 360;
        while (deltaAngle < -180) deltaAngle += 360;
        
        int rotationDegrees = Math.abs((int) deltaAngle);
        rotationDegrees = Math.min(rotationDegrees, MAX_COUPLED_ROTATION_DEGREES * 2);
        rotationDegrees = Math.max(rotationDegrees, MIN_COUPLED_ROTATION_DEGREES);
        
        boolean rotateLeft = deltaAngle < 0;
        
        log.debug("Pre-rotating camera to show target at {} (angle: {}°, delta: {}°, using {})", 
                worldPoint, (int) targetAngle, rotationDegrees,
                rotationDegrees >= ARROW_KEY_THRESHOLD_DEGREES ? "arrow keys" : "mouse");
        
        if (rotationDegrees >= ARROW_KEY_THRESHOLD_DEGREES) {
            // Use arrow keys for normal/large rotations (more human-like)
            CameraController.Direction direction = rotateLeft 
                    ? CameraController.Direction.LEFT 
                    : CameraController.Direction.RIGHT;
            
            // Calculate hold duration based on rotation needed (60°/sec at MEDIUM speed)
            long estimatedDurationMs = (long) (rotationDegrees / 60.0 * 1000) + 200;
            estimatedDurationMs = Math.max(500, Math.min(3000, estimatedDurationMs));
            
            return cameraController.performCameraHold(direction, estimatedDurationMs, rotationDegrees + 15);
        } else {
            // Use mouse for small adjustments (more precise)
        return cameraController.rotateTowardTarget(targetAngle, 
                    MIN_COUPLED_ROTATION_DEGREES, MAX_COUPLED_ROTATION_DEGREES);
        }
    }

    /**
     * Check if a world point is visible on screen and pre-rotate if needed.
     * 
     * @param worldPoint the world point to check
     * @return CompletableFuture that completes when ready to interact
     */
    public CompletableFuture<Void> ensureTargetVisible(WorldPoint worldPoint) {
        if (!enabled || worldPoint == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Convert world point to local point
        LocalPoint localPoint = LocalPoint.fromWorld(client, worldPoint);
        if (localPoint == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Convert to canvas point
        net.runelite.api.Point canvasPoint = Perspective.localToCanvas(client, localPoint, client.getPlane());
        if (canvasPoint == null) {
            // Target is definitely off-screen
            return rotateToShowTarget(worldPoint);
        }
        
        // Check if on screen
        if (!isOnScreen(canvasPoint.getX(), canvasPoint.getY())) {
            return rotateToShowTarget(worldPoint);
        }
        
        return CompletableFuture.completedFuture(null);
    }

    // ========================================================================
    // Idle Behaviors
    // ========================================================================

    /**
     * Tick the coupler to check for idle behaviors.
     * Called automatically on each game tick.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        tick();
    }
    
    /**
     * Manual tick for when not using event subscription.
     */
    public void tick() {
        if (!enabled || cameraController.isRotating()) {
            return;
        }
        
        Instant now = Instant.now();
        
        // Check zoom level periodically - zoom out if too close
        if (Duration.between(lastZoomCheckTime, now).toMillis() >= ZOOM_CHECK_INTERVAL_MS) {
            checkAndAdjustZoom();
            lastZoomCheckTime = now;
        }
        
        // Check for idle drift
        if (now.isAfter(nextDriftCheckTime)) {
            checkIdleDrift();
            scheduleNextDriftCheck();
        }
        
        // Check for 360 look-around
        if (Duration.between(lastLookAroundCheckTime, now).toMillis() >= LOOK_AROUND_CHECK_INTERVAL_MS) {
            checkLookAround();
            lastLookAroundCheckTime = now;
        }
    }

    /**
     * Check and potentially perform idle drift.
     * 
     * <p><b>Predictive Hover Integration:</b> If there is an active predictive hover,
     * camera drift is suppressed to maintain the hover. The screen position of the
     * hovered target would change after rotation, invalidating the hover.
     */
    private void checkIdleDrift() {
        // Suppress drift during predictive hover
        if (predictiveHoverManager != null && predictiveHoverManager.shouldSuppressIdleBehavior()) {
            log.trace("Camera drift suppressed due to active predictive hover");
            return;
        }

        // Don't drift if attention model says we're focused
        if (attentionModel != null && attentionModel.canAct()) {
            // Only drift when AFK or distracted, not when focused on task
            if (attentionModel.getCurrentState() == com.rocinante.behavior.AttentionState.FOCUSED) {
                // Lower chance when focused
                if (!randomization.chance(IDLE_DRIFT_CHANCE * 0.2)) {
                    return;
                }
            }
        }
        
        if (randomization.chance(IDLE_DRIFT_CHANCE)) {
            // Notify hover manager that camera is about to rotate
            if (predictiveHoverManager != null) {
                predictiveHoverManager.onCameraRotationStart();
            }

            log.trace("Performing idle camera drift");
            cameraController.performIdleDrift()
                .exceptionally(e -> {
                    log.trace("Idle drift interrupted: {}", e.getMessage());
                    return null;
                });
            lastDriftTime = Instant.now();
        }
    }

    /**
     * Check and potentially perform 360 look-around.
     * 
     * <p><b>Predictive Hover Integration:</b> A 360 look-around completely invalidates
     * any hover, so we clear it before performing the rotation.
     */
    private void checkLookAround() {
        // 360 look-around invalidates any hover - clear it first
        if (predictiveHoverManager != null && predictiveHoverManager.hasPendingHover()) {
            log.debug("Clearing predictive hover due to 360 look-around");
            predictiveHoverManager.clearHover();
        }

        if (randomization.chance(LOOK_AROUND_CHANCE_PER_MINUTE)) {
            log.debug("Performing 360 look-around");
            cameraController.perform360LookAround()
                .exceptionally(e -> {
                    log.debug("Look-around interrupted: {}", e.getMessage());
                    return null;
                });
        }
    }

    /**
     * Schedule the next drift check.
     */
    private void scheduleNextDriftCheck() {
        long intervalMs = randomization.uniformRandomLong(MIN_DRIFT_INTERVAL_MS, MAX_DRIFT_INTERVAL_MS);
        nextDriftCheckTime = Instant.now().plusMillis(intervalMs);
    }

    // ========================================================================
    // Camera Hold (Fidgeting)
    // ========================================================================

    /**
     * Check and potentially perform camera hold during idle/waiting periods.
     * This simulates bored players holding arrow key to spin camera.
     * 
     * @return CompletableFuture that completes when hold is done (or skipped)
     */
    public CompletableFuture<Void> checkCameraHold() {
        if (!enabled || cameraController.isRotating()) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Don't do camera hold too frequently
        Instant now = Instant.now();
        if (Duration.between(lastCameraHoldTime, now).toSeconds() < 30) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Check if we should perform camera hold
        if (cameraController.shouldPerformCameraHold()) {
            lastCameraHoldTime = now;
            log.debug("Performing camera hold (fidgeting)");
            return cameraController.performCameraHold();
        }
        
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Force perform a camera hold. Use during known idle periods.
     * 
     * @return CompletableFuture that completes when hold is done
     */
    public CompletableFuture<Void> performCameraHold() {
        if (!enabled || cameraController.isRotating()) {
            return CompletableFuture.completedFuture(null);
        }
        
        lastCameraHoldTime = Instant.now();
        return cameraController.performCameraHold();
    }

    // ========================================================================
    // "Meet in the Middle" Support
    // ========================================================================

    /**
     * Plan a coordinated mouse+camera movement where both converge on target.
     * 
     * @param targetWorldPoint the world point being targeted
     * @param mouseStartX current mouse X
     * @param mouseStartY current mouse Y
     * @return CoupledMovementPlan or null if no coupling needed
     */
    @Nullable
    public CoupledMovementPlan planCoupledMovement(WorldPoint targetWorldPoint, 
                                                    int mouseStartX, int mouseStartY) {
        if (!enabled || targetWorldPoint == null) {
            return null;
        }
        
        // Check if target is off-screen
        LocalPoint localPoint = LocalPoint.fromWorld(client, targetWorldPoint);
        if (localPoint == null) {
            return null; // Too far away
        }
        
        net.runelite.api.Point canvasPoint = Perspective.localToCanvas(client, localPoint, client.getPlane());
        if (canvasPoint == null || isOnScreen(canvasPoint.getX(), canvasPoint.getY())) {
            return null; // Already on screen, no coupling needed
        }
        
        // Calculate rotation needed
        WorldPoint playerPos = client.getLocalPlayer().getWorldLocation();
        if (playerPos == null) {
            return null;
        }
        
        int dx = targetWorldPoint.getX() - playerPos.getX();
        int dy = targetWorldPoint.getY() - playerPos.getY();
        double targetAngle = Math.toDegrees(Math.atan2(dx, dy));
        if (targetAngle < 0) {
            targetAngle += 360;
        }
        
        double currentYaw = cameraController.getCurrentYawDegrees();
        double deltaYaw = targetAngle - currentYaw;
        
        // Normalize to -180 to 180
        while (deltaYaw > 180) deltaYaw -= 360;
        while (deltaYaw < -180) deltaYaw += 360;
        
        // Only need coupled movement if rotation is significant
        if (Math.abs(deltaYaw) < 30) {
            return null;
        }
        
        return new CoupledMovementPlan(
            deltaYaw,
            mouseStartX,
            mouseStartY,
            canvasPoint.getX(),
            canvasPoint.getY()
        );
    }

    /**
     * Plan for coordinated mouse and camera movement.
     */
    public static class CoupledMovementPlan {
        @Getter
        private final double cameraRotationDegrees;
        @Getter
        private final int mouseStartX;
        @Getter
        private final int mouseStartY;
        @Getter
        private final int estimatedTargetX;
        @Getter
        private final int estimatedTargetY;
        
        public CoupledMovementPlan(double cameraRotationDegrees, 
                                   int mouseStartX, int mouseStartY,
                                   int estimatedTargetX, int estimatedTargetY) {
            this.cameraRotationDegrees = cameraRotationDegrees;
            this.mouseStartX = mouseStartX;
            this.mouseStartY = mouseStartY;
            this.estimatedTargetX = estimatedTargetX;
            this.estimatedTargetY = estimatedTargetY;
        }
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Get time since last camera movement (from the controller).
     * 
     * @return milliseconds since last camera movement
     */
    public long getTimeSinceLastCameraMovement() {
        return cameraController.getTimeSinceLastMovement();
    }

    /**
     * Get time since last drift.
     * 
     * @return duration since last drift
     */
    public Duration getTimeSinceLastDrift() {
        return Duration.between(lastDriftTime, Instant.now());
    }

    /**
     * Reset all timing state. Called on session start.
     */
    public void reset() {
        lastDriftTime = Instant.now();
        lastLookAroundCheckTime = Instant.now();
        lastCameraHoldTime = Instant.now();
        scheduleNextDriftCheck();
    }

    // ========================================================================
    // Zoom Control
    // ========================================================================

    /**
     * Check if we're too zoomed in and adjust if needed.
     * Called periodically from tick().
     */
    private void checkAndAdjustZoom() {
        int currentZoom = client.getScale();
        log.debug("Zoom check: current={}, threshold={}, preferred={}", 
                currentZoom, ZOOM_TOO_CLOSE_THRESHOLD, PREFERRED_ZOOM_LEVEL);
        if (currentZoom > ZOOM_TOO_CLOSE_THRESHOLD) {
            log.info("Camera too zoomed in ({} > {}), zooming out to {}", 
                    currentZoom, ZOOM_TOO_CLOSE_THRESHOLD, PREFERRED_ZOOM_LEVEL);
            zoomToPreferred();
        }
    }

    /**
     * Zoom out to the preferred level for better visibility.
     */
    public void zoomToPreferred() {
        clientThread.invokeLater(() -> {
            int currentZoom = client.getScale();
            if (currentZoom > PREFERRED_ZOOM_LEVEL + 50) {
                log.debug("Zooming out from {} to preferred level {}", currentZoom, PREFERRED_ZOOM_LEVEL);
                client.runScript(ScriptID.CAMERA_DO_ZOOM, PREFERRED_ZOOM_LEVEL, PREFERRED_ZOOM_LEVEL);
            }
        });
    }

    /**
     * Get the current zoom level.
     * 
     * @return the current camera zoom scale
     */
    public int getCurrentZoom() {
        return client.getScale();
    }
}

