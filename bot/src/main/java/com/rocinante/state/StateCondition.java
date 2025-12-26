package com.rocinante.state;

import com.rocinante.tasks.TaskContext;

/**
 * Functional interface for composable state predicates.
 *
 * Per REQUIREMENTS.md Section 6.3, StateConditions are used for:
 * <ul>
 *   <li>Task gating (checking preconditions)</li>
 *   <li>Conditional task branching</li>
 *   <li>Wait conditions</li>
 *   <li>Combat thresholds</li>
 * </ul>
 *
 * <p>Conditions are composable via {@link #and(StateCondition)},
 * {@link #or(StateCondition)}, and {@link #not()} methods.
 *
 * <p>Example usage:
 * <pre>{@code
 * StateCondition canFight = Conditions.healthAbove(0.5)
 *     .and(Conditions.hasItem(ItemID.SHARK, 5))
 *     .and(Conditions.playerIsIdle());
 *
 * if (canFight.test(taskContext)) {
 *     // Start combat
 * }
 * }</pre>
 */
@FunctionalInterface
public interface StateCondition {

    /**
     * Evaluate this condition against the current game state.
     *
     * @param ctx the task context providing game state
     * @return true if the condition is satisfied
     */
    boolean test(TaskContext ctx);

    /**
     * Create a condition that requires both this AND another condition.
     *
     * @param other the other condition
     * @return a new combined condition
     */
    default StateCondition and(StateCondition other) {
        return ctx -> this.test(ctx) && other.test(ctx);
    }

    /**
     * Create a condition that requires either this OR another condition.
     *
     * @param other the other condition
     * @return a new combined condition
     */
    default StateCondition or(StateCondition other) {
        return ctx -> this.test(ctx) || other.test(ctx);
    }

    /**
     * Create the negation of this condition.
     *
     * @return a new negated condition
     */
    default StateCondition not() {
        return ctx -> !this.test(ctx);
    }

    /**
     * Create a condition that always returns true.
     *
     * @return always-true condition
     */
    static StateCondition always() {
        return ctx -> true;
    }

    /**
     * Create a condition that always returns false.
     *
     * @return always-false condition
     */
    static StateCondition never() {
        return ctx -> false;
    }

    /**
     * Create a condition from multiple conditions with AND logic.
     *
     * @param conditions the conditions to combine
     * @return combined condition requiring all to be true
     */
    static StateCondition allOf(StateCondition... conditions) {
        return ctx -> {
            for (StateCondition c : conditions) {
                if (!c.test(ctx)) {
                    return false;
                }
            }
            return true;
        };
    }

    /**
     * Create a condition from multiple conditions with OR logic.
     *
     * @param conditions the conditions to combine
     * @return combined condition requiring any to be true
     */
    static StateCondition anyOf(StateCondition... conditions) {
        return ctx -> {
            for (StateCondition c : conditions) {
                if (c.test(ctx)) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * Create a condition that requires exactly N of the given conditions to be true.
     *
     * @param n          the required count
     * @param conditions the conditions to check
     * @return condition requiring exactly N to be true
     */
    static StateCondition exactlyN(int n, StateCondition... conditions) {
        return ctx -> {
            int count = 0;
            for (StateCondition c : conditions) {
                if (c.test(ctx)) {
                    count++;
                }
            }
            return count == n;
        };
    }

    /**
     * Create a condition that requires at least N of the given conditions to be true.
     *
     * @param n          the minimum count
     * @param conditions the conditions to check
     * @return condition requiring at least N to be true
     */
    static StateCondition atLeastN(int n, StateCondition... conditions) {
        return ctx -> {
            int count = 0;
            for (StateCondition c : conditions) {
                if (c.test(ctx)) {
                    count++;
                    if (count >= n) {
                        return true;
                    }
                }
            }
            return false;
        };
    }

    /**
     * Get a human-readable description of this condition.
     * Default implementation returns the class name.
     *
     * @return description string
     */
    default String describe() {
        return getClass().getSimpleName();
    }
}

