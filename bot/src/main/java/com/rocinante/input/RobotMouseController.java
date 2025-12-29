package com.rocinante.input;

import com.rocinante.behavior.FatigueModel;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Humanized mouse controller using java.awt.Robot.
 * Implements all specifications from REQUIREMENTS.md Section 3.1:
 *
 * - Bezier curve-based movement with distance-dependent control points
 * - Sigmoid velocity profile (slow-fast-slow)
 * - Perlin noise injection for natural path variation
 * - Overshoot simulation (8-15% of movements)
 * - Micro-corrections (20% of movements)
 * - Click position variance (2D Gaussian)
 * - Misclick simulation (1-3%)
 * - Click fatigue after 200+ clicks
 * - Idle behavior (stationary, drift, rest position)
 *
 * Compatible with Linux headless environments (Xvfb).
 */
@Slf4j
@Singleton
public class RobotMouseController {

    // Movement algorithm constants from REQUIREMENTS.md Section 3.1.1
    private static final int SHORT_DISTANCE_THRESHOLD = 200;
    private static final int MEDIUM_DISTANCE_THRESHOLD = 500;
    private static final int SHORT_CONTROL_POINTS = 3;
    private static final int MEDIUM_CONTROL_POINTS = 4;
    private static final int LONG_CONTROL_POINTS = 5;

    // Control point offset: 5-15% of total distance (reduced from 25%)
    private static final double MIN_CONTROL_OFFSET_PERCENT = 0.05;
    private static final double MAX_CONTROL_OFFSET_PERCENT = 0.15;

    // Duration calculation constants
    private static final double DURATION_DISTANCE_FACTOR = 10.0;
    private static final double DURATION_RANDOM_MEAN = 100.0;
    private static final double DURATION_RANDOM_STDDEV = 25.0;
    private static final long MIN_DURATION_MS = 80;
    private static final long MAX_DURATION_MS = 1500;

    // Noise injection constants
    private static final double MIN_NOISE_AMPLITUDE = 1.0;
    private static final double MAX_NOISE_AMPLITUDE = 3.0;
    private static final long NOISE_SAMPLE_INTERVAL_MS = 7; // 5-10ms, use middle

    // Overshoot constants (8-15% probability, 3-12 pixels)
    private static final double MIN_OVERSHOOT_PROBABILITY = 0.08;
    private static final double MAX_OVERSHOOT_PROBABILITY = 0.15;
    private static final int MIN_OVERSHOOT_PIXELS = 3;
    private static final int MAX_OVERSHOOT_PIXELS = 12;
    private static final long MIN_OVERSHOOT_DELAY_MS = 50;
    private static final long MAX_OVERSHOOT_DELAY_MS = 150;

    // Micro-correction constants (20% probability, 1-3 pixels)
    private static final double MICRO_CORRECTION_PROBABILITY = 0.20;
    private static final int MIN_MICRO_CORRECTION_PIXELS = 1;
    private static final int MAX_MICRO_CORRECTION_PIXELS = 3;
    private static final long MIN_MICRO_CORRECTION_DELAY_MS = 100;
    private static final long MAX_MICRO_CORRECTION_DELAY_MS = 200;

    // Click timing constants (REQUIREMENTS 3.1.2)
    private static final double CLICK_DURATION_MEAN_MS = 85.0;
    private static final double CLICK_DURATION_STDDEV_MS = 15.0;
    private static final long MIN_CLICK_DURATION_MS = 60;
    private static final long MAX_CLICK_DURATION_MS = 120;
    private static final long MIN_DOUBLE_CLICK_INTERVAL_MS = 80;
    private static final long MAX_DOUBLE_CLICK_INTERVAL_MS = 180;

    // Misclick constants (1-3% miss by 5-20 pixels)
    private static final int MIN_MISCLICK_OFFSET = 5;
    private static final int MAX_MISCLICK_OFFSET = 20;
    private static final long MIN_MISCLICK_CORRECTION_DELAY_MS = 200;
    private static final long MAX_MISCLICK_CORRECTION_DELAY_MS = 500;

    // Click fatigue threshold
    private static final int CLICK_FATIGUE_THRESHOLD = 200;
    private static final double MIN_FATIGUE_VARIANCE_MULTIPLIER = 1.10;
    private static final double MAX_FATIGUE_VARIANCE_MULTIPLIER = 1.30;

    // Idle behavior constants (REQUIREMENTS 3.1.3)
    private static final double IDLE_STATIONARY_PROBABILITY = 0.70;
    private static final double IDLE_DRIFT_PROBABILITY = 0.20;
    // Rest position = remaining 10%
    private static final int MIN_DRIFT_PIXELS = 5;
    private static final int MAX_DRIFT_PIXELS = 30;
    private static final long MIN_DRIFT_DURATION_MS = 500;
    private static final long MAX_DRIFT_DURATION_MS = 2000;

    private final Robot robot;
    private final Client client;
    private final Randomization randomization;
    private final PerlinNoise perlinNoise;
    private final PlayerProfile playerProfile;
    private final ScheduledExecutorService executor;

    /**
     * FatigueModel for fatigue-based click variance adjustment.
     * When set, click variance is multiplied by fatigue factor.
     */
    @Setter
    @Nullable
    private FatigueModel fatigueModel;

    /**
     * Flag indicating a click operation is currently in progress.
     * Used to coordinate between CombatTask and CombatManager to prevent
     * race conditions on the same game tick.
     */
    @Getter
    private volatile boolean clickInProgress = false;

    /**
     * MouseCameraCoupler for coordinated camera+mouse movements.
     * When set, long movements may trigger camera adjustments.
     */
    @Setter
    @Nullable
    private MouseCameraCoupler cameraCoupler;

    /**
     * InefficiencyInjector for hesitation and other inefficiencies.
     * When set, may add hesitation delays before clicks.
     */
    @Setter
    @Nullable
    private com.rocinante.behavior.InefficiencyInjector inefficiencyInjector;

    @Getter
    private volatile Point currentPosition;

    @Getter
    private volatile int sessionClickCount = 0;

    private volatile long lastMovementTime = System.currentTimeMillis();
    private volatile boolean isMoving = false;
    private long movementSeed = System.nanoTime();
    
    // Cached canvas offset (updated periodically)
    // Since we enforce fixed mode, window position changes are rare.
    // 30 second interval is safe and reduces unnecessary AWT calls.
    private volatile Point canvasOffset = new Point(0, 0);
    private volatile long lastCanvasOffsetUpdate = 0;
    private static final long CANVAS_OFFSET_UPDATE_INTERVAL_MS = 30_000;

    @Inject
    public RobotMouseController(Client client, Randomization randomization, PerlinNoise perlinNoise, PlayerProfile playerProfile) throws AWTException {
        this.robot = new Robot();
        this.robot.setAutoDelay(0); // We handle delays ourselves
        this.client = client;
        this.randomization = randomization;
        this.perlinNoise = perlinNoise;
        this.playerProfile = playerProfile;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RobotMouseController");
            t.setDaemon(true);
            return t;
        });

        // Initialize current position
        try {
            this.currentPosition = MouseInfo.getPointerInfo().getLocation();
        } catch (Exception e) {
            this.currentPosition = new Point(400, 300); // Fallback
        }

        log.info("RobotMouseController initialized");
    }
    
    // ========================================================================
    // Canvas Offset Translation
    // ========================================================================
    
    /**
     * Get the current canvas offset (screen position of the game canvas).
     * This is used to translate canvas-relative widget coordinates to absolute screen coordinates.
     * The offset is cached and updated periodically to avoid frequent AWT calls.
     */
    private Point getCanvasOffset() {
        long now = System.currentTimeMillis();
        if (now - lastCanvasOffsetUpdate > CANVAS_OFFSET_UPDATE_INTERVAL_MS) {
            updateCanvasOffset();
        }
        return canvasOffset;
    }
    
    /**
     * Update the cached canvas offset by querying the game canvas position.
     */
    private void updateCanvasOffset() {
        try {
            Canvas canvas = client.getCanvas();
            if (canvas != null && canvas.isShowing()) {
                Point screenPos = canvas.getLocationOnScreen();
                // Log if this is a significant change (first detection or window moved)
                if (canvasOffset.x == 0 && canvasOffset.y == 0 && (screenPos.x != 0 || screenPos.y != 0)) {
                    log.info("Canvas screen position detected: ({}, {})", screenPos.x, screenPos.y);
                } else if (Math.abs(screenPos.x - canvasOffset.x) > 10 || Math.abs(screenPos.y - canvasOffset.y) > 10) {
                    log.debug("Canvas position changed: ({}, {}) -> ({}, {})", 
                            canvasOffset.x, canvasOffset.y, screenPos.x, screenPos.y);
                }
                canvasOffset = screenPos;
            }
        } catch (Exception e) {
            // Canvas may not be displayable yet, keep previous offset
            log.trace("Could not get canvas offset: {}", e.getMessage());
        }
        lastCanvasOffsetUpdate = System.currentTimeMillis();
    }
    
    /**
     * Translate a CanvasPoint to a ScreenPoint.
     * 
     * @param canvas point in canvas-relative coordinates
     * @return point in screen-absolute coordinates
     */
    public ScreenPoint canvasToScreen(CanvasPoint canvas) {
        Point offset = getCanvasOffset();
        return new ScreenPoint(canvas.getX() + offset.x, canvas.getY() + offset.y);
    }
    
    /**
     * Translate a canvas-relative Rectangle to absolute screen coordinates.
     * 
     * @param canvasBounds Rectangle with canvas-relative coordinates
     * @return Rectangle with absolute screen coordinates
     */
    public Rectangle canvasToScreen(Rectangle canvasBounds) {
        Point offset = getCanvasOffset();
        return new Rectangle(
            canvasBounds.x + offset.x,
            canvasBounds.y + offset.y,
            canvasBounds.width,
            canvasBounds.height
        );
    }
    
    /**
     * Force update of the canvas offset cache.
     * Call this when the window is known to have moved or resized.
     */
    public void invalidateCanvasOffset() {
        lastCanvasOffsetUpdate = 0;
    }

    // ========================================================================
    // Movement Methods
    // ========================================================================

    /**
     * Move mouse to a ScreenPoint using humanized Bezier curve movement.
     * 
     * <p>This is the type-safe way to move to screen coordinates.
     *
     * @param target the target point in screen coordinates
     * @return CompletableFuture that completes when movement is done
     */
    public CompletableFuture<Void> moveTo(ScreenPoint target) {
        return moveToScreen(target.getX(), target.getY());
    }

    /**
     * Move mouse to screen coordinates using humanized Bezier curve movement.
     * 
     * <p>Use this only when you have actual screen coordinates (e.g., from MouseInfo).
     * For game element coordinates, use {@link #moveToCanvas(int, int)}.
     *
     * <p><b>THE HARD GUARD:</b> This is the ONLY place that blocks clicks outside the
     * game window. All mouse operations flow through here. Planning/visibility checks
     * belong at higher layers (InteractionHelper) which should handle camera rotation
     * before reaching this point.
     *
     * @param screenX target X in screen coordinates
     * @param screenY target Y in screen coordinates
     * @return CompletableFuture that completes when movement is done
     * @throws IllegalArgumentException via failed future if point is outside canvas
     */
    public CompletableFuture<Void> moveToScreen(int screenX, int screenY) {
        // THE HARD GUARD: Nothing leaves the game window. Period.
        if (!isPointInViewport(screenX, screenY)) {
            log.error("HARD GUARD: Screen point ({}, {}) is outside canvas - this should have been caught earlier!", 
                    screenX, screenY);
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Screen point (" + screenX + ", " + screenY + ") is outside canvas - planning layer should have rotated camera"));
        }
        
        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                executeMovement(screenX, screenY);
                future.complete(null);
            } catch (Exception e) {
                log.error("Mouse movement failed", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Move mouse to a random point within a hitbox using humanized movement.
     * Click position uses 2D Gaussian as per REQUIREMENTS 3.1.2.
     * 
     * NOTE: The hitbox is assumed to be in CANVAS-RELATIVE coordinates (from widget bounds).
     * This method automatically translates to screen coordinates.
     *
     * @param hitbox the target hitbox (canvas-relative)
     * @return CompletableFuture that completes when movement is done
     */
    public CompletableFuture<Void> moveTo(Rectangle hitbox) {
        // Translate canvas-relative hitbox to screen coordinates
        Rectangle screenHitbox = canvasToScreen(hitbox);
        log.debug("moveTo hitbox: canvas({},{} {}x{}) -> screen({},{} {}x{})",
                hitbox.x, hitbox.y, hitbox.width, hitbox.height,
                screenHitbox.x, screenHitbox.y, screenHitbox.width, screenHitbox.height);
        int[] clickPos = generateClickPosition(screenHitbox);
        return moveToScreen(clickPos[0], clickPos[1]);
    }

    /**
     * Move mouse to a CanvasPoint using humanized movement.
     * 
     * <p>This is the type-safe way to move to canvas coordinates.
     * The coordinates are automatically translated to screen coordinates.
     *
     * @param canvas point in canvas-relative coordinates
     * @return CompletableFuture that completes when movement is done
     */
    public CompletableFuture<Void> moveToCanvas(CanvasPoint canvas) {
        ScreenPoint screen = canvasToScreen(canvas);
        log.debug("moveToCanvas: {} -> {}", canvas, screen);
        return moveToScreen(screen.getX(), screen.getY());
    }

    /**
     * Move mouse to canvas-relative coordinates using humanized movement.
     * 
     * This method translates canvas coordinates to screen coordinates before moving.
     * Use this when you have coordinates from game elements (widgets, NPCs, objects)
     * which are always canvas-relative.
     * 
     * NO GUARD HERE: The hard guard in moveToScreen validates canvas bounds.
     * Planning/visibility checks should be done at higher layers (InteractionHelper)
     * which can trigger camera rotation if needed BEFORE calling this method.
     *
     * @param canvasX X coordinate relative to game canvas
     * @param canvasY Y coordinate relative to game canvas
     * @return CompletableFuture that completes when movement is done
     */
    public CompletableFuture<Void> moveToCanvas(int canvasX, int canvasY) {
        return moveToCanvas(new CanvasPoint(canvasX, canvasY));
    }
    
    /**
     * Check if a point is within the 3D game area (world viewport).
     * This is where game objects, NPCs, and ground items are RENDERED,
     * but their clickboxes may extend beyond this into the UI space.
     * 
     * USE CASE: Planning and visibility decisions (e.g., "do we need to rotate camera?")
     * NOT FOR: Blocking clicks - objects at screen edges have valid clickboxes outside this area
     * 
     * @param x X coordinate
     * @param y Y coordinate
     * @return true if point is within the 3D game area
     */
    public boolean isPointInGameArea(int x, int y) {
        if (client == null) {
            return true; // Can't validate without client
        }
        
        // Get 3D game area bounds from client
        int areaX = client.getViewportXOffset();
        int areaY = client.getViewportYOffset();
        int areaWidth = client.getViewportWidth();
        int areaHeight = client.getViewportHeight();
        
        // Fallback for fixed mode
        if (areaWidth <= 0 || areaHeight <= 0) {
            areaX = 4;
            areaY = 4;
            areaWidth = 512;
            areaHeight = 334;
        }
        
        return x >= areaX && x < (areaX + areaWidth) &&
               y >= areaY && y < (areaY + areaHeight);
    }
    
    /**
     * Check if a point is within the full viewport (entire game window).
     * This includes UI elements like inventory, chat, minimap, etc.
     * 
     * @param x X coordinate  
     * @param y Y coordinate
     * @return true if point is within the game window
     */
    public boolean isPointInViewport(int screenX, int screenY) {
        if (client == null) {
            return true;
        }
        
        Canvas canvas = client.getCanvas();
        if (canvas == null) {
            // Fallback to fixed mode dimensions - assume no offset
            return screenX >= 0 && screenX < 765 && screenY >= 0 && screenY < 503;
        }
        
        // Get the canvas position on screen and its dimensions
        Point offset = getCanvasOffset();
        int width = canvas.getWidth();
        int height = canvas.getHeight();
        
        // Screen coordinates must be within the canvas area on screen
        // Canvas at screen position (offset.x, offset.y) with size (width, height)
        return screenX >= offset.x && screenX < (offset.x + width) 
            && screenY >= offset.y && screenY < (offset.y + height);
    }
    
    /**
     * Clamp a point to be within the full viewport.
     * 
     * @param x X coordinate
     * @param y Y coordinate
     * @return clamped point
     */
    public Point clampToViewport(int screenX, int screenY) {
        Point offset = getCanvasOffset();
        
        Canvas canvas = client != null ? client.getCanvas() : null;
        int width = canvas != null ? canvas.getWidth() : 765;
        int height = canvas != null ? canvas.getHeight() : 503;
        
        // Clamp screen coordinates to the canvas area on screen
        return new Point(
            Math.max(offset.x, Math.min(screenX, offset.x + width - 1)),
            Math.max(offset.y, Math.min(screenY, offset.y + height - 1))
        );
    }
    
    /**
     * Clamp a point to be within the game area (3D world canvas).
     * 
     * @param x X coordinate
     * @param y Y coordinate  
     * @return clamped point within game area
     */
    public Point clampToGameArea(int x, int y) {
        if (client == null) {
            return new Point(Math.max(4, Math.min(x, 515)), Math.max(4, Math.min(y, 337)));
        }
        
        int areaX = client.getViewportXOffset();
        int areaY = client.getViewportYOffset();
        int areaWidth = client.getViewportWidth();
        int areaHeight = client.getViewportHeight();
        
        if (areaWidth <= 0 || areaHeight <= 0) {
            areaX = 4;
            areaY = 4;
            areaWidth = 512;
            areaHeight = 334;
        }
        
        return new Point(
            Math.max(areaX, Math.min(x, areaX + areaWidth - 1)),
            Math.max(areaY, Math.min(y, areaY + areaHeight - 1))
        );
    }


    /**
     * Execute the actual mouse movement with all humanization features.
     */
    private void executeMovement(int targetX, int targetY) throws InterruptedException {
        isMoving = true;
        movementSeed = System.nanoTime();

        Point start = currentPosition;
        Point target = new Point(targetX, targetY);

        double distance = start.distance(target);
        if (distance < 1) {
            isMoving = false;
            return; // Already at target
        }

        // Camera coupling for long movements (>500px, 30% chance for 5-20° rotation)
        // This runs asynchronously - camera moves while mouse moves
        if (cameraCoupler != null && distance > 500) {
            try {
                cameraCoupler.onBeforeMouseMove(start.x, start.y, targetX, targetY);
                // Note: Camera rotation happens concurrently, we don't wait
            } catch (Exception e) {
                log.trace("Camera coupling failed: {}", e.getMessage());
            }
        }

        // Generate Bezier curve control points
        List<Point> controlPoints = generateControlPoints(start, target, distance);

        // Calculate movement duration
        long duration = calculateMovementDuration(distance);

        // Apply profile speed multiplier
        duration = Math.round(duration / playerProfile.getMouseSpeedMultiplier());
        duration = Randomization.clamp(duration, MIN_DURATION_MS, MAX_DURATION_MS);

        // Calculate noise amplitude for this movement
        double noiseAmplitude = randomization.uniformRandom(MIN_NOISE_AMPLITUDE, MAX_NOISE_AMPLITUDE);

        // Execute the movement
        long startTime = System.currentTimeMillis();
        long elapsed = 0;

        while (elapsed < duration) {
            double t = (double) elapsed / duration;

            // Apply sigmoid velocity curve for slow-fast-slow motion
            double adjustedT = sigmoidProgress(t);

            // Calculate point on Bezier curve
            Point bezierPoint = evaluateBezier(controlPoints, adjustedT);

            // Add Perlin noise perpendicular to movement direction
            double[] noiseOffset = perlinNoise.getPathOffset(t, noiseAmplitude, movementSeed);
            int noisyX = bezierPoint.x + (int) Math.round(noiseOffset[0]);
            int noisyY = bezierPoint.y + (int) Math.round(noiseOffset[1]);

            // Clamp to viewport (noise could push us outside)
            Point clamped = clampToViewport(noisyX, noisyY);

            // Move mouse
            robot.mouseMove(clamped.x, clamped.y);
            currentPosition = clamped;

            // Sleep for noise sample interval
            Thread.sleep(NOISE_SAMPLE_INTERVAL_MS);
            elapsed = System.currentTimeMillis() - startTime;
        }

        // Final position (ensure we hit target)
        robot.mouseMove(targetX, targetY);
        currentPosition = target;

        // Handle overshoot simulation
        if (shouldOvershoot()) {
            executeOvershoot(target);
        }
        // Handle micro-correction (only if no overshoot)
        else if (shouldMicroCorrect()) {
            executeMicroCorrection(target);
        }

        lastMovementTime = System.currentTimeMillis();
        isMoving = false;
    }

    /**
     * Generate Bezier curve control points based on distance.
     * Control point count: 3 for <200px, 4 for 200-500px, 5 for >500px
     */
    private List<Point> generateControlPoints(Point start, Point end, double distance) {
        int numControlPoints;
        if (distance < SHORT_DISTANCE_THRESHOLD) {
            numControlPoints = SHORT_CONTROL_POINTS;
        } else if (distance < MEDIUM_DISTANCE_THRESHOLD) {
            numControlPoints = MEDIUM_CONTROL_POINTS;
        } else {
            numControlPoints = LONG_CONTROL_POINTS;
        }

        List<Point> points = new ArrayList<>();
        points.add(start);

        // Calculate perpendicular direction
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double perpX = -dy / distance;
        double perpY = dx / distance;

        // Add intermediate control points
        for (int i = 1; i < numControlPoints - 1; i++) {
            double t = (double) i / (numControlPoints - 1);

            // Base point along direct path
            int baseX = (int) (start.x + dx * t);
            int baseY = (int) (start.y + dy * t);

            // Random perpendicular offset (5-15% of distance)
            double offsetPercent = randomization.uniformRandom(MIN_CONTROL_OFFSET_PERCENT, MAX_CONTROL_OFFSET_PERCENT);
            double offsetDistance = distance * offsetPercent;

            // Random direction (positive or negative)
            if (randomization.chance(0.5)) {
                offsetDistance = -offsetDistance;
            }

            int controlX = baseX + (int) (perpX * offsetDistance);
            int controlY = baseY + (int) (perpY * offsetDistance);

            points.add(new Point(controlX, controlY));
        }

        points.add(end);
        return points;
    }

    /**
     * Evaluate Bezier curve at parameter t using De Casteljau's algorithm.
     */
    private Point evaluateBezier(List<Point> controlPoints, double t) {
        List<Point> points = new ArrayList<>(controlPoints);

        while (points.size() > 1) {
            List<Point> newPoints = new ArrayList<>();
            for (int i = 0; i < points.size() - 1; i++) {
                Point p1 = points.get(i);
                Point p2 = points.get(i + 1);
                int x = (int) Math.round(p1.x + (p2.x - p1.x) * t);
                int y = (int) Math.round(p1.y + (p2.y - p1.y) * t);
                newPoints.add(new Point(x, y));
            }
            points = newPoints;
        }

        return points.get(0);
    }

    /**
     * Sigmoid function for velocity curve: slow start (0-15%), fast middle (15-85%), slow end (85-100%).
     * Uses a modified sigmoid to achieve the desired profile.
     */
    private double sigmoidProgress(double t) {
        // Map t to sigmoid input range for desired slow-fast-slow profile
        // Use a steeper sigmoid centered at 0.5
        double x = (t - 0.5) * 10; // Scale factor for steepness
        double sigmoid = 1.0 / (1.0 + Math.exp(-x));

        // Normalize to [0, 1] range
        double minSigmoid = 1.0 / (1.0 + Math.exp(5));
        double maxSigmoid = 1.0 / (1.0 + Math.exp(-5));

        return (sigmoid - minSigmoid) / (maxSigmoid - minSigmoid);
    }

    /**
     * Calculate movement duration based on distance.
     * Formula: sqrt(distance) * 10 + gaussianRandom(50, 150)
     * Per REQUIREMENTS.md 3.1.1: bounded to 50-150ms for the random component.
     */
    private long calculateMovementDuration(double distance) {
        double baseDuration = Math.sqrt(distance) * DURATION_DISTANCE_FACTOR;
        double randomAddition = randomization.gaussianRandom(DURATION_RANDOM_MEAN, DURATION_RANDOM_STDDEV, 50.0, 150.0);
        return Math.round(baseDuration + randomAddition);
    }

    /**
     * Check if this movement should include overshoot.
     */
    private boolean shouldOvershoot() {
        double probability = playerProfile.getOvershootProbability();
        return randomization.chance(probability);
    }

    /**
     * Execute overshoot: move past target, then correct back.
     */
    private void executeOvershoot(Point target) throws InterruptedException {
        int overshootPixels = randomization.uniformRandomInt(MIN_OVERSHOOT_PIXELS, MAX_OVERSHOOT_PIXELS);

        // Random direction
        double angle = randomization.uniformRandom(0, 2 * Math.PI);
        int overshootX = target.x + (int) (Math.cos(angle) * overshootPixels);
        int overshootY = target.y + (int) (Math.sin(angle) * overshootPixels);

        // Clamp overshoot to viewport
        Point clampedOvershoot = clampToViewport(overshootX, overshootY);
        robot.mouseMove(clampedOvershoot.x, clampedOvershoot.y);
        currentPosition = clampedOvershoot;

        // Delay before correction
        long delay = randomization.uniformRandomLong(MIN_OVERSHOOT_DELAY_MS, MAX_OVERSHOOT_DELAY_MS);
        Thread.sleep(delay);

        // Correct back to target with a quick movement
        robot.mouseMove(target.x, target.y);
        currentPosition = target;

        log.trace("Overshoot: {} pixels, corrected after {}ms", overshootPixels, delay);
    }

    /**
     * Check if this movement should include micro-correction.
     */
    private boolean shouldMicroCorrect() {
        return randomization.chance(playerProfile.getMicroCorrectionProbability());
    }

    /**
     * Execute micro-correction: small adjustment after initial landing.
     */
    private void executeMicroCorrection(Point target) throws InterruptedException {
        // Delay before micro-correction
        long delay = randomization.uniformRandomLong(MIN_MICRO_CORRECTION_DELAY_MS, MAX_MICRO_CORRECTION_DELAY_MS);
        Thread.sleep(delay);

        int correctionPixels = randomization.uniformRandomInt(MIN_MICRO_CORRECTION_PIXELS, MAX_MICRO_CORRECTION_PIXELS);

        // Small random direction
        double angle = randomization.uniformRandom(0, 2 * Math.PI);
        int correctedX = target.x + (int) (Math.cos(angle) * correctionPixels);
        int correctedY = target.y + (int) (Math.sin(angle) * correctionPixels);

        // Clamp micro-correction to viewport
        Point clampedCorrection = clampToViewport(correctedX, correctedY);
        robot.mouseMove(clampedCorrection.x, clampedCorrection.y);
        currentPosition = clampedCorrection;

        log.trace("Micro-correction: {} pixels after {}ms", correctionPixels, delay);
    }

    // ========================================================================
    // Click Methods
    // ========================================================================

    /**
     * Click at the current mouse position.
     *
     * @return CompletableFuture that completes when click is done
     */
    public CompletableFuture<Void> click() {
        return executeClick(InputEvent.BUTTON1_DOWN_MASK, false);
    }

    /**
     * Move to a random point within hitbox and click.
     * 
     * NOTE: The hitbox is assumed to be in CANVAS-RELATIVE coordinates (from widget bounds).
     * This method automatically translates to screen coordinates.
     *
     * @param hitbox the target hitbox (canvas-relative)
     * @return CompletableFuture that completes when click is done
     */
    public CompletableFuture<Void> click(Rectangle hitbox) {
        // Translate canvas-relative hitbox to screen coordinates
        Rectangle screenHitbox = canvasToScreen(hitbox);
        return clickScreen(screenHitbox, false);
    }

    /**
     * Move to a random point within screen-coordinate hitbox and click.
     */
    private CompletableFuture<Void> clickScreen(Rectangle screenHitbox, boolean isCorrectionClick) {
        int[] clickPos = generateClickPosition(screenHitbox);

        // Simulate misclick (unless this is already a correction click)
        if (!isCorrectionClick && shouldMisclick()) {
            return executeMisclick(screenHitbox, clickPos);
        }

        // clickPos is already in screen coordinates (from screenHitbox)
        return moveToScreen(clickPos[0], clickPos[1]).thenCompose(v -> click());
    }

    /**
     * Move to specific screen coordinates and click.
     *
     * @param screenX X coordinate in screen coordinates
     * @param screenY Y coordinate in screen coordinates
     * @return CompletableFuture that completes when click is done
     */
    public CompletableFuture<Void> clickAt(int screenX, int screenY) {
        return moveToScreen(screenX, screenY).thenCompose(v -> click());
    }

    /**
     * Right-click at a random point within hitbox.
     * 
     * NOTE: The hitbox is assumed to be in CANVAS-RELATIVE coordinates (from widget bounds).
     * This method automatically translates to screen coordinates.
     *
     * @param hitbox the target hitbox (canvas-relative)
     * @return CompletableFuture that completes when click is done
     */
    public CompletableFuture<Void> rightClick(Rectangle hitbox) {
        // Translate canvas-relative hitbox to screen coordinates
        Rectangle screenHitbox = canvasToScreen(hitbox);
        int[] clickPos = generateClickPosition(screenHitbox);
        // clickPos is in screen coordinates (from screenHitbox)
        return moveToScreen(clickPos[0], clickPos[1])
                .thenCompose(v -> executeClick(InputEvent.BUTTON3_DOWN_MASK, false));
    }

    /**
     * Double-click at a random point within hitbox.
     * 
     * NOTE: The hitbox is assumed to be in CANVAS-RELATIVE coordinates (from widget bounds).
     * This method automatically translates to screen coordinates.
     *
     * @param hitbox the target hitbox (canvas-relative)
     * @return CompletableFuture that completes when double-click is done
     */
    public CompletableFuture<Void> doubleClick(Rectangle hitbox) {
        // Translate canvas-relative hitbox to screen coordinates
        Rectangle screenHitbox = canvasToScreen(hitbox);
        int[] clickPos = generateClickPosition(screenHitbox);
        // clickPos is in screen coordinates (from screenHitbox)
        return moveToScreen(clickPos[0], clickPos[1])
                .thenCompose(v -> executeClick(InputEvent.BUTTON1_DOWN_MASK, true));
    }

    /**
     * Execute a mouse click with humanized timing.
     */
    private CompletableFuture<Void> executeClick(int buttonMask, boolean doubleClick) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        // Set flag to indicate click is in progress
        clickInProgress = true;

        executor.execute(() -> {
            try {
                // Check for hesitation before click (5% chance, 500-1500ms hover)
                if (inefficiencyInjector != null && inefficiencyInjector.shouldHesitate()) {
                    long hesitationDelay = inefficiencyInjector.getAdjustedHesitationDelay();
                    log.trace("Hesitating for {}ms before click", hesitationDelay);
                    Thread.sleep(hesitationDelay);
                }
                
                // First click
                performSingleClick(buttonMask);
                sessionClickCount++;

                // Second click for double-click
                if (doubleClick) {
                    long interval = randomization.uniformRandomLong(
                            MIN_DOUBLE_CLICK_INTERVAL_MS, MAX_DOUBLE_CLICK_INTERVAL_MS);
                    Thread.sleep(interval);
                    performSingleClick(buttonMask);
                    sessionClickCount++;
                }

                clickInProgress = false;
                future.complete(null);
            } catch (Exception e) {
                clickInProgress = false;
                log.error("Click failed", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Perform a single click with humanized hold duration.
     */
    private void performSingleClick(int buttonMask) throws InterruptedException {
        // Calculate click duration with fatigue modifier
        long holdDuration = calculateClickDuration();

        robot.mousePress(buttonMask);
        Thread.sleep(holdDuration);
        robot.mouseRelease(buttonMask);
    }

    /**
     * Calculate click hold duration based on profile and fatigue.
     */
    private long calculateClickDuration() {
        double baseDuration = randomization.gaussianRandom(
                CLICK_DURATION_MEAN_MS, CLICK_DURATION_STDDEV_MS,
                MIN_CLICK_DURATION_MS, MAX_CLICK_DURATION_MS);

        // Apply click variance modifier from profile
        baseDuration *= playerProfile.getClickVarianceModifier();

        return Math.round(baseDuration);
    }

    /**
     * Generate click position within hitbox using 2D Gaussian distribution.
     * As per spec: center at 45-55% of dimensions, σ = 15% of dimension.
     * 
     * Fatigue effects:
     * - Session click count > 200: legacy fatigue variance (1.1-1.3x)
     * - FatigueModel: dynamic variance based on fatigue level
     * 
     * Per REQUIREMENTS.md 4.2.2: Click variance multiplier = 1.0 + (fatigue * 0.4)
     */
    private int[] generateClickPosition(Rectangle hitbox) {
        int[] basePosition = randomization.generateClickPosition(
                hitbox.x, hitbox.y, hitbox.width, hitbox.height);

        // Get combined fatigue variance multiplier
        double fatigueMultiplier = getEffectiveClickVarianceMultiplier();

        // Only apply if there's meaningful variance increase
        if (fatigueMultiplier > 1.01) {
            // Increase spread from center
            int centerX = hitbox.x + hitbox.width / 2;
            int centerY = hitbox.y + hitbox.height / 2;

            int adjustedX = (int) (centerX + (basePosition[0] - centerX) * fatigueMultiplier);
            int adjustedY = (int) (centerY + (basePosition[1] - centerY) * fatigueMultiplier);

            // Clamp to hitbox bounds
            adjustedX = Randomization.clamp(adjustedX, hitbox.x, hitbox.x + hitbox.width - 1);
            adjustedY = Randomization.clamp(adjustedY, hitbox.y, hitbox.y + hitbox.height - 1);

            return new int[]{adjustedX, adjustedY};
        }

        return basePosition;
    }

    /**
     * Get the effective click variance multiplier combining FatigueModel and session click count.
     */
    private double getEffectiveClickVarianceMultiplier() {
        double multiplier = 1.0;

        // Apply FatigueModel multiplier if available
        if (fatigueModel != null) {
            multiplier = fatigueModel.getClickVarianceMultiplier();
        }
        // Fallback: apply session click count fatigue
        else if (sessionClickCount > CLICK_FATIGUE_THRESHOLD) {
            multiplier = randomization.uniformRandom(
                    MIN_FATIGUE_VARIANCE_MULTIPLIER, MAX_FATIGUE_VARIANCE_MULTIPLIER);
        }

        return multiplier;
    }

    /**
     * Check if this click should be a misclick.
     * Per REQUIREMENTS.md 4.2.2: Misclick probability multiplier = 1.0 + (fatigue * 2.0)
     */
    private boolean shouldMisclick() {
        double baseMisclickRate = getEffectiveMisclickRate();
        double misclickMultiplier = getEffectiveMisclickMultiplier();
        double effectiveRate = baseMisclickRate * misclickMultiplier;
        
        // Cap at reasonable maximum (10%)
        effectiveRate = Math.min(effectiveRate, 0.10);
        
        return randomization.chance(effectiveRate);
    }

    /**
     * Get the base misclick rate from profile.
     */
    private double getEffectiveMisclickRate() {
        if (playerProfile != null) {
            return playerProfile.getBaseMisclickRate();
        }
        return playerProfile.getBaseMisclickRate();
    }

    /**
     * Get the misclick probability multiplier from FatigueModel.
     */
    private double getEffectiveMisclickMultiplier() {
        if (fatigueModel != null) {
            return fatigueModel.getMisclickMultiplier();
        }
        return 1.0;
    }

    /**
     * Execute misclick: click outside hitbox, then correct.
     * Note: hitbox should already be in screen coordinates.
     */
    private CompletableFuture<Void> executeMisclick(Rectangle screenHitbox, int[] intendedPos) {
        // Calculate misclick offset
        int offsetDistance = randomization.uniformRandomInt(MIN_MISCLICK_OFFSET, MAX_MISCLICK_OFFSET);
        double angle = randomization.uniformRandom(0, 2 * Math.PI);

        int misclickX = intendedPos[0] + (int) (Math.cos(angle) * offsetDistance);
        int misclickY = intendedPos[1] + (int) (Math.sin(angle) * offsetDistance);

        log.trace("Simulating misclick: intended ({}, {}), actual ({}, {})",
                intendedPos[0], intendedPos[1], misclickX, misclickY);

        // Move to misclick position and click (already in screen coordinates)
        return moveToScreen(misclickX, misclickY)
                .thenCompose(v -> click())
                .thenCompose(v -> {
                    // Delay before correction
                    long delay = randomization.uniformRandomLong(
                            MIN_MISCLICK_CORRECTION_DELAY_MS, MAX_MISCLICK_CORRECTION_DELAY_MS);
                    return sleep(delay);
                })
                .thenCompose(v -> clickScreen(screenHitbox, true)); // Correction click (already screen coords)
    }

    // ========================================================================
    // Scroll Methods
    // ========================================================================

    // Scroll timing constants - humanized scroll behavior
    private static final int MIN_SCROLL_DELAY_MS = 30;
    private static final int MAX_SCROLL_DELAY_MS = 80;
    private static final double SCROLL_SPEED_VARIANCE = 0.3; // ±30% speed variance
    private static final double SCROLL_PAUSE_PROBABILITY = 0.15; // 15% chance of brief pause mid-scroll
    private static final int MIN_SCROLL_PAUSE_MS = 50;
    private static final int MAX_SCROLL_PAUSE_MS = 150;

    /**
     * Scroll the mouse wheel with humanized timing.
     * Positive amount scrolls down, negative scrolls up.
     *
     * <p>Humanization features:
     * <ul>
     *   <li>Variable delay between scroll ticks (30-80ms)</li>
     *   <li>Speed variance per scroll session (±30%)</li>
     *   <li>Occasional brief pauses mid-scroll (15% chance)</li>
     *   <li>Slight acceleration/deceleration pattern</li>
     * </ul>
     *
     * @param amount number of scroll notches (positive = down, negative = up)
     * @return CompletableFuture that completes when scroll is done
     */
    public CompletableFuture<Void> scroll(int amount) {
        if (amount == 0) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                executeScroll(amount);
                future.complete(null);
            } catch (Exception e) {
                log.error("Scroll failed", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Execute humanized scroll with variable timing.
     */
    private void executeScroll(int amount) throws InterruptedException {
        int direction = amount > 0 ? 1 : -1;
        int remaining = Math.abs(amount);

        // Generate session-specific speed multiplier for consistency
        double speedMultiplier = 1.0 + randomization.uniformRandom(-SCROLL_SPEED_VARIANCE, SCROLL_SPEED_VARIANCE);

        log.trace("Scrolling {} notches with speed multiplier {}", amount, speedMultiplier);

        int scrolled = 0;
        while (scrolled < remaining) {
            // Calculate delay with acceleration curve (faster in middle)
            double progress = (double) scrolled / remaining;
            double accelerationFactor = 1.0 - 0.3 * Math.sin(progress * Math.PI); // Slower at start/end

            int baseDelay = randomization.uniformRandomInt(MIN_SCROLL_DELAY_MS, MAX_SCROLL_DELAY_MS);
            int delay = (int) (baseDelay * speedMultiplier * accelerationFactor);

            // Perform single scroll tick
            robot.mouseWheel(direction);
            scrolled++;

            // Apply delay between ticks (not after last one)
            if (scrolled < remaining) {
                Thread.sleep(delay);

                // Occasional pause mid-scroll (simulates human hesitation)
                if (randomization.chance(SCROLL_PAUSE_PROBABILITY)) {
                    int pauseDuration = randomization.uniformRandomInt(MIN_SCROLL_PAUSE_MS, MAX_SCROLL_PAUSE_MS);
                    log.trace("Scroll pause for {}ms at notch {}/{}", pauseDuration, scrolled, remaining);
                    Thread.sleep(pauseDuration);
                }
            }
        }

        log.trace("Scroll completed: {} notches", amount);
    }

    /**
     * Scroll to make an element visible (convenience method).
     * Scrolls in the specified direction until condition is met or max scrolls reached.
     *
     * @param direction positive = down, negative = up
     * @param maxScrolls maximum scroll attempts
     * @return CompletableFuture with number of scrolls performed
     */
    public CompletableFuture<Integer> scrollUntil(int direction, int maxScrolls, java.util.function.BooleanSupplier condition) {
        CompletableFuture<Integer> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                int scrolled = 0;
                int scrollDir = direction > 0 ? 1 : -1;

                while (scrolled < maxScrolls && !condition.getAsBoolean()) {
                    executeScroll(scrollDir);
                    scrolled++;
                    // Brief delay to let UI update
                    Thread.sleep(100);
                }

                future.complete(scrolled);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    // ========================================================================
    // Drag Methods
    // ========================================================================

    // Drag timing constants
    private static final long MIN_DRAG_HOLD_BEFORE_MOVE_MS = 50;
    private static final long MAX_DRAG_HOLD_BEFORE_MOVE_MS = 120;
    private static final long MIN_DRAG_HOLD_AFTER_MOVE_MS = 30;
    private static final long MAX_DRAG_HOLD_AFTER_MOVE_MS = 80;
    private static final double DRAG_WOBBLE_AMPLITUDE = 2.0; // Slight wobble during drag

    /**
     * Perform a humanized drag operation from current position to target.
     *
     * <p>Humanization features:
     * <ul>
     *   <li>Brief hold after mouse down before moving (50-120ms)</li>
     *   <li>Bezier curve movement with slight wobble</li>
     *   <li>Slower movement than regular moves (more deliberate)</li>
     *   <li>Brief hold before mouse up (30-80ms)</li>
     * </ul>
     *
     * @param targetX target X in canvas coordinates
     * @param targetY target Y in canvas coordinates
     * @return CompletableFuture that completes when drag is done
     */
    public CompletableFuture<Void> dragToCanvas(int targetX, int targetY) {
        ScreenPoint screen = canvasToScreen(new CanvasPoint(targetX, targetY));
        return dragToScreen(screen.getX(), screen.getY());
    }

    /**
     * Perform a humanized drag from one point to another.
     *
     * @param from starting point (canvas coordinates)
     * @param to ending point (canvas coordinates)
     * @return CompletableFuture that completes when drag is done
     */
    public CompletableFuture<Void> drag(Point from, Point to) {
        // First move to start position, then drag
        return moveToCanvas(from.x, from.y)
                .thenCompose(v -> dragToCanvas(to.x, to.y));
    }

    /**
     * Perform a humanized drag from one rectangle center to another.
     *
     * @param fromHitbox source hitbox (canvas coordinates)
     * @param toHitbox destination hitbox (canvas coordinates)
     * @return CompletableFuture that completes when drag is done
     */
    public CompletableFuture<Void> drag(Rectangle fromHitbox, Rectangle toHitbox) {
        // Generate humanized click positions within hitboxes
        int[] fromPos = generateClickPosition(canvasToScreen(fromHitbox));
        int[] toPos = generateClickPosition(canvasToScreen(toHitbox));

        return moveToScreen(fromPos[0], fromPos[1])
                .thenCompose(v -> dragToScreen(toPos[0], toPos[1]));
    }

    /**
     * Perform drag to screen coordinates from current position.
     */
    private CompletableFuture<Void> dragToScreen(int screenX, int screenY) {
        // Validate target is within viewport
        if (!isPointInViewport(screenX, screenY)) {
            log.error("Drag target ({}, {}) is outside viewport", screenX, screenY);
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Drag target outside viewport"));
        }

        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                executeDrag(screenX, screenY);
                future.complete(null);
            } catch (Exception e) {
                log.error("Drag failed", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Execute the humanized drag operation.
     */
    private void executeDrag(int targetX, int targetY) throws InterruptedException {
        Point start = currentPosition;
        Point target = new Point(targetX, targetY);
        double distance = start.distance(target);

        log.trace("Executing drag from ({}, {}) to ({}, {}), distance={}", 
                start.x, start.y, targetX, targetY, distance);

        // Press mouse button
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);

        // Brief hold before moving (human hesitation)
        long holdBeforeMove = randomization.uniformRandomLong(
                MIN_DRAG_HOLD_BEFORE_MOVE_MS, MAX_DRAG_HOLD_BEFORE_MOVE_MS);
        Thread.sleep(holdBeforeMove);

        // Execute drag movement (slower than normal movement)
        executeDragMovement(target, distance);

        // Brief hold before releasing (human confirmation)
        long holdAfterMove = randomization.uniformRandomLong(
                MIN_DRAG_HOLD_AFTER_MOVE_MS, MAX_DRAG_HOLD_AFTER_MOVE_MS);
        Thread.sleep(holdAfterMove);

        // Release mouse button
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);

        log.trace("Drag completed");
    }

    /**
     * Execute the movement portion of a drag (slower, with wobble).
     */
    private void executeDragMovement(Point target, double distance) throws InterruptedException {
        isMoving = true;
        movementSeed = System.nanoTime();

        Point start = currentPosition;

        if (distance < 1) {
            isMoving = false;
            return;
        }

        // Generate control points (same as normal movement)
        List<Point> controlPoints = generateControlPoints(start, target, distance);

        // Drag movement is slower (1.5x duration)
        long baseDuration = calculateMovementDuration(distance);
        long duration = (long) (baseDuration * 1.5);
        duration = Math.min(duration, MAX_DURATION_MS * 2); // Allow longer for drags

        // Execute movement with slight wobble
        long startTime = System.currentTimeMillis();
        long elapsed = 0;

        while (elapsed < duration) {
            double t = (double) elapsed / duration;
            double adjustedT = sigmoidProgress(t);

            // Calculate point on curve
            Point bezierPoint = evaluateBezier(controlPoints, adjustedT);

            // Add slight wobble (less than normal noise, simulates hand tremor during drag)
            double wobbleX = Math.sin(t * Math.PI * 4) * DRAG_WOBBLE_AMPLITUDE;
            double wobbleY = Math.cos(t * Math.PI * 3) * DRAG_WOBBLE_AMPLITUDE * 0.7;

            int x = bezierPoint.x + (int) Math.round(wobbleX);
            int y = bezierPoint.y + (int) Math.round(wobbleY);

            // Clamp to viewport
            Point clamped = clampToViewport(x, y);

            robot.mouseMove(clamped.x, clamped.y);
            currentPosition = clamped;

            Thread.sleep(NOISE_SAMPLE_INTERVAL_MS);
            elapsed = System.currentTimeMillis() - startTime;
        }

        // Final position
        robot.mouseMove(target.x, target.y);
        currentPosition = target;

        lastMovementTime = System.currentTimeMillis();
        isMoving = false;
    }

    // ========================================================================
    // Idle Behavior Methods
    // ========================================================================

    /**
     * Perform idle behavior when awaiting next action.
     * As per REQUIREMENTS 3.1.3:
     * - 70%: Stationary
     * - 20%: Small drift (5-30px over 500-2000ms)
     * - 10%: Move to rest position
     *
     * @return CompletableFuture that completes when idle behavior is done
     */
    public CompletableFuture<Void> performIdleBehavior() {
        double roll = randomization.uniformRandom(0, 1);

        if (roll < IDLE_STATIONARY_PROBABILITY) {
            // Stationary - do nothing
            log.trace("Idle: stationary");
            return CompletableFuture.completedFuture(null);
        } else if (roll < IDLE_STATIONARY_PROBABILITY + IDLE_DRIFT_PROBABILITY) {
            // Small drift
            return performIdleDrift();
        } else {
            // Move to rest position
            return moveToRestPosition();
        }
    }

    /**
     * Perform small idle drift movement.
     */
    private CompletableFuture<Void> performIdleDrift() {
        int driftPixels = randomization.uniformRandomInt(MIN_DRIFT_PIXELS, MAX_DRIFT_PIXELS);
        double angle = randomization.uniformRandom(0, 2 * Math.PI);

        int targetX = currentPosition.x + (int) (Math.cos(angle) * driftPixels);
        int targetY = currentPosition.y + (int) (Math.sin(angle) * driftPixels);

        log.trace("Idle: drift {} pixels", driftPixels);

        // Execute slow drift movement
        return executeSlowDrift(targetX, targetY);
    }

    /**
     * Execute a slow drift movement (500-2000ms).
     */
    private CompletableFuture<Void> executeSlowDrift(int targetX, int targetY) {
        // Clamp drift target to viewport
        Point clampedTarget = clampToViewport(targetX, targetY);
        
        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                long duration = randomization.uniformRandomLong(MIN_DRIFT_DURATION_MS, MAX_DRIFT_DURATION_MS);
                long startTime = System.currentTimeMillis();
                Point start = currentPosition;

                while (System.currentTimeMillis() - startTime < duration) {
                    double t = (double) (System.currentTimeMillis() - startTime) / duration;
                    int x = (int) (start.x + (clampedTarget.x - start.x) * t);
                    int y = (int) (start.y + (clampedTarget.y - start.y) * t);

                    // Clamp interpolated point too (paranoid safety)
                    Point clamped = clampToViewport(x, y);
                    robot.mouseMove(clamped.x, clamped.y);
                    currentPosition = clamped;

                    Thread.sleep(20); // Slow update rate for drift
                }

                robot.mouseMove(clampedTarget.x, clampedTarget.y);
                currentPosition = clampedTarget;
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Move mouse to a rest position (inventory, minimap, or chat).
     * ScreenRegion coordinates are canvas-relative, so we translate them.
     */
    private CompletableFuture<Void> moveToRestPosition() {
        ScreenRegion restRegion = playerProfile.selectIdlePosition();
        int[] restPos = restRegion.getGaussianPoint(randomization);

        log.trace("Idle: move to rest position ({})", restRegion.name());

        // ScreenRegion uses canvas-relative coordinates
        return moveToCanvas(restPos[0], restPos[1]);
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Sleep asynchronously.
     */
    private CompletableFuture<Void> sleep(long millis) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.schedule(() -> future.complete(null), millis, TimeUnit.MILLISECONDS);
        return future;
    }

    /**
     * Get time since last movement in milliseconds.
     */
    public long getTimeSinceLastMovement() {
        return System.currentTimeMillis() - lastMovementTime;
    }

    /**
     * Check if mouse is currently moving.
     */
    public boolean isMoving() {
        return isMoving;
    }

    /**
     * Reset session click count.
     */
    public void resetClickCount() {
        sessionClickCount = 0;
    }

    /**
     * Shutdown the executor.
     */
    public void shutdown() {
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

