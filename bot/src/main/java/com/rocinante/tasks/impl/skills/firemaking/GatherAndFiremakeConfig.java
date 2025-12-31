package com.rocinante.tasks.impl.skills.firemaking;

import com.rocinante.progression.MethodLocation;
import com.rocinante.progression.TrainingMethod;
import lombok.Builder;
import lombok.Getter;

import javax.annotation.Nullable;
import java.time.Duration;

/**
 * Configuration for GatherAndFiremakeTask.
 *
 * <p>Defines parameters for the combined chop-and-firemake workflow:
 * chopping → firemaking → (no products, logs consumed) → repeat.
 *
 * <p>Since logs are consumed during firemaking, no drop/bank handling is needed.
 * Inventory becomes empty (except tinderbox) after each cycle.
 *
 * <p>Example: Powerchop/firemake at Seers Village
 * <pre>{@code
 * GatherAndFiremakeConfig config = GatherAndFiremakeConfig.builder()
 *         .method(willowPowerchopFiremake)
 *         .location(seersVillage)
 *         .targetLevel(70)
 *         .build();
 * }</pre>
 */
@Getter
@Builder
public class GatherAndFiremakeConfig {

    /**
     * The training method configuration (GATHER_AND_PROCESS type).
     * Contains tree object IDs, log item ID, XP values, etc.
     */
    private final TrainingMethod method;

    /**
     * The training location with coordinates.
     */
    @Nullable
    private final MethodLocation location;

    /**
     * Target level for primary skill (woodcutting).
     */
    @Builder.Default
    private final int targetWoodcuttingLevel = -1;

    /**
     * Target level for secondary skill (firemaking).
     */
    @Builder.Default
    private final int targetFiremakingLevel = -1;

    /**
     * Target XP for primary skill.
     */
    @Builder.Default
    private final int targetWoodcuttingXp = -1;

    /**
     * Target XP for secondary skill.
     */
    @Builder.Default
    private final int targetFiremakingXp = -1;

    /**
     * Maximum duration for the entire training session.
     */
    @Nullable
    private final Duration maxDuration;
}
