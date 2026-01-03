package com.rocinante.input;

import com.rocinante.behavior.PlayerProfile;
import com.rocinante.util.PerlinNoise;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.*;

/**
 * Humanized camera controller using simulated input (middle mouse drag, arrow keys).
 * 
 * Per REQUIREMENTS.md Section 3.4.3 (Camera Behavior Coupling):
 * - Middle mouse drag simulation for yaw/pitch changes
 * - Arrow key hold simulation for continuous rotation (camera hold / fidgeting)
 * - Bezier-curve movement for natural drag patterns
 * - Integration with PlayerProfile camera preferences
 * 
 * Camera Behaviors:
 * - Idle drift: 20% per 2-5s interval, 1-3 degree slow rotation
 * - 360 look-around: 5% per minute, full sweep in 2-5 seconds
 * - Camera hold: Profile-based (5-30%) during idle/wait, hold for 2-15s, limit 180-360°
 * - Coupled rotation: During long mouse moves (>500px), 30% chance, 5-20 degrees
 * - Pre-click rotation: Off-screen targets, 70% rotate to show target first
 * 
 * Camera angles use JAU (Jagex Angle Units): 2048 JAU = 360 degrees
 * Pitch range: ~128 (highest) to ~512 (lowest looking down)
 */
@Slf4j
@Singleton
public class CameraController {

    // === JAU Constants ===
    
    /**
     * Full rotation in JAU (Jagex Angle Units).
     * 2048 JAU = 360 degrees.
     */
    public static final int FULL_ROTATION_JAU = 2048;
    
    /**
     * Degrees to JAU conversion factor.
     */
    public static final double JAU_PER_DEGREE = FULL_ROTATION_JAU / 360.0;
    
    /**
     * JAU to degrees conversion factor.
     */
    public static final double DEGREES_PER_JAU = 360.0 / FULL_ROTATION_JAU;
    
    // === Pitch Limits ===
    
    /**
     * Minimum pitch (looking up) in JAU.
     */
    private static final int MIN_PITCH_JAU = 128;
    
    /**
     * Maximum pitch (looking down) in JAU.
     */
    private static final int MAX_PITCH_JAU = 512;
    
    // === Timing Constants ===
    
    /**
     * Minimum duration for camera rotation via drag (ms).
     */
    private static final long MIN_DRAG_DURATION_MS = 200;
    
    /**
     * Maximum duration for camera rotation via drag (ms).
     */
    private static final long MAX_DRAG_DURATION_MS = 800;
    
    /**
     * Update interval during drag movement (ms).
     */
    private static final long DRAG_UPDATE_INTERVAL_MS = 10;
    
    /**
     * Minimum delay before starting rotation (ms).
     */
    private static final long MIN_ROTATION_DELAY_MS = 50;
    
    /**
     * Maximum delay before starting rotation (ms).
     */
    private static final long MAX_ROTATION_DELAY_MS = 200;
    
    // === Camera Hold Constants ===
    
    /**
     * Minimum camera hold duration (ms).
     */
    private static final long MIN_CAMERA_HOLD_MS = 2000;
    
    /**
     * Maximum camera hold duration (ms).
     */
    private static final long MAX_CAMERA_HOLD_MS = 15000;
    
    /**
     * Minimum rotation limit for camera hold (degrees).
     */
    private static final int MIN_CAMERA_HOLD_DEGREES = 180;
    
    /**
     * Maximum rotation limit for camera hold (degrees).
     */
    private static final int MAX_CAMERA_HOLD_DEGREES = 360;
    
    // === 360 Look-Around Constants ===
    
    /**
     * Minimum duration for 360 look-around (ms).
     */
    private static final long MIN_360_DURATION_MS = 2000;
    
    /**
     * Maximum duration for 360 look-around (ms).
     */
    private static final long MAX_360_DURATION_MS = 5000;
    
    // === Idle Drift Constants ===
    
    /**
     * Minimum idle drift degrees.
     */
    private static final int MIN_DRIFT_DEGREES = 1;
    
    /**
     * Maximum idle drift degrees.
     */
    private static final int MAX_DRIFT_DEGREES = 3;
    
    /**
     * Minimum drift duration (ms).
     */
    private static final long MIN_DRIFT_DURATION_MS = 1000;
    
    /**
     * Maximum drift duration (ms).
     */
    private static final long MAX_DRIFT_DURATION_MS = 3000;
    
    // === Arrow Key Hold Speed (degrees per second) ===
    
    /**
     * Slow camera hold speed (degrees per second).
     */
    private static final double SLOW_ROTATION_SPEED = 30.0;
    
    /**
     * Medium camera hold speed (degrees per second).
     */
    private static final double MEDIUM_ROTATION_SPEED = 60.0;
    
    /**
     * Fast camera hold speed (degrees per second).
     */
    private static final double FAST_ROTATION_SPEED = 100.0;
    
    // === Middle Mouse Drag Sensitivity ===
    
    /**
     * Pixels of drag per JAU of yaw rotation.
     */
    private static final double PIXELS_PER_JAU_YAW = 0.8;
    
    /**
     * Pixels of drag per JAU of pitch rotation.
     */
    private static final double PIXELS_PER_JAU_PITCH = 1.2;

    // === Dependencies ===
    
    private final Robot robot;
    private final Client client;
    private final Randomization randomization;
    private final PerlinNoise perlinNoise;
    private final ScheduledExecutorService executor;
    
    /**
     * PlayerProfile for camera preferences.
     */
    @Setter
    @Nullable
    private PlayerProfile playerProfile;
    
    // === State ===
    
    /**
     * Whether a camera operation is currently in progress.
     */
    @Getter
    private volatile boolean rotating = false;
    
    /**
     * Timestamp of last camera movement for drift timing.
     */
    private volatile long lastMovementTime = System.currentTimeMillis();
    
    /**
     * Seed for Perlin noise during drag movements.
     */
    private long movementSeed = System.nanoTime();

    @Inject
    public CameraController(Client client, Randomization randomization, PerlinNoise perlinNoise) throws AWTException {
        this.robot = new Robot();
        this.robot.setAutoDelay(0);
        this.client = client;
        this.randomization = randomization;
        this.perlinNoise = perlinNoise;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CameraController");
            t.setDaemon(true);
            return t;
        });
        
        log.info("CameraController initialized");
    }
    
    /**
     * Constructor for testing.
     */
    public CameraController(Robot robot, Client client, Randomization randomization, 
                           PerlinNoise perlinNoise, ScheduledExecutorService executor) {
        this.robot = robot;
        this.client = client;
        this.randomization = randomization;
        this.perlinNoise = perlinNoise;
        this.executor = executor;
    }

    // ========================================================================
    // Rotation Methods
    // ========================================================================

    /**
     * Rotate camera to a specific yaw angle using middle mouse drag.
     * 
     * @param targetYawDegrees target yaw angle in degrees (0-360)
     * @return CompletableFuture that completes when rotation is done
     */
    public CompletableFuture<Void> rotateToYaw(double targetYawDegrees) {
        int currentYaw = client.getCameraYaw();
        int targetYaw = (int) (targetYawDegrees * JAU_PER_DEGREE);
        int deltaYaw = calculateShortestRotation(currentYaw, targetYaw);
        
        return rotateByJau(deltaYaw, 0);
    }

    /**
     * Rotate camera by a delta amount using middle mouse drag.
     * 
     * @param deltaYawDegrees yaw change in degrees (positive = right/clockwise)
     * @param deltaPitchDegrees pitch change in degrees (positive = down)
     * @return CompletableFuture that completes when rotation is done
     */
    public CompletableFuture<Void> rotateBy(double deltaYawDegrees, double deltaPitchDegrees) {
        int deltaYaw = (int) (deltaYawDegrees * JAU_PER_DEGREE);
        int deltaPitch = (int) (deltaPitchDegrees * JAU_PER_DEGREE);
        return rotateByJau(deltaYaw, deltaPitch);
    }

    /**
     * Rotate camera by JAU values using middle mouse drag.
     */
    private CompletableFuture<Void> rotateByJau(int deltaYaw, int deltaPitch) {
        if (rotating) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Camera rotation already in progress"));
        }
        
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        executor.execute(() -> {
            try {
                rotating = true;
                movementSeed = System.nanoTime();
                
                // Initial delay
                long delay = randomization.uniformRandomLong(MIN_ROTATION_DELAY_MS, MAX_ROTATION_DELAY_MS);
                Thread.sleep(delay);
                
                // Calculate drag distance
                int dragX = (int) (deltaYaw * PIXELS_PER_JAU_YAW);
                int dragY = (int) (deltaPitch * PIXELS_PER_JAU_PITCH);
                
                // Execute drag movement
                executeMiddleMouseDrag(dragX, dragY);
                
                lastMovementTime = System.currentTimeMillis();
                rotating = false;
                future.complete(null);
            } catch (Exception e) {
                rotating = false;
                log.error("Camera rotation failed", e);
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }

    /**
     * Execute a middle mouse drag movement with humanized path.
     */
    private void executeMiddleMouseDrag(int totalDragX, int totalDragY) throws InterruptedException {
        // Get starting position (center of canvas)
        Canvas canvas = client.getCanvas();
        Point canvasCenter = new Point(canvas.getWidth() / 2, canvas.getHeight() / 2);
        Point screenPos = canvas.getLocationOnScreen();
        int startX = screenPos.x + canvasCenter.x;
        int startY = screenPos.y + canvasCenter.y;
        
        // Calculate duration based on drag distance
        double distance = Math.sqrt(totalDragX * totalDragX + totalDragY * totalDragY);
        long duration = (long) (MIN_DRAG_DURATION_MS + 
            (distance / 100.0) * (MAX_DRAG_DURATION_MS - MIN_DRAG_DURATION_MS));
        duration = Math.min(duration, MAX_DRAG_DURATION_MS);
        
        // Move to start position
        robot.mouseMove(startX, startY);
        Thread.sleep(randomization.uniformRandomLong(30, 80));
        
        // Press middle mouse button
        robot.mousePress(InputEvent.BUTTON2_DOWN_MASK);
        Thread.sleep(randomization.uniformRandomLong(20, 50));
        
        // Execute drag with Bezier curve and Perlin noise
        long startTime = System.currentTimeMillis();
        long elapsed = 0;
        
        while (elapsed < duration) {
            double t = (double) elapsed / duration;
            
            // Smooth ease-in-out curve
            double smoothT = smoothStep(t);
            
            // Calculate position along drag path
            int currentX = startX + (int) (totalDragX * smoothT);
            int currentY = startY + (int) (totalDragY * smoothT);
            
            // Add Perlin noise for natural variation
            double[] noiseOffset = perlinNoise.getPathOffset(t, 2.0, movementSeed);
            currentX += (int) noiseOffset[0];
            currentY += (int) noiseOffset[1];
            
            robot.mouseMove(currentX, currentY);
            
            Thread.sleep(DRAG_UPDATE_INTERVAL_MS);
            elapsed = System.currentTimeMillis() - startTime;
        }
        
        // Final position
        robot.mouseMove(startX + totalDragX, startY + totalDragY);
        Thread.sleep(randomization.uniformRandomLong(20, 50));
        
        // Release middle mouse button
        robot.mouseRelease(InputEvent.BUTTON2_DOWN_MASK);
        
        log.debug("Camera drag completed: ({}, {}) over {}ms", totalDragX, totalDragY, duration);
    }

    // ========================================================================
    // Camera Hold (Fidgeting Behavior)
    // ========================================================================

    /**
     * Direction for camera hold rotation.
     */
    public enum Direction {
        LEFT(KeyEvent.VK_LEFT, -1),
        RIGHT(KeyEvent.VK_RIGHT, 1);
        
        @Getter
        private final int keyCode;
        @Getter
        private final int sign;
        
        Direction(int keyCode, int sign) {
            this.keyCode = keyCode;
            this.sign = sign;
        }
    }

    /**
     * Camera hold speed preference.
     */
    public enum HoldSpeed {
        SLOW(SLOW_ROTATION_SPEED),
        MEDIUM(MEDIUM_ROTATION_SPEED),
        FAST(FAST_ROTATION_SPEED);
        
        @Getter
        private final double degreesPerSecond;
        
        HoldSpeed(double degreesPerSecond) {
            this.degreesPerSecond = degreesPerSecond;
        }
    }

    /**
     * Perform camera hold - simulate holding an arrow key to spin camera.
     * This is fidgeting behavior during idle/waiting periods.
     * 
     * @param direction LEFT or RIGHT
     * @param durationMs how long to hold (2000-15000ms typical)
     * @param maxDegrees maximum rotation before stopping (180-360)
     * @return CompletableFuture that completes when hold is done
     */
    public CompletableFuture<Void> performCameraHold(Direction direction, long durationMs, int maxDegrees) {
        if (rotating) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Camera rotation already in progress"));
        }
        
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        executor.execute(() -> {
            try {
                rotating = true;
                
                int startYaw = client.getCameraYaw();
                int maxJau = (int) (maxDegrees * JAU_PER_DEGREE);
                
                // Get rotation speed from profile or use default
                HoldSpeed speed = getProfileHoldSpeed();
                double jauPerMs = (speed.getDegreesPerSecond() * JAU_PER_DEGREE) / 1000.0;
                
                log.debug("Starting camera hold: {} for {}ms, max {}°, speed={}", 
                        direction, durationMs, maxDegrees, speed);
                
                // Press the arrow key
                robot.keyPress(direction.getKeyCode());
                
                long startTime = System.currentTimeMillis();
                long elapsed = 0;
                
                while (elapsed < durationMs) {
                    Thread.sleep(50); // Check interval
                    elapsed = System.currentTimeMillis() - startTime;
                    
                    // Check if we've rotated enough
                    int currentYaw = client.getCameraYaw();
                    int rotatedJau = Math.abs(calculateShortestRotation(startYaw, currentYaw));
                    
                    if (rotatedJau >= maxJau) {
                        log.debug("Camera hold reached max rotation: {} JAU", rotatedJau);
                        break;
                    }
                    
                    // Occasionally vary the hold (brief release and re-press for naturalness)
                    if (randomization.chance(0.02)) { // 2% chance per check
                        robot.keyRelease(direction.getKeyCode());
                        Thread.sleep(randomization.uniformRandomLong(50, 150));
                        robot.keyPress(direction.getKeyCode());
                    }
                }
                
                // Release the arrow key
                robot.keyRelease(direction.getKeyCode());
                
                lastMovementTime = System.currentTimeMillis();
                rotating = false;
                
                log.debug("Camera hold completed after {}ms", elapsed);
                future.complete(null);
            } catch (Exception e) {
                rotating = false;
                try {
                    // Ensure key is released on error
                    robot.keyRelease(direction.getKeyCode());
                } catch (Exception releaseEx) {
                    log.debug("Failed to release key during camera hold error cleanup: {}", releaseEx.getMessage());
                }
                log.error("Camera hold failed", e);
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }

    /**
     * Perform camera hold with random parameters based on profile.
     * 
     * @return CompletableFuture that completes when hold is done
     */
    public CompletableFuture<Void> performCameraHold() {
        Direction direction = selectHoldDirection();
        long duration = randomization.uniformRandomLong(MIN_CAMERA_HOLD_MS, MAX_CAMERA_HOLD_MS);
        int maxDegrees = randomization.uniformRandomInt(MIN_CAMERA_HOLD_DEGREES, MAX_CAMERA_HOLD_DEGREES);
        
        return performCameraHold(direction, duration, maxDegrees);
    }

    /**
     * Select camera hold direction based on profile preference.
     */
    private Direction selectHoldDirection() {
        if (playerProfile != null) {
            String preference = playerProfile.getProfileData().getCameraHoldPreferredDirection();
            if (preference != null) {
                switch (preference) {
                    case "LEFT_BIAS":
                        return randomization.chance(0.70) ? Direction.LEFT : Direction.RIGHT;
                    case "RIGHT_BIAS":
                        return randomization.chance(0.70) ? Direction.RIGHT : Direction.LEFT;
                }
            }
        }
        // No preference - random
        return randomization.chance(0.5) ? Direction.LEFT : Direction.RIGHT;
    }

    /**
     * Get hold speed preference from profile.
     */
    private HoldSpeed getProfileHoldSpeed() {
        if (playerProfile != null) {
            String preference = playerProfile.getProfileData().getCameraHoldSpeedPreference();
            if (preference != null) {
                try {
                    return HoldSpeed.valueOf(preference);
                } catch (IllegalArgumentException e) {
                    log.debug("Invalid camera hold speed preference '{}': {}", preference, e.getMessage());
                }
            }
        }
        // Default to medium
        return HoldSpeed.MEDIUM;
    }

    // ========================================================================
    // Camera Snap-Back (Returning to Preferred Angle)
    // ========================================================================

    /**
     * Check if the camera should snap back to the player's preferred angle.
     * 
     * This simulates the unconscious tendency to return to a "home" camera angle.
     * Players develop comfort with certain angles and naturally drift back to them.
     * 
     * @return true if snap-back should occur
     */
    public boolean shouldSnapBackToPreferred() {
        if (playerProfile == null || rotating) {
            return false;
        }
        
        // Check if enough time has passed since last movement
        long timeSinceMovement = getTimeSinceLastMovement();
        if (timeSinceMovement < playerProfile.getCameraSnapBackDelayMs()) {
            return false;
        }
        
        // Check if we're far enough from preferred angle
        double currentYaw = getCurrentYawDegrees();
        double preferredYaw = playerProfile.getPreferredCompassAngle();
        double deviation = Math.abs(angleDifference(currentYaw, preferredYaw));
        
        if (deviation < playerProfile.getCameraSnapBackTolerance()) {
            return false; // Already close enough
        }
        
        // Probability check
        return randomization.chance(playerProfile.getCameraSnapBackProbability());
    }

    /**
     * Snap camera back toward the preferred angle.
     * 
     * Doesn't go exactly to preferred - adds some natural variation.
     * The movement is gradual and human-like.
     * 
     * @return CompletableFuture that completes when snap-back is done
     */
    public CompletableFuture<Void> snapToPreferredAngle() {
        if (playerProfile == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        double currentYaw = getCurrentYawDegrees();
        double preferredYaw = playerProfile.getPreferredCompassAngle();
        
        // Add some imprecision - don't go exactly to preferred (±5-15 degrees)
        double imprecision = randomization.uniformRandom(-15, 15);
        double targetYaw = (preferredYaw + imprecision + 360) % 360;
        
        // Calculate delta (shortest path)
        double delta = angleDifference(currentYaw, targetYaw);
        
        log.debug("Camera snap-back: {}° → {}° (preferred={}°, delta={}°)", 
                String.format("%.1f", currentYaw),
                String.format("%.1f", targetYaw),
                String.format("%.1f", preferredYaw),
                String.format("%.1f", delta));
        
        return rotateBy(delta, 0);
    }

    /**
     * Calculate the shortest angular difference between two angles.
     * Result is in range [-180, 180].
     */
    private double angleDifference(double from, double to) {
        double diff = to - from;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        return diff;
    }

    // ========================================================================
    // Idle Drift
    // ========================================================================

    /**
     * Perform idle camera drift - small slow rotation during breaks.
     * 1-3 degrees over 1-3 seconds.
     * 
     * @return CompletableFuture that completes when drift is done
     */
    public CompletableFuture<Void> performIdleDrift() {
        if (rotating) {
            return CompletableFuture.completedFuture(null); // Skip if already rotating
        }
        
        // Random direction and amount
        int degreesRaw = randomization.uniformRandomInt(MIN_DRIFT_DEGREES, MAX_DRIFT_DEGREES);
        final int degrees = randomization.chance(0.5) ? -degreesRaw : degreesRaw;
        final long duration = randomization.uniformRandomLong(MIN_DRIFT_DURATION_MS, MAX_DRIFT_DURATION_MS);
        
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        executor.execute(() -> {
            // Use arrow key for slow drift
            Direction direction = degrees > 0 ? Direction.RIGHT : Direction.LEFT;
            
            try {
                rotating = true;
                
                log.debug("Performing idle drift: {}° over {}ms", degrees, duration);
                
                // Calculate hold time needed for desired rotation
                HoldSpeed speed = HoldSpeed.SLOW;
                long holdTime = (long) (Math.abs(degrees) / speed.getDegreesPerSecond() * 1000);
                holdTime = Math.min(holdTime, duration);
                
                // Press and hold arrow key
                robot.keyPress(direction.getKeyCode());
                Thread.sleep(holdTime);
                
                lastMovementTime = System.currentTimeMillis();
                future.complete(null);
            } catch (Exception e) {
                log.error("Idle drift failed", e);
                future.completeExceptionally(e);
            } finally {
                // ALWAYS release the key even on exception to prevent stuck keys
                try {
                    robot.keyRelease(direction.getKeyCode());
                } catch (Exception releaseEx) {
                    log.debug("Failed to release camera key in cleanup: {}", releaseEx.getMessage());
                }
                rotating = false;
            }
        });
        
        return future;
    }

    // ========================================================================
    // 360 Look-Around
    // ========================================================================

    /**
     * Perform a 360-degree camera sweep to "look around".
     * Used for manual camera checks (5% per minute).
     * 
     * @return CompletableFuture that completes when sweep is done
     */
    public CompletableFuture<Void> perform360LookAround() {
        if (rotating) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Camera rotation already in progress"));
        }
        
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        executor.execute(() -> {
            try {
                rotating = true;
                
                Direction direction = randomization.chance(0.5) ? Direction.LEFT : Direction.RIGHT;
                long duration = randomization.uniformRandomLong(MIN_360_DURATION_MS, MAX_360_DURATION_MS);
                
                log.debug("Performing 360 look-around: {} over {}ms", direction, duration);
                
                // Use arrow key hold for smooth rotation
                robot.keyPress(direction.getKeyCode());
                
                long startTime = System.currentTimeMillis();
                int startYaw = client.getCameraYaw();
                int fullRotation = FULL_ROTATION_JAU;
                
                while (System.currentTimeMillis() - startTime < duration) {
                    Thread.sleep(50);
                    
                    // Check if we've done a full rotation
                    int currentYaw = client.getCameraYaw();
                    int rotated = Math.abs(currentYaw - startYaw);
                    
                    // Account for wrap-around
                    if (rotated > FULL_ROTATION_JAU / 2) {
                        rotated = FULL_ROTATION_JAU - rotated;
                    }
                    
                    // Occasionally pause briefly during the sweep (natural behavior)
                    if (randomization.chance(0.01)) {
                        robot.keyRelease(direction.getKeyCode());
                        Thread.sleep(randomization.uniformRandomLong(100, 300));
                        robot.keyPress(direction.getKeyCode());
                    }
                }
                
                robot.keyRelease(direction.getKeyCode());
                
                lastMovementTime = System.currentTimeMillis();
                rotating = false;
                
                log.debug("360 look-around completed");
                future.complete(null);
            } catch (Exception e) {
                rotating = false;
                try {
                    robot.keyRelease(KeyEvent.VK_LEFT);
                    robot.keyRelease(KeyEvent.VK_RIGHT);
                } catch (Exception releaseEx) {
                    log.debug("Failed to release camera keys during 360 look-around error cleanup: {}", releaseEx.getMessage());
                }
                log.error("360 look-around failed", e);
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }

    // ========================================================================
    // Target-Based Rotation
    // ========================================================================

    /**
     * Rotate camera toward a world position to bring it on screen.
     * Used for "meet in the middle" behavior with mouse movements.
     * 
     * @param targetYawDegrees the yaw angle to rotate toward
     * @param minDegrees minimum rotation amount
     * @param maxDegrees maximum rotation amount
     * @return CompletableFuture that completes when rotation is done
     */
    public CompletableFuture<Void> rotateTowardTarget(double targetYawDegrees, int minDegrees, int maxDegrees) {
        int currentYaw = client.getCameraYaw();
        int targetYaw = (int) (targetYawDegrees * JAU_PER_DEGREE);
        int deltaJau = calculateShortestRotation(currentYaw, targetYaw);
        
        // Clamp to min/max range
        int minJau = (int) (minDegrees * JAU_PER_DEGREE);
        int maxJau = (int) (maxDegrees * JAU_PER_DEGREE);
        
        int sign = deltaJau >= 0 ? 1 : -1;
        int absJau = Math.abs(deltaJau);
        absJau = Math.max(minJau, Math.min(absJau, maxJau));
        
        return rotateByJau(sign * absJau, 0);
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Calculate the shortest rotation from current to target yaw.
     * 
     * @param currentJau current yaw in JAU
     * @param targetJau target yaw in JAU
     * @return delta JAU (positive = clockwise, negative = counter-clockwise)
     */
    private int calculateShortestRotation(int currentJau, int targetJau) {
        // Normalize to 0-2047
        currentJau = ((currentJau % FULL_ROTATION_JAU) + FULL_ROTATION_JAU) % FULL_ROTATION_JAU;
        targetJau = ((targetJau % FULL_ROTATION_JAU) + FULL_ROTATION_JAU) % FULL_ROTATION_JAU;
        
        int delta = targetJau - currentJau;
        
        // Take shortest path
        if (delta > FULL_ROTATION_JAU / 2) {
            delta -= FULL_ROTATION_JAU;
        } else if (delta < -FULL_ROTATION_JAU / 2) {
            delta += FULL_ROTATION_JAU;
        }
        
        return delta;
    }

    /**
     * Smooth step function for ease-in-out interpolation.
     */
    private double smoothStep(double t) {
        return t * t * (3 - 2 * t);
    }

    /**
     * Convert degrees to JAU.
     */
    public static int degreesToJau(double degrees) {
        return (int) (degrees * JAU_PER_DEGREE);
    }

    /**
     * Convert JAU to degrees.
     */
    public static double jauToDegrees(int jau) {
        return jau * DEGREES_PER_JAU;
    }

    /**
     * Get current camera yaw in degrees.
     * 
     * @return yaw angle 0-360
     */
    public double getCurrentYawDegrees() {
        int jau = client.getCameraYaw();
        return ((jau * DEGREES_PER_JAU) % 360 + 360) % 360;
    }

    /**
     * Get current camera pitch in degrees.
     * 
     * @return pitch angle (lower = looking up, higher = looking down)
     */
    public double getCurrentPitchDegrees() {
        int jau = client.getCameraPitch();
        return jau * DEGREES_PER_JAU;
    }

    /**
     * Get time since last camera movement.
     * 
     * @return milliseconds since last movement
     */
    public long getTimeSinceLastMovement() {
        return System.currentTimeMillis() - lastMovementTime;
    }

    /**
     * Check if the camera hold frequency check passes based on profile.
     * 
     * @return true if camera hold should be performed
     */
    public boolean shouldPerformCameraHold() {
        if (playerProfile != null) {
            double frequency = playerProfile.getProfileData().getCameraHoldFrequency();
            return randomization.chance(frequency);
        }
        // Default 15% if no profile
        return randomization.chance(0.15);
    }

    /**
     * Release all camera control keys to prevent stuck keys.
     * Called on shutdown and in emergency situations.
     */
    public void releaseAllKeys() {
        try {
            robot.keyRelease(KeyEvent.VK_LEFT);
        } catch (Exception e) {
            log.debug("Failed to release VK_LEFT: {}", e.getMessage());
        }
        try {
            robot.keyRelease(KeyEvent.VK_RIGHT);
        } catch (Exception e) {
            log.debug("Failed to release VK_RIGHT: {}", e.getMessage());
        }
        try {
            robot.keyRelease(KeyEvent.VK_UP);
        } catch (Exception e) {
            log.debug("Failed to release VK_UP: {}", e.getMessage());
        }
        try {
            robot.keyRelease(KeyEvent.VK_DOWN);
        } catch (Exception e) {
            log.debug("Failed to release VK_DOWN: {}", e.getMessage());
        }
        
        rotating = false;
        log.debug("Released all camera control keys");
    }

    /**
     * Shutdown the executor and release all keys.
     */
    public void shutdown() {
        // Release any stuck keys first
        releaseAllKeys();
        
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

