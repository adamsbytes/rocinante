package com.rocinante.tasks.impl.skills.prayer;

import com.rocinante.input.InventoryClickHelper;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.InteractionHelper;
import com.rocinante.tasks.TaskContext;
import com.rocinante.timing.DelayProfile;
import com.rocinante.util.ItemCollections;
import com.rocinante.util.ObjectCollections;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ItemComposition;
import net.runelite.api.ObjectID;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Task for offering bones on an altar for Prayer training.
 *
 * <p>Features:
 * <ul>
 *   <li>Offers multiple bones in one execution (configurable max)</li>
 *   <li>Supports any bone types from {@link ItemCollections#BONES}</li>
 *   <li>Supports all altar types including Chaos Altar and Gilded Altars</li>
 *   <li>Tracks Chaos Altar 50% bone save mechanic</li>
 *   <li>Humanized delays between offerings</li>
 * </ul>
 *
 * <p>Chaos Altar Mechanic:
 * The Chaos Altar in level 38 Wilderness has a 50% chance to NOT consume the bone
 * when offering, effectively doubling XP per bone on average. This task detects
 * when the bone is saved and continues offering the same bone without counting
 * it as a new bone consumed.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Offer all dragon bones on any nearby altar
 * AltarOfferTask offerBones = new AltarOfferTask(List.of(ItemID.DRAGON_BONES));
 *
 * // Offer bones specifically on Chaos Altar (tracks 50% save)
 * AltarOfferTask chaosOffer = new AltarOfferTask(List.of(ItemID.DRAGON_BONES))
 *     .withAltarIds(ObjectCollections.CHAOS_ALTARS);
 *
 * // Offer up to 26 bones (full inventory minus 2 for supplies)
 * AltarOfferTask limitedOffer = new AltarOfferTask()
 *     .withMaxBones(26);
 * }</pre>
 *
 * @see com.rocinante.tasks.impl.BuryBonesTask for basic bone burying
 */
@Slf4j
public class AltarOfferTask extends AbstractTask {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Chaos Altar object ID. Has 50% chance to NOT consume bone when offering.
     */
    private static final int CHAOS_ALTAR_ID = ObjectID.CHAOS_ALTAR;

    /**
     * Animation ID for offering bones at altar.
     */
    private static final int OFFER_ANIMATION_ID = 896;

    /**
     * Maximum ticks to wait for offer animation response.
     */
    private static final int OFFER_TIMEOUT_TICKS = 10;

    /**
     * Ticks to wait after offer before checking bone consumption.
     */
    private static final int POST_OFFER_CHECK_TICKS = 3;

    /**
     * Search radius for finding altar (tiles).
     */
    private static final int DEFAULT_SEARCH_RADIUS = 15;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Bone item IDs to offer. Defaults to all bones from ItemCollections.
     */
    @Getter
    private final List<Integer> boneIds;

    /**
     * Altar object IDs to use. Defaults to all bone offering altars.
     */
    @Getter
    private List<Integer> altarIds;

    /**
     * Maximum bones to offer in this execution. -1 means no limit.
     */
    @Getter
    @Setter
    private int maxBones = -1;

    /**
     * Search radius for finding altar.
     */
    @Getter
    @Setter
    private int searchRadius = DEFAULT_SEARCH_RADIUS;

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
    private OfferPhase phase = OfferPhase.FIND_ALTAR;

    /**
     * The altar object we found.
     */
    private TileObject targetAltar;

    /**
     * World position of the altar.
     */
    private WorldPoint altarPosition;

    /**
     * The resolved altar ID.
     */
    private int resolvedAltarId = -1;

    /**
     * The current bone ID being offered.
     */
    private int currentBoneId = -1;

    /**
     * Bone count before offering (for tracking consumption).
     */
    private int boneCountBeforeOffer = 0;

    /**
     * Number of bones consumed (actually used up).
     */
    @Getter
    private int bonesConsumed = 0;

    /**
     * Number of successful offer actions performed.
     */
    @Getter
    private int offersPerformed = 0;

    /**
     * Number of times bones were saved at Chaos Altar.
     */
    @Getter
    private int bonesSaved = 0;

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
     * Whether we're at the Chaos Altar (for 50% save mechanic).
     */
    private boolean isChaosAltar = false;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create an altar offer task that will offer any bones from ItemCollections.BONES.
     */
    public AltarOfferTask() {
        this.boneIds = new ArrayList<>(ItemCollections.BONES);
        this.altarIds = new ArrayList<>(ObjectCollections.BONE_OFFERING_ALTARS);
    }

    /**
     * Create an altar offer task for specific bone types.
     *
     * @param boneIds the bone item IDs to offer
     */
    public AltarOfferTask(Collection<Integer> boneIds) {
        this.boneIds = new ArrayList<>(boneIds);
        this.altarIds = new ArrayList<>(ObjectCollections.BONE_OFFERING_ALTARS);
    }

    /**
     * Create an altar offer task for specific bone types on specific altars.
     *
     * @param boneIds  the bone item IDs to offer
     * @param altarIds the altar object IDs to use
     */
    public AltarOfferTask(Collection<Integer> boneIds, Collection<Integer> altarIds) {
        this.boneIds = new ArrayList<>(boneIds);
        this.altarIds = new ArrayList<>(altarIds);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set altar IDs to use (builder-style).
     *
     * @param altarIds acceptable altar object IDs
     * @return this task for chaining
     */
    public AltarOfferTask withAltarIds(Collection<Integer> altarIds) {
        this.altarIds = new ArrayList<>(altarIds);
        return this;
    }

    /**
     * Set maximum bones to offer (builder-style).
     *
     * @param max maximum bones to offer, or -1 for no limit
     * @return this task for chaining
     */
    public AltarOfferTask withMaxBones(int max) {
        this.maxBones = max;
        return this;
    }

    /**
     * Set search radius (builder-style).
     *
     * @param radius search radius in tiles
     * @return this task for chaining
     */
    public AltarOfferTask withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public AltarOfferTask withDescription(String description) {
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

        // Check if we have any bones to offer
        InventoryState inventory = ctx.getInventoryState();
        int totalBones = countBonesInInventory(inventory);

        if (totalBones == 0) {
            log.debug("Cannot offer bones: no bones in inventory");
            return false;
        }

        // Check if InventoryClickHelper is available
        if (ctx.getInventoryClickHelper() == null) {
            log.error("InventoryClickHelper not available in TaskContext");
            return false;
        }

        log.debug("AltarOfferTask ready: {} bones in inventory", totalBones);
        return true;
    }

    @Override
    protected void resetImpl() {
        phase = OfferPhase.FIND_ALTAR;
        targetAltar = null;
        altarPosition = null;
        resolvedAltarId = -1;
        currentBoneId = -1;
        boneCountBeforeOffer = 0;
        waitTicks = 0;
        operationPending = false;
        waitingForDelay = false;
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
            case FIND_ALTAR:
                executeFindAltar(ctx);
                break;
            case WAIT_FOR_CLICKBOX:
                executeWaitForClickbox(ctx);
                break;
            case FIND_BONE:
                executeFindBone(ctx);
                break;
            case CLICK_BONE:
                executeClickBone(ctx);
                break;
            case CLICK_ALTAR:
                executeClickAltar(ctx);
                break;
            case WAIT_OFFER:
                executeWaitOffer(ctx);
                break;
            case CHECK_CONSUMPTION:
                executeCheckConsumption(ctx);
                break;
            case DELAY_BETWEEN:
                executeDelayBetween(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Find Altar
    // ========================================================================

    private void executeFindAltar(TaskContext ctx) {
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        targetAltar = findNearestAltar(ctx, playerPos);

        if (targetAltar == null) {
            log.warn("No altar from {} found within {} tiles", altarIds, searchRadius);
            fail("Altar not found");
            return;
        }

        altarPosition = getObjectWorldPoint(targetAltar);
        isChaosAltar = (resolvedAltarId == CHAOS_ALTAR_ID);

        // Initialize interaction helper and start camera rotation
        interactionHelper = new InteractionHelper(ctx);
        interactionHelper.startCameraRotation(altarPosition);

        log.info("Found altar {} at {} (Chaos Altar: {})", resolvedAltarId, altarPosition, isChaosAltar);
        recordPhaseTransition(OfferPhase.WAIT_FOR_CLICKBOX);
        phase = OfferPhase.WAIT_FOR_CLICKBOX;
    }

    // ========================================================================
    // Phase: Wait for Clickbox
    // ========================================================================

    private void executeWaitForClickbox(TaskContext ctx) {
        if (targetAltar == null) {
            fail("Target altar lost");
            return;
        }

        InteractionHelper.ClickPointResult result = interactionHelper.getClickPointForObject(
                targetAltar, altarPosition);

        if (result.hasPoint()) {
            log.debug("Altar clickbox ready");
            recordPhaseTransition(OfferPhase.FIND_BONE);
            phase = OfferPhase.FIND_BONE;
        } else if (result.shouldRotateCamera) {
            interactionHelper.startCameraRotation(altarPosition);
        } else if (result.shouldWait) {
            // Keep waiting
            return;
        } else {
            log.warn("Could not get clickbox for altar: {}", result.reason);
            fail("Cannot interact with altar: " + result.reason);
        }
    }

    // ========================================================================
    // Phase: Find Bone
    // ========================================================================

    private void executeFindBone(TaskContext ctx) {
        // Check if we've hit the max limit (based on bones consumed, not offers)
        if (maxBones > 0 && bonesConsumed >= maxBones) {
            log.info("Reached max bones limit ({} consumed), completing task", bonesConsumed);
            logStatistics();
            complete();
            return;
        }

        InventoryState inventory = ctx.getInventoryState();

        // Find first bone in inventory
        currentBoneId = findFirstBone(inventory);

        if (currentBoneId == -1) {
            // No more bones to offer
            if (offersPerformed > 0) {
                log.info("Finished offering bones (no more bones in inventory)");
                logStatistics();
                complete();
            } else {
                fail("No bones found in inventory");
            }
            return;
        }

        boneCountBeforeOffer = inventory.countItem(currentBoneId);
        log.debug("Found bone {} (count: {}) to offer", currentBoneId, boneCountBeforeOffer);

        recordPhaseTransition(OfferPhase.CLICK_BONE);
        phase = OfferPhase.CLICK_BONE;
    }

    // ========================================================================
    // Phase: Click Bone
    // ========================================================================

    private void executeClickBone(TaskContext ctx) {
        InventoryClickHelper inventoryHelper = ctx.getInventoryClickHelper();
        InventoryState inventory = ctx.getInventoryState();

        int slot = inventory.getSlotOf(currentBoneId);

        if (slot < 0) {
            log.debug("Bone {} no longer in inventory, finding next", currentBoneId);
            phase = OfferPhase.FIND_BONE;
            return;
        }

        log.debug("Clicking bone {} in slot {} to use", currentBoneId, slot);
        operationPending = true;

        // Click the bone to enter "Use" mode
        inventoryHelper.executeClick(slot, "Use bone " + currentBoneId)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        recordPhaseTransition(OfferPhase.CLICK_ALTAR);
                        phase = OfferPhase.CLICK_ALTAR;
                    } else {
                        log.warn("Failed to click bone in inventory");
                        phase = OfferPhase.FIND_BONE;
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Exception clicking bone", e);
                    fail("Click failed: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Click Altar
    // ========================================================================

    private void executeClickAltar(TaskContext ctx) {
        if (targetAltar == null) {
            fail("Target altar lost");
            return;
        }

        log.debug("Clicking altar {} with bone {}", resolvedAltarId, currentBoneId);
        operationPending = true;

        // Get bone name for menu matching
        String boneName = getBoneName(ctx, currentBoneId);

        // Click altar with "Use" action
        ctx.getSafeClickExecutor().clickObject(targetAltar, "Use", boneName)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        waitTicks = 0;
                        recordPhaseTransition(OfferPhase.WAIT_OFFER);
                        phase = OfferPhase.WAIT_OFFER;
                    } else {
                        log.warn("Failed to click altar");
                        // Try finding bone again
                        phase = OfferPhase.FIND_BONE;
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Exception clicking altar", e);
                    fail("Click failed: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait Offer
    // ========================================================================

    private void executeWaitOffer(TaskContext ctx) {
        waitTicks++;

        if (waitTicks > OFFER_TIMEOUT_TICKS) {
            log.debug("Offer wait timed out, checking consumption anyway");
            recordPhaseTransition(OfferPhase.CHECK_CONSUMPTION);
            phase = OfferPhase.CHECK_CONSUMPTION;
            waitTicks = 0;
            return;
        }

        PlayerState player = ctx.getPlayerState();

        // Check for offer animation
        if (player.isAnimating(OFFER_ANIMATION_ID)) {
            log.debug("Offer animation detected");
            offersPerformed++;
            // Wait a bit more for the actual consumption
            waitTicks = 0;
            recordPhaseTransition(OfferPhase.CHECK_CONSUMPTION);
            phase = OfferPhase.CHECK_CONSUMPTION;
            return;
        }

        // Also check for any animation (fallback)
        if (player.isAnimating() && waitTicks >= 2) {
            log.debug("Animation detected (not specific offer animation)");
            offersPerformed++;
            waitTicks = 0;
            recordPhaseTransition(OfferPhase.CHECK_CONSUMPTION);
            phase = OfferPhase.CHECK_CONSUMPTION;
        }
    }

    // ========================================================================
    // Phase: Check Consumption (Chaos Altar bone save detection)
    // ========================================================================

    private void executeCheckConsumption(TaskContext ctx) {
        waitTicks++;

        // Wait a few ticks for inventory to update
        if (waitTicks < POST_OFFER_CHECK_TICKS) {
            return;
        }

        InventoryState inventory = ctx.getInventoryState();
        int currentBoneCount = inventory.countItem(currentBoneId);

        if (currentBoneCount < boneCountBeforeOffer) {
            // Bone was consumed
            bonesConsumed++;
            log.debug("Bone {} consumed ({} -> {})", currentBoneId, boneCountBeforeOffer, currentBoneCount);
        } else if (isChaosAltar && currentBoneCount == boneCountBeforeOffer) {
            // Chaos Altar saved the bone!
            bonesSaved++;
            log.debug("Chaos Altar saved bone {} (50% proc, total saved: {})", currentBoneId, bonesSaved);
        }

        // Schedule delay before next offer
        scheduleDelayBetweenOffers(ctx);
    }

    // ========================================================================
    // Phase: Delay Between
    // ========================================================================

    private void scheduleDelayBetweenOffers(TaskContext ctx) {
        // Check if we should continue offering
        if (maxBones > 0 && bonesConsumed >= maxBones) {
            log.info("Reached max bones limit ({} consumed), completing task", bonesConsumed);
            logStatistics();
            complete();
            return;
        }

        // Check if more bones exist
        InventoryState inventory = ctx.getInventoryState();
        if (countBonesInInventory(inventory) == 0) {
            log.info("Finished offering bones (no more bones)");
            logStatistics();
            complete();
            return;
        }

        // Schedule humanized delay before next offer
        recordPhaseTransition(OfferPhase.DELAY_BETWEEN);
        phase = OfferPhase.DELAY_BETWEEN;
        waitingForDelay = true;

        ctx.getHumanTimer().sleep(DelayProfile.ACTION_GAP)
                .thenRun(() -> {
                    waitingForDelay = false;
                    phase = OfferPhase.FIND_BONE;
                });
    }

    private void executeDelayBetween(TaskContext ctx) {
        // This phase is handled by async delay scheduling
        if (!waitingForDelay) {
            phase = OfferPhase.FIND_BONE;
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Find the first bone in inventory from our bone list.
     */
    private int findFirstBone(InventoryState inventory) {
        for (int boneId : boneIds) {
            if (inventory.hasItem(boneId)) {
                return boneId;
            }
        }
        return -1;
    }

    /**
     * Count total bones in inventory from our bone list.
     */
    private int countBonesInInventory(InventoryState inventory) {
        int total = 0;
        for (int boneId : boneIds) {
            total += inventory.countItem(boneId);
        }
        return total;
    }

    /**
     * Get bone name from client definitions.
     */
    private String getBoneName(TaskContext ctx, int boneId) {
        try {
            ItemComposition def = ctx.getClient().getItemDefinition(boneId);
            return def != null ? def.getName() : null;
        } catch (Exception e) {
            log.debug("Could not get bone name for {}", boneId);
            return null;
        }
    }

    /**
     * Find the nearest altar object.
     */
    private TileObject findNearestAltar(TaskContext ctx, WorldPoint playerPos) {
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

                TileObject obj = findAltarOnTile(tile);
                if (obj != null) {
                    WorldPoint objPos = getObjectWorldPoint(obj);
                    int distance = playerPos.distanceTo(objPos);

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
     * Find an altar on a specific tile.
     */
    private TileObject findAltarOnTile(Tile tile) {
        // Check game objects
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject obj : gameObjects) {
                if (obj != null && altarIds.contains(obj.getId())) {
                    resolvedAltarId = obj.getId();
                    return obj;
                }
            }
        }

        // Check wall object
        WallObject wallObject = tile.getWallObject();
        if (wallObject != null && altarIds.contains(wallObject.getId())) {
            resolvedAltarId = wallObject.getId();
            return wallObject;
        }

        // Check decorative object
        DecorativeObject decorativeObject = tile.getDecorativeObject();
        if (decorativeObject != null && altarIds.contains(decorativeObject.getId())) {
            resolvedAltarId = decorativeObject.getId();
            return decorativeObject;
        }

        // Check ground object
        GroundObject groundObject = tile.getGroundObject();
        if (groundObject != null && altarIds.contains(groundObject.getId())) {
            resolvedAltarId = groundObject.getId();
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
     * Log statistics at completion.
     */
    private void logStatistics() {
        log.info("=== Altar Offer Statistics ===");
        log.info("Offers performed: {}", offersPerformed);
        log.info("Bones consumed: {}", bonesConsumed);
        if (isChaosAltar) {
            log.info("Bones saved (Chaos Altar): {}", bonesSaved);
            double saveRate = offersPerformed > 0 
                    ? (double) bonesSaved / offersPerformed * 100 
                    : 0;
            log.info("Effective save rate: {:.1f}% (expected: 50%)", saveRate);
        }
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        String altarDesc = isChaosAltar ? "Chaos Altar" : "Altar";
        return String.format("AltarOffer[%s, offers=%d, consumed=%d, saved=%d]",
                altarDesc, offersPerformed, bonesConsumed, bonesSaved);
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum OfferPhase {
        /** Find the altar object */
        FIND_ALTAR,
        /** Wait for altar clickbox to be ready */
        WAIT_FOR_CLICKBOX,
        /** Find next bone to offer */
        FIND_BONE,
        /** Click the bone in inventory */
        CLICK_BONE,
        /** Click the altar with the bone */
        CLICK_ALTAR,
        /** Wait for offer animation */
        WAIT_OFFER,
        /** Check if bone was consumed (Chaos Altar save detection) */
        CHECK_CONSUMPTION,
        /** Humanized delay between offers */
        DELAY_BETWEEN
    }
}

