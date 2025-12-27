package com.rocinante.quest.steps;

import com.rocinante.puzzle.PuzzleSolverRegistry;
import com.rocinante.puzzle.PuzzleType;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.PuzzleTask;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * Quest step for solving in-game puzzles.
 *
 * <p>Supports:
 * <ul>
 *   <li>Sliding puzzles (5x5 tile puzzles from treasure trails/quests)</li>
 *   <li>Light box puzzles (lights-out style from treasure trails)</li>
 * </ul>
 *
 * <p>The step can either specify a puzzle type explicitly or auto-detect
 * the puzzle type when executed.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Auto-detect puzzle type
 * PuzzleQuestStep step = new PuzzleQuestStep("Solve the puzzle");
 *
 * // Specific puzzle type
 * PuzzleQuestStep slidingStep = new PuzzleQuestStep(PuzzleType.SLIDING_PUZZLE, "Solve the sliding puzzle");
 *
 * // With custom timeout
 * PuzzleQuestStep step = new PuzzleQuestStep(PuzzleType.LIGHT_BOX, "Solve the light box")
 *     .withTimeout(180000); // 3 minutes
 * }</pre>
 */
@Getter
@Setter
@Accessors(chain = true)
public class PuzzleQuestStep extends QuestStep {

    /**
     * The specific puzzle type to solve, or null for auto-detect.
     */
    private PuzzleType puzzleType;

    /**
     * Expected widget group ID for this puzzle (optional validation).
     */
    private int expectedWidgetId = -1;

    /**
     * Minimum delay between puzzle clicks (ms).
     */
    private int minClickDelay = 100;

    /**
     * Maximum delay between puzzle clicks (ms).
     */
    private int maxClickDelay = 250;

    /**
     * Create a puzzle step with auto-detection.
     *
     * @param text instruction text
     */
    public PuzzleQuestStep(String text) {
        super(text);
    }

    /**
     * Create a puzzle step for a specific puzzle type.
     *
     * @param puzzleType the puzzle type to solve
     * @param text instruction text
     */
    public PuzzleQuestStep(PuzzleType puzzleType, String text) {
        super(text);
        this.puzzleType = puzzleType;
    }

    @Override
    public StepType getType() {
        return StepType.PUZZLE;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        List<Task> tasks = new ArrayList<>();

        // Get puzzle solver registry from context
        PuzzleSolverRegistry registry = ctx.getPuzzleSolverRegistry();
        if (registry == null) {
            throw new IllegalStateException("PuzzleSolverRegistry not available in TaskContext");
        }

        // Create puzzle task
        PuzzleTask puzzleTask;
        if (puzzleType != null && puzzleType != PuzzleType.UNKNOWN) {
            puzzleTask = new PuzzleTask(registry, puzzleType);
        } else {
            puzzleTask = new PuzzleTask(registry);
        }

        // Configure task
        puzzleTask.withDescription(getText())
                .withClickDelays(minClickDelay, maxClickDelay);

        if (getTimeoutMs() > 0) {
            puzzleTask.setTimeout(java.time.Duration.ofMillis(getTimeoutMs()));
        }

        tasks.add(puzzleTask);
        return tasks;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a step to solve a sliding puzzle.
     *
     * @param text instruction text
     * @return puzzle step for sliding puzzles
     */
    public static PuzzleQuestStep slidingPuzzle(String text) {
        return new PuzzleQuestStep(PuzzleType.SLIDING_PUZZLE, text);
    }

    /**
     * Create a step to solve a light box puzzle.
     *
     * @param text instruction text
     * @return puzzle step for light box puzzles
     */
    public static PuzzleQuestStep lightBox(String text) {
        return new PuzzleQuestStep(PuzzleType.LIGHT_BOX, text);
    }

    /**
     * Create a step with auto-detection of puzzle type.
     *
     * @param text instruction text
     * @return puzzle step with auto-detection
     */
    public static PuzzleQuestStep autoDetect(String text) {
        return new PuzzleQuestStep(text);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set the puzzle type (builder-style).
     *
     * @param type the puzzle type
     * @return this step for chaining
     */
    public PuzzleQuestStep withPuzzleType(PuzzleType type) {
        this.puzzleType = type;
        return this;
    }

    /**
     * Set the expected widget ID for validation (builder-style).
     *
     * @param widgetId the widget group ID
     * @return this step for chaining
     */
    public PuzzleQuestStep withExpectedWidgetId(int widgetId) {
        this.expectedWidgetId = widgetId;
        return this;
    }

    /**
     * Set click delay range (builder-style).
     *
     * @param minMs minimum delay in milliseconds
     * @param maxMs maximum delay in milliseconds
     * @return this step for chaining
     */
    public PuzzleQuestStep withClickDelays(int minMs, int maxMs) {
        this.minClickDelay = minMs;
        this.maxClickDelay = maxMs;
        return this;
    }

    /**
     * Set faster click delays for experienced solving (builder-style).
     *
     * @return this step for chaining
     */
    public PuzzleQuestStep withFastClicks() {
        this.minClickDelay = 50;
        this.maxClickDelay = 120;
        return this;
    }

    /**
     * Set slower click delays for more humanized solving (builder-style).
     *
     * @return this step for chaining
     */
    public PuzzleQuestStep withSlowClicks() {
        this.minClickDelay = 200;
        this.maxClickDelay = 400;
        return this;
    }

    @Override
    public String getDescription() {
        StringBuilder sb = new StringBuilder("PUZZLE: ");
        if (puzzleType != null) {
            sb.append(puzzleType.getDisplayName()).append(" - ");
        }
        sb.append(getText());
        return sb.toString();
    }
}

