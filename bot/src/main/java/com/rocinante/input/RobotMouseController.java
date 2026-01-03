package com.rocinante.input;

import com.rocinante.behavior.FatigueModel;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.input.uinput.UInputMouseDevice;
import com.rocinante.util.PerlinNoise;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static com.rocinante.input.uinput.LinuxInputConstants.*;

/**
 * Humanized mouse controller using java.awt.Robot.
 * Implements all specifications from REQUIREMENTS.md Section 3.1:
 *
 * - Bezier curve-based movement with distance-dependent control points
 * - Sigmoid velocity profile (slow-fast-slow)
 * - Perlin noise injection for natural path variation
 * - Overshoot simulation (dynamic 2-40% based on target size, distance, speed)
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

    // Fitts' Law parameters are now per-profile (see PlayerProfile.getFittsA/B)
    // Movement time = a + b * log2(1 + Distance / Width)
    // Constants removed - values come from playerProfile.getFittsA() and getFittsB()
    private static final double DEFAULT_TARGET_WIDTH = 20.0; // Fallback for blind movements
    private static final long MIN_DURATION_MS = 80;
    private static final long MAX_DURATION_MS = 2500; // Increased for very difficult targets

    // Noise injection constants
    private static final double MIN_NOISE_AMPLITUDE = 1.0;
    private static final double MAX_NOISE_AMPLITUDE = 3.0;
    private static final long NOISE_SAMPLE_INTERVAL_MS = 7; // 5-10ms, use middle

    // Overshoot constants (base 8-15% probability, scaled by target/distance/speed, clamped 2-40%)
    private static final double MIN_OVERSHOOT_PROBABILITY = 0.02;  // Floor after scaling
    private static final double MAX_OVERSHOOT_PROBABILITY = 0.40;  // Cap after scaling
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

    // Hesitation constants (brief pauses during movement)
    private static final long MIN_HESITATION_MS = 5;
    private static final long MAX_HESITATION_MS = 15;
    private static final double HESITATION_RANGE_START = 0.2;  // Only hesitate between 20-80% of path
    private static final double HESITATION_RANGE_END = 0.8;
    private static final double HESITATION_TOLERANCE = 0.05;   // Check within 5% of hesitation point

    // Sub-movement constants (direction corrections on long paths)
    private static final int MIN_SUBMOVEMENT_DISTANCE = 300;    // Only for >300px movements
    private static final double SUBMOVEMENT_DEVIATION = 0.15;   // 15% of distance as max deviation
    private static final long MIN_WAYPOINT_PAUSE_MS = 10;
    private static final long MAX_WAYPOINT_PAUSE_MS = 30;

    // Path segmentation constants (approach phases for very long movements)
    private static final int PHASE_THRESHOLD = 400;            // Use phases for >400px
    private static final double BALLISTIC_PHASE_RATIO = 0.70;  // 70% fast
    private static final double APPROACH_PHASE_RATIO = 0.25;   // 25% normal (of remaining)
    private static final double BALLISTIC_SPEED_BOOST = 1.15;  // 15% faster
    private static final double FINETUNE_SPEED_REDUCTION = 0.70; // 30% slower

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

    private final UInputMouseDevice mouseDevice;
    private final Client client;
    private final Randomization randomization;
    private final PerlinNoise perlinNoise;
    private final PlayerProfile playerProfile;
    private final ScheduledExecutorService executor;
    private final ClientThread clientThread;

    /**
     * FatigueModel for fatigue-based click variance and action recording.
     */
    private final FatigueModel fatigueModel;

    /**
     * Biologically-constrained motor noise generator.
     * Provides realistic motor noise with:
     * - Low-pass filtering at muscle bandwidth (~10Hz)
     * - Fatigue-scaled amplitude
     * - Movement phase adaptation (ballistic/correction/precision)
     * - Hand-dominance correlated X/Y axes
     */
    private final BiologicalMotorNoise motorNoise;

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

    /**
     * PredictiveHoverManager for coordinating idle behavior suppression.
     * When set and actively hovering, idle behaviors that would move the mouse are suppressed.
     */
    @Setter
    @Nullable
    private com.rocinante.behavior.PredictiveHoverManager predictiveHoverManager;

    @Getter
    private volatile Point currentPosition;

    @Getter
    private volatile int sessionClickCount = 0;
    private volatile double clickFatigueVarianceBoost = 1.0;

    private volatile long lastMovementTime = System.currentTimeMillis();
    private volatile boolean isMoving = false;
    private long movementSeed = System.nanoTime();
    
    // Cached canvas/viewport info (populated on client thread)
    private volatile Point canvasOffset = new Point(0, 0);
    private volatile Dimension cachedCanvasSize = new Dimension(765, 503);
    private volatile Rectangle cachedViewportBounds = new Rectangle(0, 0, 765, 503);
    private volatile long lastViewportRefresh = 0;
    private static final long VIEWPORT_CACHE_TTL_MS = 1_000;

    @Inject
    public RobotMouseController(Client client, Randomization randomization, PerlinNoise perlinNoise,
                                PlayerProfile playerProfile, FatigueModel fatigueModel,
                                ClientThread clientThread) {
        this(client, randomization, perlinNoise, playerProfile, fatigueModel, clientThread,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "RobotMouseController");
                    t.setDaemon(true);
                    return t;
                }));
    }

    /**
     * Testing constructor (no ClientThread injection).
     */
    public RobotMouseController(Client client, Randomization randomization, PerlinNoise perlinNoise,
                                PlayerProfile playerProfile, FatigueModel fatigueModel) {
        this(client, randomization, perlinNoise, playerProfile, fatigueModel, null,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "RobotMouseController");
                    t.setDaemon(true);
                    return t;
                }));
    }

    private RobotMouseController(Client client, Randomization randomization, PerlinNoise perlinNoise,
                                 PlayerProfile playerProfile, FatigueModel fatigueModel,
                                 ClientThread clientThread, ScheduledExecutorService executor) {
        // Create UInput virtual mouse device using profile preset and polling rate
        // This injects input at the kernel level, bypassing java.awt.Robot's XTest flag
        // The machineId is used for deterministic physical path generation across restarts
        this.mouseDevice = new UInputMouseDevice(
            playerProfile.getMouseDevicePreset(),
            playerProfile.getMousePollingRate(),
            playerProfile.getMachineId()
        );
        this.client = client;
        this.randomization = randomization;
        this.perlinNoise = perlinNoise;
        this.playerProfile = playerProfile;
        this.fatigueModel = fatigueModel;
        this.clientThread = clientThread;
        this.executor = executor;

        // Initialize biological motor noise generator
        // Uses profile's physTremorFreq, physTremorAmp, dominantHandBias for unique motor signature
        this.motorNoise = new BiologicalMotorNoise(playerProfile, fatigueModel);

        // Sync with actual cursor position
        mouseDevice.syncPosition();
        this.currentPosition = mouseDevice.getPosition();

        log.info("RobotMouseController initialized with UInput device: {}", 
                playerProfile.getMouseDevicePreset().getName());
    }
    
    // ========================================================================
    // Canvas Offset Translation
    // ========================================================================
    
    /**
     * Get the current canvas offset (screen position of the game canvas).
     * This is used to translate canvas-relative widget coordinates to absolute screen coordinates.
     * The offset is cached and updated on the client thread to avoid off-thread client access.
     */
    private Point getCanvasOffset() {
        refreshDisplayInfoIfStale();
        return canvasOffset;
    }

    /**
     * Refresh viewport/canvas info if the cache is stale.
     */
    private void refreshDisplayInfoIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastViewportRefresh < VIEWPORT_CACHE_TTL_MS) {
            return;
        }

        if (clientThread != null) {
            lastViewportRefresh = now; // prevent flood of invokes until refresh completes
            clientThread.invoke(this::updateDisplayInfo);
        } else {
            updateDisplayInfo();
        }
    }

    /**
     * Update cached canvas offset, size, and viewport bounds.
     */
    private void updateDisplayInfo() {
        try {
            Canvas canvas = client.getCanvas();
            if (canvas != null && canvas.isShowing()) {
                Point screenPos = canvas.getLocationOnScreen();
                Dimension size = canvas.getSize();
                if (Math.abs(screenPos.x - canvasOffset.x) > 10 || Math.abs(screenPos.y - canvasOffset.y) > 10) {
                    log.debug("Canvas position changed: ({}, {}) -> ({}, {})", 
                            canvasOffset.x, canvasOffset.y, screenPos.x, screenPos.y);
                }
                canvasOffset = screenPos;
                cachedCanvasSize = size;
            }

            int areaX = client.getViewportXOffset();
            int areaY = client.getViewportYOffset();
            int areaWidth = client.getViewportWidth();
            int areaHeight = client.getViewportHeight();

            if (areaWidth <= 0 || areaHeight <= 0) {
                areaX = 0;
                areaY = 0;
                areaWidth = cachedCanvasSize.width;
                areaHeight = cachedCanvasSize.height;
            }

            cachedViewportBounds = new Rectangle(areaX, areaY, areaWidth, areaHeight);
            lastViewportRefresh = System.currentTimeMillis();
        } catch (Exception e) {
            log.debug("Could not refresh display info: {}", e.getMessage());
        }
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
        lastViewportRefresh = 0;
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
     * @param screenX target X in screen coordinates
     * @param screenY target Y in screen coordinates
     * @param targetWidth estimated width of the target (for Fitts' Law), or -1 for default
     * @return CompletableFuture that completes when movement is done
     */
    public CompletableFuture<Void> moveToScreen(int screenX, int screenY, double targetWidth) {
        // THE HARD GUARD: Nothing leaves the game window. Period.
        if (!isPointInViewport(screenX, screenY)) {
            log.error("HARD GUARD: Screen point ({}, {}) is outside canvas", screenX, screenY);
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Screen point outside canvas"));
        }
        
        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                executeMovement(screenX, screenY, targetWidth <= 0 ? DEFAULT_TARGET_WIDTH : targetWidth);
                future.complete(null);
            } catch (Exception e) {
                log.error("Mouse movement failed", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Move mouse to screen coordinates using humanized Bezier curve movement.
     * Uses default target width.
     */
    public CompletableFuture<Void> moveToScreen(int screenX, int screenY) {
        return moveToScreen(screenX, screenY, DEFAULT_TARGET_WIDTH);
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
        
        // Pass the smaller dimension as the target width for Fitts' Law
        // This ensures moving to small items is slower/more precise than big ones
        double targetSize = Math.min(screenHitbox.width, screenHitbox.height);
        return moveToScreen(clickPos[0], clickPos[1], targetSize);
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
        Rectangle viewport = getViewportBounds();
        return viewport.contains(x, y);
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
        Rectangle canvasBounds = getCanvasBounds();
        return canvasBounds.contains(screenX, screenY);
    }
    
    /**
     * Clamp a point to be within the full viewport.
     * 
     * @param x X coordinate
     * @param y Y coordinate
     * @return clamped point
     */
    public Point clampToViewport(int screenX, int screenY) {
        Rectangle bounds = getCanvasBounds();
        return new Point(
            Math.max(bounds.x, Math.min(screenX, bounds.x + bounds.width - 1)),
            Math.max(bounds.y, Math.min(screenY, bounds.y + bounds.height - 1))
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
        Rectangle viewport = getViewportBounds();
        return new Point(
            Math.max(viewport.x, Math.min(x, viewport.x + viewport.width - 1)),
            Math.max(viewport.y, Math.min(y, viewport.y + viewport.height - 1))
        );
    }


    /**
     * Execute the actual mouse movement with all humanization features.
     */
    private void executeMovement(int targetX, int targetY, double targetWidth) throws InterruptedException {
        isMoving = true;
        movementSeed = System.nanoTime();

        // Sync with actual X11 cursor position before starting movement
        // This handles any drift from external cursor movements
        mouseDevice.syncPosition();
        Point start = mouseDevice.getPosition();
        currentPosition = start;
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
                log.debug("Camera coupling failed: {}", e.getMessage());
            }
        }

        // Track movement duration for overshoot probability calculation
        long movementStartTime = System.currentTimeMillis();

        // Check if this movement should use sub-movements (direction corrections)
        if (shouldUseSubMovements(distance)) {
            executeMovementWithSubMovements(start, target, distance, targetWidth);
        } else {
            // Regular direct movement
            executeDirectMovement(targetX, targetY, targetWidth);
        }

        long movementDuration = System.currentTimeMillis() - movementStartTime;

        // Handle overshoot simulation (pass start for direction calculation)
        // Probability is dynamic based on target size, distance, and speed
        if (shouldOvershoot(distance, targetWidth, movementDuration)) {
            executeOvershoot(start, target);
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
     * 
     * Curvature is modeled to match real human behavior:
     * 1. Dominant hand bias: right-handed curves clockwise, left-handed counter-clockwise
     *    (due to wrist pivot mechanics - same as overshoot direction)
     * 2. Non-uniform offset: more curvature mid-path, less at start/end (sine envelope)
     *    This matches how humans accelerate through the middle of a movement
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
        // In screen coords (+Y down): positive perpendicular = clockwise curve
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double perpX = -dy / distance;
        double perpY = dx / distance;
        
        // Dominant hand bias for curve direction
        // dominantHandBias: 0.0 = left-handed, 0.5 = ambidextrous, 1.0 = right-handed
        // Right-handed: wrist pivots from left, natural clockwise bias (positive perp)
        // Left-handed: wrist pivots from right, natural counter-clockwise bias (negative perp)
        double handBias = playerProfile.getDominantHandBias();
        // Convert to direction bias: -1.0 (always left curve) to +1.0 (always right curve)
        // At 0.5 (ambidextrous): 0.0 bias, 50/50 random
        // At 1.0 (right-handed): +0.7 bias toward clockwise
        // At 0.0 (left-handed): -0.7 bias toward counter-clockwise
        double directionBias = (handBias - 0.5) * 1.4;
        
        // Decide curve direction for this movement (consistent within one movement)
        // Use Gaussian centered on bias so it's probabilistic but biased
        double directionRoll = randomization.gaussianRandom(directionBias, 0.5);
        int curveSign = directionRoll > 0 ? 1 : -1;

        // Add intermediate control points
        for (int i = 1; i < numControlPoints - 1; i++) {
            double t = (double) i / (numControlPoints - 1);

            // Base point along direct path
            int baseX = (int) (start.x + dx * t);
            int baseY = (int) (start.y + dy * t);

            // Non-uniform offset: sine envelope for more curvature mid-path
            // sin(π*t) = 0 at t=0, 1 at t=0.5, 0 at t=1
            // This creates natural "bulge" in the middle of the curve
            double sineEnvelope = Math.sin(Math.PI * t);
            
            // Base offset (5-15% of distance), scaled by sine envelope
            double offsetPercent = randomization.uniformRandom(MIN_CONTROL_OFFSET_PERCENT, MAX_CONTROL_OFFSET_PERCENT);
            double offsetDistance = distance * offsetPercent * sineEnvelope;
            
            // Apply hand-biased direction with small random variation per point
            // Main direction is consistent (curveSign), but add ±20% noise
            double pointNoise = randomization.gaussianRandom(0, 0.2);
            double finalSign = curveSign * (1.0 + pointNoise);
            offsetDistance *= finalSign;

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
     * Asymmetric velocity progress calculation.
     * 
     * Replaces standard sigmoid with profile-based skew to create human-like
     * velocity profiles that vary per player:
     * 
     * - velocityFlow < 0.5: Fast start, gradual slowdown (snappy players)
     * - velocityFlow > 0.5: Slow start, fast finish (lazy/deliberate players)
     * - velocityFlow = 0.5: Symmetric (like original sigmoid)
     * 
     * This prevents the detectable "perfect sigmoid" pattern that bots exhibit.
     * Real humans have asymmetric acceleration based on motor habits.
     * 
     * @param t linear time progress (0.0 to 1.0)
     * @return velocity-warped progress (0.0 to 1.0)
     */
    private double calculateVelocityProgress(double t) {
        double flow = playerProfile.getVelocityFlow(); // 0.2 (snappy) to 0.8 (lazy)
        
        // Warp t using power function based on flow preference
        // flow < 0.5 → exponent < 1 → warps t upward (fast start)
        // flow > 0.5 → exponent > 1 → warps t downward (slow start)
        // Scaling factor of 2.5 makes the effect noticeable but not extreme
        double exponent = flow * 2.5;
        double warpedT = Math.pow(t, exponent);
        
        // Feed warped T through sigmoid for smooth acceleration at endpoints
        // This preserves the slow-fast-slow envelope while adding asymmetry
        double x = (warpedT - 0.5) * 10;
        double sigmoid = 1.0 / (1.0 + Math.exp(-x));
        
        // Normalize to [0, 1] range
        double minSigmoid = 1.0 / (1.0 + Math.exp(5));
        double maxSigmoid = 1.0 / (1.0 + Math.exp(-5));
        
        return (sigmoid - minSigmoid) / (maxSigmoid - minSigmoid);
    }

    /**
     * Calculate movement duration using Fitts' Law.
     * 
     * Fitts' Law: MT = a + b * ID
     * Where ID (Index of Difficulty) = log2(1 + Distance / Width)
     * 
     * This creates human-realistic duration scaling where:
     * - Large targets are faster to hit than small targets at same distance
     * - Duration scales logarithmically with distance/width ratio
     * 
     * @param distance movement distance in pixels
     * @param targetWidth target size (smaller dimension of hitbox)
     * @return duration in milliseconds
     */
    private long calculateMovementDuration(double distance, double targetWidth) {
        // Safety clamp width to avoid division issues
        double width = Math.max(1.0, targetWidth);
        
        // Index of Difficulty (Shannon formulation)
        double id = Math.log(1 + distance / width) / Math.log(2);
        
        // Fitts' Law: a + b * ID (using per-profile parameters)
        // Each player has unique motor characteristics that determine their movement timing
        double fittsA = playerProfile.getFittsA();
        double fittsB = playerProfile.getFittsB();
        double duration = fittsA + (fittsB * id);
        
        // Add random variation (human inconsistency)
        double randomAddition = randomization.gaussianRandom(0, 20.0, -50.0, 50.0);
        duration += randomAddition;
        
        return Math.round(Randomization.clamp(duration, MIN_DURATION_MS, MAX_DURATION_MS));
    }
    
    /**
     * Calculate movement duration with default target width.
     * Use when target size is unknown (e.g., moving to arbitrary point).
     */
    private long calculateMovementDuration(double distance) {
        return calculateMovementDuration(distance, DEFAULT_TARGET_WIDTH);
    }

    /**
     * Calculate dynamic overshoot probability based on movement characteristics.
     * 
     * Real human overshoot behavior depends on:
     * 1. Target size: smaller targets = harder to stop precisely = more overshoots
     * 2. Movement distance: short movements rarely overshoot (low inertia), long movements have more
     * 3. Movement speed: faster movements = more momentum = more overshoot
     * 
     * Base probability comes from player profile (8-15%), then scaled by these factors.
     * 
     * @param distance the movement distance in pixels
     * @param targetWidth the target width in pixels (smaller = harder to hit)
     * @param movementDuration actual duration of the movement in ms
     * @return true if this movement should overshoot
     */
    private boolean shouldOvershoot(double distance, double targetWidth, long movementDuration) {
        double baseProbability = playerProfile.getOvershootProbability();
        
        // Factor 1: Target size effect
        // Small targets (< 20px) = up to 1.5x multiplier (harder to stop precisely)
        // Medium targets (20-80px) = linear interpolation
        // Large targets (> 80px) = 0.5x multiplier (easy to stop)
        // Reference: DEFAULT_TARGET_WIDTH = 50
        double sizeFactor;
        if (targetWidth < 20) {
            sizeFactor = 1.5;
        } else if (targetWidth < 50) {
            // Linear: 1.5 at 20px -> 1.0 at 50px
            sizeFactor = 1.5 - 0.5 * (targetWidth - 20) / 30;
        } else if (targetWidth < 80) {
            // Linear: 1.0 at 50px -> 0.5 at 80px
            sizeFactor = 1.0 - 0.5 * (targetWidth - 50) / 30;
        } else {
            sizeFactor = 0.5;
        }
        
        // Factor 2: Distance effect
        // Short movements (< 100px) = 0.2x multiplier (rarely overshoot - precise, slow movements)
        // Medium movements (100-400px) = 0.2x to 1.0x (increasing momentum)
        // Long movements (> 400px) = 1.0x to 1.3x (high inertia)
        double distanceFactor;
        if (distance < 100) {
            distanceFactor = 0.2;
        } else if (distance < 400) {
            // Linear: 0.2 at 100px -> 1.0 at 400px
            distanceFactor = 0.2 + 0.8 * (distance - 100) / 300;
        } else if (distance < 800) {
            // Linear: 1.0 at 400px -> 1.3 at 800px
            distanceFactor = 1.0 + 0.3 * (distance - 400) / 400;
        } else {
            distanceFactor = 1.3;
        }
        
        // Factor 3: Speed effect (actual pixels/ms)
        // Slow (< 0.5 px/ms) = 0.3x (careful, controlled movement)
        // Normal (0.5-1.0 px/ms) = 0.3x to 1.0x
        // Fast (1.0-2.0 px/ms) = 1.0x to 1.5x (momentum makes stopping harder)
        // Very fast (> 2.0 px/ms) = 1.5x cap
        double speed = movementDuration > 0 ? distance / movementDuration : 1.0;
        double speedFactor;
        if (speed < 0.5) {
            speedFactor = 0.3;
        } else if (speed < 1.0) {
            // Linear: 0.3 at 0.5 -> 1.0 at 1.0
            speedFactor = 0.3 + 0.7 * (speed - 0.5) / 0.5;
        } else if (speed < 2.0) {
            // Linear: 1.0 at 1.0 -> 1.5 at 2.0
            speedFactor = 1.0 + 0.5 * (speed - 1.0);
        } else {
            speedFactor = 1.5;
        }
        
        // Combine factors multiplicatively
        double adjustedProbability = baseProbability * sizeFactor * distanceFactor * speedFactor;
        
        // Clamp to reasonable range (floor at 2% to prevent never-overshoot, cap at 40%)
        adjustedProbability = Math.max(0.02, Math.min(0.40, adjustedProbability));
        
        log.debug("Overshoot probability: base={:.3f} × size={:.2f} × dist={:.2f} × speed={:.2f} = {:.3f} " +
                "(targetWidth={:.0f}px, distance={:.0f}px, speed={:.2f}px/ms)",
                baseProbability, sizeFactor, distanceFactor, speedFactor, adjustedProbability,
                targetWidth, distance, speed);
        
        return randomization.chance(adjustedProbability);
    }

    /**
     * Execute overshoot: move past target, then correct back.
     * 
     * Overshoot direction is biased by hand dominance because real humans'
     * wrist mechanics cause predictable directional bias:
     * - Right-handed users: wrist pivots from left, causing clockwise overshoot bias
     * - Left-handed users: wrist pivots from right, causing counter-clockwise bias
     * 
     * Uses Gaussian distribution for natural spread around the biased mean,
     * rather than uniform distribution which looks robotic.
     * 
     * @param start the starting point of the movement
     * @param target the target point (where we are now)
     */
    private void executeOvershoot(Point start, Point target) throws InterruptedException {
        int overshootPixels = randomization.uniformRandomInt(MIN_OVERSHOOT_PIXELS, MAX_OVERSHOOT_PIXELS);

        // Calculate movement direction (from start to target)
        double dx = target.x - start.x;
        double dy = target.y - start.y;
        double movementAngle = Math.atan2(dy, dx);
        
        // Hand dominance affects overshoot direction due to wrist pivot mechanics
        // dominantHandBias: 0.0 = left-handed, 0.5 = ambidextrous, 1.0 = right-handed
        // In screen coords (+Y down): clockwise = positive angle, counter-clockwise = negative
        double handBias = playerProfile.getDominantHandBias();
        
        // Convert hand bias to angular bias:
        // - Right-handed (0.7): +0.4 normalized -> +14° clockwise bias
        // - Left-handed (0.3): -0.4 normalized -> -14° counter-clockwise bias
        // - Ambidextrous (0.5): 0 normalized -> no bias
        double maxBiasAngle = Math.toRadians(18); // Max bias magnitude
        double normalizedBias = (handBias - 0.5) * 2.0; // -1.0 to +1.0
        double biasAngle = normalizedBias * maxBiasAngle;
        
        // Use Gaussian distribution centered on bias angle
        // stdDev of 12° gives natural spread: 68% within ±12° of bias, 95% within ±24°
        double spreadStdDev = Math.toRadians(12);
        double angleOffset = randomization.gaussianRandom(biasAngle, spreadStdDev);
        
        // Clamp to prevent unrealistic overshoot angles (max ±45° from movement direction)
        double maxOffset = Math.toRadians(45);
        angleOffset = Math.max(-maxOffset, Math.min(maxOffset, angleOffset));
        
        double overshootAngle = movementAngle + angleOffset;
        
        int overshootX = target.x + (int) (Math.cos(overshootAngle) * overshootPixels);
        int overshootY = target.y + (int) (Math.sin(overshootAngle) * overshootPixels);

        // Clamp overshoot to viewport
        Point clampedOvershoot = clampToViewport(overshootX, overshootY);
        mouseDevice.moveTo(clampedOvershoot.x, clampedOvershoot.y);
        currentPosition = clampedOvershoot;

        // Delay before correction
        long delay = randomization.uniformRandomLong(MIN_OVERSHOOT_DELAY_MS, MAX_OVERSHOOT_DELAY_MS);
        Thread.sleep(delay);

        // Correct back to target with a quick movement
        mouseDevice.moveTo(target.x, target.y);
        currentPosition = target;

        log.debug("Overshoot: {} pixels at {}° (hand bias {}°), corrected after {}ms", 
                overshootPixels, 
                (int) Math.toDegrees(angleOffset),
                (int) Math.toDegrees(biasAngle),
                delay);
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
        mouseDevice.moveTo(clampedCorrection.x, clampedCorrection.y);
        currentPosition = clampedCorrection;

        log.debug("Micro-correction: {} pixels after {}ms", correctionPixels, delay);
    }

    // ========================================================================
    // Hesitation Points (Human-like Pauses During Movement)
    // ========================================================================

    /**
     * Plan hesitation points for a movement.
     * Hesitations are brief pauses (5-15ms) that occur during path traversal,
     * simulating human perception/decision micro-delays.
     *
     * @param distance the movement distance
     * @return list of progress values (0.0-1.0) where hesitations should occur
     */
    private List<Double> planHesitations(double distance) {
        List<Double> points = new java.util.ArrayList<>();
        
        // Only hesitate on movements with this player's probability
        if (!randomization.chance(playerProfile.getHesitationProbability())) {
            return points;
        }
        
        // Longer movements are more likely to have multiple hesitations
        int count = 1;
        if (distance > 400) {
            count = randomization.uniformRandomInt(1, 2);
        }
        
        for (int i = 0; i < count; i++) {
            // Add hesitation points between 20% and 80% of path
            double hesitationPoint = randomization.uniformRandom(
                    HESITATION_RANGE_START, HESITATION_RANGE_END);
            points.add(hesitationPoint);
        }
        
        // Sort so we process in order
        points.sort(Double::compareTo);
        
        log.trace("Planned {} hesitation point(s) for {}px movement: {}", 
                points.size(), distance, points);
        return points;
    }

    /**
     * Check if the current progress point should trigger a hesitation.
     *
     * @param currentProgress current progress (0.0-1.0)
     * @param hesitationPoints list of planned hesitation points
     * @return the hesitation point if triggered, null otherwise
     */
    private Double checkForHesitation(double currentProgress, List<Double> hesitationPoints) {
        for (Double point : hesitationPoints) {
            // Check if we're within tolerance of the hesitation point
            if (Math.abs(currentProgress - point) <= HESITATION_TOLERANCE) {
                return point;
            }
        }
        return null;
    }

    /**
     * Execute a hesitation pause.
     *
     * @return the duration of the hesitation in milliseconds
     */
    private long executeHesitation() throws InterruptedException {
        long hesitationMs = randomization.uniformRandomLong(MIN_HESITATION_MS, MAX_HESITATION_MS);
        Thread.sleep(hesitationMs);
        return hesitationMs;
    }

    // ========================================================================
    // Sub-Movements (Direction Corrections for Long Paths)
    // ========================================================================

    /**
     * Check if this movement should use sub-movements (intermediate waypoints).
     * Long movements (>300px) have a chance to include direction corrections.
     *
     * @param distance the movement distance in pixels
     * @return true if sub-movements should be used
     */
    private boolean shouldUseSubMovements(double distance) {
        if (distance < MIN_SUBMOVEMENT_DISTANCE) {
            return false;
        }
        return randomization.chance(playerProfile.getSubmovementProbability());
    }

    /**
     * Calculate an intermediate waypoint for sub-movement.
     * The waypoint deviates perpendicular to the direct path, simulating
     * a human who overshoots and corrects.
     *
     * @param start  the starting point
     * @param target the target point
     * @param distance the total movement distance
     * @return the waypoint
     */
    private Point calculateWaypoint(Point start, Point target, double distance) {
        // Waypoint at 40-60% of path
        double t = randomization.uniformRandom(0.4, 0.6);
        
        // Calculate max deviation based on distance
        double maxDeviation = distance * SUBMOVEMENT_DEVIATION;
        double deviation = randomization.uniformRandom(-maxDeviation, maxDeviation);
        
        // Calculate perpendicular offset
        double dx = target.x - start.x;
        double dy = target.y - start.y;
        double perpX = -dy / distance;
        double perpY = dx / distance;
        
        int waypointX = (int) (start.x + dx * t + perpX * deviation);
        int waypointY = (int) (start.y + dy * t + perpY * deviation);
        
        // Clamp to viewport
        return clampToViewport(waypointX, waypointY);
    }

    /**
     * Execute a movement with sub-movements (intermediate waypoints).
     * For long movements, this creates more human-like paths with direction corrections.
     *
     * @param start  the starting point
     * @param target the target point
     * @param distance the total movement distance
     * @param targetWidth target size for Fitts' Law
     */
    private void executeMovementWithSubMovements(Point start, Point target, double distance, double targetWidth) 
            throws InterruptedException {
        // Calculate waypoint
        Point waypoint = calculateWaypoint(start, target, distance);
        
        log.trace("Sub-movement via waypoint ({}, {}) for {}px movement", 
                waypoint.x, waypoint.y, distance);
        
        // Move to waypoint (direct movement, no sub-movements within)
        // Waypoints are "intermediate targets", treating them as larger targets (e.g. 50px)
        // makes the movement to them faster/rougher, which is realistic for mid-air correction
        executeDirectMovement(waypoint.x, waypoint.y, 50.0);
        
        // Brief pause at waypoint (simulates direction reassessment)
        // Use log-normal distribution - real human timing is NOT uniform
        Thread.sleep(randomization.humanizedDelayMs(20, MIN_WAYPOINT_PAUSE_MS, MAX_WAYPOINT_PAUSE_MS));
        
        // Move from waypoint to target (using actual target width for precision)
        executeDirectMovement(target.x, target.y, targetWidth);
    }

    /**
     * Execute a direct movement without sub-movements.
     * This is the core movement logic, called by both regular movements
     * and as segments of sub-movement paths.
     */
    private void executeDirectMovement(int targetX, int targetY, double targetWidth) throws InterruptedException {
        Point start = currentPosition;
        Point target = new Point(targetX, targetY);

        double distance = start.distance(target);
        if (distance < 1) {
            return; // Already at target
        }

        // Check if this player uses path segmentation for long movements
        if (distance >= PHASE_THRESHOLD && playerProfile.usesPathSegmentation()) {
            executeSegmentedMovement(start, target, distance, targetWidth);
            return;
        }

        // Standard direct movement
        executeMovementSegment(start, target, distance, 1.0, targetWidth);

        // Final position (ensure we hit target)
        mouseDevice.moveTo(targetX, targetY);
        currentPosition = target;
    }

    /**
     * Execute a segmented movement with approach phases.
     * Long movements are broken into: ballistic -> approach -> fine-tune phases.
     * This mimics how humans approach targets at different speeds.
     */
    private void executeSegmentedMovement(Point start, Point target, double distance, double targetWidth) 
            throws InterruptedException {
        log.trace("Segmented movement: {}px in 3 phases", distance);
        
        // Phase 1: Ballistic (70% of distance, fast)
        // Target width is large (virtual target in space)
        Point phase1End = interpolatePoint(start, target, BALLISTIC_PHASE_RATIO);
        double phase1Dist = start.distance(phase1End);
        executeMovementSegment(start, phase1End, phase1Dist, BALLISTIC_SPEED_BOOST, 100.0);
        
        // Phase 2: Approach (25% of remaining, normal speed)
        // Target width gets closer to real size
        Point phase2End = interpolatePoint(phase1End, target, APPROACH_PHASE_RATIO);
        double phase2Dist = phase1End.distance(phase2End);
        executeMovementSegment(phase1End, phase2End, phase2Dist, 1.0, targetWidth * 2.0);
        
        // Phase 3: Fine-tuning (final 5%, slow and precise)
        // Use actual target width for Fitts' Law precision
        double phase3Dist = phase2End.distance(target);
        executeMovementSegment(phase2End, target, phase3Dist, FINETUNE_SPEED_REDUCTION, targetWidth);
        
        // Final position
        mouseDevice.moveTo(target.x, target.y);
        currentPosition = target;
    }

    /**
     * Interpolate a point between start and target at the given ratio.
     */
    private Point interpolatePoint(Point start, Point target, double ratio) {
        int x = (int) (start.x + (target.x - start.x) * ratio);
        int y = (int) (start.y + (target.y - start.y) * ratio);
        return new Point(x, y);
    }

    /**
     * Execute a single movement segment with specified speed multiplier.
     * This is the innermost movement loop used by all movement types.
     * 
     * Implements "Physiological Physics Engine" features:
     * - Asymmetric velocity profile (per-player acceleration skew)
     * - Physiological tremor (8-12Hz alpha rhythm injection)
     * - Motor unit quantization (discrete muscle recruitment steps = "jerk")
     * - Perlin noise path variation
     * - Wall-clock-normalized timing (decouples from CPU load)
     *
     * TIMING MODEL:
     * Uses wall-clock-normalized movement to ensure duration matches Fitts' Law
     * predictions regardless of CPU load. This prevents timing dilation that
     * would correlate with server tick burden (a machine signature).
     *
     * @param start starting point
     * @param target target point
     * @param distance segment distance
     * @param speedMultiplier additional speed modifier (>1.0 = faster, <1.0 = slower)
     * @param targetWidth target width for Fitts' Law calculation
     */
    private void executeMovementSegment(Point start, Point target, double distance, 
            double speedMultiplier, double targetWidth) throws InterruptedException {
        if (distance < 1) {
            return;
        }

        // Generate Bezier curve control points
        List<Point> controlPoints = generateControlPoints(start, target, distance);

        // Calculate movement duration using Fitts' Law with provided target width
        long intendedDurationMs = calculateMovementDuration(distance, targetWidth);

        // Apply profile speed multiplier and segment speed modifier
        double combinedSpeed = playerProfile.getMouseSpeedMultiplier() * speedMultiplier;
        intendedDurationMs = Math.round(intendedDurationMs / combinedSpeed);
        intendedDurationMs = Randomization.clamp(intendedDurationMs, MIN_DURATION_MS, MAX_DURATION_MS);

        // Calculate noise amplitude for this movement
        double noiseAmplitude = randomization.uniformRandom(MIN_NOISE_AMPLITUDE, MAX_NOISE_AMPLITUDE);
        
        // Get physiological parameters from profile
        double tremorFreq = playerProfile.getPhysTremorFreq();   // 8-12 Hz
        double tremorAmp = playerProfile.getPhysTremorAmp();     // 0.2-1.5 px
        double motorThreshold = playerProfile.getMotorUnitThreshold(); // 0.0-1.5 px

        // Plan hesitation points for this segment
        List<Double> hesitationPoints = planHesitations(distance);
        Set<Double> triggeredHesitations = new java.util.HashSet<>();
        
        // Reset motor noise for new movement (clears feedback loop state)
        motorNoise.reset();

        // === Wall-Clock-Normalized Movement ===
        // Instead of sleep-based iteration counting, we calculate cursor position
        // based on ACTUAL elapsed wall-clock time. This ensures movement duration
        // matches Fitts' Law predictions regardless of CPU load or Thread.sleep()
        // imprecision. Under heavy load, the cursor will skip positions to maintain
        // timing, rather than slowing down (which would be a machine signature).
        
        final long startTimeNanos = System.nanoTime();
        final long durationNanos = intendedDurationMs * 1_000_000L;
        
        // Motor unit accumulator for quantization (jerk simulation)
        double pendingDx = 0;
        double pendingDy = 0;
        Point lastActualPos = start;

        while (true) {
            long nowNanos = System.nanoTime();
            long elapsedNanos = nowNanos - startTimeNanos;
            
            // Check if we've reached the intended duration
            if (elapsedNanos >= durationNanos) {
                break;
            }
            
            // Calculate normalized progress (0.0 to 1.0) from WALL CLOCK
            // This is the key difference: t is derived from actual time, not iteration count
            double t = (double) elapsedNanos / durationNanos;
            t = Math.min(1.0, t); // Clamp to prevent floating point overshoot

            // Check for hesitation at this progress point
            Double hesitationPoint = checkForHesitation(t, hesitationPoints);
            if (hesitationPoint != null && !triggeredHesitations.contains(hesitationPoint)) {
                triggeredHesitations.add(hesitationPoint);
                executeHesitation();
                // Continue after hesitation - next iteration will recalculate t
                continue;
            }

            // Apply asymmetric velocity curve (replaces sigmoid)
            double adjustedT = calculateVelocityProgress(t);

            // Calculate point on Bezier curve
            Point bezierPoint = evaluateBezier(controlPoints, adjustedT);

            // Add Perlin noise perpendicular to movement direction
            double[] noiseOffset = perlinNoise.getPathOffset(t, noiseAmplitude, movementSeed);
            
            // === Physiological Tremor Injection ===
            // Use wall-clock seconds for tremor phase (not loop iteration count)
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            double tremorScale = 1.0 - (Math.pow(t, 4) * 0.8); 
            double tremorX = Math.sin(elapsedSeconds * tremorFreq * Math.PI * 2) * tremorAmp * tremorScale;
            double tremorY = Math.cos(elapsedSeconds * tremorFreq * Math.PI * 2) * tremorAmp * tremorScale;
            
            // Combine all offsets
            double idealX = bezierPoint.x + noiseOffset[0] + tremorX;
            double idealY = bezierPoint.y + noiseOffset[1] + tremorY;
            
            // === Motor Unit Quantization (Jerk) ===
            pendingDx += (idealX - lastActualPos.x);
            pendingDy += (idealY - lastActualPos.y);
            
            double pendingMagnitude = Math.hypot(pendingDx, pendingDy);
            
            // Move only when accumulated movement exceeds motor threshold (or at end)
            if (pendingMagnitude >= motorThreshold || t >= 0.95) {
                // Set movement context for biological motor noise with feedback correction
                double speedPxPerMs = distance / (double) intendedDurationMs;
                motorNoise.setMovementContext(t, speedPxPerMs);
                
                // Get biologically-constrained noise with feedback correction loop
                // (not pure pink noise - includes visual-motor feedback modeling)
                double[] bioNoise = motorNoise.next2D();
                double ditherX = bioNoise[0] * 0.5;
                double ditherY = bioNoise[1] * 0.5;
                
                int moveX = lastActualPos.x + (int) Math.floor(pendingDx + ditherX + 0.5);
                int moveY = lastActualPos.y + (int) Math.floor(pendingDy + ditherY + 0.5);
                
                // Clamp to viewport
                Point clamped = clampToViewport(moveX, moveY);
                
                // Move mouse via UInput
                mouseDevice.moveTo(clamped.x, clamped.y);
                currentPosition = clamped;
                
                // Calculate EXACTLY what we moved (due to clamping/int rounding)
                int actualDx = clamped.x - lastActualPos.x;
                int actualDy = clamped.y - lastActualPos.y;
                
                // Update position state
                lastActualPos = clamped;
                
                // Subtract what we actually moved from pending accumulators
                pendingDx -= actualDx;
                pendingDy -= actualDy;
            }

            // === Adaptive Sleep with Timing Compensation ===
            // The sleep is just to yield CPU and maintain ~100Hz sample rate.
            // Actual cursor position is ALWAYS calculated from wall clock, so
            // if this sleep takes longer than expected, the next iteration will
            // "catch up" by calculating t from actual elapsed time.
            //
            // Use LockSupport.parkNanos for more precise sleep than Thread.sleep()
            long targetSleepNanos = NOISE_SAMPLE_INTERVAL_MS * 1_000_000L;
            long jitterNanos = randomization.uniformRandomLong(-1_000_000, 2_000_000);
            long sleepNanos = Math.max(1_000_000L, targetSleepNanos + jitterNanos);
            
            java.util.concurrent.locks.LockSupport.parkNanos(sleepNanos);
        }

        // Final position cleanup: ensure we end exactly at target
        if (!lastActualPos.equals(target)) {
            mouseDevice.moveTo(target.x, target.y);
        }
        currentPosition = target;
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
                    log.debug("Hesitating for {}ms before click", hesitationDelay);
                    Thread.sleep(hesitationDelay);
                }
                
                // First click
                performSingleClick(buttonMask);
                sessionClickCount++;
                maybeApplyClickFatigueBoost();

                // Second click for double-click
                if (doubleClick) {
                    long interval = randomization.uniformRandomLong(
                            MIN_DOUBLE_CLICK_INTERVAL_MS, MAX_DOUBLE_CLICK_INTERVAL_MS);
                    Thread.sleep(interval);
                    performSingleClick(buttonMask);
                    sessionClickCount++;
                    maybeApplyClickFatigueBoost();
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

        // Convert AWT button mask to Linux button code
        short linuxButton = UInputMouseDevice.awtButtonToLinux(buttonMask);
        
        mouseDevice.pressButton(linuxButton);
        Thread.sleep(holdDuration);
        mouseDevice.releaseButton(linuxButton);
        
        // Record action for fatigue/break tracking (only on actual clicks)
        fatigueModel.recordAction();
    }

    /**
     * Calculate click hold duration based on profile and fatigue.
     * 
     * Uses ex-Gaussian distribution which closely models human reaction times:
     * - Gaussian core: most clicks cluster around the mean (consistent motor pattern)
     * - Exponential tail: occasional slower clicks (distraction, fatigue, etc.)
     * 
     * This produces a right-skewed distribution that's far more realistic than
     * symmetric Gaussian, which has unrealistic fast outliers.
     * 
     * Parameters are per-profile, creating consistent "click feel" per player:
     * - mu: mean click speed (snappy vs deliberate players)
     * - sigma: consistency (steady vs variable players)
     * - tau: tail heaviness (focused vs distractible players)
     * 
     * Motor speed correlation:
     * Creates unified "player tempo" - fast mouse movers tend to be fast clickers.
     * This prevents the detectable pattern of fast mouse + slow clicks (or vice versa).
     */
    private long calculateClickDuration() {
        // Get player-specific click timing parameters
        double mu = playerProfile.getClickDurationMu();       // 75-95ms
        double sigma = playerProfile.getClickDurationSigma(); // 10-20ms
        double tau = playerProfile.getClickDurationTau();     // 5-15ms
        
        // Apply motor speed correlation
        // Fast movers (speed > 1.0) should have faster clicks (lower mu)
        // Slow movers (speed < 1.0) should have slower clicks (higher mu)
        double mouseSpeed = playerProfile.getMouseSpeedMultiplier();  // 0.8-1.3
        double correlation = playerProfile.getMotorSpeedCorrelation(); // 0.5-0.9
        
        // Calculate speed deviation from baseline (1.0)
        // speedDeviation: -0.2 to +0.3 (based on mouseSpeed 0.8-1.3)
        double speedDeviation = mouseSpeed - 1.0;
        
        // Apply correlated adjustment to mu
        // Fast mouse (positive deviation) → reduce mu (faster clicks)
        // Example: mouseSpeed=1.2, correlation=0.8 → mu reduced by 16%
        double correlatedAdjustment = 1.0 - (speedDeviation * correlation);
        double adjustedMu = mu * correlatedAdjustment;
        
        // Generate ex-Gaussian distributed click duration
        double baseDuration = randomization.exGaussianRandom(
                adjustedMu, sigma, tau, 
                MIN_CLICK_DURATION_MS, MAX_CLICK_DURATION_MS);

        // Apply click variance modifier from profile (multiplicative adjustment)
        baseDuration *= playerProfile.getClickVarianceModifier();
        
        // Clamp after variance modifier
        baseDuration = Randomization.clamp(baseDuration, MIN_CLICK_DURATION_MS, MAX_CLICK_DURATION_MS * 1.3);

        return Math.round(baseDuration);
    }

    /**
     * Generate click position within hitbox using 2D Gaussian distribution.
     * As per spec: center at 45-55% of dimensions, σ = 15% of dimension.
     * 
     * Fatigue effects (combined multiplicatively):
     * - Session click count > 200: session-based variance boost (1.1-1.3x)
     * - FatigueModel: dynamic variance based on overall fatigue level
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
        maybeApplyClickFatigueBoost();
        return fatigueModel.getClickVarianceMultiplier() * clickFatigueVarianceBoost;
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
        if (playerProfile == null) {
            throw new IllegalStateException("PlayerProfile not initialized");
        }
        return playerProfile.getBaseMisclickRate();
    }

    /**
     * Get the misclick probability multiplier from FatigueModel.
     */
    private double getEffectiveMisclickMultiplier() {
        return fatigueModel.getMisclickMultiplier();
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

        log.debug("Simulating misclick: intended ({}, {}), actual ({}, {})",
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

        log.debug("Scrolling {} notches with speed multiplier {}", amount, speedMultiplier);

        int scrolled = 0;
        while (scrolled < remaining) {
            // Calculate delay with acceleration curve (faster in middle)
            double progress = (double) scrolled / remaining;
            double accelerationFactor = 1.0 - 0.3 * Math.sin(progress * Math.PI); // Slower at start/end

            int baseDelay = randomization.uniformRandomInt(MIN_SCROLL_DELAY_MS, MAX_SCROLL_DELAY_MS);
            int delay = (int) (baseDelay * speedMultiplier * accelerationFactor);

            // Perform single scroll tick via UInput
            mouseDevice.scroll(direction);
            scrolled++;

            // Apply delay between ticks (not after last one)
            if (scrolled < remaining) {
                Thread.sleep(delay);

                // Occasional pause mid-scroll (simulates human hesitation)
                if (randomization.chance(SCROLL_PAUSE_PROBABILITY)) {
                    int pauseDuration = randomization.uniformRandomInt(MIN_SCROLL_PAUSE_MS, MAX_SCROLL_PAUSE_MS);
                    log.debug("Scroll pause for {}ms at notch {}/{}", pauseDuration, scrolled, remaining);
                    Thread.sleep(pauseDuration);
                }
            }
        }

        log.debug("Scroll completed: {} notches", amount);
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

        log.debug("Executing drag from ({}, {}) to ({}, {}), distance={}", 
                start.x, start.y, targetX, targetY, distance);

        // Press mouse button via UInput
        mouseDevice.pressButton(BTN_LEFT);

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

        // Release mouse button via UInput
        mouseDevice.releaseButton(BTN_LEFT);

        log.debug("Drag completed");
    }

    /**
     * Execute the movement portion of a drag (slower, with humanized wobble).
     * 
     * Wobble simulates natural hand tremor during drag operations. Real humans
     * have variable tremor patterns based on:
     * - Individual physiology (base frequency)
     * - Fatigue/caffeine/etc (amplitude variation)
     * - Moment-to-moment variation (Perlin noise modulation)
     * 
     * Using pure sine waves is detectable because it's too regular.
     * We use Perlin noise to modulate both frequency and amplitude per-drag.
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

        // Get profile-based wobble characteristics
        double baseFreq = playerProfile.getWobbleFrequencyBase();    // 2.5-4.0 Hz
        double freqVar = playerProfile.getWobbleFrequencyVariance(); // 0.3-0.8
        double ampMod = playerProfile.getWobbleAmplitudeModifier();  // 0.7-1.3
        
        // Per-drag frequency variation using Perlin noise
        // This makes each drag feel slightly different (like real tremor)
        double freqNoise = perlinNoise.noise2D(movementSeed * 0.0001, 0) * freqVar;
        double effectiveFreqX = baseFreq + freqNoise;
        double effectiveFreqY = baseFreq * 0.75 + freqNoise * 0.8; // Y slightly different
        
        // Per-drag amplitude variation (also Perlin-modulated)
        double ampNoise = 1.0 + perlinNoise.noise2D(0, movementSeed * 0.0001) * 0.3;
        double effectiveAmp = DRAG_WOBBLE_AMPLITUDE * ampMod * ampNoise;

        // Execute movement with humanized wobble
        long startTime = System.currentTimeMillis();
        long elapsed = 0;

        while (elapsed < duration) {
            double t = (double) elapsed / duration;
            double adjustedT = calculateVelocityProgress(t);

            // Calculate point on curve
            Point bezierPoint = evaluateBezier(controlPoints, adjustedT);

            // Humanized wobble: base sine wave + Perlin noise modulation
            // The Perlin noise makes the wobble irregular (not perfectly periodic)
            double noiseModX = 1.0 + perlinNoise.noise2D(t * 3, movementSeed * 0.001) * 0.4;
            double noiseModY = 1.0 + perlinNoise.noise2D(movementSeed * 0.001, t * 3) * 0.4;
            
            double wobbleX = Math.sin(t * Math.PI * 2 * effectiveFreqX) * effectiveAmp * noiseModX;
            double wobbleY = Math.cos(t * Math.PI * 2 * effectiveFreqY) * effectiveAmp * 0.7 * noiseModY;

            // Biological motor noise dithered rounding
            // Drag movements are slow, so use low speed context
            double speedPxPerMs = distance / (double) duration;
            motorNoise.setMovementContext(t, speedPxPerMs);
            double[] bioNoise = motorNoise.next2D();
            int x = bezierPoint.x + (int) Math.floor(wobbleX + bioNoise[0] * 0.5 + 0.5);
            int y = bezierPoint.y + (int) Math.floor(wobbleY + bioNoise[1] * 0.5 + 0.5);

            // Clamp to viewport
            Point clamped = clampToViewport(x, y);

            mouseDevice.moveTo(clamped.x, clamped.y);
            currentPosition = clamped;

            // Human-like sleep interval with log-normal distribution
            // Uniform distribution fails K-S tests against real human data
            Thread.sleep(randomization.humanizedDelayMs(7, 0.3, 5, 12));
            elapsed = System.currentTimeMillis() - startTime;
        }

        // Final position
        mouseDevice.moveTo(target.x, target.y);
        currentPosition = target;

        lastMovementTime = System.currentTimeMillis();
        isMoving = false;
    }

    // ========================================================================
    // Raw Mouse Operations (for CameraController integration)
    // ========================================================================

    /**
     * Press a mouse button synchronously (blocking).
     * Used by CameraController for middle mouse drag.
     * 
     * @param button the Linux button code (BTN_LEFT, BTN_MIDDLE, etc.)
     */
    public void pressButtonSync(short button) {
        mouseDevice.pressButton(button);
    }

    /**
     * Release a mouse button synchronously (blocking).
     * Used by CameraController for middle mouse drag.
     * 
     * @param button the Linux button code
     */
    public void releaseButtonSync(short button) {
        mouseDevice.releaseButton(button);
    }

    /**
     * Move mouse to absolute coordinates synchronously.
     * Used by CameraController for camera drag movement.
     * 
     * @param x screen X coordinate
     * @param y screen Y coordinate
     */
    public void moveToSync(int x, int y) {
        mouseDevice.moveTo(x, y);
        currentPosition = new Point(x, y);
    }

    /**
     * Sync mouse position with X11 cursor.
     * Should be called before starting camera drag.
     */
    public void syncMousePosition() {
        mouseDevice.syncPosition();
        currentPosition = mouseDevice.getPosition();
    }

    /**
     * Move mouse by relative delta synchronously.
     * Used by CameraController for camera drag movement.
     * 
     * @param dx delta X (pixels)
     * @param dy delta Y (pixels)
     */
    public void moveRelativeSync(int dx, int dy) {
        mouseDevice.moveBy(dx, dy);
        if (currentPosition != null) {
            currentPosition = new Point(currentPosition.x + dx, currentPosition.y + dy);
        }
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
     * <p><b>Predictive Hover Integration:</b> If there is an active predictive hover,
     * behaviors that would move the mouse away from the target (drift, rest position)
     * are suppressed to maintain the hover. Only stationary behavior is allowed.
     *
     * @return CompletableFuture that completes when idle behavior is done
     */
    public CompletableFuture<Void> performIdleBehavior() {
        // Check if predictive hover should suppress mouse movement
        if (predictiveHoverManager != null && predictiveHoverManager.shouldSuppressIdleBehavior()) {
            log.debug("Idle: suppressed due to active predictive hover");
            return CompletableFuture.completedFuture(null);
        }

        double roll = randomization.uniformRandom(0, 1);

        if (roll < IDLE_STATIONARY_PROBABILITY) {
            // Stationary - do nothing
            log.debug("Idle: stationary");
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

        log.debug("Idle: drift {} pixels", driftPixels);

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
                double distance = Math.hypot(clampedTarget.x - start.x, clampedTarget.y - start.y);
                double speedPxPerMs = distance / (double) duration;

                while (System.currentTimeMillis() - startTime < duration) {
                    double t = (double) (System.currentTimeMillis() - startTime) / duration;
                    
                    // Calculate ideal position
                    double idealX = start.x + (clampedTarget.x - start.x) * t;
                    double idealY = start.y + (clampedTarget.y - start.y) * t;
                    
                    // Biological motor noise dithered rounding
                    // Slow drift = low speed, precision-like context
                    motorNoise.setMovementContext(t, speedPxPerMs);
                    double[] bioNoise = motorNoise.next2D();
                    int x = (int) Math.floor(idealX + bioNoise[0] * 0.5 + 0.5);
                    int y = (int) Math.floor(idealY + bioNoise[1] * 0.5 + 0.5);

                    // Clamp interpolated point too (paranoid safety)
                    Point clamped = clampToViewport(x, y);
                    mouseDevice.moveTo(clamped.x, clamped.y);
                    currentPosition = clamped;

                    Thread.sleep(20); // Slow update rate for drift
                }

                mouseDevice.moveTo(clampedTarget.x, clampedTarget.y);
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

        log.debug("Idle: move to rest position ({})", restRegion.name());

        // ScreenRegion uses canvas-relative coordinates
        return moveToCanvas(restPos[0], restPos[1]);
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================
    
    /**
     * Apply cognitive delay for transitioning between different action types.
     * 
     * This simulates the mental "context switch" time when a human transitions
     * from one type of action to another (e.g., combat → banking, walking → clicking).
     * 
     * The delay is profile-based:
     * - cognitiveDelayBase: personal "thinking speed" (80-200ms)
     * - cognitiveDelayVariance: how variable the delay is (0.3-0.7)
     * 
     * Use this between logically different actions to avoid inhuman speed.
     * Do NOT use for repeated same-type actions (e.g., clicking multiple items).
     * 
     * @return CompletableFuture that completes after the cognitive delay
     */
    public CompletableFuture<Void> applyCognitiveDelay() {
        double base = playerProfile.getCognitiveDelayBase();
        double variance = playerProfile.getCognitiveDelayVariance();
        
        // Calculate delay with variance: base * uniform(1-var, 1+var)
        double varianceFactor = randomization.uniformRandom(1.0 - variance, 1.0 + variance);
        long delay = Math.round(base * varianceFactor);
        
        // Clamp to reasonable bounds (50-500ms)
        delay = Randomization.clamp(delay, 50, 500);
        
        log.debug("Cognitive delay: {}ms", delay);
        return sleep(delay);
    }
    
    /**
     * Apply cognitive delay synchronously (blocking).
     * 
     * Use when you need a blocking version of the cognitive delay.
     * Prefer the async version when possible.
     * 
     * @throws InterruptedException if the thread is interrupted
     */
    public void applyCognitiveDelaySync() throws InterruptedException {
        double base = playerProfile.getCognitiveDelayBase();
        double variance = playerProfile.getCognitiveDelayVariance();
        
        double varianceFactor = randomization.uniformRandom(1.0 - variance, 1.0 + variance);
        long delay = Math.round(base * varianceFactor);
        delay = Randomization.clamp(delay, 50, 500);
        
        log.debug("Cognitive delay (sync): {}ms", delay);
        Thread.sleep(delay);
    }

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
        clickFatigueVarianceBoost = 1.0;
    }

    /**
     * Shutdown the controller and release resources.
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
        
        // Close the UInput virtual device
        if (mouseDevice != null) {
            mouseDevice.close();
        }
    }

    /**
     * Cached canvas bounds helper.
     */
    private Rectangle getCanvasBounds() {
        refreshDisplayInfoIfStale();
        return new Rectangle(canvasOffset.x, canvasOffset.y, cachedCanvasSize.width, cachedCanvasSize.height);
    }

    /**
     * Cached viewport bounds helper.
     */
    private Rectangle getViewportBounds() {
        refreshDisplayInfoIfStale();
        return cachedViewportBounds;
    }

    /**
     * Apply session click fatigue variance bump after threshold is reached.
     */
    private void maybeApplyClickFatigueBoost() {
        if (sessionClickCount >= CLICK_FATIGUE_THRESHOLD && clickFatigueVarianceBoost == 1.0) {
            clickFatigueVarianceBoost = randomization.uniformRandom(
                    MIN_FATIGUE_VARIANCE_MULTIPLIER, MAX_FATIGUE_VARIANCE_MULTIPLIER);
            log.debug("Click fatigue variance boost applied: {}", String.format("%.2f", clickFatigueVarianceBoost));
        }
    }
}

