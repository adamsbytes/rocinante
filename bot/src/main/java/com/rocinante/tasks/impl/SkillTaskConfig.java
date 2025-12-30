package com.rocinante.tasks.impl;

import com.rocinante.inventory.IdealInventory;
import com.rocinante.inventory.IdealInventoryFactory;
import com.rocinante.progression.TrainingMethod;
import com.rocinante.state.PlayerState;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.Skill;

import javax.annotation.Nullable;
import java.time.Duration;

/**
 * Configuration for a skill training session.
 *
 * <p>Defines the parameters for a {@link SkillTask} including target level/XP,
 * training method, behavior overrides, and session limits.
 *
 * <p>Example configurations:
 * <pre>{@code
 * // Train mining to level 50 using iron power mining
 * SkillTaskConfig miningConfig = SkillTaskConfig.builder()
 *     .skill(Skill.MINING)
 *     .targetLevel(50)
 *     .method(trainingMethodRepo.getMethodById("iron_ore_powermine").get())
 *     .build();
 *
 * // Train woodcutting for 2 hours with banking
 * SkillTaskConfig wcConfig = SkillTaskConfig.builder()
 *     .skill(Skill.WOODCUTTING)
 *     .targetLevel(99)
 *     .method(trainingMethodRepo.getMethodById("willow_trees_banking").get())
 *     .maxDuration(Duration.ofHours(2))
 *     .bankInsteadOfDrop(true)
 *     .build();
 * }</pre>
 */
@Value
@Builder(toBuilder = true)
public class SkillTaskConfig {

    // ========================================================================
    // Target Configuration
    // ========================================================================

    /**
     * The skill to train.
     */
    Skill skill;

    /**
     * Target skill level to achieve (1-99).
     * Training stops when this level is reached.
     * Set to -1 to use targetXp instead.
     */
    @Builder.Default
    int targetLevel = -1;

    /**
     * Target XP amount to achieve.
     * Alternative to targetLevel - training stops when total XP reaches this.
     * Set to -1 to use targetLevel instead.
     */
    @Builder.Default
    long targetXp = -1;

    /**
     * The training method to use.
     */
    TrainingMethod method;

    /**
     * Specific location ID within the method to use.
     * If null, the first/default location is used.
     */
    String locationId;

    // ========================================================================
    // Behavior Overrides
    // ========================================================================

    /**
     * Override method's dropWhenFull setting.
     * If true, bank products instead of dropping.
     * If null, use method's default behavior.
     */
    Boolean bankInsteadOfDrop;

    /**
     * Minimum food count to maintain (for dangerous training).
     * If food drops below this, task will restock.
     * Set to 0 for non-dangerous training.
     */
    @Builder.Default
    int minFoodCount = 0;

    /**
     * Food item ID to use for restocking.
     * Only relevant if minFoodCount > 0.
     */
    @Builder.Default
    int foodItemId = -1;

    // ========================================================================
    // Session Limits
    // ========================================================================

    /**
     * Maximum duration for this training session.
     * Training stops after this duration regardless of progress.
     * Set to null for no time limit.
     */
    Duration maxDuration;

    /**
     * Maximum number of actions to perform.
     * Set to 0 for unlimited.
     */
    @Builder.Default
    int maxActions = 0;

    // ========================================================================
    // World Hopping
    // ========================================================================

    /**
     * Whether to world hop if the training spot is crowded.
     */
    @Builder.Default
    boolean useWorldHopping = false;

    /**
     * Number of players at spot that triggers world hop.
     * Only relevant if useWorldHopping is true.
     */
    @Builder.Default
    int worldHopThreshold = 2;

    // ========================================================================
    // Return Behavior
    // ========================================================================

    /**
     * Whether to return to the exact tile after banking.
     * If false, just returns to the general training area.
     */
    @Builder.Default
    boolean returnToExactSpot = true;

    // ========================================================================
    // Ideal Inventory
    // ========================================================================

    /**
     * Explicit ideal inventory specification for this task.
     *
     * <p>If set, this specification is used directly to prepare the inventory
     * before training begins. If null, the ideal inventory is automatically
     * derived from the {@link #method} using {@link IdealInventoryFactory}.
     *
     * <p>Use cases for explicit specification:
     * <ul>
     *   <li>Override automatic tool selection (force specific axe)</li>
     *   <li>Add extra items not inferred from method (teleports, supplies)</li>
     *   <li>Skip preparation entirely (set to {@link IdealInventory#none()})</li>
     * </ul>
     *
     * @see IdealInventory
     * @see IdealInventoryFactory#fromTrainingMethod
     */
    @Nullable
    IdealInventory idealInventory;

    /**
     * Whether to skip automatic inventory preparation.
     *
     * <p>When true, the task will NOT prepare inventory automatically,
     * assuming the caller (planner) has already prepared it.
     *
     * <p>This is a convenience flag - equivalent to setting
     * {@code idealInventory = IdealInventory.none()}.
     */
    @Builder.Default
    boolean skipInventoryPreparation = false;

    // ========================================================================
    // Validation
    // ========================================================================

    /**
     * Validate that this configuration is valid.
     *
     * @throws IllegalStateException if configuration is invalid
     */
    public void validate() {
        if (skill == null) {
            throw new IllegalStateException("Skill must be specified");
        }
        if (method == null) {
            throw new IllegalStateException("Training method must be specified");
        }
        if (method.getSkill() != skill) {
            throw new IllegalStateException(
                    String.format("Method skill (%s) does not match config skill (%s)",
                            method.getSkill(), skill));
        }
        if (targetLevel < 0 && targetXp < 0) {
            throw new IllegalStateException("Either targetLevel or targetXp must be specified");
        }
        if (targetLevel > 0 && (targetLevel < 1 || targetLevel > 99)) {
            throw new IllegalStateException("Target level must be 1-99");
        }
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if this config uses banking (either method default or override).
     *
     * @return true if banking should be used
     */
    public boolean shouldBank() {
        if (bankInsteadOfDrop != null) {
            return bankInsteadOfDrop;
        }
        return method.requiresBanking();
    }

    /**
     * Check if this config uses dropping (power training).
     *
     * @return true if dropping should be used
     */
    public boolean shouldDrop() {
        if (bankInsteadOfDrop != null) {
            return !bankInsteadOfDrop;
        }
        return method.isDropWhenFull();
    }

    /**
     * Check if this training requires food.
     *
     * @return true if food management is enabled
     */
    public boolean requiresFood() {
        return minFoodCount > 0 && foodItemId > 0;
    }

    /**
     * Check if there's a time limit.
     *
     * @return true if maxDuration is set
     */
    public boolean hasTimeLimit() {
        return maxDuration != null && !maxDuration.isZero();
    }

    /**
     * Check if there's an action limit.
     *
     * @return true if maxActions > 0
     */
    public boolean hasActionLimit() {
        return maxActions > 0;
    }

    /**
     * Get the ideal inventory for this task.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If {@link #skipInventoryPreparation} is true, returns {@link IdealInventory#none()}</li>
     *   <li>If {@link #idealInventory} is explicitly set, returns that</li>
     *   <li>Otherwise, derives from {@link #method} using {@link IdealInventoryFactory}</li>
     * </ol>
     *
     * @param player the current player state (for level-based tool selection)
     * @return the ideal inventory specification
     */
    public IdealInventory getOrCreateIdealInventory(@Nullable PlayerState player) {
        // Skip preparation if requested
        if (skipInventoryPreparation) {
            return IdealInventory.none();
        }

        // Use explicit specification if provided
        if (idealInventory != null) {
            return idealInventory;
        }

        // Derive from training method
        if (method != null) {
            return IdealInventoryFactory.fromTrainingMethod(method, player);
        }

        // Fallback: empty inventory
        return IdealInventory.emptyInventory();
    }

    /**
     * Check if this task has custom inventory preparation.
     *
     * @return true if idealInventory is explicitly set
     */
    public boolean hasCustomIdealInventory() {
        return idealInventory != null;
    }

    /**
     * Get a summary string for logging.
     *
     * @return human-readable summary
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(skill.getName());

        if (targetLevel > 0) {
            sb.append(" to level ").append(targetLevel);
        } else if (targetXp > 0) {
            sb.append(" to ").append(String.format("%,d", targetXp)).append(" XP");
        }

        sb.append(" via ").append(method.getName());

        if (hasTimeLimit()) {
            sb.append(" (max ").append(maxDuration.toMinutes()).append(" min)");
        }

        return sb.toString();
    }

    // ========================================================================
    // Static Builders
    // ========================================================================

    /**
     * Create a simple config for training to a target level.
     *
     * @param skill       the skill to train
     * @param targetLevel the target level
     * @param method      the training method
     * @return new config
     */
    public static SkillTaskConfig forLevel(Skill skill, int targetLevel, TrainingMethod method) {
        return builder()
                .skill(skill)
                .targetLevel(targetLevel)
                .method(method)
                .build();
    }

    /**
     * Create a simple config for training to a target XP.
     *
     * @param skill    the skill to train
     * @param targetXp the target XP
     * @param method   the training method
     * @return new config
     */
    public static SkillTaskConfig forXp(Skill skill, long targetXp, TrainingMethod method) {
        return builder()
                .skill(skill)
                .targetXp(targetXp)
                .method(method)
                .build();
    }

    /**
     * Create a config for time-limited training.
     *
     * @param skill    the skill to train
     * @param duration the maximum duration
     * @param method   the training method
     * @return new config
     */
    public static SkillTaskConfig forDuration(Skill skill, Duration duration, TrainingMethod method) {
        return builder()
                .skill(skill)
                .targetLevel(99) // Train indefinitely until time limit
                .method(method)
                .maxDuration(duration)
                .build();
    }
}

