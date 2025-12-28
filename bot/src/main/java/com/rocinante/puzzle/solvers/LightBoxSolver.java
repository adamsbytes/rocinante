package com.rocinante.puzzle.solvers;

import com.rocinante.puzzle.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import java.util.*;

/**
 * Solver for the Light Box puzzle from treasure trails.
 *
 * <p>The light box puzzle is a "lights out" style puzzle where:
 * <ul>
 *   <li>There are 8 buttons (A-H)</li>
 *   <li>Each button toggles a specific pattern of lights</li>
 *   <li>The goal is to turn all lights off</li>
 *   <li>The effect of each button must be learned by clicking</li>
 * </ul>
 *
 * <p>This solver uses an adaptive approach:
 * <ol>
 *   <li>Click buttons one at a time to learn their effects</li>
 *   <li>Once all effects are known, compute optimal solution</li>
 *   <li>Execute the solution sequence</li>
 * </ol>
 *
 * <p>Widget IDs:
 * <ul>
 *   <li>Interface: 322 (LIGHT_PUZZLE)</li>
 *   <li>Lights container: child 2</li>
 *   <li>Buttons A-H: children specific IDs</li>
 * </ul>
 */
@Slf4j
public class LightBoxSolver implements PuzzleSolverStrategy {

    /**
     * Widget group ID for light box interface.
     */
    private static final int WIDGET_GROUP_ID = 322;

    /**
     * Widget child ID for the lights grid container.
     */
    private static final int WIDGET_LIGHTS_CHILD = 2;

    /**
     * Widget child IDs for buttons A-H.
     */
    private static final int[] BUTTON_CHILDREN = {3, 4, 5, 6, 7, 8, 9, 10};

    /**
     * Number of buttons.
     */
    private static final int NUM_BUTTONS = 8;

    /**
     * Light grid dimensions.
     */
    private static final int ROWS = PuzzleState.LIGHT_BOX_ROWS;
    private static final int COLS = PuzzleState.LIGHT_BOX_COLS;
    private static final int NUM_LIGHTS = ROWS * COLS;

    /**
     * Item ID for lit bulb (from RuneLite).
     */
    private static final int LIGHT_BULB_ON = 9246;

    /**
     * Learned button effects (button index -> toggle pattern).
     * Populated as buttons are clicked.
     */
    private final boolean[][] buttonEffects = new boolean[NUM_BUTTONS][NUM_LIGHTS];
    private final boolean[] buttonEffectsKnown = new boolean[NUM_BUTTONS];

    /**
     * Previous light state for detecting button effects.
     */
    private boolean[] previousLightState = null;
    private int lastClickedButton = -1;

    /**
     * Tracks whether the puzzle was visible in the previous detectState call.
     * Used to detect puzzle open/close transitions.
     */
    private boolean wasVisible = false;

    /**
     * Hash of the initial light state when we first saw this puzzle.
     * Used to detect if this is a different puzzle instance.
     */
    private int initialStateHash = 0;

    @Override
    public PuzzleType getPuzzleType() {
        return PuzzleType.LIGHT_BOX;
    }

    @Override
    public int getWidgetGroupId() {
        return WIDGET_GROUP_ID;
    }

    @Override
    public boolean isPuzzleVisible(Client client) {
        Widget lightsWidget = client.getWidget(WIDGET_GROUP_ID, WIDGET_LIGHTS_CHILD);
        return lightsWidget != null && !lightsWidget.isHidden();
    }

    @Override
    public Optional<PuzzleState> detectState(Client client) {
        boolean isVisible = isPuzzleVisible(client);
        
        // Detect new puzzle: was not visible before, now is visible
        if (isVisible && !wasVisible) {
            log.debug("Light box puzzle newly visible - resetting learned effects");
            reset();
        }
        wasVisible = isVisible;

        if (!isVisible) {
            return Optional.empty();
        }

        boolean[] lights = readLightStates(client);
        if (lights == null) {
            return Optional.empty();
        }

        // Check if this looks like a completely different puzzle
        // (e.g., player closed and got a new casket)
        int stateHash = computeStateHash(lights);
        if (initialStateHash != 0 && previousLightState != null && allEffectsKnown()) {
            // If we think we know all effects but the state doesn't make sense,
            // this might be a new puzzle with the same visibility
            // This can happen if player reopens the interface quickly
        }
        
        // Store initial state hash on first detection
        if (initialStateHash == 0) {
            initialStateHash = stateHash;
        }

        // Update button effect tracking
        updateButtonEffects(lights);

        try {
            return Optional.of(new PuzzleState(lights));
        } catch (Exception e) {
            log.error("Failed to create light box state", e);
            return Optional.empty();
        }
    }

    /**
     * Compute a hash of the light state for detecting puzzle changes.
     */
    private int computeStateHash(boolean[] lights) {
        return Arrays.hashCode(lights);
    }

    /**
     * Read current light states from the widget.
     */
    private boolean[] readLightStates(Client client) {
        Widget lightsWidget = client.getWidget(WIDGET_GROUP_ID, WIDGET_LIGHTS_CHILD);
        if (lightsWidget == null) {
            return null;
        }

        Widget[] children = lightsWidget.getDynamicChildren();
        if (children == null || children.length < NUM_LIGHTS) {
            log.debug("Not enough light widgets: {}", children != null ? children.length : 0);
            return null;
        }

        boolean[] lights = new boolean[NUM_LIGHTS];
        for (int i = 0; i < NUM_LIGHTS; i++) {
            lights[i] = children[i].getItemId() == LIGHT_BULB_ON;
        }

        return lights;
    }

    /**
     * Update learned button effects based on state changes.
     */
    private void updateButtonEffects(boolean[] currentState) {
        if (previousLightState != null && lastClickedButton >= 0 && !buttonEffectsKnown[lastClickedButton]) {
            // Compute the diff (XOR of previous and current states)
            boolean[] effect = new boolean[NUM_LIGHTS];
            for (int i = 0; i < NUM_LIGHTS; i++) {
                effect[i] = previousLightState[i] != currentState[i];
            }

            buttonEffects[lastClickedButton] = effect;
            buttonEffectsKnown[lastClickedButton] = true;
            log.debug("Learned effect for button {}", (char) ('A' + lastClickedButton));
        }

        previousLightState = currentState.clone();
        lastClickedButton = -1;
    }

    @Override
    public List<WidgetClick> solve(PuzzleState state) {
        if (state == null || state.getPuzzleType() != PuzzleType.LIGHT_BOX) {
            return Collections.emptyList();
        }

        if (state.isSolved()) {
            log.debug("Light box already solved");
            return Collections.emptyList();
        }

        // Check if we know all button effects
        int unknownButtons = countUnknownButtons();
        if (unknownButtons > 0) {
            // Return click for first unknown button to learn its effect
            log.debug("Learning button effects, {} unknown", unknownButtons);
            return getNextLearningClick();
        }

        // All effects known, compute solution using brute-force with pruning
        boolean[] currentLights = new boolean[NUM_LIGHTS];
        for (int i = 0; i < NUM_LIGHTS; i++) {
            currentLights[i] = state.getLight(i);
        }

        List<Integer> solution = computeSolution(currentLights);
        if (solution == null) {
            log.warn("No solution found for light box");
            return Collections.emptyList();
        }

        return convertSolutionToClicks(solution);
    }

    /**
     * Count how many button effects are still unknown.
     */
    private int countUnknownButtons() {
        int count = 0;
        for (boolean known : buttonEffectsKnown) {
            if (!known) count++;
        }
        return count;
    }

    /**
     * Get the next button click to learn an unknown effect.
     */
    private List<WidgetClick> getNextLearningClick() {
        for (int i = 0; i < NUM_BUTTONS; i++) {
            if (!buttonEffectsKnown[i]) {
                lastClickedButton = i;
                return Collections.singletonList(
                        WidgetClick.builder()
                                .groupId(WIDGET_GROUP_ID)
                                .childId(BUTTON_CHILDREN[i])
                                .description("Learn button " + (char) ('A' + i))
                                .build()
                );
            }
        }
        return Collections.emptyList();
    }

    /**
     * Compute an optimal solution using brute-force with pruning.
     * 
     * <p>Since each button only needs to be pressed 0 or 1 times (pressing twice
     * cancels out), we try all 2^8 = 256 combinations.
     */
    private List<Integer> computeSolution(boolean[] currentLights) {
        // Try all possible button combinations (2^8 = 256)
        int bestLength = Integer.MAX_VALUE;
        List<Integer> bestSolution = null;

        for (int mask = 0; mask < (1 << NUM_BUTTONS); mask++) {
            int presses = Integer.bitCount(mask);
            if (presses >= bestLength) {
                continue; // Can't improve on best
            }

            // Apply the combination and check if it solves
            boolean[] testLights = currentLights.clone();
            for (int button = 0; button < NUM_BUTTONS; button++) {
                if ((mask & (1 << button)) != 0) {
                    applyButtonEffect(testLights, button);
                }
            }

            // Check if solved (all lights off)
            boolean solved = true;
            for (boolean light : testLights) {
                if (light) {
                    solved = false;
                    break;
                }
            }

            if (solved) {
                bestLength = presses;
                bestSolution = new ArrayList<>();
                for (int button = 0; button < NUM_BUTTONS; button++) {
                    if ((mask & (1 << button)) != 0) {
                        bestSolution.add(button);
                    }
                }
            }
        }

        if (bestSolution != null) {
            log.debug("Found solution with {} button presses", bestSolution.size());
        }
        return bestSolution;
    }

    /**
     * Apply a button's effect to the light state.
     */
    private void applyButtonEffect(boolean[] lights, int button) {
        boolean[] effect = buttonEffects[button];
        for (int i = 0; i < NUM_LIGHTS; i++) {
            if (effect[i]) {
                lights[i] = !lights[i];
            }
        }
    }

    /**
     * Convert solution (list of button indices) to widget clicks.
     */
    private List<WidgetClick> convertSolutionToClicks(List<Integer> solution) {
        List<WidgetClick> clicks = new ArrayList<>();

        for (int button : solution) {
            lastClickedButton = button; // Track for effect learning
            clicks.add(WidgetClick.builder()
                    .groupId(WIDGET_GROUP_ID)
                    .childId(BUTTON_CHILDREN[button])
                    .description("Press button " + (char) ('A' + button))
                    .build());
        }

        return clicks;
    }

    /**
     * Reset learned button effects.
     * Called when starting a new puzzle.
     */
    public void reset() {
        Arrays.fill(buttonEffectsKnown, false);
        for (int i = 0; i < NUM_BUTTONS; i++) {
            Arrays.fill(buttonEffects[i], false);
        }
        previousLightState = null;
        lastClickedButton = -1;
        initialStateHash = 0;
        // Note: wasVisible is intentionally not reset here as it's used for visibility tracking
        log.debug("Light box solver reset - ready for new puzzle");
    }

    /**
     * Check if all button effects have been learned.
     *
     * @return true if all effects are known
     */
    public boolean allEffectsKnown() {
        for (boolean known : buttonEffectsKnown) {
            if (!known) return false;
        }
        return true;
    }

    @Override
    public long getSolveTimeoutMs() {
        return 500; // Light box is fast to solve once effects are known
    }

    @Override
    public int getClickDelayMs() {
        return 200; // Slightly longer delay for light box (effects need to be observed)
    }
}

