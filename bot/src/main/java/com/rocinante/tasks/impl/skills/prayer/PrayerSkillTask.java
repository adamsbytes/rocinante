package com.rocinante.tasks.impl.skills.prayer;

import com.rocinante.progression.MethodType;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.tasks.impl.BuryBonesTask;
import com.rocinante.tasks.impl.ResupplyTask;
import com.rocinante.tasks.impl.WalkToTask;
import com.rocinante.util.ItemCollections;
import com.rocinante.util.ObjectCollections;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * High-level orchestrator for Prayer skill training.
 *
 * <p>PrayerSkillTask coordinates all aspects of a prayer training session:
 * <ul>
 *   <li>Travel to training location (altar or burial site)</li>
 *   <li>Execute appropriate prayer method (bury or offer at altar)</li>
 *   <li>Banking for more bones when inventory is empty</li>
 *   <li>Tracking XP and bones used</li>
 * </ul>
 *
 * <p>Supported training methods (via {@link PrayerMethod}):
 * <ul>
 *   <li>{@link PrayerMethod#BURY} - Bury bones using {@link BuryBonesTask}</li>
 *   <li>{@link PrayerMethod#ALTAR} - Offer bones at altar using {@link AltarOfferTask}</li>
 *   <li>{@link PrayerMethod#ECTOFUNTUS} - Future: Use Ectofuntus for 4x XP</li>
 *   <li>{@link PrayerMethod#CHAOS_ALTAR} - Offer at Chaos Altar (Wilderness) with 50% save chance</li>
 * </ul>
 *
 * <p>Pre-configured training methods:
 * <ul>
 *   <li>Bone burial (safe, anywhere)</li>
 *   <li>Gilded altar (POH or W330 house party)</li>
 *   <li>Chaos Altar (Wilderness - risky but efficient)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Simple bone burial
 * PrayerSkillTask task = PrayerSkillTask.buryBones()
 *     .withBoneType(ItemID.DRAGON_BONES)
 *     .withTargetLevel(43);  // Train to protection prayers
 *
 * // Gilded altar training
 * PrayerSkillTask gilded = PrayerSkillTask.gildedAltar()
 *     .withBoneType(ItemID.SUPERIOR_DRAGON_BONES)
 *     .withTargetLevel(70);
 *
 * // Chaos Altar (risky but cost-effective)
 * PrayerSkillTask chaos = PrayerSkillTask.chaosAltar()
 *     .withBoneType(ItemID.DRAGON_BONES)
 *     .withTargetLevel(77)  // Rigour
 *     .withEscapeProtocol(true);  // Enable emergency logout
 * }</pre>
 *
 * @see BuryBonesTask for bone burial
 * @see AltarOfferTask for altar offering
 */
@Slf4j
public class PrayerSkillTask extends AbstractTask {

    // ========================================================================
    // Pre-configured Locations
    // ========================================================================

    /**
     * Lumbridge Graveyard - safe place to bury bones.
     */
    public static final WorldPoint LUMBRIDGE_GRAVEYARD = new WorldPoint(3241, 3193, 0);

    /**
     * Edgeville bank - close to Chaos Altar.
     */
    public static final WorldPoint EDGEVILLE_BANK = new WorldPoint(3094, 3492, 0);

    /**
     * Chaos Altar in the Wilderness (level 38 Wilderness).
     */
    public static final WorldPoint CHAOS_ALTAR = new WorldPoint(2947, 3820, 0);

    /**
     * Rimmington House Portal - for gilded altar at POH.
     */
    public static final WorldPoint RIMMINGTON_PORTAL = new WorldPoint(2954, 3224, 0);

    /**
     * Altar inside Lumbridge Castle chapel.
     */
    public static final WorldPoint LUMBRIDGE_ALTAR = new WorldPoint(3243, 3207, 0);

    /**
     * Monastery altar.
     */
    public static final WorldPoint MONASTERY_ALTAR = new WorldPoint(3053, 3484, 0);

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The prayer training method to use.
     */
    @Getter
    private final PrayerMethod method;

    /**
     * Bone item IDs to use for training.
     */
    @Getter
    private List<Integer> boneIds = new ArrayList<>();

    /**
     * Training location (altar or burial spot).
     */
    @Getter
    @Setter
    private WorldPoint trainingLocation;

    /**
     * Bank location.
     */
    @Getter
    @Setter
    private WorldPoint bankLocation;

    /**
     * Altar object IDs (for altar method).
     */
    @Getter
    private List<Integer> altarIds = new ArrayList<>();

    /**
     * Target prayer level to reach.
     */
    @Getter
    @Setter
    private int targetLevel = -1;

    /**
     * Target number of bones to use.
     */
    @Getter
    @Setter
    private int targetBones = -1;

    /**
     * Number of bones to withdraw per bank trip.
     */
    @Getter
    @Setter
    private int bonesPerTrip = 26;

    /**
     * Whether this is at Chaos Altar (enables 50% bone save tracking).
     */
    @Getter
    @Setter
    private boolean isChaosAltar = false;

    /**
     * Whether to enable emergency escape in Wilderness.
     */
    @Getter
    @Setter
    private boolean escapeProtocol = false;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current phase.
     */
    private PrayerPhase phase = PrayerPhase.CHECK_REQUIREMENTS;

    /**
     * Active sub-task.
     */
    private Task currentSubTask;

    /**
     * Starting XP.
     */
    private int startXp = -1;

    /**
     * Total bones used.
     */
    @Getter
    private int totalBonesUsed = 0;

    /**
     * Bones saved at Chaos Altar (50% chance to not consume).
     */
    @Getter
    private int bonesSaved = 0;

    /**
     * Whether we've traveled to location.
     */
    private boolean atLocation = false;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a prayer skill task with the specified method.
     *
     * @param method the prayer training method
     */
    public PrayerSkillTask(PrayerMethod method) {
        this.method = method;
        this.timeout = Duration.ofHours(6);

        // Default to regular bones
        this.boneIds.addAll(ItemCollections.BONES);
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a task for burying bones.
     */
    public static PrayerSkillTask buryBones() {
        PrayerSkillTask task = new PrayerSkillTask(PrayerMethod.BURY);
        task.trainingLocation = LUMBRIDGE_GRAVEYARD;
        task.bankLocation = new WorldPoint(3208, 3220, 2);  // Lumbridge bank
        return task;
    }

    /**
     * Create a task for offering bones at a basic altar.
     */
    public static PrayerSkillTask basicAltar() {
        PrayerSkillTask task = new PrayerSkillTask(PrayerMethod.ALTAR);
        task.trainingLocation = LUMBRIDGE_ALTAR;
        task.bankLocation = new WorldPoint(3208, 3220, 2);  // Lumbridge bank
        task.altarIds.addAll(ObjectCollections.ALTARS);
        return task;
    }

    /**
     * Create a task for offering bones at a gilded altar.
     * Uses the W330 house party world method.
     */
    public static PrayerSkillTask gildedAltar() {
        PrayerSkillTask task = new PrayerSkillTask(PrayerMethod.ALTAR);
        task.trainingLocation = RIMMINGTON_PORTAL;
        task.bankLocation = RIMMINGTON_PORTAL;  // Bank at portal with NPC
        task.altarIds.addAll(ObjectCollections.POH_GILDED_ALTARS);
        return task;
    }

    /**
     * Create a task for offering bones at the Chaos Altar.
     * Located in level 38 Wilderness - dangerous but efficient.
     */
    public static PrayerSkillTask chaosAltar() {
        PrayerSkillTask task = new PrayerSkillTask(PrayerMethod.CHAOS_ALTAR);
        task.trainingLocation = CHAOS_ALTAR;
        task.bankLocation = EDGEVILLE_BANK;
        task.altarIds.addAll(ObjectCollections.CHAOS_ALTARS);
        task.isChaosAltar = true;
        task.escapeProtocol = true;  // Enable by default for wilderness
        task.bonesPerTrip = 26;  // Full inventory, no food needed if using burning amulet
        return task;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set bone type (builder-style).
     */
    public PrayerSkillTask withBoneType(int boneId) {
        this.boneIds.clear();
        this.boneIds.add(boneId);
        return this;
    }

    /**
     * Set multiple bone types (builder-style).
     */
    public PrayerSkillTask withBoneTypes(Collection<Integer> boneIds) {
        this.boneIds = new ArrayList<>(boneIds);
        return this;
    }

    /**
     * Set training location (builder-style).
     */
    public PrayerSkillTask withLocation(WorldPoint location) {
        this.trainingLocation = location;
        return this;
    }

    /**
     * Set bank location (builder-style).
     */
    public PrayerSkillTask withBankLocation(WorldPoint location) {
        this.bankLocation = location;
        return this;
    }

    /**
     * Set target level (builder-style).
     */
    public PrayerSkillTask withTargetLevel(int level) {
        this.targetLevel = level;
        return this;
    }

    /**
     * Set target bone count (builder-style).
     */
    public PrayerSkillTask withTargetBones(int count) {
        this.targetBones = count;
        return this;
    }

    /**
     * Set bones per trip (builder-style).
     */
    public PrayerSkillTask withBonesPerTrip(int count) {
        this.bonesPerTrip = count;
        return this;
    }

    /**
     * Set altar IDs (builder-style).
     */
    public PrayerSkillTask withAltarIds(Collection<Integer> altarIds) {
        this.altarIds = new ArrayList<>(altarIds);
        return this;
    }

    /**
     * Enable/disable escape protocol (builder-style).
     */
    public PrayerSkillTask withEscapeProtocol(boolean enable) {
        this.escapeProtocol = enable;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (!ctx.isLoggedIn()) {
            return false;
        }

        if (boneIds.isEmpty()) {
            log.error("No bone types configured");
            return false;
        }

        if (trainingLocation == null) {
            log.error("No training location configured");
            return false;
        }

        if ((method == PrayerMethod.ALTAR || method == PrayerMethod.CHAOS_ALTAR)
                && altarIds.isEmpty()) {
            log.error("No altar IDs configured for altar method");
            return false;
        }

        return true;
    }

    @Override
    protected void resetImpl() {
        phase = PrayerPhase.CHECK_REQUIREMENTS;
        currentSubTask = null;
        startXp = -1;
        totalBonesUsed = 0;
        bonesSaved = 0;
        atLocation = false;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        // Check for target level reached
        if (targetLevel > 0) {
            int currentLevel = ctx.getClient().getRealSkillLevel(Skill.PRAYER);
            if (currentLevel >= targetLevel) {
                log.info("Target prayer level {} reached!", targetLevel);
                logStatistics(ctx);
                complete();
                return;
            }
        }

        // Check for target bones reached
        if (targetBones > 0 && totalBonesUsed >= targetBones) {
            log.info("Target bones used ({}) reached!", targetBones);
            logStatistics(ctx);
            complete();
            return;
        }

        // Record starting XP
        if (startXp < 0) {
            startXp = ctx.getClient().getSkillExperience(Skill.PRAYER);
        }

        // Handle sub-task execution
        if (currentSubTask != null) {
            if (!currentSubTask.getState().isTerminal()) {
                currentSubTask.execute(ctx);
                return;
            } else if (currentSubTask.getState() == TaskState.COMPLETED) {
                handleSubTaskComplete(ctx);
                currentSubTask = null;
            } else if (currentSubTask.getState() == TaskState.FAILED) {
                log.warn("Sub-task failed: {}", currentSubTask.getDescription());
                currentSubTask = null;
                // Try to recover
            }
        }

        switch (phase) {
            case CHECK_REQUIREMENTS:
                executeCheckRequirements(ctx);
                break;
            case TRAVEL_TO_BANK:
                executeTravelToBank(ctx);
                break;
            case BANK:
                executeBank(ctx);
                break;
            case TRAVEL_TO_LOCATION:
                executeTravelToLocation(ctx);
                break;
            case TRAIN:
                executeTrain(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Check Requirements
    // ========================================================================

    private void executeCheckRequirements(TaskContext ctx) {
        // Check if we have bones in inventory
        InventoryState inventory = ctx.getInventoryState();
        int boneCount = countBonesInInventory(inventory);

        if (boneCount > 0) {
            // We have bones, go to training location
            recordPhaseTransition(PrayerPhase.TRAVEL_TO_LOCATION);
            phase = PrayerPhase.TRAVEL_TO_LOCATION;
        } else {
            // Need to get bones from bank
            recordPhaseTransition(PrayerPhase.TRAVEL_TO_BANK);
            phase = PrayerPhase.TRAVEL_TO_BANK;
        }
    }

    // ========================================================================
    // Phase: Travel to Bank
    // ========================================================================

    private void executeTravelToBank(TaskContext ctx) {
        if (bankLocation == null) {
            fail("No bank location configured");
            return;
        }

        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();
        int distance = playerPos.distanceTo(bankLocation);

        if (distance <= 10) {
            recordPhaseTransition(PrayerPhase.BANK);
            phase = PrayerPhase.BANK;
            return;
        }

        log.debug("Traveling to bank (distance: {})", distance);
        currentSubTask = new WalkToTask(bankLocation);
        currentSubTask.execute(ctx);
    }

    // ========================================================================
    // Phase: Bank
    // ========================================================================

    private void executeBank(TaskContext ctx) {
        ResupplyTask.ResupplyTaskBuilder builder = ResupplyTask.builder()
                .depositInventory(true)
                .returnPosition(trainingLocation);

        // Withdraw bones
        if (!boneIds.isEmpty()) {
            builder.withdrawItem(boneIds.get(0), bonesPerTrip);
        }

        currentSubTask = builder.build();
        currentSubTask.execute(ctx);
    }

    // ========================================================================
    // Phase: Travel to Location
    // ========================================================================

    private void executeTravelToLocation(TaskContext ctx) {
        if (atLocation) {
            recordPhaseTransition(PrayerPhase.TRAIN);
            phase = PrayerPhase.TRAIN;
            return;
        }

        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();
        int distance = playerPos.distanceTo(trainingLocation);

        if (distance <= 10) {
            atLocation = true;
            log.debug("Arrived at training location");
            recordPhaseTransition(PrayerPhase.TRAIN);
            phase = PrayerPhase.TRAIN;
            return;
        }

        log.debug("Traveling to training location (distance: {})", distance);
        currentSubTask = new WalkToTask(trainingLocation);
        currentSubTask.execute(ctx);
    }

    // ========================================================================
    // Phase: Train
    // ========================================================================

    private void executeTrain(TaskContext ctx) {
        Task trainingTask;

        switch (method) {
            case BURY:
                // Use existing BuryBonesTask
                trainingTask = new BuryBonesTask(boneIds);
                break;

            case ALTAR:
            case CHAOS_ALTAR:
                // Use AltarOfferTask (auto-detects Chaos Altar from altar ID)
                trainingTask = new AltarOfferTask(altarIds, boneIds);
                break;

            default:
                fail("Unsupported prayer method: " + method);
                return;
        }

        currentSubTask = trainingTask;
        currentSubTask.execute(ctx);
    }

    // ========================================================================
    // Sub-task Completion
    // ========================================================================

    private void handleSubTaskComplete(TaskContext ctx) {
        switch (phase) {
            case TRAVEL_TO_BANK:
                recordPhaseTransition(PrayerPhase.BANK);
                phase = PrayerPhase.BANK;
                break;

            case BANK:
                // Check if we got bones
                InventoryState inventory = ctx.getInventoryState();
                int boneCount = countBonesInInventory(inventory);
                
                if (boneCount == 0) {
                    log.warn("No bones withdrawn from bank");
                    fail("Out of bones");
                    return;
                }

                atLocation = false;
                recordPhaseTransition(PrayerPhase.TRAVEL_TO_LOCATION);
                phase = PrayerPhase.TRAVEL_TO_LOCATION;
                break;

            case TRAVEL_TO_LOCATION:
                atLocation = true;
                recordPhaseTransition(PrayerPhase.TRAIN);
                phase = PrayerPhase.TRAIN;
                break;

            case TRAIN:
                // Training sub-task completed (out of bones)
                if (currentSubTask instanceof BuryBonesTask) {
                    totalBonesUsed += ((BuryBonesTask) currentSubTask).getBonesBuried();
                } else if (currentSubTask instanceof AltarOfferTask) {
                    AltarOfferTask altarTask = (AltarOfferTask) currentSubTask;
                    totalBonesUsed += altarTask.getBonesConsumed();
                    bonesSaved += altarTask.getBonesSaved();
                }

                // Check if we've reached our targets
                if (targetBones > 0 && totalBonesUsed >= targetBones) {
                    log.info("Target bones used ({}) reached!", targetBones);
                    logStatistics(ctx);
                    complete();
                    return;
                }

                // Go bank for more bones
                recordPhaseTransition(PrayerPhase.TRAVEL_TO_BANK);
                phase = PrayerPhase.TRAVEL_TO_BANK;
                break;

            default:
                phase = PrayerPhase.CHECK_REQUIREMENTS;
                break;
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Count bones in inventory.
     */
    private int countBonesInInventory(InventoryState inventory) {
        int count = 0;
        for (int boneId : boneIds) {
            count += inventory.countItem(boneId);
        }
        return count;
    }

    /**
     * Log statistics.
     */
    private void logStatistics(TaskContext ctx) {
        int currentXp = ctx.getClient().getSkillExperience(Skill.PRAYER);
        int xpGained = currentXp - startXp;
        int currentLevel = ctx.getClient().getRealSkillLevel(Skill.PRAYER);

        log.info("=== Prayer Session Complete ===");
        log.info("Method: {}", method);
        log.info("Total bones used: {}", totalBonesUsed);
        if (isChaosAltar) {
            log.info("Bones saved (Chaos Altar): {}", bonesSaved);
        }
        log.info("XP gained: {}", xpGained);
        log.info("Final level: {}", currentLevel);
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        return String.format("Prayer[%s, bones=%d, phase=%s]",
                method, totalBonesUsed, phase);
    }

    // ========================================================================
    // Enums
    // ========================================================================

    /**
     * Prayer training methods.
     */
    public enum PrayerMethod {
        /** Bury bones - slowest but free */
        BURY,
        /** Offer at standard altar - 100% XP bonus with lit burners */
        ALTAR,
        /** Offer at Chaos Altar - 350% XP, 50% chance to save bone (Wilderness) */
        CHAOS_ALTAR,
        /** Use Ectofuntus - 400% XP with ectotokens (future) */
        ECTOFUNTUS
    }

    /**
     * Execution phases.
     */
    private enum PrayerPhase {
        CHECK_REQUIREMENTS,
        TRAVEL_TO_BANK,
        BANK,
        TRAVEL_TO_LOCATION,
        TRAIN
    }
}

