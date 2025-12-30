package com.rocinante.tasks.impl.skills.thieving;

import com.rocinante.combat.StunHandler;
import com.rocinante.input.InventoryClickHelper;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.timing.DelayProfile;
import com.rocinante.util.ItemCollections;
import com.rocinante.util.NpcCollections;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Task for pickpocketing NPCs for Thieving training.
 *
 * <p>Features:
 * <ul>
 *   <li>Supports any pickpocketable NPC via NpcCollections</li>
 *   <li>Integrated stun detection and recovery via {@link StunHandler}</li>
 *   <li>Dodgy necklace tracking via {@link DodgyNecklaceTracker}</li>
 *   <li>HP monitoring with automatic food consumption</li>
 *   <li>Automatic necklace replacement when broken</li>
 *   <li>Location-aware NPC selection (filters by WorldPoint area)</li>
 *   <li>Humanized delays between pickpocket attempts</li>
 * </ul>
 *
 * <p>Pickpocket Flow:
 * <pre>
 * CHECK_STUN -> FIND_TARGET -> PICKPOCKET -> WAIT_RESPONSE -> CHECK_HEALTH -> REPEAT
 *                                                |
 *                                                v (if stunned)
 *                                          WAIT_STUN_END
 * </pre>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Pickpocket any man/woman nearby
 * PickpocketTask task = new PickpocketTask(NpcCollections.CITIZENS);
 *
 * // Pickpocket Ardougne Knights at market
 * PickpocketTask ardyKnights = new PickpocketTask(NpcCollections.KNIGHTS_OF_ARDOUGNE)
 *     .withLocation(new WorldPoint(2654, 3296, 0), 5)  // Market square
 *     .withFoodIds(List.of(ItemID.CAKE, ItemID._23_CAKE, ItemID.SLICE_OF_CAKE))
 *     .withMinHp(15);
 *
 * // Pickpocket Master Farmers for seeds
 * PickpocketTask masterFarmer = new PickpocketTask(NpcCollections.MASTER_FARMERS)
 *     .withTargetCount(100);
 * }</pre>
 *
 * @see StunHandler for stun detection
 * @see DodgyNecklaceTracker for necklace management
 * @see NpcCollections for NPC ID lists
 */
@Slf4j
public class PickpocketTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Animation ID for pickpocket attempt.
     * From RuneLite AnimationID.HUMAN_PICKPOCKET (881).
     */
    private static final int PICKPOCKET_ANIMATION = 881;

    /**
     * Maximum ticks to wait for pickpocket response.
     */
    private static final int PICKPOCKET_TIMEOUT_TICKS = 8;

    /**
     * Default search radius for finding NPCs (tiles).
     */
    private static final int DEFAULT_SEARCH_RADIUS = 15;

    /**
     * Coin pouch item ID (stacks up to 28 before opening).
     */
    private static final int COIN_POUCH_ID = ItemID.COIN_POUCH;

    /**
     * Maximum coin pouches before auto-opening.
     */
    private static final int MAX_COIN_POUCHES = 28;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * NPC IDs to pickpocket.
     */
    @Getter
    private final List<Integer> targetNpcIds;

    /**
     * Food item IDs to eat when HP is low.
     */
    @Getter
    private List<Integer> foodIds;

    /**
     * Minimum HP before eating food.
     */
    @Getter
    @Setter
    private int minHp = 10;

    /**
     * Target location for NPC selection (optional).
     * If set, only NPCs within locationRadius of this point will be targeted.
     */
    @Getter
    @Setter
    private WorldPoint targetLocation;

    /**
     * Radius around targetLocation to search for NPCs.
     */
    @Getter
    @Setter
    private int locationRadius = 5;

    /**
     * Search radius for finding NPCs.
     */
    @Getter
    @Setter
    private int searchRadius = DEFAULT_SEARCH_RADIUS;

    /**
     * Target number of successful pickpockets. -1 for unlimited.
     */
    @Getter
    @Setter
    private int targetCount = -1;

    /**
     * Whether to auto-equip replacement dodgy necklaces.
     */
    @Getter
    @Setter
    private boolean autoEquipNecklace = true;

    /**
     * Whether to auto-open coin pouches when full.
     */
    @Getter
    @Setter
    private boolean autoOpenCoinPouches = true;

    /**
     * Custom description.
     */
    @Setter
    private String description;

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private PickpocketPhase phase = PickpocketPhase.CHECK_STUN;

    /**
     * Stun handler for detecting and waiting out stuns.
     */
    private final StunHandler stunHandler = new StunHandler();

    /**
     * Dodgy necklace tracker.
     */
    private final DodgyNecklaceTracker necklaceTracker = new DodgyNecklaceTracker();

    /**
     * Current target NPC.
     */
    private NPC currentTarget;

    /**
     * Number of successful pickpockets.
     */
    @Getter
    private int successfulPickpockets = 0;

    /**
     * Number of failed pickpockets (stunned).
     */
    @Getter
    private int failedPickpockets = 0;

    /**
     * Ticks waiting in current phase.
     */
    private int waitTicks = 0;

    /**
     * Whether an async operation is pending.
     */
    private boolean operationPending = false;

    /**
     * Whether we're waiting for humanized delay.
     */
    private boolean waitingForDelay = false;

    /**
     * HP before last pickpocket attempt (for damage detection).
     */
    private int hpBeforeAttempt = -1;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a pickpocket task for specified NPCs.
     *
     * @param npcIds the NPC IDs to pickpocket
     */
    public PickpocketTask(Collection<Integer> npcIds) {
        this.targetNpcIds = new ArrayList<>(npcIds);
        this.foodIds = new ArrayList<>(ItemCollections.FOOD);
        this.timeout = Duration.ofHours(2);
    }

    /**
     * Create a pickpocket task for a single NPC type.
     *
     * @param npcId the NPC ID to pickpocket
     */
    public PickpocketTask(int npcId) {
        this.targetNpcIds = List.of(npcId);
        this.foodIds = new ArrayList<>(ItemCollections.FOOD);
        this.timeout = Duration.ofHours(2);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set food item IDs (builder-style).
     */
    public PickpocketTask withFoodIds(Collection<Integer> foodIds) {
        this.foodIds = new ArrayList<>(foodIds);
        return this;
    }

    /**
     * Set minimum HP threshold for eating (builder-style).
     */
    public PickpocketTask withMinHp(int minHp) {
        this.minHp = minHp;
        return this;
    }

    /**
     * Set target location for NPC filtering (builder-style).
     */
    public PickpocketTask withLocation(WorldPoint location, int radius) {
        this.targetLocation = location;
        this.locationRadius = radius;
        return this;
    }

    /**
     * Set target pickpocket count (builder-style).
     */
    public PickpocketTask withTargetCount(int count) {
        this.targetCount = count;
        return this;
    }

    /**
     * Set search radius (builder-style).
     */
    public PickpocketTask withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Set auto-equip necklace behavior (builder-style).
     */
    public PickpocketTask withAutoEquipNecklace(boolean autoEquip) {
        this.autoEquipNecklace = autoEquip;
        return this;
    }

    /**
     * Set custom description (builder-style).
     */
    public PickpocketTask withDescription(String description) {
        this.description = description;
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

        // Verify we have InventoryClickHelper
        if (ctx.getInventoryClickHelper() == null) {
            log.error("InventoryClickHelper not available");
            return false;
        }

        return true;
    }

    @Override
    protected void resetImpl() {
        phase = PickpocketPhase.CHECK_STUN;
        currentTarget = null;
        waitTicks = 0;
        operationPending = false;
        waitingForDelay = false;
        hpBeforeAttempt = -1;
        stunHandler.reset();
        necklaceTracker.reset();
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (operationPending || waitingForDelay) {
            return;
        }

        // Update trackers every tick
        stunHandler.update(ctx);
        necklaceTracker.update(ctx);

        switch (phase) {
            case CHECK_STUN:
                executeCheckStun(ctx);
                break;
            case CHECK_HEALTH:
                executeCheckHealth(ctx);
                break;
            case CHECK_NECKLACE:
                executeCheckNecklace(ctx);
                break;
            case CHECK_COIN_POUCHES:
                executeCheckCoinPouches(ctx);
                break;
            case FIND_TARGET:
                executeFindTarget(ctx);
                break;
            case PICKPOCKET:
                executePickpocket(ctx);
                break;
            case WAIT_RESPONSE:
                executeWaitResponse(ctx);
                break;
            case WAIT_STUN_END:
                executeWaitStunEnd(ctx);
                break;
            case EAT_FOOD:
                executeEatFood(ctx);
                break;
            case EQUIP_NECKLACE:
                executeEquipNecklace(ctx);
                break;
            case OPEN_POUCHES:
                executeOpenPouches(ctx);
                break;
            case DELAY_BETWEEN:
                executeDelayBetween(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Check Stun
    // ========================================================================

    private void executeCheckStun(TaskContext ctx) {
        // Check if target count reached
        if (targetCount > 0 && successfulPickpockets >= targetCount) {
            log.info("Target pickpocket count reached ({})", targetCount);
            logStatistics();
            complete();
            return;
        }

        if (stunHandler.isStunned()) {
            recordPhaseTransition(PickpocketPhase.WAIT_STUN_END);
            phase = PickpocketPhase.WAIT_STUN_END;
            return;
        }

        recordPhaseTransition(PickpocketPhase.CHECK_HEALTH);
        phase = PickpocketPhase.CHECK_HEALTH;
    }

    // ========================================================================
    // Phase: Check Health
    // ========================================================================

    private void executeCheckHealth(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        int currentHp = player.getCurrentHitpoints();

        if (currentHp <= minHp) {
            log.debug("HP low ({}/{}), eating food", currentHp, minHp);
            recordPhaseTransition(PickpocketPhase.EAT_FOOD);
            phase = PickpocketPhase.EAT_FOOD;
            return;
        }

        recordPhaseTransition(PickpocketPhase.CHECK_NECKLACE);
        phase = PickpocketPhase.CHECK_NECKLACE;
    }

    // ========================================================================
    // Phase: Check Necklace
    // ========================================================================

    private void executeCheckNecklace(TaskContext ctx) {
        if (autoEquipNecklace && necklaceTracker.isNeedsReplacement()) {
            if (necklaceTracker.hasReplacementInInventory(ctx)) {
                log.debug("Dodgy necklace broken, equipping replacement");
                recordPhaseTransition(PickpocketPhase.EQUIP_NECKLACE);
                phase = PickpocketPhase.EQUIP_NECKLACE;
                return;
            } else {
                log.warn("Dodgy necklace broken but no replacements in inventory!");
                necklaceTracker.acknowledgeReplacement(); // Don't keep checking
            }
        }

        recordPhaseTransition(PickpocketPhase.CHECK_COIN_POUCHES);
        phase = PickpocketPhase.CHECK_COIN_POUCHES;
    }

    // ========================================================================
    // Phase: Check Coin Pouches
    // ========================================================================

    private void executeCheckCoinPouches(TaskContext ctx) {
        if (autoOpenCoinPouches) {
            InventoryState inventory = ctx.getInventoryState();
            int pouchCount = inventory.countItem(COIN_POUCH_ID);

            if (pouchCount >= MAX_COIN_POUCHES) {
                log.debug("Coin pouches full ({}), opening", pouchCount);
                recordPhaseTransition(PickpocketPhase.OPEN_POUCHES);
                phase = PickpocketPhase.OPEN_POUCHES;
                return;
            }
        }

        recordPhaseTransition(PickpocketPhase.FIND_TARGET);
        phase = PickpocketPhase.FIND_TARGET;
    }

    // ========================================================================
    // Phase: Find Target
    // ========================================================================

    private void executeFindTarget(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        // Find nearest valid NPC
        currentTarget = findNearestTarget(ctx, playerPos);

        if (currentTarget == null) {
            log.warn("No pickpocket target found within {} tiles", searchRadius);
            fail("No target NPC found");
            return;
        }

        // Record HP for damage detection
        hpBeforeAttempt = player.getCurrentHitpoints();

        log.debug("Found target: {} at {}", currentTarget.getName(), currentTarget.getWorldLocation());
        recordPhaseTransition(PickpocketPhase.PICKPOCKET);
        phase = PickpocketPhase.PICKPOCKET;
    }

    // ========================================================================
    // Phase: Pickpocket
    // ========================================================================

    private void executePickpocket(TaskContext ctx) {
        if (currentTarget == null || currentTarget.isDead()) {
            phase = PickpocketPhase.FIND_TARGET;
            return;
        }

        log.debug("Attempting to pickpocket {}", currentTarget.getName());
        operationPending = true;

        // Click NPC with "Pickpocket" action
        ctx.getSafeClickExecutor().clickNpc(currentTarget, "Pickpocket")
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        waitTicks = 0;
                        recordPhaseTransition(PickpocketPhase.WAIT_RESPONSE);
                        phase = PickpocketPhase.WAIT_RESPONSE;
                    } else {
                        log.warn("Failed to click pickpocket target");
                        phase = PickpocketPhase.FIND_TARGET;
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Pickpocket click failed", e);
                    phase = PickpocketPhase.FIND_TARGET;
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait Response
    // ========================================================================

    private void executeWaitResponse(TaskContext ctx) {
        waitTicks++;

        if (waitTicks > PICKPOCKET_TIMEOUT_TICKS) {
            log.debug("Pickpocket response timed out");
            scheduleDelayBetween(ctx);
            return;
        }

        PlayerState player = ctx.getPlayerState();

        // Check for stun (failed pickpocket)
        if (stunHandler.isStunned()) {
            failedPickpockets++;
            log.debug("Pickpocket failed - stunned! (total fails: {})", failedPickpockets);
            recordPhaseTransition(PickpocketPhase.WAIT_STUN_END);
            phase = PickpocketPhase.WAIT_STUN_END;
            return;
        }

        // Check for pickpocket animation (success indicator)
        if (player.isAnimating(PICKPOCKET_ANIMATION)) {
            successfulPickpockets++;
            stunHandler.recordSuccessfulAction();
            log.debug("Pickpocket successful! (total: {})", successfulPickpockets);
            scheduleDelayBetween(ctx);
            return;
        }

        // Check for HP decrease without stun (dodgy necklace saved us but we took damage)
        int currentHp = player.getCurrentHitpoints();
        if (hpBeforeAttempt > 0 && currentHp < hpBeforeAttempt && !stunHandler.isStunned()) {
            // Took damage but not stunned = dodgy necklace proc
            log.debug("Took damage but not stunned (dodgy necklace)");
            scheduleDelayBetween(ctx);
        }
    }

    // ========================================================================
    // Phase: Wait Stun End
    // ========================================================================

    private void executeWaitStunEnd(TaskContext ctx) {
        if (!stunHandler.isStunned()) {
            log.debug("Stun ended, resuming");
            stunHandler.clearStun();
            recordPhaseTransition(PickpocketPhase.CHECK_STUN);
            phase = PickpocketPhase.CHECK_STUN;
        }
        // Otherwise keep waiting
    }

    // ========================================================================
    // Phase: Eat Food
    // ========================================================================

    private void executeEatFood(TaskContext ctx) {
        InventoryClickHelper inventoryHelper = ctx.getInventoryClickHelper();
        InventoryState inventory = ctx.getInventoryState();

        // Find food in inventory
        int foodSlot = -1;
        for (int foodId : foodIds) {
            int slot = inventory.getSlotOf(foodId);
            if (slot >= 0) {
                foodSlot = slot;
                break;
            }
        }

        if (foodSlot < 0) {
            log.warn("No food in inventory! HP is low.");
            // Continue anyway, but this is dangerous
            recordPhaseTransition(PickpocketPhase.CHECK_NECKLACE);
            phase = PickpocketPhase.CHECK_NECKLACE;
            return;
        }

        log.debug("Eating food from slot {}", foodSlot);
        operationPending = true;

        inventoryHelper.executeClick(foodSlot, "Eat food")
                .thenAccept(success -> {
                    operationPending = false;
                    // Check health again after eating
                    recordPhaseTransition(PickpocketPhase.CHECK_HEALTH);
                    phase = PickpocketPhase.CHECK_HEALTH;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to eat food", e);
                    phase = PickpocketPhase.CHECK_HEALTH;
                    return null;
                });
    }

    // ========================================================================
    // Phase: Equip Necklace
    // ========================================================================

    private void executeEquipNecklace(TaskContext ctx) {
        int slot = necklaceTracker.getReplacementSlot(ctx);

        if (slot < 0) {
            log.warn("No dodgy necklace found in inventory");
            necklaceTracker.acknowledgeReplacement();
            recordPhaseTransition(PickpocketPhase.CHECK_COIN_POUCHES);
            phase = PickpocketPhase.CHECK_COIN_POUCHES;
            return;
        }

        log.debug("Equipping dodgy necklace from slot {}", slot);
        operationPending = true;

        ctx.getInventoryClickHelper().executeClick(slot, "Equip dodgy necklace")
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        necklaceTracker.acknowledgeReplacement();
                    }
                    recordPhaseTransition(PickpocketPhase.CHECK_COIN_POUCHES);
                    phase = PickpocketPhase.CHECK_COIN_POUCHES;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to equip necklace", e);
                    phase = PickpocketPhase.CHECK_COIN_POUCHES;
                    return null;
                });
    }

    // ========================================================================
    // Phase: Open Pouches
    // ========================================================================

    private void executeOpenPouches(TaskContext ctx) {
        InventoryState inventory = ctx.getInventoryState();
        int slot = inventory.getSlotOf(COIN_POUCH_ID);

        if (slot < 0) {
            recordPhaseTransition(PickpocketPhase.FIND_TARGET);
            phase = PickpocketPhase.FIND_TARGET;
            return;
        }

        log.debug("Opening coin pouches");
        operationPending = true;

        ctx.getInventoryClickHelper().executeClick(slot, "Open coin pouches")
                .thenAccept(success -> {
                    operationPending = false;
                    recordPhaseTransition(PickpocketPhase.FIND_TARGET);
                    phase = PickpocketPhase.FIND_TARGET;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to open pouches", e);
                    phase = PickpocketPhase.FIND_TARGET;
                    return null;
                });
    }

    // ========================================================================
    // Phase: Delay Between
    // ========================================================================

    private void scheduleDelayBetween(TaskContext ctx) {
        recordPhaseTransition(PickpocketPhase.DELAY_BETWEEN);
        phase = PickpocketPhase.DELAY_BETWEEN;
        waitingForDelay = true;

        var humanTimer = ctx.getHumanTimer();
        if (humanTimer != null) {
            humanTimer.sleep(DelayProfile.ACTION_GAP)
                    .thenRun(() -> {
                        waitingForDelay = false;
                        phase = PickpocketPhase.CHECK_STUN;
                    });
        } else {
            long delayMs = ThreadLocalRandom.current().nextLong(150, 400);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            waitingForDelay = false;
            phase = PickpocketPhase.CHECK_STUN;
        }
    }

    private void executeDelayBetween(TaskContext ctx) {
        if (!waitingForDelay) {
            phase = PickpocketPhase.CHECK_STUN;
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Find the nearest valid pickpocket target.
     */
    private NPC findNearestTarget(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();

        return client.getNpcs().stream()
                .filter(npc -> npc != null && !npc.isDead())
                .filter(npc -> targetNpcIds.contains(npc.getId()))
                .filter(npc -> {
                    WorldPoint npcPos = npc.getWorldLocation();
                    int distance = playerPos.distanceTo(npcPos);
                    return distance <= searchRadius;
                })
                .filter(npc -> {
                    // If location filter is set, check it
                    if (targetLocation != null) {
                        WorldPoint npcPos = npc.getWorldLocation();
                        return targetLocation.distanceTo(npcPos) <= locationRadius;
                    }
                    return true;
                })
                .min(Comparator.comparingInt(npc -> playerPos.distanceTo(npc.getWorldLocation())))
                .orElse(null);
    }

    /**
     * Log statistics at completion.
     */
    private void logStatistics() {
        log.info("=== Pickpocket Statistics ===");
        log.info("Successful pickpockets: {}", successfulPickpockets);
        log.info("Failed pickpockets (stunned): {}", failedPickpockets);
        int total = successfulPickpockets + failedPickpockets;
        double successRate = total > 0 ? (double) successfulPickpockets / total * 100 : 0;
        log.info("Success rate: {:.1f}%", successRate);
        log.info("Total stuns: {}, Consecutive stuns: {}",
                stunHandler.getTotalStunCount(), stunHandler.getConsecutiveStunCount());
        log.info(necklaceTracker.getStatsString());
    }

    /**
     * Get the stun handler (for external monitoring).
     */
    public StunHandler getStunHandler() {
        return stunHandler;
    }

    /**
     * Get the necklace tracker (for external monitoring).
     */
    public DodgyNecklaceTracker getNecklaceTracker() {
        return necklaceTracker;
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("Pickpocket[success=%d, fails=%d, target=%s]",
                successfulPickpockets, failedPickpockets,
                currentTarget != null ? currentTarget.getName() : "none");
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum PickpocketPhase {
        CHECK_STUN,
        CHECK_HEALTH,
        CHECK_NECKLACE,
        CHECK_COIN_POUCHES,
        FIND_TARGET,
        PICKPOCKET,
        WAIT_RESPONSE,
        WAIT_STUN_END,
        EAT_FOOD,
        EQUIP_NECKLACE,
        OPEN_POUCHES,
        DELAY_BETWEEN
    }
}

