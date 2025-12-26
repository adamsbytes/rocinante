package com.rocinante.input;

import com.rocinante.util.PerlinNoise;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

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
    private final InputProfile inputProfile;
    private final ScheduledExecutorService executor;

    @Getter
    private volatile Point currentPosition;

    @Getter
    private volatile int sessionClickCount = 0;

    private volatile long lastMovementTime = System.currentTimeMillis();
    private volatile boolean isMoving = false;
    private long movementSeed = System.nanoTime();
    
    // Cached canvas offset (updated periodically)
    private volatile Point canvasOffset = new Point(0, 0);
    private volatile long lastCanvasOffsetUpdate = 0;
    private static final long CANVAS_OFFSET_UPDATE_INTERVAL_MS = 1000;

    @Inject
    public RobotMouseController(Client client, Randomization randomization, PerlinNoise perlinNoise, InputProfile inputProfile) throws AWTException {
        this.robot = new Robot();
        this.robot.setAutoDelay(0); // We handle delays ourselves
        this.client = client;
        this.randomization = randomization;
        this.perlinNoise = perlinNoise;
        this.inputProfile = inputProfile;
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
     * Translate canvas-relative coordinates to absolute screen coordinates.
     * 
     * @param canvasX X coordinate relative to game canvas
     * @param canvasY Y coordinate relative to game canvas
     * @return Point with absolute screen coordinates
     */
    public Point canvasToScreen(int canvasX, int canvasY) {
        Point offset = getCanvasOffset();
        return new Point(canvasX + offset.x, canvasY + offset.y);
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
     * Move mouse to a target point using humanized Bezier curve movement.
     *
     * @param target the target point
     * @return CompletableFuture that completes when movement is done
     */
    public CompletableFuture<Void> moveTo(Point target) {
        return moveTo(target.x, target.y);
    }

    /**
     * Move mouse to target coordinates using humanized Bezier curve movement.
     *
     * @param targetX target X coordinate
     * @param targetY target Y coordinate
     * @return CompletableFuture that completes when movement is done
     */
    public CompletableFuture<Void> moveTo(int targetX, int targetY) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                executeMovement(targetX, targetY);
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
        return moveTo(clickPos[0], clickPos[1]);
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

        // Generate Bezier curve control points
        List<Point> controlPoints = generateControlPoints(start, target, distance);

        // Calculate movement duration
        long duration = calculateMovementDuration(distance);

        // Apply profile speed multiplier
        duration = Math.round(duration / inputProfile.getMouseSpeedMultiplier());
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

            // Move mouse
            robot.mouseMove(noisyX, noisyY);
            currentPosition = new Point(noisyX, noisyY);

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
     */
    private long calculateMovementDuration(double distance) {
        double baseDuration = Math.sqrt(distance) * DURATION_DISTANCE_FACTOR;
        double randomAddition = randomization.gaussianRandom(DURATION_RANDOM_MEAN, DURATION_RANDOM_STDDEV);
        return Math.round(baseDuration + randomAddition);
    }

    /**
     * Check if this movement should include overshoot.
     */
    private boolean shouldOvershoot() {
        double probability = inputProfile.getOvershootProbability();
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

        robot.mouseMove(overshootX, overshootY);
        currentPosition = new Point(overshootX, overshootY);

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
        return randomization.chance(inputProfile.getMicroCorrectionProbability());
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

        robot.mouseMove(correctedX, correctedY);
        currentPosition = new Point(correctedX, correctedY);

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
     * Move to target and click.
     *
     * @param target the target point
     * @return CompletableFuture that completes when click is done
     */
    public CompletableFuture<Void> click(Point target) {
        return moveTo(target).thenCompose(v -> click());
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

        return moveTo(clickPos[0], clickPos[1]).thenCompose(v -> click());
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
        return moveTo(clickPos[0], clickPos[1])
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
        return moveTo(clickPos[0], clickPos[1])
                .thenCompose(v -> executeClick(InputEvent.BUTTON1_DOWN_MASK, true));
    }

    /**
     * Execute a mouse click with humanized timing.
     */
    private CompletableFuture<Void> executeClick(int buttonMask, boolean doubleClick) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
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

                future.complete(null);
            } catch (Exception e) {
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
        baseDuration *= inputProfile.getClickVarianceModifier();

        return Math.round(baseDuration);
    }

    /**
     * Generate click position within hitbox using 2D Gaussian distribution.
     * As per spec: center at 45-55% of dimensions, σ = 15% of dimension.
     */
    private int[] generateClickPosition(Rectangle hitbox) {
        int[] basePosition = randomization.generateClickPosition(
                hitbox.x, hitbox.y, hitbox.width, hitbox.height);

        // Apply fatigue-based variance increase
        if (sessionClickCount > CLICK_FATIGUE_THRESHOLD) {
            double fatigueMultiplier = randomization.uniformRandom(
                    MIN_FATIGUE_VARIANCE_MULTIPLIER, MAX_FATIGUE_VARIANCE_MULTIPLIER);

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
     * Check if this click should be a misclick.
     */
    private boolean shouldMisclick() {
        return randomization.chance(inputProfile.getBaseMisclickRate());
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

        // Move to misclick position and click
        return moveTo(misclickX, misclickY)
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
        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                long duration = randomization.uniformRandomLong(MIN_DRIFT_DURATION_MS, MAX_DRIFT_DURATION_MS);
                long startTime = System.currentTimeMillis();
                Point start = currentPosition;

                while (System.currentTimeMillis() - startTime < duration) {
                    double t = (double) (System.currentTimeMillis() - startTime) / duration;
                    int x = (int) (start.x + (targetX - start.x) * t);
                    int y = (int) (start.y + (targetY - start.y) * t);

                    robot.mouseMove(x, y);
                    currentPosition = new Point(x, y);

                    Thread.sleep(20); // Slow update rate for drift
                }

                robot.mouseMove(targetX, targetY);
                currentPosition = new Point(targetX, targetY);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Move mouse to a rest position (inventory, minimap, or chat).
     */
    private CompletableFuture<Void> moveToRestPosition() {
        ScreenRegion restRegion = inputProfile.selectIdlePosition();
        int[] restPos = restRegion.getGaussianPoint(randomization);

        log.trace("Idle: move to rest position ({})", restRegion.name());

        return moveTo(restPos[0], restPos[1]);
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

