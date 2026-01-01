package com.rocinante.tasks.impl.skills.thieving;

import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.InteractionHelper;
import com.rocinante.tasks.TaskContext;
import com.rocinante.timing.DelayProfile;
import com.rocinante.util.ObjectCollections;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Task for stealing from market stalls for Thieving training.
 *
 * <p>Stall thieving has different mechanics than pickpocketing:
 * <ul>
 *   <li>No stun or damage on failure (but can be caught by guards)</li>
 *   <li>Stalls have respawn timers after being stolen from</li>
 *   <li>Different stalls have different level requirements and XP</li>
 *   <li>Guards can catch you if you're not in a safespot</li>
 * </ul>
 *
 * <p>Supported stall types (from {@link ObjectCollections}):
 * <ul>
 *   <li>Bakery stalls (level 5, 16 XP, 2.5s respawn)</li>
 *   <li>Silk stalls (level 20, 24 XP, 5s respawn)</li>
 *   <li>Fur stalls (level 35, 36 XP, 10s respawn)</li>
 *   <li>Silver stalls (level 50, 54 XP, 30s respawn)</li>
 *   <li>Spice stalls (level 65, 81 XP, 80s respawn)</li>
 *   <li>Gem stalls (level 75, 160 XP, 180s respawn)</li>
 *   <li>And more...</li>
 * </ul>
 *
 * <p>Ardy Cake Stall Safespot:
 * A specific training method at the East Ardougne market where a specific tile
 * allows continuous stealing from the baker's stall without being caught by
 * the guard. This is set up using the withSafespot() builder method.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Basic bakery stall stealing
 * StallThievingTask task = new StallThievingTask(ObjectCollections.BAKERY_STALLS);
 *
 * // Ardy cake stall safespot method
 * StallThievingTask ardyCakes = new StallThievingTask(ObjectCollections.BAKERY_STALLS)
 *     .withSafespot(new WorldPoint(2669, 3310, 0))  // The safespot tile
 *     .withStallLocation(new WorldPoint(2669, 3311, 0))  // The specific stall
 *     .withDescription("Ardy Cake Stall Safespot");
 *
 * // Silk stall for money making
 * StallThievingTask silk = new StallThievingTask(ObjectCollections.SILK_STALLS)
 *     .withTargetCount(100);
 * }</pre>
 */
@Slf4j
public class StallThievingTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Default search radius for finding stalls (tiles).
     */
    private static final int DEFAULT_SEARCH_RADIUS = 10;

    /**
     * Maximum ticks to wait for steal response.
     */
    private static final int STEAL_TIMEOUT_TICKS = 8;

    /**
     * Default respawn time estimate in milliseconds (used if not configured).
     */
    private static final long DEFAULT_RESPAWN_MS = 2500;

    /**
     * Animation ID for stealing from stall.
     */
    private static final int STEAL_ANIMATION = 832;

    // ========================================================================
    // Respawn Times by Stall Type (milliseconds)
    // ========================================================================

    private static final long BAKERY_RESPAWN_MS = 2500;
    private static final long SILK_RESPAWN_MS = 5000;
    private static final long FUR_RESPAWN_MS = 10000;
    private static final long SILVER_RESPAWN_MS = 30000;
    private static final long SPICE_RESPAWN_MS = 80000;
    private static final long GEM_RESPAWN_MS = 180000;
    private static final long TEA_RESPAWN_MS = 7000;
    private static final long FRUIT_RESPAWN_MS = 2500;
    private static final long SEED_RESPAWN_MS = 10000;
    private static final long VEG_RESPAWN_MS = 2500;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Stall object IDs to steal from.
     */
    @Getter
    private final List<Integer> stallIds;

    /**
     * Search radius for finding stalls.
     */
    @Getter
    @Setter
    private int searchRadius = DEFAULT_SEARCH_RADIUS;

    /**
     * Safespot tile (optional). If set, task will only steal when on this tile.
     */
    @Getter
    @Setter
    private WorldPoint safespot;

    /**
     * Specific stall location to target (optional).
     * If set, only stalls at this location will be stolen from.
     */
    @Getter
    @Setter
    private WorldPoint stallLocation;

    /**
     * Target number of successful steals. -1 for unlimited.
     */
    @Getter
    @Setter
    private int targetCount = -1;

    /**
     * Whether to drop items when inventory is full.
     */
    @Getter
    @Setter
    private boolean dropWhenFull = false;

    /**
     * Item IDs to drop when inventory is full (if dropWhenFull is true).
     */
    @Getter
    private List<Integer> dropItemIds = new ArrayList<>();

    /**
     * Custom respawn time override (milliseconds).
     */
    @Getter
    @Setter
    private long respawnTimeMs = -1;

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
    private StallPhase phase = StallPhase.CHECK_INVENTORY;

    /**
     * The stall we're stealing from.
     */
    private TileObject targetStall;

    /**
     * World position of the stall.
     */
    private WorldPoint targetStallPosition;

    /**
     * Resolved stall ID.
     */
    private int resolvedStallId = -1;

    /**
     * Number of successful steals.
     */
    @Getter
    private int successfulSteals = 0;

    /**
     * Time when last successful steal occurred.
     */
    private Instant lastStealTime;

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
     * Interaction helper for camera rotation.
     */
    private InteractionHelper interactionHelper;

    /**
     * Inventory count before steal attempt.
     */
    private int inventoryCountBefore = 0;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a stall thieving task for specified stalls.
     *
     * @param stallIds the stall object IDs to steal from
     */
    public StallThievingTask(Collection<Integer> stallIds) {
        this.stallIds = new ArrayList<>(stallIds);
    }

    /**
     * Create a stall thieving task for a single stall type.
     *
     * @param stallId the stall object ID
     */
    public StallThievingTask(int stallId) {
        this.stallIds = List.of(stallId);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set safespot tile (builder-style).
     */
    public StallThievingTask withSafespot(WorldPoint safespot) {
        this.safespot = safespot;
        return this;
    }

    /**
     * Set specific stall location (builder-style).
     */
    public StallThievingTask withStallLocation(WorldPoint location) {
        this.stallLocation = location;
        return this;
    }

    /**
     * Set target steal count (builder-style).
     */
    public StallThievingTask withTargetCount(int count) {
        this.targetCount = count;
        return this;
    }

    /**
     * Set search radius (builder-style).
     */
    public StallThievingTask withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Enable dropping items when full (builder-style).
     */
    public StallThievingTask withDropWhenFull(boolean drop, Collection<Integer> itemsToDrop) {
        this.dropWhenFull = drop;
        this.dropItemIds = new ArrayList<>(itemsToDrop);
        return this;
    }

    /**
     * Set custom respawn time (builder-style).
     */
    public StallThievingTask withRespawnTime(long ms) {
        this.respawnTimeMs = ms;
        return this;
    }

    /**
     * Set custom description (builder-style).
     */
    public StallThievingTask withDescription(String description) {
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

        return true;
    }

    @Override
    protected void resetImpl() {
        phase = StallPhase.CHECK_INVENTORY;
        targetStall = null;
        targetStallPosition = null;
        resolvedStallId = -1;
        waitTicks = 0;
        operationPending = false;
        waitingForDelay = false;
        lastStealTime = null;
        inventoryCountBefore = 0;
        if (interactionHelper != null) {
            interactionHelper.reset();
        }
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (operationPending || waitingForDelay) {
            return;
        }

        switch (phase) {
            case CHECK_INVENTORY:
                executeCheckInventory(ctx);
                break;
            case CHECK_SAFESPOT:
                executeCheckSafespot(ctx);
                break;
            case FIND_STALL:
                executeFindStall(ctx);
                break;
            case WAIT_FOR_CLICKBOX:
                executeWaitForClickbox(ctx);
                break;
            case WAIT_RESPAWN:
                executeWaitRespawn(ctx);
                break;
            case STEAL:
                executeSteal(ctx);
                break;
            case WAIT_RESPONSE:
                executeWaitResponse(ctx);
                break;
            case DROP_ITEMS:
                executeDropItems(ctx);
                break;
            case DELAY_BETWEEN:
                executeDelayBetween(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Check Inventory
    // ========================================================================

    private void executeCheckInventory(TaskContext ctx) {
        // Check if target count reached
        if (targetCount > 0 && successfulSteals >= targetCount) {
            log.info("Target steal count reached ({})", targetCount);
            logStatistics();
            complete();
            return;
        }

        InventoryState inventory = ctx.getInventoryState();

        if (inventory.isFull()) {
            if (dropWhenFull && !dropItemIds.isEmpty()) {
                log.debug("Inventory full, dropping items");
                recordPhaseTransition(StallPhase.DROP_ITEMS);
                phase = StallPhase.DROP_ITEMS;
                return;
            } else {
                log.info("Inventory full, completing task");
                logStatistics();
                complete();
                return;
            }
        }

        recordPhaseTransition(StallPhase.CHECK_SAFESPOT);
        phase = StallPhase.CHECK_SAFESPOT;
    }

    // ========================================================================
    // Phase: Check Safespot
    // ========================================================================

    private void executeCheckSafespot(TaskContext ctx) {
        if (safespot != null) {
            PlayerState player = ctx.getPlayerState();
            WorldPoint playerPos = player.getWorldPosition();

            if (!playerPos.equals(safespot)) {
                log.warn("Not on safespot! Expected {}, currently at {}", safespot, playerPos);
                // Could add walk-to-safespot logic here, but for now just warn and continue
                // The ThievingSkillTask orchestrator should handle getting us to the safespot
            }
        }

        recordPhaseTransition(StallPhase.FIND_STALL);
        phase = StallPhase.FIND_STALL;
    }

    // ========================================================================
    // Phase: Find Stall
    // ========================================================================

    private void executeFindStall(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        targetStall = findStall(ctx, playerPos);

        if (targetStall == null) {
            log.warn("No stall found within {} tiles", searchRadius);
            fail("No stall found");
            return;
        }

        targetStallPosition = getObjectWorldPoint(targetStall);

        // Initialize interaction helper
        if (interactionHelper == null) {
            interactionHelper = new InteractionHelper(ctx);
        }
        interactionHelper.startCameraRotation(targetStallPosition);

        // Check if we need to wait for respawn
        if (lastStealTime != null) {
            long elapsed = Duration.between(lastStealTime, Instant.now()).toMillis();
            long respawnTime = getEffectiveRespawnTime();

            if (elapsed < respawnTime) {
                log.debug("Waiting for stall respawn ({}/{}ms)", elapsed, respawnTime);
                recordPhaseTransition(StallPhase.WAIT_RESPAWN);
                phase = StallPhase.WAIT_RESPAWN;
                return;
            }
        }

        log.debug("Found stall {} at {}", resolvedStallId, targetStallPosition);
        recordPhaseTransition(StallPhase.WAIT_FOR_CLICKBOX);
        phase = StallPhase.WAIT_FOR_CLICKBOX;
    }

    // ========================================================================
    // Phase: Wait for Clickbox
    // ========================================================================

    private void executeWaitForClickbox(TaskContext ctx) {
        if (targetStall == null) {
            fail("Target stall lost");
            return;
        }

        InteractionHelper.ClickPointResult result = interactionHelper.getClickPointForObject(
                targetStall, targetStallPosition);

        if (result.hasPoint()) {
            recordPhaseTransition(StallPhase.STEAL);
            phase = StallPhase.STEAL;
        } else if (result.shouldRotateCamera) {
            interactionHelper.startCameraRotation(targetStallPosition);
        } else if (result.shouldWait) {
            return;
        } else {
            log.warn("Could not get clickbox for stall: {}", result.reason);
            fail("Cannot interact with stall: " + result.reason);
        }
    }

    // ========================================================================
    // Phase: Wait Respawn
    // ========================================================================

    private void executeWaitRespawn(TaskContext ctx) {
        if (lastStealTime == null) {
            recordPhaseTransition(StallPhase.WAIT_FOR_CLICKBOX);
            phase = StallPhase.WAIT_FOR_CLICKBOX;
            return;
        }

        long elapsed = Duration.between(lastStealTime, Instant.now()).toMillis();
        long respawnTime = getEffectiveRespawnTime();

        if (elapsed >= respawnTime) {
            log.debug("Stall respawned, continuing");
            recordPhaseTransition(StallPhase.WAIT_FOR_CLICKBOX);
            phase = StallPhase.WAIT_FOR_CLICKBOX;
        }
        // Otherwise keep waiting
    }

    // ========================================================================
    // Phase: Steal
    // ========================================================================

    private void executeSteal(TaskContext ctx) {
        if (targetStall == null) {
            phase = StallPhase.FIND_STALL;
            return;
        }

        // Record inventory count for success detection
        inventoryCountBefore = ctx.getInventoryState().getUsedSlots();

        log.debug("Attempting to steal from stall {}", resolvedStallId);
        operationPending = true;

        // Click stall with "Steal-from" action
        ctx.getSafeClickExecutor().clickObject(targetStall, "Steal-from")
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        waitTicks = 0;
                        recordPhaseTransition(StallPhase.WAIT_RESPONSE);
                        phase = StallPhase.WAIT_RESPONSE;
                    } else {
                        log.warn("Failed to click stall");
                        phase = StallPhase.FIND_STALL;
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Steal click failed", e);
                    phase = StallPhase.FIND_STALL;
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait Response
    // ========================================================================

    private void executeWaitResponse(TaskContext ctx) {
        waitTicks++;

        if (waitTicks > STEAL_TIMEOUT_TICKS) {
            log.debug("Steal response timed out");
            scheduleDelayBetween(ctx);
            return;
        }

        PlayerState player = ctx.getPlayerState();
        InventoryState inventory = ctx.getInventoryState();

        // Check for steal animation
        if (player.isAnimating(STEAL_ANIMATION)) {
            successfulSteals++;
            lastStealTime = Instant.now();
            log.debug("Steal successful! (total: {})", successfulSteals);
            scheduleDelayBetween(ctx);
            return;
        }

        // Check for inventory increase (alternate success indicator)
        int currentCount = inventory.getUsedSlots();
        if (currentCount > inventoryCountBefore) {
            successfulSteals++;
            lastStealTime = Instant.now();
            log.debug("Steal successful (inventory increased)! (total: {})", successfulSteals);
            scheduleDelayBetween(ctx);
            return;
        }

        // Check if stall is now empty (graphical change)
        // This varies by stall type and is harder to detect reliably
    }

    // ========================================================================
    // Phase: Drop Items
    // ========================================================================

    private void executeDropItems(TaskContext ctx) {
        if (dropItemIds.isEmpty()) {
            recordPhaseTransition(StallPhase.CHECK_SAFESPOT);
            phase = StallPhase.CHECK_SAFESPOT;
            return;
        }

        InventoryState inventory = ctx.getInventoryState();

        // Find first droppable item
        int slot = -1;
        for (int itemId : dropItemIds) {
            int foundSlot = inventory.getSlotOf(itemId);
            if (foundSlot >= 0) {
                slot = foundSlot;
                break;
            }
        }

        if (slot < 0) {
            // No more items to drop
            recordPhaseTransition(StallPhase.CHECK_SAFESPOT);
            phase = StallPhase.CHECK_SAFESPOT;
            return;
        }

        log.debug("Dropping item from slot {}", slot);
        operationPending = true;

        ctx.getInventoryClickHelper().executeClick(slot, "Drop item")
                .thenAccept(success -> {
                    operationPending = false;
                    // Check if more items to drop
                    phase = StallPhase.DROP_ITEMS;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to drop item", e);
                    phase = StallPhase.CHECK_SAFESPOT;
                    return null;
                });
    }

    // ========================================================================
    // Phase: Delay Between
    // ========================================================================

    private void scheduleDelayBetween(TaskContext ctx) {
        recordPhaseTransition(StallPhase.DELAY_BETWEEN);
        phase = StallPhase.DELAY_BETWEEN;
        waitingForDelay = true;

        ctx.getHumanTimer().sleep(DelayProfile.ACTION_GAP)
                .thenRun(() -> {
                    waitingForDelay = false;
                    phase = StallPhase.CHECK_INVENTORY;
                });
    }

    private void executeDelayBetween(TaskContext ctx) {
        if (!waitingForDelay) {
            phase = StallPhase.CHECK_INVENTORY;
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Find a stall to steal from.
     */
    private TileObject findStall(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();

        int playerPlane = playerPos.getPlane();
        TileObject nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (int x = 0; x < Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[playerPlane][x][y];
                if (tile == null) {
                    continue;
                }

                TileObject obj = findStallOnTile(tile);
                if (obj != null) {
                    WorldPoint objPos = getObjectWorldPoint(obj);
                    int distance = playerPos.distanceTo(objPos);

                    // Check location filter
                    if (stallLocation != null && !objPos.equals(stallLocation)) {
                        continue;
                    }

                    if (distance <= searchRadius && distance < nearestDistance) {
                        nearest = obj;
                        nearestDistance = distance;
                    }
                }
            }
        }

        return nearest;
    }

    /**
     * Find a stall on a specific tile.
     */
    private TileObject findStallOnTile(Tile tile) {
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject obj : gameObjects) {
                if (obj != null && stallIds.contains(obj.getId())) {
                    resolvedStallId = obj.getId();
                    return obj;
                }
            }
        }

        WallObject wallObject = tile.getWallObject();
        if (wallObject != null && stallIds.contains(wallObject.getId())) {
            resolvedStallId = wallObject.getId();
            return wallObject;
        }

        DecorativeObject decorativeObject = tile.getDecorativeObject();
        if (decorativeObject != null && stallIds.contains(decorativeObject.getId())) {
            resolvedStallId = decorativeObject.getId();
            return decorativeObject;
        }

        GroundObject groundObject = tile.getGroundObject();
        if (groundObject != null && stallIds.contains(groundObject.getId())) {
            resolvedStallId = groundObject.getId();
            return groundObject;
        }

        return null;
    }

    /**
     * Get the WorldPoint of a TileObject.
     */
    private WorldPoint getObjectWorldPoint(TileObject obj) {
        if (obj instanceof GameObject) {
            return ((GameObject) obj).getWorldLocation();
        }
        if (obj instanceof WallObject) {
            return ((WallObject) obj).getWorldLocation();
        }
        if (obj instanceof GroundObject) {
            return ((GroundObject) obj).getWorldLocation();
        }
        if (obj instanceof DecorativeObject) {
            return ((DecorativeObject) obj).getWorldLocation();
        }
        return null;
    }

    /**
     * Get the effective respawn time for the current stall type.
     */
    private long getEffectiveRespawnTime() {
        if (respawnTimeMs > 0) {
            return respawnTimeMs;
        }

        // Determine respawn time based on stall type
        if (containsAny(stallIds, ObjectCollections.BAKERY_STALLS)) {
            return BAKERY_RESPAWN_MS;
        }
        if (containsAny(stallIds, ObjectCollections.SILK_STALLS)) {
            return SILK_RESPAWN_MS;
        }
        if (containsAny(stallIds, ObjectCollections.FUR_STALLS)) {
            return FUR_RESPAWN_MS;
        }
        if (containsAny(stallIds, ObjectCollections.SILVER_STALLS)) {
            return SILVER_RESPAWN_MS;
        }
        if (containsAny(stallIds, ObjectCollections.SPICE_STALLS)) {
            return SPICE_RESPAWN_MS;
        }
        if (containsAny(stallIds, ObjectCollections.GEM_STALLS)) {
            return GEM_RESPAWN_MS;
        }
        if (containsAny(stallIds, ObjectCollections.TEA_STALLS)) {
            return TEA_RESPAWN_MS;
        }
        if (containsAny(stallIds, ObjectCollections.FRUIT_STALLS)) {
            return FRUIT_RESPAWN_MS;
        }
        if (containsAny(stallIds, ObjectCollections.SEED_STALLS)) {
            return SEED_RESPAWN_MS;
        }
        if (containsAny(stallIds, ObjectCollections.VEG_STALLS)) {
            return VEG_RESPAWN_MS;
        }

        return DEFAULT_RESPAWN_MS;
    }

    /**
     * Check if any element from list1 is in list2.
     */
    private boolean containsAny(List<Integer> list1, List<Integer> list2) {
        for (int id : list1) {
            if (list2.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Log statistics at completion.
     */
    private void logStatistics() {
        log.info("=== Stall Thieving Statistics ===");
        log.info("Successful steals: {}", successfulSteals);
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("StallThieving[steals=%d, stall=%d]",
                successfulSteals, resolvedStallId);
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum StallPhase {
        CHECK_INVENTORY,
        CHECK_SAFESPOT,
        FIND_STALL,
        WAIT_FOR_CLICKBOX,
        WAIT_RESPAWN,
        STEAL,
        WAIT_RESPONSE,
        DROP_ITEMS,
        DELAY_BETWEEN
    }
}

