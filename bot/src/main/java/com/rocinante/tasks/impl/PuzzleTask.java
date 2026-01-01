package com.rocinante.tasks.impl;

import com.rocinante.puzzle.*;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import com.rocinante.util.Randomization;

import java.awt.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * Task for solving puzzles in OSRS.
 *
 * <p>Supports:
 * <ul>
 *   <li>Sliding puzzles (5x5 tile puzzles from treasure trails)</li>
 *   <li>Light box puzzles (lights-out style from treasure trails)</li>
 * </ul>
 *
 * <p>Execution phases:
 * <ol>
 *   <li>{@code DETECT_PUZZLE} - Identify puzzle type from visible widgets</li>
 *   <li>{@code READ_STATE} - Read current puzzle state</li>
 *   <li>{@code COMPUTE_SOLUTION} - Run solver algorithm (async)</li>
 *   <li>{@code EXECUTE_CLICK} - Click next widget in solution</li>
 *   <li>{@code VERIFY_PROGRESS} - Confirm state changed</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Auto-detect puzzle type
 * PuzzleTask autoTask = new PuzzleTask(registry);
 *
 * // Specific puzzle type
 * PuzzleTask slidingTask = new PuzzleTask(registry, PuzzleType.SLIDING_PUZZLE);
 *
 * // With custom description
 * PuzzleTask task = new PuzzleTask(registry)
 *     .withDescription("Solve treasure trail puzzle");
 * }</pre>
 */
@Slf4j
public class PuzzleTask extends AbstractTask {

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Registry for puzzle solvers.
     */
    private final PuzzleSolverRegistry registry;

    /**
     * Specific puzzle type to solve (null for auto-detect).
     */
    @Getter
    @Setter
    private PuzzleType targetPuzzleType;

    /**
     * Custom description.
     */
    @Getter
    @Setter
    private String description;

    /**
     * Minimum delay between puzzle clicks (ms).
     */
    @Getter
    @Setter
    private int minClickDelay = 100;

    /**
     * Maximum delay between puzzle clicks (ms).
     */
    @Getter
    @Setter
    private int maxClickDelay = 250;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private Phase phase = Phase.DETECT_PUZZLE;

    /**
     * The solver being used for the current puzzle.
     */
    private PuzzleSolverStrategy solver;

    /**
     * Current puzzle state.
     */
    private PuzzleState currentState;

    /**
     * Solution steps to execute.
     */
    private Queue<WidgetClick> solutionSteps = new LinkedList<>();

    /**
     * Future for async solution computation.
     */
    private Future<List<WidgetClick>> solveFuture;

    /**
     * Executor for async solving.
     */
    private final ExecutorService solveExecutor = Executors.newSingleThreadExecutor();

    /**
     * Whether a click is currently in progress.
     */
    private boolean clickPending = false;

    /**
     * Ticks since last state read (for verification).
     */
    private int ticksSinceStateRead = 0;

    /**
     * State before last click (for verification).
     */
    private PuzzleState stateBeforeClick;

    /**
     * Number of consecutive verification failures.
     */
    private int verifyFailures = 0;

    /**
     * Maximum verification failures before re-solving.
     */
    private static final int MAX_VERIFY_FAILURES = 3;

    /**
     * Total clicks executed (for logging).
     */
    private int totalClicks = 0;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a puzzle task with auto-detection.
     *
     * @param registry the puzzle solver registry
     */
    public PuzzleTask(PuzzleSolverRegistry registry) {
        this.registry = registry;
    }

    /**
     * Create a puzzle task for a specific puzzle type.
     *
     * @param registry the puzzle solver registry
     * @param puzzleType the puzzle type to solve
     */
    public PuzzleTask(PuzzleSolverRegistry registry, PuzzleType puzzleType) {
        this.registry = registry;
        this.targetPuzzleType = puzzleType;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set custom description (builder-style).
     */
    public PuzzleTask withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * Set target puzzle type (builder-style).
     */
    public PuzzleTask withPuzzleType(PuzzleType type) {
        this.targetPuzzleType = type;
        return this;
    }

    /**
     * Set click delay range (builder-style).
     */
    public PuzzleTask withClickDelays(int minMs, int maxMs) {
        this.minClickDelay = minMs;
        this.maxClickDelay = maxMs;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.isLoggedIn();
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (clickPending) {
            return; // Wait for click to complete
        }

        switch (phase) {
            case DETECT_PUZZLE:
                executeDetectPuzzle(ctx);
                break;
            case READ_STATE:
                executeReadState(ctx);
                break;
            case COMPUTE_SOLUTION:
                executeComputeSolution(ctx);
                break;
            case EXECUTE_CLICK:
                executeClick(ctx);
                break;
            case VERIFY_PROGRESS:
                executeVerifyProgress(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Detect Puzzle
    // ========================================================================

    private void executeDetectPuzzle(TaskContext ctx) {
        Client client = ctx.getClient();

        // Find solver - either by specified type or auto-detect
        Optional<PuzzleSolverStrategy> foundSolver;
        if (targetPuzzleType != null && targetPuzzleType != PuzzleType.UNKNOWN) {
            foundSolver = registry.getSolver(targetPuzzleType);
            if (foundSolver.isEmpty()) {
                fail("No solver registered for puzzle type: " + targetPuzzleType);
                return;
            }
            if (!foundSolver.get().isPuzzleVisible(client)) {
                log.debug("Waiting for {} puzzle interface...", targetPuzzleType);
                return;
            }
        } else {
            foundSolver = registry.detectVisiblePuzzle(client);
            if (foundSolver.isEmpty()) {
                log.debug("No puzzle interface visible, waiting...");
                return;
            }
        }

        solver = foundSolver.get();
        log.info("Detected puzzle: {}", solver.getPuzzleType().getDisplayName());
        phase = Phase.READ_STATE;
    }

    // ========================================================================
    // Phase: Read State
    // ========================================================================

    private void executeReadState(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if puzzle is still visible
        if (!solver.isPuzzleVisible(client)) {
            // Puzzle closed - might be solved or cancelled
            if (currentState != null && currentState.isSolved()) {
                log.info("Puzzle solved! Total clicks: {}", totalClicks);
                complete();
            } else {
                fail("Puzzle interface closed unexpectedly");
            }
            return;
        }

        // Read current state
        Optional<PuzzleState> state = solver.detectState(client);
        if (state.isEmpty()) {
            log.debug("Could not read puzzle state");
            return;
        }

        currentState = state.get();
        ticksSinceStateRead = 0;

        // Check if already solved
        if (currentState.isSolved()) {
            log.info("Puzzle is already solved!");
            complete();
            return;
        }

        log.debug("Read puzzle state: solved={}", currentState.isSolved());
        phase = Phase.COMPUTE_SOLUTION;
    }

    // ========================================================================
    // Phase: Compute Solution
    // ========================================================================

    private void executeComputeSolution(TaskContext ctx) {
        // Start async solving if not already started
        if (solveFuture == null) {
            log.debug("Starting async puzzle solve...");
            final PuzzleState stateCopy = currentState;
            solveFuture = solveExecutor.submit(() -> solver.solve(stateCopy));
            return;
        }

        // Check if solving is complete
        if (!solveFuture.isDone()) {
            return;
        }

        // Get solution
        try {
            List<WidgetClick> solution = solveFuture.get(0, TimeUnit.MILLISECONDS);
            solveFuture = null;

            if (solution == null || solution.isEmpty()) {
                // For light box, empty solution might mean we need to learn more
                if (solver.getPuzzleType() == PuzzleType.LIGHT_BOX && 
                    solver instanceof com.rocinante.puzzle.solvers.LightBoxSolver) {
                    com.rocinante.puzzle.solvers.LightBoxSolver lightSolver = 
                        (com.rocinante.puzzle.solvers.LightBoxSolver) solver;
                    if (!lightSolver.allEffectsKnown()) {
                        // Re-solve to get next learning click
                        solution = solver.solve(currentState);
                    }
                }
                
                if (solution == null || solution.isEmpty()) {
                    fail("Solver could not find a solution");
                    return;
                }
            }

            solutionSteps.clear();
            solutionSteps.addAll(solution);
            log.info("Solution computed: {} steps", solutionSteps.size());
            phase = Phase.EXECUTE_CLICK;

        } catch (TimeoutException e) {
            // Still computing
            return;
        } catch (Exception e) {
            log.error("Error getting solution", e);
            solveFuture = null;
            fail("Failed to compute solution: " + e.getMessage());
        }
    }

    // ========================================================================
    // Phase: Execute Click
    // ========================================================================

    private void executeClick(TaskContext ctx) {
        // Check if we have more clicks to execute
        if (solutionSteps.isEmpty()) {
            // All clicks executed, verify final state
            phase = Phase.READ_STATE;
            return;
        }

        Client client = ctx.getClient();

        // Check if puzzle is still visible
        if (!solver.isPuzzleVisible(client)) {
            fail("Puzzle interface closed during execution");
            return;
        }

        // Get next click
        WidgetClick click = solutionSteps.peek();
        
        // Find the widget to click
        Widget widget = findWidget(client, click);
        if (widget == null || widget.isHidden()) {
            log.warn("Target widget not found: {}", click.toDisplayString());
            fail("Target widget not visible");
            return;
        }

        // Store state before click for verification
        stateBeforeClick = currentState;

        // Capture randomization for use in this method
        Randomization rand = ctx.getRandomization();

        // Calculate click point
        Rectangle bounds = widget.getBounds();
        Point clickPoint = calculateClickPoint(bounds, rand);

        // Execute click
        clickPending = true;
        totalClicks++;

        log.debug("Clicking: {} (step {})", click.toDisplayString(), totalClicks);

        ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenCompose(v -> {
                    // Add humanized delay after click
                    int delay = rand != null 
                            ? rand.uniformRandomInt(minClickDelay, maxClickDelay)
                            : minClickDelay + (int)(Math.random() * (maxClickDelay - minClickDelay));
                    return delayAsync(delay);
                })
                .thenRun(() -> {
                    clickPending = false;
                    solutionSteps.poll(); // Remove executed click
                    phase = Phase.VERIFY_PROGRESS;
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Click failed", e);
                    fail("Click failed: " + e.getMessage());
                    return null;
                });
    }

    /**
     * Find the widget to click based on WidgetClick specification.
     */
    private Widget findWidget(Client client, WidgetClick click) {
        Widget widget = client.getWidget(click.getGroupId(), click.getChildId());
        
        if (widget != null && click.hasDynamicChild()) {
            Widget[] children = widget.getDynamicChildren();
            if (children != null && click.getDynamicChildIndex() < children.length) {
                widget = children[click.getDynamicChildIndex()];
            } else {
                return null;
            }
        }
        
        return widget;
    }

    /**
     * Calculate a humanized click point within widget bounds.
     * 
     * @param bounds the widget bounds
     * @param rand the randomization instance (may be null)
     */
    private Point calculateClickPoint(Rectangle bounds, Randomization rand) {
        // Gaussian distribution centered at widget center (σ = 15% of dimension)
        double offsetX, offsetY;
        if (rand != null) {
            offsetX = rand.gaussianRandom(0, 0.15 * bounds.width);
            offsetY = rand.gaussianRandom(0, 0.15 * bounds.height);
        } else {
            // Fallback if randomization unavailable
            offsetX = (new java.util.Random().nextGaussian() * 0.15) * bounds.width;
            offsetY = (new java.util.Random().nextGaussian() * 0.15) * bounds.height;
        }

        int x = bounds.x + bounds.width / 2 + (int) offsetX;
        int y = bounds.y + bounds.height / 2 + (int) offsetY;

        // Clamp to bounds
        x = Math.max(bounds.x + 2, Math.min(bounds.x + bounds.width - 2, x));
        y = Math.max(bounds.y + 2, Math.min(bounds.y + bounds.height - 2, y));

        return new Point(x, y);
    }

    /**
     * Async delay helper using scheduled executor for non-blocking delay.
     */
    private CompletableFuture<Void> delayAsync(int delayMs) {
        return CompletableFuture.supplyAsync(
            () -> null,
            CompletableFuture.delayedExecutor(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        ).thenAccept(v -> {});
    }

    // ========================================================================
    // Phase: Verify Progress
    // ========================================================================

    private void executeVerifyProgress(TaskContext ctx) {
        ticksSinceStateRead++;

        // Wait a tick for state to update
        if (ticksSinceStateRead < 2) {
            return;
        }

        Client client = ctx.getClient();

        // Check if puzzle is still visible
        if (!solver.isPuzzleVisible(client)) {
            // Might be solved - check if we expected solution
            if (solutionSteps.isEmpty()) {
                log.info("Puzzle solved! Total clicks: {}", totalClicks);
                complete();
            } else {
                fail("Puzzle closed before all clicks executed");
            }
            return;
        }

        // Read new state
        Optional<PuzzleState> newState = solver.detectState(client);
        if (newState.isEmpty()) {
            verifyFailures++;
            if (verifyFailures >= MAX_VERIFY_FAILURES) {
                fail("Could not verify puzzle state after click");
            }
            return;
        }

        currentState = newState.get();
        verifyFailures = 0;

        // Check if solved
        if (currentState.isSolved()) {
            log.info("Puzzle solved! Total clicks: {}", totalClicks);
            complete();
            return;
        }

        // Continue with next click or re-solve if needed
        if (solutionSteps.isEmpty()) {
            // Need to re-compute solution (state may have drifted)
            log.debug("Solution exhausted but puzzle not solved, re-computing...");
            phase = Phase.COMPUTE_SOLUTION;
        } else {
            phase = Phase.EXECUTE_CLICK;
        }
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Override
    public void onComplete(TaskContext ctx) {
        super.onComplete(ctx);
        cleanup();
    }

    @Override
    public void onFail(TaskContext ctx, Exception e) {
        super.onFail(ctx, e);
        cleanup();
    }

    private void cleanup() {
        if (solveFuture != null) {
            solveFuture.cancel(true);
            solveFuture = null;
        }
        solutionSteps.clear();
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        if (solver != null) {
            return "Solve " + solver.getPuzzleType().getDisplayName();
        }
        if (targetPuzzleType != null) {
            return "Solve " + targetPuzzleType.getDisplayName();
        }
        return "Solve puzzle";
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    /**
     * Execution phases for puzzle solving.
     */
    private enum Phase {
        DETECT_PUZZLE,
        READ_STATE,
        COMPUTE_SOLUTION,
        EXECUTE_CLICK,
        VERIFY_PROGRESS
    }
}

