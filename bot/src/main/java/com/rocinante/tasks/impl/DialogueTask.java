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
import java.util.concurrent.CompletableFuture;

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

        // Determine which option to select
        String targetOption = getNextOptionToSelect();
        int targetIndex = optionIndex;
        int selectedIndex = -1;

        // Search by text to find the index
        if (targetOption != null) {
            for (int i = 0; i < children.length; i++) {
                Widget child = children[i];
                if (child == null) continue;
                String text = child.getText();
                if (text != null && text.toLowerCase().contains(targetOption.toLowerCase())) {
                    selectedIndex = i + 1; // 1-based
                    log.debug("Found matching option at index {}: '{}'", selectedIndex, text);
                    break;
                }
            }
        }

        // Fall back to specified index
        if (selectedIndex < 0 && targetIndex > 0 && targetIndex <= children.length) {
            selectedIndex = targetIndex;
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

        // Press the corresponding number key (1-5)
        int keyCode = KeyEvent.VK_1 + (selectedIndex - 1);
        log.debug("Pressing key '{}' to select option {}", selectedIndex, selectedIndex);
        
        // Use REACTION delay profile for option selection (Poisson λ=250ms)
        long selectionDelay = ctx.getHumanTimer().getDelay(DelayProfile.REACTION);
        pressKeyWithDelay(ctx, keyCode, selectionDelay);

        // Advance sequence if applicable
        if (!optionSequence.isEmpty() && sequenceIndex < optionSequence.size()) {
            sequenceIndex++;
        }

        phase = DialoguePhase.WAIT_FOR_CHANGE;
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
                || isWidgetVisible(client, WIDGET_SPRITE_DIALOGUE);
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

