package com.rocinante.tasks.impl.skills.cooking;

import com.rocinante.progression.MethodLocation;
import com.rocinante.progression.TrainingMethod;
import lombok.Builder;
import lombok.Value;

import java.time.Duration;

/**
 * Configuration for a cooking training session.
 *
 * <p>Defines parameters for {@link CookingSkillTask} including target levels,
 * cooking method, and drop/bank behavior.
 *
 * <p>Example usage:
 * <pre>{@code
 * TrainingMethod method = methodRepo.getMethodById("shrimp_cooking").get();
 * CookingSkillTaskConfig config = CookingSkillTaskConfig.builder()
 *     .method(method)
 *     .locationId("lumbridge")
 *     .productHandling(ProductHandling.DROP_ALL)
 *     .targetLevel(30)
 *     .build();
 * }</pre>
 */
@Value
@Builder(toBuilder = true)
public class CookingSkillTaskConfig {

    /**
     * The cooking training method from training_methods.json.
     * Contains source item (raw food), output item, animations, etc.
     */
    TrainingMethod method;

    /**
     * Location ID within the method (e.g., "lumbridge", "rogues_den").
     * If null, uses the method's default location.
     */
    String locationId;

    /**
     * How to handle cooked/burnt products.
     */
    @Builder.Default
    ProductHandling productHandling = ProductHandling.DROP_ALL;

    /**
     * Target cooking level to achieve (1-99).
     * Training stops when this level is reached.
     * Set to -1 to use targetXp instead.
     */
    @Builder.Default
    int targetLevel = -1;

    /**
     * Target XP amount to achieve.
     * Alternative to targetLevel.
     * Set to -1 to use targetLevel instead.
     */
    @Builder.Default
    long targetXp = -1;

    /**
     * Maximum duration for this training session.
     * Training stops after this duration regardless of progress.
     */
    Duration maxDuration;

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Get the selected location from the method.
     *
     * @return the location, or default if not specified
     */
    public MethodLocation getLocation() {
        if (locationId != null && method != null) {
            MethodLocation loc = method.getLocation(locationId);
            if (loc != null) {
                return loc;
            }
        }
        return method != null ? method.getDefaultLocation() : null;
    }

    /**
     * Validate configuration.
     *
     * @throws IllegalStateException if configuration is invalid
     */
    public void validate() {
        if (method == null) {
            throw new IllegalStateException("Training method must be specified");
        }
        if (targetLevel < 0 && targetXp < 0) {
            throw new IllegalStateException("Either targetLevel or targetXp must be specified");
        }
    }

    /**
     * Check if there is a time limit.
     *
     * @return true if maxDuration is set
     */
    public boolean hasTimeLimit() {
        return maxDuration != null && !maxDuration.isZero();
    }

    /**
     * How to handle products after cooking.
     */
    public enum ProductHandling {
        /**
         * Drop all products (cooked and burnt) - power training.
         */
        DROP_ALL,

        /**
         * Bank all products (cooked and burnt).
         */
        BANK_ALL,

        /**
         * Bank cooked products, drop burnt ones.
         */
        BANK_BUT_DROP_BURNT
    }
}
