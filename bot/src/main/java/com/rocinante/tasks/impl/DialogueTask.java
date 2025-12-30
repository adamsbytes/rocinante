package com.rocinante.tasks.impl;

import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.timing.DelayProfile;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Task for handling NPC dialogue windows.
 *
 * Per REQUIREMENTS.md Section 5.4.5:
 * <ul>
 *   <li>Detect dialogue widget visibility (groups 231, 217, 219, 229)</li>
 *   <li>Click "Click to continue" with humanized delays</li>
 *   <li>Select numbered options by text matching or index</li>
 *   <li>Support multi-step dialogues</li>
 *   <li>Use DIALOGUE_READ timing (1200ms + 50ms/word)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Click through dialogue
 * DialogueTask clickThrough = new DialogueTask();
 *
 * // Select specific option
 * DialogueTask selectOption = new DialogueTask()
 *     .withOptionText("Yes, I'm a new player");
 *
 * // Select by index
 * DialogueTask selectFirst = new DialogueTask()
 *     .withOptionIndex(1);
 * }</pre>
 */
@Slf4j
public class DialogueTask extends AbstractTask {

    // ========================================================================
    // Widget IDs for Dialogue
    // ========================================================================

    /**
     * NPC dialogue head widget group.
     */
    private static final int WIDGET_NPC_DIALOGUE = 231;

    /**
     * Player dialogue widget group.
     */
    private static final int WIDGET_PLAYER_DIALOGUE = 217;

    /**
     * Dialogue options widget group (numbered choices).
     */
    private static final int WIDGET_DIALOGUE_OPTIONS = 219;

    /**
     * Continue dialogue widget group.
     */
    private static final int WIDGET_CONTINUE = 229;

    /**
     * Generic dialogue widget.
     */
    private static final int WIDGET_DIALOGUE_GENERIC = 193;

    /**
     * Sprite dialogue widget (for some NPCs).
     */
    private static final int WIDGET_SPRITE_DIALOGUE = 11;

    /**
     * Level up dialogue.
     */
    private static final int WIDGET_LEVEL_UP = 233;

    // Child IDs for common dialogue elements
    private static final int CHILD_NPC_TEXT = 4;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Text of the option to select (case-insensitive partial match).
     */
    @Getter
    @Setter
    private String optionText;

    /**
     * Index of the option to select (1-based).
     */
    @Getter
    @Setter
    private int optionIndex = -1;

    /**
     * Whether to click through all dialogue until closed.
     */
    @Getter
    @Setter
    private boolean clickThroughAll = true;

    /**
     * Maximum number of continue clicks before giving up.
     */
    @Getter
    @Setter
    private int maxContinueClicks = 20;

    /**
     * Custom description.
     */
    @Getter
    @Setter
    private String description;

    /**
     * List of options to select in order (for multi-step dialogues).
     */
    private List<String> optionSequence = new ArrayList<>();

    /**
     * Current index in option sequence.
     */
    private int sequenceIndex = 0;

    /**
     * List of option resolvers for dynamic/complex dialogue selection.
     * Supports varbit-based, context-dependent, and pattern-matched options.
     */
    private List<DialogueOptionResolver> optionResolvers = new ArrayList<>();

    /**
     * Current index in resolver sequence.
     */
    private int resolverIndex = 0;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current dialogue handling phase.
     */
    private DialoguePhase phase = DialoguePhase.DETECT_DIALOGUE;

    /**
     * Number of continue clicks performed.
     */
    private int continueClicks = 0;

    /**
     * Whether a click is pending.
     */
    private boolean clickPending = false;

    /**
     * Ticks waiting for dialogue to change.
     */
    private int waitTicks = 0;

    /**
     * Last detected dialogue text (for change detection).
     */
    private String lastDialogueText = null;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a dialogue task.
     */
    public DialogueTask() {
        this.timeout = Duration.ofSeconds(60);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set option text to select (builder-style).
     *
     * @param text the option text (partial match)
     * @return this task for chaining
     */
    public DialogueTask withOptionText(String text) {
        this.optionText = text;
        return this;
    }

    /**
     * Set option index to select (builder-style).
     *
     * @param index the 1-based option index
     * @return this task for chaining
     */
    public DialogueTask withOptionIndex(int index) {
        this.optionIndex = index;
        return this;
    }

    /**
     * Set whether to click through all dialogue (builder-style).
     *
     * @param clickThrough true to click through
     * @return this task for chaining
     */
    public DialogueTask withClickThroughAll(boolean clickThrough) {
        this.clickThroughAll = clickThrough;
        return this;
    }

    /**
     * Add options to select in sequence (builder-style).
     *
     * @param options the options in order
     * @return this task for chaining
     */
    public DialogueTask withOptionSequence(String... options) {
        for (String opt : options) {
            this.optionSequence.add(opt);
        }
        return this;
    }

    /**
     * Add a dynamic option resolver (builder-style).
     * Resolvers are evaluated in order when selecting dialogue options.
     *
     * @param resolver the option resolver
     * @return this task for chaining
     */
    public DialogueTask withResolver(DialogueOptionResolver resolver) {
        this.optionResolvers.add(resolver);
        return this;
    }

    /**
     * Add multiple option resolvers (builder-style).
     *
     * @param resolvers the resolvers in order
     * @return this task for chaining
     */
    public DialogueTask withResolvers(DialogueOptionResolver... resolvers) {
        for (DialogueOptionResolver r : resolvers) {
            this.optionResolvers.add(r);
        }
        return this;
    }

    /**
     * Add a varbit-dependent option (builder-style).
     * Convenience method for common varbit-based dialogue selections.
     *
     * @param varbitId       the varbit ID to check
     * @param valueToOption  map of varbit values to option text
     * @return this task for chaining
     */
    public DialogueTask withVarbitOption(int varbitId, Map<Integer, String> valueToOption) {
        this.optionResolvers.add(DialogueOptionResolver.varbitBased(varbitId, valueToOption));
        return this;
    }

    /**
     * Add a regex pattern-matched option (builder-style).
     *
     * @param pattern the regex pattern to match
     * @return this task for chaining
     */
    public DialogueTask withPatternOption(String pattern) {
        this.optionResolvers.add(DialogueOptionResolver.pattern(pattern));
        return this;
    }

    /**
     * Add a regex pattern-matched option (builder-style).
     *
     * @param pattern the compiled regex pattern
     * @return this task for chaining
     */
    public DialogueTask withPatternOption(Pattern pattern) {
        this.optionResolvers.add(DialogueOptionResolver.pattern(pattern));
        return this;
    }

    /**
     * Add a context-dependent option (builder-style).
     * Only selects this option if previous dialogue contained the expected text.
     *
     * @param option               the option text to select
     * @param expectedPreviousLine text that must be in previous dialogue
     * @return this task for chaining
     */
    public DialogueTask withContextOption(String option, String expectedPreviousLine) {
        this.optionResolvers.add(
                DialogueOptionResolver.text(option).withExpectedPreviousLine(expectedPreviousLine)
        );
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param desc the description
     * @return this task for chaining
     */
    public DialogueTask withDescription(String desc) {
        this.description = desc;
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
    protected void resetImpl() {
        // Reset all execution state for retry
        phase = DialoguePhase.DETECT_DIALOGUE;
        continueClicks = 0;
        clickPending = false;
        waitTicks = 0;
        lastDialogueText = null;
        log.debug("DialogueTask reset for retry");
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (clickPending) {
            return;
        }

        switch (phase) {
            case DETECT_DIALOGUE:
                executeDetectDialogue(ctx);
                break;
            case CLICK_CONTINUE:
                executeClickContinue(ctx);
                break;
            case SELECT_OPTION:
                executeSelectOption(ctx);
                break;
            case WAIT_FOR_CHANGE:
                executeWaitForChange(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Detect Dialogue
    // ========================================================================

    private void executeDetectDialogue(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check for dialogue options first (highest priority)
        if (isWidgetVisible(client, WIDGET_DIALOGUE_OPTIONS)) {
            log.debug("Dialogue options detected");
            phase = DialoguePhase.SELECT_OPTION;
            return;
        }

        // Check for continue dialogue
        if (hasContinueOption(client)) {
            log.debug("Continue dialogue detected");
            phase = DialoguePhase.CLICK_CONTINUE;
            return;
        }

        // Check for level up dialogue
        if (isWidgetVisible(client, WIDGET_LEVEL_UP)) {
            log.debug("Level up dialogue detected");
            phase = DialoguePhase.CLICK_CONTINUE;
            return;
        }

        // No dialogue found - check if we should complete
        if (continueClicks > 0 || !optionSequence.isEmpty()) {
            // We've interacted with dialogue, and now it's gone
            log.info("Dialogue completed after {} continue clicks", continueClicks);
            complete();
            return;
        }

        // Waiting for dialogue to appear
        waitTicks++;
        if (waitTicks > 10) {
            log.warn("No dialogue detected after {} ticks", waitTicks);
            fail("No dialogue found");
        }
    }

    // ========================================================================
    // Phase: Press Space to Continue
    // ========================================================================

    private void executeClickContinue(TaskContext ctx) {
        Client client = ctx.getClient();

        // Verify continue is still available
        if (!hasContinueOption(client)) {
            phase = DialoguePhase.DETECT_DIALOGUE;
            return;
        }

        // Check continue limit
        if (continueClicks >= maxContinueClicks) {
            log.warn("Exceeded max continue presses ({})", maxContinueClicks);
            fail("Too many continue presses");
            return;
        }

        // Calculate reading delay using HumanTimer for proper humanization
        // HumanTimer.getDialogueDelay() provides:
        // - Gaussian distribution based on reading WPM
        // - Word count scaling
        // - Fatigue and attention modifiers from PlayerProfile
        String dialogueText = getDialogueText(client);
        int wordCount = countWords(dialogueText);
        long readingDelay = ctx.getHumanTimer().getDialogueDelay(wordCount);

        log.debug("Pressing SPACE to continue (delay: {}ms, words: {}, text: '{}')",
                readingDelay, wordCount, truncateText(dialogueText, 50));

        // Press spacebar to continue (like a real player)
        pressKeyWithDelay(ctx, KeyEvent.VK_SPACE, readingDelay);

        continueClicks++;
        lastDialogueText = dialogueText;
        phase = DialoguePhase.WAIT_FOR_CHANGE;
    }

    // ========================================================================
    // Phase: Select Option with Number Key
    // ========================================================================

    private void executeSelectOption(TaskContext ctx) {
        Client client = ctx.getClient();

        Widget optionsWidget = client.getWidget(WIDGET_DIALOGUE_OPTIONS, 1);
        if (optionsWidget == null) {
            // Options no longer visible
            phase = DialoguePhase.DETECT_DIALOGUE;
            return;
        }

        Widget[] children = optionsWidget.getDynamicChildren();
        if (children == null || children.length == 0) {
            log.warn("No dialogue options found");
            phase = DialoguePhase.DETECT_DIALOGUE;
            return;
        }

        // Build a list of available options for logging/debugging
        List<String> availableOptions = new ArrayList<>();
        for (Widget child : children) {
            if (child != null && child.getText() != null) {
                availableOptions.add(child.getText());
            }
        }
        log.debug("Available dialogue options: {}", availableOptions);

        int selectedIndex = -1;

        // Priority 1: Check resolvers (supports varbit, patterns, context-dependent)
        selectedIndex = resolveWithResolvers(ctx, children);

        // Priority 2: Check static option text
        if (selectedIndex < 0) {
            String targetOption = getNextOptionToSelect();
            if (targetOption != null) {
                selectedIndex = findOptionByText(children, targetOption);
            }
        }

        // Priority 3: Fall back to specified index
        // optionIndex is 1-based option number; widget 0 is title, so max valid is children.length - 1
        if (selectedIndex < 0 && optionIndex > 0 && optionIndex < children.length) {
            selectedIndex = optionIndex;
            log.debug("Using specified index: {}", selectedIndex);
        }

        // Last resort: first option
        if (selectedIndex < 0 && children.length > 0) {
            selectedIndex = 1;
            log.debug("Defaulting to first option");
        }

        if (selectedIndex < 1 || selectedIndex > 5) {
            log.warn("Invalid option index: {}", selectedIndex);
            phase = DialoguePhase.DETECT_DIALOGUE;
            return;
        }

        // STABILITY CHECK: Verify options haven't changed before pressing key
        // This prevents race condition where dialogue changes during processing
        Widget verifyWidget = client.getWidget(WIDGET_DIALOGUE_OPTIONS, 1);
        if (verifyWidget == null) {
            log.debug("Options widget disappeared before selection - retrying detection");
            phase = DialoguePhase.DETECT_DIALOGUE;
            return;
        }
        Widget[] verifyChildren = verifyWidget.getDynamicChildren();
        if (verifyChildren == null || verifyChildren.length != children.length) {
            log.debug("Options count changed ({} -> {}) - retrying detection", 
                    children.length, verifyChildren != null ? verifyChildren.length : 0);
            phase = DialoguePhase.DETECT_DIALOGUE;
            return;
        }
        // Verify first option text matches (quick sanity check)
        if (verifyChildren.length > 0 && children.length > 0) {
            String originalFirst = children[0] != null ? children[0].getText() : null;
            String currentFirst = verifyChildren[0] != null ? verifyChildren[0].getText() : null;
            if (originalFirst != null && !originalFirst.equals(currentFirst)) {
                log.debug("Options text changed ('{}' -> '{}') - retrying detection",
                        originalFirst, currentFirst);
                phase = DialoguePhase.DETECT_DIALOGUE;
                return;
            }
        }

        // Press the corresponding number key (1-5)
        int keyCode = KeyEvent.VK_1 + (selectedIndex - 1);
        log.debug("Pressing key '{}' to select option {}", selectedIndex, selectedIndex);
        
        // Use REACTION delay profile for option selection (Poisson λ=250ms)
        long selectionDelay = ctx.getHumanTimer().getDelay(DelayProfile.REACTION);
        pressKeyWithDelay(ctx, keyCode, selectionDelay);

        // Advance sequence/resolver indices
        advanceSequenceIndex();

        phase = DialoguePhase.WAIT_FOR_CHANGE;
    }

    /**
     * Attempt to resolve option using configured resolvers.
     *
     * @param ctx      task context
     * @param children option widgets
     * @return 1-based index of matched option, or -1 if no match
     */
    private int resolveWithResolvers(TaskContext ctx, Widget[] children) {
        if (optionResolvers.isEmpty()) {
            return -1;
        }

        // Get the current resolver (if following a sequence)
        DialogueOptionResolver currentResolver = getCurrentResolver();
        if (currentResolver != null) {
            int result = tryResolver(ctx, children, currentResolver);
            if (result > 0) {
                return result;
            }
        }

        // Try all resolvers that match context
        for (DialogueOptionResolver resolver : optionResolvers) {
            // Check context requirement
            if (!resolver.shouldApply(lastDialogueText)) {
                log.trace("Resolver {} skipped - context mismatch", resolver);
                continue;
            }

            // Check exclusions
            String visibleText = getVisibleOptionsText(children);
            if (resolver.shouldExclude(visibleText)) {
                log.trace("Resolver {} excluded", resolver);
                continue;
            }

            int result = tryResolver(ctx, children, resolver);
            if (result > 0) {
                return result;
            }
        }

        return -1;
    }

    /**
     * Try to match a single resolver against available options.
     */
    private int tryResolver(TaskContext ctx, Widget[] children, DialogueOptionResolver resolver) {
        // Handle index-based resolver (already 1-based, maps directly to key press)
        if (resolver.getType() == DialogueOptionResolver.ResolverType.INDEX) {
            int idx = resolver.getOptionIndex();
            // Index is 1-based option number (not widget index)
            // Widget 0 is title, Widget 1+ are options
            if (idx > 0 && idx < children.length) {
                log.debug("Resolver matched by index: {}", idx);
                return idx;
            }
            return -1;
        }

        // Search through options for a match
        // Start from index 1 - index 0 is the question/title text
        int optionNumber = 0;
        for (int i = 1; i < children.length; i++) {
            Widget child = children[i];
            if (child == null) continue;
            String text = child.getText();
            if (text == null || text.isEmpty()) continue;
            optionNumber++; // This is a valid selectable option
            if (resolver.matches(text, ctx)) {
                log.debug("Resolver {} matched option '{}' at position {}", resolver, text, optionNumber);
                return optionNumber; // 1-based option number for key press
            }
        }

        return -1;
    }

    /**
     * Find option by simple text matching.
     */
    private int findOptionByText(Widget[] children, String targetOption) {
        // Start from index 1 - index 0 is the question/title text, not a selectable option
        // Option indices are 1-based for key press mapping (key '1' = first option, etc.)
        int optionNumber = 0;
        for (int i = 1; i < children.length; i++) {
            Widget child = children[i];
            if (child == null) continue;
            String text = child.getText();
            if (text == null || text.isEmpty()) continue;
            optionNumber++; // This is a valid selectable option
            if (text.toLowerCase().contains(targetOption.toLowerCase())) {
                log.debug("Found matching option '{}' at position {} (widget index {})", text, optionNumber, i);
                return optionNumber; // 1-based option number for key press
            }
        }
        return -1;
    }

    /**
     * Get concatenated text of all visible options (for exclusion checking).
     */
    private String getVisibleOptionsText(Widget[] children) {
        StringBuilder sb = new StringBuilder();
        for (Widget child : children) {
            if (child != null && child.getText() != null) {
                sb.append(child.getText()).append(" ");
            }
        }
        return sb.toString();
    }

    /**
     * Get the current resolver if following a sequence.
     */
    private DialogueOptionResolver getCurrentResolver() {
        if (optionResolvers.isEmpty()) {
            return null;
        }
        // If we have multiple resolvers and are tracking sequence
        if (resolverIndex < optionResolvers.size()) {
            return optionResolvers.get(resolverIndex);
        }
        return null;
    }

    /**
     * Advance the sequence index after selecting an option.
     */
    private void advanceSequenceIndex() {
        if (!optionSequence.isEmpty() && sequenceIndex < optionSequence.size()) {
            sequenceIndex++;
        }
        if (!optionResolvers.isEmpty() && resolverIndex < optionResolvers.size()) {
            resolverIndex++;
        }
    }

    // ========================================================================
    // Phase: Wait For Change
    // ========================================================================

    private void executeWaitForChange(TaskContext ctx) {
        waitTicks++;

        if (waitTicks > 5) {
            // Waited enough, check dialogue state again
            waitTicks = 0;
            phase = DialoguePhase.DETECT_DIALOGUE;
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private boolean isWidgetVisible(Client client, int groupId) {
        Widget widget = client.getWidget(groupId, 0);
        return widget != null && !widget.isHidden();
    }

    private boolean hasContinueOption(Client client) {
        return isWidgetVisible(client, WIDGET_NPC_DIALOGUE)
                || isWidgetVisible(client, WIDGET_PLAYER_DIALOGUE)
                || isWidgetVisible(client, WIDGET_CONTINUE)
                || isWidgetVisible(client, WIDGET_DIALOGUE_GENERIC)
                || isWidgetVisible(client, WIDGET_SPRITE_DIALOGUE)
                || isWidgetVisible(client, WIDGET_LEVEL_UP);
    }

    private String getDialogueText(Client client) {
        Widget npcText = client.getWidget(WIDGET_NPC_DIALOGUE, CHILD_NPC_TEXT);
        if (npcText != null && !npcText.isHidden()) {
            return npcText.getText();
        }

        Widget playerText = client.getWidget(WIDGET_PLAYER_DIALOGUE, CHILD_NPC_TEXT);
        if (playerText != null && !playerText.isHidden()) {
            return playerText.getText();
        }

        return "";
    }

    private String getNextOptionToSelect() {
        if (!optionSequence.isEmpty() && sequenceIndex < optionSequence.size()) {
            return optionSequence.get(sequenceIndex);
        }
        return optionText;
    }

    /**
     * Count words in text for dialogue delay calculation.
     */
    private int countWords(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\\s+").length;
    }

    /**
     * Press a key with a humanized pre-delay.
     * This is how real players handle dialogue - spacebar for continue, numbers for options.
     * Uses HumanTimer for proper delay distribution with fatigue/attention modifiers.
     */
    private void pressKeyWithDelay(TaskContext ctx, int keyCode, long delayMs) {
        clickPending = true;

        // Use HumanTimer.sleep() for proper async sleep with humanized timing
        ctx.getHumanTimer().sleep(delayMs)
                .thenCompose(v -> ctx.getKeyboardController().pressKey(keyCode))
          .thenRun(() -> {
              clickPending = false;
              waitTicks = 0;
          })
          .exceptionally(e -> {
              clickPending = false;
              log.error("Dialogue key press failed", e);
              return null;
          });
    }

    private String truncateText(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        if (optionText != null) {
            return "DialogueTask[select: " + optionText + "]";
        }
        if (optionIndex > 0) {
            return "DialogueTask[select index: " + optionIndex + "]";
        }
        return "DialogueTask[click through]";
    }

    // ========================================================================
    // Dialogue Phase Enum
    // ========================================================================

    private enum DialoguePhase {
        DETECT_DIALOGUE,
        CLICK_CONTINUE,
        SELECT_OPTION,
        WAIT_FOR_CHANGE
    }
}

