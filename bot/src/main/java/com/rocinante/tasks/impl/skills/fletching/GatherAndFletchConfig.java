package com.rocinante.tasks.impl.skills.fletching;

import com.rocinante.progression.MethodLocation;
import com.rocinante.progression.TrainingMethod;
import lombok.Builder;
import lombok.Getter;

import javax.annotation.Nullable;
import java.time.Duration;

/**
 * Configuration for GatherAndFletchTask.
 *
 * <p>Defines parameters for the combined chop-and-fletch workflow:
 * chopping → fletching → (retain stackable shafts) → repeat.
 *
 * <p>Since arrow shafts are stackable, inventory management is simpler:
 * <ul>
 *   <li>DROP mode: Drop arrow shafts when cycle completes</li>
 *   <li>RETAIN mode: Keep arrow shafts (they stack), continue until bank trip needed</li>
 * </ul>
 *
 * <p>Example: Powerchop/fletch at Seers Village
 * <pre>{@code
 * GatherAndFletchConfig config = GatherAndFletchConfig.builder()
 *         .method(willowPowerchopFletch)
 *         .location(seersVillage)
 *         .productHandling(ProductHandling.RETAIN)
 *         .targetLevel(70)
 *         .build();
 * }</pre>
 */
@Getter
@Builder
public class GatherAndFletchConfig {

    /**
     * How to handle fletching products (arrow shafts are stackable).
     */
    public enum ProductHandling {
        /** Drop arrow shafts after each cycle. */
        DROP,
        /** Retain arrow shafts (they stack). */
        RETAIN,
        /** Bank when inventory is not empty except for tools. */
        BANK
    }

    /**
     * The training method configuration (GATHER_AND_PROCESS type).
     * Contains tree object IDs, processing config, XP values, etc.
     */
    private final TrainingMethod method;

    /**
     * The training location with coordinates.
     */
    @Nullable
    private final MethodLocation location;

    /**
     * How to handle products after fletching.
     * Since arrow shafts stack, RETAIN is the default efficient option.
     */
    @Builder.Default
    private final ProductHandling productHandling = ProductHandling.RETAIN;

    /**
     * Target level for primary skill (woodcutting).
     */
    @Builder.Default
    private final int targetWoodcuttingLevel = -1;

    /**
     * Target level for secondary skill (fletching).
     */
    @Builder.Default
    private final int targetFletchingLevel = -1;

    /**
     * Target XP for primary skill.
     */
    @Builder.Default
    private final int targetWoodcuttingXp = -1;

    /**
     * Target XP for secondary skill.
     */
    @Builder.Default
    private final int targetFletchingXp = -1;

    /**
     * Maximum duration for the entire training session.
     */
    @Nullable
    private final Duration maxDuration;
}
