package com.rocinante.quest.conditions;

import com.rocinante.state.StateCondition;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;

/**
 * Condition that checks varbit values.
 *
 * Varbits are client-side variables used to track game state, including quest progress.
 * This mirrors Quest Helper's VarbitRequirement pattern but integrates with our
 * StateCondition system for composability.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Tutorial Island progress check (varbit 281)
 * StateCondition talkedToGuide = VarbitCondition.equals(281, 2);
 * StateCondition pastSurvivalExpert = VarbitCondition.greaterThanOrEqual(281, 120);
 *
 * // Combine with other conditions
 * StateCondition readyForMining = pastSurvivalExpert
 *     .and(Conditions.playerInArea(...));
 * }</pre>
 */
public class VarbitCondition implements StateCondition {

    /**
     * Comparison operations for varbit checks.
     */
    public enum Operation {
        EQUALS,
        NOT_EQUALS,
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL,
        LESS_THAN,
        LESS_THAN_OR_EQUAL,
        IN_RANGE,
        /** Check if value matches any in a set */
        IN_SET
    }

    @Getter
    private final int varbitId;

    @Getter
    private final Operation operation;

    @Getter
    private final int expectedValue;

    /** Secondary value for range checks */
    private final int secondaryValue;

    /** Set of values for IN_SET operation */
    private final int[] valueSet;

    private VarbitCondition(int varbitId, Operation operation, int expectedValue, int secondaryValue, int[] valueSet) {
        this.varbitId = varbitId;
        this.operation = operation;
        this.expectedValue = expectedValue;
        this.secondaryValue = secondaryValue;
        this.valueSet = valueSet;
    }

    @Override
    public boolean test(TaskContext ctx) {
        int actualValue = ctx.getClient().getVarbitValue(varbitId);

        switch (operation) {
            case EQUALS:
                return actualValue == expectedValue;
            case NOT_EQUALS:
                return actualValue != expectedValue;
            case GREATER_THAN:
                return actualValue > expectedValue;
            case GREATER_THAN_OR_EQUAL:
                return actualValue >= expectedValue;
            case LESS_THAN:
                return actualValue < expectedValue;
            case LESS_THAN_OR_EQUAL:
                return actualValue <= expectedValue;
            case IN_RANGE:
                return actualValue >= expectedValue && actualValue <= secondaryValue;
            case IN_SET:
                if (valueSet != null) {
                    for (int v : valueSet) {
                        if (actualValue == v) {
                            return true;
                        }
                    }
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public String describe() {
        switch (operation) {
            case EQUALS:
                return String.format("varbit[%d] == %d", varbitId, expectedValue);
            case NOT_EQUALS:
                return String.format("varbit[%d] != %d", varbitId, expectedValue);
            case GREATER_THAN:
                return String.format("varbit[%d] > %d", varbitId, expectedValue);
            case GREATER_THAN_OR_EQUAL:
                return String.format("varbit[%d] >= %d", varbitId, expectedValue);
            case LESS_THAN:
                return String.format("varbit[%d] < %d", varbitId, expectedValue);
            case LESS_THAN_OR_EQUAL:
                return String.format("varbit[%d] <= %d", varbitId, expectedValue);
            case IN_RANGE:
                return String.format("varbit[%d] in [%d, %d]", varbitId, expectedValue, secondaryValue);
            case IN_SET:
                return String.format("varbit[%d] in set", varbitId);
            default:
                return String.format("varbit[%d] ? %d", varbitId, expectedValue);
        }
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Check if varbit equals a specific value.
     *
     * @param varbitId the varbit ID to check
     * @param value    the expected value
     * @return condition checking equality
     */
    public static VarbitCondition equals(int varbitId, int value) {
        return new VarbitCondition(varbitId, Operation.EQUALS, value, 0, null);
    }

    /**
     * Check if varbit does not equal a specific value.
     *
     * @param varbitId the varbit ID to check
     * @param value    the value that should not be present
     * @return condition checking inequality
     */
    public static VarbitCondition notEquals(int varbitId, int value) {
        return new VarbitCondition(varbitId, Operation.NOT_EQUALS, value, 0, null);
    }

    /**
     * Check if varbit is greater than a value.
     *
     * @param varbitId the varbit ID to check
     * @param value    the threshold value
     * @return condition checking greater than
     */
    public static VarbitCondition greaterThan(int varbitId, int value) {
        return new VarbitCondition(varbitId, Operation.GREATER_THAN, value, 0, null);
    }

    /**
     * Check if varbit is greater than or equal to a value.
     *
     * @param varbitId the varbit ID to check
     * @param value    the threshold value
     * @return condition checking greater than or equal
     */
    public static VarbitCondition greaterThanOrEqual(int varbitId, int value) {
        return new VarbitCondition(varbitId, Operation.GREATER_THAN_OR_EQUAL, value, 0, null);
    }

    /**
     * Check if varbit is less than a value.
     *
     * @param varbitId the varbit ID to check
     * @param value    the threshold value
     * @return condition checking less than
     */
    public static VarbitCondition lessThan(int varbitId, int value) {
        return new VarbitCondition(varbitId, Operation.LESS_THAN, value, 0, null);
    }

    /**
     * Check if varbit is less than or equal to a value.
     *
     * @param varbitId the varbit ID to check
     * @param value    the threshold value
     * @return condition checking less than or equal
     */
    public static VarbitCondition lessThanOrEqual(int varbitId, int value) {
        return new VarbitCondition(varbitId, Operation.LESS_THAN_OR_EQUAL, value, 0, null);
    }

    /**
     * Check if varbit is within a range (inclusive).
     *
     * @param varbitId the varbit ID to check
     * @param minValue minimum value (inclusive)
     * @param maxValue maximum value (inclusive)
     * @return condition checking range membership
     */
    public static VarbitCondition inRange(int varbitId, int minValue, int maxValue) {
        return new VarbitCondition(varbitId, Operation.IN_RANGE, minValue, maxValue, null);
    }

    /**
     * Check if varbit equals any value in a set.
     *
     * @param varbitId the varbit ID to check
     * @param values   the set of valid values
     * @return condition checking set membership
     */
    public static VarbitCondition inSet(int varbitId, int... values) {
        return new VarbitCondition(varbitId, Operation.IN_SET, 0, 0, values);
    }
}

