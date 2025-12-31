package com.rocinante.tasks.impl.skills.cooking;

import com.rocinante.progression.MethodLocation;
import com.rocinante.progression.TrainingMethod;
import lombok.Builder;
import lombok.Getter;

import javax.annotation.Nullable;
import java.time.Duration;

/**
 * Configuration for GatherAndCookTask.
 *
 * <p>Defines parameters for the combined gather-and-cook workflow:
 * fishing → cooking → drop/bank → repeat.
 *
 * <p>Example: Powerfish/cook at Barbarian Village
 * <pre>{@code
 * GatherAndCookConfig config = GatherAndCookConfig.builder()
 *         .method(troutSalmonPowerfishCook)
 *         .location(barbarianVillage)
 *         .productHandling(ProductHandling.DROP_ALL)
 *         .targetLevel(70)
 *         .build();
 * }</pre>
 */
@Getter
@Builder
public class GatherAndCookConfig {

    /**
     * The training method configuration (GATHER_AND_PROCESS type).
     * Contains gathering NPC IDs, processing objects, XP values, etc.
     */
    private final TrainingMethod method;

    /**
     * The training location with coordinates.
     * Includes fishing spot area and fire/range location.
     */
    @Nullable
    private final MethodLocation location;

    /**
     * How to handle products after cooking.
     */
    @Builder.Default
    private final CookingSkillTaskConfig.ProductHandling productHandling = CookingSkillTaskConfig.ProductHandling.DROP_ALL;

    /**
     * Target level for primary skill (fishing).
     */
    @Builder.Default
    private final int targetFishingLevel = -1;

    /**
     * Target level for secondary skill (cooking).
     */
    @Builder.Default
    private final int targetCookingLevel = -1;

    /**
     * Target XP for primary skill.
     */
    @Builder.Default
    private final int targetFishingXp = -1;

    /**
     * Target XP for secondary skill.
     */
    @Builder.Default
    private final int targetCookingXp = -1;

    /**
     * Maximum duration for the entire training session.
     */
    @Nullable
    private final Duration maxDuration;
}
