package com.rocinante.tasks.impl;

import com.rocinante.tasks.TaskContext;
import lombok.Getter;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Resolves dialogue options dynamically based on game state.
 *
 * <p>Quest Helper supports multiple ways to determine the correct dialogue option:
 * <ul>
 *   <li>Simple text match</li>
 *   <li>Regex pattern match</li>
 *   <li>Index-based selection</li>
 *   <li>Varbit-dependent selection (different options based on game state)</li>
 *   <li>Context-dependent selection (based on previous dialogue text)</li>
 * </ul>
 *
 * <p>This class encapsulates all these resolution strategies and allows
 * {@link DialogueTask} to evaluate the correct option at runtime.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Simple text match
 * DialogueOptionResolver simple = DialogueOptionResolver.text("Yes.");
 *
 * // Regex pattern
 * DialogueOptionResolver regex = DialogueOptionResolver.pattern(".*agree.*");
 *
 * // Varbit-dependent
 * DialogueOptionResolver varbitBased = DialogueOptionResolver.varbitBased(1234, Map.of(
 *     0, "Option for varbit=0",
 *     1, "Option for varbit=1"
 * ));
 *
 * // Context-dependent (only apply if previous dialogue contained text)
 * DialogueOptionResolver contextual = DialogueOptionResolver.text("Yes.")
 *     .withExpectedPreviousLine("Do you agree?");
 *
 * // Custom resolver
 * DialogueOptionResolver custom = DialogueOptionResolver.custom(ctx ->
 *     ctx.getClient().getVarbitValue(123) > 5 ? "High value" : "Low value"
 * );
 * }</pre>
 */
public class DialogueOptionResolver {

    /**
     * Resolution strategy type.
     */
    public enum ResolverType {
        TEXT,       // Exact text match
        PATTERN,    // Regex pattern match
        INDEX,      // Select by index
        VARBIT,     // Varbit-dependent selection
        CUSTOM      // Custom resolver function
    }

    @Getter
    private final ResolverType type;

    /**
     * Option text for TEXT type.
     */
    @Getter
    private final String optionText;

    /**
     * Regex pattern for PATTERN type.
     */
    @Getter
    private final Pattern pattern;

    /**
     * Option index for INDEX type (1-based).
     */
    @Getter
    private final int optionIndex;

    /**
     * Varbit ID for VARBIT type.
     */
    @Getter
    private final int varbitId;

    /**
     * Map of varbit value to option text for VARBIT type.
     */
    @Getter
    private final Map<Integer, String> varbitValueToOption;

    /**
     * Custom resolver function for CUSTOM type.
     */
    private final Function<TaskContext, String> customResolver;

    /**
     * Expected previous dialogue line for context-dependent selection.
     * If set, this resolver only applies when the previous dialogue contains this text.
     */
    @Getter
    private String expectedPreviousLine;

    /**
     * Exclusion text - don't apply this resolver if this text is visible.
     */
    @Getter
    private String exclusionText;

    // ========================================================================
    // Constructors (private - use static factory methods)
    // ========================================================================

    private DialogueOptionResolver(ResolverType type, String optionText, Pattern pattern,
                                   int optionIndex, int varbitId,
                                   Map<Integer, String> varbitValueToOption,
                                   Function<TaskContext, String> customResolver) {
        this.type = type;
        this.optionText = optionText;
        this.pattern = pattern;
        this.optionIndex = optionIndex;
        this.varbitId = varbitId;
        this.varbitValueToOption = varbitValueToOption;
        this.customResolver = customResolver;
    }

    // ========================================================================
    // Static Factory Methods
    // ========================================================================

    /**
     * Create a resolver for exact text match.
     *
     * @param text the option text to match
     * @return the resolver
     */
    public static DialogueOptionResolver text(String text) {
        return new DialogueOptionResolver(ResolverType.TEXT, text, null, -1, -1, null, null);
    }

    /**
     * Create a resolver for regex pattern match.
     *
     * @param regex the regex pattern string
     * @return the resolver
     */
    public static DialogueOptionResolver pattern(String regex) {
        return new DialogueOptionResolver(ResolverType.PATTERN, null, Pattern.compile(regex), -1, -1, null, null);
    }

    /**
     * Create a resolver for regex pattern match.
     *
     * @param pattern the compiled pattern
     * @return the resolver
     */
    public static DialogueOptionResolver pattern(Pattern pattern) {
        return new DialogueOptionResolver(ResolverType.PATTERN, null, pattern, -1, -1, null, null);
    }

    /**
     * Create a resolver for index-based selection.
     *
     * @param index the 1-based option index
     * @return the resolver
     */
    public static DialogueOptionResolver index(int index) {
        return new DialogueOptionResolver(ResolverType.INDEX, null, null, index, -1, null, null);
    }

    /**
     * Create a resolver for varbit-dependent selection.
     *
     * @param varbitId          the varbit ID to check
     * @param valueToOptionMap  map of varbit values to option text
     * @return the resolver
     */
    public static DialogueOptionResolver varbitBased(int varbitId, Map<Integer, String> valueToOptionMap) {
        return new DialogueOptionResolver(ResolverType.VARBIT, null, null, -1, varbitId, valueToOptionMap, null);
    }

    /**
     * Create a resolver with a custom function.
     *
     * @param resolver function that takes TaskContext and returns the option text
     * @return the resolver
     */
    public static DialogueOptionResolver custom(Function<TaskContext, String> resolver) {
        return new DialogueOptionResolver(ResolverType.CUSTOM, null, null, -1, -1, null, resolver);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set the expected previous dialogue line for context-dependent selection.
     *
     * @param previousLine text that must be in the previous dialogue
     * @return this resolver for chaining
     */
    public DialogueOptionResolver withExpectedPreviousLine(String previousLine) {
        this.expectedPreviousLine = previousLine;
        return this;
    }

    /**
     * Set exclusion text - don't apply if this text is visible.
     *
     * @param exclusion text to exclude on
     * @return this resolver for chaining
     */
    public DialogueOptionResolver withExclusion(String exclusion) {
        this.exclusionText = exclusion;
        return this;
    }

    // ========================================================================
    // Resolution Methods
    // ========================================================================

    /**
     * Check if this resolver should be applied given the previous dialogue text.
     *
     * @param previousDialogue the text from the previous dialogue
     * @return true if this resolver should be applied
     */
    public boolean shouldApply(String previousDialogue) {
        // If no context requirement, always apply
        if (expectedPreviousLine == null || expectedPreviousLine.isEmpty()) {
            return true;
        }
        // Check if previous dialogue contains the expected text
        return previousDialogue != null &&
               previousDialogue.toLowerCase().contains(expectedPreviousLine.toLowerCase());
    }

    /**
     * Check if this resolver should be excluded given visible text.
     *
     * @param visibleText text currently visible
     * @return true if this resolver should be excluded
     */
    public boolean shouldExclude(String visibleText) {
        if (exclusionText == null || exclusionText.isEmpty()) {
            return false;
        }
        return visibleText != null &&
               visibleText.toLowerCase().contains(exclusionText.toLowerCase());
    }

    /**
     * Resolve the option text to select based on current game state.
     *
     * @param ctx the task context
     * @return the option text to select, or null if cannot resolve
     */
    public String resolve(TaskContext ctx) {
        switch (type) {
            case TEXT:
                return optionText;

            case PATTERN:
                // Pattern matching is handled in DialogueTask during option search
                return null;

            case INDEX:
                // Index is used directly, no text returned
                return null;

            case VARBIT:
                if (varbitValueToOption != null) {
                    int currentValue = ctx.getClient().getVarbitValue(varbitId);
                    return varbitValueToOption.get(currentValue);
                }
                return null;

            case CUSTOM:
                if (customResolver != null) {
                    return customResolver.apply(ctx);
                }
                return null;

            default:
                return null;
        }
    }

    /**
     * Check if an option widget text matches this resolver.
     *
     * @param optionWidgetText the text from an option widget
     * @param ctx             the task context (for varbit resolution)
     * @return true if this option matches
     */
    public boolean matches(String optionWidgetText, TaskContext ctx) {
        if (optionWidgetText == null) {
            return false;
        }

        switch (type) {
            case TEXT:
                return optionText != null &&
                       optionWidgetText.toLowerCase().contains(optionText.toLowerCase());

            case PATTERN:
                return pattern != null &&
                       pattern.matcher(optionWidgetText).find();

            case INDEX:
                // Index matching is handled separately
                return false;

            case VARBIT:
                String resolved = resolve(ctx);
                return resolved != null &&
                       optionWidgetText.toLowerCase().contains(resolved.toLowerCase());

            case CUSTOM:
                String customResolved = resolve(ctx);
                return customResolved != null &&
                       optionWidgetText.toLowerCase().contains(customResolved.toLowerCase());

            default:
                return false;
        }
    }

    @Override
    public String toString() {
        switch (type) {
            case TEXT:
                return "DialogueOptionResolver[TEXT: " + optionText + "]";
            case PATTERN:
                return "DialogueOptionResolver[PATTERN: " + pattern.pattern() + "]";
            case INDEX:
                return "DialogueOptionResolver[INDEX: " + optionIndex + "]";
            case VARBIT:
                return "DialogueOptionResolver[VARBIT: " + varbitId + " -> " + varbitValueToOption + "]";
            case CUSTOM:
                return "DialogueOptionResolver[CUSTOM]";
            default:
                return "DialogueOptionResolver[UNKNOWN]";
        }
    }
}

